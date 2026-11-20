package app.kotowski.sms2email.data.database

import androidx.room.Room
import app.kotowski.sms2email.testing.TestPhone
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// The import duplicate check's range query (SmsDao.getSmsBySearchInWindow): the row is a
// duplicate iff an existing row shares the folded sender/body and its timeReceived falls
// within [providerDate - W, providerDate + W] (both bounds inclusive).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsDaoRangeDedupTest {
    private val providerDate = 1_700_000_000_000L
    private val window = 5_000L

    // The folded sender facet of TestPhone.SYNTHETIC_1 (digits only, no plus).
    private val syntheticNoPlus = TestPhone.SYNTHETIC_1.removePrefix("+")

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

    private fun row(
        id: Int,
        phoneNumber: String,
        body: String,
        timeReceived: Long,
    ): Sms =
        Sms(
            id = id.toLong(),
            dedupKey = "key-$id",
            messageBody = body,
            phoneNumber = phoneNumber,
            timeReceived = timeReceived,
        )

    @Test
    fun `rows within the window are found`() =
        runTest {
            dao.insertSms(row(1, TestPhone.SYNTHETIC_1, "hello", providerDate - 4_999L))
            dao.insertSms(row(2, TestPhone.SYNTHETIC_1, "hello", providerDate + 4_999L))

            assertNotNull(dao.getSmsBySearchInWindow(syntheticNoPlus, "hello", providerDate - window, providerDate + window))
        }

    @Test
    fun `rows outside the window are not found`() =
        runTest {
            dao.insertSms(row(1, TestPhone.SYNTHETIC_1, "hello", providerDate - window - 1L))
            dao.insertSms(row(2, TestPhone.SYNTHETIC_1, "hello", providerDate + window + 1L))

            assertNull(dao.getSmsBySearchInWindow(syntheticNoPlus, "hello", providerDate - window, providerDate + window))
        }

    @Test
    fun `both window bounds are inclusive`() =
        runTest {
            dao.insertSms(row(1, TestPhone.SYNTHETIC_1, "hello", providerDate - window))
            dao.insertSms(row(2, TestPhone.SYNTHETIC_1, "hello", providerDate + window))

            // A zero-width window at each bound still matches that boundary row.
            assertNotNull(dao.getSmsBySearchInWindow(syntheticNoPlus, "hello", providerDate - window, providerDate - window))
            assertNotNull(dao.getSmsBySearchInWindow(syntheticNoPlus, "hello", providerDate + window, providerDate + window))
        }

    @Test
    fun `a different sender is not a match`() =
        runTest {
            dao.insertSms(row(1, "+20000000000", "hello", providerDate))

            assertNull(dao.getSmsBySearchInWindow(syntheticNoPlus, "hello", providerDate - window, providerDate + window))
        }

    @Test
    fun `a different body is not a match`() =
        runTest {
            dao.insertSms(row(1, TestPhone.SYNTHETIC_1, "goodbye", providerDate))

            assertNull(dao.getSmsBySearchInWindow(syntheticNoPlus, "hello", providerDate - window, providerDate + window))
        }

    @Test
    fun `the match is on the stored folded values`() =
        runTest {
            // The row is stored with the raw values; the stored search facets are the
            // folded ones, so a query with the folded values hits it (and a query with a
            // differently folded sender does not).
            dao.insertSms(row(1, "Test App", "OTP is 176414", providerDate))

            assertNotNull(
                dao.getSmsBySearchInWindow(
                    "testapp",
                    "otp is 176414",
                    providerDate - window,
                    providerDate + window,
                ),
            )
            assertNull(
                dao.getSmsBySearchInWindow(
                    "test app",
                    "otp is 176414",
                    providerDate - window,
                    providerDate + window,
                ),
            )
        }

    @Test
    fun `the query returns a single row`() =
        runTest {
            dao.insertSms(row(1, TestPhone.SYNTHETIC_1, "hello", providerDate - 1_000L))
            dao.insertSms(row(2, TestPhone.SYNTHETIC_1, "hello", providerDate + 1_000L))

            // LIMIT 1: at most one row, even when several fall in the window.
            assertNotNull(dao.getSmsBySearchInWindow(syntheticNoPlus, "hello", providerDate - window, providerDate + window))
        }
}
