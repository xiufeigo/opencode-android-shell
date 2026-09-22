package com.xiufeigo.opencodeshell.ui.session.drawer

/**
 * W4-C mobile-form decisions (Route C, task-3).
 *
 * The values here are the **pure** part of the mobile alignment: the width
 * breakpoint, the bottom-sheet geometry (`mobile-drawer.css`), the session-row
 * long-press timeout (`home/sessions/view.tsx:30`) and the question-dock
 * pager arithmetic (`session-question-dock.tsx:84-119,486-533`). They live
 * together so `:app:testDebugUnitTest` can pin them without a device.
 *
 * ### Breakpoint: upstream `isDesktop()` = 768 px
 *
 * Upstream gates the desktop shell on the media query
 * `(min-width: 768px)` (`_upstream/packages/app/src/session/model.ts:35`,
 * consumed by `screen.tsx:290-317` and `review/view.tsx:23-117`): ≥768 px gets
 * the review sidebar + left drawer equivalent, below it the bottom-sheet drawer
 * and the four full-width view tabs. That value is what this project adopts
 * (Lead ruling R1) — 768 dp here.
 *
 * Consequence for the emulator set, measured at runtime
 * (`dumpsys activity` `mCurrentConfig`):
 *
 * | device | px | dpi | runtime config | width |
 * |---|---|---|---|---|
 * | emulator-5554 (tablet) | 2560×1600 | 320 | `sw800dp w1280dp h800dp land` | 1280 dp → desktop |
 * | emulator-5556 (foldable inner) | 2076×2152 | 390 | `sw852dp w852dp h883dp port` | 852 dp → **desktop** |
 *
 * The foldable's inner screen is 852 dp, i.e. above the upstream breakpoint, so
 * it correctly shows the desktop form. The mobile branch is verified by
 * temporarily narrowing that display — `adb -s emulator-5556 shell wm size
 * 1080x2152` = 443 dp, then `wm size reset` — or on its outer display
 * (1080×2364 ≈ 443 dp). See `research/wave4_mobile_form.md` §3.
 */
object MobileForm {
    /** Upstream `isDesktop()` breakpoint (768 px); desktop from this width up. */
    const val DESKTOP_MIN_WIDTH_DP = 768

    /** Upstream `isDesktop()` equivalent (with the project breakpoint above). */
    fun isDesktop(widthDp: Int): Boolean = widthDp >= DESKTOP_MIN_WIDTH_DP

    /** Mobile = bottom-sheet drawer + the four full-width view tabs. */
    fun isMobile(widthDp: Int): Boolean = !isDesktop(widthDp)

    /**
     * Upstream `HOME_SESSION_LONG_PRESS_MS = 500`
     * (`_upstream/packages/app/src/home/sessions/view.tsx:30`) — `setTimeout(…, 500)`
     * on touch `pointerdown` opens the row menu (`:597-610`).
     * Android's `ViewConfiguration.getLongPressTimeout()` default is the same
     * 500 ms, which is what `combinedClickable` uses; the constant is kept so the
     * parity is explicit and testable.
     */
    const val SESSION_ROW_LONG_PRESS_MS = 500

    /** `max-height: min(75dvh, calc(100dvh - env(safe-area-inset-top) - 16px))`. */
    const val SHEET_MAX_HEIGHT_FRACTION = 0.75f

    /** The `- 16px` term of the upstream `max-height` calc. */
    const val SHEET_TOP_MARGIN_DP = 16

    /** `border-radius: 16px 16px 0 0`. */
    const val SHEET_RADIUS_DP = 16

    /** `[data-slot="mobile-drawer-handle"] { height: 28px }`. */
    const val SHEET_HANDLE_HEIGHT_DP = 28

    /** `[data-slot="mobile-drawer-handle"] span { width: 32px; height: 4px }`. */
    const val SHEET_HANDLE_BAR_WIDTH_DP = 32
    const val SHEET_HANDLE_BAR_HEIGHT_DP = 4

    /** `padding: 0 12px max(12px, safe-area-inset-bottom)`. */
    const val SHEET_SIDE_PADDING_DP = 12
    const val SHEET_BOTTOM_PADDING_DP = 12

    /** `transition: transform 240ms cubic-bezier(.2,.8,.2,1)`; closing 200 ms. */
    const val SHEET_ENTER_MS = 240
    const val SHEET_EXIT_MS = 200

    /**
     * `max-height: min(75dvh, 100dvh − safe-area-top − 16px)`, in dp, clamped to
     * at least one row so a tiny window still renders a usable sheet.
     */
    fun sheetMaxHeightDp(screenHeightDp: Int, topInsetDp: Int = 0): Int {
        val fraction = (screenHeightDp * SHEET_MAX_HEIGHT_FRACTION).toInt()
        val bounded = screenHeightDp - topInsetDp - SHEET_TOP_MARGIN_DP
        return minOf(fraction, bounded).coerceAtLeast(1)
    }

    /**
     * Swipe-down dismissal: upstream drags the sheet with the pointer and closes
     * it past a threshold (the handle is `touch-action: none`, so the gesture
     * belongs to the sheet, not the scroller). Android uses a quarter of the
     * sheet height with a 64 dp floor — comfortably past the touch slop, so a
     * flick does not close the sheet by accident.
     */
    fun dismissDragThresholdDp(sheetHeightDp: Int): Float =
        maxOf(64f, sheetHeightDp * 0.25f)

    /** Pure form of [dismissDragThresholdDp] for the drag-end decision. */
    fun shouldDismissByDrag(dragDownDp: Float, sheetHeightDp: Int): Boolean =
        dragDownDp >= dismissDragThresholdDp(sheetHeightDp)

    /**
     * The four full-width tabs of the mobile session view, in upstream order:
     * `["session", "changes", "files", "terminal"]`
     * (`_upstream/packages/app/src/session/review/view.tsx:46`, mounted by
     * `session/screen.tsx:191-242,303-317`). `usage` is reachable upstream only
     * through the `⋮` menu, so it is not part of the strip.
     */
    enum class Tab { SESSION, CHANGES, FILES, TERMINAL }

    /** Strip order — `session.tab.session` / `session.review.change` / `session.tab.files` / `terminal.title`. */
    val TABS: List<Tab> = listOf(Tab.SESSION, Tab.CHANGES, Tab.FILES, Tab.TERMINAL)

    /** `!h-9` on the upstream `Tabs.List` → 36 px strip. */
    const val TAB_STRIP_HEIGHT_DP = 36

    /** Tab label key for the strip (upstream `view.tsx:54-60`). */
    fun labelOf(tab: Tab): String = when (tab) {
        Tab.SESSION -> "session.tab.session"
        Tab.CHANGES -> "session.review.change"
        Tab.FILES -> "session.tab.files"
        Tab.TERMINAL -> "terminal.title"
    }

    /** Restore a saved tab name, falling back to the session view. */
    fun tabFromName(name: String?): Tab =
        TABS.firstOrNull { it.name == name } ?: Tab.SESSION

    /** Tabs that render content in the strip; `TERMINAL` navigates to the panel. */
    fun isContentTab(tab: Tab): Boolean = tab != Tab.TERMINAL
}

/**
 * Question-dock pager, transcribed from the upstream v2 dock
 * (`_upstream/packages/app/src/session/requests/session-question-dock.tsx:84-119,437,486-533`):
 * one question per page, a `{{current}} of {{total}} questions` header
 * (`:112-115`), one progress segment per question (`:486-502`), `Back` from
 * page 2 on (`:520-524`) and the last page's button becomes `Submit` (`:525-533`).
 */
object QuestionPager {
    /** Upstream `store.tab` is clamped to the question list; 0 when there is none. */
    fun clamp(tab: Int, total: Int): Int = if (total <= 0) 0 else tab.coerceIn(0, total - 1)

    /** Upstream `store.tab > 0` gates the Back button. */
    fun canGoBack(tab: Int): Boolean = tab > 0

    /** Upstream `last() = store.tab >= total() - 1` swaps Next for Submit. */
    fun isLast(tab: Int, total: Int): Boolean = tab >= total - 1

    /** Upstream `next()`: advance, or submit on the last page. */
    fun next(tab: Int, total: Int): Int = clamp(tab + 1, total)

    fun back(tab: Int, total: Int): Int = clamp(tab - 1, total)

    /** 1-based page number for the `{{current}} of {{total}} questions` label. */
    fun progressNumber(tab: Int, total: Int): Int = clamp(tab, total) + 1

    /** `Show when={total() > 1}` — the progress row only exists for multi-question requests. */
    fun showProgress(total: Int): Boolean = total > 1

    /**
     * Per-segment state for the progress row: index of the active segment and
     * the answered set (`data-active` / `data-answered`, `:492-494`).
     */
    data class Segments(val active: Int, val answered: Set<Int>)

    fun segments(tab: Int, answered: List<Boolean>): Segments {
        val total = answered.size
        val active = clamp(tab, total)
        return Segments(
            active = active,
            answered = answered.indices.filterTo(mutableSetOf()) { answered[it] },
        )
    }

    /** A question counts as answered once at least one label is picked. */
    fun answered(selections: List<String>, customActive: Boolean, customText: String): Boolean =
        selections.isNotEmpty() || (customActive && customText.isNotBlank())
}
