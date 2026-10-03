package com.xiufeigo.opencodeshell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 frame routing: session ownership of an event payload (`data.sessionID`,
 * else the durable aggregate — `session-event.ts` `Base`, `event.ts:27`) and
 * the gate that keeps a foreign session from touching the tracked screen's
 * state. The v1 `/global/event` `{directory, payload:{…, properties}}` envelope
 * is gone with `parseEvent`/`unwrapGlobal`.
 */
class GlobalEventTest {

    @Test
    fun `eventSessionId reads data sessionID`() {
        val data = JSONObject("""{"id":"evt_1","type":"session.status","data":{"sessionID":"ses_1","status":{"type":"idle"}}}""")
        assertEquals("ses_1", ChatStore.eventSessionId(data))
    }

    @Test
    fun `eventSessionId falls back to the durable aggregate`() {
        val data = JSONObject(
            """{"id":"evt_2","type":"session.text.ended","data":{"assistantMessageID":"msg_1"},
               "durable":{"aggregateID":"ses_agg","seq":7,"version":1}}""",
        )
        assertEquals("ses_agg", ChatStore.eventSessionId(data))
    }

    @Test
    fun `eventSessionId null for session-less frames`() {
        assertNull(ChatStore.eventSessionId(JSONObject("""{"id":"evt_3","type":"server.connected","data":{}}""")))
    }

    @Test
    fun `acceptsSession drops foreign sessions when tracking`() {
        val foreign = JSONObject("""{"id":"evt_4","type":"session.text.delta","data":{"sessionID":"ses_other","delta":"x"}}""")
        assertFalse(ChatStore.acceptsSession("ses_mine", foreign))
    }

    @Test
    fun `acceptsSession keeps tracked and unrouted events`() {
        val mine = JSONObject("""{"id":"evt_5","type":"session.text.delta","data":{"sessionID":"ses_mine","delta":"x"}}""")
        assertTrue(ChatStore.acceptsSession("ses_mine", mine))
        assertTrue(ChatStore.acceptsSession("ses_mine", JSONObject("""{"id":"evt_6","type":"server.connected","data":{}}""")))
        // Nothing tracked yet accepts everything.
        assertTrue(ChatStore.acceptsSession(null, mine))
    }

    @Test
    fun `a routed v2 frame adapts and keeps its session`() {
        val frame = JSONObject(
            """{"id":"evt_7","type":"session.status","data":{"sessionID":"ses_g","status":{"type":"busy"}}}""",
        )
        val event = com.xiufeigo.opencodeshell.v2.V2EventAdapter.adapt("session.status", frame).single()
        assertTrue(event is ChatStore.Event.RunStatusChanged)
        assertEquals(ChatStore.RunStatus.RUNNING, (event as ChatStore.Event.RunStatusChanged).status)
        assertEquals("ses_g", ChatStore.eventSessionId(frame))
    }
}
