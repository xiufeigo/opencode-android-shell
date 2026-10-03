package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.OpenCodeClient.Agent
import com.xiufeigo.opencodeshell.ui.palette.ChordCycles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B-5: the two composer-cycle chords, pinned against upstream's own rules —
 * `agent.move(direction)` (`providers/models/selection.tsx:217-233`) and
 * `cycleModelVariant` (`providers/models/variant.ts:37-40`).
 */
class ChordCyclesTest {

    private fun agent(name: String, mode: String = "primary", hidden: Boolean = false) =
        Agent(name = name, description = "", mode = mode, native = true, hidden = hidden)

    @Test
    fun agentCycleWalksTheSelectableListAndWraps() {
        val agents = listOf(agent("build"), agent("plan"), agent("sub"))
        assertEquals("plan", ChordCycles.nextAgent(agents, "build", 1))
        assertEquals("build", ChordCycles.nextAgent(agents, "sub", 1))
        assertEquals("sub", ChordCycles.nextAgent(agents, "build", -1))
        assertEquals("plan", ChordCycles.nextAgent(agents, "sub", -1))
    }

    @Test
    fun agentCycleSkipsSubagentsAndHiddenEntries() {
        // `selectable` is the chip's list: primary (or v2's "all"), not hidden,
        // named — the chord must never land on an agent the user cannot see.
        val agents = listOf(
            agent("build"),
            agent("hidden-one", hidden = true),
            agent("subagent", mode = "subagent"),
            agent("plan"),
        )
        assertEquals("plan", ChordCycles.nextAgent(agents, "build", 1))
        assertEquals("build", ChordCycles.nextAgent(agents, "plan", 1))
    }

    @Test
    fun agentCycleWithAnUnknownCurrentStartsAtTheFirstEntry() {
        // `findIndex` answers -1, so `+1` is index 0 — and `-1` wraps to the last.
        val agents = listOf(agent("build"), agent("plan"))
        assertEquals("build", ChordCycles.nextAgent(agents, null, 1))
        assertEquals("plan", ChordCycles.nextAgent(agents, "gone", -1))
    }

    @Test
    fun agentCycleOnAnEmptyCatalogClearsTheSelection() {
        // Upstream `setStore("current", undefined)`; here null means "no pinned
        // agent", which is what `selectAgent(null)` writes.
        assertNull(ChordCycles.nextAgent(emptyList(), "build", 1))
        assertEquals("build", ChordCycles.nextAgent(listOf(agent("build")), "build", 0))
    }

    @Test
    fun variantCycleStepsThroughTheModelsOwnVariantsAndEndsOnDefault() {
        val variants = listOf("low", "high")
        // No pinned variant → the first one.
        assertEquals("low", ChordCycles.nextVariant(variants, null))
        assertEquals("high", ChordCycles.nextVariant(variants, "low"))
        // Past the last variant upstream answers undefined = "inherit the
        // server/agent default"; the cycle ends there instead of wrapping.
        assertNull(ChordCycles.nextVariant(variants, "high"))
    }

    @Test
    fun variantCycleIgnoresAStalePinnedLabel() {
        // A label the model no longer advertises is not "current": the cycle
        // restarts at the first variant (`resolveModelVariant` returns undefined).
        assertEquals("low", ChordCycles.nextVariant(listOf("low", "high"), "medium"))
        // No variants at all: nothing to cycle, so the selection stays default.
        assertNull(ChordCycles.nextVariant(emptyList(), "low"))
    }
}
