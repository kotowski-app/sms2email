package app.kotowski.sms2email.ui.history

import androidx.lifecycle.viewModelScope
import app.kotowski.sms2email.data.database.SmsListScope
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.service.SmsImporter
import app.kotowski.sms2email.system.ContactChangeObserver
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.system.NetworkConnectivityObserver
import app.kotowski.sms2email.system.PermissionManager
import app.kotowski.sms2email.system.SimChangeObserver
import app.kotowski.sms2email.system.SimNameResolver
import app.kotowski.sms2email.ui.deleteOutcomeToast
import app.kotowski.sms2email.ui.smslist.SmsListUiState
import app.kotowski.sms2email.ui.smslist.SmsListViewModel
import app.kotowski.sms2email.util.Logger
import app.kotowski.sms2email.util.ToastUtil
import app.kotowski.sms2email.work.SmsActions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// The History screen state: the shared list state (itself nesting the paged window)
// plus the cleanup + auto-cleanup settings.
data class HistoryUiState(
    val list: SmsListUiState = SmsListUiState(),
    val cleanupDeletedCount: Int? = null,
    // 0 is the off state; the screen derives "on" from it.
    val autoCleanupRetentionDays: Int = 0,
)

@HiltViewModel
class HistoryViewModel
    @Inject
    constructor(
        repository: SmsRepository,
        private val smsImporter: SmsImporter,
        prefs: EncryptedPrefs,
        contactResolver: ContactResolver,
        simNameResolver: SimNameResolver,
        simChangeObserver: SimChangeObserver,
        contactChangeObserver: ContactChangeObserver,
        private val permissionManager: PermissionManager,
        private val actions: SmsActions,
        @ApplicationContext appContext: android.content.Context,
        networkObserver: NetworkConnectivityObserver,
    ) : SmsListViewModel(
            SmsListScope.HISTORY,
            appContext,
            repository,
            prefs,
            actions,
            contactResolver,
            simNameResolver,
            simChangeObserver,
            contactChangeObserver,
            networkObserver,
        ) {
        internal val importFlow = ImportFlowController(viewModelScope, smsImporter, permissionManager, prefs, appContext)

        // The cleanup + auto-cleanup settings fields that ride on top of the shared list state.
        private data class HistoryExtraState(
            val cleanupDeletedCount: Int? = null,
            val autoCleanupRetentionDays: Int = 0,
        )

        private val extraState = MutableStateFlow(HistoryExtraState())

        init {
            val settings = prefs.load()
            extraState.update {
                it.copy(
                    autoCleanupRetentionDays = settings.autoCleanupRetentionDays,
                )
            }
        }

        // The shared list state is nested, not copied field-by-field: a new list field
        // cannot be silently dropped from the UI state.
        val state: StateFlow<HistoryUiState> =
            combine(listState, extraState) { list, extra ->
                HistoryUiState(
                    list = list,
                    cleanupDeletedCount = extra.cleanupDeletedCount,
                    autoCleanupRetentionDays = extra.autoCleanupRetentionDays,
                )
            }.stateIn(viewModelScope, SharingStarted.Eagerly, HistoryUiState())

        // Deletes the History scope only: the in-flight queue (PENDING/SENDING/RETRYING)
        // survives, so a "clear history" can never lose an unsent message.
        fun clearHistory() {
            viewModelScope.launch {
                try {
                    repository.deleteHistorySms()
                } catch (e: Exception) {
                    Logger.e("HistoryVM", "clearHistory failed: ${e.message}", e)
                }
            }
        }

        fun runCleanupNow() {
            viewModelScope.launch {
                val deleted = repository.deleteExpired(prefs.load().autoCleanupRetentionDays.toLong())
                extraState.update { it.copy(cleanupDeletedCount = deleted) }
            }
        }

        fun clearCleanupResult() {
            extraState.update { it.copy(cleanupDeletedCount = null) }
        }

        fun deleteSelected() {
            val ids = selectedIds
            if (ids.isEmpty()) return
            exitSelectionMode()
            viewModelScope.launch {
                val outcome = actions.deleteSms(ids.toList())
                deleteOutcomeToast(outcome)?.let { ToastUtil.show(appContext, it) }
            }
        }
    }
