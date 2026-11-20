package app.kotowski.sms2email.ui.emailconfig

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmtpProtocolRowTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `row renders both protocol buttons`() {
        composeTestRule.setContent {
            SmtpProtocolRow(
                smtpProtocol = SmtpProtocol.START_TLS,
                onProtocolChanged = {},
            )
        }
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_smtp_starttls)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_smtp_implicit_tls)).assertIsDisplayed()
    }

    @Test
    fun `clicking implicit TLS invokes the callback`() {
        var changed: SmtpProtocol? = null
        composeTestRule.setContent {
            SmtpProtocolRow(
                smtpProtocol = SmtpProtocol.START_TLS,
                onProtocolChanged = { changed = it },
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.email_config_smtp_implicit_tls))
            .performClick()
        assertEquals(SmtpProtocol.IMPLICIT_TLS, changed)
    }

    @Test
    fun `clicking STARTTLS invokes the callback`() {
        var changed: SmtpProtocol? = null
        composeTestRule.setContent {
            SmtpProtocolRow(
                smtpProtocol = SmtpProtocol.IMPLICIT_TLS,
                onProtocolChanged = { changed = it },
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.email_config_smtp_starttls))
            .performClick()
        assertEquals(SmtpProtocol.START_TLS, changed)
    }
}
