package app.kotowski.sms2email.work

import android.content.Context
import androidx.work.WorkManager
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.Sms
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.StatusUpdate
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class SmsRetryScheduler(
    @ApplicationContext private val appContext: Context,
    private val smsRepository: SmsRepository,
    private val prefs: EncryptedPrefs,
    private val exactWakeArmer: ExactWakeArmer,
) {
    private val tag = "SmsRetryScheduler"

    // The single implementation of "the retry period ran out": the row waited since its
    // first attempt longer than the policy allows, so it goes terminal FAILED with the
    // exceeded message instead of burning another backoff. The caller passes the retry
    // count to persist — the three sites deliberately persist different counts (only
    // scheduleRetry counts the attempt that just failed). The winning transition logs the
    // terminal FAILED (error level); the CAS lets exactly one caller win, so the event is
    // logged exactly once.
    suspend fun failIfExpired(
        smsId: Long,
        firstSendAttempt: Long?,
        retryCount: Int,
        expected: List<SmsStatus>,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (!RetryPolicy.isExpiredByFirstAttempt(firstSendAttempt, now)) {
            return false
        }
        val failed =
            smsRepository.transitionSmsStatus(
                smsId,
                expected,
                SmsStatus.FAILED,
                RetryPolicy.failedWindow(
                    appContext.getString(R.string.error_retry_period_exceeded),
                    retryCount,
                    now,
                ),
            )
        if (failed) {
            Logger.e(tag, "failIfExpired: retry period exceeded, marking FAILED, smsId=$smsId")
        }
        return failed
    }

    suspend fun scheduleRetry(
        smsId: Long,
        smsRecord: Sms,
        errorMessage: String,
    ): Boolean {
        // Forwarding flipped off after this send was claimed: the one-shot park
        // (BackgroundStateReconciler) already ran and does not cover SENDING rows, so
        // park this one here with the same outcome and detail. Scheduling the retry
        // instead would strand the row RETRYING: the sweep is cancelled and the per-row
        // worker skips while off, so nothing would ever run it. Off wins over expiry,
        // like the reconciler's park: expiration is the sweep's call, and the sweep is off.
        if (!prefs.load().isForwardingEnabled) {
            val parked =
                smsRepository.transitionSmsStatus(
                    smsId,
                    listOf(SmsStatus.SENDING),
                    SmsStatus.IGNORED,
                    StatusUpdate(statusDetails = appContext.getString(R.string.sms_status_forwarding_stopped)),
                )
            Logger.i(
                tag,
                "scheduleRetry: forwarding disabled, parked smsId=$smsId as IGNORED instead of scheduling a retry (parked=$parked)",
            )
            exactWakeArmer.cancelRetryAlarm(smsId)
            return parked
        }

        val now = System.currentTimeMillis()
        val newRetryCount = smsRecord.retryCount + 1

        if (failIfExpired(smsId, smsRecord.timeFirstSendAttempt, newRetryCount, listOf(SmsStatus.SENDING))) {
            exactWakeArmer.cancelRetryAlarm(smsId)
            return true
        }

        val backoffMillis = RetryPolicy.computeBackoffWithJitter(newRetryCount)
        val transitioned =
            smsRepository.transitionSmsStatus(
                smsId,
                listOf(SmsStatus.SENDING),
                SmsStatus.RETRYING,
                RetryPolicy.retryWindow(
                    errorMessage,
                    newRetryCount,
                    smsRecord.timeFirstSendAttempt ?: now,
                    now,
                ),
            )
        if (transitioned) {
            EmailWorker.enqueueSend(appContext, smsId, backoffMillis)
            exactWakeArmer.rearmRetryAlarm(smsId, backoffMillis)
        }
        return transitioned
    }

    // A permanent send failure (5xx destination/policy rejection, bad SMTP credentials):
    // retrying cannot help, so the row goes straight to FAILED with the mapped reason and
    // no follow-up work. It stays in SmsStatus.MANUAL_FORWARDABLE, so a cleared condition
    // (e.g. a full mailbox) or fixed config is one manual re-forward away.
    suspend fun scheduleFailed(
        smsId: Long,
        smsRecord: Sms,
        errorMessage: String,
    ): Boolean {
        val now = System.currentTimeMillis()
        val failed =
            smsRepository.transitionSmsStatus(
                smsId,
                listOf(SmsStatus.SENDING),
                SmsStatus.FAILED,
                RetryPolicy.failedWindow(errorMessage, smsRecord.retryCount + 1, now),
            )
        if (failed) {
            exactWakeArmer.cancelRetryAlarm(smsId)
        }
        return failed
    }

    // A manual "forward" from the UI starts a fresh retry window regardless of the
    // previous attempt history: the retry count resets to 1 and the first-attempt
    // clock restarts, so expired messages can be retried from the UI. The park CAS must
    // accept the same statuses as the caller's forward CAS: the default
    // MANUAL_FORWARDABLE re-sends any of them (detail/history by design), a narrowed
    // caller (the queue) must not park a row that left its scope while selected.
    suspend fun scheduleFirstRetry(
        smsId: Long,
        errorMessage: String,
        expectedStatuses: List<SmsStatus> = SmsStatus.MANUAL_FORWARDABLE,
    ): Boolean {
        val now = System.currentTimeMillis()
        val backoffMillis = RetryPolicy.computeBackoffWithJitter(1)
        val transitioned =
            smsRepository.transitionSmsStatus(
                smsId,
                expectedStatuses,
                SmsStatus.RETRYING,
                RetryPolicy.retryWindow(errorMessage, 1, now, now),
            )
        if (transitioned) {
            EmailWorker.enqueueSend(appContext, smsId, backoffMillis)
            // The fresh 10 s first-attempt backoff (the 5 s base, attempt 1) is under the
            // arming threshold, so this cancels any stale token from the previous window
            // and arms nothing.
            exactWakeArmer.rearmRetryAlarm(smsId, backoffMillis)
        }
        return transitioned
    }

    // A successful send proves the SMTP path is healthy: re-fire the queued rows right
    // away instead of letting them wait out their backoffs (the cooldown bounds a
    // flapping server). The rows are independent unique works, so drain in parallel
    // rather than blocking this worker thread for the whole queue.
    suspend fun drainAfterSuccess(sentSmsId: Long) {
        val now = System.currentTimeMillis()
        if (now - prefs.lastDrainAtMillis < AppConfig.SUCCESS_DRAIN_COOLDOWN_MS) return
        exactWakeArmer.cancelRetryAlarm(sentSmsId)
        val workManager = WorkManager.getInstance(appContext)
        val ids = smsRepository.getRetryingIds().filter { it != sentSmsId }
        val fired =
            coroutineScope {
                val pending =
                    ids.map { id ->
                        async {
                            if (EmailWorker.hasEnqueuedWorkForSend(workManager, id, tag)) {
                                EmailWorker.enqueueSend(appContext, id)
                                1
                            } else {
                                0
                            }
                        }
                    }
                pending.awaitAll().sum()
            }
        prefs.lastDrainAtMillis = now
        if (fired > 0) {
            Logger.i(tag, "drainAfterSuccess: re-fired $fired rows after smsId=$sentSmsId")
        }
    }
}
