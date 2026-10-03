package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.SessionOpenFailure
import com.xiufeigo.opencodeshell.v2.sessionOpenFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v82 BUG-A (emulator-5558): 坏目录会话打开是空态 + 冻结 20 s — 期望 not-found 页 +
 * 「删除该条目」出口.
 *
 * Measured on 2.0.18/4095 against a session created in `/mnt/c/nonexist_v82probe`
 * (a directory the server host does not have) — the numbers this rule is built
 * from:
 *
 * ```
 * GET  /api/session/{id}/permission   → 500   0.084 s
 * GET  /api/session/{id}/form         → 500   0.036 s
 * PATCH /api/session/{id}             → 500   0.034 s
 * GET  /api/session/{id}              → 200   0.004 s   (the row itself is fine)
 * GET  /api/session/{id}/message      → 200   0.005 s   (an empty transcript)
 * ```
 *
 * The row and the message list answer, so the app drew a **normal empty
 * session** while the device log filled with `form list failed: HTTP 500` /
 * `permission list failed: HTTP 500` (`research/v82_verify/5558/33_ghost_open.png`;
 * log `09:03:41.792` onward) — and the only exit for such an entry (the
 * not-found page's 「删除该条目」) sits behind a latch that accepted `404` alone.
 *
 * Two halves: the pure verdict ([sessionOpenFailure], so the mapping cannot be
 * read wrong), and the wiring — which read failures latch, that a socket failure
 * never does, and that the page renders the reason's copy.
 */
class SessionRefusedOpenV82Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val vm = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
    private val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
    private val panel = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionNotFoundPanel.kt")

    // ------------------------------------------------------- 1. the verdict

    @Test
    fun aServerRefusalIsARouteFailureAndASocketFailureIsNot() {
        assertEquals(SessionOpenFailure.GONE, sessionOpenFailure(404))
        assertEquals(SessionOpenFailure.REFUSED, sessionOpenFailure(500))
        assertEquals(SessionOpenFailure.REFUSED, sessionOpenFailure(502))
        assertEquals(SessionOpenFailure.REFUSED, sessionOpenFailure(503))
        assertEquals(SessionOpenFailure.REFUSED, sessionOpenFailure(599))
        assertNull("a 4xx that is not `404` is about the request, not the session", sessionOpenFailure(400))
        assertNull("auth is not a route failure", sessionOpenFailure(401))
        assertNull(sessionOpenFailure(409))
        assertNull("a 2xx is not a failure", sessionOpenFailure(200))
        assertNull("`-1` is V2Api's unparseable body, i.e. the link", sessionOpenFailure(-1))
        assertNull("no status at all is a socket failure (timeout, reset)", sessionOpenFailure(null))
    }

    @Test
    fun theWiringFilesKeepTheVerdictsApart() {
        assertTrue(vm.contains("private fun markSessionMissing(forID: String?, reason: SessionOpenFailure = SessionOpenFailure.GONE)"))
        assertTrue(vm.contains("_sessionMissingReason.value = reason"))
        assertTrue(
            "both protocols map into the one verdict",
            vm.contains("is V2Api.ApiException -> sessionOpenFailure(e.code)") &&
                vm.contains("is OpenCodeClient.ApiException -> sessionOpenFailure(e.code)"),
        )
        // The v81 P2 shape is unchanged: `forgetSession` still latches `GONE`.
        assertTrue(vm.contains("if (id == sessionID) markSessionMissing(id)"))
    }

    // ------------------------------------------------- 2. which reads latch

    @Test
    fun theThreeSessionScopedReadHooksLatchBothShapes() {
        // `refreshSnapshot`'s catch.
        val snapshot = vm.substringAfter("private fun refreshSnapshot()")
            .substringBefore("private fun refreshPermissions()")
        assertTrue(snapshot.contains("val failure = sessionOpenFailureOf(e)"))
        assertTrue(snapshot.contains("markSessionMissing(id, failure)"))
        // `refreshPermissions` and `refreshForms`: the two measured 500s.
        val permissions = vm.substringAfter("private fun refreshPermissions()")
            .substringBefore("private fun refreshForms()")
        assertTrue(permissions.contains("val failure = sessionOpenFailureOf(e)"))
        assertTrue(permissions.contains("markSessionMissing(id, failure)"))
        val forms = vm.substringAfter("private fun refreshForms()")
            .substringBefore("private fun autoApprove(")
        assertTrue(forms.contains("val failure = sessionOpenFailureOf(e)"))
        assertTrue(forms.contains("markSessionMissing(id, failure)"))
        // The per-session log stream: a 5xx there is this session's own route too.
        val logStream = vm.substringAfter("private fun openSessionLogStream(")
            .substringBefore("private fun onV2Frame(")
        assertTrue(
            "the log stream's HTTP error hook now carries both shapes",
            logStream.contains("sessionOpenFailure(code)?.let { failure ->") &&
                logStream.contains("markSessionMissing(id, failure)"),
        )
    }

    @Test
    fun noReadHookReportsATimeoutAsAGoneSession() {
        // The whole point of the split: a socket failure is the link. The
        // classifier never sees an IOException, and no hook inspects messages.
        assertTrue(vm.contains("private fun sessionOpenFailureOf(e: Exception): SessionOpenFailure? = when (e) {"))
        val mapper = vm.substringAfter("private fun sessionOpenFailureOf(e: Exception)")
            .substringBefore("private fun markSessionMissing(")
        assertTrue(mapper.contains("else -> null"))
        assertTrue("no message scraping in the verdict", !mapper.contains("e.message"))
    }

    // ----------------------------------------------- 3. the page and its copy

    @Test
    fun theRoutePassesTheReasonToThePage() {
        assertTrue(
            "the screen collects the reason beside the boolean gate",
            screen.contains("val sessionMissingReason by chatViewModel.sessionMissingReason.collectAsState()"),
        )
        assertTrue(
            "the default keeps the ghost-entry copy for a pre-v82 route",
            screen.contains("reason = sessionMissingReason ?: SessionOpenFailure.GONE"),
        )
    }

    @Test
    fun thePageHasOneShapeForEachReason() {
        val reasonBlock = panel.substringAfter("val titleRes = when (reason)")
            .substringBefore("Column(")
        assertTrue(reasonBlock.contains("SessionOpenFailure.GONE -> R.string.oc_session_not_found"))
        assertTrue(reasonBlock.contains("SessionOpenFailure.REFUSED -> R.string.oc_session_refused"))
        assertTrue(reasonBlock.contains("SessionOpenFailure.REFUSED -> R.string.oc_session_refused_description"))
        assertTrue(
            "the three exits are the page's own, for both reasons",
            panel.contains("stringResource(R.string.oc_back)") &&
                panel.contains("stringResource(R.string.oc_session_not_found_retry)") &&
                panel.contains("stringResource(R.string.oc_session_not_found_delete)"),
        )
    }

    @Test
    fun theRefusedCopyExistsInAllThreeLocales() {
        for (dir in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            val xml = source("src/main/res/$dir/strings.xml")
            assertTrue("$dir title", xml.contains("""<string name="oc_session_refused">"""))
            assertTrue("$dir description", xml.contains("""<string name="oc_session_refused_description">"""))
        }
    }
}
