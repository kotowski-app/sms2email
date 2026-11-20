package app.kotowski.sms2email.ui.smslist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.SmsListScope
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.ContactChangeObserver
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.system.NetworkConnectivityObserver
import app.kotowski.sms2email.system.NetworkStatus
import app.kotowski.sms2email.system.SimChangeObserver
import app.kotowski.sms2email.system.SimNameResolver
import app.kotowski.sms2email.ui.forwardOutcomeToasts
import app.kotowski.sms2email.util.ToastUtil
import app.kotowski.sms2email.work.EmailBatchWorker
import app.kotowski.sms2email.work.SmsActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The list state shared by the Queue and History screens.
 *
 * The paged window ([PagedSmsListState]), the SIM display settings, the connectivity
 * (the cards' waiting-for-internet line), and the multi-select state.
 */
data class SmsListUiState(
    val paged: PagedSmsListState = PagedSmsListState(),
    val displaySimCards: Boolean = false,
    val selectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    val hasInternet: Boolean = true,
)

// The shared paged-list pipeline + selection for the Queue and History screens; each
// subclass adds only its scope-specific actions.
abstract class SmsListViewModel(
    private val listScope: SmsListScope,
    protected val appContext: Context,
    protected val repository: SmsRepository,
    protected val prefs: EncryptedPrefs,
    private val actions: SmsActions,
    private val contactResolver: ContactResolver,
    private val simNameResolver: SimNameResolver,
    private val simChangeObserver: SimChangeObserver,
    private val contactChangeObserver: ContactChangeObserver,
    private val networkObserver: NetworkConnectivityObserver,
) : ViewModel() {
    private val _listState = MutableStateFlow(SmsListUiState())

    private val listEnricher =
        SmsListEnricher(
            appContext,
            prefs,
            contactResolver,
            simNameResolver,
        )

    private val listController =
        PagedSmsListController(
            viewModelScope,
            listScope,
            repository,
            listEnricher,
            prefs,
            contactResolver,
            simNameResolver,
            simChangeObserver,
            contactChangeObserver,
        )

    // The paged window is nested, not copied field-by-field: a new paged field can no
    // longer be silently dropped from the UI state.
    val listState: StateFlow<SmsListUiState> =
        combine(_listState, listController.state) { own, list ->
            own.copy(paged = list)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SmsListUiState())

    private val selection =
        SelectionController(
            _listState,
            { it.selectedIds },
            { smsListState, mode, ids -> smsListState.copy(selectionMode = mode, selectedIds = ids) },
        )

    protected val selectedIds: Set<Long>
        get() = _listState.value.selectedIds

    // The statuses the shared forward CAS accepts for this screen's rows. The History
    // forwards any manually forwardable row (re-sending a SENT one is by design there);
    // the Queue narrows it (a delivered row must never be re-forwarded from the list).
    protected open val forwardExpectedStatuses: List<SmsStatus>
        get() = SmsStatus.MANUAL_FORWARDABLE

    init {
        val settings = prefs.load()
        _listState.update {
            it.copy(
                displaySimCards = settings.displaySimCards,
                hasInternet = networkObserver.isConnectedCurrently(),
            )
        }
        // The cards' waiting-for-internet line follows the live connectivity (a callback
        // flip), not a per-second read.
        viewModelScope.launch {
            networkObserver.networkStatus.collect { status ->
                _listState.update { it.copy(hasInternet = status == NetworkStatus.Available) }
            }
        }
        listController.start()
        // A selected row can leave the window without user action (a worker delivers or
        // fails it, a cleanup deletes it, a new arrival pushes it below the loaded cap):
        // exit the selection only when a row truly left this screen's scope — the counts
        // would otherwise lie and the batch actions would act on stale rows (a re-forward
        // of a delivered queue row would re-send it). A row merely pushed below the cap
        // is still in scope (it re-enters as the window grows), so the selection stays.
        viewModelScope.launch {
            listController.state
                .map { window -> window.smsList.map { row -> row.id }.toSet() }
                .distinctUntilChanged()
                .collect { windowIds ->
                    val current = _listState.value
                    val missing = current.selectedIds.filter { it !in windowIds }
                    if (current.selectionMode && missing.isNotEmpty()) {
                        // The scope check is a DB read: a missing row whose status is
                        // still in scope was only pushed out of the capped window. The
                        // re-read of the mode covers a user exit during that read.
                        val byId = repository.getSmsByIds(missing).associateBy { it.id }
                        if (
                            missing.any { byId[it]?.status !in listScope.statuses } &&
                            _listState.value.selectionMode
                        ) {
                            selection.exit()
                            ToastUtil.show(appContext, R.string.selection_cleared)
                        }
                    }
                }
        }
    }

    // The last query the screens announced: the search state is not restored across
    // recompositions (a rotation re-announces the empty query from the fresh field), so
    // only an actual change is an edit worth reacting to.
    private var activeSearchQuery: String = ""

    fun setSearchQuery(query: String) {
        // Every trimmed query change lands here (the screens funnel the field through
        // it, typing and the X-clear alike). Editing the filter re-windows the list
        // under the selection, so exit the mode at the edit and tell the user why —
        // the watcher's toast would blame changed messages instead. A re-announced
        // (unchanged) query is a recomposition, not an edit: the selection stays.
        val edited = query != activeSearchQuery
        activeSearchQuery = query
        if (edited && _listState.value.selectionMode) {
            selection.exit()
            ToastUtil.show(appContext, R.string.selection_cleared_search)
        }
        listController.setSearchQuery(query)
    }

    fun loadMore() {
        listController.loadMore()
    }

    fun enterSelectionMode() {
        selection.enter()
    }

    fun exitSelectionMode() {
        selection.exit()
    }

    fun toggleSelection(smsId: Long) {
        selection.toggle(smsId)
    }

    fun forwardSelected() {
        val ids = selectedIds
        if (ids.isEmpty()) return
        exitSelectionMode()
        viewModelScope.launch {
            val outcome =
                actions.forwardSms(ids.toList(), expectedStatuses = forwardExpectedStatuses) { batchIds ->
                    EmailBatchWorker.enqueue(appContext, batchIds)
                }
            forwardOutcomeToasts(appContext, outcome).forEach { ToastUtil.show(appContext, it) }
        }
    }
}
