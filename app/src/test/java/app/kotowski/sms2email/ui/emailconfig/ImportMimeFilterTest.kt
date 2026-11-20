package app.kotowski.sms2email.ui.emailconfig

import app.kotowski.sms2email.ui.components.IMPORT_MIME_FILTER
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportMimeFilterTest {
    @Test
    fun `import picker accepts all files so exports are visible on any document provider`() {
        assertEquals("*/*", IMPORT_MIME_FILTER)
    }
}
