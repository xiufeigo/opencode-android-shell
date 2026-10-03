package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.overlay.ModalImePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P1（`research/v52_verify/tE/v52_11/12`、`v53_verify/tE/v53_15/16/19/20`）:
 * 弹窗/弹层一打开就把焦点交给自己的输入框，软键盘随之升起，短窗（1440×810@420dpi
 * ≈ 342 dp）上「Move to…」的目录选择卡只剩顶部残条（body 零尺寸、
 * Cancel/Select 不可见），模型选择弹层只剩 Variant 两行 —— 必须 ESC 收一次键盘
 * 才恢复。
 *
 * 修法是「空间够才自动聚焦拉键盘」，纯判定在 [ModalImePolicy]。这里既钉住判定
 * 本身，也钉住三处接线（接线错了判定再对也没用）：
 *
 * - `ModelChip`：短窗上 `requestInitialFocus = !searchAutofocus` —— `Popup` 窗口不
 *   替内容申请焦点，否掉搜索框的自动聚焦时必须把根焦点接回去，否则 ESC 回归。
 * - `ModelPickerContent`：搜索框的自动聚焦走同一个判定。
 * - `DirectoryPickerDialog`：短窗上焦点落在卡片根节点（`focusRequester(cardFocus)`
 *   + `focusable()`），不是路径输入框。
 */
class ModalImeAutoFocusPolicyTest {

    private val shortWindow = 342      // 真机短窗：1440×810 @420dpi
    private val tallWindow = 914       // 真机手机竖屏

    @Test
    fun shortWindowDoesNotPullTheKeyboard() {
        assertFalse(
            "短窗打开不自动拉键盘",
            ModalImePolicy.autoFocusOnOpen(requested = true, windowHeightDp = shortWindow),
        )
    }

    @Test
    fun tallWindowKeepsAutofocus() {
        assertTrue(
            "空间够时保持自动聚焦（v51 行为不回归）",
            ModalImePolicy.autoFocusOnOpen(requested = true, windowHeightDp = tallWindow),
        )
    }

    @Test
    fun neverRequestedStaysNeverRequested() {
        // 没有输入框的弹层（各种确认框）任何窗口高度下都不该抢焦点。
        assertFalse(
            ModalImePolicy.autoFocusOnOpen(requested = false, windowHeightDp = tallWindow),
        )
        assertFalse(
            ModalImePolicy.autoFocusOnOpen(requested = false, windowHeightDp = shortWindow),
        )
    }

    @Test
    fun theThresholdIsTheOneTheDeviceRunUsed() {
        assertTrue(
            "阈值之下不自动聚焦",
            !ModalImePolicy.autoFocusOnOpen(true, ModalImePolicy.AUTO_FOCUS_MIN_HEIGHT_DP - 1),
        )
        assertTrue(
            "阈值之上自动聚焦",
            ModalImePolicy.autoFocusOnOpen(true, ModalImePolicy.AUTO_FOCUS_MIN_HEIGHT_DP),
        )
        assertTrue("阈值不是零高度", ModalImePolicy.AUTO_FOCUS_MIN_HEIGHT_DP > 0)
    }

    @Test
    fun modelChipHandsFocusBackToTheRootWhenTheKeyboardIsSuppressed() {
        val chip = source("ui/session/composer/ModelChip.kt")
        // 搜索框自动聚焦与否由同一条判定决定，两处必须是同一个值，否则会出现
        // 「没人 autofocus、也没人拿根焦点」—— ESC 无处可来。
        assertTrue("policy read", chip.contains("ModalImePolicy.autoFocusOnOpen("))
        assertTrue(
            "root focus is the complement",
            chip.contains("requestInitialFocus = !searchAutofocus"),
        )
        assertTrue("content autofocus is gated", chip.contains("autoFocusSearch = searchAutofocus"))
    }

    @Test
    fun modelPickerContentGatesItsSearchFocus() {
        val picker = source("ui/session/composer/ModelPicker.kt")
        assertTrue("policy read", picker.contains("ModalImePolicy.autoFocusOnOpen("))
        assertTrue(
            "the effect follows the gated value, not the raw parameter",
            picker.contains("LaunchedEffect(focusSearch)") &&
                !picker.contains("LaunchedEffect(autoFocusSearch)"),
        )
    }

    @Test
    fun directoryPickerMovesInitialFocusOffThePathFieldOnShortWindows() {
        val picker = source("ui/home/DirectoryPickerDialog.kt")
        assertTrue("policy read", picker.contains("ModalImePolicy.autoFocusOnOpen("))
        // 焦点落在卡片根节点上（不是输入框）—— 一个不是文本框的焦点目标不会让 IME 升起。
        assertTrue("root focus requester", picker.contains("focusRequester(cardFocus)"))
        assertTrue("root is focusable", picker.contains(".focusable(),"))
        assertTrue("path field has its own requester", picker.contains("focusRequester(pathFocus)"))
        // imePadding / footer 预算那套没被摘掉：用户点输入框时键盘照常升起，footer 仍在。
        assertTrue("ime padding kept", picker.contains(".imePadding()"))
    }

    private fun source(path: String): String = listOf(
        File("src/main/java/com/xiufeigo/opencodeshell/$path"),
        File("app/src/main/java/com/xiufeigo/opencodeshell/$path"),
    ).firstOrNull { it.isFile }?.readText() ?: error("missing $path")
}
