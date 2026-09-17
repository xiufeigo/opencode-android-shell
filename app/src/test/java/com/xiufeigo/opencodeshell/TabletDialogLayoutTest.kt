package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TabletDialogLayoutTest {
    private fun patch(): File =
        listOf(
            File("../frontend/patches/android-webview-z-tablet-dialog.patch"),
            File("../../frontend/patches/android-webview-z-tablet-dialog.patch"),
            File("frontend/patches/android-webview-z-tablet-dialog.patch"),
        ).firstOrNull { it.isFile } ?: error("missing tablet dialog patch")

    @Test
    fun patchScopesImeDialogCssAwayFromSettingsAndPalette() {
        val text = patch().readText()
        assertTrue(text.contains("android-tablet-dialog-layout"))
        assertTrue(text.contains(":not([data-variant=\"settings\"]):not(:has(.command-palette-v2))"))
        assertTrue(text.contains("createMediaQuery(\"(max-width: 639px)\")"))
        assertTrue(text.contains("align-items: flex-start"))
        assertTrue(text.contains("margin-top: max(48px, calc((100vh - 480px) / 2))"))
        assertTrue(text.contains("min(calc(100vw - 32px), 980px)"))
        assertTrue(text.contains(":not([data-variant=\"settings\"])"))
    }

    @Test
    fun vendoredCssCentersPaletteAndWidensTabletSettings() {
        val html = File("src/main/assets/opencode-app/index.html").readText()
        val cssName =
            Regex("""href="\./assets/(index-[^"]+\.css)"""").find(html)?.groupValues?.get(1)
                ?: error("missing entry stylesheet in index.html")
        val css = File("src/main/assets/opencode-app/assets/$cssName").readText()
        assertTrue(css.contains(":has(.command-palette-v2)"))
        assertTrue(
            css.contains("980px") && (css.contains("100vw - 32px") || css.contains("calc(100vw - 32px)")),
        )
        val assets = File("src/main/assets/opencode-app/assets")
        val jsFiles = assets.listFiles { _, name -> name.endsWith(".js") }?.toList().orEmpty()
        assertTrue(
            "accordion must collapse only on phones",
            jsFiles.any { it.readText().contains("max-width: 639px") || it.readText().contains("max-width:639px") },
        )
    }
}
