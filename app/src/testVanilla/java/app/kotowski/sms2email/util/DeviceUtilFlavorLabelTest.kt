package app.kotowski.sms2email.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceUtilFlavorLabelTest {
    @Test
    fun `the vanilla build shows the FOSS flavor label`() {
        assertEquals("FOSS", DeviceUtil.flavorLabel)
    }
}
