package app.kotowski.sms2email.service

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.DedupLevel
import app.kotowski.sms2email.LogLevel
import app.kotowski.sms2email.LogSizePreset
import app.kotowski.sms2email.PeriodPreset
import app.kotowski.sms2email.SweepIntervalPreset
import app.kotowski.sms2email.data.filter.FilterMode
import app.kotowski.sms2email.data.filter.FilterRule
import app.kotowski.sms2email.data.filter.SmsFilter
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.data.prefs.SmtpProtocol
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SettingsExportImportTest {
    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var prefs2: EncryptedPrefs

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        setupPrefs(prefs, true)
        setupPrefs(prefs2, false)
    }

    private fun setupPrefs(
        p: EncryptedPrefs,
        useValues: Boolean,
    ) {
        whenever(p.load()).thenReturn(
            AppSettings(
                smtpHost = if (useValues) "smtp.example.com" else "",
                smtpPort = if (useValues) 465 else 587,
                smtpProtocol = if (useValues) SmtpProtocol.IMPLICIT_TLS else SmtpProtocol.START_TLS,
                smtpUsername = if (useValues) "user@example.com" else "",
                destEmail = if (useValues) "dest@example.com" else "",
                destName = if (useValues) "Dest" else "",
                senderEmail = if (useValues) "sender@example.com" else "",
                senderName = if (useValues) "Sender" else "",
                subjectPrefix = if (useValues) "MyPhone" else "",
                autoSubjectPrefix = !useValues,
                isForwardingEnabled = useValues,
                useContactResolution = useValues,
                displaySimCards = !useValues,
                autoCleanupRetentionDays = if (useValues) 365 else 0,
                useJsonEmailFormat = !useValues,
                includeTimeInBody = useValues,
                usePduTimestamp = useValues,
                dedupLevel = if (useValues) DedupLevel.AGGRESSIVE else DedupLevel.MINIMAL,
                showStickyNotification = useValues,
                logToFileEnabled = useValues,
                logFileSizeBytes = if (useValues) 1024 * 1024 else 256 * 1024,
                retrySweepIntervalMinutes = if (useValues) 360 else 0,
                exactWakeEnabled = useValues,
            ),
        )
    }

    // The full snapshot the "values" prefs export; the import tests verify the saved
    // settings against it, with a per-test copy() for the mutated fields.
    private val exported =
        AppSettings(
            smtpHost = "smtp.example.com",
            smtpPort = 465,
            smtpProtocol = SmtpProtocol.IMPLICIT_TLS,
            smtpUsername = "user@example.com",
            destEmail = "dest@example.com",
            destName = "Dest",
            senderEmail = "sender@example.com",
            senderName = "Sender",
            subjectPrefix = "MyPhone",
            autoSubjectPrefix = false,
            isForwardingEnabled = true,
            useContactResolution = true,
            displaySimCards = false,
            autoCleanupRetentionDays = 365,
            useJsonEmailFormat = false,
            includeTimeInBody = true,
            usePduTimestamp = true,
            dedupLevel = DedupLevel.AGGRESSIVE,
            showStickyNotification = true,
            logToFileEnabled = true,
            logFileSizeBytes = 1024 * 1024,
            retrySweepIntervalMinutes = 360,
            exactWakeEnabled = true,
        )

    @Test
    fun `export produces valid JSON with version`() {
        val json = SettingsExportImport.exportToJson(prefs)
        val obj = JSONObject(json)
        assertEquals(1, obj.getInt("version"))
    }

    @Test
    fun `export includes all settings`() {
        val json = SettingsExportImport.exportToJson(prefs)
        val obj = JSONObject(json)
        assertEquals("smtp.example.com", obj.getString("smtp_host"))
        assertEquals(465, obj.getInt("smtp_port"))
        assertEquals("IMPLICIT_TLS", obj.getString("smtp_protocol"))
        assertEquals("user@example.com", obj.getString("smtp_username"))
        assertEquals("dest@example.com", obj.getString("dest_email"))
        assertEquals("Dest", obj.getString("dest_name"))
        assertEquals("sender@example.com", obj.getString("sender_email"))
        assertEquals("Sender", obj.getString("sender_name"))
        assertEquals("MyPhone", obj.getString("subject_prefix"))
        assertEquals(false, obj.getBoolean("auto_subject_prefix"))
        assertEquals("", obj.getString("country_code"))
        assertEquals(true, obj.getBoolean("auto_country"))
        assertEquals(true, obj.getBoolean("forwarding_enabled"))
        assertEquals(true, obj.getBoolean("contact_resolution"))
        assertEquals(false, obj.getBoolean("display_sim_cards"))
        assertEquals(365, obj.getInt("auto_cleanup_retention_days"))
        assertEquals(false, obj.getBoolean("json_email_format"))
        assertEquals(true, obj.getBoolean("include_time_in_body"))
        assertEquals(true, obj.getBoolean("use_pdu_timestamp"))
        assertEquals("AGGRESSIVE", obj.getString("dedup_level"))
        assertEquals(true, obj.getBoolean("show_sticky_notification"))
        assertEquals(true, obj.getBoolean("log_to_file_enabled"))
        assertEquals(1024 * 1024, obj.getInt("log_file_size_bytes"))
        assertEquals(360, obj.getInt("retry_sweep_interval_minutes"))
        assertEquals(true, obj.getBoolean("exact_wake_enabled"))
        // The filter travels as one escaped string (the whole config document).
        assertEquals(SmsFilter().toJson(), obj.getString("filter_config"))
        // The feature toggle as a plain boolean (the product default, off).
        assertEquals(false, obj.getBoolean("filter_enabled"))
    }

    @Test
    fun `export does not include password`() {
        val json = SettingsExportImport.exportToJson(prefs)
        val obj = JSONObject(json)
        assertFalse(obj.has("smtp_password"))
    }

    @Test
    fun `export does not include onboarding complete`() {
        val json = SettingsExportImport.exportToJson(prefs)
        val obj = JSONObject(json)
        assertFalse(obj.has("onboarding_complete"))
    }

    @Test
    fun `export does not include scam warning dismissed`() {
        val json = SettingsExportImport.exportToJson(prefs)
        val obj = JSONObject(json)
        assertFalse(obj.has("scam_warning_dismissed"))
    }

    @Test
    fun `import does not throw with valid JSON`() {
        val json = SettingsExportImport.exportToJson(prefs)
        SettingsExportImport.importFromJson(prefs2, json)
    }

    @Test
    fun `import writes all settings from a complete file`() {
        val json = SettingsExportImport.exportToJson(prefs)
        SettingsExportImport.importFromJson(prefs2, json)
        verify(prefs2).save(exported)
        // Every key present in the file joins the explicit marker (the same order the
        // import marks them in).
        verify(prefs2).markExplicit(
            EncryptedPrefs.KEY_SMTP_HOST,
            EncryptedPrefs.KEY_SMTP_PORT,
            EncryptedPrefs.KEY_SMTP_PROTOCOL,
            EncryptedPrefs.KEY_SMTP_USERNAME,
            EncryptedPrefs.KEY_DEST_EMAIL,
            EncryptedPrefs.KEY_DEST_NAME,
            EncryptedPrefs.KEY_SENDER_EMAIL,
            EncryptedPrefs.KEY_SENDER_NAME,
            EncryptedPrefs.KEY_SUBJECT_PREFIX,
            EncryptedPrefs.KEY_AUTO_SUBJECT_PREFIX,
            EncryptedPrefs.KEY_COUNTRY_CODE,
            EncryptedPrefs.KEY_AUTO_COUNTRY,
            EncryptedPrefs.KEY_FORWARDING_ENABLED,
            EncryptedPrefs.KEY_CONTACT_RESOLUTION,
            EncryptedPrefs.KEY_DISPLAY_SIM_CARDS,
            EncryptedPrefs.KEY_AUTO_CLEANUP_RETENTION_DAYS,
            EncryptedPrefs.KEY_JSON_EMAIL_FORMAT,
            EncryptedPrefs.KEY_INCLUDE_TIME_IN_BODY,
            EncryptedPrefs.KEY_USE_PDU_TIMESTAMP,
            EncryptedPrefs.KEY_DEDUP_LEVEL,
            EncryptedPrefs.KEY_SHOW_STICKY_NOTIFICATION,
            EncryptedPrefs.KEY_LOG_TO_FILE_ENABLED,
            EncryptedPrefs.KEY_LOG_FILE_SIZE_BYTES,
            EncryptedPrefs.KEY_LOG_LEVEL,
            EncryptedPrefs.KEY_RETRY_SWEEP_INTERVAL_MINUTES,
            EncryptedPrefs.KEY_EXACT_WAKE_ENABLED,
            EncryptedPrefs.KEY_FILTER_CONFIG,
            EncryptedPrefs.KEY_FILTER_ENABLED,
        )
    }

    @Test
    fun `import marks only the present keys explicit, absent optional keys stay overridable`() {
        val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
        obj.remove("dedup_level")
        obj.remove("show_sticky_notification")
        SettingsExportImport.importFromJson(prefs2, obj.toString())
        // The absent optional keys must not join the explicit marker: the SENDER step's
        // ensure* may still apply its permission-derived defaults to them.
        verify(prefs2).markExplicit(
            EncryptedPrefs.KEY_SMTP_HOST,
            EncryptedPrefs.KEY_SMTP_PORT,
            EncryptedPrefs.KEY_SMTP_PROTOCOL,
            EncryptedPrefs.KEY_SMTP_USERNAME,
            EncryptedPrefs.KEY_DEST_EMAIL,
            EncryptedPrefs.KEY_DEST_NAME,
            EncryptedPrefs.KEY_SENDER_EMAIL,
            EncryptedPrefs.KEY_SENDER_NAME,
            EncryptedPrefs.KEY_SUBJECT_PREFIX,
            EncryptedPrefs.KEY_AUTO_SUBJECT_PREFIX,
            EncryptedPrefs.KEY_COUNTRY_CODE,
            EncryptedPrefs.KEY_AUTO_COUNTRY,
            EncryptedPrefs.KEY_FORWARDING_ENABLED,
            EncryptedPrefs.KEY_CONTACT_RESOLUTION,
            EncryptedPrefs.KEY_DISPLAY_SIM_CARDS,
            EncryptedPrefs.KEY_AUTO_CLEANUP_RETENTION_DAYS,
            EncryptedPrefs.KEY_JSON_EMAIL_FORMAT,
            EncryptedPrefs.KEY_INCLUDE_TIME_IN_BODY,
            EncryptedPrefs.KEY_USE_PDU_TIMESTAMP,
            EncryptedPrefs.KEY_LOG_TO_FILE_ENABLED,
            EncryptedPrefs.KEY_LOG_FILE_SIZE_BYTES,
            EncryptedPrefs.KEY_LOG_LEVEL,
            EncryptedPrefs.KEY_RETRY_SWEEP_INTERVAL_MINUTES,
            EncryptedPrefs.KEY_EXACT_WAKE_ENABLED,
            EncryptedPrefs.KEY_FILTER_CONFIG,
            EncryptedPrefs.KEY_FILTER_ENABLED,
        )
    }

    @Test
    fun `import with partial JSON throws exception`() {
        val json = """{"version":1,"smtp_host":"smtp.example.com"}"""
        Assert.assertThrows(JSONException::class.java) { SettingsExportImport.importFromJson(prefs2, json) }
    }

    @Test
    fun `import with empty JSON throws exception`() {
        val json = """{"version":1}"""
        Assert.assertThrows(JSONException::class.java) { SettingsExportImport.importFromJson(prefs2, json) }
    }

    @Test
    fun `import with invalid JSON throws exception`() {
        Assert.assertThrows(JSONException::class.java) { SettingsExportImport.importFromJson(prefs2, "not json") }
    }

    @Test
    fun `import with unsupported version throws exception`() {
        val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
        obj.put("version", 2)
        Assert.assertThrows(JSONException::class.java) { SettingsExportImport.importFromJson(prefs2, obj.toString()) }
    }

    @Test
    fun `import without version throws exception`() {
        val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
        obj.remove("version")
        Assert.assertThrows(JSONException::class.java) { SettingsExportImport.importFromJson(prefs2, obj.toString()) }
    }

    @Test
    fun `import with any missing required key throws exception`() {
        val keys =
            listOf(
                "smtp_host",
                "smtp_port",
                "smtp_protocol",
                "smtp_username",
                "dest_email",
                "sender_email",
            )
        for (key in keys) {
            val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
            obj.remove(key)
            val ex =
                Assert.assertThrows(JSONException::class.java) {
                    SettingsExportImport.importFromJson(prefs2, obj.toString())
                }
            assertEquals("Missing required key: $key", ex.message)
        }
    }

    @Test
    fun `import with a missing optional key succeeds and stores the product default`() {
        val optionalKeys =
            mapOf(
                "dest_name" to { s: AppSettings -> s.copy(destName = "") },
                "sender_name" to { s: AppSettings -> s.copy(senderName = "") },
                "subject_prefix" to { s: AppSettings -> s.copy(subjectPrefix = "") },
                "auto_subject_prefix" to { s: AppSettings -> s.copy(autoSubjectPrefix = false) },
                "country_code" to { s: AppSettings -> s.copy(countryCode = "") },
                "auto_country" to { s: AppSettings -> s.copy(autoCountry = true) },
                "forwarding_enabled" to { s: AppSettings -> s.copy(isForwardingEnabled = true) },
                "contact_resolution" to { s: AppSettings -> s.copy(useContactResolution = true) },
                "display_sim_cards" to { s: AppSettings -> s.copy(displaySimCards = false) },
                "auto_cleanup_retention_days" to { s: AppSettings -> s.copy(autoCleanupRetentionDays = 0) },
                "json_email_format" to { s: AppSettings -> s.copy(useJsonEmailFormat = false) },
                "include_time_in_body" to { s: AppSettings -> s.copy(includeTimeInBody = false) },
                "use_pdu_timestamp" to { s: AppSettings -> s.copy(usePduTimestamp = false) },
                "dedup_level" to { s: AppSettings -> s.copy(dedupLevel = DedupLevel.BALANCED) },
                "show_sticky_notification" to { s: AppSettings -> s.copy(showStickyNotification = true) },
                "log_to_file_enabled" to { s: AppSettings -> s.copy(logToFileEnabled = false) },
                "log_file_size_bytes" to { s: AppSettings -> s.copy(logFileSizeBytes = 256 * 1024) },
                "log_level" to { s: AppSettings -> s.copy(logLevel = LogLevel.ERROR) },
                "filter_config" to { s: AppSettings -> s.copy(filter = SmsFilter()) },
                "filter_enabled" to { s: AppSettings -> s.copy(filterEnabled = false) },
            )
        for ((key, toDefault) in optionalKeys) {
            // Several product defaults equal the fixture value (autoSubjectPrefix,
            // isForwardingEnabled, ...), so their saved settings collide across
            // iterations; clear the mock so each verify sees exactly its own save.
            Mockito.clearInvocations(prefs2)
            val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
            obj.remove(key)
            SettingsExportImport.importFromJson(prefs2, obj.toString())
            verify(prefs2).save(toDefault(exported))
        }
    }

    @Test
    fun `import with a null optional value succeeds and stores the product default`() {
        val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
        obj.put("dedup_level", JSONObject.NULL)
        SettingsExportImport.importFromJson(prefs2, obj.toString())
        verify(prefs2).save(exported.copy(dedupLevel = DedupLevel.BALANCED))
    }

    @Test
    fun `import with only the required keys succeeds and defaults the optional ones`() {
        val obj =
            JSONObject()
                .put("version", 1)
                .put("smtp_host", "smtp.example.com")
                .put("smtp_port", 465)
                .put("smtp_protocol", "IMPLICIT_TLS")
                .put("smtp_username", "user@example.com")
                .put("dest_email", "dest@example.com")
                .put("sender_email", "sender@example.com")
        SettingsExportImport.importFromJson(prefs2, obj.toString())
        verify(prefs2).save(
            exported.copy(
                destName = "",
                senderName = "",
                subjectPrefix = "",
                autoSubjectPrefix = false,
                isForwardingEnabled = true,
                useContactResolution = true,
                displaySimCards = false,
                autoCleanupRetentionDays = 0,
                useJsonEmailFormat = false,
                includeTimeInBody = false,
                usePduTimestamp = false,
                dedupLevel = DedupLevel.BALANCED,
                logToFileEnabled = false,
                logFileSizeBytes = 256 * 1024,
                retrySweepIntervalMinutes = 15,
                exactWakeEnabled = false,
            ),
        )
    }

    @Test
    fun `import with a null value throws exception`() {
        val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
        obj.put("dest_email", JSONObject.NULL)
        Assert.assertThrows(JSONException::class.java) { SettingsExportImport.importFromJson(prefs2, obj.toString()) }
    }

    @Test
    fun `import with an unknown smtp protocol throws exception`() {
        val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
        obj.put("smtp_protocol", "NOT_A_PROTOCOL")
        Assert.assertThrows(JSONException::class.java) { SettingsExportImport.importFromJson(prefs2, obj.toString()) }
    }

    @Test
    fun `import with an unknown dedup level throws exception with the field name`() {
        for (level in listOf("NOT_A_LEVEL", "aggressive", "")) {
            val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
            obj.put("dedup_level", level)
            val ex =
                Assert.assertThrows(JSONException::class.java) {
                    SettingsExportImport.importFromJson(prefs2, obj.toString())
                }
            assertEquals("Invalid dedup_level", ex.message, "level='$level'")
            verifyNoInteractions(prefs2)
        }
    }

    @Test
    fun `import accepts every dedup level name and stores it`() {
        for (level in DedupLevel.entries) {
            val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
            obj.put("dedup_level", level.name)
            SettingsExportImport.importFromJson(prefs2, obj.toString())
            verify(prefs2).save(exported.copy(dedupLevel = level))
        }
    }

    @Test
    fun `import with an unknown log level throws exception with the field name`() {
        for (level in listOf("NOT_A_LEVEL", "error", "")) {
            val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
            obj.put("log_level", level)
            val ex =
                Assert.assertThrows(JSONException::class.java) {
                    SettingsExportImport.importFromJson(prefs2, obj.toString())
                }
            assertEquals("Invalid log_level", ex.message, "level='$level'")
            verifyNoInteractions(prefs2)
        }
    }

    @Test
    fun `import accepts every log level name and stores it`() {
        for (level in LogLevel.entries) {
            val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
            obj.put("log_level", level.name)
            SettingsExportImport.importFromJson(prefs2, obj.toString())
            verify(prefs2).save(exported.copy(logLevel = level))
        }
    }

    @Test
    fun `import with an out-of-range smtp port throws exception with the field name`() {
        for (port in listOf(0, -1, 65536, 70000)) {
            val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
            obj.put("smtp_port", port)
            val ex =
                Assert.assertThrows(JSONException::class.java) {
                    SettingsExportImport.importFromJson(prefs2, obj.toString())
                }
            assertEquals("Invalid smtp_port", ex.message, "port=$port")
            verifyNoInteractions(prefs2)
        }
    }

    @Test
    fun `import with an empty smtp host throws exception with the field name`() {
        for (host in listOf("", "   ")) {
            val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
            obj.put("smtp_host", host)
            val ex =
                Assert.assertThrows(JSONException::class.java) {
                    SettingsExportImport.importFromJson(prefs2, obj.toString())
                }
            assertEquals("Invalid smtp_host", ex.message, "host='$host'")
            verifyNoInteractions(prefs2)
        }
    }

    @Test
    fun `import with a blank smtp username throws exception with the field name`() {
        for (username in listOf("", "   ")) {
            val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
            obj.put("smtp_username", username)
            val ex =
                Assert.assertThrows(JSONException::class.java) {
                    SettingsExportImport.importFromJson(prefs2, obj.toString())
                }
            assertEquals("Invalid smtp_username", ex.message, "username='$username'")
            verifyNoInteractions(prefs2)
        }
    }

    @Test
    fun `import with boundary ports succeeds and stores the port`() {
        for (port in listOf(1, 65535)) {
            val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
            obj.put("smtp_port", port)
            SettingsExportImport.importFromJson(prefs2, obj.toString())
            verify(prefs2).save(exported.copy(smtpPort = port))
        }
    }

    @Test
    fun `import with a non-preset log file size throws with the field name and writes nothing`() {
        for (size in listOf(0, -5, 1, 512 * 1024, Int.MAX_VALUE, 16 * 1024 * 1024 + 1, "0", "512576")) {
            val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
            obj.put("log_file_size_bytes", size)
            val ex =
                Assert.assertThrows(JSONException::class.java) {
                    SettingsExportImport.importFromJson(prefs2, obj.toString())
                }
            assertEquals("Invalid log_file_size_bytes", ex.message, "size=$size")
            verifyNoInteractions(prefs2)
        }
    }

    @Test
    fun `import accepts every preset log file size and stores it`() {
        for (size in LogSizePreset.bytes) {
            val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
            obj.put("log_file_size_bytes", size)
            SettingsExportImport.importFromJson(prefs2, obj.toString())
            verify(prefs2).save(exported.copy(logFileSizeBytes = size))
        }
    }

    @Test
    fun `import with a non-preset cleanup retention days throws with the field name and writes nothing`() {
        for (days in listOf(1, 31, -5, 9999, "1", "31", "-5", "9999")) {
            val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
            obj.put("auto_cleanup_retention_days", days)
            val ex =
                Assert.assertThrows(JSONException::class.java) {
                    SettingsExportImport.importFromJson(prefs2, obj.toString())
                }
            assertEquals("Invalid auto_cleanup_retention_days", ex.message, "days=$days")
            verifyNoInteractions(prefs2)
        }
    }

    @Test
    fun `import accepts every preset cleanup retention days and the off state and stores them`() {
        for (days in listOf(0) + PeriodPreset.days) {
            val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
            obj.put("auto_cleanup_retention_days", days)
            SettingsExportImport.importFromJson(prefs2, obj.toString())
            verify(prefs2).save(exported.copy(autoCleanupRetentionDays = days))
        }
    }

    // The cleanup period alone decides the state, so a shape with unknown extra
    // keys imports unchanged.
    @Test
    fun `import of an export with an unknown auto_cleanup_enabled key succeeds and ignores it`() {
        val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
        obj.put("auto_cleanup_enabled", true)
        obj.put("auto_cleanup_retention_days", 0)
        SettingsExportImport.importFromJson(prefs2, obj.toString())
        verify(prefs2).save(exported.copy(autoCleanupRetentionDays = 0))
    }

    @Test
    fun `import with an absent cleanup retention days succeeds and stores the product default`() {
        val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
        obj.remove("auto_cleanup_retention_days")
        SettingsExportImport.importFromJson(prefs2, obj.toString())
        verify(prefs2).save(exported.copy(autoCleanupRetentionDays = 0))
    }

    @Test
    fun `import with a non-preset sweep interval throws with the field name and writes nothing`() {
        for (minutes in listOf(1, 7, 14, 16, 90, 1441, -5, "7", "1441")) {
            val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
            obj.put("retry_sweep_interval_minutes", minutes)
            val ex =
                Assert.assertThrows(JSONException::class.java) {
                    SettingsExportImport.importFromJson(prefs2, obj.toString())
                }
            assertEquals("Invalid retry_sweep_interval_minutes", ex.message, "minutes=$minutes")
            verifyNoInteractions(prefs2)
        }
    }

    @Test
    fun `import accepts every preset sweep interval including the off state and stores them`() {
        for (minutes in SweepIntervalPreset.minutes) {
            val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
            obj.put("retry_sweep_interval_minutes", minutes)
            SettingsExportImport.importFromJson(prefs2, obj.toString())
            verify(prefs2).save(exported.copy(retrySweepIntervalMinutes = minutes))
        }
    }

    @Test
    fun `import with an absent sweep interval succeeds and stores the product default`() {
        val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
        obj.remove("retry_sweep_interval_minutes")
        SettingsExportImport.importFromJson(prefs2, obj.toString())
        verify(prefs2).save(exported.copy(retrySweepIntervalMinutes = 15))
    }

    @Test
    fun `import with a non-address sender email throws exception with the field name`() {
        val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
        obj.put("sender_email", "not-an-email")
        val ex =
            Assert.assertThrows(JSONException::class.java) {
                SettingsExportImport.importFromJson(prefs2, obj.toString())
            }
        assertEquals("Invalid email: sender_email", ex.message)
    }

    @Test
    fun `import with a CRLF-injected dest email throws exception with the field name`() {
        val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
        obj.put("dest_email", "evil@example.com\nBcc: victim@example.com")
        val ex =
            Assert.assertThrows(JSONException::class.java) {
                SettingsExportImport.importFromJson(prefs2, obj.toString())
            }
        assertEquals("Invalid email: dest_email", ex.message)
    }

    @Test
    fun `import with an empty email throws exception`() {
        val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
        obj.put("sender_email", "")
        obj.put("dest_email", "")
        val ex =
            Assert.assertThrows(JSONException::class.java) {
                SettingsExportImport.importFromJson(prefs2, obj.toString())
            }
        // Both addresses are invalid; the one earlier in the field table reports first.
        assertEquals("Invalid email: dest_email", ex.message)
    }

    @Test
    fun `import strips control characters from the free-text fields`() {
        val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
        obj.put("subject_prefix", "Prefix\r\nBcc: evil@example.com")
        obj.put("sender_name", "Sender\u0000Name")
        obj.put("dest_name", "Dest\nName")
        obj.put("smtp_username", "user@example.com\r")
        SettingsExportImport.importFromJson(prefs2, obj.toString())
        verify(prefs2).save(
            exported.copy(
                subjectPrefix = "PrefixBcc: evil@example.com",
                senderName = "SenderName",
                destName = "DestName",
                smtpUsername = "user@example.com",
            ),
        )
    }

    @Test
    fun `import stores valid email addresses verbatim`() {
        val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
        obj.put("sender_email", "first.last+tag@example.com")
        obj.put("dest_email", "user-2@example.com")
        SettingsExportImport.importFromJson(prefs2, obj.toString())
        verify(prefs2).save(
            exported.copy(
                senderEmail = "first.last+tag@example.com",
                destEmail = "user-2@example.com",
            ),
        )
    }

    @Test
    fun `import with a wrong-typed int field throws with the field name and writes nothing`() {
        for (key in listOf("smtp_port", "auto_cleanup_retention_days", "log_file_size_bytes")) {
            for (value in listOf<Any>("abc", "30 days", "1.5", true, false, JSONArray(), JSONObject())) {
                val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
                obj.put(key, value)
                val ex =
                    Assert.assertThrows(JSONException::class.java) {
                        SettingsExportImport.importFromJson(prefs2, obj.toString())
                    }
                assertEquals("Invalid type for $key", ex.message, "key=$key value=$value")
                verifyNoInteractions(prefs2)
            }
        }
    }

    @Test
    fun `import with a wrong-typed flag field throws with the field name and writes nothing`() {
        for (
        key in
        listOf(
            "auto_subject_prefix",
            "auto_country",
            "forwarding_enabled",
            "contact_resolution",
            "display_sim_cards",
            "json_email_format",
            "include_time_in_body",
            "use_pdu_timestamp",
            "show_sticky_notification",
            "log_to_file_enabled",
        )
        ) {
            for (value in listOf<Any>(0, 1, 1.5, "yes", "no", "1", "0", JSONArray(), JSONObject())) {
                val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
                obj.put(key, value)
                val ex =
                    Assert.assertThrows(JSONException::class.java) {
                        SettingsExportImport.importFromJson(prefs2, obj.toString())
                    }
                assertEquals("Invalid type for $key", ex.message, "key=$key value=$value")
                verifyNoInteractions(prefs2)
            }
        }
    }

    @Test
    fun `import with a wrong-typed text field throws with the field name and writes nothing`() {
        for (
        key in
        listOf(
            "smtp_host",
            "smtp_protocol",
            "smtp_username",
            "dest_email",
            "dest_name",
            "sender_email",
            "sender_name",
            "subject_prefix",
            "country_code",
            "dedup_level",
        )
        ) {
            for (value in listOf<Any>(587, true, 1.5, JSONArray(), JSONObject())) {
                val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
                obj.put(key, value)
                val ex =
                    Assert.assertThrows(JSONException::class.java) {
                        SettingsExportImport.importFromJson(prefs2, obj.toString())
                    }
                assertEquals("Invalid type for $key", ex.message, "key=$key value=$value")
                verifyNoInteractions(prefs2)
            }
        }
    }

    @Test
    fun `import accepts a quoted numeric string for the int fields`() {
        val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
        obj.put("smtp_port", "465")
        obj.put("auto_cleanup_retention_days", "30")
        SettingsExportImport.importFromJson(prefs2, obj.toString())
        verify(prefs2).save(exported.copy(smtpPort = 465, autoCleanupRetentionDays = 30))
    }

    @Test
    fun `export round-trips a non-default filter through the import`() {
        val filter =
            SmsFilter(
                FilterMode.FORWARD,
                listOf(FilterRule(body = "vip", sender = "123", slots = listOf(1, 3))),
            )
        whenever(prefs.load()).thenReturn(exported.copy(filter = filter))
        val json = SettingsExportImport.exportToJson(prefs)
        assertEquals(filter.toJson(), JSONObject(json).getString("filter_config"))
        SettingsExportImport.importFromJson(prefs2, json)
        verify(prefs2).save(exported.copy(filter = filter))
    }

    @Test
    fun `export round-trips a forward mode without an active rule through the import`() {
        // The legal off state (the predicate forwards everything for it) imports
        // as itself, the mode included.
        val filter = SmsFilter(FilterMode.FORWARD, emptyList())
        whenever(prefs.load()).thenReturn(exported.copy(filter = filter))
        val json = SettingsExportImport.exportToJson(prefs)
        assertEquals(filter.toJson(), JSONObject(json).getString("filter_config"))
        SettingsExportImport.importFromJson(prefs2, json)
        verify(prefs2).save(exported.copy(filter = filter))
    }

    @Test
    fun `export round-trips an enabled filter toggle through the import`() {
        whenever(prefs.load()).thenReturn(exported.copy(filterEnabled = true))
        val json = SettingsExportImport.exportToJson(prefs)
        assertEquals(true, JSONObject(json).getBoolean("filter_enabled"))
        SettingsExportImport.importFromJson(prefs2, json)
        verify(prefs2).save(exported.copy(filterEnabled = true))
    }

    @Test
    fun `import with an invalid filter_config rejects the file and writes nothing`() {
        val overCap = "a".repeat(AppConfig.FILTER_FACET_MAX_BYTES + 1)
        val badDocuments =
            listOf(
                "not json",
                """{"mode":"MAYBE","rules":[]}""",
                """{"mode":"SKIP","rules":"oops"}""",
                """{"mode":"SKIP","rules":[{"body":"(","sender":"","slots":[],"combine":"ANY"}]}""",
                """{"mode":"SKIP","rules":[{"body":"$overCap","sender":"","slots":[],"combine":"ANY"}]}""",
                """{"mode":"SKIP","rules":[{"body":"","sender":"","slots":[0],"combine":"ANY"}]}""",
                """{"mode":"SKIP","rules":[{"body":"","sender":"","slots":[4],"combine":"ANY"}]}""",
                (0 until AppConfig.FILTER_MAX_RULES + 1).let { range ->
                    """{"mode":"SKIP","rules":[${range.joinToString(
                        ",",
                    ) { i -> """{"body":"b$i","sender":"","slots":[],"combine":"ANY"}""" }}]}"""
                },
            )
        for (doc in badDocuments) {
            Mockito.clearInvocations(prefs2)
            val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
            obj.put("filter_config", doc)
            Assert.assertThrows(JSONException::class.java) {
                SettingsExportImport.importFromJson(prefs2, obj.toString())
            }
            verifyNoInteractions(prefs2)
        }
    }

    @Test
    fun `import with a wrong-typed filter_config throws with the field name and writes nothing`() {
        for (value in listOf<Any>(587, true, 1.5, JSONArray(), JSONObject())) {
            val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
            obj.put("filter_config", value)
            val ex =
                Assert.assertThrows(JSONException::class.java) {
                    SettingsExportImport.importFromJson(prefs2, obj.toString())
                }
            assertEquals("Invalid type for filter_config", ex.message)
            verifyNoInteractions(prefs2)
        }
    }

    @Test
    fun `import accepts a quoted true or false, case-insensitively, for the flag fields`() {
        val obj = JSONObject(SettingsExportImport.exportToJson(prefs))
        obj.put("forwarding_enabled", "TRUE")
        obj.put("json_email_format", "False")
        obj.put("auto_subject_prefix", "true")
        obj.put("contact_resolution", "false")
        obj.put("display_sim_cards", "True")
        SettingsExportImport.importFromJson(prefs2, obj.toString())
        verify(prefs2).save(
            exported.copy(
                isForwardingEnabled = true,
                useJsonEmailFormat = false,
                autoSubjectPrefix = true,
                useContactResolution = false,
                displaySimCards = true,
            ),
        )
    }

    @Test
    fun `queryImportFileSize returns null when the cursor is empty`() {
        val mockContext = Mockito.mock(android.content.Context::class.java)
        val mockResolver = Mockito.mock(ContentResolver::class.java)
        val mockCursor = Mockito.mock(Cursor::class.java)
        Mockito.`when`(mockCursor.moveToFirst()).thenReturn(false)
        Mockito
            .`when`(
                mockResolver.query(
                    any<Uri>(),
                    any<Array<String>>(),
                    anyOrNull<String>(),
                    anyOrNull<Array<String>>(),
                    anyOrNull<String>(),
                ),
            ).thenReturn(mockCursor)
        Mockito.`when`(mockContext.contentResolver).thenReturn(mockResolver)

        val size = SettingsExportImport.queryImportFileSize(mockContext, Uri.parse("content://test"))

        Assert.assertNull(size)
        Mockito.verify(mockCursor).close()
    }

    @Test
    fun `queryImportFileSize returns null when the size column index is negative`() {
        val mockContext = Mockito.mock(android.content.Context::class.java)
        val mockResolver = Mockito.mock(ContentResolver::class.java)
        val mockCursor = Mockito.mock(Cursor::class.java)
        Mockito.`when`(mockCursor.moveToFirst()).thenReturn(true)
        Mockito.`when`(mockCursor.getColumnIndex(OpenableColumns.SIZE)).thenReturn(-1)
        Mockito
            .`when`(
                mockResolver.query(
                    any<Uri>(),
                    any<Array<String>>(),
                    anyOrNull<String>(),
                    anyOrNull<Array<String>>(),
                    anyOrNull<String>(),
                ),
            ).thenReturn(mockCursor)
        Mockito.`when`(mockContext.contentResolver).thenReturn(mockResolver)

        val size = SettingsExportImport.queryImportFileSize(mockContext, Uri.parse("content://test"))

        Assert.assertNull(size)
        Mockito.verify(mockCursor).close()
    }

    // queryImportFileSize: when querySafely returns null (the provider is unavailable
    // or the URI is invalid), the function returns null without crashing.
    @Test
    fun `queryImportFileSize returns null when querySafely returns null cursor`() {
        val mockContext = Mockito.mock(android.content.Context::class.java)
        val mockResolver = Mockito.mock(ContentResolver::class.java)
        Mockito
            .`when`(
                mockResolver.query(
                    any<Uri>(),
                    any<Array<String>>(),
                    anyOrNull<String>(),
                    anyOrNull<Array<String>>(),
                    anyOrNull<String>(),
                ),
            ).thenReturn(null)
        Mockito.`when`(mockContext.contentResolver).thenReturn(mockResolver)

        val size = SettingsExportImport.queryImportFileSize(mockContext, Uri.parse("content://test"))

        Assert.assertNull(size)
    }

    // queryImportFileSize: when the SIZE column exists but its value is NULL, the
    // function returns null (treated as "unknown size" by the caller).
    @Test
    fun `queryImportFileSize returns null when size column value is null`() {
        val mockContext = Mockito.mock(android.content.Context::class.java)
        val mockResolver = Mockito.mock(ContentResolver::class.java)
        val mockCursor = Mockito.mock(Cursor::class.java)
        Mockito.`when`(mockCursor.moveToFirst()).thenReturn(true)
        Mockito.`when`(mockCursor.getColumnIndex(OpenableColumns.SIZE)).thenReturn(0)
        Mockito.`when`(mockCursor.isNull(0)).thenReturn(true)
        Mockito
            .`when`(
                mockResolver.query(
                    any<Uri>(),
                    any<Array<String>>(),
                    anyOrNull<String>(),
                    anyOrNull<Array<String>>(),
                    anyOrNull<String>(),
                ),
            ).thenReturn(mockCursor)
        Mockito.`when`(mockContext.contentResolver).thenReturn(mockResolver)

        val size = SettingsExportImport.queryImportFileSize(mockContext, Uri.parse("content://test"))

        Assert.assertNull(size)
        Mockito.verify(mockCursor).close()
    }

    // isImportFileTooLarge: null size means "unknown" — the function must return false
    // so the capped read proceeds (a second guard against OOM).
    @Test
    fun `isImportFileTooLarge returns false for null size`() {
        assertFalse(SettingsExportImport.isImportFileTooLarge(null))
    }
}
