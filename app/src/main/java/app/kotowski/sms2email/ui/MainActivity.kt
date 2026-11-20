package app.kotowski.sms2email.ui

import android.os.Bundle
import android.os.Process
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.kotowski.sms2email.ThemeMode
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.data.prefs.ThemePreference
import app.kotowski.sms2email.di.PrefsEntryPoint
import app.kotowski.sms2email.ui.components.EncryptedPrefsErrorDialog
import app.kotowski.sms2email.ui.theme.Sms2EmailTheme
import app.kotowski.sms2email.util.DebuggerDetector
import app.kotowski.sms2email.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (DebuggerDetector.isBeingDebugged(this)) {
            Process.killProcess(Process.myPid())
            return
        }

        enableEdgeToEdge()

        // Resolved through the EntryPoint instead of field injection: Hilt injects fields before
        // this body runs, so a broken keystore would kill the process with nothing to catch it.
        // A failure shows the settings-error dialog instead of a crash loop. The startup reads
        // (theme, scam warning, onboarding) sit inside the guard too: a corrupt value there is
        // the same failure, and would otherwise crash after a successful-looking construction.
        val entryPoint =
            EntryPointAccessors.fromApplication(applicationContext, PrefsEntryPoint::class.java)
        val startup =
            try {
                val prefs = entryPoint.prefs()
                Startup(
                    prefs = prefs,
                    themePreference = entryPoint.themePreference(),
                    scamWarningDismissed = prefs.scamWarningDismissed,
                    onboardingComplete = prefs.isOnboardingComplete,
                )
            } catch (e: Exception) {
                Logger.e("MainActivity", "EncryptedPrefs unavailable, showing failure screen: ${e.message}", e)
                null
            }

        if (startup == null) {
            // The stored theme lives in the unreadable prefs, so the failure screen uses the
            // system palette. Closing exits the app: there is nothing else it can do.
            setContent {
                Sms2EmailTheme(themeMode = ThemeMode.SYSTEM) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        EncryptedPrefsErrorDialog(
                            onClose = { Process.killProcess(Process.myPid()) },
                        )
                    }
                }
            }
            return
        }

        setContent {
            // The theme mode flows from the shared holder: a pick in Settings recomposes the
            // root with the new palette right away, no restart.
            val themeMode by startup.themePreference.mode.collectAsState()
            Sms2EmailTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AppNavGraph(
                        onboardingComplete = startup.onboardingComplete,
                        scamWarningDismissed = startup.scamWarningDismissed,
                        onDismissScamWarning = { startup.prefs.scamWarningDismissed = true },
                    )
                }
            }
        }
    }
}

// The values read at startup, held together so the guard can fail them as one: a corrupt value
// in any of them is the same "settings unavailable" outcome, not a per-read crash.
private data class Startup(
    val prefs: EncryptedPrefs,
    val themePreference: ThemePreference,
    val scamWarningDismissed: Boolean,
    val onboardingComplete: Boolean,
)
