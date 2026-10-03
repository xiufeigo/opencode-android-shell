package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.HomeRowMenu
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v83 P1 (emulator-5558 `11_rename_t2.png` → `13_rename_t34.png`): Home 行改名
 * 失败后只有一闪即逝的 toast，行内 draft 停在已输入的新名（看着像成功），离开
 * 页面才恢复。
 *
 * Two halves, both on the Home surface:
 *
 * 1. the durable report. v81 gave each rename outcome its own sentence and v82
 *    parked it on a card for the **session route** — Home's row editor kept the
 *    `Toast.LENGTH_LONG` only, which is the 4 s window the v82 log measured dying
 *    0.9 s before a screenshot. Home now parks the same text on the same card.
 * 2. the draft. `HomeRowMenu.resolveSave` re-armed the editor on a failure but
 *    kept the typed name, so the inline editor showed a title the server never
 *    accepted — the shape of a rename that worked — until the user left Home and
 *    the row was rebuilt from a catalogue read.
 *
 * v84 P1 superseded the *shape* of (2) while keeping its intent: the failure no
 * longer re-arms the editor at all, because a phone cannot leave one (see
 * [HomeRenameExitV84Test] for the measured trap). What this class still pins is
 * the durable report and the rule that the draft never outlives the answer.
 */
class HomeRenameFailureV83Test {

    // ------------------------------------------------ the draft's rules

    @Test
    fun `a resolved save closes the editor on success`() {
        val open = HomeRowMenu.openEditor("old", "ses_1")
        val (saving, _) = HomeRowMenu.submit(HomeRowMenu.editDraft(open, "ses_1", "new")!!, "old")!!
        assertNull(HomeRowMenu.resolveSave(saving, "ses_1", saved = true))
    }

    @Test
    fun `a refused save ends the edit — the unconfirmed name is gone with it`() {
        val open = HomeRowMenu.openEditor("old", "ses_1")
        val (saving, action) = HomeRowMenu.submit(HomeRowMenu.editDraft(open, "ses_1", "E2ECHK5558-gd")!!, "old")!!
        assertEquals(HomeRowMenu.Submit.Save("E2ECHK5558-gd"), action)

        // v84 P1: closed, not re-armed with the server's title. The row the
        // editor was replacing renders `row.title` again — the catalogue's own
        // label, which a failed rename never mirrors over (see
        // [HomeRenameExitV84Test] for the host-side pin).
        assertNull(
            "the inline editor must not survive the answer",
            HomeRowMenu.resolveSave(saving!!, "ses_1", saved = false),
        )
    }

    @Test
    fun `an answer for another row moves nothing`() {
        val open = HomeRowMenu.openEditor("old", "ses_1")
        val (saving, _) = HomeRowMenu.submit(HomeRowMenu.editDraft(open, "ses_1", "new")!!, "old")!!
        assertEquals(saving, HomeRowMenu.resolveSave(saving, "ses_2", saved = false))
        assertEquals(saving, HomeRowMenu.resolveSave(saving, "ses_2", saved = true))
    }

    // ------------------------------------------------ the wiring that cannot run here

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val homeScreen = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")
    private val list = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")
    private val menu = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeRowMenu.kt")
    private val actions = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeRowActions.kt")

    @Test
    fun `home parks the refusal on the same card the session route uses`() {
        assertTrue(
            "the sentence is the shared helper's (`renameFailureRes`' own wording)",
            homeScreen.contains("renameFailureMessage = renameFailureText(context, outcome)"),
        )
        assertTrue(
            "the toast stays (the transient half)",
            homeScreen.contains("renameFailureToast(context, outcome)"),
        )
        assertTrue(
            "and the card is the session route's own — one card, two surfaces",
            homeScreen.contains("import com.xiufeigo.opencodeshell.ui.session.drawer.RenameFailureDialog") &&
                homeScreen.contains("RenameFailureDialog(") &&
                homeScreen.contains("onDismiss = { renameFailureMessage = null }"),
        )
        assertTrue(
            "parked in a `remember`, not a dialogs-global: it belongs to this screen",
            homeScreen.contains("var renameFailureMessage by remember { mutableStateOf<String?>(null) }"),
        )
    }

    @Test
    fun `the row editor asks the answer's own title back`() {
        val submit = list.substringAfter("is HomeRowMenu.Submit.Save -> onRenameSession?.invoke(")
            .substringBefore("onRenameCancel =")
        assertTrue(
            "the row's server title is the label the draft is compared against",
            list.contains("HomeRowMenu.submit(rowEditor, row.title)"),
        )
        assertTrue(
            "and the one resolution runs — it now ends the edit on either answer",
            submit.contains("HomeRowMenu.resolveSave(") && submit.contains("saved,"),
        )
        assertTrue(
            "the rule lives in the pure menu state: no path keeps the editor open",
            menu.contains("fun resolveSave(current: Editor?, sessionId: String, saved: Boolean): Editor?") &&
                menu.contains("if (current?.sessionId != sessionId) return current\n        return null"),
        )
        assertTrue(
            "and the host's own contract says so, where the two surfaces meet",
            actions.contains("a failure leaves the row on the title the server still"),
        )
    }
}
