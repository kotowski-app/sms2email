package app.kotowski.sms2email.system

import android.app.AlarmManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import app.kotowski.sms2email.LogLevel
import app.kotowski.sms2email.util.LogBuffer
import app.kotowski.sms2email.util.Logger
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Robolectric provides a working android.os.Process (ContextCompat.checkSelfPermission reads
// the process pid/uid); the context, activity and prefs are mocked.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionManagerTest {
    @Test
    fun `markRequested writes to shared prefs`() {
        val mockContext = Mockito.mock(Context::class.java)
        val mockPrefs = Mockito.mock(SharedPreferences::class.java)
        val mockEditor = Mockito.mock(SharedPreferences.Editor::class.java)

        `when`(mockContext.getSharedPreferences("permission_state", Context.MODE_PRIVATE)).thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putBoolean(anyString(), anyBoolean())).thenReturn(mockEditor)
        doNothing().`when`(mockEditor).apply()

        val permissionManager = PermissionManager(mockContext)
        permissionManager.markRequested("android.permission.READ_CONTACTS")

        verify(mockEditor).putBoolean("android.permission.READ_CONTACTS", true)
        verify(mockEditor).apply()
    }

    @Test
    fun `PermissionGroup enum has the five permission groups`() {
        assertEquals(5, PermissionGroup.values().size)
        assertTrue(PermissionGroup.SMS != PermissionGroup.NOTIFICATIONS)
        assertTrue(PermissionGroup.NOTIFICATIONS != PermissionGroup.CONTACTS)
        assertTrue(PermissionGroup.CONTACTS != PermissionGroup.PHONE)
        assertTrue(PermissionGroup.PHONE != PermissionGroup.BATTERY)
    }

    @Test
    fun `PermissionsAutoResetStatus enum has correct values`() {
        assertEquals(3, PermissionsAutoResetStatus.values().size)
        assertTrue(PermissionsAutoResetStatus.DISABLED != PermissionsAutoResetStatus.ACTIVE)
        assertTrue(PermissionsAutoResetStatus.ACTIVE != PermissionsAutoResetStatus.NOT_AVAILABLE)
    }

    @Test
    fun `getPermissionsAutoResetStatus maps the DISABLED platform status`() =
        runTest {
            assertEquals(
                PermissionsAutoResetStatus.DISABLED,
                autoResetStatusOf(UnusedAppRestrictionsConstants.DISABLED),
            )
        }

    @Test
    fun `getPermissionsAutoResetStatus maps the FEATURE_NOT_AVAILABLE platform status`() =
        runTest {
            assertEquals(
                PermissionsAutoResetStatus.NOT_AVAILABLE,
                autoResetStatusOf(UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE),
            )
        }

    @Test
    fun `getPermissionsAutoResetStatus maps an unknown platform status to ACTIVE`() =
        runTest {
            assertEquals(PermissionsAutoResetStatus.ACTIVE, autoResetStatusOf(999))
        }

    @Test
    fun `getPermissionsAutoResetStatus maps the default platform status to ACTIVE`() =
        runTest {
            assertEquals(PermissionsAutoResetStatus.ACTIVE, autoResetStatusOf(0))
        }

    @Test
    fun `getPermissionsAutoResetStatus is NOT_AVAILABLE when the query future fails`() =
        runTest {
            val staticMock = Mockito.mockStatic(PackageManagerCompat::class.java)
            try {
                staticMock
                    .`when`<ListenableFuture<Int>> { PackageManagerCompat.getUnusedAppRestrictionsStatus(any<Context>()) }
                    .thenReturn(Futures.immediateFailedFuture(RuntimeException("oem")))
                Logger.setLevel(LogLevel.DEBUG)
                LogBuffer.clear()
                assertEquals(
                    PermissionsAutoResetStatus.NOT_AVAILABLE,
                    harness().manager.getPermissionsAutoResetStatus(),
                )
                val entry = LogBuffer.entries.last()
                assertEquals("W", entry.level)
                assertEquals("PermissionManager", entry.tag)
                assertTrue(entry.message.startsWith("Failed to get permissions auto-reset status"))
            } finally {
                staticMock.close()
            }
        }

    @Test
    fun `the named single permissions are the platform constants`() {
        val h = harness()
        assertEquals(READ_CONTACTS, h.manager.contactsPermission)
        assertEquals(READ_PHONE_STATE, h.manager.phonePermission)
    }

    @Test
    fun `hasBeenRequested is false by default`() {
        val h = harness()
        assertFalse(h.manager.hasBeenRequested(READ_CONTACTS))
    }

    @Test
    fun `hasBeenRequested is true for a permission stored as requested`() {
        val h = harness(requestedBefore = listOf(READ_CONTACTS, READ_SMS))
        assertTrue(h.manager.hasBeenRequested(READ_CONTACTS))
        assertTrue(h.manager.hasBeenRequested(READ_SMS))
        assertFalse(h.manager.hasBeenRequested(READ_PHONE_STATE))
    }

    @Test
    fun `permissionsFor returns the SMS pair for SMS`() {
        val h = harness()
        assertEquals(listOf(READ_SMS, RECEIVE_SMS), h.manager.permissionsFor(PermissionGroup.SMS).toList())
    }

    @Test
    fun `permissionsFor returns the single platform permission for the single-permission groups`() {
        val h = harness()
        assertEquals(
            listOf(POST_NOTIFICATIONS),
            h.manager.permissionsFor(PermissionGroup.NOTIFICATIONS).toList(),
        )
        assertEquals(listOf(READ_CONTACTS), h.manager.permissionsFor(PermissionGroup.CONTACTS).toList())
        assertEquals(listOf(READ_PHONE_STATE), h.manager.permissionsFor(PermissionGroup.PHONE).toList())
    }

    @Test
    fun `permissionsFor is empty for BATTERY because it is not a runtime permission`() {
        val h = harness()
        assertEquals(emptyList<String>(), h.manager.permissionsFor(PermissionGroup.BATTERY).toList())
    }

    @Test
    fun `snapshot reflects every granted permission and battery state`() {
        val h = harness(granted = listOf(READ_SMS, RECEIVE_SMS, POST_NOTIFICATIONS, READ_CONTACTS, READ_PHONE_STATE))
        stubBattery(h, ignoring = true)
        val s = h.manager.snapshot()
        assertTrue(s.sms)
        assertTrue(s.notifications)
        assertTrue(s.contacts)
        assertTrue(s.phone)
        assertTrue(s.battery)
    }

    @Test
    fun `snapshot is all false when nothing is granted`() {
        val h = harness()
        stubBattery(h, ignoring = false)
        val s = h.manager.snapshot()
        assertFalse(s.sms)
        assertFalse(s.notifications)
        assertFalse(s.contacts)
        assertFalse(s.phone)
        assertFalse(s.battery)
    }

    @Test
    fun `snapshot sms is false when only one of the SMS pair is granted`() {
        val h = harness(granted = listOf(READ_SMS))
        stubBattery(h, ignoring = false)
        assertFalse(h.manager.snapshot().sms)
    }

    @Test
    fun `snapshot sms is false when only RECEIVE_SMS is granted`() {
        val h = harness(granted = listOf(RECEIVE_SMS))
        stubBattery(h, ignoring = false)
        assertFalse(h.manager.snapshot().sms)
    }

    @Test
    fun `snapshot partial SMS grants exercises short-circuit paths`() {
        val h = harness(granted = listOf(RECEIVE_SMS))
        stubBattery(h, ignoring = false)
        val s = h.manager.snapshot()
        assertFalse(s.sms)
    }

    @Test
    fun `snapshot partial grants for other permissions`() {
        val h = harness(granted = listOf(READ_SMS, RECEIVE_SMS, READ_CONTACTS))
        stubBattery(h, ignoring = true)
        val s = h.manager.snapshot()
        assertTrue(s.sms)
        assertFalse(s.notifications)
        assertTrue(s.contacts)
        assertFalse(s.phone)
        assertTrue(s.battery)
    }

    @Test
    fun `canScheduleExactAlarms reads the platform state through the alarm manager`() {
        val h = harness()
        val alarmManager = Mockito.mock(AlarmManager::class.java)
        `when`(h.context.getSystemService(Context.ALARM_SERVICE)).thenReturn(alarmManager)
        `when`(alarmManager.canScheduleExactAlarms()).thenReturn(true)
        assertTrue(h.manager.canScheduleExactAlarms())
        `when`(alarmManager.canScheduleExactAlarms()).thenReturn(false)
        assertFalse(h.manager.canScheduleExactAlarms())
    }

    @Test
    fun `canScheduleExactAlarms degrades to false when the platform read throws`() {
        val h = harness()
        val alarmManager = Mockito.mock(AlarmManager::class.java)
        `when`(h.context.getSystemService(Context.ALARM_SERVICE)).thenReturn(alarmManager)
        `when`(alarmManager.canScheduleExactAlarms()).thenThrow(RuntimeException("patched rom"))
        Logger.setLevel(LogLevel.DEBUG)
        LogBuffer.clear()
        assertFalse(h.manager.canScheduleExactAlarms())
        val entry = LogBuffer.entries.last()
        assertEquals("W", entry.level)
        assertEquals("PermissionManager", entry.tag)
        assertTrue(entry.message.startsWith("Failed to check exact alarm permission"))
    }

    @Test
    fun `snapshot carries the exact alarm state`() {
        val h = harness()
        stubBattery(h, ignoring = false)
        val alarmManager = Mockito.mock(AlarmManager::class.java)
        `when`(h.context.getSystemService(Context.ALARM_SERVICE)).thenReturn(alarmManager)
        `when`(alarmManager.canScheduleExactAlarms()).thenReturn(true)
        assertTrue(h.manager.snapshot().exactAlarms)
    }

    // The platform status arrives through a static future query, so the mapping is
    // exercised through the manager's public read with the query stubbed.
    private suspend fun autoResetStatusOf(platformStatus: Int): PermissionsAutoResetStatus {
        val staticMock = Mockito.mockStatic(PackageManagerCompat::class.java)
        try {
            staticMock
                .`when`<ListenableFuture<Int>> { PackageManagerCompat.getUnusedAppRestrictionsStatus(any<Context>()) }
                .thenReturn(Futures.immediateFuture(platformStatus))
            return harness().manager.getPermissionsAutoResetStatus()
        } finally {
            staticMock.close()
        }
    }

    private fun stubBattery(
        h: Harness,
        ignoring: Boolean,
    ) {
        val powerManager = Mockito.mock(PowerManager::class.java)
        `when`(h.context.getSystemService(Context.POWER_SERVICE)).thenReturn(powerManager)
        `when`(h.context.packageName).thenReturn("app.kotowski.sms2email")
        `when`(powerManager.isIgnoringBatteryOptimizations("app.kotowski.sms2email")).thenReturn(ignoring)
    }

    private class Harness(
        val context: Context,
        val editor: SharedPreferences.Editor,
        val manager: PermissionManager,
    )

    private fun harness(
        granted: List<String> = emptyList(),
        requestedBefore: List<String> = emptyList(),
    ): Harness {
        val context = Mockito.mock(Context::class.java)
        val prefs = Mockito.mock(SharedPreferences::class.java)
        val editor = Mockito.mock(SharedPreferences.Editor::class.java)
        `when`(context.getSharedPreferences("permission_state", Context.MODE_PRIVATE)).thenReturn(prefs)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.putBoolean(anyString(), anyBoolean())).thenReturn(editor)
        `when`(context.checkPermission(anyString(), anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED)
        granted.forEach {
            `when`(context.checkPermission(eq(it), anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED)
        }
        requestedBefore.forEach { `when`(prefs.getBoolean(it, false)).thenReturn(true) }
        return Harness(context, editor, PermissionManager(context))
    }

    @Test
    @Config(sdk = [30])
    fun `canScheduleExactAlarms returns true below API 31`() {
        val h = harness()
        assertTrue(h.manager.canScheduleExactAlarms())
    }

    @Test
    fun `createRequestScheduleExactAlarmIntent builds the correct intent`() {
        val h = harness()
        `when`(h.context.packageName).thenReturn("app.kotowski.sms2email")
        val intent = h.manager.createRequestScheduleExactAlarmIntent()
        assertEquals(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, intent.action)
        assertEquals("package:app.kotowski.sms2email", intent.data.toString())
    }

    @Test
    fun `createManagePermissionsAutoResetIntent returns fallback on exception`() {
        val h = harness()
        `when`(h.context.packageName).thenReturn("app.kotowski.sms2email")
        val pm = Mockito.mock(PackageManager::class.java)
        `when`(h.context.packageManager).thenReturn(pm)
        `when`(pm.resolveActivity(any(), anyInt())).thenReturn(null)
        val intent = h.manager.createManagePermissionsAutoResetIntent()
        assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS", intent.action)
    }

    @Test
    fun `createManagePermissionsAutoResetIntent falls back when resolveActivity returns null`() {
        val h = harness()
        `when`(h.context.packageName).thenReturn("app.kotowski.sms2email")
        val pm = Mockito.mock(PackageManager::class.java)
        `when`(h.context.packageManager).thenReturn(pm)
        `when`(pm.resolveActivity(any(), anyInt())).thenReturn(null)
        val intent = h.manager.createManagePermissionsAutoResetIntent()
        assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS", intent.action)
    }

    @Test
    fun `createManagePermissionsAutoResetIntent falls back on UnsupportedOperationException`() {
        val h = harness()
        `when`(h.context.packageName).thenReturn("app.kotowski.sms2email")
        val pm = Mockito.mock(PackageManager::class.java)
        `when`(h.context.packageManager).thenReturn(pm)
        Mockito.`when`(pm.resolveActivity(any(), anyInt())).thenThrow(UnsupportedOperationException("not here"))
        val intent = h.manager.createManagePermissionsAutoResetIntent()
        assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS", intent.action)
    }

    private companion object {
        const val READ_SMS = "android.permission.READ_SMS"
        const val RECEIVE_SMS = "android.permission.RECEIVE_SMS"
        const val READ_CONTACTS = "android.permission.READ_CONTACTS"
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        const val READ_PHONE_STATE = "android.permission.READ_PHONE_STATE"
    }
}
