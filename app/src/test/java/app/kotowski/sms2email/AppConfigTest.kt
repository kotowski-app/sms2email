package app.kotowski.sms2email

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppConfigTest {
    @Test
    fun `SCAM_WARNING_TIMER_SECONDS is 20`() {
        assertEquals(20, AppConfig.SCAM_WARNING_TIMER_SECONDS)
    }

    @Test
    fun `SMTP_PORT_START_TLS is 587`() {
        assertEquals(587, AppConfig.SMTP_PORT_START_TLS)
    }

    @Test
    fun `SMTP_PORT_IMPLICIT_TLS is 465`() {
        assertEquals(465, AppConfig.SMTP_PORT_IMPLICIT_TLS)
    }

    @Test
    fun `MAX_RETRY_PERIOD_MS is 2 weeks`() {
        assertEquals(1_209_600_000L, AppConfig.MAX_RETRY_PERIOD_MS)
    }

    @Test
    fun `BASE_BACKOFF_MS is 5 seconds`() {
        assertEquals(5_000L, AppConfig.BASE_BACKOFF_MS)
    }

    @Test
    fun `LIVE_TICK_INTERVAL_MS is 1 second`() {
        assertEquals(1_000L, AppConfig.LIVE_TICK_INTERVAL_MS)
    }

    @Test
    fun `SMS_LOAD_MORE_TRIGGER_THRESHOLD is 2`() {
        assertEquals(2, AppConfig.SMS_LOAD_MORE_TRIGGER_THRESHOLD)
    }

    @Test
    fun `SMTP_SEND_TIMEOUT_MS is 3 minutes and stays below the SENDING stale threshold`() {
        assertEquals(180_000L, AppConfig.SMTP_SEND_TIMEOUT_MS)
        // A send that outlives the stale threshold can be flipped RETRYING mid-flight and
        // re-sent after it completes (duplicate); keep the hard send bound below it.
        assertTrue(AppConfig.SMTP_SEND_TIMEOUT_MS < AppConfig.RETRY_SENDING_STALE_MS)
    }

    @Test
    fun `SMTP_TEST_CONNECTION_TIMEOUT_MS is 30 seconds`() {
        assertEquals(30_000L, AppConfig.SMTP_TEST_CONNECTION_TIMEOUT_MS)
    }

    @Test
    fun `SMTP connect read and write timeouts are 5 15 and 25 seconds`() {
        assertEquals(5_000L, AppConfig.SMTP_CONNECT_TIMEOUT_MS)
        assertEquals(15_000L, AppConfig.SMTP_READ_TIMEOUT_MS)
        assertEquals(25_000L, AppConfig.SMTP_WRITE_TIMEOUT_MS)
        assertTrue(AppConfig.SMTP_CONNECT_TIMEOUT_MS < AppConfig.SMTP_READ_TIMEOUT_MS)
        assertTrue(AppConfig.SMTP_READ_TIMEOUT_MS < AppConfig.SMTP_WRITE_TIMEOUT_MS)
    }

    @Test
    fun `sweep interval presets span the WorkManager range from off to 24 hours`() {
        assertEquals(listOf(0, 15, 60, 360, 720, 1440), SweepIntervalPreset.minutes)
    }

    @Test
    fun `RETRY stale thresholds are both 5 minutes`() {
        assertEquals(5 * 60_000L, AppConfig.RETRY_PENDING_STALE_MS)
        assertEquals(5 * 60_000L, AppConfig.RETRY_SENDING_STALE_MS)
    }

    @Test
    fun `RETRY backoff base is 2 and jitter fraction is 10 percent`() {
        assertEquals(2.0, AppConfig.RETRY_BACKOFF_GROWTH_BASE)
        assertEquals(0.10, AppConfig.RETRY_BACKOFF_JITTER_FRACTION)
    }

    @Test
    fun `BATCH_DUE_CHECK_SLACK_MS is 60 seconds`() {
        assertEquals(60_000L, AppConfig.BATCH_DUE_CHECK_SLACK_MS)
    }

    @Test
    fun `HISTORY_CLEANUP_PERIOD_DAYS is 1`() {
        assertEquals(1L, AppConfig.HISTORY_CLEANUP_PERIOD_DAYS)
    }

    @Test
    fun `CONTACT_MIN_NUMBER_LENGTH is 3 and CONTACTS_CACHE_TTL_MS is 60 seconds`() {
        assertEquals(3, AppConfig.CONTACT_MIN_NUMBER_LENGTH)
        assertEquals(60_000L, AppConfig.CONTACTS_CACHE_TTL_MS)
    }

    @Test
    fun `CONTACT_FAST_PATH_MIN_LENGTH is 7`() {
        assertEquals(7, AppConfig.CONTACT_FAST_PATH_MIN_LENGTH)
    }

    @Test
    fun `DETECTED_REGION_CACHE_TTL_MS is 60 seconds`() {
        assertEquals(60_000L, AppConfig.DETECTED_REGION_CACHE_TTL_MS)
    }

    @Test
    fun `SIM_NAME_CACHE_TTL_MS is 60 seconds`() {
        assertEquals(60_000L, AppConfig.SIM_NAME_CACHE_TTL_MS)
    }

    @Test
    fun `SETTINGS_IMPORT_MAX_FILE_SIZE is 1 MiB`() {
        assertEquals(1L shl 20, AppConfig.SETTINGS_IMPORT_MAX_FILE_SIZE)
    }

    @Test
    fun `SETTINGS_IMPORT_READ_CHUNK_SIZE is 8 KiB`() {
        assertEquals(8192, AppConfig.SETTINGS_IMPORT_READ_CHUNK_SIZE)
    }

    @Test
    fun `LanguageOption tags are the system default, english, russian and chinese`() {
        assertEquals(setOf("", "en", "ru", "zh"), LanguageOption.tags)
    }

    @Test
    fun `LanguageOption labels map to the language string resources`() {
        assertEquals(R.string.settings_system_default, LanguageOption.SYSTEM.labelRes)
        assertEquals(R.string.settings_language_english, LanguageOption.ENGLISH.labelRes)
        assertEquals(R.string.settings_language_russian, LanguageOption.RUSSIAN.labelRes)
        assertEquals(R.string.settings_language_chinese, LanguageOption.CHINESE.labelRes)
    }

    @Test
    fun `ThemeMode entries are the three modes in dropdown order`() {
        assertEquals(
            listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
            ThemeMode.entries.toList(),
        )
    }

    @Test
    fun `ThemeMode labels map to the theme string resources`() {
        assertEquals(R.string.settings_system_default, ThemeMode.SYSTEM.labelRes)
        assertEquals(R.string.settings_theme_light, ThemeMode.LIGHT.labelRes)
        assertEquals(R.string.settings_theme_dark, ThemeMode.DARK.labelRes)
    }

    @Test
    fun `ThemeMode fromStored parses a stored name`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored("SYSTEM"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStored("LIGHT"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStored("DARK"))
    }

    @Test
    fun `ThemeMode fromStored degrades a missing or unknown value to SYSTEM`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored("neon"))
    }

    @Test
    fun `DedupLevel entries are the four levels in dropdown order`() {
        assertEquals(
            listOf(DedupLevel.AGGRESSIVE, DedupLevel.BALANCED, DedupLevel.MINIMAL, DedupLevel.OFF),
            DedupLevel.entries.toList(),
        )
    }

    @Test
    fun `DedupLevel windows are 60s, 10s, 3s and zero (off)`() {
        assertEquals(60_000L, DedupLevel.AGGRESSIVE.windowMillis)
        assertEquals(10_000L, DedupLevel.BALANCED.windowMillis)
        assertEquals(3_000L, DedupLevel.MINIMAL.windowMillis)
        assertEquals(0L, DedupLevel.OFF.windowMillis)
    }

    @Test
    fun `DedupLevel labels map to the settings string resources`() {
        assertEquals(R.string.settings_dedup_level_aggressive, DedupLevel.AGGRESSIVE.labelRes)
        assertEquals(R.string.settings_dedup_level_balanced, DedupLevel.BALANCED.labelRes)
        assertEquals(R.string.settings_dedup_level_minimal, DedupLevel.MINIMAL.labelRes)
        assertEquals(R.string.settings_dedup_level_off, DedupLevel.OFF.labelRes)
    }

    @Test
    fun `DedupLevel fromStored parses a stored name`() {
        assertEquals(DedupLevel.AGGRESSIVE, DedupLevel.fromStored("AGGRESSIVE"))
        assertEquals(DedupLevel.BALANCED, DedupLevel.fromStored("BALANCED"))
        assertEquals(DedupLevel.MINIMAL, DedupLevel.fromStored("MINIMAL"))
        assertEquals(DedupLevel.OFF, DedupLevel.fromStored("OFF"))
    }

    @Test
    fun `DedupLevel fromStored degrades a missing or unknown value to BALANCED`() {
        assertEquals(DedupLevel.BALANCED, DedupLevel.fromStored(null))
        assertEquals(DedupLevel.BALANCED, DedupLevel.fromStored("neon"))
    }
}
