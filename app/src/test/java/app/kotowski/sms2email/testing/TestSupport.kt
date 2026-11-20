package app.kotowski.sms2email.testing

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.work.impl.WorkManagerImpl
import app.kotowski.sms2email.BuildConfig
import app.kotowski.sms2email.data.database.Sms
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.SmsUiModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assume
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

// createComposeRule hosts the content in androidx.activity.ComponentActivity, which only the
// debug unit-test manifest declares (ui-test-manifest is a debug-only dependency); in other
// variants the wrapper rule skips the test instead of launching the missing activity.
class SkipInReleaseRule(
    private val delegate: ComposeContentTestRule,
) : ComposeContentTestRule by delegate {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        if (BuildConfig.DEBUG) {
            delegate.apply(base, description)
        } else {
            object : Statement() {
                override fun evaluate() {
                    Assume.assumeTrue("needs the debug-only ui-test-manifest ComponentActivity", false)
                }
            }
        }
}

// The real-time bound of [pumpUntil]: the mocked enrich work is trivial, so five real
// seconds is a large margin for a loaded CI box.
private const val PUMP_UNTIL_TIMEOUT_MS = 5_000L

// Pumps the scheduler (bounded in real time) until `condition` holds. The window
// pipeline enriches via real Dispatchers.IO, so settling crosses real threads and is
// not pure virtual time: the loop yields to them and fails loudly on timeout. Call it
// on the test thread (e.g. inside withMockedToast blocks, which are not coroutine
// bodies) with a StandardTestDispatcher Main, so the collectors resume here and the
// thread-local static mocks can intercept.
@OptIn(ExperimentalCoroutinesApi::class)
fun pumpUntil(
    scheduler: TestCoroutineScheduler,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + PUMP_UNTIL_TIMEOUT_MS
    while (!condition() && System.currentTimeMillis() < deadline) {
        scheduler.advanceTimeBy(10)
        scheduler.runCurrent()
        Thread.sleep(5)
    }
    check(condition()) { "pumpUntil: condition not met within ${PUMP_UNTIL_TIMEOUT_MS} ms" }
}

fun stubInternetEnabled(
    connectivityManager: ConnectivityManager,
    network: Network,
    networkCapabilities: NetworkCapabilities,
) {
    whenever(connectivityManager.activeNetwork).thenReturn(network)
    whenever(connectivityManager.getNetworkCapabilities(network)).thenReturn(networkCapabilities)
    whenever(networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
    whenever(networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
}

// The settings VM resolves the subject-preview strings through the app context,
// so a mocked context must answer getString; this delegates it to the real
// application so the tests see the real templates. Each vararg arity is stubbed
// explicitly: a single any() in the vararg position matches exactly that arity,
// and an unmatched overload would return null into a non-null String.
fun stubContextGetString(
    mock: Context,
    real: Context,
) {
    val delegate: (InvocationOnMock) -> String = { inv ->
        real.getString(inv.getArgument(0), *inv.arguments.drop(1).toTypedArray())
    }
    whenever(mock.getString(any<Int>())).thenAnswer { real.getString(it.getArgument(0)) }
    whenever(mock.getString(any<Int>(), any())).thenAnswer { delegate(it) }
    whenever(mock.getString(any<Int>(), any(), any())).thenAnswer { delegate(it) }
    whenever(mock.getString(any<Int>(), any(), any(), any())).thenAnswer { delegate(it) }
}

// work-runtime 2.12.0 rewrote WorkManager in Kotlin: getInstance is a companion-object
// function, so Kotlin callers resolve to the Companion instance method, which
// Mockito.mockStatic(WorkManager::class.java) cannot intercept (it only instruments the
// Java-facing static forwarders). WorkManagerImpl.setDelegate is the official hook: while
// a delegate is installed, both getInstance() and getInstance(context) return it.
// Always pair install with uninstall (the delegate is JVM-global state).
object WorkManagerDelegate {
    fun install(delegate: WorkManagerImpl) {
        WorkManagerImpl.setDelegate(delegate)
    }

    fun uninstall() {
        WorkManagerImpl.setDelegate(null)
    }
}

fun <T> withMockedToast(block: (MockedStatic<Toast>) -> T): T =
    Mockito.mockStatic(Toast::class.java).use { staticToast ->
        staticToast.`when`<Toast> { Toast.makeText(any<Context>(), any<Int>(), any<Int>()) }.thenReturn(mock())
        // The resolved-text overload (e.g. showTestResultToast): ToastUtil.show(context, text)
        // compiles to makeText(Context, CharSequence, int).
        staticToast.`when`<Toast> { Toast.makeText(any<Context>(), any<CharSequence>(), any<Int>()) }.thenReturn(mock())
        block(staticToast)
    }

fun sms(
    id: Long,
    status: SmsStatus,
    messageBody: String = "m$id",
    phoneNumber: String = "+$id",
): Sms =
    Sms(
        id = id,
        messageBody = messageBody,
        phoneNumber = phoneNumber,
        status = status,
    )

// The UI-layer twin of [sms]: the DAO projection the list/detail tests feed the
// screens (the entity fixture stays for the work/data tests).
fun smsModel(
    id: Long,
    status: SmsStatus,
    messageBody: String = "m$id",
    phoneNumber: String = "+$id",
    simId: Int = -1,
    statusDetails: String? = null,
    timeReceived: Long = 1_700_000_000_000L,
    timeLastSendAttempt: Long? = null,
    timeNextSendAttempt: Long? = null,
    retryCount: Int = 0,
): SmsUiModel =
    SmsUiModel(
        id = id,
        phoneNumber = phoneNumber,
        messageBody = messageBody,
        simId = simId,
        status = status,
        statusDetails = statusDetails,
        timeReceived = timeReceived,
        timeLastSendAttempt = timeLastSendAttempt,
        timeNextSendAttempt = timeNextSendAttempt,
        retryCount = retryCount,
    )

// The entity the send machinery reads as its UI projection twin.
fun Sms.toUiModel(): SmsUiModel =
    smsModel(
        id = id,
        status = status,
        messageBody = messageBody,
        phoneNumber = phoneNumber,
        simId = simId,
        statusDetails = statusDetails,
        timeReceived = timeReceived,
        timeLastSendAttempt = timeLastSendAttempt,
        timeNextSendAttempt = timeNextSendAttempt,
        retryCount = retryCount,
    )
