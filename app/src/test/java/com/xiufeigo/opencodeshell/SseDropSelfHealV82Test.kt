package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.TransportRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v82 BUG-C (emulator-5558, high): SSE 掉线不自愈 — 错误卡 「Not connected」 常驻,
 * 会话卡流式态 >120 s, 只有手动 Stop/重发才刷新.
 *
 * The device evidence for the two halves:
 *
 * - the card: `research/v82_verify/5558/45_reg_sent.png` → `50_reg_wait120.png`
 *   show the failure row 「Not connected…」 from the v76 failed-send handback
 *   (`ChatViewModel.NOT_CONNECTED_LINE`) still on screen minutes after REST
 *   answers came back (`09:05:29 form list failed: HTTP 500` follows the
 *   `09:05:03 … after 10000ms` connect timeout, and the turn after that one
 *   completed);
 * - the stuck turn: `50_reg_wait120.png` — the composer still on **Stop** 120 s
 *   after the reply had landed.
 *
 * Three rules are pinned here, one per hole the round found:
 *
 * 1. **The shell's own 「Not connected — …」 family is a link statement.** The
 *    whole recovery apparatus keys on the classifier
 *    ([ChatStore.ProviderErrorKind.TRANSPORT]); without these sentences the
 *    recovery event retired nothing, because `CONNECTION_PHRASES` only knew what
 *    OkHttp writes. The mid-sentence case below is the guard rail: a *provider*
 *    message that merely mentions the phrase keeps its own card.
 * 2. **A fresh V2 probe verdict is a recovery carrier too.** While there is no
 *    transport there are no streams, so the v81 carrier (`ESTABLISHED`) can
 *    never fire for the very card a not-connected send raises.
 * 3. **No transport is an unanswerable probe, not a skipped tick.** The idle
 *    watchdog already documents that policy for `null`
 *    (`TurnIdleWatchdog.onProbe`); skipping the tick instead left the composer
 *    locked for as long as the user left it alone.
 */
class SseDropSelfHealV82Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val vm = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
    private val chatStore = source("src/main/java/com/xiufeigo/opencodeshell/ChatStore.kt")
    private val retry = source("src/main/java/com/xiufeigo/opencodeshell/v2/TransportRetry.kt")
    private val toolRenderers =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/ToolRenderers.kt")

    // --------------------------------------- 1. the client-authored family

    /**
     * Every 「Not connected…」 sentence a source writes, harvested from the code
     * so a new one cannot be added without joining the vocabulary.
     */
    private fun clientNotConnectedLines(vararg files: String): List<String> {
        val pattern = Regex("\"(Not connected[^\"]*)\"")
        return files.flatMap { pattern.findAll(it).map { m -> m.groupValues[1] } }
            .distinct()
            .sorted()
    }

    @Test
    fun everyClientNotConnectedSentenceCountsAsTheLink() {
        val vmLines = clientNotConnectedLines(vm)
        val rendererLines = clientNotConnectedLines(toolRenderers)
        assertEquals(
            "ToolRenderers localises the fixed table: the four `setGeneration` sentences plus the " +
                "v76 failed-send line",
            5,
            rendererLines.size,
        )
        for (line in rendererLines) {
            assertTrue(
                "the display table may only carry sentences the view model raises: $line",
                vmLines.contains(line),
            )
        }
        assertTrue(
            "`createSession`'s fail-fast (\"Not connected to an OpenCode 2.x server.\") is the " +
                "sixth member of the family",
            vmLines.any { it == "Not connected to an OpenCode 2.x server." },
        )
        for (line in clientNotConnectedLines(vm, toolRenderers)) {
            assertTrue("$line must be a link statement", TransportRetry.isClientNotConnectedText(line))
            assertEquals(
                "$line must classify as TRANSPORT (the recovery predicate)",
                ChatStore.ProviderErrorKind.TRANSPORT,
                ChatStore.classifyStoredError(line, null).kind,
            )
        }
    }

    @Test
    fun thePrefixRuleIsNotASubstringRule() {
        assertTrue(TransportRetry.isClientNotConnectedText("  not connected — the server did not answer.  "))
        // A provider's own words keep their own card even when they mention it.
        assertFalse(TransportRetry.isClientNotConnectedText("upstream not connected to the model host"))
        assertFalse(TransportRetry.isClientNotConnectedText(null))
        assertFalse(TransportRetry.isClientNotConnectedText("   "))
        assertFalse(
            "mid-sentence mentions stay out of the vocabulary",
            TransportRetry.isConnectionFailureText("upstream not connected to the model host"),
        )
    }

    // ------------------------------------------------- 2. the card retires

    /** The v76 failed-send line — the card the v82 screenshots are looking at. */
    private val notConnectedLine =
        "Not connected — open the Terminal tab once to load the server config."

    private fun text(id: String, value: String) = ChatStore.UiPart(id = id, type = "text", text = value)

    private fun userRow(id: String) = ChatStore.UiMessage(
        id = id,
        role = "user",
        timeCreated = 1_000L,
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
    fun theLinkComingBackRetiresTheNotConnectedCardToo() {
        val before = failed(notConnectedLine)
        assertEquals(ChatStore.RunStatus.ERROR, before.runStatus)
        assertEquals(1, before.messages.count { ChatStore.isErrorRow(it) })

        val after = ChatStore.reduce(before, ChatStore.Event.TransportRecovered)

        assertNull("the live error mirror is what the trailing card renders", after.error)
        assertNull(after.errorDetail)
        assertEquals(ChatStore.RunStatus.IDLE, after.runStatus)
        assertEquals(
            "the failure row the failed send raised retires with the link",
            0,
            after.messages.count { ChatStore.isErrorRow(it) },
        )
        assertTrue("the transcript itself is untouched", after.messages.any { it.id == "msg_u1" })
    }

    @Test
    fun aRunningTurnDropsTheStaleNotConnectedRow() {
        val errored = failed(notConnectedLine)
        val running = ChatStore.reduce(
            errored,
            ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.RUNNING),
        )
        assertEquals(
            "a `running` status can only come from a server that answered (v57)",
            0,
            running.messages.count { ChatStore.isErrorRow(it) },
        )
    }

    // ------------------------------------------- 3. wiring (Main-side calls)

    @Test
    fun aFreshProbeVerdictIsARecoveryCarrier() {
        val installed = vm.substringAfter("private fun setGeneration(")
        val v2 = installed.substringAfter("if (generation == ServerGeneration.V2) {")
        val body = v2.substringBefore("} else {")
        assertTrue(
            "a probe that just answered GET /api/info is proof the server is back, and the " +
                "streams cannot carry that proof while no transport exists",
            body.contains("applyEvent(ChatStore.Event.TransportRecovered)"),
        )
        assertTrue(
            "the carrier stays on the store's own event, never a bespoke state write",
            body.contains("ChatStore.Event.TransportRecovered"),
        )
    }

    @Test
    fun theIdleWatchdogDoesNotSkipATickWithoutATransport() {
        val watchdog = vm.substringAfter("private fun startIdleWatchdog()")
            .substringBefore("private fun requireClient()")
        assertTrue(
            "the null-transport case has its own branch",
            watchdog.contains("if (transport == null) {"),
        )
        assertTrue(
            "and it settles through the watchdog's own policy check",
            watchdog.contains("watchdog.probeDue(_state.value.runStatus)"),
        )
        assertFalse(
            "the old `?: continue` is what skipped the turn",
            watchdog.contains("as? V2Transport ?: continue"),
        )
        assertTrue(
            "the unanswerable probe must stay quiet: nothing proved the reply landed",
            watchdog.contains("applyEvent(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.IDLE))"),
        )
    }

    // -------------------------------------------------------- 4. source pins

    @Test
    fun theVocabularyKeepsItsPrefixRule() {
        assertTrue(retry.contains("fun isClientNotConnectedText(text: String?)"))
        assertTrue(retry.contains("startsWith(CLIENT_NOT_CONNECTED_PREFIX)"))
        assertTrue(retry.contains("isClientNotConnectedText(line) ||"))
    }

    @Test
    fun theStoreStillDrivesTheRecoveryOffTheClassifier() {
        assertTrue(
            "one classifier, so the card's kind and the recovery rule cannot drift",
            chatStore.contains("classifyStoredError(state.error, state.errorDetail).kind ==")
        )
        assertTrue(chatStore.contains("ProviderErrorKind.TRANSPORT"))
        assertTrue(chatStore.contains("dropStaleTransportRows(state.messages)"))
    }
}
