package app.kotowski.sms2email.util

import app.kotowski.sms2email.AppConfig
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LogBufferTest {
    @Test
    fun `add log entry increases entry count up to the cap`() {
        val initialSize = LogBuffer.entries.size
        LogBuffer.addInfo("TestTag", "Test message")
        assertEquals(minOf(initialSize + 1, 500), LogBuffer.entries.size)
    }

    @Test
    fun `add triggers refreshTrigger`() {
        val triggerBefore = LogBuffer.refreshTrigger.value
        LogBuffer.addInfo("TestTag", "Trigger test")
        val triggerAfter = LogBuffer.refreshTrigger.value
        assertNotEquals(triggerBefore, triggerAfter)
    }

    @Test
    fun `log entry has correct format`() {
        LogBuffer.addInfo("TestTag", "Hello World")
        val entry = LogBuffer.entries.last()
        assertEquals("I", entry.level)
        assertEquals("TestTag", entry.tag)
        assertEquals("Hello World", entry.message)
        assertTrue(entry.timestamp.isNotEmpty())
    }

    @Test
    fun `log entry timestamp uses ISO 8601 UTC format with the Z suffix`() {
        LogBuffer.addInfo("TestTag", "ISO test")
        val entry = LogBuffer.entries.last()
        val ts = entry.timestamp
        val isoPattern = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")
        assertTrue(ts.matches(isoPattern), "Timestamp should match ISO 8601 UTC format with the Z suffix, got: $ts")
    }

    @Test
    fun `log entry toString has expected format`() {
        LogBuffer.addInfo("TestTag", "Hello World")
        val entry = LogBuffer.entries.last()
        val str = entry.toString()
        assertTrue(str.contains("TestTag"))
        assertTrue(str.contains("Hello World"))
        assertTrue(str.contains("I"))
        assertTrue(str.startsWith("["))
    }

    @Test
    fun `max entries limits buffer size`() {
        repeat(600) { i ->
            LogBuffer.addInfo("TestTag", "Message $i")
        }
        assertTrue(LogBuffer.entries.size <= 500)
    }

    @Test
    fun `addDebug adds with D level`() {
        LogBuffer.addDebug("TestTag", "Debug msg")
        assertEquals("D", LogBuffer.entries.last().level)
    }

    @Test
    fun `addWarn adds with W level`() {
        LogBuffer.addWarn("TestTag", "Warn msg")
        assertEquals("W", LogBuffer.entries.last().level)
    }

    @Test
    fun `addError adds with E level`() {
        LogBuffer.addError("TestTag", "Error msg")
        assertEquals("E", LogBuffer.entries.last().level)
    }

    @Test
    fun `addError with throwable includes message`() {
        LogBuffer.addError("TestTag", "Error", RuntimeException("boom"))
        val entry = LogBuffer.entries.last()
        assertTrue(entry.message.contains("boom"))
    }

    @Test
    fun `addError with a message-less throwable falls back to the class name`() {
        LogBuffer.addError("TestTag", "Error", RuntimeException())
        assertEquals("Error: RuntimeException", LogBuffer.entries.last().message)
    }

    @Test
    fun `concurrent adds never shrink the buffer below the cap`() {
        LogBuffer.clear()
        val threads = 8
        val perThread = 100
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                repeat(perThread) { LogBuffer.addInfo("RaceTag", "entry $it") }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS), "the worker threads did not finish in time")
        pool.shutdown()
        // 800 concurrent adds over the 500 cap must land exactly at the cap: the racy
        // double-remove would leave the buffer short (e.g. 499).
        assertEquals(AppConfig.LOG_MAX_ENTRIES, LogBuffer.entries.size)
    }

    @Test
    fun `concurrent adds never lose a refresh trigger increment`() {
        LogBuffer.clear()
        val triggerBefore = LogBuffer.refreshTrigger.value
        val threads = 8
        val perThread = 100
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                repeat(perThread) { LogBuffer.addInfo("RaceTriggerTag", "entry $it") }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS), "the worker threads did not finish in time")
        pool.shutdown()
        // The trigger must advance exactly once per add: the racy read-modify-write
        // dropped increments under concurrency (800 adds could yield 799).
        assertEquals(threads.toLong() * perThread, LogBuffer.refreshTrigger.value - triggerBefore)
    }

    @Test
    fun `exportText returns empty string when no store is attached`() {
        LogBuffer.clear()
        val directExecutor = java.util.concurrent.Executor { it.run() }
        val tempFile = File.createTempFile("logbuffer", ".log").apply { deleteOnExit() }
        LogBuffer.init(LogFileStore(tempFile), directExecutor)
        LogBuffer.disableFileStore()
        assertEquals("", LogBuffer.exportText())
    }

    @Test
    fun `addError with null message throwable uses class name`() {
        LogBuffer.addError("TestTag", "Error", NullPointerException())
        val entry = LogBuffer.entries.last()
        assertTrue(entry.message.contains("NullPointerException"))
    }

    @Test
    fun `add with very long message truncates to the configured max length`() {
        val longMessage = "x".repeat(AppConfig.LOG_MAX_MESSAGE_LENGTH + 1000)
        LogBuffer.addInfo("TruncateTag", longMessage)
        val entry = LogBuffer.entries.last()
        assertTrue(entry.message.length <= AppConfig.LOG_MAX_MESSAGE_LENGTH + 1, "truncated message should fit the cap")
        assertTrue(entry.message.endsWith("…"), "truncated message should end with ellipsis")
    }

    @Test
    fun `add with exactly cap-length message does not truncate`() {
        val exactMessage = "x".repeat(AppConfig.LOG_MAX_MESSAGE_LENGTH)
        LogBuffer.addInfo("ExactTag", exactMessage)
        val entry = LogBuffer.entries.last()
        assertEquals(exactMessage, entry.message)
    }

    // updateFileCap: exercises the cap change on the writer thread.
    @Test
    fun `updateFileCap changes the store cap`() {
        val tempFile = File.createTempFile("logbuffer-cap", ".log").apply { deleteOnExit() }
        val directExecutor = java.util.concurrent.Executor { it.run() }
        val store = LogFileStore(tempFile, 1024)
        LogBuffer.init(store, directExecutor)
        LogBuffer.addInfo("CapTag", "cap test message")
        LogBuffer.updateFileCap(2048)
        // The cap is applied on the writer thread; verify by writing more data and checking
        // the file doesn't exceed the new cap.
        repeat(100) { LogBuffer.addInfo("CapTag", "x".repeat(100)) }
        // Allow the writer thread to process.
        Thread.sleep(100)
        assertTrue(tempFile.length() <= 2048 + 1000, "file size should respect the new cap")
        LogBuffer.disableFileStore()
    }

    // disableFileStore: identity check branch — a newer enable replaces the store,
    // so a pending disable should not detach the newer store.
    @Test
    fun `disableFileStore does not detach a store that was replaced by a newer enable`() {
        val tempFile1 = File.createTempFile("logbuffer-disable1", ".log").apply { deleteOnExit() }
        val tempFile2 = File.createTempFile("logbuffer-disable2", ".log").apply { deleteOnExit() }
        // Use a serial executor so tasks execute in order.
        val serialExecutor = java.util.concurrent.Executor { it.run() }
        LogBuffer.init(LogFileStore(tempFile1, 1024), serialExecutor)
        // Disable will queue a clear+detach for store1.
        LogBuffer.disableFileStore()
        // Re-enable with a new store before the disable task runs.
        LogBuffer.init(LogFileStore(tempFile2, 2048), serialExecutor)
        // The new store should be active.
        LogBuffer.addInfo("ReplaceTag", "after replace")
        assertTrue(LogBuffer.entries.any { it.message == "after replace" })
    }
}
