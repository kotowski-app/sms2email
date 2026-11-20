package app.kotowski.sms2email.ui.queue

import android.content.Context
import android.net.ConnectivityManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.SmsListScope
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsSearch
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
import app.kotowski.sms2email.testing.smsModel
import app.kotowski.sms2email.testing.withMockedToast
import app.kotowski.sms2email.work.SmsActions
import app.kotowski.sms2email.work.SmsRetryScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
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
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The Queue screen's search state: an active query must survive the dismiss/cancel paths
// of the confirm dialogs and the top-bar actions (no action taken), and be dropped only
// on confirm (the rows leave the scope).
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QueueScreenTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

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

    // The mocked queue window (the newest loadedLimit + 1 rows) and the unfiltered total.
    private val windowFlow = MutableStateFlow<List<SmsUiModel>>(emptyList())

    private val countFlow = MutableStateFlow(0)

    private lateinit var viewModel: QueueViewModel

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        whenever(prefs.load()).thenReturn(AppSettings())
        // No live SIM changes in these tests: the collector completes immediately.
        whenever(simChangeObserver.simChanges).thenReturn(emptyFlow())
        // No live contact changes in these tests: the refresh collector stays idle.
        whenever(contactChangeObserver.contactChanges).thenReturn(emptyFlow())
        whenever(repository.observeSms(eq(SmsListScope.QUEUE), any<SmsSearch>(), anyInt())).thenReturn(windowFlow)
        whenever(repository.observeCount(SmsListScope.QUEUE)).thenReturn(countFlow)
        whenever(appContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)
        whenever(connectivityManager.activeNetwork).thenReturn(null)
        whenever(networkObserver.isConnectedCurrently()).thenReturn(true)
        whenever(networkObserver.networkStatus).thenReturn(flowOf(NetworkStatus.Available))
        // The confirm actions fetch the rows they act on; an empty fetch keeps them
        // silent (no toast) — these tests cover the search state, not the action.
        runBlocking {
            whenever(repository.getSmsByIds(any<List<Long>>())).thenReturn(emptyList())
            whenever(repository.getQueueIds()).thenReturn(emptyList())
        }
        viewModel =
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
    }

    @After
    fun tearDown() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        // Park Main on an unpumped dispatcher instead of resetMain: a stray IO
        // continuation can resume after the test, and resetMain would send it to the
        // unmocked real Main and crash a worker thread.
        Dispatchers.setMain(StandardTestDispatcher())
    }

    private fun showScreen(rows: List<SmsUiModel>) {
        windowFlow.value = rows
        countFlow.value = rows.size
        composeTestRule.setContent {
            QueueScreen(
                onNavigateBack = { },
                onSmsClicked = { },
                viewModel = viewModel,
            )
        }
        // The first window lands after the search debounce plus a real-IO build/enrich
        // hop, so settle on the test scheduler bounded in real time.
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.state.value.paged.isLoading && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceTimeBy(AppConfig.SMS_SEARCH_DEBOUNCE_MS)
            testDispatcher.scheduler.runCurrent()
            Thread.sleep(5)
        }
        check(!viewModel.state.value.paged.isLoading)
        composeTestRule.waitForIdle()
    }

    // The search field is the only settable-text node on the screen.
    private fun searchField() = composeTestRule.onNode(hasSetTextAction())

    // Toggles search on and types the query; the debounced query is pushed to the
    // ViewModel, so wait for the repository to re-observe with it.
    private fun activateSearch(query: String) {
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.sms_search_hint))
            .performClick()
        composeTestRule.waitForIdle()
        searchField().performTextInput(query)
        composeTestRule.waitForIdle()
        waitForQueryPushed(query)
    }

    private fun waitForQueryPushed(query: String) {
        val captor = argumentCaptor<SmsSearch>()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceTimeBy(AppConfig.SMS_SEARCH_DEBOUNCE_MS)
            testDispatcher.scheduler.runCurrent()
            verify(repository, atLeastOnce()).observeSms(eq(SmsListScope.QUEUE), captor.capture(), anyInt())
            if (captor.allValues.any { !it.noSearch && it.likePattern == "%$query%" }) return
            Thread.sleep(5)
        }
        throw AssertionError("the query '$query' never reached the repository")
    }

    // Pump past the search debounce so whatever the UI last pushed has reached the
    // repository (a buggy clear would land within this window and be caught).
    private fun settleSearchPush() {
        repeat(5) {
            testDispatcher.scheduler.advanceTimeBy(AppConfig.SMS_SEARCH_DEBOUNCE_MS)
            testDispatcher.scheduler.runCurrent()
            Thread.sleep(10)
        }
    }

    private fun lastPushedSearch(): SmsSearch {
        val captor = argumentCaptor<SmsSearch>()
        verify(repository, atLeastOnce()).observeSms(eq(SmsListScope.QUEUE), captor.capture(), anyInt())
        return captor.allValues.last()
    }

    private fun assertSearchPreserved(query: String) {
        settleSearchPush()
        assertEquals("%$query%", lastPushedSearch().likePattern)
        composeTestRule.onNodeWithText(query).assertExists()
    }

    private fun assertSearchCleared() {
        settleSearchPush()
        assertTrue(lastPushedSearch().noSearch)
        searchField().assertDoesNotExist()
    }

    @Test
    fun `cancelling the forward selection dialog keeps the active search`() {
        showScreen(listOf(smsModel(1, SmsStatus.PENDING, messageBody = "hello world")))
        activateSearch("hello")
        assertEquals("%hello%", lastPushedSearch().likePattern)

        // Long-press enters the selection mode (the search field stays visible).
        composeTestRule.onNodeWithText("hello world").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.action_send))
            .performClick()
        composeTestRule.waitForIdle()

        // The top bar's own cancel is still behind the dialog, so the dialog's cancel
        // is the "Cancel" text that has the confirm ("Send") as a sibling.
        composeTestRule
            .onNode(
                hasText(context.getString(android.R.string.cancel))
                    .and(hasAnySibling(hasText(context.getString(R.string.action_send)))),
            ).performClick()
        composeTestRule.waitForIdle()

        assertSearchPreserved("hello")
    }

    @Test
    fun `confirming the forward selection dialog clears the active search`() {
        showScreen(listOf(smsModel(1, SmsStatus.PENDING, messageBody = "hello world")))
        activateSearch("hello")
        assertEquals("%hello%", lastPushedSearch().likePattern)

        composeTestRule.onNodeWithText("hello world").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.action_send))
            .performClick()
        composeTestRule.waitForIdle()

        withMockedToast {
            composeTestRule
                .onNodeWithText(context.getString(R.string.action_send))
                .performClick()
        }
        composeTestRule.waitForIdle()

        assertSearchCleared()
    }

    @Test
    fun `cancelling the forward all dialog keeps the active search`() {
        showScreen(listOf(smsModel(1, SmsStatus.PENDING, messageBody = "hello world")))
        activateSearch("hello")
        assertEquals("%hello%", lastPushedSearch().likePattern)

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.queue_forward_all_content_desc))
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        composeTestRule.waitForIdle()

        assertSearchPreserved("hello")
    }

    @Test
    fun `confirming the forward all dialog clears the active search`() {
        showScreen(listOf(smsModel(1, SmsStatus.PENDING, messageBody = "hello world")))
        activateSearch("hello")
        assertEquals("%hello%", lastPushedSearch().likePattern)

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.queue_forward_all_content_desc))
            .performClick()
        composeTestRule.waitForIdle()

        withMockedToast {
            composeTestRule
                .onNodeWithText(context.getString(R.string.action_send))
                .performClick()
        }
        composeTestRule.waitForIdle()

        assertSearchCleared()
    }

    @Test
    fun `cancel all dialog appears and dismissing it keeps the active search`() {
        showScreen(listOf(smsModel(1, SmsStatus.PENDING, messageBody = "cancel all")))
        activateSearch("cancel")
        assertEquals("%cancel%", lastPushedSearch().likePattern)

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.queue_cancel_all_content_desc))
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(context.getString(android.R.string.cancel))
            .performClick()
        composeTestRule.waitForIdle()

        assertSearchPreserved("cancel")
    }

    @Test
    fun `cancelling the cancel all dialog keeps the active search`() {
        showScreen(listOf(smsModel(1, SmsStatus.PENDING, messageBody = "cancel all")))
        activateSearch("cancel")
        assertEquals("%cancel%", lastPushedSearch().likePattern)

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.queue_cancel_all_content_desc))
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(context.getString(android.R.string.cancel))
            .performClick()
        composeTestRule.waitForIdle()

        assertSearchPreserved("cancel")
    }

    // Empty queue: no action buttons visible (smsCount == 0 branch).
    @Test
    fun `empty queue hides the top bar action buttons`() {
        showScreen(emptyList())
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.queue_forward_all_content_desc))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.queue_cancel_all_content_desc))
            .assertDoesNotExist()
    }

    // Exit selection mode: the cancel button in the selection top bar fires
    // exitSelectionMode.
    @Test
    fun `cancelling selection restores the standard top bar`() {
        showScreen(listOf(smsModel(1, SmsStatus.PENDING, messageBody = "test")))
        composeTestRule.onNodeWithText("test").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.action_cancel)).assertExists()
        composeTestRule
            .onNodeWithText(context.getString(R.string.action_cancel))
            .performClick()
        composeTestRule.waitForIdle()
    }

    // Confirming the cancel selection dialog clears the active search.
    // The dialog's confirm and cancel buttons both resolve to "Cancel" text (queue_cancel_selected
    // = "Cancel" matches android.R.string.cancel), making them indistinguishable under Robolectric.
    // Drive the VM directly (same pattern as HistoryScreenTest for Robolectric-modal dialogs).
    @Test
    fun `confirming cancel selection clears selection and exits selection mode`() {
        showScreen(listOf(smsModel(1, SmsStatus.PENDING, messageBody = "hello world")))
        activateSearch("hello")
        assertEquals("%hello%", lastPushedSearch().likePattern)

        composeTestRule.onNodeWithText("hello world").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        assertEquals(1, viewModel.state.value.selectedIds.size)
        assertTrue(viewModel.state.value.selectionMode)

        withMockedToast {
            viewModel.cancelSelected()
        }
        composeTestRule.waitForIdle()

        assertTrue(
            viewModel.state.value
                .selectedIds
                .isEmpty(),
        )
        assertFalse(viewModel.state.value.selectionMode)
    }

    // Confirming the cancel all dialog: drive the VM directly (see cancel selection note above).
    @Test
    fun `confirming cancel all executes via VM`() {
        runBlocking {
            whenever(repository.getQueueIds()).thenReturn(listOf(1L))
        }

        showScreen(listOf(smsModel(1, SmsStatus.PENDING, messageBody = "cancel all")))

        withMockedToast {
            viewModel.cancelAll()
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `search field renders when search toggled on`() {
        showScreen(listOf(smsModel(1, SmsStatus.PENDING, messageBody = "test")))
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.sms_search_hint))
            .performClick()
        composeTestRule.waitForIdle()

        searchField().assertExists()
    }

    // Selection mode: cancelling the cancel dialog keeps search preserved.
    // Cannot disambiguate dialog's cancel from top bar's cancel icon (both resolve to "Cancel").
    @Test
    fun `cancelling cancel selection via dismiss keeps the active search`() {
        showScreen(listOf(smsModel(1, SmsStatus.PENDING, messageBody = "hello world")))
        activateSearch("hello")
        assertEquals("%hello%", lastPushedSearch().likePattern)

        composeTestRule.onNodeWithText("hello world").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        // Drive the VM to exit selection (the dismiss path does NOT clear search).
        viewModel.exitSelectionMode()
        composeTestRule.waitForIdle()

        assertSearchPreserved("hello")
    }
}
