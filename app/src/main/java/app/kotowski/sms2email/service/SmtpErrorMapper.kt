package app.kotowski.sms2email.service

import android.content.Context
import app.kotowski.sms2email.R
import com.sun.mail.smtp.SMTPAddressFailedException
import com.sun.mail.smtp.SMTPSendFailedException
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.mail.AuthenticationFailedException
import javax.mail.MessagingException
import javax.mail.SendFailedException
import javax.net.ssl.SSLException

object SmtpErrorMapper {
    private val EnhancedCodeMap =
        mapOf(
            // 1.X.X – Addressing
            "5.1.1" to R.string.smtp_enh_5_1_1,
            "5.1.2" to R.string.smtp_enh_5_1_2,
            "5.1.3" to R.string.smtp_enh_5_1_3,
            "5.1.4" to R.string.smtp_enh_5_1_4,
            "5.1.6" to R.string.smtp_enh_5_1_6,
            // 2.X.X – Mailbox
            "4.2.1" to R.string.smtp_enh_4_2_1,
            "5.2.2" to R.string.smtp_enh_5_2_2,
            "5.2.3" to R.string.smtp_enh_5_2_3,
            // 3.X.X – Mail System
            "4.3.1" to R.string.smtp_enh_4_3_1,
            "5.3.4" to R.string.smtp_enh_5_3_4,
            // 4.X.X – Network / Routing
            "4.4.1" to R.string.smtp_enh_4_4_1,
            "4.4.7" to R.string.smtp_enh_4_4_7,
            // 7.X.X – Security / Policy
            "5.7.0" to R.string.smtp_enh_5_7_0,
            "5.7.1" to R.string.smtp_enh_5_7_1,
            "5.7.2" to R.string.smtp_enh_5_7_2,
            "5.7.8" to R.string.smtp_enh_5_7_8,
            "5.7.9" to R.string.smtp_enh_5_7_9,
            "5.7.11" to R.string.smtp_enh_5_7_11,
        )

    private val CodeMap =
        mapOf(
            // TLS (RFC 3207)
            220 to R.string.smtp_220,
            454 to R.string.smtp_454,
            501 to R.string.smtp_501,
            523 to R.string.smtp_523,
            530 to R.string.smtp_530,
            538 to R.string.smtp_538,
            // Authentication (RFC 4954)
            235 to R.string.smtp_235,
            334 to R.string.smtp_334,
            432 to R.string.smtp_432,
            534 to R.string.smtp_534,
            535 to R.string.smtp_535,
            // Transient failures (4xx)
            421 to R.string.smtp_421,
            431 to R.string.smtp_431,
            442 to R.string.smtp_442,
            450 to R.string.smtp_450,
            451 to R.string.smtp_451,
            452 to R.string.smtp_452,
            455 to R.string.smtp_455,
            471 to R.string.smtp_471,
            // Permanent failures (5xx)
            500 to R.string.smtp_500,
            502 to R.string.smtp_502,
            503 to R.string.smtp_503,
            504 to R.string.smtp_504,
            510 to R.string.smtp_510,
            511 to R.string.smtp_511,
            512 to R.string.smtp_512,
            513 to R.string.smtp_513,
            521 to R.string.smtp_521,
            541 to R.string.smtp_541,
            550 to R.string.smtp_550,
            551 to R.string.smtp_551,
            552 to R.string.smtp_552,
            553 to R.string.smtp_553,
            554 to R.string.smtp_554,
            555 to R.string.smtp_555,
            556 to R.string.smtp_556,
            571 to R.string.smtp_571,
        )

    private val EnhancedCodeRegex = Regex("""\b([45]\.\d{1,3}\.\d{1,3})\b""")

    /**
     * Maps an SMTP-related exception to a user-facing, i18n string.
     *
     * Resolution order:
     *  1. AuthenticationFailedException  →  authentication error
     *  2. Socket/network-level IOException, direct or as the root cause  →  timeout or network error
     *  3. Enhanced status code (X.Y.Z) extracted from the exception message
     *  4. 3-digit SMTP returnCode from SMTPSendFailedException / SMTPAddressFailedException
     *  5. Fallback generic error
     */
    fun toUserMessage(
        e: Throwable,
        context: Context,
    ): String {
        if (e is AuthenticationFailedException) {
            return context.getString(R.string.smtp_auth_failed)
        }

        if (e is SocketTimeoutException) {
            return context.getString(R.string.smtp_timeout)
        }

        if (e is InterruptedIOException) {
            return context.getString(R.string.smtp_timeout)
        }

        if (e is IOException) {
            return context.getString(R.string.smtp_network_error)
        }

        val networkMsg = resolveNetworkCause(e, context)
        if (networkMsg != null) return networkMsg

        val (returnCode, rawMessage) = unwrapSmtpException(e)

        return if (returnCode > 0) {
            resolveEnhancedCode(rawMessage, context)
                ?: CodeMap[returnCode]?.let { context.getString(it) }
                ?: fallbackForCode(returnCode, context)
        } else {
            context.getString(R.string.smtp_unknown)
        }
    }

    /**
     * Determines whether a failed send is permanent (retrying cannot help) or transient.
     *
     *  - AuthenticationFailedException: bad SMTP credentials, permanent until the user
     *    fixes them; the exception carries no SMTP return code.
     *  - SMTPSendFailedException / SMTPAddressFailedException (MAIL FROM / DATA / `.`
     *    rejections, top-level): the 3-digit return code (4xx transient, 5xx permanent);
     *    no code (0 or -1, e.g. server EOF) means no server verdict was received, so retry.
     *  - SendFailedException (RCPT TO rejected): JavaMail already split the outcome into
     *    invalid addresses (permanent: 550/551/553/501/503 and other 5xx) and valid-unsent
     *    addresses (transient: 4xx and 552 mailbox-quota) — trust that split instead of the
     *    chained return code, which would misclassify the 552-quota case as permanent. The
     *    SMTPSendFailedException/SMTPAddressFailedException branches run first because
     *    both extend SendFailedException but carry no address arrays.
     *  - Anything else (network, TLS, timeouts): no SMTP code is extractable, so retry.
     */
    fun isPermanent(e: Throwable): Boolean =
        when (e) {
            is AuthenticationFailedException -> true
            is SMTPSendFailedException -> e.returnCode in 500..599
            is SMTPAddressFailedException -> e.returnCode in 500..599
            is SendFailedException -> e.invalidAddresses.isNullOrEmpty().not()
            else -> unwrapSmtpException(e).first in 500..599
        }

    /**
     * PII-safe one-line diagnostic for the log: the class name and, when one is
     * extractable, the SMTP return code.
     *
     * [Throwable.message] is deliberately excluded: a connect failure carries the SMTP
     * host and a server rejection can echo an address, both of which would reach the
     * exportable log file.
     *
     * @param e the caught exception; JavaMail wrapping at any depth is accepted
     * @return e.g. `MessagingException, code=554` or `SMTPSendFailedException`
     */
    fun diagnostic(e: Throwable): String =
        e.javaClass.simpleName +
            (unwrapSmtpException(e).first.takeIf { it > 0 }?.let { ", code=$it" } ?: "")

    /**
     * Recursively unwraps JavaMail's nested exceptions to find the deepest root cause.
     *
     * Follows both [Throwable.cause] and MessagingException.nextException chains.
     */
    private fun getRootCause(e: Throwable): Throwable {
        var current: Throwable? = e
        while (true) {
            val next =
                when {
                    current is MessagingException -> current.nextException
                    else -> null
                }
            val cause = current?.cause
            val deeper = next ?: cause
            if (deeper != null && deeper != current) {
                current = deeper
            } else {
                break
            }
        }
        return current ?: e
    }

    /**
     * Inspects the root cause of a MessagingException for network-level
     * exceptions that JavaMail wraps (DNS failures, connection refused,
     * socket drops, SSL/port mismatches).
     */
    private fun resolveNetworkCause(
        e: Throwable,
        context: Context,
    ): String? {
        val rootCause = getRootCause(e)

        return when (rootCause) {
            is UnknownHostException -> context.getString(R.string.smtp_dns_error)
            is ConnectException -> context.getString(R.string.smtp_connection_error)
            is SocketTimeoutException -> context.getString(R.string.smtp_timeout)
            is SocketException -> context.getString(R.string.smtp_socket_error)
            is SSLException -> context.getString(R.string.smtp_ssl_port_error)
            is EOFException -> context.getString(R.string.smtp_ssl_port_error)
            else -> null
        }
    }

    /**
     * Walks the MessagingException.nextException chain to locate an
     * SMTPSendFailedException or SMTPAddressFailedException and extract
     * its returnCode and raw server message.
     */
    private fun unwrapSmtpException(e: Throwable): Pair<Int, String> {
        var current: Throwable? = e

        while (current != null) {
            when (current) {
                is SMTPAddressFailedException -> return current.returnCode to (current.message ?: "")
                is SMTPSendFailedException -> return current.returnCode to (current.message ?: "")
                is MessagingException -> current = current.nextException
                else -> break
            }
        }

        return 0 to ""
    }

    /**
     * Attempts to extract an RFC 3463 enhanced status code (X.Y.Z) from the
     * raw server response string and resolves it to a localized string.
     *
     * Returns `null` when no recognized code is present.
     */
    private fun resolveEnhancedCode(
        raw: String,
        context: Context,
    ): String? {
        val match = EnhancedCodeRegex.find(raw) ?: return null
        val resId = EnhancedCodeMap[match.value] ?: return null
        return context.getString(resId)
    }

    /**
     * Returns a formatted fallback string for 3-digit codes that have no
     * explicit entry in [CodeMap].
     *
     * 4xx → transient, 5xx → permanent.
     */
    private fun fallbackForCode(
        code: Int,
        context: Context,
    ): String =
        if (code in 400..499) {
            context.getString(R.string.smtp_transient_fallback, code)
        } else {
            context.getString(R.string.smtp_permanent_fallback, code)
        }
}
