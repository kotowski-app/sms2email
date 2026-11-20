package app.kotowski.sms2email.receiver

import android.content.BroadcastReceiver
import android.content.BroadcastReceiver.PendingResult
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import app.kotowski.sms2email.R
import app.kotowski.sms2email.di.PrefsEntryPoint
import app.kotowski.sms2email.util.Logger
import app.kotowski.sms2email.util.normalizeSenderAddress
import app.kotowski.sms2email.work.SmsProcessor
import dagger.hilt.android.EntryPointAccessors

// No @AndroidEntryPoint/@Inject: Hilt would field-inject SmsProcessor in the generated superclass
// before onReceive, and building it builds EncryptedPrefs, whose MasterKey/prefs construction
// throws on a broken keystore with nothing to catch it (the process dies before onReceive, and a
// non-ordered SMS broadcast is never redelivered). The processor is resolved in onReceive inside a
// try/catch instead, matching Sms2EmailApp/MainActivity; a failure drops the SMS gracefully.
// `open` only so the unit tests can substitute the processor (no Hilt graph in a bare app).
open class SmsReceiver : BroadcastReceiver() {
    private val tag = "SmsReceiver"

    // The door the unit tests substitute; in production it resolves the singleton through the
    // same EntryPoint Sms2EmailApp/MainActivity use.
    protected open fun resolveSmsProcessor(context: Context): SmsProcessor =
        EntryPointAccessors
            .fromApplication(context.applicationContext, PrefsEntryPoint::class.java)
            .smsProcessor()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pendingResult = goAsync()
        val action = intent.action

        if (action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            Logger.w(tag, "Unexpected action: $action")
            pendingResult?.finish()
            return
        }

        // A malformed PDU can throw inside the parse (getMessagesFromIntent or the SmsMessage
        // accessors). A one-shot SMS broadcast is never redelivered, so catch it, log, and drop
        // (finishing the pending result) instead of crashing the process with no DB row and no
        // retry. The success path hands the finish to the processing coroutine (onComplete), so
        // only a failure finishes here - the result is finished exactly once.
        try {
            @Suppress("DEPRECATION")
            val rawMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

            // The framework returns a null entry for a PDU it fails to parse (e.g. a
            // zero-length originating address, which some service codes send). Such an
            // entry carries no body or address, so it is dropped like a missing PDU.
            val messages = rawMessages?.filterNotNull()

            if (messages.isNullOrEmpty()) {
                Logger.w(tag, "No messages found in intent")
                pendingResult?.finish()
                return
            }

            Logger.i(tag, "SMS_RECEIVED_ACTION, parts=${messages.size}")

            // Resolved here, inside onReceive, so a broken keystore (which throws while building
            // the processor) is caught: the SMS is logged and dropped, and the pending result is
            // finished, instead of the process dying in the receiver's constructor.
            val processor =
                try {
                    resolveSmsProcessor(context)
                } catch (e: Exception) {
                    Logger.e(tag, "SmsProcessor unavailable, dropping SMS: ${e.message}", e)
                    pendingResult?.finish()
                    return
                }

            val subscriptionId = resolveSubscriptionId(intent)
            val phoneNumber =
                normalizeSenderAddress(
                    messages[0].displayOriginatingAddress,
                    context.getString(R.string.sms_unknown_sender),
                )

            val body =
                if (messages.size == 1) {
                    val single = messages[0]
                    Logger.i(tag, "Received single SMS: simId=$subscriptionId, bodyLen=${single.messageBody?.length ?: 0}")
                    single.messageBody ?: ""
                } else {
                    val segmentBodies = messages.mapNotNull { it.messageBody }
                    Logger.i(tag, "Received multi-part SMS: simId=$subscriptionId, parts=${messages.size}")
                    assembleBody(messages[0].displayMessageBody, segmentBodies)
                }

            // The service-center timestamp of the first part: the raw PDU SCTS the "Use PDU
            // timestamp" setting may store (SmsProcessor resolves the validity).
            deliver(processor, phoneNumber, body, subscriptionId, messages[0].timestampMillis, pendingResult)
        } catch (e: Exception) {
            Logger.e(tag, "Failed to handle SMS, dropping: ${e.javaClass.simpleName}: ${e.message}", e)
            pendingResult?.finish()
        }
    }

    // EXTRA_SUBSCRIPTION_INDEX carries the subscription id (the telephony service fills it
    // via SubscriptionManager.putSubscriptionIdExtra), not a slot index.
    private fun resolveSubscriptionId(intent: Intent): Int =
        intent.getIntExtra(
            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
            SubscriptionManager.INVALID_SUBSCRIPTION_ID,
        )

    private fun deliver(
        processor: SmsProcessor,
        phoneNumber: String,
        body: String,
        subscriptionId: Int,
        pduTimestamp: Long,
        pendingResult: PendingResult?,
    ) {
        val timestamp = System.currentTimeMillis()

        val incomingSms =
            SmsProcessor.IncomingSms(
                phoneNumber = phoneNumber,
                messageBody = body,
                timestamp = timestamp,
                simId = subscriptionId,
                pduTimestamp = pduTimestamp,
            )

        // No guard here: processIncomingSms only launches a coroutine (it cannot throw
        // synchronously), and a throw would be caught by the guard in onReceive.
        processor.processIncomingSms(incomingSms) { pendingResult?.finish() }
        Logger.i(tag, "deliver: processIncomingSms returned OK")
    }

    internal fun assembleBody(
        displayMessageBody: String?,
        segmentBodies: List<String>,
    ): String =
        if (
            displayMessageBody != null &&
            displayMessageBody.length >= segmentBodies.sumOf { it.length }
        ) {
            displayMessageBody
        } else {
            segmentBodies.joinToString("")
        }
}
