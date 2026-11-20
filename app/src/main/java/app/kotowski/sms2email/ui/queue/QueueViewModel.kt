package app.kotowski.sms2email.ui.queue

import android.content.Context
import androidx.lifecycle.viewModelScope
import app.kotowski.sms2email.data.database.SmsListScope
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.ContactChangeObserver
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.system.NetworkConnectivityObserver
import app.kotowski.sms2email.system.SimChangeObserver
import app.kotowski.sms2email.system.SimNameResolver
import app.kotowski.sms2email.ui.cancelOutcomeToast
import app.kotowski.sms2email.ui.forwardOutcomeToasts
import app.kotowski.sms2email.ui.smslist.SmsListUiState
import app.kotowski.sms2email.ui.smslist.SmsListViewModel
import app.kotowski.sms2email.util.ToastUtil
import app.kotowski.sms2email.work.EmailBatchWorker
import app.kotowski.sms2email.work.SmsActions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QueueViewModel
    @Inject
    constructor(
        @ApplicationContext appContext: Context,
        repository: SmsRepository,
        prefs: EncryptedPrefs,
        contactResolver: ContactResolver,
        simNameResolver: SimNameResolver,
        simChangeObserver: SimChangeObserver,
        contactChangeObserver: ContactChangeObserver,
        private val actions: SmsActions,
        networkObserver: NetworkConnectivityObserver,
    ) : SmsListViewModel(
            SmsListScope.QUEUE,
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
        val state: StateFlow<SmsListUiState>
            get() = listState

        // Queue rows are in-flight only; a row delivered or failed while selected must
        // not be re-forwarded (SENT and FAILED sit in MANUAL_FORWARDABLE, so the shared
        // CAS would silently re-send an already delivered message). SENDING is excluded
        // like in forceSendQueue.
        protected override val forwardExpectedStatuses: List<SmsStatus>
            get() = listOf(SmsStatus.PENDING, SmsStatus.RETRYING)

        // The gate, the SENDING exclusion and the PENDING/RETRYING -> RETRYING policy
        // live once in the shared work function; here only the queue's worker wiring.
        fun forwardAll() {
            viewModelScope.launch {
                val outcome =
                    actions.forceSendQueue { batchIds ->
                        EmailBatchWorker.enqueue(appContext, batchIds)
                    }
                forwardOutcomeToasts(appContext, outcome).forEach { ToastUtil.show(appContext, it) }
            }
        }

        // Parks the whole queue as IGNORED (the safe "get these out of the queue"): the
        // rows move to the History, where they can be re-sent or deleted, and nothing
        // is lost. In-flight SENDING rows are left to finish — the same invariant as
        // stopping forwarding (a batch's 250 OK may already be on the wire); a send
        // that fails afterwards lands back in the queue as RETRYING and can be
        // cancelled again. The whole-queue fetch and the empty-queue-silent policy
        // live in the shared work function, like forwardAll's.
        fun cancelAll() {
            viewModelScope.launch {
                val outcome = actions.cancelQueue()
                cancelOutcomeToast(outcome)?.let { ToastUtil.show(appContext, it) }
            }
        }

        // Parks the selected in-flight rows as IGNORED (cancelAll scoped to the
        // selection): the rows move to the History, where they can be re-sent or
        // deleted. Queue rows are never deleted: the message has not been delivered
        // yet.
        fun cancelSelected() {
            val ids = selectedIds
            if (ids.isEmpty()) return
            exitSelectionMode()
            viewModelScope.launch {
                val outcome = actions.cancelSms(ids.toList())
                cancelOutcomeToast(outcome)?.let { ToastUtil.show(appContext, it) }
            }
        }
    }
