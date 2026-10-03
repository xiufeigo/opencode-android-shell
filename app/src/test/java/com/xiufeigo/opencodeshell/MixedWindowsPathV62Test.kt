package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.data.HomeProjectRecords
import com.xiufeigo.opencodeshell.data.ProjectPaths
import com.xiufeigo.opencodeshell.ui.settings.projectPageRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v59, emulator-5556: Settings → Projects listed
 * `/home/xiufeigo/C:\sensorsdata\main\program\opencode-android-shell`
 * **beside** the correct `C:\sensorsdata\main\program\opencode-android-shell`
 * for the same repository — one project, two rows, and both called
 * `opencode-android-shell`, so the list said nothing about which was which.
 *
 * The mixed spelling is a Win32 path glued onto a POSIX root. It names no
 * directory on either side: the backslashes are not separators on the WSL
 * server, and the whole string is not a Win32 path either (no drive in
 * position 2). It is a client-side question, not a server one — `GET /project`
 * simply hands back whatever the session was created with, and the v58 P0 loop
 * put such a value into the record.
 *
 * The fix is read-side and has two halves that must agree:
 * [ProjectPaths.embeddedWindowsPath] recognises the shape, [matchKey] folds it
 * onto the volume so **identity** collapses (one directory is one project
 * everywhere — store de-duplication, the session filter, the closed tombstone),
 * and [ProjectPaths.displayLocation] collapses the **spelling** for the reader.
 *
 * What it must not do is rewrite what the store persists: the client-local
 * record keeps the verbatim worktree it was handed (a read-side normalisation
 * that rewrote the stored spelling was rolled back once already), so every
 * assertion below about the store checks the *row count*, never the stored
 * string.
 */
class MixedWindowsPathV62Test {

    /** The exact value the v59 device run saw, and the row it duplicated. */
    private val mixed = "/home/xiufeigo/C:\\sensorsdata\\main\\program\\opencode-android-shell"
    private val clean = "C:\\sensorsdata\\main\\program\\opencode-android-shell"

    /** A `GET /project` body, so the test's own JSON has no escaped quotes to get wrong. */
    private fun projectJson(vararg rows: String) = rows.joinToString(",", "[", "]")

    /** A path as a JSON string value — `\` must be escaped to be legal JSON. */
    private fun jsonPath(path: String) = path.replace("\\", "\\\\")

    // ------------------------------------------------- 1. recognising the shape

    @Test
    fun `the embedded windows path is pulled out of the posix prefix`() {
        assertEquals(clean, ProjectPaths.embeddedWindowsPath(mixed))
        assertEquals("C:/repo", ProjectPaths.embeddedWindowsPath("/home/xiufeigo/C:/repo"))
        // The drive alone, and a drive root, are the same shape.
        assertEquals("C:\\", ProjectPaths.embeddedWindowsPath("/home/xiufeigo/C:\\"))
        assertEquals("C:", ProjectPaths.embeddedWindowsPath("/home/xiufeigo/C:"))
    }

    @Test
    fun `a path that is not a mixed spelling is left alone`() {
        assertNull(ProjectPaths.embeddedWindowsPath(clean))
        assertNull(ProjectPaths.embeddedWindowsPath("C:/repo"))
        assertNull(ProjectPaths.embeddedWindowsPath("/home/xiufeigo/v36projC"))
        // A colon inside a segment is not a drive: no segment boundary, and the
        // character after the colon is not a separator.
        assertNull(ProjectPaths.embeddedWindowsPath("/var/log/app:1/x"))
        assertNull(ProjectPaths.embeddedWindowsPath("/srv/a:b/c"))
        assertNull(ProjectPaths.embeddedWindowsPath(null))
        assertNull(ProjectPaths.embeddedWindowsPath("  "))
    }

    // --------------------------------------------------------- 2. the identity

    @Test
    fun `the mixed spelling and the real one are one directory`() {
        assertEquals(ProjectPaths.matchKey(clean), ProjectPaths.matchKey(mixed))
        assertTrue(ProjectPaths.sameDirectory(clean, mixed))
        assertTrue(ProjectPaths.sameDirectory(mixed, "/home/xiufeigo/c:/sensorsdata/main/program/opencode-android-shell"))
    }

    @Test
    fun `the fold does not merge directories that only look alike`() {
        // Two genuinely different POSIX paths keep their own identity.
        assertNotEquals(ProjectPaths.matchKey("/work/api"), ProjectPaths.matchKey("/work/API"))
        // A drive-less POSIX path is not the Windows tail of some other volume.
        assertNotEquals(ProjectPaths.matchKey("/mnt/data"), ProjectPaths.matchKey("d:/ata"))
        // A mixed spelling is the directory it carries, not a new one.
        assertNotEquals(ProjectPaths.matchKey(mixed), ProjectPaths.matchKey("D:\\sensorsdata\\main\\program\\opencode-android-shell"))
    }

    @Test
    fun `a session under the mixed project is attributed to it`() {
        val repo = "C:\\sensorsdata\\main\\program\\opencode-android-shell"
        val mixedRepo = "/home/xiufeigo/$repo"
        assertTrue(
            "a package subdirectory of the mixed spelling belongs to the clean project",
            ProjectPaths.containsDirectory(repo, "$mixedRepo\\app\\src"),
        )
        assertTrue(ProjectPaths.containsAny(listOf(mixedRepo), "$repo\\app"))
    }

    // ------------------------------------------- 3. the store: one row, verbatim

    @Test
    fun `the store holds one row for the directory whatever the spelling`() {
        val dirty = HomeProjectRecords.decode(
            HomeProjectRecords.encode(
                HomeProjectRecords.State(list = listOf(HomeProjectRecords.Project(mixed))),
            ),
        )
        assertEquals(1, dirty.list.size)

        val state = HomeProjectRecords.State(
            list = listOf(
                HomeProjectRecords.Project(mixed),
                HomeProjectRecords.Project(clean),
            ),
        )
        val healed = HomeProjectRecords.decode(HomeProjectRecords.encode(state))
        assertEquals(
            "one project is one row however the server spelled it (v59 5556)",
            1,
            healed.list.size,
        )
        assertEquals(
            "and the row keeps the spelling it was stored with — the store never rewrites it",
            mixed,
            healed.list.single().worktree,
        )
    }

    @Test
    fun `closing the clean spelling also closes the mixed one`() {
        val state = HomeProjectRecords.State(list = listOf(HomeProjectRecords.Project(mixed)))
        val closed = HomeProjectRecords.close(state, clean)
        assertEquals(emptyList<HomeProjectRecords.Project>(), closed.list)
    }

    // ------------------------------------------------- 4. the settings projects

    @Test
    fun `the projects page prints the windows path and lists the directory once`() {
        val json = projectJson(
            """{"id":"pr_1","canonical":"${jsonPath(mixed)}","vcs":"git","time":{"updated":200}}""",
            """{"id":"pr_2","canonical":"${jsonPath(clean)}","vcs":"git","time":{"updated":100}}""",
            """{"id":"pr_3","canonical":"/home/xiufeigo/v36projC","time":{"updated":50}}""",
        )
        val rows = projectPageRows(json)
        assertEquals(
            "the mixed row and the row it duplicated are one directory (v59 5556)",
            listOf(clean, "/home/xiufeigo/v36projC"),
            rows.map { it.worktree },
        )
        assertEquals("opencode-android-shell", rows.first().name)
    }

    @Test
    fun `the projects page search still matches what is printed`() {
        val json = projectJson("""{"id":"pr_1","canonical":"${jsonPath(mixed)}","time":{"updated":200}}""")
        val rows = projectPageRows(json)
        assertTrue(
            "the path a user can see is the path they can search for",
            com.xiufeigo.opencodeshell.ui.settings.filterProjectRows(rows, "C:\\sensorsdata").isNotEmpty(),
        )
        assertTrue(com.xiufeigo.opencodeshell.ui.settings.filterProjectRows(rows, "opencode-android-shell").isNotEmpty())
    }

    // ------------------------------------------------------ 5. read-side only

    @Test
    fun `display normalisation is not a write`() {
        assertEquals(clean, ProjectPaths.displayLocation(mixed))
        // A path with no hidden Windows half is returned exactly as it came in.
        assertEquals("/home/xiufeigo/v36projC", ProjectPaths.displayLocation("/home/xiufeigo/v36projC"))
        assertEquals(clean, ProjectPaths.displayLocation(clean))
        assertEquals(clean, ProjectPaths.displayLocation(" $clean "))
        assertNull(ProjectPaths.displayLocation(""))
        assertNull(ProjectPaths.displayLocation(null))
    }

    @Test
    fun `the write canonical form is unchanged by any of this`() {
        // normalizeLocation is the write path (createSession, the picker); the
        // mixed form is a read-side question and must not start rewriting
        // stored spellings through it. It only folds the separators the value
        // mixes, exactly as it did before.
        assertEquals(
            "/home/xiufeigo/C:/sensorsdata/main/program/opencode-android-shell",
            ProjectPaths.normalizeLocation(mixed),
        )
        assertEquals("C:/repo/app/src", ProjectPaths.normalizeLocation("C:/repo/app/src/app/src"))
    }

    @Test
    fun `a windows path with no posix prefix is still its own directory`() {
        assertFalse(ProjectPaths.sameDirectory("C:\\repo", "D:\\repo"))
    }
}
