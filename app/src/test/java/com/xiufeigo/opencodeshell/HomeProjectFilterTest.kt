package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.data.HomeProjectRecords
import com.xiufeigo.opencodeshell.ui.home.HomeProjects
import com.xiufeigo.opencodeshell.ui.home.HomeSessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Home's **selected-project scope**: picking a project in the left column filters
 * the sessions list to that project and scopes the search placeholder
 * (`_upstream/packages/app/src/home/sessions/records.ts:17-21`,
 * `controller.tsx:50-55,256` and `search.ts:45-53`).
 *
 * The scope itself is upstream's `isProjectDirectory`
 * (`_upstream/packages/app/src/workspaces/paths.ts:83-96`): a session belongs to
 * a project when its own directory **is** the project's worktree (or one of its
 * sandboxes) **or below it**. Exact equality was wrong for this server, because
 * `GET /session` reports `projectID = global` for every session and the only
 * attribution left is `session.directory` — a worktree or package session has to
 * land under its project.
 *
 * Everything here is JVM-only. The one non-pure claim — that `HomeScreen`
 * actually hands the selection to these helpers — uses the repo's established
 * source guard (`ShellWiringTest`, `Wave5IntegrationWiringTest`), because Compose
 * wiring is not reachable from a unit test.
 */
class HomeProjectFilterTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun session(
        id: String,
        directory: String? = null,
        updatedAt: Long = 0L,
        archivedAt: Long? = null,
    ) = OpenCodeClient.Session(
        id = id,
        title = id,
        directory = directory,
        updatedAt = updatedAt,
        archivedAt = archivedAt,
    )

    private fun project(worktree: String, iconColor: String? = null) =
        OpenCodeClient.Project(id = worktree, worktree = worktree, vcs = "git", iconColor = iconColor)

    /** The scoped list: [directories] is the selected project's scope. */
    private fun ids(
        sessions: List<OpenCodeClient.Session>,
        directories: List<String> = emptyList(),
    ): List<String> = HomeSessions.rows(sessions, directories = directories).map { it.id }

    /** The scoped list for a single-directory project. */
    private fun idsIn(sessions: List<OpenCodeClient.Session>, directory: String?): List<String> =
        ids(sessions, listOfNotNull(directory))

    // ------------------------------------------------------------ the filter

    @Test
    fun `no selection keeps every project's sessions`() {
        val sessions = listOf(
            session("api", "/work/api", 30L),
            session("site", "/work/site", 20L),
            session("unknown", null, 10L),
        )

        assertEquals(listOf("api", "site", "unknown"), idsIn(sessions, null))
        // The named-argument and the positional/default forms agree: the
        // pre-selection behaviour is exactly `rows(sessions)`, and an empty scope
        // (the "absent selection" a caller may pass) is *not* "match nothing".
        assertEquals(HomeSessions.rows(sessions).map { it.id }, ids(sessions, emptyList()))
        assertEquals(listOf("api", "site", "unknown"), ids(sessions, listOf("", "  ")))
    }

    @Test
    fun `a selected project keeps only its own sessions`() {
        val sessions = listOf(
            session("api1", "/work/api", 30L),
            session("site", "/work/site", 20L),
            session("api2", "/work/api", 10L),
            session("unknown", null, 5L),
        )

        assertEquals(listOf("api1", "api2"), idsIn(sessions, "/work/api"))
        assertEquals(listOf("site"), idsIn(sessions, "/work/site"))
    }

    @Test
    fun `a nested directory belongs to its project`() {
        // `isProjectDirectory` walks the tree: `/work/api/packages/app` is inside
        // `/work/api`, so it is that project's session — a worktree/package
        // session must not disappear from its project's list.
        val sessions = listOf(
            session("exact", "/work/api", 40L),
            session("nested", "/work/api/packages/app", 30L),
            session("deep", "/work/api/packages/app/src", 25L),
            session("other", "/other", 10L),
        )

        assertEquals(listOf("exact", "nested", "deep"), idsIn(sessions, "/work/api"))
    }

    @Test
    fun `a same-prefix sibling is not inside the project`() {
        // The guard of `containsDirectory` (`paths.ts:95`): the root is compared
        // with a trailing separator, so a name that merely *starts* with the
        // worktree's name is a different project.
        val remote = "C:\\sensorsdata\\main\\program\\DSH-Remote"
        val sessions = listOf(
            session("at-root", "$remote\\packages\\app", 40L),
            session("near-miss", "$remote-old", 30L),
            session("near-miss-sep", "${remote}2", 20L),
            session("parent", "C:\\sensorsdata\\main\\program", 10L),
        )

        assertEquals(listOf("at-root"), idsIn(sessions, remote))
        // The same near-miss in POSIX spelling.
        val posix = listOf(
            session("inside", "/work/api/sub", 20L),
            session("sibling", "/work/api2", 10L),
        )
        assertEquals(listOf("inside"), idsIn(posix, "/work/api"))
    }

    @Test
    fun `separators and trailing slashes do not break the match`() {
        val posix = listOf(session("a", "/work/api", 20L), session("b", "/work/api/", 10L))
        assertEquals(listOf("a", "b"), idsIn(posix, "/work/api"))
        assertEquals(listOf("a", "b"), idsIn(posix, "/work/api/"))

        val windows = listOf(
            session("c", "C:\\src\\app", 20L),
            session("d", "C:\\src\\app\\", 10L),
            // The same directory spelled with forward slashes (what a tree URI or
            // a POSIX-flavoured server response carries).
            session("e", "C:/src/app", 5L),
        )
        assertEquals(listOf("c", "d", "e"), idsIn(windows, "C:\\src\\app"))
        assertEquals(listOf("c", "d", "e"), idsIn(windows, "C:/src/app/"))
    }

    @Test
    fun `a windows directory matches case insensitively`() {
        // Windows filesystems are case-insensitive, and `containsDirectory`
        // lowercases the key for anything that looks like a drive or a UNC share
        // (`paths.ts:89-92`).
        val sessions = listOf(
            session("stored", "C:\\sensorsdata\\main\\program\\DSH-Remote", 30L),
            session("nested", "C:\\SensorsData\\Main\\Program\\DSH-Remote\\packages", 20L),
        )

        assertEquals(listOf("stored", "nested"), idsIn(sessions, "c:\\sensorsdata\\main\\program\\dsh-remote"))
        assertEquals(listOf("stored", "nested"), idsIn(sessions, "C:/SENSORSDATA/MAIN/PROGRAM/DSH-REMOTE/"))
    }

    @Test
    fun `a posix directory stays case sensitive`() {
        // Two genuinely different directories must not merge just because one
        // happens to be another's uppercase spelling.
        val sessions = listOf(
            session("lower", "/work/api", 20L),
            session("upper", "/Work/API", 10L),
        )

        assertEquals(listOf("lower"), idsIn(sessions, "/work/api"))
        assertEquals(listOf("upper"), idsIn(sessions, "/Work/API"))
    }

    @Test
    fun `the extended length path prefix is tolerated`() {
        // `\\?\C:\…` (and the `//?/` spelling) is the same directory as `C:\…`;
        // the server is free to hand the Win32 form back verbatim.
        val sessions = listOf(
            session("plain", "C:\\sensorsdata\\main\\program\\DSH-Remote", 30L),
            session("nested", "C:\\sensorsdata\\main\\program\\DSH-Remote\\packages\\app", 20L),
            session("extended", "\\\\?\\C:\\sensorsdata\\main\\program\\DSH-Remote-other", 10L),
        )

        assertEquals(
            listOf("plain", "nested"),
            idsIn(sessions, "\\\\?\\C:\\sensorsdata\\main\\program\\DSH-Remote"),
        )
        assertEquals(
            listOf("plain", "nested"),
            idsIn(sessions, "//?/C:/sensorsdata/main/program/DSH-Remote"),
        )
        // A UNC worktree with the prefix is the same directory as its `\\host`
        // spelling — `\\?\UNC\` included.
        val unc = listOf(session("u", "\\\\wsl.localhost\\Arch\\home\\xiufeigo\\code\\C-UI\\packages", 5L))
        assertEquals(
            listOf("u"),
            idsIn(unc, "\\\\?\\UNC\\wsl.localhost\\Arch\\home\\xiufeigo\\code\\C-UI"),
        )
    }

    @Test
    fun `the six duplicate workspace worktrees stay distinct`() {
        // Measured on the live server: `GET /project` registered the basename
        // `workspace` six times (its `…\Temp\cw-*` workspaces), and `GET /session`
        // carries one such directory per workspace. The basename is identical,
        // the directory is not: selecting one must list only its own sessions.
        val workspaces = (1..6).map { "C:\\Users\\xiufe\\AppData\\Local\\Temp\\cw-$it\\workspace" }
        val sessions = workspaces.mapIndexed { index, directory ->
            session("s${index + 1}", directory, 10L * (index + 1))
        }

        workspaces.forEachIndexed { index, directory ->
            assertEquals(listOf("s${index + 1}"), idsIn(sessions, directory))
        }
        // A forward-slash copy of one of them is the same project.
        assertEquals(listOf("s3"), idsIn(sessions, workspaces[2].replace('\\', '/')))
        // Repeating the selected worktree six times (the server's duplicated
        // list) neither widens nor narrows the scope.
        assertEquals(listOf("s3"), ids(sessions, List(6) { workspaces[2] }))
    }

    @Test
    fun `path keys mirror upstream for the roots and for a missing directory`() {
        assertNull(HomeSessions.pathKey(null))
        assertNull(HomeSessions.pathKey(""))
        assertNull(HomeSessions.pathKey("   "))
        assertEquals("/", HomeSessions.pathKey("/"))
        assertEquals("/", HomeSessions.pathKey("//"))
        assertEquals("/work/api", HomeSessions.pathKey("/work/api/"))
        assertEquals("C:/", HomeSessions.pathKey("C:\\"))
        assertEquals("C:/", HomeSessions.pathKey("C:/"))
        assertEquals("C:/src/app", HomeSessions.pathKey("C:\\src\\app\\"))
        assertEquals("/mnt/c", HomeSessions.pathKey("/mnt/c"))
        // The extended prefix is Android's addition to `path-key.ts:18-24`; the
        // matching key additionally case-folds a Windows directory.
        assertEquals("C:/src/app", HomeSessions.pathKey("\\\\?\\C:\\src\\app"))
        assertEquals("//server/share", HomeSessions.pathKey("\\\\?\\UNC\\server\\share"))
        assertNull(HomeSessions.matchKey(null))
        assertEquals("c:/src/app", HomeSessions.matchKey("C:\\SRC\\App"))
        assertEquals("//server/share", HomeSessions.matchKey("\\\\SERVER\\Share"))
        assertEquals("/Work/API", HomeSessions.matchKey("/Work/API"))
    }

    @Test
    fun `the root project contains every absolute path`() {
        // `/` is a real project row on the measured server (`GET /project` id
        // `global`, worktree `/`), and `containsDirectory("/", x)` is true for
        // every absolute path — that *is* the upstream rule (`paths.ts:95`:
        // `root.endsWith("/")` keeps the root's single separator). The session the
        // server gave no directory for still belongs to no project.
        val sessions = listOf(
            session("at-root", "/", 30L),
            session("deep", "/home/me/app", 20L),
            session("unknown", null, 10L),
        )

        assertEquals(listOf("at-root", "deep"), idsIn(sessions, "/"))
        assertTrue("unknown-directory sessions never match a selection", idsIn(sessions, "/").none { it == "unknown" })
        // A drive root is a different scope: nothing in the fixture is on C:.
        assertTrue(idsIn(sessions, "C:\\").isEmpty())
    }

    @Test
    fun `sandboxes widen the project scope`() {
        // Measured live: project `…\cyber-watcher\.worktrees\foundation-contracts`
        // carries sandbox `…\cyber-watcher`, and
        // `directories(project) = [project.worktree, ...(project.sandboxes ?? [])]`
        // (`home/projects/controller.tsx:41-43`) is what the filter is scoped by:
        // sessions in the sandbox belong to the worktree project too.
        val worktree = "C:\\sensorsdata\\main\\program\\cyber-watcher\\.worktrees\\foundation-contracts"
        val sandbox = "C:\\sensorsdata\\main\\program\\cyber-watcher"
        val opened = listOf(HomeProjectRecords.Project(worktree, sandboxes = listOf(sandbox)))

        val directories = HomeProjects.directoriesFor(worktree, opened)
        assertEquals(listOf(worktree, sandbox), directories)

        val sessions = listOf(
            session("worktree", "$worktree\\packages\\app", 30L),
            session("sandbox", sandbox, 20L),
            session("elsewhere", "C:\\sensorsdata\\main\\program\\other", 10L),
        )
        assertEquals(listOf("worktree", "sandbox"), ids(sessions, directories))
        // Without the record the scope is the worktree alone: nothing is invented.
        assertEquals(listOf("worktree"), ids(sessions, HomeProjects.directoriesFor(worktree, emptyList())))
        // No selection → the unscoped list, exactly as before.
        assertTrue(HomeProjects.directoriesFor(null, opened).isEmpty())
        assertTrue(HomeProjects.directoriesFor("  ", opened).isEmpty())
        assertEquals(listOf("worktree", "sandbox", "elsewhere"), ids(sessions, HomeProjects.directoriesFor(null, opened)))
    }

    // -------------------------------------------------- the column's own source

    @Test
    fun `the column's rows come from the store, not from the server list`() {
        // The measured `GET /project` is every worktree the server ever
        // registered: `/`, `C:\Windows\System32`, six `…\workspace` duplicates and
        // directories the user never opened. `openRows` takes membership and order
        // from the store and uses the catalogue as a lookup only.
        val known = HomeProjects.merge(
            projects = listOf(
                project("/", iconColor = "mint"),
                project("C:\\Windows\\System32"),
                project("C:\\Users\\me\\AppData\\Local\\Temp\\cw-1\\workspace"),
                project("C:\\Users\\me\\AppData\\Local\\Temp\\cw-2\\workspace"),
            ),
            sessions = listOf(session("s1", "/repo/unopened", 10L)),
        )

        val workspace = "C:\\Users\\me\\AppData\\Local\\Temp\\cw-1\\workspace"
        val rows = HomeProjects.openRows(
            opened = listOf(HomeProjectRecords.Project(workspace)),
            known = known,
        )

        assertEquals(listOf(workspace), rows.map { it.path })
        assertEquals(listOf("workspace"), rows.map { it.name })
        // Nothing from the server list leaks in — not the second `…\workspace`,
        // not `/`, not the system directory, not the session-derived directory.
        assertFalse(rows.any { it.path == "C:\\Windows\\System32" || it.path == "/" })
        assertTrue(HomeProjects.openRows(emptyList(), known).isEmpty())
    }

    @Test
    fun `opened projects keep store order and borrow the catalogue's metadata`() {
        val known = HomeProjects.merge(
            projects = listOf(project("/repo/a", iconColor = "mint")),
            sessions = listOf(session("s1", "/repo/a", 10L)),
        )
        val rows = HomeProjects.openRows(
            opened = listOf(
                HomeProjectRecords.Project("C:\\src\\app"),
                HomeProjectRecords.Project("/repo/a/"),
            ),
            known = known,
        )

        // Store order, not the catalogue's.
        assertEquals(listOf("C:\\src\\app", "/repo/a/"), rows.map { it.path })
        assertEquals(listOf("app", "a"), rows.map { it.name })
        // The catalogue only decorates: unknown ⇒ no tint, and a worktree matched
        // through a trailing separator still finds its icon/count.
        assertEquals(listOf(null, "mint"), rows.map { it.iconColor })
        assertEquals(listOf(0, 1), rows.map { it.sessionCount })
        assertEquals(listOf(emptyList<String>(), emptyList<String>()), rows.map { it.sandboxes })
    }

    // -------------------------------------------------- archived + cap interaction

    @Test
    fun `the archived filter still applies inside a selection`() {
        val sessions = listOf(
            session("live", "/work/api", 20L),
            session("archived", "/work/api", 30L, archivedAt = 5L),
            session("other", "/work/site", 40L),
        )

        assertEquals(listOf("live"), idsIn(sessions, "/work/api"))
        // `PATCH {"time":{"archived":0}}` writes 0, which is not an archive.
        assertEquals(listOf("back"), idsIn(listOf(session("back", "/work/api", 10L, archivedAt = 0L)), "/work/api"))
    }

    @Test
    fun `a project whose only sessions are archived renders its empty state`() {
        val sessions = listOf(
            session("archived", "/work/api", 30L, archivedAt = 1L),
            session("live", "/work/site", 20L),
        )

        // The archive filter runs first, so the selected project has no rows at
        // all and the column falls back to `HomeSessionsEmpty` (whose "new
        // session" entry is the scoped one).
        assertTrue(idsIn(sessions, "/work/api").isEmpty())
        assertEquals(listOf("live"), idsIn(sessions, null))
    }

    @Test
    fun `the 64 row cap is spent on the selected project's rows`() {
        // Upstream filters by project and *then* slices to 64
        // (`records.ts:19-23` → `controller.tsx:91`), so 70 sessions in the
        // selected project still fill all 64 rows even though other projects
        // hold newer ones.
        val sessions = (0 until 70).map { session("api$it", "/work/api", 1_000L + it) } +
            (0 until 5).map { session("site$it", "/work/site", 5_000L + it) }

        val rows = HomeSessions.rows(sessions, directories = listOf("/work/api"))

        assertEquals(64, rows.size)
        assertEquals(HomeSessions.MAX_SESSIONS, rows.size)
        assertEquals("api69", rows.first().id)
        assertTrue(rows.none { it.id.startsWith("site") })
    }

    @Test
    fun `grouping and ordering are unchanged by a selection`() {
        val now = at("2024-05-20T12:00:00Z")
        val sessions = listOf(
            session("today", "/work/api", at("2024-05-20T08:00:00Z")),
            session("yesterday", "/work/api", at("2024-05-19T08:00:00Z")),
            session("older", "/work/api", at("2024-04-01T08:00:00Z")),
            session("elsewhere", "/work/site", at("2024-05-20T09:00:00Z")),
        )

        val sections = HomeSessions.sections(HomeSessions.rows(sessions, directories = listOf("/work/api")), now, zone)

        assertEquals(
            listOf(HomeSessions.Group.TODAY, HomeSessions.Group.YESTERDAY, HomeSessions.Group.OLDER),
            sections.map { it.group },
        )
        assertEquals(listOf("today"), sections[0].rows.map { it.id })
        assertEquals(listOf("yesterday"), sections[1].rows.map { it.id })
        assertEquals(listOf("older"), sections[2].rows.map { it.id })
        // The search filter still sees the scoped rows and nothing else.
        assertEquals(
            listOf("today"),
            HomeSessions.filter(HomeSessions.rows(sessions, directories = listOf("/work/api")), "today").map { it.id },
        )
    }

    // ----------------------------------------------------- the placeholder copy

    @Test
    fun `the search placeholder is scoped only while a project is selected`() {
        // `search.ts:45-53`: no project → `home.sessions.search.placeholder`;
        // a project → `…placeholder.scoped` with `displayName(project)`.
        assertNull(HomeSessions.placeholderScope(null))
        assertNull(HomeSessions.placeholderScope(""))
        assertNull(HomeSessions.placeholderScope("   "))

        assertEquals("api", HomeSessions.placeholderScope("/work/api"))
        assertEquals("api", HomeSessions.placeholderScope("/work/api/"))
        assertEquals("app", HomeSessions.placeholderScope("C:\\src\\app\\"))
    }

    @Test
    fun `a directory without a usable name is its own scope`() {
        // `displayName` (`shell/layout/helpers.ts:50`) falls back to the worktree
        // when the last segment is empty — `/` and a drive root have no name.
        assertEquals("/", HomeSessions.placeholderScope("/"))
        assertEquals("C:\\", HomeSessions.placeholderScope("C:\\"))
        // A trailing separator on a named directory is not a name either: the
        // segment before it is.
        assertEquals("me", HomeSessions.placeholderScope("/home/me/"))
    }

    // ----------------------------------------------------- untouched neighbours

    @Test
    fun `project counts stay over every session, selection or not`() {
        // `HomeProjects.merge` takes no selection: the catalogue's counts and
        // order are the same before and after picking a project. The filter is
        // applied to the *sessions list*, never to the merge input.
        val sessions = listOf(
            session("api1", "/work/api", 30L),
            session("api2", "/work/api", 20L),
            session("site", "/work/site", 10L),
        )

        val merged = HomeProjects.merge(emptyList(), sessions)

        assertEquals(listOf("/work/api", "/work/site"), merged.map { it.path })
        assertEquals(listOf(2, 1), merged.map { it.sessionCount })
        assertEquals(2, merged.single { it.path == "/work/api" }.sessionCount)
    }

    // ------------------------------------------------------------- the wiring

    @Test
    fun `home feeds the selection into the rows and the placeholder`() {
        // The only claim a JVM test cannot reach: HomeScreen has to pass the
        // selected project's directories down. Pure-logic tests above would stay
        // green if the call sites dropped it again.
        val home = homeScreenSource()

        assertTrue("the rows are built by HomeSessions.rows", home.contains("HomeSessions.rows("))
        assertTrue(
            "the session rows must be scoped to the selected project's directories",
            home.contains(
                "directories = HomeProjects.directoriesFor(selectedProject, openedProjects.list, known = projects)",
            ),
        )
        assertTrue(
            "the sessions column must be told the search scope",
            home.contains("HomeSessions.placeholderScope(selectedDirectory)"),
        )
        assertTrue(home.contains("searchScope = searchScope"))
        assertTrue(
            "clicking the selected project again clears the selection",
            home.contains("if (selectedProject == project.path) null else project.path"),
        )
    }

    @Test
    fun `home reads the local project store and never the server list for rows`() {
        val home = homeScreenSource()

        // `home/model.ts:20-21`: the store is the column's source…
        assertTrue(home.contains("homeProjectStore?.state?.collectAsStateWithLifecycle()"))
        assertTrue(home.contains("HomeProjects.openRows("))
        assertTrue(home.contains("HomeProjects.closedRows(openedProjects.recentlyClosed, names = serverNames)"))
        // …and `GET /project` only feeds the enrichment lookup, never the list.
        assertTrue(home.contains("known = HomeProjects.merge(serverProjects.orEmpty(), visibleSessions, names = serverNames)"))
        assertFalse(
            "the column must not render a project list built straight from GET /project",
            home.contains("recentlyClosed = HomeProjects.RECENTLY_CLOSED"),
        )
        // The server catalogue's sandboxes reach the filter through the column's
        // own rows (`directoriesFor`'s `known`).
        assertTrue(
            "the catalogue's sandboxes must widen the scope",
            home.contains("HomeProjects.directoriesFor(selectedProject, openedProjects.list, known = projects)"),
        )
        // The 打开项目 entry persists what it picked, and closing/restoring go
        // through the store's own operations.
        assertTrue(home.contains("homeProjectStore?.open(directory)"))
        assertTrue(home.contains("homeProjectStore?.touch(directory)"))
        assertTrue(home.contains("homeProjectStore?.close(directory)"))
    }

    @Test
    fun `home picks a project directory on the server, not on the device`() {
        // `home/projects/controller.tsx:49-56`: `pickDirectory({ server })` browses
        // the **server's** filesystem through the shared directory browser. The
        // SAF tree picker (`ActivityResultContracts.OpenDocumentTree`) is gone
        // with the whole `HomeProjectPaths` conversion — a device folder is not a
        // server directory.
        val home = homeScreenSource()

        assertTrue(home.contains("DirectoryPickerDialog("))
        assertTrue(home.contains("dialog.show(DialogSize.FIT)"))
        assertFalse("the device-side SAF picker is gone", home.contains("ActivityResultContracts"))
        assertFalse(home.contains("OpenDocumentTree"))
        assertFalse(home.contains("HomeProjectPaths"))
        assertFalse("no tree-URI conversion may survive", File("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectPicker.kt").isFile)
    }

    @Test
    fun `opening a session from home registers its directory in the sidebar`() {
        // `home/model.ts:56-66`: `openProjectSession` does
        // `ctx.projects.open(directory)` + `ctx.projects.touch(directory)`, which
        // is what makes a session opened from Home appear in the 项目 column.
        // Selecting a project row `touch`es it too (`projects.touch` is the MRU
        // marker `newSessionProject()` reads).
        val home = homeScreenSource()

        assertTrue(
            "a session-row tap must register the session's own directory",
            home.contains("directoryById[id]?.let { registerProject(it) }"),
        )
        assertTrue("both session lists go through it", home.contains("onOpenSession = onOpenSessionRow"))
        assertTrue(home.contains("homeProjectStore?.open(directory)"))
        assertTrue(home.contains("homeProjectStore?.touch(directory)"))
        assertTrue(
            "selecting a project row touches it",
            home.contains("homeProjectStore?.touch(project.path)"),
        )
        // The registration must not move the selection: only an *add* does that
        // (`home/model.ts:101-120`), a session open does not.
        assertFalse(
            "a session open must not select the project",
            home.contains("directoryById[id]?.let { openProject(it) }"),
        )
    }

    private fun homeScreenSource(): String =
        listOf(
            File("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt"),
            File("app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt"),
        ).firstOrNull { it.isFile }?.readText() ?: error("missing ui/home/HomeScreen.kt")
}
