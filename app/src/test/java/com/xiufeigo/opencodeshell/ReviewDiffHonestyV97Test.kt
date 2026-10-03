package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.review.ReviewChangeScope
import com.xiufeigo.opencodeshell.ui.session.review.ReviewChangeScope.BRANCH
import com.xiufeigo.opencodeshell.ui.session.review.ReviewChangeScope.GIT
import com.xiufeigo.opencodeshell.ui.session.review.ReviewChangeScope.LAST_TURN
import com.xiufeigo.opencodeshell.ui.session.review.ReviewScopes
import com.xiufeigo.opencodeshell.ui.session.review.ReviewScopes.ChangesBody
import com.xiufeigo.opencodeshell.ui.session.review.ReviewVcsDiffResult
import com.xiufeigo.opencodeshell.ui.session.review.ReviewVcsProbePlan
import com.xiufeigo.opencodeshell.ui.session.review.ReviewVcsProbePlan.Attempt
import com.xiufeigo.opencodeshell.ui.session.review.ReviewVcsProbePlan.Failure
import com.xiufeigo.opencodeshell.ui.session.review.ReviewVcsProbePlan.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v97 D11 (`research/v97_verify/5556_v98/REPORT_SWEEPC2.md` §3): on a narrow
 * window the Changes tab timed out, the user pressed 「刷新」, the timeout banner
 * went away — and the page then claimed **「No uncommitted changes yet」** for a
 * working tree whose host-side `git diff --shortstat HEAD` read
 * `207 files changed, 58099 insertions(+), 9251 deletions(-)`.
 *
 * That verdict is the dangerous kind of wrong: the panel *recovered*, so the user
 * believed it, and 「recovered」 is exactly the signal that says 「your working
 * tree is clean」. A standing error is at least legible.
 *
 * The measured shape of the bug (same report, 60.2 s backend read never
 * returning, 20 s client deadline firing first):
 *
 * | candidate | answer |
 * | --- | --- |
 * | `<dir>` (stale location graph, no VCS provider) | `[]` — in **milliseconds** |
 * | `<dir>/.` (fresh graph, the real tree) | **timeout** at the 20 s deadline |
 *
 * A location with no VCS provider answers `[]` no matter what the tree holds
 * (`ReviewVcsReprobe`'s measurement), so the empty answer was never evidence of
 * a clean tree — it was evidence of a ref that knew nothing. The old aggregation
 * kept the *last* list any ref returned and threw the sibling timeout away, so
 * the empty won by default. T+23 s after Refresh matches: one instant `[]` plus
 * one 20 s deadline.
 *
 * Pinned here:
 * 1. the pure three-state truth table ([ReviewVcsProbePlan.verdict]);
 * 2. that 真空 (read empty) and 读失败 (read failed) can never collapse into the
 *    same verdict, all the way to the body the Changes tab renders;
 * 3. the host really sends every candidate's outcome into that rule, and the
 *    clean copy is only reachable behind a successful read.
 */
class ReviewDiffHonestyV97Test {

    // ------------------------------------------- ① the pure truth table

    /**
     * The whole rule in one table. `EMPTY` — the licence to say 「no
     * uncommitted changes」 — is issued **only** when every candidate answered
     * and none of them ever had a file to report.
     */
    @Test
    fun theVerdictTableIsExhaustiveAndNeverGuessesClean() {
        val answered0 = Attempt.Answered(0)
        val answered3 = Attempt.Answered(3)
        val transient = Attempt.Failed(Failure.TRANSIENT)
        val permanent = Attempt.Failed(Failure.PERMANENT)

        // Nothing was even tried — the degenerate walk is a failure, never a
        // clean tree.
        assertEquals(Verdict.FAILED, ReviewVcsProbePlan.verdict(emptyList()))

        // A real list wins outright, whatever else the walk saw. This is the
        // A2 path (N6-a): a retry that finally lands must still restore the data.
        assertEquals(Verdict.FILES, ReviewVcsProbePlan.verdict(listOf(answered3)))
        assertEquals(Verdict.FILES, ReviewVcsProbePlan.verdict(listOf(answered3, answered0)))
        assertEquals(Verdict.FILES, ReviewVcsProbePlan.verdict(listOf(answered0, answered3)))
        assertEquals(
            "a ref that answered with files outranks one that timed out",
            Verdict.FILES,
            ReviewVcsProbePlan.verdict(listOf(answered3, transient)),
        )
        assertEquals(
            "…and one that was permanently refused, too",
            Verdict.FILES,
            ReviewVcsProbePlan.verdict(listOf(answered0, permanent, answered3)),
        )

        // Nothing answered: the ordinary timeout. Still a failure.
        assertEquals(Verdict.FAILED, ReviewVcsProbePlan.verdict(listOf(transient)))
        assertEquals(Verdict.FAILED, ReviewVcsProbePlan.verdict(listOf(transient, permanent)))

        // ★ D11: answered empty **and** a sibling failed. The empty came from a
        // ref that may have had nothing to tell; it cannot claim the tree is
        // clean while the ref that could have disproved it never came back.
        assertEquals(
            "an empty answer beside a timeout is not a clean tree",
            Verdict.FAILED,
            ReviewVcsProbePlan.verdict(listOf(answered0, transient)),
        )
        assertEquals(
            "…same when the failed sibling is the fresh-graph re-probe (order A)",
            Verdict.FAILED,
            ReviewVcsProbePlan.verdict(listOf(answered0, transient)),
        )
        assertEquals(
            "…and when it comes first (order B — the walk must be order-free)",
            Verdict.FAILED,
            ReviewVcsProbePlan.verdict(listOf(transient, answered0)),
        )
        assertEquals(
            "a permanent refusal beside an empty answer is still not proof of clean",
            Verdict.FAILED,
            ReviewVcsProbePlan.verdict(listOf(answered0, permanent)),
        )
        assertEquals(
            "…even with two empties around it",
            Verdict.FAILED,
            ReviewVcsProbePlan.verdict(listOf(answered0, answered0, transient)),
        )

        // The only EMPTY: everyone answered, everyone said 「nothing changed」.
        // A small, genuinely clean repository must keep its honest copy.
        assertEquals(Verdict.EMPTY, ReviewVcsProbePlan.verdict(listOf(answered0)))
        assertEquals(
            "every candidate answering empty is the whole truth",
            Verdict.EMPTY,
            ReviewVcsProbePlan.verdict(listOf(answered0, answered0)),
        )
    }

    /**
     * The three states are three states: 真空 and 读失败 must not be
     * interchangeable anywhere, or the panel is back to guessing. Enumerated
     * over the full input alphabet rather than sampled.
     */
    @Test
    fun anEmptyReadAndAFailedReadAreNeverTheSameVerdict() {
        val alphabet = listOf(
            Attempt.Answered(0),
            Attempt.Answered(2),
            Attempt.Failed(Failure.TRANSIENT),
            Attempt.Failed(Failure.PERMANENT),
        )
        // The failing walks: a lone failure, an empty beside a failure, empties
        // beside a failure — and the success walks: files, or all-empty.
        assertNotEquals(
            "真空 and 读失败 collapsed into one verdict",
            Verdict.EMPTY,
            Verdict.FAILED,
        )
        // Sanity on the enumeration itself: no input reaches a verdict outside
        // the three, and the mixed pairs are all failures.
        for (a in alphabet) {
            for (b in alphabet) {
                val v = ReviewVcsProbePlan.verdict(listOf(a, b))
                assertTrue("unexpected verdict $v for $a + $b", v in Verdict.values().toList())
                if (a is Attempt.Answered && a.files == 0 && a === b) continue
                if (b is Attempt.Failed && a is Attempt.Answered && a.files == 0) {
                    assertEquals("empty + failed must fail: $a + $b", Verdict.FAILED, v)
                }
                if (a is Attempt.Failed && b is Attempt.Answered && b.files == 0) {
                    assertEquals("failed + empty must fail: $a + $b", Verdict.FAILED, v)
                }
            }
        }
    }

    // ------------------------------- ② verdict → host state → rendered body

    /**
     * The rule is only worth anything if it survives the trip to the screen:
     * the host's three-state result must still separate 真空 from 读失败, and
     * only a genuinely-read-empty scope may reach the clean copy.
     */
    @Test
    fun onlyASuccessfullyReadEmptyScopeRendersTheCleanCopy() {
        // A walk that failed → host says Failed → the body says failed. The
        // exact pair D11 got wrong.
        val failedResult = hostResult(
            ReviewVcsProbePlan.verdict(listOf(Attempt.Answered(0), Attempt.Failed(Failure.TRANSIENT))),
        )
        assertEquals(ReviewVcsDiffResult.Failed, failedResult)
        assertEquals(
            "a failed read must not borrow the clean copy",
            ChangesBody.LOAD_FAILED,
            body(failedResult),
        )

        // A walk where every ref answered empty → really clean → the copy is
        // earned. This is the small-clean-repo case, unchanged.
        val emptyResult = hostResult(
            ReviewVcsProbePlan.verdict(listOf(Attempt.Answered(0), Attempt.Answered(0))),
        )
        assertEquals(ReviewVcsDiffResult.Loaded(emptyList()), emptyResult)
        assertEquals(ChangesBody.EMPTY, body(emptyResult))

        // …and the two are not the same thing anywhere downstream.
        assertNotEquals("真空 and 读失败 render the same body", body(emptyResult), body(failedResult))
        assertNotEquals(failedResult, emptyResult)
    }

    /** A read still in flight is not a clean tree either (unchanged, kept in the table). */
    @Test
    fun anOutStandingReadNeverRendersTheCleanCopy() {
        assertEquals(
            ChangesBody.LOADING,
            ReviewScopes.changesBody(
                GIT, noGit = false, loadFailed = false, loadPending = true, hasDiffs = false,
            ),
        )
        assertEquals(
            ChangesBody.LOAD_SLOW,
            ReviewScopes.changesBody(
                GIT,
                noGit = false,
                loadFailed = false,
                loadPending = true,
                hasDiffs = false,
                loadStalled = true,
            ),
        )
    }

    /**
     * A **truncated** answer must not be dressed as the scope's own complete
     * truth either (v97 N6-①): the `+N -N` summary is suppressed whenever the
     * scope fell back, so a short list is never presented as the whole one.
     */
    @Test
    fun aTruncatedOrFallbackReadNeverClaimsTheScopesOwnTotals() {
        for (scope in listOf(GIT, BRANCH)) {
            assertTrue(
                "a failed read must not present totals as $scope's own",
                ReviewScopes.changesSummaryPartial(scope, loadFailed = true),
            )
        }
        // A successful read is complete for its own scope and keeps its numbers.
        assertFalse(ReviewScopes.changesSummaryPartial(GIT, loadFailed = false))
        // The snapshot scope reads the session diff by definition — the rule is
        // about the *fallback*, not the source.
        assertFalse(
            ReviewScopes.changesSummaryPartial(LAST_TURN, loadFailed = true),
        )
    }

    /** A retry that lands must still restore the real list (v97 N6-a / A2). */
    @Test
    fun aRetryThatLandsRestoresTheRealData() {
        val recovered = hostResult(
            ReviewVcsProbePlan.verdict(listOf(Attempt.Answered(0), Attempt.Failed(Failure.TRANSIENT), Attempt.Answered(207))),
        )
        val diffs = (recovered as ReviewVcsDiffResult.Loaded).diffs
        assertEquals(207, diffs.size)
        assertEquals(ChangesBody.LIST, body(recovered))
    }

    // ------------------------------------------------------------ source pins

    /**
     * The host must feed **every** candidate's outcome into the one rule — the
     * bug was an outcome being dropped on the floor, so the pin is that the
     * aggregation is the rule's and not the loop's.
     */
    @Test
    fun theHostAggregatesThroughThePureRuleNotThroughTheLastAnswer() {
        val diffRead = viewModel()
            .substringAfter("fun reviewVcsDiff(")
            .substringBefore("private fun reviewVcsApi(")
        // Every attempt is recorded, answered and failed alike.
        assertTrue(diffRead.contains("attempts += ReviewVcsProbePlan.Attempt.Answered(list.size)"))
        assertTrue(diffRead.contains("attempts += ReviewVcsProbePlan.Attempt.Failed(kind)"))
        // …and the verdict is that rule's, not the loop's last list.
        assertTrue(diffRead.contains("ReviewVcsProbePlan.verdict(attempts)"))
        for (verdict in Verdict.values()) {
            assertTrue("the $verdict verdict is not handled", diffRead.contains("Verdict.$verdict"))
        }
        // The D11 shape is unreachable by construction: inside the loop no
        // result is built at all, so no ref's list can be promoted to *the*
        // answer before the walk has finished.
        val loop = diffRead.substringAfter("for (ref in candidates)").substringBefore("val settled =")
        assertFalse(
            "a candidate's list must not be promoted inside the loop",
            loop.contains("ReviewVcsDiffResult"),
        )
    }

    /**
     * The clean copy's precondition, pinned where it is enforced: the body's
     * `EMPTY` arm must sit **after** the failure arm, and a failure must reach
     * it. If a future change moves `loadFailed` below the `else -> EMPTY`, this
     * fails.
     */
    @Test
    fun theCleanCopysPreconditionIncludesASuccessfulRead() {
        val models = reviewModels()
        val rule = models
            .substringAfter("fun changesBody(")
            .substringBefore("\n    }\n")
        val failedArm = rule.indexOf("loadFailed -> ChangesBody.LOAD_FAILED")
        val elseArm = rule.indexOf("else -> ChangesBody.EMPTY")
        assertTrue("the failure arm disappeared", failedArm > 0)
        assertTrue("the clean arm disappeared", elseArm > 0)
        assertTrue(
            "LOAD_FAILED must be evaluated before the clean arm can be reached",
            failedArm < elseArm,
        )
        // And the snapshot scope is resolved before both, so a stale VCS state
        // never relabels it.
        assertTrue(
            rule.indexOf("scope == ReviewChangeScope.LAST_TURN") < failedArm,
        )
    }

    /** No new user-visible string was needed: the failure body reuses the old one. */
    @Test
    fun theFailureBodyReusesTheExistingString() {
        val tab = reviewChangesTab()
        assertTrue(
            "the failure body must keep the existing failure copy",
            tab.contains("ChangesBody.LOAD_FAILED -> LoadFailedEmpty("),
        )
        assertTrue(
            "the slow read keeps the same hint that names 「刷新」",
            tab.contains("R.string.oc_review_changes_failed_hint)"),
        )
        // The clean copy is a distinct, pre-existing string — not a new one.
        val strings = source("src/main/res/values/strings.xml")
        assertTrue(strings.contains("<string name=\"oc_review_changes_empty_git\">"))
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The exact host mapping, mirrored so the pin reads as the production
     * three-way `when` in [ChatViewModel.reviewVcsDiff].
     *
     * D11-b added [Verdict.IN_FLIGHT], whose host arm is `null` — a walk that has
     * not settled **publishes nothing** rather than being folded into one of the
     * three settled states. `null` is what the panel reads as `loadPending`
     * (`SessionScreen.kt:451`), i.e. the loading/unknown state.
     */
    private fun hostResult(verdict: Verdict): ReviewVcsDiffResult? = when (verdict) {
        Verdict.IN_FLIGHT -> null
        Verdict.FILES -> ReviewVcsDiffResult.Loaded(List(207) { FileDiffStub })
        Verdict.EMPTY -> ReviewVcsDiffResult.Loaded(emptyList())
        Verdict.FAILED -> ReviewVcsDiffResult.Failed
    }

    private val FileDiffStub = OpenCodeClient.FileDiff(
        file = "a.txt",
        patch = null,
        additions = 1,
        deletions = 0,
        status = "modified",
    )

    /**
     * The Changes body the given host result renders on the Git scope, mirroring
     * `SessionScreen.kt:449-451` + `ReviewScopes.changesBody`.
     *
     * `null` — the host's in-flight answer (D11-b [Verdict.IN_FLIGHT]) — is the
     * `loadPending` input, so it renders the loading body, never the clean one.
     */
    private fun body(result: ReviewVcsDiffResult?): ChangesBody = ReviewScopes.changesBody(
        scope = GIT,
        noGit = false,
        loadFailed = result is ReviewVcsDiffResult.Failed,
        loadPending = result == null,
        hasDiffs = (result as? ReviewVcsDiffResult.Loaded)?.diffs?.isNotEmpty() == true,
    )

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText().replace("\r\n", "\n")
    }

    private fun viewModel() = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

    private fun reviewModels() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewModels.kt")

    private fun reviewChangesTab() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewChangesTab.kt")
}
