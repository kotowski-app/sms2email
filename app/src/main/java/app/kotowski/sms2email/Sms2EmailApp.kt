package app.kotowski.sms2email

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import app.kotowski.sms2email.BuildConfig
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.di.PrefsEntryPoint
import app.kotowski.sms2email.util.DeviceUtil
import app.kotowski.sms2email.util.LanguageHelper
import app.kotowski.sms2email.util.LogBuffer
import app.kotowski.sms2email.util.LogFileStore
import app.kotowski.sms2email.util.Logger
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class Sms2EmailApp : Application() {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        LanguageHelper.applyLocaleToApplicationSafely(newBase)
    }

    override fun onCreate() {
        super.onCreate()
        // Before any other logging, so the first entries this start records are already
        // written through to the file and survive a crash (the setLevel meta line can
        // precede the attach; it never reaches the file).
        logFileStore()?.let { initLoggingSafely(it) }
        // The stored level already applies (logFileStore set it): the line names the
        // running build, so a support export identifies the version without a round-trip.
        Logger.i("Sms2EmailApp", appStartLine())
        Logger.setEnabled(true)
        LanguageHelper.applyStoredAppLanguage(this)
        WorkManager.initialize(
            this,
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build(),
        )
        // Resolved through the EntryPoint instead of field injection: Hilt injects fields in the
        // generated base class before this body runs, so a broken keystore would kill the process
        // with nothing to catch it. A failed construction just skips the background reconciliation.
        // The observer's first collection reconciles both directions at every process start;
        // BootReceiver and AppUpdateReceiver exist only to force such a start at boot and
        // after an in-place update.
        var observerStarted = false
        try {
            val entryPoint = EntryPointAccessors.fromApplication(this, PrefsEntryPoint::class.java)
            // Construct the prefs first: on a broken keystore the construction throws and the
            // observer (which only reacts to prefs changes) is skipped.
            entryPoint.prefs()
            entryPoint.backgroundStateReconciler().start()
            observerStarted = true
        } catch (e: Exception) {
            Logger.e("Sms2EmailApp", "Background state reconciliation unavailable, skipping: ${e.message}", e)
        }
        // End-of-init marker: with "Logging enabled" above it brackets onCreate, a receiver
        // onReceive line after it identifying a boot- or update-triggered start. Info level,
        // so at the default (error) neither marker is recorded.
        Logger.i("Sms2EmailApp", "App initialized, background observer ${if (observerStarted) "running" else "unavailable"}")
    }

    // The log settings for this start, through the same keystore-failing entry point as
    // the reconciler: the picked level applies from the first entry (before the store
    // attach), the "log to file" setting (off by default) decides the store. Off removes
    // a leftover file as a backstop; a broken keystore degrades to off and the default
    // level (errors only) instead of crashing.
    private fun logFileStore(): LogFileStore? =
        try {
            val settings = EntryPointAccessors.fromApplication(this, PrefsEntryPoint::class.java).prefs().load()
            Logger.setLevel(settings.logLevel)
            val logFile = File(filesDir, AppConfig.LOG_FILE_NAME)
            if (settings.logToFileEnabled) {
                LogFileStore(logFile, logFileSizeFor(settings))
            } else {
                logFile.delete()
                null
            }
        } catch (e: Exception) {
            // A handled degradation: the buffer still works, log-to-file stays off; a broken keystore
            // behind it also kills the reconciler below, so the shared root cause keeps its error line there.
            Logger.w("Sms2EmailApp", "Log settings unreadable, log to file stays off: ${e.message}", e)
            null
        }

    companion object {
        // Log persistence is best-effort: an IO fault in the tail load (disk fault,
        // permission, torn-file edge) must not escape Application.onCreate, or it would
        // crash-loop every process start (SMS-, boot- and update-triggered included).
        // Throwable (not Exception): an OOM in the load is an Error and must degrade the
        // same way. The failure is logged to the in-memory buffer and the app continues
        // without a persisted tail.
        fun initLoggingSafely(store: LogFileStore) {
            try {
                LogBuffer.init(store)
            } catch (e: Throwable) {
                // Handled degradation: the in-memory buffer keeps working, only the
                // persisted tail is lost, so this is a warning, not an error.
                Logger.w("Sms2EmailApp", "Log persistence init failed, continuing without it: ${e.message}", e)
            }
        }

        // The engine's read of the stored cap: a value outside the presets (an unknown or
        // tampered import; the UI degrades the same way for its display) degrades to the
        // shipped default, so a non-preset value can never reach the store (a non-positive
        // one would rewrite the file on every line, a huge one would let it grow to ~2 GB).
        internal fun logFileSizeFor(settings: AppSettings): Int =
            settings.logFileSizeBytes.takeIf { it in LogSizePreset.bytes }
                ?: AppConfig.LOG_FILE_MAX_BYTES

        // Factored out like logFileSizeFor (no unit test boots the real Application); the
        // line names the running build, so a support export identifies the version.
        internal fun appStartLine(): String = "app start, version ${DeviceUtil.displayVersion()}, build type ${BuildConfig.BUILD_TYPE}"
    }
}
