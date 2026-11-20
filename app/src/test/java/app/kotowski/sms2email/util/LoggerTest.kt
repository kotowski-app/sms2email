package app.kotowski.sms2email.util

import app.kotowski.sms2email.LogLevel
import junit.framework.TestCase.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LoggerTest {
    @Before
    fun setUp() {
        // The threshold is process-global: reset so it cannot leak into other tests.
        Logger.setLevel(LogLevel.DEBUG)
    }

    @Test
    fun `e writes to LogBuffer with E level`() {
        Logger.e("TestTag", "error message")
        val entry = LogBuffer.entries.last()
        assertEquals("E", entry.level)
        assertEquals("error message", entry.message)
    }

    @Test
    fun `d with custom tag writes correct tag`() {
        Logger.d("CustomTag", "custom tag message")
        val entry = LogBuffer.entries.last()
        assertEquals("CustomTag", entry.tag)
    }

    @Test
    fun `i with custom tag writes correct tag`() {
        Logger.i("CustomTag", "custom tag message")
        val entry = LogBuffer.entries.last()
        assertEquals("CustomTag", entry.tag)
    }

    @Test
    fun `w with custom tag writes correct tag`() {
        Logger.w("CustomTag", "custom tag message")
        val entry = LogBuffer.entries.last()
        assertEquals("CustomTag", entry.tag)
    }

    @Test
    fun `e with custom tag writes correct tag`() {
        Logger.e("CustomTag", "custom tag message")
        val entry = LogBuffer.entries.last()
        assertEquals("CustomTag", entry.tag)
    }

    @Test
    fun `e with throwable includes exception message`() {
        val testException = RuntimeException("test exception message")
        Logger.e("TestTag", "error with exception", testException)
        val entry = LogBuffer.entries.last()
        assertEquals("E", entry.level)
        assertEquals(true, entry.message.contains("test exception message"))
    }

    @Test
    fun `e with throwable does not double append exception message`() {
        val testException = RuntimeException("boom")
        Logger.e("TestTag", "error occurred", testException)
        val entry = LogBuffer.entries.last()
        assertEquals("E", entry.level)
        assertEquals("TestTag", entry.tag)
        val parts = entry.message.split(": boom")
        assertEquals(2, parts.size)
        assertEquals("error occurred", parts[0])
    }

    @Test
    fun `setLevel records a meta line when the level changes`() {
        Logger.setLevel(LogLevel.INFO)
        val entry = LogBuffer.entries.last()
        assertEquals("I", entry.level)
        assertEquals("Logger", entry.tag)
        assertEquals("log level set to INFO", entry.message)
    }

    @Test
    fun `setLevel records nothing while the level stays the same`() {
        Logger.setLevel(LogLevel.DEBUG)
        val size = LogBuffer.entries.size
        Logger.setLevel(LogLevel.DEBUG)
        assertEquals(size, LogBuffer.entries.size)
    }

    @Test
    fun `the setLevel meta line respects the threshold too`() {
        Logger.setLevel(LogLevel.OFF)
        val size = LogBuffer.entries.size
        Logger.setLevel(LogLevel.OFF)
        assertEquals(size, LogBuffer.entries.size)
    }

    @Test
    fun `setEnabled(true) logs to LogBuffer`() {
        Logger.setEnabled(true)
        val entry = LogBuffer.entries.last()
        assertEquals("I", entry.level)
        assertEquals("Logging enabled", entry.message)
    }

    @Test
    fun `setEnabled(false) logs to LogBuffer`() {
        Logger.setEnabled(true)
        Logger.setEnabled(false)
        val entry = LogBuffer.entries.last()
        assertEquals("W", entry.level)
        assertEquals("Logging disabled", entry.message)
    }

    @Test
    fun `log entries have ISO 8601 UTC timestamps with the Z suffix`() {
        Logger.i("TestTag", "timestamp test")
        val entry = LogBuffer.entries.last()
        val isoPattern = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")
        assertEquals(true, entry.timestamp.matches(isoPattern))
    }

    @Test
    fun `the product default records only errors`() {
        Logger.setLevel(LogLevel.DEFAULT)
        Logger.d("LevelTag", "dropped debug")
        Logger.i("LevelTag", "dropped info")
        Logger.w("LevelTag", "dropped warning")
        val sizeAfterDrops = LogBuffer.entries.size
        Logger.e("LevelTag", "kept error")
        assertEquals(sizeAfterDrops + 1, LogBuffer.entries.size)
        assertEquals("E", LogBuffer.entries.last().level)
    }

    @Test
    fun `setLevel INFO drops debug and keeps info, warning and error`() {
        Logger.setLevel(LogLevel.INFO)
        Logger.d("LevelTag", "dropped debug")
        val sizeAfterDebug = LogBuffer.entries.size
        Logger.i("LevelTag", "kept info")
        Logger.w("LevelTag", "kept warning")
        Logger.e("LevelTag", "kept error")
        assertEquals(sizeAfterDebug + 3, LogBuffer.entries.size)
        assertEquals("I", LogBuffer.entries[sizeAfterDebug].level)
        assertEquals("W", LogBuffer.entries[sizeAfterDebug + 1].level)
        assertEquals("E", LogBuffer.entries.last().level)
    }

    @Test
    fun `setLevel WARNING drops debug and info and keeps warning and error`() {
        Logger.setLevel(LogLevel.WARNING)
        Logger.d("LevelTag", "dropped debug")
        Logger.i("LevelTag", "dropped info")
        val sizeAfterDrops = LogBuffer.entries.size
        Logger.w("LevelTag", "kept warning")
        Logger.e("LevelTag", "kept error")
        assertEquals(sizeAfterDrops + 2, LogBuffer.entries.size)
        assertEquals("W", LogBuffer.entries[sizeAfterDrops].level)
        assertEquals("E", LogBuffer.entries.last().level)
    }

    @Test
    fun `setLevel ERROR drops debug, info and warning and keeps error`() {
        Logger.setLevel(LogLevel.ERROR)
        Logger.d("LevelTag", "dropped debug")
        Logger.i("LevelTag", "dropped info")
        Logger.w("LevelTag", "dropped warning")
        val sizeAfterDrops = LogBuffer.entries.size
        Logger.e("LevelTag", "kept error")
        assertEquals(sizeAfterDrops + 1, LogBuffer.entries.size)
        assertEquals("E", LogBuffer.entries.last().level)
    }

    @Test
    fun `an error with a throwable is dropped too while its level is below the threshold`() {
        Logger.setLevel(LogLevel.ERROR)
        Logger.w("LevelTag", "dropped warning", RuntimeException("boom"))
        val sizeAfterDrop = LogBuffer.entries.size
        Logger.e("LevelTag", "kept error", RuntimeException("kept"))
        assertEquals(sizeAfterDrop + 1, LogBuffer.entries.size)
        val keptEntry = LogBuffer.entries.last()
        assertEquals(true, keptEntry.message.contains("kept"))
    }

    @Test
    fun `resetting the level to DEBUG records debug again`() {
        Logger.setLevel(LogLevel.ERROR)
        Logger.setLevel(LogLevel.DEBUG)
        Logger.d("LevelTag", "recorded again")
        assertEquals("D", LogBuffer.entries.last().level)
    }

    @Test
    fun `setLevel OFF drops every entry`() {
        Logger.setLevel(LogLevel.OFF)
        Logger.d("LevelTag", "dropped debug")
        Logger.i("LevelTag", "dropped info")
        Logger.w("LevelTag", "dropped warning")
        Logger.e("LevelTag", "dropped error")
        val sizeAfterDrops = LogBuffer.entries.size
        Logger.d("LevelTag", "still dropped")
        assertEquals(sizeAfterDrops, LogBuffer.entries.size)
    }

    @Test
    fun `the setEnabled meta lines respect the threshold too`() {
        Logger.setLevel(LogLevel.OFF)
        val sizeBefore = LogBuffer.entries.size
        Logger.setEnabled(true)
        Logger.setEnabled(false)
        assertEquals(sizeBefore, LogBuffer.entries.size)
    }

    @Test
    fun `the setEnabled meta lines record while their level passes`() {
        Logger.setEnabled(true)
        assertEquals("I", LogBuffer.entries.last().level)
        assertEquals("Logging enabled", LogBuffer.entries.last().message)
        Logger.setEnabled(false)
        assertEquals("W", LogBuffer.entries.last().level)
        assertEquals("Logging disabled", LogBuffer.entries.last().message)
    }

    @Test
    fun `d calls safeLog when enabled`() {
        LogBuffer.clear()
        Logger.setLevel(LogLevel.DEBUG)
        Logger.setEnabled(true)
        Logger.d("SafeTag", "safe debug")
        assertEquals("safe debug", LogBuffer.entries.last().message)
    }

    @Test
    fun `w calls safeLog when enabled`() {
        LogBuffer.clear()
        Logger.setLevel(LogLevel.DEBUG)
        Logger.setEnabled(true)
        Logger.w("SafeTag", "safe warning")
        assertEquals("safe warning", LogBuffer.entries.last().message)
    }

    @Test
    fun `e calls safeLog when enabled`() {
        LogBuffer.clear()
        Logger.setLevel(LogLevel.DEBUG)
        Logger.setEnabled(true)
        Logger.e("SafeTag", "safe error")
        assertEquals("safe error", LogBuffer.entries.last().message)
    }

    @Test
    fun `d is no-op in release builds`() {
        // In a debug build, d() records when level passes; the BuildConfig.DEBUG guard
        // is what drops it in release. Since we're in a debug build, d() should record.
        Logger.setLevel(LogLevel.DEBUG)
        Logger.d("ReleaseTag", "debug only")
        assertEquals("debug only", LogBuffer.entries.last().message)
    }

    @Test
    fun `e with null throwable uses simple addError`() {
        Logger.setLevel(LogLevel.DEBUG)
        Logger.e("NullThrowableTag", "no throwable", null)
        val entry = LogBuffer.entries.last()
        assertEquals("E", entry.level)
        assertEquals("no throwable", entry.message)
    }

    @Test
    fun `w with null throwable does not append exception info`() {
        Logger.setLevel(LogLevel.DEBUG)
        Logger.w("NullWTag", "warning only", null)
        val entry = LogBuffer.entries.last()
        assertEquals("warning only", entry.message)
    }

    @Test
    fun `i with null throwable does not append exception info`() {
        Logger.setLevel(LogLevel.DEBUG)
        Logger.i("NullITag", "info only", null)
        val entry = LogBuffer.entries.last()
        assertEquals("info only", entry.message)
    }
}
