package app.kotowski.sms2email.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.NetworkConnectivityObserver
import app.kotowski.sms2email.system.NetworkStatus
import app.kotowski.sms2email.system.PermissionManager
import app.kotowski.sms2email.system.PermissionsAutoResetStatus
import app.kotowski.sms2email.system.SimChangeObserver
import app.kotowski.sms2email.system.SimDetector
import app.kotowski.sms2email.system.SimNameResolver
import app.kotowski.sms2email.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class DashboardState(
    val isForwardingEnabled: Boolean = false,
    // The in-flight queue rows (PENDING/SENDING/RETRYING) at the moment the user flipped
    // the toggle off: 0 = no confirmation dialog, >0 = the stop dialog is shown with this
    // count (confirming parks them as IGNORED, re-sendable from History — the SENDING part
    // is parked by the BackgroundStateReconciler once stale, or on the next launch).
    val stopForwardingQueueCount: Int = 0,
    val smsGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val batteryOptimizationsGranted: Boolean = false,
    val contactsGranted: Boolean = false,
    val phoneGranted: Boolean = false,
    val simCards: List<SimNameResolver.ActiveSim> = emptyList(),
    val isSingleSimDevice: Boolean = true,
    // The stored display setting: the phone permission row is relevant on a single-SIM
    // device only while it is on (the filter's slot facet needs a second tray, so the
    // SIM display is the only phone-permission consumer there).
    val displaySimCards: Boolean = false,
    val hasInternet: Boolean = true,
    // null = not fetched yet; NOT_AVAILABLE = the platform confirmed the feature is absent
    // (or the status query failed, treated as not applicable), in which case the UI hides
    // the row entirely.
    val permissionsAutoResetStatus: PermissionsAutoResetStatus? = null,
)

@HiltViewModel
class DashboardViewModel
    @Inject
    constructor(
        private val prefs: EncryptedPrefs,
        // Exposed so the screen calls the manager and the shared request flow directly,
        // no delegates here.
        val permissionManager: PermissionManager,
        private val repository: SmsRepository,
        private val simDetector: SimDetector,
        private val simNameResolver: SimNameResolver,
        private val simChangeObserver: SimChangeObserver,
        private val networkObserver: NetworkConnectivityObserver,
    ) : ViewModel() {
        private val _state = MutableStateFlow(DashboardState())
        val state: StateFlow<DashboardState> = _state.asStateFlow()

        private companion object {
            const val TAG = "DashVM"
        }

        init {
            _state.update {
                it.copy(
                    isSingleSimDevice = simDetector.isSingleSimDevice(),
                    displaySimCards = prefs.load().displaySimCards,
                    hasInternet = networkObserver.isConnectedCurrently(),
                )
            }

            // The toggle mirrors the pref, not just this VM's own writes: a settings import
            // or onboarding completion that flips the flag is reflected here right away.
            // The BackgroundStateReconciler (not this VM) reconciles the worker, the
            // notification and the queue for every such change.
            viewModelScope.launch {
                prefs.backgroundFlags.collect { flags ->
                    _state.update { it.copy(isForwardingEnabled = flags.forwardingEnabled) }
                }
            }

            viewModelScope.launch {
                networkObserver.networkStatus.collect { status ->
                    _state.update { it.copy(hasInternet = status == NetworkStatus.Available) }
                }
            }

            // Live SIM cards: a subscription change (or the observer's 60 s fallback tick)
            // re-reads the SIM list while the dashboard is alive, so a rename/swap shows up
            // without navigating away. The read is off the main thread (telephony IPC).
            viewModelScope.launch {
                simChangeObserver.simChanges.collect {
                    readSimCards()
                }
            }
        }

        fun toggleForwarding(enabled: Boolean) {
            viewModelScope.launch {
                try {
                    if (enabled) {
                        // Writing the pref is all this VM does: the BackgroundStateReconciler
                        // enqueues the sweep and shows the notification for the change.
                        prefs.update { it.copy(isForwardingEnabled = true) }
                    } else {
                        // "Off" must really stop sending: the observer parks the rows a
                        // worker/sweep would still deliver on its own (PENDING/RETRYING)
                        // and the stale SENDING rows a dead worker left behind. The
                        // confirmation count is the whole in-flight queue (PENDING/SENDING/
                        // RETRYING), not just the auto-sendable part: a SENDING-only queue
                        // must not stop silently either. With a non-empty queue that is
                        // destructive (the rows become IGNORED, re-sendable only by hand
                        // from History), so ask for confirmation first; with an empty queue
                        // there is nothing to lose, so stop immediately.
                        val queuedCount = repository.getQueueIds().size
                        if (queuedCount > 0) {
                            _state.update { it.copy(stopForwardingQueueCount = queuedCount) }
                        } else {
                            prefs.update { it.copy(isForwardingEnabled = false) }
                        }
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "toggleForwarding failed: ${e.javaClass.simpleName}: ${e.message}", e)
                }
            }
        }

        // The user confirmed the stop dialog: turn forwarding off. The observer parks the
        // queue, cancels the sweep and dismisses the notification for the change.
        fun confirmStopForwarding() {
            viewModelScope.launch {
                try {
                    prefs.update { it.copy(isForwardingEnabled = false) }
                } catch (e: Exception) {
                    Logger.e(TAG, "confirmStopForwarding failed: ${e.javaClass.simpleName}: ${e.message}", e)
                }
                _state.update { it.copy(stopForwardingQueueCount = 0) }
            }
        }

        // The user dismissed the stop dialog: forwarding keeps running, nothing is parked.
        fun cancelStopForwarding() {
            _state.update { it.copy(stopForwardingQueueCount = 0) }
        }

        fun updatePermissions() {
            val snapshot = permissionManager.snapshot()
            _state.value =
                _state.value.copy(
                    smsGranted = snapshot.sms,
                    notificationsGranted = snapshot.notifications,
                    batteryOptimizationsGranted = snapshot.battery,
                    contactsGranted = snapshot.contacts,
                    phoneGranted = snapshot.phone,
                )
        }

        // Plain on purpose: the resume observer is a non-suspend lambda, and the other
        // trigger methods (updatePermissionsAutoResetStatus, toggleForwarding) follow this
        // shape — the launch lives in the VM, so the UI never owns a coroutine scope.
        fun loadSimCards() {
            viewModelScope.launch { readSimCards() }
        }

        // Suspend core: the SIM read is a binder IPC into the telephony service, so the hop
        // to Dispatchers.IO lives here (not at each call site), keeping every caller —
        // first composition, resume, sim-change tick — off the main thread.
        internal suspend fun readSimCards() {
            try {
                val sims = withContext(Dispatchers.IO) { simNameResolver.getActiveSims() }
                val displaySimCards = prefs.load().displaySimCards
                _state.update {
                    it.copy(
                        simCards = if (displaySimCards) sims else emptyList(),
                        displaySimCards = displaySimCards,
                    )
                }
            } catch (e: CancellationException) {
                // A torn-down scope (VM clear) is not a load failure: keep the coroutine
                // cancelled instead of logging it and resetting the cards.
                throw e
            } catch (e: Exception) {
                // Handled degradation: the cards reset to empty and the UI re-reads on
                // resume, so the failure is a warning, not an error.
                Logger.w(TAG, "readSimCards failed: ${e.javaClass.simpleName}: ${e.message}", e)
                _state.value =
                    _state.value.copy(
                        simCards = emptyList(),
                    )
            }
        }

        fun updatePermissionsAutoResetStatus() {
            viewModelScope.launch {
                val status = permissionManager.getPermissionsAutoResetStatus()
                _state.update { it.copy(permissionsAutoResetStatus = status) }
            }
        }
    }
