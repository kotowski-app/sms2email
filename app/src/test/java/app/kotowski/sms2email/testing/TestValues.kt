package app.kotowski.sms2email.testing

// Central safe test data. Every value is reserved for documentation and testing by an
// RFC or a national regulator, so none of them can belong to a real person or service:
// NA NMBAN fiction range 555-01XX, UK Ofcom drama numbers, FR cinema range, the
// provably-unreachable +1 000... shape, and RFC 2606 example.* domains.
// One place to audit for PII; check-test-pii.sh enforces the same allowlist in CI.
object TestPhone {
    // NA fiction range (+1 555 01XX, 0100-0199) and its 11-digit E.164 spelling.
    const val SELF = "+15550100"
    const val SELF_E164 = "+15550100000"
    const val FRIEND = "+15550142"
    const val FOREIGN = "+442079460001" // UK Ofcom drama range (London 7946 0000-0999)
    const val FRENCH = "+33123456789" // FR cinema range
    const val INVALID = "+00000000001" // universally "not a number"

    // +1 followed by an area code starting with 0: unreachable by construction.
    const val SYNTHETIC_1 = "+10000000000"
    const val SYNTHETIC_2 = "+10000000001"
    const val SYNTHETIC_3 = "+10000000002"
}

// RFC 2606: example.com / example.org / example.net are reserved for documentation.
object TestEmail {
    const val SELF = "user@example.com"
    const val RECIPIENT = "alice@example.org"
    const val BOUNCY = "nobody@example.net"
    const val OTHER = "custom@example.net"
}
