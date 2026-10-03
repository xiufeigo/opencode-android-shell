package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.nav.SessionTabRef
import com.xiufeigo.opencodeshell.ui.sessionTitleSync
import com.xiufeigo.opencodeshell.ui.tabSeedTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * v64: the tab strip and the content pane are two different renders of one
 * route, and they disagreed.
 *
 * Upstream cannot drift because there is one store: `currentTab()` is
 * `matchRoute(layout.route())` (`_upstream/packages/app/src/shell/titlebar/
 * titlebar.tsx:209-233`) and the same object feeds the strip (`:524-527`) and
 * the header (`:477`). Here the two reads are split across a Compose frame, so
 * the pairing is re-established by hand — and it was wrong twice:
 *
 * - the nav listener runs **before** the destination composes, so
 *   `ChatViewModel.currentTitle` still held the *previous* session's name when
 *   the new tab was created, and the tab kept it
 *   (`research/v64_verify/5558/02_newsession.png`, `04b_tabmenu.png`,
 *   `06_tab_longpress.png`: a tab reading 「Quick check-in」 over a content pane
 *   reading 「新建会话」, and the reverse);
 * - the correcting effect was keyed on the *route* and read the plain
 *   `ChatViewModel.sessionID` field, so it neither re-ran when the view model
 *   finally adopted the session nor cleared a name the session never owned.
 */
class TabTitleSyncV64Test {

    private fun tab(id: String, title: String?) = SessionTabRef(id = id, title = title)

    // ------------------------------------------------------------ tabSeedTitle

    @Test
    fun aTabOpenedForAnotherSessionInheritsNoTitle() {
        // The nav listener fires before `observeSession`, so the live title is
        // still the outgoing session's. Seeding with it is the reported bug.
        assertNull(tabSeedTitle("old", "new", "Quick check-in"))
    }

    @Test
    fun aReopenedTabKeepsTheTitleItAlreadyOwned() {
        // The restore navigates back to a session the view model is already
        // tracking (the launch-from-notification path, a `popUpTo` re-entry):
        // there the live title *is* this session's and the tab should show it
        // immediately rather than flashing the `oc_tab_session` fallback.
        assertEquals("Quick check-in", tabSeedTitle("same", "same", "Quick check-in"))
    }

    @Test
    fun aBlankSeedTitleIsNoTitle() {
        assertNull(tabSeedTitle("same", "same", "   "))
        assertNull(tabSeedTitle("same", "same", null))
    }

    @Test
    fun anUntrackedViewModelSeedsNothing() {
        // No view model (previews, tests): there is no title to attribute, so
        // the tab starts on the fallback instead of borrowing one.
        assertNull(tabSeedTitle(null, "new", "Quick check-in"))
    }

    // ---------------------------------------------------------- sessionTitleSync

    @Test
    fun recordsTheTrackedSessionsTitleOnItsOwnTab() {
        val next = sessionTitleSync(
            tabs = listOf(tab("a", null), tab("b", null)),
            trackedSessionId = "b",
            liveTitle = "5v7d I v36projC",
        )
        assertEquals(listOf(tab("a", null), tab("b", "5v7d I v36projC")), next)
    }

    @Test
    fun aTitleBelongsToTheTrackedSessionAndToNothingElse() {
        // The regression itself: the route has moved to `b`, the view model has
        // not caught up yet, and the live title is still `a`'s. Writing it would
        // put 「Quick check-in」 on the tab the user is looking at.
        val next = sessionTitleSync(
            tabs = listOf(tab("a", "Quick check-in"), tab("b", null)),
            trackedSessionId = "a",
            liveTitle = "Quick check-in",
        )
        assertEquals(listOf(tab("a", "Quick check-in"), tab("b", null)), next)
    }

    @Test
    fun aBlankTitleClearsTheSlotInsteadOfKeepingABorrowedName() {
        // `observeSession` resets the title to null and the snapshot that would
        // refill it may never land (offline, deleted, a failed read). Leaving
        // the seeded name behind is how the tab outlived the desync.
        val next = sessionTitleSync(
            tabs = listOf(tab("a", "Quick check-in")),
            trackedSessionId = "a",
            liveTitle = null,
        )
        assertEquals(listOf(tab("a", null)), next)
    }

    @Test
    fun noTrackedSessionChangesNothing() {
        val tabs = listOf(tab("a", "Quick check-in"))
        assertSame(tabs, sessionTitleSync(tabs, null, "Quick check-in"))
    }

    @Test
    fun anUnopenedTabIsNotCreatedByTheSync() {
        val tabs = listOf(tab("a", null))
        assertSame(tabs, sessionTitleSync(tabs, "b", "5v7d I v36projC"))
    }

    @Test
    fun anUnchangedTitleReturnsTheSameListInstance() {
        val tabs = listOf(tab("a", "Quick check-in"))
        assertSame(tabs, sessionTitleSync(tabs, "a", "Quick check-in"))
    }

    @Test
    fun aTabKeepsItsNameWhileAnotherSessionIsTracked() {
        // A tab the user switched away from holds its last known name — that is
        // the documented behaviour the strip depends on, and clearing every tab
        // on every switch would take it away.
        val next = sessionTitleSync(
            tabs = listOf(tab("a", "Quick check-in"), tab("b", null)),
            trackedSessionId = "b",
            liveTitle = "5v7d I v36projC",
        )
        assertEquals(listOf(tab("a", "Quick check-in"), tab("b", "5v7d I v36projC")), next)
    }
}
