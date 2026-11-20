package app.kotowski.sms2email.ui.settings

import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.LogSizePreset
import app.kotowski.sms2email.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSizePresetLabelTest {
    @Test
    fun `each size maps to its own label resource`() {
        assertEquals(R.string.log_size_64kb, LogSizePreset.labelResFor(64 * 1024))
        assertEquals(R.string.log_size_256kb, LogSizePreset.labelResFor(256 * 1024))
        assertEquals(R.string.log_size_1mb, LogSizePreset.labelResFor(1024 * 1024))
        assertEquals(R.string.log_size_4mb, LogSizePreset.labelResFor(4 * 1024 * 1024))
        assertEquals(R.string.log_size_16mb, LogSizePreset.labelResFor(16 * 1024 * 1024))
    }

    @Test
    fun `a stored value outside the presets degrades to the default label`() {
        assertEquals(R.string.log_size_256kb, LogSizePreset.labelResFor(0))
        assertEquals(R.string.log_size_256kb, LogSizePreset.labelResFor(128 * 1024))
        assertEquals(R.string.log_size_256kb, LogSizePreset.labelResFor(-1))
        assertEquals(R.string.log_size_256kb, LogSizePreset.labelResFor(32 * 1024 * 1024))
    }

    @Test
    fun `the preset list is the same set the label mapping covers`() {
        val presets = LogSizePreset.bytes
        assertEquals(
            listOf(64 * 1024, 256 * 1024, 1024 * 1024, 4 * 1024 * 1024, 16 * 1024 * 1024),
            presets,
        )
        // The default is one of the presets (the shipped cap), so the enable flow
        // can always commit it.
        assertTrue(LogSizePreset.DEFAULT.bytes in presets)
        assertEquals(AppConfig.LOG_FILE_MAX_BYTES, LogSizePreset.DEFAULT.bytes)
    }
}
