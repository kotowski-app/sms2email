package app.kotowski.sms2email.service

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.LogLevel
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.data.prefs.SmtpProtocol
import app.kotowski.sms2email.util.LogBuffer
import app.kotowski.sms2email.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.mail.Message
import javax.mail.internet.InternetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmailServiceTest {
    @Mock
    private lateinit var prefs: EncryptedPrefs

    @Mock
    private lateinit var context: Context

    private lateinit var emailService: EmailService

    private var closeable: AutoCloseable? = null

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        FakeSmtpTransport.reset()
        emailService = EmailService(prefs, context)
    }

    @After
    fun tearDown() {
        closeable?.close()
    }

    @Test
    fun `test SmtpConfig data class`() {
        val config =
            SmtpConfig(
                host = "smtp.example.com",
                port = 587,
                protocol = SmtpProtocol.START_TLS,
                username = "user@example.com",
                password = "password",
            )

        assertEquals("smtp.example.com", config.host)
        assertEquals(587, config.port)
        assertEquals(SmtpProtocol.START_TLS, config.protocol)
        assertEquals("user@example.com", config.username)
        assertEquals("password", config.password)
    }

    @Test
    fun `test EmailResult Failure carries the message`() {
        val result = EmailResult.Failure("Test error")
        assertEquals("Test error", result.message)
    }

    @Test
    fun `SMTP_SEND_TIMEOUT_MS is 180 seconds`() {
        assertEquals(180000, AppConfig.SMTP_SEND_TIMEOUT_MS)
    }

    @Test
    fun `SMTP_SEND_TIMEOUT_MS stays below the SENDING stale sweep threshold`() {
        // A send that outlives the stale threshold can be flipped RETRYING mid-flight and
        // re-sent after it completes (duplicate); keep the hard send bound below it.
        assertTrue(AppConfig.SMTP_SEND_TIMEOUT_MS < AppConfig.RETRY_SENDING_STALE_MS)
    }

    @Test
    fun `SMTP_SEND_TIMEOUT_MS is positive`() {
        assertTrue(AppConfig.SMTP_SEND_TIMEOUT_MS > 0)
    }

    @Test
    fun `SMTP_TEST_CONNECTION_TIMEOUT_MS is 30 seconds`() {
        assertEquals(30000, AppConfig.SMTP_TEST_CONNECTION_TIMEOUT_MS)
    }

    @Test
    fun `SMTP_TEST_CONNECTION_TIMEOUT_MS is positive`() {
        assertTrue(AppConfig.SMTP_TEST_CONNECTION_TIMEOUT_MS > 0)
    }

    // The private internetAddress is the single boundary where stored sender/dest emails
    // become InternetAddress objects, so the control-character stripping is tested through it.
    private fun buildInternetAddress(
        address: String,
        personal: String?,
    ): InternetAddress {
        val method = EmailService::class.java.getDeclaredMethod("internetAddress", String::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(emailService, address, personal) as InternetAddress
    }

    @Test
    fun `internetAddress parses a plain address without a personal name`() {
        val addr = buildInternetAddress("user@example.com", null)
        assertEquals("user@example.com", addr.address)
        assertNull(addr.personal)
    }

    @Test
    fun `internetAddress strips a trailing CRLF from the address when a personal name is set`() {
        val addr = buildInternetAddress("user@example.com\r\n", "Sender")
        assertEquals("user@example.com", addr.address)
        assertEquals("Sender <user@example.com>", addr.toString())
    }

    @Test
    fun `internetAddress never emits a raw CRLF in the header for a CRLF-bearing address`() {
        val addresses =
            listOf(
                "user@example.com\n",
                "user@example.com\r\n",
                "user\n@example.com",
                "evil@example.com\nBcc: victim@example.com",
            )
        for (address in addresses) {
            for (personal in listOf("Sender", null)) {
                val outcome = runCatching { buildInternetAddress(address, personal) }
                if (outcome.isSuccess) {
                    val header = outcome.getOrThrow().toString()
                    assertTrue(!header.contains("\r") && !header.contains("\n"), "header=$header")
                } else {
                    assertTrue(
                        outcome.exceptionOrNull() is InvocationTargetException,
                        "expected AddressException, got ${outcome.exceptionOrNull()}",
                    )
                }
            }
        }
    }

    @Test
    fun `internetAddress without a personal name rejects a CRLF-injected address`() {
        val thrown =
            assertFailsWith<InvocationTargetException> {
                buildInternetAddress("evil@example.com\nBcc: victim@example.com", null)
            }
        assertTrue(thrown.cause is javax.mail.internet.AddressException, "cause=${thrown.cause}")
    }

    @Test
    fun `SMTP_CONNECT_TIMEOUT_MS is 5 seconds`() {
        assertEquals(5000, AppConfig.SMTP_CONNECT_TIMEOUT_MS)
    }

    @Test
    fun `SMTP_READ_TIMEOUT_MS is 15 seconds`() {
        assertEquals(15000, AppConfig.SMTP_READ_TIMEOUT_MS)
    }

    @Test
    fun `SMTP_WRITE_TIMEOUT_MS is 25 seconds`() {
        assertEquals(25000, AppConfig.SMTP_WRITE_TIMEOUT_MS)
    }

    @Test
    fun `SMTP timeouts are all positive`() {
        assertTrue(AppConfig.SMTP_CONNECT_TIMEOUT_MS > 0)
        assertTrue(AppConfig.SMTP_READ_TIMEOUT_MS > 0)
        assertTrue(AppConfig.SMTP_WRITE_TIMEOUT_MS > 0)
    }

    @Test
    fun `SMTP connect timeout is less than read and write`() {
        assertTrue(AppConfig.SMTP_CONNECT_TIMEOUT_MS < AppConfig.SMTP_READ_TIMEOUT_MS)
        assertTrue(AppConfig.SMTP_READ_TIMEOUT_MS < AppConfig.SMTP_WRITE_TIMEOUT_MS)
    }

    @Test
    fun `buildSmtpProperties sets checkserveridentity for IMPLICIT_TLS`() {
        val config =
            SmtpConfig(
                host = "smtp.example.com",
                port = 465,
                protocol = SmtpProtocol.IMPLICIT_TLS,
                username = "user@example.com",
                password = "password",
            )
        val props = buildSmtpProperties(config)
        assertEquals("true", props.getProperty("mail.smtp.ssl.checkserveridentity"))
    }

    @Test
    fun `buildSmtpProperties sets checkserveridentity for START_TLS`() {
        val config =
            SmtpConfig(
                host = "smtp.example.com",
                port = 587,
                protocol = SmtpProtocol.START_TLS,
                username = "user@example.com",
                password = "password",
            )
        val props = buildSmtpProperties(config)
        assertEquals("true", props.getProperty("mail.smtp.ssl.checkserveridentity"))
    }

    @Test
    fun `buildSmtpProperties sets ssl enable for IMPLICIT_TLS mode`() {
        val config =
            SmtpConfig(
                host = "smtp.example.com",
                port = 465,
                protocol = SmtpProtocol.IMPLICIT_TLS,
                username = "user@example.com",
                password = "password",
            )
        val props = buildSmtpProperties(config)
        assertEquals("true", props.getProperty("mail.smtp.ssl.enable"))
    }

    @Test
    fun `buildSmtpProperties sets starttls required for START_TLS mode`() {
        val config =
            SmtpConfig(
                host = "smtp.example.com",
                port = 587,
                protocol = SmtpProtocol.START_TLS,
                username = "user@example.com",
                password = "password",
            )
        val props = buildSmtpProperties(config)
        assertEquals("true", props.getProperty("mail.smtp.starttls.required"))
        assertEquals("true", props.getProperty("mail.smtp.starttls.enable"))
    }

    @Test
    fun `sendEmailBatch fires the callback per item and returns all results`() =
        runTest {
            stubPrefs(25)
            val items =
                listOf(
                    BatchEmailItem(1L, "subject 1", "body 1"),
                    BatchEmailItem(2L, "subject 2", "body 2"),
                )
            val callbackResults = mutableListOf<Pair<Long, EmailResult>>()

            val results =
                runBlocking {
                    emailService.sendEmailBatch(
                        items,
                        onItemResult = { smsId, result -> callbackResults.add(smsId to result) },
                    )
                }

            assertEquals(mapOf(1L to EmailResult.Success, 2L to EmailResult.Success), results)
            val expectedCallbacks: List<Pair<Long, EmailResult>> =
                listOf(1L to EmailResult.Success, 2L to EmailResult.Success)
            assertEquals(expectedCallbacks, callbackResults)
            assertEquals(2, FakeSmtpTransport.sentMessages.size)
        }

    @Test
    fun `sendEmailBatch sends nothing and interrupts every item when shouldContinue is false from the start`() =
        runTest {
            stubPrefs(25)
            whenever(context.getString(R.string.sms_status_send_interrupted)).thenReturn("send interrupted")
            val items =
                listOf(
                    BatchEmailItem(1L, "subject 1", "body 1"),
                    BatchEmailItem(2L, "subject 2", "body 2"),
                    BatchEmailItem(3L, "subject 3", "body 3"),
                )
            val callbackResults = mutableListOf<Pair<Long, EmailResult>>()

            val results =
                runBlocking {
                    emailService.sendEmailBatch(
                        items,
                        onItemResult = { smsId, result -> callbackResults.add(smsId to result) },
                        shouldContinue = { false },
                    )
                }

            assertTrue(FakeSmtpTransport.sentMessages.isEmpty())
            // The batch transport must be closed even when nothing was sent.
            assertTrue(FakeSmtpTransport.closedCount >= 1)
            assertEquals(3, results.size)
            for (id in listOf(1L, 2L, 3L)) {
                assertTrue(results[id] is EmailResult.Failure, "item $id must be a failure")
                assertEquals("send interrupted", (results[id] as EmailResult.Failure).message)
                assertFalse((results[id] as EmailResult.Failure).permanent)
            }
            assertEquals(listOf(1L, 2L, 3L), callbackResults.map { it.first })
            assertTrue(callbackResults.all { it.second is EmailResult.Failure })
        }

    @Test
    fun `sendEmailBatch stops at the next item boundary when shouldContinue flips false mid-batch`() =
        runTest {
            stubPrefs(25)
            whenever(context.getString(R.string.sms_status_send_interrupted)).thenReturn("send interrupted")
            val items =
                listOf(
                    BatchEmailItem(1L, "subject 1", "body 1"),
                    BatchEmailItem(2L, "subject 2", "body 2"),
                    BatchEmailItem(3L, "subject 3", "body 3"),
                )
            val callbackResults = mutableListOf<Pair<Long, EmailResult>>()
            val gate = AtomicBoolean(true)

            // flip the gate off as soon as the first item is finalized: the next
            // iteration's check must stop the batch before item 2 is sent
            val results =
                runBlocking {
                    emailService.sendEmailBatch(
                        items,
                        onItemResult = { smsId, result ->
                            callbackResults.add(smsId to result)
                            if (smsId == 1L) gate.set(false)
                        },
                        shouldContinue = { gate.get() },
                    )
                }

            assertEquals(1, FakeSmtpTransport.sentMessages.size)
            assertEquals(3, results.size)
            assertTrue(results[1L] is EmailResult.Success)
            assertTrue(results[2L] is EmailResult.Failure)
            assertEquals("send interrupted", (results[2L] as EmailResult.Failure).message)
            assertTrue(results[3L] is EmailResult.Failure)
            assertEquals("send interrupted", (results[3L] as EmailResult.Failure).message)
            assertEquals(listOf(1L, 2L, 3L), callbackResults.map { it.first })
            assertTrue(callbackResults[0].second is EmailResult.Success)
            assertTrue(callbackResults[1].second is EmailResult.Failure)
            assertTrue(callbackResults[2].second is EmailResult.Failure)
        }

    @Test
    fun `sendEmailBatch sends every item when shouldContinue is always true`() =
        runTest {
            stubPrefs(25)
            val items =
                listOf(
                    BatchEmailItem(1L, "subject 1", "body 1"),
                    BatchEmailItem(2L, "subject 2", "body 2"),
                )
            val callbackResults = mutableListOf<Pair<Long, EmailResult>>()

            val results =
                runBlocking {
                    emailService.sendEmailBatch(
                        items,
                        onItemResult = { smsId, result -> callbackResults.add(smsId to result) },
                        shouldContinue = { true },
                    )
                }

            assertEquals(2, FakeSmtpTransport.sentMessages.size)
            assertEquals(mapOf(1L to EmailResult.Success, 2L to EmailResult.Success), results)
            val expectedCallbacks: List<Pair<Long, EmailResult>> =
                listOf(1L to EmailResult.Success, 2L to EmailResult.Success)
            assertEquals(expectedCallbacks, callbackResults)
        }

    @Test
    fun `sendEmailBatch keeps the connect-time Message-ID domain when the sender email changes mid-batch`() =
        runTest {
            // connectBatch reads the prefs twice (smtpConfig + snapshot); a later read —
            // the one a per-item load would take — returns a different sender.
            whenever(prefs.load()).thenReturn(
                AppSettings(
                    smtpHost = "localhost",
                    smtpPort = 25,
                    smtpProtocol = SmtpProtocol.IMPLICIT_TLS,
                    smtpUsername = "user@example.com",
                    senderEmail = "old@example.com",
                    destEmail = "dest@example.com",
                ),
                AppSettings(
                    smtpHost = "localhost",
                    smtpPort = 25,
                    smtpProtocol = SmtpProtocol.IMPLICIT_TLS,
                    smtpUsername = "user@example.com",
                    senderEmail = "old@example.com",
                    destEmail = "dest@example.com",
                ),
                AppSettings(
                    smtpHost = "localhost",
                    smtpPort = 25,
                    smtpProtocol = SmtpProtocol.IMPLICIT_TLS,
                    smtpUsername = "user@example.com",
                    senderEmail = "new@example.org",
                    destEmail = "dest@example.com",
                ),
            )
            whenever(prefs.smtpPassword).thenReturn("password")
            val items =
                listOf(
                    BatchEmailItem(1L, "subject 1", "body 1"),
                    BatchEmailItem(2L, "subject 2", "body 2"),
                )

            val results =
                runBlocking {
                    emailService.sendEmailBatch(items, onItemResult = { _, _ -> })
                }

            assertTrue(results[1L] is EmailResult.Success)
            assertTrue(results[2L] is EmailResult.Success)
            assertEquals(2, FakeSmtpTransport.sentMessages.size)
            for (message in FakeSmtpTransport.sentMessages) {
                val messageId = message.getHeader("Message-ID")?.firstOrNull() ?: ""
                assertTrue(
                    messageId.endsWith("@example.com>"),
                    "Message-ID must keep the connect-time domain, got: $messageId",
                )
            }
        }

    @Test
    fun `sendEmailBatch fails the dead item with the mapped error and remaining items as interrupted`() =
        runTest {
            stubPrefs(25)
            FakeSmtpTransport.dieAfterMessages = 1
            whenever(context.getString(R.string.smtp_socket_error)).thenReturn("socket error")
            whenever(context.getString(R.string.smtp_ssl_port_error)).thenReturn("ssl port error")
            whenever(context.getString(R.string.smtp_timeout)).thenReturn("smtp timeout")
            whenever(context.getString(R.string.sms_status_send_interrupted)).thenReturn("Send was interrupted, retrying")
            val items =
                listOf(
                    BatchEmailItem(1L, "subject 1", "body 1"),
                    BatchEmailItem(2L, "subject 2", "body 2"),
                    BatchEmailItem(3L, "subject 3", "body 3"),
                )
            val callbackResults = mutableListOf<Pair<Long, EmailResult>>()
            val mappedMessages = setOf("socket error", "ssl port error", "smtp timeout")

            val results =
                runBlocking {
                    emailService.sendEmailBatch(
                        items,
                        onItemResult = { smsId, result -> callbackResults.add(smsId to result) },
                    )
                }

            assertEquals(1, FakeSmtpTransport.sentMessages.size)
            assertEquals(3, results.size)
            assertTrue(results[1L] is EmailResult.Success)
            assertTrue(results[2L] is EmailResult.Failure)
            assertTrue((results[2L] as EmailResult.Failure).message in mappedMessages)
            assertFalse((results[2L] as EmailResult.Failure).permanent)
            assertTrue(results[3L] is EmailResult.Failure)
            assertEquals("Send was interrupted, retrying", (results[3L] as EmailResult.Failure).message)
            assertFalse((results[3L] as EmailResult.Failure).permanent)
            assertEquals(3, callbackResults.size)
            assertEquals(1L, callbackResults[0].first)
            assertTrue(callbackResults[0].second is EmailResult.Success)
            assertEquals(2L, callbackResults[1].first)
            assertTrue(callbackResults[1].second is EmailResult.Failure)
            assertTrue((callbackResults[1].second as EmailResult.Failure).message in mappedMessages)
            assertEquals(3L, callbackResults[2].first)
            assertTrue(callbackResults[2].second is EmailResult.Failure)
            assertEquals("Send was interrupted, retrying", (callbackResults[2].second as EmailResult.Failure).message)
        }

    @Test
    fun `sendEmailBatch rethrows cancellation from the item callback instead of mapping it to a failure`() =
        runTest {
            stubPrefs(25)
            val items =
                listOf(
                    BatchEmailItem(1L, "subject 1", "body 1"),
                    BatchEmailItem(2L, "subject 2", "body 2"),
                    BatchEmailItem(3L, "subject 3", "body 3"),
                )
            var callbackInvocations = 0

            assertFailsWith<CancellationException> {
                runBlocking {
                    emailService.sendEmailBatch(
                        items,
                        onItemResult = { _, _ ->
                            callbackInvocations++
                            if (callbackInvocations == 2) {
                                throw CancellationException("worker cancelled")
                            }
                        },
                    )
                }
            }

            assertEquals(2, callbackInvocations)
        }

    @Test
    fun `sendEmailBatch sends and finalizes every item when the job is cancelled mid-batch`() {
        stubPrefs(25)
        val items =
            listOf(
                BatchEmailItem(1L, "subject 1", "body 1"),
                BatchEmailItem(2L, "subject 2", "body 2"),
                BatchEmailItem(3L, "subject 3", "body 3"),
            )
        val finalized = mutableListOf<Pair<Long, EmailResult>>()
        val job = Job()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            withContext(job) {
                emailService.sendEmailBatch(
                    items,
                    onItemResult = { smsId, result ->
                        // mirrors the production finalizer: a blocking commit that must survive
                        // cancellation of the sending coroutine
                        runBlocking {
                            delay(1)
                            finalized.add(smsId to result)
                        }
                        if (finalized.size == 1) {
                            job.cancel()
                        }
                    },
                )
            }
        }
        runBlocking {
            withTimeout(10_000) { job.join() }
        }

        assertTrue(job.isCancelled)
        val expected: List<Pair<Long, EmailResult>> =
            listOf(
                1L to EmailResult.Success,
                2L to EmailResult.Success,
                3L to EmailResult.Success,
            )
        assertEquals(expected, finalized)
    }

    @Test
    fun `sendEmail rethrows cancellation instead of mapping it to a failure`() {
        stubPrefs(25)
        // Non-null for every lookup: the teardown path may reach the error mapper, and an
        // unstubbed resource string would NPE and leak into later tests.
        whenever(context.getString(any<Int>())).thenAnswer { invocation -> "str_${invocation.arguments[0]}" }
        FakeSmtpTransport.stallConnect = true
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        FakeSmtpTransport.connectStarted = started
        FakeSmtpTransport.releaseConnect = release

        val result = AtomicReference<EmailResult>()
        val thrown = AtomicReference<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job =
            scope.launch {
                try {
                    result.set(
                        emailService.sendEmail(
                            to = "dest@example.com",
                            toName = null,
                            from = "sender@example.com",
                            fromName = null,
                            subject = "subject",
                            body = "body",
                        ),
                    )
                } catch (t: Throwable) {
                    // Record what escaped sendEmail; do not rethrow, so it is not reported
                    // as an uncaught exception (which would poison later tests).
                    thrown.set(t)
                }
            }

        assertTrue(started.await(10, TimeUnit.SECONDS))
        job.cancel()
        release.countDown()
        runBlocking {
            withTimeout(10_000) { job.join() }
        }

        assertTrue(job.isCancelled)
        assertNull(result.get())
        assertTrue(
            thrown.get() is CancellationException,
            "expected CancellationException to escape sendEmail, got ${thrown.get()}",
        )
    }

    @Test
    fun `sendEmail marks a 5xx data rejection as a permanent failure`() =
        runTest {
            stubPrefs(25)
            FakeSmtpTransport.rejectDataForMessages = setOf(1)
            whenever(context.getString(R.string.smtp_enh_5_7_1)).thenReturn("smtp 5.7.1 error")

            val result =
                runBlocking {
                    emailService.sendEmail(
                        to = "dest@example.com",
                        toName = null,
                        from = "sender@example.com",
                        fromName = null,
                        subject = "subject",
                        body = "body",
                    )
                }

            assertTrue(result is EmailResult.Failure)
            assertEquals("smtp 5.7.1 error", result.message)
            assertTrue(result.permanent)
        }

    // Pinned: a connect failure's message carries the SMTP host; the log line must keep
    // only the diagnostic (class name + code), never the host.
    @Test
    fun `sendEmail failure logs the diagnostic without the host`() =
        runTest {
            Logger.setLevel(LogLevel.DEBUG)
            whenever(context.getString(any<Int>())).thenAnswer { invocation -> "str_${invocation.arguments[0]}" }
            FakeSmtpTransport.failConnect = true
            stubPrefs(25)

            val result =
                runBlocking {
                    emailService.sendEmail(
                        to = "dest@example.com",
                        toName = null,
                        from = "sender@example.com",
                        fromName = null,
                        subject = "subject",
                        body = "body",
                    )
                }

            assertTrue(result is EmailResult.Failure)
            val entry = LogBuffer.entries.last()
            assertEquals("EmailService", entry.tag)
            assertEquals("sendEmail failed: MailConnectException", entry.message)
        }

    @Test
    fun `testConnection failure logs the diagnostic without the host`() =
        runTest {
            Logger.setLevel(LogLevel.DEBUG)
            whenever(context.getString(any<Int>())).thenAnswer { invocation -> "str_${invocation.arguments[0]}" }
            FakeSmtpTransport.failConnect = true
            whenever(prefs.load()).thenReturn(
                AppSettings(
                    smtpHost = "localhost",
                    smtpPort = 25,
                    smtpProtocol = SmtpProtocol.IMPLICIT_TLS,
                    smtpUsername = "user@example.com",
                    destEmail = "dest@example.com",
                ),
            )

            val result =
                runBlocking {
                    emailService.testConnection(
                        SmtpConfig(
                            host = "localhost",
                            port = 25,
                            protocol = SmtpProtocol.IMPLICIT_TLS,
                            username = "user@example.com",
                            password = "password",
                        ),
                    )
                }

            assertTrue(result is EmailResult.Failure)
            val entry = LogBuffer.entries.last()
            assertEquals("EmailService", entry.tag)
            assertEquals("testConnection failed: MailConnectException", entry.message)
        }

    @Test
    fun `sendEmailBatch setup failure logs the diagnostic without the host`() =
        runTest {
            Logger.setLevel(LogLevel.DEBUG)
            whenever(context.getString(any<Int>())).thenAnswer { invocation -> "str_${invocation.arguments[0]}" }
            FakeSmtpTransport.failConnect = true
            stubPrefs(25)

            val results =
                runBlocking {
                    emailService.sendEmailBatch(
                        listOf(BatchEmailItem(1L, "subject 1", "body 1")),
                        onItemResult = { _, _ -> },
                    )
                }

            assertTrue(results[1L] is EmailResult.Failure)
            val entry = LogBuffer.entries.last()
            assertEquals("EmailService", entry.tag)
            assertEquals("sendEmailBatch setup failed, failing all items: MailConnectException", entry.message)
        }

    @Test
    fun `sendEmailBatch transport dead logs the diagnostic without the host`() =
        runTest {
            Logger.setLevel(LogLevel.DEBUG)
            stubPrefs(25)
            FakeSmtpTransport.dieAfterMessages = 1
            whenever(context.getString(R.string.smtp_socket_error)).thenReturn("socket error")
            whenever(context.getString(R.string.sms_status_send_interrupted)).thenReturn("Send was interrupted, retrying")
            val items =
                listOf(
                    BatchEmailItem(1L, "subject 1", "body 1"),
                    BatchEmailItem(2L, "subject 2", "body 2"),
                )

            val results =
                runBlocking {
                    emailService.sendEmailBatch(
                        items,
                        onItemResult = { _, _ -> },
                    )
                }

            assertTrue(results[1L] is EmailResult.Success)
            assertTrue(results[2L] is EmailResult.Failure)
            val entry = LogBuffer.entries.last()
            assertEquals("EmailService", entry.tag)
            assertTrue(entry.message.startsWith("sendEmailBatch transport dead, stopping batch: smsId=2, "))
            assertFalse(entry.message.contains("localhost"))
        }

    @Test
    fun `sendEmail adds the json marker header when the format is json`() =
        runTest {
            stubPrefs(25)

            val result =
                runBlocking {
                    emailService.sendEmail(
                        to = "dest@example.com",
                        toName = null,
                        from = "sender@example.com",
                        fromName = null,
                        subject = "SMS to Email",
                        body = "{\n  \"version\": 1\n}",
                        json = true,
                    )
                }

            assertEquals(EmailResult.Success, result)
            val message = FakeSmtpTransport.sentMessages.single()
            assertEquals(
                AppConfig.JSON_EMAIL_HEADER_VALUE,
                message.getHeader(AppConfig.JSON_EMAIL_HEADER_NAME)?.firstOrNull(),
            )
        }

    @Test
    fun `sendEmail omits the json marker header for a plain body`() =
        runTest {
            stubPrefs(25)

            val result =
                runBlocking {
                    emailService.sendEmail(
                        to = "dest@example.com",
                        toName = null,
                        from = "sender@example.com",
                        fromName = null,
                        subject = "subject",
                        body = "body",
                    )
                }

            assertEquals(EmailResult.Success, result)
            val message = FakeSmtpTransport.sentMessages.single()
            assertNull(message.getHeader(AppConfig.JSON_EMAIL_HEADER_NAME))
        }

    @Test
    fun `sendTestEmail sends the fixed subject and the confirmation line only, without a time`() =
        runTest {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            whenever(context.getSharedPreferences(any(), any())).thenReturn(mock<SharedPreferences>())
            val confirmation = "If you received this, your SMTP settings are configured correctly."
            whenever(context.getString(R.string.app_name)).thenReturn("SMS to Email")
            whenever(context.getString(R.string.email_test_body_confirmation)).thenReturn(confirmation)
            whenever(context.getString(R.string.test_email_subject, "SMS to Email")).thenReturn("Test Email from SMS to Email App")

            val result =
                runBlocking {
                    emailService.sendTestEmail(
                        SmtpConfig("localhost", 25, SmtpProtocol.IMPLICIT_TLS, "user@example.com", "password"),
                        TestEmailFields("sender@example.com", "Sender", "dest@example.com", "Dest"),
                    )
                }

            assertEquals(EmailResult.Success, result)
            val message = FakeSmtpTransport.sentMessages.single()
            assertEquals("Test Email from SMS to Email App", message.subject)
            assertEquals(confirmation, message.getContent() as String)
        }

    @Test
    fun `sendEmailBatch marks only the json items with the marker header`() =
        runTest {
            stubPrefs(25)
            val items =
                listOf(
                    BatchEmailItem(1L, "subject 1", "body 1", json = true),
                    BatchEmailItem(2L, "subject 2", "body 2"),
                )

            val results =
                runBlocking {
                    emailService.sendEmailBatch(items, onItemResult = { _, _ -> })
                }

            assertEquals(mapOf(1L to EmailResult.Success, 2L to EmailResult.Success), results)
            val first = FakeSmtpTransport.sentMessages[0]
            val second = FakeSmtpTransport.sentMessages[1]
            assertEquals(
                AppConfig.JSON_EMAIL_HEADER_VALUE,
                first.getHeader(AppConfig.JSON_EMAIL_HEADER_NAME)?.firstOrNull(),
            )
            assertNull(second.getHeader(AppConfig.JSON_EMAIL_HEADER_NAME)?.firstOrNull())
        }

    @Test
    fun `sendEmailBatch records a mapped failure and continues on a non-IO exception`() =
        runTest {
            stubPrefs(25)
            FakeSmtpTransport.rejectDataForMessages = setOf(1)
            whenever(context.getString(R.string.smtp_enh_5_7_1)).thenReturn("smtp 5.7.1 error")
            val items =
                listOf(
                    BatchEmailItem(1L, "subject 1", "body 1"),
                    BatchEmailItem(2L, "subject 2", "body 2"),
                )
            val callbackResults = mutableListOf<Pair<Long, EmailResult>>()

            val results =
                runBlocking {
                    emailService.sendEmailBatch(
                        items,
                        onItemResult = { smsId, result -> callbackResults.add(smsId to result) },
                    )
                }

            assertEquals(2, results.size)
            assertTrue(results[1L] is EmailResult.Failure)
            assertEquals("smtp 5.7.1 error", (results[1L] as EmailResult.Failure).message)
            assertTrue((results[1L] as EmailResult.Failure).permanent)
            assertTrue(results[2L] is EmailResult.Success)
            assertEquals(
                listOf(1L to results[1L], 2L to EmailResult.Success),
                callbackResults,
            )
        }

    @Test
    fun `sendEmailBatch fails all items when the connection is refused`() =
        runTest {
            FakeSmtpTransport.failConnect = true
            stubPrefs(25)
            whenever(context.getString(R.string.smtp_connection_error)).thenReturn("connection error")
            val items =
                listOf(
                    BatchEmailItem(1L, "subject 1", "body 1"),
                    BatchEmailItem(2L, "subject 2", "body 2"),
                )
            val callbackResults = mutableListOf<Pair<Long, EmailResult>>()

            val results =
                runBlocking {
                    emailService.sendEmailBatch(
                        items,
                        onItemResult = { smsId, result -> callbackResults.add(smsId to result) },
                    )
                }

            assertEquals(2, results.size)
            assertTrue(results[1L] is EmailResult.Failure)
            assertEquals("connection error", (results[1L] as EmailResult.Failure).message)
            assertFalse((results[1L] as EmailResult.Failure).permanent)
            assertTrue(results[2L] is EmailResult.Failure)
            assertEquals("connection error", (results[2L] as EmailResult.Failure).message)
            assertFalse((results[2L] as EmailResult.Failure).permanent)
            assertEquals(
                listOf(1L to results[1L], 2L to results[2L]),
                callbackResults,
            )
        }

    @Test
    fun `sendEmailBatch fails all items when the sender email is malformed`() =
        runTest {
            // No connection is attempted: the setup fails before the transport is used.
            stubPrefs(25)
            whenever(prefs.load()).thenReturn(
                AppSettings(
                    smtpHost = "localhost",
                    smtpPort = 25,
                    smtpProtocol = SmtpProtocol.IMPLICIT_TLS,
                    smtpUsername = "user@example.com",
                    destEmail = "dest@example.com",
                ),
            )
            whenever(context.getString(any<Int>())).thenAnswer { invocation -> "str_${invocation.arguments[0]}" }
            val items =
                listOf(
                    BatchEmailItem(1L, "subject 1", "body 1"),
                    BatchEmailItem(2L, "subject 2", "body 2"),
                )
            val callbackResults = mutableListOf<Pair<Long, EmailResult>>()

            val results =
                runBlocking {
                    emailService.sendEmailBatch(
                        items,
                        onItemResult = { smsId, result -> callbackResults.add(smsId to result) },
                    )
                }

            assertEquals(2, results.size)
            assertTrue(results[1L] is EmailResult.Failure)
            assertTrue(results[2L] is EmailResult.Failure)
            assertEquals(
                (results[1L] as EmailResult.Failure).message,
                (results[2L] as EmailResult.Failure).message,
            )
            assertEquals(
                listOf(1L to results[1L], 2L to results[2L]),
                callbackResults,
            )
        }

    @Test
    fun `sendEmailBatch fails all items when authentication is rejected`() =
        runTest {
            stubPrefs(25)
            FakeSmtpTransport.rejectAuth = true
            whenever(context.getString(R.string.smtp_auth_failed)).thenReturn("auth failed")
            val items =
                listOf(
                    BatchEmailItem(1L, "subject 1", "body 1"),
                    BatchEmailItem(2L, "subject 2", "body 2"),
                )
            val callbackResults = mutableListOf<Pair<Long, EmailResult>>()

            val results =
                runBlocking {
                    emailService.sendEmailBatch(
                        items,
                        onItemResult = { smsId, result -> callbackResults.add(smsId to result) },
                    )
                }

            assertEquals(2, results.size)
            assertTrue(results[1L] is EmailResult.Failure)
            assertEquals("auth failed", (results[1L] as EmailResult.Failure).message)
            assertTrue((results[1L] as EmailResult.Failure).permanent)
            assertTrue(results[2L] is EmailResult.Failure)
            assertEquals("auth failed", (results[2L] as EmailResult.Failure).message)
            assertTrue((results[2L] as EmailResult.Failure).permanent)
            assertEquals(
                listOf(1L to results[1L], 2L to results[2L]),
                callbackResults,
            )
        }

    @Test
    fun `sendEmail fails permanently with the invalid port message when the stored port is out of range`() =
        runTest {
            for (port in listOf(0, -1, 65536)) {
                stubPrefs(port)
                whenever(context.getString(R.string.error_invalid_smtp_port)).thenReturn("invalid smtp port")

                val result =
                    runBlocking {
                        emailService.sendEmail(
                            to = "dest@example.com",
                            toName = null,
                            from = "sender@example.com",
                            fromName = null,
                            subject = "subject",
                            body = "body",
                        )
                    }

                assertTrue(result is EmailResult.Failure, "port=$port")
                assertEquals("invalid smtp port", result.message)
                assertTrue(result.permanent, "port=$port")
            }
        }

    @Test
    fun `testConnection fails permanently with the invalid port message when the passed port is out of range`() =
        runTest {
            whenever(context.getString(R.string.error_invalid_smtp_port)).thenReturn("invalid smtp port")

            val result =
                runBlocking {
                    emailService.testConnection(
                        SmtpConfig("localhost", 0, SmtpProtocol.IMPLICIT_TLS, "user@example.com", "password"),
                    )
                }

            assertTrue(result is EmailResult.Failure)
            assertEquals("invalid smtp port", (result as EmailResult.Failure).message)
            assertTrue(result.permanent)
        }

    @Test
    fun `testConnection connects with the passed config and ignores the stored prefs`() =
        runTest {
            // A port-0 pref would fail fast as a permanent invalid-port error if the
            // service read the prefs instead of the passed config.
            stubPrefs(0)
            val config =
                SmtpConfig("localhost", 25, SmtpProtocol.IMPLICIT_TLS, "user@example.com", "password")

            val result =
                runBlocking {
                    emailService.testConnection(config)
                }

            assertTrue(result is EmailResult.Success)
            assertEquals(25, FakeSmtpTransport.connectedPorts.single())
        }

    @Test
    fun `sendEmailBatch fails all items permanently when the stored port is out of range`() =
        runTest {
            stubPrefs(0)
            whenever(context.getString(R.string.error_invalid_smtp_port)).thenReturn("invalid smtp port")
            val items =
                listOf(
                    BatchEmailItem(1L, "subject 1", "body 1"),
                    BatchEmailItem(2L, "subject 2", "body 2"),
                )
            val callbackResults = mutableListOf<Pair<Long, EmailResult>>()

            val results =
                runBlocking {
                    emailService.sendEmailBatch(
                        items,
                        onItemResult = { smsId, result -> callbackResults.add(smsId to result) },
                    )
                }

            assertEquals(2, results.size)
            assertTrue(results[1L] is EmailResult.Failure)
            assertEquals("invalid smtp port", (results[1L] as EmailResult.Failure).message)
            assertTrue((results[1L] as EmailResult.Failure).permanent)
            assertTrue(results[2L] is EmailResult.Failure)
            assertEquals("invalid smtp port", (results[2L] as EmailResult.Failure).message)
            assertTrue((results[2L] as EmailResult.Failure).permanent)
            assertEquals(
                listOf(1L to results[1L], 2L to results[2L]),
                callbackResults,
            )
        }

    @Test
    fun `InvalidSmtpPortException carries the port value`() {
        val e = InvalidSmtpPortException(99999)
        assertEquals(99999, e.port)
        assertEquals("invalid SMTP port: 99999", e.message)
    }

    @Test
    fun `internetAddress with non-blank personal name uses the 3-arg constructor`() {
        val addr = buildInternetAddress("user@example.com", "Sender Name")
        assertEquals("user@example.com", addr.address)
        assertEquals("Sender Name", addr.personal)
    }

    @Test
    fun `sendTestEmail with non-blank sender and dest names passes them through`() =
        runTest {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            whenever(context.getSharedPreferences(any(), any())).thenReturn(mock<SharedPreferences>())
            whenever(context.getString(R.string.app_name)).thenReturn("SMS to Email")
            whenever(context.getString(R.string.email_test_body_confirmation)).thenReturn("Test body")
            whenever(context.getString(R.string.test_email_subject, "SMS to Email")).thenReturn("Test Email from SMS to Email App")

            val result =
                runBlocking {
                    emailService.sendTestEmail(
                        SmtpConfig("localhost", 25, SmtpProtocol.IMPLICIT_TLS, "user@example.com", "password"),
                        TestEmailFields("sender@example.com", "Sender Name", "dest@example.com", "Dest Name"),
                    )
                }

            assertEquals(EmailResult.Success, result)
            val message = FakeSmtpTransport.sentMessages.single()
            assertEquals("Sender Name", (message.getFrom()[0] as InternetAddress).personal)
            assertEquals("Dest Name", (message.getRecipients(Message.RecipientType.TO)[0] as InternetAddress).personal)
        }

    @Test
    fun `sendTestEmail with blank sender and dest names passes null`() =
        runTest {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            whenever(context.getSharedPreferences(any(), any())).thenReturn(mock<SharedPreferences>())
            whenever(context.getString(R.string.app_name)).thenReturn("SMS to Email")
            whenever(context.getString(R.string.email_test_body_confirmation)).thenReturn("Test body")
            whenever(context.getString(R.string.test_email_subject, "SMS to Email")).thenReturn("Test Email from SMS to Email App")

            val result =
                runBlocking {
                    emailService.sendTestEmail(
                        SmtpConfig("localhost", 25, SmtpProtocol.IMPLICIT_TLS, "user@example.com", "password"),
                        TestEmailFields("sender@example.com", "", "dest@example.com", ""),
                    )
                }

            assertEquals(EmailResult.Success, result)
            val message = FakeSmtpTransport.sentMessages.single()
            assertNull((message.getFrom()[0] as InternetAddress).personal)
            assertNull((message.getRecipients(Message.RecipientType.TO)[0] as InternetAddress).personal)
        }

    @Test
    fun `sendEmailBatch setup exception fails all items with the mapped error`() =
        runTest {
            stubPrefs(0)
            whenever(prefs.load()).thenReturn(
                AppSettings(
                    smtpHost = "localhost",
                    smtpPort = 0,
                    smtpProtocol = SmtpProtocol.IMPLICIT_TLS,
                    smtpUsername = "user@example.com",
                    senderEmail = "sender@example.com",
                    destEmail = "dest@example.com",
                ),
            )
            whenever(context.getString(R.string.error_invalid_smtp_port)).thenReturn("invalid smtp port")
            val items =
                listOf(
                    BatchEmailItem(1L, "subject 1", "body 1"),
                    BatchEmailItem(2L, "subject 2", "body 2"),
                )
            val callbackResults = mutableListOf<Pair<Long, EmailResult>>()

            val results =
                runBlocking {
                    emailService.sendEmailBatch(
                        items,
                        onItemResult = { smsId, result -> callbackResults.add(smsId to result) },
                    )
                }

            assertEquals(2, results.size)
            assertTrue(results[1L] is EmailResult.Failure)
            assertEquals("invalid smtp port", (results[1L] as EmailResult.Failure).message)
            assertTrue((results[1L] as EmailResult.Failure).permanent)
            assertTrue(results[2L] is EmailResult.Failure)
            assertEquals(
                listOf(1L to results[1L], 2L to results[2L]),
                callbackResults,
            )
        }

    // testConnection: the Exception catch maps an auth rejection via mappedFailure,
    // exercising the SmtpErrorMapper path for AuthenticationFailedException.
    @Test
    fun `testConnection maps an authentication failure via the Exception catch`() =
        runTest {
            FakeSmtpTransport.rejectAuth = true
            whenever(context.getString(R.string.smtp_auth_failed)).thenReturn("auth failed")
            val config =
                SmtpConfig("localhost", 25, SmtpProtocol.IMPLICIT_TLS, "user@example.com", "password")

            val result =
                runBlocking {
                    emailService.testConnection(config)
                }

            assertTrue(result is EmailResult.Failure)
            assertEquals("auth failed", (result as EmailResult.Failure).message)
            assertTrue(result.permanent)
        }

    // testConnection: when the connect blocks and the job is cancelled, the released
    // connect throws, which maps to a network error via mappedFailure.
    @Test
    fun `testConnection handles a stalled connect by mapping the connection error`() {
        whenever(context.getString(any<Int>())).thenAnswer { invocation -> "str_${invocation.arguments[0]}" }
        FakeSmtpTransport.stallConnect = true
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        FakeSmtpTransport.connectStarted = started
        FakeSmtpTransport.releaseConnect = release

        val result = AtomicReference<EmailResult>()
        val job =
            CoroutineScope(Dispatchers.Default).launch {
                result.set(
                    emailService.testConnection(
                        SmtpConfig("localhost", 25, SmtpProtocol.IMPLICIT_TLS, "user@example.com", "password"),
                    ),
                )
            }

        assertTrue(started.await(10, TimeUnit.SECONDS))
        job.cancel()
        release.countDown()
        runBlocking {
            withTimeout(10_000) { job.join() }
        }

        assertTrue(job.isCompleted)
    }

    // testConnection: the Exception catch maps a connection refusal via
    // mappedFailure, exercising the network-error path through SmtpErrorMapper.
    @Test
    fun `testConnection maps a connection refusal via the Exception catch`() =
        runTest {
            FakeSmtpTransport.failConnect = true
            whenever(context.getString(R.string.smtp_connection_error)).thenReturn("connection refused")

            val result =
                runBlocking {
                    emailService.testConnection(
                        SmtpConfig("localhost", 25, SmtpProtocol.IMPLICIT_TLS, "user@example.com", "password"),
                    )
                }

            assertTrue(result is EmailResult.Failure)
            assertEquals("connection refused", (result as EmailResult.Failure).message)
            assertFalse(result.permanent)
        }

    @Test
    fun `sendEmail rethrows cancellation when cancelled mid-connect`() {
        stubPrefs(25)
        whenever(context.getString(any<Int>())).thenAnswer { invocation -> "str_${invocation.arguments[0]}" }
        FakeSmtpTransport.stallConnect = true
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        FakeSmtpTransport.connectStarted = started
        FakeSmtpTransport.releaseConnect = release

        val result = AtomicReference<EmailResult>()
        val thrown = AtomicReference<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job =
            scope.launch {
                try {
                    result.set(
                        emailService.sendEmail(
                            to = "dest@example.com",
                            toName = null,
                            from = "sender@example.com",
                            fromName = null,
                            subject = "subject",
                            body = "body",
                        ),
                    )
                } catch (t: Throwable) {
                    thrown.set(t)
                }
            }

        assertTrue(started.await(10, TimeUnit.SECONDS))
        job.cancel()
        release.countDown()
        runBlocking {
            withTimeout(10_000) { job.join() }
        }

        assertTrue(job.isCancelled)
        assertNull(result.get())
        assertTrue(
            thrown.get() is CancellationException,
            "expected CancellationException, got ${thrown.get()}",
        )
    }

    // The public sendEmail's InvalidSmtpPortException catch: validatedPort throws from
    // smtpConfig() when the stored port is out of range, and the catch maps it via
    // mappedFailure with permanent = true.
    @Test
    fun `sendEmail public catch for InvalidSmtpPortException maps failure as permanent`() =
        runTest {
            stubPrefs(-1)
            whenever(context.getString(R.string.error_invalid_smtp_port)).thenReturn("invalid smtp port")

            val result =
                runBlocking {
                    emailService.sendEmail(
                        to = "dest@example.com",
                        toName = null,
                        from = "sender@example.com",
                        fromName = null,
                        subject = "subject",
                        body = "body",
                    )
                }

            assertTrue(result is EmailResult.Failure)
            assertEquals("invalid smtp port", result.message)
            assertTrue(result.permanent)
        }

    private fun stubPrefs(port: Int) {
        whenever(prefs.load()).thenReturn(
            AppSettings(
                smtpHost = "localhost",
                smtpPort = port,
                smtpProtocol = SmtpProtocol.IMPLICIT_TLS,
                smtpUsername = "user@example.com",
                senderEmail = "sender@example.com",
                destEmail = "dest@example.com",
            ),
        )
        whenever(prefs.smtpPassword).thenReturn("password")
    }
}
