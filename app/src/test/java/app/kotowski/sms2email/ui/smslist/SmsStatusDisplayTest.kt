package app.kotowski.sms2email.ui.smslist

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import android.provider.Settings
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.SmsUiModel
import app.kotowski.sms2email.util.DateUtil
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals

// The list and detail screens share these mappings, so the tests assert the single
// source of truth. Rendered dates depend on the CLDR snapshot, so the SENT tests
// capture the formatted argument instead of predicting it; the locale and 12/24-hour
// choice are pinned so the pattern stays stable.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SmsStatusDisplayTest {
    private val context: Context = mock()

    private val now = 1_700_000_000_000L

    private val scheme =
        lightColorScheme().copy(
            primary = Color(0xFF111111),
            secondary = Color(0xFF222222),
            tertiary = Color(0xFF333333),
            error = Color(0xFF444444),
            onSurfaceVariant = Color(0xFF555555),
        )

    @Before
    fun setUp() {
        val appContext = RuntimeEnvironment.getApplication()
        whenever(context.getString(R.string.history_status_sent)).thenReturn("Forwarded")
        whenever(context.getString(R.string.history_status_pending)).thenReturn("Pending")
        whenever(context.getString(R.string.history_status_sending)).thenReturn("Sending")
        whenever(context.getString(R.string.history_status_retrying)).thenReturn("Retrying")
        whenever(context.getString(R.string.history_status_failed)).thenReturn("Failed")
        whenever(context.getString(R.string.history_status_ignored)).thenReturn("Ignored")
        whenever(context.getString(R.string.history_status_imported)).thenReturn("Imported")
        whenever(context.getString(R.string.history_status_waiting_for_internet)).thenReturn("Waiting for Internet")

        // appLocale reads the context's resources; pin it to English so DateUtil's
        // pattern and month letters are deterministic.
        val resources = mock<Resources>()
        val configuration = mock<Configuration>()
        whenever(context.resources).thenReturn(resources)
        whenever(resources.configuration).thenReturn(configuration)
        whenever(configuration.locales).thenReturn(LocaleList(Locale.ENGLISH))

        // is24HourFormat reads the system setting through the content resolver.
        whenever(context.contentResolver).thenReturn(appContext.contentResolver)
        Settings.System.putString(appContext.contentResolver, Settings.System.TIME_12_24, "12")
    }

    @Test
    fun `sent uses the primary color`() {
        assertEquals(scheme.primary, smsStatusColor(SmsStatus.SENT, scheme))
    }

    @Test
    fun `pending uses the tertiary color`() {
        assertEquals(scheme.tertiary, smsStatusColor(SmsStatus.PENDING, scheme))
    }

    @Test
    fun `sending uses the tertiary color`() {
        assertEquals(scheme.tertiary, smsStatusColor(SmsStatus.SENDING, scheme))
    }

    @Test
    fun `retrying uses the tertiary color`() {
        assertEquals(scheme.tertiary, smsStatusColor(SmsStatus.RETRYING, scheme))
    }

    @Test
    fun `failed uses the error color`() {
        assertEquals(scheme.error, smsStatusColor(SmsStatus.FAILED, scheme))
    }

    @Test
    fun `ignored uses the secondary color`() {
        assertEquals(scheme.secondary, smsStatusColor(SmsStatus.IGNORED, scheme))
    }

    @Test
    fun `imported uses the on surface variant color`() {
        assertEquals(scheme.onSurfaceVariant, smsStatusColor(SmsStatus.IMPORTED, scheme))
    }

    @Test
    fun `label res maps sent`() {
        assertEquals(R.string.history_status_sent, smsStatusLabelRes(SmsStatus.SENT))
    }

    @Test
    fun `label res maps pending`() {
        assertEquals(R.string.history_status_pending, smsStatusLabelRes(SmsStatus.PENDING))
    }

    @Test
    fun `label res maps sending`() {
        assertEquals(R.string.history_status_sending, smsStatusLabelRes(SmsStatus.SENDING))
    }

    @Test
    fun `label res maps retrying`() {
        assertEquals(R.string.history_status_retrying, smsStatusLabelRes(SmsStatus.RETRYING))
    }

    @Test
    fun `label res maps failed`() {
        assertEquals(R.string.history_status_failed, smsStatusLabelRes(SmsStatus.FAILED))
    }

    @Test
    fun `label res maps ignored`() {
        assertEquals(R.string.history_status_ignored, smsStatusLabelRes(SmsStatus.IGNORED))
    }

    @Test
    fun `label res maps imported`() {
        assertEquals(R.string.history_status_imported, smsStatusLabelRes(SmsStatus.IMPORTED))
    }

    private fun model(
        status: SmsStatus,
        timeLastSendAttempt: Long? = null,
        timeNextSendAttempt: Long? = null,
        timeReceived: Long = now,
    ): SmsUiModel =
        SmsUiModel(
            id = 1,
            phoneNumber = "+1",
            messageBody = "b",
            simId = -1,
            status = status,
            statusDetails = null,
            timeReceived = timeReceived,
            timeLastSendAttempt = timeLastSendAttempt,
            timeNextSendAttempt = timeNextSendAttempt,
            retryCount = 0,
        )

    // timeReceived is a different day than timeLastSendAttempt, so the two candidates
    // format differently and the branch is identifiable. The rendered date depends on
    // the CLDR snapshot, so the vararg call is stubbed with a matcher and the captured
    // argument is compared to the real formatter output for the expected timestamp.
    private fun captureForwardedAt(sms: SmsUiModel): String {
        whenever(context.getString(eq(R.string.history_status_forwarded_at), any<String>())).thenReturn("Forwarded at (stub)")
        listStatusText(context, sms, hasInternet = true, nowMillis = now + 5_000)
        val formatted = argumentCaptor<String>()
        verify(context).getString(eq(R.string.history_status_forwarded_at), formatted.capture())
        return formatted.firstValue
    }

    @Test
    fun `list sent carries the last send attempt timestamp`() {
        val sms = model(status = SmsStatus.SENT, timeLastSendAttempt = now, timeReceived = now + 86_400_000)
        assertEquals(DateUtil.formatDateTime(context, now), captureForwardedAt(sms))
    }

    @Test
    fun `list sent falls back to the receive time when never sent`() {
        val sms = model(status = SmsStatus.SENT)
        assertEquals(DateUtil.formatDateTime(context, now), captureForwardedAt(sms))
    }

    @Test
    fun `list retrying without internet waits`() {
        val sms = model(status = SmsStatus.RETRYING, timeNextSendAttempt = now + 90_000)
        assertEquals("Waiting for Internet", listStatusText(context, sms, hasInternet = false, nowMillis = now))
    }

    @Test
    fun `list retrying with internet counts down to the next attempt`() {
        val sms = model(status = SmsStatus.RETRYING, timeNextSendAttempt = now + 90_000)
        whenever(context.getString(R.string.duration_minutes, 1L, 30L)).thenReturn("1m 30s")
        whenever(context.getString(R.string.history_status_retrying_in, "1m 30s")).thenReturn("Retrying in 1m 30s")
        assertEquals("Retrying in 1m 30s", listStatusText(context, sms, hasInternet = true, nowMillis = now))
    }

    @Test
    fun `list retrying with internet past the next attempt shows plain retrying`() {
        val sms = model(status = SmsStatus.RETRYING, timeNextSendAttempt = now - 1_000)
        assertEquals("Retrying", listStatusText(context, sms, hasInternet = true, nowMillis = now))
    }

    @Test
    fun `list retrying with internet and no scheduled attempt shows plain retrying`() {
        val sms = model(status = SmsStatus.RETRYING)
        assertEquals("Retrying", listStatusText(context, sms, hasInternet = true, nowMillis = now))
    }

    @Test
    fun `list pending uses the base label`() {
        val sms = model(status = SmsStatus.PENDING)
        assertEquals("Pending", listStatusText(context, sms, hasInternet = true, nowMillis = now))
    }

    @Test
    fun `list sending uses the base label`() {
        val sms = model(status = SmsStatus.SENDING)
        assertEquals("Sending", listStatusText(context, sms, hasInternet = true, nowMillis = now))
    }

    @Test
    fun `list failed uses the base label`() {
        val sms = model(status = SmsStatus.FAILED)
        assertEquals("Failed", listStatusText(context, sms, hasInternet = true, nowMillis = now))
    }

    @Test
    fun `list ignored uses the base label`() {
        val sms = model(status = SmsStatus.IGNORED)
        assertEquals("Ignored", listStatusText(context, sms, hasInternet = true, nowMillis = now))
    }

    @Test
    fun `list imported uses the base label`() {
        val sms = model(status = SmsStatus.IMPORTED)
        assertEquals("Imported", listStatusText(context, sms, hasInternet = true, nowMillis = now))
    }

    @Test
    fun `detail retrying without internet waits`() {
        val sms = model(status = SmsStatus.RETRYING)
        assertEquals("Waiting for Internet", detailStatusText(context, sms, hasInternet = false))
    }

    @Test
    fun `detail retrying with internet shows plain retrying`() {
        val sms = model(status = SmsStatus.RETRYING)
        assertEquals("Retrying", detailStatusText(context, sms, hasInternet = true))
    }

    @Test
    fun `detail sent shows the plain label without a timestamp`() {
        val sms = model(status = SmsStatus.SENT, timeLastSendAttempt = now)
        assertEquals("Forwarded", detailStatusText(context, sms, hasInternet = true))
    }

    @Test
    fun `detail pending uses the base label`() {
        val sms = model(status = SmsStatus.PENDING)
        assertEquals("Pending", detailStatusText(context, sms, hasInternet = true))
    }

    @Test
    fun `detail failed uses the base label`() {
        val sms = model(status = SmsStatus.FAILED)
        assertEquals("Failed", detailStatusText(context, sms, hasInternet = true))
    }

    @Test
    fun `detail imported uses the base label`() {
        val sms = model(status = SmsStatus.IMPORTED)
        assertEquals("Imported", detailStatusText(context, sms, hasInternet = true))
    }
}
