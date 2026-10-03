package com.xiufeigo.opencodeshell

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v44 tC's P0 — **「删除静默无效」** (`research/v44_verify/tC/v44_49_delete_confirm.png`,
 * v44_50, v44_51, reproduced twice).
 *
 * The reported chain: Home 会话行长按 → 删除… → the confirm dialog's 删除 → the
 * dialog closes, the row is still in the list, the session is still on the
 * server, and **nothing** is on the wire (logcat had no `DELETE`; a direct
 * `curl DELETE` on the same session answered 204). From the user's side the
 * dialog closing reads as "deleted" — worse than a failure that says so.
 *
 * The Home side was correct when this was picked up (menu `onDelete` →
 * `HomeScreen.pendingDelete` → `HomeDeleteDialog.onConfirm` →
 * `HomeRowActions.delete`), and so was the view model except for one thing:
 * [ChatViewModel.deleteSession] sampled `requireTransport()` **at tap time**
 * and early-returned `onResult(false)` on `null` — no log, no request. Home
 * never calls `start` (M1) and the session route's `stop` drops the transport
 * on the way out, so a long-press right after leaving a session lands inside
 * the rebind/probe window. That is the identical window the v43 export fix
 * ([HomeExportTransportWiringTest]) closed for 导出会话, and the caller's
 * `onResult` here was `{ _ -> }`, so the `false` had nowhere to go.
 *
 * `ChatViewModel` is not constructible in a JVM test (Android deps), so the
 * three properties below are pinned in source, the same way that file does it.
 */
class HomeDeleteTransportV44Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val viewModel = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
    private val homeScreen = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")
    private val deleteDialog = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeDeleteDialog.kt")

    /** The body of a member declaration, from its signature to the next member's KDoc. */
    private fun window(text: String, start: String): String {
        val from = text.indexOf(start)
        assertTrue("missing anchor: $start", from >= 0)
        val to = text.indexOf("\n    /**", from)
        return if (to > from) text.substring(from, to) else text.substring(from)
    }

    // --------------------------------- the write acquires the transport, first

    @Test
    fun `the delete acquires its transport instead of sampling one at tap time`() {
        val body = window(viewModel, "fun deleteSession(")
        assertFalse(
            "a bare requireTransport at tap time is the sample that answered null inside the probe window",
            body.contains("val transport = requireTransport()"),
        )
        assertTrue(
            "the delete waits for the transport the way every other read does",
            body.contains("awaitSessionTransport()"),
        )
        // …and the acquisition happens inside the coroutine, i.e. not before
        // the launch that would have to be the thing waiting for it.
        assertTrue(
            "the acquisition is a suspend call, so it must live on the coroutine",
            body.indexOf("scope.launch") < body.indexOf("awaitSessionTransport()"),
        )
    }

    @Test
    fun `the rename sibling had the same tap-time sample and the same silent return`() {
        val body = window(viewModel, "fun renameSession(")
        assertFalse(
            "rename sampled requireTransport at tap time too",
            body.contains("val transport = requireTransport()"),
        )
        assertTrue(body.contains("awaitSessionTransport()"))
    }

    // ------------------------------------------- no branch leaves without a log

    @Test
    fun `every branch that skips the delete is logged`() {
        val body = window(viewModel, "fun deleteSession(")
        // Two ways out without a DELETE: the empty id (before the launch) and
        // the transport that never arrived (inside it). Each used to be silent.
        assertTrue(
            "the empty-id early return says so",
            body.contains("""Log.w(TAG, "delete session not sent: empty id")"""),
        )
        assertTrue(
            "the missing transport says so, and names the session",
            body.contains("""Log.w(TAG, "delete session ${'$'}id not sent: no transport")"""),
        )
        assertTrue(
            "both skip branches are reachable: one before the launch, one after the acquisition",
            body.contains("""Log.w(TAG, "delete session not sent: empty id")""") &&
                body.indexOf("no transport") > body.indexOf("scope.launch"),
        )
    }

    @Test
    fun `the rename skip branches are logged as well`() {
        val body = window(viewModel, "fun renameSession(")
        assertTrue(body.contains("""Log.w(TAG, "rename session not sent"""))
        assertTrue(body.contains("""rename session ${'$'}id not sent: no transport"""))
    }

    // ------------------------------------------- the false reaches the user

    @Test
    fun `the confirm dialog stays open until the server answers`() {
        val call = homeScreen.substring(homeScreen.indexOf("pendingDelete?.let { row ->"))
        assertFalse(
            "clearing the row before the answer is exactly the reported P0: the dialog closed on a delete that never happened",
            call.contains("val target = pendingDelete\n                        pendingDelete = null"),
        )
        assertTrue(
            "the dialog closes on the server's answer only",
            call.contains("if (deleted) {") && call.contains("pendingDelete = null"),
        )
        assertTrue(
            "a failed delete says why, in the dialog, where the same 删除 retries it",
            call.contains("errorText = deleteError") && call.contains("oc_delete_session_failed"),
        )
    }

    @Test
    fun `the dialog renders the failure and locks the buttons while a write is out`() {
        assertTrue(
            "the error line is part of the dialog's own contract",
            deleteDialog.contains("errorText: String? = null") && deleteDialog.contains("if (errorText != null)"),
        )
        assertTrue(
            "a second tap cannot stack a second DELETE on a busy dialog",
            deleteDialog.contains("enabled = !busy"),
        )
    }

    @Test
    fun `a failed rename is reported as well as retried in the editor`() {
        // v81 P1: Home reports through the shared outcome-aware helper, so the
        // reason can be the server's own refusal (`oc_rename_session_refused`)
        // or a session the server has dropped (`oc_rename_session_gone`) — and
        // the connection copy (`oc_rename_session_failed`) is still one of the
        // shapes the helper maps, what this pin has always been about.
        assertTrue(
            "the editor staying open is the retry; the toast is the missing reason",
            homeScreen.contains("renameFailureToast(context, outcome)"),
        )
        assertTrue(
            "and the helper still carries the connection copy",
            source("src/main/java/com/xiufeigo/opencodeshell/ui/RenameFailureText.kt")
                .contains("-> R.string.oc_rename_session_failed"),
        )
    }

    @Test
    fun `the copy exists in all three locales`() {
        for (values in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            val xml = source("src/main/res/$values/strings.xml")
            assertTrue("oc_delete_session_failed missing in $values", xml.contains("name=\"oc_delete_session_failed\""))
            assertTrue("oc_rename_session_failed missing in $values", xml.contains("name=\"oc_rename_session_failed\""))
        }
    }
}
