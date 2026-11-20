package app.kotowski.sms2email.ui

import android.content.Context
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.filter.FilterMode
import app.kotowski.sms2email.work.CancelOutcome
import app.kotowski.sms2email.work.DeleteOutcome
import app.kotowski.sms2email.work.ForwardOutcome

// The single outcome -> toast mapping for the manual SMS actions: the work layer
// (work.SmsActions) reports what happened, the screens show it. forwardOutcomeToasts
// returns the toast texts to show (already resolved, in the outcome's order), or an
// empty list for a fully successful action (silent); the cancel and delete helpers
// return the single toast's resource id, or null for the silent outcome.
//
// The single/multiple pick is a binary count check, deliberately NOT <plurals>: these
// sentences carry no number, and CLDR categories are wrong without one (Russian counts
// like 21 resolve to "one", and the genitive "many" form reads broken numberless).
// The one sentence that carries a number (the message-filter skip count) keeps the
// number out of the inflected part: the count stands alone in every locale (the
// inflection-neutral "Skipped: %1$d — reason" shape), so a single form reads for
// every count.

// A blocked forward row is one the user's send could not take: its sending is already
// in progress (the worker claimed it, or it was in flight the whole time) — the
// numberless pair. A filter-blocked row is one the message filter
// skipped (the reason is mode-dependent: what a skip means in each polarity); it is
// the one entry whose text carries a count, filled in here (the showTestResultToast
// precedent: the interpolation happens where a Context exists, before the toast call —
// ToastUtil's overloads take a ready string or a plain resId, no format args).
fun forwardOutcomeToasts(
    context: Context,
    outcome: ForwardOutcome,
): List<String> {
    if (outcome.forwardingOff) return listOf(context.getString(R.string.sms_forward_forwarding_off))
    val toasts = mutableListOf<String>()
    if (outcome.blocked > 0) {
        toasts +=
            if (outcome.blocked == 1) {
                context.getString(R.string.sms_forward_blocked_single)
            } else {
                context.getString(R.string.sms_forward_blocked_multiple)
            }
    }
    if (outcome.filterBlocked > 0) {
        val reasonRes =
            if (outcome.filterMode == FilterMode.FORWARD) {
                R.string.settings_filter_toast_no_match
            } else {
                R.string.settings_filter_toast_skipped
            }
        toasts += context.getString(reasonRes, outcome.filterBlocked)
    }
    return toasts
}

// cancelled == 0 reads as fully blocked: it covers both an empty fetch (nothing, or
// only in-flight rows) and a CAS that lost the race for every attempted row — it
// reads with the requested count, the only one the outcome carries then. A partial
// skip reads with the blocked count instead. requested == 0 is the empty queue
// (work.cancelQueue): nothing was cancelled, so it stays silent.
fun cancelOutcomeToast(outcome: CancelOutcome): Int? {
    if (outcome.cancelled == 0) {
        if (outcome.requested == 0) return null
        return if (outcome.requested == 1) R.string.sms_cancel_blocked_single else R.string.sms_cancel_blocked_multiple
    }
    if (outcome.blocked == 0) return null
    return if (outcome.blocked == 1) R.string.sms_cancel_blocked_single else R.string.sms_cancel_blocked_multiple
}

fun deleteOutcomeToast(outcome: DeleteOutcome): Int? = if (outcome.deleted < outcome.requested) R.string.sms_delete_blocked else null
