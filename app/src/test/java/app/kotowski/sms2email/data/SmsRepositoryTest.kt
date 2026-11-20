package app.kotowski.sms2email.data

import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.DedupLevel
import app.kotowski.sms2email.data.database.DedupUtil
import app.kotowski.sms2email.data.database.IngestResult
import app.kotowski.sms2email.data.database.Sms
import app.kotowski.sms2email.data.database.Sms2EmailDatabase
import app.kotowski.sms2email.data.database.SmsDao
import app.kotowski.sms2email.data.database.SmsListScope
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsSearch
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.StatusUpdate
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.testing.smsModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsRepositoryTest {
    @Mock
    private lateinit var smsDao: SmsDao

    @Mock
    private lateinit var smsDb: Sms2EmailDatabase

    @Mock
    private lateinit var contactResolver: ContactResolver

    private lateinit var closeable: AutoCloseable

    private lateinit var repository: SmsRepository

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        // The mock's default for toSearchNumber is null (a non-null Kotlin return
        // Mockito cannot honor): answer with the digits-only fold, the production
        // fallback for unparseable numbers, so the insertIfNew tests keep the
        // digits-only facet without stubbing.
        whenever(contactResolver.toSearchNumber(any()))
            .thenAnswer { invocation -> (invocation.arguments[0] as String).filter { it.isDigit() } }
        // withTransaction runs its block on the db's transaction executor and tracks the
        // nesting level through a ThreadLocal: a direct executor keeps it inline so the
        // mock's begin/setSuccessful/end calls stay verifiable.
        whenever(smsDb.transactionExecutor).thenReturn(Executor { it.run() })
        whenever(smsDb.suspendingTransactionContext).thenReturn(ThreadLocal())
        repository = SmsRepository(smsDao, smsDb, contactResolver)
    }

    private suspend fun stubDaoRows(rows: Int) {
        whenever(
            smsDao.updateSmsStatusIf(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()),
        ).thenReturn(rows)
    }

    @Test
    fun `transitionSmsStatus returns true when dao updates a row`() =
        runTest {
            stubDaoRows(1)

            assertTrue(repository.transitionSmsStatus(1L, listOf(SmsStatus.PENDING), SmsStatus.SENDING))
        }

    @Test
    fun `transitionSmsStatus returns false when dao updates zero rows`() =
        runTest {
            stubDaoRows(0)

            assertFalse(repository.transitionSmsStatus(1L, listOf(SmsStatus.PENDING), SmsStatus.SENDING))
        }

    @Test
    fun `transitionSmsStatus returns false and does not throw when dao throws`() =
        runTest {
            whenever(
                smsDao.updateSmsStatusIf(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()),
            ).thenThrow(RuntimeException("db down"))

            assertFalse(repository.transitionSmsStatus(1L, listOf(SmsStatus.PENDING), SmsStatus.SENDING))
        }

    @Test
    fun `transitionSmsStatus rethrows CancellationException from the dao`() =
        runTest {
            whenever(
                smsDao.updateSmsStatusIf(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()),
            ).thenThrow(CancellationException("cancelled"))

            assertFailsWith<CancellationException> {
                repository.transitionSmsStatus(1L, listOf(SmsStatus.PENDING), SmsStatus.SENDING)
            }
        }

    @Test
    fun `safeDb rethrows CancellationException from the dao`() =
        runTest {
            whenever(smsDao.getSmsById(7L)).thenThrow(CancellationException("cancelled"))

            assertFailsWith<CancellationException> {
                repository.getSmsById(7L)
            }
        }

    @Test
    fun `transitionSmsStatus returns false for empty expectedStatuses without touching the dao`() =
        runTest {
            assertFalse(
                repository.transitionSmsStatus(1L, emptyList(), SmsStatus.SENDING),
            )
            verify(
                smsDao,
                never(),
            ).updateSmsStatusIf(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        }

    @Test
    fun `transitionSmsStatus passes mapped enum names and field values to dao`() =
        runTest {
            stubDaoRows(1)

            repository.transitionSmsStatus(
                id = 42L,
                expectedStatuses = listOf(SmsStatus.PENDING, SmsStatus.RETRYING),
                status = SmsStatus.SENT,
                update =
                    StatusUpdate(
                        statusDetails = "sent ok",
                        retryCount = 3,
                        timeFirstSendAttempt = 111L,
                        timeLastSendAttempt = 222L,
                        timeNextSendAttempt = 333L,
                    ),
            )

            verify(smsDao).updateSmsStatusIf(
                eq(42L),
                eq(listOf("PENDING", "RETRYING")),
                eq("SENT"),
                eq("sent ok"),
                eq(3),
                eq(111L),
                eq(222L),
                eq(333L),
            )
        }

    @Test
    fun `transitionSmsStatus passes null optional fields through as null`() =
        runTest {
            stubDaoRows(1)

            repository.transitionSmsStatus(1L, listOf(SmsStatus.SENDING), SmsStatus.SENT)

            verify(smsDao).updateSmsStatusIf(
                eq(1L),
                eq(listOf("SENDING")),
                eq("SENT"),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
            )
        }

    @Test
    fun `transitionSmsStatusMany returns only the ids whose transition succeeded`() =
        runTest {
            whenever(
                smsDao.updateSmsStatusIf(eq(1L), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()),
            ).thenReturn(1)
            whenever(
                smsDao.updateSmsStatusIf(eq(2L), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()),
            ).thenReturn(0)
            whenever(
                smsDao.updateSmsStatusIf(eq(3L), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()),
            ).thenReturn(1)

            val winners = repository.transitionSmsStatusMany(listOf(1L, 2L, 3L), listOf(SmsStatus.PENDING), SmsStatus.SENDING)

            assertEquals(listOf(1L, 3L), winners)
            // Every id goes through the per-id CAS: the loser is filtered out, not skipped.
            verify(smsDao)
                .updateSmsStatusIf(eq(1L), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
            verify(smsDao)
                .updateSmsStatusIf(eq(2L), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
            verify(smsDao)
                .updateSmsStatusIf(eq(3L), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        }

    @Test
    fun `transitionSmsStatusMany returns an empty list for empty input without touching the dao`() =
        runTest {
            val winners = repository.transitionSmsStatusMany(emptyList(), listOf(SmsStatus.PENDING), SmsStatus.SENDING)

            assertTrue(winners.isEmpty())
            verify(
                smsDao,
                never(),
            ).updateSmsStatusIf(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        }

    @Test
    fun `observeSms binds never-matching sentinels for the empty facets`() =
        runTest {
            whenever(smsDao.observeSms(any(), any(), any(), any(), any(), any(), any())).thenReturn(flowOf(emptyList()))
            val numbers = argumentCaptor<List<String>>()
            val sims = argumentCaptor<List<Int>>()

            repository
                .observeSms(
                    scope = SmsListScope.HISTORY,
                    search = SmsSearch.forQuery("x", emptyList(), emptyList()),
                    limit = 21,
                ).first()

            verify(smsDao)
                .observeSms(
                    eq(SmsListScope.HISTORY.statuses.map { it.name }),
                    eq(false),
                    eq("%x%"),
                    eq("%x%"),
                    numbers.capture(),
                    sims.capture(),
                    eq(21),
                )
            // searchNumber is E.164 (a leading "+") or the digits-only fallback, so the
            // number sentinel must carry a non-digit ("" collides with rows whose address
            // has no digits, e.g. service names like "Bolt"); no SIM slot is 0.
            assertTrue(numbers.firstValue.all { it.any { c -> !c.isDigit() } })
            assertTrue(sims.firstValue.all { it <= 0 })
        }

    @Test
    fun `getSmsById returns null and does not throw when the dao throws`() =
        runTest {
            whenever(smsDao.getSmsById(7L)).thenThrow(RuntimeException("db down"))

            assertNull(repository.getSmsById(7L))
        }

    @Test
    fun `getSmsById returns the sms when the dao succeeds`() =
        runTest {
            val row = Sms(id = 7L, messageBody = "b", phoneNumber = "+7")
            whenever(smsDao.getSmsById(7L)).thenReturn(row)

            assertEquals(row, repository.getSmsById(7L))
        }

    @Test
    fun `getSmsByIds passes a small id list through as a single chunk`() =
        runTest {
            whenever(
                smsDao.getSmsByIds(eq(listOf(1L, 2L))),
            ).thenReturn(
                listOf(
                    Sms(id = 2L, messageBody = "b", phoneNumber = "+2", timeReceived = 200L),
                    Sms(id = 1L, messageBody = "b", phoneNumber = "+1", timeReceived = 100L),
                ),
            )

            val result = repository.getSmsByIds(listOf(1L, 2L))

            assertEquals(listOf(2L, 1L), result.map { it.id })
            verify(smsDao, times(1)).getSmsByIds(any())
        }

    @Test
    fun `getSmsByIds with no ids does not query the dao`() =
        runTest {
            val result = repository.getSmsByIds(emptyList())

            assertTrue(result.isEmpty())
            verify(smsDao, never()).getSmsByIds(any())
        }

    @Test
    fun `getSmsByIds chunks ids above the batch size and re-sorts the concat newest first`() =
        runTest {
            val ids = (1L..401L).toList()
            val firstChunk = ids.take(AppConfig.MAX_BATCH_IDS)
            val secondChunk = ids.drop(AppConfig.MAX_BATCH_IDS)
            // Each chunk answers in its own newest-first order; the times deliberately
            // interleave across chunks, so only a global re-sort yields the list order.
            whenever(
                smsDao.getSmsByIds(eq(firstChunk)),
            ).thenReturn(
                listOf(
                    Sms(id = 5L, messageBody = "b", phoneNumber = "+5", timeReceived = 100L),
                    Sms(id = 3L, messageBody = "b", phoneNumber = "+3", timeReceived = 300L),
                ),
            )
            whenever(
                smsDao.getSmsByIds(eq(secondChunk)),
            ).thenReturn(listOf(Sms(id = 401L, messageBody = "b", phoneNumber = "+401", timeReceived = 200L)))

            val result = repository.getSmsByIds(ids)

            // Re-sorted (timeReceived DESC, id DESC) across both chunks: 300, 200, 100.
            assertEquals(listOf(3L, 401L, 5L), result.map { it.id })
            verify(smsDao, times(2)).getSmsByIds(any())
        }

    @Test
    fun `getSmsByIds dedupes a repeated id so it cannot land in two chunks`() =
        runTest {
            // The same id repeated across the chunk boundary: the row must come back
            // once, whatever the chunking does.
            val ids = (1L..400L).toList() + 1L

            whenever(
                smsDao.getSmsByIds(eq((1L..400L).toList())),
            ).thenReturn(listOf(Sms(id = 1L, messageBody = "b", phoneNumber = "+1", timeReceived = 100L)))

            val result = repository.getSmsByIds(ids)

            assertEquals(listOf(1L), result.map { it.id })
            verify(smsDao, times(1)).getSmsByIds(any())
        }

    // The Balanced level's window (DedupLevel.BALANCED): the bucket is the level's,
    // not a shared constant.
    private val balancedWindow = 10_000L

    @Test
    fun `insertIfNew returns Inserted with the dao id and stores the computed dedup key`() =
        runTest {
            whenever(smsDao.getSmsBySearchInWindow(any(), any(), any(), any())).thenReturn(null)
            whenever(smsDao.insertSms(any())).thenReturn(55L)
            val captor = argumentCaptor<Sms>()

            val result =
                repository.insertIfNew(
                    "+1",
                    "hi",
                    1_700_000_000_000L,
                    2,
                    SmsStatus.IMPORTED,
                    DedupLevel.BALANCED,
                )

            assertEquals(IngestResult.Inserted(55L), result)
            verify(smsDao).insertSms(captor.capture())
            val stored = captor.firstValue
            assertEquals("+1", stored.phoneNumber)
            assertEquals("hi", stored.messageBody)
            assertEquals(1_700_000_000_000L, stored.timeReceived)
            assertEquals(SmsStatus.IMPORTED, stored.status)
            assertEquals(2, stored.simId)
            assertEquals(DedupUtil.computeDedupKey("+1", 1_700_000_000_000L, "hi", balancedWindow), stored.dedupKey)
        }

    @Test
    fun `insertIfNew stores the E164 searchNumber facet when the number normalizes`() =
        runTest {
            whenever(smsDao.getSmsBySearchInWindow(any(), any(), any(), any())).thenReturn(null)
            whenever(smsDao.insertSms(any())).thenReturn(55L)
            whenever(contactResolver.toSearchNumber("+1 555 010 0000")).thenReturn("+15550100000")
            val captor = argumentCaptor<Sms>()

            repository.insertIfNew("+1 555 010 0000", "hi", 1_700_000_000_000L, -1, SmsStatus.PENDING, DedupLevel.BALANCED)

            verify(smsDao).insertSms(captor.capture())
            assertEquals("+15550100000", captor.firstValue.searchNumber)
        }

    @Test
    fun `insertIfNew falls back to the digits-only searchNumber when the number does not normalize`() =
        runTest {
            // A digit-free service address ("Bolt") keeps the empty digits-only
            // facet — why the empty-facet sentinel binds a non-digit ("-"), not "".
            whenever(smsDao.getSmsBySearchInWindow(any(), any(), any(), any())).thenReturn(null)
            whenever(smsDao.insertSms(any())).thenReturn(55L)
            whenever(contactResolver.toSearchNumber("Bolt")).thenReturn("")
            val captor = argumentCaptor<Sms>()

            repository.insertIfNew("Bolt", "hi", 1_700_000_000_000L, -1, SmsStatus.PENDING, DedupLevel.BALANCED)

            verify(smsDao).insertSms(captor.capture())
            assertEquals("", captor.firstValue.searchNumber)
        }

    @Test
    fun `insertIfNew returns Duplicate when the pre-check finds a row within the window`() =
        runTest {
            whenever(
                smsDao.getSmsBySearchInWindow(any(), any(), any(), any()),
            ).thenReturn(Sms(id = 7, messageBody = "old", phoneNumber = "+1"))
            whenever(smsDao.insertSms(any())).thenReturn(55L)

            val result =
                repository.insertIfNew(
                    "+1",
                    "hi",
                    1_700_000_000_000L,
                    -1,
                    SmsStatus.PENDING,
                    DedupLevel.BALANCED,
                )

            assertEquals(IngestResult.Duplicate, result)
            verify(smsDao, never()).insertSms(any())
        }

    @Test
    fun `insertIfNew returns Duplicate when the unique index rejects the insert`() =
        runTest {
            whenever(smsDao.getSmsBySearchInWindow(any(), any(), any(), any())).thenReturn(null)
            whenever(smsDao.insertSms(any())).thenReturn(-1L)

            val result =
                repository.insertIfNew(
                    "+1",
                    "hi",
                    1_700_000_000_000L,
                    -1,
                    SmsStatus.PENDING,
                    DedupLevel.BALANCED,
                )

            assertEquals(IngestResult.Duplicate, result)
        }

    @Test
    fun `insertIfNew treats a redelivery 5ms apart as the same row (within the 10s window)`() =
        runTest {
            // The second timestamp is within ±window of the stored row, so the second
            // call must hit the pre-check's range match and skip the insert.
            whenever(smsDao.getSmsBySearchInWindow(any(), any(), any(), any()))
                .thenReturn(null, Sms(id = 7, messageBody = "hi", phoneNumber = "+1"))
            whenever(smsDao.insertSms(any())).thenReturn(7L)

            val first =
                repository.insertIfNew("+1", "hi", 1_600_000_000_000L, -1, SmsStatus.PENDING, DedupLevel.BALANCED)
            val second =
                repository.insertIfNew("+1", "hi", 1_600_000_000_005L, -1, SmsStatus.PENDING, DedupLevel.BALANCED)

            assertEquals(IngestResult.Inserted(7L), first)
            assertEquals(IngestResult.Duplicate, second)
            verify(smsDao, times(1)).insertSms(any())
        }

    @Test
    fun `insertIfNew treats same-body reports across a bucket edge as duplicates (range, not bucket)`() =
        runTest {
            // 6s apart and across a 10s bucket edge (160000000.4 -> 160000001.0): an
            // exact-key match would forward the second (different buckets), the range
            // match must not.
            whenever(smsDao.getSmsBySearchInWindow(any(), any(), any(), any()))
                .thenReturn(null, Sms(id = 7, messageBody = "hi", phoneNumber = "+1"))
            whenever(smsDao.insertSms(any())).thenReturn(7L)

            val first =
                repository.insertIfNew("+1", "hi", 1_600_000_004_000L, -1, SmsStatus.PENDING, DedupLevel.BALANCED)
            val second =
                repository.insertIfNew("+1", "hi", 1_600_000_010_000L, -1, SmsStatus.PENDING, DedupLevel.BALANCED)

            assertEquals(IngestResult.Inserted(7L), first)
            assertEquals(IngestResult.Duplicate, second)
            verify(smsDao, times(1)).insertSms(any())
        }

    @Test
    fun `insertIfNew stores the level window in the key, not a fixed bucket`() =
        runTest {
            // The same row under two different levels must carry different keys (the
            // window is the level's), so the level actually drives the bucket.
            whenever(smsDao.getSmsBySearchInWindow(any(), any(), any(), any())).thenReturn(null)
            whenever(smsDao.insertSms(any())).thenReturn(55L)
            val captor = argumentCaptor<Sms>()

            repository.insertIfNew("+1", "hi", 1_700_000_000_000L, -1, SmsStatus.PENDING, DedupLevel.AGGRESSIVE)
            repository.insertIfNew("+1", "hi", 1_700_000_000_000L, -1, SmsStatus.PENDING, DedupLevel.MINIMAL)

            verify(smsDao, times(2)).insertSms(captor.capture())
            val keys = captor.allValues.map { it.dedupKey }
            assertEquals(DedupUtil.computeDedupKey("+1", 1_700_000_000_000L, "hi", 60_000L), keys[0])
            assertEquals(DedupUtil.computeDedupKey("+1", 1_700_000_000_000L, "hi", 3_000L), keys[1])
            assertNotEquals(keys[0], keys[1])
        }

    @Test
    fun `insertIfNew with level Off runs the exact pre-check and stores an exact-timestamp key`() =
        runTest {
            // OFF is a zero window: the pre-check is an exact timeReceived match (ts..ts)
            // and the stored key is the exact timestamp, not a time bucket.
            whenever(smsDao.getSmsBySearchInWindow("1", "hi", 1_700_000_000_000L, 1_700_000_000_000L)).thenReturn(null)
            whenever(smsDao.insertSms(any())).thenReturn(55L)
            val captor = argumentCaptor<Sms>()

            val result =
                repository.insertIfNew("+1", "hi", 1_700_000_000_000L, -1, SmsStatus.PENDING, DedupLevel.OFF)

            assertEquals(IngestResult.Inserted(55L), result)
            verify(smsDao).getSmsBySearchInWindow("1", "hi", 1_700_000_000_000L, 1_700_000_000_000L)
            verify(smsDao).insertSms(captor.capture())
            assertEquals(DedupUtil.computeDedupKey("+1", 1_700_000_000_000L, "hi", 0L), captor.firstValue.dedupKey)
        }

    @Test
    fun `insertIfNew with level Off treats an exact-timestamp repeat as a duplicate`() =
        runTest {
            whenever(smsDao.getSmsBySearchInWindow(any(), any(), any(), any()))
                .thenReturn(null, Sms(id = 7, messageBody = "hi", phoneNumber = "+1"))
            whenever(smsDao.insertSms(any())).thenReturn(7L)

            val first =
                repository.insertIfNew("+1", "hi", 1_700_000_000_000L, -1, SmsStatus.PENDING, DedupLevel.OFF)
            val second =
                repository.insertIfNew("+1", "hi", 1_700_000_000_000L, -1, SmsStatus.PENDING, DedupLevel.OFF)

            assertEquals(IngestResult.Inserted(7L), first)
            assertEquals(IngestResult.Duplicate, second)
            verify(smsDao, times(1)).insertSms(any())
        }

    @Test
    fun `insertIfNew with level Off does not treat a different-timestamp report as a duplicate`() =
        runTest {
            // OFF is a zero window: only an identical timestamp is a duplicate; a different
            // timestamp (even 1ms apart) is a distinct row, not a false positive.
            whenever(smsDao.getSmsBySearchInWindow(any(), any(), any(), any())).thenReturn(null)
            whenever(smsDao.insertSms(any())).thenReturn(1L, 2L)

            val first =
                repository.insertIfNew("+1", "hi", 1_700_000_000_000L, -1, SmsStatus.PENDING, DedupLevel.OFF)
            val second =
                repository.insertIfNew("+1", "hi", 1_700_000_000_001L, -1, SmsStatus.PENDING, DedupLevel.OFF)

            assertEquals(IngestResult.Inserted(1L), first)
            assertEquals(IngestResult.Inserted(2L), second)
            verify(smsDao, times(2)).insertSms(any())
        }

    @Test
    fun `insertIfNew uses the ±window range pre-check on the folded sender and body and stores the bucketed key`() =
        runTest {
            whenever(smsDao.insertSms(any())).thenReturn(55L)
            whenever(
                smsDao.getSmsBySearchInWindow(
                    eq("1"),
                    eq("hi"),
                    eq(1_700_000_000_000L - balancedWindow),
                    eq(1_700_000_000_000L + balancedWindow),
                ),
            ).thenReturn(null)

            val result =
                repository.insertIfNew(
                    "+1",
                    "hi",
                    1_700_000_000_000L,
                    -1,
                    SmsStatus.IMPORTED,
                    DedupLevel.BALANCED,
                )

            // The row stores the bucketed key (the unique index stays the same-bucket
            // backstop behind the mutex-serialized check-then-act), and the pre-check is
            // the ±window range match on the folded sender/body.
            assertEquals(IngestResult.Inserted(55L), result)
            verify(smsDao).getSmsBySearchInWindow("1", "hi", 1_700_000_000_000L - balancedWindow, 1_700_000_000_000L + balancedWindow)
            val captor = argumentCaptor<Sms>()
            verify(smsDao).insertSms(captor.capture())
            assertEquals(DedupUtil.computeDedupKey("+1", 1_700_000_000_000L, "hi", balancedWindow), captor.firstValue.dedupKey)
        }

    @Test
    fun `insertIfNew on the import path returns Duplicate when the range match finds a row`() =
        runTest {
            whenever(
                smsDao.getSmsBySearchInWindow(any(), any(), any(), any()),
            ).thenReturn(Sms(id = 7, messageBody = "hi", phoneNumber = "+1"))
            whenever(smsDao.insertSms(any())).thenReturn(55L)

            val result =
                repository.insertIfNew(
                    "+1",
                    "hi",
                    1_700_000_000_000L,
                    -1,
                    SmsStatus.IMPORTED,
                    DedupLevel.BALANCED,
                )

            assertEquals(IngestResult.Duplicate, result)
            verify(smsDao, never()).insertSms(any())
        }

    @Test
    fun `insertIfNew on the import path folds the sender and body for the range match`() =
        runTest {
            // The row's raw values ("Test App", "OTP is 176414") must be folded the way the
            // Sms constructor stores them before the range query.
            whenever(
                smsDao.getSmsBySearchInWindow(eq("testapp"), eq("otp is 176414"), any(), any()),
            ).thenReturn(null)
            whenever(smsDao.insertSms(any())).thenReturn(55L)

            val result =
                repository.insertIfNew(
                    "Test App",
                    "OTP is 176414",
                    1_700_000_000_000L,
                    -1,
                    SmsStatus.IMPORTED,
                    DedupLevel.AGGRESSIVE,
                )

            assertEquals(IngestResult.Inserted(55L), result)
            verify(smsDao).getSmsBySearchInWindow("testapp", "otp is 176414", 1_700_000_000_000L - 60_000L, 1_700_000_000_000L + 60_000L)
        }

    @Test
    fun `insertIfNew with level Off on the import path treats a re-import as a duplicate`() =
        runTest {
            // The bug: a re-import reads the same provider DATE, so with OFF (zero window)
            // the exact-timestamp pre-check must catch it instead of re-inserting the row.
            whenever(smsDao.getSmsBySearchInWindow(any(), any(), any(), any()))
                .thenReturn(Sms(id = 7, messageBody = "hi", phoneNumber = "+1"))
            whenever(smsDao.insertSms(any())).thenReturn(55L)

            val result =
                repository.insertIfNew("+1", "hi", 1_700_000_000_000L, -1, SmsStatus.IMPORTED, DedupLevel.OFF)

            assertEquals(IngestResult.Duplicate, result)
            verify(smsDao, never()).insertSms(any())
        }

    @Test
    fun `insertIfNew with level Off on the import path runs the exact pre-check and stores an exact-timestamp key`() =
        runTest {
            whenever(smsDao.getSmsBySearchInWindow("1", "hi", 1_700_000_000_000L, 1_700_000_000_000L)).thenReturn(null)
            whenever(smsDao.insertSms(any())).thenReturn(55L)
            val captor = argumentCaptor<Sms>()

            val result =
                repository.insertIfNew("+1", "hi", 1_700_000_000_000L, -1, SmsStatus.IMPORTED, DedupLevel.OFF)

            assertEquals(IngestResult.Inserted(55L), result)
            verify(smsDao).getSmsBySearchInWindow("1", "hi", 1_700_000_000_000L, 1_700_000_000_000L)
            verify(smsDao).insertSms(captor.capture())
            assertEquals(DedupUtil.computeDedupKey("+1", 1_700_000_000_000L, "hi", 0L), captor.firstValue.dedupKey)
        }

    @Test
    fun `insertIfNew rethrows the last error after exhausting the insert retries`() =
        runTest {
            whenever(smsDao.getSmsBySearchInWindow(any(), any(), any(), any())).thenReturn(null)
            whenever(smsDao.insertSms(any())).thenThrow(RuntimeException("db down"))

            assertFailsWith<RuntimeException> {
                repository.insertIfNew("+1", "hi", 1_700_000_000_000L, -1, SmsStatus.PENDING, DedupLevel.BALANCED)
            }
            verify(smsDao, times(AppConfig.SMS_INSERT_MAX_ATTEMPTS)).insertSms(any())
        }

    @Test
    fun `insertIfNew retries the dao insert and returns Inserted when a retry succeeds`() =
        runTest {
            whenever(smsDao.getSmsBySearchInWindow(any(), any(), any(), any())).thenReturn(null)
            whenever(smsDao.insertSms(any())).thenThrow(RuntimeException("locked")).thenReturn(55L)

            val result =
                repository.insertIfNew("+1", "hi", 1_700_000_000_000L, -1, SmsStatus.PENDING, DedupLevel.BALANCED)

            assertEquals(IngestResult.Inserted(55L), result)
            verify(smsDao, times(2)).insertSms(any())
        }

    @Test
    fun `insertIfNew rethrows CancellationException from the insert without retrying`() =
        runTest {
            whenever(smsDao.getSmsBySearchInWindow(any(), any(), any(), any())).thenReturn(null)
            whenever(smsDao.insertSms(any())).thenThrow(CancellationException("cancelled"))

            assertFailsWith<CancellationException> {
                repository.insertIfNew("+1", "hi", 1_700_000_000_000L, -1, SmsStatus.PENDING, DedupLevel.BALANCED)
            }
            verify(smsDao, times(1)).insertSms(any())
        }

    @Test
    fun `concurrent straddling twins dedup to a single row (mutex serializes pre-check+insert)`() =
        runBlocking {
            // Real threads: the interleaving of two concurrent ingests cannot happen on
            // the single-threaded test scheduler. The DAO mock is a faithful fake over a
            // shared store: the pre-check is the ±window range match, the insert
            // enforces the unique dedupKey index (id <= 0 on a collision). The sleeps
            // widen the check-then-act window, so without the repository's mutex both
            // twins would pass the pre-check and insert — their timestamps straddle a
            // bucket edge, so the index never collides.
            val store = mutableListOf<Sms>()
            whenever(smsDao.getSmsBySearchInWindow(any(), any(), any(), any())).thenAnswer { inv ->
                Thread.sleep(150)
                val phone = inv.arguments[0] as String
                val body = inv.arguments[1] as String
                val lower = inv.arguments[2] as Long
                val upper = inv.arguments[3] as Long
                store.firstOrNull { it.searchPhone == phone && it.searchBody == body && it.timeReceived in lower..upper }
            }
            whenever(smsDao.insertSms(any())).thenAnswer { inv ->
                val sms = inv.arguments[0] as Sms
                if (store.any { it.dedupKey == sms.dedupKey }) {
                    0L
                } else {
                    store.add(sms)
                    store.size.toLong()
                }
            }
            // The searchNumber facet fold runs between the pre-check and the insert in
            // production; slow it down to widen the same window.
            whenever(contactResolver.toSearchNumber(any())).thenAnswer { inv ->
                Thread.sleep(150)
                (inv.arguments[0] as String).filter { it.isDigit() }
            }

            // BALANCED (10s window): 2ms apart across the 1_600_000_010_000 bucket edge —
            // different dedupKeys, yet inside each other's ±window.
            val t1 = 1_600_000_009_999L
            val t2 = 1_600_000_010_001L
            val first =
                async(Dispatchers.Default) {
                    repository.insertIfNew("+1", "hi", t1, -1, SmsStatus.PENDING, DedupLevel.BALANCED)
                }
            val second =
                async(Dispatchers.Default) {
                    repository.insertIfNew("+1", "hi", t2, -1, SmsStatus.PENDING, DedupLevel.BALANCED)
                }
            val results = listOf(first.await(), second.await())

            assertEquals(1, results.count { it is IngestResult.Inserted })
            assertEquals(1, results.count { it is IngestResult.Duplicate })
            assertEquals(1, store.size)
        }

    @Test
    fun `deleteSmsByIds passes the ids through and returns the deleted count`() =
        runTest {
            whenever(smsDao.deleteSmsByIds(eq(listOf(1L, 2L)), any())).thenReturn(1)

            assertEquals(1, repository.deleteSmsByIds(listOf(1L, 2L)))
        }

    @Test
    fun `deleteSmsByIds returns 0 and does not throw when the dao throws`() =
        runTest {
            whenever(smsDao.deleteSmsByIds(any(), any())).thenThrow(RuntimeException("db down"))

            assertEquals(0, repository.deleteSmsByIds(listOf(1L)))
        }

    @Test
    fun `deleteSmsByIds with no ids does not query the dao`() =
        runTest {
            assertEquals(0, repository.deleteSmsByIds(emptyList()))

            verify(smsDao, never()).deleteSmsByIds(any(), any())
        }

    @Test
    fun `deleteSmsByIds chunks ids above the batch size and sums the per-chunk counts`() =
        runTest {
            val ids = (1L..401L).toList()
            whenever(
                smsDao.deleteSmsByIds(eq(ids.take(AppConfig.MAX_BATCH_IDS)), any()),
            ).thenReturn(400)
            whenever(
                smsDao.deleteSmsByIds(eq(ids.drop(AppConfig.MAX_BATCH_IDS)), any()),
            ).thenReturn(1)

            assertEquals(401, repository.deleteSmsByIds(ids))
            verify(smsDao, times(2)).deleteSmsByIds(any(), any())
        }

    @Test
    fun `deleteSmsByIds returns the fallback when a chunk fails mid-batch`() =
        runTest {
            val ids = (1L..401L).toList()
            whenever(smsDao.deleteSmsByIds(any(), any())).thenReturn(400).thenThrow(RuntimeException("db down"))

            assertEquals(0, repository.deleteSmsByIds(ids))

            // Both chunks are attempted: the mid-batch failure discards the first chunk's count.
            verify(smsDao, times(2)).deleteSmsByIds(any(), any())
        }

    @Test
    fun `deleteExpired returns 0 without touching the dao when retention days is zero`() =
        runTest {
            assertEquals(0, repository.deleteExpired(0L))
            verify(smsDao, never()).deleteOlderThan(any(), any())
        }

    @Test
    fun `deleteExpired returns 0 without touching the dao when retention days is negative`() =
        runTest {
            assertEquals(0, repository.deleteExpired(-3L))
            verify(smsDao, never()).deleteOlderThan(any(), any())
        }

    @Test
    fun `deleteExpired deletes with the local-midnight cutoff of the day retention days before the day of now`() =
        runTest {
            whenever(smsDao.deleteOlderThan(any(), any())).thenReturn(7)
            val before = System.currentTimeMillis()

            val deleted = repository.deleteExpired(30L)

            val after = System.currentTimeMillis()
            val captor = argumentCaptor<Long>()
            verify(smsDao).deleteOlderThan(captor.capture(), any())
            // Pinned without calling daysAgoCutoff itself: the cutoff is a local midnight
            // (not an instant 30*24h before now) on the calendar day 30 days before the day
            // the call was made; before/after can span a local-midnight crossing, so either
            // of the two days is accepted.
            val zone = ZoneId.systemDefault()
            val local = Instant.ofEpochMilli(captor.firstValue).atZone(zone)
            assertEquals(0, local.hour)
            assertEquals(0, local.minute)
            assertEquals(0, local.second)
            assertEquals(0, local.nano)
            val firstDay =
                Instant
                    .ofEpochMilli(before)
                    .atZone(zone)
                    .toLocalDate()
                    .minusDays(30)
            val lastDay =
                Instant
                    .ofEpochMilli(after)
                    .atZone(zone)
                    .toLocalDate()
                    .minusDays(30)
            assertTrue(local.toLocalDate() in firstDay..lastDay)
            assertEquals(7, deleted)
        }

    @Test
    fun `deleteExpired passes the dao count through`() =
        runTest {
            whenever(smsDao.deleteOlderThan(any(), any())).thenReturn(42)

            assertEquals(42, repository.deleteExpired(1L))
        }

    @Test
    fun `deleteExpired returns 0 and does not throw when the dao throws`() =
        runTest {
            whenever(smsDao.deleteOlderThan(any(), any())).thenThrow(RuntimeException("db down"))

            assertEquals(0, repository.deleteExpired(30L))
        }

    @Test
    fun `getRetryingIds queries the RETRYING rows only and passes the ids through`() =
        runTest {
            whenever(smsDao.getAutoSendableIds(listOf(SmsStatus.RETRYING.name))).thenReturn(listOf(7L, 8L))

            assertEquals(listOf(7L, 8L), repository.getRetryingIds())
        }

    @Test
    fun `getRetryingIds degrades to an empty list when the dao throws`() =
        runTest {
            whenever(smsDao.getAutoSendableIds(any())).thenThrow(RuntimeException("db down"))

            assertTrue(repository.getRetryingIds().isEmpty())
        }

    @Test
    fun `getRetryingSms queries the RETRYING status and passes the rows through`() =
        runTest {
            val row = Sms(id = 7, messageBody = "b", phoneNumber = "+7", status = SmsStatus.RETRYING)
            whenever(smsDao.getRetrying(SmsStatus.RETRYING.name)).thenReturn(listOf(row))

            assertEquals(listOf(row), repository.getRetryingSms())
        }

    @Test
    fun `getRetryingSms degrades to an empty list when the dao throws`() =
        runTest {
            whenever(smsDao.getRetrying(any())).thenThrow(RuntimeException("db down"))

            assertTrue(repository.getRetryingSms().isEmpty())
        }

    @Test
    fun `observeCount delegates to the dao with status names`() =
        runTest {
            whenever(smsDao.observeCount(any())).thenReturn(kotlinx.coroutines.flow.flowOf(5))

            val count = repository.observeCount(SmsListScope.HISTORY).first()

            assertEquals(5, count)
            verify(smsDao).observeCount(SmsListScope.HISTORY.statuses.map { it.name })
        }

    @Test
    fun `observeSmsById delegates to the dao`() =
        runTest {
            val model = smsModel(id = 7L, status = SmsStatus.SENT, phoneNumber = "+7", messageBody = "hello")
            whenever(smsDao.observeSmsById(7L)).thenReturn(kotlinx.coroutines.flow.flowOf(model))

            val result = repository.observeSmsById(7L).first()

            assertEquals(model, result)
        }

    @Test
    fun `getQueueIds queries IN_FLIGHT ids and passes them through`() =
        runTest {
            whenever(smsDao.getQueueIds(statusNames(SmsStatus.IN_FLIGHT))).thenReturn(listOf(1L, 2L, 3L))

            assertEquals(listOf(1L, 2L, 3L), repository.getQueueIds())
        }

    private fun statusNames(statuses: List<SmsStatus>): List<String> = statuses.map { it.name }

    @Test
    fun `getQueueIds degrades to an empty list when the dao throws`() =
        runTest {
            whenever(smsDao.getQueueIds(any())).thenThrow(RuntimeException("db down"))

            assertTrue(repository.getQueueIds().isEmpty())
        }

    @Test
    fun `getAutoSendableIds queries auto-sendable statuses and passes ids through`() =
        runTest {
            whenever(
                smsDao.getAutoSendableIds(statusNames(SmsStatus.AUTO_SENDABLE)),
            ).thenReturn(listOf(10L, 20L))

            assertEquals(listOf(10L, 20L), repository.getAutoSendableIds())
        }

    @Test
    fun `getAutoSendableIds degrades to an empty list when the dao throws`() =
        runTest {
            whenever(smsDao.getAutoSendableIds(any())).thenThrow(RuntimeException("db down"))

            assertTrue(repository.getAutoSendableIds().isEmpty())
        }

    @Test
    fun `getStalePending queries stale pending rows and passes them through`() =
        runTest {
            val staleBefore = 1_700_000_000_000L
            val rows = listOf(Sms(id = 1L, messageBody = "b", phoneNumber = "+1", status = SmsStatus.PENDING))
            whenever(smsDao.getStalePending(staleBefore, SmsStatus.PENDING.name)).thenReturn(rows)

            assertEquals(rows, repository.getStalePending(staleBefore))
        }

    @Test
    fun `getStalePending degrades to an empty list when the dao throws`() =
        runTest {
            whenever(smsDao.getStalePending(any(), any())).thenThrow(RuntimeException("db down"))

            assertTrue(repository.getStalePending(1_700_000_000_000L).isEmpty())
        }

    @Test
    fun `getDueRetrying queries due retrying rows and passes them through`() =
        runTest {
            val now = 1_700_000_000_000L
            val rows = listOf(Sms(id = 1L, messageBody = "b", phoneNumber = "+1", status = SmsStatus.RETRYING))
            whenever(smsDao.getDueRetrying(now, SmsStatus.RETRYING.name)).thenReturn(rows)

            assertEquals(rows, repository.getDueRetrying(now))
        }

    @Test
    fun `getDueRetrying degrades to an empty list when the dao throws`() =
        runTest {
            whenever(smsDao.getDueRetrying(any(), any())).thenThrow(RuntimeException("db down"))

            assertTrue(repository.getDueRetrying(1_700_000_000_000L).isEmpty())
        }

    @Test
    fun `getStaleSending queries stale sending rows and passes them through`() =
        runTest {
            val staleBefore = 1_700_000_000_000L
            val rows = listOf(Sms(id = 1L, messageBody = "b", phoneNumber = "+1", status = SmsStatus.SENDING))
            whenever(smsDao.getStaleSending(staleBefore, SmsStatus.SENDING.name)).thenReturn(rows)

            assertEquals(rows, repository.getStaleSending(staleBefore))
        }

    @Test
    fun `getStaleSending degrades to an empty list when the dao throws`() =
        runTest {
            whenever(smsDao.getStaleSending(any(), any())).thenThrow(RuntimeException("db down"))

            assertTrue(repository.getStaleSending(1_700_000_000_000L).isEmpty())
        }

    @Test
    fun `deleteHistorySms deletes only history scope rows and passes the count through`() =
        runTest {
            whenever(
                smsDao.deleteHistorySms(statusNames(SmsListScope.HISTORY.statuses)),
            ).thenReturn(10)

            assertEquals(10, repository.deleteHistorySms())
        }

    @Test
    fun `deleteHistorySms degrades to 0 when the dao throws`() =
        runTest {
            whenever(smsDao.deleteHistorySms(any())).thenThrow(RuntimeException("db down"))

            assertEquals(0, repository.deleteHistorySms())
        }
}
