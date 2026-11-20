package app.kotowski.sms2email.ui.smsdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.SmsUiModel
import app.kotowski.sms2email.ui.components.SystemScrollStateScrollIndicator
import app.kotowski.sms2email.ui.components.TextConfirmDialog
import app.kotowski.sms2email.ui.smslist.cardNeedsLiveUpdates
import app.kotowski.sms2email.ui.smslist.detailStatusText
import app.kotowski.sms2email.ui.smslist.rememberNowTicker
import app.kotowski.sms2email.ui.smslist.smsStatusColor
import app.kotowski.sms2email.util.DateUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsDetailScreen(
    smsId: Long,
    onNavigateBack: () -> Unit,
    viewModel: SmsDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    // The row is loaded by id from the DB (the list window is capped and does not
    // necessarily contain it), observed live so a status transition while open is
    // reflected. Loading while the initial observation is in flight, NotFound when
    // the id has no row at all (stale id / cleaned up before the row ever rendered).
    val detailState by viewModel.detailState.collectAsState()
    val detail = (detailState as? SmsDetailState.Loaded)?.data
    val sms = detail?.sms
    // Whether the row has ever been shown: a NotFound before the row was shown is the
    // "no longer available" screen, a NotFound after the row was shown means it was
    // deleted while the detail was open (e.g. by the cleanup worker) and the screen
    // must go back, not spin forever.
    var hasLoaded by remember { mutableStateOf(false) }
    var contactName by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val isSingleSim = !state.displaySimCards
    val simNames = detail?.simNames ?: emptyMap()
    val simSlots = detail?.simSlots ?: emptyMap()

    LaunchedEffect(smsId) {
        viewModel.loadSms(smsId)
    }

    LaunchedEffect(detailState) {
        if (detailState is SmsDetailState.Loaded) hasLoaded = true
        if (detailShouldNavigateBack(hasLoaded, detailState)) onNavigateBack()
    }

    LaunchedEffect(sms?.phoneNumber) {
        sms?.phoneNumber?.let {
            contactName = viewModel.resolveContactName(it)
        }
    }

    if (showDeleteDialog) {
        sms?.let { smsData ->
            TextConfirmDialog(
                titleResId = R.string.sms_detail_delete_message,
                detail = stringResource(R.string.sms_detail_delete_confirm),
                confirmResId = R.string.action_delete,
                onConfirm = {
                    showDeleteDialog = false
                    viewModel.deleteRow(smsData.id)
                },
                onDismiss = { showDeleteDialog = false },
            )
        }
    }

    if (sms == null) {
        // Both pre-content states keep the top bar up so the back button is always
        // reachable: Loading while the row is in flight (a row deleted while the
        // detail is open pops the screen via detailShouldNavigateBack instead of
        // leaving the spinner behind), NotFound when the id has no row at all.
        if (detailState is SmsDetailState.NotFound) {
            DetailPlaceholder(onNavigateBack) {
                Text(
                    text = stringResource(R.string.sms_detail_no_longer_available),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            DetailPlaceholder(onNavigateBack) {
                CircularProgressIndicator()
            }
        }
    }

    sms?.let { smsData ->
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.sms_detail_message_details)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        val actions = detailActions(smsData.status)
                        if (actions.sending) {
                            // The spinner stands in for the forward button for the duration of the send
                            val sendingLabel = stringResource(R.string.history_status_sending)
                            Box(
                                modifier =
                                    Modifier
                                        .size(48.dp)
                                        .semantics { contentDescription = sendingLabel },
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                        if (actions.send) {
                            IconButton(
                                onClick = { viewModel.forwardRow(smsData) },
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(R.string.action_send),
                                )
                            }
                        }
                        if (actions.cancel) {
                            IconButton(
                                onClick = { viewModel.cancelRow(smsData) },
                            ) {
                                Icon(Icons.Default.Cancel, contentDescription = stringResource(R.string.sms_detail_cancel_content_desc))
                            }
                        }
                        if (actions.delete) {
                            IconButton(
                                onClick = { showDeleteDialog = true },
                                enabled = !actions.sending,
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SmsMessageCard(smsData.messageBody)

                    SmsDetailsCard(
                        sms = smsData,
                        contactName = contactName,
                        isSingleSim = isSingleSim,
                        simNames = simNames,
                        simSlots = simSlots,
                        hasInternet = state.hasInternet,
                    )
                }

                SystemScrollStateScrollIndicator(
                    scrollState = scrollState,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

@Composable
private fun SmsMessageCard(messageBody: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.sms_detail_message),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = messageBody,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// hasInternet comes from the VM's collected connectivity flow, never a synchronous
// read in composition.
@Composable
private fun SmsDetailsCard(
    sms: SmsUiModel,
    contactName: String?,
    isSingleSim: Boolean,
    simNames: Map<Int, String>,
    simSlots: Map<Int, Int>,
    hasInternet: Boolean,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.sms_detail_details),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))

            DetailRow(
                label = stringResource(R.string.sms_detail_from),
                value = contactName ?: sms.phoneNumber,
            )

            DetailRow(
                label = stringResource(R.string.sms_detail_date),
                value = DateUtil.formatDateTime(context, sms.timeReceived),
            )

            sms.timeLastSendAttempt?.let { sentAt ->
                if (sms.status == SmsStatus.SENT) {
                    DetailRow(
                        label = stringResource(R.string.sms_detail_sent_at),
                        value = DateUtil.formatDateTime(context, sentAt),
                    )
                }
            }

            if (!isSingleSim) {
                // A missing SIM (or a SIM without a tray slot) shows the name plain.
                val unknownSim = stringResource(R.string.history_unknown_sim)
                val simName = simNames[sms.simId]
                val simSlot = simSlots[sms.simId]
                val simValue =
                    if (simName != null && simName != unknownSim && simSlot != null) {
                        stringResource(R.string.sms_detail_sim_with_slot, simName, simSlot)
                    } else {
                        simName ?: unknownSim
                    }
                DetailRow(
                    label = stringResource(R.string.sms_detail_sim),
                    value = simValue,
                )
            }

            val statusColor = smsStatusColor(sms.status, MaterialTheme.colorScheme)
            val statusText = detailStatusText(context, sms, hasInternet)
            DetailRow(
                label = stringResource(R.string.sms_detail_status),
                value = statusText,
                valueColor = statusColor,
            )

            if (sms.statusDetails != null &&
                (
                    sms.status == SmsStatus.FAILED || sms.status == SmsStatus.RETRYING ||
                        sms.status == SmsStatus.IGNORED
                )
            ) {
                DetailRow(
                    label = stringResource(R.string.sms_detail_status_details),
                    value = sms.statusDetails,
                    valueColor = statusDetailsValueColor(sms.status, MaterialTheme.colorScheme),
                )
            }

            if (sms.status == SmsStatus.RETRYING || sms.status == SmsStatus.FAILED) {
                DetailRow(
                    label = stringResource(R.string.sms_detail_retry_count),
                    value = sms.retryCount.toString(),
                )
                sms.timeLastSendAttempt?.let { attemptTime ->
                    DetailRow(
                        label = stringResource(R.string.sms_detail_last_attempt),
                        value = DateUtil.formatDateTime(context, attemptTime),
                    )
                }

                // The countdown row is only for rows that actually get a next attempt.
                // FAILED is terminal — no worker ever retries it — and only carries the
                // stale next-attempt timestamp left by the last retry window, so
                // rendering it there would show "soon" forever.
                if (cardNeedsLiveUpdates(sms.status)) {
                    sms.timeNextSendAttempt?.let { retryTime ->
                        if (hasInternet) {
                            val now = rememberNowTicker(true)
                            DetailRow(
                                label = stringResource(R.string.sms_detail_next_retry),
                                value = formatNextRetry(context, retryTime - now),
                                valueColor = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}

// Pre-content states keep the same top bar as the content so the back button is
// always reachable.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailPlaceholder(
    onNavigateBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sms_detail_message_details)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .padding(end = 16.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

// Whether the detail screen pops: only when the row was shown and then disappeared
// (deleted while the detail is open). A NotFound before the row was shown is the
// "no longer available" screen (with a back button), not a pop; Loading is a spinner.
internal fun detailShouldNavigateBack(
    hasLoaded: Boolean,
    detail: SmsDetailState,
): Boolean = hasLoaded && detail is SmsDetailState.NotFound

// The detail top bar's actions by status, derived from the live row so the icons swap
// as the row transitions while the screen is open: in-flight rows get Send/Cancel,
// SENDING gets the spinner in place of Send plus a grayed-out (disabled) Delete — let
// it finish — and terminal rows get Send/Delete. An enabled Delete never reaches an
// in-flight row from the UI; the DAO's status filter is the backstop.
internal data class DetailActions(
    val send: Boolean,
    val cancel: Boolean,
    val delete: Boolean,
    val sending: Boolean = false,
)

internal fun detailActions(status: SmsStatus): DetailActions =
    when (status) {
        SmsStatus.PENDING,
        SmsStatus.RETRYING,
        -> DetailActions(send = true, cancel = true, delete = false)

        SmsStatus.SENDING -> DetailActions(send = false, cancel = false, delete = true, sending = true)

        else -> DetailActions(send = true, cancel = false, delete = true)
    }

// The status details row: ignored messages wear the secondary tone, everything else
// (failed, retrying) stays in the regular text color — the red status label above
// already marks the failure, the reason itself is not an error.
internal fun statusDetailsValueColor(
    status: SmsStatus,
    colorScheme: ColorScheme,
): Color =
    if (status == SmsStatus.IGNORED) {
        colorScheme.secondary
    } else {
        colorScheme.onSurface
    }

// The soon label when nothing remains; otherwise the shared localized duration (its
// units come from the duration_* strings, never from code).
internal fun formatNextRetry(
    context: android.content.Context,
    remainingMs: Long,
): String {
    if (remainingMs <= 0) {
        return context.getString(R.string.sms_detail_next_retry_soon)
    }

    return context.getString(R.string.sms_detail_next_retry_in, DateUtil.formatDurationMillis(context, remainingMs))
}
