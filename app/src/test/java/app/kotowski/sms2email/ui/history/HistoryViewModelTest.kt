package app.kotowski.sms2email.ui.history

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkContinuation
import androidx.work.WorkInfo
import androidx.work.impl.WorkManagerImpl
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.SmsListScope
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsSearch
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.SmsUiModel
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.service.SmsImporter
import app.kotowski.sms2email.system.ContactChangeObserver
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.system.NetworkConnectivityObserver
import app.kotowski.sms2email.system.NetworkStatus
import app.kotowski.sms2email.system.PermissionManager
import app.kotowski.sms2email.system.SimChangeObserver
import app.kotowski.sms2email.system.SimNameResolver
import app.kotowski.sms2email.testing.WorkManagerDelegate
import app.kotowski.sms2email.testing.pumpUntil
import app.kotowski.sms2email.testing.sms
import app.kotowski.sms2email.testing.smsModel
import app.kotowski.sms2email.testing.stubInternetEnabled
import app.kotowski.sms2email.testing.withMockedToast
import app.kotowski.sms2email.work.BATCH_IDS_KEY
import app.kotowski.sms2email.work.EmailBatchWorker
import app.kotowski.sms2email.work.SmsActions
import app.kotowski.sms2email.work.SmsRetryScheduler
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
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
class HistoryViewModelTest {
    @Mock private lateinit var repository: SmsRepository

    @Mock private lateinit var smsImporter: SmsImporter

    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var simNameResolver: SimNameResolver

    @Mock private lateinit var simChangeObserver: SimChangeObserver

    @Mock private lateinit var contactChangeObserver: ContactChangeObserver

    @Mock private lateinit var contactResolver: ContactResolver

    @Mock private lateinit var permissionManager: PermissionManager

    @Mock private lateinit var retryScheduler: SmsRetryScheduler

    @Mock private lateinit var networkObserver: NetworkConnectivityObserver

    @Mock private lateinit var context: Context

    @Mock private lateinit var workManager: WorkManagerImpl

    @Mock private lateinit var workContinuation: WorkContinuation

    @Mock private lateinit var workInfosFuture: ListenableFuture<List<WorkInfo>>

    @Mock private lateinit var connectivityManager: ConnectivityManager

    @Mock private lateinit var network: Network

    @Mock private lateinit var networkCapabilities: NetworkCapabilities

    private lateinit var viewModel: HistoryViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    // The mocked window (the newest loadedLimit + 1 rows) and the unfiltered total.
    private val windowFlow = MutableStateFlow<List<SmsUiModel>>(emptyList())

    private val countFlow = MutableStateFlow(0)

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        windowFlow.value = emptyList()
        countFlow.value = 0
        whenever(workManager.beginUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()))
            .thenReturn(workContinuation)
        // no live batch work by default -> enqueue picks REPLACE
        whenever(workManager.getWorkInfosByTag(EmailBatchWorker.BATCH_TAG)).thenReturn(workInfosFuture)
        // isDone=true puts await() on its fast path, which calls the stubbed no-arg get().
        whenever(workInfosFuture.isDone()).thenReturn(true)
        whenever(workInfosFuture.get()).thenReturn(emptyList())
        whenever(prefs.load()).thenReturn(AppSettings(useContactResolution = false))
        // No live SIM changes in these tests: the collector completes immediately.
        whenever(simChangeObserver.simChanges).thenReturn(emptyFlow())
        // No live contact changes in these tests: the refresh collector stays idle.
        whenever(contactChangeObserver.contactChanges).thenReturn(emptyFlow())
        whenever(repository.observeSms(eq(SmsListScope.HISTORY), any<SmsSearch>(), anyInt())).thenReturn(windowFlow)
        whenever(repository.observeCount(SmsListScope.HISTORY)).thenReturn(countFlow)
        whenever(context.getString(any<Int>())).thenReturn("Unknown SIM")
        whenever(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)
        whenever(connectivityManager.activeNetwork).thenReturn(null)
        whenever(permissionManager.isGranted(any())).thenReturn(true)
        whenever(networkObserver.isConnectedCurrently()).thenReturn(true)
        whenever(networkObserver.networkStatus).thenReturn(flowOf(NetworkStatus.Available))
        // the importMessages stub needs a coroutine context (suspend function)
        kotlinx.coroutines.runBlocking {
            whenever(smsImporter.importMessages(any(), any())).thenReturn(SmsImporter.ImportResult())
        }

        viewModel =
            HistoryViewModel(
                repository = repository,
                smsImporter = smsImporter,
                prefs = prefs,
                contactResolver = contactResolver,
                simNameResolver = simNameResolver,
                simChangeObserver = simChangeObserver,
                contactChangeObserver = contactChangeObserver,
                permissionManager = permissionManager,
                actions = SmsActions(repository, context, prefs, retryScheduler, simNameResolver),
                appContext = context,
                networkObserver = networkObserver,
            )
    }

    // The forward toasts are resolved texts (the mapper interpolates where a Context
    // exists, before the toast call): pin the resource's text — the generic setUp stub
    // answers every id the same — and return it for the toast verification.
    private fun pinForwardToast(resId: Int): String {
        val text = "toast $resId"
        whenever(context.getString(resId)).thenReturn(text)
        return text
    }

    @After
    fun tearDown() {
        // Settle the search pipeline before Main is swapped: the debounce may still be
        // pending and the real-IO search build resumes on Main while the test dispatcher
        // is still installed, so the resumption lands on the test scheduler and can be
        // pumped here. isLoading turning false proves the last IO hop has landed; after
        // it no cross-thread work is in flight.
        val deadline = System.currentTimeMillis() + 2_000
        while (viewModel.state.value.list.paged.isLoading && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceTimeBy(AppConfig.SMS_SEARCH_DEBOUNCE_MS)
            testDispatcher.scheduler.runCurrent()
            Thread.sleep(5)
        }
        testDispatcher.scheduler.runCurrent()
        // Park Main on a dispatcher nobody pumps instead of resetMain: a stray IO
        // continuation from the VM pipeline can still resume after the test, and
        // resetMain would send it to the unmocked real Main and crash a worker thread.
        Dispatchers.setMain(StandardTestDispatcher())
    }

    // The window query is debounced (SMS_SEARCH_DEBOUNCE_MS of virtual time), so the
    // first emission only lands once the scheduler's clock has advanced past it.
    private fun waitForLoad() {
        val deadline = System.currentTimeMillis() + 2_000
        while (viewModel.state.value.list.paged.isLoading && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceTimeBy(AppConfig.SMS_SEARCH_DEBOUNCE_MS)
            testDispatcher.scheduler.runCurrent()
            Thread.sleep(5)
        }
        testDispatcher.scheduler.runCurrent()
    }

    // The runTest twin of waitForLoad: yields to the dispatcher instead of sleeping, so
    // the real-IO buildSearch continuation can be picked up.
    private suspend fun awaitLoaded() {
        while (viewModel.state.value.list.paged.isLoading) {
            testDispatcher.scheduler.advanceTimeBy(AppConfig.SMS_SEARCH_DEBOUNCE_MS)
            testDispatcher.scheduler.runCurrent()
            kotlinx.coroutines.delay(5)
        }
        testDispatcher.scheduler.runCurrent()
    }

    // Waits until the repository observes a search different from `baseline`: buildSearch
    // runs on the real IO dispatcher, so the re-query is not synchronous. The re-verify is
    // required — a Mockito captor only records values while a verify() call matches.
    private suspend fun awaitNewSearch(
        captor: KArgumentCaptor<SmsSearch>,
        baseline: SmsSearch,
    ) {
        val deadline = System.currentTimeMillis() + 3_000
        while (captor.allValues.lastOrNull() == baseline && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceTimeBy(10)
            testDispatcher.scheduler.runCurrent()
            verify(repository, atLeastOnce()).observeSms(eq(SmsListScope.HISTORY), captor.capture(), anyInt())
            kotlinx.coroutines.delay(5)
        }
    }

    private fun enableInternet() = stubInternetEnabled(connectivityManager, network, networkCapabilities)

    @Test
    fun `initial state is empty and loading`() {
        val s = viewModel.state.value
        assertTrue(
            s.list.paged.smsList
                .isEmpty(),
        )
        assertTrue(s.list.paged.isLoading)

        waitForLoad()
        val loaded = viewModel.state.value
        assertFalse(loaded.list.paged.isLoading)
        assertTrue(
            loaded.list.paged.smsList
                .isEmpty(),
        )
    }

    @Test
    fun `the loaded window is one page and hasMore reflects the probe row`() {
        // Newest first, 20 rows plus the probe row: the window keeps the first 20.
        windowFlow.value = (21L downTo 1L).map { smsModel(it, SmsStatus.SENT) }

        waitForLoad()

        val s = viewModel.state.value
        assertEquals(AppConfig.SMS_LIST_PAGE_SIZE, s.list.paged.smsList.size)
        assertEquals(
            21L,
            s.list.paged.smsList
                .first()
                .id,
        )
        assertTrue(s.list.paged.hasMore)
    }

    @Test
    fun `hasMore is false when the probe row is absent`() {
        windowFlow.value = (20L downTo 1L).map { smsModel(it, SmsStatus.SENT) }

        waitForLoad()

        val s = viewModel.state.value
        assertEquals(AppConfig.SMS_LIST_PAGE_SIZE, s.list.paged.smsList.size)
        assertFalse(s.list.paged.hasMore)
    }

    @Test
    fun `the unfiltered total feeds the state for the title`() {
        countFlow.value = 123

        testDispatcher.scheduler.runCurrent()

        assertEquals(123, viewModel.state.value.list.paged.smsCount)
    }

    // Waits until the loaded window has `expectedSize` rows: the grown window arrives
    // through the real-IO buildSearch, so the arrival is not synchronous.
    private suspend fun awaitWindowSize(expectedSize: Int) {
        val deadline = System.currentTimeMillis() + 3_000
        while (viewModel.state.value.list.paged.smsList.size != expectedSize && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceTimeBy(10)
            testDispatcher.scheduler.runCurrent()
            kotlinx.coroutines.delay(5)
        }
        assertEquals(expectedSize, viewModel.state.value.list.paged.smsList.size)
    }

    @Test
    fun `loadMore grows the window by one page`() =
        runTest(testDispatcher) {
            windowFlow.value = (41L downTo 1L).map { smsModel(it, SmsStatus.SENT) }
            awaitLoaded()

            // The window query re-runs with the grown limit (window plus probe).
            viewModel.loadMore()
            awaitWindowSize(2 * AppConfig.SMS_LIST_PAGE_SIZE)

            val captor = argumentCaptor<Int>()
            verify(repository, atLeastOnce()).observeSms(eq(SmsListScope.HISTORY), any<SmsSearch>(), captor.capture())
            assertTrue(captor.allValues.contains(AppConfig.SMS_LIST_PAGE_SIZE * 2 + 1))
        }

    @Test
    fun `loadMore is a no-op when there is nothing more to load`() =
        runTest(testDispatcher) {
            windowFlow.value = (10L downTo 1L).map { smsModel(it, SmsStatus.SENT) }
            awaitLoaded()
            assertFalse(viewModel.state.value.list.paged.hasMore)

            viewModel.loadMore()

            // Pump past where a (wrong) grow would have re-queried with a bigger limit:
            // buildSearch hops to the real IO dispatcher, so the re-query is not
            // synchronous.
            val deadline = System.currentTimeMillis() + 500
            while (System.currentTimeMillis() < deadline) {
                testDispatcher.scheduler.advanceTimeBy(10)
                testDispatcher.scheduler.runCurrent()
                kotlinx.coroutines.delay(5)
            }
            val captor = argumentCaptor<Int>()
            verify(repository, atLeastOnce()).observeSms(eq(SmsListScope.HISTORY), any<SmsSearch>(), captor.capture())
            assertTrue(captor.allValues.max() <= AppConfig.SMS_LIST_PAGE_SIZE + 1)
        }

    @Test
    fun `setSearchQuery re-runs the window query after the debounce`() =
        runTest(testDispatcher) {
            awaitLoaded()
            val captor = argumentCaptor<SmsSearch>()
            verify(repository, atLeastOnce()).observeSms(eq(SmsListScope.HISTORY), captor.capture(), anyInt())
            val baseline = captor.allValues.last()

            viewModel.setSearchQuery("hello")
            awaitNewSearch(captor, baseline)

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
            awaitLoaded()
            val captor = argumentCaptor<SmsSearch>()
            verify(repository, atLeastOnce()).observeSms(eq(SmsListScope.HISTORY), captor.capture(), anyInt())
            val baseline = captor.allValues.last()

            viewModel.setSearchQuery("john")
            awaitNewSearch(captor, baseline)

            assertEquals(listOf("5550100"), captor.allValues.last().contactNumbers)
        }

    @Test
    fun `the sim facet matches slot names when the display is on`() =
        runTest(testDispatcher) {
            whenever(prefs.load()).thenReturn(AppSettings(displaySimCards = true))
            whenever(simNameResolver.getSlotEntries()).thenReturn(listOf(1 to "CarrierOne", 2 to "CarrierTwo"))
            awaitLoaded()
            val captor = argumentCaptor<SmsSearch>()
            verify(repository, atLeastOnce()).observeSms(eq(SmsListScope.HISTORY), captor.capture(), anyInt())
            val baseline = captor.allValues.last()

            viewModel.setSearchQuery("carrierone")
            awaitNewSearch(captor, baseline)

            assertEquals(listOf(1), captor.allValues.last().simIds)
        }

    @Test
    fun `show and dismiss import dialog`() {
        viewModel.importFlow.showImportDialog()
        assertTrue(viewModel.importFlow.state.value.showImportDialog)

        viewModel.importFlow.dismissImportDialog()
        assertFalse(viewModel.importFlow.state.value.showImportDialog)
    }

    @Test
    fun `confirmImport dismisses dialog and keeps timeframe`() {
        viewModel.importFlow.showImportDialog()
        viewModel.importFlow.selectTimeFrame(ImportTimeFrame.TWENTY_FOUR_HOURS)
        viewModel.importFlow.confirmImport()

        val s = viewModel.importFlow.state.value
        assertFalse(s.showImportDialog)
        assertEquals(ImportTimeFrame.TWENTY_FOUR_HOURS, s.selectedTimeFrame)
        kotlinx.coroutines.runBlocking {
            verify(smsImporter).importMessages(any(), any())
        }
    }

    @Test
    fun `clearImportResult clears result and error`() {
        viewModel.importFlow.clearImportResult()
        assertNull(viewModel.importFlow.state.value.importResult)
        assertNull(viewModel.importFlow.state.value.importError)
    }

    @Test
    fun `simNames and simSlots maps are initialized`() {
        assertNotNull(viewModel.state.value.list.paged.simNames)
        assertNotNull(viewModel.state.value.list.paged.simSlots)
    }

    @Test
    fun `runCleanupNow passes the retention days to the repository and stores the deleted count`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 30))
        kotlinx.coroutines.runBlocking {
            whenever(repository.deleteExpired(30L)).thenReturn(5)
        }

        viewModel.runCleanupNow()

        assertEquals(5, viewModel.state.value.cleanupDeletedCount)
    }

    @Test
    fun `runCleanupNow delegates the zero-days guard to the repository`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        kotlinx.coroutines.runBlocking {
            whenever(repository.deleteExpired(0L)).thenReturn(0)
        }

        viewModel.runCleanupNow()

        assertEquals(0, viewModel.state.value.cleanupDeletedCount)
    }

    @Test
    fun `clearCleanupResult clears the deleted count`() {
        viewModel.clearCleanupResult()

        assertNull(viewModel.state.value.cleanupDeletedCount)
    }

    @Test
    fun `clearHistory deletes through the scoped history delete, never a full-table one`() {
        viewModel.clearHistory()

        // The DAO-level predicate (SmsDaoCleanupTest) keeps PENDING/SENDING/RETRYING;
        // here: clearHistory must go through that scoped delete, never a full-table one.
        kotlinx.coroutines.runBlocking {
            verify(repository).deleteHistorySms()
        }
    }

    @Test
    fun `clearHistory swallows a repository failure instead of crashing the scope`() =
        runTest(testDispatcher) {
            whenever(repository.deleteHistorySms()).thenThrow(RuntimeException("db down"))

            viewModel.clearHistory()
        }

    @Test
    fun `toggleSelection selects item`() {
        viewModel.enterSelectionMode()
        viewModel.toggleSelection(1L)
        val s = viewModel.state.value
        assertTrue(s.list.selectionMode)
        assertEquals(setOf(1L), s.list.selectedIds)
    }

    @Test
    fun `toggleSelection deselects item when more than one selected`() {
        viewModel.enterSelectionMode()
        viewModel.toggleSelection(1L)
        viewModel.toggleSelection(2L)
        viewModel.toggleSelection(1L)
        val s = viewModel.state.value
        assertTrue(s.list.selectionMode)
        assertEquals(setOf(2L), s.list.selectedIds)
    }

    @Test
    fun `toggleSelection exits selection mode when last item is deselected`() {
        viewModel.enterSelectionMode()
        viewModel.toggleSelection(1L)
        viewModel.toggleSelection(1L)
        val s = viewModel.state.value
        assertFalse(s.list.selectionMode)
        assertTrue(s.list.selectedIds.isEmpty())
    }

    @Test
    fun `toggleSelection stays in selection mode when deselecting one of two selected`() {
        viewModel.enterSelectionMode()
        viewModel.toggleSelection(1L)
        viewModel.toggleSelection(2L)
        viewModel.toggleSelection(2L)
        val s = viewModel.state.value
        assertTrue(s.list.selectionMode)
        assertEquals(setOf(1L), s.list.selectedIds)
    }

    @Test
    fun `forwardSelected enqueues only ids whose CAS transition succeeded`() =
        runTest(testDispatcher) {
            WorkManagerDelegate.install(workManager)
            try {
                enableInternet()
                whenever(repository.getSmsByIds(any<List<Long>>())).thenReturn(
                    listOf(
                        sms(1L, SmsStatus.FAILED),
                        sms(2L, SmsStatus.IMPORTED),
                        sms(3L, SmsStatus.PENDING),
                        sms(4L, SmsStatus.SENDING),
                    ),
                )
                // The in-flight SENDING row is split off before the CAS and counts as
                // blocked only: the CAS sees the three non-in-flight rows.
                whenever(
                    repository.transitionSmsStatusMany(
                        eq(listOf(1L, 2L, 3L)),
                        eq(SmsStatus.MANUAL_FORWARDABLE),
                        eq(SmsStatus.PENDING),
                        any(),
                    ),
                ).thenReturn(listOf(1L))

                viewModel.enterSelectionMode()
                viewModel.toggleSelection(1L)
                viewModel.toggleSelection(2L)
                viewModel.toggleSelection(3L)
                viewModel.toggleSelection(4L)
                val blockedText = pinForwardToast(R.string.sms_forward_blocked_multiple)
                withMockedToast { toast ->
                    viewModel.forwardSelected()
                    // Three of four rows did not transition: the plural reads for the blocked count.
                    toast.verify { Toast.makeText(eq(context), eq(blockedText), eq(Toast.LENGTH_SHORT)) }
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
    fun `forwardSelected offline schedules first retry for each forwardable id`() =
        runTest(testDispatcher) {
            whenever(repository.getSmsByIds(any<List<Long>>())).thenReturn(
                listOf(sms(1L, SmsStatus.FAILED), sms(2L, SmsStatus.IMPORTED)),
            )
            whenever(context.getString(R.string.error_waiting_for_internet))
                .thenReturn("No Internet connection.")
            whenever(retryScheduler.scheduleFirstRetry(eq(1L), any(), any())).thenReturn(false)
            whenever(retryScheduler.scheduleFirstRetry(eq(2L), any(), any())).thenReturn(false)

            viewModel.enterSelectionMode()
            viewModel.toggleSelection(1L)
            viewModel.toggleSelection(2L)
            val blockedText = pinForwardToast(R.string.sms_forward_blocked_multiple)
            withMockedToast { toast ->
                viewModel.forwardSelected()
                // Neither row could be parked: the plural reads for the blocked count.
                toast.verify { Toast.makeText(eq(context), eq(blockedText), eq(Toast.LENGTH_SHORT)) }
            }

            // History keeps the shared default: the park CAS accepts any forwardable row.
            verify(retryScheduler).scheduleFirstRetry(eq(1L), eq("No Internet connection."), eq(SmsStatus.MANUAL_FORWARDABLE))
            verify(retryScheduler).scheduleFirstRetry(eq(2L), eq("No Internet connection."), eq(SmsStatus.MANUAL_FORWARDABLE))
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            verify(workManager, never()).beginUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }

    @Test
    fun `forwardSelected toasts and does nothing when forwarding is disabled`() =
        runTest(testDispatcher) {
            whenever(prefs.load()).thenReturn(AppSettings(isForwardingEnabled = false))

            viewModel.enterSelectionMode()
            viewModel.toggleSelection(1L)
            viewModel.toggleSelection(2L)
            val offText = pinForwardToast(R.string.sms_forward_forwarding_off)
            withMockedToast { toast ->
                viewModel.forwardSelected()
                toast.verify { Toast.makeText(eq(context), eq(offText), eq(Toast.LENGTH_SHORT)) }
            }

            verify(repository, never()).transitionSmsStatusMany(any(), any(), any(), any())
            verify(retryScheduler, never()).scheduleFirstRetry(any(), any(), any())
            verify(workManager, never()).beginUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }

    @Test
    fun `deleteSelected deletes the selected rows and exits selection mode`() =
        runTest(testDispatcher) {
            whenever(repository.deleteSmsByIds(eq(listOf(1L, 2L)))).thenReturn(2)

            viewModel.enterSelectionMode()
            viewModel.toggleSelection(1L)
            viewModel.toggleSelection(2L)
            withMockedToast { toast ->
                viewModel.deleteSelected()
                toast.verify(MockedStatic<Toast>.Verification { Toast.makeText(any<Context>(), any<Int>(), any<Int>()) }, never())
            }

            verify(repository).deleteSmsByIds(listOf(1L, 2L))
            assertFalse(viewModel.state.value.list.selectionMode)
        }

    @Test
    fun `deleteSelected shows a toast when the guarded delete removed fewer rows than selected`() =
        runTest(testDispatcher) {
            whenever(repository.deleteSmsByIds(eq(listOf(1L)))).thenReturn(0)

            viewModel.enterSelectionMode()
            viewModel.toggleSelection(1L)
            withMockedToast { toast ->
                viewModel.deleteSelected()
                toast.verify { Toast.makeText(eq(context), eq(R.string.sms_delete_blocked), eq(Toast.LENGTH_SHORT)) }
            }
        }

    @Test
    fun `deleteSelected with no selection does not touch the repository`() =
        runTest(testDispatcher) {
            viewModel.deleteSelected()
            verify(repository, never()).deleteSmsByIds(any<List<Long>>())
        }

    // The cancel + the selection_cleared toast fire in the window collector. With the
    // class's unconfined Main that collector resumes on a real IO thread (the window
    // pipeline enriches via Dispatchers.IO), where the thread-local static Toast mock
    // cannot intercept and the unintercepted android stub throws. A standard Main on
    // the same scheduler pins the collector to the test thread instead.
    @Test
    fun `selection is cancelled with a toast when a selected row is deleted by the cleanup`() {
        val standardMain = StandardTestDispatcher(testDispatcher.scheduler)
        Dispatchers.setMain(standardMain)
        try {
            runTest(testDispatcher) {
                // The setUp VM was created under the unconfined Main; a fresh VM picks
                // up the standard one so the window collector runs on the test thread.
                viewModel =
                    HistoryViewModel(
                        repository = repository,
                        smsImporter = smsImporter,
                        prefs = prefs,
                        contactResolver = contactResolver,
                        simNameResolver = simNameResolver,
                        simChangeObserver = simChangeObserver,
                        contactChangeObserver = contactChangeObserver,
                        permissionManager = permissionManager,
                        actions = SmsActions(repository, context, prefs, retryScheduler, simNameResolver),
                        appContext = context,
                        networkObserver = networkObserver,
                    )
                windowFlow.value = listOf(smsModel(1L, SmsStatus.SENT), smsModel(2L, SmsStatus.FAILED))
                awaitLoaded()
                viewModel.enterSelectionMode()
                viewModel.toggleSelection(1L)
                viewModel.toggleSelection(2L)
                testDispatcher.scheduler.runCurrent()
                assertTrue(viewModel.state.value.list.selectionMode)
                assertEquals(setOf(1L, 2L), viewModel.state.value.list.selectedIds)

                // The cleanup deleted row 1: it leaves the history window, and the DB
                // no longer returns it at all — the watcher's scope check still
                // clears the selection for a gone row.
                whenever(repository.getSmsByIds(any<List<Long>>())).thenReturn(emptyList())
                withMockedToast { toast ->
                    windowFlow.value = listOf(smsModel(2L, SmsStatus.FAILED))
                    pumpUntil(testDispatcher.scheduler) { !viewModel.state.value.list.selectionMode }
                    toast.verify { Toast.makeText(eq(context), eq(R.string.selection_cleared), eq(Toast.LENGTH_SHORT)) }
                }

                assertFalse(viewModel.state.value.list.selectionMode)
                assertTrue(
                    viewModel.state.value.list.selectedIds
                        .isEmpty(),
                )
            }
        } finally {
            Dispatchers.setMain(testDispatcher)
        }
    }

    // Editing the search re-windows the list under the selection: the mode exits at the
    // edit with the selection_cleared_search toast (the user's own keystroke is the
    // cause, not changed messages), and the query applies.
    @Test
    fun `searching while in selection mode exits the selection and applies the query`() =
        runTest(testDispatcher) {
            windowFlow.value = listOf(smsModel(1L, SmsStatus.SENT), smsModel(2L, SmsStatus.FAILED))
            awaitLoaded()
            val captor = argumentCaptor<SmsSearch>()
            verify(repository, atLeastOnce()).observeSms(eq(SmsListScope.HISTORY), captor.capture(), anyInt())
            val baseline = captor.allValues.last()

            viewModel.enterSelectionMode()
            viewModel.toggleSelection(1L)
            withMockedToast { toast ->
                viewModel.setSearchQuery("hello")
                toast.verify { Toast.makeText(eq(context), eq(R.string.selection_cleared_search), eq(Toast.LENGTH_SHORT)) }
            }
            awaitNewSearch(captor, baseline)

            assertFalse(viewModel.state.value.list.selectionMode)
            assertTrue(
                viewModel.state.value.list.selectedIds
                    .isEmpty(),
            )
            assertFalse(captor.allValues.last().noSearch)
        }

    // The X-clear re-windows too (the unfiltered window is capped, and a selected row
    // can sit below it): it exits the selection like any other query change.
    @Test
    fun `clearing the search while in selection mode exits the selection`() =
        runTest(testDispatcher) {
            windowFlow.value = listOf(smsModel(1L, SmsStatus.SENT))
            awaitLoaded()

            // Search first, then select: the active query is what the X button clears.
            viewModel.setSearchQuery("hello")
            viewModel.enterSelectionMode()
            viewModel.toggleSelection(1L)

            withMockedToast { toast ->
                viewModel.setSearchQuery("")
                toast.verify { Toast.makeText(eq(context), eq(R.string.selection_cleared_search), eq(Toast.LENGTH_SHORT)) }
            }

            assertFalse(viewModel.state.value.list.selectionMode)
            assertTrue(
                viewModel.state.value.list.selectedIds
                    .isEmpty(),
            )
        }

    // A rotation re-composes the screen: the search state is not restored, so the fresh
    // state re-announces the (unchanged, empty) query through setSearchQuery. That is a
    // recomposition, not an edit: the selection survives without a toast.
    @Test
    fun `re-announcing an unchanged query keeps the selection`() =
        runTest(testDispatcher) {
            windowFlow.value = listOf(smsModel(1L, SmsStatus.SENT))
            awaitLoaded()

            // The first composition announced the empty query; the selection came after.
            viewModel.setSearchQuery("")
            viewModel.enterSelectionMode()
            viewModel.toggleSelection(1L)

            withMockedToast { toast ->
                // A rotation re-composes: the fresh search state re-announces the same query.
                viewModel.setSearchQuery("")
                toast.verify(MockedStatic<Toast>.Verification { Toast.makeText(any<Context>(), any<Int>(), any<Int>()) }, never())
            }

            assertTrue(viewModel.state.value.list.selectionMode)
            assertEquals(setOf(1L), viewModel.state.value.list.selectedIds)
        }

    // A new arrival pushes the selected tail row below the window cap: the row is still
    // in the history scope (the DB says so), so the selection survives without a toast
    // instead of the watcher clearing it for a row that left nothing but the viewport.
    @Test
    fun `selection survives a selected row pushed below the window cap`() {
        val standardMain = StandardTestDispatcher(testDispatcher.scheduler)
        Dispatchers.setMain(standardMain)
        try {
            runTest(testDispatcher) {
                // The setUp VM was created under the unconfined Main; a fresh VM picks
                // up the standard one so the window collector runs on the test thread.
                viewModel =
                    HistoryViewModel(
                        repository = repository,
                        smsImporter = smsImporter,
                        prefs = prefs,
                        contactResolver = contactResolver,
                        simNameResolver = simNameResolver,
                        simChangeObserver = simChangeObserver,
                        contactChangeObserver = contactChangeObserver,
                        permissionManager = permissionManager,
                        actions = SmsActions(repository, context, prefs, retryScheduler, simNameResolver),
                        appContext = context,
                        networkObserver = networkObserver,
                    )
                // The watcher's scope check must report row 1 still in the history scope.
                whenever(repository.getSmsByIds(any<List<Long>>())).thenReturn(listOf(sms(1L, SmsStatus.SENT)))

                windowFlow.value = listOf(smsModel(3L, SmsStatus.IGNORED), smsModel(2L, SmsStatus.FAILED), smsModel(1L, SmsStatus.SENT))
                awaitLoaded()
                viewModel.enterSelectionMode()
                viewModel.toggleSelection(2L)
                viewModel.toggleSelection(1L)
                testDispatcher.scheduler.runCurrent()
                assertTrue(viewModel.state.value.list.selectionMode)
                assertEquals(setOf(1L, 2L), viewModel.state.value.list.selectedIds)

                withMockedToast { toast ->
                    // A new arrival re-emits the window without row 1 (pushed below the cap).
                    windowFlow.value = listOf(smsModel(3L, SmsStatus.IGNORED), smsModel(2L, SmsStatus.FAILED))
                    pumpUntil(testDispatcher.scheduler) {
                        viewModel.state.value.list.paged.smsList
                            .map { it.id } == listOf(3L, 2L)
                    }
                    toast.verify(MockedStatic<Toast>.Verification { Toast.makeText(any<Context>(), any<Int>(), any<Int>()) }, never())
                }
                // The watcher checked the missing row against the DB and kept the selection.
                verify(repository).getSmsByIds(eq(listOf(1L)))

                assertTrue(viewModel.state.value.list.selectionMode)
                assertEquals(setOf(1L, 2L), viewModel.state.value.list.selectedIds)
            }
        } finally {
            Dispatchers.setMain(testDispatcher)
        }
    }
}
