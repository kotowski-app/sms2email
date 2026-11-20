package app.kotowski.sms2email.util

import android.os.Build

// Centralizes the SDK gates so call sites read as intent, not magic numbers. The functions
// are inline so the SDK_INT comparison stays in the caller's bytecode and lint's NewApi
// guard analysis keeps working without @RequiresApi.
@Suppress("NOTHING_TO_INLINE")
object AndroidVersion {
    inline fun is11Plus(): Boolean = Build.VERSION.SDK_INT >= 30

    inline fun is12Plus(): Boolean = Build.VERSION.SDK_INT >= 31

    inline fun is13Plus(): Boolean = Build.VERSION.SDK_INT >= 33
}
