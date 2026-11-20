package app.kotowski.sms2email.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import app.kotowski.sms2email.di.AppModule
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
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Sms2EmailDatabaseTest {
    private lateinit var database: Sms2EmailDatabase

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), Sms2EmailDatabase::class.java)
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `database schema version is pinned to 1`() {
        assertEquals(1, database.openHelper.writableDatabase.version)
    }

    @Test
    fun `schema export produces version 1 schema file`() {
        val relativePath = "schemas/app.kotowski.sms2email.data.database.Sms2EmailDatabase/1.json"
        val schemaFile =
            File(relativePath).takeIf { it.exists() }
                ?: File("../$relativePath")

        assertTrue(schemaFile.exists(), "expected exported schema at $relativePath")
        val content = schemaFile.readText()
        assertTrue(content.contains("\"version\": 1"))
        assertTrue(content.contains("\"tableName\": \"messages\""))
    }

    @Test
    fun `messages table has the status_timeReceived index`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val fileName = "status_index_test.db"
            val file = context.getDatabasePath(fileName)
            file.delete()

            // One insert forces the lazy open, so the schema (with the index) exists on disk.
            val roomDb = Room.databaseBuilder(context, Sms2EmailDatabase::class.java, fileName).build()
            try {
                roomDb.smsDao().insertSms(
                    Sms(
                        phoneNumber = TestPhone.SYNTHETIC_1,
                        messageBody = "index check",
                        dedupKey = "index-check-pragma",
                    ),
                )
            } finally {
                roomDb.close()
            }

            val raw = rawDatabase(file.absolutePath)
            try {
                val indexNames =
                    raw.rawQuery("PRAGMA index_list('messages')", null).use { cursor ->
                        val nameIdx = cursor.getColumnIndexOrThrow("name")
                        val names = mutableListOf<String>()
                        while (cursor.moveToNext()) {
                            names.add(cursor.getString(nameIdx))
                        }
                        names
                    }
                assertTrue(
                    indexNames.contains("index_messages_status_timeReceived"),
                    "expected the status+timeReceived index, got $indexNames",
                )
            } finally {
                raw.close()
                file.delete()
            }
        }

    // The point of the index, not just its existence: the status-first sweep query must
    // plan an index search instead of a full table scan as the table grows for years.
    @Test
    fun `status-first sweep query plans the status index, not a full scan`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val fileName = "status_index_plan_test.db"
            val file = context.getDatabasePath(fileName)
            file.delete()

            val roomDb = Room.databaseBuilder(context, Sms2EmailDatabase::class.java, fileName).build()
            try {
                repeat(5) { i ->
                    roomDb.smsDao().insertSms(
                        Sms(
                            phoneNumber = TestPhone.SYNTHETIC_1,
                            messageBody = "index check $i",
                            dedupKey = "index-check-$i",
                        ),
                    )
                }
            } finally {
                roomDb.close()
            }

            val raw = rawDatabase(file.absolutePath)
            try {
                val planCursor =
                    raw.rawQuery(
                        "EXPLAIN QUERY PLAN SELECT id FROM messages " +
                            "WHERE status IN ('PENDING', 'SENDING', 'RETRYING')",
                        null,
                    )
                val plan =
                    planCursor.use { cursor ->
                        val detailIdx = cursor.getColumnIndexOrThrow("detail")
                        val details = mutableListOf<String>()
                        while (cursor.moveToNext()) {
                            details.add(cursor.getString(detailIdx))
                        }
                        details
                    }
                assertTrue(
                    plan.any { it.contains("index_messages_status_timeReceived") },
                    "expected the status index in the plan, got $plan",
                )
            } finally {
                raw.close()
                file.delete()
            }
        }

    // The whole table through the shared list query (no search, no cap): the union of
    // the two disjoint screen scopes covers every status.
    private suspend fun SmsDao.allRows(): List<SmsUiModel> =
        observeSms(
            statuses = SmsListScope.QUEUE.statuses.map { it.name },
            noSearch = true,
            likePattern = "",
            phoneLikePattern = "",
            contactNumbers = listOf("-"),
            simIds = listOf(0),
            limit = Int.MAX_VALUE,
        ).first() +
            observeSms(
                statuses = SmsListScope.HISTORY.statuses.map { it.name },
                noSearch = true,
                likePattern = "",
                phoneLikePattern = "",
                contactNumbers = listOf("-"),
                simIds = listOf(0),
                limit = Int.MAX_VALUE,
            ).first()

    @Test
    fun `dao works on pinned schema`() =
        runTest {
            val dao = database.smsDao()
            dao.insertSms(Sms(phoneNumber = TestPhone.SYNTHETIC_1, messageBody = "hi", dedupKey = "key-1"))

            assertEquals(1, dao.allRows().size)
        }

    @Test
    fun `observeSmsById emits the row, tracks updates and nulls on delete`() =
        runTest {
            val dao = database.smsDao()
            val id = dao.insertSms(Sms(phoneNumber = TestPhone.SYNTHETIC_1, messageBody = "hi", dedupKey = "key-obs"))
            val flow = dao.observeSmsById(id)

            val initial = flow.first()
            assertNotNull(initial)
            assertEquals("hi", initial.messageBody)

            dao.updateSmsStatusIf(id, listOf("PENDING"), "SENT", null, null, null, null, null)
            assertEquals(SmsStatus.SENT, flow.first()!!.status)

            dao.deleteSmsByIds(listOf(id), SmsStatus.IN_FLIGHT.map { it.name })
            assertNull(flow.first())

            assertNull(dao.observeSmsById(999L).first())
        }

    private fun rawDatabase(path: String): SQLiteDatabase {
        val factory: SQLiteDatabase.CursorFactory? = null
        return SQLiteDatabase.openDatabase(path, factory, Context.MODE_PRIVATE)
    }

    @Test
    fun `opening a database stored at a higher version does not wipe data`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val fileName = "old_version_test.db"
            val file = context.getDatabasePath(fileName)
            file.delete()

            val fresh = Room.databaseBuilder(context, Sms2EmailDatabase::class.java, fileName).build()
            fresh.smsDao().insertSms(Sms(phoneNumber = TestPhone.SYNTHETIC_3, messageBody = "keep me", dedupKey = "old-key"))
            fresh.close()

            val raw = rawDatabase(file.absolutePath)
            raw.setVersion(17)
            raw.close()

            val opened =
                runCatching {
                    val db = Room.databaseBuilder(context, Sms2EmailDatabase::class.java, fileName).build()
                    db.smsDao().allRows()
                    db
                }
            opened.getOrNull()?.close()
            assertNotNull(opened.exceptionOrNull(), "Room must refuse to open an unknown higher-version database")

            val check = rawDatabase(file.absolutePath)
            val count =
                check.rawQuery("SELECT COUNT(*) FROM messages", null).use { cursor ->
                    cursor.moveToFirst()
                    cursor.getInt(0)
                }
            check.close()
            file.delete()

            assertEquals(1, count, "rows from the existing database must survive, never be wiped")
        }

    @Test
    fun `AppModule provides a working database at schema version 1`() =
        runTest {
            val provided = AppModule.provideDatabase(RuntimeEnvironment.getApplication())
            try {
                assertEquals(1, provided.openHelper.writableDatabase.version)
                provided.smsDao().insertSms(Sms(phoneNumber = TestPhone.SYNTHETIC_2, messageBody = "hello", dedupKey = "key-2"))
                assertEquals(1, provided.smsDao().allRows().size)
            } finally {
                provided.close()
            }
        }
}
