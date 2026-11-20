package app.kotowski.sms2email.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.kotowski.sms2email.data.database.Sms
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.service.EmailBuilder
import app.kotowski.sms2email.service.EmailResult
import app.kotowski.sms2email.testing.WorkManagerDelegate
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailWorkerTest {
    @Mock private lateinit var smsRepository: SmsRepository

    @Mock private lateinit var sendExecutor: SmsSendExecutor

    @Mock private lateinit var prefs: EncryptedPrefs

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private lateinit var closeable: AutoCloseable

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        whenever(prefs.load()).thenReturn(AppSettings())
    }

    @After
    fun tearDown() {
        closeable.close()
    }

    private fun smsWith(status: SmsStatus) =
        Sms(
            id = 5,
            messageBody = "otp",
            phoneNumber = "+1",
            status = status,
        )

    private fun buildWorker(smsId: Long): ListenableWorker =
        TestListenableWorkerBuilder<EmailWorker>(context)
            .setInputData(workDataOf(SMS_ID_KEY to smsId))
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ) = EmailWorker(appContext, workerParameters, smsRepository, sendExecutor, prefs)
                },
            ).build()

    @Test
    fun `returns success for invalid sms id`() =
        runTest {
            val result = buildWorker(-1L).startWork().get()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(smsRepository, never()).getSmsById(any())
            verify(sendExecutor, never()).triage(any())
            verify(sendExecutor, never()).finalizeSend(any(), any())
        }

    @Test
    fun `returns success without sending when forwarding is disabled`() =
        runTest {
            whenever(prefs.load()).thenReturn(AppSettings(isForwardingEnabled = false))

            val result = buildWorker(5L).startWork().get()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(smsRepository, never()).getSmsById(any())
            verify(sendExecutor, never()).triage(any())
            verify(sendExecutor, never()).send(any(), any())
            verify(sendExecutor, never()).finalizeSend(any(), any())
        }

    @Test
    fun `returns success when sms record not found`() =
        runTest {
            whenever(smsRepository.getSmsById(5L)).thenReturn(null)

            val result = buildWorker(5L).startWork().get()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(sendExecutor, never()).triage(any())
        }

    @Test
    fun `returns success without sending when status is SENT`() =
        runTest {
            whenever(smsRepository.getSmsById(5L)).thenReturn(smsWith(SmsStatus.SENT))

            val result = buildWorker(5L).startWork().get()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(sendExecutor, never()).triage(any())
        }

    @Test
    fun `returns success without sending when status is IGNORED`() =
        runTest {
            whenever(smsRepository.getSmsById(5L)).thenReturn(smsWith(SmsStatus.IGNORED))

            val result = buildWorker(5L).startWork().get()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(sendExecutor, never()).triage(any())
        }

    @Test
    fun `returns success without building parts when triage skips message`() =
        runTest {
            whenever(smsRepository.getSmsById(5L)).thenReturn(smsWith(SmsStatus.RETRYING))
            whenever(sendExecutor.triage(any())).thenReturn(SmsSendExecutor.TriageResult.Skipped)

            val result = buildWorker(5L).startWork().get()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(sendExecutor, never()).buildParts(any())
        }

    @Test
    fun `marks sent via executor when send succeeds`() =
        runTest {
            val record = smsWith(SmsStatus.PENDING)
            whenever(smsRepository.getSmsById(5L)).thenReturn(record)
            whenever(sendExecutor.triage(record)).thenReturn(SmsSendExecutor.TriageResult.Proceed)
            whenever(sendExecutor.buildParts(record)).thenReturn(EmailBuilder.EmailParts("subject", "body"))
            whenever(sendExecutor.send(eq(record), any())).thenReturn(SmsSendExecutor.SendResult.Outcome(EmailResult.Success))

            val result = buildWorker(5L).startWork().get()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(sendExecutor).finalizeSend(eq(record), eq(EmailResult.Success))
        }

    @Test
    fun `delegates transient failure finalization to the executor`() =
        runTest {
            val record = smsWith(SmsStatus.PENDING)
            whenever(smsRepository.getSmsById(5L)).thenReturn(record)
            whenever(sendExecutor.triage(record)).thenReturn(SmsSendExecutor.TriageResult.Proceed)
            whenever(sendExecutor.buildParts(record)).thenReturn(EmailBuilder.EmailParts("subject", "body"))
            whenever(sendExecutor.send(eq(record), any())).thenReturn(SmsSendExecutor.SendResult.Outcome(EmailResult.Failure("smtp down")))

            val result = buildWorker(5L).startWork().get()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(sendExecutor).finalizeSend(eq(record), eq(EmailResult.Failure("smtp down")))
        }

    @Test
    fun `delegates permanent failure finalization to the executor`() =
        runTest {
            val record = smsWith(SmsStatus.PENDING)
            whenever(smsRepository.getSmsById(5L)).thenReturn(record)
            whenever(sendExecutor.triage(record)).thenReturn(SmsSendExecutor.TriageResult.Proceed)
            whenever(sendExecutor.buildParts(record)).thenReturn(EmailBuilder.EmailParts("subject", "body"))
            whenever(sendExecutor.send(eq(record), any())).thenReturn(
                SmsSendExecutor.SendResult.Outcome(EmailResult.Failure("Recipient does not exist", permanent = true)),
            )

            val result = buildWorker(5L).startWork().get()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(sendExecutor).finalizeSend(eq(record), eq(EmailResult.Failure("Recipient does not exist", permanent = true)))
        }

    @Test
    fun `returns success without finalizing when send is not claimed`() =
        runTest {
            val record = smsWith(SmsStatus.PENDING)
            whenever(smsRepository.getSmsById(5L)).thenReturn(record)
            whenever(sendExecutor.triage(record)).thenReturn(SmsSendExecutor.TriageResult.Proceed)
            whenever(sendExecutor.buildParts(record)).thenReturn(EmailBuilder.EmailParts("subject", "body"))
            whenever(sendExecutor.send(eq(record), any())).thenReturn(SmsSendExecutor.SendResult.NotClaimed)

            val result = buildWorker(5L).startWork().get()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(sendExecutor, never()).finalizeSend(any(), any())
        }

    private suspend fun liveStateOf(state: WorkInfo.State?): Boolean {
        val workManager = mock<WorkManager>()
        val future = mock<ListenableFuture<List<WorkInfo>>>()
        val info = mock<WorkInfo>()
        whenever(workManager.getWorkInfosForUniqueWork("email_send_5")).thenReturn(future)
        whenever(future.isDone()).thenReturn(true)
        whenever(future.get()).thenAnswer { if (state == null) emptyList<WorkInfo>() else listOf(info) }
        if (state != null) {
            whenever(info.state).thenReturn(state)
        }
        return EmailWorker.hasLiveWorkForSend(workManager, 5, "test")
    }

    @Test
    fun `workName names the per-row send work`() {
        assertEquals("email_send_5", EmailWorker.workName(5))
    }

    @Test
    fun `hasLiveWorkForSend is live for enqueued or running work and absent or terminal otherwise`() =
        runTest {
            assertTrue(liveStateOf(WorkInfo.State.ENQUEUED))
            assertTrue(liveStateOf(WorkInfo.State.RUNNING))
            assertFalse(liveStateOf(WorkInfo.State.SUCCEEDED))
            assertFalse(liveStateOf(WorkInfo.State.CANCELLED))
            assertFalse(liveStateOf(WorkInfo.State.FAILED))
            assertFalse(liveStateOf(null))
        }

    @Test
    fun `hasLiveWorkForSend degrades to live when the query fails`() =
        runTest {
            val workManager = mock<WorkManager>()
            val future = mock<ListenableFuture<List<WorkInfo>>>()
            whenever(workManager.getWorkInfosForUniqueWork("email_send_5")).thenReturn(future)
            whenever(future.isDone()).thenReturn(true)
            whenever(future.get()).thenThrow(java.util.concurrent.ExecutionException(RuntimeException("wm down")))

            assertTrue(EmailWorker.hasLiveWorkForSend(workManager, 5, "test"))
        }

    @Test
    fun `hasEnqueuedWorkForSend is true only for an enqueued work and skips on a failed query`() =
        runTest {
            val workManager = mock<WorkManager>()
            val future = mock<ListenableFuture<List<WorkInfo>>>()
            val info = mock<WorkInfo>()
            whenever(workManager.getWorkInfosForUniqueWork("email_send_5")).thenReturn(future)
            whenever(future.isDone()).thenReturn(true)
            whenever(future.get()).thenReturn(listOf(info))
            whenever(info.state).thenReturn(WorkInfo.State.ENQUEUED)
            assertTrue(EmailWorker.hasEnqueuedWorkForSend(workManager, 5, "test"))
            whenever(info.state).thenReturn(WorkInfo.State.RUNNING)
            assertFalse(EmailWorker.hasEnqueuedWorkForSend(workManager, 5, "test"))
            whenever(future.get()).thenThrow(java.util.concurrent.ExecutionException(RuntimeException("wm down")))
            assertFalse(EmailWorker.hasEnqueuedWorkForSend(workManager, 5, "test"))
        }

    @Test
    fun `enqueueSend sets initial delay when delay is positive`() {
        val wm: WorkManagerImpl = mock()
        val future: ListenableFuture<List<WorkInfo>> = mock()
        WorkManagerDelegate.install(wm)
        try {
            whenever(wm.getWorkInfosForUniqueWork("email_send_10")).thenReturn(future)
            whenever(future.isDone()).thenReturn(true)
            whenever(future.get()).thenReturn(emptyList())
            runBlocking { EmailWorker.enqueueSend(context, 10, initialDelayMillis = 5_000) }
            val captor = argumentCaptor<OneTimeWorkRequest>()
            verify(wm).enqueueUniqueWork(eq("email_send_10"), any(), captor.capture())
            assertEquals(5_000L, captor.firstValue.workSpec.initialDelay)
        } finally {
            WorkManagerDelegate.uninstall()
        }
    }

    @Test
    fun `enqueueSend uses APPEND policy when per-row work is already running`() {
        val wm: WorkManagerImpl = mock()
        val future: ListenableFuture<List<WorkInfo>> = mock()
        val info: WorkInfo = mock()
        WorkManagerDelegate.install(wm)
        try {
            whenever(wm.getWorkInfosForUniqueWork("email_send_10")).thenReturn(future)
            whenever(future.isDone()).thenReturn(true)
            whenever(future.get()).thenReturn(listOf(info))
            whenever(info.state).thenReturn(WorkInfo.State.RUNNING)
            runBlocking { EmailWorker.enqueueSend(context, 10) }
            verify(wm).enqueueUniqueWork(eq("email_send_10"), eq(ExistingWorkPolicy.APPEND), any<OneTimeWorkRequest>())
        } finally {
            WorkManagerDelegate.uninstall()
        }
    }
}
