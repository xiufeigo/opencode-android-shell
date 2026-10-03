package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.OpenCodeClient.FileNode
import com.xiufeigo.opencodeshell.ui.session.review.ReviewFileFilter
import com.xiufeigo.opencodeshell.ui.session.review.ReviewFileTree
import com.xiufeigo.opencodeshell.ui.session.review.ReviewTreeRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v97 U11 (`research/v97_verify/5562_v98/REPORT_SWEEPD2.md` §3.11 / §4.3,
 * fixed in `research/v97_fix/REVIEW_FILTER_CLEAR_FIX.md`).
 *
 * The device read as: filter `gradle` → **Clear filter (✕)** → the list keeps
 * 2 rows (`.gradle`, `.idea`) and never returns the 15 it started from; the
 * next **Refresh** shows the same 2 rows, byte for byte, across two rounds.
 *
 * The report proposed `allRows` being cached **after** filtering. The dumps
 * rule that out, and the real cause is a different state entirely:
 *
 * - `h_07_afterclear.xml` — the 「Clear filter」 button is **gone** and the
 *   field is back to its `Filter files` placeholder. `FileFilterField` only
 *   mounts that button `if (value.isNotEmpty())` (`ReviewFilesTab.kt:376`),
 *   so `filter == ""` really reached the state — the clear did its job.
 * - The row container is `scrollable="true"` and its bounds shrink from
 *   `[1140,290][1366,720]` (430 px, all 15 rows) to `[1140,290][1366,350]`
 *   (60 px) once the IME is up, and a **third** row is still mounted below the
 *   fold. The list was never 2 rows long; the viewport is ~2 rows tall.
 * - What did not come back is the **top of the list**: after the clear the
 *   first mounted row is `.gradle`, which is index 3 of the 15. That is
 *   `items(key = …)` making `LazyListState` pin the filtered list's first item
 *   by key when the item set changes — deterministic, which is why the residue
 *   was identical in both rounds.
 *
 * So the row input is pinned here as the pure truth table it has to satisfy,
 * and the two state rules the fix added — clear returns to the top, refresh
 * asks again — are pinned as the pure functions that encode them.
 */
class ReviewFilterClearV97Test {

    private val root = "/repo"

    /** The 15 rows the device showed before anything was typed, in dump order. */
    private val rootNames = listOf(
        ".dsh-acl-recovery", ".git", ".github", ".gradle", ".idea", ".kotlin",
        "_upstream", "app", "build", "frontend", "gradle", "research",
        "scripts", "v36projB", "v36projC",
    )

    /** One lazily loaded directory: only the root is fetched in this scenario. */
    private val children = mapOf(
        root to rootNames.map { name -> node(name, directory = true) },
    )

    private val expanded = mutableSetOf<String>()

    private fun node(name: String, directory: Boolean) = FileNode(
        name = name,
        path = "$root/$name",
        absolute = "$root/$name",
        directory = directory,
        ignored = false,
    )

    private fun childrenOf(key: String): List<FileNode> = children[key].orEmpty()

    private fun isExpanded(key: String): Boolean = expanded.contains(key)

    /** The rendering input `ReviewFilesTab` hands to its `LazyColumn`. */
    private fun rowsFor(filter: String): List<ReviewFileTree.Row> =
        if (!ReviewFileFilter.isActive(filter)) {
            ReviewFileTree.flatten(::childrenOf, root, ::isExpanded)
        } else {
            ReviewFileFilter.filterRows(
                ReviewFileFilter.searchRows(
                    children = ::childrenOf,
                    root = root,
                    isExpanded = ::isExpanded,
                    hasLoadedChildren = { children.containsKey(it) },
                    query = filter,
                ),
                filter,
            )
        }

    // ---------------------------------------------------------------- truth table

    /**
     * No filter → record the full list → filter → clear. The rendering input
     * after the clear must be the recorded full list, equal row for row and in
     * the same order: not the filtered subset, not a same-size different list,
     * and not a shorter tree.
     */
    @Test
    fun `clear restores the recorded full list row for row`() {
        val full = rowsFor("")
        assertEquals("the unfiltered list is the 15 rows the device showed", 15, full.size)

        val filtered = rowsFor("gradle")
        assertEquals("the filter really narrows the list", 2, filtered.size)
        assertEquals(listOf(".gradle", "gradle"), filtered.map { it.node.name })
        assertTrue("the filtered list is a strict subset", filtered.size < full.size)

        val cleared = rowsFor("")

        assertEquals("clearing must restore the full list exactly", full.size, cleared.size)
        assertEquals("clearing must restore the same rows in the same order", full, cleared)
        assertEquals(
            "clearing must restore the first row, not stay on the match",
            ".dsh-acl-recovery",
            cleared.first().node.name,
        )
        assertTrue(
            "the filtered subset must not survive the clear",
            cleared.containsAll(filtered),
        )
    }

    /**
     * The residue the device showed was the *match* rows, so a clear that
     * silently keeps the search subset would still satisfy a size check on a
     * differently sized tree. Pin the two rows that were actually left behind.
     */
    @Test
    fun `clear does not leave the previous filter's rows on top of the list`() {
        rowsFor("gradle")
        val cleared = rowsFor("")

        val topTwo = cleared.take(2).map { it.node.name }
        assertEquals(
            "the remnant was '.gradle' + '.idea' at the top of the viewport",
            listOf(".dsh-acl-recovery", ".git"),
            topTwo,
        )
        assertEquals("every recorded row is back", rootNames, cleared.map { it.node.name })
    }

    /**
     * The clear has to work off the *unfiltered* source, so whatever the search
     * walked must not have moved the tree the user left. `searchRows` descends
     * on `hasLoadedChildren` rather than `isExpanded`; if that ever wrote
     * `expandedDirs`, the restored list would be a different (expanded) tree.
     */
    @Test
    fun `searching never widens the browse tree, so the clear restores what the user left`() {
        val before = rowsFor("")
        val expansionBefore = expanded.toSet()

        rowsFor("gradle")
        assertEquals("searching must not expand anything", expansionBefore, expanded.toSet())

        val after = rowsFor("")
        assertEquals("the restored tree is the one the user left", before, after)
        assertTrue("nothing got expanded by the search", after.all { it.level == 0 })
    }

    /** A blank / whitespace-only query is 「no filter」, not 「match everything visibly」. */
    @Test
    fun `a blank filter keeps the full list and is reported inactive`() {
        assertFalse(ReviewFileFilter.isActive(""))
        assertFalse(ReviewFileFilter.isActive("   "))
        assertEquals(rowsFor(""), rowsFor("   "))
        assertEquals(15, rowsFor("   ").size)
    }

    // ------------------------------------------------------------ refresh identity

    /**
     * 「Refresh must really re-read」 is the second half of U11. The tree's
     * `load` short-circuits on an already-listed directory, so a refresh that
     * did not evict first would hand the previous — filtered — listing back.
     */
    @Test
    fun `a refresh is a new read request and evicts the cached listing first`() {
        val mount = 0

        assertFalse("the mount-time read must not evict: nothing is cached yet", ReviewTreeRead.mustEvictBeforeReload(mount, mount))

        val first = ReviewTreeRead.readId(root, 1)
        val second = ReviewTreeRead.readId(root, 2)
        assertNotEquals("two refreshes cannot share a read identity", first, second)
        assertNotEquals("a refresh cannot reuse the mount read", ReviewTreeRead.readId(root, mount), first)
        assertEquals("the identity is stable for one (root, key) pair", first, ReviewTreeRead.readId(root, 1))

        assertTrue("a bumped key evicts before re-reading", ReviewTreeRead.mustEvictBeforeReload(1, mount))
        assertTrue("every later key evicts", ReviewTreeRead.mustEvictBeforeReload(2, 1))
        assertNotEquals("the identity is per root as well as per key", ReviewTreeRead.readId("/other", 1), first)
    }

    /**
     * Re-reading after an eviction answers from the source, not from the last
     * filter: the full listing comes back even though the search ran first.
     */
    @Test
    fun `a re-read after the refresh returns the full listing, not the filtered subset`() {
        val full = rowsFor("")
        rowsFor("gradle")
        assertEquals(2, rowsFor("gradle").size)

        // The evict + re-read the refresh now performs: same source, no filter.
        assertTrue(ReviewTreeRead.mustEvictBeforeReload(refreshKey = 1, mountRefreshKey = 0))
        assertEquals("the re-read restores the full listing", full, rowsFor(""))
    }
}
