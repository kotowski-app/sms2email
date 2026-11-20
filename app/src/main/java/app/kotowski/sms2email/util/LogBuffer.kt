package app.kotowski.sms2email.util

import app.kotowski.sms2email.AppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors

data class LogEntry(
    val timestamp: String,
    val level: String,
    val tag: String,
    val message: String,
) {
    override fun toString(): String = "[$timestamp] $level $tag: $message"
}

object LogBuffer {
    private val _entries = CopyOnWriteArrayList<LogEntry>()
    private val _refreshTrigger = MutableStateFlow(0L)
    private val bufferLock = Any()

    // Persistence wiring: once a store is attached, every entry is also appended there on
    // a dedicated writer thread (never the caller's), and the file's tail loads back in.
    // @Volatile: the "log to file" setting attaches and detaches the store at runtime
    // (the disable detaches from the writer thread), while every add() reads it.
    @Volatile
    private var fileStore: LogFileStore? = null

    @Volatile
    private var fileWriter: Executor? = null

    val entries: List<LogEntry>
        get() = _entries.toList()

    val refreshTrigger: StateFlow<Long> = _refreshTrigger.asStateFlow()

    // Attaches [store] behind the in-memory buffer and loads its tail back in, so logs
    // survive process death. Called once from Application.onCreate; tests may re-wire.
    fun init(
        store: LogFileStore,
        executor: Executor = defaultWriter(),
    ) {
        fileWriter = executor
        attach(store)
    }

    // The "log to file" setting's runtime re-wiring: enabling attaches a fresh store and
    // backfills the in-memory buffer into it (LogFileStore.writeAll), so the export (the
    // file) always holds what the viewer shows. The attach and the backfill are ONE task
    // on the single-thread writer (reused, so an off/on/off cycle never leaks a thread),
    // so they serialize with the in-flight append tasks: an append queued before this
    // one still sees the old store (its entry is in the buffer, hence in the backfill's
    // snapshot, and lands in the file exactly once), one queued after sees the new store
    // (appended after the backfill, chronological order preserved).
    // The attach's tail load is a no-op in this path: the file is always empty or absent
    // when the task runs — the enable only happens on an off→on toggle, the process-start
    // off→on deletes a leftover file (Sms2EmailApp.logFileStore), and a runtime off-toggle
    // queues the deletion on the same FIFO writer, so it runs first (the buffer keeps
    // its entries through the disable anyway, so there is nothing to restore).
    // Residual edge, documented only: a buffer add landing inside the microsecond window
    // while this task runs can appear twice in the file (backfill plus its own append);
    // the file is best-effort (see writeToStore), no loss is possible.
    fun enableFileStore(
        file: File,
        maxBytes: Int,
    ) {
        val store = LogFileStore(file, maxBytes)
        fileWriter = fileWriter ?: defaultWriter()
        writeToStore {
            attach(store)
            store.writeAll(_entries.map { it.toString() })
        }
    }

    // Detaches behind the clear, both on the single-thread writer: an append queued before
    // the clear is erased by it, one queued after sees the detached store and is skipped,
    // so the file can never respawn with a ghost line. The identity check keeps a pending
    // disable from detaching a store a newer enable already attached.
    fun disableFileStore() {
        val store = fileStore ?: return
        writeToStore {
            store.clear()
            if (fileStore === store) {
                fileStore = null
            }
        }
    }

    // The committed resize (the setting's smaller/larger size): the cut of an over-cap
    // file runs on the writer thread — a multi-MB read-rewrite must not block the caller,
    // and queueing it serializes the cut with the in-flight appends.
    fun updateFileCap(maxBytes: Int) {
        writeToStore { fileStore?.applyCap(maxBytes) }
    }

    private fun attach(store: LogFileStore) {
        fileStore = store
        // Prepend, not append: the loaded tail is older history, while entries logged before
        // the attach (the start-up setLevel meta line) are newer and never reach the file.
        _entries.addAll(0, store.loadTail(AppConfig.LOG_MAX_ENTRIES).mapNotNull { lineToEntry(it) })
        _refreshTrigger.update { it + 1 }
    }

    fun add(
        level: String,
        tag: String,
        message: String,
    ) {
        val entry =
            LogEntry(
                timestamp = DateUtil.formatLogTimestamp(System.currentTimeMillis()),
                level = level,
                tag = tag,
                // "\\\\n": the regex replacement string treats backslash as an escape, so a
                // single "\\n" would collapse to a bare "n" and lose the line boundary.
                message = truncate(message.replace(newlines, "\\\\n")),
            )
        // The add and the trim are one critical section: two concurrent adds must not both
        // see the over-cap size and both remove an entry (the buffer would end under the
        // cap). The while loop also drains any pre-existing over-cap backlog.
        synchronized(bufferLock) {
            _entries.add(entry)
            while (_entries.size > AppConfig.LOG_MAX_ENTRIES) {
                _entries.removeAt(0)
            }
        }
        _refreshTrigger.update { it + 1 }
        writeToStore { fileStore?.append(entry.toString()) }
    }

    // Wipes the in-memory buffer and the log file. The file clear is queued behind any
    // in-flight appends on the single-thread writer, so an entry logged before this call
    // is erased from the file too (it can never survive the clear as a ghost line), while
    // an entry logged after it is appended after the clear and is never lost.
    fun clear() {
        _entries.clear()
        _refreshTrigger.update { it + 1 }
        val store = fileStore ?: return
        writeToStore { store.clear() }
    }

    // What the export writes: the app's full log file, which holds more history than the
    // 500-entry buffer (and the screen) shows; empty when no store is attached or the
    // file was just cleared.
    fun exportText(): String = fileStore?.readAll().orEmpty()

    // Logging must never take the caller down, even if the writer thread is gone. The
    // in-task catch matters too: a failed append (disk fault, permission) escaping the
    // writer thread would be uncaught and kill the whole process, so the file stays
    // best-effort and the in-memory buffer keeps working.
    private fun writeToStore(block: () -> Unit) {
        val writer = fileWriter ?: return
        try {
            writer.execute {
                try {
                    block()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    fun addDebug(
        tag: String,
        message: String,
    ) = add("D", tag, message)

    fun addInfo(
        tag: String,
        message: String,
    ) = add("I", tag, message)

    fun addWarn(
        tag: String,
        message: String,
    ) = add("W", tag, message)

    fun addError(
        tag: String,
        message: String,
    ) = add("E", tag, message)

    // A throwable without a message (e.g. NullPointerException()) would otherwise log a
    // literal ": null"; the class name keeps the line informative.
    fun addError(
        tag: String,
        message: String,
        throwable: Throwable,
    ) = add("E", tag, "$message: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}")

    // Line terminators — including the Unicode ones (U+0085, U+2028, U+2029), which the
    // reload regex's "." cannot match — would split or break the one-line-per-entry format;
    // the literal "\n" escape keeps the entry a single line in the buffer, the file, and the export.
    private val newlines = Regex("[\r\n\u0085\u2028\u2029]+")

    private fun truncate(message: String): String =
        if (message.length <= AppConfig.LOG_MAX_MESSAGE_LENGTH) {
            message
        } else {
            message.take(AppConfig.LOG_MAX_MESSAGE_LENGTH) + "…"
        }

    // The exact inverse of LogEntry.toString; a torn line from a mid-append crash simply
    // does not match and is skipped.
    private val entryLine = Regex("""^\[([^\]]+)\] ([DIWE]) (\S+): (.*)$""")

    internal fun lineToEntry(line: String): LogEntry? =
        entryLine.matchEntire(line)?.let { match ->
            LogEntry(
                timestamp = match.groupValues[1],
                level = match.groupValues[2],
                tag = match.groupValues[3],
                message = match.groupValues[4],
            )
        }

    private fun defaultWriter(): Executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "log-file-writer").apply { isDaemon = true }
        }
}
