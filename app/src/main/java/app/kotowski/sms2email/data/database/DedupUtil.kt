package app.kotowski.sms2email.data.database

import java.security.MessageDigest
import java.util.Locale

object DedupUtil {
    // Identity = (sender, time bucket, body): the dedupKey stored on every row and the
    // unique index's collision key (the pre-check of insertIfNew is the ±window range
    // match, serialized by the repository's ingest mutex; the index is the backstop for
    // same-bucket collisions). Live and import paths see the
    // same message with slightly different raw values, so both are normalized here:
    // - timestamp is bucketed to the level's window; a zero window (DedupLevel.OFF) keeps
    //   the exact timestamp instead of a bucket, so OFF is an exact-timestamp match: live
    //   stores the receive timestamp, import reads the provider DATE (ms/s apart; OEM
    //   redelivery arrives ms later)
    // - phone whitespace is stripped: live stores displayOriginatingAddress
    //   ("True App"), import stores the raw provider address ("TrueApp")
    fun computeDedupKey(
        phoneNumber: String,
        timestamp: Long,
        messageBody: String,
        bucketMillis: Long,
    ): String {
        val timeBucket = if (bucketMillis > 0) timestamp / bucketMillis else timestamp
        val normalizedPhone = phoneNumber.filterNot { it.isWhitespace() }
        val canonical = "$normalizedPhone||$timeBucket||${messageBody.lowercase(Locale.ROOT)}"
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
