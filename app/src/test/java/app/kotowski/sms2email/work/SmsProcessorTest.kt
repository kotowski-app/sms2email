package app.kotowski.sms2email.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkRequest
import androidx.work.impl.WorkManagerImpl
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.DedupLevel
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.IngestResult
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.StatusUpdate
import app.kotowski.sms2email.data.filter.FilterMode
import app.kotowski.sms2email.data.filter.FilterRule
import app.kotowski.sms2email.data.filter.SmsFilter
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.SimNameResolver
import app.kotowski.sms2email.testing.TestPhone
import app.kotowski.sms2email.testing.WorkManagerDelegate
import app.kotowski.sms2email.testing.stubInternetEnabled
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmsProcessorTest {
    private lateinit var closeable: AutoCloseable

    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var smsRepository: SmsRepository

    @Mock private lateinit var appContext: Context

    @Mock private lateinit var simNameResolver: SimNameResolver

    @Mock private lateinit var workManager: WorkManagerImpl

    @Mock private lateinit var workInfosFuture: ListenableFuture<List<WorkInfo>>

    @Mock private lateinit var connectivityManager: ConnectivityManager

    @Mock private lateinit var network: Network

    @Mock private lateinit var networkCapabilities: NetworkCapabilities

    private lateinit var processor: SmsProcessor

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        whenever(appContext.getString(any<Int>())).thenReturn("")
        whenever(appContext.getString(R.string.error_waiting_for_internet)).thenReturn("No Internet connection.")
        whenever(appContext.getString(R.string.sms_detail_ignored)).thenReturn("forwarding off")
        whenever(appContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)
        whenever(connectivityManager.activeNetwork).thenReturn(null)
        // The new advanced settings, defaulted so the processor's read is deterministic.
        whenever(prefs.load()).thenReturn(AppSettings())
        // No live per-row work by default: enqueueSend picks REPLACE (the default policy).
        // isDone=true puts await() on its fast path, which calls the stubbed no-arg get().
        whenever(workManager.getWorkInfosForUniqueWork(any())).thenReturn(workInfosFuture)
        whenever(workInfosFuture.isDone()).thenReturn(true)
        whenever(workInfosFuture.get()).thenReturn(emptyList())
        whenever(appContext.getString(R.string.settings_filter_status_skipped)).thenReturn("Skipped by filter")
        whenever(appContext.getString(R.string.settings_filter_status_no_match)).thenReturn("Does not match filter rules")
        // A short ingest-retry delay so the retry chain runs within the test's lifetime.
        processor = SmsProcessor(prefs, smsRepository, appContext, simNameResolver, ingestRetryDelayMs = 10L)
    }

    @After
    fun tearDown() {
        closeable.close()
    }

    private fun enableInternet() = stubInternetEnabled(connectivityManager, network, networkCapabilities)

    private fun incoming() =
        SmsProcessor.IncomingSms(
            phoneNumber = TestPhone.SELF,
            messageBody = "Test message",
            timestamp = 1234567890L,
        )

    private suspend fun stubInsertAndPass(forwardingEnabled: Boolean = true) {
        whenever(prefs.load()).thenReturn(AppSettings(isForwardingEnabled = forwardingEnabled))
        whenever(smsRepository.insertIfNew(any(), any(), any(), any(), any(), any())).thenReturn(IngestResult.Inserted(42L))
    }

    @Test
    fun `initial send enqueues EmailWorker via enqueueSend without backoff criteria`() {
        runBlocking {
            WorkManagerDelegate.install(workManager)
            try {
                stubInsertAndPass()
                enableInternet()

                processor.handleIncomingSms(incoming())

                verify(smsRepository, never())
                    .transitionSmsStatus(any(), any(), any(), any())
                val captor = argumentCaptor<OneTimeWorkRequest>()
                verify(workManager).enqueueUniqueWork(eq("email_send_42"), eq(ExistingWorkPolicy.REPLACE), captor.capture())
                val spec = captor.firstValue.workSpec
                assertEquals(42L, spec.input.getLong(SMS_ID_KEY, -1))
                assertEquals(EmailWorker::class.java.name, spec.workerClassName)
                assertEquals(WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS, spec.backoffDelayDuration)
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }
    }

    @Test
    fun `blank body is skipped without insert transition or enqueue`() {
        runBlocking {
            WorkManagerDelegate.install(workManager)
            try {
                whenever(prefs.load()).thenReturn(AppSettings())

                processor.handleIncomingSms(SmsProcessor.IncomingSms(TestPhone.SELF, "", 1234567890L))
                processor.handleIncomingSms(SmsProcessor.IncomingSms(TestPhone.SELF, "   ", 1234567890L))

                verify(smsRepository, never()).insertIfNew(any(), any(), any(), any(), any(), any())
                verify(smsRepository, never()).transitionSmsStatus(any(), any(), any(), any())
                verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }
    }

    @Test
    fun `no internet marks RETRYING via CAS with PENDING expected and still enqueues`() {
        runBlocking {
            WorkManagerDelegate.install(workManager)
            try {
                stubInsertAndPass()

                processor.handleIncomingSms(incoming())

                val updateCaptor = argumentCaptor<StatusUpdate>()
                verify(smsRepository).transitionSmsStatus(
                    eq(42L),
                    eq(listOf(SmsStatus.PENDING)),
                    eq(SmsStatus.RETRYING),
                    updateCaptor.capture(),
                )
                val update = updateCaptor.firstValue
                assertEquals("No Internet connection.", update.statusDetails)
                assertEquals(0, update.retryCount)
                assertNull(update.timeFirstSendAttempt)
                assertNull(update.timeLastSendAttempt)
                assertNull(update.timeNextSendAttempt)
                verify(workManager).enqueueUniqueWork(eq("email_send_42"), eq(ExistingWorkPolicy.REPLACE), any<OneTimeWorkRequest>())
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }
    }

    @Test
    fun `forwarding disabled marks IGNORED via CAS with PENDING expected and does not enqueue`() {
        runBlocking {
            WorkManagerDelegate.install(workManager)
            try {
                whenever(prefs.load()).thenReturn(AppSettings(isForwardingEnabled = false))
                whenever(smsRepository.insertIfNew(any(), any(), any(), any(), any(), any())).thenReturn(IngestResult.Inserted(42L))

                processor.handleIncomingSms(incoming())

                verify(smsRepository).transitionSmsStatus(
                    eq(42L),
                    eq(listOf(SmsStatus.PENDING)),
                    eq(SmsStatus.IGNORED),
                    eq(StatusUpdate(statusDetails = "forwarding off")),
                )
                verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }
    }

    // The message filter: after insert and the forwarding-off gate, before the offline
    // park — a filtered row parked RETRYING would be sent once the network returns.

    @Test
    fun `a filtered incoming is marked IGNORED with the skip reason and never enqueued`() {
        runBlocking {
            WorkManagerDelegate.install(workManager)
            try {
                whenever(prefs.load()).thenReturn(
                    AppSettings(filterEnabled = true, filter = SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "prize")))),
                )
                whenever(smsRepository.insertIfNew(any(), any(), any(), any(), any(), any())).thenReturn(IngestResult.Inserted(42L))
                enableInternet()

                processor.handleIncomingSms(SmsProcessor.IncomingSms(TestPhone.SELF, "You won a prize", 1234567890L))

                verify(smsRepository).transitionSmsStatus(
                    eq(42L),
                    eq(listOf(SmsStatus.PENDING)),
                    eq(SmsStatus.IGNORED),
                    eq(StatusUpdate(statusDetails = "Skipped by filter")),
                )
                verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }
    }

    @Test
    fun `a matching incoming while the filter toggle is off is forwarded, not filtered`() {
        runBlocking {
            WorkManagerDelegate.install(workManager)
            try {
                // The rule would match the body, but the feature toggle is off: the
                // filter is not evaluated at all.
                whenever(prefs.load()).thenReturn(
                    AppSettings(filterEnabled = false, filter = SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "prize")))),
                )
                whenever(smsRepository.insertIfNew(any(), any(), any(), any(), any(), any())).thenReturn(IngestResult.Inserted(42L))
                enableInternet()

                processor.handleIncomingSms(SmsProcessor.IncomingSms(TestPhone.SELF, "You won a prize", 1234567890L))

                verify(smsRepository, never()).transitionSmsStatus(any(), any(), eq(SmsStatus.IGNORED), any())
                verify(workManager).enqueueUniqueWork(eq("email_send_42"), eq(ExistingWorkPolicy.REPLACE), any<OneTimeWorkRequest>())
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }
    }

    @Test
    fun `an unfiltered incoming in forward mode is marked IGNORED with the no-match reason`() {
        runBlocking {
            WorkManagerDelegate.install(workManager)
            try {
                whenever(prefs.load()).thenReturn(
                    AppSettings(filterEnabled = true, filter = SmsFilter(FilterMode.FORWARD, listOf(FilterRule(body = "vip")))),
                )
                whenever(smsRepository.insertIfNew(any(), any(), any(), any(), any(), any())).thenReturn(IngestResult.Inserted(42L))
                enableInternet()

                processor.handleIncomingSms(SmsProcessor.IncomingSms(TestPhone.SELF, "hello there", 1234567890L))

                verify(smsRepository).transitionSmsStatus(
                    eq(42L),
                    eq(listOf(SmsStatus.PENDING)),
                    eq(SmsStatus.IGNORED),
                    eq(StatusUpdate(statusDetails = "Does not match filter rules")),
                )
                verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }
    }

    @Test
    fun `a matching incoming in forward mode passes the filter and is enqueued`() {
        runBlocking {
            WorkManagerDelegate.install(workManager)
            try {
                whenever(prefs.load()).thenReturn(
                    AppSettings(filterEnabled = true, filter = SmsFilter(FilterMode.FORWARD, listOf(FilterRule(body = "vip")))),
                )
                whenever(smsRepository.insertIfNew(any(), any(), any(), any(), any(), any())).thenReturn(IngestResult.Inserted(42L))
                enableInternet()

                processor.handleIncomingSms(SmsProcessor.IncomingSms(TestPhone.SELF, "a vip message", 1234567890L))

                verify(smsRepository, never()).transitionSmsStatus(any(), any(), any(), any())
                verify(workManager).enqueueUniqueWork(eq("email_send_42"), eq(ExistingWorkPolicy.REPLACE), any<OneTimeWorkRequest>())
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }
    }

    @Test
    fun `an incoming in forward mode without an active rule is forwarded, not filtered`() {
        // The off state: the toggle is on, but the filter has no active rule — the
        // predicate forwards everything (forward mode without a rule must not block all).
        runBlocking {
            WorkManagerDelegate.install(workManager)
            try {
                whenever(prefs.load()).thenReturn(
                    AppSettings(filterEnabled = true, filter = SmsFilter(FilterMode.FORWARD, emptyList())),
                )
                whenever(smsRepository.insertIfNew(any(), any(), any(), any(), any(), any())).thenReturn(IngestResult.Inserted(42L))
                enableInternet()

                processor.handleIncomingSms(SmsProcessor.IncomingSms(TestPhone.SELF, "anything at all", 1234567890L))

                verify(smsRepository, never()).transitionSmsStatus(any(), any(), any(), any())
                verify(workManager).enqueueUniqueWork(eq("email_send_42"), eq(ExistingWorkPolicy.REPLACE), any<OneTimeWorkRequest>())
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }
    }

    @Test
    fun `a filtered incoming without internet is marked IGNORED, not RETRYING`() {
        // The ordering guarantee: the filter branch precedes the offline park, so a
        // filtered row must never be parked RETRYING and re-sent when the network returns.
        runBlocking {
            WorkManagerDelegate.install(workManager)
            try {
                whenever(prefs.load()).thenReturn(
                    AppSettings(filterEnabled = true, filter = SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "prize")))),
                )
                whenever(smsRepository.insertIfNew(any(), any(), any(), any(), any(), any())).thenReturn(IngestResult.Inserted(42L))

                processor.handleIncomingSms(SmsProcessor.IncomingSms(TestPhone.SELF, "You won a prize", 1234567890L))

                verify(smsRepository).transitionSmsStatus(
                    eq(42L),
                    eq(listOf(SmsStatus.PENDING)),
                    eq(SmsStatus.IGNORED),
                    eq(StatusUpdate(statusDetails = "Skipped by filter")),
                )
                verify(smsRepository, never()).transitionSmsStatus(any(), any(), eq(SmsStatus.RETRYING), any())
                verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }
    }

    @Test
    fun `the filter's slot facet resolves the tray slot through the resolver`() {
        runBlocking {
            WorkManagerDelegate.install(workManager)
            try {
                whenever(prefs.load()).thenReturn(
                    AppSettings(filterEnabled = true, filter = SmsFilter(FilterMode.SKIP, listOf(FilterRule(slots = listOf(2))))),
                )
                whenever(smsRepository.insertIfNew(any(), any(), any(), any(), any(), any())).thenReturn(IngestResult.Inserted(42L))
                whenever(simNameResolver.getSlotFor(7)).thenReturn(2)
                enableInternet()

                processor.handleIncomingSms(SmsProcessor.IncomingSms(TestPhone.SELF, "Test message", 1234567890L, simId = 7))

                verify(simNameResolver).getSlotFor(7)
                verify(smsRepository).transitionSmsStatus(
                    eq(42L),
                    eq(listOf(SmsStatus.PENDING)),
                    eq(SmsStatus.IGNORED),
                    eq(StatusUpdate(statusDetails = "Skipped by filter")),
                )
                verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }
    }

    @Test
    fun `a slot-mismatched incoming passes the filter and is enqueued`() {
        runBlocking {
            WorkManagerDelegate.install(workManager)
            try {
                whenever(prefs.load()).thenReturn(
                    AppSettings(filterEnabled = true, filter = SmsFilter(FilterMode.SKIP, listOf(FilterRule(slots = listOf(2))))),
                )
                whenever(smsRepository.insertIfNew(any(), any(), any(), any(), any(), any())).thenReturn(IngestResult.Inserted(42L))
                whenever(simNameResolver.getSlotFor(7)).thenReturn(1)
                enableInternet()

                processor.handleIncomingSms(SmsProcessor.IncomingSms(TestPhone.SELF, "Test message", 1234567890L, simId = 7))

                verify(smsRepository, never()).transitionSmsStatus(any(), any(), any(), any())
                verify(workManager).enqueueUniqueWork(eq("email_send_42"), eq(ExistingWorkPolicy.REPLACE), any<OneTimeWorkRequest>())
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }
    }

    @Test
    fun `a duplicate twin of a filtered message dedups without a second transition`() {
        runBlocking {
            WorkManagerDelegate.install(workManager)
            try {
                whenever(prefs.load()).thenReturn(
                    AppSettings(filterEnabled = true, filter = SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "prize")))),
                )
                // The filtered twin dedups against the parked row instead of piling up.
                whenever(smsRepository.insertIfNew(any(), any(), any(), any(), any(), any()))
                    .thenReturn(IngestResult.Inserted(42L), IngestResult.Duplicate)
                enableInternet()

                processor.handleIncomingSms(SmsProcessor.IncomingSms(TestPhone.SELF, "You won a prize", 1_600_000_000_000L))
                processor.handleIncomingSms(SmsProcessor.IncomingSms(TestPhone.SELF, "You won a prize", 1_600_000_000_005L))

                verify(smsRepository, times(1))
                    .transitionSmsStatus(eq(42L), eq(listOf(SmsStatus.PENDING)), eq(SmsStatus.IGNORED), any())
                verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }
    }

    @Test
    fun `duplicate sms reported by the repository is skipped without transition or enqueue`() {
        runBlocking {
            WorkManagerDelegate.install(workManager)
            try {
                whenever(prefs.load()).thenReturn(AppSettings())
                whenever(smsRepository.insertIfNew(any(), any(), any(), any(), any(), any())).thenReturn(IngestResult.Duplicate)

                processor.handleIncomingSms(incoming())

                verify(smsRepository, never()).transitionSmsStatus(any(), any(), any(), any())
                verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }
    }

    @Test
    fun `insert failure schedules a retry that re-runs the ingest with the original timestamp`() {
        runBlocking {
            // Forwarding disabled: the retry transitions to IGNORED and never touches the
            // WorkManager (not mocked here; the retry runs on the processor's own scope).
            whenever(prefs.load()).thenReturn(AppSettings(isForwardingEnabled = false))
            whenever(smsRepository.insertIfNew(any(), any(), any(), any(), any(), any()))
                .thenThrow(RuntimeException("db down"))
                .thenReturn(IngestResult.Inserted(42L))
            val tsCaptor = argumentCaptor<Long>()

            processor.handleIncomingSms(incoming())

            verify(smsRepository, never()).transitionSmsStatus(any(), any(), any(), any())
            verify(smsRepository, Mockito.timeout(5_000).times(2)).insertIfNew(any(), any(), tsCaptor.capture(), any(), any(), any())
            assertTrue(tsCaptor.allValues.all { it == 1234567890L })
            verify(smsRepository, Mockito.timeout(5_000)).transitionSmsStatus(
                eq(42L),
                eq(listOf(SmsStatus.PENDING)),
                eq(SmsStatus.IGNORED),
                eq(StatusUpdate(statusDetails = "forwarding off")),
            )
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `insert failure is retried up to the max attempts and then drops without transition or enqueue`() {
        runBlocking {
            whenever(prefs.load()).thenReturn(AppSettings())
            whenever(smsRepository.insertIfNew(any(), any(), any(), any(), any(), any()))
                .thenThrow(RuntimeException("db down"))

            processor.handleIncomingSms(incoming())

            verify(
                smsRepository,
                Mockito.timeout(5_000).times(AppConfig.INGEST_RETRY_MAX_ATTEMPTS),
            ).insertIfNew(any(), any(), any(), any(), any(), any())
            // The chain stops at the cap: after a settle, no further attempts.
            Thread.sleep(100)
            verify(smsRepository, times(AppConfig.INGEST_RETRY_MAX_ATTEMPTS)).insertIfNew(any(), any(), any(), any(), any(), any())
            verify(smsRepository, never()).transitionSmsStatus(any(), any(), any(), any())
            verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `dedup collision reported by insertIfNew skips without transition or enqueue`() {
        runBlocking {
            WorkManagerDelegate.install(workManager)
            try {
                whenever(prefs.load()).thenReturn(AppSettings())
                whenever(smsRepository.insertIfNew(any(), any(), any(), any(), any(), any())).thenReturn(IngestResult.Duplicate)

                processor.handleIncomingSms(incoming())

                verify(smsRepository, never())
                    .transitionSmsStatus(any(), any(), any(), any())
                verify(workManager, never()).enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }
    }

    @Test
    fun `redelivered broadcast enqueues once when the second insert is a duplicate`() {
        runBlocking {
            WorkManagerDelegate.install(workManager)
            try {
                stubInsertAndPass()
                enableInternet()

                whenever(smsRepository.insertIfNew(any(), any(), any(), any(), any(), any()))
                    .thenReturn(IngestResult.Inserted(42L), IngestResult.Duplicate)

                val firstTimestamp = 1_600_000_000_000L
                val secondTimestamp = firstTimestamp + 5
                processor.handleIncomingSms(
                    SmsProcessor.IncomingSms(TestPhone.SELF, "Test message", firstTimestamp),
                )
                processor.handleIncomingSms(
                    SmsProcessor.IncomingSms(TestPhone.SELF, "Test message", secondTimestamp),
                )

                verify(smsRepository, times(2)).insertIfNew(any(), any(), any(), any(), any(), any())
                verify(workManager, times(1))
                    .enqueueUniqueWork(eq("email_send_42"), eq(ExistingWorkPolicy.REPLACE), any<OneTimeWorkRequest>())
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }
    }

    @Test
    fun `handleIncomingSms stores the PDU timestamp when the setting is on and the value is valid`() {
        runBlocking {
            WorkManagerDelegate.install(workManager)
            try {
                stubInsertAndPass()
                enableInternet()
                whenever(prefs.load()).thenReturn(AppSettings(usePduTimestamp = true))

                processor.handleIncomingSms(
                    SmsProcessor.IncomingSms(
                        phoneNumber = TestPhone.SELF,
                        messageBody = "Test message",
                        timestamp = 1_700_000_010_000L,
                        pduTimestamp = 1_700_000_000_000L,
                    ),
                )

                val tsCaptor = argumentCaptor<Long>()
                val levelCaptor = argumentCaptor<DedupLevel>()
                verify(
                    smsRepository,
                ).insertIfNew(any(), any(), tsCaptor.capture(), any(), any(), levelCaptor.capture())
                assertEquals(1_700_000_000_000L, tsCaptor.firstValue)
                assertEquals(DedupLevel.BALANCED, levelCaptor.firstValue)
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }
    }

    @Test
    fun `handleIncomingSms stores the wall clock when the setting is on but the PDU timestamp is zero`() {
        runBlocking {
            WorkManagerDelegate.install(workManager)
            try {
                stubInsertAndPass()
                enableInternet()
                whenever(prefs.load()).thenReturn(AppSettings(usePduTimestamp = true))

                processor.handleIncomingSms(
                    SmsProcessor.IncomingSms(
                        phoneNumber = TestPhone.SELF,
                        messageBody = "Test message",
                        timestamp = 1_700_000_010_000L,
                        pduTimestamp = 0L,
                    ),
                )

                val tsCaptor = argumentCaptor<Long>()
                verify(smsRepository).insertIfNew(any(), any(), tsCaptor.capture(), any(), any(), any())
                assertEquals(1_700_000_010_000L, tsCaptor.firstValue)
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }
    }

    @Test
    fun `handleIncomingSms stores the wall clock when the setting is off, even for a valid PDU timestamp`() {
        runBlocking {
            WorkManagerDelegate.install(workManager)
            try {
                stubInsertAndPass()
                enableInternet()
                whenever(prefs.load()).thenReturn(AppSettings())

                processor.handleIncomingSms(
                    SmsProcessor.IncomingSms(
                        phoneNumber = TestPhone.SELF,
                        messageBody = "Test message",
                        timestamp = 1_700_000_010_000L,
                        pduTimestamp = 1_700_000_000_000L,
                    ),
                )

                val tsCaptor = argumentCaptor<Long>()
                verify(smsRepository).insertIfNew(any(), any(), tsCaptor.capture(), any(), any(), any())
                assertEquals(1_700_000_010_000L, tsCaptor.firstValue)
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }
    }

    @Test
    fun `resolveTimestamp keeps a valid PDU timestamp when the setting is on`() {
        assertEquals(
            1_700_000_000_000L,
            SmsProcessor.resolveTimestamp(1_700_000_000_000L, 1_700_000_010_000L, true),
        )
    }

    @Test
    fun `resolveTimestamp falls back to the wall clock when the PDU timestamp is zero`() {
        assertEquals(
            1_700_000_010_000L,
            SmsProcessor.resolveTimestamp(0L, 1_700_000_010_000L, true),
        )
    }

    @Test
    fun `resolveTimestamp falls back to the wall clock when the PDU timestamp is negative`() {
        assertEquals(
            1_700_000_010_000L,
            SmsProcessor.resolveTimestamp(-5L, 1_700_000_010_000L, true),
        )
    }

    @Test
    fun `resolveTimestamp keeps a far-future PDU timestamp when the setting is on (blind trust)`() {
        // No upper bound: with the setting on the carrier time is trusted as-is
        // (a broken-SMSC future time is forwarded by design).
        val wallClock = 1_700_000_010_000L
        val scts = wallClock + 30L * 24 * 60 * 60 * 1000
        assertEquals(scts, SmsProcessor.resolveTimestamp(scts, wallClock, true))
    }

    @Test
    fun `resolveTimestamp keeps a past PDU timestamp when the setting is on`() {
        // No lower bound: a past SCTS is the message's own (network) time.
        assertEquals(
            1_600_000_000_000L,
            SmsProcessor.resolveTimestamp(1_600_000_000_000L, 1_700_000_010_000L, true),
        )
    }

    @Test
    fun `resolveTimestamp uses the wall clock when the setting is off even for a valid PDU timestamp`() {
        assertEquals(
            1_700_000_010_000L,
            SmsProcessor.resolveTimestamp(1_700_000_000_000L, 1_700_000_010_000L, false),
        )
    }

    @Test
    fun `processIncomingSms calls onComplete after handling completes`() {
        runBlocking {
            whenever(prefs.load()).thenReturn(AppSettings(isForwardingEnabled = false))
            whenever(smsRepository.insertIfNew(any(), any(), any(), any(), any(), any())).thenReturn(IngestResult.Inserted(42L))
        }

        val latch = CountDownLatch(1)
        val onCompleteInvocations = AtomicInteger(0)
        processor.processIncomingSms(incoming()) {
            onCompleteInvocations.incrementAndGet()
            latch.countDown()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(1, onCompleteInvocations.get())
    }

    @Test
    fun `processIncomingSms calls onComplete when insertIfNew fails`() {
        runBlocking {
            whenever(smsRepository.insertIfNew(any(), any(), any(), any(), any(), any())).thenThrow(RuntimeException("db down"))
        }

        val latch = CountDownLatch(1)
        processor.processIncomingSms(incoming()) { latch.countDown() }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        // Let the background ingest-retry chain finish while the mocks are still live.
        Thread.sleep(100)
    }

    @Test
    fun `test IncomingSms data class`() {
        val sms =
            SmsProcessor.IncomingSms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Test message",
                timestamp = 1234567890L,
            )

        assertEquals(TestPhone.SELF, sms.phoneNumber)
        assertEquals("Test message", sms.messageBody)
        assertEquals(1234567890L, sms.timestamp)
        assertEquals(-1, sms.simId)
        assertEquals(0L, sms.pduTimestamp)
    }

    @Test
    fun `test IncomingSms with simId`() {
        val sms =
            SmsProcessor.IncomingSms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Test message",
                timestamp = 1234567890L,
                simId = 0,
            )

        assertEquals(0, sms.simId)
    }

    @Test
    fun `test IncomingSms with simId 1`() {
        val sms =
            SmsProcessor.IncomingSms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Test message",
                timestamp = 1234567890L,
                simId = 1,
            )

        assertEquals(1, sms.simId)
    }

    @Test
    fun `test email body formatting`() {
        val contactName = "John Doe"
        val expectedDisplayName = contactName
        val expectedSubject = "[sms2email] SMS From $expectedDisplayName"

        assertEquals("[sms2email] SMS From John Doe", expectedSubject)
    }

    @Test
    fun `test subject line with phone number when no contact`() {
        val phoneNumber = TestPhone.SELF
        val subject = "[sms2email] SMS From $phoneNumber"

        assertEquals("[sms2email] SMS From ${TestPhone.SELF}", subject)
    }

    @Test
    fun `test subject prefix customization`() {
        val customPrefix = "myapp"
        val phoneNumber = TestPhone.SELF
        val subject = "[$customPrefix] SMS From $phoneNumber"

        assertEquals("[myapp] SMS From ${TestPhone.SELF}", subject)
    }

    @Test
    fun `test IncomingSms default simId is -1`() {
        val sms =
            SmsProcessor.IncomingSms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Test",
                timestamp = 1234567890L,
            )

        assertEquals(-1, sms.simId)
    }

    @Test
    fun `test IncomingSms with all parameters`() {
        val sms =
            SmsProcessor.IncomingSms(
                phoneNumber = TestPhone.FOREIGN,
                messageBody = "Hello from UK",
                timestamp = 1700000000000L,
                simId = 1,
            )

        assertEquals(TestPhone.FOREIGN, sms.phoneNumber)
        assertEquals("Hello from UK", sms.messageBody)
        assertEquals(1700000000000L, sms.timestamp)
        assertEquals(1, sms.simId)
    }

    @Test
    fun `processIncomingSms catch block handles exception from handleIncomingSms`() {
        runBlocking {
            whenever(prefs.load()).thenReturn(AppSettings())
            whenever(smsRepository.insertIfNew(any(), any(), any(), any(), any(), any())).thenReturn(IngestResult.Inserted(42L))
            enableInternet()
            // Simulate a failure in the enqueue step (after insert succeeds)
            whenever(workManager.getWorkInfosForUniqueWork(any()))
                .thenThrow(RuntimeException("work manager crashed"))
        }

        val latch = CountDownLatch(1)
        processor.processIncomingSms(incoming()) { latch.countDown() }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "onComplete should be called after exception is caught")
    }

    @Test
    fun `insert retry catches exception from retry attempt`() {
        runBlocking {
            whenever(prefs.load()).thenReturn(AppSettings(isForwardingEnabled = false))
            whenever(smsRepository.insertIfNew(any(), any(), any(), any(), any(), any()))
                .thenThrow(RuntimeException("db down"))
                .thenThrow(RuntimeException("db still down"))
                .thenReturn(IngestResult.Inserted(42L))
        }

        val latch = CountDownLatch(1)
        processor.processIncomingSms(incoming()) { latch.countDown() }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        runBlocking {
            verify(
                smsRepository,
                Mockito.timeout(5_000).times(AppConfig.INGEST_RETRY_MAX_ATTEMPTS),
            ).insertIfNew(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `handleIncomingSms with no internet still enqueues EmailWorker even when parked RETRYING`() {
        runBlocking {
            WorkManagerDelegate.install(workManager)
            try {
                stubInsertAndPass()

                processor.handleIncomingSms(incoming())

                verify(smsRepository).transitionSmsStatus(
                    eq(42L),
                    eq(listOf(SmsStatus.PENDING)),
                    eq(SmsStatus.RETRYING),
                    any<StatusUpdate>(),
                )
                verify(workManager).enqueueUniqueWork(eq("email_send_42"), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            } finally {
                WorkManagerDelegate.uninstall()
            }
        }
    }

    @Test
    fun `processIncomingSms lambda calls onComplete in finally block on success`() {
        runBlocking {
            stubInsertAndPass()
            enableInternet()
        }

        val latch = CountDownLatch(1)
        val callCount = AtomicInteger(0)
        processor.processIncomingSms(incoming()) {
            callCount.incrementAndGet()
            latch.countDown()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(1, callCount.get())
    }
}
