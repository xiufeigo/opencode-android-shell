package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v76 P0：composer 的双挂载崩溃（`movableContentOf` + 形态翻转）。
 *
 * 崩溃栈（两条真机路径同栈）：`java.lang.IllegalStateException: Cannot insert
 * LayoutNode@… because it already has a parent` →
 * `androidx.compose.ui.node.UiApplier.insertBottomUp` →
 * `Operation$CopyNodesToNewAnchorLocation.execute`（`runtime-android-1.7.6`
 * `changelist/Operation.kt:695`）→ `Recomposer`。也就是 **movable 内容移动**时把
 * 一个 `_foldedParent` 还没被清掉的节点插进了新父节点。
 *
 * - 路径 1：`emulator-5554`，`wm size 720x1280` + density 420/480，打开会话必崩。
 * - 路径 2：`emulator-5556`，「Toggle terminal」必崩 2/2（命令面板的关闭与终端面板
 *   的插入落在同一次 recomposition，pane 高度变化 → 形态翻转）。
 *
 * 根因（两层，v75 引入 movable 槽位时同时成立）：
 *
 * 1. **翻转换了父节点**。`composerSlot` 原本挂在两个互斥分支里——浮动态挂在
 *    viewport `Box` **里面**，共享态挂在它**后面**——所以翻转是一次真实的 movable
 *    移动（旧 anchor → 新 anchor，同帧）。`Composer.toolControlsSlot` 同样（两个
 *    形态各一个 `Row`；v81 P0 把这条也单树化 —— 卡内部变成 `ComposerArrangement`，
 *    两个子节点=编辑器+工具行，槽位退役）。
 * 2. **移动与失效同帧**。运行时侧的已知缺陷：movable 内容的 recompose scope 先被
 *    失效、后在同帧被移动时，失效不会被重定向到新位置，composer 会在"错的地方"重组
 *    （`issuetracker.google.com/issues/229001114`，1.7.6 之后才修；子组合变体见
 *    436858107）。本 app 的 composer 正好在 `BoxWithConstraints`（= `SubcomposeLayout`）
 *    的子组合里，且两个槽位都用 `rememberUpdatedState` 每帧写一次状态 → 每帧都有
 *    "pending invalidation"。两者相遇即随机报错（本例落在 `insertBottomUp`）。
 *
 * 修法（保住"翻转不重建"的初衷，且不丢两个槽位的语义）：
 *
 * - pane 的两种形态变成**同一棵树**：`SessionPaneArrangement` 是一个 `Layout`，恰好
 *   两个子节点（viewport `Box`、composer 宿主 `Box`）。翻转只改
 *   [SessionPaneLayout.viewportHeightPx] 给的约束与
 *   [SessionPaneLayout.composerTopPx] 给的落点，以及宿主自己的 modifier —— 父节点
 *   一个都不换，所以这条路径上根本没有 movable 移动。
 * - 两个槽位的内容改走 movable lambda 的**参数**（`movableContentOf<P> { p -> p() }`）
 *   而不是 `rememberUpdatedState` 镜像：参数是调用点的一部分（永远是本帧的 lambda），
 *   更新它是**槽位写**而不是快照写，因此不会留下需要重定向的失效。pane 的槽位保留 ——
 *   移动端切 tab（`key(mobileTab)` 会整块 dispose pane）仍靠它保住编辑器节点、草稿与
 *   已打开的弹层（`MovableContentMoveHarnessTest` 钉住这条回退路径本身是健全的）；
 *   composer 内部那个槽位在 v81 P0 被**退役**（父节点一个都不换，所以它没有要搬的东西）。
 *
 * 纯函数部分钉的是"翻转 = 两个数字"，结构部分钉的是"父节点永不改变"。
 */
class SessionPaneArrangementV76Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val sessionScreen =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private val composer =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    private val arrangement = sessionScreen
        .substringAfter("private fun SessionPaneArrangement(")
        .substringBefore("private fun OverlayBackHandler(")

    private fun flat(source: String): String = source.replace(Regex("\\s+"), " ")

    // ------------------------------------------------- 1. the arrangement (P0)

    @Test
    fun theFloatingFormGivesTheViewportTheWholePane() {
        // Upstream's desktop form: the composer is an opaque cover over the
        // transcript, which keeps scrolling under it — the viewport's height does
        // not depend on the composer at all.
        for (composer in listOf(0, 56, 96, 120, 400)) {
            assertEquals(800, SessionPaneLayout.viewportHeightPx(false, pane = 800, composer = composer))
        }
        // The cover form's host fills the pane and bottom-aligns its content, so
        // its own top edge is 0 (the `Layout` places `pane - host.height`).
        assertEquals(0, SessionPaneLayout.composerTopPx(pane = 800, composer = 800))
    }

    @Test
    fun theSharedFormPutsTheComposerOnThePanesLastRow() {
        // The v55 P0 form: the composer is the pane's last row, so the viewport is
        // exactly the pane minus the composer, and the host's top edge is the
        // viewport's bottom edge — the two add up to the pane, which is what makes
        // the shared form "entirely visible messages" instead of a cover.
        for (composer in listOf(0, 56, 96, 120, 400)) {
            val viewport = SessionPaneLayout.viewportHeightPx(true, pane = 800, composer = composer)
            val top = SessionPaneLayout.composerTopPx(pane = 800, composer = composer)
            assertEquals(800 - composer, viewport)
            assertEquals(viewport, top)
            assertEquals(800, viewport + composer)
        }
        assertEquals(680, SessionPaneLayout.viewportHeightPx(true, pane = 800, composer = 120))
        assertEquals(680, SessionPaneLayout.composerTopPx(pane = 800, composer = 120))
    }

    @Test
    fun neitherFormCanProduceANegativeViewportOrAHostAboveThePane() {
        // A draft taller than the pane (or a zero-height pane) must never hand the
        // viewport a negative constraint or place the host above the top edge —
        // both used to be `coerceAtLeast` calls scattered in the tree; they are the
        // arithmetic now.
        assertEquals(0, SessionPaneLayout.viewportHeightPx(true, pane = 100, composer = 400))
        assertEquals(0, SessionPaneLayout.composerTopPx(pane = 100, composer = 400))
        assertEquals(0, SessionPaneLayout.viewportHeightPx(true, pane = 0, composer = 120))
        assertEquals(0, SessionPaneLayout.composerTopPx(pane = 0, composer = 120))
    }

    // ------------------------------------------------------ 2. the structure

    @Test
    fun theFlipNeverReparentsTheComposer() {
        // One `Layout`, two children — the viewport and the composer's host. The
        // two arrangements used to be two exclusive branches with the composer
        // mounted *inside* a different `Box` in each, which is what made the flip a
        // movable-content move (and the crash). There is no branch here: the
        // verdict only reaches the host's own modifier and the two numbers above.
        assertTrue(sessionScreen.contains("private fun SessionPaneArrangement("))
        assertEquals(
            2,
            Regex("""measurables\[[01]\]""").findAll(arrangement).count(),
        )
        assertTrue(
            flat(arrangement).contains(
                "Box(Modifier.fillMaxSize().onSizeChanged(onViewportSize)) { viewport() }",
            ),
        )
        // …the host's *modifier* is the only thing the arrangement changes about it
        assertTrue(
            flat(arrangement).contains(
                ".let { if (shared) it.heightIn(max = composerCeiling) else it.fillMaxSize() }",
            ),
        )
        assertTrue(flat(arrangement).contains("composer()"))
        // …and the composer itself is mounted exactly once in the whole file, from
        // the single `composer = { … }` argument.
        assertEquals(
            1,
            Regex("""composerSlot\(composerRegion\)""").findAll(sessionScreen).count(),
        )
        assertTrue(sessionScreen.contains("composer = { composerSlot(composerRegion) },"))
        // No `if (sharedComposer)`-driven mount left in the pane: the pane's
        // branches are gone.
        assertFalse(
            flat(sessionScreen).contains("if (sharedComposer) { Box("),
        )
        assertFalse(
            flat(sessionScreen).contains("if (!sharedComposer) { Box("),
        )
    }

    @Test
    fun theViewportReportsItsOwnHeightThroughTheLayout() {
        // The v52 P0 padding repair reads the viewport's measured height, so the
        // `Box` the arrangement measures has to report it — the same callback the
        // inline `Box` used to carry.
        //
        // v77 P0: both reports are in **px** now (`it.height`), and the dp the
        // budget reads is derived from them with the live density — the
        // measurement is a fact about the node, the dp belongs to the
        // Configuration it is read under.
        assertTrue(
            sessionScreen.contains("onViewportSize = { viewportHeightPx = it.height },"),
        )
        assertTrue(arrangement.contains(".onSizeChanged(onViewportSize)"))
        assertTrue(sessionScreen.contains("val viewportHeight: Dp = with(density) { viewportHeightPx.toDp() }"))
        // …and the pane's own height still comes off the node the arrangement sits
        // in, so `composerSharesViewport` keeps reading the pane (never the
        // viewport it decides about).
        assertTrue(
            sessionScreen.contains(
                ".onSizeChanged { paneHeightPx = it.height },",
            ),
        )
        assertTrue(sessionScreen.contains("val paneHeight: Dp = with(density) { paneHeightPx.toDp() }"))
        assertTrue(
            sessionScreen.contains(
                "val sharedComposer = SessionPaneLayout.composerSharesViewport(\n" +
                    "                paneHeight,\n                composerHeight,\n            )",
            ),
        )
    }

    // -------------------------------------------- 3. the movable slots (P0/P1)

    @Test
    fun bothSlotsHandTheFrameOverAsAParameterNotAsSnapshotState() {
        // A parameter is part of the call site: it is this frame's lambda by
        // construction, and updating it is a slot write. A `rememberUpdatedState`
        // mirror would instead *write snapshot state* on every recomposition of the
        // host screen — the pending invalidation the runtime then fails to
        // re-target when the subtree moves in the same frame.
        assertTrue(
            sessionScreen.contains(
                "val composerSlot: @Composable (@Composable () -> Unit) -> Unit = remember {",
            ),
        )
        assertTrue(
            sessionScreen.contains(
                "movableContentOf<@Composable () -> Unit> { region -> region() }",
            ),
        )
        assertFalse(sessionScreen.contains("rememberUpdatedState(composerRegion)"))

        // v81 P0: the composer's own slot is **gone**, not fixed — the card is one
        // `Layout` whose two children (the editor, the tool row) are mounted under
        // the same parent in both forms, so the flip has nothing to move and the
        // slot has nothing to carry. What it used to move is what crashed the 420
        // window (`Cannot insert LayoutNode@… because it already has a parent`,
        // `research/v80_verify/5554/app_fatals.txt`), so this is the fix rather
        // than a second parameter-shaped slot.
        assertFalse(
            "no movable machinery left in the composer",
            composer
                .replace(Regex("(?s)/\\*.*?\\*/"), " ")
                .replace(Regex("(?m)^\\s*//.*$"), " ")
                .contains("movableContentOf"),
        )
        assertFalse(composer.contains("toolControlsSlot"))
        assertFalse(composer.contains("rememberUpdatedState(toolControls)"))
        // The controls are the arrangement's second child, mounted once.
        assertTrue(composer.contains("controls = { toolControls() },"))
    }

    @Test
    fun theComposerCeilingStillProtectsTheBandAndIsComputedFromThePane() {
        // The cap the shared form needs is computed once per frame from the pane
        // (`paneHeight`), never from the composer it caps — that is what keeps the
        // two arrangements from arguing with each other.
        assertTrue(
            sessionScreen.contains(
                "val composerCeiling = (paneHeight - SessionPaneLayout.TIMELINE_MIN_CONTENT)",
            ),
        )
        assertTrue(sessionScreen.contains("composerCeiling = composerCeiling,"))
        assertTrue(arrangement.contains("heightIn(max = composerCeiling)"))
        // …and it still yields to the band on every pane the shell hands it.
        for (pane in listOf(120.0, 146.7, 196.2, 250.0, 342.0)) {
            val ceiling = SessionPaneLayout.composerCeiling(pane.dp)
            assertTrue("pane $pane: ceiling $ceiling", ceiling <= pane.dp && ceiling > 0.dp)
        }
    }
}
