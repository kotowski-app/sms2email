package app.kotowski.sms2email.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkContinuation
import androidx.work.WorkInfo
import androidx.work.impl.WorkManagerImpl
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.StatusUpdate
import app.kotowski.sms2email.data.filter.FilterMode
import app.kotowski.sms2email.data.filter.FilterRule
import app.kotowski.sms2email.data.filter.SmsFilter
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.SimNameResolver
import app.kotowski.sms2email.testing.WorkManagerDelegate
import app.kotowski.sms2email.testing.sms
import app.kotowski.sms2email.testing.stubInternetEnabled
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SmsActionsForwardTest {
    private lateinit var closeable: AutoCloseable

    @Mock private lateinit var repository: SmsRepository

    @Mock private lateinit var retryScheduler: SmsRetryScheduler

    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var appContext: Context

    @Mock private lateinit var simNameResolver: SimNameResolver

    @Mock private lateinit var workManager: WorkManagerImpl

    @Mock private lateinit var workContinuation: WorkContinuation

    @Mock private lateinit var workInfosFuture: ListenableFuture<List<WorkInfo>>

    @Mock private lateinit var connectivityManager: ConnectivityManager

    @Mock private lateinit var network: Network

    @Mock private lateinit var networkCapabilities: NetworkCapabilities

    private lateinit var actions: SmsActions

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        actions = SmsActions(repository, appContext, prefs, retryScheduler, simNameResolver)
        whenever(appContext.getString(R.string.error_waiting_for_internet)).thenReturn("No Internet connection.")
        whenever(appContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)
        // no active network by default -> NetworkUtil.hasReliableInternet is false
        whenever(connectivityManager.activeNetwork).thenReturn(null)
        // no live batch work by default -> the batch is enqueued with REPLACE
        whenever(workManager.getWorkInfosByTag(EmailBatchWorker.BATCH_TAG)).thenReturn(workInfosFuture)
        // isDone=true puts await() on its fast path, which calls the stubbed no-arg get().
        whenever(workInfosFuture.isDone()).thenReturn(true)
        whenever(workInfosFuture.get()).thenReturn(emptyList())
        whenever(
            workManager.beginUniqueWork(eq(EmailBatchWorker.WORK_NAME), any(), any<OneTimeWorkRequest>()),
        ).thenReturn(workContinuation)
    }

    @After
    fun tearDown() {
        closeable.close()
    }

    @Test
    fun `transitionAndEnqueueBatch enqueues the batch and returns the transitioned ids`() {
        WorkManagerDelegate.install(workManager)
        try {
            runBlocking {
                whenever(
                    repository.transitionSmsStatusMany(
                        eq(listOf(1L, 2L)),
                        eq(SmsStatus.MANUAL_FORWARDABLE),
                        eq(SmsStatus.PENDING),
                        any(),
                    ),
                ).thenReturn(listOf(1L, 2L))
            }

            val result =
                runBlocking {
                    actions.transitionAndEnqueueBatch(
                        listOf(1L, 2L),
                        SmsStatus.MANUAL_FORWARDABLE,
                        SmsStatus.PENDING,
                        enqueue = { batchIds -> EmailBatchWorker.enqueue(appContext, batchIds) },
                    )
                }

            assertEquals(listOf(1L, 2L), result)
            verify(workManager).beginUniqueWork(eq(EmailBatchWorker.WORK_NAME), any(), any<OneTimeWorkRequest>())
            verify(workContinuation).enqueue()
        } finally {
            WorkManagerDelegate.uninstall()
        }
    }

    @Test
    fun `transitionAndEnqueueBatch returns empty and enqueues nothing when no row transitioned`() {
        WorkManagerDelegate.install(workManager)
        try {
            runBlocking {
                whenever(repository.transitionSmsStatusMany(any(), any(), any(), any())).thenReturn(emptyList())
            }

            val result =
                runBlocking {
                    actions.transitionAndEnqueueBatch(
                        listOf(1L),
                        SmsStatus.MANUAL_FORWARDABLE,
                        SmsStatus.PENDING,
                        enqueue = { batchIds -> EmailBatchWorker.enqueue(appContext, batchIds) },
                    )
                }

            assertEquals(emptyList(), result)
            verify(workManager, never()).beginUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>())
            verify(workContinuation, never()).enqueue()
        } finally {
            WorkManagerDelegate.uninstall()
        }
    }

    @Test
    fun `transitionAndEnqueueBatch marks the rows FAILED when the enqueue throws`() {
        runBlocking {
            whenever(
                repository.transitionSmsStatusMany(
                    eq(listOf(1L, 2L)),
                    eq(SmsStatus.MANUAL_FORWARDABLE),
                    eq(SmsStatus.PENDING),
                    any(),
                ),
            ).thenReturn(listOf(1L, 2L))
            whenever(appContext.getString(R.string.error_worker_enqueue_failed)).thenReturn("Failed to enqueue the send worker.")
        }

        val result =
            runBlocking {
                actions.transitionAndEnqueueBatch(
                    listOf(1L, 2L),
                    SmsStatus.MANUAL_FORWARDABLE,
                    SmsStatus.PENDING,
                    enqueue = { throw RuntimeException("some raw english exception message") },
                )
            }

        // The guard must not hide the CAS outcome: the transitioned ids are returned.
        assertEquals(listOf(1L, 2L), result)
        runBlocking {
            // Stranded-row guard: the rows are parked FAILED with the localized detail.
            verify(repository).transitionSmsStatusMany(
                eq(listOf(1L, 2L)),
                eq(listOf(SmsStatus.PENDING)),
                eq(SmsStatus.FAILED),
                eq(StatusUpdate(statusDetails = "Failed to enqueue the send worker.")),
            )
        }
    }

    // Coroutine cancellation (the scope closing mid-forward) is not an enqueue failure:
    // it must propagate instead of parking the rows FAILED and logging a worker failure.
    @Test
    fun `transitionAndEnqueueBatch rethrows coroutine cancellation from the enqueue instead of marking the rows FAILED`() {
        runBlocking {
            whenever(
                repository.transitionSmsStatusMany(
                    eq(listOf(1L, 2L)),
                    eq(SmsStatus.MANUAL_FORWARDABLE),
                    eq(SmsStatus.PENDING),
                    any(),
                ),
            ).thenReturn(listOf(1L, 2L))
        }

        val failure =
            runBlocking {
                try {
                    actions.transitionAndEnqueueBatch(
                        listOf(1L, 2L),
                        SmsStatus.MANUAL_FORWARDABLE,
                        SmsStatus.PENDING,
                        enqueue = { throw CancellationException("scope cancelled") },
                    )
                    null
                } catch (e: CancellationException) {
                    e
                }
            }

        assertNotNull(failure)
        assertEquals("scope cancelled", failure.message)
        runBlocking {
            verify(repository, never()).transitionSmsStatusMany(any(), any(), eq(SmsStatus.FAILED), any())
            Unit
        }
    }

    @Test
    fun `forwardSms reports forwardingOff and does nothing when forwarding is disabled`() {
        whenever(prefs.load()).thenReturn(AppSettings(isForwardingEnabled = false))

        val outcome =
            runBlocking {
                actions.forwardSms(listOf(1L, 2L)) { EmailBatchWorker.enqueue(appContext, it) }
            }

        assertEquals(ForwardOutcome(found = 0, scheduled = 0, forwardingOff = true), outcome)
        runBlocking {
            verify(repository, never()).getSmsByIds(any())
            verify(retryScheduler, never()).scheduleFirstRetry(any(), any(), any())
            Unit
        }
    }

    @Test
    fun `forwardSms reports empty and does nothing when there is nothing to forward`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        runBlocking {
            whenever(repository.getSmsByIds(any())).thenReturn(emptyList())
        }

        val outcome =
            runBlocking {
                actions.forwardSms(listOf(1L, 2L)) { EmailBatchWorker.enqueue(appContext, it) }
            }

        assertEquals(ForwardOutcome(found = 0, scheduled = 0), outcome)
        runBlocking {
            verify(repository, never()).transitionSmsStatusMany(any(), any(), any(), any())
            verify(retryScheduler, never()).scheduleFirstRetry(any(), any(), any())
            Unit
        }
    }

    @Test
    fun `forwardSms parks every row for a first retry when offline`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        runBlocking {
            whenever(repository.getSmsByIds(any())).thenReturn(
                listOf(sms(1L, SmsStatus.SENT), sms(2L, SmsStatus.SENT)),
            )
            whenever(retryScheduler.scheduleFirstRetry(any(), any(), any())).thenReturn(true)
        }

        val outcome =
            runBlocking {
                actions.forwardSms(listOf(1L, 2L)) { EmailBatchWorker.enqueue(appContext, it) }
            }

        assertEquals(ForwardOutcome(found = 2, scheduled = 2), outcome)
        runBlocking {
            verify(retryScheduler).scheduleFirstRetry(eq(1L), eq("No Internet connection."), eq(SmsStatus.MANUAL_FORWARDABLE))
            verify(retryScheduler).scheduleFirstRetry(eq(2L), eq("No Internet connection."), eq(SmsStatus.MANUAL_FORWARDABLE))
            verify(repository, never()).transitionSmsStatusMany(any(), any(), any(), any())
        }
    }

    @Test
    fun `forwardSms reports the unparked row when a row could not be parked`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        runBlocking {
            whenever(repository.getSmsByIds(any())).thenReturn(
                listOf(sms(1L, SmsStatus.SENT), sms(2L, SmsStatus.SENT)),
            )
            whenever(retryScheduler.scheduleFirstRetry(eq(1L), any(), any())).thenReturn(true)
            whenever(retryScheduler.scheduleFirstRetry(eq(2L), any(), any())).thenReturn(false)
        }

        val outcome =
            runBlocking {
                actions.forwardSms(listOf(1L, 2L)) { EmailBatchWorker.enqueue(appContext, it) }
            }

        assertEquals(ForwardOutcome(found = 2, scheduled = 1), outcome)
    }

    @Test
    fun `forwardSms forwards the batch when online`() {
        WorkManagerDelegate.install(workManager)
        try {
            stubInternetEnabled(connectivityManager, network, networkCapabilities)
            whenever(prefs.load()).thenReturn(AppSettings())
            runBlocking {
                whenever(repository.getSmsByIds(any())).thenReturn(
                    listOf(sms(1L, SmsStatus.SENT), sms(2L, SmsStatus.SENT)),
                )
                whenever(repository.transitionSmsStatusMany(any(), any(), any(), any())).thenReturn(listOf(1L, 2L))
            }

            val outcome =
                runBlocking {
                    actions.forwardSms(listOf(1L, 2L)) { EmailBatchWorker.enqueue(appContext, it) }
                }

            assertEquals(ForwardOutcome(found = 2, scheduled = 2), outcome)
            runBlocking {
                verify(repository).transitionSmsStatusMany(
                    eq(listOf(1L, 2L)),
                    eq(SmsStatus.MANUAL_FORWARDABLE),
                    eq(SmsStatus.PENDING),
                    any(),
                )
            }
            verify(workManager).beginUniqueWork(eq(EmailBatchWorker.WORK_NAME), any(), any<OneTimeWorkRequest>())
            verify(workContinuation).enqueue()
            runBlocking {
                verify(retryScheduler, never()).scheduleFirstRetry(any(), any(), any())
                Unit
            }
        } finally {
            WorkManagerDelegate.uninstall()
        }
    }

    // The queue's list path narrows the CAS to the in-flight statuses: the parameter
    // must reach the transition call, or a delivered row would be re-forwarded.
    @Test
    fun `forwardSms uses the caller-narrowed expected statuses for the CAS`() {
        WorkManagerDelegate.install(workManager)
        try {
            stubInternetEnabled(connectivityManager, network, networkCapabilities)
            whenever(prefs.load()).thenReturn(AppSettings())
            runBlocking {
                whenever(repository.getSmsByIds(any())).thenReturn(
                    listOf(sms(1L, SmsStatus.PENDING), sms(2L, SmsStatus.RETRYING)),
                )
                whenever(
                    repository.transitionSmsStatusMany(
                        eq(listOf(1L, 2L)),
                        eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                        eq(SmsStatus.PENDING),
                        any(),
                    ),
                ).thenReturn(listOf(1L, 2L))
            }

            val outcome =
                runBlocking {
                    actions.forwardSms(
                        listOf(1L, 2L),
                        expectedStatuses = listOf(SmsStatus.PENDING, SmsStatus.RETRYING),
                    ) { EmailBatchWorker.enqueue(appContext, it) }
                }

            assertEquals(ForwardOutcome(found = 2, scheduled = 2), outcome)
            runBlocking {
                verify(repository).transitionSmsStatusMany(
                    eq(listOf(1L, 2L)),
                    eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                    eq(SmsStatus.PENDING),
                    any(),
                )
            }
            verify(workManager).beginUniqueWork(eq(EmailBatchWorker.WORK_NAME), any(), any<OneTimeWorkRequest>())
            verify(workContinuation).enqueue()
        } finally {
            WorkManagerDelegate.uninstall()
        }
    }

    // The offline park path narrows the same way as the CAS: a row delivered while
    // selected (SENT, outside the expected statuses) must not be parked, or re-sent.
    @Test
    fun `forwardSms parks only the rows the narrowed expected statuses cover when offline`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        runBlocking {
            whenever(repository.getSmsByIds(any())).thenReturn(
                listOf(sms(1L, SmsStatus.SENT), sms(2L, SmsStatus.RETRYING)),
            )
            whenever(retryScheduler.scheduleFirstRetry(eq(1L), any(), eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING))))
                .thenReturn(false)
            whenever(retryScheduler.scheduleFirstRetry(eq(2L), any(), eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING))))
                .thenReturn(true)
        }

        val outcome =
            runBlocking {
                actions.forwardSms(
                    listOf(1L, 2L),
                    expectedStatuses = listOf(SmsStatus.PENDING, SmsStatus.RETRYING),
                ) { EmailBatchWorker.enqueue(appContext, it) }
            }

        // The delivered row is not parked: it reads as blocked, like the online CAS.
        assertEquals(ForwardOutcome(found = 2, scheduled = 1), outcome)
        runBlocking {
            verify(retryScheduler).scheduleFirstRetry(eq(1L), any(), eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)))
            verify(retryScheduler).scheduleFirstRetry(eq(2L), any(), eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)))
        }
    }

    @Test
    fun `forwardSms reports the skipped rows when some rows could not transition`() {
        WorkManagerDelegate.install(workManager)
        try {
            stubInternetEnabled(connectivityManager, network, networkCapabilities)
            whenever(prefs.load()).thenReturn(AppSettings())
            runBlocking {
                whenever(repository.getSmsByIds(any())).thenReturn(
                    listOf(sms(1L, SmsStatus.SENT), sms(2L, SmsStatus.SENT)),
                )
                whenever(repository.transitionSmsStatusMany(any(), any(), any(), any())).thenReturn(listOf(1L))
            }

            val outcome =
                runBlocking {
                    actions.forwardSms(listOf(1L, 2L)) { EmailBatchWorker.enqueue(appContext, it) }
                }

            assertEquals(ForwardOutcome(found = 2, scheduled = 1), outcome)
        } finally {
            WorkManagerDelegate.uninstall()
        }
    }

    @Test
    fun `forwardSms reports a single blocked row when a single row could not transition`() {
        stubInternetEnabled(connectivityManager, network, networkCapabilities)
        whenever(prefs.load()).thenReturn(AppSettings())
        runBlocking {
            whenever(repository.getSmsByIds(any())).thenReturn(listOf(sms(1L, SmsStatus.SENT)))
            whenever(repository.transitionSmsStatusMany(any(), any(), any(), any())).thenReturn(emptyList())
        }

        val outcome =
            runBlocking {
                actions.forwardSms(listOf(1L)) { EmailBatchWorker.enqueue(appContext, it) }
            }

        assertEquals(ForwardOutcome(found = 1, scheduled = 0), outcome)
    }

    // The manual-forward partition: in-flight (SENDING) rows split off before the
    // filter (its verdict is moot for them); filtered rows get the filterBlocked bucket.

    @Test
    fun `forwardSms keeps an in-flight row in the in-flight bucket even when the filter would block it`() {
        whenever(prefs.load()).thenReturn(
            AppSettings(filterEnabled = true, filter = SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = ".")))),
        )
        runBlocking {
            whenever(repository.getSmsByIds(any())).thenReturn(
                listOf(sms(1L, SmsStatus.SENDING), sms(2L, SmsStatus.SENT)),
            )
        }

        val outcome =
            runBlocking {
                actions.forwardSms(listOf(1L, 2L)) { EmailBatchWorker.enqueue(appContext, it) }
            }

        // found = in-flight + filter-passing = 1 + 0; the SENDING row is not counted as
        // filter-blocked (a filter-first partition would toast "skipped" mid-delivery).
        assertEquals(
            ForwardOutcome(found = 1, scheduled = 0, filterBlocked = 1, filterMode = FilterMode.SKIP),
            outcome,
        )
        runBlocking {
            verify(repository, never()).transitionSmsStatusMany(any(), any(), any(), any())
            verify(retryScheduler, never()).scheduleFirstRetry(any(), any(), any())
            Unit
        }
    }

    @Test
    fun `forwardSms reports the filter-blocked rows separately and enqueues only the passing rows`() {
        WorkManagerDelegate.install(workManager)
        try {
            stubInternetEnabled(connectivityManager, network, networkCapabilities)
            whenever(prefs.load()).thenReturn(
                AppSettings(filterEnabled = true, filter = SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "blocked")))),
            )
            runBlocking {
                whenever(repository.getSmsByIds(any())).thenReturn(
                    listOf(
                        sms(1L, SmsStatus.SENDING),
                        sms(2L, SmsStatus.PENDING, messageBody = "blocked"),
                        sms(3L, SmsStatus.PENDING, messageBody = "ok"),
                    ),
                )
                whenever(repository.transitionSmsStatusMany(any(), any(), any(), any())).thenReturn(listOf(3L))
            }

            val outcome =
                runBlocking {
                    actions.forwardSms(listOf(1L, 2L, 3L)) { EmailBatchWorker.enqueue(appContext, it) }
                }

            // found = in-flight + filter-passing = 1 + 1; the filtered row is its own
            // bucket, and the derived in-flight blocked = found - scheduled = 1.
            assertEquals(
                ForwardOutcome(found = 2, scheduled = 1, filterBlocked = 1, filterMode = FilterMode.SKIP),
                outcome,
            )
            runBlocking {
                verify(repository).transitionSmsStatusMany(
                    eq(listOf(3L)),
                    eq(SmsStatus.MANUAL_FORWARDABLE),
                    eq(SmsStatus.PENDING),
                    any(),
                )
            }
        } finally {
            WorkManagerDelegate.uninstall()
        }
    }

    @Test
    fun `forwardSms parks only the filter-passing rows when offline`() {
        whenever(prefs.load()).thenReturn(
            AppSettings(filterEnabled = true, filter = SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "bad")))),
        )
        runBlocking {
            whenever(repository.getSmsByIds(any())).thenReturn(
                listOf(
                    sms(1L, SmsStatus.SENT, messageBody = "bad news"),
                    sms(2L, SmsStatus.SENT, messageBody = "fine"),
                ),
            )
            whenever(retryScheduler.scheduleFirstRetry(any(), any(), any())).thenReturn(true)
        }

        val outcome =
            runBlocking {
                actions.forwardSms(listOf(1L, 2L)) { EmailBatchWorker.enqueue(appContext, it) }
            }

        assertEquals(
            ForwardOutcome(found = 1, scheduled = 1, filterBlocked = 1, filterMode = FilterMode.SKIP),
            outcome,
        )
        runBlocking {
            // The filtered row must never be parked: it would be sent once the network
            // returns, the filter bypassed.
            verify(retryScheduler, never()).scheduleFirstRetry(eq(1L), any(), any())
            verify(retryScheduler).scheduleFirstRetry(eq(2L), any(), any())
        }
    }

    @Test
    fun `forwardSms evaluates the body facet when the slot map is empty (permission denied)`() {
        // Permission denied -> the resolver reports no slots (mock default null): the
        // slot facet is inactive, so a body-only rule still decides.
        whenever(prefs.load()).thenReturn(
            AppSettings(
                filterEnabled = true,
                filter = SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "bad"))),
            ),
        )
        runBlocking {
            whenever(repository.getSmsByIds(any())).thenReturn(
                listOf(
                    sms(1L, SmsStatus.SENT, messageBody = "bad news"),
                    sms(2L, SmsStatus.SENT, messageBody = "fine"),
                ),
            )
            whenever(retryScheduler.scheduleFirstRetry(any(), any(), any())).thenReturn(true)
        }

        val outcome =
            runBlocking {
                actions.forwardSms(listOf(1L, 2L)) { EmailBatchWorker.enqueue(appContext, it) }
            }

        assertEquals(
            ForwardOutcome(found = 1, scheduled = 1, filterBlocked = 1, filterMode = FilterMode.SKIP),
            outcome,
        )
        runBlocking {
            verify(retryScheduler).scheduleFirstRetry(eq(2L), any(), any())
            Unit
        }
    }

    @Test
    fun `forwardSms ignores the filter rules while the toggle is off`() {
        WorkManagerDelegate.install(workManager)
        try {
            stubInternetEnabled(connectivityManager, network, networkCapabilities)
            // A rule that matches every body, but the feature toggle is off: nothing is
            // filter-blocked (filterBlocked = 0), the row passes the partition.
            whenever(prefs.load()).thenReturn(
                AppSettings(filterEnabled = false, filter = SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = ".")))),
            )
            runBlocking {
                whenever(repository.getSmsByIds(any())).thenReturn(
                    listOf(sms(1L, SmsStatus.PENDING, messageBody = "anything")),
                )
                whenever(repository.transitionSmsStatusMany(any(), any(), any(), any())).thenReturn(listOf(1L))
            }

            val outcome =
                runBlocking {
                    actions.forwardSms(listOf(1L)) { EmailBatchWorker.enqueue(appContext, it) }
                }

            assertEquals(ForwardOutcome(found = 1, scheduled = 1), outcome)
            runBlocking {
                verify(repository).transitionSmsStatusMany(
                    eq(listOf(1L)),
                    eq(SmsStatus.MANUAL_FORWARDABLE),
                    eq(SmsStatus.PENDING),
                    any(),
                )
            }
        } finally {
            WorkManagerDelegate.uninstall()
        }
    }

    @Test
    fun `forwardSms with a forward mode without an active rule blocks nothing`() {
        // The off state: the feature toggle is on, but the filter has no active
        // rule — the predicate forwards everything, so nothing is filter-blocked.
        WorkManagerDelegate.install(workManager)
        try {
            stubInternetEnabled(connectivityManager, network, networkCapabilities)
            whenever(prefs.load()).thenReturn(
                AppSettings(filterEnabled = true, filter = SmsFilter(FilterMode.FORWARD, emptyList())),
            )
            runBlocking {
                whenever(repository.getSmsByIds(any())).thenReturn(
                    listOf(sms(1L, SmsStatus.PENDING, messageBody = "anything at all")),
                )
                whenever(repository.transitionSmsStatusMany(any(), any(), any(), any())).thenReturn(listOf(1L))
            }

            val outcome =
                runBlocking {
                    actions.forwardSms(listOf(1L)) { EmailBatchWorker.enqueue(appContext, it) }
                }

            assertEquals(ForwardOutcome(found = 1, scheduled = 1), outcome)
            runBlocking {
                verify(repository).transitionSmsStatusMany(
                    eq(listOf(1L)),
                    eq(SmsStatus.MANUAL_FORWARDABLE),
                    eq(SmsStatus.PENDING),
                    any(),
                )
            }
        } finally {
            WorkManagerDelegate.uninstall()
        }
    }

    @Test
    fun `forceSendQueue reports forwardingOff and does nothing when forwarding is disabled`() {
        whenever(prefs.load()).thenReturn(AppSettings(isForwardingEnabled = false))

        val outcome = runBlocking { actions.forceSendQueue { EmailBatchWorker.enqueue(appContext, it) } }

        assertEquals(ForwardOutcome(found = 0, scheduled = 0, forwardingOff = true), outcome)
        runBlocking {
            verify(repository, never()).getQueueIds()
            verify(repository, never()).transitionSmsStatusMany(any(), any(), any(), any())
            Unit
        }
    }

    @Test
    fun `forceSendQueue reports empty and does nothing when the queue is empty`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        runBlocking {
            whenever(repository.getQueueIds()).thenReturn(emptyList())
        }

        val outcome = runBlocking { actions.forceSendQueue { EmailBatchWorker.enqueue(appContext, it) } }

        assertEquals(ForwardOutcome(found = 0, scheduled = 0), outcome)
        runBlocking {
            verify(repository, never()).getSmsByIds(any())
            verify(repository, never()).transitionSmsStatusMany(any(), any(), any(), any())
            Unit
        }
    }

    // The queue held rows but all are in flight: the whole queue is counted (the UI's
    // plural reads for it), not reported as an empty queue (which stays silent).
    @Test
    fun `forceSendQueue reports the whole queue blocked when the queue holds only SENDING rows`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        runBlocking {
            whenever(repository.getQueueIds()).thenReturn(listOf(1L, 2L))
            whenever(repository.getSmsByIds(eq(listOf(1L, 2L)))).thenReturn(
                listOf(sms(1L, SmsStatus.SENDING), sms(2L, SmsStatus.SENDING)),
            )
        }

        val outcome = runBlocking { actions.forceSendQueue { EmailBatchWorker.enqueue(appContext, it) } }

        assertEquals(ForwardOutcome(found = 2, scheduled = 0), outcome)
        runBlocking {
            verify(repository, never()).transitionSmsStatusMany(any(), any(), any(), any())
            Unit
        }
    }

    @Test
    fun `forceSendQueue transitions the whole queue with the fresh forward window and enqueues the batch`() {
        WorkManagerDelegate.install(workManager)
        try {
            whenever(prefs.load()).thenReturn(AppSettings())
            runBlocking {
                whenever(repository.getQueueIds()).thenReturn(listOf(1L, 2L))
                whenever(repository.getSmsByIds(eq(listOf(1L, 2L)))).thenReturn(
                    listOf(sms(1L, SmsStatus.PENDING), sms(2L, SmsStatus.RETRYING)),
                )
                whenever(
                    repository.transitionSmsStatusMany(
                        eq(listOf(1L, 2L)),
                        eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                        eq(SmsStatus.RETRYING),
                        any(),
                    ),
                ).thenReturn(listOf(1L, 2L))
            }

            val outcome = runBlocking { actions.forceSendQueue { EmailBatchWorker.enqueue(appContext, it) } }

            assertEquals(ForwardOutcome(found = 2, scheduled = 2), outcome)
            val updateCaptor = argumentCaptor<StatusUpdate>()
            runBlocking {
                verify(repository).transitionSmsStatusMany(
                    eq(listOf(1L, 2L)),
                    eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                    eq(SmsStatus.RETRYING),
                    updateCaptor.capture(),
                )
            }
            val update = updateCaptor.firstValue
            assertNull(update.statusDetails)
            assertEquals(0, update.retryCount)
            assertNotNull(update.timeFirstSendAttempt)
            assertNotNull(update.timeLastSendAttempt)
            assertNotNull(update.timeNextSendAttempt)
            val captor = argumentCaptor<OneTimeWorkRequest>()
            verify(workManager).beginUniqueWork(eq(EmailBatchWorker.WORK_NAME), any(), captor.capture())
            verify(workContinuation).enqueue()
            assertEquals(
                "1,2",
                captor.firstValue.workSpec.input
                    .getString(BATCH_IDS_KEY),
            )
        } finally {
            WorkManagerDelegate.uninstall()
        }
    }

    @Test
    fun `forceSendQueue reports the skipped row and enqueues only the transitioned ids`() {
        WorkManagerDelegate.install(workManager)
        try {
            whenever(prefs.load()).thenReturn(AppSettings())
            runBlocking {
                whenever(repository.getQueueIds()).thenReturn(listOf(1L, 2L))
                whenever(repository.getSmsByIds(eq(listOf(1L, 2L)))).thenReturn(
                    listOf(sms(1L, SmsStatus.PENDING), sms(2L, SmsStatus.RETRYING)),
                )
                whenever(
                    repository.transitionSmsStatusMany(
                        eq(listOf(1L, 2L)),
                        eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                        eq(SmsStatus.RETRYING),
                        any(),
                    ),
                ).thenReturn(listOf(1L))
            }

            val outcome = runBlocking { actions.forceSendQueue { EmailBatchWorker.enqueue(appContext, it) } }

            assertEquals(ForwardOutcome(found = 2, scheduled = 1), outcome)
            val captor = argumentCaptor<OneTimeWorkRequest>()
            verify(workManager).beginUniqueWork(eq(EmailBatchWorker.WORK_NAME), any(), captor.capture())
            verify(workContinuation).enqueue()
            assertEquals(
                "1",
                captor.firstValue.workSpec.input
                    .getString(BATCH_IDS_KEY),
            )
        } finally {
            WorkManagerDelegate.uninstall()
        }
    }

    // The queue's twin of the forward partition: the SENDING exclusion precedes the
    // filter, and the buckets stay separate.

    @Test
    fun `forceSendQueue reports the filter-blocked rows and enqueues only the passing rows`() {
        WorkManagerDelegate.install(workManager)
        try {
            whenever(prefs.load()).thenReturn(
                AppSettings(filterEnabled = true, filter = SmsFilter(FilterMode.FORWARD, listOf(FilterRule(body = "keep")))),
            )
            runBlocking {
                whenever(repository.getQueueIds()).thenReturn(listOf(1L, 2L))
                whenever(repository.getSmsByIds(eq(listOf(1L, 2L)))).thenReturn(
                    listOf(
                        sms(1L, SmsStatus.PENDING, messageBody = "keep me"),
                        sms(2L, SmsStatus.RETRYING, messageBody = "drop me"),
                    ),
                )
                whenever(repository.transitionSmsStatusMany(any(), any(), any(), any())).thenReturn(listOf(1L))
            }

            val outcome = runBlocking { actions.forceSendQueue { EmailBatchWorker.enqueue(appContext, it) } }

            // found = in-flight + filter-passing = 0 + 1; the filtered row is its own
            // bucket, separate from the derived in-flight blocked (= found - scheduled).
            assertEquals(
                ForwardOutcome(found = 1, scheduled = 1, filterBlocked = 1, filterMode = FilterMode.FORWARD),
                outcome,
            )
            val captor = argumentCaptor<OneTimeWorkRequest>()
            verify(workManager).beginUniqueWork(eq(EmailBatchWorker.WORK_NAME), any(), captor.capture())
            assertEquals(
                "1",
                captor.firstValue.workSpec.input
                    .getString(BATCH_IDS_KEY),
            )
        } finally {
            WorkManagerDelegate.uninstall()
        }
    }

    @Test
    fun `forceSendQueue reports a fully filter-blocked queue without transition or enqueue`() {
        whenever(prefs.load()).thenReturn(
            AppSettings(filterEnabled = true, filter = SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = ".")))),
        )
        runBlocking {
            whenever(repository.getQueueIds()).thenReturn(listOf(1L, 2L))
            whenever(repository.getSmsByIds(eq(listOf(1L, 2L)))).thenReturn(
                listOf(sms(1L, SmsStatus.SENDING), sms(2L, SmsStatus.PENDING)),
            )
        }

        val outcome = runBlocking { actions.forceSendQueue { EmailBatchWorker.enqueue(appContext, it) } }

        // found counts the in-flight row only: the queue's rest is all filter-blocked.
        assertEquals(
            ForwardOutcome(found = 1, scheduled = 0, filterBlocked = 1, filterMode = FilterMode.SKIP),
            outcome,
        )
        runBlocking {
            verify(repository, never()).transitionSmsStatusMany(any(), any(), any(), any())
            Unit
        }
        verify(workManager, never()).beginUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>())
    }
}
