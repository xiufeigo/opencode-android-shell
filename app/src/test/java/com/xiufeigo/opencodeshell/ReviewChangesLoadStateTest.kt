package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.review.ReviewChangeScope
import com.xiufeigo.opencodeshell.ui.session.review.ReviewScopes
import com.xiufeigo.opencodeshell.ui.session.review.ReviewVcsDiffResult
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.v2VcsDiffMode
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * v10 review finding #1: a `GET /api/vcs/diff?mode=working` that **never came
 * back** was rendered as 「No uncommitted changes yet」. The route builds a
 * whole-tree patch server-side, and on 2.0.11 it hangs for tens of seconds on a
 * large working tree — measured 2026-09-25: `/home/xiufeigo` (30 untracked
 * directories, `node_modules` among them) and this repo's own directory (1000+
 * uncommitted files) both produced **no response in 25 s**, while a small
 * directory answered `{"location":…,"data":[]}` instantly. So an empty list is
 * two different facts — 「this scope has no changes」 and 「we never found out」 —
 * and the panel has to tell them apart.
 *
 * Pinned here:
 * - the three-state decision ([ReviewScopes.changesBody]) and its order;
 * - the request's explicit deadline ([V2Api] handle with `callTimeout`, proven
 *   on the wire against [MockWebServer]) and that the deadline is what turns a
 *   hang into the failure the panel reports;
 * - the `location[directory]` the review chain sends (finding #2): exactly one,
 *   carrying the candidate ref — with the double-parameter trap that made
 *   reading through the transport's own handle wrong (root-fixed in
 *   `V2Api.url`: a caller's override replaces the instance default, so the
 *   handle can be used directly now, and the trap stays pinned here).
 */
class ReviewChangesLoadStateTest {

    // ------------------------------------------------ the four states

    @Test
    fun aFailedReadIsReportedAsAFailureNotAsAnEmptyScope() {
        assertEquals(
            ReviewScopes.ChangesBody.LOAD_FAILED,
            ReviewScopes.changesBody(
                GIT, noGit = false, loadFailed = true, loadPending = false, hasDiffs = false,
            ),
        )
        assertEquals(
            ReviewScopes.ChangesBody.EMPTY,
            ReviewScopes.changesBody(
                GIT, noGit = false, loadFailed = false, loadPending = false, hasDiffs = false,
            ),
        )
    }

    @Test
    fun aReadStillInFlightClaimsNothingEither() {
        // The 20 s window before the deadline: 「no uncommitted changes」 is as
        // wrong there as it is after a failure.
        assertEquals(
            ReviewScopes.ChangesBody.LOADING,
            ReviewScopes.changesBody(
                GIT, noGit = false, loadFailed = false, loadPending = true, hasDiffs = false,
            ),
        )
        // A pending read does not outrank a list the scope can already show.
        assertEquals(
            ReviewScopes.ChangesBody.LIST,
            ReviewScopes.changesBody(
                GIT, noGit = false, loadFailed = false, loadPending = true, hasDiffs = true,
            ),
        )
    }

    @Test
    fun aProvenAbsentRepositoryKeepsTheCtaOverTheFailureCopy() {
        // No repository at all: its VCS read has nothing to answer, so the
        // failure copy would be a dead end where the CTA is the way forward.
        assertEquals(
            ReviewScopes.ChangesBody.NO_GIT,
            ReviewScopes.changesBody(
                GIT, noGit = true, loadFailed = true, loadPending = false, hasDiffs = false,
            ),
        )
        assertEquals(
            ReviewScopes.ChangesBody.NO_GIT,
            ReviewScopes.changesBody(
                GIT, noGit = true, loadFailed = false, loadPending = true, hasDiffs = false,
            ),
        )
    }

    @Test
    fun aNonEmptyListIsNeverDisplaced() {
        assertEquals(
            ReviewScopes.ChangesBody.LIST,
            ReviewScopes.changesBody(
                GIT, noGit = false, loadFailed = true, loadPending = false, hasDiffs = true,
            ),
        )
        assertEquals(
            ReviewScopes.ChangesBody.LIST,
            ReviewScopes.changesBody(
                GIT, noGit = false, loadFailed = false, loadPending = false, hasDiffs = true,
            ),
        )
    }

    @Test
    fun theSnapshotScopeIgnoresAStaleVcsState() {
        // `loadFailed`/`loadPending` are the VCS scopes' own verdicts; the
        // last-turn scope reads the session snapshot, so a stale one must not
        // relabel it — neither as failed nor as loading.
        for (pending in listOf(true, false)) {
            for (failed in listOf(true, false)) {
                assertEquals(
                    ReviewScopes.ChangesBody.EMPTY,
                    ReviewScopes.changesBody(
                        LAST_TURN,
                        noGit = false,
                        loadFailed = failed,
                        loadPending = pending,
                        hasDiffs = false,
                    ),
                )
            }
        }
    }

    @Test
    fun anEmptyLoadedListIsNotTheFailure() {
        // The three shapes the host and the panel exchange, in one exhaustive
        // `when`: a fourth case would break this test's compilation, which is
        // the point — the Changes body's rule has to cover every case, and
        // 「answered empty」 must not be reachable as the failure.
        fun state(result: ReviewVcsDiffResult): String = when (result) {
            is ReviewVcsDiffResult.Loaded -> if (result.diffs.isEmpty()) "empty" else "files"
            ReviewVcsDiffResult.Failed -> "failed"
        }
        assertEquals("empty", state(ReviewVcsDiffResult.Loaded(emptyList())))
        assertEquals(
            "files",
            state(
                ReviewVcsDiffResult.Loaded(
                    listOf(
                        OpenCodeClient.FileDiff(
                            file = "a.txt",
                            patch = null,
                            additions = 1,
                            deletions = 0,
                            status = "modified",
                        ),
                    ),
                ),
            ),
        )
        assertEquals("failed", state(ReviewVcsDiffResult.Failed))
    }

    // ---------------------------------------------------- the deadline

    /**
     * The mechanism the panel relies on: a call that stalls after its headers
     * is aborted by `callTimeout` — the shared client's read timeout is 30 s and
     * does not bound the whole call, so the review read carries its own
     * deadline.
     */
    @Test
    fun theReviewReadIsAbortedByItsOwnCallDeadline() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"location":{"directory":"/w"},"data":[]}""")
                    // Headers now, bytes seconds later: exactly the shape of the
                    // real hang (the route answers nothing for minutes).
                    .setBodyDelay(5, TimeUnit.SECONDS),
            )
            val client = V2Api.defaultClient().newBuilder()
                .callTimeout(300, TimeUnit.MILLISECONDS)
                .build()
            val api = V2Api(
                baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
                username = null,
                password = null,
                http = client,
                directory = null,
            )
            val started = System.currentTimeMillis()
            try {
                api.vcsDiff(v2VcsDiffMode("git"), locationDirectory = "/w")
                fail("a stalled diff body must not be waited out")
            } catch (e: IOException) {
                // A deadline abort, never a server answer: callTimeout surfaces
                // as an interrupted read.
                assertTrue(
                    "the deadline, not the server, ended the call: $e",
                    e is java.io.InterruptedIOException,
                )
            }
            val elapsed = System.currentTimeMillis() - started
            assertTrue("the deadline must fire well before the 5 s body: ${elapsed}ms", elapsed < 3_000)
        }
    }

    /** The same handle without an override sends no location at all (unscoped read). */
    @Test
    fun anUnscopedReviewReadSendsNoLocation() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"location":{"directory":"/srv"},"data":[]}"""),
            )
            V2Api(
                baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
                username = null,
                password = null,
                http = V2Api.defaultClient(),
                directory = null,
            ).vcsDiff(v2VcsDiffMode("git"), locationDirectory = null)

            assertNull(server.takeRequest().requestUrl!!.queryParameter("location[directory]"))
        }
    }

    // --------------------------------------------- the location it carries

    /**
     * The value the review chain must send (finding #2): the candidate ref as
     * the **only** `location[directory]`.
     *
     * Measured live on 2.0.11 (2026-09-25) while two candidates were in flight:
     * `/home/xiufeigo` answered `{"provider":"git","branch":{"current":"master","default":"master"}}`
     * and the fresh-graph variant `/home/xiufeigo/.` answered the same way under
     * its own envelope — so the ref really is the thing the server resolves.
     */
    @Test
    fun theReviewReadCarriesTheCandidateAsTheOnlyLocation() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"location":{"directory":"/home/xiufeigo/."},"data":[]}"""),
            )
            val api = V2Api(
                baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
                username = null,
                password = null,
                http = V2Api.defaultClient(),
                // No instance default: the review handle is built this way
                // (`ChatViewModel.reviewVcsApi`).
                directory = null,
            )
            api.vcsDiff(v2VcsDiffMode("git"), locationDirectory = "/home/xiufeigo/.")

            val request = server.takeRequest()
            assertEquals("/api/vcs/diff", request.requestUrl!!.encodedPath)
            assertEquals("working", request.requestUrl!!.queryParameter("mode"))
            assertEquals("/home/xiufeigo/.", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals(2, request.requestUrl!!.querySize)
        }
    }

    /**
     * The trap this fix closed, pinned on the wire so nobody re-opens it: a
     * default beside the override put the key in the query **twice**, the
     * default first — and the server resolves the **first** occurrence, so the
     * override was dead.
     *
     * Measured live on 2.0.11 (2026-09-25):
     * `GET /api/vcs?location[directory]=/tmp&location[directory]=/home/xiufeigo`
     * → `{"location":{"directory":"/tmp"},…}` (no git), and the reversed pair
     * answered the `/home/xiufeigo` envelope — first wins, not last.
     *
     * So the override now **replaces** the instance default (`V2Api.url` appends
     * the default only when the caller sent no location of its own): a handle
     * built with the tracked session's directory, asked for another one, carries
     * exactly one `location[directory]` — the one the caller meant.
     */
    @Test
    fun theOverrideReplacesTheInstanceDefaultInsteadOfStandingBesideIt() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"location":{"directory":"/home/xiufeigo/."},"data":[]}"""),
            )
            val api = V2Api(
                baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
                username = null,
                password = null,
                http = V2Api.defaultClient(),
                // What the transport's own handle used to carry: the tracked
                // session's directory.
                directory = "/home/xiufeigo",
            )
            api.vcsDiff(v2VcsDiffMode("git"), locationDirectory = "/home/xiufeigo/.")

            val request = server.takeRequest()
            // `mode` plus **one** location: the override, never the default.
            assertEquals(2, request.requestUrl!!.querySize)
            assertEquals(
                listOf("/home/xiufeigo/."),
                request.requestUrl!!.queryParameterValues("location[directory]"),
            )
        }
    }

    // ------------------------------------------- the stall (v49)

    /**
     * v49 (`research/v49_verify/tD/v49_63_changes3.png`, `v49_68`): the Changes
     * page of a **non-Git** directory sat on 「加载中」 for as long as the tester
     * watched. The read it waits for has nothing to answer — the server gave
     * that location no VCS service — and only gives up at the 20 s client
     * deadline, so 「loading」 was the page's only answer for tens of seconds.
     *
     * The stall state is what makes the page converge: it still claims nothing
     * about the repository (a non-empty list and the proven-no-git CTA both keep
     * outranking it), but it stops the page from *only* saying 「加载中」.
     */
    @Test
    fun aReadThatOutlastsTheStallWindowSaysSo() {
        assertTrue(
            ReviewScopes.stalled(pending = true, pendingMs = ReviewScopes.REVIEW_LOAD_STALL_MS),
        )
        assertFalse(ReviewScopes.stalled(pending = true, pendingMs = 0L))
        // Nothing is out, so nothing is stalled — a read that already answered
        // must never leave the slow copy behind.
        assertFalse(
            ReviewScopes.stalled(
                pending = false,
                pendingMs = ReviewScopes.REVIEW_LOAD_STALL_MS * 10,
            ),
        )
        // Well inside the client deadline the read still gets a chance.
        assertTrue(ReviewScopes.REVIEW_LOAD_STALL_MS in 1 until 20_000L)
    }

    @Test
    fun theStallNeverOutranksAListOrTheCta() {
        assertEquals(
            ReviewScopes.ChangesBody.LIST,
            ReviewScopes.changesBody(
                GIT, noGit = false, loadFailed = false, loadPending = true, hasDiffs = true, loadStalled = true,
            ),
        )
        assertEquals(
            ReviewScopes.ChangesBody.NO_GIT,
            ReviewScopes.changesBody(
                GIT, noGit = true, loadFailed = false, loadPending = true, hasDiffs = false, loadStalled = true,
            ),
        )
        // …nor the snapshot scope (the stale VCS state must not relabel it).
        assertEquals(
            ReviewScopes.ChangesBody.EMPTY,
            ReviewScopes.changesBody(
                LAST_TURN,
                noGit = false,
                loadFailed = false,
                loadPending = true,
                hasDiffs = false,
                loadStalled = true,
            ),
        )
    }

    @Test
    fun theStallIsThePendingReadsOwnLaterState() {
        // Same read, only older: 「加载中」 before the window, the slow copy
        // after it, and a failure that has landed wins over both.
        assertEquals(
            ReviewScopes.ChangesBody.LOADING,
            ReviewScopes.changesBody(
                GIT, noGit = false, loadFailed = false, loadPending = true, hasDiffs = false, loadStalled = false,
            ),
        )
        assertEquals(
            ReviewScopes.ChangesBody.LOAD_SLOW,
            ReviewScopes.changesBody(
                GIT, noGit = false, loadFailed = false, loadPending = true, hasDiffs = false, loadStalled = true,
            ),
        )
        assertEquals(
            ReviewScopes.ChangesBody.LOAD_FAILED,
            ReviewScopes.changesBody(
                GIT, noGit = false, loadFailed = true, loadPending = false, hasDiffs = false, loadStalled = true,
            ),
        )
    }

    @Test
    fun theChangesTabWatchesThePendingReadItself() {
        val tab = reviewChangesTab()
        assertTrue(tab.contains("delay(ReviewScopes.REVIEW_LOAD_STALL_MS)"))
        assertTrue(
            tab.contains(
                "ReviewScopes.stalled(loadPending, ReviewScopes.REVIEW_LOAD_STALL_MS)",
            ),
        )
        assertTrue(tab.contains("loadStalled = loadStalled"))
        assertTrue(tab.contains("ReviewScopes.ChangesBody.LOAD_SLOW"))
        // The slow copy is the one hint that already names the one action left
        // (「刷新」), so the stall adds no new string to keep translated.
        assertTrue(tab.contains("R.string.oc_review_changes_failed_hint)"))
    }

    @Test
    fun theReviewPullFollowsTheDirectoryNotJustTheSession() {
        // v49: the stale `main` chip. The pull resets `reviewBranch`, so it has to
        // re-run when the session is re-pointed at another directory …
        val screen = sessionScreen()
        assertTrue(
            screen.contains(
                "LaunchedEffect(activeSession.id, reviewOpen, mobileReview, summaryOpen, " +
                    "vcsRevision, currentDirectory)",
            ),
        )
        // … and the directory itself is collected above that effect, not below.
        val collected = screen.indexOf("val currentDirectory by chatViewModel.currentDirectory")
        val pull = screen.indexOf("LaunchedEffect(activeSession.id, reviewOpen, mobileReview")
        assertTrue(collected in 0 until pull)
        // A root change also re-asks the probes and the scope read (the reprobe
        // key is what both of them watch).
        assertTrue(screen.contains("reviewRootSeen = reviewRootPath"))
        assertTrue(screen.contains("reviewReprobeKey++"))
    }

    // ------------------------------------------------------- source pins

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText().replace("\r\n", "\n")
    }

    private fun viewModel() = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

    private fun sessionScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private fun reviewChangesTab() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewChangesTab.kt")

    @Test
    fun theHostDiffReadAnswerIsAFailureOrALoadedListNeverABareNull() {
        val diffRead = viewModel()
            .substringAfter("fun reviewVcsDiff(")
            .substringBefore("private fun reviewVcsApi(")
        // No answer at all is the failure case …
        assertTrue(diffRead.contains("ReviewVcsDiffResult.Failed"))
        // … and an answered list is Loaded, empty or not.
        assertTrue(diffRead.contains("ReviewVcsDiffResult::Loaded"))
        assertFalse("a failed read must not be an empty list", diffRead.contains("?: emptyList()"))
    }

    @Test
    fun theHostDiffReadIsBoundedAndScopedByExactlyOneLocation() {
        val vm = viewModel()
        // The read's own wall-clock deadline (the shared client only bounds one
        // socket read).
        assertTrue(vm.contains("callTimeout(REVIEW_VCS_TIMEOUT_MS, TimeUnit.MILLISECONDS)"))
        assertTrue(vm.contains("REVIEW_VCS_TIMEOUT_MS = 20_000L"))
        val api = vm.substringAfter("private fun reviewVcsApi(").substringBefore("\n    }\n")
        // No instance default (the candidate is the override), and the timed
        // client on the one read that can hang.
        assertTrue(api.contains("transportApi(transport, location = null, http = reviewVcsHttp)"))
        // A non-v2 transport keeps its own call — the generation's contract.
        assertTrue(api.contains("if (transport !is V2Transport) return null"))
    }

    @Test
    fun theScreenKeepsOneDiffRequestSeamAndOneVerdict() {
        val screen = sessionScreen()
        // One seam: the panel's switch/retry, the mobile page and the failure
        // retry all reach the ViewModel here.
        assertEquals(1, Regex("chatViewModel\\.reviewVcsDiff").findAll(screen).count())
        assertTrue(screen.contains("val requestVcsDiff: (ReviewChangeScope) -> Unit = { reviewScope ->"))
        // One value carries the three states: out (null), Loaded, Failed.
        assertTrue(screen.contains("mutableStateOf<ReviewVcsDiffResult?>(null)"))
        assertTrue(
            screen.contains(
                "val vcsDiffs = (vcsDiffResult as? ReviewVcsDiffResult.Loaded)?.diffs ?: emptyList()",
            ),
        )
        assertTrue(screen.contains("val vcsDiffFailed = vcsDiffResult is ReviewVcsDiffResult.Failed"))
        assertTrue(screen.contains("val vcsDiffPending = vcsDiffResult == null"))
        // A new attempt clears the old verdict before it leaves.
        val seam = screen.substringAfter("val requestVcsDiff: (ReviewChangeScope) -> Unit = {")
            .substringBefore("val appendReviewText")
        assertTrue(seam.indexOf("vcsDiffResult = null") < seam.indexOf("chatViewModel.reviewVcsDiff"))
        // Wired into both surfaces: the desktop panel's callback and the mobile
        // page's own state + retry.
        assertTrue(screen.contains("onRequestVcsDiff = requestVcsDiff"))
        assertTrue(screen.contains("onRetryDiff = { requestVcsDiff(mobileReviewScope) }"))
        assertTrue(screen.contains("loadFailed = vcsDiffFailed"))
        assertTrue(screen.contains("loadPending = vcsDiffPending"))
        assertTrue(screen.contains("vcsDiffPending = vcsDiffPending"))
    }

    @Test
    fun thePanelPlumbsTheFailureIntoItsChangesTab() {
        val panel = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewPanel.kt")
        assertTrue(panel.contains("vcsDiffFailed: Boolean = false"))
        assertTrue(panel.contains("vcsDiffPending: Boolean = false"))
        assertTrue(panel.contains("loadFailed = vcsDiffFailed"))
        assertTrue(panel.contains("loadPending = vcsDiffPending"))
        // The retry is the same request the scope switch makes.
        assertTrue(panel.contains("onRetryDiff = { requestVcsDiff.value?.invoke(scope) }"))
    }

    @Test
    fun theChangesTabRendersTheFailureStateAndItsRetry() {
        val tab = reviewChangesTab()
        assertTrue(tab.contains("ReviewScopes.changesBody("))
        assertTrue(tab.contains("ReviewScopes.ChangesBody.LOAD_FAILED"))
        assertTrue(tab.contains("LoadFailedEmpty("))
        // …and never lets a failed read hide behind the session fallback list.
        assertTrue(tab.contains("LoadFailedBanner("))
        assertTrue(tab.contains("R.string.oc_review_changes_failed)"))
        assertTrue(tab.contains("R.string.oc_review_changes_failed_hint)"))
        assertTrue(tab.contains("onRetryDiff: (() -> Unit)? = null"))
        // The in-flight state waits on the copy the app already ships.
        assertTrue(tab.contains("ReviewScopes.ChangesBody.LOADING"))
        assertTrue(tab.contains("R.string.oc_common_loading"))
    }

    @Test
    fun theFailureCopyIsTranslatedEverywhere() {
        val key = "oc_review_changes_failed"
        val hint = "oc_review_changes_failed_hint"
        val en = source("src/main/res/values/strings.xml")
        val zh = source("src/main/res/values-zh-rCN/strings.xml")
        val tw = source("src/main/res/values-zh-rTW/strings.xml")
        for (text in listOf(en, zh, tw)) {
            assertTrue(text.contains("""<string name="$key">"""))
            assertTrue(text.contains("""<string name="$hint">"""))
        }
        assertFalse(zh.contains("Couldn\\'t load changes"))
        assertFalse(tw.contains("Couldn\\'t load changes"))
    }

    private companion object {
        val GIT = ReviewChangeScope.GIT
        val LAST_TURN = ReviewChangeScope.LAST_TURN
    }
}
