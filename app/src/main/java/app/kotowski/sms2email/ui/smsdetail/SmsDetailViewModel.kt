package app.kotowski.sms2email.ui.smsdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsUiModel
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.system.NetworkConnectivityObserver
import app.kotowski.sms2email.system.NetworkStatus
import app.kotowski.sms2email.system.SimChangeObserver
import app.kotowski.sms2email.system.SimNameResolver
import app.kotowski.sms2email.ui.cancelOutcomeToast
import app.kotowski.sms2email.ui.deleteOutcomeToast
import app.kotowski.sms2email.ui.forwardOutcomeToasts
import app.kotowski.sms2email.ui.smslist.SmsListEnricher
import app.kotowski.sms2email.util.ToastUtil
import app.kotowski.sms2email.work.EmailWorker
import app.kotowski.sms2email.work.SmsActions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

// The row the detail screen is showing, plus the SIM names and tray slots for its
// subscription. The row is observed by id from the DB (the list window is capped and
// does not necessarily contain it) and re-emitted on every DB change, so a status
// transition while the detail is open is reflected.
data class SmsDetailData(
    val sms: SmsUiModel,
    val simNames: Map<Int, String>,
    val simSlots: Map<Int, Int> = emptyMap(),
)

// The detail row load phase: Loading until the first observation lands, NotFound
// when the id has no row (stale id, or cleaned up before the row ever rendered —
// the screen shows a proper "no longer available" state, not an endless spinner),
// Loaded once a row was observed (re-emitted on every DB change, like SmsDetailData).
sealed interface SmsDetailState {
    data object Loading : SmsDetailState

    data object NotFound : SmsDetailState

    data class Loaded(
        val data: SmsDetailData,
    ) : SmsDetailState
}

// The settings flags the detail screen reads from the list state: the screen has its
// own ViewModel, so it carries them here instead of paying for the list pipeline.
internal data class SmsDetailUiState(
    val displaySimCards: Boolean = false,
    val hasInternet: Boolean = true,
)

@HiltViewModel
class SmsDetailViewModel
    @Inject
    constructor(
        @ApplicationContext private val appContext: android.content.Context,
        private val repository: SmsRepository,
        private val prefs: EncryptedPrefs,
        private val contactResolver: ContactResolver,
        private val simNameResolver: SimNameResolver,
        private val simChangeObserver: SimChangeObserver,
        private val actions: SmsActions,
        private val networkObserver: NetworkConnectivityObserver,
    ) : ViewModel() {
        private val listEnricher =
            SmsListEnricher(
                appContext,
                prefs,
                contactResolver,
                simNameResolver,
            )

        private val uiState = MutableStateFlow(SmsDetailUiState())

        internal val state: StateFlow<SmsDetailUiState> = uiState.asStateFlow()

        private val _detailState = MutableStateFlow<SmsDetailState>(SmsDetailState.Loading)

        val detailState: StateFlow<SmsDetailState> = _detailState.asStateFlow()

        private var detailJob: Job? = null

        init {
            uiState.update {
                it.copy(
                    displaySimCards = prefs.load().displaySimCards,
                    hasInternet = networkObserver.isConnectedCurrently(),
                )
            }

            // Reactive connectivity for the status line: a one-shot read in composition
            // stays stale (offline → online while the detail is open never recomposes
            // it), the same pattern as the dashboard.
            viewModelScope.launch {
                networkObserver.networkStatus.collect { status ->
                    uiState.update { it.copy(hasInternet = status == NetworkStatus.Available) }
                }
            }

            // Live SIM name for the open row: a subscription change (or the 60 s fallback
            // tick) re-resolves the name while the detail is loaded, so a rename shows up
            // without reopening the row. No-op until the row is Loaded.
            viewModelScope.launch {
                simChangeObserver.simChanges.collect {
                    val data = (_detailState.value as? SmsDetailState.Loaded)?.data ?: return@collect
                    val simNames = withContext(Dispatchers.IO) { listEnricher.buildSimNames(listOf(data.sms)) }
                    val simSlots = withContext(Dispatchers.IO) { listEnricher.buildSimSlots(listOf(data.sms)) }
                    // Atomic update on the current state: a DB emission that lands during the
                    // IO read above must not be clobbered back to the stale row snapshot.
                    _detailState.update { state ->
                        val loaded = state as? SmsDetailState.Loaded ?: return@update state
                        // A row switched during the read already carries its own names from the
                        // DB load, so this event is a no-op for it.
                        if (loaded.data.sms.id != data.sms.id) return@update state
                        SmsDetailState.Loaded(loaded.data.copy(simNames = simNames, simSlots = simSlots))
                    }
                }
            }
        }

        // The detail screen's row, loaded by id from the DB: the capped list window does
        // not necessarily contain the clicked row, and this detail-destination VM's own
        // window is unrelated to the screen the row was clicked on.
        fun loadSms(smsId: Long) {
            detailJob?.cancel()
            _detailState.value = SmsDetailState.Loading
            detailJob =
                viewModelScope.launch {
                    repository.observeSmsById(smsId).collect { sms ->
                        _detailState.value =
                            sms?.let {
                                SmsDetailState.Loaded(
                                    SmsDetailData(
                                        sms = it,
                                        simNames = withContext(Dispatchers.IO) { listEnricher.buildSimNames(listOf(it)) },
                                        simSlots = withContext(Dispatchers.IO) { listEnricher.buildSimSlots(listOf(it)) },
                                    ),
                                )
                            } ?: SmsDetailState.NotFound
                    }
                }
        }

        // Reuses the shared forward pipeline (gate, offline park, CAS, guarded enqueue);
        // only the worker differs: the single row goes through the per-row EmailWorker
        // (email_send_<id>), not the batch.
        fun forwardRow(sms: SmsUiModel) {
            viewModelScope.launch {
                val outcome =
                    actions.forwardSms(listOf(sms.id)) { batchIds ->
                        batchIds.forEach { EmailWorker.enqueueSend(appContext, it) }
                    }
                forwardOutcomeToasts(appContext, outcome).forEach { ToastUtil.show(appContext, it) }
            }
        }

        // Reuses the shared batch cancel: SENDING rows are left to finish, the rest is
        // parked as IGNORED, and the all/partial skip toast rule is the shared one.
        fun cancelRow(sms: SmsUiModel) {
            viewModelScope.launch {
                val outcome = actions.cancelSms(listOf(sms.id))
                cancelOutcomeToast(outcome)?.let { ToastUtil.show(appContext, it) }
            }
        }

        // Reuses the shared guarded delete: a short count means the row moved in flight
        // and the skip is shown.
        fun deleteRow(smsId: Long) {
            viewModelScope.launch {
                val outcome = actions.deleteSms(listOf(smsId))
                deleteOutcomeToast(outcome)?.let { ToastUtil.show(appContext, it) }
            }
        }

        // The per-number policy (prefs gate, fail-open) lives in the enricher, like the
        // list path; here only the provider IPC stays off the main thread.
        suspend fun resolveContactName(phoneNumber: String): String? =
            withContext(Dispatchers.IO) { listEnricher.resolveContact(phoneNumber) }
    }
