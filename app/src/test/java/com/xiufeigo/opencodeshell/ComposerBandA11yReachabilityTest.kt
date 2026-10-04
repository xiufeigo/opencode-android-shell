package com.xiufeigo.opencodeshell

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v142 TASK B — **"production has no defect" is a GEOMETRIC proposition, not a
 * usability conclusion.** v141 closed the geometry and left the a11y side
 * unassessed: it never asked whether a 48 dp touch target in the composer is
 * actually *reachable* — only where its edges are.
 *
 * This class asks, per form, per action, and **decides it statically** from the
 * archived real-device `uiautomator` frames. Five items, each with a verdict:
 *
 * | # | item | admissible? |
 * |---|---|---|
 * | 1 | semantics box **height** ≥ 48 px (= 48 dp, `wm density 160`) | `48` |
 * | 2 | semantics box **width** ≥ 48 px | `48` |
 * | 3 | **disjoint** from the other actions of the same composer | no overlap |
 * | 4 | **inside the host pane** (nothing of the target outside it) | `<= paneBottom` |
 * | 5 | **not occluded** by any foreign 48 dp target | no overlap |
 *
 * Every number below is **parsed out of the frame**, never typed, and the
 * verdicts are **pinned as literals** so a re-taken frame has to be re-read
 * rather than re-interpreted. The verdicts that come out **FAIL** are pinned as
 * facts with their arithmetic spelled out in the message — this class is
 * deliberately **green**: it is the *evidence* class. A red gate cannot also be
 * the main gate.
 *
 * The frames used (all `wm density 160` ⇒ 1 dp == 1 px):
 *
 * | frame | form | band |
 * |---|---|---|
 * | `v137_BARON_M.xml` | one-row, queue bar **on** | `[24,214][405,262]`, pane bottom 261 |
 * | `v140_600off.xml` | one-row, queue bar **off** | `[24,201][405,249]`, pane bottom 261 |
 * | `v140_A_bar.xml` | one-row, bar on, alternate run | `[24,201][405,249]`, pane bottom 261 |
 * | `v140_768.xml` | **stacked** | `[28,244][1338,292]`, pane bottom 350 |
 *
 * Sampler evidence: `research/v97_verify/5554_md/artifacts/geom_v142.ps1`
 */
class ComposerBandA11yReachabilityTest {

    private fun red(why: String): Nothing = throw AssertionError(why)

    private data class P(
        val cls: String,
        val desc: String,
        val clickable: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val w: Int get() = right - left
        val h: Int get() = bottom - top
        fun bounds() = "[$left,$top][$right,$bottom]"
        fun area() = w * h
        fun overlaps(o: P): Int {
            val ow = Math.min(right, o.right) - Math.max(left, o.left)
            val oh = Math.min(bottom, o.bottom) - Math.max(top, o.top)
            return if (ow > 0 && oh > 0) ow * oh else 0
        }
    }

    /**
     * One frame, with the v141 role rules: the tool row's **layout** box is the
     * single `HorizontalScrollView` stacked below the editor or seated to its
     * right; the composer's own actions are the 48-high clickable nodes
     * **contained** in the semantics box derived from that layout box.
     */
    private class F(relative: String) {
        val file: String = relative
        private val xml: String =
            listOf(File(relative), File("app/$relative"), File("../$relative"))
                .firstOrNull { it.isFile }?.readText()
                ?: throw AssertionError(
                    "v142: the frame $relative is missing, so a11y reachability cannot be " +
                        "adjudicated. This is a FAILURE, not a skip.",
                )
        val nodes: List<P> = Regex("<node\\b[^>]*>").findAll(xml).map { m ->
            val t = m.value
            val b = Regex("bounds=\"\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]\"").find(t)
                ?: throw AssertionError("v142: unparsable node $t")
            val g = b.groupValues
            P(
                cls = Regex("class=\"([^\"]*)\"").find(t)?.groupValues?.get(1) ?: "",
                desc = Regex("content-desc=\"([^\"]*)\"").find(t)?.groupValues?.get(1) ?: "",
                clickable = Regex("clickable=\"([^\"]*)\"").find(t)?.groupValues?.get(1) == "true",
                left = g[1].toInt(), top = g[2].toInt(),
                right = g[3].toInt(), bottom = g[4].toInt(),
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
        val semanticTop: Int get() = toolRow.top - expansionUp
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

        /** The composer's OWN actions: 48-high, clickable, inside the derived box. */
        val actions: List<P> get() = nodes.filter {
            it !== editor && it.clickable && it.h == editor.h &&
                it.top >= semanticTop && it.bottom <= semanticBottom
        }

        /** Every OTHER 48-high clickable node on the frame — the potential occluders. */
        val foreignTargets: List<P> get() = nodes.filter {
            it !== editor && it.clickable && it.h >= 48 && it !in actions
        }

        /** Item 3: overlaps *within* the composer's own action set. */
        fun internalOverlaps(): List<Pair<P, P>> {
            val out = mutableListOf<Pair<P, P>>()
            for (i in actions.indices) for (j in i + 1 until actions.size) {
                if (actions[i].overlaps(actions[j]) > 0) out += actions[i] to actions[j]
            }
            return out
        }

        /** Item 5: foreign 48 dp targets that sit on top of one of our actions. */
        fun occluders(a: P): List<Pair<P, Int>> = foreignTargets.mapNotNull { o ->
            val ov = o.overlaps(a)
            if (ov > 0) o to ov else null
        }
    }

    // ==================================================================== 1.
    // ITEM 2 — width. This is where the geometry turns out NOT to be all clear,
    // and the fact is pinned so it cannot be forgotten.

    @Test
    fun theAddActionIsFortyTwoPixelsWideInEveryFormAndThatIsUnderTheFortyEightDpMinimum() {
        for (name in listOf(FRAME_BAR_ON, FRAME_BAR_OFF, FRAME_600_ALT, FRAME_768)) {
            val f = F(name)
            val add = f.actions.single { it.desc == "Add" }
            assertEquals(
                "v142 ITEM 2 (${name.substringAfterLast('/')}): the `Add` action's semantics box " +
                    "is ${add.bounds()} = ${add.w}x${add.h}. Its HEIGHT is 48 (item 1 PASS) but " +
                    "its WIDTH is only ${add.w} < 48 ⇒ **ITEM 2 FAILS** on this action in this " +
                    "form. Material3's minimumInteractiveComponentSize only ever grows a box to " +
                    "48 on the *smaller* dimension, so a 42-wide action stays 42 wide. VERDICT: " +
                    "FAIL, and it is not a v136/v141 regression — it is the shape of the button " +
                    "plus the 6 px the editor's own 48 dp box eats into it (see the overlap test).",
                42,
                add.w,
            )
        }
    }

    // ==================================================================== 2.
    // ITEMS 1 + 4 — height, and containment in the host pane. The one real
    // overflow: the queue-bar-on one-row form.

    @Test
    fun everyActionIsFortyEightHighAndOnlyTheBarOnFormOverflowsTheHostPane() {
        for (name in listOf(FRAME_BAR_ON, FRAME_BAR_OFF, FRAME_600_ALT, FRAME_768)) {
            val f = F(name)
            assertEquals(
                "v142 ITEM 1 (${name.substringAfterLast('/')}): every composer's-own action must " +
                    "be 48 high. Sizes: " + f.actions.joinToString { "${it.desc.ifEmpty { it.cls }} ${it.bounds()}" },
                List(f.actions.size) { 48 },
                f.actions.map { it.h },
            )
        }

        // --- the overflow, pinned item by item, form by form ---
        val on = F(FRAME_BAR_ON)
        assertEquals("v142: the bar-ON one-row form's host pane bottom", 261, on.pane.bottom)
        assertEquals(
            "v142 ITEM 4 (v137_BARON_M): the composer's own union bottom is 263 — **2 px BELOW " +
                "the host pane's 261 ⇒ ITEM 4 FAILS here, and only here.** This is the same " +
                "2 px the whole v136→v141 arc is about (criterion ① = +2 on the dump side, " +
                "+1 on the production side, allowance 1). Its consequence for reachability: " +
                "the last 2 px of a 48 dp target lies outside the box the criterion measures " +
                "against. Whether it is *clipped* (so the target is really 46) or merely " +
                "*out-scrolled* is NOT decidable from a `uiautomator` dump — `clipChildren` is " +
                "not in the XML. Recorded as a boundary, not as a verdict: the geometry is " +
                "decided, the clipping is not.",
            263,
            on.actions.maxOf { it.bottom },
        )
        assertEquals("v142: and the shortfall is 2 px", 2, on.actions.maxOf { it.bottom } - on.pane.bottom)

        for (name in listOf(FRAME_BAR_OFF, FRAME_600_ALT, FRAME_768)) {
            val f = F(name)
            assertTrue(
                "v142 ITEM 4 (${name.substringAfterLast('/')}): the composer's union bottom " +
                    "${f.actions.maxOf { it.bottom }} must sit INSIDE the host pane bottom " +
                    "${f.pane.bottom} ⇒ ITEM 4 PASSES on this form (the queue bar being off " +
                    "lifts the whole composer 13 px clear of the pane edge).",
                f.actions.maxOf { it.bottom } <= f.pane.bottom,
            )
        }
    }

    // ==================================================================== 3.
    // ITEM 3 — mutual disjointness. Stacked form is clean; one-row is not.

    @Test
    fun theStackedFormIsDisjointButTheOneRowFormsOverlapTheEditorBySixPixels() {
        // ---- stacked: no overlap at all ----
        val st = F(FRAME_768)
        assertEquals(
            "v142 ITEM 3 (v140_768, STACKED): the composer's own actions are " +
                st.actions.joinToString { "${it.desc.ifEmpty { it.cls }} ${it.bounds()}" } +
                " — a row BELOW the editor, so the editor's 48 dp box and the action row never " +
                "touch. **ITEM 3 PASSES.** The stacked form is the well-formed one.",
            emptyList<Pair<P, P>>(),
            st.internalOverlaps(),
        )
        assertEquals("v142: stacked: the editor and the action row are vertically disjoint", 0, st.editor.overlaps(st.actions.first()))

        // ---- one-row: the Add button eats 6 px into the editor's own 48 dp box ----
        for (name in listOf(FRAME_BAR_ON, FRAME_BAR_OFF, FRAME_600_ALT)) {
            val f = F(name)
            val add = f.actions.single { it.desc == "Add" }
            val ov = f.editor.overlaps(add)
            assertEquals(
                "v142 ITEM 3 (${name.substringAfterLast('/')}): the editor's 48 dp semantics box " +
                    "${f.editor.bounds()} and the `Add` box ${add.bounds()} share $ov px² — a " +
                    "6-px-wide, 38..47-px-tall strip. **ITEM 3 FAILS**: on that strip two " +
                    "different a11y nodes are both hit-testable, and which one receives the tap " +
                    "is decided by view order, not by intent. The 6 px is not a rounding artefact " +
                    "— it is `editor.right − add.left = 405 − 399`, i.e. the editor's semantics " +
                    "box is 6 px wider than the layout box it wraps.",
                6 * (if (name == FRAME_BAR_ON) 47 else 38),
                ov,
            )
            // and the rest of the one-row set is disjoint: Add | chip | send
            val others = f.actions.filter { it !== add }
            assertEquals(
                "v142: the remaining one-row actions must be mutually disjoint from `Add`",
                emptyList<Pair<P, P>>(),
                others.mapNotNull { o -> if (o.overlaps(add) > 0) add to o else null },
            )
        }
    }

    // ==================================================================== 4.
    // ITEM 5 — occlusion by a FOREIGN 48 dp target. This is the finding that
    // the v141 "1 px critical point" was sitting next to without naming.

    @Test
    fun theFloatingTerminalButtonIsOccludedBySendOrStopInEveryOneRowForm() {
        for (name in listOf(FRAME_BAR_OFF, FRAME_600_ALT)) {
            val f = F(name)
            val terminal = f.foreignTargets.single { it.desc == "Open terminal" }
            // the trailing action is `Send` while idle and `Stop` while streaming — same slot
            val send = f.actions.single { it.desc == "Send" || it.desc == "Stop" }
            val ov = terminal.overlaps(send)
            assertEquals(
                "v142 ITEM 5 (${name.substringAfterLast('/')}): `Open terminal` ${terminal.bounds()} " +
                    "and `${send.desc}` ${send.bounds()} share $ov px². `Open terminal` is " +
                    "${terminal.w}x${terminal.h} = ${terminal.area()} px², so **$ov px² " +
                    "(${(100 * ov) / terminal.area()}%) of it is covered by `${send.desc}`** ⇒ ITEM 5 " +
                    "FAILS: the action is nominally 48 high but only ${terminal.area() - ov} px² " +
                    "of its own target is unambiguous. Also ITEM 2 FAILS on it: ${terminal.w} < 48 " +
                    "wide. It is a floating element, not a member of the composer row — the v141 " +
                    "role rule correctly excludes it from `actions`, and this is why that " +
                    "exclusion matters.",
                44 * 47,
                ov,
            )
            assertEquals("v142: the occluder list for `Open terminal` on this frame", 1, f.occluders(terminal).size)
        }

        // the bar-ON frame: the same occluder, but clipped to 16 px wide
        val on = F(FRAME_BAR_ON)
        val t16 = on.foreignTargets.single { it.desc == "Open terminal" }
        assertEquals(
            "v142 ITEM 2 (v137_BARON_M): `Open terminal` is [1350,192][1366,240] = " +
                "${t16.w}x${t16.h}. Its HEIGHT is 48 but its WIDTH is ${t16.w} — 16 px against " +
                "a 48 dp minimum, a third of the required size ⇒ **ITEM 2 FAILS** on this " +
                "action in this form, by the widest margin in the whole sample. Nothing in this " +
                "repo can fix it: the button is anchored to the screen's right edge and the " +
                "pane's own right edge is at 1366, so the 48 dp expansion is clipped by the " +
                "screen. Recorded, not fixed — a production change is out of this round's scope.",
            16,
            t16.w,
        )
        assertEquals(
            "v142 ITEM 5 (v137_BARON_M): on the bar-ON frame `Open terminal` is occluded by " +
                "`Stop` [1316,215][1364,263] over 14x25 = 350 px², which is 45% of its 768 px².",
            350,
            t16.overlaps(on.actions.single { it.desc == "Stop" }),
        )

        // the stacked form: NOT occluded — the floating button sits above the row
        val st = F(FRAME_768)
        val tStacked = st.foreignTargets.single { it.desc == "Open terminal" }
        assertEquals(
            "v142 ITEM 5 (v140_768, STACKED): `Open terminal` [1320,192][1366,240] vs the " +
                "action row's `Send` [1308,292][1356,340] — vertically disjoint ⇒ **ITEM 5 " +
                "PASSES**. Stacking is what saves the floating button.",
            0,
            tStacked.overlaps(st.actions.single { it.desc == "Send" }),
        )
    }

    // ==================================================================== 5.
    // THE 1 PX CRITICAL POINT — pinned as an EXPLICIT DECLARATION with its
    // trigger, per the Lead's option B. See the report §4 for why it was NOT
    // eliminated on the test side.

    @Test
    fun theOnePixelCriticalPointIsPinnedAsADeclaredFragilityWithItsTrigger() {
        val f = F(FRAME_BAR_OFF)
        val hsv = f.toolRow
        val terminal = f.foreignTargets.single { it.desc == "Open terminal" }

        // the derived semantics box, and the exact miss
        assertEquals("v142: the tool row's LAYOUT box on the 600 bar-off frame", "[441,201][1316,229]", hsv.bounds())
        assertEquals("v142: its height is 28, a frame constant, never a constant in any rule", 28, hsv.h)
        assertEquals("v142: the expansion is 48 − 28 = 20, which splits 10/10 — EVEN", 20, f.expansion)
        assertEquals("v142: an even split, so there is no rounding ambiguity on THIS frame", 10, f.expansionUp)
        assertEquals("v142: semantics bottom = 229 + 10 = 239", 239, f.semanticBottom)
        assertEquals(
            "v142: and `Open terminal`'s bottom edge is 240 — **exactly 1 px past it**. That 1 px " +
                "is the whole separator between 'a composer action' and 'a floating element that " +
                "covers 94% of the send button'. It is the fragility v141 §4.4-1 named and left " +
                "un-eliminated.",
            1,
            terminal.bottom - f.semanticBottom,
        )
        assertEquals(
            "v142 DECLARATION — the split is EVEN on this frame, so the miss cannot come from " +
                "rounding here; it comes from the 1 px vertical offset between the floating " +
                "button and the composer row. A split of 9/11 instead of 10/10 would move the " +
                "semantics box to 192..240, admit `Open terminal` into `actions`, and take the " +
                "union bottom from 249 to 240 — no change to the union, but the role rule would " +
                "then count a floating action as a composer action. THAT is the trigger: " +
                "material3 changing how it splits an ODD expansion (the v137 frame's 27 splits " +
                "13/14) or the floating button moving by 1 px.",
            0,
            f.expansion % 2,
        )
        assertTrue(
            "v142: and the declared trigger is live right now in the sibling frame — the v137 " +
                "bar-ON frame's expansion is ODD (27 ⇒ 13/14), which is exactly the case where " +
                "the split direction is unprovable from geometry alone. The rule there is " +
                "`⌊expansion/2⌋` up, matching Compose's `roundToInt(13.5) = 14` — recorded by " +
                "v141 as 'observationally consistent, NOT proven from source', and still so.",
            F(FRAME_BAR_ON).expansion % 2 == 1,
        )
    }

    private companion object {
        const val DIR = "research/v97_verify/5554_md/artifacts"
        const val FRAME_BAR_ON = "$DIR/v137_BARON_M.xml"
        const val FRAME_BAR_OFF = "$DIR/v140_600off.xml"
        const val FRAME_600_ALT = "$DIR/v140_A_bar.xml"
        const val FRAME_768 = "$DIR/v140_768.xml"
    }
}
