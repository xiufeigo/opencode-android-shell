package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout.composerBandChildTopPx
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout.composerBandUnionBottomPx
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * ============================================================================
 * LEAD-GATED CLASS — NOT PART OF THE MAIN GATE. THIS FILE IS **NOT COMPILED.**
 * ============================================================================
 *
 * v139 **A**, second half. Its entire content is three assertions of the form
 *
 *     assertEquals("<what the device said>", deviceValue, modelValue)
 *
 * i.e. **"the model's answer must equal the device's answer"**, with the device
 * side parsed out of the real `uiautomator` frame
 * `research/v97_verify/5554_md/artifacts/v137_BARON_M.xml` and the model side
 * computed by the production functions
 * `SessionPaneLayout.composerBandChildTopPx` / `composerBandUnionBottomPx`.
 *
 * ---------------------------------------------------------------------------
 * WHY IT IS NOT IN THE TEST SOURCE SET
 * ---------------------------------------------------------------------------
 * It is **RED right now, by design and by arithmetic**: the model answers 48
 * where the device answers 49, the model says the two band children are
 * top-aligned where the device reads 1 px apart. Putting it in
 * `app/src/test/java/` would make `:app:testDebugUnitTest` red, and the Lead
 * explicitly ruled (`PIN_DEVICE_HARDLINK.md` task 3) that the main gate must not
 * be turned red by this task, and that this assertion must be *expressed as
 * fail-on-mismatch* and parked in a class whose admission the Lead decides.
 *
 * The only way to honour both, without `app/build.gradle.kts` (not writable) and
 * without `@Ignore` (ruled out: it drops the trigger and cuts the case count),
 * is to keep the class **outside every compiled source set**. So it lives here,
 * in `research/`, which is the one writable location that is not compiled.
 *
 * The gate is not left blind in the meantime: `ComposerBandDeviceTruthTest`
 * (in the test source set) parses the *same* frame, asserts the *same* device
 * readings, and asserts the *size of the gap* — so the parity cannot silently
 * become 0 while this class is parked. Both halves fail loudly; neither is
 * softened.
 *
 * ---------------------------------------------------------------------------
 * HOW THE LEAD ADMITS IT
 * ---------------------------------------------------------------------------
 * When `Composer.kt:2210` is actually fixed and a fresh device dump shows
 * `unionPx 48` / `topDelta 0`, copy this file into the test source set and the
 * main gate takes over the parity for free:
 *
 *     Copy-Item research\v97_fix\quarantine\ModelVsDeviceMismatchTest.kt `
 *              app\src\test\java\com\xiufeigo\opencodeshell\ModelVsDeviceMismatchTest.kt
 *
 * It is deliberately **self-contained** (its own parser, no shared helper) so
 * that move is a single file copy with no other edit, and so it cannot be
 * broken by a change to the gate class. Its own device readings are pinned
 * against the archived register first, so if the XML is re-taken, this class
 * fails on *that* instead of quietly comparing against a stale frame.
 *
 * ---------------------------------------------------------------------------
 * PROOF IT IS RED (v139, this round)
 * ---------------------------------------------------------------------------
 * It was copied into the test source set, run, and observed failing; the log is
 * `research/v97_fix/_v139_run_red.log` and the transcript is §4 of
 * `research/v97_fix/PIN_DEVICE_HARDLINK.md`. It was then removed again, which
 * is why the main gate is green.
 * ============================================================================
 */
class ModelVsDeviceMismatchTest {

    private fun red(why: String): Nothing = throw AssertionError(why)

    // ------------------------------------------------------------------ the
    // device side: parsed from the v137 `uiautomator dump`, nothing typed.

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
                ?: red("v139 quarantine: a node has no parsable bounds: $tag")
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
            red("v139 quarantine: 0 nodes parsed out of the device frame — the readout file is truncated.")
        }
        return nodes
    }

    private fun List<FrameNode>.one(what: String, pred: (FrameNode) -> Boolean): FrameNode {
        val hits = filter(pred)
        if (hits.size != 1) {
            red("v139 quarantine: expected exactly 1 device node for [$what], found ${hits.size} — $hits")
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
                "v139 quarantine: the device readout file is missing. The model-vs-device " +
                    "parity CANNOT be judged without it, so this is a FAILURE, never a skip.",
            )
    }

    private val nodes: List<FrameNode> by lazy { parseNodes(frameXml) }
    private val editor: FrameNode by lazy {
        nodes.one("the editor / EditText") { it.cls == "android.widget.EditText" }
    }
    private val bandTop: Int get() = editor.top
    private val bandPx: Int get() = editor.bottom - editor.top
    private val controls: List<FrameNode> by lazy {
        nodes.filter { it.clickable && it !== editor && it.top in bandTop..(bandTop + bandPx) }
    }
    private val controlTop: Int get() = controls.minOf { it.top }
    private val controlBottom: Int get() = controls.maxOf { it.bottom }
    private val innerLayout: FrameNode by lazy {
        nodes.one("the controls' own inner layout") {
            it.cls == "android.widget.HorizontalScrollView" &&
                it.top in bandTop..(bandTop + bandPx)
        }
    }

    private val deviceUnionPx: Int get() = maxOf(editor.bottom, controlBottom) - bandTop
    private val deviceTopDelta: Int get() = controlTop - editor.top
    private val deviceInnerTopPx: Int get() = innerLayout.top - bandTop

    // ------------------------------------------------------------------ 1.
    // The evidence is the archived frame, pinned before it is compared. If the
    // XML is re-taken this fails HERE — which is the only honest way to notice,
    // because a re-taken frame must be re-read, not silently re-interpreted.

    @Test
    fun theDeviceReadingsAreTheArchivedFrameAndNotAStaleCopy() {
        assertEquals("v137 device: the dump is 66 nodes", 66, nodes.size)
        assertEquals("v137 device: band top", 214, bandTop)
        assertEquals("v137 device: band px", 48, bandPx)
        assertEquals("v137 device: editor bottom", 262, editor.bottom)
        assertEquals("v137 device: the 48 dp control bounds' top", 215, controlTop)
        assertEquals("v137 device: the 48 dp control bounds' bottom", 263, controlBottom)
        assertEquals("v137 device: the inner layout's top", 228, innerLayout.top)
        assertEquals("v137 device: the inner layout is 21 high", 21, innerLayout.bottom - innerLayout.top)
        assertEquals("v137 device: three control bounds in the band span", 3, controls.size)
        assertEquals("v137 device: unionPx", 49, deviceUnionPx)
    }

    // ------------------------------------------------------------------ 2.
    // **THE PARITY.** Fail on any disagreement. RED today, on all three.

    @Test
    fun theModelsUnionBottomMustEqualTheDevices() {
        assertEquals(
            "PARITY 1/3 — the model's answer for the union bottom of the one-row band " +
                "must equal what the device measures. It does not: 模型比真机乐观 1px, " +
                "待修 Composer.kt:2210 (verticalAlignment = if (minimal) Alignment.Top …) — " +
                "the 1 px is material3's 48 dp minimumInteractiveComponentSize expanding " +
                "each control's semantics bounds about a 21-high layout, a term " +
                "composerBandUnionBottomPx has no name for. This assertion is " +
                "fail-on-mismatch and stays red until a device dump reads 48.",
            deviceUnionPx,
            composerBandUnionBottomPx(bandPx = bandPx, editorPx = bandPx, toolRowPx = bandPx),
        )
    }

    @Test
    fun theModelsChildTopDeltaMustEqualTheDevices() {
        val modelEditorTop = composerBandChildTopPx(bandPx = bandPx, childPx = bandPx)
        val modelToolRowTop = composerBandChildTopPx(bandPx = bandPx, childPx = bandPx)
        assertEquals(
            "PARITY 2/3 — the model's top delta between the two band children must equal " +
                "the device's. It does not: 模型比真机乐观 1px, 待修 Composer.kt:2210 — the " +
                "model places both children at 0 and has no way to express Compose's " +
                "roundToInt((band - child) / 2f) = 14 offset or the 48 dp touch-target " +
                "expansion that pushes the controls' semantics bounds to +1.",
            deviceTopDelta,
            modelToolRowTop - modelEditorTop,
        )
    }

    @Test
    fun theModelsInnerChildTopMustEqualTheDevices() {
        // v139 CORRECTION to my own first draft of this assertion. I first compared the
        // device's inner offset (14) against the model's *centring* branch and **it
        // passed** — which is itself the finding: the 14 is arithmetically consistent
        // with `Alignment.CenterVertically`, so "the offset looks like centring" is not
        // evidence about which branch ran. The real third mismatch is between the
        // **code in the tree** and the device:
        //
        //   source  `Composer.kt:2210`  = `if (minimal) Alignment.Top`  ⇒ offset 0
        //   device  v137 frame          = the 21-high layout at bandTop + 14
        //
        // `Alignment.Top.align` returns 0 for every child by construction, so if the
        // code in this tree had rendered the frame the inner layout would have measured
        // 214, not 228. This is `UNION_1PX_NOT_EFFECTIVE.md` §5.3 as an assertion.
        val composerSource = listOf(
            File("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt"),
            File("app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt"),
            File("../src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt"),
        ).firstOrNull { it.isFile }?.readText()
            ?: red("v139 quarantine: Composer.kt not found — the branch cannot be read")

        assertTrue(
            "v139 quarantine: the one-row Row must still declare the `minimal` branch on " +
                "verticalAlignment, otherwise this assertion is testing nothing",
            composerSource.contains(
                "verticalAlignment = if (minimal) Alignment.Top else Alignment.CenterVertically",
            ),
        )
        assertEquals(
            "PARITY 3/3 — the inner layout's top the code in this tree would produce must " +
                "equal the one the device measured. It does not: the tree says " +
                "Alignment.Top (which returns 0 for every child, so bandTop " +
                "$bandTop), the v137 frame measured $bandTop + 14 = ${innerLayout.top}. " +
                "⇒ the v136 change was not what rendered the frame. 待裁定: the fix at " +
                "Composer.kt:2210 is correct as written and still has to be *proven* on a " +
                "device run that is provably using this build (see the BandUnion probe).",
            deviceInnerTopPx,
            0,
        )
    }
}
