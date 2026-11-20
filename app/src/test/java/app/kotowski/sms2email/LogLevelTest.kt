package app.kotowski.sms2email

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogLevelTest {
    @Test
    fun `the levels ascend in severity with off last`() {
        assertEquals(
            listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARNING, LogLevel.ERROR, LogLevel.OFF),
            LogLevel.entries,
        )
        val severities = LogLevel.entries.map { it.severity }
        assertTrue(severities.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun `the default is error`() {
        assertEquals(LogLevel.ERROR, LogLevel.DEFAULT)
    }

    @Test
    fun `off is more severe than every recorded level`() {
        for (level in LogLevel.entries) {
            if (level == LogLevel.OFF) continue
            assertTrue(level.severity < LogLevel.OFF.severity)
        }
    }

    @Test
    fun `fromStored parses every stored name`() {
        for (level in LogLevel.entries) {
            assertEquals(level, LogLevel.fromStored(level.name))
        }
    }

    @Test
    fun `fromStored degrades a stored debug to the default without a debug level`() {
        assertEquals(LogLevel.DEFAULT, LogLevel.fromStored("DEBUG", debugBuild = false))
    }

    @Test
    fun `fromStored keeps a stored debug in debug builds`() {
        assertEquals(LogLevel.DEBUG, LogLevel.fromStored("DEBUG", debugBuild = true))
    }

    @Test
    fun `fromStored degrades a missing or unknown value to the default`() {
        assertEquals(LogLevel.ERROR, LogLevel.fromStored(null))
        assertEquals(LogLevel.ERROR, LogLevel.fromStored(""))
        assertEquals(LogLevel.ERROR, LogLevel.fromStored("VERBOSE"))
        assertEquals(LogLevel.ERROR, LogLevel.fromStored("debug"))
    }

    @Test
    fun `each level maps to its own label resource`() {
        assertEquals(R.string.log_level_debug, LogLevel.DEBUG.labelRes)
        assertEquals(R.string.log_level_info, LogLevel.INFO.labelRes)
        assertEquals(R.string.log_level_warning, LogLevel.WARNING.labelRes)
        assertEquals(R.string.log_level_error, LogLevel.ERROR.labelRes)
        assertEquals(R.string.option_off, LogLevel.OFF.labelRes)
    }
}
