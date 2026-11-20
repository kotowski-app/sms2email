package app.kotowski.sms2email.work

import android.content.Context
import android.telephony.SubscriptionManager
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.Sms
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.StatusUpdate
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.data.prefs.isComplete
import app.kotowski.sms2email.service.EmailBuilder
import app.kotowski.sms2email.service.EmailResult
import app.kotowski.sms2email.service.EmailService
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.system.NetworkUtil
import app.kotowski.sms2email.system.SimNameResolver
import app.kotowski.sms2email.util.LanguageHelper
import app.kotowski.sms2email.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsSendExecutor
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val smsRepository: SmsRepository,
        private val emailService: EmailService,
        private val prefs: EncryptedPrefs,
        private val simNameResolver: SimNameResolver,
        private val contactResolver: ContactResolver,
        private val retryScheduler: SmsRetryScheduler,
    ) {
        private val tag = "SmsSendExecutor"

        sealed class TriageResult {
            object Proceed : TriageResult()

            object Skipped : TriageResult()
        }

        sealed class SendResult {
            object NotClaimed : SendResult()

            data class Outcome(
                val result: EmailResult,
            ) : SendResult()
        }

        suspend fun triage(sms: Sms): TriageResult {
            if (
                retryScheduler.failIfExpired(
                    sms.id,
                    sms.timeFirstSendAttempt,
                    sms.retryCount,
                    listOf(SmsStatus.PENDING, SmsStatus.RETRYING),
                )
            ) {
                return TriageResult.Skipped
            }
            return TriageResult.Proceed
        }

        suspend fun buildParts(sms: Sms): EmailBuilder.EmailParts {
            // One snapshot for the whole email, so a concurrent import cannot mix
            // settings between the parts.
            val settings = prefs.load()
            val contactName = if (settings.useContactResolution) contactResolver.resolve(sms.phoneNumber) else null
            val simName =
                if (settings.displaySimCards && sms.simId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    simNameResolver.getSimDisplayNameSync(sms.simId)
                } else {
                    null
                }

            return EmailBuilder.buildEmail(
                context = LanguageHelper.applyLocale(appContext),
                phoneNumber = sms.phoneNumber,
                contactName = contactName,
                simId = sms.simId,
                simName = simName,
                subjectPrefix = settings.subjectPrefix,
                timeReceived = sms.timeReceived,
                messageBody = sms.messageBody,
                useJsonFormat = settings.useJsonEmailFormat,
                includeTimeInBody = settings.includeTimeInBody,
                // The row's content identity: stable per row, so a consumer can dedup a
                // re-send of the same row.
                messageId = sms.dedupKey,
            )
        }

        suspend fun claim(sms: Sms): Boolean =
            smsRepository.transitionSmsStatus(
                sms.id,
                listOf(SmsStatus.PENDING, SmsStatus.RETRYING),
                SmsStatus.SENDING,
                StatusUpdate(
                    timeFirstSendAttempt = sms.timeFirstSendAttempt ?: System.currentTimeMillis(),
                    timeLastSendAttempt = System.currentTimeMillis(),
                ),
            )

        // The wizard's completeness rule on the stored config: the single check of whether a
        // background send can ever succeed.
        fun isSmtpConfigured(): Boolean = prefs.load().isComplete(prefs.smtpPassword)

        suspend fun send(
            sms: Sms,
            parts: EmailBuilder.EmailParts,
        ): SendResult {
            if (!claim(sms)) {
                Logger.w(tag, "send: claim lost, not sending, smsId=${sms.id}")
                return SendResult.NotClaimed
            }

            // Forwarding flipped off after the doWork-start check but before this claim: the
            // row is already SENDING, so fail it here (never send it) and let the caller's
            // finalizeSend route the non-permanent failure to the retry scheduler's off-park
            // (SENDING -> IGNORED) instead of a retry.
            if (!prefs.load().isForwardingEnabled) {
                Logger.w(tag, "send: forwarding disabled after claim, not sending, smsId=${sms.id}")
                return SendResult.Outcome(
                    EmailResult.Failure(appContext.getString(R.string.sms_status_forwarding_stopped)),
                )
            }

            // The queue's config gate: a config that fails the completeness rule can never
            // send, so the row fails fast and permanent instead of burning the retry window on
            // empty-address errors. The row is claimed first, so it parks FAILED (one manual
            // forward after the config is fixed) rather than PENDING, where the sweep would
            // just re-enqueue it.
            if (!isSmtpConfigured()) {
                Logger.w(tag, "send: SMTP not configured, failing permanently, smsId=${sms.id}")
                return SendResult.Outcome(
                    EmailResult.Failure(appContext.getString(R.string.error_smtp_not_configured), permanent = true),
                )
            }

            NetworkUtil.checkNetwork(appContext)?.let {
                return SendResult.Outcome(EmailResult.Failure(it))
            }

            val settings = prefs.load()
            return SendResult.Outcome(
                emailService.sendEmail(
                    to = settings.destEmail,
                    toName = settings.destName.ifBlank { null },
                    from = settings.senderEmail,
                    fromName = settings.senderName.ifBlank { null },
                    subject = parts.subject,
                    body = parts.body,
                    json = parts.json,
                ),
            )
        }

        suspend fun markSent(smsId: Long) {
            val now = System.currentTimeMillis()
            val update = StatusUpdate(timeLastSendAttempt = now)
            if (
                smsRepository.transitionSmsStatus(
                    smsId,
                    listOf(SmsStatus.SENDING),
                    SmsStatus.SENT,
                    update,
                )
            ) {
                return
            }
            if (
                !smsRepository.transitionSmsStatus(
                    smsId,
                    listOf(SmsStatus.RETRYING),
                    SmsStatus.SENT,
                    update,
                )
            ) {
                Logger.e(tag, "markSent: both transitions failed, smsId=$smsId")
            }
        }

        // runBlocking is deliberate: it is opaque to worker cancellation, so a 250 OK always
        // reaches the DB before any suspension point could strand the row SENDING (the sweep
        // would then re-send it -> duplicate).
        fun finalizeSend(
            sms: Sms,
            result: EmailResult,
        ) {
            runBlocking {
                when (result) {
                    is EmailResult.Success -> {
                        markSent(sms.id)
                        // The success is the health signal: the queued rows are re-fired
                        // right away instead of waiting out their backoffs (the cooldown
                        // inside bounds a flapping server).
                        retryScheduler.drainAfterSuccess(sms.id)
                    }

                    is EmailResult.Failure -> {
                        if (result.permanent) {
                            Logger.e(tag, "finalizeSend: permanent failure, marking failed, smsId=${sms.id}, error=${result.message}")
                            retryScheduler.scheduleFailed(sms.id, sms, result.message)
                        } else {
                            Logger.w(tag, "finalizeSend: send failed, scheduling retry, smsId=${sms.id}, error=${result.message}")
                            retryScheduler.scheduleRetry(sms.id, sms, result.message)
                        }
                    }
                }
            }
        }
    }
