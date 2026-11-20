package app.kotowski.sms2email.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.filter.FilterMode
import app.kotowski.sms2email.data.filter.FilterRule
import app.kotowski.sms2email.data.filter.SmsFilter
import app.kotowski.sms2email.testing.SkipInReleaseRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FilterSummaryRowTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    private var editCalled = false

    private fun showRow(filter: SmsFilter) {
        editCalled = false
        composeTestRule.setContent {
            FilterSummaryRow(
                filter = filter,
                onEdit = { editCalled = true },
            )
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the off state reads the off line with the edit button and no rule lines`() {
        showRow(SmsFilter())
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_off)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.action_edit)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_card_skip)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_card_forward)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_no_conditions)).assertDoesNotExist()
    }

    @Test
    fun `the skip mode reads the sentence rule line without the header`() {
        showRow(
            SmsFilter(
                FilterMode.SKIP,
                listOf(FilterRule(body = "vip", sender = "123", slots = listOf(1, 2))),
            ),
        )
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_card_skip)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_off)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.action_edit)).assertIsDisplayed()
        // No line number: the rule is one sentence line, the facets joined by "AND",
        // the patterns quoted, the several slots as the "(SIM 1 OR 2)" group.
        composeTestRule.onNodeWithText("1.").assertDoesNotExist()
        val or = context.getString(R.string.settings_filter_or)
        val line =
            context.getString(R.string.settings_filter_card_body, "vip") +
                " " +
                context.getString(R.string.settings_filter_and) +
                " " +
                context.getString(R.string.settings_filter_card_sender, "123") +
                " " +
                context.getString(R.string.settings_filter_and) +
                " " +
                context.getString(
                    R.string.settings_filter_card_slots,
                    context.getString(R.string.settings_filter_slots_group, "1 $or 2"),
                )
        composeTestRule.onNodeWithText(line).assertIsDisplayed()
    }

    @Test
    fun `the forward mode reads the sentence rule line without the header`() {
        showRow(SmsFilter(FilterMode.FORWARD, listOf(FilterRule(body = "a", slots = listOf(2)))))
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_card_forward)).assertDoesNotExist()
        // A single slot stays the plain "SIM N" (no group, no OR): the line is one
        // node, asserted as its exact text.
        val line =
            context.getString(R.string.settings_filter_card_body, "a") +
                " " +
                context.getString(R.string.settings_filter_and) +
                " " +
                context.getString(
                    R.string.settings_filter_card_slots,
                    context.getString(R.string.settings_filter_slot, 2),
                )
        composeTestRule.onNodeWithText(line).assertIsDisplayed()
    }

    @Test
    fun `the rules are separated by the or line`() {
        showRow(
            SmsFilter(
                FilterMode.SKIP,
                listOf(FilterRule(body = "a"), FilterRule(body = "b")),
            ),
        )
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_card_body, "a"))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_or)).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_card_body, "b"))
            .assertIsDisplayed()
    }

    @Test
    fun `a rule with no active facet reads the off line and the muted no-conditions`() {
        showRow(SmsFilter(FilterMode.SKIP, listOf(FilterRule())))
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_off)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_no_conditions)).assertIsDisplayed()
    }

    @Test
    fun `the edit button opens the editor`() {
        showRow(SmsFilter())
        composeTestRule.onNodeWithText(context.getString(R.string.action_edit)).performClick()
        assertTrue(editCalled)
    }
}
