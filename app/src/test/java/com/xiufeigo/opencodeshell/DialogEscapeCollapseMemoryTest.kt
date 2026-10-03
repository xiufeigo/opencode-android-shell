package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.overlay.DialogEscape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v73 verify (`research/v73_verify/5554/75-78`, emulator-5554) · 命令面板 + 键盘
 * 要按 4-5 次 ESC 才关，ESC #2 反而把键盘拉了回来。
 *
 * 规定的 ESC 规则（v61 契约）：对话框开着时，第一次 ESC 只收键盘并记住这一层
 * 已经收过（`collapsedByEscape`）；之后每一次 ESC 都关闭，不管键盘是不是又
 * 回来了；没有用户交互就不该有任何东西把键盘再拉起来。
 *
 * 当前构建在真机上已复核（ESC1 收键盘无关闭日志，ESC2 `DialogHost: layer close`
 * 落盘、面板消失、焦点回到 composer），本套件把决策侧钉死：预览层放行已武装
 * 的 ESC（不吞关闭）、宿主关闭读 per-layer 记忆、面板自己的 ESC 无条件关。
 * 按键分发本身单测够不到，接线用本仓既有的 source guard
 * （`DialogLayerPolicyTest` 同款）。
 */
class DialogEscapeCollapseMemoryTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val dialogHost = source("src/main/java/com/xiufeigo/opencodeshell/ui/overlay/DialogHost.kt")
    private val palette = source("src/main/java/com/xiufeigo/opencodeshell/ui/palette/CommandPalette.kt")

    // ---- 决策表：第一次 ESC 只收键盘 -----------------------------------------

    @Test
    fun `first Escape with the keyboard up only collapses it`() {
        assertEquals(
            DialogEscape.EscapeAction.COLLAPSE_IME,
            DialogEscape.escapeAction(isEscapeKeyDown = true, imeVisible = true, collapsedByEscape = false),
        )
        assertFalse(
            "the first Escape never dismisses while typing",
            DialogEscape.escapeDismissesLayer(imeVisible = true, collapsedByEscape = false),
        )
    }

    // ---- 决策表：之后每一次 ESC 都关闭，键盘回来也不例外 ---------------------

    @Test
    fun `later Escapes dismiss even with the keyboard back up`() {
        // v73 的那一下：ESC #2 把键盘拉了回来 —— 这一层记着已经收过一次，
        // 所以这一次是关闭而不是再来一轮收键盘。
        assertTrue(
            "the second Escape dismisses with the keyboard back",
            DialogEscape.escapeDismissesLayer(imeVisible = true, collapsedByEscape = true),
        )
        assertEquals(
            DialogEscape.EscapeAction.DISMISS,
            DialogEscape.escapeAction(isEscapeKeyDown = true, imeVisible = true, collapsedByEscape = true),
        )
        assertEquals(
            DialogEscape.EscapeAction.DISMISS,
            DialogEscape.escapeAction(isEscapeKeyDown = true, imeVisible = false, collapsedByEscape = true),
        )
    }

    @Test
    fun `desktop shape still closes in one Escape`() {
        assertTrue(
            DialogEscape.escapeDismissesLayer(imeVisible = false, collapsedByEscape = false),
        )
        assertEquals(
            DialogEscape.EscapeAction.DISMISS,
            DialogEscape.escapeAction(isEscapeKeyDown = true, imeVisible = false, collapsedByEscape = false),
        )
    }

    @Test
    fun `non Escape keydowns never dismiss`() {
        for (imeVisible in listOf(false, true)) {
            for (collapsed in listOf(false, true)) {
                assertEquals(
                    "key=$imeVisible collapsed=$collapsed",
                    DialogEscape.EscapeAction.IGNORE,
                    DialogEscape.escapeAction(
                        isEscapeKeyDown = false,
                        imeVisible = imeVisible,
                        collapsedByEscape = collapsed,
                    ),
                )
            }
        }
    }

    @Test
    fun `legacy single flag overload keeps the v50 shape`() {
        assertFalse(DialogEscape.escapeDismissesLayer(imeVisible = true))
        assertTrue(DialogEscape.escapeDismissesLayer(imeVisible = false))
    }

    // ---- 接线：武装后的 ESC 必须一路走到关闭 ---------------------------------

    @Test
    fun `the preview pass lets the closing Escape through to the host`() {
        // 预览那一遍在键盘回来时再吃掉 ESC，关闭就永远到不了宿主 —— v73 连按
        // 关不掉正是这个形状。记忆按层持有，宿主关闭时读顶层那一份。
        assertTrue(
            "preview consults the per-layer memory",
            dialogHost.contains("collapsedByEscape = imeCollapsed()"),
        )
        assertTrue(
            "collapse is recorded per layer",
            dialogHost.contains("mutableStateMapOf<Int, Boolean>()"),
        )
        assertTrue(
            "host dismissal consults the top layer memory",
            dialogHost.contains("collapsedByEscape = collapsedIme[top.id] == true"),
        )
    }

    @Test
    fun `the collapse arms the memory fail closed`() {
        // 先记后藏：`hide()` 万一没把键盘藏掉，记忆已经武装，下一次 ESC 照关。
        val arm = dialogHost.indexOf("onImeCollapsed()")
        val hide = dialogHost.indexOf("keyboard?.hide()")
        assertTrue("collapse records the memory", arm >= 0)
        assertTrue("collapse hides the keyboard", hide >= 0)
        assertTrue("memory arms before the hide runs", arm < hide)
    }

    @Test
    fun `the palette Escape closes unconditionally`() {
        // 面板自己的 ESC 就是关闭：不读键盘状态、不读记忆 —— 记忆只决定宿主那
        // 条冒泡通道吃不吃，面板这条预览通道永远关。
        val branch = palette.substringAfter("Key.Escape -> {").substringBefore("else -> false")
        assertTrue("palette ESC closes", branch.contains("onClose()"))
        assertFalse(
            "palette ESC is not gated on IME or collapse state",
            branch.contains("imeVisible") || branch.contains("collapsedByEscape"),
        )
    }
}
