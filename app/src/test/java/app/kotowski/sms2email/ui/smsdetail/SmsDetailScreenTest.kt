package app.kotowski.sms2email.ui.smsdetail

import android.content.Context
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.testing.smsModel
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SmsDetailScreenTest {
    private val scheme =
        lightColorScheme().copy(
            secondary = Color(0xFF625B71),
            onSurface = Color(0xFF1C1B1F),
            error = Color(0xFFBA1A1A),
        )

    // Delete stays disabled while sending so the send is let finish.

    @Test
    fun `pending rows offer send and cancel but not delete`() {
        assertEquals(DetailActions(send = true, cancel = true, delete = false), detailActions(SmsStatus.PENDING))
    }

    @Test
    fun `retrying rows offer send and cancel but not delete`() {
        assertEquals(DetailActions(send = true, cancel = true, delete = false), detailActions(SmsStatus.RETRYING))
    }

    @Test
    fun `sending rows swap the forward for the spinner and gray out delete`() {
        assertEquals(DetailActions(send = false, cancel = false, delete = true, sending = true), detailActions(SmsStatus.SENDING))
    }

    @Test
    fun `terminal rows offer send and delete but not cancel`() {
        for (status in listOf(SmsStatus.SENT, SmsStatus.FAILED, SmsStatus.IGNORED, SmsStatus.IMPORTED)) {
            assertEquals(DetailActions(send = true, cancel = false, delete = true), detailActions(status), "status=$status")
        }
    }

    @Test
    fun `no enabled delete is offered for any in-flight status`() {
        for (status in listOf(SmsStatus.PENDING, SmsStatus.SENDING, SmsStatus.RETRYING)) {
            val actions = detailActions(status)
            assertFalse(actions.delete && !actions.sending, "status=$status must never be deletable from the detail")
        }
    }

    @Test
    fun `failed status details use the regular text color`() {
        assertEquals(scheme.onSurface, statusDetailsValueColor(SmsStatus.FAILED, scheme))
    }

    @Test
    fun `retrying status details use the regular text color`() {
        assertEquals(scheme.onSurface, statusDetailsValueColor(SmsStatus.RETRYING, scheme))
    }

    @Test
    fun `failed status details do not wear the error color`() {
        assertNotEquals(scheme.error, statusDetailsValueColor(SmsStatus.FAILED, scheme))
    }

    @Test
    fun `ignored status details keep the secondary tone`() {
        assertEquals(scheme.secondary, statusDetailsValueColor(SmsStatus.IGNORED, scheme))
    }

    // A NotFound before the row was shown is the "no longer available" screen, not a pop;
    // only a NotFound after the row was shown (deleted while the detail is open) pops.

    @Test
    fun `no pop while the initial row load is in flight`() {
        assertFalse(detailShouldNavigateBack(hasLoaded = false, detail = SmsDetailState.Loading))
    }

    @Test
    fun `no pop when the row is gone before it was ever shown`() {
        assertFalse(detailShouldNavigateBack(hasLoaded = false, detail = SmsDetailState.NotFound))
    }

    @Test
    fun `no pop while the row is loaded`() {
        val detail =
            SmsDetailState.Loaded(
                SmsDetailData(sms = smsModel(1L, SmsStatus.SENT), simNames = emptyMap()),
            )
        assertFalse(detailShouldNavigateBack(hasLoaded = true, detail = detail))
    }

    @Test
    fun `pop when a loaded row is deleted while the detail is open`() {
        assertTrue(detailShouldNavigateBack(hasLoaded = true, detail = SmsDetailState.NotFound))
    }

    @Test
    fun `formatNextRetry returns the soon label when nothing remains`() {
        val context = mock<Context>()
        whenever(context.getString(R.string.sms_detail_next_retry_soon)).thenReturn("soon")

        assertEquals("soon", formatNextRetry(context, 0))
        assertEquals("soon", formatNextRetry(context, -1000))
    }

    @Test
    fun `formatNextRetry wraps the localized duration`() {
        // 5_400_000 ms = 1h 30m: the hours tier of the shared formatter
        val context = mock<Context>()
        whenever(context.getString(R.string.duration_hours, 1L, 30L, 0L)).thenReturn("1h 30m 0s")
        whenever(context.getString(R.string.sms_detail_next_retry_in, "1h 30m 0s")).thenReturn("in 1h 30m 0s")

        assertEquals("in 1h 30m 0s", formatNextRetry(context, 5_400_000))
    }

    @Test
    fun `formatNextRetry uses the days tier beyond a day`() {
        val context = mock<Context>()
        whenever(context.getString(R.string.duration_days, 1L, 1L, 1L, 5L)).thenReturn("1d 1h 1m 5s")
        whenever(context.getString(R.string.sms_detail_next_retry_in, "1d 1h 1m 5s")).thenReturn("in 1d 1h 1m 5s")

        assertEquals("in 1d 1h 1m 5s", formatNextRetry(context, 90_065_000))
    }
}
