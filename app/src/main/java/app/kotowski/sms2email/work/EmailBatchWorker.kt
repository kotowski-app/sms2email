package app.kotowski.sms2email.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.Sms
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.service.BatchEmailItem
import app.kotowski.sms2email.service.EmailResult
import app.kotowski.sms2email.service.EmailService
import app.kotowski.sms2email.system.NetworkUtil
import app.kotowski.sms2email.util.Logger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

const val BATCH_IDS_KEY = "batch_ids"

@HiltWorker
class EmailBatchWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val smsRepository: SmsRepository,
        private val emailService: EmailService,
        private val sendExecutor: SmsSendExecutor,
        private val retryScheduler: SmsRetryScheduler,
        private val prefs: EncryptedPrefs,
    ) : CoroutineWorker(context, params) {
        private val tag = "EmailBatchWorker"

        companion object {
            const val WORK_NAME = "email_batch_send"
            const val BATCH_TAG = "email_batch"

            suspend fun enqueue(
                appContext: Context,
                smsIds: List<Long>,
            ) {
                if (smsIds.isEmpty()) return
                val workManager = WorkManager.getInstance(appContext)
                val requests =
                    smsIds.chunked(AppConfig.MAX_BATCH_IDS).map { chunk ->
                        OneTimeWorkRequestBuilder<EmailBatchWorker>()
                            .setConstraints(
                                Constraints
                                    .Builder()
                                    .setRequiredNetworkType(NetworkType.CONNECTED)
                                    .build(),
                            ).setInputData(workDataOf(BATCH_IDS_KEY to chunk.joinToString(",")))
                            .addTag(BATCH_TAG)
                            .build()
                    }
                // APPEND while any batch work is live: REPLACE would cancel a running batch,
                // and a cancelled batch can strand an in-flight item SENDING (250 OK, not yet
                // committed) that the sweep later re-sends -> duplicate. With no live work,
                // REPLACE drops any stale terminal chain.
                val policy =
                    if (
                        hasLiveBatchWork(
                            workManager,
                            "EmailBatchWorker",
                            "enqueue: batch work state query failed, treating batch as live",
                        )
                    ) {
                        ExistingWorkPolicy.APPEND
                    } else {
                        ExistingWorkPolicy.REPLACE
                    }
                // Chain all chunks under the unique name so only one batch (one SMTP
                // connection) runs at a time. then() returns a NEW continuation (the
                // original is unchanged), so the variable is reassigned and the LAST
                // one is enqueued: enqueue() then registers the whole parent chain with
                // each chunk depending on the previous one.
                var continuation = workManager.beginUniqueWork(WORK_NAME, policy, requests.first())
                for (request in requests.drop(1)) {
                    continuation = continuation.then(request)
                }
                continuation.enqueue()
            }

            // Shared batch-liveness rule (also used by RetryWorker). A query failure must
            // look live: never REPLACE-cancel a possibly running batch.
            suspend fun hasLiveBatchWork(
                workManager: WorkManager,
                logTag: String,
                failureMessage: String,
            ): Boolean {
                val workInfos =
                    WorkInfoQuery.byTag(
                        workManager,
                        BATCH_TAG,
                        logTag,
                        failureMessage,
                    ) ?: return true
                return workInfos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
            }
        }

        override suspend fun doWork(): Result {
            if (!prefs.load().isForwardingEnabled) {
                Logger.w(tag, "doWork: forwarding disabled, not sending batch")
                return Result.success()
            }
            val idsString = inputData.getString(BATCH_IDS_KEY) ?: ""
            val ids = idsString.split(",").mapNotNull { it.trim().toLongOrNull() }.distinct()

            if (ids.isEmpty()) {
                Logger.w(tag, "doWork: empty batch IDs, returning success")
                return Result.success()
            }

            val smsRecords = smsRepository.getSmsByIds(ids)
            if (smsRecords.isEmpty()) {
                Logger.w(tag, "doWork: no records found, returning success")
                return Result.success()
            }

            val smsMap = smsRecords.associateBy { it.id }
            val claimedIds = mutableListOf<Long>()
            val batchItems = mutableListOf<BatchEmailItem>()
            val now = System.currentTimeMillis()

            for (sms in smsRecords) {
                if (sms.status == SmsStatus.SENT || sms.status == SmsStatus.IGNORED) continue
                if (sendExecutor.triage(sms) is SmsSendExecutor.TriageResult.Skipped) continue
                if (isRetryNotYetDue(sms, now)) continue
                if (!sendExecutor.claim(sms)) continue
                claimedIds.add(sms.id)
                val parts = sendExecutor.buildParts(sms)
                batchItems.add(BatchEmailItem(smsId = sms.id, subject = parts.subject, body = parts.body, json = parts.json))
            }

            if (batchItems.isEmpty()) {
                // Distinguishable from the empty-ids and no-records warnings: the records existed,
                // but every row was SENT/IGNORED, triaged away, not yet due, or lost its claim to a concurrent worker.
                Logger.i(tag, "doWork: nothing to send in batch, ids=${ids.size}")
                return Result.success()
            }

            // The queue's config gate: a config that fails the completeness rule can never
            // send, so every claimed row fails fast and permanent instead of burning the
            // retry window on empty-address errors.
            if (!sendExecutor.isSmtpConfigured()) {
                Logger.w(tag, "doWork: SMTP not configured, failing all claimed items permanently, ids=$claimedIds")
                val failure =
                    EmailResult.Failure(applicationContext.getString(R.string.error_smtp_not_configured), permanent = true)
                for (id in claimedIds) {
                    sendExecutor.finalizeSend(smsMap[id]!!, failure)
                }
                return Result.success()
            }

            NetworkUtil.checkNetwork(applicationContext)?.let { net ->
                Logger.w(tag, "doWork: network blocked, scheduling individual retries, error=$net")
                for (id in claimedIds) {
                    retryScheduler.scheduleRetry(id, smsMap[id]!!, net)
                }
                return Result.success()
            }

            // Re-check the flag at every item boundary: a toggle off mid-batch must stop the
            // in-flight batch at the next item instead of delivering every claimed row. The
            // service only sees a generic stop gate; the forwarding policy stays in this layer.
            val results =
                emailService.sendEmailBatch(
                    batchItems,
                    onItemResult = { smsId, result -> sendExecutor.finalizeSend(smsMap[smsId]!!, result) },
                    shouldContinue = { prefs.load().isForwardingEnabled },
                )

            for (id in claimedIds) {
                if (id !in results) {
                    sendExecutor.finalizeSend(
                        smsMap[id]!!,
                        EmailResult.Failure(applicationContext.getString(R.string.error_waiting_for_internet)),
                    )
                }
            }

            return Result.success()
        }

        // A RETRYING row is owned by its per-row email_send work until its next attempt is
        // due. The sweep's recovery batch must not steal it early: a stale per-row work that
        // fails first parks the row back RETRYING with a fresh (future) due time, and an
        // unconditional claim here would send it a second time in the same due window
        // (retryCount +2, one backoff step burned). PENDING rows never carry a future due
        // time, so they are unaffected. The due margin (AppConfig.BATCH_DUE_CHECK_SLACK_MS)
        // covers the two independent jitter draws of the stored due time and the per-row
        // worker's fire time.
        private fun isRetryNotYetDue(
            sms: Sms,
            now: Long,
        ): Boolean =
            sms.status == SmsStatus.RETRYING &&
                sms.timeNextSendAttempt != null &&
                sms.timeNextSendAttempt > now + AppConfig.BATCH_DUE_CHECK_SLACK_MS
    }
