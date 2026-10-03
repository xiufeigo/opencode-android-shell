package com.xiufeigo.opencodeshell.ui.session.summary

import com.xiufeigo.opencodeshell.ui.session.review.MobileUsage
import com.xiufeigo.opencodeshell.v2.SessionStatsInfo
import java.util.Locale

/**
 * Pure projection of `GET /api/experimental/session/stats`
 * ([com.xiufeigo.opencodeshell.v2.sessionStats] → `SessionStats.Info`,
 * `session-stats.ts:40-67`) onto summary rows.
 *
 * Upstream ships no screen for the route (`experimental/session/stats` is the
 * whole surface), so the presentation is this shell's own: a flat row list in
 * the summary panel's card style, plus the two structured halves the schema
 * makes interesting — the tool reliability totals (`tools.totals`) and the
 * per-model usage (`models[]`). `tools=none` carries no `totals` and `detail`
 * adds `tools.usage` (both pass through untouched for the card to render).
 *
 * Grouping is fixed to `Locale.US` (same rule as `SessionContextModel` /
 * `MobileUsage`): the digits must not depend on the device locale.
 */
object SessionStatsModel {

    /** Upstream-style placeholder for a missing figure (same glyph as context). */
    const val DASH = "\u2014"

    /** Row kinds the card labels; order below is the render order. */
    enum class Kind {
        SESSIONS,
        SUBAGENTS,
        PROMPTS,
        STEPS,
        INPUT_TOKENS,
        OUTPUT_TOKENS,
        REASONING_TOKENS,
        CACHE_TOKENS,
        COST,
        ACTIVE_DAYS,
        STREAK,
    }

    data class Row(val kind: Kind, val value: String)

    /** `tools.totals` re-projected with display-ready counts. */
    data class Tools(
        val calls: String,
        val succeeded: String,
        val failed: String,
        val unfinished: String,
    )

    /** One `models[]` row: the model label plus its steps and cost. */
    data class Model(val label: String, val steps: String, val cost: String)

    data class Snapshot(
        val rows: List<Row>,
        /** `null` when `tools.mode = none` (the route then carries no totals). */
        val tools: Tools?,
        /** `tools.usage`, only populated in `tools=detail`. */
        val usage: List<SessionStatsInfo.ToolUsage>,
        val models: List<Model>,
    )

    fun snapshot(stats: SessionStatsInfo): Snapshot = Snapshot(
        rows = listOf(
            Row(Kind.SESSIONS, number(stats.sessions.toLong())),
            Row(Kind.SUBAGENTS, number(stats.subagents.toLong())),
            Row(Kind.PROMPTS, number(stats.prompts.toLong())),
            Row(Kind.STEPS, number(stats.steps.toLong())),
            Row(Kind.INPUT_TOKENS, number(stats.tokens.input)),
            Row(Kind.OUTPUT_TOKENS, number(stats.tokens.output)),
            Row(Kind.REASONING_TOKENS, number(stats.tokens.reasoning)),
            Row(
                Kind.CACHE_TOKENS,
                number(stats.tokens.cacheRead) + " / " + number(stats.tokens.cacheWrite),
            ),
            Row(Kind.COST, MobileUsage.cost(stats.cost)),
            Row(Kind.ACTIVE_DAYS, number(stats.activeDays.toLong())),
            Row(Kind.STREAK, number(stats.streak.toLong())),
        ),
        tools = stats.toolsTotals?.let {
            Tools(
                calls = number(it.calls.toLong()),
                succeeded = number(it.succeeded.toLong()),
                failed = number(it.failed.toLong()),
                unfinished = number(it.unfinished.toLong()),
            )
        },
        usage = stats.toolsUsage,
        models = stats.models.map { usage ->
            Model(
                label = usage.model?.let { ref ->
                    ref.id.ifBlank { ref.providerID }
                }?.takeIf { it.isNotBlank() } ?: DASH,
                steps = number(usage.steps.toLong()),
                cost = MobileUsage.cost(usage.cost),
            )
        },
    )

    /** `1,234`; grouping is fixed to `Locale.US`. */
    fun number(value: Long?): String =
        value?.let { String.format(Locale.US, "%,d", it) } ?: DASH
}
