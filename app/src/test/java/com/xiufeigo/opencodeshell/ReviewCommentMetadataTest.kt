package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.review.ReviewCommentMetadata
import com.xiufeigo.opencodeshell.ui.session.review.ReviewCommentPrompt
import com.xiufeigo.opencodeshell.ui.session.review.ReviewLineComment
import com.xiufeigo.opencodeshell.ui.session.review.ReviewNote
import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineComments
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-4: the line-comment metadata chain — the `metadata.comments` array the
 * prompt sends (`composer/submit.ts:169-177`), the note lines the timeline
 * recovers (`comment-note.ts:115-135` `parseCommentNote`), and the card facts
 * built from them.
 */
class ReviewCommentMetadataTest {

    private fun note(path: String, start: Int?, end: Int?, comment: String) =
        ReviewNote(path = path, startLine = start, endLine = end, comment = comment)

    // ------------------------------------------------------------ out (send)

    @Test
    fun oneCommentSerialisesToTheUpstreamMetadataShape() {
        val json = ReviewCommentMetadata.noteJson(note("src/a.kt", 12, 12, "rename this"))
        assertEquals("src/a.kt", json.getString("path"))
        assertEquals("rename this", json.getString("comment"))
        assertEquals("review", json.getString("origin"))
        val selection = json.getJSONObject("selection")
        assertEquals(12, selection.getInt("startLine"))
        assertEquals(12, selection.getInt("endLine"))
        // `FileSelection` carries character offsets; the shell anchors on whole
        // lines (`parseCommentNote` synthesises 0/0 the same way).
        assertEquals(0, selection.getInt("startChar"))
        assertEquals(0, selection.getInt("endChar"))
    }

    @Test
    fun aRangeIsNormalisedAndAWholeFileCommentCarriesNoSelection() {
        val range = ReviewCommentMetadata.noteJson(note("src/a.kt", 18, 12, "x"))
        val selection = range.getJSONObject("selection")
        assertEquals(12, selection.getInt("startLine"))
        assertEquals(18, selection.getInt("endLine"))

        val whole = ReviewCommentMetadata.noteJson(note("src/a.kt", null, null, "x"))
        assertFalse(whole.has("selection"))
    }

    @Test
    fun theMetadataCarriesDisplayTextAlwaysAndCommentsOnlyWhenThereAreNotes() {
        val plain = ReviewCommentMetadata.metadata("just a question")
        assertEquals("just a question", plain.getString("displayText"))
        assertFalse(plain.has("comments"))

        val withNotes = ReviewCommentMetadata.metadata(
            "just a question\n" + ReviewCommentPrompt.formatNote("src/a.kt", 3, 3, "why?"),
        )
        // The user's own words survive as `displayText`…
        assertEquals("just a question", withNotes.getString("displayText"))
        // …and the note rides structured beside them.
        val comments = withNotes.getJSONArray("comments")
        assertEquals(1, comments.length())
        assertEquals("src/a.kt", comments.getJSONObject(0).getString("path"))
        assertEquals("why?", comments.getJSONObject(0).getString("comment"))
    }

    @Test
    fun blankCommentsNeverReachTheArray() {
        val json = ReviewCommentMetadata.notesJson(listOf(note("a.kt", 1, 1, "  ")))
        assertEquals(0, json.length())
    }

    // ------------------------------------------------------------ in (render)

    @Test
    fun aNoteSentenceRoundTripsThroughFormatAndParse() {
        val line = ReviewCommentPrompt.formatNote("src/a.kt", 3, 9, "extract this")
        val parsed = ReviewCommentMetadata.parseNote(line)
        assertEquals("src/a.kt", parsed?.path)
        assertEquals(3, parsed?.startLine)
        assertEquals(9, parsed?.endLine)
        assertEquals("extract this", parsed?.comment)
        assertEquals(":3-9", parsed?.lineSuffix)

        val single = ReviewCommentMetadata.parseNote(
            ReviewCommentPrompt.formatNote("a.kt", 4, 4, "x"),
        )
        assertEquals(":4", single?.lineSuffix)

        val whole = ReviewCommentMetadata.parseNote(
            ReviewCommentPrompt.formatNote("a.kt", null, null, "x"),
        )
        assertEquals("", whole?.lineSuffix)
    }

    @Test
    fun thePresentationSplitsTheBodyIntoWordsAndComments() {
        val text = listOf(
            "please look at this",
            "",
            ReviewCommentPrompt.formatNote("src/a.kt", 3, 3, "why?"),
            ReviewCommentPrompt.formatNote("src/b.kt", 10, 14, "and this"),
        ).joinToString("\n")
        val presentation = ReviewCommentMetadata.presentation(text)
        assertEquals("please look at this\n", presentation.displayText)
        assertEquals(listOf("src/a.kt", "src/b.kt"), presentation.notes.map { it.path })
        assertTrue(presentation.hasNotes)

        // Two notes on one text do NOT swallow each other (upstream's
        // whole-text regex would; see the class note).
        assertEquals("and this", presentation.notes[1].comment)
    }

    @Test
    fun aBodyWithoutNotesIsReturnedUntouched() {
        val text = "The user made the following comment regarding something else"
        val presentation = ReviewCommentMetadata.presentation(text)
        assertEquals(text, presentation.displayText)
        assertFalse(presentation.hasNotes)
    }

    @Test
    fun metadataCommentsReadBackIntoNotes() {
        val json = org.json.JSONArray(
            """
            [{"path":"src/a.kt","comment":"why?","selection":{"startLine":3,"startChar":0,"endLine":7,"endChar":0},"origin":"review"},
             {"path":null,"comment":"dropped"},
             {"path":"src/b.kt","comment":"no selection"}]
            """.trimIndent(),
        )
        val notes = ReviewCommentMetadata.notesFromJson(json)
        assertEquals(2, notes.size)
        assertEquals(3, notes[0].startLine)
        assertEquals(7, notes[0].endLine)
        assertNull(notes[1].startLine)
    }

    @Test
    fun aJsonNullPathOrCommentIsNeverTheLiteralString() {
        val row = JSONObject("""{"path":null,"comment":null}""")
        assertNull(ReviewCommentMetadata.noteFromJson(row))
    }

    // ------------------------------------------------------------ card facts

    @Test
    fun aStoreCommentConvertsToTheCardNote() {
        val comment = ReviewLineComment(
            id = "c1",
            file = "src/a.kt",
            startLine = 5,
            endLine = 5,
            comment = "hmm",
            timeMs = 1L,
        )
        val note = ReviewCommentMetadata.note(comment)
        assertEquals("src/a.kt", note.path)
        assertEquals(":5", note.lineSuffix)
        assertEquals("hmm", note.comment)
    }

    @Test
    fun thePendingRowPartsReadBackIntoNotes() {
        val parts = listOf(
            ChatStore.UiPart(id = "m:comment:0", type = TimelineComments.PART_TYPE, rawJson = null),
            ChatStore.UiPart(
                id = "m:comment:1",
                type = TimelineComments.PART_TYPE,
                rawJson = """{"path":"src/a.kt","comment":"why?","selection":{"startLine":2,"endLine":2}}""",
            ),
            ChatStore.UiPart(id = "m:text", type = "text", text = "hi"),
        )
        val notes = TimelineComments.ofParts(parts)
        assertEquals(1, notes.size)
        assertEquals("why?", notes[0].comment)
        assertEquals(":2", notes[0].lineSuffix)
    }

    @Test
    fun theCardFilenameIsTruncatedKeepingItsExtension() {
        // `util/path.ts:15-23` `getFilenameTruncated(path, 14)`.
        assertEquals("App.kt", TimelineComments.filenameTruncated("src/ui/App.kt"))
        assertEquals("App.kt", TimelineComments.filenameTruncated("src\\ui\\App.kt"))
        assertEquals("averylongn….kt", TimelineComments.filenameTruncated("averylongnamex.kt"))
        assertEquals("abcdefghijklm…", TimelineComments.filenameTruncated("abcdefghijklmnop"))
    }

    @Test
    fun aCommentOnlyMessageKeepsItsNotesAndLosesItsBody() {
        val text = ReviewCommentPrompt.formatNote("src/a.kt", 1, 1, "x")
        val presentation = ReviewCommentMetadata.presentation(text)
        // The bubble body is empty (the renderer then shows the cards alone,
        // `message-content.tsx:288-297`), and the note is the whole content.
        assertEquals("", presentation.displayText)
        assertEquals(1, presentation.notes.size)
        assertEquals("x", presentation.notes[0].comment)
    }
}
