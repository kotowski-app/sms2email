package app.kotowski.sms2email.ui.components

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
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
class HeaderCardTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `card with title only renders the title and content`() {
        composeTestRule.setContent {
            HeaderCard(
                title = "Try it",
            ) {
                Text(text = "Body text")
            }
        }
        composeTestRule.onNodeWithText("Try it").assertIsDisplayed()
        composeTestRule.onNodeWithText("Body text").assertIsDisplayed()
    }

    @Test
    fun `card with onClose only renders the close button and content`() {
        var closed = false
        composeTestRule.setContent {
            HeaderCard(
                onClose = { closed = true },
                closeLabel = "Remove rule",
            ) {
                Text(text = "Body text")
            }
        }
        composeTestRule.onNodeWithContentDescription("Remove rule").performClick()
        assertTrue(closed)
        composeTestRule.onNodeWithText("Body text").assertIsDisplayed()
    }

    @Test
    fun `card with both title and onClose renders all elements`() {
        var closed = false
        composeTestRule.setContent {
            HeaderCard(
                title = "Rule card",
                onClose = { closed = true },
                closeLabel = "Remove",
            ) {
                Text(text = "Content")
            }
        }
        composeTestRule.onNodeWithText("Rule card").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Remove").performClick()
        assertTrue(closed)
        composeTestRule.onNodeWithText("Content").assertIsDisplayed()
    }

    @Test
    fun `card with neither title nor onClose renders content only`() {
        composeTestRule.setContent {
            HeaderCard {
                Text(text = "Content only")
            }
        }
        composeTestRule.onNodeWithText("Content only").assertIsDisplayed()
    }
}
