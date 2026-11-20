package app.kotowski.sms2email.ui.components

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.kotowski.sms2email.R
import app.kotowski.sms2email.testing.SkipInReleaseRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CenteredMessageTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `centered message renders the message text`() {
        composeTestRule.setContent {
            CenteredMessage(
                messageResId = R.string.action_back,
            )
        }
        composeTestRule.onNodeWithText(context.getString(R.string.action_back)).assertIsDisplayed()
    }

    @Test
    fun `centered message with content renders both message and content`() {
        composeTestRule.setContent {
            CenteredMessage(
                messageResId = R.string.action_back,
            ) {
                Text(text = "Extra content")
            }
        }
        composeTestRule.onNodeWithText(context.getString(R.string.action_back)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Extra content").assertIsDisplayed()
    }

    @Test
    fun `centered message without content renders only the message`() {
        composeTestRule.setContent {
            CenteredMessage(
                messageResId = R.string.action_back,
            )
        }
        composeTestRule.onNodeWithText(context.getString(R.string.action_back)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Extra content").assertDoesNotExist()
    }
}
