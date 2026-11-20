package app.kotowski.sms2email.ui.components

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import app.kotowski.sms2email.R
import app.kotowski.sms2email.system.PermissionGroup
import app.kotowski.sms2email.system.PermissionManager
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// A real Robolectric activity so the context-to-activity cast, the platform rationale query
// and the permanent-denial toast work; the permission manager and launchers are fakes.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionRequestTest {
    private class TestActivity : Activity()

    // ActivityResultLauncher is an abstract class whose concrete launch(input) delegates to
    // the two-argument overload, so the fake only implements the abstract members.
    private class RecordingLauncher : ActivityResultLauncher<Array<String>>() {
        var launched: Array<String>? = null
            private set

        override fun launch(
            input: Array<String>,
            options: ActivityOptionsCompat?,
        ) {
            launched = input
        }

        override fun unregister() = Unit

        override val contract: ActivityResultContract<Array<String>, *>
            get() = throw UnsupportedOperationException("not used in tests")
    }

    private class RecordingIntentLauncher : ActivityResultLauncher<Intent>() {
        var launched: Intent? = null
            private set

        override fun launch(
            input: Intent,
            options: ActivityOptionsCompat?,
        ) {
            launched = input
        }

        override fun unregister() = Unit

        override val contract: ActivityResultContract<Intent, *>
            get() = throw UnsupportedOperationException("not used in tests")
    }

    private lateinit var activity: Activity
    private lateinit var mockActivity: Activity
    private lateinit var permissionManager: PermissionManager
    private lateinit var launcher: RecordingLauncher
    private lateinit var smsLauncher: RecordingLauncher
    private lateinit var notificationsLauncher: RecordingLauncher
    private lateinit var contactsLauncher: RecordingLauncher
    private lateinit var phoneLauncher: RecordingLauncher
    private lateinit var batteryLauncher: RecordingIntentLauncher
    private lateinit var appSettingsLauncher: RecordingIntentLauncher
    private lateinit var autoResetLauncher: RecordingIntentLauncher
    private lateinit var launchers: PermissionLaunchers
    private var settingsOpened = false
    private var alreadyGrantedInvoked = false

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(TestActivity::class.java).setup().get()
        mockActivity = mock()
        permissionManager = mock()
        launcher = RecordingLauncher()
        smsLauncher = RecordingLauncher()
        notificationsLauncher = RecordingLauncher()
        contactsLauncher = RecordingLauncher()
        phoneLauncher = RecordingLauncher()
        batteryLauncher = RecordingIntentLauncher()
        appSettingsLauncher = RecordingIntentLauncher()
        autoResetLauncher = RecordingIntentLauncher()
        launchers =
            PermissionLaunchers(
                smsLauncher,
                notificationsLauncher,
                contactsLauncher,
                phoneLauncher,
                batteryLauncher,
                appSettingsLauncher,
                autoResetLauncher,
            )
        settingsOpened = false
        alreadyGrantedInvoked = false
    }

    private fun openSettings() {
        settingsOpened = true
    }

    private fun request(permissions: Array<String>) {
        handlePermissionRequest(
            activity,
            permissionManager,
            permissions,
            launcher,
            ::openSettings,
        ) { alreadyGrantedInvoked = true }
    }

    @Test
    fun `handlePermissionRequest launches the group when the outcome is ASK`() {
        whenever(permissionManager.isGranted(Manifest.permission.READ_CONTACTS)).thenReturn(false)
        whenever(permissionManager.hasBeenRequested(Manifest.permission.READ_CONTACTS)).thenReturn(false)

        request(arrayOf(Manifest.permission.READ_CONTACTS))

        assertEquals(listOf(Manifest.permission.READ_CONTACTS), launcher.launched?.toList())
        assertEquals(false, settingsOpened)
        assertEquals(false, alreadyGrantedInvoked)
        verify(permissionManager).markRequested(Manifest.permission.READ_CONTACTS)
    }

    @Test
    fun `handlePermissionRequest launches the SMS pair when the outcome is ASK`() {
        whenever(permissionManager.isGranted(Manifest.permission.READ_SMS)).thenReturn(false)
        whenever(permissionManager.isGranted(Manifest.permission.RECEIVE_SMS)).thenReturn(false)

        request(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))

        assertEquals(listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS), launcher.launched?.toList())
        assertEquals(false, settingsOpened)
        verify(permissionManager).markRequested(Manifest.permission.READ_SMS)
        verify(permissionManager).markRequested(Manifest.permission.RECEIVE_SMS)
    }

    @Test
    fun `handlePermissionRequest opens app settings without launching when any denial is permanent`() {
        whenever(permissionManager.isGranted(Manifest.permission.READ_CONTACTS)).thenReturn(false)
        whenever(permissionManager.hasBeenRequested(Manifest.permission.READ_CONTACTS)).thenReturn(true)

        request(arrayOf(Manifest.permission.READ_CONTACTS))

        assertTrue(settingsOpened)
        assertNull(launcher.launched)
        verify(permissionManager, never()).markRequested(Manifest.permission.READ_CONTACTS)
    }

    @Test
    fun `handlePermissionRequest opens app settings without launching when the SMS pair is permanently denied`() {
        whenever(permissionManager.isGranted(Manifest.permission.READ_SMS)).thenReturn(false)
        whenever(permissionManager.hasBeenRequested(Manifest.permission.READ_SMS)).thenReturn(true)

        request(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))

        assertTrue(settingsOpened)
        assertNull(launcher.launched)
    }

    @Test
    fun `handlePermissionRequest shows the permanent-denial toast when the denial is permanent`() {
        whenever(permissionManager.isGranted(Manifest.permission.READ_CONTACTS)).thenReturn(false)
        whenever(permissionManager.hasBeenRequested(Manifest.permission.READ_CONTACTS)).thenReturn(true)

        request(arrayOf(Manifest.permission.READ_CONTACTS))

        assertTrue(settingsOpened)
        assertEquals(activity.getString(R.string.permissions_permanently_denied), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `handlePermissionRequest invokes onAlreadyGranted and launches nothing when already granted`() {
        whenever(permissionManager.isGranted(Manifest.permission.READ_CONTACTS)).thenReturn(true)

        request(arrayOf(Manifest.permission.READ_CONTACTS))

        assertTrue(alreadyGrantedInvoked)
        assertEquals(false, settingsOpened)
        assertNull(launcher.launched)
        verify(permissionManager, never()).markRequested(Manifest.permission.READ_CONTACTS)
    }

    private fun grant(group: PermissionGroup) {
        requestPermission(activity, permissionManager, launchers, group, ::openSettings)
    }

    @Test
    fun `requestPermission launches the sms launcher with the SMS pair for SMS`() {
        whenever(permissionManager.permissionsFor(PermissionGroup.SMS))
            .thenReturn(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
        whenever(permissionManager.isGranted(Manifest.permission.READ_SMS)).thenReturn(false)
        whenever(permissionManager.isGranted(Manifest.permission.RECEIVE_SMS)).thenReturn(false)

        grant(PermissionGroup.SMS)

        assertEquals(
            listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS),
            smsLauncher.launched?.toList(),
        )
        assertEquals(false, settingsOpened)
        assertNull(notificationsLauncher.launched)
        assertNull(batteryLauncher.launched)
    }

    @Test
    fun `requestPermission launches the notifications launcher for NOTIFICATIONS`() {
        whenever(permissionManager.permissionsFor(PermissionGroup.NOTIFICATIONS))
            .thenReturn(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        whenever(permissionManager.isGranted(Manifest.permission.POST_NOTIFICATIONS)).thenReturn(false)

        grant(PermissionGroup.NOTIFICATIONS)

        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), notificationsLauncher.launched?.toList())
        assertEquals(false, settingsOpened)
        assertNull(batteryLauncher.launched)
    }

    @Test
    fun `requestPermission launches the contacts launcher for CONTACTS`() {
        whenever(permissionManager.permissionsFor(PermissionGroup.CONTACTS))
            .thenReturn(arrayOf(Manifest.permission.READ_CONTACTS))
        whenever(permissionManager.isGranted(Manifest.permission.READ_CONTACTS)).thenReturn(false)

        grant(PermissionGroup.CONTACTS)

        assertEquals(listOf(Manifest.permission.READ_CONTACTS), contactsLauncher.launched?.toList())
        assertEquals(false, settingsOpened)
        assertNull(batteryLauncher.launched)
    }

    @Test
    fun `requestPermission launches the phone launcher for PHONE`() {
        whenever(permissionManager.permissionsFor(PermissionGroup.PHONE))
            .thenReturn(arrayOf(Manifest.permission.READ_PHONE_STATE))
        whenever(permissionManager.isGranted(Manifest.permission.READ_PHONE_STATE)).thenReturn(false)

        grant(PermissionGroup.PHONE)

        assertEquals(listOf(Manifest.permission.READ_PHONE_STATE), phoneLauncher.launched?.toList())
        assertEquals(false, settingsOpened)
        assertNull(batteryLauncher.launched)
    }

    @Test
    fun `requestPermission opens app settings without launching when the group is permanently denied`() {
        whenever(permissionManager.permissionsFor(PermissionGroup.CONTACTS))
            .thenReturn(arrayOf(Manifest.permission.READ_CONTACTS))
        whenever(permissionManager.isGranted(Manifest.permission.READ_CONTACTS)).thenReturn(false)
        whenever(permissionManager.hasBeenRequested(Manifest.permission.READ_CONTACTS)).thenReturn(true)

        grant(PermissionGroup.CONTACTS)

        assertTrue(settingsOpened)
        assertNull(contactsLauncher.launched)
        assertNull(batteryLauncher.launched)
    }

    @Test
    fun `requestPermission launches the battery optimization intent for BATTERY without asking a runtime permission`() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        whenever(permissionManager.createBatteryOptimizationIntent()).thenReturn(intent)

        grant(PermissionGroup.BATTERY)

        assertSame(intent, batteryLauncher.launched)
        assertEquals(false, settingsOpened)
        assertNull(smsLauncher.launched)
        assertNull(notificationsLauncher.launched)
        assertNull(contactsLauncher.launched)
        assertNull(phoneLauncher.launched)
    }

    // The ask-vs-open-settings decision itself, against a mocked activity so the platform
    // rationale query is controllable.
    @Test
    fun `prepareMultiRequest returns ALREADY_GRANTED when every permission is granted`() {
        whenever(permissionManager.isGranted(Manifest.permission.READ_CONTACTS)).thenReturn(true)

        val outcome =
            prepareMultiRequest(mockActivity, permissionManager, listOf(Manifest.permission.READ_CONTACTS))

        assertEquals(PermissionRequestOutcome.ALREADY_GRANTED, outcome)
        verify(permissionManager, never()).markRequested(Manifest.permission.READ_CONTACTS)
    }

    @Test
    fun `prepareMultiRequest returns ASK and marks every permission when none was requested`() {
        whenever(permissionManager.isGranted(Manifest.permission.READ_CONTACTS)).thenReturn(false)
        whenever(permissionManager.hasBeenRequested(Manifest.permission.READ_CONTACTS)).thenReturn(false)

        val outcome =
            prepareMultiRequest(mockActivity, permissionManager, listOf(Manifest.permission.READ_CONTACTS))

        assertEquals(PermissionRequestOutcome.ASK, outcome)
        verify(permissionManager).markRequested(Manifest.permission.READ_CONTACTS)
    }

    @Test
    fun `prepareMultiRequest returns ASK when requested before but the rationale is still shown`() {
        whenever(permissionManager.isGranted(Manifest.permission.READ_CONTACTS)).thenReturn(false)
        whenever(permissionManager.hasBeenRequested(Manifest.permission.READ_CONTACTS)).thenReturn(true)
        whenever(mockActivity.shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)).thenReturn(true)

        val outcome =
            prepareMultiRequest(mockActivity, permissionManager, listOf(Manifest.permission.READ_CONTACTS))

        assertEquals(PermissionRequestOutcome.ASK, outcome)
        verify(permissionManager).markRequested(Manifest.permission.READ_CONTACTS)
    }

    @Test
    fun `prepareMultiRequest returns OPEN_SETTINGS when any denial is permanent`() {
        whenever(permissionManager.isGranted(Manifest.permission.READ_CONTACTS)).thenReturn(false)
        whenever(permissionManager.hasBeenRequested(Manifest.permission.READ_CONTACTS)).thenReturn(true)
        whenever(mockActivity.shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)).thenReturn(false)

        val outcome =
            prepareMultiRequest(mockActivity, permissionManager, listOf(Manifest.permission.READ_CONTACTS))

        assertEquals(PermissionRequestOutcome.OPEN_SETTINGS, outcome)
        verify(permissionManager, never()).markRequested(Manifest.permission.READ_CONTACTS)
    }

    @Test
    fun `prepareMultiRequest returns ALREADY_GRANTED when the whole SMS pair is granted`() {
        whenever(permissionManager.isGranted(Manifest.permission.READ_SMS)).thenReturn(true)
        whenever(permissionManager.isGranted(Manifest.permission.RECEIVE_SMS)).thenReturn(true)

        val outcome =
            prepareMultiRequest(
                mockActivity,
                permissionManager,
                listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS),
            )

        assertEquals(PermissionRequestOutcome.ALREADY_GRANTED, outcome)
        verify(permissionManager, never()).markRequested(Manifest.permission.READ_SMS)
        verify(permissionManager, never()).markRequested(Manifest.permission.RECEIVE_SMS)
    }

    @Test
    fun `prepareMultiRequest returns ASK and marks the whole SMS pair when both can be asked`() {
        whenever(permissionManager.isGranted(Manifest.permission.READ_SMS)).thenReturn(false)
        whenever(permissionManager.isGranted(Manifest.permission.RECEIVE_SMS)).thenReturn(false)

        val outcome =
            prepareMultiRequest(
                mockActivity,
                permissionManager,
                listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS),
            )

        assertEquals(PermissionRequestOutcome.ASK, outcome)
        verify(permissionManager).markRequested(Manifest.permission.READ_SMS)
        verify(permissionManager).markRequested(Manifest.permission.RECEIVE_SMS)
    }

    @Test
    fun `prepareMultiRequest returns ASK when one of the SMS pair is granted and the other can be asked`() {
        // The realistic partial state: the pair was asked before (both flagged), the platform
        // reports no rationale for the granted one and still shows it for the remaining one.
        whenever(permissionManager.isGranted(Manifest.permission.READ_SMS)).thenReturn(true)
        whenever(permissionManager.isGranted(Manifest.permission.RECEIVE_SMS)).thenReturn(false)
        whenever(permissionManager.hasBeenRequested(Manifest.permission.READ_SMS)).thenReturn(true)
        whenever(permissionManager.hasBeenRequested(Manifest.permission.RECEIVE_SMS)).thenReturn(true)
        whenever(mockActivity.shouldShowRequestPermissionRationale(Manifest.permission.READ_SMS)).thenReturn(false)
        whenever(mockActivity.shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS)).thenReturn(true)

        val outcome =
            prepareMultiRequest(
                mockActivity,
                permissionManager,
                listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS),
            )

        assertEquals(PermissionRequestOutcome.ASK, outcome)
        verify(permissionManager).markRequested(Manifest.permission.READ_SMS)
        verify(permissionManager).markRequested(Manifest.permission.RECEIVE_SMS)
    }

    @Test
    fun `prepareMultiRequest returns OPEN_SETTINGS when either SMS permission is permanently denied`() {
        whenever(permissionManager.isGranted(Manifest.permission.READ_SMS)).thenReturn(false)
        whenever(permissionManager.hasBeenRequested(Manifest.permission.READ_SMS)).thenReturn(true)
        whenever(mockActivity.shouldShowRequestPermissionRationale(Manifest.permission.READ_SMS)).thenReturn(false)

        val outcome =
            prepareMultiRequest(
                mockActivity,
                permissionManager,
                listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS),
            )

        assertEquals(PermissionRequestOutcome.OPEN_SETTINGS, outcome)
        verify(permissionManager, never()).markRequested(Manifest.permission.READ_SMS)
        verify(permissionManager, never()).markRequested(Manifest.permission.RECEIVE_SMS)
    }

    @Test
    fun `handlePermissionRequest returns early when context is not an Activity`() {
        val appContext = org.robolectric.RuntimeEnvironment.getApplication()
        var opened = false
        handlePermissionRequest(
            appContext,
            permissionManager,
            arrayOf(Manifest.permission.READ_CONTACTS),
            launcher,
            onOpenSettings = { opened = true },
        )
        assertEquals(false, opened)
        assertNull(launcher.launched)
    }

    @Test
    fun `handlePermissionRequest invokes onAlreadyGranted when the SMS pair is already granted`() {
        whenever(permissionManager.isGranted(Manifest.permission.READ_SMS)).thenReturn(true)
        whenever(permissionManager.isGranted(Manifest.permission.RECEIVE_SMS)).thenReturn(true)

        request(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))

        assertTrue(alreadyGrantedInvoked)
        assertEquals(false, settingsOpened)
        assertNull(launcher.launched)
    }

    @Test
    fun `requestPermission does nothing when SMS group is already granted`() {
        whenever(permissionManager.permissionsFor(PermissionGroup.SMS))
            .thenReturn(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
        whenever(permissionManager.isGranted(Manifest.permission.READ_SMS)).thenReturn(true)
        whenever(permissionManager.isGranted(Manifest.permission.RECEIVE_SMS)).thenReturn(true)

        requestPermission(activity, permissionManager, launchers, PermissionGroup.SMS, ::openSettings)

        assertEquals(false, settingsOpened)
        assertNull(smsLauncher.launched)
    }

    @Test
    fun `requestPermission does nothing when NOTIFICATIONS group is already granted`() {
        whenever(permissionManager.permissionsFor(PermissionGroup.NOTIFICATIONS))
            .thenReturn(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        whenever(permissionManager.isGranted(Manifest.permission.POST_NOTIFICATIONS)).thenReturn(true)

        requestPermission(activity, permissionManager, launchers, PermissionGroup.NOTIFICATIONS, ::openSettings)

        assertEquals(false, settingsOpened)
        assertNull(notificationsLauncher.launched)
    }

    @Test
    fun `requestPermission does nothing when CONTACTS group is already granted`() {
        whenever(permissionManager.permissionsFor(PermissionGroup.CONTACTS))
            .thenReturn(arrayOf(Manifest.permission.READ_CONTACTS))
        whenever(permissionManager.isGranted(Manifest.permission.READ_CONTACTS)).thenReturn(true)

        requestPermission(activity, permissionManager, launchers, PermissionGroup.CONTACTS, ::openSettings)

        assertEquals(false, settingsOpened)
        assertNull(contactsLauncher.launched)
    }

    @Test
    fun `requestPermission does nothing when PHONE group is already granted`() {
        whenever(permissionManager.permissionsFor(PermissionGroup.PHONE))
            .thenReturn(arrayOf(Manifest.permission.READ_PHONE_STATE))
        whenever(permissionManager.isGranted(Manifest.permission.READ_PHONE_STATE)).thenReturn(true)

        requestPermission(activity, permissionManager, launchers, PermissionGroup.PHONE, ::openSettings)

        assertEquals(false, settingsOpened)
        assertNull(phoneLauncher.launched)
    }
}
