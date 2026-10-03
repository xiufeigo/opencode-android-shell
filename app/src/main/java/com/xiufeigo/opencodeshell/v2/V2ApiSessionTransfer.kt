package com.xiufeigo.opencodeshell.v2

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Experimental session transfer + stats — `protocol/src/groups/session.ts:201-273`.
 * Extension functions over [V2Api]'s `internal` HTTP seam (parallel change to
 * `V2Api.kt`); same OkHttp client, same `ApiException` on non-2xx.
 *
 * | route | wire |
 * |---|---|
 * | `GET /api/experimental/session/{id}/export` | `?sanitize=true\|false` (`BooleanFromString`, `session.ts:154-159`); → `{data:{info,messages}}` (`session.ts:257-273`) |
 * | `POST /api/experimental/session/import` | `{info, messages, location?}` → `{data:SessionInfo}` (`session.ts:240-255`) |
 * | `GET /api/experimental/session/stats` | `?from&to&project&timezone&tools` → `{data:SessionStats.Info}` (`session.ts:201-218`) |
 *
 * None of the three takes `location[directory]` — import carries its target
 * location in the **body** (`Location.PublicRef` = `{directory}`, `location.ts:14`),
 * export and stats are session/aggregate scoped.
 *
 * **Official export vs the hand-rolled `sessionRaw`+`messagesRaw` pair**
 * (Measured 2026-09-23 on 2.0.11, three sessions of 8/17/36 messages): the
 * default (unsanitized) export's `info` is the `sessionRaw` object
 * element-for-element and `messages` equals `messagesRaw` in the same ascending
 * order — **no extra fields**. What the official route still buys:
 * one round-trip for the complete transcript (no `cursor` paging / `maxPages`
 * truncation), and `sanitize=true`, which only exists here: `title`,
 * `location.directory`, message `text`/`metadata` and part `state`s collapse to
 * `[redacted:<kind>:<id>]` / `{"redacted":"<kind>:<id>"}` markers while ids,
 * times, costs and token counts stay real (Measured live).
 */

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * `GET /api/experimental/session/{sessionID}/export` → [SessionTransferData],
 * the official share/import envelope (`session.ts:257-273`,
 * `session-transfer.ts:7-10` — kept as raw [JSONObject]s so a re-import is
 * lossless; `info`/`messages` are the same public projections
 * `session.get`/`session.message.list` serve, `session.ts:120-128`).
 *
 * @param sanitize `true` sends `?sanitize=true` — the redacted form described
 *   in the file KDoc; `false` sends the literal `false`; `null` omits the query
 *   (server default = unsanitized). Values are the `BooleanFromString` literals
 *   `"true"`/`"false"`, never `1`/`0`.
 */
fun V2Api.sessionExport(sessionID: String, sanitize: Boolean? = null): SessionTransferData {
    val request = builder("experimental/session/$sessionID/export", false) {
        if (sanitize != null) it.addQueryParameter("sanitize", if (sanitize) "true" else "false")
    }.get().build()
    val data = dataObject(callBody(request))
    return SessionTransferData(
        info = data.optJSONObject("info") ?: JSONObject(),
        messages = v2ObjectList(data.optJSONArray("messages") ?: JSONArray()),
    )
}

/**
 * `POST /api/experimental/session/import` → the created [V2Api.SessionInfo]
 * (`session.ts:240-255`). Body is `{info, messages}` verbatim from
 * [transfer] plus `location:{directory}` when [locationDirectory] is set —
 * the target location for the imported session, **not** a query scope.
 *
 * Wire rules (`session.ts:251-252`): if `info.parentID` is set the parent must
 * already exist — import parents before children; a colliding id answers
 * `409 ConflictError`, a missing parent `404 SessionNotFoundError`.
 */
fun V2Api.sessionImport(
    transfer: SessionTransferData,
    locationDirectory: String? = null,
): V2Api.SessionInfo {
    val body = JSONObject()
        .put("info", transfer.info)
        .put("messages", JSONArray(transfer.messages))
    if (!locationDirectory.isNullOrBlank()) {
        body.put("location", JSONObject().put("directory", locationDirectory))
    }
    return V2Api.SessionInfo.fromJson(
        dataObject(callBody(builder("experimental/session/import", false).post(body.toString().toRequestBody(JSON_MEDIA)).build())),
    )
}

/**
 * `GET /api/experimental/session/stats` → [SessionStatsInfo]
 * (`session.ts:201-218`, `session-stats.ts:40-67`): local activity, usage and
 * tool reliability aggregated over a time range.
 *
 * @param from/to inclusive epoch-**millis** bounds, sent as decimal strings
 *   (`Schema.NumberFromString`); omit both for the server's full range.
 * @param project filter by `Project.ID` (the hash from `GET /api/project`).
 * @param timezone IANA zone shifting `activity[].date` day bucketing — measured
 *   `America/Los_Angeles` moved one bucket `2026-07-15`→`2026-07-14` while
 *   `Asia/Shanghai` matched the unzoned default.
 * @param tools `none` | `summary` | `detail` (`session-stats.ts:43`); omitted
 *   is `summary` (measured). `detail` adds `tools.usage`.
 */
fun V2Api.sessionStats(
    from: Long? = null,
    to: Long? = null,
    project: String? = null,
    timezone: String? = null,
    tools: String? = null,
): SessionStatsInfo {
    val request = builder("experimental/session/stats", false) {
        if (from != null) it.addQueryParameter("from", from.toString())
        if (to != null) it.addQueryParameter("to", to.toString())
        if (!project.isNullOrBlank()) it.addQueryParameter("project", project)
        if (!timezone.isNullOrBlank()) it.addQueryParameter("timezone", timezone)
        if (!tools.isNullOrBlank()) it.addQueryParameter("tools", tools)
    }.get().build()
    return SessionStatsInfo.fromJson(dataObject(callBody(request)))
}

/**
 * `SessionTransfer.Data` (`session-transfer.ts:7-10`) as raw halves so an
 * export→import round-trip loses nothing the public projection carries.
 */
data class SessionTransferData(
    val info: JSONObject,
    val messages: List<JSONObject>,
)

/**
 * `SessionStats.Info` (`session-stats.ts:40-67`). Measured live 2026-09-23 on
 * 2.0.11 in all three `tools` modes: `{"mode":"none"}` carries no `totals`,
 * `summary` (the default) adds `totals`, `detail` adds `totals` + `usage[]`
 * where `durationP50` is a nullable finite (ms).
 */
data class SessionStatsInfo(
    val rangeFrom: Long,
    val rangeTo: Long,
    val sessions: Int,
    val subagents: Int,
    val prompts: Int,
    val steps: Int,
    val tokens: Tokens,
    val cost: Double,
    val toolsMode: String,
    val toolsTotals: ToolsTotals?,
    val toolsUsage: List<ToolUsage>,
    val activeDays: Int,
    val streak: Int,
    val activity: List<Activity>,
    val models: List<ModelUsage>,
    val raw: JSONObject,
) {
    /** `TokenUsage.Info` (`token-usage.ts:6-15`). */
    data class Tokens(
        val input: Long,
        val output: Long,
        val reasoning: Long,
        val cacheRead: Long,
        val cacheWrite: Long,
    ) {
        companion object {
            fun fromJson(obj: JSONObject?): Tokens {
                val cache = obj?.optJSONObject("cache")
                return Tokens(
                    input = obj?.optLong("input", 0L) ?: 0L,
                    output = obj?.optLong("output", 0L) ?: 0L,
                    reasoning = obj?.optLong("reasoning", 0L) ?: 0L,
                    cacheRead = cache?.optLong("read", 0L) ?: 0L,
                    cacheWrite = cache?.optLong("write", 0L) ?: 0L,
                )
            }
        }
    }

    /** `SessionStats.ToolTotals` (`session-stats.ts:33-40`). */
    data class ToolsTotals(
        val calls: Int,
        val succeeded: Int,
        val failed: Int,
        val unfinished: Int,
    )

    /** `SessionStats.ToolUsage` (`session-stats.ts:22-31`); `durationP50` in ms. */
    data class ToolUsage(
        val name: String,
        val calls: Int,
        val succeeded: Int,
        val failed: Int,
        val unfinished: Int,
        val durationP50: Double?,
    )

    /** `SessionStats.Activity` (`session-stats.ts:7-11`); `date` is `yyyy-MM-dd`. */
    data class Activity(val date: String, val steps: Int)

    /** `SessionStats.ModelUsage` (`session-stats.ts:13-20`). */
    data class ModelUsage(
        val model: V2Api.ModelRef?,
        val steps: Int,
        val tokens: Tokens,
        val cost: Double,
    )

    companion object {
        fun fromJson(obj: JSONObject): SessionStatsInfo {
            val range = obj.optJSONObject("range")
            val tools = obj.optJSONObject("tools")
            val totals = tools?.optJSONObject("totals")
            return SessionStatsInfo(
                rangeFrom = range?.optLong("from", 0L) ?: 0L,
                rangeTo = range?.optLong("to", 0L) ?: 0L,
                sessions = obj.optInt("sessions", 0),
                subagents = obj.optInt("subagents", 0),
                prompts = obj.optInt("prompts", 0),
                steps = obj.optInt("steps", 0),
                tokens = Tokens.fromJson(obj.optJSONObject("tokens")),
                cost = obj.optDouble("cost", 0.0),
                toolsMode = tools?.optString("mode", "").orEmpty(),
                toolsTotals = totals?.let {
                    ToolsTotals(
                        calls = it.optInt("calls", 0),
                        succeeded = it.optInt("succeeded", 0),
                        failed = it.optInt("failed", 0),
                        unfinished = it.optInt("unfinished", 0),
                    )
                },
                toolsUsage = v2ObjectList(tools?.optJSONArray("usage") ?: JSONArray()).map { usage ->
                    ToolUsage(
                        name = usage.optString("name", ""),
                        calls = usage.optInt("calls", 0),
                        succeeded = usage.optInt("succeeded", 0),
                        failed = usage.optInt("failed", 0),
                        unfinished = usage.optInt("unfinished", 0),
                        durationP50 = if (usage.has("durationP50")) usage.optDouble("durationP50", 0.0) else null,
                    )
                },
                activeDays = obj.optInt("activeDays", 0),
                streak = obj.optInt("streak", 0),
                activity = v2ObjectList(obj.optJSONArray("activity") ?: JSONArray()).map {
                    Activity(date = it.optString("date", ""), steps = it.optInt("steps", 0))
                },
                models = v2ObjectList(obj.optJSONArray("models") ?: JSONArray()).map {
                    ModelUsage(
                        model = it.optJSONObject("model")?.let(V2Api.ModelRef::fromJson),
                        steps = it.optInt("steps", 0),
                        tokens = Tokens.fromJson(it.optJSONObject("tokens")),
                        cost = it.optDouble("cost", 0.0),
                    )
                },
                raw = obj,
            )
        }
    }
}
