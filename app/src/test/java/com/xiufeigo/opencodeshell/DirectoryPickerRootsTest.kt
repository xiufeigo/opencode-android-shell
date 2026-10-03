package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.DRIVES_LEVEL
import com.xiufeigo.opencodeshell.ui.home.FsBrowse
import com.xiufeigo.opencodeshell.ui.home.PickerUp
import com.xiufeigo.opencodeshell.ui.home.browseRoots
import com.xiufeigo.opencodeshell.ui.home.driveEntries
import com.xiufeigo.opencodeshell.ui.home.driveLabel
import com.xiufeigo.opencodeshell.ui.home.isSystemStart
import com.xiufeigo.opencodeshell.ui.home.pickerHomeTarget
import com.xiufeigo.opencodeshell.ui.home.pickerTrueRoot
import com.xiufeigo.opencodeshell.ui.home.pickerUp
import com.xiufeigo.opencodeshell.ui.home.resolvePickerStart
import com.xiufeigo.opencodeshell.ui.home.showBrowseLockHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The P1 directory-picker unlock: the probed browse home/root must never act
 * as a navigation ceiling. The 「根目录」 button targets the true filesystem
 * root derived client-side ([pickerTrueRoot]); above a Windows drive root the
 * walk opens a drive level ([browseRoots]/[driveEntries]) instead of
 * dead-ending ([pickerUp]); the adopted start refuses a Windows system
 * directory such as the server launch cwd `C:\Windows\System32`
 * ([resolvePickerStart]/[isSystemStart]); and the v1 lock hint never shows on
 * an unlocked transport ([showBrowseLockHint]).
 */
class DirectoryPickerRootsTest {

    // ---- root set derivation ----

    @Test
    fun windowsSamplesDeriveTheirDriveRoot() {
        assertEquals(
            listOf("C:/"),
            browseRoots(home = """C:\Users\xiufe""", current = """C:\Windows\System32""", probedRoot = "C:/"),
        )
    }

    @Test
    fun distinctDrivesAreAllListedSorted() {
        assertEquals(
            listOf("C:/", "D:/"),
            browseRoots(home = """C:\Users\xiufe""", current = "D:/data", probedRoot = "C:/"),
        )
    }

    @Test
    fun driveLettersNormaliseCase() {
        assertEquals(
            listOf("C:/"),
            browseRoots(home = """c:\users\xiufe""", current = "", probedRoot = ""),
        )
    }

    @Test
    fun probedCeilingNeverSurvivesAsARoot() {
        // v1-style probe where root == home (the start directory): neither is
        // a filesystem root, so the set falls back to `/`, not the ceiling.
        assertEquals(listOf("/"), browseRoots(home = "/home/xiufe", current = "/home/xiufe", probedRoot = "/home/xiufe"))
    }

    @Test
    fun posixSamplesYieldSlash() {
        assertEquals(listOf("/"), browseRoots(home = "/home/xiufe", current = "/home/xiufe/proj", probedRoot = "/"))
    }

    @Test
    fun uncShareRootIsKept() {
        assertEquals(
            listOf("//host/share"),
            browseRoots(home = "//host/share/users/x", current = "", probedRoot = ""),
        )
    }

    // ---- true root target ----

    @Test
    fun rootButtonTargetsTheDriveOfTheCurrentPath() {
        assertEquals("C:/", pickerTrueRoot("""C:\Windows\System32""", "C:/"))
        assertEquals("D:/", pickerTrueRoot("D:/data/proj", "C:/"))
    }

    @Test
    fun rootButtonTargetsSlashOnPosix() {
        assertEquals("/", pickerTrueRoot("/home/xiufe/proj", "/"))
    }

    @Test
    fun rootButtonFallsBackToTheProbedRootWhenEmpty() {
        assertEquals("C:/", pickerTrueRoot("", "C:/"))
    }

    // ---- upward navigation boundary ----

    @Test
    fun unlockedWalkRunsToTheDriveRoot() {
        val up = pickerUp("""C:\Windows\System32""", escapeAllowed = true) { true }
        assertEquals(PickerUp.Parent("""C:\Windows"""), up)
    }

    @Test
    fun unlockedWalkRunsToSlash() {
        val up = pickerUp("/home/xiufe/proj", escapeAllowed = true) { true }
        assertEquals(PickerUp.Parent("/home/xiufe"), up)
    }

    @Test
    fun aboveADriveRootOpensTheDriveLevel() {
        assertEquals(PickerUp.Drives, pickerUp("C:/", escapeAllowed = true) { true })
        assertEquals(PickerUp.Drives, pickerUp("""C:\""", escapeAllowed = true) { true })
    }

    @Test
    fun slashItselfHasNowhereToGo() {
        assertEquals(PickerUp.None, pickerUp("/", escapeAllowed = true) { true })
    }

    @Test
    fun uncShareRootHasNowhereToGo() {
        assertEquals(PickerUp.None, pickerUp("//host/share", escapeAllowed = true) { true })
    }

    @Test
    fun lockedTransportKeepsTheV1Gate() {
        // v1 outside the server cwd: the button must not promise the walk.
        assertEquals(PickerUp.None, pickerUp("""C:\Windows""", escapeAllowed = false) { false })
        // ... but still crosses inside it.
        assertEquals(
            PickerUp.Parent("""C:\srv"""),
            pickerUp("""C:\srv\proj""", escapeAllowed = false) { true },
        )
    }

    @Test
    fun lockedDriveRootStaysPut() {
        assertEquals(PickerUp.None, pickerUp("C:/", escapeAllowed = false) { true })
    }

    @Test
    fun driveLevelAndEmptyHaveNoUpStep() {
        assertEquals(PickerUp.None, pickerUp(DRIVES_LEVEL, escapeAllowed = true) { true })
        assertEquals(PickerUp.None, pickerUp("", escapeAllowed = true) { true })
    }

    // ---- drive rows ----

    @Test
    fun driveRowsNavigateToRoots() {
        val rows = driveEntries(listOf("C:/", "D:/"))
        assertEquals(listOf("C:/", "D:/"), rows.map { it.absolute })
        assertEquals(listOf("C:\\", "D:\\"), rows.map { it.name })
        assertTrue(rows.all { it.isDirectory })
    }

    @Test
    fun driveLabelReadsNativeForDrives() {
        assertEquals("C:\\", driveLabel("C:/"))
        assertEquals("//host/share", driveLabel("//host/share"))
    }

    // ---- default start ----

    @Test
    fun system32HomeFallsBackToTheDriveRoot() {
        assertEquals(
            "C:/",
            resolvePickerStart("", "", userNavigated = false, home = """C:\Windows\System32""", root = "C:/"),
        )
    }

    @Test
    fun windowsDirHomeFallsBackToTheDriveRoot() {
        assertEquals(
            "C:/",
            resolvePickerStart("", "", userNavigated = false, home = """C:\Windows""", root = "C:/"),
        )
    }

    @Test
    fun systemStartMatchesCaseInsensitively() {
        assertTrue(isSystemStart("""c:\WINDOWS\system32"""))
        assertTrue(isSystemStart("C:/Windows/SysWOW64/cmd.exe"))
        assertFalse(isSystemStart("C:/Users/xiufe"))
        assertFalse(isSystemStart("C:/"))
        assertFalse(isSystemStart("/usr/bin"))
        assertFalse(isSystemStart(null))
    }

    @Test
    fun saneHomeIsStillAdopted() {
        assertEquals(
            "/home/xiufe",
            resolvePickerStart("", "", userNavigated = false, home = "/home/xiufe", root = "/"),
        )
        assertEquals(
            """C:\Users\xiufe""",
            resolvePickerStart("", "", userNavigated = false, home = """C:\Users\xiufe""", root = "C:/"),
        )
    }

    @Test
    fun explicitStartSurvivesTheConstraintsCallback() {
        assertNull(
            resolvePickerStart(
                """/home/xiufe/proj""", "/", userNavigated = false, home = "/home/xiufe", root = "/",
            ),
        )
        assertNull(
            resolvePickerStart("", "/", userNavigated = true, home = "/home/xiufe", root = "/"),
        )
    }

    // ---- the `~` target (v32 BUG-2, v33 BUG-1) ----

    @Test
    fun tildeTargetsTheHomeDerivedFromThePathOnScreen() {
        // `research/v32_verify/tB/18_picker_home.png`: the picker sat on
        // `/home/xiufeigo/v11diff` and `~` did nothing, because it navigated to
        // `browseHome` — the very same path on v2.
        //
        // v33 BUG-1 (`research/v33_verify/tA/06,07`): the location is the
        // **server process cwd** (measured
        // `/mnt/c/sensorsdata/main/program/opencode-android-shell`, opencode
        // serve launched from a WSL mount), which names no home at all — so the
        // target fell back to the location itself and `~` jumped from
        // `/home/xiufeigo/v32projA` to that cwd. The path on screen is the
        // honest source.
        assertEquals("/home/xiufeigo", pickerHomeTarget("/home/xiufeigo/v32projA", null))
        assertEquals("/home/xiufeigo", pickerHomeTarget("/home/xiufeigo", null))
        assertEquals("/home/xiufeigo", pickerHomeTarget("/home/xiufeigo/", null))
    }

    @Test
    fun tildeReadsWindowsAndWslHomesFromThePathOnScreen() {
        assertEquals("C:/Users/me", pickerHomeTarget("""C:\Users\me\proj""", null))
        assertEquals("/mnt/c/Users/me", pickerHomeTarget("/mnt/c/Users/me/proj", null))
        assertEquals("/Users/me", pickerHomeTarget("/Users/me/proj", null))
        assertEquals("/root", pickerHomeTarget("/root", null))
    }

    @Test
    fun tildeFallsBackToTheLocationOnlyWhenThePathNamesNoHome() {
        // A browser parked on a directory that names no home (a WSL mount) can
        // still use the project's own home …
        assertEquals(
            "/home/xiufeigo",
            pickerHomeTarget("/mnt/c/sensorsdata/main/program/opencode-android-shell", "/home/xiufeigo/proj"),
        )
        // … but the location is never the target itself. This is the measured
        // v33 BUG-1 pair, where `~` jumped to the server cwd; nothing derivable
        // means a disabled button, not a no-op tap.
        assertNull(
            pickerHomeTarget(
                "/mnt/c/sensorsdata/main/program/opencode-android-shell",
                "/mnt/c/sensorsdata/main/program/opencode-android-shell",
            ),
        )
        assertNull(pickerHomeTarget("/srv/app", "/srv/app"))
        assertNull(pickerHomeTarget(null, null))
        assertNull(pickerHomeTarget("", ""))
        assertNull(pickerHomeTarget("   ", "   "))
    }

    @Test
    fun thePathOnScreenOutranksTheLocation() {
        assertEquals("/home/xiufeigo", pickerHomeTarget("/home/xiufeigo/v32projA", "/home/other/proj"))
    }

    @Test
    fun theTildeTapResolvesOffThePathOnScreen() {
        // End to end through the picker's own algebra (`navigate` →
        // `FsBrowse.resolveInput`) with the measured v33 state: the browser on
        // `/home/xiufeigo/v32projA`, the location the server cwd on a WSL mount.
        val location = "/mnt/c/sensorsdata/main/program/opencode-android-shell"
        val current = "/home/xiufeigo/v32projA"
        val target = pickerHomeTarget(current, location)!!
        val resolved = FsBrowse.resolveInput(target, target, current)
        assertEquals("/home/xiufeigo", resolved)
        assertNotEquals(current, resolved)
        // The pre-v33 algebra, for contrast: the old raw-location fallback made
        // the same tap land on the cwd.
        assertEquals(location, FsBrowse.resolveInput(location, location, current))
    }

    @Test
    fun aTildePrefixedTypedPathExpandsOffThePathOnScreen() {
        // The input box resolves `~/x` with the same `homeTarget` the button
        // uses (`navigate` → `FsBrowse.resolveInput(raw, homeTarget, base)`), so
        // the two cannot disagree about what `~` means.
        val home = pickerHomeTarget(
            "/home/xiufeigo/v32projA",
            "/mnt/c/sensorsdata/main/program/opencode-android-shell",
        )
        assertEquals("/home/xiufeigo/x", FsBrowse.resolveInput("~/x", home, "/home/xiufeigo/v32projA"))
    }

    // ---- lock hint ----

    @Test
    fun unlockedTransportNeverShowsTheLockHint() {
        // Even parked on the probed root with a failed listing: on v2 the
        // root-equals-start shape is a probe artifact, not a ceiling.
        assertFalse(showBrowseLockHint(escapeAllowed = true, parentAllowed = false, failed = true))
        assertFalse(showBrowseLockHint(escapeAllowed = true, parentAllowed = true, failed = false))
    }

    @Test
    fun lockedTransportShowsTheHintOnlyWhenStuckOrFailed() {
        assertTrue(showBrowseLockHint(escapeAllowed = false, parentAllowed = false, failed = false))
        assertTrue(showBrowseLockHint(escapeAllowed = false, parentAllowed = true, failed = true))
        assertFalse(showBrowseLockHint(escapeAllowed = false, parentAllowed = true, failed = false))
    }
}
