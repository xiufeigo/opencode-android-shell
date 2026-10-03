package com.xiufeigo.opencodeshell.ui.nav

// W7: the pure half of the session tab bar (research/02 section 9b) - the
// decisions the shell cannot afford to get wrong, transcribed from upstream so
// :app:testDebugUnitTest pins them without a device:
//
// - which tab takes over after a close - nextTabAfterClose(tabs, index,
//   active) (_upstream/packages/app/src/shell/tabs/closed.ts:31-34, called by
//   tabs.tsx:185 removeTab),
// - the tab label - sessionTabTitle(title, fallback)
//   (_upstream/packages/app/src/shell/titlebar/tab-title.ts:4-6, called by
//   tab-nav.tsx:64-67), and
// - the drag result - reorder(tabs, fromIndex, toIndex), upstream's
//   arrayMove in onDragEnd (tab-strip.tsx:334-352).
//
// BottomTabStrip (the mobile bottom placement, research/02 section 9b) and
// DesktopTabStrip (the desktop titlebar, titlebar.tsx:441-704) both render
// the same openSessions list, both close through this object and both reorder
// through it, so the two placements can never disagree about what a close or a
// drag does.
object SessionTabs {

    // nextTabAfterClose(tabs: Tab[], index, active: boolean)
    // (closed.ts:31-34): when the closed tab was NOT active the route does
    // not move at all (undefined, TabCloseTarget.Stay); when it was, the tab
    // to the right wins (tabs[index + 1]), else the tab to the left
    // (tabs[index - 1]), else there is nothing left and upstream navigates to
    // / (TabCloseTarget.Home).
    fun nextAfterClose(tabs: List<SessionTabRef>, index: Int, active: Boolean): TabCloseTarget {
        if (!active) return TabCloseTarget.Stay
        val next = tabs.getOrNull(index + 1) ?: tabs.getOrNull(index - 1)
        return if (next == null) TabCloseTarget.Home else TabCloseTarget.Open(next.id)
    }

    // Closes id out of tabs and reports where the shell should go.
    //
    // tabs.tsx:180-203 computes nextTabAfterClose on the list BEFORE the
    // splice, and active is recentKey() === key && pathname !== "/" - i.e.
    // "was the closed tab the one on screen". An id that is not open closes
    // nothing and leaves the route alone.
    fun close(tabs: List<SessionTabRef>, id: String, activeId: String?): CloseResult {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return CloseResult(tabs, TabCloseTarget.Stay)
        return CloseResult(
            tabs = tabs.filterIndexed { i, _ -> i != index },
            target = nextAfterClose(tabs, index, id == activeId),
        )
    }

    // v49 BUG-2 (`research/v49_verify/tC/v49_42/43/44_stale_tab.png`): a
    // session deleted from the shell (the header ⋮ flow or the drawer row) left
    // its tab in the strip — the row was gone from every list, the tab was not.
    //
    // The shell already prunes tabs against a fresh `listSessions` on every
    // `sessionsRevision` bump, but that read is server-side and the server can
    // still answer with the deleted row (eventual consistency), in which case
    // nothing bumps the revision again and the stale tab lives until the next
    // unrelated change. So the delete itself closes the tab, by **id**.
    //
    // By id, never by title: an imported copy of a session carries the same
    // title as the original (`v49_43`), and closing "the tab with that title"
    // would drop the surviving session's tab as well. [id] is the session
    // identity the server returned for the deleted write, so the match is
    // exact; a same-named sibling keeps its tab and its own id.
    //
    // The closed stack follows, for the same reason `ClosedTabs.remove` does
    // (`closed.ts:27-30`): a recorded close for a deleted session must not be
    // reopenable. Navigation is deliberately **not** decided here — the caller
    // that performed the delete already backs out of the session screen.
    //
    // v70 P2: the delete paths now report through [afterDeleteFromElsewhere] —
    // the same by-id close and closed-stack purge, plus the route the tab that
    // takes over asks for. That decision has to be reachable from a delete whose
    // answer lands after the screen that asked is gone (the 5556 「删除成功但 tab
    // 不同步关」), which a caller-side `onBack()` cannot be. This function stays
    // as the v49 statement of the by-id rule; the rule itself is unchanged.
    fun afterSessionDeleted(
        tabs: List<SessionTabRef>,
        closed: List<ClosedTab>,
        id: String,
    ): DeletedTabs {
        if (id.isEmpty()) return DeletedTabs(tabs, closed)
        return DeletedTabs(
            tabs = tabs.filterNot { it.id == id },
            closed = ClosedTabs.remove(closed, listOf(id)),
        )
    }

    // v50 LOW-2a (`research/v50_verify/tD/v50_00`, 「This session cannot be
    // found」): the tab set tracks the **server's** session list, so every id the
    // catalogue does not carry is a dead tab - and the shell has two ways to end
    // up holding one:
    //
    // - a cold start restores the stored route (`LastRouteStore`) into a
    //   session that was deleted while the process was gone. The nav listener
    //   adds a tab for whatever id the restored route carries, so the strip
    //   shows a tab whose session 404s - until something else prunes it. The
    //   `sessionsRevision` prune is no help here: a cold start bumps nothing,
    //   so the tab outlives the error screen;
    // - a list read that was **issued before** a create landed answers without
    //   the new row (`ChatViewModel.listSessions` fetches on every call, but two
    //   reads can be in flight), and the prune then drops the tab the new
    //   session's own navigation just added (v50 LOW-2b, 新建会话 tab 不出现).
    //
    // [protectedIds] is the escape hatch for the second case and nothing else:
    // ids this run created and the catalogue has not reported yet. It is
    // deliberately **not** "every id we ever navigated to", and — v51 LOW-1
    // (`research/v51_verify/tD/v51_57_deadtab_restart.png`) — deliberately not
    // "the session on screen" either: a dead tab that happens to be the active
    // one was exactly the case that made this prune a no-op, so a cold start
    // into a session deleted while the process was gone kept restoring its tab
    // over 「This session cannot be found」 (the same symptom as
    // `research/v50_verify/tD/v50_00`). What the prune returns for such an id is
    // [TabPruneResult.prunedSessionIds] carrying it, and the caller backs the
    // route out to Home - so the protection has to stay narrow enough for the
    // restore prune to be a fix rather than a second way to keep a dead tab.
    //
    // The closed stack follows the tab set for the same reason the prune above
    // drops it (`removeClosedTabs`, closed.ts:27-30): a recorded close for a
    // session the server no longer lists must not be reopenable.
    fun pruneAgainstServerList(
        tabs: List<SessionTabRef>,
        closed: List<ClosedTab>,
        serverIds: List<String>,
        protectedIds: Set<String> = emptySet(),
    ): TabPruneResult {
        val live = serverIds.toSet()
        fun dead(id: String) = id !in live && id !in protectedIds
        val gone = tabs.filter { dead(it.id) }.map { it.id }
        val goneClosed = closed.map { it.tab.id }.filter(::dead)
        if (gone.isEmpty() && goneClosed.isEmpty()) {
            return TabPruneResult(tabs, closed, emptyList())
        }
        return TabPruneResult(
            tabs = tabs.filterNot { dead(it.id) },
            closed = if (goneClosed.isEmpty()) closed else ClosedTabs.remove(closed, goneClosed),
            prunedSessionIds = gone,
        )
    }

    // v63 BUG-1 (`research/v63_verify/5556/92_final.png`): Home's row delete
    // (`ui/App.kt`) closed the tab but never moved the route and never touched
    // the closed stack, so it was a second, weaker copy of the delete above:
    // a recorded close for the deleted session stayed reopenable, and the
    // delete of the session currently on screen was decided outside the one
    // function that owns "where does the route go".
    //
    // This is that decision, for a delete the shell performs while the user is
    // somewhere *other* than the deleted session (Home, the drawer). It is
    // [close] — the same splice and the same neighbouring-target rule the ×
    // button uses — plus the closed-stack purge of [afterSessionDeleted]. A
    // delete of a session that is not open closes nothing and moves nothing,
    // which is what upstream's `notifySessionTabsRemoved` does.
    fun afterDeleteFromElsewhere(
        tabs: List<SessionTabRef>,
        closed: List<ClosedTab>,
        id: String,
        activeId: String?,
    ): DeleteRouteResult {
        val result = close(tabs, id, activeId)
        return DeleteRouteResult(
            tabs = result.tabs,
            closed = if (id.isEmpty()) closed else ClosedTabs.remove(closed, listOf(id)),
            target = result.target,
        )
    }

    // B-20: the drag result. Upstream's onDragEnd (tab-strip.tsx:334-352) runs
    // arrayMove over the *visible* tab order and merges it back around hidden
    // tabs (mergeVisibleTabOrder, tab-order.ts:8-12). Every open session is a
    // visible slot in this shell - the desktop strip and the mobile drawer both
    // render the whole list, and a draft is a route rather than a tab - so the
    // merge collapses into the move itself, and one helper serves both
    // placements.
    //
    // Out-of-range indices (a drag that ended after the list changed under it)
    // are a no-op, like arrayMove's own guard.
    fun reorder(tabs: List<SessionTabRef>, fromIndex: Int, toIndex: Int): List<SessionTabRef> {
        if (fromIndex !in tabs.indices || toIndex !in tabs.indices || fromIndex == toIndex) return tabs
        val next = tabs.toMutableList()
        val tab = next.removeAt(fromIndex)
        next.add(toIndex, tab)
        return next
    }

    // v51 MED-2 (`research/v51_verify/tC/v51_47/48/50` 新建会话 tab 不出现,
    // `v51_51`; 有空位时 1s 内出现 `v51_43`): the strip overflows, so a tab
    // created (or switched to) at the end lands **outside the viewport** — the
    // session screen is already on it, the strip just cannot show it, and it
    // only appears once the user closes an older tab. Upstream scrolls the
    // current tab into view on every render of the active slot
    // (`TitlebarTabStrip`), so this is that decision, made pure.
    //
    // [bounds] is each tab's own range in the scroll content's coordinates
    // (the strip measures them), [viewport] the scrolling row's width and
    // [current] its scroll offset. The result is the smallest scroll that puts
    // the **whole** tab inside the viewport, or **null** when it is already
    // visible — nothing to animate, which is both the common case and the one
    // that keeps a user-driven fling from being fought.
    fun revealOffset(
        bounds: Map<String, IntRange>,
        viewport: Int,
        current: Int,
        id: String,
    ): Int? {
        if (viewport <= 0) return null
        val tab = bounds[id] ?: return null
        val target = when {
            tab.first < current -> tab.first
            tab.last > current + viewport -> tab.last - viewport + 1
            else -> return null
        }
        return target.coerceAtLeast(0).takeIf { it != current }
    }

    // The label of one tab (tab-title.ts:4-6): !title || isFallbackTitle(title)
    // ? fallback : title. Blank counts as missing - upstream's !title only
    // covers ""/undefined, and a whitespace-only title is not renderable
    // either.
    fun displayTitle(title: String?, fallback: String): String =
        if (title.isNullOrBlank() || isFallbackTitle(title)) fallback else title

    // isFallbackTitle from @opencode/util/session-title-fallback - NOT in
    // this checkout (research/audit_session_ui.md:585), so only the shape
    // that _upstream/packages/app/src/session/title.ts:4 pins is reproduced:
    // the auto-generated New session - <ISO instant> / Child session - <ISO
    // instant> titles, which upstream hides behind the localized session label
    // instead of showing the raw timestamp.
    fun isFallbackTitle(title: String?): Boolean =
        title != null && FALLBACK_TITLE.matches(title)

    // session/title.ts:4, quoted verbatim as a Kotlin Regex.
    private val FALLBACK_TITLE =
        Regex("^(New session|Child session) - \\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")

    // WAVE 2.3b P0 - the v2 mobile drawer model behind MobileTabsDrawer:
    // upstream titlebar.tsx:462-592 renders TitlebarTabStrip
    // orientation=vertical (every open tab, current marked) + a New-session row
    // + a footer with a Home row (mobile-tabs-home, pressed while Home is on
    // screen) and a Settings/Help row pair.
    //
    // Which footer row is highlighted: Home paints pressed off a session route
    // (titlebar.tsx:555: data-state=pressed iff route.type === "home");
    // Settings never does - it is a fire-and-forget action, not a destination.
    // footerHomePressed is therefore exactly !onSessionRoute, and App.kt
    // passes the same currentSession == null value both strips already use.
    fun footerHomePressed(onSessionRoute: Boolean): Boolean = !onSessionRoute

    // WAVE 2.3b P1 - openNewTab's session-route branch (titlebar.tsx:271-296).
    //
    // + on a session inherits the current session's model into the draft
    // (tabs.stateValue<ComposerState>(sessionTab, "prompt")?.model.current())
    // and drops the caller to Home's newSessionProject() fallback
    // (titlebar.tsx:305-321: selected > recent > first project) whenever there
    // is no live session to inherit from. The host owns the composer read; this
    // decides which of the two upstream branches applies and which directory the
    // Home branch drafts in.
    //
    // @param onSessionRoute whether the + tap happens on a session route.
    // @param activeSessionId the session on screen, or null (matches
    //   session() returning undefined - nothing to inherit).
    // @param selectedDirectory layout.home.selection()'s directory, if any.
    // @param projectDirectories the server's project catalogue in list order.
    // @param mostRecentDirectory projects.last(), the MRU marker, if known.
    fun draftTarget(
        onSessionRoute: Boolean,
        activeSessionId: String?,
        selectedDirectory: String?,
        projectDirectories: List<String>,
        mostRecentDirectory: String?,
    ): DraftTarget {
        if (onSessionRoute && !activeSessionId.isNullOrBlank()) {
            return DraftTarget.InheritSession(activeSessionId)
        }
        val selected = selectedDirectory?.takeIf { it.isNotBlank() }
        if (selected != null) return DraftTarget.InheritProject(selected)
        val recent = mostRecentDirectory?.takeIf { it.isNotBlank() }
        if (recent != null) return DraftTarget.InheritProject(recent)
        val first = projectDirectories.firstOrNull { it.isNotBlank() }
        if (first != null) return DraftTarget.InheritProject(first)
        return DraftTarget.DefaultDirectory
    }

    // WAVE 2.3b P1 - tabs.toggleHome (tabs.tsx:485-497): pressing Home while
    // already on Home jumps back to the most recent tab (recentKey()), instead
    // of being a no-op.
    //
    // @param onHomeRoute whether Home is on screen.
    // @param mostRecentTabId recent.key mapped back to an open tab, or null
    //   when the recent tab closed since (then Home stays put).
    fun toggleHomeTarget(onHomeRoute: Boolean, mostRecentTabId: String?): HomeToggle =
        if (onHomeRoute && !mostRecentTabId.isNullOrBlank()) HomeToggle.BackToTab(mostRecentTabId)
        else HomeToggle.GoHome
}

// Which directory context a new draft inherits - SessionTabs.draftTarget.
//
// InheritSession carries the session id so App.kt can read that session's
// composer model (stateValue(...).model.current()) before opening the draft;
// both project variants carry the resolved directory for the draft route.
sealed interface DraftTarget {
    // titlebar.tsx:274-295: inherit the on-screen session's model.
    data class InheritSession(val sessionId: String) : DraftTarget

    // titlebar.tsx:306-318: Home branch - selected > recent > first.
    data class InheritProject(val directory: String) : DraftTarget

    // No selection and no catalogue: the draft falls back to the server cwd.
    object DefaultDirectory : DraftTarget
}

// SessionTabs.toggleHomeTarget - where the Home button goes.
sealed interface HomeToggle {
    // Already on Home with a live recent tab: jump back to it.
    data class BackToTab(val tabId: String) : HomeToggle

    // Otherwise: go Home (recording the current tab as recent first).
    object GoHome : HomeToggle
}

// Where the shell navigates after a close - upstream returns
// Tab | null | undefined (closed.ts:31-34), which is exactly three cases.
sealed interface TabCloseTarget {
    // undefined: the closed tab was not on screen, so the route does not move.
    object Stay : TabCloseTarget

    // null: no tab was left; upstream navigates to / (Home).
    object Home : TabCloseTarget

    // The adjacent tab that takes over - tabs[index + 1] ?? tabs[index - 1].
    data class Open(val id: String) : TabCloseTarget
}

// SessionTabs.pruneAgainstServerList — the tab set and the closed stack once
// the tabs the server no longer lists are gone, plus the ids that were pruned
// (the caller decides what that means for the route it is showing).
data class TabPruneResult(
    val tabs: List<SessionTabRef>,
    val closed: List<ClosedTab>,
    val prunedSessionIds: List<String>,
)

// SessionTabs.afterSessionDeleted — what the tab set and the closed stack are
// once a session is gone. Only the deleted session's own entry moves.
data class DeletedTabs(val tabs: List<SessionTabRef>, val closed: List<ClosedTab>)

// SessionTabs.afterDeleteFromElsewhere — the same, plus where the route goes.
data class DeleteRouteResult(
    val tabs: List<SessionTabRef>,
    val closed: List<ClosedTab>,
    val target: TabCloseTarget,
)

// The list after a close, plus the navigation TabCloseTarget it implies.
data class CloseResult(val tabs: List<SessionTabRef>, val target: TabCloseTarget)
