package app.kotowski.sms2email.ui.smslist

import app.kotowski.sms2email.AppConfig
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmsPageWindowTest {
    @Test
    fun `the window starts at one page`() {
        assertEquals(AppConfig.SMS_LIST_PAGE_SIZE, SmsPageWindow().loadedLimit)
    }

    @Test
    fun `grow adds exactly one page`() {
        val window = SmsPageWindow()
        window.grow()
        assertEquals(2 * AppConfig.SMS_LIST_PAGE_SIZE, window.loadedLimit)
        window.grow()
        assertEquals(3 * AppConfig.SMS_LIST_PAGE_SIZE, window.loadedLimit)
    }

    @Test
    fun `hasMore is true only when the probe row was returned`() {
        val window = SmsPageWindow()
        // A full window without the probe row: nothing left to load.
        assertFalse(window.hasMore(AppConfig.SMS_LIST_PAGE_SIZE))
        // A full window plus the probe row: there is more.
        assertTrue(window.hasMore(AppConfig.SMS_LIST_PAGE_SIZE + 1))
        // A short window: nothing left to load.
        assertFalse(window.hasMore(AppConfig.SMS_LIST_PAGE_SIZE - 1))
    }

    @Test
    fun `hasMore tracks the grown window`() {
        val window = SmsPageWindow()
        window.grow()
        assertFalse(window.hasMore(2 * AppConfig.SMS_LIST_PAGE_SIZE))
        assertTrue(window.hasMore(2 * AppConfig.SMS_LIST_PAGE_SIZE + 1))
    }
}
