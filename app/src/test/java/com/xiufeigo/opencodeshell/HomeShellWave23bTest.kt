package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.data.HomeProjectRecords
import com.xiufeigo.opencodeshell.ui.home.HomeRowMenu
import com.xiufeigo.opencodeshell.ui.home.HomeSessions
import com.xiufeigo.opencodeshell.ui.home.NewSessionProject
import com.xiufeigo.opencodeshell.ui.nav.SessionTabs
import com.xiufeigo.opencodeshell.ui.nav.DraftTarget
import com.xiufeigo.opencodeshell.ui.nav.HomeToggle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * WAVE 2.3b — JVM pins for the new pure logic:
 * - A2 #1: [SessionTabs.footerHomePressed] (mobile drawer footer state);
 * - A2 #9: [HomeSessions.searchRows] (untruncated search source) +
 *   [HomeSessions.looksLikeSessionID] (session-ID direct lookup);
 * - A2 #4: [HomeRowMenu] (row menu + inline rename state machine) and the
 *   `rememberHomeRowExportSaver` scope teardown (source wiring);
 * - A2 #7: [SessionTabs.draftTarget] (branch decision) +
 *   [NewSessionProject.resolve] (`newSessionProject()` fallback) +
 *   [SessionTabs.toggleHomeTarget] (`toggleHome`).
 */
class HomeShellWave23bTest {

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun session(
        id: String,
        title: String = id,
        directory: String? = null,
        updatedAt: Long = 0L,
    ) = OpenCodeClient.Session(id = id, title = title, directory = directory, updatedAt = updatedAt)

    // ------------------------------------------------------------ A2 #1 drawer

    @Test
    fun `drawer footer presses Home only off a session route`() {
        assertTrue(SessionTabs.footerHomePressed(false))
        assertFalse(SessionTabs.footerHomePressed(true))
    }

    // --------------------------------------------------- A2 #9 search source

    @Test
    fun `searchRows covers sessions past the 64 row cap`() {
        val all = (0 until 70).map { index ->
            session("ses-$index", title = "work $index", updatedAt = index.toLong())
        }
        val visible = HomeSessions.rows(all)
        assertEquals(HomeSessions.MAX_SESSIONS, visible.size)

        val searched = HomeSessions.searchRows(all, visible)
        assertEquals(70, searched.size)
        assertTrue(searched.any { it.id == "ses-0" })
        // Newest first, like the visible list.
        assertEquals("ses-69", searched.first().id)
    }

    @Test
    fun `searchRows degrades to the visible list without a full list`() {
        val visible = HomeSessions.rows(
            listOf(session("a", updatedAt = 2L), session("b", updatedAt = 1L)),
        )
        assertEquals(visible, HomeSessions.searchRows(null, visible))
        assertEquals(visible, HomeSessions.searchRows(emptyList(), visible))
    }

    @Test
    fun `searchRows dedupes the visible rows by id`() {
        val all = listOf(session("a", title = "new", updatedAt = 2L))
        val visible = HomeSessions.rows(all)
        assertEquals(listOf("a"), HomeSessions.searchRows(all, visible).map { it.id })
    }

    @Test
    fun `searchRows keeps the project scope`() {
        val all = listOf(
            session("in", directory = "/work/api", updatedAt = 2L),
            session("out", directory = "/other/site", updatedAt = 3L),
        )
        val searched = HomeSessions.searchRows(all, emptyList(), directories = listOf("/work/api"))
        assertEquals(listOf("in"), searched.map { it.id })
    }

    @Test
    fun `sessionID shaped input matches its row directly`() {
        assertTrue(HomeSessions.looksLikeSessionID("ses_12345678901234567890"))
        assertTrue(HomeSessions.looksLikeSessionID("  ses_12345678901234567890  "))
        assertFalse(HomeSessions.looksLikeSessionID("ses_short"))
        assertFalse(HomeSessions.looksLikeSessionID("fix login"))
        assertFalse(HomeSessions.looksLikeSessionID(""))

        val id = "ses_12345678901234567890"
        val rows = HomeSessions.rows(
            listOf(
                session(id, title = "unrelated title", updatedAt = 2L),
                session("other", title = "other", updatedAt = 1L),
            ),
        )
        assertEquals(listOf(id), HomeSessions.filter(rows, id).map { it.id })
        // An unknown ID falls back to substring search (no title matches → empty).
        assertTrue(HomeSessions.filter(rows, "ses_00000000000000000000").isEmpty())
    }

    // ------------------------------------------------------- A2 #4 row menu

    @Test
    fun `opening another row moves the menu`() {
        val first = HomeRowMenu.openMenu(null, "a")
        assertEquals("a", first?.sessionId)
        assertEquals("b", HomeRowMenu.openMenu(first, "b")?.sessionId)
        // Re-opening the same row keeps it.
        assertEquals(first, HomeRowMenu.openMenu(first, "a"))
    }

    @Test
    fun `dismiss closes only the matching row`() {
        val menu = HomeRowMenu.Menu("a")
        assertNull(HomeRowMenu.closeMenu(menu, "a"))
        assertEquals(menu, HomeRowMenu.closeMenu(menu, "b"))
        assertNull(HomeRowMenu.closeMenu(null, "a"))
    }

    @Test
    fun `submit noops on blank or unchanged titles`() {
        val editor = HomeRowMenu.openEditor("Fix login", "a")
        // Blank draft → noop.
        val blank = HomeRowMenu.editDraft(editor, "a", "   ")!!
        val (keptBlank, blankSubmit) = HomeRowMenu.submit(blank, "Fix login")!!
        assertEquals(blank, keptBlank)
        assertEquals(HomeRowMenu.Submit.Noop, blankSubmit)
        // Unchanged (modulo trim) → noop.
        val (keptSame, sameSubmit) = HomeRowMenu.submit(editor, "Fix login")!!
        assertEquals(HomeRowMenu.Submit.Noop, sameSubmit)
        assertEquals(editor, keptSame)
    }

    @Test
    fun `submit marks saving and resolveSave closes on either answer`() {
        val editor = HomeRowMenu.openEditor("old", "a")
        val changed = HomeRowMenu.editDraft(editor, "a", "new")!!
        val (saving, action) = HomeRowMenu.submit(changed, "old")!!
        assertEquals(HomeRowMenu.Submit.Save("new"), action)
        assertEquals(true, saving?.saving)
        // Success closes; failure closes too (v84 P1 — the row goes back to the
        // catalogue's own title, and an editor a phone cannot leave was the
        // measured trap; see [HomeRenameFailureV83Test] and [HomeRenameExitV84Test]).
        assertNull(HomeRowMenu.resolveSave(saving, "a", true))
        assertNull(HomeRowMenu.resolveSave(saving, "a", false))
        // An answer that belongs to another row still moves nothing.
        assertEquals(saving, HomeRowMenu.resolveSave(saving, "b", false))
    }

    @Test
    fun `submit refuses an idle-less editor and a save in flight`() {
        // v44: the reporting row's id is no longer part of the decision - the
        // open editor is the only rename in flight, and the field that raised
        // the gesture is that editor's own. What is still refused is a submit
        // with no editor at all, and a second gesture while the PATCH is out.
        val editor = HomeRowMenu.editDraft(HomeRowMenu.openEditor("old", "a"), "a", "new")!!
        val (saving, action) = HomeRowMenu.submit(editor, "old")!!
        assertEquals(HomeRowMenu.Submit.Save("new"), action)
        assertNull(HomeRowMenu.submit(null, "old"))
        assertNull(HomeRowMenu.submit(saving, "old"))
    }

    @Test
    fun `closeEditor discards unless a save is in flight`() {
        val editor = HomeRowMenu.openEditor("old", "a")
        assertNull(HomeRowMenu.closeEditor(editor, "a"))
        val saving = editor.copy(saving = true)
        assertEquals(saving, HomeRowMenu.closeEditor(saving, "a"))
        assertEquals(editor, HomeRowMenu.closeEditor(editor, "b"))
    }

    @Test
    fun `menu key rule matches ContextMenu or Shift+F10`() {
        assertTrue(HomeRowMenu.menuKeyOpens(contextMenuKey = true, shiftF10 = false))
        assertTrue(HomeRowMenu.menuKeyOpens(contextMenuKey = false, shiftF10 = true))
        assertFalse(HomeRowMenu.menuKeyOpens(contextMenuKey = false, shiftF10 = false))
    }

    @Test
    fun `composition guard covers isComposing and keyCode 229`() {
        assertTrue(HomeRowMenu.isCompositionKey(true, 0))
        assertTrue(HomeRowMenu.isCompositionKey(false, 229))
        assertTrue(HomeRowMenu.isCompositionKey(false, HomeRowMenu.IME_COMPOSITION_KEYCODE))
        assertFalse(HomeRowMenu.isCompositionKey(false, 13))
    }

    // --------------------------------------------- A2 #7 context inheritance

    @Test
    fun `draftTarget inherits the session on a session route`() {
        assertEquals(
            DraftTarget.InheritSession("s1"),
            SessionTabs.draftTarget(
                onSessionRoute = true,
                activeSessionId = "s1",
                selectedDirectory = "/work/api",
                projectDirectories = listOf("/work/api"),
                mostRecentDirectory = "/work/api",
            ),
        )
    }

    @Test
    fun `draftTarget falls back without a live session`() {
        // `+` on Home with a selection drafts in it…
        assertEquals(
            DraftTarget.InheritProject("/sel"),
            SessionTabs.draftTarget(
                onSessionRoute = false,
                activeSessionId = null,
                selectedDirectory = "/sel",
                projectDirectories = listOf("/sel", "/other"),
                mostRecentDirectory = "/other",
            ),
        )
        // …else the MRU marker, else the first project, else the server cwd.
        assertEquals(
            DraftTarget.InheritProject("/recent"),
            SessionTabs.draftTarget(false, null, null, listOf("/a"), "/recent"),
        )
        assertEquals(
            DraftTarget.InheritProject("/a"),
            SessionTabs.draftTarget(false, null, null, listOf("/a"), null),
        )
        assertEquals(
            DraftTarget.DefaultDirectory,
            SessionTabs.draftTarget(false, null, null, emptyList(), null),
        )
        // A session route with no live session also falls back.
        assertEquals(
            DraftTarget.InheritProject("/a"),
            SessionTabs.draftTarget(true, null, null, listOf("/a"), null),
        )
    }

    @Test
    fun `newSessionProject resolves selected over recent over first`() {
        val opened = HomeProjectRecords.State(
            list = listOf(
                HomeProjectRecords.Project("/first"),
                HomeProjectRecords.Project("/second"),
            ),
            lastProject = "/recent",
        )
        assertEquals("/sel", NewSessionProject.resolve("/sel", opened))
        assertEquals("/recent", NewSessionProject.resolve(null, opened))
        assertEquals("/first", NewSessionProject.resolve(null, opened.copy(lastProject = null)))
        assertNull(NewSessionProject.resolve(null, HomeProjectRecords.State()))
        assertEquals("/recent", NewSessionProject.resolve("  ", opened))
    }

    @Test
    fun `toggleHome jumps back to the recent tab when already home`() {
        assertEquals(HomeToggle.BackToTab("s1"), SessionTabs.toggleHomeTarget(true, "s1"))
        assertEquals(HomeToggle.GoHome, SessionTabs.toggleHomeTarget(true, null))
        assertEquals(HomeToggle.GoHome, SessionTabs.toggleHomeTarget(true, "  "))
        assertEquals(HomeToggle.GoHome, SessionTabs.toggleHomeTarget(false, "s1"))
    }

    // ------------------------------------------------- A2 #4 export scope

    private fun homeRowActionsSource(): String =
        listOf(
            File("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeRowActions.kt"),
            File("app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeRowActions.kt"),
        ).firstOrNull { it.exists() }?.readText() ?: error("HomeRowActions.kt not found")

    @Test
    fun `leaving Home cancels the remembered export scope`() {
        // The scope is `remember`ed, so nothing but a DisposableEffect can
        // cancel it — without that an in-flight export keeps toasting into a
        // composition that is already gone.
        val source = homeRowActionsSource()
        assertTrue(source.contains("remember { CoroutineScope(Dispatchers.Main) }"))
        assertTrue(source.contains("DisposableEffect(scope)"))
        assertTrue(source.contains("onDispose { scope.cancel() }"))
    }
}
