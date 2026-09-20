package com.xiufeigo.opencodeshell.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.settings.SettingsIcon

/**
 * The handful of sprite icons the M4 surfaces need, drawn directly with Compose
 * `Canvas` instead of shipping a vector-asset set. Semantics come from the
 * official SVG sprite names
 * (`Icon name="sliders|keyboard|server|providers|models|magnifying-glass|dots|plus"`).
 */
@Composable
fun OcIcon(icon: SettingsIcon, tint: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier.size(size)) { drawSettingsIcon(icon, tint) }
}

private fun DrawScope.drawSettingsIcon(icon: SettingsIcon, tint: Color) {
    val s = this.size.minDimension
    val stroke = Stroke(width = s * 0.09f, cap = StrokeCap.Round)
    val w = stroke.width
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    fun radius(r: Float) = CornerRadius(s * r)

    when (icon) {
        // sliders — two rails with offset knobs.
        SettingsIcon.SLIDERS -> {
            drawLine(tint, p(0.12f, 0.32f), p(0.88f, 0.32f), w, StrokeCap.Round)
            drawLine(tint, p(0.12f, 0.68f), p(0.88f, 0.68f), w, StrokeCap.Round)
            drawCircle(tint, s * 0.09f, p(0.34f, 0.32f))
            drawCircle(tint, s * 0.09f, p(0.66f, 0.68f))
        }
        // keyboard — rounded body + key dots.
        SettingsIcon.KEYBOARD -> {
            drawRoundRect(
                color = tint,
                topLeft = p(0.08f, 0.28f),
                size = Size(s * 0.84f, s * 0.44f),
                cornerRadius = radius(0.09f),
                style = stroke,
            )
            drawCircle(tint, s * 0.045f, p(0.28f, 0.5f))
            drawCircle(tint, s * 0.045f, p(0.44f, 0.5f))
            drawCircle(tint, s * 0.045f, p(0.6f, 0.5f))
            drawCircle(tint, s * 0.045f, p(0.72f, 0.5f))
            drawLine(tint, p(0.32f, 0.62f), p(0.68f, 0.62f), w, StrokeCap.Round)
        }
        // server — two stacked racks.
        SettingsIcon.SERVER -> {
            for (i in 0..1) {
                val top = 0.16f + i * 0.36f
                drawRoundRect(
                    color = tint,
                    topLeft = p(0.14f, top),
                    size = Size(s * 0.72f, s * 0.28f),
                    cornerRadius = radius(0.06f),
                    style = stroke,
                )
                drawCircle(tint, s * 0.045f, p(0.28f, top + 0.14f))
            }
        }
        // providers — a plug body with two blades.
        SettingsIcon.PROVIDERS -> {
            drawRoundRect(
                color = tint,
                topLeft = p(0.16f, 0.42f),
                size = Size(s * 0.68f, s * 0.44f),
                cornerRadius = radius(0.1f),
                style = stroke,
            )
            drawLine(tint, p(0.36f, 0.18f), p(0.36f, 0.42f), w, StrokeCap.Round)
            drawLine(tint, p(0.64f, 0.18f), p(0.64f, 0.42f), w, StrokeCap.Round)
        }
        // models — stacked layers.
        SettingsIcon.MODELS -> {
            val top = Path().apply {
                moveTo(s * 0.5f, s * 0.12f)
                lineTo(s * 0.9f, s * 0.34f)
                lineTo(s * 0.5f, s * 0.56f)
                lineTo(s * 0.1f, s * 0.34f)
                close()
            }
            drawPath(top, tint, style = stroke)
            drawPath(
                Path().apply {
                    moveTo(s * 0.1f, s * 0.52f)
                    lineTo(s * 0.5f, s * 0.74f)
                    lineTo(s * 0.9f, s * 0.52f)
                },
                tint,
                style = stroke,
            )
            drawPath(
                Path().apply {
                    moveTo(s * 0.1f, s * 0.68f)
                    lineTo(s * 0.5f, s * 0.9f)
                    lineTo(s * 0.9f, s * 0.68f)
                },
                tint,
                style = stroke,
            )
        }
    }
}

/** `Icon name="magnifying-glass"` — the palette search field's leading icon. */
@Composable
fun OcSearchIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = Stroke(width = s * 0.11f, cap = StrokeCap.Round)
        drawCircle(tint, s * 0.3f, Offset(s * 0.44f, s * 0.44f), style = stroke)
        drawLine(tint, Offset(s * 0.66f, s * 0.66f), Offset(s * 0.9f, s * 0.9f), stroke.width, StrokeCap.Round)
    }
}

/** `Icon name="dots"` — the `ServerRowMenu` trigger. */
@Composable
fun OcDotsIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        for (y in listOf(0.25f, 0.5f, 0.75f)) drawCircle(tint, s * 0.075f, Offset(s * 0.5f, s * y))
    }
}

/** `Icon name="plus"` — `AddServerMenu` trigger. */
@Composable
fun OcPlusIcon(tint: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = Stroke(width = s * 0.1f, cap = StrokeCap.Round)
        drawLine(tint, Offset(s * 0.5f, s * 0.16f), Offset(s * 0.5f, s * 0.84f), stroke.width, StrokeCap.Round)
        drawLine(tint, Offset(s * 0.16f, s * 0.5f), Offset(s * 0.84f, s * 0.5f), stroke.width, StrokeCap.Round)
    }
}
