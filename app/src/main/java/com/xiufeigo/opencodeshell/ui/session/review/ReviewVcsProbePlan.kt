package com.xiufeigo.opencodeshell.ui.session.review

/**
 * Which location refs the next review VCS read may try, and which failure is
 * allowed to narrow them (v97 fix, `research/v97_fix/REVIEW_HOME_SEARCH_DIAG.md`
 * §N6-②).
 *
 * Split out of `ChatViewModel.reviewVcsDiff` so the rule is a pure function the
 * JVM tests can pin without an Android runtime, and so the two halves of the
 * rule — *which refs are still on the table* and *may a failure retire them* —
 * cannot drift apart.
 *
 * **Why the second half exists.** `reviewVcsProbed` was designed as 「a
 * home-directory working diff is expensive to produce, so probe it once」
 * (`ChatViewModel.kt:5222-5223`). It also fired on *every* unanswered read,
 * including a 20 s wall-clock timeout on a huge working tree — the exact case
 * `mode=working` is known to hang on. That turned a **transient** failure into a
 * **permanent** capability loss: [candidates] then returns the canonical ref
 * only, so every later retry is strictly weaker than the first attempt, on a
 * directory the server was merely busy diffing. A deterministic *rejection* is
 * the only verdict that may retire the fresh-graph variant — retrying a ref the
 * server has already answered 「no」 cannot produce a different answer.
 */
object ReviewVcsProbePlan {

    /**
     * How a candidate ref failed. [TRANSIENT] is 「this attempt did not get an
     * answer」 (timeout, socket error, cancellation, non-HTTP crash) and leaves
     * the full candidate list for the next attempt; [PERMANENT] is 「the server
     * answered, and the answer was a refusal」 (an HTTP status that rules the ref
     * out) and may retire it.
     */
    enum class Failure {
        TRANSIENT,
        PERMANENT,
    }

    /**
     * Location refs one read may try, in order.
     *
     * @param remembered the ref that already answered for this directory
     *   ([ChatViewModel.rememberReviewVcsRef]) — the only ref worth asking once a
     *   directory is known to be reachable.
     * @param probed whether the directory was already *permanently* probed
     *   without an answer; only then does the fresh-graph variant retire.
     */
    fun candidates(directory: String?, remembered: String?, probed: Boolean): List<String?> {
        val dir = directory?.trim().orEmpty()
        if (dir.isEmpty()) return listOf(null)
        remembered?.takeIf { it.isNotBlank() }?.let { return listOf(it) }
        if (probed) return listOf(dir)
        return ReviewVcsReprobe.refs(dir)
    }

    /**
     * Whether a failure may be recorded as 「this directory is already probed」,
     * i.e. whether the next attempt starts from the canonical ref alone.
     *
     * [TRANSIENT] → false, so a timeout costs the user nothing and the retry
     * walks the same candidate list the first attempt walked. [PERMANENT] → true.
     */
    fun shouldMarkProbed(failure: Failure): Boolean = failure == Failure.PERMANENT

    /**
     * The failure class of one candidate's exception, so the caller never has to
     * repeat the HTTP-status rule. An [com.xiufeigo.opencodeshell.v2.V2Api.ApiException]
     * carries the server's status: 4xx is the server refusing this request
     * (permanent), 5xx is the server failing (transient). Anything else — a
     * socket timeout, an `IOException`, a cancellation — never got an answer, so
     * it is transient. An unscoped read ([code] = null, no status at all) that
     * threw is transient too.
     *
     * Pinned by `ReviewVcsProbePlanTest`: a future caller must not be able to
     * reintroduce 「every exception is permanent」 by widening this.
     */
    fun failureOf(code: Int?): Failure =
        if (code != null && code in 400..499) Failure.PERMANENT else Failure.TRANSIENT

    /**
     * What one candidate ref's read produced — the raw material of [verdict].
     *
     * [Answered] carries only the file **count**: the verdict is a question about
     * 「did this ref know the answer」, not about the patches themselves, so the
     * pure rule never needs a [com.xiufeigo.opencodeshell.OpenCodeClient.FileDiff].
     */
    sealed interface Attempt {
        /**
         * The ref is **in flight** — issued, not yet answered. The v97 D11 round
         * one only modelled the settled outcomes ([Answered] / [Failed]), so a
         * walk's *timing* had nowhere to live: the only way to publish was to
         * publish a finished walk, and a caller that read the halfway state
         * (or raced a second, shorter walk over the same slot) could promote a
         * stale `[]` to a whole-walk answer. 未知 ≠ 空 starts here.
         */
        data object Pending : Attempt

        /** The ref came back, with [files] changed files (0 = 「this scope is clean」). */
        data class Answered(val files: Int) : Attempt

        /** The ref never produced an answer — [failure] says why it may be retired. */
        data class Failed(val failure: Failure) : Attempt
    }

    /**
     * The three states a candidate walk can end in, and the rule that picks
     * between them (v97 fix, `research/v97_fix/REVIEW_DIFF_HONESTY_FIX.md` D11).
     *
     * ```
     * verdict([])                                  = FAILED  // nothing even tried
     * verdict([Answered(n>0), …])                  = FILES   // real data wins
     * verdict([Answered(0), Answered(0)])          = EMPTY   // vacuum: really clean
     * verdict([Answered(0), Failed(…)])            = FAILED  // ← the D11 path
     * ```
     *
     * **Why an empty answer cannot stand alone (D11).** An empty list is only
     * evidence of 「no uncommitted changes」 when it is the *whole* truth. A
     * location whose cached services predate its repository answers `[]` for a
     * **different** reason — 「I have no VCS provider, so there is nothing I can
     * tell you」 ([ReviewVcsReprobe]'s measurement) — and that answer is
     * indistinguishable on the wire from a clean tree. So when the walk holds
     * *more than one* candidate, an empty answer from the stale canonical ref
     * says nothing about the fresh-graph ref that was walked next; if that one
     * timed out (the 20 s wall on a 207-file working tree), the honest verdict is
     * that the read **failed**, not that the repository is clean.
     *
     * Measured 2026-10-02 (emulator 5556, `research/v97_verify/5556_v98`
     * `c2_082_refresh23s.png` / `c2_083_refresh23s.xml`): the canonical ref
     * answered `[]` in milliseconds, the `<dir>/.` re-probe hung past the 20 s
     * deadline, and the old aggregation kept the stale `[]` — the panel showed
     * 「No uncommitted changes yet」 at T+23 s for a tree whose host-side
     * `git diff --shortstat HEAD` read `207 files changed, 58099 insertions(+),
     * 9251 deletions(-)`. Empty + a failed sibling is the one combination that
     * turns a transient failure into a *confident wrong* answer, which is worse
     * than the error it replaced.
     *
     * The rule is deliberately narrow so nothing that works today regresses:
     * - **FILES wins outright**, so a retry that finally lands still restores the
     *   real list (v97 N6-a / the A2 「Refresh recovers real data」 case);
     * - **EMPTY needs no failure at all** — a walk where every candidate answered
     *   is a genuine 「nothing changed」, so a small clean repository keeps saying
     *   「No uncommitted changes yet」;
     * - only the *mixed* verdict changes, and it now says what actually happened.
     *
     * Pure, so `ReviewDiffHonestyV97Test` pins the whole truth table on the JVM.
     */
    fun verdict(attempts: List<Attempt>): Verdict = when {
        // 未知 ≠ 空 (v97 D11-b, `research/v97_fix/REVIEW_DIFF_HONESTY_FIX2.md`):
        // while any candidate is still in flight this walk has **not settled**,
        // so no clean conclusion is licensed. Checked before everything else so
        // a caller that asks a halfway question can never be told 「clean」 —
        // the honest answer at that moment is 「still reading」 ([ReadPhase]).
        attempts.any { it is Attempt.Pending }          -> Verdict.IN_FLIGHT
        // A real list is the whole truth; nothing later can improve on it.
        attempts.any { it is Attempt.Answered && it.files > 0 } -> Verdict.FILES
        // Nothing answered at all — including the degenerate empty walk.
        attempts.none { it is Attempt.Answered } -> Verdict.FAILED
        // Answered, but only ever empty, **and** a sibling ref failed: the empty
        // answer came from a ref that may simply have had nothing to tell, so it
        // cannot be promoted to 「the working tree is clean」 (D11).
        attempts.any { it is Attempt.Failed } -> Verdict.FAILED
        // Every candidate answered, and every one of them said 「nothing changed」:
        // the one shape where an empty list really is the truth.
        else -> Verdict.EMPTY
    }

    /** Outcome of a [verdict] walk, resolved by the caller against the diffs it kept. */
    enum class Verdict {
        /**
         * At least one candidate is still in flight, so the walk has not
         * settled. **Not** a failure and **not** a clean tree: the caller must
         * render the unknown/loading state and publish nothing.
         */
        IN_FLIGHT,

        /** A candidate answered with real changes; render its list. */
        FILES,

        /** Every candidate answered 「nothing changed」 — the tree really is clean. */
        EMPTY,

        /** The walk learned nothing trustworthy; report the failure, never a clean tree. */
        FAILED,
    }

    /**
     * The four states a read can be *observed* in, as opposed to the three a
     * **finished** walk can end in ([Verdict]). [verdict] answers 「what did the
     * walk learn」; this answers 「what may the screen say right now」, and the
     * two differ in exactly one case — **in flight**.
     *
     * ```
     * readPhase(…)                          → mayRenderClean
     * any Pending                           → IN_FLIGHT          false  // 未知 ≠ 空
     * settled, any Answered(files > 0)      → SETTLED_NONEMPTY   false
     * settled, no Answered at all           → SETTLED_FAILED     false
     * settled, every Answered == 0          → SETTLED_EMPTY      **true**
     * ```
     *
     * [mayRenderClean] is the licence to say 「No uncommitted changes yet」, and
     * only [ReadPhase.SETTLED_EMPTY] ever holds it. This is the D11-b guard the
     * render gate is pinned against: the three-state [verdict] can only be
     * consulted on a walk that has already finished, so a caller that wants to
     * publish **before** that must go through here and be told 「no, still
     * reading」 rather than 「empty」.
     */
    fun readPhase(attempts: List<Attempt>): ReadPhase = when (verdict(attempts)) {
        Verdict.IN_FLIGHT -> ReadPhase.IN_FLIGHT
        Verdict.FILES -> ReadPhase.SETTLED_NONEMPTY
        Verdict.EMPTY -> ReadPhase.SETTLED_EMPTY
        Verdict.FAILED -> ReadPhase.SETTLED_FAILED
    }

    /** The four states a review read can be observed in. */
    enum class ReadPhase {
        /** Some candidate is still in flight — the read has not settled. */
        IN_FLIGHT,

        /** Settled, and a candidate produced a real list. */
        SETTLED_NONEMPTY,

        /** Settled, and **every** candidate answered 「nothing changed」. */
        SETTLED_EMPTY,

        /** Settled, and the walk learned nothing trustworthy. */
        SETTLED_FAILED,
    }

    /**
     * Whether [phase] licenses the clean copy 「No uncommitted changes yet」.
     *
     * Only [ReadPhase.SETTLED_EMPTY] does. The three other states are the whole
     * point of the D11-b rule: an in-flight read is **not** an empty read, and a
     * read that failed or returned files is obviously not one either.
     */
    fun mayRenderClean(phase: ReadPhase): Boolean = phase == ReadPhase.SETTLED_EMPTY
}
