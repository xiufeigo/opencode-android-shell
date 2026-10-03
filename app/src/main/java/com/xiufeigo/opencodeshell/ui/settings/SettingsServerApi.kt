package com.xiufeigo.opencodeshell.ui.settings

import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.basicAuthorizationHeader
import com.xiufeigo.opencodeshell.v2.V2Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * W5-S (D1) — the **read-only** server queries behind the Projects / Worktrees /
 * Extensions pages.
 *
 * Why a second HTTP entry point instead of `OpenCodeClient`: the four endpoints
 * this needs are `GET /project` (already on the client), `GET /experimental/
 * worktree`, `GET /mcp` and `GET /skill` — and `OpenCodeClient` is owned by
 * another wave this round, so adding three methods there was out of scope. This
 * class is deliberately tiny and read-only: no writes, no state, no retries.
 *
 * **Generation dispatch (P0, 2026-09-22):** those four unprefixed routes are
 * the SPA catch-all on a 2.x server (`200 text/html`), so each read now asks
 * the [SettingsV2Writes] delegate first — present ⇒ the `/api` body via
 * [SettingsServerApi] `loadV2`, absent (v1 server, or no host wiring) ⇒ the
 * original `load(path, …)` exactly as before (zero change on v1).
 *
 * WAVE 2.3a adds the write half the panels need: `DELETE /api/credential/{id}`
 * (provider disconnect), `POST /api/experimental/mcp/{server}/{connect,
 * disconnect}` (MCP toggle), `DELETE /api/worktree` (worktree delete),
 * `GET /api/plugin` (plugin list), `GET /api/config` + `GET /api/config/shell`
 * + `PATCH /api/experimental/config` (server shell row), and
 * `GET /api/integration` (provider source badges). All v2 calls run **only**
 * when the server speaks v2 ([V2Api] is the protocol agent's surface); on v1
 * they return the honest `Unsupported` outcome instead of failing the page.
 *
 * The wire rule is the shared one: same Basic-auth header
 * ([basicAuthorizationHeader]), same "no password → no header" behaviour, same
 * `/`-prefixed paths. Parsing lives in [SettingsModel] as pure functions
 * (`projectPageRows`, `worktreePageRows`, `mcpPageRows`, `skillPageRows`), so the
 * pages are unit-testable without a socket.
 *
 * Note for the Lead: if `OpenCodeClient` grows `worktrees()` / `mcp()` later,
 * this file can collapse into it without changing a single call site.
 */
interface SettingsServerData {
    /** `GET /project` (`settings/workspaces/projects.tsx`). */
    suspend fun projects(): SettingsPageLoad<List<ProjectPageRow>>

    /** `GET /experimental/worktree` (`settings/workspaces/workspaces.tsx`). */
    suspend fun worktrees(): SettingsPageLoad<List<WorktreePageRow>>

    /**
     * B-10: the same worktree read with the **owner** on each row
     * ([WorkspaceRow]) — what the Workspaces page's project filter and the two
     * bulk deletes need (`workspaces.tsx:87-98,304-323`).
     *
     * Defaulted to the plain [worktrees] projection so implementors that predate
     * the richer read (and their test fakes) keep compiling: the page then lists
     * every row and simply has no project filter.
     */
    suspend fun workspaceInventory(): SettingsPageLoad<List<WorkspaceRow>> = when (val load = worktrees()) {
        is SettingsPageLoad.Loaded -> SettingsPageLoad.Loaded(
            load.rows.map { WorkspaceRow(it.directory, it.name, it.branch) },
        )
        SettingsPageLoad.NoServer -> SettingsPageLoad.NoServer
        is SettingsPageLoad.Failed -> load
    }

    /**
     * B-10: the sessions linked to one worktree directory — `GET /session
     * ?directory=` (v1) / `GET /api/session?directory=` (v2), the read
     * `workspaces.tsx:106-113` performs per workspace. The page counts them per
     * row and drives "Delete worktrees without sessions" off them.
     *
     * Defaulted to empty (with the same `Loaded` shape the real read answers) so
     * a fake that does not implement it keeps compiling; the page treats an
     * empty answer as "no linked sessions", exactly like upstream's.
     */
    suspend fun workspaceSessions(directory: String): SettingsPageLoad<List<OpenCodeClient.Session>> =
        SettingsPageLoad.Loaded(emptyList())

    /** `GET /mcp` (`settings/providers/extensions.tsx:31-47`). */
    suspend fun mcpServers(): SettingsPageLoad<List<McpPageRow>>

    /** `GET /skill` (`extensions.tsx:56-60`). */
    suspend fun skills(): SettingsPageLoad<List<SkillPageRow>>

    /** WAVE 2.3a: `GET /api/plugin` (`extensions.tsx`, plugins subtab). */
    suspend fun plugins(): SettingsPageLoad<List<PluginPageRow>>

    /**
     * WAVE 2.3a: `GET /api/config` + `GET /api/config/shell`.
     * Null = the server does not speak v2 (the shell row hides itself).
     */
    suspend fun serverShell(): ServerShellInfo?

    /**
     * WAVE 2.3a: `GET /api/integration` rows for provider source badges
     * (`providers.tsx:71-80`). Empty = badges read "other".
     */
    suspend fun integrations(): List<ProviderIntegrationRow>

    /** WAVE 2.3a: provider disconnect (`DELETE /api/credential/{id}`). */
    suspend fun disconnectProvider(providerID: String): ProviderWriteResult

    /** WAVE 2.3a: API-key connect (`POST /api/integration/{id}/connect/key`). */
    suspend fun connectProviderKey(providerID: String, key: String): ProviderWriteResult

    /** WAVE 2.3a: MCP toggle (`POST /api/experimental/mcp/{server}/{connect,disconnect}`). */
    suspend fun setMcpEnabled(name: String, enabled: Boolean): ProviderWriteResult

    /**
     * WAVE 2.3a: server shell write (`PATCH /api/experimental/config {shell}`).
     */
    suspend fun setServerShell(shell: String): ProviderWriteResult

    /**
     * WAVE 2.3a: worktree delete (`DELETE /api/worktree`, `Worktree.RemoveInput`).
     */
    suspend fun deleteWorktree(directory: String, force: Boolean): ProviderWriteResult

    // ------------------------------------------------ connect surface (API-ready)
    //
    // Defaulted so pre-existing implementors (and their test fakes) keep
    // compiling; `SettingsServerApi` forwards each to the [SettingsV2Writes]
    // delegate and falls back to the default (Unsupported / empty) on v1.

    /** `GET /api/integration` rows as JSON — `credentialRows` / `integrationInfoRows`. */
    suspend fun integrationsJson(): String? = null

    /** `GET /api/integration/{id}` `data` as JSON — `integrationDetailRow`. */
    suspend fun integrationDetailJson(integrationID: String): String? = null

    /** `PATCH /api/credential/{id} {label}`. */
    suspend fun renameCredential(credentialID: String, label: String): ProviderWriteResult =
        ProviderWriteResult.Unsupported

    /** `POST /api/credential/{id}/activate`. */
    suspend fun activateCredential(credentialID: String): ProviderWriteResult =
        ProviderWriteResult.Unsupported

    /** `POST /api/integration/{id}/connect/key` with the method's `Form.Answer`. */
    suspend fun connectIntegrationKey(
        integrationID: String,
        key: String,
        answer: Map<String, Any?>? = null,
        label: String? = null,
    ): ProviderWriteResult = connectProviderKey(integrationID, key)

    /** `POST /api/integration/{id}/connect/oauth` → `Integration.Attempt` JSON. */
    suspend fun oauthConnect(
        integrationID: String,
        methodID: String,
        label: String? = null,
    ): String? = null

    /** `GET /api/integration/{id}/connect/oauth/{attempt}` → `AttemptStatus` JSON. */
    suspend fun oauthStatusJson(integrationID: String, attemptID: String): String? = null

    /** `POST /api/integration/{id}/connect/oauth/{attempt}/complete {code?}`. */
    suspend fun oauthComplete(
        integrationID: String,
        attemptID: String,
        code: String? = null,
    ): ProviderWriteResult = ProviderWriteResult.Unsupported

    /** `DELETE /api/integration/{id}/connect/oauth/{attempt}`. */
    suspend fun oauthCancel(integrationID: String, attemptID: String): ProviderWriteResult =
        ProviderWriteResult.Unsupported

    /** `POST /api/integration/{id}/connect/command` → `CommandAttempt` JSON. */
    suspend fun commandConnect(
        integrationID: String,
        methodID: String,
        label: String? = null,
    ): String? = null

    /** `GET /api/integration/{id}/connect/command/{attempt}` → status JSON. */
    suspend fun commandStatusJson(integrationID: String, attemptID: String): String? = null

    /** `DELETE /api/integration/{id}/connect/command/{attempt}`. */
    suspend fun commandCancel(integrationID: String, attemptID: String): ProviderWriteResult =
        ProviderWriteResult.Unsupported

    /** `POST /api/experimental/integration/wellknown {url}`. */
    suspend fun wellknownAdd(url: String): ProviderWriteResult = ProviderWriteResult.Unsupported

    /** `PUT /api/experimental/mcp/{name} {"config":{"type":"local",…}}`. */
    suspend fun mcpAddLocal(name: String, command: List<String>): ProviderWriteResult =
        ProviderWriteResult.Unsupported

    /** `PUT /api/experimental/mcp/{name} {"config":{"type":"remote",…}}`. */
    suspend fun mcpAddRemote(name: String, url: String): ProviderWriteResult =
        ProviderWriteResult.Unsupported

    /** `DELETE /api/experimental/mcp/{name}`. */
    suspend fun mcpRemoveServer(name: String): ProviderWriteResult = ProviderWriteResult.Unsupported

    /** `GET /api/mcp/resource` → `{resources,templates}` JSON. */
    suspend fun mcpResourcesJson(): String? = null

    /** `POST /api/plugin/check` → `[Plugin.Info…]` JSON (`source.outdated` refreshed). */
    suspend fun pluginCheckJson(): String? = null

    /** `POST /api/plugin/update {targets}`. */
    suspend fun pluginUpdate(targets: List<String>): ProviderWriteResult = ProviderWriteResult.Unsupported

    /** `GET /api/experimental/migration/v1` → bare `V1MigrationStatus` JSON. */
    suspend fun migrationStatusJson(): String? = null
}

/**
 * WAVE 2.3a: one `GET /api/integration` row projected for the providers tab —
 * the connection `type` values (`credential`/`env`/…) that drive
 * [ProviderConnectCatalog.resolveSource]/[ProviderConnectCatalog.canDisconnect].
 */
data class ProviderIntegrationRow(
    val id: String,
    val connectionTypes: List<String>,
    val hasIntegration: Boolean = true,
)

/**
 * WAVE 2.3a: outcome of a settings write. `Unsupported` = the server does not
 * speak the endpoint (v1 half) — the panel must say so, not spin; `Failed`
 * carries the short diagnostic token.
 */
sealed interface ProviderWriteResult {
    data object Done : ProviderWriteResult
    data object Unsupported : ProviderWriteResult
    data class Failed(val reason: String) : ProviderWriteResult
}

/**
 * Three-state result. A page must be able to tell "the server has nothing"
 * (empty list) from "we could not ask" — rendering `Failed` as an empty list is
 * exactly the "looks broken but says nothing" failure mode this project keeps
 * removing.
 */
sealed interface SettingsPageLoad<out T> {
    data class Loaded<T>(val rows: T) : SettingsPageLoad<T>

    /** No usable server is configured/active (first-run, or a store-less preview). */
    data object NoServer : SettingsPageLoad<Nothing>

    /** Transport or HTTP failure; [reason] is a short diagnostic token, not user copy. */
    data class Failed(val reason: String) : SettingsPageLoad<Nothing>
}

/**
 * [block] as a terminal [SettingsPageLoad] — success `Loaded`, failure `Failed`.
 * A load that throws must never leave the caller's `null` "loading" flag
 * untouched (the Providers tab's permanent-spinner bug).
 */
internal suspend fun <T> settingsPageLoad(block: suspend () -> T): SettingsPageLoad<T> = try {
    SettingsPageLoad.Loaded(block())
} catch (e: java.util.concurrent.CancellationException) {
    throw e
} catch (e: Exception) {
    SettingsPageLoad.Failed(e.message ?: "error")
}

/**
 * Blocking OkHttp GETs on `Dispatchers.IO`. One short-lived client: the settings
 * dialog is opened by hand, so connection reuse across panels is not worth
 * sharing the app-wide 30 s-read client ([com.xiufeigo.opencodeshell.OpenCodeClient]).
 */
class SettingsServerApi(
    private val serverProvider: () -> ServerConfigStore.Server?,
    private val http: OkHttpClient = defaultClient(),
    /**
     * WAVE 2.3a: the v2 half, supplied by the host when the server speaks v2.
     * Null = v1 server: every v2-shaped write returns
     * [ProviderWriteResult.Unsupported] and the v2 reads degrade (shell row
     * hidden, badges "other") instead of failing the page.
     */
    private val v2: SettingsV2Writes? = null,
) : SettingsServerData {

    override suspend fun projects(): SettingsPageLoad<List<ProjectPageRow>> {
        val delegate = v2 ?: return load("/project", ::projectPageRows)
        return loadV2(delegate::projectsJson, ::projectPageRows)
    }

    override suspend fun worktrees(): SettingsPageLoad<List<WorktreePageRow>> {
        val delegate = v2 ?: return load("/experimental/worktree", ::worktreePageRows)
        return loadV2(delegate::worktreesJson, ::worktreePageRows)
    }

    override suspend fun workspaceInventory(): SettingsPageLoad<List<WorkspaceRow>> {
        val delegate = v2 ?: return load("/experimental/worktree", ::workspaceRows)
        return loadV2(delegate::worktreesJson, ::workspaceRows)
    }

    /**
     * B-10: `GET /session?directory=` — the v1 route on a v1 server, the v2
     * `/api/session` on a 2.x one (the **same** `directory` filter
     * `OpenCodeClient.listSessions` sends; the location deepObject is not what
     * this read uses). Raw OkHttp on purpose: the route is a real one on both
     * generations, so it needs neither the v1 fallback dance nor the delegate.
     */
    override suspend fun workspaceSessions(
        directory: String,
    ): SettingsPageLoad<List<OpenCodeClient.Session>> {
        if (directory.isBlank()) return SettingsPageLoad.Loaded(emptyList())
        val target = serverProvider()?.takeIf { it.isComplete } ?: return SettingsPageLoad.NoServer
        val path = if (v2 != null) "/api/session" else "/session"
        val url = endpoint(target.url, path)?.newBuilder()
            ?.addQueryParameter("directory", directory)
            ?.build() ?: return SettingsPageLoad.Failed("bad-url")
        return withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).get()
            basicAuthorizationHeader(target.username, target.password)?.let { builder.header("Authorization", it) }
            try {
                http.newCall(builder.build()).execute().use { response ->
                    val body = response.body?.string()
                    if (!response.isSuccessful) {
                        SettingsPageLoad.Failed("http-${response.code}")
                    } else {
                        SettingsPageLoad.Loaded(sessionRows(body))
                    }
                }
            } catch (_: IOException) {
                SettingsPageLoad.Failed("unreachable")
            }
        }
    }

    override suspend fun mcpServers(): SettingsPageLoad<List<McpPageRow>> {
        val delegate = v2 ?: return load("/mcp", ::mcpPageRows)
        return loadV2(delegate::mcpJson, ::mcpPageRows)
    }

    override suspend fun skills(): SettingsPageLoad<List<SkillPageRow>> {
        val delegate = v2 ?: return load("/skill", ::skillPageRows)
        return loadV2(delegate::skillsJson, ::skillPageRows)
    }

    override suspend fun plugins(): SettingsPageLoad<List<PluginPageRow>> {
        val delegate = v2 ?: return SettingsPageLoad.Loaded(emptyList())
        return withContext(Dispatchers.IO) {
            try {
                SettingsPageLoad.Loaded(pluginPageRows(delegate.pluginsJson()))
            } catch (_: IOException) {
                SettingsPageLoad.Failed("unreachable")
            }
        }
    }

    override suspend fun serverShell(): ServerShellInfo? {
        val delegate = v2 ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                serverShellInfo(delegate.configJson(), delegate.shells())
            }.getOrNull()
        }
    }

    override suspend fun integrations(): List<ProviderIntegrationRow> {
        val delegate = v2 ?: return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching { delegate.integrations() }.getOrDefault(emptyList())
        }
    }

    override suspend fun disconnectProvider(providerID: String): ProviderWriteResult {
        val delegate = v2 ?: return ProviderWriteResult.Unsupported
        return withContext(Dispatchers.IO) {
            runCatching { delegate.disconnectProvider(providerID) }
                .getOrDefault(ProviderWriteResult.Failed("unreachable"))
        }
    }

    override suspend fun connectProviderKey(providerID: String, key: String): ProviderWriteResult {
        val delegate = v2 ?: return ProviderWriteResult.Unsupported
        return withContext(Dispatchers.IO) {
            runCatching { delegate.connectProviderKey(providerID, key) }
                .getOrDefault(ProviderWriteResult.Failed("unreachable"))
        }
    }

    override suspend fun setMcpEnabled(name: String, enabled: Boolean): ProviderWriteResult {
        val delegate = v2 ?: return ProviderWriteResult.Unsupported
        return withContext(Dispatchers.IO) {
            runCatching { delegate.setMcpEnabled(name, enabled) }
                .getOrDefault(ProviderWriteResult.Failed("unreachable"))
        }
    }

    override suspend fun setServerShell(shell: String): ProviderWriteResult {
        val delegate = v2 ?: return ProviderWriteResult.Unsupported
        return withContext(Dispatchers.IO) {
            runCatching { delegate.setServerShell(shell) }
                .getOrDefault(ProviderWriteResult.Failed("unreachable"))
        }
    }

    override suspend fun deleteWorktree(directory: String, force: Boolean): ProviderWriteResult {
        val delegate = v2 ?: return ProviderWriteResult.Unsupported
        return withContext(Dispatchers.IO) {
            runCatching { delegate.deleteWorktree(directory, force) }
                .getOrDefault(ProviderWriteResult.Failed("unreachable"))
        }
    }

    // ------------------------------------------------ connect surface (API-ready)
    //
    // Each method is the same dispatch as the WAVE 2.3a writes: v2 delegate
    // first, the documented default when the server is v1.

    override suspend fun integrationsJson(): String? = readV2 { it.integrationsJson() }

    override suspend fun integrationDetailJson(integrationID: String): String? =
        readV2 { it.integrationDetailJson(integrationID) }

    override suspend fun renameCredential(credentialID: String, label: String): ProviderWriteResult =
        writeV2 { it.renameCredential(credentialID, label) }

    override suspend fun activateCredential(credentialID: String): ProviderWriteResult =
        writeV2 { it.activateCredential(credentialID) }

    override suspend fun connectIntegrationKey(
        integrationID: String,
        key: String,
        answer: Map<String, Any?>?,
        label: String?,
    ): ProviderWriteResult = writeV2 { it.connectIntegrationKey(integrationID, key, answer, label) }

    override suspend fun oauthConnect(integrationID: String, methodID: String, label: String?): String? =
        readV2 { it.oauthConnect(integrationID, methodID, label) }

    override suspend fun oauthStatusJson(integrationID: String, attemptID: String): String? =
        readV2 { it.oauthStatusJson(integrationID, attemptID) }

    override suspend fun oauthComplete(integrationID: String, attemptID: String, code: String?): ProviderWriteResult =
        writeV2 { it.oauthComplete(integrationID, attemptID, code) }

    override suspend fun oauthCancel(integrationID: String, attemptID: String): ProviderWriteResult =
        writeV2 { it.oauthCancel(integrationID, attemptID) }

    override suspend fun commandConnect(integrationID: String, methodID: String, label: String?): String? =
        readV2 { it.commandConnect(integrationID, methodID, label) }

    override suspend fun commandStatusJson(integrationID: String, attemptID: String): String? =
        readV2 { it.commandStatusJson(integrationID, attemptID) }

    override suspend fun commandCancel(integrationID: String, attemptID: String): ProviderWriteResult =
        writeV2 { it.commandCancel(integrationID, attemptID) }

    override suspend fun wellknownAdd(url: String): ProviderWriteResult = writeV2 { it.wellknownAdd(url) }

    override suspend fun mcpAddLocal(name: String, command: List<String>): ProviderWriteResult =
        writeV2 { it.mcpAddLocal(name, command) }

    override suspend fun mcpAddRemote(name: String, url: String): ProviderWriteResult =
        writeV2 { it.mcpAddRemote(name, url) }

    override suspend fun mcpRemoveServer(name: String): ProviderWriteResult =
        writeV2 { it.mcpRemoveServer(name) }

    override suspend fun mcpResourcesJson(): String? = readV2 { it.mcpResourcesJson() }

    override suspend fun pluginCheckJson(): String? = readV2 { it.pluginCheckJson() }

    override suspend fun pluginUpdate(targets: List<String>): ProviderWriteResult =
        writeV2 { it.pluginUpdate(targets) }

    override suspend fun migrationStatusJson(): String? = readV2 { it.migrationStatusJson() }

    /** v2 read: null without a delegate, and null when the route fails. */
    private suspend fun readV2(block: suspend (SettingsV2Writes) -> String?): String? {
        val delegate = v2 ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { block(delegate) }.getOrNull()
        }
    }

    /** v2 write: [ProviderWriteResult.Unsupported] without a delegate. */
    private suspend fun writeV2(block: suspend (SettingsV2Writes) -> ProviderWriteResult): ProviderWriteResult {
        val delegate = v2 ?: return ProviderWriteResult.Unsupported
        return withContext(Dispatchers.IO) {
            runCatching { block(delegate) }.getOrDefault(ProviderWriteResult.Failed("unreachable"))
        }
    }

    private suspend fun <T> load(path: String, parse: (String?) -> T): SettingsPageLoad<T> {
        val target = serverProvider()?.takeIf { it.isComplete } ?: return SettingsPageLoad.NoServer
        val url = endpoint(target.url, path) ?: return SettingsPageLoad.Failed("bad-url")
        return withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).get()
            val authorization = basicAuthorizationHeader(target.username, target.password)
            if (authorization != null) builder.header("Authorization", authorization)
            try {
                http.newCall(builder.build()).execute().use { response ->
                    val body = response.body?.string()
                    if (!response.isSuccessful) {
                        SettingsPageLoad.Failed("http-${response.code}")
                    } else {
                        SettingsPageLoad.Loaded(parse(body))
                    }
                }
            } catch (_: IOException) {
                SettingsPageLoad.Failed("unreachable")
            }
        }
    }

    /**
     * The v2 half of [load]: [fetch] answers the `/api` body through the
     * delegate (the protocol agent's [V2Api]), [parse] is the same pure
     * function the v1 path feeds — so a page's rendering code is generation-
     * agnostic. The no-server check runs first (the pages distinguish
     * [SettingsPageLoad.NoServer] from [SettingsPageLoad.Failed]); a measured
     * v2 `_tag` error maps to `http-<code>` like the v1 status check, and the
     * SPA-catch-all backstop (`JSON_BODY_CODE`, -1) reads `not-json` instead
     * of the meaningless `http--1`.
     */
    private suspend fun <T> loadV2(fetch: suspend () -> String, parse: (String?) -> T): SettingsPageLoad<T> {
        serverProvider()?.takeIf { it.isComplete } ?: return SettingsPageLoad.NoServer
        return withContext(Dispatchers.IO) {
            try {
                SettingsPageLoad.Loaded(parse(fetch()))
            } catch (e: V2Api.ApiException) {
                if (e.code == V2Api.JSON_BODY_CODE) {
                    SettingsPageLoad.Failed("not-json")
                } else {
                    SettingsPageLoad.Failed("http-${e.code}")
                }
            } catch (_: IOException) {
                SettingsPageLoad.Failed("unreachable")
            }
        }
    }

    companion object {
        /**
         * `http(s)://host:port` + `/path`, or null for an address that cannot be
         * turned into a request. Mirrors `healthUrl` (`ui/servers/ServerHealth.kt:260`)
         * without depending on that package.
         */
        fun endpoint(baseUrl: String, path: String): HttpUrl? {
            val base = baseUrl.trim().trimEnd('/')
            if (!base.startsWith("http://") && !base.startsWith("https://")) return null
            return (base + path).toHttpUrlOrNull()
        }

        /** Read-only page budget: a settings tab must never hang on a dead host. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * WAVE 2.3a: the v2-shaped writes behind `SettingsServerData`, kept behind a
 * seam so the protocol agent can supply the real [com.xiufeigo.opencodeshell.v2.V2Api]
 * without touching the panels. Since the P0 generation fix it also carries the
 * four page **reads** (the v1 unprefixed routes are SPA HTML on 2.x) — the
 * host supplies one delegate ([SettingsV2Delegate]) for both halves.
 *
 * Endpoint shapes (openapi + `providers.tsx:100-121`,
 * `providers/connect/controller.ts`, `providers/connect/mcp.ts`,
 * `settings/general/controllers.ts:26-69`):
 *
 * - disconnect: `GET /api/integration/{id}` → `DELETE /api/credential/{id}`
 *   per `credential` connection;
 * - API-key connect: `POST /api/integration/{id}/connect/key {key}`;
 * - MCP toggle: `POST /api/experimental/mcp/{server}/connect|disconnect`;
 * - server shell: `GET /api/config` + `GET /api/config/shell`,
 *   `PATCH /api/experimental/config {shell}`;
 * - worktree delete: `DELETE /api/worktree` (`Worktree.RemoveInput`);
 * - plugins: `GET /api/plugin`.
 */
interface SettingsV2Writes {
    // -------------------------------------------------- v2 page reads (P0)
    //
    // The four W5-S pages used to fall through to the v1 unprefixed routes,
    // which on a 2.x server are the SPA catch-all (`200 text/html` — measured
    // 2026-09-22 on 2.0.11), so every parse failed. Bodies are the JSON the
    // pure parsers (`projectPageRows`/`worktreePageRows`/`mcpPageRows`/
    // `skillPageRows`) already accept; implementations throw on failure and
    // `SettingsServerApi.loadV2` maps that to the same `Failed` tokens the v1
    // path produces.

    /** `GET /api/project` → the BARE array body (v2 keys: `{id,canonical,…}` — no `worktree`). */
    suspend fun projectsJson(): String

    /**
     * `GET /api/worktree?projectID=` rows for every known project, flattened
     * into one array body (elements are `{directory}`-shaped, which
     * `worktreePageRows` reads). `projectID` is **required** on v2 — measured
     * `400 InvalidRequestError "Missing key at [\"projectID\"]"` without it —
     * so the implementation resolves ids from `GET /api/project` first; zero
     * projects answers `"[]"`, never a bare 400.
     */
    suspend fun worktreesJson(): String

    /** `GET /api/mcp` → its `data` array body (`[{name,status:{status}}]`). */
    suspend fun mcpJson(): String

    /** `GET /api/skill` → its `data` array body (`[{id,name,description,path,…}]`). */
    suspend fun skillsJson(): String

    suspend fun pluginsJson(): String?
    suspend fun configJson(): String?
    suspend fun shells(): List<String>
    suspend fun integrations(): List<ProviderIntegrationRow>
    suspend fun disconnectProvider(providerID: String): ProviderWriteResult
    suspend fun connectProviderKey(providerID: String, key: String): ProviderWriteResult
    suspend fun setMcpEnabled(name: String, enabled: Boolean): ProviderWriteResult
    suspend fun setServerShell(shell: String): ProviderWriteResult
    suspend fun deleteWorktree(directory: String, force: Boolean): ProviderWriteResult

    // ------------------------------------------------ connect surface (API-ready)
    //
    // Defaulted so pre-existing implementors (and their test fakes) keep
    // compiling; bodies are the `data` payloads the pure parsers in
    // [SettingsConnectModel] already accept.

    /** `GET /api/integration` rows as JSON — `credentialRows` / `integrationInfoRows`. */
    suspend fun integrationsJson(): String? = null

    /** `GET /api/integration/{id}` `data` as JSON — `integrationDetailRow`. */
    suspend fun integrationDetailJson(integrationID: String): String? = null

    /** `PATCH /api/credential/{id} {label}`. */
    suspend fun renameCredential(credentialID: String, label: String): ProviderWriteResult =
        ProviderWriteResult.Unsupported

    /** `POST /api/credential/{id}/activate`. */
    suspend fun activateCredential(credentialID: String): ProviderWriteResult =
        ProviderWriteResult.Unsupported

    /** `POST /api/integration/{id}/connect/key` with the method's `Form.Answer`. */
    suspend fun connectIntegrationKey(
        integrationID: String,
        key: String,
        answer: Map<String, Any?>? = null,
        label: String? = null,
    ): ProviderWriteResult = connectProviderKey(integrationID, key)

    /** `POST /api/integration/{id}/connect/oauth` → `Integration.Attempt` JSON. */
    suspend fun oauthConnect(integrationID: String, methodID: String, label: String? = null): String? = null

    /** `GET /api/integration/{id}/connect/oauth/{attempt}` → `AttemptStatus` JSON. */
    suspend fun oauthStatusJson(integrationID: String, attemptID: String): String? = null

    /** `POST /api/integration/{id}/connect/oauth/{attempt}/complete {code?}`. */
    suspend fun oauthComplete(integrationID: String, attemptID: String, code: String? = null): ProviderWriteResult =
        ProviderWriteResult.Unsupported

    /** `DELETE /api/integration/{id}/connect/oauth/{attempt}`. */
    suspend fun oauthCancel(integrationID: String, attemptID: String): ProviderWriteResult =
        ProviderWriteResult.Unsupported

    /** `POST /api/integration/{id}/connect/command` → `CommandAttempt` JSON. */
    suspend fun commandConnect(integrationID: String, methodID: String, label: String? = null): String? = null

    /** `GET /api/integration/{id}/connect/command/{attempt}` → status JSON. */
    suspend fun commandStatusJson(integrationID: String, attemptID: String): String? = null

    /** `DELETE /api/integration/{id}/connect/command/{attempt}`. */
    suspend fun commandCancel(integrationID: String, attemptID: String): ProviderWriteResult =
        ProviderWriteResult.Unsupported

    /** `POST /api/experimental/integration/wellknown {url}`. */
    suspend fun wellknownAdd(url: String): ProviderWriteResult = ProviderWriteResult.Unsupported

    /** `PUT /api/experimental/mcp/{name} {"config":{"type":"local",…}}`. */
    suspend fun mcpAddLocal(name: String, command: List<String>): ProviderWriteResult =
        ProviderWriteResult.Unsupported

    /** `PUT /api/experimental/mcp/{name} {"config":{"type":"remote",…}}`. */
    suspend fun mcpAddRemote(name: String, url: String): ProviderWriteResult =
        ProviderWriteResult.Unsupported

    /** `DELETE /api/experimental/mcp/{name}`. */
    suspend fun mcpRemoveServer(name: String): ProviderWriteResult = ProviderWriteResult.Unsupported

    /** `GET /api/mcp/resource` → `{resources,templates}` JSON. */
    suspend fun mcpResourcesJson(): String? = null

    /** `POST /api/plugin/check` → `[Plugin.Info…]` JSON (`source.outdated` refreshed). */
    suspend fun pluginCheckJson(): String? = null

    /** `POST /api/plugin/update {targets}`. */
    suspend fun pluginUpdate(targets: List<String>): ProviderWriteResult = ProviderWriteResult.Unsupported

    /** `GET /api/experimental/migration/v1` → bare `V1MigrationStatus` JSON. */
    suspend fun migrationStatusJson(): String? = null
}
