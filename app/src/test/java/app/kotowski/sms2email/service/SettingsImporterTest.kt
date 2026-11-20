package app.kotowski.sms2email.service

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.provider.OpenableColumns
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.LogLevel
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.data.prefs.SmtpProtocol
import app.kotowski.sms2email.testing.TestEmail
import app.kotowski.sms2email.util.LogBuffer
import app.kotowski.sms2email.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Robolectric: the import validates addresses with android.util.Patterns, which
// needs the Android runtime. The I/O phase is tested in SettingsExportImportFileTest;
// here the in-flight guard, the read/apply-to-success mapping, and the apply
// contract (the import runs on the caller's scope, the ViewModel's in production).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SettingsImporterTest {
    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var appContext: Context

    @Mock private lateinit var resolver: ContentResolver

    @Mock private lateinit var cursor: Cursor

    private val uri = Uri.parse("content://com.android.documentsui/documents/pick")

    private var stored = AppSettings()

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        stored = AppSettings()
        whenever(prefs.load()).thenAnswer { stored }
        doAnswer { inv ->
            stored = inv.getArgument<AppSettings>(0)
            null
        }.whenever(prefs).save(any())
        // DeviceUtil.getDeviceName falls back to the app name when the device name is unset
        whenever(appContext.getString(R.string.app_name)).thenReturn("SMS to Email")
        whenever(appContext.contentResolver).thenReturn(resolver)
    }

    // The settings the import stores from FULL_IMPORT_JSON (the rest equals the defaults).
    private fun importedSettings() =
        AppSettings(
            smtpHost = "smtp.example.com",
            smtpPort = 465,
            smtpProtocol = SmtpProtocol.IMPLICIT_TLS,
            smtpUsername = TestEmail.SELF,
            destEmail = "dest@example.com",
            senderEmail = "sender@example.com",
            autoCleanupRetentionDays = 365,
            usePduTimestamp = true,
        )

    @Test
    fun `applyJson with a valid file writes the prefs, runs the hook and reports success`() {
        var hooks = 0
        val importer = SettingsImporter(appContext, prefs) { hooks++ }

        assertTrue(importer.applyJson(FULL_IMPORT_JSON))

        assertEquals(1, hooks)
        assertEquals(importedSettings(), stored)
    }

    @Test
    fun `applyJson with invalid json reports failure without writing the prefs`() {
        Logger.setLevel(LogLevel.DEBUG)
        var hooks = 0
        val importer = SettingsImporter(appContext, prefs) { hooks++ }

        assertFalse(importer.applyJson("not json"))

        assertEquals(0, hooks)
        assertEquals(AppSettings(), stored)
        // Pinned: a parser error can echo the file's content; the log keeps the class name only.
        val entry = LogBuffer.entries.last()
        assertEquals("SettingsImport", entry.tag)
        assertEquals("Import failed: JSONException", entry.message)
    }

    @Test
    fun `applyJson with an auto prefix that is not the device name disables the auto flag`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoSubjectPrefix = true, subjectPrefix = "foreign"))
        val importer = SettingsImporter(appContext, prefs)

        assertTrue(importer.applyJson(FULL_IMPORT_JSON))

        // The import stores the file's value; the device-name check re-writes the flag off.
        verify(prefs).save(importedSettings())
        verify(prefs).save(AppSettings(subjectPrefix = "foreign"))
    }

    @Test
    fun `applyJson keeps the auto flag when the imported prefix is the device name`() {
        whenever(appContext.getString(R.string.app_name)).thenReturn("My Device")
        val importer = SettingsImporter(appContext, prefs)

        assertTrue(importer.applyJson(importJsonWithPrefix("My Device")))

        assertTrue(stored.autoSubjectPrefix)
        // No device-name re-write: the import's own save is the only one.
        verify(prefs, times(1)).save(any())
    }

    @Test
    fun `applyJson keeps the auto flag when the device name is over the prefix cap`() {
        // The stored prefix is capped at the form's bound, so the check must compare
        // against the capped name: an own-device import must not disable the flag.
        whenever(appContext.getString(R.string.app_name)).thenReturn("d".repeat(AppConfig.SUBJECT_PREFIX_MAX_LENGTH + 10))
        val importer = SettingsImporter(appContext, prefs)

        assertTrue(importer.applyJson(importJsonWithPrefix("d".repeat(AppConfig.SUBJECT_PREFIX_MAX_LENGTH))))

        assertTrue(stored.autoSubjectPrefix)
        verify(prefs, times(1)).save(any())
    }

    // FULL_IMPORT_JSON with the given subject prefix and the auto flag on: the shape of an
    // export from a device whose stored (capped) prefix is that value.
    private fun importJsonWithPrefix(prefix: String) =
        FULL_IMPORT_JSON
            .replace("\"subject_prefix\":\"\"", "\"subject_prefix\":\"$prefix\"")
            .replace("\"auto_subject_prefix\":false", "\"auto_subject_prefix\":true")

    // The production scope is the ViewModel's (Main.immediate); the tests drive it the
    // same way: the I/O phase hops to a real IO thread, the resumption lands on the main
    // looper, which the wait loops idle until the import completes.
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun awaitImport(job: Job?) {
        assertNotNull(job)
        while (job.isActive) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    // size null means "unknown" (the query returns an empty cursor); openStreamError and
    // nullStream are mutually exclusive with a real json payload.
    private fun stubPick(
        size: Long?,
        json: String? = null,
        openStreamError: Throwable? = null,
        nullStream: Boolean = false,
    ) {
        whenever(
            resolver.query(
                any<Uri>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
            ),
        ).thenReturn(cursor)
        whenever(cursor.moveToFirst()).thenReturn(size != null)
        whenever(cursor.getColumnIndex(OpenableColumns.SIZE)).thenReturn(0)
        whenever(cursor.isNull(0)).thenReturn(false)
        whenever(cursor.getLong(0)).thenReturn(size ?: 0L)
        if (openStreamError != null) {
            doThrow(openStreamError).whenever(resolver).openInputStream(uri)
        } else {
            whenever(resolver.openInputStream(uri)).thenReturn(
                if (nullStream) null else ByteArrayInputStream((json ?: "").toByteArray()),
            )
        }
    }

    @Test
    fun `a too large file is rejected without reading`() {
        stubPick(AppConfig.SETTINGS_IMPORT_MAX_FILE_SIZE + 1, json = "{}")
        val importer = SettingsImporter(appContext, prefs)
        var success: Boolean? = null

        awaitImport(importer.importPicked(scope(), uri) { success = it })

        assertEquals(false, success)
        verify(resolver, never()).openInputStream(uri)
        assertEquals(AppSettings(), stored)
    }

    @Test
    fun `an unknown size stream over the cap is rejected without applying`() {
        stubPick(null, json = "a".repeat(AppConfig.SETTINGS_IMPORT_MAX_FILE_SIZE.toInt() + 1))
        val importer = SettingsImporter(appContext, prefs)
        var success: Boolean? = null

        awaitImport(importer.importPicked(scope(), uri) { success = it })

        assertEquals(false, success)
        verify(resolver).openInputStream(uri)
        assertEquals(AppSettings(), stored)
    }

    @Test
    fun `an unknown size proceeds to the read, applies the file and reports success`() {
        stubPick(null, json = FULL_IMPORT_JSON)
        var hooks = 0
        var success: Boolean? = null
        val importer = SettingsImporter(appContext, prefs) { hooks++ }

        awaitImport(importer.importPicked(scope(), uri) { success = it })

        assertEquals(true, success)
        assertEquals(1, hooks)
        assertEquals(importedSettings(), stored)
    }

    @Test
    fun `a file at the cap is still read`() {
        stubPick(AppConfig.SETTINGS_IMPORT_MAX_FILE_SIZE, json = FULL_IMPORT_JSON)
        val importer = SettingsImporter(appContext, prefs)
        var success: Boolean? = null

        awaitImport(importer.importPicked(scope(), uri) { success = it })

        assertEquals(true, success)
        verify(resolver).openInputStream(uri)
    }

    @Test
    fun `a rejected file reports failure without applying`() {
        stubPick(1024, json = "not json")
        var hooks = 0
        var success: Boolean? = null
        val importer = SettingsImporter(appContext, prefs) { hooks++ }

        awaitImport(importer.importPicked(scope(), uri) { success = it })

        assertEquals(false, success)
        assertEquals(0, hooks)
        assertEquals(AppSettings(), stored)
    }

    @Test
    fun `a missing input stream is rejected as an empty json with a failed result`() {
        stubPick(1024, nullStream = true)
        var hooks = 0
        var success: Boolean? = null
        val importer = SettingsImporter(appContext, prefs) { hooks++ }

        awaitImport(importer.importPicked(scope(), uri) { success = it })

        assertEquals(false, success)
        assertEquals(0, hooks)
        assertEquals(AppSettings(), stored)
    }

    @Test
    fun `a read error reports failure without applying`() {
        stubPick(1024, openStreamError = FileNotFoundException("no stream"))
        val importer = SettingsImporter(appContext, prefs)
        var success: Boolean? = null

        awaitImport(importer.importPicked(scope(), uri) { success = it })

        assertEquals(false, success)
        assertEquals(AppSettings(), stored)
    }

    @Test
    fun `the outcome report runs on the main thread so the caller's state updates are direct`() {
        stubPick(1024, json = FULL_IMPORT_JSON)
        var outcomeLooper: Looper? = null
        val importer = SettingsImporter(appContext, prefs)

        awaitImport(importer.importPicked(scope(), uri) { outcomeLooper = Looper.myLooper() })

        assertEquals(Looper.getMainLooper(), outcomeLooper)
    }

    @Test
    fun `a CancellationException from the read cancels the import without an outcome`() {
        // A CE surfacing from inside the IO phase (here: from the stream's read) must
        // cancel the import, never be folded into a failed outcome.
        stubPick(null, json = "export")
        whenever(resolver.openInputStream(uri)).thenReturn(
            object : InputStream() {
                override fun read(): Int = throw CancellationException("cancelled mid-read")
            },
        )
        val importer = SettingsImporter(appContext, prefs)
        var outcomes = 0
        val job = importer.importPicked(scope(), uri) { outcomes++ }
        assertNotNull(job)
        while (job.isActive) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue(job.isCancelled)
        assertEquals(0, outcomes)
    }

    @Test
    fun `a pick while an import is in flight is dropped`() {
        // The stream blocks its first read until the test releases it: an in-memory stream
        // would finish on the IO thread before the second pick, so the in-flight state the
        // guard must observe would only exist by scheduling luck.
        val release = CountDownLatch(1)
        val source = ByteArrayInputStream(FULL_IMPORT_JSON.toByteArray())
        var blocked = false
        stubPick(null)
        whenever(resolver.openInputStream(uri)).thenReturn(
            object : InputStream() {
                override fun read(): Int {
                    if (!blocked) {
                        blocked = true
                        release.await()
                    }
                    return source.read()
                }
            },
        )
        var hooks = 0
        var outcomes = 0
        val importer = SettingsImporter(appContext, prefs) { hooks++ }

        val first = importer.importPicked(scope(), uri) { outcomes++ }
        val second = importer.importPicked(scope(), uri) { outcomes++ }
        assertNotNull(first)
        assertNull(second)
        release.countDown()
        while (first.isActive) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }

        assertEquals(1, outcomes)
        assertEquals(1, hooks)
        assertEquals(importedSettings(), stored)
    }

    // The applyJson catch block: prefs.save throws after importFromJson has already
    // succeeded (the second save is the autoSubjectPrefix correction).
    @Test
    fun `applyJson catches Exception from prefs save and reports failure`() {
        var saveCount = 0
        doAnswer { inv ->
            saveCount++
            if (saveCount == 2) throw RuntimeException("disk full")
            stored = inv.getArgument<AppSettings>(0)
            null
        }.whenever(prefs).save(any())
        // Force the autoSubjectPrefix re-save: loaded prefix doesn't match device name.
        whenever(prefs.load()).thenReturn(
            importedSettings().copy(autoSubjectPrefix = true, subjectPrefix = "foreign"),
        )

        val importer = SettingsImporter(appContext, prefs)

        assertFalse(importer.applyJson(FULL_IMPORT_JSON))
        // First save from importFromJson succeeded; second (autoSubjectPrefix fix) threw.
        assertEquals(2, saveCount)
    }

    // The query exception is swallowed by querySafely (the size reads as unknown); the
    // unstubbed stream is null, so the empty JSON is rejected in the apply phase.
    @Test
    fun `importPicked handles Failed outcome when the resolver query throws`() {
        whenever(
            resolver.query(
                any<Uri>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
            ),
        ).thenThrow(SecurityException("permission denied"))
        val importer = SettingsImporter(appContext, prefs)
        var success: Boolean? = null

        awaitImport(importer.importPicked(scope(), uri) { success = it })

        assertEquals(false, success)
        assertEquals(AppSettings(), stored)
    }

    @Test
    fun `importPicked catches unexpected Exception from apply phase and reports failure`() {
        Logger.setLevel(LogLevel.DEBUG)
        stubPick(1024, json = FULL_IMPORT_JSON)
        // onImportSucceeded throws inside applyJson's own try, so it lands in applyJson's
        // catch (the class-name-only line), not in importPicked's.
        var importer: SettingsImporter? = null
        importer =
            SettingsImporter(appContext, prefs) {
                if (importer != null) throw RuntimeException("callback error")
            }
        var success: Boolean? = null

        awaitImport(importer.importPicked(scope(), uri) { success = it })

        assertEquals(false, success)
        val entry = LogBuffer.entries.last()
        assertEquals("SettingsImport", entry.tag)
        assertEquals("Import failed: RuntimeException", entry.message)
    }

    // importPicked's own apply catch is defensive (applyJson catches its Exceptions); an
    // overriding subclass is the only way to make it fire.
    @Test
    fun `importPicked logs an escaping applyJson failure with the class name only`() {
        Logger.setLevel(LogLevel.DEBUG)
        stubPick(1024, json = FULL_IMPORT_JSON)
        val importer =
            object : SettingsImporter(appContext, prefs) {
                override fun applyJson(json: String): Boolean = throw RuntimeException("boom")
            }
        var success: Boolean? = null

        awaitImport(importer.importPicked(scope(), uri) { success = it })

        assertEquals(false, success)
        val entry = LogBuffer.entries.last()
        assertEquals("SettingsImport", entry.tag)
        assertEquals("Import failed while applying json: RuntimeException", entry.message)
    }

    @Suppress("ktlint:standard:max-line-length")
    private companion object {
        val FULL_IMPORT_JSON =
            """{"version":1,"smtp_host":"smtp.example.com","smtp_port":465,"smtp_protocol":"IMPLICIT_TLS","smtp_username":"${TestEmail.SELF}","dest_email":"dest@example.com","dest_name":"","sender_email":"sender@example.com","sender_name":"","subject_prefix":"","auto_subject_prefix":false,"forwarding_enabled":true,"contact_resolution":true,"display_sim_cards":false,"auto_cleanup_retention_days":365,"json_email_format":false,"use_pdu_timestamp":true,"dedup_level":"BALANCED"}"""
    }
}
