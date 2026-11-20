package app.kotowski.sms2email.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.util.Logger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class HistoryCleanupWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val smsRepository: SmsRepository,
        private val prefs: EncryptedPrefs,
    ) : CoroutineWorker(appContext, params) {
        companion object {
            const val WORK_NAME = "periodic_history_cleanup"
        }

        override suspend fun doWork(): Result {
            val retentionDays = prefs.load().autoCleanupRetentionDays
            if (retentionDays <= 0) {
                return Result.success()
            }
            val deleted = smsRepository.deleteExpired(retentionDays.toLong())
            Logger.i("HistoryCleanup", "Deleted $deleted messages older than $retentionDays days")
            return Result.success()
        }
    }
