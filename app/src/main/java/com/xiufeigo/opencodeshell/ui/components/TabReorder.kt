package com.xiufeigo.opencodeshell.ui.components

import kotlin.math.ceil
import kotlin.math.floor

/**
 * B-20 / D-15: the pure half of the tab-strip drag reorder.
 *
 * Upstream reorders through `@dnd-kit`'s `useSortable` + `arrayMove`
 * (`shell/titlebar/tab-strip.tsx:334-352` for the session strip,
 * `session/terminal/tab.tsx` for the terminal one): the pointer's travel is
 * measured against the slot grid and the tab lands on the slot it was dragged
 * over. Compose has no `dnd-kit`, so the strips keep exactly that decision here
 * — which index the dragged slot has reached — and apply it live. The models
 * only need the two indices (`SessionTabs.reorder`, `TerminalTabs.move`), which
 * is why this one rule serves all three surfaces (desktop session strip, mobile
 * drawer, terminal strip).
 */
object TabReorder {

    /**
     * The index a slot dragged from [fromIndex] has reached.
     *
     * [dragPx] is the pointer travel since the drag started, [pitchPx] the slot
     * pitch (slot size + gap) and [count] the number of slots: the travel is
     * divided by the pitch, rounded to the nearest slot and clamped to the list,
     * so a drag of half a slot already counts as a step.
     *
     * Half a slot is a step in **both** directions — the rounding is away from
     * zero, not `roundToInt`'s toward positive infinity, which would make a drag
     * to the right move a slot earlier than the same drag to the left.
     *
     * Returns [fromIndex] when there is nothing to decide (a single tab, a slot
     * whose size is not measured yet) and `-1` when [fromIndex] is not a slot at
     * all — a stale gesture can then never move the wrong tab.
     */
    fun dropIndex(fromIndex: Int, dragPx: Float, pitchPx: Float, count: Int): Int {
        if (count <= 0 || fromIndex !in 0 until count) return -1
        if (pitchPx <= 0f) return fromIndex
        val ratio = dragPx / pitchPx
        val steps = if (ratio >= 0f) floor(ratio + 0.5f) else ceil(ratio - 0.5f)
        return (fromIndex + steps.toInt()).coerceIn(0, count - 1)
    }
}
