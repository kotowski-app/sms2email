package app.kotowski.sms2email.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.kotowski.sms2email.util.Logger

// Exists to start the app process when the device powers on: the process start runs
// Sms2EmailApp.onCreate, which starts the BackgroundStateReconciler, whose first collection
// reconciles the background state (both directions). Deleting this receiver or its manifest
// entry would silently stop the app from starting at boot.
// It also marks that start in the log: onCreate cannot see what triggered the process start,
// so this onReceive line — which the platform always delivers after onCreate — is the only
// way to tell in the log that the start was boot-triggered.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        Logger.i("BootReceiver", "onReceive action=${intent.action}")
    }
}
