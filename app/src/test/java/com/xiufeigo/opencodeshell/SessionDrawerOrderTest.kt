package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.drawer.SessionDrawerOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A-3: the session drawer's row order — the server's `time.updated`, newest
 * first.
 *
 * The v27 device run reported the drawer leading with 「另一个旧会话（标着
 * just now）」 while the active session's server `updated` was the newest. This
 * pins the order the rows are rendered in (the key and the tie-break), so a
 * re-sort cannot quietly change which row leads; the *freshness* half — the list
 * re-reads on a row write, the labels follow the clock — is pinned by
 * [SessionDrawerFreshnessTest].
 *
 * Anchors: `_upstream/packages/app/src/home/sessions/index.ts:63-64`
 * (`retainHomeSessions`: `time.updated` descending, then `id` ascending) and
 * `packages/tui/src/component/dialog-open.tsx:168` (the TUI's session list sorts
 * by `time.updated` alone).
 */
class SessionDrawerOrderTest {

    private fun session(id: String, updatedAt: Long) =
        OpenCodeClient.Session(id = id, title = id, directory = "/w", updatedAt = updatedAt)

    @Test
    fun `newest updated row leads`() {
        val rows = SessionDrawerOrder.rows(
            listOf(
                session("oldest", 1_000L),
                session("newest", 9_000L),
                session("middle", 5_000L),
            ),
        )

        assertEquals(listOf("newest", "middle", "oldest"), rows.map { it.id })
    }

    @Test
    fun `the row whose updated moved leads even when the caller hands it last`() {
        // The reported shape: the drawer renders the caller's list as-is, and the
        // list was a snapshot from *before* the active session's row write — so
        // the active session sat below an older row. The order is this view's own.
        val rows = SessionDrawerOrder.rows(
            listOf(session("older", 1_000L), session("active", 9_000L)),
        )

        assertEquals(listOf("active", "older"), rows.map { it.id })
    }

    @Test
    fun `equal timestamps tie-break on id, not on the caller's order`() {
        // One burst can touch two sessions inside the same millisecond
        // (`time.updated` is written per row-writing event), and without a
        // tie-break the two rows would follow the server's page order — i.e. the
        // drawer could disagree with itself between two reads of the same data.
        val ascending = SessionDrawerOrder.rows(
            listOf(session("ses_a", 5_000L), session("ses_b", 5_000L)),
        )
        val descending = SessionDrawerOrder.rows(
            listOf(session("ses_b", 5_000L), session("ses_a", 5_000L)),
        )

        assertEquals(listOf("ses_a", "ses_b"), ascending.map { it.id })
        assertEquals(ascending.map { it.id }, descending.map { it.id })
    }

    @Test
    fun `an unknown time never leads`() {
        // `updatedAt <= 0` renders no time at all (`SessionTimeLabel.relative`
        // answers null), so such a row cannot claim to be the newest thing on
        // screen — it sinks below every row with a real time. (Between two
        // unknown rows the comparator is still plain descending; neither renders
        // a time, so the pair is indistinguishable on screen.)
        val rows = SessionDrawerOrder.rows(
            listOf(session("unknown", 0L), session("negative", -1L), session("known", 1L)),
        )

        assertEquals("known", rows.first().id)
        assertEquals(listOf("known", "unknown", "negative"), rows.map { it.id })
    }

    @Test
    fun `every row survives the sort`() {
        val sessions = listOf(
            session("a", 1L),
            session("b", 0L),
            session("c", 5L),
            session("d", 5L),
        )

        val rows = SessionDrawerOrder.rows(sessions)

        assertEquals(sessions.size, rows.size)
        assertEquals(sessions.map { it.id }.toSet(), rows.map { it.id }.toSet())
    }
}
