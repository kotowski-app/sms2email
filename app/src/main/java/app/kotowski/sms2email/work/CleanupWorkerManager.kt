package app.kotowski.sms2email.work

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.kotowski.sms2email.AppConfig
import java.util.concurrent.TimeUnit

class CleanupWorkerManager(
    private val workManager: WorkManager,
) {
    fun enqueue() {
        val workRequest =
            PeriodicWorkRequestBuilder<HistoryCleanupWorker>(AppConfig.HISTORY_CLEANUP_PERIOD_DAYS, TimeUnit.DAYS)
                .build()
        workManager.enqueueUniquePeriodicWork(
            HistoryCleanupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest,
        )
    }

    fun cancel() {
        workManager.cancelUniqueWork(HistoryCleanupWorker.WORK_NAME)
    }
}
