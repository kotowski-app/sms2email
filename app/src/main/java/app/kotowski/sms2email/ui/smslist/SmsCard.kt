package app.kotowski.sms2email.ui.smslist

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.SmsUiModel
import app.kotowski.sms2email.ui.icons.NoSim
import app.kotowski.sms2email.ui.icons.slotIcon
import app.kotowski.sms2email.util.DateUtil
import kotlinx.coroutines.delay

// The list item shared by the History and Queue screens.
@Composable
fun SmsListCard(
    sms: SmsUiModel,
    selectionMode: Boolean,
    isSelected: Boolean,
    isSingleSim: Boolean,
    simNames: Map<Int, String>,
    simSlots: Map<Int, Int>,
    contactNames: Map<String, String>,
    onClick: (Long) -> Unit,
    onLongPress: (Long) -> Unit,
    onToggleSelection: (Long) -> Unit,
    hasInternet: Boolean,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (selectionMode) {
                        Modifier.clickable(onClick = { onToggleSelection(sms.id) })
                    } else {
                        Modifier.pointerInput(sms.id) {
                            detectTapGestures(
                                onTap = { onClick(sms.id) },
                                onLongPress = { onLongPress(sms.id) },
                            )
                        }
                    },
                ),
        colors =
            if (selectionMode) {
                CardDefaults.cardColors(
                    containerColor =
                        if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                )
            } else {
                CardDefaults.cardColors()
            },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            SmsCardBody(
                sms = sms,
                isSingleSim = isSingleSim,
                simNames = simNames,
                simSlots = simSlots,
                contactNames = contactNames,
                hasInternet = hasInternet,
            )
        }
    }
}

// The shared "live per-second value" idiom: returns the wall-clock millis and recomposes
// the caller once per second while `active`. The list card and the detail screen run it
// only behind a visible per-second value (the retry countdown); any other row would just
// re-compose every second for no visible change.
@Composable
fun rememberNowTicker(
    active: Boolean,
    intervalMs: Long = AppConfig.LIVE_TICK_INTERVAL_MS,
): Long {
    var now by remember(active) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            delay(intervalMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
private fun SmsCardBody(
    sms: SmsUiModel,
    isSingleSim: Boolean,
    simNames: Map<Int, String>,
    simSlots: Map<Int, Int>,
    contactNames: Map<String, String>,
    hasInternet: Boolean,
) {
    val context = LocalContext.current
    val displayName = contactNames[sms.phoneNumber] ?: sms.phoneNumber
    val dateStr = DateUtil.formatDateTime(context, sms.timeReceived)
    val statusColor = smsStatusColor(sms.status, MaterialTheme.colorScheme)

    val isRetrying = cardNeedsLiveUpdates(sms.status)
    val now = rememberNowTicker(isRetrying)

    val statusText = listStatusText(context, sms, hasInternet, now)

    val unknownSim = stringResource(R.string.history_unknown_sim)
    val simDisplayName = simNames[sms.simId] ?: unknownSim
    val simMissing = simDisplayName == unknownSim

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = displayName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = dateStr,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (!isSingleSim && sms.simId >= 0) {
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(
                imageVector = if (simMissing) NoSim else Icons.Default.SimCard,
                contentDescription = null,
                modifier = Modifier.size(14.dp).testTag("sms_sim_icon"),
            )
            simSlots[sms.simId]?.let { slot ->
                slotIcon(slot)?.let { icon ->
                    androidx.compose.material3.Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp).testTag("sms_slot_icon"),
                    )
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = simDisplayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = sms.messageBody,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )

    Spacer(modifier = Modifier.height(4.dp))
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIndicator(color = statusColor)
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
            )
        }
    }
}

@Composable
private fun CircleIndicator(color: Color) {
    Canvas(
        modifier = Modifier.size(8.dp),
    ) {
        drawCircle(color = color)
    }
}

// The only status that shows a live per-second value (the retry countdown), hence the
// only one that needs the 1s ticker running in a list card.
fun cardNeedsLiveUpdates(status: SmsStatus): Boolean = status == SmsStatus.RETRYING
