package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.nav.ViewportWidth
import com.xiufeigo.opencodeshell.ui.session.drawer.MobileForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1366x768 field triage: `MobileForm.isDesktop` is exact, so a mobile branch
 * on that display means `Configuration.screenWidthDp` (window width) stayed
 * below 768 — `wm size` without a matching `wm density` keeps the old dpi
 * and shrinks the dp width (`dp = px * 160 / dpi`).
 */
class ViewportWidthTest {

    @Test
    fun breakpointIsExactAtTheReportedWidth() {
        assertTrue(MobileForm.isDesktop(1366))
    }

    @Test
    fun displayPxConvertsLikeConfiguration() {
        assertEquals(1366, ViewportWidth.dpForPx(1366, 160))
        assertEquals(683, ViewportWidth.dpForPx(1366, 320))
        assertEquals(1280, ViewportWidth.dpForPx(2560, 320))
        assertEquals(443, ViewportWidth.dpForPx(1080, 390))
    }

    @Test
    fun staleDensityKeepsTheMobileBranch() {
        assertTrue(ViewportWidth.isDesktopDisplay(1366, 160))
        assertFalse(ViewportWidth.isDesktopDisplay(1366, 320))
        assertFalse(ViewportWidth.isDesktopDisplay(1366, 440))
    }

    @Test(expected = IllegalArgumentException::class)
    fun densityMustBePositive() {
        ViewportWidth.dpForPx(1366, 0)
    }
}
