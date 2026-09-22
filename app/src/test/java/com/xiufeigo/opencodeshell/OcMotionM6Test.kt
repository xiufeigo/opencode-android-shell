package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.components.OcScrollbarModel
import com.xiufeigo.opencodeshell.ui.components.SpinnerModel
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.ReducedMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * M6 motion contract (`research/03` §2–§5, `research/07` §3).
 *
 * Everything asserted here is a number or a pure function taken from the
 * vendored CSS / JS: the overlay enter/exit table, the reduced-motion decision
 * and its 16 targets, the shimmer sweep model, the spinner's random table and
 * the 25-dot marquee. No Compose runtime is involved, so the tests run as plain
 * JVM unit tests.
 */
class OcMotionM6Test {

    // ------------------------------------------------------------ overlays

    @Test
    fun `overlay table keeps the design rhythm`() {
        // research/03 §6.8: enter 120–150 ms ease-out, exit 80–100 ms ease-in.
        assertTrue(OcMotion.Overlay.allWithinRhythm())
        assertTrue(OcMotion.Overlay.entries.size >= 8)
    }

    @Test
    fun `each family carries its keyframe duration`() {
        assertEquals(120, OcMotion.Overlay.MENU.enterMs)
        assertEquals(80, OcMotion.Overlay.MENU.exitMs)
        assertEquals(150, OcMotion.Overlay.DIALOG.enterMs)
        assertEquals(100, OcMotion.Overlay.DIALOG.exitMs)
        assertEquals(120, OcMotion.Overlay.TOOLTIP.enterMs)
        assertEquals(80, OcMotion.Overlay.TOOLTIP.exitMs)
        assertEquals(120, OcMotion.Overlay.PALETTE.enterMs)
        assertEquals(80, OcMotion.Overlay.PALETTE.exitMs)
        assertEquals(150, OcMotion.Overlay.DROPDOWN.enterMs)
        assertEquals(150, OcMotion.Overlay.DROPDOWN.exitMs)
        assertEquals(150, OcMotion.Overlay.POPOVER.enterMs)
        assertEquals(150, OcMotion.Overlay.POPOVER.exitMs)
        assertEquals(280, OcMotion.Overlay.TOAST.enterMs)
        assertEquals(280, OcMotion.Overlay.TOAST.exitMs)
        assertEquals(160, OcMotion.Overlay.TOAST.opacityExitMs)
        assertEquals(0.96f, OcMotion.Overlay.MENU.startScale)
        assertEquals(0.98f, OcMotion.Overlay.DIALOG.startScale)
        assertEquals(-2f, OcMotion.Overlay.SELECT.enterTranslateY)
    }

    @Test
    fun `all scales stay in the 0_95 to 1 band of the keyframes`() {
        // select-open is the loosest at .95; contentShow/dialog-v2 use .98.
        for (overlay in OcMotion.Overlay.entries) {
            assertTrue(overlay.name, overlay.startScale in 0.95f..1f)
        }
    }

    @Test
    fun `enter and exit specs follow reduced motion`() {
        val spec = OcMotion.enterSpec(OcMotion.Overlay.DIALOG, reduced = false)
        assertEquals(150, spec.durationMillis)
        val exit = OcMotion.exitSpec(OcMotion.Overlay.DIALOG, reduced = false)
        assertEquals(100, exit.durationMillis)

        assertEquals(0, OcMotion.enterSpec(OcMotion.Overlay.DIALOG, reduced = true).durationMillis)
        assertEquals(0, OcMotion.exitSpec(OcMotion.Overlay.DIALOG, reduced = true).durationMillis)
        assertEquals(0, OcMotion.enterSpec(OcMotion.Overlay.MENU, reduced = true, delayMillis = 90).delay)
        assertEquals(90, OcMotion.enterSpec(OcMotion.Overlay.MENU, reduced = false, delayMillis = 90).delay)
    }

    @Test
    fun `a closing layer is held for its exit plus one frame`() {
        assertEquals(132L, OcMotion.exitHoldMs(OcMotion.Overlay.DIALOG, reduced = false))
        assertEquals(OcMotion.EXIT_HOLD_SLACK_MS, OcMotion.exitHoldMs(OcMotion.Overlay.DIALOG, reduced = true))
    }

    @Test
    fun `dropdown popover and hover-card close over 150ms ease-out`() {
        for (overlay in listOf(OcMotion.Overlay.DROPDOWN, OcMotion.Overlay.POPOVER)) {
            assertEquals(150, overlay.exitMs)
            assertTrue(overlay.closeEasingOut)
            assertEquals(
                OcMotion.enterEasing,
                OcMotion.exitSpec(overlay, reduced = false).easing,
            )
        }
        // Toast: transform/hold runs 280ms, opacity fades in 160ms.
        assertEquals(280, OcMotion.Overlay.TOAST.exitMs)
        assertEquals(160, OcMotion.exitOpacitySpec(OcMotion.Overlay.TOAST, reduced = false).durationMillis)
        assertEquals(312L, OcMotion.exitHoldMs(OcMotion.Overlay.TOAST, reduced = false))
    }

    // ------------------------------------------------------ reduced motion

    @Test
    fun `reduced motion predicate follows the platform switches`() {
        assertTrue(ReducedMotion.isReduced(animatorDurationScale = 0f, animatorsEnabled = true))
        assertTrue(ReducedMotion.isReduced(animatorDurationScale = 1f, animatorsEnabled = false))
        assertTrue(!ReducedMotion.isReduced(animatorDurationScale = 1f, animatorsEnabled = true))
        // A scaled-down (but not off) animation scale is not reduced motion.
        assertTrue(!ReducedMotion.isReduced(animatorDurationScale = 0.5f, animatorsEnabled = true))
    }

    @Test
    fun `durationMs zeroes every animation when reduced`() {
        val durations = listOf(
            OcMotion.Shimmer.DURATION_MS,
            OcMotion.Shimmer.SWAP_MS,
            OcMotion.Spinner.MIN_DURATION_MS,
            OcMotion.SessionProgress.DURATION_MS,
            OcMotion.ToolCount.SPRING_MS,
            OcMotion.ToolCount.FADE_IN_MS,
            OcMotion.ToolCount.FADE_OUT_MS,
            OcMotion.RING_MS,
            OcMotion.SWAP_MS,
        )
        for (duration in durations) {
            assertEquals(0, OcMotion.durationMs(duration, reduced = true))
            assertEquals(duration, OcMotion.durationMs(duration, reduced = false))
        }
    }

    @Test
    fun `the reduced motion table has the sixteen CSS rules`() {
        // research/03 §5: exactly 16 `prefers-reduced-motion` rules in the build.
        assertEquals(16, OcMotion.ReducedMotionTarget.entries.size)
        assertEquals(
            OcMotion.ReducedMotionTarget.entries.size,
            OcMotion.ReducedMotionTarget.entries.map { it.selector }.toSet().size,
        )
        assertEquals(
            OcMotion.ReducedMotionTarget.Degrade.SHIMMER_OFF,
            OcMotion.ReducedMotionTarget.SHIMMER_ANIMATION.degrade,
        )
        assertEquals(
            OcMotion.ReducedMotionTarget.Degrade.CENTER_ALWAYS_ON,
            OcMotion.ReducedMotionTarget.SESSION_PROGRESS_CENTER.degrade,
        )
        assertEquals(
            OcMotion.ReducedMotionTarget.Degrade.DURATION_ZERO,
            OcMotion.ReducedMotionTarget.TOOL_COUNT_SUMMARY.degrade,
        )
    }

    // -------------------------------------------------------------- shimmer

    @Test
    fun `shimmer keeps the keyframe constants`() {
        assertEquals(1200, OcMotion.Shimmer.DURATION_MS)
        assertEquals(45, OcMotion.Shimmer.STEP_MS)
        assertEquals(220, OcMotion.Shimmer.SWAP_MS)
        assertEquals(90f, OcMotion.Shimmer.ANGLE_DEG)
        assertEquals(5.2f, OcMotion.Shimmer.SPREAD_CH)
        assertEquals(3.6f, OcMotion.Shimmer.SIZE_RATIO)
    }

    @Test
    fun `per character phase steps by 45 over 1200`() {
        assertEquals(0f, OcMotion.Shimmer.charPhase(0))
        assertEquals(0.0375f, OcMotion.Shimmer.charPhase(1), 1e-6f)
        assertEquals(0.375f, OcMotion.Shimmer.charPhase(10), 1e-6f)
    }

    @Test
    fun `the sweep peak crosses one character box`() {
        // CSS: background-size 360%, background-position 100% -> 0%.
        // At u = 0 the peak sits to the left of the box, at u = 1 to the right.
        val left = OcMotion.Shimmer.peak(0f)
        val right = OcMotion.Shimmer.peak(1f - 1e-6f)
        assertEquals(-0.8f, left, 1e-4f)
        assertTrue(right > 1.7f)
        // The peak crosses the box middle at u = .5.
        assertEquals(0.5f, OcMotion.Shimmer.peak(0.5f), 1e-4f)
    }

    @Test
    fun `the button is brightest when the peak is centred`() {
        val centred = OcMotion.Shimmer.intensity(local = 0.5f, progress = 0.5f)
        val off = OcMotion.Shimmer.intensity(local = 0.5f, progress = 0.05f)
        assertEquals(1f, centred, 1e-4f)
        assertTrue(off < centred)
        // Intensity never leaves 0..1 (the band is wider than a character).
        for (step in 0..20) {
            val value = OcMotion.Shimmer.intensityAtChar(
                index = step % 7,
                t = step / 20f,
                cycle = step / 20f,
            )
            assertTrue(value in 0f..1f)
        }
    }

    @Test
    fun `per character phase shifts the wave along the text`() {
        val a = OcMotion.Shimmer.intensityAtChar(index = 0, t = 0.5f, cycle = 0.2f)
        val b = OcMotion.Shimmer.intensityAtChar(index = 8, t = 0.5f, cycle = 0.2f)
        assertNotEquals(a, b)
    }

    // -------------------------------------------------------------- spinner

    @Test
    fun `spinner geometry matches the bundle`() {
        assertEquals(15f, OcMotion.Spinner.VIEW_BOX)
        assertEquals(3f, OcMotion.Spinner.DOT_SIZE)
        assertEquals(1f, OcMotion.Spinner.DOT_RADIUS)
        assertEquals(4f, OcMotion.Spinner.GRID_PITCH)
        assertEquals(16, OcMotion.Spinner.COUNT)
        assertEquals(setOf(0, 3, 12, 15), OcMotion.Spinner.CORNERS)
        assertEquals(setOf(1, 2, 4, 7, 8, 11, 13, 14), OcMotion.Spinner.OUTER)
        assertEquals(setOf(5, 6, 9, 10), OcMotion.Spinner.INNER)
        // The three groups partition the matrix.
        assertEquals(
            (0 until 16).toSet(),
            OcMotion.Spinner.CORNERS + OcMotion.Spinner.OUTER + OcMotion.Spinner.INNER,
        )
    }

    @Test
    fun `spinner pulse ranges are the pulse-opacity keyframes`() {
        assertEquals(0.15f, OcMotion.Spinner.OUTER_MIN_ALPHA)
        assertEquals(0.35f, OcMotion.Spinner.OUTER_MAX_ALPHA)
        assertEquals(0.4f, OcMotion.Spinner.INNER_MIN_ALPHA)
        assertEquals(1f, OcMotion.Spinner.INNER_MAX_ALPHA)
        assertEquals(OcMotion.Spinner.Kind.CORNER, OcMotion.Spinner.kindOf(3))
        assertEquals(OcMotion.Spinner.Kind.OUTER, OcMotion.Spinner.kindOf(1))
        assertEquals(OcMotion.Spinner.Kind.INNER, OcMotion.Spinner.kindOf(6))
    }

    @Test
    fun `the spinner table is random but inside the documented ranges`() {
        val dots = SpinnerModel.dots(Random(7))
        assertEquals(16, dots.size)
        dots.forEachIndexed { index, dot ->
            assertEquals(index, dot.index)
            assertEquals((index % 4) * 4f, dot.x)
            assertEquals((index / 4) * 4f, dot.y)
            assertTrue(dot.delayMs in 0 until OcMotion.Spinner.MAX_DELAY_MS)
            assertTrue(
                dot.durationMs in OcMotion.Spinner.MIN_DURATION_MS until OcMotion.Spinner.MAX_DURATION_MS,
            )
        }
        // Two draws differ (no seed in the bundle — research/07 §3), while the
        // process-wide table is a single shared instance.
        val other = SpinnerModel.dots(Random(8))
        assertNotEquals(dots.map { it.delayMs }, other.map { it.delayMs })
        assertNotNull(SpinnerModel.Default)
        assertEquals(16, SpinnerModel.Default.size)
    }

    @Test
    fun `reduced motion freezes the spinner on its bright pose`() {
        assertEquals(0.35f, SpinnerModel.staticAlpha(OcMotion.Spinner.Kind.OUTER))
        assertEquals(1f, SpinnerModel.staticAlpha(OcMotion.Spinner.Kind.INNER))
        assertEquals(0f, SpinnerModel.staticAlpha(OcMotion.Spinner.Kind.CORNER))
    }

    // ------------------------------------------------------ session progress

    @Test
    fun `progress indicator is the five by five bundle grid`() {
        assertEquals(5, OcMotion.SessionProgress.GRID)
        assertEquals(25, OcMotion.SessionProgress.COUNT)
        assertEquals(12, OcMotion.SessionProgress.CENTER)
        assertEquals(16f, OcMotion.SessionProgress.VIEW_BOX)
        assertEquals(2f, OcMotion.SessionProgress.DOT_SIZE)
        assertEquals(1f, OcMotion.SessionProgress.GAP)
        assertEquals(1.5f, OcMotion.SessionProgress.INSET)
        assertEquals(1200, OcMotion.SessionProgress.DURATION_MS)
        assertEquals(0.2f, OcMotion.SessionProgress.BASE_ALPHA)
        // x = 1.5 + i % 5 * 3, y = 1.5 + floor(i / 5) * 3
        assertEquals(1.5f, OcMotion.SessionProgress.x(0))
        assertEquals(13.5f, OcMotion.SessionProgress.x(4))
        assertEquals(1.5f, OcMotion.SessionProgress.y(4))
        assertEquals(13.5f, OcMotion.SessionProgress.y(24))
        // `data-dot="12"` is the centre cell: column 2, row 2 (index 10 starts row 2).
        assertEquals(OcMotion.SessionProgress.x(2), OcMotion.SessionProgress.x(12))
        assertEquals(OcMotion.SessionProgress.y(10), OcMotion.SessionProgress.y(12))
        assertEquals(7.5f, OcMotion.SessionProgress.x(12))
        assertEquals(7.5f, OcMotion.SessionProgress.y(12))
    }

    @Test
    fun `the centre dot is always lit`() {
        // `[data-dot="12"] { opacity: 1 }` in every reduced-motion rule.
        for (step in 0..20) {
            assertEquals(1f, OcMotion.SessionProgress.opacity(12, step / 20f), 1e-6f)
        }
    }

    @Test
    fun `the marquee decays from one to the baseline`() {
        val samples = (0..100).map { OcMotion.SessionProgress.opacity(5, it / 100f) }
        for (value in samples) {
            assertTrue("opacity out of range: $value", value in 0.2f..1f)
        }
        assertEquals(1f, samples.max(), 1e-6f)
        assertEquals(0.2f, samples.min(), 1e-6f)
    }

    @Test
    fun `later dots lag behind the wave`() {
        // research/03 §2.1: 索引越大相位越靠后 — dot 1 peaks one 25th later than dot 0.
        val first = OcMotion.SessionProgress.opacity(1, 0f)
        val second = OcMotion.SessionProgress.opacity(2, 1f / 25f)
        assertEquals(first, second, 1e-6f)
    }

    // ------------------------------------------------------------ counters

    @Test
    fun `tool counter gather values match the CSS`() {
        assertEquals(480, OcMotion.ToolCount.SPRING_MS)
        assertEquals(280, OcMotion.ToolCount.FADE_IN_MS)
        assertEquals(320, OcMotion.ToolCount.FADE_OUT_MS)
        assertEquals(2f, OcMotion.ToolCount.BLUR_DP)
        assertEquals(0.06f, OcMotion.ToolCount.TRANSLATE_EM)
        assertEquals(0.985f, OcMotion.ToolCount.SCALE_FROM)
        assertEquals(240, OcMotion.ToolCount.STATUS_SWAP_MS)
        assertEquals(0.45f, OcMotion.ToolCount.STATUS_BLUR_FACTOR)
        assertEquals(0.03f, OcMotion.ToolCount.STATUS_TRANSLATE_EM)
    }

    // ------------------------------------------------------------ scrollbar

    @Test
    fun `scrollbar thumb tracks a reversed list`() {
        // At the bottom of a reverseLayout list the newest item (index 0) is the
        // first visible one: the thumb sits at the track's end.
        val atBottom = OcScrollbarModel.thumb(
            firstVisibleItemIndex = 0,
            totalItems = 100,
            visibleItems = 10,
        )
        assertEquals(1f, atBottom.topFraction + atBottom.sizeFraction, 1e-4f)
        assertEquals(0.1f, atBottom.sizeFraction, 1e-4f)

        // Scrolled to the visual top: the newest item is out of view and the
        // thumb sits at the track's start.
        val atTop = OcScrollbarModel.thumb(
            firstVisibleItemIndex = 99,
            totalItems = 100,
            visibleItems = 10,
        )
        assertEquals(0f, atTop.topFraction, 1e-3f)

        // Halfway through: the thumb is roughly centred.
        val middle = OcScrollbarModel.thumb(
            firstVisibleItemIndex = 50,
            totalItems = 100,
            visibleItems = 10,
        )
        assertTrue(middle.topFraction in 0.3f..0.6f)
    }

    @Test
    fun `scrollbar degrades on short lists`() {
        val single = OcScrollbarModel.thumb(firstVisibleItemIndex = 0, totalItems = 1, visibleItems = 1)
        assertEquals(1f, single.sizeFraction)
        assertEquals(0f, single.topFraction)
        val empty = OcScrollbarModel.thumb(firstVisibleItemIndex = 0, totalItems = 0, visibleItems = 0)
        assertEquals(1f, empty.sizeFraction)
        val tiny = OcScrollbarModel.thumb(firstVisibleItemIndex = 0, totalItems = 1000, visibleItems = 1)
        assertEquals(OcScrollbarModel.MIN_SIZE_FRACTION, tiny.sizeFraction)
    }

    @Test
    fun `dragging the scrollbar maps back onto item indexes`() {
        assertEquals(0, OcScrollbarModel.indexForProgress(1f, totalItems = 100, visibleItems = 10))
        assertEquals(99, OcScrollbarModel.indexForProgress(0f, totalItems = 100, visibleItems = 10))
        val middle = OcScrollbarModel.indexForProgress(0.5f, totalItems = 100, visibleItems = 10)
        assertTrue(middle in 40..60)
        assertEquals(0, OcScrollbarModel.indexForProgress(0f, totalItems = 1, visibleItems = 1))
        // Never leave the list's index range.
        val clamped = OcScrollbarModel.indexForProgress(2f, totalItems = 5, visibleItems = 2)
        assertTrue(clamped in 0..4)
    }

    // ----------------------------------------------------------- invariants

    @Test
    fun `no streaming caret is implemented`() {
        // research/07 §1 定案: opencode v2 has no blinking cursor; the shell must
        // not grow one. The M6 motion code therefore has no cursor constant.
        val names = OcMotion::class.java.declaredClasses.mapNotNull { it.simpleName }
        assertTrue(
            "motion tokens must not describe a text cursor: $names",
            names.none { it.contains("cursor", ignoreCase = true) || it.contains("caret", ignoreCase = true) },
        )
    }
}
