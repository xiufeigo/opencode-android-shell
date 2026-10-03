package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v81 BUG-01 (emulator-5558, blocking): airplane mode on → a send raises the
 * transport card → airplane mode off → the app does not come back. 40 s+ later
 * the error card is still on screen, the composer still refuses to send, and
 * injected keys land nowhere; only switching away from the session and back
 * recovers (`research/v81_verify/5558/t2_s05_send_offline_err.png` →
 * `t2_s06_recover_2s.png` → `t2_s08_send_after_recover.png`).
 *
 * Three independent halves, one per pinned rule:
 *
 * - the stream half — [EventStream.reconnectNow] must dial in **every** state the
 *   reconnect loop can be in (parked on the grown backoff, mid-dial, or stopped
 *   by the client-error breaker), because a poke that does nothing is the
 *   reported 40 s+;
 * - the store half — the connection coming back is itself the proof the
 *   transport cards were waiting for, so it retires them
 *   ([ChatStore.Event.TransportRecovered], the v57/v68 rule driven by the
 *   recovery instead of by a turn that has to run first);
 * - the composer half — the failed-send handback rewrites the IME's value
 *   (`restartInput`), and on the device that is where the caret went; the
 *   handback now re-asks for it inside the same bounded window the session
 *   entry uses.
 *
 * The stream behaviour is exercised against a [okhttp3.mockwebserver.MockWebServer]
 * in `NetworkFlapRecoveryTest`; this file owns the store rule, the wiring pins
 * and the composer window.
 */
class TransportRecoveryV81Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val vm = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
    private val composer =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    // ------------------------------------------------------- 1. the store rule

    /** The line a failed prompt POST carries (`Failed to connect to /127.0.0.1:4095`). */
    private val transportLine = "Failed to connect to /127.0.0.1:4095"

    private val quotaLine = "You have reached your usage limit for this plan."

    private fun text(id: String, value: String) = ChatStore.UiPart(id = id, type = "text", text = value)

    private var clock = System.currentTimeMillis() - 60_000

    private fun userRow(id: String) = ChatStore.UiMessage(
        id = id,
        role = "user",
        timeCreated = clock++,
        parts = listOf(text("$id:0", "hello")),
    )

    private fun failed(line: String): ChatStore.ChatState {
        var state = ChatStore.ChatState(sessionID = "ses_1")
        state = ChatStore.reduce(state, ChatStore.Event.MessageUpdated(userRow("msg_u1")))
        return ChatStore.reduce(
            state,
            ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.ERROR, line),
        )
    }

    @Test
    fun theFailureFixturesClassifyTheWayTheRuleReadsThem() {
        assertEquals(
            "the transport fixture must be a TRANSPORT failure",
            ChatStore.ProviderErrorKind.TRANSPORT,
            ChatStore.classifyStoredError(transportLine, null).kind,
        )
        assertTrue(
            "the quota fixture must not be one",
            ChatStore.classifyStoredError(quotaLine, null).kind != ChatStore.ProviderErrorKind.TRANSPORT,
        )
    }

    @Test
    fun theConnectionComingBackRetiresTheTransportErrorAndItsCard() {
        val before = failed(transportLine)
        assertEquals(ChatStore.RunStatus.ERROR, before.runStatus)
        assertEquals(1, before.messages.count { ChatStore.isErrorRow(it) })

        val after = ChatStore.reduce(before, ChatStore.Event.TransportRecovered)

        assertNull("the live error mirror is what the trailing card renders", after.error)
        assertNull(after.errorDetail)
        assertEquals(
            "the session is no longer in the failure posture",
            ChatStore.RunStatus.IDLE,
            after.runStatus,
        )
        assertEquals(
            "the failure row that only said 「I cannot reach the server」 retires",
            0,
            after.messages.count { ChatStore.isErrorRow(it) },
        )
        assertTrue("the transcript itself is untouched", after.messages.any { it.id == "msg_u1" })
    }

    @Test
    fun aRealFailureKeepsItsCardWhenTheLinkComesBack() {
        val before = failed(quotaLine)
        val after = ChatStore.reduce(before, ChatStore.Event.TransportRecovered)

        assertEquals("a quota is not the link", quotaLine, after.error)
        assertEquals(ChatStore.RunStatus.ERROR, after.runStatus)
        assertEquals(1, after.messages.count { ChatStore.isErrorRow(it) })
    }

    @Test
    fun recoveryIsIdempotentAndHarmlessWithNothingToRetire() {
        val clean = ChatStore.ChatState(sessionID = "ses_1")
        assertEquals(clean, ChatStore.reduce(clean, ChatStore.Event.TransportRecovered))
        val once = ChatStore.reduce(failed(transportLine), ChatStore.Event.TransportRecovered)
        assertEquals(once, ChatStore.reduce(once, ChatStore.Event.TransportRecovered))
    }

    // ------------------------------------------------------- 2. the wiring pins

    @Test
    fun theGlobalStreamsOwnRecoveryRetiresTheTransportCards() {
        val listener = vm.substringAfter("val global = EventStream(")
            .substringBefore("eventStream = global")
        assertTrue(
            "the establish branch is where the proof lands",
            listener.contains("refreshSnapshot()"),
        )
        assertTrue(
            "the recovery event rides it",
            listener.contains("applyEvent(ChatStore.Event.TransportRecovered)"),
        )
        assertTrue(
            "the store write hops to Main like every other applier",
            listener.contains("scope.launch"),
        )
        // The v57 rule itself stays where it was: a `running` status still
        // retires transport rows (ChatStore.reduce), so the two proofs agree.
        val store = source("src/main/java/com/xiufeigo/opencodeshell/ChatStore.kt")
        assertTrue(store.contains("event.status == RunStatus.RUNNING -> dropStaleTransportRows(state.messages)"))
    }

    @Test
    fun aHealWithNoStreamRebuildsItInsteadOfPokingNothing() {
        val heal = vm.substringAfter("    fun onNetworkAvailable() {")
            .substringBefore("    /** Publishes [failure]")
        assertTrue("a live stream is poked", heal.contains("global.reconnectNow()"))
        assertTrue("the session log follows", heal.contains("sessionLogStream?.reconnectNow()"))
        assertTrue(
            "no stream at all is rebuilt through the one opener",
            heal.contains("openV2Streams(server)"),
        )
        assertTrue(
            "…and only while this view model is started (the screen's `start()` owns them)",
            heal.contains("started.get()"),
        )
        assertFalse(
            "the old branch poked null and reported success",
            heal.contains("eventStream?.reconnectNow()"),
        )
    }

    // ---------------------------------------------------- 3. the composer window

    /** The handback caret window, from its launch to the restore effect. */
    private val handbackWindow = composer
        .substringAfter("LaunchedEffect(handbackTick) {")
        .substringBefore("LaunchedEffect(draftKey, restoredPending) {")

    @Test
    fun theFailedSendHandbackReAsksForTheCaret() {
        assertTrue(
            "the restore effect is what rewrites the IME's value — it arms the window",
            composer.substringAfter("LaunchedEffect(draftKey, restoredPending) {")
                .substringBefore("// P1-C: flush the live editor text")
                .contains("handbackTick += 1"),
        )
        assertTrue("the window exists", handbackWindow.isNotEmpty())
        assertTrue(
            "it is the same bounded window as the entry handoff",
            handbackWindow.contains("delay(ComposerEntryFocus.RETRY_DELAY_MS)") &&
                handbackWindow.contains("if (!ComposerEntryFocus.retries(attempt)) return@LaunchedEffect"),
        )
        assertTrue(
            "a field that already holds the caret ends it — the healthy case does nothing",
            handbackWindow.contains("if (editorFocused) return@LaunchedEffect"),
        )
        assertTrue(
            "the v74 terminal yield still applies",
            handbackWindow.contains("if (terminalImeFocus?.holdsKeyboard == true) return@LaunchedEffect"),
        )
        assertFalse(
            "no `show()`: the IME session is already up and a repeat show is a fresh restartInput (v66)",
            handbackWindow.contains("keyboardController?.show()"),
        )
    }

    @Test
    fun theHandbackWindowIsGatedOnItsOwnOneShot() {
        assertTrue(
            "keyed on the tick, never on `restoredPending` — that flag is consumed in the same pass, so an effect keyed on it would be cancelled mid-window",
            composer.contains("LaunchedEffect(handbackTick) {"),
        )
        assertTrue(
            "and a fresh composer (a session switch) starts at 0",
            composer.contains("var handbackTick by remember(draftKey) { mutableStateOf(0) }"),
        )
    }
}
