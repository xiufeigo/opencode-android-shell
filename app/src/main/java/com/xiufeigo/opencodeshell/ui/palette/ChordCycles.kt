package com.xiufeigo.opencodeshell.ui.palette

import com.xiufeigo.opencodeshell.OpenCodeClient

/**
 * B-5: the pure half of the two composer-cycle chords, transcribed so
 * `:app:testDebugUnitTest` pins them without a device.
 *
 * Both were previously palette rows with no runtime action; the chord now calls
 * the same `ChatViewModel.selectAgent` / `selectVariant` the chips use, with the
 * **next** value chosen here.
 */
object ChordCycles {

    /**
     * `agent.move(direction)` (`providers/models/selection.tsx:217-233`):
     * `next = items.findIndex(name) + direction`, wrapped into the list; an
     * unknown current name counts as -1, so `+1` picks the first agent and `-1`
     * the last. An empty list answers null — upstream clears the selection
     * (`setStore("current", undefined)`), and the shell does the same by passing
     * null to `selectAgent`.
     *
     * `items` is `OpenCodeClient.Agent.selectable` — the same list the chip
     * renders, so the chord can never land on an agent the user cannot see.
     */
    fun nextAgent(agents: List<OpenCodeClient.Agent>, current: String?, direction: Int): String? {
        if (direction == 0) return current
        val items = OpenCodeClient.Agent.selectable(agents)
        if (items.isEmpty()) return null
        val step = if (direction > 0) 1 else -1
        var next = items.indexOfFirst { it.name == current } + step
        if (next < 0) next = items.lastIndex
        if (next > items.lastIndex) next = 0
        return items[next].name
    }

    /**
     * `cycleModelVariant` (`providers/models/variant.ts:37-40`):
     * `variants[current ? indexOf(current) + 1 : 0]` — stepping past the last
     * variant answers `undefined`, which is the "inherit the server/agent
     * default" value (`variant.ts:31-35`), so the cycle ends on the default
     * instead of wrapping.
     *
     * [variants] is the model's advertized list (the keys of
     * `Model.variants`), not the picker's `["default"] + variants` — the picker
     * label is not a variant.
     */
    fun nextVariant(variants: List<String>, selected: String?): String? {
        if (variants.isEmpty()) return null
        val current = selected?.takeIf { it.isNotBlank() && it in variants }
        val index = if (current == null) 0 else variants.indexOf(current) + 1
        return variants.getOrNull(index)
    }
}
