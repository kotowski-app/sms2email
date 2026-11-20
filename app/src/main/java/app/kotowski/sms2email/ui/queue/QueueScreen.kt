package app.kotowski.sms2email.ui.queue

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.kotowski.sms2email.R
import app.kotowski.sms2email.ui.components.CenteredMessage
import app.kotowski.sms2email.ui.components.SelectionTopBar
import app.kotowski.sms2email.ui.components.TextConfirmDialog
import app.kotowski.sms2email.ui.smslist.SearchToggleButton
import app.kotowski.sms2email.ui.smslist.SmsListContent
import app.kotowski.sms2email.ui.smslist.SmsSearchField
import app.kotowski.sms2email.ui.smslist.rememberSmsSearchState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    onNavigateBack: () -> Unit,
    onSmsClicked: (Long) -> Unit,
    viewModel: QueueViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showCancelDialog by remember { mutableStateOf(false) }
    var showForwardDialog by remember { mutableStateOf(false) }
    var showForwardAllDialog by remember { mutableStateOf(false) }
    var showCancelAllDialog by remember { mutableStateOf(false) }
    val search = rememberSmsSearchState(viewModel::setSearchQuery)

    if (showCancelDialog) {
        TextConfirmDialog(
            titleResId = R.string.queue_cancel_selected_title,
            detail = pluralStringResource(R.plurals.queue_cancel_confirm, state.selectedIds.size, state.selectedIds.size),
            confirmResId = R.string.action_yes,
            onConfirm = {
                viewModel.cancelSelected()
                showCancelDialog = false
                search.clear()
            },
            onDismiss = {
                showCancelDialog = false
            },
        )
    }

    if (showForwardDialog) {
        TextConfirmDialog(
            titleResId = R.string.forward_messages_title,
            detail = pluralStringResource(R.plurals.forward_messages_confirm, state.selectedIds.size, state.selectedIds.size),
            confirmResId = R.string.action_send,
            onConfirm = {
                viewModel.forwardSelected()
                showForwardDialog = false
                search.clear()
            },
            onDismiss = {
                showForwardDialog = false
            },
        )
    }

    if (showForwardAllDialog) {
        TextConfirmDialog(
            titleResId = R.string.forward_messages_title,
            // The exact queue total from the DB (the loaded list is window-sized, and
            // "Forward all" forwards the whole queue, not just the window).
            detail = pluralStringResource(R.plurals.queue_forward_all_confirm, state.paged.smsCount, state.paged.smsCount),
            confirmResId = R.string.action_send,
            onConfirm = {
                viewModel.forwardAll()
                showForwardAllDialog = false
                search.clear()
            },
            onDismiss = {
                showForwardAllDialog = false
            },
        )
    }

    if (showCancelAllDialog) {
        TextConfirmDialog(
            titleResId = R.string.queue_cancel_all_title,
            // The exact queue total from the DB (the loaded list is window-sized, and
            // "Cancel all" parks the whole queue, not just the window).
            detail = pluralStringResource(R.plurals.queue_cancel_all_confirm, state.paged.smsCount, state.paged.smsCount),
            confirmResId = R.string.action_yes,
            onConfirm = {
                viewModel.cancelAll()
                showCancelAllDialog = false
                search.clear()
            },
            onDismiss = {
                showCancelAllDialog = false
            },
        )
    }

    Scaffold(
        topBar = {
            if (state.selectionMode) {
                SelectionTopBar(
                    selectedCount = state.selectedIds.size,
                    onCancel = { viewModel.exitSelectionMode() },
                    onFirstAction = { showForwardDialog = true },
                    onSecondAction = { showCancelDialog = true },
                    firstResId = R.string.action_send,
                    secondResId = R.string.queue_cancel_selected,
                    secondIcon = Icons.Default.Cancel,
                    countResId = R.string.selection_count,
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.queue_title_count, state.paged.smsCount)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        if (state.paged.smsCount > 0) {
                            SearchToggleButton(
                                onToggle = search.toggle,
                            )
                            IconButton(
                                onClick = { showForwardAllDialog = true },
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(R.string.queue_forward_all_content_desc),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            IconButton(
                                onClick = { showCancelAllDialog = true },
                            ) {
                                Icon(
                                    Icons.Default.Cancel,
                                    contentDescription = stringResource(R.string.queue_cancel_all_content_desc),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            if (search.isSearchMode) {
                SmsSearchField(
                    query = search.rawQuery,
                    onQueryChange = search.onRawQueryChange,
                    onClear = search.clear,
                )
            }

            SmsListContent(
                list = state,
                searchQuery = search.query,
                onSmsClicked = onSmsClicked,
                onLongPress = { id -> viewModel.enterSelectionMode().also { viewModel.toggleSelection(id) } },
                onToggleSelection = viewModel::toggleSelection,
                onLoadMore = viewModel::loadMore,
            ) {
                CenteredMessage(R.string.queue_empty)
            }
        }
    }
}
