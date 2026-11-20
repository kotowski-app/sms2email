package app.kotowski.sms2email.ui.smslist

import android.content.Context
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.data.database.Sms
import app.kotowski.sms2email.data.database.SmsListScope
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsSearch
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.SmsUiModel
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.ContactChangeObserver
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.system.SimChangeObserver
import app.kotowski.sms2email.system.SimNameResolver
import app.kotowski.sms2email.testing.toUiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class PagedSmsListControllerTest {
    @Mock private lateinit var appContext: Context

    @Mock private lateinit var repository: SmsRepository

    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var contactResolver: ContactResolver

    @Mock private lateinit var simNameResolver: SimNameResolver

    @Mock private lateinit var simChangeObserver: SimChangeObserver

    @Mock private lateinit var contactChangeObserver: ContactChangeObserver

    private val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()

    // The mocked window (the newest loadedLimit + 1 rows) and the unfiltered total.
    private val windowFlow = MutableStateFlow<List<SmsUiModel>>(emptyList())

    private val countFlow = MutableStateFlow(0)

    // The contact-change signal the controller's refresh collector subscribes to.
    private val contactChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private lateinit var controller: PagedSmsListController

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        whenever(prefs.load()).thenReturn(AppSettings(useContactResolution = false))
        // No live SIM changes in these tests: the collector completes immediately.
        whenever(simChangeObserver.simChanges).thenReturn(emptyFlow())
        // No live contact changes by default: the test drives the flow explicitly.
        whenever(contactChangeObserver.contactChanges).thenReturn(contactChanges)
        // A real Context never returns null; the enricher's display branch reads a
        // string resource, so an unstubbed mock would kill the pipeline with an NPE.
        whenever(appContext.getString(any<Int>())).thenReturn("Unknown SIM")
        whenever(repository.observeSms(eq(SmsListScope.HISTORY), any<SmsSearch>(), anyInt())).thenReturn(windowFlow)
        whenever(repository.observeCount(SmsListScope.HISTORY)).thenReturn(countFlow)
        controller =
            PagedSmsListController(
                CoroutineScope(testDispatcher),
                SmsListScope.HISTORY,
                repository,
                SmsListEnricher(appContext, prefs, contactResolver, simNameResolver),
                prefs,
                contactResolver,
                simNameResolver,
                simChangeObserver,
                contactChangeObserver,
            )
    }

    @After
    fun tearDown() {
        if (!::controller.isInitialized) return
        // Settle the search pipeline before the scheduler is released: the debounce may
        // still be pending and the real-IO search build resumes on the test dispatcher,
        // so it can be pumped here.
        val deadline = System.currentTimeMillis() + 2_000
        while (controller.state.value.isLoading && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceTimeBy(AppConfig.SMS_SEARCH_DEBOUNCE_MS)
            testDispatcher.scheduler.runCurrent()
            Thread.sleep(5)
        }
        testDispatcher.scheduler.runCurrent()
    }

    private fun rows(count: Int): List<SmsUiModel> =
        (1..count).map {
            SmsUiModel(
                id = it.toLong(),
                phoneNumber = "+1$it",
                messageBody = "m$it",
                simId = -1,
                status = SmsStatus.PENDING,
                statusDetails = null,
                timeReceived = 1_700_000_000_000L,
                timeLastSendAttempt = null,
                timeNextSendAttempt = null,
                retryCount = 0,
            )
        }

    // The first window emission only lands once the scheduler's clock has advanced past
    // the debounce of the initial (empty) query.
    private suspend fun awaitLoaded() {
        val deadline = System.currentTimeMillis() + 3_000
        while (controller.state.value.isLoading && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceTimeBy(AppConfig.SMS_SEARCH_DEBOUNCE_MS)
            testDispatcher.scheduler.runCurrent()
            Thread.sleep(5)
        }
        assertFalse(controller.state.value.isLoading)
    }

    private suspend fun awaitLoadedFor(ctrl: PagedSmsListController) {
        val deadline = System.currentTimeMillis() + 3_000
        while (ctrl.state.value.isLoading && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceTimeBy(AppConfig.SMS_SEARCH_DEBOUNCE_MS)
            testDispatcher.scheduler.runCurrent()
            Thread.sleep(5)
        }
        assertFalse(ctrl.state.value.isLoading)
    }

    private suspend fun awaitWindowSize(size: Int) {
        val deadline = System.currentTimeMillis() + 3_000
        while (controller.state.value.smsList.size != size && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceTimeBy(10)
            testDispatcher.scheduler.runCurrent()
            Thread.sleep(5)
        }
        assertEquals(size, controller.state.value.smsList.size)
    }

    // Waits until the repository observes a search with `likePattern`: buildSearch runs
    // on the real IO dispatcher, so the re-query is not synchronous. The re-verify is
    // required — a Mockito captor only records values while a verify() call matches.
    private suspend fun awaitSearch(likePattern: String) {
        val captor = argumentCaptor<SmsSearch>()
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            try {
                verify(repository, atLeastOnce()).observeSms(eq(SmsListScope.HISTORY), captor.capture(), anyInt())
            } catch (_: Throwable) {
                // The first (debounced) query may not have happened yet: keep pumping.
            }
            if (captor.allValues.any { it.likePattern == likePattern }) return
            testDispatcher.scheduler.advanceTimeBy(10)
            testDispatcher.scheduler.runCurrent()
            Thread.sleep(5)
        }
        verify(repository, atLeastOnce()).observeSms(eq(SmsListScope.HISTORY), captor.capture(), anyInt())
        assertTrue(captor.allValues.any { it.likePattern == likePattern })
    }

    private fun observedSearches(): List<SmsSearch> {
        val captor = argumentCaptor<SmsSearch>()
        verify(repository, atLeastOnce()).observeSms(eq(SmsListScope.HISTORY), captor.capture(), anyInt())
        return captor.allValues
    }

    @Test
    fun `the initial state is loading before the first window lands`() =
        runTest(testDispatcher) {
            countFlow.value = 42
            controller.start()
            val s = controller.state.value
            assertTrue(s.isLoading)
            assertTrue(s.smsList.isEmpty())
            // The count is not debounced: it lands with the start, not the window.
            assertEquals(42, s.smsCount)
            assertFalse(s.hasMore)

            awaitLoaded()
            assertFalse(controller.state.value.isLoading)
            assertTrue(
                controller.state.value.smsList
                    .isEmpty(),
            )
        }

    @Test
    fun `the window and count are shown as emitted by the repository`() =
        runTest(testDispatcher) {
            val pending = Sms(id = 1, messageBody = "Hi", phoneNumber = "+123", status = SmsStatus.PENDING)
            val retrying = Sms(id = 2, messageBody = "Err", phoneNumber = "+789", status = SmsStatus.RETRYING)
            windowFlow.value = listOf(pending, retrying).map { it.toUiModel() }
            countFlow.value = 7
            controller.start()
            awaitLoaded()
            val s = controller.state.value
            assertEquals(listOf(pending.toUiModel(), retrying.toUiModel()), s.smsList)
            assertEquals(7, s.smsCount)
            assertFalse(s.hasMore)
        }

    @Test
    fun `a full window without the probe row means there is no more`() =
        runTest(testDispatcher) {
            windowFlow.value = rows(AppConfig.SMS_LIST_PAGE_SIZE)
            controller.start()
            awaitLoaded()
            assertFalse(controller.state.value.hasMore)
            assertEquals(AppConfig.SMS_LIST_PAGE_SIZE, controller.state.value.smsList.size)
        }

    @Test
    fun `the probe row beyond the window means more and the list stays capped`() =
        runTest(testDispatcher) {
            windowFlow.value = rows(AppConfig.SMS_LIST_PAGE_SIZE + 1)
            controller.start()
            awaitLoaded()
            assertTrue(controller.state.value.hasMore)
            // The probe row is not part of the window.
            assertEquals(AppConfig.SMS_LIST_PAGE_SIZE, controller.state.value.smsList.size)
        }

    @Test
    fun `loadMore grows the window by one page`() =
        runTest(testDispatcher) {
            windowFlow.value = rows(2 * AppConfig.SMS_LIST_PAGE_SIZE + 1)
            controller.start()
            awaitLoaded()
            assertTrue(controller.state.value.hasMore)

            // The window query re-runs with the grown limit (window plus probe).
            controller.loadMore()
            awaitWindowSize(2 * AppConfig.SMS_LIST_PAGE_SIZE)
            val limitCaptor = argumentCaptor<Int>()
            verify(repository, atLeastOnce()).observeSms(eq(SmsListScope.HISTORY), any<SmsSearch>(), limitCaptor.capture())
            assertEquals(2 * AppConfig.SMS_LIST_PAGE_SIZE + 1, limitCaptor.allValues.max())
            assertEquals(2 * AppConfig.SMS_LIST_PAGE_SIZE, controller.state.value.smsList.size)
        }

    @Test
    fun `loadMore is a no-op when the window is exhausted`() =
        runTest(testDispatcher) {
            windowFlow.value = rows(3)
            controller.start()
            awaitLoaded()
            controller.loadMore()
            // Pump past where a (wrong) grow would have re-queried with a bigger limit:
            // buildSearch hops to the real IO dispatcher, so the re-query is not synchronous.
            val deadline = System.currentTimeMillis() + 500
            while (System.currentTimeMillis() < deadline) {
                testDispatcher.scheduler.advanceTimeBy(10)
                testDispatcher.scheduler.runCurrent()
                Thread.sleep(5)
            }
            val limitCaptor = argumentCaptor<Int>()
            verify(repository, atLeastOnce()).observeSms(eq(SmsListScope.HISTORY), any<SmsSearch>(), limitCaptor.capture())
            assertEquals(AppConfig.SMS_LIST_PAGE_SIZE + 1, limitCaptor.allValues.max())
        }

    @Test
    fun `a search re-runs the window query with the like pattern`() =
        runTest(testDispatcher) {
            controller.start()
            awaitLoaded()
            controller.setSearchQuery("abc")
            awaitSearch("%abc%")
            val search = observedSearches().first { !it.noSearch }
            assertEquals("%abc%", search.likePattern)
        }

    @Test
    fun `rapid search updates collapse into a single re-query`() =
        runTest(testDispatcher) {
            controller.start()
            awaitLoaded()
            controller.setSearchQuery("a")
            controller.setSearchQuery("ab")
            // Before the debounce window elapses: no re-query yet.
            testDispatcher.scheduler.advanceTimeBy(AppConfig.SMS_SEARCH_DEBOUNCE_MS / 2)
            testDispatcher.scheduler.runCurrent()
            assertTrue(observedSearches().all { it.noSearch })
            testDispatcher.scheduler.advanceTimeBy(AppConfig.SMS_SEARCH_DEBOUNCE_MS / 2)
            testDispatcher.scheduler.runCurrent()
            awaitSearch("%ab%")
            val searches = observedSearches()
            assertEquals(1, searches.count { !it.noSearch })
            assertEquals("%ab%", searches.first { !it.noSearch }.likePattern)
        }

    @Test
    fun `an empty query clears the search`() =
        runTest(testDispatcher) {
            controller.start()
            awaitLoaded()
            controller.setSearchQuery("abc")
            awaitSearch("%abc%")
            val noSearchBefore = observedSearches().count { it.noSearch }
            controller.setSearchQuery("")
            // Pump until the cleared (no-search) re-query lands.
            val deadline = System.currentTimeMillis() + 3_000
            while (observedSearches().count { it.noSearch } == noSearchBefore && System.currentTimeMillis() < deadline) {
                testDispatcher.scheduler.advanceTimeBy(10)
                testDispatcher.scheduler.runCurrent()
                Thread.sleep(5)
            }
            assertEquals(noSearchBefore + 1, observedSearches().count { it.noSearch })
        }

    @Test
    fun `a cleared query re-queries without waiting for the search debounce`() =
        runTest(testDispatcher) {
            windowFlow.value = rows(3)
            controller.start()
            awaitLoaded()
            controller.setSearchQuery("abc")
            awaitSearch("%abc%")
            val noSearchBefore = observedSearches().count { it.noSearch }
            val clockAtClear = testDispatcher.scheduler.currentTime
            controller.setSearchQuery("")
            // The cleared query skips the debounce: pump (bounded, real time, no virtual
            // advance) until the no-search re-query is observed.
            val deadline = System.currentTimeMillis() + 3_000
            while (observedSearches().count { it.noSearch } == noSearchBefore && System.currentTimeMillis() < deadline) {
                testDispatcher.scheduler.runCurrent()
                Thread.sleep(5)
            }
            assertEquals(noSearchBefore + 1, observedSearches().count { it.noSearch })
            // The re-query landed without the virtual clock leaving the debounce window:
            // a still-debounced clear could not have re-queried this early.
            assertTrue(testDispatcher.scheduler.currentTime - clockAtClear < AppConfig.SMS_SEARCH_DEBOUNCE_MS)
        }

    @Test
    fun `the contact facet is resolved only when contact resolution is on`() =
        runTest(testDispatcher) {
            controller.start()
            awaitLoaded()
            controller.setSearchQuery("abc")
            awaitSearch("%abc%")
            val search = observedSearches().first { !it.noSearch }
            assertTrue(search.contactNumbers.isEmpty())
            verify(contactResolver, never()).resolveNumbersByName("abc")
        }

    @Test
    fun `the contact facet joins the search when contact resolution is on`() =
        runTest(testDispatcher) {
            whenever(prefs.load()).thenReturn(AppSettings(useContactResolution = true))
            whenever(contactResolver.resolveNumbersByName("abc")).thenReturn(listOf("123", "456"))
            controller.start()
            awaitLoaded()
            controller.setSearchQuery("abc")
            awaitSearch("%abc%")
            assertEquals(listOf("123", "456"), observedSearches().first { !it.noSearch }.contactNumbers)
        }

    @Test
    fun `the sim facet matches slot display names when the display is on`() =
        runTest(testDispatcher) {
            whenever(prefs.load()).thenReturn(AppSettings(displaySimCards = true))
            whenever(simNameResolver.getSlotEntries()).thenReturn(listOf(1 to "Home", 2 to "Work", 3 to null))
            controller.start()
            awaitLoaded()
            controller.setSearchQuery("work")
            awaitSearch("%work%")
            val search = observedSearches().first { !it.noSearch }
            assertEquals(listOf(2), search.simIds)
            assertTrue(search.contactNumbers.isEmpty())
        }

    @Test
    fun `the sim facet is skipped when the display is off`() =
        runTest(testDispatcher) {
            controller.start()
            awaitLoaded()
            controller.setSearchQuery("work")
            awaitSearch("%work%")
            assertTrue(observedSearches().first { !it.noSearch }.simIds.isEmpty())
            verify(simNameResolver, never()).getSlotEntries()
        }

    @Test
    fun `a page grow re-runs the window query without re-resolving the facets`() =
        runTest(testDispatcher) {
            whenever(prefs.load()).thenReturn(AppSettings(useContactResolution = true))
            whenever(contactResolver.resolveNumbersByName("abc")).thenReturn(listOf("123"))
            windowFlow.value = rows(2 * AppConfig.SMS_LIST_PAGE_SIZE + 1)
            controller.start()
            controller.setSearchQuery("abc")
            awaitSearch("%abc%")
            verify(contactResolver, times(1)).resolveNumbersByName("abc")
            // The page grow re-emits the same (debounced) query: buildSearch must hit
            // its per-query cache instead of re-hitting the contacts provider.
            controller.loadMore()
            awaitWindowSize(2 * AppConfig.SMS_LIST_PAGE_SIZE)
            verify(contactResolver, times(1)).resolveNumbersByName("abc")
        }

    @Test
    fun `a contact change re-resolves the window names without a DB re-query`() =
        runTest(testDispatcher) {
            whenever(prefs.load()).thenReturn(AppSettings(useContactResolution = true))
            whenever(contactResolver.resolveAll(any<Collection<String>>()))
                .thenReturn(mapOf("+11" to "Jane Doe"))
                .thenReturn(mapOf("+11" to "Jane D."))
            windowFlow.value = rows(1)
            controller.start()
            awaitLoaded()
            assertEquals(mapOf("+11" to "Jane Doe"), controller.state.value.contactNames)
            val queriesBefore = observedSearches().size

            contactChanges.tryEmit(Unit)

            val deadline = System.currentTimeMillis() + 3_000
            while (
                controller.state.value.contactNames != mapOf("+11" to "Jane D.") &&
                System.currentTimeMillis() < deadline
            ) {
                testDispatcher.scheduler.advanceTimeBy(10)
                testDispatcher.scheduler.runCurrent()
                Thread.sleep(5)
            }
            assertEquals(mapOf("+11" to "Jane D."), controller.state.value.contactNames)
            // The refresh re-resolves the loaded window only: no new window query.
            assertEquals(queriesBefore, observedSearches().size)
        }

    @Test
    fun `a contact change is a no-op before the first window lands`() =
        runTest(testDispatcher) {
            whenever(prefs.load()).thenReturn(AppSettings(useContactResolution = true))
            controller.start()
            awaitLoaded()

            contactChanges.tryEmit(Unit)
            testDispatcher.scheduler.runCurrent()

            verify(contactResolver, never()).resolveAll(any<Collection<String>>())
        }

    // A second call to start() cancels the jobs from the first call: the ?.cancel()
    // branches exercise the non-null path on every entry point.
    @Test
    fun `calling start twice cancels the previous collection jobs`() =
        runTest(testDispatcher) {
            controller.start()
            awaitLoaded()
            controller.start()
            awaitLoaded()
            // State is still valid after restart.
            assertFalse(controller.state.value.isLoading)
        }

    // A live SIM change event drives the collector body: the controller re-enriches the
    // loaded window's simNames without a DB re-query, updating the badges in place.
    @Test
    fun `a sim change re-resolves the loaded window sim names without a DB re-query`() =
        runTest(testDispatcher) {
            // Use a fresh controller with a drivable SIM change flow (setUp's emptyFlow
            // completes immediately, so the collector body never runs).
            val simEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            whenever(simChangeObserver.simChanges).thenReturn(simEvents)
            whenever(prefs.load()).thenReturn(AppSettings(displaySimCards = true))
            whenever(simNameResolver.getSimDisplayNameSync(1)).thenReturn("Home")
            whenever(simNameResolver.getSlotFor(1)).thenReturn(1)
            val ctrl =
                PagedSmsListController(
                    CoroutineScope(testDispatcher),
                    SmsListScope.HISTORY,
                    repository,
                    SmsListEnricher(appContext, prefs, contactResolver, simNameResolver),
                    prefs,
                    contactResolver,
                    simNameResolver,
                    simChangeObserver,
                    contactChangeObserver,
                )
            val row =
                SmsUiModel(
                    id = 1,
                    phoneNumber = "+11",
                    messageBody = "m1",
                    simId = 1,
                    status = SmsStatus.PENDING,
                    statusDetails = null,
                    timeReceived = 1_700_000_000_000L,
                    timeLastSendAttempt = null,
                    timeNextSendAttempt = null,
                    retryCount = 0,
                )
            windowFlow.value = listOf(row)
            countFlow.value = 1
            ctrl.start()
            awaitLoadedFor(ctrl)
            assertEquals(mapOf(1 to "Home"), ctrl.state.value.simNames)
            assertEquals(mapOf(1 to 1), ctrl.state.value.simSlots)

            // Simulate a SIM rename.
            whenever(simNameResolver.getSimDisplayNameSync(1)).thenReturn("Work")
            whenever(simNameResolver.getSlotFor(1)).thenReturn(2)
            assertTrue(simEvents.tryEmit(Unit))

            val deadline = System.currentTimeMillis() + 3_000
            while (
                ctrl.state.value.simNames != mapOf(1 to "Work") &&
                System.currentTimeMillis() < deadline
            ) {
                testDispatcher.scheduler.advanceTimeBy(10)
                testDispatcher.scheduler.runCurrent()
                Thread.sleep(5)
            }
            assertEquals(mapOf(1 to "Work"), ctrl.state.value.simNames)
            assertEquals(mapOf(1 to 2), ctrl.state.value.simSlots)
        }

    // A SIM change before the first window lands is a no-op: the currentWindow is
    // still empty, so the collector returns early and never touches the enricher.
    @Test
    fun `a sim change is a no-op before the first window lands`() =
        runTest(testDispatcher) {
            val simEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            whenever(simChangeObserver.simChanges).thenReturn(simEvents)
            val ctrl =
                PagedSmsListController(
                    CoroutineScope(testDispatcher),
                    SmsListScope.HISTORY,
                    repository,
                    SmsListEnricher(appContext, prefs, contactResolver, simNameResolver),
                    prefs,
                    contactResolver,
                    simNameResolver,
                    simChangeObserver,
                    contactChangeObserver,
                )
            ctrl.start()
            // Emit a SIM change while currentWindow is still empty.
            assertTrue(simEvents.tryEmit(Unit))
            testDispatcher.scheduler.runCurrent()
            // No enrichment call because there is no loaded data.
            verify(simNameResolver, never()).getSimDisplayNameSync(any())
            // Now load a window and verify the SIM change does not replay.
            windowFlow.value = rows(1)
            awaitLoadedFor(ctrl)
        }
}
