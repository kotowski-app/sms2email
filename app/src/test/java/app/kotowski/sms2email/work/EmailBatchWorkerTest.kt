package app.kotowski.sms2email.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkContinuation
import androidx.work.WorkInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.data.database.Sms
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.service.BatchEmailItem
import app.kotowski.sms2email.service.EmailBuilder
import app.kotowski.sms2email.service.EmailResult
import app.kotowski.sms2email.service.EmailService
import app.kotowski.sms2email.testing.WorkManagerDelegate
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
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
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutionException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailBatchWorkerTest {
    @Mock private lateinit var context: Context

    @Mock private lateinit var connectivityManager: ConnectivityManager

    @Mock private lateinit var network: Network

    @Mock private lateinit var networkCapabilities: NetworkCapabilities

    @Mock private lateinit var smsRepository: SmsRepository

    @Mock private lateinit var emailService: EmailService

    @Mock private lateinit var sendExecutor: SmsSendExecutor

    @Mock private lateinit var retryScheduler: SmsRetryScheduler

    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var workManager: WorkManagerImpl

    @Mock private lateinit var workContinuation: WorkContinuation

    @Mock private lateinit var chainedContinuation: WorkContinuation

    @Mock private lateinit var workInfosFuture: ListenableFuture<List<WorkInfo>>

    @Mock private lateinit var workInfo: WorkInfo

    @Mock private lateinit var terminalWorkInfo: WorkInfo

    private lateinit var closeable: AutoCloseable

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        // A config that passes the queue's completeness gate, so the tests under it reach
        // the network check and the batch send.
        whenever(
            prefs.load(),
        ).thenReturn(
            AppSettings(
                smtpHost = "smtp.example.com",
                smtpUsername = "user@example.com",
                senderEmail = "sender@example.com",
                destEmail = "dest@example.com",
            ),
        )
        whenever(prefs.smtpPassword).thenReturn("password")
        // the executor's config gate passes by default; individual tests override it
        whenever(sendExecutor.isSmtpConfigured()).thenReturn(true)
        // no active network by default -> NetworkUtil.checkNetwork reports the wait error, blocking the batch
        whenever(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)
        whenever(connectivityManager.activeNetwork).thenReturn(null)
        // no live batch work by default -> enqueue picks REPLACE. isDone=true puts
        // await() on its fast path, which calls the stubbed no-arg get().
        whenever(workManager.getWorkInfosByTag(EmailBatchWorker.BATCH_TAG)).thenReturn(workInfosFuture)
        whenever(workInfosFuture.isDone()).thenReturn(true)
        whenever(workInfosFuture.get()).thenReturn(emptyList())
        whenever(context.getString(app.kotowski.sms2email.R.string.error_waiting_for_internet)).thenReturn("waiting for internet")
    }

    @After
    fun tearDown() {
        closeable.close()
    }

    private fun enableInternet() {
        whenever(connectivityManager.activeNetwork).thenReturn(network)
        whenever(connectivityManager.getNetworkCapabilities(network)).thenReturn(networkCapabilities)
        whenever(networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        whenever(networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
    }

    private fun sms(
        id: Long,
        status: SmsStatus,
    ) = Sms(
        id = id,
        messageBody = "otp $id",
        phoneNumber = "+1$id",
        status = status,
    )

    private fun buildWorker(ids: List<Long>): ListenableWorker =
        TestListenableWorkerBuilder<EmailBatchWorker>(context)
            .setInputData(workDataOf(BATCH_IDS_KEY to ids.joinToString(",")))
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ) = EmailBatchWorker(appContext, workerParameters, smsRepository, emailService, sendExecutor, retryScheduler, prefs)
                },
            ).build()

    private suspend fun stubBatchSend(callbackResults: (List<BatchEmailItem>) -> Map<Long, EmailResult>) {
        whenever(emailService.sendEmailBatch(any(), any(), any())).thenAnswer { invocation ->
            val items = invocation.getArgument<List<BatchEmailItem>>(0)
            val callback = invocation.getArgument<(Long, EmailResult) -> Unit>(1)
            callbackResults(items).onEach { (smsId, result) ->
                callback(smsId, result)
            }
        }
    }

    @Test
    fun `returns success for empty batch ids`() =
        runTest {
            val result = buildWorker(emptyList()).startWork().get()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(smsRepository, never()).getSmsByIds(any())
        }

    @Test
    fun `returns success when no records found`() =
        runTest {
            whenever(smsRepository.getSmsByIds(listOf(1L, 2L))).thenReturn(emptyList())

            val result = buildWorker(listOf(1L, 2L)).startWork().get()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(emailService, never()).sendEmailBatch(any(), any(), any())
        }

    @Test
    fun `returns success without sending when forwarding is disabled`() =
        runTest {
            whenever(prefs.load()).thenReturn(AppSettings(isForwardingEnabled = false))

            val result = buildWorker(listOf(1L, 2L)).startWork().get()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(smsRepository, never()).getSmsByIds(any())
            verify(sendExecutor, never()).triage(any())
            verify(sendExecutor, never()).claim(any())
            verify(emailService, never()).sendEmailBatch(any(), any(), any())
            verify(retryScheduler, never()).scheduleRetry(any(), any(), any())
        }

    @Test
    fun `skips SENT and IGNORED records without triage or claim`() =
        runTest {
            whenever(smsRepository.getSmsByIds(listOf(1L, 2L))).thenReturn(
                listOf(sms(1L, SmsStatus.SENT), sms(2L, SmsStatus.IGNORED)),
            )

            val result = buildWorker(listOf(1L, 2L)).startWork().get()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(sendExecutor, never()).triage(any())
            verify(sendExecutor, never()).claim(any())
            verify(sendExecutor, never()).buildParts(any())
            verify(emailService, never()).sendEmailBatch(any(), any(), any())
            verify(retryScheduler, never()).scheduleRetry(any(), any(), any())
        }

    @Test
    fun `skips triaged records without claiming`() =
        runTest {
            val record = sms(3L, SmsStatus.PENDING)
            whenever(smsRepository.getSmsByIds(listOf(3L))).thenReturn(listOf(record))
            whenever(sendExecutor.triage(record)).thenReturn(SmsSendExecutor.TriageResult.Skipped)

            val result = buildWorker(listOf(3L)).startWork().get()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(sendExecutor, never()).claim(any())
            verify(sendExecutor, never()).buildParts(any())
            verify(emailService, never()).sendEmailBatch(any(), any(), any())
        }

    @Test
    fun `does not send a record when the claim loses the race`() =
        runTest {
            val record = sms(4L, SmsStatus.PENDING)
            whenever(smsRepository.getSmsByIds(listOf(4L))).thenReturn(listOf(record))
            whenever(sendExecutor.triage(record)).thenReturn(SmsSendExecutor.TriageResult.Proceed)
            whenever(sendExecutor.claim(record)).thenReturn(false)

            val result = buildWorker(listOf(4L)).startWork().get()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(sendExecutor, never()).buildParts(any())
            verify(emailService, never()).sendEmailBatch(any(), any(), any())
            verify(retryScheduler, never()).scheduleRetry(any(), any(), any())
        }

    @Test
    fun `skips a retrying row whose next attempt is still in the future`() =
        runTest {
            enableInternet()
            val record =
                sms(1L, SmsStatus.RETRYING).copy(
                    timeNextSendAttempt = System.currentTimeMillis() + 120_000L,
                )
            whenever(smsRepository.getSmsByIds(listOf(1L))).thenReturn(listOf(record))
            whenever(sendExecutor.triage(record)).thenReturn(SmsSendExecutor.TriageResult.Proceed)

            val result = buildWorker(listOf(1L)).startWork().get()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(sendExecutor, never()).claim(any())
            verify(sendExecutor, never()).buildParts(any())
            verify(emailService, never()).sendEmailBatch(any(), any(), any())
            verify(retryScheduler, never()).scheduleRetry(any(), any(), any())
        }

    @Test
    fun `sends claimed items and finalizes each callback result via the executor`() =
        runTest {
            enableInternet()
            val okRecord = sms(1L, SmsStatus.RETRYING)
            val failRecord = sms(2L, SmsStatus.RETRYING)
            whenever(smsRepository.getSmsByIds(listOf(1L, 2L))).thenReturn(listOf(okRecord, failRecord))
            whenever(sendExecutor.triage(okRecord)).thenReturn(SmsSendExecutor.TriageResult.Proceed)
            whenever(sendExecutor.triage(failRecord)).thenReturn(SmsSendExecutor.TriageResult.Proceed)
            whenever(sendExecutor.claim(okRecord)).thenReturn(true)
            whenever(sendExecutor.claim(failRecord)).thenReturn(true)
            whenever(sendExecutor.buildParts(okRecord)).thenReturn(EmailBuilder.EmailParts("s1", "b1"))
            whenever(sendExecutor.buildParts(failRecord)).thenReturn(EmailBuilder.EmailParts("s2", "b2"))
            stubBatchSend { items ->
                items.associate { it.smsId to (if (it.smsId == 2L) EmailResult.Failure("smtp refused") else EmailResult.Success) }
            }

            val result = buildWorker(listOf(1L, 2L)).startWork().get()

            assertEquals(ListenableWorker.Result.success(), result)
            val captor = argumentCaptor<List<BatchEmailItem>>()
            verify(emailService).sendEmailBatch(captor.capture(), any(), any())
            assertEquals(
                listOf(
                    BatchEmailItem(smsId = 1L, subject = "s1", body = "b1"),
                    BatchEmailItem(smsId = 2L, subject = "s2", body = "b2"),
                ),
                captor.firstValue,
            )
            verify(sendExecutor, times(1)).buildParts(okRecord)
            verify(sendExecutor, times(1)).buildParts(failRecord)
            verify(sendExecutor).finalizeSend(eq(okRecord), eq(EmailResult.Success))
            verify(sendExecutor).finalizeSend(eq(failRecord), eq(EmailResult.Failure("smtp refused")))
        }

    @Test
    fun `passes a shouldContinue predicate that reflects the live forwarding flag`() =
        runTest {
            enableInternet()
            val record = sms(1L, SmsStatus.PENDING)
            whenever(smsRepository.getSmsByIds(listOf(1L))).thenReturn(listOf(record))
            whenever(sendExecutor.triage(record)).thenReturn(SmsSendExecutor.TriageResult.Proceed)
            whenever(sendExecutor.claim(record)).thenReturn(true)
            whenever(sendExecutor.buildParts(record)).thenReturn(EmailBuilder.EmailParts("s1", "b1"))
            stubBatchSend { items -> items.associate { it.smsId to EmailResult.Success } }

            val result = buildWorker(listOf(1L)).startWork().get()

            assertEquals(ListenableWorker.Result.success(), result)
            val predicateCaptor = argumentCaptor<() -> Boolean>()
            verify(emailService).sendEmailBatch(any(), any(), predicateCaptor.capture())
            val predicate = assertIs<() -> Boolean>(predicateCaptor.firstValue)
            assertTrue(predicate(), "flag is on in the stubbed prefs, predicate must pass")
            whenever(prefs.load()).thenReturn(AppSettings(isForwardingEnabled = false))
            assertFalse(predicate(), "flag flipped off, predicate must stop the batch")
        }

    @Test
    fun `delegates permanent failure finalization to the executor`() =
        runTest {
            enableInternet()
            val okRecord = sms(1L, SmsStatus.RETRYING)
            val permRecord = sms(2L, SmsStatus.RETRYING)
            whenever(smsRepository.getSmsByIds(listOf(1L, 2L))).thenReturn(listOf(okRecord, permRecord))
            whenever(sendExecutor.triage(okRecord)).thenReturn(SmsSendExecutor.TriageResult.Proceed)
            whenever(sendExecutor.triage(permRecord)).thenReturn(SmsSendExecutor.TriageResult.Proceed)
            whenever(sendExecutor.claim(okRecord)).thenReturn(true)
            whenever(sendExecutor.claim(permRecord)).thenReturn(true)
            whenever(sendExecutor.buildParts(okRecord)).thenReturn(EmailBuilder.EmailParts("s1", "b1"))
            whenever(sendExecutor.buildParts(permRecord)).thenReturn(EmailBuilder.EmailParts("s2", "b2"))
            stubBatchSend { items ->
                items.associate {
                    it.smsId to
                        (if (it.smsId == 2L) EmailResult.Failure("Recipient does not exist", permanent = true) else EmailResult.Success)
                }
            }

            val result = buildWorker(listOf(1L, 2L)).startWork().get()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(sendExecutor).finalizeSend(eq(okRecord), eq(EmailResult.Success))
            verify(sendExecutor).finalizeSend(eq(permRecord), eq(EmailResult.Failure("Recipient does not exist", permanent = true)))
        }

    @Test
    fun `propagates cancellation from sendEmailBatch without finalizing anything`() =
        runTest {
            enableInternet()
            val record = sms(1L, SmsStatus.RETRYING)
            whenever(smsRepository.getSmsByIds(listOf(1L))).thenReturn(listOf(record))
            whenever(sendExecutor.triage(record)).thenReturn(SmsSendExecutor.TriageResult.Proceed)
            whenever(sendExecutor.claim(record)).thenReturn(true)
            whenever(sendExecutor.buildParts(record)).thenReturn(EmailBuilder.EmailParts("s1", "b1"))
            whenever(emailService.sendEmailBatch(any(), any(), any())).thenThrow(CancellationException("cancelled"))

            val worker = buildWorker(listOf(1L)) as EmailBatchWorker

            assertFailsWith<CancellationException> {
                worker.doWork()
            }

            verify(sendExecutor, never()).finalizeSend(any(), any())
        }

    @Test
    fun `finalizes unreached claimed ids as waiting-for-internet failures`() =
        runTest {
            enableInternet()
            val first = sms(1L, SmsStatus.PENDING)
            val second = sms(2L, SmsStatus.PENDING)
            whenever(smsRepository.getSmsByIds(listOf(1L, 2L))).thenReturn(listOf(first, second))
            whenever(sendExecutor.triage(first)).thenReturn(SmsSendExecutor.TriageResult.Proceed)
            whenever(sendExecutor.triage(second)).thenReturn(SmsSendExecutor.TriageResult.Proceed)
            whenever(sendExecutor.claim(first)).thenReturn(true)
            whenever(sendExecutor.claim(second)).thenReturn(true)
            whenever(sendExecutor.buildParts(first)).thenReturn(EmailBuilder.EmailParts("s1", "b1"))
            whenever(sendExecutor.buildParts(second)).thenReturn(EmailBuilder.EmailParts("s2", "b2"))
            // the batch stopped after the first item (transport dead)
            stubBatchSend { mapOf(1L to EmailResult.Success) }

            val result = buildWorker(listOf(1L, 2L)).startWork().get()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(sendExecutor).finalizeSend(eq(first), eq(EmailResult.Success))
            verify(sendExecutor).finalizeSend(eq(second), eq(EmailResult.Failure("waiting for internet")))
        }

    @Test
    fun `fails all claimed items permanently when the SMTP config is incomplete`() =
        runTest {
            whenever(sendExecutor.isSmtpConfigured()).thenReturn(false)
            whenever(context.getString(app.kotowski.sms2email.R.string.error_smtp_not_configured)).thenReturn("smtp not configured")
            val first = sms(1L, SmsStatus.PENDING)
            val second = sms(2L, SmsStatus.RETRYING)
            whenever(smsRepository.getSmsByIds(listOf(1L, 2L))).thenReturn(listOf(first, second))
            whenever(sendExecutor.triage(first)).thenReturn(SmsSendExecutor.TriageResult.Proceed)
            whenever(sendExecutor.triage(second)).thenReturn(SmsSendExecutor.TriageResult.Proceed)
            whenever(sendExecutor.claim(first)).thenReturn(true)
            whenever(sendExecutor.claim(second)).thenReturn(true)
            whenever(sendExecutor.buildParts(first)).thenReturn(EmailBuilder.EmailParts("s1", "b1"))
            whenever(sendExecutor.buildParts(second)).thenReturn(EmailBuilder.EmailParts("s2", "b2"))

            val result = buildWorker(listOf(1L, 2L)).startWork().get()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(sendExecutor).finalizeSend(eq(first), eq(EmailResult.Failure("smtp not configured", permanent = true)))
            verify(sendExecutor).finalizeSend(eq(second), eq(EmailResult.Failure("smtp not configured", permanent = true)))
            verify(emailService, never()).sendEmailBatch(any(), any(), any())
            verify(retryScheduler, never()).scheduleRetry(any(), any(), any())
        }

    @Test
    fun `schedules a retry for each claimed id and succeeds when network blocked`() =
        runTest {
            val first = sms(1L, SmsStatus.PENDING)
            val second = sms(2L, SmsStatus.RETRYING)
            whenever(smsRepository.getSmsByIds(listOf(1L, 2L))).thenReturn(listOf(first, second))
            whenever(sendExecutor.triage(first)).thenReturn(SmsSendExecutor.TriageResult.Proceed)
            whenever(sendExecutor.triage(second)).thenReturn(SmsSendExecutor.TriageResult.Proceed)
            whenever(sendExecutor.claim(first)).thenReturn(true)
            whenever(sendExecutor.claim(second)).thenReturn(true)
            whenever(sendExecutor.buildParts(first)).thenReturn(EmailBuilder.EmailParts("s1", "b1"))
            whenever(sendExecutor.buildParts(second)).thenReturn(EmailBuilder.EmailParts("s2", "b2"))

            val result = buildWorker(listOf(1L, 2L)).startWork().get()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(retryScheduler).scheduleRetry(eq(1L), eq(first), eq("waiting for internet"))
            verify(retryScheduler).scheduleRetry(eq(2L), eq(second), eq("waiting for internet"))
            verify(emailService, never()).sendEmailBatch(any(), any(), any())
            verify(sendExecutor, never()).markSent(any())
        }

    @Test
    fun `enqueue chains all chunks under the unique work so batches run one after another`() {
        WorkManagerDelegate.install(workManager)
        try {
            whenever(
                workManager.beginUniqueWork(
                    eq(EmailBatchWorker.WORK_NAME),
                    eq(ExistingWorkPolicy.REPLACE),
                    any<OneTimeWorkRequest>(),
                ),
            ).thenReturn(workContinuation)
            // then() returns a new continuation; the enqueue must land on the LAST one,
            // otherwise every chunk after the first is silently dropped.
            whenever(workContinuation.then(any<OneTimeWorkRequest>())).thenReturn(chainedContinuation)

            val ids = (1L..401L).toList()
            runBlocking { EmailBatchWorker.enqueue(context, ids) }

            val firstCaptor = argumentCaptor<OneTimeWorkRequest>()
            verify(workManager).beginUniqueWork(
                eq(EmailBatchWorker.WORK_NAME),
                eq(ExistingWorkPolicy.REPLACE),
                firstCaptor.capture(),
            )
            val secondCaptor = argumentCaptor<OneTimeWorkRequest>()
            verify(workContinuation).then(secondCaptor.capture())
            verify(chainedContinuation).enqueue()
            verify(workContinuation, never()).enqueue()
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            verify(workManager, never()).enqueue(any<OneTimeWorkRequest>())

            val firstRequest = firstCaptor.firstValue
            val secondRequest = secondCaptor.firstValue
            assertEquals(
                ids.take(AppConfig.MAX_BATCH_IDS).joinToString(","),
                firstRequest.workSpec.input.getString(BATCH_IDS_KEY),
            )
            assertEquals("401", secondRequest.workSpec.input.getString(BATCH_IDS_KEY))
            assertTrue(EmailBatchWorker.BATCH_TAG in firstRequest.tags)
            assertTrue(EmailBatchWorker.BATCH_TAG in secondRequest.tags)
            assertEquals(NetworkType.CONNECTED, firstRequest.workSpec.constraints.requiredNetworkType)
            assertEquals(NetworkType.CONNECTED, secondRequest.workSpec.constraints.requiredNetworkType)
            assertEquals(EmailBatchWorker::class.java.name, firstRequest.workSpec.workerClassName)
            assertEquals(EmailBatchWorker::class.java.name, secondRequest.workSpec.workerClassName)
        } finally {
            WorkManagerDelegate.uninstall()
        }
    }

    @Test
    fun `enqueue fits a single chunk into the unique work only`() {
        WorkManagerDelegate.install(workManager)
        try {
            whenever(
                workManager.beginUniqueWork(
                    eq(EmailBatchWorker.WORK_NAME),
                    eq(ExistingWorkPolicy.REPLACE),
                    any<OneTimeWorkRequest>(),
                ),
            ).thenReturn(workContinuation)

            runBlocking { EmailBatchWorker.enqueue(context, (1L..400L).toList()) }

            val captor = argumentCaptor<OneTimeWorkRequest>()
            verify(workManager).beginUniqueWork(
                eq(EmailBatchWorker.WORK_NAME),
                eq(ExistingWorkPolicy.REPLACE),
                captor.capture(),
            )
            verify(workContinuation, never()).then(any<List<OneTimeWorkRequest>>())
            verify(workContinuation).enqueue()
            verify(workManager, never()).enqueue(any<OneTimeWorkRequest>())
            assertEquals(
                (1L..400L).joinToString(","),
                captor.firstValue.workSpec.input
                    .getString(BATCH_IDS_KEY),
            )
            assertTrue(EmailBatchWorker.BATCH_TAG in captor.firstValue.tags)
        } finally {
            WorkManagerDelegate.uninstall()
        }
    }

    @Test
    fun `enqueue does nothing for empty ids`() {
        WorkManagerDelegate.install(workManager)
        try {
            runBlocking { EmailBatchWorker.enqueue(context, emptyList()) }

            verify(workManager, never()).beginUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            verify(workManager, never()).enqueue(any<OneTimeWorkRequest>())
        } finally {
            WorkManagerDelegate.uninstall()
        }
    }

    private fun stubAppendPolicy() {
        whenever(
            workManager.beginUniqueWork(
                eq(EmailBatchWorker.WORK_NAME),
                eq(ExistingWorkPolicy.APPEND),
                any<OneTimeWorkRequest>(),
            ),
        ).thenReturn(workContinuation)
    }

    @Test
    fun `enqueue appends when a batch is already running`() {
        WorkManagerDelegate.install(workManager)
        try {
            whenever(workInfosFuture.get()).thenReturn(listOf(workInfo))
            whenever(workInfo.state).thenReturn(WorkInfo.State.RUNNING)
            stubAppendPolicy()
            whenever(workContinuation.then(any<OneTimeWorkRequest>())).thenReturn(chainedContinuation)

            val ids = (1L..401L).toList()
            runBlocking { EmailBatchWorker.enqueue(context, ids) }

            verify(workManager).beginUniqueWork(
                eq(EmailBatchWorker.WORK_NAME),
                eq(ExistingWorkPolicy.APPEND),
                any<OneTimeWorkRequest>(),
            )
            verify(workManager, never()).beginUniqueWork(
                eq(EmailBatchWorker.WORK_NAME),
                eq(ExistingWorkPolicy.REPLACE),
                any<OneTimeWorkRequest>(),
            )
            verify(workContinuation).then(any<OneTimeWorkRequest>())
            verify(chainedContinuation).enqueue()
        } finally {
            WorkManagerDelegate.uninstall()
        }
    }

    @Test
    fun `enqueue appends when batch work is enqueued but not yet running`() {
        WorkManagerDelegate.install(workManager)
        try {
            whenever(workInfosFuture.get()).thenReturn(listOf(workInfo, terminalWorkInfo))
            whenever(workInfo.state).thenReturn(WorkInfo.State.ENQUEUED)
            whenever(terminalWorkInfo.state).thenReturn(WorkInfo.State.SUCCEEDED)
            stubAppendPolicy()

            runBlocking { EmailBatchWorker.enqueue(context, (1L..400L).toList()) }

            verify(workManager).beginUniqueWork(
                eq(EmailBatchWorker.WORK_NAME),
                eq(ExistingWorkPolicy.APPEND),
                any<OneTimeWorkRequest>(),
            )
            verify(workContinuation).enqueue()
        } finally {
            WorkManagerDelegate.uninstall()
        }
    }

    @Test
    fun `enqueue replaces when only terminal batch work exists`() {
        WorkManagerDelegate.install(workManager)
        try {
            whenever(workInfosFuture.get()).thenReturn(listOf(workInfo, terminalWorkInfo))
            whenever(workInfo.state).thenReturn(WorkInfo.State.SUCCEEDED)
            whenever(terminalWorkInfo.state).thenReturn(WorkInfo.State.FAILED)
            whenever(
                workManager.beginUniqueWork(
                    eq(EmailBatchWorker.WORK_NAME),
                    eq(ExistingWorkPolicy.REPLACE),
                    any<OneTimeWorkRequest>(),
                ),
            ).thenReturn(workContinuation)

            runBlocking { EmailBatchWorker.enqueue(context, (1L..400L).toList()) }

            verify(workManager).beginUniqueWork(
                eq(EmailBatchWorker.WORK_NAME),
                eq(ExistingWorkPolicy.REPLACE),
                any<OneTimeWorkRequest>(),
            )
            verify(workManager, never()).beginUniqueWork(
                eq(EmailBatchWorker.WORK_NAME),
                eq(ExistingWorkPolicy.APPEND),
                any<OneTimeWorkRequest>(),
            )
            verify(workContinuation).enqueue()
        } finally {
            WorkManagerDelegate.uninstall()
        }
    }

    @Test
    fun `enqueue appends when the batch work state query fails`() {
        WorkManagerDelegate.install(workManager)
        try {
            whenever(workInfosFuture.get()).thenThrow(ExecutionException(RuntimeException("query failed")))
            stubAppendPolicy()

            runBlocking { EmailBatchWorker.enqueue(context, (1L..400L).toList()) }

            verify(workManager).beginUniqueWork(
                eq(EmailBatchWorker.WORK_NAME),
                eq(ExistingWorkPolicy.APPEND),
                any<OneTimeWorkRequest>(),
            )
            verify(workContinuation).enqueue()
        } finally {
            WorkManagerDelegate.uninstall()
        }
    }

    @Test
    fun `enqueue rethrows cancellation instead of treating the batch as live`() {
        WorkManagerDelegate.install(workManager)
        try {
            whenever(workInfosFuture.get()).thenThrow(CancellationException("cancelled"))

            assertFailsWith<CancellationException> {
                runBlocking { EmailBatchWorker.enqueue(context, (1L..400L).toList()) }
            }

            verify(workManager, never()).beginUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        } finally {
            WorkManagerDelegate.uninstall()
        }
    }

    @Test
    fun `enqueue creates multiple chunks when ids exceed the maximum batch size`() {
        WorkManagerDelegate.install(workManager)
        try {
            whenever(
                workManager.beginUniqueWork(
                    eq(EmailBatchWorker.WORK_NAME),
                    eq(ExistingWorkPolicy.REPLACE),
                    any<OneTimeWorkRequest>(),
                ),
            ).thenReturn(workContinuation)
            whenever(workContinuation.then(any<OneTimeWorkRequest>())).thenReturn(chainedContinuation)

            runBlocking { EmailBatchWorker.enqueue(context, (1L..500L).toList()) }

            verify(workContinuation).then(any<OneTimeWorkRequest>())
            verify(chainedContinuation).enqueue()
        } finally {
            WorkManagerDelegate.uninstall()
        }
    }
}
