package app.kotowski.sms2email.system

import android.content.Context
import android.telephony.TelephonyManager
import app.kotowski.sms2email.LogLevel
import app.kotowski.sms2email.util.LogBuffer
import app.kotowski.sms2email.util.Logger
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner.Silent::class)
class SimDetectorTest {
    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockTelephonyManager: TelephonyManager

    private lateinit var simDetector: SimDetector

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        given(mockContext.getSystemService(Context.TELEPHONY_SERVICE)).willReturn(mockTelephonyManager)
        given(mockContext.getSystemService(TelephonyManager::class.java)).willReturn(mockTelephonyManager)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `single SIM device returns isSingleSimDevice true`() {
        given(mockTelephonyManager.supportedModemCount).willReturn(1)
        given(mockTelephonyManager.phoneCount).willReturn(1)

        simDetector = SimDetector(mockContext)

        assert(simDetector.isSingleSimDevice())
        assert(simDetector.getSupportedModemCount() == 1)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `dual SIM device returns isSingleSimDevice false`() {
        given(mockTelephonyManager.supportedModemCount).willReturn(2)
        given(mockTelephonyManager.phoneCount).willReturn(2)

        simDetector = SimDetector(mockContext)

        assert(!simDetector.isSingleSimDevice())
        assert(simDetector.getSupportedModemCount() == 2)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `cache returns same value without calling API again`() {
        given(mockTelephonyManager.supportedModemCount).willReturn(2)
        given(mockTelephonyManager.phoneCount).willReturn(2)

        simDetector = SimDetector(mockContext)

        val firstCall = simDetector.getSupportedModemCount()
        val secondCall = simDetector.getSupportedModemCount()

        assert(firstCall == 2)
        assert(secondCall == 2)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `three SIM device is detected as not single SIM`() {
        given(mockTelephonyManager.supportedModemCount).willReturn(3)
        given(mockTelephonyManager.phoneCount).willReturn(3)

        simDetector = SimDetector(mockContext)

        assert(!simDetector.isSingleSimDevice())
        assert(simDetector.getSupportedModemCount() == 3)
    }

    @Test
    fun `returns 1 when TelephonyManager is null`() {
        given(mockContext.getSystemService(TelephonyManager::class.java)).willReturn(null)

        simDetector = SimDetector(mockContext)

        assert(simDetector.getSupportedModemCount() == 1)
        assert(simDetector.isSingleSimDevice())
    }

    @Test
    fun `exception defaults to 1`() {
        given(mockContext.getSystemService(TelephonyManager::class.java)).willThrow(RuntimeException("service unavailable"))

        Logger.setLevel(LogLevel.DEBUG)
        LogBuffer.clear()
        simDetector = SimDetector(mockContext)

        val result = simDetector.getSupportedModemCount()
        org.junit.Assert.assertEquals(1, result)
        val simEntries = LogBuffer.entries.filter { it.tag == "SimDetector" }
        org.junit.Assert.assertTrue(simEntries.isNotEmpty())
        org.junit.Assert.assertEquals("W", simEntries.last().level)
    }
}
