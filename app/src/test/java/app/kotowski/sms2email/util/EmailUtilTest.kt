package app.kotowski.sms2email.util

import app.kotowski.sms2email.testing.TestEmail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.mail.internet.InternetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailUtilTest {
    @Test
    fun `extractDomain strips smtp prefix`() {
        assertEquals("example.com", EmailUtil.extractDomain("smtp.example.com"))
    }

    @Test
    fun `extractDomain strips smtp with digit suffix`() {
        assertEquals("example.com", EmailUtil.extractDomain("smtp0.example.com"))
        assertEquals("example.com", EmailUtil.extractDomain("smtp1.example.com"))
    }

    @Test
    fun `extractDomain strips smtp with hyphen`() {
        assertEquals("example.com", EmailUtil.extractDomain("smtp-relay.example.com"))
    }

    @Test
    fun `extractDomain strips mail prefix`() {
        assertEquals("example.com", EmailUtil.extractDomain("mail.example.com"))
    }

    @Test
    fun `extractDomain leaves host without recognized prefix`() {
        assertEquals("example.com", EmailUtil.extractDomain("example.com"))
        assertEquals("localhost", EmailUtil.extractDomain("localhost"))
    }

    @Test
    fun `extractDomain is case insensitive`() {
        assertEquals("example.com", EmailUtil.extractDomain("SMTP.EXAMPLE.COM"))
        assertEquals("example.com", EmailUtil.extractDomain("Mail.Example.com"))
    }

    @Test
    fun `extractDomain does not strip non smtp or mail prefix`() {
        assertEquals("pop.example.com", EmailUtil.extractDomain("pop.example.com"))
        assertEquals("imap.example.com", EmailUtil.extractDomain("imap.example.com"))
    }

    @Test
    fun `extractDomain lowercases a host without a recognized prefix`() {
        assertEquals("example.com", EmailUtil.extractDomain("Example.COM"))
        assertEquals("localhost", EmailUtil.extractDomain("LOCALHOST"))
    }

    @Test
    fun `extractDomain strips repeated server labels`() {
        assertEquals("example.com", EmailUtil.extractDomain("smtp.mail.example.com"))
        assertEquals("mail.example", EmailUtil.extractDomain("mail.mail.example"))
    }

    @Test
    fun `extractDomain never collapses a host below two labels`() {
        assertEquals("mail.example", EmailUtil.extractDomain("mail.example"))
        assertEquals("mail.test", EmailUtil.extractDomain("mail.test"))
        assertEquals("mail.example", EmailUtil.extractDomain("smtp.mail.example"))
        assertEquals("smtp.test", EmailUtil.extractDomain("smtp.test"))
        assertEquals("mail.example", EmailUtil.extractDomain("mail.example"))
    }

    @Test
    fun `deriveSenderEmail returns username when it is already an email`() {
        assertEquals(TestEmail.SELF, EmailUtil.deriveSenderEmail(TestEmail.SELF, "smtp.example.com"))
    }

    @Test
    fun `deriveSenderEmail combines username and extracted domain`() {
        assertEquals("john@example.com", EmailUtil.deriveSenderEmail("john", "smtp.example.com"))
        assertEquals("john@example.com", EmailUtil.deriveSenderEmail("john", "mail.example.com"))
        assertEquals("john@example.com", EmailUtil.deriveSenderEmail("john", "smtp0.example.com"))
    }

    @Test
    fun `deriveSenderEmail uses host as-is when no prefix to strip`() {
        assertEquals("john@example.com", EmailUtil.deriveSenderEmail("john", "example.com"))
        assertEquals("john@localhost", EmailUtil.deriveSenderEmail("john", "localhost"))
    }

    @Test
    fun `deriveSenderEmail returns empty on missing inputs`() {
        assertEquals("", EmailUtil.deriveSenderEmail("", "smtp.example.com"))
        assertEquals("", EmailUtil.deriveSenderEmail("john", ""))
        assertEquals("", EmailUtil.deriveSenderEmail("", ""))
    }

    @Test
    fun `deriveSenderEmail ignores the edge spaces of the padded draft values`() {
        assertEquals("john@example.com", EmailUtil.deriveSenderEmail(" john ", " smtp.example.com "))
        assertEquals(TestEmail.SELF, EmailUtil.deriveSenderEmail(" ${TestEmail.SELF} ", "smtp.example.com"))
        assertEquals("", EmailUtil.deriveSenderEmail("   ", "smtp.example.com"))
        assertEquals("", EmailUtil.deriveSenderEmail("john", "   "))
    }

    @Test
    fun `deriveSenderEmail preserves username with special characters`() {
        assertEquals("john.doe@example.com", EmailUtil.deriveSenderEmail("john.doe", "smtp.example.com"))
        assertEquals("john+tag@example.com", EmailUtil.deriveSenderEmail("john+tag", "smtp.example.com"))
    }

    @Test
    fun `deriveSenderEmail with username containing @ is treated as email`() {
        val result = EmailUtil.deriveSenderEmail("me@example.org", "smtp.example.org")
        assertEquals("me@example.org", result)
    }

    @Test
    fun `isValidEmail accepts an email address`() {
        assertTrue(EmailUtil.isValidEmail("john@example.com"))
        assertTrue(EmailUtil.isValidEmail("john.doe+tag@example.com"))
    }

    @Test
    fun `isValidEmail rejects a non email address`() {
        assertFalse(EmailUtil.isValidEmail("not-an-email"))
        assertFalse(EmailUtil.isValidEmail("a@"))
        assertFalse(EmailUtil.isValidEmail("@example.com"))
        assertFalse(EmailUtil.isValidEmail("a b@example.com"))
        assertFalse(EmailUtil.isValidEmail(""))
    }

    @Test
    fun `isInvalidEmail is true only for a non empty invalid draft`() {
        assertFalse(EmailUtil.isInvalidEmail(""))
        assertFalse(EmailUtil.isInvalidEmail("john@example.com"))
        assertTrue(EmailUtil.isInvalidEmail("not-an-email"))
        assertTrue(EmailUtil.isInvalidEmail("a@"))
    }

    @Test
    fun `encodePersonalName returns unchanged for ASCII without special chars`() {
        assertEquals("JohnDoe", EmailUtil.encodePersonalName("JohnDoe"))
    }

    @Test
    fun `encodePersonalName passes special chars through unchanged for JavaMail quoting`() {
        assertEquals("John, Doe", EmailUtil.encodePersonalName("John, Doe"))
    }

    @Test
    fun `encodePersonalName passes quotes and backslashes through unchanged`() {
        assertEquals("Mr. \"Big\" Test", EmailUtil.encodePersonalName("Mr. \"Big\" Test"))
        assertEquals("Back\\slash", EmailUtil.encodePersonalName("Back\\slash"))
    }

    @Test
    fun `encodePersonalName strips control characters from the name`() {
        assertEquals("Line1Bcc: evil@example.com", EmailUtil.encodePersonalName("Line1\r\nBcc: evil@example.com"))
    }

    @Test
    fun `encodePersonalName encodes non-ASCII characters`() {
        val result = EmailUtil.encodePersonalName("Müller")
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `encodePersonalName renders a valid header for a name containing quotes`() {
        val addr = InternetAddress("sender@example.com", EmailUtil.encodePersonalName("Mr. \"Big\" Test"), "UTF-8")
        assertEquals("\"Mr. \\\"Big\\\" Test\" <sender@example.com>", addr.toString())
    }

    @Test
    fun `encodePersonalName renders no raw line breaks in the header for a name containing CRLF`() {
        val addr = InternetAddress("sender@example.com", EmailUtil.encodePersonalName("Line1\r\nBcc: evil@example.com"), "UTF-8")
        val header = addr.toString()
        assertTrue(!header.contains("\r") && !header.contains("\n"))
    }
}
