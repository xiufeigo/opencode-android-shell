package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.review.ReviewVcsProbePlan
import com.xiufeigo.opencodeshell.ui.session.review.ReviewVcsProbePlan.Failure
import com.xiufeigo.opencodeshell.ui.session.review.ReviewVcsReprobe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N6-② pin (`research/v97_fix/REVIEW_HOME_SEARCH_DIAG.md`).
 *
 * The defect: a 20 s timeout on `vcs/diff?mode=working` used to be recorded as
 * 「this directory is already probed」, and `reviewVcsCandidates` then returned
 * the canonical ref alone — so every later retry was **strictly weaker than the
 * first attempt**, on a directory the server was merely busy diffing.
 *
 * These pins fail against the old rule
 * (`if (dir in reviewVcsProbed) listOf(dir)` plus an unconditional
 * `markReviewVcsProbed()`), and they are the regression guard for the fix.
 */
class ReviewVcsProbePlanTest {

    @Test
    fun `a transient failure does not burn the fresh-graph candidates`() {
        // The full candidate list, i.e. the state a never-probed directory is in.
        val before = ReviewVcsProbePlan.candidates("/repo", remembered = null, probed = false)
        // What the next attempt actually sees after a TRANSIENT failure: the
        // `probed` flag is only ever set when `shouldMarkProbed` says so, and for
        // a timeout that is false — so the list is walked again in full.
        val afterTransient = ReviewVcsProbePlan.candidates(
            "/repo",
            remembered = null,
            probed = ReviewVcsProbePlan.shouldMarkProbed(Failure.TRANSIENT),
        )
        // The core pin: retrying after a transient failure must walk the same
        // candidate list the first attempt walked.
        assertEquals(before, afterTransient)
        assertEquals(ReviewVcsReprobe.refs("/repo"), before)
        assertTrue(before.contains("/repo/."))
    }

    @Test
    fun `a transient failure is never recorded as probed`() {
        assertFalse(ReviewVcsProbePlan.shouldMarkProbed(Failure.TRANSIENT))
        assertTrue(ReviewVcsProbePlan.shouldMarkProbed(Failure.PERMANENT))
    }

    @Test
    fun `only a deterministic refusal retires the fresh-graph candidate`() {
        val permanent = ReviewVcsProbePlan.candidates("/repo", remembered = null, probed = true)
        assertEquals(listOf("/repo"), permanent)
        assertNotEquals(ReviewVcsReprobe.refs("/repo"), permanent)
    }

    @Test
    fun `a no-status failure is transient - a timeout has no HTTP verdict`() {
        // The narrow-window symptom: the read died on the client deadline, so
        // the server never refused anything and must not be treated as if it did.
        assertEquals(Failure.TRANSIENT, ReviewVcsProbePlan.failureOf(null))
    }

    @Test
    fun `4xx is the server refusing this ref and 5xx is the server failing`() {
        assertEquals(Failure.PERMANENT, ReviewVcsProbePlan.failureOf(400))
        assertEquals(Failure.PERMANENT, ReviewVcsProbePlan.failureOf(404))
        assertEquals(Failure.PERMANENT, ReviewVcsProbePlan.failureOf(422))
        assertEquals(Failure.TRANSIENT, ReviewVcsProbePlan.failureOf(500))
        assertEquals(Failure.TRANSIENT, ReviewVcsProbePlan.failureOf(502))
        assertEquals(Failure.TRANSIENT, ReviewVcsProbePlan.failureOf(503))
    }

    @Test
    fun `a remembered ref wins over both the fresh-graph list and the probed flag`() {
        val candidates = ReviewVcsProbePlan.candidates("/repo", remembered = "/repo/.", probed = true)
        assertEquals(listOf("/repo/."), candidates)
    }

    @Test
    fun `an unscoped read has no variant to build and never invents one`() {
        // `location[directory]` absent: asking about a fabricated directory
        // would be a question about a different project.
        assertEquals(listOf(null), ReviewVcsProbePlan.candidates(null, remembered = null, probed = false))
        assertEquals(listOf(null), ReviewVcsProbePlan.candidates("  ", remembered = null, probed = false))
    }

    @Test
    fun `a blank remembered ref does not shadow the candidates`() {
        val candidates = ReviewVcsProbePlan.candidates("/repo", remembered = "  ", probed = false)
        assertEquals(ReviewVcsReprobe.refs("/repo"), candidates)
    }
}
