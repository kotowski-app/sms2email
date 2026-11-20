package app.kotowski.sms2email.work

import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.kotowski.sms2email.util.Logger
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.guava.await

// Safe WorkManager query: a CancellationException is rethrown (a plain catch(Exception)
// would swallow coroutine cancellation), any other failure yields null for the caller's
// policy.
object WorkInfoQuery {
    suspend fun forUniqueWork(
        workManager: WorkManager,
        name: String,
        logTag: String,
        failureNote: String,
    ): List<WorkInfo>? = query(logTag, failureNote, workManager.getWorkInfosForUniqueWork(name))

    suspend fun byTag(
        workManager: WorkManager,
        tag: String,
        logTag: String,
        failureNote: String,
    ): List<WorkInfo>? = query(logTag, failureNote, workManager.getWorkInfosByTag(tag))

    private suspend fun query(
        logTag: String,
        failureNote: String,
        future: ListenableFuture<List<WorkInfo>>,
    ): List<WorkInfo>? =
        try {
            future.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(logTag, "$failureNote (${e.javaClass.simpleName}: ${e.message})")
            null
        }
}
