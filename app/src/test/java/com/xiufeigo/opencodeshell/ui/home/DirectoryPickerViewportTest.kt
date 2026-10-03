package com.xiufeigo.opencodeshell.ui.home

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v40 BUG-1 / BUG-2 / BUG-4: the geometry and the two disabled states the
 * device run measured.
 *
 * BUG-1 is 「目录锁死」: with the path field focused and ~300 dp of keyboard up,
 * the whole footer — the pending-path line, 取消 and 选择此目录 — was drawn under
 * the IME and gone from the uiautomator dump
 * (`research/v40_verify/tA/23_ime_try.png`, `24_ime_ui.xml`), so the browsed
 * directory could not be submitted at all. The card is now bounded by
 * [pickerCardMax] and the browser body absorbs the keyboard, so the footer
 * keeps its place above it.
 */
class DirectoryPickerViewportTest {

    @Test
    fun theCardNeverGrowsPastTheVisibleWindow() {
        val window = 700.dp
        // Keyboard down: the whole window minus the gutter, never more.
        assertEquals(window - 8.dp, pickerCardMax(window, imeBottom = 0.dp))
        // A 300 dp keyboard leaves 392 dp of card.
        assertEquals(392.dp, pickerCardMax(window, imeBottom = 300.dp))
    }

    @Test
    fun aCardTallerThanTheViewportIsStillBoundByTheBodyFloor() {
        // A keyboard that leaves less than a two-row browser must not produce a
        // zero/negative card: the body floor is the lower bound.
        assertTrue(pickerCardMax(300.dp, imeBottom = 290.dp) >= 96.dp)
    }

    @Test
    fun theKeyboardIsWhatShrinksTheBodyNotTheListing() {
        // Same window, keyboard down: the body is its full frame, so the footer
        // still cannot move between two frames of the same window (v30).
        assertEquals(320.dp, pickerBodyMax(700.dp, imeBottom = 0.dp))
        // Keyboard up: the body gives way to the chrome, never below the floor.
        val squeezed = pickerBodyMax(700.dp, imeBottom = 300.dp)
        assertTrue("body must shrink for the footer, was $squeezed", squeezed < 320.dp)
        assertTrue("body must stay browsable, was $squeezed", squeezed >= 96.dp)
    }

    @Test
    fun anOpenSuggestionDropdownTakesItsShareFromTheBodyFirst() {
        val withDropdown = pickerBodyMax(700.dp, imeBottom = 100.dp, suggestionsOpen = true)
        val without = pickerBodyMax(700.dp, imeBottom = 100.dp, suggestionsOpen = false)
        assertTrue("dropdown must not grow the card: $withDropdown", withDropdown <= without)
    }

    /**
     * v40 BUG-2: under 「目录不存在或无法读取」 the button was still accent-cyan
     * and tappable, so a directory the server cannot list was one tap away from
     * being added as a project. Upstream `dialog.tsx` disables it
     * (`disabled={!policy.result(...)}`).
     */
    @Test
    fun aFailedListingDisablesTheSelectButton() {
        assertFalse(pickerCanConfirm("/home/xiufeigo/frontend", atDrives = false, verifiedPath = null))
        assertTrue(
            pickerCanConfirm("/home/xiufeigo/frontend", atDrives = false, verifiedPath = "/home/xiufeigo/frontend"),
        )
    }

    @Test
    fun theDriveLevelAndAnEmptyPathStillSelectNothing() {
        assertFalse(pickerCanConfirm(DRIVES_LEVEL, atDrives = true, verifiedPath = "/"))
        assertFalse(pickerCanConfirm("", atDrives = false, verifiedPath = "/"))
    }

    /**
     * v40 BUG-4: a `~` that names no home was a dead tap that looked exactly
     * like a live one (two clicks, two byte-identical screenshots).
     */
    @Test
    fun theTildeButtonIsOnlyEnabledWithAHomeToJumpTo() {
        assertFalse(pickerHomeEnabled(null))
        assertFalse(pickerHomeEnabled("   "))
        assertTrue(pickerHomeEnabled("/home/xiufeigo"))
    }

    @Test
    fun aBlankProbeStillFallsBackToThePathTheBrowserIsOn() {
        assertEquals("/home/xiufeigo", pickerHomeTarget("/home/xiufeigo/frontend", "  "))
        assertEquals("/home/xiufeigo", pickerHomeTarget("/mnt/c/sensorsdata", "/home/xiufeigo"))
        // A WSL-mounted launch cwd names no home — the v33 BUG-1 case.
        assertEquals(null, pickerHomeTarget("/mnt/c/sensorsdata/main/program/opencode-android-shell", null))
    }
}
