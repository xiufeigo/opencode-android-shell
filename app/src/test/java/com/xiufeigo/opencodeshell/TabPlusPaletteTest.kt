package com.xiufeigo.opencodeshell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TabPlusPaletteTest {
    private fun patch(): File =
        listOf(
            File("../frontend/patches/android-webview-z-tab-plus.patch"),
            File("../../frontend/patches/android-webview-z-tab-plus.patch"),
            File("frontend/patches/android-webview-z-tab-plus.patch"),
        ).firstOrNull { it.isFile } ?: error("missing tab-plus patch")

    @Test
    fun patchWiresPlusToFileOpenPaletteByDefault() {
        val text = patch().readText()
        assertTrue(text.contains("titlebar-tab-plus"))
        assertTrue(text.contains("openTitlebarTabPlus"))
        assertTrue(text.contains("file.open"))
        assertTrue(text.contains("settings-tab-plus-creates-session"))
        assertTrue(text.contains("opencode.android.tabPlusCreatesSession"))
        assertTrue(text.contains("Plus button creates a new session"))
        assertTrue(text.contains("「+」新建会话"))
        assertFalse("default must not persist true", text.contains("localStorage?.setItem(ANDROID_TAB_PLUS_STORAGE_KEY, \"true\") &&"))
    }

    @Test
    fun vendoredUiKeepsTabPlusAndSettingsToggle() {
        val html = File("src/main/assets/opencode-app/index.html").readText()
        val jsName =
            Regex("""src="\./assets/(index-[^"]+\.js)"""").find(html)?.groupValues?.get(1)
                ?: error("missing entry script in index.html")
        val assets = File("src/main/assets/opencode-app/assets")
        val jsFiles = listOf(File(assets, jsName)) + (assets.listFiles { _, name -> name.endsWith(".js") }?.toList().orEmpty())
        fun bundled(needle: String) = jsFiles.any { it.isFile && needle in it.readText() }
        assertTrue("plus button must expose data-action", bundled("titlebar-tab-plus"))
        assertTrue("settings toggle must exist", bundled("settings-tab-plus-creates-session"))
        assertTrue("preference key must persist in localStorage", bundled("opencode.android.tabPlusCreatesSession"))
        assertTrue("must trigger official file.open palette", bundled("file.open"))
    }
}
