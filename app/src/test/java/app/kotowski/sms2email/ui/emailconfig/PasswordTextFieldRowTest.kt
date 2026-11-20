package app.kotowski.sms2email.ui.emailconfig

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PasswordTextFieldRowTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    // The observable effect of the toggle: the field's EditableText equals the real
    // value only when revealed (masked it is bullet chars).
    private val plaintextPassword =
        SemanticsMatcher("password shown in plaintext") {
            it.config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text == "secret"
        }

    @Test
    fun `password field renders with hide icon when password is hidden`() {
        var showPassword = false
        composeTestRule.setContent {
            PasswordTextFieldRow(
                label = "Password",
                value = "secret",
                onValueChange = {},
                showPassword = showPassword,
                onTogglePassword = { showPassword = !showPassword },
            )
        }
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.email_config_show_password))
            .assertIsDisplayed()
    }

    @Test
    fun `password field renders with show icon when password is visible`() {
        var showPassword = true
        composeTestRule.setContent {
            PasswordTextFieldRow(
                label = "Password",
                value = "secret",
                onValueChange = {},
                showPassword = showPassword,
                onTogglePassword = { showPassword = !showPassword },
            )
        }
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.email_config_hide_password))
            .assertIsDisplayed()
    }

    @Test
    fun `toggling password visibility switches the icon`() {
        composeTestRule.setContent {
            // remember: a bare mutableStateOf would recreate the state on every
            // recomposition and the toggle would be lost.
            var showPassword by remember { mutableStateOf(false) }
            PasswordTextFieldRow(
                label = "Password",
                value = "secret",
                onValueChange = {},
                showPassword = showPassword,
                onTogglePassword = { showPassword = !showPassword },
            )
        }
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.email_config_show_password))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.email_config_show_password))
            .performClick()
        composeTestRule.waitForIdle()
        // The icon's display is covered by the sibling render tests; here the switch is
        // proven by existence and the effect by the plaintextPassword state.
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.email_config_hide_password))
            .assertExists()
        composeTestRule.onNode(plaintextPassword).assertExists()
    }

    @Test
    fun `password field renders the label`() {
        composeTestRule.setContent {
            PasswordTextFieldRow(
                label = "SMTP Password",
                value = "",
                onValueChange = {},
                showPassword = false,
                onTogglePassword = {},
            )
        }
        composeTestRule.onNodeWithText("SMTP Password").assertExists()
    }
}
