package app.kotowski.sms2email.ui.history

import android.Manifest
import android.content.Context
import app.kotowski.sms2email.PeriodPreset
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.service.SmsImporter
import app.kotowski.sms2email.system.PermissionManager
import app.kotowski.sms2email.util.DateUtil
import app.kotowski.sms2email.util.ToastUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ImportTimeFrame(
    val resId: Int,
    // The range length in days for the import cutoff (all time and since-date have
    // no fixed length).
    val nominalDays: Int?,
) {
    // The rows carry their own "Last …" labels (import dialog wording, per-language
    // inflection). 24 hours and 7 days are import-only; the 30- and 90-day rows take
    // their day counts from PeriodPreset so a period has one length everywhere. All
    // time and since-date have no fixed length. The 24 hours row is a rolling window;
    // the day rows are calendar-day based (see computeSinceTimestamp).
    TWENTY_FOUR_HOURS(R.string.import_timeframe_24h, 1),
    SEVEN_DAYS(R.string.import_timeframe_7d, 7),
    THIRTY_DAYS(R.string.import_timeframe_30d, PeriodPreset.THIRTY_DAYS.days),
    NINETY_DAYS(R.string.import_timeframe_90d, PeriodPreset.NINETY_DAYS.days),
    ALL_TIME(R.string.import_timeframe_all, null),
    SINCE_DATE(R.string.import_timeframe_since, null),
    ;

    companion object {
        val Default = SEVEN_DAYS
    }
}

data class ImportUiState(
    val isImporting: Boolean = false,
    val importProgress: SmsImporter.ImportProgress? = null,
    val importResult: SmsImporter.ImportResult? = null,
    val importError: String? = null,
    val showImportDialog: Boolean = false,
    val selectedTimeFrame: ImportTimeFrame = ImportTimeFrame.Default,
    val customImportDate: Long? = null,
    // The yes/no dialog shown when auto-clear would delete part of the chosen import
    // range. While it is up, the import is held in pendingImportSince (null = all
    // time).
    val showRetentionConfirm: Boolean = false,
    val retentionConfirmLabel: String? = null,
    val pendingImportSince: Long? = null,
)

// The import wizard's state machine (timeframe selection, since-date, retention-confirm)
// and the import execution, kept outside HistoryViewModel so it is unit-testable without
// a ViewModel; the VM keeps thin delegations.
internal class ImportFlowController(
    private val scope: CoroutineScope,
    private val smsImporter: SmsImporter,
    private val permissionManager: PermissionManager,
    private val prefs: EncryptedPrefs,
    private val appContext: Context,
) {
    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    fun showImportDialog() {
        _state.update { it.copy(showImportDialog = true) }
    }

    fun dismissImportDialog() {
        _state.update { it.copy(showImportDialog = false) }
    }

    fun selectTimeFrame(timeFrame: ImportTimeFrame) {
        _state.update { it.copy(selectedTimeFrame = timeFrame) }
    }

    fun setCustomImportDate(timestamp: Long) {
        _state.update { it.copy(customImportDate = timestamp, selectedTimeFrame = ImportTimeFrame.SINCE_DATE) }
    }

    fun confirmImport() {
        val state = _state.value
        if (state.selectedTimeFrame == ImportTimeFrame.SINCE_DATE && state.customImportDate == null) {
            ToastUtil.show(appContext, R.string.import_pick_date_first)
            return
        }
        // One clock read for the future-date guard, the import's since, and the retention
        // warning: a confirm straddling local midnight must not compare two different
        // "today"s, or the warning could be a day off from the range it guards.
        val now = System.currentTimeMillis()
        if (
            state.selectedTimeFrame == ImportTimeFrame.SINCE_DATE &&
            state.customImportDate != null &&
            state.customImportDate > now
        ) {
            ToastUtil.show(appContext, R.string.import_future_date)
            return
        }
        val sinceTimestamp = computeSinceTimestamp(state.selectedTimeFrame, state.customImportDate, now)
        val label = retentionWarningLabel(state.selectedTimeFrame, state.customImportDate, now)
        if (label != null) {
            _state.update {
                it.copy(
                    showRetentionConfirm = true,
                    retentionConfirmLabel = label,
                    pendingImportSince = sinceTimestamp,
                )
            }
            return
        }
        dismissImportDialog()
        importSms(sinceTimestamp)
    }

    // The user said yes to importing although the next auto-clear will delete part of it.
    // The consume is one atomic update: a second tap on the dialog before it leaves
    // composition sees no pending import and is a no-op, instead of re-reading the
    // already-cleared pendingImportSince (null = all-time) and launching a second import.
    // showRetentionConfirm is the presence flag: pendingImportSince itself is null for
    // an all-time import, so null cannot mean "already consumed".
    fun confirmRetentionImport() {
        var since: Long? = null
        var pending = false
        _state.update { state ->
            pending = state.showRetentionConfirm
            since = state.pendingImportSince
            state.copy(showRetentionConfirm = false, retentionConfirmLabel = null, pendingImportSince = null)
        }
        if (!pending) return
        dismissImportDialog()
        importSms(since)
    }

    // The user said no (or dismissed): the sheet stays open so the range can be adjusted.
    fun cancelRetentionImport() {
        _state.update { it.copy(showRetentionConfirm = false, retentionConfirmLabel = null, pendingImportSince = null) }
    }

    // The retention period label when auto-clear is on and the chosen import range starts
    // strictly before the retention cutoff (i.e. the range is longer than the retention,
    // so a cleanup would delete part of the import), else null. The since timestamp and
    // the cutoff are both midnight-anchored calendar-day instants (daysAgoCutoff): an
    // equal range (a 30-day import with a 1-month retention) starts exactly at the cutoff
    // and the sweep deletes only rows before it, so the aligned range never warns.
    private fun retentionWarningLabel(
        timeFrame: ImportTimeFrame,
        customDate: Long?,
        now: Long,
    ): String? {
        val retentionDays = prefs.load().autoCleanupRetentionDays
        if (retentionDays <= 0) return null
        val since =
            computeSinceTimestamp(timeFrame, customDate, now)
                ?: return appContext.getString(PeriodPreset.labelResFor(retentionDays))
        if (since >= DateUtil.daysAgoCutoff(now, retentionDays.toLong())) return null
        return appContext.getString(PeriodPreset.labelResFor(retentionDays))
    }

    // The since timestamp of the chosen import range (null = all time). The day frames
    // are calendar-day windows (local-midnight anchored, matching the sweep's cutoff);
    // the 24h row is a rolling window — its label is literal hours and no offered
    // retention (min 30 days) can collide with it, so anchoring would only stretch it
    // to up to 48h.
    private fun computeSinceTimestamp(
        timeFrame: ImportTimeFrame,
        customDate: Long?,
        now: Long,
    ): Long? =
        when (timeFrame) {
            ImportTimeFrame.ALL_TIME -> null
            ImportTimeFrame.SINCE_DATE -> customDate
            ImportTimeFrame.TWENTY_FOUR_HOURS -> now - DateUtil.MS_PER_DAY
            else -> timeFrame.nominalDays?.let { DateUtil.daysAgoCutoff(now, it.toLong()) }
        }

    fun importSms(sinceTimestamp: Long? = null) {
        // One import at a time, checked before the launch so a second call on the same
        // tick (a double tap on a dialog before it leaves composition) is a no-op for
        // every entry point, not just the retention dialog.
        if (_state.value.isImporting) return
        _state.update { it.copy(isImporting = true, importResult = null, importError = null, importProgress = null) }
        scope.launch {
            if (!permissionManager.isGranted(Manifest.permission.READ_SMS)) {
                _state.update {
                    it.copy(
                        isImporting = false,
                        importError = appContext.getString(R.string.history_import_no_sms_permission),
                    )
                }
                return@launch
            }
            val result =
                smsImporter.importMessages(sinceTimestamp) { progress ->
                    _state.update { it.copy(importProgress = progress) }
                }
            _state.update { it.copy(isImporting = false, importResult = result, importProgress = null) }
        }
    }

    fun clearImportResult() {
        _state.update { it.copy(importResult = null, importError = null) }
    }
}
