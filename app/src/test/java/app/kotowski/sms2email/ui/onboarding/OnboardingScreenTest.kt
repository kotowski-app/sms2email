package app.kotowski.sms2email.ui.onboarding

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.service.EmailResult
import app.kotowski.sms2email.service.EmailService
import app.kotowski.sms2email.service.SmtpConfig
import app.kotowski.sms2email.service.SmtpTestRunner
import app.kotowski.sms2email.service.SmtpTester
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.system.PermissionManager
import app.kotowski.sms2email.system.PermissionSnapshot
import app.kotowski.sms2email.system.PermissionsAutoResetStatus
import app.kotowski.sms2email.system.SimDetector
import app.kotowski.sms2email.testing.SkipInReleaseRule
import app.kotowski.sms2email.testing.withMockedToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingScreenTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var emailService: EmailService

    @Mock private lateinit var simDetector: SimDetector

    @Mock private lateinit var permissionManager: PermissionManager

    private lateinit var viewModel: OnboardingViewModel
    private var completeCalled = false
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        whenever(prefs.load()).thenReturn(AppSettings())
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.isOnboardingComplete).thenReturn(false)
        whenever(simDetector.isSingleSimDevice()).thenReturn(true)
        completeCalled = false
        viewModel =
            OnboardingViewModel(
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(context, prefs),
                simDetector,
                permissionManager,
                context,
            )
    }

    @After
    fun tearDown() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        Dispatchers.resetMain()
    }

    private fun showScreen() {
        composeTestRule.setContent {
            OnboardingScreen(
                onComplete = { completeCalled = true },
                viewModel = viewModel,
            )
        }
        composeTestRule.waitForIdle()
    }

    private fun clickNext() {
        composeTestRule.onNodeWithText(context.getString(R.string.action_next)).performClick()
        composeTestRule.waitForIdle()
    }

    private fun fillCompleteConfig() {
        viewModel.form.onSmtpHostChanged("smtp.example.com")
        viewModel.form.onSmtpUsernameChanged("user@example.com")
        viewModel.form.onSmtpPasswordChanged("secret")
        viewModel.form.onDestEmailChanged("dest@example.com")
        viewModel.form.onSenderEmailChanged("sender@example.com")
        viewModel.form.update {
            it.copy(test = it.test.copy(smtpTestResult = EmailResult.Success, testEmailResult = EmailResult.Success))
        }
    }

    @Test
    fun `proceeding on the permissions step without permissions toasts the grant hint`() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = false, notifications = false, contacts = false, phone = false, battery = false),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
        }
        showScreen()
        clickNext()
        withMockedToast { staticToast ->
            composeTestRule.onNodeWithText(context.getString(R.string.action_next)).performClick()
            staticToast.verify {
                Toast.makeText(any<Context>(), eq(R.string.onboarding_grant_required), eq(Toast.LENGTH_SHORT))
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.permissions_sms)).assertExists()
        assertFalse(completeCalled)
    }

    // The state where the required SMS pair is granted but a recommended permission is
    // not: proceeding past the permissions step is gated by the warning dialog only.
    private fun setRecommendedMissingState() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = false, contacts = true, phone = true, battery = true),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
        }
    }

    @Test
    fun `proceeding with a recommended permission missing shows the recommended warning dialog`() {
        setRecommendedMissingState()
        showScreen()
        clickNext() // WELCOME -> PERMISSIONS
        clickNext() // PERMISSIONS -> warning dialog, not the next step
        composeTestRule
            .onNodeWithText(context.getString(R.string.onboarding_permissions_missing_warning_title))
            .assertExists()
        // The detail lists the missing permission on its own line, not inline.
        val detail =
            context.getString(
                R.string.onboarding_permissions_missing_warning_text,
                context.getString(R.string.permissions_notifications),
            )
        composeTestRule.onNodeWithText(detail, substring = true).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.permissions_sms)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_section_smtp)).assertDoesNotExist()
        assertFalse(completeCalled)
    }

    @Test
    fun `proceeding with battery optimization missing shows the recommended warning dialog`() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = true, contacts = true, phone = true, battery = false),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
        }
        showScreen()
        clickNext() // WELCOME -> PERMISSIONS
        clickNext() // PERMISSIONS -> warning dialog
        composeTestRule
            .onNodeWithText(context.getString(R.string.onboarding_permissions_missing_warning_title))
            .assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_section_smtp)).assertDoesNotExist()
    }

    @Test
    fun `proceeding with permissions auto-reset active shows the recommended warning dialog`() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = true, contacts = true, phone = true, battery = true),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.ACTIVE)
        }
        showScreen()
        clickNext() // WELCOME -> PERMISSIONS
        clickNext() // PERMISSIONS -> warning dialog
        composeTestRule
            .onNodeWithText(context.getString(R.string.onboarding_permissions_missing_warning_title))
            .assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_section_smtp)).assertDoesNotExist()
    }

    @Test
    fun `confirming the recommended warning dialog proceeds to the smtp step`() {
        setRecommendedMissingState()
        showScreen()
        clickNext() // WELCOME -> PERMISSIONS
        clickNext() // PERMISSIONS -> warning dialog
        composeTestRule.onNodeWithText(context.getString(R.string.onboarding_skip)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_section_smtp)).assertExists()
        composeTestRule
            .onNodeWithText(context.getString(R.string.onboarding_permissions_missing_warning_title))
            .assertDoesNotExist()
    }

    @Test
    fun `dismissing the recommended warning dialog stays on the permissions step`() {
        setRecommendedMissingState()
        showScreen()
        clickNext() // WELCOME -> PERMISSIONS
        clickNext() // PERMISSIONS -> warning dialog
        composeTestRule.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.permissions_sms)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_section_smtp)).assertDoesNotExist()
        assertFalse(completeCalled)
    }

    @Test
    fun `auto-reset row is hidden when the platform does not support the feature`() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = true, contacts = true, phone = true, battery = true),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.NOT_AVAILABLE)
        }
        showScreen()
        clickNext() // WELCOME -> PERMISSIONS
        val autoResetTitle =
            context.getString(R.string.recommended_suffix, context.getString(R.string.permissions_auto_reset))
        composeTestRule
            .onNodeWithText(autoResetTitle)
            .assertDoesNotExist()
        // The other rows are unaffected.
        composeTestRule.onNodeWithText(context.getString(R.string.permissions_battery), substring = true).assertExists()
    }

    @Test
    fun `wizard step survives the composition leaving and re-entering`() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = true, contacts = true, phone = true, battery = true),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
        }

        // In and out of the composition behind a SaveableStateHolder (the same mechanism
        // NavHost and a rotation use): the saveable state is restored on re-entry.
        val showWizard = mutableStateOf(true)
        composeTestRule.setContent {
            val stateHolder = rememberSaveableStateHolder()
            stateHolder.SaveableStateProvider(if (showWizard.value) "wizard" else "elsewhere") {
                if (showWizard.value) {
                    OnboardingScreen(
                        onComplete = { completeCalled = true },
                        viewModel = viewModel,
                    )
                }
            }
        }
        clickNext() // WELCOME -> PERMISSIONS
        clickNext() // PERMISSIONS -> SMTP
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_section_smtp)).assertExists()

        composeTestRule.runOnIdle { showWizard.value = false }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { showWizard.value = true }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(context.getString(R.string.email_config_section_smtp)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.onboarding_welcome_subtitle)).assertDoesNotExist()
        assertFalse(completeCalled)
    }

    @Test
    fun `a finished smtp test toasts once and does not re-toast on step round-trip`() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = true, contacts = true, phone = true, battery = true),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
            whenever(emailService.testConnection(any<SmtpConfig>())).thenReturn(EmailResult.Success)
        }
        showScreen()
        clickNext() // WELCOME -> PERMISSIONS
        clickNext() // PERMISSIONS -> SMTP

        val successMessage = context.getString(R.string.email_config_test_success)
        withMockedToast { staticToast ->
            viewModel.testConnection()
            // The stubbed service's resumption is scheduled on the test dispatcher;
            // the compose rule's idle does not advance it.
            testDispatcher.scheduler.advanceUntilIdle()
            composeTestRule.waitForIdle()
            staticToast.verify {
                Toast.makeText(eq(context), eq(successMessage), eq(Toast.LENGTH_SHORT))
            }
            clickNext()
            composeTestRule.onNodeWithText(context.getString(R.string.action_back)).performClick()
            composeTestRule.waitForIdle()
            staticToast.verifyNoMoreInteractions()
        }
    }

    @Test
    fun `a finished smtp test toasts once and does not re-toast when the composition is recreated`() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = true, contacts = true, phone = true, battery = true),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
            whenever(emailService.testConnection(any<SmtpConfig>())).thenReturn(EmailResult.Success)
        }

        // Out of the composition behind a SaveableStateHolder and back (the same
        // mechanism a rotation uses): the saved step and VM result are restored.
        val showWizard = mutableStateOf(true)
        composeTestRule.setContent {
            val stateHolder = rememberSaveableStateHolder()
            stateHolder.SaveableStateProvider(if (showWizard.value) "wizard" else "elsewhere") {
                if (showWizard.value) {
                    OnboardingScreen(
                        onComplete = { completeCalled = true },
                        viewModel = viewModel,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        clickNext() // WELCOME -> PERMISSIONS
        clickNext() // PERMISSIONS -> SMTP

        val successMessage = context.getString(R.string.email_config_test_success)
        withMockedToast { staticToast ->
            viewModel.testConnection()
            // The stubbed service's resumption is scheduled on the test dispatcher;
            // the compose rule's idle does not advance it.
            testDispatcher.scheduler.advanceUntilIdle()
            composeTestRule.waitForIdle()
            staticToast.verify {
                Toast.makeText(eq(context), eq(successMessage), eq(Toast.LENGTH_SHORT))
            }
            composeTestRule.runOnIdle { showWizard.value = false }
            composeTestRule.waitForIdle()
            composeTestRule.runOnIdle { showWizard.value = true }
            composeTestRule.waitForIdle()
            staticToast.verifyNoMoreInteractions()
        }
    }

    @Test
    fun `finishing with an incomplete config toasts the incomplete hint`() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = true, contacts = true, phone = true, battery = true),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
        }
        // Both test gates must be green to reach the final step.
        viewModel.form.update {
            it.copy(test = it.test.copy(smtpTestResult = EmailResult.Success, testEmailResult = EmailResult.Success))
        }
        showScreen()
        clickNext()
        clickNext()
        clickNext()
        clickNext()
        withMockedToast { staticToast ->
            composeTestRule.onNodeWithText(context.getString(R.string.action_done)).performClick()
            staticToast.verify {
                Toast.makeText(any<Context>(), eq(R.string.email_config_incomplete_config), eq(Toast.LENGTH_SHORT))
            }
        }
        composeTestRule.waitForIdle()
        assertFalse(completeCalled)
    }

    // The message is the same for a first run and a re-run: the wizard's Done enables
    // forwarding either way, so there is nothing to distinguish.
    @Test
    fun `the done step shows the setup complete message on entry`() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = true, contacts = true, phone = true, battery = true),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
        }
        // Both test gates must be green to reach the final step.
        viewModel.form.update {
            it.copy(test = it.test.copy(smtpTestResult = EmailResult.Success, testEmailResult = EmailResult.Success))
        }
        showScreen()
        clickNext()
        clickNext()
        clickNext()
        clickNext()
        composeTestRule.onNodeWithText(context.getString(R.string.onboarding_done_message)).assertExists()
        assertFalse(completeCalled)
    }

    @Test
    fun `back button navigates to the previous step`() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = true, contacts = true, phone = true, battery = true),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
        }
        showScreen()
        clickNext() // WELCOME -> PERMISSIONS
        clickNext() // PERMISSIONS -> SMTP
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_section_smtp)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.action_back)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.permissions_sms)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_section_smtp)).assertDoesNotExist()
    }

    @Test
    fun `back at welcome step invokes onBack`() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = true, contacts = true, phone = true, battery = true),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
        }
        var backInvoked = false
        composeTestRule.setContent {
            OnboardingScreen(
                onComplete = { completeCalled = true },
                onBack = { backInvoked = true },
                viewModel = viewModel,
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.action_back)).performClick()
        composeTestRule.waitForIdle()
        assertTrue(backInvoked)
    }

    @Test
    fun `smtp step without successful test toasts the enter fields hint`() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = true, contacts = true, phone = true, battery = true),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
        }
        showScreen()
        clickNext() // WELCOME -> PERMISSIONS
        clickNext() // PERMISSIONS -> SMTP
        withMockedToast { staticToast ->
            composeTestRule.onNodeWithText(context.getString(R.string.action_next)).performClick()
            staticToast.verify {
                Toast.makeText(any<Context>(), eq(R.string.onboarding_enter_fields_test), eq(Toast.LENGTH_SHORT))
            }
        }
        composeTestRule.waitForIdle()
        // Still on SMTP step
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_section_smtp)).assertExists()
    }

    @Test
    fun `sender step without successful test email toasts the enter fields hint`() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = true, contacts = true, phone = true, battery = true),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
        }
        viewModel.form.update {
            it.copy(test = it.test.copy(smtpTestResult = EmailResult.Success))
        }
        showScreen()
        clickNext() // WELCOME -> PERMISSIONS
        clickNext() // PERMISSIONS -> SMTP
        clickNext() // SMTP -> SENDER
        withMockedToast { staticToast ->
            composeTestRule.onNodeWithText(context.getString(R.string.action_next)).performClick()
            staticToast.verify {
                Toast.makeText(any<Context>(), eq(R.string.onboarding_enter_fields_test), eq(Toast.LENGTH_SHORT))
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `skip button hidden when already configured`() {
        whenever(prefs.isOnboardingComplete).thenReturn(true)
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = true, contacts = true, phone = true, battery = true),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
        }
        viewModel =
            OnboardingViewModel(
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(context, prefs),
                simDetector,
                permissionManager,
                context,
            )
        showScreen()
        composeTestRule.onNodeWithText(context.getString(R.string.onboarding_skip)).assertDoesNotExist()
    }

    @Test
    fun `onboarding complete triggers onComplete`() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = true, contacts = true, phone = true, battery = true),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
        }
        fillCompleteConfig()
        showScreen()
        clickNext()
        clickNext()
        clickNext()
        clickNext()
        composeTestRule.onNodeWithText(context.getString(R.string.action_done)).performClick()
        composeTestRule.waitForIdle()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(completeCalled)
    }

    @Test
    fun `sender step shows with dual sim device and phone not granted`() {
        whenever(simDetector.isSingleSimDevice()).thenReturn(false)
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = true, contacts = true, phone = false, battery = true),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
        }
        viewModel =
            OnboardingViewModel(
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(context, prefs),
                simDetector,
                permissionManager,
                context,
            )
        viewModel.form.update {
            it.copy(test = it.test.copy(smtpTestResult = EmailResult.Success))
        }
        showScreen()
        clickNext() // WELCOME -> PERMISSIONS
        clickNext() // PERMISSIONS -> SMTP
        clickNext() // SMTP -> SENDER
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_sender_title)).assertExists()
    }

    @Test
    fun `landscape smtp step renders`() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = true, contacts = true, phone = true, battery = true),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
        }
        showScreen()
        clickNext() // WELCOME -> PERMISSIONS
        clickNext() // PERMISSIONS -> SMTP
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_section_smtp)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_smtp_test)).assertExists()
    }

    @Test
    fun `back button on smtp step navigates to permissions`() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = true, contacts = true, phone = true, battery = true),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
        }
        showScreen()
        clickNext() // WELCOME -> PERMISSIONS
        clickNext() // PERMISSIONS -> SMTP
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_section_smtp)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.action_back)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.permissions_sms)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_section_smtp)).assertDoesNotExist()
    }

    @Test
    fun `phone permission row hidden on single sim device`() {
        whenever(simDetector.isSingleSimDevice()).thenReturn(true)
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = true, contacts = true, phone = true, battery = true),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
        }
        viewModel =
            OnboardingViewModel(
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(context, prefs),
                simDetector,
                permissionManager,
                context,
            )
        showScreen()
        clickNext() // WELCOME -> PERMISSIONS
        val phoneTitle =
            context.getString(R.string.optional_suffix, context.getString(R.string.permissions_phone))
        composeTestRule.onNodeWithText(phoneTitle).assertDoesNotExist()
    }

    @Test
    fun `phone permission row visible on dual sim device`() {
        whenever(simDetector.isSingleSimDevice()).thenReturn(false)
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = true, contacts = true, phone = true, battery = true),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
        }
        viewModel =
            OnboardingViewModel(
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(context, prefs),
                simDetector,
                permissionManager,
                context,
            )
        showScreen()
        clickNext() // WELCOME -> PERMISSIONS
        val phoneTitle =
            context.getString(R.string.optional_suffix, context.getString(R.string.permissions_phone))
        composeTestRule.onNodeWithText(phoneTitle).assertExists()
    }

    @Test
    fun `onboarding already complete on entry triggers onComplete`() {
        whenever(prefs.isOnboardingComplete).thenReturn(true)
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = true, contacts = true, phone = true, battery = true),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
        }
        viewModel =
            OnboardingViewModel(
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(context, prefs),
                simDetector,
                permissionManager,
                context,
            )
        viewModel.form.update { it.copy(test = it.test.copy(smtpTestResult = EmailResult.Success, testEmailResult = EmailResult.Success)) }
        fillCompleteConfig()
        // Directly complete onboarding to trigger the LaunchedEffect
        viewModel.completeOnboarding()
        showScreen()
        composeTestRule.waitForIdle()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(completeCalled)
    }

    @Test
    fun `done button with complete config marks onboarding complete and triggers callback`() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = true, contacts = true, phone = true, battery = true),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
        }
        fillCompleteConfig()
        showScreen()
        clickNext()
        clickNext()
        clickNext()
        clickNext()
        composeTestRule.onNodeWithText(context.getString(R.string.action_done)).performClick()
        composeTestRule.waitForIdle()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(completeCalled)
    }

    @Test
    fun `sender step renders with contacts permission not granted`() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = true, contacts = false, phone = false, battery = true),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
        }
        viewModel.form.update {
            it.copy(test = it.test.copy(smtpTestResult = EmailResult.Success))
        }
        showScreen()
        clickNext() // WELCOME -> PERMISSIONS
        clickNext() // PERMISSIONS -> SMTP
        clickNext() // SMTP -> SENDER
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_sender_title)).assertExists()
    }

    @Test
    fun `smtp step test button visible with partial credentials`() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = true, notifications = true, contacts = true, phone = true, battery = true),
        )
        runBlocking {
            whenever(permissionManager.getPermissionsAutoResetStatus()).thenReturn(PermissionsAutoResetStatus.DISABLED)
        }
        showScreen()
        clickNext() // WELCOME -> PERMISSIONS
        clickNext() // PERMISSIONS -> SMTP
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_smtp_test)).assertExists()
    }
}
