package app.kotowski.sms2email

import app.kotowski.sms2email.BuildConfig
import app.kotowski.sms2email.LogLevel
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.util.LogBuffer
import app.kotowski.sms2email.util.LogFileStore
import app.kotowski.sms2email.util.Logger
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Sms2EmailAppTest {
    @Before
    fun setUp() {
        // The default threshold (ERROR) would filter the init-failure warning the
        // degradation tests assert on.
        Logger.setLevel(LogLevel.DEBUG)
    }

    @Test
    fun `forwarding enabled default is on`() {
        assertTrue(AppSettings.DEFAULT_FORWARDING_ENABLED)
    }

    @Test
    fun `initLoggingSafely swallows an IO error from the tail load and logs it`() {
        LogBuffer.clear()
        // A directory exists but cannot be read as a file: loadTail throws an IOException.
        val dir =
            File.createTempFile("logdir", "").apply {
                delete()
                mkdirs()
            }
        val store = LogFileStore(dir)
        assertFailsWith<IOException> { store.loadTail(1) }
        Sms2EmailApp.initLoggingSafely(store)
        val entry = LogBuffer.entries.last()
        assertEquals("W", entry.level)
        assertEquals("Sms2EmailApp", entry.tag)
        assertTrue(entry.message.startsWith("Log persistence init failed"))
    }

    @Test
    fun `initLoggingSafely leaves the in-memory buffer usable after a failure`() {
        LogBuffer.clear()
        val dir =
            File.createTempFile("logdir", "").apply {
                delete()
                mkdirs()
            }
        Sms2EmailApp.initLoggingSafely(LogFileStore(dir))
        LogBuffer.addInfo("AfterTag", "buffer still works")
        val entry = LogBuffer.entries.last()
        assertEquals("I", entry.level)
        assertEquals("AfterTag", entry.tag)
        assertEquals("buffer still works", entry.message)
    }

    @Test
    fun `initLoggingSafely swallows an OOM from the tail load instead of letting it escape`() {
        // An escaping Error would crash-loop every process start, so it must degrade like
        // any other init failure. The file exists but dies on the tail load's size read
        // (simulated OOM).
        LogBuffer.clear()
        val dir =
            File.createTempFile("logdir", "").apply {
                delete()
                mkdirs()
            }
        val oomFile =
            object : File(dir, "oom.log") {
                override fun exists(): Boolean = true

                override fun length(): Long = throw OutOfMemoryError("simulated")
            }
        Sms2EmailApp.initLoggingSafely(LogFileStore(oomFile))
        assertTrue(
            LogBuffer.entries.any { it.level == "W" && it.message.startsWith("Log persistence init failed") },
        )
        LogBuffer.addInfo("AfterTag", "buffer still works")
        val entry = LogBuffer.entries.last()
        assertEquals("I", entry.level)
        assertEquals("AfterTag", entry.tag)
        assertEquals("buffer still works", entry.message)
    }

    @Test
    fun `logFileSizeFor passes a preset size through`() {
        for (size in LogSizePreset.bytes) {
            assertEquals(size, Sms2EmailApp.logFileSizeFor(AppSettings(logFileSizeBytes = size)))
        }
    }

    @Test
    fun `logFileSizeFor degrades a non-preset size to the shipped default`() {
        for (size in listOf(0, -5, 1, 512 * 1024, Int.MAX_VALUE)) {
            assertEquals(
                AppConfig.LOG_FILE_MAX_BYTES,
                Sms2EmailApp.logFileSizeFor(AppSettings(logFileSizeBytes = size)),
                "size=$size",
            )
        }
    }

    @Test
    fun `appStartLine carries the version and build type`() {
        val line = Sms2EmailApp.appStartLine()
        assertTrue(line.startsWith("app start, version "))
        assertTrue(line.contains(BuildConfig.VERSION_NAME.substringBefore("-")))
        assertTrue(line.contains("build type ${BuildConfig.BUILD_TYPE}"))
    }
}
