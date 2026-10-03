package com.xiufeigo.opencodeshell.ui.session.composer

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.xiufeigo.opencodeshell.ui.components.ocOverlayContentTopPx
import com.xiufeigo.opencodeshell.ui.components.ocOverlayDecorInsetPx
import com.xiufeigo.opencodeshell.ui.components.ocOverlayY
import com.xiufeigo.opencodeshell.ui.components.OcPopoverCloseReason
import com.xiufeigo.opencodeshell.ui.components.OcPopoverDismiss
import com.xiufeigo.opencodeshell.ui.components.OcPopoverPolicy
import com.xiufeigo.opencodeshell.ui.components.ocPopoverDismiss
import com.xiufeigo.opencodeshell.ui.nav.desktopCaptionReserve
import com.xiufeigo.opencodeshell.ui.overlay.ModalImePolicy
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/** One entry of the model menu; `provider` is reserved for the M2 provider icon. */
data class ModelOption(
    val id: String,
    val label: String,
    val provider: String? = null,
)

/**
 * The M1.5 fallback model data, kept for its JVM tests (`ComposerM1_5Test`).
 * The picker itself now reads the real catalog ([OpenCodeClient.Providers])
 * through [ModelPickerItems].
 */
object ModelOptions {

    fun fromLabel(label: String?): List<ModelOption> {
        val trimmed = label?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyList()
        return listOf(ModelOption(id = trimmed, label = trimmed))
    }
}

/**
 * Composer model chip (`prompt-input-v2.tsx` `PromptInputV2ModelControl`,
 * `research/02` §4.5): 28 dp tall, `max-w-[220px]`, `ProviderIcon` (unavailable
 * here — the asset set has no provider icons yet) + name + `▾`.
 *
 * Always rendered, falling back to the `oc_model_label` ("Model") string like
 * `ChatView.renderModelLabel`. Clicking opens the model picker
 * ([ModelPickerContent], upstream `ModelSelectorPopoverView`); while the
 * provider catalog is unavailable the chip explains that with a toast.
 *
 * The popup keeps M6's `menu-v2-content` shell: `layer-01`, radius 6,
 * `min-w 160` / `max-w 23rem`, `elevation-floating` (`research/03` §3.5) plus
 * the dropdown enter pair — `dropdown-menu-open` `.15s ease-out` with
 * `scale(.96) → 1` (`research/03` §2.1/§3.4).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ModelChip(
    current: String?,
    providers: OpenCodeClient.Providers?,
    selectedModel: Pair<String, String>?,
    overrideActive: Boolean,
    onSelect: (providerID: String, modelID: String) -> Unit,
    onUseServerDefault: () -> Unit,
    modifier: Modifier = Modifier,
    /** W5-C B4: the pinned variant (`null` = server/agent default). */
    variantLabel: String? = null,
    onSelectVariant: (String?) -> Unit = {},
    /**
     * W5-C B1: `/model` opens this picker. The composer bumps the counter; a
     * value change opens the menu, so the chip keeps owning its own open state.
     *
     * v75 P2: **只有新请求**才开面板（[modelOpenRequestArrived]）。宿主那条计数器
     * 是单向闩、永不回落，v74 却让 chip 在每次挂载时重读它 —— 于是用户点过一次
     * 之后，此后**任何**重建都会再弹一次面板：点 composer 文本区（焦点一落，
     * 键盘/布局跟着变，composer 重建）时模型选择器会"凭空"出现，真机上读起来
     * 就是「chip 的热区盖住了 composer 文本区」。本实例记住自己已经见过的值，
     * 重建后的实例把当前值当作已见过 —— 面板只在 `/model` 或 chip 自己那一下
     * 抬起计数器时打开（v74 的"重建后重开"改由 `Composer` 的 movable 槽位保证：
     * 形态翻转是**移动**，不是重建，面板根本不会被卸载）。
     */
    openRequest: Int = 0,
    /**
     * v74 P0: the chip's **own** tap, reported to the host so it can ride the
     * same counter `/model` does.
     *
     * [expanded] is local `remember`, so a composer rebuilt under the chip —
     * the model picker's own focus change hides the keyboard, which moves the
     * body and flips the composer's form — came back with `expanded = false`
     * and the picker never appeared, while `/model` worked: its request lives
     * outside the composer and the `LaunchedEffect` below re-reads it on every
     * re-mount. The tap therefore raises the request too, and the local write
     * only makes the same frame immediate.
     *
     * v75 P1: the form flip no longer rebuilds the chip at all — `Composer`
     * mounted the tool controls through a `movableContentOf` slot, so the flip
     * *moved* them and the open picker rode along; v81 P0 retired that slot for
     * the stronger version of the same property (the card is one `Layout` whose
     * two children — the editor and the tool row — are the same nodes in both
     * forms, so there is nothing to move and nothing to rebuild). This counter
     * stays as the one channel a request travels on (`/model` and the tap are the
     * same request), and the chip's own write keeps the same frame immediate.
     */
    onRequestOpen: (() -> Unit)? = null,
    /**
     * B-13: the picker's「管理模型」row (`select-dialog.tsx:544-556`). The chip
     * closes its own menu before handing the tap on, so the settings dialog never
     * opens over a live popup; null hides the row.
     */
    onManageModels: (() -> Unit)? = null,
    /**
     * v57 P0: the room the caller actually has for the chip (the composer
     * controls viewport's measured width). The chip lives inside a horizontal
     * scroller, whose children are measured with an unbounded width — so the
     * window-share ceiling alone cannot shrink it, and on a short pane the label
     * ran under the submit button instead of ellipsizing. A specified value
     * becomes the tightest ceiling; unspecified keeps the window share.
     */
    maxWidth: Dp = Dp.Unspecified,
    /**
     * v57: 弹层卸载时把焦点还给触发者（composer 编辑器）。弹层的搜索框 autofocus
     * 把焦点与 IME 都抢走了，卸载后没人接手的话布局永久停在「无键盘」那一档，
     * 下一次打开的锚点就永久偏移一个键盘高度（`OcPopoverDismiss` 文件头末节）。
     * 外点关闭不调用它 —— 那一击正要被补发给底下的控件。
     */
    onRestoreFocus: (() -> Unit)? = null,
) {
    val c = OcTheme.colors
    val context = LocalContext.current
    // v49: 弹层的 ESC + 外点穿透都挂在 [OcPopoverDismiss] 上，宿主窗口在这里
    // （Popup 之外）取，`LocalView` 在 Popup 内部拿到的是 Popup 自己。
    val hostView = LocalView.current
    var expanded by remember { mutableStateOf(false) }
    // 关闭原因：弹层内容卸载的那一刻才知道该不该把焦点还回去。
    //
    // v75 P1: 只有**真的关过一次**（这里非 null）才归还。组件被重建（形态翻转、
    // 会话切换、控件结构变化）而卸载时它还是 null —— 那不是一次关闭，照旧归还
    // 只会把键盘重新拉起来，键盘一起布局又翻回去、组件再被重建，来回震荡，
    // 弹层永远来不及显示（短窗上的「点不开」就是这么来的）。
    var closeReason by remember { mutableStateOf<OcPopoverCloseReason?>(null) }
    val closePicker: (OcPopoverCloseReason) -> Unit = { reason ->
        closeReason = reason
        expanded = false
    }
    val popoverDismiss = remember { OcPopoverDismiss(hostView) }
    // 打开之前键盘在不在：归还焦点后只有原本可见时才把键盘叫回来（原本没有就不
    // 多事），这一条决定了下一次打开时量到的 composer 位置和这一次相同。
    val imeVisibleNow = WindowInsets.isImeVisible
    val keyboardController = LocalSoftwareKeyboardController.current
    var imeVisibleAtOpen by remember { mutableStateOf(false) }
    // v75 P1: 打开这一次面板（chip 自己那一下 / `/model`）。清掉上一次的关闭原因：
    // 归还判定读的是**卸载那一刻**的原因，留着上一轮的值会让这一次的"重建卸载"
    // 被误判成一次关闭（`closePicker` 与 `expanded = false` 同帧生效，弹层内容
    // 不会再多组合一次）。
    val openPicker: () -> Unit = {
        imeVisibleAtOpen = imeVisibleNow
        closeReason = null
        expanded = true
    }
    // 外点那一击要等弹层真正从 WindowManager 上摘掉才能补发，否则 Popup 窗口
    // （touch-modal）会把它再吃一次。
    LaunchedEffect(expanded) { if (!expanded) popoverDismiss.flushPendingTap() }
    var anchor by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(LocalDensity.current) { MENU_GAP.roundToPx() }
    val label = buildString {
        append(current?.takeIf { it.isNotBlank() } ?: stringResource(R.string.oc_model_label))
        // `ComposerEditorConfiguredSelect` keeps the active variant visible next to
        // the model (`editor.tsx:295-306` renders a second control); the phone
        // toolbar has no room for a second chip, so the label carries it.
        variantLabel?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
    }

    // `/model` from the command menu: open the picker on the next composition.
    //
    // v75 P2: 只有**新**请求才开（[modelOpenRequestArrived]）。宿主那条计数器是
    // 单向闩、永不回落，v74 让 chip 在每次挂载时重读它 —— 于是用户点过一次之后，
    // 此后任何重建都会再弹一次面板：点 composer 文本区（焦点一落，键盘与布局跟着
    // 变，composer 重建）时模型选择器"凭空"出现。这里记住本实例已经见过的值，
    // 重建后的实例把当前值当作已见过。
    var seenRequest by remember { mutableStateOf(openRequest) }
    LaunchedEffect(openRequest) {
        if (modelOpenRequestArrived(seenRequest, openRequest)) openPicker()
        seenRequest = openRequest
    }
    // Same-name models (`space-bunny-free` under both `opencode` and
    // `opencode-go`) are indistinguishable by name alone — the badge names the
    // owning provider, but only while the name is genuinely ambiguous.
    val providerBadge = remember(providers, selectedModel) {
        modelChipProviderBadge(providers, selectedModel)
    }
    // Hoisted: `semantics {}` is not a composable scope. The label rides the
    // clickable Row, not the `Icon` inside it.
    val modelLabel = stringResource(R.string.oc_model_label)

    val widthCeiling = modelChipMaxWidth(LocalConfiguration.current.screenWidthDp, maxWidth)
    // Below [MODEL_CHIP_COMPACT_BELOW] the full chip crushes its label to
    // nothing and clips its chevron away; the compact form keeps both ends.
    val compact = modelChipCompact(widthCeiling)

    Box(modifier = modifier.onSizeChanged { anchor = it }) {
        Row(
            modifier = Modifier
                .height(CHIP_HEIGHT)
                .widthIn(min = MODEL_CHIP_MIN_WIDTH, max = widthCeiling)
                .clip(OcTheme.shapes.md)
                .clickable {
                    if (providers == null) {
                        Toast.makeText(
                            context,
                            R.string.oc_model_picker_unavailable,
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        openPicker()
                        // v74 P0: …and raise the hoisted request, so a composer
                        // rebuilt under the open popup re-opens it (see
                        // [onRequestOpen]). v75 P1: that rebuild no longer
                        // happens for a form flip (the tool controls ride one
                        // movable slot), so this is the one channel both `/model`
                        // and the tap travel on.
                        onRequestOpen?.invoke()
                    }
                }
                .semantics { contentDescription = if (compact) label else modelLabel }
                .padding(horizontal = if (compact) 2.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_model),
                contentDescription = null,
                tint = c.iconMuted,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(if (compact) 2.dp else 4.dp))
            if (!compact) {
                Text(
                    text = label,
                    color = c.textMuted,
                    fontSize = 13.sp,
                    // The label is the only elastic child: it takes exactly the
                    // width it needs and only ellipsizes once the chip has hit
                    // [modelChipMaxWidth] — a long model id never starves the
                    // badge and the chevron, which are what make the chip look
                    // tappable.
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!compact && providerBadge != null) {
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .clip(OcTheme.shapes.sm)
                        .border(0.5.dp, c.borderMuted, OcTheme.shapes.sm)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = providerBadge,
                        color = c.textFaint,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(if (compact) 0.dp else 2.dp))
            Icon(
                painter = painterResource(R.drawable.ic_oc_chevron_down),
                contentDescription = null,
                tint = c.textFaint,
                modifier = Modifier.size(if (compact) 12.dp else 14.dp),
            )
        }

        if (expanded) {
            // P1（`v53_19/20`）: 短窗上弹层不自动把焦点给搜索框，键盘就不会自己
            // 升起把弹层压成两行（见 `ModalImePolicy`）。否掉搜索框的自动聚焦时，
            // 焦点归属回到弹层根节点 —— `Popup` 窗口不替内容申请焦点，没有根焦点
            // ESC 就无处可来（`OcPopoverDismiss` 文件头 §1）。
            val searchAutofocus = ModalImePolicy.autoFocusOnOpen(
                requested = true,
                windowHeightDp = LocalConfiguration.current.screenHeightDp,
            )
            // Prefers opening below the chip and flips above it when it would
            // run off the bottom edge — the usual case, because the composer
            // sits at the bottom of the screen. Horizontally it stays inside the
            // window so a wide model id cannot push the menu off-screen.
            val density = LocalDensity.current
            // v58 (`research/v58_verify/5556/05_model_ime.png`): the caption the
            // window manager paints over a freeform window's top. The decor
            // reading alone measured 0 there (the decor *is* the content view,
            // and `adjustResize` shrinks both while the IME is up), so the
            // overlay was clamped onto y=0 and covered the caption. The reserve
            // does not move with the keyboard, so both window states hold.
            val captionReservePx = with(density) { desktopCaptionReserve().roundToPx() }
            val provider = remember(anchor, gapPx, captionReservePx) {
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
                            // v58 (`research/v57_verify/emu5556`): a `Popup` is
                            // positioned against the window **frame**, so on a
                            // freeform window the picker came out one caption
                            // strip too low and, flipped up, that much too high
                            // — over the caption. `ocOverlayY` adds the inset back
                            // and clamps the result into the content area.
                            y = ocOverlayY(
                                desiredContentY = if (fitsBelow) below else anchorBounds.top - gapPx - popupContentSize.height,
                                contentTopPx = ocOverlayContentTopPx(
                                    decorInsetPx = ocOverlayDecorInsetPx(
                                        rootHeightPx = hostView.rootView.height,
                                        contentHeightPx = hostView.height,
                                    ),
                                    captionReservePx = captionReservePx,
                                ),
                                windowHeightPx = hostView.rootView.height,
                                overlayHeightPx = popupContentSize.height,
                            ),
                        )
                    }
                }
            }
            Popup(
                popupPositionProvider = provider,
                onDismissRequest = { closePicker(OcPopoverCloseReason.OUTSIDE) },
                // v48 tD_34-36: the picker would not go away — ESC did nothing
                // (the focusable window eats the composer's key handling) and the
                // dismiss contract lived only on the defaults. Both flags stay
                // explicit so a later edit cannot silently drop them. v49: ESC is
                // answered on the popup content itself ([Modifier.ocPopoverDismiss]
                // — including the focus ownership a `Popup` never gives its
                // content), and the outside tap is recorded there so it can be
                // replayed onto the control underneath ([OcPopoverDismiss]).
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                ),
            ) {
                OcEnterOnMount(
                    overlay = OcMotion.Overlay.DROPDOWN,
                    // v75 P3: 面板自己的矩形（圆角 + 不透明底）画在入场动画**外面**。
                    // `OcEnterOnMount` 把 `fadeIn` 的 alpha 层加在 `modifier` **之后**
                    // 的链上（`AnimatedEnterExitImpl` 的 `modifier.then(enterModifier)`），
                    // 所以画在这个 modifier 上的底不进那一层 —— 面板从第一帧起就是
                    // 不透明的。底画在动画里面（内层 Column）时，整个面板在入场的那
                    // 150ms 里是半透的：短窗上弹层被夹到内容区顶部、正压在顶栏上，
                    // 顶栏文字就从面板里透出来（`v74_21/27`）。内容照旧淡入 + 缩放，
                    // `dropdown-menu-open` 的手感不变。
                    modifier = Modifier
                        .widthIn(min = MENU_MIN_WIDTH, max = MENU_MAX_WIDTH)
                        .clip(OcTheme.shapes.md)
                        .background(c.layer01)
                        // v49: ESC 关闭 + 外点穿透。焦点归属也在这个节点上建起来
                        // （Popup 窗口不替内容申请焦点，见 `OcPopoverDismiss`）。
                        // v51 `tE`: 弹层里自带搜索框，根节点就不替内容抢焦点了 ——
                        // 否则点 Search models 键盘不出、`input text` 无效、列表
                        // 不过滤（`OcPopoverDismiss` 文件头 §2）。P1: 窗口够高时搜索框
                        // 自己 autofocus；短窗上（不自动拉键盘）改由根节点承担焦点归属，
                        // ESC 依旧。
                        .ocPopoverDismiss(popoverDismiss, requestInitialFocus = !searchAutofocus) { closePicker(OcPopoverCloseReason.KEY) },
                ) {
                    // 卸载那一刻才做焦点归还：弹层还挂着的时候编辑器抢不回焦点，
                    // 而这一击（ESC / 选中）之后布局必须回到打开前的键盘状态。
                    //
                    // v75 P1: 读的是**卸载那一刻**的原因与回调，而不是组合时捕获的
                    // 那一份 —— `closePicker` 与 `expanded = false` 同帧生效，弹层内容
                    // 不会再多组合一次，捕获值会停在"打开时"的那一份（先外点、再 ESC
                    // 就会错判）。`null`（`openPicker` 会清成 null）= 这一次卸载不是
                    // 关闭：组件被重建而卸载时不归还，否则键盘被重新拉起、布局再翻
                    // 一次，弹层和重建来回震荡，面板永远来不及显示。
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
                        ModelPickerContent(
                            providers = providers,
                            selectedModel = selectedModel,
                            overrideActive = overrideActive,
                            selectedVariant = variantLabel,
                            // v51 `tE`: the popup — not the popover root — owns
                            // the initial focus, so Search models opens with the
                            // keyboard and typing filters the list. P1: 短窗上
                            // 交给 `ModalImePolicy` 否掉，键盘不自动升起。
                            autoFocusSearch = searchAutofocus,
                            onSelect = { providerID, modelID ->
                                closePicker(OcPopoverCloseReason.PICK)
                                onSelect(providerID, modelID)
                            },
                            onSelectVariant = { variant ->
                                closePicker(OcPopoverCloseReason.PICK)
                                onSelectVariant(variant)
                            },
                            onUseServerDefault = {
                                closePicker(OcPopoverCloseReason.PICK)
                                onUseServerDefault()
                            },
                            onManageModels = onManageModels?.let { manage ->
                                {
                                    closePicker(OcPopoverCloseReason.PICK)
                                    manage()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** `style={{height:"28px"}}`. */
private val CHIP_HEIGHT = 28.dp

/**
 * Ceiling for the chip, well past upstream's `max-w-[220px]`. That upstream cap
 * is a *max*, not a fixed size: the button shrinks to its content and only
 * truncates past 220 px. v43 tD_02 (1344 px wide) showed the shell's cap
 * cutting the name to「Muse Spark 1.3 Cont…」with ~250 px of the row still
 * empty, i.e. the name was truncated while the room to show it was unused.
 * The ceiling stays only so a pathological id cannot push the controls
 * viewport into a long scroll; real names fit well inside it.
 */
private val CHIP_MAX_WIDTH = 320.dp

/**
 * Share of the window the chip may take — the bound that actually protects the
 * composer row, since the controls viewport is a horizontal scroller. v40
 * tD_70 (720x1280 @420dpi = a 274 dp window): the old fixed 220 dp chip was
 * wider than that viewport, so the model name ran under the submit button and
 * the chevron — the only hint that the chip opens the picker — left the
 * visible row entirely. v43 measured the free room between the chip and the
 * submit button on both a 274 dp and a 448 dp window: the share below still
 * leaves the whole control, chevron included, inside the row, while giving the
 * label every pixel it needs before it ellipsizes.
 */
private const val CHIP_MAX_WINDOW_SHARE = 0.62f

/**
 * Floor for the chip: icon + a few label characters + chevron stay inside the
 * row whatever the one-row split leaves the controls viewport. 88 dp is the
 * 48 dp of fixed chrome (icon 16 + gaps 6 + chevron 14 + padding 12, see
 * `ModelChipLabelWidthTest`) plus ~40 dp of label — about six characters at
 * 13 sp, enough to tell `opencode…` from `GLM-5…`. Below
 * [MODEL_CHIP_COMPACT_BELOW] the chip takes its compact form (icon + chevron,
 * the name on the content description) instead of crushing the label to
 * nothing — that midpoint used to read `ope...` with the chevron clipped
 * away (`research/v80_verify/5554/16_480_session.png`).
 * Inert on a roomy row (content is wider) and above it the viewport ceiling
 * still caps long ids; a row squeezed past even this by extra fixed children
 * (the delivery toggle while a turn runs) scrolls instead of clipping the
 * chevron off.
 */
internal val MODEL_CHIP_MIN_WIDTH: Dp = 88.dp

/**
 * The ceiling below which the chip takes its compact form — icon + chevron,
 * no label text, the model name on the content description.
 *
 * The full chip's fixed chrome is 48 dp (icon 16 + gaps 6 + chevron 14 +
 * padding 12, see `ModelChipLabelWidthTest`), so below 56 dp it leaves no
 * room for even one label character and the Row overflow pushes the chevron
 * out of the controls viewport: the chip reads as icon-only with no text and
 * no affordance at all (420 px window). The compact chrome is 34 dp
 * (padding 2×2 + icon 16 + gap 2 + chevron 12), which stays whole on that
 * window's ~36 dp controls share.
 */
internal val MODEL_CHIP_COMPACT_BELOW: Dp = 56.dp

/** Whether the chip at [widthCeiling] renders its compact form. Pure for JVM tests. */
internal fun modelChipCompact(widthCeiling: Dp): Boolean = widthCeiling < MODEL_CHIP_COMPACT_BELOW

/** [CHIP_MAX_WIDTH], never wider than [CHIP_MAX_WINDOW_SHARE] of the window. */
internal fun modelChipMaxWidth(windowWidthDp: Int): Dp =
    minOf(CHIP_MAX_WIDTH, (windowWidthDp * CHIP_MAX_WINDOW_SHARE).dp)

/**
 * [modelChipMaxWidth], tightened to [available] when the caller knows the room
 * it really has (the controls viewport). The tightest of the three wins, so a
 * roomy pane is unchanged and a short one ellipsizes instead of being clipped.
 */
internal fun modelChipMaxWidth(windowWidthDp: Int, available: Dp): Dp {
    val window = modelChipMaxWidth(windowWidthDp)
    return if (available != Dp.Unspecified) minOf(window, available) else window
}

/** `menu-v2-content`: `min-width: 160px; max-width: 23rem`. */
private val MENU_MIN_WIDTH = 160.dp
private val MENU_MAX_WIDTH = 368.dp

/** Gap between the chip and its menu. */
private val MENU_GAP = 4.dp

/**
 * Provider badge for [ModelChip]: the `providerID` owning the selected model,
 * or `null` when the chip renders its bare name.
 *
 * Shown **only** while the selected model's display name is cataloged under
 * more than one provider (`space-bunny-free` lives under both `opencode` and
 * `opencode-go`, and a bare name cannot tell which backend the session would
 * run on). An unconditional suffix would badge every chip for no information;
 * the full `providerID` (not a lossy short name) keeps the badge exact, and a
 * raw id needs no localized copy. Pure for JVM tests.
 */
internal fun modelChipProviderBadge(
    providers: OpenCodeClient.Providers?,
    selectedModel: Pair<String, String>?,
): String? {
    if (providers == null || selectedModel == null) return null
    val (providerID, modelID) = selectedModel
    val name = providers.model(providerID, modelID)?.name ?: return null
    val owners = providers.all.count { provider ->
        provider.models.any { it.name == name }
    }
    return if (owners > 1) providerID else null
}

/**
 * v75 P2: whether [current] is a request this chip instance has not seen yet.
 * The host's counter (`externalModelOpen`) is a one-way latch that never falls
 * back, and v74 let the chip re-read it on **every** mount — so once the user had
 * tapped the chip, any later rebuild of the composer re-opened the picker out of
 * nowhere (a tap on the composer's text area moves the keyboard and the layout,
 * which is exactly a rebuild). A rebuilt instance now treats the value it mounts
 * with as already seen, and only a genuinely **new** value opens the panel; the
 * v74 guarantee ("a composer rebuilt under the open picker keeps it") is carried
 * by `Composer`'s movable tool-controls slot instead — a form flip **moves** the
 * chip, so its popup is never unmounted at all.
 *
 * @param seen the value this instance has already seen (its mount value).
 * @param current the value the host publishes now.
 */
internal fun modelOpenRequestArrived(seen: Int, current: Int): Boolean =
    current > 0 && current != seen

/**
 * v75 P2: the height the chip is really touchable at.
 *
 * Compose 1.7 gives **every** pointer-input node smaller than the platform
 * minimum touch target a centred 48 dp rectangle for touch events
 * (`NodeCoordinator.hitNear` / `distanceInMinimumTouchTarget`, the same rule the
 * v71 × button ring came from) — the 28 dp chip is therefore 48 dp tall on the
 * device, and `hitTestChild` descends without a bounds check, so that band also
 * reaches 2 dp past the 44 dp tool row ([modelChipTouchOverflowDp]) and into the
 * composer's text band. That band cannot be shrunk (the 48 dp minimum is not
 * opt-out-able in 1.7 and a clickable inside a 44 dp row is clamped to the row),
 * so the text band has to own its own taps — `Composer` puts a focus tap on the
 * whole editor slot, and a **direct** hit always beats a minimum-touch-target
 * hit. Pure for JVM tests.
 */
internal fun modelChipTouchHeightDp(
    chipHeight: Dp = CHIP_HEIGHT,
    minimumTouchTarget: Dp = MINIMUM_TOUCH_TARGET,
): Dp = maxOf(chipHeight, minimumTouchTarget)

/** How far the chip's touch band reaches past a [rowHeight] row, per side. */
internal fun modelChipTouchOverflowDp(
    rowHeight: Dp,
    chipHeight: Dp = CHIP_HEIGHT,
    minimumTouchTarget: Dp = MINIMUM_TOUCH_TARGET,
): Dp = (modelChipTouchHeightDp(chipHeight, minimumTouchTarget) - rowHeight) / 2

/**
 * `ViewConfiguration.minimumTouchTargetSize` — the platform minimum Compose
 * expands an undersized pointer-input node to (48 dp, `AndroidViewConfiguration`).
 */
private val MINIMUM_TOUCH_TARGET = 48.dp

/**
 * v75 P1: whether the picker's content may hand the focus (and the keyboard)
 * back to the composer when it is unmounted.
 *
 * `null` means **this unmount is not a close** — the chip (or the popup) was
 * rebuilt out from under the picker (the composer's form flip, a session switch,
 * a structural change in the chip). Restoring the focus there is what closed the
 * loop on a short window: the picker's own focus change hides the keyboard, the
 * body grows, the form flips, the chip is rebuilt, the teardown showed the
 * keyboard again, the body shrank, the form flipped back — an open/destroy loop
 * the picker never survived, which is the reported「chip 点不开」and the reason its
 * 150 ms enter fade was always caught half-done (the see-through panel, P3).
 *
 * Only a real close ([OcPopoverCloseReason]) restores — and of those, only the
 * ones [OcPopoverPolicy.restoresFocus] accepts (an outside tap is replayed onto
 * the control underneath instead). Pure for JVM tests.
 */
internal fun restoresFocusOnUnmount(reason: OcPopoverCloseReason?): Boolean =
    reason != null && OcPopoverPolicy.restoresFocus(reason)
