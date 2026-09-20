package com.xiufeigo.opencodeshell.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.theme.LocalOcReducedMotion
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Pure pill geometry for the overlay scrollbar.
 *
 * `firstVisibleItemIndex` counts from the list's index `0`, which in a
 * `reverseLayout` list *is* the visual end (that is the M1/M2 bottom-follow
 * contract in `MessageTimeline`). `progress` is therefore measured from the
 * content's **start** (the visual top): index `total - 1` gives `0`, index `0`
 * gives `1`.
 *
 * The model is index-based on purpose — a pixel-exact thumb would need the whole
 * content height, which a `LazyColumn` never measures. With `LazyListState`'s
 * per-item offset folded in, the thumb tracks the list closely enough for a
 * 3 dp bar; the plan explicitly allows an approximation ("近似即可").
 */
object OcScrollbarModel {

    /** Never shrink the thumb into a hairline; `[0, 1]` of the track. */
    const val MIN_SIZE_FRACTION = 0.08f

    /** `topFraction` is already in track units (0 = top, 1 = bottom edge). */
    data class Thumb(val topFraction: Float, val sizeFraction: Float)

    fun thumb(
        firstVisibleItemIndex: Int,
        totalItems: Int,
        visibleItems: Int,
        firstItemOffsetFraction: Float = 0f,
    ): Thumb {
        if (totalItems <= 0) return Thumb(0f, 1f)
        val visible = visibleItems.coerceIn(0, totalItems)
        val sizeFraction = (visible.toFloat() / totalItems)
            .coerceIn(MIN_SIZE_FRACTION, 1f)
        if (sizeFraction >= 1f) return Thumb(0f, 1f)
        val maxScroll = (totalItems - visible).coerceAtLeast(1).toFloat()
        val fromStart = (totalItems - 1 - firstVisibleItemIndex).toFloat() -
            firstItemOffsetFraction.coerceIn(0f, 1f)
        val progress = (fromStart / maxScroll).coerceIn(0f, 1f)
        return Thumb(topFraction = progress * (1f - sizeFraction), sizeFraction = sizeFraction)
    }

    /**
     * Inverse of [thumb] for dragging: which item index puts the viewport at
     * `progress` (0 = visual top).
     */
    fun indexForProgress(progress: Float, totalItems: Int, visibleItems: Int): Int {
        if (totalItems <= 1) return 0
        val clamped = progress.coerceIn(0f, 1f)
        if (clamped >= 0.999f) return 0
        val maxScroll = (totalItems - visibleItems).coerceAtLeast(1).toFloat()
        val index = (totalItems - 1 - clamped * maxScroll).roundToInt()
        return index.coerceIn(0, totalItems - 1)
    }
}

/**
 * Overlay scrollbar for the long lists (`MessageTimeline` first).
 *
 * A **12 dp hit strip** on the trailing edge carries a **3 dp** rounded pill:
 * the strip is what the finger grabs (and what the drag gesture listens on), the
 * pill is what the eye sees. The track is transparent, so the scrollbar never
 * competes with the `bg-base` surface.
 *
 * Visibility follows the platform convention — the bar fades in while the list
 * moves and fades back out [HIDE_DELAY_MS] later — with a `200 ms` fade so it
 * does not blink on a fling. Dragging the strip scrolls the list; the mapping
 * through [OcScrollbarModel.indexForProgress] is what makes a reversed list
 * (newest at the bottom) behave the same way it reads.
 */
@Composable
fun OcScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    /** `12px` hit strip. */
    hitWidth: Dp = 12.dp,
    /** The visible pill. */
    thumbWidth: Dp = 3.dp,
    /** A 3 dp pill must still be grabbable/visible. */
    minThumbHeight: Dp = 24.dp,
    color: Color = OcTheme.colors.iconMuted,
) {
    val scope = rememberCoroutineScope()

    // Read `layoutInfo` here (not in the timeline) so a scroll pixel only
    // recomposes the scrollbar.
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val firstVisible = info.visibleItemsInfo.firstOrNull()
    val visibleCount = info.visibleItemsInfo.size
    val offsetFraction = firstVisible
        ?.takeIf { it.size > 0 }
        ?.let { state.firstVisibleItemScrollOffset.toFloat() / it.size }
        ?: 0f
    val thumb = remember(total, firstVisible?.index, visibleCount, offsetFraction) {
        OcScrollbarModel.thumb(
            firstVisibleItemIndex = firstVisible?.index ?: 0,
            totalItems = total,
            visibleItems = visibleCount,
            firstItemOffsetFraction = offsetFraction,
        )
    }

    var idle by remember { mutableStateOf(true) }
    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) {
            idle = false
        } else {
            delay(HIDE_DELAY_MS)
            idle = true
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (idle) 0f else 1f,
        animationSpec = tween(
            durationMillis = OcMotion.durationMs(FADE_MS, LocalOcReducedMotion.current),
        ),
        label = "scrollbar-alpha",
    )

    // The gesture must NOT be keyed by the thumb: every drag frame changes the
    // thumb, and a changed `pointerInput` key restarts (i.e. cancels) the gesture
    // detector mid-drag. Read the live values through a holder instead.
    val live = rememberUpdatedState(ScrollbarDragState(thumb, total, visibleCount))

    Canvas(
        modifier = modifier
            .fillMaxHeight()
            .width(hitWidth)
            .graphicsLayer { this.alpha = alpha }
            .pointerInput(state) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    val drag = live.value
                    val track = size.height.toFloat()
                    if (track <= 0f || drag.thumb.sizeFraction >= 1f) {
                        return@detectVerticalDragGestures
                    }
                    val thumbPx = (drag.thumb.sizeFraction * track).coerceAtLeast(1f)
                    val travel = (track - thumbPx).coerceAtLeast(1f)
                    val current = drag.thumb.topFraction.coerceIn(0f, 1f) * track
                    val next = (current + dragAmount).coerceIn(0f, travel)
                    val progress = next / travel
                    val index = OcScrollbarModel.indexForProgress(
                        progress = progress,
                        totalItems = drag.totalItems,
                        visibleItems = drag.visibleItems,
                    )
                    scope.launch { state.scrollToItem(index) }
                }
            },
    ) {
        val track = size.height
        val pillWidth = thumbWidth.toPx().coerceAtMost(size.width)
        val pillHeight = (thumb.sizeFraction * track)
            .coerceIn(minThumbHeight.toPx().coerceAtMost(track), track)
        val top = thumb.topFraction.coerceIn(0f, 1f) * (track - pillHeight)
        drawRoundRect(
            color = color.copy(alpha = color.alpha * THUMB_ALPHA),
            topLeft = Offset((size.width - pillWidth) / 2f, top),
            size = Size(pillWidth, pillHeight),
            cornerRadius = CornerRadius(pillWidth / 2f),
        )
    }
}

/** `ChatView`-era touch-slop-free fade; matches the `.2s` overlay transitions. */
private const val FADE_MS = 200

/** Snapshot of the values a drag needs, read through `rememberUpdatedState`. */
private data class ScrollbarDragState(
    val thumb: OcScrollbarModel.Thumb,
    val totalItems: Int,
    val visibleItems: Int,
)

/** How long the bar lingers after the list stops moving. */
private const val HIDE_DELAY_MS = 900L

/** `--v2-overlay-simple-overlay-*` territory: present, never loud. */
private const val THUMB_ALPHA = 0.45f
