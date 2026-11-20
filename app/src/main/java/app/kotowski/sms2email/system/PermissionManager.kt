package app.kotowski.sms2email.system

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import androidx.core.content.edit
import androidx.core.net.toUri
import app.kotowski.sms2email.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.guava.await
import javax.inject.Inject
import javax.inject.Singleton

// The permission groups the app can ask for, keyed here so the system layer owns the
// group-to-platform-permission policy; the UI keeps only launcher selection.
enum class PermissionGroup {
    SMS,
    NOTIFICATIONS,
    CONTACTS,
    PHONE,
    BATTERY,
}

enum class PermissionsAutoResetStatus {
    DISABLED,
    ACTIVE,
    NOT_AVAILABLE,
}

// One read of every runtime permission the UI displays; screens render from this single
// matrix instead of calling checkSelfPermission themselves.
data class PermissionSnapshot(
    val sms: Boolean,
    val notifications: Boolean,
    val contacts: Boolean,
    val phone: Boolean,
    val battery: Boolean,
    // The "Alarms & reminders" (SCHEDULE_EXACT_ALARM) state; unconditionally true below
    // API 31, where the permission does not exist.
    val exactAlarms: Boolean = true,
)

@Singleton
class PermissionManager
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
    ) {
        private val prefs = appContext.getSharedPreferences("permission_state", Context.MODE_PRIVATE)

        fun isGranted(permission: String): Boolean =
            ContextCompat.checkSelfPermission(
                appContext,
                permission,
            ) == PackageManager.PERMISSION_GRANTED

        // Whether the user has been asked for this permission before: a per-app flag the
        // request flow sets the first time it asks (the platform's rationale query alone
        // cannot tell "never asked" from "permanently denied" apart on every API level).
        fun hasBeenRequested(permission: String): Boolean = prefs.getBoolean(permission, false)

        fun markRequested(permission: String) {
            prefs.edit { putBoolean(permission, true) }
        }

        // The SMS pair (read + receive) is always requested and denied as one unit.
        private val smsPermissions = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)

        // The single-permission groups, named here so every screen references the manager
        // instead of the platform constants.
        @SuppressLint("NewApi")
        private val notificationsPermission = Manifest.permission.POST_NOTIFICATIONS
        val contactsPermission = Manifest.permission.READ_CONTACTS
        val phonePermission = Manifest.permission.READ_PHONE_STATE

        // The platform permissions a group maps to, asked as one unit (a single permission
        // is a group of one); battery is not a runtime permission at all, hence empty.
        fun permissionsFor(group: PermissionGroup): Array<String> =
            when (group) {
                PermissionGroup.SMS -> smsPermissions
                PermissionGroup.NOTIFICATIONS -> arrayOf(notificationsPermission)
                PermissionGroup.CONTACTS -> arrayOf(contactsPermission)
                PermissionGroup.PHONE -> arrayOf(phonePermission)
                PermissionGroup.BATTERY -> emptyArray()
            }

        fun createAppSettingsIntent(): Intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:${appContext.packageName}".toUri()
            }

        @SuppressLint("BatteryLife")
        fun createBatteryOptimizationIntent(): Intent =
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                "package:${appContext.packageName}".toUri(),
            )

        private fun batteryOptimizationGranted(): Boolean {
            val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            return powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
        }

        // The single guarded exact-alarm permission check: the "Alarms & reminders"
        // (SCHEDULE_EXACT_ALARM) permission only exists from API 31, so below 31 the check
        // is unconditionally true; from 31 on only the system setting is read (never the
        // manifest). A ROM whose patched framework throws degrades to "not granted".
        fun canScheduleExactAlarms(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            return try {
                (appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
            } catch (e: Exception) {
                Logger.w("PermissionManager", "Failed to check exact alarm permission: ${e.message}")
                false
            }
        }

        // The system "Alarms & reminders" page for this app: only reachable (and only
        // needed) from API 31 on, where canScheduleExactAlarms() can be false.
        @RequiresApi(Build.VERSION_CODES.S)
        fun createRequestScheduleExactAlarmIntent(): Intent =
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = "package:${appContext.packageName}".toUri()
            }

        fun snapshot(): PermissionSnapshot =
            PermissionSnapshot(
                sms = isGranted(Manifest.permission.READ_SMS) && isGranted(Manifest.permission.RECEIVE_SMS),
                notifications = isGranted(notificationsPermission),
                contacts = isGranted(Manifest.permission.READ_CONTACTS),
                phone = isGranted(Manifest.permission.READ_PHONE_STATE),
                battery = batteryOptimizationGranted(),
                exactAlarms = canScheduleExactAlarms(),
            )

        suspend fun getPermissionsAutoResetStatus(): PermissionsAutoResetStatus =
            try {
                val status = PackageManagerCompat.getUnusedAppRestrictionsStatus(appContext).await()
                when (status) {
                    UnusedAppRestrictionsConstants.DISABLED -> PermissionsAutoResetStatus.DISABLED
                    UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE -> PermissionsAutoResetStatus.NOT_AVAILABLE
                    else -> PermissionsAutoResetStatus.ACTIVE
                }
            } catch (e: Exception) {
                // A failing query (OEM ROM) is reported as NOT_AVAILABLE: the UI then hides
                // the row instead of showing a warning whose only action is a dead end.
                Logger.w("PermissionManager", "Failed to get permissions auto-reset status: ${e.message}")
                PermissionsAutoResetStatus.NOT_AVAILABLE
            }

        fun createManagePermissionsAutoResetIntent(): Intent {
            val fallback = createAppSettingsIntent()
            return try {
                val intent =
                    IntentCompat.createManageUnusedAppRestrictionsIntent(
                        appContext,
                        appContext.packageName,
                    )
                if (intent.resolveActivity(appContext.packageManager) != null) intent else fallback
            } catch (e: UnsupportedOperationException) {
                fallback
            }
        }
    }
