package app.kotowski.sms2email.util

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogFileStoreTest {
    private fun tempFile(): File = File.createTempFile("logstore", ".log").apply { delete() }

    @Test
    fun `append writes the line to the file`() {
        val file = tempFile()
        LogFileStore(file).append("line one")
        assertEquals(listOf("line one"), file.readLines())
    }

    @Test
    fun `appends keep lines in order`() {
        val file = tempFile()
        val store = LogFileStore(file)
        repeat(5) { store.append("line $it") }
        assertEquals((0 until 5).map { "line $it" }, file.readLines())
    }

    @Test
    fun `append creates the missing parent directory`() {
        val base = File.createTempFile("logstore", ".dir").apply { delete() }
        val file = File(base, "nested/deep/store.log")
        LogFileStore(file).append("line")
        assertTrue(file.readLines().contains("line"))
    }

    @Test
    fun `loadTail returns the last lines in file order`() {
        val file = tempFile()
        val store = LogFileStore(file)
        repeat(10) { store.append("line $it") }
        assertEquals(listOf("line 8", "line 9"), store.loadTail(2))
        assertEquals((0 until 10).map { "line $it" }, store.loadTail(50))
    }

    @Test
    fun `loadTail is empty for a missing file`() {
        assertTrue(LogFileStore(tempFile()).loadTail(10).isEmpty())
    }

    @Test
    fun `readAll returns the whole file content`() {
        val file = tempFile()
        val store = LogFileStore(file)
        store.append("line one")
        store.append("line two")
        assertEquals("line one\nline two\n", store.readAll())
    }

    @Test
    fun `readAll is empty for a missing file`() {
        assertEquals("", LogFileStore(tempFile()).readAll())
    }

    @Test
    fun `writeAll seeds an absent file with the given lines in order`() {
        val file = tempFile()
        val store = LogFileStore(file)
        store.writeAll(listOf("line one", "line two", "line three"))
        assertEquals("line one\nline two\nline three\n", store.readAll())
    }

    @Test
    fun `writeAll on a non-empty file is a no-op`() {
        val file = tempFile()
        val store = LogFileStore(file)
        store.append("existing")
        store.writeAll(listOf("line one", "line two"))
        assertEquals(listOf("existing"), file.readLines())
    }

    @Test
    fun `writeAll over-cap content is cut to the tail like the overflow rotation`() {
        // 10 lines of 12 bytes (120 total) cross the 64-byte cap: the post-write cut
        // keeps the last 32 bytes at a line boundary (lines 8..9).
        val file = tempFile()
        val store = LogFileStore(file, maxBytes = 64, tailBytes = 32)
        store.writeAll((0 until 10).map { "line-$it-xxxx" })
        assertEquals(listOf("line-8-xxxx", "line-9-xxxx"), file.readLines())
    }

    @Test
    fun `clear removes the file`() {
        val file = tempFile()
        val store = LogFileStore(file)
        store.append("line")
        store.clear()
        assertTrue(!file.exists())
        assertTrue(store.loadTail(10).isEmpty())
    }

    @Test
    fun `appending past the size cap rotates to the tail instead of deleting the file`() {
        // repeat(11) appends lines 0..10 (each 12 bytes, line-10 is 13). The file passes
        // the 64-byte cap after the 6th and 10th appends, so the 7th and 11th rotate to
        // the 32-byte tail cut at a line boundary (lines 4..5, then 8..9) before landing.
        val file = tempFile()
        val store = LogFileStore(file, maxBytes = 64, tailBytes = 32)
        repeat(11) { store.append("line-$it-xxxx") }
        assertEquals(listOf("line-8-xxxx", "line-9-xxxx", "line-10-xxxx"), file.readLines())
    }

    @Test
    fun `rotation starts at a line boundary so no torn line survives`() {
        val file = tempFile()
        val store = LogFileStore(file, maxBytes = 64, tailBytes = 33)
        repeat(11) { store.append("line-$it-xxxx") }
        val lines = file.readLines()
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.all { it.startsWith("line-") }, "every surviving line must be whole: $lines")
    }

    @Test
    fun `rotation with a tail bigger than the file still drops the oldest lines`() {
        val file = tempFile()
        val store = LogFileStore(file, maxBytes = 32)
        store.append("first")
        store.append("x".repeat(40))
        store.append("last")
        val lines = file.readLines()
        assertTrue(!lines.contains("first"), "the old content must be rotated out: $lines")
        assertEquals("last", lines.last())
    }

    @Test
    fun `the file never grows past the cap plus one line`() {
        val file = tempFile()
        val store = LogFileStore(file, maxBytes = 64, tailBytes = 32)
        repeat(50) { store.append("line-$it-xxxx") }
        val longestLine = ("line-49-xxxx" + "\n").length
        assertTrue(file.length() <= 64 + longestLine, "file grew to ${file.length()} bytes")
    }

    @Test
    fun `applyCap cuts an over-cap file to its last bytes`() {
        // 10 lines of 12 bytes: a 50-byte cap starts mid-line 5, the torn fragment is
        // dropped, the file keeps whole lines 6..9 (48 bytes, under the cap).
        val file = tempFile()
        val store = LogFileStore(file, maxBytes = 1024, tailBytes = 16)
        repeat(10) { store.append("line-$it-xxxx") }
        store.applyCap(50)
        assertEquals(listOf("line-6-xxxx", "line-7-xxxx", "line-8-xxxx", "line-9-xxxx"), file.readLines())
    }

    @Test
    fun `applyCap with a cut exactly on a line boundary drops the first kept line too`() {
        // The same over-truncation as the overflow rotation (the cut at a line start
        // drops the complete first line of the tail): a 48-byte cap keeps lines 7..9.
        val file = tempFile()
        val store = LogFileStore(file, maxBytes = 1024, tailBytes = 16)
        repeat(10) { store.append("line-$it-xxxx") }
        store.applyCap(48)
        assertEquals(listOf("line-7-xxxx", "line-8-xxxx", "line-9-xxxx"), file.readLines())
    }

    @Test
    fun `applyCap under the current size leaves the file untouched`() {
        val file = tempFile()
        val store = LogFileStore(file, maxBytes = 1024, tailBytes = 16)
        repeat(3) { store.append("line-$it-xxxx") }
        store.applyCap(512)
        assertEquals((0 until 3).map { "line-$it-xxxx" }, file.readLines())
    }

    @Test
    fun `applyCap enforces the new cap on the next append`() {
        // The file is under the new cap (no cut), but appends past it rotate under the
        // new cap, not the old 1024: the file must stay near 48 bytes.
        val file = tempFile()
        val store = LogFileStore(file, maxBytes = 1024, tailBytes = 16)
        repeat(3) { store.append("line-$it-xxxx") }
        store.applyCap(48)
        repeat(3) { store.append("after-$it-xxxx") }
        val lines = file.readLines()
        assertTrue(lines.all { it.startsWith("line-") || it.startsWith("after-") }, "only whole lines: $lines")
        assertTrue(file.length() <= 48 + 13, "the file must stay near the new cap: ${file.length()}")
        assertEquals("after-2-xxxx", lines.last())
    }

    @Test
    fun `natural overflow rotation still keeps the configured tail`() {
        // Regression guard: the append-time rotation cuts to tailBytes (the small tail),
        // never to the full cap, whatever applyCap set earlier.
        val file = tempFile()
        val store = LogFileStore(file, maxBytes = 64, tailBytes = 32)
        store.applyCap(64)
        repeat(11) { store.append("line-$it-xxxx") }
        assertEquals(listOf("line-8-xxxx", "line-9-xxxx", "line-10-xxxx"), file.readLines())
    }

    @Test
    fun `overflow rotation clamps the tail to the cap when the cap is under the tail`() {
        // A sub-tail cap (unreachable through the preset-only write paths) must not pin
        // the file at the full tail: the rotation keeps a cap-sized tail instead.
        val file = tempFile()
        val store = LogFileStore(file, maxBytes = 128, tailBytes = 64 * 1024)
        val line = { i: Int -> "line-$i-${"x".repeat(24)}" }
        repeat(50) { store.append(line(it)) }
        val longestLine = (line(49) + "\n").length
        assertTrue(file.length() <= 128 + longestLine, "file grew to ${file.length()} bytes")
        assertEquals(line(49), file.readLines().last())
    }

    @Test
    fun `loadTail of a file beyond the read window returns the last lines intact`() {
        // 40 lines of 1000 bytes (40 KB) exceed the loadTail(2) window (~24 KB): only the
        // window is read, its torn first line is dropped, the last two lines come back
        // whole.
        val file = tempFile()
        val store = LogFileStore(file)
        val lines = (0 until 40).map { "line-$it-".padEnd(999, 'x') }
        lines.forEach { file.appendText("$it\n") }
        assertEquals(lines.takeLast(2), store.loadTail(2))
    }

    @Test
    fun `loadTail drops the first window line when the window starts on a line boundary`() {
        // 39 lines of 1000 bytes plus a 768-byte last line put the window start exactly
        // at a line start: the first (whole) window line is still dropped, and the extra
        // line's worth of window bytes keeps the result at maxLines.
        val file = tempFile()
        val store = LogFileStore(file)
        val lines = (0 until 39).map { "line-$it-".padEnd(999, 'x') } + "line-39-".padEnd(767, 'x')
        lines.forEach { file.appendText("$it\n") }
        assertEquals(lines.takeLast(2), store.loadTail(2))
    }

    @Test
    fun `loadTail decodes multi-byte lines when the window starts mid-character`() {
        // The leading byte shifts the window start into the middle of a two-byte UTF-8
        // sequence: the torn fragment (with its replacement char) is dropped, the rest
        // decodes intact.
        val file = tempFile()
        val store = LogFileStore(file)
        file.appendText("a")
        val lines = (0 until 40).map { "строка-$it-${"я".repeat(489)}" }
        lines.forEach { file.appendText("$it\n") }
        val tail = store.loadTail(2)
        assertEquals(lines.takeLast(2), tail)
        assertTrue(tail.all { !it.contains("\uFFFD") }, "no replacement char may leak: $tail")
    }

    @Test
    fun `applyCap on a non-existent file updates the cap without error`() {
        val file = tempFile()
        val store = LogFileStore(file, maxBytes = 1024, tailBytes = 16)
        store.applyCap(512)
        // The cap is updated, but no file exists yet, so no rotation happens
        store.append("line")
        assertEquals(listOf("line"), file.readLines())
    }

    @Test
    fun `loadTail with zero max lines returns empty list`() {
        val file = tempFile()
        val store = LogFileStore(file)
        store.append("line one")
        store.append("line two")
        assertTrue(store.loadTail(0).isEmpty())
    }

    @Test
    fun `append creates nested parent directories`() {
        val base = File.createTempFile("logstore", ".dir").apply { delete() }
        val file = File(base, "a/b/c/deep.log")
        LogFileStore(file).append("deep line")
        assertEquals(listOf("deep line"), file.readLines())
    }

    @Test
    fun `writeAll creates parent directory if missing`() {
        val base = File.createTempFile("logstore", ".dir").apply { delete() }
        val file = File(base, "nested/write.log")
        val store = LogFileStore(file)
        store.writeAll(listOf("line one", "line two"))
        assertEquals(listOf("line one", "line two"), file.readLines())
    }

    @Test
    fun `writeAll on an empty file (length 0) does write the lines`() {
        val file = tempFile()
        file.createNewFile()
        val store = LogFileStore(file)
        store.writeAll(listOf("appears on empty"))
        assertEquals(listOf("appears on empty"), file.readLines())
    }
}
