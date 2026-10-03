package com.xiufeigo.opencodeshell

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The project row's trailing ✎ (device run tB: "the pencil jumps to 新建会话, but
 * the editor is in the long-press menu").
 *
 * Adjudicated against upstream `home/projects/view.tsx`, where the pencil **is**
 * the new-session action:
 *
 * ```
 * 767  <IconButton
 * 768    data-action="home-project-new-session"
 * 770    icon={<Icon name="edit" />}
 * 769    aria-label={props.language.t("command.session.new")}
 * 771    onClick={() => props.onOpenProjectNewSession(props.server, props.project.worktree)}
 * ```
 *
 * — `data-action` says new-session, the `aria-label` says `command.session.new`,
 * and the glyph is upstream's `edit` icon. So the shell keeps ✎ = new session
 * (matching upstream's action *and* its label) and aligns the menu instead:
 * upstream's `MenuV2` lists `command.session.new` as its first row
 * (`view.tsx:733-735`) right above 「Edit project…」 (`:742-745`), so both
 * entries spell out their own action and the pencil cannot be read as the
 * editor. Editing a project is only ever the menu row.
 */
class HomeProjectPencilSemanticsTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText().replace("\r\n", "\n")
    }

    private val list by lazy {
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectsList.kt")
    }

    /** The ✎'s clickable Box, up to the ✕'s. */
    private fun pencil(): String {
        val body = list
        val start = body.indexOf(".clickable(role = Role.Button, onClick = onNewSession)")
        val end = body.indexOf("contentDescription = closeLabel", start)
        assertTrue("the row's ✎ clickable is gone", start >= 0 && end > start)
        return body.substring(start, end)
    }

    @Test
    fun `the pencil is the new-session action, labelled as upstream labels it`() {
        val button = pencil()
        assertTrue(button.contains("onClick = onNewSession"))
        // Upstream's `aria-label={command.session.new}` — the same label the
        // menu's new-session row carries.
        assertTrue(button.contains("contentDescription = newSessionLabel"))
        assertTrue(
            list.contains("val newSessionLabel = stringResource(R.string.oc_new_session)"),
        )
    }

    @Test
    fun `the menu spells out both actions in upstream's order`() {
        val menu = list.substringAfter("DropdownMenu(").substringBefore("HorizontalDivider(color = c.borderMuted)")
        // Upstream's first row (`view.tsx:733-735`) — the action the ✎ runs.
        assertTrue(menu.contains("R.string.oc_command_session_new"))
        // …then the import row, then the only editor entry, in that order.
        val newSession = menu.indexOf("R.string.oc_command_session_new")
        val edit = menu.indexOf("R.string.oc_home_project_edit")
        assertTrue("the new-session row leads the menu", newSession in 0 until edit)
        assertTrue(menu.contains("R.string.command_session_import"))
        // The menu row dismisses the menu before opening the draft, exactly
        // like the Close row does.
        assertTrue(menu.contains("onDismissMenu()\n                    onNewSession()"))    }

    @Test
    fun `the editor is reachable only through the menu row`() {
        // `controller.tsx:135-138` → the settings project editor; the row's own
        // affordances are new-session (✎) and close (✕) only.
        assertTrue(list.contains("R.string.oc_home_project_edit"))
        assertTrue(list.contains("onClick = onEdit"))
        // The ✎ opens a draft, never the editor: its clickable chain names the
        // one action.
        assertFalse(pencil().contains("onEdit"))
    }

    @Test
    fun `the new-session label exists in all three locales`() {
        val locales = listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-zh-rCN/strings.xml",
            "src/main/res/values-zh-rTW/strings.xml",
        )
        locales.forEach { relative ->
            val body = source(relative)
            assertTrue(relative, body.contains("name=\"oc_command_session_new\""))
            assertTrue(relative, body.contains("name=\"oc_new_session\""))
        }
    }

    @Test
    fun `the menu keeps the rows it already had`() {
        // The existing menu contract (HomeProjectMenuTest): import + clear +
        // the edit row + Close, on both mount points, all through ProjectRow.
        assertTrue(list.contains("R.string.command_session_import"))
        assertTrue(list.contains("R.string.oc_home_project_clear_notifications"))
        assertTrue(list.contains("R.string.oc_home_project_refresh_worktrees"))
        assertTrue(list.contains("R.string.oc_home_project_close"))
        assertEquals(3, Regex("""\bProjectRow\(""").findAll(list).count())
    }
}
