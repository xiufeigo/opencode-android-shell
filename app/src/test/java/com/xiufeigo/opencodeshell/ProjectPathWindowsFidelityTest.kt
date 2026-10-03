package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.data.HomeProjectRecords
import com.xiufeigo.opencodeshell.data.ProjectPaths
import com.xiufeigo.opencodeshell.ui.home.FsBrowse
import com.xiufeigo.opencodeshell.ui.home.adoptPickerRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v36 BUG-2 — the add-project picker's path rewriting on a **Windows** host
 * (`research/v36_verify/tB/v36tb_bug_addproject.png`).
 *
 * Two symptoms, one root:
 *
 * 1. the picker's box (and the project row it wrote) held
 *    `/sensorsdata/main/program/opencode-android-shell/v36projB` for the
 *    directory `C:\sensorsdata\main\program\opencode-android-shell\v36projB` —
 *    **the drive gone and a bare leading `/` where it used to be**;
 * 2. tapping 「选择此目录」 for that path twice left **two project rows** for one
 *    directory, because the drive-less spelling is a different
 *    `ProjectPaths.matchKey` from the `C:\…` one the same directory already had.
 *
 * (1) is [FsBrowse.absolute] treating a rooted path as absolute whatever the
 * location's flavour is. On Windows a `/`-rooted path with no drive is
 * **drive-relative** (`/x` = `\x` on the current drive), so against a Windows
 * location it must resolve on that location's own root — never replace it. On a
 * POSIX location nothing changes.
 *
 * (2) is the store's identity: [ProjectPaths.storeKey] strips the volume from
 * both sides, so the drive-less spelling and the `C:\…` spelling are one
 * directory again — for membership, de-duplication, renames and the closed
 * tombstone — while [ProjectPaths.matchKey] (the session-scope filter's key)
 * stays verbatim and case-sensitive on POSIX.
 */
class ProjectPathWindowsFidelityTest {

    private val location = "C:\\sensorsdata\\main\\program\\opencode-android-shell"

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    // ------------------------------------------------ (1) the rewriting fidelity

    @Test
    fun aDriveLessRootedPathKeepsTheWindowsLocation() {
        // The exact value from the device run: the picker's location is a
        // Windows path and the answer it rewrote was rooted POSIX.
        assertEquals(
            "C:/sensorsdata/main/program/opencode-android-shell/v36projB",
            FsBrowse.absolute("/sensorsdata/main/program/opencode-android-shell/v36projB", location),
        )
        assertEquals("C:/sensorsdata/other", FsBrowse.absolute("/sensorsdata/other", location))
        // The drive root itself is drive-relative too: `/` on `C:` is `C:\`.
        assertEquals("C:/", FsBrowse.absolute("/", location))
        // A UNC location resolves on the share, not on a drive.
        assertEquals(
            "//host/share/sensorsdata/other",
            FsBrowse.absolute("/sensorsdata/other", "\\\\host\\share\\proj"),
        )
    }

    @Test
    fun aPosixLocationStillKeepsPosixRootedPaths() {
        // The rule is Windows-only: on a POSIX server `/sensorsdata/other` is
        // `/sensorsdata/other`, and the WSL-hosted device (whose location is
        // `/mnt/c/…`) must keep browsing `/mnt/c/…` exactly as before.
        assertEquals("/sensorsdata/other", FsBrowse.absolute("/sensorsdata/other", "/home/xiufeigo/proj"))
        assertEquals("/sensorsdata/other", FsBrowse.absolute("/sensorsdata/other", "/mnt/c/sensorsdata/proj"))
        assertEquals("/", FsBrowse.absolute("/", "/home/xiufeigo"))
        assertEquals("/sensorsdata/other", FsBrowse.absolute("/sensorsdata/other", ""))
        assertEquals("/sensorsdata/other", FsBrowse.absolute("/sensorsdata/other", "relative/base"))
    }

    @Test
    fun aWindowsAbsolutePathIsStillTakenVerbatim() {
        // A drive or a UNC head already names its volume: not drive-relative,
        // and never re-rooted onto the location.
        assertEquals(
            "C:/Windows/System32",
            FsBrowse.absolute("C:\\Windows\\System32", location),
        )
        assertEquals("D:/other", FsBrowse.absolute("D:/other", location))
        assertEquals("//host/share/x", FsBrowse.absolute("\\\\host\\share\\x", location))
    }

    @Test
    fun theMsysDriveSpellingFoldsToItsWin32TwinOnAWindowsLocation() {
        // `/mnt/c/…` and `/cygdrive/c/…` already *name* a drive, so against a
        // Windows location they are the Win32 directory — the same fold
        // `ProjectPaths.matchKey` applies — not a drive-relative descent into a
        // literal `mnt/c` directory.
        assertEquals("c:/sensorsdata", FsBrowse.absolute("/mnt/c/sensorsdata", location))
        assertEquals("c:/sensorsdata", FsBrowse.absolute("/cygdrive/c/sensorsdata", location))
        // On a POSIX location the fold must NOT fire: there `/mnt/c/…` is the
        // WSL mount the server really browses (the measured host this shell
        // talks to), so it stays verbatim.
        assertEquals("/mnt/c/sensorsdata", FsBrowse.absolute("/mnt/c/sensorsdata", "/mnt/c/sensorsdata/proj"))
        assertEquals("/mnt/c/sensorsdata", FsBrowse.absolute("/mnt/c/sensorsdata", "/home/xiufeigo"))
    }

    @Test
    fun theTypedPathInputInheritsTheFidelity() {
        // `navigate(pathInput)` → `resolveInput(raw, homeTarget, base)`: the same
        // rule, so a typed drive-less path against a Windows location lands on
        // the Windows directory instead of a phantom POSIX one.
        assertEquals(
            "C:/sensorsdata/main/program/opencode-android-shell/v36projB",
            FsBrowse.resolveInput("/sensorsdata/main/program/opencode-android-shell/v36projB", null, location),
        )
        // A relative input and a `~`-input are unchanged by the new rule.
        assertEquals("C:/sensorsdata/main/program/opencode-android-shell/nested", FsBrowse.resolveInput("nested", null, location))
        assertEquals("C:/Users/me", FsBrowse.resolveInput("~", "C:\\Users\\me", location))
        assertEquals("/home/other", FsBrowse.resolveInput("/home/other", "/home/me", "/tmp"))
    }

    @Test
    fun thePickerReRootsADriveLessStartOnceTheProbeAnswers() {
        // `initialPath` is the remembered MRU and is used verbatim, so a record an
        // older build wrote hands the picker a drive-less path; once the probe
        // answers a Windows root the browser re-roots it instead of displaying
        // (and finally selecting) the volume-less spelling.
        assertEquals(
            "C:/sensorsdata/main/program/opencode-android-shell/v36projB",
            adoptPickerRoot("/sensorsdata/main/program/opencode-android-shell/v36projB", "C:/"),
        )
        // The probed root itself is a valid target too: `/` on a Windows host is
        // drive-relative, so the picker lands on `C:/`.
        assertEquals("C:/", adoptPickerRoot("/", "C:/"))
        // A UNC root re-roots on the share.
        assertEquals("//host/share/sensorsdata/x", adoptPickerRoot("/sensorsdata/x", "\\\\host\\share"))
        // Nothing to do on a POSIX host, on a path that already names its volume,
        // or on an empty pair — the probe's fallback `/` included.
        assertNull(adoptPickerRoot("/home/xiufeigo", "/"))
        assertNull(adoptPickerRoot("/", "/"))
        assertNull(adoptPickerRoot("C:/Users/me", "C:/"))
        assertNull(adoptPickerRoot("", "C:/"))
        assertNull(adoptPickerRoot("/sensorsdata/x", ""))
    }

    @Test
    fun thePickerAdoptionIsWiredIntoTheProbeCallback() {
        // The rendering path, not just the pure helper: the probe callback must
        // fall through to it when `resolvePickerStart` has nothing to adopt.
        val picker = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/DirectoryPickerDialog.kt")
        assertTrue(
            "the probe callback re-roots a drive-less start",
            picker.contains("?: adoptPickerRoot(currentPath, root)"),
        )
        assertTrue(
            "and it still prefers the home/root start",
            picker.contains(
                "if (!mayAdopt) null else resolvePickerStart(currentPath, previousRoot, userNavigated, home, root)",
            ),
        )
        // v41 BUG-1: the adoption is skipped once the user has taken hold of the
        // picker (navigated, typed, or armed the two-tap confirm).
        assertTrue(
            "and it is gated on probeMayAdopt",
            picker.contains("val mayAdopt = probeMayAdopt(userNavigated, userEdited, pendingConfirm)"),
        )
    }

    // ---------------------------------------------------- (2) the store identity

    @Test
    fun theDriveLessSpellingIsTheSameDirectoryAsTheWin32One() {
        val win32 = "C:\\sensorsdata\\main\\program\\opencode-android-shell\\v36projB"
        val driveLess = "/sensorsdata/main/program/opencode-android-shell/v36projB"
        // matchKey (the session-scope filter's key) still sees two keys — that is
        // upstream's rule and stays verbatim.
        assertNotEquals(ProjectPaths.matchKey(win32), ProjectPaths.matchKey(driveLess))
        // The store's key strips the volume from both sides, case preserved.
        assertEquals("sensorsdata/main/program/opencode-android-shell/v36projB", ProjectPaths.storeKey(driveLess))
        assertEquals(ProjectPaths.storeKey(win32), ProjectPaths.storeKey(driveLess))
        // The MSYS/Cygwin twins were already folded by matchKey; storeKey keeps
        // that and adds the drive-less one.
        assertEquals(ProjectPaths.storeKey(win32), ProjectPaths.storeKey("/mnt/c/sensorsdata/main/program/opencode-android-shell/v36projB"))
        assertEquals(ProjectPaths.storeKey(win32), ProjectPaths.storeKey("/cygdrive/c/sensorsdata/main/program/opencode-android-shell/v36projB"))
        // The identity itself, which is what the store compares with. The fold is
        // case-insensitive because a volume was involved on one side: `matchKey`
        // has already lowercased the Win32 spelling, so a case-sensitive tail
        // comparison could never meet it.
        assertTrue(ProjectPaths.sameDirectory(win32, driveLess))
        assertTrue(ProjectPaths.sameDirectory(driveLess, win32))
        assertTrue(
            ProjectPaths.sameDirectory(
                "C:\\SENSORSDATA\\MAIN\\PROGRAM\\OpenCode-Android-Shell\\V36PROJB",
                driveLess,
            ),
        )
        // A trailing separator, the `/`-spelling and the MSYS spelling all agree.
        assertTrue(ProjectPaths.sameDirectory("C:/sensorsdata/main/program/opencode-android-shell/v36projB/", driveLess))
        assertTrue(ProjectPaths.sameDirectory("/mnt/c$driveLess", win32))
        assertTrue(ProjectPaths.sameDirectory("\\\\wsl.localhost\\Ubuntu\\home\\u\\repo", "//wsl$/Ubuntu/home/u/repo"))
        // Blank is still "no directory".
        assertFalse(ProjectPaths.sameDirectory(win32, null))
        assertFalse(ProjectPaths.sameDirectory("", ""))
    }

    @Test
    fun theStoreIdentityKeepsEverythingMatchKeyKept() {
        // Case still separates two POSIX directories: neither side named a
        // volume, so the drive-less fold does not apply.
        assertNotEquals(ProjectPaths.storeKey("/work/api"), ProjectPaths.storeKey("/work/API"))
        assertFalse(ProjectPaths.sameDirectory("/work/api", "/work/API"))
        assertEquals("Work/API", ProjectPaths.storeKey("/Work/API"))
        // A POSIX path that is not a Windows tail keeps its own identity.
        assertNotEquals(ProjectPaths.storeKey("/mnt/data"), ProjectPaths.storeKey("d:/ata"))
        assertNotEquals(ProjectPaths.storeKey("/cygdrive/data"), ProjectPaths.storeKey("d:/ata"))
        assertFalse(ProjectPaths.sameDirectory("/mnt/data", "d:/ata"))
        // A UNC share names its volume in the host+share head, so the head is
        // stripped rather than folded into a drive-less `/host/share/…`.
        assertEquals("x", ProjectPaths.storeKey("\\\\HOST\\SHARE\\x"))
        assertEquals("x", ProjectPaths.storeKey("//host/share/x"))
        assertNotEquals(ProjectPaths.storeKey("//host/share/x"), ProjectPaths.storeKey("/host/share/x"))
        assertFalse(ProjectPaths.sameDirectory("//host/share/x", "/host/share/x"))
        // Two different volumes never meet through their tails.
        assertFalse(ProjectPaths.sameDirectory("//host/share/x", "C:/x"))
        // Blank is still "no directory".
        assertNull(ProjectPaths.storeKey(null))
        assertNull(ProjectPaths.storeKey("   "))
    }

    @Test
    fun addingTheSameDirectoryTwiceLeavesOneRowUnderEitherSpelling() {
        val home = HomeProjectRecords.State()
        val win32 = "C:\\sensorsdata\\main\\program\\opencode-android-shell\\v36projB"
        val driveLess = "/sensorsdata/main/program/opencode-android-shell/v36projB"

        // The device flow: the first add stored the faithful spelling, the second
        // (from the drive-less box) must not add a second row.
        var state = HomeProjectRecords.open(home, win32)
        state = HomeProjectRecords.open(state, driveLess)
        assertEquals(listOf(win32), state.list.map { it.worktree })

        // …and the other order keeps the spelling the user picked first.
        var reverse = HomeProjectRecords.open(home, driveLess)
        reverse = HomeProjectRecords.open(reverse, win32)
        assertEquals(listOf(driveLess), reverse.list.map { it.worktree })

        // The two-tap flow the report describes: the picker hands the same string
        // twice, which was always a no-op and still is.
        assertEquals(state, HomeProjectRecords.open(state, win32))
        assertEquals(state, HomeProjectRecords.open(state, driveLess))
    }

    @Test
    fun closingUnderEitherSpellingDismissesTheOneRow() {
        val win32 = "C:\\sensorsdata\\main\\program\\opencode-android-shell\\v36projB"
        val driveLess = "/sensorsdata/main/program/opencode-android-shell/v36projB"
        val state = HomeProjectRecords.open(HomeProjectRecords.open(HomeProjectRecords.State(), win32), "/work/site")

        val closed = HomeProjectRecords.close(state, driveLess)
        assertEquals(listOf("/work/site"), closed.list.map { it.worktree })
        assertEquals(listOf(driveLess), closed.recentlyClosed)
        // Re-opening under the twin spelling takes the tombstone out again.
        assertTrue(HomeProjectRecords.open(closed, win32).recentlyClosed.isEmpty())
        assertTrue(HomeProjectRecords.open(closed, driveLess).recentlyClosed.isEmpty())
    }

    @Test
    fun aRecordAnOlderBuildWroteWithBothSpellingsHealsToOneRow() {
        val win32 = "C:\\sensorsdata\\main\\program\\opencode-android-shell\\v36projB"
        val driveLess = "/sensorsdata/main/program/opencode-android-shell/v36projB"
        // The blob an older build persisted: one directory, two spellings (the
        // JSON needs the Win32 backslashes escaped, hence `encode`).
        val dirty = HomeProjectRecords.encode(
            HomeProjectRecords.State(
                list = listOf(
                    HomeProjectRecords.Project(win32, sandboxes = listOf("C:\\sb")),
                    HomeProjectRecords.Project(driveLess, sandboxes = listOf("/sb2")),
                ),
                recentlyClosed = listOf(driveLess, win32),
            ),
        )
        val decoded = HomeProjectRecords.decode(dirty)
        assertEquals(listOf(win32), decoded.list.map { it.worktree })
        assertEquals(listOf("C:\\sb", "/sb2"), decoded.list.single().sandboxes)
        assertEquals("one tombstone for the directory", 1, decoded.recentlyClosed.size)
        // And the healed record round trips.
        assertEquals(decoded, HomeProjectRecords.decode(HomeProjectRecords.encode(decoded)))
        // A malformed blob still degrades instead of crashing Home.
        assertEquals(HomeProjectRecords.State(), HomeProjectRecords.decode("""{"projects":[{"worktree":"C:\sensorsdata"}]}"""))
    }

    @Test
    fun aDirtyInMemoryRecordIsHealedOnTheNextWrite() {
        // `decode` heals on read, but a state built by hand (an older build's
        // in-memory snapshot) must not survive a write either.
        val win32 = "C:\\sensorsdata\\main\\program\\opencode-android-shell\\v36projB"
        val driveLess = "/sensorsdata/main/program/opencode-android-shell/v36projB"
        val dirty = HomeProjectRecords.State(
            list = listOf(HomeProjectRecords.Project(win32), HomeProjectRecords.Project(driveLess)),
        )
        assertEquals(
            "the new row plus one healed row, not two",
            listOf("/work/site", win32),
            HomeProjectRecords.open(dirty, "/work/site").list.map { it.worktree },
        )
        assertEquals(
            listOf(win32),
            HomeProjectRecords.close(dirty, "/work/other").list.map { it.worktree },
        )
    }

    @Test
    fun aRenameAndAMoveFindTheRowUnderEitherSpelling() {
        val win32 = "C:\\sensorsdata\\main\\program\\opencode-android-shell\\v36projB"
        val driveLess = "/sensorsdata/main/program/opencode-android-shell/v36projB"
        val state = HomeProjectRecords.open(
            HomeProjectRecords.open(HomeProjectRecords.State(), win32),
            "/work/site",
        )
        val renamed = HomeProjectRecords.setName(state, driveLess, "v36 B")
        assertEquals("v36 B", renamed.list.first { it.worktree == win32 }.name)
        assertEquals(
            listOf(win32, "/work/site"),
            HomeProjectRecords.move(state, driveLess, 0).list.map { it.worktree },
        )
    }
}
