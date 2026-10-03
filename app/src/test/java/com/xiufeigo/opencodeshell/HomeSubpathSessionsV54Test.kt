package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.HomeProjects
import com.xiufeigo.opencodeshell.ui.home.HomeRowLayout
import com.xiufeigo.opencodeshell.ui.home.HomeSessions
import com.xiufeigo.opencodeshell.OpenCodeClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import androidx.compose.ui.unit.dp

/**
 * v54 P1, half 1 — 「Home 列表与搜索里看不到子路径会话」and 「矮窗会话列表只
 * 渲染一行且被截断 / 滚动后整片空白」。
 *
 * Both halves are measured off `research/v54_verify/`:
 *
 * - **tB** (`tB_50_home.png`, `tB_82_home_search.png`): the window was bound to
 *   `…/opencode-android-shell/app/src` (the backend reports `subpath:app/src`),
 *   so v2 answers with `location.directory = …/opencode-android-shell` plus the
 *   subpath. The shell registered the **subpath** as the project and scoped the
 *   sessions column to it, and the filter compared the raw `directory` — so the
 *   session belonged to no scope: no row in 今天, no hit for `E2ECHK`, and an
 *   empty column with no copy over it. A session from another machine
 *   (`v36projC`) searched fine, which is what localised the loss to the scope
 *   rather than to the fetch.
 *
 * - **tE** (`v54_16/18/19/20/22`, `u16.xml` vs `u18.xml`): a 1440x810 @420dpi
 *   window, 50+ sessions behind. The `uiautomator` bounds are the proof: `u16`
 *   has the row at `y627…663` under a card ending at 660 (one row, sliced by
 *   the card edge), and `u18` has the two group headers 74 px apart — one
 *   header's height — with **nothing between them**, i.e. every row and every
 *   pad measured 0 dp and the scroll had no range left to recover from (three
 *   further frames byte-identical).
 */
class HomeSubpathSessionsV54Test {

    private val root = "/home/me/work/opencode-android-shell"
    private val sub = "$root/app/src"

    private fun session(
        id: String,
        directory: String? = root,
        subpath: String? = null,
        title: String = "E2ECHK5556",
        updatedAt: Long = 1_760_000_000_000L,
        archivedAt: Long? = null,
    ) = OpenCodeClient.Session(
        id = id,
        title = title,
        directory = directory,
        updatedAt = updatedAt,
        archivedAt = archivedAt,
        subpath = subpath,
    )

    // ------------------------------------------------- 1. sessionDirectory

    @Test
    fun `a subpath is appended to the project root`() {
        assertEquals(sub, HomeSessions.sessionDirectory(session("ses_a", subpath = "app/src")))
    }

    @Test
    fun `a blank subpath keeps the directory as it is`() {
        assertEquals(root, HomeSessions.sessionDirectory(session("ses_a", subpath = null)))
        assertEquals(root, HomeSessions.sessionDirectory(session("ses_a", subpath = "  ")))
        assertEquals(root, HomeSessions.sessionDirectory(session("ses_a", subpath = "/")))
    }

    @Test
    fun `a subpath that is already the directory's tail is not appended twice`() {
        // Measured rows carry both spellings (`research/sess.json`):
        // `location.directory=/home/xiufeigo/code/…/knowledge`, `subpath=knowledge`.
        assertEquals(
            "/home/xiufeigo/code/chenjieyan_test/knowledge",
            HomeSessions.sessionDirectory(
                session("ses_a", directory = "/home/xiufeigo/code/chenjieyan_test/knowledge", subpath = "knowledge"),
            ),
        )
    }

    @Test
    fun `a Windows root keeps its own separator`() {
        assertEquals(
            "C:\\repo\\app\\src",
            HomeSessions.sessionDirectory(session("ses_a", directory = "C:\\repo", subpath = "app/src")),
        )
    }

    @Test
    fun `a session with no directory still resolves to nothing to scope on`() {
        assertNull(HomeSessions.sessionDirectory(session("ses_a", directory = null, subpath = "app/src")))
    }

    // --------------------------------------------- 2. the scope / the search

    @Test
    fun `the subpath session is listed while the subpath project is selected`() {
        val rows = HomeSessions.rows(
            listOf(
                session("ses_sub", subpath = "app/src"),
                session("ses_other", directory = "/home/me/work/other"),
            ),
            directories = listOf(sub),
        )
        assertEquals(listOf("ses_sub"), rows.map { it.id })
        assertEquals(sub, rows.single().directory)
        assertEquals("src", rows.single().project)
    }

    @Test
    fun `and it is still listed when the project root is selected`() {
        val rows = HomeSessions.rows(listOf(session("ses_sub", subpath = "app/src")), directories = listOf(root))
        assertEquals(listOf("ses_sub"), rows.map { it.id })
    }

    @Test
    fun `a subpath session is searchable too`() {
        val all = listOf(session("ses_sub", subpath = "app/src"))
        val hits = HomeSessions.searchRows(all, visible = emptyList(), directories = listOf(sub))
        assertEquals(listOf("ses_sub"), hits.map { it.id })
        assertEquals(
            listOf("ses_sub"),
            HomeSessions.filter(hits, "E2ECHK").map { it.id },
        )
    }

    @Test
    fun `a session from another project is still out of scope`() {
        val rows = HomeSessions.rows(
            listOf(session("ses_other", directory = "/home/me/work/other")),
            directories = listOf(sub),
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `the subpath counts against the project it lives in`() {
        val projects = HomeProjects.merge(
            projects = listOf(
                OpenCodeClient.Project(id = sub, worktree = sub, vcs = "git", iconColor = null),
                OpenCodeClient.Project(id = root, worktree = root, vcs = "git", iconColor = null),
            ),
            sessions = listOf(session("ses_sub", subpath = "app/src")),
        )
        assertEquals(1, projects.first { it.path == sub }.sessionCount)
        assertEquals(0, projects.first { it.path == root }.sessionCount)
    }

    @Test
    fun `the row's avatar is attributed to the subpath project`() {
        val project = HomeProjects.Project(name = "src", path = sub, sessionCount = 1, iconColor = "#ff0000")
        val row = HomeSessions.rows(listOf(session("ses_sub", subpath = "app/src")), projects = listOf(project))
        assertEquals("#ff0000", row.single().iconColor)
    }

    // ---------------------------------------------------- 3. the wiring

    @Test
    fun `the v2 row keeps its subpath on the way in`() {
        val vm = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        val body = vm.window("private fun V2Api.SessionInfo.toUiSession()", "private fun V2Api.SessionInfo.toUnread()")
        assertTrue(
            "dropping `subpath` here is what lost the row: the shell then had a " +
                "project root to compare against a subpath scope",
            body.contains("subpath = subpath"),
        )
        val api = source("src/main/java/com/xiufeigo/opencodeshell/v2/V2Api.kt")
        assertTrue("v2 reads the field off the wire", api.contains("subpath = optStringOrNull(obj, \"subpath\")"))
    }

    // -------------------------------- 4. the short-window budget (the list)

    @Test
    fun `the short-card floor is a whole row with the group it belongs to`() {
        assertEquals(
            HomeRowLayout.HEADER_MIN_HEIGHT + HomeRowLayout.GROUP_PAD_HEIGHT +
                HomeRowLayout.SESSION_ROW_HEIGHT,
            HomeRowLayout.LIST_SHORT_HEIGHT,
        )
        assertTrue(
            "a bare row was never composable on its own — the header and its pad " +
                "come first and the band ran out on them (`u18.xml`)",
            HomeRowLayout.listMinHeight(185.dp, editing = false) == HomeRowLayout.LIST_SHORT_HEIGHT,
        )
    }

    @Test
    fun `the list's own top step gives way on a card that cannot also afford the rows`() {
        val card = 131.dp // the measured 1440x810 @420dpi card
        val listMin = HomeRowLayout.listMinHeight(card)
        assertEquals(0.dp, HomeRowLayout.listTopPadding(card, listMin = listMin))
        assertTrue(
            "the band must be able to hold one whole row",
            HomeRowLayout.listBand(card) - HomeRowLayout.listTopPadding(card, listMin = listMin) >=
                HomeRowLayout.LIST_SHORT_HEIGHT - 4.dp,
        )
        // …and on a healthy card the cosmetic step is untouched.
        assertEquals(HomeRowLayout.LIST_TOP_PAD, HomeRowLayout.listTopPadding(900.dp))
    }

    @Test
    fun `the desktop column's top inset is elastic too`() {
        // tB_50: a ≈118 dp card with a fixed `pt-12` left ≈10 dp of rows, so the
        // 「今天」header rendered over a column with nothing in it.
        assertEquals(0.dp, HomeRowLayout.desktopSearchTopInset(118.dp, 48.dp))
        assertEquals(48.dp, HomeRowLayout.desktopSearchTopInset(600.dp, 48.dp))
    }

    @Test
    fun `the mobile column still fits its card at every height`() {
        for (card in listOf(900.dp, 640.dp, 400.dp, 300.dp, 220.dp, 200.dp, 185.dp, 150.dp, 131.dp)) {
            val listMin = HomeRowLayout.listMinHeight(card)
            val projects = HomeRowLayout.projectsDropdownMaxHeight(card, listMin)
            val nav = HomeRowLayout.utilityNavMaxHeight(card, listMin)
            val search = HomeRowLayout.searchBlockHeight(card)
            val top = HomeRowLayout.listTopPadding(card, chrome = projects + nav, listMin = listMin)
            val demand = search + projects + nav + top + listMin
            assertTrue("the column wants $demand of a $card card", demand <= card + 4.dp)
            if (demand <= card) {
                val band = card - search - projects - nav - top
                assertTrue("only $band of rows on a $card card", band >= listMin - 4.dp)
            }
        }
    }

    @Test
    fun `the host derives the elastic top step and hands it to the column`() {
        val home = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")
        assertTrue(home.contains("val listTopPadding = HomeRowLayout.listTopPadding(cardHeight)"))
        assertEquals(2, Regex("listTopPadding = listTopPadding").findAll(home).count())
        assertTrue(
            "the wide branch's fixed `pt-12` is what starved the rows in tB",
            home.contains("topInset = HomeRowLayout.desktopSearchTopInset("),
        )
        val list = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")
        assertTrue(list.contains("padding(top = listTopPadding)"))
    }

    @Test
    fun `a tracked title change re-reads the list without waiting for the tick`() {
        val home = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")
        assertTrue(home.contains("val trackedTitle by (chatViewModel?.currentTitle"))
        val tick = home.window(
            "LaunchedEffect(chatViewModel, trackedTitle) {",
            "// The 项目 column:",
        )
        assertTrue("a moved title reads now", tick.contains("if (!titleChanged) delay(HOME_SESSIONS_FRESHNESS_MS)"))
    }

    // ------------------------------------------------------------- helpers

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun String.window(from: String, to: String): String {
        val start = indexOf(from)
        require(start >= 0) { "missing window start: $from" }
        val end = indexOf(to, start + from.length)
        require(end > start) { "missing window end: $to" }
        return substring(start, end)
    }
}
