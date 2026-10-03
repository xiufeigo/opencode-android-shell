package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.notify.AppNotification
import com.xiufeigo.opencodeshell.notify.AppNotificationKind
import com.xiufeigo.opencodeshell.notify.AppNotifier
import com.xiufeigo.opencodeshell.notify.NotificationDelivery
import com.xiufeigo.opencodeshell.notify.NotificationPersistence
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

    /** In-memory stand-in for `NotificationPrefs` (B-3). */
    private class FakePersistence(initial: List<AppNotification> = emptyList()) : NotificationPersistence {
        var stored: List<AppNotification> = initial
        var saves = 0

        override fun load(): List<AppNotification> = stored

        override fun save(notifications: List<AppNotification>) {
            stored = notifications
            saves++
        }
    }

    private fun notifier(
        clock: Clock,
        enabled: (AppNotificationKind) -> Boolean = { true },
        maxItems: Int = AppNotifier.MAX_ITEMS,
        persistence: NotificationPersistence? = null,
        delivery: NotificationDelivery? = null,
    ) = AppNotifier(
        enabled = enabled,
        now = { clock.now },
        maxItems = maxItems,
        persistence = persistence,
        delivery = delivery,
    )

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
    fun acknowledgeDropsTheEntryAndMarksItsSessionSeen() {
        val clock = Clock()
        val notifier = notifier(clock)
        // v47 tE: one settled turn per entry — the feed behind the stuck banner.
        notifier.raise(AppNotificationKind.AGENT, sessionID = "ses_2")
        clock.advance(AppNotifier.DEDUPE_WINDOW_MS)
        notifier.raise(AppNotificationKind.AGENT, sessionID = "ses_1")
        clock.advance(AppNotifier.DEDUPE_WINDOW_MS)
        val second = notifier.raise(AppNotificationKind.AGENT, sessionID = "ses_1")!!
        assertEquals(3, notifier.size)

        // 「知道了」 on the shown (newest unviewed) entry: the entry is gone
        // and its session's queued twin stops counting as unseen, so the
        // banner's `lastOrNull { !viewed }` selection hides it at once —
        // a bare dismiss would have revealed the identical twin instead.
        val shown = notifier.notifications.value.lastOrNull { !it.viewed }!!
        assertEquals(second.id, shown.id)
        assertTrue(notifier.acknowledge(shown.id))
        assertEquals(2, notifier.size)
        assertTrue(notifier.notifications.value.none { it.sessionID == "ses_1" && !it.viewed })
        // The other session's news is still unseen — the banner moves to it.
        assertEquals("ses_2", notifier.notifications.value.lastOrNull { !it.viewed }?.sessionID)

        assertFalse("unknown ids stay a no-op", notifier.acknowledge("nope"))
        assertFalse("acknowledging twice is a no-op", notifier.acknowledge(shown.id))
    }

    @Test
    fun acknowledgeOfASessionLessEntryJustDropsIt() {
        val clock = Clock()
        val notifier = notifier(clock)
        val entry = notifier.raise(AppNotificationKind.ERROR, detail = "boom")!!
        assertTrue(notifier.acknowledge(entry.id))
        assertTrue(notifier.notifications.value.isEmpty())
    }

    @Test
    fun markKindViewedClosesOnlyThatKindsRows() {
        val clock = Clock()
        val notifier = notifier(clock)
        notifier.raise(AppNotificationKind.PERMISSION, sessionID = "ses_1")
        clock.advance(1)
        notifier.raise(AppNotificationKind.QUESTION, sessionID = "ses_1")
        clock.advance(1)
        notifier.raise(AppNotificationKind.PERMISSION, sessionID = "ses_2")

        // The auto-approve path closes the permission row it answered — and
        // nothing else: the form row still waits for the user, and another
        // session's ask is not this session's business.
        assertTrue(notifier.markKindViewed("ses_1", AppNotificationKind.PERMISSION))
        val byKey = notifier.notifications.value.associateBy { it.kind to it.sessionID }
        assertTrue(byKey.getValue(AppNotificationKind.PERMISSION to "ses_1").viewed)
        assertFalse(byKey.getValue(AppNotificationKind.QUESTION to "ses_1").viewed)
        assertFalse(byKey.getValue(AppNotificationKind.PERMISSION to "ses_2").viewed)
        assertFalse("nothing left to mark", notifier.markKindViewed("ses_1", AppNotificationKind.PERMISSION))

        // `markViewed` still covers every kind of one session (the user opened it).
        assertTrue(notifier.markViewed("ses_1"))
        assertTrue(notifier.notifications.value.filter { it.sessionID == "ses_1" }.all { it.viewed })
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

    // ------------------------------------------------------ B-3: persistence

    @Test
    fun theFeedAndItsUnreadWatermarkSurviveARestart() {
        val clock = Clock()
        val persistence = FakePersistence()
        val first = notifier(clock, persistence = persistence)
        first.raise(AppNotificationKind.AGENT, sessionID = "ses_1", sessionTitle = "Fix the build")
        clock.advance(1)
        first.raise(AppNotificationKind.ERROR, sessionID = "ses_2", detail = "boom")
        // The user read ses_1 before the process died.
        assertTrue(first.markViewed("ses_1"))
        val persisted = persistence.stored
        assertEquals(2, persisted.size)
        assertTrue("every mutation is written through", persistence.saves >= 3)

        // A fresh notifier over the same blob (the next cold start).
        val restarted = notifier(clock, persistence = FakePersistence(persisted))
        assertEquals(persisted, restarted.notifications.value)
        assertEquals(1, restarted.notifications.value.count { !it.viewed })
        assertEquals("ses_2", restarted.notifications.value.last { !it.viewed }.sessionID)
        assertEquals("Fix the build", restarted.notifications.value.first().sessionTitle)
    }

    @Test
    fun theFeedIsCappedAtTheUpstreamMaximum() {
        val clock = Clock()
        val persistence = FakePersistence()
        val notifier = notifier(clock, persistence = persistence)

        // One distinct session per raise, so nothing is de-bounced away.
        repeat(AppNotifier.MAX_ITEMS + 5) { index ->
            clock.advance(1)
            notifier.raise(AppNotificationKind.AGENT, sessionID = "ses_$index")
        }

        assertEquals(500, AppNotifier.MAX_ITEMS)
        assertEquals(500, notifier.size)
        assertEquals("ses_5", notifier.notifications.value.first().sessionID)
        assertEquals("the disk copy is the same list", 500, persistence.stored.size)
    }

    @Test
    fun entriesOlderThanTheTtlArePrunedOnRestore() {
        val clock = Clock(now = 1_700_000_000_000L)
        val persistence = FakePersistence()
        val notifier = notifier(clock, persistence = persistence)
        notifier.raise(AppNotificationKind.AGENT, sessionID = "ses_old")
        clock.advance(1)
        notifier.raise(AppNotificationKind.AGENT, sessionID = "ses_new")
        assertEquals(2, persistence.stored.size)

        // 30 days later the older one is history (upstream `NOTIFICATION_TTL_MS`).
        clock.advance(AppNotifier.TTL_MS)
        val restarted = notifier(clock, persistence = persistence)
        assertEquals(listOf("ses_new"), restarted.notifications.value.map { it.sessionID })
        // The prune is written back, so the file cannot carry entries the UI drops.
        assertEquals(listOf("ses_new"), persistence.stored.map { it.sessionID })
    }

    @Test
    fun aPrunedRestoreStillKeepsTheBoundaryEntry() {
        val clock = Clock(now = 1_700_000_000_000L)
        val persistence = FakePersistence()
        notifier(clock, persistence = persistence).raise(AppNotificationKind.AGENT, sessionID = "ses_edge")

        // Exactly at the TTL the entry is still inside it (`>=` cutoff, upstream
        // `notification.tsx:65`).
        clock.advance(AppNotifier.TTL_MS)
        assertEquals(1, notifier(clock, persistence = persistence).size)
    }

    @Test
    fun withoutPersistenceTheFeedStaysInMemory() {
        val clock = Clock()
        val notifier = notifier(clock)
        notifier.raise(AppNotificationKind.AGENT, sessionID = "ses_1")
        // A second, unwired notifier starts empty — the pre-B-3 behaviour the
        // previews and the legacy WebView shell still get.
        assertEquals(0, notifier(clock).size)
        assertEquals(1, notifier.size)
    }

    // --------------------------------------------------- B-2: delivery hook

    @Test
    fun onlyAcceptedRaisesReachTheSystemHalf() {
        val clock = Clock()
        val delivered = mutableListOf<AppNotification>()
        val notifier = notifier(
            clock,
            enabled = { it == AppNotificationKind.AGENT },
            delivery = { delivered += it },
        )

        assertNull("a gated kind is never delivered", notifier.raise(AppNotificationKind.ERROR, sessionID = "ses_1"))
        val raised = notifier.raise(AppNotificationKind.AGENT, sessionID = "ses_1")
        assertEquals(listOf(raised), delivered)

        // A de-bounced duplicate is not a second notification.
        clock.advance(1)
        assertNull(notifier.raise(AppNotificationKind.AGENT, sessionID = "ses_1"))
        assertEquals(1, delivered.size)

        // …and the next turn, outside the window, is delivered again.
        clock.advance(AppNotifier.DEDUPE_WINDOW_MS)
        assertNotNull(notifier.raise(AppNotificationKind.AGENT, sessionID = "ses_1"))
        assertEquals(2, delivered.size)
    }
}
