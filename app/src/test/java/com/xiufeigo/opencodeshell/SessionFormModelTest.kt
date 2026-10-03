package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.dock.FormField
import com.xiufeigo.opencodeshell.ui.session.dock.SessionFormModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WAVE 2.4a: the v2 form dock's pure rules (`SessionFormModel`, transcribed
 * from `_upstream/packages/schema/src/form.ts` and `packages/app/src/session/
 * requests/session-question-dock.tsx`).
 *
 * Covered: the six field types (parse + defaults), the `when` linkage
 * (AND, unanswered ⇒ false, multiselect `eq` = includes), page visibility,
 * answer building (answered-only, single value vs array, inactive/hidden/
 * external dropped), numeric draft/default validation (finite, Long-range),
 * the custom-row toggle (single-select enter/exit, multi draft removal),
 * boolean defaults (explicit null / invalid ⇒ no default) and the
 * cancel-request wire shape.
 */
class SessionFormModelTest {

    private fun fields(vararg entries: String): List<FormField> =
        SessionFormModel.parseFormFields("[${entries.joinToString(",")}]")

    // ------------------------------------------------------------------ parse

    @Test
    fun parsesAllSixFieldTypes() {
        val parsed = fields(
            """{"key":"s","type":"string","title":"Name","description":"Your name","placeholder":"x","default":"y"}""",
            """{"key":"m","type":"multiselect","title":"Pick","options":[{"value":"a","label":"A","description":"first"}],"custom":false,"default":["a"]}""",
            """{"key":"n","type":"number","title":"N","default":1.5}""",
            """{"key":"i","type":"integer","title":"I"}""",
            """{"key":"b","type":"boolean","title":"B","default":true}""",
            """{"key":"e","type":"external","title":"Docs","description":"Read","url":"https://example.com"}""",
        )
        assertEquals(6, parsed.size)

        val text = parsed[0] as FormField.Text
        assertEquals("Name", text.title)
        assertEquals("Your name", text.description)
        assertEquals("x", text.placeholder)
        assertEquals("y", text.default)
        assertTrue(text.custom)

        val multi = parsed[1] as FormField.Multi
        assertEquals(listOf("a"), multi.default)
        assertFalse(multi.custom)
        assertEquals("A", multi.options.single().label)
        assertEquals("first", multi.options.single().description)

        val number = parsed[2] as FormField.NumberInput
        assertFalse(number.integer)
        assertEquals(1.5, number.default!!, 0.0)

        val integer = parsed[3] as FormField.NumberInput
        assertTrue(integer.integer)
        assertNull(integer.default)

        val boolean = parsed[4] as FormField.BooleanInput
        assertEquals(true, boolean.default)

        val external = parsed[5] as FormField.External
        assertEquals("https://example.com", external.url)
        assertEquals("Read", external.description)
    }

    @Test
    fun skipsMalformedEntriesButKeepsTheForm() {
        val parsed = fields(
            """{"key":"ok","type":"string"}""",
            """{"type":"string"}""",
            """{"key":"nope","type":"teleport"}""",
            """{"key":"nourl","type":"external"}""",
            "not an object",
        )
        assertEquals(1, parsed.size)
        assertEquals("ok", parsed.single().key)
    }

    @Test
    fun malformedArrayYieldsNoFields() {
        assertTrue(SessionFormModel.parseFormFields("not json").isEmpty())
        assertTrue(SessionFormModel.parseFormFields("").isEmpty())
    }

    @Test
    fun descriptionFallsBackToTitleLikeUpstream() {
        val text = fields("""{"key":"s","type":"string","title":"T"}""").single() as FormField.Text
        assertEquals("T", text.description)
    }

    // ------------------------------------------------------------------- when

    private fun whenForm(): List<FormField> = fields(
        """{"key":"mode","type":"string","options":[{"value":"a","label":"A"},{"value":"b","label":"B"}]}""",
        """{"key":"tags","type":"multiselect","options":[{"value":"x","label":"X"}]}""",
        """{"key":"detail","type":"string","when":[{"key":"mode","op":"eq","value":"a"}]}""",
        """{"key":"other","type":"string","when":[{"key":"mode","op":"neq","value":"a"}]}""",
        """{"key":"tagged","type":"string","when":[{"key":"tags","op":"eq","value":"x"}]}""",
        """{"key":"both","type":"string","when":[{"key":"mode","op":"eq","value":"a"},{"key":"tags","op":"eq","value":"x"}]}""",
    )

    @Test
    fun whenConditionsGateActivity() {
        val form = whenForm()
        val detail = form[2]
        val other = form[3]
        val tagged = form[4]
        val both = form[5]

        // Unanswered references are false for both ops: eq-off, neq-off.
        assertFalse(SessionFormModel.isFieldActive(detail, emptyMap()))
        assertFalse(SessionFormModel.isFieldActive(other, emptyMap()))

        assertTrue(SessionFormModel.isFieldActive(detail, mapOf("mode" to "a")))
        assertFalse(SessionFormModel.isFieldActive(other, mapOf("mode" to "a")))
        assertTrue(SessionFormModel.isFieldActive(other, mapOf("mode" to "b")))

        // Multiselect eq = "selection includes value".
        assertTrue(SessionFormModel.isFieldActive(tagged, mapOf("tags" to listOf("x", "y"))))
        assertFalse(SessionFormModel.isFieldActive(tagged, mapOf("tags" to listOf("y"))))

        // AND across conditions.
        assertTrue(
            SessionFormModel.isFieldActive(
                both,
                mapOf("mode" to "a", "tags" to listOf("x")),
            ),
        )
        assertFalse(
            SessionFormModel.isFieldActive(
                both,
                mapOf("mode" to "a", "tags" to listOf("y")),
            ),
        )
    }

    @Test
    fun hiddenFieldsNeverPage() {
        val form = fields(
            """{"key":"a","type":"string"}""",
            """{"key":"b","type":"string","hidden":true}""",
        )
        val pages = SessionFormModel.visiblePages(form, emptyMap())
        assertEquals(listOf("a"), pages.map { it.key })
    }

    // ------------------------------------------------------------------ answer

    @Test
    fun buildAnswerPostsAnsweredFieldsOnly() {
        val form = fields(
            """{"key":"s","type":"string"}""",
            """{"key":"m","type":"multiselect"}""",
            """{"key":"n","type":"number"}""",
            """{"key":"i","type":"integer"}""",
            """{"key":"b","type":"boolean"}""",
            """{"key":"e","type":"external","url":"https://example.com"}""",
            """{"key":"h","type":"string","hidden":true}""",
            """{"key":"w","type":"string","when":[{"key":"s","op":"eq","value":"z"}]}""",
            """{"key":"u","type":"string"}""",
        )
        val answer = SessionFormModel.buildAnswer(
            form,
            mapOf(
                "s" to "yes",
                "m" to listOf("a", "b"),
                "n" to 1.5,
                "i" to 2.0,
                "b" to true,
                "e" to "ignored",
                "h" to "stale-hidden",
                "w" to "stale-inactive",
            ),
        )
        // Single-select posts the single value, multiselect the array; the
        // integer-valued double posts as a Long; hidden/inactive/external and
        // the unanswered field never appear.
        assertEquals("yes", answer["s"])
        assertEquals(listOf("a", "b"), answer["m"])
        assertEquals(1.5, answer["n"])
        assertEquals(2L, answer["i"])
        assertEquals(true, answer["b"])
        assertFalse(answer.containsKey("e"))
        assertFalse(answer.containsKey("h"))
        assertFalse(answer.containsKey("w"))
        assertFalse(answer.containsKey("u"))
    }

    @Test
    fun buildAnswerSkipsBlankAndInvalidValues() {
        val form = fields(
            """{"key":"s","type":"string"}""",
            """{"key":"m","type":"multiselect"}""",
            """{"key":"n","type":"number"}""",
        )
        val answer = SessionFormModel.buildAnswer(
            form,
            mapOf("s" to "  ", "m" to emptyList<String>(), "n" to "abc"),
        )
        assertTrue(answer.isEmpty())
    }

    @Test
    fun pageAnsweredFlagsProgressSegments() {
        val text = FormField.Text(key = "s", title = "", description = "")
        val multi = FormField.Multi(key = "m", title = "", description = "")
        val external = FormField.External(key = "e", title = "", description = "", url = "https://x")
        assertFalse(SessionFormModel.pageAnswered(text, null))
        assertFalse(SessionFormModel.pageAnswered(text, "  "))
        assertTrue(SessionFormModel.pageAnswered(text, "v"))
        assertFalse(SessionFormModel.pageAnswered(multi, emptyList<String>()))
        assertTrue(SessionFormModel.pageAnswered(multi, listOf("a")))
        // External pages carry no answer and always read as done.
        assertTrue(SessionFormModel.pageAnswered(external, null))
    }

    // ------------------------------------------------------------ cancel wire

    @Test
    fun cancelRequestTargetsTheDocumentedRoute() {
        val request = SessionFormModel.formCancelRequest(
            baseUrl = "http://127.0.0.1:4095",
            sessionID = "ses_1",
            formID = "frm_1",
            username = "opencode",
            password = "opencode",
        )!!
        assertEquals("DELETE", request.method)
        assertEquals("/api/session/ses_1/form/frm_1", request.url.encodedPath)
        val header = request.header("Authorization")!!
        assertTrue(header.startsWith("Basic "))
        // `btoa("opencode:opencode")` — the upstream credential shape.
        assertEquals("Basic b3BlbmNvZGU6b3BlbmNvZGU=", header)
    }

    @Test
    fun cancelRequestOmitsAuthWithoutAPassword() {
        val request = SessionFormModel.formCancelRequest(
            baseUrl = "http://127.0.0.1:4095",
            sessionID = "ses_1",
            formID = "frm_1",
            username = null,
            password = null,
        )!!
        assertNull(request.header("Authorization"))
    }

    @Test
    fun cancelRequestIsNullForABadBaseUrl() {
        assertNull(
            SessionFormModel.formCancelRequest(
                baseUrl = ":::",
                sessionID = "ses_1",
                formID = "frm_1",
                username = null,
                password = null,
            ),
        )
    }

    // ------------------------------------------------------- numeric drafts

    @Test
    fun numberDraftRejectsNonFiniteInput() {
        // `JSONObject.put` throws on non-finite doubles — these must never
        // reach `values`, or the submit rolls back with no visible error.
        assertNull(SessionFormModel.parseNumberDraft("NaN", integer = false))
        assertNull(SessionFormModel.parseNumberDraft("Infinity", integer = false))
        assertNull(SessionFormModel.parseNumberDraft("-Infinity", integer = false))
        assertNull(SessionFormModel.parseNumberDraft("1e400", integer = false))
        assertNull(SessionFormModel.parseNumberDraft("abc", integer = false))
        assertEquals(1.5, SessionFormModel.parseNumberDraft(" 1.5 ", integer = false)!!, 0.0)
        assertEquals(1e30, SessionFormModel.parseNumberDraft("1e30", integer = false)!!, 0.0)
    }

    @Test
    fun integerDraftRejectsValuesBeyondLongRange() {
        assertNull(SessionFormModel.parseNumberDraft("1e30", integer = true))
        assertNull(SessionFormModel.parseNumberDraft("-1e30", integer = true))
        assertNull(SessionFormModel.parseNumberDraft("1e400", integer = true))
        assertEquals(2.0, SessionFormModel.parseNumberDraft("2", integer = true)!!, 0.0)
        // In-range fractions still parse (they post as Double, unchanged).
        assertEquals(1.5, SessionFormModel.parseNumberDraft("1.5", integer = true)!!, 0.0)
    }

    @Test
    fun buildAnswerNeverPostsNonFiniteOrSaturatingNumbers() {
        val form = fields(
            """{"key":"n","type":"number"}""",
            """{"key":"i","type":"integer"}""",
            """{"key":"g","type":"number"}""",
        )
        val answer = SessionFormModel.buildAnswer(
            form,
            mapOf(
                "n" to Double.NaN,
                "i" to 1e30,
                "g" to Double.NEGATIVE_INFINITY,
            ),
        )
        assertTrue(answer.isEmpty())

        // In-range integers still convert exactly — 2^63 - 1024 is the
        // largest integral double a `Long` can hold.
        val ok = SessionFormModel.buildAnswer(
            form,
            mapOf("n" to 0.5, "i" to 9223372036854774784.0),
        )
        assertEquals(0.5, ok["n"])
        assertEquals(9223372036854774784L, ok["i"])
    }

    @Test
    fun numberDefaultsRejectNonFiniteAndOutOfRangeIntegers() {
        val parsed = fields(
            """{"key":"inf","type":"number","default":1e400}""",
            """{"key":"nul","type":"number","default":null}""",
            """{"key":"big","type":"integer","default":1e30}""",
            """{"key":"ok","type":"integer","default":7}""",
        )
        assertNull((parsed[0] as FormField.NumberInput).default)
        assertNull((parsed[1] as FormField.NumberInput).default)
        assertNull((parsed[2] as FormField.NumberInput).default)
        assertEquals(7.0, (parsed[3] as FormField.NumberInput).default!!, 0.0)
    }

    // ------------------------------------------------------- custom toggle

    @Test
    fun singleCustomEnterReplacesTheOptionPick() {
        val entered = SessionFormModel.toggleCustom(
            key = "s",
            multi = false,
            customOn = emptySet(),
            customText = emptyMap(),
            values = mapOf("s" to "A"),
        )
        assertEquals(setOf("s"), entered.customOn)
        assertFalse(entered.values.containsKey("s"))

        // A retained draft becomes the answer so it matches the visible
        // input (upstream `customOpen` → `customUpdate(input(), true)`).
        val restored = SessionFormModel.toggleCustom(
            key = "s",
            multi = false,
            customOn = emptySet(),
            customText = mapOf("s" to "B"),
            values = mapOf("s" to "A"),
        )
        assertEquals(mapOf("s" to "B"), restored.values)
    }

    @Test
    fun singleCustomExitDropsTheDraftFromTheAnswer() {
        val closed = SessionFormModel.toggleCustom(
            key = "s",
            multi = false,
            customOn = setOf("s"),
            customText = mapOf("s" to "B"),
            values = mapOf("s" to "B"),
        )
        assertEquals(emptySet<String>(), closed.customOn)
        assertFalse(closed.values.containsKey("s"))

        // The reported trigger: with nothing left in `values`, a closed
        // custom row can no longer submit its draft invisibly.
        val form = fields("""{"key":"s","type":"string","options":[{"value":"A","label":"A"}]}""")
        assertTrue(SessionFormModel.buildAnswer(form, closed.values).isEmpty())
    }

    @Test
    fun multiCustomToggleKeepsPicksAndDropsOnlyTheDraftOnExit() {
        val closed = SessionFormModel.toggleCustom(
            key = "m",
            multi = true,
            customOn = setOf("m"),
            customText = mapOf("m" to "B"),
            values = mapOf("m" to listOf("X", "B")),
        )
        assertEquals(emptySet<String>(), closed.customOn)
        assertEquals(mapOf("m" to listOf("X")), closed.values)

        // Entering multi custom extends the picks — it never replaces them.
        val entered = SessionFormModel.toggleCustom(
            key = "m",
            multi = true,
            customOn = emptySet(),
            customText = mapOf("m" to "B"),
            values = mapOf("m" to listOf("X")),
        )
        assertEquals(setOf("m"), entered.customOn)
        assertEquals(mapOf("m" to listOf("X")), entered.values)
    }

    // ------------------------------------------------------ boolean default

    @Test
    fun booleanDefaultsTreatNullAndInvalidAsAbsent() {
        val parsed = fields(
            """{"key":"n","type":"boolean","default":null}""",
            """{"key":"y","type":"boolean","default":"yes"}""",
            """{"key":"f","type":"boolean","default":false}""",
            """{"key":"t","type":"boolean","default":"true"}""",
            """{"key":"a","type":"boolean"}""",
        )
        assertNull((parsed[0] as FormField.BooleanInput).default)
        assertNull((parsed[1] as FormField.BooleanInput).default)
        assertEquals(false, (parsed[2] as FormField.BooleanInput).default)
        assertEquals(true, (parsed[3] as FormField.BooleanInput).default)
        assertNull((parsed[4] as FormField.BooleanInput).default)
    }
}
