package com.xiufeigo.opencodeshell.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import com.xiufeigo.opencodeshell.ui.theme.LocalOcReducedMotion
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * **M6** adds the layer transition (`research/03` §3.4): the content fades and
 * scales `contentShow`/`contentHide`-style (`DIALOG`: `.15s ease-out` in,
 * `.1s ease-in` out, scale `.98 → 1`) while the scrim only fades
 * (`overlayShow` / `overlayHide`). Because a closing layer must stay mounted for
 * its exit to play, [DialogController.close] marks the entry and the host
 * removes it after [OcMotion.exitHoldMs] — the stack is not emptied instantly
 * any more, which also means `LocalDialogActive` stays true for those ~100 ms
 * (the global keybinds stay suppressed across the animation, as they should).
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

    fun show(size: DialogSize = DialogSize.FIT, content: @Composable (DialogController) -> Unit) {
        stack.add(DialogEntry(nextId(), size, content))
    }

    /** Closes the topmost layer, playing its exit transition first. */
    fun close() {
        val top = stack.lastOrNull() ?: return
        if (top.closing) return
        top.closing = true
        beginClose(top)
    }

    /** Closes every layer (upstream `ctx.show` clears the stack before mounting). */
    fun closeAll() {
        stack.clear()
    }
}

/** Ambient controller; null outside [DialogHost] (previews). */
val LocalDialogController = compositionLocalOf<DialogController?> { null }

/** True while a layer is open — the keybind hook suppresses palette chords. */
val LocalDialogActive = compositionLocalOf { false }

/**
 * Hosts the stack. Wrap the navigation tree in [content]; anything inside can
 * reach the controller through [LocalDialogController] / [requireDialogController].
 */
@Composable
fun DialogHost(content: @Composable () -> Unit) {
    val stack = remember { mutableStateListOf<DialogEntry>() }
    val ids = remember { intArrayOf(0) }
    val scope = rememberCoroutineScope()
    val reduced = LocalOcReducedMotion.current
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
        Box(Modifier.fillMaxSize()) {
            content()

            val scrim = OcTheme.colors.overlayScrim
            // Layers render oldest → newest; each paints its own scrim and
            // swallows taps outside the dialog content (upstream layer has
            // `pointer-events:none` with an interactive child).
            for (entry in stack) {
                DialogLayer(
                    entry = entry,
                    controller = controller,
                    scrim = scrim,
                )
            }
        }
    }
}

@Composable
private fun DialogLayer(
    entry: DialogEntry,
    controller: DialogController,
    scrim: Color,
) {
    val reduced = LocalOcReducedMotion.current
    val overlay = overlayFor(entry.size)
    val enter = OcMotion.enterSpec(overlay, reduced)
    val exit = OcMotion.exitSpec(overlay, reduced)
    // Starts hidden so the layer animates in on first composition, and flips to
    // `false` (playing the exit) when the controller marks it closing.
    val visible = remember(entry.id) { MutableTransitionState(false) }
    visible.targetState = !entry.closing

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    controller.close()
                    true
                } else {
                    false
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
                    .pointerInput(entry.id) {
                        detectTapGestures(onTap = { controller.close() })
                    },
            )
        }
        AnimatedVisibility(
            visibleState = visible,
            enter = fadeIn(enter) + scaleIn(enter, initialScale = overlay.startScale),
            exit = fadeOut(exit) + scaleOut(exit, targetScale = overlay.startScale),
            modifier = Modifier.fillMaxSize(),
            label = "dialog-content",
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                entry.content(controller)
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
