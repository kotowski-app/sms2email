package app.kotowski.sms2email.util

import android.util.Patterns
import javax.mail.internet.MimeUtility

object EmailUtil {
    private const val MAX_ASCII_CODE_POINT: Int = 127

    private val PREFIX_PATTERN = Regex("^(smtp[\\w-]*)\\.(.+)")
    private val MAIL_PATTERN = Regex("^mail\\.(.+)")
    private val EMAIL_PATTERN = Regex("^[^@]+@[^@]+$")

    // The single email-address rule of the app: the platform's email pattern. Used by the
    // forms (the onboarding wizard and the settings screen) and by the settings import.
    fun isValidEmail(value: String): Boolean = Patterns.EMAIL_ADDRESS.matcher(value).matches()

    fun isInvalidEmail(value: String): Boolean = value.isNotEmpty() && !isValidEmail(value)

    // Strips the leading smtp*/mail. server labels so the result reads as a domain
    // (smtp.gmail.com -> gmail.com, smtp.mail.yahoo.com -> yahoo.com). It stops before the
    // result would drop below two labels, so real mail.* registered domains survive
    // (mail.ru and mail.com stay as-is); a host without a recognized prefix is returned
    // lowercased and otherwise untouched.
    fun extractDomain(host: String): String {
        var domain = host.lowercase()
        while (true) {
            val rest =
                when {
                    PREFIX_PATTERN.matches(domain) -> PREFIX_PATTERN.find(domain)!!.groupValues[2]
                    MAIL_PATTERN.matches(domain) -> MAIL_PATTERN.find(domain)!!.groupValues[1]
                    else -> null
                } ?: break
            if (!rest.contains('.')) break
            domain = rest
        }
        return domain
    }

    // The auto-fill's derivation from the draft's raw (possibly padded) values: the
    // edge spaces are ignored, so a padded host or username can never leak into the
    // derived address.
    fun deriveSenderEmail(
        username: String,
        host: String,
    ): String {
        val user = username.trim()
        val cleanHost = host.trim()
        if (user.isEmpty() || cleanHost.isEmpty()) return ""
        if (EMAIL_PATTERN.matches(user)) return user
        return "$user@${extractDomain(cleanHost)}"
    }

    fun encodePersonalName(personal: String): String {
        val clean = sanitizeHeaderValue(personal)
        return if (clean.any { it.code > MAX_ASCII_CODE_POINT }) {
            MimeUtility.encodeText(clean, "UTF-8", "B")
        } else {
            clean
        }
    }
}
