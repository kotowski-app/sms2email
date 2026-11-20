package app.kotowski.sms2email.ui.onboarding

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Robolectric: the completeness gate validates the email fields with the platform
// email pattern (EmailUtil), which is unavailable on the plain JVM test classpath.
// sdk 28: below 29 AppCompatDelegate keeps the requested locales in its own static
// state, which the app-language tests round-trip without an activity delegate.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OnboardingViewModelTest {
    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var emailService: EmailService

    @Mock private lateinit var simDetector: SimDetector

    @Mock private lateinit var permissionManager: PermissionManager

    @Mock private lateinit var appContext: android.content.Context

    @Mock private lateinit var languagePrefs: SharedPreferences

    @Mock private lateinit var languagePrefsEditor: SharedPreferences.Editor

    @Mock private lateinit var resources: Resources

    private lateinit var viewModel: OnboardingViewModel

    // Unconfined: the view state is derived from the form's flow, so it must settle
    // synchronously after every handler call for the assertions below to see it.
    private val testDispatcher = UnconfinedTestDispatcher()

    private fun setupContextMocks() {
        whenever(appContext.getString(any<Int>())).thenAnswer { inv ->
            val resId = inv.arguments[0] as Int
            when (resId) {
                R.string.subject_template -> "[sms2email] SMS From %s"

                R.string.subject_template_with_sim -> "[sms2email] SMS From %s %s"

                R.string.subject_template_no_prefix -> "SMS from %s"

                R.string.subject_template_no_prefix_with_sim -> "SMS from %s %s"

                R.string.history_unknown_sim -> "Unknown SIM"

                // DeviceUtil.getDeviceName falls back to the app name when the device
                // name is unset
                R.string.app_name -> "SMS to Email"

                else -> ""
            }
        }
        whenever(appContext.getString(any<Int>(), any<String>(), any<String>())).thenAnswer { inv ->
            val resId = inv.arguments[0] as Int
            val a1 = inv.arguments[1] as String
            val a2 = inv.arguments[2] as String
            when (resId) {
                R.string.subject_template -> "[$a1] SMS from $a2"
                R.string.subject_template_no_prefix -> "SMS from $a1"
                R.string.subject_template_no_prefix_with_sim -> "SMS from $a1 $a2"
                R.string.subject_template_with_sim -> "[$a1] SMS from $a2 $a2"
                else -> ""
            }
        }
        whenever(appContext.getString(any<Int>(), any<String>())).thenAnswer { inv ->
            val resId = inv.arguments[0] as Int
            val a1 = inv.arguments[1] as String
            when (resId) {
                R.string.subject_template -> "[$a1] SMS from $a1"
                R.string.subject_template_no_prefix -> "SMS from $a1"
                R.string.subject_template_no_prefix_with_sim -> "SMS from $a1 $a1"
                R.string.subject_template_with_sim -> "[$a1] SMS from $a1 $a1"
                else -> ""
            }
        }
    }

    private fun createViewModel(
        subjectPrefix: String = "",
        isOnboardingComplete: Boolean = false,
        useContactResolution: Boolean = false,
        senderEmail: String = "",
        destEmail: String = "",
    ) {
        whenever(prefs.load()).thenReturn(
            AppSettings(
                subjectPrefix = subjectPrefix,
                useContactResolution = useContactResolution,
                senderEmail = senderEmail,
                destEmail = destEmail,
            ),
        )
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.isOnboardingComplete).thenReturn(isOnboardingComplete)
        doAnswer { inv ->
            val transform = inv.getArgument<(AppSettings) -> AppSettings>(0)
            prefs.save(transform(prefs.load()))
            null
        }.whenever(prefs).update(any())
        whenever(simDetector.isSingleSimDevice()).thenReturn(true)
        whenever(appContext.getSharedPreferences("app_language", Context.MODE_PRIVATE)).thenReturn(languagePrefs)
        whenever(languagePrefs.edit()).thenReturn(languagePrefsEditor)
        whenever(
            languagePrefsEditor.putString(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
            ),
        ).thenReturn(languagePrefsEditor)
        whenever(languagePrefsEditor.commit()).thenReturn(true)
        whenever(appContext.resources).thenReturn(resources)
        whenever(resources.configuration).thenReturn(Configuration())
        setupContextMocks()
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

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        MockitoAnnotations.openMocks(this)
        // The delegate stores the app language in static state that leaks across tests, so
        // reset it to "follow the system" before every test.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        createViewModel()
    }

    @After
    fun tearDown() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        Dispatchers.resetMain()
    }

    @Test
    fun `SMTP field change handlers`() {
        viewModel.form.onSmtpHostChanged("smtp.example.com")
        assertEquals("smtp.example.com", viewModel.state.value.config.settings.smtpHost)

        viewModel.form.onPortDraftChanged("465")
        assertEquals(465, viewModel.state.value.config.settings.smtpPort)
        assertEquals("465", viewModel.state.value.config.portDraft)

        viewModel.form.onSmtpUsernameChanged(TestEmail.SELF)
        assertEquals(TestEmail.SELF, viewModel.state.value.config.settings.smtpUsername)

        viewModel.form.onSmtpPasswordChanged("secret")
        assertEquals("secret", viewModel.state.value.config.smtpPassword)
    }

    @Test
    fun `email field change handlers`() {
        viewModel.form.onDestEmailChanged("dest@example.com")
        assertEquals("dest@example.com", viewModel.state.value.config.settings.destEmail)

        viewModel.form.onDestNameChanged("Dest")
        assertEquals("Dest", viewModel.state.value.config.settings.destName)

        viewModel.form.onSenderEmailChanged("sender@example.com")
        assertEquals("sender@example.com", viewModel.state.value.config.settings.senderEmail)

        viewModel.form.onSenderNameChanged("Sender")
        assertEquals("Sender", viewModel.state.value.config.settings.senderName)
    }

    @Test
    fun `smtp protocol switches the protocol and port`() {
        viewModel.form.onSmtpProtocolChanged(SmtpProtocol.IMPLICIT_TLS)
        assertEquals(SmtpProtocol.IMPLICIT_TLS, viewModel.state.value.config.settings.smtpProtocol)
        assertEquals(465, viewModel.state.value.config.settings.smtpPort)

        viewModel.form.onSmtpProtocolChanged(SmtpProtocol.START_TLS)
        assertEquals(SmtpProtocol.START_TLS, viewModel.state.value.config.settings.smtpProtocol)
        assertEquals(587, viewModel.state.value.config.settings.smtpPort)
    }

    @Test
    fun `initial smtp defaults`() {
        assertEquals(SmtpProtocol.START_TLS, viewModel.state.value.config.settings.smtpProtocol)
    }

    @Test
    fun `subject prefix prepopulated on first install`() {
        createViewModel(subjectPrefix = "", isOnboardingComplete = false)
        assertEquals("SMS to Email", viewModel.state.value.config.settings.subjectPrefix)
    }

    @Test
    fun `subject prefix not prepopulated when already set`() {
        createViewModel(subjectPrefix = "myapp", isOnboardingComplete = false)
        assertEquals("myapp", viewModel.state.value.config.settings.subjectPrefix)
    }

    @Test
    fun `subject prefix not prepopulated after onboarding complete`() {
        createViewModel(subjectPrefix = "", isOnboardingComplete = true)
        assertEquals("", viewModel.state.value.config.settings.subjectPrefix)
    }

    @Test
    fun `subject prefix seed persists the prefix along with the auto flag`() {
        // The seed must persist the prefix and the flag in one write: the send path
        // reads the stored prefix alone, so a split write would drop every prefix. The
        // setUp VM alone keeps the exact-once verify from counting a second seed.
        verify(prefs).save(
            AppSettings(
                subjectPrefix = "SMS to Email",
                autoSubjectPrefix = true,
                useContactResolution = false,
            ),
        )
    }

    @Test
    fun `subject prefix trims whitespace`() {
        viewModel.form.onSubjectPrefixChanged("  my-prefix  ")
        assertEquals("my-prefix", viewModel.state.value.config.settings.subjectPrefix)

        viewModel.form.onSubjectPrefixChanged("\tmy-prefix\t")
        assertEquals("my-prefix", viewModel.state.value.config.settings.subjectPrefix)
    }

    @Test
    fun `subject prefix whitespace only becomes empty`() {
        viewModel.form.onSubjectPrefixChanged("   ")
        assertEquals("", viewModel.state.value.config.settings.subjectPrefix)
    }

    @Test
    fun `subject prefix accepts empty and single char`() {
        viewModel.form.onSubjectPrefixChanged("")
        assertEquals("", viewModel.state.value.config.settings.subjectPrefix)

        viewModel.form.onSubjectPrefixChanged("a")
        assertEquals("a", viewModel.state.value.config.settings.subjectPrefix)

        viewModel.form.onSubjectPrefixChanged("custom prefix")
        assertEquals("custom prefix", viewModel.state.value.config.settings.subjectPrefix)
    }

    @Test
    fun `SMTP field changes reset smtpTestSucceeded`() {
        viewModel.form.onSmtpHostChanged("smtp.example.com")
        assertFalse(viewModel.state.value.smtpTestSucceeded)
        viewModel.form.onPortDraftChanged("587")
        assertFalse(viewModel.state.value.smtpTestSucceeded)
        viewModel.form.onSmtpUsernameChanged("u")
        assertFalse(viewModel.state.value.smtpTestSucceeded)
        viewModel.form.onSmtpPasswordChanged("p")
        assertFalse(viewModel.state.value.smtpTestSucceeded)
        viewModel.form.onSmtpProtocolChanged(SmtpProtocol.IMPLICIT_TLS)
        assertFalse(viewModel.state.value.smtpTestSucceeded)
    }

    @Test
    fun `email field changes reset testEmailSucceeded`() {
        viewModel.form.onDestEmailChanged(TestEmail.RECIPIENT)
        assertFalse(viewModel.state.value.testEmailSucceeded)
        viewModel.form.onDestNameChanged("D")
        assertFalse(viewModel.state.value.testEmailSucceeded)
        viewModel.form.onSenderEmailChanged(TestEmail.SELF)
        assertFalse(viewModel.state.value.testEmailSucceeded)
        viewModel.form.onSenderNameChanged("S")
        assertFalse(viewModel.state.value.testEmailSucceeded)
        viewModel.form.onSubjectPrefixChanged("p")
        assertFalse(viewModel.state.value.testEmailSucceeded)
    }

    @Test
    fun `smtp field edits clear the stored smtp test result`() =
        runTest {
            whenever(emailService.testConnection(any<SmtpConfig>())).thenReturn(EmailResult.Success)
            val edits =
                mapOf(
                    "host" to { viewModel.form.onSmtpHostChanged("other.example.com") },
                    "port" to { viewModel.form.onPortDraftChanged("25") },
                    "protocol" to { viewModel.form.onSmtpProtocolChanged(SmtpProtocol.IMPLICIT_TLS) },
                    "username" to { viewModel.form.onSmtpUsernameChanged("other") },
                    "password" to { viewModel.form.onSmtpPasswordChanged("other") },
                )
            withMockedToast {
                edits.forEach { (name, edit) ->
                    viewModel.testConnection()
                    advanceUntilIdle()
                    assertTrue(viewModel.state.value.config.test.smtpTestResult is EmailResult.Success)
                    edit()
                    assertEquals(null, viewModel.state.value.config.test.smtpTestResult, "edit of $name must clear the result")
                }
            }
        }

    @Test
    fun `testConnection toasts the success once`() =
        runTest {
            whenever(appContext.getString(R.string.email_config_test_success)).thenReturn("Test successful")
            whenever(emailService.testConnection(any<SmtpConfig>())).thenReturn(EmailResult.Success)
            withMockedToast { staticToast ->
                viewModel.testConnection()
                advanceUntilIdle()
                staticToast.verify {
                    Toast.makeText(eq(appContext), eq("Test successful"), eq(Toast.LENGTH_SHORT))
                }
            }
        }

    @Test
    fun `testConnection toasts the failure message once`() =
        runTest {
            whenever(emailService.testConnection(any<SmtpConfig>())).thenReturn(EmailResult.Failure("smtp said no"))
            withMockedToast { staticToast ->
                viewModel.testConnection()
                advanceUntilIdle()
                staticToast.verify {
                    Toast.makeText(eq(appContext), eq("smtp said no"), eq(Toast.LENGTH_SHORT))
                }
            }
        }

    @Test
    fun `email field edits clear the stored test email result`() =
        runTest {
            whenever(emailService.sendTestEmail(any<SmtpConfig>(), any<TestEmailFields>())).thenReturn(EmailResult.Success)
            val edits =
                mapOf(
                    "dest email" to { viewModel.form.onDestEmailChanged("other@example.com") },
                    "dest name" to { viewModel.form.onDestNameChanged("Other") },
                    "sender email" to { viewModel.form.onSenderEmailChanged("other@example.com") },
                    "sender name" to { viewModel.form.onSenderNameChanged("Other") },
                    "subject prefix" to { viewModel.form.onSubjectPrefixChanged("new-prefix") },
                )
            withMockedToast {
                edits.forEach { (name, edit) ->
                    viewModel.sendTestEmail()
                    advanceUntilIdle()
                    assertTrue(viewModel.state.value.config.test.testEmailResult is EmailResult.Success)
                    edit()
                    assertEquals(null, viewModel.state.value.config.test.testEmailResult, "edit of $name must clear the result")
                }
            }
        }

    @Test
    fun `sendTestEmail toasts the success once`() =
        runTest {
            whenever(appContext.getString(R.string.email_config_test_success)).thenReturn("Test successful")
            whenever(emailService.sendTestEmail(any<SmtpConfig>(), any<TestEmailFields>())).thenReturn(EmailResult.Success)
            withMockedToast { staticToast ->
                viewModel.sendTestEmail()
                advanceUntilIdle()
                staticToast.verify {
                    Toast.makeText(eq(appContext), eq("Test successful"), eq(Toast.LENGTH_SHORT))
                }
            }
        }

    @Test
    fun `onboardingCompleteFlow initial value`() {
        assertFalse(viewModel.state.value.onboardingComplete)
    }

    @Test
    fun `initial state has no test results or success flags`() {
        assertFalse(viewModel.state.value.smtpTestSucceeded)
        assertFalse(viewModel.state.value.testEmailSucceeded)
        assertEquals(null, viewModel.state.value.config.test.smtpTestResult)
        assertEquals(null, viewModel.state.value.config.test.testEmailResult)
    }

    @Test
    fun `contactResolution initial and toggle`() {
        createViewModel(useContactResolution = false)
        assertFalse(viewModel.state.value.config.settings.useContactResolution)

        createViewModel(useContactResolution = true)
        assertTrue(viewModel.state.value.config.settings.useContactResolution)

        viewModel.form.onUseContactResolutionChanged(false)
        assertFalse(viewModel.state.value.config.settings.useContactResolution)
        viewModel.form.onUseContactResolutionChanged(true)
        assertTrue(viewModel.state.value.config.settings.useContactResolution)
    }

    @Test
    fun `autoFill derives sender email from host and username`() {
        createViewModel()
        viewModel.form.onSmtpHostChanged("smtp.example.com")
        assertEquals("", viewModel.state.value.config.settings.senderEmail)
        viewModel.form.onSmtpUsernameChanged("user")
        assertEquals("", viewModel.state.value.config.settings.senderEmail)
        viewModel.tryAutoFillEmail()
        assertEquals(TestEmail.SELF, viewModel.state.value.config.settings.senderEmail)
    }

    @Test
    fun `autoFill copies sender email to dest email when empty`() {
        createViewModel()
        viewModel.form.onSmtpHostChanged("smtp.example.com")
        viewModel.form.onSmtpUsernameChanged("user")
        viewModel.tryAutoFillEmail()
        assertEquals(TestEmail.SELF, viewModel.state.value.config.settings.destEmail)
    }

    @Test
    fun `autoFill does not override existing sender email`() {
        createViewModel(senderEmail = "manual@example.com")
        viewModel.form.onSmtpHostChanged("smtp.example.com")
        viewModel.form.onSmtpUsernameChanged("user")
        viewModel.tryAutoFillEmail()
        assertEquals("manual@example.com", viewModel.state.value.config.settings.senderEmail)
    }

    @Test
    fun `autoFill does not override existing dest email`() {
        createViewModel(destEmail = "other@example.com")
        viewModel.form.onSmtpHostChanged("smtp.example.com")
        viewModel.form.onSmtpUsernameChanged("user")
        viewModel.tryAutoFillEmail()
        assertEquals(TestEmail.SELF, viewModel.state.value.config.settings.senderEmail)
        assertEquals("other@example.com", viewModel.state.value.config.settings.destEmail)
    }

    @Test
    fun `autoFill does nothing when host or username empty`() {
        createViewModel()
        viewModel.form.onSmtpHostChanged("smtp.example.com")
        viewModel.tryAutoFillEmail()
        assertEquals("", viewModel.state.value.config.settings.senderEmail)
        viewModel.form.onSmtpUsernameChanged("")
        viewModel.tryAutoFillEmail()
        assertEquals("", viewModel.state.value.config.settings.senderEmail)
    }

    @Test
    fun `autoFill uses username as-is when it is already an email`() {
        createViewModel()
        viewModel.form.onSmtpHostChanged("smtp.example.com")
        viewModel.form.onSmtpUsernameChanged(TestEmail.OTHER)
        viewModel.tryAutoFillEmail()
        assertEquals(TestEmail.OTHER, viewModel.state.value.config.settings.senderEmail)
        assertEquals(TestEmail.OTHER, viewModel.state.value.config.settings.destEmail)
    }

    @Test
    fun `autoFill strips mail prefix from host`() {
        createViewModel()
        viewModel.form.onSmtpHostChanged("mail.example.com")
        viewModel.form.onSmtpUsernameChanged("user")
        viewModel.tryAutoFillEmail()
        assertEquals(TestEmail.SELF, viewModel.state.value.config.settings.senderEmail)
    }

    @Test
    fun `autoFill strips smtp0 prefix from host`() {
        createViewModel()
        viewModel.form.onSmtpHostChanged("smtp0.example.com")
        viewModel.form.onSmtpUsernameChanged("user")
        viewModel.tryAutoFillEmail()
        assertEquals(TestEmail.SELF, viewModel.state.value.config.settings.senderEmail)
    }

    @Test
    fun `autoFill not triggered on keystroke`() {
        createViewModel()
        viewModel.form.onSmtpHostChanged("smtp.example.com")
        assertEquals("", viewModel.state.value.config.settings.senderEmail)
        viewModel.form.onSmtpUsernameChanged("user")
        assertEquals("", viewModel.state.value.config.settings.senderEmail)
    }

    // Mirrors the real wizard: the DONE step is only reachable after a succeeded
    // test email, which requires a valid sender and destination.
    private fun fillCompleteConfig() {
        viewModel.form.onSmtpHostChanged("smtp.example.com")
        viewModel.form.onSmtpUsernameChanged(TestEmail.SELF)
        viewModel.form.onSmtpPasswordChanged("secret")
        viewModel.form.onDestEmailChanged("dest@example.com")
        viewModel.form.onSenderEmailChanged("sender@example.com")
    }

    @Test
    fun `completeOnboarding persists settings and marks complete`() {
        createViewModel()
        fillCompleteConfig()

        val completed = viewModel.completeOnboarding()
        testDispatcher.scheduler.runCurrent()

        assertTrue(completed)
        // The init block prepopulates the subject prefix with the device name, so the
        // committed draft carries it.
        verify(prefs).save(
            AppSettings(
                smtpHost = "smtp.example.com",
                smtpUsername = TestEmail.SELF,
                destEmail = "dest@example.com",
                senderEmail = "sender@example.com",
                subjectPrefix = "SMS to Email",
                autoSubjectPrefix = true,
                useContactResolution = false,
            ),
        )
        verify(prefs).smtpPassword = "secret"
        // The product default snapshot (forwarding on) on top of the stubbed store.
        verify(prefs).save(AppSettings(useContactResolution = false))
        verify(prefs).isOnboardingComplete = true
        assertTrue(viewModel.state.value.onboardingComplete)
    }

    @Test
    fun `completeOnboarding returns false and persists nothing with an empty config`() {
        createViewModel()

        val completed = viewModel.completeOnboarding()
        testDispatcher.scheduler.runCurrent()

        assertFalse(completed)
        verify(prefs, never()).isOnboardingComplete = true
        verify(prefs, never()).save(AppSettings())
        assertFalse(viewModel.state.value.onboardingComplete)
    }

    @Test
    fun `completeOnboarding refuses when host is blank`() {
        createViewModel()
        fillCompleteConfig()
        viewModel.form.onSmtpHostChanged("   ")

        assertFalse(viewModel.completeOnboarding())
        verify(prefs, never()).isOnboardingComplete = true
        assertFalse(viewModel.state.value.onboardingComplete)
    }

    @Test
    fun `completeOnboarding refuses when username is blank`() {
        createViewModel()
        fillCompleteConfig()
        viewModel.form.onSmtpUsernameChanged("")

        assertFalse(viewModel.completeOnboarding())
        verify(prefs, never()).isOnboardingComplete = true
    }

    @Test
    fun `completeOnboarding refuses when password is blank`() {
        createViewModel()
        fillCompleteConfig()
        viewModel.form.onSmtpPasswordChanged("")

        assertFalse(viewModel.completeOnboarding())
        verify(prefs, never()).isOnboardingComplete = true
    }

    @Test
    fun `completeOnboarding refuses when dest email is blank`() {
        createViewModel()
        fillCompleteConfig()
        viewModel.form.onDestEmailChanged("")

        assertFalse(viewModel.completeOnboarding())
        verify(prefs, never()).isOnboardingComplete = true
    }

    @Test
    fun `completeOnboarding refuses when dest email is not an email`() {
        createViewModel()
        fillCompleteConfig()
        viewModel.form.onDestEmailChanged("dest@test")

        assertFalse(viewModel.completeOnboarding())
        verify(prefs, never()).isOnboardingComplete = true
    }

    @Test
    fun `completeOnboarding refuses when sender email is blank`() {
        createViewModel()
        fillCompleteConfig()
        viewModel.form.onSenderEmailChanged("")

        assertFalse(viewModel.completeOnboarding())
        verify(prefs, never()).isOnboardingComplete = true
    }

    @Test
    fun `completeOnboarding refuses when sender email is not an email`() {
        createViewModel()
        fillCompleteConfig()
        viewModel.form.onSenderEmailChanged("not-an-email")

        assertFalse(viewModel.completeOnboarding())
        verify(prefs, never()).isOnboardingComplete = true
    }

    @Test
    fun `completeOnboarding refuses when the port draft is out of range`() {
        createViewModel()
        fillCompleteConfig()
        viewModel.form.onPortDraftChanged("0")

        assertFalse(viewModel.completeOnboarding())
        viewModel.form.onPortDraftChanged("65536")
        assertFalse(viewModel.completeOnboarding())
        verify(prefs, never()).isOnboardingComplete = true
    }

    @Test
    fun `completeOnboarding refuses when the port draft is empty`() {
        createViewModel()
        fillCompleteConfig()
        viewModel.form.onPortDraftChanged("")

        assertFalse(viewModel.completeOnboarding())
        verify(prefs, never()).isOnboardingComplete = true
    }

    @Test
    fun `completeOnboarding accepts boundary ports`() {
        createViewModel()
        fillCompleteConfig()
        viewModel.form.onPortDraftChanged("1")

        assertTrue(viewModel.completeOnboarding())
        viewModel.form.onPortDraftChanged("65535")
        assertTrue(viewModel.completeOnboarding())
    }

    @Test
    fun `skipOnboarding disables forwarding and marks onboarding complete`() {
        createViewModel()

        viewModel.skipOnboarding()
        testDispatcher.scheduler.runCurrent()

        verify(prefs).save(AppSettings(useContactResolution = false, isForwardingEnabled = false))
        verify(prefs).isOnboardingComplete = true
        assertTrue(viewModel.state.value.onboardingComplete)
    }

    @Test
    fun `alreadyConfigured is false on first run`() {
        createViewModel(isOnboardingComplete = false)
        assertFalse(viewModel.state.value.alreadyConfigured)
    }

    @Test
    fun `alreadyConfigured is true when onboarding already completed`() {
        createViewModel(isOnboardingComplete = true)
        assertTrue(viewModel.state.value.alreadyConfigured)
    }

    @Test
    fun `setAppLanguage applies the tag and updates state`() {
        viewModel.form.setAppLanguage("ru")
        assertEquals("ru", viewModel.state.value.config.appLanguage)
        assertEquals(LocaleListCompat.forLanguageTags("ru"), AppCompatDelegate.getApplicationLocales())
    }

    @Test
    fun `setAppLanguage with an empty tag resets to the system language`() {
        viewModel.form.setAppLanguage("ru")
        viewModel.form.setAppLanguage("")
        assertEquals("", viewModel.state.value.config.appLanguage)
        assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
    }

    @Test
    fun `setAppLanguage ignores a tag outside the shipped languages`() {
        viewModel.form.setAppLanguage("fr")
        assertEquals("", viewModel.state.value.config.appLanguage)
        assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
    }

    @Test
    fun `initial app language follows the AppCompat state`() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        createViewModel()
        assertEquals("en", viewModel.state.value.config.appLanguage)
    }

    @Test
    fun `importPicked reads the picked file, applies it and toasts the onboarding success`() {
        // The import's I/O phase runs on a real IO thread; with the unconfined Main its
        // resumption would run inline there, where the thread-bound static Toast mock
        // cannot intercept. A standard dispatcher routes it through the test scheduler.
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        createViewModel()
        stubPickResolver(FULL_IMPORT_JSON)
        clearInvocations(prefs)

        withMockedToast { toast ->
            val job = viewModel.importPicked(pickedUri)
            assertNotNull(job)
            awaitImport(job, dispatcher)
            toast.verify {
                Toast.makeText(eq(appContext), eq(R.string.config_import_success), eq(Toast.LENGTH_SHORT))
            }
        }

        verify(prefs).save(
            AppSettings(
                smtpHost = "smtp.example.com",
                smtpPort = 465,
                smtpProtocol = SmtpProtocol.IMPLICIT_TLS,
                smtpUsername = TestEmail.SELF,
                destEmail = "dest@example.com",
                senderEmail = "sender@example.com",
                autoCleanupRetentionDays = 365,
                usePduTimestamp = true,
            ),
        )
    }

    private val pickedUri = Uri.parse("content://com.android.documentsui/documents/pick")

    private fun stubPickResolver(json: String) {
        val resolver: ContentResolver = mock()
        val cursor: Cursor = mock()
        whenever(appContext.contentResolver).thenReturn(resolver)
        whenever(
            resolver.query(
                any<Uri>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
            ),
        ).thenReturn(cursor)
        whenever(cursor.moveToFirst()).thenReturn(true)
        whenever(cursor.getColumnIndex(OpenableColumns.SIZE)).thenReturn(0)
        whenever(cursor.isNull(0)).thenReturn(false)
        whenever(cursor.getLong(0)).thenReturn(json.length.toLong())
        whenever(resolver.openInputStream(any<Uri>())).thenReturn(ByteArrayInputStream(json.toByteArray()))
    }

    // The import's I/O phase runs on a real thread; its resumption lands on [dispatcher]'s
    // scheduler, so the wait advances it until the import completes.
    private fun awaitImport(
        job: Job,
        dispatcher: TestDispatcher,
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        while (job.isActive) {
            dispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(5)
            check(System.currentTimeMillis() < deadline) { "the import did not complete" }
        }
    }

    @Test
    fun `smtpTestSucceeded is false when result is a failure`() {
        createViewModel()
        viewModel.form.update {
            it.copy(test = it.test.copy(smtpTestResult = EmailResult.Failure("connection refused")))
        }
        assertFalse(viewModel.state.value.smtpTestSucceeded)
    }

    @Test
    fun `testEmailSucceeded is false when result is a failure`() {
        createViewModel()
        viewModel.form.update {
            it.copy(test = it.test.copy(testEmailResult = EmailResult.Failure("send failed")))
        }
        assertFalse(viewModel.state.value.testEmailSucceeded)
    }

    @Test
    fun `smtpTestSucceeded is true only for success result`() {
        createViewModel()
        viewModel.form.update {
            it.copy(test = it.test.copy(smtpTestResult = EmailResult.Success))
        }
        assertTrue(viewModel.state.value.smtpTestSucceeded)
    }

    @Test
    fun `testEmailSucceeded is true only for success result`() {
        createViewModel()
        viewModel.form.update {
            it.copy(test = it.test.copy(testEmailResult = EmailResult.Success))
        }
        assertTrue(viewModel.state.value.testEmailSucceeded)
    }

    @Test
    fun `tryAutoFillEmail does nothing when host is empty`() {
        createViewModel()
        viewModel.form.onSmtpUsernameChanged("user")
        viewModel.tryAutoFillEmail()
        assertEquals("", viewModel.state.value.config.settings.senderEmail)
    }

    @Test
    fun `tryAutoFillEmail does nothing when username is empty`() {
        createViewModel()
        viewModel.form.onSmtpHostChanged("smtp.example.com")
        viewModel.tryAutoFillEmail()
        assertEquals("", viewModel.state.value.config.settings.senderEmail)
    }

    @Test
    fun `tryAutoFillEmail does nothing when host is whitespace only`() {
        createViewModel()
        viewModel.form.onSmtpHostChanged("   ")
        viewModel.form.onSmtpUsernameChanged("user")
        viewModel.tryAutoFillEmail()
        assertEquals("", viewModel.state.value.config.settings.senderEmail)
    }

    @Test
    fun `tryAutoFillEmail does nothing when username is whitespace only`() {
        createViewModel()
        viewModel.form.onSmtpHostChanged("smtp.example.com")
        viewModel.form.onSmtpUsernameChanged("   ")
        viewModel.tryAutoFillEmail()
        assertEquals("", viewModel.state.value.config.settings.senderEmail)
    }

    @Test
    fun `completeOnboarding succeeds when forwarding already enabled`() {
        whenever(prefs.load()).thenReturn(
            AppSettings(
                subjectPrefix = "SMS to Email",
                autoSubjectPrefix = true,
                isForwardingEnabled = true,
            ),
        )
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.isOnboardingComplete).thenReturn(false)
        doAnswer { inv ->
            val transform = inv.getArgument<(AppSettings) -> AppSettings>(0)
            prefs.save(transform(prefs.load()))
            null
        }.whenever(prefs).update(any())
        whenever(simDetector.isSingleSimDevice()).thenReturn(true)
        whenever(appContext.getSharedPreferences("app_language", Context.MODE_PRIVATE)).thenReturn(languagePrefs)
        whenever(languagePrefs.edit()).thenReturn(languagePrefsEditor)
        whenever(
            languagePrefsEditor.putString(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
            ),
        ).thenReturn(languagePrefsEditor)
        whenever(languagePrefsEditor.commit()).thenReturn(true)
        whenever(appContext.resources).thenReturn(resources)
        whenever(resources.configuration).thenReturn(Configuration())
        setupContextMocks()
        viewModel =
            OnboardingViewModel(
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                simDetector,
                permissionManager,
                appContext,
            )
        fillCompleteConfig()
        assertTrue(viewModel.completeOnboarding())
    }

    @Test
    fun `sendTestEmail toasts the failure message`() =
        runTest {
            whenever(
                emailService.sendTestEmail(
                    any<SmtpConfig>(),
                    any<TestEmailFields>(),
                ),
            ).thenReturn(EmailResult.Failure("delivery failed"))
            withMockedToast { staticToast ->
                viewModel.sendTestEmail()
                advanceUntilIdle()
                staticToast.verify {
                    Toast.makeText(eq(appContext), eq("delivery failed"), eq(Toast.LENGTH_SHORT))
                }
            }
        }

    @Test
    fun `importPicked failure toasts the failure message`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        createViewModel()
        stubPickResolver("not valid json")
        clearInvocations(prefs)

        withMockedToast { toast ->
            val job = viewModel.importPicked(pickedUri)
            assertNotNull(job)
            awaitImport(job, dispatcher)
            toast.verify {
                Toast.makeText(eq(appContext), eq(R.string.config_import_failed), eq(Toast.LENGTH_SHORT))
            }
        }
    }

    @Test
    fun `state reflects onboardingComplete flag from flow`() {
        createViewModel()
        assertFalse(viewModel.state.value.onboardingComplete)
        viewModel.skipOnboarding()
        testDispatcher.scheduler.runCurrent()
        assertTrue(viewModel.state.value.onboardingComplete)
    }

    @Test
    fun `state reflects alreadyConfigured when onboarding was completed before`() {
        createViewModel(isOnboardingComplete = true)
        assertTrue(viewModel.state.value.alreadyConfigured)
    }

    @Test
    fun `state does not reflect alreadyConfigured on first run`() {
        createViewModel(isOnboardingComplete = false)
        assertFalse(viewModel.state.value.alreadyConfigured)
    }

    @Suppress("ktlint:standard:max-line-length")
    private companion object {
        val FULL_IMPORT_JSON =
            """{"version":1,"smtp_host":"smtp.example.com","smtp_port":465,"smtp_protocol":"IMPLICIT_TLS","smtp_username":"${TestEmail.SELF}","dest_email":"dest@example.com","dest_name":"","sender_email":"sender@example.com","sender_name":"","subject_prefix":"","auto_subject_prefix":false,"forwarding_enabled":true,"contact_resolution":true,"display_sim_cards":false,"auto_cleanup_retention_days":365,"json_email_format":false,"use_pdu_timestamp":true,"dedup_level":"BALANCED"}"""
    }
}
