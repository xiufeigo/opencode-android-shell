package com.xiufeigo.opencodeshell.ui.settings

import com.xiufeigo.opencodeshell.ServerConfigStore

/**
 * [ServerBridge] over the existing [ServerConfigStore].
 *
 * Reads are state-driven from the store (`listServers()` / `activeUrl()` — the
 * only additions to that class, which stays otherwise untouched), and writes go
 * back through `replaceAll(...)` so the row list re-reads the persisted value on
 * the next recomposition.
 *
 * The health column is reported as [ServerHealth.UNKNOWN]: M4 does not run
 * `checkServerHealth` (that would be a network call on a settings open). The row
 * still shows the indicator dot and the ordering is stable.
 */
class SettingsServerBridge(
    private val store: ServerConfigStore?,
    private val onChanged: () -> Unit = {},
) : ServerBridge {

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
}
