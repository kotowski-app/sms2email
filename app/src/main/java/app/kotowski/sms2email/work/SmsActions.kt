package app.kotowski.sms2email.work

import android.content.Context
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.Sms
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.StatusUpdate
import app.kotowski.sms2email.data.filter.FilterMode
import app.kotowski.sms2email.data.filter.SmsFilter
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.NetworkUtil
import app.kotowski.sms2email.system.SimNameResolver
import app.kotowski.sms2email.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

// What a manual forward did: `found` rows were considered (filter-passing + in-flight,
// post-partition), `scheduled` of them were taken (parked for a first retry, or
// transitioned + enqueued). The work layer shows nothing; the UI maps the outcome to
// its toasts (ui.SmsActionFeedback), resolving the plural from `blocked` — so the count
// must be the number of rows the toast talks about. `blocked` covers only the
// "sending is in progress" rows (the in-flight SENDING split + the CAS losers); the
// message-filter skips are reported separately in `filterBlocked` (with the mode that
// decided them, `filterMode`, set only when at least one row was filter-blocked), so
// one batch can carry both kinds of blocked rows and the toasts say both.
data class ForwardOutcome(
    val found: Int,
    val scheduled: Int,
    val forwardingOff: Boolean = false,
    val filterBlocked: Int = 0,
    val filterMode: FilterMode? = null,
) {
    val blocked: Int
        get() = found - scheduled
}

// `attempted` is what the cancel actually tried (the in-flight SENDING rows excluded)
// and `blocked` derives from it, like ForwardOutcome's. `requested` is what the caller
// asked to cancel (the ids passed in): the all-blocked outcome (cancelled == 0) reads
// with that count, since nothing else in the outcome carries it (an empty fetch and an
// all-in-flight fetch both yield cancelled == 0). The empty queue is (0, 0, 0) and
// reads as silent.
data class CancelOutcome(
    val cancelled: Int,
    val attempted: Int,
    val requested: Int,
) {
    val blocked: Int
        get() = attempted - cancelled
}

data class DeleteOutcome(
    val deleted: Int,
    val requested: Int,
)

// The manual user actions shared by the list, queue, detail and history screens, wired
// by Hilt so the screens inject this object instead of re-spelling the collaborators.
// The per-call `enqueue` lambda stays public: the worker type is chosen by the caller
// by design (see [transitionAndEnqueueBatch]).
@Singleton
class SmsActions
    @Inject
    constructor(
        private val repository: SmsRepository,
        @ApplicationContext private val appContext: Context,
        private val prefs: EncryptedPrefs,
        private val retryScheduler: SmsRetryScheduler,
        // The tray-slot lookup of the manual-forward filter partition (the same
        // precedent as SmsSendExecutor's SIM-name lookup).
        private val simNameResolver: SimNameResolver,
    ) {
        // The CAS + guarded enqueue shared by every manual-forward path. The enqueue action
        // is passed in because the worker type differs by design: the list and the queue
        // forward enqueue EmailBatchWorker, the detail screen enqueues the per-row
        // EmailWorker (email_send_<id>). The guard marks the transitioned rows FAILED when
        // the enqueue throws: without it they would be stranded in `status` (a crash
        // between the CAS and the enqueue) with no worker left to pick them up.
        suspend fun transitionAndEnqueueBatch(
            ids: List<Long>,
            expectedStatuses: List<SmsStatus>,
            status: SmsStatus,
            update: StatusUpdate = StatusUpdate(),
            enqueue: suspend (List<Long>) -> Unit,
        ): List<Long> {
            val idsToForward =
                repository.transitionSmsStatusMany(
                    ids,
                    expectedStatuses,
                    status,
                    update,
                )
            if (idsToForward.isNotEmpty()) {
                try {
                    enqueue(idsToForward)
                } catch (e: CancellationException) {
                    // Coroutine cancellation (the scope closing mid-forward) is not an
                    // enqueue failure: rethrow it so the scope cancels, and the
                    // transitioned rows stay in `status` for the sweep to pick up instead
                    // of being parked FAILED for a cancellation (and logged as a worker
                    // failure).
                    throw e
                } catch (e: Exception) {
                    Logger.e(
                        "SmsActions",
                        "transitionAndEnqueueBatch: failed to enqueue worker: ${e.javaClass.simpleName}: ${e.message}",
                        e,
                    )
                    repository.transitionSmsStatusMany(
                        idsToForward,
                        expectedStatuses = listOf(status),
                        status = SmsStatus.FAILED,
                        update = StatusUpdate(statusDetails = appContext.getString(R.string.error_worker_enqueue_failed)),
                    )
                }
            }
            return idsToForward
        }

        // The shared manual-forward prelude (the list/detail path): the forwarding gate, the
        // offline park (first retry per row), the expected -> PENDING CAS and the guarded
        // enqueue. `expectedStatuses` defaults to MANUAL_FORWARDABLE (the detail screen
        // re-forwards any of them, including a SENT row, by design); the Queue list path
        // narrows it to its in-flight statuses for BOTH the park and the forward CAS, so a
        // row delivered while selected can never be taken again (a silent duplicate
        // resend). Shows nothing; the caller maps the outcome to its toasts.
        suspend fun forwardSms(
            ids: List<Long>,
            expectedStatuses: List<SmsStatus> = SmsStatus.MANUAL_FORWARDABLE,
            enqueue: suspend (List<Long>) -> Unit,
        ): ForwardOutcome {
            // The send workers no-op while forwarding is off; reject before the transition
            // so the rows keep their current status and the user can be told why nothing
            // happened.
            val settings = prefs.load()
            if (!settings.isForwardingEnabled) {
                Logger.w("SmsActions", "forwardSms: forwarding disabled, not sending ${ids.size} messages")
                return ForwardOutcome(0, 0, forwardingOff = true)
            }
            val smsList = repository.getSmsByIds(ids)
            if (smsList.isEmpty()) return ForwardOutcome(0, 0)
            // The two-step partition at the entry (in-flight first, then the filter),
            // before the offline-park branch and the CAS. The in-flight (SENDING) rows
            // split off first and keep the in-flight bucket: they are being sent anyway
            // (no send-time check — a claimed row can't be unsent), so the filter's
            // verdict is moot for them (a filter-first partition would toast "skipped by
            // filter" for a message that is being delivered). The filter partition then
            // runs on the rest; its skips are reported separately, and the offline park
            // (which also transitions to RETRYING) must never see a filtered row, or it
            // would be sent once the network returns.
            val partition = partitionForForward(smsList, settings.effectiveFilter)
            val found = partition.inFlight.size + partition.passing.size
            val filterMode = partition.filterMode
            if (!NetworkUtil.hasReliableInternet(appContext)) {
                val waitingForInternet = appContext.getString(R.string.error_waiting_for_internet)
                // The park CAS accepts the same statuses as the forward CAS below: a
                // narrowed caller (the queue) must not park a row that left its scope
                // while selected — the default MANUAL_FORWARDABLE would re-park a
                // delivered row and re-send it.
                var parked = 0
                for (id in partition.passing.map { it.id }) {
                    if (retryScheduler.scheduleFirstRetry(id, waitingForInternet, expectedStatuses)) {
                        parked++
                    }
                }
                return ForwardOutcome(found, parked, filterBlocked = partition.blocked.size, filterMode = filterMode)
            }
            val transitioned =
                transitionAndEnqueueBatch(
                    partition.passing.map { it.id },
                    expectedStatuses = expectedStatuses,
                    status = SmsStatus.PENDING,
                    update = RetryPolicy.freshPendingWindow(),
                    enqueue = enqueue,
                )
            return ForwardOutcome(found, transitioned.size, filterBlocked = partition.blocked.size, filterMode = filterMode)
        }

        // The shared two-step partition of the manual-forward entry: the in-flight
        // (SENDING) rows split off first (they keep their bucket, the filter's verdict
        // is moot for them), then the message filter partitions the rest. `filterMode`
        // is the mode that decided the skips, non-null only when at least one row was
        // filter-blocked (the toast's reason is mode-dependent); an off filter blocks
        // nothing, so the filter bucket stays empty.
        private data class ForwardPartition(
            val inFlight: List<Long>,
            val passing: List<Sms>,
            val blocked: List<Sms>,
            val filterMode: FilterMode?,
        )

        private fun partitionForForward(
            smsList: List<Sms>,
            filter: SmsFilter,
        ): ForwardPartition {
            val inFlight = smsList.filter { it.status == SmsStatus.SENDING }.map { it.id }
            val (passing, blocked) =
                smsList
                    .filter { it.status != SmsStatus.SENDING }
                    .partition { sms ->
                        filter.shouldForward(sms.messageBody, sms.phoneNumber, simNameResolver.getSlotFor(sms.simId))
                    }
            return ForwardPartition(
                inFlight = inFlight,
                passing = passing,
                blocked = blocked,
                filterMode = if (blocked.isNotEmpty()) filter.mode else null,
            )
        }

        // The queue's "Send all now": a user-forced immediate send attempt that overrides the
        // automatic retry schedule (the queue twin of [forwardSms]). The forwarding gate, the
        // in-flight SENDING exclusion, and the PENDING/RETRYING -> RETRYING CAS with the
        // fresh forward window. Unlike [forwardSms] there is no offline park: the batch
        // worker handles the no-network case itself. Shows nothing; the caller maps the
        // outcome to its toasts.
        suspend fun forceSendQueue(enqueue: suspend (List<Long>) -> Unit): ForwardOutcome {
            val settings = prefs.load()
            if (!settings.isForwardingEnabled) {
                Logger.w("SmsActions", "forceSendQueue: forwarding disabled, not sending the queue")
                return ForwardOutcome(0, 0, forwardingOff = true)
            }
            // The whole queue, not just the loaded window: the ids come from the DB.
            val ids = repository.getQueueIds()
            if (ids.isEmpty()) return ForwardOutcome(0, 0)
            val smsList = repository.getSmsByIds(ids)
            // The two-step partition at the entry (the SENDING exclusion precedes the
            // filter partition, like forwardSms): the buckets stay separate — a queue
            // whose rest the filter blocked all reports (found = in-flight, scheduled =
            // 0, filterBlocked = N), and an empty queue stays (0, 0, 0) and reads as
            // silent.
            val partition = partitionForForward(smsList, settings.effectiveFilter)
            val found = partition.inFlight.size + partition.passing.size
            if (partition.passing.isEmpty()) {
                return ForwardOutcome(found, 0, filterBlocked = partition.blocked.size, filterMode = partition.filterMode)
            }
            val transitioned =
                transitionAndEnqueueBatch(
                    partition.passing.map { it.id },
                    expectedStatuses = listOf(SmsStatus.PENDING, SmsStatus.RETRYING),
                    status = SmsStatus.RETRYING,
                    update = RetryPolicy.freshForwardWindow(System.currentTimeMillis()),
                    enqueue = enqueue,
                )
            return ForwardOutcome(found, transitioned.size, filterBlocked = partition.blocked.size, filterMode = partition.filterMode)
        }

        // The shared cancel: in-flight SENDING rows are left to finish (see
        // QueueViewModel.cancelAll), the rest is parked as IGNORED with the user-cancel
        // detail. Shows nothing; the caller maps the outcome to its toasts. Every skip is
        // visible in the counts: the CAS lost a race for at least one row (it was claimed
        // SENDING between the fetch and the transition), or the fetch came back empty —
        // both read as fully blocked.
        suspend fun cancelSms(ids: List<Long>): CancelOutcome {
            val smsList = repository.getSmsByIds(ids)
            val toCancel = smsList.filter { it.status != SmsStatus.SENDING }.map { it.id }
            if (toCancel.isEmpty()) {
                // Nothing to cancel, but the confirm was destructive-looking: report it so
                // the UI can say so.
                return CancelOutcome(0, 0, ids.size)
            }
            val transitioned =
                repository.transitionSmsStatusMany(
                    toCancel,
                    expectedStatuses = listOf(SmsStatus.PENDING, SmsStatus.RETRYING),
                    status = SmsStatus.IGNORED,
                    update = StatusUpdate(statusDetails = appContext.getString(R.string.sms_status_cancelled_by_user)),
                )
            return CancelOutcome(transitioned.size, toCancel.size, ids.size)
        }

        // The queue's "Cancel All": the whole queue from the DB (not just the loaded
        // window) through the shared [cancelSms], plus the queue's "empty stays silent"
        // policy — a tap on an empty queue cancelled nothing, so there is nothing to
        // report ((0, 0, 0) reads as silent in the UI's mapper, unlike a non-empty fully
        // blocked cancel).
        suspend fun cancelQueue(): CancelOutcome {
            // The whole queue, not just the loaded window: the ids come from the DB.
            val ids = repository.getQueueIds()
            if (ids.isEmpty()) return CancelOutcome(0, 0, 0)
            return cancelSms(ids)
        }

        // Deletes rows through the status-filtered delete (see SmsDao.deleteSmsByIds): the
        // History shows terminal rows only, so a short count means the guard skipped a row
        // (it moved in flight between the tap and the confirm) or the DB refused the batch.
        // Shows nothing; the caller maps the outcome to its toast.
        suspend fun deleteSms(ids: List<Long>): DeleteOutcome {
            val deleted = repository.deleteSmsByIds(ids)
            return DeleteOutcome(deleted, ids.size)
        }
    }
