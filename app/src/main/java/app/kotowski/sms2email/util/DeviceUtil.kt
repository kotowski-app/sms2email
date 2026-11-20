package app.kotowski.sms2email.util

import android.content.Context
import android.provider.Settings
import app.kotowski.sms2email.BuildConfig
import app.kotowski.sms2email.R

object DeviceUtil {
    // The device's user-set name (Settings > About > Device name); the app name is the
    // fallback, so the subject prefix a fresh device gets reads the same as the app.
    fun getDeviceName(context: Context): String =
        try {
            Settings.Global
                .getString(
                    context.contentResolver,
                    Settings.Global.DEVICE_NAME,
                )?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } ?: context.getString(R.string.app_name)

    fun displayVersion(): String = BuildConfig.VERSION_NAME.substringBefore("-")

    // The flavor label shown next to the version.
    val flavorLabel: String
        get() = BuildConfig.FLAVOR_LABEL
}
