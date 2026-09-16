package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BootPathTest {
    @Test
    fun vendoredBundleUsesDesktopMemoryRouterOnAssetHost() {
        val html = File("src/main/assets/opencode-app/index.html").readText()
        val jsName =
            Regex("""src="\./assets/(index-[^"]+\.js)"""").find(html)?.groupValues?.get(1)
                ?: error("missing entry script in index.html")
        val js = File("src/main/assets/opencode-app/assets/$jsName").readText()
        assertTrue("asset host must gate Android bootstrap", js.contains("appassets.androidplatform.net"))
        assertTrue("zero-server stub must remain", js.contains("http://127.0.0.1:0"))
        assertTrue("empty home must expose Settings", js.contains("home-open-settings"))
        val marker = "opencode.global.dat:server"
        val idx = js.indexOf(marker)
        assertTrue("missing global server store key", idx >= 0)
        val window = js.substring(idx, (idx + 900).coerceAtMost(js.length))
        assertTrue("entry must bind MemoryRouter history on the asset host\n$window", window.contains("{history:"))
        assertTrue(window.contains("appassets.androidplatform.net"))
    }

    @Test
    fun vendoredCssStacksSettingsBelow840px() {
        val html = File("src/main/assets/opencode-app/index.html").readText()
        val cssName =
            Regex("""href="\./assets/(index-[^"]+\.css)"""").find(html)?.groupValues?.get(1)
                ?: error("missing entry stylesheet in index.html")
        val css = File("src/main/assets/opencode-app/assets/$cssName").readText()
        assertTrue("portrait Settings breakpoint missing", css.contains("839px"))
        assertTrue(css.contains("flex-direction:column") || css.contains("flex-direction: column"))
        assertTrue("portrait must unstick the category tab header", css.contains(".settings-v2-panel .settings-v2-tab-header"))
        assertTrue("portrait section titles must be sticky", css.contains("summary.settings-v2-section-title"))
        val assets = File("src/main/assets/opencode-app/assets")
        val accordionJs =
            assets.listFiles { _, name -> name.startsWith("index-") && name.endsWith(".js") }
                ?.any { it.readText().contains("details class=settings-v2-section") }
                ?: false
        assertTrue("General sections must be <details> accordions", accordionJs)
    }
}
