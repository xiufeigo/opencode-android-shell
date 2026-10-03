package com.xiufeigo.opencodeshell.ui.session.summary

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.ui.settings.McpPageRow
import com.xiufeigo.opencodeshell.ui.settings.PluginPageRow
import com.xiufeigo.opencodeshell.ui.settings.mcpPageRows
import com.xiufeigo.opencodeshell.ui.settings.pluginPageRows
import com.xiufeigo.opencodeshell.v2.V2Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * The server card's four service catalogs (`summary/server-panel.tsx`
 * `ServiceCatalog`), read once per panel mount through the v2 routes the
 * protocol agent already exposes: `GET /api/mcp`, `/api/plugin`, `/api/skill`
 * and `/api/config` ([V2Api.mcp] / [V2Api.plugins] / [V2Api.skills] /
 * [V2Api.configs]).
 *
 * Every field is nullable for exactly one reason: `null` means "the host could
 * not supply the catalog" and the panel shows upstream's empty copy instead of
 * inventing rows — the honesty rule this shell keeps. An empty **list** is the
 * server saying "nothing configured".
 */
data class SummaryCatalogs(
    val mcp: List<SessionSummaryModel.ServiceItem>? = null,
    val plugins: List<String>? = null,
    val skills: List<String>? = null,
    /** Bare `GET /api/config` array body — [SessionSummaryModel.configuredLsps] input. */
    val configJson: String? = null,
) {
    companion object {
        /** The "could not ask" state: every submenu falls back to its empty copy. */
        val Unavailable: SummaryCatalogs = SummaryCatalogs()
    }
}

/**
 * Pure projections of the four catalogs onto the summary model — kept here (not
 * in the composable) so the mapping is JVM-testable without a socket.
 */
object SummaryServiceSource {

    /**
     * MCP rows (`server-panel.tsx:147-152,180-187`) sorted by name like
     * upstream's `toSorted(name.localeCompare)`; [SessionSummaryModel.ServiceItem.status]
     * carries the status **token** (`failed` / `connecting` / `auth`) the panel
     * resolves to `session.summary.failed|connecting|needsAuth`, while
     * [SessionSummaryModel.ServiceItem.rawStatus] keeps the verbatim state the
     * row's `Switch` positions itself from (`disabled` vs `connected`) and
     * [SessionSummaryModel.ServiceItem.error] the failure message.
     */
    fun mcpItems(rows: List<McpPageRow>): List<SessionSummaryModel.ServiceItem> = SessionSummaryModel.serviceItems(
        rows.map {
            SessionSummaryModel.ServiceItem(
                name = it.name,
                status = statusToken(it.status),
                rawStatus = it.status,
                error = it.error,
            )
        },
    )

    /** `GET /api/plugin` ids → the plain name list upstream's `ServiceCatalog` rows show. */
    fun pluginNames(rows: List<PluginPageRow>): List<String> = rows.map { it.name }

    /** `server-panel.tsx:180-187` status labels, as the tokens the panel maps. */
    fun statusToken(status: String?): String? = when (status?.lowercase()) {
        "failed" -> "failed"
        "pending", "connecting" -> "connecting"
        "needs-auth", "needs_auth", "unauthorized", "auth", "sign-in" -> "auth"
        else -> null
    }

    /** The four reads against one [V2Api]; any failure leaves the whole set null. */
    fun load(api: V2Api?, locationDirectory: String?): SummaryCatalogs {
        if (api == null) return SummaryCatalogs.Unavailable
        return runCatching {
            SummaryCatalogs(
                mcp = mcpItems(mcpPageRows(JSONArray(api.mcp(locationDirectory)).toString())),
                plugins = pluginNames(pluginPageRows(JSONArray(api.plugins(locationDirectory)).toString())),
                skills = api.skills(locationDirectory).map { it.name },
                configJson = JSONArray(api.configs(locationDirectory).map { it.raw }).toString(),
            )
        }.getOrElse { SummaryCatalogs.Unavailable }
    }
}

/**
 * Loads [SummaryCatalogs] for the summary server card. The panel takes explicit
 * catalog parameters first (host-supplied wins); this hook is the read-only
 * fallback so the four submenus have real data without waiting on a ViewModel —
 * `ChatViewModel` exposes nothing for these routes yet (see the wiring notes on
 * [SessionSummaryPanel]).
 *
 * [revision] is the re-read trigger: the panel passes the MCP toggle host's
 * revision so a connect/disconnect re-reads the page, the same
 * invalidate-then-sync pair upstream's `useMcpToggle` ends with
 * (`providers/connect/mcp.ts:45-48`).
 */
@Composable
fun rememberSummaryCatalogs(directory: String?, revision: Int = 0): SummaryCatalogs {
    val context = LocalContext.current
    // Read-only handle on the shared server prefs (same fallback as
    // `ui/terminal/TerminalPanel.kt`): the summary panel is not handed the
    // host's store, and `ChatViewModel` keeps its `V2Api` private.
    val serverStore = remember(context) { ServerConfigStore(context).also { it.restore() } }
    var catalogs by remember(directory) { mutableStateOf(SummaryCatalogs.Unavailable) }
    LaunchedEffect(serverStore, directory, revision) {
        catalogs = withContext(Dispatchers.IO) {
            val server = serverStore.server ?: return@withContext SummaryCatalogs.Unavailable
            val api = V2Api.forServer(server.url, server.username, server.password)
            SummaryServiceSource.load(api, directory)
        }
    }
    return catalogs
}
