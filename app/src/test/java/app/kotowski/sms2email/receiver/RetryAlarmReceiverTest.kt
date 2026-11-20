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
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

// WorkManager.getInstance is delegate-mocked (WorkManagerImpl.setDelegate); the
// liveness read goes through EmailWorker.hasLiveWorkForSend (its await() takes the
// isDone fast path onto the stubbed no-arg get()).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetryAlarmReceiverTest {
    @Mock private lateinit var context: Context

    @Mock private lateinit var workManager: WorkManagerImpl

    @Mock private lateinit var workInfosFuture: ListenableFuture<List<WorkInfo>>

    @Mock private lateinit var workInfo: WorkInfo

    private lateinit var closeable: AutoCloseable

    private lateinit var receiver: RetryAlarmReceiver

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        WorkManagerDelegate.install(workManager)
        whenever(workManager.getWorkInfosForUniqueWork(eq("email_send_9"))).thenReturn(workInfosFuture)
        whenever(workInfosFuture.isDone()).thenReturn(true)
        receiver = RetryAlarmReceiver()
    }

    @After
    fun tearDown() {
        WorkManagerDelegate.uninstall()
        closeable.close()
    }

    private fun intent(smsId: Long): Intent = Intent().putExtra(RetryAlarmReceiver.SMS_ID_KEY, smsId)

    @Test
    fun `an alarm without an sms id is ignored`() {
        receiver.onReceive(context, Intent())

        verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
    }

    @Test
    fun `an enqueued per-row work skips the wake`() {
        whenever(workInfosFuture.get()).thenReturn(listOf(workInfo))
        whenever(workInfo.state).thenReturn(WorkInfo.State.ENQUEUED)

        receiver.onReceive(context, intent(9))

        verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
    }

    @Test
    fun `a running per-row work skips the wake`() {
        whenever(workInfosFuture.get()).thenReturn(listOf(workInfo))
        whenever(workInfo.state).thenReturn(WorkInfo.State.RUNNING)

        receiver.onReceive(context, intent(9))

        verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
    }

    @Test
    fun `an absent or terminal per-row work gets the zero-delay wake`() {
        whenever(workInfosFuture.get()).thenReturn(emptyList())

        receiver.onReceive(context, intent(9))

        val captor = argumentCaptor<OneTimeWorkRequest>()
        verify(workManager).enqueueUniqueWork(eq("email_send_9"), eq(ExistingWorkPolicy.REPLACE), captor.capture())
        // The wake is immediate: no initial delay of its own.
        assertTrue(captor.firstValue.workSpec.initialDelay == 0L)
    }

    @Test
    fun `a terminal per-row work gets the zero-delay wake`() {
        whenever(workInfosFuture.get()).thenReturn(listOf(workInfo))
        whenever(workInfo.state).thenReturn(WorkInfo.State.SUCCEEDED)

        receiver.onReceive(context, intent(9))

        verify(workManager).enqueueUniqueWork(eq("email_send_9"), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
    }

    @Test
    fun `a failed state read skips the wake`() {
        whenever(workInfosFuture.get()).thenThrow(java.util.concurrent.ExecutionException(RuntimeException("wm down")))

        receiver.onReceive(context, intent(9))

        verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
    }

    @Test
    fun `InterruptedException restores interrupt flag and logs warning`() {
        // getUninterruptibly retries after an IE; the retry must hit a real failure
        // or the loop never terminates.
        whenever(workInfosFuture.get())
            .thenThrow(InterruptedException("interrupted"))
            .thenThrow(java.util.concurrent.ExecutionException(RuntimeException("wm down")))

        receiver.onReceive(context, intent(9))

        assertTrue(Thread.interrupted())
        verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
    }

    @Test
    fun `general exception during wake is logged and does not crash`() {
        whenever(workInfosFuture.get())
            .thenThrow(RuntimeException("unexpected error"))

        receiver.onReceive(context, intent(9))

        verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
    }
}
