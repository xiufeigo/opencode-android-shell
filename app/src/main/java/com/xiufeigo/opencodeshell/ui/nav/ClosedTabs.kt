package com.xiufeigo.opencodeshell.ui.nav

// B-6: the pure half of the closed-tab stack — `tab.close` records a tab and
// `tab.reopenClosed` restores it, transcribed from upstream
// `_upstream/packages/app/src/shell/tabs/closed.ts` (called by
// `tabs.tsx:365-390` `closeTab` / `reopenClosedTab`, bound to `mod+shift+t` by
// `titlebar/titlebar.tsx:406-412`).
//
// Only session tabs are recorded: closing a draft tab deletes its persisted
// state, so a reopened draft would come back empty (closed.ts:6-7). The shell's
// tab model is [SessionTabRef] (id + title), so a closed entry carries the ref
// plus the index it occupied — the insertion point `reopenClosedTab` uses
// (`Math.min(entry.index, store.length)`, tabs.tsx:381).
object ClosedTabs {

    /** `CLOSED_TAB_LIMIT = 25` (closed.ts:6). */
    const val LIMIT: Int = 25

    /**
     * `pushClosedTab` (closed.ts:10-13): append, then keep the newest [LIMIT]
     * entries. A close of a tab that is not open anymore still records — the
     * caller only pushes for a real close (`SessionTabs.close` returned a
     * different list).
     */
    fun push(stack: List<ClosedTab>, tab: SessionTabRef, index: Int): List<ClosedTab> =
        (stack + ClosedTab(tab, index.coerceAtLeast(0))).takeLast(LIMIT)

    /**
     * `takeClosedTab` (closed.ts:17-25): pop the most recently closed tab that is
     * **not open again**, discarding stale entries along the way. [openIds] is
     * the shell's current tab set; when the popped entry is open again it is
     * dropped and the next one is considered.
     */
    fun take(stack: List<ClosedTab>, openIds: Set<String>): TakeResult {
        val remaining = stack.toMutableList()
        while (remaining.isNotEmpty()) {
            val entry = remaining.removeAt(remaining.lastIndex)
            if (entry.tab.id !in openIds) return TakeResult(entry, remaining)
        }
        return TakeResult(null, remaining)
    }

    /**
     * `removeClosedTabs` (closed.ts:27-30): drop every entry for a session the
     * server no longer has. The shell prunes open tabs on every
     * `sessionsRevision` bump; the closed stack has to follow, or
     * `tab.reopenClosed` would navigate to a deleted session.
     */
    fun remove(stack: List<ClosedTab>, sessionIds: Collection<String>): List<ClosedTab> {
        if (sessionIds.isEmpty()) return stack
        val removed = sessionIds.toSet()
        return stack.filterNot { it.tab.id in removed }
    }

    /** The insertion point `reopenClosedTab` uses: `Math.min(entry.index, tabs.length)`. */
    fun insertIndex(entry: ClosedTab, openCount: Int): Int =
        entry.index.coerceIn(0, openCount.coerceAtLeast(0))
}

/** One recorded close: the tab and the index it occupied (`ClosedTab` schema). */
data class ClosedTab(val tab: SessionTabRef, val index: Int)

/** `takeClosedTab`'s pair: the entry to restore (or null) and the pruned stack. */
data class TakeResult(val entry: ClosedTab?, val stack: List<ClosedTab>)
