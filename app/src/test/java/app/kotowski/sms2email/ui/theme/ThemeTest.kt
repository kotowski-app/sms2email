package app.kotowski.sms2email.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.kotowski.sms2email.ThemeMode
import app.kotowski.sms2email.testing.SkipInReleaseRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// The Sms2EmailTheme composable has two code paths: static palettes on pre-12 devices
// and dynamic Material You on 12+. Robolectric at sdk=28 forces the static path; sdk=34
// forces the dynamic path. All theme assets (Color, Type, Shape, AndroidVersion) are
// exercised transitively through the composable.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeTest {
    @get:Rule
    val composeTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Reset to light so each test starts from a known uiMode baseline.
    @Before
    fun resetToLight() {
        val cfg = context.resources.configuration
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        context.resources.updateConfiguration(cfg, context.resources.displayMetrics)
    }

    @After
    fun restoreLight() {
        resetToLight()
    }

    // sdk=28: is12Plus() is false, so the static LightColorScheme path executes.
    @Config(sdk = [28])
    @Test
    fun sdk28LightModeCoversStaticLightPalette() {
        composeTestRule.setContent {
            Sms2EmailTheme(themeMode = ThemeMode.LIGHT) {
                Text("hello")
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("hello").assertExists()
    }

    @Config(sdk = [28])
    @Test
    fun sdk28DarkModeCoversStaticDarkPalette() {
        composeTestRule.setContent {
            Sms2EmailTheme(themeMode = ThemeMode.DARK) {
                Text("hello")
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("hello").assertExists()
    }

    @Config(sdk = [28])
    @Test
    fun sdk28SystemModeWithDefaultParamCoversSystemBranch() {
        composeTestRule.setContent {
            Sms2EmailTheme {
                Text("hello")
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("hello").assertExists()
    }

    @Config(sdk = [28])
    @Test
    fun sdk28SystemModeDarkUiModeCoversDarkSystemBranch() {
        val cfg = context.resources.configuration
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        context.resources.updateConfiguration(cfg, context.resources.displayMetrics)

        composeTestRule.setContent {
            Sms2EmailTheme(themeMode = ThemeMode.SYSTEM) {
                Text("hello")
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("hello").assertExists()
    }

    // sdk=34: is12Plus() is true, so the dynamic Material You path executes.
    @Config(sdk = [34])
    @Test
    fun sdk34LightModeCoversDynamicPalette() {
        composeTestRule.setContent {
            Sms2EmailTheme(themeMode = ThemeMode.LIGHT) {
                Text("hello")
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("hello").assertExists()
    }

    @Config(sdk = [34])
    @Test
    fun sdk34DarkModeCoversDynamicDarkPalette() {
        composeTestRule.setContent {
            Sms2EmailTheme(themeMode = ThemeMode.DARK) {
                Text("hello")
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("hello").assertExists()
    }

    @Config(sdk = [34])
    @Test
    fun sdk34SystemModeDarkUiModeCoversDynamicDarkSystemBranch() {
        val cfg = context.resources.configuration
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        context.resources.updateConfiguration(cfg, context.resources.displayMetrics)

        composeTestRule.setContent {
            Sms2EmailTheme(themeMode = ThemeMode.SYSTEM) {
                Text("hello")
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("hello").assertExists()
    }

    @Config(sdk = [34])
    @Test
    fun sdk34SystemModeLightUiModeCoversDynamicLightSystemBranch() {
        composeTestRule.setContent {
            Sms2EmailTheme(themeMode = ThemeMode.SYSTEM) {
                Text("hello")
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("hello").assertExists()
    }
}
