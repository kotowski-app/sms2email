package app.kotowski.sms2email.ui

import android.content.Context
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.filter.FilterMode
import app.kotowski.sms2email.work.CancelOutcome
import app.kotowski.sms2email.work.DeleteOutcome
import app.kotowski.sms2email.work.ForwardOutcome
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The outcome -> toast text rules the screens rely on: the single/multiple form
// resolves from the blocked count (the all-blocked cancel reads the requested
// count), so the text's number always matches the rows it talks about. The forward
// mapper returns the assembled texts (the in-flight pair numberless, the filter
// skip with its count) in the outcome's order; the shipped texts resolving per
// locale is covered by SmsActionFeedbackRussianTest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsActionFeedbackTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `forwardOutcomeToasts maps forwardingOff before any count rule`() {
        assertEquals(
            listOf(context.getString(R.string.sms_forward_forwarding_off)),
            forwardOutcomeToasts(context, ForwardOutcome(0, 0, forwardingOff = true)),
        )
        assertEquals(
            listOf(context.getString(R.string.sms_forward_forwarding_off)),
            forwardOutcomeToasts(context, ForwardOutcome(found = 2, scheduled = 1, forwardingOff = true)),
        )
    }

    @Test
    fun `forwardOutcomeToasts stays silent for a successful or empty forward`() {
        assertTrue(forwardOutcomeToasts(context, ForwardOutcome(found = 2, scheduled = 2)).isEmpty())
        assertTrue(forwardOutcomeToasts(context, ForwardOutcome(found = 1, scheduled = 1)).isEmpty())
        // An empty fetch (not-found ids) and an empty queue both read as silent.
        assertTrue(forwardOutcomeToasts(context, ForwardOutcome(0, 0)).isEmpty())
    }

    @Test
    fun `forwardOutcomeToasts resolves the in-flight plural from the blocked count, not the found count`() {
        // One blocked row reads singular — even when it was selected among others.
        assertEquals(
            listOf(context.getString(R.string.sms_forward_blocked_single)),
            forwardOutcomeToasts(context, ForwardOutcome(found = 1, scheduled = 0)),
        )
        assertEquals(
            listOf(context.getString(R.string.sms_forward_blocked_single)),
            forwardOutcomeToasts(context, ForwardOutcome(found = 3, scheduled = 2)),
        )
        assertEquals(
            listOf(context.getString(R.string.sms_forward_blocked_multiple)),
            forwardOutcomeToasts(context, ForwardOutcome(found = 2, scheduled = 0)),
        )
        assertEquals(
            listOf(context.getString(R.string.sms_forward_blocked_multiple)),
            forwardOutcomeToasts(context, ForwardOutcome(found = 3, scheduled = 1)),
        )
    }

    @Test
    fun `forwardOutcomeToasts reads the whole queue when every queue row is in flight`() {
        // The queue-shaped outcome counts the in-flight rows themselves.
        assertEquals(
            listOf(context.getString(R.string.sms_forward_blocked_single)),
            forwardOutcomeToasts(context, ForwardOutcome(found = 1, scheduled = 0)),
        )
        assertEquals(
            listOf(context.getString(R.string.sms_forward_blocked_multiple)),
            forwardOutcomeToasts(context, ForwardOutcome(found = 4, scheduled = 0)),
        )
    }

    // The filter skips are reported in their own entry, after the in-flight one,
    // with the mode-dependent reason and the count standing alone (the
    // inflection-neutral shape).
    @Test
    fun `forwardOutcomeToasts appends the filter skips with the skip-mode reason and the count`() {
        val outcome = ForwardOutcome(found = 3, scheduled = 2, filterBlocked = 1, filterMode = FilterMode.SKIP)
        assertEquals(
            listOf(
                context.getString(R.string.sms_forward_blocked_single),
                context.getString(R.string.settings_filter_toast_skipped, 1),
            ),
            forwardOutcomeToasts(context, outcome),
        )
    }

    @Test
    fun `forwardOutcomeToasts appends the filter skips with the forward-mode reason and the count`() {
        val outcome = ForwardOutcome(found = 4, scheduled = 3, filterBlocked = 1, filterMode = FilterMode.FORWARD)
        assertEquals(
            listOf(
                context.getString(R.string.sms_forward_blocked_single),
                context.getString(R.string.settings_filter_toast_no_match, 1),
            ),
            forwardOutcomeToasts(context, outcome),
        )
    }

    @Test
    fun `forwardOutcomeToasts reports both blocked buckets in one batch`() {
        // found = filter-passing + in-flight: three in flight (none scheduled) plus
        // two filter skips outside the found count.
        val outcome = ForwardOutcome(found = 3, scheduled = 0, filterBlocked = 2, filterMode = FilterMode.FORWARD)
        assertEquals(
            listOf(
                context.getString(R.string.sms_forward_blocked_multiple),
                context.getString(R.string.settings_filter_toast_no_match, 2),
            ),
            forwardOutcomeToasts(context, outcome),
        )
    }

    @Test
    fun `forwardOutcomeToasts omits the filter entry while no row was filter-blocked`() {
        assertEquals(
            listOf(context.getString(R.string.sms_forward_blocked_single)),
            forwardOutcomeToasts(context, ForwardOutcome(found = 1, scheduled = 0)),
        )
        // The filter-mode default (null) is unreachable with a non-zero count; the
        // count alone gates the entry.
        assertTrue(
            forwardOutcomeToasts(
                context,
                ForwardOutcome(found = 2, scheduled = 2, filterBlocked = 0, filterMode = FilterMode.FORWARD),
            ).isEmpty(),
        )
    }

    @Test
    fun `cancelOutcomeToast reads the requested count for a fully blocked cancel`() {
        assertEquals(
            R.string.sms_cancel_blocked_single,
            cancelOutcomeToast(CancelOutcome(cancelled = 0, attempted = 0, requested = 1)),
        )
        assertEquals(
            R.string.sms_cancel_blocked_multiple,
            cancelOutcomeToast(CancelOutcome(cancelled = 0, attempted = 0, requested = 3)),
        )
        // A CAS that lost every attempted row reads the same way, by the requested count.
        assertEquals(
            R.string.sms_cancel_blocked_multiple,
            cancelOutcomeToast(CancelOutcome(cancelled = 0, attempted = 2, requested = 2)),
        )
    }

    // The empty queue (work.cancelQueue) cancelled nothing: it stays silent, unlike a
    // fully blocked cancel, which reads the requested count.
    @Test
    fun `cancelOutcomeToast stays silent for the empty queue`() {
        assertNull(cancelOutcomeToast(CancelOutcome(cancelled = 0, attempted = 0, requested = 0)))
    }

    @Test
    fun `cancelOutcomeToast reads the blocked count for a partial skip and stays silent on full success`() {
        assertEquals(
            R.string.sms_cancel_blocked_single,
            cancelOutcomeToast(CancelOutcome(cancelled = 1, attempted = 2, requested = 2)),
        )
        assertEquals(
            R.string.sms_cancel_blocked_multiple,
            cancelOutcomeToast(CancelOutcome(cancelled = 1, attempted = 3, requested = 3)),
        )
        assertNull(cancelOutcomeToast(CancelOutcome(cancelled = 2, attempted = 2, requested = 2)))
    }

    @Test
    fun `deleteOutcomeToast reports a short count and stays silent on full success`() {
        assertEquals(R.string.sms_delete_blocked, deleteOutcomeToast(DeleteOutcome(deleted = 1, requested = 2)))
        assertEquals(R.string.sms_delete_blocked, deleteOutcomeToast(DeleteOutcome(deleted = 0, requested = 1)))
        assertNull(deleteOutcomeToast(DeleteOutcome(deleted = 2, requested = 2)))
    }
}
