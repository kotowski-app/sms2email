package app.kotowski.sms2email.ui.settings

import app.kotowski.sms2email.PeriodPreset
import app.kotowski.sms2email.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodPresetLabelTest {
    @Test
    fun `each period maps to its own label resource`() {
        assertEquals(R.string.period_30_days, PeriodPreset.labelResFor(30))
        assertEquals(R.string.period_90_days, PeriodPreset.labelResFor(90))
        assertEquals(R.string.period_180_days, PeriodPreset.labelResFor(180))
        assertEquals(R.string.period_1_year, PeriodPreset.labelResFor(365))
        assertEquals(R.string.period_2_years, PeriodPreset.labelResFor(730))
        assertEquals(R.string.period_5_years, PeriodPreset.labelResFor(1825))
    }

    @Test
    fun `a stored value outside the presets degrades to the longest preset label`() {
        assertEquals(R.string.period_5_years, PeriodPreset.labelResFor(0))
        assertEquals(R.string.period_5_years, PeriodPreset.labelResFor(45))
        assertEquals(R.string.period_5_years, PeriodPreset.labelResFor(-1))
        assertEquals(R.string.period_5_years, PeriodPreset.labelResFor(99999))
    }

    @Test
    fun `the preset list is the same set the label mapping covers`() {
        val presets = PeriodPreset.days
        assertEquals(
            listOf(30, 90, 180, 365, 730, 1825),
            presets,
        )
        // The default proposal is one of the presets (1 year), so the enable flow
        // can always commit it.
        assertTrue(PeriodPreset.ONE_YEAR.days in presets)
    }
}
