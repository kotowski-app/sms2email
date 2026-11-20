package app.kotowski.sms2email.ui.emailconfig

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import androidx.test.core.app.ApplicationProvider
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.SmtpProtocol
import app.kotowski.sms2email.testing.SkipInReleaseRule
import app.kotowski.sms2email.ui.settings.EmailConfigSummaryCard
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
class EmailConfigFoldTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun boundsOf(text: String): DpRect =
        composeTestRule
            .onNodeWithText(text, useUnmergedTree = true)
            .getBoundsInRoot()

    private fun assertSideBySide(
        leftText: String,
        rightText: String,
    ) {
        val left = boundsOf(leftText)
        val right = boundsOf(rightText)
        assertTrue(
            right.left > left.right,
            "expected \"$rightText\" to the right of \"$leftText\": $left vs $right",
        )
    }

    private fun assertStacked(
        topText: String,
        bottomText: String,
    ) {
        val top = boundsOf(topText)
        val bottom = boundsOf(bottomText)
        assertEquals(top.left.value, bottom.left.value, 0.5f, "expected the same left edge")
        assertTrue(
            bottom.top > top.bottom,
            "expected \"$bottomText\" below \"$topText\": $top vs $bottom",
        )
    }

    @Test
    fun `smtp fields fold username and password in the landscape`() {
        composeTestRule.setContent {
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
                isLandscape = true,
                look = SmtpFieldLook.SETTINGS,
            )
        }
        assertSideBySide(
            context.getString(R.string.email_config_username),
            context.getString(R.string.email_config_password),
        )
    }

    @Test
    fun `smtp fields stack the fields in the portrait`() {
        composeTestRule.setContent {
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
                isLandscape = false,
                look = SmtpFieldLook.SETTINGS,
            )
        }
        assertStacked(
            context.getString(R.string.email_config_username),
            context.getString(R.string.email_config_password),
        )
    }

    @Test
    fun `summary card folds the four pairs in the landscape and masks the password`() {
        val config =
            EmailConfig(
                settings =
                    AppSettings(
                        smtpHost = "smtp.example.com",
                        smtpPort = 465,
                        smtpProtocol = SmtpProtocol.IMPLICIT_TLS,
                        smtpUsername = "user",
                        destEmail = "dest@example.com",
                        destName = "Dest",
                        senderEmail = "sender@example.com",
                        senderName = "Sender",
                    ),
                smtpPassword = "secret",
            )
        composeTestRule.setContent {
            EmailConfigSummaryCard(
                config = config,
                isLandscape = true,
                onEdit = {},
            )
        }
        assertSideBySide(context.getString(R.string.email_config_protocol), context.getString(R.string.email_config_port))
        assertSideBySide(
            context.getString(R.string.email_config_username),
            context.getString(R.string.email_config_password),
        )
        assertSideBySide(context.getString(R.string.email_config_dest_email), context.getString(R.string.email_config_dest_name))
        assertSideBySide(
            context.getString(R.string.email_config_sender_email),
            context.getString(R.string.email_config_sender_name),
        )
        // The password is masked: one dot per character.
        composeTestRule
            .onNodeWithText("••••••", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun `summary card stacks the rows in the portrait with all labels`() {
        val config =
            EmailConfig(
                settings =
                    AppSettings(
                        smtpHost = "smtp.example.com",
                        smtpPort = 465,
                        smtpProtocol = SmtpProtocol.IMPLICIT_TLS,
                        smtpUsername = "user",
                        destEmail = "dest@example.com",
                        destName = "Dest",
                        senderEmail = "sender@example.com",
                        senderName = "Sender",
                    ),
                smtpPassword = "secret",
            )
        composeTestRule.setContent {
            EmailConfigSummaryCard(
                config = config,
                isLandscape = false,
                onEdit = {},
            )
        }
        assertStacked(context.getString(R.string.email_config_protocol), context.getString(R.string.email_config_port))
        assertStacked(
            context.getString(R.string.email_config_username),
            context.getString(R.string.email_config_password),
        )
        assertStacked(context.getString(R.string.email_config_dest_email), context.getString(R.string.email_config_dest_name))
        assertStacked(
            context.getString(R.string.email_config_sender_email),
            context.getString(R.string.email_config_sender_name),
        )
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_host), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `summary card shows an em dash for the empty values`() {
        composeTestRule.setContent {
            EmailConfigSummaryCard(
                config = EmailConfig(),
                isLandscape = false,
                onEdit = {},
            )
        }
        // Host, username, password, recipient email, recipient name, sender email,
        // sender name — the port and the protocol always have a value.
        composeTestRule
            .onAllNodes(hasText("\u2014"), useUnmergedTree = true)
            .assertCountEquals(7)
    }
}
