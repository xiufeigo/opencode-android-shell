package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.export.SessionExport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A8 session export, mirroring the upstream test file
 * (`_upstream/packages/app/src/session/commands/export.test.ts`) so the two
 * implementations agree case by case.
 */
class SessionExportTest {

    // -------------------------------------------------------------- filename

    @Test
    fun filenameComesFromTheTitleLikeUpstream() {
        // export.test.ts:8-12
        assertEquals(
            "clone-pr-in-worktree-from-fork.json",
            SessionExport.filename(id = "ses_123", title = "Clone PR in worktree from fork"),
        )
    }

    @Test
    fun filenameFallsBackToSlugThenId() {
        // export.test.ts:14-20
        assertEquals("my-session-slug.json", SessionExport.filename("ses_123", slug = "my-session-slug"))
        assertEquals("ses_123.json", SessionExport.filename("ses_123"))
    }

    @Test
    fun blankTitleAndSlugAreIgnoredNotNormalizedIntoNothing() {
        assertEquals("ses_9.json", SessionExport.filename("ses_9", title = "   ", slug = ""))
    }

    @Test
    fun normalizationCollapsesRunsAndTrimsEdges() {
        assertEquals("a-b-c.json", SessionExport.filename("x", title = "A!!!B   C"))
        assertEquals("hello-world.json", SessionExport.filename("x", title = "  --Hello World--  "))
        // Non-ASCII titles collapse to dashes, so the id keeps the name usable.
        assertEquals("ses_42.json", SessionExport.filename("ses_42", title = "中文标题"))
        // Only runs of *disallowed* characters fold to one `-`; dashes the user
        // typed are allowed characters and stay. " - " is therefore three dashes
        // (space→`-`, the literal `-`, space→`-`), exactly what upstream's
        // `replace(/[^a-z0-9_-]+/gi, "-")` produces.
        assertEquals("keep_this---and-this.json", SessionExport.filename("x", title = "keep_this - and-this"))
    }

    // ----------------------------------------------------------------- pages

    @Test
    fun pagesFlattenInOrderAndCursorsAreNotPartOfThePayload() {
        // export.test.ts:24-48 (the cursor lives outside `data` and is dropped)
        val first = JSONObject("""{"id":"msg_1"}""")
        val second = JSONObject("""{"id":"msg_2"}""")
        val joined = SessionExport.joinPages(listOf(listOf(first), listOf(second)))
        assertEquals(listOf("msg_1", "msg_2"), joined.map { it.optString("id") })
    }

    // ------------------------------------------------------------- serialize

    @Test
    fun serializationUsesTwoSpaceIndentLikeUpstream() {
        // export.test.ts:60-72 expects exactly "{\n  \"id\": \"ses_1\"\n}"
        val json = SessionExport.serialize(JSONObject("""{"id":"ses_1"}"""), emptyList())
        val lines = json.lines()
        assertTrue("expected multi-line output, got: $json", lines.size > 1)
        assertTrue("2-space indent missing: $json", lines.drop(1).any { it.startsWith("  ") })
        assertFalse("4-space indent leaked: $json", lines.drop(1).any { it.startsWith("    ") })
    }

    @Test
    fun payloadHasInfoAndMessagesOnlyAndKeepsMessageOrder() {
        val info = JSONObject("""{"id":"ses_1","title":"Test Session"}""")
        val messages = listOf(JSONObject("""{"id":"msg_1"}"""), JSONObject("""{"id":"msg_2"}"""))
        val parsed = JSONObject(SessionExport.serialize(SessionExport.Frame(info, messages)))

        assertEquals(setOf("info", "messages"), parsed.keys().asSequence().toSet())
        assertEquals("ses_1", parsed.getJSONObject("info").optString("id"))
        val array = parsed.getJSONArray("messages")
        assertEquals(2, array.length())
        assertEquals("msg_1", array.getJSONObject(0).optString("id"))
        assertEquals("msg_2", array.getJSONObject(1).optString("id"))
        // No cursor key anywhere in the root (export.test.ts:43).
        assertFalse(parsed.has("cursor"))
    }

    @Test
    fun emptySessionStillExportsAWellFormedPayload() {
        val parsed = JSONObject(SessionExport.serialize(JSONObject("""{"id":"ses_0"}"""), emptyList()))
        assertEquals(0, parsed.getJSONArray("messages").length())
    }

    // -------------------------------------------------------------- adapter

    @Test
    fun adapterKeepsTheServersRawPartPayloadAndRebuildsInfo() {
        val part = OpenCodeClient.Part.fromJson(
            JSONObject("""{"id":"prt_1","type":"text","text":"hi","extra":{"kept":true}}"""),
        )
        val message = OpenCodeClient.Message(
            id = "msg_1",
            sessionID = "ses_1",
            role = "assistant",
            timeCreated = 1234L,
            parts = listOf(part),
            tokens = 42L,
            providerID = "openai",
            modelID = "gpt-5.6-sol-fast",
            agent = "build",
            timeCompleted = 1300L,
        )
        val frame = SessionExport.frameFrom(JSONObject("""{"id":"ses_1","title":"T"}"""), listOf(message))
        val row = JSONObject(SessionExport.serialize(frame)).getJSONArray("messages").getJSONObject(0)

        val info = row.getJSONObject("info")
        assertEquals("msg_1", info.optString("id"))
        assertEquals("assistant", info.optString("role"))
        assertEquals(1234L, info.getJSONObject("time").optLong("created"))
        assertEquals(1300L, info.getJSONObject("time").optLong("completed"))
        assertEquals(42L, info.getJSONObject("tokens").optLong("total"))
        assertEquals("openai", info.optString("providerID"))
        assertEquals("build", info.optString("agent"))

        // Parts come from `Part.raw`, so server-only fields survive untouched.
        val exportedPart = row.getJSONArray("parts").getJSONObject(0)
        assertEquals("prt_1", exportedPart.optString("id"))
        assertTrue("raw payload must keep unknown fields", exportedPart.getJSONObject("extra").optBoolean("kept"))
    }

    @Test
    fun adapterOmitsAbsentOptionalFieldsInsteadOfWritingNulls() {
        val message = OpenCodeClient.Message(
            id = "msg_2",
            sessionID = "ses_1",
            role = "user",
            timeCreated = 1L,
            parts = emptyList(),
        )
        val row = SessionExport.messageJson(message)
        val info = row.getJSONObject("info")
        assertFalse(info.has("tokens"))
        assertFalse(info.has("providerID"))
        assertFalse(info.has("agent"))
        assertFalse(info.getJSONObject("time").has("completed"))
        assertEquals(0, row.getJSONArray("parts").length())
    }
}
