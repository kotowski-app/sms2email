package app.kotowski.sms2email.service

import com.sun.mail.smtp.SMTPSendFailedException
import com.sun.mail.smtp.SMTPTransport
import com.sun.mail.util.MailConnectException
import com.sun.mail.util.SocketConnectException
import java.net.ConnectException
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.mail.Address
import javax.mail.AuthenticationFailedException
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.Session
import javax.mail.URLName

// The unit test classpath swaps the SMTP transport via META-INF/javamail.providers
// (loaded before the jar defaults, first registration per protocol wins): EmailService's
// JavaMail sessions get this class instead of com.sun.mail.smtp.SMTPTransport, so tests
// drive connection/send outcomes without sockets, TLS, or certificate fixtures.
class FakeSmtpTransport(
    session: Session,
    urlName: URLName,
) : SMTPTransport(session, urlName) {
    companion object {
        // Per-test scenarios, read on every call.
        @Volatile var failConnect = false

        @Volatile var rejectAuth = false

        @Volatile var rejectDataForMessages: Set<Int> = emptySet()

        @Volatile var dieAfterMessages = Int.MAX_VALUE

        @Volatile var stallConnect = false

        // Stall mode: [connectStarted] counts down once connect() begins blocking; counting
        // down [releaseConnect] unblocks it, after which it throws like a closed socket.
        @Volatile var connectStarted: CountDownLatch? = null

        @Volatile var releaseConnect: CountDownLatch? = null

        val connectedPorts = CopyOnWriteArrayList<Int>()
        val sentMessages = CopyOnWriteArrayList<Message>()
        private val closeCount = AtomicInteger()
        private val attemptCount = AtomicInteger()

        val closedCount: Int
            get() = closeCount.get()

        fun reset() {
            failConnect = false
            rejectAuth = false
            rejectDataForMessages = emptySet()
            dieAfterMessages = Int.MAX_VALUE
            stallConnect = false
            connectStarted = null
            releaseConnect = null
            connectedPorts.clear()
            sentMessages.clear()
            closeCount.set(0)
            attemptCount.set(0)
        }
    }

    // The base Service.connect resolves host/port from the session before calling this,
    // mirroring where the real SMTPTransport performs the actual connection.
    override fun protocolConnect(
        host: String?,
        port: Int,
        user: String?,
        password: String?,
    ): Boolean {
        val h = host ?: "localhost"
        // The real protocolConnect falls back to the session's mail.smtp.port for a -1 port.
        val p = if (port == -1) session?.getProperty("mail.smtp.port")?.toIntOrNull() ?: -1 else port
        if (stallConnect) {
            connectStarted?.countDown()
            releaseConnect?.await(10, TimeUnit.SECONDS)
            throw MailConnectException(
                SocketConnectException("Socket closed", SocketException("Socket closed"), h, p, 0),
            )
        }
        if (failConnect) {
            throw MailConnectException(
                SocketConnectException("Connection refused", ConnectException("Connection refused"), h, p, 5000),
            )
        }
        if (rejectAuth) {
            throw AuthenticationFailedException("Authentication failed")
        }
        connectedPorts.add(p)
        return true
    }

    override fun sendMessage(
        message: Message,
        addresses: Array<Address>,
    ) {
        // Attempts, not accepted sends, drive the scenarios: a rejected message still
        // consumed its slot in the sequence.
        val attemptsBefore = attemptCount.get()
        attemptCount.incrementAndGet()
        if (attemptsBefore >= dieAfterMessages) {
            throw MessagingException("Unable to deliver message", SocketException("Connection reset"))
        }
        if (attemptsBefore + 1 in rejectDataForMessages) {
            // Constructor order is (command, returnCode, message, ...).
            throw SMTPSendFailedException("DATA", 550, "550 5.7.1 Message rejected", null, null, null, null)
        }
        message.saveChanges()
        sentMessages.add(message)
    }

    override fun close() {
        closeCount.incrementAndGet()
    }
}
