package com.xiufeigo.opencodeshell.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * The platform minimum touch target (`48 dp`) around a control whose *visual* box
 * is smaller — the Home headers' 28 dp icon buttons, the picker's 28 dp close.
 *
 * The device run measured the cost of the bare 28 dp box on 「folder+」: only the
 * exact centre of the glyph opened the picker (`y=278` opened it, `y=240` — 38 px
 * away and still inside the same header row — did nothing), so the add entry read
 * as broken. A finger cannot hit a 28 dp square reliably; `48 dp` is the platform
 * minimum for a reason.
 *
 * The growth is a *hit* one, not a layout one: the content is measured at
 * [target] and placed centred inside a node that still reports [visual] to its
 * parent, so the row keeps its exact pixel geometry (the header rows are a fixed
 * 28 dp tall and the glyph must not move) while the tappable box is the full
 * target. Compose hit-tests a child by the child's own placed bounds, so the ring
 * that overflows the reported box stays tappable.
 *
 * Apply it to the clickable node itself, before `.clip` / `.clickable`:
 *
 * ```
 * Modifier.ocMinTouchTarget(visual = ICON_BUTTON_LARGE).clip(shape).clickable { … }
 * ```
 *
 * Do **not** use it on adjacent controls (the row's ✎/✕ pair, the colour
 * swatches): their rings would overlap and a tap between them would become
 * ambiguous — which control wins is then an accident of sibling order.
 */
fun Modifier.ocMinTouchTarget(visual: Dp, target: Dp = 48.dp): Modifier = layout { measurable, _ ->
    val visualPx = visual.roundToPx()
    val targetPx = target.roundToPx()
    val placeable = measurable.measure(
        Constraints(
            minWidth = targetPx,
            maxWidth = targetPx,
            minHeight = targetPx,
            maxHeight = targetPx,
        ),
    )
    layout(visualPx, visualPx) {
        val offset = touchTargetOffsetPx(visualPx, targetPx)
        placeable.place(offset, offset)
    }
}

/**
 * Where the [targetPx]-wide hit box is placed inside the [visualPx]-wide node the
 * parent lays out: centred, so the glyph inside does not move. Pure (and negative
 * when the target is the larger of the two — that is the overflow the ring lives
 * in) so the geometry is pinned without a composition.
 */
fun touchTargetOffsetPx(visualPx: Int, targetPx: Int): Int = (visualPx - targetPx) / 2

/**
 * The positive half of the same number: how far the [target]-wide ring reaches past
 * the [visual]-wide box on *each* side. A control that sits at its surface's own
 * edge has to leave this much room behind it — the ring is what the finger lands
 * on, and the part that hangs past the surface is outside the window (in a popup:
 * the scrim, where the tap only dismisses the popup) — see `WorkspaceMenuRow`'s ×.
 */
fun touchTargetRingOverflow(visual: Dp, target: Dp = 48.dp): Dp =
    if (target > visual) (target - visual) / 2 else 0.dp

/**
 * The row-shaped half of the rule: a row that is *visually* shorter than the
 * platform minimum (the 40 dp session row, the 28 dp project rows) but whose
 * whole box is the tap target.
 *
 * Two devices measured the bare row at 41 dp (108 px @ 2.625 px/dp, and 41 dp
 * @ 160 dpi) — a `uiautomator` dump shows one `clickable` node per row at that
 * height, so the 48 dp minimum simply was not met.
 *
 * Unlike [ocMinTouchTarget] this one does **not** re-measure its child: the row
 * keeps the exact size it lays out at (upstream's `h-10` / `h-7` stay intact) and
 * is only *centred* inside a node at least [target] tall, so the clickable node
 * that sits outside this one reports the full 48 dp.
 *
 * The order matters: this must come **before** any `.height` / `.heightIn(min)`
 * in the chain (a later one constrains the child to its own fixed height and
 * clamps this node straight back down) and **before** `.clip` / `.clickable`,
 * which is the node whose bounds the finger lands on.
 */
fun Modifier.ocHitTargetAtLeast(target: Dp = 48.dp): Modifier = layout { measurable, constraints ->
    val targetPx = target.roundToPx()
    val placeable = measurable.measure(constraints)
    val width = if (constraints.hasBoundedWidth) max(placeable.width, targetPx) else placeable.width
    val height = max(placeable.height, targetPx)
    layout(width, height) {
        placeable.place(
            (width - placeable.width) / 2,
            touchTargetOffsetPx(placeable.height, height),
        )
    }
}
