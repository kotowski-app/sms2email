package app.kotowski.sms2email.receiver

import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.impl.WorkManagerImpl
import app.kotowski.sms2email.testing.WorkManagerDelegate
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

// WorkManager.getInstance is delegate-mocked (WorkManagerImpl.setDelegate), so the
// receiver's enqueue goes through the mock; the sweep's own doWork is not exercised
// (that is RetryWorkerTest's scope).
class SweepAlarmReceiverTest {
    @Mock private lateinit var context: Context

    @Mock private lateinit var workManager: WorkManagerImpl

    @Mock private lateinit var workInfosFuture: ListenableFuture<List<WorkInfo>>

    @Mock private lateinit var workInfo: WorkInfo

    private lateinit var closeable: AutoCloseable

    private lateinit var receiver: SweepAlarmReceiver

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        WorkManagerDelegate.install(workManager)
        whenever(workManager.getWorkInfosForUniqueWork(app.kotowski.sms2email.work.RetryWorker.WORK_NAME))
            .thenReturn(workInfosFuture)
        receiver = SweepAlarmReceiver()
    }

    @After
    fun tearDown() {
        WorkManagerDelegate.uninstall()
        closeable.close()
    }

    @Test
    fun `a live periodic sweep skips the one-shot wake`() {
        whenever(workInfosFuture.get(2, TimeUnit.SECONDS)).thenReturn(listOf(workInfo))
        whenever(workInfo.state).thenReturn(WorkInfo.State.RUNNING)

        receiver.onReceive(context, Intent())

        verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
    }

    @Test
    fun `an enqueued periodic sweep gets the one-shot wake`() {
        whenever(workInfosFuture.get(2, TimeUnit.SECONDS)).thenReturn(listOf(workInfo))
        whenever(workInfo.state).thenReturn(WorkInfo.State.ENQUEUED)

        receiver.onReceive(context, Intent())

        verify(workManager).enqueueUniqueWork(
            eq(app.kotowski.sms2email.work.RetryWorker.WORK_NAME_ONCE),
            eq(ExistingWorkPolicy.REPLACE),
            any<OneTimeWorkRequest>(),
        )
    }

    @Test
    fun `no periodic work at all gets the one-shot wake`() {
        whenever(workInfosFuture.get(2, TimeUnit.SECONDS)).thenReturn(emptyList())

        receiver.onReceive(context, Intent())

        verify(workManager).enqueueUniqueWork(
            eq(app.kotowski.sms2email.work.RetryWorker.WORK_NAME_ONCE),
            eq(ExistingWorkPolicy.REPLACE),
            any<OneTimeWorkRequest>(),
        )
    }

    @Test
    fun `a failed state read skips the wake`() {
        whenever(workInfosFuture.get(2, TimeUnit.SECONDS)).thenThrow(java.util.concurrent.TimeoutException("stuck"))

        receiver.onReceive(context, Intent())

        verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
    }

    @Test
    fun `InterruptedException restores interrupt flag and skips wake`() {
        whenever(workInfosFuture.get(2, TimeUnit.SECONDS)).thenThrow(InterruptedException("interrupted"))

        receiver.onReceive(context, Intent())

        assertTrue(Thread.interrupted())
        verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
    }
}
