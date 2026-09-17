package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SystemBarsJsTest {
    @Test
    fun observerReportsOfficialColorSchemeToBridge() {
        val js = SystemBarsJs.INSTALL
        assertTrue(js.contains("data-color-scheme") || js.contains("colorScheme"))
        assertTrue(js.contains("prefers-color-scheme"))
        assertTrue(js.contains("theme-color"))
        assertTrue(js.contains("setColorScheme"))
        assertTrue(js.contains("MutationObserver"))
        val src = File("src/main/java/com/xiufeigo/opencodeshell/MainActivity.kt").readText()
        assertTrue(src.contains("SystemBarsJs.INSTALL"))
        assertTrue(src.contains("addDocumentStartJavaScript"))
    }
}
