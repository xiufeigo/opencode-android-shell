package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.notify.AppNotification
import com.xiufeigo.opencodeshell.notify.AppNotificationKind
import com.xiufeigo.opencodeshell.ui.nav.SessionDotSources
import com.xiufeigo.opencodeshell.ui.nav.SessionTabBadge
import com.xiufeigo.opencodeshell.ui.nav.SessionTabIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-2: the session tab's `unread` / `loading` derivation
 * ([SessionTabIndicator], upstream `shell/layout/project-avatar-state.ts:26-49`).
 *
 * Pure, so the two upstream rules are pinned without a device:
 * - `unread = needsAttention || unseenCount > 0 || serverUnread` — the last
 *   term is the *other* upstream client's ledger ([SessionUnread], the TUI's
 *   `time.idle > time.viewed`), which is what still answers for a turn that
 *   settled while this shell had no stream open;
 * - `loading = !needsAttention && running` — a session waiting on the user is
 *   never "busy" (an unseen notification does not count as attention here,
 *   upstream's own reading);
 * - `needsAttention = (!autoApprove && hasPermission) || hasForm` — the setting
 *   gates the permission half only. It also gates the *permission* rows of the
 *   in-app feed, because this shell's ledger carries a kind upstream's does not
 *   (see [unseenCount] and `autoApproveKeepsPermissionEntriesOutOfTheLedgerCount`).
 */
class SessionTabIndicatorTest {

    private fun notification(
        sessionID: String?,
        viewed: Boolean,
        kind: AppNotificationKind = AppNotificationKind.AGENT,
    ) = AppNotification(
        id = "id-$sessionID-$viewed-$kind",
        kind = kind,
        sessionID = sessionID,
        sessionTitle = null,
        detail = null,
        time = 0L,
        viewed = viewed,
    )

    @Test
    fun aPendingPermissionMarksTheTabUnread() {
        val badge = SessionTabIndicator.badge(
            hasPermission = true,
            hasForm = false,
            autoApprove = false,
            unseenNotifications = 0,
            running = false,
        )
        assertTrue(badge.unread)
        assertFalse(badge.loading)
    }

    @Test
    fun autoApproveSilencesThePermissionHalfButNotForms() {
        val permission = SessionTabIndicator.badge(
            hasPermission = true,
            hasForm = false,
            autoApprove = true,
            unseenNotifications = 0,
            running = false,
        )
        assertFalse(permission.unread)

        val form = SessionTabIndicator.badge(
            hasPermission = false,
            hasForm = true,
            autoApprove = true,
            unseenNotifications = 0,
            running = false,
        )
        assertTrue(form.unread)
    }

    @Test
    fun anUnseenNotificationMarksTheTabUnread() {
        val badge = SessionTabIndicator.badge(
            hasPermission = false,
            hasForm = false,
            autoApprove = false,
            unseenNotifications = 1,
            running = false,
        )
        assertTrue(badge.unread)
    }

    @Test
    fun theServerWatermarkMarksTheTabUnread() {
        // A-2's second source: a turn that settled while this shell had no stream
        // open is unread purely off the session row's watermark
        // (`SessionUnread`), which is the state v15 hit — the feed had nothing
        // to offer because no frame was ever delivered.
        val badge = SessionTabIndicator.badge(
            hasPermission = false,
            hasForm = false,
            autoApprove = false,
            unseenNotifications = 0,
            running = false,
            serverUnread = true,
        )
        assertEquals(SessionTabBadge(unread = true, loading = false), badge)
    }

    @Test
    fun aRunningRowKeepsItsMarqueeEvenWhenUnread() {
        // Upstream's `loading = !needsAttention && running` counts *attention*
        // only, and `SessionTabAvatarView` renders `loading ? marquee : avatar`
        // — so a run in progress wins over an unseen notification. The dot
        // reappears the moment the run stops, which is the same precedence the
        // shell's `SessionTabBadgeSlot` renders.
        val badge = SessionTabIndicator.badge(
            hasPermission = false,
            hasForm = false,
            autoApprove = false,
            unseenNotifications = 0,
            running = true,
            serverUnread = true,
        )
        assertTrue(badge.unread)
        assertTrue(badge.loading)
    }

    @Test
    fun aHostWithoutAServerWatermarkRendersWhatItAlwaysDid() {
        // `serverUnread` defaults to false: the legacy call sites (previews, the
        // notification-only hosts) keep the derivation they had.
        assertFalse(
            SessionTabIndicator.badge(
                hasPermission = false,
                hasForm = false,
                autoApprove = false,
                unseenNotifications = 0,
                running = false,
            ).unread,
        )
    }

    @Test
    fun aRunningSessionWithoutAttentionLoads() {
        val badge = SessionTabIndicator.badge(
            hasPermission = false,
            hasForm = false,
            autoApprove = false,
            unseenNotifications = 0,
            running = true,
        )
        assertEquals(SessionTabBadge(unread = false, loading = true), badge)
    }

    @Test
    fun attentionWinsOverRunning() {
        val badge = SessionTabIndicator.badge(
            hasPermission = false,
            hasForm = true,
            autoApprove = false,
            unseenNotifications = 0,
            running = true,
        )
        assertTrue(badge.unread)
        assertFalse(badge.loading)
    }

    @Test
    fun anIdleSessionWithNothingPendingIsPlain() {
        assertEquals(
            SessionTabBadge.NONE,
            SessionTabIndicator.badge(
                hasPermission = false,
                hasForm = false,
                autoApprove = false,
                unseenNotifications = 0,
                running = false,
            ),
        )
    }

    @Test
    fun unseenCountsOnlyUnviewedRowsOfThatSession() {
        val feed = listOf(
            notification("ses_1", viewed = false),
            notification("ses_1", viewed = true),
            notification("ses_1", viewed = false),
            notification("ses_2", viewed = false),
            notification(null, viewed = false),
        )
        assertEquals(2, SessionTabIndicator.unseenCount(feed, "ses_1"))
        assertEquals(1, SessionTabIndicator.unseenCount(feed, "ses_2"))
        assertEquals(0, SessionTabIndicator.unseenCount(feed, "ses_3"))
        // A notification with no session id belongs to no tab.
        assertEquals(0, SessionTabIndicator.unseenCount(feed, ""))
    }

    // ------------------------------------------------- the auto-approve gate

    /**
     * Source 2 (`unseenNotifications`) is the one the setting had not reached:
     * this shell's feed carries [AppNotificationKind.PERMISSION] rows that
     * upstream's ledger never holds, so without the gate an auto-approved ask
     * whose entry was raised `viewed = false` (the user was not on that session's
     * route) counted as unread for the rest of the process — the v17
     * `switch_perm` dot.
     */
    @Test
    fun autoApproveKeepsPermissionEntriesOutOfTheLedgerCount() {
        val permission = listOf(notification("ses_1", viewed = false, kind = AppNotificationKind.PERMISSION))

        // Setting on: the permission half contributes nothing, exactly as
        // upstream's `hasPermissions = !autoApprove && …`.
        assertEquals(0, SessionTabIndicator.unseenCount(permission, "ses_1", autoApprove = true))
        assertFalse(
            SessionTabIndicator.badge(
                hasPermission = false,
                hasForm = false,
                autoApprove = true,
                unseenNotifications = SessionTabIndicator.unseenCount(permission, "ses_1", autoApprove = true),
                running = false,
            ).unread,
        )

        // Setting off: the entry counts, so the dock ring's dot still lights.
        assertEquals(1, SessionTabIndicator.unseenCount(permission, "ses_1", autoApprove = false))
        assertTrue(
            SessionTabIndicator.badge(
                hasPermission = false,
                hasForm = false,
                autoApprove = false,
                unseenNotifications = 1,
                running = false,
            ).unread,
        )
    }

    @Test
    fun autoApproveOnlyGatesThePermissionKind() {
        val feed = listOf(
            notification("ses_1", viewed = false, kind = AppNotificationKind.PERMISSION),
            notification("ses_1", viewed = false, kind = AppNotificationKind.QUESTION),
            notification("ses_1", viewed = false, kind = AppNotificationKind.AGENT),
            notification("ses_1", viewed = false, kind = AppNotificationKind.ERROR),
        )
        // Three of the four kinds are upstream's own ledger (`turn-complete` |
        // `error`) or its ungated form half — only the permission row drops out.
        assertEquals(3, SessionTabIndicator.unseenCount(feed, "ses_1", autoApprove = true))
        assertEquals(4, SessionTabIndicator.unseenCount(feed, "ses_1", autoApprove = false))
        // The parameter is optional: a host that passes nothing keeps the old
        // derivation.
        assertEquals(4, SessionTabIndicator.unseenCount(feed, "ses_1"))
    }

    @Test
    fun theServerWatermarkIsNotGatedByAutoApprove() {
        // Source 3 is not permission-derived at all: the server advances
        // `time.idle` on `session.execution.*` only
        // (`core/src/session/projector.ts:407-427`), never on a permission ask,
        // and upstream's `unread = needsAttention || unseenCount > 0` has no
        // auto-approve gate on it either. So a settled turn still lights.
        assertTrue(
            SessionTabIndicator.badge(
                hasPermission = false,
                hasForm = false,
                autoApprove = true,
                unseenNotifications = 0,
                running = false,
                serverUnread = true,
            ).unread,
        )
    }

    // ------------------------------------------- the drawer row's dot (`unread`)

    /**
     * [SessionTabIndicator.unread] is the drawer row's dot. Its **first** term is
     * the one the row was missing: a pending permission/form ask on the session
     * the user is looking at. Neither of the other two can report that ask — the
     * feed entry for an ask raised while its session *is* the visible route is
     * `viewed = true`, and the server watermark only moves on
     * `session.execution.*`, never on an ask — so the strip lit and the row did
     * not.
     */
    @Test
    fun aPendingAskLightsTheDrawerRow() {
        assertTrue(
            SessionTabIndicator.unread(
                hasPermission = true,
                hasForm = false,
                autoApprove = false,
                unseenNotifications = 0,
            ),
        )
        assertTrue(
            SessionTabIndicator.unread(
                hasPermission = false,
                hasForm = true,
                autoApprove = false,
                unseenNotifications = 0,
            ),
        )
        // The setting gates the permission half only — the form still waits.
        assertFalse(
            SessionTabIndicator.unread(
                hasPermission = true,
                hasForm = false,
                autoApprove = true,
                unseenNotifications = 0,
            ),
        )
        assertTrue(
            SessionTabIndicator.unread(
                hasPermission = false,
                hasForm = true,
                autoApprove = true,
                unseenNotifications = 0,
            ),
        )
    }

    @Test
    fun theDrawerRowReadsTheStripsOtherTwoSources() {
        assertTrue(
            SessionTabIndicator.unread(
                hasPermission = false,
                hasForm = false,
                autoApprove = false,
                unseenNotifications = 2,
            ),
        )
        assertTrue(
            SessionTabIndicator.unread(
                hasPermission = false,
                hasForm = false,
                autoApprove = false,
                unseenNotifications = 0,
                serverUnread = true,
            ),
        )
        // Nothing pending, nothing unseen, nothing unsettled: no dot.
        assertFalse(
            SessionTabIndicator.unread(
                hasPermission = false,
                hasForm = false,
                autoApprove = false,
                unseenNotifications = 0,
            ),
        )
    }

    @Test
    fun theDrawerRowAndTheStripCannotDisagreeAboutTheDot() {
        // Every combination of the row's five inputs, against the strip's own
        // badge at both run statuses: one derivation, so the drawer and the strip
        // can never disagree about which session is waiting.
        for (permission in listOf(false, true)) {
            for (form in listOf(false, true)) {
                for (autoApprove in listOf(false, true)) {
                    for (unseen in listOf(0, 1)) {
                        for (watermark in listOf(false, true)) {
                            for (running in listOf(false, true)) {
                                assertEquals(
                                    SessionTabIndicator.badge(
                                        hasPermission = permission,
                                        hasForm = form,
                                        autoApprove = autoApprove,
                                        unseenNotifications = unseen,
                                        running = running,
                                        serverUnread = watermark,
                                    ).unread,
                                    SessionTabIndicator.unread(
                                        hasPermission = permission,
                                        hasForm = form,
                                        autoApprove = autoApprove,
                                        unseenNotifications = unseen,
                                        serverUnread = watermark,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------- the three surfaces' shared sources (v28)

    private companion object {
        const val TRACKED = "ses_tracked"
        const val FOREIGN = "ses_foreign"
    }

    /**
     * Scenario 1 of the v28 `t2_envtweak` run (`research/v28_verify/t2_envtweak/`):
     * the app sits on the session whose permission dock is up. The tracked
     * session's own dock list is the source, and every surface has to answer it —
     * including Home's session rows, which had no indicator slot at all.
     */
    @Test
    fun theTrackedSessionsPendingAskLightsEverySurface() {
        val sources = SessionDotSources(
            trackedSessionID = TRACKED,
            trackedPermission = true,
            trackedForm = false,
        )
        assertTrue("the drawer row", sources.unread(TRACKED))
        assertTrue("the tab strip", sources.badge(TRACKED).unread)
        assertFalse("and nothing else is lit by it", sources.unread(FOREIGN))
        // `loading` counts attention only (upstream `!needsAttention && running`),
        // so the dock's session is never drawn as merely busy.
        assertFalse(sources.badge(TRACKED).loading)
        assertEquals("the row and the strip answer one derivation", sources.badge(TRACKED).unread, sources.unread(TRACKED))
    }

    /**
     * Scenario 2: the ask belongs to a session the app is **not** observing (the
     * device run created it through `POST /api/session` and hung the request from
     * another session). `ChatStore.pendingPermissions` cannot report it — every
     * ask event is gated by `tracksSession` — so the location-wide ledger
     * (`ChatViewModel.sessionAsks`, upstream's `session.permission.list`) is the
     * only thing that can light that session's row.
     */
    @Test
    fun aForeignSessionsPendingAskLightsItsOwnRow() {
        val sources = SessionDotSources(
            trackedSessionID = TRACKED,
            trackedPermission = false,
            trackedForm = false,
            asks = mapOf(FOREIGN to SessionAsks(permission = true)),
        )
        assertTrue("the foreign session's row / tab", sources.unread(FOREIGN))
        assertTrue(sources.badge(FOREIGN).unread)
        assertTrue("…from the ask half, not the feed", sources.hasPermission(FOREIGN))
        assertFalse("the session on screen stays plain", sources.unread(TRACKED))
        // Nothing about the *tracked* session's own facts leaks onto the row: the
        // ledger answers per session, like upstream's lists.
        assertFalse(sources.hasPermission(TRACKED))
    }

    @Test
    fun aForeignSessionsPendingFormLightsEvenWithAutoApproveOn() {
        // The setting gates the permission half only (`project-avatar-state.ts:27`
        // nests it inside `hasPermissions`); a form still waits for the user, on
        // any session.
        val sources = SessionDotSources(
            autoApprove = true,
            asks = mapOf(
                FOREIGN to SessionAsks(permission = true, form = true),
            ),
        )
        assertTrue(sources.hasForm(FOREIGN))
        assertTrue("the form half survives the setting", sources.unread(FOREIGN))

        val permissionOnly = SessionDotSources(
            autoApprove = true,
            asks = mapOf(FOREIGN to SessionAsks(permission = true)),
        )
        assertFalse("an auto-approved ask stays dark", permissionOnly.unread(FOREIGN))
    }

    @Test
    fun theLedgerAndTheTrackedDockListAreTheSameFact() {
        // Belt and braces: the ledger is fed by the global frame path, the dock
        // list by the tracked session's own snapshot reads. A dot that is up in
        // one and not the other is exactly the drift this object prevents.
        val viaLedger = SessionDotSources(asks = mapOf(TRACKED to SessionAsks(permission = true)))
        val viaDock = SessionDotSources(trackedSessionID = TRACKED, trackedPermission = true)
        assertEquals(viaLedger.badge(TRACKED), viaDock.badge(TRACKED))
        assertTrue(viaLedger.unread(TRACKED) && viaDock.unread(TRACKED))
    }

    @Test
    fun theSurfacesKeepTheFeedsAndTheWatermarksRules() {
        // The two sources the surfaces had before are untouched: an unseen feed
        // row lights any session, and the server watermark does not care whether
        // the session is the tracked one either.
        val sources = SessionDotSources(
            trackedSessionID = TRACKED,
            notifications = listOf(
                notification(FOREIGN, viewed = false, kind = AppNotificationKind.PERMISSION),
            ),
            unread = mapOf(FOREIGN to SessionUnread(idle = 1_700_000_000_000L, viewed = null)),
        )
        assertTrue(sources.unread(FOREIGN))
        assertTrue("the watermark alone is enough", sources.badge(FOREIGN).unread)
        assertTrue("…with no ask anywhere in sight", !sources.hasPermission(FOREIGN) && !sources.hasForm(FOREIGN))
        // …and an auto-approved permission row stays out of the feed's count.
        val autoApproved = SessionDotSources(
            autoApprove = true,
            notifications = listOf(
                notification(FOREIGN, viewed = false, kind = AppNotificationKind.PERMISSION),
            ),
        )
        assertFalse(autoApproved.unread(FOREIGN))
    }

    @Test
    fun theTrackedSessionIsTheOnlyOneThatCanShowTheMarquee() {
        // `ChatViewModel` observes one session, so only its run status is known;
        // the row's `loading` half must not be invented for the others.
        val sources = SessionDotSources(trackedSessionID = TRACKED, trackedRunning = true)
        assertTrue(sources.badge(TRACKED).loading)
        assertFalse(sources.badge(FOREIGN).loading)
        // …and a session waiting on the user is never drawn as busy.
        val waiting = SessionDotSources(
            trackedSessionID = TRACKED,
            trackedRunning = true,
            asks = mapOf(TRACKED to SessionAsks(permission = true)),
        )
        assertTrue(waiting.badge(TRACKED).unread)
        assertFalse(waiting.badge(TRACKED).loading)
    }
}
