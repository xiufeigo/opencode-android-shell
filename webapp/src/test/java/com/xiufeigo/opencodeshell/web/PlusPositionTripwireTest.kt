package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX3 绊线（tripwire）：「打开终端 / 打开文件」选择面板必须挂在
 * **右侧栏标签条的「+」**（`<header>` 之外那个 `[role=tablist]` 尾部），
 * 而**不是**顶栏标签条的「+」。
 *
 * ## 它钉住的是什么
 * UX1/UX2 曾把面板挂在顶栏「+」（`aria-label="New session"`，上游
 * titlebar.tsx:621-629 → openNewTab(:271) 直接新建会话草稿标签）上，
 * 产品负责人用 2506×1673 截图批注纠正：要的是右侧栏「审查」旁边那个「+」。
 * 根因是 UX1 用**文案判据**（`/新建会话|新会话|new session/i`）全页面扫描按钮，
 * 第一个命中的就是顶栏那个。本轮改为 **DOM 结构判据**（设备实测
 * `document.elementFromPoint` 反查确认，见 UX3_PLUS_POSITION_FIX.md §1）。
 *
 * ## 静态判据（对 oc-host.js 源码扫描）
 * 1. 旧的文案判据 `/新建会话|新会话|new session/i` 必须**整体消失**
 *    （出现即 = 顶栏「+」又被纳入拦截范围）。
 * 2. finder 必须**限定在 `[role=tablist]` 作用域**内且显式排除 `<header>`
 *    （`list.closest("header")`）——两个条件都命中的元素结构上就不可能是顶栏「+」。
 * 3. 面板动作「打开终端」必须把点击还给上游按钮自己（passThrough 守卫存在），
 *    保证上游「New terminal」原功能不丢。
 *
 * ## 反向验证（本绊线必须能变红）
 * [redTopBarWiring] / [redNoHeaderGuard] 是**故意写坏**的样本，分别模拟
 * "文案判据回归"与"去掉 header 排除"。扫描器必须对它们报红 —— 否则这条
 * 绊线就是"永远绿"的假绊线。同理，把真实 oc-host.js 撤回 UX1 接线后，
 * 第 1 条断言必须真红。
 */
class PlusPositionTripwireTest {

    private fun read(rel: String): String {
        val f = File(rel)
        assertTrue("找不到 $rel（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        return f.readText()
    }

    private val js: String by lazy { read("src/main/assets/www/oc-host.js") }

    /**
     * UX1 的错判据原文：全页面按"新建会话"文案找按钮的正则字面量。
     * 注意只认**正则字面量**（`/…/i`）——注释里提到 "New session" 按钮
     * 是说明文字，不算回归；出现该字面量 = 顶栏「+」又被纳入拦截。
     */
    private val topBarLabelCriteria = Regex("""/新建会话\|新会话\|new session/i""")

    @Test
    fun `host injection has no top-bar label criteria`() {
        assertFalse(
            "顶栏「+」的文案判据正则字面量（/新建会话|新会话|new session/i）又回来了 —— " +
                "面板会重新挂到顶栏 titlebar 的「New session」按钮上（UX1 的错）",
            topBarLabelCriteria.containsMatchIn(js),
        )
    }

    @Test
    fun `plus finder is scoped to non-header tablist`() {
        assertTrue(
            "finder 必须限定在 [role=tablist] 作用域（右侧栏标签条），实测原文见 UX3 报告 §1",
            js.contains("[role=tablist]"),
        )
        assertTrue(
            "finder 必须显式排除 <header> 内的 tablist（顶栏保险），" +
                "设备实测顶栏「+」祖先为 <header>",
            Regex("""closest\(\s*"header"\s*\)""").containsMatchIn(js),
        )
        assertTrue(
            "finder 必须排除标签自带的 close ×（aria-label 前缀判据），" +
                "否则先命中 Close terminal 按钮",
            Regex("""CLOSE_PREFIX\s*=\s*/""").containsMatchIn(js),
        )
    }

    @Test
    fun `terminal action hands the click back to upstream plus`() {
        assertTrue(
            "「打开终端」必须用 passThrough 把合成 click 还给上游「+」自己，" +
                "否则上游 New terminal 原功能会丢",
            Regex("""passThrough\s*=\s*true""").containsMatchIn(js),
        )
        assertTrue(
            "onClick 必须在 passThrough 时放行，否则还给上游的点击会被自己的" +
                " document 捕获监听吃回去（面板反复重开）",
            Regex("""if\s*\(\s*passThrough\s*\)\s*return""").containsMatchIn(js),
        )
    }

    // ---------------------------------------------------------------- 反向验证

    /** 坏样本 1：UX1 顶栏接线（文案判据）——第 1 条断言必须对它报红。 */
    private val redTopBarWiring = """
        function findPlusButton() {
          var nodes = document.querySelectorAll("button,[role=button]");
          for (var i = 0; i < nodes.length; i++) {
            var label = (nodes[i].getAttribute("aria-label") || "").trim();
            if (/新建会话|新会话|new session/i.test(label)) return nodes[i];
          }
          return null;
        }
    """.trimIndent()

    /** 坏样本 2：有 tablist 作用域但去掉 header 排除。 */
    private val redNoHeaderGuard = """
        function findPlusButton() {
          var lists = document.querySelectorAll("[role=tablist]");
          return lists.length ? lists[0].querySelector("button") : null;
        }
    """.trimIndent()

    @Test
    fun `scanner flags the old top-bar wiring as red`() {
        assertTrue(
            "扫描器对 UX1 顶栏文案判据样本不报红 —— 绊线是假绿",
            topBarLabelCriteria.containsMatchIn(redTopBarWiring),
        )
        assertFalse(
            "扫描器对 UX1 顶栏文案判据样本不报红 —— 绊线是假绿",
            redTopBarWiring.contains("[role=tablist]") &&
                Regex("""closest\(\s*"header"\s*\)""").containsMatchIn(redTopBarWiring),
        )
    }

    @Test
    fun `scanner flags a finder without the header guard as red`() {
        assertFalse(
            "坏样本 2 没有 header 排除，必须被判不合格",
            Regex("""closest\(\s*"header"\s*\)""").containsMatchIn(redNoHeaderGuard),
        )
    }
}
