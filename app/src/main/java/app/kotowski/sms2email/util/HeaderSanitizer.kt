package app.kotowski.sms2email.util

import app.kotowski.sms2email.AppConfig

/**
 * Strips C0 control characters (U+0000..U+001F) and DEL (U+007F) so the result is safe to
 * place in a MIME header (e.g. Subject).
 *
 * Without this, an attacker-controlled value such as an SMS sender address could embed a
 * CRLF and break out into extra injected headers (SMTP/HTTP-style header injection).
 */
fun sanitizeHeaderValue(value: String): String = value.filterNot { it.code in 0..0x1F || it.code == 0x7F }

/**
 * Truncates the value to at most [maxBytes] UTF-8 bytes, on a code point boundary.
 *
 * A multi-byte sequence that does not fully fit is dropped whole, never split into a
 * partial character. A value that already fits is returned unchanged.
 */
fun String.truncatedToUtf8Bytes(maxBytes: Int): String {
    val bytes = toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return this
    // The byte at `end` is the first one dropped. If it is a UTF-8 continuation byte
    // (10xxxxxx), the character owning it started before the cut and is split by it, so
    // step left to that character's leading byte and drop the whole character.
    var end = maxBytes
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
    return String(bytes, 0, end, Charsets.UTF_8)
}

/**
 * Makes a composed subject safe for the Subject header: strips control characters and caps
 * the result at SUBJECT_MAX_BYTES, so the physical `Subject: ` line on the wire stays within
 * the 998-octet bound a conforming SMTP server must accept (RFC 5321, section 4.5.3.1.6).
 *
 * The cut is a tail cut, so with the template order `[prefix] SMS from <name> on <sim>` the
 * prefix is kept, the SIM suffix drops first and the name is truncated last.
 */
fun sanitizeSubject(value: String): String = sanitizeHeaderValue(value).truncatedToUtf8Bytes(AppConfig.SUBJECT_MAX_BYTES)
