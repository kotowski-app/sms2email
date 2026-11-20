package app.kotowski.sms2email.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import kotlinx.coroutines.flow.Flow

@Dao
interface SmsDao {
    // The newest rows, capped by `limit`, observed live. One shared query for both list
    // screens: `statuses` is the screen's status partition (SmsListScope.statuses, bound
    // as enum names — the scopes are disjoint, a row is in exactly one of them),
    // `noSearch = 1` disables the search predicates (empty query). The search matches
    // the pre-folded message body (searchBody), the pre-folded, separator-stripped
    // sender address (searchPhone — the query is stripped the same way, SmsSearch),
    // the E.164-normalized numbers of the name-matched contacts (searchNumber, the
    // digits-only fold when a number does not normalize) and the name-matched SIM
    // slots — all full-table, nothing is filtered in memory. The id tiebreaker keeps the
    // order deterministic when two rows share a timeReceived (the timeReceived index is
    // non-unique). statuses/contactNumbers/simIds must never be empty (the repository
    // binds the scope's statuses and never-matching sentinels: an empty IN clause is a
    // SQL syntax error).
    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT * FROM messages
        WHERE status IN (:statuses)
          AND (:noSearch = 1
                OR searchBody LIKE :likePattern ESCAPE '\'
                OR searchPhone LIKE :phoneLikePattern ESCAPE '\'
                OR searchNumber IN (:contactNumbers)
                OR simId IN (:simIds))
        ORDER BY timeReceived DESC, id DESC
        LIMIT :limit
        """,
    )
    fun observeSms(
        statuses: List<String>,
        noSearch: Boolean,
        likePattern: String,
        phoneLikePattern: String,
        contactNumbers: List<String>,
        simIds: List<Int>,
        limit: Int,
    ): Flow<List<SmsUiModel>>

    // Exact row count for the list title (the loaded window is capped, so its size cannot
    // be the total). Unfiltered by the search on purpose: the count is the scope's total.
    @Query("SELECT COUNT(*) FROM messages WHERE status IN (:statuses)")
    fun observeCount(statuses: List<String>): Flow<Int>

    // The in-flight row ids: "Forward all" and "Cancel all" must cover the whole queue,
    // not just the loaded window. FAILED is not in the queue — it lives in the
    // History, where it can be re-forwarded.
    @Query("SELECT id FROM messages WHERE status IN (:statuses)")
    suspend fun getQueueIds(statuses: List<String>): List<Long>

    // The rows a worker/sweep will still send on its own: PENDING and RETRYING. SENDING is
    // in-flight (let it finish) and FAILED is not auto-sent, so neither is parked here.
    // Used to stop the queue when forwarding is toggled off.
    @Query("SELECT id FROM messages WHERE status IN (:statuses)")
    suspend fun getAutoSendableIds(statuses: List<String>): List<Long>

    // The rows in one status with their due times: the exact-wake-up re-arm reads the
    // RETRYING rows' stored next attempt from here.
    @Query("SELECT * FROM messages WHERE status = :status")
    suspend fun getRetrying(status: String): List<Sms>

    // The single row by id, observed live: the detail screen shows a row the capped list
    // window does not necessarily contain, and a status transition while it is open must
    // be reflected. Emits null while the row is absent (deleted).
    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM messages WHERE id = :id")
    fun observeSmsById(id: Long): Flow<SmsUiModel?>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getSmsById(id: Long): Sms?

    // Ordered like the list query (newest first): the forward paths re-fetch the batch
    // through this, and the send order must be the order the user sees the list in
    // (the callers' id lists are unordered — a Set or a rowid-ordered getQueueIds).
    @Query("SELECT * FROM messages WHERE id IN (:ids) ORDER BY timeReceived DESC, id DESC")
    suspend fun getSmsByIds(ids: List<Long>): List<Sms>

    // The duplicate check (SmsRepository.insertIfNew, both the live and the import
    // path): a row is a duplicate iff an existing row shares the folded sender and body
    // (the caller folds the row's values the way the Sms constructor stores them) and its
    // timeReceived falls within [lowerBound, upperBound] (both inclusive) — a range
    // match, so unlike the bucketed key it has no boundary misses.
    @Query(
        "SELECT * FROM messages WHERE searchPhone = :searchPhone AND searchBody = :searchBody " +
            "AND timeReceived BETWEEN :lowerBound AND :upperBound LIMIT 1",
    )
    suspend fun getSmsBySearchInWindow(
        searchPhone: String,
        searchBody: String,
        lowerBound: Long,
        upperBound: Long,
    ): Sms?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSms(sms: Sms): Long

    // statusDetails: null clears the stored detail. The other fields: null keeps the stored value.
    @Query(
        """
        UPDATE messages
        SET status = :status,
            statusDetails = :statusDetails,
            retryCount = COALESCE(:retryCount, retryCount),
            timeFirstSendAttempt = COALESCE(:timeFirstSendAttempt, timeFirstSendAttempt),
            timeLastSendAttempt = COALESCE(:timeLastSendAttempt, timeLastSendAttempt),
            timeNextSendAttempt = COALESCE(:timeNextSendAttempt, timeNextSendAttempt)
        WHERE id = :id AND status IN (:expectedStatuses)
        """,
    )
    suspend fun updateSmsStatusIf(
        id: Long,
        expectedStatuses: List<String>,
        status: String,
        statusDetails: String?,
        retryCount: Int?,
        timeFirstSendAttempt: Long?,
        timeLastSendAttempt: Long?,
        timeNextSendAttempt: Long?,
    ): Int

    @Query("SELECT * FROM messages WHERE status = :status AND COALESCE(timeFirstSendAttempt, timeReceived) < :staleBefore")
    suspend fun getStalePending(
        staleBefore: Long,
        status: String,
    ): List<Sms>

    @Query("SELECT * FROM messages WHERE status = :status AND (timeNextSendAttempt IS NULL OR timeNextSendAttempt <= :now)")
    suspend fun getDueRetrying(
        now: Long,
        status: String,
    ): List<Sms>

    @Query(
        "SELECT * FROM messages WHERE status = :status AND COALESCE(timeLastSendAttempt, timeFirstSendAttempt, timeReceived) < :staleBefore",
    )
    suspend fun getStaleSending(
        staleBefore: Long,
        status: String,
    ): List<Sms>

    // Deletes exactly what the History screen shows (the HISTORY scope): the in-flight
    // queue rows (PENDING/SENDING/RETRYING) are never deleted — the message has not been
    // delivered yet and must stay until it is sent or the retry period fails it. FAILED
    // rows are deleted: they are a visible history entry, and the queue is cleared
    // deliberately through "Cancel all" (which parks, never deletes).
    @Query("DELETE FROM messages WHERE status IN (:statuses)")
    suspend fun deleteHistorySms(statuses: List<String>): Int

    // Queue statuses (PENDING/SENDING/RETRYING) are never deleted here: the message has not
    // been delivered yet and must stay until it is sent or the retry period fails it.
    @Query(
        "DELETE FROM messages WHERE timeReceived < :beforeTimestamp " +
            "AND status NOT IN (:protectedStatuses)",
    )
    suspend fun deleteOlderThan(
        beforeTimestamp: Long,
        protectedStatuses: List<String>,
    ): Int

    // The in-flight queue rows (PENDING/SENDING/RETRYING) are never deleted: the message
    // has not been delivered yet and must stay until it is sent or the retry period
    // fails it. The guard lives in the SQL so no caller can bypass it, and it closes
    // the selection race (a row claimed SENDING between selection and confirm). Returns
    // the rows actually deleted, so a skip is visible to the caller.
    @Query(
        "DELETE FROM messages WHERE id IN (:ids) " +
            "AND status NOT IN (:protectedStatuses)",
    )
    suspend fun deleteSmsByIds(
        ids: List<Long>,
        protectedStatuses: List<String>,
    ): Int
}
