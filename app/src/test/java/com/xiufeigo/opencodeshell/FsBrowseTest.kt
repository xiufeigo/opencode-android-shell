package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.FsBrowse
import com.xiufeigo.opencodeshell.ui.home.HomeProjects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The directory picker's path algebra ([FsBrowse]): `~`/relative/absolute input
 * resolution (upstream `pickerAbsoluteInput`), root calculation and the parent
 * stop condition (`pickerRoot` / `HomeProjects.parentOf`), relative-path joins
 * with trailing-separator normalisation (`absoluteTreePath`), the v1
 * trailing-separator directory flag, and the v1 escape boundary that decides
 * when the tree may leave the server cwd.
 */
class FsBrowseTest {

    // ---- resolveInput: ~ / ~/x / absolute / relative -----------------------

    @Test
    fun `resolveInput expands tilde to home`() {
        assertEquals(
            "/home/me",
            FsBrowse.resolveInput("~", home = "/home/me", current = "/tmp"),
        )
        assertEquals(
            "C:/Users/me",
            FsBrowse.resolveInput("~", home = "C:\\Users\\me", current = "C:/tmp"),
        )
    }

    @Test
    fun `resolveInput joins tilde-relative onto home`() {
        assertEquals(
            "/home/me/proj",
            FsBrowse.resolveInput("~/proj", home = "/home/me", current = "/tmp"),
        )
        assertEquals(
            "C:/Users/me/src",
            FsBrowse.resolveInput("~/src", home = "C:/Users/me", current = "C:/tmp"),
        )
    }

    @Test
    fun `resolveInput keeps rooted paths as-is`() {
        assertEquals(
            "C:/Windows/System32",
            FsBrowse.resolveInput("C:\\Windows\\System32", home = "C:\\Users\\me", current = "C:/tmp"),
        )
        assertEquals(
            "/home/other",
            FsBrowse.resolveInput("/home/other", home = "/home/me", current = "/tmp"),
        )
    }

    @Test
    fun `resolveInput joins relative paths onto the current directory`() {
        assertEquals(
            "/tmp/nested",
            FsBrowse.resolveInput("nested", home = "/home/me", current = "/tmp"),
        )
        assertEquals(
            "C:/tmp/nested",
            FsBrowse.resolveInput("nested", home = "C:/Users/me", current = "C:\\tmp"),
        )
    }

    @Test
    fun `resolveInput canonicalises dot and dot-dot`() {
        assertEquals(
            "/home/me",
            FsBrowse.resolveInput("../me/./proj/..", home = null, current = "/home/other"),
        )
        // `..` never escapes the root.
        assertEquals("/", FsBrowse.resolveInput("..", home = null, current = "/home"))
    }

    @Test
    fun `resolveInput keeps an unexpanded tilde when home is unknown`() {
        // Contract above: a blank home must leave `~` for the caller's hint,
        // not silently resolve it onto the current directory.
        assertEquals("~", FsBrowse.resolveInput("~", home = null, current = "/tmp"))
        assertEquals("~/proj", FsBrowse.resolveInput("~/proj", home = null, current = "/tmp"))
        assertEquals("~", FsBrowse.resolveInput("~", home = "", current = "C:/tmp"))
        assertEquals("~/proj", FsBrowse.resolveInput("~/proj", home = "  ", current = "C:/tmp"))
    }

    // ---- root calculation and the parent stop condition --------------------

    @Test
    fun `root reads POSIX, drive and UNC roots`() {
        assertEquals("/", FsBrowse.root("/home/me/proj"))
        assertEquals("/", FsBrowse.root("/"))
        assertEquals("C:/", FsBrowse.root("C:\\Windows\\System32"))
        assertEquals("C:/", FsBrowse.root("C:/"))
        assertEquals("C:/", FsBrowse.root("C:"))
        assertEquals("//host/share", FsBrowse.root("\\\\host\\share\\home\\me"))
        assertEquals("//host/share", FsBrowse.root("//host/share"))
    }

    @Test
    fun `root is empty for relative paths`() {
        assertEquals("", FsBrowse.root("relative/path"))
        assertEquals("", FsBrowse.root(""))
    }

    @Test
    fun `parent walk stops at each root flavour`() {
        assertEquals("/home/me", HomeProjects.parentOf("/home/me/proj"))
        assertEquals("/", HomeProjects.parentOf("/home"))
        assertEquals("C:\\", HomeProjects.parentOf("C:\\src"))
        assertNull(HomeProjects.parentOf("/"))
        assertNull(HomeProjects.parentOf("C:\\"))
        assertNull(HomeProjects.parentOf("C:/"))
        assertNull(HomeProjects.parentOf("\\\\host\\share"))
        assertNull(HomeProjects.parentOf("\\\\host"))
        // resolveInput normalises `\` to `/`, so the picker walks UNC paths
        // in their `//host/share` spelling and must stop at that share root
        // instead of climbing to `//host` and on to `/`.
        assertNull(HomeProjects.parentOf("//host/share"))
        assertNull(HomeProjects.parentOf("//host"))
        assertEquals("//host/share", HomeProjects.parentOf("//host/share/home"))
        assertEquals("//host/share/home", HomeProjects.parentOf("//host/share/home/me"))
    }

    // ---- joinRelative: trailing-separator normalisation --------------------

    @Test
    fun `joinRelative joins a POSIX location and its relative child`() {
        assertEquals("/home/x/code/.ref", FsBrowse.joinRelative("/home/x", "code/.ref/"))
        assertEquals("/code", FsBrowse.joinRelative("/", "code/"))
        assertEquals("/home/x", FsBrowse.joinRelative("/home/x", ""))
    }

    @Test
    fun `joinRelative follows the server separators on Windows paths`() {
        assertEquals(
            "C:\\Windows\\System32\\0409",
            FsBrowse.joinRelative("C:\\Windows\\System32", "0409\\"),
        )
        assertEquals(
            "C:\\Windows\\System32\\file.txt",
            FsBrowse.joinRelative("C:\\Windows\\System32/", "file.txt"),
        )
    }

    @Test
    fun `joinRelative drops the duplicate trailing separator`() {
        assertEquals("/home/x/code", FsBrowse.joinRelative("/home/x/", "code"))
        assertEquals("/home/x/code", FsBrowse.joinRelative("/home/x", "/code/"))
    }

    @Test
    fun `join collapses separators on both sides`() {
        assertEquals("/home/me/proj", FsBrowse.join("/home/me/", "/proj/"))
        assertEquals("proj", FsBrowse.join("", "proj"))
        assertEquals("/home/me", FsBrowse.join("/home/me", ""))
    }

    // ---- trailing separator = directory (v1 /find/file) --------------------

    @Test
    fun `trailing separator flags a directory`() {
        assertTrue(FsBrowse.isDirectoryPath("AMD\\"))
        assertTrue(FsBrowse.isDirectoryPath("AppV/"))
        assertFalse(FsBrowse.isDirectoryPath("at.exe"))
        assertFalse(FsBrowse.isDirectoryPath("atl.dll"))
    }

    @Test
    fun `name takes the last segment and drops the trailing separator`() {
        assertEquals("AMD", FsBrowse.name("AMD\\"))
        assertEquals("at.exe", FsBrowse.name("at.exe"))
        assertEquals("System32", FsBrowse.name("C:\\Windows\\System32"))
        assertEquals("code", FsBrowse.name("home/x/code/"))
        assertEquals("", FsBrowse.name("/"))
    }

    // ---- v1 escape boundary (browseEscapeAllowed = false) ------------------

    @Test
    fun `isWithin keeps the server-cwd subtree together`() {
        val root = "C:\\Windows\\System32"
        assertTrue(FsBrowse.isWithin(root, "C:\\Windows\\System32"))
        assertTrue(FsBrowse.isWithin(root, "C:\\Windows\\System32\\0409"))
        // Case-insensitive on drives, like upstream pickerRelativePath.
        assertTrue(FsBrowse.isWithin(root, "c:/windows/system32/0409"))
    }

    @Test
    fun `isWithin rejects parents and siblings of the v1 root`() {
        val root = "C:\\Windows\\System32"
        assertFalse(FsBrowse.isWithin(root, "C:\\Windows"))
        assertFalse(FsBrowse.isWithin(root, "C:\\Users"))
        assertFalse(FsBrowse.isWithin(root, "C:\\"))
        assertFalse(FsBrowse.isWithin(root, "D:\\elsewhere"))
    }

    @Test
    fun `isWithin is case-sensitive on POSIX and scoped to the UNC share`() {
        assertTrue(FsBrowse.isWithin("/home/me", "/home/me/proj"))
        assertFalse(FsBrowse.isWithin("/home/me", "/home/ME/proj"))
        assertTrue(FsBrowse.isWithin("\\\\host\\share", "\\\\host\\share\\home"))
        assertFalse(FsBrowse.isWithin("\\\\host\\share", "\\\\host\\other"))
    }

    @Test
    fun `parent of the v1 root falls outside the allowed subtree`() {
        val root = "C:\\Windows\\System32"
        val parent = HomeProjects.parentOf(root)
        assertEquals("C:\\Windows", parent)
        assertFalse(FsBrowse.isWithin(root, parent!!))
    }

    // ---- fsReadRelative: absolute → v2 GET /api/fs/read/<rel> --------------

    @Test
    fun `fsReadRelative strips the POSIX location prefix and leading slash`() {
        assertEquals(".bashrc", FsBrowse.fsReadRelative("/home/me", "/home/me/.bashrc"))
        assertEquals(
            "app/src/Main.kt",
            FsBrowse.fsReadRelative("/home/me/proj/", "/home/me/proj/app/src/Main.kt"),
        )
        // Location `/`: everything is inside; only the leading slash goes.
        assertEquals("etc/hosts", FsBrowse.fsReadRelative("/", "/etc/hosts"))
        // `..` and `.` canonicalise before the strip.
        assertEquals("x.txt", FsBrowse.fsReadRelative("/home/me", "/home/me/sub/../x.txt"))
    }

    @Test
    fun `fsReadRelative normalises Windows separators and drive case`() {
        assertEquals(
            "app/build.gradle.kts",
            FsBrowse.fsReadRelative("C:\\src\\app", "C:\\src\\app\\app\\build.gradle.kts"),
        )
        // Drive prefixes compare case-insensitively (the Windows rule).
        assertEquals(
            "README.md",
            FsBrowse.fsReadRelative("c:/src/app", "C:/SRC/App/README.md"),
        )
        assertEquals(
            "dir/x.txt",
            FsBrowse.fsReadRelative("\\\\host\\share\\repo", "\\\\host\\share\\repo\\dir\\x.txt"),
        )
    }

    @Test
    fun `fsReadRelative refuses every path a guessed relative would lie about`() {
        // Blank location: nothing to be relative to.
        assertNull(FsBrowse.fsReadRelative(null, "/home/me/.bashrc"))
        assertNull(FsBrowse.fsReadRelative("  ", "/home/me/.bashrc"))
        // Outside the location: a guessed relative reads a DIFFERENT file
        // under the location — the preview must fail instead.
        assertNull(FsBrowse.fsReadRelative("/home/me", "/etc/hosts"))
        assertNull(FsBrowse.fsReadRelative("C:\\src\\app", "C:\\other\\x.txt"))
        // POSIX stays case-sensitive (same rule as isWithin).
        assertNull(FsBrowse.fsReadRelative("/home/me", "/home/ME/x"))
        // The location itself is a directory, not a file.
        assertNull(FsBrowse.fsReadRelative("/home/me", "/home/me"))
        assertNull(FsBrowse.fsReadRelative("C:\\src\\app", "C:\\src\\app\\"))
    }

    // ---- homeOf: the `~` target derivable from a server path (v32 BUG-2) ----

    @Test
    fun `homeOf derives the user home from a path below it`() {
        // v2's `/api/location` answers the *location* (the project directory),
        // and the picker's `~` used it — a silent no-op whenever the browser
        // already sat there (`research/v32_verify/tB/18_picker_home.png`).
        assertEquals("/home/xiufeigo", FsBrowse.homeOf("/home/xiufeigo/v11diff"))
        assertEquals("/home/xiufeigo", FsBrowse.homeOf("/home/xiufeigo/"))
        assertEquals("/Users/me", FsBrowse.homeOf("/Users/me/proj"))
        assertEquals("C:/Users/me", FsBrowse.homeOf("""C:\Users\me\proj"""))
        // WSL's view of a Windows home: the drive prefix is part of the path.
        assertEquals("/mnt/c/Users/me", FsBrowse.homeOf("/mnt/c/Users/me/proj"))
        assertEquals("/root", FsBrowse.homeOf("/root"))
    }

    @Test
    fun `homeOf answers a home path unchanged`() {
        assertEquals("/home/xiufeigo", FsBrowse.homeOf("/home/xiufeigo"))
        assertEquals("/Users/me", FsBrowse.homeOf("/Users/me"))
        assertEquals("C:/Users/me", FsBrowse.homeOf("C:/Users/me"))
        // `\` normalisation and trailing separators follow the picker's own
        // algebra (`normalizeDrive` + `canonical`).
        assertEquals("C:/Users/me", FsBrowse.homeOf("""C:\Users\me\"""))
    }

    @Test
    fun `homeOf is null when the path names no home`() {
        assertNull(FsBrowse.homeOf(null))
        assertNull(FsBrowse.homeOf(""))
        assertNull(FsBrowse.homeOf("   "))
        assertNull(FsBrowse.homeOf("/"))
        assertNull(FsBrowse.homeOf("/srv/app"))
        // `home`/`Users` as the last segment names no user.
        assertNull(FsBrowse.homeOf("/home"))
        assertNull(FsBrowse.homeOf("/Users"))
        // The v2 server launch cwd the picker refuses as a start.
        assertNull(FsBrowse.homeOf("""C:\Windows\System32"""))
        // A relative path has no root to hang a home off.
        assertNull(FsBrowse.homeOf("relative/home/me"))
    }
}
