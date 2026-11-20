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

// Proves the shipped values-ru strings resolve under the Russian locale, and that
// the single/multiple pick stays binary: these sentences carry no number, so CLDR
// plural categories must not drive the selection (under <plurals>, 21 would resolve
// to "one" and show the singular for 21 rows). The assertions are the exact shipped
// text: the mapper returns the assembled texts, resolved in the ru-locale context.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ru")
class SmsActionFeedbackRussianTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun forwardTexts(outcome: ForwardOutcome): List<String> = forwardOutcomeToasts(context, outcome)

    private fun cancelText(outcome: CancelOutcome): String = context.getString(cancelOutcomeToast(outcome)!!)

    @Test
    fun `russian forward selection is binary, not CLDR categories`() {
        assertEquals(listOf("Сообщение уже отправляется."), forwardTexts(ForwardOutcome(1, 0)))
        assertEquals(listOf("Сообщения уже отправляются."), forwardTexts(ForwardOutcome(2, 0)))
        // 21 resolves to CLDR "one" under <plurals>: the guard against that regression.
        assertEquals(listOf("Сообщения уже отправляются."), forwardTexts(ForwardOutcome(21, 0)))
    }

    // The filter skip's count stands alone in the same shape for every count (the
    // inflection-neutral construction): no CLDR category can split it.
    @Test
    fun `russian filter skip toast carries the count standing alone`() {
        assertEquals(
            "Пропущено: 1 — не соответствует правилам фильтра",
            forwardTexts(ForwardOutcome(found = 1, scheduled = 0, filterBlocked = 1, filterMode = FilterMode.FORWARD))
                .last(),
        )
        assertEquals(
            "Пропущено: 2 — не соответствует правилам фильтра",
            forwardTexts(ForwardOutcome(found = 2, scheduled = 0, filterBlocked = 2, filterMode = FilterMode.FORWARD))
                .last(),
        )
        // 21 resolves to CLDR "one" under <plurals>: the guard against that regression.
        assertEquals(
            "Пропущено: 21 — не соответствует правилам фильтра",
            forwardTexts(ForwardOutcome(found = 21, scheduled = 0, filterBlocked = 21, filterMode = FilterMode.FORWARD))
                .last(),
        )
        assertEquals(
            "Пропущено фильтром: 2",
            forwardTexts(ForwardOutcome(found = 2, scheduled = 0, filterBlocked = 2, filterMode = FilterMode.SKIP))
                .last(),
        )
    }

    // A batch can carry both kinds of blocked rows: the toasts say both, in the
    // outcome's order (the in-flight pair first, the filter skip with its count).
    @Test
    fun `russian forward toast reports both blocked buckets in one batch`() {
        assertEquals(
            listOf(
                "Сообщения уже отправляются.",
                "Пропущено: 2 — не соответствует правилам фильтра",
            ),
            forwardTexts(ForwardOutcome(found = 3, scheduled = 0, filterBlocked = 2, filterMode = FilterMode.FORWARD)),
        )
    }

    @Test
    fun `russian cancel selection is binary, not CLDR categories`() {
        // «историю» lowercase mid-sentence: native Russian, the screen name is only
        // capitalized in quotes («История») elsewhere.
        assertEquals(
            "Сообщение отправляется или уже перемещено в историю.",
            cancelText(CancelOutcome(cancelled = 0, attempted = 0, requested = 1)),
        )
        // 21 resolves to CLDR "one" under <plurals>: the guard against that regression.
        assertEquals(
            "Сообщения отправляются или уже перемещены в историю.",
            cancelText(CancelOutcome(cancelled = 0, attempted = 0, requested = 21)),
        )
        // The partial-skip branch counts the blocked rows (3), not the requested (4).
        assertEquals(
            "Сообщения отправляются или уже перемещены в историю.",
            cancelText(CancelOutcome(cancelled = 1, attempted = 4, requested = 4)),
        )
    }
}
