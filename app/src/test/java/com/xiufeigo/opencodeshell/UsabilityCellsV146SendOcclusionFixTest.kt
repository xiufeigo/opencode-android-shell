package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import com.xiufeigo.opencodeshell.ui.session.SessionTerminalEntryPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v146 — **cell 5 is fixed**: `Send` no longer covers `Open terminal`.
 *
 * v144 attributed cell 5 to a line and proved no z-order-only change could fix
 * it, then stopped because every lever moved a 48 dp target. v146's brief adds
 * the chip's **placement** to the writable scope, which makes the one lever that
 * touches neither a target's height nor the timeline's vertical budget available:
 * `SessionTerminalEntryPolicy.END_DP`, the chip's **horizontal** trailing anchor
 * (`SessionScreen.kt:3615`), moved 8 dp → 64 dp.
 *
 * ## What is pinned here, and what is *not*
 *
 * These tests do **not** assert a hard-coded "overlap is 0". They rebuild the
 * chip's touch box **from the live production constants** and let it meet the
 * archived device frames, so the six acceptance criteria are *evaluated* on
 * every run:
 *
 * | # | criterion | evaluated by |
 * |---|---|---|
 * | ① | `overlapArea == 0` | [criterion1OverlapAreaIsZero] |
 * | ② | `narrowestResidualPx ≥ 16` | [criterion2NarrowestResidualIsFingerSized] |
 * | ③ | `chipVisiblePixelCount > 0` | [criterion3TheChipHasVisiblePixels] |
 * | ④ | **both targets still 48 tall** | [criterion4BothTargetsStay48DpTall] |
 * | ⑤ | **768 form still 0 overlap** | [criterion5TheStackedFormStillHasZeroOverlap] |
 * | ⑥ | **both still clickable** | [criterion6BothTargetsRemainClickable] |
 *
 * ## The model is validated against the device, not assumed
 *
 * [theGeometryModelReproducesTheMeasuredPreFixBox] rebuilds the chip with the
 * **pre-fix** inset and asserts it reproduces the *measured* `[1320,192][1366,240]`
 * (46 px wide — clipped by the screen edge) to the pixel. Only then is the
 * post-fix box trusted. That same test carries v145's device readings
 * (`2068 / 2 / 0`) as **regression triggers**: if the geometry ever moves again
 * the archived frame stops matching the model and this goes red.
 *
 * ## What this class does NOT prove
 *
 * It is a JVM test: the post-fix box is **derived**, not photographed. The
 * pixel-level proof of criterion ③ (`chipVisiblePixelCount > 0` read off a real
 * screenshot) needs an installed build, which this round is not authorized to
 * produce — see `research/v97_fix/CELL5_FIX.md` §9.
 */
class UsabilityCellsV146SendOcclusionFixTest {

    // ================================================================ geometry

    private data class P(
        val desc: String,
        val clickable: Boolean,
        val enabled: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val index: Int,
    ) {
        val w: Int get() = right - left
        val h: Int get() = bottom - top
        val area: Int get() = w * h
        fun bounds() = "[$left,$top][$right,$bottom]"
        fun overlaps(o: P): Int {
            val ow = minOf(right, o.right) - maxOf(left, o.left)
            val oh = minOf(bottom, o.bottom) - maxOf(top, o.top)
            return if (ow > 0 && oh > 0) ow * oh else 0
        }

        /**
         * What is left of `this` once `o` is drawn on top. Unchanged from
         * v143/v144 (including their `min(w, h)` narrow-dimension rule) — with
         * no overlap the whole box survives and the narrow dimension is
         * `min(w, h)`, which is exactly the "nothing is occluded" case.
         */
        fun minus(o: P): List<Pair<Int, Int>> {
            val out = mutableListOf<Pair<Int, Int>>()
            val spanL = maxOf(left, o.left)
            val spanR = minOf(right, o.right)
            if (o.left > left) out += (o.left - left) to (bottom - top)
            if (o.right < right) out += (right - o.right) to (bottom - top)
            if (o.top > top) out += (spanR - spanL) to (o.top - top)
            if (o.bottom < bottom) out += (spanR - spanL) to (bottom - o.bottom)
            return out
        }

        fun narrowest(o: P): Int {
            val sl = minus(o)
            return if (sl.isEmpty()) minOf(w, h) else sl.maxOf { minOf(it.first, it.second) }
        }
    }

    private class F(relative: String) {
        val file: String = relative
        private val xml: String =
            listOf(File(relative), File("app/$relative"), File("../$relative"))
                .firstOrNull { it.isFile }?.readText()
                ?: throw AssertionError(
                    "v146: the device frame $relative is missing, so cell 5 cannot be " +
                        "re-adjudicated. This is a FAILURE, not a skip.",
                )
        val nodes: List<P> = Regex("<node\\b[^>]*>").findAll(xml).mapIndexed { i, m ->
            val t = m.value
            val b = Regex("bounds=\"\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]\"").find(t)
                ?: throw AssertionError("v146: unparsable node $t")
            val g = b.groupValues
            P(
                desc = Regex("content-desc=\"([^\"]*)\"").find(t)?.groupValues?.get(1) ?: "",
                clickable = Regex("clickable=\"([^\"]*)\"").find(t)?.groupValues?.get(1) == "true",
                enabled = Regex("enabled=\"([^\"]*)\"").find(t)?.groupValues?.get(1) == "true",
                left = g[1].toInt(), top = g[2].toInt(),
                right = g[3].toInt(), bottom = g[4].toInt(),
                index = i,
            )
        }.toList()

        /** The screen's own right edge, as measured on this frame. */
        val screenRight: Int get() = nodes.maxOf { it.right }
        val chip: P get() = nodes.single { it.desc == "Open terminal" }
        val send: P get() = nodes.single { it.desc == "Send" || it.desc == "Stop" }
    }

    // ------------------------------------------------------------------ model

    /**
     * The chip's 48 dp touch box, rebuilt from live production constants.
     *
     * `TerminalEntryButton` draws a 28 dp body and material3's
     * `minimumInteractiveComponentSize` grows the touch box to 48 dp, i.e.
     * [expandPx] per side; `padding(end = endInsetDp)` puts the **body's** right
     * edge `endInsetDp` inside the timeline pane's trailing edge. A body that
     * ends within 10 px of the screen edge therefore has its touch box **clipped**
     * — which is why the measured chip is 46 px wide and not 48.
     *
     * Vertical is taken from the frame, not modelled: the fix moves the
     * horizontal anchor only, so the chip's top/bottom are unchanged by it.
     */
    private fun chipTouchBox(
        paneRight: Int,
        screenRight: Int,
        endInsetDp: Float,
        top: Int,
        bottom: Int,
        index: Int,
    ): P {
        val expand = (SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX -
            SessionPaneLayout.CONTROL_FLOOR_PX) / 2
        val bodyRight = paneRight - endInsetDp.toInt()
        val unclippedRight = bodyRight + expand
        val left = unclippedRight - SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX
        val right = minOf(unclippedRight, screenRight)
        return P(
            desc = "Open terminal",
            clickable = true,
            enabled = true,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            index = index,
        )
    }

    /** The one-row (600) form's chip under the **current** production anchor. */
    private fun chipNow(oneRow: F): P {
        val pre = oneRow.chip
        // In the one-row form the timeline pane spans the full window width, so
        // the pane's trailing edge IS the screen's right edge.
        return chipTouchBox(
            paneRight = oneRow.screenRight,
            screenRight = oneRow.screenRight,
            endInsetDp = SessionTerminalEntryPolicy.END_DP.value,
            top = pre.top,
            bottom = pre.bottom,
            // v146 changed no z-order, so the chip keeps its place in the node
            // stream — the composer host is still composed second.
            index = pre.index,
        )
    }

    // ============================================================ ① overlap = 0

    @Test
    fun criterion1OverlapAreaIsZero() {
        val f = F(FRAME_600)
        val chip = chipNow(f)
        val send = f.send

        assertEquals(
            "v146 CELL 5 / **criterion 1**: the chip rebuilt from the live anchor " +
                "`SessionTerminalEntryPolicy.END_DP = ${SessionTerminalEntryPolicy.END_DP.value} dp` " +
                "is $chip, and `Send` is ${send.bounds()}. `overlapArea` must be **0** — the two " +
                "48 dp targets may no longer occupy the same rectangle. On v145's build this was " +
                "44 × 47 = **2068** px².",
            0,
            chip.overlaps(send),
        )
        assertTrue(
            "v146 CELL 5 / criterion 1: the boxes must be genuinely disjoint, not merely " +
                "non-overlapping by a rounding accident — `Send`'s left edge (${send.left}) has to " +
                "sit clear of the chip's right edge (${chip.right}).",
            send.left >= chip.right,
        )
    }

    // ================================================ ② residual narrow dimension

    @Test
    fun criterion2NarrowestResidualIsFingerSized() {
        val f = F(FRAME_600)
        val chip = chipNow(f)
        val send = f.send
        val narrowest = chip.narrowest(send)

        assertEquals(
            "v146 CELL 5 / **criterion 2**: with nothing over the chip, the whole box survives " +
                "and the narrow dimension is `min(w, h)` = ${minOf(chip.w, chip.h)} px. " +
                "v145 measured **2** px (a right sliver 1366 − 1364 plus a 1 px bottom sliver) " +
                "and 140 px² total.",
            0,
            chip.overlaps(send),
        )
        assertTrue(
            "v146 CELL 5 / criterion 2: `narrowestResidualPx` = $narrowest must be ≥ $TAPPABLE_PX. " +
                "The floor is deliberately far below the ~40 px adult contact patch so that a " +
                "'technically non-zero' sliver cannot pass as fixed.",
            narrowest >= TAPPABLE_PX,
        )
        assertEquals(
            "v146 CELL 5 / criterion 2: …and the chip is no longer clipped, so its touch box is " +
                "a whole 48 dp again (v145 measured 46).",
            SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX,
            chip.w,
        )
    }

    // ==================================================== ③ visible chip pixels

    @Test
    fun criterion3TheChipHasVisiblePixels() {
        val f = F(FRAME_600)
        val chip = chipNow(f)

        // Visibility, in the only form an a11y frame can answer it: the pixels
        // of the chip's own rectangle that no **later-drawn clickable target**
        // sits on top of. Full-screen background `View`s are excluded — they are
        // the pane's own surface, not something standing between the user and
        // the chip. Whether the chip is *painted* is the pixel question v145
        // answered on device (0 px), and the post-fix pixel count is the
        // remaining device step in `CELL5_FIX.md` §9.
        val covered = f.nodes.filter { it.clickable && it.index > chip.index && it.overlaps(chip) != 0 }
        val visible = chip.area - covered.sumOf { it.overlaps(chip) }

        assertEquals(
            "v146 CELL 5 / **criterion 3**: `chipVisiblePixelCount` must be > 0. Rebuilt from the " +
                "live anchor the chip is $chip (${chip.area} px²) and the later-drawn clickable " +
                "targets covering it are $covered. v145 measured **0 visible pixels** on the " +
                "pre-fix build, where the chip's 28 dp body lay entirely under `Send`'s.",
            chip.area,
            visible,
        )
        assertTrue(
            "v146 CELL 5 / criterion 3: a positive count, with the whole box reaching the user.",
            visible > 0,
        )
    }

    // ============================== ④ RED LINE — both targets stay 48 dp tall

    @Test
    fun criterion4BothTargetsStay48DpTall() {
        val f = F(FRAME_600)
        val chip = chipNow(f)
        val send = f.send

        assertEquals(
            "v146 CELL 5 / **criterion 4 (RED LINE)**: the fix is the chip's **horizontal** anchor " +
                "only. Its 48 dp target height comes from material3's " +
                "`minimumInteractiveComponentSize` (${SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX}" +
                " dp, read live) applied to its ${SessionPaneLayout.CONTROL_FLOOR_PX} dp body — " +
                "nothing in this fix touches either number, so the rebuilt chip must still be " +
                "${SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX} px tall.",
            SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX,
            chip.h,
        )
        assertEquals(
            "v146 CELL 5 / criterion 4: and `Send` must still be ${SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX} dp tall on the " +
                "device frame. **Shrinking either target is the forbidden way to close this cell** " +
                "— if this ever fails, the cell was closed by deleting the 48 dp semantics.",
            SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX,
            send.h,
        )
        assertEquals(
            "v146 CELL 5 / criterion 4: the vertical anchor `TOP_DP` is untouched by v146 — it is " +
                "still cell ④'s 2 px knob, and this fix deliberately did not turn it.",
            88f,
            SessionTerminalEntryPolicy.TOP_DP.value,
        )
    }

    // ============================ ⑤ RED LINE — the stacked form stays clear too

    @Test
    fun criterion5TheStackedFormStillHasZeroOverlap() {
        val f = F(FRAME_768)
        val chip = chipNow(f)
        val send = f.send

        assertEquals(
            "v146 CELL 5 / **criterion 5 (RED LINE)**: the 768 stacked form must stay at **0** " +
                "overlap — repairing the one-row form by moving the chip must not carry the defect " +
                "upward. Rebuilt chip $chip vs `Send` ${send.bounds()}.",
            0,
            chip.overlaps(send),
        )
        assertTrue(
            "v146 CELL 5 / criterion 5: the two forms separate for different reasons and both must " +
                "hold — the stacked form by **vertical** separation (chip bottom ${chip.bottom} " +
                "< `Send` top ${send.top}), the one-row form by **horizontal** (criterion 1). " +
                "v145 measured the stacked form's chip at [1320,192][1366,240] against " +
                "`Send` at [1308,292][1356,340].",
            chip.bottom < send.top || send.left >= chip.right,
        )
    }

    // ============================== ⑥ RED LINE — both actions still clickable

    @Test
    fun criterion6BothTargetsRemainClickable() {
        for (name in listOf(FRAME_600, FRAME_768)) {
            val f = F(name)
            assertTrue(
                "v146 CELL 5 / **criterion 6 (RED LINE)** on ${name.substringAfterLast('/')}: " +
                    "`${f.chip.desc}` must stay clickable (measured ${f.chip.clickable}).",
                f.chip.clickable,
            )
            assertTrue(
                "v146 CELL 5 / criterion 6: `${f.send.desc}` must stay clickable too " +
                    "(measured ${f.send.clickable}). The fix moves no `clickable`, no `enabled` " +
                    "and no z-order — it only separates the two rectangles.",
                f.send.clickable,
            )
        }
    }

    // ============================== regression triggers — v145's device readings

    /**
     * v145's three device readings, re-derived here as **triggers**.
     *
     * The model is first validated by rebuilding the chip with the **pre-fix**
     * inset and reproducing the *measured* pre-fix rectangle to the pixel. Only
     * a model that reproduces `46` and `[1320,1366]` is allowed to claim a
     * post-fix rectangle; and if a future change moves the geometry, the
     * archived frame stops matching and this test goes red instead of silently
     * validating a wrong model.
     */
    @Test
    fun theGeometryModelReproducesTheMeasuredPreFixBoxAndPinsV145Readings() {
        val f = F(FRAME_600)
        val measured = f.chip
        val send = f.send

        // --- the model, driven with the pre-fix inset it claims to reproduce ---
        val rebuilt = chipTouchBox(
            paneRight = f.screenRight,
            screenRight = f.screenRight,
            endInsetDp = PRE_FIX_END_DP,
            top = measured.top,
            bottom = measured.bottom,
            index = measured.index,
        )
        assertEquals(
            "v146: the chip's touch box is its 28 dp body grown to " +
                "${SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX} dp by material3 " +
                "(`expandPx` = ${(SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX - SessionPaneLayout.CONTROL_FLOOR_PX) / 2} per side) with the body's right " +
                "edge `endInsetDp` inside the pane. Driven with the pre-fix " +
                "$PRE_FIX_END_DP dp inset it must reproduce the **measured** pre-fix box exactly — " +
                "if it does not, the post-fix box this class trusts is a fiction.",
            measured.bounds(),
            rebuilt.bounds(),
        )
        assertEquals(
            "v146: …including its **46 px** width: `28 + 2×10 = 48` is clipped by the screen's " +
                "right edge because the body ends only 8 dp inside it. 46, not 48 — this clip is " +
                "the shape of the residual slivers v144 decomposed.",
            46,
            rebuilt.w,
        )

        // --- v145 reading ①: the occlusion itself ---
        val covered = measured.overlaps(send)
        assertEquals(
            "v146 **TRIGGER 2068**: v145 measured `Open terminal` ${measured.bounds()} " +
                "(${measured.w}×${measured.h}) overlapped by `${send.desc}` ${send.bounds()} by " +
                "44 × 47. If this stops being 2068 the archived frame changed and every " +
                "conclusion in `CELL5_FIX.md` must be re-derived from it.",
            44 * 47,
            covered,
        )

        // --- v145 reading ②: the 2 px residual ---
        val residual = measured.minus(send)
        assertEquals(
            "v146 **TRIGGER 2**: the residual decomposes into a right sliver " +
                "(${measured.right} − ${send.right} = ${measured.right - send.right} px wide) and " +
                "a bottom sliver (${measured.bottom} − ${send.bottom} = " +
                "${measured.bottom - send.bottom} px) — 140 px² in total, a **2 px** narrowest " +
                "dimension. This is the horizontal disagreement between the chip's trailing " +
                "anchor and the composer's own end inset, neither derived from the other.",
            listOf(2 to 48, 44 to 1),
            residual,
        )
        assertEquals(
            "v146 TRIGGER 2: 46 × 48 = 2208, 2208 − 2068 = 140 — the slivers account for the " +
                "whole uncovered area, so the decomposition is complete.",
            140,
            residual.sumOf { it.first * it.second },
        )
        assertEquals(
            "v146 TRIGGER 2: and the narrowest of them is **2 px**, which is the value the " +
                "acceptance floor of $TAPPABLE_PX rejects.",
            2,
            measured.narrowest(send),
        )

        // --- v145 reading ③: zero visible chip pixels ---
        //
        // Careful with the two vocabularies. The **frame** can only say that
        // 140 px² of the chip's rectangle escapes `${send.desc}`; it cannot say
        // what is *painted* there. The device said: nothing — the 2 px right
        // sliver (x ${send.right}..${measured.right}) and the 1 px bottom sliver
        // are plain background, and the chip's 28 dp body sits under `Send`'s.
        // So this pins the geometric half (which is decidable) and records the
        // painted half (which is the remaining device step) instead of pretending
        // bounds can produce a pixel count.
        val drawnAbove = f.nodes.filter { it.clickable && it.index > measured.index && it.overlaps(measured) != 0 }
        val geometricallyUncovered = measured.area - drawnAbove.sumOf { it.overlaps(measured) }
        assertEquals(
            "v146 **TRIGGER 0**: the only later-drawn clickable target over the pre-fix chip is " +
                "$drawnAbove, so what the frame leaves uncovered is the 140 px² of slivers — and " +
                "v145's raw screenshot showed that entire remainder (x ${send.right}..${measured.right}" +
                ", y ${send.bottom}..${measured.bottom}) is **background, not the chip**: pure " +
                "white R255 at y=205 and y=215. 140 px² of geometry, **0 px of chip**.",
            140,
            geometricallyUncovered,
        )
        assertEquals(
            "v146 TRIGGER 0: …and the 2 px the remainder is thinnest at is exactly the right sliver " +
                "`${send.right}..${measured.right}` — the column v145 read as white. A finger " +
                "cannot land on 2 px, so the pre-fix chip was not merely narrow, it was absent.",
            2,
            measured.right - send.right,
        )
        assertFalse(
            "v146 TRIGGER 0: and the node doing the covering was itself **disabled** — an " +
                "unusable button was burying a usable one. v145 §2.4 read this off the one-row " +
                "frame it dumped, `${FRAME_600_DISABLED_SEND.substringAfterLast('/')}`, where the " +
                "composer is empty; the geometry there is identical to ${FRAME_600.substringAfterLast('/')}" +
                " (both `[1320,192][1366,240]` over `[1316,191][1364,239]`), only the composer " +
                "state differs — with text in the editor the submit button is enabled again. " +
                "Pinning the frame it was measured on keeps the severity from being re-graded " +
                "down, and keeps this assertion from silently claiming `v140_600off.xml` says " +
                "otherwise.",
            F(FRAME_600_DISABLED_SEND).send.enabled,
        )

        // --- and the same model, driven with the CURRENT inset, must clear it ---
        val after = chipNow(f)
        assertTrue(
            "v146: the same model on the current anchor puts the chip at $after — no overlap with " +
                "a `Send` at ${send.bounds()}, against the 2068 px² the pre-fix anchor produced.",
            after.overlaps(send) == 0,
        )
    }

    // ============================ the anchors are read live, and stay independent

    @Test
    fun theFixTouchesOnlyTheHorizontalAnchorAndItIsReadLive() {
        assertEquals(
            "v146: `SessionTerminalEntryPolicy.END_DP` is the one knob this fix turned, and it is " +
                "read **live** from production, not copied into the test. 64 dp is the smallest " +
                "inset that separates the two 48 dp boxes by geometry with a gap to spare " +
                "(60 dp would leave them exactly touching; 56 dp would still overlap).",
            64f,
            SessionTerminalEntryPolicy.END_DP.value,
        )
        assertEquals(
            "v146: the timeline band the composer's ceiling keeps clear is still 64 dp — v146 " +
                "did not touch the vertical budget, so the timeline accounting is unchanged.",
            64f,
            SessionPaneLayout.TIMELINE_MIN_CONTENT.value,
        )

        // The defect existed because the two anchors were INDEPENDENT. Keep them
        // independent: if a future fix derives the chip from the composer, the
        // separation stops being a coincidence of numbers and becomes a coupling
        // that can silently re-collapse.
        val composer = readSource("app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
        assertFalse(
            "v146: `Composer.kt` must not reference `SessionTerminalEntryPolicy.END_DP`. The chip " +
                "and the composer are separate components, and v144's root cause was exactly that " +
                "their two anchors were unrelated; coupling them now would hide the next " +
                "divergence instead of preventing it.",
            composer.contains("END_DP"),
        )
        val screen = readSource("app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        assertTrue(
            "v146: the anchor must still be *applied* as the chip's trailing padding " +
                "(`SessionScreen.kt:2262`), not merely declared. If the modifier stopped using " +
                "it, the geometry model above would be describing a value nothing reads.",
            screen.contains("end = SessionTerminalEntryPolicy.END_DP,"),
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun readSource(rel: String): String =
        listOf(File(rel), File("../$rel"))
            .firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("v146: $rel is missing, so the wiring pin cannot run. " +
                "This is a FAILURE, not a skip.")

    private companion object {
        const val DIR = "research/v97_verify/5554_md/artifacts"

        /** The 600 one-row form — the frame cell 5 was measured on. */
        const val FRAME_600 = "$DIR/v140_600off.xml"

        /** The 768 stacked form — where cell 5 must NOT be carried upward. */
        const val FRAME_768 = "$DIR/v140_768.xml"

        /**
         * The same one-row form as [FRAME_600] — identical geometry — but with an
         * **empty** composer, which is the state v145 §2.4 dumped and the one in
         * which the occluding `Send` reads `enabled="false"`.
         */
        const val FRAME_600_DISABLED_SEND = "$DIR/B_CELL5_600_ONEROW.xml"

        /** `SessionScreen.kt:3615` as it stood before v146. */
        const val PRE_FIX_END_DP = 8f

        /** A surviving sliver must be at least this wide to take a finger. */
        const val TAPPABLE_PX = 16
    }
}
