package app.kotowski.sms2email.data.prefs

import android.content.Context
import android.content.SharedPreferences
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.DedupLevel
import app.kotowski.sms2email.LogLevel
import app.kotowski.sms2email.ThemeMode
import app.kotowski.sms2email.data.filter.FilterMode
import app.kotowski.sms2email.data.filter.FilterRule
import app.kotowski.sms2email.data.filter.SmsFilter
import app.kotowski.sms2email.data.prefs.SmtpProtocol
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.any
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Plain JVM tests: the constructor is exercised through the internal create-factory seam, so no
// keystore or Robolectric is needed (the real org.json on the test classpath runs the filter's
// JSON). Every setting, including the SMTP password, lives in the single sms2email_prefs file.
class EncryptedPrefsTest {
    private val context = Mockito.mock(Context::class.java)

    @Test
    fun `success opens the prefs file without deleting`() {
        val opened = mutableListOf<String>()
        EncryptedPrefs(context) { fileName, _ ->
            opened += fileName
            InMemorySharedPreferences()
        }
        assertEquals(listOf("sms2email_prefs"), opened)
        verify(context, never()).deleteSharedPreferences(any())
    }

    @Test
    fun `unreadable prefs file propagates and is never deleted`() {
        val attempts = mutableMapOf<String, Int>()
        assertFailsWith<IOException> {
            EncryptedPrefs(context) { fileName, _ ->
                attempts[fileName] = attempts.getOrDefault(fileName, 0) + 1
                throw IOException("unreadable xml")
            }
        }
        assertEquals(1, attempts.getValue("sms2email_prefs"))
        verify(context, never()).deleteSharedPreferences(any())
    }

    @Test
    fun `keystore failure does not delete and propagates`() {
        val opened = mutableListOf<String>()
        val failure =
            assertFailsWith<GeneralSecurityException> {
                EncryptedPrefs(context) { fileName, _ ->
                    opened += fileName
                    throw GeneralSecurityException("keystore unavailable")
                }
            }
        assertEquals("keystore unavailable", failure.message)
        assertEquals(listOf("sms2email_prefs"), opened)
        verify(context, never()).deleteSharedPreferences(any())
    }

    // A corrupt (undecryptable) value is not recovered in place: the first getter that reads it
    // throws, so construction fails and the entry point shows the settings-error dialog.
    @Test
    fun `a corrupt stored value propagates from construction`() {
        val main = InMemorySharedPreferences().apply { failGetBoolean = true }
        assertFailsWith<SecurityException> {
            EncryptedPrefs(context) { _, _ -> main }
        }
    }

    @Test
    fun `the saved settings round-trip through the prefs file`() {
        val main = InMemorySharedPreferences()
        val prefs = EncryptedPrefs(context) { _, _ -> main }
        prefs.save(AppSettings(smtpHost = "smtp.example.com", smtpPort = 2525, isForwardingEnabled = false))
        prefs.smtpPassword = "secret"
        prefs.themeMode = ThemeMode.LIGHT
        assertEquals("smtp.example.com", prefs.load().smtpHost)
        assertEquals(2525, prefs.load().smtpPort)
        assertEquals(false, prefs.load().isForwardingEnabled)
        assertEquals("secret", prefs.smtpPassword)
        assertEquals(ThemeMode.LIGHT, prefs.themeMode)
        assertEquals("smtp.example.com", main.value("smtp_host"))
        assertEquals(false, main.value("forwarding_enabled"))
        assertEquals("secret", main.value("smtp_password"))
        assertEquals("LIGHT", main.value("theme_mode"))
    }

    @Test
    fun `a snapshot save never makes a key explicit, a deliberate choice does`() {
        val main = InMemorySharedPreferences()
        val prefs = EncryptedPrefs(context) { _, _ -> main }
        assertFalse(prefs.contains(EncryptedPrefs.KEY_CONTACT_RESOLUTION))

        prefs.update { it.copy(useContactResolution = false) }

        // The raw key is written, but a snapshot save is not an explicit choice.
        assertTrue(main.contains(EncryptedPrefs.KEY_CONTACT_RESOLUTION))
        assertFalse(prefs.contains(EncryptedPrefs.KEY_CONTACT_RESOLUTION))

        prefs.markExplicit(EncryptedPrefs.KEY_CONTACT_RESOLUTION)
        assertTrue(prefs.contains(EncryptedPrefs.KEY_CONTACT_RESOLUTION))

        // The marker survives a re-open, so the choice is remembered across launches.
        val reopened = EncryptedPrefs(context) { _, _ -> main }
        assertTrue(reopened.contains(EncryptedPrefs.KEY_CONTACT_RESOLUTION))
        assertFalse(reopened.contains(EncryptedPrefs.KEY_DISPLAY_SIM_CARDS))
    }

    @Test
    fun `a file without the explicit marker yields no explicit keys`() {
        val main = InMemorySharedPreferences()
        main.value(EncryptedPrefs.KEY_DISPLAY_SIM_CARDS, false)
        val prefs = EncryptedPrefs(context) { _, _ -> main }
        assertFalse(prefs.contains(EncryptedPrefs.KEY_DISPLAY_SIM_CARDS))
        assertFalse(prefs.contains(EncryptedPrefs.KEY_CONTACT_RESOLUTION))

        // A snapshot save writes every key but never makes one explicit, so a re-open
        // still sees no explicit keys.
        prefs.save(AppSettings())
        val afterSave = EncryptedPrefs(context) { _, _ -> main }
        assertFalse(afterSave.contains(EncryptedPrefs.KEY_DISPLAY_SIM_CARDS))
        assertFalse(afterSave.contains(EncryptedPrefs.KEY_CONTACT_RESOLUTION))
    }

    @Test
    fun `unknown stored protocol degrades to START_TLS`() {
        val main = InMemorySharedPreferences()
        main.value("smtp_protocol", "GARBAGE")
        val prefs = EncryptedPrefs(context) { _, _ -> main }
        assertEquals(SmtpProtocol.START_TLS, prefs.load().smtpProtocol)
    }

    @Test
    fun `theme mode outside the modes degrades to system`() {
        val main = InMemorySharedPreferences()
        main.value("theme_mode", "neon")
        val prefs = EncryptedPrefs(context) { _, _ -> main }
        assertEquals(ThemeMode.SYSTEM, prefs.themeMode)
    }

    @Test
    fun `use pdu timestamp defaults to off and round-trips`() {
        val main = InMemorySharedPreferences()
        val prefs = EncryptedPrefs(context) { _, _ -> main }
        assertFalse(prefs.load().usePduTimestamp)
        prefs.update { it.copy(usePduTimestamp = true) }
        assertTrue(prefs.load().usePduTimestamp)
        assertEquals(true, main.value(EncryptedPrefs.KEY_USE_PDU_TIMESTAMP))
        prefs.update { it.copy(usePduTimestamp = false) }
        assertFalse(prefs.load().usePduTimestamp)
        assertEquals(false, main.value(EncryptedPrefs.KEY_USE_PDU_TIMESTAMP))
    }

    @Test
    fun `include time in body defaults to off and round-trips`() {
        val main = InMemorySharedPreferences()
        val prefs = EncryptedPrefs(context) { _, _ -> main }
        assertFalse(prefs.load().includeTimeInBody)
        prefs.update { it.copy(includeTimeInBody = true) }
        assertTrue(prefs.load().includeTimeInBody)
        assertEquals(true, main.value(EncryptedPrefs.KEY_INCLUDE_TIME_IN_BODY))
        prefs.update { it.copy(includeTimeInBody = false) }
        assertFalse(prefs.load().includeTimeInBody)
        assertEquals(false, main.value(EncryptedPrefs.KEY_INCLUDE_TIME_IN_BODY))
    }

    @Test
    fun `dedup level defaults to balanced and round-trips as the enum name`() {
        val main = InMemorySharedPreferences()
        val prefs = EncryptedPrefs(context) { _, _ -> main }
        assertEquals(DedupLevel.BALANCED, prefs.load().dedupLevel)
        prefs.update { it.copy(dedupLevel = DedupLevel.OFF) }
        assertEquals(DedupLevel.OFF, prefs.load().dedupLevel)
        assertEquals("OFF", main.value(EncryptedPrefs.KEY_DEDUP_LEVEL))
    }

    @Test
    fun `dedup level outside the levels degrades to balanced`() {
        val main = InMemorySharedPreferences()
        main.value("dedup_level", "neon")
        val prefs = EncryptedPrefs(context) { _, _ -> main }
        assertEquals(DedupLevel.BALANCED, prefs.load().dedupLevel)
    }

    @Test
    fun `log level defaults to error and round-trips as the enum name`() {
        val main = InMemorySharedPreferences()
        val prefs = EncryptedPrefs(context) { _, _ -> main }
        assertEquals(LogLevel.ERROR, prefs.load().logLevel)
        prefs.update { it.copy(logLevel = LogLevel.OFF) }
        assertEquals(LogLevel.OFF, prefs.load().logLevel)
        assertEquals("OFF", main.value(EncryptedPrefs.KEY_LOG_LEVEL))
    }

    @Test
    fun `log level outside the levels degrades to error`() {
        val main = InMemorySharedPreferences()
        main.value("log_level", "verbose")
        val prefs = EncryptedPrefs(context) { _, _ -> main }
        assertEquals(LogLevel.ERROR, prefs.load().logLevel)
    }

    @Test
    fun `background flags default to forwarding on and cleanup off`() {
        val prefs = EncryptedPrefs(context) { _, _ -> InMemorySharedPreferences() }
        assertEquals(BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false), prefs.backgroundFlags.value)
    }

    @Test
    fun `background flags seed from the stored values`() {
        val main = InMemorySharedPreferences()
        main.value("forwarding_enabled", false)
        main.value("auto_cleanup_retention_days", 30)
        val prefs = EncryptedPrefs(context) { _, _ -> main }
        assertEquals(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = true), prefs.backgroundFlags.value)
    }

    @Test
    fun `setting a flag updates the flow and the stored value`() {
        val main = InMemorySharedPreferences()
        val prefs = EncryptedPrefs(context) { _, _ -> main }

        prefs.update { it.copy(isForwardingEnabled = false) }
        assertEquals(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = false), prefs.backgroundFlags.value)
        assertEquals(false, main.value("forwarding_enabled"))

        prefs.update { it.copy(autoCleanupRetentionDays = 30) }
        assertEquals(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = true), prefs.backgroundFlags.value)
        assertEquals(30, main.value("auto_cleanup_retention_days"))
    }

    @Test
    fun `flag loads track the flow value`() {
        val prefs = EncryptedPrefs(context) { _, _ -> InMemorySharedPreferences() }
        assertTrue(prefs.load().isForwardingEnabled)
        assertEquals(0, prefs.load().autoCleanupRetentionDays)
        prefs.update { it.copy(autoCleanupRetentionDays = 30) }
        assertEquals(30, prefs.load().autoCleanupRetentionDays)
    }

    @Test
    fun `the sticky notification setting defaults to on in the load and the flow seed`() {
        val prefs = EncryptedPrefs(context) { _, _ -> InMemorySharedPreferences() }
        assertTrue(prefs.load().showStickyNotification)
        assertTrue(prefs.backgroundFlags.value.showStickyNotification)
    }

    @Test
    fun `the sticky notification setting saves, loads and rides the flow`() {
        val main = InMemorySharedPreferences()
        val prefs = EncryptedPrefs(context) { _, _ -> main }

        prefs.save(AppSettings(showStickyNotification = false))
        assertEquals(false, main.value(EncryptedPrefs.KEY_SHOW_STICKY_NOTIFICATION))
        assertFalse(prefs.load().showStickyNotification)
        assertFalse(prefs.backgroundFlags.value.showStickyNotification)
    }

    @Test
    fun `a save that does not change the sticky setting keeps the stored value`() {
        val main = InMemorySharedPreferences()
        val prefs = EncryptedPrefs(context) { _, _ -> main }

        prefs.update { it.copy(showStickyNotification = false) }
        prefs.update { it.copy(smtpHost = "smtp.example.com") }
        assertEquals(false, main.value(EncryptedPrefs.KEY_SHOW_STICKY_NOTIFICATION))
        assertFalse(prefs.load().showStickyNotification)
    }

    @Test
    fun `country settings default to auto on with an empty code and round-trip`() {
        val main = InMemorySharedPreferences()
        val prefs = EncryptedPrefs(context) { _, _ -> main }
        assertTrue(prefs.load().autoCountry)
        assertEquals("", prefs.load().countryCode)

        prefs.update { it.copy(autoCountry = false, countryCode = "DE") }
        assertFalse(prefs.load().autoCountry)
        assertEquals("DE", prefs.load().countryCode)
        assertEquals(false, main.value(EncryptedPrefs.KEY_AUTO_COUNTRY))
        assertEquals("DE", main.value(EncryptedPrefs.KEY_COUNTRY_CODE))

        prefs.update { it.copy(autoCountry = true) }
        assertTrue(prefs.load().autoCountry)
        assertEquals(true, main.value(EncryptedPrefs.KEY_AUTO_COUNTRY))
    }

    @Test
    fun `log to file defaults to off with the shipped size and round-trips`() {
        val main = InMemorySharedPreferences()
        val prefs = EncryptedPrefs(context) { _, _ -> main }
        assertFalse(prefs.load().logToFileEnabled)
        assertEquals(AppConfig.LOG_FILE_MAX_BYTES, prefs.load().logFileSizeBytes)

        prefs.update { it.copy(logToFileEnabled = true, logFileSizeBytes = 1024 * 1024) }
        assertTrue(prefs.load().logToFileEnabled)
        assertEquals(1024 * 1024, prefs.load().logFileSizeBytes)
        assertEquals(true, main.value(EncryptedPrefs.KEY_LOG_TO_FILE_ENABLED))
        assertEquals(1024 * 1024, main.value(EncryptedPrefs.KEY_LOG_FILE_SIZE_BYTES))

        prefs.update { it.copy(logToFileEnabled = false) }
        assertFalse(prefs.load().logToFileEnabled)
        assertEquals(1024 * 1024, prefs.load().logFileSizeBytes)
    }

    @Test
    fun `load is a memory read and never touches the file after construction`() {
        val main = InMemorySharedPreferences()
        main.value("smtp_host", "smtp.example.com")
        main.value("forwarding_enabled", false)
        val prefs = EncryptedPrefs(context) { _, _ -> main }

        // The constructor seeds the snapshot from the file (the one full read).
        val seeded = main.reads
        assertTrue(seeded > 0)

        prefs.load()
        prefs.load()
        assertEquals(seeded, main.reads)

        // The write-through updates the file and the cache; the reads after it stay
        // in memory.
        prefs.update { it.copy(smtpHost = "other.example.com") }
        assertEquals("other.example.com", prefs.load().smtpHost)
        assertEquals(false, prefs.load().isForwardingEnabled)
        assertEquals(seeded, main.reads)
        assertEquals("other.example.com", main.value("smtp_host"))
    }

    @Test
    fun `a reopened instance seeds the snapshot from the file`() {
        val main = InMemorySharedPreferences()
        val prefs = EncryptedPrefs(context) { _, _ -> main }
        prefs.save(AppSettings(smtpHost = "smtp.example.com", isForwardingEnabled = false, autoCleanupRetentionDays = 365))

        val reopened = EncryptedPrefs(context) { _, _ -> main }
        assertEquals("smtp.example.com", reopened.load().smtpHost)
        assertFalse(reopened.load().isForwardingEnabled)
        assertEquals(365, reopened.load().autoCleanupRetentionDays)
        assertEquals(
            BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = true),
            reopened.backgroundFlags.value,
        )
    }

    @Test
    fun `the keys outside the snapshot stay direct file access`() {
        val main = InMemorySharedPreferences()
        val prefs = EncryptedPrefs(context) { _, _ -> main }

        prefs.smtpPassword = "secret"
        prefs.isOnboardingComplete = true
        prefs.scamWarningDismissed = true
        prefs.themeMode = ThemeMode.LIGHT

        assertEquals("secret", main.value("smtp_password"))
        assertEquals(true, main.value("onboarding_complete"))
        assertEquals(true, main.value("scam_warning_dismissed"))
        assertEquals("LIGHT", main.value("theme_mode"))

        // Each of them is one key read on the file, not served from the snapshot.
        val after = main.reads
        prefs.smtpPassword
        prefs.isOnboardingComplete
        prefs.scamWarningDismissed
        prefs.themeMode
        assertTrue(main.reads > after)
    }

    @Test
    fun `the sweep interval defaults to 15 minutes in the load and the flow seed`() {
        val prefs = EncryptedPrefs(context) { _, _ -> InMemorySharedPreferences() }
        assertEquals(15, prefs.load().retrySweepIntervalMinutes)
        assertEquals(15, prefs.backgroundFlags.value.retrySweepIntervalMinutes)
    }

    @Test
    fun `the sweep interval saves, loads, rides the flow and survives a reopen`() {
        val main = InMemorySharedPreferences()
        val prefs = EncryptedPrefs(context) { _, _ -> main }

        prefs.update { it.copy(retrySweepIntervalMinutes = 360) }

        assertEquals(360, prefs.load().retrySweepIntervalMinutes)
        assertEquals(360, prefs.backgroundFlags.value.retrySweepIntervalMinutes)

        // A fresh instance over the same file sees the stored period (the re-arm gate
        // of the reconciler reads it at every process start).
        val reopened = EncryptedPrefs(context) { _, _ -> main }
        assertEquals(360, reopened.load().retrySweepIntervalMinutes)
        assertEquals(360, reopened.backgroundFlags.value.retrySweepIntervalMinutes)
    }

    @Test
    fun `the exact wake-up defaults to off and round-trips`() {
        val prefs = EncryptedPrefs(context) { _, _ -> InMemorySharedPreferences() }
        assertFalse(prefs.load().exactWakeEnabled)

        prefs.update { it.copy(exactWakeEnabled = true) }

        assertTrue(prefs.load().exactWakeEnabled)
    }

    // The message filter: one JSON document key plus the feature-toggle key, the
    // document parsed once at the seed (a corrupt one degrades to the filter off).

    @Test
    fun `the filter config and the feature toggle round-trip through the prefs file`() {
        val main = InMemorySharedPreferences()
        val prefs = EncryptedPrefs(context) { _, _ -> main }
        val filter = SmsFilter(FilterMode.FORWARD, listOf(FilterRule(body = "vip", slots = listOf(2))))
        prefs.save(AppSettings(filter = filter, filterEnabled = true))

        assertEquals(filter.toJson(), main.value("filter_config"))
        assertEquals(true, main.value("filter_enabled"))
        assertEquals(filter, prefs.load().filter)
        assertTrue(prefs.load().filterEnabled)
        // A re-open re-parses the stored document at the seed.
        val reopened = EncryptedPrefs(context) { _, _ -> main }
        assertEquals(filter, reopened.load().filter)
        assertTrue(reopened.load().filterEnabled)
    }

    @Test
    fun `the filter defaults to off and disabled while the keys are absent`() {
        val prefs = EncryptedPrefs(context) { _, _ -> InMemorySharedPreferences() }
        assertEquals(SmsFilter(), prefs.load().filter)
        assertFalse(prefs.load().filterEnabled)
    }

    @Test
    fun `a corrupt filter config degrades to the filter off at the seed`() {
        val main = InMemorySharedPreferences()
        main.value("filter_config", "not json")
        main.value("filter_enabled", true)
        val prefs = EncryptedPrefs(context) { _, _ -> main }
        assertEquals(SmsFilter(), prefs.load().filter)
        assertTrue(prefs.load().filterEnabled)
    }

    @Test
    fun `the armed sweep interval and the drain clock are direct file access outside the snapshot`() {
        val main = InMemorySharedPreferences()
        val prefs = EncryptedPrefs(context) { _, _ -> main }
        assertEquals(0, prefs.armedSweepIntervalMinutes)
        assertEquals(0L, prefs.lastDrainAtMillis)

        prefs.armedSweepIntervalMinutes = 60
        prefs.lastDrainAtMillis = 1234L

        assertEquals(60, main.value("armed_sweep_interval_minutes"))
        assertEquals(1234L, main.value("last_drain_at_millis"))

        // A save of the snapshot does not touch the engine-state keys.
        prefs.save(prefs.load())
        assertEquals(60, prefs.armedSweepIntervalMinutes)
        assertEquals(1234L, prefs.lastDrainAtMillis)
    }
}

private class InMemorySharedPreferences : SharedPreferences {
    // Sets are stored wrapped in a final type so getStringSet reads them back without an
    // unchecked cast (K2 rejects `is MutableSet<String>` on an erased value).
    private class StringSet(
        val value: MutableSet<String>,
    )

    private val values = mutableMapOf<String, Any?>()

    // Simulates a corrupt (undecryptable) stored value.
    var failGetBoolean = false

    // Every file read (any getter) increments this, so a test can prove a call path is a
    // memory read and never touches the file.
    var reads = 0

    fun value(key: String): Any? = values[key]

    fun value(
        key: String,
        value: Any?,
    ) {
        values[key] = value
    }

    override fun getAll(): MutableMap<String, *> {
        reads++
        return values.toMutableMap()
    }

    override fun getString(
        key: String,
        defValue: String?,
    ): String? {
        reads++
        return values[key] as? String ?: defValue
    }

    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? {
        reads++
        return (values[key] as? StringSet)?.value ?: defValues
    }

    override fun getInt(
        key: String,
        defValue: Int,
    ): Int {
        reads++
        return values[key] as? Int ?: defValue
    }

    override fun getLong(
        key: String,
        defValue: Long,
    ): Long {
        reads++
        return values[key] as? Long ?: defValue
    }

    override fun getFloat(
        key: String,
        defValue: Float,
    ): Float {
        reads++
        return values[key] as? Float ?: defValue
    }

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean {
        reads++
        if (failGetBoolean) throw SecurityException("corrupt value")
        return values[key] as? Boolean ?: defValue
    }

    override fun contains(key: String): Boolean {
        reads++
        return values.containsKey(key)
    }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class Editor : SharedPreferences.Editor {
        private var pending = mutableMapOf<String, Any?>()

        private var cleared = false

        override fun putString(
            key: String,
            value: String?,
        ): SharedPreferences.Editor = put(key, value)

        override fun putStringSet(
            key: String,
            value: MutableSet<String>?,
        ): SharedPreferences.Editor = put(key, value?.let { StringSet(it) })

        override fun putInt(
            key: String,
            value: Int,
        ): SharedPreferences.Editor = put(key, value)

        override fun putLong(
            key: String,
            value: Long,
        ): SharedPreferences.Editor = put(key, value)

        override fun putFloat(
            key: String,
            value: Float,
        ): SharedPreferences.Editor = put(key, value)

        override fun putBoolean(
            key: String,
            value: Boolean,
        ): SharedPreferences.Editor = put(key, value)

        override fun remove(key: String): SharedPreferences.Editor {
            pending -= key
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            cleared = true
            pending = mutableMapOf()
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (cleared) values.clear()
            values.putAll(pending)
            pending = mutableMapOf()
            cleared = false
        }

        private fun put(
            key: String,
            value: Any?,
        ): SharedPreferences.Editor {
            pending[key] = value
            return this
        }
    }
}
