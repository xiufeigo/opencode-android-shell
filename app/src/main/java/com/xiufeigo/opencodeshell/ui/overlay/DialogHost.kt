package com.xiufeigo.opencodeshell.ui.overlay

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import com.xiufeigo.opencodeshell.ui.theme.LocalOcReducedMotion
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** logcat tag for layer close reasons — the diagnostics for a self-close repro. */
private const val TAG = "DialogHost"

/**
 * The native equivalent of upstream's global dialog stack.
 *
 * Verified host chain (`research/05_palette_host_session.md` §1.2):
 * `app.tsx` → `<DialogProvider>` (`packages/ui/src/context/dialog.tsx`) →
 * `<div data-component="dialog-stack">` → per-layer
 * `data-dialog-layer=N` with `position:fixed; inset:0; display:flex;
 * align-items:center; justify-content:center`. Pages never render the Settings
 * dialog or the command palette in their own JSX tree — they call
 * `dialog.show(...)` and a layer is pushed onto the stack.
 *
 * [DialogHost] reproduces that: it wraps the whole app, provides
 * [LocalDialogController], and renders every pushed layer above the navigation
 * content, so Home / session / new-session all open the *same* instances without
 * a route change (PLAN §4.3/§4.4).
 *
 * **Not every overlay is a dialog upstream**: `/settings` is a real route with
 * its own full-screen surface (`shell/routes/routes.tsx:38` →
 * `settings/shell.tsx:70`, `settings.css:8-27`). The native shell keeps the route
 * as a deep-link entry and renders the surface through this stack, which is the
 * same layer the palette and the server dialogs use — a structural deviation
 * recorded in `audit_routes.md` 表 A, not an upstream fact.
 *
 * **M6** adds the layer transition (`research/03` §3.4): the content fades and
 * scales `contentShow`/`contentHide`-style (`DIALOG`: `.15s ease-out` in,
 * `.1s ease-in` out, scale `.98 → 1`) while the scrim only fades
 * (`overlayShow` / `overlayHide`). Because a closing layer must stay mounted for
 * its exit to play, [DialogController.close] marks the entry and the host
 * removes it after [OcMotion.exitHoldMs] — the stack is not emptied instantly
 * any more, which also means `LocalDialogActive` stays true for those ~100 ms
 * (the global keybinds stay suppressed across the animation, as they should).
 * The lingering layer takes no input while it plays out: it **leaves the hit
 * path entirely** ([DialogLayerPolicy]) — the scrim's tap detector is removed
 * from the modifier chain and the content is displaced off the viewport (drawn
 * back by the exit transition) — so the first tap after a close reaches the
 * screen the user closed the dialog to get back to instead of dying on the
 * closing card.
 *
 * **Dismissal** is the controller's, never a dialog's own: the scrim tap
 * (outside the content box only, [scrimTapDismisses]), Escape
 * ([DialogEscape] — the IME keeps the key while it is up) and BACK
 * ([BackHandler] below, composed last so it outranks the nav tree's handlers).
 */
class DialogEntry internal constructor(
    val id: Int,
    /** `dialog-v2` `data-size` (`fit` / `large` / `x-large`). */
    val size: DialogSize,
    val content: @Composable (DialogController) -> Unit,
) {
    /** Set by [DialogController.close]; the host keeps the layer for the exit. */
    var closing by mutableStateOf(false)
        internal set
}

/** `data-size` values M4 uses; drives the shell width/height. */
enum class DialogSize { FIT, LARGE, X_LARGE }

/**
 * Handle handed to screens so Home/session/new-session can push the Settings
 * dialog and the command palette (`dialog.show {...}` semantics).
 */
class DialogController internal constructor(
    private val stack: SnapshotStateList<DialogEntry>,
    private val nextId: () -> Int,
    /**
     * Starts the exit of [entry]: the host marks it closing, lets the transition
     * play and only then removes it. Defaults to an immediate removal so a
     * controller built outside [DialogHost] (tests, previews) still works.
     */
    private val beginClose: (DialogEntry) -> Unit = { stack.remove(it) },
) {
    /** True when any layer is open — the global keybind hook must stand down. */
    val isActive: Boolean get() = stack.isNotEmpty()

    /**
     * Upstream `dialog.show` **replaces** the whole stack before mounting the new
     * layer (`packages/ui/src/context/dialog.tsx:140-149`: it clears the layers
     * and pushes one); `dialog.push` (`:131-138`) is what stacks. The earlier
     * Android `show` behaved like `push`, so any upstream chain that relies on
     * replacing the current dialog (e.g. `usage-exceeded-dialogs.tsx:86`) stacked
     * instead of switching. Layers are closed the same way [close] does — marked
     * closing so their exit animation plays — and the new one is appended after
     * them, so it is on top from the first frame.
     */
    fun show(size: DialogSize = DialogSize.FIT, content: @Composable (DialogController) -> Unit) {
        val replaced = stack.count { !it.closing }
        if (replaced > 0) {
            Log.d(TAG, "layer dismissed by show() replace count=$replaced", Throwable("dialog show"))
        }
        for (entry in stack.toList()) {
            if (entry.closing) continue
            entry.closing = true
            beginClose(entry)
        }
        stack.add(DialogEntry(nextId(), size, content))
    }

    /** Closes the topmost layer, playing its exit transition first. */
    fun close() {
        val top = stack.lastOrNull { !it.closing } ?: return
        Log.d(TAG, "layer close", Throwable("dialog close"))
        top.closing = true
        beginClose(top)
    }

    /** Closes every layer (upstream `ctx.show` clears the stack before mounting). */
    fun closeAll() {
        Log.d(TAG, "layer close-all size=${stack.size}", Throwable("dialog closeAll"))
        stack.clear()
    }
}

/** Ambient controller; null outside [DialogHost] (previews). */
val LocalDialogController = compositionLocalOf<DialogController?> { null }

/** True while a layer is open — the keybind hook suppresses palette chords. */
val LocalDialogActive = compositionLocalOf { false }

/**
 * The scrim state of the layer being composed — provided by [DialogHost] around
 * every layer's content, so a layer can report the card its own backdrop guards
 * ([dialogCardBounds]) when that card is not the layer's root box: the `/btw`
 * panel pads its root by the safe-area insets, and the host's own measurement
 * of that root then covers the keyboard strip and the composer behind it (v71).
 *
 * Null outside a layer (previews), like [LocalDialogController].
 */
val LocalDialogScrimState = compositionLocalOf<DialogScrimState?> { null }

/**
 * Hosts the stack. Wrap the navigation tree in [content]; anything inside can
 * reach the controller through [LocalDialogController] / [requireDialogController].
 */
@Composable
fun DialogHost(content: @Composable () -> Unit) {
    val stack = remember { mutableStateListOf<DialogEntry>() }
    val ids = remember { intArrayOf(0) }
    val collapsedIme = remember { mutableStateMapOf<Int, Boolean>() }
    val scope = rememberCoroutineScope()
    val reduced = LocalOcReducedMotion.current
    val view = LocalView.current
    val controller = remember(reduced) {
        DialogController(
            stack = stack,
            nextId = { ids[0]++ },
            beginClose = { entry ->
                scope.launch {
                    delay(OcMotion.exitHoldMs(overlayFor(entry.size), reduced))
                    stack.remove(entry)
                }
            },
        )
    }

    CompositionLocalProvider(
        LocalDialogController provides controller,
        LocalDialogActive provides stack.isNotEmpty(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // ESC dismisses the top layer (`DialogEscape` — with the IME up
                // the key collapses the keyboard instead). The hook rides this
                // box because it is the one ancestor every focus path shares:
                // the layers and the navigation tree are siblings, so a hook
                // inside either branch never sees the keys aimed at the other —
                // which is why Escape did nothing for a dialog with no text
                // field (focus stayed on the nav tree) while the palette, which
                // autofocuses its search field, closed fine. The *bubble* pass
                // (`onKeyEvent`) is deliberate: an inner handler that owns
                // Escape mid-state — the palette's own close, the settings
                // keybind recorder's cancel — runs first and consumes it.
                .onKeyEvent { event ->
                    if (stack.isEmpty()) return@onKeyEvent false
                    val top = stack.lastOrNull { !it.closing } ?: return@onKeyEvent false
                    if (event.type != KeyEventType.KeyDown || event.key != Key.Escape) {
                        return@onKeyEvent false
                    }
                    if (DialogEscape.escapeDismissesLayer(
                            imeVisible = isImeVisible(view),
                            collapsedByEscape = collapsedIme[top.id] == true,
                        )
                    ) {
                        controller.close()
                        true
                    } else {
                        false
                    }
                },
        ) {
            content()

            val scrim = OcTheme.colors.overlayScrim
            // Layers render oldest → newest; each paints its own scrim and
            // swallows taps outside the dialog content (upstream layer has
            // `pointer-events:none` with an interactive child).
            //
            // v54 (`research/v54_verify/tB/tB_71_palette_redo.png` →
            // `tB_73_after_redo2.png`): `key(entry.id)` is load-bearing, not
            // decoration. Without it every iteration of this loop composes in
            // the *same* group, so Compose reuses one slot per **position** —
            // and the stack shrinks all the time, a dismissed layer leaving
            // `exitHoldMs` later. The layer that slid into the freed slot was
            // therefore rebuilt on the dismissed layer's remembered state: the
            // palette reopened with the previous open's query still in the
            // search field ("redo" typed, Redo run, palette reopened ⇒
            // "redoredo"), and every other layer inherited the previous one's
            // scroll offsets, focus and in-flight effects. Keying by the entry
            // id gives each layer its own slot, so a new layer is always a new
            // composition.
            for (entry in stack) {
                key(entry.id) {
                    DialogLayer(
                        entry = entry,
                        controller = controller,
                        scrim = scrim,
                        onImeCollapsed = { collapsedIme[entry.id] = true },
                        imeCollapsed = { collapsedIme[entry.id] == true },
                    )
                }
            }
        }
        // BACK dismisses the top layer — `DialogController` owns back dismissal,
        // so pointer-only dialogs (`DialogReleaseNotes`) keep a hardware path
        // beside Escape and the scrim tap. Composed after the
        // layers so it outranks nav / screen back handlers while a layer is up.
        // Enabled only while an *open* layer owns the key: dismissed layers stay
        // mounted for their exit transition, and gating on the bare stack would
        // swallow one BACK on the screen the user closed the dialog to get back
        // to ([DialogLayerPolicy.hasOpenLayer]).
        BackHandler(enabled = DialogLayerPolicy.hasOpenLayer(stack.map { it.closing })) {
            controller.close()
        }
    }
}

@Composable
private fun DialogLayer(
    entry: DialogEntry,
    controller: DialogController,
    scrim: Color,
    onImeCollapsed: () -> Unit,
    imeCollapsed: () -> Boolean,
) {
    val reduced = LocalOcReducedMotion.current
    val overlay = overlayFor(entry.size)
    val enter = OcMotion.enterSpec(overlay, reduced)
    val exit = OcMotion.exitSpec(overlay, reduced)
    // Starts hidden so the layer animates in on first composition, and flips to
    // `false` (playing the exit) when the controller marks it closing.
    val visible = remember(entry.id) { MutableTransitionState(false) }
    visible.targetState = !entry.closing

    val view = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    // The card this layer's scrim test reads, shared with the layer itself
    // through [LocalDialogScrimState]: the host measures the layer's root — the
    // card for every layer whose content is its own box — and a layer whose root
    // is bigger than its card reports the card instead ([dialogCardBounds]) and
    // wins. v71: the `/btw` panel pads its root by the safe-area insets, so the
    // measured box held the keyboard strip *and* the composer behind the card,
    // and the tap on that composer was consumed without dismissing anything.
    val cardState = remember(entry.id) { DialogScrimState() }
    // Measured layer viewport (px) — the fixed shells size from
    // `LocalConfiguration`, which can overshoot this in a small window and
    // leave a tall body (e.g. the Pairing QR) spilling past the window edge.
    var viewportPx by remember(entry.id) { mutableStateOf(IntSize.Zero) }
    // Full content size (px), tracked on the *inner* box so it stays the true
    // height while the fallback cap + scroll is engaged — measuring the
    // capped box instead would flap the fallback every frame.
    var fullContentPx by remember(entry.id) { mutableStateOf(IntSize.Zero) }
    val fallbackScroll = remember(entry.id) { ScrollState(initial = 0) }
    // Engagement is strictly past the physical edge (`fillMaxSize` roots are
    // exactly viewport-sized, so they can never trip it): every fitting layer
    // keeps byte-identical geometry, only a genuinely overflowing layer gets
    // capped + scrollable.
    val overflowing = DialogViewportFit.overflows(fullContentPx.height, viewportPx.height) ||
        DialogViewportFit.overflows(fullContentPx.width, viewportPx.width)
    // Displacement that carries a closing layer's content clear of every
    // on-screen pointer, so the card's own nodes cannot take the hit the user
    // aimed at the screen behind ([DialogLayerPolicy.ghostOffsetPx]).
    val ghostPx = DialogLayerPolicy.ghostOffsetPx(
        closing = entry.closing,
        viewportPx = viewportPx.height,
        contentPx = fullContentPx.height,
    )

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { viewportPx = it.size }
            .onPreviewKeyEvent { event ->
                // Escape's *IME* half only (`DialogEscape`): while typing the
                // key collapses the keyboard and is consumed so nothing behind
                // the field sees it; with the keyboard down this returns false
                // and the dismissal falls through to the host's own handler.
                // v61: once this layer's keyboard has already been collapsed by
                // an Escape, the next Escape is the dismissal — consume it here
                // again and the host's handler never gets asked (the phone
                // form of 「ESC 关不掉命令面板」).
                collapseImeOnEscape(
                    event = event,
                    imeVisible = isImeVisible(view),
                    collapsedByEscape = imeCollapsed(),
                ) {
                    onImeCollapsed()
                    keyboard?.hide()
                }
            },
    ) {
        // `overlayShow` / `overlayHide`: the scrim only fades — scaling it would
        // drag the dimmed backdrop away from the screen edges.
        AnimatedVisibility(
            visibleState = visible,
            enter = fadeIn(enter),
            exit = fadeOut(exit),
            modifier = Modifier.fillMaxSize(),
            label = "dialog-scrim",
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(scrim)
                    .onGloballyPositioned { cardState.scrimOrigin = it.positionInRoot() }
                    // The tap detector is **removed from the chain** while the
                    // layer plays its exit, not left inert: a `pointerInput`
                    // node is a hit-test target even after its block has
                    // returned, and the hit walk stops at the first sibling
                    // that records one — so an inert detector still starved the
                    // screen behind the closing layer, which is exactly the
                    // first click after a close that did nothing
                    // ([DialogLayerPolicy.acceptsPointerInput]).
                    .then(
                        if (DialogLayerPolicy.acceptsPointerInput(entry.closing)) {
                            Modifier.pointerInput(entry.id) {
                                detectTapGestures(onTap = { local ->
                                    if (cardState.dismisses(local)) {
                                        controller.close()
                                    }
                                })
                            }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
        AnimatedVisibility(
            visibleState = visible,
            enter = fadeIn(enter) + scaleIn(enter, initialScale = overlay.startScale),
            exit = fadeOut(exit) + scaleOut(exit, targetScale = overlay.startScale),
            modifier = Modifier.fillMaxSize(),
            label = "dialog-content",
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    // A closing card is a picture, not a control. Its own
                    // pointer nodes (a scroll body, a row button) record a hit
                    // and the walk stops there, so a tap the user aimed at the
                    // screen behind died on the card (真机:
                    // 「弹层退出动画卡片可吞一击」). Displacing the content off
                    // the viewport takes it out of the walk entirely — the walk
                    // maps through the layer's transform — and drawing it back
                    // cancels the displacement, so the exit transition plays
                    // exactly as before ([DialogLayerPolicy.isHitTransparent]).
                    .then(
                        if (DialogLayerPolicy.isHitTransparent(entry.closing)) {
                            Modifier
                                .graphicsLayer { translationY = ghostPx.toFloat() }
                                .drawWithContent {
                                    val drawn = this
                                    translate(top = -ghostPx.toFloat()) { drawn.drawContent() }
                                }
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // Upstream `dialog-content` is `max-height: 100%` + `overflow:
                // auto`: while everything fits this is the plain centered box
                // it always was; past the viewport edge the content is capped
                // to the measured viewport and scrolls, so a tall body (the
                // Pairing QR on a small window) stays reachable instead of
                // spilling past the window and reading as clipped.
                Box(
                    Modifier
                        .then(
                            if (overflowing) {
                                Modifier
                                    .widthIn(
                                        max = DialogViewportFit.maxWidth(
                                            with(density) { viewportPx.width.toDp() },
                                        ),
                                    )
                                    .heightIn(
                                        max = DialogViewportFit.maxHeight(
                                            with(density) { viewportPx.height.toDp() },
                                        ),
                                    )
                                    .verticalScroll(fallbackScroll)
                            } else {
                                Modifier
                            },
                        )
                        .onGloballyPositioned { cardState.contentBounds = it.boundsInRoot() }
                        // The layer reads as one group to accessibility services
                        // (`uiautomator dump` / TalkBack): the card's texts and
                        // controls belong to the dialog, not to the screen it
                        // covers, so traversal stays inside the layer while it is
                        // up instead of walking the page behind it. A closing
                        // layer is a picture, not a control: the exit branch
                        // hides it from the tree (the BOM-pinned Compose has no
                        // `hideFromAccessibility` yet), so a TalkBack focus that
                        // landed mid-exit cannot be double-tapped after the
                        // surface is conceptually gone.
                        .then(
                            if (DialogLayerPolicy.isHitTransparent(entry.closing)) {
                                Modifier.clearAndSetSemantics { }
                            } else {
                                Modifier.semantics { isTraversalGroup = true }
                            },
                        ),
                ) {
                    Box(Modifier.onGloballyPositioned { fullContentPx = it.size }) {
                        // The layer's own card report ([dialogCardBounds]) rides
                        // this state, so the scrim test above can read a card
                        // that is smaller than the layer's root.
                        CompositionLocalProvider(LocalDialogScrimState provides cardState) {
                            entry.content(controller)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Motion family of a layer. Upstream ships two dialog-v2 rhythms: the
 * `command-palette-v2` shell (`DialogSize.LARGE`) transitions at `120ms`, the
 * settings dialog (`.15s ease-out` / `.1s ease-in`) at 150/100 — so the size is
 * what picks the spec, and no layer plays two transitions at once.
 */
private fun overlayFor(size: DialogSize): OcMotion.Overlay = when (size) {
    DialogSize.LARGE -> OcMotion.Overlay.PALETTE
    DialogSize.FIT, DialogSize.X_LARGE -> OcMotion.Overlay.DIALOG
}

/** Reads the ambient controller or throws — screens inside [DialogHost] only. */
@Composable
fun requireDialogController(): DialogController =
    LocalDialogController.current ?: error("DialogHost is missing above this composable")
