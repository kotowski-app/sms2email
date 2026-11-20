package app.kotowski.sms2email.system

import android.content.Context
import android.os.Looper
import android.telephony.SubscriptionManager
import app.kotowski.sms2email.util.AndroidVersion
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
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Mirrors NetworkConnectivityObserverFlowTest: a mocked SubscriptionManager captures the
// listener so onSubscriptionsChanged can be driven directly, and the resolver is a mock so
// the invalidate() calls (the cache hook) are asserted.
@OptIn(ExperimentalCoroutinesApi::class)
@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class SimChangeObserverTest {
    @Mock private lateinit var mockContext: Context

    @Mock private lateinit var mockSubscriptionManager: SubscriptionManager

    private val testDispatcher = StandardTestDispatcher()

    private var capturedListener: SubscriptionManager.OnSubscriptionsChangedListener? = null
    private var registered: CountDownLatch = CountDownLatch(1)

    private val mockResolver: SimNameResolver = mock()

    private lateinit var observer: SimChangeObserver

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        MockitoAnnotations.openMocks(this)
        whenever(mockContext.getSystemService(SubscriptionManager::class.java)).thenReturn(mockSubscriptionManager)
        registered = CountDownLatch(1)
        // The Executor overload exists only on API 30+; referencing it at all (even for
        // stubbing) is a NoSuchMethodError below, so the stub is installed only there.
        if (AndroidVersion.is11Plus()) {
            whenever(
                mockSubscriptionManager.addOnSubscriptionsChangedListener(
                    any<Executor>(),
                    any<SubscriptionManager.OnSubscriptionsChangedListener>(),
                ),
            ).thenAnswer { invocation ->
                capturedListener = invocation.getArgument(1)
                registered.countDown()
                null
            }
        }
        doAnswer { null }
            .whenever(mockSubscriptionManager)
            .removeOnSubscriptionsChangedListener(any<SubscriptionManager.OnSubscriptionsChangedListener>())
        observer = SimChangeObserver(mockContext, mockResolver)
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
    fun `onSubscriptionsChanged invalidates the cache and emits a change`() {
        val events =
            collectFlowUntil {
                capturedListener!!.onSubscriptionsChanged()
            }

        assertEquals(2, events.size)
        verify(mockResolver, times(2)).invalidate()
    }

    @Test
    fun `the fallback tick re-invalidates and emits on the poll interval`() {
        observer.pollIntervalMs = 100
        val events = mutableListOf<Unit>()
        val scope = CoroutineScope(testDispatcher + Job())
        val job = scope.launch { observer.simChanges.collect { events.add(it) } }
        testDispatcher.scheduler.runCurrent()
        assertTrue(registered.await(5, TimeUnit.SECONDS), "listener was not registered")
        testDispatcher.scheduler.advanceTimeBy(300)
        testDispatcher.scheduler.runCurrent()
        job.cancel()
        testDispatcher.scheduler.runCurrent()

        // The initial emission plus at least one tick (the tick re-invalidates + emits).
        assertTrue(events.size >= 2, "expected the fallback tick to emit, got ${events.size}")
        verify(mockResolver, atLeast(2)).invalidate()
    }

    @Test
    fun `cancelling the collection unregisters the listener`() {
        val scope = CoroutineScope(testDispatcher + Job())
        val job = scope.launch { observer.simChanges.collect { } }
        testDispatcher.scheduler.runCurrent()
        assertTrue(registered.await(5, TimeUnit.SECONDS), "listener was not registered")
        job.cancel()
        testDispatcher.scheduler.runCurrent()

        verify(mockSubscriptionManager, times(1)).removeOnSubscriptionsChangedListener(
            any<SubscriptionManager.OnSubscriptionsChangedListener>(),
        )
    }

    @Test
    @Config(sdk = [29])
    @Suppress("DEPRECATION")
    fun `below R the listener is registered through the no-arg overload on the main thread`() {
        // The Executor overload was added in API 30; on API 29 registration must be posted
        // to the main looper and go through the no-arg overload instead.
        val noArgRegistered = CountDownLatch(1)
        whenever(
            mockSubscriptionManager.addOnSubscriptionsChangedListener(
                any<SubscriptionManager.OnSubscriptionsChangedListener>(),
            ),
        ).thenAnswer { invocation ->
            capturedListener = invocation.getArgument(0)
            noArgRegistered.countDown()
            null
        }
        val events = mutableListOf<Unit>()
        val scope = CoroutineScope(testDispatcher + Job())
        val job = scope.launch { observer.simChanges.collect { events.add(it) } }
        testDispatcher.scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(noArgRegistered.await(5, TimeUnit.SECONDS), "the no-arg overload was not used")
        job.cancel()
        testDispatcher.scheduler.runCurrent()
        assertEquals(1, events.size)
        // A call to the Executor overload (added in API 30) would be a NoSuchMethodError
        // on this SDK, so surviving the registration above proves it was not taken.
    }

    @Test
    @Config(sdk = [29])
    @Suppress("DEPRECATION")
    fun `below R the removal is posted to the main looper so it runs in order after the add`() {
        val noArgRegistered = CountDownLatch(1)
        whenever(
            mockSubscriptionManager.addOnSubscriptionsChangedListener(
                any<SubscriptionManager.OnSubscriptionsChangedListener>(),
            ),
        ).thenAnswer { invocation ->
            capturedListener = invocation.getArgument(0)
            noArgRegistered.countDown()
            null
        }
        val scope = CoroutineScope(testDispatcher + Job())
        val job = scope.launch { observer.simChanges.collect { } }
        testDispatcher.scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(noArgRegistered.await(5, TimeUnit.SECONDS), "the no-arg overload was not used")

        job.cancel()
        testDispatcher.scheduler.runCurrent()
        // The removal is now posted to the main looper (mirroring the registration), so it has
        // not run yet before the looper is idled — this is what keeps it in order after the add.
        verify(mockSubscriptionManager, times(0)).removeOnSubscriptionsChangedListener(
            any<SubscriptionManager.OnSubscriptionsChangedListener>(),
        )
        shadowOf(Looper.getMainLooper()).idle()
        verify(mockSubscriptionManager, times(1)).removeOnSubscriptionsChangedListener(
            any<SubscriptionManager.OnSubscriptionsChangedListener>(),
        )
    }

    @Test
    fun `null SubscriptionManager skips registration and still emits initial`() {
        whenever(mockContext.getSystemService(SubscriptionManager::class.java)).thenReturn(null)
        val localObserver = SimChangeObserver(mockContext, mockResolver)
        val events = collectFlowFor(localObserver) { }

        assertEquals(1, events.size)
        verify(mockResolver, times(1)).invalidate()
    }

    private fun collectFlowUntil(action: () -> Unit): List<Unit> {
        val events = mutableListOf<Unit>()
        val scope = CoroutineScope(testDispatcher + Job())
        val job = scope.launch { observer.simChanges.collect { events.add(it) } }
        testDispatcher.scheduler.runCurrent()
        assertTrue(registered.await(5, TimeUnit.SECONDS), "listener was not registered")
        action()
        testDispatcher.scheduler.runCurrent()
        job.cancel()
        testDispatcher.scheduler.runCurrent()
        return events
    }

    private fun collectFlowFor(
        obs: SimChangeObserver,
        action: () -> Unit,
    ): List<Unit> {
        val events = mutableListOf<Unit>()
        val scope = CoroutineScope(testDispatcher + Job())
        val job = scope.launch { obs.simChanges.collect { events.add(it) } }
        testDispatcher.scheduler.runCurrent()
        action()
        testDispatcher.scheduler.runCurrent()
        job.cancel()
        testDispatcher.scheduler.runCurrent()
        return events
    }
}
