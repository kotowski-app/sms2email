package app.kotowski.sms2email.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Debug
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebuggerDetectorTest {
    @Test
    fun `isBeingDebugged returns true when debugger is connected`() {
        val context = mock<Context>()
        Mockito.mockStatic(Debug::class.java).use { debug ->
            debug.`when`<Boolean> { Debug.isDebuggerConnected() }.thenReturn(true)
            debug.`when`<Boolean> { Debug.waitingForDebugger() }.thenReturn(false)

            val result = DebuggerDetector.isBeingDebugged(context)
            assertTrue(result, "Should detect attached debugger")
        }
    }

    @Test
    fun `isBeingDebugged returns true when waiting for debugger`() {
        val context = mock<Context>()
        Mockito.mockStatic(Debug::class.java).use { debug ->
            debug.`when`<Boolean> { Debug.isDebuggerConnected() }.thenReturn(false)
            debug.`when`<Boolean> { Debug.waitingForDebugger() }.thenReturn(true)

            val result = DebuggerDetector.isBeingDebugged(context)
            assertTrue(result, "Should detect process waiting for debugger")
        }
    }

    @Test
    fun `isBeingDebugged returns false when app is debuggable but no debugger attached`() {
        val context = mock<Context>()
        whenever(context.applicationInfo).thenReturn(
            mock<ApplicationInfo>().apply {
                flags = ApplicationInfo.FLAG_DEBUGGABLE
            },
        )

        val result = DebuggerDetector.isBeingDebugged(context)
        assertFalse(result, "Debuggable build without an attached debugger is not being debugged")
    }

    @Test
    fun `isBeingDebugged returns false when app is not debuggable and no debugger attached`() {
        val context = mock<Context>()
        whenever(context.applicationInfo).thenReturn(
            mock<ApplicationInfo>().apply {
                flags = 0
            },
        )

        val result = DebuggerDetector.isBeingDebugged(context)
        assertFalse(result, "Should not detect as debugged when no debugger attached")
    }

    @Test
    fun `isBeingDebugged returns false when other flags set but no debugger attached`() {
        val context = mock<Context>()
        whenever(context.applicationInfo).thenReturn(
            mock<ApplicationInfo>().apply {
                flags = ApplicationInfo.FLAG_HAS_CODE or ApplicationInfo.FLAG_SYSTEM
            },
        )

        val result = DebuggerDetector.isBeingDebugged(context)
        assertFalse(result, "Should not detect as debugged when only non-debug flags set")
    }

    @Test
    fun `isBeingDebugged returns false when Debug API throws`() {
        val context = mock<Context>()
        Mockito.mockStatic(Debug::class.java).use { debug ->
            debug.`when`<Boolean> { Debug.isDebuggerConnected() }.thenThrow(RuntimeException("security exception"))
            debug.`when`<Boolean> { Debug.waitingForDebugger() }.thenReturn(false)

            val result = DebuggerDetector.isBeingDebugged(context)
            assertFalse(result, "Should degrade to false when Debug API throws")
        }
    }

    @Test
    fun `isBeingDebugged returns false when waitingForDebugger throws`() {
        val context = mock<Context>()
        Mockito.mockStatic(Debug::class.java).use { debug ->
            debug.`when`<Boolean> { Debug.isDebuggerConnected() }.thenReturn(false)
            debug.`when`<Boolean> { Debug.waitingForDebugger() }.thenThrow(SecurityException("not allowed"))

            val result = DebuggerDetector.isBeingDebugged(context)
            assertFalse(result, "Should degrade to false when waitingForDebugger throws")
        }
    }
}
