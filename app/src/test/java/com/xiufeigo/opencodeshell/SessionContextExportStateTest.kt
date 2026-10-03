package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel
import com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel.ExportState
import com.xiufeigo.opencodeshell.ui.session.export.SessionExportKind
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2: the context tab's Export button always renders — the *rows* it opens are
 * what a missing payload disables, and the header's hint line says why. The
 * pure half lives on [SessionContextModel.Snapshot.exportState]
 * ([ExportState]) plus the menu gate
 * ([SessionContextModel.exportRowEnabled]); the panel half (always-rendered,
 * always-tappable trigger, waiting/error hint) is pinned by source assertions,
 * the house style for Compose structure ([SessionContextPanelScrollTest]).
 *
 * v9-41: the trigger itself used to be gated (`clickable(enabled = ready)`), so
 * a payload that failed to load took the whole menu down — **Import
 * included**, even though Import needs no payload at all. The pins below are
 * the two halves of that fix: the pure gate, which is where the rows' rules
 * live, and the source line that keeps the trigger open.
 */
class SessionContextExportStateTest {

    private fun session(id: String = "ses_1"): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", "Export work")
        put("time", JSONObject().put("created", 1_735_689_600_000L))
    }

    private fun message(id: String = "msg_1", role: String = "user"): JSONObject {
        val info = JSONObject()
            .put("id", id)
            .put("sessionID", "ses_1")
            .put("role", role)
            .put("time", JSONObject().put("created", 1_735_689_600_000L))
        return JSONObject().put("info", info).put("parts", JSONArray())
    }

    // ---------------------------------------------------------- model states

    @Test
    fun emptyProjectionDisablesExportWithWaitingState() {
        val snapshot = SessionContextModel.project(session = null, messages = emptyList())
        assertNull(snapshot.export)
        assertNull(snapshot.exportError)
        assertEquals(ExportState.EMPTY, snapshot.exportState)
    }

    @Test
    fun arrivedPayloadsEnableExport() {
        val snapshot = SessionContextModel.project(
            session = session(),
            messages = listOf(message()),
        )
        assertNotNull(snapshot.export)
        assertEquals(ExportState.READY, snapshot.exportState)
    }

    @Test
    fun failedFetchShowsErrorState() {
        val snapshot = SessionContextModel.project(
            session = null,
            messages = emptyList(),
            exportError = "timeout",
        )
        assertNull(snapshot.export)
        assertEquals("timeout", snapshot.exportError)
        assertEquals(ExportState.FAILED, snapshot.exportState)
    }

    @Test
    fun arrivedDataWinsOverStaleFailure() {
        val snapshot = SessionContextModel.project(
            session = session(),
            messages = listOf(message()),
            exportError = "stale",
        )
        assertNotNull(snapshot.export)
        assertEquals(ExportState.READY, snapshot.exportState)
    }

    @Test
    fun sessionWithoutMessagesStillExports() {
        val snapshot = SessionContextModel.project(session = session(), messages = emptyList())
        assertNotNull(snapshot.export)
        assertEquals(ExportState.READY, snapshot.exportState)
    }

    // ------------------------------------------------------- the menu's gates

    @Test
    fun importRowIsNeverGatedByThePayloadOrAnExport() {
        // The v9-41 bug, pure: with no payload, no session id and an export
        // attempt out, Import is *still* enabled.
        assertTrue(
            SessionContextModel.exportRowEnabled(
                row = SessionContextModel.ExportRow.IMPORT,
                payloadReady = false,
                sessionID = null,
                exportBusy = true,
                importBusy = false,
            ),
        )
        // …and only its own attempt takes it down
        assertFalse(
            SessionContextModel.exportRowEnabled(
                row = SessionContextModel.ExportRow.IMPORT,
                payloadReady = true,
                sessionID = "ses_1",
                exportBusy = false,
                importBusy = true,
            ),
        )
    }

    @Test
    fun payloadRowsGateOnTheirOwnNeedsOnly() {
        fun enabled(row: SessionContextModel.ExportRow, payload: Boolean, id: String? = "ses_1", busy: Boolean = false) =
            SessionContextModel.exportRowEnabled(
                row = row,
                payloadReady = payload,
                sessionID = id,
                exportBusy = busy,
                importBusy = false,
            )

        // the hand-rolled pair serializes the tab's own payload …
        assertTrue(enabled(SessionContextModel.ExportRow.ASSEMBLED, payload = true))
        assertFalse(enabled(SessionContextModel.ExportRow.ASSEMBLED, payload = false))
        // … while the route-backed pair is keyed by the session id, which is why
        // a session half that never landed disables exactly those two
        assertTrue(enabled(SessionContextModel.ExportRow.OFFICIAL, payload = true))
        assertFalse(enabled(SessionContextModel.ExportRow.OFFICIAL, payload = true, id = null))
        assertTrue(enabled(SessionContextModel.ExportRow.SANITIZED, payload = true))
        assertFalse(enabled(SessionContextModel.ExportRow.SANITIZED, payload = true, id = null))
        // an export attempt takes all three down, and no other row
        for (row in listOf(
            SessionContextModel.ExportRow.ASSEMBLED,
            SessionContextModel.ExportRow.OFFICIAL,
            SessionContextModel.ExportRow.SANITIZED,
        )) {
            assertFalse(row.name, enabled(row, payload = true, busy = true))
        }
    }

    @Test
    fun theThreeExportRowsMapToTheirFormats() {
        assertEquals(SessionExportKind.ASSEMBLED, SessionContextModel.ExportRow.ASSEMBLED.kind)
        assertEquals(SessionExportKind.OFFICIAL, SessionContextModel.ExportRow.OFFICIAL.kind)
        assertEquals(SessionExportKind.SANITIZED, SessionContextModel.ExportRow.SANITIZED.kind)
        // Import is not a save format: it reads a file
        assertNull(SessionContextModel.ExportRow.IMPORT.kind)
    }

    // ---------------------------------------------------------- panel states

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun panel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/context/SessionContextPanel.kt")

    @Test
    fun exportButtonAlwaysRenderedNeverHidden() {
        val body = panel()
        assertTrue(body.contains("ExportSessionButton(export)"))
        assertTrue(!body.contains("if (export != null)"))
    }

    @Test
    fun theTriggerStaysTappableWithoutAPayload() {
        val body = panel()
        // v9-41: this is the line that took Import down with the payload
        assertTrue("the trigger must not be gated on the payload", !body.contains("clickable(enabled"))
        assertTrue(body.contains(".clickable(role = Role.Button) { menuOpen = true }"))
        // the rows carry the gating instead, through the pinned pure rule
        assertTrue(body.contains("SessionContextModel.exportRowEnabled("))
        for (row in listOf("ASSEMBLED", "OFFICIAL", "SANITIZED", "IMPORT")) {
            assertTrue("$row must use the rule", body.contains("SessionContextModel.ExportRow.$row"))
        }
        // and the export flag never gates Import (two flags, two rows)
        assertTrue(body.contains("exportBusy = exportBusy,"))
        assertTrue(body.contains("importBusy = importBusy,"))
    }

    @Test
    fun disabledRowsDimInsteadOfVanishing() {
        // the trigger keeps its "the payload rows are unavailable" cue …
        assertTrue(panel().contains(".alpha(if (ready) 1f else 0.4f)"))
        // … while the disabled state of a row is the row's own tint
        assertTrue(panel().contains("color = if (enabled) c.textBase else c.textFaint"))
    }

    @Test
    fun waitingAndFailureHintsAreWired() {
        val body = panel()
        assertTrue(body.contains("oc_context_export_waiting"))
        assertTrue(body.contains("oc_context_export_load_failed"))
        assertTrue(body.contains("stateDangerFg"))
        assertTrue(body.contains("ExportState.FAILED"))
        assertTrue(body.contains("ExportState.READY"))
    }

    @Test
    fun hintStringsExistInAllLocales() {
        val locales = listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-zh-rCN/strings.xml",
            "src/main/res/values-zh-rTW/strings.xml",
        )
        locales.forEach { relative ->
            val xml = source(relative)
            assertTrue("$relative", xml.contains("name=\"oc_context_export_waiting\""))
            assertTrue("$relative", xml.contains("name=\"oc_context_export_load_failed\""))
        }
    }
}
