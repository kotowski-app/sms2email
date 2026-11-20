package app.kotowski.sms2email.util

import app.kotowski.sms2email.AppConfig
import java.io.File
import java.io.RandomAccessFile

// Size-capped rolling log file: one line per entry. Plain text on purpose — the log is
// human-readable and shareable as-is, and a process death mid-append loses at most the
// in-flight line; everything already appended survives restarts and crashes.
class LogFileStore(
    private val file: File,
    private var maxBytes: Int = AppConfig.LOG_FILE_MAX_BYTES,
    private val tailBytes: Int = AppConfig.LOG_FILE_TAIL_BYTES,
) {
    private val lock = Any()

    fun append(line: String) {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            if (file.length() > maxBytes) {
                // Clamped to the cap: with a sub-tail cap the full tail would outlive the cap
                // it is meant to enforce, so the store holds its own invariant.
                rotate(minOf(tailBytes, maxBytes))
            }
            file.appendText("$line\n")
        }
    }

    // The committed cap change (the "log to file" setting's resize): the new cap applies
    // to future growth at once, and a file already over it is cut to its last [bytes]
    // bytes right away (the same line-boundary cut as the overflow rotation, which keeps
    // only the 64 KB tail).
    fun applyCap(bytes: Int) {
        synchronized(lock) {
            maxBytes = bytes
            if (file.exists() && file.length() > bytes) {
                rotate(bytes)
            }
        }
    }

    // Truncates the file to its last [tailSize] bytes instead of wiping the whole history.
    // The cut starts at the first line boundary inside the tail, so a torn first line never
    // survives and the one-line-per-entry format (and the reload's line parsing) stays
    // intact. The file is bounded by maxBytes plus one line, so reading it whole is cheap.
    private fun rotate(tailSize: Int) {
        val bytes = file.readBytes()
        val tail = bytes.copyOfRange(maxOf(0, bytes.size - tailSize), bytes.size)
        val firstBreak = tail.indexOf(NEWLINE)
        val kept = if (firstBreak >= 0) tail.copyOfRange(firstBreak + 1, tail.size) else tail
        file.writeBytes(kept)
    }

    // The last [maxLines] lines in file order; empty when the file does not exist. The
    // exists check runs under the lock, so a concurrent clear cannot delete the file
    // between the check and the read. A file beyond the read window is only read from
    // the window's start (random access), so the startup tail load never pulls a
    // full-cap file into memory on the main thread.
    fun loadTail(maxLines: Int): List<String> =
        synchronized(lock) {
            if (!file.exists()) {
                emptyList()
            } else {
                val size = file.length()
                val window = tailWindowBytes(maxLines)
                val text =
                    if (size <= window) {
                        file.readText()
                    } else {
                        RandomAccessFile(file, "r").use { access ->
                            access.seek(size - window.toLong())
                            val bytes = ByteArray(window)
                            access.readFully(bytes)
                            String(bytes)
                        }
                    }
                // lineSequence() (what this stdlib's lines() is) yields an element for a
                // trailing terminator, so the trailing empty elements are dropped.
                val lines = text.lineSequence().toList().dropLastWhile { it.isEmpty() }
                // Beyond the window the first line of the read is torn: drop it (the window
                // holds one extra line's worth of bytes, so [maxLines] whole lines remain).
                (if (size > window) lines.drop(1) else lines).takeLast(maxLines)
            }
        }

    // Room for [maxLines] whole lines plus the one torn line the window start can cut;
    // bounded by the per-line byte cap, so the read stays small whatever the file's cap.
    private fun tailWindowBytes(maxLines: Int): Int = (maxLines + 1) * AppConfig.LOG_MAX_LINE_BYTES

    // The whole file, empty when it does not exist; read under the same lock as appends and
    // clear, so the snapshot is consistent with the writer thread.
    fun readAll(): String =
        synchronized(lock) {
            if (file.exists()) {
                file.readText()
            } else {
                ""
            }
        }

    fun clear() {
        synchronized(lock) {
            file.delete()
        }
    }

    // Seeds an empty (or absent) file with the in-memory history: the "log to file"
    // enable lands while the buffer holds entries that never reached a file, so without
    // this backfill the export would be empty while the viewer shows entries. A
    // non-empty file is never overwritten (defensive — the enable path always finds an
    // empty or absent file), and the same cap-cut as the overflow rotation runs after
    // the write, because the buffer can hold 500 entries of ~2.8 KB, past the default
    // 256 KB cap. Under the same lock as the appends and the clear, so the empty check
    // and the write are atomic against both.
    fun writeAll(lines: Collection<String>) {
        synchronized(lock) {
            if (file.exists() && file.length() > 0) {
                return
            }
            file.parentFile?.mkdirs()
            lines.forEach { file.appendText("$it\n") }
            if (file.length() > maxBytes) {
                rotate(minOf(tailBytes, maxBytes))
            }
        }
    }
}

private val NEWLINE: Byte = '\n'.code.toByte()
