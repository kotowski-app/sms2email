package app.kotowski.sms2email.service

import android.content.Context
import app.kotowski.sms2email.R
import app.kotowski.sms2email.util.Logger
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

// The network gate of the test runs: the production binding checks the real
// connectivity, the tests pass a stub.
fun interface NetworkCheck {
    fun check(context: Context): Boolean
}

// Runs the SMTP connection test and the test email from the shared form: gates
// on the network, runs the service against the passed values and maps any throw
// to a user message. It holds no prefs reference: a test run must never persist
// the draft fields it checks. [networkCheck] is the seam the tests use.
@Singleton
class SmtpTester
    @Inject
    constructor(
        private val emailService: EmailService,
        private val networkCheck: NetworkCheck,
    ) {
        suspend fun testConnection(
            config: SmtpConfig,
            context: Context,
        ): EmailResult = runGated(context, "testConnection") { emailService.testConnection(config) }

        suspend fun sendTestEmail(
            config: SmtpConfig,
            fields: TestEmailFields,
            context: Context,
        ): EmailResult = runGated(context, "sendTestEmail") { emailService.sendTestEmail(config, fields) }

        private suspend fun runGated(
            context: Context,
            label: String,
            block: suspend () -> EmailResult,
        ): EmailResult {
            if (!networkCheck.check(context)) {
                Logger.w("SmtpTester", "$label: no reliable internet, aborting")
                return EmailResult.Failure(context.getString(R.string.error_waiting_for_internet))
            }

            return try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("SmtpTester", "$label threw: ${SmtpErrorMapper.diagnostic(e)}")
                EmailResult.Failure(SmtpErrorMapper.toUserMessage(e, context))
            }
        }
    }
