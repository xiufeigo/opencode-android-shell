package com.xiufeigo.opencodeshell.ui.palette

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.xiufeigo.opencodeshell.R

/**
 * Palette rows whose actions are **self-sufficient**: unlike the route
 * registry (`appCommands` / `sessionCommands`, whose side effects `ui/App.kt`
 * owns) these panels only need a `DialogController` plus the shared server
 * prefs — the `SummaryServiceSource` pattern. [CommandPalette] therefore
 * injects and intercepts them itself, so no host wiring is required to reach
 * `GET /api/agent/{agentID}`, `GET|DELETE /api/debug/location` or
 * `POST /api/rpc/{rpcID}/{method}`.
 *
 * They append to the command list **after** the registered rows (never into
 * `COMMON_COMMAND_IDS`), so the preferred rows and every `PaletteModel` pure
 * test stay byte-identical; the rows surface through the query search
 * ("agent" / "debug" / "rpc") and at the end of the blank-query browse list
 * (`browseEntries`).
 */
object BuiltinCommands {

    /** `GET /api/agent/{agentID}` detail card ([AgentDetailsDialog]). */
    const val AGENT_DETAILS: String = "agent.details"

    /** `GET|DELETE /api/debug/location` panel ([DebugLocationsDialog]). */
    const val DEBUG_LOCATIONS: String = "debug.locations"

    /** `POST /api/rpc/{rpcID}/{method}` console ([PluginRpcDialog]). */
    const val PLUGIN_RPC: String = "plugin.rpc"

    /** `"command:" + id`, the same identity [PaletteCommand.entryId] uses. */
    fun entryId(commandId: String): String = "command:$commandId"

    /** The ids that [CommandPalette] intercepts instead of forwarding to the host. */
    val IDS: List<String> = listOf(AGENT_DETAILS, DEBUG_LOCATIONS, PLUGIN_RPC)

    fun isBuiltin(entryId: String): Boolean = entryId in IDS.map(::entryId)

    /**
     * Append [builtins] to the host's command rows — no dedupe against the
     * registry beyond identity (a host that registers the same id wins).
     */
    fun withBuiltinCommands(sources: PaletteSources, builtins: List<PaletteEntry>): PaletteSources {
        val registered = sources.commands.map { it.id }.toSet()
        return sources.copy(commands = sources.commands + builtins.filterNot { it.id in registered })
    }
}

/** The [BuiltinCommands] rows, with titles resolved from `oc_palette_*`. */
@Composable
fun builtinCommandEntries(): List<PaletteEntry> {
    val english = englishStrings()
    return listOf(
        PaletteEntry.command(
            id = BuiltinCommands.AGENT_DETAILS,
            title = stringResource(R.string.oc_palette_agent_details),
            searchTerms = listOf(english.getString(R.string.oc_palette_agent_details)),
        ),
        PaletteEntry.command(
            id = BuiltinCommands.DEBUG_LOCATIONS,
            title = stringResource(R.string.oc_palette_debug_locations),
            searchTerms = listOf(english.getString(R.string.oc_palette_debug_locations)),
        ),
        PaletteEntry.command(
            id = BuiltinCommands.PLUGIN_RPC,
            title = stringResource(R.string.oc_palette_plugin_rpc),
            searchTerms = listOf(english.getString(R.string.oc_palette_plugin_rpc)),
        ),
    )
}
