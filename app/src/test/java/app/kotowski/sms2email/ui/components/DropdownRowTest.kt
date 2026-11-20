package app.kotowski.sms2email.ui.components

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DropdownRowTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `dropdown renders the value and expands on click`() {
        var expanded by mutableStateOf(false)
        composeTestRule.setContent {
            DropdownRow(
                value = "Option A",
                label = "Pick one",
                items = listOf("Option A" to {}, "Option B" to {}),
                expanded = expanded,
                onExpandedChange = { expanded = it },
            )
        }
        composeTestRule.onNodeWithText("Option A").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Option B").assertIsDisplayed()
    }

    @Test
    fun `picking an item invokes the callback`() {
        var picked = ""
        var expanded by mutableStateOf(false)
        composeTestRule.setContent {
            DropdownRow(
                value = "Option A",
                label = "Pick one",
                items = listOf("Option A" to { picked = "A" }, "Option B" to { picked = "B" }),
                expanded = expanded,
                onExpandedChange = { expanded = it },
            )
        }
        composeTestRule.onNodeWithText("Option A").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Option B").performClick()
        composeTestRule.waitForIdle()
        assertEquals("B", picked)
    }

    @Test
    fun `subtitle renders when provided`() {
        composeTestRule.setContent {
            DropdownRow(
                value = "Option A",
                label = "Pick one",
                items = listOf("Option A" to {}),
                expanded = false,
                onExpandedChange = {},
                subtitle = "Choose an option",
            )
        }
        composeTestRule.onNodeWithText("Choose an option").assertIsDisplayed()
    }

    @Test
    fun `subtitle is absent when null`() {
        composeTestRule.setContent {
            DropdownRow(
                value = "Option A",
                label = "Pick one",
                items = listOf("Option A" to {}),
                expanded = false,
                onExpandedChange = {},
                subtitle = null,
            )
        }
        composeTestRule.onNodeWithText("Choose an option").assertDoesNotExist()
    }

    @Test
    fun `leadingIcon renders when provided`() {
        var expanded by mutableStateOf(false)
        composeTestRule.setContent {
            DropdownRow(
                value = "Option A",
                label = "Pick one",
                items = listOf("Option A" to {}),
                expanded = expanded,
                onExpandedChange = { expanded = it },
                leadingIcon = {
                    androidx.compose.material3.Icon(
                        Icons.Default.Info,
                        contentDescription = "Info",
                    )
                },
            )
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `testTag is applied when provided`() {
        var expanded by mutableStateOf(false)
        composeTestRule.setContent {
            DropdownRow(
                value = "Option A",
                label = "Pick one",
                items = listOf("Option A" to {}),
                expanded = expanded,
                onExpandedChange = { expanded = it },
                testTag = "my_dropdown",
            )
        }
        composeTestRule.waitForIdle()
    }
}
