package com.xiufeigo.opencodeshell.ui.session.timeline

import com.xiufeigo.opencodeshell.ChatStore

/**
 * Pure (JVM-testable) timeline grouping — the port of upstream `groupParts()`
 * (`research/02` §3.7).
 *
 * Rules, verified against the source:
 * - only **consecutive** `read | glob | grep | list` parts merge into one
 *   `context` group; any other part flushes the group;
 * - `todowrite` is hidden entirely (`HIDDEN_TOOLS`);
 * - a group is `busy` while the run is working (the trailing group only);
 * - the summary counts read / search(glob+grep) / list.
 *
 * The grouping is a *display* concern: `ChatStore` keeps every part and the
 * message-level `appendFingerprint()` still sees the untouched part list, so
 * bottom-following keeps working across a group transition.
 */
object TimelineGrouping {

    /** `CONTEXT_GROUP_TOOLS` — the tools that merge into an "Exploring" row. */
    val CONTEXT_GROUP_TOOLS = setOf("read", "glob", "grep", "list")

    /** `HIDDEN_TOOLS` — never rendered (the todo dock / status row is M6). */
    val HIDDEN_TOOLS = setOf("todowrite")

    /** One row of an assistant turn. */
    sealed interface Row {
        /** Stable `LazyColumn` key (a group is keyed by its first part). */
        val key: String

        data class Part(val part: ChatStore.UiPart) : Row {
            override val key: String get() = part.id
        }

        data class Context(
            val parts: List<ChatStore.UiPart>,
            val busy: Boolean,
        ) : Row {
            override val key: String get() = "ctx:" + parts.first().id
        }
    }

    /** read / search / list counts of a context group (`contextToolSummary()`). */
    data class Summary(val read: Int, val search: Int, val list: Int) {
        val total: Int get() = read + search + list
        val isEmpty: Boolean get() = total == 0
    }

    /**
     * Assistant part list → display rows.
     *
     * @param working the turn is still running; the last group then renders the
     *   "Exploring" label instead of "Explored".
     */
    fun rowsFor(parts: List<ChatStore.UiPart>, working: Boolean = false): List<Row> {
        val rows = ArrayList<Row>(parts.size)
        var pending = ArrayList<ChatStore.UiPart>()
        var lastGroupIndex = -1

        fun flush() {
            if (pending.isEmpty()) return
            rows += Row.Context(parts = pending, busy = false)
            lastGroupIndex = rows.lastIndex
            pending = ArrayList()
        }

        for (part in parts) {
            if (part.type == "tool" && part.toolName in HIDDEN_TOOLS) continue
            val grouped = part.type == "tool" && part.toolName in CONTEXT_GROUP_TOOLS
            if (grouped) {
                pending.add(part)
            } else {
                flush()
                rows += Row.Part(part)
            }
        }
        flush()

        if (working && lastGroupIndex >= 0) {
            val group = rows[lastGroupIndex] as Row.Context
            rows[lastGroupIndex] = group.copy(busy = true)
        }
        return rows
    }

    /** `contextToolSummary()` — read / search (glob+grep) / list. */
    fun summary(parts: List<ChatStore.UiPart>): Summary {
        var read = 0
        var search = 0
        var list = 0
        for (part in parts) {
            when (part.toolName) {
                "read" -> read++
                "glob", "grep" -> search++
                "list" -> list++
            }
        }
        return Summary(read = read, search = search, list = list)
    }
}
