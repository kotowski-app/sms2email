package app.kotowski.sms2email.system

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Mirrors SimChangeObserverTest: a mocked ContentResolver captures the observer so
// onChange can be driven directly, and the resolver is a mock so the invalidate() calls
// (the cache hook) are asserted.
@OptIn(ExperimentalCoroutinesApi::class)
@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class ContactChangeObserverTest {
    @Mock private lateinit var mockContext: Context

    @Mock private lateinit var mockContentResolver: ContentResolver

    private val testDispatcher = StandardTestDispatcher()

    private var capturedObserver: ContentObserver? = null
    private var registered: CountDownLatch = CountDownLatch(1)

    private val mockResolver: ContactResolver = mock()

    private val mockPermissionManager: PermissionManager = mock()

    private lateinit var observer: ContactChangeObserver

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        MockitoAnnotations.openMocks(this)
        whenever(mockContext.contentResolver).thenReturn(mockContentResolver)
        // Granted by default; the denied cases restub it. A mock never runs the constructor,
        // so the contactsPermission val is stubbed (it would read null).
        whenever(mockPermissionManager.contactsPermission).thenReturn(Manifest.permission.READ_CONTACTS)
        whenever(mockPermissionManager.isGranted(Manifest.permission.READ_CONTACTS)).thenReturn(true)
        registered = CountDownLatch(1)
        whenever(
            mockContentResolver.registerContentObserver(
                any<Uri>(),
                ArgumentMatchers.anyBoolean(),
                any<ContentObserver>(),
            ),
        ).thenAnswer { invocation ->
            capturedObserver = invocation.getArgument(2)
            registered.countDown()
            true
        }
        observer = ContactChangeObserver(mockContext, mockResolver, mockPermissionManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `collecting emits an initial value and invalidates the resolver cache`() {
        val events = collectFlowUntil { }

        assertEquals(1, events.size)
        verify(mockResolver, times(1)).invalidate()
    }

    @Test
    fun `onChange invalidates the cache and emits a change`() {
        val events =
            collectFlowUntil {
                capturedObserver!!.onChange(false, ContactsContract.Contacts.CONTENT_URI)
            }

        assertEquals(2, events.size)
        verify(mockResolver, times(2)).invalidate()
    }

    @Test
    fun `onChange with selfChange true still invalidates and emits`() {
        val events =
            collectFlowUntil {
                capturedObserver!!.onChange(true, ContactsContract.Contacts.CONTENT_URI)
            }

        assertEquals(2, events.size)
        verify(mockResolver, times(2)).invalidate()
    }

    @Test
    fun `onChange with null uri still invalidates and emits`() {
        val events =
            collectFlowUntil {
                capturedObserver!!.onChange(false, null)
            }

        assertEquals(2, events.size)
        verify(mockResolver, times(2)).invalidate()
    }

    @Test
    fun `the fallback tick emits on the poll interval without invalidating the resolver`() {
        observer.pollIntervalMs = 100
        val events = mutableListOf<Unit>()
        val scope = CoroutineScope(testDispatcher + Job())
        val job = scope.launch { observer.contactChanges.collect { events.add(it) } }
        testDispatcher.scheduler.runCurrent()
        assertTrue(registered.await(5, TimeUnit.SECONDS), "observer was not registered")
        testDispatcher.scheduler.advanceTimeBy(300)
        testDispatcher.scheduler.runCurrent()
        job.cancel()
        testDispatcher.scheduler.runCurrent()

        // The initial emission plus at least two ticks: the ticks re-check the enricher's
        // window cache but keep the resolver's scan on its own TTL.
        assertTrue(events.size >= 3, "expected the fallback tick to emit, got ${events.size}")
        verify(mockResolver, times(1)).invalidate()
    }

    @Test
    fun `cancelling the collection unregisters the observer`() {
        val scope = CoroutineScope(testDispatcher + Job())
        val job = scope.launch { observer.contactChanges.collect { } }
        testDispatcher.scheduler.runCurrent()
        assertTrue(registered.await(5, TimeUnit.SECONDS), "observer was not registered")
        job.cancel()
        testDispatcher.scheduler.runCurrent()

        verify(mockContentResolver, times(1)).unregisterContentObserver(
            any<ContentObserver>(),
        )
    }

    @Test
    fun `without the contacts permission the observer is not registered or unregistered`() {
        whenever(mockPermissionManager.isGranted(Manifest.permission.READ_CONTACTS)).thenReturn(false)

        val events = mutableListOf<Unit>()
        val scope = CoroutineScope(testDispatcher + Job())
        val job = scope.launch { observer.contactChanges.collect { events.add(it) } }
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, events.size)
        verify(mockResolver, times(1)).invalidate()
        verify(
            mockContentResolver,
            never(),
        ).registerContentObserver(
            any<Uri>(),
            ArgumentMatchers.anyBoolean(),
            any<ContentObserver>(),
        )
        job.cancel()
        testDispatcher.scheduler.runCurrent()
        verify(mockContentResolver, never()).unregisterContentObserver(any<ContentObserver>())
    }

    @Test
    fun `without the contacts permission the fallback tick still emits`() {
        whenever(mockPermissionManager.isGranted(Manifest.permission.READ_CONTACTS)).thenReturn(false)
        observer.pollIntervalMs = 100

        val events = mutableListOf<Unit>()
        val scope = CoroutineScope(testDispatcher + Job())
        val job = scope.launch { observer.contactChanges.collect { events.add(it) } }
        testDispatcher.scheduler.runCurrent()
        testDispatcher.scheduler.advanceTimeBy(300)
        testDispatcher.scheduler.runCurrent()
        job.cancel()
        testDispatcher.scheduler.runCurrent()

        // The initial emission plus at least two ticks.
        assertTrue(events.size >= 3, "expected the fallback tick to emit, got ${events.size}")
        verify(
            mockContentResolver,
            never(),
        ).registerContentObserver(
            any<Uri>(),
            ArgumentMatchers.anyBoolean(),
            any<ContentObserver>(),
        )
    }

    private fun collectFlowUntil(action: () -> Unit): List<Unit> {
        val events = mutableListOf<Unit>()
        val scope = CoroutineScope(testDispatcher + Job())
        val job = scope.launch { observer.contactChanges.collect { events.add(it) } }
        testDispatcher.scheduler.runCurrent()
        assertTrue(registered.await(5, TimeUnit.SECONDS), "observer was not registered")
        action()
        testDispatcher.scheduler.runCurrent()
        job.cancel()
        testDispatcher.scheduler.runCurrent()
        return events
    }
}
