package app.kotowski.sms2email.util

import android.util.Log
import app.kotowski.sms2email.BuildConfig
import app.kotowski.sms2email.LogLevel

object Logger {
    private const val TAG_PREFIX = " sms2email "
    private var isEnabled: Boolean = false

    // The threshold: entries at it or more severe pass, the rest are dropped (OFF drops
    // all). @Volatile: set on the main thread, read from every logging thread.
    @Volatile
    private var minLevel: LogLevel = LogLevel.DEFAULT

    // Only-on-change: the start-up and import hooks re-apply the stored level, and a meta
    // line per no-op call is noise. Via the level-checked i, so a change to a level above INFO drops its own meta line.
    fun setLevel(level: LogLevel) {
        if (level == minLevel) return
        minLevel = level
        i("Logger", "log level set to $level")
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        // Via the level-checked i/w, so the meta lines respect the threshold too.
        if (enabled) {
            i("Logger", "Logging enabled")
        } else {
            w("Logger", "Logging disabled")
        }
    }

    private fun passes(level: LogLevel): Boolean = level.severity >= minLevel.severity

    fun d(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        if (!passes(LogLevel.DEBUG)) return
        LogBuffer.addDebug(tag, message)
        if (isEnabled) safeLog { Log.d("$TAG_PREFIX$tag", message, throwable) }
    }

    fun i(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (!passes(LogLevel.INFO)) return
        LogBuffer.addInfo(tag, message)
        if (isEnabled) safeLog { Log.i("$TAG_PREFIX$tag", message, throwable) }
    }

    fun w(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (!passes(LogLevel.WARNING)) return
        LogBuffer.addWarn(tag, message)
        if (isEnabled) safeLog { Log.w("$TAG_PREFIX$tag", message, throwable) }
    }

    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (!passes(LogLevel.ERROR)) return
        if (throwable != null) {
            LogBuffer.addError(tag, message, throwable)
        } else {
            LogBuffer.addError(tag, message)
        }
        if (isEnabled) safeLog { Log.e("$TAG_PREFIX$tag", message, throwable) }
    }

    private inline fun safeLog(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }
}
