package app.kotowski.sms2email.data.database

import app.kotowski.sms2email.testing.TestPhone
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DedupUtilTest {
    // The level windows (DedupLevel): the bucket is a per-level parameter, so the tests
    // pin per-level values.
    private val aggressiveWindow = 60_000L
    private val balancedWindow = 10_000L
    private val minimalWindow = 3_000L

    @Test
    fun `test computeDedupKey returns 64 character hex string`() {
        val key = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "Hello", balancedWindow)

        assertNotNull(key)
        assertEquals(64, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `test same inputs produce same dedupKey`() {
        val key1 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "Hello", balancedWindow)
        val key2 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "Hello", balancedWindow)

        assertEquals(key1, key2)
    }

    @Test
    fun `test different messages produce different dedupKeys`() {
        val key1 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "Hello", balancedWindow)
        val key2 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "World", balancedWindow)

        assertNotEquals(key1, key2)
    }

    @Test
    fun `test different phone numbers produce different dedupKeys`() {
        val key1 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "Hello", balancedWindow)
        val key2 = DedupUtil.computeDedupKey(TestPhone.INVALID, 1609459200000L, "Hello", balancedWindow)

        assertNotEquals(key1, key2)
    }

    @Test
    fun `test same message from different SIMs produces same dedupKey`() {
        val incomingKey = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "Hello", balancedWindow)
        val importKey = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "Hello", balancedWindow)

        assertEquals(incomingKey, importKey, "Same message should produce same dedupKey regardless of SIM")
    }

    @Test
    fun `test different timestamps produce different dedupKeys`() {
        val key1 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "Hello", balancedWindow)
        val key2 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459300000L, "Hello", balancedWindow)

        assertNotEquals(key1, key2)
    }

    @Test
    fun `test timestamps 5ms apart produce same dedupKey`() {
        val key1 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "Hello", balancedWindow)
        val key2 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200005L, "Hello", balancedWindow)

        assertEquals(key1, key2, "Redelivered broadcast milliseconds apart must share dedupKey")
    }

    @Test
    fun `test timestamps within same 10s bucket produce same dedupKey`() {
        val key1 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200001L, "Hello", balancedWindow)
        val key2 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459209999L, "Hello", balancedWindow)

        assertEquals(key1, key2, "Timestamps in the same 10s bucket must share dedupKey")
    }

    @Test
    fun `test timestamps crossing 10s bucket boundary produce different dedupKeys`() {
        val key1 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459209999L, "Hello", balancedWindow)
        val key2 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459210000L, "Hello", balancedWindow)

        assertNotEquals(key1, key2)
    }

    @Test
    fun `test the level window changes the bucket and thus the key`() {
        val aggressive = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "Hello", aggressiveWindow)
        val balanced = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "Hello", balancedWindow)
        val minimal = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "Hello", minimalWindow)

        assertNotEquals(aggressive, balanced)
        assertNotEquals(balanced, minimal)
        assertNotEquals(aggressive, minimal)
    }

    @Test
    fun `test a 3s window dedupes ms-apart redeliveries but not a 3500ms-apart repeat`() {
        val near1 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "Hello", minimalWindow)
        val near2 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200500L, "Hello", minimalWindow)
        val far = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459203500L, "Hello", minimalWindow)

        assertEquals(near1, near2, "A 500ms-apart redelivery must share the 3s bucket")
        assertNotEquals(near1, far, "A 3.5s-apart repeat must not share the 3s bucket")
    }

    @Test
    fun `test a 60s window dedupes a 20s-apart repeat`() {
        val key1 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "Hello", aggressiveWindow)
        val key2 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459220000L, "Hello", aggressiveWindow)

        assertEquals(key1, key2, "A 20s-apart repeat must share the 60s bucket")
    }

    @Test
    fun `test a zero window (off) dedupes only an identical timestamp`() {
        // OFF is a zero window: the key uses the exact timestamp, not a time bucket. An
        // identical timestamp shares the key; even 1ms apart it must not (no bucket to
        // bridge the gap).
        val exact1 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "Hello", 0L)
        val exact2 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "Hello", 0L)
        val msApart = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200001L, "Hello", 0L)

        assertEquals(exact1, exact2, "An identical timestamp must share the exact (zero-window) key")
        assertNotEquals(exact1, msApart, "A 1ms-apart timestamp must not share the exact (zero-window) key")
    }

    @Test
    fun `test display address and raw address with different whitespace produce same dedupKey`() {
        // Live path stores displayOriginatingAddress ("Test App"), import path stores the
        // raw provider address ("TestApp") for the same message
        val liveKey = DedupUtil.computeDedupKey("Test App", 1609459200834L, "OTP is 176414", balancedWindow)
        val importKey = DedupUtil.computeDedupKey("TestApp", 1609459200629L, "OTP is 176414", balancedWindow)

        assertEquals(liveKey, importKey, "Whitespace differences in sender address must not break dedup")
    }

    @Test
    fun `test spaced phone number and compact phone number produce same dedupKey`() {
        val key1 = DedupUtil.computeDedupKey("+1 555 0142", 1609459200000L, "Hello", balancedWindow)
        val key2 = DedupUtil.computeDedupKey(TestPhone.FRIEND, 1609459200000L, "Hello", balancedWindow)

        assertEquals(key1, key2)
    }

    @Test
    fun `test case-insensitive message body`() {
        val key1 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "Hello", balancedWindow)
        val key2 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "hello", balancedWindow)

        assertEquals(key1, key2)
    }

    @Test
    fun `test empty message body produces valid dedupKey`() {
        val key = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "", balancedWindow)

        assertNotNull(key)
        assertEquals(64, key.length)
    }
}
