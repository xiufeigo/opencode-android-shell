package com.xiufeigo.opencodeshell.ui.nav

// Display-px to dp translation for form-factor triage. Upstream `isDesktop()`
// is a CSS-width test (`_upstream/packages/app/src/session/model.ts:35`,
// `(min-width: 768px)`); Android's equivalent input is
// `Configuration.screenWidthDp`, which reports the *window* width, not the
// display size. That distinction explains the 1366x768 field report: `wm size`
// prints display px while the app can sit in a narrower window (or keep a
// higher density), so `screenWidthDp` stays below 768 and the mobile form is
// the correct branch. `MobileForm.isDesktop` itself is exact
// (`isDesktop(1366) == true`) — when the branch surprises, convert the
// display numbers here and compare against what `Configuration` reported
// instead of re-tuning the breakpoint.
object ViewportWidth {
    /** `dp = px * 160 / densityDpi`, the same scale `Configuration` uses. */
    fun dpForPx(displayPx: Int, densityDpi: Int): Int {
        require(densityDpi > 0) { "densityDpi must be positive" }
        return (displayPx * 160f / densityDpi).toInt()
    }

    /**
     * Which branch a display of [displayWidthPx] px at [densityDpi] dpi takes.
     * [desktopMinWidthDp] stays 768 (upstream `model.ts:35`); pass the same
     * constant the call site uses so triage and rendering can never disagree.
     */
    fun isDesktopDisplay(displayWidthPx: Int, densityDpi: Int, desktopMinWidthDp: Int = 768): Boolean =
        dpForPx(displayWidthPx, densityDpi) >= desktopMinWidthDp
}
