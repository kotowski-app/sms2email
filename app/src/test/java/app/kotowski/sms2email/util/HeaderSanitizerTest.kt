package app.kotowski.sms2email.util

import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.testing.TestPhone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderSanitizerTest {
    @Test
    fun `sanitizeHeaderValue strips CRLF`() {
        assertEquals("123Bcc: evil@example.com", sanitizeHeaderValue("123\r\nBcc: evil@example.com"))
    }

    @Test
    fun `sanitizeHeaderValue strips lone line feed`() {
        assertEquals("123Bcc: evil@example.com", sanitizeHeaderValue("123\nBcc: evil@example.com"))
    }

    @Test
    fun `sanitizeHeaderValue strips lone carriage return`() {
        assertEquals("123Bcc: evil@example.com", sanitizeHeaderValue("123\rBcc: evil@example.com"))
    }

    @Test
    fun `sanitizeHeaderValue strips other C0 control chars`() {
        assertEquals("abc", sanitizeHeaderValue("a\tb\u0000c"))
    }

    @Test
    fun `sanitizeHeaderValue strips DEL`() {
        assertEquals("ab", sanitizeHeaderValue("a\u007Fb"))
    }

    @Test
    fun `sanitizeHeaderValue preserves normal subject text`() {
        assertEquals("[myapp] SMS from ${TestPhone.SELF}", sanitizeHeaderValue("[myapp] SMS from ${TestPhone.SELF}"))
    }

    @Test
    fun `sanitizeHeaderValue preserves non-ASCII text`() {
        assertEquals("SMS from caf\u00e9", sanitizeHeaderValue("SMS from caf\u00e9"))
    }

    @Test
    fun `sanitizeHeaderValue on empty string`() {
        assertEquals("", sanitizeHeaderValue(""))
    }

    @Test
    fun `truncatedToUtf8Bytes keeps a fitting value unchanged`() {
        assertEquals("hello", "hello".truncatedToUtf8Bytes(10))
        assertEquals("SMS from café", "SMS from café".truncatedToUtf8Bytes(100))
    }

    @Test
    fun `truncatedToUtf8Bytes cuts ASCII exactly at the cap`() {
        assertEquals("abcde", "abcdefgh".truncatedToUtf8Bytes(5))
    }

    @Test
    fun `truncatedToUtf8Bytes does not split a 2-byte character`() {
        // 'a'+'b' = 2 bytes, 'Я' needs 2 more but only 1 byte is left.
        assertEquals("ab", "abЯ".truncatedToUtf8Bytes(3))
    }

    @Test
    fun `truncatedToUtf8Bytes does not split a 3-byte character`() {
        // 'a' = 1 byte, '€' needs 3 more but only 2 bytes are left.
        assertEquals("a", "a€".truncatedToUtf8Bytes(3))
    }

    @Test
    fun `truncatedToUtf8Bytes does not split a 4-byte character`() {
        // Two emoji = 8 bytes, a third would need 12 of the 10-byte budget.
        assertEquals("😀😀", "😀😀😀😀😀".truncatedToUtf8Bytes(10))
    }

    @Test
    fun `truncatedToUtf8Bytes returns empty for a zero budget`() {
        assertEquals("", "abc".truncatedToUtf8Bytes(0))
    }

    @Test
    fun `truncatedToUtf8Bytes keeps a value that fits exactly`() {
        assertEquals("ЯЯЯЯ", "ЯЯЯЯ".truncatedToUtf8Bytes(8))
    }

    @Test
    fun `sanitizeSubject keeps a short subject unchanged`() {
        assertEquals("[myapp] SMS from ${TestPhone.SELF}", sanitizeSubject("[myapp] SMS from ${TestPhone.SELF}"))
    }

    @Test
    fun `sanitizeSubject cuts a long ASCII subject to the cap`() {
        val result = sanitizeSubject("A".repeat(2000))
        assertEquals("A".repeat(AppConfig.SUBJECT_MAX_BYTES), result)
        assertEquals(AppConfig.SUBJECT_MAX_BYTES, result.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `sanitizeSubject cuts a long non-ASCII subject on a character boundary`() {
        // 989 is an odd budget: 494 'Я' (2 bytes each) = 988 bytes, a 495th would need 990.
        val result = sanitizeSubject("Я".repeat(1000))
        assertEquals("Я".repeat(494), result)
        assertEquals(494 * 2, result.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `sanitizeSubject uses the odd budget exactly on a mixed subject`() {
        // 'a' = 1 byte, then 494 'Я' = 988 bytes: the full 989-byte budget.
        val result = sanitizeSubject("a" + "Я".repeat(1000))
        assertEquals("a" + "Я".repeat(494), result)
        assertEquals(AppConfig.SUBJECT_MAX_BYTES, result.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `sanitizeSubject strips control chars before truncating`() {
        val result = sanitizeSubject("123\r\nBcc: " + "A".repeat(2000))
        assertEquals("123Bcc: " + "A".repeat(AppConfig.SUBJECT_MAX_BYTES - "123Bcc: ".length), result)
    }

    @Test
    fun `sanitizeSubject never exceeds the cap in bytes`() {
        val subjects =
            listOf(
                "A".repeat(5000),
                "Я".repeat(5000),
                "😀".repeat(5000),
                "abc ".repeat(2000),
                ("x".repeat(120) + " ").repeat(20),
            )
        for (subject in subjects) {
            val result = sanitizeSubject(subject)
            assertTrue(result.toByteArray(Charsets.UTF_8).size <= AppConfig.SUBJECT_MAX_BYTES)
        }
    }

    @Test
    fun `truncatedToUtf8Bytes handles cut on continuation byte of multi-byte char`() {
        // 'a' (1 byte) + '€' (3 bytes: 0xE2 0x82 0xAC) = 4 bytes total
        // Cutting at 2 bytes lands on the first continuation byte (0x82)
        // The while loop should step left to drop the entire '€' character
        assertEquals("a", "a€".truncatedToUtf8Bytes(2))
    }

    @Test
    fun `truncatedToUtf8Bytes handles cut on second continuation byte`() {
        // 'a' (1 byte) + '€' (3 bytes) = 4 bytes
        // Cutting at 3 bytes lands on second continuation byte (0xAC)
        // The while loop steps left twice to drop the entire character
        assertEquals("a", "a€".truncatedToUtf8Bytes(3))
    }

    @Test
    fun `truncatedToUtf8Bytes handles cut on third continuation byte of 4-byte char`() {
        // 'a' (1 byte) + '😀' (4 bytes: F0 9F 98 80) = 5 bytes
        // Cutting at 4 lands on 0x80 (continuation byte)
        // While loop should step back 3 times to drop the emoji entirely
        assertEquals("a", "a😀".truncatedToUtf8Bytes(4))
    }

    @Test
    fun `truncatedToUtf8Bytes with all continuation bytes returns empty`() {
        // The 1-byte budget holds only '€'s leading byte E2; the cut lands on the
        // continuation 0x82, so the whole character is dropped and the result is empty.
        assertEquals("", "€".truncatedToUtf8Bytes(1))
    }
}
