package com.xiufeigo.opencodeshell.ui.markdown

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Column sizing for the timeline markdown table ([MarkdownRenderer] `TableView`).
 *
 * The table scrolls horizontally (upstream `table { display: block;
 * overflow-x: auto }`), and a scrolling container measures its content with
 * **unbounded** width constraints — where `Row` `weight()` children resolve to
 * zero width and the whole table collapses to blank (no crash, no a11y nodes).
 * Columns therefore get fixed equal widths derived from the real viewport
 * (via `BoxWithConstraints`, whose `maxWidth` is bounded by the timeline item)
 * instead of weights: narrow tables fill the viewport, wide tables scroll.
 * Equal widths keep header and body columns aligned across their separate rows.
 */
internal val TABLE_MIN_COL_WIDTH = 96.dp

/**
 * Width of one column for a [columns]-column table in a [viewportWidth]-wide
 * viewport: an even split, floored at [minColWidth] so wide tables scroll
 * instead of squeezing. Pure arithmetic (no composition) so it is JVM-tested
 * (`TableRenderTest`).
 */
internal fun tableColumnWidth(
    viewportWidth: Dp,
    columns: Int,
    minColWidth: Dp = TABLE_MIN_COL_WIDTH,
): Dp {
    if (columns <= 0 || viewportWidth <= 0.dp || !viewportWidth.value.isFinite()) return minColWidth
    return (viewportWidth / columns.toFloat()).coerceAtLeast(minColWidth)
}
