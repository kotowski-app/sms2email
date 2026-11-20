package app.kotowski.sms2email.ui.smslist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.kotowski.sms2email.R
import app.kotowski.sms2email.ui.components.CenteredMessage

// The content area shared by the Queue and History screens, in precedence order: the
// initial loading spinner, the search "no matches" state (the search covers the whole
// table in SQL, so an empty window with an active query is "no matches", not "empty"),
// the caller's empty state, then the paged list itself.
@Composable
fun SmsListContent(
    list: SmsListUiState,
    searchQuery: String,
    onSmsClicked: (Long) -> Unit,
    onLongPress: (Long) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onLoadMore: () -> Unit,
    emptyContent: @Composable () -> Unit,
) {
    if (list.paged.isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.testTag("sms_loading"))
        }
    } else if (searchQuery.isNotEmpty() && list.paged.smsList.isEmpty()) {
        CenteredMessage(R.string.sms_search_no_results)
    } else if (list.paged.smsList.isEmpty()) {
        emptyContent()
    } else {
        SmsPagedList(
            rows = list.paged.smsList,
            hasMore = list.paged.hasMore,
            onLoadMore = onLoadMore,
            onSmsClicked = onSmsClicked,
            onLongPress = onLongPress,
            onToggleSelection = onToggleSelection,
            selectionMode = list.selectionMode,
            selectedIds = list.selectedIds,
            isSingleSim = !list.displaySimCards,
            simNames = list.paged.simNames,
            simSlots = list.paged.simSlots,
            contactNames = list.paged.contactNames,
            hasInternet = list.hasInternet,
        )
    }
}
