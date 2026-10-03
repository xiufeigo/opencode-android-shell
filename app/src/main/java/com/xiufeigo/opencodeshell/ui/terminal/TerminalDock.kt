package com.xiufeigo.opencodeshell.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.settings.LocalTimelineDisplayPrefs
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * B3 — the **docked terminal pane**, in either placement.
 *
 * Upstream mounts the terminal in one of two places depending on
 * `settings.general.terminalPlacement` (`session/screen-layout.ts:19-20`):
 * `side` puts it in the right side region, `bottom` under the timeline row, and
 * this composable is the single implementation of both. The two mounting points
 * live in `SessionScreen`'s desktop body — one inside the review `Row`, one under
 * it — and each asks for its own [TerminalPlacement]; only the slot the stored
 * setting names renders, so the setting can be flipped at runtime (it arrives
 * through [LocalTimelineDisplayPrefs], which `ui/App.kt` feeds from the store).
 *
 * Geometry (all of it pure and unit-tested in `TerminalLayout.kt`):
 *
 * ```
 * bottom: Column(height = size)      side: Row(width = size)
 * ├─ drag handle  (8 dp, top edge)   ├─ drag handle (8 dp, leading edge)
 * └─ EmbedddedTerminalPanel weight   └─ EmbeddedTerminalPanel weight
 * ```
 *
 * The handle is `min 100 dp`, `max 60%` of the viewport on its axis and closes
 * the pane when the drag passes the 50 dp collapse threshold — upstream's
 * `ResizeHandle` numbers (`session/screen.tsx:455-464,517-531`). The size is
 * remembered per session *and* per placement through `rememberSaveable`, and
 * before the first drag it is the 280 dp / 60% default the pre-B3 pane used, so
 * nothing regresses on the default path.
 *
 * @param placement which slot this call is; the pane renders only when the
 *   stored setting names it.
 * @param sessionId key of the remembered size (a session switch must not carry
 *   the previous session's split over) and the session whose bound terminals
 *   the panel can list/create/attach (`sessionTerminals` & friends).
 */
@Composable
fun TerminalDock(
    placement: TerminalPlacement,
    sessionId: String,
    directory: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** B-5: `terminal.new` — forwarded to the pane (see [TerminalPanelHost]). */
    newTabRequest: Int = 0,
) {
    val configuration = LocalConfiguration.current
    // `isDesktop()` gate + the setting, in one pure projection.
    val stored = LocalTimelineDisplayPrefs.current.terminalPlacement
    if (dockedPanePlacement(stored, configuration.screenWidthDp) != placement) return

    val viewportDp = when (placement) {
        TerminalPlacement.SIDE -> configuration.screenWidthDp
        TerminalPlacement.BOTTOM -> configuration.screenHeightDp
    }
    val sizes = rememberSaveable(saver = PaneSizesSaver) { TerminalPaneSizes() }
    val key = TerminalPaneSizes.key(sessionId, placement)
    // Re-derived (not merely re-read) when the viewport changes: a rotation
    // re-clamps the remembered size instead of dropping it.
    var sizeDp by remember(key, viewportDp) {
        mutableStateOf(TerminalPaneSize.restore(sizes[key], placement, viewportDp))
    }
    val handle: @Composable (Modifier) -> Unit = { handleModifier ->
        PaneResizeHandle(
            vertical = placement == TerminalPlacement.BOTTOM,
            sizeDp = sizeDp,
            viewportDp = viewportDp,
            onSizeChange = { sizeDp = it },
            onCollapse = {
                // Upstream collapses on pointer-up (`resize-handle.tsx:78-80`);
                // the seam is the same `terminalDocked` flag the chip toggles.
                sizes[key] = sizeDp
                onClose()
            },
            onDragSettled = { sizes[key] = sizeDp },
            onReset = {
                sizeDp = TerminalPaneSize.defaultDp(placement, viewportDp)
                sizes[key] = sizeDp
            },
            modifier = handleModifier,
        )
    }

    when (placement) {
        TerminalPlacement.BOTTOM -> Column(modifier.fillMaxWidth().height(sizeDp.dp)) {
            handle(Modifier.fillMaxWidth().height(PANE_HANDLE_DP.dp))
            EmbeddedTerminalPanel(
                directory = directory,
                sessionID = sessionId,
                newTabRequest = newTabRequest,
                onClose = onClose,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }

        TerminalPlacement.SIDE -> Row(modifier.fillMaxHeight().width(sizeDp.dp)) {
            handle(Modifier.fillMaxHeight().width(PANE_HANDLE_DP.dp))
            EmbeddedTerminalPanel(
                directory = directory,
                sessionID = sessionId,
                newTabRequest = newTabRequest,
                onClose = onClose,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

/**
 * The drag handle on the pane's splitting edge — the native counterpart of
 * `ResizeHandle` (`@opencode/ui/src/components/resize-handle.tsx`): the pointer
 * delta is accumulated as *growth* of the pane and clamped by
 * [TerminalPaneSize.drag] on every step, the collapse verdict is taken from the
 * unclamped value, and a double tap restores the default (the same affordance
 * the review sidebar's handle carries, `ReviewPanel.kt` `PanelResizeHandle`).
 */
@Composable
private fun PaneResizeHandle(
    vertical: Boolean,
    sizeDp: Int,
    viewportDp: Int,
    onSizeChange: (Int) -> Unit,
    onCollapse: () -> Unit,
    onDragSettled: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val description = stringResource(R.string.oc_terminal_resize_handle)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // The gesture block below restarts only when its keys change, so the sizes it
    // compares against have to be read through a state holder, not captured.
    val currentSize by rememberUpdatedState(sizeDp)
    var startDp by remember { mutableStateOf(0) }
    var growth by remember { mutableStateOf(0f) }
    var collapsed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .background(c.bgBase)
            .semantics { contentDescription = description }
            .pointerInput(vertical) { detectTapGestures(onDoubleTap = { onReset() }) }
            .pointerInput(vertical, viewportDp) {
                detectDragGestures(
                    onDragStart = {
                        startDp = currentSize
                        growth = 0f
                        collapsed = false
                    },
                    onDragCancel = { collapsed = false },
                    onDragEnd = {
                        if (collapsed) onCollapse() else onDragSettled()
                        collapsed = false
                    },
                ) { change, dragAmount ->
                    change.consume()
                    growth += TerminalPaneSize.growth(
                        deltaDp = if (vertical) dragAmount.y else dragAmount.x,
                        vertical = vertical,
                        rtl = rtl,
                    )
                    val step = TerminalPaneSize.drag(startDp, growth, viewportDp)
                    onSizeChange(step.sizeDp)
                    collapsed = step.collapsed
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The visible 1 px grab line, on the edge that splits the two regions.
        if (vertical) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderBase))
        } else {
            Box(Modifier.fillMaxHeight().width(1.dp).background(c.borderBase))
        }
    }
}

/** Upstream's handle is an `h-2` (8 px) overlay; the native one is a layout sibling. */
private const val PANE_HANDLE_DP = 8

/** `listSaver` over [TerminalPaneSizes.encoded] — both directions are pure. */
private val PaneSizesSaver = listSaver<TerminalPaneSizes, String>(
    save = { it.encoded() },
    restore = { TerminalPaneSizes.decode(it) },
)
