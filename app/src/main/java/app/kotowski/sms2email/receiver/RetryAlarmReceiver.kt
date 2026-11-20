package app.kotowski.sms2email.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import app.kotowski.sms2email.util.Logger
import app.kotowski.sms2email.work.EmailWorker
import kotlinx.coroutines.runBlocking

// The exact-wake-up token of one row's retry: fires at the row's due time and re-wakes
// the same per-row send work the WorkManager wake would run, unless that work is live
// (a same-instant REPLACE would cancel it and lose the attempt; the check lives in
// EmailWorker). It does nothing else: no engine logic here (the 10-second receiver
// budget is never at risk), and the per-row work keeps running as the backup — the
// claim CAS absorbs both wakes arriving for the same row.
class RetryAlarmReceiver : BroadcastReceiver() {
    private val tag = "RetryAlarmReceiver"

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val smsId = intent.getLongExtra(SMS_ID_KEY, -1)
        if (smsId < 0) {
            Logger.w(tag, "alarm fired without an sms id, ignoring")
            return
        }
        Logger.i(tag, "exact alarm fired smsId=$smsId")
        try {
            // The blocking read is safe in the receiver: a local in-memory/SQLite read
            // (no IPC), well inside onReceive's 10-second budget. A failed read degrades
            // to "live work" (skipped wake): a missed wake self-heals (the WorkManager
            // work is the primary wake), a wrong wake would REPLACE-cancel a possibly
            // running send.
            val live = runBlocking { EmailWorker.hasLiveWorkForSend(WorkManager.getInstance(context), smsId, tag) }
            if (live) {
                Logger.i(tag, "smsId=$smsId send work live, exact-alarm wake skipped")
                return
            }
            runBlocking { EmailWorker.enqueueSend(context, smsId) }
        } catch (e: Exception) {
            if (e is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            Logger.w(tag, "Failed to wake the send work: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    companion object {
        const val SMS_ID_KEY = "retry_alarm_sms_id"
    }
}
