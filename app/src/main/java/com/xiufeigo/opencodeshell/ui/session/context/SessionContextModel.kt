package com.xiufeigo.opencodeshell.ui.session.context

import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.ui.session.export.SessionExport
import com.xiufeigo.opencodeshell.ui.session.review.MobileUsage
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Row model of the session **context** tab (B2) — the upstream
 * `SessionContextTab` (`_upstream/packages/app/src/session/files/session-context-tab.tsx`),
 * reachable as the right panel's third tab (`session-side-panel.tsx:345-373`,
 * label key `session.tab.context`) *and* as the mobile `usage` view
 * (`session/screen.tsx:307-309`: `<Match when={!isDesktop() && mobileView() === "usage"}>`).
 *
 * Upstream renders sixteen `Stat` rows (`:173-193`, order is significant), an
 * optional **system prompt** block (`:153-159`) and the **raw message** list
 * (`:54-81`). This object owns the pure part — which rows exist, in what order,
 * how their values are derived and how the raw rows are projected — so the rules
 * are JVM-testable; [SessionContextPanel] owns only layout and copy.
 *
 * ### Input shape
 *
 * The inputs are the payloads the app already fetches for the export and the
 * summary: `GET /session/:id` (raw `Session.Info`) and `GET /session/:id/message`
 * (raw `WithParts[]`, i.e. `{info, parts}` rows, `OpenCodeClient.messagesJson`).
 * The parsed `OpenCodeClient.Message` deliberately flattens `info.tokens` to one
 * total, so the per-bucket rows (input / output / reasoning / cache) can only be
 * produced from the raw objects. Both the v1 `{info:{role,providerID,modelID,…}}`
 * shape this server speaks and the v2 flat
 * `{type, model:{providerID,id}, tokens}` shape (`SessionMessageInfo`) are read,
 * so a server that starts answering v2 needs no change here.
 *
 * ### Deliberate deviations (recorded in the B2 report)
 *
 * - **Grouping is `Locale.US`** (`,` separators), like `MobileUsage`: the digits
 *   must not depend on the device locale. Upstream formats through `Intl` with
 *   the app locale.
 * - **Times are the device's locale/zone** (`FormatStyle.MEDIUM`/`SHORT`, which
 *   is what upstream's luxon `DATETIME_MED` preset renders) and are injectable so
 *   tests stay deterministic.
 * - **Cost**: upstream formats `info()?.cost ?? 0` (`$0.00` for an unpriced
 *   session). This server omits the session-level `cost` from
 *   `GET /session/:id`, so the row falls back to `ChatViewModel.sessionCost` —
 *   the number the header's 总成本 already shows — and renders `—` only when
 *   neither source has one.
 * - **The system prompt** is read from the last `system` **message**; when that
 *   message is a v1 `{info, parts}` row (this server's shape) the text comes from
 *   its text parts, because v1 has no flat `text` field.
 */
object SessionContextModel {

    /** Upstream `createSessionContextFormatter`'s placeholder for every absent value. */
    const val DASH = "\u2014"

    /** Upstream `context.stats.*` (`session-context-tab.tsx:173-193`), in order. */
    enum class Kind {
        SESSION,
        MESSAGES,
        PROVIDER,
        MODEL,
        LIMIT,
        TOTAL_TOKENS,
        USAGE,
        INPUT_TOKENS,
        OUTPUT_TOKENS,
        REASONING_TOKENS,
        CACHE_TOKENS,
        USER_MESSAGES,
        ASSISTANT_MESSAGES,
        TOTAL_COST,
        SESSION_CREATED,
        LAST_ACTIVITY,
    }

    /** One stat cell: [value] is display-ready (long values ellipsize/wrap). */
    data class Stat(val kind: Kind, val value: String)

    /** One raw message row (upstream `RawMessage`, `:54-81`). */
    data class RawMessage(
        /**
         * Stable key for the expansion state — the message id, or `message:<i>`
         * for a malformed row without one, so two rows can never collide.
         */
        val id: String,
        val type: String,
        /** `time.created` already formatted, or [DASH]. */
        val time: String,
        /** `JSON.stringify(message, null, 2)` — [SessionExport.INDENT] spaces. */
        val json: String,
    ) {
        /** Upstream `:65`: `{type} • {id}`. */
        val title: String get() = "$type \u2022 $id"
    }

    data class Snapshot(
        val stats: List<Stat>,
        val messages: List<RawMessage>,
        /** Last non-blank `system` message, trimmed; `null` = section hidden (`:283`). */
        val systemPrompt: String?,
        /**
         * B3: source of the tab's `context.export.session` button
         * (`session-context-tab.tsx:195-215`). Upstream re-fetches the session
         * there; this screen already holds the raw payloads [project] was called
         * with, so the button writes exactly the data the sixteen rows were
         * derived from — no second request and no new HTTP code. `null` while
         * nothing has loaded (the button is not rendered then).
         */
        val export: Export? = null,
    )

    /**
     * The `{info, messages}` payload of the tab's export button, i.e. upstream's
     * `fetchSessionExport` output (`session/commands/export.ts:10-43`) as this
     * screen can produce it from data it already has.
     *
     * [json] is lazy: the tab body is projected on every payload change, and
     * serializing a long session's messages eagerly would make that projection
     * pay for a button the user may never press.
     */
    class Export(
        /** `sessionExportFilename(info)` — `title || slug || id`, sanitized. */
        val filename: String,
        val info: JSONObject?,
        val messages: List<JSONObject>,
    ) {
        /** `JSON.stringify(data, null, 2)`, exactly `SessionExport.serialize`. */
        val json: String by lazy { SessionExport.serialize(info ?: JSONObject(), messages) }
    }

    /**
     * The pre-load state of both mounting points: all sixteen rows with every
     * value unavailable. `lazy` because it projects through [project] itself.
     */
    val EMPTY: Snapshot by lazy { project(session = null, messages = emptyList()) }

    /** 32 rem at the 16 px root font — upstream's `@[32rem]:grid-cols-2` (`:277`). */
    const val TWO_COLUMN_MIN_WIDTH_DP = 512

    /** Grid columns for a container of [widthDp] (`grid-cols-1 @[32rem]:grid-cols-2`). */
    fun columns(widthDp: Int): Int = if (widthDp >= TWO_COLUMN_MIN_WIDTH_DP) 2 else 1

    /**
     * The full tab projection. [providers] is the `GET /provider` catalog the
     * screen already holds (`ChatViewModel.availableProviders`); without it the
     * provider/model rows fall back to the raw ids.
     *
     * [contextLimit] and [sessionCost] are the two values the screen computes
     * independently — `ChatViewModel.contextLimit` (the ring's denominator and
     * the header's `上下文用量` percent) and `ChatViewModel.sessionCost` (the
     * header's 总成本) — and they are the *fallbacks* for rows whose upstream
     * source is missing on this server: v1 `GET /session/:id` carries no
     * session-level `cost`. They are not the primary source, so a server that
     * does price the session still wins (upstream semantics).
     */
    fun project(
        session: JSONObject?,
        messages: List<JSONObject>,
        providers: OpenCodeClient.Providers? = null,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        contextLimit: Int? = null,
        sessionCost: Double? = null,
    ): Snapshot {
        val last = messages.lastOrNull { isAssistantWithTokens(it) }
        val buckets = last?.let { tokensOf(it) }?.let(::Buckets)
        val ref = last?.let { modelRef(it) }
        val provider = ref?.providerID?.let { id -> providers?.all?.firstOrNull { it.id == id } }
        val model = ref?.let { (providerID, modelID) ->
            if (providerID != null && modelID != null) providers?.model(providerID, modelID) else null
        }
        // `model?.limit.context` upstream; the catalog row can be absent while
        // the screen still knows the limit it shows in the header ring, and the
        // two must never disagree.
        val limit = model?.limitContext ?: contextLimit
        val usage = if (buckets != null && limit != null && limit > 0) {
            Math.round(buckets.total.toDouble() / limit * 100.0).toInt()
        } else {
            null
        }

        val stats = listOf(
            // `info()?.title ?? params.id ?? "—"`
            Stat(Kind.SESSION, sessionTitle(session)),
            Stat(Kind.MESSAGES, number(messages.size.toLong())),
            // `provider?.name ?? message.model.providerID` then `?? "—"`
            Stat(Kind.PROVIDER, provider?.name?.takeIf { it.isNotBlank() } ?: ref?.providerID ?: DASH),
            // `model?.name ?? message.model.id` then `?? "—"`
            Stat(Kind.MODEL, model?.name?.takeIf { it.isNotBlank() } ?: ref?.modelID ?: DASH),
            Stat(Kind.LIMIT, number(limit?.toLong())),
            Stat(Kind.TOTAL_TOKENS, buckets?.let { number(it.total) } ?: DASH),
            Stat(Kind.USAGE, percent(usage)),
            Stat(Kind.INPUT_TOKENS, buckets?.let { number(it.input) } ?: DASH),
            Stat(Kind.OUTPUT_TOKENS, buckets?.let { number(it.output) } ?: DASH),
            Stat(Kind.REASONING_TOKENS, buckets?.let { number(it.reasoning) } ?: DASH),
            // `"${number(read)} / ${number(write)}"` — both halves, even when absent.
            Stat(
                Kind.CACHE_TOKENS,
                (buckets?.let { number(it.cacheRead) } ?: DASH) + " / " +
                    (buckets?.let { number(it.cacheWrite) } ?: DASH),
            ),
            Stat(Kind.USER_MESSAGES, number(messages.count { messageType(it) == ROLE_USER }.toLong())),
            Stat(Kind.ASSISTANT_MESSAGES, number(messages.count { messageType(it) == ROLE_ASSISTANT }.toLong())),
            // `usd().format(info()?.cost ?? 0)` upstream; this server leaves the
            // session-level `cost` out, so the screen's own 总成本 is the
            // fallback rather than a bare `—` next to a header that shows a
            // number.
            Stat(Kind.TOTAL_COST, MobileUsage.cost(sessionCost(session) ?: sessionCost)),
            Stat(Kind.SESSION_CREATED, time(sessionCreatedAt(session), zone, locale)),
            Stat(Kind.LAST_ACTIVITY, time(last?.let { createdAt(it) }, zone, locale)),
        )
        return Snapshot(
            stats = stats,
            messages = rawMessages(messages, zone, locale),
            systemPrompt = systemPrompt(messages),
            export = exportSource(session, messages),
        )
    }

    /**
     * The tab's export source, or `null` when there is nothing to export yet
     * (both payloads absent — the pre-load state of the mobile usage view).
     * Upstream's `sessionExportFilename(data.info)` falls back to the id, and a
     * row without one exports as a session-shaped file rather than failing.
     */
    private fun exportSource(session: JSONObject?, messages: List<JSONObject>): Export? {
        if (session == null && messages.isEmpty()) return null
        return Export(
            filename = SessionExport.filename(
                id = session?.optString("id", "")?.takeIf { it.isNotBlank() } ?: "session",
                title = session?.optString("title", "")?.takeIf { it.isNotBlank() },
                slug = session?.optString("slug", "")?.takeIf { it.isNotBlank() },
            ),
            info = session,
            messages = messages,
        )
    }

    /**
     * The raw list of the tab's second half, in server order (upstream renders
     * `messages()` unchanged, `:308`): `type • id`, the formatted `time.created`
     * and the untouched two-space JSON.
     */
    fun rawMessages(
        messages: List<JSONObject>,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): List<RawMessage> = messages.mapIndexed { index, row ->
        RawMessage(
            id = infoOf(row).optString("id", "").ifEmpty { "message:$index" },
            type = messageType(row).ifEmpty { TYPE_UNKNOWN },
            time = time(createdAt(row), zone, locale),
            json = row.toString(SessionExport.INDENT),
        )
    }

    /** Last `system` message's trimmed text, `null` when there is none or it is blank. */
    fun systemPrompt(messages: List<JSONObject>): String? =
        messages.lastOrNull { messageType(it) == ROLE_SYSTEM }
            ?.let { messageText(it).trim() }
            ?.takeIf { it.isNotEmpty() }

    /** `1,234`; [DASH] for an absent value. Grouping is fixed to `Locale.US`. */
    fun number(value: Long?): String =
        value?.let { String.format(Locale.US, "%,d", it) } ?: DASH

    /** `75%`; [DASH] for an absent value (`formatter().percent`, `session-context-format.ts:16-20`). */
    fun percent(value: Int?): String = value?.let { "${number(it.toLong())}%" } ?: DASH

    /**
     * Absolute local time (`formatter().time`, `session-context-format.ts:21-24`):
     * falsy/absent → [DASH]. The preset is upstream's luxon `DATETIME_MED`
     * (`{year, month:short, day, hour, minute}`).
     */
    fun time(value: Long?, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        if (value == null || value <= 0L) return DASH
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(zone)
            .format(Instant.ofEpochMilli(value))
    }

    // ------------------------------------------------------------------ rows

    /** `info` of one `WithParts` row; a flat v2 message is its own info. */
    private fun infoOf(row: JSONObject): JSONObject = row.optJSONObject("info") ?: row

    /**
     * The message discriminator: v2's flat `type`, else v1's `role`
     * (`session-context-tab.tsx:115,144,154` all test `message.type`).
     */
    private fun messageType(row: JSONObject): String {
        val info = infoOf(row)
        return info.optString("type", "").ifEmpty { info.optString("role", "") }
    }

    private fun isAssistantWithTokens(row: JSONObject): Boolean =
        messageType(row) == ROLE_ASSISTANT && tokensOf(row) != null

    /** Raw `info.tokens`, or `null` when the message carries none (`!!item.tokens`, `:115`). */
    private fun tokensOf(row: JSONObject): JSONObject? = infoOf(row).optJSONObject("tokens")

    private fun createdAt(row: JSONObject): Long? =
        infoOf(row).optJSONObject("time")?.optLong("created", 0L)?.takeIf { it > 0L }

    private fun sessionCost(session: JSONObject?): Double? =
        session?.optDouble("cost", Double.NaN)?.takeIf { !it.isNaN() }

    private fun sessionCreatedAt(session: JSONObject?): Long? =
        session?.optJSONObject("time")?.optLong("created", 0L)?.takeIf { it > 0L }

    private fun sessionTitle(session: JSONObject?): String =
        session?.optString("title", "")?.takeIf { it.isNotBlank() }
            ?: session?.optString("id", "")?.takeIf { it.isNotBlank() }
            ?: DASH

    /**
     * The `{providerID, modelID}` pair of a message: flat (`MessageV1.Assistant`)
     * first, then the nested `model` object of v2, whose model key is `id`
     * (`SessionMessageInfo`: `model: {id, providerID, variant}`).
     */
    private fun modelRef(row: JSONObject): ModelRef {
        val info = infoOf(row)
        val nested = info.optJSONObject("model")
        val providerID = info.optString("providerID", "")
            .ifEmpty { nested?.optString("providerID", "").orEmpty() }
        val modelID = info.optString("modelID", "")
            .ifEmpty { nested?.optString("modelID", "").orEmpty() }
            .ifEmpty { nested?.optString("id", "").orEmpty() }
        return ModelRef(providerID.takeIf { it.isNotEmpty() }, modelID.takeIf { it.isNotEmpty() })
    }

    /**
     * v2's flat `text`, else the concatenated text parts of a v1 `{info, parts}`
     * row (v1 system messages have no `text` field of their own).
     */
    private fun messageText(row: JSONObject): String {
        infoOf(row).optString("text", "").takeIf { it.isNotEmpty() }?.let { return it }
        val parts = row.optJSONArray("parts") ?: infoOf(row).optJSONArray("parts") ?: return ""
        return buildString {
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                if (part.optString("type", "") != "text") continue
                if (isNotEmpty()) append('\n')
                append(part.optString("text", ""))
            }
        }
    }

    private data class ModelRef(val providerID: String?, val modelID: String?)

    /** The five token buckets of `info.tokens`, all defaulted to 0 when absent. */
    private class Buckets(tokens: JSONObject) {
        val input = tokens.optLong("input", 0L)
        val output = tokens.optLong("output", 0L)
        val reasoning = tokens.optLong("reasoning", 0L)
        val cacheRead = tokens.optJSONObject("cache")?.optLong("read", 0L) ?: 0L
        val cacheWrite = tokens.optJSONObject("cache")?.optLong("write", 0L) ?: 0L
        val total = input + output + reasoning + cacheRead + cacheWrite
    }

    /** `MessageV1`/v2 message roles; the raw payload uses one of these. */
    private const val ROLE_USER = "user"
    private const val ROLE_ASSISTANT = "assistant"
    private const val ROLE_SYSTEM = "system"

    /** Fallback discriminator of a row that carries neither `type` nor `role`. */
    private const val TYPE_UNKNOWN = "unknown"
}
