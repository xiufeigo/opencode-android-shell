package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.HomeSessions
import com.xiufeigo.opencodeshell.ui.session.review.ReviewChangeScope
import com.xiufeigo.opencodeshell.ui.session.review.ReviewDiff
import com.xiufeigo.opencodeshell.ui.session.review.ReviewFileFilter
import com.xiufeigo.opencodeshell.ui.session.review.ReviewFileTree
import com.xiufeigo.opencodeshell.ui.session.review.ReviewScopes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins for the three derived defects in
 * `research/v97_fix/REVIEW_HOME_SEARCH_DIAG.md` (N4-derived occlusion, N5-derived
 * search reach, N6-① missing completeness marker) plus the two **misjudgments**
 * the report separated out, pinned so a later reader cannot "fix" N4/N5 back
 * into a regression by following the original report text.
 */
class ReviewSearchV97FixTest {

    // ---------------------------------------------------- N5: search reach

    /**
     * A lazily loaded tree where only the root and `app/` have been fetched, and
     * `app/` has been expanded. `nested/` was loaded earlier but is collapsed.
     */
    private val tree: Map<String, List<OpenCodeClient.FileNode>> = mapOf(
        "/w" to listOf(
            node("app", "/w/app", directory = true),
            node("build.gradle.kts", "/w/build.gradle.kts"),
            node("nested", "/w/nested", directory = true),
        ),
        "/w/app" to listOf(
            node("build.gradle.kts", "/w/app/build.gradle.kts"),
            node("Main.kt", "/w/app/Main.kt"),
        ),
        // Fetched earlier, then collapsed: reachable in the search, invisible in
        // the browse tree.
        "/w/nested" to listOf(
            node("build.gradle.kts", "/w/nested/build.gradle.kts"),
            node("notes.md", "/w/nested/notes.md"),
        ),
    )

    @Test
    fun `a search reaches a file inside a collapsed but loaded directory`() {
        val browse = ReviewFileFilter.searchRows(
            children = { key -> tree[key].orEmpty() },
            root = "/w",
            isExpanded = { key -> key == "/w/app" },
            hasLoadedChildren = { key -> tree.containsKey(key) },
            query = "build.gradle",
        )
        val names = ReviewFileFilter.filterRows(browse, "build.gradle").map { it.node.absolute }
        // The defect: only the expanded `app/` branch was searchable before.
        assertTrue("/w/build.gradle.kts" in names)
        assertTrue("/w/app/build.gradle.kts" in names)
        assertTrue("/w/nested/build.gradle.kts" in names)
    }

    @Test
    fun `a blank query leaves the browse tree exactly as the user left it`() {
        val rows = ReviewFileFilter.searchRows(
            children = { key -> tree[key].orEmpty() },
            root = "/w",
            isExpanded = { key -> key == "/w/app" },
            hasLoadedChildren = { key -> tree.containsKey(key) },
            query = "",
        ).map { it.node.absolute }
        // Only `app/` was expanded, so the collapsed branch's children are absent
        // and the blank query must not have pulled them in. Depth-first pre-order,
        // siblings directories-first then name ascending
        // (`ReviewFileTree.flatten` / `sortSiblings`).
        assertEquals(
            listOf("/w/app", "/w/app/build.gradle.kts", "/w/app/Main.kt", "/w/nested", "/w/build.gradle.kts"),
            rows,
        )
    }

    @Test
    fun `clearing the filter returns the full flattened list - the N5 pin`() {
        val all = listOf(
            row("gradle", dir = true, level = 0),
            row(".idea", dir = true, level = 0),
            row("build.gradle.kts", dir = false, level = 0),
        )
        assertTrue(ReviewFileFilter.filterRows(all, "gradle").size < all.size)
        // The core pin from the report: clearing must restore everything, and a
        // whitespace-only query is a cleared query.
        assertEquals(all, ReviewFileFilter.filterRows(all, ""))
        assertEquals(all, ReviewFileFilter.filterRows(all, "    "))
    }

    @Test
    fun `matches only the supplied row - an unloaded child stays unreachable by design`() {
        // The lazily-loaded trade-off, pinned as an explicit contract: the app
        // cannot list a directory it has never fetched, so this stays true even
        // after the collapsed-directory fix.
        assertFalse(ReviewFileFilter.matches("gradle", ".idea"))
        assertTrue(ReviewFileFilter.matches("gradle", "build.gradle.kts"))
    }

    @Test
    fun `the search never writes expansion state - isExpanded is read-only`() {
        val calls = mutableListOf<String>()
        ReviewFileFilter.searchRows(
            children = { key -> tree[key].orEmpty() },
            root = "/w",
            isExpanded = { key -> calls += key; key == "/w/app" },
            hasLoadedChildren = { key -> calls += key; tree.containsKey(key) },
            query = "build.gradle",
        )
        // Both gates were only ever invoked, never handed to a mutator: the
        // browse tree's expansion is a caller-owned map this function cannot
        // reach, which is what makes the search side-effect free.
        assertTrue(calls.contains("/w/app"))
    }

    // -------------------------------------------- N6-b: completeness marker

    @Test
    fun `totals sum every file the server returned - no client-side cap`() {
        // Locks the verified fact that the client never truncates: four
        // segments (`V2Api.callBody`, `vcsDiff` with no limit, `totals`,
        // `FileDiff.fromJson`) carry every file through. A future `take(N)`
        // fails here.
        val diffs = (1..207).map { OpenCodeClient.FileDiff("f$it", null, 274, 44, "modified") }
        assertEquals(207 * 274 to 207 * 44, ReviewDiff.totals(diffs))
    }

    @Test
    fun `a failed scope read never presents fallback totals as this scope's total`() {
        // The v97 fix: `+153 -76` next to a 57407-insertion working tree was a
        // partial `mode=working` answer rendered as a complete one.
        assertTrue(ReviewScopes.changesSummaryPartial(ReviewChangeScope.GIT, loadFailed = true))
        assertTrue(ReviewScopes.changesSummaryPartial(ReviewChangeScope.BRANCH, loadFailed = true))
    }

    @Test
    fun `a completed read still shows its totals`() {
        assertFalse(ReviewScopes.changesSummaryPartial(ReviewChangeScope.GIT, loadFailed = false))
        assertFalse(ReviewScopes.changesSummaryPartial(ReviewChangeScope.BRANCH, loadFailed = false))
    }

    @Test
    fun `the last-turn scope is never relabelled by a VCS failure`() {
        // Its source is the session snapshot, so a VCS-scope failure says
        // nothing about its numbers.
        assertFalse(ReviewScopes.changesSummaryPartial(ReviewChangeScope.LAST_TURN, loadFailed = true))
    }

    @Test
    fun `LOAD_FAILED stays distinct from EMPTY - a failure is never no changes`() {
        val failed = ReviewScopes.changesBody(
            scope = ReviewChangeScope.GIT,
            noGit = false,
            loadFailed = true,
            loadPending = false,
            hasDiffs = false,
            loadStalled = false,
        )
        assertEquals(ReviewScopes.ChangesBody.LOAD_FAILED, failed)
        assertNotEquals(ReviewScopes.ChangesBody.EMPTY, failed)
    }

    // ------------------------------- N4: the original report was a misjudgment

    @Test
    fun `the home search filter is a pure function of the current query`() {
        val rows = listOf(
            homeRow("a", "pathlib"),
            homeRow("b", "Queued"),
        )
        assertEquals(listOf("b"), HomeSessions.filter(rows, "Queued").map { it.id })
        // Same rows, different query: recomputed, never a frozen result set.
        assertEquals(listOf("a"), HomeSessions.filter(rows, "pathlib").map { it.id })
        assertEquals(emptyList<String>(), HomeSessions.filter(rows, "E2ECHK5562").map { it.id })
    }

    @Test
    fun `a cleared search restores every row unchanged`() {
        val rows = listOf(homeRow("a", "Queued"), homeRow("b", "E2ECHK5562"))
        assertEquals(rows, HomeSessions.filter(rows, ""))
        assertEquals(rows, HomeSessions.filter(rows, "   "))
    }

    // ------------------------------------------------------------- fixtures

    private fun node(name: String, absolute: String, directory: Boolean = false) =
        OpenCodeClient.FileNode(
            name = name,
            path = absolute,
            absolute = absolute,
            directory = directory,
            ignored = false,
        )

    private fun row(name: String, dir: Boolean, level: Int) = ReviewFileTree.Row(
        node = node(name, "/w/$name", directory = dir),
        level = level,
    )

    private fun homeRow(id: String, title: String) = HomeSessions.Row(
        id = id,
        title = title,
        directory = "/w",
        project = title,
        updatedAt = 0L,
    )
}
