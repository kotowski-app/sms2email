package app.kotowski.sms2email.ui.logs

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.util.LogBuffer
import app.kotowski.sms2email.util.LogEntry
import app.kotowski.sms2email.util.Logger
import app.kotowski.sms2email.util.writeExport
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

// The screen's door to the LogBuffer singleton: the entries state mirrors the
// buffer (seeded once, re-emitted on every refresh trigger), the actions run
// here, so the composable holds no direct buffer reference.
@HiltViewModel
class LogViewerViewModel
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        prefs: EncryptedPrefs,
    ) : ViewModel() {
        // Reversed to newest-first: the screen renders the list as-is, and copy follows the
        // same on-screen order (the file export writes the log file chronologically). The
        // buffer itself stays chronological.
        private val _entries = MutableStateFlow(LogBuffer.entries.reversed())

        init {
            viewModelScope.launch {
                LogBuffer.refreshTrigger.collect { _entries.value = LogBuffer.entries.reversed() }
            }
        }

        val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

        // The banner's caps, read from the single config source so the UI never hardcodes them.
        val maxEntries: Int = AppConfig.LOG_MAX_ENTRIES

        // The committed log-to-file setting: the save button's availability. Read once like
        // the History screen reads the cleanup settings — the screen is recreated on
        // navigation, so a live flow is not needed.
        private val logSettings: AppSettings = prefs.load()
        val logToFileEnabled: Boolean = logSettings.logToFileEnabled

        fun clear() {
            LogBuffer.clear()
        }

        // The full-file read (up to the 256KB cap) and the write to the picked document
        // (a Binder call into the provider) both happen on IO, never the main thread.
        suspend fun exportToUri(uri: Uri): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    writeExport(appContext, uri, LogBuffer.exportText())
                } catch (e: Exception) {
                    Logger.e("LogViewer", "Log export failed: ${e.message}")
                    false
                }
            }
    }
