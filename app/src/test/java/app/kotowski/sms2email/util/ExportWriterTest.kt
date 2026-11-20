package app.kotowski.sms2email.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream

class ExportWriterTest {
    private lateinit var context: Context
    private lateinit var resolver: ContentResolver
    private lateinit var uri: Uri

    @Before
    fun setup() {
        context = mock()
        resolver = mock()
        uri = mock()
        whenever(context.contentResolver).thenReturn(resolver)
    }

    @Test
    fun `writes json to the stream and returns true`() {
        val out = ByteArrayOutputStream()
        whenever(resolver.openOutputStream(uri)).thenReturn(out)

        assertTrue(writeExport(context, uri, """{"version":1}"""))
        assertEquals("""{"version":1}""", out.toString(Charsets.UTF_8))
    }

    @Test
    fun `returns false when the provider gives no stream`() {
        whenever(resolver.openOutputStream(uri)).thenReturn(null)

        assertFalse(writeExport(context, uri, "{}"))
    }

    @Test
    fun `writes empty json`() {
        val out = ByteArrayOutputStream()
        whenever(resolver.openOutputStream(uri)).thenReturn(out)

        assertTrue(writeExport(context, uri, "{}"))
        assertEquals("{}", out.toString(Charsets.UTF_8))
    }

    @Test
    fun `writes large json`() {
        val out = ByteArrayOutputStream()
        whenever(resolver.openOutputStream(uri)).thenReturn(out)
        val largeJson = """{"data":"${"x".repeat(10000)}"}"""

        assertTrue(writeExport(context, uri, largeJson))
        assertEquals(largeJson, out.toString(Charsets.UTF_8))
    }

    @Test
    fun `writes non-ASCII json`() {
        val out = ByteArrayOutputStream()
        whenever(resolver.openOutputStream(uri)).thenReturn(out)
        val json = """{"message":"привет мир","emoji":"👋"}"""

        assertTrue(writeExport(context, uri, json))
        assertEquals(json, out.toString(Charsets.UTF_8))
    }
}
