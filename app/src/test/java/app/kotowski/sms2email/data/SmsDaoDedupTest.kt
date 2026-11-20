package app.kotowski.sms2email.data.database

import app.kotowski.sms2email.data.database.DedupUtil
import app.kotowski.sms2email.testing.TestPhone
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SmsDaoDedupTest {
    // The level window (DedupLevel.BALANCED): the bucket is a per-level parameter.
    private val window = 10_000L

    @Test
    fun `test same SMS data produces same dedupKey for import dedup`() {
        val key1 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "Hello World", window)
        val key2 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "Hello World", window)

        assertEquals(key1, key2, "Same SMS data must produce identical dedupKey for dedup to work")
    }

    @Test
    fun `test reimport scenario - same message produces same dedupKey`() {
        val importedKey = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "Meeting at 3pm", window)
        val reimportedKey = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "Meeting at 3pm", window)

        assertEquals(importedKey, reimportedKey, "Re-importing the same message must produce the same dedupKey")
    }

    @Test
    fun `test different messages with same sender produce different dedupKeys`() {
        val key1 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "First message", window)
        val key2 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "Second message", window)

        assertNotEquals(key1, key2)
    }

    @Test
    fun `test case insensitivity prevents false duplicates`() {
        val key1 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "HELLO", window)
        val key2 = DedupUtil.computeDedupKey(TestPhone.SELF, 1609459200000L, "hello", window)

        assertEquals(key1, key2, "Case differences should not create false duplicates")
    }

    @Test
    fun `test incoming SMS and import of same message produce same dedupKey`() {
        // The live and import paths store different timestamps (receive time vs provider
        // DATE) for the same message, ms/s apart; the 10s bucket (Balanced) bridges them.
        val liveTimestamp = 1609459200000L
        val importTimestamp = liveTimestamp + 4_500
        val incomingKey = DedupUtil.computeDedupKey(TestPhone.SELF, liveTimestamp, "Same message", window)
        val importKey = DedupUtil.computeDedupKey(TestPhone.SELF, importTimestamp, "Same message", window)

        assertEquals(incomingKey, importKey, "Incoming and import paths of the same message should share dedupKey")
    }

    @Test
    fun `test live and import timestamps diverging beyond the 10s bucket produce different dedupKeys`() {
        val liveTimestamp = 1609459200000L
        val importTimestamp = liveTimestamp + 120_000
        val key1 = DedupUtil.computeDedupKey(TestPhone.SELF, liveTimestamp, "Same message", window)
        val key2 = DedupUtil.computeDedupKey(TestPhone.SELF, importTimestamp, "Same message", window)

        assertNotEquals(key1, key2, "Divergence beyond the 10s bucket is a known limitation of the key")
    }
}
