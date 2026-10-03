package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.WORKSPACE_FILTER_ALL
import com.xiufeigo.opencodeshell.ui.settings.WorkspaceRow
import com.xiufeigo.opencodeshell.ui.settings.filterWorkspaceRows
import com.xiufeigo.opencodeshell.ui.settings.sessionRows
import com.xiufeigo.opencodeshell.ui.settings.sessionsForWorkspace
import com.xiufeigo.opencodeshell.ui.settings.workspaceProjectOptions
import com.xiufeigo.opencodeshell.ui.settings.workspaceRows
import com.xiufeigo.opencodeshell.ui.settings.workspacesWithoutSessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-10: the pure half of the Workspaces page — the inventory parser, the project
 * filter and the linked-session rules
 * (`settings/workspaces/workspaces.tsx:87-173`,
 * `workspaces/paths.ts:31-70`).
 */
class WorkspacePanelModelTest {

    @Test
    fun inventoryRowsCarryTheirOwner() {
        val rows = workspaceRows(
            """
            [
              {"directory":"/w/a/.worktrees/x","branch":"feat","projectID":"p1","projectName":"alpha"},
              {"worktree":"/w/b/.worktrees/y","project":{"id":"p2","name":"beta"}},
              {"name":"named","directory":"/w/c/.worktrees/z","ref":"main"},
              {"directory":""},
              {"branch":"orphan"}
            ]
            """.trimIndent(),
        )
        assertEquals(listOf("/w/a/.worktrees/x", "/w/b/.worktrees/y", "/w/c/.worktrees/z"), rows.map { it.directory })
        assertEquals("feat", rows[0].branch)
        assertEquals("p1", rows[0].projectID)
        assertEquals("alpha", rows[0].projectName)
        // A v1-style nested owner object parses too.
        assertEquals("p2", rows[1].projectID)
        assertEquals("beta", rows[1].projectName)
        // A row with no owner keeps nulls — never a guessed project.
        assertNull(rows[2].projectID)
        assertEquals("named", rows[2].name)
        assertEquals("main", rows[2].branch)
    }

    @Test
    fun projectOptionsNeedMoreThanOneOwner() {
        val one = listOf(WorkspaceRow("/a", "a", projectID = "p1", projectName = "alpha"))
        assertTrue(workspaceProjectOptions(one).isEmpty())
        assertTrue(workspaceProjectOptions(emptyList()).isEmpty())

        val two = one + listOf(
            WorkspaceRow("/b", "b", projectID = "p2", projectName = "beta"),
            WorkspaceRow("/c", "c", projectID = "p1", projectName = "alpha"),
        )
        // "all" first, then each project once, in first-seen order.
        assertEquals(
            listOf(WORKSPACE_FILTER_ALL, "p1", "p2"),
            workspaceProjectOptions(two).map { it.first },
        )
    }

    @Test
    fun filterKeepsAllForTheSentinel() {
        val rows = listOf(
            WorkspaceRow("/a", "a", projectID = "p1"),
            WorkspaceRow("/b", "b", projectID = "p2"),
        )
        assertEquals(2, filterWorkspaceRows(rows, WORKSPACE_FILTER_ALL).size)
        assertEquals(listOf("/b"), filterWorkspaceRows(rows, "p2").map { it.directory })
        assertTrue(filterWorkspaceRows(rows, "missing").isEmpty())
    }

    @Test
    fun sessionsForWorkspaceFiltersArchivedAndSortsNewestFirst() {
        val sessions = listOf(
            session("s1", "/w/a/.worktrees/x", updated = 100L),
            session("s2", "/w/a/.worktrees/x/sub", updated = 300L),
            session("s3", "/w/a/.worktrees/x", updated = 200L, archived = 5L),
            session("s4", "/w/other", updated = 400L),
        )
        val linked = sessionsForWorkspace(sessions, "/w/a/.worktrees/x")
        // Nested directories count (upstream's `containsDirectory`), the
        // archived row does not, and the newest is first.
        assertEquals(listOf("s2", "s1"), linked.map { it.id })
        assertTrue(sessionsForWorkspace(sessions, "/w/none").isEmpty())
    }

    @Test
    fun withoutSessionsNeverReadsAFailedLoadAsEmpty() {
        val rows = listOf(
            WorkspaceRow("/a", "a"),
            WorkspaceRow("/b", "b"),
        )
        val empty = emptyMap<String, List<OpenCodeClient.Session>>()
        // Nothing loaded at all → the action has nothing to offer.
        assertTrue(workspacesWithoutSessions(rows, empty).isEmpty())

        val partial = mapOf(
            "/a" to emptyList<OpenCodeClient.Session>(),
            // "/b" is absent: its read failed, so it must not be a candidate.
        )
        assertEquals(listOf("/a"), workspacesWithoutSessions(rows, partial).map { it.directory })

        val both = mapOf(
            "/a" to listOf(session("s1", "/a", updated = 1L)),
            "/b" to emptyList<OpenCodeClient.Session>(),
        )
        assertEquals(listOf("/b"), workspacesWithoutSessions(rows, both).map { it.directory })
    }

    @Test
    fun sessionPayloadsParseBothGenerations() {
        // v1: a bare array. v2: `{data:[…]}` with the directory nested.
        val v1 = sessionRows("""[{"id":"s1","title":"t","directory":"/w/a","time":{"updated":10}}]""")
        assertEquals(listOf("s1"), v1.map { it.id })
        assertEquals("/w/a", v1.single().directory)

        val v2 = sessionRows("""{"data":[{"id":"s2","title":"t","location":{"directory":"/w/b"},"time":{"updated":20}}]}""")
        assertEquals("/w/b", v2.single().directory)
        assertEquals(20L, v2.single().updatedAt)

        assertTrue(sessionRows("").isEmpty())
        assertTrue(sessionRows("null").isEmpty())
    }

    private fun session(
        id: String,
        directory: String,
        updated: Long,
        archived: Long? = null,
    ) = OpenCodeClient.Session(
        id = id,
        title = id,
        directory = directory,
        updatedAt = updated,
        archivedAt = archived,
    )
}
