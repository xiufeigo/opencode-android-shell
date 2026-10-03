package com.xiufeigo.opencodeshell.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.theme.LocalOcReducedMotion
import com.xiufeigo.opencodeshell.ui.theme.OcMotion

/**
 * The Compose half of the v2 overlay motion (`research/03` §2.1 keyframes,
 * §3.4 table): `AnimatedVisibility` with the family's enter/exit timing and the
 * `scale(.96|.98) → 1` + opacity pair.
 *
 * Every entry point routes through [OcMotion.enterSpec] / [OcMotion.exitSpec] so
 * the 120–150 ms in / 80–100 ms out rhythm and the reduced-motion collapse live
 * in exactly one place.
 *
 * Plays the **enter** transition on first composition, for hosts that mount
 * their content only while open (`Popup`, `DropdownMenu`, dialog layers) — a
 * plain `AnimatedVisibility(visible = true)` renders the end state with no
 * transition at all.
 *
 * @param origin scale origin; `top center` is `menu-v2-content`'s
 *   `transform-origin`, `bottom center` is right for a popover that opens upward.
 */
@Composable
fun OcEnterOnMount(
    overlay: OcMotion.Overlay = OcMotion.Overlay.DEFAULT,
    modifier: Modifier = Modifier,
    origin: TransformOrigin = TransformOrigin(0.5f, 0f),
    content: @Composable () -> Unit,
) {
    val reduced = LocalOcReducedMotion.current
    val state = remember { MutableTransitionState(false) }
    state.targetState = true
    val enter = OcMotion.enterSpec(overlay, reduced)
    AnimatedVisibility(
        visibleState = state,
        modifier = modifier,
        enter = fadeIn(enter) + scaleIn(enter, initialScale = overlay.startScale, transformOrigin = origin),
        // Never exits: the host unmounts the whole overlay instead.
        exit = ExitTransition.None,
    ) {
        content()
    }
}

/**
 * Where a `Popup`-hosted overlay's top-left corner goes once the window's own
 * top chrome is taken out of the picture.
 *
 * A `Popup` is a separate platform window whose position is applied in the
 * **frame** coordinate the window manager sees, while `PopupPositionProvider`
 * hands back a number measured in the Compose content area. In a freeform /
 * desktop-mode window the two differ by the caption strip above the content
 * (see [ocOverlayContentTopPx]), so a popup placed at "just under its anchor"
 * came out that much lower, and one flipped up past the content's top edge
 * **over the caption** — the model picker on a freeform window,
 * `research/v57_verify/emu5556` `E2ECHK5556-09-model-select.png` (~27 px over).
 * Adding the inset back puts the overlay back in the content area's own
 * coordinates; the clamp then keeps it inside that area even when it is taller
 * than the window.
 *
 * Pure integer math so the JVM pin can read it without a Compose runtime.
 */
internal fun ocOverlayY(
    desiredContentY: Int,
    contentTopPx: Int,
    windowHeightPx: Int,
    overlayHeightPx: Int,
): Int {
    val top = contentTopPx.coerceAtLeast(0)
    val bottom = (windowHeightPx - overlayHeightPx).coerceAtLeast(top)
    return (desiredContentY + top).coerceIn(top, bottom)
}

/**
 * How far the window's content area starts below the frame the window manager
 * positions popups against. Two independent readings, and the **larger** one
 * wins because either can be the whole truth:
 *
 * - [ocOverlayDecorInsetPx] — the decor's height minus the content view's own.
 *   This is the caption strip whenever the window manager insets the app window
 *   itself (the model picker on a 1280-tall freeform window: 1280 − 1225).
 * - the app's own **caption reserve** (`desktopCaptionReserve`, 36 dp on a
 *   freeform window, 0 where the status bar already owns the band). The window
 *   manager paints that caption over the app's top and keeps every touch in it
 *   (`ui/nav/DesktopTabStrip.kt`); a `Popup` sits **above** it in z-order, so an
 *   overlay placed without the reserve is not merely misaligned — it draws over
 *   the caption. `research/v58_verify/5556/05_model_ime.png`: the model's top
 *   edge landed at y=0 while the content area starts at y=46.
 *
 * The decor reading alone cannot cover the second case: in desktop mode the
 * decor **is** the content view (both 768 px tall on 5556), and while the IME
 * is up `adjustResize` shrinks both by the same keyboard height — the diff is
 * 0 either way, so v58's first attempt measured 0 here and the clamp then
 * pinned the overlay to y=0 (v58 `05_model_ime.png`, worse than the ~27 px it
 * replaced). The reserve does not move with the IME, which is exactly what
 * makes it the reading that holds in both window states.
 */
internal fun ocOverlayContentTopPx(decorInsetPx: Int, captionReservePx: Int): Int =
    maxOf(decorInsetPx.coerceAtLeast(0), captionReservePx.coerceAtLeast(0))

/**
 * The decor measurement behind [ocOverlayContentTopPx]: the decor's height
 * minus the content view's own height, which is the caption strip whenever the
 * window manager insets the app window. Anything that does not add up (both
 * measurements 0, a desktop window whose decor *is* the content) reads as 0
 * and leaves the other reading in charge.
 */
internal fun ocOverlayDecorInsetPx(rootHeightPx: Int, contentHeightPx: Int): Int =
    (rootHeightPx - contentHeightPx).coerceAtLeast(0)

/**
 * Same motion, driven by a state flag — for overlays that live inside a layout
 * (the `@`-mention popover above the composer, inline cards) rather than in
 * their own window. A vertical expand rides along so the surrounding column does
 * not jump when the card appears.
 */
@Composable
fun OcEnterExitVisibility(
    visible: Boolean,
    overlay: OcMotion.Overlay = OcMotion.Overlay.DEFAULT,
    modifier: Modifier = Modifier,
    origin: TransformOrigin = TransformOrigin(0.5f, 1f),
    content: @Composable () -> Unit,
) {
    val reduced = LocalOcReducedMotion.current
    val enter = OcMotion.enterSpec(overlay, reduced)
    val exit = OcMotion.exitSpec(overlay, reduced)
    // `expandVertically` / `shrinkVertically` animate an `IntSize`, so they need
    // their own spec instances even though the timing is the same.
    val enterSize = tween<IntSize>(enter.durationMillis, easing = enter.easing)
    val exitSize = tween<IntSize>(exit.durationMillis, easing = exit.easing)
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(enter) +
            scaleIn(enter, initialScale = overlay.startScale, transformOrigin = origin) +
            expandVertically(enterSize, expandFrom = Alignment.Bottom),
        exit = fadeOut(exit) +
            scaleOut(exit, targetScale = overlay.startScale, transformOrigin = origin) +
            shrinkVertically(exitSize, shrinkTowards = Alignment.Bottom),
        label = "oc-overlay",
    ) {
        content()
    }
}

/**
 * `[data-slot=tool-count-summary-item]` (`research/03` §3.3): a counter that
 * gathers in as its bucket goes from zero to one.
 *
 * The CSS tweens `grid-template-columns: 0fr → 1fr` — width — over **480 ms**
 * with `cubic-bezier(.22,1,.36,1)`, fades over 280 ms in / 320 ms out and
 * collapses a `2px` blur plus `translateY(.06em) scale(.985)` as it goes.
 * `expandHorizontally` / `shrinkHorizontally` stand in for the grid track,
 * `fadeIn` / `fadeOut` for the opacity, and a `graphicsLayer` + `blur` pair
 * driven off the visibility transition for the "gather".
 *
 * An item that is already visible on first composition renders instantly (no
 * animation) — which is what keeps a group scrolled into view from re-animating.
 */
@Composable
fun OcCountItem(
    visible: Boolean,
    modifier: Modifier = Modifier,
    /** `translateY(.06em)` at the group's 14 sp ≈ 1 dp. */
    translateFrom: Dp = 1.dp,
    content: @Composable () -> Unit,
) {
    val reduced = LocalOcReducedMotion.current
    val widthSpec = tween<IntSize>(
        durationMillis = OcMotion.durationMs(OcMotion.ToolCount.SPRING_MS, reduced),
        easing = OcMotion.softSpringEasing,
    )
    val fadeInSpec = tween<Float>(
        durationMillis = OcMotion.durationMs(OcMotion.ToolCount.FADE_IN_MS, reduced),
        easing = LinearOutSlowInEasing,
    )
    val fadeOutSpec = tween<Float>(
        durationMillis = OcMotion.durationMs(OcMotion.ToolCount.FADE_OUT_MS, reduced),
        easing = LinearOutSlowInEasing,
    )
    val gatherSpec = tween<Float>(
        durationMillis = OcMotion.durationMs(OcMotion.ToolCount.STATUS_SWAP_MS, reduced),
        easing = LinearOutSlowInEasing,
    )
    val drift = with(LocalDensity.current) { translateFrom.toPx() }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = expandHorizontally(widthSpec, expandFrom = Alignment.Start) + fadeIn(fadeInSpec),
        exit = shrinkHorizontally(widthSpec, shrinkTowards = Alignment.Start) + fadeOut(fadeOutSpec),
        label = "oc-count-item",
    ) {
        // `AnimatedVisibilityScope.transition` carries the appear/disappear
        // progress into the "gather", so blur/scale ride the same 240 ms.
        val gather by transition.animateFloat(transitionSpec = { gatherSpec }, label = "count-gather") {
            if (it == EnterExitState.Visible) 1f else 0f
        }
        Box(
            Modifier
                .graphicsLayer {
                    translationY = (1f - gather) * drift
                    val scale = OcMotion.ToolCount.SCALE_FROM +
                        (1f - OcMotion.ToolCount.SCALE_FROM) * gather
                    scaleX = scale
                    scaleY = scale
                }
                // No-op below API 31 — same degradation as a browser without blur.
                .blur(radius = ((1f - gather) * OcMotion.ToolCount.BLUR_DP).dp),
        ) {
            content()
        }
    }
}
