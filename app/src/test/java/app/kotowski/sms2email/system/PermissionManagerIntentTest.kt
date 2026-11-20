package app.kotowski.sms2email.system

import android.content.Context
import android.provider.Settings
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionManagerIntentTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = PermissionManager(context)

    @Test
    fun `createAppSettingsIntent points at the app details page of this package`() {
        val intent = manager.createAppSettingsIntent()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
    }

    @Test
    fun `createBatteryOptimizationIntent requests exemption for this package`() {
        val intent = manager.createBatteryOptimizationIntent()
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
    }

    @Test
    fun `createManagePermissionsAutoResetIntent falls back to the app details page when nothing resolves`() {
        val intent = manager.createManagePermissionsAutoResetIntent()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
    }
}
