package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.context.SessionInstructionsModel
import com.xiufeigo.opencodeshell.v2.InstructionEntry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the session instructions editor: the
 * `instruction-entry.ts:10` key grammar, the arbitrary-JSON value
 * text/parse pair and the list projection.
 */
class SessionInstructionsModelTest {

    // --------------------------------------------------------------- keys

    @Test
    fun keyGrammarIsLowercaseDigitsDotDashUnderscore() {
        assertTrue(SessionInstructionsModel.isValidKey("api"))
        assertTrue(SessionInstructionsModel.isValidKey("a1.b_c-d"))
        assertTrue(SessionInstructionsModel.isValidKey("0leading"))
        assertFalse(SessionInstructionsModel.isValidKey(""))
        assertFalse(SessionInstructionsModel.isValidKey("-leading"))
        assertFalse(SessionInstructionsModel.isValidKey("Upper"))
        assertFalse(SessionInstructionsModel.isValidKey("has space"))
        assertFalse(SessionInstructionsModel.isValidKey("has/slash"))
        assertFalse(SessionInstructionsModel.isValidKey("has:colon"))
    }

    @Test
    fun canSaveTrimsAroundTheKeyButDoesNotRepairIt() {
        assertTrue(SessionInstructionsModel.canSave("  tone  "))
        assertFalse(SessionInstructionsModel.canSave("  Bad Key  "))
    }

    // -------------------------------------------------------------- values

    @Test
    fun valueTextShowsStringsVerbatimAndContainersPrettyPrinted() {
        assertEquals("hello", SessionInstructionsModel.valueText("hello"))
        assertEquals("null", SessionInstructionsModel.valueText(null))
        assertEquals("12", SessionInstructionsModel.valueText(12))
        assertEquals("true", SessionInstructionsModel.valueText(true))
        // Containers go through `toString(SessionExport.INDENT)` — the same
        // two-space pretty-print `SessionExport.serialize` pins. Assert the
        // format the way that test does (multi-line, 2-space steps) rather
        // than a byte-exact body: `org.json` keeps single-entry containers
        // compact and orders keys by hash.
        val obj = SessionInstructionsModel.valueText(JSONObject().put("a", 1).put("b", 2))
        assertTrue("expected multi-line object, got: $obj", obj.lines().size > 1)
        assertTrue("2-space indent missing: $obj", obj.lines().drop(1).any { it.startsWith("  ") })
        assertTrue("4-space indent leaked: $obj", obj.lines().drop(1).none { it.startsWith("    ") })
        val arr = SessionInstructionsModel.valueText(JSONArray().put(1).put(2))
        assertTrue("expected multi-line array, got: $arr", arr.lines().size > 1)
    }

    @Test
    fun parseValueReadsJsonAndLeavesPlainWordsAsTypedText() {
        assertNull(SessionInstructionsModel.parseValue("null"))
        // An empty editor field is the empty string value, not JSON null.
        assertEquals("", SessionInstructionsModel.parseValue(""))
        assertEquals("hi", SessionInstructionsModel.parseValue("\"hi\""))
        assertEquals("hi there", SessionInstructionsModel.parseValue("hi there"))
        assertEquals(true, SessionInstructionsModel.parseValue("true"))
        assertEquals(12L, SessionInstructionsModel.parseValue("12"))
        assertEquals(12.5, SessionInstructionsModel.parseValue("12.5"))
        val obj = SessionInstructionsModel.parseValue("{\"a\":1}")
        assertTrue(obj is JSONObject)
        assertEquals(1, (obj as JSONObject).getInt("a"))
        assertTrue(SessionInstructionsModel.parseValue("[1]") is JSONArray)
    }

    @Test
    fun roundTripKeepsPlainSentencesAndJsonValues() {
        val sentence = "Be terse. Prefer Kotlin."
        assertEquals(sentence, SessionInstructionsModel.valueText(SessionInstructionsModel.parseValue(sentence)))
        val json = "{\"style\":\"concise\"}"
        val parsed = SessionInstructionsModel.parseValue(json)
        assertTrue(parsed is JSONObject)
    }

    // ---------------------------------------------------------------- rows

    @Test
    fun rowsKeepServerOrderAndExposeTheEditorText() {
        val rows = SessionInstructionsModel.rows(
            listOf(
                InstructionEntry(key = "tone", value = "terse"),
                InstructionEntry(key = "limits", value = JSONObject().put("files", 3)),
            ),
        )
        assertEquals(listOf("tone", "limits"), rows.map { it.key })
        assertEquals("terse", rows[0].text)
        // The container row's editor text parses back to the same value.
        val reparsed = SessionInstructionsModel.parseValue(rows[1].text)
        assertEquals(3, (reparsed as JSONObject).getInt("files"))
    }
}
