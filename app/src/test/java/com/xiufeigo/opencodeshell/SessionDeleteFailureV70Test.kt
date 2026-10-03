package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.dropDeletedSessions
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v69 P2 — **删除会话静默失败** (`research/v69_verify/5560/07f_delete.png` →
 * `07m_delete2.png`): with the server down, Delete → 确认 closed the card, the
 * row was still in the list, and the only thing on screen was the transport
 * banner the dead server also raises — which reads as a timeline problem, not
 * "your delete did not happen". The user was told, by a closing dialog, that a
 * delete had happened that never did.
 *
 * Root cause: both `SessionScreen` delete cards cleared their own open-state on
 * the **tap** and read the `deleteSession` answer with an `if (ok)` and no
 * `else`, so a refusal had nowhere to land. Home's delete has answered this
 * since v44 tC (`HomeDeleteDialog`'s `busy`/`errorText`, `research/v44_verify/
 * tC/v44_49_delete_confirm.png`) — the session route never got the same
 * contract.
 *
 * This file keeps the contract itself: the dialog's `busy`/`errorText` inputs,
 * the copy in all three locales, and the two optimistic halves the fix must not
 * disturb — a **confirmed** delete drops the row locally and leaves a tombstone
 * ([dropDeletedSessions]) so the catalogue reads already in flight cannot
 * resurrect it, while a **refused** one leaves the row *and* the tombstone set
 * alone. (A tombstone on a delete that never happened would silently drop a live
 * session from the column.)
 *
 * v70 P2 moved that contract's state out of `SessionScreen` and into one host
 * object (`SessionDeleteHost`) — the two per-entry-point state triples are gone,
 * so the wiring is pinned by [SessionDeleteFlowV70Test] instead of here.
 */
class SessionDeleteFailureV70Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val sessionScreen =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
    private val rowDialogs =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/drawer/SessionRowDialogs.kt")
    private val homeScreen = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")
    private val chatViewModel = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
    private val renameFailureText = source("src/main/java/com/xiufeigo/opencodeshell/ui/RenameFailureText.kt")

    /** The text between two anchors, failing loudly when either is missing. */
    private fun window(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("missing anchor: $from", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("missing anchor: $to", end > start)
        return text.substring(start, end)
    }

    // ------------------------------------------------ the dialog contract

    @Test
    fun `SessionDeleteDialog carries the answer Home's dialog carries`() {
        val dialog = window(rowDialogs, "fun SessionDeleteDialog(", "/** Centered card")
        assertTrue("busy input", dialog.contains("busy: Boolean = false"))
        assertTrue("error input", dialog.contains("errorText: String? = null"))
        assertTrue("the reason is rendered", dialog.contains("if (errorText != null) {"))
        assertTrue("in the danger colour", dialog.contains("color = c.stateDangerFg"))
        assertTrue("Escape is owned by the write in flight", dialog.contains("ModalCard(onDismiss = onDismiss, open = !busy)"))
        // Both buttons, so a refusal's Delete is a retry rather than a dead card.
        assertEquals(2, Regex("enabled = !busy").findAll(dialog).count())
    }

    @Test
    fun `ModalCard passes the open flag through to the escape seam`() {
        val card = window(rowDialogs, "private fun ModalCard(", "/** Retry frames")
        assertTrue("open param", card.contains("open: Boolean = true"))
        assertTrue("wired", card.contains(".ocModalEscape(open = open, autoFocus = autoFocus, onDismiss = onDismiss)"))
    }

    // ------------------------------------------------ the optimistic half

    @Test
    fun `a refused delete records no tombstone and drops no row`() {
        val answer = window(
            homeScreen,
            "actions.delete(target) { deleted ->",
            "/** `m-2` = 8 dp around the page card.",
        )
        val okBranch = answer.substringBefore("} else {")
        val failBranch = answer.substringAfter("} else {")
        assertTrue("row dropped on success", okBranch.contains("sessions = sessions.filterNot { it.id == target.id }"))
        assertTrue("mirror dropped on success", okBranch.contains("renamedTitles = renamedTitles - target.id"))
        assertTrue("tombstone recorded on success", okBranch.contains("deletedSessions = deletedSessions + target.id"))
        assertTrue("the refusal says so", failBranch.contains("oc_delete_session_failed"))
        // A tombstone written on a delete that never happened would drop a live
        // session from the column with no trace — the one way this fix could
        // turn a silent failure into a silent data loss.
        assertTrue("no tombstone on a refusal", !failBranch.contains("deletedSessions"))
    }

    @Test
    fun `the view model applies nothing when the server did not confirm`() {
        val body = chatViewModel.substringAfter("fun deleteSession(").substringBefore("\n    /**")
        val okBranch = body.substringAfter("if (deleted) {").substringBefore("onResult(deleted)")
        for (effect in listOf(
            "_sessionUnread.value = _sessionUnread.value.filterKeys",
            "_askLedger.update",
            "if (id == sessionID) newSession()",
            "_sessionsRevision.value += 1",
        )) {
            assertTrue("`$effect` is applied only on a confirmed delete", okBranch.contains(effect))
        }
        // Every branch still answers, and none of them throws: the caller's
        // `if (ok)` / `else` is the only thing that depends on it. The two
        // `onResult(false)` are the empty id and the transport that never
        // arrived; a refusal that did reach the wire is the `deleted` answer.
        assertEquals(2, Regex("onResult\\(false\\)").findAll(body).count())
        assertTrue("the answer is the server's", body.contains("onResult(deleted)"))
    }

    @Test
    fun `the tombstone still outlives a read that was already in flight`() {
        val rows = listOf(
            OpenCodeClient.Session(id = "s1", title = "A", directory = null, updatedAt = 0L),
            OpenCodeClient.Session(id = "s2", title = "B", directory = null, updatedAt = 0L),
        )
        // The confirmed delete's local drop …
        assertEquals(listOf("s2"), rows.filterNot { it.id == "s1" }.map { it.id })
        // … and the read that was issued before the DELETE landed.
        assertEquals(listOf("s2"), dropDeletedSessions(rows, setOf("s1")).map { it.id })
        // A refused one leaves the tombstone set empty, so the same read is kept.
        assertEquals(rows, dropDeletedSessions(rows, emptySet()))
    }

    // ------------------------------------------------ the sibling that shared it

    @Test
    fun `the session route's rename reports its refusal too`() {
        // Same card-dismisses-on-tap shape as the delete had, same silence: the
        // write's only answer was an `if (ok)`. Home's row menu has toasted
        // `oc_rename_session_failed` since v45; the session route did not.
        //
        // v81 P1: the wording is now chosen by the outcome — a server `500` gets
        // its own line (`oc_rename_session_refused`) instead of the connection
        // copy the measured refusal was mis-reported with. The shared helper is
        // the single place that maps them, and it is a LONG toast.
        val rename = window(sessionScreen, "renameTarget?.let { session ->", "// v69 P2")
        assertTrue(
            "the refusal is reported",
            rename.contains("renameFailureToast(context, outcome)"),
        )
        val copy = window(renameFailureText, "internal fun renameFailureRes(", "internal fun renameFailureToast(")
        assertTrue(
            "the connection copy is still one of the shapes",
            copy.contains("-> R.string.oc_rename_session_failed"),
        )
        val toast = renameFailureText.substringAfter("internal fun renameFailureToast(")
        assertTrue("as a long toast", toast.contains("Toast.LENGTH_LONG"))
    }

    // ------------------------------------------------ the copy it now leans on

    @Test
    fun `the three newly surfaced lines are translated, not the English copy`() {
        val keys = listOf("oc_delete_session_failed", "oc_rename_session_failed", "oc_export_failed")
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
}
