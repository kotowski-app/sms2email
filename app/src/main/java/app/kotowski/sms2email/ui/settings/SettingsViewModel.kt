package app.kotowski.sms2email.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.DedupLevel
import app.kotowski.sms2email.LogLevel
import app.kotowski.sms2email.LogSizePreset
import app.kotowski.sms2email.PeriodPreset
import app.kotowski.sms2email.R
import app.kotowski.sms2email.SweepIntervalPreset
import app.kotowski.sms2email.ThemeMode
import app.kotowski.sms2email.data.filter.SmsFilter
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.data.prefs.ThemePreference
import app.kotowski.sms2email.service.EmailBuilder
import app.kotowski.sms2email.service.EmailResult
import app.kotowski.sms2email.service.SettingsExportImport
import app.kotowski.sms2email.service.SettingsImporter
import app.kotowski.sms2email.service.SmtpTestRunner
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.system.PermissionManager
import app.kotowski.sms2email.ui.emailconfig.EmailConfig
import app.kotowski.sms2email.ui.emailconfig.EmailConfigController
import app.kotowski.sms2email.ui.emailconfig.isEmailFormComplete
import app.kotowski.sms2email.ui.emailconfig.showTestResultToast
import app.kotowski.sms2email.util.LogBuffer
import app.kotowski.sms2email.util.Logger
import app.kotowski.sms2email.util.ToastUtil
import app.kotowski.sms2email.work.BackgroundStateReconciler
import app.kotowski.sms2email.work.ExactWakeArmer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import javax.inject.Inject

// The confirms of the retention editor: enabling starts the deletions, and
// shortening deletes the messages the shorter period no longer covers — both
// commits wait for the explicit OK.
enum class RetentionConfirm {
    ENABLE,
    SHORTEN,
}

// The confirms of the log-to-file editor: turning the setting off deletes the
// log file, and a smaller size truncates it to the new cap — both commits
// wait for the explicit OK. Enabling and growing are confirm-free (they delete
// nothing).
enum class LogToFileConfirm {
    DISABLE,
    SHRINK,
}

// The retention editor's decision state: the committed half (enabled, storedDays)
// mirrors the stored period (a non-zero period is on, 0 is off), the editing half
// (draftDays, editing, confirm) the in-progress change that only the VM's events
// commit. The period proposed when the user turns the cleanup on for the first time
// is 1 year (PeriodPreset.ONE_YEAR), so enabling must not clear the history the user
// has accumulated.
data class RetentionUiState(
    val enabled: Boolean = false,
    val storedDays: Int = PeriodPreset.ONE_YEAR.days,
    val draftDays: Int = PeriodPreset.ONE_YEAR.days,
    val editing: Boolean = false,
    val confirm: RetentionConfirm? = null,
)

// The log-to-file editor's decision state: the committed half (enabled, storedSize)
// mirrors the stored settings, the editing half (draftSize, editing, confirm) the
// in-progress change that only the VM's events commit. The size proposed when the
// user turns the setting on for the first time is the shipped default (256 KB);
// disabling keeps the stored size, so the next enable re-proposes it.
data class LogToFileUiState(
    val enabled: Boolean = false,
    val storedSize: Int = LogSizePreset.DEFAULT.bytes,
    val draftSize: Int = LogSizePreset.DEFAULT.bytes,
    val editing: Boolean = false,
    val confirm: LogToFileConfirm? = null,
)

data class SettingsState(
    // The shared email-config fields (the form's state, one-to-one).
    val config: EmailConfig = EmailConfig(),
    // The subject as a forwarded email would read it with the current settings
    // (the placeholder sender, the optional SIM suffix); the screen renders it.
    val subjectPreview: String = "",
    // The retention editor's decision state (the committed half mirrors the stored
    // settings); the screen renders it and forwards the events.
    val retention: RetentionUiState = RetentionUiState(),
    // The log-to-file editor's decision state (same committed/editing split).
    val logToFile: LogToFileUiState = LogToFileUiState(),
    val useJsonEmailFormat: Boolean = false,
    // The time line in the plain-text email body, off by default (the user opts in).
    val includeTimeInBody: Boolean = AppSettings.DEFAULT_INCLUDE_TIME_IN_BODY,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    // The live receive's stored time (the PDU service-center timestamp when on).
    val usePduTimestamp: Boolean = false,
    val dedupLevel: DedupLevel = DedupLevel.BALANCED,
    val logLevel: LogLevel = LogLevel.DEFAULT,
    // The stored intent: the sticky-notification toggle as the user set it, independent of
    // the permission.
    val showStickyNotification: Boolean = AppSettings.DEFAULT_STICKY_NOTIFICATION,
    // The live POST_NOTIFICATIONS state, UI-cached; unconditionally true below API 33
    // because the manifest permission is granted at install there (the snapshot already
    // returns true below 33, so no SDK gate is needed here).
    val notificationsGranted: Boolean = true,
    // The recovery-sweep period as the user set it; a non-preset stored value degrades to
    // the default in the UI and in the engine alike (the reconciler degrades it on every
    // pass, so the two cannot disagree).
    val retrySweepIntervalMinutes: Int = AppSettings.DEFAULT_RETRY_SWEEP_INTERVAL_MINUTES,
    // The exact-wake-up toggle as the user set it, independent of the permission.
    val exactWakeEnabled: Boolean = AppSettings.DEFAULT_EXACT_WAKE_ENABLED,
    // The committed message filter (the summary card's view-only rendering; the edit
    // lives on its own screen with its own draft).
    val filter: SmsFilter = SmsFilter(),
    // The message-filter feature toggle (the explicit opt-in): the card renders only
    // while on, and the engine evaluates the filter only while on.
    val filterEnabled: Boolean = AppSettings.DEFAULT_FILTER_ENABLED,
    // The live "Alarms & reminders" state, UI-cached; unconditionally true below API 31
    // where the permission does not exist.
    val exactAlarmsGranted: Boolean = true,
    // The country field's error: the manual code that is not a valid ISO country,
    // or null (while auto is on the field is disabled and the code is not shown).
    val countryError: String? = null,
    // The value the country field displays: the detected region while auto is on
    // (the stored code is only the pure auto marker until the user opts out),
    // the stored code otherwise.
    val countryValue: String = "",
)

// The outcome of an email-config save attempt: committed, blocked by the
// completeness rule, or waiting for the untested-settings confirmation.
enum class EmailSaveOutcome {
    SAVED,
    INCOMPLETE,
    NEEDS_CONFIRM,
}

// The test half of the save gate: the test email must have succeeded (the
// connection test is not part of it - a sent test email already proves the
// connection); the completeness half is checked by the gate itself, so each
// half is evaluated once. The form clears the test email result on every edit
// of a field the test run exercises (the SMTP parameters, the sender and dest
// emails and names), so a stale success can never gate a value the run verified.
internal fun isEmailSaveReady(config: EmailConfig): Boolean = config.test.testEmailResult is EmailResult.Success

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val prefs: EncryptedPrefs,
        private val testRunner: SmtpTestRunner,
        private val contactResolver: ContactResolver,
        // Exposed so the screen calls the manager and the shared request flow directly,
        // no delegates here.
        val permissionManager: PermissionManager,
        private val themePreference: ThemePreference,
        private val backgroundStateReconciler: BackgroundStateReconciler,
        private val exactWakeArmer: ExactWakeArmer,
    ) : ViewModel() {
        // Sanitized before the form reads the prefs, so the field never starts
        // from a dead value.
        private val initialSettings: AppSettings = sanitizeCountry(prefs.load())

        // The non-config half of SettingsState: its config and retention fields are
        // default placeholders, the combine below always overwrites them with the
        // form's live config and the editor's decision state.
        private val extrasState = MutableStateFlow(extrasFromPrefs(initialSettings))

        // The retention editor's decision state: a separate flow, because refreshState()
        // re-seeds only its committed half (the in-progress edit must survive a resume)
        // and the editor's events own the editing half.
        private val retentionState = MutableStateFlow(retentionFromPrefs(initialSettings))

        // The log-to-file editor's decision state: the same split as the retention editor's.
        private val logToFileState = MutableStateFlow(logToFileFromPrefs(initialSettings))

        // Exposed so the screen calls the form's field handlers directly, no per-field
        // delegates here.
        val form =
            EmailConfigController(
                appContext,
                prefs,
                testRunner,
                contactResolver,
            )

        // The settings import: the read and the apply run on the ViewModel's scope (not the
        // composition's), so an in-flight import survives a rotation and is dropped only
        // when the settings entry is left. The hook re-reads the fields that live outside
        // the form's state so the state mirrors the imported prefs.
        val importRunner =
            SettingsImporter(appContext, prefs) {
                // The import carries the country keys too, so the re-read runs
                // the same sanitize before the form loads the result.
                val settings = sanitizeCountry(prefs.load())
                form.reloadFromPrefs(keepTestRuns = true)
                extrasState.value = extrasFromPrefs(settings)
                // The imported threshold applies to the live logger right away.
                Logger.setLevel(settings.logLevel)
                syncRetentionCommitted(settings)
                syncLogToFileCommitted(settings)
            }

        // The seed carries the computed preview and the editors' decision state too, so
        // the first frame is correct by construction, independent of when the combine's
        // first emission lands.
        private val initialState: SettingsState =
            extrasState.value.copy(
                config = form.state.value,
                subjectPreview = subjectPreview(form.state.value, extrasState.value),
                retention = retentionState.value,
                logToFile = logToFileState.value,
                countryError = countryError(form.state.value),
                countryValue = countryValue(form.state.value),
            )

        val state: StateFlow<SettingsState> =
            combine(form.state, extrasState, retentionState, logToFileState) { config, extras, retention, logToFile ->
                extras.copy(
                    config = config,
                    subjectPreview = subjectPreview(config, extras),
                    retention = retention,
                    logToFile = logToFile,
                    countryError = countryError(config),
                    countryValue = countryValue(config),
                )
            }.stateIn(viewModelScope, SharingStarted.Eagerly, initialState)

        // The subject preview, evaluated once per state change: the sender placeholder
        // follows the contact resolution, the SIM suffix the display setting,
        // and the prefix the form's live draft — the same selection EmailBuilder applies
        // to a real message, formatted through the shared template.
        private fun subjectPreview(
            config: EmailConfig,
            extras: SettingsState,
        ): String {
            val sender =
                appContext.getString(
                    if (config.settings.useContactResolution) {
                        R.string.settings_subject_preview_sender
                    } else {
                        R.string.settings_subject_preview_phone
                    },
                )
            val simSuffix =
                if (config.settings.displaySimCards) {
                    appContext.getString(R.string.settings_subject_preview_carrier)
                } else {
                    null
                }
            return EmailBuilder.format(appContext, config.settings.subjectPrefix, sender, simSuffix)
        }

        // The stored country's one-time heal: a manual code that is blank or not a
        // valid ISO country resolves to the detected region anyway (the resolver
        // treats both as no override), so the toggle left off with such a code (a
        // cleared field, turning auto off before a code was ever set, an imported
        // value) is a lie about what the app uses. On a settings
        // open/refresh/import it resets to the shipped default (auto, the detected
        // region); a valid manual code is left alone and no write happens for it.
        private fun sanitizeCountry(settings: AppSettings): AppSettings {
            val code = settings.countryCode.trim().uppercase(Locale.ROOT)
            if (settings.autoCountry || (code.isNotEmpty() && code in Locale.getISOCountries())) return settings
            val cleaned = settings.copy(autoCountry = true, countryCode = contactResolver.detectedRegion())
            prefs.update { it.copy(autoCountry = cleaned.autoCountry, countryCode = cleaned.countryCode) }
            return cleaned
        }

        // The country field's error: the non-blank manual code that is not a valid ISO
        // country; null while auto is on (the field is disabled then) or the code blank.
        // Resolution keeps using the detected region in that state (the resolver treats
        // an invalid code as no override), so the error flags the code the field shows
        // (a rejected keystroke included) without breaking resolution.
        private fun countryError(config: EmailConfig): String? {
            val settings = config.settings
            if (settings.autoCountry || settings.countryCode.isBlank()) return null
            val code = settings.countryCode.uppercase(Locale.ROOT)
            return if (Locale.getISOCountries().contains(code)) {
                null
            } else {
                appContext.getString(R.string.settings_country_code_error)
            }
        }

        // The value the country field displays: while auto is on the detected region,
        // the effective value (the stored code stays the pure auto marker until the
        // user opts out), the stored code otherwise.
        private fun countryValue(config: EmailConfig): String {
            val settings = config.settings
            return if (settings.autoCountry) contactResolver.detectedRegion() else settings.countryCode
        }

        // The period shown in the editor dropdown: the stored one while the cleanup is on
        // (a non-zero period) and it is one of the presets, the default proposal otherwise.
        // A stored value outside the presets (unknown/tampered) degrades to the default
        // in the UI, the engine keeps using the stored value until the next save.
        private fun storedRetentionDays(settings: AppSettings): Int =
            if (settings.autoCleanupRetentionDays != 0) {
                settings.autoCleanupRetentionDays.takeIf { it in PeriodPreset.days }
                    ?: PeriodPreset.ONE_YEAR.days
            } else {
                PeriodPreset.ONE_YEAR.days
            }

        // The editor's state from the stored settings: the committed half re-seeded on
        // every refresh, the draft starting from the same proposal.
        private fun retentionFromPrefs(settings: AppSettings): RetentionUiState {
            val stored = storedRetentionDays(settings)
            return RetentionUiState(
                enabled = settings.autoCleanupRetentionDays != 0,
                storedDays = stored,
                draftDays = stored,
            )
        }

        // The size shown in the editor: the stored one when it is a preset, the default
        // proposal otherwise. Unlike the retention (which stores 0 while off), the size
        // survives a disable, so this is the same read in both states. A stored value
        // outside the presets (unknown/tampered) degrades to the default here and in the
        // engine's startup read (Sms2EmailApp.logFileSizeFor).
        private fun storedLogFileSize(settings: AppSettings): Int =
            settings.logFileSizeBytes.takeIf { it in LogSizePreset.bytes }
                ?: LogSizePreset.DEFAULT.bytes

        // The editor's state from the stored settings: the committed half re-seeded on
        // every refresh, the draft starting from the same value.
        private fun logToFileFromPrefs(settings: AppSettings): LogToFileUiState {
            val stored = storedLogFileSize(settings)
            return LogToFileUiState(
                enabled = settings.logToFileEnabled,
                storedSize = stored,
                draftSize = stored,
            )
        }

        private fun extrasFromPrefs(settings: AppSettings): SettingsState =
            SettingsState(
                useJsonEmailFormat = settings.useJsonEmailFormat,
                includeTimeInBody = settings.includeTimeInBody,
                themeMode = prefs.themeMode,
                usePduTimestamp = settings.usePduTimestamp,
                dedupLevel = settings.dedupLevel,
                logLevel = settings.logLevel,
                showStickyNotification = settings.showStickyNotification,
                // The live read (not the stored one): a resume refresh re-seeds it, which is
                // what the permission row's active state must reflect.
                notificationsGranted = permissionManager.snapshot().notifications,
                retrySweepIntervalMinutes =
                    settings.retrySweepIntervalMinutes.takeIf { it in SweepIntervalPreset.minutes }
                        ?: AppSettings.DEFAULT_RETRY_SWEEP_INTERVAL_MINUTES,
                exactWakeEnabled = settings.exactWakeEnabled,
                exactAlarmsGranted = permissionManager.canScheduleExactAlarms(),
                filter = settings.filter,
                filterEnabled = settings.filterEnabled,
            )

        fun refreshState() {
            // The email edit lives on its own screen (with its own form), so a
            // refresh of this screen (returning from it, a permission grant) always
            // re-reads the stored config; the extras reload with it. The sanitize
            // runs before the form's re-read, so a dead value is gone by the time
            // the field loads it.
            val settings = sanitizeCountry(prefs.load())
            form.reloadFromPrefs()
            extrasState.value = extrasFromPrefs(settings)
            syncRetentionCommitted(settings)
            syncLogToFileCommitted(settings)
        }

        // A required permission revoked while the screen was away must not leave the
        // dependent toggle on: the re-read settings are what get checked, so the call
        // belongs right after refreshState().
        fun syncPermissionGatedToggles() {
            val settings = form.state.value.settings
            val permissions = permissionManager.snapshot()
            if (settings.useContactResolution && !permissions.contacts) {
                form.onUseContactResolutionChanged(false)
            }
            if (settings.displaySimCards && !permissions.phone) {
                form.onDisplaySimCardsChanged(false)
            }
        }

        // The save gate: an incomplete live form (a red or empty port field among
        // the rest) is blocked with a toast, a config with a succeeded test email
        // commits silently, an unedited form commits silently (the untested-settings
        // warning is about changed settings, so a draft equal to the stored config
        // has nothing to warn about), anything else asks for the explicit
        // "save anyway".
        fun requestSaveEmail(): EmailSaveOutcome {
            val config = form.state.value
            if (!isEmailFormComplete(config)) return EmailSaveOutcome.INCOMPLETE
            if (isEmailSaveReady(config) || !form.hasChanges()) {
                commitEmailDraft()
                return EmailSaveOutcome.SAVED
            }
            return EmailSaveOutcome.NEEDS_CONFIRM
        }

        // The "save anyway" confirm: the live-form completeness rule is the only
        // remaining guard (the user just accepted the untested state).
        fun confirmSaveAnyway(): EmailSaveOutcome {
            if (!isEmailFormComplete(form.state.value)) {
                return EmailSaveOutcome.INCOMPLETE
            }
            commitEmailDraft()
            return EmailSaveOutcome.SAVED
        }

        private fun commitEmailDraft() {
            form.commitDraft()
            form.reloadFromPrefs()
        }

        // The re-read settings change only the committed half of the retention editor:
        // the in-progress edit (the draft, the open confirm) must survive the refresh.
        private fun syncRetentionCommitted(settings: AppSettings) {
            retentionState.update {
                it.copy(
                    enabled = settings.autoCleanupRetentionDays != 0,
                    storedDays = storedRetentionDays(settings),
                )
            }
        }

        // The re-read settings change only the committed half of the log-to-file editor
        // (the in-progress edit and the open confirm survive the refresh).
        private fun syncLogToFileCommitted(settings: AppSettings) {
            logToFileState.update {
                it.copy(
                    enabled = settings.logToFileEnabled,
                    storedSize = storedLogFileSize(settings),
                )
            }
        }

        // The single commit of the retention editor: off stores 0 (nothing deleted), on
        // stores the preset. Writing the period is the whole reconcile: the background
        // flags derive the auto-cleanup state from the stored period, so the
        // BackgroundStateReconciler enqueues or cancels the periodic cleanup in response,
        // and expired messages are deleted right away instead of waiting for the next
        // daily sweep (the bound the toast promises).
        internal fun commitRetention(
            enabled: Boolean,
            days: Int,
        ) {
            if (days !in PeriodPreset.days) return
            val wasEnabled = retentionState.value.enabled
            prefs.update { it.copy(autoCleanupRetentionDays = if (enabled) days else 0) }
            retentionState.update {
                it.copy(
                    enabled = enabled,
                    storedDays = if (enabled) days else PeriodPreset.ONE_YEAR.days,
                )
            }
            if (enabled && !wasEnabled) {
                ToastUtil.show(appContext, R.string.settings_cleanup_will_run)
            }
        }

        // The retention editor's decision events: the screen forwards the user input and
        // renders the resulting state, the branches live here.

        // Turning on commits nothing by itself: it opens the period row in the edit mode
        // with the stored proposal, and the enable waits for the Save's confirm dialog
        // with the picked period; turning off deletes nothing, so it commits straight
        // away (a pending enable has nothing committed yet, so it is just dropped).
        fun onRetentionToggled(value: Boolean) {
            if (value) {
                retentionState.update { it.copy(draftDays = it.storedDays, editing = true) }
                return
            }
            retentionState.update { it.copy(editing = false) }
            if (retentionState.value.enabled) {
                commitRetention(false, retentionState.value.storedDays)
            }
        }

        // The picked period: the dropdown's selection, the only draft mutation.
        fun onRetentionDraftChanged(days: Int) {
            retentionState.update { it.copy(draftDays = days) }
        }

        // The locked row's Edit: opens the edit with the stored period as the draft.
        fun onEditStarted() {
            retentionState.update { it.copy(draftDays = it.storedDays, editing = true) }
        }

        // The edit's Cancel: just closes the edit (a pending enable is dropped with it).
        fun onEditCancelled() {
            retentionState.update { it.copy(editing = false) }
        }

        // The period row's Save: while the enable is pending it always goes through the
        // confirm dialog (enabling starts the deletions); while the cleanup is on, an
        // unchanged draft just closes the edit, a shorter period deletes the messages it
        // no longer covers and waits for the confirm's OK, and a longer one commits
        // straight away.
        fun onRetentionSave() {
            val retention = retentionState.value
            if (!retention.enabled) {
                retentionState.update { it.copy(confirm = RetentionConfirm.ENABLE) }
            } else if (retention.draftDays == retention.storedDays) {
                retentionState.update { it.copy(editing = false) }
            } else if (retention.draftDays < retention.storedDays) {
                retentionState.update { it.copy(confirm = RetentionConfirm.SHORTEN) }
            } else {
                retentionState.update { it.copy(editing = false) }
                commitRetention(true, retention.draftDays)
            }
        }

        // The confirm's OK: one commit for both confirms (the picked period, the cleanup
        // on), the edit closes with it.
        fun onConfirmed() {
            val days = retentionState.value.draftDays
            retentionState.update { it.copy(confirm = null, editing = false) }
            commitRetention(true, days)
        }

        // The confirm's dismiss: just hides the dialog — the shorten keeps the edit open
        // with the draft, the enable keeps the pending enable.
        fun onConfirmDismissed() {
            retentionState.update { it.copy(confirm = null) }
        }

        // The single commit of the log-to-file editor: the pref write and the runtime
        // re-wiring are one action — attach the store (enable), delete the file and
        // detach (disable), or cut the file to the new cap on the writer thread (resize;
        // a grown cap just raises the bound, no cut). The in-memory buffer is never
        // touched here. Disabling keeps the stored size, so the next enable re-proposes
        // it.
        internal fun commitLogToFile(
            enabled: Boolean,
            bytes: Int,
        ) {
            if (bytes !in LogSizePreset.bytes) return
            val wasEnabled = logToFileState.value.enabled
            val logFile = File(appContext.filesDir, AppConfig.LOG_FILE_NAME)
            prefs.update { settings ->
                settings.copy(
                    logToFileEnabled = enabled,
                    logFileSizeBytes = if (enabled) bytes else settings.logFileSizeBytes,
                )
            }
            when {
                enabled && !wasEnabled -> LogBuffer.enableFileStore(logFile, bytes)
                !enabled && wasEnabled -> LogBuffer.disableFileStore()
                enabled -> LogBuffer.updateFileCap(bytes)
            }
            // The commit is the end of the editor's in-progress change: the edit closes
            // with it (the pending-enable save and the locked-row save both land here).
            logToFileState.update {
                val stored = if (enabled) bytes else it.storedSize
                it.copy(enabled = enabled, storedSize = stored, draftSize = stored, editing = false)
            }
        }

        // The log-to-file editor's decision events: the mirror of the retention editor's,
        // minus the enables confirm (turning the logging on deletes nothing) and with the
        // disable moved to a confirm (it deletes the file).

        // Turning on commits nothing by itself: it opens the size row in the edit mode
        // with the stored proposal, the enable lands with the Save. Turning off deletes
        // the file, so it waits for the confirm's OK (a pending enable just drops).
        fun onLogToFileToggled(value: Boolean) {
            if (value) {
                logToFileState.update { it.copy(draftSize = it.storedSize, editing = true) }
                return
            }
            val wasEnabled = logToFileState.value.enabled
            logToFileState.update { it.copy(editing = false) }
            if (wasEnabled) {
                logToFileState.update { it.copy(confirm = LogToFileConfirm.DISABLE) }
            }
        }

        // The picked size: the dropdown's selection, the only draft mutation.
        fun onLogSizeDraftChanged(bytes: Int) {
            logToFileState.update { it.copy(draftSize = bytes) }
        }

        // The locked row's Edit: opens the edit with the stored size as the draft.
        fun onLogEditStarted() {
            logToFileState.update { it.copy(draftSize = it.storedSize, editing = true) }
        }

        // The edit's Cancel: just closes the edit (a pending enable is dropped with it).
        fun onLogEditCancelled() {
            logToFileState.update { it.copy(editing = false) }
        }

        // The size row's Save: while the enable is pending it commits straight away
        // (enabling deletes nothing); while the setting is on, an unchanged draft just
        // closes the edit, a smaller one truncates the file to the new cap and waits for
        // the confirm's OK, and a larger one commits straight away.
        fun onLogSave() {
            val log = logToFileState.value
            when {
                !log.enabled -> commitLogToFile(true, log.draftSize)
                log.draftSize == log.storedSize -> logToFileState.update { it.copy(editing = false) }
                log.draftSize < log.storedSize -> logToFileState.update { it.copy(confirm = LogToFileConfirm.SHRINK) }
                else -> commitLogToFile(true, log.draftSize)
            }
        }

        // The confirm's OK: one commit for both confirms (the disable with the file
        // deletion, the shrink with the truncation to the picked size), the edit closes
        // with it.
        fun onLogConfirmed() {
            val log = logToFileState.value
            when (log.confirm) {
                LogToFileConfirm.DISABLE -> commitLogToFile(false, log.storedSize)
                LogToFileConfirm.SHRINK -> commitLogToFile(true, log.draftSize)
                null -> Unit
            }
            logToFileState.update { it.copy(confirm = null, editing = false) }
        }

        // The confirm's dismiss: just hides the dialog — the shrink keeps the edit open
        // with the draft, the disable keeps the setting on.
        fun onLogConfirmDismissed() {
            logToFileState.update { it.copy(confirm = null) }
        }

        fun sendTestEmail() {
            viewModelScope.launch {
                showTestResultToast(appContext, form.runTestEmail())
            }
        }

        // Regenerated on every call: the write lands at pick time, which can be a fresh
        // composition after a process death while the picker was open, so a string
        // remembered at tap time cannot be trusted there.
        fun exportSettings(): String = SettingsExportImport.exportToJson(prefs)

        fun importPicked(uri: Uri): Job? =
            importRunner.importPicked(viewModelScope, uri) { success ->
                ToastUtil.show(appContext, if (success) R.string.config_import_success else R.string.config_import_failed)
            }

        fun onJsonEmailFormatChanged(enabled: Boolean) {
            extrasState.update { it.copy(useJsonEmailFormat = enabled) }
            prefs.update { it.copy(useJsonEmailFormat = enabled) }
        }

        // Writing the pref is the whole reconcile for the workers/queue: the
        // BackgroundStateReconciler shows or dismisses the notification in response (the
        // flag write emits through backgroundFlags) - the same pattern as the other
        // extras toggles.
        fun onStickyNotificationChanged(value: Boolean) {
            extrasState.update { it.copy(showStickyNotification = value) }
            prefs.update { it.copy(showStickyNotification = value) }
        }

        // Re-reads the live POST_NOTIFICATIONS permission (the screen drives it on resume and
        // after the request dialog returns): the toggle shows the active state again, and a
        // grant while the sticky notification is on shows the persistent hint at once (a
        // revoke needs no dismissal - the system drops the notification itself).
        fun refreshNotificationsPermission() {
            val granted = permissionManager.snapshot().notifications
            val wasGranted = extrasState.value.notificationsGranted
            extrasState.update { it.copy(notificationsGranted = granted) }
            if (granted && !wasGranted && extrasState.value.showStickyNotification) {
                Logger.i("SettingsVM", "notifications permission granted while the sticky notification is on")
                viewModelScope.launch { backgroundStateReconciler.syncNotification() }
            }
        }

        fun onPduTimestampChanged(enabled: Boolean) {
            extrasState.update { it.copy(usePduTimestamp = enabled) }
            prefs.update { it.copy(usePduTimestamp = enabled) }
        }

        // The message-filter feature toggle: commits the intent and the card follows it
        // (hidden while off). The stored filter config is untouched by the toggle — it
        // is a dormant draft while off, re-applied as-is on the next enable.
        fun onFilterEnabledChanged(value: Boolean) {
            extrasState.update { it.copy(filterEnabled = value) }
            prefs.update { it.copy(filterEnabled = value) }
        }

        fun onIncludeTimeInBodyChanged(enabled: Boolean) {
            extrasState.update { it.copy(includeTimeInBody = enabled) }
            prefs.update { it.copy(includeTimeInBody = enabled) }
        }

        // The pick commits right away: writing the setting is the whole reconcile — the
        // BackgroundStateReconciler re-arms or cancels the sweep (and its exact-wake-up
        // token) in response to the flag emission. A pick outside the presets (the
        // dropdown offers nothing else) is dropped.
        fun setSweepInterval(minutes: Int) {
            if (minutes !in SweepIntervalPreset.minutes) return
            extrasState.update { it.copy(retrySweepIntervalMinutes = minutes) }
            prefs.update { it.copy(retrySweepIntervalMinutes = minutes) }
        }

        // The toggle is the user's intent: enabling it while the "Alarms & reminders"
        // permission is missing is allowed — the screen opens the system page right after,
        // and while the permission is not granted the alarms are simply not armed. Every
        // change syncs the exact alarms with the gate (active: re-arm, inactive: cancel —
        // both idempotent), so rows already waiting do not wait for their next backoff.
        fun onExactWakeChanged(value: Boolean) {
            extrasState.update { it.copy(exactWakeEnabled = value) }
            prefs.update { it.copy(exactWakeEnabled = value) }
            Logger.i("SettingsVM", "exact wake-up ${if (value) "enabled" else "disabled"}")
            viewModelScope.launch { syncExactAlarms() }
        }

        // Idempotent sync of the exact-alarm tokens with the gate: while active (setting
        // on AND permission granted) every pending retry re-arms from its stored due time
        // and the sweep's token follows the stored period, otherwise every pending token
        // is cancelled (a turn-off or a revoke must not leave them armed).
        private suspend fun syncExactAlarms() {
            if (exactWakeArmer.exactWakeActive()) {
                exactWakeArmer.rearmAllRetryAlarms()
                val settings = prefs.load()
                if (settings.isForwardingEnabled && settings.retrySweepIntervalMinutes > 0) {
                    exactWakeArmer.armSweepAlarm(System.currentTimeMillis() + settings.retrySweepIntervalMinutes * 60_000L)
                }
            } else {
                exactWakeArmer.cancelAll()
            }
        }

        // Re-reads the live permission (the screen drives it on resume and after the
        // system page returns): the row shows the active state again, and the exact
        // alarms follow the gate (a grant arms them, a revoke cancels them).
        fun refreshExactAlarmPermission() {
            val granted = permissionManager.canScheduleExactAlarms()
            val wasGranted = extrasState.value.exactAlarmsGranted
            extrasState.update { it.copy(exactAlarmsGranted = granted) }
            if (granted != wasGranted) {
                Logger.i("SettingsVM", "Alarms & reminders permission ${if (granted) "granted" else "revoked"}")
                viewModelScope.launch { syncExactAlarms() }
            }
        }

        fun setDedupLevel(level: DedupLevel) {
            extrasState.update { it.copy(dedupLevel = level) }
            prefs.update { it.copy(dedupLevel = level) }
        }

        fun setLogLevel(level: LogLevel) {
            extrasState.update { it.copy(logLevel = level) }
            prefs.update { it.copy(logLevel = level) }
            Logger.setLevel(level)
        }

        // The root collects the holder's flow, so the new palette applies right away, no
        // restart.
        fun setThemeMode(mode: ThemeMode) {
            themePreference.set(mode)
            extrasState.update { it.copy(themeMode = mode) }
        }
    }
