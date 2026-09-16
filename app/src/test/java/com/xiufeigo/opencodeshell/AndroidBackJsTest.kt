package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AndroidBackJsTest {
    @Test
    fun parseWebViewEvaluateJavascriptEncoding() {
        assertEquals(AndroidBackJs.OVERLAY, AndroidBackJs.parse("\"overlay\""))
        assertEquals(AndroidBackJs.NAV, AndroidBackJs.parse("\"nav\""))
        assertEquals(AndroidBackJs.ROOT, AndroidBackJs.parse("\"root\""))
        assertEquals(AndroidBackJs.ROOT, AndroidBackJs.parse("null"))
        assertEquals(AndroidBackJs.ROOT, AndroidBackJs.parse(null))
        assertEquals(AndroidBackJs.OVERLAY, AndroidBackJs.parse("overlay"))
    }

    @Test
    fun backLeavesAppOnlyAtRootWithoutHistory() {
        assertFalse(AndroidBackJs.shouldLeaveApp(AndroidBackJs.OVERLAY, false))
        assertFalse(AndroidBackJs.shouldLeaveApp(AndroidBackJs.OVERLAY, true))
        assertFalse(AndroidBackJs.shouldLeaveApp(AndroidBackJs.NAV, false))
        assertFalse(AndroidBackJs.shouldLeaveApp(AndroidBackJs.ROOT, true))
        assertTrue(AndroidBackJs.shouldLeaveApp(AndroidBackJs.ROOT, false))
    }

    @Test
    fun escapeNeverFinishesTheActivity() {
        assertFalse(AndroidBackJs.shouldFinishOnEscape())
        val src = File("src/main/java/com/xiufeigo/opencodeshell/MainActivity.kt").readText()
        assertTrue(src.contains("KEYCODE_ESCAPE"))
        assertTrue(src.contains("dispatchKeyEvent"))
        assertTrue(src.contains("AndroidBackJs.INVOKE"))
        assertFalse(src.contains("finish()"))
        assertTrue(src.contains("evaluateJavascript(AndroidBackJs.INVOKE"))
        val js = AndroidBackJs.INSTALL
        assertTrue(js.contains("Escape"))
        assertTrue(js.contains("dialog-overlay") || js.contains("role=\"dialog\""))
        assertTrue(js.contains("__ocAndroidHandleBack"))
    }

    @Test
    fun readmeDocumentsBackAndEscape() {
        val readme =
            listOf(File("../README.md"), File("README.md"), File("../../README.md")).firstOrNull { it.isFile }
                ?: error("README.md not found")
        val text = readme.readText()
        assertTrue(text.contains("Escape") || text.contains("ESC"))
        assertTrue(text.contains("never") || text.contains("does not exit") || text.contains("不会退出"))
        assertTrue(text.contains("Settings") || text.contains("设置"))
    }
}
