package app.kotowski.sms2email.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.impl.WorkManagerImpl
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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
import java.util.concurrent.ExecutionException
import kotlin.test.assertFalse

class SmsRetrySchedulerTest {
    @Mock private lateinit var context: Context

    @Mock private lateinit var smsRepository: SmsRepository

    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var workManager: WorkManagerImpl

    @Mock private lateinit var workInfosFuture: ListenableFuture<List<WorkInfo>>

    @Mock private lateinit var runningWorkInfo: WorkInfo

    @Mock private lateinit var enqueuedWorkInfo: WorkInfo

    @Mock private lateinit var exactWakeArmer: ExactWakeArmer

    private lateinit var closeable: AutoCloseable

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        whenever(context.getString(R.string.error_retry_period_exceeded)).thenReturn("period exceeded")
        whenever(context.getString(R.string.sms_status_forwarding_stopped)).thenReturn("Forwarding stopped before sending")
        // Forwarding is on by default: the off-park tests re-stub it off per test.
        whenever(prefs.load()).thenReturn(AppSettings())
        WorkManagerDelegate.install(workManager)
        // No live per-row work by default: enqueueSend picks REPLACE; APPEND tests
        // re-stub the future. isDone=true puts await() on its fast path (stubbed get()).
        whenever(workManager.getWorkInfosForUniqueWork(any())).thenReturn(workInfosFuture)
        whenever(workInfosFuture.isDone()).thenReturn(true)
        whenever(workInfosFuture.get()).thenReturn(emptyList())
    }

    @After
    fun tearDown() {
        WorkManagerDelegate.uninstall()
        closeable.close()
    }

    private fun scheduler() = SmsRetryScheduler(context, smsRepository, prefs, exactWakeArmer)

    private suspend fun stubTransition(result: Boolean) {
        whenever(
            smsRepository.transitionSmsStatus(any(), any(), any(), any()),
        ).thenReturn(result)
    }

    @Test
    fun `not expired transitions to RETRYING and enqueues email_send work`() =
        runTest {
            val firstAttempt = System.currentTimeMillis() - 1_000
            val sms =
                Sms(
                    id = 42,
                    messageBody = "hi",
                    phoneNumber = "+1",
                    retryCount = 2,
                    timeFirstSendAttempt = firstAttempt,
                )
            stubTransition(true)

            val scheduled = scheduler().scheduleRetry(42, sms, "smtp down")

            assertTrue(scheduled)
            val updateCaptor = argumentCaptor<StatusUpdate>()
            verify(smsRepository).transitionSmsStatus(
                eq(42L),
                eq(listOf(SmsStatus.SENDING)),
                eq(SmsStatus.RETRYING),
                updateCaptor.capture(),
            )
            assertEquals("smtp down", updateCaptor.firstValue.statusDetails)
            assertEquals(3, updateCaptor.firstValue.retryCount)
            assertEquals(firstAttempt, updateCaptor.firstValue.timeFirstSendAttempt)
            assertNotNull(updateCaptor.firstValue.timeLastSendAttempt)
            assertNotNull(updateCaptor.firstValue.timeNextSendAttempt)
            val captor = argumentCaptor<OneTimeWorkRequest>()
            verify(workManager).enqueueUniqueWork(eq("email_send_42"), eq(ExistingWorkPolicy.REPLACE), captor.capture())
            val spec = captor.firstValue.workSpec
            assertEquals(42L, spec.input.getLong(SMS_ID_KEY, -1))
            assertTrue(spec.initialDelay > 0)
            assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
            assertEquals(EmailWorker::class.java.name, spec.workerClassName)
        }

    @Test
    fun `null first attempt time is set to now`() =
        runTest {
            val sms =
                Sms(
                    id = 9,
                    messageBody = "hi",
                    phoneNumber = "+1",
                    retryCount = 0,
                    timeFirstSendAttempt = null,
                )
            stubTransition(true)

            val scheduled = scheduler().scheduleRetry(9, sms, "err")

            assertTrue(scheduled)
            val updateCaptor = argumentCaptor<StatusUpdate>()
            verify(smsRepository).transitionSmsStatus(
                eq(9L),
                eq(listOf(SmsStatus.SENDING)),
                eq(SmsStatus.RETRYING),
                updateCaptor.capture(),
            )
            assertEquals("err", updateCaptor.firstValue.statusDetails)
            assertEquals(1, updateCaptor.firstValue.retryCount)
            assertNotNull(updateCaptor.firstValue.timeFirstSendAttempt)
            verify(workManager).enqueueUniqueWork(
                eq("email_send_9"),
                eq(ExistingWorkPolicy.REPLACE),
                any<OneTimeWorkRequest>(),
            )
        }

    @Test
    fun `does not enqueue and returns false when the CAS loses the race`() =
        runTest {
            val sms =
                Sms(
                    id = 42,
                    messageBody = "hi",
                    phoneNumber = "+1",
                    retryCount = 2,
                    timeFirstSendAttempt = System.currentTimeMillis() - 1_000,
                )
            stubTransition(false)

            val scheduled = scheduler().scheduleRetry(42, sms, "smtp down")

            assertFalse(scheduled)
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }

    @Test
    fun `scheduleFirstRetry resets the retry window and enqueues email_send work`() =
        runTest {
            val before = System.currentTimeMillis()
            stubTransition(true)

            val scheduled = scheduler().scheduleFirstRetry(42, "waiting for internet")

            assertTrue(scheduled)
            val updateCaptor = argumentCaptor<StatusUpdate>()
            verify(smsRepository).transitionSmsStatus(
                eq(42L),
                eq(SmsStatus.MANUAL_FORWARDABLE),
                eq(SmsStatus.RETRYING),
                updateCaptor.capture(),
            )
            assertEquals("waiting for internet", updateCaptor.firstValue.statusDetails)
            assertEquals(1, updateCaptor.firstValue.retryCount)
            assertNotNull(updateCaptor.firstValue.timeFirstSendAttempt)
            val firstAttempt = updateCaptor.firstValue.timeFirstSendAttempt!!
            assertTrue(firstAttempt in before..System.currentTimeMillis())
            assertEquals(firstAttempt, updateCaptor.firstValue.timeLastSendAttempt)
            assertTrue(updateCaptor.firstValue.timeNextSendAttempt!! > firstAttempt)
            val captor = argumentCaptor<OneTimeWorkRequest>()
            verify(workManager).enqueueUniqueWork(eq("email_send_42"), eq(ExistingWorkPolicy.REPLACE), captor.capture())
            val spec = captor.firstValue.workSpec
            assertEquals(42L, spec.input.getLong(SMS_ID_KEY, -1))
            assertTrue(spec.initialDelay > 0)
            assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
            assertEquals(EmailWorker::class.java.name, spec.workerClassName)
        }

    @Test
    fun `scheduleFirstRetry does not enqueue and returns false when the CAS loses the race`() =
        runTest {
            stubTransition(false)

            val scheduled = scheduler().scheduleFirstRetry(42, "waiting for internet")

            assertFalse(scheduled)
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }

    // A narrowed caller (the queue's offline park) must reach the CAS, or a delivered
    // row would be parked RETRYING and re-sent when the internet returns.
    @Test
    fun `scheduleFirstRetry uses the caller-narrowed expected statuses for the CAS`() =
        runTest {
            stubTransition(true)

            val scheduled =
                scheduler().scheduleFirstRetry(42, "waiting for internet", listOf(SmsStatus.PENDING, SmsStatus.RETRYING))

            assertTrue(scheduled)
            verify(smsRepository).transitionSmsStatus(
                eq(42L),
                eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                eq(SmsStatus.RETRYING),
                any(),
            )
            verify(workManager).enqueueUniqueWork(eq("email_send_42"), eq(ExistingWorkPolicy.REPLACE), any<OneTimeWorkRequest>())
        }

    // While the per-row work is RUNNING (the worker scheduling this very retry),
    // enqueueSend must APPEND — a REPLACE would cancel live work for a finalized row.
    @Test
    fun `scheduleRetry appends while the per-row work is running instead of replacing it`() =
        runTest {
            val sms =
                Sms(
                    id = 42,
                    messageBody = "hi",
                    phoneNumber = "+1",
                    retryCount = 2,
                    timeFirstSendAttempt = System.currentTimeMillis() - 1_000,
                )
            stubTransition(true)
            whenever(workInfosFuture.get()).thenReturn(listOf(runningWorkInfo))
            whenever(runningWorkInfo.state).thenReturn(WorkInfo.State.RUNNING)

            val scheduled = scheduler().scheduleRetry(42, sms, "smtp down")

            assertTrue(scheduled)
            verify(workManager).getWorkInfosForUniqueWork(eq("email_send_42"))
            verify(workManager).enqueueUniqueWork(eq("email_send_42"), eq(ExistingWorkPolicy.APPEND), any<OneTimeWorkRequest>())
        }

    @Test
    fun `scheduleFirstRetry appends while the per-row work is running instead of replacing it`() =
        runTest {
            stubTransition(true)
            whenever(workInfosFuture.get()).thenReturn(listOf(runningWorkInfo))
            whenever(runningWorkInfo.state).thenReturn(WorkInfo.State.RUNNING)

            val scheduled = scheduler().scheduleFirstRetry(42, "waiting for internet")

            assertTrue(scheduled)
            verify(workManager).enqueueUniqueWork(eq("email_send_42"), eq(ExistingWorkPolicy.APPEND), any<OneTimeWorkRequest>())
        }

    // A work-state query failure must look live: APPEND rather than risk REPLACE-cancelling
    // a possibly running work (the safe direction).
    @Test
    fun `scheduleRetry appends when the work state query fails`() =
        runTest {
            val sms =
                Sms(
                    id = 42,
                    messageBody = "hi",
                    phoneNumber = "+1",
                    retryCount = 2,
                    timeFirstSendAttempt = System.currentTimeMillis() - 1_000,
                )
            stubTransition(true)
            whenever(workInfosFuture.get()).thenThrow(ExecutionException(RuntimeException("wm down")))

            val scheduled = scheduler().scheduleRetry(42, sms, "smtp down")

            assertTrue(scheduled)
            verify(workManager).enqueueUniqueWork(eq("email_send_42"), eq(ExistingWorkPolicy.APPEND), any<OneTimeWorkRequest>())
        }

    @Test
    fun `scheduleFailed marks the row FAILED with the mapped reason and enqueues no work`() =
        runTest {
            val sms =
                Sms(
                    id = 42,
                    messageBody = "hi",
                    phoneNumber = "+1",
                    retryCount = 2,
                    timeFirstSendAttempt = System.currentTimeMillis() - 1_000,
                )
            stubTransition(true)

            val failed = scheduler().scheduleFailed(42, sms, "Recipient does not exist")

            assertTrue(failed)
            val updateCaptor = argumentCaptor<StatusUpdate>()
            verify(smsRepository).transitionSmsStatus(
                eq(42L),
                eq(listOf(SmsStatus.SENDING)),
                eq(SmsStatus.FAILED),
                updateCaptor.capture(),
            )
            assertEquals("Recipient does not exist", updateCaptor.firstValue.statusDetails)
            assertEquals(3, updateCaptor.firstValue.retryCount)
            assertNull(updateCaptor.firstValue.timeFirstSendAttempt)
            assertNotNull(updateCaptor.firstValue.timeLastSendAttempt)
            assertNull(updateCaptor.firstValue.timeNextSendAttempt)
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }

    @Test
    fun `scheduleFailed returns false when the CAS loses the race`() =
        runTest {
            val sms =
                Sms(
                    id = 42,
                    messageBody = "hi",
                    phoneNumber = "+1",
                    retryCount = 2,
                    timeFirstSendAttempt = System.currentTimeMillis() - 1_000,
                )
            stubTransition(false)

            val failed = scheduler().scheduleFailed(42, sms, "err")

            assertFalse(failed)
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }

    @Test
    fun `expired message is marked FAILED and no work is enqueued`() =
        runTest {
            val sms =
                Sms(
                    id = 7,
                    messageBody = "hi",
                    phoneNumber = "+1",
                    retryCount = 5,
                    timeFirstSendAttempt = System.currentTimeMillis() - AppConfig.MAX_RETRY_PERIOD_MS - 1,
                )
            stubTransition(true)

            val scheduled = scheduler().scheduleRetry(7, sms, "err")

            assertTrue(scheduled)
            val updateCaptor = argumentCaptor<StatusUpdate>()
            verify(smsRepository).transitionSmsStatus(
                eq(7L),
                eq(listOf(SmsStatus.SENDING)),
                eq(SmsStatus.FAILED),
                updateCaptor.capture(),
            )
            assertEquals("period exceeded", updateCaptor.firstValue.statusDetails)
            assertEquals(6, updateCaptor.firstValue.retryCount)
            assertNull(updateCaptor.firstValue.timeFirstSendAttempt)
            assertNotNull(updateCaptor.firstValue.timeLastSendAttempt)
            assertNull(updateCaptor.firstValue.timeNextSendAttempt)
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }

    // Like the reconciler's one-shot park: a send failing after the flag flips off must
    // park IGNORED, not schedule a retry nothing will run (sweep cancelled, work skips).
    @Test
    fun `scheduleRetry parks the row IGNORED with the forwarding-stopped detail when forwarding is off`() =
        runTest {
            val sms =
                Sms(
                    id = 42,
                    messageBody = "hi",
                    phoneNumber = "+1",
                    retryCount = 2,
                    timeFirstSendAttempt = System.currentTimeMillis() - 1_000,
                )
            whenever(prefs.load()).thenReturn(AppSettings(isForwardingEnabled = false))
            stubTransition(true)

            val scheduled = scheduler().scheduleRetry(42, sms, "smtp down")

            assertTrue(scheduled)
            val updateCaptor = argumentCaptor<StatusUpdate>()
            verify(smsRepository).transitionSmsStatus(
                eq(42L),
                eq(listOf(SmsStatus.SENDING)),
                eq(SmsStatus.IGNORED),
                updateCaptor.capture(),
            )
            assertEquals("Forwarding stopped before sending", updateCaptor.firstValue.statusDetails)
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }

    // Off wins over expiry, like the reconciler's park: expiration is the sweep's call,
    // and the sweep is cancelled while off.
    @Test
    fun `scheduleRetry parks the row IGNORED instead of FAILED when forwarding is off and the period expired`() =
        runTest {
            val sms =
                Sms(
                    id = 7,
                    messageBody = "hi",
                    phoneNumber = "+1",
                    retryCount = 5,
                    timeFirstSendAttempt = System.currentTimeMillis() - AppConfig.MAX_RETRY_PERIOD_MS - 1,
                )
            whenever(prefs.load()).thenReturn(AppSettings(isForwardingEnabled = false))
            stubTransition(true)

            val scheduled = scheduler().scheduleRetry(7, sms, "err")

            assertTrue(scheduled)
            verify(smsRepository).transitionSmsStatus(
                eq(7L),
                eq(listOf(SmsStatus.SENDING)),
                eq(SmsStatus.IGNORED),
                any<StatusUpdate>(),
            )
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }

    @Test
    fun `scheduleRetry returns false when the off-park CAS loses the race`() =
        runTest {
            val sms =
                Sms(
                    id = 42,
                    messageBody = "hi",
                    phoneNumber = "+1",
                    retryCount = 2,
                    timeFirstSendAttempt = System.currentTimeMillis() - 1_000,
                )
            whenever(prefs.load()).thenReturn(AppSettings(isForwardingEnabled = false))
            stubTransition(false)

            val scheduled = scheduler().scheduleRetry(42, sms, "smtp down")

            assertFalse(scheduled)
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }

    @Test
    fun `failIfExpired transitions to FAILED with the exceeded message and the passed expected statuses`() =
        runTest {
            val firstAttempt = System.currentTimeMillis() - AppConfig.MAX_RETRY_PERIOD_MS - 1
            stubTransition(true)

            val failed = scheduler().failIfExpired(42, firstAttempt, 5, listOf(SmsStatus.PENDING, SmsStatus.RETRYING))

            assertTrue(failed)
            val updateCaptor = argumentCaptor<StatusUpdate>()
            verify(smsRepository).transitionSmsStatus(
                eq(42L),
                eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                eq(SmsStatus.FAILED),
                updateCaptor.capture(),
            )
            assertEquals("period exceeded", updateCaptor.firstValue.statusDetails)
            assertEquals(5, updateCaptor.firstValue.retryCount)
            assertNull(updateCaptor.firstValue.timeFirstSendAttempt)
            assertNotNull(updateCaptor.firstValue.timeLastSendAttempt)
            assertNull(updateCaptor.firstValue.timeNextSendAttempt)
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }

    @Test
    fun `failIfExpired returns false and transitions nothing when not expired`() =
        runTest {
            val firstAttempt = System.currentTimeMillis() - 1_000

            val failed = scheduler().failIfExpired(42, firstAttempt, 2, listOf(SmsStatus.RETRYING))

            assertFalse(failed)
            verify(smsRepository, never()).transitionSmsStatus(any(), any(), any(), any())
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }

    @Test
    fun `failIfExpired treats a missing first attempt as not expired`() =
        runTest {
            val failed = scheduler().failIfExpired(42, null, 0, listOf(SmsStatus.SENDING))

            assertFalse(failed)
            verify(smsRepository, never()).transitionSmsStatus(any(), any(), any(), any())
        }

    @Test
    fun `failIfExpired returns false when the CAS loses the race`() =
        runTest {
            stubTransition(false)
            val firstAttempt = System.currentTimeMillis() - AppConfig.MAX_RETRY_PERIOD_MS - 1

            val failed = scheduler().failIfExpired(42, firstAttempt, 5, listOf(SmsStatus.RETRYING))

            assertFalse(failed)
        }

    // The 7th attempt's backoff (5 s * 2^7, ±10% jitter) is always past the 5-minute
    // arming threshold; the 3rd's (40 s) always under it.
    @Test
    fun `scheduleRetry arms the retry token for backoffs past the threshold`() =
        runTest {
            val sms =
                Sms(
                    id = 42,
                    messageBody = "hi",
                    phoneNumber = "+1",
                    retryCount = 6,
                    timeFirstSendAttempt = System.currentTimeMillis() - 1_000,
                )
            stubTransition(true)

            scheduler().scheduleRetry(42, sms, "smtp down")

            verify(exactWakeArmer).rearmRetryAlarm(eq(42L), any<Long>())
        }

    @Test
    fun `scheduleRetry cancels but does not arm the token for short backoffs`() =
        runTest {
            val sms =
                Sms(
                    id = 42,
                    messageBody = "hi",
                    phoneNumber = "+1",
                    retryCount = 2,
                    timeFirstSendAttempt = System.currentTimeMillis() - 1_000,
                )
            stubTransition(true)

            scheduler().scheduleRetry(42, sms, "smtp down")

            val delay = argumentCaptor<Long>()
            verify(exactWakeArmer).rearmRetryAlarm(eq(42L), delay.capture())
            assertTrue(delay.firstValue < AppConfig.EXACT_WAKE_ARM_THRESHOLD_MS)
        }

    @Test
    fun `scheduleRetry off-park cancels the token`() =
        runTest {
            val sms =
                Sms(
                    id = 42,
                    messageBody = "hi",
                    phoneNumber = "+1",
                    retryCount = 2,
                    timeFirstSendAttempt = System.currentTimeMillis() - 1_000,
                )
            whenever(prefs.load()).thenReturn(AppSettings(isForwardingEnabled = false))
            stubTransition(true)

            scheduler().scheduleRetry(42, sms, "smtp down")

            verify(exactWakeArmer).cancelRetryAlarm(42L)
            verify(exactWakeArmer, never()).rearmRetryAlarm(any(), any())
        }

    @Test
    fun `scheduleRetry expired cancels the token`() =
        runTest {
            val sms =
                Sms(
                    id = 7,
                    messageBody = "hi",
                    phoneNumber = "+1",
                    retryCount = 5,
                    timeFirstSendAttempt = System.currentTimeMillis() - AppConfig.MAX_RETRY_PERIOD_MS - 1,
                )
            stubTransition(true)

            scheduler().scheduleRetry(7, sms, "err")

            verify(exactWakeArmer).cancelRetryAlarm(7L)
        }

    @Test
    fun `scheduleFailed cancels the token`() =
        runTest {
            val sms =
                Sms(
                    id = 42,
                    messageBody = "hi",
                    phoneNumber = "+1",
                    retryCount = 2,
                    timeFirstSendAttempt = System.currentTimeMillis() - 1_000,
                )
            stubTransition(true)

            scheduler().scheduleFailed(42, sms, "Recipient does not exist")

            verify(exactWakeArmer).cancelRetryAlarm(42L)
        }

    @Test
    fun `scheduleFirstRetry cancels the stale token without arming a new one`() =
        runTest {
            stubTransition(true)

            scheduler().scheduleFirstRetry(42, "waiting for internet")

            val delay = argumentCaptor<Long>()
            verify(exactWakeArmer).rearmRetryAlarm(eq(42L), delay.capture())
            assertTrue(delay.firstValue < AppConfig.EXACT_WAKE_ARM_THRESHOLD_MS)
        }

    @Test
    fun `drainAfterSuccess re-fires the enqueued retrying rows and stamps the cooldown`() =
        runTest {
            whenever(smsRepository.getRetryingIds()).thenReturn(listOf(5L, 6L))
            // Row 5 is the row just sent (excluded from the drain); row 6's work is ENQUEUED.
            whenever(workInfosFuture.get()).thenReturn(listOf(enqueuedWorkInfo))
            whenever(enqueuedWorkInfo.state).thenReturn(WorkInfo.State.ENQUEUED)

            scheduler().drainAfterSuccess(5L)

            verify(exactWakeArmer).cancelRetryAlarm(5L)
            // No blind cancel: the re-fire relies on enqueueSend's own policy (REPLACE for
            // the stale ENQUEUED work), which already drops it.
            verify(workManager, never()).cancelUniqueWork(any<String>())
            verify(workManager).enqueueUniqueWork(eq("email_send_6"), eq(ExistingWorkPolicy.REPLACE), any<OneTimeWorkRequest>())
            verify(prefs).lastDrainAtMillis = any()
        }

    // The re-fire race: ENQUEUED when the guard reads it, RUNNING by enqueueSend's
    // re-check — must APPEND, never cancel, or a mid-flight send becomes a duplicate.
    @Test
    fun `drainAfterSuccess appends instead of cancelling when the work starts running in the gap`() =
        runTest {
            whenever(smsRepository.getRetryingIds()).thenReturn(listOf(5L, 6L))
            // Only row 6 queries (row 5 is the row just sent), so the sequential stubs are
            // deterministic.
            whenever(enqueuedWorkInfo.state).thenReturn(WorkInfo.State.ENQUEUED)
            whenever(runningWorkInfo.state).thenReturn(WorkInfo.State.RUNNING)
            whenever(workInfosFuture.get())
                .thenReturn(listOf(enqueuedWorkInfo))
                .thenReturn(listOf(runningWorkInfo))

            scheduler().drainAfterSuccess(5L)

            verify(workManager, never()).cancelUniqueWork(any<String>())
            verify(workManager).enqueueUniqueWork(eq("email_send_6"), eq(ExistingWorkPolicy.APPEND), any<OneTimeWorkRequest>())
            verify(prefs).lastDrainAtMillis = any()
        }

    @Test
    fun `drainAfterSuccess within the cooldown re-fires nothing and cancels nothing`() =
        runTest {
            whenever(prefs.lastDrainAtMillis).thenReturn(System.currentTimeMillis() - 1_000)

            scheduler().drainAfterSuccess(5L)

            verify(smsRepository, never()).getRetryingIds()
            verify(exactWakeArmer, never()).cancelRetryAlarm(any())
            verify(prefs, never()).lastDrainAtMillis = any()
        }

    @Test
    fun `drainAfterSuccess skips rows whose per-row work is running or absent`() =
        runTest {
            whenever(smsRepository.getRetryingIds()).thenReturn(listOf(5L, 6L, 7L))
            whenever(workInfosFuture.get()).thenReturn(listOf(runningWorkInfo))
            whenever(runningWorkInfo.state).thenReturn(WorkInfo.State.RUNNING)

            scheduler().drainAfterSuccess(5L)

            verify(workManager, never()).cancelUniqueWork(any<String>())
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            verify(prefs).lastDrainAtMillis = any()
        }

    @Test
    fun `drainAfterSuccess skips the rows but stamps the cooldown when the guard queries fail`() =
        runTest {
            whenever(smsRepository.getRetryingIds()).thenReturn(listOf(5L, 6L))
            whenever(workInfosFuture.get()).thenThrow(ExecutionException(RuntimeException("wm down")))

            scheduler().drainAfterSuccess(5L)

            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            verify(prefs).lastDrainAtMillis = any()
        }
}
