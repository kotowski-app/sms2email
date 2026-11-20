package app.kotowski.sms2email.work

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkContinuation
import androidx.work.WorkInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.utils.taskexecutor.SerialExecutor
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestForegroundUpdater
import androidx.work.testing.TestProgressUpdater
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.Sms
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.StatusUpdate
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.testing.WorkManagerDelegate
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetryWorkerTest {
    @Mock private lateinit var smsRepository: SmsRepository

    @Mock private lateinit var context: Context

    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var retryScheduler: SmsRetryScheduler

    @Mock private lateinit var exactWakeArmer: ExactWakeArmer

    @Mock private lateinit var workManager: WorkManagerImpl

    @Mock private lateinit var workContinuation: WorkContinuation

    @Mock private lateinit var workInfosFuture: ListenableFuture<List<WorkInfo>>

    @Mock private lateinit var workInfo: WorkInfo

    private lateinit var closeable: AutoCloseable

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        WorkManagerDelegate.install(workManager)
        whenever(workManager.beginUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()))
            .thenReturn(workContinuation)
        whenever(workManager.getWorkInfosByTag(EmailBatchWorker.BATCH_TAG)).thenReturn(workInfosFuture)
        // isDone=true puts await() on its fast path, which calls the stubbed no-arg get().
        whenever(workInfosFuture.isDone()).thenReturn(true)
        whenever(workInfosFuture.get()).thenReturn(emptyList())
        whenever(context.getString(R.string.sms_status_send_interrupted)).thenReturn("Send was interrupted, retrying")
        whenever(prefs.load()).thenReturn(AppSettings())
    }

    @After
    fun tearDown() {
        WorkManagerDelegate.uninstall()
        closeable.close()
    }

    private fun buildWorker(): RetryWorker {
        val serialExecutor =
            object : SerialExecutor {
                override fun execute(command: Runnable) {
                    command.run()
                }

                override fun hasPendingTasks(): Boolean = false
            }
        val taskExecutor =
            object : TaskExecutor {
                override fun getMainThreadExecutor(): Executor = SynchronousExecutor()

                override fun getSerialTaskExecutor(): SerialExecutor = serialExecutor

                override fun getCoroutineScope(): CoroutineScope = CoroutineScope(Dispatchers.Unconfined)
            }
        val defaultFactory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = throw UnsupportedOperationException()
            }
        val params =
            WorkerParameters(
                UUID.randomUUID(),
                Data.EMPTY,
                emptyList(),
                WorkerParameters.RuntimeExtras(),
                1,
                1,
                SynchronousExecutor(),
                EmptyCoroutineContext,
                taskExecutor,
                defaultFactory,
                TestProgressUpdater(),
                TestForegroundUpdater(),
            )
        return RetryWorker(context, params, smsRepository, prefs, retryScheduler, exactWakeArmer)
    }

    @Test
    fun `sweep is skipped entirely when forwarding is disabled`() =
        runTest {
            val expired =
                Sms(
                    id = 3,
                    messageBody = "c",
                    phoneNumber = "+3",
                    status = SmsStatus.RETRYING,
                    timeFirstSendAttempt = System.currentTimeMillis() - AppConfig.MAX_RETRY_PERIOD_MS - 1,
                    timeNextSendAttempt = null,
                )
            stubSweeps(dueRetrying = listOf(expired))
            whenever(prefs.load()).thenReturn(AppSettings(isForwardingEnabled = false))

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(smsRepository, never()).transitionSmsStatus(any(), any(), any(), any())
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            verify(workManager, never()).beginUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            verify(workManager, never()).enqueue(any<OneTimeWorkRequest>())
        }

    private suspend fun stubSweeps(
        staleSending: List<Sms> = emptyList(),
        dueRetrying: List<Sms> = emptyList(),
        stalePending: List<Sms> = emptyList(),
    ) {
        whenever(smsRepository.getStaleSending(any())).thenReturn(staleSending)
        whenever(smsRepository.getDueRetrying(any())).thenReturn(dueRetrying)
        whenever(smsRepository.getStalePending(any())).thenReturn(stalePending)
    }

    private fun verifyEnqueuedBatchIds(vararg expected: Long) {
        val captor = argumentCaptor<OneTimeWorkRequest>()
        verify(workManager).beginUniqueWork(eq(EmailBatchWorker.WORK_NAME), eq(ExistingWorkPolicy.REPLACE), captor.capture())
        verify(workContinuation).enqueue()
        assertEquals(
            expected.joinToString(","),
            captor.firstValue.workSpec.input
                .getString(BATCH_IDS_KEY),
        )
    }

    @Test
    fun `stale sending row is recovered via CAS and enqueued`() =
        runTest {
            val stale =
                Sms(
                    id = 1,
                    messageBody = "a",
                    phoneNumber = "+1",
                    status = SmsStatus.SENDING,
                    retryCount = 3,
                    timeLastSendAttempt = 1L,
                )
            stubSweeps(staleSending = listOf(stale))
            whenever(
                smsRepository.transitionSmsStatus(eq(1L), any(), any(), any()),
            ).thenReturn(true)
            val before = System.currentTimeMillis()

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            val updateCaptor = argumentCaptor<StatusUpdate>()
            verify(smsRepository).transitionSmsStatus(
                eq(1L),
                eq(listOf(SmsStatus.SENDING)),
                eq(SmsStatus.RETRYING),
                updateCaptor.capture(),
            )
            val update = updateCaptor.firstValue
            assertEquals("Send was interrupted, retrying", update.statusDetails)
            assertEquals(4, update.retryCount)
            assertNull(update.timeFirstSendAttempt)
            val lastAttempt = assertNotNull(update.timeLastSendAttempt)
            assertTrue(lastAttempt in before..System.currentTimeMillis())
            assertEquals(lastAttempt, update.timeNextSendAttempt)
            verifyEnqueuedBatchIds(1L)
        }

    @Test
    fun `stale sending row that loses the CAS is not enqueued`() =
        runTest {
            val stale =
                Sms(
                    id = 1,
                    messageBody = "a",
                    phoneNumber = "+1",
                    status = SmsStatus.SENDING,
                    retryCount = 3,
                    timeLastSendAttempt = 1L,
                )
            stubSweeps(staleSending = listOf(stale))
            whenever(
                smsRepository.transitionSmsStatus(eq(1L), any(), any(), any()),
            ).thenReturn(false)

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            verify(workManager, never()).beginUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            verify(workManager, never()).enqueue(any<OneTimeWorkRequest>())
        }

    @Test
    fun `due retrying row is enqueued without any state change`() =
        runTest {
            val due =
                Sms(
                    id = 2,
                    messageBody = "b",
                    phoneNumber = "+2",
                    status = SmsStatus.RETRYING,
                    timeFirstSendAttempt = System.currentTimeMillis() - 3_600_000L,
                    timeNextSendAttempt = null,
                )
            stubSweeps(dueRetrying = listOf(due))
            whenever(
                retryScheduler.failIfExpired(eq(2L), eq(due.timeFirstSendAttempt), eq(due.retryCount), eq(listOf(SmsStatus.RETRYING))),
            ).thenReturn(false)

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(smsRepository, never())
                .transitionSmsStatus(any(), any(), any(), any())
            verifyEnqueuedBatchIds(2L)
        }

    @Test
    fun `retrying row with a future next attempt is untouched`() =
        runTest {
            stubSweeps()

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(smsRepository, never())
                .transitionSmsStatus(any(), any(), any(), any())
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            verify(workManager, never()).beginUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            verify(workManager, never()).enqueue(any<OneTimeWorkRequest>())
        }

    @Test
    fun `expired retrying row is delegated to the scheduler and not enqueued`() =
        runTest {
            val expired =
                Sms(
                    id = 3,
                    messageBody = "c",
                    phoneNumber = "+3",
                    status = SmsStatus.RETRYING,
                    timeFirstSendAttempt = System.currentTimeMillis() - AppConfig.MAX_RETRY_PERIOD_MS - 1,
                    timeNextSendAttempt = null,
                )
            stubSweeps(dueRetrying = listOf(expired))
            whenever(
                retryScheduler.failIfExpired(
                    eq(3L),
                    eq(expired.timeFirstSendAttempt),
                    eq(expired.retryCount),
                    eq(listOf(SmsStatus.RETRYING)),
                ),
            ).thenReturn(true)

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(smsRepository, never()).transitionSmsStatus(any(), any(), any(), any())
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            verify(workManager, never()).beginUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            verify(workManager, never()).enqueue(any<OneTimeWorkRequest>())
        }

    @Test
    fun `stale pending row is enqueued without any state transition`() =
        runTest {
            val pending =
                Sms(
                    id = 4,
                    messageBody = "d",
                    phoneNumber = "+4",
                    status = SmsStatus.PENDING,
                    timeFirstSendAttempt = 1L,
                )
            stubSweeps(stalePending = listOf(pending))

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(smsRepository, never())
                .transitionSmsStatus(any(), any(), any(), any())
            verifyEnqueuedBatchIds(4L)
        }

    @Test
    fun `live batch work skips the entire sweep including db transitions`() =
        runTest {
            val stale =
                Sms(
                    id = 1,
                    messageBody = "a",
                    phoneNumber = "+1",
                    status = SmsStatus.SENDING,
                    retryCount = 3,
                    timeLastSendAttempt = 1L,
                )
            val expired =
                Sms(
                    id = 3,
                    messageBody = "c",
                    phoneNumber = "+3",
                    status = SmsStatus.RETRYING,
                    timeFirstSendAttempt = System.currentTimeMillis() - AppConfig.MAX_RETRY_PERIOD_MS - 1,
                    timeNextSendAttempt = null,
                )
            val pending =
                Sms(
                    id = 5,
                    messageBody = "e",
                    phoneNumber = "+5",
                    status = SmsStatus.PENDING,
                    timeFirstSendAttempt = 1L,
                )
            stubSweeps(staleSending = listOf(stale), dueRetrying = listOf(expired), stalePending = listOf(pending))
            whenever(workInfosFuture.get()).thenReturn(listOf(workInfo))
            whenever(workInfo.state).thenReturn(WorkInfo.State.ENQUEUED)

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(smsRepository, never())
                .transitionSmsStatus(any(), any(), any(), any())
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            verify(workManager, never()).beginUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            verify(workManager, never()).enqueue(any<OneTimeWorkRequest>())
        }

    @Test
    fun `batch work state query failure skips the entire sweep including db transitions`() =
        runTest {
            val stale =
                Sms(
                    id = 1,
                    messageBody = "a",
                    phoneNumber = "+1",
                    status = SmsStatus.SENDING,
                    retryCount = 3,
                    timeLastSendAttempt = 1L,
                )
            val expired =
                Sms(
                    id = 3,
                    messageBody = "c",
                    phoneNumber = "+3",
                    status = SmsStatus.RETRYING,
                    timeFirstSendAttempt = System.currentTimeMillis() - AppConfig.MAX_RETRY_PERIOD_MS - 1,
                    timeNextSendAttempt = null,
                )
            val pending =
                Sms(
                    id = 5,
                    messageBody = "e",
                    phoneNumber = "+5",
                    status = SmsStatus.PENDING,
                    timeFirstSendAttempt = 1L,
                )
            stubSweeps(staleSending = listOf(stale), dueRetrying = listOf(expired), stalePending = listOf(pending))
            whenever(workInfosFuture.get())
                .thenThrow(ExecutionException(RuntimeException("query failed")))

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(smsRepository, never())
                .transitionSmsStatus(any(), any(), any(), any())
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            verify(workManager, never()).beginUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            verify(workManager, never()).enqueue(any<OneTimeWorkRequest>())
        }

    @Test
    fun `batch work state query cancellation rethrows without any db transitions or enqueue`() =
        runTest {
            val stale =
                Sms(
                    id = 1,
                    messageBody = "a",
                    phoneNumber = "+1",
                    status = SmsStatus.SENDING,
                    retryCount = 3,
                    timeLastSendAttempt = 1L,
                )
            stubSweeps(staleSending = listOf(stale))
            whenever(workInfosFuture.get()).thenThrow(CancellationException("cancelled"))

            assertFailsWith<CancellationException> {
                buildWorker().doWork()
            }

            verify(smsRepository, never())
                .transitionSmsStatus(any(), any(), any(), any())
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            verify(workManager, never()).beginUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            verify(workManager, never()).enqueue(any<OneTimeWorkRequest>())
        }

    @Test
    fun `batch ids are capped at 400`() =
        runTest {
            val pending =
                (1L..401L).map { id ->
                    Sms(
                        id = id,
                        messageBody = "p$id",
                        phoneNumber = "+$id",
                        status = SmsStatus.PENDING,
                        timeFirstSendAttempt = 1L,
                    )
                }
            stubSweeps(stalePending = pending)

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verifyEnqueuedBatchIds(*(1L..400L).toList().toLongArray())
        }

    @Test
    fun `enqueuePeriodic uses the given interval with KEEP policy`() {
        val captor = argumentCaptor<PeriodicWorkRequest>()
        RetryWorker.enqueuePeriodic(context, 60)
        verify(workManager).enqueueUniquePeriodicWork(
            eq(RetryWorker.WORK_NAME),
            eq(ExistingPeriodicWorkPolicy.KEEP),
            captor.capture(),
        )
        assertEquals(60 * 60_000L, captor.firstValue.workSpec.intervalDuration)
        assertEquals(RetryWorker::class.java.name, captor.firstValue.workSpec.workerClassName)
    }

    @Test
    fun `enqueueOnce uses the once name with REPLACE policy`() {
        RetryWorker.enqueueOnce(context)
        verify(workManager).enqueueUniqueWork(
            eq(RetryWorker.WORK_NAME_ONCE),
            eq(ExistingWorkPolicy.REPLACE),
            any<OneTimeWorkRequest>(),
        )
    }

    @Test
    fun `a completed sweep re-arms the next tick token`() =
        runTest {
            stubSweeps()
            val before = System.currentTimeMillis()

            buildWorker().doWork()

            val at = argumentCaptor<Long>()
            verify(exactWakeArmer).armSweepAlarm(at.capture())
            // The default settings carry the 15-minute period: the token is the next tick.
            assertTrue(at.firstValue in (before + 15 * 60_000L)..(System.currentTimeMillis() + 15 * 60_000L))
        }

    @Test
    fun `a sweep skipped for a live batch still re-arms the next tick token`() =
        runTest {
            whenever(workInfosFuture.get()).thenReturn(listOf(workInfo))
            whenever(workInfo.state).thenReturn(WorkInfo.State.ENQUEUED)

            buildWorker().doWork()

            verify(exactWakeArmer).armSweepAlarm(any())
        }

    @Test
    fun `a sweep with forwarding disabled re-arms no token`() =
        runTest {
            whenever(prefs.load()).thenReturn(AppSettings(isForwardingEnabled = false))

            buildWorker().doWork()

            verify(exactWakeArmer, never()).armSweepAlarm(any())
        }

    @Test
    fun `a sweep with the interval off re-arms no token`() =
        runTest {
            stubSweeps()
            whenever(prefs.load()).thenReturn(AppSettings(retrySweepIntervalMinutes = 0))

            buildWorker().doWork()

            verify(exactWakeArmer, never()).armSweepAlarm(any())
        }
}
