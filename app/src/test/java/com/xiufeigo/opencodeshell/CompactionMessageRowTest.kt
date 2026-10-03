package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineRows
import com.xiufeigo.opencodeshell.ui.session.timeline.compactionLabelRes
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2Transport
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v21 P3 (`research/v21_verify/limit/v21_92_session_compaction.png`): the server
 * wrote a **compaction message row** with `status:"failed"` and
 * `error:{type:"provider.invalid-request",message:"Upstream invalid_request 400"}`
 * (`Session.Message.Compaction.Failed`,
 * `_upstream/packages/schema/src/session-message.ts:268-275`) and the timeline
 * rendered 「Session compacted」 — the success copy — twice: once for the row's
 * own part and once for the `COMPACTION_END` divider.
 *
 * Two shapes of one fact reach the UI:
 *
 * - the **live** row the `session.compaction.*` frames build
 *   (`ChatStore.reduceCompaction`: `toolStatus` `"running"` / `"completed"` /
 *   `"error"`), which the v19 fix already labelled honestly;
 * - the **durable** row above, whose outcome lives on the row itself and which
 *   the snapshot projection used to keep only as a `summary` — so a failed row
 *   arrived as an empty part, indistinguishable from a completed one.
 *
 * [ChatStore.compactionRow] rebuilds the durable row in the live row's exact
 * shape, `V2Transport.toUi` projects every `type=compaction` snapshot row
 * through that same builder, and [ChatStore.compactionStatusOf] is the one
 * reader all three share — so the three states (failed / completed / no status)
 * are pinned here for the snapshot row as well as for the tool part.
 */
class CompactionMessageRowTest {

    // ------------------------------------------------------- the durable row

    /**
     * The row measured live on 2.0.18 (2026-09-26): `POST /api/session/{id}/compact`
     * on a session with nothing to summarise answers 200 and the server records
     * `Session.Message.Compaction.Failed` with `reason:"manual"` — a
     * `status`/`error` on the **row**, no `summary`, no `content[]`.
     */
    private fun measuredFailedRow(): JSONObject = JSONObject(
        """{"type":"compaction","id":"msg_0de4855ea001Davzlg5syoLI7m","time":{"created":1790435677683},""" +
            """"status":"failed","reason":"manual",""" +
            """"error":{"type":"compaction.unavailable","message":"Nothing to compact yet"}}""",
    )

    private fun failedRow(): JSONObject = JSONObject(
        """
        {"id":"msg_a1","type":"compaction","status":"failed","reason":"auto",
         "error":{"type":"provider.invalid-request","message":"Upstream invalid_request 400","status":400},
         "cost":0,"tokens":{"input":1234,"output":12},
         "time":{"created":1790396863440}}
        """.trimIndent(),
    )

    private fun completedRow(): JSONObject = JSONObject(
        """
        {"id":"msg_a2","type":"compaction","status":"completed","reason":"auto",
         "summary":"## Goal\nShip the timeline fix",
         "time":{"created":1790396863440}}
        """.trimIndent(),
    )

    private fun runningRow(): JSONObject = JSONObject(
        """{"id":"msg_a3","type":"compaction","status":"running","reason":"auto","summary":"","time":{"created":1790396863440}}""",
    )

    // ------------------------------------------------- 1. the status reader

    @Test
    fun theStatusReaderSpeaksBothVocabularies() {
        fun part(status: String?) = ChatStore.UiPart(id = "p", type = "compaction", toolStatus = status)
        // The store's own spelling of a rejected compaction, and the durable row's.
        assertEquals(ChatStore.COMPACTION_FAILED, ChatStore.compactionStatusOf(part("error")))
        assertEquals(ChatStore.COMPACTION_FAILED, ChatStore.compactionStatusOf(part("failed")))
        assertEquals(ChatStore.COMPACTION_COMPLETED, ChatStore.compactionStatusOf(part("completed")))
        assertEquals(ChatStore.COMPACTION_RUNNING, ChatStore.compactionStatusOf(part("running")))
        // Absent / unknown is not a failure: upstream's completed label is the
        // fallback (`message-content.tsx:411-431`).
        for (unknown in listOf(null, "", "   ", "settled")) {
            assertNull("$unknown", ChatStore.compactionStatusOf(part(unknown)))
            assertFalse("$unknown", ChatStore.compactionFailed(part(unknown)))
        }
    }

    @Test
    fun onlyARejectedCompactionReadsAsFailed() {
        assertTrue(ChatStore.compactionFailed(ChatStore.UiPart(id = "p", type = "compaction", toolStatus = "error")))
        assertTrue(ChatStore.compactionFailed(ChatStore.UiPart(id = "p", type = "compaction", toolStatus = "failed")))
        for (status in listOf("completed", "running", null)) {
            assertFalse(
                "$status must keep the completed label",
                ChatStore.compactionFailed(ChatStore.UiPart(id = "p", type = "compaction", toolStatus = status)),
            )
        }
    }

    // ---------------------------------------- 2. the durable row → the store

    @Test
    fun aFailedRowRebuildsAsTheLiveErrorRow() {
        val message = ChatStore.compactionRow(failedRow())!!
        assertEquals("msg_a1", message.id)
        assertEquals("assistant", message.role)
        assertEquals(1790396863440L, message.timeCreated)
        val part = message.parts.single()
        // Same keys the live `session.compaction.*` row uses, so the two merge
        // onto one row instead of stacking (`ChatStore.upsert`).
        assertEquals("msg_a1:notice", part.id)
        assertEquals("compaction", part.type)
        assertEquals("error", part.toolStatus)
        // The readable line lives in `text` on both paths: `reduceCompaction`
        // fills it from the frame's `error`, this from the row's.
        assertEquals("Upstream invalid_request 400", part.text)
        assertNotNull(part.rawJson)
        assertTrue(ChatStore.compactionFailed(part))
    }

    @Test
    fun aCompletedRowRebuildsAsTheLiveSettledRow() {
        val part = ChatStore.compactionRow(completedRow())!!.parts.single()
        assertEquals("completed", part.toolStatus)
        assertEquals("## Goal\nShip the timeline fix", part.text)
        assertFalse(ChatStore.compactionFailed(part))
    }

    @Test
    fun aRunningRowRebuildsAsRunning() {
        val part = ChatStore.compactionRow(runningRow())!!.parts.single()
        assertEquals("running", part.toolStatus)
        assertTrue(ChatStore.compactionRunning(part))
        assertFalse(ChatStore.compactionFailed(part))
    }

    @Test
    fun aRowWithoutAnIdIsDropped() {
        assertNull(ChatStore.compactionRow(JSONObject("""{"type":"compaction","status":"failed"}""")))
    }

    /**
     * The same measured row, rebuilt by [ChatStore.compactionRow] — the builder
     * `V2Transport.toUi` now projects a snapshot row through, so the timeline
     * labels it honestly on reopen instead of reading 「Session compacted」.
     */
    @Test
    fun theMeasuredFailedRowRebuildsAsARejectedCompaction() {
        val message = ChatStore.compactionRow(measuredFailedRow())!!
        assertEquals("msg_0de4855ea001Davzlg5syoLI7m", message.id)
        assertEquals(1790435677683L, message.timeCreated)
        val part = message.parts.single()
        assertEquals("msg_0de4855ea001Davzlg5syoLI7m:notice", part.id)
        assertEquals("error", part.toolStatus)
        assertEquals("Nothing to compact yet", part.text)
        assertEquals("Nothing to compact yet", ChatStore.compactionErrorOf(part))
        // …and the divider after it says so too.
        val end = TimelineRows.build(listOf(message))
            .last { it is TimelineRows.Row.Separator } as TimelineRows.Row.Separator
        assertTrue(end.failed)
    }

    // ------------------------------------- 2b. the snapshot projection (toUi)

    /**
     * The snapshot path: `GET /api/session/{id}/message` answers the durable row
     * and `V2Transport.toUi` projects it. A `type=compaction` row goes through
     * [ChatStore.compactionRow], so the outcome travels with the snapshot itself
     * — no per-session second read, and the same store shape the live
     * `session.compaction.*` frames build.
     */
    private val transport = V2Transport(V2Api("http://127.0.0.1:1/".toHttpUrl()))

    /** One wire row → the store row the snapshot merge applies. */
    private fun snapshot(row: JSONObject): ChatStore.UiMessage =
        with(transport) { V2Api.Message.fromJson(row).toUi() }
            ?: error("the row projects to nothing: $row")

    @Test
    fun aFailedSnapshotRowArrivesAsARejectedCompaction() {
        val part = snapshot(failedRow()).parts.single()
        assertEquals("msg_a1:notice", part.id)
        assertEquals("compaction", part.type)
        assertEquals("error", part.toolStatus)
        assertTrue(ChatStore.compactionFailed(part))
        // Both slots `compactionErrorOf` reads are filled by the projection: the
        // readable line in `text`, the untouched row in `rawJson`.
        assertEquals("Upstream invalid_request 400", part.text)
        assertEquals("Upstream invalid_request 400", ChatStore.compactionErrorOf(part))
        assertTrue(part.rawJson.orEmpty().contains("provider.invalid-request"))
        // …and the divider that closes the row says so too.
        val end = TimelineRows.build(listOf(snapshot(failedRow())))
            .last { it is TimelineRows.Row.Separator } as TimelineRows.Row.Separator
        assertTrue("a rejected compaction must not close as a completed one", end.failed)
    }

    @Test
    fun aCompletedSnapshotRowKeepsTheSummaryAndTheCompletedLabel() {
        val part = snapshot(completedRow()).parts.single()
        assertEquals("completed", part.toolStatus)
        assertEquals("## Goal\nShip the timeline fix", part.text)
        assertFalse(ChatStore.compactionFailed(part))
        assertNull(ChatStore.compactionErrorOf(part))
    }

    @Test
    fun aRunningSnapshotRowKeepsTheShimmer() {
        val part = snapshot(runningRow()).parts.single()
        assertEquals("running", part.toolStatus)
        assertTrue(ChatStore.compactionRunning(part))
        assertFalse(ChatStore.compactionFailed(part))
    }

    @Test
    fun aSnapshotRowWithoutAStatusKeepsTheCompletedFallback() {
        // A row that carries only the summary (an older server, or a stored
        // snapshot): nothing settled the part, so the completed label is the
        // fallback — and never a failure.
        val legacy = JSONObject(
            """{"id":"msg_a5","type":"compaction","summary":"Old summary","time":{"created":7}}""",
        )
        val message = snapshot(legacy)
        val part = message.parts.single()
        assertEquals("msg_a5", message.id)
        assertEquals(7L, message.timeCreated)
        assertNull(part.toolStatus)
        assertEquals("Old summary", part.text)
        assertFalse(ChatStore.compactionFailed(part))
        assertNull(ChatStore.compactionErrorOf(part))
        // An unrecognised status is equally not a failure.
        val unknown = snapshot(
            JSONObject("""{"id":"msg_a6","type":"compaction","status":"settled","summary":"s"}"""),
        )
        assertNull(unknown.parts.single().toolStatus)
        assertFalse(ChatStore.compactionFailed(unknown.parts.single()))
    }

    @Test
    fun theMeasuredRowReachesTheTimelineThroughTheSnapshotProjection() {
        val message = snapshot(measuredFailedRow())
        assertEquals("msg_0de4855ea001Davzlg5syoLI7m", message.id)
        val part = message.parts.single()
        assertEquals("error", part.toolStatus)
        assertEquals("Nothing to compact yet", part.text)
        assertEquals("Nothing to compact yet", ChatStore.compactionErrorOf(part))
        val end = TimelineRows.build(listOf(message))
            .last { it is TimelineRows.Row.Separator } as TimelineRows.Row.Separator
        assertTrue(end.failed)
    }

    @Test
    fun theSnapshotProjectionAndTheDurableRowBuilderAgreeOnEveryState() {
        // One builder for the durable row, used by the projection: if the
        // snapshot path ever re-derives the outcome on its own, the two shapes
        // drift and one compaction renders two different verdicts.
        for (row in listOf(failedRow(), completedRow(), runningRow(), measuredFailedRow())) {
            assertEquals("${row.optString("status")} must project identically", ChatStore.compactionRow(row), snapshot(row))
        }
    }

    // ------------------------------------------------- 3. the error detail

    @Test
    fun theFailureDetailSurvivesBothShapes() {
        // Live: `reduceCompaction` settled the line into `text`.
        assertEquals(
            "Upstream invalid_request 400",
            ChatStore.compactionErrorOf(
                ChatStore.UiPart(id = "p", type = "compaction", toolStatus = "error", text = "Upstream invalid_request 400"),
            ),
        )
        // Durable: rebuilt above, so the same reader answers.
        assertEquals(
            "Upstream invalid_request 400",
            ChatStore.compactionErrorOf(ChatStore.compactionRow(failedRow())!!.parts.single()),
        )
        // A failed row whose payload carries no readable error still reads as
        // failed (the label must not fall back to 「Session compacted」) — only
        // the detail box is empty.
        val bare = ChatStore.UiPart(id = "p", type = "compaction", toolStatus = "error")
        assertTrue(ChatStore.compactionFailed(bare))
        assertNull(ChatStore.compactionErrorOf(bare))
        // …and a non-failed part never grows an error row.
        assertNull(ChatStore.compactionErrorOf(ChatStore.UiPart(id = "p", type = "compaction", toolStatus = "completed", text = "summary")))
        assertNull(ChatStore.compactionErrorOf(ChatStore.UiPart(id = "p", type = "compaction")))
    }

    // ---------------------------------------------- 4. the two render paths

    @Test
    fun theMessageRowMarksTheFailedCompactionDivider() {
        // The v21 screenshot's divider: the `COMPACTION_END` after the message
        // row that carried the failed compaction.
        val end = TimelineRows.build(listOf(ChatStore.compactionRow(failedRow())!!))
            .last { it is TimelineRows.Row.Separator } as TimelineRows.Row.Separator
        assertEquals(TimelineRows.SeparatorKind.COMPACTION_END, end.kind)
        assertTrue("a rejected compaction must not close as a completed one", end.failed)

        for (row in listOf(completedRow(), runningRow())) {
            val settled = TimelineRows.build(listOf(ChatStore.compactionRow(row)!!))
                .last { it is TimelineRows.Row.Separator } as TimelineRows.Row.Separator
            assertFalse("${row.optString("status")} must keep the completed label", settled.failed)
        }

        // No status at all (a part the store could not settle): the upstream
        // completed label, exactly as before the fix.
        val unknown = TimelineRows.build(
            listOf(
                ChatStore.UiMessage(
                    id = "msg_a4",
                    role = "assistant",
                    timeCreated = 1L,
                    parts = listOf(ChatStore.UiPart(id = "msg_a4:notice", type = "compaction")),
                ),
            ),
        ).last { it is TimelineRows.Row.Separator } as TimelineRows.Row.Separator
        assertFalse(unknown.failed)
    }

    @Test
    fun bothPathsChooseTheSameLabel() {
        // One chooser: the part body (`CompactionBlock`) and the divider
        // (`TimelineSeparatorRow`) cannot disagree about one compaction.
        val failed = ChatStore.UiPart(id = "p", type = "compaction", toolStatus = "error")
        assertEquals(R.string.oc_compaction_failed, compactionLabelRes(failed))
        assertEquals(R.string.oc_compaction_failed, compactionLabelRes(failed = true))
        for (part in listOf(
            ChatStore.UiPart(id = "p", type = "compaction", toolStatus = "completed", text = "summary"),
            ChatStore.UiPart(id = "p", type = "compaction", toolStatus = "running"),
            ChatStore.UiPart(id = "p", type = "compaction"),
        )) {
            assertEquals(R.string.oc_compaction, compactionLabelRes(part))
            assertEquals(R.string.oc_compaction, compactionLabelRes(failed = false))
        }
    }

    @Test
    fun theFailedDetailIsExpandable() {
        // 「错误详情可展」: a short line is shown whole, a provider wall is clamped
        // to one line until tapped (`CompactionErrorDetail`).
        val renderers = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/ToolRenderers.kt")
        val body = renderers.substringAfter("private fun CompactionErrorDetail(")
            .substringBefore("\n@StringRes")
        assertTrue(body.contains("rememberSaveable(part.id)"))
        assertTrue(body.contains("maxLines = if (clamp) 1 else Int.MAX_VALUE"))
        assertTrue(body.contains("COMPACTION_ERROR_CLAMP"))
        // The detail is the failure's own line, never the summary re-printed.
        assertTrue(body.contains("error: String"))
    }

    // ------------------------------------------------------------- fixtures

    private fun source(relative: String): String =
        listOf(java.io.File(relative), java.io.File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")
}
