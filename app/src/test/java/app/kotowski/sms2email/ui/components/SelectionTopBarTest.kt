package app.kotowski.sms2email.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.kotowski.sms2email.R
import app.kotowski.sms2email.testing.SkipInReleaseRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionTopBarTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun render(
        selectedCount: Int,
        onCancel: () -> Unit = { },
        onFirstAction: () -> Unit = { },
        onSecondAction: () -> Unit = { },
    ) {
        composeTestRule.setContent {
            SelectionTopBar(
                selectedCount = selectedCount,
                onCancel = onCancel,
                onFirstAction = onFirstAction,
                onSecondAction = onSecondAction,
                firstResId = R.string.action_send,
                secondResId = R.string.queue_cancel_selected,
                secondIcon = Icons.Default.Delete,
                countResId = R.string.selection_count,
            )
        }
    }

    @Test
    fun `actions are enabled when items are selected`() {
        render(selectedCount = 3)
        composeTestRule
            .onNodeWithContentDescription(ctx.getString(R.string.action_send))
            .assertExists()
    }

    @Test
    fun `actions are disabled when nothing is selected`() {
        var firstCalled = false
        var secondCalled = false
        render(
            selectedCount = 0,
            onFirstAction = { firstCalled = true },
            onSecondAction = { secondCalled = true },
        )
        // With zero selected the buttons are disabled but still composables on screen.
        composeTestRule
            .onNodeWithContentDescription(ctx.getString(R.string.action_send))
            .assertExists()
        // Clicking disabled buttons does not fire callbacks.
        composeTestRule
            .onNodeWithContentDescription(ctx.getString(R.string.action_send))
            .performClick()
        composeTestRule.waitForIdle()
        assertTrue(!firstCalled)
        assertTrue(!secondCalled)
    }

    @Test
    fun `cancel button fires the cancel callback`() {
        var cancelled = false
        render(selectedCount = 1, onCancel = { cancelled = true })
        composeTestRule.onNodeWithText(ctx.getString(R.string.action_cancel)).performClick()
        composeTestRule.waitForIdle()
        assertTrue(cancelled)
    }
}
