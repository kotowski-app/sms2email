package app.kotowski.sms2email.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Locale

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["dedupKey"], unique = true),
        Index(value = ["timeReceived"]),
        // The duplicate check (both the live and the import path) is a range match on the
        // folded sender/body pair (SmsDao.getSmsBySearchInWindow); the composite index
        // keeps it an index scan.
        Index(value = ["searchPhone", "searchBody"]),
        // The status-first queries (the retry sweep, the queue-id fetches, the list
        // count, the history clear) would full-scan as the table grows for years; the
        // timeReceived suffix serves status+recency ranges.
        Index(value = ["status", "timeReceived"]),
    ],
)
data class Sms(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val dedupKey: String? = null,
    val messageBody: String,
    val phoneNumber: String,
    val retryCount: Int = 0,
    val simId: Int = -1,
    val status: SmsStatus = SmsStatus.PENDING,
    val statusDetails: String? = null,
    val timeFirstSendAttempt: Long? = null,
    val timeLastSendAttempt: Long? = null,
    val timeNextSendAttempt: Long? = null,
    val timeReceived: Long = System.currentTimeMillis(),
    // Search facets, pre-folded at construction (the Kotlin defaults reference the
    // constructor params, so every construction site gets them for free). searchBody and
    // searchPhone are Unicode-lowercased (the built-in SQLite LIKE/LOWER fold case
    // ASCII-only, and the app is i18n — e.g. Russian); searchPhone is reduced to
    // letters/digits so sender search is separator-insensitive (a query typed with
    // spaces, dashes or "+" hits the same rows as without them); searchNumber is the
    // E.164 form of the sender (the display path's key), so a name-matched contact
    // number compares equal to the stored number regardless of country-code /
    // trunk-prefix / leading-zero formatting. The insert site (SmsRepository) folds it
    // through the shared normalizer (ContactResolver.toSearchNumber); the constructor
    // default is the digits-only fallback for direct construction.
    val searchBody: String = foldSearchBody(messageBody),
    val searchNumber: String = phoneNumber.filter { it.isDigit() },
    val searchPhone: String = foldSearchPhone(phoneNumber),
)

// The search fold of a raw body (the stored searchBody): Unicode-lowercased. The import
// duplicate check (SmsRepository) folds the row's body the same way before querying.
internal fun foldSearchBody(body: String): String = body.lowercase(Locale.ROOT)

// The search fold of a raw sender address (the stored searchPhone): Unicode-lowercased
// and reduced to letters/digits. The import duplicate check folds the row's address the
// same way before querying.
internal fun foldSearchPhone(phone: String): String = phone.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

enum class SmsStatus {
    IMPORTED,
    PENDING,
    SENDING,
    SENT,
    RETRYING,
    FAILED,
    IGNORED,
    ;

    companion object {
        // Source statuses for a user-initiated (re-)forward: SENT is re-forwardable by design,
        // SENDING is in-flight and must stay untouched. IGNORED is forwardable so messages
        // parked while forwarding was off (or that arrived while off) can be re-sent from the
        // UI.
        val MANUAL_FORWARDABLE = listOf(IMPORTED, PENDING, RETRYING, FAILED, SENT, IGNORED)

        // In-flight: the message has not been delivered yet. These rows must never be
        // deleted by a history clear, a retention sweep or a manual delete (the guards
        // bind these names into SQL), and they are exactly the Queue screen's partition.
        val IN_FLIGHT = listOf(PENDING, SENDING, RETRYING)

        // Terminal: the message will never be sent again on its own. With IN_FLIGHT this
        // is the full partition of every status — exactly the History screen's scope.
        val TERMINAL = listOf(SENT, FAILED, IGNORED, IMPORTED)

        // The rows a worker/sweep will still send on its own: PENDING and RETRYING. SENDING
        // is in-flight (let it finish) and FAILED is not auto-sent, so neither is parked
        // here when forwarding is toggled off.
        val AUTO_SENDABLE = listOf(PENDING, RETRYING)
    }
}

// The status partition of a list screen: the two scopes are disjoint and cover every
// status, so a row is either in the Queue or in the History, never both. Forwarding a
// History row moves it into the Queue (PENDING/RETRYING); a delivered or failed send
// brings it back to the History. `statuses` is the single source the list queries bind
// into their IN clause (SmsDao), so the SQL can never drift from the enum.
enum class SmsListScope(
    val statuses: List<SmsStatus>,
) {
    // In-flight: the queue screen's scope. FAILED is terminal, not in-flight — it lives
    // in the History, where it can be re-forwarded.
    QUEUE(SmsStatus.IN_FLIGHT),

    // Terminal: SENT, FAILED, IGNORED, IMPORTED — the history screen's scope.
    HISTORY(SmsStatus.TERMINAL),
}
