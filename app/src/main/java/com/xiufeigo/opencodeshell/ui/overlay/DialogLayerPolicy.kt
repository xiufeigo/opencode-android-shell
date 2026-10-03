package com.xiufeigo.opencodeshell.ui.overlay

import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * How a [DialogHost] layer reacts to Escape, and how it stops reacting once it
 * has been dismissed.
 *
 * **Escape dismisses the top layer — unless the IME owns the key.** The key has
 * two jobs on this shell and they must not collide: `input keyevent 111` is the
 * harness's 收键盘, so treating Escape as a dismissal *while typing* closed the
 * settings dialog the moment a form field's keyboard collapsed (the WebView
 * shell had already fixed that half for itself:
 * `AndroidBackJs.shellConsumesEscape() == false`). The split is by IME
 * visibility, not by dialog: with the keyboard up Escape collapses it and
 * nothing else; with the keyboard down Escape is the layer dismissal the
 * device run asked for (scrim tap and BACK stay the pointer/hardware paths).
 * Inner handlers still win — the host listens on the *bubble* pass
 * (`onKeyEvent`), so the palette's own Escape and the settings keybind
 * recorder's cancel see the key first.
 *
 * **A scrim tap dismisses only outside the content box.** Upstream's layer is
 * `pointer-events:none` with an interactive child, so a click on the dialog body
 * never reaches the backdrop. The scrim's tap detector fired on every
 * *unconsumed* tap — including taps on inert parts of the dialog body (labels,
 * padding, anything without `clickable`) — and closed the layer under the
 * user's finger.
 */
object DialogEscape {

    /** Escape dismisses the top layer (the IME exception is [escapeDismissesLayer]). */
    fun dismissesLayerOnEscape(): Boolean = true

    /**
     * `true` while the IME is up: consume Escape and collapse the keyboard only,
     * shielding inner handlers (the palette's own Escape) mid-typing. `false`
     * with the IME down: the key is the layer's dismissal.
     */
    fun consumesToCollapseIme(imeVisible: Boolean): Boolean = imeVisible

    /**
     * The dismissal decision for one key event: with the IME down Escape closes
     * the top layer, with it up the same key collapses the keyboard instead.
     */
    fun escapeDismissesLayer(imeVisible: Boolean): Boolean =
        dismissesLayerOnEscape() && !consumesToCollapseIme(imeVisible)

    /**
     * v61（`research/v61_verify/5558`）：手机形态下 **ESC 关不掉命令面板** 的根因
     * 与修法。
     *
     * [escapeDismissesLayer] 的 IME 例外在键盘**回不来**的桌面上是对的（一次 ESC =
     * 关闭），在**手机**上却是一个死循环：面板的搜索框 autofocus，软键盘随之升起，
     * 于是 ESC 只收键盘；下一次 `input keyevent 111` 打在仍有焦点的文本框上，
     * Android 又把键盘拉起来 —— 于是 ESC 在「收键盘」与「键盘又回来了」之间来回，
     * 真机连按 3 次面板都不关（`v61_verify/5558/12_pal_esc1..3`）。
     *
     * 修法是记住「这一层已经被 ESC 收过一次键盘」：第一次 ESC 仍然只收键盘
     * （正在输入的表单不被误关，`v50` 的原意保留），第二次 ESC 不管键盘状态一律
     * 关闭。桌面上键盘从来没起来过，[collapsedByEscape] 恒为 false，仍是一次 ESC
     * 关闭 —— 桌面行为不变。
     */
    fun escapeDismissesLayer(imeVisible: Boolean, collapsedByEscape: Boolean): Boolean =
        dismissesLayerOnEscape() && !consumesToCollapseIme(imeVisible) ||
            (dismissesLayerOnEscape() && collapsedByEscape)

    /** ESC 这一下对某层做什么（[escapeDismissesLayer] 的三分支，供 JVM 断言）。 */
    enum class EscapeAction {
        /** 收键盘，这一层不动。 */
        COLLAPSE_IME,

        /** 关闭这一层。 */
        DISMISS,

        /** 不是 ESC 的 KeyDown：不管。 */
        IGNORE,
    }

    fun escapeAction(
        isEscapeKeyDown: Boolean,
        imeVisible: Boolean,
        collapsedByEscape: Boolean,
    ): EscapeAction = when {
        !isEscapeKeyDown -> EscapeAction.IGNORE
        escapeDismissesLayer(imeVisible, collapsedByEscape) -> EscapeAction.DISMISS
        else -> EscapeAction.COLLAPSE_IME
    }
}

/**
 * Input policy of a *mounted* layer.
 *
 * A dismissed layer is not unmounted at once: [DialogHost] keeps it for
 * [OcMotion.exitHoldMs] so its exit transition can play (upstream
 * `contentHide` / `overlayHide`). While it lingers it must take no input: the
 * first click after every close did nothing (真机: 「弹层关闭后第一次点击被吞」,
 * `research/HANDOFF.md` 低优残留 「弹层退出动画卡片可吞一击」).
 *
 * **Inert is not enough — a closing layer has to leave the hit path.** Compose's
 * hit walk stops at the first sibling that records a hit, and a `pointerInput`
 * node records one whether or not its coroutine is still running
 * (`HitTestResult.hit` → `NodeCoordinator.shouldSharePointerInputWithSiblings`,
 * and a plain `pointerInput` never shares). So a scrim whose detector merely
 * *returns early* while closing still starves the screen behind it, and the
 * card's own pointer nodes — a scroll body, a row button — starve it wherever
 * the tap lands on the card. Both halves are therefore structural:
 *
 * - the scrim's tap detector is **removed from the modifier chain** while
 *   closing ([acceptsPointerInput] gates the modifier itself, not the block
 *   inside it), and
 * - the content is **displaced off the viewport** ([ghostOffsetPx]) so no
 *   on-screen pointer can be inside it; the exit transition draws it back in
 *   place, so the animation is unchanged.
 */
object DialogLayerPolicy {

    /** Where a mounted layer is in its life — the exit is a phase of its own. */
    enum class Phase {
        /** On screen: the scrim dismisses and the card's controls act. */
        OPEN,

        /** Playing its exit: drawn, but nothing of it may take a pointer. */
        EXITING,
    }

    fun phaseOf(closing: Boolean): Phase = if (closing) Phase.EXITING else Phase.OPEN

    /** `true` only while the layer is still open — a closing layer is inert. */
    fun acceptsPointerInput(closing: Boolean): Boolean = phaseOf(closing) == Phase.OPEN

    /** `true` while the layer must be off the hit path altogether (see the type doc). */
    fun isHitTransparent(closing: Boolean): Boolean = phaseOf(closing) == Phase.EXITING

    /**
     * `true` while at least one mounted layer is still open.
     *
     * A dismissed layer stays mounted for its exit transition, so "the stack is
     * non-empty" stays true ~100 ms after the last close: gating BACK (or
     * Escape) on that would swallow one press on the screen the user closed the
     * dialog to get back to. The dismissal stays armed only while an open layer
     * owns it.
     */
    fun hasOpenLayer(closings: List<Boolean>): Boolean = closings.any { !it }

    /** Margin past the viewport edge, so the displacement is never a hair short. */
    const val GHOST_SLACK_PX = 32

    /**
     * Vertical displacement (px) that carries a closing layer's content clear of
     * every on-screen pointer.
     *
     * The content is centred in the viewport, so its top edge sits at
     * `(viewport − content) / 2`; clearing the viewport means carrying that edge
     * past the bottom, i.e. displacing by `(viewport + content) / 2`. The sum
     * used here — `viewport + content + slack` — clears that with room to spare
     * for a card taller than the window (whose top edge is negative) and stays
     * positive before anything has been measured, so an unmeasured closing layer
     * is off the hit path from its first frame. Open layers are not displaced at
     * all.
     */
    fun ghostOffsetPx(closing: Boolean, viewportPx: Int, contentPx: Int): Int =
        if (!isHitTransparent(closing)) {
            0
        } else {
            viewportPx.coerceAtLeast(0) + contentPx.coerceAtLeast(0) + GHOST_SLACK_PX
        }
}

/**
 * `true` when [event] was consumed as an IME collapse — never a dismissal.
 *
 * [collapsedByEscape] 是这一层**已经**被 ESC 收过一次键盘（v61）：那一次之后的
 * ESC 属于关闭，预览这一遍就不能再把它当「收键盘」吃掉，否则事件到不了宿主那条
 * 关闭的 handler 上。
 */
fun collapseImeOnEscape(
    event: KeyEvent,
    imeVisible: Boolean,
    collapsedByEscape: Boolean = false,
    hideIme: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown || event.key != Key.Escape) return false
    if (!DialogEscape.consumesToCollapseIme(imeVisible) || collapsedByEscape) return false
    hideIme()
    return true
}

/** IME visibility at event time — the `WindowInsets` composition read cannot be taken in a key handler. */
fun isImeVisible(view: View): Boolean =
    ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true

/**
 * A tap at [tapInRoot] dismisses the layer only when it lands outside the
 * content box. Unknown bounds (before first layout) never dismiss.
 *
 * [backdropBounds] is the **backdrop override** (v73): a box that reads as the
 * backdrop *whatever the card says*, because the card's own reported box can
 * cover it. The session composer is that box — a short window with the keyboard
 * up centers the `/btw` card right down over it (5556, measured: the card's box
 * y 124…259 × x 443…922 against the composer's y 230…334 × x 12…1353), so the tap
 * the user aimed at the composer landed "inside the card" and closed nothing. A
 * composer tap is the backdrop's: the composer is inert while a modal is up, and
 * the layer hands it the caret when it closes (v69 C2). Where the card *does* own
 * a control under the tap, that control consumes the gesture first and this test
 * never runs.
 */
fun scrimTapDismisses(
    contentBounds: Rect?,
    tapInRoot: Offset,
    backdropBounds: Rect? = null,
): Boolean =
    (backdropBounds != null && backdropBounds.contains(tapInRoot)) ||
        (contentBounds != null && !contentBounds.contains(tapInRoot))

/**
 * Viewport coordination for a [DialogHost] layer.
 *
 * Upstream parity: `dialog-content` (`dialog.css`) is `max-height: 100%` with
 * `overflow: auto` inside its fixed container — an oversized body scrolls
 * instead of spilling past the viewport. The native layer used to be a bare
 * centered box: content taller than the real window (small windows, landscape
 * phones, split-screen — anywhere the fixed shells sized from
 * `LocalConfiguration` overshoot the actual layer) overflowed symmetrically
 * and was cut off by the window, e.g. the Pairing settings' QR block.
 *
 * The host therefore caps a layer to the *measured* viewport and lets an
 * overflowing layer scroll as a fallback. The caps stay clear of every fitting
 * layer (they only bind past the viewport), and `fillMaxSize` roots can never
 * trip the fallback (they are exactly viewport-sized by construction), so no
 * fitting layer changes geometry.
 *
 * Pure on [Dp] so JVM unit tests can pin it; the Compose wiring lives in
 * `DialogLayer`.
 */
object DialogViewportFit {

    /** Total horizontal inset — upstream x-large `min(100vw − 32, 980)`. */
    val horizontalMargin: Dp = 32.dp

    /**
     * Total vertical inset — the tablet host cap precedent
     * (`max-height: calc(100% - 16px)`, pinned by `TabletDialogLayoutTest`).
     */
    val verticalMargin: Dp = 16.dp

    /** Floor so the cap never collapses to nothing on absurd viewports. */
    val minCap: Dp = 160.dp

    /** Widest a layer may be inside a [viewport]-wide host. */
    fun maxWidth(viewport: Dp): Dp = (viewport - horizontalMargin).coerceAtLeast(minCap)

    /** Tallest a layer may be inside a [viewport]-tall host. */
    fun maxHeight(viewport: Dp): Dp = (viewport - verticalMargin).coerceAtLeast(minCap)

    /**
     * True when [contentPx] no longer fits inside [viewportPx] — the scroll
     * fallback engages. Compared against the bare viewport (no margin) on
     * purpose: a layer that fits keeps byte-identical geometry, and a
     * `fillMaxSize` root (exactly viewport-sized by construction) can never
     * trip it. Zero/unmeasured viewports never engage.
     */
    fun overflows(contentPx: Int, viewportPx: Int): Boolean =
        viewportPx > 0 && contentPx > viewportPx
}
