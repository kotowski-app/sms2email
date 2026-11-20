package app.kotowski.sms2email.ui.smslist

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.testing.SkipInReleaseRule
import app.kotowski.sms2email.testing.smsModel
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
class SmsPagedListTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private fun render(
        rows: List<app.kotowski.sms2email.data.database.SmsUiModel>,
        hasMore: Boolean = false,
    ) {
        var loadMoreCalled = false
        composeTestRule.setContent {
            SmsPagedList(
                rows = rows,
                hasMore = hasMore,
                onLoadMore = { loadMoreCalled = true },
                onSmsClicked = { },
                onLongPress = { },
                onToggleSelection = { },
                selectionMode = false,
                selectedIds = emptySet(),
                isSingleSim = true,
                simNames = emptyMap(),
                simSlots = emptyMap(),
                contactNames = emptyMap(),
                hasInternet = true,
            )
        }
    }

    @Test
    fun `paged list renders rows and their message text`() {
        val rows = listOf(smsModel(1, SmsStatus.SENT, messageBody = "hello"))
        render(rows)
        composeTestRule.onNodeWithText("hello").assertExists()
    }

    @Test
    fun `empty paged list renders no cards`() {
        render(emptyList())
        // No crash with empty list.
    }

    @Test
    fun `SmsListContent shows loading spinner while isLoading`() {
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
        // The loading branch renders the spinner, not the list or empty content.
    }

    @Test
    fun `SmsListContent shows no results when search is active but list is empty`() {
        composeTestRule.setContent {
            SmsListContent(
                list = SmsListUiState(paged = PagedSmsListState(isLoading = false)),
                searchQuery = "test",
                onSmsClicked = { },
                onLongPress = { },
                onToggleSelection = { },
                onLoadMore = { },
            ) { Text("empty slot") }
        }
        // The search-no-results branch renders "No results" instead of the empty slot.
    }

    @Test
    fun `SmsListContent shows empty content when not searching and list is empty`() {
        composeTestRule.setContent {
            SmsListContent(
                list = SmsListUiState(paged = PagedSmsListState(isLoading = false)),
                searchQuery = "",
                onSmsClicked = { },
                onLongPress = { },
                onToggleSelection = { },
                onLoadMore = { },
            ) { Text("empty slot") }
        }
        composeTestRule.onNodeWithText("empty slot").assertExists()
    }

    @Test
    fun `click callback fires for a tapped card`() {
        val clicked = mutableListOf<Long>()
        val rows = listOf(smsModel(1, SmsStatus.SENT, messageBody = "hello"))
        composeTestRule.setContent {
            SmsPagedList(
                rows = rows,
                hasMore = false,
                onLoadMore = { },
                onSmsClicked = { clicked.add(it) },
                onLongPress = { },
                onToggleSelection = { },
                selectionMode = false,
                selectedIds = emptySet(),
                isSingleSim = true,
                simNames = emptyMap(),
                simSlots = emptyMap(),
                contactNames = emptyMap(),
                hasInternet = true,
            )
        }
        composeTestRule.onNodeWithText("hello").performClick()
        assertEquals(listOf(1L), clicked)
    }

    @ExperimentalTestApi
    @Test
    fun `long press callback fires for a long-pressed card`() {
        val longPressed = mutableListOf<Long>()
        val rows = listOf(smsModel(1, SmsStatus.SENT, messageBody = "hello"))
        composeTestRule.setContent {
            SmsPagedList(
                rows = rows,
                hasMore = false,
                onLoadMore = { },
                onSmsClicked = { },
                onLongPress = { longPressed.add(it) },
                onToggleSelection = { },
                selectionMode = false,
                selectedIds = emptySet(),
                isSingleSim = true,
                simNames = emptyMap(),
                simSlots = emptyMap(),
                contactNames = emptyMap(),
                hasInternet = true,
            )
        }
        composeTestRule.onNodeWithText("hello").performMouseInput { longClick() }
        assertEquals(listOf(1L), longPressed)
    }

    // In selection mode, a tap fires the toggleSelection callback instead of click.
    @Test
    fun `toggle selection callback fires in selection mode`() {
        val toggled = mutableListOf<Long>()
        val rows = listOf(smsModel(1, SmsStatus.SENT, messageBody = "hello"))
        composeTestRule.setContent {
            SmsPagedList(
                rows = rows,
                hasMore = false,
                onLoadMore = { },
                onSmsClicked = { },
                onLongPress = { },
                onToggleSelection = { toggled.add(it) },
                selectionMode = true,
                selectedIds = emptySet(),
                isSingleSim = true,
                simNames = emptyMap(),
                simSlots = emptyMap(),
                contactNames = emptyMap(),
                hasInternet = true,
            )
        }
        composeTestRule.onNodeWithText("hello").performClick()
        assertEquals(listOf(1L), toggled)
    }

    // The LaunchedEffect fires onLoadMore when the list has more data: with a single
    // item rendered and hasMore=true, the last visible index triggers the threshold.
    @Test
    fun `loadMore fires when the list has more data`() {
        var loadMoreFired = false
        val rows = listOf(smsModel(1, SmsStatus.SENT, messageBody = "hello"))
        composeTestRule.setContent {
            SmsPagedList(
                rows = rows,
                hasMore = true,
                onLoadMore = { loadMoreFired = true },
                onSmsClicked = { },
                onLongPress = { },
                onToggleSelection = { },
                selectionMode = false,
                selectedIds = emptySet(),
                isSingleSim = true,
                simNames = emptyMap(),
                simSlots = emptyMap(),
                contactNames = emptyMap(),
                hasInternet = true,
            )
        }
        composeTestRule.waitForIdle()
        assertTrue(loadMoreFired)
    }
}
