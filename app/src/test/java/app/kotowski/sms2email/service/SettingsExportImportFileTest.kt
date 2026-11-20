package app.kotowski.sms2email.service

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.LogLevel
import app.kotowski.sms2email.service.SettingsExportImport.ImportFileTooLargeException
import app.kotowski.sms2email.util.LogBuffer
import app.kotowski.sms2email.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.charset.MalformedInputException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The settings-import I/O phase (size check + capped read) that [SettingsExportImport.readSettingsImport] runs on Dispatchers.IO.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SettingsExportImportFileTest {
    private val uri = Uri.parse("content://com.android.documentsui/documents/pick")

    @Test
    fun `the size cap accepts realistic exports and rejects large files`() {
        assertTrue(4096L < AppConfig.SETTINGS_IMPORT_MAX_FILE_SIZE)
        assertTrue(50L * 1024 * 1024 > AppConfig.SETTINGS_IMPORT_MAX_FILE_SIZE)
    }

    @Test
    fun `unknown sizes are not too large so the read is attempted`() {
        assertFalse(SettingsExportImport.isImportFileTooLarge(null))
        assertFalse(SettingsExportImport.isImportFileTooLarge(0))
        assertFalse(SettingsExportImport.isImportFileTooLarge(-1))
    }

    @Test
    fun `sizes up to the cap are not too large`() {
        assertFalse(SettingsExportImport.isImportFileTooLarge(4096))
        assertFalse(SettingsExportImport.isImportFileTooLarge(AppConfig.SETTINGS_IMPORT_MAX_FILE_SIZE))
    }

    @Test
    fun `sizes above the cap are too large`() {
        assertTrue(SettingsExportImport.isImportFileTooLarge(AppConfig.SETTINGS_IMPORT_MAX_FILE_SIZE + 1))
        assertTrue(SettingsExportImport.isImportFileTooLarge(50L * 1024 * 1024))
    }

    private fun mockResolverWith(cursor: Cursor?): Pair<Context, ContentResolver> {
        val context = mock<Context>()
        val resolver = mock<ContentResolver>()
        whenever(context.contentResolver).thenReturn(resolver)
        whenever(
            resolver.query(
                any<Uri>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
            ),
        ).thenReturn(cursor)
        return context to resolver
    }

    @Test
    fun `queryImportFileSize returns the reported size and closes the cursor`() {
        val cursor = mock<Cursor>()
        whenever(cursor.moveToFirst()).thenReturn(true)
        whenever(cursor.getColumnIndex(OpenableColumns.SIZE)).thenReturn(0)
        whenever(cursor.isNull(0)).thenReturn(false)
        whenever(cursor.getLong(0)).thenReturn(1234L)
        val (context, _) = mockResolverWith(cursor)

        assertEquals(1234L, SettingsExportImport.queryImportFileSize(context, uri))
        verify(cursor).close()
    }

    @Test
    fun `queryImportFileSize queries the OpenableColumns SIZE projection`() {
        val (context, resolver) = mockResolverWith(null)

        SettingsExportImport.queryImportFileSize(context, uri)

        verify(resolver).query(eq(uri), eq(arrayOf(OpenableColumns.SIZE)), isNull(), isNull(), isNull())
    }

    @Test
    fun `queryImportFileSize is null when the query returns no cursor`() {
        val (context, _) = mockResolverWith(null)

        assertNull(SettingsExportImport.queryImportFileSize(context, uri))
    }

    @Test
    fun `queryImportFileSize is null when the cursor has no rows`() {
        val cursor = mock<Cursor>()
        whenever(cursor.moveToFirst()).thenReturn(false)
        val (context, _) = mockResolverWith(cursor)

        assertNull(SettingsExportImport.queryImportFileSize(context, uri))
    }

    @Test
    fun `queryImportFileSize is null when the SIZE column is missing`() {
        val cursor = mock<Cursor>()
        whenever(cursor.moveToFirst()).thenReturn(true)
        whenever(cursor.getColumnIndex(OpenableColumns.SIZE)).thenReturn(-1)
        val (context, _) = mockResolverWith(cursor)

        assertNull(SettingsExportImport.queryImportFileSize(context, uri))
    }

    @Test
    fun `queryImportFileSize is null when the SIZE value is null`() {
        val cursor = mock<Cursor>()
        whenever(cursor.moveToFirst()).thenReturn(true)
        whenever(cursor.getColumnIndex(OpenableColumns.SIZE)).thenReturn(0)
        whenever(cursor.isNull(0)).thenReturn(true)
        val (context, _) = mockResolverWith(cursor)

        assertNull(SettingsExportImport.queryImportFileSize(context, uri))
    }

    private class Pick(
        val context: Context,
        val resolver: ContentResolver,
        val cursor: Cursor,
    )

    // size null means "unknown" (the query returns an empty cursor); openStreamError and
    // nullStream are mutually exclusive with a real json payload.
    private fun setupPick(
        size: Long?,
        json: String? = null,
        openStreamError: Throwable? = null,
        nullStream: Boolean = false,
    ): Pick {
        val context = mock<Context>()
        val resolver = mock<ContentResolver>()
        val cursor = mock<Cursor>()
        whenever(context.contentResolver).thenReturn(resolver)
        whenever(
            resolver.query(
                any<Uri>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
            ),
        ).thenReturn(cursor)
        whenever(cursor.moveToFirst()).thenReturn(size != null)
        whenever(cursor.getColumnIndex(OpenableColumns.SIZE)).thenReturn(0)
        whenever(cursor.isNull(0)).thenReturn(false)
        whenever(cursor.getLong(0)).thenReturn(size ?: 0L)
        if (openStreamError != null) {
            doThrow(openStreamError).whenever(resolver).openInputStream(uri)
        } else {
            whenever(resolver.openInputStream(uri)).thenReturn(
                if (nullStream) null else ByteArrayInputStream((json ?: "").toByteArray()),
            )
        }
        return Pick(context, resolver, cursor)
    }

    @Test
    fun `readImportJson returns the stream content`() {
        val pick = setupPick(1024, json = "export")

        assertEquals("export", SettingsExportImport.readImportJson(pick.context, uri))
    }

    @Test
    fun `readImportJson is an empty string when there is no stream`() {
        val pick = setupPick(1024, nullStream = true)

        assertEquals("", SettingsExportImport.readImportJson(pick.context, uri))
    }

    @Test
    fun `readImportJson throws when the stream exceeds the cap`() {
        val pick = setupPick(null, json = "a".repeat(AppConfig.SETTINGS_IMPORT_MAX_FILE_SIZE.toInt() + 1))

        assertFailsWith<ImportFileTooLargeException> {
            SettingsExportImport.readImportJson(pick.context, uri)
        }
    }

    @Test
    fun `readImportJson accepts a stream of exactly the cap`() {
        val text = "a".repeat(AppConfig.SETTINGS_IMPORT_MAX_FILE_SIZE.toInt())
        val pick = setupPick(null, json = text)

        assertEquals(text, SettingsExportImport.readImportJson(pick.context, uri))
    }

    @Test
    fun `readImportJson decodes multi-byte characters in a stream larger than one chunk`() {
        // 9000 bytes of a 3-byte character: the read spans two 8 KiB chunks; decoding
        // runs on the collected buffer, so no character is ever split across chunks.
        val text = "€".repeat(3000)
        val pick = setupPick(1024, json = text)

        assertEquals(text, SettingsExportImport.readImportJson(pick.context, uri))
    }

    @Test
    fun `readImportJson rejects invalid utf-8 with a MalformedInputException`() {
        val context = mock<Context>()
        val resolver = mock<ContentResolver>()
        whenever(context.contentResolver).thenReturn(resolver)
        whenever(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(byteArrayOf(-1, -2, 3)))

        assertFailsWith<MalformedInputException> { SettingsExportImport.readImportJson(context, uri) }
    }

    @Test
    fun `a reported size over the cap is TooLarge without opening the stream`() {
        val pick = setupPick(AppConfig.SETTINGS_IMPORT_MAX_FILE_SIZE + 1, json = "{}")

        val outcome = runBlocking { SettingsExportImport.readSettingsImport(pick.context, uri) }

        assertEquals(ImportReadOutcome.TooLarge, outcome)
        verify(pick.resolver, never()).openInputStream(uri)
    }

    @Test
    fun `an unknown size stream over the cap is TooLarge`() {
        val pick = setupPick(null, json = "a".repeat(AppConfig.SETTINGS_IMPORT_MAX_FILE_SIZE.toInt() + 1))

        val outcome = runBlocking { SettingsExportImport.readSettingsImport(pick.context, uri) }

        assertEquals(ImportReadOutcome.TooLarge, outcome)
        verify(pick.resolver).openInputStream(uri)
    }

    @Test
    fun `a stream of exactly the cap is read as Json`() {
        val pick = setupPick(AppConfig.SETTINGS_IMPORT_MAX_FILE_SIZE, json = "export")

        val outcome = runBlocking { SettingsExportImport.readSettingsImport(pick.context, uri) }

        assertEquals(ImportReadOutcome.Json("export"), outcome)
    }

    @Test
    fun `a valid stream is Json with its content`() {
        val pick = setupPick(null, json = "export")

        val outcome = runBlocking { SettingsExportImport.readSettingsImport(pick.context, uri) }

        assertEquals(ImportReadOutcome.Json("export"), outcome)
    }

    @Test
    fun `a missing stream is an empty Json`() {
        val pick = setupPick(1024, nullStream = true)

        val outcome = runBlocking { SettingsExportImport.readSettingsImport(pick.context, uri) }

        assertEquals(ImportReadOutcome.Json(""), outcome)
    }

    @Test
    fun `invalid utf-8 is Failed`() {
        val context = mock<Context>()
        val resolver = mock<ContentResolver>()
        whenever(context.contentResolver).thenReturn(resolver)
        whenever(
            resolver.query(
                any<Uri>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
            ),
        ).thenReturn(null)
        whenever(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(byteArrayOf(-1, -2, 3)))

        val outcome = runBlocking { SettingsExportImport.readSettingsImport(context, uri) }

        assertEquals(ImportReadOutcome.Failed, outcome)
    }

    @Test
    fun `a read error is Failed`() {
        Logger.setLevel(LogLevel.DEBUG)
        val pick = setupPick(1024, openStreamError = FileNotFoundException("no stream"))

        val outcome = runBlocking { SettingsExportImport.readSettingsImport(pick.context, uri) }

        assertEquals(ImportReadOutcome.Failed, outcome)
        // Pinned: an I/O error can carry the picked file name; the log keeps the class name only.
        val entry = LogBuffer.entries.last()
        assertEquals("SettingsImport", entry.tag)
        assertEquals("Import failed: FileNotFoundException", entry.message)
    }

    @Test
    fun `a CancellationException from the stream is rethrown`() {
        // A CE surfacing from inside the IO phase (here: from the stream's read) must
        // cancel the import, never be folded into a Failed outcome.
        val pick = setupPick(null, json = "export")
        whenever(pick.resolver.openInputStream(uri)).thenReturn(
            object : InputStream() {
                override fun read(): Int = throw CancellationException("cancelled mid-read")
            },
        )

        assertFailsWith<CancellationException> {
            runBlocking { SettingsExportImport.readSettingsImport(pick.context, uri) }
        }
    }
}
