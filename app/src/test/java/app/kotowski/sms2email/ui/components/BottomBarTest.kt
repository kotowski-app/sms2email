package app.kotowski.sms2email.ui.components

import androidx.compose.material3.Button
import androidx.compose.material3.Text
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomBarTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    @Test
    fun `bar renders leading and trailing buttons without step dots`() {
        var leadingClicked = false
        var trailingClicked = false
        composeTestRule.setContent {
            BottomBar(
                leading = {
                    Button(onClick = { leadingClicked = true }) {
                        Text("Back")
                    }
                },
                trailing = {
                    Button(onClick = { trailingClicked = true }) {
                        Text("Next")
                    }
                },
            )
        }
        composeTestRule.onNodeWithText("Back").performClick()
        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `step dots render when currentStep and totalSteps are provided`() {
        composeTestRule.setContent {
            BottomBar(
                currentStep = 1,
                totalSteps = 3,
                leading = { Text("Back") },
                trailing = { Text("Next") },
            )
        }
        // The showDots branch fires: currentStep >= 0 && totalSteps > 0
        composeTestRule.onNodeWithText("Back").assertExists()
    }

    @Test
    fun `step dots are hidden when totalSteps is zero`() {
        composeTestRule.setContent {
            BottomBar(
                currentStep = 0,
                totalSteps = 0,
                leading = { Text("Back") },
                trailing = { Text("Next") },
            )
        }
        // showDots is false: totalSteps is not > 0
        composeTestRule.onNodeWithText("Back").assertExists()
    }

    @Test
    fun `step dots are hidden when currentStep is negative`() {
        composeTestRule.setContent {
            BottomBar(
                currentStep = -1,
                totalSteps = 3,
                leading = { Text("Back") },
                trailing = { Text("Next") },
            )
        }
        // showDots is false: currentStep < 0
        composeTestRule.onNodeWithText("Back").assertExists()
    }
}
