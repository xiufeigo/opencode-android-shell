package com.xiufeigo.opencodeshell.ui.session.drawer

import com.xiufeigo.opencodeshell.OpenCodeClient

/**
 * Pure (JVM-testable) row order for the session drawer.
 *
 * [com.xiufeigo.opencodeshell.ChatViewModel.listSessions] already sorts by
 * `updatedAt` descending, but the sort is repeated here so the view cannot
 * depend on its caller's ordering — the same rule `HomeSessions.rows` follows.
 * Two things the drawer's own copy pins:
 *
 * - **the tie-break is upstream's** (`home/sessions/index.ts:63-64`,
 *   `retainHomeSessions`: `time.updated` descending, then `id` ascending). Equal
 *   timestamps are real — `time.updated` is written per row-writing event, and
 *   two sessions touched by one burst can share a millisecond — and without a
 *   tie-break the drawer's order for those two rows is whatever the server's
 *   page happened to return, i.e. it can differ between two reads of the same
 *   data;
 * - **an unknown time (`updatedAt <= 0`) sorts last**, never first: such a row
 *   renders no time at all ([SessionTimeLabel.relative] answers `null`), so it
 *   must not claim the top of a newest-first list.
 *
 * What this cannot fix is the *freshness* of the rows it is given: the drawer
 * re-reads the list when the server's `time.updated` moves
 * (`ChatViewModel.sessionsRevision`) and measures the relative labels against a
 * clock that ticks while it is open ([SessionDrawerContent]'s `now`), because a
 * Composable row is otherwise composed once and skipped — its `updatedAt` input
 * does not change as time passes.
 */
object SessionDrawerOrder {

    /** [sessions] newest first; see the class note for the key and tie-break. */
    fun rows(sessions: List<OpenCodeClient.Session>): List<OpenCodeClient.Session> =
        sessions.sortedWith(
            compareByDescending<OpenCodeClient.Session> { it.updatedAt }.thenBy { it.id },
        )
}
