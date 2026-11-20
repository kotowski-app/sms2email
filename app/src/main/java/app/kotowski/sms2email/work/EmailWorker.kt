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
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.util.Logger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

const val SMS_ID_KEY = "sms_id"

// Handles both the initial send and all retries (scheduled via SmsRetryScheduler).
// Skips SENT/IGNORED so a stale retry work can never re-send a finished message.
@HiltWorker
class EmailWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val smsRepository: SmsRepository,
        private val sendExecutor: SmsSendExecutor,
        private val prefs: EncryptedPrefs,
    ) : CoroutineWorker(context, params) {
        private val tag = "EmailWorker"

        companion object {
            // APPEND while the per-row work for this sms is RUNNING: a REPLACE would cancel
            // the live work — most notably the worker scheduling this very retry, which would
            // cancel itself (the work records CANCELLED though the row is already finalized).
            // REPLACE still drops stale ENQUEUED and terminal chains (dedup) and is kept for
            // the legitimate cases (initial send, manual re-forward). The initial delay makes
            // the appended retry fire after the backoff exactly like the replaced one did.
            suspend fun enqueueSend(
                context: Context,
                smsId: Long,
                initialDelayMillis: Long = 0,
            ) {
                val workManager = WorkManager.getInstance(context)
                val name = workName(smsId)
                val policy =
                    if (isSendWorkRunning(workManager, name)) {
                        ExistingWorkPolicy.APPEND
                    } else {
                        ExistingWorkPolicy.REPLACE
                    }
                val builder =
                    OneTimeWorkRequestBuilder<EmailWorker>()
                        .setConstraints(
                            Constraints
                                .Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build(),
                        ).setInputData(workDataOf(SMS_ID_KEY to smsId))
                if (initialDelayMillis > 0) {
                    builder.setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
                }

                workManager.enqueueUniqueWork(
                    name,
                    policy,
                    builder.build(),
                )
            }

            fun workName(smsId: Long): String = "email_send_$smsId"

            // The exact-alarm wake's liveness rule. A query failure must look live: a
            // missed wake self-heals (the WorkManager work is the primary wake), a wrong
            // wake would REPLACE-cancel a possibly running send.
            suspend fun hasLiveWorkForSend(
                workManager: WorkManager,
                smsId: Long,
                logTag: String,
            ): Boolean {
                val workInfos =
                    WorkInfoQuery.forUniqueWork(
                        workManager,
                        workName(smsId),
                        logTag,
                        "send work state query failed, treating as live (smsId=$smsId)",
                    ) ?: return true
                return workInfos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
            }

            // The success drain's re-fire rule. A query failure skips the row (opposite
            // of hasLiveWorkForSend): a missed re-fire self-heals on the row's own due
            // time.
            suspend fun hasEnqueuedWorkForSend(
                workManager: WorkManager,
                smsId: Long,
                logTag: String,
            ): Boolean {
                val workInfos =
                    WorkInfoQuery.forUniqueWork(
                        workManager,
                        workName(smsId),
                        logTag,
                        "send work state query failed, skipping smsId=$smsId",
                    ) ?: return false
                return workInfos.any { it.state == WorkInfo.State.ENQUEUED }
            }

            // enqueueSend's APPEND/REPLACE rule: only RUNNING is live — a stale ENQUEUED
            // work must still be replaceable (dedup). A query failure must look live:
            // never REPLACE-cancel a possibly running work.
            private suspend fun isSendWorkRunning(
                workManager: WorkManager,
                name: String,
            ): Boolean {
                val workInfos =
                    WorkInfoQuery.forUniqueWork(
                        workManager,
                        name,
                        "EmailWorker",
                        "enqueueSend: work state query failed, treating as running",
                    ) ?: return true
                return workInfos.any { it.state == WorkInfo.State.RUNNING }
            }
        }

        override suspend fun doWork(): Result {
            val smsId = inputData.getLong(SMS_ID_KEY, -1)
            if (!prefs.load().isForwardingEnabled) {
                Logger.w(tag, "doWork: forwarding disabled, not sending smsId=$smsId")
                return Result.success()
            }
            if (smsId < 0) {
                Logger.w(tag, "doWork: invalid smsId=$smsId, returning success")
                return Result.success()
            }

            val smsRecord = smsRepository.getSmsById(smsId)
            if (smsRecord == null) {
                Logger.w(tag, "doWork: smsRecord not found in DB, smsId=$smsId, returning success")
                return Result.success()
            }

            if (smsRecord.status == SmsStatus.SENT || smsRecord.status == SmsStatus.IGNORED) {
                return Result.success()
            }

            when (sendExecutor.triage(smsRecord)) {
                is SmsSendExecutor.TriageResult.Skipped -> {
                    return Result.success()
                }

                is SmsSendExecutor.TriageResult.Proceed -> {
                }
            }

            val parts = sendExecutor.buildParts(smsRecord)
            when (val sendResult = sendExecutor.send(smsRecord, parts)) {
                is SmsSendExecutor.SendResult.NotClaimed -> {
                    Logger.w(tag, "doWork: claim lost, another owner handles this sms, smsId=$smsId, returning success")
                    return Result.success()
                }

                is SmsSendExecutor.SendResult.Outcome -> {
                    sendExecutor.finalizeSend(smsRecord, sendResult.result)
                    return Result.success()
                }
            }
        }
    }
