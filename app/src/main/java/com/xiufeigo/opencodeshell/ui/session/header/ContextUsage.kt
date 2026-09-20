package com.xiufeigo.opencodeshell.ui.session.header

import com.xiufeigo.opencodeshell.ChatStore
import kotlin.math.roundToInt

/**
 * Pure (JVM-testable) context-ring math — the port of upstream
 * `packages/app/src/components/session/session-context-metrics.ts`
 * (`research/02` §2.2).
 *
 * Upstream:
 * ```
 * tokenTotal = last assistant message with tokenTotal > 0
 *            = tokens.input + output + reasoning + cache.read + cache.write
 * limit      = provider.models[modelID].limit.context
 * usage      = limit ? round(total / limit * 100) : null      // clamped 0..100
 * ```
 *
 * **No >80 % "turn red" branch exists upstream** — the source only clamps the
 * value, so this file deliberately has no threshold either (`research/02` §2.2
 * "阈值变色：未找到 → 不要在 Android 里臆造阈值").
 *
 * Data availability: the token total is carried by [ChatStore.UiMessage.tokens]
 * (additive display payload, parsed from the message info). The **context
 * limit** needs the provider/model catalog (`GET /config/providers`), which is
 * M4 scope — until then [percentOf] returns `null` and the ring renders an
 * empty track, i.e. the documented "数据缺 = 0 %" state.
 */
object ContextUsage {

    /** `Math.max(0, Math.min(100, round(total / limit * 100)))`, `null` when unknown. */
    fun percentOf(total: Long?, limit: Long?): Int? {
        if (total == null || limit == null || limit <= 0L || total < 0L) return null
        return ((total.toDouble() / limit.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
    }

    /** Last **assistant** message whose token total is > 0 (upstream picks the newest). */
    fun lastTokenTotal(messages: List<ChatStore.UiMessage>): Long? =
        messages.lastOrNull { it.role == "assistant" && (it.tokens ?: 0L) > 0L }?.tokens

    /** `null`/out-of-range percent → `0f` (a ring with an unknown usage is empty, not full). */
    fun progress(percent: Int?): Float = (percent ?: 0).coerceIn(0, 100) / 100f

    /** Convenience for the Compose layer: messages + limit → sweep fraction. */
    fun progressOf(messages: List<ChatStore.UiMessage>, limit: Long?): Float =
        progress(percentOf(lastTokenTotal(messages), limit))
}
