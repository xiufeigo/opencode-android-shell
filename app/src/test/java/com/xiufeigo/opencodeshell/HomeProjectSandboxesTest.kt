package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.data.HomeProjectRecords
import com.xiufeigo.opencodeshell.ui.home.HomeProjects
import com.xiufeigo.opencodeshell.ui.home.HomeSessions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `project.sandboxes` — the server-declared extra directories of a project.
 *
 * Two halves:
 *
 * 1. the wire shape (`GET /project`, parsed by [OpenCodeClient.Project.fromJson]):
 *    a JSON array of strings; absent / `null` / `[]` / a non-array value all read
 *    as "no sandboxes", never as an exception;
 * 2. the scope it widens (`directories(project) = [worktree, ...sandboxes]`,
 *    `_upstream/packages/app/src/home/projects/controller.tsx:41-43`), which is
 *    what the sessions filter walks ([HomeSessions.rows] via
 *    [HomeProjects.directoriesFor]).
 *
 * Measured live on 1.18.31: the worktree registered from
 * `…\cyber-watcher\.worktrees\foundation-contracts` carries the sandbox
 * `…\cyber-watcher`; every other project in the catalogue carries `[]`.
 */
class HomeProjectSandboxesTest {

    // -------------------------------------------------------- the wire shape

    @Test
    fun `an absent or empty sandboxes array reads as no sandboxes`() {
        // Every project the live server lists but the worktree one answers with
        // `"sandboxes":[]`, and an older/other server may omit the field
        // entirely — both are "no sandboxes", never a throw.
        assertEquals(emptyList<String>(), projectJson("""{"id":"a","worktree":"/repo/a"}""").sandboxes)
        assertEquals(emptyList<String>(), projectJson("""{"id":"a","worktree":"/repo/a","sandboxes":[]}""").sandboxes)
    }

    @Test
    fun `a null or non-array sandboxes value reads as no sandboxes`() {
        // `optJSONArray` is `null` for every non-array JSON value, including the
        // literal string "null" a sloppy serializer can emit (the same trap
        // `Session.fromJson` documents). The read must not throw.
        assertEquals(emptyList<String>(), projectJson("""{"worktree":"/a","sandboxes":null}""").sandboxes)
        assertEquals(emptyList<String>(), projectJson("""{"worktree":"/a","sandboxes":"null"}""").sandboxes)
        assertEquals(emptyList<String>(), projectJson("""{"worktree":"/a","sandboxes":42}""").sandboxes)
        assertEquals(emptyList<String>(), projectJson("""{"worktree":"/a","sandboxes":{}}""").sandboxes)
    }

    @Test
    fun `sandboxes carry the server's directories and survive the entry filter`() {
        val project = projectJson(
            """
            {"id":"d5a1","worktree":"C:\\sensorsdata\\main\\program\\cyber-watcher\\.worktrees\\foundation-contracts",
             "vcs":"git","icon":{"color":"orange"},
             "sandboxes":["C:\\sensorsdata\\main\\program\\cyber-watcher"]}
            """.trimIndent(),
        )

        assertEquals(
            listOf("C:\\sensorsdata\\main\\program\\cyber-watcher"),
            project.sandboxes,
        )
        // A `worktree`-less entry is still dropped, sandboxes or not.
        assertNull(OpenCodeClient.Project.fromJson(JSONObject("""{"sandboxes":["/x"]}""")))
    }

    @Test
    fun `only usable sandbox entries survive`() {
        val sandboxes = JSONArray()
            .put("  /repo/a  ")
            .put("")
            .put("   ")
            .put(JSONObject())
            .put(42)
            .put(JSONObject.NULL)
            .put("/repo/b")

        val project = OpenCodeClient.Project.fromJson(
            JSONObject().put("worktree", "/repo/a").put("sandboxes", sandboxes),
        )

        // Trimmed, blanks and non-strings dropped — the filter is handed paths.
        assertEquals(listOf("/repo/a", "/repo/b"), project?.sandboxes)
    }

    // ----------------------------------------------- the scope it widens

    @Test
    fun `the catalogue's sandboxes widen the selected project's scope`() {
        // The store record carries no sandboxes (the project was opened by hand
        // before the catalogue answered), so `directoriesFor` has to take them
        // from the server-declared catalogue — the column's own rows.
        val worktree = "C:\\sensorsdata\\main\\program\\cyber-watcher\\.worktrees\\foundation-contracts"
        val sandbox = "C:\\sensorsdata\\main\\program\\cyber-watcher"
        val opened = listOf(HomeProjectRecords.Project(worktree))
        val known = HomeProjects.openRows(
            opened = opened,
            known = HomeProjects.merge(
                projects = listOf(
                    OpenCodeClient.Project(
                        id = "d5a1",
                        worktree = worktree,
                        vcs = "git",
                        iconColor = "orange",
                        sandboxes = listOf(sandbox),
                    ),
                ),
                sessions = emptyList(),
            ),
        )

        // The row itself carries the union, so anything reading the column sees
        // the server's sandboxes too.
        assertEquals(listOf(sandbox), known.single().sandboxes)
        assertEquals(listOf(worktree, sandbox), HomeProjects.directoriesFor(worktree, opened, known = known))

        val sessions = listOf(
            session("worktree", "$worktree\\packages\\app", 30L),
            session("sandbox", sandbox, 20L),
            session("elsewhere", "C:\\sensorsdata\\main\\program\\other", 10L),
        )

        assertEquals(
            listOf("worktree", "sandbox"),
            HomeSessions.rows(
                sessions,
                directories = HomeProjects.directoriesFor(worktree, opened, known = known),
            ).map { it.id },
        )
    }

    @Test
    fun `the record's and the catalogue's sandboxes are one de-duplicated list`() {
        // Same directory spelled three ways across the two sources: one entry.
        val sandboxes = HomeProjects.sandboxesOf(
            listOf("/repo/sandbox", "  "),
            listOf("/repo/sandbox/", "C:\\repo\\other"),
        )

        assertEquals(listOf("/repo/sandbox", "C:\\repo\\other"), sandboxes)
        // A Windows directory is compared case-insensitively, a POSIX one is not
        // (`ProjectPaths.matchKey`), so `C:\repo\other` and `c:\REPO\Other`
        // collapse while two POSIX spellings stay apart.
        assertEquals(
            listOf("C:\\repo\\other"),
            HomeProjects.sandboxesOf(listOf("C:\\repo\\other"), listOf("c:\\REPO\\Other")),
        )
        assertEquals(
            listOf("/repo/other", "/repo/Other"),
            HomeProjects.sandboxesOf(listOf("/repo/other"), listOf("/repo/Other")),
        )
    }

    @Test
    fun `a project's sessions are listed when only case or separators differ`() {
        // The Windows filesystem is case-insensitive, so the server may hand back
        // a worktree/session directory that differs from the picked one by case
        // alone, or use `/` where the picker used `\` — `WorkspacePaths.matchKey`
        // folds both, and the scope must not care which spelling the user picked.
        val opened = listOf(HomeProjectRecords.Project("C:\\Windows\\System32"))
        val sessions = listOf(
            session("exact", "C:\\Windows\\System32", 30L),
            session("case", "c:\\windows\\system32", 20L),
            session("separators", "C:/Windows/System32", 10L),
            session("elsewhere", "C:\\Windows\\SysWOW64", 5L),
        )

        for (selected in listOf("C:\\Windows\\System32", "c:/WINDOWS/system32")) {
            assertEquals(
                "selected $selected",
                listOf("exact", "case", "separators"),
                HomeSessions.rows(
                    sessions,
                    directories = HomeProjects.directoriesFor(selected, opened),
                ).map { it.id },
            )
        }
    }

    @Test
    fun `a project the catalogue does not list keeps its own scope`() {
        // `/a-long-name` is a sibling prefix, not a child: `containsDirectory`
        // compares on a path boundary.
        val opened = listOf(HomeProjectRecords.Project("/repo/a"))
        val sessions = listOf(
            session("child", "/repo/a/pkg", 30L),
            session("sibling", "/repo/a-long-name", 20L),
        )

        assertEquals(
            listOf("child"),
            HomeSessions.rows(
                sessions,
                directories = HomeProjects.directoriesFor("/repo/a", opened, known = emptyList()),
            ).map { it.id },
        )
        assertEquals(
            listOf("/repo/a"),
            HomeProjects.directoriesFor("/repo/a", opened, known = emptyList()),
        )
    }

    private fun projectJson(json: String): OpenCodeClient.Project =
        OpenCodeClient.Project.fromJson(JSONObject(json)) ?: error("project not parsed: $json")

    private fun session(
        id: String,
        directory: String? = null,
        updatedAt: Long = 0L,
    ) = OpenCodeClient.Session(
        id = id,
        title = id,
        directory = directory,
        updatedAt = updatedAt,
    )
}
