package com.xiufeigo.opencodeshell.ui.terminal.share

import org.json.JSONArray
import org.json.JSONObject

/**
 * WAVE 2.3a (A4 #21): terminal tabs persisted per workspace directory
 * (`session/terminal/context.tsx:14-28,177-181,405-437`).
 *
 * Upstream keeps one `{active, all[]}` state per workspace
 * (`Persist.serverWorkspace(scope, dir, "terminal")`), trims the in-memory
 * cache to 20 workspaces on switch (`MAX_TERMINAL_SESSIONS` doubles as the
 * workspace-cache cap in spirit), and drops the persisted entry on
 * `pty.exited` cleanup (`terminal.tsx:25-28,154-171`, 2k scrollback persist).
 *
 * The native panel cannot persist the buffer/scrollY/cursor (the VT kernel is
 * process-local), so this store persists the **tab chrome**: `{id, title,
 * titleNumber}` per workspace plus the active id. On restore the panel
 * re-creates live PTYs for the remembered ids; ids the server no longer knows
 * (`GET /pty/:id` 404 / `pty.exited`) are dropped by the caller.
 */
object TerminalStateStore {

    /** Mirrors upstream's 20-workspace in-memory cache cap. */
    const val MAX_WORKSPACES = 20

    data class PersistedTab(val id: String, val title: String?, val number: Int)

    /**
     * Tab chrome plus the `terminal.title.numbered` counter ([nextNumber]).
     * The counter must outlive the tabs themselves: when every persisted id is
     * dead after a restart (daemon dropped the PTYs) the revive adopts nothing
     * and the next fresh tab would otherwise restart at "Terminal 1".
     */
    data class PersistedState(
        val activeId: String?,
        val tabs: List<PersistedTab>,
        val nextNumber: Int = 0,
    )

    /** Key for one workspace: the directory, or "" for the server cwd. */
    fun workspaceKey(directory: String?): String = directory?.trim().orEmpty()

    fun encode(state: PersistedState): String {
        val obj = JSONObject()
        state.activeId?.takeIf { it.isNotBlank() }?.let { obj.put("active", it) }
        obj.put("nextNumber", state.nextNumber.coerceAtLeast(0))
        val all = JSONArray()
        for (tab in state.tabs) {
            if (tab.id.isBlank()) continue
            val entry = JSONObject().put("id", tab.id).put("titleNumber", tab.number)
            tab.title?.takeIf { it.isNotBlank() }?.let { entry.put("title", it) }
            all.put(entry)
        }
        obj.put("all", all)
        return obj.toString()
    }

    fun decode(raw: String?): PersistedState {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return PersistedState(activeId = null, tabs = emptyList())
        val obj = runCatching { JSONObject(trimmed) }.getOrNull()
            ?: return PersistedState(activeId = null, tabs = emptyList())
        val tabs = ArrayList<PersistedTab>()
        val seen = HashSet<String>()
        val all = obj.optJSONArray("all") ?: JSONArray()
        for (i in 0 until all.length()) {
            val entry = all.optJSONObject(i) ?: continue
            val id = entry.optString("id", "").trim()
            if (id.isEmpty() || !seen.add(id)) continue
            tabs.add(
                PersistedTab(
                    id = id,
                    title = entry.optString("title", "").takeIf { it.isNotEmpty() },
                    number = entry.optInt("titleNumber", 0),
                ),
            )
        }
        val active = obj.optString("active", "").takeIf { it.isNotEmpty() && it in seen }
        // Payloads written before the counter existed carry no `nextNumber` —
        // fall back to the highest restored tab number instead of restarting
        // numbering from zero.
        val nextNumber = if (obj.has("nextNumber")) {
            obj.optInt("nextNumber", 0).coerceAtLeast(0)
        } else {
            tabs.maxOfOrNull { it.number }?.coerceAtLeast(0) ?: 0
        }
        return PersistedState(activeId = active, tabs = tabs, nextNumber = nextNumber)
    }

    /** Encodes the whole per-workspace map (`workspaceKey` → [encode]). */
    fun encodeAll(states: Map<String, PersistedState>): String {
        val obj = JSONObject()
        for ((key, state) in states) obj.put(key, JSONObject(encode(state)))
        return obj.toString()
    }

    fun decodeAll(raw: String?): Map<String, PersistedState> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyMap()
        val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyMap()
        val out = linkedMapOf<String, PersistedState>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = decode(obj.optJSONObject(key)?.toString())
        }
        return out
    }

    /**
     * Trims the workspace map to [MAX_WORKSPACES] entries on switch, keeping
     * the current workspace (upstream `caches` trim in `context.tsx`).
     */
    fun trim(
        states: Map<String, PersistedState>,
        currentKey: String,
        max: Int = MAX_WORKSPACES,
    ): Map<String, PersistedState> {
        if (states.size <= max || max <= 0) return states
        val out = linkedMapOf<String, PersistedState>()
        states[currentKey]?.let { out[currentKey] = it }
        for ((key, state) in states) {
            if (out.size >= max) break
            if (key != currentKey) out[key] = state
        }
        return out
    }

    /** Drops one tab (upstream `pty.exited` cleanup, `terminal.tsx:154-171`). */
    fun withoutTab(state: PersistedState, id: String): PersistedState {
        if (state.tabs.none { it.id == id }) return state
        val tabs = state.tabs.filter { it.id != id }
        val active = state.activeId?.takeIf { it != id } ?: tabs.firstOrNull()?.id
        return PersistedState(activeId = active, tabs = tabs, nextNumber = state.nextNumber)
    }

    /** Snapshot of a live [TerminalTabs] for persistence. */
    fun snapshot(tabs: TerminalTabs): PersistedState =
        PersistedState(
            activeId = tabs.activeId,
            tabs = tabs.tabs.map { PersistedTab(id = it.id, title = it.title, number = it.number) },
            nextNumber = tabs.issuedNumber,
        )
}
