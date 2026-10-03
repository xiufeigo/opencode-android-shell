package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.SessionDeleteCard
import com.xiufeigo.opencodeshell.ui.session.SessionDeleteHost
import com.xiufeigo.opencodeshell.ui.session.SessionDeleteModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v70 P2 — the two machines disagreed (`research/v70_verify`): on a dead server
 * 5560 (phone form) kept the delete card up with 「Not deleted — the server did
 * not confirm it」, while 5556 (desktop form) lost the card — no reason, and the
 * `DELETE` that did reach the server left the tab hanging. The card's state and
 * its answer were both `SessionScreen`'s own `remember`s, so anything that
 * recomposed or re-created that screen between the tap and the answer took the
 * refusal (and the success half that closes the tab) with it.
 *
 * The fix is `SessionDeleteHost`: one card, armed by both entry points, owned by
 * `ui/App.kt` — above the screen — with the confirmed delete going back to the
 * tab set. The transitions are pure ([SessionDeleteModel]) and the host is a
 * plain object, so the contract is exercised **behaviourally** here rather than
 * as source text; the wiring that cannot run on the JVM (which composable
 * renders the card, which entry points arm it, where the tab decision lives) is
 * pinned as source facts at the bottom, the way
 * [SessionDeleteFailureV70Test] and [SessionTabDeleteSyncTest] pin theirs.
 */
class SessionDeleteFlowV70Test {

    // ------------------------------------------------ the card's rules

    @Test
    fun `asking arms one card for the session it names`() {
        val armed = SessionDeleteModel.asked(null, "ses_a", "Alpha", "/w/a")
        assertEquals(SessionDeleteCard("ses_a", "Alpha", "/w/a"), armed)
        assertFalse("a fresh card is not busy", armed!!.busy)
        assertFalse("and has no reason to show yet", armed.failed)
    }

    @Test
    fun `an empty id arms nothing`() {
        assertNull(SessionDeleteModel.asked(null, "", "Alpha", null))
        val open = SessionDeleteCard("ses_a", "Alpha")
        assertEquals("an open card is left alone", open, SessionDeleteModel.asked(open, "", "Alpha", null))
    }

    /**
     * The invariant the whole flow rests on: a card's target is pinned for the
     * whole write. A re-arm while the `DELETE` is out would let the answer land
     * on the wrong session's card.
     */
    @Test
    fun `a card cannot change its target while its write is out`() {
        val busy = SessionDeleteCard("ses_a", "Alpha", busy = true)
        assertEquals(busy, SessionDeleteModel.asked(busy, "ses_b", "Beta", null))
    }

    @Test
    fun `a second tap cannot stack a second write`() {
        assertNull("no card, no write", SessionDeleteModel.writable(null))
        val busy = SessionDeleteCard("ses_a", "Alpha", busy = true)
        assertNull("a write already out is not repeated", SessionDeleteModel.writable(busy))
    }

    @Test
    fun `the tap locks the card and clears the previous reason`() {
        val failed = SessionDeleteCard("ses_a", "Alpha", busy = false, failed = true)
        val locked = SessionDeleteModel.writable(failed)
        assertNotNull(locked)
        assertTrue("locked while the write is out", locked!!.busy)
        assertFalse("the retry starts from a clean card", locked.failed)
    }

    @Test
    fun `a refusal keeps the card up with the reason, a confirmed delete closes it`() {
        val card = SessionDeleteCard("ses_a", "Alpha")
        val refused = SessionDeleteModel.settled(card, deleted = false)
        assertNotNull("the card is the retry", refused)
        assertEquals("ses_a", refused!!.id)
        assertFalse("and it is unlocked again", refused.busy)
        assertTrue("with the reason to show", refused.failed)

        assertNull("a confirmed delete closes the card", SessionDeleteModel.settled(card, deleted = true))
    }

    /** A card the user closed while the write was out is not put back by the answer. */
    @Test
    fun `an answer cannot resurrect a dismissed card`() {
        assertNull(SessionDeleteModel.settled(null, deleted = false))
        assertNull(SessionDeleteModel.settled(null, deleted = true))
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

    @Test
    fun `the card locks while the write is out and a refusal keeps it, unlocked`() {
        val write = FakeWrite()
        val host = host(write)

        host.ask("ses_a", "Alpha", "/w/a")
        host.confirm()
        assertEquals("the row's own directory rides the write", listOf("ses_a" to "/w/a"), write.calls)
        assertEquals(true, host.card?.busy)

        // A second tap while the first `DELETE` is out used to stack another.
        host.confirm()
        assertEquals("one tap, one write", 1, write.calls.size)

        write.answer(false)
        assertEquals("the card stays on the session it named", "ses_a", host.card?.id)
        assertEquals("unlocked", false, host.card?.busy)
        assertEquals("with the reason", true, host.card?.failed)
        assertEquals("nothing was deleted, so nothing is recorded", emptySet<String>(), host.confirmed)

        // The same 删除 is a retry: the card is writable again.
        host.confirm()
        assertEquals(2, write.calls.size)
    }

    @Test
    fun `a confirmed delete closes the card and reports the id out`() {
        val write = FakeWrite()
        val confirmed = mutableListOf<String>()
        val host = host(write, confirmed)

        host.ask("ses_a", "Alpha")
        host.confirm()
        write.answer(true)

        assertNull("the card closes only on the server's answer", host.card)
        assertEquals(setOf("ses_a"), host.confirmed)
        assertEquals("the tab set hears about it exactly once", listOf("ses_a"), confirmed)
    }

    /**
     * The 5556 hang: the answer arrived after the card was gone, so the success
     * half — the part that closes the tab — never ran. Here it always runs: the
     * write's answer is the host's, not a composition's.
     */
    @Test
    fun `a confirmed delete still reports out after the card is gone`() {
        val write = FakeWrite()
        val confirmed = mutableListOf<String>()
        val host = host(write, confirmed)

        host.ask("ses_a", "Alpha")
        host.confirm()
        host.dismiss()
        assertNull(host.card)

        write.answer(true)
        assertEquals("the tab set is told even though nothing was on screen", listOf("ses_a"), confirmed)
        assertEquals(setOf("ses_a"), host.confirmed)
    }

    @Test
    fun `a refusal after a dismissal says nothing rather than reopening the card`() {
        val write = FakeWrite()
        val host = host(write)

        host.ask("ses_a", "Alpha")
        host.confirm()
        host.dismiss()
        write.answer(false)

        assertNull(host.card)
        assertEquals(emptySet<String>(), host.confirmed)
    }

    @Test
    fun `both entry points arm the same card`() {
        val host = host(FakeWrite())
        host.ask("ses_a", "Alpha")
        assertEquals("ses_a", host.card?.id)
        // The header ⋮ and a drawer row differ only in what they pass in.
        host.ask("ses_b", "Beta", "/w/b")
        assertEquals(SessionDeleteCard("ses_b", "Beta", "/w/b"), host.card)
        host.dismiss()
        assertNull(host.card)
    }

    // ------------------------------------------------ the wiring that cannot run here

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val sessionScreen =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
    private val app = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
    private val hostSource =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionDeleteHost.kt")

    @Test
    fun `the screen arms the one card from both entry points`() {
        // The header ⋮ → Delete …
        assertTrue(
            "the ⋮ arms the host's card for the session on screen",
            sessionScreen.contains("deleteHost?.ask(activeSession.id, title, activeSession.directory)"),
        )
        // … and the drawer row's long-press 删除…
        assertTrue(
            "the drawer row arms the same card for its own row",
            sessionScreen.contains("deleteHost?.ask(session.id, session.title, session.directory)"),
        )
        assertTrue(
            "the card comes from the host, not from screen state",
            sessionScreen.contains("val deleteHost = LocalSessionDeleteHost.current"),
        )
        // The two divergent triples the v69 fix had to write twice are gone: a
        // second path cannot drift from the first if there is only one.
        for (state in listOf("deleteTarget", "deleteBusy", "deleteError", "headerDeleteOpen", "headerDeleteBusy", "headerDeleteError")) {
            assertFalse("`$state` must be gone", sessionScreen.contains(state))
        }
    }

    @Test
    fun `the card renders the host's answer`() {
        val card = sessionScreen.substringAfter("deleteHost?.card?.let { card ->")
            .substringBefore("// Export progress:")
        assertTrue("busy comes from the host", card.contains("busy = card.busy,"))
        assertTrue(
            "the reason is the shared copy, and only when the server refused",
            card.contains("errorText = if (card.failed) stringResource(R.string.oc_delete_session_failed) else null"),
        )
        assertTrue("Cancel / backdrop / Escape close the host's card", card.contains("onDismiss = { deleteHost?.dismiss() }"))
        assertTrue("and 删除 starts the host's write", card.contains("onConfirm = { deleteHost?.confirm() }"))
    }

    /** The banner lives in the timeline; the card's inputs are the host's alone. */
    @Test
    fun `nothing about the timeline banner feeds the card`() {
        val card = sessionScreen.substringAfter("deleteHost?.card?.let { card ->")
            .substringBefore("// Export progress:")
        for (input in listOf("SessionErrorCard", "errorDetail", "transport", "state.messages", "incompatibleServerVersion")) {
            assertFalse("`$input` must not decide the card", card.contains(input))
        }
    }

    @Test
    fun `the shell owns the card and the tab decision it reports into`() {
        assertTrue(
            "the host is built above the session destination",
            app.contains("val sessionDeleteHost: SessionDeleteHost? = remember(chatViewModel, serverKey) {"),
        )
        assertTrue(
            "its write is the view model's delete",
            app.contains("write = { id, directory, onResult -> viewModel.deleteSession(id, directory, onResult) }"),
        )
        assertTrue("a confirmed delete reports out", app.contains("onConfirmed = forgetDeletedSessionTab,"))
        assertTrue(
            "the session route can reach it",
            app.contains("LocalSessionDeleteHost provides sessionDeleteHost,"),
        )
    }

    /**
     * 删除成功 → tab 同步关: the confirmed id goes through the one tab decision
     * (`SessionTabs.afterDeleteFromElsewhere`, v63 BUG-1's mechanism), which
     * closes the tab, purges the closed stack and moves the route to the tab
     * that takes over — no separate `onBack()`/`onSessionDeleted` pair that only
     * runs while the screen is alive.
     */
    @Test
    fun `a confirmed delete closes the tab and moves the route`() {
        val helper = app.substringAfter("val forgetDeletedSessionTab: (String) -> Unit = { id ->")
            .substringBefore("// v70 P2 (`research/v70_verify`")
        assertTrue(helper.contains("SessionTabs.afterDeleteFromElsewhere("))
        assertTrue("the closed stack is purged with the tab", helper.contains("closedTabs.addAll(result.closed)"))
        for (target in listOf("TabCloseTarget.Home ->", "is TabCloseTarget.Open ->", "TabCloseTarget.Stay ->")) {
            assertTrue("the route follows `$target`", helper.contains(target))
        }
        // The last tab's Home: back out of the deleted session's entry rather
        // than stacking a second Home over it (Back would walk back into a
        // session that no longer exists).
        val homeBranch = helper.substringAfter("TabCloseTarget.Home ->").substringBefore("is TabCloseTarget.Open ->")
        assertTrue("the deleted session's entry is popped", homeBranch.contains("navController.popBackStack()"))
        assertFalse("not a second Home entry", homeBranch.contains("navigate(Route.HOME)"))
        assertFalse(
            "the screen must not close the tab by itself any more",
            sessionScreen.contains("onSessionDeleted"),
        )
        assertTrue(
            "Home's row delete goes through the same decision",
            app.contains("if (ok) forgetDeletedSessionTab(row.id)"),
        )
    }

    @Test
    fun `the host carries the busy lock and the re-entry guard itself`() {
        assertTrue(hostSource.contains("val armed = SessionDeleteModel.writable(card) ?: return"))
        assertTrue("the card locks before the write", hostSource.contains("card = armed"))
        assertTrue(
            "and the answer is what unlocks it",
            hostSource.contains("card = SessionDeleteModel.settled(card, deleted)"),
        )
        assertTrue(
            "a confirmed id is recorded for the screen's own list",
            hostSource.contains("confirmed = confirmed + armed.id"),
        )
    }
}
