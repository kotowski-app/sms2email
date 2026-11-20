package app.kotowski.sms2email.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

// The import picker accepts any file so the export is visible on any document provider.
internal const val IMPORT_MIME_FILTER = "*/*"

// The settings-import file picker shared by the onboarding wizard and the settings
// screen: registers the document picker and hands the picked uri to [onPicked]. The
// import itself (the read, the apply, the toast) runs in the caller's ViewModel scope,
// so it survives the composition's disposal (a rotation, a recomposition).
@Composable
internal fun rememberImportLauncher(onPicked: (Uri) -> Unit): ActivityResultLauncher<Array<String>> =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(onPicked)
    }
