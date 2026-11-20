package app.kotowski.sms2email.ui.emailconfig

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.LanguageOption
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.data.prefs.SmtpProtocol
import app.kotowski.sms2email.service.EmailResult
import app.kotowski.sms2email.service.SmtpConfig
import app.kotowski.sms2email.service.SmtpTestRunner
import app.kotowski.sms2email.service.TestEmailFields
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.ui.components.AutoValueFieldController
import app.kotowski.sms2email.ui.components.AutoValueFieldState
import app.kotowski.sms2email.util.DeviceUtil
import app.kotowski.sms2email.util.LanguageHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

// The transient state of the form's two test runs (the connection test and the
// test email): their results and the in-progress flags. Kept apart from the
// persisted settings: a reload from the prefs resets the results, never the
// draft the user is editing.
data class TestRunState(
    val smtpTestResult: EmailResult? = null,
    val testEmailResult: EmailResult? = null,
    val isTestingConnection: Boolean = false,
    val isSendingTestEmail: Boolean = false,
)

// The email config shared by the onboarding wizard and the settings screen: the
// draft of the persisted settings (plus the SMTP password, which the export
// never carries), the app language, the test runs' live state, and the refresh
// generation the screens re-sync the auto-value fields' local drafts on.
data class EmailConfig(
    val settings: AppSettings = AppSettings(),
    // The port draft as shown in the port field: the text the user is editing. A
    // port in range is committed to settings.smtpPort on the way (the
    // controller's onPortDraftChanged); an empty or invalid draft leaves the last
    // valid port active and the live-form completeness rule reports the form
    // incomplete until the field holds a port in range again.
    val portDraft: String = "",
    val smtpPassword: String = "",
    val appLanguage: String = "",
    val test: TestRunState = TestRunState(),
    // Bumped on every reload from the prefs (an import, a manual refresh); the
    // subject prefix and country fields re-sync their local drafts when it changes.
    val refreshGeneration: Int = 0,
)

// The email-config form shared by the onboarding wizard and the settings screen:
// the SMTP and sender/receiver fields' state, every field handler, the connection
// test and test-email runs.
//
// The SMTP and the sender/receiver fields stay in the form's state (the draft)
// until commitDraft() (the wizard's "Done", the settings save), so a background
// send firing while the user is mid-edit reads the last saved config, never a
// half-typed one. The subject prefix, the contact resolution and the SIM-card
// display persist on change.
class EmailConfigController(
    private val appContext: Context,
    private val prefs: EncryptedPrefs,
    private val testRunner: SmtpTestRunner,
    // The auto value source of the country field: the region the chain detects.
    private val contactResolver: ContactResolver,
) {
    // The stored (settings, smtpPassword) the draft was loaded from: the
    // baseline hasChanges() compares the draft against, captured on every load
    // (the construction, the reload) so an import or a manual refresh
    // re-baselines the form. Declared before _state: its initializer runs the
    // first capture.
    private var savedBaseline: Pair<AppSettings, String>? = null

    private val _state = MutableStateFlow(stateFromPrefs())
    val state: StateFlow<EmailConfig> = _state.asStateFlow()

    // The subject prefix as the shared auto-value field: the auto/manual rules
    // and the persist-on-change live in the element; the draft mirrors it, so
    // the test-email run and the save (both reading the draft) never drift
    // from the field.
    private val autoValueField =
        AutoValueFieldController(
            load = {
                val settings = prefs.load()
                AutoValueFieldState(settings.autoSubjectPrefix, settings.subjectPrefix)
            },
            save = { field ->
                prefs.update { it.copy(autoSubjectPrefix = field.auto, subjectPrefix = field.value) }
            },
            // The cap keeps an over-long device name from breaking the composed subject
            // (the same bound the manual edits and the import enforce).
            autoValue = { DeviceUtil.getDeviceName(appContext).take(AppConfig.SUBJECT_PREFIX_MAX_LENGTH) },
        )

    // The country for local numbers as the shared auto-value field: the auto/manual
    // rules and the persist-on-change live in the element; the draft mirrors it, so
    // the save never drifts from the field. A manual code persists only as a valid
    // ISO country (or blank, the no-override marker): a rejected keystroke stays
    // in the field (the error flags it) and never reaches the prefs.
    private val countryField =
        AutoValueFieldController(
            load = {
                val settings = prefs.load()
                AutoValueFieldState(settings.autoCountry, settings.countryCode)
            },
            save = { field ->
                prefs.update { it.copy(autoCountry = field.auto, countryCode = field.value) }
            },
            autoValue = { contactResolver.detectedRegion() },
            validate = { code -> code.isEmpty() || code in Locale.getISOCountries() },
        )

    private fun stateFromPrefs(): EmailConfig {
        val settings = prefs.load()
        val password = prefs.smtpPassword
        savedBaseline = settings to password
        return EmailConfig(
            settings = settings,
            portDraft = settings.smtpPort.toString(),
            smtpPassword = password,
            appLanguage = currentAppLanguage(),
        )
    }

    // Whether the save would change anything: the draft compared against the
    // baseline with the commit's normalization (the connection fields trimmed),
    // so an edit that only adds the spaces the commit trims away is not a
    // change; a null baseline (should not happen) answers true, so the save
    // asks for the confirm, like any untested change.
    fun hasChanges(): Boolean {
        val baseline = savedBaseline ?: return true
        val current = _state.value
        return current.settings.copy(
            smtpHost = current.settings.smtpHost.trim(),
            smtpUsername = current.settings.smtpUsername.trim(),
        ) != baseline.first || current.smtpPassword.trim() != baseline.second
    }

    // Re-reads the draft from the prefs and bumps the refresh generation. With
    // keepTestRuns, an in-flight test survives the reload (its results arrive on the
    // in-flight config after the reload replaces the state); by default the test runs
    // reset, like any other reload.
    fun reloadFromPrefs(keepTestRuns: Boolean = false) {
        autoValueField.reload()
        countryField.reload()
        _state.update { current ->
            val reloaded = stateFromPrefs().copy(refreshGeneration = current.refreshGeneration + 1)
            if (keepTestRuns) {
                reloaded.copy(
                    test =
                        TestRunState(
                            isTestingConnection = current.test.isTestingConnection,
                            isSendingTestEmail = current.test.isSendingTestEmail,
                        ),
                )
            } else {
                reloaded
            }
        }
    }

    // The direct draft write for the fields the form owns (the auto-fill's
    // sender/dest emails). Must never touch settings.subjectPrefix/autoSubjectPrefix:
    // the auto-value field owns them and mirrors them into the draft one-way.
    fun update(transform: (EmailConfig) -> EmailConfig) {
        _state.update(transform)
    }

    // The SMTP fields feed both test runs, so an edit invalidates both results:
    // the settings save gate reads the test-email success alone, and a stale one
    // would gate a changed credential.
    fun onSmtpHostChanged(value: String) {
        _state.update { it.copy(settings = it.settings.copy(smtpHost = value)).clearSmtpResults() }
    }

    // The port field's text change: the draft always follows the field, and a port
    // in range is committed on the way. An empty or invalid draft never commits
    // (the last valid port stays active) — the live-form completeness rule then
    // reports the form incomplete until the field holds a port in range again.
    fun onPortDraftChanged(value: String) {
        _state.update { it.copy(portDraft = value) }
        smtpPortCommitValue(value)?.let { onSmtpPortChanged(it) }
    }

    // The committed-port half of the port edit (the SMTP fields feed both test
    // runs, so a change invalidates both results: the settings save gate reads
    // the test-email success alone, and a stale one would gate a changed
    // credential).
    private fun onSmtpPortChanged(value: Int) {
        _state.update { it.copy(settings = it.settings.copy(smtpPort = value)).clearSmtpResults() }
    }

    fun onSmtpProtocolChanged(protocol: SmtpProtocol) {
        val port = protocol.defaultPort
        _state.update {
            it.copy(settings = it.settings.copy(smtpProtocol = protocol, smtpPort = port)).clearSmtpResults()
        }
    }

    fun onSmtpUsernameChanged(value: String) {
        _state.update { it.copy(settings = it.settings.copy(smtpUsername = value)).clearSmtpResults() }
    }

    fun onSmtpPasswordChanged(value: String) {
        _state.update { it.copy(smtpPassword = value).clearSmtpResults() }
    }

    fun onDestEmailChanged(value: String) {
        _state.update { it.copy(settings = it.settings.copy(destEmail = value)).clearTestEmailResult() }
    }

    fun onDestNameChanged(value: String) {
        _state.update { it.copy(settings = it.settings.copy(destName = value)).clearTestEmailResult() }
    }

    fun onSenderEmailChanged(value: String) {
        _state.update { it.copy(settings = it.settings.copy(senderEmail = value)).clearTestEmailResult() }
    }

    fun onSenderNameChanged(value: String) {
        _state.update { it.copy(settings = it.settings.copy(senderName = value)).clearTestEmailResult() }
    }

    // The manual edit and the IME commit keep the prefix within the cap: the composed
    // subject (the prefix inside the template plus the sender name) must stay within the
    // providers' subject bounds (the auto value and the import enforce the same bound).
    fun onSubjectPrefixChanged(value: String) {
        autoValueField.onValueChange(value.take(AppConfig.SUBJECT_PREFIX_MAX_LENGTH))
        mirrorAutoValueField()
        _state.update { it.copy(test = it.test.copy(testEmailResult = null)) }
    }

    fun onSubjectPrefixCommitted(value: String) {
        autoValueField.onCommitted(value.take(AppConfig.SUBJECT_PREFIX_MAX_LENGTH))
        mirrorAutoValueField()
    }

    fun onAutoSubjectPrefixChanged(value: Boolean) {
        autoValueField.onAutoChanged(value)
        mirrorAutoValueField()
    }

    // The draft mirror of the shared field: the test-email run and the save
    // read the draft, so it must follow the field on every change.
    private fun mirrorAutoValueField() {
        val field = autoValueField.state.value
        _state.update {
            it.copy(settings = it.settings.copy(subjectPrefix = field.value, autoSubjectPrefix = field.auto))
        }
    }

    // The country code is canonical uppercase: the stored and displayed value is
    // normalized on the way in, so the commit's auto re-engage compares like for like.
    fun onCountryCodeChanged(value: String) {
        countryField.onValueChange(value.uppercase(Locale.ROOT))
        mirrorCountryField()
    }

    fun onCountryCodeCommitted(value: String) {
        countryField.onCommitted(value.uppercase(Locale.ROOT))
        mirrorCountryField()
    }

    fun onAutoCountryChanged(value: Boolean) {
        countryField.onAutoChanged(value)
        mirrorCountryField()
    }

    // The draft mirror of the country field: the save reads the draft, so it must
    // follow the field on every change.
    private fun mirrorCountryField() {
        val field = countryField.state.value
        _state.update {
            it.copy(settings = it.settings.copy(countryCode = field.value, autoCountry = field.auto))
        }
    }

    fun onUseContactResolutionChanged(enabled: Boolean) {
        _state.update { it.copy(settings = it.settings.copy(useContactResolution = enabled)) }
        prefs.update { it.copy(useContactResolution = enabled) }
        prefs.markExplicit(EncryptedPrefs.KEY_CONTACT_RESOLUTION)
    }

    fun onDisplaySimCardsChanged(enabled: Boolean) {
        _state.update { it.copy(settings = it.settings.copy(displaySimCards = enabled)) }
        prefs.update { it.copy(displaySimCards = enabled) }
        prefs.markExplicit(EncryptedPrefs.KEY_DISPLAY_SIM_CARDS)
    }

    // The SENDER step's permission-derived defaults: applied only while the pref was never
    // explicitly written (the key absent), so an explicit choice (the settings toggles, an
    // import, the draft commit) is never overridden.
    fun ensureContactResolutionOnIfUnset() {
        if (!prefs.contains(EncryptedPrefs.KEY_CONTACT_RESOLUTION)) {
            onUseContactResolutionChanged(true)
        }
    }

    fun ensureSimDisplayOnIfUnset() {
        if (!prefs.contains(EncryptedPrefs.KEY_DISPLAY_SIM_CARDS)) {
            onDisplaySimCardsChanged(true)
        }
    }

    fun setAppLanguage(tag: String) {
        if (tag !in LanguageOption.tags) return
        LanguageHelper.setAppLanguage(appContext, tag)
        _state.update { it.copy(appLanguage = tag) }
    }

    private fun currentAppLanguage(): String {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        return if (appLocales.isEmpty) "" else (appLocales[0]?.toLanguageTag() ?: "")
    }

    // Returns the result published into the state (the run's outcome, or the
    // invalidated one when the draft changed mid-run) so the caller can react to
    // exactly this completion, once.
    suspend fun runTestConnection(): EmailResult? {
        _state.update { it.copy(test = it.test.copy(isTestingConnection = true, smtpTestResult = null)) }
        val current = _state.value
        val snapshot = current.toSmtpConfig()
        val result = testRunner.testConnection(snapshot, appContext)
        var published: EmailResult? = null
        _state.update {
            val stillMatched = it.toSmtpConfig() == snapshot
            published = if (stillMatched) result else invalidatedTestResult()
            it.copy(
                test =
                    it.test.copy(
                        isTestingConnection = false,
                        smtpTestResult = published,
                    ),
            )
        }
        return published
    }

    // Returns the result published into the state (the run's outcome, or the
    // invalidated one when the draft changed mid-run) so the caller can react to
    // exactly this completion, once.
    suspend fun runTestEmail(): EmailResult? {
        _state.update { it.copy(test = it.test.copy(isSendingTestEmail = true, testEmailResult = null)) }
        val current = _state.value
        val smtpSnapshot = current.toSmtpConfig()
        val fieldsSnapshot = current.toTestEmailFields()
        val result = testRunner.sendTestEmail(smtpSnapshot, fieldsSnapshot, appContext)
        var published: EmailResult? = null
        _state.update {
            val stillMatched = it.toSmtpConfig() == smtpSnapshot && it.toTestEmailFields() == fieldsSnapshot
            published = if (stillMatched) result else invalidatedTestResult()
            it.copy(
                test =
                    it.test.copy(
                        isSendingTestEmail = false,
                        testEmailResult = published,
                    ),
            )
        }
        return published
    }

    private fun invalidatedTestResult() = EmailResult.Failure(appContext.getString(R.string.email_config_test_invalidated))

    // The one commit of the draft's fields (the wizard's "Done", the settings
    // save); the fields that already persist on change are harmlessly written again.
    // The connection string fields commit trimmed: the draft accepts edge spaces
    // while typing (the native field behavior), but a padded value stored would pass
    // the non-blank gate and fail every connect with an opaque DNS/auth error.
    fun commitDraft() {
        val current = _state.value
        prefs.save(
            current.settings.copy(
                smtpHost = current.settings.smtpHost.trim(),
                smtpUsername = current.settings.smtpUsername.trim(),
            ),
        )
        prefs.smtpPassword = current.smtpPassword.trim()
    }

    // The one mapping of the draft to the test runs' inputs, shared by both runs:
    // the SMTP credentials (trimmed, exactly as the commit stores them), and the
    // sender/receiver fields of the test email.
    private fun EmailConfig.toSmtpConfig() =
        SmtpConfig(
            settings.smtpHost.trim(),
            settings.smtpPort,
            settings.smtpProtocol,
            settings.smtpUsername.trim(),
            smtpPassword.trim(),
        )

    private fun EmailConfig.toTestEmailFields() =
        TestEmailFields(
            settings.senderEmail,
            settings.senderName,
            settings.destEmail,
            settings.destName,
        )

    // The SMTP fields feed both test runs, so an edit clears both results.
    private fun EmailConfig.clearSmtpResults() = copy(test = test.copy(smtpTestResult = null, testEmailResult = null))

    // The sender/receiver fields only shape the test email, so an edit clears its
    // result alone; the connection test is unaffected.
    private fun EmailConfig.clearTestEmailResult() = copy(test = test.copy(testEmailResult = null))
}
