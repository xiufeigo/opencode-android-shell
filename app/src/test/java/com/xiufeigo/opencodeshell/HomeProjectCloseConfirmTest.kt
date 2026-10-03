package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.data.HomeProjectRecords
import com.xiufeigo.opencodeshell.ui.home.HomeProjects
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v73, emulator-5556: the sidebar project row's trailing ✕ removed the row
 * immediately with no confirm (while the session column's delete has one),
 * reading exactly like a delete — while the worktree behind it stayed on the
 * server and came back (any session opened in it re-registers the project,
 * upstream `openProjectSession`).
 *
 * Per upstream the row action is Close — client-local, non-destructive, no
 * server delete exists in home — so the ✕ keeps that semantics and gains the
 * ask-first step instead: the confirm names the consequence (off the list,
 * nothing deleted) on neutral buttons. A worktree that should really go is
 * deleted where the shell already offers server-side delete (the new-session
 * worktree list, the Settings worktrees page), never from this row.
 *
 * Compose has no `ui-test-junit4` in this module, so the wiring is pinned at
 * source level (the `HomeProjectRowCloseA11yTest` technique); the store half
 * — a close survives a restart read — runs for real.
 */
class HomeProjectCloseConfirmTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun list() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectsList.kt")

    private fun value(dir: String, key: String): String =
        Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(source("src/main/res/$dir/strings.xml"))
            ?.groupValues
            ?.get(1)
            ?: error("$dir missing $key")

    // ------------------------------------------- the ✕ asks first, twice

    @Test
    fun `neither mount closes straight through the row`() {
        val body = list()
        assertFalse(
            "the ✕/menu Close must arm the confirm, not close behind the tap",
            body.contains("onClose = { onCloseProject(project) }"),
        )
        assertEquals(
            "the desktop column and the mobile dropdown both ask first",
            2,
            body.split("onClose = { pendingClose = project }").size - 1,
        )
    }

    @Test
    fun `the confirm closes the named row and nothing else`() {
        val body = list()
        assertTrue(body.contains("fun ProjectCloseConfirmDialog("))
        val calls = body.split("ProjectCloseConfirmDialog(").drop(1)
            .filter { it.contains("onConfirm = {") }
        assertEquals("both mounts host the confirm", 2, calls.size)
        for (call in calls) {
            val window = call.substring(0, 400)
            assertTrue(window.contains("onConfirm = {"))
            assertTrue(window.contains("pendingClose = null"))
            assertTrue(window.contains("onCloseProject(target)"))
            assertTrue(window.contains("onDismiss = { pendingClose = null }"))
        }
    }

    @Test
    fun `the card is a neutral close, not a danger delete`() {
        val body = list().substring(list().indexOf("fun ProjectCloseConfirmDialog("))
        assertTrue(body.contains("oc_home_project_close_title"))
        assertTrue(body.contains("oc_home_project_close_message"))
        assertTrue(body.contains("oc_common_cancel"))
        assertTrue(body.contains("R.string.oc_home_project_close"))
        assertFalse(
            "danger styling would say something is about to be destroyed",
            body.contains("stateDangerFg") || body.contains("danger = true"),
        )
    }

    // ------------------------------------------------------- the locales

    @Test
    fun `the close confirm is translated in all three locales`() {
        for (dir in listOf("values-zh-rCN", "values-zh-rTW")) {
            assertNotEquals(dir, value("values", "oc_home_project_close_title"), value(dir, "oc_home_project_close_title"))
            assertNotEquals(dir, value("values", "oc_home_project_close_message"), value(dir, "oc_home_project_close_message"))
        }
        for (dir in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            assertTrue(
                "$dir must name the row being closed",
                value(dir, "oc_home_project_close_message").contains("%1\$s"),
            )
        }
    }

    // --------------------------------------- the close survives a restart

    private fun project(worktree: String) =
        OpenCodeClient.Project(id = worktree, worktree = worktree, vcs = "git", iconColor = null)

    private fun session(directory: String) =
        OpenCodeClient.Session(id = "s", title = "s", directory = directory, updatedAt = 1L)

    @Test
    fun `a closed worktree stays closed across an encode decode round trip`() {
        val closed = HomeProjectRecords.close(
            HomeProjectRecords.open(HomeProjectRecords.State(), "/work/v36projC-wt"),
            "/work/v36projC-wt",
        )
        val restored = HomeProjectRecords.decode(HomeProjectRecords.encode(closed))
        assertEquals(listOf("/work/v36projC-wt"), restored.recentlyClosed)
        val known = HomeProjects.merge(
            projects = listOf(project("/work/v36projC-wt")),
            sessions = listOf(session("/work/v36projC-wt")),
        )
        val rows = HomeProjects.openRows(
            opened = restored.list,
            known = known,
            closed = restored.recentlyClosed,
        )
        assertTrue(
            "the server keeps listing the worktree; the tombstone keeps it off the column",
            rows.none { it.path == "/work/v36projC-wt" },
        )
    }

    @Test
    fun `reopening retires the tombstone instead of stacking rows`() {
        val closed = HomeProjectRecords.close(
            HomeProjectRecords.open(HomeProjectRecords.State(), "/work/wt"),
            "/work/wt",
        )
        val reopened = HomeProjectRecords.open(closed, "/work/wt")
        assertTrue(reopened.recentlyClosed.isEmpty())
        val known = HomeProjects.merge(
            projects = listOf(project("/work/wt")),
            sessions = emptyList(),
        )
        assertEquals(
            listOf("/work/wt"),
            HomeProjects.openRows(reopened.list, known, reopened.recentlyClosed).map { it.path },
        )
    }
}
