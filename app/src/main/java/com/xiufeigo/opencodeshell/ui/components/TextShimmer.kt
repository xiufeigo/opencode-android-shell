package com.xiufeigo.opencodeshell.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.xiufeigo.opencodeshell.ui.theme.LocalOcReducedMotion
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * `TextShimmer` (`research/03` §2.3) — the sweep the web runs over a tool card's
 * title and the "Exploring" label while the tool / turn is still working.
 *
 * CSS structure, reproduced here:
 * ```
 * [data-component=text-shimmer]            data-active, --text-shimmer-index: i
 * ├─ [data-slot=text-shimmer-char-base]    solid --text-weak glyphs
 * └─ [data-slot=text-shimmer-char-shimmer] sweep gradient, background-clip:text
 * ```
 * i.e. a solid base layer with the animated gradient layer painted on top, the
 * latter fading out over **220 ms** (`--text-shimmer-swap`) when the tool stops.
 * Compositing two `Text`s keeps that structure and avoids per-glyph layout.
 *
 * The brush is one horizontal gradient whose stops are sampled per character
 * from [OcMotion.Shimmer]: char *i* runs `45 ms × i` ahead of the sweep, so the
 * highlight is a wave travelling across the string rather than a single bar.
 * See [OcMotion.Shimmer.peak] for the `background-size: 360%` derivation.
 *
 * Reduced motion (`research/03` §5, `SHIMMER_ANIMATION` / `SHIMMER_BASE`) turns
 * the layer off entirely and leaves the solid base glyphs — the same result as
 * `background-image: none; animation: none` + `opacity: 1`.
 *
 * **Layout contract**: the brush is normalised over the text's own width, so the
 * caller must let the text size itself (do not pass `Modifier.fillMaxWidth()` or
 * a fixed width); `softWrap` is off and the text is a single line.
 */
@Composable
fun TextShimmer(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    /** `--text-shimmer-base-color: var(--text-weak)`. */
    baseColor: Color = OcTheme.colors.shimmerBase,
    /** `--text-shimmer-peak-color: var(--text-strong)`. */
    peakColor: Color = OcTheme.colors.shimmerPeak,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    val reduced = LocalOcReducedMotion.current
    val running = active && !reduced && text.isNotEmpty()

    // `[data-slot=text-shimmer-char-shimmer] { transition: opacity 220ms ease-out }`
    val swap by animateFloatAsState(
        targetValue = if (running) 1f else 0f,
        animationSpec = tween(
            durationMillis = OcMotion.durationMs(OcMotion.Shimmer.SWAP_MS, reduced),
            easing = OcMotion.enterEasing,
        ),
        label = "text-shimmer-swap",
    )

    val textStyle = LocalTextStyle.current.merge(
        androidx.compose.ui.text.TextStyle(
            color = baseColor,
            fontSize = fontSize,
            fontWeight = fontWeight,
            lineHeight = lineHeight,
        ),
    )

    Box(modifier = modifier) {
        // char-base: the static glyphs the sweep resolves back to.
        Text(
            text = text,
            style = textStyle,
            maxLines = maxLines,
            overflow = overflow,
            softWrap = false,
        )
        if (swap > 0.01f) {
            val cycle = shimmerCycle()
            Text(
                text = text,
                style = textStyle.copy(brush = shimmerBrush(text.length, cycle, baseColor, peakColor)),
                maxLines = maxLines,
                overflow = overflow,
                softWrap = false,
                // The sweep is decoration: the base layer already announces the text.
                modifier = Modifier.alpha(swap).clearAndSetSemantics { },
            )
        }
    }
}

/** `--text-shimmer-duration: 1.2s`, `linear`, `infinite`, restarted each cycle. */
@Composable
private fun shimmerCycle(): Float {
    val transition = rememberInfiniteTransition(label = "text-shimmer")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = OcMotion.Shimmer.DURATION_MS,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "text-shimmer-cycle",
    )
    return cycle
}

/**
 * The sweep as a single multi-stop horizontal gradient: [OcMotion.Shimmer.SAMPLES_PER_CHAR]
 * samples inside every character box, each evaluated at that character's own
 * phase. Positions are strictly increasing (sample `s` of char `i` sits at
 * `(i + t) / chars`), which is what a `LinearGradient` shader requires.
 */
internal fun shimmerBrush(
    chars: Int,
    cycle: Float,
    baseColor: Color,
    peakColor: Color,
): Brush {
    val count = chars.coerceAtLeast(1)
    val samples = OcMotion.Shimmer.SAMPLES_PER_CHAR
    val stops = ArrayList<Pair<Float, Color>>(count * samples)
    for (index in 0 until count) {
        for (sample in 0 until samples) {
            val t = (sample + 0.5f) / samples
            val position = (index + t) / count
            val intensity = OcMotion.Shimmer.intensityAtChar(index, t, cycle)
            stops += position to lerp(baseColor, peakColor, intensity)
        }
    }
    return Brush.horizontalGradient(*stops.toTypedArray())
}
