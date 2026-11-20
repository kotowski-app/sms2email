package app.kotowski.sms2email.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class SectionToggleTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    @Test
    fun `portrait mode renders section header and toggle row`() {
        var checked = false
        composeTestRule.setContent {
            SectionToggle(
                isLandscape = false,
                sectionTitle = "Contacts",
                title = "Resolve contacts",
                description = "Match sender to a name",
                checked = checked,
                onCheckedChange = { checked = it },
            )
        }
        composeTestRule.onNodeWithText("Contacts").assertIsDisplayed()
        composeTestRule.onNodeWithText("Resolve contacts").assertIsDisplayed()
        composeTestRule.onNodeWithText("Match sender to a name").assertIsDisplayed()
        composeTestRule.onNode(isToggleable()).performClick()
        assertTrue(checked)
    }

    @Test
    fun `landscape mode renders section title, toggle title, description, and switch`() {
        var checked = false
        composeTestRule.setContent {
            SectionToggle(
                isLandscape = true,
                sectionTitle = "Contacts",
                title = "Resolve contacts",
                description = "Match sender to a name",
                checked = checked,
                onCheckedChange = { checked = it },
            )
        }
        composeTestRule.onNodeWithText("Contacts").assertIsDisplayed()
        composeTestRule.onNodeWithText("Resolve contacts").assertIsDisplayed()
        composeTestRule.onNodeWithText("Match sender to a name").assertIsDisplayed()
        composeTestRule.onNode(isToggleable()).performClick()
        assertTrue(checked)
    }
}
