package app.kotowski.sms2email.service

import org.junit.Test
import java.util.Properties
import javax.mail.Session
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CustomMimeMessageTest {
    private fun CustomMimeMessage.callUpdateMessageID() {
        val method = javaClass.superclass!!.getDeclaredMethod("updateMessageID")
        method.isAccessible = true
        method.invoke(this)
    }

    @Test
    fun `updateMessageID generates valid Message-ID with sender domain`() {
        val session = Session.getDefaultInstance(Properties())
        val msg = CustomMimeMessage(session, "user@example.com")
        msg.callUpdateMessageID()

        val messageId = msg.getHeader("Message-ID")
        assertNotNull(messageId)
        assertTrue(messageId.isNotEmpty())
        val header = messageId[0]
        assertTrue(header.startsWith("<"))
        assertTrue(header.endsWith("@example.com>"))
    }

    @Test
    fun `updateMessageID falls back to localhost when no @ in email`() {
        val session = Session.getDefaultInstance(Properties())
        val msg = CustomMimeMessage(session, "invalidemail")
        msg.callUpdateMessageID()

        val messageId = msg.getHeader("Message-ID")
        assertNotNull(messageId)
        val header = messageId[0]
        assertTrue(header.endsWith("@localhost>"))
    }

    @Test
    fun `updateMessageID uses last @ for domain extraction`() {
        val session = Session.getDefaultInstance(Properties())
        val msg = CustomMimeMessage(session, "user@sub@example.com")
        msg.callUpdateMessageID()

        val messageId = msg.getHeader("Message-ID")
        assertNotNull(messageId)
        val header = messageId[0]
        assertTrue(header.endsWith("@example.com>"))
    }

    @Test
    fun `each updateMessageID call produces unique ID`() {
        val session = Session.getDefaultInstance(Properties())
        val msg1 = CustomMimeMessage(session, "user@example.com").apply { callUpdateMessageID() }
        val msg2 = CustomMimeMessage(session, "user@example.com").apply { callUpdateMessageID() }

        val id1 = msg1.getHeader("Message-ID")[0]
        val id2 = msg2.getHeader("Message-ID")[0]
        assertTrue(id1 != id2, "Message-IDs should be unique")
    }
}
