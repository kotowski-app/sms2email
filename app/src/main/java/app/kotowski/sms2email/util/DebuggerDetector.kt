package app.kotowski.sms2email.util

import android.content.Context
import android.os.Debug

object DebuggerDetector {
    fun isBeingDebugged(context: Context): Boolean {
        try {
            return Debug.isDebuggerConnected() || Debug.waitingForDebugger()
        } catch (_: Exception) {
            return false
        }
    }
}
