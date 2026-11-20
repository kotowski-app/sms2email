package app.kotowski.sms2email.util

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AndroidVersionTest {
    @Test
    @Config(sdk = [29])
    fun `below API 30 all gates are false`() {
        assertFalse(AndroidVersion.is11Plus())
        assertFalse(AndroidVersion.is12Plus())
        assertFalse(AndroidVersion.is13Plus())
    }

    @Test
    @Config(sdk = [30])
    fun `API 30 enables is11Plus only`() {
        assertTrue(AndroidVersion.is11Plus())
        assertFalse(AndroidVersion.is12Plus())
        assertFalse(AndroidVersion.is13Plus())
    }

    @Test
    @Config(sdk = [31])
    fun `API 31 enables is11Plus and is12Plus`() {
        assertTrue(AndroidVersion.is11Plus())
        assertTrue(AndroidVersion.is12Plus())
        assertFalse(AndroidVersion.is13Plus())
    }

    @Test
    @Config(sdk = [32])
    fun `API 32 still leaves is13Plus false`() {
        assertTrue(AndroidVersion.is11Plus())
        assertTrue(AndroidVersion.is12Plus())
        assertFalse(AndroidVersion.is13Plus())
    }

    @Test
    @Config(sdk = [33])
    fun `API 33 enables all gates`() {
        assertTrue(AndroidVersion.is11Plus())
        assertTrue(AndroidVersion.is12Plus())
        assertTrue(AndroidVersion.is13Plus())
    }
}
