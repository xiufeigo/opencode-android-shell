package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.nav.SessionTabRef
import com.xiufeigo.opencodeshell.ui.sessionHeaderTitle
import com.xiufeigo.opencodeshell.ui.sessionTitleSync
import com.xiufeigo.opencodeshell.ui.tabSeedTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v68 C1 (agent-688 handover, 5558): the title chain went stale in two shapes.
 *
 * **`observeSession` published a navigation-time name.** Its `title` argument is
 * whatever the caller was holding when it navigated — the row cache a Home list
 * or a drawer was drawn from, or the draft-time name a route was built with. A
 * rename after that point makes it the *old* name, and it was written straight
 * into `currentTitle`, so the header, the export filename and the tab label all
 * read the session's former name until a catalogue read came back and corrected
 * it. The fix records what this client has actually learned per id
 * (`ChatViewModel.sessionTitle`) and prefers that over the argument, which
 * stays as the last resort for a caller that has nothing better (a preview, a
 * test).
 *
 * **A tab switch painted the placeholder.** `observeSession` fires from the
 * destination's own effect, i.e. *after* the composition, so on the frame the
 * switch lands on the live title is still the outgoing session's — and after the
 * draft page cleared it, null. The header read that and fell through to
 * 「新建会话」, while the new tab, seeded with nothing, read 「会话」; only a later
 * read fixed both. The two rules pinned here are what make the switch land
 * correct on the first frame: a per-id name is safe to read before adoption, and
 * a live title is only ever read for the session the view model has adopted.
 */
class SessionTitleChainV69Test {

    private fun tab(id: String, title: String?) = SessionTabRef(id = id, title = title)

    private fun header(
        pending: String? = null,
        live: String? = null,
        tracked: String? = null,
        onScreen: String = "ses_a",
        learned: String? = null,
        nav: String? = null,
    ): String = sessionHeaderTitle(
        pendingTitle = pending,
        liveTitle = live,
        trackedSessionId = tracked,
        onScreenSessionId = onScreen,
        learnedTitle = learned,
        navTitle = nav,
        newSessionLabel = "新建会话",
    )

    // --------------------------------------------------------- tabSeedTitle

    @Test
    fun aLearnedNameSeedsTheTabEvenBeforeTheViewModelAdopts() {
        // The switch frame: the view model still tracks the previous session and
        // `currentTitle` is that session's, so the only safe seed is the name
        // read for *this* id.
        assertEquals(
            "5v7d I v36projC",
            tabSeedTitle(
                trackedSessionId = "ses_b",
                openingId = "ses_a",
                liveTitle = "Quick check-in",
                knownTitle = "5v7d I v36projC",
            ),
        )
    }

    @Test
    fun aLearnedNameIsTakenEvenWhenTheViewModelTracksTheSameSession() {
        // Same session, both sources: the learned name is the fresher of the two
        // (a rename this client already saw), so it is the one that lands.
        assertEquals(
            "5v7d I v36projC",
            tabSeedTitle("ses_a", "ses_a", "Old name", "5v7d I v36projC"),
        )
    }

    @Test
    fun aBlankLearnedNameFallsBackToTheV64Rule() {
        // The learned name is a plain string, and a blank one is no name: the
        // v64 contract (`TabTitleSyncV64Test`) still decides from there.
        assertEquals("Quick check-in", tabSeedTitle("same", "same", "Quick check-in", "   "))
        assertNull(tabSeedTitle("other", "new", "Quick check-in", null))
        assertEquals("Quick check-in", tabSeedTitle("same", "same", "Quick check-in"))
    }

    @Test
    fun theV64SeedContractIsUnchangedWithoutALearnedName() {
        // Every case `TabTitleSyncV64Test` pins, through the same call.
        assertNull(tabSeedTitle("old", "new", "Quick check-in"))
        assertEquals("Quick check-in", tabSeedTitle("same", "same", "Quick check-in"))
        assertNull(tabSeedTitle("same", "same", "   "))
        assertNull(tabSeedTitle(null, "new", "Quick check-in"))
    }

    // ------------------------------------------------------- sessionHeaderTitle

    @Test
    fun theSwitchFrameNamesTheIncomingSessionNotTheOutgoingOne() {
        // v68 C1 on 5558: the view model had not adopted `ses_a` yet, so the live
        // title still read 「新建会话」's neighbour — the outgoing session.
        assertEquals(
            "5v7d I v36projC",
            header(live = "Quick check-in", tracked = "ses_b", onScreen = "ses_a", learned = "5v7d I v36projC"),
        )
    }

    @Test
    fun anAdoptedSessionReadsItsOwnLiveTitle() {
        assertEquals("5v7d I v36projC", header(live = "5v7d I v36projC", tracked = "ses_a", onScreen = "ses_a"))
    }

    @Test
    fun theLiveTitleNeverNamesAnotherSessionEvenWhenItIsTheOnlyOne() {
        // No learned name and no nav title: the honest answer for a session this
        // client has never read is the placeholder, not the outgoing session's
        // name.
        assertEquals("新建会话", header(live = "Quick check-in", tracked = "ses_b", onScreen = "ses_a"))
        // …and neither does a null tracked id (no view model, previews).
        assertEquals("新建会话", header(live = "Quick check-in", tracked = null, onScreen = "ses_a"))
    }

    @Test
    fun aSessionWithNoNameAtAllReadsThePlaceholder() {
        assertEquals("新建会话", header(tracked = "ses_a"))
    }

    @Test
    fun theOptimisticRenameOutranksEveryOtherSource() {
        // The `PATCH` bridge: the header shows the new name before the server
        // answers, and a rollback is a session that failed to answer at all.
        assertEquals(
            "E2ECHK5558-Stream",
            header(
                pending = "E2ECHK5558-Stream",
                live = "Old name",
                tracked = "ses_a",
                onScreen = "ses_a",
                learned = "Old name",
                nav = "Old name",
            ),
        )
    }

    @Test
    fun aLearnedNameBeatsTheNavigationTimeOne() {
        // The v68 bug in one line: the caller was holding the name from before
        // the rename, the registry the one after it.
        assertEquals("New name", header(nav = "Old name", learned = "New name"))
    }

    @Test
    fun theNavigationTimeNameIsTheLastResortBeforeThePlaceholder() {
        assertEquals("Old name", header(nav = "Old name"))
    }

    @Test
    fun aBlankNameIsNoNameEverywhere() {
        for (blank in listOf("", "   ")) {
            assertEquals(
                "blank live title: $blank",
                "5v7d I v36projC",
                header(live = blank, tracked = "ses_a", onScreen = "ses_a", learned = "5v7d I v36projC"),
            )
            assertEquals(
                "blank pending title: $blank",
                "5v7d I v36projC",
                header(pending = blank, tracked = "ses_a", onScreen = "ses_a", learned = "5v7d I v36projC"),
            )
            assertEquals(
                "blank nav title: $blank",
                "5v7d I v36projC",
                header(nav = blank, learned = "5v7d I v36projC"),
            )
        }
    }

    // ------------------------------------------------------- sessionTitleSync

    @Test
    fun theSyncRecordsTheAdoptedSessionsTitleOnItsOwnTab() {
        val next = sessionTitleSync(
            tabs = listOf(tab("ses_b", "Quick check-in"), tab("ses_a", null)),
            trackedSessionId = "ses_a",
            liveTitle = "5v7d I v36projC",
        )
        assertEquals(listOf(tab("ses_b", "Quick check-in"), tab("ses_a", "5v7d I v36projC")), next)
    }

    @Test
    fun theSyncDoesNotBlankATabTheLearnedNameAlreadyFilled() {
        // The half of the v68 flicker this fix closes: `observeSession` publishes
        // the learned name with the session, so the sync that runs on the frame
        // after adoption has a non-blank title to record and the tab keeps its
        // label instead of dropping back to 「会话」.
        val next = sessionTitleSync(
            tabs = listOf(tab("ses_a", "5v7d I v36projC")),
            trackedSessionId = "ses_a",
            liveTitle = "5v7d I v36projC",
        )
        assertEquals(listOf(tab("ses_a", "5v7d I v36projC")), next)
    }

    @Test
    fun theSwitchFrameLeavesTheIncomingTabAlone() {
        // Between the route change and adoption the tracked id is still the
        // outgoing session: the sync may only write on *its* tab.
        val next = sessionTitleSync(
            tabs = listOf(tab("ses_a", "5v7d I v36projC"), tab("ses_b", "Quick check-in")),
            trackedSessionId = "ses_b",
            liveTitle = "Quick check-in",
        )
        assertEquals(listOf(tab("ses_a", "5v7d I v36projC"), tab("ses_b", "Quick check-in")), next)
    }

    // --------------------------------------------------- the wiring itself

    @Test
    fun theHeaderAndTheTabReadTheSamePerIdName() {
        // One source, read the same way on both surfaces: a name learned for a
        // session names its header and its tab in the same frame.
        val learned = "5v7d I v36projC"
        val tabTitle = tabSeedTitle("ses_b", "ses_a", "Quick check-in", learned)
        val headerTitle = header(live = "Quick check-in", tracked = "ses_b", onScreen = "ses_a", learned = learned)
        assertEquals(headerTitle, tabTitle)
    }

    @Test
    fun observeSessionSeedsFromTheLearnedNameNotTheArgument() {
        // The view model's own rule, pinned on the source: a caller holding a
        // pre-rename name cannot overwrite what the client already learned.
        val text = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        val body = text
            .substringAfter("fun observeSession(id: String, title: String? = null, directory: String? = null)")
            .substringBefore("fun newSession()")
        assertTrue(
            "observeSession must prefer the learned name: $body",
            body.contains("publishCurrentTitle(id, sessionTitle(id) ?: title"),
        )
        // …and every other writer goes through the one guarded publisher, so a
        // read that answers after the user switched away cannot file its
        // session's name under the incoming one.
        assertTrue(
            "the guarded publisher is missing",
            text.contains("private fun publishCurrentTitle(forID: String?, title: String?)"),
        )
        assertTrue(
            "the publisher must drop a write for a session that is no longer tracked",
            text.contains("if (forID != null && forID != sessionID) return"),
        )
    }

    private fun source(relative: String): String =
        listOf(java.io.File(relative), java.io.File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")
}
