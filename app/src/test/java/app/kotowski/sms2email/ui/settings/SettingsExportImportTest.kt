package app.kotowski.sms2email.ui.settings

import android.util.Xml
import androidx.test.core.app.ApplicationProvider
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.LogLevel
import app.kotowski.sms2email.R
import app.kotowski.sms2email.ThemeMode
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.data.prefs.ThemePreference
import app.kotowski.sms2email.service.EmailService
import app.kotowski.sms2email.service.SmtpTestRunner
import app.kotowski.sms2email.service.SmtpTester
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.system.PermissionManager
import app.kotowski.sms2email.system.PermissionSnapshot
import app.kotowski.sms2email.testing.TestEmail
import app.kotowski.sms2email.testing.stubContextGetString
import app.kotowski.sms2email.work.BackgroundStateReconciler
import app.kotowski.sms2email.work.ExactWakeArmer
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SettingsExportImportTest {
    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // The VM resolves the subject-preview strings through the app context at
        // construction; the mock delegates getString to the real application.
        // Registered before setupMocks so its per-ID literal stubs still win.
        stubContextGetString(appContext, ApplicationProvider.getApplicationContext())
    }

    @Mock private lateinit var appContext: android.content.Context

    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var emailService: EmailService

    @Mock private lateinit var permissionManager: PermissionManager

    @Mock private lateinit var themePreference: ThemePreference

    @Mock private lateinit var backgroundStateReconciler: BackgroundStateReconciler

    @Mock private lateinit var exactWakeArmer: ExactWakeArmer

    private lateinit var viewModel: SettingsViewModel

    private fun setupMocks() {
        `when`(appContext.applicationContext).thenReturn(appContext)
        `when`(appContext.getString(R.string.config_export_success)).thenReturn("Exported")
        `when`(appContext.getString(R.string.config_export_failed)).thenReturn("Export failed")
        `when`(appContext.getString(R.string.settings_cleanup_will_run)).thenReturn("Cleanup will run")
        whenever(prefs.load()).thenReturn(
            AppSettings(
                autoCleanupRetentionDays = 30,
                usePduTimestamp = true,
            ),
        )
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.isOnboardingComplete).thenReturn(true)
        whenever(prefs.scamWarningDismissed).thenReturn(false)
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        // The VM reads the live permission at construction (the extras seed).
        whenever(permissionManager.snapshot())
            .thenReturn(PermissionSnapshot(sms = false, notifications = false, contacts = false, phone = false, battery = false))
    }

    private fun createViewModel() {
        setupMocks()
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
    }

    @Test
    fun `exportSettings returns JSON with version`() {
        createViewModel()
        val json = viewModel.exportSettings()
        kotlin.test.assertTrue(json.contains("\"version\""), "Should produce valid JSON with version")
    }

    @Test
    fun `exportSettings reflects the current prefs at call time`() {
        createViewModel()
        val first = viewModel.exportSettings()
        // A process death while the picker is open recreates the composition, so the
        // export must reflect the current prefs: the JSON is derived per call, not from a remembered snapshot.
        whenever(prefs.load()).thenReturn(
            AppSettings(
                autoCleanupRetentionDays = 30,
                usePduTimestamp = true,
                subjectPrefix = "fresh:",
            ),
        )
        val second = viewModel.exportSettings()
        kotlin.test.assertNotEquals(first, second, "The export must be regenerated from the current prefs")
        kotlin.test.assertTrue(second.contains("fresh:"), "The fresh prefs must be in the regenerated JSON")
    }

    @Test
    fun `applyJson with valid JSON reports success`() {
        createViewModel()
        // The file deliberately omits the optional show_sticky_notification key: the
        // import degrades an absent optional key to the product default.
        @Suppress("ktlint:standard:max-line-length")
        val json = """{"version":1,"smtp_host":"smtp.example.com","smtp_port":587,"smtp_protocol":"START_TLS","smtp_username":"${TestEmail.SELF}","dest_email":"${TestEmail.RECIPIENT}","dest_name":"","sender_email":"sender@example.com","sender_name":"","subject_prefix":"","auto_subject_prefix":false,"forwarding_enabled":true,"contact_resolution":true,"display_sim_cards":false,"auto_cleanup_retention_days":365,"json_email_format":false,"use_pdu_timestamp":true,"dedup_level":"BALANCED"}"""
        kotlin.test.assertTrue(viewModel.importRunner.applyJson(json), "Should report success for a valid JSON")
    }

    @Test
    fun `applyJson caps an over-long imported subject prefix`() {
        createViewModel()
        val longPrefix = "p".repeat(AppConfig.SUBJECT_PREFIX_MAX_LENGTH + 10)

        @Suppress("ktlint:standard:max-line-length")
        val json =
            """{"version":1,"smtp_host":"smtp.example.com","smtp_port":587,"smtp_protocol":"START_TLS","smtp_username":"${TestEmail.SELF}","dest_email":"${TestEmail.RECIPIENT}","sender_email":"sender@example.com","subject_prefix":"$longPrefix"}"""
        kotlin.test.assertTrue(viewModel.importRunner.applyJson(json), "Should report success for a long prefix")
        val captor = argumentCaptor<AppSettings>()
        verify(prefs).save(captor.capture())
        kotlin.test.assertEquals(
            AppConfig.SUBJECT_PREFIX_MAX_LENGTH,
            captor.firstValue.subjectPrefix.length,
            "The imported prefix must be stored capped",
        )
    }

    @Test
    fun `applyJson with only the required keys reports success`() {
        createViewModel()
        @Suppress("ktlint:standard:max-line-length")
        val json = """{"version":1,"smtp_host":"smtp.example.com","smtp_port":587,"smtp_protocol":"START_TLS","smtp_username":"${TestEmail.SELF}","dest_email":"${TestEmail.RECIPIENT}","sender_email":"sender@example.com"}"""
        kotlin.test.assertTrue(
            viewModel.importRunner.applyJson(json),
            "Should report success for a JSON with only the required keys",
        )
    }

    @Test
    fun `applyJson with invalid JSON reports failure`() {
        createViewModel()
        kotlin.test.assertFalse(viewModel.importRunner.applyJson("not json"), "Should report failure on invalid JSON")
    }

    @Test
    fun `exportSettings includes the log to file settings`() {
        createViewModel()
        whenever(prefs.load()).thenReturn(
            AppSettings(
                autoCleanupRetentionDays = 30,
                logToFileEnabled = true,
                logFileSizeBytes = 1024 * 1024,
            ),
        )
        val json = viewModel.exportSettings()
        kotlin.test.assertTrue(json.contains("\"log_to_file_enabled\": true"), "missing log_to_file_enabled: $json")
        kotlin.test.assertTrue(
            json.contains("\"log_file_size_bytes\": 1048576"),
            "missing log_file_size_bytes: $json",
        )
    }

    @Test
    fun `applyJson without the log to file keys degrades them to the product defaults`() {
        createViewModel()
        @Suppress("ktlint:standard:max-line-length")
        val json = """{"version":1,"smtp_host":"smtp.example.com","smtp_port":587,"smtp_protocol":"START_TLS","smtp_username":"${TestEmail.SELF}","dest_email":"${TestEmail.RECIPIENT}","dest_name":"","sender_email":"sender@example.com","sender_name":"","subject_prefix":"","auto_subject_prefix":false,"forwarding_enabled":true,"contact_resolution":true,"display_sim_cards":false,"auto_cleanup_retention_days":365,"json_email_format":false,"use_pdu_timestamp":true,"dedup_level":"BALANCED"}"""
        kotlin.test.assertTrue(viewModel.importRunner.applyJson(json), "Should report success for the minimal JSON")
        val captor = argumentCaptor<AppSettings>()
        verify(prefs).save(captor.capture())
        kotlin.test.assertFalse(captor.firstValue.logToFileEnabled)
        kotlin.test.assertEquals(256 * 1024, captor.firstValue.logFileSizeBytes)
    }

    @Test
    fun `applyJson with the log to file keys applies them`() {
        createViewModel()
        @Suppress("ktlint:standard:max-line-length")
        val json = """{"version":1,"smtp_host":"smtp.example.com","smtp_port":587,"smtp_protocol":"START_TLS","smtp_username":"${TestEmail.SELF}","dest_email":"${TestEmail.RECIPIENT}","dest_name":"","sender_email":"sender@example.com","sender_name":"","subject_prefix":"","auto_subject_prefix":false,"forwarding_enabled":true,"contact_resolution":true,"display_sim_cards":false,"auto_cleanup_retention_days":365,"json_email_format":false,"use_pdu_timestamp":true,"dedup_level":"BALANCED","log_to_file_enabled":true,"log_file_size_bytes":1048576}"""
        kotlin.test.assertTrue(viewModel.importRunner.applyJson(json), "Should report success for the full JSON")
        val captor = argumentCaptor<AppSettings>()
        verify(prefs).save(captor.capture())
        kotlin.test.assertTrue(captor.firstValue.logToFileEnabled)
        kotlin.test.assertEquals(1024 * 1024, captor.firstValue.logFileSizeBytes)
    }

    @Test
    fun `exportSettings includes the log level`() {
        createViewModel()
        whenever(prefs.load()).thenReturn(
            AppSettings(
                autoCleanupRetentionDays = 30,
                logLevel = LogLevel.WARNING,
            ),
        )
        val json = viewModel.exportSettings()
        kotlin.test.assertTrue(json.contains("\"log_level\": \"WARNING\""), "missing log_level: $json")
    }

    @Test
    fun `applyJson without the log level key degrades it to the product default`() {
        createViewModel()
        @Suppress("ktlint:standard:max-line-length")
        val json = """{"version":1,"smtp_host":"smtp.example.com","smtp_port":587,"smtp_protocol":"START_TLS","smtp_username":"${TestEmail.SELF}","dest_email":"${TestEmail.RECIPIENT}","dest_name":"","sender_email":"sender@example.com","sender_name":"","subject_prefix":"","auto_subject_prefix":false,"forwarding_enabled":true,"contact_resolution":true,"display_sim_cards":false,"auto_cleanup_retention_days":365,"json_email_format":false,"use_pdu_timestamp":true,"dedup_level":"BALANCED"}"""
        kotlin.test.assertTrue(viewModel.importRunner.applyJson(json), "Should report success for the minimal JSON")
        val captor = argumentCaptor<AppSettings>()
        verify(prefs).save(captor.capture())
        kotlin.test.assertEquals(LogLevel.ERROR, captor.firstValue.logLevel)
    }

    @Test
    fun `applyJson with the log level key applies it`() {
        createViewModel()
        @Suppress("ktlint:standard:max-line-length")
        val json = """{"version":1,"smtp_host":"smtp.example.com","smtp_port":587,"smtp_protocol":"START_TLS","smtp_username":"${TestEmail.SELF}","dest_email":"${TestEmail.RECIPIENT}","dest_name":"","sender_email":"sender@example.com","sender_name":"","subject_prefix":"","auto_subject_prefix":false,"forwarding_enabled":true,"contact_resolution":true,"display_sim_cards":false,"auto_cleanup_retention_days":365,"json_email_format":false,"use_pdu_timestamp":true,"dedup_level":"BALANCED","log_level":"ERROR"}"""
        kotlin.test.assertTrue(viewModel.importRunner.applyJson(json), "Should report success for the full JSON")
        val captor = argumentCaptor<AppSettings>()
        verify(prefs).save(captor.capture())
        kotlin.test.assertEquals(LogLevel.ERROR, captor.firstValue.logLevel)
    }

    @Test
    fun `applyJson with an unknown log level reports failure`() {
        createViewModel()
        @Suppress("ktlint:standard:max-line-length")
        val json = """{"version":1,"smtp_host":"smtp.example.com","smtp_port":587,"smtp_protocol":"START_TLS","smtp_username":"${TestEmail.SELF}","dest_email":"${TestEmail.RECIPIENT}","dest_name":"","sender_email":"sender@example.com","sender_name":"","subject_prefix":"","auto_subject_prefix":false,"forwarding_enabled":true,"contact_resolution":true,"display_sim_cards":false,"auto_cleanup_retention_days":365,"json_email_format":false,"use_pdu_timestamp":true,"dedup_level":"BALANCED","log_level":"VERBOSE"}"""
        kotlin.test.assertFalse(viewModel.importRunner.applyJson(json), "Should reject an unknown log level")
    }

    @Test
    fun `exportSettings includes the country settings`() {
        createViewModel()
        whenever(prefs.load()).thenReturn(
            AppSettings(
                autoCleanupRetentionDays = 30,
                usePduTimestamp = true,
                autoCountry = false,
                countryCode = "FR",
            ),
        )
        val json = viewModel.exportSettings()
        kotlin.test.assertTrue(json.contains("\"country_code\": \"FR\""), "missing country_code: $json")
        kotlin.test.assertTrue(json.contains("\"auto_country\": false"), "missing auto_country: $json")
    }

    @Test
    fun `applyJson with the country keys applies them`() {
        createViewModel()
        @Suppress("ktlint:standard:max-line-length")
        val json = """{"version":1,"smtp_host":"smtp.example.com","smtp_port":587,"smtp_protocol":"START_TLS","smtp_username":"${TestEmail.SELF}","dest_email":"${TestEmail.RECIPIENT}","dest_name":"","sender_email":"sender@example.com","sender_name":"","subject_prefix":"","auto_subject_prefix":false,"forwarding_enabled":true,"contact_resolution":true,"display_sim_cards":false,"auto_cleanup_retention_days":365,"json_email_format":false,"use_pdu_timestamp":true,"dedup_level":"BALANCED","country_code":"FR","auto_country":false}"""
        kotlin.test.assertTrue(viewModel.importRunner.applyJson(json), "Should report success for the country JSON")
        val captor = argumentCaptor<AppSettings>()
        verify(prefs).save(captor.capture())
        kotlin.test.assertEquals("FR", captor.firstValue.countryCode)
        kotlin.test.assertFalse(captor.firstValue.autoCountry)
    }

    @Test
    fun `applyJson without the country keys degrades them to the product defaults`() {
        createViewModel()
        @Suppress("ktlint:standard:max-line-length")
        val json = """{"version":1,"smtp_host":"smtp.example.com","smtp_port":587,"smtp_protocol":"START_TLS","smtp_username":"${TestEmail.SELF}","dest_email":"${TestEmail.RECIPIENT}","dest_name":"","sender_email":"sender@example.com","sender_name":"","subject_prefix":"","auto_subject_prefix":false,"forwarding_enabled":true,"contact_resolution":true,"display_sim_cards":false,"auto_cleanup_retention_days":365,"json_email_format":false,"use_pdu_timestamp":true,"dedup_level":"BALANCED"}"""
        kotlin.test.assertTrue(viewModel.importRunner.applyJson(json), "Should report success for the minimal JSON")
        val captor = argumentCaptor<AppSettings>()
        verify(prefs).save(captor.capture())
        kotlin.test.assertTrue(captor.firstValue.autoCountry)
        kotlin.test.assertEquals("", captor.firstValue.countryCode)
    }

    // The export never contains the SMTP password, so the import success toast must
    // warn that a cross-account import keeps the stored password. Unit tests run
    // without the app resource table, so the value is read from the source XML.
    @Test
    fun `import success toast warns that the password is not included in exports`() {
        val resDir =
            listOf(File("src/main/res"), File("app/src/main/res"))
                .firstOrNull { it.isDirectory }
                ?: error("strings dir not found")
        val enToast = readString(resDir.resolve("values/strings.xml"), "config_import_success")
        val ruToast = readString(resDir.resolve("values-ru/strings.xml"), "config_import_success")
        kotlin.test.assertTrue(
            enToast.contains("password", true),
            "import success toast must warn about the missing SMTP password",
        )
        kotlin.test.assertTrue(
            ruToast.contains("пароль", true),
            "ru import success toast must warn about the missing SMTP password",
        )
    }

    private fun readString(
        file: File,
        name: String,
    ): String {
        val parser = Xml.newPullParser()
        parser.setInput(file.inputStream(), "UTF-8")
        var value: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG &&
                parser.name == "string" &&
                parser.getAttributeValue(null, "name") == name
            ) {
                value = parser.nextText()
                break
            }
            event = parser.next()
        }
        return value ?: error("string $name not found in ${file.path}")
    }
}
