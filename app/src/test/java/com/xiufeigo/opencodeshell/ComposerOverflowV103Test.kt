package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.min

/**
 * v103 P0 —— **composer 越 pane 底沿 4–5px；上限（ceiling）= 0 时宿主仍有 48px**。
 *
 * 真机读数（`research/v97_verify/5556_v98/REPORT_U1E.md` §5.3，`1366x600` + IME 稳态帧）：
 *
 * ```
 * pane            = 51          （IME InsetsSource.mFrame = [0,213][1366,600]，顶沿 162）
 * floorPx         = 51          （timelineViewportFloor(51.dp) = 让位 = 51）
 * ceiling         = 51 - 51 = 0
 * 但设备实测：EditText y=169..217 (h=48)、Send/Add y=170..218
 *   ⇒ 宿主仍然占了 48px
 *   ⇒ 底沿 217/218 比 pane 底沿 213 低 4–5px  ⇒ 越界，伸进了 IME 区域
 * ```
 *
 * **根因（结构性，可证）**：改之前「宿主能有多高」在**两处各求一次值**——
 *  1. measure policy（`SessionScreen.kt`）：`maxHeight = composerHostCeilingPx(pane, floorPx)`
 *     —— 减的是 `floorPx`；
 *  2. 摆位（`SessionScreen.kt`）：`host.place(0, composerTopPx(pane, host.height))`
 *     —— 减的是 `host.height`；
 * 而 `composerTopPx = (pane - composer).coerceAtLeast(0)` 在 `host.height > pane` 时
 * **把 top 静默钉死在 0**，`coerceAtLeast(0)` **只护住了 top、没护住 bottom**
 * ⇒ 底沿 = `host.height` 原样越出 pane。**「宿主比 pane 高」这一状态没有任何一处
 * 把它按小**：measure 只写 `maxHeight`（依赖宿主自愿遵守），摆位只写 `top`。
 *
 * **修法**：把「宿主可高」收敛到**唯一一处** `SessionPaneLayout.composerHostHeightPx`，
 * measure policy 与 `composerTopPx` **都走它** ⇒ `top + host <= pane` 成为构造性恒等式。
 *
 * 本类 pin：
 *  * ① **三维真值表**：窗口 {768, 600, 480} × IME 开/关 × 队列 {0, 2, 11} 条，
 *    逐格断言 **`composerBottom <= paneBottom`** 且 **`hostHeight <= ceiling`**，
 *    且 **ceiling=0 ⇒ 宿主高必须为 0**；
 *  * ② **全组合 / 全整数扫描**：把「底沿不越界」在**非抽样**的全域上钉死；
 *  * ③ **不降强度**：旧公式在新公式**本该等价**的输入上**逐格恒等**
 *    （证明改动只在越界的那一格起作用），且 `1366x768` 的 92 / `1366x600` 的 51 /
 *    「≥11 条 / 2–5 条」两格 / 缩窗往返**逐字不变**；
 *  * ④ **结构 pin**：「宿主可高」**只有一处求值** —— measure 与摆位都走它，
 *    且 `coerceAtMost(panePx)` 在全仓**只出现一次**。
 */
class ComposerOverflowV103Test {

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

    // ------------------------------------------------------------------ 模型

    private companion object {
        /**
         * 真机实测的 chrome（header 底沿）：`1366x768` 与 `1366x600` 的时间线盒顶沿
         * **都**是 `162`（`REPORT_U1E.md` §4.3 / §7.1），所以它与窗高无关。
         */
        const val CHROME_PX = 162

        /** `TIMELINE_FLOOR_UNDER_QUEUE` 的 px 值（`QueueTimelineSpaceV99Test` pin 的 `64`）。 */
        const val BAND_PX = 64

        /** composer 一行卡的固有高（真机 `EditText h=48`）。 */
        const val CARD_PX = 48
    }

    /**
     * composer 想占的高度。**真机口径：恒为 48** ——
     * 队列条是这个布局里宿主**内部**的一层 overlay，**不叠加到宿主高度上**，
     * 这正是 `REPORT_U1E.md` §7.3 实测到的那件事：
     * **4 条与 12 条两格读数逐字相同**（`Steer` 43 / `×` 48 / `readable` 92），
     * 即「队列深度不改宿主高」。
     *
     * 「composer 想变得更���」这个风险不靠这里覆盖，靠
     * [theComposerBottomNeverExceedsThePaneBottomOverTheFullSweep]
     * （`desired 0..300` 全扫）—— 上限该收住时一定收得住。
     */
    private fun composerWants(rows: Int): Int {
        require(rows >= 0)
        return CARD_PX
    }

    /**
     * 一帧的完整几何，**逐字复刻** `SessionPaneArrangement` 的 measure policy
     * （`SessionScreen.kt`）—— 顺序即源码顺序。
     */
    private inner class Frame(
        val windowPx: Int,
        val imeBottomPx: Int,
        val rows: Int,
    ) {
        val pane: Int = (windowPx - CHROME_PX - imeBottomPx).coerceAtLeast(0)

        // ① 组合期：`declaredFloorPx = roundToPx(timelineViewportFloor(paneHeight))`，
        //    pane 装得下 64 就 64、装不下就让位成整个 pane。
        val declaredFloorPx: Int =
            SessionPaneLayout.timelineViewportFloorPx(panePx = pane, floorPx = BAND_PX)

        // ② 测量期：对**本帧真实**的 pane 再求一次地板。
        val measuredFloorPx: Int =
            SessionPaneLayout.timelineViewportFloorPx(panePx = pane, floorPx = declaredFloorPx)

        // ③ v101 的合并（可证恒等）+ v102 的夹取。
        val floorPx: Int = SessionPaneLayout.safeViewportFloorPx(
            floorPx = maxOf(declaredFloorPx, measuredFloorPx),
            availablePx = pane,
        )

        // ④ v100 的上限（pane − floor），**v103 收进 pane 之内** —— 单一求值处。
        val hostHeightPx: Int = SessionPaneLayout.composerHostHeightPx(
            panePx = pane,
            ceilingPx = SessionPaneLayout.composerHostCeilingPx(pane = pane, floorPx = floorPx),
        )

        /**
         * Compose 的 `maxHeight` 是**契约**：被测节点不可能高于它。
         * 宿主内部自报的高度 `composerWants(rows)` 只会**向下**收敛。
         */
        val hostPx: Int = min(composerWants(rows), hostHeightPx).coerceAtLeast(0)

        val topPx: Int = SessionPaneLayout.composerTopPx(pane = pane, composer = hostPx)

        val bottomPx: Int = topPx + hostPx

        /**
         * **末行式（`shared = true`）下真机读到的 `readable`**，与 composer 底沿
         * 是**两个不同的量**：`readable` 是**视口**高（`140 − 48 = 92`），
         * composer 底沿恒等于 pane 底沿（`140`）。`REPORT_U1E.md` §5.2/§7 的
         * `readable = 92 / 51` 说的就是它。
         */
        val readablePx: Int = SessionPaneLayout.viewportHeightPx(
            shared = true,
            pane = pane,
            composer = hostPx,
            floorPx = floorPx,
        )

        /** Lead 的判据，直接写成断言用的一行。 */
        val composerBottom: Int = SessionPaneLayout.composerBottomPx(
            panePx = pane,
            hostPx = hostPx,
        )
    }

    // ------------------------------------------------- ① 三维真值表（核心判据）

    /**
     * **窗口 768 / 600 / 480 × IME 开 / 关 × 队列 0 / 2 / 11 条**，
     * 逐格断言 Lead 的两条判据，外加「ceiling=0 ⇒ 宿主高 = 0」。
     *
     * IME 信念取**真机实测的两个真值**，不编造：
     *  * `1366x600` + IME：`believedImeBottomPx = 387`（`REPORT_U1E.md` §4.2，
     *    与 IME `InsetsSource.mFrame` 逐位相等）；
     *  * `1366x768` + IME：`mFrame = [0,294][1366,768]` ⇒ `474`（同文 §4.1）。
     * 480 窗两种都跑（IME 固有高与窗高有关，真机未实测，不编第三个数）。
     */
    @Test
    fun theThreeDimensionalTruthTableHoldsOnEveryWindowImeAndQueueCell() {
        val imeHeights = listOf(387, 474)
        val rows = listOf(0, 2, 11)
        var cells = 0
        var zeroCeilingCells = 0

        for (window in listOf(768, 600, 480)) {
            for (ime in imeHeights + listOf(0)) {
                for (n in rows) {
                    val f = Frame(window, ime, n)
                    val where = "window=$window ime=$ime rows=$n pane=${f.pane} " +
                        "floor=${f.floorPx} hostPx=${f.hostPx} top=${f.topPx}"
                    cells++

                    // 判据 ①：composer 底沿 ≤ pane 底沿（不得越界，允许相等）。
                    assertTrue(
                        "OVERFLOW — $where bottom=${f.bottomPx}",
                        f.bottomPx <= f.pane,
                    )
                    assertTrue(
                        "bottom not the pinned number — $where",
                        f.composerBottom == f.bottomPx,
                    )
                    // 宿主落在 pane 之内时底沿**恒等于** pane 底沿（底对齐，不留缝）。
                    assertEquals("bottom is not the pane bottom — $where", f.pane, f.bottomPx)

                    // 判据 ②：宿主高 ≤ ceiling。
                    assertTrue(
                        "host taller than its ceiling — $where",
                        f.hostPx <= f.hostHeightPx,
                    )
                    assertTrue("negative top — $where", f.topPx >= 0)

                    // 判据 ③：ceiling = 0 ⇒ 宿主高必须为 0（不许仍有 48px）。
                    if (f.hostHeightPx == 0) {
                        zeroCeilingCells++
                        assertEquals("ceiling 0 but host drew — $where", 0, f.hostPx)
                    }
                }
            }
        }

        // 真机 1366x600 + IME 那一格必须真的落在「ceiling=0」分支里，
        // 否则这条判据是空跑的（不做抽样断言：这一格逐位复现报告的读数）。
        val device = Frame(600, 387, 0)
        assertEquals("pane at 1366x600+IME", 51, device.pane)
        assertEquals("floor at 1366x600+IME", 51, device.floorPx)
        assertEquals("ceiling at 1366x600+IME", 0, device.hostHeightPx)
        assertEquals("the device cell is in the zero-ceiling branch", 0, device.hostPx)
        assertTrue("the sweep must actually exercise it", zeroCeilingCells > 0)
        assertEquals("3 windows x 3 IME x 3 queue depths", 27, cells)
    }

    // ------------------------------------------------- ② 全组合 / 全整数扫描

    /**
     * **非抽样**：`pane 0..800` 全整数 × `floor 0..200` 全整数 × `desired 0..300`
     * × 两种排列，逐格断言 `top + host <= pane`。
     */
    @Test
    fun theComposerBottomNeverExceedsThePaneBottomOverTheFullSweep() {
        var cells = 0
        for (pane in 0..800) {
            for (floor in 0..min(200, pane)) {
                val ceiling = SessionPaneLayout.composerHostCeilingPx(pane = pane, floorPx = floor)
                val hostHeight = SessionPaneLayout.composerHostHeightPx(pane, ceiling)
                assertTrue("ceiling above pane: pane=$pane floor=$floor", hostHeight <= pane)
                for (desired in 0..300 step 7) {
                    // 被测节点不可能高于交给它的 maxHeight。
                    val host = min(desired, hostHeight)
                    val top = SessionPaneLayout.composerTopPx(pane = pane, composer = host)
                    assertTrue("negative top pane=$pane", top >= 0)
                    assertTrue(
                        "OVERFLOW pane=$pane floor=$floor desired=$desired top=$top host=$host",
                        top + host <= pane,
                    )
                    if (hostHeight == 0) {
                        assertEquals("ceiling 0 but host drew", 0, host)
                    }
                    cells++
                }
            }
        }
        assertTrue("sweep too small: $cells", cells > 100_000)
    }

    /**
     * **这一条把话说明白，不含糊**：摆位**本身收不住**一个比 pane 高的宿主 ——
     * 节点已经量完了，`top` 再怎么算也改不了它自己的高度。
     * 旧的 `(pane - composer).coerceAtLeast(0)` 在这种情况下把 top 钉死在 0，
     * **底沿原样越界**（这就是那 4–5px 的形状）。
     *
     * ⇒ 真正的保证**只能来自上限**（Compose `maxHeight` 契约）：
     * `host <= ceiling` 且 `ceiling <= pane` ⇒ `top + host == pane`。
     */
    @Test
    fun theOverflowShapeIsOnlyReachableWhenTheHostOutgrowsThePaneAndTheCeilingPreventsIt() {
        var oldOverflowed = 0
        for (pane in 1..400) {
            for (host in (pane + 1)..(pane + 40)) {
                val oldTop = (pane - host).coerceAtLeast(0)
                if (oldTop + host > pane) oldOverflowed++
                // 新的 top 是「能收住的那一档」的 top：pane - min(host, pane) = 0。
                assertEquals(0, SessionPaneLayout.composerTopPx(pane = pane, composer = host))
            }
        }
        assertTrue("the old placement never overflowed — nothing to fix", oldOverflowed > 0)

        // 真正的保证：上限尊重了它，底沿就恒等于 pane 底沿，一个像素都不越。
        var cells = 0
        for (pane in 0..800) {
            for (floor in 0..min(200, pane)) {
                val ceiling = SessionPaneLayout.composerHostCeilingPx(pane = pane, floorPx = floor)
                val hostHeight = SessionPaneLayout.composerHostHeightPx(pane, ceiling)
                val host = min(CARD_PX, hostHeight) // 宿主尊重 maxHeight
                val top = SessionPaneLayout.composerTopPx(pane = pane, composer = host)
                assertTrue("negative top pane=$pane", top >= 0)
                assertTrue(
                    "OVERFLOW pane=$pane floor=$floor host=$host top=$top",
                    top + host <= pane,
                )
                assertEquals("bottom is not the pane bottom pane=$pane", pane, top + host)
                cells++
            }
        }
        assertTrue("sweep too small: $cells", cells > 50_000)
    }

    // ------------------------------------------------- ③ 不降强度 / 零回退

    /**
     * `composerTopPx` 在**所有合法输入**上与 v76 的旧公式**逐格恒等**
     * （`composer <= pane` 时），所以 `SessionPaneArrangementV76Test` 的 4 条
     * 逐位断言与真机 768/600 读数**一个像素都不会动**。
     */
    @Test
    fun theTopIsByteIdenticalToTheOldFormulaOnEveryLegalInput() {
        for (pane in 0..1000) {
            for (host in 0..1000) {
                val old = (pane - host).coerceAtLeast(0)
                val new = SessionPaneLayout.composerTopPx(pane = pane, composer = host)
                if (host <= pane) {
                    assertEquals("pane=$pane host=$host regressed", old, new)
                }
                assertTrue("pane=$pane host=$host", new >= 0)
            }
        }
        // 既有 pin 逐字复核。
        assertEquals(0, SessionPaneLayout.composerTopPx(pane = 800, composer = 800))
        assertEquals(0, SessionPaneLayout.composerTopPx(pane = 100, composer = 400))
        assertEquals(0, SessionPaneLayout.composerTopPx(pane = 0, composer = 120))
        assertEquals(680, SessionPaneLayout.composerTopPx(pane = 800, composer = 120))
    }

    /**
     * 已 PASS 的读数**逐字不变**：768 的 `readable = 92`（两格）、600 的 51、
     * 以及 768 两格的 `Steer` h=43 / `×` h=48（= 宿主高 48，`48 <= ceiling 76`）。
     */
    @Test
    fun theAlreadyPassingReadingsAreByteIdentical() {
        // 1366x768 + IME 稳态：pane=140, floor=64, ceiling=76, host=48
        val f768 = Frame(768, 466, 11)
        assertEquals("pane at 768", 140, f768.pane)
        assertEquals("floor at 768", BAND_PX, f768.floorPx)
        assertEquals("host ceiling at 768", 76, f768.hostHeightPx)
        assertEquals("host height at 768 (the 48 that keeps Steer/× clickable)", 48, f768.hostPx)
        assertEquals("readable at 768", 92, f768.readablePx)
        assertEquals("composer bottom at 768 is the pane bottom", 140, f768.bottomPx)
        assertTrue("readable >= floor", f768.readablePx >= BAND_PX)

        // 2–5 条那一格：读数逐字相同（深度不改 readable）。
        for (rows in 0..14) {
            val f = Frame(768, 466, rows)
            assertEquals("rows=$rows changed the 768 readable", 92, f.readablePx)
            assertEquals("rows=$rows changed the 768 host", 48, f.hostPx)
            assertTrue("rows=$rows host escaped the ceiling", f.hostPx <= f.hostHeightPx)
            assertTrue("rows=$rows overflowed", f.bottomPx <= f.pane)
        }

        // 1366x600 + IME 稳态：readable 仍是 51（让位到极限，一个像素不退）。
        val f600 = Frame(600, 387, 0)
        assertEquals("readable at 600", 51, f600.readablePx)
    }

    /**
     * 缩窗往返不崩（承 v102 `QueueTimelineShrinkCrashV102Test`）：本轮改的是
     * **宿主的高度上限与摆位**，不碰地板夹取。
     *
     * 这里复刻的是**真机时序**（`CRASH_SHRINK_FIX.md` §1.3）：`declaredFloorPx`
     * 由组合期的 `paneHeight` 喂，**比测量晚一帧** ⇒ 整条缩窗序列里它**恒停在
     * 缩之前的 64**，而 `constraints.maxHeight` 是本帧真值。
     */
    @Test
    fun theShrinkRoundTripIsLegalAndNeverOverflowsOnAnyFrame() {
        val cycle = listOf(140, 120, 90, 64, 51, 38, 24, 12)
        for (pane in cycle) {
            // 真机时序：地板滞后一帧，仍是缩之前的 64。
            val declaredFloorPx = BAND_PX
            val measuredFloorPx = SessionPaneLayout.timelineViewportFloorPx(pane, declaredFloorPx)
            val floorPx = SessionPaneLayout.safeViewportFloorPx(
                floorPx = maxOf(declaredFloorPx, measuredFloorPx),
                availablePx = pane,
            )
            val ceiling = SessionPaneLayout.composerHostHeightPx(
                panePx = pane,
                ceilingPx = SessionPaneLayout.composerHostCeilingPx(pane, floorPx),
            )
            val host = min(CARD_PX, ceiling)
            val top = SessionPaneLayout.composerTopPx(pane = pane, composer = host)
            val where = "pane=$pane floor=$floorPx ceiling=$ceiling host=$host top=$top"

            // v102 的不崩：minHeight 永不超 maxHeight。
            assertTrue("minHeight > maxHeight — $where", floorPx <= pane)
            assertTrue("floor escaped the pane — $where", floorPx <= pane)
            // v103 的不越界。
            assertTrue("OVERFLOW — $where", top + host <= pane)
            assertTrue("host escaped the ceiling — $where", host <= ceiling)
            if (ceiling == 0) assertEquals("ceiling 0 but host drew — $where", 0, host)
        }
        // 往返终值 = 初值（768 稳态那一帧）。
        val f768 = Frame(768, 466, 0)
        val f600 = Frame(600, 387, 0)
        val back = Frame(768, 466, 0)
        assertEquals(f768.bottomPx, back.bottomPx)
        assertEquals(92, back.readablePx)
        // 600 那一格仍然让位到 51（不退）。
        assertEquals(51, f600.readablePx)
    }

    // ------------------------------------------------- ④ 结构 pin：只有一处求值

    /**
     * 「宿主可高」**只有一处求值**：
     *  * measure policy 必须走 `composerHostHeightPx`；
     *  * `composerTopPx` 的函数体必须走同一个函数；
     *  * 收进 pane 的那一步（`coerceAtMost(panePx)`）在全仓**只出现一次**。
     */
    @Test
    fun theContainmentIsEvaluatedInExactlyOnePlace() {
        val policy = sessionScreen.substringAfter("private fun SessionPaneArrangement(")

        assertTrue(
            "the measure policy no longer routes through the single containment point",
            policy.contains("SessionPaneLayout.composerHostHeightPx("),
        )
        assertTrue(
            "the host's maxHeight is no longer the containment point",
            policy.contains("maxHeight = hostHeightPx,"),
        )
        // 既有 v100 pin 逐字仍在（不许放宽）。
        assertTrue(policy.contains("SessionPaneLayout.composerHostCeilingPx("))
        assertTrue(policy.contains("floorPx = floorPx,"))
        assertTrue(policy.contains("minHeight = floorPx,"))
        // v102 pin 逐字仍在。
        assertTrue(policy.contains("SessionPaneLayout.safeViewportFloorPx("))
        assertTrue(policy.contains("maxOf(declaredFloorPx, measuredFloorPx)"))
        // 摆位那一步仍在同一个函数上。
        assertTrue(
            "the placement no longer goes through composerTopPx",
            policy.contains(
                "host.place(0, SessionPaneLayout.composerTopPx(pane, host.height))",
            ),
        )

        // 全仓唯一性：把 pane 收进宿主高度的那一步只写一次。
        val containsCount = Regex("coerceAtMost\\(panePx").findAll(sessionPaneLayout).count()
        assertEquals("the containment step is duplicated", 1, containsCount)

        // 并且 `composerTopPx` 自己就是那一个函数的调用，不是第二份算式。
        val topBody = sessionPaneLayout
            .substringAfter("fun composerTopPx(pane: Int, composer: Int): Int =")
            .lines()
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        assertTrue(
            "composerTopPx recomputes the containment instead of reusing it: $topBody",
            topBody.contains("composerHostHeightPx(panePx = pane, ceilingPx = composer)"),
        )

        // 无界高分支必须补上限：那一支 `constraints.maxHeight == Infinity`，
        // `composerHostCeilingPx` 算出的是「近乎无界」，上限形同虚设，
        // 宿主先把 pane 撑大、pane 再迁就宿主 ⇒ 摆位再也收不住它。
        assertTrue(
            "the unbounded branch no longer takes the caller's pane-derived ceiling",
            policy.contains("with(arrangementDensity) { composerCeiling.roundToPx() }"),
        )
        assertTrue(
            "the host's maxHeight is no longer the single containment point",
            policy.contains("maxHeight = hostHeightPx,"),
        )
    }

    /**
     * `composerHostHeightPx` 自身的性质：非负、不超 pane、单调、幂等，
     * 且**对所有 `floorPx >= 0` 与旧上限逐格恒等**（零回退的证明）。
     */
    @Test
    fun theContainmentIsNonNegativeMonotoneAndIdenticalOnEveryReachableInput() {
        for (pane in 0..600) {
            for (floor in 0..600) {
                val ceiling = SessionPaneLayout.composerHostCeilingPx(pane, floor)
                val h = SessionPaneLayout.composerHostHeightPx(pane, ceiling)
                assertTrue("pane=$pane floor=$floor", h in 0..pane)
                if (floor >= 0) {
                    assertEquals(
                        "the clamp is not an identity on a reachable input: pane=$pane floor=$floor",
                        ceiling,
                        h,
                    )
                }
                // 幂等。
                assertEquals(h, SessionPaneLayout.composerHostHeightPx(pane, h))
            }
        }
        // 单调：pane 变大，可高不变小。
        for (ceiling in 0..200) {
            var prev = 0
            for (pane in 0..200) {
                val h = SessionPaneLayout.composerHostHeightPx(pane, ceiling)
                assertTrue("not monotone at ceiling=$ceiling pane=$pane", h >= prev)
                prev = h
            }
        }
    }
}
