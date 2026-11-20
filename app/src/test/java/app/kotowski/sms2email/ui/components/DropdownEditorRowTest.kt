package app.kotowski.sms2email.ui.components

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DropdownEditorRowTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `locked state shows the locked value and edit button`() {
        var editStarted = false
        composeTestRule.setContent {
            DropdownEditorRow(
                editing = false,
                dropdownValue = "365 days",
                dropdownLabel = "Period",
                items = listOf("30 days" to {}, "365 days" to {}),
                expanded = false,
                onExpandedChange = {},
                lockedValue = "Period: 365 days",
                onSave = {},
                onCancel = {},
                onEditStarted = { editStarted = true },
            )
        }
        composeTestRule.onNodeWithText("Period: 365 days").assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.action_edit)).performClick()
        assertTrue(editStarted)
    }

    @Test
    fun `editing state shows the dropdown and the cancel save buttons`() {
        var cancelled = false
        var saved = false
        composeTestRule.setContent {
            DropdownEditorRow(
                editing = true,
                dropdownValue = "365 days",
                dropdownLabel = "Period",
                items = listOf("30 days" to {}, "365 days" to {}),
                expanded = false,
                onExpandedChange = {},
                lockedValue = "Period: 365 days",
                onSave = { saved = true },
                onCancel = { cancelled = true },
                onEditStarted = {},
            )
        }
        composeTestRule.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        assertTrue(cancelled)
        composeTestRule.onNodeWithText(context.getString(R.string.action_save)).performClick()
        assertTrue(saved)
    }

    @Test
    fun `editing state dropdown expands and picks an item`() {
        var picked = ""
        var expanded by mutableStateOf(false)
        composeTestRule.setContent {
            DropdownEditorRow(
                editing = true,
                dropdownValue = "365 days",
                dropdownLabel = "Period",
                items = listOf("30 days" to { picked = "30" }, "365 days" to { picked = "365" }),
                expanded = expanded,
                onExpandedChange = { expanded = it },
                lockedValue = "Period: 365 days",
                onSave = {},
                onCancel = {},
                onEditStarted = {},
            )
        }
        composeTestRule.onNodeWithText("365 days").performClick()
        composeTestRule.waitForIdle()
        // "30 days" only exists as a menu item, so it is an unambiguous pick target.
        composeTestRule.onNodeWithText("30 days").performClick()
        composeTestRule.waitForIdle()
        assertEquals("30", picked)
    }

    @Test
    fun `hint renders when provided`() {
        composeTestRule.setContent {
            DropdownEditorRow(
                editing = true,
                dropdownValue = "365 days",
                dropdownLabel = "Period",
                items = listOf("365 days" to {}),
                expanded = false,
                onExpandedChange = {},
                lockedValue = "Period: 365 days",
                onSave = {},
                onCancel = {},
                onEditStarted = {},
                hint = "Pick a period and tap Save",
            )
        }
        composeTestRule.onNodeWithText("Pick a period and tap Save").assertIsDisplayed()
    }

    @Test
    fun `hint is absent when null`() {
        composeTestRule.setContent {
            DropdownEditorRow(
                editing = true,
                dropdownValue = "365 days",
                dropdownLabel = "Period",
                items = listOf("365 days" to {}),
                expanded = false,
                onExpandedChange = {},
                lockedValue = "Period: 365 days",
                onSave = {},
                onCancel = {},
                onEditStarted = {},
                hint = null,
            )
        }
        composeTestRule.onNodeWithText("Pick a period and tap Save").assertDoesNotExist()
    }
}
