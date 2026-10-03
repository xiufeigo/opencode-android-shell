package com.xiufeigo.opencodeshell

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.xiufeigo.opencodeshell.ui.GlobalChord
import com.xiufeigo.opencodeshell.ui.globalChord
import com.xiufeigo.opencodeshell.ui.debug.HeapUsage
import com.xiufeigo.opencodeshell.ui.debug.PROVIDER_METRIC_EVENT_TYPES
import com.xiufeigo.opencodeshell.ui.debug.ProviderMetricAccumulator
import com.xiufeigo.opencodeshell.ui.debug.ProviderMetricEvent
import com.xiufeigo.opencodeshell.ui.debug.ProviderMetrics
import com.xiufeigo.opencodeshell.ui.debug.TokenUsage
import com.xiufeigo.opencodeshell.ui.debug.debugBarMetrics
import com.xiufeigo.opencodeshell.ui.debug.foldProviderMetrics
import com.xiufeigo.opencodeshell.ui.debug.formatDuration
import com.xiufeigo.opencodeshell.ui.debug.formatHeapPercent
import com.xiufeigo.opencodeshell.ui.debug.formatMemory
import com.xiufeigo.opencodeshell.ui.debug.formatTokens
import com.xiufeigo.opencodeshell.ui.debug.formatTps
import com.xiufeigo.opencodeshell.ui.debug.heapUsage
import com.xiufeigo.opencodeshell.ui.debug.isProviderMetricEvent
import com.xiufeigo.opencodeshell.ui.debug.projectedProviderMetrics
import com.xiufeigo.opencodeshell.ui.debug.providerMetricEventOf
import com.xiufeigo.opencodeshell.ui.palette.CATEGORY_VIEW
import com.xiufeigo.opencodeshell.ui.palette.appCommands
import com.xiufeigo.opencodeshell.ui.settings.SHORTCUT_ROWS
import com.xiufeigo.opencodeshell.ui.settings.WIRED_SHORTCUT_IDS
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-17: the debug bar (`ui/debug/`) — the provider-metric arithmetic, the heap
 * reading, the formatting, and the command wiring.
 *
 * The upstream twin is `shell/debug/provider-metrics.test.ts`; the cases below
 * follow it (an attempt folds from `step.started` → first-output marker →
 * `step.streamed` → `step.ended{tokens}`) and add the Android-specific halves:
 * the history projection ([projectedProviderMetrics]) and the command rows.
 *
 * The Compose surface itself is asserted by source scan, the same technique
 * `ChordCommandWiringTest` uses, because a `@Composable` body is not reachable
 * from a JVM test.
 */
class DebugBarMetricsTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun tokens(output: Long, input: Long = 100L) =
        TokenUsage(input = input, output = output, reasoning = 10L, cacheRead = 5L, cacheWrite = 1L)

    private fun started(id: String, at: Long) =
        ProviderMetricEvent("session.step.started", created = at, assistantMessageID = id, started = at)

    // ------------------------------------------------------- the live fold

    @Test
    fun oneAttemptFoldsIntoAllFourCards() {
        // 1_000 dispatch → 1_200 first output (TTFT 200) → 1_350 answer text
        // (TTFA 350) → 2_100 stream end (E2E 1_100, generation 750 ms).
        val metrics = foldProviderMetrics(
            listOf(
                started("msg_1", 1_000L),
                ProviderMetricEvent("session.reasoning.started", created = 1_200L, assistantMessageID = "msg_1"),
                ProviderMetricEvent("session.text.started", created = 1_350L, assistantMessageID = "msg_1"),
                ProviderMetricEvent("session.step.streamed", created = 2_100L, assistantMessageID = "msg_1"),
                ProviderMetricEvent(
                    "session.step.ended",
                    created = 2_120L,
                    assistantMessageID = "msg_1",
                    tokens = tokens(output = 150L),
                ),
            ),
        )
        assertEquals(200L, metrics?.ttft)
        assertEquals(350L, metrics?.ttfa)
        assertEquals(1_100L, metrics?.e2e)
        // 150 output tokens over the 750 ms from the answer start.
        assertEquals(200.0, metrics?.tps ?: 0.0, 1e-9)
    }

    @Test
    fun tpsFallsBackToTheFirstOutputWhenNoAnswerTextWasSeen() {
        val metrics = foldProviderMetrics(
            listOf(
                started("msg_1", 0L),
                // A tool-input-first turn: `first` is set, `answer` never is.
                ProviderMetricEvent("session.tool.input.started", created = 100L, assistantMessageID = "msg_1"),
                ProviderMetricEvent("session.step.streamed", created = 1_100L, assistantMessageID = "msg_1"),
                ProviderMetricEvent(
                    "session.step.ended",
                    created = 1_110L,
                    assistantMessageID = "msg_1",
                    tokens = tokens(output = 100L),
                ),
            ),
        )
        // 100 tokens / 1 s of generation, measured from `first`.
        assertEquals(100.0, metrics?.tps ?: 0.0, 1e-9)
        assertEquals(100L, metrics?.ttft)
        assertNull("no answer text → TTFA stays undefined", metrics?.ttfa)
    }

    @Test
    fun anIncompleteOrUnpricedAttemptPublishesNothing() {
        // No `step.streamed`: the response never ended, so E2E is unknowable and
        // upstream publishes nothing rather than three-of-four numbers.
        assertNull(
            foldProviderMetrics(
                listOf(
                    started("msg_1", 0L),
                    ProviderMetricEvent("session.text.started", created = 50L, assistantMessageID = "msg_1"),
                    ProviderMetricEvent(
                        "session.step.ended",
                        created = 90L,
                        assistantMessageID = "msg_1",
                        tokens = tokens(output = 10L),
                    ),
                ),
            ),
        )
        // No usage on the terminal frame (an interrupted step).
        assertNull(
            foldProviderMetrics(
                listOf(
                    started("msg_1", 0L),
                    ProviderMetricEvent("session.text.started", created = 50L, assistantMessageID = "msg_1"),
                    ProviderMetricEvent("session.step.streamed", created = 90L, assistantMessageID = "msg_1"),
                    ProviderMetricEvent("session.step.failed", created = 95L, assistantMessageID = "msg_1"),
                ),
            ),
        )
        // No first output at all.
        assertNull(
            foldProviderMetrics(
                listOf(
                    started("msg_1", 0L),
                    ProviderMetricEvent("session.step.streamed", created = 90L, assistantMessageID = "msg_1"),
                    ProviderMetricEvent(
                        "session.step.ended",
                        created = 95L,
                        assistantMessageID = "msg_1",
                        tokens = tokens(output = 10L),
                    ),
                ),
            ),
        )
    }

    @Test
    fun aForeignAttemptsFramesAreIgnored() {
        val state = ProviderMetricAccumulator()
        state.apply(started("msg_1", 0L))
        // A second request started elsewhere in the same session supersedes the
        // tracked attempt, so `msg_1`'s frames no longer contribute.
        state.apply(started("msg_2", 10L))
        state.apply(ProviderMetricEvent("session.text.started", created = 50L, assistantMessageID = "msg_1"))
        state.apply(ProviderMetricEvent("session.step.streamed", created = 60L, assistantMessageID = "msg_1"))
        state.apply(
            ProviderMetricEvent(
                "session.step.ended",
                created = 70L,
                assistantMessageID = "msg_1",
                tokens = tokens(output = 10L),
            ),
        )
        assertNull(state.latest)
        // The frame without a message id matches nothing either.
        state.apply(ProviderMetricEvent("session.step.streamed", created = 80L, assistantMessageID = ""))
        assertNull(state.latest)
    }

    @Test
    fun aSecondAttemptSupersedesTheFirstAndResetClearsEverything() {
        val state = ProviderMetricAccumulator()
        state.apply(started("msg_1", 0L))
        state.apply(ProviderMetricEvent("session.text.started", created = 10L, assistantMessageID = "msg_1"))
        state.apply(ProviderMetricEvent("session.step.streamed", created = 100L, assistantMessageID = "msg_1"))
        state.apply(
            ProviderMetricEvent(
                "session.step.ended",
                created = 110L,
                assistantMessageID = "msg_1",
                tokens = tokens(output = 90L),
            ),
        )
        val first = state.latest
        assertEquals(100L, first?.e2e)

        // A new request replaces the attempt; `latest` survives until the new one
        // completes, so the bar never blanks between turns.
        state.apply(started("msg_2", 500L))
        assertEquals(first, state.latest)

        state.reset()
        assertNull(state.latest)
    }

    @Test
    fun theEventSetIsTheOneUpstreamFolds() {
        assertEquals(
            setOf(
                "session.step.started",
                "session.step.streamed",
                "session.step.ended",
                "session.step.failed",
                "session.text.started",
                "session.reasoning.started",
                "session.tool.input.started",
            ),
            PROVIDER_METRIC_EVENT_TYPES,
        )
        assertTrue(isProviderMetricEvent("session.step.streamed"))
        assertFalse(isProviderMetricEvent("session.text.delta"))
        assertFalse(isProviderMetricEvent("session.usage.recorded"))
    }

    @Test
    fun theFrameReaderTakesTheEnvelopeTimestampAndTheStepPayload() {
        val payload = JSONObject(
            """{"assistantMessageID":"msg_9","started":1234,"tokens":{"input":7,"output":8,"reasoning":1,"cache":{"read":2,"write":3}}}""",
        )
        val step = providerMetricEventOf("session.step.started", 99L, payload)
        assertEquals("msg_9", step?.assistantMessageID)
        assertEquals(1234L, step?.started)
        assertEquals(99L, step?.created)
        assertEquals(21L, step?.tokens?.total)

        // The three markers carry no `started` / `tokens`; the envelope stamp is
        // the measurement point.
        val marker = providerMetricEventOf("session.text.started", 500L, JSONObject("""{"assistantMessageID":"msg_9"}"""))
        assertEquals(500L, marker?.created)
        assertEquals(0L, marker?.started)
        assertNull(marker?.tokens)

        // Not part of the fold → the caller can hand every frame over.
        assertNull(providerMetricEventOf("session.usage.recorded", 1L, payload))
    }

    // ------------------------------------------------- the history projection

    @Test
    fun theHistoryProjectionOnlyKnowsE2E() {
        val messages = listOf(
            ChatStore.UiMessage("msg_user", role = "user", timeCreated = 10L, parts = emptyList()),
            ChatStore.UiMessage(
                id = "msg_a",
                role = "assistant",
                timeCreated = 100L,
                parts = emptyList(),
                tokens = 900L,
                timeCompleted = 1_600L,
            ),
        )
        val projected = projectedProviderMetrics(messages)
        assertEquals(1_500L, projected?.e2e)
        // `ChatStore.UiPart` carries no timestamps, so the other three cards have
        // no history source at all — they stay undefined and render `debugBar.na`.
        assertNull(projected?.ttft)
        assertNull(projected?.ttfa)
        assertNull(projected?.tps)
    }

    @Test
    fun theHistoryProjectionNeedsAFinishedAssistantRowWithUsage() {
        // Still streaming (no `timeCompleted`).
        assertNull(
            projectedProviderMetrics(
                listOf(
                    ChatStore.UiMessage(
                        "msg_a",
                        role = "assistant",
                        timeCreated = 100L,
                        parts = emptyList(),
                        tokens = 900L,
                    ),
                ),
            ),
        )
        // Finished but no usage.
        assertNull(
            projectedProviderMetrics(
                listOf(
                    ChatStore.UiMessage(
                        "msg_a",
                        role = "assistant",
                        timeCreated = 100L,
                        parts = emptyList(),
                        timeCompleted = 200L,
                    ),
                ),
            ),
        )
        // Only user rows.
        assertNull(
            projectedProviderMetrics(
                listOf(ChatStore.UiMessage("msg_u", role = "user", timeCreated = 1L, parts = emptyList())),
            ),
        )
        // The newest qualifying row wins.
        val older = ChatStore.UiMessage("a1", "assistant", 0L, emptyList(), tokens = 1L, timeCompleted = 100L)
        val newer = ChatStore.UiMessage("a2", "assistant", 200L, emptyList(), tokens = 1L, timeCompleted = 500L)
        assertEquals(300L, projectedProviderMetrics(listOf(older, newer))?.e2e)
    }

    @Test
    fun liveMetricsWinOverTheProjection() {
        val messages = listOf(
            ChatStore.UiMessage("a1", "assistant", 0L, emptyList(), tokens = 1L, timeCompleted = 100L),
        )
        val live = ProviderMetrics(tps = 12.5, ttft = 1L, ttfa = 2L, e2e = 3L)
        assertEquals(live, debugBarMetrics(live, messages))
        // Nothing live → the projection's E2E, and nothing else.
        assertEquals(ProviderMetrics(e2e = 100L), debugBarMetrics(null, messages))
        assertNull(debugBarMetrics(null, emptyList()))
    }

    // ------------------------------------------------------------ formatting

    @Test
    fun durationsReadAsUpstreamsCards() {
        assertNull(formatDuration(null))
        assertEquals("0ms", formatDuration(0L))
        assertEquals("999ms", formatDuration(999L))
        assertEquals("1.0s", formatDuration(1_000L))
        assertEquals("9.9s", formatDuration(9_949L))
        assertEquals("10s", formatDuration(10_000L))
        assertEquals("12s", formatDuration(12_400L))
    }

    @Test
    fun tpsKeepsOneDecimalAndRejectsNonNumbers() {
        assertNull(formatTps(null))
        assertNull(formatTps(Double.NaN))
        assertNull(formatTps(Double.POSITIVE_INFINITY))
        assertEquals("0.0", formatTps(0.0))
        assertEquals("12.5", formatTps(12.53))
    }

    @Test
    fun memoryUsesUpstreamsUnits() {
        assertNull(formatMemory(null))
        assertNull(formatMemory(-1L))
        assertEquals("1.0MB", formatMemory(1024L * 1024L))
        assertEquals("512.0MB", formatMemory(512L * 1024L * 1024L))
        // Upstream keeps the `MB` unit above a gigabyte (`debug-bar.tsx:57-61`).
        assertEquals("2048MB", formatMemory(2048L * 1024L * 1024L))
    }

    @Test
    fun tokenTotalsAreGroupedAndZeroReadsAsUnavailable() {
        assertNull(formatTokens(null))
        assertNull(formatTokens(0L))
        assertEquals("999", formatTokens(999L))
        assertEquals("12,345", formatTokens(12_345L))
    }

    @Test
    fun theHeapCardIsUsedOverTheCeiling() {
        val heap = heapUsage(totalMemory = 512L * 1024L * 1024L, freeMemory = 256L * 1024L * 1024L, maxMemory = 1024L * 1024L * 1024L)
        assertEquals(256L * 1024L * 1024L, heap?.used)
        assertEquals(1024L * 1024L * 1024L, heap?.limit)
        assertEquals(0.25, heap?.fraction ?: 0.0, 1e-9)
        assertEquals("25%", formatHeapPercent(heap))
        // A runtime without a ceiling reports nothing rather than dividing by zero.
        assertNull(heapUsage(1L, 0L, 0L))
        assertNull(formatHeapPercent(null))
        // `freeMemory` above `totalMemory` cannot produce a negative reading.
        assertEquals(0L, heapUsage(10L, 99L, 100L)?.used)
        assertEquals(HeapUsage(0L, 100L), heapUsage(10L, 99L, 100L))
    }

    // ------------------------------------------------------- command wiring

    @Test
    fun theToggleIsARebindableRowWithNoDefaultLikeUpstream() {
        val row = SHORTCUT_ROWS.first { it.id == "debugBar.toggle" }
        // Upstream registers `debugBar.toggle` without a keybind
        // (`shell/shell.tsx:49-58`), so the default is blank and a recorded
        // override is what gives it a chord.
        assertEquals("", row.keybind)
        assertEquals(R.string.command_debugBar_toggle, row.titleResId)
        assertTrue("debugBar.toggle" in WIRED_SHORTCUT_IDS)
        // Both directions of the B-5 contract still hold with the new row.
        assertEquals(SHORTCUT_ROWS.map { it.id }.toSet(), WIRED_SHORTCUT_IDS)
    }

    @Test
    fun theChordFiresOnlyThroughARecordedOverride() {
        fun chord(key: Key, ctrl: Boolean = false, shift: Boolean = false, keybinds: Map<String, String> = emptyMap()) =
            globalChord(KeyEventType.KeyDown, key, ctrl = ctrl, meta = false, shift = shift, keybinds = keybinds)

        // Blank default → no signature at all.
        assertNull(chord(Key.J, ctrl = true, shift = true))
        assertEquals(
            GlobalChord.DEBUG_BAR,
            chord(Key.J, ctrl = true, shift = true, keybinds = mapOf("debugBar.toggle" to "Ctrl+Shift+J")),
        )
        // An override replaces rather than adds, and the default it replaced was
        // never bound.
        assertNull(chord(Key.J, ctrl = true, keybinds = mapOf("debugBar.toggle" to "Ctrl+Shift+J")))
        // Every modifier still has to match.
        assertNull(chord(Key.J, ctrl = true, shift = true, keybinds = mapOf("debugBar.toggle" to "Ctrl+Alt+J")))
    }

    @Test
    fun thePaletteRowExistsOnlyWhenTheHostProvidesTheAction() {
        val withAction = appCommands(onOpenSettings = {}, onToggleDebugBar = {})
        val row = withAction.first { it.id == "debugBar.toggle" }
        assertEquals(R.string.command_debugBar_toggle, row.titleResId)
        assertEquals(CATEGORY_VIEW, row.category)
        assertNull("upstream declares no keybind", row.keybind)
        // A null action keeps the row out (the `appCommands` rule).
        assertFalse(appCommands(onOpenSettings = {}).any { it.id == "debugBar.toggle" })
    }

    @Test
    fun thePaletteRowRunsTheToggle() {
        var toggled = 0
        val commands = appCommands(onOpenSettings = {}, onToggleDebugBar = { toggled++ })
        commands.first { it.id == "debugBar.toggle" }.onSelect()
        assertEquals(1, toggled)
    }

    @Test
    fun theCommandIsServedByTheChordTableAndTheViewModelFold() {
        val app = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
        // The id reaches a handler, the enum member names it, and the bar is
        // mounted by the scaffold.
        assertTrue(app.contains("\"debugBar.toggle\""))
        assertTrue(app.contains("GlobalChord.DEBUG_BAR"))
        assertTrue(app.contains("DebugBar("))
        assertTrue(app.contains("onToggleDebugBar = actions.onToggleDebugBar"))

        // The settings row's action is App-level, so no screen has to serve it.
        val model = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsModel.kt")
        assertTrue(model.contains("ShortcutRow(\"debugBar.toggle\""))

        // The live half: the frame path folds the raw frames into the flow the
        // bar reads.
        val viewModel = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        assertTrue(viewModel.contains("providerMetricEventOf(eventType, frame.raw.optLong(\"created\", 0L), frame.data)"))
        assertTrue(viewModel.contains("val providerMetrics: StateFlow<ProviderMetrics?>"))
        assertTrue(viewModel.contains("resetProviderMetrics()"))

        // The bar itself is the composable, and it renders the four provider
        // cards plus the two Android diagnostics.
        val bar = source("src/main/java/com/xiufeigo/opencodeshell/ui/debug/DebugBar.kt")
        for (key in listOf(
            "R.string.debugBar_tps_label",
            "R.string.debugBar_ttft_label",
            "R.string.debugBar_ttfa_label",
            "R.string.debugBar_e2e_label",
            "R.string.debugBar_tok_label",
            "R.string.debugBar_mem_label",
            "R.string.debugBar_inp_label",
            "R.string.debugBar_na",
            "R.string.debugBar_providerAriaLabel",
        )) {
            assertTrue("DebugBar must render $key", bar.contains(key))
        }
    }
}
