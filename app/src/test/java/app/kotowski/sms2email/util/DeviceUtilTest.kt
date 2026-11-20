package app.kotowski.sms2email.util

import android.content.Context
import android.provider.Settings
import app.kotowski.sms2email.BuildConfig
import app.kotowski.sms2email.R
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DeviceUtilTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `getDeviceName returns the device name from settings`() {
        Settings.Global.putString(context.contentResolver, Settings.Global.DEVICE_NAME, "My Pixel")
        assertEquals("My Pixel", DeviceUtil.getDeviceName(context))
    }

    @Test
    fun `getDeviceName falls back to app name when device name is blank`() {
        Settings.Global.putString(context.contentResolver, Settings.Global.DEVICE_NAME, "")
        assertEquals(context.getString(R.string.app_name), DeviceUtil.getDeviceName(context))
    }

    @Test
    fun `getDeviceName falls back to app name when device name is whitespace`() {
        Settings.Global.putString(context.contentResolver, Settings.Global.DEVICE_NAME, "   ")
        assertEquals(context.getString(R.string.app_name), DeviceUtil.getDeviceName(context))
    }

    @Test
    fun `getDeviceName falls back to app name when no device name set`() {
        val appName = context.getString(R.string.app_name)
        assertEquals(appName, DeviceUtil.getDeviceName(context))
    }

    @Test
    fun `displayVersion strips the pre-release suffix`() {
        val version = BuildConfig.VERSION_NAME
        val display = DeviceUtil.displayVersion()
        assertTrue(!display.contains("-"), "display version should not contain hyphen: $display")
        assertTrue(version.startsWith(display), "$version should start with $display")
    }

    @Test
    fun `displayVersion returns the full version when there is no suffix`() {
        val version = BuildConfig.VERSION_NAME
        if (version.contains("-")) {
            assertEquals(version.substringBefore("-"), DeviceUtil.displayVersion())
        } else {
            assertEquals(version, DeviceUtil.displayVersion())
        }
    }
}
