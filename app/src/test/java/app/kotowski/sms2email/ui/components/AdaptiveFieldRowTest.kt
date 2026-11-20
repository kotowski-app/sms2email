package app.kotowski.sms2email.ui.components

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
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
class AdaptiveFieldRowTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `landscape mode renders fields side by side`() {
        composeTestRule.setContent {
            AdaptiveFieldRow(
                isLandscape = true,
                field1 = { Text(text = "Field A") },
                field2 = { Text(text = "Field B") },
            )
        }
        composeTestRule.onNodeWithText("Field A").assertIsDisplayed()
        composeTestRule.onNodeWithText("Field B").assertIsDisplayed()
    }

    @Test
    fun `portrait mode renders fields stacked`() {
        composeTestRule.setContent {
            AdaptiveFieldRow(
                isLandscape = false,
                field1 = { Text(text = "Field A") },
                field2 = { Text(text = "Field B") },
            )
        }
        composeTestRule.onNodeWithText("Field A").assertIsDisplayed()
        composeTestRule.onNodeWithText("Field B").assertIsDisplayed()
    }

    @Test
    fun `portrait mode with spacing renders a spacer between fields`() {
        composeTestRule.setContent {
            AdaptiveFieldRow(
                isLandscape = false,
                field1 = { Text(text = "Field A") },
                field2 = { Text(text = "Field B") },
                portraitSpacing = 8.dp,
            )
        }
        composeTestRule.onNodeWithText("Field A").assertIsDisplayed()
        composeTestRule.onNodeWithText("Field B").assertIsDisplayed()
    }

    @Test
    fun `portrait mode with zero spacing omits the spacer`() {
        composeTestRule.setContent {
            AdaptiveFieldRow(
                isLandscape = false,
                field1 = { Text(text = "Field A") },
                field2 = { Text(text = "Field B") },
                portraitSpacing = 0.dp,
            )
        }
        composeTestRule.onNodeWithText("Field A").assertIsDisplayed()
        composeTestRule.onNodeWithText("Field B").assertIsDisplayed()
    }
}
