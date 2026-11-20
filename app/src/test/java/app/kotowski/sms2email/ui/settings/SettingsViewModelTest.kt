package app.kotowski.sms2email.ui.settings

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.kotowski.sms2email.DedupLevel
import app.kotowski.sms2email.LogLevel
import app.kotowski.sms2email.LogSizePreset
import app.kotowski.sms2email.PeriodPreset
import app.kotowski.sms2email.R
import app.kotowski.sms2email.ThemeMode
import app.kotowski.sms2email.data.filter.SmsFilter
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.data.prefs.SmtpProtocol
import app.kotowski.sms2email.data.prefs.ThemePreference
import app.kotowski.sms2email.data.prefs.isComplete
import app.kotowski.sms2email.service.EmailResult
import app.kotowski.sms2email.service.EmailService
import app.kotowski.sms2email.service.SmtpConfig
import app.kotowski.sms2email.service.SmtpTestRunner
import app.kotowski.sms2email.service.SmtpTester
import app.kotowski.sms2email.service.TestEmailFields
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.system.PermissionManager
import app.kotowski.sms2email.system.PermissionSnapshot
import app.kotowski.sms2email.testing.TestEmail
import app.kotowski.sms2email.testing.stubContextGetString
import app.kotowski.sms2email.testing.withMockedToast
import app.kotowski.sms2email.ui.emailconfig.EmailConfig
import app.kotowski.sms2email.ui.emailconfig.TestRunState
import app.kotowski.sms2email.util.LogBuffer
import app.kotowski.sms2email.util.Logger
import app.kotowski.sms2email.work.BackgroundStateReconciler
import app.kotowski.sms2email.work.ExactWakeArmer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Robolectric: the completeness gate validates the email fields with the platform
// email pattern (EmailUtil), which is unavailable on the plain JVM test classpath.
// sdk 28: below 29 AppCompatDelegate keeps the requested locales in its own static
// state, which the app-language tests round-trip without an activity delegate.
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsViewModelTest {
    @Mock private lateinit var appContext: Context

    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var emailService: EmailService

    @Mock private lateinit var permissionManager: PermissionManager

    @Mock private lateinit var themePreference: ThemePreference

    @Mock private lateinit var backgroundStateReconciler: BackgroundStateReconciler

    @Mock private lateinit var exactWakeArmer: ExactWakeArmer

    @Mock private lateinit var languagePrefs: SharedPreferences

    @Mock private lateinit var languagePrefsEditor: SharedPreferences.Editor

    @Mock private lateinit var resources: Resources

    private lateinit var viewModel: SettingsViewModel
    private lateinit var contactResolver: ContactResolver
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        // The delegate stores the app language in static state that leaks across tests, so
        // reset it to "follow the system" before every test.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        // The language tag persists through the plain "app_language" prefs the helper writes.
        `when`(appContext.getSharedPreferences("app_language", Context.MODE_PRIVATE)).thenReturn(languagePrefs)
        `when`(languagePrefs.edit()).thenReturn(languagePrefsEditor)
        `when`(
            languagePrefsEditor.putString(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()),
        ).thenReturn(languagePrefsEditor)
        `when`(appContext.resources).thenReturn(resources)
        `when`(resources.configuration).thenReturn(Configuration())
        // The subject preview resolves the placeholder sender/sim and the subject
        // templates through the app context; the mock delegates getString to the
        // Robolectric application so the assertions check the real templates.
        stubContextGetString(appContext, RuntimeEnvironment.getApplication())
        `when`(prefs.load()).thenReturn(AppSettings())
        `when`(prefs.smtpPassword).thenReturn("")
        doAnswer { inv ->
            val transform = inv.getArgument<(AppSettings) -> AppSettings>(0)
            prefs.save(transform(prefs.load()))
            null
        }.whenever(prefs).update(any())
        `when`(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        // The VM reads the live permission at construction (the extras seed); the sync tests
        // re-stub it per test.
        `when`(permissionManager.snapshot()).thenReturn(permissionsSnapshot())
        // Granted by default (the API 28 test config is below the permission's SDK anyway).
        `when`(permissionManager.canScheduleExactAlarms()).thenReturn(true)
        // The log-to-file commits attach the store under the app's files dir; point it at a
        // throwaway directory so no test leaves a log file behind.
        `when`(appContext.filesDir).thenReturn(
            File.createTempFile("settingsvm", "").apply {
                delete()
                mkdirs()
            },
        )
        // The resolver the form's country field reads its auto value from; the mock
        // context answers no telephony, so the chain degrades to the device locale.
        contactResolver = ContactResolver(appContext, prefs)
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
    }

    @After
    fun tearDown() {
        // The commits attach a real store to the temp dir: detach (and clear the buffer)
        // so a leftover attachment can't receive appends from the next test class.
        LogBuffer.disableFileStore()
        LogBuffer.clear()
        // The threshold is process-global: reset so it cannot leak into other tests.
        Logger.setLevel(LogLevel.DEBUG)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has correct defaults`() {
        val s = viewModel.state.value
        assertEquals("", s.config.settings.smtpHost)
        assertEquals(587, s.config.settings.smtpPort)
        assertEquals("587", s.config.portDraft)
        assertEquals(SmtpProtocol.START_TLS, s.config.settings.smtpProtocol)
        assertEquals("", s.config.settings.smtpUsername)
        assertEquals("", s.config.smtpPassword)
        assertEquals("", s.config.settings.destEmail)
        assertEquals("", s.config.settings.destName)
        assertEquals("", s.config.settings.senderEmail)
        assertEquals("", s.config.settings.senderName)
        assertEquals("", s.config.settings.subjectPrefix)
        assertEquals("", s.config.settings.countryCode)
        assertTrue(s.config.settings.autoCountry)
        assertEquals(contactResolver.detectedRegion(), s.countryValue)
        assertEquals(null, s.countryError)
        assertEquals("SMS from My Contact", s.subjectPreview)
        assertTrue(s.config.settings.useContactResolution)
        assertFalse(s.config.test.isTestingConnection)
        assertFalse(s.config.test.isSendingTestEmail)
        assertFalse(s.config.settings.displaySimCards)
        assertEquals(null, s.config.test.smtpTestResult)
        assertEquals(null, s.config.test.testEmailResult)
        assertFalse(s.retention.enabled)
        assertEquals(PeriodPreset.ONE_YEAR.days, s.retention.storedDays)
        assertEquals(s.retention.storedDays, s.retention.draftDays)
        assertFalse(s.retention.editing)
        assertEquals(null, s.retention.confirm)
        assertEquals(ThemeMode.SYSTEM, s.themeMode)
        assertEquals("", s.config.appLanguage)
        assertEquals(false, s.usePduTimestamp)
        assertEquals(DedupLevel.BALANCED, s.dedupLevel)
        assertEquals(LogLevel.ERROR, s.logLevel)
        assertFalse(s.logToFile.enabled)
        assertEquals(LogSizePreset.DEFAULT.bytes, s.logToFile.storedSize)
        assertEquals(s.logToFile.storedSize, s.logToFile.draftSize)
        assertFalse(s.logToFile.editing)
        assertEquals(null, s.logToFile.confirm)
    }

    @Test
    fun `smtp field change handlers update state`() {
        viewModel.form.onSmtpHostChanged("smtp.example.com")
        assertEquals("smtp.example.com", viewModel.state.value.config.settings.smtpHost)

        viewModel.form.onPortDraftChanged("465")
        assertEquals(465, viewModel.state.value.config.settings.smtpPort)
        assertEquals("465", viewModel.state.value.config.portDraft)

        viewModel.form.onSmtpUsernameChanged(TestEmail.SELF)
        assertEquals(TestEmail.SELF, viewModel.state.value.config.settings.smtpUsername)

        viewModel.form.onSmtpPasswordChanged("secret")
        assertEquals("secret", viewModel.state.value.config.smtpPassword)
    }

    @Test
    fun `smtp protocol change switches the protocol and port`() {
        viewModel.form.onSmtpProtocolChanged(SmtpProtocol.IMPLICIT_TLS)
        assertEquals(SmtpProtocol.IMPLICIT_TLS, viewModel.state.value.config.settings.smtpProtocol)
        assertEquals(465, viewModel.state.value.config.settings.smtpPort)

        viewModel.form.onSmtpProtocolChanged(SmtpProtocol.START_TLS)
        assertEquals(SmtpProtocol.START_TLS, viewModel.state.value.config.settings.smtpProtocol)
        assertEquals(587, viewModel.state.value.config.settings.smtpPort)
    }

    @Test
    fun `email field change handlers update state`() {
        viewModel.form.onDestEmailChanged("dest@example.com")
        assertEquals("dest@example.com", viewModel.state.value.config.settings.destEmail)

        viewModel.form.onDestNameChanged("Dest")
        assertEquals("Dest", viewModel.state.value.config.settings.destName)

        viewModel.form.onSenderEmailChanged("sender@example.com")
        assertEquals("sender@example.com", viewModel.state.value.config.settings.senderEmail)

        viewModel.form.onSenderNameChanged("Sender")
        assertEquals("Sender", viewModel.state.value.config.settings.senderName)
    }

    @Test
    fun `subject prefix trims whitespace`() {
        viewModel.form.onSubjectPrefixChanged("  my-prefix  ")
        assertEquals("my-prefix", viewModel.state.value.config.settings.subjectPrefix)
    }

    @Test
    fun `subject prefix whitespace only becomes empty`() {
        viewModel.form.onSubjectPrefixChanged("   ")
        assertEquals("", viewModel.state.value.config.settings.subjectPrefix)
    }

    @Test
    fun `subject prefix accepts empty and single char`() {
        viewModel.form.onSubjectPrefixChanged("")
        assertEquals("", viewModel.state.value.config.settings.subjectPrefix)

        viewModel.form.onSubjectPrefixChanged("a")
        assertEquals("a", viewModel.state.value.config.settings.subjectPrefix)
    }

    // The country for local numbers: the shared auto-value field wired through the form.

    @Test
    fun `country code change is uppercased and turns auto off`() {
        viewModel.form.onCountryCodeChanged("  de  ")

        val s = viewModel.state.value.config.settings
        assertFalse(s.autoCountry)
        assertEquals("DE", s.countryCode)
        val captor = argumentCaptor<(AppSettings) -> AppSettings>()
        verify(prefs).update(captor.capture())
        val saved = captor.lastValue(AppSettings())
        assertFalse(saved.autoCountry)
        assertEquals("DE", saved.countryCode)
    }

    @Test
    fun `country auto toggle on takes the detected region`() {
        viewModel.form.onAutoCountryChanged(true)

        val s = viewModel.state.value.config.settings
        assertTrue(s.autoCountry)
        assertEquals(contactResolver.detectedRegion(), s.countryCode)
    }

    @Test
    fun `country auto toggle off keeps the value the user edits from`() {
        viewModel.form.onAutoCountryChanged(true)
        viewModel.form.onAutoCountryChanged(false)

        val s = viewModel.state.value.config.settings
        assertFalse(s.autoCountry)
        assertEquals(contactResolver.detectedRegion(), s.countryCode)
    }

    @Test
    fun `committing the detected code re-engages country auto`() {
        viewModel.form.onAutoCountryChanged(false)
        val detected = contactResolver.detectedRegion()
        viewModel.form.onCountryCodeChanged(detected)
        viewModel.form.onCountryCodeCommitted(detected)

        val s = viewModel.state.value.config.settings
        assertTrue(s.autoCountry)
        assertEquals(detected, s.countryCode)
    }

    @Test
    fun `committing a code different from the detected keeps country auto off`() {
        viewModel.form.onAutoCountryChanged(false)
        val other = if (contactResolver.detectedRegion() == "FR") "DE" else "FR"
        viewModel.form.onCountryCodeChanged(other)
        viewModel.form.onCountryCodeCommitted(other)

        assertFalse(viewModel.state.value.config.settings.autoCountry)
    }

    @Test
    fun `country error is present for an unknown code with auto off`() {
        viewModel.form.onCountryCodeChanged("xx")

        assertEquals("Unknown country code", viewModel.state.value.countryError)
    }

    @Test
    fun `country error is absent for a valid code`() {
        viewModel.form.onCountryCodeChanged("de")

        assertNull(viewModel.state.value.countryError)
    }

    @Test
    fun `country error is absent for a blank code`() {
        viewModel.form.onCountryCodeChanged("   ")

        assertNull(viewModel.state.value.countryError)
    }

    @Test
    fun `country error is absent while auto is on`() {
        viewModel.form.onCountryCodeChanged("xx")
        viewModel.form.onAutoCountryChanged(true)

        assertNull(viewModel.state.value.countryError)
    }

    @Test
    fun `country value follows the manual code when auto is off`() {
        viewModel.form.onAutoCountryChanged(false)
        viewModel.form.onCountryCodeChanged("FR")

        val s = viewModel.state.value
        assertFalse(s.config.settings.autoCountry)
        assertEquals("FR", s.countryValue)
    }

    @Test
    fun `country value reverts to the detected region when auto re-engages`() {
        viewModel.form.onAutoCountryChanged(false)
        val detected = contactResolver.detectedRegion()
        viewModel.form.onCountryCodeChanged(detected)
        viewModel.form.onCountryCodeCommitted(detected)

        val s = viewModel.state.value
        assertTrue(s.config.settings.autoCountry)
        assertEquals(detected, s.countryValue)
    }

    // The stored country's one-time heal: a manual code that is not a valid ISO
    // country resets to the auto default on a settings open/refresh. The prefs stub
    // is stateful (the update writes back into what the load answers) so the form's
    // own re-read sees the cleaned value, like the real prefs.

    @Test
    fun `settings open resets an invalid stored country code to the auto default`() {
        var stored = AppSettings(autoCountry = false, countryCode = "XX")
        `when`(prefs.load()).thenAnswer { stored }
        doAnswer { inv ->
            stored = inv.getArgument<(AppSettings) -> AppSettings>(0)(stored)
            null
        }.whenever(prefs).update(any())
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )

        val s = viewModel.state.value
        assertTrue(s.config.settings.autoCountry)
        assertEquals(contactResolver.detectedRegion(), s.config.settings.countryCode)
        assertNull(s.countryError)
    }

    @Test
    fun `settings open resets a blank manual country code to the auto default`() {
        // A blank manual code (a cleared field, auto turned off before a code was
        // ever set) resolves to the detected region exactly like auto does, so the
        // toggle-off-with-empty-field state heals to auto.
        var stored = AppSettings(autoCountry = false, countryCode = "")
        `when`(prefs.load()).thenAnswer { stored }
        doAnswer { inv ->
            stored = inv.getArgument<(AppSettings) -> AppSettings>(0)(stored)
            null
        }.whenever(prefs).update(any())
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )

        val s = viewModel.state.value
        assertTrue(s.config.settings.autoCountry)
        assertEquals(contactResolver.detectedRegion(), s.config.settings.countryCode)
        assertNull(s.countryError)
    }

    @Test
    fun `settings open keeps a valid stored country code untouched`() {
        `when`(prefs.load()).thenReturn(AppSettings(autoCountry = false, countryCode = "DE"))
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )

        val s = viewModel.state.value
        assertFalse(s.config.settings.autoCountry)
        assertEquals("DE", s.config.settings.countryCode)
        assertNull(s.countryError)
        verify(prefs, never()).update(any())
    }

    @Test
    fun `refreshState resets an invalid stored country code to the auto default`() {
        var stored = AppSettings(autoCountry = false, countryCode = "XX")
        `when`(prefs.load()).thenAnswer { stored }
        doAnswer { inv ->
            stored = inv.getArgument<(AppSettings) -> AppSettings>(0)(stored)
            null
        }.whenever(prefs).update(any())

        viewModel.refreshState()

        val s = viewModel.state.value
        assertTrue(s.config.settings.autoCountry)
        assertEquals(contactResolver.detectedRegion(), s.config.settings.countryCode)
        assertNull(s.countryError)
    }

    // The subject preview: the VM evaluates it (the screen renders state.subjectPreview
    // as-is), from the form's live draft settings.

    @Test
    fun `subject preview defaults to the sender placeholder without a prefix`() {
        assertEquals("SMS from My Contact", viewModel.state.value.subjectPreview)
    }

    @Test
    fun `subject preview switches to the phone placeholder when contact resolution is off`() {
        viewModel.form.onUseContactResolutionChanged(false)
        assertEquals("SMS from +1234", viewModel.state.value.subjectPreview)
    }

    @Test
    fun `subject preview picks up the prefix live and drops it when cleared`() {
        viewModel.form.onSubjectPrefixChanged("my-prefix")
        assertEquals("[my-prefix] SMS from My Contact", viewModel.state.value.subjectPreview)
        viewModel.form.onSubjectPrefixChanged("")
        assertEquals("SMS from My Contact", viewModel.state.value.subjectPreview)
    }

    @Test
    fun `subject preview shows the sim suffix with sim display on`() {
        viewModel.form.onDisplaySimCardsChanged(true)
        assertEquals("SMS from My Contact on My SIM", viewModel.state.value.subjectPreview)
    }

    @Test
    fun `subject preview combines the prefix and the sim suffix`() {
        viewModel.form.onDisplaySimCardsChanged(true)
        viewModel.form.onSubjectPrefixChanged("my-prefix")
        assertEquals("[my-prefix] SMS from My Contact on My SIM", viewModel.state.value.subjectPreview)
    }

    @Test
    fun `contact resolution toggle`() {
        viewModel.form.onUseContactResolutionChanged(false)
        assertFalse(viewModel.state.value.config.settings.useContactResolution)
        viewModel.form.onUseContactResolutionChanged(true)
        assertTrue(viewModel.state.value.config.settings.useContactResolution)
    }

    @Test
    fun `display sim cards toggle`() {
        viewModel.form.onDisplaySimCardsChanged(true)
        assertTrue(viewModel.state.value.config.settings.displaySimCards)
        viewModel.form.onDisplaySimCardsChanged(false)
        assertFalse(viewModel.state.value.config.settings.displaySimCards)
    }

    @Test
    fun `form edits and extras commits merge into one state`() {
        viewModel.form.onSmtpHostChanged("smtp.example.com")
        viewModel.onJsonEmailFormatChanged(true)
        val s = viewModel.state.value
        assertEquals("smtp.example.com", s.config.settings.smtpHost)
        assertTrue(s.useJsonEmailFormat)
        viewModel.onPduTimestampChanged(true)
        val after = viewModel.state.value
        assertEquals("smtp.example.com", after.config.settings.smtpHost)
        assertTrue(after.useJsonEmailFormat)
        assertTrue(after.usePduTimestamp)
    }

    @Test
    fun `refreshState loads from prefs`() {
        `when`(prefs.load()).thenReturn(AppSettings(smtpHost = "refreshed.example.com", destEmail = "refreshed@example.com"))
        viewModel.refreshState()
        assertEquals("refreshed.example.com", viewModel.state.value.config.settings.smtpHost)
        assertEquals("refreshed@example.com", viewModel.state.value.config.settings.destEmail)
    }

    @Test
    fun `refreshState bumps the refresh generation`() {
        val before = viewModel.state.value.config.refreshGeneration
        viewModel.refreshState()
        assertEquals(before + 1, viewModel.state.value.config.refreshGeneration)
    }

    // The sync tests only vary the gating permissions; the rest stay denied.
    private fun permissionsSnapshot(
        notifications: Boolean = false,
        contacts: Boolean = false,
        phone: Boolean = false,
    ): PermissionSnapshot =
        PermissionSnapshot(
            sms = false,
            notifications = notifications,
            contacts = contacts,
            phone = phone,
            battery = false,
        )

    @Test
    fun `syncPermissionGatedToggles turns contact resolution off when the contacts permission is revoked`() {
        viewModel.form.onUseContactResolutionChanged(true)
        `when`(permissionManager.snapshot()).thenReturn(permissionsSnapshot())
        viewModel.syncPermissionGatedToggles()
        assertFalse(viewModel.state.value.config.settings.useContactResolution)
    }

    @Test
    fun `syncPermissionGatedToggles turns sim display off when the phone permission is revoked`() {
        viewModel.form.onDisplaySimCardsChanged(true)
        `when`(permissionManager.snapshot()).thenReturn(permissionsSnapshot(phone = false))
        viewModel.syncPermissionGatedToggles()
        assertFalse(viewModel.state.value.config.settings.displaySimCards)
    }

    @Test
    fun `syncPermissionGatedToggles leaves the toggles on when the permissions are granted`() {
        viewModel.form.onUseContactResolutionChanged(true)
        viewModel.form.onDisplaySimCardsChanged(true)
        `when`(permissionManager.snapshot()).thenReturn(permissionsSnapshot(contacts = true, phone = true))
        viewModel.syncPermissionGatedToggles()
        assertTrue(viewModel.state.value.config.settings.useContactResolution)
        assertTrue(viewModel.state.value.config.settings.displaySimCards)
    }

    @Test
    fun `syncPermissionGatedToggles keeps already-off toggles off when the permissions are revoked`() {
        `when`(permissionManager.snapshot()).thenReturn(permissionsSnapshot())
        viewModel.syncPermissionGatedToggles()
        assertFalse(viewModel.state.value.config.settings.useContactResolution)
        assertFalse(viewModel.state.value.config.settings.displaySimCards)
    }

    @Test
    fun `state seeds showStickyNotification from the stored settings`() {
        `when`(prefs.load()).thenReturn(AppSettings(showStickyNotification = false))
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        assertFalse(viewModel.state.value.showStickyNotification)
    }

    @Test
    fun `state seeds notificationsGranted from the live permission snapshot`() {
        `when`(permissionManager.snapshot()).thenReturn(permissionsSnapshot(notifications = true))
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        assertTrue(viewModel.state.value.notificationsGranted)
    }

    @Test
    fun `onStickyNotificationChanged flips the state and persists the change`() {
        viewModel.onStickyNotificationChanged(false)
        assertFalse(viewModel.state.value.showStickyNotification)
        // The pref write is the whole reconcile: capture the transform and check it flips
        // the flag on the loaded settings (the reconciler reacts to the flag emission).
        val captor = argumentCaptor<(AppSettings) -> AppSettings>()
        verify(prefs).update(captor.capture())
        assertFalse(captor.firstValue(AppSettings()).showStickyNotification)
        viewModel.onStickyNotificationChanged(true)
        assertTrue(viewModel.state.value.showStickyNotification)
    }

    @Test
    fun `onFilterEnabledChanged flips the state and persists the change`() {
        viewModel.onFilterEnabledChanged(true)
        assertTrue(viewModel.state.value.filterEnabled)
        // The pref write is the whole commit: capture the transform and check it flips the
        // flag on the loaded settings (the engine gates the filter on it).
        val captor = argumentCaptor<(AppSettings) -> AppSettings>()
        verify(prefs).update(captor.capture())
        assertTrue(captor.firstValue(AppSettings()).filterEnabled)
        viewModel.onFilterEnabledChanged(false)
        assertFalse(viewModel.state.value.filterEnabled)
    }

    @Test
    fun `state seeds filterEnabled from the stored settings`() {
        `when`(prefs.load()).thenReturn(AppSettings(filterEnabled = true))
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        assertTrue(viewModel.state.value.filterEnabled)
    }

    @Test
    fun `refreshNotificationsPermission syncs the notification on a grant while the sticky setting is on`() {
        `when`(permissionManager.snapshot()).thenReturn(permissionsSnapshot(notifications = true))
        viewModel.refreshNotificationsPermission()
        assertTrue(viewModel.state.value.notificationsGranted)
        verifyBlocking(backgroundStateReconciler) { syncNotification() }
    }

    @Test
    fun `refreshNotificationsPermission does not sync without a grant transition`() {
        // false -> false: the base stub denies, so a re-read changes nothing.
        viewModel.refreshNotificationsPermission()
        verifyBlocking(backgroundStateReconciler, never()) { syncNotification() }
        // true -> true: the first call consumes the transition, the second sees none.
        `when`(permissionManager.snapshot()).thenReturn(permissionsSnapshot(notifications = true))
        viewModel.refreshNotificationsPermission()
        verifyBlocking(backgroundStateReconciler, times(1)) { syncNotification() }
        viewModel.refreshNotificationsPermission()
        verifyBlocking(backgroundStateReconciler, times(1)) { syncNotification() }
    }

    @Test
    fun `refreshNotificationsPermission does not sync a grant while the sticky setting is off`() {
        viewModel.onStickyNotificationChanged(false)
        `when`(permissionManager.snapshot()).thenReturn(permissionsSnapshot(notifications = true))
        viewModel.refreshNotificationsPermission()
        assertTrue(viewModel.state.value.notificationsGranted)
        verifyBlocking(backgroundStateReconciler, never()) { syncNotification() }
    }

    @Test
    fun `sendTestEmail success`() =
        runTest {
            `when`(emailService.sendTestEmail(any<SmtpConfig>(), any<TestEmailFields>())).thenReturn(EmailResult.Success)
            withMockedToast {
                viewModel.sendTestEmail()
                advanceUntilIdle()
            }
            assertFalse(viewModel.state.value.config.test.isSendingTestEmail)
            assertTrue(viewModel.state.value.config.test.testEmailResult is EmailResult.Success)
        }

    @Test
    fun `sendTestEmail failure on exception`() =
        runTest {
            `when`(emailService.sendTestEmail(any<SmtpConfig>(), any<TestEmailFields>())).thenReturn(EmailResult.Failure("fail"))
            withMockedToast {
                viewModel.sendTestEmail()
                advanceUntilIdle()
            }
            assertTrue(viewModel.state.value.config.test.testEmailResult is EmailResult.Failure)
        }

    @Test
    fun `sendTestEmail clears previous result`() =
        runTest {
            `when`(emailService.sendTestEmail(any<SmtpConfig>(), any<TestEmailFields>())).thenReturn(EmailResult.Success)
            withMockedToast {
                viewModel.sendTestEmail()
                advanceUntilIdle()
                viewModel.sendTestEmail()
                advanceUntilIdle()
            }
            assertTrue(viewModel.state.value.config.test.testEmailResult is EmailResult.Success)
        }

    @Test
    fun `sendTestEmail toasts the success once`() =
        runTest {
            `when`(emailService.sendTestEmail(any<SmtpConfig>(), any<TestEmailFields>())).thenReturn(EmailResult.Success)
            val successMessage = RuntimeEnvironment.getApplication().getString(R.string.email_config_test_success)
            withMockedToast { staticToast ->
                viewModel.sendTestEmail()
                advanceUntilIdle()
                staticToast.verify {
                    Toast.makeText(eq(appContext), eq(successMessage), eq(Toast.LENGTH_SHORT))
                }
            }
        }

    @Test
    fun `smtp field edits clear the stored smtp and test email results`() =
        runTest {
            val edits =
                mapOf(
                    "host" to { viewModel.form.onSmtpHostChanged("other.example.com") },
                    "port" to { viewModel.form.onPortDraftChanged("25") },
                    "protocol" to { viewModel.form.onSmtpProtocolChanged(SmtpProtocol.IMPLICIT_TLS) },
                    "username" to { viewModel.form.onSmtpUsernameChanged("other") },
                    "password" to { viewModel.form.onSmtpPasswordChanged("other") },
                )
            edits.forEach { (name, edit) ->
                viewModel.form.update {
                    it.copy(test = it.test.copy(smtpTestResult = EmailResult.Success, testEmailResult = EmailResult.Success))
                }
                assertTrue(viewModel.state.value.config.test.smtpTestResult is EmailResult.Success)
                assertTrue(viewModel.state.value.config.test.testEmailResult is EmailResult.Success)
                edit()
                assertEquals(null, viewModel.state.value.config.test.smtpTestResult, "edit of $name must clear the connection result")
                assertEquals(null, viewModel.state.value.config.test.testEmailResult, "edit of $name must clear the test email result")
            }
        }

    @Test
    fun `email field edits clear the stored test email result`() =
        runTest {
            `when`(emailService.sendTestEmail(any<SmtpConfig>(), any<TestEmailFields>())).thenReturn(EmailResult.Success)
            val edits =
                mapOf(
                    "dest email" to { viewModel.form.onDestEmailChanged("other@example.com") },
                    "dest name" to { viewModel.form.onDestNameChanged("Other") },
                    "sender email" to { viewModel.form.onSenderEmailChanged("other@example.com") },
                    "sender name" to { viewModel.form.onSenderNameChanged("Other") },
                    "subject prefix" to { viewModel.form.onSubjectPrefixChanged("new-prefix") },
                )
            withMockedToast {
                edits.forEach { (name, edit) ->
                    viewModel.sendTestEmail()
                    advanceUntilIdle()
                    assertTrue(viewModel.state.value.config.test.testEmailResult is EmailResult.Success)
                    edit()
                    assertEquals(null, viewModel.state.value.config.test.testEmailResult, "edit of $name must clear the result")
                }
            }
        }

    @Test
    fun `retention defaults to off`() {
        assertFalse(viewModel.state.value.retention.enabled)
    }

    @Test
    fun `autoCleanupRetentionDays defaults to 1 year while the cleanup is off`() {
        assertEquals(PeriodPreset.ONE_YEAR.days, viewModel.state.value.retention.storedDays)
    }

    @Test
    fun `initial state keeps the stored period while it is one of the presets`() {
        `when`(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 1825))
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        assertTrue(viewModel.state.value.retention.enabled)
        assertEquals(1825, viewModel.state.value.retention.storedDays)
    }

    @Test
    fun `initial state degrades a stored period outside the presets to the default`() {
        `when`(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 45))
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        assertTrue(viewModel.state.value.retention.enabled)
        assertEquals(PeriodPreset.ONE_YEAR.days, viewModel.state.value.retention.storedDays)
    }

    @Test
    fun `commitRetention on stores the preset and updates the state`() {
        mockToast().use {
            viewModel.commitRetention(true, 365)
        }
        assertTrue(viewModel.state.value.retention.enabled)
        assertEquals(365, viewModel.state.value.retention.storedDays)
        verify(prefs).save(AppSettings(autoCleanupRetentionDays = 365))
        // Enqueueing/cancelling the periodic cleanup is the BackgroundStateReconciler's job,
        // not this VM's: it reacts to the stored-period write above.
    }

    @Test
    fun `commitRetention off clears the stored period and the proposal returns to the default`() {
        mockToast().use {
            viewModel.commitRetention(true, 730)
        }
        assertEquals(730, viewModel.state.value.retention.storedDays)
        viewModel.commitRetention(false, 730)
        assertFalse(viewModel.state.value.retention.enabled)
        assertEquals(PeriodPreset.ONE_YEAR.days, viewModel.state.value.retention.storedDays)
        verify(prefs).save(AppSettings(autoCleanupRetentionDays = 0))
    }

    @Test
    fun `commitRetention keeps the stored period when the same one is committed again`() {
        mockToast().use {
            viewModel.commitRetention(true, 365)
        }
        viewModel.commitRetention(true, 365)
        assertTrue(viewModel.state.value.retention.enabled)
        assertEquals(365, viewModel.state.value.retention.storedDays)
        verify(prefs, times(2)).save(AppSettings(autoCleanupRetentionDays = 365))
    }

    @Test
    fun `commitRetention ignores a period that is not one of the presets`() {
        viewModel.commitRetention(true, 999)
        assertFalse(viewModel.state.value.retention.enabled)
        assertEquals(PeriodPreset.ONE_YEAR.days, viewModel.state.value.retention.storedDays)
        verify(prefs, never()).save(any())
    }

    // The retention editor's decision matrix: the VM owns the branches, the screen
    // renders the resulting state.
    // The toast fires on the off->on transition (the cleanup starts), so every commit
    // path that can cross it mocks the static Toast.
    private fun mockToast() =
        org.mockito.Mockito.mockStatic(Toast::class.java).also {
            it.`when`<Toast> { Toast.makeText(any<Context>(), any<Int>(), any<Int>()) }.thenReturn(mock())
        }

    @Test
    fun `toggle on opens the edit with the stored proposal and commits nothing`() {
        viewModel.onRetentionToggled(true)
        val r = viewModel.state.value.retention
        assertFalse(r.enabled)
        assertTrue(r.editing)
        assertEquals(PeriodPreset.ONE_YEAR.days, r.draftDays)
        assertEquals(null, r.confirm)
        verify(prefs, never()).save(any())
    }

    @Test
    fun `toggle on while enabled re-opens the edit with the stored period`() {
        `when`(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 730))
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        viewModel.onRetentionToggled(true)
        val r = viewModel.state.value.retention
        assertTrue(r.enabled)
        assertTrue(r.editing)
        assertEquals(730, r.draftDays)
        verify(prefs, never()).save(any())
    }

    @Test
    fun `toggle off while enabled commits off straight away`() {
        `when`(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 730))
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        viewModel.onRetentionToggled(false)
        val r = viewModel.state.value.retention
        assertFalse(r.enabled)
        assertFalse(r.editing)
        assertEquals(PeriodPreset.ONE_YEAR.days, r.storedDays)
        verify(prefs).save(AppSettings(autoCleanupRetentionDays = 0))
    }

    @Test
    fun `toggle off from a pending enable drops the enable without a commit`() {
        viewModel.onRetentionToggled(true)
        viewModel.onRetentionToggled(false)
        val r = viewModel.state.value.retention
        assertFalse(r.enabled)
        assertFalse(r.editing)
        assertEquals(PeriodPreset.ONE_YEAR.days, r.draftDays)
        verify(prefs, never()).save(any())
    }

    @Test
    fun `save while the enable is pending asks for the enable confirm and commits nothing`() {
        viewModel.onRetentionToggled(true)
        viewModel.onRetentionSave()
        val r = viewModel.state.value.retention
        assertFalse(r.enabled)
        assertTrue(r.editing)
        assertEquals(RetentionConfirm.ENABLE, r.confirm)
        verify(prefs, never()).save(any())
    }

    @Test
    fun `enable confirm commits the picked period and closes the edit`() {
        mockToast().use {
            viewModel.onRetentionToggled(true)
            viewModel.onRetentionDraftChanged(365)
            viewModel.onRetentionSave()
            viewModel.onConfirmed()
        }
        val r = viewModel.state.value.retention
        assertTrue(r.enabled)
        assertFalse(r.editing)
        assertEquals(null, r.confirm)
        assertEquals(365, r.storedDays)
        verify(prefs).save(AppSettings(autoCleanupRetentionDays = 365))
    }

    @Test
    fun `enable dismiss keeps the pending enable with the draft`() {
        viewModel.onRetentionToggled(true)
        viewModel.onRetentionDraftChanged(90)
        viewModel.onRetentionSave()
        viewModel.onConfirmDismissed()
        val r = viewModel.state.value.retention
        assertFalse(r.enabled)
        assertTrue(r.editing)
        assertEquals(null, r.confirm)
        assertEquals(90, r.draftDays)
        verify(prefs, never()).save(any())
    }

    @Test
    fun `save with an unchanged draft closes the edit and commits nothing`() {
        `when`(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 730))
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        viewModel.onEditStarted()
        viewModel.onRetentionSave()
        val r = viewModel.state.value.retention
        assertTrue(r.enabled)
        assertFalse(r.editing)
        assertEquals(null, r.confirm)
        assertEquals(730, r.storedDays)
        verify(prefs, never()).save(any())
    }

    @Test
    fun `save with a shorter draft asks for the shorten confirm and commits nothing`() {
        `when`(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 1825))
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        viewModel.onEditStarted()
        viewModel.onRetentionDraftChanged(365)
        viewModel.onRetentionSave()
        val r = viewModel.state.value.retention
        assertTrue(r.enabled)
        assertTrue(r.editing)
        assertEquals(RetentionConfirm.SHORTEN, r.confirm)
        verify(prefs, never()).save(any())
    }

    @Test
    fun `shorten confirm commits the shorter period and closes the edit`() {
        `when`(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 1825))
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        viewModel.onEditStarted()
        viewModel.onRetentionDraftChanged(365)
        viewModel.onRetentionSave()
        viewModel.onConfirmed()
        val r = viewModel.state.value.retention
        assertTrue(r.enabled)
        assertFalse(r.editing)
        assertEquals(null, r.confirm)
        assertEquals(365, r.storedDays)
        verify(prefs).save(AppSettings(autoCleanupRetentionDays = 365))
    }

    @Test
    fun `shorten dismiss keeps the edit open with the draft`() {
        `when`(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 1825))
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        viewModel.onEditStarted()
        viewModel.onRetentionDraftChanged(365)
        viewModel.onRetentionSave()
        viewModel.onConfirmDismissed()
        val r = viewModel.state.value.retention
        assertTrue(r.enabled)
        assertTrue(r.editing)
        assertEquals(null, r.confirm)
        assertEquals(365, r.draftDays)
        verify(prefs, never()).save(any())
    }

    @Test
    fun `save with a longer draft commits straight away`() {
        `when`(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 365))
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        viewModel.onEditStarted()
        viewModel.onRetentionDraftChanged(1825)
        viewModel.onRetentionSave()
        val r = viewModel.state.value.retention
        assertTrue(r.enabled)
        assertFalse(r.editing)
        assertEquals(null, r.confirm)
        assertEquals(1825, r.storedDays)
        verify(prefs).save(AppSettings(autoCleanupRetentionDays = 1825))
    }

    // The log-to-file editor's decision matrix: the mirror of the retention editor's,
    // minus the enables confirm and with the disable moved to a confirm.
    private fun viewModelWithLog(settings: AppSettings): SettingsViewModel {
        `when`(prefs.load()).thenReturn(settings)
        val viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        return viewModel
    }

    @Test
    fun `logToFile defaults to off with the default size`() {
        assertFalse(viewModel.state.value.logToFile.enabled)
        assertEquals(LogSizePreset.DEFAULT.bytes, viewModel.state.value.logToFile.storedSize)
    }

    @Test
    fun `initial state keeps the stored size while it is one of the presets`() {
        viewModel =
            viewModelWithLog(
                AppSettings(
                    autoCleanupRetentionDays = 30,
                    logToFileEnabled = true,
                    logFileSizeBytes = 1024 * 1024,
                ),
            )
        val l = viewModel.state.value.logToFile
        assertTrue(l.enabled)
        assertEquals(1024 * 1024, l.storedSize)
    }

    @Test
    fun `initial state degrades a stored size outside the presets to the default`() {
        viewModel =
            viewModelWithLog(
                AppSettings(
                    autoCleanupRetentionDays = 30,
                    logToFileEnabled = true,
                    logFileSizeBytes = 128 * 1024,
                ),
            )
        val l = viewModel.state.value.logToFile
        assertTrue(l.enabled)
        assertEquals(LogSizePreset.DEFAULT.bytes, l.storedSize)
    }

    @Test
    fun `log toggle on opens the edit with the stored size and commits nothing`() {
        viewModel.onLogToFileToggled(true)
        val l = viewModel.state.value.logToFile
        assertFalse(l.enabled)
        assertTrue(l.editing)
        assertEquals(LogSizePreset.DEFAULT.bytes, l.draftSize)
        assertEquals(null, l.confirm)
        verify(prefs, never()).save(any())
    }

    @Test
    fun `log toggle off while enabled asks for the disable confirm and commits nothing`() {
        viewModel = viewModelWithLog(AppSettings(autoCleanupRetentionDays = 30, logToFileEnabled = true))
        viewModel.onLogToFileToggled(false)
        val l = viewModel.state.value.logToFile
        assertTrue(l.enabled)
        assertFalse(l.editing)
        assertEquals(LogToFileConfirm.DISABLE, l.confirm)
        verify(prefs, never()).save(any())
    }

    @Test
    fun `log toggle off from a pending enable drops the enable without a commit`() {
        viewModel.onLogToFileToggled(true)
        viewModel.onLogToFileToggled(false)
        val l = viewModel.state.value.logToFile
        assertFalse(l.enabled)
        assertFalse(l.editing)
        assertEquals(null, l.confirm)
        verify(prefs, never()).save(any())
    }

    @Test
    fun `log save while the enable is pending commits straight away`() {
        viewModel.onLogToFileToggled(true)
        viewModel.onLogSizeDraftChanged(1024 * 1024)
        viewModel.onLogSave()
        val l = viewModel.state.value.logToFile
        assertTrue(l.enabled)
        assertFalse(l.editing)
        assertEquals(null, l.confirm)
        assertEquals(1024 * 1024, l.storedSize)
        verify(prefs).save(
            AppSettings(
                logToFileEnabled = true,
                logFileSizeBytes = 1024 * 1024,
            ),
        )
    }

    @Test
    fun `log save with an unchanged draft closes the edit and commits nothing`() {
        viewModel =
            viewModelWithLog(
                AppSettings(
                    autoCleanupRetentionDays = 30,
                    logToFileEnabled = true,
                    logFileSizeBytes = 1024 * 1024,
                ),
            )
        viewModel.onLogEditStarted()
        viewModel.onLogSave()
        val l = viewModel.state.value.logToFile
        assertTrue(l.enabled)
        assertFalse(l.editing)
        assertEquals(null, l.confirm)
        verify(prefs, never()).save(any())
    }

    @Test
    fun `log save with a smaller draft asks for the shrink confirm and commits nothing`() {
        viewModel =
            viewModelWithLog(
                AppSettings(
                    autoCleanupRetentionDays = 30,
                    logToFileEnabled = true,
                    logFileSizeBytes = 16 * 1024 * 1024,
                ),
            )
        viewModel.onLogEditStarted()
        viewModel.onLogSizeDraftChanged(256 * 1024)
        viewModel.onLogSave()
        val l = viewModel.state.value.logToFile
        assertTrue(l.enabled)
        assertTrue(l.editing)
        assertEquals(LogToFileConfirm.SHRINK, l.confirm)
        verify(prefs, never()).save(any())
    }

    @Test
    fun `log save with a larger draft commits straight away`() {
        viewModel =
            viewModelWithLog(
                AppSettings(
                    autoCleanupRetentionDays = 30,
                    logToFileEnabled = true,
                    logFileSizeBytes = 64 * 1024,
                ),
            )
        viewModel.onLogEditStarted()
        viewModel.onLogSizeDraftChanged(1024 * 1024)
        viewModel.onLogSave()
        val l = viewModel.state.value.logToFile
        assertTrue(l.enabled)
        assertFalse(l.editing)
        assertEquals(1024 * 1024, l.storedSize)
        verify(prefs).save(
            AppSettings(
                autoCleanupRetentionDays = 30,
                logToFileEnabled = true,
                logFileSizeBytes = 1024 * 1024,
            ),
        )
    }

    @Test
    fun `log shrink confirm commits the picked size and closes the edit`() {
        viewModel =
            viewModelWithLog(
                AppSettings(
                    autoCleanupRetentionDays = 30,
                    logToFileEnabled = true,
                    logFileSizeBytes = 16 * 1024 * 1024,
                ),
            )
        viewModel.onLogEditStarted()
        viewModel.onLogSizeDraftChanged(256 * 1024)
        viewModel.onLogSave()
        viewModel.onLogConfirmed()
        val l = viewModel.state.value.logToFile
        assertTrue(l.enabled)
        assertFalse(l.editing)
        assertEquals(null, l.confirm)
        assertEquals(256 * 1024, l.storedSize)
        verify(prefs).save(
            AppSettings(
                autoCleanupRetentionDays = 30,
                logToFileEnabled = true,
                logFileSizeBytes = 256 * 1024,
            ),
        )
    }

    @Test
    fun `log shrink dismiss keeps the edit open with the draft`() {
        viewModel =
            viewModelWithLog(
                AppSettings(
                    autoCleanupRetentionDays = 30,
                    logToFileEnabled = true,
                    logFileSizeBytes = 16 * 1024 * 1024,
                ),
            )
        viewModel.onLogEditStarted()
        viewModel.onLogSizeDraftChanged(256 * 1024)
        viewModel.onLogSave()
        viewModel.onLogConfirmDismissed()
        val l = viewModel.state.value.logToFile
        assertTrue(l.enabled)
        assertTrue(l.editing)
        assertEquals(null, l.confirm)
        assertEquals(256 * 1024, l.draftSize)
        verify(prefs, never()).save(any())
    }

    @Test
    fun `log disable confirm commits off and keeps the stored size`() {
        viewModel =
            viewModelWithLog(
                AppSettings(
                    autoCleanupRetentionDays = 30,
                    logToFileEnabled = true,
                    logFileSizeBytes = 1024 * 1024,
                ),
            )
        viewModel.onLogToFileToggled(false)
        viewModel.onLogConfirmed()
        val l = viewModel.state.value.logToFile
        assertFalse(l.enabled)
        assertEquals(null, l.confirm)
        // The size survives the disable, so the next enable re-proposes it.
        assertEquals(1024 * 1024, l.storedSize)
        verify(prefs).save(
            AppSettings(
                autoCleanupRetentionDays = 30,
                logToFileEnabled = false,
                logFileSizeBytes = 1024 * 1024,
            ),
        )
    }

    @Test
    fun `log disable dismiss keeps the setting on`() {
        viewModel = viewModelWithLog(AppSettings(autoCleanupRetentionDays = 30, logToFileEnabled = true))
        viewModel.onLogToFileToggled(false)
        viewModel.onLogConfirmDismissed()
        val l = viewModel.state.value.logToFile
        assertTrue(l.enabled)
        assertEquals(null, l.confirm)
        verify(prefs, never()).save(any())
    }

    @Test
    fun `commitLogToFile ignores a size that is not one of the presets`() {
        viewModel.commitLogToFile(true, 999)
        assertFalse(viewModel.state.value.logToFile.enabled)
        verify(prefs, never()).save(any())
    }

    @Test
    fun `log edit start opens the edit with the stored size as the draft`() {
        viewModel =
            viewModelWithLog(
                AppSettings(
                    autoCleanupRetentionDays = 30,
                    logToFileEnabled = true,
                    logFileSizeBytes = 4 * 1024 * 1024,
                ),
            )
        viewModel.onLogEditStarted()
        val l = viewModel.state.value.logToFile
        assertTrue(l.editing)
        assertEquals(4 * 1024 * 1024, l.draftSize)
        verify(prefs, never()).save(any())
    }

    @Test
    fun `log edit cancel closes the edit and drops a pending enable`() {
        viewModel.onLogToFileToggled(true)
        viewModel.onLogEditCancelled()
        val l = viewModel.state.value.logToFile
        assertFalse(l.enabled)
        assertFalse(l.editing)
        assertEquals(null, l.confirm)
        verify(prefs, never()).save(any())
    }

    @Test
    fun `refresh re-seeds the committed log-to-file half`() {
        viewModel = viewModelWithLog(AppSettings(autoCleanupRetentionDays = 30, logToFileEnabled = true))
        `when`(prefs.load()).thenReturn(
            AppSettings(
                autoCleanupRetentionDays = 30,
                logToFileEnabled = true,
                logFileSizeBytes = 4 * 1024 * 1024,
            ),
        )
        viewModel.refreshState()
        val l = viewModel.state.value.logToFile
        assertTrue(l.enabled)
        assertEquals(4 * 1024 * 1024, l.storedSize)
    }

    @Test
    fun `edit start opens the edit with the stored period as the draft`() {
        `when`(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 730))
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        viewModel.onEditStarted()
        val r = viewModel.state.value.retention
        assertTrue(r.editing)
        assertEquals(730, r.draftDays)
    }

    @Test
    fun `edit cancel closes the edit without a commit`() {
        `when`(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 730))
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        viewModel.onEditStarted()
        viewModel.onRetentionDraftChanged(365)
        viewModel.onEditCancelled()
        val r = viewModel.state.value.retention
        assertTrue(r.enabled)
        assertFalse(r.editing)
        assertEquals(null, r.confirm)
        assertEquals(730, r.storedDays)
        verify(prefs, never()).save(any())
    }

    @Test
    fun `edit cancel from a pending enable drops the enable without a commit`() {
        viewModel.onRetentionToggled(true)
        viewModel.onEditCancelled()
        val r = viewModel.state.value.retention
        assertFalse(r.enabled)
        assertFalse(r.editing)
        verify(prefs, never()).save(any())
    }

    @Test
    fun `draft change picks the period for the save`() {
        viewModel.onRetentionToggled(true)
        viewModel.onRetentionDraftChanged(90)
        assertEquals(90, viewModel.state.value.retention.draftDays)
    }

    @Test
    fun `refreshState re-seeds the committed half and keeps the in-progress edit`() {
        `when`(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 730))
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        viewModel.onEditStarted()
        viewModel.onRetentionDraftChanged(365)
        `when`(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 1825))
        viewModel.refreshState()
        val r = viewModel.state.value.retention
        assertTrue(r.enabled)
        assertEquals(1825, r.storedDays)
        assertTrue(r.editing)
        assertEquals(365, r.draftDays)
        assertEquals(null, r.confirm)
    }

    @Test
    fun `useJsonEmailFormat defaults to false`() {
        assertEquals(false, viewModel.state.value.useJsonEmailFormat)
    }

    @Test
    fun `onJsonEmailFormatChanged enables and persists`() {
        viewModel.onJsonEmailFormatChanged(true)
        assertEquals(true, viewModel.state.value.useJsonEmailFormat)
        verify(prefs).save(AppSettings(useJsonEmailFormat = true))
    }

    @Test
    fun `onJsonEmailFormatChanged disables`() {
        viewModel.onJsonEmailFormatChanged(true)
        viewModel.onJsonEmailFormatChanged(false)
        assertEquals(false, viewModel.state.value.useJsonEmailFormat)
        verify(prefs).save(AppSettings())
    }

    @Test
    fun `usePduTimestamp defaults to false`() {
        assertEquals(false, viewModel.state.value.usePduTimestamp)
    }

    @Test
    fun `dedupLevel defaults to Balanced`() {
        assertEquals(DedupLevel.BALANCED, viewModel.state.value.dedupLevel)
    }

    @Test
    fun `onPduTimestampChanged disables and persists`() {
        viewModel.onPduTimestampChanged(false)
        assertEquals(false, viewModel.state.value.usePduTimestamp)
        verify(prefs).save(AppSettings())
    }

    @Test
    fun `onPduTimestampChanged re-enables and persists`() {
        viewModel.onPduTimestampChanged(false)
        viewModel.onPduTimestampChanged(true)
        assertEquals(true, viewModel.state.value.usePduTimestamp)
        verify(prefs).save(AppSettings(usePduTimestamp = true))
    }

    @Test
    fun `includeTimeInBody defaults to false`() {
        assertEquals(false, viewModel.state.value.includeTimeInBody)
    }

    @Test
    fun `onIncludeTimeInBodyChanged enables and persists`() {
        viewModel.onIncludeTimeInBodyChanged(true)
        assertEquals(true, viewModel.state.value.includeTimeInBody)
        verify(prefs).save(AppSettings(includeTimeInBody = true))
    }

    @Test
    fun `onIncludeTimeInBodyChanged disables`() {
        viewModel.onIncludeTimeInBodyChanged(true)
        viewModel.onIncludeTimeInBodyChanged(false)
        assertEquals(false, viewModel.state.value.includeTimeInBody)
        verify(prefs).save(AppSettings())
    }

    @Test
    fun `setDedupLevel updates state and persists`() {
        viewModel.setDedupLevel(DedupLevel.AGGRESSIVE)
        assertEquals(DedupLevel.AGGRESSIVE, viewModel.state.value.dedupLevel)
        verify(prefs).save(AppSettings(dedupLevel = DedupLevel.AGGRESSIVE))
    }

    @Test
    fun `setDedupLevel can be set to Off`() {
        viewModel.setDedupLevel(DedupLevel.OFF)
        assertEquals(DedupLevel.OFF, viewModel.state.value.dedupLevel)
        verify(prefs).save(AppSettings(dedupLevel = DedupLevel.OFF))
    }

    @Test
    fun `SettingsState defaults exercise all data class parameter defaults`() {
        val defaults = SettingsState()
        assertEquals(EmailConfig(), defaults.config)
        assertEquals("", defaults.subjectPreview)
        assertEquals(RetentionUiState(), defaults.retention)
        assertEquals(LogToFileUiState(), defaults.logToFile)
        assertFalse(defaults.useJsonEmailFormat)
        assertEquals(AppSettings.DEFAULT_INCLUDE_TIME_IN_BODY, defaults.includeTimeInBody)
        assertEquals(ThemeMode.SYSTEM, defaults.themeMode)
        assertFalse(defaults.usePduTimestamp)
        assertEquals(DedupLevel.BALANCED, defaults.dedupLevel)
        assertEquals(LogLevel.DEFAULT, defaults.logLevel)
        assertEquals(AppSettings.DEFAULT_STICKY_NOTIFICATION, defaults.showStickyNotification)
        assertTrue(defaults.notificationsGranted)
        assertEquals(AppSettings.DEFAULT_RETRY_SWEEP_INTERVAL_MINUTES, defaults.retrySweepIntervalMinutes)
        assertEquals(AppSettings.DEFAULT_EXACT_WAKE_ENABLED, defaults.exactWakeEnabled)
        assertEquals(SmsFilter(), defaults.filter)
        assertEquals(AppSettings.DEFAULT_FILTER_ENABLED, defaults.filterEnabled)
        assertTrue(defaults.exactAlarmsGranted)
    }

    @Test
    fun `permissionManager is exposed for the screen to call directly`() {
        assertEquals(permissionManager, viewModel.permissionManager)
    }

    @Test
    fun `onLogConfirmed with null confirm is a no-op`() {
        viewModel.onLogConfirmed()
        val l = viewModel.state.value.logToFile
        assertFalse(l.enabled)
        assertEquals(null, l.confirm)
        assertFalse(l.editing)
    }

    @Test
    fun `importPicked toasts failure when the import fails`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        stubPickResolver("""{"version":999}""")
        clearInvocations(prefs)

        withMockedToast { toast ->
            val job = viewModel.importPicked(pickedUri)
            assertNotNull(job)
            awaitImport(job, dispatcher)
            toast.verify {
                Toast.makeText(eq(appContext), eq(R.string.config_import_failed), eq(Toast.LENGTH_SHORT))
            }
        }
    }

    @Test
    fun `syncExactAlarms arms the sweep only when forwarding is enabled and period is positive`() =
        runTest {
            `when`(exactWakeArmer.exactWakeActive()).thenReturn(true)
            `when`(prefs.load()).thenReturn(AppSettings(isForwardingEnabled = false, retrySweepIntervalMinutes = 15))

            viewModel.onExactWakeChanged(true)

            verify(exactWakeArmer).rearmAllRetryAlarms()
            verify(exactWakeArmer, never()).armSweepAlarm(any())
        }

    @Test
    fun `syncExactAlarms arms the sweep only when the period is greater than zero`() =
        runTest {
            `when`(exactWakeArmer.exactWakeActive()).thenReturn(true)
            `when`(prefs.load()).thenReturn(AppSettings(isForwardingEnabled = true, retrySweepIntervalMinutes = 0))

            viewModel.onExactWakeChanged(true)

            verify(exactWakeArmer).rearmAllRetryAlarms()
            verify(exactWakeArmer, never()).armSweepAlarm(any())
        }

    @Test
    fun `logLevel defaults to Error`() {
        assertEquals(LogLevel.ERROR, viewModel.state.value.logLevel)
    }

    @Test
    fun `setLogLevel updates state, persists and applies the threshold`() {
        viewModel.setLogLevel(LogLevel.WARNING)
        assertEquals(LogLevel.WARNING, viewModel.state.value.logLevel)
        verify(prefs).save(AppSettings(logLevel = LogLevel.WARNING))
        // The live logger follows the pick: below the threshold is dropped, at it kept.
        Logger.d("LogLevelVM", "dropped by the new threshold")
        val sizeAfterDrop = LogBuffer.entries.size
        Logger.w("LogLevelVM", "kept by the new threshold")
        assertEquals(sizeAfterDrop + 1, LogBuffer.entries.size)
        assertEquals("LogLevelVM", LogBuffer.entries.last().tag)
    }

    @Test
    fun `setLogLevel can be set to Error`() {
        viewModel.setLogLevel(LogLevel.ERROR)
        assertEquals(LogLevel.ERROR, viewModel.state.value.logLevel)
        verify(prefs).save(AppSettings(logLevel = LogLevel.ERROR))
    }

    @Test
    fun `initial state keeps the stored advanced settings`() {
        `when`(prefs.load()).thenReturn(AppSettings(dedupLevel = DedupLevel.MINIMAL, logLevel = LogLevel.OFF))
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        assertEquals(false, viewModel.state.value.usePduTimestamp)
        assertEquals(DedupLevel.MINIMAL, viewModel.state.value.dedupLevel)
        assertEquals(LogLevel.OFF, viewModel.state.value.logLevel)
    }

    @Test
    fun `initial theme mode is the stored pref`() {
        `when`(prefs.themeMode).thenReturn(ThemeMode.DARK)
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        assertEquals(ThemeMode.DARK, viewModel.state.value.themeMode)
    }

    @Test
    fun `setThemeMode updates state and delegates to the holder`() {
        viewModel.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, viewModel.state.value.themeMode)
        verify(themePreference).set(ThemeMode.LIGHT)
    }

    @Test
    fun `setAppLanguage applies the tag and updates state`() {
        viewModel.form.setAppLanguage("ru")
        assertEquals("ru", viewModel.state.value.config.appLanguage)
        assertEquals(LocaleListCompat.forLanguageTags("ru"), AppCompatDelegate.getApplicationLocales())
    }

    @Test
    fun `setAppLanguage with an empty tag resets to the system language`() {
        viewModel.form.setAppLanguage("ru")
        viewModel.form.setAppLanguage("")
        assertEquals("", viewModel.state.value.config.appLanguage)
        assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
    }

    @Test
    fun `setAppLanguage ignores a tag outside the shipped languages`() {
        viewModel.form.setAppLanguage("fr")
        assertEquals("", viewModel.state.value.config.appLanguage)
        assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
    }

    @Test
    fun `initial app language follows the AppCompat state`() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        assertEquals("en", viewModel.state.value.config.appLanguage)
    }

    // The complete draft (the wizard's rule) with both addresses filled, so the test
    // email is applicable.
    private fun fillCompleteConfig() {
        viewModel.form.onSmtpHostChanged("smtp.example.com")
        viewModel.form.onSmtpUsernameChanged("user")
        viewModel.form.onSmtpPasswordChanged("pass")
        viewModel.form.onDestEmailChanged("dest@example.com")
        viewModel.form.onDestNameChanged("Dest")
        viewModel.form.onSenderEmailChanged("sender@example.com")
        viewModel.form.onSenderNameChanged("Sender")
    }

    @Test
    fun `requestSaveEmail saves an unedited complete config without the untested warning`() {
        val stored =
            AppSettings(
                smtpHost = "smtp.example.com",
                smtpPort = 465,
                smtpUsername = "user",
                destEmail = "dest@example.com",
                senderEmail = "sender@example.com",
                autoCleanupRetentionDays = 30,
            )
        `when`(prefs.load()).thenReturn(stored)
        `when`(prefs.smtpPassword).thenReturn("pass")
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )

        assertEquals(EmailSaveOutcome.SAVED, viewModel.requestSaveEmail())
        verify(prefs).save(stored)
    }

    @Test
    fun `requestSaveEmail saves the complete tested config and writes all fields`() {
        fillCompleteConfig()
        viewModel.form.update { it.copy(test = it.test.copy(smtpTestResult = EmailResult.Success, testEmailResult = EmailResult.Success)) }
        val genBefore = viewModel.state.value.config.refreshGeneration
        assertEquals(EmailSaveOutcome.SAVED, viewModel.requestSaveEmail())
        verify(prefs).save(
            AppSettings(
                smtpHost = "smtp.example.com",
                smtpUsername = "user",
                destEmail = "dest@example.com",
                destName = "Dest",
                senderEmail = "sender@example.com",
                senderName = "Sender",
            ),
        )
        verify(prefs).smtpPassword = "pass"
        assertTrue(viewModel.state.value.config.refreshGeneration > genBefore)
    }

    @Test
    fun `requestSaveEmail asks for confirmation when the tests have not run and writes nothing`() {
        fillCompleteConfig()
        assertEquals(EmailSaveOutcome.NEEDS_CONFIRM, viewModel.requestSaveEmail())
        verify(prefs, never()).save(any())
        verify(prefs, never()).smtpPassword = any<String>()
    }

    @Test
    fun `requestSaveEmail asks for confirmation when the test email failed while the connection test succeeded`() {
        fillCompleteConfig()
        viewModel.form.update {
            it.copy(
                test =
                    it.test.copy(
                        smtpTestResult = EmailResult.Success,
                        testEmailResult = EmailResult.Failure("fail"),
                    ),
            )
        }
        assertEquals(EmailSaveOutcome.NEEDS_CONFIRM, viewModel.requestSaveEmail())
        verify(prefs, never()).save(any())
    }

    @Test
    fun `requestSaveEmail is incomplete when the sender email is cleared and the confirm refuses`() {
        fillCompleteConfig()
        viewModel.form.onSenderEmailChanged("")
        viewModel.form.update {
            it.copy(
                test =
                    it.test.copy(
                        smtpTestResult = EmailResult.Success,
                        testEmailResult = EmailResult.Failure("fail"),
                    ),
            )
        }
        // A cleared sender can never send, so the completeness gate blocks the save
        // and the "save anyway" confirm alike — no confirmation dialog is offered.
        assertEquals(EmailSaveOutcome.INCOMPLETE, viewModel.requestSaveEmail())
        assertEquals(EmailSaveOutcome.INCOMPLETE, viewModel.confirmSaveAnyway())
        verify(prefs, never()).save(any())
    }

    @Test
    fun `requestSaveEmail saves without confirmation when the test email succeeded and the connection test failed`() {
        fillCompleteConfig()
        viewModel.form.update {
            it.copy(
                test =
                    it.test.copy(
                        smtpTestResult = EmailResult.Failure("fail"),
                        testEmailResult = EmailResult.Success,
                    ),
            )
        }
        assertEquals(EmailSaveOutcome.SAVED, viewModel.requestSaveEmail())
        verify(prefs).save(
            AppSettings(
                smtpHost = "smtp.example.com",
                smtpUsername = "user",
                destEmail = "dest@example.com",
                destName = "Dest",
                senderEmail = "sender@example.com",
                senderName = "Sender",
            ),
        )
        verify(prefs).smtpPassword = "pass"
    }

    @Test
    fun `requestSaveEmail is incomplete when the password is blank and writes nothing`() {
        fillCompleteConfig()
        viewModel.form.onSmtpPasswordChanged("")
        viewModel.form.update { it.copy(test = it.test.copy(smtpTestResult = EmailResult.Success, testEmailResult = EmailResult.Success)) }
        assertEquals(EmailSaveOutcome.INCOMPLETE, viewModel.requestSaveEmail())
        verify(prefs, never()).save(any())
        verify(prefs, never()).smtpPassword = any<String>()
    }

    // The live port draft is part of the form's completeness: a red or empty port
    // field blocks the save (and the "save anyway" confirm) even though the
    // committed port is still in range.
    @Test
    fun `requestSaveEmail is incomplete with an invalid port draft and the confirm refuses`() {
        fillCompleteConfig()
        viewModel.form.onPortDraftChanged("70000")
        viewModel.form.update { it.copy(test = it.test.copy(smtpTestResult = EmailResult.Success, testEmailResult = EmailResult.Success)) }
        assertEquals(EmailSaveOutcome.INCOMPLETE, viewModel.requestSaveEmail())
        assertEquals(EmailSaveOutcome.INCOMPLETE, viewModel.confirmSaveAnyway())
        verify(prefs, never()).save(any())
        verify(prefs, never()).smtpPassword = any<String>()
    }

    @Test
    fun `requestSaveEmail is incomplete with an empty port draft and writes nothing`() {
        fillCompleteConfig()
        viewModel.form.onPortDraftChanged("")
        assertEquals(EmailSaveOutcome.INCOMPLETE, viewModel.requestSaveEmail())
        verify(prefs, never()).save(any())
        verify(prefs, never()).smtpPassword = any<String>()
    }

    @Test
    fun `confirmSaveAnyway commits the config`() {
        fillCompleteConfig()
        assertEquals(EmailSaveOutcome.SAVED, viewModel.confirmSaveAnyway())
        verify(prefs).save(
            AppSettings(
                smtpHost = "smtp.example.com",
                smtpUsername = "user",
                destEmail = "dest@example.com",
                destName = "Dest",
                senderEmail = "sender@example.com",
                senderName = "Sender",
            ),
        )
        verify(prefs).smtpPassword = "pass"
    }

    @Test
    fun `isComplete requires each wizard field`() {
        val base =
            AppSettings(
                smtpHost = "h",
                smtpPort = 587,
                smtpUsername = "u",
                destEmail = TestEmail.RECIPIENT,
                senderEmail = TestEmail.SELF,
            )
        assertTrue(base.isComplete("p"))
        assertFalse(base.copy(smtpHost = "").isComplete("p"))
        assertFalse(base.copy(smtpHost = "  ").isComplete("p"))
        assertFalse(base.copy(smtpPort = 0).isComplete("p"))
        assertFalse(base.copy(smtpPort = 65536).isComplete("p"))
        assertFalse(base.copy(smtpUsername = "").isComplete("p"))
        assertFalse(base.isComplete(""))
        assertFalse(base.copy(destEmail = "").isComplete("p"))
        assertFalse(base.copy(destEmail = "abc").isComplete("p"))
        assertFalse(base.copy(senderEmail = "").isComplete("p"))
        assertFalse(base.copy(senderEmail = "abc").isComplete("p"))
    }

    @Test
    fun `isComplete requires a valid sender email`() {
        val base =
            AppSettings(
                smtpHost = "h",
                smtpPort = 587,
                smtpUsername = "u",
                destEmail = TestEmail.RECIPIENT,
            )
        assertFalse(base.isComplete("p"))
        assertTrue(base.copy(senderEmail = TestEmail.SELF).isComplete("p"))
        assertFalse(base.copy(senderEmail = "not-an-email").isComplete("p"))
    }

    @Test
    fun `isEmailSaveReady is false when the test email cannot run`() {
        // No sender email: the test email is not runnable, so it can never have
        // succeeded, and the connection test alone does not make the save ready.
        val config =
            EmailConfig(
                settings =
                    AppSettings(
                        smtpHost = "h",
                        smtpPort = 587,
                        smtpUsername = "u",
                        destEmail = TestEmail.RECIPIENT,
                    ),
                smtpPassword = "p",
                test = TestRunState(smtpTestResult = EmailResult.Success),
            )
        assertFalse(isEmailSaveReady(config))
    }

    @Test
    fun `isEmailSaveReady is ready with the test email success and a failed connection test`() {
        val config =
            EmailConfig(
                settings =
                    AppSettings(
                        smtpHost = "h",
                        smtpPort = 587,
                        smtpUsername = "u",
                        destEmail = TestEmail.RECIPIENT,
                        senderEmail = TestEmail.SELF,
                    ),
                smtpPassword = "p",
                test =
                    TestRunState(
                        smtpTestResult = EmailResult.Failure("fail"),
                        testEmailResult = EmailResult.Success,
                    ),
            )
        assertTrue(isEmailSaveReady(config))
    }

    @Test
    fun `isEmailSaveReady is false when the test email has not run`() {
        val config =
            EmailConfig(
                settings =
                    AppSettings(
                        smtpHost = "h",
                        smtpPort = 587,
                        smtpUsername = "u",
                        destEmail = TestEmail.RECIPIENT,
                        senderEmail = TestEmail.SELF,
                    ),
                smtpPassword = "p",
                test = TestRunState(smtpTestResult = EmailResult.Success),
            )
        assertFalse(isEmailSaveReady(config))
    }

    @Test
    fun `isEmailSaveReady is false when the test email failed`() {
        val config =
            EmailConfig(
                settings =
                    AppSettings(
                        smtpHost = "h",
                        smtpPort = 587,
                        smtpUsername = "u",
                        destEmail = TestEmail.RECIPIENT,
                        senderEmail = TestEmail.SELF,
                    ),
                smtpPassword = "p",
                test =
                    TestRunState(
                        smtpTestResult = EmailResult.Success,
                        testEmailResult = EmailResult.Failure("fail"),
                    ),
            )
        assertFalse(isEmailSaveReady(config))
    }

    @Test
    fun `isEmailSaveReady is the test half of the gate and ignores completeness`() {
        // Completeness is the gate's other half (requestSaveEmail checks it), so
        // a succeeded test email is ready even on an incomplete config.
        val config = EmailConfig(test = TestRunState(testEmailResult = EmailResult.Success))
        assertTrue(isEmailSaveReady(config))
    }

    @Test
    fun `importPicked reads the picked file, applies it and toasts success`() {
        // The import's I/O phase runs on a real IO thread; with the unconfined test
        // dispatcher its resumption would run inline on that thread, where the static
        // Toast mock is not visible (mockStatic is thread-bound) and the real Toast
        // has no looper — a standard dispatcher routes it through the test scheduler.
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        stubPickResolver(FULL_IMPORT_JSON)
        clearInvocations(prefs)

        withMockedToast { toast ->
            val job = viewModel.importPicked(pickedUri)
            assertNotNull(job)
            awaitImport(job, dispatcher)
            toast.verify {
                Toast.makeText(eq(appContext), eq(R.string.config_import_success), eq(Toast.LENGTH_SHORT))
            }
        }

        verify(prefs).save(
            AppSettings(
                smtpHost = "smtp.example.com",
                smtpPort = 465,
                smtpProtocol = SmtpProtocol.IMPLICIT_TLS,
                smtpUsername = TestEmail.SELF,
                destEmail = "dest@example.com",
                senderEmail = "sender@example.com",
                autoCleanupRetentionDays = 365,
                usePduTimestamp = true,
            ),
        )
    }

    private val pickedUri = Uri.parse("content://com.android.documentsui/documents/pick")

    private fun stubPickResolver(json: String) {
        val resolver: ContentResolver = mock()
        val cursor: Cursor = mock()
        whenever(appContext.contentResolver).thenReturn(resolver)
        whenever(
            resolver.query(
                any<Uri>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
            ),
        ).thenReturn(cursor)
        whenever(cursor.moveToFirst()).thenReturn(true)
        whenever(cursor.getColumnIndex(OpenableColumns.SIZE)).thenReturn(0)
        whenever(cursor.isNull(0)).thenReturn(false)
        whenever(cursor.getLong(0)).thenReturn(json.length.toLong())
        whenever(resolver.openInputStream(any<Uri>())).thenReturn(ByteArrayInputStream(json.toByteArray()))
    }

    // The import's I/O phase runs on a real thread; its resumption lands on [dispatcher]'s
    // scheduler, so the wait advances it until the import completes.
    private fun awaitImport(
        job: Job,
        dispatcher: TestDispatcher,
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        while (job.isActive) {
            dispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(5)
            check(System.currentTimeMillis() < deadline) { "the import did not complete" }
        }
    }

    @Test
    fun `setSweepInterval commits a preset and drops a non-preset`() {
        viewModel.setSweepInterval(360)
        assertEquals(360, viewModel.state.value.retrySweepIntervalMinutes)
        verify(prefs).update(any())

        viewModel.setSweepInterval(7)
        assertEquals(360, viewModel.state.value.retrySweepIntervalMinutes)
        verify(prefs, times(1)).update(any())
    }

    @Test
    fun `a non-preset stored sweep interval degrades to the default in the state`() {
        `when`(prefs.load()).thenReturn(AppSettings(retrySweepIntervalMinutes = 7))
        viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                contactResolver,
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )

        assertEquals(15, viewModel.state.value.retrySweepIntervalMinutes)
    }

    @Test
    fun `onExactWakeChanged commits the setting and re-arms the tokens while active`() =
        runTest {
            `when`(exactWakeArmer.exactWakeActive()).thenReturn(true)

            viewModel.onExactWakeChanged(true)

            assertTrue(viewModel.state.value.exactWakeEnabled)
            verify(prefs).update(any())
            verify(exactWakeArmer).rearmAllRetryAlarms()
            // The default settings carry forwarding on and the 15-minute period: the sweep
            // token is armed too.
            verify(exactWakeArmer).armSweepAlarm(any())
            verify(exactWakeArmer, never()).cancelSweepAlarm()
            verify(exactWakeArmer, never()).cancelAllRetryAlarms(any())
        }

    @Test
    fun `onExactWakeChanged cancels every token while the gate is inactive`() =
        runTest {
            `when`(exactWakeArmer.exactWakeActive()).thenReturn(false)

            viewModel.onExactWakeChanged(false)

            assertFalse(viewModel.state.value.exactWakeEnabled)
            verify(exactWakeArmer).cancelAll()
            verify(exactWakeArmer, never()).rearmAllRetryAlarms()
        }

    @Test
    fun `refreshExactAlarmPermission syncs the tokens on a grant transition only`() =
        runTest {
            // Build the VM with the permission ungranted (the state seeds false).
            `when`(permissionManager.canScheduleExactAlarms()).thenReturn(false)
            viewModel =
                SettingsViewModel(
                    appContext,
                    prefs,
                    SmtpTestRunner(SmtpTester(emailService) { true }),
                    contactResolver,
                    permissionManager,
                    themePreference,
                    backgroundStateReconciler,
                    exactWakeArmer,
                )
            assertFalse(viewModel.state.value.exactAlarmsGranted)

            viewModel.refreshExactAlarmPermission()
            verify(exactWakeArmer, never()).rearmAllRetryAlarms()

            `when`(permissionManager.canScheduleExactAlarms()).thenReturn(true)
            `when`(exactWakeArmer.exactWakeActive()).thenReturn(true)
            viewModel.refreshExactAlarmPermission()
            assertTrue(viewModel.state.value.exactAlarmsGranted)
            verify(exactWakeArmer).rearmAllRetryAlarms()
        }

    @Test
    fun `refreshExactAlarmPermission cancels the tokens on a revoke transition`() =
        runTest {
            viewModel.refreshExactAlarmPermission() // seeds the state granted (the default stub)
            clearInvocations(exactWakeArmer)
            `when`(permissionManager.canScheduleExactAlarms()).thenReturn(false)

            viewModel.refreshExactAlarmPermission()

            assertFalse(viewModel.state.value.exactAlarmsGranted)
            verify(exactWakeArmer).cancelAll()
        }

    @Suppress("ktlint:standard:max-line-length")
    private companion object {
        val FULL_IMPORT_JSON =
            """{"version":1,"smtp_host":"smtp.example.com","smtp_port":465,"smtp_protocol":"IMPLICIT_TLS","smtp_username":"${TestEmail.SELF}","dest_email":"dest@example.com","dest_name":"","sender_email":"sender@example.com","sender_name":"","subject_prefix":"","auto_subject_prefix":false,"forwarding_enabled":true,"contact_resolution":true,"display_sim_cards":false,"auto_cleanup_retention_days":365,"json_email_format":false,"use_pdu_timestamp":true,"dedup_level":"BALANCED"}"""
    }
}
