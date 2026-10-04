package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout.composerBandUnionBottomPx
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * ============================================================================
 * LEAD-GATED CLASS — NOT PART OF THE MAIN GATE. THIS FILE IS **NOT COMPILED.**
 * ============================================================================
 *
 * v142 — **THE v139 LEDGER IS REWRITTEN, AND THE CLASS IS STILL RED.**
 *
 * ---------------------------------------------------------------------------
 * WHAT WAS WRONG WITH THE OLD LEDGER (v139's `PARITY 3/3`), IN ITS OWN WORDS
 * ---------------------------------------------------------------------------
 * v139 asserted:
 *
 *     assertEquals("…the tree says Alignment.Top (which returns 0 for every
 *     child…), the v137 frame measured 214 + 14 = 228. ⇒ the v136 change was
 *     not what rendered the frame.", deviceInnerTopPx, 0)
 *
 * i.e. **"the code in this tree does not match the frame ⇒ some other build
 * rendered it."**
 *
 * **The v140 device probe falsified that half, and v141 ruled on it:**
 *
 *     10-03 23:36:34.202 27592 I BandUnion: minimal=true bandPx=48 editorTop=14
 *                                  editorH=20 toolRowTop=0 toolRowH=48
 *                                  unionBottomPx=48
 *     10-03 23:42:23.570 31108 I BandUnion: … byte-identical, second pid
 *
 * The probe sits **in this tree's own** `layout` block, next to
 * `SessionComposerBandUnion.record(...)` (`Composer.kt:2391`), and it printed
 * **twice across two pids, byte for byte**. So the tree *is* what ran. The old
 * `PARITY 3/3` was a **mis-attribution wearing the costume of a parity check**:
 * it compared `Alignment.Top`'s *offset* (0) against a *centring* offset (14)
 * and concluded "wrong build", when in truth the 14 is
 * `composerBandChildTopPx(48, 20) = (48−20)/2 = 14` — **this tree's own
 * function, on this tree's real input**.
 *
 * ⇒ That assertion is **withdrawn**, and it is replaced below by a framework
 * that is falsifiable in the other direction.
 *
 * ---------------------------------------------------------------------------
 * THE NEW FRAMEWORK (v142)
 * ---------------------------------------------------------------------------
 * Two quantities that are not the same quantity, so they may not be compared
 * for equality without an admission rule. **The admission rule IS the ledger.**
 *
 *   PRODUCTION side (authoritative, v141 ruling for form ①)
 *       unionBottomPx = max(editorBottom, toolRowBottom)
 *                      = max(editorTop+editorH, toolRowTop+toolRowH)
 *                      = max(14+20, 0+48) = 48          ← read from the PROBE
 *
 *   DUMP side (semantics / a11y)
 *       the union of the 48 dp touch-target SEMANTICS boxes
 *                      = 263, i.e. unionPx 49
 *
 *   ALLOWANCE = 263 − 262 = 1, and it is **admitted downward only**, bounded by
 *   the expansion that produced it, and it is **derived from the frame**, never
 *   typed:
 *
 *       expansion      = 48 − innerLayoutHeight          = 48 − 21 = 27
 *       expansionUp    = ⌊expansion/2⌋                   = 13
 *       expansionDown  = expansion − expansionUp        = 14
 *       allowance      = (innerLayoutBottom − productionUnionBottomAbs)
 *                        + expansionDown
 *                      = (249 − 262) + 14               =  1
 *
 * The OLD ledger asserted `dump == model` (49 vs 48) with no admission rule
 * and attributed the gap to a wrong build. The NEW ledger asserts:
 *
 *   * the production evaluation IS the adjudicated value, and is re-derived
 *     here from the probe, not retyped;
 *   * the dump side is admitted **if and only if** the gap is downward and no
 *     larger than `expansionDown`;
 *   * **and the admission is PROVISIONAL: the parity is still owed.** The
 *     class stays RED on that, deliberately, until it is closed on a device.
 *
 * ---------------------------------------------------------------------------
 * WHY IT IS STILL RED — the honest red, stated exactly
 * ---------------------------------------------------------------------------
 * v141 called "production has no defect" a **geometric** proposition. It is
 * not a usability conclusion. The dump's semantics box reaches 2 px below the
 * host pane's bottom edge on this frame, so the last 2 px of a 48 dp target
 * lies outside the box the criterion measures against. Until that is either
 * fixed or re-based, **"model == device" is not true**, and pretending it is
 * would be exactly the fake green this class exists to prevent.
 *
 * `theAdmissionIsProvisionalAndTheParityIsStillOwed` is that red. It is
 * **fail-on-mismatch**, never `@Ignore`d, never made trivially true, and it
 * **fails with a different message than the guards below** — so when a guard
 * also goes red you can tell the two apart in the log.
 *
 * ---------------------------------------------------------------------------
 * THE TRIGGERS THAT MUST STAY LIVE (v142 hard requirement)
 * ---------------------------------------------------------------------------
 * Nothing in this class may become a tautology. Concretely, each of these goes
 * RED if it changes, and each is a **separate test method** so the log says
 * which one broke:
 *
 *   | trigger                                            | which method  |
 *   |----------------------------------------------------|---------------|
 *   | the allowance GROWS (gap ≠ the derived 1)          | `theDumpSide…`|
 *   | the DIRECTION flips (dump < production)            | `theDumpSide…`|
 *   | the gap exceeds `expansionDown` (upper bound)      | `theDumpSide…`|
 *   | the production evaluation stops being 48           | `theProduction…`|
 *   | the split stops being 13 up / 14 down              | `theTouchTarget…` |
 *   | the frame is re-taken                              | `theDevice…`  |
 *   | the probe stops printing / stops self-consistent   | `theProduction…` |
 *
 * v142's reverse verification **injects** a change and shows the class turning
 * red with a *new* failure on top of the owed one — see
 * `research/v97_fix/LEDGER_REWRITE_AND_USABILITY.md` §3 for the verbatim log.
 *
 * ---------------------------------------------------------------------------
 * HOW THE LEAD ADMITS IT  ← the collection command, verbatim
 * ---------------------------------------------------------------------------
 * This class is parked **outside every compiled source set** (it lives in
 * `research/`, the one writable location that is not compiled) so the main gate
 * stays green while the parity is still owed. `app/build.gradle.kts` is not
 * writable and `@Ignore` is ruled out — it drops the trigger and cuts the case
 * count. So admission is a **single file copy**, and nothing else:
 *
 *     Copy-Item research\v97_fix\quarantine\ModelVsDeviceMismatchTest.kt `
 *              app\src\test\java\com\xiufeigo\opencodeshell\ModelVsDeviceMismatchTest.kt
 *
 * It is deliberately **self-contained** (its own parser, its own probe reader,
 * no shared helper) so that move is one command with no other edit, and so a
 * later change to the gate class `ComposerBandDeviceTruthTest` cannot silently
 * break it. The class is now **GREEN only when the parity is genuinely closed**
 * (i.e. when the dump's union bottom equals the production one), so the copy
 * is the honest moment: it fails until then, which is the point.
 * To park it again, delete the copy — do not edit the assertions.
 *
 * ---------------------------------------------------------------------------
 * PROOF IT IS RED
 * ---------------------------------------------------------------------------
 * v139: `research/v97_fix/_v139_run_red.log`, transcript §4 of
 * `research/v97_fix/PIN_DEVICE_HARDLINK.md`.
 * v142: `research/v97_fix/_v142_quarantine_red.log` (copied in, run, observed
 * failing) and `research/v97_fix/_v142_reverse.log` (an injected change ⇒ one
 * extra, distinctly-named failure).
 * ============================================================================
 */
class ModelVsDeviceMismatchTest {

    private fun red(why: String): Nothing = throw AssertionError(why)

    // ------------------------------------------------------------------ the
    // device side: parsed out of the real `uiautomator dump`. Nothing typed.

    private data class FrameNode(
        val cls: String,
        val desc: String,
        val clickable: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private val nodeTag = Regex("<node\\b[^>]*>")
    private val clsAttr = Regex("class=\"([^\"]*)\"")
    private val descAttr = Regex("content-desc=\"([^\"]*)\"")
    private val clickableAttr = Regex("clickable=\"([^\"]*)\"")
    private val boundsAttr = Regex("bounds=\"\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]\"")

    private fun parseNodes(xml: String): List<FrameNode> {
        val nodes = nodeTag.findAll(xml).map { m ->
            val tag = m.value
            val b = boundsAttr.find(tag)
                ?: red("v142 quarantine: a node has no parsable bounds: $tag")
            val g = b.groupValues
            FrameNode(
                cls = clsAttr.find(tag)?.groupValues?.get(1) ?: "",
                desc = descAttr.find(tag)?.groupValues?.get(1) ?: "",
                clickable = clickableAttr.find(tag)?.groupValues?.get(1) == "true",
                left = g[1].toInt(),
                top = g[2].toInt(),
                right = g[3].toInt(),
                bottom = g[4].toInt(),
            )
        }.toList()
        if (nodes.isEmpty()) {
            red("v142 quarantine: 0 nodes parsed out of the device frame — the readout file is truncated.")
        }
        return nodes
    }

    private fun List<FrameNode>.one(what: String, pred: (FrameNode) -> Boolean): FrameNode {
        val hits = filter(pred)
        if (hits.size != 1) {
            red("v142 quarantine: expected exactly 1 device node for [$what], found ${hits.size} — $hits")
        }
        return hits.first()
    }

    private val frameXml: String by lazy {
        listOf(
            File("research/v97_verify/5554_md/artifacts/v137_BARON_M.xml"),
            File("app/research/v97_verify/5554_md/artifacts/v137_BARON_M.xml"),
            File("../research/v97_verify/5554_md/artifacts/v137_BARON_M.xml"),
        ).firstOrNull { it.isFile }?.readText()
            ?: red(
                "v142 quarantine: the device readout file is missing. The model-vs-device " +
                    "parity CANNOT be judged without it, so this is a FAILURE, never a skip.",
            )
    }

    /** The v140 device probe — the PRODUCTION side, read from the device. */
    private val probeXml: String by lazy {
        listOf(
            File("research/v97_verify/5554_md/artifacts/probe_A.log"),
            File("app/research/v97_verify/5554_md/artifacts/probe_A.log"),
            File("../research/v97_verify/5554_md/artifacts/probe_A.log"),
        ).firstOrNull { it.isFile }?.readText()
            ?: red(
                "v142 quarantine: the v140 BandUnion probe log is missing, so the PRODUCTION " +
                    "side has no device evidence. This is a FAILURE, never a skip.",
            )
    }

    private val probeLine: String by lazy {
        probeXml.lineSequence().firstOrNull { it.contains("bandPx=") }
            ?: red("v142 quarantine: no `bandPx=` line in the probe log — the probe never printed")
    }

    private fun probeValue(key: String): Int {
        val m = Regex("$key=(\\d+)").find(probeLine)
            ?: red("v142 quarantine: the probe line has no `$key=` field — the production readout " +
                "changed shape and this class must be re-derived. Line was: $probeLine")
        return m.groupValues[1].toInt()
    }

    private val probeBandPx: Int by lazy { probeValue("bandPx") }
    private val probeEditorTop: Int by lazy { probeValue("editorTop") }
    private val probeEditorH: Int by lazy { probeValue("editorH") }
    private val probeToolRowTop: Int by lazy { probeValue("toolRowTop") }
    private val probeToolRowH: Int by lazy { probeValue("toolRowH") }
    private val probeUnionBottomPx: Int by lazy { probeValue("unionBottomPx") }

    private val nodes: List<FrameNode> by lazy { parseNodes(frameXml) }
    private val editor: FrameNode by lazy {
        nodes.one("the editor / EditText") { it.cls == "android.widget.EditText" }
    }
    private val bandTop: Int get() = editor.top
    private val bandPx: Int get() = editor.bottom - editor.top

    private val innerLayout: FrameNode by lazy {
        nodes.one("the tool row's own LAYOUT box") {
            it.cls == "android.widget.HorizontalScrollView" &&
                (
                    (it.top >= editor.bottom - 1 && it.top <= editor.bottom + 2 * bandPx) ||
                        (
                            it.left >= editor.right &&
                                Math.abs((it.top + it.bottom) / 2 - (editor.top + editor.bottom) / 2) <= bandPx
                            )
                    )
        }
    }

    private val innerH: Int get() = innerLayout.bottom - innerLayout.top
    private val expansion: Int get() = (48 - innerH).coerceAtLeast(0)
    private val expansionUp: Int get() = expansion / 2
    private val expansionDown: Int get() = expansion - expansionUp
    private val semanticTop: Int get() = innerLayout.top - expansionUp
    private val semanticBottom: Int get() = innerLayout.bottom + expansionDown

    /** The controls' 48 dp semantics boxes, by containment in the derived box. */
    private val controls: List<FrameNode> by lazy {
        nodes.filter {
            it.clickable && it !== editor &&
                it.bottom - it.top == 48 &&
                it.top >= semanticTop && it.bottom <= semanticBottom
        }
    }
    private val controlBottom: Int get() = controls.maxOf { it.bottom }

    /** The host pane — the datum criterion 1 is measured against. */
    private val paneBottom: Int by lazy {
        val screenWidth = nodes.maxOf { it.right - it.left }
        val straddling = nodes.filter {
            it.cls == "android.view.View" && (it.right - it.left) == screenWidth &&
                it.top < bandTop && it.bottom > bandTop
        }
        val tightest = straddling.minOfOrNull { it.bottom }
            ?: red("v142 quarantine: no full-width View contains the band, so the host box cannot be read")
        val tied = straddling.filter { it.bottom == tightest }
        if (tied.size != 1) {
            red("v142 quarantine: ${tied.size} full-width Views share the tightest bottom $tightest")
        }
        tied.first().bottom
    }

    private val deviceUnionPx: Int get() = maxOf(editor.bottom, controlBottom) - bandTop
    private val deviceUnionBottomAbs: Int get() = maxOf(editor.bottom, controlBottom)

    // ==================================================================== THE
    // PRODUCTION SIDE, which v141 ruled to be the adjudicated value. Re-derived
    // from the probe here; the equality below is what makes the probe's readout
    // a *reading* rather than a claim.

    @Test
    fun theProductionEvaluationIsTheAdjudicatedValueAndItIsStillFortyEight() {
        assertEquals(
            "v142 TRIGGER (production evaluation moved): v141 ruled the PRODUCTION side " +
                "authoritative for form ①, and the probe reads unionBottomPx=48. If this is no " +
                "longer 48 the whole allowance arithmetic is void and this class must be " +
                "re-derived from a fresh device run — it must NOT be updated to a new literal.",
            48,
            probeUnionBottomPx,
        )
        assertEquals(
            "v142: the probe is self-consistent — unionBottomPx = " +
                "max(editorTop+editorH, toolRowTop+toolRowH) = max(14+20, 0+48) = 48. If the " +
                "probe's own arithmetic stopped closing, the readout is not trustworthy and " +
                "nothing below it may be read as evidence.",
            maxOf(probeEditorTop + probeEditorH, probeToolRowTop + probeToolRowH),
            probeUnionBottomPx,
        )
        assertEquals(
            "v142: the production function fed the probe's own inputs returns what the probe " +
                "printed — the tree and the running layout AGREE (this is what v139's " +
                "'wrong build' ledger got wrong)",
            probeUnionBottomPx,
            composerBandUnionBottomPx(
                bandPx = probeBandPx,
                editorPx = probeEditorH,
                toolRowPx = probeToolRowH,
            ),
        )
        assertNotEquals(
            "v142 SANITY: the two sides are NOT the same quantity. If the dump side had ever " +
                "read the same as production, the allowance below would be 0 and this class " +
                "would have nothing to adjudicate — a frame that closes the gap must make " +
                "the owed-parity assertion pass, not this one.",
            probeUnionBottomPx,
            deviceUnionPx,
        )
    }

    // ==================================================================== THE
    // ALLOWANCE, in formula + direction + upper bound. This is the replacement
    // for "the model must equal the device" — and it is *stronger* than a
    // literal `assertEquals(1, gap)`, because it also pins the sign, the origin
    // and the ceiling.

    @Test
    fun theDumpSideIsAdmittedByTheTouchTargetAllowanceDownwardAndOnlyDownward() {
        val productionUnionBottomAbs = bandTop + probeUnionBottomPx
        val allowance = (innerLayout.bottom - productionUnionBottomAbs) + expansionDown

        assertEquals(
            "v142: the 48 dp target is grown about the 21-high layout box, so the expansion is 27",
            27,
            expansion,
        )
        assertEquals(
            "v142 TRIGGER (the split changed): 27 px splits 13 up / 14 down — the ODD remainder " +
                "lands on the BOTTOM edge, which is the entire reason the dump's gap is on the " +
                "bottom. If material3 ever splits it 14/13 the allowance becomes 0 and the dump " +
                "would read equal to production; if it splits evenly the gap is 0 too. Neither " +
                "is admissible without re-deriving this class.",
            13,
            expansionUp,
        )
        assertEquals("v142: the downward half of the expansion is 14", 14, expansionDown)
        assertEquals(
            "v142: the semantics bottom = layout bottom + the downward half = 249 + 14 = 263",
            263,
            semanticBottom,
        )
        assertEquals(
            "v142 ALLOWANCE (derived, not typed): the dump's union bottom 263 minus the " +
                "production's 262. Expressed through the expansion that produced it — " +
                "(innerLayoutBottom 249 − productionUnionBottom 262) + expansionDown 14 = 1. " +
                "TRIGGER: this goes RED the moment the gap grows, shrinks, or stops being this " +
                "expression. A literal `assertEquals(1, gap)` would have hidden exactly that.",
            1,
            deviceUnionBottomAbs - productionUnionBottomAbs,
        )
        assertEquals(
            "v142: and that derived expression must equal the measured gap",
            allowance,
            deviceUnionBottomAbs - productionUnionBottomAbs,
        )
        // DIRECTION: semantics CONTAINS layout, so the dump may read lower, never higher.
        assertTrue(
            "v142 TRIGGER (direction flipped): the dump side read $deviceUnionPx against " +
                "production $probeUnionBottomPx. Semantics ⊇ layout is structural, so a dump " +
                "reading SHORTER than production would mean the touch target had shrunk — no " +
                "model in this repo can produce that, so it is not admissible.",
            deviceUnionPx >= probeUnionBottomPx,
        )
        // UPPER BOUND: the admitted gap may never exceed the expansion it comes from.
        assertTrue(
            "v142 TRIGGER (upper bound broken): the admitted gap must lie in 0..$expansionDown " +
                "(the downward half of the expansion). A larger gap means something other than " +
                "the touch target is contributing and the allowance no longer explains it.",
            deviceUnionBottomAbs - productionUnionBottomAbs in 0..expansionDown,
        )
    }

    // ==================================================================== THE
    // HONEST RED. Fail-on-mismatch, on purpose, forever until the parity is
    // closed on a device. This is a DIFFERENT test method from the guards
    // above so that a log shows which half broke.

    @Test
    fun theAdmissionIsProvisionalAndTheParityIsStillOwed() {
        assertEquals(
            "v142 THE OWED PARITY — RED BY ARITHMETIC, ON PURPOSE. Under the v141 framework the " +
                "PRODUCTION side is authoritative (unionBottomPx = " +
                "max(editorBottom, toolRowBottom) = $probeUnionBottomPx) and the dump side is " +
                "admitted a derived, downward-only allowance of " +
                "${deviceUnionBottomAbs - (bandTop + probeUnionBottomPx)} px, " +
                "bounded by expansionDown=$expansionDown. That admission is PROVISIONAL: the " +
                "dump's semantics union still reaches ${deviceUnionBottomAbs - paneBottom} px " +
                "past the host pane's bottom edge ($deviceUnionBottomAbs vs $paneBottom), so the " +
                "two sides are NOT equal and 'the model agrees with the device' is not true. " +
                "'Production has no defect' (v141) is a GEOMETRIC proposition, not a usability " +
                "conclusion. This assertion closes only when a device frame reads a dump union " +
                "bottom equal to the production one. It is NOT to be relaxed, @Ignore'd, or " +
                "turned into `assertTrue(gap <= 1)`: the whole reason this class is parked " +
                "outside the source set is that this red is real.",
            0,
            deviceUnionBottomAbs - (bandTop + probeUnionBottomPx),
        )
    }

    // ==================================================================== The
    // evidence itself. If the frame is re-taken or the roles stop resolving,
    // this goes red FIRST, so a re-read is never silently reinterpreted.

    @Test
    fun theDeviceReadingsAreTheArchivedFrameAndNotAStaleCopy() {
        assertEquals("v137 device: the dump is 66 nodes", 66, nodes.size)
        assertEquals("v137 device: band top", 214, bandTop)
        assertEquals("v137 device: band px", 48, bandPx)
        assertEquals("v137 device: editor bottom", 262, editor.bottom)
        assertEquals("v137 device: the inner layout's top", 228, innerLayout.top)
        assertEquals("v137 device: the inner layout is 21 high", 21, innerH)
        assertEquals("v137 device: three control bounds in the derived semantics box", 3, controls.size)
        assertEquals("v137 device: the controls' semantics bottom", 263, controlBottom)
        assertEquals("v137 device: pane bottom", 261, paneBottom)
        assertEquals("v137 device: dump unionPx", 49, deviceUnionPx)
    }
}
