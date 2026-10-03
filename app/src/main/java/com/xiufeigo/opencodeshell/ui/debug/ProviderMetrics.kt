package com.xiufeigo.opencodeshell.ui.debug

import com.xiufeigo.opencodeshell.ChatStore
import org.json.JSONObject
import java.util.Locale

/**
 * B-17: the debug bar's **provider metrics** — the pure half.
 *
 * Ported from `_upstream/packages/app/src/shell/debug/provider-metrics.ts`
 * (whose unit test is `provider-metrics.test.ts`): the desktop bar folds a live
 * event stream into the four cards the panel shows, and falls back to a
 * projection of the already-loaded history while nothing live has landed.
 *
 * | card | meaning                                             |
 * |------|-----------------------------------------------------|
 * | E2E  | request dispatch → response stream ended             |
 * | TTFT | request dispatch → first model output (any part)     |
 * | TTFA | request dispatch → first **answer text**             |
 * | TPS  | output tokens / second of generation                 |
 *
 * `null` on a metric is upstream's `undefined`: the card renders the localized
 * `debugBar.na` instead of a number. Nothing here is guessed — a request that
 * never streamed, or an interrupted step without usage, publishes **no** metrics
 * at all (`attemptMetrics` is only reached with both timestamps and tokens).
 *
 * Everything in this file is plain JVM data, so the arithmetic is unit-testable
 * without a Compose runtime ([DebugBarMetricsTest]).
 */

/** `TokenUsage.Info` (`_upstream/packages/schema/src/token-usage.ts:6-14`). */
data class TokenUsage(
    val input: Long = 0L,
    val output: Long = 0L,
    val reasoning: Long = 0L,
    val cacheRead: Long = 0L,
    val cacheWrite: Long = 0L,
) {
    /** `TokenUsage.total` (`token-usage.ts:16-18`). */
    val total: Long get() = input + output + reasoning + cacheRead + cacheWrite

    companion object {
        /** `null` for a missing object, so "no usage" stays distinguishable from all-zero. */
        fun fromJson(json: JSONObject?): TokenUsage? {
            if (json == null) return null
            val cache = json.optJSONObject("cache")
            return TokenUsage(
                input = json.optLong("input", 0L),
                output = json.optLong("output", 0L),
                reasoning = json.optLong("reasoning", 0L),
                cacheRead = cache?.optLong("read", 0L) ?: 0L,
                cacheWrite = cache?.optLong("write", 0L) ?: 0L,
            )
        }
    }
}

/** The four cards. `null` = upstream's `undefined` (rendered as `debugBar.na`). */
data class ProviderMetrics(
    val tps: Double? = null,
    val ttft: Long? = null,
    val ttfa: Long? = null,
    val e2e: Long? = null,
)

/**
 * The frame types the fold listens to (`provider-metrics.ts:8-25`): the step
 * lifecycle plus the three "first output" markers. `session.step.failed` is in
 * the set because a failed step can still carry usage — the fold's own guard
 * decides whether it publishes.
 */
val PROVIDER_METRIC_EVENT_TYPES: Set<String> = setOf(
    "session.step.started",
    "session.step.streamed",
    "session.step.ended",
    "session.step.failed",
    "session.text.started",
    "session.reasoning.started",
    "session.tool.input.started",
)

fun isProviderMetricEvent(type: String): Boolean = type in PROVIDER_METRIC_EVENT_TYPES

/**
 * One folded input: the frame `type`, its envelope `created` millisecond stamp,
 * the step's own `started` dispatch time (only `session.step.started` carries
 * one) and the `TokenUsage.Info` of a terminal step.
 */
data class ProviderMetricEvent(
    val type: String,
    val created: Long,
    val assistantMessageID: String,
    val started: Long = 0L,
    val tokens: TokenUsage? = null,
)

/**
 * Read one v2 frame into a [ProviderMetricEvent] — `null` when the type is not
 * part of the fold, so the caller can hand every frame over unexamined.
 *
 * [data] is the frame's `data` payload (`V2EventAdapter.Frame.data`); [created]
 * is the **envelope's** `created` (`event.ts:60-71`), which is the timestamp the
 * three "first output" markers are measured at.
 */
fun providerMetricEventOf(type: String, created: Long, data: JSONObject): ProviderMetricEvent? {
    if (!isProviderMetricEvent(type)) return null
    return ProviderMetricEvent(
        type = type,
        created = created,
        assistantMessageID = data.optString("assistantMessageID", ""),
        started = data.optLong("started", 0L),
        tokens = TokenUsage.fromJson(data.optJSONObject("tokens")),
    )
}

/**
 * The live fold — upstream's `ProviderMetricState` + `applyProviderMetricEvent`
 * (`provider-metrics.ts:36-81`), as one mutable accumulator.
 *
 * One attempt is tracked at a time: `session.step.started` replaces it (a new
 * request supersedes the previous one whatever its outcome), every other frame
 * is ignored unless it names that attempt's assistant message. [latest] keeps
 * the last **published** metrics across turns, so the bar shows the most recent
 * completed request rather than blanking between turns.
 */
class ProviderMetricAccumulator {

    private var attempt: Attempt? = null

    /** The last published metrics; `null` until a request completed with usage. */
    var latest: ProviderMetrics? = null
        private set

    /** Fold one frame. Returns true when [latest] changed. */
    fun apply(event: ProviderMetricEvent): Boolean {
        if (event.type == "session.step.started") {
            attempt = Attempt(event.assistantMessageID, event.started)
            return false
        }

        val current = attempt ?: return false
        // A foreign attempt's frames (a second concurrent request, a replay of
        // another turn) must not contribute to this one.
        if (event.assistantMessageID.isEmpty() || event.assistantMessageID != current.assistantMessageID) {
            return false
        }

        when (event.type) {
            "session.text.started", "session.reasoning.started", "session.tool.input.started" -> {
                if (current.first == null) current.first = event.created
                // Only real answer text sets TTFA; reasoning and tool input are
                // "model output" for TTFT but not an answer.
                if (event.type == "session.text.started" && current.answer == null) {
                    current.answer = event.created
                }
                return false
            }

            "session.step.streamed" -> {
                current.streamed = event.created
                return false
            }
        }

        // `session.step.ended` / `session.step.failed`. Interrupted or failed
        // attempts without usage would publish misleading partial numbers.
        val tokens = event.tokens ?: return false
        val streamed = current.streamed ?: return false
        if (current.first == null) return false
        current.tokens = tokens
        latest = attemptMetrics(current)
        return true
    }

    /** Drop the tracked attempt **and** the published metrics (session switch, reconnect). */
    fun reset() {
        attempt = null
        latest = null
    }
}

/** Fold a whole list — upstream's `foldProviderMetrics` (`provider-metrics.ts:83-87`). */
fun foldProviderMetrics(events: List<ProviderMetricEvent>): ProviderMetrics? {
    val state = ProviderMetricAccumulator()
    for (event in events) state.apply(event)
    return state.latest
}

private class Attempt(
    val assistantMessageID: String,
    val started: Long,
    var first: Long? = null,
    var answer: Long? = null,
    var streamed: Long? = null,
    var tokens: TokenUsage? = null,
)

/**
 * Upstream `projectedProviderMetrics` (`provider-metrics.ts:89-115`), adapted to
 * what this shell's history actually carries.
 *
 * **Deliberate deviation.** The desktop projection reads part timestamps
 * (`message.content[0].time.created`, the reasoning part's `time.completed`) to
 * derive TTFT / TTFA / TPS from loaded history. `ChatStore.UiPart` has no
 * timestamp field at all, so those three stay `null` on Android and only E2E is
 * projected — from `time.created` to `time.completed`, the assistant row's own
 * span, which is the closest available stand-in for upstream's
 * `time.created → time.streamed`. The live fold fills all four in as soon as a
 * request completes while the bar is up; until then the bar shows `debugBar.na`
 * for the three it cannot know. Guessing them from part order would be worse
 * than saying nothing.
 */
fun projectedProviderMetrics(messages: List<ChatStore.UiMessage>): ProviderMetrics? {
    val message = messages.lastOrNull {
        it.role == "assistant" && it.timeCompleted != null && it.tokens != null
    } ?: return null
    return ProviderMetrics(e2e = elapsed(message.timeCreated, message.timeCompleted))
}

/** Upstream `state.live ?? projected()` (`debug-bar.tsx:228`). */
fun debugBarMetrics(live: ProviderMetrics?, messages: List<ChatStore.UiMessage>): ProviderMetrics? =
    live ?: projectedProviderMetrics(messages)

private fun attemptMetrics(attempt: Attempt): ProviderMetrics {
    val ttft = elapsed(attempt.started, attempt.first)
    val ttfa = elapsed(attempt.started, attempt.answer)
    val e2e = elapsed(attempt.started, attempt.streamed)
    // Output tokens exclude reasoning, so measure them from the answer start
    // when one exists (`provider-metrics.ts:121-122`).
    val generation = elapsed(attempt.answer ?: attempt.first, attempt.streamed)
    val output = attempt.tokens?.output
    val tps = if (generation != null && generation > 0L && output != null && output > 0L) {
        output / (generation / 1_000.0)
    } else {
        null
    }
    return ProviderMetrics(tps = tps, ttft = ttft, ttfa = ttfa, e2e = e2e)
}

private fun elapsed(start: Long?, end: Long?): Long? {
    if (start == null || end == null) return null
    return (end - start).coerceAtLeast(0L)
}

// ---------------------------------------------------------------- formatting

/**
 * Upstream `duration` (`debug-bar.tsx:63-67`): whole ms below one second, then
 * one decimal of seconds below ten, then whole seconds.
 */
fun formatDuration(millis: Long?): String? {
    if (millis == null) return null
    if (millis < 1_000L) return "${millis}ms"
    val pattern = if (millis < 10_000L) "%.1fs" else "%.0fs"
    return String.format(Locale.US, pattern, millis / 1_000.0)
}

/** Upstream `fixed(n, 1)` for the TPS card (`debug-bar.tsx:550`). */
fun formatTps(tps: Double?): String? {
    if (tps == null || tps.isNaN() || tps.isInfinite()) return null
    return String.format(Locale.US, "%.1f", tps)
}

/**
 * Upstream `mb` (`debug-bar.tsx:57-61`): one decimal below a gigabyte, whole
 * numbers above — and the unit stays `MB` either way, which is upstream's own
 * quirk kept for parity (`2048.0MB` would read as 2 GiB).
 */
fun formatMemory(bytes: Long?): String? {
    if (bytes == null || bytes < 0L) return null
    val mb = bytes / 1024.0 / 1024.0
    val pattern = if (mb >= 1024.0) "%.0fMB" else "%.1fMB"
    return String.format(Locale.US, pattern, mb)
}

/** Session token total (`debugBar.tok`), grouped so six digits stay readable. */
fun formatTokens(tokens: Long?): String? {
    if (tokens == null || tokens <= 0L) return null
    return String.format(Locale.US, "%,d", tokens)
}

/** The `MEM` card's value: `used / limit` as a whole percentage. */
fun formatHeapPercent(heap: HeapUsage?): String? {
    if (heap == null) return null
    return "${Math.round(heap.fraction * 100)}%"
}

/**
 * One heap reading. [limit] is the runtime's **max** heap, not its current
 * size: `Runtime.totalMemory()` grows on demand, so a percentage against it
 * would fall every time the heap grows — upstream measures against
 * `jsHeapSizeLimit`, which is the same kind of ceiling.
 */
data class HeapUsage(val used: Long, val limit: Long) {
    val fraction: Double get() = if (limit <= 0L) 0.0 else used.toDouble() / limit.toDouble()
}

/**
 * Build a [HeapUsage] from `Runtime`'s three numbers (`Runtime.getRuntime()`):
 * `totalMemory - freeMemory` is what the process holds right now, `maxMemory` is
 * the ceiling the VM will grow to.
 *
 * **Deliberate deviation.** Upstream reads `performance.memory`
 * (`jsHeapSizeLimit` / `usedJSHeapSize`), a Chromium-only API
 * (`debug-bar.tsx:19-24,379-383`); there is no JS heap on Android. The ART
 * runtime's numbers are the honest equivalent and the same card renders them.
 * A runtime with no ceiling (`maxMemory() == 0`, which the spec allows) yields
 * `null` so the card says `debugBar.na` instead of dividing by zero.
 */
fun heapUsage(totalMemory: Long, freeMemory: Long, maxMemory: Long): HeapUsage? {
    if (maxMemory <= 0L) return null
    return HeapUsage(used = (totalMemory - freeMemory).coerceAtLeast(0L), limit = maxMemory)
}
