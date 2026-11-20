package app.kotowski.sms2email.ui

import android.content.Context
import app.kotowski.sms2email.data.filter.FilterMode
import app.kotowski.sms2email.work.CancelOutcome
import app.kotowski.sms2email.work.ForwardOutcome
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

// Proves the shipped values-zh strings resolve under the Chinese locale, and that
// the single/multiple pick stays binary: these sentences carry no number, so CLDR
// plural categories must not drive the selection (Chinese has a single "other"
// category, but the guard against a category-driven regression is kept). The
// assertions are the exact shipped text.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh")
class SmsActionFeedbackChineseTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun forwardTexts(outcome: ForwardOutcome): List<String> = forwardOutcomeToasts(context, outcome)

    private fun cancelText(outcome: CancelOutcome): String = context.getString(cancelOutcomeToast(outcome)!!)

    @Test
    fun `chinese forward selection is binary, not CLDR categories`() {
        assertEquals(listOf("消息正在发送中。"), forwardTexts(ForwardOutcome(1, 0)))
        assertEquals(listOf("消息正在发送中。"), forwardTexts(ForwardOutcome(2, 0)))
        assertEquals(listOf("消息正在发送中。"), forwardTexts(ForwardOutcome(21, 0)))
    }

    @Test
    fun `chinese filter skip toast carries the count standing alone`() {
        assertEquals(
            "已跳过：1 — 不匹配过滤器规则",
            forwardTexts(ForwardOutcome(found = 1, scheduled = 0, filterBlocked = 1, filterMode = FilterMode.FORWARD))
                .last(),
        )
        assertEquals(
            "已跳过：2 — 不匹配过滤器规则",
            forwardTexts(ForwardOutcome(found = 2, scheduled = 0, filterBlocked = 2, filterMode = FilterMode.FORWARD))
                .last(),
        )
        assertEquals(
            "已跳过：21 — 不匹配过滤器规则",
            forwardTexts(ForwardOutcome(found = 21, scheduled = 0, filterBlocked = 21, filterMode = FilterMode.FORWARD))
                .last(),
        )
        assertEquals(
            "被过滤器跳过：2",
            forwardTexts(ForwardOutcome(found = 2, scheduled = 0, filterBlocked = 2, filterMode = FilterMode.SKIP))
                .last(),
        )
    }

    @Test
    fun `chinese forward toast reports both blocked buckets in one batch`() {
        assertEquals(
            listOf(
                "消息正在发送中。",
                "已跳过：2 — 不匹配过滤器规则",
            ),
            forwardTexts(ForwardOutcome(found = 3, scheduled = 0, filterBlocked = 2, filterMode = FilterMode.FORWARD)),
        )
    }

    @Test
    fun `chinese cancel selection is binary, not CLDR categories`() {
        assertEquals(
            "消息正在发送中，或已移至历史记录。",
            cancelText(CancelOutcome(cancelled = 0, attempted = 0, requested = 1)),
        )
        assertEquals(
            "消息正在发送中，或已移至历史记录。",
            cancelText(CancelOutcome(cancelled = 0, attempted = 0, requested = 21)),
        )
        // The partial-skip branch counts the blocked rows (3), not the requested (4).
        assertEquals(
            "消息正在发送中，或已移至历史记录。",
            cancelText(CancelOutcome(cancelled = 1, attempted = 4, requested = 4)),
        )
    }
}
