package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v82 BUG-B (emulator-5558): 坏目录会话改名后标题回滚**无任何提示**.
 *
 * The report's hypothesis was a missing shape branch (`500` not landing in
 * `Refused(500)`). The device evidence says otherwise, and the difference
 * matters — the fix is *where the sentence lives*, not what it says:
 *
 * ```
 * 09:04:05/09:04:38  server log: PATCH /api/session/ses_f0e85a432… http.status=500
 *                    (cause: FileSystem.realPath (/mnt/c/nonexist_v82) … ENOENT)
 * 09:04:06.324       app log:    rename session ses_f0e85a432… refused by the server: HTTP 500
 * 09:04:06.363       WindowManager: setClientSurface … VRI-Toast#3476 … for … Toast
 * 09:04:10.453       NotificationService: Toast already killed. pkg=com.xiufeigo.opencodeshell
 * 09:04:11.398       research/v82_verify/5558/35_ghost_rename_err.png   ← 0.9 s too late
 * ```
 *
 * The shape routing worked, the toast was raised with the v81 REFUSED sentence,
 * and `LENGTH_LONG` killed it before the screenshot. So the durable half is
 * pinned here: the session route keeps the sentence on a card the reader
 * dismisses ([com.xiufeigo.opencodeshell.ui.session.drawer.RenameFailureDialog]),
 * and the one surface that really was silent — the tab strip's own rename, which
 * handled `RENAMED` and `GONE` and nothing else — reports like every other
 * surface does.
 */
class RenameFailureDurableV82Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
    private val app = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
    private val text = source("src/main/java/com/xiufeigo/opencodeshell/ui/RenameFailureText.kt")
    private val dialogs =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/drawer/SessionRowDialogs.kt")

    // ------------------------------------------------- 1. the durable half

    @Test
    fun theRouteParksTheSentenceUntilItIsDismissed() {
        assertTrue(
            "the route owns the notice",
            screen.contains("var renameFailureMessage by remember(sessionId) { mutableStateOf<String?>(null) }"),
        )
        assertTrue(
            "a session switch clears it (keyed on the route's session)",
            screen.contains("remember(sessionId) { mutableStateOf<String?>(null) }"),
        )
        val inline = screen.substringAfter("onRename = { newTitle ->")
            .substringBefore("onBack = onBack,")
        assertTrue(
            "the header's inline rename parks the refusal",
            inline.contains("renameFailureMessage = renameFailureText(context, outcome)"),
        )
        val menu = screen.substringAfter("SessionRenameDialog(")
            .substringBefore("v69 P2 (5560")
        assertTrue(
            "so does the ⋮ menu's rename dialog",
            menu.contains("renameFailureMessage = renameFailureText(context, outcome)"),
        )
        assertTrue(
            "and the card renders it with a dismiss",
            screen.contains("RenameFailureDialog(") &&
                screen.contains("onDismiss = { renameFailureMessage = null }"),
        )
    }

    @Test
    fun theCardSaysTheSameThingAsTheToastAndHasNoNewCopy() {
        assertTrue(dialogs.contains("fun RenameFailureDialog("))
        val card = dialogs.substringAfter("fun RenameFailureDialog(")
            .substringBefore("/**\n * Destructive confirmation")
        assertTrue("the sentence is the caller's (renameFailureRes' own)", card.contains("message: String,"))
        assertTrue(
            "one way out, and it is a dismissal",
            card.contains("stringResource(R.string.oc_common_close)") &&
                card.contains("onClick = onDismiss"),
        )
        assertTrue(
            "the card never raises a toast — the toast is the other, transient half",
            !card.contains("Toast.makeText"),
        )
    }

    @Test
    fun theTextHelperIsTheToastHelperWithoutTheToast() {
        val helper = text.substringAfter("internal fun renameFailureText(")
        assertTrue(
            "one resource table, so the two halves cannot drift",
            helper.contains("renameFailureRes(outcome)?.let { context.getString(it) }"),
        )
        assertTrue(
            "a success still reports nothing",
            text.contains("SessionRenameOutcome.RENAMED -> null"),
        )
        assertTrue(
            "the toast keeps its LENGTH_LONG (v81 pin)",
            text.contains("Toast.LENGTH_LONG"),
        )
    }

    // ------------------------------------------------- 2. the silent branch

    @Test
    fun theTabStripsRenameReportsItsFailure() {
        val strip = app.substringAfter("val onRenameSessionTab: (SessionTabRef, String) -> Unit = { tab, title ->")
            .substringBefore("// `tab.reopenClosed`")
        assertTrue(
            "the strip handled only RENAMED/GONE: a refusal left the old name and said nothing",
            strip.contains("renameFailureToast(appContext, outcome)"),
        )
        assertTrue(
            "the reporting rides the same decision the rename itself took",
            strip.contains("forgetDeletedSessionTab(tab.id)"),
        )
        assertTrue(
            "and it is the shared helper, so it says which shape it was",
            strip.contains("renameFailureToast"),
        )
    }
}
