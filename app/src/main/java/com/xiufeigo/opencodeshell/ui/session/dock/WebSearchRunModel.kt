package com.xiufeigo.opencodeshell.ui.session.dock

import com.xiufeigo.opencodeshell.v2.WebSearchResult
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Pure rules behind the **manual** websearch run ([SessionWebSearchRunDock]) —
 * the execution half of `POST /api/websearch` ([com.xiufeigo.opencodeshell.v2.
 * websearchQuery]), which the consent dock never touches.
 *
 * Upstream only ever *consents* to the agent's own search
 * (`_upstream/packages/app/src/session/requests/websearch.ts` — provider pick +
 * allow/disable/choose reply); the query route itself is what the server-side
 * websearch tool calls. This model is what turns one `{query, providerID?}`
 * round-trip into dock rows: the run state machine, the provider argument and
 * the row projection of `WebSearch.Result` (`schema/src/websearch.ts:23-33`).
 *
 * The provider pick reuses the consent dock's vocabulary:
 * [WebSearchModel.ANY_VALUE] ("Any") means **omit** `providerID` so the
 * configured default runs (`websearch.ts:36-37`).
 *
 * No Compose, no network — every rule here is plain JVM unit test.
 */
object WebSearchRunModel {

    /** What the tray's status line renders. [IDLE] = nothing ran yet (no line). */
    enum class Status { IDLE, LOADING, EMPTY, DONE, FAILED }

    /**
     * Failure classes of `POST /api/websearch`
     * (`server/src/handlers/websearch.ts:24-42`): `400 InvalidRequestError` with
     * `kind` ∈ `websearch_provider_required` / `websearch_provider_not_found` /
     * `websearch_disabled`, or `503` when the provider request itself fails.
     * The wire `kind` survives into the [com.xiufeigo.opencodeshell.v2.V2Api.
     * ApiException] message via `errorText`'s `_tag`, so the classifier matches
     * on the message rather than re-parsing the body.
     */
    enum class ErrorKind { NONE, DISABLED, PROVIDER_NOT_FOUND, PROVIDER_REQUIRED, OTHER }

    /** One result row, ready to render. */
    data class Row(
        val url: String,
        /** `title` or the url when the provider sent none. */
        val title: String,
        val host: String,
        val snippet: String,
        /** Localized `published` date, `null` when the result carries no time. */
        val published: String?,
    )

    /** A blank query is not runnable and the tray keeps its idle status. */
    fun canRun(query: String, running: Boolean): Boolean = !running && query.isNotBlank()

    /**
     * The `providerID` half of the request body: [WebSearchModel.ANY_VALUE] and
     * blank both mean "no providerID" (`websearchQuery` omits the key then).
     */
    fun providerArg(selection: String?): String? =
        selection?.takeIf { it.isNotBlank() && it != WebSearchModel.ANY_VALUE }

    /** Status line of the tray; [hasRun] separates "never ran" from "no results". */
    fun status(
        running: Boolean,
        failed: Boolean,
        hasRun: Boolean,
        resultCount: Int,
    ): Status = when {
        running -> Status.LOADING
        failed -> Status.FAILED
        !hasRun -> Status.IDLE
        resultCount == 0 -> Status.EMPTY
        else -> Status.DONE
    }

    /** [ErrorKind] of one failure; see the enum's KDoc for the wire mapping. */
    fun errorKind(code: Int, message: String): ErrorKind = when {
        "websearch_disabled" in message -> ErrorKind.DISABLED
        "websearch_provider_not_found" in message -> ErrorKind.PROVIDER_NOT_FOUND
        "websearch_provider_required" in message -> ErrorKind.PROVIDER_REQUIRED
        code == 400 || code == 503 -> ErrorKind.OTHER
        else -> ErrorKind.OTHER
    }

    /** `title` when present, else the url — every result names itself somehow. */
    fun titleOf(result: WebSearchResult): String =
        result.title?.takeIf { it.isNotBlank() } ?: result.url

    /** Host of a result url; a url the `URI` parser rejects shows itself whole. */
    fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url

    /**
     * Localized date of `time.published` (epoch millis). Injectable [zone] /
     * [locale] keep the rule testable; the preset is upstream's luxon
     * `DATE_MED`-ish short date (`DATETIME_MED` is what the context tab uses for
     * full timestamps — a result list only needs the day).
     */
    fun published(timeMillis: Long?, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
        if (timeMillis == null || timeMillis <= 0L) return null
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(locale)
            .withZone(zone)
            .format(Instant.ofEpochMilli(timeMillis))
    }

    /** Row projection of the response's `results`, in server order. */
    fun rows(
        results: List<WebSearchResult>,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): List<Row> = results.map { result ->
        Row(
            url = result.url,
            title = titleOf(result),
            host = hostOf(result.url),
            snippet = result.content.orEmpty(),
            published = published(result.timePublished, zone, locale),
        )
    }
}
