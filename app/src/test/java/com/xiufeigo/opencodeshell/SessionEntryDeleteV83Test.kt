package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.SessionDeleteEntry
import com.xiufeigo.opencodeshell.ui.session.SessionDeleteEntryModel
import com.xiufeigo.opencodeshell.ui.session.SessionDeleteHost
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v83 P1 (emulator-5558 `25_after_delete.png` → `26_list_after_del.png`):
 * 坏目录会话的 not-found 页点「Delete entry」**不真删** — the tab closed, the row
 * stayed in Home and the session stayed on the server (`GET` → `200`).
 *
 * v81 had made this exit local-only, on the premise that a session the server has
 * forgotten answers `DELETE` with `404`, which the delete card would report as
 * 「Not deleted」. The device disproved the premise for the shape that actually
 * reaches this page — the `REFUSED` (missing-project-directory) session whose
 * scoped routes answer `5xx` — where `DELETE` answers `204` and really deletes.
 *
 * So the exit is server-first ([SessionDeleteHost.deleteEntry]): `204` and `404`
 * (the transport folds "already gone" into a confirmed answer) both forget the
 * entry, anything else keeps the page up with the reason. The transitions are
 * pure ([SessionDeleteEntryModel]) and the host is a plain object, so the
 * contract is driven behaviourally here; the wiring that cannot run on the JVM
 * (which composable paints the refusal, which file folds the `404`) is pinned as
 * source facts at the bottom.
 */
class SessionEntryDeleteV83Test {

    // ------------------------------------------------ the entry's rules

    @Test
    fun `a tap arms the entry it names and an empty id arms nothing`() {
        assertEquals(SessionDeleteEntry("ses_a"), SessionDeleteEntryModel.asked(null, "ses_a"))
        assertNull(SessionDeleteEntryModel.asked(null, ""))
        val open = SessionDeleteEntry("ses_a")
        assertEquals("an open entry is left alone", open, SessionDeleteEntryModel.asked(open, ""))
    }

    /** The same invariant the delete card rests on: the target is pinned for the write. */
    @Test
    fun `an entry cannot change its target while its write is out`() {
        val busy = SessionDeleteEntry("ses_a", busy = true)
        assertEquals(busy, SessionDeleteEntryModel.asked(busy, "ses_b"))
    }

    @Test
    fun `a second tap cannot stack a second write`() {
        assertNull("nothing armed, nothing to write", SessionDeleteEntryModel.writable(null))
        assertNull("a write already out is not repeated", SessionDeleteEntryModel.writable(SessionDeleteEntry("ses_a", busy = true)))
    }

    @Test
    fun `the tap locks the entry and clears the previous reason`() {
        val locked = SessionDeleteEntryModel.writable(SessionDeleteEntry("ses_a", busy = false, failed = true))
        assertNotNull(locked)
        assertTrue("locked while the DELETE is out", locked!!.busy)
        assertFalse("the retry starts from a clean state", locked.failed)
    }

    @Test
    fun `a refusal keeps the reason, a delete that landed clears everything`() {
        val refused = SessionDeleteEntryModel.settled(SessionDeleteEntry("ses_a"), deleted = false)
        assertNotNull("the page keeps saying why", refused)
        assertEquals("ses_a", refused!!.id)
        assertFalse("and the button is usable again", refused.busy)
        assertTrue("with the reason to show", refused.failed)

        assertNull("a confirmed delete leaves nothing to show", SessionDeleteEntryModel.settled(SessionDeleteEntry("ses_a"), deleted = true))
        assertNull("and a dismissal is not undone by a late answer", SessionDeleteEntryModel.settled(null, deleted = false))
    }

    // ------------------------------------------------ the host, driven

    /** A `DELETE` the test decides when to answer — `ChatViewModel.deleteSession`'s shape. */
    private class FakeWrite {
        val calls = mutableListOf<Pair<String, String?>>()
        private var answer: ((Boolean) -> Unit)? = null

        fun write(id: String, directory: String?, onResult: (Boolean) -> Unit) {
            calls += id to directory
            answer = onResult
        }

        fun answer(deleted: Boolean) {
            val pending = answer
            answer = null
            pending?.invoke(deleted)
        }
    }

    private fun host(write: FakeWrite, confirmed: MutableList<String> = mutableListOf()): SessionDeleteHost =
        SessionDeleteHost(
            write = { id, directory, onResult -> write.write(id, directory, onResult) },
            onConfirmed = { id -> confirmed += id },
        )

    /**
     * The measured repair: `DELETE` → `204`. The tab must close **and** the entry
     * must be recorded gone, so Home's and the drawer's lists drop it on the very
     * next read instead of keeping the row the server just deleted.
     */
    @Test
    fun `a confirmed entry delete writes, forgets and reports out`() {
        val write = FakeWrite()
        val confirmed = mutableListOf<String>()
        val host = host(write, confirmed)
        val answers = mutableListOf<Boolean>()

        host.deleteEntry("ses_a", "/mnt/c/x") { answers += it }
        assertEquals("the row's own directory rides the write", listOf("ses_a" to "/mnt/c/x"), write.calls)
        assertEquals("the exit locks while the DELETE is out", true, host.entry?.busy)

        write.answer(true)
        assertEquals("no reason to show", null, host.entry)
        assertEquals("the entry is recorded gone for every list surface", setOf("ses_a"), host.confirmed)
        assertEquals("and the tab is told exactly once", listOf("ses_a"), confirmed)
        assertEquals("the caller hears the answer", listOf(true), answers)
    }

    /**
     * The `404` half of v81's own premise, which is why the transport folds it in:
     * a session the server never had is exactly what this page is up about, so
     * asking to be rid of it must not paint "the server did not confirm it".
     */
    @Test
    fun `a refusal keeps the entry and says so instead of forgetting it silently`() {
        val write = FakeWrite()
        val confirmed = mutableListOf<String>()
        val host = host(write, confirmed)
        val answers = mutableListOf<Boolean>()

        host.deleteEntry("ses_a") { answers += it }
        write.answer(false)

        assertEquals("the reason stays for the page to paint", true, host.entry?.failed)
        assertEquals(false, host.entry?.busy)
        assertEquals("nothing was deleted, so nothing is forgotten", emptySet<String>(), host.confirmed)
        assertEquals("and the shell is not told to close the tab", emptyList<String>(), confirmed)
        assertEquals(listOf(false), answers)

        // The same 删除 is the retry: the entry is writable again.
        host.deleteEntry("ses_a")
        assertEquals(2, write.calls.size)
        host.deleteEntry("ses_a")
        assertEquals("…and the retry is guarded while it is out", 2, write.calls.size)
    }

    /** A late answer for a dismissed/unrelated state cannot resurrect anything. */
    @Test
    fun `a double tap while the write is out stacks nothing`() {
        val write = FakeWrite()
        val host = host(write)

        host.deleteEntry("ses_a")
        host.deleteEntry("ses_a")
        assertEquals("one tap, one write", 1, write.calls.size)
    }

    // ------------------------------------------------ the wiring that cannot run here

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val hostSource = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionDeleteHost.kt")
    private val panel = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionNotFoundPanel.kt")
    private val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
    private val transport = source("src/main/java/com/xiufeigo/opencodeshell/v2/V2Transport.kt")

    @Test
    fun `the host's entry delete writes first and forgets on the answer`() {
        val body = hostSource.substringAfter("fun deleteEntry(id: String")
            .substringBefore("/** Cancel / backdrop / Escape")
        assertTrue("the write is the host's own (`ChatViewModel.deleteSession`)", body.contains("write(armed.id, directory)"))
        assertTrue(
            "the pin and the re-entry guard come from the pure model",
            body.contains("SessionDeleteEntryModel.writable(SessionDeleteEntryModel.asked(entry, id)) ?: return"),
        )
        assertTrue("the answer is what unlocks the page", body.contains("entry = SessionDeleteEntryModel.settled(entry, deleted)"))
        assertTrue(
            "only a confirmed delete forgets the entry",
            body.contains("if (deleted) forget(armed.id)"),
        )
        val forget = hostSource.substringAfter("fun forget(id: String)")
            .substringBefore("fun deleteEntry(")
        assertFalse(
            "`forget` itself stays write-free: it is the local half of a delete already agreed",
            forget.contains("write("),
        )
    }

    /**
     * The transport half of "404 counts as gone": `deleteSession` answers `true`
     * for `204` and for the `404` of a session the server no longer has, `false`
     * for everything else — which is exactly what the exit above branches on.
     */
    @Test
    fun `the transport folds a gone session into a confirmed delete`() {
        val body = transport.substringAfter("override fun deleteSession(")
            .substringBefore("override fun stageRevert(")
        assertTrue("204 (and any 2xx) is a delete", body.contains("api.deleteSession(sessionID)"))
        assertTrue("404 is the same outcome — the session is gone either way", body.contains("e.code == 404"))
        assertTrue("any other answer is a refusal", body.contains("catch (_: Exception)") && body.contains("false"))
    }

    @Test
    fun `the page paints the refusal and locks its own exit`() {
        assertTrue("the exit is the page's", panel.contains("onDelete: () -> Unit"))
        assertTrue(
            "and renders it",
            panel.contains("label = stringResource(R.string.oc_session_not_found_delete)"),
        )
        assertTrue("locked while the DELETE is out", panel.contains("enabled = !entryDeleteBusy,"))
        assertTrue(
            "the reason is shown on the page, in the danger colour",
            panel.contains("text = entryDeleteError,") && panel.contains("color = c.stateDangerFg,"),
        )
        assertFalse(
            "no toast: a transient window is the report v82 had to fix",
            panel.contains("Toast.makeText"),
        )
    }

    @Test
    fun `the route asks the server first and drops the catalogue row on the answer`() {
        val wiring = screen.substringAfter("SessionNotFoundPanel(")
            .substringBefore("} else if (sshFallback")
        assertTrue(
            "the exit goes through the host's server-first delete",
            wiring.contains("host.deleteEntry(activeSession.id, activeSession.directory) { deleted ->"),
        )
        assertTrue(
            "and the local catalogue drop runs only once the server agreed",
            wiring.contains("if (deleted) chatViewModel.forgetSession(activeSession.id)"),
        )
        assertTrue(
            "the page's state is the host's, read back per session id",
            screen.contains("val entryDelete = deleteHost?.entry?.takeIf { it.id == activeSession.id }"),
        )
        assertTrue(
            "the reason on screen is the shared delete copy",
            wiring.contains("stringResource(R.string.oc_delete_session_failed)"),
        )
        assertTrue(
            "a host-less screen (a preview) still has the v81 local exit",
            wiring.contains("chatViewModel.forgetSession(activeSession.id)") && wiring.contains("onBack()"),
        )
    }
}
