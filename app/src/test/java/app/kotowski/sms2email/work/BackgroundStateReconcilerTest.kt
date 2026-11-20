package app.kotowski.sms2email.work

import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.impl.WorkManagerImpl
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.Sms
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.StatusUpdate
import app.kotowski.sms2email.data.prefs.BackgroundFlags
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.OpenAppIntentProvider
import app.kotowski.sms2email.testing.WorkManagerDelegate
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ForwardingNotification is intentionally not mocked: it is a Kotlin object, so
// mockStatic cannot intercept it. The real show()/dismiss() run with the mock
// context and swallow their own failures, which is all the observer needs.
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundStateReconcilerTest {
    @Mock
    private lateinit var context: Context

    @Mock
    private lateinit var workManager: WorkManagerImpl

    @Mock
    private lateinit var workInfosFuture: ListenableFuture<List<WorkInfo>>

    @Mock
    private lateinit var cleanupWorkerManager: CleanupWorkerManager

    @Mock
    private lateinit var smsRepository: SmsRepository

    @Mock
    private lateinit var prefs: EncryptedPrefs

    @Mock
    private lateinit var exactWakeArmer: ExactWakeArmer

    private lateinit var closeable: AutoCloseable

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var flagsFlow: MutableStateFlow<BackgroundFlags>

    private lateinit var observer: BackgroundStateReconciler

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        WorkManagerDelegate.install(workManager)
        // Default: WorkManager holds no sweep work (fresh install or a reset DB); the
        // unchanged-interval test re-stubs a live one. isDone=true puts await() on its
        // fast path, which calls the stubbed no-arg get().
        whenever(workManager.getWorkInfosForUniqueWork(RetryWorker.WORK_NAME)).thenReturn(workInfosFuture)
        whenever(workInfosFuture.isDone()).thenReturn(true)
        whenever(workInfosFuture.get()).thenReturn(emptyList())
        flagsFlow = MutableStateFlow(BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false))
        whenever(prefs.backgroundFlags).thenReturn(flagsFlow)
        whenever(context.getString(R.string.sms_status_forwarding_stopped)).thenReturn("Forwarding stopped before sending")
        runBlocking {
            whenever(smsRepository.getAutoSendableIds()).thenReturn(emptyList())
            whenever(smsRepository.getStaleSending(any())).thenReturn(emptyList())
        }
        observer =
            BackgroundStateReconciler(
                context,
                prefs,
                smsRepository,
                cleanupWorkerManager,
                OpenAppIntentProvider { Intent() },
                exactWakeArmer,
                CoroutineScope(testDispatcher),
            )
    }

    @After
    fun tearDown() {
        WorkManagerDelegate.uninstall()
        closeable.close()
    }

    private fun startWith(flags: BackgroundFlags) {
        flagsFlow.value = flags
        observer.start()
        testDispatcher.scheduler.runCurrent()
    }

    @Test
    fun `startup with forwarding on cancels the stale work, enqueues the sweep and arms the token`() {
        // The mock's armed-period getter defaults to 0: a fresh arm (cancel + enqueue),
        // the cleanup worker stays cancelled.
        whenever(exactWakeArmer.exactWakeActive()).thenReturn(true)
        startWith(BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false))

        val inOrder = Mockito.inOrder(workManager)
        inOrder.verify(workManager).cancelUniqueWork(RetryWorker.WORK_NAME)
        val captor = argumentCaptor<PeriodicWorkRequest>()
        inOrder
            .verify(
                workManager,
            ).enqueueUniquePeriodicWork(eq(RetryWorker.WORK_NAME), eq(ExistingPeriodicWorkPolicy.KEEP), captor.capture())
        assertEquals(RetryWorker::class.java.name, captor.firstValue.workSpec.workerClassName)
        assertEquals(15 * 60_000L, captor.firstValue.workSpec.intervalDuration)
        verify(exactWakeArmer).armSweepAlarm(any())
        verify(cleanupWorkerManager).cancel()
        verify(cleanupWorkerManager, never()).enqueue()
    }

    @Test
    fun `an unchanged armed interval does not re-enqueue the sweep but re-arms the token`() {
        whenever(prefs.armedSweepIntervalMinutes).thenReturn(15)
        whenever(exactWakeArmer.exactWakeActive()).thenReturn(true)
        // The sweep is alive in WorkManager, so the interval match means "leave it be".
        whenever(workInfosFuture.get()).thenReturn(listOf(mock<WorkInfo>()))

        startWith(BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false))

        verify(workManager, never()).enqueueUniquePeriodicWork(any<String>(), any<ExistingPeriodicWorkPolicy>(), any<PeriodicWorkRequest>())
        verify(workManager, never()).cancelUniqueWork(any<String>())
        verify(exactWakeArmer).armSweepAlarm(any())
    }

    @Test
    fun `an unchanged armed interval re-enqueues the sweep when WorkManager lost it`() {
        // The bug: WorkManager's Room DB was reset (or a prefs-only backup restored) while
        // the pref survived — the interval matches, but no live sweep exists, so it must
        // be re-armed from the live WorkManager state.
        whenever(prefs.armedSweepIntervalMinutes).thenReturn(15)
        whenever(exactWakeArmer.exactWakeActive()).thenReturn(true)
        // setUp default: getWorkInfosForUniqueWork -> emptyList (no live sweep).

        startWith(BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false))

        val captor = argumentCaptor<PeriodicWorkRequest>()
        verify(workManager)
            .enqueueUniquePeriodicWork(eq(RetryWorker.WORK_NAME), eq(ExistingPeriodicWorkPolicy.KEEP), captor.capture())
        assertEquals(15 * 60_000L, captor.firstValue.workSpec.intervalDuration)
        verify(workManager, never()).cancelUniqueWork(any<String>())
        verify(exactWakeArmer).armSweepAlarm(any())
    }

    @Test
    fun `a changed armed interval cancels and re-enqueues the sweep with the new period`() {
        whenever(prefs.armedSweepIntervalMinutes).thenReturn(15)
        whenever(exactWakeArmer.exactWakeActive()).thenReturn(true)

        startWith(
            BackgroundFlags(
                forwardingEnabled = true,
                autoCleanupEnabled = false,
                retrySweepIntervalMinutes = 360,
            ),
        )

        val inOrder = Mockito.inOrder(workManager)
        inOrder.verify(workManager).cancelUniqueWork(RetryWorker.WORK_NAME)
        val captor = argumentCaptor<PeriodicWorkRequest>()
        inOrder.verify(workManager).enqueueUniquePeriodicWork(
            eq(RetryWorker.WORK_NAME),
            eq(ExistingPeriodicWorkPolicy.KEEP),
            captor.capture(),
        )
        assertEquals(360 * 60_000L, captor.firstValue.workSpec.intervalDuration)
        verify(exactWakeArmer).armSweepAlarm(any())
    }

    @Test
    fun `forwarding on with the interval off cancels the sweep and the token`() {
        whenever(prefs.armedSweepIntervalMinutes).thenReturn(15)
        whenever(exactWakeArmer.exactWakeActive()).thenReturn(true)

        startWith(
            BackgroundFlags(
                forwardingEnabled = true,
                autoCleanupEnabled = false,
                retrySweepIntervalMinutes = 0,
            ),
        )

        verify(workManager).cancelUniqueWork(RetryWorker.WORK_NAME)
        verify(workManager, never()).enqueueUniquePeriodicWork(any<String>(), any<ExistingPeriodicWorkPolicy>(), any<PeriodicWorkRequest>())
        verify(exactWakeArmer).cancelSweepAlarm()
        verify(exactWakeArmer, never()).armSweepAlarm(any())
    }

    @Test
    fun `forwarding on with the gate off cancels every token`() =
        runTest(testDispatcher) {
            // The mock's gate defaults to false: the revoke/toggle-off cleanup path.
            // The armer's arm below no-ops internally while the gate is off (ExactWakeArmerTest).
            startWith(BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false))

            verify(exactWakeArmer).cancelAll()
        }

    @Test
    fun `startup with forwarding off parks the queued rows before cancelling the sweep`() =
        runTest(testDispatcher) {
            runBlocking {
                whenever(smsRepository.getAutoSendableIds()).thenReturn(listOf(1L, 2L))
            }

            startWith(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = false))

            val inOrder = Mockito.inOrder(smsRepository, workManager)
            inOrder.verify(smsRepository).transitionSmsStatusMany(
                eq(listOf(1L, 2L)),
                eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                eq(SmsStatus.IGNORED),
                eq(StatusUpdate(statusDetails = "Forwarding stopped before sending")),
            )
            inOrder.verify(workManager).cancelUniqueWork(RetryWorker.WORK_NAME)
            // The parked rows' exact-wake-up tokens are disarmed with the park.
            verify(exactWakeArmer).cancelAllRetryAlarms(listOf(1L, 2L))
        }

    @Test
    fun `startup with forwarding off and an empty queue cancels without parking`() =
        runTest(testDispatcher) {
            startWith(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = false))

            verify(smsRepository, never()).transitionSmsStatusMany(any(), any(), any(), any())
            verify(workManager).cancelUniqueWork(RetryWorker.WORK_NAME)
            verify(exactWakeArmer, never()).cancelAllRetryAlarms(any())
        }

    @Test
    fun `startup with cleanup on enqueues the cleanup worker`() {
        startWith(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = true))

        verify(cleanupWorkerManager).enqueue()
        verify(cleanupWorkerManager, never()).cancel()
    }

    @Test
    fun `a change to off parks and cancels like the import scenario`() =
        runTest(testDispatcher) {
            startWith(BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false))
            verify(workManager).enqueueUniquePeriodicWork(eq(RetryWorker.WORK_NAME), any(), any())

            runBlocking {
                whenever(smsRepository.getAutoSendableIds()).thenReturn(listOf(5L))
            }
            flagsFlow.value = BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = false)
            testDispatcher.scheduler.runCurrent()

            val inOrder = Mockito.inOrder(smsRepository, workManager)
            inOrder.verify(smsRepository).transitionSmsStatusMany(
                eq(listOf(5L)),
                eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                eq(SmsStatus.IGNORED),
                any<StatusUpdate>(),
            )
            inOrder.verify(workManager).cancelUniqueWork(RetryWorker.WORK_NAME)
            verify(exactWakeArmer).cancelAllRetryAlarms(listOf(5L))
        }

    @Test
    fun `a change to on re-enqueues the sweep`() {
        startWith(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = false))
        verify(workManager).cancelUniqueWork(RetryWorker.WORK_NAME)

        flagsFlow.value = BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false)
        testDispatcher.scheduler.runCurrent()

        verify(workManager).enqueueUniquePeriodicWork(eq(RetryWorker.WORK_NAME), eq(ExistingPeriodicWorkPolicy.KEEP), any())
    }

    @Test
    fun `a parking failure still cancels the sweep and runs the cleanup step`() {
        runBlocking {
            whenever(smsRepository.getAutoSendableIds()).thenReturn(listOf(1L))
            whenever(smsRepository.transitionSmsStatusMany(any(), any(), any(), any()))
                .thenThrow(RuntimeException("db down"))
        }

        startWith(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = true))

        verify(workManager).cancelUniqueWork(RetryWorker.WORK_NAME)
        verify(cleanupWorkerManager).enqueue()
        // The park failed, so the rows are not parked and their tokens stay armed.
        verify(exactWakeArmer, never()).cancelAllRetryAlarms(any())
    }

    @Test
    fun `a sweep enqueue failure still runs the cleanup step`() {
        whenever(workManager.enqueueUniquePeriodicWork(eq(RetryWorker.WORK_NAME), any(), any()))
            .thenThrow(RuntimeException("work manager down"))

        startWith(BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = true))

        verify(cleanupWorkerManager).enqueue()
    }

    @Test
    fun `forwarding off parks a stale SENDING orphan as IGNORED with the forwarding-stopped detail`() =
        runTest(testDispatcher) {
            runBlocking {
                whenever(smsRepository.getStaleSending(any()))
                    .thenReturn(listOf(Sms(id = 7, messageBody = "b", phoneNumber = "+7", status = SmsStatus.SENDING)))
            }

            startWith(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = false))

            verify(smsRepository).transitionSmsStatusMany(
                eq(listOf(7L)),
                eq(listOf(SmsStatus.SENDING)),
                eq(SmsStatus.IGNORED),
                eq(StatusUpdate(statusDetails = "Forwarding stopped before sending")),
            )
        }

    @Test
    fun `forwarding off parks stale SENDING only after the sweep is cancelled`() =
        runTest(testDispatcher) {
            runBlocking {
                whenever(smsRepository.getAutoSendableIds()).thenReturn(listOf(1L))
                whenever(smsRepository.getStaleSending(any()))
                    .thenReturn(listOf(Sms(id = 7, messageBody = "b", phoneNumber = "+7", status = SmsStatus.SENDING)))
            }

            startWith(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = false))

            val inOrder = Mockito.inOrder(smsRepository, workManager)
            inOrder.verify(smsRepository).transitionSmsStatusMany(
                eq(listOf(1L)),
                eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                eq(SmsStatus.IGNORED),
                any<StatusUpdate>(),
            )
            inOrder.verify(workManager).cancelUniqueWork(RetryWorker.WORK_NAME)
            inOrder.verify(smsRepository).transitionSmsStatusMany(
                eq(listOf(7L)),
                eq(listOf(SmsStatus.SENDING)),
                eq(SmsStatus.IGNORED),
                any<StatusUpdate>(),
            )
        }

    @Test
    fun `forwarding off with no stale SENDING rows parks only the auto-sendable set`() =
        runTest(testDispatcher) {
            runBlocking {
                whenever(smsRepository.getAutoSendableIds()).thenReturn(listOf(1L, 2L))
                whenever(smsRepository.getStaleSending(any())).thenReturn(emptyList())
            }

            startWith(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = false))

            verify(smsRepository).transitionSmsStatusMany(
                eq(listOf(1L, 2L)),
                eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                eq(SmsStatus.IGNORED),
                any<StatusUpdate>(),
            )
            verify(smsRepository, never()).transitionSmsStatusMany(any(), eq(listOf(SmsStatus.SENDING)), any(), any())
        }

    @Test
    fun `the stale SENDING cutoff uses the sweep's own threshold`() =
        runTest(testDispatcher) {
            val before = System.currentTimeMillis()
            startWith(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = false))
            val after = System.currentTimeMillis()

            val captor = argumentCaptor<Long>()
            verify(smsRepository).getStaleSending(captor.capture())
            val cutoff = captor.firstValue
            assertTrue(cutoff >= before - AppConfig.RETRY_SENDING_STALE_MS)
            assertTrue(cutoff <= after - AppConfig.RETRY_SENDING_STALE_MS)
        }

    @Test
    fun `a stale SENDING park failure still runs the cleanup step`() {
        runBlocking {
            whenever(smsRepository.getStaleSending(any()))
                .thenReturn(listOf(Sms(id = 7, messageBody = "b", phoneNumber = "+7", status = SmsStatus.SENDING)))
            whenever(smsRepository.transitionSmsStatusMany(any(), any(), any(), any()))
                .thenThrow(RuntimeException("db down"))
        }

        startWith(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = true))

        verify(workManager).cancelUniqueWork(RetryWorker.WORK_NAME)
        verify(cleanupWorkerManager).enqueue()
    }

    @Test
    fun `tampered interval value degrades to the default preset`() {
        whenever(prefs.armedSweepIntervalMinutes).thenReturn(15)
        whenever(exactWakeArmer.exactWakeActive()).thenReturn(true)

        startWith(
            BackgroundFlags(
                forwardingEnabled = true,
                autoCleanupEnabled = false,
                retrySweepIntervalMinutes = 9999,
            ),
        )

        val captor = argumentCaptor<PeriodicWorkRequest>()
        verify(workManager)
            .enqueueUniquePeriodicWork(eq(RetryWorker.WORK_NAME), eq(ExistingPeriodicWorkPolicy.KEEP), captor.capture())
        assertEquals(15 * 60_000L, captor.firstValue.workSpec.intervalDuration)
    }

    @Test
    fun `startup reconciles immediately on the test dispatcher`() {
        whenever(exactWakeArmer.exactWakeActive()).thenReturn(true)
        val reconciled = kotlinx.coroutines.sync.Mutex(locked = true)
        whenever(prefs.backgroundFlags).thenReturn(MutableStateFlow(BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false)))

        val localObserver =
            BackgroundStateReconciler(
                context,
                prefs,
                smsRepository,
                cleanupWorkerManager,
                OpenAppIntentProvider { Intent() },
                exactWakeArmer,
                CoroutineScope(testDispatcher),
            )
        localObserver.start()
        testDispatcher.scheduler.runCurrent()

        verify(exactWakeArmer).armSweepAlarm(any())
    }
}
