package app.kotowski.sms2email.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import app.kotowski.sms2email.LogLevel
import app.kotowski.sms2email.testing.TestPhone
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class QueryExtTest {
    private val uri = Uri.parse("content://sms/inbox")

    @Test
    fun `querySafely returns the cursor on success`() {
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
        assertSame(cursor, context.querySafely(uri, arrayOf("address")))
    }

    @Test
    fun `querySafely passes selection args and sort order through`() {
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
        ).thenAnswer { invocation ->
            assertEquals("date >= ?", invocation.arguments[2])
            assertEquals(listOf("123"), invocation.getArgument<Array<String>>(3).toList())
            assertEquals("date ASC", invocation.arguments[4])
            cursor
        }
        val result = context.querySafely(uri, arrayOf("address"), "date >= ?", arrayOf("123"), "date ASC")
        assertNotNull(result)
    }

    @Test
    fun `querySafely returns null when the query throws`() {
        val context = mock<Context>()
        val resolver = mock<ContentResolver>()
        whenever(context.contentResolver).thenReturn(resolver)
        doThrow(RuntimeException("boom"))
            .whenever(resolver)
            .query(any<Uri>(), anyOrNull<Array<String>>(), anyOrNull<String>(), anyOrNull<Array<String>>(), anyOrNull<String>())
        assertNull(context.querySafely(uri, arrayOf("address")))
    }

    // Pinned: the phone_lookup path carries the sender's number and the Permission Denial
    // message embeds the full URI; the log line must keep neither.
    @Test
    fun `querySafely logs the authority only and never the number`() {
        Logger.setLevel(LogLevel.DEBUG)
        val lookupUri = Uri.parse("content://com.android.contacts/phone_lookup/" + Uri.encode(TestPhone.FRIEND))
        val context = mock<Context>()
        val resolver = mock<ContentResolver>()
        whenever(context.contentResolver).thenReturn(resolver)
        // The Permission Denial text embeds the full URI, the way the framework builds it.
        val denial = "Permission Denial: reading uri $lookupUri from pid=1, uid=1 requires android.permission.READ_CONTACTS"
        doThrow(SecurityException(denial))
            .whenever(resolver)
            .query(any<Uri>(), anyOrNull<Array<String>>(), anyOrNull<String>(), anyOrNull<Array<String>>(), anyOrNull<String>())

        assertNull(context.querySafely(lookupUri, arrayOf("display_name")))

        val entry = LogBuffer.entries.last()
        assertEquals("querySafely", entry.tag)
        assertEquals("query failed for content://com.android.contacts: SecurityException", entry.message)
        assertTrue(!entry.message.contains(TestPhone.FRIEND))
    }
}
