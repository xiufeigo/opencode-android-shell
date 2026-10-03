package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.review.ReviewChangeScope
import com.xiufeigo.opencodeshell.ui.session.review.ReviewScopes
import com.xiufeigo.opencodeshell.ui.session.review.ReviewScopes.ChangesBody
import com.xiufeigo.opencodeshell.ui.session.review.ReviewVcsDiffResult
import com.xiufeigo.opencodeshell.ui.session.review.ReviewVcsProbePlan
import com.xiufeigo.opencodeshell.ui.session.review.ReviewVcsProbePlan.Attempt
import com.xiufeigo.opencodeshell.ui.session.review.ReviewVcsProbePlan.Failure
import com.xiufeigo.opencodeshell.ui.session.review.ReviewVcsProbePlan.ReadPhase
import com.xiufeigo.opencodeshell.ui.session.review.ReviewVcsProbePlan.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v97 D11 **round two** (`research/v97_verify/5556_v98/REPORT_D11.md` §3 条目 2/3,
 * fixed in `research/v97_fix/REVIEW_DIFF_HONESTY_FIX2.md`).
 *
 * Round one made the *finished* walk honest: `[Answered(0), Failed]` no longer
 * collapsed to 「no uncommitted changes」. It left the timing alone, and the real
 * device still flashed the false empty — **17 % of refreshes**, `No uncommitted
 * changes yet` at T+3 s for a tree whose host `git diff --shortstat HEAD` read
 * `1191 files changed, 109218 insertions(+), 60354 deletions(-)`.
 *
 * Two holes let it through, and both are pinned here:
 *
 * 1. **The render gate had no in-flight state.** `Attempt` modelled only
 *    `Answered` / `Failed`, so 「nobody has come back yet」 and 「everybody said
 *    nothing changed」 were distinguishable only by *when* the caller looked.
 *    D11-b adds [Attempt.Pending], [Verdict.IN_FLIGHT], [readPhase] and
 *    [ReviewVcsProbePlan.mayRenderClean]: 未知 ≠ 空. The licence to say 「no
 *    uncommitted changes」 exists in exactly one state out of four.
 * 2. **A `vcs/status` answer was promoting a ref for the `vcs/diff` walk.**
 *    `reviewVcsInfo` called `rememberReviewVcsRef` on the ref that answered
 *    `vcs/status`, and that map is the diff walk's `remembered` input. A location
 *    whose cached graph predates the repository answers **both** routes while its
 *    `vcs/diff` is `[]` whatever the tree holds — so the promotion collapsed the
 *    diff walk to that single ref, it answered `[]` in milliseconds, and the
 *    fresh-graph ref that could have disproved it was never asked.
 *
 * Pinned: the four-state truth table, the clean-copy licence, the re-read entry
 * leaving the clean state before any read lands, and the two source invariants
 * above. No existing assertion was relaxed — `ReviewDiffHonestyV97Test`'s nine
 * cases are untouched except for gaining the new `IN_FLIGHT` arm in its mirror
 * of the host `when`.
 */
class ReviewDiffReadStateV97Test {

    // ---------------------------------------- ① the four-state truth table

    /**
     * The whole rule in one table: **in flight / settled-empty / settled-nonempty
     * / settled-failed**, and which of them may render the clean copy. Only
     * settled-empty ever may — that is the D11-b invariant in one assertion per
     * row.
     */
    @Test
    fun theFourStateTruthTableLicensesOnlySettledEmpty() {
        val answered0 = Attempt.Answered(0)
        val answered3 = Attempt.Answered(3)
        val transient = Attempt.Failed(Failure.TRANSIENT)
        val permanent = Attempt.Failed(Failure.PERMANENT)

        // in flight — nothing may be claimed, and the clean copy is the one
        // thing that must not be: a read that has not come back is not a read
        // that came back empty.
        assertEquals(ReadPhase.IN_FLIGHT, phase(Attempt.Pending))
        assertEquals(ReadPhase.IN_FLIGHT, phase(Attempt.Pending, answered0))
        assertEquals(ReadPhase.IN_FLIGHT, phase(answered0, Attempt.Pending))
        assertEquals(
            "a failed sibling does not settle the rest of the walk",
            ReadPhase.IN_FLIGHT,
            phase(transient, Attempt.Pending),
        )
        assertFalse(
            "in flight must never license the clean copy",
            ReviewVcsProbePlan.mayRenderClean(ReadPhase.IN_FLIGHT),
        )

        // settled-empty — the only licensed state. Both the single-ref vacuum
        // (small clean repository) and the corroborated one.
        assertEquals(ReadPhase.SETTLED_EMPTY, phase(answered0))
        assertEquals(ReadPhase.SETTLED_EMPTY, phase(answered0, answered0))
        assertTrue(
            "every candidate answering 「nothing changed」 is the whole truth",
            ReviewVcsProbePlan.mayRenderClean(ReadPhase.SETTLED_EMPTY),
        )

        // settled-nonempty — real data, never the clean copy.
        assertEquals(ReadPhase.SETTLED_NONEMPTY, phase(answered3))
        assertEquals(ReadPhase.SETTLED_NONEMPTY, phase(answered0, answered3))
        assertEquals(
            "a real list still wins when a sibling timed out (the A2 path)",
            ReadPhase.SETTLED_NONEMPTY,
            phase(transient, answered3),
        )
        assertFalse(ReviewVcsProbePlan.mayRenderClean(ReadPhase.SETTLED_NONEMPTY))

        // settled-failed — round one's D11 verdict, unchanged.
        assertEquals(ReadPhase.SETTLED_FAILED, phase(transient))
        assertEquals(ReadPhase.SETTLED_FAILED, phase(permanent))
        assertEquals(
            "★ D11: an empty answer beside a timeout is not a clean tree",
            ReadPhase.SETTLED_FAILED,
            phase(answered0, transient),
        )
        assertEquals(
            "…and the walk must be order-free",
            ReadPhase.SETTLED_FAILED,
            phase(transient, answered0),
        )
        assertEquals(ReadPhase.SETTLED_FAILED, phase(answered0, answered0, transient))
        assertEquals(ReadPhase.SETTLED_FAILED, phase(answered0, permanent))
        assertEquals(ReadPhase.SETTLED_FAILED, phase())
        assertFalse(ReviewVcsProbePlan.mayRenderClean(ReadPhase.SETTLED_FAILED))

        // Exhaustiveness, over the full input alphabet rather than a sample: no
        // combination of outcomes can produce a state outside the four, and the
        // four are mutually distinct.
        val alphabet = listOf(
            Attempt.Pending,
            Attempt.Answered(0),
            Attempt.Answered(2),
            Attempt.Failed(Failure.TRANSIENT),
            Attempt.Failed(Failure.PERMANENT),
        )
        val seen = LinkedHashSet<ReadPhase>()
        for (a in alphabet) {
            for (b in alphabet) {
                for (c in listOf(null, Attempt.Pending, Attempt.Answered(0), Attempt.Answered(1))) {
                    val input = listOfNotNull(a, b, c)
                    val p = phaseOf(input)
                    assertTrue("unexpected phase $p for $input", p in ReadPhase.values().toList())
                    seen += p
                }
            }
        }
        assertEquals("the enumeration must reach all four states", 4, seen.size)
    }

    /**
     * The four observed states, mapped the way the panel maps them, so the
     * licence in ① is checked all the way to the body that is rendered. The
     * clean copy is reachable from exactly one of the four.
     */
    @Test
    fun onlySettledEmptyRendersTheCleanCopy() {
        // in flight → the host publishes nothing → the panel reads null →
        // `loadPending` → loading. Never the clean copy.
        val inFlightHost: ReviewVcsDiffResult? = null
        assertEquals(ChangesBody.LOADING, body(inFlightHost))
        assertEquals(
            ChangesBody.LOAD_SLOW,
            body(inFlightHost, stalled = true),
        )

        // settled-empty → really clean → the copy is earned.
        val emptyHost: ReviewVcsDiffResult? = ReviewVcsDiffResult.Loaded(emptyList())
        assertEquals(ChangesBody.EMPTY, body(emptyHost))

        // settled-nonempty → the list.
        val listHost: ReviewVcsDiffResult? =
            ReviewVcsDiffResult.Loaded(List(1191) { FileDiffStub })
        assertEquals(ChangesBody.LIST, body(listHost))

        // settled-failed → the honest failure, with the retry affordance.
        val failedHost: ReviewVcsDiffResult? = ReviewVcsDiffResult.Failed
        assertEquals(ChangesBody.LOAD_FAILED, body(failedHost))

        // …and the four are four different screens.
        val bodies = listOf(
            body(inFlightHost),
            body(emptyHost),
            body(listHost),
            body(failedHost),
        )
        assertEquals("four states must render four different bodies", 4, bodies.toSet().size)
        assertNotEquals(bodies[0], bodies[1])
    }

    /**
     * The phase a **walk** reports must not depend on which state the caller
     * happens to look at: publishing twice — once mid-walk, once at the end —
     * is what produced the flash, and the first answer must be 「still reading」,
     * not the eventual `[]`.
     */
    @Test
    fun aWalkThatIsLookedAtEarlySpeaksAsUnknownNotAsEmpty() {
        val candidates = 2
        val walked = ArrayList<Attempt>(candidates).apply {
            repeat(candidates) { add(Attempt.Pending) }
        }
        // Mid-walk, with nothing settled yet.
        assertEquals(ReadPhase.IN_FLIGHT, phaseOf(walked))
        assertFalse(ReviewVcsProbePlan.mayRenderClean(phaseOf(walked)))

        // The stale ref answers `[]` and retires its own placeholder — the exact
        // T+3 s frame of REPORT_D11: one sibling has now said 「nothing changed」
        // while the fresh-graph ref is still in flight.
        walked.removeAt(0)
        walked.add(Attempt.Answered(0))
        assertEquals(
            "★ the false-empty frame: one empty answer, one read still in flight",
            ReadPhase.IN_FLIGHT,
            phaseOf(walked),
        )
        assertFalse(
            "★ that frame must not be allowed to claim a clean tree",
            ReviewVcsProbePlan.mayRenderClean(phaseOf(walked)),
        )

        // The fresh-graph ref then times out → the walk settles, and now — and
        // only now — the honest answer is available: failed, not clean.
        walked.removeAt(0)
        walked.add(Attempt.Failed(Failure.TRANSIENT))
        assertEquals(ReadPhase.SETTLED_FAILED, phaseOf(walked))
        assertFalse(ReviewVcsProbePlan.mayRenderClean(phaseOf(walked)))
    }

    // ---------------------------------------------------------------- ② pins

    /**
     * **The re-read entry must leave the clean state before any read lands.**
     *
     * `requestVcsDiff` clears the single slot to `null` *synchronously, before*
     * `reviewVcsDiff` is called, and `vcsDiffPending` is defined as that slot
     * being null. Together they are what makes 「press Refresh → unknown」
     * immediate rather than 「press Refresh → keep showing the old verdict until
     * the new one lands」 — the second shape is the stale-成功空态 the report
     * caught. If the clear ever moved after the call, or the pending flag were
     * derived from anything but the slot, this fails.
     */
    @Test
    fun aReReadEntryLeavesTheCleanStateBeforeAnyReadLands() {
        val screen = sessionScreen()

        val request = screen.substringAfter("val requestVcsDiff:").substringBefore("}")
        val clearAt = request.indexOf("vcsDiffResult = null")
        val readAt = request.indexOf("chatViewModel.reviewVcsDiff(")
        assertTrue("the re-read must clear the previous verdict", clearAt > 0)
        assertTrue("the re-read must still issue the read", readAt > 0)
        assertTrue(
            "the slot must be cleared *before* the read is issued, so the window " +
                "between the tap and the answer is unknown — not the old verdict",
            clearAt < readAt,
        )
        assertFalse(
            "a failed re-read must not be folded into the clear",
            request.contains("catch"),
        )

        // The pending flag is the cleared slot, nothing else — so 「in flight」 is
        // a first-class render input rather than an inference.
        assertTrue(
            screen.contains("val vcsDiffPending = vcsDiffResult == null"),
        )
        assertTrue(
            screen.contains("val vcsDiffFailed = vcsDiffResult is ReviewVcsDiffResult.Failed"),
        )
    }

    /**
     * **A `vcs/status` answer may not promote a ref for the `vcs/diff` walk.**
     *
     * This is the round-two root cause. `reviewVcsInfo` used to call
     * `rememberReviewVcsRef(ref)` / `markReviewVcsProbed()`, and
     * `reviewVcsCandidates` feeds exactly that map to the diff walk as
     * `remembered` / `probed` — so a ref that merely answered `vcs/status`
     * became 「the only ref worth asking」 for `vcs/diff`, whose `[]` from a
     * pre-repository location graph is evidence of nothing. The pin is that the
     * info walk remembers nothing, and that the diff walk only ever remembers a
     * ref **behind a non-empty list**.
     */
    @Test
    fun aStatusAnswerCannotPromoteARefForTheDiffWalk() {
        val info = viewModel()
            .substringAfter("fun reviewVcsInfo(")
            .substringBefore("fun reviewVcsDiff(")
        assertFalse(
            "a vcs/status answer must not be remembered for the vcs/diff walk — " +
                "it collapses the walk to one []-prone ref (REPORT_D11 §3)",
            info.contains("rememberReviewVcsRef"),
        )
        assertFalse(
            "a vcs/status walk that answered nothing must not latch the diff " +
                "walk onto the canonical ref alone",
            info.contains("markReviewVcsProbed"),
        )
        // It still walks the shared candidate list, so nothing else regressed.
        assertTrue(info.contains("reviewVcsCandidates()"))

        // The diff walk's own memory is earned, not inherited: the ref is
        // remembered exactly once, and only behind a non-empty list.
        val diffRead = diffRead()
        assertEquals(
            "a ref may only be remembered from the one place that has a real list",
            1,
            Regex("rememberReviewVcsRef\\(ref\\)").findAll(diffRead).count(),
        )
        val guard = diffRead.substringAfter("if (list.isNotEmpty()) {")
            .substringBefore("\n            }")
        assertTrue(
            "the ref may only be remembered behind a non-empty list",
            guard.contains("rememberReviewVcsRef(ref)"),
        )
    }

    /**
     * **A read the user has moved on from may not paint over the retry.**
     *
     * `LaunchedEffect` cancellation does not cancel a coroutine the ViewModel
     * owns, so the previous attempt's 20 s-deadline walk was still alive when the
     * retry started, and its verdict used to land in the retry's slot — the
     * T+20 s banner painting over the T+3 s frame. The attempt id is claimed on
     * entry and checked before publishing; a superseded walk stays silent and the
     * panel keeps the unknown state, which is true of both reads.
     */
    @Test
    fun aSupersededReadNeverPublishesOverTheRetry() {
        val diffRead = diffRead()
        assertTrue(
            "the attempt id must be claimed before the read is launched",
            diffRead.contains("reviewVcsDiffAttempt.incrementAndGet()"),
        )
        assertTrue(
            diffRead.substringAfter("incrementAndGet()").let {
                it.indexOf("val attemptId =") < it.indexOf("scope.launch(Dispatchers.IO)")
            },
        )
        assertTrue(
            "publishing must be conditional on still being the newest attempt",
            diffRead.contains("attemptId == reviewVcsDiffAttempt.get()"),
        )
        // Silence is the default: the in-flight arm yields no result at all.
        assertTrue(
            "an unsettled walk must publish nothing (null), not an empty list",
            diffRead.contains("ReviewVcsProbePlan.Verdict.IN_FLIGHT -> null"),
        )
    }

    /**
     * **The host models in-flight, it does not only model outcomes.** One
     * placeholder per candidate is seeded before the walk, and each iteration
     * retires exactly its own before appending that ref's answer — so a partial
     * walk is a value the rule can see instead of an absence it has to infer.
     */
    @Test
    fun theHostSeedsEveryCandidateAsInFlightBeforeWalking() {
        val diffRead = diffRead()
        assertTrue(
            "every candidate must start life as in flight",
            diffRead.contains("repeat(candidates.size) { attempts += ReviewVcsProbePlan.Attempt.Pending }"),
        )
        val loop = diffRead.substringAfter("for (ref in candidates)").substringBefore("val settled =")
        assertTrue(
            "each iteration retires its own placeholder first",
            loop.contains("attempts.removeAt(0)"),
        )
        assertFalse(
            "★ nothing inside the walk may become a verdict — not even an empty one",
            loop.contains("ReviewVcsDiffResult"),
        )
        // Every verdict is still handled explicitly, the new one included.
        for (verdict in Verdict.values()) {
            assertTrue(
                "the $verdict verdict is not handled",
                diffRead.contains("Verdict.$verdict"),
            )
        }
    }

    /**
     * The clean copy keeps its existing copy and its existing string; D11-b
     * added no user-visible text. The loading and failure bodies it relies on
     * instead are the ones already in the tab.
     */
    @Test
    fun theUnknownStateReusesTheExistingStrings() {
        val tab = reviewChangesTab()
        // The clean copy is the `else` arm of the tab's own `when`, and it keeps
        // its pre-existing string resource — D11-b added no fourth copy.
        assertTrue(
            "the clean copy must keep its pre-existing string resource",
            tab.contains("text = stringResource(ReviewScopes.emptyRes(scope))"),
        )
        assertTrue(
            "the failure body must keep the existing failure copy",
            tab.contains("ChangesBody.LOAD_FAILED -> LoadFailedEmpty("),
        )
        assertTrue(
            "the slow read keeps the existing hint that names 「刷新」",
            tab.contains("R.string.oc_review_changes_failed_hint)"),
        )
        val strings = source("src/main/res/values/strings.xml")
        assertTrue(strings.contains("<string name=\"oc_review_changes_empty_git\">"))
        // No fourth copy for 「still reading」: loading and slow already exist.
        assertTrue(tab.contains("ChangesBody.LOADING ->"))
        assertTrue(tab.contains("ChangesBody.LOAD_SLOW ->"))
    }

    // ---------------------------------------------------------------- helpers

    private fun phase(vararg attempts: Attempt): ReadPhase =
        ReviewVcsProbePlan.readPhase(attempts.toList())

    /** The same rule over a list, for a walk observed in the middle of running. */
    private fun phaseOf(input: List<Attempt>): ReadPhase = ReviewVcsProbePlan.readPhase(input)

    private fun diffRead() = viewModel()
        .substringAfter("fun reviewVcsDiff(")
        .substringBefore("private fun reviewVcsApi(")

    /** Mirrors `SessionScreen.kt:449-451` + `ReviewScopes.changesBody`. */
    private fun body(result: ReviewVcsDiffResult?, stalled: Boolean = false): ChangesBody =
        ReviewScopes.changesBody(
            scope = ReviewChangeScope.GIT,
            noGit = false,
            loadFailed = result is ReviewVcsDiffResult.Failed,
            loadPending = result == null,
            hasDiffs = (result as? ReviewVcsDiffResult.Loaded)?.diffs?.isNotEmpty() == true,
            loadStalled = stalled,
        )

    private val FileDiffStub = OpenCodeClient.FileDiff(
        file = "a.txt",
        patch = null,
        additions = 1,
        deletions = 0,
        status = "modified",
    )

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText().replace("\r\n", "\n")
    }

    private fun viewModel() = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

    private fun sessionScreen() = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private fun reviewChangesTab() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewChangesTab.kt")
}
