package com.xiufeigo.opencodeshell.ui.session.context

import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.ui.session.export.SessionExport
import com.xiufeigo.opencodeshell.ui.session.export.SessionExportKind
import com.xiufeigo.opencodeshell.ui.session.header.ContextUsage
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
 * - **`User Messages`** counts prompts through [isUserPrompt], which is
 *   upstream's `type === "user"` plus the one extra rule the v1 shape needs (its
 *   injected records are `role: "user"` rows, so the discriminator alone counts
 *   frames). See that function — the v19 device finding is why.
 */
object SessionContextModel {

    /** Upstream `createSessionContextFormatter`'s placeholder for every absent value. */
    const val DASH = "\u2014"

    /** Upstream `context.stats.*` (`session-context-tab.tsx:173-193`), in order. */
    enum class Kind {
        SESSION,

        /**
         * **All** frames of the message list, `system`/injected rows included —
         * upstream `counts().all` (`:133-141`), i.e. `messages().length`. Not
         * `USER_MESSAGES + ASSISTANT_MESSAGES`; the label carries that ("all
         * frames", see [SessionContextPanel]'s `oc_context_stats_messages`).
         */
        MESSAGES,
        PROVIDER,
        MODEL,

        /** The model's effective window, `min(limit.input, limit.context)` (see [project]). */
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

    /** Three-way affordance of the tab's always-rendered export button. */
    enum class ExportState {
        /** Payloads arrived — the payload-backed rows are enabled. */
        READY,
        /** Nothing loaded yet — those rows are disabled with a waiting hint. */
        EMPTY,
        /** The backing fetch failed — those rows are disabled with the error. */
        FAILED,
    }

    /**
     * The four rows of the raw-messages header's menu, in render order
     * (`session-context-tab.tsx:296-307` for the first; the two route-backed
     * exports and the import are this shell's additions, `research/wave6_export.md`).
     *
     * [kind] is the save format the three export rows use; [IMPORT] has none —
     * it reads a file the user picks and posts it to `session/import`.
     */
    enum class ExportRow(val kind: SessionExportKind?) {
        /** Upstream's hand-rolled `{info, messages}` pair, serialized from [Snapshot.export]. */
        ASSEMBLED(SessionExportKind.ASSEMBLED),

        /** `GET /api/experimental/session/{id}/export` — needs the session id. */
        OFFICIAL(SessionExportKind.OFFICIAL),

        /** The same route with `?sanitize=true`. */
        SANITIZED(SessionExportKind.SANITIZED),

        /** `POST /api/experimental/session/import` — needs no payload and no id. */
        IMPORT(null),
    }

    /**
     * Whether [row] can run right now — the one place the menu's enablement
     * rules live, so the panel renders them and the JVM test pins them.
     *
     * - [payloadReady]: the tab's `{info, messages}` payload is in hand
     *   (`Snapshot.export != null`). [ExportRow.ASSEMBLED] serializes it, so it
     *   needs it;
     * - [sessionID]: the raw session id, `null` until the session half lands.
     *   Both route-backed rows are keyed by it;
     * - [exportBusy]: one of the three export rows has an attempt out. It gates
     *   those rows **only**;
     * - [importBusy]: the import row's own attempt.
     *
     * [ExportRow.IMPORT] deliberately ignores every payload fact: it needs
     * neither the session payload nor the id, so a payload that never loaded
     * (or failed) must not take Import down with it. The v9-41 device report is
     * exactly that bug — the *trigger* was gated on `payloadReady`, so a failed
     * payload left Import permanently unreachable even though its own row would
     * have been enabled.
     */
    fun exportRowEnabled(
        row: ExportRow,
        payloadReady: Boolean,
        sessionID: String?,
        exportBusy: Boolean,
        importBusy: Boolean,
    ): Boolean = when (row) {
        ExportRow.ASSEMBLED -> payloadReady && !exportBusy
        ExportRow.OFFICIAL, ExportRow.SANITIZED -> sessionID != null && !exportBusy
        ExportRow.IMPORT -> !importBusy
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
         * nothing has loaded: the export rows then render disabled (with
         * [exportState]'s hint) instead of disappearing, and flip enabled the
         * moment payloads arrive. The header's own menu stays reachable either
         * way, because [ExportRow.IMPORT] needs none of this.
         */
        val export: Export? = null,
        /**
         * Raw reason of the backing export-frame fetch failure, e.g. the
         * exception message. `null` while loading or loaded — a failure never
         * hides the button, it only moves [exportState] to [ExportState.FAILED]
         * so the panel can show the reason. An arrival wins over a stale
         * failure ([ExportState.READY] while [export] is non-null).
         */
        val exportError: String? = null,
        /**
         * `Session.Info.id` of the payload — the handle the official export
         * (`experimental/session/{id}/export`) and the instructions routes
         * need. `null` until the raw session half lands.
         */
        val sessionID: String? = null,
    ) {
        /** See [ExportState]: arrivals win, then failures, then the waiting state. */
        val exportState: ExportState get() = when {
            export != null -> ExportState.READY
            exportError != null -> ExportState.FAILED
            else -> ExportState.EMPTY
        }

        /**
         * The message drain stopped at its page cap (`SessionExport.Frame`
         * `truncated`): [Export.json] then carries the `truncated` note and the
         * tab shows the "only the first N pages" hint. `false` while nothing is
         * loaded — an absent payload is [ExportState.EMPTY], not a truncation.
         */
        val exportTruncated: Boolean get() = export?.truncated == true

        /** Pages the drain read before stopping (`SessionExport.Frame.truncatedPages`). */
        val exportTruncatedPages: Int get() = export?.truncatedPages ?: 0
    }

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
        /** `Session.Info.id` — the official export route's path half; `null` disables it. */
        val sessionID: String? = null,
        /**
         * The backing read stopped at `V2Api.MessageDrain`'s page cap with the
         * server still offering a cursor — the payload holds the first
         * [truncatedPages] pages of the transcript, not all of it. [json] writes
         * `SessionExport.TRUNCATED_KEY` when set, so the *file* says so too; the
         * panel renders the matching hint (the flag used to be dropped when the
         * context tab rebuilt its own payload from `{info, messages}`).
         */
        val truncated: Boolean = false,
        /** Pages the drain read before stopping. */
        val truncatedPages: Int = 0,
    ) {
        /** `JSON.stringify(data, null, 2)`, exactly `SessionExport.serialize`. */
        val json: String by lazy {
            SessionExport.serialize(info ?: JSONObject(), messages, truncated, truncatedPages)
        }
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
     *
     * [activeMessages] is the v2 **active** context (`GET /api/session/{id}/
     * context`, `ChatViewModel.activeContextRows`) — every message after the last
     * compaction, and the **live** read behind the header ring's numerator
     * ([lastAssistantTotal] is the shared rule). When given, it owns the `ctx()`
     * half of upstream's stats (`session-context-tab.tsx:114-135,176-193`:
     * provider / model / limit / token buckets / last activity, all read off the
     * last assistant message with tokens), so those rows track the header ring's
     * `activeContextTokens` — the same rows of the same fetch — instead of a
     * pre-compaction turn or a frame read frozen at this tab's first open.
     * Upstream reads that message off `data.session.message.list` instead; `null`
     * keeps that full-list source (v1 servers, before the first read, after a
     * failed one). Counts, the raw list, the system prompt and the export always
     * come from [messages], exactly like `fetchSessionExport` — that half is the
     * export frame, re-read on a session/server change, on the tab's 「刷新」 and
     * once per settled run ([SessionContextLoad]) — not per event.
     *
     * [exportError] is the backing fetch's failure reason; it only feeds
     * `Snapshot.exportState` (disabled button + error hint), never the rows.
     *
     * The `Context Limit` / `Usage` rows show the **effective window**
     * `min(limit.input, limit.context)` ([ContextUsage.windowOf]) rather than the
     * raw `limit.context` upstream prints: the server compacts on the input half,
     * so the v20 device read `Usage 59 %` (619,741 tokens) on a model whose real
     * ceiling is 504,288 — see `ContextUsage`'s class doc. Both halves come off
     * the resolved entry of [providers] itself
     * ([OpenCodeClient.Providers.Model.limitInput]); an entry that carries no
     * `limit.input` (v1, or 74 of the 129 live v20 rows) keeps the upstream
     * `limit.context` reading.
     *
     * [exportTruncated] / [exportTruncatedPages] are the backing read's own
     * report (`SessionExport.Frame`, i.e. `V2Api.MessageDrain.truncated` /
     * `pages`): a transcript longer than the drain's page cap. They only feed
     * `Snapshot.exportTruncated*` — the payload's `truncated` note and the tab's
     * hint — and change nothing else, so a truncated projection is otherwise
     * identical to a complete one.
     */
    fun project(
        session: JSONObject?,
        messages: List<JSONObject>,
        providers: OpenCodeClient.Providers? = null,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        contextLimit: Int? = null,
        sessionCost: Double? = null,
        activeMessages: List<JSONObject>? = null,
        exportError: String? = null,
        exportTruncated: Boolean = false,
        exportTruncatedPages: Int = 0,
    ): Snapshot {
        val last = (activeMessages ?: messages).lastOrNull { isAssistantWithUsage(it) }
        val buckets = last?.let { tokensOf(it) }?.let(::Buckets)
        val ref = last?.let { modelRef(it) }
        val provider = ref?.providerID?.let { id -> providers?.all?.firstOrNull { it.id == id } }
        val model = ref?.let { (providerID, modelID) ->
            if (providerID != null && modelID != null) providers?.model(providerID, modelID) else null
        }
        // `model?.limit.context` upstream — narrowed to the **effective window**
        // `min(limit.input, limit.context)` (v20, [ContextUsage.windowOf]), which
        // is also what `ChatViewModel.contextLimit` resolves to, so the panel's
        // `Context Limit` / `Usage` rows and the header ring cannot disagree. The
        // same helper re-resolves the ref, so both halves can only ever come from
        // the one catalog entry; a row that is absent from the catalog falls back
        // to the screen's own value.
        val limit = ref?.let { (providerID, modelID) ->
            if (providerID != null && modelID != null) {
                ContextUsage.windowOf(providers, providerID, modelID)
            } else {
                null
            }
        } ?: contextLimit
        val usage = if (buckets != null && limit != null && limit > 0) {
            Math.round(buckets.total.toDouble() / limit * 100.0).toInt()
        } else {
            null
        }

        val stats = listOf(
            // `info()?.title ?? params.id ?? "—"`
            Stat(Kind.SESSION, sessionTitle(session)),
            // `counts().all` — **every frame** of the message list, system and
            // injected rows included (`session-context-tab.tsx:133-141`:
            // `const all = messages()` … `all.length`; `messages()` is
            // `data.session.message.list(id)` unfiltered). So it is deliberately
            // *not* `user + assistant`: the v20 device saw `Messages 17` above
            // `User Messages 1` / `Assistant Messages 8` and read the mismatch as
            // a bug. Upstream's semantics are kept (the row is the frame count)
            // and the label says so instead — see
            // `oc_context_stats_messages` in `res/values*/strings.xml`.
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
            // `counts().user` — the prompt rows only; see [isUserPrompt] for why
            // this is not the shared discriminator alone.
            Stat(Kind.USER_MESSAGES, number(messages.count(::isUserPrompt).toLong())),
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
            export = exportSource(session, messages, exportTruncated, exportTruncatedPages),
            exportError = exportError,
            sessionID = session?.optString("id", "")?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The tab's export source, or `null` when there is nothing to export yet
     * (both payloads absent — the pre-load state of the mobile usage view).
     * Upstream's `sessionExportFilename(data.info)` falls back to the id, and a
     * row without one exports as a session-shaped file rather than failing.
     *
     * [truncated] / [truncatedPages] ride along so the rebuilt payload keeps the
     * note the backing read reported (see [Export.truncated]).
     */
    private fun exportSource(
        session: JSONObject?,
        messages: List<JSONObject>,
        truncated: Boolean = false,
        truncatedPages: Int = 0,
    ): Export? {
        if (session == null && messages.isEmpty()) return null
        return Export(
            filename = SessionExport.filename(
                id = session?.optString("id", "")?.takeIf { it.isNotBlank() } ?: "session",
                title = session?.optString("title", "")?.takeIf { it.isNotBlank() },
                slug = session?.optString("slug", "")?.takeIf { it.isNotBlank() },
            ),
            info = session,
            messages = messages,
            sessionID = session?.optString("id", "")?.takeIf { it.isNotBlank() },
            truncated = truncated,
            truncatedPages = truncatedPages,
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

    /**
     * The newest assistant row's usage total, **the header ring's numerator rule**
     * (`input + output + reasoning + cache.read + cache.write`) applied to the
     * rows of `GET /api/session/{id}/context` — [ChatViewModel.activeContextRows].
     * `null` when no row carries usage, or the total is 0, i.e. exactly
     * `OpenCodeClient.tokenTotal`'s shape.
     *
     * This is the one place the numerator is derived, so the ring
     * (`ChatViewModel.activeContextTokens`) and the tab's `Total Tokens` /
     * `Usage` rows ([project] with `activeMessages`) read **the same message of
     * the same fetch** and can never describe different turns.
     *
     * The v17-23 device finding was exactly a split here: the ring re-read this
     * route on every snapshot while the tab kept a second, one-shot read of it
     * (`ChatViewModel.sessionContext`) that froze at the tab's first open — the
     * ring showed 42 % of the live total while the tab still printed the first
     * turn's `9,154` / `1 %`.
     */
    fun lastAssistantTotal(rows: List<JSONObject>?): Long? {
        val last = (rows ?: emptyList()).lastOrNull { isAssistantWithUsage(it) } ?: return null
        val tokens = tokensOf(last) ?: return null
        return Buckets(tokens).total.takeIf { it > 0L }
    }

    /**
     * Whether [row] is a **prompt** — the row `User Messages` counts
     * (upstream `counts()`: `message.type === "user"`, `session-context-tab.tsx:141-149`).
     *
     * On a v2 payload the discriminator alone is the whole rule: the union has
     * exactly one `user` type (`schema/src/session-message.ts:78-86`), and the
     * other ten — `assistant`, `synthetic`, `system`, `shell`, `skill`,
     * `compaction`, `idle`, `agent-switched`, `model-switched`,
     * `location-switched` — are frames. The one the v19 report named, the
     * `model-switched` record a model switch writes
     * (`core/src/session/message-updater.ts:101-115`), is a `model-switched`
     * type and never a `user` one; the tests pin every frame of that list.
     *
     * The **v1 shape** is where the discriminator is not enough, and why this is
     * a named rule rather than an inline comparison: v1 has no `type` field at
     * all (`schema/src/v1/session.ts:327-355`: `Info = User | Assistant`, `role`
     * is the discriminator) and it stores its injected records as
     * `role: "user"` messages — model-facing reminders arrive as `synthetic`
     * text parts. Counting every `role: "user"` row therefore counted frames
     * the user cannot see as prompts. [isInjectedNotice] is the payload's own
     * "this is a notice" test, and a row it cannot prove is a notice stays a
     * prompt (nothing identifies it as a frame).
     */
    fun isUserPrompt(row: JSONObject): Boolean {
        val info = infoOf(row)
        val type = info.optString("type", "").trim()
        if (type.isNotEmpty()) return type.equals(ROLE_USER, ignoreCase = true)
        if (!info.optString("role", "").trim().equals(ROLE_USER, ignoreCase = true)) return false
        return !isInjectedNotice(row, info)
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

    /**
     * The v1 shape's own "this is an injected record, not a prompt" test — the
     * `role: "user"` half of [isUserPrompt].
     *
     * It mirrors the rule the server itself applies when it migrates a v1
     * database into the v2 message store
     * (`core/src/database/v1-migration.bun.ts:277-368`), because that is the one
     * place upstream has to answer the same question: a v1 `role: "user"` row is
     * a **`synthetic`** message when it carries no ordinary text part, no
     * attachment and no agent mention, and at least one synthetic text part
     * (`:326-347`); a row owning a `compaction` part is re-typed `compaction`
     * (`:277-312`); a row of nothing but `subtask` parts is dropped (`:315`).
     * None of the three is a prompt.
     *
     * Deliberately lenient in the other direction: a row with no parts at all,
     * or with only `ignored` parts, is **not** a notice — nothing in the payload
     * identifies it as a frame, and dropping rows we cannot classify would
     * under-count prompts, which is worse than the over-count this rule fixes.
     */
    private fun isInjectedNotice(row: JSONObject, info: JSONObject): Boolean {
        val parts = row.optJSONArray("parts") ?: info.optJSONArray("parts") ?: return false
        var synthetic = false
        var envelope = false
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            when (part.optString("type", "")) {
                // An ordinary (non-synthetic, non-ignored) text part is what the
                // user typed, so the row is a prompt whatever else it carries.
                "text" -> {
                    if (part.optBoolean("ignored", false)) continue
                    if (part.optBoolean("synthetic", false)) {
                        synthetic = true
                    } else {
                        return false
                    }
                }
                // An attachment or an @-mention is content the user chose.
                "file", "agent" -> return false
                "compaction", "subtask" -> envelope = true
            }
        }
        return synthetic || envelope
    }

    /**
     * The row that owns the `ctx()` half of the stats and the ring's numerator:
     * an assistant row carrying a **non-zero** usage total.
     *
     * Upstream's selector is `item.type === "assistant" && !!item.tokens`
     * (`session-context-tab.tsx:115`), which an all-zero `tokens` object also
     * satisfies. The ring's own rule is stricter — `ContextUsage.lastUsage` wants
     * `tokens > 0` — and a tab that answered `0` / `0 %` for a turn the ring
     * ignores is exactly the kind of two-answers-one-screen split the v17-23
     * finding was about. So the stricter rule is the shared one: a zero-usage
     * trailing row is skipped by both sides, and the newest real usage wins.
     */
    private fun isAssistantWithUsage(row: JSONObject): Boolean =
        messageType(row) == ROLE_ASSISTANT &&
            (tokensOf(row)?.let { Buckets(it).total } ?: 0L) > 0L

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
