package app.kotowski.sms2email.work

import app.kotowski.sms2email.AppConfig
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetryPolicyTest {
    @Test
    fun `MAX_RETRY_PERIOD_MS equals 2 weeks`() {
        val expected = 14L * 24 * 60 * 60 * 1_000
        assertEquals(expected, AppConfig.MAX_RETRY_PERIOD_MS)
    }

    @Test
    fun `BASE_BACKOFF_MS equals 5 seconds`() {
        assertEquals(5_000, AppConfig.BASE_BACKOFF_MS)
    }

    @Test
    fun `computeBackoffWithJitter attempt 0 is around 5s`() {
        for (i in 0 until 100) {
            val backoff = RetryPolicy.computeBackoffWithJitter(0)
            val base = AppConfig.BASE_BACKOFF_MS
            val jitterRange = (base * 0.10).toLong()
            assertTrue(
                backoff in (base - jitterRange)..(base + jitterRange),
                "Backoff $backoff not in expected range [$${base - jitterRange}, $${base + jitterRange}]",
            )
        }
    }

    @Test
    fun `computeBackoffWithJitter attempt 1 is around 10s`() {
        for (i in 0 until 100) {
            val backoff = RetryPolicy.computeBackoffWithJitter(1)
            val expected = AppConfig.BASE_BACKOFF_MS * 2
            val jitterRange = (expected * 0.10).toLong()
            assertTrue(backoff in (expected - jitterRange)..(expected + jitterRange))
        }
    }

    @Test
    fun `computeBackoffWithJitter attempt 5 is around 160s`() {
        for (i in 0 until 100) {
            val backoff = RetryPolicy.computeBackoffWithJitter(5)
            val expected = AppConfig.BASE_BACKOFF_MS * 32
            val jitterRange = (expected * 0.10).toLong()
            assertTrue(backoff in (expected - jitterRange)..(expected + jitterRange))
        }
    }

    @Test
    fun `computeBackoffWithJitter caps at MAX_RETRY_PERIOD_MS`() {
        val highAttempt = 50
        for (i in 0 until 100) {
            val backoff = RetryPolicy.computeBackoffWithJitter(highAttempt)
            val jitterRange = (AppConfig.MAX_RETRY_PERIOD_MS * 0.10).toLong()
            assertTrue(backoff in (AppConfig.MAX_RETRY_PERIOD_MS - jitterRange)..(AppConfig.MAX_RETRY_PERIOD_MS + jitterRange))
        }
    }

    @Test
    fun `computeBackoffWithJitter is always positive`() {
        for (attempt in 0..50) {
            for (i in 0 until 10) {
                val backoff = RetryPolicy.computeBackoffWithJitter(attempt)
                assertTrue(backoff > 0, "Backoff for attempt $attempt is $backoff, expected > 0")
            }
        }
    }

    @Test
    fun `computeNextRetryTime returns now plus backoff`() {
        val now = 1_000_000L
        val result = RetryPolicy.computeNextRetryTime(0, now)
        val expectedBase = now + AppConfig.BASE_BACKOFF_MS
        val jitterRange = (AppConfig.BASE_BACKOFF_MS * 0.10).toLong()
        assertTrue(result in (expectedBase - jitterRange)..(expectedBase + jitterRange))
    }

    @Test
    fun `computeNextRetryTime with high attempt caps at max`() {
        val now = 1_000_000L
        val result = RetryPolicy.computeNextRetryTime(50, now)
        val expectedBase = now + AppConfig.MAX_RETRY_PERIOD_MS
        val jitterRange = (AppConfig.MAX_RETRY_PERIOD_MS * 0.10).toLong()
        assertTrue(result in (expectedBase - jitterRange)..(expectedBase + jitterRange))
    }

    @Test
    fun `isExpiredByFirstAttempt returns false when firstAttemptTime is null`() {
        val now = System.currentTimeMillis()
        assertFalse(RetryPolicy.isExpiredByFirstAttempt(null, now))
    }

    @Test
    fun `isExpiredByFirstAttempt returns false when within 2 weeks of first attempt`() {
        val now = System.currentTimeMillis()
        val firstAttempt = now - (13L * 24 * 60 * 60 * 1_000)
        assertFalse(RetryPolicy.isExpiredByFirstAttempt(firstAttempt, now))
    }

    @Test
    fun `isExpiredByFirstAttempt returns true when beyond 2 weeks of first attempt`() {
        val now = System.currentTimeMillis()
        val firstAttempt = now - (15L * 24 * 60 * 60 * 1_000)
        assertTrue(RetryPolicy.isExpiredByFirstAttempt(firstAttempt, now))
    }

    @Test
    fun `isExpiredByFirstAttempt returns false at 1 second before expiry from first attempt`() {
        val now = System.currentTimeMillis()
        val firstAttempt = now - (AppConfig.MAX_RETRY_PERIOD_MS - 1)
        assertFalse(RetryPolicy.isExpiredByFirstAttempt(firstAttempt, now))
    }

    @Test
    fun `isExpiredByFirstAttempt returns true at exactly expiry boundary from first attempt`() {
        val now = System.currentTimeMillis()
        val firstAttempt = now - AppConfig.MAX_RETRY_PERIOD_MS
        assertTrue(RetryPolicy.isExpiredByFirstAttempt(firstAttempt, now))
    }

    @Test
    fun `retryWindow sets the full retry payload`() {
        val now = 1_000_000L
        val firstAttempt = now - 60_000L
        val window = RetryPolicy.retryWindow("smtp down", 3, firstAttempt, now)

        assertEquals("smtp down", window.statusDetails)
        assertEquals(3, window.retryCount)
        assertEquals(firstAttempt, window.timeFirstSendAttempt)
        assertEquals(now, window.timeLastSendAttempt)
        val nextAttempt = window.timeNextSendAttempt
        assertNotNull(nextAttempt)
        assertTrue(nextAttempt > now)
    }

    @Test
    fun `retryWindow next attempt is now plus backoff with jitter`() {
        val now = 1_000_000L
        for (i in 0 until 100) {
            val window = RetryPolicy.retryWindow(null, 2, null, now)

            assertNull(window.statusDetails)
            assertEquals(2, window.retryCount)
            assertNull(window.timeFirstSendAttempt)
            assertEquals(now, window.timeLastSendAttempt)
            val expected = AppConfig.BASE_BACKOFF_MS * 4 // 2^2
            val jitterRange = (expected * 0.10).toLong()
            assertTrue(
                window.timeNextSendAttempt in (now + expected - jitterRange)..(now + expected + jitterRange),
            )
        }
    }

    @Test
    fun `failedWindow has no next attempt and leaves first attempt unset`() {
        val now = 1_000_000L
        val window = RetryPolicy.failedWindow("period exceeded", 6, now)

        assertEquals("period exceeded", window.statusDetails)
        assertEquals(6, window.retryCount)
        assertNull(window.timeFirstSendAttempt)
        assertEquals(now, window.timeLastSendAttempt)
        assertNull(window.timeNextSendAttempt)
    }

    @Test
    fun `freshForwardWindow resets the count and is due at the base backoff without jitter`() {
        val now = 1_000_000L
        val window = RetryPolicy.freshForwardWindow(now)

        assertNull(window.statusDetails)
        assertEquals(0, window.retryCount)
        assertEquals(now, window.timeFirstSendAttempt)
        assertEquals(now, window.timeLastSendAttempt)
        assertEquals(now + AppConfig.BASE_BACKOFF_MS, window.timeNextSendAttempt)
    }

    @Test
    fun `freshPendingWindow resets the count and starts the first-attempt clock only`() {
        val now = 1_000_000L
        val window = RetryPolicy.freshPendingWindow(now)

        assertNull(window.statusDetails)
        assertEquals(0, window.retryCount)
        assertEquals(now, window.timeFirstSendAttempt)
        assertNull(window.timeLastSendAttempt)
        assertNull(window.timeNextSendAttempt)
    }
}
