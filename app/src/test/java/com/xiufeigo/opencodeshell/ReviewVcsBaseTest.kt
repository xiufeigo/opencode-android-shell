package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.review.ReviewVcsBase
import com.xiufeigo.opencodeshell.v2.VcsBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic coverage for the review diff base line (`GET /api/vcs/base` →
 * 「相对 `<name>@<ref>`」): the `name@ref` label and its half-missing fallbacks.
 */
class ReviewVcsBaseTest {

    private fun base(name: String, ref: String) = VcsBase(name = name, ref = ref, source = VcsBase.SOURCE_DEFAULT)

    @Test
    fun labelJoinsNameAndRefWithAt() {
        assertEquals("main@refs/heads/main", ReviewVcsBase.label(base("main", "refs/heads/main")))
        assertEquals("v2@refs/remotes/origin/v2", ReviewVcsBase.label(base("v2", "refs/remotes/origin/v2")))
    }

    @Test
    fun halfMissingPairsDegradeToWhicheverHalfExists() {
        assertEquals("main", ReviewVcsBase.label(base("main", "")))
        assertEquals("refs/heads/main", ReviewVcsBase.label(base("  ", "refs/heads/main")))
        assertEquals("main@refs/heads/main", ReviewVcsBase.label(base("  main  ", "  refs/heads/main  ")))
    }

    @Test
    fun noBaseIsAnAbsentLineNotAnEmptyOne() {
        assertNull(ReviewVcsBase.label(null))
        assertNull(ReviewVcsBase.label(base("", "")))
    }
}
