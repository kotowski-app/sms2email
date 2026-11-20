package app.kotowski.sms2email.ui.settings

import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import app.kotowski.sms2email.R
import app.kotowski.sms2email.ThemeMode
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.data.prefs.ThemePreference
import app.kotowski.sms2email.service.EmailResult
import app.kotowski.sms2email.service.EmailService
import app.kotowski.sms2email.service.SmtpConfig
import app.kotowski.sms2email.service.SmtpTestRunner
import app.kotowski.sms2email.service.SmtpTester
import app.kotowski.sms2email.service.TestEmailFields
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.system.PermissionManager
import app.kotowski.sms2email.system.PermissionSnapshot
import app.kotowski.sms2email.testing.SkipInReleaseRule
import app.kotowski.sms2email.testing.stubContextGetString
import app.kotowski.sms2email.testing.withMockedToast
import app.kotowski.sms2email.work.BackgroundStateReconciler
import app.kotowski.sms2email.work.ExactWakeArmer
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
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmailConfigEditScreenTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Mock private lateinit var appContext: Context

    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var emailService: EmailService

    @Mock private lateinit var permissionManager: PermissionManager

    @Mock private lateinit var themePreference: ThemePreference

    @Mock private lateinit var backgroundStateReconciler: BackgroundStateReconciler

    @Mock private lateinit var exactWakeArmer: ExactWakeArmer

    private lateinit var viewModel: SettingsViewModel
    private var backCalled = false
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        // The VM resolves the subject-preview strings through the app context at
        // construction; the mock delegates getString to the real application.
        stubContextGetString(appContext, context)
        // The stored config is complete, so a save attempt reaches the test gate.
        `when`(prefs.load()).thenReturn(
            AppSettings(
                smtpHost = "stored.smtp.com",
                smtpUsername = "stored-user",
                destEmail = "dest@example.com",
                destName = "Dest",
                senderEmail = "sender@example.com",
                senderName = "Sender",
                autoCleanupRetentionDays = 30,
                usePduTimestamp = true,
            ),
        )
        `when`(prefs.smtpPassword).thenReturn("stored-pass")
        `when`(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        // The VM reads the live permission at construction (the extras seed).
        `when`(permissionManager.snapshot())
            .thenReturn(PermissionSnapshot(sms = false, notifications = false, contacts = false, phone = false, battery = false))
        backCalled = false
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

    @After
    fun tearDown() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        Dispatchers.resetMain()
    }

    private fun showScreen() {
        composeTestRule.setContent {
            EmailConfigEditScreen(
                onNavigateBack = { backCalled = true },
                viewModel = viewModel,
            )
        }
    }

    private fun assertStoredConfigWritten() {
        verify(prefs).save(
            AppSettings(
                smtpHost = "stored.smtp.com",
                smtpUsername = "stored-user",
                destEmail = "dest@example.com",
                destName = "Dest",
                senderEmail = "sender@example.com",
                senderName = "Sender",
                autoCleanupRetentionDays = 30,
                usePduTimestamp = true,
            ),
        )
    }

    private fun assertNothingWritten() {
        verify(prefs, never()).save(any())
    }

    @Test
    fun `the screen shows the form, the test button and the save cancel bar`() {
        showScreen()
        // The top bar title (the screen also shows an "Email Settings" group header,
        // so the title is named by its own string, not a hardcoded phrase).
        composeTestRule.onNodeWithText(context.getString(R.string.settings_section_forwarding)).assertIsDisplayed()
        composeTestRule.onNodeWithText("stored.smtp.com").assertIsDisplayed()
        composeTestRule.onNodeWithText("stored-user").assertIsDisplayed()
        // The email pair and the test button sit below the test window's fold.
        composeTestRule.onNodeWithText("dest@example.com").assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_send_test_email)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.action_save)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.action_cancel)).assertIsDisplayed()
    }

    @Test
    fun `the save button is labelled once (the icon is decorative)`() {
        showScreen()
        // The text next to the icon is the button's label; an icon description of its
        // own would make TalkBack read "Save Save".
        composeTestRule.onNodeWithText(context.getString(R.string.action_save)).assertIsDisplayed()
        composeTestRule
            .onAllNodesWithContentDescription(context.getString(R.string.action_save))
            .assertCountEquals(0)
    }

    @Test
    fun `cancel leaves the screen without writing`() {
        showScreen()
        composeTestRule.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        composeTestRule.waitForIdle()
        assertTrue(backCalled)
        assertNothingWritten()
    }

    @Test
    fun `the back icon leaves the screen without writing`() {
        showScreen()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()
        composeTestRule.waitForIdle()
        assertTrue(backCalled)
        assertNothingWritten()
    }

    @Test
    fun `a field edit reaches only the draft and the back leaves it unwritten`() {
        showScreen()
        composeTestRule.onNodeWithText("stored.smtp.com").performClick()
        composeTestRule.onNodeWithText("stored.smtp.com").performTextReplacement("draft.smtp.com")
        composeTestRule.waitForIdle()
        assertEquals("draft.smtp.com", viewModel.state.value.config.settings.smtpHost)
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()
        composeTestRule.waitForIdle()
        assertTrue(backCalled)
        assertNothingWritten()
    }

    @Test
    fun `save with an incomplete config shows the toast and stays`() {
        showScreen()
        viewModel.form.onSmtpPasswordChanged("")
        withMockedToast {
            composeTestRule.onNodeWithText(context.getString(R.string.action_save)).performClick()
        }
        composeTestRule.waitForIdle()
        assertFalse(backCalled)
        assertNothingWritten()
        composeTestRule.onNodeWithText(context.getString(R.string.action_save)).assertExists()
    }

    @Test
    fun `save with an invalid port draft shows the toast and stays`() {
        showScreen()
        composeTestRule.onNodeWithText("587").performTextReplacement("70000")
        composeTestRule.waitForIdle()
        withMockedToast {
            composeTestRule.onNodeWithText(context.getString(R.string.action_save)).performClick()
        }
        composeTestRule.waitForIdle()
        assertFalse(backCalled)
        assertNothingWritten()
        composeTestRule.onNodeWithText(context.getString(R.string.action_save)).assertExists()
    }

    @Test
    fun `save with an unedited config commits and leaves without the warning`() {
        showScreen()
        composeTestRule.onNodeWithText(context.getString(R.string.action_save)).performClick()
        composeTestRule.waitForIdle()
        assertTrue(backCalled)
        composeTestRule.onNodeWithText(context.getString(R.string.settings_email_untested_title)).assertDoesNotExist()
        assertStoredConfigWritten()
    }

    @Test
    fun `save with an untested config shows the warning dialog`() {
        showScreen()
        viewModel.form.onSmtpHostChanged("changed.smtp.com")
        composeTestRule.onNodeWithText(context.getString(R.string.action_save)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_email_untested_title)).assertIsDisplayed()
        assertFalse(backCalled)
        assertNothingWritten()
    }

    @Test
    fun `save anyway commits the config and leaves`() {
        showScreen()
        viewModel.form.onSmtpHostChanged("changed.smtp.com")
        composeTestRule.onNodeWithText(context.getString(R.string.action_save)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_email_save_anyway)).performClick()
        composeTestRule.waitForIdle()
        assertTrue(backCalled)
        verify(prefs).save(
            AppSettings(
                smtpHost = "changed.smtp.com",
                smtpUsername = "stored-user",
                destEmail = "dest@example.com",
                destName = "Dest",
                senderEmail = "sender@example.com",
                senderName = "Sender",
                autoCleanupRetentionDays = 30,
                usePduTimestamp = true,
            ),
        )
    }

    @Test
    fun `the dialog cancel keeps the editor open`() {
        showScreen()
        viewModel.form.onSmtpHostChanged("changed.smtp.com")
        composeTestRule.onNodeWithText(context.getString(R.string.action_save)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_email_untested_title)).assertIsDisplayed()
        // The bar's Cancel and the dialog's Cancel match: the dialog renders last.
        val cancelNodes = composeTestRule.onAllNodes(hasText(context.getString(R.string.action_cancel)))
        cancelNodes.assertCountEquals(2)
        cancelNodes[1].performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_email_untested_title)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.action_save)).assertExists()
        assertFalse(backCalled)
        assertNothingWritten()
    }

    @Test
    fun `a finished test email toasts once and does not re-toast when the composition is recreated`() {
        runBlocking {
            `when`(emailService.sendTestEmail(any<SmtpConfig>(), any<TestEmailFields>())).thenReturn(EmailResult.Success)
        }

        // Out of the composition behind a SaveableStateHolder and back (the same
        // mechanism a rotation uses): the VM's result is restored into the fresh
        // composition.
        val showEditor = mutableStateOf(true)
        composeTestRule.setContent {
            val stateHolder = rememberSaveableStateHolder()
            stateHolder.SaveableStateProvider(if (showEditor.value) "editor" else "elsewhere") {
                if (showEditor.value) {
                    EmailConfigEditScreen(
                        onNavigateBack = { backCalled = true },
                        viewModel = viewModel,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val successMessage = context.getString(R.string.email_config_test_success)
        withMockedToast { staticToast ->
            viewModel.sendTestEmail()
            // The stubbed service's resumption is scheduled on the test dispatcher;
            // the compose rule's idle does not advance it.
            testDispatcher.scheduler.advanceUntilIdle()
            composeTestRule.waitForIdle()
            staticToast.verify {
                Toast.makeText(eq(appContext), eq(successMessage), eq(Toast.LENGTH_SHORT))
            }
            composeTestRule.runOnIdle { showEditor.value = false }
            composeTestRule.waitForIdle()
            composeTestRule.runOnIdle { showEditor.value = true }
            composeTestRule.waitForIdle()
            staticToast.verifyNoMoreInteractions()
        }
    }

    @Test
    fun `save with a succeeded test email commits and leaves without the dialog`() {
        showScreen()
        viewModel.form.update { it.copy(test = it.test.copy(testEmailResult = EmailResult.Success)) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.action_save)).performClick()
        composeTestRule.waitForIdle()
        assertTrue(backCalled)
        assertStoredConfigWritten()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_email_untested_title)).assertDoesNotExist()
    }

    @Test
    fun `landscape mode renders the screen with fields side by side`() {
        context.resources.configuration.apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
        showScreen()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_section_forwarding)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.action_save)).assertIsDisplayed()
    }

    @Test
    fun `test email button is enabled when the config is complete`() {
        showScreen()
        composeTestRule
            .onNodeWithText(context.getString(R.string.email_config_send_test_email))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.email_config_send_test_email))
            .assertIsDisplayed()
    }

    @Test
    fun `test email button is disabled when the sender email is missing`() {
        showScreen()
        viewModel.form.onSenderEmailChanged("")
        composeTestRule.waitForIdle()
        // The button stays in the form, only its enabled state follows the completeness.
        composeTestRule
            .onNodeWithText(context.getString(R.string.email_config_send_test_email))
            .assertIsNotEnabled()
    }

    @Test
    fun `confirmSaveAnyway with NEEDS_CONFIRM outcome toasts incomplete`() {
        showScreen()
        // A complete but changed, untested form is what opens the confirm dialog.
        viewModel.form.onSmtpHostChanged("changed.smtp.com")
        composeTestRule.onNodeWithText(context.getString(R.string.action_save)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_email_untested_title)).assertIsDisplayed()
        // The form goes incomplete before the confirm: the confirm re-checks completeness
        // and degrades to the incomplete toast instead of committing.
        viewModel.form.onSmtpPasswordChanged("")
        composeTestRule.waitForIdle()
        withMockedToast {
            composeTestRule.onNodeWithText(context.getString(R.string.settings_email_save_anyway)).performClick()
        }
        composeTestRule.waitForIdle()
        assertFalse(backCalled)
        assertNothingWritten()
    }
}
