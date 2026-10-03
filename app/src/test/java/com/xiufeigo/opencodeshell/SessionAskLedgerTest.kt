package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The location-wide pending-ask ledger ([SessionAskLedger]) — the
 * `hasPermission` / `hasForm` halves of the dot, folded out of the v2 frames
 * `ChatViewModel` consumes for **every** session
 * (`shell/layout/project-avatar-state.ts:26-35` reads
 * `ctx.data.session.permission.list` / `session.form.list`, both location-wide).
 *
 * Why it exists at all: `ChatStore.pendingPermissions` is the *tracked* session's
 * dock list (every ask event is gated by `tracksSession`), so it can only ever
 * light the session on screen. The v28 device run's second scenario hung a
 * permission on a session the app was not observing — with only the tracked list
 * and the in-app feed, that session's row had no source at all.
 */
class SessionAskLedgerTest {

    private companion object {
        const val TRACKED = "ses_tracked"
        const val FOREIGN = "ses_foreign"
    }

    @Test
    fun anAskOnAnySessionIsPendingForThatSession() {
        val ledger = SessionAskLedger().asked("perm_1", FOREIGN)
        assertTrue(ledger.pending(FOREIGN).permission)
        assertTrue(ledger.pending(FOREIGN).any)
        assertEquals("a session nobody asked about is plain", SessionAsks.NONE, ledger.pending(TRACKED))
    }

    @Test
    fun theReplyClearsOnlyTheAskItNames() {
        // Two asks on one session (a turn can raise a second while the first is
        // open): the row stays lit until both are answered.
        val ledger = SessionAskLedger()
            .asked("perm_1", FOREIGN)
            .asked("perm_2", FOREIGN)
            .replied("perm_1")
        assertTrue("one ask is still pending", ledger.pending(FOREIGN).permission)

        val answered = ledger.replied("perm_2")
        assertFalse(answered.pending(FOREIGN).permission)
        // The other session's entry is untouched.
        assertFalse(answered.pending(TRACKED).permission)
    }

    @Test
    fun anUnknownReplyIsANoOp() {
        val ledger = SessionAskLedger().asked("perm_1", FOREIGN)
        assertEquals("a reply for an ask we never saw changes nothing", ledger, ledger.replied("perm_other"))
    }

    @Test
    fun aFrameWithNoSessionIsDroppedRatherThanGuessed() {
        // `ChatStore`'s own rule: an empty session id carries no routing info.
        val ledger = SessionAskLedger().asked("perm_1", "")
        assertEquals(SessionAskLedger(), ledger)
        assertEquals(SessionAskLedger(), ledger.formCreated("frm_1", ""))
    }

    @Test
    fun aFormIsItsOwnHalfAndSurvivesThePermissionReply() {
        val ledger = SessionAskLedger()
            .asked("perm_1", TRACKED)
            .formCreated("frm_1", TRACKED)
        assertTrue(ledger.pending(TRACKED).permission)
        assertTrue(ledger.pending(TRACKED).form)

        val answeredPermission = ledger.replied("perm_1")
        assertFalse(answeredPermission.pending(TRACKED).permission)
        assertTrue("the form still waits for the user", answeredPermission.pending(TRACKED).form)

        val answeredForm = answeredPermission.formResolved("frm_1")
        assertFalse(answeredForm.pending(TRACKED).any)
    }

    @Test
    fun theTrackedSessionsSnapshotReplacesOnlyThatSessionsHalf() {
        val ledger = SessionAskLedger()
            .asked("perm_old", TRACKED)
            .asked("perm_elsewhere", FOREIGN)

        // `GET …/permission` answered with nothing: the ask was answered while we
        // were away, so it is gone — but the foreign session's entry must not be
        // collateral damage.
        val refreshed = ledger.permissionSnapshot(TRACKED, emptyList())
        assertFalse(refreshed.pending(TRACKED).permission)
        assertTrue(refreshed.pending(FOREIGN).permission)

        // …and the snapshot's own ids are what a later reply matches on.
        val asked = ledger.permissionSnapshot(TRACKED, listOf("perm_new"))
        assertTrue(asked.pending(TRACKED).permission)
        assertFalse(asked.replied("perm_new").pending(TRACKED).permission)
    }

    @Test
    fun theFormSnapshotIsTheFormHalvesEquivalent() {
        val ledger = SessionAskLedger().formCreated("frm_1", TRACKED)
        assertFalse(ledger.formSnapshot(TRACKED, emptyList()).pending(TRACKED).form)
        val asked = ledger.formSnapshot(TRACKED, listOf("frm_2"))
        assertTrue(asked.pending(TRACKED).form)
        assertFalse(asked.formResolved("frm_2").pending(TRACKED).form)
    }

    @Test
    fun theLocationWideRowsAreUnionedNotReplaced() {
        // The read is scoped to one location, so an entry it does not name is not
        // evidence that the ask is gone — only a reply frame clears one.
        val ledger = SessionAskLedger().asked("perm_seen", TRACKED)
        val merged = ledger.pendingRows(
            permissions = listOf("perm_far" to FOREIGN),
            forms = listOf("frm_far" to FOREIGN),
        )
        assertTrue("the stream's own entry stays", merged.pending(TRACKED).permission)
        assertTrue(merged.pending(FOREIGN).permission)
        assertTrue(merged.pending(FOREIGN).form)
        // …and a REST-added entry is still cleared by its reply frame.
        assertFalse(merged.replied("perm_far").pending(FOREIGN).permission)
    }

    @Test
    fun aDeletedSessionTakesItsAsksWithIt() {
        val ledger = SessionAskLedger()
            .asked("perm_1", TRACKED)
            .formCreated("frm_1", TRACKED)
            .asked("perm_2", FOREIGN)
        val after = ledger.forget(TRACKED)
        assertEquals(SessionAsks.NONE, after.pending(TRACKED))
        assertTrue(after.pending(FOREIGN).permission)
        // A reply for a forgotten ask can no longer resurrect the entry.
        assertEquals(after, after.replied("perm_1"))
    }

    @Test
    fun anAnsweredSessionLeavesNoEntryBehind() {
        // The published map means "the sessions waiting on something", so an
        // answered ask must not leave an all-false row for every list surface to
        // walk past on every recomposition.
        val ledger = SessionAskLedger()
            .asked("perm_1", TRACKED)
            .formCreated("frm_1", TRACKED)
            .replied("perm_1")
            .formResolved("frm_1")
        assertEquals(emptyMap<String, SessionAsks>(), ledger.bySession)
        assertEquals(SessionAsks.NONE, ledger.pending(TRACKED))
        // A form-only session keeps its entry (the form half still waits).
        val waiting = SessionAskLedger().asked("perm_1", TRACKED).formCreated("frm_1", TRACKED).replied("perm_1")
        assertEquals(mapOf(TRACKED to SessionAsks(form = true)), waiting.bySession)
    }

    /**
     * The two v28 scenarios, at the ledger level: the frame the app receives for
     * a foreign session's ask is enough on its own — no dock, no route, no
     * session switch.
     */
    @Test
    fun bothDeviceScenariosFoldFromTheSameFrames() {
        // Scenario 1: the ask belongs to the session on screen.
        val tracked = SessionAskLedger().asked("perm_tracked", TRACKED)
        assertTrue(tracked.pending(TRACKED).permission)

        // Scenario 2: the ask belongs to a session the app is not observing.
        val foreign = SessionAskLedger().asked("perm_foreign", FOREIGN)
        assertTrue(foreign.pending(FOREIGN).permission)
        assertFalse(foreign.pending(TRACKED).any)
    }
}
