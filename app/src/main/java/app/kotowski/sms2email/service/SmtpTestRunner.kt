package app.kotowski.sms2email.service

import android.content.Context
import app.kotowski.sms2email.AppConfig
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

// Wraps [SmtpTester] so the form's test buttons wait a minimum delay before
// the result lands: a fast failure must not flash on the screen.
@Singleton
class SmtpTestRunner
    @Inject
    constructor(
        private val tester: SmtpTester,
    ) {
        suspend fun testConnection(
            config: SmtpConfig,
            context: Context,
        ): EmailResult {
            val startTime = System.currentTimeMillis()
            val result = tester.testConnection(config, context)
            ensureMinDelay(startTime)
            return result
        }

        suspend fun sendTestEmail(
            config: SmtpConfig,
            fields: TestEmailFields,
            context: Context,
        ): EmailResult {
            val startTime = System.currentTimeMillis()
            val result = tester.sendTestEmail(config, fields, context)
            ensureMinDelay(startTime)
            return result
        }

        internal suspend fun ensureMinDelay(startTime: Long) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed < AppConfig.SMTP_TEST_MIN_DELAY_MS) {
                delay(AppConfig.SMTP_TEST_MIN_DELAY_MS - elapsed)
            }
        }
    }
