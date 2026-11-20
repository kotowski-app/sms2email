package app.kotowski.sms2email.ui.components

import android.content.Context
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
class PermissionsAutoResetDialogTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `cancel dismisses without opening settings`() {
        var dismissed = 0
        var opened = 0
        composeTestRule.setContent {
            PermissionsAutoResetDialog(onDismiss = { dismissed++ }, onOpenSettings = { opened++ })
        }
        composeTestRule.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        assertEquals(1, dismissed)
        assertEquals(0, opened)
    }

    @Test
    fun `open settings dismisses and opens the settings`() {
        var dismissed = 0
        var opened = 0
        composeTestRule.setContent {
            PermissionsAutoResetDialog(onDismiss = { dismissed++ }, onOpenSettings = { opened++ })
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.permissions_auto_reset_dialog_open_settings))
            .performClick()
        assertEquals(1, dismissed)
        assertEquals(1, opened)
    }
}
