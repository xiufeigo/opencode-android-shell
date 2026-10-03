package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.actions.RevertStep
import com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The composer handoff a revert owes the user
 * (`_upstream/packages/app/src/session/revert.ts:31-57` `restore`).
 *
 * `revert.to(messageID)` drops the named user message **and** everything after
 * it — that part is upstream-faithful and the intentional「撤销=破坏性」deviation
 * covers it. What upstream additionally does, and this app did not, is put the
 * dropped prompt back into the composer. The v60 report is the consequence:
 * `research/v60_verify/5558/10_reply_done.png` → `11_retry.png`, tapping the ↺
 * on 「say hello」 left only the 「模型已切换」 divider, with nothing left to
 * re-send.
 */
class RevertComposerRestoreTest {

    private fun text(id: String) = ChatStore.UiPart(id = "$id-text", type = "text", text = "say hello")

    private fun dataFile(id: String, url: String) = ChatStore.UiPart(
        id = "$id-file",
        type = "file",
        mime = "image/png",
        filename = "shot.png",
        url = url,
    )

    private fun message(id: String, role: String = "user", vararg parts: ChatStore.UiPart) =
        ChatStore.UiMessage(id = id, role = role, timeCreated = 0L, parts = parts.toList())

    private val transcript = listOf(
        message("msg_01", parts = arrayOf(text("msg_01"))),
        message("msg_02", parts = arrayOf(text("msg_02"))),
        message("msg_asst", role = "assistant", parts = arrayOf(text("msg_asst"))),
    )

    @Test
    fun revertingAMessageHandsItsPromptBackToTheComposer() {
        val restore = SessionActionsModel.revertRestore(transcript, "msg_01")
        assertEquals("say hello", restore?.text)
        assertEquals(emptyList<SessionActionsModel.RevertFile>(), restore?.files)
    }

    @Test
    fun theAssistantTurnIsNotWhatComesBack() {
        // The boundary hides the tail; the composer is only owed the *user*
        // prompt, so an assistant id is read from the same list but is not a
        // revert target (SessionActionsModel.revertToStep rejects it first).
        val restore = SessionActionsModel.revertRestore(transcript, "msg_asst")
        assertNull(SessionActionsModel.revertToStep(SessionActionsModel.userMessageIDs(transcript), "msg_asst"))
        assertEquals("say hello", restore?.text)
    }

    @Test
    fun inlineDataAttachmentsRideBackToo() {
        val url = "data:image/png;base64,AAA"
        val restore = SessionActionsModel.revertRestore(
            listOf(message("msg_01", parts = arrayOf(text("msg_01"), dataFile("msg_01", url)))),
            "msg_01",
        )
        assertEquals(
            listOf(SessionActionsModel.RevertFile("image/png", "shot.png", url)),
            restore?.files,
        )
    }

    @Test
    fun workspaceFileCommentsDoNotComeBackAsAttachments() {
        // A `@`-referenced workspace file is stored as a `file://` url; the
        // composer needs the bytes to re-attach it, which the revert path does
        // not read. The text still comes home.
        val restore = SessionActionsModel.revertRestore(
            listOf(
                message(
                    "msg_01",
                    parts = arrayOf(
                        text("msg_01"),
                        ChatStore.UiPart(
                            id = "msg_01-file",
                            type = "file",
                            mime = "text/plain",
                            filename = "notes.md",
                            url = "file:///work/notes.md",
                        ),
                    ),
                ),
            ),
            "msg_01",
        )
        assertEquals("say hello", restore?.text)
        assertEquals(emptyList<SessionActionsModel.RevertFile>(), restore?.files)
    }

    @Test
    fun anUnknownOrEmptyIdHandsBackNothing() {
        assertNull(SessionActionsModel.revertRestore(transcript, "msg_404"))
        assertNull(SessionActionsModel.revertRestore(transcript, ""))
        assertNull(SessionActionsModel.revertRestore(emptyList(), "msg_01"))
    }

    @Test
    fun anEmptyPromptHandsBackNothing() {
        // No text part and no data: file — there is nothing to put in the
        // composer, and `restoreHandoff` treats an empty write as a no-op.
        assertNull(
            SessionActionsModel.revertRestore(
                listOf(
                    message(
                        "msg_01",
                        parts = arrayOf(
                            ChatStore.UiPart(
                                id = "msg_01-file",
                                type = "file",
                                mime = "text/plain",
                                filename = "notes.md",
                                url = "file:///work/notes.md",
                            ),
                        ),
                    ),
                ),
                "msg_01",
            ),
        )
    }

    @Test
    fun theHandoffIsNotAReSend() {
        // The boundary still lands on the message — the row leaves the
        // transcript, exactly as upstream's `revert.to` does — so the composer
        // is the only thing carrying the prompt afterwards.
        val users = SessionActionsModel.userMessageIDs(transcript)
        assertEquals(
            RevertStep.RevertTo("msg_01"),
            SessionActionsModel.revertToStep(users, "msg_01"),
        )
        assertEquals(emptyList<String>(), SessionActionsModel.visibleUserMessageIDs(users, "msg_01"))
        assertEquals("say hello", SessionActionsModel.revertRestore(transcript, "msg_01")?.text)
    }
}
