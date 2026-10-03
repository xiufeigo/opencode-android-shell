package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2Transport
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P3-3: the session-level revert boundary is **server state**
 * (`Session.Info.revert.messageID`, `_upstream/packages/schema/src/session-revert.ts:8-14`),
 * so a session reopened after a revert elsewhere must cut its tail again. The
 * transport answered `revertMessageID = null` unconditionally and the snapshot
 * read never adopted the field, so the hidden tail came back on every reopen.
 *
 * The two halves are pinned here: the pure row projection (JVM-testable) and
 * the one wiring line in [ChatViewModel.refreshSnapshot] that consumes it
 * (source-level, like [SessionUnreadWiringTest] — no test instantiates the
 * ViewModel).
 */
class SessionRevertRestoreTest {

    private val transport = V2Transport(V2Api("http://127.0.0.1:1/".toHttpUrl()))

    private fun row(json: String) =
        with(transport) { V2Api.SessionInfo.fromJson(JSONObject(json)).toRow() }

    // ------------------------------------------------------ the row projection

    @Test
    fun theSessionRowCarriesTheServerRevertBoundary() {
        val row = row(
            """{"id":"ses_1","title":"t","revert":{"messageID":"msg_02"},""" +
                """"time":{"created":1,"updated":2}}""",
        )
        assertEquals("ses_1", row.id)
        assertEquals("msg_02", row.revertMessageID)
    }

    @Test
    fun noRevertMeansNoBoundary() {
        // Absent, JSON `null`, or an empty object — all "nothing staged".
        assertNull(row("""{"id":"ses_1","title":"t","time":{"created":1}}""").revertMessageID)
        assertNull(row("""{"id":"ses_1","revert":null}""").revertMessageID)
        assertNull(row("""{"id":"ses_1","revert":{}}""").revertMessageID)
        // optString's trap: a JSON `null` would answer the literal "null".
        assertNull(row("""{"id":"ses_1","revert":{"messageID":null}}""").revertMessageID)
    }

    // ---------------------------------------------------------- the wiring

    @Test
    fun theSnapshotReadAdoptsTheBoundary() {
        val vm = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        // …and it must land inside the session-guarded info block, so a fetch
        // that raced a session switch cannot cut the wrong transcript.
        // (Two sites write the flow — `applySessionInfo` and this one — so the
        // adopt is located *after* the guard rather than by first occurrence.)
        val guard = vm.indexOf("if (info != null && sessionID == id) {")
        assertTrue("the session-guarded info block exists", guard >= 0)
        val adopt = vm.indexOf("_revertMessageID.value = info.revertMessageID", guard)
        assertTrue(
            "refreshSnapshot's GET /session/:id must adopt the server's revert boundary",
            adopt > guard,
        )
        assertTrue("the adopt sits inside the session-guarded block", insideBlock(vm, guard, adopt))
        assertTrue(
            "the transport carries it out of Session.Info.revert",
            source("src/main/java/com/xiufeigo/opencodeshell/v2/V2Transport.kt")
                .contains("""raw.optJSONObject("revert")?.let { optStringOrNull(it, "messageID") }"""),
        )
        assertTrue(
            "the snapshot read is the only restore path a reopened session takes",
            vm.contains("transport.fetchSession(id)"),
        )
    }

    /** `true` when the offset [needle] lies inside the brace block opened at [open]. */
    private fun insideBlock(source: String, open: Int, needle: Int): Boolean {
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return needle < i
                }
            }
        }
        return false
    }

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")
}
