package app.kotowski.sms2email.ui.emailconfig

import android.content.Context
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import kotlin.test.assertNull

class SmtpPortFieldTest {
    @Test
    fun `a port in range is valid`() {
        for (port in listOf("1", "25", "587", "465", "2525", "65535")) {
            assertEquals(SmtpPortState.VALID, validateSmtpPort(port), port)
        }
    }

    @Test
    fun `an empty draft is empty`() {
        assertEquals(SmtpPortState.EMPTY, validateSmtpPort(""))
    }

    @Test
    fun `a draft out of range or not a number is invalid`() {
        for (draft in listOf("0", "65536", "99999999999", "-1", "1.5", "58a", " 587", "0x10")) {
            assertEquals(SmtpPortState.INVALID, validateSmtpPort(draft), draft)
        }
    }

    @Test
    fun `the commit value is the parsed port`() {
        assertEquals(587, smtpPortCommitValue("587"))
        assertEquals(65535, smtpPortCommitValue("65535"))
    }

    @Test
    fun `an empty draft commits nothing`() {
        assertNull(smtpPortCommitValue(""))
    }

    @Test
    fun `an invalid draft commits nothing`() {
        for (draft in listOf("0", "65536", "-1", "abc", "1.5")) {
            assertNull(smtpPortCommitValue(draft), draft)
        }
    }
}

// Compose rendering tests for the port field: the three border color branches.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmtpPortFieldUiTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `port field renders with valid port and label`() {
        composeTestRule.setContent {
            SmtpPortField(
                value = "587",
                onValueChange = { },
            )
        }
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_port)).assertExists()
    }

    // The INVALID branch: the border color switches to error for an out-of-range port.
    @Test
    fun `port field renders with invalid port`() {
        composeTestRule.setContent {
            SmtpPortField(
                value = "99999",
                onValueChange = { },
            )
        }
        // The field composes without crash; the error border color branch is exercised.
    }

    // The EMPTY branch: the border color uses outline for an empty field.
    @Test
    fun `port field renders with empty draft`() {
        composeTestRule.setContent {
            SmtpPortField(
                value = "",
                onValueChange = { },
            )
        }
        // The field composes without crash; the outline border color branch is exercised.
    }
}
