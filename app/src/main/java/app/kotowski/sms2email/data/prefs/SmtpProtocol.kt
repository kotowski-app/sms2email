package app.kotowski.sms2email.data.prefs

import app.kotowski.sms2email.AppConfig

// The SMTP security protocol of the connection. Both are always encrypted:
// START_TLS upgrades a plain connection with a required STARTTLS, IMPLICIT_TLS
// is TLS from the first byte.
enum class SmtpProtocol {
    START_TLS,
    IMPLICIT_TLS,
    ;

    val defaultPort: Int
        get() =
            when (this) {
                START_TLS -> AppConfig.SMTP_PORT_START_TLS
                IMPLICIT_TLS -> AppConfig.SMTP_PORT_IMPLICIT_TLS
            }

    companion object {
        // Parses a stored protocol name (the enum name, as written to the prefs and the
        // exports). A missing or unknown value degrades to START_TLS.
        fun fromStored(value: String?): SmtpProtocol = entries.firstOrNull { it.name == value } ?: START_TLS
    }
}
