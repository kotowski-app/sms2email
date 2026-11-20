package app.kotowski.sms2email.util

import android.content.Context
import android.net.Uri

// Writes the export JSON to the SAF document the user picked. Returns false when the
// provider hands back no stream (the target was rejected, nothing was written), so the
// caller can tell a real write from a silent no-op.
fun writeExport(
    context: Context,
    uri: Uri,
    json: String,
): Boolean {
    val stream = context.contentResolver.openOutputStream(uri) ?: return false
    stream.use { it.writer().use { writer -> writer.write(json) } }
    return true
}
