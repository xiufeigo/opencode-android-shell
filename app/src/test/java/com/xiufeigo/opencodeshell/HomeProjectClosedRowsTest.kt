package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.data.HomeProjectRecords
import com.xiufeigo.opencodeshell.ui.home.HomeProjects
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3: 「关闭」 a project must take its row out of the column instead of leaving
 * it sitting beside the live rows.
 *
 * `/api/project` has no delete route (`protocol/src/groups/project.ts` is list +
 * update only) and upstream's own row menu has no delete entry either
 * (`home/projects/view.tsx:731-760`): 「Close」 is a **local** removal — it drops
 * the project from `projects.list` and pushes it onto `recentlyClosed`
 * (`runtime/server/registry.tsx:70-75`). That stack is rendered in exactly one
 * place upstream: `HomeProjectEmpty` (`view.tsx:219,585-589`), the fallback used
 * once the column has no open project left. This shell rendered it whenever it
 * was non-empty, so a just-closed project stayed in the same list — and on the
 * mobile dropdown without its 最近关闭 label at all, i.e. an unlabelled row that
 * 「关闭」 appeared not to remove.
 */
class HomeProjectClosedRowsTest {

    private fun session(id: String, directory: String, updatedAt: Long) =
        OpenCodeClient.Session(id = id, title = id, directory = directory, updatedAt = updatedAt)

    private fun project(worktree: String) =
        OpenCodeClient.Project(id = worktree, worktree = worktree, vcs = "git", iconColor = null)

    /** `projects.open()` in argument order, so the last one is the first row. */
    private fun opened(vararg worktrees: String) =
        worktrees.fold(HomeProjectRecords.State()) { state, worktree ->
            HomeProjectRecords.open(state, worktree)
        }

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun list() = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectsList.kt")

    // ---- the closed row leaves the column ----------------------------------

    @Test
    fun `a closed project leaves the column even though the server still lists it`() {
        val state = HomeProjectRecords.close(opened("/work/api", "/work/site", "/work/app"), "/work/api")
        // `GET /project` is persistent: it keeps listing every worktree the
        // server ever registered, the closed one included.
        val known = HomeProjects.merge(
            projects = listOf(project("/work/api"), project("/work/site"), project("/work/app")),
            sessions = listOf(session("s1", "/work/api", 10L)),
        )

        val rows = HomeProjects.openRows(
            opened = state.list,
            known = known,
            closed = state.recentlyClosed,
        )

        assertEquals(listOf("/work/app", "/work/site"), rows.map { it.path })
        assertFalse("the closed project must not render as a project row", rows.any { it.path == "/work/api" })
        // Nothing draws it elsewhere either while rows are left.
        assertFalse(HomeProjects.showsClosed(rows, HomeProjects.closedRows(state.recentlyClosed)))
    }

    @Test
    fun `the closed stack is what is left once the column empties`() {
        val state = HomeProjectRecords.close(opened("/work/api"), "/work/api")
        val rows = HomeProjects.openRows(opened = state.list, known = emptyList(), closed = state.recentlyClosed)

        assertTrue(rows.isEmpty())
        val closed = HomeProjects.closedRows(state.recentlyClosed)
        assertTrue(HomeProjects.showsClosed(rows, closed))
        assertEquals(listOf("/work/api"), closed.map { it.path })
        assertEquals(listOf("api"), closed.map { it.name })
    }

    @Test
    fun `closing one of several projects keeps the other rows`() {
        val state = HomeProjectRecords.close(opened("/work/api", "/work/site"), "/work/site")
        val rows = HomeProjects.openRows(opened = state.list, known = emptyList(), closed = state.recentlyClosed)

        assertEquals(listOf("/work/api"), rows.map { it.path })
        assertFalse(HomeProjects.showsClosed(rows, HomeProjects.closedRows(state.recentlyClosed)))
    }

    @Test
    fun `reopening from the closed stack puts the row back and empties the stack`() {
        val state = HomeProjectRecords.close(opened("/work/api", "/work/site"), "/work/api")
        val reopened = HomeProjectRecords.open(state, "/work/api")

        val rows = HomeProjects.openRows(
            opened = reopened.list,
            known = emptyList(),
            closed = reopened.recentlyClosed,
        )

        assertEquals(listOf("/work/api", "/work/site"), rows.map { it.path })
        assertFalse(HomeProjects.showsClosed(rows, HomeProjects.closedRows(reopened.recentlyClosed)))
    }

    @Test
    fun `showsClosed needs both an empty column and something closed`() {
        val closed = HomeProjects.closedRows(listOf("/work/api"))
        assertTrue(HomeProjects.showsClosed(emptyList(), closed))
        assertFalse(HomeProjects.showsClosed(HomeProjects.closedRows(listOf("/work/site")), closed))
        assertFalse(HomeProjects.showsClosed(emptyList(), emptyList()))
    }

    // ---- the wiring --------------------------------------------------------

    @Test
    fun `both mount points gate the closed stack on the empty column`() {
        val body = list()
        // Upstream `HomeProjectEmpty` is the only host, so neither mount point
        // may draw a closed row beside a live one.
        assertEquals(2, Regex("HomeProjects\\.showsClosed\\(projects, recentlyClosed\\)").findAll(body).count())
        assertTrue(body.contains("internal fun HomeProjectsColumn("))
        assertTrue(body.contains("internal fun HomeProjectsDropdown("))
        assertFalse("the always-on gate is gone", body.contains("if (recentlyClosed.isNotEmpty())"))
    }

    @Test
    fun `the shared closed section carries the header both hosts use`() {
        val body = list()
        assertTrue(body.contains("private fun RecentlyClosedSection("))
        assertTrue(body.contains("R.string.oc_home_recently_closed"))
        listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-zh-rCN/strings.xml",
            "src/main/res/values-zh-rTW/strings.xml",
        ).forEach { relative ->
            assertTrue(relative, source(relative).contains("name=\"oc_home_recently_closed\""))
        }
    }
}
