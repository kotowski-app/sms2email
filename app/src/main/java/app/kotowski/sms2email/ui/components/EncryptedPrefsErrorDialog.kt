package app.kotowski.sms2email.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.kotowski.sms2email.R

// Shown when the encrypted settings cannot be opened (an unreadable file or keyset, or an
// unavailable keystore): the app cannot read or save anything, so the only actions are to
// restart the phone, or, if that does not help, reset the app's data or reinstall it.
@Composable
fun EncryptedPrefsErrorDialog(onClose: () -> Unit) {
    TextConfirmDialog(
        icon = Icons.Default.WarningAmber,
        detail = stringResource(R.string.encrypted_prefs_error_message),
        confirmResId = R.string.action_close,
        showCancel = false,
        onConfirm = onClose,
        onDismiss = onClose,
    )
}
