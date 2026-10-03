package com.xiufeigo.opencodeshell.ui.session.drawer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.overlay.DialogLayerPolicy
import com.xiufeigo.opencodeshell.ui.overlay.DialogScrim
import com.xiufeigo.opencodeshell.ui.overlay.dialogCardBounds
import com.xiufeigo.opencodeshell.ui.overlay.rememberDialogScrimState
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ocShadow
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Mobile session drawer as an upstream **bottom sheet**
 * (`_upstream/packages/app/src/shell/mobile-drawer.css:1-60`):
 *
 * | upstream | here |
 * |---|---|
 * | `[data-slot="mobile-drawer-overlay"]` `inset:0`, `overlay-scrim`, 240 ms fade in / 200 ms out (`:1-11,62-78`) | full-size scrim sibling, tap to dismiss |
 * | `[data-slot="mobile-drawer-content"]` `inset-inline:0; bottom:0`, `border-radius:16px 16px 0 0`, `max-height:min(75dvh,100dvh−safe-top−16px)`, `padding:0 12px max(12px,safe-bottom)`, `bg-deep`, `elevation-overlay` (`:13-31`) | bottom-aligned sheet, same radius/height/padding/tokens |
 * | `transform` 240 ms `cubic-bezier(.2,.8,.2,1)`, closing 200 ms (`:33-39`) | `slideInVertically`/`slideOutVertically` with the same curve+durations |
 * | `[data-slot="mobile-drawer-handle"]` 28 px row with a 32×4 radius-999 bar, `touch-action:none` (`:46-60`) | 28 dp row + 32×4 dp `borderStrong` bar, drag target for swipe-to-close |
 *
 * Swipe-down dismisses (upstream drags the sheet; the handle owns the gesture),
 * tap on the scrim dismisses, and `AnimatedVisibility` keeps the closing
 * animation alive because the sheet stays mounted with `open=false`.
 */
@Composable
fun SessionBottomSheet(
    open: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = OcTheme.colors
    val dark = c.isDarkSurface()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val configurationHeightDp = configuration.screenHeightDp
    // `env(safe-area-inset-top)` / `env(safe-area-inset-bottom)` of the upstream
    // `max-height` and `padding-bottom` calcs, in dp.
    val topInsetDp = with(density) {
        WindowInsets.statusBars.getTop(this).toDp().value.roundToInt()
    }
    val bottomInsetDp = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
        .value
        .roundToInt()
    val maxHeightDp = MobileForm.sheetMaxHeightDp(configurationHeightDp, topInsetDp)

    val shape = RoundedCornerShape(
        topStart = MobileForm.SHEET_RADIUS_DP.dp,
        topEnd = MobileForm.SHEET_RADIUS_DP.dp,
    )
    val scope = rememberCoroutineScope()
    // Live swipe offset while the handle is dragged; 0 restored on release.
    val dragOffset = remember { Animatable(0f) }
    var sheetHeightPx by remember { mutableStateOf(0) }
    LaunchedEffect(open) { if (!open) dragOffset.snapTo(0f) }
    val scrim = rememberDialogScrimState()

    Box(modifier = modifier.fillMaxSize()) {
        // --------------------------------------------------------------- scrim
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(animationSpec = tween(MobileForm.SHEET_ENTER_MS)),
            exit = fadeOut(animationSpec = tween(MobileForm.SHEET_EXIT_MS)),
        ) {
            // The backdrop swallows the taps meant for the session content
            // behind the sheet, and dismisses only outside the sheet below.
            // `closing = !open`: the exit keeps this mounted for ~200 ms, and
            // for that window the detector is off the chain (a mounted
            // `pointerInput` takes the hit even when its block does nothing)
            // and the backdrop hides from the semantics tree — the
            // `DialogHost` layers' exit-gating, applied to the one hand-rolled
            // surface that has a real exit window
            // ([DialogLayerPolicy.acceptsPointerInput]).
            DialogScrim(scrim, onDismissRequest, color = MobileColors.scrim, closing = !open)
        }

        // ---------------------------------------------------------------- sheet
        AnimatedVisibility(
            visible = open,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset { IntOffset(0, dragOffset.value.roundToInt()) },
            enter = slideInVertically(
                animationSpec = tween(
                    durationMillis = MobileForm.SHEET_ENTER_MS,
                    easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f),
                ),
                initialOffsetY = { it },
            ),
            exit = slideOutVertically(
                animationSpec = tween(
                    durationMillis = MobileForm.SHEET_EXIT_MS,
                    easing = CubicBezierEasing(0.4f, 0f, 1f, 1f),
                ),
                targetOffsetY = { it },
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeightDp.dp)
                    .clip(shape)
                    .background(c.bgDeep)
                    .ocShadow(OcTheme.elevation.overlay, shape)
                    .onSizeChanged { sheetHeightPx = it.height }
                    .dialogCardBounds(scrim)
                    // A closing sheet is a picture, not a control: hide the
                    // card from the semantics tree for the exit window so a
                    // TalkBack focus that landed mid-exit cannot be double-
                    // tapped after the surface is conceptually gone (the
                    // BOM-pinned Compose has no `hideFromAccessibility` yet;
                    // the `DialogHost` layers' exit-gating, applied to the one
                    // hand-rolled surface with a real exit window).
                    .then(
                        if (DialogLayerPolicy.isHitTransparent(!open)) {
                            Modifier.clearAndSetSemantics { }
                        } else {
                            Modifier
                        },
                    )
                    .padding(
                        start = MobileForm.SHEET_SIDE_PADDING_DP.dp,
                        end = MobileForm.SHEET_SIDE_PADDING_DP.dp,
                        bottom = maxOf(MobileForm.SHEET_BOTTOM_PADDING_DP, bottomInsetDp).dp,
                    ),
            ) {
                // ------------------------------------------------------- handle
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MobileForm.SHEET_HANDLE_HEIGHT_DP.dp)
                        .pointerInput(Unit) {
                            var dragged = 0f
                            detectVerticalDragGestures(
                                onDragStart = { dragged = 0f },
                                onDragEnd = {
                                    val heightDp = with(density) { sheetHeightPx.toDp().value }
                                    if (MobileForm.shouldDismissByDrag(
                                            dragDownDp = with(density) { dragged.toDp().value },
                                            sheetHeightDp = heightDp.roundToInt(),
                                        )
                                    ) {
                                        onDismissRequest()
                                    } else {
                                        scope.launch { dragOffset.animateTo(0f, tween(MobileForm.SHEET_EXIT_MS)) }
                                    }
                                    dragged = 0f
                                },
                                onDragCancel = {
                                    dragged = 0f
                                    scope.launch { dragOffset.animateTo(0f, tween(MobileForm.SHEET_EXIT_MS)) }
                                },
                                onVerticalDrag = { change, dy ->
                                    change.consume()
                                    dragged = (dragged + dy).coerceAtLeast(0f)
                                    scope.launch { dragOffset.snapTo(dragged) }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(MobileForm.SHEET_HANDLE_BAR_WIDTH_DP.dp)
                            .height(MobileForm.SHEET_HANDLE_BAR_HEIGHT_DP.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MobileColors.strong(dark)),
                    )
                }
                content()
            }
        }
    }
}
