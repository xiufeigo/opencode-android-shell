package com.xiufeigo.opencodeshell.v2

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Catalog single-item routes that sit next to [V2Api]'s list endpoints:
 * `PATCH /api/project/{projectID}` (`protocol/src/groups/project.ts:22-33`),
 * `GET /api/agent/{agentID}` (`agent.ts:24-38`),
 * `POST /api/worktree/refresh` (`worktree.ts:64-76`) and
 * `GET|DELETE /api/debug/location` (`debug.ts:8-31`). `GET /api/worktree`
 * stays [V2Api.worktrees].
 *
 * Held as extension functions rather than [V2Api] members (parallel change to
 * `V2Api.kt`); they use the class's `internal` HTTP seam only — same OkHttp
 * client, same `basicAuthorizationHeader`, same [V2Api.ApiException] on non-2xx.
 */

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * `Project.Icon` (`schema/src/project.ts:20-25`) — all keys optional; the
 * Kotlin property `override` is the wire key `override`.
 */
data class ProjectIcon(
    val url: String? = null,
    val `override`: String? = null,
    val color: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().also {
        url?.let { v -> it.put("url", v) }
        `override`?.let { v -> it.put("override", v) }
        color?.let { v -> it.put("color", v) }
    }
}

/**
 * `Project.Commands` (`schema/src/project.ts:26-31`): `start` is the startup
 * script run when creating a new workspace (worktree).
 */
data class ProjectCommands(val start: String? = null) {
    fun toJson(): JSONObject = JSONObject().also {
        start?.let { v -> it.put("start", v) }
    }
}

/**
 * `PATCH /api/project/{projectID}` — `project.ts:22-33`, body `UpdatePayload`
 * = `Project.UpdateInput` minus `projectID` (`project.ts:7`,
 * `schema/src/project.ts:50-57`).
 *
 * | piece | contract |
 * |---|---|
 * | body | `{canonical?, name?, icon?, commands?}` (`additionalProperties:false`) |
 * | null params | key omitted → server leaves the field unchanged (`optionalKey`; JSON `null` is not in the contract) |
 * | success | **bare** `Project.Info` — no `data` envelope (`handlers/project.ts:12-20` returns the service value unwrapped, unlike `handlers/agent.ts:15-25`) |
 * | errors | `404 ProjectNotFoundError`, `400 InvalidRequestError` |
 *
 * Returns [V2Api.V2Project] like [V2Api.projects]; `name`/`vcs`/`icon`/
 * `commands` stay in `raw`. `Project` shape Measured live 2026-09-23 via
 * `GET /api/project`; the PATCH response itself is UNVERIFIED live (write).
 */
fun V2Api.updateProject(
    projectID: String,
    name: String? = null,
    canonical: String? = null,
    icon: ProjectIcon? = null,
    commands: ProjectCommands? = null,
): V2Api.V2Project {
    val body = JSONObject()
    name?.let { body.put("name", it) }
    canonical?.let { body.put("canonical", it) }
    icon?.let { body.put("icon", it.toJson()) }
    commands?.let { body.put("commands", it.toJson()) }
    val request = builder("project/$projectID", false)
        .patch(body.toString().toRequestBody(JSON_MEDIA))
        .build()
    return V2Api.V2Project.fromJson(parseObject(callBody(request)))
}

/**
 * `GET /api/agent/{agentID}` — `agent.ts:24-38`.
 *
 * `{location, data:Agent.Info}` envelope with the same element shape as
 * [V2Api.agents] (`schema/src/agent.ts:29-51`); Measured live 2026-09-23 for
 * `build`. `404 AgentNotFoundError`.
 */
fun V2Api.agent(agentID: String, locationDirectory: String? = null): V2Api.AgentInfo = V2Api.AgentInfo.fromJson(
    dataObject(callBody(builder("agent/$agentID", true) { located(it, locationDirectory) }.get().build())),
)

/**
 * `POST /api/worktree/refresh` — `worktree.ts:64-76`: body `{"projectID"}`
 * (`additionalProperties:false`), **204** once discovery has reconciled the
 * saved inventory. `400 WorktreeError` (`{name:"WorktreeError",
 * data:{message,forceRequired?}}`), `404 ProjectNotFoundError`. The route has
 * no `location` query. UNVERIFIED live (write).
 */
fun V2Api.refreshWorktrees(projectID: String) {
    postUnit("worktree/refresh", false, JSONObject().put("projectID", projectID))
}

/**
 * `GET /api/debug/location` — `debug.ts:8-17`: **bare** array of
 * `Location.PublicRef` `{directory}` (`schema/src/location.ts:14-17`, no
 * envelope — do not use `dataObject`). Measured live 2026-09-23:
 * `[{"directory":"/home/…"}]`. Returns the directories of every location the
 * server currently has loaded; a row whose `directory` is JSON `null` (the
 * literal `"null"` on Android's `org.json`) or absent is dropped rather than
 * listed as a location that does not exist.
 */
fun V2Api.debugLocations(): List<String> {
    val body = callBody(builder("debug/location", false).get().build()).trim()
    if (!body.startsWith("[")) {
        throw V2Api.ApiException(V2Api.JSON_BODY_CODE, "response is not a bare array")
    }
    return v2ObjectList(JSONArray(body)).mapNotNull { optStringOrNull(it, "directory") }
}

/**
 * `DELETE /api/debug/location` — `debug.ts:19-31`: dispose the requested
 * location's cached services so its next use boots them fresh → **204**.
 * Takes `location[directory]` (deepObject, `groups/location.ts:7-18`); with
 * no override the instance default directory is sent. UNVERIFIED live (write).
 */
fun V2Api.evictDebugLocation(locationDirectory: String? = null) {
    callUnit(builder("debug/location", true) { located(it, locationDirectory) }.delete().build())
}
