package app.kotowski.sms2email.service

import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.data.prefs.SmtpProtocol
import java.util.Properties

data class SmtpConfig(
    val host: String,
    val port: Int,
    val protocol: SmtpProtocol,
    val username: String,
    val password: String,
)

// A stored port outside 1..65535 (cleared field, unknown value, import) can never
// connect; throwing lets every send path fail fast with an accurate message
// instead of a misleading network error.
class InvalidSmtpPortException(
    val port: Int,
) : IllegalArgumentException("invalid SMTP port: $port")

fun buildSmtpProperties(config: SmtpConfig): Properties =
    Properties().apply {
        put("mail.transport.protocol", "smtp")
        put("mail.smtp.auth", "true")
        put("mail.smtp.host", config.host)
        put("mail.smtp.port", config.port.toString())
        put("mail.smtp.connectiontimeout", AppConfig.SMTP_CONNECT_TIMEOUT_MS.toString())
        put("mail.smtp.timeout", AppConfig.SMTP_READ_TIMEOUT_MS.toString())
        put("mail.smtp.writetimeout", AppConfig.SMTP_WRITE_TIMEOUT_MS.toString())
        put("mail.smtp.ssl.checkserveridentity", "true")

        when (config.protocol) {
            SmtpProtocol.IMPLICIT_TLS -> {
                put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                put("mail.smtp.socketFactory.port", config.port.toString())
                put("mail.smtp.ssl.enable", "true")
            }

            SmtpProtocol.START_TLS -> {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            }
        }
    }

// The sender and receiver fields of the test email: the connection test runs
// without them, so they travel as a value separate from the SMTP credentials.
data class TestEmailFields(
    val senderEmail: String,
    val senderName: String,
    val destEmail: String,
    val destName: String,
)

sealed class EmailResult {
    object Success : EmailResult()

    data class Failure(
        val message: String,
        // Permanent failures (5xx rejections, bad SMTP credentials) must not be retried;
        // network/transport failures and no-server-verdict failures are transient.
        val permanent: Boolean = false,
    ) : EmailResult()
}

data class BatchEmailItem(
    val smsId: Long,
    val subject: String,
    val body: String,
    // True when the body is the machine JSON format (the send path adds the format's
    // header); per item, because each item's parts come from their own settings snapshot.
    val json: Boolean = false,
)
