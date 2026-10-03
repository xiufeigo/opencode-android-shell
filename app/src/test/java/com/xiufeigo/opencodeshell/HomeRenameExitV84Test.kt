package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.HomeRowMenu
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v84 P1 (emulator-5558 `research/v84_verify/5558/`): a failed rename on a bad
 * directory session left the row **in the editor forever**.
 *
 * The measured pair, 2/2:
 *
 * * `11_rename_result.png` / `16_rename2_result.png` — the persistent card is up
 *   (that half of v83 worked: `Something went wrong` / `Not renamed — the server
 *   returned an error for this session…`), and the row still shows the typed name
 *   `E2ECHK5558-renamed2` instead of the server's `E2ECHK5558-rename`.
 * * `12_rollback.png` → `13_rollback_10s.png` (10 s apart, identical apart from
 *   the clock) — the row is *still* the editor with the keyboard down.
 * * `14_lp2.png` — a long-press on it gives the platform's text-selection bar
 *   (Copy / Paste / Cut / Select all) instead of the session menu, so 改名 /
 *   导出 / 导入 / 删除 are all unreachable.
 *
 * Two independent defects produced that state:
 *
 * 1. **the field never adopted a host-side draft rewrite.** It seeds its
 *    `TextFieldValue` once, under `remember(editor.sessionId)`, and is written by
 *    the IME afterwards — so v83's rollback (`resolveSave` returning an open
 *    editor on the server's title) existed in the model and nowhere on screen.
 * 2. **nothing on a phone could close the editor.** Upstream keeps it open on a
 *    failure because a desktop can leave it (`Esc`, `onBlur={closeEditor}`); this
 *    shell's field *commits* on blur (v43) and a soft keyboard has no Esc.
 *
 * The fix is one rule instead of two patches: a resolved answer **ends the
 * edit**, and the row it was replacing renders the catalogue's own title again.
 * The rollback is therefore structural rather than a draft write the field could
 * ignore — a failed rename never mirrors a title (`HomeScreen` writes
 * `renamedTitles` only on `RENAMED`), so the label that comes back is the
 * server's.
 */
class HomeRenameExitV84Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val menu = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeRowMenu.kt")
    private val list = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")
    private val homeScreen = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")

    // ------------------------------------------- no answer leaves the edit up

    @Test
    fun `every resolved answer ends the editor`() {
        val open = HomeRowMenu.openEditor("E2ECHK5558-rename", "ses_bad")
        val typed = HomeRowMenu.editDraft(open, "ses_bad", "E2ECHK5558-renamed2")!!
        val (saving, action) = HomeRowMenu.submit(typed, "E2ECHK5558-rename")!!
        assertEquals(HomeRowMenu.Submit.Save("E2ECHK5558-renamed2"), action)

        // The host collapses the whole v81 outcome set (`RENAMED`, `GONE`,
        // `REFUSED`, `NOT_CONFIRMED`, `UNREACHABLE`, `NOT_SENT`) into this
        // boolean, so both values are the exhaustive check.
        assertNull("a landed rename ends the edit", HomeRowMenu.resolveSave(saving, "ses_bad", saved = true))
        assertNull(
            "and a refused one does too — this is the state the device could not leave",
            HomeRowMenu.resolveSave(saving, "ses_bad", saved = false),
        )
    }

    @Test
    fun `a late answer for a row that is gone moves nothing`() {
        // The id is the guard that keeps one row's answer off another's editor;
        // it is the *only* thing `resolveSave` may keep.
        val open = HomeRowMenu.openEditor("old", "ses_1")
        val (saving, _) = HomeRowMenu.submit(HomeRowMenu.editDraft(open, "ses_1", "new")!!, "old")!!
        assertEquals(saving, HomeRowMenu.resolveSave(saving, "ses_2", saved = false))
        assertNull(HomeRowMenu.resolveSave(null, "ses_1", saved = false))
    }

    // ------------------------------------------- the row's own label comes back

    @Test
    fun `the host mirrors a rename only on success, so the row shows the server's title`() {
        assertTrue(
            "a failed rename must not write the row's title mirror",
            homeScreen.contains("if (renamed) renamedTitles = renamedTitles + (id to title)"),
        )
        assertTrue(
            "the tombstone is the other half (`GONE` drops the row)",
            homeScreen.contains("if (outcome == SessionRenameOutcome.GONE) deletedSessions = deletedSessions + id"),
        )
    }

    @Test
    fun `the row editor hands the commit back through the one resolution`() {
        val submit = list.substringAfter("is HomeRowMenu.Submit.Save -> onRenameSession?.invoke(")
            .substringBefore("onRenameCancel =")
        assertTrue(
            "the answer is applied through `resolveSave` — the one place that decides",
            submit.contains("rowEditor = HomeRowMenu.resolveSave(") && submit.contains("saved,"),
        )
        assertTrue(
            "the no-op path closes, and the label the draft is compared against is the row's own",
            list.contains("HomeRowMenu.Submit.Noop -> rowEditor = null") &&
                list.contains("HomeRowMenu.submit(rowEditor, row.title)"),
        )
    }

    // ------------------------------------------- an editor over a row that left

    @Test
    fun `an editor whose row left the list is pruned`() {
        val open = HomeRowMenu.openEditor("old", "ses_1")
        assertEquals(open, HomeRowMenu.pruneEditor(open, setOf("ses_1", "ses_2")))
        assertNull(
            "the row is gone (`GONE`, a delete from another surface, a scope change) — so is its editor",
            HomeRowMenu.pruneEditor(open, setOf("ses_2")),
        )
        assertNull("nothing open stays nothing open", HomeRowMenu.pruneEditor(null, emptySet()))
    }

    @Test
    fun `the list root prunes against the rows it renders`() {
        assertTrue(
            "the membership signature is the trigger",
            list.contains("val rowIds = remember(rows) { rows.mapTo(HashSet()) { it.id } }") &&
                list.contains("LaunchedEffect(rowIds) { rowEditor = HomeRowMenu.pruneEditor(rowEditor, rowIds) }"),
        )
    }

    // ------------------------------------------- the phone's own exit

    @Test
    fun `the editor answers BACK with a discard`() {
        val editor = list.substringAfter("private fun HomeSessionRenameEditor(")
        assertTrue(
            "BACK discards the editor — the one exit a soft keyboard leaves (Esc / a blur that closes are both gone)",
            editor.contains("BackHandler { cancel() }"),
        )
        assertTrue(
            "…and `cancel` is the discard the Esc key takes, not a commit",
            editor.contains("fun cancel() {\n        if (gate.cancel()) onCancel()\n    }"),
        )
    }

    // ------------------------------------------- the rule, in the pure state

    @Test
    fun `the pure state is the only place that decides`() {
        assertTrue(
            "one signature, no `serverTitle` to fall back on",
            menu.contains("fun resolveSave(current: Editor?, sessionId: String, saved: Boolean): Editor?") &&
                !menu.contains("serverTitle"),
        )
        assertTrue(
            "and the body says the id is all it may keep",
            menu.contains("if (current?.sessionId != sessionId) return current\n        return null"),
        )
    }
}
