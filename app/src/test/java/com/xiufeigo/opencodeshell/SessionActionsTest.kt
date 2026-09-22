package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.actions.ForkMessage
import com.xiufeigo.opencodeshell.ui.session.actions.RevertStep
import com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6 session actions: the pure rules of
 * `_upstream/packages/app/src/session/revert.ts` (`:92-99` undo, `:101-119` redo),
 * `session/session-domain.ts:16-18` (visible set), `timeline/controller-projection.ts:25`
 * (hidden tail), `session/commands/use-session-commands.tsx:261,269,292` (enablement)
 * and `session/commands/fork-dialog.tsx:38-56` (picker rows).
 *
 * The server side of these actions was verified live on 1.18.31 (see
 * `research/final_visual_fix.md` §8.4): revert stores `revert.messageID`,
 * unrevert clears it, fork returns the new session, archive is
 * `PATCH time.archived`, and **`DELETE /session/:id/share` returns the same URL**
 * — hence `ChatViewModel` clears the share URL itself and `menu()` derives
 * `shared` from the local flow rather than from that response.
 */
class SessionActionsTest {

    // --------------------------------------------------------------- fixtures

    private fun user(id: String, text: String, created: Long = 1_700_000_000_000L) = ChatStore.UiMessage(
        id = id,
        role = "user",
        timeCreated = created,
        parts = listOf(ChatStore.UiPart(id = "$id-p", type = "text", text = text)),
    )

    private fun assistant(id: String, text: String = "ok") = ChatStore.UiMessage(
        id = id,
        role = "assistant",
        timeCreated = 1_700_000_000_000L,
        parts = listOf(ChatStore.UiPart(id = "$id-p", type = "text", text = text)),
    )

    private val u1 = "msg_01"
    private val u2 = "msg_02"
    private val u3 = "msg_03"
    private val a1 = "msg_01a"
    private val a2 = "msg_02a"

    // ------------------------------------------------------- boundary filters

    @Test
    fun withoutABoundaryEverythingIsVisible() {
        val ids = listOf(u1, u2, u3)
        assertEquals(ids, SessionActionsModel.visibleUserMessageIDs(ids, null))
        assertEquals(ids, SessionActionsModel.visibleUserMessageIDs(ids, ""))
        assertTrue(SessionActionsModel.hiddenMessageIDs(listOf(u1, a1, u2, a2), null).isEmpty())
    }

    @Test
    fun aBoundaryHidesItsOwnMessageAndEverythingAfterIt() {
        val users = listOf(u1, u2, u3)
        assertEquals(listOf(u1), SessionActionsModel.visibleUserMessageIDs(users, u2))
        // The hidden set covers *every* message at or after the boundary, not just
        // user ones — the timeline projection is `message.id >= boundary`.
        val all = listOf(u1, a1, u2, a2, u3)
        assertEquals(listOf(u2, a2, u3), SessionActionsModel.hiddenMessageIDs(all, u2))
    }

    // --------------------------------------------------------- undo / redo

    @Test
    fun undoRewindsTheLastUserMessageWhenNothingIsReverted() {
        assertEquals(u3, SessionActionsModel.undoStep(listOf(u1, u2, u3), null))
        assertEquals(u2, SessionActionsModel.undoStep(listOf(u1, u2, u3), u3))
        assertEquals(u1, SessionActionsModel.undoStep(listOf(u1, u2, u3), u2))
    }

    @Test
    fun undoIsUnavailableAtTheStartOrWithoutUserMessages() {
        assertNull(SessionActionsModel.undoStep(emptyList(), null))
        // boundary 0 → `if (boundary <= 0) return`
        assertNull(SessionActionsModel.undoStep(listOf(u1, u2), u1))
        // A boundary that is no longer in the snapshot window is not index 0 and
        // must not silently rewind from the end.
        assertNull(SessionActionsModel.undoStep(listOf(u2, u3), "msg_gone"))
    }

    @Test
    fun redoMovesTheBoundaryForwardAndEndsWithUnrevert() {
        assertNull(SessionActionsModel.redoStep(listOf(u1, u2, u3), null))
        assertEquals(
            RevertStep.RevertTo(u3),
            SessionActionsModel.redoStep(listOf(u1, u2, u3), u2),
        )
        // Past the last user message the only step left is `revert.clear`.
        assertEquals(
            RevertStep.Unrevert,
            SessionActionsModel.redoStep(listOf(u1, u2, u3), u3),
        )
        assertNull(SessionActionsModel.redoStep(listOf(u1, u2), "msg_gone"))
    }

    // ---------------------------------------------------------- enablement

    @Test
    fun menuEnablementFollowsTheVisibleSetAndTheRevertBoundary() {
        val messages = listOf(user(u1, "one"), assistant(a1), user(u2, "two"), assistant(a2))

        val fresh = SessionActionsModel.menu(messages, revertMessageID = null, shareUrl = null)
        assertTrue("there is a user message to rewind to", fresh.undoEnabled)
        assertTrue("nothing is reverted yet", !fresh.redoEnabled)
        assertTrue("the picker has rows", fresh.forkEnabled)
        assertFalse(fresh.shared)

        val reverted = SessionActionsModel.menu(messages, revertMessageID = u2, shareUrl = null)
        assertTrue(reverted.undoEnabled)
        assertTrue(reverted.redoEnabled)
        assertTrue(reverted.forkEnabled)

        // Everything visible is reverted away → both undo and fork go away, but
        // 重做 stays available (that is the way back).
        val allHidden = SessionActionsModel.menu(messages, revertMessageID = u1, shareUrl = null)
        assertFalse(allHidden.undoEnabled)
        assertFalse(allHidden.forkEnabled)
        assertTrue(allHidden.redoEnabled)
    }

    @Test
    fun shareRowIsSelectedOnlyWhileAUrlIsKnown() {
        val messages = listOf(user(u1, "one"))
        assertTrue(
            SessionActionsModel.menu(messages, null, "https://opncd.ai/share/x").shared,
        )
        // The Android form clears its own flow after unshare (the DELETE response
        // still carries the old URL), so an empty/blank value must read as "not shared".
        assertFalse(SessionActionsModel.menu(messages, null, null).shared)
        assertFalse(SessionActionsModel.menu(messages, null, "").shared)
    }

    @Test
    fun anEmptySessionDisablesUndoForkAndRedo() {
        val state = SessionActionsModel.menu(emptyList(), revertMessageID = null, shareUrl = null)
        assertFalse(state.undoEnabled)
        assertFalse(state.redoEnabled)
        assertFalse(state.forkEnabled)
    }

    // ---------------------------------------------------------- fork picker

    @Test
    fun forkPickerKeepsOnlyUserMessagesWithTextNewestFirst() {
        val messages = listOf(
            user(u1, "first", created = 1),
            assistant(a1),
            user(u2, "second\nline", created = 2),
            ChatStore.UiMessage(
                id = u3,
                role = "user",
                timeCreated = 3,
                parts = listOf(ChatStore.UiPart(id = "p", type = "file", text = "")),
            ),
        )
        val rows = SessionActionsModel.forkMessages(messages)
        assertEquals(
            listOf(ForkMessage(u2, "second line", 2), ForkMessage(u1, "first", 1)),
            rows,
        )
    }

    @Test
    fun forkPickerClipsLongPromptsAfterSingleLiningThem() {
        val long = "x".repeat(SessionActionsModel.FORK_TEXT_LIMIT + 40)
        val rows = SessionActionsModel.forkMessages(listOf(user(u1, "$long\n$long")))
        assertEquals(1, rows.size)
        assertEquals(SessionActionsModel.FORK_TEXT_LIMIT, rows[0].text.length)
        assertFalse("newlines are gone before the cap is applied", rows[0].text.contains('\n'))
    }

    @Test
    fun messageTextJoinsOnlyNonEmptyTextParts() {
        val message = ChatStore.UiMessage(
            id = u1,
            role = "user",
            timeCreated = 1,
            parts = listOf(
                ChatStore.UiPart(id = "a", type = "text", text = "alpha"),
                ChatStore.UiPart(id = "b", type = "tool", text = ""),
                ChatStore.UiPart(id = "c", type = "text", text = "beta"),
            ),
        )
        assertEquals("alpha\nbeta", SessionActionsModel.messageText(message))
    }
}
