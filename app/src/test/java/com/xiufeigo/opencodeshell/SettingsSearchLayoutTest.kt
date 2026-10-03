package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.GENERAL_PANEL
import com.xiufeigo.opencodeshell.ui.settings.REASONING_MODE_OPTIONS
import com.xiufeigo.opencodeshell.ui.settings.SettingsSearchLayoutRules
import com.xiufeigo.opencodeshell.ui.settings.visibleRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v47 真机批的设置搜索崩溃 + General 页 Model reasoning 行排查。
 *
 * 问题一（崩溃，2/2 复现，`research/v47_verify/tE/tE_20_search_tap.png` /
 * `tE_21_search_typed.png`）：在「Search settings」里敲第一个字符，应用立刻
 * 异常退出（ActivityTaskManager 记 Force finishing OcActivity）。原因是嵌套
 * 竖向滚动 —— 搜索结果列表本身是 `verticalScroll`，却被挂进导航栏 body 自己
 * 的 `verticalScroll` 里，父容器给它的是「最大高度无上限」约束，Compose 在
 * 首次组合结果列表时抛约束异常。修法是让两个滚动角色互斥：查询非空时由结果
 * 列表自己滚，body 退化成普通的受限槽位（侧栏 `weight(1f)`，顶栏
 * `heightIn(max=180.dp)`）。
 *
 * 问题二（Model reasoning 行"消失"）：源码里该行并未被条件隐藏、也没有被删，
 * 见下面的 `theReasoningRowSurvivesTheRenderFilter` —— 报告里的"整行消失"是
 * 校验手段的假象（宽屏一次 fling 掠过了该行，而基于搜索框的定位又被上面这个
 * 崩溃打断）。这里把"两种形态下都渲染"钉死，防止真的回归。
 */
class SettingsSearchLayoutTest {

    // ---- 问题一：搜索结果容器不得是无限高度 ---------------------------------------

    @Test
    fun theTabListKeepsTheBodyScrollerWhileTheQueryIsBlank() {
        for (topBar in listOf(true, false)) {
            val layout = SettingsSearchLayoutRules.of("", topBar)
            assertTrue("blank query, topBar=$topBar", layout.outerScroll)
        }
        // A whitespace-only query is still "no query" upstream (`search.tsx`).
        assertTrue(SettingsSearchLayoutRules.of("   ", false).outerScroll)
    }

    @Test
    fun theBodyStopsScrollingAsSoonAsThereIsAQuery() {
        // The crash half of the contract: the scrollable result list may never
        // be mounted inside the body's own unbounded `verticalScroll`.
        for (topBar in listOf(true, false)) {
            val layout = SettingsSearchLayoutRules.of("gen", topBar)
            assertFalse("query active, topBar=$topBar", layout.outerScroll)
        }
    }

    @Test
    fun theSidebarResultsFillTheWeightedBody() {
        // The sidebar body is already bounded by `weight(1f)`, so the results
        // need no cap of their own — an extra `heightIn` here would only shrink
        // the list on a tall window.
        val layout = SettingsSearchLayoutRules.of("reasoning", topBar = false)
        assertNull(layout.resultsMaxHeightDp)
    }

    @Test
    fun theTopBarResultsKeepAFiniteCap() {
        // The strip has no scrolling body of its own, so without a cap the
        // result list would be measured with an unbounded max height again.
        val layout = SettingsSearchLayoutRules.of("reasoning", topBar = true)
        assertEquals(SettingsSearchLayoutRules.TOP_BAR_RESULTS_MAX_DP, layout.resultsMaxHeightDp)
        assertEquals(180, SettingsSearchLayoutRules.TOP_BAR_RESULTS_MAX_DP)
    }

    @Test
    fun aBlankQueryNeverCapsTheResults() {
        assertNull(SettingsSearchLayoutRules.of("", topBar = true).resultsMaxHeightDp)
    }

    @Test
    fun theSourceMountsTheResultsThroughTheRules() {
        val body = file("SettingsScreen.kt")
        // The body is only a scroller when the rules say so …
        assertTrue(body.contains("if (layout.outerScroll) Modifier.verticalScroll(rememberScrollState())"))
        // … and the results always go in through the cap the rules computed.
        assertEquals(2, Regex("then\\(layout\\.resultsMaxHeightModifier\\(\\)\\)").findAll(body).count())
    }

    // ---- 问题二：Model reasoning 行的存在性 ---------------------------------------

    private fun generalActions(mobile: Boolean) =
        visibleRows(GENERAL_PANEL, mobile = mobile).flatMap { it.rows }.map { it.action }

    @Test
    fun theReasoningRowSurvivesTheRenderFilter() {
        // The only runtime filter over the panel table is `visibleRows`
        // (`mobileOnly`); the reasoning row is declared unconditionally, so it
        // must be rendered on both the mobile form and the wide sidebar —
        // this is the "整行消失" regression, pinned at the table the renderer
        // actually walks.
        for (mobile in listOf(true, false)) {
            val actions = generalActions(mobile)
            val index = actions.indexOf("settings-reasoning-mode")
            assertTrue("reasoning row missing (mobile=$mobile) in $actions", index >= 0)
            // It sits between the workspace destination and the diff wrap rows
            // (`general.tsx:340-358`; `model.tsx:114,207`), so it cannot be
            // "present" at a stray position either.
            assertEquals("settings-workspace-destination", actions[index - 1])
            assertEquals("settings-mobile-diff-wrap", actions[index + 1])
        }
    }

    @Test
    fun theReasoningRowCarriesItsThreeStops() {
        val rows = GENERAL_PANEL.flatMap { it.rows }
        val row = rows.single { it.action == "settings-reasoning-mode" }
        assertEquals(REASONING_MODE_OPTIONS, row.options)
        assertFalse("must not be mobileOnly — it has a wide-form consumer", row.mobileOnly)
        assertTrue(row.titleResId != 0)
        assertTrue(row.descriptionResId != null)
    }

    // ---- shared ------------------------------------------------------------------

    private fun file(name: String): String {
        val file = File("src/main/java/com/xiufeigo/opencodeshell/ui/settings/$name")
            .takeIf { it.isFile }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/ui/settings/$name")
        assertTrue("missing source: $name", file.isFile)
        return file.readText()
    }
}
