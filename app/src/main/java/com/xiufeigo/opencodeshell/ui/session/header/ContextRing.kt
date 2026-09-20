package com.xiufeigo.opencodeshell.ui.session.header

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * The session context ring (`research/02` §2.2), drawn natively.
 *
 * Upstream is an SVG `ProgressCircleV2` (v2 appearance):
 * `size = 14`, `strokeWidth = 1.5`, `viewBox 0 0 14 14`, `center = 7`,
 * `radius = 7 - 1.5 / 2 = 6.25`, `circumference = 2πr ≈ 39.27`,
 * `offset = circumference * (1 - clamp(pct, 0, 100) / 100)`, root
 * `transform: rotate(-90deg)`, track `--v2-background-bg-layer-04`, progress
 * `--v2-icon-icon-base`, `transition: stroke-dashoffset .35s
 * cubic-bezier(.65,0,.35,1)`.
 *
 * Compose translation:
 * - `rotate(-90deg)` + `stroke-dashoffset` is exactly a `drawArc` starting at
 *   **-90°** whose sweep is `360° × progress` — no dash math needed;
 * - the stroke has **no** line cap upstream (butt), unlike the M0-era
 *   `ContextRingView` which used `ROUND` — so no cap is passed here either;
 * - the sweep is animated with the CSS duration/easing pair
 *   ([OcMotion.RING_MS] / [OcMotion.ringEasing]).
 *
 * There is deliberately **no "over 80 % turns red"** rule: upstream clamps the
 * percentage without any colour branch (`research/02` §2.2 "阈值变色：未找到").
 */
@Composable
fun ContextRing(progress: Float, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    val sweep by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = OcMotion.RING_MS, easing = OcMotion.ringEasing),
        label = "context-ring-sweep",
    )
    Canvas(modifier = modifier.size(RING_SIZE)) {
        val strokePx = RING_STROKE.toPx()
        // radius 6.25dp inside a 14dp box: the arc's centre line sits half a
        // stroke width away from the edge.
        val radius = size.minDimension / 2f - strokePx / 2f
        val style = Stroke(width = strokePx)
        drawCircle(color = c.layer04, radius = radius, style = style)
        if (sweep > 0f) {
            drawArc(
                color = c.iconBase,
                startAngle = START_ANGLE,
                sweepAngle = 360f * sweep,
                useCenter = false,
                style = style,
            )
        }
    }
}

/** `ProgressCircleV2` `size = 14` (§2.2). */
private val RING_SIZE = 14.dp

/** `strokeWidth = 1.5` (§2.2). */
private val RING_STROKE = 1.5.dp

/** `transform: rotate(-90deg)` applied to the SVG root, as an arc start angle. */
private const val START_ANGLE = -90f
