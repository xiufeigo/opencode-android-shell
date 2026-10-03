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
        val top: Int,
        val bottom: Int,
    )

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
    private val controls: List<FrameNode> by lazy {
        nodes.filter { it.clickable && it !== editor && it.top in bandTop..(bandTop + bandPx) }
    }
    private val controlTop: Int get() = controls.minOf { it.top }
    private val controlBottom: Int get() = controls.maxOf { it.bottom }

    /** The 21-high *layout* the 48 dp target wraps — the term the model has no name for. */
    private val innerLayout: FrameNode by lazy {
        nodes.one("the controls' own inner layout") {
            it.cls == "android.widget.HorizontalScrollView" &&
                it.top in bandTop..(bandTop + bandPx)
        }
    }

    /**
     * The host box: the **tightest** full-width `View` that still contains the band.
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

    /**
     * The tool row's own height is **not observable in the frame**: it carries
     * `Modifier.height(BAND_HEIGHT)` (`Composer.kt:2189`) and has no semantics
     * node of its own, so uiautomator never prints it. v138 §3 established it is
     * coerced to exactly `band`; that inference is the one model input this class
     * cannot source from the device, and it is pinned by
     * [theModelsOnlyUnsourcedInputIsTheBandItselfAndThatIsPinned].
     */
    private val modelEditorTop: Int get() = composerBandChildTopPx(bandPx, bandPx)
    private val modelUnionPx: Int get() = composerBandUnionBottomPx(bandPx, bandPx, bandPx)

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
        // model, from production, fed the device's own band:
        assertEquals("the model places a full-height child flush at the band's top", 0, modelEditorTop)
        assertEquals(
            "the model's union bottom for the arrangement's own (band, band, band) triple",
            48,
            modelUnionPx,
        )
        // device, parsed:
        assertEquals("the device's union bottom, parsed from the frame", 49, deviceUnionPx)

        // ---- the gap, asserted. This is the whole defect, and it is not 0. ----
        assertEquals(
            "MODEL vs DEVICE on the v137 frame: the model is 1 px optimistic about the " +
                "union bottom (model 48, device 49). The 1 px is produced below " +
                "place() by the material3 48 dp minimumInteractiveComponentSize " +
                "expanding each control's semantics bounds about a 21-high layout — a " +
                "term composerBandUnionBottomPx has no name for. PENDING FIX: " +
                "Composer.kt:2210 (verticalAlignment = if (minimal) Alignment.Top …), " +
                "which is the only line of the v136 diff that can move a pixel, and " +
                "which the v137 frame proves was NOT what rendered it (the frame " +
                "carries the Alignment.CenterVertically fingerprint, 214 + 14 = 228). " +
                "The fail-on-this-gap assertion lives in the Lead-gated " +
                "ModelVsDeviceMismatchTest (research/v97_fix/quarantine/) — move it " +
                "into the test source set when the parity is actually paid for.",
            1,
            deviceUnionPx - modelUnionPx,
        )
    }

    @Test
    fun theModelAlsoCallsTheControlsTopAlignedWhereTheDeviceReadsOnePixelLower() {
        // The model places BOTH band children through the same rule, and on this
        // form that rule returns 0 for both, so the model's top delta is 0 by
        // construction — it has no way to express a half-pixel rounding.
        assertEquals(
            "the model places the editor at 0 and the tool row at 0, so its top delta is 0",
            0,
            composerBandChildTopPx(bandPx, bandPx) - composerBandChildTopPx(bandPx, bandPx),
        )
        assertEquals(
            "MODEL vs DEVICE: the model says the control bounds are top-aligned with the " +
                "editor; the frame says they are 1 px lower",
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
        // `toolRow.height` is fed to the production function as `bandPx`, because the
        // tool row carries `Modifier.height(BAND_HEIGHT)` (`Composer.kt:2189`) and has
        // no node in the frame. That inference is the single device-unobservable input
        // in the comparison, so it is stated here rather than buried.
        assertEquals(
            "the tool row is coerced to the band height, which is why the production " +
                "rule degenerates to 0 on this form (v138 §3)",
            0,
            composerBandChildTopPx(bandPx, bandPx),
        )
        assertEquals(
            "and the same coercion is why the union degenerates to exactly the band",
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

    private companion object {
        const val FRAME = "research/v97_verify/5554_md/artifacts/v137_BARON_M.xml"
    }
}
