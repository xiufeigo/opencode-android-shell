package com.xiufeigo.opencodeshell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/global/event` envelope handling (research/04 §2.2): frames arrive as
 * `{directory, payload:{id,type,properties}}` and must be unwrapped before the
 * regular v1 parsing, then gated so foreign sessions never touch the tracked
 * screen's state.
 */
class GlobalEventTest {

    @Test
    fun `unwrapGlobal returns inner payload of a global frame`() {
        val frame = JSONObject(
            """{"directory":"C:\\proj","payload":{"id":"e1","type":"message.updated","properties":{"info":{"sessionID":"ses_a"}}}}""",
        )
        val inner = ChatStore.unwrapGlobal(frame)
        assertEquals("message.updated", inner?.optString("type"))
        assertEquals("ses_a", inner?.optJSONObject("properties")?.optJSONObject("info")?.optString("sessionID"))
    }

    @Test
    fun `unwrapGlobal passes on instance-shaped frames`() {
        val frame = JSONObject("""{"id":"e1","type":"session.status","properties":{"sessionID":"ses_a","status":{"type":"busy"}}}""")
        assertNull(ChatStore.unwrapGlobal(frame))
    }

    @Test
    fun `unwrapGlobal ignores payload without type`() {
        val frame = JSONObject("""{"directory":"C:\\proj","payload":{"id":"e1"}}""")
        assertNull(ChatStore.unwrapGlobal(frame))
    }

    @Test
    fun `eventSessionId reads properties sessionID`() {
        val data = JSONObject("""{"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"idle"}}}""")
        assertEquals("ses_1", ChatStore.eventSessionId(data))
    }

    @Test
    fun `eventSessionId reads message info sessionID`() {
        val data = JSONObject("""{"type":"message.updated","properties":{"info":{"id":"m1","sessionID":"ses_2","role":"assistant"}}}""")
        assertEquals("ses_2", ChatStore.eventSessionId(data))
    }

    @Test
    fun `eventSessionId reads part sessionID`() {
        val data = JSONObject("""{"type":"message.part.updated","properties":{"part":{"id":"p1","sessionID":"ses_3","messageID":"m1","type":"text"}}}""")
        assertEquals("ses_3", ChatStore.eventSessionId(data))
    }

    @Test
    fun `eventSessionId null for session-less frames`() {
        assertNull(ChatStore.eventSessionId(JSONObject("""{"type":"server.heartbeat","properties":{}}""")))
    }

    @Test
    fun `acceptsSession drops foreign sessions when tracking`() {
        val foreign = JSONObject("""{"type":"message.part.delta","properties":{"sessionID":"ses_other","messageID":"m","partID":"p","delta":"x"}}""")
        assertFalse(ChatStore.acceptsSession("ses_mine", foreign))
    }

    @Test
    fun `acceptsSession keeps tracked and unrouted events`() {
        val mine = JSONObject("""{"type":"message.part.delta","properties":{"sessionID":"ses_mine","messageID":"m","partID":"p","delta":"x"}}""")
        assertTrue(ChatStore.acceptsSession("ses_mine", mine))
        assertTrue(ChatStore.acceptsSession("ses_mine", JSONObject("""{"type":"server.heartbeat","properties":{}}""")))
        // Nothing tracked yet (legacy shell) accepts everything.
        assertTrue(ChatStore.acceptsSession(null, mine))
    }

    @Test
    fun `parseEvent consumes an unwrapped global payload`() {
        val frame = JSONObject(
            """{"directory":"D:\\w","payload":{"id":"e9","type":"session.status","properties":{"sessionID":"ses_g","status":{"type":"busy"}}}}""",
        )
        val inner = ChatStore.unwrapGlobal(frame) ?: error("global frame must unwrap")
        val event = ChatStore.parseEvent(inner.optString("type"), inner)
        assertTrue(event is ChatStore.Event.RunStatusChanged)
        assertEquals(ChatStore.RunStatus.RUNNING, (event as ChatStore.Event.RunStatusChanged).status)
    }
}
