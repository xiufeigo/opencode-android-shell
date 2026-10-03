package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.home.HomeRowLayout
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v97（task-4）— v57 修复批移交项「HomeProjectsList.kt 项目行 ROW_GAP 死缝」取证。
 *
 * 结论：**已落地（结构性消除），无需改码。** v57 时代项目行之间曾隔着一个不属于
 * 任何行热区的外侧缝隙（ROW_GAP Spacer，与会话列表同形状的 bug：点击落在缝里谁
 * 也不认领 —— 缝隙误触）。现行代码里项目行**背靠背**排布：两个挂载点（桌面列
 * `HomeProjectsColumn` 与手机下拉 `HomeProjectsDropdown`）的 `projects.forEach`
 * 都直接发射 `ProjectRow`，行与行之间没有任何 Spacer / ROW_GAP；每行又带
 * `ocHitTargetAtLeast(HomeRowLayout.MIN_HIT_TARGET)`，相邻 48 dp 命中区无缝密铺
 * （行内 clickable 是 28 dp，被平台最小触摸目标居中扩到 48 dp，恰好铺满行的命
 * 中节点），死缝在结构上不可能存在。桶C 的「gap 归行热区」语义继续由
 * HomeSessionsList.kt 承担（`gap-px` 是行自己的 padding，钉在
 * `HomeSessionGapTapV58Test`）。
 *
 * 本类把「行背靠背 + 48 dp 命中地板」钉住：谁再往行间塞外侧缝隙就挂。
 */
class HomeProjectRowSeamV97Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val projects =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectsList.kt")

    /** The first row-call after [from] — `AddProjectRow(`/`ProjectRow(` share a suffix. */
    private fun nextRowCallAfter(source: String, from: Int): Int =
        listOf("ProjectRow(", "RecentlyClosedRow(")
            .map { source.indexOf(it, from) }
            .filter { it >= 0 }
            .minOrNull()
            ?: -1

    @Test
    fun projectRowsAreEmittedBackToBack() {
        // 无缝隙常量、无行间 Spacer：每个 `projects.forEach { project ->` 到它
        // 随后的行调用之间不允许出现任何 Spacer —— 外侧缝隙是没有任何行认领的
        // 死区（v57 的缝隙误触正是这个形状）。
        assertFalse("no ROW_GAP may come back between project rows", "ROW_GAP" in projects)
        Regex("""projects\.forEach \{ project ->""").findAll(projects).forEach { match ->
            val next = nextRowCallAfter(projects, match.range.last)
            assertTrue("a forEach with no row after it?", next > match.range.last)
            val segment = projects.substring(match.range.last, next)
            assertFalse(
                "an outer spacer between project rows is a dead seam",
                "Spacer(" in segment,
            )
        }
    }

    @Test
    fun theRowHitFloorTilesTheColumn() {
        // 行保持上游 `h-7` 的 28 dp 视觉高度……
        assertTrue(projects.contains("private val ROW_HEIGHT = 28.dp"))
        // ……而它的命中节点不小于 48 dp（平台最小触摸目标）——相邻行的命中区
        // 相接（48 + 48 + …，中间没有留白），没有落不进任何行的像素。
        assertEquals(48.dp, HomeRowLayout.MIN_HIT_TARGET)
        assertEquals(48.dp, HomeRowLayout.hitTarget(28.dp))
        // 三种行挂载（AddProjectRow / ProjectRow / RecentlyClosedRow）都带地板，
        // 且按 `OcTouchTarget.kt` 的次序规则放在 `.heightIn` / clickable 之前。
        assertEquals(
            3,
            Regex("""\.ocHitTargetAtLeast\(HomeRowLayout\.MIN_HIT_TARGET\)""")
                .findAll(projects).count(),
        )
        val rowChain = projects
            .substringAfter(".ocHitTargetAtLeast(HomeRowLayout.MIN_HIT_TARGET)")
            .substringBefore("verticalAlignment")
        assertTrue(
            "the floor must precede the height clamp (OcTouchTarget.kt ordering)",
            rowChain.contains(".heightIn(min = ROW_HEIGHT)"),
        )
    }
}
