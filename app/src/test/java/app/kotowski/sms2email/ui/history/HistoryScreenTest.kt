package app.kotowski.sms2email.ui.history

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.PeriodPreset
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
import app.kotowski.sms2email.testing.SkipInReleaseRule
import app.kotowski.sms2email.testing.smsModel
import app.kotowski.sms2email.testing.stubContextGetString
import app.kotowski.sms2email.testing.withMockedToast
import app.kotowski.sms2email.util.DateUtil
import app.kotowski.sms2email.work.SmsActions
import app.kotowski.sms2email.work.SmsRetryScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The History screen's search state: an active query must survive the dismiss/cancel
// paths (the clear-history dialog, the import sheet) and be dropped on confirm or on a
// successful import (the scope the search filtered was rewritten).
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoryScreenTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Mock private lateinit var appContext: Context

    @Mock private lateinit var repository: SmsRepository

    @Mock private lateinit var smsImporter: SmsImporter

    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var contactResolver: ContactResolver

    @Mock private lateinit var simNameResolver: SimNameResolver

    @Mock private lateinit var simChangeObserver: SimChangeObserver

    @Mock private lateinit var contactChangeObserver: ContactChangeObserver

    @Mock private lateinit var permissionManager: PermissionManager

    @Mock private lateinit var retryScheduler: SmsRetryScheduler

    @Mock private lateinit var networkObserver: NetworkConnectivityObserver

    @Mock private lateinit var connectivityManager: ConnectivityManager

    // Standard (not Unconfined): a resume from the pipeline's real-IO hop must only run
    // when this test thread pumps the scheduler, never inline on the IO worker thread.
    private val testDispatcher = StandardTestDispatcher()

    // The mocked history window (the newest loadedLimit + 1 rows) and the unfiltered total.
    private val windowFlow = MutableStateFlow<List<SmsUiModel>>(emptyList())

    private val countFlow = MutableStateFlow(0)

    private lateinit var viewModel: HistoryViewModel

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
        whenever(repository.observeSms(eq(SmsListScope.HISTORY), any<SmsSearch>(), anyInt())).thenReturn(windowFlow)
        whenever(repository.observeCount(SmsListScope.HISTORY)).thenReturn(countFlow)
        whenever(appContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)
        whenever(connectivityManager.activeNetwork).thenReturn(null)
        whenever(networkObserver.isConnectedCurrently()).thenReturn(true)
        whenever(networkObserver.networkStatus).thenReturn(flowOf(NetworkStatus.Available))
        whenever(permissionManager.isGranted(any())).thenReturn(true)
        // anyOrNull (not any) for the since timestamp: with the suspend-mock setup any()
        // does not match the null (all-time) cutoff, so the stub silently would not apply.
        runBlocking {
            whenever(smsImporter.importMessages(anyOrNull(), any())).thenReturn(SmsImporter.ImportResult(importedCount = 2))
            whenever(repository.deleteHistorySms()).thenReturn(0)
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
                actions = SmsActions(repository, appContext, prefs, retryScheduler, simNameResolver),
                appContext = appContext,
                networkObserver = networkObserver,
            )
    }

    @After
    fun tearDown() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        // Park Main on a dispatcher nobody pumps instead of resetMain: a stray IO
        // continuation from the VM pipeline can still resume after the test, and
        // resetMain would send it to the unmocked real Main and crash a worker thread.
        Dispatchers.setMain(StandardTestDispatcher())
    }

    private fun showScreen(rows: List<SmsUiModel>) {
        windowFlow.value = rows
        countFlow.value = rows.size
        composeTestRule.setContent {
            HistoryScreen(
                onNavigateBack = { },
                onSmsClicked = { },
                viewModel = viewModel,
            )
        }
        // The first window lands after the search debounce (virtual time) plus a real-IO
        // build/enrich hop, so settle on the test scheduler bounded in real time (the
        // pattern of the VM tests).
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.state.value.list.paged.isLoading && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceTimeBy(AppConfig.SMS_SEARCH_DEBOUNCE_MS)
            testDispatcher.scheduler.runCurrent()
            Thread.sleep(5)
        }
        check(!viewModel.state.value.list.paged.isLoading)
        composeTestRule.waitForIdle()
    }

    // The search field is the only settable-text node on the screen.
    private fun searchField() = composeTestRule.onNode(hasSetTextAction())

    // The query reaches the repository only after the debounce, so wait for the re-observe.
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
            verify(repository, atLeastOnce()).observeSms(eq(SmsListScope.HISTORY), captor.capture(), anyInt())
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
        verify(repository, atLeastOnce()).observeSms(eq(SmsListScope.HISTORY), captor.capture(), anyInt())
        return captor.allValues.last()
    }

    private fun assertSearchPreserved(query: String) {
        settleSearchPush()
        assertEquals("%$query%", lastPushedSearch().likePattern)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(query).assertExists()
    }

    private fun assertSearchCleared() {
        settleSearchPush()
        assertTrue(lastPushedSearch().noSearch)
        composeTestRule.waitForIdle()
        searchField().assertDoesNotExist()
    }

    @Test
    fun `the clear history action is disabled on an empty history`() {
        showScreen(emptyList())
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.history_clear_content_desc))
            .assertIsNotEnabled()
    }

    @Test
    fun `the clear history action is enabled once a row exists`() {
        showScreen(listOf(smsModel(1, SmsStatus.IMPORTED)))
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.history_clear_content_desc))
            .assertIsEnabled()
    }

    @Test
    fun `cancelling the clear history dialog keeps the active search`() {
        showScreen(listOf(smsModel(1, SmsStatus.IMPORTED, messageBody = "hello world")))
        activateSearch("hello")
        assertEquals("%hello%", lastPushedSearch().likePattern)

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.history_clear_content_desc))
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        composeTestRule.waitForIdle()

        assertSearchPreserved("hello")
    }

    @Test
    fun `confirming the clear history dialog clears the active search`() {
        showScreen(listOf(smsModel(1, SmsStatus.IMPORTED, messageBody = "hello world")))
        activateSearch("hello")
        assertEquals("%hello%", lastPushedSearch().likePattern)

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.history_clear_content_desc))
            .performClick()
        composeTestRule.waitForIdle()

        withMockedToast {
            composeTestRule
                .onNodeWithText(context.getString(R.string.history_clear_confirm_btn))
                .performClick()
        }
        composeTestRule.waitForIdle()

        assertSearchCleared()
    }

    @Test
    fun `dismissing the import sheet keeps the active search`() {
        showScreen(listOf(smsModel(1, SmsStatus.IMPORTED, messageBody = "hello world")))
        activateSearch("hello")
        assertEquals("%hello%", lastPushedSearch().likePattern)

        openImportSheet()
        // The sheet's cancel button is unclickable under Robolectric; dismiss through the
        // same flow call it makes.
        viewModel.importFlow.dismissImportDialog()
        composeTestRule.waitForIdle()

        assertSearchPreserved("hello")
    }

    @Test
    fun `a completed import clears the active search`() {
        showScreen(listOf(smsModel(1, SmsStatus.IMPORTED, messageBody = "hello world")))
        activateSearch("hello")
        assertEquals("%hello%", lastPushedSearch().likePattern)

        openImportSheet()
        // The sheet's import button is unclickable under Robolectric; confirm through the
        // same flow call it makes. The completion crosses three pumps (the coroutine
        // scheduler, the Robolectric main looper — the dialog window attaches there — and
        // the compose frame clock): pump all three in wall time until the result is
        // published.
        viewModel.importFlow.confirmImport()
        settleImportFlow { it.importResult != null }
        composeTestRule
            .onNodeWithText(context.getString(R.string.history_import_complete))
            .assertExists()

        // The search-clear fires from the effect on the published result: advance past
        // the debounce, then let the push reach the repository.
        testDispatcher.scheduler.advanceTimeBy(AppConfig.SMS_SEARCH_DEBOUNCE_MS + 50)
        testDispatcher.scheduler.runCurrent()
        assertSearchCleared()
    }

    // Selection mode: long-press enters the mode, the multi-select bar replaces the
    // standard top bar, and the per-row click toggles selection instead of navigating.
    @Test
    fun `long press enters selection mode and shows the multi-select bar`() {
        showScreen(listOf(smsModel(1, SmsStatus.IMPORTED, messageBody = "select me")))
        composeTestRule
            .onNodeWithText("select me")
            .performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.action_cancel)).assertExists()
    }

    @Test
    fun `exit selection mode restores the standard top bar`() {
        showScreen(listOf(smsModel(1, SmsStatus.IMPORTED, messageBody = "select me")))
        composeTestRule
            .onNodeWithText("select me")
            .performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        composeTestRule.waitForIdle()
        // Standard top bar title is back (count in title).
        composeTestRule.onNodeWithText(context.getString(R.string.history_title_count, 1)).assertExists()
    }

    // Auto-cleanup banner: shown when autoCleanupRetentionDays > 0, with a button
    // to run cleanup immediately. The banner stays even with an empty history because
    // it explains the (auto-cleared) empty list.
    @Test
    fun `auto cleanup banner renders when retention is configured`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 30))
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
                actions = SmsActions(repository, appContext, prefs, retryScheduler, simNameResolver),
                appContext = appContext,
                networkObserver = networkObserver,
            )
        showScreen(emptyList())
        composeTestRule
            .onNodeWithText(context.getString(R.string.history_auto_clear_on))
            .assertExists()
    }

    @Test
    fun `empty history shows import button in the centered message`() {
        showScreen(emptyList())
        composeTestRule
            .onNodeWithText(context.getString(R.string.history_import_btn))
            .assertExists()
    }

    // The import flow's confirm button lives in the modal bottom sheet, whose
    // expand animation does not run under Robolectric (the sheet content composes but
    // lays out at zero height, so its buttons are unclickable). The screen's other
    // dialogs are plain Dialog windows and stay clickable; the sheet's VM entry point
    // (confirmImport) is the same call its Import button makes, so the flow is driven
    // there directly (the pattern of HistoryViewModelTest).
    private fun openImportSheet() {
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.history_import_content_desc))
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.import_dialog_title)).assertExists()
    }

    // Pump the scheduler (the import coroutine's Main dispatcher), the main looper
    // (dialog windows attach there) and the frame clock in wall time until the import
    // flow publishes the expected state.
    private fun settleImportFlow(expected: (ImportUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!expected(viewModel.importFlow.state.value) && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.runCurrent()
            ShadowLooper.idleMainLooper()
            composeTestRule.waitForIdle()
            Thread.sleep(10)
        }
        check(expected(viewModel.importFlow.state.value)) {
            "the import flow never reached the expected state: ${viewModel.importFlow.state.value}"
        }
    }

    // Freezes the import right after the first progress callback: the mock answers
    // COROUTINE_SUSPENDED (the suspend contract), so the import coroutine suspends
    // while isImporting/importProgress are live. Without the suspension the mock would
    // resume the continuation with the result in the same scheduler drain and the
    // progress dialog could never compose. The frozen import is abandoned at the end of
    // the test: a suspended coroutine holds no scheduled task and cannot poison the
    // next test.
    private fun stubFrozenImport(progress: SmsImporter.ImportProgress) {
        runBlocking {
            whenever(smsImporter.importMessages(any(), any())).thenAnswer { inv ->
                inv.getArgument<(SmsImporter.ImportProgress) -> Unit>(1)(progress)
                COROUTINE_SUSPENDED
            }
        }
    }

    // Import progress dialog: while isImporting && importProgress != null, an alert
    // dialog shows the progress. The progress.total > 0 branch shows a linear progress
    // bar; total <= 0 shows a circular spinner.
    @Test
    fun `import progress dialog shows the linear progress text while importing`() {
        val progress = SmsImporter.ImportProgress(imported = 3, current = 3, total = 10)
        stubFrozenImport(progress)
        showScreen(emptyList())
        viewModel.importFlow.confirmImport()
        testDispatcher.scheduler.runCurrent()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.history_importing)).assertExists()
        val expected =
            context.resources.getQuantityString(
                R.plurals.history_importing_progress,
                progress.total,
                progress.current,
                progress.total,
            )
        composeTestRule.onNodeWithText(expected).assertExists()
        // While the import is in flight the empty-state import button is held back.
        composeTestRule
            .onNodeWithText(context.getString(R.string.history_import_btn))
            .assertIsNotEnabled()
    }

    @Test
    fun `import progress dialog shows a spinner when the total is unknown`() {
        stubFrozenImport(SmsImporter.ImportProgress(current = 3, total = 0))
        showScreen(listOf(smsModel(1, SmsStatus.IMPORTED)))
        viewModel.importFlow.confirmImport()
        testDispatcher.scheduler.runCurrent()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.history_importing)).assertExists()
        // While the import is in flight the toolbar import and clear actions are held back.
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.history_import_content_desc))
            .assertIsNotEnabled()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.history_clear_content_desc))
            .assertIsNotEnabled()
    }

    // The completed import publishes a single-OK result dialog (no cancel): imported,
    // skipped and, when nonzero, the error count.
    @Test
    fun `a completed import shows the result dialog with imported skipped and error counts`() {
        runBlocking {
            whenever(smsImporter.importMessages(any(), any()))
                .thenReturn(SmsImporter.ImportResult(importedCount = 7, skippedCount = 2, errorCount = 3))
        }
        showScreen(listOf(smsModel(1, SmsStatus.IMPORTED)))
        openImportSheet()
        viewModel.importFlow.confirmImport()
        settleImportFlow { it.importResult != null }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.history_import_complete)).assertExists()
        // The dialog detail is one multiline text; match the lines as substrings.
        composeTestRule.onNodeWithText(context.getString(R.string.history_imported, 7), substring = true).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.history_skipped, 2), substring = true).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.history_errors, 3), substring = true).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.action_done)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.history_import_complete)).assertDoesNotExist()
    }

    // The import coroutine checks the SMS permission before importing; without it the
    // flow ends in the single-OK error dialog instead of the result dialog.
    @Test
    fun `an import without the SMS permission shows the error dialog`() {
        // The error text is resolved through the (mocked) app context.
        stubContextGetString(appContext, context)
        whenever(permissionManager.isGranted(android.Manifest.permission.READ_SMS)).thenReturn(false)
        showScreen(listOf(smsModel(1, SmsStatus.IMPORTED)))
        openImportSheet()
        viewModel.importFlow.confirmImport()
        settleImportFlow { it.importError != null }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.history_import_failed)).assertExists()
        composeTestRule
            .onNodeWithText(context.getString(R.string.history_import_no_sms_permission))
            .assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.action_done)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.history_import_failed)).assertDoesNotExist()
    }

    // Auto-clear retention on: an import range that outlives the retention (all time)
    // holds the import in a yes/no dialog over the sheet. Cancel keeps the sheet open
    // so the range can be adjusted; confirm releases the import.
    @Test
    fun `a retention conflict shows the confirm dialog and cancel keeps the sheet open`() {
        stubContextGetString(appContext, context)
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 30))
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
                actions = SmsActions(repository, appContext, prefs, retryScheduler, simNameResolver),
                appContext = appContext,
                networkObserver = networkObserver,
            )
        showScreen(listOf(smsModel(1, SmsStatus.IMPORTED)))
        openImportSheet()
        viewModel.importFlow.selectTimeFrame(ImportTimeFrame.ALL_TIME)
        composeTestRule.waitForIdle()
        viewModel.importFlow.confirmImport()
        composeTestRule.waitForIdle()
        // The confirm dialog is up over the still-open sheet.
        composeTestRule.onNodeWithText(context.getString(R.string.import_retention_title)).assertExists()
        val label = context.getString(PeriodPreset.labelResFor(30))
        composeTestRule
            .onNodeWithText(context.getString(R.string.import_retention_warning, label))
            .assertExists()
        // The sheet's Import button (below) shares the confirm button's text; the dialog
        // window composes after the sheet, so its button is the last match.
        composeTestRule
            .onAllNodesWithText(context.getString(android.R.string.cancel))
            .onLast()
            .performClick()
        composeTestRule.waitForIdle()
        // Cancel: the dialog is gone, the sheet (and its pending import) survives.
        composeTestRule.onNodeWithText(context.getString(R.string.import_retention_title)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.import_dialog_title)).assertExists()
        // Confirming the same conflict releases the held import into the result dialog.
        viewModel.importFlow.confirmImport()
        composeTestRule.waitForIdle()
        composeTestRule
            .onAllNodesWithText(context.getString(R.string.import_confirm))
            .onLast()
            .performClick()
        settleImportFlow { it.importResult != null }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.history_import_complete)).assertExists()
    }

    // Selection mode: the second top-bar action opens the delete dialog; cancelling it
    // keeps the selection, confirming deletes and exits the mode.
    @Test
    fun `the delete dialog confirms the deletion and exits selection mode`() {
        runBlocking {
            whenever(repository.deleteSmsByIds(any())).thenReturn(1)
        }
        showScreen(
            listOf(
                smsModel(1, SmsStatus.IMPORTED, messageBody = "row one"),
                smsModel(2, SmsStatus.IMPORTED, messageBody = "row two"),
            ),
        )
        composeTestRule
            .onNodeWithText("row one")
            .performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.selection_count, 1)).assertExists()
        // The delete action is the second icon; it is enabled with a non-empty selection.
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.history_delete_selected))
            .assertIsEnabled()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.history_delete_selected))
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.history_delete_selected_title)).assertExists()
        // Cancel: the dialog closes, the selection survives. The selection top bar carries
        // its own Cancel; the dialog window composes after it, so its button is last.
        composeTestRule
            .onAllNodesWithText(context.getString(android.R.string.cancel))
            .onLast()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.history_delete_selected_title)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.selection_count, 1)).assertExists()
        // Confirm: the rows are deleted and the standard top bar comes back.
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.history_delete_selected))
            .performClick()
        composeTestRule.waitForIdle()
        withMockedToast {
            composeTestRule.onNodeWithText(context.getString(R.string.action_delete)).performClick()
        }
        testDispatcher.scheduler.runCurrent()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.history_title_count, 2)).assertExists()
        runBlocking {
            verify(repository).deleteSmsByIds(listOf(1L))
        }
    }

    // Selection mode: the first top-bar action opens the forward dialog; confirming
    // forwards the selection and exits the mode.
    @Test
    fun `the forward dialog forwards the selection and exits selection mode`() {
        runBlocking {
            whenever(repository.getSmsByIds(any())).thenReturn(emptyList())
        }
        showScreen(listOf(smsModel(1, SmsStatus.IMPORTED, messageBody = "row one")))
        composeTestRule
            .onNodeWithText("row one")
            .performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.action_send))
            .assertIsEnabled()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.action_send))
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.forward_messages_title)).assertExists()
        withMockedToast {
            composeTestRule.onNodeWithText(context.getString(R.string.action_send)).performClick()
        }
        testDispatcher.scheduler.runCurrent()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.history_title_count, 1)).assertExists()
        runBlocking {
            verify(repository).getSmsByIds(listOf(1L))
        }
    }

    // The auto-cleanup banner's button runs a cleanup immediately and reports the
    // deleted count in a single-OK dialog.
    @Test
    fun `running the cleanup now shows the cleanup result dialog`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 30))
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
                actions = SmsActions(repository, appContext, prefs, retryScheduler, simNameResolver),
                appContext = appContext,
                networkObserver = networkObserver,
            )
        runBlocking {
            whenever(repository.deleteExpired(30L)).thenReturn(4)
        }
        showScreen(emptyList())
        composeTestRule.onNodeWithTag("run_cleanup_now").performClick()
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.state.value.cleanupDeletedCount == null && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.runCurrent()
            composeTestRule.waitForIdle()
            Thread.sleep(10)
        }
        assertEquals(4, viewModel.state.value.cleanupDeletedCount)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.history_cleanup_complete)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.history_cleanup_deleted, 4)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.action_done)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.history_cleanup_complete)).assertDoesNotExist()
    }

    // The import sheet's rows compose their selected state from the flow; the
    // since-date row shows the picked date once one is set. The row clicks themselves
    // are unclickable under Robolectric (see openImportSheet), so the selection is
    // driven on the flow the same way the row callbacks would.
    @Test
    fun `the import sheet rows reflect the selected time frame and the custom since date`() {
        showScreen(listOf(smsModel(1, SmsStatus.IMPORTED)))
        openImportSheet()
        // The default frame is selected from the start.
        composeTestRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertCountEquals(1)
        for (timeFrame in ImportTimeFrame.entries.filter { it != ImportTimeFrame.SINCE_DATE }) {
            viewModel.importFlow.selectTimeFrame(timeFrame)
            composeTestRule.waitForIdle()
            composeTestRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertCountEquals(1)
        }
        // A picked since date renders as the trailing text of the since-date row.
        val customDate = 1_700_000_000_000L
        viewModel.importFlow.setCustomImportDate(customDate)
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(DateUtil.formatDate(context, customDate))
            .assertExists()
        composeTestRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertCountEquals(1)
        viewModel.importFlow.dismissImportDialog()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.import_dialog_title)).assertDoesNotExist()
    }

    // The empty state's import button opens the sheet, the same way the toolbar icon does.
    @Test
    fun `the empty state import button opens the import sheet`() {
        showScreen(emptyList())
        composeTestRule
            .onNodeWithText(context.getString(R.string.history_import_btn))
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.import_dialog_title)).assertExists()
    }
}
