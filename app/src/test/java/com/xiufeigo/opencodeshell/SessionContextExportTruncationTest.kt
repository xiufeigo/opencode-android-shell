package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel
import com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel.ExportState
import com.xiufeigo.opencodeshell.ui.session.export.SessionExport
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The message drain's truncation has to be **visible**, not only recorded.
 *
 * `V2Api.messagesRawPage` stops at its page cap with the server still offering a
 * cursor and reports it (`MessageDrain.truncated`/`pages`), `V2Transport.
 * fetchExportFrame` carries it on `SessionExport.Frame` and
 * `SessionExport.serialize` writes the `truncated` note into the file
 * (`SessionExportTruncationTest`). The context tab then **rebuilt** its payload
 * from `{info, messages}` alone, so the flag was dropped on the floor: the
 * exported file lost the note and the UI said nothing — a transcript the user
 * believes is complete. These pins are the projection half
 * ([SessionContextModel.project] → `Snapshot.exportTruncated*` + the payload's
 * own note) and the panel half (the hint line, source-pinned like the other
 * Compose structure in this tab).
 */
class SessionContextExportTruncationTest {

    private fun session(id: String = "ses_1"): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", "Long session")
        put("time", JSONObject().put("created", 1_735_689_600_000L))
    }

    private fun message(id: String = "msg_1"): JSONObject {
        val info = JSONObject()
            .put("id", id)
            .put("sessionID", "ses_1")
            .put("role", "user")
            .put("time", JSONObject().put("created", 1_735_689_600_000L))
        return JSONObject().put("info", info).put("parts", JSONArray())
    }

    // ------------------------------------------------------------ projection

    @Test
    fun aCappedReadReachesTheProjectionAndTheExportedJson() {
        val snapshot = SessionContextModel.project(
            session = session(),
            messages = listOf(message()),
            exportTruncated = true,
            exportTruncatedPages = 128,
        )

        assertEquals(ExportState.READY, snapshot.exportState)
        assertTrue(snapshot.exportTruncated)
        assertEquals(128, snapshot.exportTruncatedPages)

        val payload = JSONObject(snapshot.export!!.json)
        val note = payload.getJSONObject(SessionExport.TRUNCATED_KEY)
        assertTrue(note.getBoolean("messages"))
        assertEquals(128, note.getInt("pages"))
    }

    @Test
    fun aCompleteReadStaysByteIdenticalToTheUpstreamShape() {
        val snapshot = SessionContextModel.project(
            session = session(),
            messages = listOf(message()),
        )

        assertFalse(snapshot.exportTruncated)
        assertEquals(0, snapshot.exportTruncatedPages)
        assertEquals(
            setOf("info", "messages"),
            JSONObject(snapshot.export!!.json).keys().asSequence().toSet(),
        )
    }

    @Test
    fun anEmptyPayloadIsNeverATruncation() {
        // Nothing loaded is `EMPTY`, not "truncated to 0 pages": the hint must
        // not claim a truncation next to the waiting line.
        val snapshot = SessionContextModel.project(
            session = null,
            messages = emptyList(),
            exportTruncated = true,
            exportTruncatedPages = 128,
        )

        assertNull(snapshot.export)
        assertEquals(ExportState.EMPTY, snapshot.exportState)
        assertFalse(snapshot.exportTruncated)
        assertEquals(0, snapshot.exportTruncatedPages)
    }

    // ----------------------------------------------------------- panel half

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun panel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/context/SessionContextPanel.kt")

    @Test
    fun thePanelShowsTheHintInTheExportArea() {
        val body = panel()
        assertTrue(body.contains("R.string.oc_context_export_truncated"))
        assertTrue(body.contains("shown.exportTruncated"))
        assertTrue(body.contains("shown.exportTruncatedPages"))
        // Same slot as the waiting/failure hint, and only once the payload is
        // in hand (a loading tab has nothing to be truncated).
        assertTrue(body.contains("SessionContextModel.ExportState.READY &&"))
        assertTrue(body.contains("c.stateWarningFg"))
    }

    @Test
    fun theHintStringExistsInAllLocales() {
        val locales = listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-zh-rCN/strings.xml",
            "src/main/res/values-zh-rTW/strings.xml",
        )
        locales.forEach { relative ->
            val xml = source(relative)
            assertTrue(relative, xml.contains("name=\"oc_context_export_truncated\""))
        }
    }
}
