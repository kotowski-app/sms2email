package app.kotowski.sms2email.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.StatusUpdate
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.util.Logger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class RetryWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val smsRepository: SmsRepository,
        private val prefs: EncryptedPrefs,
        private val retryScheduler: SmsRetryScheduler,
        private val exactWakeArmer: ExactWakeArmer,
    ) : CoroutineWorker(appContext, params) {
        private val tag = "RetryWorker"

        companion object {
            const val WORK_NAME = "periodic_retry_stuck"

            // The one-shot twin the exact-alarm wake enqueues when the periodic tick is
            // not live: same doWork, no period.
            const val WORK_NAME_ONCE = "retry_sweep_once"

            // The period is the caller's (the user setting, reconciled by the
            // BackgroundStateReconciler): a KEEP re-enqueue would otherwise keep the
            // stale period, so the caller cancels first when it changes.
            fun enqueuePeriodic(
                context: Context,
                intervalMinutes: Long,
            ) {
                val workRequest =
                    PeriodicWorkRequestBuilder<RetryWorker>(intervalMinutes, TimeUnit.MINUTES)
                        .build()
                WorkManager
                    .getInstance(context)
                    .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, workRequest)
            }

            fun enqueueOnce(context: Context) {
                WorkManager
                    .getInstance(context)
                    .enqueueUniqueWork(
                        WORK_NAME_ONCE,
                        ExistingWorkPolicy.REPLACE,
                        OneTimeWorkRequestBuilder<RetryWorker>().build(),
                    )
            }
        }

        override suspend fun doWork(): Result {
            // A sweep while forwarding is off must do nothing: it would keep marking
            // stuck rows FAILED after the retry period even though the user asked to
            // stop sending. (The BackgroundStateReconciler cancels the sweep on the
            // flip; this guard covers a tick that already started before it.)
            if (!prefs.load().isForwardingEnabled) {
                // An already-enqueued one-shot (WORK_NAME_ONCE) reaches here too; warning
                // level, like the twin guards in the send workers.
                Logger.w(tag, "doWork: forwarding disabled, skipping sweep")
                return Result.success()
            }
            if (
                EmailBatchWorker.hasLiveBatchWork(
                    WorkManager.getInstance(applicationContext),
                    tag,
                    "doWork: batch work state query failed, skipping sweep this tick",
                )
            ) {
                // A live batch owns in-flight SENDING rows; flipping them mid-batch would let the
                // sweep re-send a message the batch is about to send; re-sweep next tick.
                // Debug: repeat standby while one batch (up to 400 ids) spans ticks, bounded by the SMTP send timeout.
                Logger.d(tag, "doWork: live batch work present, skipping sweep")
                armNextTick()
                return Result.success()
            }

            val now = System.currentTimeMillis()
            val ids = mutableListOf<Long>()

            val staleSending = smsRepository.getStaleSending(now - AppConfig.RETRY_SENDING_STALE_MS)
            for (row in staleSending) {
                val recovered =
                    smsRepository.transitionSmsStatus(
                        id = row.id,
                        expectedStatuses = listOf(SmsStatus.SENDING),
                        status = SmsStatus.RETRYING,
                        update =
                            StatusUpdate(
                                statusDetails = applicationContext.getString(R.string.sms_status_send_interrupted),
                                retryCount = row.retryCount + 1,
                                timeLastSendAttempt = now,
                                timeNextSendAttempt = now,
                            ),
                    )
                if (recovered) {
                    ids.add(row.id)
                } else {
                    Logger.w(tag, "doWork: stale SENDING recovery lost race, smsId=${row.id}")
                }
            }

            val dueRetrying = smsRepository.getDueRetrying(now)
            for (row in dueRetrying) {
                // The terminal FAILED is logged by the scheduler's winning transition.
                if (retryScheduler.failIfExpired(row.id, row.timeFirstSendAttempt, row.retryCount, listOf(SmsStatus.RETRYING))) {
                    continue
                }
                ids.add(row.id)
            }

            val stalePending = smsRepository.getStalePending(now - AppConfig.RETRY_PENDING_STALE_MS)
            for (row in stalePending) {
                ids.add(row.id)
            }

            val batchIds = ids.distinct().take(AppConfig.MAX_BATCH_IDS)
            if (batchIds.isNotEmpty()) {
                Logger.i(tag, "doWork: enqueueing recovery batch, count=${batchIds.size}")
                EmailBatchWorker.enqueue(applicationContext, batchIds)
            }
            armNextTick()
            return Result.success()
        }

        // The next tick's exact-wake-up token: armed from this run, on every exit that
        // keeps the sweep running (the forwarding-off exit returns before it — the
        // reconciler owns that state). The periodic schedule itself stays WorkManager's,
        // this is only the additional token; the armer no-ops while its gate is off.
        private fun armNextTick() {
            val intervalMinutes = prefs.load().retrySweepIntervalMinutes
            if (intervalMinutes > 0) {
                exactWakeArmer.armSweepAlarm(System.currentTimeMillis() + intervalMinutes * 60_000L)
            }
        }
    }
