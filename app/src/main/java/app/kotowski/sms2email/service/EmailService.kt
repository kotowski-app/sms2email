package app.kotowski.sms2email.service

import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.util.EmailUtil
import app.kotowski.sms2email.util.LanguageHelper
import app.kotowski.sms2email.util.Logger
import app.kotowski.sms2email.util.sanitizeHeaderValue
import app.kotowski.sms2email.util.sanitizeSubject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress

@Singleton
class EmailService
    @Inject
    constructor(
        private val prefs: EncryptedPrefs,
        @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    ) {
        // The queue path: the config comes from the stored prefs, the single source of
        // truth for the forwarding worker; the port is validated there, so a bad stored
        // port degrades to a failure result.
        suspend fun sendEmail(
            to: String,
            toName: String?,
            from: String,
            fromName: String?,
            subject: String,
            body: String,
            json: Boolean = false,
        ): EmailResult =
            try {
                sendEmail(
                    smtpConfig(),
                    to,
                    toName,
                    from,
                    fromName,
                    subject,
                    body,
                    json,
                )
            } catch (e: InvalidSmtpPortException) {
                Logger.e("EmailService", "sendEmail aborted: ${e.message}")
                mappedFailure(e)
            }

        private suspend fun sendEmail(
            config: SmtpConfig,
            to: String,
            toName: String?,
            from: String,
            fromName: String?,
            subject: String,
            body: String,
            json: Boolean,
        ): EmailResult =
            withContext(Dispatchers.IO) {
                try {
                    withTimeout(AppConfig.SMTP_SEND_TIMEOUT_MS) {
                        validatedPort(config.port)

                        val session = authenticatedSession(config)

                        val mimeMessage =
                            CustomMimeMessage(session, from).apply {
                                setFrom(internetAddress(from, fromName))
                                setRecipient(Message.RecipientType.TO, internetAddress(to, toName))
                                this.subject = sanitizeSubject(subject)
                                setText(body, "utf-8")
                                if (json) {
                                    addHeader(AppConfig.JSON_EMAIL_HEADER_NAME, AppConfig.JSON_EMAIL_HEADER_VALUE)
                                }
                            }

                        Transport.send(mimeMessage)
                    }
                    // Field-free on purpose: the destination is constant per config and the
                    // subject/body are PII; the line only marks that a send committed.
                    Logger.i("EmailService", "sendEmail success")
                    EmailResult.Success
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    Logger.e("EmailService", "sendEmail timed out after ${AppConfig.SMTP_SEND_TIMEOUT_MS}ms")
                    EmailResult.Failure(appContext.getString(R.string.error_connection_timeout))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e("EmailService", "sendEmail failed: ${SmtpErrorMapper.diagnostic(e)}")
                    mappedFailure(e)
                }
            }

        // The test runs against the passed config, never the stored prefs: a test must
        // not be able to persist (or depend on persisting) anything.
        suspend fun testConnection(config: SmtpConfig): EmailResult =
            withContext(Dispatchers.IO) {
                var transport: Transport? = null

                try {
                    validatedPort(config.port)
                    val props = buildSmtpProperties(config)
                    val session = Session.getInstance(props)
                    withTimeout(AppConfig.SMTP_TEST_CONNECTION_TIMEOUT_MS) {
                        val t = session.getTransport("smtp")
                        transport = t
                        t.connect(config.host, config.port, config.username, config.password)
                    }
                    EmailResult.Success
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    Logger.e("EmailService", "testConnection timed out after ${AppConfig.SMTP_TEST_CONNECTION_TIMEOUT_MS}ms")
                    EmailResult.Failure(appContext.getString(R.string.error_connection_timeout))
                } catch (e: Exception) {
                    Logger.e("EmailService", "testConnection failed: ${SmtpErrorMapper.diagnostic(e)}")
                    mappedFailure(e)
                } finally {
                    try {
                        transport?.close()
                    } catch (e: Exception) {
                        Logger.w("EmailService", "Failed to close transport: ${e.javaClass.simpleName}")
                    }
                }
            }

        // Like testConnection, runs against the passed config and fields.
        suspend fun sendTestEmail(
            config: SmtpConfig,
            fields: TestEmailFields,
        ): EmailResult {
            val ctx = LanguageHelper.applyLocale(appContext)
            return sendEmail(
                config,
                to = fields.destEmail,
                toName = fields.destName.ifBlank { null },
                from = fields.senderEmail,
                fromName = fields.senderName.ifBlank { null },
                subject = ctx.getString(R.string.test_email_subject, ctx.getString(R.string.app_name)),
                body = ctx.getString(R.string.email_test_body_confirmation),
                // A test email is never a machine payload: no marker header.
                json = false,
            )
        }

        // onItemResult must stay non-suspend: it runs on the IO thread right after a 250 OK
        // and must not open a suspension point between the SMTP commit and the per-item DB
        // write, where worker cancellation would strand the row SENDING (sweep re-send ->
        // duplicate). The worker runs its finalization in runBlocking, opaque to cancellation.
        // shouldContinue is the caller's generic stop gate, checked at each item boundary
        // before the next send; null (the default) disables the check.
        suspend fun sendEmailBatch(
            items: List<BatchEmailItem>,
            onItemResult: (smsId: Long, result: EmailResult) -> Unit,
            shouldContinue: (() -> Boolean)? = null,
        ): Map<Long, EmailResult> =
            withContext(Dispatchers.IO) {
                val results = mutableMapOf<Long, EmailResult>()

                // The whole setup is inside the fail-all path: a bad config (e.g. malformed
                // sender/dest email) must fail every item per-item instead of escaping
                // doWork and stranding the claimed batch SENDING.
                val setupResult = connectBatch()
                val setup =
                    setupResult.getOrNull() ?: run {
                        val e = setupResult.exceptionOrNull()!!
                        val failure = mappedFailure(e)
                        for (item in items) {
                            results[item.smsId] = failure
                            onItemResult(item.smsId, failure)
                        }
                        return@withContext results
                    }

                sendItems(setup, items, results, onItemResult, shouldContinue)
                results
            }

        private data class BatchSetup(
            val session: Session,
            val transport: Transport,
            val fromAddress: InternetAddress,
            val toAddress: InternetAddress,
        )

        private sealed class ItemOutcome {
            data class Sent(
                val result: EmailResult,
            ) : ItemOutcome()

            // A dead transport stops the batch; the caller fails the remaining items as interrupted.
            data class TransportDead(
                val result: EmailResult,
            ) : ItemOutcome()
        }

        private fun connectBatch(): Result<BatchSetup> {
            try {
                val config = smtpConfig()
                val settings = prefs.load()
                val session = authenticatedSession(config)
                val fromName = settings.senderName.ifBlank { null }
                val toName = settings.destName.ifBlank { null }
                val fromAddress = internetAddress(settings.senderEmail, fromName)
                val toAddress = internetAddress(settings.destEmail, toName)
                val newTransport = session.getTransport("smtp")
                newTransport.connect(config.host, config.port, config.username, config.password)
                return Result.success(BatchSetup(session, newTransport, fromAddress, toAddress))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("EmailService", "sendEmailBatch setup failed, failing all items: ${SmtpErrorMapper.diagnostic(e)}")
                return Result.failure(e)
            }
        }

        private fun sendItems(
            setup: BatchSetup,
            items: List<BatchEmailItem>,
            results: MutableMap<Long, EmailResult>,
            onItemResult: (smsId: Long, result: EmailResult) -> Unit,
            shouldContinue: (() -> Boolean)?,
        ) {
            try {
                var lastSentId: Long? = null
                var sentCount = 0
                var stoppedEarly = false
                for (index in items.indices) {
                    val item = items[index]
                    // The stop gate flipped off: report the current (not yet sent) and every
                    // remaining item as interrupted, so the caller finalizes each claimed row
                    // (while off, the retry scheduler's off-park turns it SENDING -> IGNORED).
                    if (shouldContinue != null && !shouldContinue()) {
                        Logger.w(
                            "EmailService",
                            "sendEmailBatch stop gate closed, interrupting ${items.size - index} items, lastSentBefore=${lastSentId ?: "none"}",
                        )
                        val interrupted =
                            EmailResult.Failure(appContext.getString(R.string.sms_status_send_interrupted))
                        for (remaining in items.subList(index, items.size)) {
                            results[remaining.smsId] = interrupted
                            onItemResult(remaining.smsId, interrupted)
                        }
                        stoppedEarly = true
                        break
                    }
                    when (val outcome = sendOne(setup, item)) {
                        is ItemOutcome.Sent -> {
                            results[item.smsId] = outcome.result
                            onItemResult(item.smsId, outcome.result)
                            if (outcome.result is EmailResult.Success) {
                                lastSentId = item.smsId
                                sentCount++
                            }
                        }

                        is ItemOutcome.TransportDead -> {
                            results[item.smsId] = outcome.result
                            onItemResult(item.smsId, outcome.result)
                            val interrupted =
                                EmailResult.Failure(appContext.getString(R.string.sms_status_send_interrupted))
                            for (remaining in items.subList(index + 1, items.size)) {
                                results[remaining.smsId] = interrupted
                                onItemResult(remaining.smsId, interrupted)
                            }
                            stoppedEarly = true
                            break
                        }
                    }
                }
                // Normal exit only: the stop-gate and the transport-dead breaks log their
                // own lines. One count-only line for the whole batch, never per item.
                if (!stoppedEarly) {
                    Logger.i("EmailService", "sendEmailBatch finished, sent $sentCount of ${items.size}")
                }
            } finally {
                try {
                    setup.transport.close()
                } catch (e: Exception) {
                    Logger.w("EmailService", "Failed to close batch transport: ${e.javaClass.simpleName}")
                }
            }
        }

        private fun sendOne(
            setup: BatchSetup,
            item: BatchEmailItem,
        ): ItemOutcome {
            try {
                val mimeMessage =
                    CustomMimeMessage(setup.session, setup.fromAddress.address).apply {
                        setFrom(setup.fromAddress)
                        setRecipient(Message.RecipientType.TO, setup.toAddress)
                        this.subject = sanitizeSubject(item.subject)
                        setText(item.body, "utf-8")
                        if (item.json) {
                            addHeader(AppConfig.JSON_EMAIL_HEADER_NAME, AppConfig.JSON_EMAIL_HEADER_VALUE)
                        }
                    }

                setup.transport.sendMessage(mimeMessage, mimeMessage.getAllRecipients())
                return ItemOutcome.Sent(EmailResult.Success)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isTransportDead(e)) {
                    Logger.e(
                        "EmailService",
                        "sendEmailBatch transport dead, stopping batch: smsId=${item.smsId}, ${SmtpErrorMapper.diagnostic(e)}",
                    )
                    return ItemOutcome.TransportDead(mappedFailure(e))
                }
                Logger.e(
                    "EmailService",
                    "sendEmailBatch per-message failed: smsId=${item.smsId}, ${e.javaClass.simpleName}",
                )
                return ItemOutcome.Sent(mappedFailure(e))
            }
        }

        // A stored or passed port outside 1..65535 can never connect; throwing lets every
        // path fail fast with an accurate message instead of a misleading network error.
        private fun validatedPort(port: Int): Int {
            if (port !in AppConfig.VALID_PORT_RANGE) {
                Logger.e("EmailService", "invalid SMTP port: $port")
                throw InvalidSmtpPortException(port)
            }
            return port
        }

        private fun smtpConfig(): SmtpConfig {
            val settings = prefs.load()
            return SmtpConfig(
                host = settings.smtpHost,
                port = validatedPort(settings.smtpPort),
                protocol = settings.smtpProtocol,
                username = settings.smtpUsername,
                password = prefs.smtpPassword,
            )
        }

        private fun authenticatedSession(config: SmtpConfig): Session =
            Session.getInstance(
                buildSmtpProperties(config),
                object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication =
                        PasswordAuthentication(config.username, config.password)
                },
            )

        // The 3-arg InternetAddress constructor does not reject control characters in the
        // address, so a CRLF stored in sender/dest email would land raw in the From/To
        // headers; stripping them here fails the send instead (defense in depth, the import
        // already validates the address format).
        private fun internetAddress(
            address: String,
            personal: String?,
        ): InternetAddress {
            val clean = sanitizeHeaderValue(address)
            return if (personal.isNullOrBlank()) {
                InternetAddress(clean)
            } else {
                InternetAddress(clean, EmailUtil.encodePersonalName(personal), "UTF-8")
            }
        }

        // An invalid port (stored or passed) cannot recover on its own: permanent, so the
        // queue stops retrying a config the user has to fix in Settings.
        private fun mappedFailure(e: Throwable): EmailResult.Failure =
            if (e is InvalidSmtpPortException) {
                EmailResult.Failure(appContext.getString(R.string.error_invalid_smtp_port), permanent = true)
            } else {
                EmailResult.Failure(
                    SmtpErrorMapper.toUserMessage(e, appContext),
                    SmtpErrorMapper.isPermanent(e),
                )
            }

        private fun isTransportDead(e: Exception): Boolean {
            var current: Throwable? = e
            while (current != null) {
                if (current is java.io.IOException) {
                    return true
                }
                current = current.cause
            }
            return false
        }
    }
