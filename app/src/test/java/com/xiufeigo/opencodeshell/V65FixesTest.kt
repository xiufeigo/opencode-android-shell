package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.pickerDirectBrowseTarget
import com.xiufeigo.opencodeshell.ui.home.pickerAutoBrowseTarget
import com.xiufeigo.opencodeshell.ui.home.scopedSearch
import com.xiufeigo.opencodeshell.ui.nav.NavRestore
import com.xiufeigo.opencodeshell.ui.session.composer.popoverMaxHeight
import com.xiufeigo.opencodeshell.v2.DirEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v65 device run on `emulator-5556` (`research/v65_verify/5556/`):
 *
 * 1. **P0 crash** — creating a worktree navigated to
 *    `server/<key>/session//home/xiufeigo/.local/share/opencode/worktree/…`:
 *    the new-session screen handed the worktree **directory** to its
 *    *session-created* callback, and the route builder pasted it behind
 *    `session/` verbatim (`41-42`, FATAL `IllegalArgumentException`).
 * 2. 「移动到…」 — a hand-typed **complete** absolute path never browsed: the
 *    field and the resolved line followed the typing, the body kept listing the
 *    old directory and 「选择此目录」 stayed grey (`57-58`).
 * 3. `/` — the command list was cut to four unscrollable rows while the keyboard
 *    was up, and grew back to ten when it came down (`19-22`).
 */
class V65FixesTest {

    // ------------------------------------------------------------------ (1)

    /** A plain session id is the route the nav graph expects, unchanged. */
    @Test
    fun aSessionIdBuildsTheSessionRoute() {
        assertEquals("server/s71e98507/session/ses_7", NavRestore.session("s71e98507", "ses_7"))
    }

    /**
     * The crash's own string: an absolute directory pasted after `session/`.
     * Percent-encoded, it can no longer reshape the route — the `/` is data,
     * not a separator.
     */
    @Test
    fun anAbsolutePathInASessionIdIsEncodedNotAppended() {
        val route = NavRestore.session("s71e98507", "/home/xiufeigo/.local/share/opencode/worktree/ae5407/shiny-orchid")
        assertFalse("route must not carry a bare '//'", route.contains("//"))
        assertEquals(4, route.split("/").size)
        assertTrue(route.contains("%2Fhome"))
    }

    /** A route is only navigable when the id is one usable path segment. */
    @Test
    fun sessionOrNullRefusesWhatTheRouteCannotCarry() {
        assertNull(NavRestore.sessionOrNull("s1", ""))
        assertNull(NavRestore.sessionOrNull("s1", "   "))
        assertNull(NavRestore.sessionOrNull("s1", null))
        assertNull(
            "the v65 crash input",
            NavRestore.sessionOrNull("s1", "/home/xiufeigo/v36projC/app/src"),
        )
        assertEquals("server/s1/session/ses_9", NavRestore.sessionOrNull("s1", " ses_9 "))
    }

    /** Unreserved characters survive; everything else is percent-encoded. */
    @Test
    fun encodeSegmentKeepsAsciiIdsReadable() {
        assertEquals("ses-7_a.b~c", NavRestore.encodeSegment("ses-7_a.b~c"))
        assertEquals("a%2Fb", NavRestore.encodeSegment("a/b"))
        assertEquals("%3F%23", NavRestore.encodeSegment("?#"))
    }

    /**
     * The worktree dialog's `onCreatedFrom` must not reach the new-session
     * screen's own `onCreated` (the navigate-on-session-created callback) — the
     * mix-up that put a directory on the session route.
     */
    @Test
    fun theWorktreeDialogDoesNotNavigateToItsDirectory() {
        val source = File(
            "src/main/java/com/xiufeigo/opencodeshell/ui/home/NewSessionScreen.kt",
        ).readText()
        val block = source.substringAfter("onCreatedFrom = { created, branch ->")
            .substringBefore("onDismiss = { worktreeCreateOpen = false }")
        assertFalse(
            "the worktree callback must not call the session-created callback: $block",
            Regex("""\bonCreated\s*\(""").containsMatchIn(block),
        )
        assertTrue(block.contains("selected = created"))
        assertTrue(block.contains("worktreeStore.record(created, branch)"))
    }

    /** The route builder itself is guarded where a create is turned into a page. */
    @Test
    fun theNewSessionRouteRefusesAnUnusableId() {
        val app = File("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt").readText()
        val block = app.substringAfter("onCreated = { id ->").substringBefore("popUpTo(Route.NEW_SESSION)")
        assertTrue(block.contains("NavRestore.sessionOrNull(serverKey, id)"))
    }

    // ------------------------------------------------------------------ (2)

    /**
     * The full typed path of the device run. The scope walk names its parent
     * (`…/v36projC/app`) and its last segment, which is what the second
     * auto-browse route reads.
     */
    @Test
    fun aTypedAbsolutePathNamesADirectoryToBrowse() {
        val input = "/home/xiufeigo/v36projC/app/src"
        val scope = scopedSearch(input, "/home/xiufeigo", "/home/xiufeigo")
        assertEquals("/home/xiufeigo/v36projC/app", scope.directory)
        assertEquals("src", scope.name)
        assertEquals(
            input,
            pickerDirectBrowseTarget(input, "/home/xiufeigo", "/home/xiufeigo", "/home/xiufeigo/v36projC"),
        )
    }

    /**
     * A half-typed path is **named** but not browsed: the function only says
     * what a settled absolute path would mean, and the caller browses it solely
     * when the server can list it — so a segment still being typed leaves the
     * body (and the suggestion rows) alone.
     */
    @Test
    fun aHalfTypedPathIsOnlyNamedAndTheReadDecides() {
        assertEquals(
            "/home/xiufeigo/v36projC/ap",
            pickerDirectBrowseTarget(
                "/home/xiufeigo/v36projC/ap",
                "/home/xiufeigo",
                "/home/xiufeigo/v36projC",
                "/home/xiufeigo/v36projC",
            ),
        )
        val source = File("src/main/java/com/xiufeigo/opencodeshell/ui/home/DirectoryPickerDialog.kt").readText()
        val block = source.substringAfter("val direct = pickerDirectBrowseTarget(")
            .substringBefore("browseResolved(direct)")
        assertTrue("an unlistable path must not move the browser", block.contains("listed != null"))
    }

    /** A trailing `/` asks for children, not for one directory. */
    @Test
    fun aTrailingSlashIsNotACompletePath() {
        assertNull(
            pickerDirectBrowseTarget(
                "/home/xiufeigo/v36projC/app/",
                "/home/xiufeigo",
                "/home/xiufeigo/v36projC",
                "/home/xiufeigo/v36projC",
            ),
        )
    }

    /** A relative name keeps the field's Enter / suggestion rows in charge. */
    @Test
    fun aRelativeNameIsLeftAlone() {
        assertNull(pickerDirectBrowseTarget("src", "/home/xiufeigo", "/home/xiufeigo/v36projC", "/home/xiufeigo/v36projC"))
    }

    /** The settled path on screen must not re-list on every keystroke. */
    @Test
    fun thePathAlreadyOnScreenIsNotRebrowsed() {
        assertNull(
            pickerDirectBrowseTarget(
                "/home/xiufeigo/v36projC/",
                "/home/xiufeigo",
                "/home/xiufeigo",
                "/home/xiufeigo/v36projC",
            ),
        )
        assertNull(
            pickerDirectBrowseTarget(
                "/home/xiufeigo/v36projC",
                "/home/xiufeigo",
                "/home/xiufeigo",
                "/home/xiufeigo/v36projC/",
            ),
        )
    }

    /** The parent-listing route still answers first, for a scope it can read. */
    @Test
    fun theParentListingRouteIsUnchanged() {
        val input = "/home/xiufeigo/v36projC/app/src"
        val scope = scopedSearch(input, "/home/xiufeigo", "/home/xiufeigo")
        val children = listOf(DirEntry("src", "/home/xiufeigo/v36projC/app/src", true))
        assertEquals(
            "/home/xiufeigo/v36projC/app/src",
            pickerAutoBrowseTarget(input, scope, children, "/home/xiufeigo/v36projC"),
        )
        // No child by that name ⇒ that route declines and the direct read runs.
        assertNull(
            pickerAutoBrowseTarget(
                input,
                scope,
                listOf(DirEntry("build", "/home/xiufeigo/v36projC/app/build", true)),
                "/home/xiufeigo/v36projC",
            ),
        )
    }

    // ------------------------------------------------------------------ (3)

    /** No keyboard: `max-h-80` still governs — an unclipped card is unchanged. */
    @Test
    fun thePopoverKeepsItsOwnCapWithoutAKeyboard() {
        assertEquals(320.dp(), popoverMaxHeight(768.dp(), 0.dp(), 0.dp()))
    }

    /**
     * Keyboard up on a short window: the card is capped by the room left between
     * the IME and the chrome above it, so the list shrinks into a scrollable one
     * instead of a clipped one (v65 `19-22`: four rows, no scroll, ten rows once
     * the keyboard was gone).
     */
    @Test
    fun thePopoverIsCappedByTheRoomTheKeyboardLeaves() {
        val ime = 428.dp()
        val capped = popoverMaxHeight(768.dp(), ime, 0.dp())
        assertTrue("expected a cap well under max-h-80, got $capped", capped < 320.dp())
        assertTrue("the cap must still fit rows: $capped", capped >= 96.dp())
    }

    /** A cramped window floors the cap instead of collapsing the card. */
    @Test
    fun theCapHasAFloor() {
        assertEquals(96.dp(), popoverMaxHeight(240.dp(), 200.dp(), 0.dp()))
    }

    private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())
}
