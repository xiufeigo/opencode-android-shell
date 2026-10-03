package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.palette.CATEGORY_COMMANDS
import com.xiufeigo.opencodeshell.ui.palette.PALETTE_GROUP_SPACING
import com.xiufeigo.opencodeshell.ui.palette.PALETTE_ROW_SPACING
import com.xiufeigo.opencodeshell.ui.palette.PaletteEntry
import com.xiufeigo.opencodeshell.ui.palette.groupEntries
import com.xiufeigo.opencodeshell.ui.palette.lazyRowIndices
import com.xiufeigo.opencodeshell.ui.palette.paletteSlotSpacings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The palette's results list is spaced in **two** dimensions upstream:
 * `.command-palette-v2-results { gap:16px }` between groups and
 * `.command-palette-v2-group { gap:1px }` between the rows of one group
 * (`research/02_ui_screens.md` §6.5, `_upstream/.../shell/commands/dialog.css`).
 *
 * The native list carried a single `Arrangement.spacedBy(16.dp)`, which is
 * uniform: every row inside a group sat 16dp from its neighbour, so a group read
 * as a run of one-row groups and the list was far taller than upstream's. A
 * `LazyColumn`'s arrangement cannot vary per slot, so the gap is computed per
 * slot ([paletteSlotSpacings]) and applied on the item itself.
 */
class PaletteGroupSpacingTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun palette() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/palette/CommandPalette.kt")

    private fun command(id: String, category: String = CATEGORY_COMMANDS) =
        PaletteEntry.command(id = id, title = id, category = category)

    // ------------------------------------------------------------- the two gaps

    @Test
    fun upstreamsTwoGapsAreTranscribed() {
        assertEquals(16f, PALETTE_GROUP_SPACING, 0f)
        assertEquals(1f, PALETTE_ROW_SPACING, 0f)
    }

    @Test
    fun theFirstGroupTitleOpensTheListWithNoGap() {
        val grouped = groupEntries(listOf(command("a"), command("b")))
        // [title Commands, a, b] — nothing above the list's own first item.
        assertEquals(listOf(0f, 1f, 1f), paletteSlotSpacings(grouped))
    }

    @Test
    fun everyLaterGroupIsPushedSixteenAwayFromTheOneAbove() {
        val grouped = groupEntries(
            listOf(command("a"), command("b"), PaletteEntry.file("x.kt"), PaletteEntry.session("srv", "s1", "s")),
        )
        // [title Commands, a, b, title Files, x.kt, title Session, s]
        assertEquals(listOf(0f, 1f, 1f, 16f, 1f, 16f, 1f), paletteSlotSpacings(grouped))
    }

    @Test
    fun theGapOnlyEverAppearsOnAGroupTitle() {
        val grouped = groupEntries(
            listOf(command("a"), command("b"), command("c"), PaletteEntry.file("x.kt"), PaletteEntry.file("y.kt")),
        )
        val spacings = paletteSlotSpacings(grouped)
        // A 16px slot is always a title and a 1px slot always a row: five rows
        // and two titles — the gap above the *second* title (`1`), the rows
        // (`5`), and the opening title's own slot at 0.
        assertEquals(1, spacings.count { it == PALETTE_GROUP_SPACING })
        assertEquals(5, spacings.count { it == PALETTE_ROW_SPACING })
        assertEquals(0f, spacings.first(), 0f)
    }

    @Test
    fun anEmptyListHasNoSlots() {
        assertEquals(emptyList<Float>(), paletteSlotSpacings(emptyList()))
    }

    // -------------------------------------------------- agreement with the slots

    @Test
    fun slotSpacingsCoverExactlyTheLazySlots() {
        val grouped = groupEntries(
            listOf(command("a"), command("b"), PaletteEntry.file("x.kt")),
        )
        val indices = lazyRowIndices(grouped)
        val spacings = paletteSlotSpacings(grouped)
        // [title Commands, a, b, title Files, x.kt]
        assertEquals(listOf(1, 2, 4), indices.values.sorted())
        // One spacing per lazy slot, so a slot index from `lazyRowIndices` (the
        // follow-scroll's target) indexes this list directly.
        assertEquals(indices.values.max() + 1, spacings.size)
        // Every row's slot keeps the 1px inner gap…
        for (slot in indices.values) assertEquals(PALETTE_ROW_SPACING, spacings[slot], 0f)
        // …and the slot right above a group's first row is its title: 0 for the
        // group that opens the list, 16 for the one after it.
        assertEquals(0f, spacings[0], 0f)
        assertEquals(PALETTE_GROUP_SPACING, spacings[3], 0f)
    }

    // --------------------------------------------------------------- the view

    @Test
    fun theListAppliesTheGapPerSlotInsteadOfOneUniformArrangement() {
        val body = palette()
        assertTrue(body.contains("paletteSlotSpacings(grouped)"))
        // The uniform 16dp arrangement was the bug; `LazyColumn` keeps its
        // default top arrangement and each item carries its own gap.
        assertEquals(0, Regex("Arrangement\\.spacedBy\\(16\\.dp\\)").findAll(body).count())
        // Both item kinds take that gap from the model — the title and the row.
        assertEquals(2, Regex("topSpacing = ").findAll(body).count())
        // `.command-palette-v2-results { padding: 6px 6px 8px }` — the top 6px
        // was missing too, which put the first title against the search field.
        assertTrue(body.contains("PaddingValues(start = 6.dp, top = 6.dp, end = 6.dp, bottom = 8.dp)"))
    }
}
