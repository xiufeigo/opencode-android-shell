package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v76 (emulator-5558, `research/v76_verify/5558/10..17`): the variant taps an
 * **empty** session received come back from the server after a cold start.
 *
 * The measured run: three variant taps on a brand-new session (the transcript
 * correctly stayed empty — `13_after_low.png`), a force-stop, the session
 * reopened (`16_variant_persist.png`: still empty) and the first prompt sent —
 * which opened with **three** 「Model changed」 rows stacked above the user
 * bubble (`17_stream.png`). Every tap persists a durable `model-switched` row
 * server-side (`Session.switchModel`, `session/session.ts:90-101`), so a
 * cold-start snapshot hands them all back; v74's gate
 * ([ChatStore.withoutPreContentModelNotices] before this round) only asked
 * "is there a `user` row yet", and the first prompt is exactly what supplies
 * one.
 *
 * The rule is therefore ordered, not conditional: a `model-switched` notice
 * written while the transcript held nothing but notice chrome never renders,
 * at any later time. A switch made once the session has real content is
 * untouched — that is the PASS behavior of the same round ("switching the
 * variant after the first prompt renders its marker", `21_expand.png`), and
 * the live path is pinned in [ModelSwitchNoticeV69Test] and [ChatStoreTest].
 */
class ModelSwitchColdStartReplayV76Test {

    // ------------------------------------------------------------- fixtures

    private fun modelNotice(id: String, time: Long) = ChatStore.UiMessage(
        id = id,
        role = "assistant",
        timeCreated = time,
        parts = listOf(ChatStore.UiPart(id = "$id:notice", type = "model-switched")),
    )

    private fun chrome(id: String, type: String, time: Long) = ChatStore.UiMessage(
        id = id,
        role = "assistant",
        timeCreated = time,
        parts = listOf(ChatStore.UiPart(id = "$id:notice", type = type)),
    )

    private fun user(id: String, time: Long) = ChatStore.UiMessage(
        id = id,
        role = "user",
        timeCreated = time,
        parts = listOf(ChatStore.UiPart(id = "$id:text", type = "text", text = "count to 100")),
    )

    private fun assistant(id: String, time: Long, model: String? = null) = ChatStore.UiMessage(
        id = id,
        role = "assistant",
        timeCreated = time,
        model = model,
        parts = listOf(ChatStore.UiPart(id = "$id:text", type = "text", text = "1, 2, 3")),
    )

    /** The parked-steer handoff row (`ChatStore.PENDING_ROLE`): a prompt, not chrome. */
    private fun pending(id: String, time: Long) = ChatStore.UiMessage(
        id = id,
        role = ChatStore.PENDING_ROLE,
        timeCreated = time,
        parts = listOf(ChatStore.UiPart(id = "$id:text", type = "text", text = "queued")),
    )

    private fun snapshot(vararg rows: ChatStore.UiMessage): ChatStore.ChatState =
        ChatStore.reduce(ChatStore.ChatState(), ChatStore.Event.Snapshot(rows.toList()))

    private fun ids(state: ChatStore.ChatState): List<String> = state.messages.map { it.id }

    private fun live(
        state: ChatStore.ChatState,
        row: ChatStore.UiMessage,
    ): ChatStore.ChatState = ChatStore.reduce(state, ChatStore.Event.MessageUpdated(row))

    // ------------------------------------------------------- the replays

    @Test
    fun theVariantRowsOfAnEmptySessionDoNotReplayOverTheFirstPrompt() {
        // The measured cold start, in order: three durable variant rows
        // (high/max/low — the times the taps were made), the first prompt, and
        // the assistant turn that followed it. Only the turn may render.
        val s = snapshot(
            modelNotice("msg_high", 1L),
            modelNotice("msg_max", 2L),
            modelNotice("msg_low", 3L),
            user("msg_u1", 4L),
            assistant("msg_a1", 5L, model = "opencode-go/glm-5.3-flash"),
        )
        assertEquals(listOf("msg_u1", "msg_a1"), ids(s))
    }

    @Test
    fun aSwitchAfterTheFirstPromptStillRenders() {
        // The control group of the same round: by then the session has content,
        // so the marker is a real timeline separator and must stay.
        val s = snapshot(
            user("msg_u1", 1L),
            assistant("msg_a1", 2L),
            modelNotice("msg_sw", 3L),
        )
        assertEquals(listOf("msg_u1", "msg_a1", "msg_sw"), ids(s))
    }

    @Test
    fun theLivePathStillRendersASwitchOntoATranscript() {
        val seeded = snapshot(user("msg_u1", 1L), assistant("msg_a1", 2L))
        assertEquals(
            listOf("msg_u1", "msg_a1", "msg_sw"),
            ids(live(seeded, modelNotice("msg_sw", 3L))),
        )
    }

    @Test
    fun theCountNoLongerFlickersAcrossSnapshots() {
        // 5558 measured 3 / 1 / 3 across renders. The rows were dropped only
        // while the store happened to hold no `user` row — a pending prompt, an
        // in-flight delivery or a re-entry flipped that between snapshots. Now
        // they never enter the store, so the count is one value on every path:
        // empty session, the prompt's own snapshot (the user row still in the
        // inbox — v2 has no user-message frame), and the transcript after it.
        var s = snapshot(
            modelNotice("msg_high", 1L),
            modelNotice("msg_max", 2L),
            modelNotice("msg_low", 3L),
        )
        assertEquals(emptyList<String>(), ids(s))

        s = snapshot(
            modelNotice("msg_high", 1L),
            modelNotice("msg_max", 2L),
            modelNotice("msg_low", 3L),
            user("msg_u1", 4L),
            assistant("msg_a1", 5L),
        )
        assertEquals(listOf("msg_u1", "msg_a1"), ids(s))

        // A repeat of the same snapshot adds nothing (the merge keeps what is
        // stored) …
        s = ChatStore.reduce(
            s,
            ChatStore.Event.Snapshot(
                listOf(
                    modelNotice("msg_high", 1L),
                    modelNotice("msg_max", 2L),
                    modelNotice("msg_low", 3L),
                    user("msg_u1", 4L),
                    assistant("msg_a1", 5L),
                ),
            ),
        )
        assertEquals(listOf("msg_u1", "msg_a1"), ids(s))

        // … and a snapshot that predates the delivered user row (only the
        // durable notice rows are in it) cannot take the turn away either.
        s = ChatStore.reduce(
            s,
            ChatStore.Event.Snapshot(
                listOf(
                    modelNotice("msg_high", 1L),
                    modelNotice("msg_max", 2L),
                    modelNotice("msg_low", 3L),
                ),
            ),
        )
        assertEquals(listOf("msg_u1", "msg_a1"), ids(s))
    }

    // --------------------------------------------------- where the window ends

    @Test
    fun noticeChromeAloneDoesNotCloseTheWindow() {
        // An agent pick in the same empty session is chrome too: the window is
        // about the session having no content, not about which picker wrote a
        // row. The model rows stay out, the other notice stays in.
        val s = snapshot(
            modelNotice("msg_high", 1L),
            chrome("msg_ag", "agent-switched", 2L),
            modelNotice("msg_low", 3L),
            user("msg_u1", 4L),
        )
        assertEquals(listOf("msg_ag", "msg_u1"), ids(s))
    }

    @Test
    fun aShellRowClosesTheWindow() {
        // Upstream gives a shell row a turn of its own
        // (`timeline/projection.ts:187-190`), so it is content: a model switch
        // after it is a switch onto a transcript and renders.
        val s = snapshot(
            chrome("msg_shell", "shell", 1L),
            modelNotice("msg_sw", 2L),
        )
        assertEquals(listOf("msg_shell", "msg_sw"), ids(s))
    }

    @Test
    fun aParkedPromptClosesTheWindow() {
        // The handoff row a send leaves behind is the user's own prompt, not
        // chrome (`composer/submit.ts:97`): a switch written after it belongs to
        // the turn being submitted and renders. (The delivered row reuses the
        // parked id — `upsert`'s client-row hand-over, a separate contract — so
        // the two carry different ids here to keep the boundary in view.)
        val s = snapshot(
            pending("msg_p1", 1L),
            modelNotice("msg_sw", 2L),
            user("msg_u1", 3L),
        )
        assertEquals(listOf("msg_p1", "msg_sw", "msg_u1"), ids(s))
    }

    @Test
    fun aSwitchFromBeforeTheParkedPromptStaysOut() {
        // The other side of the same boundary: the picker tap *before* the
        // prompt was admitted happened in the empty-session window.
        val s = snapshot(
            modelNotice("msg_pin", 1L),
            pending("msg_p1", 2L),
            user("msg_u1", 3L),
        )
        assertEquals(listOf("msg_p1", "msg_u1"), ids(s))
    }

    @Test
    fun everyOtherNoticeTypeSurvivesTheModelRule() {
        // The sweep names `model-switched` only: the notice chrome a session can
        // legitimately carry before its first prompt (a location switch after a
        // Move to…, a system line) is not the row this round is about.
        val s = snapshot(
            chrome("msg_loc", "location-switched", 1L),
            chrome("msg_sys", "system", 2L),
            chrome("msg_skill", "skill", 3L),
            chrome("msg_syn", "synthetic", 4L),
            modelNotice("msg_sw", 5L),
            user("msg_u1", 6L),
        )
        assertEquals(
            listOf("msg_loc", "msg_sys", "msg_skill", "msg_syn", "msg_u1"),
            ids(s),
        )
        assertTrue(s.messages.none { message -> message.parts.any { it.type == "model-switched" } })
    }
}
