package app.kotowski.sms2email.work

import android.content.Context
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.filter.FilterMode
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.SimNameResolver
import app.kotowski.sms2email.testing.sms
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmsActionsTest {
    private lateinit var closeable: AutoCloseable

    @Mock private lateinit var repository: SmsRepository

    @Mock private lateinit var appContext: Context

    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var retryScheduler: SmsRetryScheduler

    @Mock private lateinit var simNameResolver: SimNameResolver

    private lateinit var actions: SmsActions

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        actions = SmsActions(repository, appContext, prefs, retryScheduler, simNameResolver)
    }

    @After
    fun tearDown() {
        closeable.close()
    }

    @Test
    fun `cancelSms reports the cancelled rows when every attempted row transitions`() {
        runBlocking {
            whenever(repository.getSmsByIds(eq(listOf(1L, 2L)))).thenReturn(
                listOf(sms(1L, SmsStatus.PENDING), sms(2L, SmsStatus.RETRYING)),
            )
            whenever(
                repository.transitionSmsStatusMany(
                    eq(listOf(1L, 2L)),
                    eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                    eq(SmsStatus.IGNORED),
                    any(),
                ),
            ).thenReturn(listOf(1L, 2L))
        }
        whenever(appContext.getString(R.string.sms_status_cancelled_by_user)).thenReturn("Canceled by user.")

        val outcome = runBlocking { actions.cancelSms(listOf(1L, 2L)) }

        assertEquals(CancelOutcome(cancelled = 2, attempted = 2, requested = 2), outcome)
    }

    // The branch no ViewModel test exercises: the fetch saw the row as cancellable, but
    // the CAS lost the race for every row, so the whole cancel was a no-op and must
    // report as fully blocked, not "some".
    @Test
    fun `cancelSms reports fully blocked when every attempted row is skipped by the CAS`() {
        runBlocking {
            whenever(repository.getSmsByIds(eq(listOf(1L)))).thenReturn(listOf(sms(1L, SmsStatus.PENDING)))
            whenever(
                repository.transitionSmsStatusMany(
                    eq(listOf(1L)),
                    eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                    eq(SmsStatus.IGNORED),
                    any(),
                ),
            ).thenReturn(emptyList())
        }
        whenever(appContext.getString(R.string.sms_status_cancelled_by_user)).thenReturn("Canceled by user.")

        val outcome = runBlocking { actions.cancelSms(listOf(1L)) }

        assertEquals(CancelOutcome(cancelled = 0, attempted = 1, requested = 1), outcome)
        runBlocking {
            verify(repository, never()).deleteSmsByIds(any())
            Unit
        }
    }

    @Test
    fun `cancelSms reports the partial skip when only some rows transition`() {
        runBlocking {
            whenever(repository.getSmsByIds(eq(listOf(1L, 2L)))).thenReturn(
                listOf(sms(1L, SmsStatus.PENDING), sms(2L, SmsStatus.RETRYING)),
            )
            whenever(
                repository.transitionSmsStatusMany(
                    eq(listOf(1L, 2L)),
                    eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                    eq(SmsStatus.IGNORED),
                    any(),
                ),
            ).thenReturn(listOf(1L))
        }
        whenever(appContext.getString(R.string.sms_status_cancelled_by_user)).thenReturn("Canceled by user.")

        val outcome = runBlocking { actions.cancelSms(listOf(1L, 2L)) }

        assertEquals(CancelOutcome(cancelled = 1, attempted = 2, requested = 2), outcome)
    }

    // In-flight SENDING rows are left to finish: the fetch sees them, the exclusion
    // drops them, and the no-op reports as fully blocked (the same shape as an empty
    // fetch — the UI reads both as "can't be cancelled").
    @Test
    fun `cancelSms reports fully blocked and never transitions when every row is SENDING`() {
        runBlocking {
            whenever(repository.getSmsByIds(eq(listOf(1L, 2L)))).thenReturn(
                listOf(sms(1L, SmsStatus.SENDING), sms(2L, SmsStatus.SENDING)),
            )
        }

        val outcome = runBlocking { actions.cancelSms(listOf(1L, 2L)) }

        assertEquals(CancelOutcome(cancelled = 0, attempted = 0, requested = 2), outcome)
        runBlocking {
            verify(repository, never()).transitionSmsStatusMany(any(), any(), any(), any())
            Unit
        }
    }

    @Test
    fun `cancelSms reports fully blocked and never transitions when the fetch is empty`() {
        runBlocking {
            whenever(repository.getSmsByIds(eq(listOf(9L)))).thenReturn(emptyList())
        }

        val outcome = runBlocking { actions.cancelSms(listOf(9L)) }

        assertEquals(CancelOutcome(cancelled = 0, attempted = 0, requested = 1), outcome)
        runBlocking {
            verify(repository, never()).transitionSmsStatusMany(any(), any(), any(), any())
            Unit
        }
    }

    // The queue's "Cancel All" fetches the whole queue (not a selection): an empty
    // queue reports (0, 0, 0), which the UI reads as silent (nothing was cancelled),
    // unlike a non-empty fully blocked cancel, which reads the requested count.
    @Test
    fun `cancelQueue reports the empty queue as silent and never transitions`() {
        runBlocking {
            whenever(repository.getQueueIds()).thenReturn(emptyList())
        }

        val outcome = runBlocking { actions.cancelQueue() }

        assertEquals(CancelOutcome(cancelled = 0, attempted = 0, requested = 0), outcome)
        runBlocking {
            verify(repository, never()).getSmsByIds(any())
            verify(repository, never()).transitionSmsStatusMany(any(), any(), any(), any())
            Unit
        }
    }

    // A non-empty queue delegates to the shared cancel: the SENDING row is excluded
    // up front, the rest is parked as IGNORED, and the counts read the same way.
    @Test
    fun `cancelQueue cancels the whole queue through the shared cancel`() {
        runBlocking {
            whenever(repository.getQueueIds()).thenReturn(listOf(1L, 2L, 3L))
            whenever(repository.getSmsByIds(eq(listOf(1L, 2L, 3L)))).thenReturn(
                listOf(sms(1L, SmsStatus.PENDING), sms(2L, SmsStatus.SENDING), sms(3L, SmsStatus.RETRYING)),
            )
            whenever(
                repository.transitionSmsStatusMany(
                    eq(listOf(1L, 3L)),
                    eq(listOf(SmsStatus.PENDING, SmsStatus.RETRYING)),
                    eq(SmsStatus.IGNORED),
                    any(),
                ),
            ).thenReturn(listOf(1L, 3L))
        }
        whenever(appContext.getString(R.string.sms_status_cancelled_by_user)).thenReturn("Canceled by user.")

        val outcome = runBlocking { actions.cancelQueue() }

        assertEquals(CancelOutcome(cancelled = 2, attempted = 2, requested = 3), outcome)
    }

    @Test
    fun `deleteSms reports the deleted and requested counts`() {
        runBlocking {
            whenever(repository.deleteSmsByIds(eq(listOf(1L, 2L)))).thenReturn(1)
        }

        val outcome = runBlocking { actions.deleteSms(listOf(1L, 2L)) }

        assertEquals(DeleteOutcome(deleted = 1, requested = 2), outcome)
    }

    @Test
    fun `deleteSms reports a full delete when every row was removed`() {
        runBlocking {
            whenever(repository.deleteSmsByIds(eq(listOf(1L)))).thenReturn(1)
        }

        val outcome = runBlocking { actions.deleteSms(listOf(1L)) }

        assertEquals(DeleteOutcome(deleted = 1, requested = 1), outcome)
    }

    @Test
    fun `ForwardOutcome blocked derives from found minus scheduled`() {
        val outcome = ForwardOutcome(found = 5, scheduled = 3)

        assertEquals(2, outcome.blocked)
    }

    @Test
    fun `ForwardOutcome blocked is zero when all are scheduled`() {
        val outcome = ForwardOutcome(found = 3, scheduled = 3)

        assertEquals(0, outcome.blocked)
    }

    @Test
    fun `ForwardOutcome carries forwardingOff flag`() {
        val outcome = ForwardOutcome(found = 0, scheduled = 0, forwardingOff = true)

        assertTrue(outcome.forwardingOff)
        assertEquals(0, outcome.blocked)
    }

    @Test
    fun `ForwardOutcome carries filter counts and mode`() {
        val outcome =
            ForwardOutcome(found = 5, scheduled = 2, filterBlocked = 2, filterMode = FilterMode.FORWARD)

        assertEquals(3, outcome.blocked)
        assertEquals(2, outcome.filterBlocked)
        assertEquals(FilterMode.FORWARD, outcome.filterMode)
    }

    @Test
    fun `CancelOutcome blocked derives from attempted minus cancelled`() {
        val outcome = CancelOutcome(cancelled = 1, attempted = 3, requested = 3)

        assertEquals(2, outcome.blocked)
    }

    @Test
    fun `CancelOutcome blocked is zero when all attempted are cancelled`() {
        val outcome = CancelOutcome(cancelled = 2, attempted = 2, requested = 2)

        assertEquals(0, outcome.blocked)
    }

    @Test
    fun `CancelOutcome empty queue is zero across all counts`() {
        val outcome = CancelOutcome(cancelled = 0, attempted = 0, requested = 0)

        assertEquals(0, outcome.blocked)
        assertEquals(0, outcome.requested)
    }
}
