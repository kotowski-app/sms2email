package app.kotowski.sms2email.work

import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID
import java.util.concurrent.ExecutionException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WorkInfoQueryTest {
    private val workManager = mock<WorkManager>()

    private val future = mock<ListenableFuture<List<WorkInfo>>>()

    private fun workInfo(state: WorkInfo.State): WorkInfo = WorkInfo(UUID.randomUUID(), state, emptySet())

    private fun stubUniqueWork(vararg infos: WorkInfo) {
        whenever(workManager.getWorkInfosForUniqueWork(any())).thenReturn(future)
        whenever(future.isDone()).thenReturn(true)
        whenever(future.get()).thenReturn(infos.toList())
    }

    private fun stubByTag(vararg infos: WorkInfo) {
        whenever(workManager.getWorkInfosByTag(any())).thenReturn(future)
        whenever(future.isDone()).thenReturn(true)
        whenever(future.get()).thenReturn(infos.toList())
    }

    @Test
    fun `forUniqueWork returns the queried work infos`() =
        runTest {
            val info = workInfo(WorkInfo.State.ENQUEUED)
            stubUniqueWork(info)

            assertEquals(listOf(info), WorkInfoQuery.forUniqueWork(workManager, "email_send_5", "test", "failed"))
        }

    @Test
    fun `forUniqueWork returns null when the query fails`() =
        runTest {
            whenever(workManager.getWorkInfosForUniqueWork(any())).thenReturn(future)
            whenever(future.isDone()).thenReturn(true)
            whenever(future.get()).thenThrow(ExecutionException(RuntimeException("wm down")))

            assertNull(WorkInfoQuery.forUniqueWork(workManager, "email_send_5", "test", "failed"))
        }

    @Test
    fun `forUniqueWork rethrows coroutine cancellation`() =
        runTest {
            whenever(workManager.getWorkInfosForUniqueWork(any())).thenReturn(future)
            whenever(future.isDone()).thenReturn(true)
            whenever(future.get()).thenThrow(CancellationException("cancelled"))

            assertFailsWith<CancellationException> {
                WorkInfoQuery.forUniqueWork(workManager, "email_send_5", "test", "failed")
            }
        }

    @Test
    fun `byTag returns the queried work infos`() =
        runTest {
            val info = workInfo(WorkInfo.State.RUNNING)
            stubByTag(info)

            assertEquals(listOf(info), WorkInfoQuery.byTag(workManager, "email_batch", "test", "failed"))
        }

    @Test
    fun `byTag returns null when the query fails`() =
        runTest {
            whenever(workManager.getWorkInfosByTag(any())).thenReturn(future)
            whenever(future.isDone()).thenReturn(true)
            whenever(future.get()).thenThrow(ExecutionException(RuntimeException("wm down")))

            assertNull(WorkInfoQuery.byTag(workManager, "email_batch", "test", "failed"))
        }

    @Test
    fun `byTag rethrows coroutine cancellation`() =
        runTest {
            whenever(workManager.getWorkInfosByTag(any())).thenReturn(future)
            whenever(future.isDone()).thenReturn(true)
            whenever(future.get()).thenThrow(CancellationException("cancelled"))

            assertFailsWith<CancellationException> {
                WorkInfoQuery.byTag(workManager, "email_batch", "test", "failed")
            }
        }
}
