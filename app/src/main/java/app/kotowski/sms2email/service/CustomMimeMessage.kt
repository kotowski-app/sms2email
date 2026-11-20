package app.kotowski.sms2email.service

import java.util.UUID
import javax.mail.MessagingException
import javax.mail.Session
import javax.mail.internet.MimeMessage

class CustomMimeMessage(
    session: Session,
    private val senderEmail: String,
) : MimeMessage(session) {
    @Throws(MessagingException::class)
    override fun updateMessageID() {
        val domain = senderEmail.substringAfterLast('@', "localhost")
        val uniqueId = UUID.randomUUID().toString()
        setHeader("Message-ID", "<$uniqueId@$domain>")
    }
}
