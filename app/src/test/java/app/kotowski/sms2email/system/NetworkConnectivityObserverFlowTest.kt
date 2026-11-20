package app.kotowski.sms2email.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import app.kotowski.sms2email.LogLevel
import app.kotowski.sms2email.util.LogBuffer
import app.kotowski.sms2email.util.Logger
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
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class NetworkConnectivityObserverFlowTest {
    @Mock private lateinit var mockContext: Context

    @Mock private lateinit var mockCm: ConnectivityManager

    private val testDispatcher = StandardTestDispatcher()

    private var capturedCallback: ConnectivityManager.NetworkCallback? = null
    private var registered: CountDownLatch = CountDownLatch(1)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        MockitoAnnotations.openMocks(this)
        whenever(mockContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(mockCm)
        registered = CountDownLatch(1)
        whenever(
            mockCm.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>()),
        ).thenAnswer { invocation ->
            capturedCallback = invocation.getArgument(1)
            registered.countDown()
            null
        }
        doAnswer { null }
            .whenever(mockCm)
            .unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    @Suppress("DEPRECATION")
    fun `onLost keeps Available when another network still has validated internet`() {
        val wifiNetwork = mock<Network>()
        val cellularNetwork = mock<Network>()
        val wifiCaps = mock<NetworkCapabilities>()
        val cellularCaps = mock<NetworkCapabilities>()
        stubCaps(wifiCaps, internet = true, validated = true)
        stubCaps(cellularCaps, internet = true, validated = true)
        whenever(mockCm.getNetworkCapabilities(wifiNetwork)).thenReturn(wifiCaps)
        whenever(mockCm.getNetworkCapabilities(cellularNetwork)).thenReturn(cellularCaps)
        whenever(mockCm.getAllNetworks()).thenReturn(arrayOf(wifiNetwork, cellularNetwork))

        val events =
            collectFlowUntil {
                whenever(mockCm.getNetworkCapabilities(wifiNetwork)).thenReturn(null)
                capturedCallback!!.onLost(wifiNetwork)
            }

        assertEquals(listOf(NetworkStatus.Available), events)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `onLost emits Unavailable when no network has validated internet`() {
        val wifiNetwork = mock<Network>()
        val wifiCaps = mock<NetworkCapabilities>()
        stubCaps(wifiCaps, internet = true, validated = true)
        whenever(mockCm.getNetworkCapabilities(wifiNetwork)).thenReturn(wifiCaps)
        whenever(mockCm.getAllNetworks()).thenReturn(arrayOf(wifiNetwork))

        val events =
            collectFlowUntil {
                whenever(mockCm.getNetworkCapabilities(wifiNetwork)).thenReturn(null)
                capturedCallback!!.onLost(wifiNetwork)
            }

        assertEquals(listOf(NetworkStatus.Available, NetworkStatus.Unavailable), events)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `onCapabilitiesChanged keeps Available when another network still has validated internet`() {
        val wifiNetwork = mock<Network>()
        val cellularNetwork = mock<Network>()
        val wifiCaps = mock<NetworkCapabilities>()
        val cellularCaps = mock<NetworkCapabilities>()
        stubCaps(wifiCaps, internet = true, validated = true)
        stubCaps(cellularCaps, internet = true, validated = true)
        whenever(mockCm.getNetworkCapabilities(wifiNetwork)).thenReturn(wifiCaps)
        whenever(mockCm.getNetworkCapabilities(cellularNetwork)).thenReturn(cellularCaps)
        whenever(mockCm.getAllNetworks()).thenReturn(arrayOf(wifiNetwork, cellularNetwork))

        val unvalidatedCaps = mock<NetworkCapabilities>()
        stubCaps(unvalidatedCaps, internet = true, validated = false)

        val events =
            collectFlowUntil {
                whenever(mockCm.getNetworkCapabilities(wifiNetwork)).thenReturn(unvalidatedCaps)
                capturedCallback!!.onCapabilitiesChanged(wifiNetwork, unvalidatedCaps)
            }

        assertEquals(listOf(NetworkStatus.Available), events)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `onCapabilitiesChanged emits Unavailable when no network has validated internet`() {
        val wifiNetwork = mock<Network>()
        val wifiCaps = mock<NetworkCapabilities>()
        stubCaps(wifiCaps, internet = true, validated = true)
        whenever(mockCm.getNetworkCapabilities(wifiNetwork)).thenReturn(wifiCaps)
        whenever(mockCm.getAllNetworks()).thenReturn(arrayOf(wifiNetwork))

        val unvalidatedCaps = mock<NetworkCapabilities>()
        stubCaps(unvalidatedCaps, internet = true, validated = false)

        val events =
            collectFlowUntil {
                whenever(mockCm.getNetworkCapabilities(wifiNetwork)).thenReturn(unvalidatedCaps)
                capturedCallback!!.onCapabilitiesChanged(wifiNetwork, unvalidatedCaps)
            }

        assertEquals(listOf(NetworkStatus.Available, NetworkStatus.Unavailable), events)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `onCapabilitiesChanged emits Available when network becomes validated`() {
        val wifiNetwork = mock<Network>()
        val wifiCaps = mock<NetworkCapabilities>()
        stubCaps(wifiCaps, internet = true, validated = false)
        whenever(mockCm.getNetworkCapabilities(wifiNetwork)).thenReturn(wifiCaps)
        whenever(mockCm.getAllNetworks()).thenReturn(arrayOf(wifiNetwork))

        val validatedCaps = mock<NetworkCapabilities>()
        stubCaps(validatedCaps, internet = true, validated = true)

        val events =
            collectFlowUntil {
                whenever(mockCm.getNetworkCapabilities(wifiNetwork)).thenReturn(validatedCaps)
                capturedCallback!!.onCapabilitiesChanged(wifiNetwork, validatedCaps)
            }

        assertEquals(listOf(NetworkStatus.Unavailable, NetworkStatus.Available), events)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `onAvailable emits Available when a validated network appears`() {
        val wifiNetwork = mock<Network>()
        val cellularNetwork = mock<Network>()
        val cellularCaps = mock<NetworkCapabilities>()
        stubCaps(cellularCaps, internet = true, validated = false)
        whenever(mockCm.getNetworkCapabilities(cellularNetwork)).thenReturn(cellularCaps)
        whenever(mockCm.getAllNetworks()).thenReturn(arrayOf(cellularNetwork))

        val wifiCaps = mock<NetworkCapabilities>()
        stubCaps(wifiCaps, internet = true, validated = true)

        val events =
            collectFlowUntil {
                whenever(mockCm.getNetworkCapabilities(wifiNetwork)).thenReturn(wifiCaps)
                whenever(mockCm.getAllNetworks()).thenReturn(arrayOf(wifiNetwork, cellularNetwork))
                capturedCallback!!.onAvailable(wifiNetwork)
            }

        assertEquals(listOf(NetworkStatus.Unavailable, NetworkStatus.Available), events)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `initial status is Available when another network has validated internet`() {
        val defaultNetwork = mock<Network>()
        val secondaryNetwork = mock<Network>()
        val defaultCaps = mock<NetworkCapabilities>()
        val secondaryCaps = mock<NetworkCapabilities>()
        stubCaps(defaultCaps, internet = true, validated = false)
        stubCaps(secondaryCaps, internet = true, validated = true)
        whenever(mockCm.getNetworkCapabilities(defaultNetwork)).thenReturn(defaultCaps)
        whenever(mockCm.getNetworkCapabilities(secondaryNetwork)).thenReturn(secondaryCaps)
        whenever(mockCm.getAllNetworks()).thenReturn(arrayOf(defaultNetwork, secondaryNetwork))

        val events =
            collectFlowUntil { }

        assertEquals(listOf(NetworkStatus.Available), events)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `onAvailable emits Unavailable when no network has validated internet`() {
        val wifiNetwork = mock<Network>()
        val wifiCaps = mock<NetworkCapabilities>()
        stubCaps(wifiCaps, internet = true, validated = false)
        whenever(mockCm.getNetworkCapabilities(wifiNetwork)).thenReturn(wifiCaps)
        whenever(mockCm.getAllNetworks()).thenReturn(arrayOf(wifiNetwork))

        val events =
            collectFlowUntil {
                capturedCallback!!.onAvailable(wifiNetwork)
            }

        assertEquals(listOf(NetworkStatus.Unavailable), events)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `registration failure still emits the current status when evaluation succeeds`() {
        whenever(
            mockCm.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>()),
        ).thenThrow(SecurityException("denied"))
        val network = mock<Network>()
        val caps = mock<NetworkCapabilities>()
        stubCaps(caps, internet = true, validated = true)
        whenever(mockCm.getNetworkCapabilities(network)).thenReturn(caps)
        whenever(mockCm.getAllNetworks()).thenReturn(arrayOf(network))

        val observer = NetworkConnectivityObserver(mockContext)
        val events = mutableListOf<NetworkStatus>()
        Logger.setLevel(LogLevel.DEBUG)
        LogBuffer.clear()
        val scope = CoroutineScope(testDispatcher + Job())
        val job = scope.launch { observer.networkStatus.collect { events.add(it) } }
        testDispatcher.scheduler.runCurrent()

        assertTrue(job.isActive, "the collector must survive a registration failure")
        assertEquals(listOf(NetworkStatus.Available), events)
        val entry = LogBuffer.entries.last()
        assertEquals("W", entry.level)
        assertEquals("NetworkConnectivityObserver", entry.tag)
        assertTrue(entry.message.startsWith("registerNetworkCallback failed"))
        assertTrue(entry.message.contains("SecurityException: denied"))
        job.cancel()
        testDispatcher.scheduler.runCurrent()
        verify(mockCm, never()).unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>())
    }

    @Test
    @Suppress("DEPRECATION")
    fun `registration failure with a dead service emits nothing and keeps the collector alive`() {
        whenever(
            mockCm.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>()),
        ).thenThrow(SecurityException("denied"))
        whenever(mockCm.getAllNetworks()).thenThrow(SecurityException("service dead"))

        val observer = NetworkConnectivityObserver(mockContext)
        val events = mutableListOf<NetworkStatus>()
        val scope = CoroutineScope(testDispatcher + Job())
        val job = scope.launch { observer.networkStatus.collect { events.add(it) } }
        testDispatcher.scheduler.runCurrent()

        assertTrue(job.isActive, "the collector must survive a registration failure")
        assertTrue(events.isEmpty(), "no evaluation, no emission")
        job.cancel()
        testDispatcher.scheduler.runCurrent()
        verify(mockCm, never()).unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>())
    }

    @Test
    @Suppress("DEPRECATION")
    fun `a throwing evaluation emits nothing and the collector keeps the last known state`() {
        val network = mock<Network>()
        val caps = mock<NetworkCapabilities>()
        stubCaps(caps, internet = true, validated = true)
        whenever(mockCm.getNetworkCapabilities(network)).thenReturn(caps)
        whenever(mockCm.getAllNetworks()).thenReturn(arrayOf(network))

        val observer = NetworkConnectivityObserver(mockContext)
        val events = mutableListOf<NetworkStatus>()
        val scope = CoroutineScope(testDispatcher + Job())
        val job = scope.launch { observer.networkStatus.collect { events.add(it) } }
        testDispatcher.scheduler.runCurrent()
        assertTrue(registered.await(5, TimeUnit.SECONDS), "network callback was not registered")

        whenever(mockCm.getAllNetworks()).thenThrow(SecurityException("service dead"))
        capturedCallback!!.onLost(network)
        testDispatcher.scheduler.runCurrent()

        assertTrue(job.isActive, "the collector must survive an evaluation failure")
        assertEquals(listOf(NetworkStatus.Available), events)
        job.cancel()
        testDispatcher.scheduler.runCurrent()
    }

    @Test
    @Suppress("DEPRECATION")
    fun `evaluation recovers after the service comes back`() {
        val network = mock<Network>()
        val caps = mock<NetworkCapabilities>()
        stubCaps(caps, internet = true, validated = true)
        whenever(mockCm.getNetworkCapabilities(network)).thenReturn(caps)
        whenever(mockCm.getAllNetworks()).thenThrow(SecurityException("service dead"))

        val observer = NetworkConnectivityObserver(mockContext)
        val events = mutableListOf<NetworkStatus>()
        val scope = CoroutineScope(testDispatcher + Job())
        val job = scope.launch { observer.networkStatus.collect { events.add(it) } }
        testDispatcher.scheduler.runCurrent()
        assertTrue(registered.await(5, TimeUnit.SECONDS), "network callback was not registered")

        capturedCallback!!.onLost(network)
        testDispatcher.scheduler.runCurrent()
        assertTrue(events.isEmpty(), "no evaluation, no emission")

        doReturn(arrayOf(network)).whenever(mockCm).getAllNetworks()
        capturedCallback!!.onAvailable(network)
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf(NetworkStatus.Available), events)
        job.cancel()
        testDispatcher.scheduler.runCurrent()
    }

    @Test
    @Suppress("DEPRECATION")
    fun `unregister failure on close does not crash the collector`() {
        doThrow(SecurityException("service dead"))
            .whenever(mockCm)
            .unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>())
        whenever(mockCm.getAllNetworks()).thenReturn(emptyArray())

        val observer = NetworkConnectivityObserver(mockContext)
        val events = mutableListOf<NetworkStatus>()
        val scope = CoroutineScope(testDispatcher + Job())
        val job = scope.launch { observer.networkStatus.collect { events.add(it) } }
        testDispatcher.scheduler.runCurrent()
        assertTrue(registered.await(5, TimeUnit.SECONDS), "network callback was not registered")

        job.cancel()
        testDispatcher.scheduler.runCurrent()

        assertTrue(job.isCompleted)
        assertEquals(listOf(NetworkStatus.Unavailable), events)
    }

    private fun stubCaps(
        caps: NetworkCapabilities,
        internet: Boolean,
        validated: Boolean,
    ) {
        whenever(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(internet)
        whenever(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(validated)
    }

    private fun collectFlowUntil(action: () -> Unit): List<NetworkStatus> {
        val observer = NetworkConnectivityObserver(mockContext)
        val events = mutableListOf<NetworkStatus>()
        val scope = CoroutineScope(testDispatcher + Job())
        val job = scope.launch { observer.networkStatus.collect { events.add(it) } }
        testDispatcher.scheduler.runCurrent()
        assertTrue(registered.await(5, TimeUnit.SECONDS), "network callback was not registered")
        action()
        testDispatcher.scheduler.runCurrent()
        job.cancel()
        testDispatcher.scheduler.runCurrent()
        return events
    }
}
