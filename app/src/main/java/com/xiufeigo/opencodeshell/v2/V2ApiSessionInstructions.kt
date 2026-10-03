package com.xiufeigo.opencodeshell.v2

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Session instruction entries + manual skill activation, as extension functions
 * on [V2Api] (parallel change to `V2Api.kt`; the class's `internal` HTTP seam
 * is the only coupling).
 *
 * | route | wire |
 * |---|---|
 * | list | `GET /api/experimental/session/{sessionID}/instructions/entries` → `{data:[InstructionEntry.Info]}` (`session.ts:646-663`) |
 * | put | `PUT /api/experimental/session/{sessionID}/instructions/entries/{key}` body `{value}` → `204` (`session.ts:665-684`) |
 * | remove | `DELETE /api/experimental/session/{sessionID}/instructions/entries/{key}` → `204` (`session.ts:686-705`) |
 * | skill | `POST /api/experimental/session/{sessionID}/skill` body `{id, resume?}` → `204` (`session.ts:433-451`) |
 *
 * Contrary to a per-key **PUT** — there is no bulk write route. All four carry
 * `sessionLocationMiddleware`, but openapi declares **no** `location[directory]`
 * query on any of them (the location is resolved server-side from the session),
 * so these call `builder(..., withLocation = false)`.
 *
 * Contract source: `protocol/src/groups/session.ts` + `schema/src/
 * instruction-entry.ts` + live `GET /openapi.json` (opencode 2.0.11, 113 paths).
 * Wire shapes are `UNVERIFIED` against a live session (read-only probe of
 * openapi; write routes are covered by MockWebServer tests only).
 */

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * `InstructionEntry.Info` (`schema/src/instruction-entry.ts:16-19`) —
 * `key` plus one arbitrary JSON `value` attached to the session's instructions.
 *
 * [value] is the org.json representation: `JSONObject`, `JSONArray`, `String`,
 * `Number`, `Boolean`, or Kotlin `null` for JSON `null`. The server derives the
 * namespaced instructions key as `api/<key>` (`instruction-entry.ts:5-8`), and
 * `key` must match `^[a-z0-9][a-z0-9._-]*$` (`instruction-entry.ts:10`); the
 * path segment is encoded via `addPathSegment`, so a hostile key cannot re-root
 * the URL. Values larger than 256 KiB are rejected `413`
 * (`instruction-entry.ts:30-40`).
 */
data class InstructionEntry(
    val key: String,
    val value: Any?,
) {
    companion object {
        fun fromJson(obj: JSONObject): InstructionEntry = InstructionEntry(
            key = obj.optString("key", ""),
            value = obj.opt("value").takeUnless { it == null || it === JSONObject.NULL },
        )
    }
}

/**
 * `GET …/instructions/entries` → the API-managed entries attached to the
 * session, `[]` when the envelope omits `data`. `404` is `SessionNotFoundError`.
 */
fun V2Api.sessionInstructionEntries(sessionID: String): List<InstructionEntry> =
    v2ObjectList(dataArray(callBody(builder("experimental/session/$sessionID/instructions/entries", false).get().build())))
        .map(InstructionEntry::fromJson)

/**
 * `PUT …/instructions/entries/{key}` body `{"value":<json>}` → `204`.
 *
 * Attach or replace one durable entry; the change is announced to the model at
 * the next step boundary (`session.ts:678-679`). [value] must be a JSON type
 * (`JSONObject` / `JSONArray` / `String` / `Number` / `Boolean`) or `null`
 * (encoded as JSON `null`, key still present). `413` carries
 * `InstructionEntryValueTooLargeError{actualBytes,maxBytes}`.
 */
fun V2Api.sessionInstructionEntryPut(sessionID: String, key: String, value: Any?) {
    val body = JSONObject()
    body.put("value", value ?: JSONObject.NULL)
    callUnit(
        builder("experimental/session/$sessionID/instructions/entries/$key", false)
            .put(body.toString().toRequestBody(JSON_MEDIA))
            .build(),
    )
}

/**
 * `DELETE …/instructions/entries/{key}` → `204`. Removes one entry; the removal
 * is announced to the model at the next step boundary (`session.ts:699-701`).
 * Unknown keys are a no-op `204` (only a missing session is `404`).
 */
fun V2Api.sessionInstructionEntryRemove(sessionID: String, key: String) {
    callUnit(builder("experimental/session/$sessionID/instructions/entries/$key", false).delete().build())
}

/**
 * `POST …/skill` body `{"id":<skillID>, "resume":<bool>?}` → `204`.
 *
 * Activates a skill for the session by appending a skill message and resuming
 * execution (`session.ts:446-448`). [resume] is omitted from the body when null
 * (`Schema.optional`, not `false`). `404` is `SessionNotFoundError |
 * SkillNotFoundError`.
 */
fun V2Api.sessionSkillActivate(sessionID: String, skillID: String, resume: Boolean? = null) {
    val body = JSONObject().put("id", skillID)
    if (resume != null) body.put("resume", resume)
    postUnit("experimental/session/$sessionID/skill", false, body)
}
