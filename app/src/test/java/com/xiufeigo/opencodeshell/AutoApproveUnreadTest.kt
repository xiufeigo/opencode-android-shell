package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.notify.AppNotificationKind
import com.xiufeigo.opencodeshell.notify.AppNotifier
import com.xiufeigo.opencodeshell.ui.nav.SessionTabBadge
import com.xiufeigo.opencodeshell.ui.nav.SessionTabIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v17 `research/v17_verify/switch_perm/`: with auto-accept **on**, a permission
 * the app answered by itself still lit the session tab's unread dot, and the dot
 * never cleared (60 s+, a Home round-trip, a fresh app only). The control round
 * in the same setting — a turn with no tool — stayed at 0 dots.
 *
 * The dot has three sources ([SessionTabIndicator.badge]) and each one is pinned
 * here on its own, through the real collaborators (`AppNotifier`, `AutoApprover`,
 * `SessionTabIndicator`, `SessionUnread`) driven in the order `ChatViewModel`
 * drives them:
 *
 * 1. `attention` — `hasPermission`, already gated by the setting
 *    (`project-avatar-state.ts:27`);
 * 2. `unseenNotifications` — the in-app feed's [AppNotificationKind.PERMISSION]
 *    rows, which upstream's ledger does not have at all, and which the setting
 *    had not reached: an entry raised while the user was on another route
 *    (`viewed = false`) stayed unseen for the process, because the auto-approver
 *    only answered the server. This is the v17 dot.
 * 3. `serverUnread` — the row's own `time.idle > time.viewed`. Not
 *    permission-derived (the projector advances `time.idle` on
 *    `session.execution.*` only), so the setting does not gate it; what it needs
 *    is an acknowledgement that covers the transition the viewer saw
 *    ([SessionUnread.ackedAt]).
 *
 * The **off** setting keeps the whole ring the device verified: the dock lights
 * the dot through `attention` *and* through the feed, and answering it clears
 * both.
 */
class AutoApproveUnreadTest {

    private companion object {
        const val SESSION = "ses_probe"
        const val OTHER = "ses_elsewhere"
    }

    /**
     * The three dot inputs `ChatViewModel` publishes, mutated the way its own
     * paths mutate them. `now` is injected so the notifier's de-bounce window
     * never depends on wall time.
     */
    private class Harness(
        var autoApprove: Boolean,
        /** The session the user is looking at (`visibleSessionID`); null = Home. */
        var visible: String? = null,
    ) {
        private var clock = 1_000L

        val notifier = AppNotifier(now = { clock })
        private val approver = AutoApprover(enabled = { autoApprove })

        /** `ChatState.pendingPermissions` — the dock's own list, keyed by session. */
        var pending = listOf<Pair<String, String>>()
        /** `ChatViewModel.sessionUnread` for [SESSION]. */
        var watermark = SessionUnread()

        fun advance(ms: Long) {
            clock += ms
        }

        fun hasPending(sessionID: String = SESSION): Boolean = pending.any { it.first == sessionID }

        /** `ChatViewModel.raise` (`notifyFor`): the ask raises its ledger row. */
        fun raiseAsk(id: String, sessionID: String = SESSION) {
            // `raise(PERMISSION, …, viewed = sessionID == visibleSessionID)`.
            notifier.raise(
                kind = AppNotificationKind.PERMISSION,
                sessionID = sessionID,
                detail = "bash",
                viewed = sessionID == visible,
            )
            pending = pending + (sessionID to id)
        }

        /** `ChatViewModel.autoApprove`: the reply, and the ledger row closes with it. */
        fun autoApproveReply(id: String, sessionID: String = SESSION) {
            if (!approver.tryMark(id)) return
            // The fix: answering without the user also closes the entry the ask
            // raised. The server's own `permission.replied` empties the dock.
            notifier.markKindViewed(sessionID, AppNotificationKind.PERMISSION)
            pending = pending - (sessionID to id)
        }

        /** The two steps `ChatViewModel` runs for one frame, in order. */
        fun askPermission(id: String, sessionID: String = SESSION) {
            raiseAsk(id, sessionID)
            autoApproveReply(id, sessionID)
        }

        /** The user's own answer (`replyPermission`): the dock empties, nothing auto. */
        fun allowPermission(id: String, sessionID: String = SESSION) {
            pending = pending - (sessionID to id)
            notifier.markViewed(sessionID)
        }

        /** A turn settled with no tool: upstream's `turn-complete` row. */
        fun turnCompleted(sessionID: String = SESSION) {
            notifier.raise(
                kind = AppNotificationKind.AGENT,
                sessionID = sessionID,
                viewed = sessionID == visible,
            )
        }

        /** `ackSessionViewed()`: the feed of that session is seen, the row acked. */
        fun viewerObservedTheIdle() {
            notifier.markViewed(SESSION)
            watermark = watermark.ackedAt(watermark.idle ?: return)
        }

        fun badge(sessionID: String = SESSION): SessionTabBadge = SessionTabIndicator.badge(
            // Only the tracked session's dock exists (`ChatViewModel.pendingPermissions`
            // is that session's list).
            hasPermission = hasPending(sessionID),
            hasForm = false,
            autoApprove = autoApprove,
            unseenNotifications = SessionTabIndicator.unseenCount(
                notifier.notifications.value,
                sessionID,
                autoApprove = autoApprove,
            ),
            running = false,
            serverUnread = watermark.unread,
        )

        /** The feed's count for [sessionID] **without** the setting's gate. */
        fun unseenWithoutTheGate(sessionID: String = SESSION): Int =
            SessionTabIndicator.unseenCount(notifier.notifications.value, sessionID, autoApprove = false)

        fun unseenFor(sessionID: String = SESSION): Int =
            SessionTabIndicator.unseenCount(notifier.notifications.value, sessionID, autoApprove)
    }

    // ------------------------------------------------------- auto-approve ON

    @Test
    fun anAutoApprovedAskLeavesNoDotOnAnyOfTheThreeSources() {
        // The ask lands while the user is elsewhere (Home): upstream's
        // `viewedInCurrentSession` is false, so the entry is raised unseen.
        val h = Harness(autoApprove = true, visible = null)
        h.raiseAsk("perm_1")

        // Without the fix this is where the v17 dot came from: the feed's
        // permission row counted (the gate is what the setting adds) and nothing
        // ever closed it.
        assertEquals(
            "the entry is raised unseen (the condition the fix has to survive)",
            1,
            h.unseenWithoutTheGate(),
        )
        assertEquals("the setting alone keeps it off the dot", 0, h.unseenFor())

        // The auto-approver answers without the user, so it closes the row too.
        h.autoApproveReply("perm_1")
        assertTrue("the auto-approver closed the dock", !h.hasPending())
        assertEquals(0, h.unseenWithoutTheGate())
        assertEquals(SessionTabBadge.NONE, h.badge())

        // It stays closed: nothing re-raises it, and a later read (Home
        // re-entry, drawer open) recomputes the same three sources.
        h.advance(AppNotifier.DEDUPE_WINDOW_MS * 2)
        assertEquals(0, h.unseenFor())
        assertEquals(SessionTabBadge.NONE, h.badge())
    }

    @Test
    fun theAutoApprovedAskIsInvisibleEvenWhenItsEntryIsTheOnlyUnreadRow() {
        // Belt and braces, in the order that used to slip through: the ledger
        // gate alone (setting on) keeps the count at 0 even if a mark were
        // missed — the invariant does not depend on which of the two lands first.
        val h = Harness(autoApprove = true, visible = null)
        h.notifier.raise(AppNotificationKind.PERMISSION, sessionID = SESSION, viewed = false)
        assertEquals(0, h.unseenFor())
        assertEquals(SessionTabBadge.NONE, h.badge())
    }

    @Test
    fun aPermissionAskedWhileTheUserWatchesNeverLightsAnyway() {
        // The other half of the same setting: on screen, `viewed` is true at
        // raise time, so the entry never counted — the auto-approved ask is
        // invisible from both directions.
        val h = Harness(autoApprove = true, visible = SESSION)
        h.askPermission("perm_1")
        assertEquals(0, h.unseenFor())
        assertEquals(SessionTabBadge.NONE, h.badge())
    }

    // ------------------------------------------------------ auto-approve OFF

    @Test
    fun theManualRingStillLightsAndClears() {
        // The device-verified ring (screens 11-14): dock → dot → Allow → dot off.
        // On screen the entry is raised `viewed = true`, so the dot the dock
        // shows is `attention` — exactly upstream's `hasPermissions`.
        val h = Harness(autoApprove = false, visible = SESSION)
        h.askPermission("perm_1")

        assertTrue("the ask stays pending with the setting off", h.hasPending())
        assertEquals("the entry is raised seen while the user is on it", 0, h.unseenFor())
        assertTrue("the pending ask is what lights the tab", h.badge().unread)

        // Allow: the dock empties and the feed of that session is seen.
        h.allowPermission("perm_1")
        assertFalse("the dock emptied on the user's answer", h.hasPending())
        assertEquals(SessionTabBadge.NONE, h.badge())
    }

    @Test
    fun answeringFromAnotherRouteStillClearsTheFeedDot() {
        // The same ring answered from Home (upstream's `viewedInCurrentSession`
        // is false there): now the feed's permission row is what lights the dot
        // — the setting off means it counts — and the answer closes it.
        val h = Harness(autoApprove = false, visible = null)
        h.askPermission("perm_1")
        assertEquals(1, h.unseenFor())
        assertTrue(h.badge().unread)

        h.allowPermission("perm_1")
        assertEquals(0, h.unseenFor())
        assertEquals(SessionTabBadge.NONE, h.badge())
    }

    // ------------------------------------------------------ the control round

    @Test
    fun aRoundWithoutToolsNeverLightsADot() {
        // The control round (62 s, 0 dots): no permission ask, so no
        // `attention` and no permission row — the settled turn raises upstream's
        // own `turn-complete` kind, which on screen is raised seen.
        val h = Harness(autoApprove = true, visible = SESSION)
        h.turnCompleted()
        repeat(6) {
            h.advance(10_000)
            assertEquals(0, h.unseenFor())
            assertFalse("62 s of ticks with no permission ask", h.badge().unread)
        }
    }

    @Test
    fun aSettledTurnIsAckedAndTheWatermarkDotClears() {
        // Source 3 pinned on its own: the settled transition is unread until the
        // viewer acknowledges *that* boundary (`ackedAt`), which is what makes
        // the dot clear instead of re-lighting on the next read.
        val h = Harness(autoApprove = true, visible = SESSION)
        h.turnCompleted()
        h.watermark = SessionUnread(idle = 1_700_000_009_000L, viewed = 1_700_000_000_000L)
        assertTrue("a settled transition nobody acked is unread", h.badge().unread)

        h.viewerObservedTheIdle()
        assertFalse(h.badge().unread)
        // …and a read that still carries the server's older watermark does not
        // un-read it (`withAcknowledged`'s floor).
        assertFalse(
            SessionUnread(idle = 1_700_000_009_000L, viewed = 1_700_000_000_000L)
                .withAcknowledged(h.watermark)
                .unread,
        )
    }

    @Test
    fun anotherSessionsPermissionDoesNotLightThisTab() {
        val h = Harness(autoApprove = false, visible = SESSION)
        h.askPermission("perm_2", sessionID = OTHER)
        assertEquals("the row belongs to its own session", 0, h.unseenFor())
        assertEquals(1, h.unseenFor(OTHER))
        assertEquals(SessionTabBadge.NONE, h.badge())
    }
}
