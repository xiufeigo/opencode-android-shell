package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.min

/**
 * v100 P0 —「时间线最小可读高度必须是硬约束」的二维 pin。
 *
 * **这一轮为什么要有这个类**（`research/v97_verify/5556_v98/REPORT_U1B.md` §4.1）：
 * 同一扇窗（1366x768@160 + IME + 队列条真实存在）里，时间线在 **16px** 与 **140px**
 * 之间出现两档，`h=16` 连读 3 帧逐字稳定，**唯一的相变量是 composer 高度（h=34 ↔ h=48）**。
 * 前两轮（`QUEUE_TIMELINE_SPACE_FIX.md` / `FIX2.md`）都把地板写在了**被压缩的那一侧**：
 * 地板由 `heightIn(min = …)` / `queueBarCeiling` 表达，而真正决定时间线盒子高度的是
 * `SessionPaneArrangement` 的 measure policy 里那句 `pane - host.height`，**它的下限是 0**。
 * composer 一变高，差额就整块落在时间线上。
 *
 * 这里 pin 的**不是**某个函数的返回值，而是**整条预算链**：
 *
 * ```
 * floor    = min(64, pane)                       // timelineViewportFloor
 * card     = composerFloorFor(lines)             // 56 / 116
 * bar      = queueBarHeight(rows, pane, card)    // 队列条自己先让
 * hostPaid = min(bar + card, pane - floor)       // ← v100 新增的硬上限
 * timeline = viewportHeightPx(shared, pane, hostPaid, floor)
 * ```
 *
 * **二维穷举，不走对角线**：composer 行数 {1,2,3} × 队列条数 {0,2,11,23}，
 * 每格 × 窗口 {720,600} × IME {378,0} × 排列 {shared, floating} = **96 格**。
 */
class QueueTimelineFloorV100Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val sessionScreen = source(
        "src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt",
    )
    private val sessionPaneLayout = source(
        "src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt",
    )

    /** 队列条只要画得出来，就必须放得下**一整行** `h-8`。 */
    private fun rowHeightOk(queueRows: Int, bar: Dp): Boolean {
        if (bar.value <= 0f) return true // 0 行不画；房间不够则退化为纯 chrome
        val floor = SessionPaneLayout.queueBarViewportFloor(queueRows)
        return floor >= SessionPaneLayout.QUEUE_ROW_HEIGHT &&
            SessionPaneLayout.queueBarVisibleRows(queueRows, bar) >= 1
    }

    // ------------------------------------------------------------ ① 二维真值表

    /**
     * ①时间线 ≥ 阈值 · ②队列行高 ≥ 最小行高 · ③不出现「时间线 = 原缺陷值」——
     * **每一格**都断言，不抽样、不走对角线。
     */
    @Test
    fun everyComposerLineByQueueDepthCellKeepsTheBandAndTheRow() {
        var cells = 0
        for (windowDp in listOf(720, 600)) {
            for (imeDp in listOf(378, 0)) {
                for (composerLines in 1..3) {
                    for (queueRows in listOf(0, 2, 11, 23)) {
                        val pane = maxOf(0, windowDp - imeDp)
                        val floorPx = min(64, pane)
                        val card = SessionPaneLayout.composerFloorFor(composerLines)
                        val bar = SessionPaneLayout.queueBarHeight(
                            rows = queueRows,
                            pane = pane.dp,
                            composerFloor = card,
                        )
                        // v100：宿主拿不到「pane 减去 band」以外的东西。
                        val hostCeilingPx = SessionPaneLayout.composerHostCeilingPx(pane, floorPx)
                        val hostPaidPx = min(bar.value.toInt() + card.value.toInt(), hostCeilingPx)
                        // shared（最后一行）与 floating（覆盖）两种排列都要站得住。
                        for (shared in listOf(true, false)) {
                            val timeline = SessionPaneLayout.viewportHeightPx(
                                shared = shared,
                                pane = pane,
                                composer = hostPaidPx,
                                floorPx = floorPx,
                            )
                            val where = "window=$windowDp ime=$imeDp lines=$composerLines " +
                                "rows=$queueRows shared=$shared pane=$pane"

                            // ① 时间线 ≥ 同一阈值
                            assertTrue(
                                "① timeline=$timeline < floor=$floorPx ($where)",
                                timeline >= floorPx,
                            )
                            // ② 队列行高 ≥ 最小行高
                            assertTrue(
                                "② row floor broken rows=$queueRows bar=$bar ($where)",
                                rowHeightOk(queueRows, bar),
                            )
                            // ③ 绝不回到原缺陷读数（16px 量级）
                            assertTrue(
                                "③ timeline=$timeline fell back to the 16px defect ($where)",
                                timeline > 16 || floorPx <= 16,
                            )
                            // 预算自洽：两半不得超出 pane。
                            // 只在**末行式**成立：覆盖式（floating）里 composer 宿主是
                            // 不透明的**盖子**，`fillMaxSize` + 底对齐，它按设计就压
                            // 在视口之上（`SessionPaneLayout` 的「floating」形态），
                            // 它的可读带由 `timelineContentBottom` 另行预留。所以
                            // 覆盖式该断言的是「视口拿整个 pane」，而不是求和。
                            if (shared) {
                                assertTrue(
                                    "budget overflow host=$hostPaidPx timeline=$timeline pane=$pane ($where)",
                                    hostPaidPx + timeline <= pane,
                                )
                            } else {
                                assertEquals(
                                    "the cover form must still hand the viewport the whole pane ($where)",
                                    pane, timeline,
                                )
                            }
                            // 永远不从 band 里扣
                            assertTrue(
                                "host $hostPaidPx > pane $pane - floor $floorPx ($where)",
                                hostPaidPx <= hostCeilingPx,
                            )
                            cells++
                        }
                    }
                }
            }
        }
        // 2 窗口 × 2 IME × 3 composer 行 × 4 队列深度 × 2 排列 = 96 格。
        // 这条断言本身就是「没有走对角线」的机器可证形式：少跑一格它就红。
        assertEquals(96, cells)
    }

    // ------------------------------------------------- ② 底层两个函数的穷举扫描

    /**
     * `viewportHeightPx` 无论宿主多高都不得低于地板；`composerHostCeilingPx`
     * 永远把 band 留出来。全整数扫描，不抽样。
     */
    @Test
    fun theFloorIsNeverSubtractedFromTheBandAnywhereInTheSweep() {
        for (pane in 0..900) {
            val f = min(64, pane)
            for (composer in 0..900 step 3) {
                for (shared in listOf(true, false)) {
                    for (floor in listOf(0, 17, 64, 72, 200)) {
                        val capped = min(floor, pane)
                        val h = SessionPaneLayout.viewportHeightPx(shared, pane, composer, capped)
                        assertTrue(
                            "viewportHeightPx(shared=$shared,pane=$pane,composer=$composer," +
                                "floor=$capped)=$h < floor $capped",
                            h >= capped,
                        )
                        assertTrue("viewportHeightPx overflowed the pane: $h > $pane", h <= pane)
                    }
                }
                val c = SessionPaneLayout.composerHostCeilingPx(pane, f)
                assertTrue("composerHostCeilingPx($pane)=$c not below pane-64", c <= pane - f)
                assertTrue("composerHostCeilingPx negative: $c", c >= 0)
            }
        }
    }

    /**
     * 即便宿主**一点都不肯让**（hostPaid = bar + card），band 依然 ≥ 地板。
     */
    @Test
    fun theBandSurvivesEvenWhenTheHostAsksForEverything() {
        for (windowDp in listOf(672, 640, 768, 600, 561, 480, 320)) {
            for (composerLines in 1..3) {
                for (queueRows in listOf(0, 2, 11, 23, 60)) {
                    val pane = windowDp
                    val floorPx = min(64, pane)
                    val card = SessionPaneLayout.composerFloorFor(composerLines)
                    val bar = SessionPaneLayout.queueBarHeight(
                        rows = queueRows,
                        pane = pane.dp,
                        composerFloor = card,
                    )
                    val greedy = bar.value.toInt() + card.value.toInt()
                    val timeline = SessionPaneLayout.viewportHeightPx(
                        shared = true, pane = pane, composer = greedy, floorPx = floorPx,
                    )
                    assertTrue(
                        "greedy host stole the band: timeline=$timeline floor=$floorPx " +
                            "window=$windowDp lines=$composerLines rows=$queueRows",
                        timeline >= floorPx,
                    )
                }
            }
        }
    }

    /**
     * 既有 pin 看到的数值**逐字不变**：`floorPx` 默认 0，3 参形态与修前一致。
     * 这是「不许放宽任何既有断言」的机器可证形式。
     */
    @Test
    fun theOldThreeArgumentCallSeesExactlyWhatItSawBefore() {
        for (pane in 0..600) {
            for (composer in 0..600) {
                for (shared in listOf(true, false)) {
                    assertEquals(
                        SessionPaneLayout.viewportHeightPx(shared, pane, composer),
                        SessionPaneLayout.viewportHeightPx(shared, pane, composer, 0),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ ③ 结构 pin

    /**
     * measure policy 必须**真的**把地板用上：宿主上限走 `composerHostCeilingPx`，
     * 视口走带 `floorPx` 的 4 参重载，且 `minHeight = floorPx` 落在视口约束上。
     */
    @Test
    fun theMeasurePolicyChargesTheBandBeforeItChargesTheViewport() {
        val policy = sessionScreen.substringAfter("private fun SessionPaneArrangement(")
        assertTrue(
            "the host is not capped at pane - floor",
            policy.contains("SessionPaneLayout.composerHostCeilingPx("),
        )
        assertTrue(
            "the viewport is not measured with a floor term",
            policy.contains("floorPx = floorPx,"),
        )
        assertTrue(
            "the viewport constraints carry no minHeight = floorPx",
            policy.contains("minHeight = floorPx,"),
        )
        assertTrue(
            "the arrangement takes no timelineFloor parameter",
            sessionScreen.contains("timelineFloor: Dp,"),
        )
        assertTrue(
            "the call site does not pass timelineFloor",
            sessionScreen.contains(
                "timelineFloor = SessionPaneLayout.timelineViewportFloor(paneHeight)",
            ),
        )
        // 不得退回「下限 0」：4 参重载里必须出现 floorPx 的 coerceAtLeast。
        val overload = sessionPaneLayout.substringAfter("fun viewportHeightPx(\n        shared: Boolean,")
            .substringBefore("fun composerHostCeilingPx(")
        assertTrue(
            "the shared subtraction is still floored at 0",
            overload.contains("coerceAtLeast(floorPx)"),
        )
    }

    /**
     * 队列条出现时机语义不变：`Composer.kt` 的挂载判据一个字符都没动。
     */
    @Test
    fun theQueueBarStillAppearsUnderTheSameRule() {
        val composer = source(
            "src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt",
        )
        assertTrue(
            "the queue bar's mount rule changed",
            composer.contains("queueSupported && queuedRows.isNotEmpty()"),
        )
    }

    // ------------------------------------------- v101 加严：只加严，不削弱任何既有断言

    /**
     * v101 加严：凡 `pane >= 64` 的 pane，地板**逐字**是 64 —— 不缩放、不随窗口
     * 变化。全整数扫描 64..900。
     */
    @Test
    fun theBandIsHardAndUnscaledOnEveryAffordablePane() {
        for (pane in 64..900) {
            assertEquals(
                "timelineViewportFloor scaled the band down at pane=$pane",
                64.dp,
                SessionPaneLayout.timelineViewportFloor(pane.dp),
            )
        }
    }

    /**
     * v101 加严：把极端窗口那一格也钉进既有口径（原先只跑 `window ∈ {720, 600}`
     * 且 `pane = window - ime` 的 24 组合，**没有**覆盖 `pane` 本身 < 64 的格）。
     * 这一格上地板让位到 pane，且**时间线吃掉整个 pane** —— 让位必须让到极限。
     */
    @Test
    fun aPaneBelowTheBandYieldsToTheLimitAndNotPastIt() {
        for (pane in 0..63) {
            val floorPx = SessionPaneLayout.timelineViewportFloor(pane.dp).value.toInt()
            assertEquals("the yield must hand the pane at pane=$pane", pane, floorPx)
            val hostCeiling = SessionPaneLayout.composerHostCeilingPx(pane, floorPx)
            val hostPaid = min(barAndCardAt(pane), hostCeiling)
            val timeline = SessionPaneLayout.viewportHeightPx(
                shared = true, pane = pane, composer = hostPaid, floorPx = floorPx,
            )
            assertEquals("host has no room on a sub-64 pane: pane=$pane", 0, hostCeiling)
            assertEquals("nothing is left to squeeze at pane=$pane", pane, timeline)
        }
    }

    /** 让位窗里最贵的一档宿主（2~3 行 composer + 满队列条）付多少。 */
    private fun barAndCardAt(pane: Int): Int {
        val card = SessionPaneLayout.composerFloorFor(3).value.toInt()
        val bar = SessionPaneLayout.queueBarHeight(
            rows = 23, pane = pane.dp, composerFloor = card.dp,
        ).value.toInt()
        return bar + card
    }
}
