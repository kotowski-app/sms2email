package app.kotowski.sms2email.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.kotowski.sms2email.PeriodPreset
import app.kotowski.sms2email.R
import app.kotowski.sms2email.ui.components.Banner
import app.kotowski.sms2email.ui.components.CenteredMessage
import app.kotowski.sms2email.ui.components.SelectionTopBar
import app.kotowski.sms2email.ui.components.TextConfirmDialog
import app.kotowski.sms2email.ui.icons.SyncArrowDown
import app.kotowski.sms2email.ui.smslist.SearchToggleButton
import app.kotowski.sms2email.ui.smslist.SmsListContent
import app.kotowski.sms2email.ui.smslist.SmsSearchField
import app.kotowski.sms2email.ui.smslist.rememberSmsSearchState
import app.kotowski.sms2email.util.DateUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onNavigateBack: () -> Unit,
    onSmsClicked: (Long) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val importState by viewModel.importFlow.state.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showForwardDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val search = rememberSmsSearchState(viewModel::setSearchQuery)

    // A successful import rewrites the history scope, so the active search no longer
    // applies and is dropped. A failed import changes nothing: the search survives.
    LaunchedEffect(importState.importResult) {
        if (importState.importResult != null) {
            search.clear()
        }
    }

    HistoryResultDialogs(
        state = state,
        importState = importState,
        viewModel = viewModel,
        showClearDialog = showClearDialog,
        showDeleteDialog = showDeleteDialog,
        showForwardDialog = showForwardDialog,
        // Dismissing a dialog took no action: the active search must survive.
        onSelectionDialogDismiss = {
            showClearDialog = false
            showDeleteDialog = false
            showForwardDialog = false
        },
        // Confirming one clears the rows it acted on: the search no longer applies.
        onSelectionDialogConfirm = {
            showClearDialog = false
            showDeleteDialog = false
            showForwardDialog = false
            search.clear()
        },
    )

    if (importState.isImporting && importState.importProgress != null) {
        val progress = importState.importProgress!!
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.history_importing)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (progress.total > 0) {
                        LinearProgressIndicator(
                            progress = { progress.current.toFloat() / progress.total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            // The plural is selected by the total (the "of N" part); the
                            // args are current then total to keep %1$d/%2$d as before.
                            text =
                                pluralStringResource(
                                    R.plurals.history_importing_progress,
                                    progress.total,
                                    progress.current,
                                    progress.total,
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }
            },
            confirmButton = { },
        )
    }

    if (importState.showImportDialog) {
        ImportTimeFrameSheet(
            selectedTimeFrame = importState.selectedTimeFrame,
            customImportDate = importState.customImportDate,
            onDismiss = { viewModel.importFlow.dismissImportDialog() },
            onTimeFrameSelected = { viewModel.importFlow.selectTimeFrame(it) },
            onConfirm = { viewModel.importFlow.confirmImport() },
            onCustomDateSelected = { viewModel.importFlow.setCustomImportDate(it) },
            context = context,
        )
    }

    // Over the sheet: the import is held pending the user's explicit yes/no.
    if (importState.showRetentionConfirm) {
        RetentionImportConfirmDialog(
            label = importState.retentionConfirmLabel.orEmpty(),
            onDismiss = { viewModel.importFlow.cancelRetentionImport() },
            onConfirm = { viewModel.importFlow.confirmRetentionImport() },
        )
    }

    Scaffold(
        topBar = {
            if (state.list.selectionMode) {
                SelectionTopBar(
                    selectedCount = state.list.selectedIds.size,
                    onCancel = { viewModel.exitSelectionMode() },
                    onFirstAction = { showForwardDialog = true },
                    onSecondAction = { showDeleteDialog = true },
                    firstResId = R.string.action_send,
                    secondResId = R.string.history_delete_selected,
                    secondIcon = Icons.Default.Delete,
                    countResId = R.string.selection_count,
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.history_title_count, state.list.paged.smsCount)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        if (state.list.paged.smsCount > 0) {
                            SearchToggleButton(
                                onToggle = search.toggle,
                            )
                        }
                        IconButton(
                            onClick = { viewModel.importFlow.showImportDialog() },
                            enabled = !importState.isImporting,
                        ) {
                            Icon(
                                SyncArrowDown,
                                contentDescription = stringResource(R.string.history_import_content_desc),
                                tint =
                                    if (importState.isImporting) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                            )
                        }
                        IconButton(
                            onClick = { showClearDialog = true },
                            enabled = state.list.paged.smsCount > 0 && !importState.isImporting,
                        ) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = stringResource(R.string.history_clear_content_desc),
                            )
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
            // While the auto-clear retention is on the banner stays visible even with an
            // empty history: it is the explanation of an (auto-cleared) empty list.
            if (state.autoCleanupRetentionDays > 0) {
                Banner(
                    icon = Icons.Filled.CleaningServices,
                    title = stringResource(R.string.history_auto_clear_on),
                    description =
                        stringResource(
                            R.string.history_auto_clear_desc,
                            stringResource(PeriodPreset.labelResFor(state.autoCleanupRetentionDays)),
                        ),
                    buttonText = stringResource(R.string.history_run_cleanup_now),
                    onButtonClick = { viewModel.runCleanupNow() },
                    buttonTestTag = "run_cleanup_now",
                )
            }

            if (search.isSearchMode) {
                SmsSearchField(
                    query = search.rawQuery,
                    onQueryChange = search.onRawQueryChange,
                    onClear = search.clear,
                )
            }

            SmsListContent(
                list = state.list,
                searchQuery = search.query,
                onSmsClicked = onSmsClicked,
                onLongPress = { id -> viewModel.enterSelectionMode().also { viewModel.toggleSelection(id) } },
                onToggleSelection = viewModel::toggleSelection,
                onLoadMore = viewModel::loadMore,
            ) {
                CenteredMessage(R.string.history_no_sms_yet) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.importFlow.showImportDialog() },
                        enabled = !importState.isImporting,
                    ) {
                        Text(stringResource(R.string.history_import_btn))
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryResultDialogs(
    state: HistoryUiState,
    importState: ImportUiState,
    viewModel: HistoryViewModel,
    showClearDialog: Boolean,
    showDeleteDialog: Boolean,
    showForwardDialog: Boolean,
    onSelectionDialogDismiss: () -> Unit,
    onSelectionDialogConfirm: () -> Unit,
) {
    if (showClearDialog) {
        TextConfirmDialog(
            titleResId = R.string.history_clear_title,
            detail = stringResource(R.string.history_clear_confirm),
            confirmResId = R.string.history_clear_confirm_btn,
            onConfirm = {
                viewModel.clearHistory()
                onSelectionDialogConfirm()
            },
            onDismiss = onSelectionDialogDismiss,
        )
    }

    if (showDeleteDialog) {
        TextConfirmDialog(
            titleResId = R.string.history_delete_selected_title,
            detail = pluralStringResource(R.plurals.history_delete_confirm, state.list.selectedIds.size, state.list.selectedIds.size),
            confirmResId = R.string.action_delete,
            onConfirm = {
                viewModel.deleteSelected()
                onSelectionDialogConfirm()
            },
            onDismiss = onSelectionDialogDismiss,
        )
    }

    if (showForwardDialog) {
        TextConfirmDialog(
            titleResId = R.string.forward_messages_title,
            detail = pluralStringResource(R.plurals.forward_messages_confirm, state.list.selectedIds.size, state.list.selectedIds.size),
            confirmResId = R.string.action_send,
            onConfirm = {
                viewModel.forwardSelected()
                onSelectionDialogConfirm()
            },
            onDismiss = onSelectionDialogDismiss,
        )
    }

    if (state.cleanupDeletedCount != null) {
        TextConfirmDialog(
            titleResId = R.string.history_cleanup_complete,
            detail = stringResource(R.string.history_cleanup_deleted, state.cleanupDeletedCount),
            confirmResId = R.string.action_done,
            onConfirm = { viewModel.clearCleanupResult() },
            onDismiss = { viewModel.clearCleanupResult() },
            showCancel = false,
        )
    }

    // Mutually exclusive: an error (if any) takes precedence over the success summary,
    // so the two result dialogs can never stack.
    if (importState.importError != null) {
        TextConfirmDialog(
            titleResId = R.string.history_import_failed,
            detail = importState.importError,
            confirmResId = R.string.action_done,
            onConfirm = { viewModel.importFlow.clearImportResult() },
            onDismiss = { viewModel.importFlow.clearImportResult() },
            showCancel = false,
        )
    } else if (importState.importResult != null) {
        val result = importState.importResult
        TextConfirmDialog(
            titleResId = R.string.history_import_complete,
            detail =
                buildList {
                    add(stringResource(R.string.history_imported, result.importedCount))
                    add(stringResource(R.string.history_skipped, result.skippedCount))
                    if (result.errorCount > 0) {
                        add(stringResource(R.string.history_errors, result.errorCount))
                    }
                }.joinToString("\n"),
            confirmResId = R.string.action_done,
            onConfirm = { viewModel.importFlow.clearImportResult() },
            onDismiss = { viewModel.importFlow.clearImportResult() },
            showCancel = false,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportTimeFrameSheet(
    selectedTimeFrame: ImportTimeFrame,
    customImportDate: Long?,
    onDismiss: () -> Unit,
    onTimeFrameSelected: (ImportTimeFrame) -> Unit,
    onConfirm: () -> Unit,
    onCustomDateSelected: (Long) -> Unit,
    context: android.content.Context,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Text(
            text = stringResource(R.string.import_dialog_title),
            style = MaterialTheme.typography.titleMedium,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            textAlign = TextAlign.Center,
        )
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))

        val timeFrames = ImportTimeFrame.entries.filter { it != ImportTimeFrame.SINCE_DATE }
        timeFrames.forEach { timeFrame ->
            RadioButtonTimeFrameRow(
                timeFrame = timeFrame,
                isSelected = selectedTimeFrame == timeFrame,
                onClick = { onTimeFrameSelected(timeFrame) },
            )
        }

        var showDatePicker by remember { mutableStateOf(false) }
        if (showDatePicker) {
            val datePickerState = rememberDatePickerState(initialSelectedDateMillis = DateUtil.utcMidnightOfTodayMillis())
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        datePickerState.selectedDateMillis?.let { onCustomDateSelected(DateUtil.localMidnightOfUtcCalendarDateMillis(it)) }
                        showDatePicker = false
                    }) {
                        Text(stringResource(R.string.action_done))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            ) {
                DatePicker(
                    state = datePickerState,
                    showModeToggle = false,
                )
            }
        }

        RadioButtonTimeFrameRow(
            timeFrame = ImportTimeFrame.SINCE_DATE,
            isSelected = selectedTimeFrame == ImportTimeFrame.SINCE_DATE,
            onClick = {
                onTimeFrameSelected(ImportTimeFrame.SINCE_DATE)
                showDatePicker = true
            },
            trailingIcon = Icons.Default.CalendarToday,
            trailingText = customImportDate?.let { DateUtil.formatDate(context, it) },
        )

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.import_confirm))
            }
        }
    }
}

@Composable
private fun RetentionImportConfirmDialog(
    label: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    TextConfirmDialog(
        titleResId = R.string.import_retention_title,
        detail = stringResource(R.string.import_retention_warning, label),
        confirmResId = R.string.import_confirm,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun RadioButtonTimeFrameRow(
    timeFrame: ImportTimeFrame,
    isSelected: Boolean,
    onClick: () -> Unit,
    trailingIcon: ImageVector? = null,
    trailingText: String? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onClick,
        )
        Spacer(modifier = Modifier.width(12.dp))
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(timeFrame.resId),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
        }
        if (trailingIcon != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
