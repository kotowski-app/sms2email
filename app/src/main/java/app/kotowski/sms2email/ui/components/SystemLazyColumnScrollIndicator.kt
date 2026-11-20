package app.kotowski.sms2email.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

private const val MIN_THUMB_HEIGHT_PX = 66f

// The thumb geometry for one frame; null = draw nothing.
internal data class LazyScrollIndicatorLayout(
    val thumbTopY: Float,
    val thumbHeight: Float,
)

// The LazyListState has no exact content size, so the total height is estimated from the
// average visible item height — close enough for the thumb even with rows of varying heights.
internal fun lazyScrollIndicatorLayout(
    alpha: Float,
    visibleItemSizesPx: List<Int>,
    totalItemsCount: Int,
    viewportHeightPx: Int,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffsetPx: Int,
): LazyScrollIndicatorLayout? {
    if (alpha <= 0f) return null
    if (visibleItemSizesPx.isEmpty()) return null
    if (totalItemsCount == 0) return null
    val viewportHeight = viewportHeightPx.toFloat()
    if (viewportHeight <= 0f) return null

    val averageItemHeight = visibleItemSizesPx.sum().toFloat() / visibleItemSizesPx.size
    val totalContentHeight = totalItemsCount * averageItemHeight
    if (totalContentHeight <= viewportHeight) return null

    val thumbHeight =
        (viewportHeight / totalContentHeight * viewportHeight)
            .coerceIn(MIN_THUMB_HEIGHT_PX, viewportHeight)
    val scrollOffset =
        firstVisibleItemIndex * averageItemHeight + firstVisibleItemScrollOffsetPx.toFloat()
    val maxScrollOffset = totalContentHeight - viewportHeight
    val scrollProgress =
        if (maxScrollOffset > 0) {
            (scrollOffset / maxScrollOffset).coerceIn(0f, 1f)
        } else {
            0f
        }
    return LazyScrollIndicatorLayout(
        thumbTopY = scrollProgress * (viewportHeight - thumbHeight),
        thumbHeight = thumbHeight,
    )
}

@Composable
fun SystemLazyColumnScrollIndicator(
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val isScrolling = listState.isScrollInProgress
    val alphaState =
        animateFloatAsState(
            targetValue = scrollbarAlphaTarget(isScrolling),
            animationSpec = tween(scrollbarFadeDurationMs(isScrolling)),
            label = "scrollbar_alpha",
        )
    val baseColor = MaterialTheme.colorScheme.onSurfaceVariant

    Spacer(
        modifier =
            modifier
                .fillMaxHeight()
                .width(5.dp)
                .drawWithCache {
                    // Reads are observed: scroll/fade changes redraw, never recompose.
                    val alpha = alphaState.value
                    val visibleItems = listState.layoutInfo.visibleItemsInfo
                    val layout =
                        lazyScrollIndicatorLayout(
                            alpha = alpha,
                            visibleItemSizesPx = visibleItems.map { it.size },
                            totalItemsCount = listState.layoutInfo.totalItemsCount,
                            viewportHeightPx = listState.layoutInfo.viewportSize.height,
                            firstVisibleItemIndex = listState.firstVisibleItemIndex,
                            firstVisibleItemScrollOffsetPx = listState.firstVisibleItemScrollOffset,
                        )
                    val trackColor = baseColor.copy(alpha = 0.15f * alpha)
                    val thumbColor = baseColor.copy(alpha = 0.5f * alpha)

                    onDrawWithContent {
                        val geometry = layout ?: return@onDrawWithContent
                        drawRoundRect(
                            color = trackColor,
                            topLeft = Offset.Zero,
                            size = Size(size.width, size.height),
                            cornerRadius = CornerRadius(size.width / 2, size.width / 2),
                        )
                        drawRoundRect(
                            color = thumbColor,
                            topLeft = Offset(0f, geometry.thumbTopY),
                            size = Size(size.width, geometry.thumbHeight),
                            cornerRadius = CornerRadius(size.width / 2, size.width / 2),
                        )
                    }
                },
    )
}
