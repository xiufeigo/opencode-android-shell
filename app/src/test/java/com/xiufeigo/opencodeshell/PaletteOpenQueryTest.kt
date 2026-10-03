package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.palette.PALETTE_OPEN_QUERY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v54 真机批（桌面 tB）：命令面板重开还留着上次的查询
 * (`research/v54_verify/tB/tB_71_palette_redo.png` →
 * `tB_73_after_redo2.png`)。
 *
 * 现象：搜索框里输过「redo」→ 执行 Redo → 再开面板，搜索框里还是「redo」，
 * 于是接着输一遍变成「redoredo」，一条结果都匹配不到。
 *
 * 原因不在面板自己：`CommandPalette` 的 `query` 是 `remember` 出来的弹层本地
 * 状态，只有**弹层被卸载再挂载**才会归零。`DialogHost` 按位置渲染
 * `for (entry in stack)` 而没有 `key`，所以同一位置的循环体共用一个组合槽；
 * 被关闭的那层 `exitHoldMs` 后才出栈，出栈后新层正好滑进那个空槽 —— 于是
 * 「新弹层」是在「旧弹层的 remember」上重建的。同一个洞还会把上一层残留的
 * 滚动位置、焦点和在飞的效果一起带过去。
 *
 * 修法：`DialogHost` 用 `key(entry.id)` 给每层一个自己的槽，新层永远是一次
 * 新的组合；面板这边把「每次打开都是空查询」写成具名常量 [PALETTE_OPEN_QUERY]，
 * 不再是埋在 `remember` 里的一个 `""`。
 */
class PaletteOpenQueryTest {

    private fun source(relative: String): String {
        val file = File("src/main/java/com/xiufeigo/opencodeshell/$relative")
            .takeIf { it.isFile }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/$relative")
        assertTrue("missing source: $relative", file.isFile)
        return file.readText()
    }

    private fun host() = source("ui/overlay/DialogHost.kt")
    private fun palette() = source("ui/palette/CommandPalette.kt")

    @Test
    fun everyOpenStartsFromABlankQuery() {
        assertEquals("", PALETTE_OPEN_QUERY)
    }

    @Test
    fun thePaletteQueryStartsFromThatConstant() {
        assertTrue(
            "面板的 query 必须从 PALETTE_OPEN_QUERY 起，而不是内联的 \"\"",
            palette().contains("var query by remember { mutableStateOf(PALETTE_OPEN_QUERY) }"),
        )
    }

    @Test
    fun theQueryIsStillDialogLocalState() {
        // 上游 `dialog.tsx` 每次开都是空 query：它属于弹层，不属于会话或 Home。
        // 断言它没有被提到宿主层去共享。
        val body = palette().substringAfter("fun CommandPalette(").substringBefore("private fun PaletteRow(")
        assertTrue(body.contains("var query by remember { mutableStateOf("))
    }

    @Test
    fun everyDialogLayerGetsItsOwnCompositionSlot() {
        // v54 的真正修法：`for (entry in stack)` 按位置渲染，没有 key 的话
        // 出栈后新层会复用旧层的 remembered state。
        val body = host().substringAfter("fun DialogHost(").substringBefore("private fun DialogLayer(")
        assertTrue(
            "弹层循环必须按 entry.id 加 key",
            body.contains("key(entry.id) {"),
        )
    }

    @Test
    fun theKeyWrapsEveryLayerNotJustSome() {
        val body = host().substringAfter("fun DialogHost(").substringBefore("private fun DialogLayer(")
        val loop = body.substringAfter("for (entry in stack) {").substringBefore("BackHandler(")
        assertTrue(loop.contains("DialogLayer("))
        val keyAt = loop.indexOf("key(entry.id) {")
        val layerAt = loop.indexOf("DialogLayer(")
        assertTrue("key 必须包在 DialogLayer 外面", keyAt >= 0 && layerAt > keyAt)
    }

    @Test
    fun theExitHoldStillRemovesTheLayer() {
        // key 不许顺手把「关闭后延迟出栈」的既有行为改掉。
        val body = host()
        assertTrue(body.contains("OcMotion.exitHoldMs"))
        assertTrue(body.contains("stack.remove(entry)"))
    }
}
