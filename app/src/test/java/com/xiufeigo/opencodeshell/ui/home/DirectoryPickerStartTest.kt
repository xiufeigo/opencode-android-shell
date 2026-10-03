package com.xiufeigo.opencodeshell.ui.home

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picker start-path adoption ([resolvePickerStart]) and the select-confirm
 * tail split ([confirmPathTail]): the first open must prefer the browse home
 * over `/`, including the retry after a credential/probe window parked it on
 * the root fallback — without ever yanking a path the user chose.
 *
 * The other half is the **system-start guard**: no entry, and no probe answer,
 * may leave the browser on `C:\Windows\System32` (see [pickerEntryStart]).
 */
class DirectoryPickerStartTest {

    @Test
    fun freshOpenAdoptsHome() {
        assertEquals(
            "/home/xiufeigo",
            resolvePickerStart("", "", false, "/home/xiufeigo", "/"),
        )
    }

    @Test
    fun freshOpenFallsBackToRootWithoutHome() {
        assertEquals("/", resolvePickerStart("", "", false, null, "/"))
    }

    @Test
    fun stuckRootAdoptsHomeOnceItArrives() {
        assertEquals(
            "/home/xiufeigo",
            resolvePickerStart("/", "/", false, "/home/xiufeigo", "/"),
        )
    }

    @Test
    fun userNavigatedPathIsKept() {
        assertNull(resolvePickerStart("/", "/", true, "/home/xiufeigo", "/"))
        assertNull(resolvePickerStart("", "", true, "/home/xiufeigo", "/"))
    }

    @Test
    fun chosenPathIsKept() {
        assertNull(resolvePickerStart("/home/xiufeigo/.agents", "/", false, "/home/xiufeigo", "/"))
    }

    @Test
    fun samePathIsNoOp() {
        assertNull(resolvePickerStart("/home/xiufeigo", "/", false, "/home/xiufeigo", "/"))
    }

    @Test
    fun blankStartIsNoOp() {
        assertNull(resolvePickerStart("", "", false, null, ""))
    }

    @Test
    fun confirmTailSplitsLastSegment() {
        assertEquals("/home/xiufeigo/".length, confirmPathTail("/home/xiufeigo/.agents"))
        assertEquals(0, confirmPathTail("/"))
        assertEquals(0, confirmPathTail(""))
        assertEquals("C:/".length, confirmPathTail("C:/proj"))
    }

    @Test
    fun rememberedStartReopensLastDirectory() {
        assertEquals("/home/xiufeigo/repo", pickerRememberedStart("/home/xiufeigo/repo"))
        assertEquals("C:/repo", pickerRememberedStart("  C:/repo  "))
    }

    @Test
    fun rememberedStartWithoutMemoryIsNull() {
        assertNull(pickerRememberedStart(null))
        assertNull(pickerRememberedStart(""))
        assertNull(pickerRememberedStart("   "))
    }

    @Test
    fun rememberedStartRefusesSystemDirectory() {
        assertNull(pickerRememberedStart("C:\\Windows\\System32"))
        assertNull(pickerRememberedStart("C:/Windows"))
        assertNull(pickerRememberedStart("c:/windows/syswow64"))
    }

    // ---- the entry guard: no start parks the browser on System32 -----------

    @Test
    fun aSystemStartIsRerootedOnEntry() {
        assertEquals("C:/", pickerEntryStart("""C:\Windows\System32"""))
        assertEquals("C:/", pickerEntryStart("C:/Windows"))
        assertEquals("C:/", pickerEntryStart("c:/WINDOWS/SysWOW64/cmd.exe"))
        assertEquals("D:/", pickerEntryStart("""D:\Windows\Temp"""))
    }

    @Test
    fun everyOtherEntryStartsVerbatim() {
        assertEquals("/home/xiufeigo/repo", pickerEntryStart("/home/xiufeigo/repo"))
        assertEquals("C:/Users/xiufe", pickerEntryStart("  C:/Users/xiufe  "))
        // No drive names no Windows directory: `/Windows` on a POSIX server is an
        // ordinary directory, and only the probe callback — which knows the root
        // — may judge the drive-less spelling.
        assertEquals("/Windows/System32", pickerEntryStart("/Windows/System32"))
        assertEquals("", pickerEntryStart(null))
        assertEquals("", pickerEntryStart("   "))
    }

    @Test
    fun aNonEmptySystemStartIsRefusedByTheProbeAnswerToo() {
        // The 移动到… hole: `SessionActionsDialogs.MoveSurface` hands over the
        // session's own directory, so `currentPath` is non-empty and the old
        // early return (`currentPath != previousRoot`) swallowed the guard —
        // System32 listed in the body and named in the footer all session.
        assertEquals(
            "C:/",
            resolvePickerStart("""C:\Windows\System32""", "", false, "/home/xiufeigo", "C:/"),
        )
        assertEquals("C:/", resolvePickerStart("C:/Windows", "", false, null, "C:/"))
        // …and the same when the stuck path *is* the probed root.
        assertEquals("C:/", resolvePickerStart("""C:\Windows""", """C:\Windows""", false, null, "C:/"))
    }

    @Test
    fun aDriveLessSystemStartIsJudgedAgainstTheProbedRoot() {
        // `/Windows/System32` is the drive-less spelling of `C:\Windows\System32`
        // (the v36 BUG-2 shape), and the callback is where a root exists to read
        // it with.
        assertEquals("C:/", resolvePickerStart("/Windows/System32", "", false, null, "C:/"))
        assertEquals("C:/", resolvePickerStart("/mnt/c/Windows/System32", "", false, null, "C:/"))
        // …and an ordinary directory when the root says POSIX.
        assertNull(resolvePickerStart("/Windows/System32", "", false, null, "/"))
    }

    @Test
    fun aSystemDirectoryTheUserNavigatedToIsKept() {
        assertNull(resolvePickerStart("""C:\Windows\System32""", "", true, null, "C:/"))
    }

    // ---- viewport fit: the footer must not fall behind the keyboard --------

    @Test
    fun theBodyKeepsItsFullHeightWhileTheWindowHasRoom() {
        // A 411×914 dp phone, keyboard down and keyboard up: the whole card fits
        // either way, so the browser body is exactly what it always was.
        assertEquals(320.dp, pickerBodyMax(914.dp, 0.dp))
        assertEquals(320.dp, pickerBodyMax(914.dp, 200.dp))
        assertEquals(320.dp, pickerBodyMax(914.dp, 300.dp))
        // …and so does the dropdown's own cap: 8 rows fit, so nothing is cut.
        assertEquals(260.dp, pickerSuggestionsMax(914.dp, 0.dp))
        assertEquals(260.dp, pickerSuggestionsMax(914.dp, 300.dp))
    }

    @Test
    fun aShortWindowTakesTheBodyInsteadOfTheFooter() {
        // 914 − 520 of IME − 240 of chrome: 154 dp of body, and 取消 /
        // 选择此目录 still land above the keyboard.
        assertEquals(154.dp, pickerBodyMax(914.dp, 520.dp))
        // A small landscape window with no keyboard at all.
        assertEquals(240.dp, pickerBodyMax(480.dp, 0.dp))
    }

    @Test
    fun theBodyFloorSurvivesAnImpossibleWindow() {
        // v51: a window that cannot hold the picker even after the covered strip
        // is discounted — a 250 dp keyboard on a 300 dp window, no keyboard at
        // all on a 0 dp one — now keeps the footer's rows and spends everything
        // else on the body. The 96 dp floor is what used to push the footer off
        // the card (`research/v51_verify/tE/v51_22_movedlg.png`).
        assertEquals(88.dp, pickerBodyMax(300.dp, 250.dp))
        assertEquals(0.dp, pickerBodyMax(0.dp, 0.dp))
        // …and the dropdown gets nothing rather than the body getting less than
        // its room.
        assertEquals(0.dp, pickerSuggestionsMax(300.dp, 250.dp))
    }

    @Test
    fun theSuggestionDropdownSharesTheBodysRoom() {
        // In flow, so an uncapped dropdown grows the card past the window and the
        // block that falls off is the path field being typed in. It takes what is
        // left over a two-row body, never more than its own natural height.
        assertEquals(260.dp, pickerSuggestionsMax(914.dp, 300.dp))
        assertEquals(114.dp, pickerBodyMax(914.dp, 300.dp, suggestionsOpen = true))
        // A tall IME leaves the dropdown 58 dp and the body exactly its floor.
        assertEquals(58.dp, pickerSuggestionsMax(914.dp, 520.dp))
        assertEquals(96.dp, pickerBodyMax(914.dp, 520.dp, suggestionsOpen = true))
        // A small window: the dropdown gives way first (144 dp = 4 rows).
        assertEquals(144.dp, pickerSuggestionsMax(480.dp, 0.dp))
        assertEquals(96.dp, pickerBodyMax(480.dp, 0.dp, suggestionsOpen = true))
    }

    @Test
    fun theCappedCardFitsTheAreaTheKeyboardLeaves() {
        // chrome + body (+ the dropdown's share) ≤ visible window: the footer is
        // the card's last block and must never be the one that falls off.
        val chrome = 240.dp
        for (ime in listOf(0.dp, 240.dp, 300.dp, 400.dp, 520.dp)) {
            val window = 914.dp
            val visible = window - ime
            val body = pickerBodyMax(window, ime)
            assertTrue(
                "chrome + body must fit the $visible dp left by a $ime dp keyboard",
                body + chrome <= visible,
            )
            val open = pickerBodyMax(window, ime, suggestionsOpen = true)
            val dropdown = pickerSuggestionsMax(window, ime)
            assertTrue(
                "chrome + body + dropdown must fit the $visible dp too",
                open + dropdown + chrome <= visible,
            )
        }
    }
}
