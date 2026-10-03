package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.components.OcPopoverCloseReason
import com.xiufeigo.opencodeshell.ui.session.composer.modelChipTouchHeightDp
import com.xiufeigo.opencodeshell.ui.session.composer.modelChipTouchOverflowDp
import com.xiufeigo.opencodeshell.ui.session.composer.modelOpenRequestArrived
import com.xiufeigo.opencodeshell.ui.session.composer.restoresFocusOnUnmount
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v75：模型 chip 的三条命中几何缺陷（`emulator-5554`，720x1280 @420dpi 的短窗与
 * 1344x2992 @3x 的常规窗）。
 *
 * 1. **短窗 chip 点不开**：短窗上 composer 的形态由 body 高度裁决，而模型选择器
 *    自己的焦点变化会把键盘收掉 → body 长高 → 形态翻转 → `Composer` 的两个形态
 *    分支各挂一份控件，chip 连同它开着的弹层被**销毁** → 弹层卸载时又把焦点和键盘
 *    还回去（`closeReason` 还停在初始值）→ 形态翻回来 → 重建的 chip 重开弹层 ——
 *    开/毁循环，用户看不到面板。修法：控件走一个 `movableContentOf` 槽位（翻转是
 *    **移动**，弹层不被卸载），并且只有"真的关过一次"的卸载才归还焦点。v81 P0 把这
 *    条修法换掉：卡内部变成**单棵 Layout**（`ComposerArrangement`，两个子节点=编辑器
 *    + 工具行），翻转连"移动"都不是，槽位退役 —— 那次移动正是 720x1280 @420dpi
 *    打开会话必崩（`Cannot insert LayoutNode@… because it already has a parent`）的
 *    来源。
 * 2. **chip 热区劫持 composer**：宿主那条打开请求计数器是单向闩、永不回落，v74 让
 *    chip 每次挂载都重读它 —— 用户点过一次之后，任何重建都会"凭空"再弹一次面板
 *    （点 composer 文本区就会触发一次重建）。另外 28 dp 的 chip 在 Compose 1.7 里
 *    有平台强制的最小触摸目标（48 dp 矩形），比 44 dp 的工具行还高、上下各探出
 *    2 dp 进 composer 的文本区。修法：只有**新**请求才开；文本区自己直吃那一击
 *    （直接命中永远赢最小触摸目标命中）。
 * 3. **面板半透明**：面板的底原本画在入场动画**里面**，`fadeIn` 的 alpha 层让整块
 *    面板在那 150 ms 里是半透的（短窗上弹层被夹到内容区顶部、正压顶栏，顶栏文字
 *    就透出来）。修法：面板的矩形画在 `OcEnterOnMount` 的 `modifier` 上 ——
 *    `AnimatedEnterExitImpl` 的链是 `modifier.then(enterModifier)`
 *    （`animation-android-1.7.6-sources.jar` `AnimatedVisibility.kt:729-780`），
 *    alpha 层只包住它**后面**的内容，所以面板从第一帧起就不透明。
 */
class ComposerModelChipV75Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val composer =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    private val modelChip =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelChip.kt")

    private val themeRegistry =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/theme/OcThemeRegistry.kt")

    private fun flat(source: String): String = source.replace(Regex("\\s+"), " ")

    // ------------------------------------------------------------ 1. the flip (P1)

    @Test
    fun theToolControlsAreMountedOnceAndNeverMoved() {
        // One definition (the same field/controls both forms use) …
        assertTrue(composer.contains("val toolControls:"))
        // …mounted **once**, as the arrangement's second child, in both forms: a
        // form flip changes measurement, placement and values only, so the chip
        // keeps its `remember`ed `expanded` and the popup window it owns. v75
        // answered the same bug with a `movableContentOf` slot (the flip was a
        // move then); v81 retires it — the move is what crashed the 420 window
        // with `Cannot insert LayoutNode@… because it already has a parent`
        // (`research/v80_verify/5554/app_fatals.txt`).
        val code = composer
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("(?m)^\\s*//.*$"), " ")
        assertFalse("no movable machinery left in the composer", code.contains("movableContentOf"))
        assertFalse("the v75 slot is retired", code.contains("toolControlsSlot"))
        assertEquals(1, Regex("""controls = \{ toolControls\(\) \}""").findAll(composer).count())
        assertFalse(
            composer.contains("val latestToolControls by rememberUpdatedState(toolControls)"),
        )
        // The single mount is the arrangement's `controls` argument — the tool row
        // it lands in is the same node in both forms (its modifier is the form's).
        assertTrue(composer.contains("controls = { toolControls() },"))
        assertEquals(1, Regex("""Row\(\s*modifier = if \(minimal\)""").findAll(composer).count())
    }

    @Test
    fun aRebuildDoesNotHandTheFocusAndKeyboardBack() {
        // A rebuild (the composer's form flip, a session switch, a structural
        // change in the chip) unmounts the popup with no close reason at all.
        assertFalse("a rebuild is not a close", restoresFocusOnUnmount(null))
        // A real close still restores — except the outside tap, whose event is
        // replayed onto the control underneath instead.
        assertTrue(restoresFocusOnUnmount(OcPopoverCloseReason.KEY))
        assertTrue(restoresFocusOnUnmount(OcPopoverCloseReason.PICK))
        assertFalse(restoresFocusOnUnmount(OcPopoverCloseReason.OUTSIDE))
        // The chip keeps the reason nullable and asks the policy at teardown.
        assertTrue(
            modelChip.contains("var closeReason by remember { mutableStateOf<OcPopoverCloseReason?>(null) }"),
        )
        assertTrue(
            "the teardown reads the reason it actually ended on",
            flat(modelChip).contains("if (restoresFocusOnUnmount(closeReason)) {"),
        )
    }

    // -------------------------------------------------- 2. the request latch (P2)

    @Test
    fun onlyANewRequestOpensThePanel() {
        // The value a rebuilt instance mounts with is what the host's latch
        // already held — that is not a request.
        assertFalse(modelOpenRequestArrived(seen = 1, current = 1))
        assertFalse(modelOpenRequestArrived(seen = 0, current = 0))
        // A host that falls back to zero is not raising a request either.
        assertFalse(modelOpenRequestArrived(seen = 4, current = 0))
        // `/model` and the chip's own tap both land as a **new** value.
        assertTrue(modelOpenRequestArrived(seen = 0, current = 1))
        assertTrue(modelOpenRequestArrived(seen = 1, current = 2))
        // …and the chip's effect is the only consumer of the host counter.
        val chip = flat(modelChip)
        assertTrue(chip.contains("var seenRequest by remember { mutableStateOf(openRequest) }"))
        assertTrue(chip.contains("if (modelOpenRequestArrived(seenRequest, openRequest)) openPicker()"))
        assertTrue(chip.contains("seenRequest = openRequest"))
    }

    // ------------------------------------------------- 3. the hit geometry (P2)

    @Test
    fun theChipBandLeavesTheToolRowAndTheTextBandOwnsIt() {
        // The chip is 28 dp; the platform minimum touch target is 48 dp, so the
        // chip's real touch band is 48 dp tall — 10 dp past the chip on each side.
        assertEquals(48.dp, modelChipTouchHeightDp())
        // A control already past the minimum gets no expansion at all.
        assertEquals(52.dp, modelChipTouchHeightDp(chipHeight = 52.dp))
        // The tool row is 44 dp, so the band leaves it by 2 dp per side and that
        // sliver sits inside the composer's text band (hitTestChild descends
        // without a bounds check, so the row does not clip it).
        assertTrue(composer.contains("private val TOOL_ROW_HEIGHT = 44.dp"))
        assertEquals(2.dp, modelChipTouchOverflowDp(rowHeight = 44.dp))
        assertTrue(modelChipTouchOverflowDp(rowHeight = 44.dp) > 0.dp)
        // A row that already carries the minimum has no sliver to cover.
        assertEquals(0.dp, modelChipTouchOverflowDp(rowHeight = 48.dp))
        // The text band claims the tap: the focus tap node sits **outside** the
        // field's padding, so the whole editor slot is a direct hit — and a
        // direct hit always beats a minimum-touch-target hit. v80: the band is
        // one chain shared by both forms (the editor has one call site, so the
        // form flip cannot dispose the field the band just focused), and the two
        // forms' insets are the *values* of that one padding.
        val flatComposer = flat(composer)
        assertEquals(1, Regex("""editorBandTap\(focusRequester\)""").findAll(composer).count())
        assertTrue(
            "the band must sit outside the padding",
            flatComposer.contains(".editorBandTap(focusRequester) .padding("),
        )
        assertTrue(
            "the one-row inset",
            flatComposer.contains("start = if (minimal) 12.dp else 16.dp")
                && flatComposer.contains("end = if (minimal) 4.dp else 16.dp")
                && flatComposer.contains("top = if (minimal) 0.dp else 16.dp")
                && flatComposer.contains("bottom = if (minimal) 0.dp else 8.dp"),
        )
        assertTrue(
            composer.contains("detectTapGestures { runCatching { focusRequester.requestFocus() } }"),
        )
        // The chip's own box is untouched — still 28 dp, and deliberately **no**
        // `ocMinTouchTarget` ring: growing the chip's box to the row's 44 dp
        // would make the band the whole controls viewport, and a 48 dp ring
        // inside a 44 dp row cannot be honoured either (it clamps to the row).
        assertTrue(modelChip.contains(".height(CHIP_HEIGHT)"))
        assertFalse(modelChip.contains("ocMinTouchTarget"))
    }

    // ------------------------------------------------------ 4. the panel (P3)

    @Test
    fun thePanelSurfaceIsPaintedOutsideTheEnterFade() {
        val chip = flat(modelChip)
        // The surface (radius + opaque fill) rides the `OcEnterOnMount` modifier
        // chain, which `AnimatedEnterExitImpl` keeps *outside* the fade layer
        // (`modifier.then(childTransition.createModifier(enter, exit))`), so the
        // panel's rectangle is opaque from its first frame — the reported
        // 「面板背景透出顶栏文字」 cannot be observed even mid-fade.
        assertTrue(
            chip.contains(
                "modifier = Modifier " +
                    ".widthIn(min = MENU_MIN_WIDTH, max = MENU_MAX_WIDTH) " +
                    ".clip(OcTheme.shapes.md) .background(c.layer01)",
            ),
        )
        // …and it is the *enter-on-mount* node's modifier, i.e. the panel shell
        // rather than a child of the animated content.
        assertTrue(chip.contains("OcEnterOnMount( overlay = OcMotion.Overlay.DROPDOWN,"))
        // The outer surface already pins opacity; the inner forced-opaque chrome
        // (`layer01.copy(alpha = 1f)`) was added by a sibling and then removed
        // again as vacuous against the real cause — the two concurrent edits
        // resolved to "surface outside the fade only", which assertion 1 locks.
        // The content inside the fade keeps the normal fade-in look.
    }

    @Test
    fun thePanelTokenIsOpaqueInEveryBuiltInTheme() {
        // The other explanation for a see-through panel — a theme authoring
        // `--v2-background-bg-layer-01` with alpha — does not hold: every
        // 63-stop data row (35 themes × light/dark) carries a 6-digit
        // (fully opaque) third stop, and the default theme's own hand-transcribed
        // values (`OcColors.Dark/Light`) are opaque too. So the translucency can
        // only ever have come from the enter animation's alpha layer.
        val stops = Regex("\"#([0-9a-fA-F]{6,8})\"")
            .findAll(themeRegistry)
            .map { it.groupValues[1] }
            .toList()
        assertTrue("theme data must be whole 63-stop rows", stops.size >= 63)
        assertEquals(0, stops.size % 63)
        stops.chunked(63).forEachIndexed { index, row ->
            assertEquals(
                "theme row $index: layer-01 must be #RRGGBB (opaque), got ${row[2]}",
                6,
                row[2].length,
            )
        }
    }
}
