package com.xiufeigo.opencodeshell.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * The backdrop of a modal that draws its own frame — the rule [DialogHost]
 * applies to its own scrim ([scrimTapDismisses]), for the surfaces that cannot
 * ride the host (a second scrim inside a layer would dim the screen twice, and
 * a `fillMaxSize` root would defeat the host's tap-outside test).
 *
 * **A scrim tap dismisses only outside the card.** A bare `clickable`
 * `fillMaxSize` Box fired on every *unconsumed* tap — including taps on inert
 * parts of the card (labels, padding, anything without `clickable`) — so
 * tapping a dialog's own whitespace closed it under the user's finger. The
 * detector therefore compares the tap against the card's measured bounds and
 * only dismisses when the tap landed on the backdrop. Bounds unknown (before
 * first layout) never dismiss.
 *
 * **"The card" is the card's own box, never a wrapper around it.** The bounds
 * come from [dialogCardBounds] on the card; where nothing reports them, the
 * host's measured content box stands in ([DialogScrimState.contentBounds]) —
 * which is the card only while the layer's root *is* the card. A root that
 * carries more than the card (the `/btw` panel's safe-area padding) has to
 * report, or the extra area reads as "inside" and swallows the taps aimed at
 * the screen behind it (v71: the whole keyboard strip counted as card and a tap
 * on the composer behind the panel closed nothing).
 *
 * **The scrim is not a control, so it must not be in the accessibility tree.**
 * `clickable` gave the backdrop `onClick` semantics: a screen-filling,
 * unlabeled *button* that an accessibility service — or `uiautomator dump` —
 * landed on first, above the dialog it was covering. `detectTapGestures`
 * carries no semantics, so the dimmed backdrop stays a picture.
 *
 * Usage — the state is shared by the backdrop and the card it guards:
 *
 * ```
 * val scrim = rememberDialogScrimState()
 * Box(modifier.fillMaxSize()) {
 *     DialogScrim(scrim, onDismiss)
 *     Column(
 *         modifier = Modifier
 *             .align(Alignment.Center)
 *             …
 *             .dialogCardBounds(scrim)   // after the size/clip modifiers,
 *             .padding(16.dp),           // *before* the card's own padding
 *     ) { … }
 * }
 * ```
 *
 * [Modifier.dialogCardBounds] must sit *before* the card's inner padding so the
 * whole card counts as "inside": a tap on the card's own margin is a tap on the
 * dialog, not on the backdrop. Placing it on a `fillMaxSize` wrapper instead
 * would measure the screen and make the backdrop undismissable.
 *
 * **The detector is a hit-test target for as long as it is mounted** — a
 * `pointerInput` node takes the hit even when its block does nothing, and the
 * walk stops at the first sibling that records one. A caller that keeps this
 * backdrop mounted through its own *exit* animation (an `AnimatedVisibility`
 * fading it out) therefore has to drop the detector for that window — which is
 * what [DialogHost] does for its own scrim
 * ([DialogLayerPolicy.acceptsPointerInput]); otherwise the tap the user aims at
 * the screen behind dies on the fading backdrop.
 */
class DialogScrimState {

    /**
     * The card's bounds in root coordinates, as the card itself reports them
     * ([dialogCardBounds]); null until it is first laid out.
     */
    internal var cardBounds by mutableStateOf<Rect?>(null)

    /**
     * The box [DialogHost] measured as the layer's content — the fallback for
     * the layers whose root box *is* their card, so they report nothing. A layer
     * whose root is **bigger** than its card (the `/btw` panel pads its root by
     * the safe-area insets, so the measured box is the card plus the whole
     * keyboard strip) reports the card itself and this box is never consulted —
     * reading it made the composer behind the card count as "inside" and the tap
     * on it closed nothing (v71).
     */
    internal var contentBounds by mutableStateOf<Rect?>(null)

    /** The scrim's own origin, so a local tap can be compared in root space. */
    internal var scrimOrigin by mutableStateOf(Offset.Zero)

    /**
     * v73 — a box that reads as the backdrop even where [cardBounds] covers it:
     * the session composer behind the card ([SessionComposerBounds]). Set by a
     * layer that has the composer to fear, i.e. the `/btw` panel — see
     * [com.xiufeigo.opencodeshell.ui.overlay.scrimTapDismisses].
     */
    internal var backdropBounds by mutableStateOf<Rect?>(null)

    /** The box a tap has to land outside to count as a backdrop tap. */
    internal val guardedBounds: Rect? get() = cardBounds ?: contentBounds

    /**
     * `true` when a tap at [tap] — in the *scrim's* coordinates — landed
     * outside the card, i.e. on the backdrop.
     */
    fun dismisses(tap: Offset): Boolean =
        scrimTapDismisses(guardedBounds, tap + scrimOrigin, backdropBounds)
}

/** Remembers the [DialogScrimState] one modal's backdrop and card share. */
@Composable
fun rememberDialogScrimState(): DialogScrimState = remember { DialogScrimState() }

/**
 * The dimmed, tap-blocking backdrop. Sibling order matters: the card is
 * composed *after* this so its own controls see their taps first, and only the
 * taps the card leaves unconsumed reach the detector below.
 *
 * [onDismiss] runs only for a tap the card does not cover. [color] defaults to
 * the theme scrim; the mobile sheets pass `MobileColors.scrim`, which is the
 * same backdrop under the form tokens.
 *
 * **Exit gating.** Most callers unmount the moment they dismiss ([open] flips to
 * false and the `?.let` / `if` drops the whole surface), so there is no exit
 * window and [closing] stays false. The animated sheet
 * (`SessionBottomSheet`, whose `AnimatedVisibility(visible = open)` keeps the
 * scrim mounted through its exit) passes `closing = !open`: the detector is
 * then **removed from the modifier chain** while the exit plays — the same
 * structural fix [com.xiufeigo.opencodeshell.ui.overlay.DialogHost] applies to
 * its own scrim
 * ([com.xiufeigo.opencodeshell.ui.overlay.DialogLayerPolicy.acceptsPointerInput]).
 * A detector that merely *returns early* while closing would still record the
 * hit (`pointerInput` never shares with siblings) and starve the screen behind
 * the fading backdrop.
 *
 * **Accessibility.** While [closing], the backdrop also hides itself from the
 * semantics tree (`clearAndSetSemantics {}` — the BOM-pinned Compose has no
 * `hideFromAccessibility` yet): a closing layer is a picture, not a control,
 * and a TalkBack focus that landed on it mid-exit must not survive to receive
 * a stale double-tap after the surface is conceptually gone.
 */
@Composable
fun DialogScrim(
    state: DialogScrimState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = OcTheme.colors.overlayScrim,
    /** `true` while the owning surface plays its exit — detector off, semantics hidden. */
    closing: Boolean = false,
) {
    // The detector is created once and reads the callback through
    // `rememberUpdatedState`: a restarted `pointerInput` would drop the
    // in-flight gesture. While closing the detector is replaced by an empty
    // semantics merge: the node stops taking pointer hits (a mounted
    // `pointerInput` would still record one even with a no-op block) *and*
    // hides from the accessibility tree, so a TalkBack focus that landed
    // mid-exit cannot be double-tapped after the surface is conceptually gone.
    val dismiss by rememberUpdatedState(onDismiss)
    Box(
        modifier
            .fillMaxSize()
            .background(color)
            .onGloballyPositioned { state.scrimOrigin = it.positionInRoot() }
            .then(
                if (DialogLayerPolicy.acceptsPointerInput(closing)) {
                    Modifier.pointerInput(state) {
                        detectTapGestures(onTap = { tap -> if (state.dismisses(tap)) dismiss() })
                    }
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            ),
    )
}

/**
 * Marks the card [state] guards — see the placement rule in [DialogScrim].
 *
 * The same marker serves a layer of [DialogHost] that draws its own card inside
 * a larger root: the host hands the layer its scrim state through
 * [LocalDialogScrimState], and the reported card then outranks the box the host
 * measured itself.
 */
fun Modifier.dialogCardBounds(state: DialogScrimState): Modifier =
    onGloballyPositioned { state.cardBounds = it.boundsInRoot() }

/**
 * v73 — **the session composer's box on screen**, published by the composer
 * (`ui/session/composer/Composer`) and read by the layers that have to treat a
 * tap there as the backdrop ([DialogScrimState.backdropBounds]).
 *
 * Why a shell-level box rather than a composition local: a dialog layer is a
 * *sibling* of the navigation tree ([DialogHost]), so a layer can never read
 * anything the session screen provides. The value is geometry, not state anyone
 * renders, and it is only ever compared against a tap point.
 *
 * The device case it exists for (5556 desktop, `research/v73_repro2`): with the
 * keyboard up on a short window (1366×768) the centered `/btw` card reached down
 * over the composer — the card's own box y 124…259 × x 443…922 against the
 * composer's y 230…334 × x 12…1353 — so a tap on the composer's strip, inside the
 * card's box, was answered "inside the card" by the scrim and closed nothing (the
 * tester's 「获焦但卡不关」); without the keyboard the two never overlap (the card
 * sits at the viewport's center, the composer at its bottom), which is why the
 * tall phone viewport (5558) passed the same step.
 */
object SessionComposerBounds {

    /** The composer's box in root coordinates; null while no composer is composed. */
    var bounds: Rect? by mutableStateOf(null)
}
