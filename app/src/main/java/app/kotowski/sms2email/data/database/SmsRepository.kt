package app.kotowski.sms2email.data.database

import androidx.room.withTransaction
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.DedupLevel
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.util.DateUtil
import app.kotowski.sms2email.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// Optional fields written alongside a status transition. null means "keep the stored value"
// (statusDetails is the exception: null clears the stored detail).
data class StatusUpdate(
    val statusDetails: String? = null,
    val retryCount: Int? = null,
    val timeFirstSendAttempt: Long? = null,
    val timeLastSendAttempt: Long? = null,
    val timeNextSendAttempt: Long? = null,
)

// Sentinel bindings for an empty search facet: an empty IN (...) list is a SQL syntax
// error, so an empty facet binds a value no row can carry. searchNumber is E.164 (a
// leading "+") or the digits-only fallback (ContactResolver.toSearchNumber), so a
// non-digit sentinel can never equal a stored value — "" would collide with rows whose
// sender address has no digits (service names like "Bolt" or the localized unknown-sender
// fallback, which is digit-free too). No SIM slot is 0 (simId is -1 or a positive
// subscription id).
private const val NO_NUMBER_SENTINEL = "-"

private const val NO_SIM_SENTINEL = 0

// The enum names as SQL bindings: the DAO binds these lists, never status literals.
private fun statusNames(statuses: List<SmsStatus>): List<String> = statuses.map { it.name }

// Outcome of a dedup-guarded insert (insertIfNew). Inserted carries the new row id;
// Duplicate covers both the pre-check hit and the unique-dedupKey index rejecting the
// insert (id <= 0).
sealed interface IngestResult {
    data class Inserted(
        val id: Long,
    ) : IngestResult

    data object Duplicate : IngestResult
}

@Singleton
class SmsRepository
    @Inject
    constructor(
        private val dao: SmsDao,
        private val db: Sms2EmailDatabase,
        private val contactResolver: ContactResolver,
    ) {
        private val tag = "SmsRepository"

        // Serializes concurrent ingests (live receive and the inbox import run on
        // different dispatchers) across the whole check-then-act of insertIfNew. The
        // unique dedupKey index alone cannot close the race: it only collides for twins
        // whose timestamps fall in the same bucket, so a straddling twin pair would
        // insert twice. The suspend lock parks waiters without blocking a dispatcher
        // thread; it is the only lock the repository holds, so no deadlock can form.
        private val ingestMutex = Mutex()

        fun observeSms(
            scope: SmsListScope,
            search: SmsSearch,
            limit: Int,
        ): Flow<List<SmsUiModel>> =
            dao.observeSms(
                statuses = statusNames(scope.statuses),
                noSearch = search.noSearch,
                likePattern = search.likePattern,
                phoneLikePattern = search.phoneLikePattern,
                contactNumbers = search.contactNumbers.ifEmpty { listOf(NO_NUMBER_SENTINEL) },
                simIds = search.simIds.ifEmpty { listOf(NO_SIM_SENTINEL) },
                limit = limit,
            )

        fun observeCount(scope: SmsListScope): Flow<Int> = dao.observeCount(statusNames(scope.statuses))

        fun observeSmsById(id: Long): Flow<SmsUiModel?> = dao.observeSmsById(id)

        suspend fun getQueueIds(): List<Long> = safeDb("getQueueIds", emptyList()) { dao.getQueueIds(statusNames(SmsStatus.IN_FLIGHT)) }

        // Rows a worker/sweep will still send on its own (PENDING/RETRYING): what toggling
        // forwarding off must park so "off" really stops sending.
        suspend fun getAutoSendableIds(): List<Long> =
            safeDb("getAutoSendableIds", emptyList()) { dao.getAutoSendableIds(statusNames(SmsStatus.AUTO_SENDABLE)) }

        // The rows whose next attempt the success drain re-fires: RETRYING only — PENDING
        // rows already carry a zero-delay work, SENDING rows are in flight and owned by
        // a live work.
        suspend fun getRetryingIds(): List<Long> =
            safeDb("getRetryingIds", emptyList()) { dao.getAutoSendableIds(statusNames(listOf(SmsStatus.RETRYING))) }

        // The RETRYING rows with their stored due time: the exact-wake-up re-arm reads
        // the pending tokens' state from here.
        suspend fun getRetryingSms(): List<Sms> = safeDb("getRetryingSms", emptyList()) { dao.getRetrying(SmsStatus.RETRYING.name) }

        suspend fun getSmsById(id: Long): Sms? = safeDb("getSmsById", null) { dao.getSmsById(id) }

        // The ids are uncapped (the "forward all" / "cancel all" paths pass the whole
        // in-flight set), so they are chunked to stay under the SQLite variable limit on
        // older devices (999 before 3.32; the IN clause binds one ? per id). distinct()
        // keeps a repeated id out of two chunks. Each chunk comes back newest-first, but
        // the chunk order is not the time order, so the concat is re-sorted to keep the
        // DAO's (timeReceived DESC, id DESC) contract that the send paths rely on.
        suspend fun getSmsByIds(ids: List<Long>): List<Sms> =
            safeDb("getSmsByIds", emptyList()) {
                ids
                    .distinct()
                    .chunked(AppConfig.MAX_BATCH_IDS)
                    .flatMap { dao.getSmsByIds(it) }
                    .sortedWith(compareByDescending<Sms> { it.timeReceived }.thenByDescending { it.id })
            }

        // The single dedup core shared by the live receive and the import path, so the
        // key computation, the pre-check and the id<=0 collision semantics cannot
        // diverge. The pre-check is the ±window range match on the folded sender/body
        // for BOTH paths: a redelivery or a second observation of the same message (the
        // live re-report ms apart, the import the default app's runtime — or the
        // carrier transit with the PDU toggle on — away) lands within the window of the
        // stored row, and a range has no bucket-boundary misses. One window per level
        // (dedupLevel) serves both; OFF is a zero window, so its range match is an exact
        // timeReceived match (a re-import / re-delivery with the same timestamp is a
        // duplicate) and its key is the exact timestamp. The whole check-then-act is
        // serialized by a process-wide mutex held across the pre-check and the insert
        // (ingestMutex), so a concurrent twin's pre-check always runs after the first
        // twin's row is committed — no bucket-boundary gap. The stored dedupKey (the
        // level's time bucket, or the exact timestamp for OFF) stays a second layer
        // behind it: a same-bucket twin that still reaches the insert loses on the
        // unique index (id <= 0). Callers keep their own policies (status, blank
        // bodies, skip-vs-error counting)
        // and handle failures: insert exceptions propagate to the caller. The
        // searchNumber facet is folded here (the single insert site) through the shared
        // normalizer (ContactResolver.toSearchNumber), so the stored facet and the
        // name-search facet (resolveNumbersByName) cannot drift.
        suspend fun insertIfNew(
            phoneNumber: String,
            messageBody: String,
            timestamp: Long,
            simId: Int,
            status: SmsStatus,
            dedupLevel: DedupLevel,
        ): IngestResult =
            ingestMutex.withLock {
                val windowMillis = dedupLevel.windowMillis
                val dedupKey = DedupUtil.computeDedupKey(phoneNumber, timestamp, messageBody, windowMillis)
                if (existingInWindow(phoneNumber, messageBody, timestamp, windowMillis) != null) {
                    return@withLock IngestResult.Duplicate
                }
                val id =
                    insertSmsWithRetry(
                        Sms(
                            phoneNumber = phoneNumber,
                            messageBody = messageBody,
                            timeReceived = timestamp,
                            status = status,
                            simId = simId,
                            dedupKey = dedupKey,
                            searchNumber = contactResolver.toSearchNumber(phoneNumber),
                        ),
                    )
                if (id <= 0) {
                    IngestResult.Duplicate
                } else {
                    IngestResult.Inserted(id)
                }
            }

        // The insert retried against a briefly locked database: the app's own import or a
        // batch CAS can hold the write lock, and a single failed live insert would drop the
        // SMS for good (no row, so nothing for the sweep to see). A bounded quick retry
        // rides out the lock; whatever survives the attempts (disk full, corrupt db)
        // propagates to the caller, whose own retry (SmsProcessor) is the next line. The
        // retries stay inside the ingest mutex — moving the insert out of it would reopen
        // the bucket-boundary gap the mutex closes — so the lock is held at most the retry
        // delays longer, and only on the failing path.
        private suspend fun insertSmsWithRetry(sms: Sms): Long {
            var attempt = 0
            while (true) {
                attempt++
                try {
                    return dao.insertSms(sms)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (attempt >= AppConfig.SMS_INSERT_MAX_ATTEMPTS) {
                        throw e
                    }
                    Logger.w(
                        tag,
                        "insertSms failed, attempt $attempt of ${AppConfig.SMS_INSERT_MAX_ATTEMPTS}, retrying: ${e.javaClass.simpleName}: ${e.message}",
                        e,
                    )
                    delay(AppConfig.SMS_INSERT_RETRY_DELAY_MS)
                }
            }
        }

        // The pre-check of both paths: an existing row shares the folded sender and body
        // (folded the way the Sms constructor stores them) and its timeReceived falls
        // within ±window of the incoming timestamp (both inclusive) — a range match, so
        // unlike a bucketed key it has no boundary misses.
        private suspend fun existingInWindow(
            phoneNumber: String,
            messageBody: String,
            timestamp: Long,
            windowMillis: Long,
        ): Sms? =
            safeDb(
                "getSmsBySearchInWindow",
                null,
            ) {
                dao.getSmsBySearchInWindow(
                    foldSearchPhone(phoneNumber),
                    foldSearchBody(messageBody),
                    timestamp - windowMillis,
                    timestamp + windowMillis,
                )
            }

        suspend fun transitionSmsStatus(
            id: Long,
            expectedStatuses: List<SmsStatus>,
            status: SmsStatus,
            update: StatusUpdate = StatusUpdate(),
        ): Boolean =
            if (expectedStatuses.isEmpty()) {
                Logger.e(tag, "transitionSmsStatus: empty expectedStatuses, id=$id, status=${status.name}")
                false
            } else {
                try {
                    val rows =
                        dao.updateSmsStatusIf(
                            id = id,
                            expectedStatuses = expectedStatuses.map { it.name },
                            status = status.name,
                            statusDetails = update.statusDetails,
                            retryCount = update.retryCount,
                            timeFirstSendAttempt = update.timeFirstSendAttempt,
                            timeLastSendAttempt = update.timeLastSendAttempt,
                            timeNextSendAttempt = update.timeNextSendAttempt,
                        )
                    if (rows == 0) {
                        Logger.w(
                            tag,
                            "transitionSmsStatus: no rows updated (lost race), id=$id, expected=$expectedStatuses, status=${status.name}",
                        )
                        false
                    } else {
                        true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e(tag, "transitionSmsStatus failed: id=$id, ${e.javaClass.simpleName}: ${e.message}", e)
                    false
                }
            }

        // One transaction for the whole batch: the N per-id CAS updates share a single
        // commit (journal sync) instead of N, so "forward all" / "cancel all" /
        // forwarding-off stay fast as the queue grows. Each id still goes through the
        // per-id CAS and the returned list holds exactly the ids that transitioned. No
        // caller runs this inside an existing transaction, so the commit/rollback
        // boundaries are exactly the batch's.
        // The body runs on IO: Room forbids transactions on the main thread, and the UI
        // callers (forward/cancel all, selection forward/cancel) invoke this straight
        // from viewModelScope.
        suspend fun transitionSmsStatusMany(
            ids: List<Long>,
            expectedStatuses: List<SmsStatus>,
            status: SmsStatus,
            update: StatusUpdate = StatusUpdate(),
        ): List<Long> {
            if (ids.isEmpty()) return emptyList()
            return withContext(Dispatchers.IO) {
                // One transaction around the N CAS updates: withTransaction commits on
                // normal completion and rolls back on any exception (the manual
                // begin/setSuccessful/end triple is deprecated).
                db.withTransaction {
                    ids.filter { id ->
                        transitionSmsStatus(id, expectedStatuses, status, update)
                    }
                }
            }
        }

        suspend fun getStalePending(staleBefore: Long): List<Sms> =
            safeDb("getStalePending", emptyList()) { dao.getStalePending(staleBefore, SmsStatus.PENDING.name) }

        suspend fun getDueRetrying(now: Long): List<Sms> =
            safeDb("getDueRetrying", emptyList()) { dao.getDueRetrying(now, SmsStatus.RETRYING.name) }

        suspend fun getStaleSending(staleBefore: Long): List<Sms> =
            safeDb("getStaleSending", emptyList()) { dao.getStaleSending(staleBefore, SmsStatus.SENDING.name) }

        // Deletes exactly the History scope (see SmsDao.deleteHistorySms): the in-flight
        // queue rows survive a "clear history".
        suspend fun deleteHistorySms(): Int =
            safeDb("deleteHistorySms", 0) { dao.deleteHistorySms(statusNames(SmsListScope.HISTORY.statuses)) }

        // The single retention-delete core shared by the daily worker and the "run now"
        // button, so the two cannot compute different cutoffs. retentionDays <= 0 deletes
        // nothing: a cutoff of "now" would delete every row.
        suspend fun deleteExpired(retentionDays: Long): Int =
            if (retentionDays <= 0) {
                0
            } else {
                safeDb("deleteExpired", 0) {
                    dao.deleteOlderThan(
                        DateUtil.daysAgoCutoff(System.currentTimeMillis(), retentionDays),
                        statusNames(SmsStatus.IN_FLIGHT),
                    )
                }
            }

        // In-flight rows are never deleted (see SmsDao.deleteSmsByIds): the returned
        // count is the rows actually deleted, less than ids.size means some were skipped.
        // The ids are chunked to stay under the SQLite variable limit (see getSmsByIds);
        // the per-chunk counts are summed (a delete has no order to preserve). A single
        // statement is atomic on its own; several chunks share one transaction (like
        // transitionSmsStatusMany) so a mid-batch failure deletes nothing, not half. The
        // body runs on IO: Room forbids transactions on the main thread, and the UI
        // callers invoke this straight from viewModelScope. No caller runs this inside
        // an existing transaction, so the commit/rollback boundaries are exactly the
        // batch's.
        suspend fun deleteSmsByIds(ids: List<Long>): Int =
            safeDb("deleteSmsByIds", 0) {
                val chunks = ids.distinct().chunked(AppConfig.MAX_BATCH_IDS)
                if (chunks.size <= 1) {
                    chunks.firstOrNull()?.let { dao.deleteSmsByIds(it, statusNames(SmsStatus.IN_FLIGHT)) } ?: 0
                } else {
                    withContext(Dispatchers.IO) {
                        // One transaction across the chunks: a mid-batch failure deletes
                        // nothing, not half (withTransaction rolls back on exception).
                        db.withTransaction {
                            chunks.sumOf { dao.deleteSmsByIds(it, statusNames(SmsStatus.IN_FLIGHT)) }
                        }
                    }
                }
            }

        private suspend fun <T> safeDb(
            name: String,
            fallback: T,
            block: suspend () -> T,
        ): T =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(tag, "$name failed: ${e.javaClass.simpleName}: ${e.message}", e)
                fallback
            }
    }
