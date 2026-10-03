package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.export.SessionExport
import com.xiufeigo.opencodeshell.ui.session.export.parseTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic coverage for the import document parse (`POST /api/experimental/
 * session/import`'s `{info, messages}` body — `session-transfer.ts:7-10`): the
 * strict `info` half, the lenient `messages` half and the reject cases.
 */
class SessionImportParseTest {

    @Test
    fun aFullDocumentKeepsBothHalvesVerbatim() {
        val transfer = SessionExport.parseTransfer(
            """{"info":{"id":"ses_1","title":"T"},"messages":[{"info":{"id":"msg_1"}},{"info":{"id":"msg_2"}}]}""",
        )
        assertNotNull(transfer)
        assertEquals("ses_1", transfer?.info?.optString("id"))
        assertEquals(2, transfer?.messages?.size)
        assertEquals("msg_2", transfer?.messages?.get(1)?.optJSONObject("info")?.optString("id"))
    }

    @Test
    fun missingMessagesIsAnEmptyListNotAFailure() {
        val transfer = SessionExport.parseTransfer("""{"info":{"id":"ses_1"}}""")
        assertNotNull(transfer)
        assertEquals(0, transfer?.messages?.size)
    }

    @Test
    fun nonObjectRowsAreDroppedAndBadDocumentsAreNull() {
        val transfer = SessionExport.parseTransfer(
            """{"info":{"id":"ses_1"},"messages":[{"info":{"id":"msg_1"}}, 7, null, "x"]}""",
        )
        assertEquals(1, transfer?.messages?.size)
        assertNull(SessionExport.parseTransfer("not json"))
        assertNull(SessionExport.parseTransfer("""{"messages":[]}""")) // no info half
        assertNull(SessionExport.parseTransfer("[]"))
    }
}
