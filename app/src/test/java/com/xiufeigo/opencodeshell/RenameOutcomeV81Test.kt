package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.renameFailureRes
import com.xiufeigo.opencodeshell.v2.RenameConfirmation
import com.xiufeigo.opencodeshell.v2.RenameResult
import com.xiufeigo.opencodeshell.v2.SessionRenameOutcome
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2Transport
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v81 会话生命周期双 bug 的回归 (5556 `research/v81_verify/5556/24_rename_dlg.png` →
 * `25_renamed.png`, 5558 `research/v81_verify/5558/t4_s02_empty_session.png`).
 *
 * **BUG-01** 「重命名报 Not renamed 并回滚」: the server log for the second failed
 * attempt is the whole story —
 *
 * ```
 * 07:04:47.171 PATCH /api/session/ses_f0edfb53effegnrQBkdNGsl4Sr
 *   cause="PlatformError: NotFound: FileSystem.realPath (/mnt/c/x) … ENOENT …"
 *   http.status=500
 * ```
 *
 * the session's project directory does not exist on the server host, so the
 * server refuses the write — and the transport answered the same bare `null` for
 * that refusal, for a socket error, for a `404`, and for a title the server
 * never held. One `catch (_: Exception) { null }`, four causes, one sentence to
 * the user: 「Not renamed — the server did not confirm it. Check the connection
 * and try again.」 (wrong twice over: the connection was fine, a retry cannot
 * work).
 *
 * **BUG-02** 「幽灵会话死胡同」: the same session answered `404` on *every* route
 * from `07:07:54` on (message / inbox / form / permission / context / log / the
 * session itself) while the app still listed the row and held the tab; opening
 * it showed 「This session cannot be found」 with Back + Retry and no way to drop
 * the entry (Retry re-runs the same 404s).
 *
 * The transport half is driven through [MockWebServer]; the policy, the copy
 * mapping and the wiring are pure or source-pinned (no test touches a real
 * server).
 */
class RenameOutcomeV81Test {

    private fun v2api(server: MockWebServer) = V2Api(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        username = "opencode",
        password = "opencode",
        http = V2Api.defaultClient(),
    )

    /** The window's pause is a seam: a test must not spend it sleeping. */
    private fun transport(server: MockWebServer) =
        V2Transport(v2api(server), renameConfirmDelayMs = 0L)

    private fun sessionBody(title: String) =
        """{"data":{"id":"ses_1","title":"$title","time":{"updated":1}}}"""

    private fun serverError() = MockResponse().setResponseCode(500)
        .setBody("""{"_tag":"UnknownError","message":"FileSystem.realPath (/mnt/c/x)"}""")

    // ------------------------------------------------ the server's own refusal

    @Test
    fun `a rename the server refuses keeps its own status code`() {
        MockWebServer().use { server ->
            server.enqueue(serverError())
            val result = transport(server).renameSession("ses_1", "New")
            assertEquals(RenameResult.Refused(500), result)
            // The refusal is the PATCH's own answer: no confirmation read is
            // worth making for a write the server did not take.
            assertEquals("/api/session/ses_1", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `a 404 on the write is gone, not a failure to report against the connection`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"_tag":"SessionNotFoundError"}"""))
            assertEquals(RenameResult.Gone, transport(server).renameSession("ses_1", "New"))
        }
    }

    @Test
    fun `a 404 on the read after an accepted write is gone too`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"_tag":"SessionNotFoundError"}"""))
            assertEquals(RenameResult.Gone, transport(server).renameSession("ses_1", "New"))
        }
    }

    @Test
    fun `no answer at all is the only shape the connection copy describes`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            assertTrue(transport(server).renameSession("ses_1", "New") is RenameResult.Unreachable)
        }
    }

    @Test
    fun `a read that never answers is unreachable even after the write landed`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val result = transport(server).renameSession("ses_1", "New")
            assertTrue("expected Unreachable, got $result", result is RenameResult.Unreachable)
        }
    }

    // ------------------------------------------------------- the confirmation

    @Test
    fun `an accepted write the read confirms is renamed`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(200).setBody(sessionBody("New")))
            assertEquals(RenameResult.Renamed("New"), transport(server).renameSession("ses_1", "New"))
        }
    }

    @Test
    fun `the window accepts a write the first read was too early to see`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            // The projector relayed the row a moment after the write path
            // answered: the single immediate read the old code made was the
            // other candidate root cause the v81 report named.
            server.enqueue(MockResponse().setResponseCode(200).setBody(sessionBody("Untitled")))
            server.enqueue(MockResponse().setResponseCode(200).setBody(sessionBody("New")))
            assertEquals(RenameResult.Renamed("New"), transport(server).renameSession("ses_1", "New"))
            assertEquals(3, server.requestCount)
            assertEquals("PATCH", server.takeRequest().method)
            assertEquals("GET", server.takeRequest().method)
            assertEquals("GET", server.takeRequest().method)
        }
    }

    @Test
    fun `a title the server never holds is unconfirmed, never mirrored`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            repeat(RenameConfirmation.ATTEMPTS) {
                server.enqueue(MockResponse().setResponseCode(200).setBody(sessionBody("AI Regenerated")))
            }
            val result = transport(server).renameSession("ses_1", "New")
            assertEquals(RenameResult.Unconfirmed, result)
            // The strict contract: the foreign title is NOT handed back, so no
            // row can mirror a name the user never typed.
            assertFalse(result is RenameResult.Renamed)
            assertEquals(1 + RenameConfirmation.ATTEMPTS, server.requestCount)
        }
    }

    // ------------------------------------------------------ the delete's 404

    @Test
    fun `a delete of a session the server forgot is not a failure`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"_tag":"SessionNotFoundError"}"""))
            // The write's goal is "this session is gone" — a 404 says it already
            // is, so the row can be cleared instead of the card reporting a
            // refusal for a session the user only wants off their screen.
            assertTrue(transport(server).deleteSession("ses_1"))
            assertEquals("DELETE", server.takeRequest().method)
        }
    }

    @Test
    fun `a delete the server refuses is still a failure`() {
        MockWebServer().use { server ->
            server.enqueue(serverError())
            assertFalse("a live session must not be tombstoned", transport(server).deleteSession("ses_1"))
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            assertFalse(transport(server).deleteSession("ses_1"))
        }
    }

    // ----------------------------------------------------------- the policy

    @Test
    fun `the acceptance rule is an exact match or nothing`() {
        assertTrue(RenameConfirmation.confirmed("New", "New"))
        assertFalse(RenameConfirmation.confirmed("New", "new"))
        assertFalse(RenameConfirmation.confirmed("New", "New "))
        assertFalse(RenameConfirmation.confirmed("New", "Untitled"))
        assertFalse(RenameConfirmation.confirmed("New", null))
    }

    @Test
    fun `the window is bounded and worth more than one read`() {
        assertTrue("at least one retry", RenameConfirmation.ATTEMPTS >= 2)
        assertTrue("a pause between attempts", RenameConfirmation.RETRY_DELAY_MS > 0L)
        assertTrue(RenameConfirmation.shouldRetry(0))
        assertTrue(RenameConfirmation.shouldRetry(RenameConfirmation.ATTEMPTS - 1))
        assertFalse(RenameConfirmation.shouldRetry(RenameConfirmation.ATTEMPTS))
        assertFalse(
            "one read already made is the whole budget when a caller bounds it to one",
            RenameConfirmation.shouldRetry(1, maxAttempts = 1),
        )
    }

    @Test
    fun `only 404 is gone and everything else keeps the server's number`() {
        assertEquals(RenameResult.Gone, RenameConfirmation.fromStatus(404))
        assertEquals(RenameResult.Refused(500), RenameConfirmation.fromStatus(500))
        assertEquals(RenameResult.Refused(503), RenameConfirmation.fromStatus(503))
        assertEquals(RenameResult.Refused(400), RenameConfirmation.fromStatus(400))
        // `V2Api.JSON_BODY_CODE` (-1) is "the answer could not be parsed", not a
        // status the server chose: it goes with the connection copy.
        assertTrue(RenameConfirmation.fromStatus(-1) is RenameResult.Unreachable)
    }

    // ------------------------------------------------------------- the copy

    @Test
    fun `every outcome gets the line that describes it`() {
        assertNull("a rename that landed says nothing", renameFailureRes(SessionRenameOutcome.RENAMED))
        assertEquals(R.string.oc_rename_session_gone, renameFailureRes(SessionRenameOutcome.GONE))
        assertEquals(R.string.oc_rename_session_refused, renameFailureRes(SessionRenameOutcome.REFUSED))
        assertEquals(R.string.oc_rename_session_failed, renameFailureRes(SessionRenameOutcome.NOT_CONFIRMED))
        assertEquals(R.string.oc_rename_session_failed, renameFailureRes(SessionRenameOutcome.UNREACHABLE))
        assertEquals(R.string.oc_rename_session_failed, renameFailureRes(SessionRenameOutcome.NOT_SENT))
    }

    @Test
    fun `a refused rename no longer blames the connection`() {
        // The measured failure: the server answered `500`, so the line must not
        // tell the user to check a connection that is provably fine.
        val refused = value("values", "oc_rename_session_refused")
        assertFalse(refused.contains("connection", ignoreCase = true))
        assertFalse(refused.contains("connect", ignoreCase = true))
    }

    // ----------------------------------------------- the wiring it leans on

    @Test
    fun `the transport keeps the status and the window, and swallows nothing`() {
        val body = source("src/main/java/com/xiufeigo/opencodeshell/v2/V2Transport.kt")
            .substringAfter("override fun renameSession(")
            .substringBefore("\n    override fun deleteSession(")
        assertTrue("the PATCH's own status is kept", body.contains("RenameConfirmation.fromStatus(e.code)"))
        assertTrue("an accepted write is confirmed exactly", body.contains("RenameConfirmation.confirmed(title, read)"))
        assertTrue("and re-read within the bound window", body.contains("RenameConfirmation.shouldRetry(attempt)"))
        assertFalse(
            "the old shape — one null for every failure — must not come back",
            body.contains("api.session(sessionID).title.takeIf { it == title }") ||
                body.contains("catch (_: Exception)"),
        )
    }

    @Test
    fun `the view model forgets a gone session instead of leaving the row to retry`() {
        val forget = chatViewModel.substringAfter("fun forgetSession(").substringBefore("\n    /**")
        for (effect in listOf(
            "lastSessionRows = lastSessionRows.filterNot { it.id == id }",
            "_sessionUnread.value = _sessionUnread.value.filterKeys",
            "_askLedger.update",
            "if (id == sessionID) markSessionMissing(id)",
            "_sessionsRevision.value += 1",
        )) {
            assertTrue("`$effect` drops the entry everywhere", forget.contains(effect))
        }
        val rename = chatViewModel.substringAfter("fun renameSession(").substringBefore("\n    /**")
        assertTrue(
            "a 404 half reaches the entry repair",
            rename.contains("if (result is RenameResult.Gone) {") && rename.contains("forgetSession(id)"),
        )
        assertTrue("and the failure shapes are reported apart", rename.contains("onResult(result.toOutcome())"))
    }

    @Test
    fun `home drops the ghost row and says which failure it was`() {
        val handler = homeScreen.substringAfter("actions.rename(id, title) { outcome ->")
            .substringBefore("val onExportRow")
        assertTrue(
            "the ghost row takes the delete tombstone",
            handler.contains("if (outcome == SessionRenameOutcome.GONE) deletedSessions = deletedSessions + id"),
        )
        assertTrue("the reason is the outcome's", handler.contains("renameFailureToast(context, outcome)"))
        assertFalse(
            "the old one-size toast is gone from the rename path",
            handler.contains("R.string.oc_rename_session_failed"),
        )
    }

    @Test
    fun `the tab strip drops a tab whose session is gone`() {
        val strip = app.substringAfter("val onRenameSessionTab:")
            .substringBefore("val onReopenClosedTab")
        assertTrue(strip.contains("outcome == SessionRenameOutcome.GONE"))
        assertTrue(strip.contains("forgetDeletedSessionTab(tab.id)"))
        val row = app.substringAfter("rename = { id, title, cb ->").substringBefore("export = { row ->")
        assertTrue(row.contains("outcome == SessionRenameOutcome.GONE"))
        assertTrue(row.contains("forgetDeletedSessionTab(id)"))
    }

    @Test
    fun `the dead end offers the entry exit and it writes to the server first`() {
        val panel = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionNotFoundPanel.kt")
        assertTrue("the page takes the exit", panel.contains("onDelete: () -> Unit"))
        assertTrue(
            "and renders it",
            panel.contains("label = stringResource(R.string.oc_session_not_found_delete)"),
        )
        val wiring = sessionScreen.substringAfter("SessionNotFoundPanel(")
            .substringBefore("} else if (sshFallback")
        assertTrue(
            "the row leaves the catalogue with the exit",
            wiring.contains("chatViewModel.forgetSession(activeSession.id)"),
        )
        // v83 P1: v81 made this exit local-only and the device measured what that
        // cost — the tab closed, the row stayed in Home and the session stayed on
        // the server (`research/v83_verify/5558/26_list_after_del.png`). The exit
        // is server-first now, and only its answer drops the entry; the tab goes
        // through the host's `forget` (below), so both halves are still wired.
        assertTrue(
            "the exit goes through the host's server-first delete",
            wiring.contains("host.deleteEntry(activeSession.id, activeSession.directory) { deleted ->"),
        )
        assertTrue(
            "and the local drop is the answer's, not the tap's",
            wiring.contains("if (deleted) chatViewModel.forgetSession(activeSession.id)"),
        )
        val forget = deleteHost.substringAfter("fun forget(id: String)").substringBefore("fun deleteEntry(")
        assertTrue("the entry is dropped", forget.contains("confirmed = confirmed + id"))
        assertTrue("and the shell is told", forget.contains("onConfirmed(id)"))
        assertFalse("`forget` stays write-free: it is the half a delete already agreed", forget.contains("write("))
        val entry = deleteHost.substringAfter("fun deleteEntry(id: String")
            .substringBefore("/** Cancel / backdrop / Escape")
        assertTrue("the write is the host's own", entry.contains("write(armed.id, directory)"))
        assertTrue("and only a confirmed answer forgets the entry", entry.contains("if (deleted) forget(armed.id)"))
    }

    // ------------------------------------------------------------ the copy

    @Test
    fun `the two new lines are translated, not the English copy`() {
        val keys = listOf("oc_rename_session_refused", "oc_rename_session_gone", "oc_session_not_found_delete")
        for (dir in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            for (key in keys) {
                val text = value(dir, key)
                assertTrue("$dir/$key is empty", text.isNotBlank())
                if (dir != "values") {
                    assertNotEquals("$dir/$key is still the English copy", value("values", key), text)
                }
            }
        }
    }

    private fun value(dir: String, key: String): String =
        Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(source("src/main/res/$dir/strings.xml"))!!.groupValues[1]

    private val chatViewModel =
        source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
    private val homeScreen = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")
    private val app = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
    private val sessionScreen = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
    private val deleteHost = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionDeleteHost.kt")

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")
}
