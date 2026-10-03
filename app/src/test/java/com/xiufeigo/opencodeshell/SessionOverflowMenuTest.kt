package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.header.SessionMenuAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P1 pins for the session header's ⋮ overflow menu (`SessionHeader.kt`
 * `SessionOverflowMenu`): every row collapses the menu on tap.
 *
 * `Popup(onDismissRequest)` only fires on outside taps / back press — a row tap
 * leaves `expanded == true`, so without an explicit `expanded = false` the menu
 * stays open on top of the export SAF sheet / delete confirm dialog. Compose
 * rows cannot run on the JVM unit-test harness, so — like `SshPanelTunnelTest`
 * and `BtwPanelRenderTest` — the collapse contract is pinned against the
 * composable source.
 */
class SessionOverflowMenuTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun overflowMenu(): String {
        val body =
            source("src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt")
        val start = body.indexOf("private fun SessionOverflowMenu")
        val end = body.indexOf("private fun MenuDivider")
        assertTrue(start >= 0 && end > start)
        return body.substring(start, end)
    }

    @Test
    fun exportRowCollapsesTheMenuBeforeDispatching() {
        val menu = overflowMenu()
        val label = menu.indexOf("command_session_export")
        val dispatch = menu.indexOf("SessionMenuAction.EXPORT", label)
        assertTrue(label >= 0 && dispatch > label)
        assertTrue(
            "Export must set expanded = false, or the ⋮ menu stays open",
            menu.substring(label, dispatch).contains("expanded = false"),
        )
    }

    @Test
    fun deleteRowCollapsesTheMenuBeforeDispatching() {
        val menu = overflowMenu()
        val label = menu.indexOf("oc_delete_session")
        val dispatch = menu.indexOf("SessionMenuAction.DELETE", label)
        assertTrue(label >= 0 && dispatch > label)
        assertTrue(
            "Delete must set expanded = false, or the ⋮ menu stays open",
            menu.substring(label, dispatch).contains("expanded = false"),
        )
    }

    @Test
    fun noRowDispatchesToTheCallSiteWithoutCollapsing() {
        assertTrue(overflowMenu().contains("SessionMenuAction.EXPORT"))
        assertTrue(
            "every onMenuAction dispatch must be preceded by expanded = false",
            !overflowMenu().contains("onClick = { onMenuAction("),
        )
    }

    @Test
    fun moveRowCollapsesTheMenuBeforeDispatching() {
        val menu = overflowMenu()
        val label = menu.indexOf("oc_move_session")
        val dispatch = menu.indexOf("SessionActionsMenuItem.MOVE", label)
        assertTrue(label >= 0 && dispatch > label)
        assertTrue(
            "Move must set expanded = false, or the ⋮ menu stays open",
            menu.substring(label, dispatch).contains("expanded = false"),
        )
    }

    /**
     * F-4 grows the ⋮ menu's session-action group to four rows (undo / redo /
     * fork / move) while the export entry stays exactly one (`SessionMenuAction`
     * itself is untouched: `menuActionsStayAtThreeWithASingleExport` still pins
     * its three values): the ASSEMBLED / OFFICIAL / SANITIZED + Import variants
     * live under the context tab's `ExportSessionButton`, never here.
     */
    @Test
    fun moveJoinsTheMenuWithoutDuplicatingExport() {
        val menu = overflowMenu()
        assertTrue(menu.contains("SessionActionsMenuItem.MOVE"))
        assertEquals(1, menu.split("command_session_export").size - 1)
        assertEquals(1, menu.split("SessionMenuAction.EXPORT").size - 1)
    }

    /**
     * The ⋮ menu carries exactly one export entry (`SessionMenuAction.EXPORT`):
     * the ASSEMBLED / OFFICIAL / SANITIZED + Import variants live under the
     * context tab's `ExportSessionButton`, never here.
     */
    @Test
    fun menuActionsStayAtThreeWithASingleExport() {
        assertEquals(
            setOf(SessionMenuAction.RENAME, SessionMenuAction.DELETE, SessionMenuAction.EXPORT),
            SessionMenuAction.values().toSet(),
        )
        val menu = overflowMenu()
        assertTrue(!menu.contains("ASSEMBLED"))
        assertTrue(!menu.contains("OFFICIAL"))
        assertTrue(!menu.contains("SANITIZED"))
    }
}
