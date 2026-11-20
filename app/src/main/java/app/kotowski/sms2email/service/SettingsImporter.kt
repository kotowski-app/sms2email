package app.kotowski.sms2email.service

import android.content.Context
import android.net.Uri
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.util.DeviceUtil
import app.kotowski.sms2email.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// The settings import shared by the onboarding wizard and the settings screen: reads the
// picked file (the I/O phase on Dispatchers.IO, see [SettingsExportImport.readSettingsImport]),
// applies the JSON to the prefs ([applyJson]), and reports the result to the caller, which
// toasts it (each screen names its own strings); a cancelled import reports nothing. A
// pick while an import is in flight is dropped (logged), because two imports would
// interleave their prefs writes. The guard lives with this instance (one per ViewModel),
// so an in-flight import survives a recomposition (a rotation) and is cancelled only with
// the ViewModel's entry.
// `open`/`open fun` only so a unit test can override applyJson and reach importPicked's
// apply catch: applyJson swallows its own Exceptions, so the catch is otherwise
// unreachable.
open class SettingsImporter(
    private val context: Context,
    private val prefs: EncryptedPrefs,
    private val onImportSucceeded: () -> Unit = {},
) {
    private var importJob: Job? = null

    // The apply phase: writes the imported config to the prefs, re-checks the auto subject
    // prefix against the device name (a foreign prefix disables the auto flag), and runs
    // [onImportSucceeded] so the form and the caller re-read the imported prefs. A file
    // that does not parse or validate is a failed import, not a crash.
    open fun applyJson(json: String): Boolean =
        try {
            SettingsExportImport.importFromJson(prefs, json)
            val loaded = prefs.load()
            // The stored prefix is capped (the form's bound), so the check compares
            // against the capped name: an over-long device name must not read as a
            // foreign prefix and disable the flag on an own-device import.
            if (
                loaded.autoSubjectPrefix &&
                loaded.subjectPrefix != DeviceUtil.getDeviceName(context).take(AppConfig.SUBJECT_PREFIX_MAX_LENGTH)
            ) {
                prefs.save(loaded.copy(autoSubjectPrefix = false))
            }
            onImportSucceeded()
            true
        } catch (e: Exception) {
            // No e.message: a parser error can echo the file's content (the imported
            // addresses) and an I/O error can carry the picked file name.
            Logger.e("SettingsImport", "Import failed: ${e.javaClass.simpleName}")
            false
        }

    // Starts the import on [scope] (the caller's ViewModel scope): the I/O phase runs on
    // Dispatchers.IO, the apply ([applyJson]) and the result report back on [scope]'s
    // dispatcher (main, so the caller's state updates are direct), and a cancellation
    // reports nothing. A too-large file is a failed import. Returns the started import, or
    // null while one is in flight.
    fun importPicked(
        scope: CoroutineScope,
        uri: Uri,
        onResult: (Boolean) -> Unit,
    ): Job? {
        if (importJob?.isActive == true) {
            Logger.w("SettingsImport", "Import ignored: another import is in flight")
            return null
        }
        importJob =
            scope.launch {
                val success =
                    try {
                        when (val read = SettingsExportImport.readSettingsImport(context, uri)) {
                            is ImportReadOutcome.Json -> applyJson(read.json)
                            ImportReadOutcome.TooLarge -> false
                            ImportReadOutcome.Failed -> false
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Class name only: e.message can echo the file's content.
                        Logger.e("SettingsImport", "Import failed while applying json: ${e.javaClass.simpleName}")
                        false
                    }
                onResult(success)
            }
        return importJob
    }
}
