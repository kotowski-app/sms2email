package app.kotowski.sms2email.ui.smslist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kotowski.sms2email.data.database.SmsUiModel
import app.kotowski.sms2email.ui.components.SystemLazyColumnScrollIndicator
import kotlinx.coroutines.launch

// The paged SMS list shared by the History and Queue screens: a newest-first window of
// rows (the ViewModel loads `loadedLimit` rows, growing a page per loadMore), keyed by
// row id so a top insert (a new send finishing while the list is open) keeps every
// visible row anchored instead of shifting the window under the finger.
@Composable
fun SmsPagedList(
    rows: List<SmsUiModel>,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onSmsClicked: (Long) -> Unit,
    onLongPress: (Long) -> Unit,
    onToggleSelection: (Long) -> Unit,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    isSingleSim: Boolean,
    simNames: Map<Int, String>,
    simSlots: Map<Int, Int>,
    contactNames: Map<String, String>,
    hasInternet: Boolean,
) {
    val listState = rememberLazyListState()
    val lastVisibleIndex by remember {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index
                ?: 0
        }
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(lastVisibleIndex, hasMore, rows.size) {
        if (!shouldLoadMore(lastVisibleIndex, rows.size, hasMore)) return@LaunchedEffect
        scope.launch { onLoadMore() }
    }
    Box(
        modifier =
            Modifier
                .fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxSize(),
            contentPadding =
                PaddingValues(
                    16.dp,
                ),
            verticalArrangement =
                Arrangement.spacedBy(
                    8.dp,
                ),
        ) {
            itemsIndexed(
                items = rows,
                key = { _, row -> row.id },
            ) { _, row ->
                SmsListCard(
                    sms = row,
                    selectionMode = selectionMode,
                    isSelected = selectedIds.contains(row.id),
                    isSingleSim = isSingleSim,
                    simNames = simNames,
                    simSlots = simSlots,
                    contactNames = contactNames,
                    onClick = onSmsClicked,
                    onLongPress = onLongPress,
                    onToggleSelection = onToggleSelection,
                    hasInternet = hasInternet,
                )
            }
        }
        SystemLazyColumnScrollIndicator(
            listState = listState,
            modifier =
                Modifier
                    .align(
                        Alignment.CenterEnd,
                    ),
        )
    }
}
