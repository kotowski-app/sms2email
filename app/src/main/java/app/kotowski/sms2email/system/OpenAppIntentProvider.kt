package app.kotowski.sms2email.system

import android.content.Context
import android.content.Intent

// The notification's "open the app" target, injected so system/ never references
// ui/: the composition root (di/) wires it to the app's main activity.
fun interface OpenAppIntentProvider {
    fun create(context: Context): Intent
}
