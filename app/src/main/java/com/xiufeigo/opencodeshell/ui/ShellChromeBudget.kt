package com.xiufeigo.opencodeshell.ui

/**
 * v57 P0 「短窗 composer 塌成麦克风条」: whether the shell's top tab strip
 * ([ui.nav.DesktopTabStrip]) yields while the soft keyboard owns the window.
 *
 * Measured on 1440x810 @420dpi (2.625 px/dp, ≈308.6 dp of window) from an
 * on-device `onSizeChanged` probe — `research/v57_verify/tE`, tags `V57PROBE`
 * and `V57BUDGET`:
 *
 * ```
 * no IME   maxHeight (the whole session body)  146.67 dp
 *          chrome ceiling                       30.67 dp
 *          composer (stacked)                   96.00 dp
 *          timeline viewport                    50.67 dp   ← 1.5 message lines
 * IME up   maxHeight                            33.90 dp   ← the composer is
 *                                                               the entire pane
 * ```
 *
 * 146.7 dp of body is already less than half the window: the strip above it and
 * the navigation-bar inset below it take the rest. With the IME up the strip's
 * ~48 dp is the difference between a composer that fits whole and one that
 * cannot hold its own tool row — so it goes, and comes back when the keyboard
 * does.
 *
 * Only a *short* window yields: on a phone in portrait the strip is worth its
 * height and the composer was never in danger.
 */
internal object ShellChromeBudget {

    /** Below this window height (dp) the composer cannot afford the chrome. */
    const val SHORT_WINDOW_DP: Int = 480

    /** Whether the shell's top strip stands down for the IME. */
    fun yieldsToIme(imeVisible: Boolean, windowHeightDp: Int): Boolean =
        imeVisible && isShortWindow(windowHeightDp)

    /**
     * Whether this window is too short for the shell's chrome at all.
     *
     * Read from the **configuration**, never from a measured layout: on the
     * measured 1440x810 @420dpi window `screenHeightDp` is 308 and stays 308,
     * while the session body's own height swings 146.7 → 218.7 → 38.1 dp as the
     * IME animates and the top strip is shown and hidden. A form decision read
     * from that number feeds back into it — the v57 build measured the composer
     * alternating between 56 and 116 dp several times a second until it was
     * moved here.
     */
    fun isShortWindow(windowHeightDp: Int): Boolean =
        windowHeightDp in 1 until SHORT_WINDOW_DP

    /**
     * Whether the shell's top strip is actually on screen — i.e. whether it is
     * the thing that **consumes** the status-bar inset, so the destinations
     * below must not pad for it a second time.
     *
     * v63 P0 「横屏+键盘：顶栏图标压状态栏」 (`research/v63_verify/5560/72_land_ime.png`):
     * the consumption used to follow the *setting* (`topTitlebar`) alone, while
     * the strip's own rendering follows the setting **and** [yieldsToIme]. On a
     * short window — which is every landscape phone and the measured
     * 1440x810 @420dpi (308 dp) — the keyboard takes the strip away, so the
     * destination inherited a consumed status-bar inset of 0 and laid its own
     * header row at y=0: the back chevron under the clock, the trailing icons
     * under LTE/signal/battery (`70_land_nokb.png`, same window, no keyboard,
     * is correct). The consumption must follow the render, not the setting.
     */
    fun topStripOwnsStatusBar(topTitlebar: Boolean, yieldsToIme: Boolean): Boolean =
        topTitlebar && !yieldsToIme
}
