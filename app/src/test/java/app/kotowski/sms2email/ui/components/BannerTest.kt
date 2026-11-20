package app.kotowski.sms2email.ui.components

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
class BannerTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `banner renders icon and title`() {
        composeTestRule.setContent {
            Banner(
                icon = Icons.Default.Info,
                title = "Notice",
            )
        }
        composeTestRule.onNodeWithText("Notice").assertIsDisplayed()
    }

    @Test
    fun `banner with description renders the description`() {
        composeTestRule.setContent {
            Banner(
                icon = Icons.Default.Info,
                title = "Notice",
                description = "Additional info",
            )
        }
        composeTestRule.onNodeWithText("Notice").assertIsDisplayed()
        composeTestRule.onNodeWithText("Additional info").assertIsDisplayed()
    }

    @Test
    fun `banner with button renders the button and invokes callback`() {
        var clicked = false
        composeTestRule.setContent {
            Banner(
                icon = Icons.Default.Info,
                title = "Notice",
                buttonText = "Action",
                onButtonClick = { clicked = true },
            )
        }
        composeTestRule.onNodeWithText("Action").performClick()
        assertTrue(clicked)
    }

    @Test
    fun `banner without button text or handler renders no button`() {
        composeTestRule.setContent {
            Banner(
                icon = Icons.Default.Info,
                title = "Notice",
            )
        }
        composeTestRule.onNodeWithText("Action").assertDoesNotExist()
    }

    @Test
    fun `banner with button text but no handler renders no button`() {
        composeTestRule.setContent {
            Banner(
                icon = Icons.Default.Info,
                title = "Notice",
                buttonText = "Action",
            )
        }
        composeTestRule.onNodeWithText("Action").assertDoesNotExist()
    }

    @Test
    fun `banner with dimmed button renders the button`() {
        composeTestRule.setContent {
            Banner(
                icon = Icons.Default.Info,
                title = "Notice",
                buttonText = "Action",
                onButtonClick = {},
                buttonDimmed = true,
            )
        }
        composeTestRule.onNodeWithText("Action").assertIsDisplayed()
    }

    @Test
    fun `banner with button test tag applies the tag`() {
        composeTestRule.setContent {
            Banner(
                icon = Icons.Default.Info,
                title = "Notice",
                buttonText = "Action",
                onButtonClick = {},
                buttonTestTag = "action_button",
            )
        }
        composeTestRule.onNodeWithText("Action").assertIsDisplayed()
    }

    @Test
    fun `banner with handler but no button text renders no button`() {
        composeTestRule.setContent {
            Banner(
                icon = Icons.Default.Info,
                title = "Notice",
                onButtonClick = {},
            )
        }
        composeTestRule.onNodeWithText("Action").assertDoesNotExist()
    }
}
