package com.xiufeigo.opencodeshell.ui.terminal.share

/**
 * Tab model of the terminal panel (M5 phase 1) — pure state, so the multi
 * terminal rules of `research/02_ui_screens.md` §9.3 are unit-testable:
 *
 * - `MAX_TERMINAL_SESSIONS = 20` (the 21st terminal is refused);
 * - opening a tab focuses it, closing the last tab closes the panel
 *   ([close] returns `false`, and the caller pops the route);
 * - closing the active tab activates its right neighbour, else the new last one;
 * - `number` is the `terminal.title.numbered` counter and is **not** reused after
 *   a close, so a title like "Terminal 2" keeps its meaning;
 * - the tab list is reorderable (`terminal.move` upstream).
 *
 * The PTY connection of a tab lives in the UI layer (`PtyClient` per id), so
 * closing a tab there means *unmount*: close the socket, release the engine and
 * `DELETE /pty/:id`.
 */
class TerminalTabs(private val max: Int = PtyProtocol.MAX_TERMINAL_SESSIONS) {

    data class Tab(
        val id: String,
        /** 1-based `terminal.title.numbered` counter. */
        val number: Int,
        /** User title; `null` means the numbered default. */
        val title: String? = null,
    )

    private val items = ArrayList<Tab>()

    /**
     * Monotonic `terminal.title.numbered` counter. It deliberately does not
     * reuse a closed tab's number: showing "Terminal 2" again for a brand-new
     * shell after the original one was closed reads as the same session.
     */
    private var lastNumber = 0

    var activeId: String? = null
        private set

    val tabs: List<Tab> get() = items.toList()

    val size: Int get() = items.size

    val isEmpty: Boolean get() = items.isEmpty()

    val isFull: Boolean get() = items.size >= max

    /**
     * Registers a server-created PTY as a tab. Returns `null` when the id is
     * blank, already known, or the panel is at [max] terminals.
     */
    fun add(id: String, title: String? = null, number: Int? = null, focus: Boolean = true): Tab? {
        if (id.isBlank() || items.any { it.id == id } || isFull) return null
        val tab = Tab(id = id, number = number ?: nextNumber(), title = title)
        items += tab
        if (focus || activeId == null) activeId = tab.id
        return tab
    }

    /**
     * Removes a tab. Returns `true` while tabs remain (the panel stays open) and
     * `false` when the panel itself should close — the §9.3 "closing the last
     * terminal closes the panel" rule.
     */
    fun close(id: String): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return items.isNotEmpty()
        val wasActive = activeId == id
        items.removeAt(index)
        if (wasActive) {
            activeId = items.getOrNull(index)?.id ?: items.lastOrNull()?.id
        }
        return items.isNotEmpty()
    }

    fun activate(id: String): Boolean {
        if (items.none { it.id == id }) return false
        activeId = id
        return true
    }

    fun rename(id: String, title: String): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        items[index] = items[index].copy(title = title.trim().takeIf { it.isNotEmpty() })
        return true
    }

    /** Drag-and-drop reorder (`@dnd-kit` upstream); out-of-range indices are no-ops. */
    fun move(fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex !in items.indices || toIndex !in items.indices || fromIndex == toIndex) return false
        val tab = items.removeAt(fromIndex)
        items.add(toIndex, tab)
        return true
    }

    fun find(id: String): Tab? = items.firstOrNull { it.id == id }

    /** Panel unmount: forget everything (connections are closed by the caller). */
    fun clear() {
        items.clear()
        activeId = null
        lastNumber = 0
    }

    private fun nextNumber(): Int {
        lastNumber += 1
        return lastNumber
    }
}
