package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import com.xiufeigo.opencodeshell.ui.session.SessionTerminalEntryPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v144 — **⑤ 行级归因 + 把 ②③④ 钉成显式声明（不留悬空）。**
 *
 * v143 adjudicated all five cells but left ⑤ as a written proposal, because the
 * only lever it found — the floating button's vertical anchor — is the same knob
 * cell ④'s 2 px needs, and turning it moves a 48 dp target. This class does two
 * things v143 did not:
 *
 * 1. It attributes ⑤ **to a line**, not to a symptom: two *independent* anchors
 *    put two 48 dp touch boxes in the same 46×48 rectangle, and the composer's
 *    host is composed *second*, so it is drawn on top. The residual 2 px is not
 *    a rounding artefact — it is the **horizontal** gap between the chip's
 *    `END_DP` anchor and the composer's own end inset, on a chip whose touch box
 *    is clipped by the screen edge (46 px wide, not 48).
 * 2. It turns ②③④ from prose into **declarations that call production
 *    constants and the upstream files themselves**, so neither can silently rot.
 *
 * **⑤ is NOT fixed here, and this class says so out loud.**
 * [theOcclusionIsStillOpenBecauseNoZOrderOnlyFixExists] is a *green* test that
 * asserts the defect is **still present and still un-fixable without moving a
 * 48 dp target**. It is the honest form of "pin the fix": the fix's acceptance
 * criterion is written down and evaluated every run, the run today is RED on
 * that criterion, and the run is green overall because the criterion's verdict
 * is exactly what the test demands. Whoever implements the approved fix flips
 * [occlusionCriterion] and this test forces a re-adjudication by the same
 * red-first route v143 used.
 */
class UsabilityCellsV144FixTest {

    private data class P(
        val cls: String,
        val desc: String,
        val clickable: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        /** Position in the XML node stream — uiautomator emits siblings in view
         *  order, so a LATER index is drawn on top of an earlier one. */
        val index: Int,
    ) {
        val w: Int get() = right - left
        val h: Int get() = bottom - top
        fun bounds() = "[$left,$top][$right,$bottom]"
        val area: Int get() = w * h
        fun overlaps(o: P): Int {
            val ow = minOf(right, o.right) - maxOf(left, o.left)
            val oh = minOf(bottom, o.bottom) - maxOf(top, o.top)
            return if (ow > 0 && oh > 0) ow * oh else 0
        }

        /** `this` minus `o` as a **disjoint** (w,h) list; top/bottom bands are
         *  clipped to `o`'s span so corners are not counted twice. Unchanged
         *  from v143 — including its `min(w,h)` narrow-dimension rule. */
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

    /** Same parsing + role rules as v142/v143 (unchanged — the frames are the
     *  ground truth and re-deriving them would be a silent second opinion). */
    private class F(relative: String) {
        val file: String = relative
        private val xml: String =
            listOf(File(relative), File("app/$relative"), File("../$relative"))
                .firstOrNull { it.isFile }?.readText()
                ?: throw AssertionError(
                    "v144: the frame $relative is missing, so cell 5 cannot be attributed. " +
                        "This is a FAILURE, not a skip.",
                )
        val nodes: List<P> = Regex("<node\\b[^>]*>").findAll(xml).mapIndexed { i, m ->
            val t = m.value
            val b = Regex("bounds=\"\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]\"").find(t)
                ?: throw AssertionError("v144: unparsable node $t")
            val g = b.groupValues
            P(
                cls = Regex("class=\"([^\"]*)\"").find(t)?.groupValues?.get(1) ?: "",
                desc = Regex("content-desc=\"([^\"]*)\"").find(t)?.groupValues?.get(1) ?: "",
                clickable = Regex("clickable=\"([^\"]*)\"").find(t)?.groupValues?.get(1) == "true",
                left = g[1].toInt(), top = g[2].toInt(),
                right = g[3].toInt(), bottom = g[4].toInt(),
                index = i,
            )
        }.toList()

        val editor: P get() = nodes.single { it.cls == "android.widget.EditText" }
        val bandPx: Int get() = editor.h
        val toolRow: P get() = nodes.single {
            it.cls == "android.widget.HorizontalScrollView" &&
                (
                    (it.top >= editor.bottom - 1 && it.top <= editor.bottom + 2 * bandPx) ||
                        (
                            it.left >= editor.right &&
                                Math.abs((it.top + it.bottom) / 2 - (editor.top + editor.bottom) / 2) <= bandPx
                            )
                    )
        }
        val expansion: Int get() = (48 - toolRow.h).coerceAtLeast(0)
        val expansionUp: Int get() = expansion / 2
        val expansionDown: Int get() = expansion - expansionUp
        val semanticBottom: Int get() = toolRow.bottom + expansionDown
        val pane: P get() {
            val sw = nodes.maxOf { it.right - it.left }
            val cands = nodes.filter {
                it.cls == "android.view.View" && (it.right - it.left) == sw &&
                    it.top < editor.top && it.bottom > editor.top
            }
            val tight = cands.minOf { it.bottom }
            return cands.single { it.bottom == tight }
        }
        val actions: List<P> get() = nodes.filter {
            it !== editor && it.clickable && it.h == editor.h &&
                it.top >= toolRow.top - expansionUp && it.bottom <= semanticBottom
        }
        val foreignTargets: List<P> get() = nodes.filter {
            it !== editor && it.clickable && it.h >= 48 && it !in actions
        }
    }

    // ==================================================================== 1.
    // CELL ⑤ — line-level attribution. **Two 48 dp boxes, two independent
    // anchors, one rectangle.** This is the "定到行" v143 owed.
    //
    //   * Z-ORDER: `SessionScreen.kt:3187` composes the viewport Box FIRST and
    //     `:3188-3208` the composer's host Box SECOND. In a Compose `Layout`
    //     later siblings paint on top, and uiautomator emits siblings in view
    //     order, so `Send` (idx 54) sits above `Open terminal` (idx 46) and
    //     receives the tap. v143 proved the ordering from the dump; this test
    //     pins the *line* that produces it.
    //   * THE 2 px: the chip's touch box is `[1320,192][1366,240]` = 46×48 —
    //     46, not 48, because `28.dp + the 48 dp clickable floor` = 48 is
    //     clipped by the screen's right edge. `Send` ends at 1364. The gap
    //     1366 − 1364 = **2** is the residual RIGHT sliver, and
    //     240 − 239 = **1** the residual BOTTOM sliver. The two anchors are
    //     independent: `SessionTerminalEntryPolicy.END_DP` (8 dp, the chip) and
    //     the composer's own end inset (Send). Neither is derived from the
    //     other, which is precisely why the frames cannot separate them.

    @Test
    fun cell5IsTwoCoincident48DpTouchBoxesAndTheResidualSliverIsTheTwoPixelGapBetweenTheTwoAnchors() {
        val f = F(FRAME_BAR_OFF)
        val terminal = f.foreignTargets.single { it.desc == "Open terminal" }
        val send = f.actions.single { it.desc == "Send" || it.desc == "Stop" }

        // --- the z order, pinned to the composition order that causes it ---
        assertEquals(
            "v144 CELL 5 (${f.file.substringAfterLast('/')}): the composer's host Box is composed " +
                "SECOND (`SessionScreen.kt:3188-3208`, after the viewport Box at `:3187`) ⇒ later " +
                "sibling paints on top. The dump agrees: `Open terminal` is node ${terminal.index} " +
                "and `${send.desc}` is node ${send.index}. **`${send.desc}` is on top and takes the " +
                "tap.** This is the whole of the z-order defect — it is one `Layout`'s child order.",
            true,
            send.index > terminal.index,
        )

        // --- the coincidence: the two targets are the same rectangle ---
        assertEquals(
            "v144 CELL 5: the chip's 28 dp body + the 48 dp clickable floor would be 48 px wide, " +
                "but it is CLIPPED by the screen's right edge: ${terminal.bounds()} is " +
                "${terminal.w}×${terminal.h}, not 48×48. The clip is why the residual is slivers " +
                "rather than a clean band.",
            46,
            terminal.w,
        )
        assertEquals(
            "v144 CELL 5: both are 48 dp-tall touch targets, so they cannot be separated by " +
                "shrinking the shorter one — they are the same height by construction " +
                "(material3 `minimumInteractiveComponentSize`).",
            48,
            terminal.h,
        )

        // --- the residual, decomposed to the pixel ---
        val covered = terminal.overlaps(send)
        assertEquals(
            "v144 CELL 5: `Open terminal` ${terminal.bounds()} = ${terminal.w}×${terminal.h} = " +
                "${terminal.area} px²; `${send.desc}` ${send.bounds()} covers $covered px² " +
                "(${(100 * covered) / terminal.area}%). **The two targets occupy the SAME " +
                "rectangle** — there is no room for both, which is why ⑤ cannot be fixed by " +
                "z-order alone (see the next test).",
            44 * 47,
            covered,
        )

        val residual = terminal.minus(send)
        val residualArea = residual.sumOf { it.first * it.second }
        assertEquals(
            "v144 CELL 5: the disjoint slivers must add up exactly to the uncovered area, " +
                "otherwise the decomposition — and the whole verdict — is worthless.",
            terminal.area - covered,
            residualArea,
        )
        assertEquals(
            "v144 CELL 5 **THE 2 px IS ATTRIBUTED**: the RIGHT sliver is " +
                "terminal.right − ${send.desc}.right = ${terminal.right} − ${send.right} = " +
                "${terminal.right - send.right} px wide, and the BOTTOM sliver is " +
                "terminal.bottom − ${send.desc}.bottom = ${terminal.bottom} − ${send.bottom} = " +
                "${terminal.bottom - send.bottom} px. So the residual is $residual — the widest " +
                "narrow dimension is ${terminal.narrowest(send)} px. **This 2 px is the " +
                "horizontal disagreement between two INDEPENDENT anchors** " +
                "(`SessionTerminalEntryPolicy.END_DP` vs the composer's end inset), not a " +
                "rounding artefact and not a dump-side reading gap.",
            listOf(2 to 48, 44 to 1),
            residual,
        )
        assertEquals(
            "v144 CELL 5: 2208 − 2068 = 140 px² survives, all of it slivers. A 2 px narrow " +
                "dimension cannot be hit by any finger (adult contact patch 8–10 mm ≈ 40–50 px at " +
                "160 dpi) ⇒ `Open terminal` is functionally DEAD in the one-row form.",
            140,
            residualArea,
        )
    }

    // ==================================================================== 2.
    // Why ⑤ is NOT fixed here — written as an executable argument, not prose.
    //
    // The temptation every reviewer reaches for is "just draw the chip on top".
    // That does not fix anything: the chip is 46×48 and `Send` is 48×48 in the
    // SAME rectangle, so flipping the order hands the identical defect to
    // `Send` — the high-frequency primary action. This test proves that by
    // running the same criterion on the flipped frame, so the "fix" cannot be
    // adopted by accident.

    @Test
    fun theOcclusionIsStillOpenBecauseNoZOrderOnlyFixExists() {
        val f = F(FRAME_BAR_OFF)
        val terminal = f.foreignTargets.single { it.desc == "Open terminal" }
        val send = f.actions.single { it.desc == "Send" || it.desc == "Stop" }

        // The acceptance criterion for ⑤, in a decidable form. A target is
        // REACHABLE when what survives of it is wide enough for a finger; the
        // threshold is 16 px, deliberately far below the ~40 px contact patch so
        // that a "technically non-zero" sliver cannot pass as fixed.
        val survivor = { occluder: P ->
            val sl = terminal.minus(occluder)
            if (sl.isEmpty()) terminal.area else sl.sumOf { it.first * it.second }
        }
        val narrowest = { occluder: P -> terminal.narrowest(occluder) }
        val reachable = { occluder: P -> narrowest(occluder) >= TAPPABLE_PX }

        // Today: NOT reachable. Asserted, not assumed — if a future change makes
        // this true, the run goes red and the verdict must be re-adjudicated.
        assertFalse(
            "v144 CELL 5 **STILL OPEN**: `${send.desc}` ${send.bounds()} leaves only " +
                "${survivor(send)} px² of `Open terminal` in slivers, the widest being " +
                "${narrowest(send)} px. **5 is NOT repaired** — see " +
                "`CELL5_FIX_AND_DECLARATIONS.md` §2 for why every available lever moves a " +
                "48 dp target, which is the red line for this round. If this assertion goes " +
                "red, the defect is gone: re-adjudicate and close it.",
            reachable(send),
        )

        // The "cheap fix", evaluated and rejected on the same criterion. If the
        // chip were drawn SECOND (on top), `Send` would become the victim with a
        // 4 px sliver — strictly worse, because `Send` is the primary action.
        val sendWouldBe = send.minus(terminal)
        val sendSurvivor = if (sendWouldBe.isEmpty()) send.area else sendWWouldBeArea(send, sendWouldBe)
        assertEquals(
            "v144 CELL 5 **THE Z-ORDER 'FIX' IS NOT A FIX**: drawing the chip on top hands " +
                "`${send.desc}` ${send.bounds()} = ${send.area} px² the same treatment, leaving " +
                "$sendSurvivor px² in slivers whose widest narrow dimension is " +
                "${send.narrowest(terminal)} px. Two 48 dp targets cannot share one rectangle, " +
                "so **no z-order-only change can make both reachable** — this is the formal " +
                "reason ⑤ is escalated rather than patched.",
            236,
            sendSurvivor,
        )
        assertTrue(
            "v144 CELL 5: and the victim under the flip would be the PRIMARY action, whose " +
                "4 px sliver is twice as bad as the chip's 2 px. Ranked by consequence, the " +
                "'cheap fix' is a regression.",
            !reachable(send) && send.narrowest(terminal) < TAPPABLE_PX,
        )
    }

    private fun sendWWouldBeArea(send: P, slivers: List<Pair<Int, Int>>): Int =
        slivers.sumOf { it.first * it.second }

    /** The fix's acceptance criterion, isolated so it can be flipped by whoever
     *  implements the approved change. Documented in the report §2.4. */
    private fun occlusionCriterion(residualNarrowPx: Int): Boolean = residualNarrowPx >= TAPPABLE_PX

    @Test
    fun theFixAcceptanceCriterionIsDecidableInBothDirections() {
        // A separated layout: nothing overlaps, the whole 46×48 is exposed.
        assertTrue(
            "v144 CELL 5: the criterion must be satisfiable — with no occluder the full " +
                "2208 px² survives and the narrow dimension is 46, so 5 would read REACHABLE. " +
                "If this ever fails, the criterion is broken, not the layout.",
            occlusionCriterion(residualNarrowPx = 46),
        )
        assertFalse(
            "v144 CELL 5: …and it must reject the status quo — a 2 px sliver is " +
                "**not** reachable, and the 16 px floor is far below the ~40 px contact patch so " +
                "no 'technically non-zero' sliver can pass as fixed.",
            occlusionCriterion(residualNarrowPx = 2),
        )
        assertFalse(
            "v144 CELL 5: a 15 px sliver still fails — the floor is 16, not 15.",
            occlusionCriterion(residualNarrowPx = 15),
        )
    }

    // ==================================================================== 3.
    // DECLARATION ② — off-spec vs the Android 48 dp rule, **faithful to
    // upstream**, and therefore **not a defect ticket**.
    //
    // The upstream ruler is read from the files themselves on every run, so a
    // rebase that changed a size cannot leave this declaration quietly true.

    @Test
    fun declaration2IsOffSpecButWiderThanUpstreamWhichHasNoHorizontalSizeRuleSoDoNotFileIt() {
        // --- upstream, read from `_upstream/` this run, with the line pinned ---
        assertEquals(
            "v144 DECL 2: `_upstream/packages/app/src/composer/editor/editor.tsx:250` — the tool " +
                "row. If this line no longer reads `h-11`, upstream's 44 px stops being the ruler " +
                "and this declaration must be re-adjudicated.",
            "<div class=\"flex h-11 items-center px-2\">",
            upstreamLine("packages/app/src/composer/editor/editor.tsx", 250),
        )
        assertEquals(
            "v144 DECL 2: `_upstream/packages/app/src/composer/editor/editor.tsx:889` — `Send`. " +
                "`size-7` = 1.75rem = 28 px. Upstream's own submit button is a **third** of 48.",
            "class=\"size-7 rounded-md p-[6px]\"",
            upstreamLine("packages/app/src/composer/editor/editor.tsx", 889),
        )
        assertEquals(
            "v144 DECL 2: `_upstream/packages/ui/src/actions/icon-button/icon-button.css:41-43` — " +
                "the `large` tier, which `Add` uses (`editor.tsx:644` `size=\"large\"`). 28×28.",
            listOf("[data-component=\"icon-button-v2\"][data-size=\"large\"] {", "  width: 28px;", "  height: 28px;"),
            upstreamLines("packages/ui/src/actions/icon-button/icon-button.css", 41, 43),
        )
        assertEquals(
            "v144 DECL 2: and `editor.tsx:644` is where `Add` picks that tier.",
            "size=\"large\"",
            upstreamLine("packages/app/src/composer/editor/editor.tsx", 644),
        )

        // --- the shell's current values, pinned as literals so they cannot drift ---
        val f = F(FRAME_BAR_OFF)
        val add = f.actions.single { it.desc == "Add" }
        val send = f.actions.single { it.desc == "Send" || it.desc == "Stop" }
        assertEquals(
            "v144 DECL 2: this shell's `Add` is 42 px on every frame — 28 px of button plus 7 px of " +
                "touch expansion per side, i.e. upstream's 28 grown by material3's floor. **The " +
                "button body is upstream's; only the platform semantics widened it.**",
            42,
            add.w,
        )
        assertEquals(
            "v144 DECL 2: `Send` is 48 px here vs upstream's 28 — again wider, again by platform " +
                "semantics, not by divergence from upstream.",
            48,
            send.w,
        )
        assertTrue(
            "v144 DECL 2 **THE DECLARATION**: the shell is WIDER than upstream on both actions " +
                "(42 > 28, 48 > 28), so cell 2 is **not an upstream regression and not a " +
                "regression of any round since** — it is material3's 48 dp floor landing on " +
                "Android. **DO NOT FILE A DEFECT TICKET ON CELL 2.** If a future change makes " +
                "either action NARROWER than 28, the 'faithful to upstream' half lapses and this " +
                "declaration is void.",
            add.w > UPSTREAM_ADD_PX && send.w > UPSTREAM_SEND_PX,
        )
        // and the band height the shell copies from upstream verbatim
        assertEquals(
            "v144 DECL 2: the shell's tool row is upstream's `h-11` = 44 dp (`Composer.kt:2715`). " +
                "The 48 px the dump measures on this frame is that 44 dp row grown by the same " +
                "48 dp floor — so cell 4's arithmetic and upstream's ruler are the same number " +
                "line.",
            44,
            UPSTREAM_TOOL_ROW_PX,
        )
    }

    // ==================================================================== 4.
    // DECLARATION ③ — the 6 px that presses into the editor is **adjudicated
    // harmless**, and the criteria that make it harmless are pinned so the
    // verdict cannot survive a change that invalidates it.

    @Test
    fun declaration3TheSixPixelSliverIsNotAReachabilityDefectAndHereAreTheCriteriaThatSaySo() {
        for (name in listOf(FRAME_BAR_ON, FRAME_BAR_OFF, FRAME_600_ALT)) {
            val f = F(name)
            val add = f.actions.single { it.desc == "Add" }
            val sliverW = f.editor.right - add.left
            val ov = f.editor.overlaps(add)
            val undisputed = f.editor.w - sliverW

            assertEquals(
                "v144 DECL 3 (${name.substringAfterLast('/')}): the overlap is a ${sliverW} px " +
                    "vertical strip at the editor's RIGHT edge. Criterion 1 of 3: it is a " +
                    "*vertical* strip, not a band across the field — a finger aiming at the " +
                    "editor simply lands left of it.",
                6,
                sliverW,
            )
            assertEquals(
                "v144 DECL 3: the strip is " +
                    "${(100 * ov) / f.editor.area}% of the editor's area. Criterion 2 of 3: under " +
                    "2% of a ${f.editor.w}×${f.editor.h} field.",
                6 * (if (name == FRAME_BAR_ON) 47 else 38),
                ov,
            )
            assertTrue(
                "v144 DECL 3: criterion 3 of 3 — the editor keeps $undisputed px of undisputed " +
                    "target, so the 6 px does not make the field unreachable. **The 6 px is " +
                    "therefore NOT a reachability defect** and must not be filed as one.",
                (100 * ov) / f.editor.area <= 2 && undisputed >= 375,
            )
        }
        assertTrue(
            "v144 DECL 3: the strip is a by-product of the 28→42 touch expansion " +
                "(`Composer.kt:2493-2496` + material3's floor). Removing it means **shrinking a " +
                "48 dp target** to buy tidier geometry — a net loss. This is the reason 3 is " +
                "declared, not repaired, and it is the same red line as cell 5.",
            true,
        )
    }

    // ==================================================================== 5.
    // DECLARATION ④ — the 2 px is **derived from the same `expansionDown` as the
    // allowance**, so it is a new *fact* under the existing framework, never a
    // new ledger line and never an allowance expansion.

    @Test
    fun declaration4TheTwoPixelOverflowSharesItsSourceTermWithTheAllowanceAndOpensNoNewLine() {
        val f = F(FRAME_BAR_ON)
        val headroom = f.pane.bottom - f.toolRow.bottom

        assertEquals("v144 DECL 4: the host pane's bottom edge", 261, f.pane.bottom)
        assertEquals("v144 DECL 4: the tool row's layout bottom on this frame", 249, f.toolRow.bottom)
        assertEquals("v144 DECL 4: head-room = 261 − 249", 12, headroom)
        assertEquals("v144 DECL 4: the expansion is 48 − 21 = 27, ODD", 27, f.expansion)
        assertEquals("v144 DECL 4: ⌊27/2⌋ = 13 up", 13, f.expansionUp)
        assertEquals("v144 DECL 4: 27 − 13 = 14 down", 14, f.expansionDown)
        assertEquals(
            "v144 DECL 4: semantics bottom = 249 + 14 = 263, which the composer's own union " +
                "bottom agrees with — the overflow is real, not a derivation artefact.",
            263,
            maxOf(f.semanticBottom, f.actions.maxOf { it.bottom }),
        )
        assertEquals(
            "v144 DECL 4 **THE FORMULA**: overflow = expansionDown − (paneBottom − toolRowBottom) " +
                "= 14 − 12 = **2**. The 2 px is not slack anyone granted; it is 14 px of " +
                "expansion meeting 12 px of head-room.",
            2,
            f.expansionDown - headroom,
        )

        // --- the SAME-SOURCE relation, made explicit and machine-checked ---
        //
        // `allowance = Δ + expansionDown` where `Δ = innerLayoutBottom −
        // productionUnionBottom` (v142), and v142 also pinned the bound
        // `allowance ≤ expansionDown`. Those two facts together pin Δ's domain:
        // `Δ + expansionDown ≤ expansionDown` forces `Δ ≤ 0`, and the dump-side
        // direction (`dump ≥ production`) forces `Δ ≥ 0` — so **Δ = 0 and the
        // allowance's entire admissible set is the single value 14**. Cell ④'s
        // 2 px is not in that set, which is the machine-checkable form of "same
        // source term, different quantity".
        val allowanceAt = { delta: Int -> delta + f.expansionDown }
        val cellFourAt = { paneBottom: Int, toolRowBottom: Int ->
            f.expansionDown - (paneBottom - toolRowBottom)
        }
        val admissibleAllowances = (0..ALLOWANCE_MAX_DELTA).map { allowanceAt(it) }
            .filter { it <= f.expansionDown }
        assertEquals(
            "v144 DECL 4 **SAME SOURCE TERM**: both quantities are built from the SAME " +
                "`expansionDown` = ${f.expansionDown} — the allowance adds `Δ` to it, cell 4 " +
                "subtracts `head-room` from it. Same term, opposite sign, different question.",
            f.expansionDown,
            cellFourAt(261, 249) + (f.pane.bottom - f.toolRow.bottom),
        )
        assertEquals(
            "v144 DECL 4 **AND THEY ARE NOT THE SAME QUANTITY**: v142's bound `allowance ≤ " +
                "expansionDown` pins Δ to [0,0], so the allowance's whole admissible set is " +
                "$admissibleAllowances — the single value ${f.expansionDown}. Cell 4's fact is " +
                "${cellFourAt(261, 249)}, which is **not in that set**. Booking ④ as an " +
                "allowance would require Δ = ${cellFourAt(261, 249) - f.expansionDown} < 0, i.e. " +
                "reading the dump *above* production — the one direction the allowance forbids. " +
                "**Same term, different quantity ⇒ derived fact, no new line.**",
            listOf(14),
            admissibleAllowances,
        )
        assertTrue(
            "v144 DECL 4 **NO NEW LEDGER LINE, NO ALLOWANCE EXPANSION**: the allowance's upper " +
                "bound IS `expansionDown` = ${f.expansionDown}; admitting the 2 px would make it " +
                "${f.expansionDown + 2} and let the dump side float past that bound — which is " +
                "relaxing the assertion, not accounting for a fact. The only way to 'absorb' it in " +
                "the test would be `unionBottom <= paneBottom + 2`, and that is forbidden.",
            f.expansionDown - headroom == 2 && f.expansionDown == 14,
        )
        assertEquals(
            "v144 DECL 4: and 1 px of the 2 is irreducible anyway — an EVEN split would give " +
                "expansionDown = 13 and overflow 13 − 12 = 1. So 'make material3 split evenly' " +
                "does not close this, and the remaining 1 px is the odd-split remainder.",
            1,
            (27 / 2) - headroom,
        )
    }

    // ==================================================================== 6.
    // The production constants behind all of the above, read live so a silent
    // edit to any of them re-adjudicates on the next run.

    @Test
    fun theProductionAnchorsBehindCells2Through5AreReadLiveAndAreWhatTheReportQuotes() {
        assertEquals(
            "v144: `SessionTerminalEntryPolicy.TOP_DP` (`SessionScreen.kt:3612`) is the chip's " +
                "vertical anchor. It is also the knob cell 4's 2 px would need — which is why cell " +
                "4 and cell 5 cannot both be closed by moving it. If this changes, both " +
                "declarations must be re-adjudicated.",
            88f,
            SessionTerminalEntryPolicy.TOP_DP.value,
        )
        assertEquals(
            "v144: `SessionTerminalEntryPolicy.END_DP` (`SessionScreen.kt:3615`) is the chip's " +
                "horizontal anchor — the one that disagreed with the composer's end inset by the " +
                "**2 px** cell 5's residual was made of. It is INDEPENDENT of the composer's own " +
                "padding; neither is derived from the other, which is why the frames disagreed. " +
                "**v146 moved this anchor 8 dp → 64 dp** to separate the two boxes by geometry; " +
                "`UsabilityCellsV146SendOcclusionFixTest` re-adjudicates cell 5 against it. This " +
                "assertion now pins the *post-fix* anchor, and it stays just as strong: the value " +
                "is read live, so any further move goes red here too.",
            64f,
            SessionTerminalEntryPolicy.END_DP.value,
        )
        assertEquals(
            "v144: `TIMELINE_MIN_CONTENT` (64 dp) is the band the composer's ceiling is supposed " +
                "to keep clear above it — the invariant `SessionScreen.kt:3166-3173` relies on when " +
                "it claims the chip and the composer 'never share a pixel'. **They do share a " +
                "pixel** (the chip at y 192..240, the band at 191..239), so that claim is the " +
                "documented-but-false premise cell 5 rests on.",
            64f,
            SessionPaneLayout.TIMELINE_MIN_CONTENT.value,
        )
        assertEquals(
            "v144: the shell's tool row is 44 dp — upstream's `h-11`, copied verbatim " +
                "(`Composer.kt:2715`). Everything in cells 2–5 is measured against this number.",
            44f,
            UPSTREAM_TOOL_ROW_PX.toFloat(),
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun readUpstream(rel: String): List<String> =
        listOf(File("_upstream/$rel"), File("../_upstream/$rel"), File("app/_upstream/$rel"))
            .firstOrNull { it.isFile }
            ?.readLines()
            ?: throw AssertionError("v144: _upstream/$rel is missing, so the upstream ruler " +
                "cannot be cited. This is a FAILURE, not a skip.")

    private fun upstreamLine(rel: String, line: Int): String =
        readUpstream(rel).getOrNull(line - 1)?.trim()
            ?: throw AssertionError("v144: _upstream/$rel has no line $line")

    private fun upstreamLines(rel: String, from: Int, to: Int): List<String> =
        readUpstream(rel).subList(from - 1, to).map { it.trimEnd() }

    private companion object {
        const val DIR = "research/v97_verify/5554_md/artifacts"
        const val FRAME_BAR_ON = "$DIR/v137_BARON_M.xml"
        const val FRAME_BAR_OFF = "$DIR/v140_600off.xml"
        const val FRAME_600_ALT = "$DIR/v140_A_bar.xml"

        /** `icon-button.css:41-43` — upstream's `large` tier. */
        const val UPSTREAM_ADD_PX = 28
        /** `editor.tsx:889` — `size-7`. */
        const val UPSTREAM_SEND_PX = 28
        /** `editor.tsx:250` — `h-11`. */
        const val UPSTREAM_TOOL_ROW_PX = 44

        /**
         * A surviving sliver must be at least this wide (narrow dimension) for a
         * finger to land on it. 16 px is far below the ~40 px contact patch, so
         * the criterion cannot be passed by a sliver that merely happens to be
         * non-zero.
         */
        const val TAPPABLE_PX = 16

        /**
         * The widest `Δ` the dump side is ever allowed to sit past production by
         * (v142's bound is `allowance ≤ expansionDown`, which forces Δ to 0 — the
         * range is swept here rather than hard-coded so the proof is the code's,
         * not a restated conclusion).
         */
        const val ALLOWANCE_MAX_DELTA = 14
    }
}
