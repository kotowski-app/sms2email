package app.kotowski.sms2email.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkConnectivityObserverTest {
    @Mock private lateinit var mockContext: Context

    @Mock private lateinit var mockCm: ConnectivityManager

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        whenever(mockContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(mockCm)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `isConnectedCurrently returns true when a network has internet and validated`() {
        val network = mock<Network>()
        val caps = mock<NetworkCapabilities>()
        stubCaps(caps, internet = true, validated = true)
        whenever(mockCm.getAllNetworks()).thenReturn(arrayOf(network))
        whenever(mockCm.getNetworkCapabilities(network)).thenReturn(caps)

        val observer = NetworkConnectivityObserver(mockContext)
        assertTrue(observer.isConnectedCurrently())
    }

    @Test
    @Suppress("DEPRECATION")
    fun `isConnectedCurrently returns true when another network has validated internet`() {
        val unvalidatedNetwork = mock<Network>()
        val validatedNetwork = mock<Network>()
        val unvalidatedCaps = mock<NetworkCapabilities>()
        val validatedCaps = mock<NetworkCapabilities>()
        stubCaps(unvalidatedCaps, internet = true, validated = false)
        stubCaps(validatedCaps, internet = true, validated = true)
        whenever(mockCm.getAllNetworks()).thenReturn(arrayOf(unvalidatedNetwork, validatedNetwork))
        whenever(mockCm.getNetworkCapabilities(unvalidatedNetwork)).thenReturn(unvalidatedCaps)
        whenever(mockCm.getNetworkCapabilities(validatedNetwork)).thenReturn(validatedCaps)

        val observer = NetworkConnectivityObserver(mockContext)
        assertTrue(observer.isConnectedCurrently())
    }

    @Test
    @Suppress("DEPRECATION")
    fun `isConnectedCurrently returns false when there are no networks`() {
        whenever(mockCm.getAllNetworks()).thenReturn(emptyArray())

        val observer = NetworkConnectivityObserver(mockContext)
        assertFalse(observer.isConnectedCurrently())
    }

    @Test
    @Suppress("DEPRECATION")
    fun `isConnectedCurrently returns false when no capabilities`() {
        val network = mock<Network>()
        whenever(mockCm.getAllNetworks()).thenReturn(arrayOf(network))
        whenever(mockCm.getNetworkCapabilities(network)).thenReturn(null)

        val observer = NetworkConnectivityObserver(mockContext)
        assertFalse(observer.isConnectedCurrently())
    }

    @Test
    @Suppress("DEPRECATION")
    fun `isConnectedCurrently returns false when no internet capability`() {
        val network = mock<Network>()
        val caps = mock<NetworkCapabilities>()
        stubCaps(caps, internet = false, validated = true)
        whenever(mockCm.getAllNetworks()).thenReturn(arrayOf(network))
        whenever(mockCm.getNetworkCapabilities(network)).thenReturn(caps)

        val observer = NetworkConnectivityObserver(mockContext)
        assertFalse(observer.isConnectedCurrently())
    }

    @Test
    @Suppress("DEPRECATION")
    fun `isConnectedCurrently returns false when not validated`() {
        val network = mock<Network>()
        val caps = mock<NetworkCapabilities>()
        stubCaps(caps, internet = true, validated = false)
        whenever(mockCm.getAllNetworks()).thenReturn(arrayOf(network))
        whenever(mockCm.getNetworkCapabilities(network)).thenReturn(caps)

        val observer = NetworkConnectivityObserver(mockContext)
        assertFalse(observer.isConnectedCurrently())
    }

    @Test
    @Suppress("DEPRECATION")
    fun `isConnectedCurrently returns true when the connectivity service throws`() {
        whenever(mockCm.getAllNetworks()).thenThrow(SecurityException("service dead"))

        val observer = NetworkConnectivityObserver(mockContext)
        assertTrue(observer.isConnectedCurrently())
    }

    @Test
    @Suppress("DEPRECATION")
    fun `isConnectedCurrently rethrows cancellation`() {
        whenever(mockCm.getAllNetworks()).thenThrow(CancellationException())

        val observer = NetworkConnectivityObserver(mockContext)
        assertFailsWith<CancellationException> { observer.isConnectedCurrently() }
    }

    private fun stubCaps(
        caps: NetworkCapabilities,
        internet: Boolean,
        validated: Boolean,
    ) {
        whenever(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(internet)
        whenever(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(validated)
    }
}
