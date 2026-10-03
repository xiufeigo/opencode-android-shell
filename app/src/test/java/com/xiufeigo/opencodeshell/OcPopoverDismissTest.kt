package com.xiufeigo.opencodeshell

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.xiufeigo.opencodeshell.ui.components.OcPopoverCloseReason
import com.xiufeigo.opencodeshell.ui.components.OcPopoverPolicy
import com.xiufeigo.opencodeshell.ui.components.ocPopoverAnchorY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v49 真机批 · 弹层的 ESC + 外点吞点击
 * （`research/v49_verify/tD/v49_04→05`、`v49_06→07/08/09`、`v49_66/67`）。
 *
 * 现象一：**ESC 关不掉任何弹层**——模型下拉、composer「+」菜单、会话「⋯」菜单，
 * `KEYCODE_ESCAPE` 全无反应；点外面能关。
 *
 * 原因一：`Popup` 的窗口**不替内容申请焦点**（`PopupLayout` 只在 `DialogWrapper`
 * 里 attach 时 `requestFocus()`，Popup 走 `windowManager.addView()`），没有
 * focus owner 时 `FocusOwner.dispatchKeyEvent` 直接短路，`onPreviewKeyEvent`
 * 那一圈分发根本没跑起来 —— 上一轮挂在 ModelChip 内容上的 ESC handler 因此
 * 在真机上永远不会被调用。修法见 `Modifier.ocPopoverDismiss`：焦点归属 + ESC。
 *
 * 现象二：弹层外点吞点击——点外部目标控件，弹层关掉但那击没传到控件，必须再点
 * 一次。原因二：Popup 窗口是 touch-modal 的（没有 `FLAG_NOT_TOUCH_MODAL`），
 * `PopupLayout.onTouchEvent` 用「DOWN 落在卡片 bounds 外」判定后
 * `onDismissRequest(); return true`，那一下就到此为止。
 *
 * [OcPopoverPolicy] 是从 View / Compose 里剥出来的两条判定，这边钉死。
 */
class OcPopoverDismissTest {

    // ---- ESC ---------------------------------------------------------------

    @Test
    fun escapeKeyDownClosesAnOpenPopover() {
        assertTrue(OcPopoverPolicy.dismissesOnEscape(KeyEventType.KeyDown, Key.Escape, open = true))
    }

    @Test
    fun escapeKeyUpAndOtherKeysNeverCloseIt() {
        assertFalse(
            "KeyUp must not double-fire the dismissal",
            OcPopoverPolicy.dismissesOnEscape(KeyEventType.KeyUp, Key.Escape, open = true),
        )
        assertFalse(
            OcPopoverPolicy.dismissesOnEscape(KeyEventType.KeyDown, Key.Enter, open = true),
        )
        assertFalse(
            OcPopoverPolicy.dismissesOnEscape(KeyEventType.KeyDown, Key.Backspace, open = true),
        )
    }

    @Test
    fun aClosedPopoverOwnsNoEscape() {
        assertFalse(OcPopoverPolicy.dismissesOnEscape(KeyEventType.KeyDown, Key.Escape, open = false))
    }

    // ---- 平台 keyCode 兜底（v58 `research/v58_verify/5556`）-----------------
    //
    // 预览事件只在「根 → 焦点」那条链上分发，而链只经过焦点节点的祖先。会话页的
    // `OcSelect` 是内联展开（不是 `Popup`），composer 编辑器一直攥着焦点，那个
    // handler 一次也看不到 ESC —— Settings 里同一个组件却能关（那里焦点无处可
    // 去）。所以 `ocOverlayEscape` 在 View 上再挂一条 `OnKeyListener`。

    @Test
    fun thePlatformEscapeFallbackClosesOnTheKeyDownOnly() {
        assertTrue(
            OcPopoverPolicy.dismissesOnEscapeKeyCode(
                action = KeyEvent.ACTION_DOWN,
                keyCode = 111,
                open = true,
            ),
        )
        assertFalse(
            "KeyUp must not double-fire the dismissal",
            OcPopoverPolicy.dismissesOnEscapeKeyCode(KeyEvent.ACTION_UP, 111, open = true),
        )
        assertFalse(OcPopoverPolicy.dismissesOnEscapeKeyCode(99, 111, open = true))
    }

    @Test
    fun thePlatformFallbackOwnsNoOtherKey() {
        for (keyCode in listOf(4 /* BACK */, 66 /* ENTER */, 67 /* DEL */, 82 /* MENU */)) {
            assertFalse(
                "keyCode $keyCode",
                OcPopoverPolicy.dismissesOnEscapeKeyCode(KeyEvent.ACTION_DOWN, keyCode, open = true),
            )
        }
    }

    @Test
    fun aClosedOverlayOwnsNoPlatformEscapeEither() {
        assertFalse(OcPopoverPolicy.dismissesOnEscapeKeyCode(KeyEvent.ACTION_DOWN, 111, open = false))
    }

    @Test
    fun theTwoEscapeChannelsAgreeOnTheSameEvent() {
        // 两条通道同时命中也只关一次：监听挂在 `View.dispatchKeyEvent` 上，Compose
        // 消费过的那一击根本不会问到它。
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val composeSide = OcPopoverPolicy.dismissesOnEscape(
                if (action == KeyEvent.ACTION_DOWN) KeyEventType.KeyDown else KeyEventType.KeyUp,
                Key.Escape,
                open = true,
            )
            assertEquals(
                "action $action",
                composeSide,
                OcPopoverPolicy.dismissesOnEscapeKeyCode(action, 111, open = true),
            )
        }
    }

    @Test
    fun everyEscapeOnlyOverlayAlsoHostsThePlatformFallback() {
        val source = source("ui/components/OcPopoverDismiss.kt")
        // v61: the fallback moved to `OcEscapeRouter` — one listener per *view*
        // (a `View` has a single `OnKeyListener` slot, so six `DropdownMenu` rows
        // each installing their own kept tearing each other down), routed to the
        // topmost registration.
        assertTrue("the fallback rides the shared modifier", source.contains("OcEscapeRouter.attach"))
        assertTrue("and only while the overlay is open", source.contains("OcEscapeRouter.push"))
        assertTrue("the listener is reference counted", source.contains("object OcEscapeRouter {"))
    }

    // ---- 外点判定 ----------------------------------------------------------
    //
    // 判定必须和 Compose `PopupLayout.onTouchEvent` 的那一条一字不差
    // (`event.x < 0 || event.x >= width || event.y < 0 || event.y >= height`)，
    // 否则会出现「我们记了一击、Compose 却没关」这种错位。

    @Test
    fun onlyADownOutsideTheCardCountsAsTheDismissingTap() {
        assertTrue(
            OcPopoverPolicy.isOutsideCard(MotionEvent.ACTION_DOWN, -1f, 40f, 200, 300),
        )
        assertTrue(
            "a touch-modal popup reports coordinates past the right edge too",
            OcPopoverPolicy.isOutsideCard(MotionEvent.ACTION_DOWN, 240f, 40f, 200, 300),
        )
        assertTrue(
            OcPopoverPolicy.isOutsideCard(MotionEvent.ACTION_DOWN, 40f, 320f, 200, 300),
        )
        assertTrue(
            "the bounds are half-open, exactly like Compose's own check",
            OcPopoverPolicy.isOutsideCard(MotionEvent.ACTION_DOWN, 200f, 0f, 200, 300),
        )
        assertTrue(
            OcPopoverPolicy.isOutsideCard(MotionEvent.ACTION_DOWN, 0f, 300f, 200, 300),
        )
    }

    @Test
    fun aTapInsideTheCardAndTheRestOfTheGestureAreNotRecorded() {
        assertFalse(
            OcPopoverPolicy.isOutsideCard(MotionEvent.ACTION_DOWN, 40f, 40f, 200, 300),
        )
        assertFalse(
            OcPopoverPolicy.isOutsideCard(MotionEvent.ACTION_DOWN, 199.9f, 299.9f, 200, 300),
        )
        assertFalse(
            "the stream that follows the dismissing down must not queue again",
            OcPopoverPolicy.isOutsideCard(MotionEvent.ACTION_UP, -1f, 40f, 200, 300),
        )
        assertFalse(
            OcPopoverPolicy.isOutsideCard(MotionEvent.ACTION_MOVE, -1f, 40f, 200, 300),
        )
    }

    @Test
    fun insideAndOutsideAreExactComplementsOnTheSamePoint() {
        for (point in listOf(0f to 0f, 10f to 10f, 199f to 299f, 200f to 10f, -1f to 10f)) {
            val (x, y) = point
            assertTrue(
                "$x,$y",
                OcPopoverPolicy.isOutsideCard(MotionEvent.ACTION_DOWN, x, y, 200, 300) !=
                    OcPopoverPolicy.within(x, y, 200, 300),
            )
        }
    }

    // ---- 接线 --------------------------------------------------------------

    @Test
    fun everyPopoverMenuHostsTheDismissContract() {
        // 四个弹层（模型下拉 / 「+」菜单 / 会话「⋯」菜单 / agent 菜单）走同一套。
        // The marker stops before the closing paren: v51 gave the model chip a
        // third argument (`requestInitialFocus = false`) on the same call.
        val marker = "ocPopoverDismiss(popoverDismiss"
        val hosts = listOf(
            "ui/session/composer/ModelChip.kt",
            "ui/session/composer/AgentChip.kt",
            "ui/session/composer/Composer.kt",
            "ui/session/header/SessionHeader.kt",
        )
        for (path in hosts) {
            val source = source(path)
            assertTrue("$path wires the shared modifier", source.contains(marker))
            assertTrue("$path flushes the recorded tap", source.contains("flushPendingTap()"))
            // ESC 关闭那一击由共享修饰符处理，所以 ESC 不再由各调用点自己判断。
            assertFalse(
                "$path must not keep a per-site ESC handler",
                source.substringAfter(marker).contains("Key.Escape"),
            )
        }
    }

    @Test
    fun theSharedModifierOwnsTheFocusOwnershipPopupNeverGives() {
        val source = source("ui/components/OcPopoverDismiss.kt")
        // 焦点归属：没有它，Popup 窗口里没有 focus owner，onPreviewKeyEvent 不会被调用。
        assertTrue("focus target", source.contains(".focusable()"))
        assertTrue("focus requester", source.contains(".focusRequester(requester)"))
        assertTrue("request on mount", source.contains("requester.requestFocus()"))
        assertTrue("preview pass", source.contains(".onPreviewKeyEvent"))
        // 外点记录：监听挂在 Popup 自己身上（PopupLayout 没有子 View，
        // LocalView.current 就是它），且必须返回 false 让 Compose 照常关闭。
        assertTrue("popup touch listener", source.contains("popupView.setOnTouchListener"))
        val listener = source.substringAfter("popupView.setOnTouchListener {").substringBefore("onDispose")
        assertTrue(
            "the listener stays transparent so PopupLayout still dismisses",
            listener.replace(Regex("\\s+"), " ").trim().endsWith("false }"),
        )
    }

    @Test
    fun escapeOnlyPopoversGetTheirOwnFocusOwnership() {
        // v58 (`research/v57_verify/emu5556`): 新建会话的工作目录选择是 Material
        // 的 `DropdownMenu` —— Esc 按了没反应，点外面正常关。它的 `Popup` 默认不
        // focusable，窗口里没有焦点归属，ESC 进得去没人接；接上 `ocOverlayEscape`
        // （焦点归属 + ESC，不带外点补发）之后两条都通。
        val menu = source("ui/home/NewSessionScreen.kt")
        assertTrue("focusable popup", menu.contains("focusable = true"))
        assertTrue("the ESC contract rides the shared modifier", menu.contains("ocOverlayEscape"))
    }

    @Test
    fun theInlineSelectClosesOnEscapeWhileItsListIsOpen() {
        // The select's list is a plain layout child (no `Popup` window), so the
        // only focus owner that can see the key is one inside the select itself:
        // while `expanded` the select takes focus and closes on ESC.
        val select = source("ui/settings/SettingsComponents.kt")
        assertTrue(select.contains("if (expanded) Modifier.ocOverlayEscape(open = true) { expanded = false }"))
    }

    // ---- 焦点归还（v57 锚点漂移）-------------------------------------------

    @Test
    fun closingWithoutAnOutsideTapHandsFocusBack() {
        // ESC / 选中都要把焦点还给 composer 编辑器：弹层的搜索框 autofocus 把焦点
        // 和 IME 都抢走了，卸载后没人接手，布局就永久停在「无键盘」那一档。
        assertTrue(OcPopoverPolicy.restoresFocus(OcPopoverCloseReason.KEY))
        assertTrue(OcPopoverPolicy.restoresFocus(OcPopoverCloseReason.PICK))
    }

    @Test
    fun anOutsideTapDoesNotStealFocusBackFromTheTapTarget() {
        // 外点关闭时那一击正要被补发给底下的控件（`flushPendingTap`），焦点跟着
        // 点击目标走才对。
        assertFalse(OcPopoverPolicy.restoresFocus(OcPopoverCloseReason.OUTSIDE))
    }

    @Test
    fun theKeyboardComesBackOnlyWhenItWasUpWhenThePopoverOpened() {
        assertTrue(OcPopoverPolicy.imeVisibleAfterClose(true, OcPopoverCloseReason.KEY))
        assertTrue(OcPopoverPolicy.imeVisibleAfterClose(true, OcPopoverCloseReason.PICK))
        assertFalse(
            "a popover opened over a keyboard-less composer must not summon one",
            OcPopoverPolicy.imeVisibleAfterClose(false, OcPopoverCloseReason.KEY),
        )
        assertFalse(OcPopoverPolicy.imeVisibleAfterClose(true, OcPopoverCloseReason.OUTSIDE))
    }

    @Test
    fun theAnchorIsIdenticalOnReopenOnceFocusGoesBack() {
        // 复现 v57 `research/v57_verify/5554`：弹层开着时 composer 在 y≈649（有
        // 键盘），ESC 关掉后焦点没归还，composer 落到 y≈1044，锚点从 y≈88 漂到
        // y≈485 —— 此后再怎么关/开都在错基准上量。
        val composerTop = 1044f
        val imeHeight = 395f
        val gap = 4f
        val firstOpen = ocPopoverAnchorY(composerTop, gap, imeVisible = true, imeHeightDp = imeHeight)
        val afterClose = OcPopoverPolicy.imeVisibleAfterClose(true, OcPopoverCloseReason.KEY)
        val secondOpen = ocPopoverAnchorY(composerTop, gap, imeVisible = afterClose, imeHeightDp = imeHeight)
        assertEquals(firstOpen, secondOpen, 0.001f)
        assertTrue(
            "the keyboard is what moves the composer, not the chip",
            ocPopoverAnchorY(composerTop, gap, imeVisible = false, imeHeightDp = imeHeight) >
                firstOpen + imeHeight - 1f,
        )
    }

    @Test
    fun theModelChipRestoresFocusFromItsOwnCloseReasons() {
        val source = source("ui/session/composer/ModelChip.kt")
        assertTrue("close reason is recorded", source.contains("closePicker(OcPopoverCloseReason.KEY)"))
        assertTrue(
            "the popup hands focus back as it unmounts",
            source.contains("restoresFocusOnUnmount(closeReason)"),
        )
        // v75 P1: …and only for a close that really happened (`null` = the chip
        // was rebuilt out from under the picker, which must not hand the
        // keyboard back or the form flip and the rebuild feed each other).
        assertTrue(
            "the unmount rule is the close-reason rule",
            source.contains("OcPopoverPolicy.restoresFocus(reason)"),
        )
        assertTrue("the composer editor is the one handed back to", source.contains("onRestoreFocus"))
    }

    private fun source(path: String): String {
        val file = File("src/main/java/com/xiufeigo/opencodeshell/$path")
            .takeIf { it.isFile }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/$path")
        assertTrue("missing source: $path", file.isFile)
        return file.readText()
    }
}
