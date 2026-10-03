package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.nav.ViewportWidth
import com.xiufeigo.opencodeshell.ui.settings.SettingsRowStackedViewportWidth
import com.xiufeigo.opencodeshell.ui.settings.SettingsRowStackedWidth
import com.xiufeigo.opencodeshell.ui.settings.settingsRowStacked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `SettingsRow`'s **stacked** shape: the branch `stacked = true` selects, which
 * no caller ever selected (the parameter is dead — every call site takes the
 * default) while the row's own width is what decides whether two columns fit.
 *
 * Upstream wraps the row in `@container settings-panel (max-width: 520px)`
 * (`settings.css:644-656`) and again under its base `flex-wrap: wrap`, which only
 * un-wraps at `@media (min-width: 640px)` (`:446-463`) — so a sub-640 px window
 * stacks whatever the panel measures, and above it the panel's own 520 px
 * decides. [SettingsRowStackedWidth] is that container rule translated to the
 * width the row measures (the container minus the 40 dp panel inset and the
 * 20 dp list inset, 120 dp in total).
 *
 * The first landed version of this fix used 320 dp, derived from the copy
 * column's own readability (`0.4 × row − 16 = 112 dp`). That is the wrong
 * quantity, and the device run of `research/v36_verify/tC` is what shows it: a
 * 1080 px / 320 dpi phone is a **540 dp** window, so the row is 388 dp — above
 * 320, so the row kept its two columns and the descriptions wrapped beside
 * their controls (`tC_03e_narrow_general.png`, `tC_03f_narrow_appearance.png`).
 *
 * The panels have no JVM-renderable surface in this project (no Compose test
 * dependency; see `SettingsPanelScrollTest`), so the decision is pinned as the
 * **pure function** the row calls — [settingsRowStacked] — the real device
 * geometries that function must answer for, plus source facts that the row
 * really calls it with its measured width **and** its window width.
 */
class SettingsRowStackedTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun components() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsComponents.kt")

    private fun window(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("missing window start: $from", start >= 0)
        val end = src.indexOf(to, start + from.length)
        assertTrue("missing window end: $to", end > start)
        return src.substring(start, end)
    }

    /**
     * The row width a window of [windowWidthDp] produces, through the same chain
     * the dialog renders: `BoxWithCappedSize` is `min(100vw − 32, 980)`,
     * `SettingsPanelBody` insets 40 dp a side, `SettingsList` another 20, and
     * `SettingsDialogContent`'s `wide` branch adds the 240 dp nav plus its 1 dp
     * divider from 640 dp up — the same 640 the row's own viewport rule reads.
     */
    private fun rowWidthForWindow(windowWidthDp: Int): Dp {
        val dialog = minOf(windowWidthDp - 32, 980)
        val panel = if (windowWidthDp >= SettingsRowStackedViewportWidth) dialog - 241 else dialog
        return (panel - 120).dp
    }

    /** The stacked decision for a real window, as `SettingsRow` makes it. */
    private fun stackedForWindow(windowWidthDp: Int): Boolean =
        settingsRowStacked(rowWidthForWindow(windowWidthDp), viewportWidthDp = windowWidthDp)

    // ------------------------------------------------------------- the decision

    @Test
    fun aVeryNarrowRowStacksInsteadOfStarvingItsCopy() {
        // The width this pin exists for: at 250 dp the two-column shape leaves
        // the copy column `0.4 × 250 − 16` = 84 dp, and the control's 140 dp
        // floor takes over under 233 dp.
        assertTrue("250 dp must stack", settingsRowStacked(250.dp))
        assertTrue("one dp under the breakpoint still stacks", settingsRowStacked(399.dp))
        assertTrue("a 200 dp row must stack", settingsRowStacked(200.dp))
        assertTrue("0 dp (a collapsed host) must not divide by two columns", settingsRowStacked(0.dp))
    }

    @Test
    fun aRowWideEnoughForTwoColumnsKeepsThem() {
        // 400 dp is upstream's 520 px container translated to the row: the panel
        // insets 40 dp a *side* (80) and the list another 20 a side (40), so the
        // row sits 120 dp inside the container.
        assertFalse("the breakpoint itself is the last two-column width", settingsRowStacked(400.dp))
        assertFalse("wider rows keep their columns", settingsRowStacked(480.dp))
        assertFalse("a tablet's dialog row keeps its columns", settingsRowStacked(619.dp))
    }

    @Test
    fun theBreakpointIsUpstreamsContainerRuleInRowUnits() {
        assertEquals("the stacked breakpoint", 400f, SettingsRowStackedWidth.value)
        // The arithmetic the KDoc states: upstream's container query wraps the
        // row at 520 px of *panel*, and the row sits 120 dp inside that panel —
        // `SettingsPanelBody`'s `0 40px 40px` (80) plus `SettingsList`'s
        // `padding-inline: 20px` (40).
        assertEquals(
            "the panel width upstream wraps at",
            520f,
            SettingsRowStackedWidth.value + 120f,
            0.01f,
        )
        assertEquals(
            "and the viewport rule upstream wraps at, whatever the panel measures",
            640,
            SettingsRowStackedViewportWidth,
        )
    }

    @Test
    fun anUnboundedRowNeverStacks() {
        // A horizontally scrolling host hands the row `Dp.Infinity`; comparing
        // it as narrow would stack a row whose width is unknown rather than
        // small.
        assertFalse(settingsRowStacked(Dp.Infinity))
    }

    @Test
    fun aCallerCanStillForceTheShape() {
        assertTrue("forced stacked, however wide the row", settingsRowStacked(619.dp, forced = true))
        assertTrue("the force wins over the width", settingsRowStacked(0.dp, forced = true))
        // `forced` is a *force-stacked* override, not a force-two-column one: the
        // parameter it stands for used to be the only switch and no caller ever
        // set it, so the width is what must decide — including for a caller that
        // passes the default explicitly.
        assertFalse("the default leaves the width in charge", settingsRowStacked(619.dp, forced = false))
        assertTrue("and the width still stacks a narrow row", settingsRowStacked(200.dp, forced = false))
    }

    @Test
    fun aSubSixHundredAndFortyWindowStacksWhateverThePanelMeasures() {
        // Upstream's base row IS the wrapped one (`flex-wrap: wrap`); only
        // `@media (min-width: 640px)` un-wraps it. A row wide enough for two
        // columns still stacks in that window — the rule the row's own width
        // cannot express, because the 640 dp nav breakpoint moves the row *down*
        // 241 dp right at that boundary.
        assertTrue(
            "a 639 dp window stacks even a 487 dp row",
            settingsRowStacked(487.dp, viewportWidthDp = 639),
        )
        assertFalse(
            "and the same row keeps its columns one dp up",
            settingsRowStacked(487.dp, viewportWidthDp = 640),
        )
        assertTrue("a 480 dp window stacks", settingsRowStacked(400.dp, viewportWidthDp = 480))
    }

    // ------------------------------------------------- the device geometries

    /**
     * The windows the v36 device run actually had, through the whole chain: the
     * fix exists because these must stack, and the wide tablet because it must
     * not. `research/v36_verify/tC` was captured at 1080x2152 px, which the
     * dialog's own geometry identifies as 320 dpi (the dialog is exactly
     * `min(540 − 32, 980)` = 508 dp = 1015 px wide and 600 dp = 1197 px tall in
     * `tC_03e_narrow_general.png`), i.e. a **540 dp** window — not the 443 dp
     * the older note assumed from `1080 / 2.4375`.
     *
     * The v37 run (`research/v37_verify/tB`) is the **same pixel sizes at a
     * different density**, which is what makes its evidence read as a bug: the
     * dialog in `02_settings_narrow_general.png` / `04_settings_wide_appearance
     * .png` is 993 px wide (379 dp) where v36's was 1015 px (508 dp), i.e. the
     * window is `379 + 32` = **411 dp**, not 540 — a 420 dpi device
     * (`1080 / 2.625`), and `05_settings_tablet_appearance.png` (1600x1200 px)
     * has a 1517 px dialog = 578 dp = `min(W − 32, 980)`, so **W ≈ 610 dp**.
     * Both are below the 640 dp wrap, so both stack — and the *screenshots say
     * so themselves*: the navigation is the **top bar** in all four, and
     * `SettingsDialogContent` only takes that branch when
     * `LocalConfiguration.current.screenWidthDp < 640` — the same reading the
     * row's own viewport rule uses. `wm size` alone cannot produce a wide
     * window: the dp width is `px × 160 / densityDpi`
     * ([ViewportWidth.dpForPx]), and the density the verifier left behind is
     * what decides the form factor.
     */
    @Test
    fun theDeviceWindowThatFailedStacks() {
        assertTrue("1080 px @ 320 dpi = 540 dp window", stackedForWindow(540))
        assertTrue("a 443 dp window (the older reading) stacks too", stackedForWindow(443))
        assertTrue("a 360 dp phone stacks", stackedForWindow(360))
        assertTrue("a 411 dp phone stacks", stackedForWindow(411))
        assertEquals("the 540 dp window's row is 388 dp", 388f, rowWidthForWindow(540).value, 0.01f)
        assertEquals("the 443 dp window's row is 291 dp", 291f, rowWidthForWindow(443).value, 0.01f)
    }

    @Test
    fun aWideWindowKeepsItsTwoColumns() {
        // 2560x1600 px @ 320 dpi = 1280x800 dp — the wide capture in
        // `research/v36_verify/tC/tC_03d_wide_general.png`, whose dialog is the
        // 980 dp cap (1957 px measured). Its row is 619 dp: two columns.
        assertFalse("the 1280 dp tablet keeps its columns", stackedForWindow(1280))
        assertEquals("the tablet's row is 619 dp", 619f, rowWidthForWindow(1280).value, 0.01f)
        assertFalse("and so does an 800 dp window's 407 dp row", stackedForWindow(800))
        // The boundary is the container rule, not a phone/tablet split: at 793 dp
        // the row is exactly 400 dp and keeps its columns, one dp narrower it
        // stacks.
        assertEquals(400f, rowWidthForWindow(793).value, 0.01f)
        assertFalse(stackedForWindow(793))
        assertTrue(stackedForWindow(792))
    }

    /**
     * The wide case the v37 report asked for, at the density that makes it wide:
     * **1600x1200 px @ 320 dpi** is an 800x600 dp window — the landscape tablet
     * shape — and the row there is 407 dp, over the breakpoint, so it keeps its
     * two columns. This is the geometry the report assumed for
     * `05_settings_tablet_appearance.png`; that capture is a 420 dpi window
     * instead — its dialog measures 1517 px = 578 dp = `min(W − 32, 980)` and
     * its navigation is the top bar, both of which need `screenWidthDp < 640`
     * ([theSamePixelWindowAtPhoneDensityStacks]) — which is why the *screenshot*
     * stacks and this case does not.
     */
    @Test
    fun aSixteenHundredPixelWindowAtTabletDensityKeepsTwoColumns() {
        val window = ViewportWidth.dpForPx(1600, 320)
        assertEquals("1600 px @ 320 dpi", 800, window)
        assertEquals("the dialog is min(800 − 32, 980) = 768 dp", 768, window - 32)
        assertEquals(
            "so the row is 768 − 241 (nav + divider) − 120 (panel and list insets)",
            407f,
            rowWidthForWindow(window).value,
            0.01f,
        )
        assertFalse(
            "a 407 dp row keeps its two columns",
            settingsRowStacked(rowWidthForWindow(window), viewportWidthDp = window),
        )
        // The same window read without the nav column — the dialog minus the two
        // insets, 800 − 120 = 680 dp — is over the breakpoint as well.
        assertFalse("and a 680 dp row does too", settingsRowStacked(680.dp, viewportWidthDp = 800))
        assertFalse("as does the 2560x1600 tablet's 1280 dp window", stackedForWindow(1280))
    }

    /**
     * The density trap the v37 captures fell into: `wm size` changes the display
     * pixels and leaves `wm density` alone, so the **same** 1600x1200 px is an
     * 800 dp window on a 320 dpi device and a **609 dp** one on a 420 dpi phone
     * (`ViewportWidth.dpForPx`). 609 < 640, so the row stacks — even though its
     * own width there (457 dp) is over the breakpoint, i.e. the viewport rule is
     * the only rule stacking it. The screenshots prove the density without any
     * pixel measuring: all four v37 settings captures show the **top bar**, and
     * that branch needs `screenWidthDp < 640`.
     */
    @Test
    fun theSamePixelWindowAtPhoneDensityStacks() {
        assertEquals("1080 px @ 420 dpi — the v37 tB phone", 411, ViewportWidth.dpForPx(1080, 420))
        assertEquals("1080 px @ 320 dpi — the v36 tC foldable", 540, ViewportWidth.dpForPx(1080, 320))
        assertEquals("1600 px @ 420 dpi — the v37 'tablet' capture", 609, ViewportWidth.dpForPx(1600, 420))
        assertEquals("the 609 dp window's row is 457 dp", 457f, rowWidthForWindow(609).value, 0.01f)
        assertFalse("so the row alone would keep two columns", settingsRowStacked(457.dp, viewportWidthDp = 800))
        assertTrue("but the 609 dp window still stacks it", stackedForWindow(609))
        assertTrue("and the 411 dp phone windows stack", stackedForWindow(411))
        assertEquals("the 411 dp window's row is 259 dp", 259f, rowWidthForWindow(411).value, 0.01f)
    }

    /**
     * The two-column branch a wide window reaches: the decision is `false`, it
     * feeds `SettingsRowBody(stacked = …)` (pinned below), and the body's single
     * shape `if`/`else` renders the `copy (weight 1f) | 16 dp | control` row for
     * that `false`.
     */
    @Test
    fun theWideGeometrySelectsTheTwoColumnBranch() {
        assertFalse(settingsRowStacked(619.dp, viewportWidthDp = 1280))
        assertFalse(settingsRowStacked(407.dp, viewportWidthDp = 800))
        val body = window(components(), "private fun SettingsRowBody(", "internal val SettingsRowControlWidth")
        assertTrue(body.contains("Row(verticalAlignment = Alignment.CenterVertically)"))
        assertTrue(body.contains("Box(Modifier.weight(1f).then(toggle))"))
    }

    /**
     * Why a screenshot can be read as a window width — the measurement the v37
     * evidence needs. `SettingsDialogContent`'s nav shape (top bar vs 240 dp
     * sidebar) and the row's wrap read the **same** constant off the same
     * `LocalConfiguration.current.screenWidthDp`, so the top bar in a capture
     * *is* the proof that `screenWidthDp < 640`, and the row therefore stacks
     * there whatever its own measured width is.
     */
    @Test
    fun theNavShapeAndTheRowWrapReadTheSameWindowWidth() {
        val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsScreen.kt")
        assertTrue(
            "the nav branch reads the row rule's constant",
            screen.contains(
                "val wide = LocalConfiguration.current.screenWidthDp >= SettingsRowStackedViewportWidth",
            ),
        )
        assertTrue(
            "and the row reads the same window width",
            components().contains("val viewportWidthDp = LocalConfiguration.current.screenWidthDp"),
        )
    }

    // ---------------------------------------------------------------- the wiring

    @Test
    fun theRowDecidesFromItsOwnMeasuredWidth() {
        val row = window(components(), "fun SettingsRow(", "private fun SettingsRowBody(")
        assertTrue(
            "the row measures itself — a screen-width breakpoint would be wrong inside the 980 dp dialog",
            row.contains("BoxWithConstraints(modifier.fillMaxWidth())"),
        )
        assertTrue(
            "and hands the measured width to the shared decision",
            row.contains("stacked = settingsRowStacked(maxWidth, stacked, viewportWidthDp),"),
        )
        assertTrue("the body is the one that renders", row.contains("SettingsRowBody("))
        // The viewport rule needs the window, and it must be the *window*
        // (`LocalConfiguration`), the same source `SettingsDialogContent`'s
        // `wide` nav decision reads.
        assertTrue(
            "the row reads the window width for upstream's <640 px wrap",
            row.contains("val viewportWidthDp = LocalConfiguration.current.screenWidthDp"),
        )
    }

    @Test
    fun theStackedBranchIsTheOneThatCannotStarve() {
        val body = window(components(), "private fun SettingsRowBody(", "internal val SettingsRowControlWidth")
        val stacked = window(body, "if (stacked) {", "} else {")
        assertTrue(
            "the copy takes the whole line",
            stacked.contains("SettingsRowCopy(titleResId, descriptionResId, Modifier.fillMaxWidth().then(toggle), description)"),
        )
        assertTrue(
            "the control moves under it, full width and trailing-aligned (upstream's wrapped control)",
            stacked.contains("Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { control() }"),
        )
        assertFalse(
            "nothing in the stacked branch is laid out beside the copy",
            stacked.contains("Modifier.weight(1f).then(toggle)"),
        )
        val columns = window(body, "} else {", "if (!last) {")
        assertTrue(
            "the two-column branch is unchanged: copy weighted, control unweighted",
            columns.contains("Box(Modifier.weight(1f).then(toggle))") && columns.contains("control()"),
        )
    }

    /**
     * The rendering path itself: [settingsRowStacked] is only the decision — the
     * device bug was that the row never took the branch it selects. The call
     * site is pinned above; this pins that the branch it feeds is the *only*
     * place the stacked shape is rendered, and that `SettingsRowBody` has no
     * second, unguarded two-column path a narrow row could fall back into.
     */
    @Test
    fun theStackedBranchIsReachedThroughTheRowsOwnBody() {
        val body = window(components(), "private fun SettingsRowBody(", "internal val SettingsRowControlWidth")
        assertEquals(
            "one shape decision, one if/else",
            1,
            Regex("if \\(stacked\\) \\{").findAll(body).count(),
        )
        assertEquals(
            "and no other call site renders a row's copy beside its control",
            1,
            Regex("Modifier\\.weight\\(1f\\)\\.then\\(toggle\\)").findAll(components()).count(),
        )
        assertTrue(
            "the decision is applied to the body's `stacked` parameter, not to a local default",
            components().contains("stacked: Boolean,\n    onToggle: (() -> Unit)?,"),
        )
    }

    @Test
    fun theStackedShapeIsDocumentedAsUpstreamsWrap() {
        val row = window(components(), "/**\n * One `[data-component=settings-v2-row]`", "@Composable\nfun SettingsRow(")
        assertTrue("the stacked shape cites upstream's container query", row.contains("settings-panel (max-width: 520px)"))
        assertTrue("and the viewport rule it mirrors", row.contains("640"))
        assertTrue(
            "the breakpoint carries its arithmetic",
            components().contains("520 − 120") || components().contains("520 - 120"),
        )
        assertTrue(
            "and the container inset it is derived from",
            components().contains("`0 40px 40px`") || components().contains("padding-inline: 20px"),
        )
    }
}
