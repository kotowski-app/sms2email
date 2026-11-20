package app.kotowski.sms2email.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import app.kotowski.sms2email.testing.SkipInReleaseRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemScrollIndicatorTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    @Test
    fun `the alpha target is fully visible while scrolling`() {
        assertEquals(1f, scrollbarAlphaTarget(true))
    }

    @Test
    fun `the alpha target is invisible while idle`() {
        assertEquals(0f, scrollbarAlphaTarget(false))
    }

    @Test
    fun `the fade in is fast while the finger is down`() {
        assertEquals(150, scrollbarFadeDurationMs(true))
    }

    @Test
    fun `the fade out is slow so the thumb stays readable`() {
        assertEquals(1000, scrollbarFadeDurationMs(false))
    }

    private val visibleSizes = List(10) { 50 }

    @Test
    fun `nothing is drawn while fully faded out`() {
        assertEquals(null, lazyScrollIndicatorLayout(0f, visibleSizes, 100, 500, 50, 0))
    }

    @Test
    fun `nothing is drawn for an empty list`() {
        assertEquals(null, lazyScrollIndicatorLayout(1f, emptyList(), 0, 500, 0, 0))
    }

    @Test
    fun `nothing is drawn when the content fits the viewport`() {
        assertEquals(null, lazyScrollIndicatorLayout(1f, List(5) { 50 }, 5, 500, 0, 0))
    }

    @Test
    fun `nothing is drawn when the content exactly fills the viewport`() {
        assertEquals(null, lazyScrollIndicatorLayout(1f, visibleSizes, 10, 500, 0, 0))
    }

    @Test
    fun `nothing is drawn when total item count is zero`() {
        assertEquals(null, lazyScrollIndicatorLayout(1f, visibleSizes, 0, 500, 0, 0))
    }

    @Test
    fun `nothing is drawn when viewport height is zero`() {
        assertEquals(null, lazyScrollIndicatorLayout(1f, visibleSizes, 50, 0, 0, 0))
    }

    @Test
    fun `nothing is drawn when viewport height is negative`() {
        assertEquals(null, lazyScrollIndicatorLayout(1f, visibleSizes, 50, -100, 0, 0))
    }

    @Test
    fun `thumb layout handles maxScrollOffset zero when content barely exceeds viewport`() {
        // Average item height is 50, 11 items = 550 total, viewport = 500
        // maxScrollOffset = 550 - 500 = 50 (> 0, so progress is computed)
        val layout = lazyScrollIndicatorLayout(1f, visibleSizes, 11, 500, 0, 0)
        assertEquals(0f, layout?.thumbTopY ?: -1f, 1e-3f)
    }

    @Test
    fun `the thumb is at the top when scrolled to the start`() {
        val layout = lazyScrollIndicatorLayout(1f, visibleSizes, 50, 500, 0, 0)
        assertEquals(0f, layout?.thumbTopY ?: -1f, 1e-3f)
    }

    @Test
    fun `the thumb height is the visible to total ratio of the viewport`() {
        val layout = lazyScrollIndicatorLayout(1f, visibleSizes, 50, 500, 0, 0)
        assertEquals(100f, layout?.thumbHeight ?: -1f, 1e-3f)
    }

    @Test
    fun `the thumb accounts for the first visible item offset`() {
        val layout = lazyScrollIndicatorLayout(1f, visibleSizes, 50, 500, 25, 10)
        assertEquals(252f, layout?.thumbTopY ?: -1f, 1e-2f)
    }

    @Test
    fun `the thumb reaches the bottom when scrolled to the end`() {
        val layout = lazyScrollIndicatorLayout(1f, visibleSizes, 50, 500, 40, 0)
        assertEquals(400f, layout?.thumbTopY ?: -1f, 1e-2f)
    }

    @Test
    fun `the thumb progress is clamped to the track`() {
        val pastEnd = lazyScrollIndicatorLayout(1f, visibleSizes, 50, 500, 50, 5000)
        val beforeStart = lazyScrollIndicatorLayout(1f, visibleSizes, 50, 500, 0, -5000)
        assertEquals(400f, pastEnd?.thumbTopY ?: -1f, 1e-2f)
        assertEquals(0f, beforeStart?.thumbTopY ?: -1f, 1e-2f)
    }

    @Test
    fun `the thumb height is clamped to the minimum`() {
        val layout = lazyScrollIndicatorLayout(1f, visibleSizes, 100, 500, 0, 0)
        assertEquals(66f, layout?.thumbHeight ?: -1f, 1e-3f)
    }

    @Test
    fun `the total height is estimated from the average visible item size`() {
        val layout = lazyScrollIndicatorLayout(1f, listOf(100, 200), 10, 500, 0, 0)
        assertEquals(166.667f, layout?.thumbHeight ?: -1f, 1e-2f)
    }

    @Test
    fun `the indicator composes with a scrollable list`() {
        composeTestRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    val state = rememberLazyListState()
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = state,
                    ) {
                        items(100) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(40.dp),
                            )
                        }
                    }
                    SystemLazyColumnScrollIndicator(
                        listState = state,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `scroll indicator composes with a scrollable column`() {
        composeTestRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState),
                    ) {
                        repeat(100) {
                            Box(modifier = Modifier.fillMaxWidth().height(40.dp))
                        }
                    }
                    SystemScrollStateScrollIndicator(
                        scrollState = scrollState,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    // SystemScrollStateScrollIndicator: early return when max value is zero.
    @Test
    fun `scroll indicator returns early when there is nothing to scroll`() {
        composeTestRule.setContent {
            MaterialTheme {
                val scrollState = rememberScrollState()
                Box(modifier = Modifier.fillMaxSize()) {
                    SystemScrollStateScrollIndicator(
                        scrollState = scrollState,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `scroll indicator draws during scroll gesture`() {
        composeTestRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState),
                    ) {
                        repeat(100) {
                            Box(modifier = Modifier.fillMaxWidth().height(40.dp))
                        }
                    }
                    SystemScrollStateScrollIndicator(
                        scrollState = scrollState,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `lazy scroll indicator draws during scroll gesture`() {
        composeTestRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                    ) {
                        items(100) {
                            Box(modifier = Modifier.fillMaxWidth().height(40.dp))
                        }
                    }
                    SystemLazyColumnScrollIndicator(
                        listState = listState,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()
    }
}
