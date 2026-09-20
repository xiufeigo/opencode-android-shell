package com.xiufeigo.opencodeshell.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.theme.LocalOcReducedMotion
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlin.random.Random

/**
 * One dot of the 4×4 `spinner` matrix: grid coordinates in the `0 0 15 15`
 * viewBox plus the random pulse timing (`research/03` §2.4, `research/07` §3).
 */
@Immutable
data class SpinnerDot(
    val index: Int,
    val x: Float,
    val y: Float,
    val delayMs: Int,
    val durationMs: Int,
) {
    val kind: OcMotion.Spinner.Kind get() = OcMotion.Spinner.kindOf(index)
}

/**
 * Pure model of the dot matrix — the port of the bundle's
 * `NMe = Array.from({length:16}, (e,t) => ({x: t%4*4, y: floor(t/4)*4,
 * delay: Math.random()*1.5, duration: 1 + Math.random()*1, ...}))`.
 *
 * `research/07` §3 confirms there is **no seed** and that the table is a
 * module-level `const`, i.e. generated once per page load and shared by every
 * spinner. [Default] reproduces that: one table for the process. A seed can be
 * injected through [dots] for screenshot tests, where a moving picture is worse
 * than a fixed one.
 */
object SpinnerModel {

    /** The bundle's module-level table: one random draw per process. */
    val Default: List<SpinnerDot> by lazy { dots(Random.Default) }

    /** `delay = rand * 1.5 s`, `duration = 1 s + rand * 1 s`. */
    fun dots(random: Random): List<SpinnerDot> = List(OcMotion.Spinner.COUNT) { index ->
        SpinnerDot(
            index = index,
            x = (index % 4) * OcMotion.Spinner.GRID_PITCH,
            y = (index / 4) * OcMotion.Spinner.GRID_PITCH,
            delayMs = (random.nextFloat() * OcMotion.Spinner.MAX_DELAY_MS).toInt(),
            durationMs = OcMotion.Spinner.MIN_DURATION_MS +
                (random.nextFloat() * (OcMotion.Spinner.MAX_DURATION_MS - OcMotion.Spinner.MIN_DURATION_MS)).toInt(),
        )
    }

    /** Frozen pose for reduced motion: the bright end of each dot's range. */
    fun staticAlpha(kind: OcMotion.Spinner.Kind): Float = when (kind) {
        OcMotion.Spinner.Kind.OUTER -> OcMotion.Spinner.REDUCED_OUTER_ALPHA
        OcMotion.Spinner.Kind.INNER -> OcMotion.Spinner.REDUCED_INNER_ALPHA
        OcMotion.Spinner.Kind.CORNER -> 0f
    }
}

/**
 * The 4×4 pixel-dot spinner (`research/03` §2.4) — **not** a rotating ring.
 *
 * Sixteen `3×3 rx=1` squares on a `4`-pitch grid inside a `15×15` viewBox; the
 * four corners `{0,3,12,15}` are dead (`opacity: 0`), the outer ring
 * `{1,2,4,7,8,11,13,14}` breathes `.15 ↔ .35` (`pulse-opacity-dim`) and the
 * inner 2×2 `{5,6,9,10}` breathes `.4 ↔ 1` (`pulse-opacity`). Each dot gets its
 * own random `duration` (1–2 s) and `delay` (0–1.5 s) with `ease-in-out` and
 * `RepeatMode.Reverse`, which is what produces the "gritty twinkle" the web has.
 *
 * Default size is the tool-card 16×16 (`research/03` §3.5).
 */
@Composable
fun SpinnerDots(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    color: Color = OcTheme.colors.textFaint,
    dots: List<SpinnerDot> = SpinnerModel.Default,
) {
    val reduced = LocalOcReducedMotion.current
    if (reduced) {
        // `@media (prefers-reduced-motion:reduce)` freezes every animation: draw
        // the matrix in a single static pose instead of 16 infinite transitions.
        SpinnerCanvas(
            dots = dots,
            alphas = List(dots.size) { SpinnerModel.staticAlpha(dots[it].kind) },
            color = color,
            modifier = modifier.size(size),
        )
    } else {
        AnimatedSpinnerDots(dots = dots, color = color, modifier = modifier.size(size))
    }
}

@Composable
private fun AnimatedSpinnerDots(
    dots: List<SpinnerDot>,
    color: Color,
    modifier: Modifier,
) {
    val alphas = ArrayList<Float>(dots.size)
    for (dot in dots) {
        val range = OcMotion.Spinner.alphaRange(dot.kind)
        val transition = rememberInfiniteTransition(label = "spinner-dot-${dot.index}")
        val alpha by transition.animateFloat(
            initialValue = range.start,
            targetValue = range.endInclusive,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = dot.durationMs,
                    delayMillis = dot.delayMs,
                    easing = FastOutSlowInEasing,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "spinner-alpha-${dot.index}",
        )
        alphas += if (dot.kind == OcMotion.Spinner.Kind.CORNER) 0f else alpha
    }
    SpinnerCanvas(dots = dots, alphas = alphas, color = color, modifier = modifier)
}

@Composable
private fun SpinnerCanvas(
    dots: List<SpinnerDot>,
    alphas: List<Float>,
    color: Color,
    modifier: Modifier,
) {
    Canvas(modifier = modifier) {
        val scale = size.minDimension / OcMotion.Spinner.VIEW_BOX
        val dotSide = OcMotion.Spinner.DOT_SIZE * scale
        val radius = CornerRadius(OcMotion.Spinner.DOT_RADIUS * scale)
        for (index in dots.indices) {
            val dot = dots[index]
            if (dot.kind == OcMotion.Spinner.Kind.CORNER) continue
            val alpha = alphas.getOrElse(index) { 0f }
            if (alpha <= 0f) continue
            drawRoundRect(
                color = color.copy(alpha = color.alpha * alpha),
                topLeft = Offset(dot.x * scale, dot.y * scale),
                size = Size(dotSide, dotSide),
                cornerRadius = radius,
            )
        }
    }
}
