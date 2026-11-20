package app.kotowski.sms2email.ui.onboarding

import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.data.prefs.SmtpProtocol
import app.kotowski.sms2email.service.EmailResult
import app.kotowski.sms2email.service.EmailService
import app.kotowski.sms2email.service.SmtpConfig
import app.kotowski.sms2email.service.SmtpTestRunner
import app.kotowski.sms2email.service.SmtpTester
import app.kotowski.sms2email.service.TestEmailFields
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.system.PermissionManager
import app.kotowski.sms2email.system.SimDetector
import app.kotowski.sms2email.testing.TestEmail
import app.kotowski.sms2email.testing.withMockedToast
import app.kotowski.sms2email.util.DeviceUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OnboardingImportConfigTest {
    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var emailService: EmailService

    @Mock private lateinit var simDetector: SimDetector

    @Mock private lateinit var permissionManager: PermissionManager

    @Mock private lateinit var appContext: android.content.Context

    private lateinit var viewModel: OnboardingViewModel

    // Unconfined: the view state is derived from the form's flow, so it must settle
    // synchronously after every handler call for the assertions below to see it.
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        MockitoAnnotations.openMocks(this)
        `when`(appContext.applicationContext).thenReturn(appContext)
        // DeviceUtil.getDeviceName falls back to the app name when the device name is unset
        `when`(appContext.getString(R.string.app_name)).thenReturn("SMS to Email")
        `when`(appContext.getString(R.string.subject_template)).thenReturn("[sms2email] SMS From %s")
        `when`(appContext.getString(R.string.subject_template_with_sim)).thenReturn("[sms2email] SMS From %s %s")
        `when`(appContext.getString(R.string.subject_template_no_prefix)).thenReturn("SMS from %s")
        `when`(appContext.getString(R.string.subject_template_no_prefix_with_sim)).thenReturn("SMS from %s %s")
        `when`(appContext.getString(R.string.history_unknown_sim)).thenReturn("Unknown SIM")
        `when`(appContext.getString(R.string.email_config_test_success)).thenReturn("Test successful")
        `when`(prefs.load()).thenReturn(AppSettings(useContactResolution = false))
        `when`(prefs.smtpPassword).thenReturn("")
        `when`(prefs.isOnboardingComplete).thenReturn(false)
        `when`(simDetector.isSingleSimDevice()).thenReturn(true)
        viewModel =
            OnboardingViewModel(
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                simDetector,
                permissionManager,
                appContext,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `applyJson with valid JSON reports success`() {
        @Suppress("ktlint:standard:max-line-length")
        val json = """{"version":1,"smtp_host":"smtp.example.com","smtp_port":465,"smtp_protocol":"IMPLICIT_TLS","smtp_username":"${TestEmail.SELF}","dest_email":"dest@example.com","dest_name":"","sender_email":"sender@example.com","sender_name":"","subject_prefix":"","auto_subject_prefix":false,"forwarding_enabled":true,"contact_resolution":true,"display_sim_cards":false,"auto_cleanup_retention_days":365,"json_email_format":false,"use_pdu_timestamp":true,"dedup_level":"BALANCED"}"""
        assertTrue(viewModel.importRunner.applyJson(json), "Should report success for a valid JSON")
    }

    @Test
    fun `applyJson with invalid JSON reports failure`() {
        assertFalse(viewModel.importRunner.applyJson("not json"), "Should report failure on invalid JSON")
    }

    // A complete settings file (the import rejects partial ones) with the given
    // subject prefix fields.
    @Suppress("ktlint:standard:max-line-length")
    private fun fullImportJson(
        subjectPrefix: String,
        autoSubjectPrefix: Boolean,
    ): String =
        """{"version":1,"smtp_host":"smtp.example.com","smtp_port":465,"smtp_protocol":"IMPLICIT_TLS","smtp_username":"${TestEmail.SELF}","dest_email":"dest@example.com","dest_name":"","sender_email":"sender@example.com","sender_name":"","subject_prefix":"$subjectPrefix","auto_subject_prefix":$autoSubjectPrefix,"forwarding_enabled":true,"contact_resolution":true,"display_sim_cards":false,"auto_cleanup_retention_days":365,"json_email_format":false,"use_pdu_timestamp":true,"dedup_level":"BALANCED"}"""

    @Test
    fun `import with auto prefix and foreign device name disables auto`() {
        val realContext = RuntimeEnvironment.getApplication()
        val deviceName = DeviceUtil.getDeviceName(realContext)
        val foreignPrefix = if (deviceName == "sms2email") "OtherPhone" else "sms2email"
        `when`(prefs.load()).thenReturn(AppSettings(autoSubjectPrefix = true))
        val vm =
            OnboardingViewModel(
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(realContext, prefs),
                simDetector,
                permissionManager,
                realContext,
            )
        vm.importRunner.applyJson(fullImportJson(foreignPrefix, true))
        // The import stores the file's value; the device-name check re-writes the flag off.
        verify(prefs).save(
            AppSettings(
                smtpHost = "smtp.example.com",
                smtpPort = 465,
                smtpProtocol = SmtpProtocol.IMPLICIT_TLS,
                smtpUsername = TestEmail.SELF,
                destEmail = "dest@example.com",
                senderEmail = "sender@example.com",
                subjectPrefix = foreignPrefix,
                autoSubjectPrefix = true,
                autoCleanupRetentionDays = 365,
                usePduTimestamp = true,
            ),
        )
        verify(prefs).save(AppSettings())
    }

    @Test
    fun `import with auto prefix matching device name keeps auto enabled`() {
        val realContext = RuntimeEnvironment.getApplication()
        val deviceName = DeviceUtil.getDeviceName(realContext)
        `when`(prefs.load()).thenReturn(AppSettings(autoSubjectPrefix = true, subjectPrefix = deviceName))
        val vm =
            OnboardingViewModel(
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(realContext, prefs),
                simDetector,
                permissionManager,
                realContext,
            )
        vm.importRunner.applyJson(fullImportJson(deviceName, true))
        // The file's value is stored once, the flag untouched: the prefix matches the device name.
        val captor = argumentCaptor<AppSettings>()
        verify(prefs, times(1)).save(captor.capture())
        assertTrue(captor.firstValue.autoSubjectPrefix)
    }

    @Test
    fun `successful import resets previous test success flags and results`() =
        runBlocking {
            `when`(emailService.testConnection(any<SmtpConfig>())).thenReturn(EmailResult.Success)
            `when`(emailService.sendTestEmail(any<SmtpConfig>(), any<TestEmailFields>())).thenReturn(EmailResult.Success)
            withMockedToast {
                viewModel.testConnection()
                testDispatcher.scheduler.advanceUntilIdle()
                assertTrue(viewModel.state.value.smtpTestSucceeded)
                viewModel.sendTestEmail()
                testDispatcher.scheduler.advanceUntilIdle()
                assertTrue(viewModel.state.value.testEmailSucceeded)
            }

            viewModel.importRunner.applyJson(fullImportJson("", false))

            val state = viewModel.state.value
            assertFalse(state.smtpTestSucceeded)
            assertNull(state.config.test.smtpTestResult)
            assertFalse(state.testEmailSucceeded)
            assertNull(state.config.test.testEmailResult)
        }
}
