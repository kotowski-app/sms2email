package app.kotowski.sms2email.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
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
class AutoValueFieldTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    @Composable
    private fun AutoValueFieldFixture(
        checked: Boolean,
        value: String,
        onCheckedChange: (Boolean) -> Unit = {},
        onValueChange: (String) -> Unit = {},
        onCommit: (String) -> Unit = {},
        resetKey: Int = 0,
        errorText: String? = null,
    ) {
        AutoValueField(
            isLandscape = false,
            title = "Auto prefix",
            description = "Use the device name",
            checked = checked,
            onCheckedChange = onCheckedChange,
            value = value,
            onValueChange = onValueChange,
            onCommit = onCommit,
            label = "Custom prefix",
            resetKey = resetKey,
            errorText = errorText,
        )
    }

    // The element emits two siblings (the switch row, the field); the screens
    // compose it inside a column, the test does the same.
    @Composable
    private fun InColumn(content: @Composable ColumnScope.() -> Unit) {
        Column(content = content)
    }

    @Test
    fun `the switch reports the toggle`() {
        var checked = false
        composeTestRule.setContent {
            InColumn {
                AutoValueFieldFixture(
                    checked = checked,
                    value = "Device",
                    onCheckedChange = { checked = it },
                )
            }
        }
        composeTestRule.onNode(isToggleable()).performClick()
        assertTrue(checked)
    }

    @Test
    fun `the field is locked while auto is on`() {
        composeTestRule.setContent {
            InColumn { AutoValueFieldFixture(checked = true, value = "Device") }
        }
        // The newer Compose BOM drops the SetText action from disabled fields, so the
        // lock is asserted through the field's own enabled state instead.
        composeTestRule.onNodeWithText("Device").assertIsNotEnabled()
    }

    @Test
    fun `the field is editable in the manual mode`() {
        composeTestRule.setContent {
            InColumn { AutoValueFieldFixture(checked = false, value = "Device") }
        }
        composeTestRule.onNode(hasSetTextAction()).assertIsEnabled()
    }

    @Test
    fun `typing reports every change and Done commits the draft`() {
        val changes = mutableListOf<String>()
        val commits = mutableListOf<String>()
        composeTestRule.setContent {
            InColumn {
                AutoValueFieldFixture(
                    checked = false,
                    value = "abc",
                    onValueChange = { changes += it },
                    onCommit = { commits += it },
                )
            }
        }
        composeTestRule
            .onNode(hasSetTextAction())
            .performTextReplacement("abc def")
        composeTestRule.waitForIdle()
        assertEquals(listOf("abc def"), changes)
        composeTestRule
            .onNode(hasSetTextAction())
            .performImeAction()
        composeTestRule.waitForIdle()
        assertEquals(listOf("abc def"), commits)
    }

    @Test
    fun `a resetKey change re-syncs the draft from the value`() {
        val value = mutableStateOf("initial")
        val resetKey = mutableStateOf(0)
        composeTestRule.setContent {
            InColumn {
                AutoValueFieldFixture(
                    checked = false,
                    value = value.value,
                    resetKey = resetKey.value,
                )
            }
        }
        composeTestRule
            .onNode(hasSetTextAction())
            .performTextReplacement("changed")
        composeTestRule.waitForIdle()
        // The external refresh: a new generation and the imported value.
        composeTestRule.runOnIdle {
            value.value = "imported"
            resetKey.value = 1
        }
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText("imported", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun `the error text renders under the field when set`() {
        composeTestRule.setContent {
            InColumn {
                AutoValueFieldFixture(
                    checked = false,
                    value = "Device",
                    errorText = "Invalid prefix",
                )
            }
        }
        composeTestRule
            .onNodeWithText("Invalid prefix", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun `no error text renders by default`() {
        composeTestRule.setContent {
            InColumn { AutoValueFieldFixture(checked = false, value = "Device") }
        }
        composeTestRule
            .onNodeWithText("Invalid prefix", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `landscape mode renders row layout with switch and field`() {
        var checked = false
        composeTestRule.setContent {
            AutoValueField(
                isLandscape = true,
                title = "Auto prefix",
                description = "Use the device name",
                checked = checked,
                onCheckedChange = { checked = it },
                value = "Device",
                onValueChange = {},
                onCommit = {},
                label = "Custom prefix",
            )
        }
        composeTestRule.onNodeWithText("Auto prefix").assertIsDisplayed()
        composeTestRule.onNodeWithText("Use the device name").assertIsDisplayed()
        composeTestRule.onNodeWithText("Device").assertIsDisplayed()
    }

    @Test
    fun `landscape mode toggle updates the checked state`() {
        var checked = false
        composeTestRule.setContent {
            AutoValueField(
                isLandscape = true,
                title = "Auto prefix",
                description = "Use the device name",
                checked = checked,
                onCheckedChange = { checked = it },
                value = "Device",
                onValueChange = {},
                onCommit = {},
                label = "Custom prefix",
            )
        }
        composeTestRule.onNode(isToggleable()).performClick()
        assertTrue(checked)
    }

    @Test
    fun `auto mode shows read-only value in the field`() {
        composeTestRule.setContent {
            AutoValueField(
                isLandscape = false,
                title = "Auto prefix",
                description = "Use the device name",
                checked = true,
                onCheckedChange = {},
                value = "AutoValue",
                onValueChange = {},
                onCommit = {},
                label = "Custom prefix",
            )
        }
        composeTestRule.onNodeWithText("AutoValue").assertExists()
    }
}
