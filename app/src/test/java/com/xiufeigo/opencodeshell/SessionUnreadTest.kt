package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-2: the server-side unread watermark ([SessionUnread]) — the TUI client's
 * `isUnread` (`_upstream/packages/tui/src/context/session-tabs.tsx:120-123`)
 * over `Session.Info.time.idle` / `time.viewed`
 * (`_upstream/packages/schema/src/session.ts:44-49`).
 *
 * The rule is what makes a background turn completion visible at all: the
 * watermark lives on the session row, so the tab strip and the session drawer
 * can light the dot from a plain list read, with no stream open.
 */
class SessionUnreadTest {

    @Test
    fun aSettledTransitionNobodyAcknowledgedIsUnread() {
        // The whole point: the server recorded an idle and no client reported
        // having seen it (`time.viewed` absent — the state every session is in
        // before this client ever opens it).
        assertTrue(SessionUnread(idle = 1_700_000_000_000L, viewed = null).unread)
    }

    @Test
    fun aNewerIdleThanTheAcknowledgedOneIsUnread() {
        assertTrue(
            SessionUnread(idle = 1_700_000_000_500L, viewed = 1_700_000_000_000L).unread,
        )
    }

    @Test
    fun anAcknowledgedIdleIsRead() {
        assertFalse(
            SessionUnread(idle = 1_700_000_000_000L, viewed = 1_700_000_000_000L).unread,
        )
    }

    @Test
    fun aViewerAheadOfTheIdleKeepsItRead() {
        // `time_viewed` is monotone server-side (`projector.ts:583-593`), so a
        // client that posted a newer watermark than the idle it saw must not be
        // told the older transition is still unseen.
        assertFalse(
            SessionUnread(idle = 1_700_000_000_000L, viewed = 1_700_000_001_000L).unread,
        )
    }

    @Test
    fun aSessionThatNeverSettledIsNeverUnread() {
        // No terminal transition recorded: a brand-new or still-running session
        // has nothing to be unread about (its dot, if any, comes from
        // `needsAttention` / the run status).
        assertFalse(SessionUnread(idle = null, viewed = null).unread)
        assertFalse(SessionUnread(idle = null, viewed = 1_700_000_000_000L).unread)
    }

    @Test
    fun anEmptyRowIsPlain() {
        assertFalse(SessionUnread().unread)
    }

    @Test
    fun anAlreadyRecordedAcknowledgementIsAFloor() {
        // A read that raced the `POST …/view` (the row still carries the older —
        // or no — `time.viewed`) must not un-read the transition the user just
        // saw: `time_viewed` is monotone server-side, so the local value wins.
        val raced = SessionUnread(idle = 1_700_000_000_000L, viewed = null)
        val acked = SessionUnread(idle = 1_700_000_000_000L, viewed = 1_700_000_000_000L)
        assertFalse(raced.withAcknowledged(acked).unread)
        // A newer idle than the floor is still unread: the floor only covers the
        // transition it acknowledged.
        val newer = SessionUnread(idle = 1_700_000_009_000L, viewed = null)
        assertTrue(newer.withAcknowledged(acked).unread)
    }

    @Test
    fun aFreshRowIsKeptAsIs() {
        val row = SessionUnread(idle = 1_700_000_000_000L, viewed = 1_600_000_000_000L)
        assertEquals(row, row.withAcknowledged(null))
        // A floor *below* what the server already reported changes nothing.
        assertEquals(row, row.withAcknowledged(SessionUnread(viewed = 1_500_000_000_000L)))
    }

    @Test
    fun anAcknowledgementCoversTheBoundaryItNames() {
        // `ackSessionViewed` records the boundary it posted, so the row reads as
        // observed locally even when the POST has not landed (or failed): the
        // v17 report's dot lingered because the mirror kept a *stale* watermark
        // that the next read's newer idle was compared against.
        val fresh = SessionUnread(idle = 1_700_000_000_000L, viewed = null)
        assertEquals(
            SessionUnread(idle = 1_700_000_000_000L, viewed = 1_700_000_000_000L),
            fresh.ackedAt(1_700_000_000_000L),
        )
        assertFalse(fresh.ackedAt(1_700_000_000_000L).unread)
    }

    @Test
    fun anAcknowledgementNeverRegressesOrOverreaches() {
        // A boundary *older* than the row's own idle (the stale list value the
        // ack used to post) leaves that newer transition unread — the dot the
        // user still owes an acknowledgement for.
        val settled = SessionUnread(idle = 1_700_000_009_000L, viewed = 1_700_000_000_000L)
        assertEquals(
            SessionUnread(idle = 1_700_000_009_000L, viewed = 1_700_000_000_000L),
            settled.ackedAt(1_700_000_000_000L),
        )
        assertTrue(settled.ackedAt(1_700_000_000_000L).unread)

        // …and an earlier watermark never comes back down (monotone, like the
        // server's `time_viewed`).
        assertEquals(
            SessionUnread(idle = 1_700_000_000_000L, viewed = 1_700_000_005_000L),
            SessionUnread(idle = 1_700_000_000_000L, viewed = 1_700_000_005_000L).ackedAt(1_700_000_000_000L),
        )
    }
}
