package app.kotowski.sms2email.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.kotowski.sms2email.util.Logger
import app.kotowski.sms2email.work.RetryWorker
import java.util.concurrent.TimeUnit

// The exact-wake-up token of the recovery sweep: fires at the scheduled tick and wakes
// the sweep through a one-shot work unless the periodic tick is already live. It does
// nothing else: no engine logic here (the 10-second receiver budget is never at risk),
// and the periodic work keeps running as the backup — the sweep's CAS transitions
// absorb both wakes arriving for the same tick.
class SweepAlarmReceiver : BroadcastReceiver() {
    private val tag = "SweepAlarmReceiver"

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        Logger.i(tag, "exact alarm fired")
        try {
            // The blocking read is safe in the receiver: a local in-memory/SQLite read
            // (no IPC) with a 2 s bound, well inside onReceive's 10-second budget. A
            // failed read degrades to "live sweep" (skipped wake): a missed wake
            // self-heals on the next tick, a wrong wake just re-runs the idempotent
            // sweep.
            val states = WorkManager.getInstance(context).getWorkInfosForUniqueWork(RetryWorker.WORK_NAME).get(2, TimeUnit.SECONDS)
            if (states.any { it.state == WorkInfo.State.RUNNING }) {
                Logger.i(tag, "periodic sweep live, exact-alarm wake skipped")
                return
            }
            RetryWorker.enqueueOnce(context)
        } catch (e: Exception) {
            if (e is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            Logger.w(tag, "Failed to check the sweep state, skipping wake: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }
}
