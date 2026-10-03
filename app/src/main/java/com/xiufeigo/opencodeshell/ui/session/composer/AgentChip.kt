package com.xiufeigo.opencodeshell.ui.session.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.OcEnterOnMount
import com.xiufeigo.opencodeshell.ui.components.OcPopoverCloseReason
import com.xiufeigo.opencodeshell.ui.components.OcPopoverDismiss
import com.xiufeigo.opencodeshell.ui.components.ocPopoverDismiss
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * W5-C B4: the composer's **agent chip**.
 *
 * Upstream renders one `ComposerEditorConfiguredSelect` per control
 * (`composer/editor/editor.tsx:284-292` agent, `:293-307` model + variant) — the
 * agent control is the `agent.cycle` command from `composer/commands.tsx:65-73`
 * with its options coming from `local.agent`. Here the options are the
 * `GET /agent` `mode == "primary"` entries ([OpenCodeClient.Agent.selectable]),
 * because only a primary agent can own a session.
 *
 * Chrome matches [ModelChip] (same `menu-v2-content` shell, same dropdown motion):
 * 28 dp tall, `max-w-[220px]`, icon + name + `▾`, muted while following the
 * server default.
 *
 * B-9: the chip also honours upstream's `local.agent.visible()`
 * (`providers/models/selection.tsx:94`) — `settings.general.showCustomAgents`
 * **or** a server with a custom agent. Off ⇒ the chip is not composed at all,
 * so the model chip is the only control left, exactly like upstream's
 * `view.agent === undefined`.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AgentChip(
    current: String?,
    agents: List<OpenCodeClient.Agent>,
    overrideActive: Boolean,
    onSelect: (String) -> Unit,
    onUseDefault: () -> Unit,
    modifier: Modifier = Modifier,
    /** `/agent` from the command menu opens this menu (see [ModelChip.openRequest]). */
    openRequest: Int = 0,
    /**
     * B-9: upstream `local.agent.visible()` — while it is `false` the control is
     * not rendered at all (`composer/model.ts:367-376` returns `undefined` for
     * `view.agent`, and `editor.tsx:284` renders it under `<Show when>`).
     */
    visible: Boolean = true,
    /**
     * v97（v57 修复批移交项）：弹层卸载时把焦点还给触发者（composer 编辑器）。
     * 弹层根节点的初始聚焦（[Modifier.ocPopoverDismiss] 的 `requestInitialFocus`，
     * ESC 的必要条件）把焦点与 IME 都从编辑器手里抢走，卸载后没人接手的话布局
     * 永久停在「无键盘」那一档，下一次打开的锚点就永久偏移一个键盘高度
     * （`OcPopoverDismiss` 文件头「关掉之后焦点还给谁」一节，`ModelChip` 同款）。
     * 外点关闭不调用它 —— 那一击正要被补发给底下的控件。
     */
    onRestoreFocus: (() -> Unit)? = null,
) {
    val c = OcTheme.colors
    val options = remember(agents) { OpenCodeClient.Agent.selectable(agents) }
    // v97 P2（照 `ModelChip` 的 v75 P2 修法）：本实例**已经消费过**的 `/agent` 请求
    // 计数。宿主那条 `agentOpenRequest`（`Composer.kt:467`）是单向闩、永不回落，
    // v74 却让 chip 在**每次挂载**时重读它 —— 用户敲过一次 `/agent` 之后，此后
    // 任何重建（点 composer 文本区让键盘与布局跟着变、进出 shell 模式
    // `Composer.kt:1726`、会话切换）都会「凭空」再弹一次 agent 菜单。重建出来的
    // 实例把挂载时那个值当作已见过，只有真正**新**到的值才开
    // （`agentOpenRequestArrived`）。
    // 刻意放在下一行早退的**之上**：chip 不可见 / agent 列表还没到时这个 composable
    // 整段 return（`agentPickerVisible` 的初值就是 false，`ChatViewModel.kt:581`），
    // 闩仍然活着 —— 那之前敲的 `/agent` 在 chip 可见之后照样开，正路径不破。
    var seenRequest by remember { mutableStateOf(openRequest) }
    if (!visible || options.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    // v97（v57 移交项）：关闭原因。弹层内容卸载的那一刻才知道该不该把焦点还
    // 回去 —— `null` = 这次卸载**不是**一次关闭（组件被重建而卸载：形态翻转、
    // 会话切换、控件结构变化）。照 v75 已定语义，那种卸载**不**归还：归还会把
    // 键盘重新拉起来，键盘一起布局又翻回去、组件再被重建，来回震荡，弹层永远
    // 来不及显示（`ModelChip` 的 P1 注释在案）。
    var closeReason by remember { mutableStateOf<OcPopoverCloseReason?>(null) }
    val closeMenu: (OcPopoverCloseReason) -> Unit = { reason ->
        closeReason = reason
        expanded = false
    }
    // 打开之前键盘在不在：归还焦点后只有原本可见时才把键盘叫回来（原本没有就
    // 不多事），这一条决定了下一次打开时量到的 composer 位置和这一次相同。
    val imeVisibleNow = WindowInsets.isImeVisible
    val keyboardController = LocalSoftwareKeyboardController.current
    var imeVisibleAtOpen by remember { mutableStateOf(false) }
    val openMenu: () -> Unit = {
        imeVisibleAtOpen = imeVisibleNow
        // 清掉上一轮的关闭原因：归还判定读的是**卸载那一刻**的原因，留着上一
        // 轮的值会让这一次的「重建卸载」被误判成一次关闭（`closeMenu` 与
        // `expanded = false` 同帧生效，弹层内容不会再多组合一次）。
        closeReason = null
        expanded = true
    }
    var anchor by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(LocalDensity.current) { MENU_GAP.roundToPx() }
    val label = current?.takeIf { it.isNotBlank() } ?: stringResource(R.string.oc_composer_agent)
    // Hoisted: `semantics {}` is not a composable scope. The label rides the
    // clickable Row, not the `Icon` inside it — only the node carrying
    // ACTION_CLICK is announced as a button.
    val chooseLabel = stringResource(R.string.oc_composer_choose_agent)

    // `/agent` from the command menu: open the menu on the next composition.
    //
    // v97 P2: 只有**新**请求才开（[agentOpenRequestArrived]）。与
    // [modelOpenRequestArrived]（v75 P2）同款：重建出的实例把挂载时那个值当作
    // 已见过，所以「重建」不再等于「再弹一次」。
    LaunchedEffect(openRequest) {
        if (agentOpenRequestArrived(seenRequest, openRequest)) openMenu()
        seenRequest = openRequest
    }
    // v49: ESC 关闭 + 外点穿透，宿主窗口在 Popup 之外取（见 `OcPopoverDismiss`）。
    val hostView = LocalView.current
    val popoverDismiss = remember { OcPopoverDismiss(hostView) }
    LaunchedEffect(expanded) { if (!expanded) popoverDismiss.flushPendingTap() }

    Box(modifier = modifier.onSizeChanged { anchor = it }) {
        Row(
            modifier = Modifier
                .height(CHIP_HEIGHT)
                .widthIn(max = CHIP_MAX_WIDTH)
                .clip(OcTheme.shapes.md)
                .clickable(role = Role.Button) { openMenu() }
                .semantics { contentDescription = chooseLabel }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_task),
                contentDescription = null,
                tint = c.iconMuted,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                color = if (overrideActive) c.textBase else c.textMuted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                painter = painterResource(R.drawable.ic_oc_chevron_down),
                contentDescription = null,
                tint = c.textFaint,
                modifier = Modifier.size(14.dp),
            )
        }

        if (expanded) {
            val provider = remember(anchor, gapPx) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset {
                        val below = anchorBounds.bottom + gapPx
                        val fitsBelow = below + popupContentSize.height <= windowSize.height
                        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                        return IntOffset(
                            x = anchorBounds.left.coerceIn(0, maxX),
                            y = if (fitsBelow) below else anchorBounds.top - gapPx - popupContentSize.height,
                        )
                    }
                }
            }
            Popup(
                popupPositionProvider = provider,
                onDismissRequest = { closeMenu(OcPopoverCloseReason.OUTSIDE) },
                // v49: explicit so a later edit cannot silently drop them back to
                // relying on the defaults.
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                ),
            ) {
                OcEnterOnMount(
                    overlay = OcMotion.Overlay.DROPDOWN,
                    // ESC 关闭 + 外点穿透（含 Popup 自己不建立的焦点归属）。
                    modifier = Modifier.ocPopoverDismiss(popoverDismiss) { closeMenu(OcPopoverCloseReason.KEY) },
                ) {
                    // 卸载那一刻才做焦点归还（`ModelChip` 的 v57/v75 语义）：弹层
                    // 还挂着的时候编辑器抢不回焦点，而这一击（ESC / 选中）之后布
                    // 局必须回到打开前的键盘状态。读的是**卸载那一刻**的原因与
                    // 回调，而不是组合时捕获的那一份；`null` = 这一次卸载不是关
                    // 闭（组件被重建而卸载），不归还 —— 否则键盘被重新拉起、布局
                    // 再翻一次，弹层和重建来回震荡（v75 P1，别反转这条语义）。
                    val latestRestore by rememberUpdatedState(onRestoreFocus)
                    DisposableEffect(Unit) {
                        onDispose {
                            if (restoresFocusOnUnmount(closeReason)) {
                                latestRestore?.invoke()
                                if (imeVisibleAtOpen) keyboardController?.show()
                            }
                        }
                    }
                    Column(
                        modifier = Modifier
                            .widthIn(min = MENU_MIN_WIDTH, max = MENU_MAX_WIDTH)
                            .clip(OcTheme.shapes.md)
                            .background(c.layer01)
                            .border(0.5.dp, c.borderBase, OcTheme.shapes.md),
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = LIST_MAX_HEIGHT)) {
                            items(items = options, key = { agent -> agent.name }) { agent ->
                                AgentRow(
                                    label = agent.name,
                                    description = agent.description,
                                    selected = overrideActive && agent.name == current,
                                    onClick = {
                                        closeMenu(OcPopoverCloseReason.PICK)
                                        onSelect(agent.name)
                                    },
                                )
                            }
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(c.borderMuted),
                        )
                        AgentRow(
                            label = stringResource(R.string.oc_composer_agent_default),
                            description = null,
                            selected = !overrideActive,
                            onClick = {
                                closeMenu(OcPopoverCloseReason.PICK)
                                onUseDefault()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * v97 P2: whether [current] is a request this chip instance has not seen yet.
 * The host's counter (`agentOpenRequest`, `Composer.kt:467`) is a one-way latch
 * that never falls back, and v74 let the chip re-read it on **every** mount — so
 * once the user had typed `/agent`, any later rebuild of the composer re-opened
 * the menu out of nowhere (a tap on the composer's text area moves the keyboard
 * and the layout, which is exactly a rebuild). A rebuilt instance now treats the
 * value it mounts with as already seen, and only a genuinely **new** value opens
 * the menu — the same shape as [modelOpenRequestArrived] (v75 P2).
 *
 * @param seen the value this instance has already seen (its mount value).
 * @param current the value the host publishes now.
 */
internal fun agentOpenRequestArrived(seen: Int, current: Int): Boolean =
    current > 0 && current != seen

/** `[data-slot=menu-v2-item]` — name + muted description, check on the active row. */
@Composable
private fun AgentRow(
    label: String,
    description: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .height(MENU_ROW_HEIGHT)
            .clip(OcTheme.shapes.sm)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (selected) c.textBase else c.textMuted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            description?.takeIf { it.isNotBlank() }?.let { text ->
                Text(
                    text = text,
                    color = c.textFaint,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_check),
                contentDescription = null,
                tint = c.accent,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Matches [ModelChip]: 28 dp tall, `max-w-[220px]`. */
private val CHIP_HEIGHT = 28.dp
private val CHIP_MAX_WIDTH = 220.dp

/** `menu-v2-content`: `min-width: 160px; max-width: 23rem`. */
private val MENU_MIN_WIDTH = 160.dp
private val MENU_MAX_WIDTH = 368.dp

/** Scroll cap so a 200-agent server cannot push the menu off screen. */
private val LIST_MAX_HEIGHT = 260.dp

/** `[data-slot=menu-v2-item]` row height — a comfortable touch target. */
private val MENU_ROW_HEIGHT = 40.dp

/** Gap between the chip and its menu. */
private val MENU_GAP = 4.dp
