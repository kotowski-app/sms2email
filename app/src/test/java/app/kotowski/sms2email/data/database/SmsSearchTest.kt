package app.kotowski.sms2email.data.database

import app.kotowski.sms2email.AppConfig
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmsSearchTest {
    @Test
    fun `none is a search-less criteria with empty facets`() {
        val search = SmsSearch.none()
        assertTrue(search.noSearch)
        assertEquals("", search.likePattern)
        assertEquals("", search.phoneLikePattern)
        assertTrue(search.contactNumbers.isEmpty())
        assertTrue(search.simIds.isEmpty())
    }

    @Test
    fun `forQuery lowers the pattern with unicode case folding`() {
        val search = SmsSearch.forQuery("Привет", emptyList(), emptyList())
        assertFalse(search.noSearch)
        assertEquals("%привет%", search.likePattern)
    }

    @Test
    fun `forQuery folds uppercase I to i independent of the Turkish default locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            val search = SmsSearch.forQuery("Istiklal", emptyList(), emptyList())
            assertEquals("%istiklal%", search.likePattern)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `forQuery wraps the pattern in wildcards`() {
        val search = SmsSearch.forQuery("abc", emptyList(), emptyList())
        assertEquals("%abc%", search.likePattern)
    }

    @Test
    fun `forQuery escapes the percent wildcard`() {
        val search = SmsSearch.forQuery("100%", emptyList(), emptyList())
        assertEquals("%100\\%%", search.likePattern)
    }

    @Test
    fun `forQuery escapes the underscore wildcard`() {
        val search = SmsSearch.forQuery("a_b", emptyList(), emptyList())
        assertEquals("%a\\_b%", search.likePattern)
    }

    @Test
    fun `forQuery escapes the escape character itself`() {
        val search = SmsSearch.forQuery("a\\b", emptyList(), emptyList())
        assertEquals("%a\\\\b%", search.likePattern)
    }

    @Test
    fun `forQuery strips separators from the phone pattern`() {
        val search = SmsSearch.forQuery("1 555 010-0", emptyList(), emptyList())
        assertEquals("%15550100%", search.phoneLikePattern)
    }

    @Test
    fun `forQuery keeps letters and digits in the phone pattern with unicode folding`() {
        val search = SmsSearch.forQuery("Оплата", emptyList(), emptyList())
        assertEquals("%оплата%", search.phoneLikePattern)
    }

    @Test
    fun `forQuery binds a never-matching phone pattern for a query without alphanumerics`() {
        val search = SmsSearch.forQuery("!!!", emptyList(), emptyList())
        assertEquals("\u0001", search.phoneLikePattern)
    }

    @Test
    fun `forQuery caps the contact facet at the max size keeping the first entries`() {
        val numbers = (1..AppConfig.SMS_SEARCH_MAX_CONTACT_NUMBERS + 100).map { "1555000$it" }
        val search = SmsSearch.forQuery("john", numbers, emptyList())
        assertEquals(AppConfig.SMS_SEARCH_MAX_CONTACT_NUMBERS, search.contactNumbers.size)
        assertEquals(numbers.take(AppConfig.SMS_SEARCH_MAX_CONTACT_NUMBERS), search.contactNumbers)
    }

    @Test
    fun `forQuery carries the facet inputs through`() {
        val search = SmsSearch.forQuery("john", listOf("5550100"), listOf(2))
        assertFalse(search.noSearch)
        assertEquals(listOf("5550100"), search.contactNumbers)
        assertEquals(listOf(2), search.simIds)
    }

    @Test
    fun `escapeLike leaves regular text untouched`() {
        assertEquals("hello world", SmsSearch.escapeLike("hello world"))
    }

    @Test
    fun `escapeLike lowercases nothing`() {
        assertEquals("ABC", SmsSearch.escapeLike("ABC"))
    }
}
