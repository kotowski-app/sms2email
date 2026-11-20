package app.kotowski.sms2email.util

import android.content.Context
import android.database.Cursor
import android.net.Uri

internal fun Context.querySafely(
    uri: Uri,
    projection: Array<String>?,
    selection: String? = null,
    selectionArgs: Array<String>? = null,
    sortOrder: String? = null,
): Cursor? =
    try {
        contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
    } catch (e: Exception) {
        // No path, no e.message: the path can hold PII (a phone_lookup number, a picked
        // file name) and the Permission Denial text embeds the full URI.
        Logger.w("querySafely", "query failed for ${uri.scheme}://${uri.authority}: ${e.javaClass.simpleName}")
        null
    }
