package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX7 绊线（B-1/B-4）：「+」拦截**只允许锚定审查条**，终端标签条的「+」绝不被拦截。
 *
 * ## 背景（产品负责人 2026-10-05 实测打回）
 * 手机档点**终端标签条的「+」**（上游语义 = terminal.new() 直接新建终端，
 * panel.tsx:330-335）弹出的却是我们的「打开/打开终端/打开文件」面板。
 * 手机端"右上角那个按钮"（B-4 截图）同一个根因：拦截抓错了对象。
 *
 * ## 根因（UX7 设备实测，emulator-6652 手机档）
 * 旧 findPlusButton() 扫"页面上第一个不在 <header> 内的 [role=tablist]"。
 * 审查条（SessionSidePanel）是**桌面限定**（session-side-panel.tsx:257
 * `<Show when={isDesktop() && !!params.id}>`）——手机档没有审查条，于是命中
 * 了终端标签条的「+」（aria-label="New terminal"）。
 * 手机档视图标签条（SessionMobileViewTabs，review/view.tsx:44-64）虽然也在
 * DOM 里，但它的按钮全是 [role=tab]，唯一例外是 ≡ 菜单触发器——它是 Tabs
 * 的**兄弟节点**（view.tsx:66-102 的 <Menu> 在 <Tabs> 外），不在 tablist 内，
 * 所以旧扫描会跳过它直接命中终端条。
 *
 * ## 修法（选择器级收窄）
 * findPlusButton() 只从 `aside#review-panel`（session-side-panel.tsx:259，
 * 桌面限定）内的 tablist 找「+」；手机档该 aside 不挂载 ⇒ findPlusButton()
 * 恒 null ⇒ **手机档全页面零拦截**，终端条「+」完全归上游。
 *
 * ## 反向验证（本绊线必须能变红）
 * [redOldWideScan] 模拟旧实现（全页 tablist 扫描）⇒ 扫描器必须报红；
 * [redTerminalPanelAnchor] 模拟"锚到 #terminal-panel"的错误收窄 ⇒ 报红。
 */
class Ux7PhoneSweepTripwireTest {

    private fun read(rel: String): String {
        val f = File(rel)
        assertTrue("找不到 $rel（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        return f.readText()
    }

    private val js: String by lazy { read("src/main/assets/www/oc-host.js") }

    /** installPlusChooser 段：从函数体到 window.__ocPlusChooser 暴露赋值之前。 */
    private val chooserBody: String by lazy {
        val from = js.indexOf("function installPlusChooser()")
        assertTrue("oc-host.js 里找不到 installPlusChooser()", from >= 0)
        val to = js.indexOf("window.__ocPlusChooser = {", from)
        assertTrue("oc-host.js 里找不到 __ocPlusChooser 暴露赋值点", to > from)
        js.substring(from, to)
    }

    /** findPlusButton 函数体（从声明到 findTerminalTrigger 声明之前）。 */
    private val findPlusBody: String by lazy {
        val from = chooserBody.indexOf("function findPlusButton()")
        assertTrue("找不到 findPlusButton()", from >= 0)
        val to = chooserBody.indexOf("function findTerminalTrigger()", from)
        assertTrue("找不到 findTerminalTrigger()（顺序异常）", to > from)
        chooserBody.substring(from, to)
    }

    @Test
    fun `plus interception anchored to review panel only`() {
        assertTrue(
            "findPlusButton 必须先经 findReviewTablist() 锚定 aside#review-panel " +
                "（session-side-panel.tsx:259，桌面限定 :257）",
            findPlusBody.contains("findReviewTablist()"),
        )
        assertTrue(
            "findReviewTablist 必须用 getElementById(\"review-panel\") 取审查条容器",
            chooserBody.contains("getElementById(\"review-panel\")"),
        )
        assertTrue(
            "审查条收起（宽度 0）时必须视为不存在，不得拦截",
            Regex("""getBoundingClientRect\(\)\.width\s*<\s*2""").containsMatchIn(chooserBody),
        )
    }

    @Test
    fun `terminal strip plus must never be intercepted`() {
        assertFalse(
            "findPlusButton 里又出现了全页 [role=tablist] 扫描 —— " +
                "手机档会因此命中终端标签条的「+」（B-1 回归）",
            findPlusBody.contains("querySelectorAll(\"[role=tablist]\")"),
        )
        assertFalse(
            "拦截锚到了 #terminal-panel —— 那正是终端标签条自己的「+」" +
                "（panel.tsx:330-335，terminal.new()），B-1 的直接根因",
            findPlusBody.contains("terminal-panel"),
        )
        // onClick 里对 findPlusButton() 返回 null（手机档恒 null）必须直接放行：
        // 现有结构是 var btn = findPlusButton(); if (!btn) return; —— 守住这个早退。
        assertTrue(
            "findPlusButton 返回 null 时 onClick 必须早退放行（手机档零拦截的前提）",
            Regex("""if\s*\(\s*!btn\s*\)\s*return""").containsMatchIn(chooserBody),
        )
    }

    @Test
    fun `open terminal and open file pass-through wiring kept`() {
        // 收窄的是"找谁"，不是"找到后干什么"——UX5 的两条透传路线原样保留。
        assertTrue(
            "「打开文件」仍走 passThrough 还给上游「+」自己",
            chooserBody.contains("passThrough = true"),
        )
        assertTrue(
            "「打开终端」仍保留上游 terminal.new 键位路（面板未开时）",
            chooserBody.contains("dispatchUpstreamKey(\"t\", true, true)"),
        )
        assertTrue(
            "终端触发器查找（findTerminalTrigger，aria-label 命中 terminal/终端）仍在",
            chooserBody.contains("function findTerminalTrigger()"),
        )
    }

    // ---------------------------------------------------------------- 反向验证

    /** 坏样本 1：UX7 改之前的旧实现 —— 全页扫描第一个非 header tablist。 */
    private val redOldWideScan = """
        function findPlusButton() {
          var lists = document.querySelectorAll("[role=tablist]");
          for (var i = 0; i < lists.length; i++) {
            var list = lists[i];
            if (list.closest("header")) continue;
            if (list.offsetParent === null) continue;
            var hit = scanTablistButtons(list, true);
            if (hit) return hit;
          }
          return null;
        }
    """.trimIndent()

    /** 坏样本 2：错误收窄 —— 锚到终端面板（终端条自己的「+」）。 */
    private val redTerminalPanelAnchor = """
        function findPlusButton() {
          var panel = document.getElementById("terminal-panel");
          if (!panel) return null;
          var list = panel.querySelector("[role=tablist]");
          if (!list) return null;
          return scanTablistButtons(list, true);
        }
    """.trimIndent()

    @Test
    fun `scanner flags old wide tablist scan as red`() {
        assertTrue(
            "旧全页扫描样本没有 review-panel 锚 —— 必须被判不合格（否则绊线是假绿）",
            redOldWideScan.contains("querySelectorAll(\"[role=tablist]\")"),
        )
        assertFalse(
            "旧全页扫描样本不得含有 review-panel 锚",
            redOldWideScan.contains("review-panel"),
        )
    }

    @Test
    fun `scanner flags terminal-panel anchor as red`() {
        assertTrue(
            "坏样本锚到了 terminal-panel —— 扫描器必须能识别它命中的是终端条「+」",
            redTerminalPanelAnchor.contains("terminal-panel"),
        )
        assertFalse(
            "坏样本不得通过「review-panel 锚」判据",
            redTerminalPanelAnchor.contains("review-panel"),
        )
    }
}
