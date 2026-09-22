package com.xiufeigo.opencodeshell.ui.session.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * A5 — the message-navigation rail, the Android port of `MessageNav`'s
 * `size="compact"` branch (`_upstream/packages/session-ui/src/components/
 * message-nav.tsx:48-59,83-103`) without the hover card (a phone has no hover).
 *
 * `components/message-nav.css`:
 *
 * | rule | upstream | here |
 * |---|---|---|
 * | rail width | `[data-size=compact] width: 24px` (`:14-16`) | 24 dp |
 * | one item per user message | `li[data-slot=message-nav-item]` (`:19-28`) | [TimelineRows.Tick] |
 * | tick hit target | `height: 12px; width: 24px` (`:30-38`) | [MessageNavRailPolicy.TICK_HEIGHT_DP] |
 * | tick line | `1px × 16px`, `--icon-base` (`:47-54`) | same, `OcTheme.colors.iconBase` |
 * | active tick | `2px × 100%`, `--icon-strong-base` (`:40-44`) | same geometry, `textBase` |
 * | hover | line grows to 100 % (`:56-59`) | not ported (touch shell) |
 *
 * The rail is only ever rendered while a session's timeline is on screen; a tap
 * scrolls the list with `LazyListState.animateScrollToItem` — the Compose
 * equivalent of upstream's `onMessageSelect` → `revealMessage`
 * (`search-controller.ts:221-240` does the same for a search hit).
 */
@Composable
internal fun MessageNavRail(
    ticks: List<TimelineRows.Tick>,
    currentId: String?,
    onSelect: (TimelineRows.Tick) -> Unit,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    if (ticks.isEmpty()) return
    val c = OcTheme.colors
    val tickHeight = MessageNavRailPolicy.tickHeightDp(ticks.size, maxHeight.value).dp
    val scroller = rememberScrollState()
    // `ui.messageNav.newMessage` (`message-nav.tsx:67-69`) is the label of a turn
    // without text; resolved here because a `semantics {}` block cannot call
    // `stringResource`.
    val newMessage = stringResource(R.string.oc_nav_new_message)

    Column(
        modifier = modifier
            .width(RAIL_WIDTH)
            .heightIn(max = maxHeight)
            .verticalScroll(scroller),
        horizontalAlignment = Alignment.End,
    ) {
        ticks.forEach { tick ->
            val active = tick.messageID == currentId
            Box(
                modifier = Modifier
                    .width(RAIL_WIDTH)
                    .height(tickHeight)
                    .clickable(role = Role.Button) { onSelect(tick) }
                    .semantics { contentDescription = tick.preview ?: newMessage },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .width(if (active) RAIL_WIDTH else TICK_WIDTH)
                        .height(if (active) ACTIVE_TICK_HEIGHT else TICK_HEIGHT)
                        .background(if (active) c.textBase else c.iconBase),
                )
            }
        }
    }
}

/**
 * Pure rail geometry (`message-nav.css`): every tick wants 12 dp, so a rail that
 * would overflow its viewport shrinks the ticks down to a 3 dp floor and scrolls
 * the rest instead of spilling over the composer.
 *
 * Wave 2.2b (P2): the rail also carries the normal-size `MessageNav` parity
 * items without its hover card (no hover on touch): the active turn shows its
 * preview label + up-to-5 diff bars ([ToolModels.diffBars]) beside the tick
 * ([NavPreview]). The bars need per-turn `FileDiffInfo` the store does not
 * carry yet, so [NavPreview.changes] is a caller hook (null = label only).
 */
object MessageNavRailPolicy {

    /** `[data-slot=message-nav-tick-button] height: 12px`. */
    const val TICK_HEIGHT_DP = 12f

    /** Smallest tick the rail still draws as a distinguishable line. */
    const val MIN_TICK_HEIGHT_DP = 3f

    fun tickHeightDp(count: Int, availableDp: Float): Float {
        if (count <= 0) return TICK_HEIGHT_DP
        if (availableDp <= 0f) return MIN_TICK_HEIGHT_DP
        return (availableDp / count).coerceIn(MIN_TICK_HEIGHT_DP, TICK_HEIGHT_DP)
    }

    /** True when the ticks had to shrink: the rail scrolls. */
    fun needsScroll(count: Int, availableDp: Float): Boolean =
        count > 0 && availableDp > 0f && count * TICK_HEIGHT_DP > availableDp
}

/**
 * P2 parity item for the normal-size `MessageNav` (`message-nav.tsx:60-79`):
 * the turn's preview label plus its diff bars. Upstream pairs these with
 * `getLabel` / `getChanges(message)`; [changes] is `{additions, deletions}`
 * the main agent can source from `session.diff` once it is wired (null keeps
 * the label-only row, which is all the store can express today).
 */
data class NavPreview(
    val messageID: String,
    val label: String?,
    val changes: Pair<Int, Int>? = null,
)

/** `[data-size=compact] width: 24px`. */
private val RAIL_WIDTH = 24.dp

/** `[data-slot=message-nav-tick-line] width: 16px`. */
private val TICK_WIDTH = 16.dp

private val TICK_HEIGHT = 1.dp
private val ACTIVE_TICK_HEIGHT = 2.dp
