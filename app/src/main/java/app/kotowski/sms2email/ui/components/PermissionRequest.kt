package app.kotowski.sms2email.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import app.kotowski.sms2email.R
import app.kotowski.sms2email.system.PermissionGroup
import app.kotowski.sms2email.system.PermissionManager
import app.kotowski.sms2email.util.ToastUtil

// The outcome of the ask-vs-open-settings decision for a group of permissions asked as one
// unit: already granted, the system dialog can be shown again, or the user has permanently
// denied the group and only the app settings page can undo it.
internal enum class PermissionRequestOutcome {
    ALREADY_GRANTED,
    ASK,
    OPEN_SETTINGS,
}

// The ask-vs-open-settings decision, made in the UI because the permanent-denial check
// needs the Activity's rationale query: all granted, ask the system for the whole group
// (marking it as requested), or send the user to the app settings because any denial is
// permanent.
internal fun prepareMultiRequest(
    activity: Activity,
    permissionManager: PermissionManager,
    permissions: List<String>,
): PermissionRequestOutcome {
    if (permissions.all { permissionManager.isGranted(it) }) return PermissionRequestOutcome.ALREADY_GRANTED
    if (
        permissions.any {
            // Granted permissions never count as permanently denied: the rationale query
            // returns false for them, so the granted check must come first.
            !permissionManager.isGranted(it) &&
                permissionManager.hasBeenRequested(it) &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }
    ) {
        return PermissionRequestOutcome.OPEN_SETTINGS
    }
    permissions.forEach { permissionManager.markRequested(it) }
    return PermissionRequestOutcome.ASK
}

// The permission request flow shared by every screen, for one permission or a group asked
// as one unit: already granted (an optional callback, e.g. to enable the dependent
// setting), ask the system for the whole group, or send the user to the app settings
// because any denial is permanent.
fun handlePermissionRequest(
    context: Context,
    permissionManager: PermissionManager,
    permissions: Array<String>,
    launcher: ActivityResultLauncher<Array<String>>,
    onOpenSettings: () -> Unit,
    onAlreadyGranted: (() -> Unit)? = null,
) {
    val activity = context as? Activity ?: return
    when (prepareMultiRequest(activity, permissionManager, permissions.toList())) {
        PermissionRequestOutcome.ALREADY_GRANTED -> {
            onAlreadyGranted?.invoke()
        }

        PermissionRequestOutcome.ASK -> {
            launcher.launch(permissions)
        }

        PermissionRequestOutcome.OPEN_SETTINGS -> {
            ToastUtil.show(context, R.string.permissions_permanently_denied)
            onOpenSettings()
        }
    }
}

// The single entry point for a permission row's request, shared by every screen: the
// runtime-permission groups go through the standard ask/settings flow, battery (not a
// runtime permission) launches the system battery-optimization screen instead.
fun requestPermission(
    context: Context,
    permissionManager: PermissionManager,
    launchers: PermissionLaunchers,
    group: PermissionGroup,
    onOpenSettings: () -> Unit,
) {
    if (group == PermissionGroup.BATTERY) {
        launchers.battery.launch(permissionManager.createBatteryOptimizationIntent())
        return
    }
    handlePermissionRequest(
        context,
        permissionManager,
        permissionManager.permissionsFor(group),
        launcherFor(group, launchers),
        onOpenSettings,
    )
}

// The result launcher of each runtime-permission group; battery and app-settings use their
// own Intent-based launchers on the PermissionLaunchers holder.
private fun launcherFor(
    group: PermissionGroup,
    launchers: PermissionLaunchers,
): ActivityResultLauncher<Array<String>> =
    when (group) {
        PermissionGroup.SMS -> launchers.sms
        PermissionGroup.NOTIFICATIONS -> launchers.notifications
        PermissionGroup.CONTACTS -> launchers.contacts
        PermissionGroup.PHONE -> launchers.phone
        PermissionGroup.BATTERY -> error("battery is launched directly, see requestPermission")
    }

// Every result launcher the permission screens need, created once per screen with the
// screen's refresh callbacks instead of each screen declaring the same launcher block.
data class PermissionLaunchers(
    val sms: ActivityResultLauncher<Array<String>>,
    val notifications: ActivityResultLauncher<Array<String>>,
    val contacts: ActivityResultLauncher<Array<String>>,
    val phone: ActivityResultLauncher<Array<String>>,
    val battery: ActivityResultLauncher<Intent>,
    val appSettings: ActivityResultLauncher<Intent>,
    val autoReset: ActivityResultLauncher<Intent>,
)

@Composable
fun rememberPermissionLaunchers(
    onResult: () -> Unit,
    onAutoResetResult: () -> Unit,
): PermissionLaunchers {
    val sms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
    val contacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
    val phone = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
    val battery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onResult() }
    val appSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onResult() }
    val autoReset = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onAutoResetResult() }
    return remember(sms, notifications, contacts, phone, battery, appSettings, autoReset) {
        PermissionLaunchers(sms, notifications, contacts, phone, battery, appSettings, autoReset)
    }
}

// The identical info dialog the dashboard and the onboarding show before sending the user
// to the permissions auto-reset settings.
@Composable
fun PermissionsAutoResetDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    TextConfirmDialog(
        titleResId = R.string.permissions_auto_reset,
        detail = stringResource(R.string.permissions_auto_reset_dialog_message),
        confirmResId = R.string.permissions_auto_reset_dialog_open_settings,
        onConfirm = {
            onDismiss()
            onOpenSettings()
        },
        onDismiss = onDismiss,
    )
}
