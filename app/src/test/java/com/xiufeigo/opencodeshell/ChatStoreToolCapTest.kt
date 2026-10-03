package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.ToolPresentation
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ChatStore.capToolText` pins: an over-cap tool payload must stay
 * displayable — JSON `state.input` still parses after the cap (subtitle,
 * diff, shell command and patch text all come from `parseObject`), surrogate
 * pairs are never split, and `state.error` is capped like the other fields.
 */
class ChatStoreToolCapTest {

    private fun toolPart(
        input: JSONObject? = null,
        output: String? = null,
        error: String? = null,
    ): JSONObject {
        val state = JSONObject()
        if (input != null) state.put("input", input)
        if (output != null) state.put("output", output)
        if (error != null) state.put("error", error)
        return JSONObject("""{"id":"t1","type":"tool","tool":"write"}""").put("state", state)
    }

    @Test
    fun overCapJsonInputShrinksByStringValueAndStaysParseable() {
        val input = JSONObject()
            .put("filePath", "/w/notes.md")
            .put("content", "x".repeat(40_000))
        val part = ChatStore.parsePart(toolPart(input = input))

        val capped = part.toolInput!!
        assertTrue("capped to the budget", capped.length <= 16_000)
        val parsed = ToolPresentation.parseObject(capped)
        assertNotNull("a hard-cut slice nulls parseObject and blanks the card", parsed)
        assertEquals("/w/notes.md", parsed!!.optString("filePath"))
        val content = parsed.optString("content")
        assertTrue(content.length < 40_000)
        assertTrue("truncated values are marked", content.endsWith("…"))
        assertFalse(Character.isHighSurrogate(content.last()))
    }

    @Test
    fun nestedJsonStringsShrinkToo() {
        val patch = "@@ -1 +1 @@\n" + "-old\n+new\n".repeat(4_000)
        val input = JSONObject().put("files", JSONArray().put(JSONObject().put("patch", patch)))
        val part = ChatStore.parsePart(toolPart(input = input))

        val parsed = ToolPresentation.parseObject(part.toolInput!!)
        assertNotNull(parsed)
        val shrunk = parsed!!.getJSONArray("files").getJSONObject(0).optString("patch")
        assertTrue(shrunk.length < patch.length)
        assertTrue(shrunk.endsWith("…"))
        assertTrue(part.toolInput!!.length <= 16_000)
    }

    @Test
    fun capNeverSplitsASurrogatePair() {
        // The 16000 cut lands exactly on the emoji's high surrogate.
        val part = ChatStore.parsePart(toolPart(output = "x".repeat(15_999) + "😀tail"))
        assertEquals("x".repeat(15_999) + "\n… (truncated)", part.toolOutput)
    }

    @Test
    fun toolErrorIsCappedLikeTheOtherToolFields() {
        val part = ChatStore.parsePart(toolPart(error = "e".repeat(50_000)))
        val capped = part.toolError!!
        assertEquals(16_000 + "\n… (truncated)".length, capped.length)
        assertTrue(capped.startsWith("e".repeat(100)))
        assertTrue(capped.endsWith("(truncated)"))
    }
}
