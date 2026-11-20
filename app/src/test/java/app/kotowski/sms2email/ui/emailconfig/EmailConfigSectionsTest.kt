package app.kotowski.sms2email.ui.emailconfig

import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.testing.TestEmail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Robolectric: the test-email gate validates the email fields with the platform
// email pattern (EmailUtil), which is unavailable on the plain JVM test classpath.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailConfigSectionsTest {
    // A config that passes the completeness rule (the test-email gate's base case):
    // the committed config is complete and the live port draft is a port in range.
    private val completeConfig =
        EmailConfig(
            settings =
                AppSettings(
                    smtpHost = "h",
                    smtpPort = 587,
                    smtpUsername = "u",
                    destEmail = TestEmail.RECIPIENT,
                    senderEmail = TestEmail.SELF,
                ),
            portDraft = "587",
            smtpPassword = "p",
        )

    @Test
    fun `smtp test enabled with all fields and a valid port draft`() {
        assertTrue(testSmtpEnabled("smtp.example.com", "user", "pass", "587", false))
        assertTrue(testSmtpEnabled("smtp.example.com", "user", "pass", "465", false))
    }

    @Test
    fun `smtp test disabled with an invalid or empty port draft`() {
        assertFalse(testSmtpEnabled("smtp.example.com", "user", "pass", "70000", false))
        assertFalse(testSmtpEnabled("smtp.example.com", "user", "pass", "abc", false))
        assertFalse(testSmtpEnabled("smtp.example.com", "user", "pass", "", false))
    }

    @Test
    fun `smtp test disabled with a blank credential`() {
        assertFalse(testSmtpEnabled("", "user", "pass", "587", false))
        assertFalse(testSmtpEnabled("  ", "user", "pass", "587", false))
        assertFalse(testSmtpEnabled("smtp.example.com", "", "pass", "587", false))
        assertFalse(testSmtpEnabled("smtp.example.com", "user", "   ", "587", false))
    }

    @Test
    fun `smtp test disabled while a test is running`() {
        assertFalse(testSmtpEnabled("smtp.example.com", "user", "pass", "587", true))
    }

    @Test
    fun `email test enabled with a complete config`() {
        assertTrue(testEmailEnabled(completeConfig))
    }

    @Test
    fun `email test disabled with an invalid port draft`() {
        assertFalse(testEmailEnabled(completeConfig.copy(portDraft = "70000")))
        assertFalse(testEmailEnabled(completeConfig.copy(portDraft = "abc")))
    }

    @Test
    fun `email test disabled with an empty port draft`() {
        assertFalse(testEmailEnabled(completeConfig.copy(portDraft = "")))
    }

    @Test
    fun `form complete with a complete config and a port draft in range`() {
        assertTrue(isEmailFormComplete(completeConfig))
        assertTrue(isEmailFormComplete(completeConfig.copy(portDraft = "1")))
        assertTrue(isEmailFormComplete(completeConfig.copy(portDraft = "65535")))
    }

    @Test
    fun `form incomplete with an empty or invalid port draft`() {
        assertFalse(isEmailFormComplete(completeConfig.copy(portDraft = "")))
        assertFalse(isEmailFormComplete(completeConfig.copy(portDraft = "70000")))
        assertFalse(isEmailFormComplete(completeConfig.copy(portDraft = "abc")))
    }

    @Test
    fun `form incomplete when the committed config is incomplete`() {
        assertFalse(isEmailFormComplete(completeConfig.copy(smtpPassword = "")))
        assertFalse(
            isEmailFormComplete(
                completeConfig.copy(settings = completeConfig.settings.copy(smtpHost = "")),
            ),
        )
    }

    @Test
    fun `email test disabled with an incomplete config`() {
        assertFalse(testEmailEnabled(completeConfig.copy(smtpPassword = "")))
        assertFalse(testEmailEnabled(completeConfig.copy(smtpPassword = "  ")))
        assertFalse(
            testEmailEnabled(
                completeConfig.copy(settings = completeConfig.settings.copy(smtpHost = "")),
            ),
        )
        assertFalse(
            testEmailEnabled(
                completeConfig.copy(settings = completeConfig.settings.copy(smtpPort = 0)),
            ),
        )
        assertFalse(
            testEmailEnabled(
                completeConfig.copy(settings = completeConfig.settings.copy(smtpPort = 65536)),
            ),
        )
        assertFalse(
            testEmailEnabled(
                completeConfig.copy(settings = completeConfig.settings.copy(smtpUsername = "")),
            ),
        )
    }

    @Test
    fun `email test disabled with an invalid email`() {
        assertFalse(
            testEmailEnabled(
                completeConfig.copy(settings = completeConfig.settings.copy(destEmail = "abc")),
            ),
        )
        assertFalse(
            testEmailEnabled(
                completeConfig.copy(settings = completeConfig.settings.copy(senderEmail = "abc")),
            ),
        )
        assertFalse(
            testEmailEnabled(
                completeConfig.copy(settings = completeConfig.settings.copy(destEmail = "")),
            ),
        )
        assertFalse(
            testEmailEnabled(
                completeConfig.copy(settings = completeConfig.settings.copy(senderEmail = "")),
            ),
        )
    }

    @Test
    fun `email test disabled while a test is sending`() {
        assertFalse(
            testEmailEnabled(
                completeConfig.copy(test = TestRunState(isSendingTestEmail = true)),
            ),
        )
    }
}
