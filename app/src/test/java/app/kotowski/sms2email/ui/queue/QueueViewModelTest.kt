package app.kotowski.sms2email.ui.queue

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkContinuation
import androidx.work.WorkInfo
import androidx.work.impl.WorkManagerImpl
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.Sms
import app.kotowski.sms2email.data.database.SmsListScope
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsSearch
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.SmsUiModel
import app.kotowski.sms2email.data.database.StatusUpdate
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.ContactChangeObserver
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.system.NetworkConnectivityObserver
import app.kotowski.sms2email.system.NetworkStatus
import app.kotowski.sms2email.system.SimChangeObserver
import app.kotowski.sms2email.system.SimNameResolver
import app.kotowski.sms2email.testing.WorkManagerDelegate
import app.kotowski.sms2email.testing.pumpUntil
import app.kotowski.sms2email.testing.smsModel
import app.kotowski.sms2email.testing.stubInternetEnabled
import app.kotowski.sms2email.testing.toUiModel
import app.kotowski.sms2email.testing.withMockedToast
import app.kotowski.sms2email.ui.smslist.SmsListUiState
import app.kotowski.sms2email.work.BATCH_IDS_KEY
import app.kotowski.sms2email.work.EmailBatchWorker
import app.kotowski.sms2email.work.SmsActions
import app.kotowski.sms2email.work.SmsRetryScheduler
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.MockedStatic
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.KArgumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class QueueViewModelTest {
    @Mock private lateinit var appContext: Context

    @Mock private lateinit var repository: SmsRepository

    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var simNameResolver: SimNameResolver

    @Mock private lateinit var simChangeObserver: SimChangeObserver

    @Mock private lateinit var contactChangeObserver: ContactChangeObserver

    @Mock private lateinit var contactResolver: ContactResolver

    @Mock private lateinit var retryScheduler: SmsRetryScheduler

    @Mock private lateinit var networkObserver: NetworkConnectivityObserver

    @Mock private lateinit var workManager: WorkManagerImpl

    @Mock private lateinit var workContinuation: WorkContinuation

    @Mock private lateinit var workInfosFuture: ListenableFuture<List<WorkInfo>>

    @Mock private lateinit var connectivityManager: ConnectivityManager

    @Mock private lateinit var network: Network

    @Mock private lateinit var networkCapabilities: NetworkCapabilities

    private val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()

    // The mocked queue window (the newest loadedLimit + 1 undelivered rows) and the
    // unfiltered queue total.
    private val windowFlow = MutableStateFlow<List<SmsUiModel>>(emptyList())

    private val countFlow = MutableStateFlow(0)

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        whenever(workManager.beginUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()))
            .thenReturn(workContinuation)
        // No live batch work by default: enqueue picks REPLACE
        whenever(workManager.getWorkInfosByTag(EmailBatchWorker.BATCH_TAG)).thenReturn(workInfosFuture)
        // isDone=true puts await() on its fast path, which calls the stubbed no-arg get().
        whenever(workInfosFuture.isDone()).thenReturn(true)
        whenever(workInfosFuture.get()).thenReturn(emptyList())
        whenever(prefs.load()).thenReturn(AppSettings())
        // No live SIM changes in these tests: the collector completes immediately.
        whenever(simChangeObserver.simChanges).thenReturn(emptyFlow())
        // No live contact changes in these tests: the refresh collector stays idle.
        whenever(contactChangeObserver.contactChanges).thenReturn(emptyFlow())
        whenever(appContext.getString(any<Int>())).thenReturn("No Internet connection.")
        whenever(appContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)
        whenever(connectivityManager.activeNetwork).thenReturn(null)
        whenever(networkObserver.isConnectedCurrently()).thenReturn(true)
        whenever(networkObserver.networkStatus).thenReturn(flowOf(NetworkStatus.Available))
    }

    // Forward toasts are resolved texts (the mapper interpolates before the toast
    // call): pin the resource's text — the generic setUp stub answers every id the
    // same — and return it for the toast verification.
    private fun pinForwardToast(resId: Int): String {
        val text = "toast $resId"
        whenever(appContext.getString(resId)).thenReturn(text)
        return text
    }

    private var lastVm: QueueViewModel? = null

    @After
    fun tearDown() {
        // Settle the search pipeline before Main is swapped: the real-IO search build
        // resumes on the test dispatcher while it is still installed, so the resumption
        // can be pumped here; isLoading false proves no cross-thread work is in flight.
        lastVm?.let { vm ->
            val deadline = System.currentTimeMillis() + 2_000
            while (vm.state.value.paged.isLoading && System.currentTimeMillis() < deadline) {
                testDispatcher.scheduler.advanceTimeBy(AppConfig.SMS_SEARCH_DEBOUNCE_MS)
                testDispatcher.scheduler.runCurrent()
                Thread.sleep(5)
            }
            // Cancel the VM scope so no collector survives the test, then pump the
            // cancellation while the test dispatcher is still installed: a continuation
            // that reaches setMain mid-call fails with "Dispatchers.Main is used concurrently with setting it".
            vm.viewModelScope.coroutineContext[Job]?.cancel()
            testDispatcher.scheduler.advanceTimeBy(AppConfig.SMS_SEARCH_DEBOUNCE_MS)
            testDispatcher.scheduler.runCurrent()
        }
        lastVm = null
        // Park Main on an unpumped dispatcher instead of resetMain: a stray IO
        // continuation can resume after the test, and resetMain would send it to the
        // unmocked real Main and crash a worker thread.
        Dispatchers.setMain(StandardTestDispatcher())
    }

    private suspend fun createViewModel(queuedSms: List<Sms> = emptyList()): QueueViewModel {
        // The window is the UI projection, the forward path reads the entity.
        windowFlow.value = queuedSms.map { it.toUiModel() }
        countFlow.value = queuedSms.size
        whenever(repository.observeSms(eq(SmsListScope.QUEUE), any<SmsSearch>(), anyInt())).thenReturn(windowFlow)
        whenever(repository.observeCount(SmsListScope.QUEUE)).thenReturn(countFlow)
        // "Forward all" works on the whole queue through the DB, not on the loaded window.
        whenever(repository.getQueueIds()).thenReturn(queuedSms.map { it.id })
        whenever(repository.getSmsByIds(any<List<Long>>())).thenReturn(queuedSms)
        // The real actions over the same fakes: the tests verify the repository and
        // worker side effects through the action pipeline.
        val vm =
            QueueViewModel(
                appContext,
                repository,
                prefs,
                contactResolver,
                simNameResolver,
                simChangeObserver,
                contactChangeObserver,
                SmsActions(repository, appContext, prefs, retryScheduler, simNameResolver),
                networkObserver,
            )
        lastVm = vm
        return vm
    }

    // The window query is debounced (SMS_SEARCH_DEBOUNCE_MS of virtual time), so the
    // first emission only lands once the scheduler's clock has advanced past it.
    private suspend fun awaitLoaded(vm: QueueViewModel) {
        while (vm.state.value.paged.isLoading) {
            testDispatcher.scheduler.advanceTimeBy(AppConfig.SMS_SEARCH_DEBOUNCE_MS)
            testDispatcher.scheduler.runCurrent()
            kotlinx.coroutines.delay(5)
        }
        testDispatcher.scheduler.runCurrent()
    }

    // Waits until the loaded window has `expectedSize` rows: the grown window arrives
    // through the real-IO buildSearch, so the arrival is not synchronous.
    private suspend fun awaitWindowSize(
        vm: QueueViewModel,
        expectedSize: Int,
    ) {
        val deadline = System.currentTimeMillis() + 3_000
        while (vm.state.value.paged.smsList.size != expectedSize && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceTimeBy(10)
            testDispatcher.scheduler.runCurrent()
            kotlinx.coroutines.delay(5)
        }
        assertEquals(expectedSize, vm.state.value.paged.smsList.size)
    }

    // Waits until the repository observes a search different from `baseline`: buildSearch
    // runs on the real IO dispatcher, so the re-query is not synchronous. The re-verify is
    // required — a Mockito captor only records values while a verify() call matches.
    private suspend fun awaitNewSearch(
        vm: QueueViewModel,
        captor: KArgumentCaptor<SmsSearch>,
        baseline: SmsSearch,
    ) {
        val deadline = System.currentTimeMillis() + 3_000
        while (captor.allValues.lastOrNull() == baseline && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceTimeBy(10)
            testDispatcher.scheduler.runCurrent()
            verify(repository, atLeastOnce()).observeSms(eq(SmsListScope.QUEUE), captor.capture(), anyInt())
            kotlinx.coroutines.delay(5)
        }
    }

    private fun enableInternet() = stubInternetEnabled(connectivityManager, network, networkCapabilities)

    @Test
    fun `initial state is empty and loading`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            val s = vm.state.value
            assertTrue(s.paged.smsList.isEmpty())
            assertTrue(s.paged.isLoading)
            assertFalse(s.displaySimCards)

            awaitLoaded(vm)
            assertFalse(vm.state.value.paged.isLoading)
            assertTrue(
                vm.state.value.paged.smsList
                    .isEmpty(),
            )
        }

    @Test
    fun `the loaded window is shown as emitted by the repository`() =
        runTest(testDispatcher) {
            val pending = Sms(id = 1, messageBody = "Hi", phoneNumber = "+123", status = SmsStatus.PENDING)
            val retrying = Sms(id = 2, messageBody = "Err", phoneNumber = "+789", status = SmsStatus.RETRYING)

            val vm = createViewModel(listOf(pending, retrying))
            awaitLoaded(vm)
            val s = vm.state.value

            assertFalse(s.paged.isLoading)
            assertEquals(2, s.paged.smsList.size)
            assertEquals(2, s.paged.smsCount)
        }

    @Test
    fun `empty queue shows empty list`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            awaitLoaded(vm)
            val s = vm.state.value
            assertTrue(s.paged.smsList.isEmpty())
            assertFalse(s.paged.isLoading)
        }

    @Test
    fun `SmsListUiState defaults are correct`() {
        // paged is a PagedSmsListState, whose initial state is "loading" (the window
        // has not landed yet): the default follows the controller's initial state.
        val state = SmsListUiState()
        assertTrue(state.paged.smsList.isEmpty())
        assertTrue(state.paged.isLoading)
        assertTrue(state.paged.simNames.isEmpty())
        assertTrue(state.paged.contactNames.isEmpty())
        assertFalse(state.displaySimCards)
        assertTrue(state.hasInternet)
        assertEquals(0, state.paged.smsCount)
        assertFalse(state.paged.hasMore)
    }

    @Test
    fun `the unfiltered total feeds the state for the title`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            countFlow.value = 99

            testDispatcher.scheduler.runCurrent()

            assertEquals(99, vm.state.value.paged.smsCount)
        }

    @Test
    fun `the loaded window is one page and hasMore reflects the probe row`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            // Newest first, 20 rows plus the probe row: the window keeps the first 20.
            windowFlow.value = (21L downTo 1L).map { smsModel(it, SmsStatus.PENDING) }

            awaitLoaded(vm)

            val s = vm.state.value
            assertEquals(AppConfig.SMS_LIST_PAGE_SIZE, s.paged.smsList.size)
            assertEquals(
                21L,
                s.paged.smsList
                    .first()
                    .id,
            )
            assertTrue(s.paged.hasMore)
        }

    @Test
    fun `hasMore is false when the probe row is absent`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            windowFlow.value = (20L downTo 1L).map { smsModel(it, SmsStatus.PENDING) }

            awaitLoaded(vm)

            val s = vm.state.value
            assertEquals(AppConfig.SMS_LIST_PAGE_SIZE, s.paged.smsList.size)
            assertFalse(s.paged.hasMore)
        }

    @Test
    fun `loadMore grows the window by one page`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            windowFlow.value = (41L downTo 1L).map { smsModel(it, SmsStatus.PENDING) }
            awaitLoaded(vm)

            // The window query re-runs with the grown limit (window plus probe).
            vm.loadMore()
            awaitWindowSize(vm, 2 * AppConfig.SMS_LIST_PAGE_SIZE)

            val captor = argumentCaptor<Int>()
            verify(repository, atLeastOnce()).observeSms(eq(SmsListScope.QUEUE), any<SmsSearch>(), captor.capture())
            assertTrue(captor.allValues.contains(AppConfig.SMS_LIST_PAGE_SIZE * 2 + 1))
        }

    @Test
    fun `loadMore is a no-op when there is nothing more to load`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            windowFlow.value = (10L downTo 1L).map { smsModel(it, SmsStatus.PENDING) }
            awaitLoaded(vm)
            assertFalse(vm.state.value.paged.hasMore)

            vm.loadMore()

            // Pump past where a (wrong) grow would have re-queried with a bigger limit:
            // buildSearch hops to the real IO dispatcher, so the re-query is not synchronous.
            val deadline = System.currentTimeMillis() + 500
            while (System.currentTimeMillis() < deadline) {
                testDispatcher.scheduler.advanceTimeBy(10)
                testDispatcher.scheduler.runCurrent()
                kotlinx.coroutines.delay(5)
            }
            val captor = argumentCaptor<Int>()
            verify(repository, atLeastOnce()).observeSms(eq(SmsListScope.QUEUE), any<SmsSearch>(), captor.capture())
            assertTrue(captor.allValues.max() <= AppConfig.SMS_LIST_PAGE_SIZE + 1)
        }

    @Test
    fun `setSearchQuery re-runs the window query after the debounce`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            awaitLoaded(vm)
            val captor = argumentCaptor<SmsSearch>()
            verify(repository, atLeastOnce()).observeSms(eq(SmsListScope.QUEUE), captor.capture(), anyInt())
            val baseline = captor.allValues.last()

            vm.setSearchQuery("hello")
            awaitNewSearch(vm, captor, baseline)

            val search = captor.allValues.last()
            assertFalse(search.noSearch)
            assertEquals("%hello%", search.likePattern)
            assertTrue(search.contactNumbers.isEmpty())
            assertTrue(search.simIds.isEmpty())
        }

    @Test
    fun `the contact facet is resolved only while contact resolution is on`() =
        runTest(testDispatcher) {
            whenever(prefs.load()).thenReturn(AppSettings(useContactResolution = true))
            whenever(contactResolver.resolveNumbersByName("john")).thenReturn(listOf("5550100"))
            val vm = createViewModel()
            awaitLoaded(vm)
            val captor = argumentCaptor<SmsSearch>()
            verify(repository, atLeastOnce()).observeSms(eq(SmsListScope.QUEUE), captor.capture(), anyInt())
            val baseline = captor.allValues.last()

            vm.setSearchQuery("john")
            awaitNewSearch(vm, captor, baseline)

            assertEquals(listOf("5550100"), captor.allValues.last().contactNumbers)
        }

    @Test
    fun `the sim facet matches slot names when the display is on`() =
        runTest(testDispatcher) {
            whenever(prefs.load()).thenReturn(AppSettings(displaySimCards = true))
            whenever(simNameResolver.getSlotEntries()).thenReturn(listOf(1 to "CarrierOne", 2 to "CarrierTwo"))
            val vm = createViewModel()
            awaitLoaded(vm)
            val captor = argumentCaptor<SmsSearch>()
            verify(repository, atLeastOnce()).observeSms(eq(SmsListScope.QUEUE), captor.capture(), anyInt())
            val baseline = captor.allValues.last()

            vm.setSearchQuery("carrierone")
            awaitNewSearch(vm, captor, baseline)

            assertEquals(listOf(1), captor.allValues.last().simIds)
        }

    @Test
    fun `forwardAll does nothing when queue is empty`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            vm.forwardAll()
            verify(
                repository,
                never(),
            ).transitionSmsStatus(any(), any(), any(), any())
        }

    @Test
    fun `forwardAll toasts and does nothing when forwarding is disabled`() =
        runTest(testDispatcher) {
            whenever(prefs.load()).thenReturn(AppSettings(isForwardingEnabled = false))
            val pending = Sms(id = 1, messageBody = "a", phoneNumber = "+1", status = SmsStatus.PENDING)

            val vm = createViewModel(listOf(pending))
            val offText = pinForwardToast(R.string.sms_forward_forwarding_off)
            withMockedToast { toast ->
                vm.forwardAll()
                toast.verify { Toast.makeText(eq(appContext), eq(offText), eq(Toast.LENGTH_SHORT)) }
            }

            verify(repository, never()).transitionSmsStatusMany(any(), any(), any(), any())
            verify(workManager, never()).beginUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }

    @Test
    fun `forwardAll covers rows beyond the loaded window`() =
        runTest(testDispatcher) {
            WorkManagerDelegate.install(workManager)
            try {
                val inWindow = (1L..2L).map { Sms(id = it, messageBody = "m$it", phoneNumber = "+$it", status = SmsStatus.PENDING) }
                val beyondWindow = (3L..5L).map { Sms(id = it, messageBody = "m$it", phoneNumber = "+$it", status = SmsStatus.RETRYING) }

                val vm = createViewModel(inWindow + beyondWindow)
                // The screen has only loaded its first window: the rest is not in the state.
                windowFlow.value = inWindow.map { it.toUiModel() }
                whenever(
                    repository.transitionSmsStatusMany(
                        eq(listOf(1L, 2L, 3L, 4L, 5L)),
                        eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                        eq(SmsStatus.RETRYING),
                        any(),
                    ),
                ).thenReturn(listOf(1L, 2L, 3L, 4L, 5L))

                vm.forwardAll()

                verify(repository).transitionSmsStatusMany(
                    eq(listOf(1L, 2L, 3L, 4L, 5L)),
                    eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                    eq(SmsStatus.RETRYING),
                    any(),
                )
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }

    @Test
    fun `forwardAll enqueues only ids whose CAS transition succeeded`() =
        runTest(testDispatcher) {
            WorkManagerDelegate.install(workManager)
            try {
                val pending = Sms(id = 1, messageBody = "a", phoneNumber = "+1", status = SmsStatus.PENDING)
                val retrying = Sms(id = 2, messageBody = "b", phoneNumber = "+2", status = SmsStatus.RETRYING)
                val sending = Sms(id = 3, messageBody = "c", phoneNumber = "+3", status = SmsStatus.SENDING)
                whenever(
                    repository.transitionSmsStatusMany(
                        eq(listOf(1L, 2L)),
                        eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                        eq(SmsStatus.RETRYING),
                        any(),
                    ),
                ).thenReturn(listOf(1L, 2L))

                val vm = createViewModel(listOf(pending, retrying, sending))
                val blockedText = pinForwardToast(R.string.sms_forward_blocked_single)
                withMockedToast { toast ->
                    vm.forwardAll()
                    // The in-flight row counts as blocked (found = 3, scheduled = 2):
                    // the singular reads for the blocked count (1).
                    toast.verify { Toast.makeText(eq(appContext), eq(blockedText), eq(Toast.LENGTH_SHORT)) }
                }

                val updateCaptor = argumentCaptor<StatusUpdate>()
                verify(repository).transitionSmsStatusMany(
                    eq(listOf(1L, 2L)),
                    eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                    eq(SmsStatus.RETRYING),
                    updateCaptor.capture(),
                )
                val update = updateCaptor.firstValue
                assertNull(update.statusDetails)
                assertEquals(0, update.retryCount)
                assertNotNull(update.timeFirstSendAttempt)
                assertNotNull(update.timeLastSendAttempt)
                assertNotNull(update.timeNextSendAttempt)
                val captor = argumentCaptor<OneTimeWorkRequest>()
                verify(workManager).beginUniqueWork(eq(EmailBatchWorker.WORK_NAME), eq(ExistingWorkPolicy.REPLACE), captor.capture())
                verify(workContinuation).enqueue()
                assertEquals(
                    "1,2",
                    captor.firstValue.workSpec.input
                        .getString(BATCH_IDS_KEY),
                )
                verify(workManager, never()).enqueue(any<OneTimeWorkRequest>())
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }

    @Test
    fun `forwardAll skips enqueue entirely when every CAS transition fails`() =
        runTest(testDispatcher) {
            WorkManagerDelegate.install(workManager)
            try {
                val pending = Sms(id = 1, messageBody = "a", phoneNumber = "+1", status = SmsStatus.PENDING)
                whenever(
                    repository.transitionSmsStatusMany(any(), any(), any(), any()),
                ).thenReturn(emptyList())

                val vm = createViewModel(listOf(pending))
                val blockedText = pinForwardToast(R.string.sms_forward_blocked_single)
                withMockedToast { toast ->
                    vm.forwardAll()
                    // One row lost the CAS: the singular reads for the blocked count (1).
                    toast.verify { Toast.makeText(eq(appContext), eq(blockedText), eq(Toast.LENGTH_SHORT)) }
                }

                verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
                verify(workManager, never()).beginUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
                verify(workManager, never()).enqueue(any<OneTimeWorkRequest>())
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }

    @Test
    fun `forwardAll counts the in-flight row as blocked when a queued row is not taken`() =
        runTest(testDispatcher) {
            WorkManagerDelegate.install(workManager)
            try {
                val pending = Sms(id = 1, messageBody = "a", phoneNumber = "+1", status = SmsStatus.PENDING)
                val retrying = Sms(id = 2, messageBody = "b", phoneNumber = "+2", status = SmsStatus.RETRYING)
                val sending = Sms(id = 3, messageBody = "c", phoneNumber = "+3", status = SmsStatus.SENDING)
                whenever(
                    repository.transitionSmsStatusMany(
                        eq(listOf(1L, 2L)),
                        eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                        eq(SmsStatus.RETRYING),
                        any(),
                    ),
                ).thenReturn(listOf(1L))

                val vm = createViewModel(listOf(pending, retrying, sending))
                val blockedText = pinForwardToast(R.string.sms_forward_blocked_multiple)
                withMockedToast { toast ->
                    vm.forwardAll()
                    // The in-flight row counts as blocked with the CAS loser (found = 3,
                    // scheduled = 1, blocked = 2): the plural reads for the blocked count.
                    toast.verify { Toast.makeText(eq(appContext), eq(blockedText), eq(Toast.LENGTH_SHORT)) }
                }

                val captor = argumentCaptor<OneTimeWorkRequest>()
                verify(workManager).beginUniqueWork(eq(EmailBatchWorker.WORK_NAME), eq(ExistingWorkPolicy.REPLACE), captor.capture())
                verify(workContinuation).enqueue()
                assertEquals(
                    "1",
                    captor.firstValue.workSpec.input
                        .getString(BATCH_IDS_KEY),
                )
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }

    @Test
    fun `cancelAll parks queued rows as IGNORED and leaves SENDING untouched`() =
        runTest(testDispatcher) {
            val pending = Sms(id = 1, messageBody = "a", phoneNumber = "+1", status = SmsStatus.PENDING)
            val retrying = Sms(id = 2, messageBody = "b", phoneNumber = "+2", status = SmsStatus.RETRYING)
            val sending = Sms(id = 3, messageBody = "c", phoneNumber = "+3", status = SmsStatus.SENDING)
            whenever(
                repository.transitionSmsStatusMany(
                    eq(listOf(1L, 2L)),
                    eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                    eq(SmsStatus.IGNORED),
                    any(),
                ),
            ).thenReturn(listOf(1L, 2L))

            // Pin the exact resource: the generic getString stub in setUp answers every
            // id with the same text, so the cancel detail must be stubbed per-resource.
            whenever(appContext.getString(R.string.sms_status_cancelled_by_user)).thenReturn("Canceled by user.")

            val vm = createViewModel(listOf(pending, retrying, sending))
            vm.cancelAll()

            // The in-flight SENDING row is filtered out up front; the rest is parked as
            // IGNORED with the user-cancel detail (re-sendable from the History).
            val updateCaptor = argumentCaptor<StatusUpdate>()
            verify(repository).transitionSmsStatusMany(
                eq(listOf(1L, 2L)),
                eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                eq(SmsStatus.IGNORED),
                updateCaptor.capture(),
            )
            assertEquals("Canceled by user.", updateCaptor.firstValue.statusDetails)
        }

    @Test
    fun `forwardAll shows a toast and does nothing when the queue holds only SENDING rows`() =
        runTest(testDispatcher) {
            val sending = Sms(id = 1, messageBody = "a", phoneNumber = "+1", status = SmsStatus.SENDING)

            val vm = createViewModel(listOf(sending))
            val blockedText = pinForwardToast(R.string.sms_forward_blocked_single)
            withMockedToast { toast ->
                vm.forwardAll()
                // The whole queue (1 row) is in flight: the singular reads for that count.
                toast.verify { Toast.makeText(eq(appContext), eq(blockedText), eq(Toast.LENGTH_SHORT)) }
            }

            verify(repository, never()).transitionSmsStatusMany(any(), any(), any(), any())
        }

    @Test
    fun `cancelAll shows a toast and does nothing when the queue holds only SENDING rows`() =
        runTest(testDispatcher) {
            val sending = Sms(id = 1, messageBody = "a", phoneNumber = "+1", status = SmsStatus.SENDING)

            val vm = createViewModel(listOf(sending))
            withMockedToast { toast ->
                vm.cancelAll()
                // One row requested, fully blocked: the singular reads for the requested count.
                toast.verify { Toast.makeText(eq(appContext), eq(R.string.sms_cancel_blocked_single), eq(Toast.LENGTH_SHORT)) }
            }

            verify(repository, never()).transitionSmsStatusMany(any(), any(), any(), any())
        }

    @Test
    fun `cancelAll does nothing when queue is empty`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            vm.cancelAll()
            verify(
                repository,
                never(),
            ).transitionSmsStatusMany(any(), any(), any(), any())
        }

    @Test
    fun `cancelAll shows a toast when a queued row was claimed in the meantime`() =
        runTest(testDispatcher) {
            val pending = Sms(id = 1, messageBody = "a", phoneNumber = "+1", status = SmsStatus.PENDING)
            val retrying = Sms(id = 2, messageBody = "b", phoneNumber = "+2", status = SmsStatus.RETRYING)
            whenever(
                repository.transitionSmsStatusMany(
                    eq(listOf(1L, 2L)),
                    eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                    eq(SmsStatus.IGNORED),
                    any(),
                ),
            ).thenReturn(listOf(1L))

            val vm = createViewModel(listOf(pending, retrying))
            withMockedToast { toast ->
                vm.cancelAll()
                // One of two rows lost the CAS: the singular reads for the blocked count (1).
                toast.verify { Toast.makeText(eq(appContext), eq(R.string.sms_cancel_blocked_single), eq(Toast.LENGTH_SHORT)) }
            }
        }

    @Test
    fun `cancelSelected parks the selected in-flight rows as IGNORED and leaves SENDING untouched`() =
        runTest(testDispatcher) {
            val pending = Sms(id = 1, messageBody = "a", phoneNumber = "+1", status = SmsStatus.PENDING)
            val retrying = Sms(id = 2, messageBody = "b", phoneNumber = "+2", status = SmsStatus.RETRYING)
            val sending = Sms(id = 3, messageBody = "c", phoneNumber = "+3", status = SmsStatus.SENDING)
            whenever(
                repository.transitionSmsStatusMany(
                    eq(listOf(1L, 2L)),
                    eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                    eq(SmsStatus.IGNORED),
                    any(),
                ),
            ).thenReturn(listOf(1L, 2L))
            whenever(appContext.getString(R.string.sms_status_cancelled_by_user)).thenReturn("Canceled by user.")

            val vm = createViewModel(listOf(pending, retrying, sending))
            vm.enterSelectionMode()
            vm.toggleSelection(1L)
            vm.toggleSelection(2L)
            vm.toggleSelection(3L)
            withMockedToast { toast ->
                vm.cancelSelected()
                toast.verify(MockedStatic<Toast>.Verification { Toast.makeText(any<Context>(), any<Int>(), any<Int>()) }, never())
            }

            // The selection is parked, not deleted: the SENDING row is filtered out up
            // front, the rest goes IGNORED with the user-cancel detail (re-sendable from
            // the History, where it can be deleted).
            verify(repository, never()).deleteSmsByIds(any<List<Long>>())
            val updateCaptor = argumentCaptor<StatusUpdate>()
            verify(repository).transitionSmsStatusMany(
                eq(listOf(1L, 2L)),
                eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                eq(SmsStatus.IGNORED),
                updateCaptor.capture(),
            )
            assertEquals("Canceled by user.", updateCaptor.firstValue.statusDetails)
            assertFalse(vm.state.value.selectionMode)
        }

    @Test
    fun `cancelSelected shows a toast and does nothing when the selection holds only SENDING rows`() =
        runTest(testDispatcher) {
            val sending = Sms(id = 1, messageBody = "a", phoneNumber = "+1", status = SmsStatus.SENDING)

            val vm = createViewModel(listOf(sending))
            vm.enterSelectionMode()
            vm.toggleSelection(1L)
            withMockedToast { toast ->
                vm.cancelSelected()
                // One row selected, fully blocked: the singular reads for the requested count.
                toast.verify { Toast.makeText(eq(appContext), eq(R.string.sms_cancel_blocked_single), eq(Toast.LENGTH_SHORT)) }
            }

            verify(repository, never()).transitionSmsStatusMany(any(), any(), any(), any())
            verify(repository, never()).deleteSmsByIds(any<List<Long>>())
        }

    @Test
    fun `cancelSelected shows a toast when a selected row was claimed in the meantime`() =
        runTest(testDispatcher) {
            val pending = Sms(id = 1, messageBody = "a", phoneNumber = "+1", status = SmsStatus.PENDING)
            val retrying = Sms(id = 2, messageBody = "b", phoneNumber = "+2", status = SmsStatus.RETRYING)
            whenever(
                repository.transitionSmsStatusMany(
                    eq(listOf(1L, 2L)),
                    eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                    eq(SmsStatus.IGNORED),
                    any(),
                ),
            ).thenReturn(listOf(1L))

            val vm = createViewModel(listOf(pending, retrying))
            vm.enterSelectionMode()
            vm.toggleSelection(1L)
            vm.toggleSelection(2L)
            withMockedToast { toast ->
                vm.cancelSelected()
                // One of two rows lost the CAS: the singular reads for the blocked count (1).
                toast.verify { Toast.makeText(eq(appContext), eq(R.string.sms_cancel_blocked_single), eq(Toast.LENGTH_SHORT)) }
            }
        }

    @Test
    fun `cancelSelected with no selection does not touch the repository`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            vm.cancelSelected()
            verify(
                repository,
                never(),
            ).transitionSmsStatusMany(any(), any(), any(), any())
            verify(repository, never()).deleteSmsByIds(any<List<Long>>())
        }

    @Test
    fun `toggleSelection enters selection mode and selects item`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            vm.enterSelectionMode()
            vm.toggleSelection(1L)
            val s = vm.state.value
            assertTrue(s.selectionMode)
            assertEquals(setOf(1L), s.selectedIds)
        }

    @Test
    fun `toggleSelection deselects item when more than one selected`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            vm.enterSelectionMode()
            vm.toggleSelection(1L)
            vm.toggleSelection(2L)
            vm.toggleSelection(1L)
            val s = vm.state.value
            assertTrue(s.selectionMode)
            assertEquals(setOf(2L), s.selectedIds)
        }

    @Test
    fun `toggleSelection exits selection mode when last item is deselected`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            vm.enterSelectionMode()
            vm.toggleSelection(1L)
            vm.toggleSelection(1L)
            val s = vm.state.value
            assertFalse(s.selectionMode)
            assertTrue(s.selectedIds.isEmpty())
        }

    @Test
    fun `toggleSelection stays in selection mode when deselecting one of two selected`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            vm.enterSelectionMode()
            vm.toggleSelection(1L)
            vm.toggleSelection(2L)
            vm.toggleSelection(2L)
            val s = vm.state.value
            assertTrue(s.selectionMode)
            assertEquals(setOf(1L), s.selectedIds)
        }

    @Test
    fun `forwardSelected online enqueues only ids whose CAS transition succeeded`() =
        runTest(testDispatcher) {
            WorkManagerDelegate.install(workManager)
            try {
                enableInternet()
                // createViewModel stubs getSmsByIds with the window rows; the test's stub
                // must come after it to win.
                val vm = createViewModel()
                whenever(repository.getSmsByIds(any<List<Long>>())).thenReturn(
                    listOf(
                        Sms(id = 1, messageBody = "a", phoneNumber = "+1", status = SmsStatus.PENDING),
                        Sms(id = 2, messageBody = "b", phoneNumber = "+2", status = SmsStatus.RETRYING),
                        Sms(id = 3, messageBody = "c", phoneNumber = "+3", status = SmsStatus.SENDING),
                    ),
                )
                // The queue narrows the forward CAS to its in-flight statuses (a
                // delivered row must not be re-forwarded from the list); the in-flight
                // SENDING row is split off before the CAS and counts as blocked only.
                whenever(
                    repository.transitionSmsStatusMany(
                        eq(listOf(1L, 2L)),
                        eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                        eq(SmsStatus.PENDING),
                        any(),
                    ),
                ).thenReturn(listOf(1L))

                vm.enterSelectionMode()
                vm.toggleSelection(1L)
                vm.toggleSelection(2L)
                vm.toggleSelection(3L)
                val blockedText = pinForwardToast(R.string.sms_forward_blocked_multiple)
                withMockedToast { toast ->
                    vm.forwardSelected()
                    // Two of three rows did not transition: the plural reads for the blocked count.
                    toast.verify { Toast.makeText(eq(appContext), eq(blockedText), eq(Toast.LENGTH_SHORT)) }
                }

                val updateCaptor = argumentCaptor<StatusUpdate>()
                verify(repository).transitionSmsStatusMany(
                    eq(listOf(1L, 2L)),
                    eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                    eq(SmsStatus.PENDING),
                    updateCaptor.capture(),
                )
                val update = updateCaptor.firstValue
                assertNull(update.statusDetails)
                assertEquals(0, update.retryCount)
                assertNotNull(update.timeFirstSendAttempt)
                assertNull(update.timeLastSendAttempt)
                assertNull(update.timeNextSendAttempt)
                val captor = argumentCaptor<OneTimeWorkRequest>()
                verify(workManager).beginUniqueWork(eq(EmailBatchWorker.WORK_NAME), eq(ExistingWorkPolicy.REPLACE), captor.capture())
                verify(workContinuation).enqueue()
                assertEquals(
                    "1",
                    captor.firstValue.workSpec.input
                        .getString(BATCH_IDS_KEY),
                )
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }

    @Test
    fun `forwardSelected offline schedules first retry per selected id`() =
        runTest(testDispatcher) {
            // createViewModel stubs getSmsByIds with the window rows; the test's stub
            // must come after it to win.
            val vm = createViewModel()
            whenever(repository.getSmsByIds(any<List<Long>>())).thenReturn(
                listOf(
                    Sms(id = 1, messageBody = "a", phoneNumber = "+1", status = SmsStatus.PENDING),
                    Sms(id = 2, messageBody = "b", phoneNumber = "+2", status = SmsStatus.RETRYING),
                ),
            )
            whenever(retryScheduler.scheduleFirstRetry(any(), any(), any())).thenReturn(false)
            vm.enterSelectionMode()
            vm.toggleSelection(1L)
            vm.toggleSelection(2L)
            val blockedText = pinForwardToast(R.string.sms_forward_blocked_multiple)
            withMockedToast { toast ->
                vm.forwardSelected()
                // Neither row could be parked: the plural reads for the blocked count.
                toast.verify { Toast.makeText(eq(appContext), eq(blockedText), eq(Toast.LENGTH_SHORT)) }
            }

            // The queue narrows the park CAS to its in-flight statuses, like the forward CAS.
            verify(retryScheduler).scheduleFirstRetry(
                eq(1L),
                eq("No Internet connection."),
                eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
            )
            verify(retryScheduler).scheduleFirstRetry(
                eq(2L),
                eq("No Internet connection."),
                eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
            )
            verify(
                repository,
                never(),
            ).transitionSmsStatus(any(), any(), any(), any())
        }

    @Test
    fun `forwardSelected toasts and does nothing when forwarding is disabled`() =
        runTest(testDispatcher) {
            whenever(prefs.load()).thenReturn(AppSettings(isForwardingEnabled = false))

            val vm = createViewModel()
            vm.enterSelectionMode()
            vm.toggleSelection(1L)
            vm.toggleSelection(2L)
            val offText = pinForwardToast(R.string.sms_forward_forwarding_off)
            withMockedToast { toast ->
                vm.forwardSelected()
                toast.verify { Toast.makeText(eq(appContext), eq(offText), eq(Toast.LENGTH_SHORT)) }
            }

            verify(repository, never()).transitionSmsStatusMany(any(), any(), any(), any())
            verify(retryScheduler, never()).scheduleFirstRetry(any(), any(), any())
            verify(workManager, never()).beginUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }

    // The cancel + selection_cleared toast fire in the window collector, which under the
    // unconfined Main resumes on a real IO thread, where the thread-local static Toast
    // mock cannot intercept. A standard Main on the same scheduler pins it to the test thread.
    @Test
    fun `selection is cancelled with a toast when a selected row leaves the window`() {
        val standardMain = StandardTestDispatcher(testDispatcher.scheduler)
        Dispatchers.setMain(standardMain)
        try {
            runTest(testDispatcher) {
                val pending = Sms(id = 1, messageBody = "a", phoneNumber = "+1", status = SmsStatus.PENDING)
                val retrying = Sms(id = 2, messageBody = "b", phoneNumber = "+2", status = SmsStatus.RETRYING)

                val vm = createViewModel(listOf(pending, retrying))
                awaitLoaded(vm)
                vm.enterSelectionMode()
                vm.toggleSelection(1L)
                vm.toggleSelection(2L)
                testDispatcher.scheduler.runCurrent()
                assertTrue(vm.state.value.selectionMode)
                assertEquals(setOf(1L, 2L), vm.state.value.selectedIds)

                // Row 1 was delivered by a worker: it leaves the queue window, and the
                // DB now reports it SENT — outside the queue scope, so the watcher
                // (which checks missing ids against the DB) still clears the selection.
                whenever(repository.getSmsByIds(any<List<Long>>())).thenReturn(
                    listOf(Sms(id = 1, messageBody = "a", phoneNumber = "+1", status = SmsStatus.SENT)),
                )
                withMockedToast { toast ->
                    windowFlow.value = listOf(retrying.toUiModel())
                    pumpUntil(testDispatcher.scheduler) { !vm.state.value.selectionMode }
                    toast.verify { Toast.makeText(eq(appContext), eq(R.string.selection_cleared), eq(Toast.LENGTH_SHORT)) }
                }

                assertFalse(vm.state.value.selectionMode)
                assertTrue(
                    vm.state.value.selectedIds
                        .isEmpty(),
                )
            }
        } finally {
            Dispatchers.setMain(testDispatcher)
        }
    }

    @Test
    fun `selection survives a window emission that still contains the selected rows`() =
        runTest(testDispatcher) {
            val pending = Sms(id = 1, messageBody = "a", phoneNumber = "+1", status = SmsStatus.PENDING)
            val retrying = Sms(id = 2, messageBody = "b", phoneNumber = "+2", status = SmsStatus.RETRYING)

            val vm = createViewModel(listOf(pending, retrying))
            awaitLoaded(vm)
            vm.enterSelectionMode()
            vm.toggleSelection(1L)
            vm.toggleSelection(2L)

            withMockedToast { toast ->
                // A status flip inside the queue scope re-emits the window with the same
                // rows: nothing leaves, so the selection (and no toast) must stay.
                windowFlow.value = listOf(pending.toUiModel(), retrying.toUiModel().copy(status = SmsStatus.SENDING))
                pumpUntil(testDispatcher.scheduler) {
                    vm.state.value.paged.smsList
                        .firstOrNull { it.id == 2L }
                        ?.status == SmsStatus.SENDING
                }
                toast.verify(MockedStatic<Toast>.Verification { Toast.makeText(any<Context>(), any<Int>(), any<Int>()) }, never())
            }

            assertTrue(vm.state.value.selectionMode)
            assertEquals(setOf(1L, 2L), vm.state.value.selectedIds)
        }

    // Editing the search re-windows the list under the selection: the mode exits at the
    // edit with the selection_cleared_search toast (the user's own keystroke is the
    // cause, not changed messages), and the query applies.
    @Test
    fun `searching while in selection mode exits the selection and applies the query`() =
        runTest(testDispatcher) {
            val pending = Sms(id = 1, messageBody = "a", phoneNumber = "+1", status = SmsStatus.PENDING)
            val retrying = Sms(id = 2, messageBody = "b", phoneNumber = "+2", status = SmsStatus.RETRYING)

            val vm = createViewModel(listOf(pending, retrying))
            awaitLoaded(vm)
            val captor = argumentCaptor<SmsSearch>()
            verify(repository, atLeastOnce()).observeSms(eq(SmsListScope.QUEUE), captor.capture(), anyInt())
            val baseline = captor.allValues.last()

            vm.enterSelectionMode()
            vm.toggleSelection(1L)
            withMockedToast { toast ->
                vm.setSearchQuery("hello")
                toast.verify { Toast.makeText(eq(appContext), eq(R.string.selection_cleared_search), eq(Toast.LENGTH_SHORT)) }
            }
            awaitNewSearch(vm, captor, baseline)

            assertFalse(vm.state.value.selectionMode)
            assertTrue(
                vm.state.value.selectedIds
                    .isEmpty(),
            )
            assertFalse(captor.allValues.last().noSearch)
        }

    // The X-clear re-windows too (the unfiltered window is capped, and a selected row
    // can sit below it): it exits the selection like any other query change.
    @Test
    fun `clearing the search while in selection mode exits the selection`() =
        runTest(testDispatcher) {
            val pending = Sms(id = 1, messageBody = "a", phoneNumber = "+1", status = SmsStatus.PENDING)

            val vm = createViewModel(listOf(pending))
            awaitLoaded(vm)

            // Search first, then select: the active query is what the X button clears.
            vm.setSearchQuery("hello")
            vm.enterSelectionMode()
            vm.toggleSelection(1L)

            withMockedToast { toast ->
                vm.setSearchQuery("")
                toast.verify { Toast.makeText(eq(appContext), eq(R.string.selection_cleared_search), eq(Toast.LENGTH_SHORT)) }
            }

            assertFalse(vm.state.value.selectionMode)
            assertTrue(
                vm.state.value.selectedIds
                    .isEmpty(),
            )
        }

    // A rotation re-composes the screen: the search state is not restored, so the fresh
    // state re-announces the (unchanged, empty) query through setSearchQuery. That is a
    // recomposition, not an edit: the selection survives without a toast.
    @Test
    fun `re-announcing an unchanged query keeps the selection`() =
        runTest(testDispatcher) {
            val pending = Sms(id = 1, messageBody = "a", phoneNumber = "+1", status = SmsStatus.PENDING)

            val vm = createViewModel(listOf(pending))
            awaitLoaded(vm)

            // The first composition announced the empty query; the selection came after.
            vm.setSearchQuery("")
            vm.enterSelectionMode()
            vm.toggleSelection(1L)

            withMockedToast { toast ->
                vm.setSearchQuery("")
                toast.verify(MockedStatic<Toast>.Verification { Toast.makeText(any<Context>(), any<Int>(), any<Int>()) }, never())
            }

            assertTrue(vm.state.value.selectionMode)
            assertEquals(setOf(1L), vm.state.value.selectedIds)
        }

    // A new arrival pushes the selected tail row below the window cap: the row is still
    // in the queue (the DB says so), so the selection survives without a toast instead
    // of the watcher clearing it for a row that left nothing but the viewport.
    @Test
    fun `selection survives a selected row pushed below the window cap`() {
        val standardMain = StandardTestDispatcher(testDispatcher.scheduler)
        Dispatchers.setMain(standardMain)
        try {
            runTest(testDispatcher) {
                val row1 = Sms(id = 1, messageBody = "a", phoneNumber = "+1", status = SmsStatus.PENDING)
                val row2 = Sms(id = 2, messageBody = "b", phoneNumber = "+2", status = SmsStatus.RETRYING)
                val row3 = Sms(id = 3, messageBody = "c", phoneNumber = "+3", status = SmsStatus.PENDING)

                // createViewModel stubs getSmsByIds with these rows: the watcher's scope
                // check reports row 1 still PENDING (in the queue scope) when missing.
                val vm = createViewModel(listOf(row3, row2, row1))
                awaitLoaded(vm)
                vm.enterSelectionMode()
                vm.toggleSelection(2L)
                vm.toggleSelection(1L)
                testDispatcher.scheduler.runCurrent()
                assertTrue(vm.state.value.selectionMode)
                assertEquals(setOf(1L, 2L), vm.state.value.selectedIds)

                withMockedToast { toast ->
                    windowFlow.value = listOf(row3.toUiModel(), row2.toUiModel())
                    pumpUntil(testDispatcher.scheduler) {
                        vm.state.value.paged.smsList
                            .map { it.id } == listOf(3L, 2L)
                    }
                    toast.verify(MockedStatic<Toast>.Verification { Toast.makeText(any<Context>(), any<Int>(), any<Int>()) }, never())
                }
                verify(repository).getSmsByIds(eq(listOf(1L)))

                assertTrue(vm.state.value.selectionMode)
                assertEquals(setOf(1L, 2L), vm.state.value.selectedIds)
            }
        } finally {
            Dispatchers.setMain(testDispatcher)
        }
    }

    @Test
    fun `forwardSelected never takes a row that was delivered while selected`() =
        runTest(testDispatcher) {
            WorkManagerDelegate.install(workManager)
            try {
                enableInternet()
                val pending = Sms(id = 1, messageBody = "a", phoneNumber = "+1", status = SmsStatus.PENDING)
                val retrying = Sms(id = 2, messageBody = "b", phoneNumber = "+2", status = SmsStatus.RETRYING)
                val vm = createViewModel(listOf(pending, retrying))
                awaitLoaded(vm)
                // The window still shows row 1, but the DB already delivered it (SENT):
                // the stale selection must not be re-forwarded.
                whenever(repository.getSmsByIds(any<List<Long>>())).thenReturn(
                    listOf(
                        Sms(id = 1, messageBody = "a", phoneNumber = "+1", status = SmsStatus.SENT),
                        retrying,
                    ),
                )
                whenever(
                    repository.transitionSmsStatusMany(
                        eq(listOf(1L, 2L)),
                        eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                        eq(SmsStatus.PENDING),
                        any(),
                    ),
                ).thenReturn(listOf(2L))

                vm.enterSelectionMode()
                vm.toggleSelection(1L)
                vm.toggleSelection(2L)
                val blockedText = pinForwardToast(R.string.sms_forward_blocked_single)
                withMockedToast { toast ->
                    vm.forwardSelected()
                    // The delivered row is blocked: the singular reads for the blocked count (1).
                    toast.verify { Toast.makeText(eq(appContext), eq(blockedText), eq(Toast.LENGTH_SHORT)) }
                }

                // The CAS only accepts the queue's in-flight statuses: a delivered row
                // can never transition back to PENDING through the list path.
                verify(repository).transitionSmsStatusMany(
                    eq(listOf(1L, 2L)),
                    eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                    eq(SmsStatus.PENDING),
                    any(),
                )
                val captor = argumentCaptor<OneTimeWorkRequest>()
                verify(workManager).beginUniqueWork(eq(EmailBatchWorker.WORK_NAME), eq(ExistingWorkPolicy.REPLACE), captor.capture())
                verify(workContinuation).enqueue()
                assertEquals(
                    "2",
                    captor.firstValue.workSpec.input
                        .getString(BATCH_IDS_KEY),
                )
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }
}
