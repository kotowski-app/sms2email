package app.kotowski.sms2email.util

// The system-reported sender address can be null or blank: some service codes report an
// empty originating address. Both collapse to a single fallback so the value stays safe for
// the dedup key, the search facets and the email/UI display. Shared by the live receive and
// the import path so the two normalize identically and their dedup keys never diverge.
fun normalizeSenderAddress(
    raw: String?,
    fallback: String,
): String = raw?.takeIf { it.isNotBlank() } ?: fallback
