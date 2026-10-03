package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v68 worktree-lifecycle wiring pins: every destructive write stays behind its
 * confirm, every create entry records, and every delete reconciles.
 *
 * - Bug 2: the new-session worktree row's × only arms `worktreeDeleting`,
 *   which composes `WorktreeDeleteConfirmDialog` — `removeWorktree` is
 *   reachable from that screen solely through the dialog's confirm, so a tap
 *   on × can never delete outright again. The Settings page keeps its own
 *   `WorktreeDeleteDialog` gate.
 * - Bug 1: all three create entries (new-session selector, palette,
 *   session Move) record the created directory, and the new-session create
 *   invalidates the read behind its immediate add.
 * - Bug 3: both delete seams reconcile the saved inventory
 *   (`POST /api/worktree/refresh`) behind the delete.
 */
class WorktreeLifecycleWiringTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun occurrences(body: String, token: String): Int =
        body.split(token).size - 1

    // ------------------------------------------------- delete stays confirmed

    @Test
    fun theNewSessionRowDeleteOnlyArmsTheConfirmDialog() {
        val body = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/NewSessionScreen.kt")
        // The row's × arms the dialog; the dialog is what the screen composes.
        assertTrue(body.contains("worktreeDeleting = path"))
        assertTrue(body.contains("WorktreeDeleteConfirmDialog("))
        // The write itself is reachable from this screen only once — inside
        // the dialog's confirm (the import and the KDoc links carry no paren).
        assertEquals(1, occurrences(body, "removeWorktree("))
    }

    @Test
    fun theSettingsRowDeleteStaysBehindItsOwnDialog() {
        val body = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsPanels.kt")
        assertTrue(body.contains("pendingDelete = row"))
        assertTrue(body.contains("WorktreeDeleteDialog("))
    }

    @Test
    fun theDeleteConfirmCopyIsAvailableInEveryLocale() {
        for (key in listOf(
            "oc_new_session_worktree_delete_title",
            "oc_new_session_worktree_delete_message",
        )) {
            val english = stringValue("values", key)
            assertTrue("values/$key is empty", english.isNotBlank())
            for (locale in listOf("values-zh-rCN", "values-zh-rTW")) {
                val translated = stringValue(locale, key)
                assertTrue("$locale/$key is empty", translated.isNotBlank())
                assertNotEquals("$locale/$key is still the English copy", english, translated)
            }
        }
    }

    // ------------------------------------------------- every create is recorded

    @Test
    fun allThreeCreateEntriesRecordTheCreatedWorktree() {
        val newSession = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/NewSessionScreen.kt")
        assertTrue(newSession.contains("worktreeStore.record(created, branch)"))
        // The create invalidates the read behind its immediate in-memory add.
        assertTrue(newSession.contains("worktreeReload += 1"))
        val app = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
        assertTrue(app.contains("worktreeRecordStore.record(directory, null)"))
        val header = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt")
        assertTrue(header.contains("NewSessionWorktreeStore(context).record(created, null)"))
    }

    // ------------------------------------------------- every delete reconciles

    @Test
    fun bothDeleteSeamsRefreshTheInventoryBehindTheDelete() {
        val shared = source("src/main/java/com/xiufeigo/opencodeshell/ui/workspace/WorktreeCreate.kt")
        // Once behind the plain delete, once behind the forced retry.
        assertEquals(2, occurrences(shared, "api.refreshWorktrees(projectID)"))
        val delegate = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsV2Delegate.kt")
        assertEquals(1, occurrences(delegate, "api.refreshWorktrees(projectID)"))
    }

    private fun stringValue(locale: String, key: String): String =
        Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(source("src/main/res/$locale/strings.xml"))
            ?.groupValues
            ?.get(1)
            ?: error("$locale missing $key")
}
