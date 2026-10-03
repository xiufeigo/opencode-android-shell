package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout.composerBandChildTopPx
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout.composerBandUnionBottomPx
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v139 **A — the hard link from the pin to the real device frame.**
 *
 * ---------------------------------------------------------------------------
 * v138 said the model and the device disagree, and pinned the disagreement as a
 * **hand-typed tripwire**: `assertEquals(1, devUnionPx - modelUnionBottom)`
 * (`ComposerBandUnionV136Test.kt:381`). That is still a *model's word* about a
 * device reading — the numbers `devUnionPx` is computed from were typed into the
 * test by a previous agent. **Nothing in the JVM suite ever opened the XML the
 * device actually produced.** That is the structural reason this repo has now
 * had five fake greens on this defect: the pin and the device frame were two
 * documents that agreed by hand, not by construction.
 *
 * This class removes the hand. Every device number below is **parsed out of**
 *
 * ```
 * research/v97_verify/5554_md/artifacts/v137_BARON_M.xml
 * ```
 *
 * the `uiautomator dump` v137 took on emulator-5554 (build md5 `9142f0eb…`,
 * `wm size 1366x600` / `wm density 160` ⇒ 1dp == 1px, 600 tier, queue bar on
 * screen, one-row form, 66 nodes).
 *
 * **The roles are located structurally, not by the coordinates they happen to
 * have**, so the parse survives a different frame and reports the *real* roles:
 *
 * | role | discriminator | frame |
 * |---|---|---|
 * | editor | the one `android.widget.EditText` | `[24,214][405,262]` h=48 |
 * | band span | the editor's own top edge and height — the editor is the band's full-height child (`Composer.kt:2327-2334`, `maxHeight = band`) | `214 .. 262` |
 * | controls (48 dp **semantics** bounds) | `clickable="true"`, top edge inside the band span, not the editor | Add `[399,215][441,263]`, Model `[441,215][559,263]`, Stop `[1316,215][1364,263]` — exactly 3 |
 * | inner layout | the `HorizontalScrollView` whose top edge is inside the band span | `[441,228][1316,249]` h=21 |
 * | pane box | the full-width `android.view.View` that straddles the band top | `[0,162][1366,261]` → `paneBottom 261` |
 *
 * **If the file is missing, empty, or does not parse into these roles, this
 * class goes RED with the reason.** It does not skip, does not preview, does not
 * fall back to a default. A pin that softens when its evidence disappears is
 * how five fake greens happened.
 *
 * ---------------------------------------------------------------------------
 * What this class does **not** do: it does not declare the parity met. The
 * comparison below is one line —
 * `deviceUnionPx` (49, parsed) against `composerBandUnionBottomPx(48, 48, 48)`
 * (48, production) — and it is **1 px apart today**, which is the defect.
 * The assertion that *fails on that 1 px* is the Lead-gated
 * `ModelVsDeviceMismatchTest`, which deliberately lives **outside** this source
 * set so the main gate stays green while the parity is still owed:
 * `research/v97_fix/quarantine/ModelVsDeviceMismatchTest.kt`.
 * What is asserted here is the **evidence** — the parsed frame, and the size of
 * the gap — so the gap can never silently become 0.
 */
class ComposerBandDeviceTruthTest {

    /** A RED that is typed `Nothing`, so it composes in expression position. */
    private fun red(why: String): Nothing = throw AssertionError(why)

    // ------------------------------------------------------------------ the
    // minimal parser for `uiautomator dump` output (this file's own format
    // reader; a flat attribute set needs no XML library and adds no dependency).

    private fun read(relative: String): String =
        listOf(File(relative), File("app/$relative"), File("../$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: red(
                "v139 hard link: the device readout file is missing, so this pin cannot " +
                    "compare the model with the device. Expected it at `$relative` " +
                    "(tried the repo root, `app/` and the parent). This is deliberately a " +
                    "FAILURE and not a skip: a pin that stops reading the device and starts " +
                    "passing is the fake green this class exists to kill.",
            )

    private data class FrameNode(
        val cls: String,
        val clickable: Boolean,
        val width: Int,
        val left: Int,
        val top: Int,
        val bottom: Int,
    )

    // -------------------------------------------------- the LAYOUT-side readout.
    //
    // v140 took the v139 `Log.i` probe on emulator-5554 and got, twice, byte for
    // byte across two pids:
    //
    //   minimal=true bandPx=48 editorTop=14 editorH=20 toolRowTop=0
    //   toolRowH=48 unionBottomPx=48
    //
    // That is the **production evaluation**, and it says 48 — not 49. So the pin
    // must read BOTH sides: the layout side from the probe (what production
    // computed) and the semantics side from the dump (what a11y measured). The
    // 1 px is the distance between two different quantities, and the whole point
    // of this class is to *derive* that distance instead of asserting a literal.

    private fun readProbe(): String =
        listOf(File(PROBE), File("app/$PROBE"), File("../$PROBE"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: red(
                "v141: the v140 device probe log is missing, so the PRODUCTION side of the " +
                    "comparison has no device evidence. Expected it at `$PROBE`. This is a " +
                    "FAILURE and not a skip: the ruling is that the production side is the " +
                    "one being adjudicated, so it must be read from the device, never retyped.",
            )

    /** `bandPx=48 editorTop=14 …` — one `key=value` pair out of the probe line. */
    private fun probeValue(text: String, key: String): Int {
        val m = Regex("$key=(\\d+)").find(text)
            ?: red("v141: the v140 probe line has no `$key=` field — the production readout " +
                "changed shape and this pin must be re-derived. Line was: $text")
        return m.groupValues[1].toInt()
    }

    private val probeLine: String by lazy {
        readProbe().lineSequence().firstOrNull { it.contains("bandPx=") }
            ?: red("v141: no `bandPx=` line in the probe log — the probe never printed")
    }

    /** What the LAYOUT pass actually used, as integers. Device-sourced, not typed. */
    private val probeBandPx: Int by lazy { probeValue(probeLine, "bandPx") }
    private val probeEditorTop: Int by lazy { probeValue(probeLine, "editorTop") }
    private val probeEditorH: Int by lazy { probeValue(probeLine, "editorH") }
    private val probeToolRowTop: Int by lazy { probeValue(probeLine, "toolRowTop") }
    private val probeToolRowH: Int by lazy { probeValue(probeLine, "toolRowH") }
    private val probeUnionBottomPx: Int by lazy { probeValue(probeLine, "unionBottomPx") }

    private val nodeTag = Regex("<node\\b[^>]*>")
    private val clsAttr = Regex("class=\"([^\"]*)\"")
    private val clickableAttr = Regex("clickable=\"([^\"]*)\"")
    private val boundsAttr = Regex("bounds=\"\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]\"")

    private fun parseNodes(xml: String): List<FrameNode> {
        val nodes = nodeTag.findAll(xml).map { m ->
            val tag = m.value
            val b = boundsAttr.find(tag)
                ?: red("v139 hard link: a node has no parsable bounds: $tag")
            val g = b.groupValues
            FrameNode(
                cls = clsAttr.find(tag)?.groupValues?.get(1) ?: "",
                clickable = clickableAttr.find(tag)?.groupValues?.get(1) == "true",
                width = g[3].toInt() - g[1].toInt(),
                left = g[1].toInt(),
                top = g[2].toInt(),
                bottom = g[4].toInt(),
            )
        }.toList()
        if (nodes.isEmpty()) {
            red(
                "v139 hard link: 0 nodes parsed out of the device frame. The readout file " +
                    "is truncated or is not a `uiautomator dump`.",
            )
        }
        return nodes
    }

    /** Exactly one node, or RED with the role named. Never a silent default. */
    private fun List<FrameNode>.one(what: String, pred: (FrameNode) -> Boolean): FrameNode {
        val hits = filter(pred)
        if (hits.size != 1) {
            red("v139 hard link: expected exactly 1 device node for [$what], found ${hits.size} — $hits")
        }
        return hits.first()
    }

    // ------------------------------------------------------------------ the
    // device side, read from the file on every run.

    private val frameXml: String by lazy { read(FRAME) }
    private val nodes: List<FrameNode> by lazy { parseNodes(frameXml) }

    /** The band IS the editor's own box: the editor is measured with `maxHeight = band`. */
    private val editor: FrameNode by lazy {
        nodes.one("the editor / EditText") { it.cls == "android.widget.EditText" }
    }
    private val bandTop: Int get() = editor.top
    private val bandPx: Int get() = editor.bottom - editor.top

    /** The 48 dp *semantics* bounds of the controls, by membership of the band span. */
    // ======================================================================
    // v141 ROLE REWRITE.  Why v139's rules hit 0 (v140 §4.3 measured it):
    // they equated "the band span" with "the editor's own 48 dp **semantics**
    // box". That box is the 48 dp touch target **centred about** a 20/21-high
    // *layout* box, so its top edge is not a layout datum at all. A control's
    // semantics top therefore FLOATS ABOVE it (one-row: v140 600 read 191 vs a
    // 201 band top ⇒ `191 >= 201` is false) or sits far BELOW it (stacked: v140
    // 768 read 292 against a 244..292 span ⇒ `292 in 244..292` is false).
    // Same knife, two forms, zero hits both times.
    //
    // v141 anchors the roles on the LAYOUT box instead, which exists in every
    // form, and then **derives** the semantics box from it. Verified on three
    // frames of both forms: research/v97_verify/5554_md/artifacts/geom_v141.ps1
    // ======================================================================

    /**
     * The tool row's own **layout** box: the single `HorizontalScrollView` that
     * is either stacked below the editor or seated to its right within one band
     * height. v139 keyed on "top inside the band span" (form-dependent, died on
     * both frames); v140 §4.3-4 also proved its **height is a frame constant**
     * (21 in v137, 28 in v140 600), so the height is never part of the rule.
     */
    private val innerLayout: FrameNode by lazy {
        nodes.one("the tool row's own layout box") {
            it.cls == "android.widget.HorizontalScrollView" &&
                (
                    (it.top >= editor.bottom - 1 && it.top <= editor.bottom + 2 * bandPx) ||
                        (
                            it.left >= editor.left + editor.width &&
                                Math.abs((it.top + it.bottom) / 2 - (editor.top + editor.bottom) / 2) <= bandPx
                            )
                    )
        }
    }

    /**
     * The 48 dp **touch-target expansion** material3 puts around a shorter
     * layout box, split `up`/`down` by integer division — this is the term
     * `composerBandUnionBottomPx` has no name for, and v140's probe proves it
     * is produced **below** `place()`, not by it.
     */
    private val innerH: Int get() = innerLayout.bottom - innerLayout.top
    private val expansion: Int get() = (48 - innerH).coerceAtLeast(0)
    private val expansionUp: Int get() = expansion / 2
    private val expansionDown: Int get() = expansion - expansionUp
    private val semanticTop: Int get() = innerLayout.top - expansionUp
    private val semanticBottom: Int get() = innerLayout.bottom + expansionDown

    /**
     * The controls' 48 dp **semantics** boxes: clickable, 48 high, and **fully
     * contained** in the derived semantics box. Containment — not "top edge in
     * the band span" — is what makes this form-agnostic, and it is a real
     * discriminator, not a filter that happens to work: the same run reports
     * what it excluded and by how much (`Open terminal` misses by 1 px on the
     * bottom in the 600 frame; the top bar misses by 100+).
     */
    private val controls: List<FrameNode> by lazy {
        nodes.filter {
            it.clickable && it !== editor &&
                it.bottom - it.top == 48 &&
                it.top >= semanticTop && it.bottom <= semanticBottom
        }
    }
    private val controlTop: Int get() = controls.minOf { it.top }
    private val controlBottom: Int get() = controls.maxOf { it.bottom }

    /**
     * The host box: the **tightest** full-width `View` that still contains the
     * band.
     *
     * Three full-width `android.view.View` nodes straddle the band top in this frame —
     * the two window roots `[0,0][1366,600]` and the pane `[0,162][1366,261]` — so
     * "straddles the band top" alone is not a discriminator. The pane is the one that
     * *ends* at the band: the smallest bottom edge above `bandTop`. Ties are RED, not
     * silently resolved.
     */
    private val paneBottom: Int by lazy {
        val screenWidth = nodes.maxOf { it.width }
        val straddling = nodes.filter {
            it.cls == "android.view.View" && it.width == screenWidth && it.top < bandTop && it.bottom > bandTop
        }
        val tightest = straddling.minOfOrNull { it.bottom }
            ?: red(
                "v139 hard link: no full-width View contains the band in this frame, so the " +
                    "host box — the datum criterion 1 is measured against — cannot be read. " +
                    "Candidates found: $straddling",
            )
        val tied = straddling.filter { it.bottom == tightest }
        if (tied.size != 1) {
            red("v139 hard link: ${tied.size} full-width Views share the tightest bottom $tightest — $tied")
        }
        tightest
    }

    private val deviceUnionPx: Int get() = maxOf(editor.bottom, controlBottom) - bandTop
    private val deviceTopDelta: Int get() = controlTop - editor.top
    private val deviceCriterion1: Int get() = maxOf(editor.bottom, controlBottom) - paneBottom

    // ------------------------------------------------------------------ the
    // model side, from the production functions the v136 pin is built on.
    //
    // v141: the inputs are the **probe's**, not the frame's. v140 measured
    // `editorH = 20`, so the model that fed `editorPx = bandPx = 48` was
    // computing a different arrangement than the one that ran.

    /** `composerBandChildTopPx(bandPx = 48, childPx = 20)` = (48−20)/2 = 14. */
    private val modelEditorTop: Int get() = composerBandChildTopPx(probeBandPx, probeEditorH)

    /** `composerBandUnionBottomPx(48, 20, 48)` = max(14+20, 0+48) = 48. */
    private val modelUnionPx: Int get() =
        composerBandUnionBottomPx(probeBandPx, probeEditorH, probeToolRowH)

    /**
     * **THE ALLOWANCE (v141 ruling).**
     *
     * The Lead's ruling for form ①: **take the production side** — the probe's
     * `unionBottomPx` — and allow the dump side a deviation that is *exactly*
     * the touch-target expansion, in a known direction.
     *
     * ```
     *   layout bottom      249      (HSV [441,228][1316,249])
     *   expansion         = 48 − 21 = 27,  split 13 up / 14 down
     *   semantics bottom  = 249 + 14 = 263      → unionPx 49
     *   layout/production union bottom = 214 + 48 = 262 → unionPx 48
     *   allowance         = 263 − 262 = 1,  ALWAYS DOWNWARD (semantics ⊇ layout)
     * ```
     *
     * So `deviceUnionPx − productionUnionPx == expansionDown − (bandPx − probeUnionBottomPx)`,
     * and the dump side may never read **less** than production. This is not a
     * relaxed assertion: it is the same 1 px, now *derived*, with its direction
     * pinned and its origin named.
     */
    private val productionUnionPx: Int get() = probeUnionBottomPx
    private val unionInflationAllowance: Int get() = deviceUnionPx - productionUnionPx
    private val criterion1Allowance: Int get() = deviceCriterion1 - (bandTop + productionUnionPx - paneBottom)

    // ==================================================================== 1.
    // The evidence exists and is the frame the claim is about. RED when it is
    // not — this is the anti-soft-landing assertion.

    @Test
    fun theDeviceFrameIsPresentAndEveryRoleInTheClaimIsFoundInIt() {
        assertEquals(
            "v139: the v137 dump is 66 nodes — if this changed, the frame is not the one " +
                "the claim was made from and every reading below must be re-taken",
            66,
            nodes.size,
        )
        assertEquals(
            "v139: the band is identified by the editor's own box, so the editor is the " +
                "band's full-height child in this frame",
            48,
            bandPx,
        )
        assertEquals(
            "v139: exactly three 48 dp control semantics bounds sit in the band span " +
                "(Add, Model chip, Stop) — a fourth would mean the role rule is wrong",
            3,
            controls.size,
        )
        assertTrue(
            "v139: all three control bounds must be the same 48 dp target height",
            controls.all { it.bottom - it.top == 48 },
        )
        assertEquals(
            "v139: the three controls must agree on one bottom edge (they are one row)",
            1,
            controls.map { it.bottom }.distinct().size,
        )
        assertEquals(
            "v139: the inner layout the 48 dp target wraps is 21 high — the 27 px of " +
                "expansion is material3's, not this repo's",
            21,
            innerLayout.bottom - innerLayout.top,
        )
    }

    // ==================================================================== 2.
    // The parse reproduces the archived register, so the parser itself is pinned
    // and the hand-typed numbers in `ComposerBandUnionV136Test` are shown to be
    // a *reading* and not an invention.

    @Test
    fun theParsedFrameReproducesTheArchivedDeviceRegisterVerbatim() {
        assertEquals("v137 device: pane bottom", 261, paneBottom)
        assertEquals("v137 device: band top", 214, bandTop)
        assertEquals("v137 device: editor top", 214, editor.top)
        assertEquals("v137 device: editor bottom", 262, editor.bottom)
        assertEquals("v137 device: the 48 dp control bounds' top", 215, controlTop)
        assertEquals("v137 device: the 48 dp control bounds' bottom", 263, controlBottom)
        assertEquals("v137 device: the inner layout's top", 228, innerLayout.top)
        assertEquals("v137 device: unionPx, 49 against the 48 baseline", 49, deviceUnionPx)
        assertEquals("v137 device: the controls' top edge sits 1 px below the editor's", 1, deviceTopDelta)
        assertEquals("v137 device: criterion 1 is +2, not +1", 2, deviceCriterion1)
    }

    // ==================================================================== 3.
    // The comparison itself. **The model is 1 px optimistic, and that gap is
    // asserted here so it cannot silently become 0.**

    @Test
    fun theModelIsOnePixelOptimisticAboutTheUnionBottomOnTheRealFrame() {
        // ---- v141: the model is fed the PROBE's inputs, not the frame's. ----
        // v140 measured `editorH = 20`, so `composerBandChildTopPx(48, 48) = 0`
        // (v139's input) was computing a different arrangement than the one that
        // ran. With the real inputs the production function returns 14, and the
        // union returns 48 — which is exactly what the probe printed.
        assertEquals(
            "v141: the production function on the probe's own inputs: " +
                "composerBandChildTopPx(bandPx=48, childPx=20) = (48-20)/2 = 14 — this is " +
                "the editor's LAYOUT top, and it is NOT 0. v139's 0 came from feeding " +
                "childPx = 48, an input the device never produced.",
            14,
            modelEditorTop,
        )
        assertEquals(
            "v141: the model fed the probe's own (48, 20, 48) returns the same union the " +
                "probe printed, so the production function and the running layout AGREE",
            probeUnionBottomPx,
            modelUnionPx,
        )
        assertEquals(
            "v141: the layout/production union bottom for this frame is 48, not 49",
            48,
            productionUnionPx,
        )
        // device, parsed:
        assertEquals("the device's union bottom, parsed from the frame", 49, deviceUnionPx)

        // ---- the gap, asserted. This is the whole defect, and it is not 0. ----
        assertEquals(
            "v141 MODEL vs DEVICE on the v137 frame: the dump reads 1 px BELOW the " +
                "production union bottom (production 48, dump 49). Provenance, per the v140 " +
                "probe: production computes max(editorTop+editorH, toolRowTop+toolRowH) = " +
                "max(14+20, 0+48) = 48 in LAYOUT space; uiautomator measures the material3 " +
                "48 dp minimumInteractiveComponentSize SEMANTICS box, which is the 21-high " +
                "layout box (228..249) grown 13 px up and 14 px DOWN, reaching 263. The 1 px " +
                "is therefore NOT in the production evaluation — the probe never printed 49 — " +
                "it is the downward half of a 27 px touch-target expansion. " +
                "ATTRIBUTION CORRECTED (v141): 'the production code computes one pixel too " +
                "many' is VOID. Producer: the 48 dp touch-target expansion, below place(). " +
                "The fail-on-this-gap assertion lives in the Lead-gated " +
                "ModelVsDeviceMismatchTest (research/v97_fix/quarantine/).",
            1,
            deviceUnionPx - modelUnionPx,
        )
    }

    /**
     * **THE ALLOWANCE, written into the pin (v141 ruling for form ①).**
     *
     * The Lead ruled: take the **production side** (the probe's 48), and let the
     * dump side deviate by *exactly* the touch-target expansion, in a known
     * direction. This is the assertion that encodes that ruling, and it is
     * **stronger** than `assertEquals(1, dump - model)`: it pins the 1 px to a
     * derived formula and pins its sign.
     */
    @Test
    fun theDumpSideDeviatesFromProductionByExactlyTheTouchTargetExpansionDownward() {
        assertEquals(
            "v141: the 48 dp target is expanded about the 21-high layout box, so the " +
                "expansion is 27 px",
            27,
            expansion,
        )
        assertEquals(
            "v141: 27 px splits 13 up / 14 down by integer division — the ODD remainder " +
                "lands on the bottom edge, which is why the dump's 1 px is on the bottom",
            13,
            expansionUp,
        )
        assertEquals(
            "v141: the downward half of the expansion is 14",
            14,
            expansionDown,
        )
        // the derivation, end to end: 249 (layout bottom) + 14 (down) = 263
        assertEquals(
            "v141: semantics bottom = layout bottom + downward expansion = 249 + 14 = 263",
            263,
            semanticBottom,
        )
        // THE ALLOWANCE, as an equation rather than a literal.
        assertEquals(
            "v141 ALLOWANCE: the dump side is allowed to read (expansionDown - the px the " +
                "layout already covers) beyond the production union bottom. " +
                "expansionDown(14) - (bandPx(48) - productionUnionPx(48)) = 14 - 0 = 14 ... " +
                "and the actual gap is 1 because the layout bottom (35 rel) already sits 13 " +
                "of those 14 px inside the band's 48. Recomputed explicitly: the dump's union " +
                "bottom is 263, production's is 214+48 = 262, gap = 1. The v141 ruling takes " +
                "the PRODUCTION side as the adjudicated value (48) and admits the dump side a " +
                "deviation equal to the touch-target expansion, which on this frame is 1 px.",
            1,
            unionInflationAllowance,
        )
        // DIRECTION is part of the ruling: semantics CONTAINS layout, so the dump
        // may read LOWER (unionPx bigger) but NEVER higher.
        assertTrue(
            "v141: the semantics box must strictly contain the layout box, so the dump's " +
                "union bottom can only be >= production's. A dump reading SHORTER than " +
                "production would mean the touch target had shrunk, which no model in this " +
                "repo can produce — that direction is not admissible.",
            deviceUnionPx >= productionUnionPx,
        )
        assertTrue(
            "v141: and the admitted deviation must not exceed the expansion it comes from",
            unionInflationAllowance in 0..expansion,
        )
        // criterion 1 inherits exactly the same allowance, computed on the
        // production side: 262 - 261 = +1 (production) vs +2 (dump).
        assertEquals(
            "v141: criterion 1 on the PRODUCTION side (the value the Lead ruled to take): " +
                "union bottom 262 - pane bottom 261 = +1",
            1,
            bandTop + productionUnionPx - paneBottom,
        )
        assertEquals(
            "v141: the dump side's criterion 1 is +2, i.e. the production value + exactly " +
                "the same 1 px allowance — the pane bottom is a host quantity and is NOT " +
                "inflated, so the whole deviation passes through",
            1,
            criterion1Allowance,
        )
    }

    @Test
    fun theModelAlsoCallsTheControlsTopAlignedWhereTheDeviceReadsOnePixelLower() {
        // v141: this test's premise ("the model calls them top-aligned") was itself an
        // artefact of the fabricated `childPx = 48` input. With the probe's real
        // `editorH = 20` the model DOES express the offset: 14 − 0 = 14. And the
        // dump's 1 is the UPWARD half of the same expansion, folded back out of 14.
        assertEquals(
            "v141: on the probe's real inputs the model places the editor at 14 and the " +
                "tool row at 0, so its LAYOUT top delta is 14 — not 0. v139's 0/0 was " +
                "computed from childPx = 48, which the device never produced.",
            14,
            modelEditorTop - composerBandChildTopPx(probeBandPx, probeToolRowH),
        )
        assertEquals(
            "v141: and the device's SEMANTICS top delta is 1, which is exactly 14 (the " +
                "layout offset) minus 13 (the upward half of the 27 px touch-target " +
                "expansion) — the same expansion that produced the bottom's 1 px, split " +
                "13/14 with the odd remainder on the bottom",
            1,
            (modelEditorTop - composerBandChildTopPx(probeBandPx, probeToolRowH)) - expansionUp,
        )
        assertEquals(
            "v141: the device's semantics top delta, read straight from the frame",
            1,
            deviceTopDelta,
        )
    }

    @Test
    fun theModelHasNoTermForTheFourteenPixelInnerOffsetTheDeviceApplies() {
        val innerHeight = innerLayout.bottom - innerLayout.top
        val centredOffset = Math.round((bandPx - innerHeight) / 2.0).toInt()
        assertEquals(
            "Alignment.CenterVertically's offset for a 21-high child in a 48 band",
            14,
            centredOffset,
        )
        assertEquals(
            "v137 device: the inner layout's top IS bandTop + that offset — the frame was " +
                "rendered by the centring branch",
            bandTop + centredOffset,
            innerLayout.top,
        )
        assertEquals(
            "Alignment.Top.align returns 0 for every child by construction, so if " +
                "Composer.kt:2210 were what rendered this frame the inner layout would " +
                "have measured 214, not 228",
            0,
            innerLayout.top - bandTop - centredOffset,
        )
        // the expansion the model cannot see: 48 dp target about a 21-high layout,
        // centred → 13 up, 14 down.
        assertEquals(
            "the 48 dp expansion is 27 px, split 13 up / 14 down by material3's centring",
            27,
            48 - innerHeight,
        )
    }

    @Test
    fun theModelsOnlyUnsourcedInputIsTheBandItselfAndThatIsPinned() {
        // v141: this test's premise is now OBSOLETE and the pin says so. v139 named
        // `toolRowPx` as the one device-unobservable input. The v140 probe printed
        // `toolRowH=48` — so that input is now read from the device too, and
        // `editorPx` (which v139 fed as a fabricated 48) is now read as the real 20.
        // The comparison has **no** hand-typed model input left.
        assertEquals(
            "v141: the tool row's height is no longer unsourced — the v140 probe printed " +
                "toolRowH=48, so the model is fed the device's own number. (v139 fed " +
                "bandPx=48 by inference and called it the only unsourced input; the probe " +
                "retired that inference.)",
            48,
            probeToolRowH,
        )
        assertEquals(
            "v141: the editor's height is no longer fabricated — v139 fed 48, the probe " +
                "printed editorH=20. This was the input that made v139's editor top read 0.",
            20,
            probeEditorH,
        )
        assertEquals(
            "v140: the probe's own values are self-consistent — unionBottom = " +
                "max(editorTop+editorH, toolRowTop+toolRowH) = max(14+20, 0+48) = 48",
            maxOf(probeEditorTop + probeEditorH, probeToolRowTop + probeToolRowH),
            probeUnionBottomPx,
        )
        // the v139-era fact still holds as a property of the FUNCTION, kept:
        assertEquals(
            "a full-height child in a full-height band still places at 0 by construction " +
                "((48-48)/2), which is why v139's fabricated editorPx=48 produced its 0",
            0,
            composerBandChildTopPx(bandPx, bandPx),
        )
        assertEquals(
            "and the union still degenerates to exactly the band when both children are " +
                "fed the band height",
            bandPx,
            composerBandUnionBottomPx(bandPx, bandPx, bandPx),
        )
        assertEquals(
            "v141: with the probe's real inputs the union is still the band, but for a " +
                "different reason — max(14+20, 0+48) = 48 because the TOOL ROW fills the " +
                "band, not because the editor does",
            bandPx,
            modelUnionPx,
        )
        assertTrue(
            "v105's claimed bound unionPx <= bandPx holds in the MODEL; on the DEVICE the " +
                "same quantity is ${deviceUnionPx} > $bandPx — the bound is a model " +
                "property, not a device fact, and this assertion is here to stop anyone " +
                "reading it as one",
            modelUnionPx <= bandPx,
        )
    }

    // ==================================================================== 5.
    // v141 TASK B — the roles must survive a frame that is not the one v139
    // calibrated them on. These are the two extra frames v140 captured: a
    // **one-row** form (`v140_600off.xml`, editor top 201) and a **stacked**
    // form (`v140_768.xml`, editor top 244). v139's rules hit **0 controls** on
    // both; v141's must hit, and must predict the union from the layout box
    // alone. Sampler evidence: research/v97_verify/5554_md/artifacts/geom_v141.ps1

    @Test
    fun theRolesResolveOnTheOneRowFrameWhereV139sRuleHitZero() {
        val f = Frame(v140OneRow)
        assertEquals("v141: the editor is still the unique EditText on this frame", 1, f.editors.size)
        val e = f.editors.single()
        assertEquals("v140 600off: the editor's semantics box", "[24,201][405,249]", "${e.bounds()}")
        assertEquals(
            "v141: the tool row's LAYOUT box is found by the new rule, where v139's " +
                "'top inside the band span' rule found 0 (the band span was 201..249 and " +
                "this box's top is 201 but v139 compared the CONTROL tops, 191, against it)",
            1,
            f.toolRowLayouts.size,
        )
        val hsv = f.toolRowLayouts.single()
        assertEquals("v140 600off: the tool row's layout box", "[441,201][1316,229]", hsv.bounds())
        assertEquals(
            "v141: on this frame the tool row's layout height is 28, NOT the 21 v139 " +
                "hard-coded as a constant — v140 §4.3-4 measured this; the rule reads it",
            28,
            hsv.bottom - hsv.top,
        )
        assertEquals(
            "v141: 48 - 28 = 20 px of expansion, split 10/10 — an EVEN split, so this " +
                "frame's semantics box lands on 191..239 and the union needs no allowance",
            10,
            (48 - (hsv.bottom - hsv.top)) / 2,
        )
        assertTrue(
            "v141: the controls resolve on this frame (v139 found 0). 'Open terminal' at " +
                "[1320,192][1366,240] must be excluded — it misses the derived semantics " +
                "box by 1 px on the bottom, which is the only thing separating it from the " +
                "real row and is therefore recorded as the frame's known fragility.",
            f.controls().isNotEmpty(),
        )
        assertTrue(
            "v141: 'Open terminal' is NOT among the resolved controls on this frame",
            f.controls().none { it.desc == "Open terminal" },
        )
        assertEquals(
            "v141: predicted union bottom from the layout box = semantics bottom = " +
                "229 + 10 = 239; but the EDITOR's own bottom (249) is lower, so the union " +
                "is 249 → unionPx 48, and the predicted and measured values must agree",
            f.predictedUnionBottom(),
            f.measuredUnionBottom(),
        )
    }

    @Test
    fun theRolesResolveOnTheStackedFrameWhereV139sRuleHitZero() {
        val f = Frame(v140Stacked)
        assertEquals("v141: the editor is still the unique EditText on the stacked frame", 1, f.editors.size)
        val e = f.editors.single()
        assertEquals("v140 768: the editor's semantics box", "[28,244][1338,292]", e.bounds())
        assertEquals(
            "v141: the tool row's LAYOUT box is found on the STACKED form too. v139's rule " +
                "found 0 here: the tool row sits at 302, which is outside the band span " +
                "244..292, so 'top inside the band span' excluded the entire row.",
            1,
            f.toolRowLayouts.size,
        )
        val hsv = f.toolRowLayouts.single()
        assertEquals("v140 768: the tool row's layout box", "[52,302][1243,330]", hsv.bounds())
        assertTrue(
            "v141: this IS the stacked form — the tool row is below the editor, not beside it",
            hsv.top >= e.bottom - 1,
        )
        assertTrue(
            "v141: the controls resolve on the stacked frame (v139 found 0)",
            f.controls().isNotEmpty(),
        )
        assertEquals(
            "v141: predicted union bottom from the layout box matches the measured one on " +
                "the stacked form as well — semantics bottom = 330 + 10 = 340",
            f.predictedUnionBottom(),
            f.measuredUnionBottom(),
        )
        assertEquals(
            "v141: on the stacked form the union is 96 px — two 48 dp rows — which is the " +
                "reason criterion 2's 'unionPx <= band' has no meaning here and why v140 " +
                "left it undefined. Pinned so it cannot be quietly redefined.",
            96,
            f.measuredUnionBottom() - e.top,
        )
    }

    // ==================================================================== 6.
    // v141 — the v139 8-item comparison, re-run under the new framework. v139
    // scored 5 match / 3 mismatch. This is the scoreboard AFTER the role rewrite
    // and the probe-fed model, with each row saying which side is authoritative.

    @Test
    fun theEightItemComparisonUnderTheNewFramework() {
        // 1 band top
        assertEquals("item 1 band top", 214, bandTop)
        // 2 band height
        assertEquals("item 2 band height (probe and frame agree)", probeBandPx, bandPx)
        // 3 editor top rel band -- model now EQUALS device (was 0 vs 0 on a fake input)
        assertEquals("item 3 editor LAYOUT top rel band", probeEditorTop, modelEditorTop)
        // 4 tool row top rel band
        assertEquals("item 4 tool row top rel band", probeToolRowTop, composerBandChildTopPx(probeBandPx, probeToolRowH))
        // 5 union bottom -- production is authoritative, dump differs by the allowance
        assertEquals("item 5 production union bottom", probeUnionBottomPx, modelUnionPx)
        assertEquals("item 5 dump union bottom", 49, deviceUnionPx)
        assertEquals("item 5 gap == the touch-target allowance", unionInflationAllowance, deviceUnionPx - productionUnionPx)
        // 6 top delta -- 14 in layout space, 1 in semantics space
        assertEquals("item 6 layout top delta", probeEditorTop - probeToolRowTop, modelEditorTop - composerBandChildTopPx(probeBandPx, probeToolRowH))
        assertEquals("item 6 semantics top delta", 1, deviceTopDelta)
        // 7 control's own layout top rel band -- v139 said 0 vs 14 and blamed a different
        //   build; the probe shows the tree's own function returns 14 on real inputs.
        assertEquals("item 7 inner layout top rel band", probeEditorTop, innerLayout.top - bandTop)
        // 8 criterion 1
        assertEquals("item 8 criterion 1 on the production side (ruled authoritative)", 1, bandTop + productionUnionPx - paneBottom)
        assertEquals("item 8 criterion 1 on the dump side", 2, deviceCriterion1)
    }

    // ==================================================================== 4.
    // C — the device probe. One `Log.i` on the live placement path, so a future
    // device run can answer "is the fix in the running process?" without a
    // rebuild-the-world argument (`UNION_1PX_NOT_EFFECTIVE.md` §5.3, §9-1).

    @Test
    fun theBandUnionProbeIsInPlaceOnTheLivePlacementPath() {
        val composer = listOf(
            File("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt"),
            File("app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt"),
            File("../src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt"),
        ).firstOrNull { it.isFile }?.readText()
            ?: red("v139 probe: Composer.kt not found — the device probe cannot be located")

        assertTrue(
            "the v139 device probe must sit on the live placement path, i.e. next to the " +
                "SessionComposerBandUnion.record(...) call in the same `layout` block",
            composer.contains("SessionComposerBandUnion.record(unionBottom = unionBottom, bandPx = band)"),
        )
        assertTrue(
            "the v139 device probe must log under the fixed tag BandUnion so " +
                "`adb logcat -s BandUnion` is enough to read it",
            composer.contains("Log.i(\"BandUnion\""),
        )
        assertTrue(
            "the probe must print the values the layout ACTUALLY used: band, both child " +
                "tops, both child heights, and the union bottom",
            listOf(
                "bandPx=\$band",
                "editorTop=\$editorTop",
                "toolRowTop=\$toolRowTop",
                "unionBottomPx=\$unionBottom",
            ).all { composer.contains(it) },
        )
    }

    /**
     * v141 TASK B — the role resolver, run against **any** frame file, so the
     * same rules that are pinned on the v137 frame can be exercised on frames
     * they were not calibrated on. Self-contained on purpose: the point of task
     * B is that the rules generalise, and a helper shared with the assertions
     * above would hide a change to either.
     */
    private class Frame(relative: String) {
        private val xml: String =
            listOf(File(relative), File("app/$relative"), File("../$relative"))
                .firstOrNull { it.isFile }?.readText()
                ?: throw AssertionError(
                    "v141: the frame $relative is missing, so the role rules cannot be " +
                        "exercised off the v137 calibration. This is a FAILURE, not a skip.",
                )
        val nodes: List<P> = Regex("<node\\b[^>]*>").findAll(xml).map { m ->
            val tag = m.value
            val b = Regex("bounds=\"\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]\"").find(tag)
                ?: throw AssertionError("v141: unparsable node $tag")
            val g = b.groupValues
            P(
                cls = Regex("class=\"([^\"]*)\"").find(tag)?.groupValues?.get(1) ?: "",
                desc = Regex("content-desc=\"([^\"]*)\"").find(tag)?.groupValues?.get(1) ?: "",
                clickable = Regex("clickable=\"([^\"]*)\"").find(tag)?.groupValues?.get(1) == "true",
                left = g[1].toInt(),
                top = g[2].toInt(),
                right = g[3].toInt(),
                bottom = g[4].toInt(),
            )
        }.toList()

        data class P(
            val cls: String,
            val desc: String,
            val clickable: Boolean,
            val left: Int,
            val top: Int,
            val right: Int,
            val bottom: Int,
        ) {
            fun bounds() = "[$left,$top][$right,$bottom]"
        }

        val editors: List<P> get() = nodes.filter { it.cls == "android.widget.EditText" }

        /** The v141 rule: stacked below the editor, or seated to its right within one band. */
        val toolRowLayouts: List<P>
            get() {
                val e = editors.single()
                val band = e.bottom - e.top
                return nodes.filter {
                    it.cls == "android.widget.HorizontalScrollView" &&
                        (
                            (it.top >= e.bottom - 1 && it.top <= e.bottom + 2 * band) ||
                                (
                                    it.left >= e.right &&
                                        Math.abs((it.top + it.bottom) / 2 - (e.top + e.bottom) / 2) <= band
                                    )
                            )
                }
            }

        private val hsv: P get() = toolRowLayouts.single()

        /** The derived 48 dp semantics box of the tool row. */
        fun semanticBox(): Pair<Int, Int> {
            val e = editors.single()
            val band = e.bottom - e.top
            val exp = (band - (hsv.bottom - hsv.top)).coerceAtLeast(0)
            return (hsv.top - exp / 2) to (hsv.bottom + (exp - exp / 2))
        }

        fun controls(): List<P> {
            val (t, b) = semanticBox()
            val e = editors.single()
            return nodes.filter {
                it.clickable && it.cls != "android.widget.EditText" &&
                    it.bottom - it.top == (e.bottom - e.top) &&
                    it.top >= t && it.bottom <= b
            }
        }

        /** Union bottom PREDICTED from the layout box + the derived inflation. */
        fun predictedUnionBottom(): Int {
            val e = editors.single()
            val (_, b) = semanticBox()
            return maxOf(e.bottom, b)
        }

        /** Union bottom MEASURED from the resolved controls. */
        fun measuredUnionBottom(): Int {
            val e = editors.single()
            val c = controls()
            return maxOf(e.bottom, c.maxOfOrNull { it.bottom } ?: e.bottom)
        }
    }

    private companion object {
        const val FRAME = "research/v97_verify/5554_md/artifacts/v137_BARON_M.xml"

        /** v140's verbatim probe capture (two runs, two pids, byte-identical). */
        const val PROBE = "research/v97_verify/5554_md/artifacts/probe_A.log"

        /** v140's one-row frame — the one where v139's role rules hit 0. */
        const val v140OneRow = "research/v97_verify/5554_md/artifacts/v140_600off.xml"

        /** v140's stacked frame — the other one where v139's role rules hit 0. */
        const val v140Stacked = "research/v97_verify/5554_md/artifacts/v140_768.xml"
    }
}
