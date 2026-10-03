package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2EventAdapter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The v2 frame router's session gate ([acceptsV2SessionFrame]), pinned on the
 * JVM at the level it actually decides: the **real** [V2EventAdapter] turns a
 * wire frame into the store events it would produce, and the **real**
 * [ChatStore] reduces them, so a cross-talk shows up as a changed
 * `ChatState.runStatus` rather than as an assertion about a literal.
 *
 * The bug: `acceptsV2Session` read `if (routed == null) return true`. The global
 * `/api/event` stream carries *every* session, and a session-scoped frame whose
 * owner cannot be recovered (`data.sessionID` absent, no durable aggregate, no
 * `form.sessionID`) therefore passed the gate — so another session's
 * unattributed `session.status` / `session.idle` settled the tracked session's
 * RUNNING turn as IDLE. The composer went idle, the next prompt was steered into
 * a turn that was still running, and nothing on screen said why.
 *
 * What must still work: a genuinely global frame is a
 * `ChatStore.Event.ServerSignal`, which `onV2Frame` consumes *before* the gate,
 * so tightening it cannot cost the directory / catalog / file-tree updates.
 */
class V2SessionGateTest {

    private val tracked = "ses_tracked"
    private val foreign = "ses_other"

    /** The tracked session's turn is running; every frame below lands on it. */
    private val running = ChatStore.ChatState(runStatus = ChatStore.RunStatus.RUNNING)

    /**
     * `ChatViewModel.onV2Frame`'s gate, re-run over the real adapter + store:
     * a frame only reaches `reduce` when the gate lets it through.
     */
    private fun ChatStore.ChatState.read(frame: String): ChatStore.ChatState {
        val envelope = JSONObject(frame)
        val routed = V2EventAdapter.sessionID(envelope)
        if (!acceptsV2SessionFrame(tracked, routed)) return this
        var state = this
        for (event in V2EventAdapter.adapt(envelope.optString("type"), envelope)) {
            state = ChatStore.reduce(state, event)
        }
        return state
    }

    private fun statusFrame(sessionID: String?, status: String): String {
        val data = JSONObject().put("status", JSONObject().put("type", status))
        if (sessionID != null) data.put("sessionID", sessionID)
        return JSONObject()
            .put("id", "evt_$status")
            .put("created", 1)
            .put("type", "session.status")
            .put("data", data)
            .toString()
    }

    private fun idleFrame(sessionID: String?): String {
        val data = JSONObject()
        if (sessionID != null) data.put("sessionID", sessionID)
        return JSONObject()
            .put("id", "evt_idle")
            .put("created", 1)
            .put("type", "session.idle")
            .put("data", data)
            .toString()
    }

    // ------------------------------------------------------------- the gate

    @Test
    fun onlyTheTrackedSessionIsAccepted() {
        assertTrue(acceptsV2SessionFrame(tracked, tracked))
        assertFalse("another session's frame", acceptsV2SessionFrame(tracked, foreign))
    }

    @Test
    fun anUnattributedFrameIsNotAccepted() {
        // The whole fix: no owner means no claim on this session.
        assertFalse(
            "a session-less frame must not drive the tracked session",
            acceptsV2SessionFrame(tracked, null),
        )
    }

    @Test
    fun nothingIsAcceptedWhileNoSessionIsTracked() {
        // The frame handler only runs with a session open, but the gate must
        // answer "no" rather than "yes" for a pair that cannot be compared.
        assertFalse(acceptsV2SessionFrame(null, null))
        assertFalse(acceptsV2SessionFrame(null, tracked))
    }

    // ------------------------------------------------- the cross-talk itself

    @Test
    fun anUnattributedIdleFrameDoesNotSettleTheRunningTurn() {
        val after = running.read(idleFrame(sessionID = null))
        assertEquals(
            "a foreign session's idle frame must not stop this turn",
            ChatStore.RunStatus.RUNNING,
            after.runStatus,
        )
    }

    @Test
    fun anUnattributedStatusFrameDoesNotSettleTheRunningTurn() {
        assertEquals(ChatStore.RunStatus.RUNNING, running.read(statusFrame(null, "idle")).runStatus)
        // `busy` must not be read as this session starting work either.
        val alreadyIdle = ChatStore.ChatState(runStatus = ChatStore.RunStatus.IDLE)
        assertEquals(ChatStore.RunStatus.IDLE, alreadyIdle.read(statusFrame(null, "busy")).runStatus)
    }

    @Test
    fun anAttributedForeignFrameDoesNotSettleTheRunningTurn() {
        // The pre-existing half of the gate, kept: the regression this guards is
        // the `null` case, not the mismatch case.
        assertEquals(ChatStore.RunStatus.RUNNING, running.read(idleFrame(foreign)).runStatus)
        assertEquals(ChatStore.RunStatus.RUNNING, running.read(statusFrame(foreign, "idle")).runStatus)
    }

    @Test
    fun theTrackedSessionsOwnFramesStillDriveItsStatus() {
        assertEquals(ChatStore.RunStatus.IDLE, running.read(idleFrame(tracked)).runStatus)
        assertEquals(ChatStore.RunStatus.IDLE, running.read(statusFrame(tracked, "idle")).runStatus)
        assertEquals(
            ChatStore.RunStatus.RUNNING,
            ChatStore.ChatState().read(statusFrame(tracked, "busy")).runStatus,
        )
    }

    @Test
    fun theDurableAggregateAloneStillRoutes() {
        // A frame that only names its owner in `durable.aggregateID` is the
        // common v2 shape; it must keep working (this is the routing path the
        // session log replay depends on).
        val frame = """{"id":"evt_s","created":1,"type":"session.idle",""" +
            """"data":{},"durable":{"aggregateID":"$tracked","seq":9,"version":1}}"""
        assertEquals(ChatStore.RunStatus.IDLE, running.read(frame).runStatus)
    }

    @Test
    fun aGlobalFrameIsASignalAndNeverReachesTheGate() {
        // Why tightening the gate is safe: the frames with no session to name are
        // the global ones, and they adapt to `ServerSignal`, which `onV2Frame`
        // consumes before the gate is consulted.
        val frame = """{"id":"evt_fs","created":1,"type":"filesystem.changed","data":{"file":"a.ts"}}"""
        val events = V2EventAdapter.adapt("filesystem.changed", JSONObject(frame))
        assertTrue("the global frame is routed", events.isNotEmpty())
        assertTrue(
            "a global frame must not adapt to session state",
            events.all { it is ChatStore.Event.ServerSignal },
        )
    }

    // ------------------------------------------------- the wiring is the real one

    @Test
    fun theViewModelUsesThisGateAndNoLongerLetsSessionlessFramesThrough() {
        val vm = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        // Both call sites now ask the tested function …
        assertTrue(
            vm.contains(
                "private fun acceptsV2Session(currentID: String?, routed: String?): Boolean =\n" +
                    "        acceptsV2SessionFrame(currentID, routed)",
            ),
        )
        assertTrue(
            vm.contains("if (!acceptsV2Session(sessionID, routed)) continue"),
        )
        assertTrue(
            vm.contains(
                "if (eventType.startsWith(\"session.inbox.\") && acceptsV2Session(sessionID, routed)) refreshInbox()",
            ),
        )
        // … and the liveness stamp (the same cross-talk: a foreign turn keeping
        // this one "working") asks it too, instead of its own `routed == null`
        // relaxation.
        assertTrue(
            vm.contains(
                "if (eventType.startsWith(\"session.\") && acceptsV2SessionFrame(sessionID, routed)) {",
            ),
        )
        assertFalse(
            "no site may still admit an unattributed session frame",
            vm.contains("routed == null || routed == sessionID"),
        )
    }

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")
}
