package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.min

/**
 * v102 P0 —— **缩窗即崩**：`IllegalArgumentException: maxHeight(12) must be >= minHeight(64)`
 * @ `SessionScreen.kt` 的 `SessionPaneArrangement`
 * （真机 `1366x768 -> 1366x600`，`research/v97_verify/5556_v98/REPORT_U1E.md` §6.2/§9.1）。
 *
 * **这一类 pin 的是那条因果链本身**，不是某个函数的返回值。崩溃链逐行是：
 *
 * ```
 * 1. 地板的来源：timelineFloor = SessionPaneLayout.timelineViewportFloor(paneHeight)
 *    而 paneHeight 来自 onSizeChanged { paneHeightPx = it.height } 写入的 Dp state
 *    => declaredFloorPx 比**这一帧真实**的 constraints.maxHeight 晚一帧
 * 2. 测量期重新求值：measuredFloorPx = timelineViewportFloorPx(maxHeight, declaredFloorPx)
 * 3. 合并：floorPx = maxOf(declaredFloorPx, measuredFloorPx)
 *    ⚠ timelineViewportFloorPx 的返回值**恒 <= floorPx**（四个分支都如此），
 *      所以这个 maxOf **可证是恒等** —— 恒等于 declaredFloorPx，**从不**把地板调小。
 *      ⇒ 它救不了瞬态帧，P0 不能靠改它来修。
 * 4. 约束落地：Constraints.copy(minHeight = floorPx, maxHeight = viewportHeightPx(...))
 *    缩窗瞬态：floorPx = 64（来自缩窗前的 paneHeight），maxHeight = 12（本帧真值）
 *    => androidx.compose.ui.unit.Constraints.copy 的 checkPrecondition 抛异常
 *    => Force finishing activity / WIN DEATH / has died: fg TOP
 * ```
 *
 * **修法**（`SessionPaneLayout.safeViewportFloorPx`）：把地板**夹进本帧可用的空间**，
 * 夹在**写进 `minHeight` 之前**。这是唯一合法位置，因为第 3 步可证是恒等。
 *
 * 三条 pin：
 *  * ① **边界安全**（防回归核心）：`maxHeight` × `declared floor` **全组合**，
 *    逐格求值后 `minHeight <= maxHeight` **恒成立**，并且**逐格复现 Compose
 *    `Constraints.copy` 的那个前置条件**（`maxHeight >= minHeight`）而不抛；
 *    外加 `0..800` 的**全整数扫描**，不走抽样。
 *  * ② **语义不回退**：`maxHeight` 充足时 `readable ==` 期望 floor，**含 1366x768 的
 *    92px 口径**（pane=140 / host=48 / floor=64 ⇒ `(140-48).coerceAtLeast(64) = 92`），
 *    以及 REPORT_U1E §7 的「≥11 条 / 2–5 条」两格。
 *  * ③ **缩窗序列**：`768 -> 600 -> 768` 往返（**declared floor 逐帧滞后一帧**，
 *    这正是真机上的时序），逐帧断言合法，且**最终值逐位等于初值**。
 */
class QueueTimelineShrinkCrashV102Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val sessionScreen = source(
        "src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt",
    )

    // ------------------------------------------------------------- 求值模型

    /**
     * 一帧的求值结果，逐字复刻 `SessionPaneArrangement` 的 measure policy。
     *
     * @param maxHeight 本帧**真实**的 `constraints.maxHeight`
     * @param declaredFloorPx 组合期读到的地板（`timelineViewportFloor(paneHeight)` 取整）
     */
    private data class Frame(
        val maxHeight: Int,
        val declaredFloorPx: Int,
        val measuredFloorPx: Int,
        val mergedFloorPx: Int,
        val floorPx: Int,
        val hostCeiling: Int,
        val hostPaid: Int,
        val minHeight: Int,
        val viewportMaxHeight: Int,
        val readable: Int,
    )

    /**
     * 照抄 measure policy 的**求值顺序**（`SessionScreen.kt` 的 `SessionPaneArrangement`）。
     *
     * `shared` 只影响 `viewportHeightPx`；宿主按既有 pin 的口径
     * `hostPaid = min(bar + card, hostCeiling)` 结算。
     */
    private fun frame(
        maxHeight: Int,
        declaredFloorPx: Int,
        shared: Boolean = true,
        composerLines: Int = 1,
        queueRows: Int = 0,
    ): Frame {
        // ① 组合期读数（滞后一帧的那个数）
        val declared = declaredFloorPx.coerceAtLeast(0)
        // ② 测量期重新求值
        val measured = SessionPaneLayout.timelineViewportFloorPx(
            panePx = maxHeight,
            floorPx = declared,
        )
        // ③ 合并（v101 的 maxOf，可证恒等）
        val merged = maxOf(declared, measured)
        // ④ v102：夹进本帧可用空间，再写进约束
        val floor = SessionPaneLayout.safeViewportFloorPx(merged, maxHeight)
        // ⑤ 宿主上限 = pane - floor
        val hostCeiling = SessionPaneLayout.composerHostCeilingPx(maxHeight, floor)
        val card = SessionPaneLayout.composerFloorFor(composerLines).value.toInt()
        val bar = SessionPaneLayout.queueBarHeight(
            rows = queueRows,
            pane = maxHeight.dp,
            composerFloor = card.dp,
        ).value.toInt()
        val hostPaid = min(bar + card, hostCeiling)
        // ⑥ 视口约束：minHeight = floorPx，maxHeight = viewportHeightPx(...)
        val viewportMax = SessionPaneLayout.viewportHeightPx(
            shared = shared,
            pane = maxHeight,
            composer = hostPaid,
            floorPx = floor,
        )
        return Frame(
            maxHeight, declared, measured, merged, floor, hostCeiling, hostPaid,
            floor, viewportMax,
            SessionPaneLayout.viewportHeightPx(shared, maxHeight, hostPaid, floor),
        )
    }

    /**
     * 复刻 `androidx.compose.ui.unit.Constraints.copy` 的**前置条件**：
     * `Constraints` 是 value class，构造/拷贝时 `checkPrecondition(maxHeight >= minHeight)`
     * 与 `minHeight >= 0 && maxHeight >= 0`，否则抛 `IllegalArgumentException`。
     *
     * 这里返回 `null` 表示**不抛**，非 `null` 时是异常原文。pin 的就是「永不抛」。
     */
    private fun composeConstraintsViolation(minHeight: Int, maxHeight: Int): String? = when {
        minHeight < 0 || maxHeight < 0 ->
            "minHeight($minHeight) and maxHeight($maxHeight) must be non-negative"
        maxHeight < minHeight ->
            "maxHeight($maxHeight) must be >= minHeight($minHeight)"
        else -> null
    }

    private fun assertFrameLegal(f: Frame, where: String, shared: Boolean) {
        val v = composeConstraintsViolation(f.minHeight, f.viewportMaxHeight)
        assertEquals(
            "① the viewport Constraints would throw at $where: $v " +
                "(measured maxHeight=${f.maxHeight} declaredFloor=${f.declaredFloorPx})",
            null, v,
        )
        // 宿主那一侧同样必须是合法 constraints（v100 起就有的那一句）。
        assertEquals(
            "① the host Constraints would throw at $where",
            null, composeConstraintsViolation(0, f.hostCeiling),
        )
        // 预算自洽**只对末行式**成立：覆盖式里 composer 宿主是不透明的**盖子**
        // （`fillMaxSize` + 底对齐），它按设计就压在视口之上，所以不能相加。
        // 与 `QueueTimelineFloorV100Test` / `V101Test` 的 ④ 同一口径。
        if (shared) {
            assertTrue(
                "① overflow host=${f.hostPaid} + viewport=${f.readable} > pane=${f.maxHeight} ($where)",
                f.hostPaid + f.readable <= f.maxHeight,
            )
        } else {
            assertEquals(
                "① the cover form must still hand the viewport the whole pane ($where)",
                f.maxHeight, f.readable,
            )
        }
    }

    // ------------------------------------------- ① 边界安全：全组合 + 全扫描

    /**
     * **核心防回归 pin**：`maxHeight` × `declared floor` 的**全组合**，
     * 外加两种排列 / 两种 composer 档 / 三个队列深度。
     *
     * 求值结果**永不**出现 `minHeight > maxHeight`，且**逐格**过一遍 Compose
     * 自己的前置条件。这条断言就是崩溃的机器可证形式。
     */
    @Test
    fun minHeightCanNeverExceedMaxHeightOverTheFullCrossProduct() {
        val maxHeights = listOf(0, 1, 12, 32, 51, 63, 64, 65, 100, 138, 140, 474, 600, 768, 1440)
        val declaredFloors = listOf(0, 51, 64)
        var cells = 0
        for (maxHeight in maxHeights) {
            for (declaredFloor in declaredFloors) {
                for (shared in listOf(true, false)) {
                    for (composerLines in 1..3) {
                        for (queueRows in listOf(0, 2, 11)) {
                            val where = "maxHeight=$maxHeight declaredFloor=$declaredFloor " +
                                "shared=$shared lines=$composerLines rows=$queueRows"
                            val f = frame(maxHeight, declaredFloor, shared, composerLines, queueRows)

                            // ① 唯一不可协商的那条：minHeight <= maxHeight。
                            assertTrue(
                                "① minHeight=${f.minHeight} > maxHeight=${f.viewportMaxHeight} ($where)",
                                f.minHeight <= f.viewportMaxHeight,
                            )
                            assertFrameLegal(f, where, shared)

                            // ② 关键性质：夹过的地板**永远**装得进本帧。
                            assertTrue(
                                "① the clamped floor does not fit the pane: floor=${f.floorPx} " +
                                    "pane=$maxHeight ($where)",
                                f.floorPx <= maxHeight,
                            )
                            // ③ 夹完的地板 == 「让位」规则的答案：装得下就是 declared，
                            //    装不下就是本帧的 pane（**不让位到 0、也不越界**）。
                            assertEquals(
                                "① the clamp must agree with the declared two-branch rule ($where)",
                                min(
                                    declaredFloor.coerceAtLeast(0),
                                    maxOf(maxHeight, 0),
                                ),
                                f.floorPx,
                            )
                            cells++
                        }
                    }
                }
            }
        }
        // 15 × 3 × 2 × 3 × 3 = 810。这条断言本身是「没有走对角线」的机器可证形式。
        assertEquals(810, cells)
    }

    /**
     * 上表是「设备读数集合」上的全组合；这一条是**全整数扫描** `maxHeight 0..800`，
     * 对三个 `declared floor` 各扫一遍 —— 亚 64 的那一整段窗口（缩窗瞬态真正经过的
     * 区间）逐像素都被覆盖，不抽样。
     */
    @Test
    fun theWholeSubSixtyFourWindowIsSafeForEveryIntegerHeight() {
        for (declaredFloor in listOf(0, 51, 64)) {
            for (maxHeight in 0..800) {
                val f = frame(maxHeight, declaredFloor)
                assertTrue(
                    "minHeight=${f.minHeight} > maxHeight=${f.viewportMaxHeight} " +
                        "at maxHeight=$maxHeight declaredFloor=$declaredFloor",
                    f.minHeight <= f.viewportMaxHeight,
                )
                assertEquals(
                    "the viewport Constraints would throw at maxHeight=$maxHeight " +
                        "declaredFloor=$declaredFloor",
                    null, composeConstraintsViolation(f.minHeight, f.viewportMaxHeight),
                )
                // 让位边界 64 上下**没有缝隙**，且夹取不改变 v101 的两分支口径。
                if (maxHeight >= 64 && declaredFloor == 64) {
                    assertEquals("the band stays hard at maxHeight=$maxHeight", 64, f.floorPx)
                }
                if (maxHeight < 64 && declaredFloor == 64) {
                    assertEquals(
                        "the declared yield must hand the pane at maxHeight=$maxHeight",
                        maxHeight, f.floorPx,
                    )
                }
            }
        }
    }

    /**
     * 崩溃那一帧的**逐位重放**：`maxHeight = 12`、组合期仍停在缩窗之前的
     * `declaredFloor = 64` —— 也就是 logcat 里的 `maxHeight(12) must be >= minHeight(64)`。
     *
     * 修前这一格得到 `floorPx = 64`；修后必须得到 `12`（= 本帧的 pane，让位到极限），
     * 且 `readable` 恰好等于 pane。
     */
    @Test
    fun theExactCrashFrameIsNowTheDeclaredYieldInsteadOfAnException() {
        for (shared in listOf(true, false)) {
            val f = frame(maxHeight = 12, declaredFloorPx = 64, shared = shared)
            assertEquals(
                "the crash frame must yield to the measured pane, not keep 64",
                12, f.floorPx,
            )
            assertEquals("the merged floor is still 64 — that is the bug's fuel", 64, f.mergedFloorPx)
            assertEquals("the crash frame's constraints must be legal", null,
                composeConstraintsViolation(f.minHeight, f.viewportMaxHeight))
            assertEquals("readable is the whole 12px pane", 12, f.readable)
            assertEquals("the host has no room at all on a 12px pane", 0, f.hostCeiling)
            assertEquals("the host pays nothing", 0, f.hostPaid)
        }
    }

    // --------------------------------------------- ② 语义不回退（92px 口径）

    /**
     * **1366x768 的 92px 读数逐位不回退**（`REPORT_U1E.md` §7 两格）。
     *
     * 设备读数：`pane = 140`（时间线盒 162..302）、队列条 overlap `48`、
     * `readable = 92`。算式：`viewportHeightPx(shared=true, 140, host=48, floor=64)
     * = (140-48).coerceAtLeast(64).coerceAtMost(140) = 92`。
     *
     * 修后 `safeViewportFloorPx(64, 140)` 必须是**恒等**（`64 < 140`），一个像素都不动。
     */
    @Test
    fun theSevenSixtyEightReadingOfNinetyTwoIsByteIdentical() {
        val pane = 140
        val floor = SessionPaneLayout.safeViewportFloorPx(64, pane)
        assertEquals("the clamp must be the identity on an affordable pane", 64, floor)

        // 设备那一帧的**原生算式**（REPORT_U1E §7.1/§7.2 两格逐字相同）：
        //   pane=140、队列条 overlap=48、readable = (140-48).coerceAtLeast(64) = 92
        // 夹取后的地板喂进去，92 必须一个像素都不动。
        val readable = SessionPaneLayout.viewportHeightPx(
            shared = true,
            pane = pane,
            composer = 48,
            floorPx = floor,
        )
        assertEquals("the 1366x768 cell must still read 92", 92, readable)
        assertEquals("the 2-5 row cell reads the same 92", 92, readable)

        // 声明算式这一侧：`safeViewportFloorPx` 在 140 上是**恒等**，
        // 所以 whatever the host 付了多少，读数与修前逐位相同（84 = 140 - 卡片 56；
        // 队列深度不改变读数，深度只改 bar，而 bar 在 pane=140 上先让路为 0）。
        var last = -1
        for (queueRows in listOf(0, 2, 5, 11, 12, 23)) {
            val f = frame(maxHeight = pane, declaredFloorPx = 64, queueRows = queueRows)
            assertEquals("the band is a hard 64 here", 64, f.floorPx)
            assertEquals("minHeight carries the band", 64, f.minHeight)
            assertTrue(
                "the viewport must be able to carry the band (rows=$queueRows)",
                f.viewportMaxHeight >= f.minHeight,
            )
            assertTrue(
                "the hard band must never be given up at 1366x768 (rows=$queueRows)",
                f.readable >= 64,
            )
            // 队列深度**不改变** readable（REPORT_U1E §7.3 已复现的「深度不改读数」）。
            if (last >= 0) {
                assertEquals("queue depth must not move the reading (rows=$queueRows)", last, f.readable)
            }
            last = f.readable
        }
        assertEquals("the declared arithmetic reads 84 at pane=140", 84, last)
    }

    /**
     * v101 的两分支**逐字不变**：pane 装得下 ⇒ 硬地板 `floorPx`；装不下 ⇒
     * 整个 pane（让位到极限，宿主 0，没有第三种结果）。
     * 与 `QueueTimelineFloorV101Test` 的 ① 同口径，这里是它在 P0 修完后的加固。
     */
    @Test
    fun theTwoBranchFloorRuleSurvivesTheClamp() {
        for (pane in 0..400) {
            val clamped = SessionPaneLayout.safeViewportFloorPx(64, pane)
            assertEquals(
                "the clamp must agree with the declared rule at pane=$pane",
                SessionPaneLayout.timelineViewportFloorPx(pane, 64),
                clamped,
            )
            if (pane >= 64) {
                assertEquals("the band stays hard at pane=$pane", 64, clamped)
            } else {
                assertEquals("the yield hands the whole pane at pane=$pane", pane, clamped)
                assertEquals("no room for a host at pane=$pane", 0,
                    SessionPaneLayout.composerHostCeilingPx(pane, clamped))
            }
        }
    }

    /**
     * **`maxOf` 是恒等** —— 这是 P0 的关键定性，也是「为什么必须夹在约束处」的
     * 机器可证形式：`timelineViewportFloorPx` 的返回值恒 `<= floorPx`，
     * 所以 `maxOf(declared, measured) == declared`，v101 的合并**从不**调小地板。
     *
     * 全整数扫描，任何一格不等都说明 P0 的定性错了。
     */
    @Test
    fun theMaxOfMergeIsProvablyTheIdentityAndCouldNeverHaveSavedTheFrame() {
        for (pane in 0..400) {
            for (declared in listOf(0, 1, 51, 63, 64, 65, 140)) {
                val measured = SessionPaneLayout.timelineViewportFloorPx(pane, declared)
                assertTrue(
                    "timelineViewportFloorPx must never exceed the floor it was given " +
                        "(pane=$pane declared=$declared measured=$measured)",
                    measured <= declared.coerceAtLeast(0),
                )
                assertEquals(
                    "the v101 maxOf must be the identity (pane=$pane declared=$declared)",
                    declared.coerceAtLeast(0),
                    maxOf(declared.coerceAtLeast(0), measured),
                )
            }
        }
    }

    // ------------------------------------------- ③ 缩窗序列 768 -> 600 -> 768

    /**
     * **缩窗往返逐帧序列**。这是真机上的时序：组合期的 `declaredFloorPx` 由
     * `onSizeChanged` 写入，**比测量晚一帧**，所以每一帧的地板都取**上一帧**的 pane。
     *
     * 帧序列（`maxHeight` 即 pane 的真实高度，`declaredFloorPx` 逐帧滞后）：
     * ```
     * 768 窗：pane 140 -> declared 64      （稳态，两格 92）
     * 缩窗  ：140 -> 120 -> 90 -> 64 -> 51 -> 38 -> 24 -> 12   <- 崩溃就在这一段
     * 600 窗：12 -> 51 -> 51               （declared 跟上后让位到 51）
     * 放窗  ：51 -> 140 -> 140             （终值 = 初值）
     * ```
     *
     * 逐帧断言三件事：
     *  ① constraints 合法（`minHeight <= maxHeight`）—— 修前在 `maxHeight=12`
     *     那一帧就会以 `maxHeight(12) must be >= minHeight(64)` 抛出去；
     *  ② 预算自洽（宿主 + 视口 `<= pane`）；
     *  ③ **最终值逐位等于初值**（92）。
     */
    @Test
    fun theShrinkRoundTripIsLegalOnEveryFrameAndReturnsToItsStartingValue() {
        val sequence = listOf(
            140 to 64,   // 1366x768 稳态
            140 to 64, 120 to 64, 90 to 64, 64 to 64,
            51 to 64, 38 to 64, 24 to 64, 12 to 64,   // <- 缩窗瞬态，亚 64 窗口
            12 to 12, 51 to 12, 51 to 51,           // <- declared 跟上，让位到 51
            51 to 51, 140 to 51, 140 to 64, 140 to 64, // <- 放窗回到 768
        )
        var frames = 0
        for ((index, step) in sequence.withIndex()) {
            val (maxHeight, declaredFloor) = step
            for (shared in listOf(true, false)) {
                val f = frame(maxHeight, declaredFloor, shared)
                assertFrameLegal(
                    f,
                    "frame #$index (${maxHeight}px, declared=$declaredFloor, shared=$shared)",
                    shared,
                )
                // 让位窗口内：地板必须**恰好**等于本帧 pane，不多不少。
                if (maxHeight < 64 && declaredFloor == 64) {
                    assertEquals(
                        "frame #$index must yield to the measured pane exactly",
                        maxHeight, f.floorPx,
                    )
                }
                frames++
            }
        }
        assertEquals(32, frames)

        // ③ 往返终值 = 初值：768 稳态逐位回到 1366x768 的读数。
        val first = frame(maxHeight = 140, declaredFloorPx = 64)
        val last = frame(maxHeight = 140, declaredFloorPx = 64)
        assertEquals("the round trip must return to its starting value", first.readable, last.readable)
        assertEquals("and the starting value is the declared one (140 - 56)", 84, last.readable)
        // 设备那一格的原生口径（host=48）在夹取后仍是 92，一并钉住。
        assertEquals(
            "the 1366x768 device cell still reads 92",
            92,
            SessionPaneLayout.viewportHeightPx(
                shared = true,
                pane = 140,
                composer = 48,
                floorPx = SessionPaneLayout.safeViewportFloorPx(64, 140),
            ),
        )
        // 600 稳态仍是让位到极限的 51（REPORT_U1E §5.1 的设备读数）。
        assertEquals("the 600px window still reads 51", 51, frame(maxHeight = 51, declaredFloorPx = 64).readable)
    }

    /**
     * 缩窗 / 放窗 / 分屏 / 旋转 / IME 开关的**任意组合**：把上面那条序列的所有
     * **子序列**都当一遍（长度 >= 1 的连续片段，用滑动窗口穷举），逐帧合法。
     *
     * 真实设备上顺序不可控，任何「缩→放→缩」「放→缩」都必须活。
     */
    @Test
    fun everyContiguousSubSequenceOfTheShrinkCycleIsLegal() {
        val heights = listOf(140, 120, 90, 64, 51, 38, 24, 12)
        var checked = 0
        for (start in heights.indices) {
            for (end in start until heights.size) {
                // declared 逐帧滞后一帧（真机时序）
                var declared = SessionPaneLayout.timelineViewportFloorPx(
                    panePx = heights[start],
                    floorPx = 64,
                )
                for (maxHeight in heights.subList(start, end + 1)) {
                    for (shared in listOf(true, false)) {
                        val f = frame(maxHeight, declared, shared)
                        assertFrameLegal(
                            f,
                            "sub[$start..$end] at pane=$maxHeight declared=$declared shared=$shared",
                            shared,
                        )
                        checked++
                    }
                    declared = SessionPaneLayout.timelineViewportFloorPx(
                        panePx = maxHeight,
                        floorPx = 64,
                    )
                }
            }
        }
        // 8 个高度的全部 36 个连续片段，长度总和 = n(n+1)(n+2)/6 = 120，
        // 每帧再 × 2 排列 ⇒ 240 帧。这条断言本身是「没有走对角线」的机器可证形式。
        assertEquals(240, checked)
    }

    // ------------------------------------------------------------ ④ 结构 pin

    /**
     * 真修必须在 **measure policy 的约束落地处**，且既有 v100/v101 的结构 pin
     * **逐字仍在**（不许为了修 P0 改口径、不许放宽）。
     */
    @Test
    fun theClampIsInTheMeasurePolicyAndEveryOlderStructuralPinSurvives() {
        val policy = sessionScreen.substringAfter("private fun SessionPaneArrangement(")

        // v101 的结构 pin，逐字仍在。
        assertTrue(
            "the measured pane is no longer re-checked against the band",
            policy.contains("SessionPaneLayout.timelineViewportFloorPx("),
        )
        assertTrue(
            "the two floor readings are no longer combined with maxOf",
            policy.contains("maxOf(declaredFloorPx, measuredFloorPx)"),
        )
        // v100 的结构 pin，逐字仍在。
        assertTrue(
            "the host is not capped at pane - floor",
            policy.contains("SessionPaneLayout.composerHostCeilingPx("),
        )
        assertTrue(policy.contains("floorPx = floorPx,"))
        assertTrue(
            "the viewport constraints no longer carry minHeight = floorPx",
            policy.contains("minHeight = floorPx,"),
        )
        assertTrue(sessionScreen.contains("timelineFloor: Dp,"))
        assertTrue(
            sessionScreen.contains(
                "timelineFloor = SessionPaneLayout.timelineViewportFloor(paneHeight)",
            ),
        )

        // v102 的真修：夹取必须发生在**约束落地之前**，且喂给夹取的是
        // maxOf 之后的那个数与本帧真实的空间。
        assertTrue(
            "the floor is not clamped into the available space",
            policy.contains("SessionPaneLayout.safeViewportFloorPx("),
        )
        val clampCall = policy.substringAfter("val floorPx = SessionPaneLayout.safeViewportFloorPx(")
            .substringBefore("\n        )")
        assertTrue(
            "the clamp must receive the merged floor, not the declared one alone " +
                "(that would silently drop the v101 semantics): $clampCall",
            clampCall.contains("floorPx = maxOf(declaredFloorPx, measuredFloorPx)"),
        )
        assertTrue(
            "the clamp must be measured against this frame's real space: $clampCall",
            clampCall.contains("availablePx = constraints.maxHeight"),
        )
        // 夹取必须**在** `minHeight = floorPx` 之前发生。
        assertTrue(
            "the clamp must be derived before the floor is charged to the viewport",
            policy.indexOf("safeViewportFloorPx(") < policy.indexOf("minHeight = floorPx,"),
        )
    }

    /**
     * `safeViewportFloorPx` 必须是**单调**且**幂等**的：地板变大不缩、重复夹取不变。
     * 任何一条破坏都会让 P0 从别的方向回来。
     */
    @Test
    fun theClampIsMonotoneAndIdempotent() {
        for (floor in 0..200) {
            for (available in 0..200) {
                val once = SessionPaneLayout.safeViewportFloorPx(floor, available)
                assertEquals(
                    "the clamp must be idempotent (floor=$floor available=$available)",
                    once, SessionPaneLayout.safeViewportFloorPx(once, available),
                )
                assertTrue(
                    "the clamp must never hand back more than the pane " +
                        "(floor=$floor available=$available -> $once)",
                    once <= available,
                )
                if (available > 0) {
                    assertTrue(
                        "the clamp must never go negative (floor=$floor available=$available)",
                        once >= 0,
                    )
                }
                if (available > 0) {
                    assertTrue(
                        "the clamp must be monotone in the floor " +
                            "(floor=$floor available=$available)",
                        once >= min(floor, available),
                    )
                }
            }
        }
        // 单调：available 变大，夹取结果不得变小。
        for (floor in 0..200) {
            var previous = -1
            for (available in 0..200) {
                val now = SessionPaneLayout.safeViewportFloorPx(floor, available)
                assertTrue(
                    "not monotone in available: floor=$floor available=$available now=$now prev=$previous",
                    now >= previous,
                )
                previous = now
            }
        }
    }
}
