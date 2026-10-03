package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel
import com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel.Kind
import java.time.ZoneId
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SessionContextModel.project(activeMessages = …)`: the v2 active context
 * (`GET /api/session/{id}/context`, `ChatViewModel.activeContextRows`) owns the
 * `ctx()` half of the stats (provider / model / limit / token buckets / last
 * activity) so those rows track the header ring, while counts, the raw list
 * and the export stay on the full message list. `null` keeps the old
 * full-list source — v1 servers and pre-fetch state must not change.
 */
class SessionContextActiveMessagesTest {

    private val utc = ZoneId.of("UTC")
    private val en = Locale.US

    private fun session(): JSONObject = JSONObject()
        .put("id", "ses_1")
        .put("title", "Context work")
        .put("time", JSONObject().put("created", 1_735_689_600_000L))

    private fun assistant(id: String, input: Long, output: Long = 0L): JSONObject =
        JSONObject()
            .put("info", JSONObject()
                .put("id", id)
                .put("role", "assistant")
                .put("time", JSONObject().put("created", 1_735_689_600_000L))
                .put("tokens", JSONObject().put("input", input).put("output", output)))
            .put("parts", JSONArray())

    private fun user(id: String): JSONObject =
        JSONObject()
            .put("info", JSONObject()
                .put("id", id)
                .put("role", "user")
                .put("time", JSONObject().put("created", 1_735_689_600_000L)))
            .put("parts", JSONArray())

    private fun valueOf(snapshot: SessionContextModel.Snapshot, kind: Kind): String =
        snapshot.stats.first { it.kind == kind }.value

    @Test
    fun activeContextOwnsTheTokenRows() {
        val full = listOf(user("msg_u"), assistant("msg_full", input = 100_000))
        val active = listOf(user("msg_u2"), assistant("msg_act", input = 12_345))
        val snapshot = SessionContextModel.project(
            session = session(),
            messages = full,
            activeMessages = active,
        )
        assertEquals("12,345", valueOf(snapshot, Kind.TOTAL_TOKENS))
        assertEquals("12,345", valueOf(snapshot, Kind.INPUT_TOKENS))
        // Counts and the raw list stay on the full message list.
        assertEquals("2", valueOf(snapshot, Kind.MESSAGES))
        assertEquals(listOf("msg_u", "msg_full"), snapshot.messages.map { it.id })
    }

    @Test
    fun nullActiveMessagesKeepTheFullListSource() {
        val full = listOf(assistant("msg_full", input = 100_000))
        val snapshot = SessionContextModel.project(
            session = session(),
            messages = full,
            activeMessages = null,
        )
        assertEquals("100,000", valueOf(snapshot, Kind.TOTAL_TOKENS))
        assertEquals("1", valueOf(snapshot, Kind.MESSAGES))
    }

    @Test
    fun emptyActiveContextDashesTheTokenRowsInsteadOfFallingBack() {
        // Compaction left the active context empty: the ring shows an empty
        // track, so the tab must not resurrect a pre-compaction turn's tokens.
        val snapshot = SessionContextModel.project(
            session = session(),
            messages = listOf(assistant("msg_old", input = 100_000)),
            activeMessages = emptyList(),
        )
        assertEquals(SessionContextModel.DASH, valueOf(snapshot, Kind.TOTAL_TOKENS))
        assertEquals(SessionContextModel.DASH, valueOf(snapshot, Kind.INPUT_TOKENS))
    }

    @Test
    fun exportKeepsTheFullMessageList() {
        val full = listOf(user("msg_u"), assistant("msg_full", input = 1))
        val snapshot = SessionContextModel.project(
            session = session(),
            messages = full,
            activeMessages = listOf(assistant("msg_act", input = 2)),
        )
        assertEquals(2, snapshot.export?.messages?.size)
        assertTrue(snapshot.export!!.json.contains("msg_full"))
    }
}
