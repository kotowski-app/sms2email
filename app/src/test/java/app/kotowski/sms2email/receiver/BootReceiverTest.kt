package app.kotowski.sms2email.receiver

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import app.kotowski.sms2email.LogLevel
import app.kotowski.sms2email.util.LogBuffer
import app.kotowski.sms2email.util.Logger
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The receiver's only job is to start the app process at boot (see BootReceiver); these
// tests pin that onReceive stays side-effect free and only logs the received action.
@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        // The default threshold (ERROR) would filter the receiver's info line.
        Logger.setLevel(LogLevel.DEBUG)
    }

    private fun lastBootLog(): String? = LogBuffer.entries.lastOrNull { it.tag == "BootReceiver" }?.message

    @Test
    fun `onReceive only logs for the boot actions`() {
        val receiver = BootReceiver()

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(lastBootLog()?.contains("BOOT_COMPLETED") == true)

        receiver.onReceive(context, Intent("android.intent.action.QUICKBOOT_POWERON"))
        assertTrue(lastBootLog()?.contains("QUICKBOOT_POWERON") == true)
    }

    @Test
    fun `onReceive only logs for unrelated actions too, the manifest filter is the gate`() {
        val receiver = BootReceiver()

        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertTrue(lastBootLog()?.contains("MY_PACKAGE_REPLACED") == true)
    }

    @Test
    fun `manifest guards the receiver with RECEIVE_BOOT_COMPLETED so unprivileged spoofs are dropped`() {
        val info =
            context.packageManager.getReceiverInfo(ComponentName(context, BootReceiver::class.java), 0)

        assertEquals("android.permission.RECEIVE_BOOT_COMPLETED", info.permission)
    }
}
