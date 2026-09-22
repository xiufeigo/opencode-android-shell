package com.xiufeigo.opencodeshell.ui.settings

import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.basicAuthorizationHeader
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

    override suspend fun projects(): SettingsPageLoad<List<ProjectPageRow>> =
        load("/project", ::projectPageRows)

    override suspend fun worktrees(): SettingsPageLoad<List<WorktreePageRow>> =
        load("/experimental/worktree", ::worktreePageRows)

    override suspend fun mcpServers(): SettingsPageLoad<List<McpPageRow>> =
        load("/mcp", ::mcpPageRows)

    override suspend fun skills(): SettingsPageLoad<List<SkillPageRow>> =
        load("/skill", ::skillPageRows)

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
 * without touching the panels.
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
    suspend fun pluginsJson(): String?
    suspend fun configJson(): String?
    suspend fun shells(): List<String>
    suspend fun integrations(): List<ProviderIntegrationRow>
    suspend fun disconnectProvider(providerID: String): ProviderWriteResult
    suspend fun connectProviderKey(providerID: String, key: String): ProviderWriteResult
    suspend fun setMcpEnabled(name: String, enabled: Boolean): ProviderWriteResult
    suspend fun setServerShell(shell: String): ProviderWriteResult
    suspend fun deleteWorktree(directory: String, force: Boolean): ProviderWriteResult
}
