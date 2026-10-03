package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.review.NewReviewComment
import com.xiufeigo.opencodeshell.ui.session.review.ReviewCommentPrompt
import com.xiufeigo.opencodeshell.ui.session.review.ReviewCommentStore
import com.xiufeigo.opencodeshell.ui.session.review.ReviewLineQuote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-2 line comments: the store's add/list/all/update/remove/replace/clear
 * (upstream `composer/comments.tsx:35-41,100-148`: per-file insertion order,
 * aggregate sorted by `time`) and the prompt-text rendering (upstream
 * `composer/comment-note.ts:103-113` `formatCommentNote` +
 * `composer/request.ts:124-133` text assembly), plus the tap-to-quote format.
 *
 * The expected note sentences pin the upstream English wording verbatim:
 * `parseCommentNote` (`comment-note.ts:115-135`) round-trips it, so any
 * rewording here means the wire/display contract forked.
 */
class ReviewCommentsTest {

    private fun input(
        file: String,
        comment: String,
        line: Int = 12,
    ) = NewReviewComment(file = file, startLine = line, endLine = line, comment = comment)

    // ------------------------------------------------------------------ store

    @Test
    fun listKeepsInsertionOrderWhileAllSortsByTimeAcrossFiles() {
        val store = ReviewCommentStore()
        store.add(input("a.ts", "a-late"), id = "a-late", timeMs = 20_000)
        store.add(input("a.ts", "a-early"), id = "a-early", timeMs = 1_000)
        store.add(input("b.ts", "b-mid"), id = "b-mid", timeMs = 10_000)

        assertEquals(listOf("a-late", "a-early"), store.list("a.ts").map { it.id })
        assertEquals(listOf("a-early", "b-mid", "a-late"), store.all().map { it.id })
    }

    @Test
    fun updateChangesOnlyTheTargetComment() {
        val store = ReviewCommentStore()
        store.add(input("a.ts", "first"), id = "a1", timeMs = 1)
        store.add(input("a.ts", "second"), id = "a2", timeMs = 2)

        store.update("a.ts", "a2", "second, revised")

        assertEquals("first", store.list("a.ts").first { it.id == "a1" }.comment)
        assertEquals("second, revised", store.list("a.ts").first { it.id == "a2" }.comment)
    }

    @Test
    fun removeDropsOnlyTheTargetComment() {
        val store = ReviewCommentStore()
        store.add(input("a.ts", "keep"), id = "keep", timeMs = 1)
        store.add(input("a.ts", "drop"), id = "drop", timeMs = 2)
        store.add(input("b.ts", "other"), id = "other", timeMs = 3)

        store.remove("a.ts", "drop")

        assertEquals(listOf("keep"), store.list("a.ts").map { it.id })
        assertEquals(listOf("other"), store.list("b.ts").map { it.id })
        assertEquals(2, store.all().size)
    }

    @Test
    fun replaceAndClearResetTheStore() {
        val store = ReviewCommentStore()
        store.add(input("a.ts", "stale"), id = "stale", timeMs = 1)

        val restored = store.add(input("b.ts", "next"), id = "next", timeMs = 2)
        store.replace(listOf(restored))

        assertTrue(store.list("a.ts").isEmpty())
        assertEquals(listOf("next"), store.all().map { it.id })

        store.clear()
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun addStampsIdTimeAndPreview() {
        val store = ReviewCommentStore()
        val added = store.add(
            input("a.ts", "anchored").copy(preview = "const x = 1"),
            id = "fixed-id",
            timeMs = 42,
        )

        assertEquals("fixed-id", added.id)
        assertEquals(42L, added.timeMs)
        assertEquals("const x = 1", added.preview)
        assertEquals(added, store.list("a.ts").single())
    }

    // ------------------------------------------------------------ prompt text

    @Test
    fun formatNotePinsTheUpstreamSentenceForASingleLine() {
        assertEquals(
            "The user made the following comment regarding line 12 of src/app.tsx: Keep the normal flow flat",
            ReviewCommentPrompt.formatNote("src/app.tsx", 12, 12, "Keep the normal flow flat"),
        )
    }

    @Test
    fun formatNotePinsTheUpstreamSentenceForARange() {
        assertEquals(
            "The user made the following comment regarding lines 12 through 14 of src/app.tsx: Keep it flat",
            ReviewCommentPrompt.formatNote("src/app.tsx", 12, 14, "Keep it flat"),
        )
    }

    @Test
    fun formatNoteNormalizesAReversedRangeLikeUpstreamMinMax() {
        assertEquals(
            "The user made the following comment regarding lines 12 through 14 of src/app.tsx: Keep it flat",
            ReviewCommentPrompt.formatNote("src/app.tsx", 14, 12, "Keep it flat"),
        )
    }

    @Test
    fun formatNoteFallsBackToThisFileWithoutALineAnchor() {
        assertEquals(
            "The user made the following comment regarding this file of src/app.tsx: Overall LGTM",
            ReviewCommentPrompt.formatNote("src/app.tsx", null, null, "Overall LGTM"),
        )
    }

    @Test
    fun buildPromptPutsBaseTextFirstThenNotesInChronologicalOrder() {
        val store = ReviewCommentStore()
        store.add(input("b.ts", "second file", line = 3), id = "c2", timeMs = 20)
        store.add(input("a.ts", "first file", line = 7), id = "c1", timeMs = 10)

        assertEquals(
            "please review\n" +
                "The user made the following comment regarding line 7 of a.ts: first file\n" +
                "The user made the following comment regarding line 3 of b.ts: second file",
            ReviewCommentPrompt.buildPrompt("please review", store.all()),
        )
    }

    @Test
    fun buildPromptSkipsBlankCommentsAndOmitsTheBaseLineWhenBlank() {
        val store = ReviewCommentStore()
        store.add(input("a.ts", "   "), id = "blank", timeMs = 1)
        store.add(input("a.ts", "real", line = 5), id = "real", timeMs = 2)

        assertEquals(
            "The user made the following comment regarding line 5 of a.ts: real",
            ReviewCommentPrompt.buildPrompt("  ", store.all()),
        )
    }

    // ------------------------------------------------------------------ quote

    @Test
    fun quoteLinePrefersTheNewSideNumber() {
        assertEquals(
            "`src/A.kt:12`: val x = 1",
            ReviewLineQuote.quoteLine("src/A.kt", oldLine = 9, newLine = 12, content = "val x = 1"),
        )
    }

    @Test
    fun quoteLineFallsBackToTheOldSideForPureDeletions() {
        assertEquals(
            "`src/A.kt:9`: val x = 1",
            ReviewLineQuote.quoteLine("src/A.kt", oldLine = 9, newLine = null, content = "val x = 1"),
        )
    }

    @Test
    fun quoteLineReturnsNullWithoutAnyLineNumber() {
        assertNull(ReviewLineQuote.quoteLine("src/A.kt", oldLine = null, newLine = null, content = "@@ -1 +1 @@"))
    }

    @Test
    fun quoteLineTruncatesLongBodies() {
        val quoted = ReviewLineQuote.quoteLine("src/A.kt", oldLine = null, newLine = 1, content = "x".repeat(300))

        assertTrue(quoted!!.startsWith("`src/A.kt:1`: "))
        assertEquals(ReviewLineQuote.MAX_QUOTE_CHARS, quoted.removePrefix("`src/A.kt:1`: ").length)
    }
}
