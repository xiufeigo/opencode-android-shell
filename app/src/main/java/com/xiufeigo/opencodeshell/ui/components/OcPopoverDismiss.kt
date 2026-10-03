package com.xiufeigo.opencodeshell.ui.components

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.focusable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView

/**
 * v49 真机批 · 弹层的两件事：**ESC 关闭** + **外点穿透**。
 *
 * ## ESC 关不掉（`research/v49_verify/tD/v49_04→05`、`v49_66/67`）
 *
 * 上一轮在 ModelChip 的 Popup 内容上挂了 `onPreviewKeyEvent`（ESC），
 * 真机完全无效。原因在 Compose 自己的代码里，不是 shell 的事件处理：
 *
 * - `PopupLayout`（`AndroidPopup.android.kt`）只在 `DialogWrapper` 里才会在
 *   attach 时 `requestFocus()`；`Popup` 走的是 `windowManager.addView()` 这条路，
 *   **不替内容申请焦点**。
 * - `FocusOwner.dispatchKeyEvent` 在没有 focus owner 时直接返回 `false`，于是
 *   `onPreviewKeyEvent`（挂在焦点宿主到根的链上）压根不会被调用。
 *
 * 也就是说：ESC 进了 Popup 窗口，但窗口里没有焦点归属，`Preview` 那一圈分发
 * 从头到尾没跑。修法在 [Modifier.ocPopoverDismiss]：把 Popup 的根节点变成一个
 * 真正的焦点目标（`focusRequester` + `focusable`）并在挂载时 `requestFocus()`，
 * 焦点链条建起来之后 `onPreviewKeyEvent` 才会被走到。焦点在内容里的搜索框上时
 * 同样有效 —— `onPreviewKeyEvent` 是**自根向焦点**分发的，根节点永远先看到 ESC。
 *
 * ## 外点吞点击（`v49_07→08/09`）
 *
 * `Popup` 的窗口没有 `FLAG_NOT_TOUCH_MODAL`，是 touch-modal 的：全屏的点击
 * 都先送进 Popup 窗口，`PopupLayout.onTouchEvent` 用「DOWN 落在卡片 bounds 外」
 * 判定后调用 `onDismissRequest` 并 `return true` —— 关闭了，但那一下**没有**
 * 再送给底下的控件，于是「点外部目标控件 → 弹层关掉、目标要再点一次」。
 *
 * 修法：Popup 自己没有把坐标交出来的 API，但 [Modifier.ocPopoverDismiss] 里
 * `LocalView.current` **就是** `PopupLayout` 本身（`subCompositionView = this`，
 * 没有子 View），所以可以给它挂一个 `OnTouchListener`：照 Compose 同一套
 * 「落在卡片外」的判定记下这一次按下，返回 `false` 让 Compose 照常关闭；等
 * 弹层真的从 WindowManager 上摘掉之后，[OcPopoverDismiss.flushPendingTap]
 * 再把这记下来的一次点击原样补发给宿主窗口。
 *
 * ## 抢搜索框的焦点（v51 `tE/v51_47→48`）
 *
 * 根节点自己 `requestFocus()` 是 ESC 的必要条件，却会和弹层里**自己的**输入框
 * 抢焦点：模型选择弹层打开后点「Search models」，键盘不出、`input text` 也
 * 没有任何反应，列表不过滤 —— 焦点停在根节点上，弹层窗口没有 IME 连接。
 *
 * `onPreviewKeyEvent` 需要的是「这条链上有焦点归属」，**不需要**焦点恰好停在
 * 根节点：焦点在子输入框上时，预览事件同样自根向下分发，ESC 照样先被根看到。
 * 所以带输入框的弹层传 [Modifier.ocPopoverDismiss] 的 `requestInitialFocus =
 * false`，让内容自己 autofocus（`ModelPickerContent` 的搜索框），
 * ESC 与外点穿透两条行为都不变。
 *
 * ## 短窗上别自动拉键盘（P1，`research/v53_verify/tE/v53_19/20`）
 *
 * 上面那条「让搜索框 autofocus」在**短窗**上会把弹层压死：焦点一到位 IME 就升起，
 * 342 dp 高的窗口里弹层只剩 Variant 两行，模型列表全被盖住。弹层是否自动聚焦
 * 现在由 `ModalImePolicy.autoFocusOnOpen` 决定（`ui/overlay/ModalEscape.kt`）：
 * 窗口够高照旧自动聚焦，短窗不自动拉键盘 —— 此时 `requestInitialFocus` 取回
 * `true`，焦点归属交给根节点，ESC 与外点穿透一条不变，用户点搜索框键盘照常升起。
 *
 * ## 关掉之后焦点还给谁（v57 `research/v57_verify/5554`）
 *
 * 弹层挂着的时候搜索框 autofocus 把焦点/IME 从 composer 编辑器抢走，卸载时却没
 * 人接手：布局永久停在「无键盘」那一档，composer 从 y≈649 落到 y≈1044，锚点从
 * y≈88 漂到 y≈485 —— 而且此后每一次关/开都在错基准上量，谁也纠不回来。
 *
 * 修法在调用点（`ModelChip`）：关掉时记下关闭原因，弹层内容卸载的那一刻把焦点
 * 交还给触发者（composer 编辑器），键盘与布局一起复位。只有外点关闭不还——
 * 那一击正要被补发给底下的控件。
 *
 * ## 只要 ESC 的弹层（[Modifier.ocOverlayEscape]）
 *
 * 新建会话的工作目录选择（`NewSessionScreen` 的 `WorkspaceSelector`）走的是
 * Material 的 `DropdownMenu`：它的 `Popup` 默认**不** focusable，窗口里没有焦点
 * 归属，ESC 进得去、没人接得住（点外面正常关 —— 外面那一击由 `PopupLayout` 自己
 * 的判定处理，本来就不需要焦点）。`ocOverlayEscape` 把 §1 的焦点归属与 ESC 判定
 * 单独摘出来给这类弹层用：它们没有「外点那一击要补发」的诉求。
 *
 * 但 `DropdownMenu` 的 `modifier` **不在 `Popup` 里**（v61 `research/v61_verify/5558`
 * 真机 logcat 钉死）：M3 把它挂在宿主树上一个零尺寸的 `Box` 上，弹层内容是另一次
 * 组合。所以兜底监听必须落在**宿主窗口**的 View 上、且整个窗口只能有一个 ——
 * 这就是 [OcEscapeRouter] 存在的理由，细节见 [ocOverlayEscape] 的函数文档。
 */

/**
 * 弹层是被哪条路径关掉的。焦点归还（见 [OcPopoverPolicy.restoresFocus]）只看这个。
 */
enum class OcPopoverCloseReason {
    /** ESC / 返回键：`Modifier.ocPopoverDismiss` 或 `Popup` 自己的 dismiss。 */
    KEY,

    /** 落在卡片外的点击：那一下正要被补发给底下的控件。 */
    OUTSIDE,

    /** 选中某一项 / 进入下一层（管理模型）。 */
    PICK,
}

/**
 * 纯判定，供 JVM 测试（`OcPopoverDismissTest`）：把「这次按下落在这张卡片外
 * 吗」「这个 key 事件该关弹层吗」「关掉之后要不要把焦点还回去」三条规则从
 * View/Compose 里剥出来。
 */
object OcPopoverPolicy {

    /**
     * 与 `PopupLayout.onTouchEvent` 完全同一条判定（`event.x < 0 ||
     * event.x >= width || event.y < 0 || event.y >= height`）。两边必须一致，
     * 否则会出现「我们记了一击、Compose 却没关」或反过来的错位。
     */
    fun isOutsideCard(action: Int, x: Float, y: Float, width: Int, height: Int): Boolean =
        action == MotionEvent.ACTION_DOWN && !within(x, y, width, height)

    /** [isOutsideCard] 的另一半，导出以便直接断言。 */
    fun within(x: Float, y: Float, width: Int, height: Int): Boolean =
        x >= 0f && x < width && y >= 0f && y < height

    /**
     * ESC 关闭最上层的弹层。只认 KeyDown 的 `Key.Escape`（KeyUp 不重复触发，
     * 长按的 repeat 也由 Compose 合成同一个事件）；[open] 之外的层拿不到事件。
     */
    fun dismissesOnEscape(type: KeyEventType, key: Key, open: Boolean): Boolean =
        open && type == KeyEventType.KeyDown && key == Key.Escape

    /**
     * 同一判定在**平台 keyCode** 上的一遍，供 View 层的兜底监听用
     * （`Modifier.ocOverlayEscape` 文件头末节）：KEYCODE_ESCAPE = 111。
     * 与 [dismissesOnEscape] 必须同进同出，两条通道同时命中也只关一次
     * （监听只在 Compose 没消费时才被问到）。
     */
    fun dismissesOnEscapeKeyCode(action: Int, keyCode: Int, open: Boolean): Boolean =
        open && action == KeyEvent.ACTION_DOWN && keyCode == KEYCODE_ESCAPE

    /**
     * [OcEscapeRouter] 的 `OnKeyListener` 那一步：除了是 ESC 的 KeyDown，还要
     * **有人注册**。没人注册时返回 false，事件照常往下走（弹层没开着的 ESC 不该
     * 被吞掉）。
     */
    fun routesEscape(action: Int, keyCode: Int, handlerCount: Int): Boolean =
        handlerCount > 0 && dismissesOnEscapeKeyCode(action, keyCode, open = true)

    /**
     * 弹层挂载时把搜索框/焦点从宿主编辑器抢走，卸载后要还回去 —— 否则 composer
     * 停在「无键盘」那一档的布局上，下一次打开的锚点基准就永久偏了一个键盘高度。
     *
     * 唯一不还的是 [OcPopoverCloseReason.OUTSIDE]：那一击正要被补发给底下的控件
     * （[OcPopoverDismiss.flushPendingTap]），焦点跟着点击目标走才是对的。
     */
    fun restoresFocus(reason: OcPopoverCloseReason): Boolean =
        reason != OcPopoverCloseReason.OUTSIDE

    /**
     * 关掉弹层之后键盘还在不在：只有焦点真的回到了编辑器、编辑器又是 IME 连接
     * 持有者时才回来，于是下一次打开量到的 composer 位置和这一次一样。
     */
    fun imeVisibleAfterClose(imeVisibleWhenOpen: Boolean, reason: OcPopoverCloseReason): Boolean =
        imeVisibleWhenOpen && restoresFocus(reason)
}

/**
 * 弹层锚点的 y（dp）：chip 所在行的位置减去键盘占掉的高度（键盘可见时 composer
 * 被顶上去），再加 [gapDp]。纯算术，供 JVM 测试钉住「关掉再打开不漂」——
 * 漂移全部来自 [OcPopoverPolicy.imeVisibleAfterClose] 改变了这一个布尔量。
 */
internal fun ocPopoverAnchorY(
    composerTopDp: Float,
    gapDp: Float,
    imeVisible: Boolean,
    imeHeightDp: Float,
): Float = composerTopDp - (if (imeVisible) imeHeightDp else 0f) + gapDp

/**
 * 一个弹层的「关掉之后」状态。持有宿主窗口（**Popup 之外**拿到的那个 View），
 * 记录外点那一击，并在弹层真正消失后补发。
 *
 * 在弹层宿主里 `remember` 一次即可；[flushPendingTap] 在 `open` 翻成 `false`
 * 之后调用。
 */
class OcPopoverDismiss internal constructor(private val host: View) {

    /** 待补发的一次点击，屏幕绝对坐标；null 表示这次关闭不是外点造成的。 */
    private var pending: Pair<Float, Float>? = null

    /**
     * 每次弹层挂载时清掉上一轮没发出去的记录 —— 否则一次「记了但没关」的按
     * 下会一直悬着，等到**下一次**关闭时才补发出去，打在一个用户早就不看的
     * 坐标上。
     */
    internal fun clearPendingTap() {
        pending = null
    }

    /** [Modifier.ocPopoverDismiss] 在 Popup 内部调用（此时 view 就是 PopupLayout）。 */
    internal fun recordOutsideTouch(popup: View, event: MotionEvent) {
        val location = IntArray(2)
        popup.getLocationOnScreen(location)
        pending = (location[0] + event.x) to (location[1] + event.y)
    }

    /**
     * 把记下的那一击补发给宿主窗口。**必须等弹层已经从 WindowManager 摘掉**：
     * Popup 窗口还在的话它是 touch-modal 的，这一击会被它再吃一次——那正好是
     * 要修的 bug。补发延迟两帧（[FORWARD_DELAY_MS]），足够走完一次重组 +
     * `removeViewImmediate`。
     */
    fun flushPendingTap() {
        val tap = pending ?: return
        pending = null
        host.postDelayed({
            val origin = IntArray(2)
            host.getLocationOnScreen(origin)
            val x = tap.first - origin[0]
            val y = tap.second - origin[1]
            val downTime = SystemClock.uptimeMillis()
            dispatch(MotionEvent.ACTION_DOWN, downTime, downTime, x, y)
            dispatch(MotionEvent.ACTION_UP, downTime, downTime + FORWARD_TAP_UP_MS, x, y)
        }, FORWARD_DELAY_MS)
    }

    private fun dispatch(action: Int, downTime: Long, eventTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        try {
            host.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}

/** 两帧：够弹层完成卸载，又短到用户察觉不到（延迟一个补发点击）。 */
private const val FORWARD_DELAY_MS = 48L

/** DOWN/UP 之间的时间戳差，和真机一次点按同量级。 */
private const val FORWARD_TAP_UP_MS = 60L

/** `KEYCODE_ESCAPE`（`input keyevent 111`）。 */
private const val KEYCODE_ESCAPE = 111

/**
 * 挂在 Popup 根节点上：**ESC 关闭** + **外点这一击记下来**。
 *
 * 必须挂在 Popup 内容的最外层节点（`OcEnterOnMount` 的 `modifier` 参数就是这个
 * 位置）：焦点归属和触摸监听都只认这一个节点。
 *
 * @param requestInitialFocus 挂载时是否由**根节点**申请焦点（文件头 §2 需要）。
 *   弹层自带输入框时传 `false`：焦点链照样由输入框建起来，ESC 与外点穿透都
 *   不受影响，而输入框不再被根节点抢走（v51 `tE`）。声明在 [onDismiss] 之前，
 *   这样既有的 `ocPopoverDismiss(controller) { … }` 尾随 lambda 写法不变。
 * @param onDismiss ESC 命中时的关闭动作；外点关闭仍走 `Popup` 自己的
 *   `onDismissRequest`（这里只旁路记录，不代替它）。
 */
@Composable
fun Modifier.ocPopoverDismiss(
    controller: OcPopoverDismiss?,
    open: Boolean = true,
    requestInitialFocus: Boolean = true,
    onDismiss: () -> Unit,
): Modifier {
    val popupView = LocalView.current
    // 外点那一击：Popup 窗口 touch-modal，落在卡片外的 DOWN 会先到这里。返回
    // false 让 `PopupLayout.onTouchEvent` 照常按自己的判定关闭弹层。
    DisposableEffect(popupView, controller) {
        controller?.clearPendingTap()
        popupView.setOnTouchListener { view, event ->
            if (event != null && controller != null &&
                OcPopoverPolicy.isOutsideCard(event.action, event.x, event.y, view.width, view.height)
            ) {
                controller.recordOutsideTouch(view, event)
            }
            false
        }
        onDispose { popupView.setOnTouchListener(null) }
    }
    return ocOverlayEscape(open = open, requestInitialFocus = requestInitialFocus, onDismiss = onDismiss)
}

/**
 * ESC 关闭一个弹层，**不**带外点补发：给那些「关掉就完事」的弹层用
 * （Material 的 `DropdownMenu`、行内展开的列表）。
 *
 * 背景同上：ESC 进了弹层的窗口，但窗口里没有焦点归属时 `FocusOwner` 直接返回
 * false，`onPreviewKeyEvent` 压根不会被调用；所以这里把当前节点变成焦点目标并
 * 在挂载时 `requestFocus()`，焦点链建起来之后 ESC 才有人接。焦点落在弹层**内容
 * 自己的输入框**上时同样有效 —— 预览事件是自根向焦点分发的。
 *
 * 外点关闭不经过这里：这类弹层各有各的 `onDismissRequest`（`DropdownMenu` 自己
 * 的 scrim 判定、`OcSelect` 的整层布局），需要把外点那一击补发给底下控件的场景
 * 走 [ocPopoverDismiss]。
 *
 * ## 焦点链之外的那一次 ESC（v58 `research/v58_verify/5556`）
 *
 * 预览事件是「**根 → 焦点**」这条链上的，而链只经过焦点节点的**祖先**。于是
 * 只要焦点没落在弹层子树里，那个 handler 就一次也看不到 ESC —— 会话页的
 * `OcSelect`（内联展开，不是 `Popup`）正是这样：composer 编辑器一直攥着焦点，
 * Settings 里同一个组件却能关（那里焦点无处可去，`requestFocus` 必成）。
 * 键盘弹层再叠一层：焦点在搜索框上时 IME 接管窗口的按键，Compose 有时不把
 * `Key.Escape` 交回来。
 *
 * 所以这里在节点之外再挂一条**平台通道**：[OcEscapeRouter] 在
 * `LocalView.current` 上挂的 `OnKeyListener`。它挂在 `View.dispatchKeyEvent` 上，
 * 只在 Compose 没消费这一击时才会被问到，因此与预览事件**不重复**触发，两条通道
 * 合起来才是「弹层开着时 ESC 必可关」。
 *
 * ## 这条通道必须是**一个**监听，不是每个节点一个（v61 `research/v61_verify/5558`）
 *
 * v59 给 Home 两个行菜单的修法是给 `DropdownMenu` 补 `PopupProperties(focusable)`
 * + `Modifier.ocOverlayEscape`，真机（5558/5560 手机）**一次都没生效**，
 * 桌面（5556）却是好的。真机 logcat 把事件路径钉死了：
 *
 * ```
 * # 菜单打开着，input keyevent 111
 * OcEscapeDiag: dialogHost key=Escape type=KeyDown stack=0 ime=true
 *               view=AndroidComposeView winFocus=true
 * # 一个 keyListener / preview 日志都没有
 * ```
 *
 * 两处根因：
 *
 * 1. **`DropdownMenu` 的 `modifier` 不在 `Popup` 里。** M3 把它挂在宿主树上一个
 *    零尺寸的 `Box` 上（弹层内容是另一次组合）。于是 `LocalView.current` 拿到的
 *    是**宿主的 `AndroidComposeView`**，不是 `PopupLayout` —— 真机日志里
 *    `ocOverlayEscape` 挂载时打的 `view=AndroidComposeView` 就是这个（而且打了 6 次，
 *    六个会话行各挂一次）。弹层自己的窗口从来没拿到焦点，ESC 自然只在宿主窗口里。
 * 2. **同一个 `View` 上只能挂一个 `OnKeyListener`。** 六个行菜单的
 *    `DisposableEffect` 全往宿主 `AndroidComposeView` 的同一个槽位上装，谁
 *    `onDispose` 谁就 `setOnKeyListener(null)`，活着的兄弟的监听被顺手摘掉 ——
 *    ESC 到达时那个槽位多半是空的，于是既没人关菜单，事件又照常往下掉。
 *
 * 于是这条通道改成 [OcEscapeRouter]：按引用计数在 View 上装**一个**监听，
 * 各个弹层只往路由里注册自己的关闭动作（后注册的在上），并且只有 [open] 为真
 * （菜单真的开着）时才注册。
 *
 * [open] 还有第二个用处：`DropdownMenu` 的那个宿主 `Box` 在菜单**关着**时也在
 * 组合里，不门控的话 `focusRequester.requestFocus()` 会把焦点从 composer 抢走。
 */
@Composable
fun Modifier.ocOverlayEscape(
    open: Boolean = true,
    requestInitialFocus: Boolean = true,
    onDismiss: () -> Unit,
): Modifier {
    val popupView = LocalView.current
    val requester = remember(popupView) { FocusRequester() }
    val dismiss by rememberUpdatedState(onDismiss)
    // 焦点链之外的兜底（见文件头末节）。
    DisposableEffect(popupView, open) {
        if (!open) {
            onDispose { }
        } else {
            val unregister = OcEscapeRouter.push { dismiss() }
            val detach = OcEscapeRouter.attach(popupView)
            onDispose {
                unregister()
                detach()
            }
        }
    }
    // Popup 不会替内容申请焦点（见文件头），这里自己补上。拿不到焦点时
    // `onPreviewKeyEvent` 永远不会被走到，所以重试几帧而不是只试一次。
    LaunchedEffect(requester, requestInitialFocus, open) {
        if (!open || !requestInitialFocus) return@LaunchedEffect
        repeat(FOCUS_ATTEMPTS) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }
    if (!open) return this
    return this
        .focusRequester(requester)
        .focusable()
        .onPreviewKeyEvent { event ->
            if (OcPopoverPolicy.dismissesOnEscape(event.type, event.key, open = true)) {
                onDismiss()
                true
            } else {
                false
            }
        }
}

/**
 * 窗口级 ESC 路由（v61 `research/v61_verify/5558`）。
 *
 * 一个 `View` 上只挂一个 `OnKeyListener`，所以「每个弹层自己往 `LocalView.current`
 * 上装一个」在同一个宿主 View 上必然互相摘掉（见 [ocOverlayEscape] 文件头 §v61）。
 * 路由把这件事拆成两半：**监听**按引用计数装一个，**关闭动作**进一条栈，
 * ESC 来了交给最后注册的那一个（最上面那层弹层）。
 */
object OcEscapeRouter {

    private val handlers = mutableListOf<() -> Unit>()

    /** 引用计数：`count == 0 → 1` 时装监听，`1 → 0` 时摘掉。 */
    private val attached = java.util.WeakHashMap<View, Int>()

    /** 注册一个关闭动作，返回注销函数。返回的引用身份即身份（[handlers] 按它移除）。 */
    fun push(handler: () -> Unit): () -> Unit {
        handlers.add(handler)
        return { handlers.remove(handler) }
    }

    /** 当前注册了几个（供 [attach] 的纯判定镜像与测试观察用）。 */
    val handlerCount: Int get() = handlers.size

    /** ESC 交给最上面那层；没有人注册时返回 false，事件照常往下走。 */
    fun dispatchEscape(): Boolean {
        val handler = handlers.lastOrNull() ?: return false
        handler()
        return true
    }

    /** 在 [view] 上装这一个监听（已在装就只加计数），返回引用计数减一的函数。 */
    fun attach(view: View): () -> Unit {
        val count = attached[view] ?: 0
        attached[view] = count + 1
        if (count == 0) {
            view.setOnKeyListener { _, keyCode, event ->
                event != null &&
                    OcPopoverPolicy.routesEscape(
                        event.action,
                        keyCode,
                        handlerCount = handlers.size,
                    ) &&
                    dispatchEscape()
            }
        }
        return { detach(view) }
    }

    private fun detach(view: View) {
        val count = attached[view] ?: return
        if (count <= 1) {
            attached.remove(view)
            view.setOnKeyListener(null)
        } else {
            attached[view] = count - 1
        }
    }
}

/** 焦点请求失败时的重试帧数（首帧可能还没 attach）。 */
private const val FOCUS_ATTEMPTS = 4
