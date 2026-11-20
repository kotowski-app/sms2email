package app.kotowski.sms2email.ui.components

import android.content.Context
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.kotowski.sms2email.LanguageOption
import app.kotowski.sms2email.testing.SkipInReleaseRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LanguagePickerItemsTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Above the platform gate the pick never recreates the activity, so this suite covers the
    // options list and pick wiring; the recreate rule is covered in LanguageHelperTest.
    @Test
    fun `the items are the shipped languages in order and a pick reports its tag`() {
        val picks = mutableListOf<String>()
        lateinit var items: List<Pair<String, () -> Unit>>
        composeTestRule.setContent {
            items = languagePickerItems(picks::add)
        }

        assertEquals(
            LanguageOption.entries.map { context.getString(it.labelRes) },
            items.map { it.first },
        )

        items.forEach { it.second() }
        assertEquals(LanguageOption.entries.map { it.tag }, picks)
    }

    @org.robolectric.annotation.Config(sdk = [28])
    @Test
    fun `on pre-API-33 picking system default does not crash`() {
        val picks = mutableListOf<String>()
        lateinit var items: List<Pair<String, () -> Unit>>
        composeTestRule.setContent {
            items = languagePickerItems(picks::add)
        }

        // On pre-API-33, the system default pick may trigger activity recreate.
        assertEquals(
            LanguageOption.entries.map { context.getString(it.labelRes) },
            items.map { it.first },
        )

        items.forEach { it.second() }
        assertEquals(LanguageOption.entries.map { it.tag }, picks)
    }
}
