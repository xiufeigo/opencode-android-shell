package com.xiufeigo.opencodeshell.ui.overlay

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView

/**
 * v50 LOW-1 (`research/v50_verify/tD/v50_46→47`): ESC 关不掉原生确认框。
 *
 * [DialogHost] 接的 ESC（`DialogHost` 的 `onKeyEvent`）只对 **栈上的 layer**
 * 生效 —— 栈空时那个 handler 直接 `return false`。而 删除…/重命名 这几个确认框
 * 不是 layer：它们自己画 scrim（[DialogScrim]），直接挂在屏幕树里，所以宿主那条
 * 链路看不见它们，真机上 ESC 按了没反应（`v50_47` 里对话框还在）。
 *
 * 本文件把这个能力补到「自己画 scrim 的模态框」上，规则与宿主完全一致
 * （[DialogEscape]）：**IME 在时** ESC 只收键盘（`input keyevent 111` 是测试台的
 * 收键盘动作，把它当关闭会误伤正在输入的重命名框），**IME 不在时** ESC = 关闭。
 *
 * 焦点：ESC 的分发走 `FocusOwner.dispatchKeyEvent`，**窗口里没有焦点归属时它直接
 * 返回 false**，`onPreviewKeyEvent` 压根不会被调用（与 `ocPopoverDismiss` 文件头
 * 记的是同一个 Compose 行为）。所以根节点要自己成为焦点目标并在挂载时
 * `requestFocus()`；已经 autofocus 的输入框（重命名）不用抢 —— 预览事件自根向
 * 焦点分发，挂在焦点节点的**祖先**上同样收得到，[autoFocus] 就是给那种框留的
 * 出口。
 */
object ModalEscapePolicy {

    /**
     * 一个 key 事件是否关闭模态框：IME 在 → 不关（收键盘由调用方做）；IME 不在
     * → 只认 KeyDown 的 `Key.Escape`（KeyUp 不重复触发）。
     */
    fun dismisses(type: KeyEventType, key: Key, open: Boolean, imeVisible: Boolean): Boolean =
        open && !imeVisible && type == KeyEventType.KeyDown && key == Key.Escape
}

/**
 * P1（`research/v52_verify/tE/v52_11/12`、`v53_verify/tE/v53_15/16/19/20`）:
 * 弹层/弹窗打开就把焦点给了自己的输入框，软键盘随之升起，在**短窗**上吃掉
 * 几乎全部高度 —— 「Move to…」的目录选择卡只剩顶部一条残边（列表零尺寸、
 * Cancel/Select 不可见），模型选择弹层只剩 Variant 两行（模型列表全被盖住），
 * 必须 ESC 收一次键盘才恢复。大窗 / 收过键盘之后一切正常，所以这是**空间**
 * 问题，不是焦点或布局的问题。
 *
 * 规则很简单：**空间够就保持原样（自动聚焦搜索框，键盘随之而起）**，空间不够
 * 就不自动拉键盘 —— 焦点归属仍然由内容自己建起来（ESC 不回归），只是不再由
 * 打开这一下把 IME 拉起来；用户点搜索框/输入框时键盘照常升起。
 *
 * 阈值取 [AUTO_FOCUS_MIN_HEIGHT_DP]：低于它是真机量过的短窗（1440×810@420dpi
 * ≈ 342 dp），高于它是所有正常的手机/平板竖屏（≥ 640 dp），两边的行为都没有
 * 争议。
 */
object ModalImePolicy {

    /** 低于这个窗口高度（dp）就不自动拉键盘。 */
    const val AUTO_FOCUS_MIN_HEIGHT_DP = 480

    /**
     * 打开弹层时是否让输入框自动聚焦（并因此自动升起键盘）：调用方**要**自动
     * 聚焦（[requested]）且窗口够高。
     *
     * 与之配套的一条接线约定：被这里否掉时，调用方必须把「根节点自己申请焦点」
     * 打开（`ocPopoverDismiss(requestInitialFocus = true)` /
     * `ocModalEscape(autoFocus = true)`）—— `Popup` 窗口不替内容申请焦点，
     * 没有根焦点就没人能接住 ESC（文件头 §1）。
     */
    fun autoFocusOnOpen(requested: Boolean, windowHeightDp: Int): Boolean =
        requested && windowHeightDp >= AUTO_FOCUS_MIN_HEIGHT_DP
}

/**
 * 挂在模态框**根节点**上的 ESC 关闭，与 [DialogHost] 同一套规则。
 *
 * @param open 弹层是否可关（危险确认框在写请求在途时会锁住按钮，ESC 同样不关）。
 * @param autoFocus 根节点是否在挂载时抢焦点。没有 autofocus 输入框的确认框要给
 *   `true`（否则窗口里可能根本没有焦点归属，ESC 无处可来）；已经自己
 *   `requestFocus` 的框（重命名）给 `false`，免得把输入框的光标/全选抢掉。
 */
@Composable
fun Modifier.ocModalEscape(
    open: Boolean = true,
    autoFocus: Boolean = true,
    onDismiss: () -> Unit,
): Modifier {
    val requester = remember { FocusRequester() }
    val view = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    // 挂载可能还没 attach（首帧），抢不到焦点就再试几帧。
    LaunchedEffect(requester, autoFocus) {
        if (!autoFocus) return@LaunchedEffect
        repeat(MODAL_ESCAPE_FOCUS_ATTEMPTS) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }
    return this
        .focusRequester(requester)
        .focusable()
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || event.key != Key.Escape || !open) {
                return@onPreviewKeyEvent false
            }
            if (isImeVisible(view)) {
                // IME 在：只收键盘，与 DialogHost 同一分工（见 DialogEscape）。
                keyboard?.hide()
                return@onPreviewKeyEvent true
            }
            if (ModalEscapePolicy.dismisses(event.type, event.key, open = true, imeVisible = false)) {
                onDismiss()
                true
            } else {
                false
            }
        }
}

/** 焦点请求失败时的重试帧数（与 `ocPopoverDismiss` 同一量级）。 */
private const val MODAL_ESCAPE_FOCUS_ATTEMPTS = 4
