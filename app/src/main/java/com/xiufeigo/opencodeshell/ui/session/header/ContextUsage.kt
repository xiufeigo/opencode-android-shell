package com.xiufeigo.opencodeshell.ui.session.header

import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.OpenCodeClient
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
 * **One deliberate deviation (device v20, `research/v20_verify/`)**: the
 * denominator is the model's **effective prompt window** — `min(limit.input,
 * limit.context)`, [windowOf] — not the bare `limit.context` upstream divides
 * by. The two differ on most of the live catalog (measured: 45 of 129 entries on
 * the v20 backend advertise `input < context`), and the *server* compacts on the
 * input half: `SessionCompaction.required`
 * (`_upstream/packages/core/src/session/compaction.ts:742-763`) triggers at
 * `min(limit.input - buffer, context - max(output, buffer))` with
 * `buffer = 20_000`. For `opencode-go/space-bunny-free`
 * (`{"context":1048576,"input":524288,"output":524288}`) that is **504,288**
 * while the raw `limit.context` is 1,048,576 — so a session could read 59 %
 * (619,741 tokens) in this UI with the server's own ceiling already behind it.
 * The ring, the header percent, the context tab's `Context Limit` / `Usage` rows
 * and the client's own pre-emptive compaction all read this one number
 * ([windowOf] / [com.xiufeigo.opencodeshell.ChatViewModel.contextLimitFrom]).
 * Both halves are fields of the catalog entry the ref resolves to
 * (`OpenCodeClient.Providers.Model.limitInput` / `limitContext`), so the window
 * is always read off one row — there is no second lookup to fall out of sync.
 *
 * **No >80 % "turn red" branch exists upstream** — the source only clamps the
 * value, so this file deliberately has no threshold either (`research/02` §2.2
 * "阈值变色：未找到 → 不要在 Android 里臆造阈值"). The 80 % figure lives in
 * `ChatViewModel.ContextCompactionPolicy` and is a *trigger*, not a colour.
 *
 * Data availability: the token total is carried by [ChatStore.UiMessage.tokens]
 * (additive display payload, parsed from the message info). The **context
 * limit** comes from the provider/model catalog (`GET /provider` v1 /
 * `GET /api/provider` + `GET /api/model` v2) — `ChatViewModel.contextLimit`
 * resolves the ref through [lastModelRef] first and only falls back to the
 * picker / session refs, so the two halves of the ratio come from the same
 * message. Without a limit [percentOf] returns `null` and the ring renders an
 * empty track, i.e. the documented "数据缺 = 0 %" state.
 */
object ContextUsage {

    /**
     * The **effective prompt window** of one catalog entry: `min(limit.input,
     * limit.context)`, or `limit.context` alone when the catalog carries no
     * `limit.input` (measured: 74 of the 129 live entries omit it — the field is
     * optional on the wire, `Model.limit.input?: number`).
     *
     * Why the *smaller* half: the server's own compaction trigger subtracts its
     * buffer from `limit.input` (see the class doc), so the input half is the
     * real ceiling on what may be *sent*. On the v20 catalog `context` is never
     * below `input` on entries that carry both — 45 are strictly larger, 10 are
     * equal — but the `min` is written out rather than assumed, because the
     * catalog is data and a future entry may invert the relation.
     *
     * `null` for an unusable window (`context` missing or `<= 0`) and for an
     * `input` of `0`/negative — the same "no fake denominator" rule as
     * [percentOf]; `input == 0` is a *placeholder*, not a window (measured on 4
     * live entries that report `{"context":0,"input":0,"output":0}`).
     */
    fun windowOf(limitContext: Int?, limitInput: Int?): Int? {
        val context = limitContext?.takeIf { it > 0 } ?: return null
        val input = limitInput?.takeIf { it > 0 } ?: return context
        return minOf(context, input)
    }

    /**
     * The catalog lookup behind [windowOf] — the model a `providerID/modelID`
     * ref resolves to (same rule as [OpenCodeClient.Providers.model], entry id
     * first) and that entry's own `limit.input`.
     *
     * Both halves come from the **resolved entry**
     * ([OpenCodeClient.Providers.Model.entryID], then [id]) rather than the
     * requested ref, and from the same catalog row: `limit.input` is a field of
     * the entry that carries `limit.context` ([OpenCodeClient.Providers.Model
     * .limitInput], folded out of the same `GET /api/model` document), so the
     * pair can never describe two different models. `null` when the ref resolves
     * to no catalog entry — the ring's documented empty track.
     */
    fun windowOf(
        catalog: OpenCodeClient.Providers?,
        providerID: String,
        modelID: String,
    ): Int? {
        val model = catalog?.model(providerID, modelID) ?: return null
        return windowOf(model.limitContext, model.limitInput)
    }

    /** `Math.max(0, Math.min(100, round(total / limit * 100)))`, `null` when unknown. */
    fun percentOf(total: Long?, limit: Long?): Int? {
        if (total == null || limit == null || limit <= 0L || total < 0L) return null
        return ((total.toDouble() / limit.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
    }

    /**
     * The **row the ring reads both halves from**: the newest assistant message
     * that reported token usage (upstream
     * `session-context-usage.tsx:52` `messages().findLast(item => item.type ===
     * "assistant" && !!item.tokens)`). `null` before the first usage lands.
     *
     * The same rule over the **raw** rows of `GET /api/session/{id}/context` is
     * [com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel
     * .lastAssistantTotal] — `ChatViewModel.activeContextTokens` is reduced
     * through it and the context tab's `Total Tokens` / `Usage` rows are projected
     * from the same rows, so the ring and the panel beside it always describe the
     * same turn (v17-23 was exactly that split).
     */
    fun lastUsage(messages: List<ChatStore.UiMessage>): ChatStore.UiMessage? =
        messages.lastOrNull { it.role == "assistant" && (it.tokens ?: 0L) > 0L }

    /** Last **assistant** message whose token total is > 0 (upstream picks the newest). */
    fun lastTokenTotal(messages: List<ChatStore.UiMessage>): Long? = lastUsage(messages)?.tokens

    /**
     * [ChatStore.UiMessage.model] of [lastUsage] — the `"providerID/modelID"`
     * ref of the model that produced the ring's numerator, i.e. upstream's
     * `message.model` (`session-context-usage.tsx:53-54`) and the same ref the
     * context tab resolves (`SessionContextModel.modelRef`). The *same* message
     * supplies both halves, so the ring and the tab cannot disagree.
     */
    fun lastModelRef(messages: List<ChatStore.UiMessage>): String? =
        lastUsage(messages)?.model?.takeIf { it.isNotBlank() }

    /** `null`/out-of-range percent → `0f` (a ring with an unknown usage is empty, not full). */
    fun progress(percent: Int?): Float = (percent ?: 0).coerceIn(0, 100) / 100f

    /** Convenience for the Compose layer: messages + limit → sweep fraction. */
    fun progressOf(messages: List<ChatStore.UiMessage>, limit: Long?): Float =
        progress(percentOf(lastTokenTotal(messages), limit))

    /**
     * The percentage of the **context tab's `Usage` cell**, read back for the
     * tab's own usage indicator (upstream `session-side-panel.tsx:370`
     * `<SessionContextUsage variant="indicator" />`, which renders the very same
     * `ctx()?.usage` the tab's stats table shows -- `session-context-tab.tsx:133`).
     *
     * Why parse a string: the right panel is handed
     * [com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel.Snapshot],
     * whose cells are display-ready text ([com.xiufeigo.opencodeshell.ui.session
     * .context.SessionContextModel.Stat.value]), so re-deriving a fresh ratio
     * here would be a *second* computation over a payload this panel does not
     * own -- and could disagree with the `Usage` row sitting one tap away. One
     * number, one source.
     *
     * `null` for absent / placeholder (`SessionContextModel.DASH`) / unparseable
     * cells, and for anything outside `0..100`, so the indicator is not drawn at
     * all rather than drawn empty (upstream renders nothing without a limit
     * either: `ctx()?.usage` is `null` when `model.limit.context` is missing).
     */
    fun indicatorPercent(usageCell: String?): Int? {
        val cell = usageCell?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val digits = cell.removeSuffix("%").trim()
        val percent = digits.toIntOrNull() ?: return null
        return percent.takeIf { it in 0..100 }
    }

    /** [indicatorPercent] folded into [progress]'s sweep fraction. */
    fun indicatorProgress(usageCell: String?): Float? = indicatorPercent(usageCell)?.let(::progress)
}
