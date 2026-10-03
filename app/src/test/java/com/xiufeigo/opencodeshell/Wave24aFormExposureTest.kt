package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsMenuItem
import com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WAVE 2.4a exposure + gating contracts.
 *
 * 1. Form store→VM exposure: `ChatViewModel.pendingForm` publishes
 *    `state.pendingForms.firstOrNull()` (session-scoped, arrival-ordered), so
 *    the store list below is exactly what the dock receives. (The ViewModel
 *    itself cannot be constructed on the JVM — `Dispatchers.Main` — so the
 *    contract is pinned at the store level, plus the transport reply wire.)
 * 2. v2-only: the ⋮ menu carries undo/redo/fork/move only — share/archive hit v1
 *    routes that 404 on v2, so the rows are removed outright. MOVE (F-4) joined
 *    later from upstream's workspace menu, which Android does not render.
 */
class Wave24aFormExposureTest {

    private fun form(id: String, sessionID: String = "ses_1") =
        ChatStore.FormAsk(id = id, sessionID = sessionID, title = "t")

    private fun messages(): List<ChatStore.UiMessage> = listOf(
        ChatStore.UiMessage(
            id = "msg_1",
            role = "user",
            timeCreated = 1_700_000_000_000L,
            parts = listOf(ChatStore.UiPart(id = "p1", type = "text", text = "hi")),
        ),
    )

    // ------------------------------------------------- store→VM exposure

    @Test
    fun pendingFormExposureIsFirstPendingOfTheTrackedSession() {
        var state = ChatStore.ChatState(sessionID = "ses_1")
        // Arrival order decides: the dock shows frm_1 while both pend.
        state = ChatStore.reduce(state, ChatStore.Event.FormCreated(form("frm_1")))
        state = ChatStore.reduce(state, ChatStore.Event.FormCreated(form("frm_2")))
        assertEquals("frm_1", state.pendingForms.firstOrNull()?.id)

        // Resolving the first promotes the second — the dock follows.
        state = ChatStore.reduce(state, ChatStore.Event.FormResolved("frm_1"))
        assertEquals("frm_2", state.pendingForms.firstOrNull()?.id)

        state = ChatStore.reduce(state, ChatStore.Event.FormResolved("frm_2"))
        assertEquals(null, state.pendingForms.firstOrNull())
    }

    @Test
    fun foreignAndGlobalFormsNeverReachTheExposure() {
        var state = ChatStore.ChatState(sessionID = "ses_1")
        state = ChatStore.reduce(state, ChatStore.Event.FormCreated(form("frm_x", "ses_9")))
        state = ChatStore.reduce(state, ChatStore.Event.FormCreated(form("frm_g", "global")))
        assertTrue(state.pendingForms.isEmpty())
        assertEquals(null, state.pendingForms.firstOrNull())
    }

    @Test
    fun formSnapshotMergesTheAuthoritativeList() {
        var state = ChatStore.reduce(
            ChatStore.ChatState(sessionID = "ses_1"),
            ChatStore.Event.FormCreated(form("frm_stale")),
        )
        state = ChatStore.reduce(
            state,
            ChatStore.Event.FormSnapshot(listOf(form("frm_a"), form("frm_b"))),
        )
        assertEquals(listOf("frm_a", "frm_b"), state.pendingForms.map { it.id })
        assertEquals("frm_a", state.pendingForms.firstOrNull()?.id)
    }

    // ------------------------------------------------- share/archive removed

    @Test
    fun shareAndArchiveRowsAreGoneWithV2Only() {
        val state = SessionActionsModel.menu(messages(), null)
        assertTrue(state.undoEnabled)
        assertTrue(state.forkEnabled)
        assertTrue(state.moveEnabled)
        // v2 has no share/archive routes — the rows and their state flags are
        // removed outright rather than hidden. F-4 added MOVE to the same menu,
        // so the pin is four rows; the invariant below is that share/archive
        // are still absent.
        assertEquals(listOf("UNDO", "REDO", "FORK", "MOVE"), SessionActionsMenuItem.entries.map { it.name })
        assertTrue(SessionActionsMenuItem.entries.none { it.name == "SHARE" || it.name == "ARCHIVE" })
    }
}
