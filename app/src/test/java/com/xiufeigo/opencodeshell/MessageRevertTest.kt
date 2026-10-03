package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.actions.RevertStep
import com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Message-level revert (`session/revert.ts:85-119` `to`, `message-content.tsx:328-338`):
 * the「撤销到此」entry stages the boundary at the named **user** message.
 */
class MessageRevertTest {

    private val users = listOf("msg_01", "msg_02", "msg_03")

    @Test
    fun revertToStagesTheNamedUserMessage() {
        assertEquals(RevertStep.RevertTo("msg_02"), SessionActionsModel.revertToStep(users, "msg_02"))
        assertEquals(RevertStep.RevertTo("msg_01"), SessionActionsModel.revertToStep(users, "msg_01"))
    }

    @Test
    fun revertToIsANoOpOutsideTheUserList() {
        // Upstream's `to()` looks the id up in `history.userMessages()` and
        // returns without a request when it is missing (`revert.ts:85-89`).
        assertNull(SessionActionsModel.revertToStep(users, "msg_assistant"))
        assertNull(SessionActionsModel.revertToStep(users, ""))
        assertNull(SessionActionsModel.revertToStep(emptyList(), "msg_01"))
    }

    @Test
    fun messageLevelAndSessionLevelStepsAgreeOnTheBoundary() {
        // 撤销 targets `boundary - 1`; the per-message button on that same
        // message must stage exactly that id (`revert.ts:92-99` vs `:85-119`).
        val undoTarget = SessionActionsModel.undoStep(users, revertMessageID = null)
        assertEquals("msg_03", undoTarget)
        assertEquals(RevertStep.RevertTo(undoTarget!!), SessionActionsModel.revertToStep(users, undoTarget))
    }
}
