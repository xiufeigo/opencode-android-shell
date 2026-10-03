package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.header.InlineTitleEdit
import com.xiufeigo.opencodeshell.ui.session.header.sessionHeaderActionsVisible
import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineSearch
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v97 sweepB §3.5 — the three "high" findings on the session timeline search
 * (`research/v97_verify/5562_sweepB/REPORT_v97b.md`, Q1–Q10).
 *
 * ## What the device actually ran
 *
 * The sweep's first move was `input tap 902 133`, read as 「tap 🔍 `Search
 * session`」. On the 1366 px / 160 dpi window of `emulator-5562` that entry is
 * **not** at x=902:
 *
 * ```
 * v97b_002_session01.xml
 *   Button  [84,110][1118,158]  text='E2ECHK5562-01'      ← the title
 *   Button  [1118,110][1158,158] desc='Search session'    ← the 🔍 entry
 * ```
 *
 * x=902 is 216 px inside the **title**, whose `onClick` is `editing = true`
 * (`SessionHeader.kt:350`). Every symptom after it is the inline title editor,
 * not the search:
 *
 * - the `EditText` at `[98,111][1188,159]` in
 *   `v97b_042/043/045/046/047/058.xml` is 48 dp tall and full-header —
 *   `TimelineSearchBar` is `BAR_HEIGHT = 28.dp` (`TimelineSearchBar.kt:266`) and
 *   always renders a ✕ `oc_search_close` button (`:228-233`). Neither is in the
 *   dump, and `Session summary` / `Context usage` / `More options` still sit
 *   beside it, which the bar's `return@Row` (`SessionHeader.kt:304-309`) would
 *   have removed;
 * - 「the 🔍 and `Ask a side question` nodes disappeared」 is
 *   [sessionHeaderActionsVisible] returning false — the documented step-aside
 *   for the editor;
 * - 「typing `Queued` + Enter rewrote the session title」 is upstream's own
 *   `saveTitleEditor` (`message-timeline.tsx:475-478`, `:280-283`) reached through
 *   the editor that tap opened;
 * - 「frozen results」 — there was no search; the timeline never filtered.
 *
 * So the three findings share **no** app state: the search is `TimelineSearch`
 * (a process-wide controller) and the rename is the header's local `editing`
 * flag, in two different composables with two different commit outlets. The real
 * defect in the same cluster is the **hit target** that made the mis-tap
 * possible — `the title hit area is the text, not the whole leftover row` below.
 *
 * These cases exist so that stays true, and so both exit paths keep resetting.
 */
class SessionSearchStateV97Test {

    @After
    fun tearDown() = TimelineSearch.reset()

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val header = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt")
    private val bar = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/header/TimelineSearchBar.kt")
    private val timeline = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/MessageTimeline.kt")

    /** The editor + the search's three slots, split out of the composable. */
    private val titleSlot: String
        get() = header.substringAfter("if (editing) {").substringBefore("Spacer(Modifier.weight(1f))")

    /** The `else` arm: the display title and its clickable. */
    private val titleBranch: String
        get() = titleSlot.substringAfter("} else {")

    private fun message(id: String, value: String) =
        ChatStore.UiMessage(
            id = id,
            role = "assistant",
            timeCreated = 0L,
            parts = listOf(ChatStore.UiPart(id = "p-$id", type = "text", text = value)),
        )

    /** The transcript `emulator-5562` was on: three texts, three unique needles. */
    private val transcript = listOf(
        message("m1", "from pathlib import Path"),
        message("m2", "OpenCode Android Shell"),
        message("m3", "Queued second message while streaming."),
    )

    // ------------------------------------------------- 1. separate commit outlets

    @Test
    fun `the search bar has no rename outlet at all`() {
        // The shape that turned the mis-tap into a server-side title write: one
        // host field with two exits. None of these may appear in the bar.
        for (forbidden in listOf("onRename", "InlineTitleEdit", "renameSession", "onCommit", "editing")) {
            assertFalse(
                "the search bar must not reach the rename path via `$forbidden`",
                bar.contains(forbidden),
            )
        }
        // …and the bar's own exits are close / move / set the needle, nothing else.
        assertTrue(bar.contains("onValueChange = { TimelineSearch.setQuery(it) }"))
        assertTrue(bar.contains("onClose: () -> Unit = { TimelineSearch.close() }"))
    }

    @Test
    fun `only the title editor can reach onRename, and only from the editing branch`() {
        val commit = titleSlot.substringAfter("onCommit = { renamed ->").substringBefore("onCancel =")
        assertTrue("the editor's commit is the rename outlet", commit.contains("onRename(renamed)"))
        // The display branch — the one the v97 tap hit — must not name it: a title
        // tap opens the editor, it never writes a title itself.
        assertFalse("a title tap must not write a title", titleBranch.contains("onRename"))
        // A blank draft is a cancel, never a rename to "" (`InlineTitleEdit`).
        val edit = InlineTitleEdit("E2ECHK5562-01")
        assertTrue(edit.commit("   ").close)
        assertNull(edit.commit("   ").save)
    }

    @Test
    fun `a search that was never opened leaves the rename path untouched`() {
        // The sweep's actual path: the title is tapped and the editor opens; the
        // search controller — never armed — must be inert, and the entry it gates
        // must not be what the tap can reach.
        TimelineSearch.reset()
        TimelineSearch.setAvailable(true)
        assertFalse(TimelineSearch.open)
        assertFalse("nothing is being searched", TimelineSearch.isLive)
        assertEquals("", TimelineSearch.query)
        assertEquals(0, TimelineSearch.count)
        // The bar is not composed at all, so no `setQuery` ever runs.
        assertTrue(header.contains("if (searching) {"))
        assertTrue(header.contains("val searching = TimelineSearch.open"))
    }

    // --------------------------------------------- 2. results follow the query

    @Test
    fun `three different queries over one transcript give three different results`() {
        // The sweep's Q2/Q3 sequence (`pathlib` → `the` → `OpenCode` → `Queued`),
        // as the pure recompute `MessageTimeline` runs on every keystroke.
        val pathlib = TimelineSearch.hits(transcript, "pathlib")
        val queued = TimelineSearch.hits(transcript, "Queued")
        val opencode = TimelineSearch.hits(transcript, "OpenCode")

        assertEquals(listOf("m1"), pathlib.map { it.messageID })
        assertEquals(listOf("m2"), opencode.map { it.messageID })
        assertEquals(listOf("m3"), queued.map { it.messageID })
        assertNotEquals(pathlib.map { it.partID }, opencode.map { it.partID })
        assertNotEquals(opencode.map { it.partID }, queued.map { it.partID })

        // Re-running the *first* query after the later ones must give the first
        // answer back — i.e. nothing is frozen on the first query.
        assertEquals(pathlib, TimelineSearch.hits(transcript, "pathlib"))
        // A needle nothing matches yields nothing, so a later query can also go
        // empty without the previous hits lingering.
        assertTrue(TimelineSearch.hits(transcript, "zzz").isEmpty())
    }

    @Test
    fun `the timeline recomputes its hits on every query change`() {
        // The wiring, not the pure matcher: `hits` is keyed on the query **and**
        // the liveness flag, both read from the controller on every composition.
        val block = timeline
            .substringAfter("val searchLive = TimelineSearch.isLive")
            .substringBefore("val activeHit =")
        assertTrue(block.contains("val searchQuery = TimelineSearch.query"))
        assertTrue(
            "a `remember` that ignored the query would freeze the results",
            block.contains("remember(messages, searchQuery, searchLive)"),
        )
        assertTrue(block.contains("TimelineSearch.hits(messages, searchQuery)"))
        // A blank needle is not a search: the bar can be up with an empty box.
        TimelineSearch.reset()
        TimelineSearch.setAvailable(true)
        assertFalse(TimelineSearch.isLive)
        TimelineSearch.open()
        assertFalse(TimelineSearch.isLive)
        TimelineSearch.setQuery("Queued")
        assertTrue(TimelineSearch.isLive)
    }

    // -------------------------------------------- 3. exiting resets everything

    @Test
    fun `closing the search resets the filter, the cursor and the count`() {
        TimelineSearch.reset()
        TimelineSearch.setAvailable(true)
        TimelineSearch.open()
        TimelineSearch.setQuery("Queued")
        TimelineSearch.publish(3)
        TimelineSearch.moveBy(2)
        assertTrue(TimelineSearch.isLive)

        TimelineSearch.close()

        assertFalse("the bar is down", TimelineSearch.open)
        assertFalse("nothing is being searched", TimelineSearch.isLive)
        assertEquals("the needle is gone", "", TimelineSearch.query)
        assertEquals("no filter survives", 0, TimelineSearch.count)
        assertEquals("the cursor is home", 0, TimelineSearch.active)
        assertEquals(TimelineSearch.Highlight.None, TimelineSearch.highlight(emptyList(), 0, ""))
    }

    @Test
    fun `leaving the timeline resets the search and the availability gate`() {
        TimelineSearch.reset()
        TimelineSearch.setAvailable(true)
        TimelineSearch.open()
        TimelineSearch.setQuery("Queued")
        // What `MessageTimeline`'s `DisposableEffect` runs on unmount.
        TimelineSearch.reset()
        assertFalse(TimelineSearch.available)
        assertFalse(TimelineSearch.open)
        assertEquals("", TimelineSearch.query)
        assertEquals(0, TimelineSearch.count)
        // Re-entering the session is a clean bar, not the previous search's residue.
        TimelineSearch.setAvailable(true)
        assertEquals("", TimelineSearch.query)
        assertEquals(0, TimelineSearch.count)
    }

    @Test
    fun `the header action entries come back the moment the search closes`() {
        // The "permanently wedged header" claim. It is one predicate, and the
        // search exit is what has to flip it back.
        assertFalse("the bar owns the row", sessionHeaderActionsVisible(searching = true, editing = false))
        assertFalse("the editor owns the title", sessionHeaderActionsVisible(searching = false, editing = true))
        assertTrue(
            "closed search + no editor = 🔍 and 「Ask a side question」 back",
            sessionHeaderActionsVisible(searching = false, editing = false),
        )
        // The composable must read that predicate, not a stale literal, and both
        // entries must sit behind it.
        assertTrue(header.contains("sessionHeaderActionsVisible(searching, editing)"))
        assertTrue(header.contains("if (canSearch && trailingActionsVisible)"))
        assertTrue(header.contains("if (trailingActionsVisible) SessionBtwEntry(modifier ="))
    }

    // ----------------------------------------- 4. the real defect: the hit area

    @Test
    fun `the title hit area is the text, not the whole leftover row`() {
        // `width(metrics.titleWidth)` made the clickable Text own all 1034 px the
        // trailing cluster left over — an invisible rename button across the
        // middle of the header, 216 px from the 🔍 the sweep aimed at. Upstream is
        // `w-fit` (`message-timeline.tsx:451`).
        assertTrue(
            "the title box must be capped, not stretched to the leftover width",
            titleBranch.contains(".widthIn(max = metrics.titleWidth)"),
        )
        assertFalse(
            "`.width(metrics.titleWidth)` is the v97 regression",
            titleBranch.contains(".width(metrics.titleWidth)"),
        )
        // The clickable still sits on the title and still opens the editor.
        assertTrue(titleBranch.contains(".clickable(role = Role.Button) { editing = true }"))
        // The v49 narrow-window clamp is unchanged: a title longer than the cap
        // ellipsises inside it instead of pushing the ring and ⋮ off the row.
        assertTrue(titleBranch.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue(titleBranch.contains("maxLines = 1"))
    }

    @Test
    fun `the search entry keeps its own reserved slot, so it is no longer under the title`() {
        // The row budget still reserves the entry — the fix moved the *title's*
        // box, not the cluster's, and the search entry is a 40 dp target at the
        // trailing edge where the dump measured it.
        assertTrue(header.contains("(if (canSearch && !editing && !searching) 1 else 0) +"))
        assertTrue(header.contains("TimelineSearchEntry(modifier = Modifier.size(metrics.iconButtonSize))"))
    }
}
