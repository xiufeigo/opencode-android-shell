package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.review.ReviewChangeScope
import com.xiufeigo.opencodeshell.ui.session.review.ReviewProjectVcs
import com.xiufeigo.opencodeshell.ui.session.review.ReviewScopes
import com.xiufeigo.opencodeshell.ui.session.review.ReviewVcsReprobe
import com.xiufeigo.opencodeshell.v2.V2Api
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The v8-B review fix, traceable from `research/v8_verify/err_review`: the
 * Changes panel showed 「Create a Git repository」 for a directory that had been
 * a Git repository for half an hour, because both review data sources were dead
 * for a location whose cached services predate `git init`:
 *
 * - `GET /api/vcs` / `/api/vcs/diff` — the builtin git plugin only registers
 *   when `location.vcs` was Git when the location graph was built;
 * - `GET /api/session/:id/diff` — a snapshot diff, and `Snapshot.capture()`
 *   bails on the same flag.
 *
 * These tests pin the three client-side rules that came out of that:
 * the re-probe candidates ([ReviewVcsReprobe]), the project-table verdict that
 * replaces the timer heuristic ([ReviewProjectVcs]), and the scope rules the
 * panel renders with (default scope, snapshot fallback, CTA gate).
 */
class ReviewVcsReprobeTest {

    // ---------------------------------------------------- refs + answers

    @Test
    fun reProbeAddsAFreshGraphVariantOfTheSameDirectory() {
        // Measured: `/home/xiufeigo` answers {"branch":{}} while
        // `/home/xiufeigo/.` answers {"provider":"git","branch":{...master}}.
        assertEquals(
            listOf("/home/xiufeigo", "/home/xiufeigo/."),
            ReviewVcsReprobe.refs("/home/xiufeigo"),
        )
        // A directory that already ends in a separator must not produce "//.".
        assertEquals(
            listOf("/home/xiufeigo/", "/home/xiufeigo/."),
            ReviewVcsReprobe.refs("/home/xiufeigo/"),
        )
        assertEquals(listOf("/", "/."), ReviewVcsReprobe.refs("/"))
        // Windows-hosted servers keep the backslash separator.
        assertEquals(
            listOf("""C:\work\repo""", """C:\work\repo\."""),
            ReviewVcsReprobe.refs("""C:\work\repo"""),
        )
    }

    @Test
    fun unknownDirectoryHasNoVariantToTry() {
        assertEquals(emptyList<String>(), ReviewVcsReprobe.refs(null))
        assertEquals(emptyList<String>(), ReviewVcsReprobe.refs(""))
        assertEquals(emptyList<String>(), ReviewVcsReprobe.refs("   "))
    }

    @Test
    fun anAnswerCountsWhenItCarriesABranchOrAFile() {
        assertTrue(ReviewVcsReprobe.answered("master", 0))
        assertTrue(ReviewVcsReprobe.answered(null, 3))
        // The stale-location answer: no provider → no branch, no files.
        assertFalse(ReviewVcsReprobe.answered(null, 0))
        assertFalse(ReviewVcsReprobe.answered("", 0))
    }

    // --------------------------------------------------- project verdict

    /** `GET /api/project` rows for `/home/xiufeigo` as measured 2026-09-25. */
    private fun liveProjects(): List<V2Api.V2Project> = listOf(
        project("264884bb26d35255a58501108794dbfde2777686", "/home/xiufeigo", vcs = "git"),
        project("0a95337c0d21b14f8a06c6fca757ff194a73a5d4", "/home/xiufeigo", vcs = null),
        project("global", "/", vcs = null),
    )

    @Test
    fun aRepositoryIsProvenThroughTheProjectTableNotTheVcsRead() {
        // The `git init` that made the directory a repository created a second
        // row for the same canonical; a row with a VCS wins.
        assertEquals(true, ReviewProjectVcs.verdict(liveProjects(), "/home/xiufeigo"))
        // Subdirectories resolve to the same project (the server walks up).
        assertEquals(true, ReviewProjectVcs.verdict(liveProjects(), "/home/xiufeigo/v8diff"))
    }

    @Test
    fun aProjectWithoutARepositoryIsTheOnlyFalseVerdict() {
        val markerless = listOf(
            project("0a95337c0d21b14f8a06c6fca757ff194a73a5d4", "/home/xiufeigo", vcs = null),
            project("global", "/", vcs = null),
        )
        assertEquals(false, ReviewProjectVcs.verdict(markerless, "/home/xiufeigo"))
        assertEquals(false, ReviewProjectVcs.verdict(markerless, "/home/xiufeigo/code"))
    }

    @Test
    fun unknownDirectoriesStayUnknown() {
        val rows = liveProjects()
        // No row covers it.
        assertNull(ReviewProjectVcs.verdict(rows, "/tmp/elsewhere"))
        // The root project covers everything and says nothing about VCS.
        assertNull(ReviewProjectVcs.verdict(listOf(project("global", "/", vcs = null)), "/tmp/elsewhere"))
        assertNull(ReviewProjectVcs.verdict(rows, null))
        assertNull(ReviewProjectVcs.verdict(emptyList(), "/home/xiufeigo"))
        // A prefix is not an ancestor: `/home/xiufeigo2` ≠ `/home/xiufeigo`.
        assertNull(ReviewProjectVcs.verdict(rows, "/home/xiufeigo2"))
    }

    @Test
    fun theMostSpecificProjectWins() {
        val rows = listOf(
            project("outer", "/work", vcs = "git"),
            project("inner", "/work/plain", vcs = null),
        )
        assertEquals(false, ReviewProjectVcs.verdict(rows, "/work/plain/sub"))
        assertEquals(true, ReviewProjectVcs.verdict(rows, "/work/other"))
    }

    // ------------------------------------------------------- scope rules

    @Test
    fun panelOpensOnTheGitScopeLikeUpstream() {
        val withRepo = ReviewScopes.available(hasVcs = true, currentBranch = "main", defaultBranch = "main")
        assertEquals(ReviewChangeScope.GIT, ReviewScopes.defaultScope(withRepo))
        val feature = ReviewScopes.available(hasVcs = true, currentBranch = "feat", defaultBranch = "main")
        assertEquals(ReviewChangeScope.GIT, ReviewScopes.defaultScope(feature))
        // No repository → the only offered scope carries the session snapshot.
        val noRepo = ReviewScopes.available(hasVcs = false, currentBranch = null, defaultBranch = null)
        assertEquals(ReviewChangeScope.LAST_TURN, ReviewScopes.defaultScope(noRepo))
        assertEquals(ReviewChangeScope.LAST_TURN, ReviewScopes.defaultScope(emptyList()))
    }

    @Test
    fun anEmptyVcsScopeFallsBackToTheSessionSnapshot() {
        val session = listOf(diff("a.txt", "modified"))
        val vcs = listOf(diff("src/Other.kt", "added"))
        // The scope's own source wins when it has anything …
        assertEquals(vcs, ReviewScopes.diffsFor(ReviewChangeScope.GIT, session, vcs))
        assertEquals(session, ReviewScopes.diffsFor(ReviewChangeScope.LAST_TURN, session, vcs))
        // … and the session's files stay visible when the VCS read came back
        // empty (a stale location, or a session whose git plugin is alive).
        assertEquals(session, ReviewScopes.diffsFor(ReviewChangeScope.GIT, session, emptyList()))
        assertEquals(session, ReviewScopes.diffsFor(ReviewChangeScope.BRANCH, session, emptyList()))
        assertTrue(ReviewScopes.diffsFor(ReviewChangeScope.GIT, emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun theCreateRepositoryCtaNeedsAProvenAbsentRepository() {
        // The v8-B state: the host knows nothing, the `/vcs` read answered
        // nothing, the list is empty — no proof, so no CTA.
        assertFalse(ReviewScopes.noGit(hasVcs = null, projectVcs = null, hasChanges = false))
        assertFalse(ReviewScopes.noGit(hasVcs = true, projectVcs = null, hasChanges = false))
        assertFalse(ReviewScopes.noGit(hasVcs = null, projectVcs = true, hasChanges = false))
        // A project known to have no repository → the CTA.
        assertTrue(ReviewScopes.noGit(hasVcs = false, projectVcs = null, hasChanges = false))
        assertTrue(ReviewScopes.noGit(hasVcs = null, projectVcs = false, hasChanges = false))
        // Diffs never get displaced by an empty state.
        assertFalse(ReviewScopes.noGit(hasVcs = false, projectVcs = false, hasChanges = true))
        assertFalse(ReviewScopes.noGit(hasVcs = true, projectVcs = true, hasChanges = true))
    }

    // -------------------------------------------------------- source pins

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText().replace("\r\n", "\n")
    }

    @Test
    fun theHostFetchChainWalksTheReProbeCandidates() {
        val viewModel = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        val info = viewModel.substringAfter("fun reviewVcsInfo(").substringBefore("fun reviewVcsDiff(")
        val diffRead = viewModel.substringAfter("fun reviewVcsDiff(").substringBefore("private fun reviewVcsCandidates(")
        listOf(info, diffRead).forEach { call ->
            assertTrue(call.contains("reviewVcsCandidates()"))
            assertTrue(call.contains("ReviewVcsReprobe.answered(") || call.contains("list.isNotEmpty()"))
        }
        // v97 fix: the host no longer *builds* the ref list. `reviewVcsCandidates`
        // hands the rule the three inputs it needs and delegates, so the rule has
        // exactly one owner (`ReviewVcsProbePlan`) and the two halves of it —
        // which refs are still on the table, and may a failure retire them —
        // cannot drift apart inside this 5400-line file.
        val candidates = viewModel.substringAfter("private fun reviewVcsCandidates(")
            .substringBefore("private fun rememberReviewVcsRef(")
        assertTrue(candidates.contains("ReviewVcsProbePlan.candidates("))
        // The remembered-ref map, keyed by the tracked session's own directory …
        assertTrue(candidates.contains("remembered = directory?.let { reviewVcsRefs[it] }"))
        // … and the permanent-probe latch, which may only narrow the list to the
        // canonical ref (a transient one must not — `ReviewVcsProbePlanTest`).
        assertTrue(candidates.contains("probed = directory != null && directory in reviewVcsProbed"))
        // The delegation is the pin: the host must not re-inline the ref list
        // behind the plan's back, which is the drift that broke this test.
        assertFalse(candidates.contains("ReviewVcsReprobe.refs("))
        // The fresh-graph source moved with the rule, so pin it where it now
        // lives — and the guards, in precedence order. Reordering them would let
        // a probed latch outrank a ref that already answered, and re-filtering
        // the returned list would break the per-candidate walk.
        val plan = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewVcsProbePlan.kt")
        val planCandidates = plan.substringAfter("fun candidates(")
            .substringBefore("fun shouldMarkProbed(")
        val unscoped = "if (dir.isEmpty()) return listOf(null)"
        val remembered = "remembered?.takeIf { it.isNotBlank() }?.let { return listOf(it) }"
        val retired = "if (probed) return listOf(dir)"
        val freshGraph = "return ReviewVcsReprobe.refs(dir)"
        listOf(unscoped, remembered, retired, freshGraph).forEach { guard ->
            assertTrue("missing candidate guard: $guard", planCandidates.contains(guard))
        }
        val precedence = listOf(unscoped, remembered, retired, freshGraph).map { planCandidates.indexOf(it) }
        assertEquals(precedence.sorted(), precedence)
    }

    @Test
    fun thePanelGatesTheCtaOnTheProbedVerdict() {
        val panel = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewPanel.kt")
        // Re-read whenever the panel's 「刷新」 settles (`reprobeKey`): the evict
        // rebuilt the directory's services, and this read is the one that sees a
        // `git init` the cached graph missed.
        assertTrue(panel.contains("rememberReviewProjectVcs(rootPath, reprobeKey)"))
        assertTrue(panel.contains("ReviewScopes.noGit(hasVcs, projectVcs, visibleDiffs.isNotEmpty())"))
        // Upstream opens on the git scope and rewrites it when unavailable.
        assertTrue(panel.contains("mutableStateOf(ReviewChangeScope.GIT.name)"))
        assertTrue(panel.contains("scope !in available"))
        // The timer heuristic is gone.
        assertFalse(panel.contains("noGitSettled"))
        assertFalse(panel.contains("NO_GIT_SETTLE_MS"))
    }

    // -------------------------------------------------------------- helpers

    private fun diff(file: String, status: String?) = OpenCodeClient.FileDiff(
        file = file,
        patch = null,
        additions = 1,
        deletions = 0,
        status = status,
    )

    private fun project(id: String, canonical: String, vcs: String?): V2Api.V2Project {
        val json = JSONObject().put("id", id).put("canonical", canonical)
        if (vcs != null) json.put("vcs", vcs)
        return V2Api.V2Project.fromJson(json)
    }
}
