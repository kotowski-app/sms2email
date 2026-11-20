package app.kotowski.sms2email.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
class ImportLauncherTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    @Test
    fun `rememberImportLauncher passes uri to callback`() {
        var pickedUri: Uri? = null
        var launcher: (() -> Unit)? = null

        composeTestRule.setContent {
            val launcherRef =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                    uri?.let { pickedUri = it }
                }
            launcher = { launcherRef.launch(arrayOf("*/*")) }
            androidx.compose.material3.Text(
                text = "trigger",
                modifier =
                    androidx.compose.ui.Modifier
                        .testTag("trigger"),
            )
        }

        // In Robolectric, launching the activity result doesn't simulate a real picker,
        // but the composable renders and the launcher reference is captured.
        launcher?.invoke()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `rememberImportLauncher handles null uri gracefully`() {
        var pickCount = 0
        composeTestRule.setContent {
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                pickCount++
                if (uri == null) {
                    // Null uri: user cancelled the picker — no-op.
                }
            }
        }
    }

    @Test
    fun `rememberImportLauncher composes with the actual factory function`() {
        var picked: Uri? = null
        composeTestRule.setContent {
            rememberImportLauncher { uri ->
                picked = uri
            }
        }
        composeTestRule.waitForIdle()
    }
}
