package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.export.SessionExport
import com.xiufeigo.opencodeshell.ui.session.export.SessionExportKind
import com.xiufeigo.opencodeshell.ui.session.export.sanitizeParam
import com.xiufeigo.opencodeshell.ui.session.export.transferFrame
import com.xiufeigo.opencodeshell.v2.SessionTransferData
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the two route-backed export kinds
 * (`GET /api/experimental/session/{id}/export`): the `?sanitize=` query each
 * kind sends and the `SessionTransfer.Data` → frame mapping the SAF saver
 * serializes.
 */
class SessionExportModesTest {

    @Test
    fun onlyTheSanitizedKindSendsSanitizeTrue() {
        assertNull(SessionExportKind.ASSEMBLED.sanitizeParam())
        assertNull(SessionExportKind.OFFICIAL.sanitizeParam())
        assertEquals(true, SessionExportKind.SANITIZED.sanitizeParam())
    }

    @Test
    fun transferFrameKeepsTheRawHalvesVerbatim() {
        val info = JSONObject().put("id", "ses_1").put("title", "T")
        val message = JSONObject().put("info", JSONObject().put("id", "msg_1"))
        val frame = SessionExport.transferFrame(SessionTransferData(info = info, messages = listOf(message)))
        assertEquals("ses_1", frame.info.optString("id"))
        assertEquals(1, frame.messages.size)
        assertEquals("msg_1", frame.messages[0].optJSONObject("info")?.optString("id"))
    }

    @Test
    fun officialFrameSerializesToTheSameShapeAsTheHandRolledPair() {
        val info = JSONObject().put("id", "ses_1")
        val messages = listOf(JSONObject().put("info", JSONObject().put("id", "msg_1")))
        val official = SessionExport.serialize(
            SessionExport.transferFrame(SessionTransferData(info = info, messages = messages)),
        )
        val handRolled = SessionExport.serialize(SessionExport.Frame(info, messages))
        assertEquals(handRolled, official)
        assertTrue(official.contains("\"messages\""))
        // Two-space indent, exactly upstream's `JSON.stringify(data, null, 2)`.
        val reparsed = JSONObject(official)
        assertEquals("ses_1", reparsed.getJSONObject("info").getString("id"))
        assertEquals(1, reparsed.getJSONArray("messages").length())
    }
}
