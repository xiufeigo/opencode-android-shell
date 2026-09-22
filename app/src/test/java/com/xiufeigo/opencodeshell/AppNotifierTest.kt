package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.notify.AppNotificationKind
import com.xiufeigo.opencodeshell.notify.AppNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W5-E5, data side: the notification feed's three contracts — gating by the
 * `SettingsStore` notify switches (injected here as the `enabled` predicate),
 * de-bouncing ("same session + same kind within 5 s is one notification") and
 * the bounded feed.
 *
 * Upstream anchor: `_upstream/packages/app/src/shell/notifications/`
 * (`notification.tsx:225-281` appends per session event and calls the platform
 * only when the matching setting is on; `coordinator.ts:46-54` de-duplicates by
 * event id — a `(kind, session)` key is this client's equivalent).
 */
class AppNotifierTest {

    /** Clock the tests drive by hand; no `sleep`, no flakiness. */
    private class Clock(var now: Long = 1_000L) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    private fun notifier(
        clock: Clock,
        enabled: (AppNotificationKind) -> Boolean = { true },
        maxItems: Int = AppNotifier.MAX_ITEMS,
    ) = AppNotifier(enabled = enabled, now = { clock.now }, maxItems = maxItems)

    @Test
    fun raiseAppendsTheNotificationWithItsData() {
        val clock = Clock()
        val notifier = notifier(clock)

        val raised = notifier.raise(
            kind = AppNotificationKind.ERROR,
            sessionID = "ses_1",
            sessionTitle = "Fix the build",
            detail = "Rate limit reached",
            viewed = true,
        )

        assertNotNull(raised)
        assertEquals(listOf(raised), notifier.notifications.value)
        val entry = notifier.notifications.value.single()
        assertEquals(AppNotificationKind.ERROR, entry.kind)
        assertEquals("ses_1", entry.sessionID)
        assertEquals("Fix the build", entry.sessionTitle)
        assertEquals("Rate limit reached", entry.detail)
        assertEquals(clock.now, entry.time)
        assertTrue(entry.viewed)
        assertEquals("the id keys the list and the dismissal", "ERROR:ses_1:${clock.now}", entry.id)

        // A second session/kind appends after the first (oldest first).
        clock.advance(1)
        notifier.raise(AppNotificationKind.AGENT, sessionID = "ses_1")
        assertEquals(2, notifier.size)
        assertEquals(clock.now, notifier.notifications.value.last().time)
    }

    @Test
    fun blankTitlesAndDetailsAreNormalisedToNull() {
        val notifier = notifier(Clock())
        val entry = notifier.raise(
            AppNotificationKind.AGENT,
            sessionID = "ses_1",
            sessionTitle = "   ",
            detail = "",
        )?.let { notifier.notifications.value.single() }!!
        assertNull("a blank title must not render an empty line", entry.sessionTitle)
        assertNull(entry.detail)
    }

    @Test
    fun disabledKindsRaiseNothing() {
        val clock = Clock()
        // The three settings in action: agent off, permissions on, errors off.
        val notifier = notifier(clock, enabled = { it == AppNotificationKind.PERMISSION })

        assertNull(notifier.raise(AppNotificationKind.AGENT, sessionID = "ses_1"))
        assertNull(notifier.raise(AppNotificationKind.ERROR, sessionID = "ses_1"))
        assertTrue("the feed stays empty while every kind is off", notifier.notifications.value.isEmpty())

        assertNotNull(notifier.raise(AppNotificationKind.PERMISSION, sessionID = "ses_1"))
        assertEquals(1, notifier.size)
        // …and a disabled kind never touches the de-bounce state either.
        clock.advance(AppNotifier.DEDUPE_WINDOW_MS)
        assertNull(notifier.raise(AppNotificationKind.AGENT, sessionID = "ses_1"))
        assertTrue(notifier.raise(AppNotificationKind.PERMISSION, sessionID = "ses_1") != null)
    }

    @Test
    fun sameSessionAndKindInsideTheWindowIsDeduped() {
        val clock = Clock()
        val notifier = notifier(clock)

        assertNotNull(notifier.raise(AppNotificationKind.AGENT, sessionID = "ses_1"))
        clock.advance(4_999)
        assertNull("a duplicate frame must not stack up", notifier.raise(AppNotificationKind.AGENT, sessionID = "ses_1"))
        assertEquals(1, notifier.size)

        // The window is exclusive: exactly 5 s later a new turn may notify again.
        clock.advance(1)
        assertNotNull(notifier.raise(AppNotificationKind.AGENT, sessionID = "ses_1"))
        assertEquals(2, notifier.size)
    }

    @Test
    fun otherSessionsAndOtherKindsAreNotDeduped() {
        val clock = Clock()
        val notifier = notifier(clock)

        assertNotNull(notifier.raise(AppNotificationKind.PERMISSION, sessionID = "ses_1"))
        // Another session asking in the same instant is its own notification.
        assertNotNull(notifier.raise(AppNotificationKind.PERMISSION, sessionID = "ses_2"))
        // …and so is another kind for the same session.
        assertNotNull(notifier.raise(AppNotificationKind.QUESTION, sessionID = "ses_1"))
        assertEquals(3, notifier.size)

        // A session-less notification is keyed separately from every session.
        assertNotNull(notifier.raise(AppNotificationKind.ERROR))
        assertNull(notifier.raise(AppNotificationKind.ERROR))
        assertEquals(4, notifier.size)
    }

    @Test
    fun theFeedKeepsOnlyTheNewestEntries() {
        val clock = Clock()
        val notifier = notifier(clock, maxItems = 3)

        repeat(5) { i ->
            clock.advance(1)
            notifier.raise(AppNotificationKind.AGENT, sessionID = "ses_$i")
        }

        assertEquals(3, notifier.size)
        assertEquals(
            listOf("ses_2", "ses_3", "ses_4"),
            notifier.notifications.value.map { it.sessionID },
        )
    }

    @Test
    fun dismissClearAndMarkViewedActOnTheFeed() {
        val clock = Clock()
        val notifier = notifier(clock)
        val first = notifier.raise(AppNotificationKind.AGENT, sessionID = "ses_1")!!
        clock.advance(1)
        notifier.raise(AppNotificationKind.ERROR, sessionID = "ses_2")
        clock.advance(1)
        notifier.raise(AppNotificationKind.QUESTION, sessionID = "ses_2")
        assertEquals(3, notifier.size)

        assertTrue("dismiss removes exactly one entry", notifier.dismiss(first.id))
        assertFalse("dismissing it twice is a no-op", notifier.dismiss(first.id))
        assertFalse(notifier.dismiss("nope"))
        assertEquals(listOf("ses_2", "ses_2"), notifier.notifications.value.map { it.sessionID })

        // `markViewed` targets one session only (the user opened ses_2).
        assertTrue(notifier.markViewed("ses_2"))
        assertTrue(notifier.notifications.value.all { it.viewed })
        clock.advance(1)
        notifier.raise(AppNotificationKind.QUESTION, sessionID = "ses_3")
        assertTrue(notifier.markViewed("ses_3"))
        assertFalse("nothing left to mark", notifier.markViewed("ses_3"))

        notifier.clear()
        assertTrue(notifier.notifications.value.isEmpty())
        assertEquals(0, notifier.size)
        // A cleared feed can notify again for the same session once the window passed.
        clock.advance(AppNotifier.DEDUPE_WINDOW_MS)
        assertNotNull(notifier.raise(AppNotificationKind.ERROR, sessionID = "ses_2"))
        assertEquals(1, notifier.size)
    }

    @Test
    fun aSessionLessFeedEntryStillCarriesAnIdAndTime() {
        val clock = Clock()
        val notifier = notifier(clock)
        val entry = notifier.raise(AppNotificationKind.ERROR, detail = "boom")!!
        assertEquals(AppNotificationKind.ERROR, entry.kind)
        assertNull(entry.sessionID)
        assertNull(entry.sessionTitle)
        assertEquals("boom", entry.detail)
        assertFalse(entry.viewed)
        assertEquals(entry, notifier.notifications.value.single())
    }
}
