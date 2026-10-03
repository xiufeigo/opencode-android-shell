package com.xiufeigo.opencodeshell.v2

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * The secondary HTTP verbs the rest of the v2 surface skipped — held as
 * [V2Api] extension functions over the class's `internal` HTTP seam (same
 * OkHttp client, same `basicAuthorizationHeader`, same [V2Api.ApiException]
 * on non-2xx), same pattern as `V2ApiCredentialPlugin.kt`.
 *
 * | route | contract |
 * |---|---|
 * | `DELETE /api/credential/{credentialID}` | no body → **204** (`credential.ts:35-46`) |
 * | `PUT /api/experimental/persistent-pty/{ptyID}` | `PersistentPty.UpdateInput` → `{"data":Info}` (`persistent-pty.ts:73-79`) |
 * | `DELETE /api/experimental/persistent-pty/{ptyID}` | no body → **204** (`persistent-pty.ts:88-93`) |
 * | `POST /api/shell` | `Shell.CreateInput` → `{"location":…,"data":Info}` (`shell.ts:24-38`) |
 * | `POST /api/worktree` | `Worktree.CreateInput` → **bare** `Worktree.Info` (`worktree.ts:36-48`) |
 * | `DELETE /api/worktree` | `Worktree.RemoveInput` **as JSON body** → **204** (`worktree.ts:50-62`) |
 * | `DELETE /api/session/{sessionID}/form/{formID}` | no body → **204** (`session.ts:855-868`) |
 *
 * `PATCH`/`POST …/activate` on `credential`, `GET` on `persistent-pty`, the
 * `shell` get/output/remove trio, `GET /api/worktree` and `POST …/refresh`
 * live in `V2ApiCredentialPlugin.kt` / `V2ApiPersistentPty.kt` /
 * `V2ApiPtyShell.kt` / [V2Api.worktrees] / `V2ApiCatalogExtras.kt` — nothing
 * here duplicates them. Request/response shapes are the live 2.0.11
 * `openapi.json` plus the route groups cited above; every write is
 * `UNVERIFIED` live (asserted on `MockWebServer` only — writes never hit the
 * real server). Blocking; call on an IO dispatcher.
 */

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * `DELETE /api/credential/{credentialID}` → **204**: removes a stored
 * integration credential (`credential.remove`, `credential.ts:35-46`; handler
 * `server/src/handlers/credential.ts:22-30`). No request body and no `location`
 * query (the credential group takes neither). Errors: 400 `InvalidRequestError`,
 * 401 — both [V2Api.ApiException] with the `_tag` in the message. Unknown ids
 * are unprobed (`UNVERIFIED` live).
 */
fun V2Api.credentialRemove(credentialID: String) {
    callUnit(builder("credential/$credentialID", false).delete().build())
}

/**
 * `PUT /api/experimental/persistent-pty/{ptyID}` body `PersistentPty.UpdateInput`
 * → `{"data":PersistentPty.Info}` (`persistentPty.update`,
 * `persistent-pty.ts:73-79`; schema `schema/src/persistent-pty.ts:33-38`).
 *
 * [cols]/[rows] are the `size` object, **required** by the schema and each
 * `> 0` (`PositiveInt`); [attachmentID] is the optional client attachment id.
 * No `location` query (openapi `parameters`: path id only). Errors carry `_tag`
 * `PtyNotFoundError` (404) / `InvalidRequestError` (400) /
 * `ServiceUnavailableError` (503). `UNVERIFIED` live (write probe refused).
 */
fun V2Api.persistentPtyUpdate(
    ptyID: String,
    cols: Int,
    rows: Int,
    attachmentID: String? = null,
): PersistentPtyInfo {
    val size = JSONObject().put("cols", cols).put("rows", rows)
    val body = JSONObject()
    if (attachmentID != null) body.put("attachmentID", attachmentID)
    body.put("size", size)
    return PersistentPtyInfo.fromJson(
        dataObject(
            callBody(
                builder("experimental/persistent-pty/$ptyID", false)
                    .put(body.toString().toRequestBody(JSON_MEDIA))
                    .build(),
            ),
        ),
    )
}

/**
 * `DELETE /api/experimental/persistent-pty/{ptyID}` → **204**
 * (`persistentPty.remove`, `persistent-pty.ts:88-93`): tear the terminal down
 * and drop its retained output. No request body, no `location` query. Errors:
 * 404 `PtyNotFoundError`, 400/503 as on [persistentPtyUpdate]. `UNVERIFIED`
 * live (write probe refused).
 */
fun V2Api.persistentPtyRemove(ptyID: String) {
    callUnit(builder("experimental/persistent-pty/$ptyID", false).delete().build())
}

/**
 * `POST /api/shell` body `Shell.CreateInput` → `{"location":…,"data":Shell.Info}`
 * ([ShellCommandInfo]) — spawn one non-interactive background command
 * (`shell.create`, `shell.ts:24-38`; schema `schema/src/shell.ts:59-64`).
 *
 * @param command the shell line; the only required key.
 * @param cwd working directory; omitted ⇒ the location's directory
 *   (`server/src/handlers/shell.ts:18`).
 * @param timeout kill delay in **milliseconds**, `0`/omitted = no timeout
 *   (`core/src/shell.ts:260,379-382` `Effect.sleep(Duration.millis(...))`).
 * @param metadata opaque caller tags echoed on the info row
 *   (`schema/src/shell.ts:33-35`); omitted ⇒ `{}`.
 * @param locationDirectory `location[directory]` override (deepObject query,
 *   same as [V2Api.shells]).
 *
 * Follow with [shellOutput] to page the capture and [shellRemove] to stop the
 * command. `UNVERIFIED` live (write probe refused).
 */
fun V2Api.shellCreate(
    command: String,
    cwd: String? = null,
    timeout: Int? = null,
    metadata: JSONObject? = null,
    locationDirectory: String? = null,
): ShellCommandInfo {
    val body = JSONObject().put("command", command)
    if (cwd != null) body.put("cwd", cwd)
    if (timeout != null) body.put("timeout", timeout)
    if (metadata != null) body.put("metadata", metadata)
    return ShellCommandInfo.fromJson(
        dataObject(
            callBody(
                builder("shell", true) { located(it, locationDirectory) }
                    .post(body.toString().toRequestBody(JSON_MEDIA))
                    .build(),
            ),
        ),
    )
}

/**
 * `POST /api/worktree` body `Worktree.CreateInput` → **bare** `Worktree.Info`
 * `{directory}` — **no** `data` envelope (`worktree.create`, `worktree.ts:36-48`;
 * schema `schema/src/worktree.ts:12-23`; generated client
 * `WorktreeCreateOutput = Worktree.Info`). Contrast the location-wrapped shell
 * routes.
 *
 * @param projectID required — the project whose canonical configuration drives
 *   the create strategy and setup script.
 * @param from source checkout to branch from; omitted ⇒ the project default.
 * @param branch branch name (non-empty when present).
 * @param directory parent directory for the new worktree; omitted ⇒ the
 *   server's data directory (`schema/src/worktree.ts:16-18`).
 * @param name worktree name; omitted ⇒ a generated slug
 *   (`core/src/worktree.ts:211`).
 *
 * Errors: 400 `WorktreeError` (`name`+`data.message`+`data.forceRequired`, not
 * `_tag` — same bytes as `refreshWorktrees`) / `InvalidRequestError`, 404
 * `ProjectNotFoundError`. No `location` query and no `projectID` query (the id
 * travels in the body). `UNVERIFIED` live (write probe refused).
 */
fun V2Api.worktreeCreate(
    projectID: String,
    from: String? = null,
    branch: String? = null,
    directory: String? = null,
    name: String? = null,
): WorktreeInfo {
    val body = JSONObject().put("projectID", projectID)
    if (from != null) body.put("from", from)
    if (branch != null) body.put("branch", branch)
    if (directory != null) body.put("directory", directory)
    if (name != null) body.put("name", name)
    return WorktreeInfo.fromJson(
        parseObject(callBody(builder("worktree", false).post(body.toString().toRequestBody(JSON_MEDIA)).build())),
    )
}

/**
 * `DELETE /api/worktree` with JSON body `Worktree.RemoveInput` → **204**
 * (`worktree.remove`, `worktree.ts:50-62`; schema `schema/src/worktree.ts:25-29`).
 *
 * The three keys travel in the **request body**, not the query (openapi:
 * `parameters: []`, `requestBody: Worktree.RemoveInput`; confirmed against
 * `worktree.ts:50-54` `payload:`) — unlike [V2Api.worktrees], which passes
 * `projectID` as a query parameter. All three keys are required; [force]
 * `false` fails on a dirty worktree with `WorktreeError.data.forceRequired`
 * (`core/src/worktree.ts:257-270`). Same error family as [worktreeCreate].
 * `UNVERIFIED` live (write probe refused).
 */
fun V2Api.worktreeRemove(projectID: String, directory: String, force: Boolean) {
    val body = JSONObject()
        .put("projectID", projectID)
        .put("directory", directory)
        .put("force", force)
    callUnit(builder("worktree", false).delete(body.toString().toRequestBody(JSON_MEDIA)).build())
}

/**
 * `DELETE /api/session/{sessionID}/form/{formID}` → **204**: cancel a pending
 * form (`session.form.cancel`, `session.ts:855-868` — "Cancel a pending form").
 *
 * No request body and no `location` query (openapi `parameters`: path ids only).
 * Errors: 404 `SessionNotFoundError`/`FormNotFoundError`, 409
 * `FormAlreadySettledError` once the form was answered or cancelled — all
 * [V2Api.ApiException] with the `_tag` in the message. This is the `V2Api`
 * seam for the cancel `SessionFormModel.formCancelRequest` hand-builds;
 * `UNVERIFIED` live (write probe refused).
 */
fun V2Api.formCancel(sessionID: String, formID: String) {
    callUnit(builder("session/$sessionID/form/$formID", false).delete().build())
}

/**
 * `Worktree.Info` — `{directory}` (`schema/src/worktree.ts:21-23`): the
 * canonical absolute path of the created worktree. Bare on the wire (no `data`
 * key), flattened here; [raw] keeps the response body for future fields.
 */
data class WorktreeInfo(
    val directory: String,
    val raw: JSONObject,
) {
    companion object {
        fun fromJson(obj: JSONObject): WorktreeInfo = WorktreeInfo(
            directory = obj.optString("directory", ""),
            raw = obj,
        )
    }
}
