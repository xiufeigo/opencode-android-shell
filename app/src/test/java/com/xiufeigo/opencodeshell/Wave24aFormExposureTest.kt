package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsModel
import com.xiufeigo.opencodeshell.v2.ServerGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 2. Generation gating: the ⋮ menu hides the v1-only share/archive rows on
 *    anything but V1 (`V2Capability`: no v2 share/archive routes).
 */
class Wave24aFormExposureTest {

    private fun form(id: String, sessionID: String = "ses_1") =
        ChatStore.FormAsk(id = id, sessionID = sessionID, title = "t")

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

    // ------------------------------------------------- generation gating

    private fun messages(): List<ChatStore.UiMessage> = listOf(
        ChatStore.UiMessage(
            id = "msg_1",
            role = "user",
            timeCreated = 1_700_000_000_000L,
            parts = listOf(ChatStore.UiPart(id = "p1", type = "text", text = "hi")),
        ),
    )

    @Test
    fun shareAndArchiveRowsShowOnV1() {
        val state = SessionActionsModel.menu(messages(), null, null, ServerGeneration.V1)
        assertTrue(state.shareVisible)
        assertTrue(state.archiveVisible)
    }

    @Test
    fun shareAndArchiveRowsHideOnV2() {
        val state = SessionActionsModel.menu(
            messages(),
            null,
            "https://opncd.ai/share/x",
            ServerGeneration.V2,
        )
        assertFalse(state.shareVisible)
        assertFalse(state.archiveVisible)
        // The rest of the menu is generation-agnostic.
        assertTrue(state.undoEnabled)
        assertTrue(state.forkEnabled)
        assertTrue(state.shared)
    }

    @Test
    fun shareAndArchiveRowsHideWhenGenerationIsUnknown() {
        val state = SessionActionsModel.menu(messages(), null, null, ServerGeneration.UNKNOWN)
        assertFalse(state.shareVisible)
        assertFalse(state.archiveVisible)
    }

    @Test
    fun menuDefaultsToV1ForPreGenerationCallers() {
        val state = SessionActionsModel.menu(messages(), null, null)
        assertTrue(state.shareVisible)
        assertTrue(state.archiveVisible)
    }
}
