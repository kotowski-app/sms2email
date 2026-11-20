package app.kotowski.sms2email

import app.kotowski.sms2email.util.DateUtil

object AppConfig {
    // The scam-warning splash's countdown in seconds; its dismiss button unlocks only at
    // 0 (the timer resets on pause and on screen unlock).
    const val SCAM_WARNING_TIMER_SECONDS: Int = 20

    // The rows in one batch-send worker: the queue path chunks its ids by this, and the
    // retry sweep's recovery batch is capped at it.
    const val MAX_BATCH_IDS: Int = 400

    // The list screens' paging: the initial window size and the rows added per load-more.
    const val SMS_LIST_PAGE_SIZE: Int = 20

    // The list's load-more trigger: fire when the last visible row is within this many
    // rendered items (rows + spinner item) of the end.
    const val SMS_LOAD_MORE_TRIGGER_THRESHOLD: Int = 2

    // The search field's debounce before the query hits the database.
    const val SMS_SEARCH_DEBOUNCE_MS: Long = 300L

    // The cap on the name-matched contact numbers bound into the search's contact facet
    // (SmsSearch): an unbounded IN list can exceed SQLite's variable limit on older
    // devices and fail the whole query.
    const val SMS_SEARCH_MAX_CONTACT_NUMBERS: Int = 500

    // The app's live per-second heartbeat: the scam-warning countdown and the retry
    // countdowns in the list cards and the detail screen tick on it.
    const val LIVE_TICK_INTERVAL_MS: Long = 1_000L

    // The valid range of a TCP port; the SMTP field, the settings import and the
    // send path all check against it.
    val VALID_PORT_RANGE: IntRange = 1..65535

    // The IANA-registered SMTP ports, by protocol; the form's default port and the
    // import's fallback for a missing one.
    const val SMTP_PORT_START_TLS: Int = 587
    const val SMTP_PORT_IMPLICIT_TLS: Int = 465

    // The minimum time the test buttons keep their spinner: a fast result must not flash
    // on the screen.
    const val SMTP_TEST_MIN_DELAY_MS: Long = 3_000L

    // The subject prefix cap: the composed subject (the prefix inside the template plus
    // the sender name) must stay within the mail providers' subject bounds, so the form's
    // manual edits, the auto device name and the import keep the prefix within it.
    const val SUBJECT_PREFIX_MAX_LENGTH: Int = 128

    // The composed subject's UTF-8 byte cap, applied on the send path: the wire line is
    // `Subject: ` (9 octets) plus the value, and a conforming SMTP server must accept
    // lines up to 998 octets excluding CRLF (RFC 5321, section 4.5.3.1.6) — beyond that
    // the 5xx is permanent, so the subject is cut to fit instead of failing the row.
    const val SUBJECT_MAX_BYTES: Int = 989

    // The JSON email format's schema version, carried in the payload. v1 ships with
    // the final field set.
    const val JSON_EMAIL_FORMAT_VERSION: Int = 1

    // The JSON email format's marker header: its presence marks a machine payload, the
    // value names the body format. A typo in either is a silent failure (a consumer stops
    // seeing the header with no error), so both live here rather than inline at the send
    // site.
    const val JSON_EMAIL_HEADER_NAME: String = "X-Sms2Email"
    const val JSON_EMAIL_HEADER_VALUE: String = "json"

    // The retry window: a row is marked FAILED this long after its first send attempt.
    const val MAX_RETRY_PERIOD_MS: Long = 14L * DateUtil.MS_PER_DAY // 2 weeks

    // The backoff of the first retry attempt; later attempts grow it per the backoff
    // shape below.
    const val BASE_BACKOFF_MS: Long = 5_000 // 5 seconds

    // The SMTP network timeouts. The whole-send bound must stay below the SENDING stale
    // sweep threshold, or a slow send can be flipped RETRYING mid-flight and re-sent.
    const val SMTP_SEND_TIMEOUT_MS: Long = 180_000 // 3 minutes
    const val SMTP_TEST_CONNECTION_TIMEOUT_MS: Long = 30_000 // 30 seconds
    const val SMTP_CONNECT_TIMEOUT_MS: Long = 5_000 // 5 seconds
    const val SMTP_READ_TIMEOUT_MS: Long = 15_000 // 15 seconds
    const val SMTP_WRITE_TIMEOUT_MS: Long = 25_000 // 25 seconds

    // The retry sweep: how old an in-flight row must be before the sweep considers it
    // stuck. The sweep's own period is the user setting (SweepIntervalPreset), not a
    // constant.
    const val RETRY_PENDING_STALE_MS: Long = 5 * 60_000L // 5 minutes
    const val RETRY_SENDING_STALE_MS: Long = 5 * 60_000L // 5 minutes

    // The success drain: a successful send proves the SMTP path is healthy, so the queued
    // rows are re-fired right away instead of waiting out their backoffs. The cooldown
    // bounds a flapping server: each success triggers at most one drain per interval.
    const val SUCCESS_DRAIN_COOLDOWN_MS: Long = 60_000L // 1 minute

    // The exact-wake-up threshold: a retry's exact alarm is armed only past it — shorter
    // delays fire while the process is still alive from the failed send, and a burst of
    // RTC_WAKEUP alarms for sub-minute delays would just burn battery for no gain.
    const val EXACT_WAKE_ARM_THRESHOLD_MS: Long = 5 * 60_000L // 5 minutes

    // A failed live-path insert created no row, so the sweep (which only sees stored
    // rows) can never recover the SMS. Two retry lines: the data layer re-tries the
    // insert itself against a briefly locked database, and if it still fails the work
    // layer re-runs the whole ingest after a delay. A process death inside the window
    // still drops the SMS (no durable spool by design).
    const val SMS_INSERT_MAX_ATTEMPTS: Int = 3
    const val SMS_INSERT_RETRY_DELAY_MS: Long = 100L
    const val INGEST_RETRY_MAX_ATTEMPTS: Int = 3
    const val INGEST_RETRY_DELAY_MS: Long = 30_000 // 30 seconds

    // The exponential backoff shape: the growth base per attempt, and the ± jitter
    // fraction of the capped backoff.
    const val RETRY_BACKOFF_GROWTH_BASE: Double = 2.0
    const val RETRY_BACKOFF_JITTER_FRACTION: Double = 0.10

    // The batch's not-yet-due margin: a row's stored next-attempt time and its per-row
    // worker's fire time are two independent jitter draws, so a row due within this
    // slack is treated as due rather than deferred to a worker that may already have
    // fired and failed.
    const val BATCH_DUE_CHECK_SLACK_MS: Long = 60_000L // 60 seconds

    // The history auto-clear period of the daily cleanup worker.
    const val HISTORY_CLEANUP_PERIOD_DAYS: Long = 1

    // Contact resolution: the minimum number of digits to attempt a match, and the
    // contact-scan cache TTL.
    const val CONTACT_MIN_NUMBER_LENGTH: Int = 3

    // The minimum digits for the provider's prefix-match fast path: shorter senders
    // (service/short codes) could prefix-match an unrelated contact, so they use the
    // exact E.164 fallback instead.
    const val CONTACT_FAST_PATH_MIN_LENGTH: Int = 7
    const val CONTACTS_CACHE_TTL_MS: Long = 60_000L // 60 seconds

    // The detected-region (SIM -> network -> locale) cache TTL: a SIM swap or a roam
    // re-resolves the region after this window without a process restart.
    const val DETECTED_REGION_CACHE_TTL_MS: Long = 60_000L // 60 seconds

    // The SIM slot map cache TTL.
    const val SIM_NAME_CACHE_TTL_MS: Long = 60_000L // 60 seconds

    // The settings import's size cap: a valid export is a few KB at most, anything
    // bigger can only be a wrong pick.
    const val SETTINGS_IMPORT_MAX_FILE_SIZE: Long = 1L shl 20 // 1 MiB

    // The settings import's read chunk: the capped read pulls the stream in chunks of
    // this size and counts the bytes, aborting once the cap is crossed.
    const val SETTINGS_IMPORT_READ_CHUNK_SIZE: Int = 8192

    // The app log: the in-memory buffer cap, the per-message ingestion bound, the rolling
    // file size cap, the tail kept when the cap is crossed (the rotation truncates the
    // file to it instead of wiping it), the per-line byte bound the tail load's read
    // window is sized from, and the file's location under the app's files dir.
    const val LOG_MAX_ENTRIES: Int = 500
    const val LOG_MAX_MESSAGE_LENGTH: Int = 2_000
    const val LOG_FILE_MAX_BYTES: Int = 256 * 1024
    const val LOG_FILE_TAIL_BYTES: Int = 64 * 1024
    const val LOG_FILE_NAME: String = "logs/sms2email.log"

    // The longest line one entry can occupy in the file, in UTF-8 bytes: the message is
    // capped in chars (LOG_MAX_MESSAGE_LENGTH), a char takes up to 4 UTF-8 bytes, plus
    // the fixed timestamp/level/tag/terminator overhead (a tag is short by construction).
    const val LOG_MAX_LINE_BYTES: Int = LOG_MAX_MESSAGE_LENGTH * 4 + 256

    // The message filter's soft caps (pathology guards, not UX limits): the maximum
    // number of rules and the UTF-8 byte cap of a facet's text (body/sender regex).
    // The editor's save gate, the strict import and the lenient runtime parse all
    // enforce the same bounds.
    const val FILTER_MAX_RULES: Int = 10
    const val FILTER_FACET_MAX_BYTES: Int = 512
}

// The shipped app languages: the single source for the picker lists, the row
// labels and the tag validation (the empty tag is the system default).
enum class LanguageOption(
    val tag: String,
    val labelRes: Int,
) {
    SYSTEM("", R.string.settings_system_default),
    ENGLISH("en", R.string.settings_language_english),
    RUSSIAN("ru", R.string.settings_language_russian),
    CHINESE("zh", R.string.settings_language_chinese),
    ;

    companion object {
        val tags: Set<String>
            get() = entries.mapTo(mutableSetOf()) { it.tag }
    }
}

// The retention periods the auto-clear editor offers (ascending). Named in days
// (not months) so they read the same as the import dialog's "Last …" rows — e.g.
// both say "30 days". The import dialog offers a different set of periods (24
// hours, 7 days, 30 days, 90 days) plus all time and since-date; its 30- and
// 90-day rows take their day counts from here. The maximum of 1825 days is 5
// years — "keep forever" is the off state of the auto-clear toggle.
enum class PeriodPreset(
    val days: Int,
    val labelRes: Int,
) {
    THIRTY_DAYS(30, R.string.period_30_days),
    NINETY_DAYS(90, R.string.period_90_days),
    ONE_HUNDRED_EIGHTY_DAYS(180, R.string.period_180_days),
    ONE_YEAR(365, R.string.period_1_year),
    TWO_YEARS(730, R.string.period_2_years),
    FIVE_YEARS(1825, R.string.period_5_years),
    ;

    companion object {
        val days: List<Int>
            get() = entries.map { it.days }

        // The label of a stored days value; a value outside the presets (unknown/tampered)
        // degrades to the longest preset label, like the state degrades to a preset.
        fun labelResFor(days: Int): Int = entries.firstOrNull { it.days == days }?.labelRes ?: FIVE_YEARS.labelRes
    }
}

// The recovery-sweep periods the settings offer (ascending; 0 is the off state). The
// 15-minute floor is WorkManager's periodic minimum, so every preset is enqueueable
// as-is.
enum class SweepIntervalPreset(
    val minutes: Int,
    val labelRes: Int,
) {
    OFF(0, R.string.option_off),
    FIFTEEN_MINS(15, R.string.settings_sweep_interval_15min),
    ONE_HOUR(60, R.string.settings_sweep_interval_1h),
    SIX_HOURS(360, R.string.settings_sweep_interval_6h),
    TWELVE_HOURS(720, R.string.settings_sweep_interval_12h),
    TWENTY_FOUR_HOURS(1440, R.string.settings_sweep_interval_24h),
    ;

    companion object {
        val minutes: List<Int>
            get() = entries.map { it.minutes }

        // The label of a stored minutes value; a value outside the presets (unknown or
        // tampered) degrades to the default preset's label, like the state degrades to a
        // preset.
        fun labelResFor(minutes: Int): Int =
            entries.firstOrNull { it.minutes == minutes }?.labelRes
                ?: FIFTEEN_MINS.labelRes
    }
}

// The log file size caps the "log to file" editor offers (ascending). The default is
// the shipped cap (LOG_FILE_MAX_BYTES). A stored value outside the presets
// (unknown/tampered) degrades to the default in the UI and in the engine's startup
// read (Sms2EmailApp.logFileSizeFor).
enum class LogSizePreset(
    val bytes: Int,
    val labelRes: Int,
) {
    SIXTY_FOUR_KB(64 * 1024, R.string.log_size_64kb),
    TWO_HUNDRED_FIFTY_SIX_KB(AppConfig.LOG_FILE_MAX_BYTES, R.string.log_size_256kb),
    ONE_MB(1024 * 1024, R.string.log_size_1mb),
    FOUR_MB(4 * 1024 * 1024, R.string.log_size_4mb),
    SIXTEEN_MB(16 * 1024 * 1024, R.string.log_size_16mb),
    ;

    companion object {
        val DEFAULT: LogSizePreset = TWO_HUNDRED_FIFTY_SIX_KB

        val bytes: List<Int>
            get() = entries.map { it.bytes }

        // The label of a stored size; a value outside the presets (unknown/tampered)
        // degrades to the default preset label, like the state degrades to a preset.
        fun labelResFor(bytes: Int): Int = entries.firstOrNull { it.bytes == bytes }?.labelRes ?: DEFAULT.labelRes
    }
}

// The log levels in the order the settings dropdown shows them (ascending severity,
// OFF last). The pick is a threshold: entries at it or more severe are recorded, the
// rest dropped (buffer, file and logcat alike); OFF records nothing. ERROR is the
// default; a stored value outside the levels degrades to it.
enum class LogLevel(
    val severity: Int,
    val labelRes: Int,
) {
    DEBUG(0, R.string.log_level_debug),
    INFO(1, R.string.log_level_info),
    WARNING(2, R.string.log_level_warning),
    ERROR(3, R.string.log_level_error),
    OFF(4, R.string.option_off),
    ;

    companion object {
        val DEFAULT: LogLevel = ERROR

        // A missing or unknown value degrades to the default; a stored DEBUG degrades to
        // the default in release builds, where the level does not exist.
        fun fromStored(
            value: String?,
            debugBuild: Boolean = BuildConfig.DEBUG,
        ): LogLevel =
            (entries.firstOrNull { it.name == value } ?: DEFAULT)
                .takeIf { it != DEBUG || debugBuild } ?: DEFAULT
    }
}

// The theme modes in the order the settings dropdown shows them; the first is also the
// default.
enum class ThemeMode(
    val labelRes: Int,
) {
    SYSTEM(R.string.settings_system_default),
    LIGHT(R.string.settings_theme_light),
    DARK(R.string.settings_theme_dark),
    ;

    companion object {
        // Parses a stored mode name (the enum name, as written to the prefs). A missing or
        // unknown value degrades to SYSTEM.
        fun fromStored(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

// The duplicate protection levels in the order the settings dropdown shows them;
// BALANCED is also the default. One window per level, used for both paths: the live
// dedup key's time bucket (DedupUtil) and the range match's ± bound. The window is how
// close two timestamps must be to count as the same message. OFF is a zero window: it
// still dedups, but only on an exact timeReceived match (both paths) — the weakest
// protection, hence labelled "not recommended" in the settings.
enum class DedupLevel(
    val windowMillis: Long,
    val labelRes: Int,
) {
    AGGRESSIVE(60_000L, R.string.settings_dedup_level_aggressive),
    BALANCED(10_000L, R.string.settings_dedup_level_balanced),
    MINIMAL(3_000L, R.string.settings_dedup_level_minimal),
    OFF(0L, R.string.settings_dedup_level_off),
    ;

    companion object {
        // Parses a stored level name (the enum name, as written to the prefs). A missing
        // or unknown value degrades to BALANCED.
        fun fromStored(value: String?): DedupLevel = entries.firstOrNull { it.name == value } ?: BALANCED
    }
}
