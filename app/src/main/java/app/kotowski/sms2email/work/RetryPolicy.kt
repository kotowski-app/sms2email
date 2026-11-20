package app.kotowski.sms2email.work

import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.data.database.StatusUpdate
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

object RetryPolicy {
    fun computeBackoffWithJitter(attempt: Int): Long {
        val exponential = (AppConfig.BASE_BACKOFF_MS * AppConfig.RETRY_BACKOFF_GROWTH_BASE.pow(attempt)).toLong()
        val capped = min(exponential, AppConfig.MAX_RETRY_PERIOD_MS)
        val jitterRange = (capped * AppConfig.RETRY_BACKOFF_JITTER_FRACTION).toLong()
        val jitter = Random.nextLong(-jitterRange, jitterRange + 1)
        return capped + jitter
    }

    fun isExpiredByFirstAttempt(
        firstSendAttemptTime: Long?,
        now: Long,
    ): Boolean {
        val attemptTime = firstSendAttemptTime ?: return false
        return now - attemptTime >= AppConfig.MAX_RETRY_PERIOD_MS
    }

    fun computeNextRetryTime(
        attempt: Int,
        now: Long,
    ): Long = now + computeBackoffWithJitter(attempt)

    // The full retry window payload: the attempt count, the last-attempt clock, and the
    // next attempt at now + backoff(attempt). firstAttempt is written verbatim — pass
    // null to keep the stored value (the expiry clock stays stopped until a real attempt).
    fun retryWindow(
        statusDetails: String?,
        attempt: Int,
        firstAttempt: Long?,
        now: Long,
    ): StatusUpdate =
        StatusUpdate(
            statusDetails = statusDetails,
            retryCount = attempt,
            timeFirstSendAttempt = firstAttempt,
            timeLastSendAttempt = now,
            timeNextSendAttempt = computeNextRetryTime(attempt, now),
        )

    // Terminal failure payload: no next attempt, the first-attempt clock is left alone.
    fun failedWindow(
        statusDetails: String?,
        retryCount: Int,
        now: Long,
    ): StatusUpdate =
        StatusUpdate(
            statusDetails = statusDetails,
            retryCount = retryCount,
            timeLastSendAttempt = now,
        )

    // A user-initiated "forward all": a fresh window with no attempt yet, due at the
    // base backoff (exact, no jitter) since the batch is enqueued immediately.
    fun freshForwardWindow(now: Long): StatusUpdate =
        StatusUpdate(
            retryCount = 0,
            timeFirstSendAttempt = now,
            timeLastSendAttempt = now,
            timeNextSendAttempt = now + AppConfig.BASE_BACKOFF_MS,
        )

    // A user-initiated manual (re-)forward parks the row as PENDING: a fresh window with
    // no attempt yet; the claim at send time stamps the last-attempt clock.
    fun freshPendingWindow(now: Long = System.currentTimeMillis()): StatusUpdate =
        StatusUpdate(
            retryCount = 0,
            timeFirstSendAttempt = now,
        )
}
