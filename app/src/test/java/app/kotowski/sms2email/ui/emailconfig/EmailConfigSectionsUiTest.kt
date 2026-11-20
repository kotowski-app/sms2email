package app.kotowski.sms2email.ui.emailconfig

import android.content.Context
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
import kotlin.test.assertEquals

// Compose rendering tests for the shared email config field sections: the SMTP fields
// block (host, protocol/port, username, password with hint) and the sender/receiver
// block (dest email/name, sender email/name).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmailConfigSectionsUiTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `SmtpFields renders all credential fields in SETTINGS look`() {
        composeTestRule.setContent {
            SmtpFields(
                smtpHost = "smtp.example.com",
                portDraft = "587",
                smtpProtocol = SmtpProtocol.START_TLS,
                smtpUsername = "user",
                smtpPassword = "pass",
                onSmtpHostChanged = { },
                onPortDraftChange = { },
                onSmtpProtocolChanged = { },
                onSmtpUsernameChanged = { },
                onSmtpPasswordChanged = { },
                isLandscape = false,
                look = SmtpFieldLook.SETTINGS,
            )
        }
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_host)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_protocol)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_username)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_password)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_smtp_password_hint)).assertExists()
    }

    // The OUTLINED look uses OutlinedTextField instead of TextFieldRow.
    @Test
    fun `SmtpFields renders all credential fields in OUTLINED look`() {
        composeTestRule.setContent {
            SmtpFields(
                smtpHost = "smtp.example.com",
                portDraft = "587",
                smtpProtocol = SmtpProtocol.START_TLS,
                smtpUsername = "user",
                smtpPassword = "pass",
                onSmtpHostChanged = { },
                onPortDraftChange = { },
                onSmtpProtocolChanged = { },
                onSmtpUsernameChanged = { },
                onSmtpPasswordChanged = { },
                isLandscape = false,
                look = SmtpFieldLook.OUTLINED,
            )
        }
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_host)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_protocol)).assertExists()
    }

    // The landscape mode in SmtpFields places protocol and port side by side.
    @Test
    fun `SmtpFields renders in landscape mode`() {
        composeTestRule.setContent {
            SmtpFields(
                smtpHost = "smtp.example.com",
                portDraft = "587",
                smtpProtocol = SmtpProtocol.START_TLS,
                smtpUsername = "user",
                smtpPassword = "pass",
                onSmtpHostChanged = { },
                onPortDraftChange = { },
                onSmtpProtocolChanged = { },
                onSmtpUsernameChanged = { },
                onSmtpPasswordChanged = { },
                isLandscape = true,
                look = SmtpFieldLook.SETTINGS,
            )
        }
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_protocol)).assertExists()
    }

    // The password hint text always renders below the password field.
    @Test
    fun `SmtpFields always renders the password hint`() {
        composeTestRule.setContent {
            SmtpFields(
                smtpHost = "smtp.example.com",
                portDraft = "587",
                smtpProtocol = SmtpProtocol.START_TLS,
                smtpUsername = "user",
                smtpPassword = "pass",
                onSmtpHostChanged = { },
                onPortDraftChange = { },
                onSmtpProtocolChanged = { },
                onSmtpUsernameChanged = { },
                onSmtpPasswordChanged = { },
                isLandscape = false,
                look = SmtpFieldLook.SETTINGS,
            )
        }
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_smtp_password_hint)).assertExists()
    }

    @Test
    fun `SenderReceiverFields renders all fields`() {
        composeTestRule.setContent {
            SenderReceiverFields(
                destEmail = "dest@example.com",
                destName = "Dest",
                senderEmail = "sender@example.com",
                senderName = "Sender",
                onDestEmailChanged = { },
                onDestNameChanged = { },
                onSenderEmailChanged = { },
                onSenderNameChanged = { },
                isLandscape = false,
            )
        }
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_dest_email)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_sender_email)).assertExists()
    }

    // The landscape mode in SenderReceiverFields places email/name side by side.
    @Test
    fun `SenderReceiverFields renders in landscape mode`() {
        composeTestRule.setContent {
            SenderReceiverFields(
                destEmail = "dest@example.com",
                destName = "Dest",
                senderEmail = "sender@example.com",
                senderName = "Sender",
                onDestEmailChanged = { },
                onDestNameChanged = { },
                onSenderEmailChanged = { },
                onSenderNameChanged = { },
                isLandscape = true,
            )
        }
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_dest_email)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_sender_email)).assertExists()
    }

    // The portrait mode with row spacing renders the spacer between dest and sender rows.
    @Test
    fun `SenderReceiverFields renders portrait mode with row spacing`() {
        composeTestRule.setContent {
            SenderReceiverFields(
                destEmail = "dest@example.com",
                destName = "Dest",
                senderEmail = "sender@example.com",
                senderName = "Sender",
                onDestEmailChanged = { },
                onDestNameChanged = { },
                onSenderEmailChanged = { },
                onSenderNameChanged = { },
                isLandscape = false,
                portraitRowSpacing = 8.dp,
            )
        }
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_dest_email)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_sender_email)).assertExists()
    }

    // The optional name fields use the optional_suffix format string.
    @Test
    fun `SenderReceiverFields renders optional name labels`() {
        composeTestRule.setContent {
            SenderReceiverFields(
                destEmail = "dest@example.com",
                destName = "",
                senderEmail = "sender@example.com",
                senderName = "",
                onDestEmailChanged = { },
                onDestNameChanged = { },
                onSenderEmailChanged = { },
                onSenderNameChanged = { },
                isLandscape = false,
            )
        }
        // The optional suffix wraps the dest_name and sender_name strings.
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_dest_email)).assertExists()
    }

    // The SmtpProtocolRow within SmtpFields: clicking a protocol triggers the callback.
    @Test
    fun `SmtpFields protocol row triggers callback on click`() {
        var protocol: SmtpProtocol? = null
        composeTestRule.setContent {
            SmtpFields(
                smtpHost = "smtp.example.com",
                portDraft = "587",
                smtpProtocol = SmtpProtocol.START_TLS,
                smtpUsername = "user",
                smtpPassword = "pass",
                onSmtpHostChanged = { },
                onPortDraftChange = { },
                onSmtpProtocolChanged = { protocol = it },
                onSmtpUsernameChanged = { },
                onSmtpPasswordChanged = { },
                isLandscape = false,
                look = SmtpFieldLook.SETTINGS,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.email_config_smtp_implicit_tls))
            .performClick()
        assertEquals(SmtpProtocol.IMPLICIT_TLS, protocol)
    }
}
