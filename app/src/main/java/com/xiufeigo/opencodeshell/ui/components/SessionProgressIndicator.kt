package com.xiufeigo.opencodeshell.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.theme.LocalOcReducedMotion
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * `session-progress-indicator-v2` (`research/03` §2.1) — the 25-dot marquee the
 * web shows on a session that is currently working.
 *
 * Geometry was read off the bundle (`index-CKBO9Mty.js` @1680971) instead of the
 * guess the research note had to leave open: `viewBox="0 0 16 16"`, 25
 * `<rect width=2 height=2>` on a **5×5** grid of pitch `3` starting at `1.5`, so
 * `x = 1.5 + i % 5 * 3`, `y = 1.5 + floor(i / 5) * 3`, and `data-dot="12"` is the
 * centre cell. `fill: currentColor` (`--v2-icon-icon-muted`, `#808080`), base
 * `opacity: .2`, `1.2 s ease-out infinite both`, and a dot-12 that is `opacity: 1`
 * in every one of its nine segments.
 *
 * Reduced motion (`SESSION_PROGRESS_DOT` + `SESSION_PROGRESS_CENTER`) freezes the
 * marquee: the centre dot stays lit and the rest sit at the `.2` floor.
 */
@Composable
fun SessionProgressIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    color: Color = OcTheme.colors.iconMuted,
) {
    val reduced = LocalOcReducedMotion.current
    val phase = if (reduced) 0f else progressPhase()
    val frozenFloor = reduced
    Canvas(modifier = modifier.size(size)) {
        val scale = this.size.minDimension / OcMotion.SessionProgress.VIEW_BOX
        val dotSide = OcMotion.SessionProgress.DOT_SIZE * scale
        for (index in 0 until OcMotion.SessionProgress.COUNT) {
            val alpha = if (frozenFloor && index != OcMotion.SessionProgress.CENTER) {
                OcMotion.SessionProgress.BASE_ALPHA
            } else {
                OcMotion.SessionProgress.opacity(index, phase)
            }
            drawRect(
                color = color.copy(alpha = color.alpha * alpha),
                topLeft = Offset(
                    OcMotion.SessionProgress.x(index) * scale,
                    OcMotion.SessionProgress.y(index) * scale,
                ),
                size = Size(dotSide, dotSide),
            )
        }
    }
}

/** One `--_duration: 1.2s` turn of the marquee, restarted each cycle. */
@Composable
private fun progressPhase(): Float {
    val transition = rememberInfiniteTransition(label = "session-progress")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = OcMotion.SessionProgress.DURATION_MS,
                easing = OcMotion.enterEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "session-progress-phase",
    )
    return phase
}
