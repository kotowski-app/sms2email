package app.kotowski.sms2email.ui.components

import android.content.Context
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EncryptedPrefsErrorDialogTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `shows the error message and the close button`() {
        composeTestRule.setContent { EncryptedPrefsErrorDialog(onClose = {}) }
        composeTestRule
            .onNodeWithText(context.getString(R.string.encrypted_prefs_error_message))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.action_close)).assertIsDisplayed()
    }

    @Test
    fun `close button invokes onClose`() {
        var closed = 0
        composeTestRule.setContent { EncryptedPrefsErrorDialog(onClose = { closed++ }) }
        composeTestRule.onNodeWithText(context.getString(R.string.action_close)).performClick()
        assertEquals(1, closed)
    }
}
