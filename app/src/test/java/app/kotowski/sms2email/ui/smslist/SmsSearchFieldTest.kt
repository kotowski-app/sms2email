package app.kotowski.sms2email.ui.smslist

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
class SmsSearchFieldTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    @Test
    fun `search field renders with hint and clear button`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        var cleared = false
        composeTestRule.setContent {
            SmsSearchField(
                query = "",
                onQueryChange = { },
                onClear = { cleared = true },
            )
        }
        composeTestRule.onNodeWithText(ctx.getString(R.string.sms_search_hint)).assertExists()
        composeTestRule
            .onNodeWithContentDescription(ctx.getString(R.string.action_cancel))
            .performClick()
        composeTestRule.waitForIdle()
    }

    // The SearchToggleButton exercises the toggle branch in the toolbar.
    @Test
    fun `search toggle button fires onToggle`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        var toggled = false
        composeTestRule.setContent {
            SearchToggleButton(onToggle = { toggled = true })
        }
        composeTestRule
            .onNodeWithContentDescription(ctx.getString(R.string.sms_search_hint))
            .performClick()
        assertEquals(true, toggled)
    }
}
