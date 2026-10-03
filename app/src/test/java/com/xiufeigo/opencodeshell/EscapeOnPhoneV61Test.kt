package com.xiufeigo.opencodeshell

import android.view.KeyEvent
import com.xiufeigo.opencodeshell.ui.components.OcPopoverPolicy
import com.xiufeigo.opencodeshell.ui.overlay.DialogEscape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v61 真机批（`research/v61_verify/5558`）· **手机形态下 ESC 关不掉弹层**。
 *
 * 真机 logcat 钉死的事件路径：菜单开着时 `input keyevent 111` 落在**宿主窗口**的
 * `AndroidComposeView`（`winFocus=true`），弹层的 `Popup` 窗口从头到尾没有焦点，
 * 弹层内部既收不到 `OnKeyListener` 也走不到 `onPreviewKeyEvent`。两个现象两个根因：
 *
 * 1. **行菜单（会话行 / 项目行）**：`DropdownMenu` 的 `modifier` 挂在**宿主树**上
 *    一个零尺寸 `Box` 上（不在 `Popup` 里），所以 v59 补的 `ocOverlayEscape` 拿到
 *    的 `LocalView` 是宿主 View；更要命的是一个 View 只能挂一个 `OnKeyListener`，
 *    六个会话行各装各的、谁 `onDispose` 谁 `null` 掉 —— ESC 到达时那个槽位多半是
 *    空的。修法：`OcEscapeRouter`（一个 View 一个监听 + 一栈关闭动作 +
 *    `open = expanded` 门控）。
 * 2. **命令面板**：`DialogHost` 的 ESC 规则是「IME 在 → 只收键盘」。手机上搜索框
 *    autofocus、键盘随之而来，下一个 `input keyevent 111` 又把它拉回来，于是 ESC
 *    永远只在「收键盘」里打转（真机连按 3 次不关，桌面一次就关）。修法：记住
 *    「这一层已被 ESC 收过一次键盘」，下一次 ESC 一律关闭。
 */
class EscapeOnPhoneV61Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val popover = source("src/main/java/com/xiufeigo/opencodeshell/ui/components/OcPopoverDismiss.kt")
    private val sessionsList = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")
    private val projectsList = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectsList.kt")
    private val newSession = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/NewSessionScreen.kt")
    private val dialogHost = source("src/main/java/com/xiufeigo/opencodeshell/ui/overlay/DialogHost.kt")

    // ---- 路由：ESC 该不该被这条通道吃掉 -------------------------------------

    @Test
    fun `the platform escape route only claims the key while an overlay is registered`() {
        assertTrue(
            "ESC KeyDown with a handler",
            OcPopoverPolicy.routesEscape(KeyEvent.ACTION_DOWN, KEYCODE_ESCAPE, handlerCount = 1),
        )
        assertFalse(
            "no handler means the event keeps going down the chain",
            OcPopoverPolicy.routesEscape(KeyEvent.ACTION_DOWN, KEYCODE_ESCAPE, handlerCount = 0),
        )
        assertFalse(
            "KeyUp never double-fires",
            OcPopoverPolicy.routesEscape(KeyEvent.ACTION_UP, KEYCODE_ESCAPE, handlerCount = 1),
        )
        assertFalse(
            "another key is none of our business",
            OcPopoverPolicy.routesEscape(KeyEvent.ACTION_DOWN, KEYCODE_DPAD_DOWN, handlerCount = 1),
        )
    }

    // ---- 路由：一个 View 一个监听（v59 那版的真正破口） --------------------

    @Test
    fun `the escape channel installs one listener per view and routes by registration order`() {
        assertTrue("router", popover.contains("object OcEscapeRouter {"))
        // 旧写法：每个节点自己 `setOnKeyListener` / `onDispose { setOnKeyListener(null) }`
        // —— 同一个宿主 View 上六个行菜单互相摘掉，ESC 到达时是空的。
        val body = popover.substringAfter("fun Modifier.ocOverlayEscape(")
        assertFalse("no per-node listener", body.contains("popupView.setOnKeyListener"))
        assertFalse("no per-node teardown", body.contains("popupView.setOnKeyListener(null)"))
        assertTrue("routes through the router", body.contains("OcEscapeRouter.push"))
        assertTrue("one listener per view", body.contains("OcEscapeRouter.attach"))
    }

    @Test
    fun `the router only ever claims the topmost registration and tears down with the overlay`() {
        assertTrue("last registration wins", popover.contains("val handler = handlers.lastOrNull() ?: return false"))
        assertTrue("no handler falls through", popover.contains("fun dispatchEscape(): Boolean"))
        assertTrue(
            "install at 0 to 1, remove at 1 to 0",
            popover.contains("if (count == 0) {"),
        )
        assertTrue("teardown drops the listener", popover.contains("view.setOnKeyListener(null)"))
    }

    // ---- 接线：三个 DropdownMenu 都走宿主窗口那条通道 ----------------------

    @Test
    fun `every DropdownMenu escape hookup goes through the host-window route, gated on open`() {
        for ((name, text) in listOf(
            "session row" to sessionsList,
            "project row" to projectsList,
            "workspace selector" to newSession,
        )) {
            assertTrue(
                "$name registers on the router",
                text.contains("ocOverlayEscape(open = "),
            )
            // 关着的时候那个宿主 Box 也在组合里：不门控就会把焦点从 composer 抢走。
            assertTrue(
                "$name gates on the expanded flag",
                text.contains("ocOverlayEscape(open = menuOpen)") ||
                    text.contains("ocOverlayEscape(open = expanded)"),
            )
        }
    }

    // ---- 命令面板：ESC 与键盘的那次死循环 ----------------------------------

    @Test
    fun `a collapsed keyboard does not trap escape in a loop on a phone`() {
        val collapse = DialogEscape.escapeAction(isEscapeKeyDown = true, imeVisible = true, collapsedByEscape = false)
        assertEquals(DialogEscape.EscapeAction.COLLAPSE_IME, collapse)
        // 键盘又被下一次 keyevent 拉回来 —— 这一层记着「已经收过一次」，
        // 于是这一次是关闭而不是再来一轮收键盘。
        assertTrue(
            "the second Escape dismisses even with the keyboard back",
            DialogEscape.escapeDismissesLayer(imeVisible = true, collapsedByEscape = true),
        )
    }

    @Test
    fun `the desktop shape still closes in one Escape`() {
        // 桌面形态键盘从来没起来过（`collapsedByEscape` 恒为 false），规则原样：
        // 一次 ESC 就是关闭，中间没有「收键盘」那一步。
        assertTrue(
            "one Escape closes with the keyboard down",
            DialogEscape.escapeDismissesLayer(imeVisible = false, collapsedByEscape = false),
        )
        assertEquals(
            DialogEscape.EscapeAction.DISMISS,
            DialogEscape.escapeAction(isEscapeKeyDown = true, imeVisible = false, collapsedByEscape = false),
        )
        assertEquals(
            DialogEscape.EscapeAction.IGNORE,
            DialogEscape.escapeAction(isEscapeKeyDown = false, imeVisible = true, collapsedByEscape = false),
        )
    }

    @Test
    fun `the layer owns its collapsed-keyboard memory and stops swallowing the closing Escape`() {
        assertTrue("per-layer state", dialogHost.contains("mutableStateMapOf<Int, Boolean>()"))
        assertTrue(
            "host dismissal consults it",
            dialogHost.contains("collapsedByEscape = collapsedIme[top.id] == true"),
        )
        // 预览那一遍必须放行第二次 ESC，否则事件到不了宿主那条关闭的 handler。
        assertTrue(
            "preview stops consuming after the collapse",
            dialogHost.contains("collapsedByEscape = imeCollapsed()"),
        )
        assertTrue("the collapse is recorded", dialogHost.contains("onImeCollapsed()"))
    }

    private companion object {
        const val KEYCODE_ESCAPE = 111
        const val KEYCODE_DPAD_DOWN = 20
    }
}
