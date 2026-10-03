package com.xiufeigo.opencodeshell.v2

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Three routes held as extension functions rather than [V2Api] members (parallel
 * change to `V2Api.kt`); they use the class's `internal` HTTP seam only — same
 * OkHttp client, same `basicAuthorizationHeader`, same [V2Api.ApiException] on
 * non-2xx.
 *
 * | piece | contract |
 * |---|---|
 * | `POST /api/websearch` | body `{query, providerID?}`, success `{location, data:{providerID, results[]}}` |
 * | `GET /api/vcs/base` | success `{location, data}` with `data` a `Vcs.Base` or `null` |
 * | `POST /api/location/reload` | **204**, no query and no body — rebuilds **every** loaded location |
 *
 * `GET /api/websearch/provider` is already [V2Api.websearchProviders] — not
 * repeated here. Both POSTs are `UNVERIFIED` live (write routes are
 * MockWebServer-only); `GET /api/vcs/base`'s envelope is `Measured live` — see
 * [vcsBase].
 */

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * `POST /api/websearch` body `{query, providerID?}` →
 * `{location, data:{providerID, results:[…]}}` — `websearch.query`
 * (`protocol/src/groups/websearch.ts:25-29`).
 *
 * | piece | contract |
 * |---|---|
 * | body | `WebSearch.Input` `{query, providerID?}`, `additionalProperties:false` (`schema/src/websearch.ts:17-20`) — [providerID] is omitted when null/blank and then the configured default runs (`websearch.ts:36-37`) |
 * | success | `Location.response(WebSearch.Response)` (`websearch.ts:28`): `data` is `{providerID, results:[…]}` (`schema/src/websearch.ts:35-38`) |
 * | query | `location[directory]` deep-object, like `websearch/provider` |
 * | errors | `400 InvalidRequestError` with `kind` ∈ `websearch_provider_required` / `websearch_provider_not_found` / `websearch_disabled`; `503 ServiceUnavailableError` when the provider request fails (`server/src/handlers/websearch.ts:24-42`) |
 *
 * `UNVERIFIED` live (write route — measured only against MockWebServer).
 */
fun V2Api.websearchQuery(
    query: String,
    providerID: String? = null,
    locationDirectory: String? = null,
): WebSearchResponse {
    val body = JSONObject().put("query", query)
    if (!providerID.isNullOrBlank()) body.put("providerID", providerID)
    val request = builder("websearch", true) { located(it, locationDirectory) }
        .post(body.toString().toRequestBody(JSON_MEDIA))
        .build()
    return WebSearchResponse.fromJson(dataObject(callBody(request)))
}

/**
 * `GET /api/vcs/base` → `{location, data}` where `data` is a `Vcs.Base` or
 * `null` — `vcs.base` (`protocol/src/groups/vcs.ts:39-42`).
 *
 * | piece | contract |
 * |---|---|
 * | success | `Location.response(Schema.NullOr(Vcs.Base))` (`vcs.ts:41`); `Vcs.Base` is `{name, ref, source}` with `source` ∈ `"reflog"` \| `"default"` (`schema/src/vcs.ts:21-25`) |
 * | null | before the first commit, when the provider lacks base metadata, or when the location has no VCS provider (`vcs.ts:49-50`, `core/src/vcs.ts:174`, `core/src/plugin/vcs/git.ts:278-279`) — ambiguous history wants an explicit `base` on `vcs/diff` |
 * | errors | `503 ServiceUnavailableError` when inference fails ("Choose a review base", …) (`server/src/handlers/vcs.ts:19-25`) |
 *
 * `Measured live` 2026-09-23 on 2.0.11: `200 {"location":{"directory":"…"},
 * "data":null}` (server default location and an on-default-branch git checkout).
 * The non-null `data` object is `UNVERIFIED` live — shape is the schema above
 * plus upstream's own fixtures (`core/test/vcs.test.ts`: `{name:"main",
 * ref:"refs/heads/main", source:"default"}`, `{name:"v2",
 * ref:"refs/remotes/origin/v2", source:"reflog"}`). Missing `data` is read as
 * `null`, same as the explicit JSON null.
 */
fun V2Api.vcsBase(locationDirectory: String? = null): VcsBase? {
    // `dataObject` would fabricate `{}` for the NullOr payload — unwrap by hand.
    val root = parseObject(
        callBody(builder("vcs/base", true) { located(it, locationDirectory) }.get().build()),
    )
    if (root.isNull("data")) return null
    return root.optJSONObject("data")?.let(VcsBase::fromJson)
}

/**
 * `POST /api/location/reload` → **204** — `location.reload`
 * (`protocol/src/groups/location.ts:51-53`).
 *
 * No query and no payload: it shuts down and rebuilds **every** loaded
 * location, cancels pending permissions and forms, and answers once the
 * replacement builds settle (`location.ts:57-59`) — so it must not carry
 * `location[directory]` (and never boots the caller's location first,
 * `location.ts:29-30`). An empty `{}` JSON body goes on the wire, same as the
 * other no-payload POSTs ([V2Api.background]). `UNVERIFIED` live (write route).
 */
fun V2Api.locationReload() {
    postUnit("location/reload", false, JSONObject())
}

/** `WebSearch.Response` — `{providerID, results:[…]}` (`schema/src/websearch.ts:35-38`). */
data class WebSearchResponse(
    val providerID: String,
    val results: List<WebSearchResult>,
) {
    companion object {
        fun fromJson(obj: JSONObject): WebSearchResponse = WebSearchResponse(
            providerID = obj.optString("providerID", ""),
            results = v2ObjectList(obj.optJSONArray("results") ?: JSONArray()).map(WebSearchResult::fromJson),
        )
    }
}

/** `WebSearch.Result` — `{url, title?, content?, time:{published?}}` (`schema/src/websearch.ts:23-33`). */
data class WebSearchResult(
    val url: String,
    val title: String?,
    val content: String?,
    /** `time.published` — epoch **millis**; `null` when absent. */
    val timePublished: Long?,
) {
    companion object {
        fun fromJson(obj: JSONObject): WebSearchResult {
            val time = obj.optJSONObject("time")
            return WebSearchResult(
                url = obj.optString("url", ""),
                title = optStringOrNull(obj, "title"),
                content = optStringOrNull(obj, "content"),
                timePublished = if (time?.has("published") == true) time.optLong("published") else null,
            )
        }
    }
}

/** `Vcs.Base` — `{name, ref, source}`, `source` ∈ `"reflog"` or `"default"` (`schema/src/vcs.ts:21-25`). */
data class VcsBase(
    /** Short branch name — `refs/heads/` and `refs/remotes/<remote>/` stripped. */
    val name: String,
    /** Full ref the diff merges against, e.g. `refs/heads/main` or `refs/remotes/origin/v2`. */
    val ref: String,
    val source: String,
) {
    companion object {
        const val SOURCE_REFLOG = "reflog"
        const val SOURCE_DEFAULT = "default"

        fun fromJson(obj: JSONObject): VcsBase = VcsBase(
            name = obj.optString("name", ""),
            ref = obj.optString("ref", ""),
            source = obj.optString("source", ""),
        )
    }
}
