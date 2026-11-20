package app.kotowski.sms2email.ui.smslist

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.SmsUiModel
import app.kotowski.sms2email.util.DateUtil

// The status→display mapping is defined exactly once here so the list cards and the
// detail screen cannot drift apart; a new status is one branch in each of the two
// mappings below and needs no change in either screen.

fun smsStatusColor(
    status: SmsStatus,
    scheme: ColorScheme,
): Color =
    when (status) {
        SmsStatus.SENT -> scheme.primary
        SmsStatus.PENDING -> scheme.tertiary
        SmsStatus.SENDING -> scheme.tertiary
        SmsStatus.RETRYING -> scheme.tertiary
        SmsStatus.FAILED -> scheme.error
        SmsStatus.IGNORED -> scheme.secondary
        SmsStatus.IMPORTED -> scheme.onSurfaceVariant
    }

fun smsStatusLabelRes(status: SmsStatus): Int =
    when (status) {
        SmsStatus.SENT -> R.string.history_status_sent
        SmsStatus.PENDING -> R.string.history_status_pending
        SmsStatus.SENDING -> R.string.history_status_sending
        SmsStatus.RETRYING -> R.string.history_status_retrying
        SmsStatus.FAILED -> R.string.history_status_failed
        SmsStatus.IGNORED -> R.string.history_status_ignored
        SmsStatus.IMPORTED -> R.string.history_status_imported
    }

// The list card's status line: SENT carries the "Forwarded at <ts>" timestamp here
// (the list has no separate sent-at row) and RETRYING shows the live countdown.
fun listStatusText(
    context: Context,
    sms: SmsUiModel,
    hasInternet: Boolean,
    nowMillis: Long,
): String =
    when (sms.status) {
        SmsStatus.SENT -> {
            val timestamp = sms.timeLastSendAttempt ?: sms.timeReceived
            context.getString(R.string.history_status_forwarded_at, DateUtil.formatDateTime(context, timestamp))
        }

        SmsStatus.RETRYING -> {
            if (!hasInternet) {
                context.getString(R.string.history_status_waiting_for_internet)
            } else {
                val nextAttempt = sms.timeNextSendAttempt
                if (nextAttempt != null && nextAttempt - nowMillis > 0) {
                    context.getString(R.string.history_status_retrying_in, DateUtil.formatDurationMillis(context, nextAttempt - nowMillis))
                } else {
                    context.getString(R.string.history_status_retrying)
                }
            }
        }

        else -> {
            context.getString(smsStatusLabelRes(sms.status))
        }
    }

// The detail screen's status value: plain labels everywhere; only a RETRYING row
// without internet shows the waiting label (its countdown is a separate detail row).
fun detailStatusText(
    context: Context,
    sms: SmsUiModel,
    hasInternet: Boolean,
): String =
    if (sms.status == SmsStatus.RETRYING && !hasInternet) {
        context.getString(R.string.history_status_waiting_for_internet)
    } else {
        context.getString(smsStatusLabelRes(sms.status))
    }
