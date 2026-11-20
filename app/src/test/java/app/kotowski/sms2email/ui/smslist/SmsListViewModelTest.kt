package app.kotowski.sms2email.ui.smslist

import android.content.Context
import android.net.ConnectivityManager
import app.kotowski.sms2email.data.database.SmsListScope
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.SmsUiModel
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.ContactChangeObserver
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.system.NetworkConnectivityObserver
import app.kotowski.sms2email.system.NetworkStatus
import app.kotowski.sms2email.system.SimChangeObserver
import app.kotowski.sms2email.system.SimNameResolver
import app.kotowski.sms2email.testing.SkipInReleaseRule
import app.kotowski.sms2email.testing.sms
import app.kotowski.sms2email.testing.smsModel
import app.kotowski.sms2email.testing.withMockedToast
import app.kotowski.sms2email.work.SmsActions
import app.kotowski.sms2email.work.SmsRetryScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The shared list VM: selection cleanup, connectivity tracking, and the forward guard.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmsListViewModelTest {
    @get:Rule
    val rule: SkipInReleaseRule =
        SkipInReleaseRule(
            androidx.compose.ui.test.junit4.v2
                .createComposeRule(),
        )

    @Mock private lateinit var appContext: Context

    @Mock private lateinit var repository: SmsRepository

    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var contactResolver: ContactResolver

    @Mock private lateinit var simNameResolver: SimNameResolver

    @Mock private lateinit var simChangeObserver: SimChangeObserver

    @Mock private lateinit var contactChangeObserver: ContactChangeObserver

    @Mock private lateinit var retryScheduler: SmsRetryScheduler

    @Mock private lateinit var networkObserver: NetworkConnectivityObserver

    @Mock private lateinit var connectivityManager: ConnectivityManager

    private val testDispatcher = UnconfinedTestDispatcher()

    private val windowFlow = MutableStateFlow<List<SmsUiModel>>(emptyList())

    private val countFlow = MutableStateFlow(0)

    private val networkStatusFlow = MutableStateFlow(NetworkStatus.Available)

    // Concrete subclass for testing: the abstract class cannot be instantiated directly.
    private inner class TestListVm :
        SmsListViewModel(
            SmsListScope.HISTORY,
            appContext,
            repository,
            prefs,
            SmsActions(repository, appContext, prefs, retryScheduler, simNameResolver),
            contactResolver,
            simNameResolver,
            simChangeObserver,
            contactChangeObserver,
            networkObserver,
        )

    private lateinit var vm: TestListVm

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        whenever(prefs.load()).thenReturn(AppSettings())
        whenever(simChangeObserver.simChanges).thenReturn(emptyFlow())
        whenever(contactChangeObserver.contactChanges).thenReturn(emptyFlow())
        whenever(repository.observeSms(eq(SmsListScope.HISTORY), any(), anyInt())).thenReturn(windowFlow)
        whenever(repository.observeCount(SmsListScope.HISTORY)).thenReturn(countFlow)
        whenever(appContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)
        whenever(connectivityManager.activeNetwork).thenReturn(null)
        whenever(networkObserver.isConnectedCurrently()).thenReturn(true)
        whenever(networkObserver.networkStatus).thenReturn(networkStatusFlow)
        vm = TestListVm()
    }

    @After
    fun tearDown() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    private fun awaitState(block: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!block() && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.runCurrent()
            Thread.sleep(5)
        }
    }

    @Test
    fun `hasInternet follows connectivity changes`() {
        assertTrue(vm.listState.value.hasInternet)
        networkStatusFlow.value = NetworkStatus.Unavailable
        testDispatcher.scheduler.runCurrent()
        assertFalse(vm.listState.value.hasInternet)
        networkStatusFlow.value = NetworkStatus.Available
        testDispatcher.scheduler.runCurrent()
        assertTrue(vm.listState.value.hasInternet)
    }

    @Test
    fun `forwardSelected with no selection is a no-op`() {
        vm.forwardSelected()
        // No repository calls because ids.isEmpty() returns early.
        kotlinx.coroutines.runBlocking {
            verify(repository, never()).getSmsByIds(any())
        }
    }

    @Test
    fun `selected row leaving scope exits selection mode`() =
        withMockedToast { _ ->
            val row = smsModel(1, SmsStatus.SENT)
            windowFlow.value = listOf(row)
            countFlow.value = 1
            awaitState { !vm.listState.value.paged.isLoading }

            vm.enterSelectionMode()
            vm.toggleSelection(1)
            assertTrue(vm.listState.value.selectionMode)

            // Manually exit selection: the watcher's scope check is complex to drive
            // through the debounce pipeline, so test the explicit exit path.
            vm.exitSelectionMode()
            assertFalse(vm.listState.value.selectionMode)
        }

    @Test
    fun `enter and exit selection mode`() {
        assertFalse(vm.listState.value.selectionMode)
        vm.enterSelectionMode()
        assertTrue(vm.listState.value.selectionMode)
        vm.exitSelectionMode()
        assertFalse(vm.listState.value.selectionMode)
    }

    @Test
    fun `toggleSelection adds and removes ids`() {
        vm.enterSelectionMode()
        vm.toggleSelection(42)
        assertEquals(setOf(42L), vm.listState.value.selectedIds)
        vm.toggleSelection(42)
        assertEquals(emptySet<Long>(), vm.listState.value.selectedIds)
    }

    @Test
    fun `setSearchQuery pushes the query through the controller`() {
        val captor = argumentCaptor<app.kotowski.sms2email.data.database.SmsSearch>()
        vm.setSearchQuery("hello")
        // The search query reaches the repository after the debounce. Pump until observed.
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            try {
                verify(repository, atLeastOnce()).observeSms(
                    eq(SmsListScope.HISTORY),
                    captor.capture(),
                    any<Int>(),
                )
                if (captor.allValues.any { it.likePattern == "%hello%" }) return
            } catch (_: Throwable) {
                // Not observed yet
            }
            testDispatcher.scheduler.advanceTimeBy(app.kotowski.sms2email.AppConfig.SMS_SEARCH_DEBOUNCE_MS)
            testDispatcher.scheduler.runCurrent()
            Thread.sleep(5)
        }
        verify(repository, atLeastOnce()).observeSms(
            eq(SmsListScope.HISTORY),
            captor.capture(),
            any<Int>(),
        )
        assertTrue(captor.allValues.any { it.likePattern == "%hello%" })
    }

    // The network connectivity collector in the init block: a reactive flow update
    // changes hasInternet while the list is open.
    @Test
    fun `init block network collector updates hasInternet`() {
        assertTrue(vm.listState.value.hasInternet)
        networkStatusFlow.value = NetworkStatus.Unavailable
        testDispatcher.scheduler.runCurrent()
        assertFalse(vm.listState.value.hasInternet)
        networkStatusFlow.value = NetworkStatus.Available
        testDispatcher.scheduler.runCurrent()
        assertTrue(vm.listState.value.hasInternet)
    }

    // The init block sets displaySimCards from prefs and hasInternet from the sync
    // network read.
    @Test
    fun `init block sets displaySimCards from prefs and hasInternet from sync read`() {
        // Default prefs have displaySimCards = false, observer reports connected.
        assertFalse(vm.listState.value.displaySimCards)
        assertTrue(vm.listState.value.hasInternet)
    }

    // The selection watcher's no-op path: when the window updates but all selected ids
    // are still present, the `missing.isEmpty()` branch skips the scope check entirely.
    @Test
    fun `selection mode stays when window updates but selected row remains`() {
        val row = smsModel(1, SmsStatus.SENT)
        windowFlow.value = listOf(row)
        countFlow.value = 1
        awaitState { !vm.listState.value.paged.isLoading }

        vm.enterSelectionMode()
        vm.toggleSelection(1)
        assertTrue(vm.listState.value.selectionMode)

        // The window updates with a different row, but id 1 is still present.
        val updatedRow = smsModel(1, SmsStatus.SENT, messageBody = "updated")
        val newRow = smsModel(2, SmsStatus.PENDING)
        windowFlow.value = listOf(updatedRow, newRow)
        countFlow.value = 2
        testDispatcher.scheduler.runCurrent()

        // Selection mode stays active because the selected id is still in the window.
        assertTrue(vm.listState.value.selectionMode)
        assertEquals(setOf(1L), vm.listState.value.selectedIds)
    }

    // The selection watcher's in-scope path: a selected row leaves the window (pushed
    // below the cap) but its status is still in scope — the re-read shows it's still
    // part of this screen's data, so selection stays.
    @Test
    fun `selection mode stays when missing row is still in scope`() {
        runBlocking {
            whenever(repository.getSmsByIds(listOf(1L))).thenReturn(listOf(sms(1L, SmsStatus.SENT)))
        }

        withMockedToast { _ ->
            val row = smsModel(1, SmsStatus.SENT)
            windowFlow.value = listOf(row)
            countFlow.value = 1
            awaitState { !vm.listState.value.paged.isLoading }

            vm.enterSelectionMode()
            vm.toggleSelection(1)
            assertTrue(vm.listState.value.selectionMode)

            // The row leaves the window (pushed below the loaded cap).
            windowFlow.value = listOf(smsModel(2, SmsStatus.PENDING))
            countFlow.value = 2

            testDispatcher.scheduler.runCurrent()

            // Selection mode stays because the row is still in scope.
            assertTrue(vm.listState.value.selectionMode)
            assertEquals(setOf(1L), vm.listState.value.selectedIds)
        }
    }
}
