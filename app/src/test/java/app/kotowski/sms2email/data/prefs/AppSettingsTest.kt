package app.kotowski.sms2email.data.prefs

import app.kotowski.sms2email.data.filter.FilterMode
import app.kotowski.sms2email.data.filter.FilterRule
import app.kotowski.sms2email.data.filter.SmsFilter
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AppSettingsTest {
    private val filter = SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "vip")))

    @Test
    fun `effectiveFilter is the stored filter while the toggle is on`() {
        val settings = AppSettings(filter = filter, filterEnabled = true)
        // The stored instance as-is: no copy, no re-parse.
        assertSame(filter, settings.effectiveFilter)
    }

    @Test
    fun `effectiveFilter is the off filter while the toggle is off`() {
        // The stored config stays a dormant draft: the off filter applies, it is not
        // touched or cleared.
        assertEquals(SmsFilter(), AppSettings(filter = filter, filterEnabled = false).effectiveFilter)
        assertEquals(filter, AppSettings(filter = filter, filterEnabled = false).filter)
    }

    @Test
    fun `effectiveFilter of the product default is the off filter`() {
        assertEquals(SmsFilter(), AppSettings().effectiveFilter)
    }
}
