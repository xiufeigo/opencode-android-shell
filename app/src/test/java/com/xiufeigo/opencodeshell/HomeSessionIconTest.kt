package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.HomeProjects
import com.xiufeigo.opencodeshell.ui.home.HomeSessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-14, the session-row half of the project icon: Home's session list drew a
 * name-initial **stand-in** while the 项目 column right beside it drew the
 * project's own avatar (`icon.override` → `icon.color` → initial). Upstream
 * renders one `SessionTabAvatarView` in both places
 * (`home/sessions/view.tsx:219-228` → `ProjectAvatar`), so a project with an
 * uploaded icon read as its icon in one column and as a letter in the other.
 *
 * Two halves again:
 *
 * 1. **the attribution** — `homeProjectForSession`
 *    (`home/sessions/records.ts:41-53`) is a pure lookup, so the match rule is
 *    behavioural here: exact `pathKey` equality on the worktree or one of the
 *    sandboxes, *not* the nested rule the session scope uses.
 * 2. **the wiring** — that a row carries the icon, that the leading slot draws
 *    it, and that the host hands the catalogue down. The defaults on
 *    `HomeSessions.rows` / `HomeSessionsColumn` make an omission silent (a
 *    neutral avatar, not a compile error), so those two call sites are pinned
 *    at source level in the style of [SessionUnreadWiringTest].
 */
class HomeSessionIconTest {

    /** The `data:` URL shape `HomeProjectEditDialog`'s SAF pick writes. */
    private val image = "data:image/png;base64,AAEC"

    private fun project(
        path: String,
        color: String? = null,
        override: String? = null,
        sandboxes: List<String> = emptyList(),
    ) = HomeProjects.Project(
        name = path.trimEnd('/').substringAfterLast('/'),
        path = path,
        iconColor = color,
        sandboxes = sandboxes,
        iconOverride = override,
    )

    private fun session(id: String, directory: String?, updatedAt: Long = 1L) =
        OpenCodeClient.Session(id = id, title = id, directory = directory, updatedAt = updatedAt)

    // ------------------------------------------------------- the attribution

    @Test
    fun `a session is attributed to its project by worktree or sandbox`() {
        val api = project("/work/api", color = "mint", override = image, sandboxes = listOf("/work/api-feat"))

        assertEquals(api, HomeProjects.forSession("/work/api", listOf(api)))
        assertEquals(api, HomeProjects.forSession("/work/api-feat", listOf(api)))
        // The catalogue's spelling does not have to match the session's: the
        // lookup compares `matchKey` (trailing separator / Windows case).
        assertEquals(api, HomeProjects.forSession("/work/api/", listOf(api)))
        // Windows keys fold case (`matchKey`), so the server's `C:\Repo` finds a
        // catalogue row spelled `c:/repo`.
        val windows = project("c:/repo")
        assertEquals(windows, HomeProjects.forSession("C:\\Repo", listOf(windows)))
        // A POSIX path is case-sensitive, exactly as `matchKey` is.
        assertNull(HomeProjects.forSession("/work/API", listOf(api)))
    }

    @Test
    fun `a directory below a worktree is not the project`() {
        // Upstream matches `pathKey(item.worktree) === directory` — exact — while
        // the *scope* filter matches nested directories (`isProjectDirectory`).
        // The two rules differ on purpose: a session in `worktree/packages/x`
        // lists under the project but renders its own (unknown) avatar.
        val api = project("/work/api")
        assertTrue(HomeSessions.containsDirectory("/work/api", "/work/api/packages/x"))
        assertNull(HomeProjects.forSession("/work/api/packages/x", listOf(api)))
    }

    @Test
    fun `a session with no directory belongs to no project`() {
        val api = project("/work/api")
        assertNull(HomeProjects.forSession(null, listOf(api)))
        assertNull(HomeProjects.forSession("", listOf(api)))
        assertNull(HomeProjects.forSession("   ", listOf(api)))
        // …and a project the catalogue does not hold is simply not found.
        assertNull(HomeProjects.forSession("/elsewhere", listOf(api)))
    }

    // ------------------------------------------------------------ the row

    @Test
    fun `the row carries its project's icon halves`() {
        val catalogue = listOf(project("/work/api", color = "mint", override = image))
        val rows = HomeSessions.rows(
            listOf(
                session("ses_1", "/work/api", updatedAt = 2L),
                session("ses_2", "/elsewhere", updatedAt = 1L),
            ),
            projects = catalogue,
        )

        val api = rows.single { it.id == "ses_1" }
        assertEquals("mint", api.iconColor)
        assertEquals(image, api.iconOverride)
        // The un-attributed row keeps the neutral avatar, never the first
        // project's icon.
        val other = rows.single { it.id == "ses_2" }
        assertNull(other.iconColor)
        assertNull(other.iconOverride)
        // The label half of the row is untouched by the icon lookup.
        assertEquals("api", api.project)
    }

    @Test
    fun `the search source's own rows carry the icon too`() {
        // `searchRows` builds fresh rows out of the *untruncated* list and those
        // win the `distinctBy` against the visible ones, so the icon has to be
        // resolved there as well or every search hit loses its avatar.
        val catalogue = listOf(project("/work/api", color = "orange", override = image))
        val searched = HomeSessions.searchRows(
            all = listOf(session("ses_1", "/work/api")),
            visible = emptyList(),
            projects = catalogue,
        )

        assertEquals(image, searched.single().iconOverride)
        assertEquals("orange", searched.single().iconColor)
    }

    @Test
    fun `without a catalogue the rows keep the neutral avatar`() {
        // The defaults are what every preview / isolated host relies on.
        val rows = HomeSessions.rows(listOf(session("ses_1", "/work/api")))

        assertNull(rows.single().iconColor)
        assertNull(rows.single().iconOverride)
    }

    // ------------------------------------------------------------ the wiring

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun homeSessionsList() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")

    private fun homeScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")

    /** The file without its comment lines, so a doc mention is not a code read. */
    private fun code(text: String): String = text.lineSequence()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
        .joinToString("\n")

    private fun bodyOf(text: String, signature: String, span: Int = 1_400): String {
        val at = text.indexOf(signature)
        assertTrue("missing $signature", at >= 0)
        return text.substring(at, minOf(text.length, at + span))
    }

    /** Every `signature(...)` call block, balanced-paren scanned. */
    private fun callBlocks(text: String, signature: String): List<String> {
        val blocks = mutableListOf<String>()
        var index = text.indexOf(signature)
        while (index >= 0) {
            var depth = 0
            var at = index + signature.length - 1
            while (at < text.length) {
                when (text[at]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                at++
            }
            blocks += text.substring(index, minOf(at + 1, text.length))
            index = text.indexOf(signature, minOf(at + 1, text.length))
        }
        return blocks
    }

    @Test
    fun `the leading slot draws the project icon instead of the initial stand-in`() {
        val list = homeSessionsList()
        val leading = bodyOf(list, "private fun HomeSessionLeading(")

        assertTrue(leading.contains("ProjectIconView("))
        assertTrue(leading.contains("override = row.iconOverride"))
        assertTrue(leading.contains("iconColor = row.iconColor"))
        // Upstream's fallback name is `displayName(project ?? { worktree: directory })`
        // — the row's own project label; the title only covers a session the
        // server gave no directory for.
        assertTrue(leading.contains("name = row.project ?: row.title"))
        // The stand-in this replaced: the project/title initial drawn by hand.
        assertTrue(
            "the initial must come from ProjectIconView now",
            !code(leading).contains("uppercaseChar"),
        )
        // The dot still rides the avatar's corner, outside its clip.
        assertTrue(leading.contains("if (unread) SessionAvatarUnreadDot(avatarSize = AVATAR_SIZE)"))
    }

    @Test
    fun `the host hands the catalogue to the row model and to both column layouts`() {
        val home = homeScreen()

        // The row model: `HomeSessions.rows` is where the icon is resolved.
        val rowsCall = callBlocks(home, "HomeSessions.rows(").singleOrNull()
        assertTrue("HomeSessions.rows must be called once here", rowsCall != null)
        assertTrue(
            "the row model needs the catalogue to attribute the icons",
            rowsCall!!.contains("projects = projects,"),
        )

        // Both `HomeSessionsColumn` call sites (desktop two-column + phone) —
        // the parameter is defaulted, so a missing pass renders neutral avatars
        // instead of failing the build.
        val columns = callBlocks(home, "HomeSessionsColumn(")
        assertTrue("both column layouts exist", columns.size >= 2)
        for (block in columns) {
            assertTrue(
                "every HomeSessionsColumn call passes the catalogue",
                block.contains("projects = projects,"),
            )
        }
    }

    @Test
    fun `the search source is handed the same catalogue`() {
        val list = homeSessionsList()
        val searchAt = list.indexOf("HomeSessions.searchRows(")
        assertTrue("the search source is built from the untruncated list", searchAt >= 0)
        val call = list.substring(searchAt, minOf(list.length, searchAt + 400))

        assertTrue(call.contains("projects = projects,"))
        assertTrue(call.contains("projectNames = projectNames,"))
    }
}
