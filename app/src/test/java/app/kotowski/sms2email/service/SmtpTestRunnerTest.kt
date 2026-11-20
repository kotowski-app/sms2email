package app.kotowski.sms2email.service

import android.content.Context
import app.kotowski.sms2email.data.prefs.SmtpProtocol
import app.kotowski.sms2email.testing.TestEmail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SmtpTestRunnerTest {
    @Mock
    private lateinit var emailService: EmailService

    @Mock
    private lateinit var context: Context

    private lateinit var runner: SmtpTestRunner

    private val config = SmtpConfig("h", 587, SmtpProtocol.START_TLS, "u", "p")

    private val fields = TestEmailFields(TestEmail.SELF, "S", TestEmail.RECIPIENT, "D")

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        runner = SmtpTestRunner(SmtpTester(emailService) { true })
    }

    @Test
    fun `testConnection returns the service success result`() =
        runTest {
            whenever(emailService.testConnection(config)).thenReturn(EmailResult.Success)
            val result = runner.testConnection(config, context)
            assertTrue(result is EmailResult.Success)
        }

    @Test
    fun `testConnection returns the service failure result`() =
        runTest {
            whenever(emailService.testConnection(config)).thenReturn(EmailResult.Failure("refused"))
            val result = runner.testConnection(config, context)
            assertTrue(result is EmailResult.Failure)
        }

    @Test
    fun `testConnection waits the minimum delay after a fast result`() =
        runTest {
            whenever(emailService.testConnection(config)).thenReturn(EmailResult.Success)
            val start = testScheduler.currentTime
            runner.testConnection(config, context)
            assertTrue(testScheduler.currentTime - start in 2900L..3000L)
        }

    @Test
    fun `sendTestEmail returns the service success result`() =
        runTest {
            whenever(emailService.sendTestEmail(config, fields)).thenReturn(EmailResult.Success)
            val result = runner.sendTestEmail(config, fields, context)
            assertTrue(result is EmailResult.Success)
        }

    @Test
    fun `sendTestEmail returns the service failure result`() =
        runTest {
            whenever(emailService.sendTestEmail(config, fields)).thenReturn(EmailResult.Failure("nope"))
            val result = runner.sendTestEmail(config, fields, context)
            assertTrue(result is EmailResult.Failure)
            assertEquals("nope", result.message)
        }

    @Test
    fun `sendTestEmail waits the minimum delay after a fast result`() =
        runTest {
            whenever(emailService.sendTestEmail(config, fields)).thenReturn(EmailResult.Success)
            val start = testScheduler.currentTime
            runner.sendTestEmail(config, fields, context)
            assertTrue(testScheduler.currentTime - start in 2900L..3000L)
        }

    @Test
    fun `ensureMinDelay skips the delay when the elapsed time exceeds the threshold`() =
        runTest {
            val start = testScheduler.currentTime
            testScheduler.advanceTimeBy(4000)
            runner.ensureMinDelay(start - 4000)
            assertTrue(testScheduler.currentTime - start == 4000L)
        }
}
