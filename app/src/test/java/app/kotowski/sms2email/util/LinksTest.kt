package app.kotowski.sms2email.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LinksTest {
    @Test
    fun `the repo link points at the sms2email repository`() {
        assertEquals("https://github.com/kotowski-app/sms2email", Links.REPO)
    }

    @Test
    fun `the bug link opens the new-issue page of the repo without a pre-selected type`() {
        assertEquals("https://github.com/kotowski-app/sms2email/issues/new", Links.REPORT_BUG)
    }

    @Test
    fun `the donate link points at the buy me a coffee page`() {
        assertEquals("https://www.buymeacoffee.com/kotowski", Links.DONATE)
    }
}
