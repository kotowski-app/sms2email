package app.kotowski.sms2email.ui.history

import android.content.Context
import android.widget.Toast
import app.kotowski.sms2email.PeriodPreset
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.service.SmsImporter
import app.kotowski.sms2email.system.PermissionManager
import app.kotowski.sms2email.testing.withMockedToast
import app.kotowski.sms2email.util.DateUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class ImportFlowControllerTest {
    @Mock private lateinit var smsImporter: SmsImporter

    @Mock private lateinit var permissionManager: PermissionManager

    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var context: Context

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        whenever(prefs.load()).thenReturn(AppSettings(useContactResolution = false))
        whenever(permissionManager.isGranted(any())).thenReturn(true)
        whenever(context.getString(any<Int>())).thenReturn("Unknown SIM")
        // the importMessages stub needs a coroutine context (suspend function)
        kotlinx.coroutines.runBlocking {
            whenever(smsImporter.importMessages(any(), any())).thenReturn(SmsImporter.ImportResult())
        }
    }

    // The background scope runs on the unconfined test dispatcher, so scope.launch in
    // importSms runs immediately; no Main is involved (the controller never touches
    // viewModelScope/Dispatchers.Main).
    private fun withController(block: (ImportFlowController) -> Unit) {
        runTest(UnconfinedTestDispatcher()) {
            block(ImportFlowController(backgroundScope, smsImporter, permissionManager, prefs, context))
        }
    }

    @Test
    fun `initial state is empty`() =
        withController { controller ->
            val s = controller.state.value
            assertFalse(s.isImporting)
            assertNull(s.importProgress)
            assertNull(s.importResult)
            assertNull(s.importError)
            assertFalse(s.showImportDialog)
            assertEquals(ImportTimeFrame.SEVEN_DAYS, s.selectedTimeFrame)
            assertNull(s.customImportDate)
            assertFalse(s.showRetentionConfirm)
            assertNull(s.retentionConfirmLabel)
            assertNull(s.pendingImportSince)
        }

    @Test
    fun `show and dismiss import dialog`() =
        withController { controller ->
            controller.showImportDialog()
            assertTrue(controller.state.value.showImportDialog)

            controller.dismissImportDialog()
            assertFalse(controller.state.value.showImportDialog)
        }

    @Test
    fun `selectTimeFrame updates selection`() =
        withController { controller ->
            controller.selectTimeFrame(ImportTimeFrame.TWENTY_FOUR_HOURS)
            assertEquals(ImportTimeFrame.TWENTY_FOUR_HOURS, controller.state.value.selectedTimeFrame)
        }

    @Test
    fun `setCustomImportDate sets date and selects SINCE_DATE`() =
        withController { controller ->
            controller.setCustomImportDate(1700000000000L)
            val s = controller.state.value
            assertEquals(1700000000000L, s.customImportDate)
            assertEquals(ImportTimeFrame.SINCE_DATE, s.selectedTimeFrame)
        }

    @Test
    fun `confirmImport dismisses dialog and keeps timeframe`() =
        withController { controller ->
            controller.showImportDialog()
            controller.selectTimeFrame(ImportTimeFrame.TWENTY_FOUR_HOURS)
            controller.confirmImport()

            val s = controller.state.value
            assertFalse(s.showImportDialog)
            assertEquals(ImportTimeFrame.TWENTY_FOUR_HOURS, s.selectedTimeFrame)
        }

    @Test
    fun `confirmImport with the 24h frame imports a rolling 24h window, not the midnight anchor`() {
        withController { controller ->
            controller.showImportDialog()
            controller.selectTimeFrame(ImportTimeFrame.TWENTY_FOUR_HOURS)
            val before = System.currentTimeMillis()
            controller.confirmImport()
            val after = System.currentTimeMillis()

            val captor = argumentCaptor<Long>()
            kotlinx.coroutines.runBlocking {
                verify(smsImporter).importMessages(captor.capture(), any())
            }
            // Rolling: now - 24h (yesterday at this hour of day), not local midnight of
            // yesterday, which the midnight-anchored day frames use.
            assertTrue(captor.firstValue in (before - DateUtil.MS_PER_DAY)..(after - DateUtil.MS_PER_DAY))
        }
    }

    @Test
    fun `confirmImport with SINCE_DATE and no date keeps dialog open and does not import`() =
        withController { controller ->
            controller.showImportDialog()
            controller.selectTimeFrame(ImportTimeFrame.SINCE_DATE)
            withMockedToast { toast ->
                controller.confirmImport()
                toast.verify { Toast.makeText(eq(context), eq(R.string.import_pick_date_first), eq(Toast.LENGTH_SHORT)) }
            }

            assertTrue(controller.state.value.showImportDialog)
            kotlinx.coroutines.runBlocking {
                verify(smsImporter, never()).importMessages(any(), any())
            }
        }

    @Test
    fun `confirmImport with SINCE_DATE and a future date keeps dialog open and does not import`() =
        withController { controller ->
            controller.showImportDialog()
            controller.setCustomImportDate(System.currentTimeMillis() + 2L * DateUtil.MS_PER_DAY)
            withMockedToast { toast ->
                controller.confirmImport()
                toast.verify { Toast.makeText(eq(context), eq(R.string.import_future_date), eq(Toast.LENGTH_SHORT)) }
            }

            assertTrue(controller.state.value.showImportDialog)
            kotlinx.coroutines.runBlocking {
                verify(smsImporter, never()).importMessages(any(), any())
            }
        }

    @Test
    fun `confirmImport with SINCE_DATE and date imports from that date`() =
        withController { controller ->
            controller.showImportDialog()
            controller.setCustomImportDate(1700000000000L)
            controller.confirmImport()

            assertFalse(controller.state.value.showImportDialog)
            kotlinx.coroutines.runBlocking {
                verify(smsImporter).importMessages(eq(1700000000000L), any())
            }
        }

    @Test
    fun `confirmImport with SINCE_DATE and today's date imports`() =
        withController { controller ->
            controller.showImportDialog()
            val today = DateUtil.localMidnightOfUtcCalendarDateMillis(DateUtil.utcMidnightOfTodayMillis())
            controller.setCustomImportDate(today)
            controller.confirmImport()

            assertFalse(controller.state.value.showImportDialog)
            kotlinx.coroutines.runBlocking {
                verify(smsImporter).importMessages(eq(today), any())
            }
        }

    @Test
    fun `confirmImport shows the retention confirm dialog instead of importing`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 30))
        whenever(context.getString(any<Int>())).thenReturn("1 month")
        withController { controller ->
            controller.showImportDialog()
            controller.selectTimeFrame(ImportTimeFrame.ALL_TIME)
            controller.confirmImport()

            val s = controller.state.value
            assertTrue(s.showRetentionConfirm)
            assertEquals("1 month", s.retentionConfirmLabel)
            assertTrue(s.showImportDialog)
            kotlinx.coroutines.runBlocking {
                verify(smsImporter, never()).importMessages(any(), any())
            }
        }
    }

    @Test
    fun `confirmRetentionImport imports the pending range and closes the dialogs`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 30))
        withController { controller ->
            controller.showImportDialog()
            controller.selectTimeFrame(ImportTimeFrame.ALL_TIME)
            controller.confirmImport()
            controller.confirmRetentionImport()

            val s = controller.state.value
            assertFalse(s.showRetentionConfirm)
            assertFalse(s.showImportDialog)
            assertNull(s.retentionConfirmLabel)
            assertNull(s.pendingImportSince)
            kotlinx.coroutines.runBlocking {
                verify(smsImporter).importMessages(isNull(), any())
            }
        }
    }

    @Test
    fun `confirmRetentionImport a second time is a no-op`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 30))
        withController { controller ->
            controller.showImportDialog()
            controller.selectTimeFrame(ImportTimeFrame.ALL_TIME)
            controller.confirmImport()
            controller.confirmRetentionImport()
            // A second tap that lands before the dialog leaves composition.
            controller.confirmRetentionImport()

            val s = controller.state.value
            assertFalse(s.showRetentionConfirm)
            assertFalse(s.showImportDialog)
            assertNull(s.pendingImportSince)
            kotlinx.coroutines.runBlocking {
                verify(smsImporter, times(1)).importMessages(isNull(), any())
            }
        }
    }

    @Test
    fun `confirmRetentionImport a second time does not turn the ranged import into an all-time one`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 30))
        withController { controller ->
            controller.showImportDialog()
            controller.setCustomImportDate(System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000)
            controller.confirmImport()
            controller.confirmRetentionImport()
            controller.confirmRetentionImport()

            kotlinx.coroutines.runBlocking {
                verify(smsImporter, times(1)).importMessages(any(), any())
                verify(smsImporter, never()).importMessages(isNull(), any())
            }
        }
    }

    @Test
    fun `cancelRetentionImport keeps the sheet open and does not import`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 30))
        withController { controller ->
            controller.showImportDialog()
            controller.selectTimeFrame(ImportTimeFrame.ALL_TIME)
            controller.confirmImport()
            controller.cancelRetentionImport()

            val s = controller.state.value
            assertFalse(s.showRetentionConfirm)
            assertTrue(s.showImportDialog)
            assertNull(s.pendingImportSince)
            kotlinx.coroutines.runBlocking {
                verify(smsImporter, never()).importMessages(any(), any())
            }
        }
    }

    @Test
    fun `confirmImport skips the dialog when the range fits inside the retention window`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 30))
        withController { controller ->
            controller.showImportDialog()
            controller.selectTimeFrame(ImportTimeFrame.TWENTY_FOUR_HOURS)
            controller.confirmImport()

            val s = controller.state.value
            assertFalse(s.showRetentionConfirm)
            assertFalse(s.showImportDialog)
            kotlinx.coroutines.runBlocking {
                verify(smsImporter).importMessages(any(), any())
            }
        }
    }

    @Test
    fun `confirmImport skips the dialog when the import range equals the retention window`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 30))
        withController { controller ->
            controller.showImportDialog()
            // The 30-day import starts exactly at the 30-day retention cutoff (both are
            // local midnights) and the sweep deletes only rows before that cutoff, so
            // the aligned range imports without a warning.
            controller.selectTimeFrame(ImportTimeFrame.THIRTY_DAYS)
            controller.confirmImport()

            val s = controller.state.value
            assertFalse(s.showRetentionConfirm)
            assertFalse(s.showImportDialog)
            kotlinx.coroutines.runBlocking {
                verify(smsImporter).importMessages(any(), any())
            }
        }
    }

    @Test
    fun `confirmImport shows the retention confirm dialog when the import range exceeds the retention window`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 30))
        whenever(context.getString(any<Int>())).thenReturn("1 month")
        withController { controller ->
            controller.showImportDialog()
            controller.selectTimeFrame(ImportTimeFrame.NINETY_DAYS)
            controller.confirmImport()

            val s = controller.state.value
            assertTrue(s.showRetentionConfirm)
            assertEquals("1 month", s.retentionConfirmLabel)
            assertTrue(s.showImportDialog)
            kotlinx.coroutines.runBlocking {
                verify(smsImporter, never()).importMessages(any(), any())
            }
        }
    }

    @Test
    fun `confirmImport skips the dialog for a since date inside the retention window`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 30))
        withController { controller ->
            controller.showImportDialog()
            controller.setCustomImportDate(System.currentTimeMillis() - 10L * 24 * 60 * 60 * 1000)
            controller.confirmImport()

            val s = controller.state.value
            assertFalse(s.showRetentionConfirm)
            assertFalse(s.showImportDialog)
        }
    }

    @Test
    fun `confirmImport shows the retention confirm dialog for a since date older than the retention window`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 30))
        whenever(context.getString(any<Int>())).thenReturn("1 month")
        withController { controller ->
            controller.showImportDialog()
            controller.setCustomImportDate(System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000)
            controller.confirmImport()

            val s = controller.state.value
            assertTrue(s.showRetentionConfirm)
            assertTrue(s.showImportDialog)
        }
    }

    @Test
    fun `confirmImport skips the dialog for a since date at the retention boundary`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 30))
        withController { controller ->
            controller.showImportDialog()
            // Local midnight 30 days ago — the same instant the 30-day retention cutoff
            // uses: the import starts exactly at the cutoff and the sweep deletes only
            // rows before it, so the aligned range imports without a warning.
            val pickerDate = DateUtil.utcMidnightOfTodayMillis() - 30L * DateUtil.MS_PER_DAY
            val since = DateUtil.localMidnightOfUtcCalendarDateMillis(pickerDate)
            controller.setCustomImportDate(since)
            controller.confirmImport()

            val s = controller.state.value
            assertFalse(s.showRetentionConfirm)
            assertFalse(s.showImportDialog)
            kotlinx.coroutines.runBlocking {
                verify(smsImporter).importMessages(eq(since), any())
            }
        }
    }

    @Test
    fun `confirmImport skips the dialog for a since date just inside the retention window`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 30))
        withController { controller ->
            controller.showImportDialog()
            // One hour after the retention cutoff's midnight (30 days ago), so no
            // imported row is at or before the cutoff and nothing is deleted.
            val since = System.currentTimeMillis() - (30L * DateUtil.MS_PER_DAY - 3_600_000L)
            controller.setCustomImportDate(since)
            controller.confirmImport()

            val s = controller.state.value
            assertFalse(s.showRetentionConfirm)
            assertFalse(s.showImportDialog)
        }
    }

    @Test
    fun `confirmImport skips the dialog when retention is off`() =
        withController { controller ->
            controller.showImportDialog()
            controller.selectTimeFrame(ImportTimeFrame.ALL_TIME)
            controller.confirmImport()

            val s = controller.state.value
            assertFalse(s.showRetentionConfirm)
            assertFalse(s.showImportDialog)
            kotlinx.coroutines.runBlocking {
                verify(smsImporter).importMessages(isNull(), any())
            }
        }

    @Test
    fun `clearImportResult clears result and error`() =
        withController { controller ->
            controller.clearImportResult()
            assertNull(controller.state.value.importResult)
            assertNull(controller.state.value.importError)
        }

    @Test
    fun `importSms without permission sets error`() {
        whenever(permissionManager.isGranted(android.Manifest.permission.READ_SMS)).thenReturn(false)
        whenever(context.getString(any<Int>())).thenReturn("SMS permission required")
        withController { controller ->
            controller.importSms()

            val s = controller.state.value
            assertEquals("SMS permission required", s.importError)
            assertFalse(s.isImporting)
            assertNull(s.importResult)
        }
    }

    @Test
    fun `importSms surfaces importer progress and clears it when the import finishes`() {
        withQueuedScopeController { controller ->
            val mid = SmsImporter.ImportProgress(imported = 5, current = 5, total = 10)
            var observed: SmsImporter.ImportProgress? = null
            kotlinx.coroutines.runBlocking {
                whenever(smsImporter.importMessages(isNull(), any())).thenAnswer {
                    it.getArgument<(SmsImporter.ImportProgress) -> Unit>(1)(mid)
                    observed = controller.state.value.importProgress
                    SmsImporter.ImportResult(importedCount = 10)
                }
            }
            controller.importSms()
            runCurrent()

            assertEquals(mid, observed)

            val s = controller.state.value
            assertFalse(s.isImporting)
            assertNull(s.importProgress)
            assertNotNull(s.importResult)
        }
    }

    // These two use a queued (non-unconfined) scope, like production's viewModelScope:
    // the first import's coroutine body is still pending when the second call arrives,
    // so the guard must decide from state alone. The block drives the scheduler itself
    // (runCurrent) once the in-flight calls under test are done.
    private fun withQueuedScopeController(block: kotlinx.coroutines.test.TestScope.(ImportFlowController) -> Unit) {
        runTest {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
            block(ImportFlowController(scope, smsImporter, permissionManager, prefs, context))
        }
    }

    @Test
    fun `importSms while an import is in flight is a no-op`() {
        withQueuedScopeController { controller ->
            controller.importSms(1700000000000L)
            assertTrue(controller.state.value.isImporting)
            controller.importSms()
            controller.importSms(1700000000001L)
            runCurrent()

            val s = controller.state.value
            assertFalse(s.isImporting)
            assertNotNull(s.importResult)
            kotlinx.coroutines.runBlocking {
                verify(smsImporter, times(1)).importMessages(any(), any())
            }
        }
    }

    @Test
    fun `confirmImport twice launches the import only once`() {
        withQueuedScopeController { controller ->
            controller.showImportDialog()
            controller.selectTimeFrame(ImportTimeFrame.ALL_TIME)
            controller.confirmImport()
            // A second tap that lands before the sheet leaves composition.
            controller.confirmImport()
            runCurrent()

            assertFalse(controller.state.value.showImportDialog)
            kotlinx.coroutines.runBlocking {
                verify(smsImporter, times(1)).importMessages(isNull(), any())
            }
        }
    }

    @Test
    fun `importSms with timestamp closes dialog`() =
        withController { controller ->
            controller.importSms(sinceTimestamp = 1700000000000L)
            assertFalse(controller.state.value.showImportDialog)
        }

    @Test
    fun `ImportTimeFrame has six entries with SEVEN_DAYS as default`() {
        assertEquals(6, ImportTimeFrame.entries.size)
        assertEquals(ImportTimeFrame.SEVEN_DAYS, ImportTimeFrame.Default)
        assertTrue(ImportTimeFrame.entries.all { it.resId > 0 })
        assertEquals(R.string.import_timeframe_24h, ImportTimeFrame.TWENTY_FOUR_HOURS.resId)
        assertEquals(R.string.import_timeframe_7d, ImportTimeFrame.SEVEN_DAYS.resId)
        assertEquals(R.string.import_timeframe_30d, ImportTimeFrame.THIRTY_DAYS.resId)
        assertEquals(R.string.import_timeframe_90d, ImportTimeFrame.NINETY_DAYS.resId)
        // 24 hours and 7 days are import-only; the 30- and 90-day rows take their day
        // counts from the shared PeriodPreset.
        assertEquals(1, ImportTimeFrame.TWENTY_FOUR_HOURS.nominalDays)
        assertEquals(7, ImportTimeFrame.SEVEN_DAYS.nominalDays)
        assertEquals(PeriodPreset.THIRTY_DAYS.days, ImportTimeFrame.THIRTY_DAYS.nominalDays)
        assertEquals(PeriodPreset.NINETY_DAYS.days, ImportTimeFrame.NINETY_DAYS.nominalDays)
        assertNull(ImportTimeFrame.ALL_TIME.nominalDays)
        assertNull(ImportTimeFrame.SINCE_DATE.nominalDays)
    }

    // Day-based timeframes (7d, 30d, 90d) use the else branch in computeSinceTimestamp:
    // nominalDays?.let { DateUtil.daysAgoCutoff(...) }. This is distinct from 24h
    // (rolling window) and ALL_TIME/SINCE_DATE (null since).
    @Test
    fun `confirmImport with seven days uses the midnight-anchored cutoff`() =
        withController { controller ->
            controller.showImportDialog()
            controller.selectTimeFrame(ImportTimeFrame.SEVEN_DAYS)
            val before = System.currentTimeMillis()
            controller.confirmImport()
            val after = System.currentTimeMillis()

            val captor = argumentCaptor<Long>()
            kotlinx.coroutines.runBlocking {
                verify(smsImporter).importMessages(captor.capture(), any())
            }
            // SEVEN_DAYS uses daysAgoCutoff: local midnight 7 days ago.
            val sevenDaysAgo = DateUtil.utcMidnightOfTodayMillis() - 7L * DateUtil.MS_PER_DAY
            val cutoff = DateUtil.localMidnightOfUtcCalendarDateMillis(sevenDaysAgo)
            assertTrue(captor.firstValue in (cutoff - DateUtil.MS_PER_DAY)..(cutoff + DateUtil.MS_PER_DAY))
        }
}
