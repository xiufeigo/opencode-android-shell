package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.SettingsTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-13: the model picker's「管理模型」entry.
 *
 * Upstream anchor: `providers/models/select-dialog.tsx:544-556` — the last block
 * of the model select dialog is one full-width button (`border-t`, `h-9`,
 * `outline-sliders` + `dialog.model.manage`) whose `onManage` opens the model
 * manager (visibility toggles + provider connect). Android has that manager on
 * the Settings Models page, so the row navigates there instead of duplicating it.
 *
 * The pure half (the item tables) is covered by `ModelPickerTest`; what is
 * asserted here is the entry itself: the copy exists in all three locales, the
 * row is wired through the composer chain, and the app opens Settings on Models.
 */
class ModelPickerManageEntryTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    @Test
    fun `the manage row is drawn with upstream's copy and icon`() {
        val picker = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelPicker.kt")
        assertTrue("upstream label", picker.contains("R.string.dialog_model_manage"))
        assertTrue("outline-sliders", picker.contains("SettingsIcon.SLIDERS"))
        assertTrue("h-9 button", picker.contains("MANAGE_ROW_HEIGHT"))
        assertTrue("only when the host offers the route", picker.contains("if (onManageModels != null)"))
    }

    @Test
    fun `the copy ships translated in all three locales`() {
        val en = source("src/main/res/values/strings_oc_i18n.xml")
        val zh = source("src/main/res/values-zh-rCN/strings_oc_i18n.xml")
        val zht = source("src/main/res/values-zh-rTW/strings_oc_i18n.xml")
        val key = "name=\"dialog_model_manage\""
        for ((dir, xml) in listOf("en" to en, "zh" to zh, "zht" to zht)) {
            assertTrue("$dir has the key", xml.contains(key))
        }
        // zh/zht are translations, not the English copy shipped three times.
        val value = Regex("""<string name="dialog_model_manage">(.*?)</string>""")
        val enText = value.find(en)?.groupValues?.get(1)
        assertEquals("Manage models", enText)
        assertTrue("zh translated", value.find(zh)?.groupValues?.get(1) != enText)
        assertTrue("zht translated", value.find(zht)?.groupValues?.get(1) != enText)
    }

    @Test
    fun `the row travels the composer chain to the app`() {
        val chip = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelChip.kt")
        assertTrue("chip closes its menu first", chip.contains("expanded = false") && chip.contains("manage()"))
        assertTrue("chip passes it on", chip.contains("onManageModels = onManageModels?.let"))

        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
        assertTrue("composer parameter", composer.contains("onManageModels: (() -> Unit)? = null"))
        assertTrue("composer forwards it", composer.contains("onManageModels = onManageModels,"))

        val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        assertTrue("screen parameter", screen.contains("onManageModels: (() -> Unit)? = null"))
        assertTrue("screen forwards it", screen.contains("onManageModels = onManageModels,"))
    }

    @Test
    fun `the app opens the settings dialog on the Models page`() {
        val app = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
        assertTrue(
            "the picker's row lands on Models",
            app.contains("initialTab = SettingsTab.MODELS,"),
        )
        assertTrue("and the dialog takes the page", app.contains("initialTab: SettingsTab = SettingsTab.Default,"))
        assertTrue("reuses the existing dialog opener", app.contains("showSettingsDialog("))

        val dialog = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsScreen.kt")
        assertTrue("dialog parameter", dialog.contains("initialTab: SettingsTab = SettingsTab.Default,"))
        assertTrue("and opens on it", dialog.contains("mutableStateOf(initialTab.value)"))
        // The Models page is a real tab of that dialog.
        assertEquals("models", SettingsTab.MODELS.value)
    }

    @Test
    fun `the row is navigation only`() {
        // No visibility switch / connect UI is copied into the picker: the row
        // hands the tap to the host, exactly like upstream's `onManage`, and the
        // host opens the page that already owns that UI.
        val picker = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelPicker.kt")
        assertTrue(
            "the row's only action is the callback",
            picker.contains(".clickable(role = Role.Button, onClick = onManageModels)"),
        )
        val app = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
        val wiring = app.substringAfter("onManageModels = {").substringBefore("},")
        assertTrue("the host opens the existing dialog", wiring.contains("showSettingsDialog("))
        assertTrue("…on the Models page", wiring.contains("initialTab = SettingsTab.MODELS"))
    }
}
