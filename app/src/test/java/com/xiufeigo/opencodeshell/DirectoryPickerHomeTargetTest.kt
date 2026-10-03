package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.FsBrowse
import com.xiufeigo.opencodeshell.ui.home.pickerHomeEnabled
import com.xiufeigo.opencodeshell.ui.home.pickerHomeTarget
import com.xiufeigo.opencodeshell.ui.home.pickerSamePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 「~」 breadcrumb's two contracts, pinned as pure functions.
 *
 * v57 (`research/v57_verify/E2ECHK5560/13-16`, emulator-5560): the browser on
 * `/home` rendered `~` as if it could go somewhere and the tap did nothing.
 * Two separate gaps, both here:
 * - `/home` names no user segment, so the target could not be derived from the
 *   path on screen — the home the session had already seen (`/home/xiufeigo`)
 *   is what has to answer, otherwise `~` is dead on the one directory that is
 *   a single step from home;
 * - a target equal to the path already on screen is a dead tap wearing a live
 *   button's colours and must grey out like any other.
 */
class DirectoryPickerHomeTargetTest {

    /** The home a session already named stays the target off paths that derive none. */
    @Test
    fun oneLevelAboveHomeTheRememberedTargetStillAnswers() {
        assertEquals("/home/xiufeigo", pickerHomeTarget("/home", null, "/home/xiufeigo"))
        // The probed location naming no home does not change that …
        assertEquals("/home/xiufeigo", pickerHomeTarget("/home", "/mnt/c/x", "/home/xiufeigo"))
        // … and a deeper path still derives its own home ahead of the remembered one.
        assertEquals("/home/other", pickerHomeTarget("/home/other/proj", null, "/home/xiufeigo"))
    }

    /** With nothing ever named a home there is still no target — no invented one. */
    @Test
    fun theRememberedTargetIsNeverInvented() {
        assertNull(pickerHomeTarget("/home", null))
        assertNull(pickerHomeTarget("/", null))
        assertNull(pickerHomeTarget("/home", "/", "  "))
        assertNull(pickerHomeTarget(null, null, null))
        assertNull(pickerHomeTarget("/srv/app", "/srv/app", "/srv/app"))
    }

    /** `~` goes home from anywhere below it, including the root and one level above home. */
    @Test
    fun theTargetIsTheRealHomeFromEveryDirectoryBelowIt() {
        for (current in listOf("/", "/home", "/home/xiufeigo", "/home/xiufeigo/.codex", "/home/xiufeigo/a/b")) {
            assertEquals("/home/xiufeigo", pickerHomeTarget(current, null, "/home/xiufeigo"))
        }
    }

    /** Not at home → live; already home → greyed, exactly like a target nobody can name. */
    @Test
    fun theButtonIsLiveOnlyWhileThereIsSomewhereToGo() {
        assertFalse(pickerHomeEnabled(null, "/home"))
        assertFalse(pickerHomeEnabled("   ", "/home"))
        assertTrue(pickerHomeEnabled("/home/xiufeigo", "/home"))
        assertTrue(pickerHomeEnabled("/home/xiufeigo", "/"))
        assertTrue(pickerHomeEnabled("/home/xiufeigo", "/home/xiufeigo/.codex"))
        // Already home — trailing separator and casing aside, there is nowhere to go.
        assertFalse(pickerHomeEnabled("/home/xiufeigo", "/home/xiufeigo"))
        assertFalse(pickerHomeEnabled("/home/xiufeigo", "/home/xiufeigo/"))
        assertFalse(pickerHomeEnabled("/root", "/root"))
        assertFalse(pickerHomeEnabled("C:/Users/me", """C:\Users\me"""))
        // The drive level is not a path, so a named home is still worth jumping to.
        assertTrue(pickerHomeEnabled("C:/Users/me", "drives"))
    }

    /** Path identity used by that grey-out: canonical, separator- and case-insensitive on Windows. */
    @Test
    fun pathIdentityIsCanonical() {
        assertTrue(pickerSamePath("/home/xiufeigo", "/home/./xiufeigo/"))
        assertTrue(pickerSamePath("/home/xiufeigo", "/home/other/../xiufeigo"))
        assertFalse(pickerSamePath("/home/xiufeigo", "/home/xiufeigo2"))
        assertFalse(pickerSamePath("/home/xiufeigo", null))
        assertFalse(pickerSamePath("", "/"))
    }

    /** The whole tap end to end through the picker's own algebra, from `/home`. */
    @Test
    fun theTildeTapFromOneLevelAboveHomeLandsInHome() {
        val current = "/home"
        val target = pickerHomeTarget(current, "/mnt/c/x", "/home/xiufeigo")!!
        assertEquals("/home/xiufeigo", FsBrowse.resolveInput(target, target, current))
    }

    /**
     * v68 (emulator-5560, `research/v68_verify/5560/75_tilde.png`): a
     * WSL/Windows server cwd names no home in any source, so `~` sat grey and
     * dispatched nothing. The probed location answers as a last resort, so the
     * button jumps back to the session's base instead of staying a dead zone.
     */
    @Test
    fun withNoHomeAnywhereTheLocationAnswersAsLastResort() {
        assertEquals("/mnt/c/x", pickerHomeTarget("/", "/mnt/c/x", null, fallback = "/mnt/c/x"))
        assertEquals("/mnt/c/x", pickerHomeTarget("/home", "/mnt/c/x", null, fallback = "/mnt/c/x"))
        // Blank fallback keeps the old contract: no target is invented.
        assertNull(pickerHomeTarget("/", "/mnt/c/x", null, fallback = "  "))
        assertNull(pickerHomeTarget("/", null, null))
    }

    /** A derived home always outranks the fallback; the remembered one included. */
    @Test
    fun aDerivedHomeOutranksTheFallback() {
        assertEquals("/home/xiufeigo", pickerHomeTarget("/home", "/mnt/c/x", "/home/xiufeigo", fallback = "/mnt/c/x"))
        assertEquals("/home/xiufeigo", pickerHomeTarget("/home/xiufeigo/proj", "/mnt/c/x", null, fallback = "/mnt/c/x"))
        assertEquals("/home/xiufeigo", pickerHomeTarget("/", "/home/xiufeigo", null, fallback = "/mnt/c/x"))
    }

    /** Already on the fallback — greyed, exactly like already home (no v32 dead tap). */
    @Test
    fun theButtonGreysOutWhileAlreadyOnTheFallback() {
        assertFalse(pickerHomeEnabled("/mnt/c/x", "/mnt/c/x"))
        assertTrue(pickerHomeEnabled("/mnt/c/x", "/"))
        // A `~` typed on a homeless server expands to the fallback, not to a
        // literal `~` subdirectory that only errors.
        assertEquals("/mnt/c/x", FsBrowse.resolveInput("~", "/mnt/c/x", "/"))
    }
}
