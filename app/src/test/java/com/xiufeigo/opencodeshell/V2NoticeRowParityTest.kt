package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.RawParts
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2EventAdapter
import com.xiufeigo.opencodeshell.v2.V2Transport
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P2-3: a notice row reaches the timeline down **two** paths — the live frames
 * the adapter builds (`session.shell.started`, `session.skill.activated`) and
 * the durable row `GET /api/session/{id}/message` projects
 * ([V2Transport.toUi]). Both build the part `"$id:notice"` for the same row id
 * (`ID.fromEvent`, `core/src/session/message-updater.ts:185-198`), and the
 * timeline prints `part.toolName` as the row's value for `shell` and `skill`
 * (`ui/session/timeline/PartRenderer.kt:200-210`). The snapshot side left it
 * null, so reopening a session — where the snapshot is the only source for
 * rows the live stream no longer replays — blanked the command / skill name.
 *
 * These pin the two projections to the same part shape.
 */
class V2NoticeRowParityTest {

    private val transport = V2Transport(V2Api("http://127.0.0.1:1/".toHttpUrl()))

    /** One `GET /api/session/{id}/message` row → the store row the snapshot applies. */
    private fun snapshot(row: JSONObject): ChatStore.UiMessage =
        with(transport) { V2Api.Message.fromJson(row).toUi() }
            ?: error("the row projects to nothing: $row")

    /** The live frame for the same event (same envelope shape the SSE carries). */
    private fun live(type: String, id: String, data: String): ChatStore.UiMessage =
        V2EventAdapter.adapt(
            type,
            JSONObject("""{"id":"$id","type":"$type","created":1700000000000,"data":$data}"""),
        ).filterIsInstance<ChatStore.Event.MessageUpdated>().single().message

    // ------------------------------------------------------------- shell

    /**
     * The durable row the projector writes: `command` / `status` at the top
     * level (`Session.Message.Shell`, `session-message.ts:111-123`) and, once
     * `session.shell.ended` settled it, the captured page as the `Shell.Output`
     * struct (`_upstream/packages/schema/src/shell.ts:73-81`).
     */
    private fun settledShellRow(): JSONObject = JSONObject(
        """{"id":"msg_sh1","type":"shell","shellID":"sh_9","command":"ls -la","status":"exited","exit":0,""" +
            """"output":{"output":"file.txt","cursor":8,"size":8,"truncated":false},""" +
            """"time":{"created":1700000000000,"completed":1700000001000}}""",
    )

    @Test
    fun `a reopened shell row keeps its command and status`() {
        val row = snapshot(settledShellRow())
        assertEquals("msg_sh1", row.id)
        assertEquals("assistant", row.role)
        val part = row.parts.single()
        assertEquals("msg_sh1:notice", part.id)
        assertEquals("shell", part.type)
        assertEquals("ls -la", part.toolName)
        assertEquals("exited", part.toolStatus)
        // The page is `output.output`, not the `Shell.Output` struct stringified.
        assertEquals("ls -la\nfile.txt", part.text)
    }

    @Test
    fun `the snapshot and live shell parts are the same shape`() {
        val fromLive = live(
            "session.shell.started",
            "evt_sh1",
            """{"sessionID":"ses_1","shell":{"id":"sh_9","command":"ls -la","status":"running"}}""",
        ).parts.single()
        val fromSnapshot = snapshot(settledShellRow()).parts.single()
        // Same row id and part id: `mergeParts` folds them onto one row.
        assertEquals("msg_sh1:notice", fromLive.id)
        assertEquals(fromLive.id, fromSnapshot.id)
        assertEquals(fromLive.type, fromSnapshot.type)
        assertEquals(fromLive.toolName, fromSnapshot.toolName)
        // Same slot, later value: the durable row is the settled one, so it
        // carries the ended frame's status.
        assertEquals("running", fromLive.toolStatus)
        assertEquals("exited", fromSnapshot.toolStatus)
    }

    @Test
    fun `a null command or status never becomes the literal null`() {
        val part = snapshot(
            JSONObject("""{"id":"msg_sh2","type":"shell","command":null,"status":null,"time":{"created":1}}"""),
        ).parts.single()
        assertNull(part.toolName)
        assertNull(part.toolStatus)
        assertEquals("", part.text)
    }

    @Test
    fun `a shell row carrying the page as a plain string still renders it`() {
        val part = snapshot(
            JSONObject("""{"id":"msg_sh3","type":"shell","command":"ls","output":"file.txt","time":{"created":1}}"""),
        ).parts.single()
        assertEquals("ls", part.toolName)
        assertEquals("ls\nfile.txt", part.text)
    }

    // ------------------------------------------------------------- skill

    /** `Session.Message.Skill{skill, name, text}` (`session-message.ts:103-108`). */
    private fun skillRow(): JSONObject = JSONObject(
        """{"id":"msg_k1","type":"skill","skill":"sk_1","name":"review","text":"SKILL BODY",""" +
            """"time":{"created":1700000000000}}""",
    )

    @Test
    fun `a reopened skill row keeps its skill name`() {
        val part = snapshot(skillRow()).parts.single()
        assertEquals("msg_k1:notice", part.id)
        assertEquals("skill", part.type)
        assertEquals("review", part.toolName)
        // The live `skill.activated` frame carries no status, so neither does
        // the snapshot part — `NoticeLabelRow` reads only `toolName` anyway.
        assertNull(part.toolStatus)
        assertEquals("SKILL BODY", part.text)
    }

    @Test
    fun `the snapshot and live skill parts are the same shape`() {
        val fromLive = live(
            "session.skill.activated",
            "evt_k1",
            """{"sessionID":"ses_1","id":"sk_1","name":"review","text":"SKILL BODY"}""",
        ).parts.single()
        val fromSnapshot = snapshot(skillRow()).parts.single()
        assertEquals(fromLive.id, fromSnapshot.id)
        assertEquals(fromLive.type, fromSnapshot.type)
        assertEquals(fromLive.toolName, fromSnapshot.toolName)
        assertEquals(fromLive.toolStatus, fromSnapshot.toolStatus)
        assertEquals(fromLive.text, fromSnapshot.text)
    }

    @Test
    fun `a skill row without a name stays nameless`() {
        val part = snapshot(
            JSONObject("""{"id":"msg_k2","type":"skill","name":null,"text":"body","time":{"created":1}}"""),
        ).parts.single()
        assertNull(part.toolName)
    }

    // ---------------------------------------------------- system / synthetic

    /**
     * `Session.Message.System` / `Session.Message.Synthetic`
     * (`_upstream/packages/schema/src/session-message.ts:71-85`) carry the
     * model-facing `text` **and** an optional human `description`. The timeline
     * prints `RawParts.description(rawJson) ?? text` (`PartRenderer.kt:196-199`),
     * which live rows satisfy because their part keeps the payload as `rawJson`;
     * a snapshot part carries none (`UiPart.RAW_JSON_TYPES`), so [V2Transport.toUi]
     * resolves the description into `text`. The two paths must draw the same line.
     */
    private fun renderedLabel(part: ChatStore.UiPart): String =
        RawParts.description(part.rawJson) ?: part.text

    @Test
    fun `a reopened synthetic row shows the line the live row showed`() {
        val fromLive = live(
            "session.synthetic",
            "evt_s1",
            """{"sessionID":"ses_1","text":"Heads up","description":"Compacted 3 files"}""",
        ).parts.single()
        val fromSnapshot = snapshot(
            JSONObject(
                """{"id":"msg_s1","type":"synthetic","text":"Heads up","description":"Compacted 3 files",""" +
                    """"time":{"created":1700000000000}}""",
            ),
        ).parts.single()
        assertEquals("msg_s1:notice", fromSnapshot.id)
        assertEquals(fromLive.id, fromSnapshot.id)
        assertEquals(fromLive.type, fromSnapshot.type)
        // The live row draws the description, not the model-facing text…
        assertEquals("Compacted 3 files", renderedLabel(fromLive))
        // …and the reopened row draws the same one.
        assertEquals(renderedLabel(fromLive), renderedLabel(fromSnapshot))
        // Without breaking the contract: snapshot parts still carry no rawJson.
        assertNull(fromSnapshot.rawJson)
    }

    @Test
    fun `a reopened system row shows the line the live row showed`() {
        val fromLive = live(
            "session.instructions.updated",
            "evt_i1",
            """{"sessionID":"ses_1","text":"New guidance","description":"Instructions changed"}""",
        ).parts.single()
        val fromSnapshot = snapshot(
            JSONObject(
                """{"id":"msg_i1","type":"system","text":"New guidance","description":"Instructions changed",""" +
                    """"time":{"created":1700000000000}}""",
            ),
        ).parts.single()
        assertEquals(fromLive.id, fromSnapshot.id)
        assertEquals(fromLive.type, fromSnapshot.type)
        assertEquals("Instructions changed", renderedLabel(fromLive))
        assertEquals(renderedLabel(fromLive), renderedLabel(fromSnapshot))
        assertNull(fromSnapshot.rawJson)
    }

    @Test
    fun `a row without a description still renders its text on both paths`() {
        val fromLive = live(
            "session.synthetic",
            "evt_s2",
            """{"sessionID":"ses_1","text":"Heads up"}""",
        ).parts.single()
        val fromSnapshot = snapshot(
            JSONObject("""{"id":"msg_s2","type":"synthetic","text":"Heads up","time":{"created":1}}"""),
        ).parts.single()
        assertEquals("Heads up", renderedLabel(fromLive))
        assertEquals("Heads up", renderedLabel(fromSnapshot))
    }

    @Test
    fun `a JSON null description never becomes the literal null`() {
        // org.json's `optString` answers the literal "null" for a JSON null, so
        // the reader has to guard it (`RawParts.description`).
        val part = snapshot(
            JSONObject("""{"id":"msg_s3","type":"synthetic","text":"Heads up","description":null,"time":{"created":1}}"""),
        ).parts.single()
        assertEquals("Heads up", part.text)
        assertNull(RawParts.description(part.rawJson))
    }
}
