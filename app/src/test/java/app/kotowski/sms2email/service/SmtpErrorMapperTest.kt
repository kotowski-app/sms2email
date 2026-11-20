package app.kotowski.sms2email.service

import com.sun.mail.smtp.SMTPAddressFailedException
import com.sun.mail.smtp.SMTPSendFailedException
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.mail.AuthenticationFailedException
import javax.mail.MessagingException
import javax.mail.SendFailedException
import javax.mail.internet.InternetAddress
import javax.net.ssl.SSLException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmtpErrorMapperTest {
    @Mock
    private lateinit var context: android.content.Context

    private val authFailed = "Authentication failed"
    private val timeout = "Timed out"
    private val networkError = "Network error"
    private val dnsError = "DNS resolution failed"
    private val connectionError = "Connection refused"
    private val socketError = "Network unreachable or connection dropped"
    private val sslPortError = "SSL/port mismatch"
    private val unknown = "Unexpected error"
    private val smtp550 = "Recipient does not exist"

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        `when`(context.getString(app.kotowski.sms2email.R.string.smtp_auth_failed)).thenReturn(authFailed)
        `when`(context.getString(app.kotowski.sms2email.R.string.smtp_timeout)).thenReturn(timeout)
        `when`(context.getString(app.kotowski.sms2email.R.string.smtp_network_error)).thenReturn(networkError)
        `when`(context.getString(app.kotowski.sms2email.R.string.smtp_dns_error)).thenReturn(dnsError)
        `when`(context.getString(app.kotowski.sms2email.R.string.smtp_connection_error)).thenReturn(connectionError)
        `when`(context.getString(app.kotowski.sms2email.R.string.smtp_socket_error)).thenReturn(socketError)
        `when`(context.getString(app.kotowski.sms2email.R.string.smtp_ssl_port_error)).thenReturn(sslPortError)
        `when`(context.getString(app.kotowski.sms2email.R.string.smtp_unknown)).thenReturn(unknown)
        `when`(context.getString(app.kotowski.sms2email.R.string.smtp_550)).thenReturn(smtp550)
    }

    @Test
    fun `AuthenticationFailedException returns auth failed message`() {
        val e = AuthenticationFailedException("auth failed")
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals(authFailed, msg)
    }

    @Test
    fun `SocketTimeoutException returns timeout message`() {
        val e = SocketTimeoutException("read timed out")
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals(timeout, msg)
    }

    @Test
    fun `direct IOException returns network error message`() {
        val e = java.io.IOException("network unreachable")
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals(networkError, msg)
    }

    @Test
    fun `MessagingException with UnknownHostException cause returns DNS error`() {
        val cause = UnknownHostException("smtp.invalid.domain")
        val e = MessagingException("Could not connect to SMTP host", cause)
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals(dnsError, msg)
    }

    @Test
    fun `MessagingException with UnknownHostException nextException returns DNS error`() {
        val dnsErr = UnknownHostException("smtp.invalid.domain")
        val e = MessagingException("Could not connect")
        e.setNextException(dnsErr)
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals(dnsError, msg)
    }

    @Test
    fun `MessagingException with ConnectException cause returns connection error`() {
        val connError = ConnectException("Connection refused")
        val e = MessagingException("Connect failed", connError)
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals(connectionError, msg)
    }

    @Test
    fun `MessagingException with SocketException cause returns socket error`() {
        val cause = SocketException("Network unreachable")
        val e = MessagingException("Connect failed", cause)
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals(socketError, msg)
    }

    @Test
    fun `MessagingException with SocketException nextException returns socket error`() {
        val socketErr = SocketException("Connection reset")
        val e = MessagingException("Operation failed")
        e.setNextException(socketErr)
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals(socketError, msg)
    }

    @Test
    fun `SMTPAddressFailedException with returnCode 550 returns mapped message`() {
        val addr = InternetAddress("test@example.com")
        val e = SMTPAddressFailedException(addr, null, 550, "User not found")
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals(smtp550, msg)
    }

    @Test
    fun `MessagingException with no SMTP code and no network cause returns unknown`() {
        val e = MessagingException("Something weird happened")
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals(unknown, msg)
    }

    @Test
    fun `plain RuntimeException returns unknown`() {
        val e = RuntimeException("unexpected crash")
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals(unknown, msg)
    }

    @Test
    fun `cause takes precedence over nextException for network errors`() {
        val dnsErr = UnknownHostException("dns failed")
        val sockErr = SocketException("socket failed")
        val e = MessagingException("operation failed", dnsErr)
        e.setNextException(sockErr)
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals(dnsError, msg)
    }

    @Test
    fun `nextException used when cause is null`() {
        val connErr = ConnectException("Connection refused")
        val e = MessagingException("operation failed")
        e.setNextException(connErr)
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals(connectionError, msg)
    }

    @Test
    fun `deeply nested SocketTimeoutException is unwrapped correctly`() {
        val cause = SocketTimeoutException("connect timed out")
        val inner = MessagingException("inner wrap", cause)
        val outer = MessagingException("outer wrap", inner)
        val msg = SmtpErrorMapper.toUserMessage(outer, context)
        assertEquals(timeout, msg)
    }

    @Test
    fun `deeply nested ConnectException is unwrapped correctly`() {
        val connErr = ConnectException("Connection refused")
        val inner = MessagingException("inner wrap", connErr)
        val outer = MessagingException("outer wrap", inner)
        val msg = SmtpErrorMapper.toUserMessage(outer, context)
        assertEquals(connectionError, msg)
    }

    @Test
    fun `SSLException root cause returns ssl port error`() {
        val sslErr = SSLException("SSL handshake failed")
        val e = MessagingException("connect failed", sslErr)
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals(sslPortError, msg)
    }

    @Test
    fun `EOFException root cause returns ssl port error`() {
        val eofErr = EOFException("SSL peer shut down incorrectly")
        val e = MessagingException("connect failed", eofErr)
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals(sslPortError, msg)
    }

    @Test
    fun `isPermanent returns true for AuthenticationFailedException`() {
        val e = AuthenticationFailedException("failed to connect")
        assertTrue(SmtpErrorMapper.isPermanent(e))
    }

    @Test
    fun `isPermanent returns true for SendFailedException with invalid addresses`() {
        val e =
            SendFailedException(
                "Invalid Addresses",
                null,
                emptyArray(),
                emptyArray(),
                arrayOf(InternetAddress("bad@example.com")),
            )
        assertTrue(SmtpErrorMapper.isPermanent(e))
    }

    @Test
    fun `isPermanent returns false for SendFailedException with only valid unsent addresses`() {
        // 552 quota: JavaMail classifies the address as valid (retryable), not invalid
        val e =
            SendFailedException(
                "Invalid Addresses",
                null,
                emptyArray(),
                arrayOf(InternetAddress("full@example.com")),
                emptyArray(),
            )
        assertFalse(SmtpErrorMapper.isPermanent(e))
    }

    @Test
    fun `isPermanent uses the address split, not the chained code, for SendFailedException`() {
        val chained =
            SMTPAddressFailedException(
                InternetAddress("full@example.com"),
                "RCPT TO:<full@example.com>",
                552,
                "552 5.2.2 Mailbox full",
            )
        val e =
            SendFailedException(
                "Invalid Addresses",
                chained,
                emptyArray(),
                arrayOf(InternetAddress("full@example.com")),
                emptyArray(),
            )
        assertFalse(SmtpErrorMapper.isPermanent(e))
    }

    @Test
    fun `isPermanent returns false for SendFailedException without address arrays`() {
        val e = SendFailedException("No recipient addresses")
        assertFalse(SmtpErrorMapper.isPermanent(e))
    }

    @Test
    fun `isPermanent returns true for SMTPSendFailedException with a 5xx code`() {
        val e = SMTPSendFailedException(".", 554, "554 5.7.1 Message rejected", null, null, null, null)
        assertTrue(SmtpErrorMapper.isPermanent(e))
    }

    @Test
    fun `isPermanent returns false for SMTPSendFailedException with a 4xx code`() {
        val e = SMTPSendFailedException("DATA", 451, "451 Try again later", null, null, null, null)
        assertFalse(SmtpErrorMapper.isPermanent(e))
    }

    @Test
    fun `isPermanent returns false for SMTPSendFailedException with no code (EOF)`() {
        val e = SMTPSendFailedException("DATA", -1, "[EOF]", null, null, null, null)
        assertFalse(SmtpErrorMapper.isPermanent(e))
    }

    @Test
    fun `isPermanent returns false for network-level MessagingException`() {
        val e = MessagingException("Could not connect to SMTP host", ConnectException("Connection refused"))
        assertFalse(SmtpErrorMapper.isPermanent(e))
    }

    @Test
    fun `isPermanent returns false for non-SMTP exceptions`() {
        assertFalse(SmtpErrorMapper.isPermanent(RuntimeException("unexpected crash")))
    }

    @Test
    fun `InterruptedIOException returns timeout message`() {
        val e = InterruptedIOException("operation interrupted")
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals(timeout, msg)
    }

    @Test
    fun `MessagingException wrapping SMTPSendFailedException returns mapped message`() {
        val inner = SMTPSendFailedException("DATA", 554, "554 Message rejected", null, null, null, null)
        val e = MessagingException("send failed", inner)
        org.mockito.Mockito.`when`(context.getString(app.kotowski.sms2email.R.string.smtp_554)).thenReturn(
            "Message rejected by server",
        )
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals("Message rejected by server", msg)
    }

    @Test
    fun `enhanced status code in message resolves to specific string`() {
        val e = SMTPSendFailedException("DATA", 550, "550 5.1.1 User unknown", null, null, null, null)
        org.mockito.Mockito
            .`when`(
                context.getString(app.kotowski.sms2email.R.string.smtp_enh_5_1_1),
            ).thenReturn("Recipient address rejected")
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals("Recipient address rejected", msg)
    }

    @Test
    fun `unmapped 4xx code returns transient fallback`() {
        val e = SMTPSendFailedException("DATA", 499, "499 Unknown transient", null, null, null, null)
        org.mockito.Mockito
            .`when`(
                context.getString(app.kotowski.sms2email.R.string.smtp_transient_fallback, 499),
            ).thenReturn("Transient error 499")
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals("Transient error 499", msg)
    }

    @Test
    fun `unmapped 5xx code returns permanent fallback`() {
        val e = SMTPSendFailedException("DATA", 599, "599 Unknown permanent", null, null, null, null)
        org.mockito.Mockito
            .`when`(
                context.getString(app.kotowski.sms2email.R.string.smtp_permanent_fallback, 599),
            ).thenReturn("Permanent error 599")
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals("Permanent error 599", msg)
    }

    @Test
    fun `isPermanent returns true for SMTPAddressFailedException with 5xx code`() {
        val addr = InternetAddress("test@example.com")
        val e = SMTPAddressFailedException(addr, null, 550, "User not found")
        assertTrue(SmtpErrorMapper.isPermanent(e))
    }

    @Test
    fun `isPermanent returns false for SMTPAddressFailedException with 4xx code`() {
        val addr = InternetAddress("test@example.com")
        val e = SMTPAddressFailedException(addr, null, 450, "Try again")
        assertFalse(SmtpErrorMapper.isPermanent(e))
    }

    @Test
    fun `resolveNetworkCause with nested SocketTimeoutException returns timeout`() {
        val cause = SocketTimeoutException("read timed out")
        val e = MessagingException("connect failed", cause)
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals(timeout, msg)
    }

    @Test
    fun `enhanced code present but unrecognized falls through to code map`() {
        val e = SMTPSendFailedException("DATA", 550, "550 9.9.9 Unknown enhanced", null, null, null, null)
        org.mockito.Mockito.`when`(context.getString(app.kotowski.sms2email.R.string.smtp_550)).thenReturn(
            "Recipient does not exist",
        )
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals("Recipient does not exist", msg)
    }

    @Test
    fun `isPermanent uses unwrapSmtpException else branch for MessagingException with 5xx code`() {
        val inner = SMTPSendFailedException("DATA", 554, "554 Rejected", null, null, null, null)
        val e = MessagingException("send failed", inner)
        assertTrue(SmtpErrorMapper.isPermanent(e))
    }

    @Test
    fun `isPermanent uses unwrapSmtpException else branch for MessagingException with 4xx code`() {
        val inner = SMTPSendFailedException("DATA", 451, "451 Try again", null, null, null, null)
        val e = MessagingException("send failed", inner)
        assertFalse(SmtpErrorMapper.isPermanent(e))
    }

    @Test
    fun `SMTPSendFailedException with no enhanced code falls through to code map`() {
        val e = SMTPSendFailedException("DATA", 554, "554 Rejected", null, null, null, null)
        org.mockito.Mockito.`when`(context.getString(app.kotowski.sms2email.R.string.smtp_554)).thenReturn(
            "Transaction failed",
        )
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals("Transaction failed", msg)
    }

    @Test
    fun `unwrapSmtpException stops at non-MessagingException`() {
        val e = RuntimeException("unexpected", SMTPSendFailedException("DATA", 550, "550", null, null, null, null))
        org.mockito.Mockito.`when`(context.getString(app.kotowski.sms2email.R.string.smtp_unknown)).thenReturn(
            "Unknown error",
        )
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals("Unknown error", msg)
    }

    // Range boundary: returnCode above 599 is not in the 500..599 permanent range.
    @Test
    fun `isPermanent returns false for SMTPSendFailedException with returnCode 600`() {
        val e = SMTPSendFailedException("DATA", 600, "600 Unknown", null, null, null, null)
        assertFalse(SmtpErrorMapper.isPermanent(e))
    }

    @Test
    fun `isPermanent returns false for SMTPAddressFailedException with returnCode 600`() {
        val addr = InternetAddress("test@example.com")
        val e = SMTPAddressFailedException(addr, null, 600, "Unknown")
        assertFalse(SmtpErrorMapper.isPermanent(e))
    }

    // The else branch of isPermanent unwraps via unwrapSmtpException (nextException chain)
    // and checks the code range; code above 599 is transient.
    @Test
    fun `isPermanent returns false when unwrapped code via nextException exceeds 599`() {
        val inner = SMTPSendFailedException("DATA", 600, "600 Unknown", null, null, null, null)
        val e = MessagingException("wrapped")
        e.setNextException(inner)
        assertFalse(SmtpErrorMapper.isPermanent(e))
    }

    // An enhanced code pattern that matches the regex but has no entry in EnhancedCodeMap
    // should fall through to the code-map lookup, then to the fallback.
    @Test
    fun `enhanced code that matches regex but is not in map falls through to code map`() {
        val e = SMTPSendFailedException("DATA", 550, "550 5.0.0 Unknown enhanced", null, null, null, null)
        org.mockito.Mockito.`when`(context.getString(app.kotowski.sms2email.R.string.smtp_550)).thenReturn(
            "Recipient does not exist",
        )
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals("Recipient does not exist", msg)
    }

    // getRootCause must detect a circular reference (cause or nextException pointing back
    // to the current throwable) and break out instead of looping forever.
    @Test
    fun `getRootCause detects circular reference and stops`() {
        val circular = CircularThrowable()
        val msg = SmtpErrorMapper.toUserMessage(circular, context)
        assertEquals(unknown, msg)
    }

    // getRootCause follows nextException from a MessagingException into a non-MessagingException
    // wrapper, then follows its cause to the actual root. This exercises the else -> null
    // branch for the nextException check when the intermediate throwable is not a MessagingException.
    @Test
    fun `getRootCause follows cause through non-MessagingException intermediate`() {
        val dnsErr = UnknownHostException("dns failed")
        val wrapper = RuntimeException("wrap", dnsErr)
        val e = MessagingException("outer")
        e.setNextException(wrapper)
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals(dnsError, msg)
    }

    // getRootCause: when MessagingException has both nextException and cause,
    // nextException is preferred — the cause branch (next == null) is exercised here.
    @Test
    fun `getRootCause prefers cause when nextException is null`() {
        val socketErr = SocketException("network down")
        val e = MessagingException("connect failed", socketErr)
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals(socketError, msg)
    }

    // isPermanent: SendFailedException where invalidAddresses is empty but
    // validUnsentAddresses is also empty (no addresses at all).
    @Test
    fun `isPermanent returns false for SendFailedException with no addresses at all`() {
        val e = SendFailedException("no addresses")
        assertFalse(SmtpErrorMapper.isPermanent(e))
    }

    // isPermanent: range boundary at 499 (transient, not permanent).
    @Test
    fun `isPermanent returns false for SMTPSendFailedException with returnCode 499`() {
        val e = SMTPSendFailedException("DATA", 499, "499 Transient", null, null, null, null)
        assertFalse(SmtpErrorMapper.isPermanent(e))
    }

    // isPermanent: else-branch unwrapSmtpException with code exactly 500 (permanent boundary).
    @Test
    fun `isPermanent returns true when unwrapped code is exactly 500`() {
        val inner = SMTPSendFailedException("DATA", 500, "500 Syntax", null, null, null, null)
        val e = MessagingException("wrapped")
        e.setNextException(inner)
        assertTrue(SmtpErrorMapper.isPermanent(e))
    }

    // toUserMessage: returnCode exactly 0 (not > 0) falls through to unknown.
    @Test
    fun `toUserMessage with returnCode 0 returns unknown`() {
        val e = SMTPSendFailedException("DATA", 0, "[EOF]", null, null, null, null)
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals(unknown, msg)
    }

    // A throwable whose cause property returns itself — used to exercise getRootCause's
    // circular-reference guard (deeper == current).
    private class CircularThrowable : Throwable() {
        override val cause: Throwable
            get() = this
    }

    // isPermanent: else-branch with MessagingException -> nextException -> SMTPSendFailedException
    // with returnCode 550 (permanent). The unwrapSmtpException walks the nextException chain.
    @Test
    fun `isPermanent returns true when nextException chain leads to 5xx`() {
        val inner = SMTPSendFailedException("DATA", 550, "550 User unknown", null, null, null, null)
        val middle = MessagingException("connection failed")
        middle.setNextException(inner)
        val outer = MessagingException("outer wrap", middle)
        assertTrue(SmtpErrorMapper.isPermanent(outer))
    }

    // isPermanent: else-branch with MessagingException -> nextException -> SMTPSendFailedException
    // with returnCode 450 (transient).
    @Test
    fun `isPermanent returns false when nextException chain leads to 4xx`() {
        val inner = SMTPSendFailedException("DATA", 450, "450 Try again", null, null, null, null)
        val e = MessagingException("wrapped")
        e.setNextException(inner)
        assertFalse(SmtpErrorMapper.isPermanent(e))
    }

    // toUserMessage: returnCode negative (-1, EOF) falls to unknown.
    @Test
    fun `toUserMessage with returnCode -1 returns unknown`() {
        val e = SMTPSendFailedException("DATA", -1, "[EOF]", null, null, null, null)
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals(unknown, msg)
    }

    // toUserMessage: SMTPAddressFailedException with returnCode 550 directly (not wrapped).
    @Test
    fun `toUserMessage with direct SMTPAddressFailedException returns mapped message`() {
        val addr = InternetAddress("bad@example.com")
        val e = SMTPAddressFailedException(addr, null, 550, "550 User not found")
        val msg = SmtpErrorMapper.toUserMessage(e, context)
        assertEquals(smtp550, msg)
    }

    // diagnostic: the PII-safe log form (class name + optional SMTP code, never the raw message).
    @Test
    fun `diagnostic is the class name for a non-SMTP exception`() {
        assertEquals("RuntimeException", SmtpErrorMapper.diagnostic(RuntimeException("boom")))
    }

    @Test
    fun `diagnostic appends the SMTP return code when one is extractable`() {
        val e = SMTPSendFailedException("DATA", 554, "554 5.7.1 Message rejected", null, null, null, null)
        assertEquals("SMTPSendFailedException, code=554", SmtpErrorMapper.diagnostic(e))
    }

    @Test
    fun `diagnostic extracts the code from a wrapped chain`() {
        val inner = SMTPSendFailedException("DATA", 550, "550 User unknown", null, null, null, null)
        val e = MessagingException("send failed", inner)
        assertEquals("MessagingException, code=550", SmtpErrorMapper.diagnostic(e))
    }

    @Test
    fun `diagnostic omits the code when none is extractable`() {
        val e = SMTPSendFailedException("DATA", -1, "[EOF]", null, null, null, null)
        assertEquals("SMTPSendFailedException", SmtpErrorMapper.diagnostic(e))
    }

    @Test
    fun `diagnostic never includes the raw server message`() {
        val e = SMTPSendFailedException("DATA", 554, "554 5.7.1 Message rejected", null, null, null, null)
        val diagnostic = SmtpErrorMapper.diagnostic(e)
        assertFalse(diagnostic.contains("Message rejected"))
        assertFalse(diagnostic.contains("5.7.1"))
    }

    // isPermanent: SendFailedException with both invalid and valid-unsent addresses
    // — the invalidAddresses is non-empty, so it's permanent.
    @Test
    fun `isPermanent returns true for SendFailedException with both invalid and validUnsent`() {
        val e =
            SendFailedException(
                "Mixed",
                null,
                emptyArray(),
                arrayOf(InternetAddress("quota@example.com")),
                arrayOf(InternetAddress("invalid@example.com")),
            )
        assertTrue(SmtpErrorMapper.isPermanent(e))
    }
}
