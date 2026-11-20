package app.kotowski.sms2email.ui.smslist

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The shared list's scroll trigger: the load fires within 2 rows of the end.
class ShouldLoadMoreTest {
    // 10 rows: item indices 0..9.
    private val itemCount = 10

    @Test
    fun `no load when the window has no more pages`() {
        assertFalse(shouldLoadMore(9, itemCount, hasMore = false))
    }

    @Test
    fun `no load while the user is far from the end`() {
        assertFalse(shouldLoadMore(0, itemCount, hasMore = true))
        assertFalse(shouldLoadMore(5, itemCount, hasMore = true))
    }

    @Test
    fun `load fires on the last row`() {
        assertTrue(shouldLoadMore(9, itemCount, hasMore = true))
    }

    @Test
    fun `load fires one row before the end`() {
        assertTrue(shouldLoadMore(8, itemCount, hasMore = true))
    }
}
