package app.kotowski.sms2email.service

import android.content.Context
import android.telephony.SubscriptionManager
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.R
import app.kotowski.sms2email.util.DateUtil
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object EmailBuilder {
    data class EmailParts(
        val subject: String,
        val body: String,
        // True when the body is the machine JSON format: the send path marks the message
        // with the format's header so a consumer can route on it without parsing the body.
        val json: Boolean = false,
    )

    fun buildEmail(
        context: Context,
        phoneNumber: String,
        contactName: String?,
        simId: Int,
        simName: String?,
        subjectPrefix: String,
        timeReceived: Long,
        messageBody: String,
        useJsonFormat: Boolean,
        includeTimeInBody: Boolean,
        messageId: String?,
    ): EmailParts {
        if (useJsonFormat) {
            return buildJsonEmail(
                context = context,
                phoneNumber = phoneNumber,
                contactName = contactName,
                simId = simId,
                simName = simName,
                subjectPrefix = subjectPrefix,
                timeReceived = timeReceived,
                messageBody = messageBody,
                messageId = messageId,
            )
        }

        return buildPlainEmail(
            context = context,
            phoneNumber = phoneNumber,
            contactName = contactName,
            simName = simName,
            subjectPrefix = subjectPrefix,
            timeReceived = timeReceived,
            includeTimeInBody = includeTimeInBody,
            messageBody = messageBody,
        )
    }

    // The sim suffix is the caller's decision (the display setting, the valid slot):
    // a non-null simName is always rendered.
    private fun buildPlainEmail(
        context: Context,
        phoneNumber: String,
        contactName: String?,
        simName: String?,
        subjectPrefix: String,
        timeReceived: Long,
        includeTimeInBody: Boolean,
        messageBody: String,
    ): EmailParts {
        val displayName = contactName ?: phoneNumber

        val simSuffix =
            if (simName != null) {
                context.getString(R.string.email_subject_sim_suffix, simName)
            } else {
                null
            }

        val subject = format(context, subjectPrefix, displayName, simSuffix)
        val body =
            buildString {
                // The time line is the user's choice (off by default); the JSON format
                // carries its own time_received and never renders this.
                if (includeTimeInBody) {
                    appendLine(DateUtil.formatDateTime(context, timeReceived))
                    appendLine()
                }
                appendLine(messageBody)
            }

        return EmailParts(subject, body)
    }

    // The machine format: a fixed field set, always present (a missing value is null,
    // never an absent key). Field order is unspecified — org.json's toString iterates a
    // HashMap — so a consumer must parse the JSON and must not rely on order or
    // whitespace. Every value goes through org.json's escaping, so the body never
    // carries a raw control character.
    private fun buildJsonEmail(
        context: Context,
        phoneNumber: String,
        contactName: String?,
        simId: Int,
        simName: String?,
        subjectPrefix: String,
        timeReceived: Long,
        messageBody: String,
        messageId: String?,
    ): EmailParts {
        val subject = context.getString(R.string.app_name)

        // The single canonical "when": an RFC 3339 UTC instant. Local rendering is a
        // display concern of the consumer, and a send-time device zone would drift from
        // the receive instant after a retry anyway.
        val timeReceivedUtc =
            Instant.ofEpochMilli(timeReceived).atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)

        // put(key, null) removes the key, so an explicit JSONObject.NULL keeps the
        // field present with a null value.
        val json = JSONObject()
        json.put("version", AppConfig.JSON_EMAIL_FORMAT_VERSION)
        json.put("message_id", messageId ?: JSONObject.NULL)
        json.put("time_received", timeReceivedUtc)
        json.put("source", subjectPrefix.ifBlank { null } ?: JSONObject.NULL)
        // sim_id is a row fact and unconditional (sim_name alone rides on the display
        // setting); the Android constant (-1) means "no subscription" (an imported or
        // SIM-less row), and the payload says null instead of leaking the constant.
        json.put(
            "sim_id",
            if (simId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                JSONObject.NULL
            } else {
                simId
            },
        )
        json.put("sim_name", simName ?: JSONObject.NULL)
        json.put("from_phone", phoneNumber)
        json.put("from_contact", contactName ?: JSONObject.NULL)
        json.put("message_body", messageBody)

        return EmailParts(subject, json.toString(2), json = true)
    }

    fun format(
        context: Context,
        subjectPrefix: String,
        displayName: String,
        simSuffix: String?,
    ): String {
        val hasPrefix = subjectPrefix.isNotBlank()

        return when {
            hasPrefix && simSuffix != null -> {
                context.getString(R.string.subject_template_with_sim, subjectPrefix, displayName, simSuffix)
            }

            hasPrefix -> {
                context.getString(R.string.subject_template, subjectPrefix, displayName)
            }

            simSuffix != null -> {
                context.getString(R.string.subject_template_no_prefix_with_sim, displayName, simSuffix)
            }

            else -> {
                context.getString(R.string.subject_template_no_prefix, displayName)
            }
        }
    }
}
