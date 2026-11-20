package app.kotowski.sms2email.data.prefs

import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.DedupLevel
import app.kotowski.sms2email.LogLevel
import app.kotowski.sms2email.data.filter.SmsFilter
import app.kotowski.sms2email.util.EmailUtil

// The persisted settings as a single value: the settings the export writes (the import
// requires only the six core keys - the SMTP connection: host, port, protocol, username
// - and the sender/destination addresses, and degrades the rest to the product defaults
// while absent), the form's draft, and the read-modify-write snapshot of every consumer.
// One setting lives here once; adding one means one field here and one line each in
// EncryptedPrefs.load/save and the export's field list, never a coordinated edit across
// the form and the import.
//
// Not in the value: the SMTP password (the export never carries it), the
// onboarding and scam-warning flags, and the theme mode (none of them part of
// the exported config); those stay individual prefs on the EncryptedPrefs.
data class AppSettings(
    val smtpHost: String = "",
    val smtpPort: Int = AppConfig.SMTP_PORT_START_TLS,
    val smtpProtocol: SmtpProtocol = SmtpProtocol.START_TLS,
    val smtpUsername: String = "",
    val destEmail: String = "",
    val destName: String = "",
    val senderEmail: String = "",
    val senderName: String = "",
    val subjectPrefix: String = "",
    val autoSubjectPrefix: Boolean = DEFAULT_AUTO_SUBJECT_PREFIX,
    val countryCode: String = "",
    val autoCountry: Boolean = DEFAULT_AUTO_COUNTRY,
    val isForwardingEnabled: Boolean = DEFAULT_FORWARDING_ENABLED,
    val useContactResolution: Boolean = DEFAULT_CONTACT_RESOLUTION,
    val displaySimCards: Boolean = DEFAULT_DISPLAY_SIM_CARDS,
    // The auto-cleanup period as the single setting (0 is the off state).
    val autoCleanupRetentionDays: Int = DEFAULT_AUTO_CLEANUP_RETENTION_DAYS,
    val useJsonEmailFormat: Boolean = DEFAULT_JSON_EMAIL_FORMAT,
    // The received time as the first line of the plain-text body: off by default, the
    // time is sent only when the user explicitly turns it on (the JSON format carries
    // its own time_received field and ignores this).
    val includeTimeInBody: Boolean = DEFAULT_INCLUDE_TIME_IN_BODY,
    val usePduTimestamp: Boolean = DEFAULT_USE_PDU_TIMESTAMP,
    val dedupLevel: DedupLevel = DedupLevel.BALANCED,
    val showStickyNotification: Boolean = DEFAULT_STICKY_NOTIFICATION,
    val logToFileEnabled: Boolean = DEFAULT_LOG_TO_FILE_ENABLED,
    val logFileSizeBytes: Int = DEFAULT_LOG_FILE_SIZE_BYTES,
    val logLevel: LogLevel = LogLevel.DEFAULT,
    // The recovery-sweep period in minutes (0 is the off state, see SweepIntervalPreset).
    val retrySweepIntervalMinutes: Int = DEFAULT_RETRY_SWEEP_INTERVAL_MINUTES,
    // The exact-wake-up additional token (the exact alarms), off by default.
    val exactWakeEnabled: Boolean = DEFAULT_EXACT_WAKE_ENABLED,
    // The message filter as the single value (the whole config, mode + rules);
    // the default (SKIP, no rules) is the off state: everything forwards.
    val filter: SmsFilter = SmsFilter(),
    // The message-filter feature toggle: an explicit opt-in (off by default). While off
    // the filter's rules are not evaluated at all (every message forwards), so the
    // config is a dormant draft the user can revisit after enabling.
    val filterEnabled: Boolean = DEFAULT_FILTER_ENABLED,
) {
    // The filter the engine applies: the stored config while the feature toggle is on,
    // the off filter (everything forwards) while it is off — a disabled feature blocks
    // nothing at any check site.
    val effectiveFilter: SmsFilter
        get() = if (filterEnabled) filter else SmsFilter()

    companion object {
        // Product defaults: what a fresh install degrades to, and what the settings
        // import falls back to for an absent optional key (the import requires only
        // the core keys present, so for those these are the stored-value defaults).
        const val DEFAULT_FORWARDING_ENABLED = true
        const val DEFAULT_AUTO_SUBJECT_PREFIX = false

        // Auto on: a fresh install detects the region from the SIM/network/locale, a
        // manual code is an opt-in.
        const val DEFAULT_AUTO_COUNTRY = true
        const val DEFAULT_CONTACT_RESOLUTION = true
        const val DEFAULT_DISPLAY_SIM_CARDS = false
        const val DEFAULT_AUTO_CLEANUP_RETENTION_DAYS = 0
        const val DEFAULT_JSON_EMAIL_FORMAT = false
        const val DEFAULT_INCLUDE_TIME_IN_BODY = false
        const val DEFAULT_USE_PDU_TIMESTAMP = false
        const val DEFAULT_STICKY_NOTIFICATION = true
        const val DEFAULT_LOG_TO_FILE_ENABLED = false
        const val DEFAULT_LOG_FILE_SIZE_BYTES = AppConfig.LOG_FILE_MAX_BYTES
        const val DEFAULT_RETRY_SWEEP_INTERVAL_MINUTES = 15
        const val DEFAULT_EXACT_WAKE_ENABLED = false

        // Off by default: the message filter is an explicit opt-in (a regex feature the
        // user enables knowingly), so a fresh install forwards everything.
        const val DEFAULT_FILTER_ENABLED = false
    }
}

// The completeness rule, checked on the settings + password pair by both the UI gates
// (the form's draft) and the queue path (the stored values): the host, a port in range,
// the username and the password must be non-blank, and the sender and destination
// emails must be valid — a missing or malformed address can never send (the queue would
// burn its whole retry window), so it is blocked here like the import gate already does.
fun AppSettings.isComplete(smtpPassword: String): Boolean =
    smtpHost.isNotBlank() &&
        smtpPort in AppConfig.VALID_PORT_RANGE &&
        smtpUsername.isNotBlank() &&
        smtpPassword.isNotBlank() &&
        EmailUtil.isValidEmail(senderEmail) &&
        EmailUtil.isValidEmail(destEmail)
