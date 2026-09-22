package com.xiufeigo.opencodeshell.ui.nav

// W7: the pure half of the session tab bar (research/02 section 9b) - the two
// decisions the shell cannot afford to get wrong, transcribed from upstream so
// :app:testDebugUnitTest pins them without a device:
//
// - which tab takes over after a close - nextTabAfterClose(tabs, index,
//   active) (_upstream/packages/app/src/shell/tabs/closed.ts:31-34, called by
//   tabs.tsx:185 removeTab), and
// - the tab label - sessionTabTitle(title, fallback)
//   (_upstream/packages/app/src/shell/titlebar/tab-title.ts:4-6, called by
//   tab-nav.tsx:64-67).
//
// BottomTabStrip (the mobile bottom placement, research/02 section 9b) and
// DesktopTabStrip (the desktop titlebar, titlebar.tsx:441-704) both render
// the same openSessions list and both close through this object, so the two
// placements can never disagree about what a close does.
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

// The list after a close, plus the navigation TabCloseTarget it implies.
data class CloseResult(val tabs: List<SessionTabRef>, val target: TabCloseTarget)
