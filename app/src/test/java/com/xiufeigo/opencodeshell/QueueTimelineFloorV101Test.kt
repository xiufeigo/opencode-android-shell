package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.min

/**
 * v101 P0 — 时间线地板的**让位阈值显式化**（`research/v97_fix/QUEUE_TIMELINE_SPACE_FIX4.md`）。
 *
 * **为什么有这一轮**：`REPORT_U1D.md` §7.3 在 `wm size 1366x600` + `wm density 160`
 * 下连读 3 帧读到 `readable = 51px < 64` 地板。v100 的地板是随窗口缩放的
 * `min(64, pane)`，在 600px 高的窗口里让位，而**让位阈值既没有名字、也没有 pin**，
 * 是一条沉默的阈值。Lead 要求裁定并落地。
 *
 * **裁定（FIX4 §2）**：地板**继续保 64**，但只在 pane 本身够得着的时候；
 * pane 装不下时按**显式声明**的规则让位，并且这条规则被 pin 住。
 *
 * ```
 * TIMELINE_FLOOR_YIELD_PANE = 64.dp                // 让位边界（= TIMELINE_FLOOR_UNDER_QUEUE）
 * timelineViewportFloor(pane) = 64  if pane >= 64  // 硬地板，永不缩放
 *                            = pane otherwise     // 声明的让位
 * timelineFloorGuaranteed(pane) = pane >= 64
 * timelineViewportFloorPx(panePx, floorPx) = 同样的两分支（density-free）
 * ```
 *
 * **v101 的真修**（不是改阈值，是修**让位的触发条件**）：measure policy 现在把地板
 * 同时对**测量期真实存在的 pane** 重新求值（`SessionScreen.kt` 的
 * `maxOf(declaredFloorPx, measuredFloorPx)`）。v100 只喂组合期读到的 `paneHeight`
 * （一个早一帧的 `Dp` state），所以一个**在测量时早已超过 64 的 pane** 仍可能按那个
 * 陈旧的、更小的 `paneHeight` 让位 —— 地板为一个**付得起它的 pane** 让了路。
 * 取 `maxOf` 只会**加严**地板，因此 ≥11 条与 `1366x768` 两格逐字不受影响。
 *
 * 本文件把真值表**加一维窗口高度**（1366x768 / 1366x600 / 更小），逐格断言
 * `readable >= 声明地板` 且不低于该方案声明的最小值。
 */
class QueueTimelineFloorV101Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val sessionScreen = source(
        "src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt",
    )

    /** 一格真值：与 FIX4 §3 的出厂算式逐字同源。 */
    private data class Cell(
        val windowDp: Int,
        val imeDp: Int,
        val pane: Int,
        val composerLines: Int,
        val queueRows: Int,
        val card: Int,
        val bar: Int,
        val floor: Int,
        val declaredFloor: Int,
        val hostCeiling: Int,
        val hostPaid: Int,
        val timeline: Int,
    ) {
        val where: String
            get() = "window=$windowDp ime=$imeDp pane=$pane lines=$composerLines " +
                "rows=$queueRows card=$card bar=$bar floor=$floor"
    }

    /**
     * 出厂算式（`dp == px @160dpi`）：
     *
     * ```
     * pane     = window - ime                       // 减到 0
     * floor    = timelineViewportFloor(pane)        // 64 或 pane（声明让位）
     * declared = timelineReadableFloorPx(pane, 64)  // 本方案声明的最小可读带
     * card     = composerFloorFor(lines)            // 56 / 116
     * bar      = queueBarHeight(rows, pane, card)   // 队列条**最先**让路
     * hostCap  = composerHostCeilingPx(pane, floor) // 宿主 = pane - floor
     * hostPaid = min(bar + card, hostCap)           // 末行式
     * timeline = viewportHeightPx(shared, pane, hostPaid, floor)
     * ```
     */
    private fun cell(
        windowDp: Int,
        imeDp: Int,
        composerLines: Int,
        queueRows: Int,
        shared: Boolean = true,
    ): Cell {
        val pane = maxOf(0, windowDp - imeDp)
        val floor = SessionPaneLayout.timelineViewportFloor(pane.dp).value.toInt()
        val declared = SessionPaneLayout.timelineReadableFloorPx(pane, 64)
        val card = SessionPaneLayout.composerFloorFor(composerLines).value.toInt()
        val bar = SessionPaneLayout.queueBarHeight(
            rows = queueRows,
            pane = pane.dp,
            composerFloor = card.dp,
        ).value.toInt()
        val hostCeiling = SessionPaneLayout.composerHostCeilingPx(pane, floor)
        val hostPaid = min(bar + card, hostCeiling)
        val timeline = SessionPaneLayout.viewportHeightPx(
            shared = shared,
            pane = pane,
            composer = hostPaid,
            floorPx = floor,
        )
        return Cell(
            windowDp, imeDp, pane, composerLines, queueRows, card, bar, floor,
            declared, hostCeiling, hostPaid, timeline,
        )
    }

    // ------------------------------------------------------- ① 三维真值表

    /**
     * **加了一维窗口高度**：1366x768（已 PASS 格）/ 1366x600（本轮 FAIL 格）/ 更小
     * （480）。每格断言 `readable >= 声明地板`，并在 `pane >= 64` 时**额外**断言
     * `readable >= 64`（硬地板），在 `pane < 64` 时断言 `readable == pane`
     * （让位到极限 = 时间线**吃掉整个 pane**，没有任何预算项可以再挤）。
     *
     * 3 窗 × 2 IME × 3 composer 行 × 4 队列深度 × 2 排列 = **144 格**，
     * 不走对角线。
     */
    @Test
    fun theWindowHeightDimensionKeepsTheDeclaredFloorInEveryCell() {
        var cells = 0
        for (windowDp in listOf(768, 600, 480)) {
            for (imeDp in listOf(294, 0)) {
                for (composerLines in 1..3) {
                    for (queueRows in listOf(0, 2, 11, 23)) {
                        for (shared in listOf(true, false)) {
                            val c = cell(windowDp, imeDp, composerLines, queueRows, shared)

                            // ① 逐格：不低于本方案**声明**的最小可读带。
                            assertTrue(
                                "① timeline=${c.timeline} < declared floor=${c.declaredFloor} (${c.where})",
                                c.timeline >= c.declaredFloor,
                            )
                            // ② pane 付得起 64 时，地板**就是** 64 且 readable >= 64。
                            if (c.pane >= 64) {
                                assertEquals(
                                    "② floor must stay a hard 64 on an affordable pane (${c.where})",
                                    64, c.floor,
                                )
                                assertTrue(
                                    "② timeline=${c.timeline} < 64 on pane=${c.pane} (${c.where})",
                                    c.timeline >= 64,
                                )
                            } else {
                                // ③ pane 本身 < 64：声明的让位必须让到**极限** ——
                                //    时间线 = 整个 pane，宿主 = 0，没有第三种结果。
                                assertEquals(
                                    "③ the declared yield must hand the whole pane (${c.where})",
                                    c.pane, c.timeline,
                                )
                                assertEquals(
                                    "③ a sub-64 pane has no room for a host (${c.where})",
                                    0, c.hostPaid,
                                )
                            }
                            // ④ 预算自洽（末行式）/ 覆盖式拿整个 pane。
                            if (shared) {
                                assertTrue(
                                    "④ overflow host=${c.hostPaid} + timeline=${c.timeline} " +
                                        "> pane=${c.pane} (${c.where})",
                                    c.hostPaid + c.timeline <= c.pane,
                                )
                            } else {
                                assertEquals(
                                    "④ the cover form must still hand the viewport the whole pane " +
                                        "(${c.where})",
                                    c.pane, c.timeline,
                                )
                            }
                            // ⑤ 宿主永远不越过 `pane - floor`。
                            assertTrue(
                                "⑤ host ${c.hostPaid} > pane-floor (${c.where})",
                                c.hostPaid <= c.hostCeiling,
                            )
                            cells++
                        }
                    }
                }
            }
        }
        // 3 × 2 × 3 × 4 × 2 = 144。这条断言本身是「没有走对角线」的机器可证形式。
        assertEquals(144, cells)
    }

    // ------------------------------------------------------- ② 队列条/动作可达性

    /**
     * 不回退 `REPORT_U1D` §5/§6 已 PASS 的两格：**队列 ≥11 条时队列条要么整条不画，
     * 要么至少画出一整行 32px**（`Steer` / **×** 的 h ≥ 32 门槛就挂在这上面），
     * 且**队列条永远不吃地板**。
     */
    @Test
    fun aDeepQueueEitherVanishesOrKeepsOneWholeActionableRow() {
        for (windowDp in listOf(768, 600, 480)) {
            for (imeDp in listOf(294, 0)) {
                for (composerLines in 1..3) {
                    for (queueRows in listOf(0, 2, 11, 23, 60)) {
                        val c = cell(windowDp, imeDp, composerLines, queueRows)
                        if (queueRows > 0 && c.bar > 0) {
                            assertTrue(
                                "a bar with rows must show one whole row: bar=${c.bar} rows=" +
                                    "$queueRows (${c.where})",
                                c.bar - SessionPaneLayout.QUEUE_BAR_CHROME.value.toInt() >=
                                    SessionPaneLayout.QUEUE_ROW_HEIGHT.value.toInt(),
                            )
                            assertTrue(
                                "a drawn bar must be at least the 32+18 whole-bar floor: " +
                                    "bar=${c.bar} (${c.where})",
                                c.bar >= SessionPaneLayout.QUEUE_BAR_MIN_HEIGHT.value.toInt(),
                            )
                            assertTrue(
                                "the bar ate the band: bar=${c.bar} (${c.where})",
                                c.bar <= c.pane - c.floor - c.card,
                            )
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------- ③ 600px 预算重放

    /**
     * 把 `REPORT_U1D.md` §7.3 的那一帧当**数据**重放：1366x600@160 + IME，
     * 时间线节点读 `0,162-820,213` ⇒ 盒高 **51**、队列条**整条不在树**。
     *
     * 逐项预算（`dp == px @160dpi`）：
     *
     * ```
     * pane      = 51                      // 整条自洽解，见 FIX4 §1
     * floor     = min 规则让位 = pane = 51 // TIMELINE_FLOOR_YIELD_PANE=64 > pane
     * card      = 56 (1 行) / 116 (2~3 行)
     * bar       = 0   // queueBarCeiling = 51 - 64 - card < 50 ⇒ 整条不画 ✔
     * hostCap   = 51 - 51 = 0  ⇒ hostPaid = 0   // 宿主被地板吃光
     * timeline  = max(51 - 0, 51) = 51   ✔ 逐位命中设备读数
     * 51 + 0 + 0 = 51 = pane               // 没有第四项预算可以再挤
     * ```
     */
    @Test
    fun theSixHundredPixelFrameIsTheWholePaneNotAStolenBand() {
        val pane = 51
        val floor = SessionPaneLayout.timelineViewportFloor(pane.dp).value.toInt()
        assertEquals("floor yields to the pane itself", pane, floor)
        assertEquals(
            "the yield boundary is 64 and this pane is below it",
            false, SessionPaneLayout.timelineFloorGuaranteed(pane.dp),
        )
        for (composerLines in 1..3) {
            val c = cell(windowDp = 600, imeDp = 600 - pane, composerLines = composerLines, queueRows = 0)
            assertEquals("pane replays", pane, c.pane)
            assertEquals("host is clamped to zero", 0, c.hostCeiling)
            assertEquals("host pays nothing", 0, c.hostPaid)
            assertEquals("the queue bar is not drawn at all", 0, c.bar)
            assertEquals(
                "readable is the whole pane — nothing is left to squeeze (${c.where})",
                pane, c.timeline,
            )
            // 声明口径：这一格的地板**就是** 51，因为 64 装不进 51。
            assertEquals(pane, SessionPaneLayout.timelineReadableFloorPx(pane, 64))
        }
    }

    /**
     * 让位边界的**双向**性质：`<= 64` 让位、`>= 64` 硬保，中间**没有缝隙**。
     * 全整数扫描，不抽样。
     */
    @Test
    fun theYieldBoundaryHasNoGapAroundSixtyFour() {
        for (panePx in 0..400) {
            val floor = SessionPaneLayout.timelineViewportFloorPx(panePx, 64)
            val guaranteed = SessionPaneLayout.timelineFloorGuaranteed(panePx.dp)
            if (panePx >= 64) {
                assertEquals("the band must be hard at pane=$panePx", 64, floor)
                assertTrue("guaranteed must be true at pane=$panePx", guaranteed)
            } else {
                assertEquals("the yield must hand the pane at pane=$panePx", panePx, floor)
                assertEquals("guaranteed must be false at pane=$panePx", false, guaranteed)
            }
            // 两分支的 Dp / px 两种表述必须**逐字相等**（density-free 的那一个）。
            assertEquals(
                "Dp and px branches disagree at pane=$panePx",
                SessionPaneLayout.timelineViewportFloor(panePx.dp).value.toInt(),
                floor,
            )
        }
    }

    // ------------------------------------------------------- ④ 结构 pin

    /**
     * v101 的真修必须在 measure policy 里：地板要同时对**测量期真实的 pane** 求值。
     * 写成结构断言，防止 v100 的「只喂组合期 `paneHeight`」悄悄回来。
     */
    @Test
    fun theMeasurePolicyRederivesTheFloorAgainstTheMeasuredPane() {
        val policy = sessionScreen.substringAfter("private fun SessionPaneArrangement(")
        assertTrue(
            "the measured pane is never re-checked against the band",
            policy.contains("SessionPaneLayout.timelineViewportFloorPx("),
        )
        assertTrue(
            "the two floor readings are not combined with maxOf",
            policy.contains("maxOf(declaredFloorPx, measuredFloorPx)"),
        )
        // 既有 v100 结构 pin 必须**逐字仍在**（不许为了 v101 改口径）。
        assertTrue(
            "the host is not capped at pane - floor",
            policy.contains("SessionPaneLayout.composerHostCeilingPx("),
        )
        assertTrue(policy.contains("floorPx = floorPx,"))
        assertTrue(policy.contains("minHeight = floorPx,"))
    }
}
