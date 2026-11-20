package app.kotowski.sms2email.ui.dashboard

import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.BackgroundFlags
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.NetworkConnectivityObserver
import app.kotowski.sms2email.system.NetworkStatus
import app.kotowski.sms2email.system.PermissionManager
import app.kotowski.sms2email.system.PermissionsAutoResetStatus
import app.kotowski.sms2email.system.SimChangeObserver
import app.kotowski.sms2email.system.SimDetector
import app.kotowski.sms2email.system.SimNameResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    @Mock private lateinit var mockPrefs: EncryptedPrefs

    @Mock private lateinit var mockPermissionManager: PermissionManager

    @Mock private lateinit var mockSimDetector: SimDetector

    @Mock private lateinit var mockSimNameResolver: SimNameResolver

    @Mock private lateinit var mockSimChangeObserver: SimChangeObserver

    @Mock private lateinit var mockNetworkObserver: NetworkConnectivityObserver

    @Mock private lateinit var mockRepository: SmsRepository

    private lateinit var viewModel: DashboardViewModel
    private val testDispatcher = StandardTestDispatcher()
    private var forwardingPref = false
    private var displaySimCardsPref = false

    // The real EncryptedPrefs would emit the new flag pair from the setter; the mock
    // setter below mirrors that so the VM's flow collection sees every change.
    private lateinit var flagsFlow: MutableStateFlow<BackgroundFlags>

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        MockitoAnnotations.openMocks(this)
        forwardingPref = false
        whenever(mockPrefs.load()).thenAnswer {
            AppSettings(
                isForwardingEnabled = forwardingPref,
                displaySimCards = displaySimCardsPref,
            )
        }
        doAnswer { inv ->
            val transform = inv.getArgument<(AppSettings) -> AppSettings>(0)
            val next =
                transform(
                    AppSettings(
                        isForwardingEnabled = forwardingPref,
                        displaySimCards = displaySimCardsPref,
                    ),
                )
            forwardingPref = next.isForwardingEnabled
            flagsFlow.value = flagsFlow.value.copy(forwardingEnabled = forwardingPref)
            Unit
        }.whenever(mockPrefs)
            .update(any())
        whenever(mockNetworkObserver.isConnectedCurrently()).thenReturn(true)
        whenever(mockNetworkObserver.networkStatus).thenReturn(flowOf(NetworkStatus.Available))
        // No live SIM changes in these tests: the collector completes immediately.
        whenever(mockSimChangeObserver.simChanges).thenReturn(emptyFlow())
        // the suspend stub needs a coroutine context; an empty queue by default
        kotlinx.coroutines.runBlocking {
            whenever(mockRepository.getQueueIds()).thenReturn(emptyList())
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(singleSim: Boolean = true) {
        whenever(mockSimDetector.isSingleSimDevice()).thenReturn(singleSim)
        flagsFlow = MutableStateFlow(BackgroundFlags(forwardingEnabled = forwardingPref, autoCleanupEnabled = false))
        whenever(mockPrefs.backgroundFlags).thenReturn(flagsFlow)
        viewModel =
            DashboardViewModel(
                mockPrefs,
                mockPermissionManager,
                mockRepository,
                mockSimDetector,
                mockSimNameResolver,
                mockSimChangeObserver,
                mockNetworkObserver,
            )
        testDispatcher.scheduler.runCurrent()
    }

    @Test
    fun `initial state reflects single SIM device`() {
        createViewModel(singleSim = true)
        assertTrue(viewModel.state.value.isSingleSimDevice)
    }

    @Test
    fun `initial state reflects dual SIM device`() {
        createViewModel(singleSim = false)
        assertFalse(viewModel.state.value.isSingleSimDevice)
    }

    @Test
    fun `loadSimCards populates the cards with slot and name when enabled`() =
        runTest(testDispatcher) {
            displaySimCardsPref = true
            whenever(mockSimNameResolver.getActiveSims())
                .thenReturn(
                    listOf(
                        SimNameResolver.ActiveSim(slot = 1, displayName = "CarrierOne"),
                        SimNameResolver.ActiveSim(slot = 2, displayName = "CarrierTwo"),
                    ),
                )
            createViewModel(singleSim = false)
            viewModel.readSimCards()

            val s = viewModel.state.value
            assertEquals(1, s.simCards[0].slot)
            assertEquals("CarrierOne", s.simCards[0].displayName)
            assertEquals(2, s.simCards[1].slot)
            assertEquals("CarrierTwo", s.simCards[1].displayName)
            assertTrue(s.displaySimCards)
        }

    @Test
    fun `loadSimCards returns empty when display disabled`() =
        runTest(testDispatcher) {
            displaySimCardsPref = false
            createViewModel(singleSim = false)
            viewModel.readSimCards()

            val s = viewModel.state.value
            assertTrue(s.simCards.isEmpty())
            assertFalse(s.displaySimCards)
        }

    @Test
    fun `loadSimCards returns empty when resolver has no sims`() =
        runTest(testDispatcher) {
            displaySimCardsPref = true
            whenever(mockSimNameResolver.getActiveSims()).thenReturn(emptyList())
            createViewModel(singleSim = false)
            viewModel.readSimCards()

            val s = viewModel.state.value
            assertTrue(s.simCards.isEmpty())
        }

    @Test
    fun `loadSimCards keeps the tray slot of a single active SIM on a dual-SIM device`() =
        runTest(testDispatcher) {
            displaySimCardsPref = true
            whenever(mockSimNameResolver.getActiveSims())
                .thenReturn(listOf(SimNameResolver.ActiveSim(slot = 2, displayName = "CarrierOne")))
            createViewModel(singleSim = false)
            viewModel.readSimCards()

            val s = viewModel.state.value
            assertEquals(2, s.simCards[0].slot)
            assertEquals("CarrierOne", s.simCards[0].displayName)
            assertFalse(s.isSingleSimDevice)
            // Widget visibility condition: simCards.isNotEmpty() => true
            assertTrue(s.simCards.isNotEmpty())
        }

    @Test
    fun `loadSimCards populates the cards on a single-SIM device when enabled`() =
        runTest(testDispatcher) {
            displaySimCardsPref = true
            whenever(mockSimNameResolver.getActiveSims())
                .thenReturn(listOf(SimNameResolver.ActiveSim(slot = 1, displayName = "CarrierOne")))
            createViewModel(singleSim = true)
            viewModel.readSimCards()

            val s = viewModel.state.value
            assertTrue(s.isSingleSimDevice)
            assertTrue(s.displaySimCards)
            // Widget visibility condition: simCards.isNotEmpty() => true
            assertTrue(s.simCards.isNotEmpty())
        }

    @Test
    fun `a sim change emission re-reads the SIM cards via the collector`() =
        runTest(testDispatcher) {
            displaySimCardsPref = true
            val simChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            whenever(mockSimChangeObserver.simChanges).thenReturn(simChanges)
            whenever(mockSimNameResolver.getActiveSims())
                .thenReturn(listOf(SimNameResolver.ActiveSim(slot = 1, displayName = "CarrierOne")))
            createViewModel(singleSim = false)

            simChanges.emit(Unit)
            // The collector's read hops to Dispatchers.IO, so wait in virtual time until
            // the state update lands.
            withTimeout(60_000) {
                var state = viewModel.state.value
                while (state.simCards.isEmpty()) {
                    delay(1)
                    state = viewModel.state.value
                }
            }

            val s = viewModel.state.value
            assertEquals(1, s.simCards.size)
            assertEquals("CarrierOne", s.simCards[0].displayName)
        }

    @Test
    fun `initial state has an unknown auto-reset status`() {
        createViewModel()
        assertEquals(null, viewModel.state.value.permissionsAutoResetStatus)
    }

    @Test
    fun `updatePermissionsAutoResetStatus publishes the fetched status`() =
        runTest(testDispatcher) {
            createViewModel()
            kotlinx.coroutines.runBlocking {
                whenever(mockPermissionManager.getPermissionsAutoResetStatus())
                    .thenReturn(PermissionsAutoResetStatus.NOT_AVAILABLE)
            }
            viewModel.updatePermissionsAutoResetStatus()
            testDispatcher.scheduler.runCurrent()
            assertEquals(PermissionsAutoResetStatus.NOT_AVAILABLE, viewModel.state.value.permissionsAutoResetStatus)
        }

    // Forwarding toggle tests
    @Test
    fun `initial state reflects forwarding pref off`() {
        forwardingPref = false
        createViewModel()
        assertFalse(viewModel.state.value.isForwardingEnabled)
    }

    @Test
    fun `initial state reflects forwarding pref on`() {
        forwardingPref = true
        createViewModel()
        assertTrue(viewModel.state.value.isForwardingEnabled)
    }

    @Test
    fun `toggleForwarding on sets pref and state`() {
        forwardingPref = false
        createViewModel()
        assertFalse(viewModel.state.value.isForwardingEnabled)

        viewModel.toggleForwarding(true)
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.state.value.isForwardingEnabled)
        val updateCaptor = argumentCaptor<(AppSettings) -> AppSettings>()
        verify(mockPrefs).update(updateCaptor.capture())
        assertTrue(updateCaptor.firstValue(AppSettings()).isForwardingEnabled)
    }

    @Test
    fun `toggleForwarding off with an empty queue stops immediately without a dialog`() =
        runTest(testDispatcher) {
            forwardingPref = true
            createViewModel()
            assertTrue(viewModel.state.value.isForwardingEnabled)

            viewModel.toggleForwarding(false)
            testDispatcher.scheduler.runCurrent()

            assertFalse(viewModel.state.value.isForwardingEnabled)
            assertEquals(0, viewModel.state.value.stopForwardingQueueCount)
            val updateCaptor = argumentCaptor<(AppSettings) -> AppSettings>()
            verify(mockPrefs).update(updateCaptor.capture())
            assertFalse(updateCaptor.firstValue(AppSettings()).isForwardingEnabled)
            verify(mockRepository, never()).transitionSmsStatusMany(any(), any(), any(), any())
        }

    @Test
    fun `toggleForwarding off with queued rows shows the stop dialog without stopping yet`() =
        runTest(testDispatcher) {
            forwardingPref = true
            whenever(mockRepository.getQueueIds()).thenReturn(listOf(1L, 2L))
            createViewModel()
            assertTrue(viewModel.state.value.isForwardingEnabled)

            viewModel.toggleForwarding(false)
            testDispatcher.scheduler.runCurrent()

            assertEquals(2, viewModel.state.value.stopForwardingQueueCount)
            assertTrue(viewModel.state.value.isForwardingEnabled)
            verify(mockPrefs, never()).update(any())
            verify(mockRepository, never()).transitionSmsStatusMany(any(), any(), any(), any())
        }

    @Test
    fun `toggleForwarding off with only in-flight SENDING rows shows the stop dialog`() =
        runTest(testDispatcher) {
            forwardingPref = true
            whenever(mockRepository.getQueueIds()).thenReturn(listOf(9L))
            createViewModel()

            viewModel.toggleForwarding(false)
            testDispatcher.scheduler.runCurrent()

            assertEquals(1, viewModel.state.value.stopForwardingQueueCount)
            assertTrue(viewModel.state.value.isForwardingEnabled)
            verify(mockPrefs, never()).update(any())
        }

    @Test
    fun `confirmStopForwarding writes the pref off and clears the dialog count`() =
        runTest(testDispatcher) {
            forwardingPref = true
            whenever(mockRepository.getQueueIds()).thenReturn(listOf(1L, 2L))
            createViewModel()

            viewModel.toggleForwarding(false)
            testDispatcher.scheduler.runCurrent()
            assertEquals(2, viewModel.state.value.stopForwardingQueueCount)

            viewModel.confirmStopForwarding()
            testDispatcher.scheduler.runCurrent()

            val updateCaptor = argumentCaptor<(AppSettings) -> AppSettings>()
            verify(mockPrefs).update(updateCaptor.capture())
            assertFalse(updateCaptor.firstValue(AppSettings()).isForwardingEnabled)
            assertFalse(viewModel.state.value.isForwardingEnabled)
            assertEquals(0, viewModel.state.value.stopForwardingQueueCount)
            // Parking the queue is the BackgroundStateReconciler's job, not this VM's.
            verify(mockRepository, never()).transitionSmsStatusMany(any(), any(), any(), any())
        }

    @Test
    fun `cancelStopForwarding dismisses the dialog and keeps forwarding on`() =
        runTest(testDispatcher) {
            forwardingPref = true
            whenever(mockRepository.getQueueIds()).thenReturn(listOf(1L))
            createViewModel()

            viewModel.toggleForwarding(false)
            testDispatcher.scheduler.runCurrent()
            assertEquals(1, viewModel.state.value.stopForwardingQueueCount)

            viewModel.cancelStopForwarding()
            testDispatcher.scheduler.runCurrent()

            assertEquals(0, viewModel.state.value.stopForwardingQueueCount)
            assertTrue(viewModel.state.value.isForwardingEnabled)
            verify(mockPrefs, never()).update(any())
            verify(mockRepository, never()).transitionSmsStatusMany(any(), any(), any(), any())
        }

    @Test
    fun `an external flag change from a settings import updates the toggle state`() =
        runTest(testDispatcher) {
            forwardingPref = false
            createViewModel()
            assertFalse(viewModel.state.value.isForwardingEnabled)

            flagsFlow.value = BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false)
            testDispatcher.scheduler.runCurrent()

            assertTrue(viewModel.state.value.isForwardingEnabled)
        }

    @Test
    fun `updatePermissions populates the permission snapshot`() {
        createViewModel()
        whenever(mockPermissionManager.snapshot()).thenReturn(
            app.kotowski.sms2email.system.PermissionSnapshot(
                sms = true,
                notifications = true,
                contacts = false,
                phone = true,
                battery = false,
            ),
        )
        viewModel.updatePermissions()

        val s = viewModel.state.value
        assertTrue(s.smsGranted)
        assertTrue(s.notificationsGranted)
        assertFalse(s.contactsGranted)
        assertTrue(s.phoneGranted)
        assertFalse(s.batteryOptimizationsGranted)
    }

    @Test
    fun `toggleForwarding catches exceptions and keeps state stable`() =
        runTest(testDispatcher) {
            forwardingPref = false
            createViewModel()
            doThrow(RuntimeException("prefs crash")).whenever(mockPrefs).update(any())
            viewModel.toggleForwarding(true)
            testDispatcher.scheduler.runCurrent()

            assertFalse(viewModel.state.value.isForwardingEnabled)
        }

    @Test
    fun `confirmStopForwarding catches exceptions and still clears dialog count`() =
        runTest(testDispatcher) {
            forwardingPref = true
            createViewModel()
            viewModel.state.value.copy(stopForwardingQueueCount = 3)
            viewModel.toggleForwarding(false)
            whenever(mockRepository.getQueueIds()).thenReturn(listOf(1L))
            createViewModel()
            whenever(mockRepository.getQueueIds()).thenReturn(listOf(1L))
            viewModel.toggleForwarding(false)
            testDispatcher.scheduler.runCurrent()
            assertEquals(1, viewModel.state.value.stopForwardingQueueCount)

            doThrow(RuntimeException("prefs crash")).whenever(mockPrefs).update(any())
            viewModel.confirmStopForwarding()
            testDispatcher.scheduler.runCurrent()

            assertEquals(0, viewModel.state.value.stopForwardingQueueCount)
        }

    @Test
    fun `readSimCards catches exceptions and resets cards to empty`() =
        runTest(testDispatcher) {
            displaySimCardsPref = true
            whenever(mockSimNameResolver.getActiveSims())
                .thenReturn(listOf(SimNameResolver.ActiveSim(slot = 1, displayName = "CarrierOne")))
            createViewModel(singleSim = false)
            viewModel.readSimCards()
            assertEquals(1, viewModel.state.value.simCards.size)

            whenever(mockSimNameResolver.getActiveSims()).thenThrow(RuntimeException("sim crash"))
            viewModel.readSimCards()

            val s = viewModel.state.value
            assertTrue(s.simCards.isEmpty())
        }

    @Test
    fun `readSimCards rethrows CancellationException`() =
        runTest(testDispatcher) {
            displaySimCardsPref = true
            createViewModel()
            whenever(mockSimNameResolver.getActiveSims()).thenThrow(CancellationException("cancelled"))
            try {
                viewModel.readSimCards()
                assertTrue(false, "should have thrown")
            } catch (e: CancellationException) {
            }
        }

    @Test
    fun `network status unavailable sets hasInternet to false`() =
        runTest(testDispatcher) {
            val networkFlow = MutableSharedFlow<NetworkStatus>(extraBufferCapacity = 1)
            whenever(mockNetworkObserver.networkStatus).thenReturn(networkFlow)
            whenever(mockNetworkObserver.isConnectedCurrently()).thenReturn(true)
            createViewModel()
            assertTrue(viewModel.state.value.hasInternet)

            networkFlow.emit(NetworkStatus.Unavailable)
            testDispatcher.scheduler.runCurrent()

            assertFalse(viewModel.state.value.hasInternet)
        }

    @Test
    fun `loadSimCards delegates to readSimCards`() =
        runTest(testDispatcher) {
            displaySimCardsPref = true
            whenever(mockSimNameResolver.getActiveSims())
                .thenReturn(listOf(SimNameResolver.ActiveSim(slot = 1, displayName = "TestCarrier")))
            createViewModel()
            viewModel.loadSimCards()

            // The IO hop runs on a real thread; wait for the state update to land.
            withTimeout(60_000) {
                var state = viewModel.state.value
                while (state.simCards.isEmpty()) {
                    delay(1)
                    state = viewModel.state.value
                }
            }

            assertEquals(1, viewModel.state.value.simCards.size)
            assertEquals(
                "TestCarrier",
                viewModel.state.value.simCards[0]
                    .displayName,
            )
        }

    @Test
    fun `DashboardState defaults are correct`() {
        val state = DashboardState()
        assertFalse(state.isForwardingEnabled)
        assertEquals(0, state.stopForwardingQueueCount)
        assertFalse(state.smsGranted)
        assertFalse(state.notificationsGranted)
        assertFalse(state.batteryOptimizationsGranted)
        assertFalse(state.contactsGranted)
        assertFalse(state.phoneGranted)
        assertTrue(state.simCards.isEmpty())
        assertTrue(state.isSingleSimDevice)
        assertFalse(state.displaySimCards)
        assertTrue(state.hasInternet)
        assertNull(state.permissionsAutoResetStatus)
    }
}
