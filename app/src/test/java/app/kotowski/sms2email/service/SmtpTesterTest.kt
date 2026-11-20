package app.kotowski.sms2email.service

import android.content.Context
import app.kotowski.sms2email.LogLevel
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.prefs.SmtpProtocol
import app.kotowski.sms2email.testing.TestEmail
import app.kotowski.sms2email.util.LogBuffer
import app.kotowski.sms2email.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test as KotlinTest

@ExperimentalCoroutinesApi
class SmtpTesterTest {
    @Mock
    private lateinit var emailService: EmailService

    @Mock
    private lateinit var context: Context

    private lateinit var tester: SmtpTester

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        tester = SmtpTester(emailService) { true }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @KotlinTest
    fun `testConnection passes the config to the service`() =
        runTest {
            val config = SmtpConfig("smtp.example.com", 587, SmtpProtocol.START_TLS, TestEmail.SELF, "secret")
            `when`(emailService.testConnection(config)).thenReturn(EmailResult.Success)

            tester.testConnection(config, context)

            verify(emailService).testConnection(config)
        }

    @KotlinTest
    fun `testConnection returns success result`() =
        runTest {
            val config = SmtpConfig("smtp.example.com", 587, SmtpProtocol.START_TLS, TestEmail.SELF, "secret")
            `when`(emailService.testConnection(config)).thenReturn(EmailResult.Success)

            val result = tester.testConnection(config, context)

            assertTrue(result is EmailResult.Success)
        }

    @KotlinTest
    fun `testConnection returns failure on exception`() =
        runTest {
            `when`(context.getString(R.string.smtp_unknown)).thenReturn("Unknown error")
            val config = SmtpConfig("smtp.example.com", 587, SmtpProtocol.START_TLS, TestEmail.SELF, "secret")
            `when`(emailService.testConnection(config)).thenThrow(RuntimeException("connection refused"))

            val result = tester.testConnection(config, context)

            assertTrue(result is EmailResult.Failure)
            assertEquals("Unknown error", result.message)
        }

    // Pinned: the failure line carries the diagnostic (class name, no raw message), so an
    // SMTP host or server response text can never reach the log from this path.
    @KotlinTest
    fun `testConnection failure logs the diagnostic without the raw message`() =
        runTest {
            Logger.setLevel(LogLevel.DEBUG)
            `when`(context.getString(R.string.smtp_unknown)).thenReturn("Unknown error")
            val config = SmtpConfig("smtp.example.com", 587, SmtpProtocol.START_TLS, TestEmail.SELF, "secret")
            `when`(emailService.testConnection(config)).thenThrow(RuntimeException("connection refused"))

            tester.testConnection(config, context)

            val entry = LogBuffer.entries.last()
            assertEquals("SmtpTester", entry.tag)
            assertEquals("testConnection threw: RuntimeException", entry.message)
        }

    @KotlinTest
    fun `sendTestEmail passes the config and fields to the service`() =
        runTest {
            val config = SmtpConfig("smtp.example.com", 465, SmtpProtocol.IMPLICIT_TLS, TestEmail.SELF, "secret")
            val fields = TestEmailFields("sender@example.com", "Sender", "dest@example.com", "Dest")
            `when`(emailService.sendTestEmail(config, fields)).thenReturn(EmailResult.Success)

            tester.sendTestEmail(config, fields, context)

            verify(emailService).sendTestEmail(config, fields)
        }

    @KotlinTest
    fun `sendTestEmail returns success result`() =
        runTest {
            val config = SmtpConfig("smtp.example.com", 587, SmtpProtocol.START_TLS, TestEmail.SELF, "secret")
            val fields = TestEmailFields("sender@example.com", "", "dest@example.com", "")
            `when`(emailService.sendTestEmail(config, fields)).thenReturn(EmailResult.Success)

            val result = tester.sendTestEmail(config, fields, context)

            assertTrue(result is EmailResult.Success)
        }

    @KotlinTest
    fun `sendTestEmail returns failure on exception`() =
        runTest {
            `when`(context.getString(R.string.smtp_unknown)).thenReturn("Unknown error")
            val config = SmtpConfig("smtp.example.com", 587, SmtpProtocol.START_TLS, TestEmail.SELF, "secret")
            val fields = TestEmailFields("sender@example.com", "", "dest@example.com", "")
            `when`(emailService.sendTestEmail(config, fields)).thenThrow(RuntimeException("send failed"))

            val result = tester.sendTestEmail(config, fields, context)

            assertTrue(result is EmailResult.Failure)
            assertEquals("Unknown error", result.message)
        }

    @KotlinTest
    fun `testConnection with null exception message maps to unknown error`() =
        runTest {
            `when`(context.getString(R.string.smtp_unknown)).thenReturn("Unknown error")
            val ex = RuntimeException(null as String?)
            val config = SmtpConfig("smtp.example.com", 587, SmtpProtocol.START_TLS, TestEmail.SELF, "secret")
            `when`(emailService.testConnection(config)).thenThrow(ex)

            val result = tester.testConnection(config, context)

            assertTrue(result is EmailResult.Failure)
            assertEquals("Unknown error", result.message)
        }

    @KotlinTest
    fun `testConnection rethrows cancellation`() =
        runTest {
            val config = SmtpConfig("smtp.example.com", 587, SmtpProtocol.START_TLS, TestEmail.SELF, "secret")
            `when`(emailService.testConnection(config)).thenThrow(CancellationException())

            assertFailsWith<CancellationException> {
                tester.testConnection(config, context)
            }
        }

    @KotlinTest
    fun `sendTestEmail rethrows cancellation`() =
        runTest {
            val config = SmtpConfig("smtp.example.com", 587, SmtpProtocol.START_TLS, TestEmail.SELF, "secret")
            val fields = TestEmailFields("sender@example.com", "", "dest@example.com", "")
            `when`(emailService.sendTestEmail(config, fields)).thenThrow(CancellationException())

            assertFailsWith<CancellationException> {
                tester.sendTestEmail(config, fields, context)
            }
        }

    @KotlinTest
    fun `testConnection returns no internet failure when network check fails`() =
        runTest {
            val offlineTester = SmtpTester(emailService) { false }
            `when`(context.getString(org.mockito.Mockito.anyInt())).thenReturn("No internet")
            val config = SmtpConfig("smtp.example.com", 587, SmtpProtocol.START_TLS, TestEmail.SELF, "secret")

            val result = offlineTester.testConnection(config, context)

            assertTrue(result is EmailResult.Failure)
            assertEquals("No internet", result.message)
            verify(emailService, org.mockito.Mockito.never()).testConnection(config)
        }

    @KotlinTest
    fun `sendTestEmail returns no internet failure when network check fails`() =
        runTest {
            val offlineTester = SmtpTester(emailService) { false }
            `when`(context.getString(org.mockito.Mockito.anyInt())).thenReturn("No internet")
            val config = SmtpConfig("smtp.example.com", 587, SmtpProtocol.START_TLS, TestEmail.SELF, "secret")
            val fields = TestEmailFields("sender@example.com", "", "dest@example.com", "")

            val result = offlineTester.sendTestEmail(config, fields, context)

            assertTrue(result is EmailResult.Failure)
            assertEquals("No internet", result.message)
            verify(emailService, org.mockito.Mockito.never()).sendTestEmail(config, fields)
        }
}
