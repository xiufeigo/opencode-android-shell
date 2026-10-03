package com.xiufeigo.opencodeshell.ui.home

import com.xiufeigo.opencodeshell.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v49 tD_59→61 — the armed confirm's dead row.
 *
 * The first tap on 「选择此目录」 swaps the browser body for the full pending
 * path (tail emphasised), but that row only *displayed* the path: tapping it
 * did nothing, while the footer button kept its first-stage label, so nothing
 * told the user the card was armed and waiting — the second tap had to land
 * on the button again. Now the whole row is the second tap (it submits through
 * the same [tapConfirm] action as the footer button) and the armed footer
 * names the submit ([pickerConfirmLabelRes]).
 *
 * Two-stage semantics are unchanged: the first tap only ever arms, and only
 * an armed second action submits — pinned below, not just in
 * [DirectoryPickerConfirmTest].
 */
class DirectoryPickerConfirmRowTest {

    private fun picker(): String =
        listOf(
            File("src/main/java/com/xiufeigo/opencodeshell/ui/home/DirectoryPickerDialog.kt"),
            File("app/src/main/java/com/xiufeigo/opencodeshell/ui/home/DirectoryPickerDialog.kt"),
        ).firstOrNull { it.isFile }?.readText() ?: error("missing DirectoryPickerDialog.kt")

    private fun stringValue(dir: String, key: String): String {
        val file = listOf(
            File("src/main/res/$dir/strings.xml"),
            File("app/src/main/res/$dir/strings.xml"),
        ).firstOrNull { it.isFile } ?: error("missing $dir/strings.xml")
        return Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())?.groupValues?.get(1) ?: error("$dir missing $key")
    }

    // ------------------------------------------------------- the staged label

    @Test
    fun unarmedFooterInvitesTheFirstTap() {
        assertEquals(R.string.oc_home_projects_select_directory, pickerConfirmLabelRes(false))
    }

    @Test
    fun armedFooterNamesTheSubmit() {
        assertEquals(R.string.oc_home_projects_confirm_directory, pickerConfirmLabelRes(true))
    }

    @Test
    fun footerRendersTheStagedLabel() {
        assertTrue(
            "footer label follows the arm state",
            picker().contains("stringResource(pickerConfirmLabelRes(confirming != null))"),
        )
    }

    @Test
    fun armedLabelIsTranslatedEverywhere() {
        assertEquals("Confirm this directory", stringValue("values", "oc_home_projects_confirm_directory"))
        assertEquals("确认选择此目录", stringValue("values-zh-rCN", "oc_home_projects_confirm_directory"))
        assertEquals("確認選擇此目錄", stringValue("values-zh-rTW", "oc_home_projects_confirm_directory"))
    }

    // ------------------------------------------------- the row is a submitter

    @Test
    fun firstTapStillOnlyArms() {
        assertEquals(
            DirectoryConfirm.Armed("/home/xiufeigo/research"),
            confirmStep(null, "/home/xiufeigo/research"),
        )
    }

    @Test
    fun armedRowTapSubmitsExactlyTheDisplayedPath() {
        // The row only exists while armed, so its tap is always the second
        // action: it submits the armed (displayed) path, never a re-resolve.
        val armed = confirmStep(null, "/home/xiufeigo/research") as DirectoryConfirm.Armed
        assertEquals(DirectoryConfirm.Submit(armed.path), confirmStep(armed.path, armed.path))
    }

    @Test
    fun rowAndFooterShareOneSubmitAction() {
        val src = picker()
        // Both entry points run the one local action, so the row tap and the
        // button tap can never disagree on what the second tap submits.
        assertTrue("action exists", src.contains("fun tapConfirm() {"))
        val taps = Regex("""onClick = \{ tapConfirm\(\) \}""").findAll(src).toList()
        assertEquals("confirm body + footer button, nothing else", 2, taps.size)
    }
}
