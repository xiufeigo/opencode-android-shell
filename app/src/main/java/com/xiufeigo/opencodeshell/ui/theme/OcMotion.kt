package com.xiufeigo.opencodeshell.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntSize

/**
 * The v2 motion tokens used by the Compose shell. Values are the *mapped*
 * Compose specs, not raw CSS: the Framer `{type:"spring", visualDuration:.35,
 * bounce:0}` collapsible animation has no direct Compose equivalent, so it
 * becomes a critically damped spring (`dampingRatio = 1f` — "no bounce") with
 * the stiffness `research/03` §4 suggests; the CSS cubic-beziers are
 * transcribed verbatim.
 *
 * **M6** adds the parts of the vendored CSS the shell had not ported yet:
 * - the overlay enter/exit table (`research/03` §2.1, §3.4) — the design's fixed
 *   rhythm is *in 120–150 ms `ease-out`, out 80–100 ms `ease-in`*;
 * - the 16 `@media (prefers-reduced-motion:reduce)` targets (§5) and the pure
 *   duration-zeroing rule that implements them;
 * - the `text-shimmer` sweep model (§2.3), the 4×4 `spinner` dot model (§2.4,
 *   `research/07` §3), the 5×5 `session-progress-indicator-v2` dot model (§2.1,
 *   geometry read off the bundle — see [SessionProgress]) and the
 *   `tool-count-summary` / `tool-status-title` "gather" values (§3.3).
 *
 * Everything on this object is a value or a pure function on purpose: the
 * timeline numbers, the reduced-motion decision and the dot/brush math are all
 * covered by JVM unit tests (`OcMotionM6Test`).
 */
object OcMotion {

    // ------------------------------------------------------------------ M2

    /**
     * `{type:"spring", visualDuration:.35, bounce:0}` — `[data-slot=collapsible-content][data-animated]`
     * (tool card body, `research/03` §4 mapping note). Typed for
     * `expandVertically` / `shrinkVertically`, which animate an `IntSize`.
     */
    val expand: SpringSpec<IntSize> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** `--tool-motion-fade-ms: 240ms` — the fade that rides with [expand]. */
    val fade: TweenSpec<Float> = tween(durationMillis = 240)

    /** `transition: stroke-dashoffset .35s` on the context ring (`research/02` §2.2). */
    const val RING_MS = 350

    /** `cubic-bezier(0.65, 0, 0.35, 1)` — the ring's `stroke-dashoffset` easing. */
    val ringEasing: Easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

    /** `--tool-motion-ease: cubic-bezier(.22,1,.36,1)` — the shared "soft spring" curve. */
    val softSpringEasing: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    /** `transition: .15s` — chevrons, icon swap, menu fade. */
    const val SWAP_MS = 150

    // ------------------------------------------------------------ M6: easing

    /**
     * CSS `ease-out` as a cubic bezier. Compose's `LinearOutSlowInEasing` is the
     * Material curve (`0.0, 0.0, 0.2, 1.0`), not CSS `ease-out`; the vendored
     * keyframes say `ease-out`, so the CSS curve is what is transcribed here.
     */
    val enterEasing: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    /** CSS `ease-in` (the exit side of every overlay keyframe). */
    val exitEasing: Easing = CubicBezierEasing(0.42f, 0f, 1f, 1f)

    /** CSS `ease-in-out` — the spinner dots (`pulse-opacity*` / `ease-in-out`). */
    val easeInOutEasing: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** CSS `linear` — the shimmer sweep and the progress marquee. */
    val linearEasing: Easing = LinearEasing

    // ------------------------------------------------------ M6: reduced motion

    /**
     * `prefers-reduced-motion: reduce` collapses every duration to `0`
     * (`research/03` §5). Centralised so no call site can forget it: the
     * `AnimatedVisibility` layers, the shimmer, the spinner and the progress
     * marquee all route their durations through here.
     */
    fun durationMs(baseMs: Int, reduced: Boolean): Int =
        if (reduced) 0 else baseMs.coerceAtLeast(0)

    /**
     * The **16** `@media (prefers-reduced-motion:reduce)` rules of the vendored
     * CSS (`research/03` §5), in document order. The shell does not need the raw
     * CSS selector to *do* the degradation — [degrade] says what each rule
     * effectively does, and the enum exists so the port is auditable: a missing
     * rule is a compile-time-visible gap rather than a silent omission.
     */
    enum class ReducedMotionTarget(val selector: String, val degrade: Degrade) {
        ANIMATED_NUMBER(
            "[data-component=animated-number] [data-slot=animated-number-value|strip]",
            Degrade.DURATION_ZERO,
        ),
        TEXT_REVEAL(
            "[data-component=text-reveal] [track|entering|leaving]",
            Degrade.DURATION_ZERO,
        ),
        TEXT_STRIKETHROUGH(
            "[data-slot=text-strikethrough-line]",
            Degrade.CLIP_PATH_NONE,
        ),
        SHIMMER_CHARS(
            "[data-component=text-shimmer] [char-base|char-shimmer]",
            Degrade.DURATION_ZERO,
        ),
        SHIMMER_ANIMATION(
            "[data-component=text-shimmer] [data-slot=text-shimmer-char-shimmer]",
            Degrade.SHIMMER_OFF,
        ),
        SHIMMER_BASE(
            "[data-component=text-shimmer] [data-slot=text-shimmer-char-base]",
            Degrade.BASE_OPAQUE,
        ),
        TOOL_COUNT_LABEL(
            "[data-component=tool-count-label] [tool-count-label-suffix]",
            Degrade.DURATION_ZERO,
        ),
        TOOL_COUNT_SUMMARY(
            "[data-component=tool-count-summary] [empty|item|prefix]",
            Degrade.DURATION_ZERO,
        ),
        TOOL_STATUS_TITLE(
            "[data-component=tool-status-title] [swap|tail|active|done]",
            Degrade.DURATION_ZERO,
        ),
        TITLEBAR_UPDATE_LOADER(
            "[data-slot=titlebar-update-loader]",
            Degrade.ANIMATION_NONE,
        ),
        LOADER_V2(
            "[data-component=loader-v2]",
            Degrade.ANIMATION_NONE,
        ),
        TOAST_V2(
            ".toast-v2, .toast-v2 > *",
            Degrade.ANIMATION_NONE,
        ),
        TOOLTIP_V2(
            "[data-component=tooltip-v2] / [data-closed]",
            Degrade.ANIMATION_NONE,
        ),
        SESSION_PROGRESS_DOT(
            "[data-component=session-progress-indicator-v2] [data-dot]",
            Degrade.ANIMATION_NONE,
        ),
        SESSION_PROGRESS_CENTER(
            "[data-component=session-progress-indicator-v2] [data-dot=\"12\"]",
            Degrade.CENTER_ALWAYS_ON,
        ),
        TAILWIND_MOTION_REDUCE(
            ".motion-reduce\\:translate-x-0 / .motion-reduce\\:transition-none",
            Degrade.DURATION_ZERO,
        ),
        ;

        /** What the CSS rule does when the user asked for reduced motion. */
        enum class Degrade {
            /** `transition-duration: 0s` / `animation: none` — jump to the end state. */
            DURATION_ZERO,

            /** Same as [DURATION_ZERO]; spelled out for the `animation: none` rules. */
            ANIMATION_NONE,

            /** `background-image: none; animation: none` → plain, solid text. */
            SHIMMER_OFF,

            /** `opacity: 1 !important` — the static base glyphs. */
            BASE_OPAQUE,

            /** `clip-path: none` — the strike-through line is drawn outright. */
            CLIP_PATH_NONE,

            /** `opacity: 1` — the 25-dot marquee freezes with its centre dot lit. */
            CENTER_ALWAYS_ON,
        }
    }

    // ------------------------------------------------------------ M6: overlays

    /**
     * Overlay enter/exit timing (`research/03` §2.1 keyframes, §3.4 table).
     *
     * Two invariants the whole family shares, asserted by the unit test: the
     * enter is **120–150 ms with `ease-out`**, the exit is **80–100 ms with
     * `ease-in`** — leaving is always faster than arriving.
     */
    enum class Overlay(val enterMs: Int, val exitMs: Int, val startScale: Float) {
        /** `menu-v2-in .12s ease-out`; no dedicated out keyframe → the 80 ms rhythm. */
        MENU(120, 80, 0.96f),

        /** `select-v2-content-in .12s` / `select-v2-content-out 80ms`. */
        SELECT(120, 80, 0.96f),

        /** `dropdown-menu-open .15s ease-out` / `dropdown-menu-close`. */
        DROPDOWN(150, 100, 0.96f),

        /** `contentShow .15s ease-out` / `contentHide .1s ease-in` (scale .98). */
        DIALOG(150, 100, 0.98f),

        /** `tooltipV2In .12s` / `tooltipV2Out 80ms`. */
        TOOLTIP(120, 80, 0.96f),

        /** `toastPopIn .15s` / `toastPopOut .1s` (translateY, scale kept). */
        TOAST(150, 100, 1f),

        /** `popover-open` / `hover-card-open` / `sessionTabPopoverIn` (`.96`). */
        POPOVER(150, 100, 0.96f),

        /** Command palette: it is a dialog-v2 shell (`command-palette-v2` `.12s`). */
        PALETTE(120, 80, 0.98f),

        /**
         * Tailwind `animate-in` / `animate-out` defaults
         * (`--tw-animation-duration: .15s`, `ease`) — `.15s` in, `.1s` out.
         */
        DEFAULT(150, 100, 0.96f),
        ;

        /** `translateY(-2px)` on the select card (`select-v2-content-in`). */
        val enterTranslateY: Float get() = if (this == SELECT) -2f else 0f

        companion object {
            /** Every family obeys the design's fixed rhythm. */
            fun allWithinRhythm(): Boolean = entries.all {
                it.enterMs in ENTER_RANGE && it.exitMs in EXIT_RANGE
            }

            /** `research/03` §6.8: enter 120–150 ms. */
            val ENTER_RANGE = 120..150

            /** …and exit 80–100 ms. */
            val EXIT_RANGE = 80..100
        }
    }

    /** Enter spec for an overlay family, honouring reduced motion. */
    fun enterSpec(
        overlay: Overlay,
        reduced: Boolean,
        delayMillis: Int = 0,
    ): TweenSpec<Float> = tween(
        durationMillis = durationMs(overlay.enterMs, reduced),
        delayMillis = durationMs(delayMillis, reduced),
        easing = if (reduced) LinearEasing else enterEasing,
    )

    /** Exit spec for an overlay family, honouring reduced motion. */
    fun exitSpec(overlay: Overlay, reduced: Boolean): TweenSpec<Float> = tween(
        durationMillis = durationMs(overlay.exitMs, reduced),
        easing = if (reduced) LinearEasing else exitEasing,
    )

    /** Milliseconds a layer must stay mounted after `close` for its exit to play. */
    fun exitHoldMs(overlay: Overlay, reduced: Boolean): Long =
        durationMs(overlay.exitMs, reduced).toLong() + EXIT_HOLD_SLACK_MS

    /** One frame of slack so the removal never races the last animation frame. */
    const val EXIT_HOLD_SLACK_MS = 32L

    // ------------------------------------------------------------ M6: shimmer

    /**
     * `text-shimmer` (`research/03` §2.3) — the tool-title sweep.
     *
     * CSS sweeps a `5.2ch` highlight of a **360 %-wide** gradient across each
     * character's own box, one character per `-45ms × index`, `1.2 s linear
     * infinite`, and fades the whole shimmer layer out over `220 ms` when the
     * tool stops running.
     *
     * [peak] is the derivation that makes a Compose `Brush` behave like the CSS:
     * with `background-size: 360%` and `background-position` going `100% → 0%`,
     * the image's left edge sits at `-2.6w·X` relative to the character box
     * (`w` = that box's advance) and the gradient's `50%` peak is `1.8w` into the
     * image, so the peak crosses the box as `peak(X) = 1.8 - 2.6X` in box units.
     * Substituting `X = 1 - u` gives `peak(u) = 2.6u - 0.8`.
     */
    object Shimmer {

        /** `--text-shimmer-duration: 1.2s`. */
        const val DURATION_MS = 1200

        /** `--text-shimmer-step: 45ms` — the per-character delay. */
        const val STEP_MS = 45

        /** `--text-shimmer-swap: 220ms` — how long the shimmer layer fades out. */
        const val SWAP_MS = 220

        /** `--text-shimmer-angle: 90deg`. */
        const val ANGLE_DEG = 90f

        /** `--text-shimmer-spread: 5.2ch` — half-width of the highlight band. */
        const val SPREAD_CH = 5.2f

        /** `--text-shimmer-size: 360%`. */
        const val SIZE_RATIO = 3.6f

        /** Samples per character when the band is baked into one horizontal brush. */
        const val SAMPLES_PER_CHAR = 3

        /** `--text-shimmer-index: i * 45 / 1200` — the phase each character runs at. */
        fun charPhase(index: Int): Float = index * (STEP_MS.toFloat() / DURATION_MS)

        /**
         * Highlight centre inside one character box, in box-width units, for the
         * cycle progress `u` (`0 → 1`). Range `[-0.8, 1.8]`: the peak spends a
         * third of the cycle to either side of the box, which is why the sweep
         * reads as a wave travelling over the glyphs.
         */
        fun peak(progress: Float): Float = (SIZE_RATIO - 1f) * frac(progress) - (SIZE_RATIO / 2f - 1f)

        /** Highlight intensity at `local` (0..1 across the box): `1` at the peak, `0` at the band edge. */
        fun intensity(local: Float, progress: Float): Float {
            val distance = kotlin.math.abs(local - peak(progress))
            return (1f - distance / SPREAD_CH).coerceIn(0f, 1f)
        }

        /**
         * Highlight intensity for character [index] at [t] (`0..1` inside that
         * character's box), including the per-character phase difference.
         */
        fun intensityAtChar(index: Int, t: Float, cycle: Float): Float =
            intensity(t, cycle + charPhase(index))
    }

    // ------------------------------------------------------------ M6: spinner

    /**
     * The 4×4 `spinner` dot matrix (`research/03` §2.4, `research/07` §3).
     *
     * `duration` / `delay` are `Math.random()` per dot with **no seed**, generated
     * **once per module load** — so one process-wide table is the faithful port,
     * not a per-instance one (`research/07` §3 定案).
     */
    object Spinner {

        /** `viewBox="0 0 15 15"`. */
        const val VIEW_BOX = 15f

        /** `rect width=3 height=3 rx=1`. */
        const val DOT_SIZE = 3f

        /** `rx=1`. */
        const val DOT_RADIUS = 1f

        /** `x = (i % 4) * 4` / `y = floor(i / 4) * 4` — the grid pitch. */
        const val GRID_PITCH = 4f

        /** 16 dots. */
        const val COUNT = 16

        /** `FMe = {0,3,12,15}` — `opacity: 0`, no animation. */
        val CORNERS = setOf(0, 3, 12, 15)

        /** `RMe = {1,2,4,7,8,11,13,14}` — `pulse-opacity-dim` (`.15 ↔ .35`). */
        val OUTER = setOf(1, 2, 4, 7, 8, 11, 13, 14)

        /** The inner 2×2 — `pulse-opacity` (`.4 ↔ 1`). */
        val INNER = setOf(5, 6, 9, 10)

        /** `pulse-opacity-dim`: `0%,100% { opacity: .15 } 50% { opacity: .35 }`. */
        const val OUTER_MIN_ALPHA = 0.15f
        const val OUTER_MAX_ALPHA = 0.35f

        /** `pulse-opacity`: `0%,100% { opacity: .4 } 50% { opacity: 1 }`. */
        const val INNER_MIN_ALPHA = 0.4f
        const val INNER_MAX_ALPHA = 1f

        /** `duration = 1 + Math.random() * 1` seconds. */
        const val MIN_DURATION_MS = 1000
        const val MAX_DURATION_MS = 2000

        /** `delay = Math.random() * 1.5` seconds. */
        const val MAX_DELAY_MS = 1500

        /** The static pose used when reduced motion freezes the matrix. */
        const val REDUCED_OUTER_ALPHA = OUTER_MAX_ALPHA
        const val REDUCED_INNER_ALPHA = INNER_MAX_ALPHA

        /** Where a dot sits: inner 2×2, outer ring, or a dead corner. */
        enum class Kind { CORNER, OUTER, INNER }

        fun kindOf(index: Int): Kind = when (index) {
            in CORNERS -> Kind.CORNER
            in OUTER -> Kind.OUTER
            else -> Kind.INNER
        }

        fun alphaRange(kind: Kind): ClosedFloatingPointRange<Float> = when (kind) {
            Kind.OUTER -> OUTER_MIN_ALPHA..OUTER_MAX_ALPHA
            Kind.INNER -> INNER_MIN_ALPHA..INNER_MAX_ALPHA
            Kind.CORNER -> 0f..0f
        }
    }

    // --------------------------------------------------- M6: session progress

    /**
     * `session-progress-indicator-v2` (`research/03` §2.1) — the 25-dot marquee
     * shown on a session that is currently working.
     *
     * **Geometry** (read off the bundle rather than guessed — `research/03` §7
     * listed it as unverified): the SVG is `viewBox="0 0 16 16"`, built from
     * `Array.from({length: 25}, (index) => ({x: 1.5 + index % 5 * 3,
     * y: 1.5 + floor(index / 5) * 3}))` with `<rect width=2 height=2>`, i.e. a
     * **5×5 grid**, pitch `3` (2 px dot + 1 px gap), inset `1.5`. `data-dot` is
     * the index, and dot **12** is the centre of the grid.
     *
     * **Timing**: `--_duration: 1.2s`, `ease-out`, `infinite`, `both`, base
     * `opacity: .2`; the centre dot is `opacity: 1` in every segment.
     *
     * Deviation (documented in `research/_M6_motion_status.md`): the bundle only
     * shows the full 9-segment opacity tables for `dot-0`, `dot-12` and `dot-17`,
     * so [opacity] reproduces the *documented semantics* — a highlight that runs
     * ahead of the index, decaying `1 → .75 → .5 → .2` — instead of the 25
     * literal tables.
     */
    object SessionProgress {

        /** 5×5. */
        const val GRID = 5

        /** 25 dots. */
        const val COUNT = GRID * GRID

        /** `data-dot="12"` — the centre of the grid, always `opacity: 1`. */
        const val CENTER = 12

        /** `rect width=2 height=2`. */
        const val DOT_SIZE = 2f

        /** The 1 px gap between dots. */
        const val GAP = 1f

        /** `x = 1.5 + …` — the top-left inset. */
        const val INSET = 1.5f

        /** `viewBox="0 0 16 16"`. */
        const val VIEW_BOX = 16f

        /** `--_duration: 1.2s`. */
        const val DURATION_MS = 1200

        /** `[data-dot] { opacity: .2 }` — the marquee's floor. */
        const val BASE_ALPHA = 0.2f

        /** Eight interpolation segments per cycle (`0% … 100%` in 12.5 % steps). */
        const val SEGMENTS = 8

        /** The decay read off `dot-0` / `dot-17`: `1 → .75 → .5`, then the floor. */
        val SEGMENT_ALPHAS = floatArrayOf(1f, 0.75f, 0.5f, BASE_ALPHA, BASE_ALPHA, BASE_ALPHA, BASE_ALPHA, BASE_ALPHA)

        fun x(index: Int): Float = INSET + (index % GRID) * (DOT_SIZE + GAP)

        fun y(index: Int): Float = INSET + (index / GRID) * (DOT_SIZE + GAP)

        /**
         * Opacity of `data-dot=[index]` at cycle [phase] (`0 → 1`, one `1.2 s`
         * turn). The centre dot never moves; every other dot runs `index / 25`
         * of a cycle *behind* index 0 (`research/03` §2.1: 索引越大相位越靠后),
         * so the highlight travels 0 → 24 across the grid.
         */
        fun opacity(index: Int, phase: Float): Float {
            if (index == CENTER) return 1f
            val local = frac(phase - index.toFloat() / COUNT)
            val scaled = local * SEGMENTS
            val segment = scaled.toInt().coerceIn(0, SEGMENTS - 1)
            val t = scaled - segment
            val from = SEGMENT_ALPHAS[segment]
            val to = if (segment == SEGMENTS - 1) 1f else SEGMENT_ALPHAS[segment + 1]
            return from + (to - from) * t
        }
    }

    // ------------------------------------------------------ M6: tool counters

    /**
     * `tool-count-summary` / `tool-status-title` (`research/03` §3.3) — the
     * "gather" motion of the tool-group counters: the CSS tweens
     * `grid-template-columns: 0fr → 1fr` (width) in **480 ms** with
     * `cubic-bezier(.22,1,.36,1)`, fading over 280/320 ms while a `2px` blur and a
     * `translateY(.06em) scale(.985)` collapse.
     */
    object ToolCount {

        /** `--tool-motion-spring-ms: .48s` (the width tween). */
        const val SPRING_MS = 480

        /** `--tool-motion-fade-ms: .28s` on the way in. */
        const val FADE_IN_MS = 280

        /** `--tool-motion-fade-ms: .32s` on the way out. */
        const val FADE_OUT_MS = 320

        /** `--tool-motion-blur: 2px`. */
        const val BLUR_DP = 2f

        /** `transform: translateY(.06em) scale(.985)` — the collapsed pose. */
        const val TRANSLATE_EM = 0.06f
        const val SCALE_FROM = 0.985f

        /** `tool-status-title` swap: `--tool-motion-fade-ms: .24s`. */
        const val STATUS_SWAP_MS = 240

        /** `filter: blur(calc(var(--tool-motion-blur) * .45))` on the hidden slot. */
        const val STATUS_BLUR_FACTOR = 0.45f

        /** `transform: translateY(.03em)` on the hidden slot. */
        const val STATUS_TRANSLATE_EM = 0.03f
    }

}

/**
 * Positive fractional part (`frac(-0.25) == 0.75`), like CSS keyframe maths.
 * File-private and top-level on purpose: the nested motion objects below the
 * `OcMotion` umbrella cannot see the outer object's members.
 */
internal fun frac(value: Float): Float {
    val f = value - kotlin.math.floor(value)
    return if (f < 0f) f + 1f else f
}
