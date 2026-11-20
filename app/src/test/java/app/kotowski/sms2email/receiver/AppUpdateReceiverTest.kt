package app.kotowski.sms2email.receiver

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
import kotlin.test.assertTrue

// The receiver's only job is to start the app process after an in-place update (see
// AppUpdateReceiver); these tests pin that onReceive stays side-effect free and only logs.
@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class AppUpdateReceiverTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        // The default threshold (ERROR) would filter the receiver's info line.
        Logger.setLevel(LogLevel.DEBUG)
    }

    private fun lastAppUpdateLog(): String? = LogBuffer.entries.lastOrNull { it.tag == "AppUpdateReceiver" }?.message

    @Test
    fun `onReceive only logs for the package-replaced action`() {
        val receiver = AppUpdateReceiver()

        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertTrue(lastAppUpdateLog()?.contains("MY_PACKAGE_REPLACED") == true)
    }

    @Test
    fun `onReceive only logs for unrelated actions too, the manifest filter is the gate`() {
        val receiver = AppUpdateReceiver()

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertTrue(lastAppUpdateLog()?.contains("BOOT_COMPLETED") == true)
    }
}
