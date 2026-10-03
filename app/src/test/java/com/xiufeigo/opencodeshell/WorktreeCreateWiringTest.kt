package com.xiufeigo.opencodeshell

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source pins for the three 「创建工作树」 entry points and the copy they share
 * (`workspaces/create.ts`, `new-session/workspace/selector.tsx:152-163`,
 * `session/timeline/session-workspace-menu.tsx:103-127`,
 * `shell/commands/palette.ts:40`).
 *
 * The rendering itself is a Compose modal (`ui/workspace/WorktreeCreateDialog`),
 * which these tests cannot mount; they pin the wiring instead — every call site
 * must reach the shared create, and no call site may keep the old
 * "pick a directory instead" stand-in.
 */
class WorktreeCreateWiringTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun i18n(locale: String): String =
        source("src/main/res/$locale/strings_oc_i18n.xml")

    private fun stringValue(locale: String, key: String): String =
        Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(i18n(locale))
            ?.groupValues
            ?.get(1)
            ?: error("$locale missing $key")

    // ------------------------------------------------------- new-session stop

    @Test
    fun theNewSessionSelectorOffersTheCreateStop() {
        val body = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/NewSessionScreen.kt")
        // `selector.tsx:152-163`: the create stop of the `Run session in` group.
        assertTrue(body.contains("R.string.session_new_worktree_create"))
        assertTrue(body.contains("onCreateWorktree = { worktreeCreateOpen = true }"))
        assertTrue(body.contains("onCreateWorktree: () -> Unit"))
    }

    @Test
    fun theCreatedWorktreeJoinsTheMenuAndBecomesTheSelection() {
        val body = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/NewSessionScreen.kt")
        assertTrue(body.contains("WorktreeCreateDialog("))
        // The created directory is both listed and selected, so the next
        // 「Create session」 drafts inside it.
        assertTrue(body.contains("worktrees = (worktrees + created).distinct()"))
        assertTrue(body.contains("selected = created"))
    }

    // ------------------------------------------------------------ move menu

    @Test
    fun theSessionHeaderCarriesTheCreateBesideMove() {
        val body = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt")
        // `session-workspace-menu.tsx:103-127`: `workspace.new` next to Local /
        // 移动到… in the same group.
        assertTrue(body.contains("R.string.oc_move_session"))
        assertTrue(body.contains("R.string.workspace_new"))
        assertTrue(body.contains("onCreateWorktree()"))
        assertTrue(body.contains("WorktreeCreateDialog("))
        // `move("create")` = create, then move the tracked session in.
        assertTrue(body.contains("moveSessionTo(created)"))
    }

    // ---------------------------------------------------------------- palette

    @Test
    fun thePaletteRowCreatesInsteadOfAskingForADirectory() {
        val body = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
        assertTrue(body.contains("import com.xiufeigo.opencodeshell.ui.workspace.WorktreeCreateDialog"))
        // The palette's `workspace.new` row now runs the real create and drafts
        // in the created worktree.
        val start = body.indexOf("val onNewWorkspace: () -> Unit = {")
        assertTrue(start > 0)
        val block = body.substring(start, body.indexOf("\n    val openSession:", start))
        assertTrue(block.contains("WorktreeCreateDialog("))
        assertTrue(block.contains("Route.newSession(directory)"))
    }

    // ------------------------------------------------------------------- copy

    @Test
    fun theCreateCopyIsAvailableInEveryLocale() {
        // The preparing line is the only key this delivery had to add; the
        // other two were already translated (unlike their English-only
        // siblings `session_new_worktree_draftPlaceholder`).
        for (key in listOf(
            "session_new_worktree_creating",
            "session_new_worktree_create",
            "workspace_new",
            "workspace_error_stillPreparing",
            "prompt_toast_worktreeCreateFailed_title",
            "session_new_workspace_fromBranch",
            "session_new_workspace_branch_search_placeholder",
        )) {
            val english = stringValue("values", key)
            assertTrue("values/$key is empty", english.isNotBlank())
            for (locale in listOf("values-zh-rCN", "values-zh-rTW")) {
                assertNotEquals(
                    "$locale/$key is still the English copy",
                    english,
                    stringValue(locale, key),
                )
            }
        }
    }

    // ---------------------------------------------------------------- api use

    @Test
    fun theCreateVerbHasItsCaller() {
        // `V2Api.worktreeCreate` (`V2ApiMissingVerbs.kt:151`) was API-only before
        // this delivery; the shared model is its single caller.
        val model = source("src/main/java/com/xiufeigo/opencodeshell/ui/workspace/WorktreeCreate.kt")
        assertTrue(model.contains("api.worktreeCreate("))
        assertTrue(model.contains("api.projects()"))
        assertTrue(model.contains("api.vcsBranches("))
    }
}
