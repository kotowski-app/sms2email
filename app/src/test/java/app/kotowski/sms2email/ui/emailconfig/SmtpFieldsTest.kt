package app.kotowski.sms2email.ui.emailconfig

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import app.kotowski.sms2email.data.prefs.SmtpProtocol
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
class SmtpFieldsTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    // The newer Compose BOM dropped the isPasswordField() matcher and keeps the
    // Password semantics key in both states; the observable difference is the
    // rendered text: bullets while masked, the real value when revealed. (The
    // autofill InputText semantics keeps the real value in both states by design,
    // so plain text-presence matching is no longer a reliable mask signal.)
    private val plaintextPassword =
        SemanticsMatcher("password shown in plaintext") {
            it.config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text == "secret"
        }

    @Composable
    private fun SmtpFieldsFixture(isLandscape: Boolean = false) {
        SmtpFields(
            smtpHost = "smtp.example.com",
            portDraft = "587",
            smtpProtocol = SmtpProtocol.START_TLS,
            smtpUsername = "user",
            smtpPassword = "secret",
            onSmtpHostChanged = {},
            onPortDraftChange = {},
            onSmtpProtocolChanged = {},
            onSmtpUsernameChanged = {},
            onSmtpPasswordChanged = {},
            isLandscape = isLandscape,
        )
    }

    @Test
    fun `the toggle reveals the password in plaintext and hides it back`() {
        composeTestRule.setContent { SmtpFieldsFixture() }
        composeTestRule.onNode(plaintextPassword).assertDoesNotExist()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.email_config_show_password))
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(plaintextPassword).assertExists()
        composeTestRule
            .onNodeWithText("secret", useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.email_config_hide_password))
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(plaintextPassword).assertDoesNotExist()
    }

    @Test
    fun `a revealed password stays revealed when the composition leaves and re-enters`() {
        // Leaving and re-entering behind a SaveableStateHolder (the same mechanism a
        // rotation uses): the visibility state must be restored, not re-masked.
        val showFields = mutableStateOf(true)
        composeTestRule.setContent {
            val stateHolder = rememberSaveableStateHolder()
            stateHolder.SaveableStateProvider(if (showFields.value) "fields" else "elsewhere") {
                if (showFields.value) {
                    SmtpFieldsFixture()
                } else {
                    Text("elsewhere")
                }
            }
        }
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.email_config_show_password))
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("secret", useUnmergedTree = true).assertExists()

        composeTestRule.runOnIdle { showFields.value = false }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { showFields.value = true }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("secret", useUnmergedTree = true).assertIsDisplayed()
    }
}
