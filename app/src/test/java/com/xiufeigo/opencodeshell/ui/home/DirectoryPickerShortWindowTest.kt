package com.xiufeigo.opencodeshell.ui.home

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v51, the two picker bugs the 移动到… run measured on a 1440×810 @420 dpi
 * window (308 dp of height — a window that is short by itself, no keyboard).
 *
 * - **The dead end** (`research/v51_verify/tE/v51_22_movedlg.png` …
 *   `v51_30_selrow.png`): the whole window showed the path field, the nav row,
 *   the resolved-path line and a ✕. The browser body measured `[0,0][0,0]` and
 *   the footer (取消 / 选择此目录) was clipped off the card — the dialog could
 *   browse nothing and submit nothing. The same dialog is fine at 1344×2992,
 *   which is what makes it a *budget* bug and not a data one: what differs is
 *   only how much of the fixed rows the window can pay for.
 * - **The path field** (`research/v51_verify/tD/v51_23_typed2.png`): select-all
 *   + DEL did nothing (the field refilled itself from the browsed path), so
 *   every retype appended to the old text — `…/v36projC/mnt`, then
 *   `/mnt/mnt/c/…` — and a drive letter typed without its colon resolved
 *   against the browsed directory instead of against the drive.
 */
class DirectoryPickerShortWindowTest {

    /** Card padding + header + path input + nav row + footer. */
    private val footerRows = 184.dp

    /** The resolved-path line, always reserved like the body. */
    private val resolvedLine = 17.dp

    // ---- the dead end: the footer survives every window height -------------

    @Test
    fun theFooterRowsFitInTheTallWindowsToo() {
        for (window in listOf(308.dp, 400.dp, 623.dp, 914.dp, 1200.dp)) {
            for (covered in listOf(0.dp, 24.dp, 145.dp, 300.dp)) {
                val card = pickerCardMax(window, covered)
                assertTrue(
                    "card $card for a ${window.value}dp window minus $covered must hold its footer rows",
                    card >= footerRows,
                )
                val body = pickerBodyMax(window, covered)
                assertTrue(
                    "body $body must not push the footer off a ${window.value}dp card ($card)",
                    footerRows + resolvedLine + body <= card,
                )
            }
        }
    }

    @Test
    fun theV51WindowShowsAListAndTheFooter() {
        // The measured window: 1440×810 @420 dpi = 308 dp, with whatever strip the
        // insets reported covered (145 dp here — no keyboard was on screen). The
        // browser body has to stay browsable — the dead end was a body of zero
        // height — and the footer must stay on card.
        val card = pickerCardMax(308.dp, imeBottom = 145.dp)
        assertEquals(289.dp, card)
        assertEquals(88.dp, pickerBodyMax(308.dp, 145.dp))
        assertEquals(96.dp, pickerBodyMax(308.dp, 0.dp))
    }

    @Test
    fun anUnbelievableInsetCannotEatTheFooter() {
        // v51 BUG: the covered strip was believed even when it left less room
        // than the card's own fixed rows, so the card was capped *below* the
        // footer and clipped it. The usable viewport wins over the inset.
        val card = pickerCardMax(308.dp, imeBottom = 290.dp)
        assertEquals(289.dp, card)
        assertEquals(88.dp, pickerBodyMax(308.dp, 290.dp))
        // A believable keyboard still shrinks the card exactly as v40 measured.
        assertEquals(606.dp, pickerCardMax(914.dp, imeBottom = 300.dp))
        assertEquals(320.dp, pickerBodyMax(914.dp, 300.dp))
    }

    @Test
    fun anOptionalRowGivesWayBeforeTheFooter() {
        // The v1 lock hint only shows on a locked transport and only when it
        // fits under the body; the footer is not optional, so it never does.
        val card = pickerCardMax(700.dp, 0.dp)
        val body = pickerBodyMax(700.dp, 0.dp)
        assertTrue(pickerOptionalFits(card, body, 24.dp))
        // A body that already eats the room: the hint goes, the footer stays.
        val tightCard = pickerCardMax(308.dp, 0.dp)
        val tightBody = pickerBodyMax(308.dp, 0.dp)
        assertTrue(tightBody > 0.dp)
        assertFalse(pickerOptionalFits(tightCard, tightBody, 24.dp))
        assertTrue(
            "the hint's row must never be spent out of the footer's",
            footerRows + tightBody <= tightCard,
        )
    }

    @Test
    fun theSuggestionDropdownIsPaidForOutOfTheBodyFirst() {
        // The dropdown is in flow above the footer, so it may not eat the space
        // the footer needs either.
        val card = pickerCardMax(914.dp, 300.dp)
        val dropdown = pickerSuggestionsMax(914.dp, 300.dp)
        val body = pickerBodyMax(914.dp, 300.dp, suggestionsOpen = true)
        assertTrue(dropdown + footerRows + resolvedLine + body <= card)
    }

    // ---- the path field: emptying it stays emptied -----------------------

    @Test
    fun anEmptiedFieldIsNotRefilledFromTheBrowsedPath() {
        // select-all + DEL used to be a no-op: the field went blank and the very
        // same frame put the browsed path back, so the old path could only be
        // deleted character by character.
        assertEquals("", pickerConfirmPath(null, "/home/xiufeigo/projA", "", edited = true))
        // Untouched, the field still names the browsed path.
        assertEquals("/home/xiufeigo/projA", pickerConfirmPath(null, "/home/xiufeigo/projA", ""))
        // An armed confirm outranks an emptied field.
        assertEquals(
            "/home/xiufeigo/projB",
            pickerConfirmPath("/home/xiufeigo/projB", "/home/xiufeigo/projA", "", edited = true),
        )
        assertEquals("/typed", pickerConfirmPath(null, "/current", "/typed", edited = true))
    }

    // ---- a drive letter typed without its colon --------------------------

    @Test
    fun aDriveLetterResolvesToTheDriveNotToTheBrowsedDirectory() {
        // v51 `tD_23`: `c/sensorsdata/…` read as a relative path and joined onto
        // whatever the browser was on, which from anywhere but `/mnt` names a
        // directory the server cannot read.
        assertEquals(
            "/mnt/c/sensorsdata/main/program/v36projC",
            pickerResolveInput("c/sensorsdata/main/program/v36projC", null, "/mnt"),
        )
        assertEquals(
            "/mnt/c/sensorsdata/main/program/v36projC",
            pickerResolveInput(
                "c/sensorsdata/main/program/v36projC",
                null,
                "/mnt/c/sensorsdata/main/program/opencode-android-shell",
            ),
        )
        assertEquals(
            "/cygdrive/c/Users/xiufe",
            pickerResolveInput("c/Users/xiufe", null, "/cygdrive/c/work"),
        )
    }

    @Test
    fun theSameInputTwiceLandsOnTheSameDirectory() {
        // The compounding the field bug made visible: a second attempt onto the
        // first attempt's result grew `/mnt`, then `/mnt/mnt`.
        val first = pickerResolveInput("c/sensorsdata/main/program/v36projC", null, "/mnt")
        val second = pickerResolveInput("c/sensorsdata/main/program/v36projC", null, first)
        assertEquals(first, second)
    }

    @Test
    fun aDriveLetterOnAWindowsLocationKeepsTheDriveSpelling() {
        assertEquals("C:/sensorsdata/app", pickerResolveInput("c/sensorsdata/app", null, "C:/"))
        assertEquals("C:/sensorsdata/app", pickerResolveInput("C:/sensorsdata/app", null, "C:/"))
    }

    @Test
    fun everythingElseKeepsItsUpstreamReading() {
        // A base that names neither a drive nor a mount has no evidence for
        // either spelling, so the relative reading wins — no invented mount.
        assertEquals(
            "/home/xiufeigo/projA/c/sensorsdata",
            pickerResolveInput("c/sensorsdata", null, "/home/xiufeigo/projA"),
        )
        assertEquals(
            "/home/xiufeigo/projA/app/src",
            pickerResolveInput("app/src", "/home/xiufeigo", "/home/xiufeigo/projA"),
        )
        assertEquals(
            "/home/xiufeigo/proj",
            pickerResolveInput("~/proj", "/home/xiufeigo", "/mnt"),
        )
        assertEquals(
            "/home/xiufeigo/v36projB",
            pickerResolveInput("/home/xiufeigo/v36projB", null, "/mnt/c/sensorsdata"),
        )
    }

    @Test
    fun theArmedConfirmNamesTheDriveTheUserTyped() {
        // The armed/submitted target resolves through the same rule as the
        // navigation, so the confirm card can never name a different directory
        // than the one the field browsed to.
        assertEquals(
            "/mnt/c/sensorsdata/main/program/v36projC",
            pickerArmTarget(
                pathInput = "c/sensorsdata/main/program/v36projC",
                currentPath = "/mnt",
                browseRoot = "/",
                homeTarget = null,
            ),
        )
    }
}
