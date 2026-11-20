package app.kotowski.sms2email.system

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class NetworkUtilTest {
    private lateinit var mockContext: android.content.Context
    private lateinit var mockCm: ConnectivityManager
    private lateinit var mockNetwork: Network
    private lateinit var mockCaps: NetworkCapabilities

    @Before
    fun setup() {
        mockContext = mock()
        mockCm = mock()
        mockNetwork = mock()
        mockCaps = mock()
        whenever(mockContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)).thenReturn(mockCm)
        whenever(mockCm.activeNetwork).thenReturn(mockNetwork)
        whenever(mockCm.getNetworkCapabilities(mockNetwork)).thenReturn(mockCaps)
    }

    @Test
    fun hasReliableInternet_internet_and_validated_returns_true() {
        whenever(mockCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        whenever(mockCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
        assertTrue(NetworkUtil.hasReliableInternet(mockContext))
    }

    @Test
    fun hasReliableInternet_no_internet_returns_false() {
        whenever(mockCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(false)
        assertFalse(NetworkUtil.hasReliableInternet(mockContext))
    }

    @Test
    fun hasReliableInternet_no_validated_returns_false() {
        whenever(mockCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        whenever(mockCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(false)
        assertFalse(NetworkUtil.hasReliableInternet(mockContext))
    }

    @Test
    fun hasReliableInternet_no_active_network_returns_false() {
        whenever(mockCm.activeNetwork).thenReturn(null)
        assertFalse(NetworkUtil.hasReliableInternet(mockContext))
    }

    @Test
    fun hasReliableInternet_no_capabilities_returns_false() {
        whenever(mockCm.getNetworkCapabilities(mockNetwork)).thenReturn(null)
        assertFalse(NetworkUtil.hasReliableInternet(mockContext))
    }

    @Test
    fun isReliableInternet_returns_true_when_both_capabilities_present() {
        whenever(mockCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        whenever(mockCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
        assertTrue(mockCaps.isReliableInternet())
    }

    @Test
    fun isReliableInternet_returns_false_when_internet_missing() {
        whenever(mockCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(false)
        whenever(mockCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
        assertFalse(mockCaps.isReliableInternet())
    }

    @Test
    fun isReliableInternet_returns_false_when_validated_missing() {
        whenever(mockCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        whenever(mockCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(false)
        assertFalse(mockCaps.isReliableInternet())
    }

    @Test
    fun checkNetwork_returns_null_when_internet_validated() {
        whenever(mockCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        whenever(mockCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
        whenever(mockContext.getString(any<Int>())).thenReturn("error")

        val result = NetworkUtil.checkNetwork(mockContext)
        assertNull(result)
    }

    @Test
    fun checkNetwork_returns_message_no_internet() {
        whenever(mockCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(false)
        whenever(mockContext.getString(any<Int>())).thenReturn("No internet")

        val result = NetworkUtil.checkNetwork(mockContext)
        assertEquals("No internet", result)
    }

    @Test
    fun checkNetwork_returns_message_no_validated() {
        whenever(mockCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        whenever(mockCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(false)
        whenever(mockContext.getString(any<Int>())).thenReturn("No validated")

        val result = NetworkUtil.checkNetwork(mockContext)
        assertEquals("No validated", result)
    }

    @Test
    fun checkNetwork_returns_message_no_active_network() {
        whenever(mockCm.activeNetwork).thenReturn(null)
        whenever(mockContext.getString(any<Int>())).thenReturn("No network")

        val result = NetworkUtil.checkNetwork(mockContext)
        assertEquals("No network", result)
    }
}
