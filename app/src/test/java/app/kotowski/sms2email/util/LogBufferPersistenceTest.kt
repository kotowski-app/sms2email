package app.kotowski.sms2email.util

import app.kotowski.sms2email.AppConfig
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogBufferPersistenceTest {
    // Runs the writer task inline so the file effects are deterministic without waiting.
    private val directExecutor = Executor { it.run() }

    private fun tempLogFile(): File = File.createTempFile("logbuffer", ".log").apply { delete() }

    @Test
    fun `add appends the entry line to the store`() {
        val file = tempLogFile()
        LogBuffer.clear()
        LogBuffer.init(LogFileStore(file), directExecutor)
        LogBuffer.addInfo("PersistTag", "persisted message")
        assertTrue(
            file.readLines().any { it.contains("PersistTag") && it.contains("persisted message") },
            "the store must contain the entry, got: ${file.readLines()}",
        )
    }

    @Test
    fun `init reloads the file tail into the buffer`() {
        val file = tempLogFile()
        LogFileStore(file).append("[2026-10-04T10:00:00.000] I OldTag: old message")
        LogBuffer.clear()
        LogBuffer.init(LogFileStore(file), directExecutor)
        assertEquals(1, LogBuffer.entries.size)
        val entry = LogBuffer.entries.last()
        assertEquals("2026-10-04T10:00:00.000", entry.timestamp)
        assertEquals("I", entry.level)
        assertEquals("OldTag", entry.tag)
        assertEquals("old message", entry.message)
    }

    @Test
    fun `init skips lines that do not match the entry format`() {
        val file = tempLogFile()
        LogFileStore(file).append("torn line without the entry format")
        LogFileStore(file).append("[2026-10-04T10:00:00.000] E NewTag: new message")
        LogBuffer.clear()
        LogBuffer.init(LogFileStore(file), directExecutor)
        assertEquals(1, LogBuffer.entries.size)
        assertEquals("NewTag", LogBuffer.entries.last().tag)
    }

    @Test
    fun `clear empties the buffer and the file`() {
        val file = tempLogFile()
        LogBuffer.clear()
        LogBuffer.init(LogFileStore(file), directExecutor)
        LogBuffer.addInfo("ClearTag", "gone soon")
        assertTrue(file.readLines().isNotEmpty())
        LogBuffer.clear()
        assertTrue(LogBuffer.entries.isEmpty())
        assertTrue(!file.exists(), "clear must delete the log file")
    }

    @Test
    fun `clear without an attached store does not throw`() {
        LogBuffer.clear()
        LogBuffer.clear()
        LogBuffer.addInfo("NoStoreTag", "still buffered")
        assertTrue(LogBuffer.entries.last().message == "still buffered")
    }

    @Test
    fun `add truncates messages longer than the cap`() {
        val file = tempLogFile()
        LogBuffer.clear()
        LogBuffer.init(LogFileStore(file), directExecutor)
        LogBuffer.addInfo("TruncTag", "a".repeat(AppConfig.LOG_MAX_MESSAGE_LENGTH + 500))
        val entry = LogBuffer.entries.last()
        assertEquals(AppConfig.LOG_MAX_MESSAGE_LENGTH + 1, entry.message.length)
        assertTrue(entry.message.endsWith("…"))
    }

    @Test
    fun `exportText returns the full file including history beyond the buffer`() {
        val file = tempLogFile()
        LogBuffer.clear()
        LogBuffer.init(LogFileStore(file), directExecutor)
        LogFileStore(file).append("[2026-10-04T09:00:00.000] I OldTag: older than the buffer")
        LogBuffer.addInfo("ExportTag", "newest entry")
        val text = LogBuffer.exportText()
        assertTrue(text.contains("OldTag"), "the file history must be exported: $text")
        assertTrue(text.contains("newest entry"), "the newest entry must be exported: $text")
    }

    @Test
    fun `lineToEntry parses a full entry line`() {
        val entry = LogBuffer.lineToEntry("[2026-10-04T10:00:00.000] E SmsTag: failed: [weird] text")
        assertEquals("2026-10-04T10:00:00.000", entry?.timestamp)
        assertEquals("E", entry?.level)
        assertEquals("SmsTag", entry?.tag)
        assertEquals("failed: [weird] text", entry?.message)
    }

    @Test
    fun `lineToEntry returns null for a malformed line`() {
        assertNull(LogBuffer.lineToEntry("garbage without the entry format"))
    }

    @Test
    fun `lineToEntry returns null for an unsupported V level line`() {
        assertNull(LogBuffer.lineToEntry("[2026-10-04T10:00:00.000] V OldTag: verbose message"))
    }

    @Test
    fun `file appends do not run on the calling thread`() {
        val file = tempLogFile()
        LogBuffer.clear()
        val writer =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "test-log-writer").apply { isDaemon = true }
            }
        val writerThread = AtomicReference<Thread>()
        LogBuffer.init(
            LogFileStore(file),
            Executor { task ->
                writer.execute {
                    writerThread.set(Thread.currentThread())
                    task.run()
                }
            },
        )
        val caller = Thread.currentThread()
        LogBuffer.addInfo("AsyncTag", "async message")
        writer.shutdown()
        assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(writerThread.get() != caller, "the append must not run on the calling thread")
        assertTrue(file.readLines().any { it.contains("AsyncTag") })
    }

    @Test
    fun `a store failure on the writer thread does not escape the task`() {
        // A directory exists, so loadTail throws; init still wires the broken store behind
        // the buffer, which is exactly the state a failed startup init leaves behind.
        val dir =
            File.createTempFile("logdir", "").apply {
                delete()
                mkdirs()
            }
        val writer =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "test-log-writer").apply { isDaemon = true }
            }
        val uncaught = AtomicReference<Throwable>()
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, t -> uncaught.set(t) }
        try {
            LogBuffer.clear()
            try {
                LogBuffer.init(LogFileStore(dir), writer)
            } catch (_: Exception) {
                // expected: the directory is unreadable as a file
            }
            LogBuffer.addInfo("SurvivorTag", "logged with a broken store")
            writer.shutdown()
            assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS), "the writer did not stop in time")
            assertNull(uncaught.get(), "the failed append must not escape the writer thread")
            assertTrue(LogBuffer.entries.any { it.tag == "SurvivorTag" })
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        }
    }

    @Test
    fun `add escapes newlines so the entry stays a single file line`() {
        val file = tempLogFile()
        LogBuffer.clear()
        LogBuffer.init(LogFileStore(file), directExecutor)
        LogBuffer.addInfo("NewlineTag", "line one\nline two\r\nline three")
        assertEquals("line one\\nline two\\nline three", LogBuffer.entries.last().message)
        val lines = file.readLines()
        assertEquals(1, lines.size, "the entry must stay one file line: $lines")
        assertEquals("line one\\nline two\\nline three", LogBuffer.lineToEntry(lines.single())?.message)
    }

    @Test
    fun `add escapes unicode line terminators so the entry survives the reload`() {
        val file = tempLogFile()
        LogBuffer.clear()
        LogBuffer.init(LogFileStore(file), directExecutor)
        LogBuffer.addInfo("UnicodeTag", "a\u0085b\u2028c\u2029d")
        assertEquals("a\\nb\\nc\\nd", LogBuffer.entries.last().message)
        val lines = file.readLines()
        assertEquals(1, lines.size, "the entry must stay one file line: $lines")
        assertEquals("a\\nb\\nc\\nd", LogBuffer.lineToEntry(lines.single())?.message)
    }

    @Test
    fun `enableFileStore attaches a fresh store and loads its tail`() {
        val oldFile = tempLogFile()
        LogBuffer.clear()
        LogBuffer.init(LogFileStore(oldFile), directExecutor)
        LogBuffer.addInfo("OldTag", "old history")

        val newFile = tempLogFile()
        LogFileStore(newFile).append("[2026-10-04T10:00:00.000] I FileTag: from the file")
        LogBuffer.enableFileStore(newFile, AppConfig.LOG_FILE_MAX_BYTES)

        assertTrue(LogBuffer.entries.any { it.tag == "FileTag" })
        LogBuffer.addInfo("NewTag", "new entry")
        assertTrue(newFile.readText().contains("new entry"))
    }

    @Test
    fun `enableFileStore backfills the whole buffer into the fresh file`() {
        // Entries logged while the store was off must reach the fresh file on enable, so
        // the export is never empty while the viewer shows entries.
        val file = tempLogFile()
        LogBuffer.clear()
        LogBuffer.init(LogFileStore(file), directExecutor)
        LogBuffer.addInfo("BeforeTag", "logged while attached")
        LogBuffer.disableFileStore()
        LogBuffer.addInfo("AfterTag", "logged while off")

        val freshFile = tempLogFile()
        LogBuffer.enableFileStore(freshFile, AppConfig.LOG_FILE_MAX_BYTES)

        val lines = freshFile.readLines()
        assertEquals(
            LogBuffer.entries.map { it.toString() },
            lines,
            "the fresh file must hold the whole buffer in chronological order",
        )
        assertTrue(LogBuffer.exportText().isNotEmpty(), "the export must not be empty while entries are visible")

        LogBuffer.addInfo("PostTag", "appended after the backfill")
        val after = freshFile.readLines()
        assertEquals(lines.size + 1, after.size)
        assertTrue(after.last().contains("PostTag"), "the next append must land after the backfill: $after")
    }

    @Test
    fun `off-on-off-on keeps the buffer and the second enable backfills it again`() {
        val file1 = tempLogFile()
        LogBuffer.clear()
        LogBuffer.init(LogFileStore(file1), directExecutor)
        LogBuffer.addInfo("CycleTag", "first on period")
        LogBuffer.disableFileStore()
        LogBuffer.addInfo("CycleTag", "first off period")

        val file2 = tempLogFile()
        LogBuffer.enableFileStore(file2, AppConfig.LOG_FILE_MAX_BYTES)
        LogBuffer.disableFileStore()
        LogBuffer.addInfo("CycleTag", "second off period")

        val file3 = tempLogFile()
        LogBuffer.enableFileStore(file3, AppConfig.LOG_FILE_MAX_BYTES)

        assertEquals(3, LogBuffer.entries.size)
        assertEquals(
            LogBuffer.entries.map { it.toString() },
            file3.readLines(),
            "the file after the second enable must hold the buffer content",
        )
        assertTrue(!file2.exists(), "the first enable's file must stay deleted after its disable")
    }

    @Test
    fun `enableFileStore backfill respects the file cap`() {
        // 80 max-length messages (~2 KB each) put the buffer well past the 64 KB cap:
        // the backfill must cut to the tail, keeping the newest entries whole.
        val file = tempLogFile()
        LogBuffer.clear()
        LogBuffer.init(LogFileStore(file, 64 * 1024), directExecutor)
        repeat(80) { LogBuffer.addInfo("CapTag", "x".repeat(AppConfig.LOG_MAX_MESSAGE_LENGTH)) }
        LogBuffer.disableFileStore()

        val freshFile = tempLogFile()
        LogBuffer.enableFileStore(freshFile, 64 * 1024)

        val lines = freshFile.readLines()
        assertTrue(lines.isNotEmpty(), "the backfill must leave entries in the file")
        assertTrue(lines.all { it.startsWith("[") }, "only whole entry lines may survive the cut: ${lines.take(2)}")
        assertEquals(LogBuffer.entries.last().toString(), lines.last(), "the newest entry must survive the cut")
        assertTrue(
            freshFile.length() <= 64 * 1024 + AppConfig.LOG_MAX_LINE_BYTES,
            "the file must stay within the cap plus one line: ${freshFile.length()}",
        )
    }

    @Test
    fun `disableFileStore deletes the file and stops appending while keeping the buffer`() {
        val file = tempLogFile()
        LogBuffer.clear()
        LogBuffer.init(LogFileStore(file), directExecutor)
        LogBuffer.addInfo("BeforeTag", "kept in memory")
        LogBuffer.disableFileStore()

        assertTrue(!file.exists(), "the file must be deleted")
        assertEquals("kept in memory", LogBuffer.entries.last().message)

        LogBuffer.addInfo("AfterTag", "no file any more")
        assertTrue(!file.exists(), "no append may respawn the file")
        assertEquals("no file any more", LogBuffer.entries.last().message)
    }

    @Test
    fun `disableFileStore without an attached store is a no-op`() {
        LogBuffer.clear()
        LogBuffer.disableFileStore()
        LogBuffer.addInfo("Tag", "buffer only")
        assertEquals("buffer only", LogBuffer.entries.last().message)
    }

    @Test
    fun `a pending disable does not detach a store a newer enable attached`() {
        // The disable runs on the writer thread: if a re-enable lands before it, the
        // identity check must keep the new store attached.
        val writer = ManualExecutor()
        val fileA = tempLogFile()
        val fileB = tempLogFile()
        val fileC = tempLogFile()
        LogBuffer.clear()
        LogBuffer.init(LogFileStore(fileA), writer)
        LogBuffer.enableFileStore(fileB, AppConfig.LOG_FILE_MAX_BYTES)
        LogBuffer.disableFileStore()
        LogBuffer.enableFileStore(fileC, AppConfig.LOG_FILE_MAX_BYTES)
        writer.drain()

        assertTrue(!fileB.exists(), "the disable must delete its own file")
        LogBuffer.addInfo("AfterTag", "still attached")
        writer.drain()
        assertTrue(fileC.readText().contains("still attached"), "the new store must survive")
    }

    @Test
    fun `updateFileCap cuts an over-cap file to the new cap on the writer thread`() {
        val file = tempLogFile()
        LogBuffer.clear()
        LogBuffer.init(LogFileStore(file), directExecutor)
        repeat(40) { LogBuffer.addInfo("BigTag", "x".repeat(100)) }
        val bigLength = file.length()
        LogBuffer.updateFileCap(1024)

        assertTrue(file.length() < bigLength, "the file must be cut to the new cap")
        assertTrue(file.length() <= 1024 + 200, "the file must stay near the new cap: ${file.length()}")
        // The next append must respect the new cap, not the old one.
        LogBuffer.addInfo("AfterTag", "y".repeat(100))
        val lines = file.readLines()
        assertTrue(lines.all { it.startsWith("[") }, "whole entry lines only: ${lines.take(3)}")
    }
}

// A writer executor that records the tasks instead of running them: the tests queue the
// disable and control when it lands against a store a newer enable attached.
private class ManualExecutor : Executor {
    private val tasks = mutableListOf<Runnable>()

    override fun execute(command: Runnable) {
        tasks.add(command)
    }

    fun drain() {
        while (tasks.isNotEmpty()) {
            tasks.removeAt(0).run()
        }
    }
}
