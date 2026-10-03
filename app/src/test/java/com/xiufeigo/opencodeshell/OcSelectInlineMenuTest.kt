package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v40 真机批：设置里的下拉弹层画到设置对话框外面 (`research/v40_verify/tD`
 * `tD_23_uifont` / `tD_81_fontdd` 的 UI Font 菜单浮在 tab 条之上，
 * `tD_30_sound` / `tD_83_snddd` 的 Sound effects 菜单浮在会话抽屉之上，
 * 浅色下尤其明显)。
 *
 * 现象：`OcSelect` 的菜单是 `DropdownMenu`——一个独立的平台窗口，对话框卡片
 * 裁剪不到它，所以展开项越过卡片圆角边，压住 tab 条和抽屉。
 *
 * 修法：`OcSelect` 不再开第二个窗口：展开项是布局里的内联 `Column`，只能画在
 * 宿主 scroll body 的边界内；展开时把下面的行往下顶（和上游换行的 select
 * 一样），对话框保持自己的几何形状。
 */
class OcSelectInlineMenuTest {

    private fun components(): String {
        val file = File("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsComponents.kt")
            .takeIf { it.isFile }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsComponents.kt")
        assertTrue("missing source: SettingsComponents.kt", file.isFile)
        return file.readText()
    }

    /** [text] from [from] up to (excluding) the next [to]. */
    private fun window(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("$from is gone", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("$to is gone", end > start)
        return text.substring(start, end)
    }

    @Test
    fun selectOpensNoSecondWindow() {
        val select = window(components(), "fun OcSelect(", "/** One option row:")
        assertFalse(select.contains("DropdownMenu("))
        assertFalse(select.contains("DropdownMenuItem("))
        assertFalse(select.contains("Popup("))
        assertFalse(select.contains("ExposedDropdownMenu"))
    }

    @Test
    fun selectImportsNoMenuWindow() {
        val body = components()
        assertFalse(body.contains("import androidx.compose.material3.DropdownMenu"))
        assertFalse(body.contains("import androidx.compose.ui.window.Popup"))
    }

    @Test
    fun expandedListIsAnInlineColumnUnderTheTrigger() {
        val select = window(components(), "fun OcSelect(", "/** One option row:")
        assertTrue(select.contains("if (expanded"))
        assertTrue(select.contains("Spacer(Modifier.height(4.dp))"))
        // v43 moved the list body into `OcSelectList` (it is now capped,
        // scrollable and can open upward) — the inline-column contract itself
        // is unchanged, so the pin reads the body where it now lives.
        val list = window(components(), "private fun OcSelectList(", "fun OcTextField(")
        assertTrue(list.contains("border(0.5.dp, c.borderMuted, RoundedCornerShape(6.dp))"))
        assertTrue(list.contains(".clickable(enabled = enabled)"))
    }

    @Test
    fun triggerTogglesAndChevronFollowsTheState() {
        val select = window(components(), "fun OcSelect(", "/** One option row:")
        assertTrue(select.contains("expanded = !expanded"))
        assertTrue(select.contains("Chevron(c.iconMuted, expanded = expanded)"))
        assertTrue(components().contains("private fun Chevron(tint: Color, expanded: Boolean = false)"))
    }

    @Test
    fun everySelectCallSiteStillCompilesAgainstTheSameSignature() {
        val dir = File("src/main/java/com/xiufeigo/opencodeshell/ui/settings")
            .takeIf { it.isDirectory }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/ui/settings")
        assertTrue("the settings package is gone", dir.isDirectory)
        var calls = 0
        for (file in dir.listFiles { f: File -> f.name.endsWith(".kt") }.orEmpty().sortedBy { it.name }) {
            val text = file.readText()
            calls += Regex("(?<!fun )OcSelect\\(").findAll(text).count()
        }
        val dock = File("src/main/java/com/xiufeigo/opencodeshell/ui/session/dock")
            .takeIf { it.isDirectory }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/ui/session/dock")
        if (dock.isDirectory) {
            for (file in dock.listFiles { f: File -> f.name.endsWith(".kt") }.orEmpty()) {
                calls += Regex("(?<!fun )OcSelect\\(").findAll(file.readText()).count()
            }
        }
        val servers = File("src/main/java/com/xiufeigo/opencodeshell/ui/servers")
            .takeIf { it.isDirectory }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/ui/servers")
        if (servers.isDirectory) {
            for (file in servers.listFiles { f: File -> f.name.endsWith(".kt") }.orEmpty()) {
                calls += Regex("(?<!fun )OcSelect\\(").findAll(file.readText()).count()
            }
        }
        assertEquals("OcSelect call sites changed unexpectedly", 9, calls)
    }
}
