package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsPolishTest {
    private fun patch(): File =
        listOf(
            File("../frontend/patches/android-webview-z-settings-polish.patch"),
            File("../../frontend/patches/android-webview-z-settings-polish.patch"),
            File("frontend/patches/android-webview-z-settings-polish.patch"),
        ).firstOrNull { it.isFile } ?: error("missing settings polish patch")

    @Test
    fun accordionHeadersMatchNavIconLabelAndDefaultCollapsed() {
        val text = patch().readText()
        assertTrue(text.contains("settings-v2-section-title-main"))
        assertTrue(text.contains("createMediaQuery(\"(max-width: 839px)\")"))
        assertTrue(text.contains("icon=\"sliders\""))
        assertTrue(text.contains("white-space: nowrap"))
        assertTrue(text.contains("settings-v2-tab-header [data-component=\"button-v2\"]"))
        assertTrue(text.contains("details.settings-v2-section:not([open])"))
        assertTrue(text.contains("KeybindV2"))
        assertTrue(text.contains("settings-v2-shortcut-row"))
    }

    @Test
    fun vendoredCssKeepsNavLikeAccordionAndUnwrappedShortcutsTitle() {
        val html = File("src/main/assets/opencode-app/index.html").readText()
        val cssName =
            Regex("""href="\./assets/(index-[^"]+\.css)"""").find(html)?.groupValues?.get(1)
                ?: error("missing entry stylesheet in index.html")
        val css = File("src/main/assets/opencode-app/assets/$cssName").readText()
        assertTrue(css.contains("settings-v2-section-title-main"))
        assertTrue(
            "collapsed accordion CSS must target details only",
            css.contains("details.settings-v2-section:not([open])"),
        )
        assertTrue(css.contains("settings-v2-tab-header [data-component=\"button-v2\"]") || css.contains("settings-v2-tab-header [data-component=button-v2]"))
        val jsName =
            Regex("""src="\./assets/(index-[^"]+\.js)"""").find(html)?.groupValues?.get(1)
                ?: error("missing entry script in index.html")
        val assets = File("src/main/assets/opencode-app/assets")
        val jsFiles = assets.listFiles { _, name -> name.endsWith(".js") }?.toList().orEmpty()
        assertTrue(
            "section title markup must ship",
            jsFiles.any { it.readText().contains("settings-v2-section-title-main") || it.readText().contains("section-title-main") },
        )
        val keybinds =
            jsFiles.any { it.readText().contains("settings-v2-shortcut-row") }
        assertTrue("shortcuts must render KeybindV2 keycaps", keybinds)
    }
}
