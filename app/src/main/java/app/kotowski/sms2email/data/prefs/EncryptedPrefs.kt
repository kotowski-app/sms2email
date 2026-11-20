// MasterKey/EncryptedSharedPreferences are deprecated; the KTX replacement is released, but
// the migration is a major one, so suppress. The library still works.
@file:Suppress("DEPRECATION")

package app.kotowski.sms2email.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.DedupLevel
import app.kotowski.sms2email.LogLevel
import app.kotowski.sms2email.ThemeMode
import app.kotowski.sms2email.data.filter.SmsFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

// The background-work flags as a single observable value. Every save that writes
// them emits the new pair, so the app-scoped BackgroundStateReconciler reconciles
// workers/notification/queue on every flag change no matter which code path
// (toggle, import, onboarding, launch) wrote it.
data class BackgroundFlags(
    val forwardingEnabled: Boolean,
    val autoCleanupEnabled: Boolean,
    // Defaulted (not required): the stored key resolves to the same default, like the
    // other flags.
    val showStickyNotification: Boolean = AppSettings.DEFAULT_STICKY_NOTIFICATION,
    // The recovery-sweep period the BackgroundStateReconciler arms the periodic work with
    // (0 is the off state).
    val retrySweepIntervalMinutes: Int = AppSettings.DEFAULT_RETRY_SWEEP_INTERVAL_MINUTES,
)

@Singleton
class EncryptedPrefs
    internal constructor(
        private val context: Context,
        private val createPrefs: (fileName: String, masterKey: MasterKey) -> SharedPreferences,
    ) {
        @Inject
        constructor(
            @ApplicationContext context: Context,
        ) : this(
            context,
            { fileName, masterKey ->
                EncryptedSharedPreferences.create(
                    context,
                    fileName,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            },
        )

        private val masterKey =
            MasterKey
                .Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

        // Any failure to open (unreadable file or keyset, an unavailable keystore) is not
        // recoverable in place: the data is unreadable and a reset would be the only real fix,
        // so it propagates to the entry points (App/MainActivity/SmsReceiver), which catch it
        // and degrade instead of crashing.
        private val prefs: SharedPreferences = createPrefs(PREFS_NAME, masterKey)

        // The in-memory snapshot of the exported settings: seeded once from the file (the
        // one full read, alongside create) and then the single source of truth for every
        // read, so load() is a memory read safe on any thread and never pays the per-key
        // decrypts. It can never disagree with the file because every write of this file
        // goes through save()/update() (no external writer), which write through and
        // refresh it. The keys outside the snapshot (the password, the onboarding and
        // scam flags, the theme, the engine state) stay direct file access: one key read
        // each, cheap.
        private val snapshot = MutableStateFlow(readSettings())

        // Seeded from the snapshot, so the observer's first collection at startup
        // reconciles whatever state the flags are currently in (both directions); every
        // save() refreshes it with the write, so the flow never lags the snapshot.
        private val _backgroundFlags =
            MutableStateFlow(
                BackgroundFlags(
                    forwardingEnabled = snapshot.value.isForwardingEnabled,
                    autoCleanupEnabled = snapshot.value.autoCleanupRetentionDays != 0,
                    showStickyNotification = snapshot.value.showStickyNotification,
                    retrySweepIntervalMinutes = snapshot.value.retrySweepIntervalMinutes,
                ),
            )
        val backgroundFlags: StateFlow<BackgroundFlags> = _backgroundFlags.asStateFlow()

        // The keys holding an explicit choice (a toggle, an import, an ensured default): a
        // snapshot save writes every key, so the raw presence cannot answer "was this ever
        // explicitly set" (the SENDER step's ensure* relies on it). A file without the
        // marker yields an empty set: nothing is explicit, so the permission-derived
        // defaults may still apply.
        private val explicitKeys: MutableSet<String> =
            prefs.getStringSet(KEY_EXPLICIT_KEYS, null)?.toMutableSet() ?: mutableSetOf()

        private fun string(
            key: String,
            default: String = "",
        ): String = prefs.getString(key, default) ?: default

        private fun putString(
            key: String,
            value: String,
        ) {
            prefs.edit { putString(key, value) }
        }

        private fun int(
            key: String,
            default: Int,
        ): Int = prefs.getInt(key, default)

        private fun bool(
            key: String,
            default: Boolean,
        ): Boolean = prefs.getBoolean(key, default)

        private fun putBoolean(
            key: String,
            value: Boolean,
        ) {
            prefs.edit { putBoolean(key, value) }
        }

        // Whether the value is an explicit choice: a missing marker means the value is only
        // the default, so a permission-derived default may still be applied.
        internal fun contains(key: String): Boolean = key in explicitKeys

        // A snapshot save (a commit, an update) never makes a key explicit; only a deliberate
        // choice does, so the SENDER step's permission-derived defaults still apply.
        internal fun markExplicit(vararg keys: String) {
            explicitKeys += keys
            prefs.edit { putStringSet(KEY_EXPLICIT_KEYS, explicitKeys.toMutableSet()) }
        }

        // A consistent snapshot of the exported settings, served from the in-memory cache:
        // a memory read (no per-key decrypts), consistent by construction, because the
        // cache is refreshed by save()/update(), the only writers of this file.
        fun load(): AppSettings = snapshot.value

        // The one full file read: the constructor seed of the snapshot.
        // The background flags are read from the stored keys here; from the first save on
        // the snapshot is the source of their state and the flow is refreshed with it.
        private fun readSettings(): AppSettings =
            AppSettings(
                smtpHost = string(KEY_SMTP_HOST),
                smtpPort = int(KEY_SMTP_PORT, AppConfig.SMTP_PORT_START_TLS),
                smtpProtocol = SmtpProtocol.fromStored(prefs.getString(KEY_SMTP_PROTOCOL, null)),
                smtpUsername = string(KEY_SMTP_USERNAME),
                destEmail = string(KEY_DEST_EMAIL),
                destName = string(KEY_DEST_NAME),
                senderEmail = string(KEY_SENDER_EMAIL),
                senderName = string(KEY_SENDER_NAME),
                subjectPrefix = string(KEY_SUBJECT_PREFIX),
                autoSubjectPrefix = bool(KEY_AUTO_SUBJECT_PREFIX, AppSettings.DEFAULT_AUTO_SUBJECT_PREFIX),
                countryCode = string(KEY_COUNTRY_CODE),
                autoCountry = bool(KEY_AUTO_COUNTRY, AppSettings.DEFAULT_AUTO_COUNTRY),
                isForwardingEnabled = bool(KEY_FORWARDING_ENABLED, AppSettings.DEFAULT_FORWARDING_ENABLED),
                useContactResolution = bool(KEY_CONTACT_RESOLUTION, AppSettings.DEFAULT_CONTACT_RESOLUTION),
                displaySimCards = bool(KEY_DISPLAY_SIM_CARDS, AppSettings.DEFAULT_DISPLAY_SIM_CARDS),
                autoCleanupRetentionDays =
                    int(
                        KEY_AUTO_CLEANUP_RETENTION_DAYS,
                        AppSettings.DEFAULT_AUTO_CLEANUP_RETENTION_DAYS,
                    ),
                useJsonEmailFormat = bool(KEY_JSON_EMAIL_FORMAT, AppSettings.DEFAULT_JSON_EMAIL_FORMAT),
                includeTimeInBody = bool(KEY_INCLUDE_TIME_IN_BODY, AppSettings.DEFAULT_INCLUDE_TIME_IN_BODY),
                usePduTimestamp = bool(KEY_USE_PDU_TIMESTAMP, AppSettings.DEFAULT_USE_PDU_TIMESTAMP),
                dedupLevel = DedupLevel.fromStored(prefs.getString(KEY_DEDUP_LEVEL, null)),
                showStickyNotification = bool(KEY_SHOW_STICKY_NOTIFICATION, AppSettings.DEFAULT_STICKY_NOTIFICATION),
                logToFileEnabled = bool(KEY_LOG_TO_FILE_ENABLED, AppSettings.DEFAULT_LOG_TO_FILE_ENABLED),
                logFileSizeBytes = int(KEY_LOG_FILE_SIZE_BYTES, AppSettings.DEFAULT_LOG_FILE_SIZE_BYTES),
                logLevel = LogLevel.fromStored(prefs.getString(KEY_LOG_LEVEL, null)),
                retrySweepIntervalMinutes =
                    int(KEY_RETRY_SWEEP_INTERVAL_MINUTES, AppSettings.DEFAULT_RETRY_SWEEP_INTERVAL_MINUTES),
                exactWakeEnabled = bool(KEY_EXACT_WAKE_ENABLED, AppSettings.DEFAULT_EXACT_WAKE_ENABLED),
                // Parsed once at the seed: the in-memory snapshot then serves the typed
                // value (import and save replace the snapshot directly, no re-read), so
                // the per-SMS load() stays a memory read with no JSON parse on the hot
                // path. A blank/absent key is the product default (filter off).
                filter = SmsFilter.fromJson(string(KEY_FILTER_CONFIG)),
                // The feature toggle: absent key = the product default (off, everything
                // forwards).
                filterEnabled = bool(KEY_FILTER_ENABLED, AppSettings.DEFAULT_FILTER_ENABLED),
            )

        // The single write of the whole snapshot: one edit, so the stored keys can never
        // disagree mid-write. The in-memory snapshot and the background flags' flow are
        // refreshed with the write-through (an unchanged pair degrades to a no-op emit),
        // so the observer reconciles on every change no matter the caller.
        fun save(settings: AppSettings) {
            prefs.edit {
                putString(KEY_SMTP_HOST, settings.smtpHost)
                putInt(KEY_SMTP_PORT, settings.smtpPort)
                putString(KEY_SMTP_PROTOCOL, settings.smtpProtocol.name)
                putString(KEY_SMTP_USERNAME, settings.smtpUsername)
                putString(KEY_DEST_EMAIL, settings.destEmail)
                putString(KEY_DEST_NAME, settings.destName)
                putString(KEY_SENDER_EMAIL, settings.senderEmail)
                putString(KEY_SENDER_NAME, settings.senderName)
                putString(KEY_SUBJECT_PREFIX, settings.subjectPrefix)
                putBoolean(KEY_AUTO_SUBJECT_PREFIX, settings.autoSubjectPrefix)
                putString(KEY_COUNTRY_CODE, settings.countryCode)
                putBoolean(KEY_AUTO_COUNTRY, settings.autoCountry)
                putBoolean(KEY_FORWARDING_ENABLED, settings.isForwardingEnabled)
                putBoolean(KEY_CONTACT_RESOLUTION, settings.useContactResolution)
                putBoolean(KEY_DISPLAY_SIM_CARDS, settings.displaySimCards)
                putInt(KEY_AUTO_CLEANUP_RETENTION_DAYS, settings.autoCleanupRetentionDays)
                putBoolean(KEY_JSON_EMAIL_FORMAT, settings.useJsonEmailFormat)
                putBoolean(KEY_INCLUDE_TIME_IN_BODY, settings.includeTimeInBody)
                putBoolean(KEY_USE_PDU_TIMESTAMP, settings.usePduTimestamp)
                putString(KEY_DEDUP_LEVEL, settings.dedupLevel.name)
                putBoolean(KEY_SHOW_STICKY_NOTIFICATION, settings.showStickyNotification)
                putBoolean(KEY_LOG_TO_FILE_ENABLED, settings.logToFileEnabled)
                putInt(KEY_LOG_FILE_SIZE_BYTES, settings.logFileSizeBytes)
                putString(KEY_LOG_LEVEL, settings.logLevel.name)
                putInt(KEY_RETRY_SWEEP_INTERVAL_MINUTES, settings.retrySweepIntervalMinutes)
                putBoolean(KEY_EXACT_WAKE_ENABLED, settings.exactWakeEnabled)
                putString(KEY_FILTER_CONFIG, settings.filter.toJson())
                putBoolean(KEY_FILTER_ENABLED, settings.filterEnabled)
            }
            snapshot.value = settings
            _backgroundFlags.update {
                it.copy(
                    forwardingEnabled = settings.isForwardingEnabled,
                    autoCleanupEnabled = settings.autoCleanupRetentionDays != 0,
                    showStickyNotification = settings.showStickyNotification,
                    retrySweepIntervalMinutes = settings.retrySweepIntervalMinutes,
                )
            }
        }

        // The read-modify-write of a single setting (or a few at once) without touching
        // the rest: the caller names the change, the snapshot carries the values.
        fun update(transform: (AppSettings) -> AppSettings) {
            save(transform(load()))
        }

        // The SMTP password: deliberately outside the AppSettings, the export never
        // carries it, so a cross-device import must not overwrite a stored one.
        var smtpPassword: String
            get() = string(KEY_SMTP_PASSWORD)
            set(value) {
                putString(KEY_SMTP_PASSWORD, value)
            }

        var isOnboardingComplete: Boolean
            get() = bool(KEY_ONBOARDING_COMPLETE, false)
            set(value) {
                putBoolean(KEY_ONBOARDING_COMPLETE, value)
            }

        var scamWarningDismissed: Boolean
            get() = bool(KEY_SCAM_WARNING_DISMISSED, false)
            set(value) {
                putBoolean(KEY_SCAM_WARNING_DISMISSED, value)
            }

        // Theme mode: SYSTEM follows the device's dark/light setting, the other modes force
        // the palette. A stored value outside the modes (unknown or tampered) degrades to
        // the default.
        var themeMode: ThemeMode
            get() = ThemeMode.fromStored(prefs.getString(KEY_THEME_MODE, null))
            set(value) {
                putString(KEY_THEME_MODE, value.name)
            }

        // Engine state (not user settings, hence outside the export): the sweep period
        // the periodic work is currently armed with. The reconciler re-arms only when it
        // changes, so an app open cannot reset the schedule; 0 means not armed.
        var armedSweepIntervalMinutes: Int
            get() = int(KEY_ARMED_SWEEP_INTERVAL_MINUTES, 0)
            set(value) {
                prefs.edit { putInt(KEY_ARMED_SWEEP_INTERVAL_MINUTES, value) }
            }

        // Engine state: the wall clock of the last success drain (its cooldown gate; 0
        // has never drained).
        var lastDrainAtMillis: Long
            get() = prefs.getLong(KEY_LAST_DRAIN_AT_MILLIS, 0L)
            set(value) {
                prefs.edit { putLong(KEY_LAST_DRAIN_AT_MILLIS, value) }
            }

        companion object {
            private const val PREFS_NAME = "sms2email_prefs"

            // The keys the settings export writes; the import requires only the six core
            // ones (an absent optional key degrades to the product default).
            // internal (not private) so SettingsExportImport shares them verbatim.
            internal const val KEY_SMTP_HOST = "smtp_host"
            internal const val KEY_SMTP_PORT = "smtp_port"
            internal const val KEY_SMTP_PROTOCOL = "smtp_protocol"
            internal const val KEY_SMTP_USERNAME = "smtp_username"
            internal const val KEY_DEST_EMAIL = "dest_email"
            internal const val KEY_DEST_NAME = "dest_name"
            internal const val KEY_SENDER_EMAIL = "sender_email"
            internal const val KEY_SENDER_NAME = "sender_name"
            internal const val KEY_SUBJECT_PREFIX = "subject_prefix"
            internal const val KEY_AUTO_SUBJECT_PREFIX = "auto_subject_prefix"
            internal const val KEY_COUNTRY_CODE = "country_code"
            internal const val KEY_AUTO_COUNTRY = "auto_country"
            internal const val KEY_FORWARDING_ENABLED = "forwarding_enabled"
            internal const val KEY_CONTACT_RESOLUTION = "contact_resolution"
            internal const val KEY_DISPLAY_SIM_CARDS = "display_sim_cards"
            internal const val KEY_AUTO_CLEANUP_RETENTION_DAYS = "auto_cleanup_retention_days"
            internal const val KEY_JSON_EMAIL_FORMAT = "json_email_format"
            internal const val KEY_INCLUDE_TIME_IN_BODY = "include_time_in_body"
            internal const val KEY_USE_PDU_TIMESTAMP = "use_pdu_timestamp"
            internal const val KEY_DEDUP_LEVEL = "dedup_level"
            internal const val KEY_SHOW_STICKY_NOTIFICATION = "show_sticky_notification"
            internal const val KEY_LOG_TO_FILE_ENABLED = "log_to_file_enabled"
            internal const val KEY_LOG_FILE_SIZE_BYTES = "log_file_size_bytes"
            internal const val KEY_LOG_LEVEL = "log_level"
            internal const val KEY_RETRY_SWEEP_INTERVAL_MINUTES = "retry_sweep_interval_minutes"
            internal const val KEY_EXACT_WAKE_ENABLED = "exact_wake_enabled"
            internal const val KEY_FILTER_CONFIG = "filter_config"
            internal const val KEY_FILTER_ENABLED = "filter_enabled"

            private const val KEY_SMTP_PASSWORD = "smtp_password"
            private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
            private const val KEY_SCAM_WARNING_DISMISSED = "scam_warning_dismissed"
            private const val KEY_THEME_MODE = "theme_mode"
            private const val KEY_EXPLICIT_KEYS = "explicit_keys"
            private const val KEY_ARMED_SWEEP_INTERVAL_MINUTES = "armed_sweep_interval_minutes"
            private const val KEY_LAST_DRAIN_AT_MILLIS = "last_drain_at_millis"
        }
    }
