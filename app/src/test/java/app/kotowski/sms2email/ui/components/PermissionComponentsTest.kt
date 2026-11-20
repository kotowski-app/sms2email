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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PermissionComponentsTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `rememberPermissionLaunchers creates all launchers`() {
        var launchers: PermissionLaunchers? = null

        composeTestRule.setContent {
            launchers =
                rememberPermissionLaunchers(
                    onResult = {},
                    onAutoResetResult = {},
                )
        }
        composeTestRule.waitForIdle()

        assertNotNull(launchers)
    }

    @Test
    fun `PermissionsAutoResetDialog confirm dismisses and opens settings`() {
        var dismissed = false
        var settingsOpened = false

        composeTestRule.setContent {
            PermissionsAutoResetDialog(
                onDismiss = { dismissed = true },
                onOpenSettings = { settingsOpened = true },
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithText(context.getString(R.string.permissions_auto_reset_dialog_open_settings))
            .performClick()
        composeTestRule.waitForIdle()

        assertTrue(dismissed)
        assertTrue(settingsOpened)
    }

    @Test
    fun `PermissionsAutoResetDialog dismiss cancels`() {
        var dismissed = false
        var settingsOpened = false

        composeTestRule.setContent {
            PermissionsAutoResetDialog(
                onDismiss = { dismissed = true },
                onOpenSettings = { settingsOpened = true },
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        composeTestRule.waitForIdle()

        assertTrue(dismissed)
        assertTrue(!settingsOpened)
    }
}
