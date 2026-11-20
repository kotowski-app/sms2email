package app.kotowski.sms2email.service

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.provider.Telephony
import app.kotowski.sms2email.DedupLevel
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.IngestResult
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.testing.TestPhone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.given
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

// importMessages calls Uri.parse, which needs the Android runtime.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SmsImporterTest {
    @Mock
    private lateinit var context: Context

    @Mock
    private lateinit var repository: SmsRepository

    @Mock
    private lateinit var prefs: EncryptedPrefs

    private lateinit var smsImporter: SmsImporter

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        given(context.getString(R.string.sms_unknown_sender)).willReturn("unknown")
        // The duplicate protection level the importer threads into the repository.
        given(prefs.load()).willReturn(AppSettings())
        smsImporter = SmsImporter(context, repository, prefs)
    }

    @Test
    fun `ImportProgress data class has expected fields`() {
        val progress = SmsImporter.ImportProgress(imported = 1, total = 10)
        assertEquals(1, progress.imported)
        assertEquals(10, progress.total)
    }

    @Test
    fun `ImportProgress default values`() {
        val progress = SmsImporter.ImportProgress()
        assertEquals(0, progress.imported)
        assertEquals(0, progress.skipped)
        assertEquals(0, progress.errors)
        assertEquals(0, progress.current)
        assertEquals(0, progress.total)
    }

    // importMessages issues three queries against the same resolver: the sub_id probe
    // (wide projection, sortOrder null), a cheap _ID count (single-column projection,
    // sortOrder null) and the real inbox scan (sortOrder "date ASC"). Routing them to
    // distinct cursors keeps close() verification unambiguous. The default probe reports
    // no sub_id column, mirroring the import's degradation path.
    private fun stubInboxQueries(
        countCursor: Cursor,
        importCursor: Cursor,
        probeCursor: Cursor = probeWithoutSubId(),
    ) {
        val resolver = mock<ContentResolver>()
        given(context.contentResolver).willReturn(resolver)
        given(
            resolver.query(
                any<Uri>(),
                any<Array<String>>(),
                anyOrNull<String>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
            ),
        ).willAnswer { inv ->
            val projection = inv.getArgument<Array<String>>(1)
            val sortOrder = inv.arguments[4] as String?
            when {
                projection.size == 1 -> countCursor
                sortOrder != null -> importCursor
                else -> probeCursor
            }
        }
    }

    private fun probeWithSubId(index: Int): Cursor {
        val probeCursor = mock<Cursor>()
        given(probeCursor.getColumnIndex("sub_id")).willReturn(index)
        return probeCursor
    }

    // The explicit -1 matters: an unstubbed mock answers 0, which the importer
    // would read as "column at 0".
    private fun probeWithoutSubId(): Cursor {
        val probeCursor = mock<Cursor>()
        given(probeCursor.getColumnIndex("sub_id")).willReturn(-1)
        return probeCursor
    }

    // Single-row inbox: address at 0, body at 1, date at 2. `subId` stubs the sub_id
    // column (probe + scan) so the import reads it; null leaves the column absent.
    private fun stubInboxRow(
        countCursor: Cursor,
        importCursor: Cursor,
        body: String,
        address: String? = TestPhone.FRIEND,
        subId: Int? = null,
    ) {
        given(countCursor.count).willReturn(1)
        given(importCursor.count).willReturn(1)
        given(importCursor.getColumnIndex(Telephony.Sms.ADDRESS)).willReturn(0)
        given(importCursor.getColumnIndex(Telephony.Sms.BODY)).willReturn(1)
        given(importCursor.getColumnIndex(Telephony.Sms.DATE)).willReturn(2)
        given(importCursor.moveToNext()).willReturn(true, false)
        // The address is nullable (null for a NULL column), so it is stubbed with the
        // doReturn/when style, which accepts null.
        `doReturn`(address).`when`(importCursor).getString(0)
        given(importCursor.getString(1)).willReturn(body)
        given(importCursor.getLong(2)).willReturn(1_700_000_000_000L)
        val probeCursor =
            if (subId != null) {
                given(importCursor.getColumnIndex("sub_id")).willReturn(3)
                given(importCursor.isNull(3)).willReturn(false)
                given(importCursor.getInt(3)).willReturn(subId)
                probeWithSubId(3)
            } else {
                probeWithoutSubId()
            }
        stubInboxQueries(countCursor, importCursor, probeCursor)
    }

    // N-row inbox: address at 0, body at 1, date at 2, one body per row in order.
    private fun stubInboxRows(
        countCursor: Cursor,
        importCursor: Cursor,
        bodies: List<String>,
        address: String? = TestPhone.FRIEND,
    ) {
        given(countCursor.count).willReturn(bodies.size)
        given(importCursor.count).willReturn(bodies.size)
        given(importCursor.getColumnIndex(Telephony.Sms.ADDRESS)).willReturn(0)
        given(importCursor.getColumnIndex(Telephony.Sms.BODY)).willReturn(1)
        given(importCursor.getColumnIndex(Telephony.Sms.DATE)).willReturn(2)
        var nextIndex = 0
        given(importCursor.moveToNext()).willAnswer {
            val proceed = nextIndex < bodies.size
            if (proceed) nextIndex++
            proceed
        }
        var bodyIndex = 0
        `doReturn`(address).`when`(importCursor).getString(0)
        given(importCursor.getString(1)).willAnswer { bodies[bodyIndex++] }
        given(importCursor.getLong(2)).willReturn(1_700_000_000_000L)
        stubInboxQueries(countCursor, importCursor)
    }

    @Test
    fun `import imports a row and closes the cursor`() {
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        stubInboxRow(countCursor, importCursor, "hello")

        runBlocking {
            given(repository.insertIfNew(any(), any(), any(), any(), any(), any())).willReturn(IngestResult.Inserted(1L))
        }

        val result = runBlocking { smsImporter.importMessages() }

        assertEquals(1, result.importedCount)
        assertEquals(0, result.skippedCount)
        assertEquals(0, result.errorCount)
        runBlocking { verify(repository).insertIfNew(any(), any(), any(), any(), any(), any()) }
        verify(importCursor).close()
    }

    @Test
    fun `import reads sub_id when the inbox exposes the column`() {
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        stubInboxRow(countCursor, importCursor, "hello", subId = 7)

        runBlocking {
            given(repository.insertIfNew(any(), any(), any(), any(), any(), any())).willReturn(IngestResult.Inserted(1L))
        }

        runBlocking { smsImporter.importMessages() }

        val simIdCaptor = argumentCaptor<Int>()
        runBlocking {
            verify(repository).insertIfNew(any(), any(), any(), simIdCaptor.capture(), any(), any())
        }
        assertEquals(7, simIdCaptor.firstValue)
    }

    @Test
    fun `import stores -1 as sim id when the inbox has no sub_id column`() {
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        stubInboxRow(countCursor, importCursor, "hello")

        runBlocking {
            given(repository.insertIfNew(any(), any(), any(), any(), any(), any())).willReturn(IngestResult.Inserted(1L))
        }

        runBlocking { smsImporter.importMessages() }

        val simIdCaptor = argumentCaptor<Int>()
        runBlocking {
            verify(repository).insertIfNew(any(), any(), any(), simIdCaptor.capture(), any(), any())
        }
        assertEquals(-1, simIdCaptor.firstValue)
    }

    @Test
    fun `import passes the stored dedup level to the repository`() {
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        stubInboxRow(countCursor, importCursor, "hello")

        runBlocking {
            given(repository.insertIfNew(any(), any(), any(), any(), any(), any())).willReturn(IngestResult.Inserted(1L))
        }

        runBlocking { smsImporter.importMessages() }

        val levelCaptor = argumentCaptor<DedupLevel>()
        runBlocking {
            verify(repository).insertIfNew(any(), any(), any(), any(), any(), levelCaptor.capture())
        }
        assertEquals(DedupLevel.BALANCED, levelCaptor.firstValue)
    }

    @Test
    fun `import passes one dedup-level snapshot to every row`() {
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        stubInboxRows(countCursor, importCursor, listOf("one", "two", "three"))
        given(prefs.load()).willReturn(AppSettings(dedupLevel = DedupLevel.AGGRESSIVE))

        runBlocking {
            given(repository.insertIfNew(any(), any(), any(), any(), any(), any())).willReturn(IngestResult.Inserted(1L))
        }

        val result = runBlocking { smsImporter.importMessages() }

        assertEquals(3, result.importedCount)
        val levelCaptor = argumentCaptor<DedupLevel>()
        runBlocking {
            verify(repository, times(3)).insertIfNew(any(), any(), any(), any(), any(), levelCaptor.capture())
        }
        assertEquals(
            listOf(DedupLevel.AGGRESSIVE, DedupLevel.AGGRESSIVE, DedupLevel.AGGRESSIVE),
            levelCaptor.allValues,
        )
    }

    @Test
    fun `import loads the settings once per import call, not per row`() {
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        stubInboxRows(countCursor, importCursor, listOf("one", "two", "three"))

        runBlocking {
            given(repository.insertIfNew(any(), any(), any(), any(), any(), any())).willReturn(IngestResult.Inserted(1L))
        }

        runBlocking { smsImporter.importMessages() }

        runBlocking { verify(prefs, times(1)).load() }
    }

    @Test
    fun `import raises the total when the inbox grows past the pre-count`() {
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        stubInboxRows(countCursor, importCursor, listOf("one", "two", "three"))
        // The pre-count snapshot saw two rows; a third SMS arrived after it, so the
        // iteration cursor outgrows the total the progress was seeded with.
        given(countCursor.count).willReturn(2)

        runBlocking {
            given(repository.insertIfNew(any(), any(), any(), any(), any(), any())).willReturn(IngestResult.Inserted(1L))
        }

        val progress = Collections.synchronizedList(mutableListOf<SmsImporter.ImportProgress>())
        val result = runBlocking { smsImporter.importMessages { progress.add(it) } }

        assertEquals(3, result.importedCount)
        // The total follows the current once it is outgrown, and no emission ever
        // reports more processed than total (the UI renders both raw).
        assertEquals(3, progress.last().current)
        assertEquals(3, progress.last().total)
        assertTrue(progress.all { it.current <= it.total })
    }

    @Test
    fun `import stores the unknown-sender sentinel when the address is blank`() {
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        stubInboxRow(countCursor, importCursor, "hello", address = "")

        runBlocking {
            given(repository.insertIfNew(any(), any(), any(), any(), any(), any())).willReturn(IngestResult.Inserted(1L))
        }

        val result = runBlocking { smsImporter.importMessages() }

        assertEquals(1, result.importedCount)
        val captor = argumentCaptor<String>()
        runBlocking { verify(repository).insertIfNew(captor.capture(), any(), any(), any(), any(), any()) }
        assertEquals("unknown", captor.firstValue)
        verify(importCursor).close()
    }

    @Test
    fun `import stores the unknown-sender sentinel when the address is null`() {
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        stubInboxRow(countCursor, importCursor, "hello", address = null)

        runBlocking {
            given(repository.insertIfNew(any(), any(), any(), any(), any(), any())).willReturn(IngestResult.Inserted(1L))
        }

        val result = runBlocking { smsImporter.importMessages() }

        assertEquals(1, result.importedCount)
        val captor = argumentCaptor<String>()
        runBlocking { verify(repository).insertIfNew(captor.capture(), any(), any(), any(), any(), any()) }
        assertEquals("unknown", captor.firstValue)
        verify(importCursor).close()
    }

    @Test
    fun `import skips a blank body row without touching the repository`() {
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        stubInboxRow(countCursor, importCursor, "   ")

        val result = runBlocking { smsImporter.importMessages() }

        assertEquals(0, result.importedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(0, result.errorCount)
        verifyNoInteractions(repository)
        verify(importCursor).close()
    }

    @Test
    fun `import skips a row the repository reports as duplicate`() {
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        stubInboxRow(countCursor, importCursor, "hello")

        runBlocking {
            given(repository.insertIfNew(any(), any(), any(), any(), any(), any())).willReturn(IngestResult.Duplicate)
        }

        val result = runBlocking { smsImporter.importMessages() }

        assertEquals(0, result.importedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(0, result.errorCount)
        verify(importCursor).close()
    }

    @Test
    fun `import counts a row as error when insertIfNew throws`() {
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        stubInboxRow(countCursor, importCursor, "hello")

        runBlocking {
            given(repository.insertIfNew(any(), any(), any(), any(), any(), any())).willThrow(RuntimeException("db down"))
        }

        val result = runBlocking { smsImporter.importMessages() }

        assertEquals(0, result.importedCount)
        assertEquals(0, result.skippedCount)
        assertEquals(1, result.errorCount)
        verify(importCursor).close()
    }

    @Test
    fun `import reports an empty inbox as zero counts and closes the cursor`() {
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        given(countCursor.count).willReturn(0)
        given(importCursor.count).willReturn(0)
        stubInboxQueries(countCursor, importCursor)

        val result = runBlocking { smsImporter.importMessages() }

        assertEquals(0, result.importedCount)
        assertEquals(0, result.skippedCount)
        assertEquals(0, result.errorCount)
        verifyNoInteractions(repository)
        verify(importCursor).close()
    }

    @Test
    fun `import fails once when the address column is missing`() {
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        given(countCursor.count).willReturn(2)
        given(importCursor.count).willReturn(2)
        given(importCursor.getColumnIndex(Telephony.Sms.ADDRESS)).willReturn(-1)
        given(importCursor.getColumnIndex(Telephony.Sms.BODY)).willReturn(1)
        given(importCursor.getColumnIndex(Telephony.Sms.DATE)).willReturn(2)
        stubInboxQueries(countCursor, importCursor)

        val result = runBlocking { smsImporter.importMessages() }

        assertEquals(0, result.importedCount)
        assertEquals(0, result.skippedCount)
        assertEquals(1, result.errorCount)
        verifyNoInteractions(repository)
        verify(importCursor).close()
    }

    @Test
    fun `import fails once when the date column is missing`() {
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        given(countCursor.count).willReturn(2)
        given(importCursor.count).willReturn(2)
        given(importCursor.getColumnIndex(Telephony.Sms.ADDRESS)).willReturn(0)
        given(importCursor.getColumnIndex(Telephony.Sms.BODY)).willReturn(1)
        given(importCursor.getColumnIndex(Telephony.Sms.DATE)).willReturn(-1)
        stubInboxQueries(countCursor, importCursor)

        val result = runBlocking { smsImporter.importMessages() }

        assertEquals(0, result.importedCount)
        assertEquals(0, result.skippedCount)
        assertEquals(1, result.errorCount)
        verifyNoInteractions(repository)
        verify(importCursor).close()
    }

    @Test
    fun `import closes the cursor when the count getter throws`() {
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        given(countCursor.count).willReturn(1)
        given(importCursor.count).willThrow(IllegalStateException("provider died"))
        stubInboxQueries(countCursor, importCursor)

        val result = runBlocking { smsImporter.importMessages() }

        assertEquals(1, result.errorCount)
        verify(importCursor).close()
    }

    @Test
    fun `import runs the provider queries off the main thread`() {
        // Records, for every resolver.query call, whether it ran on the main Looper's thread.
        // Robolectric runs the test method on the main thread, mirroring the production
        // condition (viewModelScope = Main); Dispatchers.IO is the real pool.
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        val onMainThread = Collections.synchronizedList(mutableListOf<Boolean>())
        val resolver = mock<ContentResolver>()
        given(context.contentResolver).willReturn(resolver)
        given(
            resolver.query(
                any<Uri>(),
                any<Array<String>>(),
                anyOrNull<String>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
            ),
        ).willAnswer { inv ->
            onMainThread.add(Looper.myLooper() == Looper.getMainLooper())
            val projection = inv.getArgument<Array<String>>(1)
            val sortOrder = inv.arguments[4] as String?
            when {
                projection.size == 1 -> countCursor
                sortOrder != null -> importCursor
                else -> probeWithoutSubId()
            }
        }
        // Single-row inbox, same shape as stubInboxRow, routed through the recording resolver.
        given(countCursor.count).willReturn(1)
        given(importCursor.count).willReturn(1)
        given(importCursor.getColumnIndex(Telephony.Sms.ADDRESS)).willReturn(0)
        given(importCursor.getColumnIndex(Telephony.Sms.BODY)).willReturn(1)
        given(importCursor.getColumnIndex(Telephony.Sms.DATE)).willReturn(2)
        given(importCursor.moveToNext()).willReturn(true, false)
        `doReturn`(TestPhone.FRIEND).`when`(importCursor).getString(0)
        given(importCursor.getString(1)).willReturn("hello")
        given(importCursor.getLong(2)).willReturn(1_700_000_000_000L)

        runBlocking {
            given(repository.insertIfNew(any(), any(), any(), any(), any(), any())).willReturn(IngestResult.Inserted(1L))
        }

        val result = runBlocking { smsImporter.importMessages() }

        assertEquals(1, result.importedCount)
        // One sub_id probe, one count query and one scan query, none on the main thread.
        assertEquals(listOf(false, false, false), onMainThread)
    }

    @Test
    fun `import with missing body column skips the row as blank`() {
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        given(countCursor.count).willReturn(1)
        given(importCursor.count).willReturn(1)
        given(importCursor.getColumnIndex(Telephony.Sms.ADDRESS)).willReturn(0)
        given(importCursor.getColumnIndex(Telephony.Sms.BODY)).willReturn(-1)
        given(importCursor.getColumnIndex(Telephony.Sms.DATE)).willReturn(2)
        given(importCursor.moveToNext()).willReturn(true, false)
        `doReturn`(TestPhone.FRIEND).`when`(importCursor).getString(0)
        given(importCursor.getLong(2)).willReturn(1_700_000_000_000L)
        stubInboxQueries(countCursor, importCursor)

        val result = runBlocking { smsImporter.importMessages() }

        assertEquals(0, result.importedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(0, result.errorCount)
        verifyNoInteractions(repository)
        verify(importCursor).close()
    }

    @Test
    fun `import with sinceTimestamp passes selection to the queries`() {
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        val resolver = mock<ContentResolver>()
        given(context.contentResolver).willReturn(resolver)
        val capturedSelections = mutableListOf<String?>()
        val capturedArgs = mutableListOf<Array<String>?>()
        given(
            resolver.query(
                any<Uri>(),
                any<Array<String>>(),
                anyOrNull<String>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
            ),
        ).willAnswer { inv ->
            capturedSelections.add(inv.arguments[2] as String?)
            capturedArgs.add(inv.arguments[3] as Array<String>?)
            val projection = inv.getArgument<Array<String>>(1)
            val sortOrder = inv.arguments[4] as String?
            when {
                projection.size == 1 -> countCursor
                sortOrder != null -> importCursor
                else -> probeWithoutSubId()
            }
        }
        given(countCursor.count).willReturn(1)
        given(importCursor.count).willReturn(1)
        given(importCursor.getColumnIndex(Telephony.Sms.ADDRESS)).willReturn(0)
        given(importCursor.getColumnIndex(Telephony.Sms.BODY)).willReturn(1)
        given(importCursor.getColumnIndex(Telephony.Sms.DATE)).willReturn(2)
        given(importCursor.moveToNext()).willReturn(true, false)
        `doReturn`(TestPhone.FRIEND).`when`(importCursor).getString(0)
        given(importCursor.getString(1)).willReturn("hello")
        given(importCursor.getLong(2)).willReturn(1_700_000_000_000L)

        runBlocking {
            given(repository.insertIfNew(any(), any(), any(), any(), any(), any())).willReturn(IngestResult.Inserted(1L))
        }

        val sinceTs = 1_600_000_000_000L
        val result = runBlocking { smsImporter.importMessages(sinceTimestamp = sinceTs) }

        assertEquals(1, result.importedCount)
        // The count and import queries both carry the since-timestamp selection.
        assertTrue(capturedSelections.any { it != null && it.contains(">=") })
        assertTrue(capturedArgs.any { it != null && it.contentToString().contains(sinceTs.toString()) })
    }

    @Test
    fun `import degrades when the count query returns null`() {
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        val resolver = mock<ContentResolver>()
        given(context.contentResolver).willReturn(resolver)
        given(
            resolver.query(
                any<Uri>(),
                any<Array<String>>(),
                anyOrNull<String>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
            ),
        ).willAnswer { inv ->
            val projection = inv.getArgument<Array<String>>(1)
            val sortOrder = inv.arguments[4] as String?
            when {
                projection.size == 1 -> null
                sortOrder != null -> importCursor
                else -> probeWithoutSubId()
            }
        }
        given(importCursor.count).willReturn(0)

        val result = runBlocking { smsImporter.importMessages() }

        assertEquals(0, result.importedCount)
        assertEquals(0, result.skippedCount)
        assertEquals(0, result.errorCount)
        verifyNoInteractions(repository)
    }

    @Test
    fun `import degrades when the sub_id probe query returns null`() {
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        val resolver = mock<ContentResolver>()
        given(context.contentResolver).willReturn(resolver)
        given(
            resolver.query(
                any<Uri>(),
                any<Array<String>>(),
                anyOrNull<String>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
            ),
        ).willAnswer { inv ->
            val projection = inv.getArgument<Array<String>>(1)
            val sortOrder = inv.arguments[4] as String?
            when {
                projection.size == 1 -> countCursor
                sortOrder != null -> importCursor
                else -> null
            }
        }
        given(countCursor.count).willReturn(1)
        given(importCursor.count).willReturn(1)
        given(importCursor.getColumnIndex(Telephony.Sms.ADDRESS)).willReturn(0)
        given(importCursor.getColumnIndex(Telephony.Sms.BODY)).willReturn(1)
        given(importCursor.getColumnIndex(Telephony.Sms.DATE)).willReturn(2)
        given(importCursor.moveToNext()).willReturn(true, false)
        `doReturn`(TestPhone.FRIEND).`when`(importCursor).getString(0)
        given(importCursor.getString(1)).willReturn("hello")
        given(importCursor.getLong(2)).willReturn(1_700_000_000_000L)

        runBlocking {
            given(repository.insertIfNew(any(), any(), any(), any(), any(), any())).willReturn(IngestResult.Inserted(1L))
        }

        val result = runBlocking { smsImporter.importMessages() }

        assertEquals(1, result.importedCount)
        val simIdCaptor = argumentCaptor<Int>()
        runBlocking {
            verify(repository).insertIfNew(any(), any(), any(), simIdCaptor.capture(), any(), any())
        }
        assertEquals(-1, simIdCaptor.firstValue)
    }

    @Test
    fun `import with sub_id column present but null value stores -1 as simId`() {
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        given(countCursor.count).willReturn(1)
        given(importCursor.count).willReturn(1)
        given(importCursor.getColumnIndex(Telephony.Sms.ADDRESS)).willReturn(0)
        given(importCursor.getColumnIndex(Telephony.Sms.BODY)).willReturn(1)
        given(importCursor.getColumnIndex(Telephony.Sms.DATE)).willReturn(2)
        given(importCursor.getColumnIndex("sub_id")).willReturn(3)
        given(importCursor.isNull(3)).willReturn(true)
        given(importCursor.moveToNext()).willReturn(true, false)
        `doReturn`(TestPhone.FRIEND).`when`(importCursor).getString(0)
        given(importCursor.getString(1)).willReturn("hello")
        given(importCursor.getLong(2)).willReturn(1_700_000_000_000L)
        stubInboxQueries(countCursor, importCursor, probeWithSubId(3))

        runBlocking {
            given(repository.insertIfNew(any(), any(), any(), any(), any(), any())).willReturn(IngestResult.Inserted(1L))
        }

        runBlocking { smsImporter.importMessages() }

        val simIdCaptor = argumentCaptor<Int>()
        runBlocking {
            verify(repository).insertIfNew(any(), any(), any(), simIdCaptor.capture(), any(), any())
        }
        assertEquals(-1, simIdCaptor.firstValue)
    }

    @Test
    fun `import skips a blank body row when the body column returns null`() {
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        given(countCursor.count).willReturn(1)
        given(importCursor.count).willReturn(1)
        given(importCursor.getColumnIndex(Telephony.Sms.ADDRESS)).willReturn(0)
        given(importCursor.getColumnIndex(Telephony.Sms.BODY)).willReturn(1)
        given(importCursor.getColumnIndex(Telephony.Sms.DATE)).willReturn(2)
        given(importCursor.moveToNext()).willReturn(true, false)
        `doReturn`(TestPhone.FRIEND).`when`(importCursor).getString(0)
        `doReturn`(null).`when`(importCursor).getString(1)
        given(importCursor.getLong(2)).willReturn(1_700_000_000_000L)
        stubInboxQueries(countCursor, importCursor)

        val result = runBlocking { smsImporter.importMessages() }

        assertEquals(0, result.importedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(0, result.errorCount)
        verifyNoInteractions(repository)
        verify(importCursor).close()
    }

    // When the inbox cursor query returns null (permission denied or provider unavailable),
    // importFromInbox returns ImportResult(errorCount = 1) without throwing.
    @Test
    fun `import fails once when the inbox cursor query returns null`() {
        val countCursor = mock<Cursor>()
        val resolver = mock<ContentResolver>()
        given(context.contentResolver).willReturn(resolver)
        given(
            resolver.query(
                any<Uri>(),
                any<Array<String>>(),
                anyOrNull<String>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
            ),
        ).willAnswer { inv ->
            val sortOrder = inv.arguments[4] as String?
            val projection = inv.getArgument<Array<String>>(1)
            when {
                sortOrder != null -> null
                projection.size == 1 -> countCursor
                else -> probeWithoutSubId()
            }
        }
        given(countCursor.count).willReturn(0)

        val result = runBlocking { smsImporter.importMessages() }

        assertEquals(1, result.errorCount)
        assertEquals(0, result.importedCount)
        assertEquals(0, result.skippedCount)
    }

    // importMessages: the outer catch(Exception) block fires when prefs.load() or any
    // pre-import step throws. The import reports a single error rather than crashing.
    @Test
    fun `importMessages catches exception from prefs load and reports single error`() {
        given(prefs.load()).willThrow(RuntimeException("prefs corrupted"))
        val resolver = mock<ContentResolver>()
        given(context.contentResolver).willReturn(resolver)
        given(
            resolver.query(
                any<Uri>(),
                any<Array<String>>(),
                anyOrNull<String>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
            ),
        ).willReturn(null)

        val result = runBlocking { smsImporter.importMessages() }

        assertEquals(1, result.errorCount)
        assertEquals(0, result.importedCount)
        assertEquals(0, result.skippedCount)
    }

    // When the import processes rows and some fail (insertIfNew throws), the errorCount > 0
    // path in importFromInbox logs the aggregate error count. The result carries the error
    // count from processRows.
    @Test
    fun `import logs error summary when rows fail and returns the error count`() {
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        stubInboxRows(countCursor, importCursor, listOf("ok", "fail"))

        runBlocking {
            given(repository.insertIfNew(any(), any(), any(), any(), any(), any()))
                .willReturn(IngestResult.Inserted(1L))
                .willThrow(RuntimeException("db error"))
        }

        val result = runBlocking { smsImporter.importMessages() }

        assertEquals(1, result.importedCount)
        assertEquals(0, result.skippedCount)
        assertEquals(1, result.errorCount)
        verify(importCursor).close()
    }

    @Test
    fun `OpenedInbox data class exposes supportsSimId and cursor properties`() {
        // OpenedInbox is private, but its constructor and properties are exercised
        // by openInboxCursor which is called by importFromInbox. We verify the shape
        // through the import flow with sim support enabled.
        val countCursor = mock<Cursor>()
        val importCursor = mock<Cursor>()
        stubInboxRow(countCursor, importCursor, "hello", subId = 3)

        runBlocking {
            given(repository.insertIfNew(any(), any(), any(), any(), any(), any())).willReturn(IngestResult.Inserted(1L))
        }

        val result = runBlocking { smsImporter.importMessages() }

        assertEquals(1, result.importedCount)
        val simIdCaptor = argumentCaptor<Int>()
        runBlocking {
            verify(repository).insertIfNew(any(), any(), any(), simIdCaptor.capture(), any(), any())
        }
        assertEquals(3, simIdCaptor.firstValue)
    }
}
