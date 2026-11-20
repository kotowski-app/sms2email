package app.kotowski.sms2email.work

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.SubscriptionManager
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.Sms
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.StatusUpdate
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.service.EmailBuilder
import app.kotowski.sms2email.service.EmailResult
import app.kotowski.sms2email.service.EmailService
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.system.SimNameResolver
import app.kotowski.sms2email.testing.TestPhone
import app.kotowski.sms2email.util.DateUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsSendExecutorTest {
    @Mock private lateinit var context: Context

    @Mock private lateinit var sharedPrefs: SharedPreferences

    @Mock private lateinit var resources: Resources

    @Mock private lateinit var connectivityManager: ConnectivityManager

    @Mock private lateinit var network: Network

    @Mock private lateinit var networkCapabilities: NetworkCapabilities

    @Mock private lateinit var smsRepository: SmsRepository

    @Mock private lateinit var emailService: EmailService

    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var simNameResolver: SimNameResolver

    @Mock private lateinit var contactResolver: ContactResolver

    @Mock private lateinit var retryScheduler: SmsRetryScheduler

    private lateinit var closeable: AutoCloseable

    private val testSms =
        Sms(
            id = 1,
            messageBody = "Your OTP is 1234",
            phoneNumber = TestPhone.SELF,
            simId = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
            timeReceived = 1_700_000_000_000L,
        )

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        // LanguageHelper.applyLocale falls back to stored tag (null here) and returns the context as-is
        whenever(context.getSharedPreferences(any<String>(), any<Int>())).thenReturn(sharedPrefs)
        // DateUtil.formatDateTime reads the configuration via context.resources
        whenever(context.resources).thenReturn(resources)
        whenever(resources.configuration).thenReturn(RuntimeEnvironment.getApplication().resources.configuration)
        whenever(context.getString(R.string.subject_template_no_prefix, TestPhone.SELF)).thenReturn("SMS from ${TestPhone.SELF}")
        whenever(context.getString(R.string.subject_template_no_prefix, "John Doe")).thenReturn("SMS from John Doe")
        whenever(context.getString(R.string.email_subject_sim_suffix, "CarrierOne")).thenReturn("on CarrierOne")
        whenever(
            context.getString(R.string.subject_template_no_prefix_with_sim, TestPhone.SELF, "on CarrierOne"),
        ).thenReturn("SMS from ${TestPhone.SELF} on CarrierOne")
    }

    private fun executor() =
        SmsSendExecutor(
            context,
            smsRepository,
            emailService,
            prefs,
            simNameResolver,
            contactResolver,
            retryScheduler,
        )

    private fun enableInternet() {
        whenever(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)
        whenever(connectivityManager.activeNetwork).thenReturn(network)
        whenever(connectivityManager.getNetworkCapabilities(network)).thenReturn(networkCapabilities)
        whenever(networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        whenever(networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
    }

    // A config that passes the queue's completeness gate, so the tests under it reach the
    // network check and the service call.
    private fun stubCompleteConfig() {
        whenever(
            prefs.load(),
        ).thenReturn(
            AppSettings(
                smtpHost = "smtp.example.com",
                smtpUsername = "user@example.com",
                senderEmail = "sender@example.com",
                destEmail = "dest@example.com",
            ),
        )
        whenever(prefs.smtpPassword).thenReturn("password")
    }

    @Test
    fun `triage returns Proceed when not expired`() =
        runTest {
            whenever(
                retryScheduler.failIfExpired(any(), anyOrNull(), any(), any()),
            ).thenReturn(false)

            val result = executor().triage(testSms)

            assertIs<SmsSendExecutor.TriageResult.Proceed>(result)
            verify(retryScheduler).failIfExpired(
                eq(1L),
                eq(testSms.timeFirstSendAttempt),
                eq(testSms.retryCount),
                eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
            )
            verify(smsRepository, never()).transitionSmsStatus(any(), any(), any(), any())
        }

    @Test
    fun `triage returns Skipped when the scheduler expires the row`() =
        runTest {
            val expiredSms =
                testSms.copy(timeFirstSendAttempt = System.currentTimeMillis() - AppConfig.MAX_RETRY_PERIOD_MS - 1)
            whenever(
                retryScheduler.failIfExpired(
                    eq(1L),
                    eq(expiredSms.timeFirstSendAttempt),
                    eq(expiredSms.retryCount),
                    eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                ),
            ).thenReturn(true)

            val result = executor().triage(expiredSms)

            assertIs<SmsSendExecutor.TriageResult.Skipped>(result)
            verify(smsRepository, never()).transitionSmsStatus(any(), any(), any(), any())
        }

    @Test
    fun `buildParts resolves contact when enabled`() =
        runTest {
            whenever(prefs.load()).thenReturn(AppSettings(useContactResolution = true))
            whenever(contactResolver.resolve(TestPhone.SELF)).thenReturn("John Doe")

            val parts = executor().buildParts(testSms)

            assertEquals("SMS from John Doe", parts.subject)
            assertTrue(parts.body.isNotEmpty())
            verify(contactResolver).resolve(TestPhone.SELF)
        }

    @Test
    fun `buildParts skips contact resolution when disabled`() =
        runTest {
            whenever(prefs.load()).thenReturn(AppSettings(useContactResolution = false))

            val parts = executor().buildParts(testSms)

            assertEquals("SMS from ${TestPhone.SELF}", parts.subject)
            verify(contactResolver, never()).resolve(any())
        }

    @Test
    fun `buildParts resolves sim name on multi sim device with valid sim id`() =
        runTest {
            val multiSimSms = testSms.copy(simId = 2)
            whenever(prefs.load()).thenReturn(AppSettings(displaySimCards = true))
            whenever(simNameResolver.getSimDisplayNameSync(2)).thenReturn("CarrierOne")

            val parts = executor().buildParts(multiSimSms)

            assertEquals("SMS from ${TestPhone.SELF} on CarrierOne", parts.subject)
            verify(simNameResolver).getSimDisplayNameSync(2)
        }

    @Test
    fun `buildParts resolves sim name on a single sim device when the display is on`() =
        runTest {
            val singleSimSms = testSms.copy(simId = 2)
            whenever(prefs.load()).thenReturn(AppSettings(displaySimCards = true))
            whenever(simNameResolver.getSimDisplayNameSync(2)).thenReturn("CarrierOne")

            val parts = executor().buildParts(singleSimSms)

            assertEquals("SMS from ${TestPhone.SELF} on CarrierOne", parts.subject)
            verify(simNameResolver).getSimDisplayNameSync(2)
        }

    @Test
    fun `buildParts skips sim name when sim id is invalid`() =
        runTest {
            whenever(prefs.load()).thenReturn(AppSettings(displaySimCards = true))

            val parts = executor().buildParts(testSms)

            assertEquals("SMS from ${TestPhone.SELF}", parts.subject)
            verify(simNameResolver, never()).getSimDisplayNameSync(any())
        }

    @Test
    fun `buildParts skips sim name when displaySimCards is false even with valid sim id`() =
        runTest {
            val smsWithSim = testSms.copy(simId = 2)
            whenever(prefs.load()).thenReturn(AppSettings(displaySimCards = false))

            val parts = executor().buildParts(smsWithSim)

            assertEquals("SMS from ${TestPhone.SELF}", parts.subject)
            verify(simNameResolver, never()).getSimDisplayNameSync(any())
        }

    @Test
    fun `buildParts omits the time line from the plain body by default`() =
        runTest {
            whenever(prefs.load()).thenReturn(AppSettings())

            val parts = executor().buildParts(testSms)

            assertEquals(testSms.messageBody.trim(), parts.body.trim())
        }

    @Test
    fun `buildParts leads the plain body with the time line when the setting is on`() =
        runTest {
            whenever(prefs.load()).thenReturn(AppSettings(includeTimeInBody = true))

            val parts = executor().buildParts(testSms)

            assertTrue(parts.body.startsWith(DateUtil.formatDateTime(context, testSms.timeReceived)))
            assertTrue(parts.body.contains(testSms.messageBody))
        }

    @Test
    fun `claim transitions to SENDING and keeps existing first attempt time`() =
        runTest {
            val firstAttempt = 1_700_000_000_000L
            val smsWithAttempt = testSms.copy(timeFirstSendAttempt = firstAttempt)
            whenever(
                smsRepository.transitionSmsStatus(any(), any(), any(), any()),
            ).thenReturn(true)

            assertTrue(executor().claim(smsWithAttempt))

            val updateCaptor = argumentCaptor<StatusUpdate>()
            verify(smsRepository).transitionSmsStatus(
                eq(1L),
                eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                eq(SmsStatus.SENDING),
                updateCaptor.capture(),
            )
            val update = updateCaptor.firstValue
            assertEquals(firstAttempt, update.timeFirstSendAttempt)
            assertNotNull(update.timeLastSendAttempt)
            assertNull(update.statusDetails)
            assertNull(update.retryCount)
            assertNull(update.timeNextSendAttempt)
        }

    @Test
    fun `claim returns false when the transition loses the race`() =
        runTest {
            whenever(
                smsRepository.transitionSmsStatus(any(), any(), any(), any()),
            ).thenReturn(false)

            assertFalse(executor().claim(testSms))
        }

    @Test
    fun `send claims SENDING and returns service success`() =
        runTest {
            enableInternet()
            stubCompleteConfig()
            whenever(
                smsRepository.transitionSmsStatus(any(), any(), any(), any()),
            ).thenReturn(true)
            whenever(
                emailService.sendEmail(any(), anyOrNull(), any(), anyOrNull(), any(), any(), any()),
            ).thenReturn(EmailResult.Success)

            val result = executor().send(testSms, EmailBuilder.EmailParts("subject", "body"))

            val outcome = assertIs<SmsSendExecutor.SendResult.Outcome>(result)
            assertIs<EmailResult.Success>(outcome.result)
            val updateCaptor = argumentCaptor<StatusUpdate>()
            verify(smsRepository).transitionSmsStatus(
                eq(1L),
                eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                eq(SmsStatus.SENDING),
                updateCaptor.capture(),
            )
            assertNotNull(updateCaptor.firstValue.timeFirstSendAttempt)
            assertNotNull(updateCaptor.firstValue.timeLastSendAttempt)
        }

    @Test
    fun `send preserves existing first attempt time on claim`() =
        runTest {
            enableInternet()
            stubCompleteConfig()
            whenever(
                smsRepository.transitionSmsStatus(any(), any(), any(), any()),
            ).thenReturn(true)
            whenever(
                emailService.sendEmail(any(), anyOrNull(), any(), anyOrNull(), any(), any(), any()),
            ).thenReturn(EmailResult.Success)
            val firstAttempt = 1_700_000_000_000L
            val smsWithAttempt = testSms.copy(timeFirstSendAttempt = firstAttempt)

            executor().send(smsWithAttempt, EmailBuilder.EmailParts("subject", "body"))

            val updateCaptor = argumentCaptor<StatusUpdate>()
            verify(smsRepository).transitionSmsStatus(
                eq(1L),
                eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                eq(SmsStatus.SENDING),
                updateCaptor.capture(),
            )
            assertEquals(firstAttempt, updateCaptor.firstValue.timeFirstSendAttempt)
            assertNotNull(updateCaptor.firstValue.timeLastSendAttempt)
        }

    @Test
    fun `send returns NotClaimed without calling email service when claim fails`() =
        runTest {
            enableInternet()
            whenever(
                smsRepository.transitionSmsStatus(any(), any(), any(), any()),
            ).thenReturn(false)

            val result = executor().send(testSms, EmailBuilder.EmailParts("subject", "body"))

            assertIs<SmsSendExecutor.SendResult.NotClaimed>(result)
            verify(emailService, never()).sendEmail(any(), anyOrNull(), any(), anyOrNull(), any(), any(), any())
        }

    @Test
    fun `send returns network failure but row stays claimed when network blocked`() =
        runTest {
            whenever(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)
            whenever(connectivityManager.activeNetwork).thenReturn(null)
            whenever(context.getString(R.string.error_waiting_for_internet)).thenReturn("waiting for internet")
            stubCompleteConfig()
            whenever(
                smsRepository.transitionSmsStatus(any(), any(), any(), any()),
            ).thenReturn(true)

            val result = executor().send(testSms, EmailBuilder.EmailParts("subject", "body"))

            val outcome = assertIs<SmsSendExecutor.SendResult.Outcome>(result)
            val failure = assertIs<EmailResult.Failure>(outcome.result)
            assertEquals("waiting for internet", failure.message)
            verify(emailService, never()).sendEmail(any(), any(), any(), any(), any(), any(), any())
            val updateCaptor = argumentCaptor<StatusUpdate>()
            verify(smsRepository).transitionSmsStatus(
                eq(1L),
                eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                eq(SmsStatus.SENDING),
                updateCaptor.capture(),
            )
            assertNotNull(updateCaptor.firstValue.timeFirstSendAttempt)
            assertNotNull(updateCaptor.firstValue.timeLastSendAttempt)
        }

    @Test
    fun `send returns service failure message`() =
        runTest {
            enableInternet()
            stubCompleteConfig()
            whenever(
                smsRepository.transitionSmsStatus(any(), any(), any(), any()),
            ).thenReturn(true)
            whenever(
                emailService.sendEmail(any(), anyOrNull(), any(), anyOrNull(), any(), any(), any()),
            ).thenReturn(EmailResult.Failure("smtp refused"))

            val result = executor().send(testSms, EmailBuilder.EmailParts("subject", "body"))

            val outcome = assertIs<SmsSendExecutor.SendResult.Outcome>(result)
            val failure = assertIs<EmailResult.Failure>(outcome.result)
            assertEquals("smtp refused", failure.message)
        }

    @Test
    fun `send fails permanently with the not configured message when the stored config is incomplete`() =
        runTest {
            enableInternet()
            whenever(prefs.load()).thenReturn(AppSettings())
            whenever(prefs.smtpPassword).thenReturn("")
            whenever(context.getString(R.string.error_smtp_not_configured)).thenReturn("smtp not configured")
            whenever(
                smsRepository.transitionSmsStatus(any(), any(), any(), any()),
            ).thenReturn(true)

            val result = executor().send(testSms, EmailBuilder.EmailParts("subject", "body"))

            val outcome = assertIs<SmsSendExecutor.SendResult.Outcome>(result)
            val failure = assertIs<EmailResult.Failure>(outcome.result)
            assertEquals("smtp not configured", failure.message)
            assertTrue(failure.permanent)
            verify(emailService, never()).sendEmail(any(), anyOrNull(), any(), anyOrNull(), any(), any(), any())
            val updateCaptor = argumentCaptor<StatusUpdate>()
            verify(smsRepository).transitionSmsStatus(
                eq(1L),
                eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                eq(SmsStatus.SENDING),
                updateCaptor.capture(),
            )
            assertNotNull(updateCaptor.firstValue.timeFirstSendAttempt)
        }

    @Test
    fun `send returns a forwarding-stopped failure without sending when the flag is off after claim`() =
        runTest {
            enableInternet()
            whenever(prefs.load()).thenReturn(AppSettings(isForwardingEnabled = false))
            whenever(
                smsRepository.transitionSmsStatus(any(), any(), any(), any()),
            ).thenReturn(true)
            whenever(context.getString(R.string.sms_status_forwarding_stopped)).thenReturn("forwarding stopped")

            val result = executor().send(testSms, EmailBuilder.EmailParts("subject", "body"))

            val outcome = assertIs<SmsSendExecutor.SendResult.Outcome>(result)
            val failure = assertIs<EmailResult.Failure>(outcome.result)
            assertEquals("forwarding stopped", failure.message)
            assertFalse(failure.permanent)
            verify(emailService, never()).sendEmail(any(), anyOrNull(), any(), anyOrNull(), any(), any(), any())
            // send itself must not touch the scheduler; the caller's finalizeSend routes this
            // failure to scheduleRetry, whose off-park parks the row SENDING -> IGNORED
            verify(retryScheduler, never()).scheduleRetry(any(), any(), any())
            val updateCaptor = argumentCaptor<StatusUpdate>()
            verify(smsRepository).transitionSmsStatus(
                eq(1L),
                eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                eq(SmsStatus.SENDING),
                updateCaptor.capture(),
            )
            assertNotNull(updateCaptor.firstValue.timeFirstSendAttempt)
        }

    @Test
    fun `isSmtpConfigured is false for a fresh install and true for a complete config`() =
        runTest {
            whenever(prefs.load()).thenReturn(AppSettings())
            whenever(prefs.smtpPassword).thenReturn("")
            assertFalse(executor().isSmtpConfigured())

            stubCompleteConfig()
            assertTrue(executor().isSmtpConfigured())
        }

    @Test
    fun `markSent transitions SENDING to SENT`() =
        runTest {
            whenever(
                smsRepository.transitionSmsStatus(any(), any(), any(), any()),
            ).thenReturn(true)

            executor().markSent(1L)

            val updateCaptor = argumentCaptor<StatusUpdate>()
            verify(smsRepository).transitionSmsStatus(
                eq(1L),
                eq(listOf(SmsStatus.SENDING)),
                eq(SmsStatus.SENT),
                updateCaptor.capture(),
            )
            assertNotNull(updateCaptor.firstValue.timeLastSendAttempt)
            assertNull(updateCaptor.firstValue.statusDetails)
        }

    @Test
    fun `markSent falls back to RETRYING when SENDING transition fails`() =
        runTest {
            whenever(
                smsRepository.transitionSmsStatus(eq(1L), eq(listOf(SmsStatus.SENDING)), eq(SmsStatus.SENT), any()),
            ).thenReturn(false)
            whenever(
                smsRepository.transitionSmsStatus(eq(1L), eq(listOf(SmsStatus.RETRYING)), eq(SmsStatus.SENT), any()),
            ).thenReturn(true)

            executor().markSent(1L)

            val updateCaptor = argumentCaptor<StatusUpdate>()
            verify(smsRepository).transitionSmsStatus(
                eq(1L),
                eq(listOf(SmsStatus.RETRYING)),
                eq(SmsStatus.SENT),
                updateCaptor.capture(),
            )
            assertNotNull(updateCaptor.firstValue.timeLastSendAttempt)
        }

    @Test
    fun `finalizeSend marks SENT on success`() =
        runTest {
            whenever(
                smsRepository.transitionSmsStatus(any(), any(), any(), any()),
            ).thenReturn(true)

            executor().finalizeSend(testSms, EmailResult.Success)

            verify(smsRepository).transitionSmsStatus(
                eq(1L),
                eq(listOf(SmsStatus.SENDING)),
                eq(SmsStatus.SENT),
                any(),
            )
            // The success is the health signal: the queued rows are drained right away.
            verify(retryScheduler).drainAfterSuccess(1L)
            verify(retryScheduler, never()).scheduleRetry(any(), any(), any())
            verify(retryScheduler, never()).scheduleFailed(any(), any(), any())
        }

    @Test
    fun `finalizeSend does not drain on a failure`() =
        runTest {
            whenever(retryScheduler.scheduleRetry(any(), any(), any())).thenReturn(true)

            executor().finalizeSend(testSms, EmailResult.Failure("smtp refused"))

            verify(retryScheduler, never()).drainAfterSuccess(any())
        }

    @Test
    fun `finalizeSend schedules a retry on transient failure`() =
        runTest {
            whenever(retryScheduler.scheduleRetry(any(), any(), any())).thenReturn(true)

            executor().finalizeSend(testSms, EmailResult.Failure("smtp refused"))

            verify(retryScheduler).scheduleRetry(eq(1L), eq(testSms), eq("smtp refused"))
            verify(retryScheduler, never()).scheduleFailed(any(), any(), any())
            verify(smsRepository, never()).transitionSmsStatus(any(), any(), any(), any())
        }

    @Test
    fun `finalizeSend marks failed without a retry on permanent failure`() =
        runTest {
            whenever(retryScheduler.scheduleFailed(any(), any(), any())).thenReturn(true)

            executor().finalizeSend(testSms, EmailResult.Failure("Recipient does not exist", permanent = true))

            verify(retryScheduler).scheduleFailed(eq(1L), eq(testSms), eq("Recipient does not exist"))
            verify(retryScheduler, never()).scheduleRetry(any(), any(), any())
            verify(smsRepository, never()).transitionSmsStatus(any(), any(), any(), any())
        }

    @Test
    fun `finalizeSend reaches the DB even when the caller coroutine dies after the smtp 250`() =
        runTest {
            val sent = AtomicBoolean(false)
            whenever(smsRepository.transitionSmsStatus(any(), any(), any(), any())).doSuspendableAnswer {
                // real suspension point: interrupted if finalization ran inside the dying
                // worker coroutine, completes because it runs in its own runBlocking scope
                delay(1)
                sent.set(true)
                true
            }
            val workerJob = Job()
            runCatching {
                CoroutineScope(workerJob + Dispatchers.Default)
                    .async {
                        // simulate the worker dying (crash / OOM / force-stop) in the ms-scale
                        // window between the SMTP 250 and the SENDING->SENT write
                        workerJob.cancel()
                        executor().finalizeSend(testSms, EmailResult.Success)
                    }.await()
            }

            assertTrue(sent.get())
        }
}
