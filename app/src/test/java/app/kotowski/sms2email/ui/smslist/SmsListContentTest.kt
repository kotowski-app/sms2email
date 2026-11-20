package app.kotowski.sms2email.ui.smslist

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.SmsUiModel
import app.kotowski.sms2email.testing.SkipInReleaseRule
import app.kotowski.sms2email.testing.smsModel
import app.kotowski.sms2email.ui.components.CenteredMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmsListContentTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private fun list(
        smsList: List<SmsUiModel> = emptyList(),
        hasMore: Boolean = false,
        selectionMode: Boolean = false,
    ): SmsListUiState =
        SmsListUiState(
            paged =
                PagedSmsListState(
                    isLoading = false,
                    smsList = smsList,
                    hasMore = hasMore,
                ),
            selectionMode = selectionMode,
        )

    @Test
    fun `the loading state shows the spinner, not the empty slot`() {
        composeTestRule.setContent {
            SmsListContent(
                list = SmsListUiState(paged = PagedSmsListState(isLoading = true)),
                searchQuery = "",
                onSmsClicked = { },
                onLongPress = { },
                onToggleSelection = { },
                onLoadMore = { },
            ) { Text("empty slot") }
        }
        composeTestRule.onNodeWithTag("sms_loading").assertExists()
        composeTestRule.onNodeWithText("empty slot").assertDoesNotExist()
    }

    @Test
    fun `an empty list without a search shows the caller's empty slot`() {
        composeTestRule.setContent {
            SmsListContent(
                list = list(),
                searchQuery = "",
                onSmsClicked = { },
                onLongPress = { },
                onToggleSelection = { },
                onLoadMore = { },
            ) { CenteredMessage(R.string.queue_empty) }
        }
        composeTestRule.onNodeWithText("No messages waiting").assertExists()
        composeTestRule.onNodeWithTag("sms_loading").assertDoesNotExist()
    }

    @Test
    fun `an empty list with an active search shows no-results, not the empty slot`() {
        composeTestRule.setContent {
            SmsListContent(
                list = list(),
                searchQuery = "abc",
                onSmsClicked = { },
                onLongPress = { },
                onToggleSelection = { },
                onLoadMore = { },
            ) { Text("empty slot") }
        }
        composeTestRule.onNodeWithText("No messages match your search").assertExists()
        composeTestRule.onNodeWithText("empty slot").assertDoesNotExist()
    }

    @Test
    fun `rows render when the window has content`() {
        composeTestRule.setContent {
            SmsListContent(
                list = list(smsList = listOf(smsModel(1, SmsStatus.PENDING))),
                searchQuery = "",
                onSmsClicked = { },
                onLongPress = { },
                onToggleSelection = { },
                onLoadMore = { },
            ) { Text("empty slot") }
        }
        composeTestRule.onNodeWithText("+1").assertExists()
        composeTestRule.onNodeWithText("m1").assertExists()
        composeTestRule.onNodeWithText("empty slot").assertDoesNotExist()
    }

    @ExperimentalTestApi
    @Test
    fun `a tap and a long press forward the row id`() {
        val clicked = mutableListOf<Long>()
        val longPressed = mutableListOf<Long>()
        composeTestRule.setContent {
            SmsListContent(
                list = list(smsList = listOf(smsModel(1, SmsStatus.PENDING))),
                searchQuery = "",
                onSmsClicked = { clicked.add(it) },
                onLongPress = { longPressed.add(it) },
                onToggleSelection = { },
                onLoadMore = { },
            ) { Text("empty slot") }
        }
        composeTestRule.onNodeWithText("m1").performClick()
        assertEquals(listOf(1L), clicked)
        composeTestRule.onNodeWithText("m1").performMouseInput { longClick() }
        assertEquals(listOf(1L), longPressed)
        assertTrue(clicked.size == 1, "a long press must not also fire the tap")
    }

    @Test
    fun `in selection mode a tap toggles the selection`() {
        val toggled = mutableListOf<Long>()
        composeTestRule.setContent {
            SmsListContent(
                list = list(smsList = listOf(smsModel(1, SmsStatus.PENDING)), selectionMode = true),
                searchQuery = "",
                onSmsClicked = { },
                onLongPress = { },
                onToggleSelection = { toggled.add(it) },
                onLoadMore = { },
            ) { Text("empty slot") }
        }
        composeTestRule.onNodeWithText("m1").performClick()
        assertEquals(listOf(1L), toggled)
    }

    @Test
    fun `load more fires when the window can grow`() {
        var loadMoreFired = false
        composeTestRule.setContent {
            SmsListContent(
                list = list(smsList = listOf(smsModel(1, SmsStatus.PENDING)), hasMore = true),
                searchQuery = "",
                onSmsClicked = { },
                onLongPress = { },
                onToggleSelection = { },
                onLoadMore = { loadMoreFired = true },
            ) { Text("empty slot") }
        }
        composeTestRule.waitForIdle()
        assertTrue(loadMoreFired)
    }
}
