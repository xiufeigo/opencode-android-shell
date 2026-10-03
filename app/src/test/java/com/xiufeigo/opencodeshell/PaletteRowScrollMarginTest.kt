package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.palette.PALETTE_ROW_SCROLL_MARGIN
import com.xiufeigo.opencodeshell.ui.palette.paletteRowScrollNudgePx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The follow-scroll must honour the row's `scroll-margin: 6px 0` (transcribed
 * from the bundled v2 CSS `dialog-command-palette-v2-*.css`,
 * `.command-palette-v2-row`): upstream's `scrollIntoView({ block: 'nearest' })`
 * keeps the active row 6px inside the viewport edge, while a bare
 * `scrollToItem(slot)` snaps it flush to the edge. The native follow snaps and
 * then nudges back 6dp via `scrollBy` ([paletteRowScrollNudgePx]).
 */
class PaletteRowScrollMarginTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun palette() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/palette/CommandPalette.kt")

    // ------------------------------------------------------------- the margin

    @Test
    fun upstreamsRowScrollMarginIsTranscribed() {
        assertEquals(6f, PALETTE_ROW_SCROLL_MARGIN, 0f)
    }

    // ------------------------------------------------------------------ nudge

    @Test
    fun nudgeIsTheNegativeMarginInPx() {
        assertEquals(-6f, paletteRowScrollNudgePx(density = 1f), 0f)
        assertEquals(-12f, paletteRowScrollNudgePx(density = 2f), 0f)
        assertEquals(-18f, paletteRowScrollNudgePx(density = 3f), 0f)
    }

    @Test
    fun nudgeHonoursACustomMargin() {
        assertEquals(-8f, paletteRowScrollNudgePx(density = 2f, marginDp = 4f), 0f)
    }

    @Test
    fun nudgeNeverScrollsForward() {
        // Zero margin or density means "no nudge"; negative inputs clamp too.
        assertEquals(0f, paletteRowScrollNudgePx(density = 2f, marginDp = 0f), 0f)
        assertEquals(0f, paletteRowScrollNudgePx(density = 0f), 0f)
        assertEquals(0f, paletteRowScrollNudgePx(density = -1f), 0f)
        assertEquals(0f, paletteRowScrollNudgePx(density = 2f, marginDp = -6f), 0f)
    }

    // --------------------------------------------------------------- the view

    @Test
    fun followScrollSnapsThenNudgesByTheMargin() {
        val body = palette()
        // `block:nearest`: only scroll when the active row left the viewport.
        assertTrue(body.contains("visibleItemsInfo.none { it.index == slot }"))
        assertTrue(body.contains("listState.scrollToItem(slot)"))
        assertTrue(body.contains("paletteRowScrollNudgePx(density.density)"))
        assertTrue(body.contains("listState.scrollBy("))
    }
}
