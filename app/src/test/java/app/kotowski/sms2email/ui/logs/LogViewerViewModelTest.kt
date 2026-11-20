package app.kotowski.sms2email.ui.logs

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.util.LogBuffer
import app.kotowski.sms2email.util.LogFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogViewerViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // Runs the writer task inline so the file effects are deterministic without waiting.
    private val directExecutor = Executor { it.run() }

    private lateinit var appContext: Context
    private val prefs = mock<EncryptedPrefs>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        appContext = ApplicationProvider.getApplicationContext()
        LogBuffer.clear()
        whenever(prefs.load()).thenReturn(AppSettings())
    }

    @After
    fun tearDown() {
        LogBuffer.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(): LogViewerViewModel = LogViewerViewModel(appContext, prefs)

    @Test
    fun `init collects from refreshTrigger`() {
        val vm = viewModel()
        testDispatcher.scheduler.runCurrent()
        LogBuffer.addInfo("InitTag", "triggered")
        testDispatcher.scheduler.runCurrent()
        assertTrue(vm.entries.value.any { it.message == "triggered" })
    }

    @Test
    fun `log to file defaults to off`() {
        val vm = viewModel()
        assertFalse(vm.logToFileEnabled)
    }

    @Test
    fun `log to file reads the stored setting`() {
        whenever(prefs.load()).thenReturn(AppSettings(logToFileEnabled = true))
        val vm = viewModel()
        assertTrue(vm.logToFileEnabled)
    }

    @Test
    fun `entries is seeded from the buffer`() {
        LogBuffer.addInfo("Tag", "first")

        val vm = viewModel()

        assertEquals(listOf(LogBuffer.entries.first()), vm.entries.value)
    }

    @Test
    fun `entries are emitted latest first`() {
        LogBuffer.addInfo("Tag", "oldest")
        LogBuffer.addInfo("Tag", "newest")

        val vm = viewModel()

        assertEquals(listOf("newest", "oldest"), vm.entries.value.map { it.message })
    }

    @Test
    fun `entries update when the refresh trigger fires`() {
        val vm = viewModel()
        testDispatcher.scheduler.runCurrent()

        LogBuffer.addInfo("Tag", "second")
        testDispatcher.scheduler.runCurrent()

        assertTrue(vm.entries.value.any { it.message == "second" })
    }

    @Test
    fun `clear wipes the buffer and the state`() {
        LogBuffer.addInfo("Tag", "doomed")
        val vm = viewModel()
        testDispatcher.scheduler.runCurrent()

        vm.clear()
        testDispatcher.scheduler.runCurrent()

        assertTrue(LogBuffer.entries.isEmpty())
        assertTrue(vm.entries.value.isEmpty())
    }

    @Test
    fun `exportToUri writes the full log file to a writable uri`() {
        val logFile = File.createTempFile("logbuffer", ".log").apply { delete() }
        LogBuffer.init(LogFileStore(logFile), directExecutor)
        LogBuffer.addInfo("ExportTag", "line one")
        LogBuffer.addInfo("ExportTag", "line two")

        val target = File.createTempFile("logexport", ".txt")
        assertTrue(runBlocking { viewModel().exportToUri(Uri.fromFile(target)) })
        assertEquals(LogBuffer.exportText(), target.readText())
        assertTrue(target.readText().contains("line one"))
        assertTrue(target.readText().contains("line two"))
    }

    @Test
    fun `exportToUri returns false when the write to the provider stream fails`() {
        val logFile = File.createTempFile("logbuffer", ".log").apply { delete() }
        LogBuffer.init(LogFileStore(logFile), directExecutor)
        LogBuffer.addInfo("ExportTag", "line one")

        val failingUri = Uri.parse("content://failing/provider/doc")
        shadowOf(appContext.contentResolver).registerOutputStream(
            failingUri,
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("simulated provider failure")
            },
        )

        assertFalse(runBlocking { viewModel().exportToUri(failingUri) })
    }
}
