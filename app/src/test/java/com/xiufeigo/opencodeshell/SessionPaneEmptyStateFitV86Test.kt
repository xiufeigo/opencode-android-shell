package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v86 P0 「720x1280 @420dpi 键盘弹起：空态文案只剩半截字，屏上读成『Build anvthing』」:
 * the empty state's one line has a floor of its own, and the shared arrangement's
 * timeline box has none.
 *
 * The frame the previous agent metered (`research/v85_verify/5554/03_created.png`,
 * `docs` = the v86 composer suite's own numbers):
 *
 * ```
 * 720x1280 @420dpi (2.625 px/dp), Gboard up (ime 284 dp)
 *   pane (body column post-inset)   67.05 dp
 *   composer ceiling                56.00 dp   (COMPOSER_MINIMAL_HEIGHT)
 *   shared arrangement, timeline box 11.05 dp   ← the empty state's own box
 *   empty state                     「Build anything」 13 sp, ~20 dp line
 * ```
 *
 * The label is `Alignment.Center` of that 11.05 dp box, so a 20 dp line hangs
 * ~4.475 dp past **each** end — the top slice out of the pane, the bottom slice
 * under the composer's opaque card — and the middle of the glyphs is all that
 * reaches the screen: the y's descender reads as "v". Sending one message
 * unloads the empty state, which is why the bug report says it disappears after
 * the first turn.
 *
 * What is pinned here is the decision the v74 note assumed away — the box is
 * `pane − composer` and nothing floors it — and the two properties the repair
 * needs: it hides under one line, and it is invisible (byte for byte unchanged)
 * on every frame where the label used to render whole.
 */
class SessionPaneEmptyStateFitV86Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val sessionScreen =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private val sessionPaneLayout =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt")

    /** The list's trailing gap — the floating form's reserve, not the label's box. */
    private val gap = 12.dp

    /** 720x1280 @420dpi with the keyboard up: the pane the v86 meter reads. */
    private val pane420 = 67.05.dp

    /** What the pane hands the composer there — the one-row form, whole. */
    private val composer420: Dp get() = SessionPaneLayout.composerCeiling(pane420)

    /** …and what the shared arrangement leaves the timeline: the label's box. */
    private val viewport420: Dp get() = (pane420 - composer420).coerceAtLeast(0.dp)

    // ------------------------------------------------------------ the bug frame

    @Test
    fun `the 420 keyboard frame hides the empty state`() {
        assertEquals(SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT, composer420)
        assertEquals(56.dp, composer420)
        // The composer is the pane's **sibling** here (it does not fit beside a
        // 64 dp band), so the timeline box is the pane minus the composer…
        assertTrue(SessionPaneLayout.composerSharesViewport(pane420, composer420))
        assertEquals(11.05f, viewport420.value, 0.001f)
        // …and the offset is not what is wrong: 0 is the shared form's correct
        // shift at every box height (the box *is* the band).
        assertEquals(
            0.dp,
            SessionPaneLayout.emptyStateShift(true, viewport420, composer420, gap),
        )
        // What is wrong is the box: 11.05 dp cannot hold the line, so the label
        // is not composed at all — the empty box, which is what this pane already
        // showed on the 480 dpi and v84 frames (0 dp boxes, pinned by
        // `SessionPaneImeBandsV81Test`).
        assertFalse(SessionPaneLayout.emptyStateFits(true, viewport420, composer420))
        assertTrue(viewport420 < SessionPaneLayout.EMPTY_STATE_LINE_HEIGHT)
        // The half-line the bug report saw, arithmetically: centred, the label
        // hangs (20 − 11.05) / 2 = 4.475 dp past each end of the box.
        assertEquals(
            4.475f,
            (SessionPaneLayout.EMPTY_STATE_LINE_HEIGHT - viewport420).value / 2f,
            0.001f,
        )
    }

    @Test
    fun `the frames the earlier repairs pinned still render it whole`() {
        // The v58/v74 measured pane (the floating form's own window): the label
        // renders exactly as it did — the guard is inert above one line, and its
        // room is 88.6 dp there.
        val measuredPane = 162.7.dp
        val measuredComposer = 58.1.dp
        assertFalse(SessionPaneLayout.composerSharesViewport(measuredPane, measuredComposer))
        assertTrue(SessionPaneLayout.emptyStateFits(false, measuredPane, measuredComposer))
        assertEquals(
            88.6f,
            SessionPaneLayout.emptyStateRoom(false, measuredPane, measuredComposer).value,
            0.01f,
        )
        // A roomy pane, a short pane on the other arrangement's side of the flip,
        // and a shared pane whose box *is* big enough: all three keep the label.
        assertTrue(SessionPaneLayout.emptyStateFits(false, 900.dp, 116.dp))
        assertTrue(SessionPaneLayout.emptyStateFits(false, 200.dp, 96.dp))
        // The flip's line is `viewport − composer < 64`, so a 170 dp pane with a
        // 116 dp composer is shared — and its box (54 dp) is still 2.7 lines of
        // label, which the guard keeps.
        assertTrue(SessionPaneLayout.composerSharesViewport(170.dp, 116.dp))
        assertTrue(SessionPaneLayout.emptyStateFits(true, 54.dp, 116.dp))
        // The pre-measure frame (the composer is not measured yet, so its height
        // is 0) is not hidden either — nothing opaque is over the box yet.
        assertTrue(SessionPaneLayout.emptyStateFits(false, 500.dp, 0.dp))
        // …and a zero box renders nothing, as it always did.
        assertFalse(SessionPaneLayout.emptyStateFits(true, 0.dp, 56.dp))
        assertFalse(SessionPaneLayout.emptyStateFits(false, 0.dp, 56.dp))
    }

    // ------------------------------------------------ the arithmetic's properties

    @Test
    fun `hiding starts under one line and never comes back`() {
        val composer = 56.dp
        var seen = false
        var viewport = 0.dp
        while (viewport <= 44.dp) {
            val fits = SessionPaneLayout.emptyStateFits(true, viewport, composer)
            if (viewport <= 0.dp) {
                assertFalse("a 0 dp box cannot hold a line", fits)
            } else {
                // The boundary is the line itself: 19.75 hides, 20 renders.
                assertEquals(
                    "viewport $viewport",
                    viewport >= SessionPaneLayout.EMPTY_STATE_LINE_HEIGHT,
                    fits,
                )
            }
            if (fits) seen = true else assertFalse("viewport $viewport re-hid the label", seen)
            viewport += 0.25.dp
        }
        assertTrue(seen)
        assertFalse(SessionPaneLayout.emptyStateFits(true, 19.75.dp, composer))
        assertTrue(SessionPaneLayout.emptyStateFits(true, 20.dp, composer))
    }

    @Test
    fun `the floating form can never hide it`() {
        // The flip keeps `viewport − composer >= TIMELINE_MIN_CONTENT` (64 dp), so
        // the room above the composer is at least 48 dp once the top step is out
        // — twice the line. No floating frame changes a pixel, on either the
        // measured composers or the whole sweep.
        var floating = 0
        var viewport = 100.dp
        while (viewport <= 900.dp) {
            var composer = 56.dp
            while (composer <= 220.dp) {
                if (!SessionPaneLayout.composerSharesViewport(viewport, composer)) {
                    floating++
                    assertTrue(
                        "floating viewport $viewport composer $composer",
                        SessionPaneLayout.emptyStateFits(false, viewport, composer),
                    )
                }
                composer += 2.dp
            }
            viewport += 5.dp
        }
        assertTrue("the sweep must have seen floating frames", floating > 1000)
    }

    @Test
    fun `the line the pane passes is the label at this font scale`() {
        // 13 sp at the default scale is the measured one-line box…
        assertEquals(20.dp, SessionPaneLayout.EMPTY_STATE_LINE_HEIGHT)
        // …and the caller scales it with the configuration's font scale, so 2×
        // type needs 2× the box and a box between the two readings hides.
        assertFalse(
            SessionPaneLayout.emptyStateFits(true, 30.dp, 56.dp, lineHeight = 40.dp),
        )
        assertTrue(
            SessionPaneLayout.emptyStateFits(true, 40.dp, 56.dp, lineHeight = 40.dp),
        )
        assertFalse(SessionPaneLayout.emptyStateFits(false, 60.dp, 56.dp, lineHeight = 40.dp))
        // The scaled line reaches the guard in the screen (below the empty-state
        // `if`, so the fallback block itself is untouched).
        val flat = sessionScreen.replace(Regex("\\s+"), " ")
        assertTrue(
            flat.contains(
                "val emptyStateLine = SessionPaneLayout.EMPTY_STATE_LINE_HEIGHT * " +
                    "density.fontScale",
            ),
        )
        assertTrue(flat.contains("lineHeight = emptyStateLine,"))
    }

    // --------------------------------------------------- the screen and the note

    @Test
    fun `the pane guards the fallback with the pure fit and adds no copy`() {
        val flat = sessionScreen.replace(Regex("\\s+"), " ")
        // The fallback is still there, and still the same resource — no new
        // user-visible string was added to hide a line (the guard hides, it does
        // not reword).
        assertTrue(flat.contains("if (state.messages.isEmpty())"))
        assertEquals(1, Regex("R\\.string\\.oc_timeline_empty").findAll(sessionScreen).count())
        // …and the label is now behind the fit decision, whose line is the
        // caller's own scaled reading.
        assertTrue(flat.contains("SessionPaneLayout.emptyStateFits("))
        // The v74 offset is still the only thing that moves the label.
        assertTrue(flat.contains("y = SessionPaneLayout.emptyStateShift("))
        // Nothing of the composer/arrangement geometry moved with it (the v86
        // weight policy is pinned by `ComposerOneRowEditorFloorV86Test`).
        assertTrue(
            flat.contains(
                ".let { if (shared) it.heightIn(max = composerCeiling) else it.fillMaxSize() },",
            ),
        )
        val paneFlat = sessionPaneLayout.replace(Regex("\\s+"), " ")
        assertTrue(
            paneFlat.contains(
                "fun emptyStateFits( shared: Boolean, viewport: Dp, composerHeight: Dp, " +
                    "lineHeight: Dp = EMPTY_STATE_LINE_HEIGHT, ): Boolean {",
            ),
        )
        assertTrue(
            paneFlat.contains(
                "fun emptyStateRoom(shared: Boolean, viewport: Dp, composerHeight: Dp): Dp {",
            ),
        )
    }

    @Test
    fun `the shift's note is aligned with the implementation`() {
        // v74's sentence — "the box there is never smaller than
        // TIMELINE_MIN_CONTENT" — was a premise of the *note*, not of the offset:
        // the implementation (0 in the shared form) is still what is wanted, and
        // the box the note described is the thing that was wrong. So the comment
        // is the half that was corrected, and it now names the decision that
        // covers the case it used to rule out.
        val shiftDoc = sessionPaneLayout
            .substringBefore("fun emptyStateShift(")
            .substringAfterLast("/**")
        assertTrue("the note must carry the v86 correction", shiftDoc.contains("v86 P0"))
        assertTrue(
            "the note must point at the fit decision",
            shiftDoc.contains("[emptyStateFits]"),
        )
        assertTrue(
            "…and at the box it happens to be about",
            shiftDoc.replace(Regex("\\s+"), " ").contains("11.05 dp"),
        )
        // The offset itself is untouched, at every box height (the shared form's
        // 0 is the band's own centre, and the floating form's is v74's).
        for (viewport in listOf(11.05.dp, 20.dp, 64.dp, 104.6.dp, 400.dp)) {
            assertEquals(
                "shared viewport $viewport",
                0.dp,
                SessionPaneLayout.emptyStateShift(true, viewport, 56.dp, gap),
            )
        }
        assertEquals(
            -27.05f,
            SessionPaneLayout.emptyStateShift(false, 162.7.dp, 58.1.dp, gap).value,
            0.01f,
        )
    }
}
