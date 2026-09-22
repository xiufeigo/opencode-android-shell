package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.drawer.MobileForm
import com.xiufeigo.opencodeshell.ui.session.drawer.QuestionPager
import com.xiufeigo.opencodeshell.ui.session.review.MobileUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W4-C mobile form (task-3): the pure decisions behind the bottom-sheet drawer,
 * the four full-width view tabs and the question-dock pager. Mirrors the
 * upstream anchors in `MobileForm` (upstream `isDesktop()` =
 * `session/model.ts:35`, `mobile-drawer.css:1-60`, `home/sessions/view.tsx:30`,
 * `session-question-dock.tsx:84-119,486-533`).
 */
class MobileFormW4Test {

    // ---------------------------------------------------------------- form gate

    @Test
    fun desktopGateIsUpstream768() {
        // Upstream `createMediaQuery("(min-width: 768px)")`.
        assertFalse(MobileForm.isDesktop(360))
        assertFalse(MobileForm.isDesktop(443))
        assertFalse(MobileForm.isDesktop(767))
        assertTrue(MobileForm.isDesktop(MobileForm.DESKTOP_MIN_WIDTH_DP))
        assertTrue(MobileForm.isDesktop(1280))

        assertTrue(MobileForm.isMobile(767))
        assertFalse(MobileForm.isMobile(768))
    }

    @Test
    fun measuredDevicesLandOnTheDocumentedBranch() {
        // `dumpsys activity` `mCurrentConfig` of the acceptance devices.
        assertTrue(MobileForm.isDesktop(1280)) // emulator-5554 tablet, w1280dp
        assertTrue(MobileForm.isDesktop(852)) // emulator-5556 inner screen, w852dp
        assertFalse(MobileForm.isDesktop(443)) // emulator-5556 + `wm size 1080x2152`
    }

    // -------------------------------------------------------------- sheet metrics

    @Test
    fun sheetHeightIsTheMinOfTheTwoUpstreamTerms() {
        // 75 % of the viewport while that is the smaller term…
        assertEquals(662, MobileForm.sheetMaxHeightDp(screenHeightDp = 883, topInsetDp = 55))
        // …and `100dvh − safe-top − 16px` once the viewport gets short.
        assertEquals(44, MobileForm.sheetMaxHeightDp(screenHeightDp = 60, topInsetDp = 0))
        // Never collapses to zero, even in an absurd window.
        assertEquals(1, MobileForm.sheetMaxHeightDp(screenHeightDp = 10, topInsetDp = 0))
    }

    @Test
    fun sheetGeometryMatchesMobileDrawerCss() {
        assertEquals(16, MobileForm.SHEET_RADIUS_DP)
        assertEquals(28, MobileForm.SHEET_HANDLE_HEIGHT_DP)
        assertEquals(32, MobileForm.SHEET_HANDLE_BAR_WIDTH_DP)
        assertEquals(4, MobileForm.SHEET_HANDLE_BAR_HEIGHT_DP)
        assertEquals(12, MobileForm.SHEET_SIDE_PADDING_DP)
        assertEquals(240, MobileForm.SHEET_ENTER_MS)
        assertEquals(200, MobileForm.SHEET_EXIT_MS)
    }

    @Test
    fun swipeThresholdHasAFloorAndScalesWithTheSheet() {
        assertEquals(64f, MobileForm.dismissDragThresholdDp(100), 0.001f)
        assertEquals(100f, MobileForm.dismissDragThresholdDp(400), 0.001f)

        assertFalse(MobileForm.shouldDismissByDrag(dragDownDp = 99f, sheetHeightDp = 400))
        assertTrue(MobileForm.shouldDismissByDrag(dragDownDp = 100f, sheetHeightDp = 400))
        assertFalse(MobileForm.shouldDismissByDrag(dragDownDp = 63f, sheetHeightDp = 10))
    }

    // ------------------------------------------------------------- view tabs

    @Test
    fun viewTabsAreTheFourUpstreamOnesInOrder() {
        assertEquals(
            listOf(
                MobileForm.Tab.SESSION,
                MobileForm.Tab.CHANGES,
                MobileForm.Tab.FILES,
                MobileForm.Tab.TERMINAL,
            ),
            MobileForm.TABS,
        )
        assertEquals("session.tab.session", MobileForm.labelOf(MobileForm.Tab.SESSION))
        assertEquals("session.review.change", MobileForm.labelOf(MobileForm.Tab.CHANGES))
        assertEquals("session.tab.files", MobileForm.labelOf(MobileForm.Tab.FILES))
        assertEquals("terminal.title", MobileForm.labelOf(MobileForm.Tab.TERMINAL))
        assertEquals(36, MobileForm.TAB_STRIP_HEIGHT_DP)
    }

    @Test
    fun savedTabRoundTripsAndFallsBack() {
        assertEquals(MobileForm.Tab.FILES, MobileForm.tabFromName("FILES"))
        assertEquals(MobileForm.Tab.SESSION, MobileForm.tabFromName(null))
        assertEquals(MobileForm.Tab.SESSION, MobileForm.tabFromName("USAGE"))
    }

    @Test
    fun terminalTabNavigatesInsteadOfRenderingContent() {
        assertTrue(MobileForm.isContentTab(MobileForm.Tab.SESSION))
        assertTrue(MobileForm.isContentTab(MobileForm.Tab.CHANGES))
        assertTrue(MobileForm.isContentTab(MobileForm.Tab.FILES))
        assertFalse(MobileForm.isContentTab(MobileForm.Tab.TERMINAL))
    }

    // ------------------------------------------------------------ row long press

    @Test
    fun longPressTimeoutMatchesTheUpstreamConstant() {
        assertEquals(500, MobileForm.SESSION_ROW_LONG_PRESS_MS)
    }

    // -------------------------------------------------------- question dock pager

    @Test
    fun pagerClampsToTheQuestionList() {
        assertEquals(0, QuestionPager.clamp(0, 3))
        assertEquals(2, QuestionPager.clamp(2, 3))
        assertEquals(2, QuestionPager.clamp(9, 3))
        assertEquals(0, QuestionPager.clamp(-1, 3))
        assertEquals(0, QuestionPager.clamp(0, 0))
        assertEquals(0, QuestionPager.clamp(4, 0))
    }

    @Test
    fun pagerTracksFirstAndLastPage() {
        assertFalse(QuestionPager.canGoBack(0))
        assertTrue(QuestionPager.canGoBack(1))

        assertFalse(QuestionPager.isLast(0, 3))
        assertFalse(QuestionPager.isLast(1, 3))
        assertTrue(QuestionPager.isLast(2, 3))
        assertTrue(QuestionPager.isLast(0, 1))
    }

    @Test
    fun pagerWalksForwardAndBack() {
        assertEquals(1, QuestionPager.next(0, 3))
        assertEquals(2, QuestionPager.next(2, 3))
        assertEquals(0, QuestionPager.back(1, 3))
        assertEquals(0, QuestionPager.back(0, 3))
    }

    @Test
    fun progressHeaderIsOneBasedAndHiddenForSingleQuestions() {
        assertEquals(1, QuestionPager.progressNumber(0, 3))
        assertEquals(3, QuestionPager.progressNumber(2, 3))
        // Out-of-range tabs still report a sane number.
        assertEquals(3, QuestionPager.progressNumber(7, 3))
        assertEquals(1, QuestionPager.progressNumber(0, 0))

        assertFalse(QuestionPager.showProgress(0))
        assertFalse(QuestionPager.showProgress(1))
        assertTrue(QuestionPager.showProgress(2))
    }

    @Test
    fun segmentsMarkActiveAndAnsweredPages() {
        val segments = QuestionPager.segments(tab = 1, answered = listOf(true, false, true, false))
        assertEquals(1, segments.active)
        assertEquals(setOf(0, 2), segments.answered)

        val clamped = QuestionPager.segments(tab = 99, answered = listOf(false, false))
        assertEquals(1, clamped.active)
        assertEquals(emptySet<Int>(), clamped.answered)
    }

    @Test
    fun answeredQuestionNeedsAPickOrCustomText() {
        assertFalse(QuestionPager.answered(emptyList(), customActive = false, customText = ""))
        assertTrue(QuestionPager.answered(listOf("a"), customActive = false, customText = ""))
        assertTrue(QuestionPager.answered(emptyList(), customActive = true, customText = "other"))
        // Custom mode on but still empty = not answered (upstream `customUpdate`).
        assertFalse(QuestionPager.answered(emptyList(), customActive = true, customText = "   "))
    }

    // ---------------------------------------------------------- usage view rows

    @Test
    fun usagePercentIsClampedLikeUptream() {
        assertEquals("—", MobileUsage.percent(null))
        assertEquals("0%", MobileUsage.percent(0))
        assertEquals("62%", MobileUsage.percent(62))
        assertEquals("100%", MobileUsage.percent(100))
        assertEquals("100%", MobileUsage.percent(140))
        assertEquals("0%", MobileUsage.percent(-5))
    }

    @Test
    fun usageTokensShowTheModelWindowWhenKnown() {
        assertEquals("—", MobileUsage.tokens(null, null))
        assertEquals("1,234", MobileUsage.tokens(1_234L, null))
        assertEquals("1,234", MobileUsage.tokens(1_234L, 0))
        assertEquals("1,234 / 200,000", MobileUsage.tokens(1_234L, 200_000))
    }

    @Test
    fun usageCostKeepsFourDecimals() {
        assertEquals("—", MobileUsage.cost(null))
        assertEquals("$0.0123", MobileUsage.cost(0.0123))
        assertEquals("$12.5000", MobileUsage.cost(12.5))
    }
}
