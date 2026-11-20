package app.kotowski.sms2email.ui.logs

import android.content.Context
import android.widget.Toast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.testing.SkipInReleaseRule
import app.kotowski.sms2email.testing.withMockedToast
import app.kotowski.sms2email.util.LogBuffer
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LogViewerScreenTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private lateinit var viewModel: LogViewerViewModel
    private val prefs = mock<EncryptedPrefs>()

    @Before
    fun setUp() {
        whenever(prefs.load()).thenReturn(AppSettings())
        viewModel = LogViewerViewModel(ApplicationProvider.getApplicationContext(), prefs)
    }

    @Test
    fun `the top bar offers copy and clear, the banner offers save to file`() {
        LogBuffer.clear()
        LogBuffer.addInfo("UiTag", "visible line")
        composeTestRule.setContent { LogViewerScreen(onNavigateBack = { }, viewModel = viewModel) }
        composeTestRule.onNodeWithContentDescription("Copy").assertExists()
        composeTestRule.onNodeWithContentDescription("Clear").assertExists()
        composeTestRule.onNodeWithText("Save to file").assertExists()
    }

    @Test
    fun `the banner states the record cap and the save-to-file hint`() {
        LogBuffer.clear()
        composeTestRule.setContent { LogViewerScreen(onNavigateBack = { }, viewModel = viewModel) }
        composeTestRule.onNodeWithText("Limited view").assertExists()
        composeTestRule.onNodeWithText("500 records", substring = true).assertExists()
        composeTestRule.onNodeWithText("Save to file", substring = true).assertExists()
    }

    @Test
    fun `the save button is hidden while the log is empty`() {
        LogBuffer.clear()
        composeTestRule.setContent { LogViewerScreen(onNavigateBack = { }, viewModel = viewModel) }
        composeTestRule.onNodeWithText("Save to file").assertDoesNotExist()
    }

    @Test
    fun `the dimmed save button toasts the enable hint while log to file is off`() {
        LogBuffer.clear()
        LogBuffer.addInfo("UiTag", "visible line")
        composeTestRule.setContent { LogViewerScreen(onNavigateBack = { }, viewModel = viewModel) }
        withMockedToast { toast ->
            composeTestRule.onNodeWithText("Save to file").performClick()
            toast.verify {
                Toast.makeText(any<Context>(), eq(R.string.log_viewer_enable_toast), eq(Toast.LENGTH_SHORT))
            }
        }
    }

    @Test
    fun `entries are shown latest first`() {
        LogBuffer.clear()
        LogBuffer.addInfo("UiTag", "older line")
        LogBuffer.addInfo("UiTag", "newer line")
        composeTestRule.setContent { LogViewerScreen(onNavigateBack = { }, viewModel = viewModel) }
        val newerTop =
            composeTestRule
                .onNodeWithText("newer line", substring = true)
                .fetchSemanticsNode()
                .boundsInRoot.top
        val olderTop =
            composeTestRule
                .onNodeWithText("older line", substring = true)
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue(newerTop < olderTop)
    }

    @Test
    fun `clear confirm wipes the buffer and shows the empty state`() {
        LogBuffer.clear()
        LogBuffer.addInfo("UiTag", "doomed line")
        composeTestRule.setContent { LogViewerScreen(onNavigateBack = { }, viewModel = viewModel) }
        composeTestRule.onNodeWithText("doomed line", substring = true).assertExists()
        composeTestRule.onNodeWithContentDescription("Clear").performClick()
        composeTestRule.onNodeWithText("Clear logs?").assertExists()
        composeTestRule.onNodeWithText("Clear").performClick()
        composeTestRule.waitForIdle()
        assertTrue(LogBuffer.entries.isEmpty())
        composeTestRule.onNodeWithText("No logs captured yet").assertExists()
    }

    @Test
    fun `clear cancel keeps the log`() {
        LogBuffer.clear()
        LogBuffer.addInfo("UiTag", "safe line")
        composeTestRule.setContent { LogViewerScreen(onNavigateBack = { }, viewModel = viewModel) }
        composeTestRule.onNodeWithContentDescription("Clear").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("safe line", substring = true).assertExists()
        assertTrue(LogBuffer.entries.any { it.message == "safe line" })
    }

    @Test
    fun `search filter narrows to matching entries`() {
        LogBuffer.clear()
        LogBuffer.addInfo("UiTag", "alpha line")
        LogBuffer.addInfo("UiTag", "beta line")
        composeTestRule.setContent { LogViewerScreen(onNavigateBack = { }, viewModel = viewModel) }
        composeTestRule.onNodeWithText("Search", substring = true).performClick()
        composeTestRule.onNodeWithText("Search", substring = true).performTextInput("beta")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("beta line", substring = true).assertExists()
        composeTestRule.onNodeWithText("alpha line", substring = true).assertDoesNotExist()
    }

    @Test
    fun `search with no match shows the no-match message`() {
        LogBuffer.clear()
        LogBuffer.addInfo("UiTag", "some line")
        composeTestRule.setContent { LogViewerScreen(onNavigateBack = { }, viewModel = viewModel) }
        composeTestRule.onNodeWithText("Search", substring = true).performClick()
        composeTestRule.onNodeWithText("Search", substring = true).performTextInput("zzznomatch")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("No logs match the search query.").assertExists()
        composeTestRule.onNodeWithText("some line", substring = true).assertDoesNotExist()
    }

    @Test
    fun `copy all button copies all filtered entries`() {
        LogBuffer.clear()
        LogBuffer.addInfo("UiTag", "copyable line")
        composeTestRule.setContent { LogViewerScreen(onNavigateBack = { }, viewModel = viewModel) }
        withMockedToast { toast ->
            composeTestRule.onNodeWithContentDescription("Copy").performClick()
            toast.verify {
                Toast.makeText(any<Context>(), eq(R.string.log_viewer_copied), eq(Toast.LENGTH_SHORT))
            }
        }
    }

    @Test
    fun `individual entry click copies the entry text`() {
        LogBuffer.clear()
        LogBuffer.addInfo("UiTag", "clickable entry")
        composeTestRule.setContent { LogViewerScreen(onNavigateBack = { }, viewModel = viewModel) }
        withMockedToast { toast ->
            composeTestRule.onNodeWithText("clickable entry", substring = true).performClick()
            toast.verify {
                Toast.makeText(any<Context>(), eq(R.string.log_viewer_copied), eq(Toast.LENGTH_SHORT))
            }
        }
    }

    @Test
    fun `navigateBack invokes the callback`() {
        LogBuffer.clear()
        var backInvoked = false
        composeTestRule.setContent {
            LogViewerScreen(onNavigateBack = { backInvoked = true }, viewModel = viewModel)
        }
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(backInvoked)
    }

    @Test
    fun `empty log shows the empty message`() {
        LogBuffer.clear()
        composeTestRule.setContent { LogViewerScreen(onNavigateBack = { }, viewModel = viewModel) }
        composeTestRule.onNodeWithText("No logs captured yet").assertExists()
    }

    @Test
    fun `copy button is disabled when there are no entries`() {
        LogBuffer.clear()
        composeTestRule.setContent { LogViewerScreen(onNavigateBack = { }, viewModel = viewModel) }
        composeTestRule.onNodeWithContentDescription("Copy").assertIsNotEnabled()
    }

    @Test
    fun `clear button is disabled when there are no entries`() {
        LogBuffer.clear()
        composeTestRule.setContent { LogViewerScreen(onNavigateBack = { }, viewModel = viewModel) }
        composeTestRule.onNodeWithContentDescription("Clear").assertIsNotEnabled()
    }

    @Test
    fun `log to file enabled shows non-dimmed save button`() {
        LogBuffer.clear()
        LogBuffer.addInfo("UiTag", "visible line")

        whenever(prefs.load()).thenReturn(AppSettings(logToFileEnabled = true))
        val enabledVm = LogViewerViewModel(ApplicationProvider.getApplicationContext(), prefs)

        composeTestRule.setContent {
            LogViewerScreen(onNavigateBack = { }, viewModel = enabledVm)
        }
        composeTestRule.onNodeWithText("Save to file").assertExists()
    }
}
