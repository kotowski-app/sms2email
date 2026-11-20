package app.kotowski.sms2email.data.prefs

import app.kotowski.sms2email.AppConfig
import org.junit.Test
import kotlin.test.assertEquals

class SmtpProtocolTest {
    @Test
    fun `the START_TLS default port is 587`() {
        assertEquals(587, SmtpProtocol.START_TLS.defaultPort)
        assertEquals(AppConfig.SMTP_PORT_START_TLS, SmtpProtocol.START_TLS.defaultPort)
    }

    @Test
    fun `the IMPLICIT_TLS default port is 465`() {
        assertEquals(465, SmtpProtocol.IMPLICIT_TLS.defaultPort)
        assertEquals(AppConfig.SMTP_PORT_IMPLICIT_TLS, SmtpProtocol.IMPLICIT_TLS.defaultPort)
    }

    @Test
    fun `the protocol names are the persisted and exported values`() {
        assertEquals("START_TLS", SmtpProtocol.START_TLS.name)
        assertEquals("IMPLICIT_TLS", SmtpProtocol.IMPLICIT_TLS.name)
    }

    @Test
    fun `fromStored parses each protocol name`() {
        assertEquals(SmtpProtocol.START_TLS, SmtpProtocol.fromStored("START_TLS"))
        assertEquals(SmtpProtocol.IMPLICIT_TLS, SmtpProtocol.fromStored("IMPLICIT_TLS"))
    }

    @Test
    fun `fromStored degrades a missing value to START_TLS`() {
        assertEquals(SmtpProtocol.START_TLS, SmtpProtocol.fromStored(null))
    }

    @Test
    fun `fromStored degrades an unknown value to START_TLS`() {
        assertEquals(SmtpProtocol.START_TLS, SmtpProtocol.fromStored("NOT_A_PROTOCOL"))
    }
}
