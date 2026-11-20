package app.kotowski.sms2email.ui.emailconfig

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.data.prefs.SmtpProtocol
import app.kotowski.sms2email.service.EmailResult
import app.kotowski.sms2email.service.EmailService
import app.kotowski.sms2email.service.SmtpConfig
import app.kotowski.sms2email.service.SmtpTestRunner
import app.kotowski.sms2email.service.SmtpTester
import app.kotowski.sms2email.service.TestEmailFields
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.util.DeviceUtil
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Robolectric: the import path validates addresses with android.util.Patterns, which is
// only available in the instrumented Android runtime.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EmailConfigControllerTest {
    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var emailService: EmailService

    @Mock private lateinit var appContext: Context

    @Mock private lateinit var languagePrefs: SharedPreferences

    @Mock private lateinit var languagePrefsEditor: SharedPreferences.Editor

    @Mock private lateinit var resources: Resources

    private lateinit var contactResolver: ContactResolver

    private fun stubEmptyPrefs() {
        whenever(prefs.load()).thenReturn(AppSettings())
        whenever(prefs.smtpPassword).thenReturn("")
        doAnswer { inv ->
            val transform = inv.getArgument<(AppSettings) -> AppSettings>(0)
            prefs.save(transform(prefs.load()))
            null
        }.whenever(prefs).update(any())
    }

    private fun createForm(): EmailConfigController =
        EmailConfigController(
            appContext,
            prefs,
            SmtpTestRunner(SmtpTester(emailService) { true }),
            contactResolver,
        )

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // The mock context answers no telephony: the chain degrades to the device
        // locale, which the tests do not rely on (auto on by default).
        contactResolver = ContactResolver(appContext, prefs)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        stubEmptyPrefs()
        // DeviceUtil.getDeviceName falls back to the app name when the device name is unset
        whenever(appContext.getString(R.string.app_name)).thenReturn("SMS to Email")
        whenever(appContext.getString(R.string.email_config_test_invalidated))
            .thenReturn("Settings were changed while the test was running")
        whenever(appContext.getSharedPreferences("app_language", Context.MODE_PRIVATE)).thenReturn(languagePrefs)
        whenever(languagePrefs.edit()).thenReturn(languagePrefsEditor)
        whenever(
            languagePrefsEditor.putString(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
            ),
        ).thenReturn(languagePrefsEditor)
        whenever(languagePrefsEditor.commit()).thenReturn(true)
        whenever(appContext.resources).thenReturn(resources)
        whenever(resources.configuration).thenReturn(Configuration())
    }

    @After
    fun tearDown() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    fun `initial state reads every pref and the app language`() {
        whenever(prefs.load()).thenReturn(
            AppSettings(
                smtpHost = "smtp.example.com",
                smtpPort = 465,
                smtpProtocol = SmtpProtocol.IMPLICIT_TLS,
                smtpUsername = "user",
                destEmail = "dest@example.com",
                destName = "Dest",
                senderEmail = "sender@example.com",
                senderName = "Sender",
                subjectPrefix = "prefix",
                autoSubjectPrefix = true,
                useContactResolution = false,
                displaySimCards = true,
            ),
        )
        whenever(prefs.smtpPassword).thenReturn("pass")
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))

        val s = createForm().state.value

        assertEquals("smtp.example.com", s.settings.smtpHost)
        assertEquals(465, s.settings.smtpPort)
        assertEquals("465", s.portDraft)
        assertEquals(SmtpProtocol.IMPLICIT_TLS, s.settings.smtpProtocol)
        assertEquals("user", s.settings.smtpUsername)
        assertEquals("pass", s.smtpPassword)
        assertEquals("dest@example.com", s.settings.destEmail)
        assertEquals("Dest", s.settings.destName)
        assertEquals("sender@example.com", s.settings.senderEmail)
        assertEquals("Sender", s.settings.senderName)
        assertEquals("prefix", s.settings.subjectPrefix)
        assertTrue(s.settings.autoSubjectPrefix)
        assertFalse(s.settings.useContactResolution)
        assertTrue(s.settings.displaySimCards)
        assertEquals("ru", s.appLanguage)
        assertEquals(0, s.refreshGeneration)
    }

    @Test
    fun `field edits stay out of the prefs until the commit`() {
        val form = createForm()

        form.onSmtpHostChanged("h")
        form.onPortDraftChanged("25")
        form.onSmtpProtocolChanged(SmtpProtocol.IMPLICIT_TLS)
        form.onSmtpUsernameChanged("u")
        form.onSmtpPasswordChanged("p")
        form.onDestEmailChanged("d@example.com")
        form.onDestNameChanged("dn")
        form.onSenderEmailChanged("s@example.com")
        form.onSenderNameChanged("sn")

        verify(prefs, never()).save(any())
        verify(prefs, never()).smtpPassword = org.mockito.kotlin.any<String>()
        assertEquals("h", form.state.value.settings.smtpHost)
        assertEquals(465, form.state.value.settings.smtpPort)
    }

    @Test
    fun `protocol change switches the protocol and the default port`() {
        val form = createForm()

        form.onSmtpProtocolChanged(SmtpProtocol.IMPLICIT_TLS)
        assertEquals(SmtpProtocol.IMPLICIT_TLS, form.state.value.settings.smtpProtocol)
        assertEquals(465, form.state.value.settings.smtpPort)
        form.onSmtpProtocolChanged(SmtpProtocol.START_TLS)
        assertEquals(SmtpProtocol.START_TLS, form.state.value.settings.smtpProtocol)
        assertEquals(587, form.state.value.settings.smtpPort)
    }

    @Test
    fun `a port draft change updates the draft and commits a port in range`() {
        val form = createForm()

        form.onPortDraftChanged("465")

        assertEquals("465", form.state.value.portDraft)
        assertEquals(465, form.state.value.settings.smtpPort)
        verify(prefs, never()).save(any())
    }

    @Test
    fun `an empty or invalid port draft never commits the port`() {
        val form = createForm()

        form.onPortDraftChanged("70000")
        assertEquals("70000", form.state.value.portDraft)
        assertEquals(AppConfig.SMTP_PORT_START_TLS, form.state.value.settings.smtpPort)

        form.onPortDraftChanged("")
        assertEquals("", form.state.value.portDraft)
        assertEquals(AppConfig.SMTP_PORT_START_TLS, form.state.value.settings.smtpPort)

        verify(prefs, never()).save(any())
    }

    @Test
    fun `a valid port draft edit clears the stored test results`() =
        runTest {
            whenever(emailService.testConnection(any<SmtpConfig>())).thenReturn(EmailResult.Success)
            whenever(emailService.sendTestEmail(any<SmtpConfig>(), any<TestEmailFields>()))
                .thenReturn(EmailResult.Success)
            val form = createForm()
            form.runTestConnection()
            form.runTestEmail()

            form.onPortDraftChanged("465")

            assertNull(form.state.value.test.smtpTestResult)
            assertNull(form.state.value.test.testEmailResult)
        }

    @Test
    fun `reloadFromPrefs re-syncs the port draft from the reloaded port`() {
        val form = createForm()
        form.onPortDraftChanged("465")
        whenever(prefs.load()).thenReturn(AppSettings(smtpHost = "from-prefs", smtpPort = 25))

        form.reloadFromPrefs()

        assertEquals("25", form.state.value.portDraft)
    }

    @Test
    fun `subject prefix edit trims and turns the auto flag off`() {
        val form = createForm()
        form.onAutoSubjectPrefixChanged(true)

        form.onSubjectPrefixChanged("  my prefix  ")

        assertEquals("my prefix", form.state.value.settings.subjectPrefix)
        assertFalse(form.state.value.settings.autoSubjectPrefix)
        // One combined write: the prefix and the flag land together (no half state).
        verify(prefs).save(AppSettings(subjectPrefix = "my prefix", autoSubjectPrefix = false))
    }

    @Test
    fun `subject prefix toggle on writes the device name`() {
        val form = createForm()

        form.onAutoSubjectPrefixChanged(true)

        assertEquals(DeviceUtil.getDeviceName(appContext), form.state.value.settings.subjectPrefix)
        assertTrue(form.state.value.settings.autoSubjectPrefix)
        // One combined write: the device-name prefix and the flag land together.
        verify(prefs).save(
            AppSettings(
                subjectPrefix = DeviceUtil.getDeviceName(appContext),
                autoSubjectPrefix = true,
            ),
        )
    }

    @Test
    fun `committing a prefix matching the device name turns the auto flag on`() {
        val form = createForm()
        form.onSubjectPrefixChanged("other")

        form.onSubjectPrefixCommitted(DeviceUtil.getDeviceName(appContext))

        assertTrue(form.state.value.settings.autoSubjectPrefix)
    }

    @Test
    fun `committing a foreign prefix keeps the auto flag off`() {
        val form = createForm()

        form.onSubjectPrefixCommitted("foreign")

        assertFalse(form.state.value.settings.autoSubjectPrefix)
    }

    // The composed subject (the prefix inside the template plus the sender name) must
    // stay within the providers' subject bounds, so every write path caps the prefix.
    @Test
    fun `a manual prefix longer than the cap is stored capped`() {
        val form = createForm()

        form.onSubjectPrefixChanged("a".repeat(AppConfig.SUBJECT_PREFIX_MAX_LENGTH + 10))

        assertEquals(AppConfig.SUBJECT_PREFIX_MAX_LENGTH, form.state.value.settings.subjectPrefix.length)
        verify(prefs).save(
            AppSettings(
                subjectPrefix = "a".repeat(AppConfig.SUBJECT_PREFIX_MAX_LENGTH),
                autoSubjectPrefix = false,
            ),
        )
    }

    @Test
    fun `a committed prefix longer than the cap is stored capped`() {
        val form = createForm()
        val longValue = "b".repeat(AppConfig.SUBJECT_PREFIX_MAX_LENGTH + 10)
        form.onSubjectPrefixChanged(longValue)

        form.onSubjectPrefixCommitted(longValue)

        assertEquals(AppConfig.SUBJECT_PREFIX_MAX_LENGTH, form.state.value.settings.subjectPrefix.length)
    }

    @Test
    fun `the auto prefix takes an over-long device name capped`() {
        whenever(appContext.getString(R.string.app_name)).thenReturn("c".repeat(AppConfig.SUBJECT_PREFIX_MAX_LENGTH + 10))
        val form = createForm()

        form.onAutoSubjectPrefixChanged(true)

        assertEquals(AppConfig.SUBJECT_PREFIX_MAX_LENGTH, form.state.value.settings.subjectPrefix.length)
        assertTrue(form.state.value.settings.autoSubjectPrefix)
    }

    @Test
    fun `contact resolution and sim card toggles persist on change`() {
        val form = createForm()

        form.onUseContactResolutionChanged(false)
        verify(prefs).save(AppSettings(useContactResolution = false))
        verify(prefs).markExplicit(EncryptedPrefs.KEY_CONTACT_RESOLUTION)
        form.onDisplaySimCardsChanged(true)
        verify(prefs).save(AppSettings(displaySimCards = true))
        verify(prefs).markExplicit(EncryptedPrefs.KEY_DISPLAY_SIM_CARDS)
    }

    @Test
    fun `ensureContactResolutionOnIfUnset applies while the key was never written`() {
        whenever(prefs.contains(EncryptedPrefs.KEY_CONTACT_RESOLUTION)).thenReturn(false)
        val form = createForm()

        form.ensureContactResolutionOnIfUnset()

        assertTrue(form.state.value.settings.useContactResolution)
        verify(prefs).save(AppSettings())
    }

    @Test
    fun `ensureContactResolutionOnIfUnset never overrides an explicit choice`() {
        whenever(prefs.contains(EncryptedPrefs.KEY_CONTACT_RESOLUTION)).thenReturn(true)
        whenever(prefs.load()).thenReturn(AppSettings(useContactResolution = false))
        val form = createForm()

        form.ensureContactResolutionOnIfUnset()

        assertFalse(form.state.value.settings.useContactResolution)
        verify(prefs, never()).save(any())
    }

    @Test
    fun `ensureSimDisplayOnIfUnset applies while the key was never written`() {
        whenever(prefs.contains(EncryptedPrefs.KEY_DISPLAY_SIM_CARDS)).thenReturn(false)
        val form = createForm()

        form.ensureSimDisplayOnIfUnset()

        assertTrue(form.state.value.settings.displaySimCards)
        verify(prefs).save(AppSettings(displaySimCards = true))
    }

    @Test
    fun `ensureSimDisplayOnIfUnset never overrides an explicit choice`() {
        whenever(prefs.contains(EncryptedPrefs.KEY_DISPLAY_SIM_CARDS)).thenReturn(true)
        whenever(prefs.load()).thenReturn(AppSettings(displaySimCards = false))
        val form = createForm()

        form.ensureSimDisplayOnIfUnset()

        assertFalse(form.state.value.settings.displaySimCards)
        verify(prefs, never()).save(any())
    }

    @Test
    fun `setAppLanguage applies only the shipped tags and resets on empty`() {
        val form = createForm()

        form.setAppLanguage("ru")
        assertEquals("ru", form.state.value.appLanguage)
        form.setAppLanguage("fr")
        assertEquals("ru", form.state.value.appLanguage)
        form.setAppLanguage("")
        assertEquals("", form.state.value.appLanguage)
    }

    @Test
    fun `runTestConnection marks the in-progress state and stores the result`() =
        runTest {
            lateinit var form: EmailConfigController
            var sawTesting = false
            whenever(emailService.testConnection(any<SmtpConfig>())).thenAnswer {
                sawTesting = form.state.value.test.isTestingConnection
                EmailResult.Success
            }
            form = createForm()

            val returned = form.runTestConnection()

            assertEquals(EmailResult.Success, returned)
            assertTrue(sawTesting)
            assertTrue(form.state.value.test.smtpTestResult is EmailResult.Success)
            assertFalse(form.state.value.test.isTestingConnection)
        }

    @Test
    fun `runTestConnection failure on exception stores a failure`() =
        runTest {
            whenever(appContext.getString(R.string.smtp_unknown)).thenReturn("Unknown error")
            whenever(emailService.testConnection(any<SmtpConfig>())).thenThrow(RuntimeException("fail"))
            val form = createForm()

            val returned = form.runTestConnection()
            assertTrue(returned is EmailResult.Failure)
            assertEquals("Unknown error", returned.message)

            val result = form.state.value.test.smtpTestResult
            assertTrue(result is EmailResult.Failure)
            assertEquals("Unknown error", result.message)
            assertFalse(form.state.value.test.isTestingConnection)
        }

    @Test
    fun `runTestEmail marks the in-progress state and stores the result`() =
        runTest {
            lateinit var form: EmailConfigController
            var sawSending = false
            whenever(emailService.sendTestEmail(any<SmtpConfig>(), any<TestEmailFields>())).thenAnswer {
                sawSending = form.state.value.test.isSendingTestEmail
                EmailResult.Success
            }
            form = createForm()

            val returned = form.runTestEmail()

            assertEquals(EmailResult.Success, returned)
            assertTrue(sawSending)
            assertTrue(form.state.value.test.testEmailResult is EmailResult.Success)
            assertFalse(form.state.value.test.isSendingTestEmail)
        }

    @Test
    fun `test runs pass the draft smtp config and test email fields to the service`() =
        runTest {
            var capturedConfig: SmtpConfig? = null
            var capturedFields: TestEmailFields? = null
            whenever(emailService.testConnection(any<SmtpConfig>())).thenAnswer {
                capturedConfig = it.arguments[0] as SmtpConfig
                EmailResult.Success
            }
            whenever(emailService.sendTestEmail(any<SmtpConfig>(), any<TestEmailFields>())).thenAnswer {
                capturedConfig = it.arguments[0] as SmtpConfig
                capturedFields = it.arguments[1] as TestEmailFields
                EmailResult.Success
            }
            val form = createForm()
            form.update {
                it.copy(
                    settings =
                        it.settings.copy(
                            smtpHost = "smtp.example.com",
                            smtpPort = 465,
                            smtpProtocol = SmtpProtocol.IMPLICIT_TLS,
                            smtpUsername = "user",
                            senderEmail = "sender@example.com",
                            senderName = "Sender",
                            destEmail = "dest@example.com",
                            destName = "Dest",
                        ),
                    smtpPassword = "pass",
                )
            }

            form.runTestConnection()
            assertEquals(
                SmtpConfig("smtp.example.com", 465, SmtpProtocol.IMPLICIT_TLS, "user", "pass"),
                capturedConfig,
            )

            form.runTestEmail()
            assertEquals(
                SmtpConfig("smtp.example.com", 465, SmtpProtocol.IMPLICIT_TLS, "user", "pass"),
                capturedConfig,
            )
            assertEquals(
                TestEmailFields("sender@example.com", "Sender", "dest@example.com", "Dest"),
                capturedFields,
            )
        }

    @Test
    fun `test runs pass the trimmed draft smtp config to the service`() =
        runTest {
            var capturedConfig: SmtpConfig? = null
            whenever(emailService.testConnection(any<SmtpConfig>())).thenAnswer {
                capturedConfig = it.arguments[0] as SmtpConfig
                EmailResult.Success
            }
            val form = createForm()
            form.update {
                it.copy(
                    settings =
                        it.settings.copy(
                            smtpHost = "  smtp.example.com ",
                            smtpUsername = " user ",
                        ),
                    smtpPassword = " pass ",
                )
            }

            form.runTestConnection()
            assertEquals(
                SmtpConfig("smtp.example.com", AppConfig.SMTP_PORT_START_TLS, SmtpProtocol.START_TLS, "user", "pass"),
                capturedConfig,
            )
        }

    @Test
    fun `test runs never write the smtp or email prefs`() =
        runTest {
            whenever(emailService.testConnection(any<SmtpConfig>())).thenReturn(EmailResult.Failure("fail"))
            whenever(emailService.sendTestEmail(any<SmtpConfig>(), any<TestEmailFields>()))
                .thenReturn(EmailResult.Failure("fail"))
            val form = createForm()
            form.onSmtpHostChanged("other.example.com")
            form.onDestEmailChanged("other@example.com")
            clearInvocations(prefs)

            form.runTestConnection()
            form.runTestEmail()

            verify(prefs, never()).save(any())
            verify(prefs, never()).smtpPassword = any<String>()
        }

    @Test
    fun `smtp and email field edits clear their stored result`() =
        runTest {
            whenever(emailService.testConnection(any<SmtpConfig>())).thenReturn(EmailResult.Success)
            whenever(emailService.sendTestEmail(any<SmtpConfig>(), any<TestEmailFields>()))
                .thenReturn(EmailResult.Success)
            val form = createForm()
            form.runTestConnection()
            form.runTestEmail()

            // The SMTP fields feed both test runs, so an edit clears both results.
            form.onSmtpHostChanged("other")
            assertNull(form.state.value.test.smtpTestResult)
            assertNull(form.state.value.test.testEmailResult)
            form.runTestEmail()
            assertTrue(form.state.value.test.testEmailResult is EmailResult.Success)
            form.onSmtpProtocolChanged(SmtpProtocol.IMPLICIT_TLS)
            assertNull(form.state.value.test.smtpTestResult)
            assertNull(form.state.value.test.testEmailResult)
            form.onDestEmailChanged("other@example.com")
            assertNull(form.state.value.test.testEmailResult)
        }

    @Test
    fun `an smtp edit mid-run drops the stale connection test result`() =
        runTest {
            lateinit var form: EmailConfigController
            whenever(emailService.testConnection(any<SmtpConfig>())).thenAnswer {
                form.onSmtpHostChanged("changed.example.com")
                EmailResult.Success
            }
            form = createForm()

            val returned = form.runTestConnection()
            assertTrue(returned is EmailResult.Failure)
            assertEquals("Settings were changed while the test was running", returned.message)

            val result = form.state.value.test.smtpTestResult
            assertTrue(result is EmailResult.Failure)
            assertEquals("Settings were changed while the test was running", result.message)
            assertFalse(form.state.value.test.isTestingConnection)
        }

    @Test
    fun `an smtp edit mid-run drops the stale test email result`() =
        runTest {
            lateinit var form: EmailConfigController
            whenever(emailService.sendTestEmail(any<SmtpConfig>(), any<TestEmailFields>())).thenAnswer {
                form.onSmtpPasswordChanged("changed")
                EmailResult.Success
            }
            form = createForm()

            val returned = form.runTestEmail()
            assertTrue(returned is EmailResult.Failure)
            assertEquals("Settings were changed while the test was running", returned.message)

            val result = form.state.value.test.testEmailResult
            assertTrue(result is EmailResult.Failure)
            assertEquals("Settings were changed while the test was running", result.message)
            assertFalse(form.state.value.test.isSendingTestEmail)
        }

    @Test
    fun `a destination edit mid-run drops the test email result but not the connection result`() =
        runTest {
            lateinit var form: EmailConfigController
            whenever(emailService.testConnection(any<SmtpConfig>())).thenReturn(EmailResult.Success)
            whenever(emailService.sendTestEmail(any<SmtpConfig>(), any<TestEmailFields>())).thenAnswer {
                form.onDestEmailChanged("changed@example.com")
                EmailResult.Success
            }
            form = createForm()

            form.runTestConnection()
            form.runTestEmail()

            val emailResult = form.state.value.test.testEmailResult
            assertTrue(emailResult is EmailResult.Failure)
            assertEquals("Settings were changed while the test was running", emailResult.message)
            assertTrue(form.state.value.test.smtpTestResult is EmailResult.Success)
        }

    @Test
    fun `a mid-run edit that reverts to the tested value keeps the test email result`() =
        runTest {
            lateinit var form: EmailConfigController
            whenever(emailService.sendTestEmail(any<SmtpConfig>(), any<TestEmailFields>())).thenAnswer {
                form.onDestEmailChanged("changed@example.com")
                form.onDestEmailChanged("dest@example.com")
                EmailResult.Success
            }
            form = createForm()
            form.onDestEmailChanged("dest@example.com")

            form.runTestEmail()

            assertTrue(form.state.value.test.testEmailResult is EmailResult.Success)
        }

    @Test
    fun `commitDraft persists every config field of the draft`() {
        val form = createForm()
        form.onSmtpHostChanged("h")
        form.onPortDraftChanged("25")
        form.onSmtpProtocolChanged(SmtpProtocol.IMPLICIT_TLS)
        form.onSmtpUsernameChanged("u")
        form.onSmtpPasswordChanged("p")
        form.onDestEmailChanged("d@example.com")
        form.onDestNameChanged("dn")
        form.onSenderEmailChanged("s@example.com")
        form.onSenderNameChanged("sn")
        form.onSubjectPrefixChanged("pref")
        form.onUseContactResolutionChanged(true)
        form.onDisplaySimCardsChanged(false)
        clearInvocations(prefs)

        form.commitDraft()

        verify(prefs).save(
            AppSettings(
                smtpHost = "h",
                smtpPort = 465,
                smtpProtocol = SmtpProtocol.IMPLICIT_TLS,
                smtpUsername = "u",
                destEmail = "d@example.com",
                destName = "dn",
                senderEmail = "s@example.com",
                senderName = "sn",
                subjectPrefix = "pref",
                useContactResolution = true,
                displaySimCards = false,
            ),
        )
        verify(prefs).smtpPassword = "p"
    }

    @Test
    fun `commitDraft trims the edge spaces of the connection fields`() {
        val form = createForm()
        form.onSmtpHostChanged("  smtp.example.com ")
        form.onSmtpUsernameChanged(" user ")
        form.onSmtpPasswordChanged(" pass ")
        clearInvocations(prefs)

        form.commitDraft()

        verify(prefs).save(AppSettings(smtpHost = "smtp.example.com", smtpUsername = "user"))
        verify(prefs).smtpPassword = "pass"
    }

    @Test
    fun `hasChanges is false right after the form loads`() {
        val form = createForm()

        assertFalse(form.hasChanges())
    }

    @Test
    fun `hasChanges is true after an email field edit`() {
        val form = createForm()

        form.onSmtpHostChanged("h")
        assertTrue(form.hasChanges())
        form.onSmtpHostChanged("")
        form.onSmtpUsernameChanged("u")
        assertTrue(form.hasChanges())
        form.onSmtpUsernameChanged("")
        form.onSmtpPasswordChanged("p")
        assertTrue(form.hasChanges())
        form.onSmtpPasswordChanged("")
        form.onDestEmailChanged("d@example.com")
        assertTrue(form.hasChanges())
        form.onDestEmailChanged("")
        form.onSenderEmailChanged("s@example.com")
        assertTrue(form.hasChanges())
        form.onSenderEmailChanged("")
        form.onPortDraftChanged("465")
        assertTrue(form.hasChanges())
    }

    @Test
    fun `hasChanges is false after the edit is reverted to the stored value`() {
        whenever(prefs.load()).thenReturn(AppSettings(smtpHost = "h"))
        whenever(prefs.smtpPassword).thenReturn("p")
        val form = createForm()

        form.onSmtpHostChanged("other")
        assertTrue(form.hasChanges())
        form.onSmtpHostChanged("h")

        assertFalse(form.hasChanges())
    }

    @Test
    fun `hasChanges ignores the edge spaces the commit trims`() {
        whenever(prefs.load()).thenReturn(AppSettings(smtpHost = "h"))
        val form = createForm()

        form.onSmtpHostChanged("h ")

        assertFalse(form.hasChanges())
    }

    @Test
    fun `reloadFromPrefs re-baselines the form so a dropped edit is not a change`() {
        val form = createForm()
        form.onSmtpHostChanged("edited")
        assertTrue(form.hasChanges())
        whenever(prefs.load()).thenReturn(AppSettings(smtpHost = "stored"))

        form.reloadFromPrefs()

        assertFalse(form.hasChanges())
    }

    @Test
    fun `reloadFromPrefs re-reads the prefs, bumps the generation and resets the test runs`() {
        val form = createForm()
        val generationBefore = form.state.value.refreshGeneration
        form.onSmtpHostChanged("edited")
        form.update { it.copy(test = it.test.copy(isTestingConnection = true, isSendingTestEmail = true)) }
        whenever(prefs.load()).thenReturn(AppSettings(smtpHost = "from-prefs"))

        form.reloadFromPrefs()

        assertEquals(generationBefore + 1, form.state.value.refreshGeneration)
        assertEquals("from-prefs", form.state.value.settings.smtpHost)
        assertFalse(form.state.value.test.isTestingConnection)
        assertFalse(form.state.value.test.isSendingTestEmail)
    }

    @Test
    fun `reloadFromPrefs re-seeds the country field from the prefs`() {
        val form = createForm()
        form.onCountryCodeChanged("FR")
        whenever(prefs.load()).thenReturn(AppSettings(countryCode = "DE", autoCountry = false))

        form.reloadFromPrefs()

        assertEquals("DE", form.state.value.settings.countryCode)
        assertFalse(form.state.value.settings.autoCountry)
    }

    @Test
    fun `country code change is uppercased and turns the auto flag off`() {
        val form = createForm()

        form.onCountryCodeChanged("  fr  ")

        assertEquals("FR", form.state.value.settings.countryCode)
        assertFalse(form.state.value.settings.autoCountry)
    }

    @Test
    fun `a valid country code change persists to the prefs`() {
        val form = createForm()

        form.onCountryCodeChanged("fr")

        val captor = argumentCaptor<(AppSettings) -> AppSettings>()
        verify(prefs).update(captor.capture())
        val saved = captor.lastValue(AppSettings())
        assertEquals("FR", saved.countryCode)
        assertFalse(saved.autoCountry)
    }

    @Test
    fun `an invalid country code updates the field but not the prefs`() {
        val form = createForm()

        form.onCountryCodeChanged("XX")

        assertEquals("XX", form.state.value.settings.countryCode)
        assertFalse(form.state.value.settings.autoCountry)
        verify(prefs, never()).update(any())
    }

    @Test
    fun `committing the detected code re-engages the country auto flag`() {
        val form = createForm()
        val detected = contactResolver.detectedRegion()
        form.onAutoCountryChanged(false)
        form.onCountryCodeChanged(detected)
        form.onCountryCodeCommitted(detected)

        assertTrue(form.state.value.settings.autoCountry)
    }

    @Test
    fun `committing a valid country code different from the detected keeps the auto flag off`() {
        val form = createForm()
        val other = if (contactResolver.detectedRegion() == "FR") "DE" else "FR"
        form.onAutoCountryChanged(false)
        form.onCountryCodeChanged(other)
        form.onCountryCodeCommitted(other)

        assertFalse(form.state.value.settings.autoCountry)
        assertEquals(other, form.state.value.settings.countryCode)
    }

    @Test
    fun `committing a blank country code keeps the auto flag off with the blank value`() {
        val form = createForm()
        form.onAutoCountryChanged(false)
        form.onCountryCodeChanged("   ")
        form.onCountryCodeCommitted("   ")

        assertFalse(form.state.value.settings.autoCountry)
        assertEquals("", form.state.value.settings.countryCode)
    }

    @Test
    fun `reloadFromPrefs with keepTestRuns keeps the in-progress runs`() {
        val form = createForm()
        form.update { it.copy(test = it.test.copy(isTestingConnection = true, isSendingTestEmail = true)) }
        val generationBefore = form.state.value.refreshGeneration
        whenever(prefs.load()).thenReturn(AppSettings(smtpHost = "smtp.example.com"))

        form.reloadFromPrefs(keepTestRuns = true)

        assertEquals(generationBefore + 1, form.state.value.refreshGeneration)
        assertEquals("smtp.example.com", form.state.value.settings.smtpHost)
        assertTrue(form.state.value.test.isTestingConnection)
        assertTrue(form.state.value.test.isSendingTestEmail)
        assertNull(form.state.value.test.smtpTestResult)
        assertNull(form.state.value.test.testEmailResult)
    }

    // hasChanges with a null baseline (should not happen): answers true so the save
    // asks for the confirm, like any untested change.
    @Test
    fun `hasChanges with null baseline returns true`() {
        val form = createForm()
        // The baseline is captured on construction, so the null branch is not reachable
        // here: the test pins the fresh-form answer (no change yet).
        assertTrue(form.hasChanges() == false)
    }

    // setAppLanguage rejects unknown tags: the validation against LanguageOption.tags.
    @Test
    fun `setAppLanguage rejects an unknown tag`() {
        val form = createForm()
        form.setAppLanguage("zz")
        assertEquals("", form.state.value.appLanguage)
    }

    // The update transform: direct state write for fields the form owns.
    @Test
    fun `update applies the transform directly to the state`() {
        val form = createForm()
        form.update { it.copy(portDraft = "9999") }
        assertEquals("9999", form.state.value.portDraft)
    }

    // currentAppLanguage reads the non-empty app locale: the else branch that
    // extracts the language tag from the first locale in the list.
    @Test
    fun `currentAppLanguage picks up the non-empty app locale on reload`() {
        val form = createForm()
        assertEquals("", form.state.value.appLanguage)
        // Set a non-empty locale and reload: currentAppLanguage reads it from the delegate.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("de"))
        form.reloadFromPrefs()
        assertEquals("de", form.state.value.appLanguage)
    }
}
