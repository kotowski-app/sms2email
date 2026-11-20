package app.kotowski.sms2email.ui.components

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
class PermissionLaunchersTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    @Test
    fun `rememberPermissionLaunchers exposes seven distinct launchers`() {
        var results = 0
        var autoResetResults = 0
        var launchers: PermissionLaunchers? = null
        composeTestRule.setContent {
            launchers =
                rememberPermissionLaunchers(
                    onResult = { results++ },
                    onAutoResetResult = { autoResetResults++ },
                )
        }
        val all =
            listOf(
                launchers!!.sms,
                launchers.notifications,
                launchers.contacts,
                launchers.phone,
                launchers.battery,
                launchers.appSettings,
                launchers.autoReset,
            )
        assertEquals(7, all.distinct().size)
    }
}
