package app.kotowski.sms2email.ui.emailconfig

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.kotowski.sms2email.R
import app.kotowski.sms2email.service.EmailResult
import app.kotowski.sms2email.testing.SkipInReleaseRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TestResultButtonTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun showButton(
        enabled: Boolean = true,
        isWorking: Boolean = false,
        result: EmailResult? = null,
    ) {
        composeTestRule.setContent {
            TestResultButton(
                label = context.getString(R.string.email_config_send_test_email),
                onClick = {},
                enabled = enabled,
                isWorking = isWorking,
                result = result,
            )
        }
    }

    @Test
    fun `the idle button shows the label alone`() {
        showButton()
        composeTestRule
            .onNodeWithText(context.getString(R.string.email_config_send_test_email))
            .assertIsDisplayed()
    }

    // The spinner replaces the label while the run is in flight (and the button is
    // disabled): the a11y label must not be lost with it.
    @Test
    fun `the working button carries the testing label for accessibility`() {
        showButton(enabled = false, isWorking = true)
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.email_config_test_testing))
            .assertIsDisplayed()
    }

    // The result icon alone carries the outcome: TalkBack must read a label with the
    // button's text, not just the label.
    @Test
    fun `a succeeded result labels its icon success`() {
        showButton(result = EmailResult.Success)
        composeTestRule.onNodeWithText(context.getString(R.string.email_config_send_test_email)).assertExists()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.email_config_test_success))
            .assertIsDisplayed()
    }

    @Test
    fun `a failed result labels its icon failure`() {
        showButton(result = EmailResult.Failure("refused"))
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.history_status_failed))
            .assertIsDisplayed()
    }

    // The icon vectors themselves (the labels are asserted above): the check for the
    // success, the cancel for the failure.
    @Test
    fun `the success maps to the check icon`() {
        assertSame(Icons.Default.CheckCircle, testResultIcon(EmailResult.Success))
    }

    @Test
    fun `the failure maps to the cancel icon`() {
        assertSame(Icons.Default.Cancel, testResultIcon(EmailResult.Failure("x")))
    }

    @Test
    fun `the disabled button shows the label in disabled state`() {
        showButton(enabled = false)
        composeTestRule
            .onNodeWithText(context.getString(R.string.email_config_send_test_email))
            .assertExists()
    }

    @Test
    fun `the working button with custom indicator size`() {
        composeTestRule.setContent {
            TestResultButton(
                label = context.getString(R.string.email_config_send_test_email),
                onClick = {},
                enabled = false,
                isWorking = true,
                result = null,
                indicatorSize = 24.dp,
            )
        }
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.email_config_test_testing))
            .assertExists()
    }

    @Test
    fun `a failed result shows both label and failure icon`() {
        showButton(result = EmailResult.Failure("connection refused"))
        composeTestRule
            .onNodeWithText(context.getString(R.string.email_config_send_test_email))
            .assertExists()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.history_status_failed))
            .assertExists()
    }
}
