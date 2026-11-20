package app.kotowski.sms2email.ui.components

import app.kotowski.sms2email.LanguageOption
import app.kotowski.sms2email.R
import org.junit.Test
import kotlin.test.assertEquals

class LanguagePickerTest {
    @Test
    fun `languageLabelRes returns the label for a known tag`() {
        LanguageOption.entries.forEach { option ->
            assertEquals(option.labelRes, languageLabelRes(option.tag), option.tag)
        }
    }

    @Test
    fun `languageLabelRes returns system default for an unknown tag`() {
        assertEquals(R.string.settings_system_default, languageLabelRes("xx"))
        assertEquals(R.string.settings_system_default, languageLabelRes(""))
    }

    @Test
    fun `languageLabelRes returns system default for empty string`() {
        assertEquals(R.string.settings_system_default, languageLabelRes(""))
    }
}
