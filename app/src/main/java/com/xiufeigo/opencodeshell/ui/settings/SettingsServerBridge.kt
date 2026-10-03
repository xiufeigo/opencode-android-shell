package com.xiufeigo.opencodeshell.ui.settings

import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.ui.servers.HealthTarget
import com.xiufeigo.opencodeshell.ui.servers.ServerHealthSource
import kotlinx.coroutines.flow.StateFlow

/**
 * [ServerBridge] over the existing [ServerConfigStore].
 *
 * Reads are state-driven from the store (`listServers()` / `activeUrl()` — the
 * only additions to that class, which stays otherwise untouched), and writes go
 * back through `replaceAll(...)` so the row list re-reads the persisted value on
 * the next recomposition.
 *
 * The health column is reported as [ServerHealth.UNKNOWN]: this class never
 * performs IO. Since W5-D3 the real tri-state comes from
 * `ServersPanel`'s probe over [healthTargets] — HTTP stays out of the bridge so
 * the settings *model* (ordering, `serverMeta`) remains JVM-testable, and the
 * credentials never leave the store's own list.
 */
class SettingsServerBridge(
    private val store: ServerConfigStore?,
    private val onChanged: () -> Unit = {},
    /**
     * WAVE 2.3a: the v2 writes, supplied by the host when the server speaks v2
     * (the protocol agent's `V2Api` adapter). Null = v1: writes report
     * `Unsupported` and the panels degrade honestly.
     */
    v2: SettingsV2Writes? = null,
) : ServerBridge, ServerHealthSource, SettingsServerData, SshEntryStore {

    /**
     * W5-S (D1): the read-only pages (Projects / Worktrees / Extensions) ask the
     * bridge for its **optional** data capability instead of `SettingsPanelExtras`
     * growing a constructor argument — the dialog is built in `ui/App.kt`, which
     * this wave must not touch, so a new `SettingsPanelExtras` field would have
     * been null at every real call site (the exact bug that left Providers/Models
     * empty before W4-D).
     *
     * The provider reads `store.server` lazily, so editing the active server in
     * the Servers tab is picked up by the next page fetch without rebuilding the
     * bridge (or the HTTP client).
     */
    private val api = SettingsServerApi(serverProvider = { store?.server }, v2 = v2)

    override suspend fun projects() = api.projects()

    override suspend fun worktrees() = api.worktrees()

    override suspend fun workspaceInventory() = api.workspaceInventory()

    override suspend fun workspaceSessions(directory: String) = api.workspaceSessions(directory)

    override suspend fun mcpServers() = api.mcpServers()

    override suspend fun skills() = api.skills()

    override suspend fun plugins() = api.plugins()

    override suspend fun serverShell() = api.serverShell()

    override suspend fun integrations() = api.integrations()

    override suspend fun disconnectProvider(providerID: String) = api.disconnectProvider(providerID)

    override suspend fun connectProviderKey(providerID: String, key: String) = api.connectProviderKey(providerID, key)

    override suspend fun setMcpEnabled(name: String, enabled: Boolean) = api.setMcpEnabled(name, enabled)

    override suspend fun setServerShell(shell: String) = api.setServerShell(shell)

    override suspend fun deleteWorktree(directory: String, force: Boolean) = api.deleteWorktree(directory, force)

    override suspend fun integrationsJson() = api.integrationsJson()

    override suspend fun integrationDetailJson(integrationID: String) =
        api.integrationDetailJson(integrationID)

    override suspend fun renameCredential(credentialID: String, label: String) = api.renameCredential(credentialID, label)

    override suspend fun activateCredential(credentialID: String) = api.activateCredential(credentialID)

    override suspend fun connectIntegrationKey(
        integrationID: String,
        key: String,
        answer: Map<String, Any?>?,
        label: String?,
    ) = api.connectIntegrationKey(integrationID, key, answer, label)

    override suspend fun oauthConnect(integrationID: String, methodID: String, label: String?) =
        api.oauthConnect(integrationID, methodID, label)

    override suspend fun oauthStatusJson(integrationID: String, attemptID: String) =
        api.oauthStatusJson(integrationID, attemptID)

    override suspend fun oauthComplete(integrationID: String, attemptID: String, code: String?) =
        api.oauthComplete(integrationID, attemptID, code)

    override suspend fun oauthCancel(integrationID: String, attemptID: String) = api.oauthCancel(integrationID, attemptID)

    override suspend fun commandConnect(integrationID: String, methodID: String, label: String?) =
        api.commandConnect(integrationID, methodID, label)

    override suspend fun commandStatusJson(integrationID: String, attemptID: String) =
        api.commandStatusJson(integrationID, attemptID)

    override suspend fun commandCancel(integrationID: String, attemptID: String) =
        api.commandCancel(integrationID, attemptID)

    override suspend fun wellknownAdd(url: String) = api.wellknownAdd(url)

    override suspend fun mcpAddLocal(name: String, command: List<String>) = api.mcpAddLocal(name, command)

    override suspend fun mcpAddRemote(name: String, url: String) = api.mcpAddRemote(name, url)

    override suspend fun mcpRemoveServer(name: String) = api.mcpRemoveServer(name)

    override suspend fun mcpResourcesJson() = api.mcpResourcesJson()

    override suspend fun pluginCheckJson() = api.pluginCheckJson()

    override suspend fun pluginUpdate(targets: List<String>) = api.pluginUpdate(targets)

    override suspend fun migrationStatusJson() = api.migrationStatusJson()

    override val revision: StateFlow<Int>
        get() = store?.revision ?: kotlinx.coroutines.flow.MutableStateFlow(0)

    override val servers: List<ServerListItem>
        get() = store?.listServers().orEmpty().map { server ->
            ServerListItem(
                url = server.url,
                name = server.url.removePrefix("http://").removePrefix("https://"),
                username = server.username,
                health = if (server.isComplete) ServerHealth.UNKNOWN else ServerHealth.UNHEALTHY,
                isDefault = server.url == store?.activeUrl(),
            )
        }

    override val activeUrl: String?
        get() = store?.activeUrl()

    /**
     * D3: the probe targets for every **usable** stored server, credential
     * included. `listServers()` only returns complete (`http://`-prefixed)
     * entries, so the probe never sees an address it would have to reject.
     */
    override fun healthTargets(): List<HealthTarget> =
        store?.listServers().orEmpty()
            .filter { it.isComplete }
            .map { HealthTarget(url = it.url, username = it.username, password = it.password) }

    override fun upsert(server: ServerListItem, password: String?) {
        val current = store ?: return
        val list = current.listServers().toMutableList()
        val existing = list.indexOfFirst { it.url == server.url }
        val next = ServerConfigStore.Server(
            url = server.url,
            username = server.username,
            password = password ?: list.getOrNull(existing)?.password,
        )
        if (existing >= 0) {
            list[existing] = next
        } else {
            list.add(next)
        }
        current.replaceAll(list, activeUrl ?: server.url)
        onChanged()
    }

    override fun replace(originalUrl: String?, server: ServerListItem, password: String?) {
        val current = store ?: return
        val list = current.listServers().toMutableList()
        val idx = originalUrl?.let { orig -> list.indexOfFirst { it.url == orig } } ?: -1
        val next = ServerConfigStore.Server(
            url = server.url,
            username = server.username,
            password = password ?: list.getOrNull(idx)?.password,
        )
        if (idx >= 0) {
            list[idx] = next
        } else {
            val existing = list.indexOfFirst { it.url == server.url }
            if (existing >= 0) list[existing] = next else list.add(next)
        }
        val nextActive = when {
            // Editing the default row keeps it default across a URL rename.
            originalUrl != null && activeUrl == originalUrl -> server.url
            activeUrl != null -> activeUrl
            else -> server.url
        }
        current.replaceAll(list, nextActive)
        onChanged()
    }

    override fun delete(url: String) {
        val current = store ?: return
        val list = current.listServers().filterNot { it.url == url }
        val nextActive = activeUrl?.takeIf { it != url } ?: list.firstOrNull()?.url
        current.replaceAll(list, nextActive)
        onChanged()
    }

    override fun setDefault(url: String) {
        val current = store ?: return
        current.replaceAll(current.listServers(), url)
        onChanged()
    }

    // --------------------------------------------------- SshEntryStore (v92)
    //
    // The SSH half of the Servers list is a **persisted** list: the durable
    // recipes live in the store's `ssh_json`, the panel's `SshTunnelHost` is
    // composition-scoped. These delegates are the panel's seam to the durable
    // half — seeded on composition, written on submit, dropped on the
    // confirmed delete. A null store (no config yet) degrades to empty/false.

    override fun sshEntries(): List<ServerConfigStore.SshEntry> = store?.sshEntries().orEmpty()

    override fun sshEntry(id: String): ServerConfigStore.SshEntry? = store?.sshEntry(id)

    override fun putSshEntry(entry: ServerConfigStore.SshEntry): Boolean =
        store?.putSshEntry(entry) ?: false

    override fun removeSshEntry(id: String): Boolean = store?.removeSshEntry(id) ?: false
}

/**
 * v92 — the panel-side seam to the persisted SSH redial recipes
 * (`ServerConfigStore.sshEntries()`, `ssh_json`). A capability interface in
 * the [ServerHealthSource] pattern: `ServersPanel` asks for it with
 * `bridge as? SshEntryStore`, so bridges without a store (tests, the null
 * bridge) simply do not offer it.
 */
interface SshEntryStore {
    fun sshEntries(): List<ServerConfigStore.SshEntry>
    fun sshEntry(id: String): ServerConfigStore.SshEntry?
    fun putSshEntry(entry: ServerConfigStore.SshEntry): Boolean
    fun removeSshEntry(id: String): Boolean
}
