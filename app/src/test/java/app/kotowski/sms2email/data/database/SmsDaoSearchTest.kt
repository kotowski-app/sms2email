package app.kotowski.sms2email.data.database

import androidx.room.Room
import app.kotowski.sms2email.testing.TestPhone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsDaoSearchTest {
    private lateinit var database: Sms2EmailDatabase
    private lateinit var dao: SmsDao

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), Sms2EmailDatabase::class.java)
                .build()
        dao = database.smsDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // The result set of the shared list query for a scope, the repository's sentinel
    // bindings for empty facets included. The search tests' rows default to SENT, a
    // History-scope status.
    private suspend fun rows(
        scope: SmsListScope = SmsListScope.HISTORY,
        query: String = "",
        contactNumbers: List<String> = emptyList(),
        simIds: List<Int> = emptyList(),
        limit: Int = Int.MAX_VALUE,
    ): List<SmsUiModel> {
        val search =
            if (query.isEmpty()) {
                SmsSearch.none()
            } else {
                SmsSearch.forQuery(query, contactNumbers, simIds)
            }
        return dao
            .observeSms(
                statuses = scope.statuses.map { it.name },
                noSearch = search.noSearch,
                likePattern = search.likePattern,
                phoneLikePattern = search.phoneLikePattern,
                // the repository's never-matching sentinels for empty facets (SmsRepository):
                // a non-digit number sentinel, since a searchNumber (E.164 or the
                // digits-only fallback) can never equal it ("" would match rows whose
                // address has no digits)
                contactNumbers = contactNumbers.ifEmpty { listOf("-") },
                simIds = simIds.ifEmpty { listOf(0) },
                limit = limit,
            ).first()
    }

    private suspend fun insert(
        id: Long,
        status: SmsStatus = SmsStatus.SENT,
        body: String = "body $id",
        number: String = TestPhone.SYNTHETIC_1,
        simId: Int = -1,
        timeReceived: Long,
        // The production insert site (SmsRepository) folds the E.164 facet through
        // ContactResolver; the tests pass it explicitly where the contract matters.
        searchNumber: String? = null,
    ): Sms {
        val sms =
            Sms(
                id = id,
                dedupKey = "key-$id",
                messageBody = body,
                phoneNumber = number,
                status = status,
                simId = simId,
                timeReceived = timeReceived,
            )
        val row = if (searchNumber != null) sms.copy(searchNumber = searchNumber) else sms
        dao.insertSms(row)
        return row
    }

    @Test
    fun `observeSms caps the result at the limit with the newest rows first`() =
        runTest {
            for (i in 1L..25) {
                insert(id = i, timeReceived = i * 1000L)
            }

            val rows = rows(limit = 10)

            assertEquals(10, rows.size)
            assertEquals((25L downTo 16).toList(), rows.map { it.id })
        }

    @Test
    fun `observeSms breaks ties on the same timeReceived by id descending`() =
        runTest {
            // id 7 is inserted first but must sort after id 9: same timeReceived, higher id wins.
            insert(id = 7, timeReceived = 5000L)
            insert(id = 9, timeReceived = 5000L)
            insert(id = 8, timeReceived = 6000L)

            val rows = rows()

            assertEquals(listOf(8L, 9L, 7L), rows.map { it.id })
        }

    @Test
    fun `queue scope shows only the in-flight statuses, FAILED excluded`() =
        runTest {
            for (status in SmsStatus.entries) {
                insert(id = status.ordinal + 1L, status = status, timeReceived = (status.ordinal + 1) * 1000L)
            }

            val queue = rows(scope = SmsListScope.QUEUE)

            // Newest first: RETRYING, SENDING, PENDING. FAILED is terminal — it lives in
            // the History, so the queue is strictly in-flight.
            assertEquals(listOf(5L, 3L, 2L), queue.map { it.id })
        }

    @Test
    fun `history scope shows only the terminal statuses, FAILED included`() =
        runTest {
            for (status in SmsStatus.entries) {
                insert(id = status.ordinal + 1L, status = status, timeReceived = (status.ordinal + 1) * 1000L)
            }

            val history = rows(scope = SmsListScope.HISTORY)

            // Newest first: IGNORED, FAILED, SENT, IMPORTED. A row is in exactly one
            // scope: the in-flight trio is the queue's only tenant.
            assertEquals(listOf(7L, 6L, 4L, 1L), history.map { it.id })
        }

    @Test
    fun `getAutoSendableIds returns only PENDING and RETRYING rows`() =
        runTest {
            for (status in SmsStatus.entries) {
                insert(id = status.ordinal + 1L, status = status, timeReceived = (status.ordinal + 1) * 1000L)
            }

            val ids = dao.getAutoSendableIds(SmsStatus.AUTO_SENDABLE.map { it.name })

            assertEquals(
                setOf((SmsStatus.PENDING.ordinal + 1).toLong(), (SmsStatus.RETRYING.ordinal + 1).toLong()),
                ids.toSet(),
            )
        }

    @Test
    fun `body search is case-insensitive for ascii text`() =
        runTest {
            insert(id = 1, body = "Hello World", timeReceived = 1000L)
            insert(id = 2, body = "goodbye", timeReceived = 2000L)

            assertEquals(listOf(1L), rows(query = "hello").map { it.id })
            assertEquals(listOf(1L), rows(query = "WORLD").map { it.id })
            assertTrue(rows(query = "nope").isEmpty())
        }

    @Test
    fun `body search is case-insensitive for cyrillic text`() =
        runTest {
            insert(id = 1, body = "Привет мир", timeReceived = 1000L)
            insert(id = 2, body = "пока", timeReceived = 2000L)

            assertEquals(listOf(1L), rows(query = "привет").map { it.id })
            assertEquals(listOf(1L), rows(query = "ПРИВЕТ").map { it.id })
            assertEquals(listOf(2L), rows(query = "Пока").map { it.id })
        }

    @Test
    fun `sender search is case-insensitive for cyrillic text`() =
        runTest {
            // Service-style alpha sender IDs as stored from displayOriginatingAddress;
            // the bodies deliberately do not contain the sender words.
            insert(id = 1, number = "Оплата", body = "payment sent", timeReceived = 1000L)
            insert(id = 2, number = "Тест Банк", body = "statement ready", timeReceived = 2000L)

            assertEquals(listOf(1L), rows(query = "оплата").map { it.id })
            assertEquals(listOf(1L), rows(query = "Оплата").map { it.id })
            assertEquals(listOf(2L), rows(query = "Тест").map { it.id })
        }

    @Test
    fun `sender search matches a mixed-case stored address in any query case`() =
        runTest {
            insert(id = 1, number = "Оплата", body = "payment sent", timeReceived = 1000L)

            assertEquals(listOf(1L), rows(query = "ОПЛАТА").map { it.id })
            assertEquals(listOf(1L), rows(query = "оплата").map { it.id })
        }

    @Test
    fun `number search matches the stored number regardless of case`() =
        runTest {
            insert(id = 1, number = TestPhone.SELF_E164, timeReceived = 1000L)
            insert(id = 2, number = TestPhone.FRIEND, timeReceived = 2000L)

            assertEquals(listOf(1L), rows(query = "5550100000").map { it.id })
        }

    @Test
    fun `sender search is separator-insensitive for the query`() =
        runTest {
            insert(id = 1, number = "+1 555 010 0000", timeReceived = 1000L)
            insert(id = 2, number = TestPhone.FRIEND, timeReceived = 2000L)

            assertEquals(listOf(1L), rows(query = "1 555 010-0").map { it.id })
            assertEquals(listOf(1L), rows(query = "(555) 010-0").map { it.id })
            assertEquals(listOf(1L), rows(query = "5550100000").map { it.id })
        }

    @Test
    fun `sender search is separator-insensitive for the stored address`() =
        runTest {
            insert(id = 1, number = "Test App", timeReceived = 1000L)
            insert(id = 2, number = "TestApp", timeReceived = 2000L)

            assertEquals(listOf(2L, 1L), rows(query = "testapp").map { it.id })
            assertEquals(listOf(2L, 1L), rows(query = "test app").map { it.id })
        }

    @Test
    fun `a query without alphanumerics matches no sender`() =
        runTest {
            insert(id = 1, number = "!!!", timeReceived = 1000L)
            insert(id = 2, number = TestPhone.SELF_E164, timeReceived = 2000L)

            // The stripped facet of "!!!" is empty; without the never-matching pattern
            // the phone facet would degrade to "%%" and leak every row in.
            assertTrue(rows(query = "!!!").isEmpty())
        }

    @Test
    fun `the separator stripping applies to the sender facet only, the body keeps the literal query`() =
        runTest {
            insert(id = 1, body = "snake case", timeReceived = 1000L)
            insert(id = 2, body = "snakecase", timeReceived = 2000L)

            assertEquals(listOf(2L), rows(query = "snakecase").map { it.id })
            assertEquals(listOf(1L), rows(query = "snake case").map { it.id })
        }

    @Test
    fun `contact facet matches on E164 regardless of formatting`() =
        runTest {
            // The row's sender "+1 555 010 0000" stored its E.164 facet at insert; the
            // contact provider stored the same number without the country code and the
            // name search normalized it to the same E.164.
            insert(id = 1, number = "+1 555 010 0000", searchNumber = "+15550100000", timeReceived = 1000L)
            insert(id = 2, number = "+1 555 010 0002", searchNumber = "+15550100002", timeReceived = 2000L)

            assertEquals(listOf(1L), rows(query = "john", contactNumbers = listOf("+15550100000")).map { it.id })
            assertTrue(rows(query = "john", contactNumbers = listOf("+15550100099")).isEmpty())
        }

    @Test
    fun `sim facet matches the slot id`() =
        runTest {
            insert(id = 1, simId = 1, timeReceived = 1000L)
            insert(id = 2, simId = 2, timeReceived = 2000L)

            assertEquals(listOf(2L), rows(query = "carrier", simIds = listOf(2)).map { it.id })
            assertTrue(rows(query = "carrier", simIds = listOf(3)).isEmpty())
        }

    @Test
    fun `facets combine with the body match with or`() =
        runTest {
            insert(id = 1, body = "alpha", number = TestPhone.SELF_E164, simId = 1, timeReceived = 1000L)
            insert(id = 2, body = "beta", number = TestPhone.FRIEND, simId = 2, timeReceived = 2000L)

            // The body "gamma" matches nothing, but the contact and sim facets do.
            val rows = rows(query = "gamma", contactNumbers = listOf(TestPhone.SELF_E164.removePrefix("+")), simIds = listOf(2))
            assertEquals(listOf(2L, 1L), rows.map { it.id })
        }

    @Test
    fun `the percent wildcard in a query matches literally`() =
        runTest {
            // Row 2's number must not contain "100": the separator-stripped phone facet
            // would match it through the digits and blur the body-only assertion.
            insert(id = 1, body = "100% done", timeReceived = 1000L)
            insert(id = 2, body = "almost done", number = TestPhone.FRIEND, timeReceived = 2000L)

            // Without escaping "100%" would be the LIKE pattern "%%" and match everything.
            assertEquals(listOf(1L), rows(query = "100%").map { it.id })
        }

    @Test
    fun `the underscore wildcard in a query matches literally`() =
        runTest {
            insert(id = 1, body = "snake_case", timeReceived = 1000L)
            insert(id = 2, body = "snakecase", timeReceived = 2000L)

            assertEquals(listOf(1L), rows(query = "snake_case").map { it.id })
        }

    @Test
    fun `search applies before the limit`() =
        runTest {
            for (i in 1L..25) {
                insert(id = i, body = if (i % 5L == 0L) "needle" else "hay", timeReceived = i * 1000L)
            }

            // 5 "needle" rows exist (5, 10, 15, 20, 25); the limit of 3 takes the newest.
            val rows = rows(query = "needle", limit = 3)
            assertEquals(listOf(25L, 20L, 15L), rows.map { it.id })
        }

    @Test
    fun `a row whose address has no digits does not match through the empty contact facet`() =
        runTest {
            // "Bolt"-style service addresses store an empty searchNumber: the empty
            // contact facet's sentinel must not collide with it and leak every row in.
            insert(id = 1, number = "Bolt", body = "code 4694", timeReceived = 1000L)
            insert(id = 2, number = TestPhone.SELF_E164, body = "other", timeReceived = 2000L)

            assertTrue(rows(query = "nothing matches this").isEmpty())
            assertEquals(listOf(1L), rows(query = "4694").map { it.id })
        }

    @Test
    fun `observeCount is the unfiltered total per scope`() =
        runTest {
            insert(id = 1, status = SmsStatus.PENDING, timeReceived = 1000L)
            insert(id = 2, status = SmsStatus.SENT, timeReceived = 2000L)
            insert(id = 3, status = SmsStatus.FAILED, timeReceived = 3000L)
            insert(id = 4, status = SmsStatus.IGNORED, timeReceived = 4000L)

            assertEquals(3, dao.observeCount(SmsListScope.HISTORY.statuses.map { it.name }).first())
            assertEquals(1, dao.observeCount(SmsListScope.QUEUE.statuses.map { it.name }).first())
        }

    @Test
    fun `getQueueIds returns the in-flight rows only`() =
        runTest {
            insert(id = 1, status = SmsStatus.PENDING, timeReceived = 1000L)
            insert(id = 2, status = SmsStatus.SENDING, timeReceived = 2000L)
            insert(id = 3, status = SmsStatus.SENT, timeReceived = 3000L)
            insert(id = 4, status = SmsStatus.IMPORTED, timeReceived = 4000L)
            insert(id = 5, status = SmsStatus.FAILED, timeReceived = 5000L)

            // The query has no ORDER BY: assert the id set, not an incidental order.
            // FAILED is not in the queue — it is re-forwardable from the History.
            assertEquals(setOf(1L, 2L), dao.getQueueIds(SmsStatus.IN_FLIGHT.map { it.name }).toSet())
        }

    @Test
    fun `getSmsByIds returns the rows in the list order not the request or rowid order`() =
        runTest {
            // Inserted in id order with shuffled times; id 4 is present but not requested.
            insert(id = 1, timeReceived = 3000L)
            insert(id = 2, timeReceived = 1000L)
            insert(id = 3, timeReceived = 2000L)
            insert(id = 4, status = SmsStatus.SENT, timeReceived = 4000L)

            // The forward paths pass unordered id lists; the batch must come back
            // newest first (timeReceived DESC, id DESC) like the list query.
            assertEquals(listOf(1L, 3L, 2L), dao.getSmsByIds(listOf(2L, 3L, 1L)).map { it.id })
        }
}
