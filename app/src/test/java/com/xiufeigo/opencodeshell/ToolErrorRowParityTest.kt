package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineGrouping
import com.xiufeigo.opencodeshell.ui.session.timeline.ToolPresentation
import com.xiufeigo.opencodeshell.ui.session.timeline.ToolRendererKind
import com.xiufeigo.opencodeshell.ui.session.timeline.toolRendererKind
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2EventAdapter
import com.xiufeigo.opencodeshell.v2.V2Transport
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failed-tool row: **one error card per failed call, and each card keeps the
 * command that tells it apart from its neighbour**.
 *
 * ## What the device evidence actually was (v37 tB, `31_err_dup.png`)
 *
 * Two pixel-identical 「Shell / 工作目录不存在」 rows in one turn, read as one
 * failure rendered twice. The server's own transcript for that session
 * (`ses_f1d462d2bffesi96YxBA1R4PV3`, `/home/xiufeigo/v37projA`) holds **two
 * distinct tool calls**, in two assistant steps:
 *
 * | step | message | content |
 * |---|---|---|
 * | 1 | `msg_0e2c53469…` | `tool{id:call_function_gzj1f64nfs4z_1, name:shell, state:{status:error, input:{command:"ls"}, error:"Working directory does not exist: …"}}` |
 * | 2 | `msg_0e2c53af0…` | `reasoning("The working directory doesn't exist. Let me check.")` + `tool{id:call_function_9ho179c73wsc_1, …, input:{command:"ls -la /home/xiufeigo/"}, error:"Working directory does not exist: …"}` |
 * | 3 | `msg_0e2c540b3…` | `reasoning` + `tool{… input:{command:"ls -la /home/xiufeigo/", workdir:"/tmp/opencode"}, status:running}` — the one that succeeded |
 *
 * So the row *count* was right (two calls → two cards, and a second card must
 * never be deduped away — it is a second failure the user has to see). The rows
 * were identical because the **live** part had lost its `toolInput`: the v2
 * terminal frames (`session.tool.success` / `session.tool.failed`) are
 * `ToolBase + content/error` and carry **no** `input`
 * (`_upstream/packages/schema/src/session-event.ts:501-556`), while the durable
 * `ToolState` the snapshot reads does (`V2Transport.toolPart`). A whole-part
 * replace therefore blanked the input the `called` frame had set, and with it
 * the card's subtitle — the shell command (`toolErrorSubtitle`,
 * `tools/tool-renderer.tsx:1265-1267`). Two different failures rendered as two
 * identical rows; a reopen (snapshot) showed the commands again, so live and
 * snapshot disagreed about the same failure.
 *
 * These tests pin both halves: the row identity (one card per call, no
 * dedup) and the input parity (live frames and the durable snapshot produce the
 * same card, whichever order they arrive in).
 */
class ToolErrorRowParityTest {

    private val transport = V2Transport(V2Api("http://127.0.0.1:1/".toHttpUrl()))

    private var event = 0

    private fun live(state: ChatStore.ChatState, type: String, data: String): ChatStore.ChatState {
        event += 1
        val envelope = JSONObject("""{"id":"evt_$event","type":"$type","created":1790510970000,"data":$data}""")
        return V2EventAdapter.adapt(type, envelope).fold(state) { s, e -> ChatStore.reduce(s, e) }
    }

    /**
     * The live frames one v2 shell call publishes, in wire order (2.0.18):
     * `input.started` → `input.ended` → `called` → the terminal frame. Only
     * `input.started` names the tool and only `called` carries the parsed input
     * — the terminal frame carries the outcome alone.
     */
    private fun call(
        state: ChatStore.ChatState,
        messageID: String,
        callID: String,
        command: String,
        error: String? = null,
        workdir: String? = null,
    ): ChatStore.ChatState {
        val input = if (workdir == null) {
            """{"command":"$command"}"""
        } else {
            """{"command":"$command","workdir":"$workdir"}"""
        }
        var s = live(
            state,
            "session.tool.input.started",
            """{"sessionID":"ses_1","assistantMessageID":"$messageID","id":"$callID","name":"shell"}""",
        )
        s = live(
            s,
            "session.tool.input.ended",
            """{"sessionID":"ses_1","assistantMessageID":"$messageID","id":"$callID",""" +
                """"text":"${input.replace("\"", "\\\"")}"}""",
        )
        s = live(
            s,
            "session.tool.called",
            """{"sessionID":"ses_1","assistantMessageID":"$messageID","id":"$callID",""" +
                """"input":$input,"executed":true}""",
        )
        // `Tool.Failed` / `Tool.Success` carry the outcome only — no `input`,
        // no `name` (the frames the store must not let blank the card).
        return if (error == null) {
            live(
                s,
                "session.tool.success",
                """{"sessionID":"ses_1","assistantMessageID":"$messageID","id":"$callID",""" +
                    """"content":[{"type":"text","text":"code/"}],"executed":true}""",
            )
        } else {
            live(
                s,
                "session.tool.failed",
                """{"sessionID":"ses_1","assistantMessageID":"$messageID","id":"$callID",""" +
                    """"error":{"type":"unknown","message":"$error"},"executed":false}""",
            )
        }
    }

    private fun reasoning(state: ChatStore.ChatState, messageID: String, text: String): ChatStore.ChatState {
        var s = live(
            state,
            "session.reasoning.started",
            """{"sessionID":"ses_1","assistantMessageID":"$messageID","ordinal":0}""",
        )
        s = live(
            s,
            "session.reasoning.ended",
            """{"sessionID":"ses_1","assistantMessageID":"$messageID","ordinal":0,"text":"$text"}""",
        )
        return s
    }

    private fun step(state: ChatStore.ChatState, messageID: String, started: Long): ChatStore.ChatState =
        live(
            state,
            "session.step.started",
            """{"sessionID":"ses_1","assistantMessageID":"$messageID","started":$started}""",
        )

    /**
     * One durable `GET /api/session/{id}/message` assistant row as the server
     * wrote it, projected through the transport's own snapshot mapping.
     */
    private fun snapshot(vararg rows: String): ChatStore.ChatState {
        val messages = rows.map { row ->
            with(transport) { V2Api.Message.fromJson(JSONObject(row)).toUi() }!!
        }
        return ChatStore.reduce(ChatStore.ChatState(sessionID = "ses_1"), ChatStore.Event.Snapshot(messages))
    }

    private val failedStep1 = """
        {"id":"msg_1","type":"assistant","time":{"created":1790510970021},"agent":"build",
         "model":{"id":"space-bunny-free","providerID":"opencode-go"},"finish":"tool-calls",
         "content":[{"type":"tool","id":"call_function_gzj1f64nfs4z_1","name":"shell","executed":false,
           "state":{"status":"error","input":{"command":"ls"},
                    "error":{"type":"unknown","message":"Working directory does not exist: /home/xiufeigo/v37projA"}},
           "time":{"created":1790510971215,"ran":1790510971473,"completed":1790510971593}}]}
    """.trimIndent()

    private val failedStep2 = """
        {"id":"msg_2","type":"assistant","time":{"created":1790510971658},"agent":"build",
         "model":{"id":"space-bunny-free","providerID":"opencode-go"},"finish":"tool-calls",
         "content":[{"type":"reasoning","text":"The working directory doesn't exist. Let me check."},
           {"type":"tool","id":"call_function_9ho179c73wsc_1","name":"shell","executed":false,
            "state":{"status":"error","input":{"command":"ls -la /home/xiufeigo/"},
                     "error":{"type":"unknown","message":"Working directory does not exist: /home/xiufeigo/v37projA"}},
            "time":{"created":1790510973047,"ran":1790510973064,"completed":1790510973080}}]}
    """.trimIndent()

    private val okStep3 = """
        {"id":"msg_3","type":"assistant","time":{"created":1790510973131},"agent":"build",
         "model":{"id":"space-bunny-free","providerID":"opencode-go"},"finish":"tool-calls",
         "content":[{"type":"reasoning","text":"The workdir doesn't exist. I need to specify an existing workdir."},
           {"type":"tool","id":"call_function_kbfqfz1uoc0m_1","name":"shell","executed":false,
            "state":{"status":"running","input":{"command":"ls -la /home/xiufeigo/","workdir":"/tmp/opencode"}},
            "time":{"created":1790510974953,"ran":1790510974995}}]}
    """.trimIndent()

    // ------------------------------------------------------------- row identity

    private fun errorRows(state: ChatStore.ChatState): List<TimelineGrouping.Row.Part> = state.messages
        .flatMap { message -> TimelineGrouping.rowsFor(message.parts) }
        .filterIsInstance<TimelineGrouping.Row.Part>()
        .filter { toolRendererKind(it.part) == ToolRendererKind.ERROR }

    private fun errorParts(state: ChatStore.ChatState) = state.messages
        .flatMap { it.parts }
        .filter { toolRendererKind(it) == ToolRendererKind.ERROR }

    /** The command the card shows next to the title (`ToolPresentation.subtitle`). */
    private fun subtitles(state: ChatStore.ChatState) = errorParts(state).map { ToolPresentation.subtitle(it) }

    /** The whole v37 turn, built from the live frames the server published. */
    private fun liveTurn(): ChatStore.ChatState {
        var live = step(ChatStore.ChatState(sessionID = "ses_1"), "msg_1", 1790510970021)
        live = call(live, "msg_1", FIRST_CALL, "ls", ERROR)
        live = step(live, "msg_2", 1790510971658)
        live = reasoning(live, "msg_2", "The working directory doesn't exist. Let me check.")
        live = call(live, "msg_2", SECOND_CALL, "ls -la /home/xiufeigo/", ERROR)
        live = step(live, "msg_3", 1790510973131)
        live = reasoning(live, "msg_3", "The workdir doesn't exist. I need to specify an existing workdir.")
        return call(live, "msg_3", THIRD_CALL, "ls -la /home/xiufeigo/")
    }

    @Test
    fun oneFailedCallIsOneErrorRowInEveryArrivalOrder() {
        val live = call(step(ChatStore.ChatState(sessionID = "ses_1"), "msg_1", 1), "msg_1", FIRST_CALL, "ls", ERROR)
        val snap = snapshot(failedStep1)

        assertEquals(1, errorRows(live).size)
        assertEquals(1, errorRows(snap).size)
        // Live frames first, then the reconnect snapshot: same part id, so the
        // merge has to land on the one row.
        assertEquals(1, errorRows(ChatStore.reduce(live, ChatStore.Event.Snapshot(snap.messages))).size)
        // Snapshot first (the session was opened mid-turn), then the frames —
        // replayed one part at a time, as the stream delivers them.
        var after = snap
        for (message in live.messages) {
            for (part in message.parts) after = ChatStore.reduce(after, ChatStore.Event.PartUpdated(message.id, part))
        }
        assertEquals(1, errorRows(after).size)
        assertEquals(1, after.messages.single { it.id == "msg_1" }.parts.count { it.id == FIRST_CALL })
    }

    /**
     * The v37 transcript: two failed shell calls in two steps must stay **two**
     * cards — a second failure is real news, not a duplicate to fold away. The
     * four rows are the evidence screenshot's `E / T / E / S`, and the two cards
     * are told apart by their own command.
     */
    @Test
    fun twoFailedCallsStayTwoRowsAndKeepTheirOwnCommand() {
        val live = liveTurn()

        val rows = errorRows(live)
        assertEquals(2, rows.size)
        assertEquals(listOf(FIRST_CALL, SECOND_CALL), rows.map { it.part.id })

        // Row order of the turn: failed call, the reasoning between the two
        // failures, the second failure, then the group of the call that worked.
        val all = live.messages.flatMap { TimelineGrouping.rowsFor(it.parts) }
        assertEquals(4, all.size)
        assertEquals(FIRST_CALL, (all[0] as TimelineGrouping.Row.Part).part.id)
        assertTrue(all[1] is TimelineGrouping.Row.Context)
        assertEquals(SECOND_CALL, (all[2] as TimelineGrouping.Row.Part).part.id)
        assertTrue(all[3] is TimelineGrouping.Row.Context)

        assertEquals(listOf("ls", "ls -la /home/xiufeigo/"), subtitles(live))
        assertNotEquals(subtitles(live)[0], subtitles(live)[1])

        // The whole turn through both paths, in both orders: the cards stay the
        // same two, each still carrying its own command.
        val snap = snapshot(failedStep1, failedStep2, okStep3)
        assertEquals(2, errorRows(ChatStore.reduce(live, ChatStore.Event.Snapshot(snap.messages))).size)
        assertEquals(
            listOf("ls", "ls -la /home/xiufeigo/"),
            subtitles(ChatStore.reduce(snap, ChatStore.Event.Snapshot(live.messages))),
        )
    }

    // -------------------------------------------------------------- input parity

    /**
     * The live half of the fix: `session.tool.failed` / `session.tool.success`
     * carry no `input`, and must not blank the one `session.tool.called` set.
     */
    @Test
    fun terminalFramesKeepTheCommandTheCalledFrameSet() {
        val failed = call(step(ChatStore.ChatState(sessionID = "ses_1"), "msg_1", 1), "msg_1", FIRST_CALL, "ls", ERROR)
        val failedPart = failed.messages.single().parts.single { it.id == FIRST_CALL }
        assertEquals("error", failedPart.toolStatus)
        assertEquals("shell", failedPart.toolName)
        assertEquals("""{"command":"ls"}""", failedPart.toolInput)
        assertEquals("ls", ToolPresentation.subtitle(failedPart))

        val ok = call(step(ChatStore.ChatState(sessionID = "ses_1"), "msg_1", 1), "msg_1", THIRD_CALL, "pwd")
        val okPart = ok.messages.single().parts.single { it.id == THIRD_CALL }
        assertEquals("completed", okPart.toolStatus)
        assertEquals("""{"command":"pwd"}""", okPart.toolInput)
        assertEquals("pwd", ToolPresentation.subtitle(okPart))
    }

    /** The snapshot half: a card rebuilt from `GET …/message` shows the command too. */
    @Test
    fun theSnapshotShowsTheSameCommandAsTheLiveFrames() {
        val snap = snapshot(failedStep1)
        val part = snap.messages.single().parts.single()
        assertEquals("error", part.toolStatus)
        assertEquals("ls", ToolPresentation.subtitle(part))

        val live = call(step(ChatStore.ChatState(sessionID = "ses_1"), "msg_1", 1), "msg_1", FIRST_CALL, "ls", ERROR)
        assertEquals(
            ToolPresentation.subtitle(live.messages.single().parts.single { it.id == FIRST_CALL }),
            ToolPresentation.subtitle(part),
        )
    }

    /**
     * The store opened mid-turn (the live failed frame landed before the
     * snapshot): the snapshot may still fill the blank input — filling a blank
     * cannot roll a streamed part back, which is the one thing `incomingWins =
     * false` must never do.
     */
    @Test
    fun aSnapshotFillsABlankInputWithoutRollingTheStoredPartBack() {
        // A live part that never saw `called` (the connect raced the call).
        var s = live(
            ChatStore.ChatState(sessionID = "ses_1"),
            "session.tool.failed",
            """{"sessionID":"ses_1","assistantMessageID":"msg_1","id":"$FIRST_CALL",""" +
                """"error":{"type":"unknown","message":"$ERROR"}}""",
        )
        assertNull(s.messages.single().parts.single().toolInput)

        s = ChatStore.reduce(s, ChatStore.Event.Snapshot(snapshot(failedStep1).messages))
        val part = s.messages.single().parts.single { it.id == FIRST_CALL }
        assertEquals("""{"command":"ls"}""", part.toolInput)
        // …and the name the same snapshot carries, which the lone terminal
        // frame never set either.
        assertEquals("shell", part.toolName)
        assertEquals("ls", ToolPresentation.subtitle(part))
        // The streamed state itself stays untouched — only the blanks were filled.
        assertEquals("error", part.toolStatus)
    }

    /**
     * The pre-existing contract is intact: a non-blank stored field is never
     * overwritten by a (possibly stale) reconnect snapshot.
     */
    @Test
    fun aSnapshotNeverOverwritesAStreamedPart() {
        var s = live(
            ChatStore.ChatState(sessionID = "ses_1"),
            "session.tool.input.started",
            """{"sessionID":"ses_1","assistantMessageID":"msg_1","id":"$FIRST_CALL","name":"shell"}""",
        )
        s = live(
            s,
            "session.tool.called",
            """{"sessionID":"ses_1","assistantMessageID":"msg_1","id":"$FIRST_CALL",""" +
                """"input":{"command":"ls"},"executed":true}""",
        )
        s = live(
            s,
            "session.tool.failed",
            """{"sessionID":"ses_1","assistantMessageID":"msg_1","id":"$FIRST_CALL",""" +
                """"error":{"type":"unknown","message":"$ERROR"}}""",
        )
        val stored = s.messages.single().parts.single()
        assertEquals("""{"command":"ls"}""", stored.toolInput)

        // A stale snapshot replaying the same call with a different command.
        val stale = snapshot(failedStep1.replace("""{"command":"ls"}""", """{"command":"stale"}"""))
        s = ChatStore.reduce(s, ChatStore.Event.Snapshot(stale.messages))
        val part = s.messages.single().parts.single { it.id == FIRST_CALL }
        assertEquals("""{"command":"ls"}""", part.toolInput)
        assertEquals("ls", ToolPresentation.subtitle(part))
    }

    /** The successful call of the v37 turn: its group row keeps the command too. */
    @Test
    fun theSuccessfulCallOfTheTurnAlsoKeepsItsCommand() {
        var live = step(ChatStore.ChatState(sessionID = "ses_1"), "msg_3", 1790510973131)
        live = reasoning(live, "msg_3", "The workdir doesn't exist.")
        live = call(live, "msg_3", THIRD_CALL, "ls -la /home/xiufeigo/", workdir = "/tmp/opencode")

        val snap = snapshot(okStep3)
        val livePart = live.messages.single().parts.single { it.id == THIRD_CALL }
        val snapPart = snap.messages.single().parts.single { it.id == THIRD_CALL }
        // `org.json` object key order is not stable, so the two payloads are
        // compared by their values, not byte for byte.
        assertEquals("ls -la /home/xiufeigo/", command(snapPart.toolInput))
        assertEquals("/tmp/opencode", workdir(snapPart.toolInput))
        assertEquals("ls -la /home/xiufeigo/", command(livePart.toolInput))
        assertEquals("/tmp/opencode", workdir(livePart.toolInput))

        // The group row counts the tool once (the turn's third row in the
        // evidence screenshot) — its reasoning rides along in the same group.
        val rows = live.messages.flatMap { TimelineGrouping.rowsFor(it.parts) }
        val group = rows.filterIsInstance<TimelineGrouping.Row.Context>().single()
        assertEquals(1, group.parts.count { it.type == "tool" })
        assertEquals("Used 1 Shell", TimelineGrouping.label(group.parts).englishText)
    }

    private fun command(input: String?): String? = ToolPresentation.parseObject(input)?.optString("command")

    private fun workdir(input: String?): String? = ToolPresentation.parseObject(input)?.optString("workdir")

    private companion object {
        const val ERROR = "Working directory does not exist: /home/xiufeigo/v37projA"

        /** The three shell calls of the v37 turn, by the server's own ids. */
        const val FIRST_CALL = "call_function_gzj1f64nfs4z_1"
        const val SECOND_CALL = "call_function_9ho179c73wsc_1"
        const val THIRD_CALL = "call_function_kbfqfz1uoc0m_1"
    }
}
