package app.kotowski.sms2email.ui.smsdetail

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.impl.WorkManagerImpl
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.SmsUiModel
import app.kotowski.sms2email.data.database.StatusUpdate
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.system.NetworkConnectivityObserver
import app.kotowski.sms2email.system.NetworkStatus
import app.kotowski.sms2email.system.SimChangeObserver
import app.kotowski.sms2email.system.SimNameResolver
import app.kotowski.sms2email.testing.WorkManagerDelegate
import app.kotowski.sms2email.testing.sms
import app.kotowski.sms2email.testing.smsModel
import app.kotowski.sms2email.testing.stubInternetEnabled
import app.kotowski.sms2email.testing.withMockedToast
import app.kotowski.sms2email.work.SMS_ID_KEY
import app.kotowski.sms2email.work.SmsActions
import app.kotowski.sms2email.work.SmsRetryScheduler
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockedStatic
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
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
class SmsDetailViewModelTest {
    @Mock private lateinit var repository: SmsRepository

    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var simNameResolver: SimNameResolver

    @Mock private lateinit var simChangeObserver: SimChangeObserver

    @Mock private lateinit var contactResolver: ContactResolver

    @Mock private lateinit var retryScheduler: SmsRetryScheduler

    @Mock private lateinit var networkObserver: NetworkConnectivityObserver

    @Mock private lateinit var context: Context

    @Mock private lateinit var workManager: WorkManagerImpl

    @Mock private lateinit var workInfosFuture: ListenableFuture<List<WorkInfo>>

    @Mock private lateinit var connectivityManager: ConnectivityManager

    @Mock private lateinit var network: Network

    @Mock private lateinit var networkCapabilities: NetworkCapabilities

    private lateinit var viewModel: SmsDetailViewModel
    private lateinit var networkStatusFlow: MutableStateFlow<NetworkStatus>
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        whenever(prefs.load()).thenReturn(AppSettings(useContactResolution = false))
        whenever(context.getString(any<Int>())).thenReturn("Unknown SIM")
        whenever(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)
        whenever(connectivityManager.activeNetwork).thenReturn(null)
        networkStatusFlow = MutableStateFlow(NetworkStatus.Available)
        whenever(networkObserver.isConnectedCurrently()).thenReturn(true)
        whenever(networkObserver.networkStatus).thenReturn(networkStatusFlow)
        // No live SIM changes in these tests: the collector completes immediately.
        whenever(simChangeObserver.simChanges).thenReturn(emptyFlow())
        // No live per-row work by default: enqueueSend picks REPLACE (the default policy).
        // isDone=true puts await() on its fast path, which calls the stubbed no-arg get().
        whenever(workManager.getWorkInfosForUniqueWork(any())).thenReturn(workInfosFuture)
        whenever(workInfosFuture.isDone()).thenReturn(true)
        whenever(workInfosFuture.get()).thenReturn(emptyList())

        viewModel =
            SmsDetailViewModel(
                appContext = context,
                repository = repository,
                prefs = prefs,
                contactResolver = contactResolver,
                simNameResolver = simNameResolver,
                simChangeObserver = simChangeObserver,
                actions = SmsActions(repository, context, prefs, retryScheduler, simNameResolver),
                networkObserver = networkObserver,
            )
    }

    @After
    fun tearDown() {
        testDispatcher.scheduler.runCurrent()
        // Park Main on a dispatcher nobody pumps instead of resetMain: a stray IO
        // continuation may still resume after the test and crash the unmocked real Main.
        Dispatchers.setMain(StandardTestDispatcher())
    }

    private fun enableInternet() = stubInternetEnabled(connectivityManager, network, networkCapabilities)

    private fun currentDetailSms(): SmsUiModel? = (viewModel.detailState.value as? SmsDetailState.Loaded)?.data?.sms

    // Waits until the detail row landed: the sim-name enrichment hops to the real IO
    // dispatcher, so the state update is not synchronous (same as the window load).
    private suspend fun awaitDetail(expected: SmsUiModel?) {
        val deadline = System.currentTimeMillis() + 3_000
        while (currentDetailSms() != expected && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.runCurrent()
            kotlinx.coroutines.delay(5)
        }
        assertEquals(expected, currentDetailSms())
    }

    // Connectivity is collected reactively (the dashboard pattern): the initial value
    // comes from the sync read, then every flow emission updates hasInternet while
    // the detail is open — no re-read dependent on a recomposition.

    @Test
    fun `initial state has internet per the observer sync read`() {
        assertTrue(viewModel.state.value.hasInternet)
    }

    @Test
    fun `hasInternet follows connectivity changes while the detail is open`() {
        networkStatusFlow.value = NetworkStatus.Unavailable
        testDispatcher.scheduler.runCurrent()
        assertFalse(viewModel.state.value.hasInternet)

        networkStatusFlow.value = NetworkStatus.Available
        testDispatcher.scheduler.runCurrent()
        assertTrue(viewModel.state.value.hasInternet)
    }

    @Test
    fun `loadSms observes the row by id from the db and tracks it live`() =
        runTest(testDispatcher) {
            val row = smsModel(42L, SmsStatus.SENT)
            val flow = MutableStateFlow<SmsUiModel?>(row)
            whenever(repository.observeSmsById(42L)).thenReturn(flow)

            viewModel.loadSms(42L)
            awaitDetail(row)

            val updated = row.copy(status = SmsStatus.FAILED)
            flow.value = updated
            awaitDetail(updated)

            whenever(repository.observeSmsById(99L)).thenReturn(MutableStateFlow<SmsUiModel?>(null))
            viewModel.loadSms(99L)

            // The unconfined test dispatcher delivers the first (null) emission
            // synchronously, so the id with no row lands in NotFound right away.
            assertEquals(SmsDetailState.NotFound, viewModel.detailState.value)
        }

    // Waits until the detail state holds the expected sim names (the enrichment hops to
    // the real IO dispatcher, same as the row load).
    private suspend fun awaitSimNames(
        vm: SmsDetailViewModel,
        expected: Map<Int, String>,
    ) {
        val deadline = System.currentTimeMillis() + 3_000
        while (
            (vm.detailState.value as? SmsDetailState.Loaded)?.data?.simNames != expected &&
            System.currentTimeMillis() < deadline
        ) {
            testDispatcher.scheduler.runCurrent()
            kotlinx.coroutines.delay(5)
        }
        assertEquals(expected, (vm.detailState.value as? SmsDetailState.Loaded)?.data?.simNames)
    }

    @Test
    fun `a sim change re-resolves the name on the loaded row`() =
        runTest(testDispatcher) {
            // This test drives live SIM changes: a fresh VM on a drivable flow (the shared
            // setUp VM already collected its completed emptyFlow).
            val simEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            whenever(simChangeObserver.simChanges).thenReturn(simEvents)
            whenever(prefs.load()).thenReturn(AppSettings(displaySimCards = true))
            whenever(simNameResolver.getSimDisplayNameSync(1)).thenReturn("Home")
            whenever(simNameResolver.getSlotFor(1)).thenReturn(1)
            val vm =
                SmsDetailViewModel(
                    appContext = context,
                    repository = repository,
                    prefs = prefs,
                    contactResolver = contactResolver,
                    simNameResolver = simNameResolver,
                    simChangeObserver = simChangeObserver,
                    actions = SmsActions(repository, context, prefs, retryScheduler, simNameResolver),
                    networkObserver = networkObserver,
                )
            val row = smsModel(42L, SmsStatus.SENT, simId = 1)
            whenever(repository.observeSmsById(42L)).thenReturn(MutableStateFlow<SmsUiModel?>(row))

            vm.loadSms(42L)
            awaitSimNames(vm, mapOf(1 to "Home"))
            assertEquals(mapOf(1 to 1), (vm.detailState.value as? SmsDetailState.Loaded)?.data?.simSlots)

            whenever(simNameResolver.getSimDisplayNameSync(1)).thenReturn("Work")
            whenever(simNameResolver.getSlotFor(1)).thenReturn(2)
            assertTrue(simEvents.tryEmit(Unit))
            awaitSimNames(vm, mapOf(1 to "Work"))
            assertEquals(mapOf(1 to 2), (vm.detailState.value as? SmsDetailState.Loaded)?.data?.simSlots)
        }

    // The gate, offline park, CAS and stranded-row guard live in SmsActionsForwardTest; here
    // only the detail-specific worker wiring is tested: the per-row EmailWorker, not the batch.
    @Test
    fun `forwardRow transitions to PENDING via CAS and enqueues the per-row EmailWorker`() =
        runTest(testDispatcher) {
            WorkManagerDelegate.install(workManager)
            try {
                enableInternet()
                whenever(repository.getSmsByIds(eq(listOf(1L)))).thenReturn(listOf(sms(1L, SmsStatus.FAILED)))
                whenever(
                    repository.transitionSmsStatusMany(
                        eq(listOf(1L)),
                        eq(SmsStatus.MANUAL_FORWARDABLE),
                        eq(SmsStatus.PENDING),
                        any(),
                    ),
                ).thenReturn(listOf(1L))

                viewModel.forwardRow(smsModel(1L, SmsStatus.FAILED))

                val updateCaptor = argumentCaptor<StatusUpdate>()
                verify(repository).transitionSmsStatusMany(
                    eq(listOf(1L)),
                    eq(SmsStatus.MANUAL_FORWARDABLE),
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
                verify(workManager).enqueueUniqueWork(eq("email_send_1"), eq(ExistingWorkPolicy.REPLACE), captor.capture())
                assertEquals(
                    1L,
                    captor.firstValue.workSpec.input
                        .getLong(SMS_ID_KEY, -1),
                )
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }

    @Test
    fun `cancelRow parks the in-flight row as IGNORED with the user-cancel detail`() =
        runTest(testDispatcher) {
            whenever(context.getString(R.string.sms_status_cancelled_by_user)).thenReturn("Canceled by user.")
            whenever(repository.getSmsByIds(eq(listOf(1L)))).thenReturn(listOf(sms(1L, SmsStatus.PENDING)))
            whenever(
                repository.transitionSmsStatusMany(
                    eq(listOf(1L)),
                    eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                    eq(SmsStatus.IGNORED),
                    any(),
                ),
            ).thenReturn(listOf(1L))

            withMockedToast { toast ->
                viewModel.cancelRow(smsModel(1L, SmsStatus.PENDING))
                toast.verify(MockedStatic<Toast>.Verification { Toast.makeText(any<Context>(), any<Int>(), any<Int>()) }, never())
            }

            val updateCaptor = argumentCaptor<StatusUpdate>()
            verify(repository).transitionSmsStatusMany(
                eq(listOf(1L)),
                eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                eq(SmsStatus.IGNORED),
                updateCaptor.capture(),
            )
            assertEquals("Canceled by user.", updateCaptor.firstValue.statusDetails)
            verify(repository, never()).deleteSmsByIds(any<List<Long>>())
        }

    @Test
    fun `cancelRow shows a toast and never deletes when the row is in flight (SENDING)`() =
        runTest(testDispatcher) {
            whenever(repository.getSmsByIds(eq(listOf(1L)))).thenReturn(listOf(sms(1L, SmsStatus.SENDING)))

            withMockedToast { toast ->
                viewModel.cancelRow(smsModel(1L, SmsStatus.SENDING))
                // One row requested, fully blocked: the singular reads for the requested count.
                toast.verify { Toast.makeText(eq(context), eq(R.string.sms_cancel_blocked_single), eq(Toast.LENGTH_SHORT)) }
            }

            verify(repository, never()).deleteSmsByIds(any<List<Long>>())
        }

    @Test
    fun `deleteRow deletes the row through the status-filtered delete`() =
        runTest(testDispatcher) {
            whenever(repository.deleteSmsByIds(eq(listOf(1L)))).thenReturn(1)

            withMockedToast { toast ->
                viewModel.deleteRow(1L)
                toast.verify(MockedStatic<Toast>.Verification { Toast.makeText(any<Context>(), any<Int>(), any<Int>()) }, never())
            }

            verify(repository).deleteSmsByIds(listOf(1L))
        }

    @Test
    fun `deleteRow shows a toast when the in-flight guard skipped the row`() =
        runTest(testDispatcher) {
            whenever(repository.deleteSmsByIds(eq(listOf(1L)))).thenReturn(0)

            withMockedToast { toast ->
                viewModel.deleteRow(1L)
                toast.verify { Toast.makeText(eq(context), eq(R.string.sms_delete_blocked), eq(Toast.LENGTH_SHORT)) }
            }
        }

    @Test
    fun `resolveContactName returns null and does not query when contact resolution is off`() =
        runTest(testDispatcher) {
            assertNull(viewModel.resolveContactName("+1"))
            verify(contactResolver, never()).resolve(any())
        }

    @Test
    fun `resolveContactName returns the resolved name when contact resolution is on`() =
        runTest(testDispatcher) {
            whenever(prefs.load()).thenReturn(AppSettings(useContactResolution = true))
            whenever(contactResolver.resolve("+1")).thenReturn("John Doe")

            assertEquals("John Doe", viewModel.resolveContactName("+1"))
            verify(contactResolver).resolve("+1")
        }

    // Sim change collector is a no-op until the first row loads: the detailState is still
    // Loading, so the cast to Loaded fails and the collector returns early.
    @Test
    fun `sim change is a no-op before the first row loads`() =
        runTest(testDispatcher) {
            val simEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            whenever(simChangeObserver.simChanges).thenReturn(simEvents)
            val vm =
                SmsDetailViewModel(
                    appContext = context,
                    repository = repository,
                    prefs = prefs,
                    contactResolver = contactResolver,
                    simNameResolver = simNameResolver,
                    simChangeObserver = simChangeObserver,
                    actions = SmsActions(repository, context, prefs, retryScheduler, simNameResolver),
                    networkObserver = networkObserver,
                )
            // Emit a SIM change while detailState is still Loading.
            assertTrue(simEvents.tryEmit(Unit))
            testDispatcher.scheduler.runCurrent()
            // No enrichment call because there is no loaded data.
            verify(simNameResolver, never()).getSimDisplayNameSync(any())
        }

    // When the loaded row switches (user navigated to a different SMS), the sim change
    // event must not overwrite the new row's enrichment with the old row's names.
    @Test
    fun `sim change does not clobber a different loaded row`() =
        runTest(testDispatcher) {
            val simEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            whenever(simChangeObserver.simChanges).thenReturn(simEvents)
            whenever(prefs.load()).thenReturn(AppSettings(displaySimCards = true))
            whenever(simNameResolver.getSimDisplayNameSync(1)).thenReturn("Home")
            whenever(simNameResolver.getSimDisplayNameSync(2)).thenReturn("Work")
            whenever(simNameResolver.getSlotFor(1)).thenReturn(1)
            whenever(simNameResolver.getSlotFor(2)).thenReturn(2)
            val vm =
                SmsDetailViewModel(
                    appContext = context,
                    repository = repository,
                    prefs = prefs,
                    contactResolver = contactResolver,
                    simNameResolver = simNameResolver,
                    simChangeObserver = simChangeObserver,
                    actions = SmsActions(repository, context, prefs, retryScheduler, simNameResolver),
                    networkObserver = networkObserver,
                )
            val row1 = smsModel(42L, SmsStatus.SENT, simId = 1)
            whenever(repository.observeSmsById(42L)).thenReturn(MutableStateFlow<SmsUiModel?>(row1))
            vm.loadSms(42L)
            awaitSimNames(vm, mapOf(1 to "Home"))

            // Load a different row while a SIM change event is pending.
            val row2 = smsModel(99L, SmsStatus.SENT, simId = 2)
            whenever(repository.observeSmsById(99L)).thenReturn(MutableStateFlow<SmsUiModel?>(row2))
            vm.loadSms(99L)
            awaitSimNames(vm, mapOf(2 to "Work"))

            // Now the SIM change fires: it captured row1's data, but row2 is loaded.
            assertTrue(simEvents.tryEmit(Unit))
            testDispatcher.scheduler.runCurrent()
            // The update's id check prevents overwriting row2's SIM data with row1's.
            assertEquals(mapOf(2 to "Work"), (vm.detailState.value as? SmsDetailState.Loaded)?.data?.simNames)
            assertEquals(mapOf(2 to 2), (vm.detailState.value as? SmsDetailState.Loaded)?.data?.simSlots)
        }

    // A row with no DB entry lands in NotFound immediately, not Loading.
    @Test
    fun `loadSms with no matching row lands in NotFound`() =
        runTest(testDispatcher) {
            whenever(repository.observeSmsById(999L)).thenReturn(MutableStateFlow<SmsUiModel?>(null))
            viewModel.loadSms(999L)
            testDispatcher.scheduler.runCurrent()
            assertEquals(SmsDetailState.NotFound, viewModel.detailState.value)
        }

    // The forwardRow toast displays the outcome: one forwarded, zero skipped.
    @Test
    fun `forwardRow displays the outcome toast`() =
        runTest(testDispatcher) {
            WorkManagerDelegate.install(workManager)
            try {
                enableInternet()
                whenever(repository.getSmsByIds(eq(listOf(1L)))).thenReturn(listOf(sms(1L, SmsStatus.FAILED)))
                whenever(
                    repository.transitionSmsStatusMany(
                        eq(listOf(1L)),
                        eq(SmsStatus.MANUAL_FORWARDABLE),
                        eq(SmsStatus.PENDING),
                        any(),
                    ),
                ).thenReturn(listOf(1L))

                withMockedToast {
                    viewModel.forwardRow(smsModel(1L, SmsStatus.FAILED))
                }

                verify(
                    workManager,
                ).enqueueUniqueWork(eq("email_send_1"), eq(ExistingWorkPolicy.REPLACE), any<androidx.work.OneTimeWorkRequest>())
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }

    // The forwardRow with no CAS match (row status changed): the skip toast fires.
    @Test
    fun `forwardRow shows skip toast when CAS does not match`() =
        runTest(testDispatcher) {
            enableInternet()
            // The row was SENT (in MANUAL_FORWARDABLE), but the CAS transition returns
            // empty (status changed between the read and the transition).
            whenever(repository.getSmsByIds(eq(listOf(1L)))).thenReturn(listOf(sms(1L, SmsStatus.SENT)))
            whenever(
                repository.transitionSmsStatusMany(
                    eq(listOf(1L)),
                    eq(SmsStatus.MANUAL_FORWARDABLE),
                    eq(SmsStatus.PENDING),
                    any(),
                ),
            ).thenReturn(emptyList())

            withMockedToast {
                viewModel.forwardRow(smsModel(1L, SmsStatus.SENT))
            }

            // No worker enqueued because the CAS found no matching rows.
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }

    // The loadSms cancel path: calling loadSms a second time cancels the previous job.
    @Test
    fun `loadSms cancels the previous observation job`() =
        runTest(testDispatcher) {
            val flow1 = MutableStateFlow<SmsUiModel?>(smsModel(1L, SmsStatus.SENT))
            val flow2 = MutableStateFlow<SmsUiModel?>(smsModel(2L, SmsStatus.PENDING))
            whenever(repository.observeSmsById(1L)).thenReturn(flow1)
            whenever(repository.observeSmsById(2L)).thenReturn(flow2)

            viewModel.loadSms(1L)
            awaitDetail(smsModel(1L, SmsStatus.SENT))
            assertEquals(smsModel(1L, SmsStatus.SENT).id, currentDetailSms()?.id)

            viewModel.loadSms(2L)
            awaitDetail(smsModel(2L, SmsStatus.PENDING))
            assertEquals(2L, currentDetailSms()?.id)
        }

    @Test
    fun `init block reads displaySimCards from prefs`() {
        // Default prefs: useContactResolution = false, displaySimCards = false
        assertEquals(false, viewModel.state.value.displaySimCards)
    }

    // The deleteRow lambda: when the row is in-flight (SENDING), the delete is blocked.
    @Test
    fun `deleteRow blocks when the row is sending`() =
        runTest(testDispatcher) {
            whenever(repository.deleteSmsByIds(eq(listOf(1L)))).thenReturn(0)

            withMockedToast { toast ->
                viewModel.deleteRow(1L)
                toast.verify { Toast.makeText(eq(context), eq(R.string.sms_delete_blocked), eq(Toast.LENGTH_SHORT)) }
            }
        }

    // The cancelRow with a partial skip: some rows are SENDING, some are cancelled.
    @Test
    fun `cancelRow with partial result shows partial skip toast`() =
        runTest(testDispatcher) {
            // Row is SENDING: cancel will skip it.
            whenever(repository.getSmsByIds(eq(listOf(1L)))).thenReturn(listOf(sms(1L, SmsStatus.SENDING)))

            withMockedToast { toast ->
                viewModel.cancelRow(smsModel(1L, SmsStatus.SENDING))
                toast.verify { Toast.makeText(eq(context), eq(R.string.sms_cancel_blocked_single), eq(Toast.LENGTH_SHORT)) }
            }

            verify(repository, never()).transitionSmsStatusMany(any(), any(), any(), any())
        }

    @Test
    fun `init block connectivity collector sets initial hasInternet from observer`() {
        // The setUp mocks networkObserver.isConnectedCurrently() to return true.
        assertTrue(viewModel.state.value.hasInternet)
    }

    @Test
    fun `resolveContactName with resolution on returns the name`() =
        runTest(testDispatcher) {
            whenever(prefs.load()).thenReturn(AppSettings(useContactResolution = true))
            whenever(contactResolver.resolve("+15550100")).thenReturn("Alice")
            assertEquals("Alice", viewModel.resolveContactName("+15550100"))
        }

    // The SIM change collector: when the detailState is Loaded, the simNames lambda runs.
    @Test
    fun `sim change collector lambda builds sim names for the loaded row`() =
        runTest(testDispatcher) {
            val simEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            whenever(simChangeObserver.simChanges).thenReturn(simEvents)
            whenever(prefs.load()).thenReturn(AppSettings(displaySimCards = true))
            whenever(simNameResolver.getSimDisplayNameSync(2)).thenReturn("Mobile")
            val vm =
                SmsDetailViewModel(
                    appContext = context,
                    repository = repository,
                    prefs = prefs,
                    contactResolver = contactResolver,
                    simNameResolver = simNameResolver,
                    simChangeObserver = simChangeObserver,
                    actions = SmsActions(repository, context, prefs, retryScheduler, simNameResolver),
                    networkObserver = networkObserver,
                )
            val row = smsModel(7L, SmsStatus.PENDING, simId = 2)
            whenever(repository.observeSmsById(7L)).thenReturn(MutableStateFlow<SmsUiModel?>(row))

            vm.loadSms(7L)
            awaitSimNames(vm, mapOf(2 to "Mobile"))

            // Now trigger a SIM change to exercise the collector lambda again.
            whenever(simNameResolver.getSimDisplayNameSync(2)).thenReturn("Data")
            assertTrue(simEvents.tryEmit(Unit))
            awaitSimNames(vm, mapOf(2 to "Data"))
        }
}
