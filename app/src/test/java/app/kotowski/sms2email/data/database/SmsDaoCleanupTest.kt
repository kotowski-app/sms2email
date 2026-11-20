package app.kotowski.sms2email.data.database

import androidx.room.Room
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
class SmsDaoCleanupTest {
    private val cutoff = 1_000L
    private val oldTime = 500L
    private val recentTime = 2_000L

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

    // The whole table through the shared list query (no search, no cap): the union of
    // the two disjoint screen scopes covers every status.
    private suspend fun SmsDao.allRows(): List<SmsUiModel> = scopeRows(SmsListScope.QUEUE) + scopeRows(SmsListScope.HISTORY)

    private suspend fun SmsDao.scopeRows(scope: SmsListScope): List<SmsUiModel> =
        observeSms(
            statuses = scope.statuses.map { it.name },
            noSearch = true,
            likePattern = "",
            phoneLikePattern = "",
            contactNumbers = listOf("-"),
            simIds = listOf(0),
            limit = Int.MAX_VALUE,
        ).first()

    private fun sms(
        id: Int,
        status: SmsStatus,
        timeReceived: Long,
    ): Sms =
        Sms(
            id = id.toLong(),
            dedupKey = "key-$id",
            messageBody = "body $id",
            phoneNumber = "+10000000000",
            status = status,
            timeReceived = timeReceived,
        )

    @Test
    fun `deleteOlderThan deletes only finished statuses older than the cutoff`() =
        runTest {
            var nextId = 1
            for (status in SmsStatus.entries) {
                dao.insertSms(sms(nextId++, status, oldTime))
                dao.insertSms(sms(nextId++, status, recentTime))
            }

            val deleted = dao.deleteOlderThan(cutoff, SmsStatus.IN_FLIGHT.map { it.name })

            assertEquals(4, deleted)
            val remaining = dao.allRows()
            assertEquals(SmsStatus.entries.size + QUEUE_STATUSES.size, remaining.size)
            assertTrue(remaining.none { it.status !in QUEUE_STATUSES && it.timeReceived < cutoff })
            assertTrue(SmsStatus.entries.all { status -> remaining.any { it.status == status && it.timeReceived == recentTime } })
            for (status in QUEUE_STATUSES) {
                assertTrue(
                    remaining.any { it.status == status && it.timeReceived == oldTime },
                    "old $status row must survive cleanup",
                )
            }
        }

    @Test
    fun `deleteOlderThan deletes nothing when no row is older than the cutoff`() =
        runTest {
            var nextId = 1
            for (status in SmsStatus.entries) {
                dao.insertSms(sms(nextId++, status, recentTime))
            }

            val deleted = dao.deleteOlderThan(cutoff, SmsStatus.IN_FLIGHT.map { it.name })

            assertEquals(0, deleted)
            assertEquals(SmsStatus.entries.size, dao.allRows().size)
        }

    @Test
    fun `deleteHistorySms deletes the history statuses and keeps the in-flight queue`() =
        runTest {
            var nextId = 1
            for (status in SmsStatus.entries) {
                dao.insertSms(sms(nextId++, status, oldTime))
            }

            val deleted = dao.deleteHistorySms(SmsStatus.TERMINAL.map { it.name })

            // "Clear history" deletes exactly what the History screen shows; the in-flight
            // queue (PENDING/SENDING/RETRYING) must survive.
            assertEquals(SmsStatus.entries.size - QUEUE_STATUSES.size, deleted)
            val remaining = dao.allRows()
            assertEquals(QUEUE_STATUSES.size, remaining.size)
            assertTrue(
                remaining.all { it.status in QUEUE_STATUSES },
                "only in-flight rows must survive clear history, got: ${remaining.map { it.status }}",
            )
        }

    @Test
    fun `deleteSmsByIds keeps the in-flight queue rows and deletes the terminal ones`() =
        runTest {
            val ids =
                SmsStatus.entries
                    .map { status -> dao.insertSms(sms(status.ordinal + 1, status, oldTime)) }

            val deleted = dao.deleteSmsByIds(ids, SmsStatus.IN_FLIGHT.map { it.name })

            // The in-flight invariant (see SmsDao.deleteSmsByIds): a bare id-scoped delete
            // must never reach PENDING/SENDING/RETRYING rows, whatever the caller passed.
            assertEquals(SmsStatus.entries.size - QUEUE_STATUSES.size, deleted)
            val remaining = dao.allRows()
            assertEquals(QUEUE_STATUSES.size, remaining.size)
            assertTrue(
                remaining.all { it.status in QUEUE_STATUSES },
                "only in-flight rows must survive the id-scoped delete, got: ${remaining.map { it.status }}",
            )
        }

    @Test
    fun `deleteSmsByIds deletes nothing and returns 0 for an empty id list`() =
        runTest {
            dao.insertSms(sms(1, SmsStatus.SENT, oldTime))

            val deleted = dao.deleteSmsByIds(emptyList(), SmsStatus.IN_FLIGHT.map { it.name })

            assertEquals(0, deleted)
            assertEquals(1, dao.allRows().size)
        }

    companion object {
        private val QUEUE_STATUSES = SmsStatus.IN_FLIGHT
    }
}
