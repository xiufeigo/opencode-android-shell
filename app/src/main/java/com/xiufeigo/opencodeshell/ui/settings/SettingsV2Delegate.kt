package com.xiufeigo.opencodeshell.ui.settings

import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.data.ProjectPaths
import com.xiufeigo.opencodeshell.v2.IntegrationDetail
import com.xiufeigo.opencodeshell.v2.ServerGeneration
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.configUpdate
import com.xiufeigo.opencodeshell.v2.credentialActivate
import com.xiufeigo.opencodeshell.v2.credentialRemove
import com.xiufeigo.opencodeshell.v2.credentialUpdate
import com.xiufeigo.opencodeshell.v2.generateText
import com.xiufeigo.opencodeshell.v2.integrationCommandCancel
import com.xiufeigo.opencodeshell.v2.integrationCommandStatus
import com.xiufeigo.opencodeshell.v2.integrationConnectCommand
import com.xiufeigo.opencodeshell.v2.integrationConnectKey
import com.xiufeigo.opencodeshell.v2.integrationDetail
import com.xiufeigo.opencodeshell.v2.integrationOauthCancel
import com.xiufeigo.opencodeshell.v2.integrationOauthComplete
import com.xiufeigo.opencodeshell.v2.integrationOauthConnect
import com.xiufeigo.opencodeshell.v2.integrationOauthStatus
import com.xiufeigo.opencodeshell.v2.integrationWellknownAdd
import com.xiufeigo.opencodeshell.v2.mcpAdd
import com.xiufeigo.opencodeshell.v2.mcpConnect
import com.xiufeigo.opencodeshell.v2.mcpDisconnect
import com.xiufeigo.opencodeshell.v2.mcpLocalConfig
import com.xiufeigo.opencodeshell.v2.mcpRemoteConfig
import com.xiufeigo.opencodeshell.v2.mcpRemove
import com.xiufeigo.opencodeshell.v2.mcpResourceCatalog
import com.xiufeigo.opencodeshell.v2.migrationV1Status
import com.xiufeigo.opencodeshell.v2.pluginCheck
import com.xiufeigo.opencodeshell.v2.pluginUpdate
import com.xiufeigo.opencodeshell.v2.refreshWorktrees
import com.xiufeigo.opencodeshell.v2.rpcCall
import com.xiufeigo.opencodeshell.v2.worktreeRemove
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * [SettingsV2Writes] over the protocol agent's [V2Api] — the delegate
 * `SettingsServerApi` consults before touching a v1 unprefixed route, supplied
 * by the host (`ui/App.kt` `showSettingsDialog`) **only while the active
 * connection speaks v2**.
 *
 * Reads are the real `/api` calls (page bodies as JSON the pure parsers
 * already accept; measured shapes in [SettingsV2Writes] and [V2Api]).
 *
 * The write half routes through the `v2` package extensions (credential
 * update/activate/remove, integration key/OAuth/command connect, MCP runtime
 * add/remove/connect/disconnect, plugin check/update, `config.update`,
 * wellknown import, v1 migration status) and always runs.
 * [disconnectProvider] maps the integration id to its stored credentials the
 * way upstream `settings/providers/providers.tsx` `disconnect` does;
 * [deleteWorktree] resolves the `projectID` [worktreeRemove] needs from the
 * directory against `GET /api/project` / `GET /api/worktree?projectID=`.
 */
class SettingsV2Delegate(
    private val api: V2Api,
) : SettingsV2Writes {

    // ------------------------------------------------------- page reads (P0)

    /** `GET /api/project` → its BARE array body, elements untouched. */
    override suspend fun projectsJson(): String =
        JSONArray(api.projects().map { it.raw }).toString()

    /**
     * Flattened `GET /api/worktree?projectID=` rows across every known
     * project. `projectID` is required (measured `400 InvalidRequestError`
     * without it), so [V2Api.projects] resolves the ids first; zero projects
     * answers `[]` instead of firing a doomed request.
     *
     * B-10: the rows are flattened **with their owner stamped on**
     * (`projectID` + `projectName`) — the route answers `[{directory,strategy?}]`
     * and nothing else, so a flat body is otherwise unattributable and the
     * Workspaces page could not offer its project filter. The plain
     * `worktreePageRows` parser ignores the two extra keys, so the existing
     * page and its test read the same rows as before.
     */
    override suspend fun worktreesJson(): String {
        val rows = JSONArray()
        for (project in api.projects()) {
            if (project.id.isBlank()) continue
            val name = worktreeDisplayName(project.canonical)
            api.worktrees(project.id).forEach { row ->
                rows.put(
                    org.json.JSONObject(row.toString())
                        .put("projectID", project.id)
                        .put("projectName", name),
                )
            }
        }
        return rows.toString()
    }

    /** `GET /api/mcp` → its `{location,data}` envelope unwrapped to the array. */
    override suspend fun mcpJson(): String = JSONArray(api.mcp()).toString()

    /**
     * `GET /api/skill` → array of `{id,name,description,path,content}`.
     * [V2Api.SkillInfo] has no raw object, so the rows are rebuilt from the
     * DTO fields — exactly the keys `skillPageRows` reads back.
     */
    override suspend fun skillsJson(): String = JSONArray(
        api.skills().map { skill ->
            val row = org.json.JSONObject()
                .put("id", skill.id)
                .put("name", skill.name)
                .put("description", skill.description)
            if (skill.location != null) row.put("path", skill.location)
            if (skill.content != null) row.put("content", skill.content)
            row
        },
    ).toString()

    // --------------------------------------- WAVE 2.3a GET halves (delegate)

    /** `GET /api/plugin` → array body; elements carry `id` (no `name`). */
    override suspend fun pluginsJson(): String = JSONArray(api.plugins()).toString()

    /** `GET /api/config` bare array body, elements untouched. */
    override suspend fun configJson(): String = JSONArray(api.configs().map { it.raw }).toString()

    /** `GET /api/config/shell` → distinct option `name`s. */
    override suspend fun shells(): List<String> = api.shellOptions()

    override suspend fun integrations(): List<ProviderIntegrationRow> =
        api.integrations().map { row ->
            // `connections[].type` (`credential`/`env`), never `methods[].type`:
            // canDisconnect/resolveSource read connection kinds, and a key
            // method with a stored grant must report `credential`.
            ProviderIntegrationRow(
                id = row.id,
                connectionTypes = integrationConnectionTypes(row.raw),
                hasIntegration = true,
            )
        }

    // ------------------------------------------- write half (API-ready routes)

    /**
     * Upstream `settings/providers/providers.tsx:102-111` `disconnect`:
     * `GET /api/integration/{id}` then `DELETE /api/credential/{id}` per
     * [removableCredentialIDs] entry. [providerID] is the integration id —
     * the same one [connectProviderKey] takes. No removable credential
     * answers `Failed("no-credentials")`, upstream's throw ("No removable
     * credentials found"): `env`-only integrations store nothing this route
     * can delete. Removals run sequentially and stop at the first failure.
     */
    override suspend fun disconnectProvider(providerID: String): ProviderWriteResult {
        var empty = false
        val result = write {
            val ids = removableCredentialIDs(api.integrationDetail(providerID).connections)
            if (ids.isEmpty()) {
                empty = true
            } else {
                ids.forEach { api.credentialRemove(it) }
            }
        }
        return if (empty) ProviderWriteResult.Failed("no-credentials") else result
    }

    /** `POST /api/integration/{id}/connect/key` (`integration.ts:62-81`). */
    override suspend fun connectProviderKey(providerID: String, key: String): ProviderWriteResult =
        connectIntegrationKey(providerID, key)

    override suspend fun connectIntegrationKey(
        integrationID: String,
        key: String,
        answer: Map<String, Any?>?,
        label: String?,
    ): ProviderWriteResult = write {
        api.integrationConnectKey(integrationID, key, answer, label)
    }

    /** `POST /api/experimental/mcp/{server}/{connect,disconnect}`. */
    override suspend fun setMcpEnabled(name: String, enabled: Boolean): ProviderWriteResult = write {
        if (enabled) api.mcpConnect(name) else api.mcpDisconnect(name)
    }

    /**
     * `PATCH /api/experimental/config {shell}` (`config.ts:35-45`). Blank
     * clears `config.shell` back to the discovered default (`null` on the wire).
     */
    override suspend fun setServerShell(shell: String): ProviderWriteResult = write {
        api.configUpdate(shell.trim().takeIf { it.isNotEmpty() })
    }

    /**
     * `DELETE /api/worktree` ([worktreeRemove]) with the `projectID` this seam
     * does not carry, resolved from [directory] by [projectIDForWorktree]:
     * every `GET /api/project` canonical first, then each project's
     * `GET /api/worktree?projectID=` inventory. No owner answers
     * `Failed("no-project")` — never a guessed id.
     */
    override suspend fun deleteWorktree(directory: String, force: Boolean): ProviderWriteResult {
        var unowned = false
        val result = write {
            val projectID = worktreeProjectID(directory)
            if (projectID == null) {
                unowned = true
            } else {
                api.worktreeRemove(projectID, directory, force)
                // v68: reconcile behind the delete (the same refresh the
                // new-session delete runs): a directory removed outside the
                // API leaves a stale row the next read would otherwise keep.
                runCatching { api.refreshWorktrees(projectID) }
            }
        }
        return if (unowned) ProviderWriteResult.Failed("no-project") else result
    }

    /** [worktreeRemove]'s `projectID` for [directory], or null when no project owns it. */
    private fun worktreeProjectID(directory: String): String? {
        val projects = api.projects().filter { it.id.isNotBlank() }
        projectIDForWorktree(directory, projects.map { it.id to it.canonical })?.let { return it }
        for (project in projects) {
            val rows = api.worktrees(project.id).map { project.id to it.optString("directory") }
            projectIDForWorktree(directory, rows)?.let { return it }
        }
        return null
    }

    override suspend fun integrationsJson(): String =
        JSONArray(api.integrations().map { it.raw }).toString()

    override suspend fun integrationDetailJson(integrationID: String): String =
        api.integrationDetail(integrationID).raw.toString()

    override suspend fun renameCredential(credentialID: String, label: String): ProviderWriteResult = write {
        api.credentialUpdate(credentialID, label)
    }

    override suspend fun activateCredential(credentialID: String): ProviderWriteResult = write {
        api.credentialActivate(credentialID)
    }

    override suspend fun oauthConnect(integrationID: String, methodID: String, label: String?): String? =
        api.integrationOauthConnect(integrationID, methodID, label = label).raw.toString()

    override suspend fun oauthStatusJson(integrationID: String, attemptID: String): String =
        api.integrationOauthStatus(integrationID, attemptID).raw.toString()

    override suspend fun oauthComplete(integrationID: String, attemptID: String, code: String?): ProviderWriteResult =
        write { api.integrationOauthComplete(integrationID, attemptID, code?.trim()?.takeIf { it.isNotEmpty() }) }

    override suspend fun oauthCancel(integrationID: String, attemptID: String): ProviderWriteResult = write {
        api.integrationOauthCancel(integrationID, attemptID)
    }

    override suspend fun commandConnect(integrationID: String, methodID: String, label: String?): String? =
        api.integrationConnectCommand(integrationID, methodID, label).raw.toString()

    override suspend fun commandStatusJson(integrationID: String, attemptID: String): String =
        api.integrationCommandStatus(integrationID, attemptID).raw.toString()

    override suspend fun commandCancel(integrationID: String, attemptID: String): ProviderWriteResult = write {
        api.integrationCommandCancel(integrationID, attemptID)
    }

    override suspend fun wellknownAdd(url: String): ProviderWriteResult = write {
        api.integrationWellknownAdd(url)
    }

    override suspend fun mcpAddLocal(name: String, command: List<String>): ProviderWriteResult = write {
        api.mcpAdd(name, mcpLocalConfig(command))
    }

    override suspend fun mcpAddRemote(name: String, url: String): ProviderWriteResult = write {
        api.mcpAdd(name, mcpRemoteConfig(url))
    }

    override suspend fun mcpRemoveServer(name: String): ProviderWriteResult = write {
        api.mcpRemove(name)
    }

    override suspend fun mcpResourcesJson(): String {
        val catalog = api.mcpResourceCatalog()
        return JSONObject()
            .put("resources", JSONArray(catalog.resources.map { it.raw }))
            .put("templates", JSONArray(catalog.templates.map { it.raw }))
            .toString()
    }

    override suspend fun pluginCheckJson(): String =
        JSONArray(api.pluginCheck().map { it.raw }).toString()

    override suspend fun pluginUpdate(targets: List<String>): ProviderWriteResult = write {
        api.pluginUpdate(targets)
    }

    override suspend fun migrationStatusJson(): String = api.migrationV1Status().raw.toString()

    // ------------------------------------------------- prepared, never called

    /**
     * `POST /api/experimental/generate` — entry kept ready for a title feature.
     * **Do not wire UI to this**: it runs a real stateless LLM generation.
     */
    suspend fun generateTitle(prompt: String, model: V2Api.ModelRef? = null): String =
        api.generateText(prompt, model)

    /** `POST /api/rpc/{rpcID}/{method}` — entry kept ready; no UI calls it yet. */
    suspend fun invokeRpc(rpcID: String, method: String, input: Any? = null): Any? =
        api.rpcCall(rpcID, method, input)

    /** [block] ran → [ProviderWriteResult.Done]; API/transport failure → `Failed`. */
    private inline fun write(block: () -> Unit): ProviderWriteResult {
        return try {
            block()
            ProviderWriteResult.Done
        } catch (e: V2Api.ApiException) {
            ProviderWriteResult.Failed(if (e.code == V2Api.JSON_BODY_CODE) "not-json" else "http-${e.code}")
        } catch (_: IOException) {
            ProviderWriteResult.Failed("unreachable")
        }
    }

    companion object {
        /**
         * Host-side factory: a delegate exists only while the **active
         * connection** is confirmed v2 and the settings' own server row (the
         * same `ServerConfigStore` `SettingsServerApi.serverProvider` reads)
         * is complete. `UNKNOWN` (probe still in flight or failed) and preview
         * hosts without a view model all get null.
         */
        fun forHost(generation: ServerGeneration, server: ServerConfigStore.Server?): SettingsV2Writes? {
            if (generation != ServerGeneration.V2) return null
            val target = server?.takeIf { it.isComplete } ?: return null
            val api = V2Api.forServer(target.url, target.username, target.password) ?: return null
            return SettingsV2Delegate(api)
        }
    }
}

/**
 * The credential ids a provider disconnect removes: upstream
 * `providers.tsx:104-108` filters `connections` on `type === "credential"`
 * and `credential.remove`s each id. `env` connections are environment
 * detections (nothing stored to delete) and blank ids drop out.
 */
internal fun removableCredentialIDs(connections: List<IntegrationDetail.Connection>): List<String> =
    connections.filter { it.type == "credential" }.mapNotNull { it.id?.takeIf(String::isNotBlank) }

/**
 * The owning `projectID` for a worktree [directory] over `(projectID, path)`
 * [candidates] — project `canonical`s and listed worktree directories —
 * compared with [ProjectPaths.matchKey]: separators normalised and Windows
 * paths case-folded, while POSIX keeps case. First match wins, so feed every
 * `canonical` before any inventory row: a project's own directory outranks a
 * claim inside another project's worktree list.
 */
internal fun projectIDForWorktree(
    directory: String,
    candidates: List<Pair<String, String>>,
): String? {
    val key = ProjectPaths.matchKey(directory) ?: return null
    return candidates.firstOrNull { ProjectPaths.matchKey(it.second) == key }?.first
}
