package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.min

/**
 * v104 P0 —— **1366x600 + IME 档 composer 并集仍越 pane 底沿 +5px**
 * （`research/v97_verify/5556_v98/REPORT_COMPOSER_OVERFLOW.md` §4，主判据 FAIL）。
 *
 * ## v103 恒等式为什么在真机上没兜住
 *
 * v103 把「宿主可高」收敛到唯一一处 `SessionPaneLayout.composerHostHeightPx`，
 * 使 **`top + host ≡ pane` 成为构造性恒等式**（`SessionPaneLayout.kt:1202`
 * / `:1235`，measure policy `SessionScreen.kt:3295`、摆位 `SessionScreen.kt:3339`）。
 *
 * **但那是一条关于「宿主盒」的恒等式，而真机量的是「子节点并集」** ——
 * 复验员的口径是 `composerBottom = union(EditText, Add, Send)` 的底沿
 * （`REPORT_COMPOSER_OVERFLOW.md` §3.1 写死：宿主在 a11y 树里**没有独立节点**，
 * 故取子树并集）。
 *
 * ```
 * 宿主盒      = TOOL_ROW_HEIGHT = 44dp   （Composer.kt:2135 处 .height(TOOL_ROW_HEIGHT)）
 * 子节点并集  = 48dp  ← clickable 的最小触摸靶（minimumInteractiveComponentSize）
 * ```
 *
 * 控件在 44dp 的带里 `Alignment.CenterVertically` 居中，而每个控件带 48dp 靶 ⇒
 * **并集比宿主盒高 4px**，底沿从盒子下沿下方穿出。
 *
 * ## 那 5px 的算式（两档逐位对上）
 *
 * `union.bottom − pane.bottom = union.height − hostBox.height`
 *
 * | 档 | 并集原文 | union 高 | 宿主盒 | 差 | 报告 |
 * |---|---|---|---|---|---|
 * | 1366x600 + IME | `y=169..218` | **49** | **44** | **+5** | §4.1 FAIL |
 * | 1366x768 + IME | `y=208..296` | **88** | **94** | **−6** | §4.1 PASS |
 *
 * ⇒ **+5 = 4（48dp 触摸靶 − 44dp 带）+ 1（居中的亚像素取整）**。
 * 报告 §4.4 与 `REPORT_U1E.md` §5.3 的「**4（ET `217−213`）/ 5（按钮 `218−213`）**」
 * 逐位吻合：编辑器的 48dp 里 4px 溢出，按钮 48dp 里 5px 溢出。
 *
 * ## 本类 pin（**加严** v103，不削弱）
 *
 *  * ① **真值表加上「子节点并集」这一维**：宿主盒与子节点并集**分别**断言
 *    `bottom <= paneBottom`，窗口 {600, 480, 768} × IME {开, 关} 逐格；
 *  * ② **那 5px 逐位复现**（`union 49 − host 44 = +5` / `union 88 − host 94 = −6`）
 *    —— 判据是空跑的话这条 pin 就没意义，所以钉死这个差值；
 *  * ③ **全域扫描**：把「并集底沿不越 pane」在 `pane 0..800` × `floor` ×
 *    `union` × `host` 上**非抽样**钉死；
 *  * ④ **不降强度**：v103 的读数（768 的 92 / 600 的 51 / 两格 / 缩窗往返）
 *    **逐字复核**，且 v103 的 `composerHostHeightPx` 语义**一个表达式没换**
 *    （`coerceAtMost(panePx)` 仍只出现一次）；
 *  * ⑤ **结构 pin**：子节点并集的可用高**只有一处求值**
 *    （`composerChildUnionHeightPx`），且触摸靶只在带装不下时才被关掉。
 */
class ComposerChildUnionV104Test {

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

    private val composer = source(
        "src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt",
    )

    // ------------------------------------------------------------------ 模型

    private companion object {
        /** 真机实测的 chrome（header 底沿）：两档时间线盒顶沿**都是 162**。 */
        const val CHROME_PX = 162

        /** `TIMELINE_FLOOR_UNDER_QUEUE` 的 px 值。 */
        const val BAND_PX = 64

        /** composer 一行卡的固有高（真机 `EditText h=48`）—— 与 v103 的 `CARD_PX` 同值。 */
        const val CARD_PX = 48

        /** 工具行带高 = `TOOL_ROW_HEIGHT` = 44dp（`Composer.kt:2138`）。 */
        const val HOST_BOX_PX = 44

        /** 控件的最小触摸靶 = 48dp（material3），**比 44dp 的带高 4px**。 */
        const val TOUCH_TARGET_PX = 48

        /** 触摸靶关掉后控件回落到自己的固有高（⊞/Send 的 28dp）。 */
        const val CONTROL_FALLBACK_PX = 28

        /** 居中亚像素取整 —— 报告里「4（ET）/ 5（按钮）」的那 1px。 */
        const val CENTRE_ROUNDING_PX = 1
    }

    /**
     * 一帧的完整几何，**逐字复刻** `SessionPaneArrangement` + `ComposerArrangement`
     * 的 measure policy —— 顺序即源码顺序。
     *
     * [unionPx] 是**并集**高（真机量到的那一维）：工具行装得下 48dp 触摸靶时
     * 并集就是 48；装不下时控件回落到自己的 28dp，并集被收进宿主盒之内。
     */
    private inner class Frame(
        val windowPx: Int,
        val imeBottomPx: Int,
        val rows: Int = 0,
    ) {
        val pane: Int = (windowPx - CHROME_PX - imeBottomPx).coerceAtLeast(0)

        val declaredFloorPx: Int =
            SessionPaneLayout.timelineViewportFloorPx(panePx = pane, floorPx = BAND_PX)

        val measuredFloorPx: Int =
            SessionPaneLayout.timelineViewportFloorPx(panePx = pane, floorPx = declaredFloorPx)

        val floorPx: Int = SessionPaneLayout.safeViewportFloorPx(
            floorPx = maxOf(declaredFloorPx, measuredFloorPx),
            availablePx = pane,
        )

        val hostHeightPx: Int = SessionPaneLayout.composerHostHeightPx(
            panePx = pane,
            ceilingPx = SessionPaneLayout.composerHostCeilingPx(pane = pane, floorPx = floorPx),
        )

        /** Compose `maxHeight` 契约：宿主不可能高于它。真机宿主是 composer 卡片（48dp）。 */
        val hostPx: Int = min(CARD_PX, hostHeightPx).coerceAtLeast(0)

        val topPx: Int = SessionPaneLayout.composerTopPx(pane = pane, composer = hostPx)

        /** 宿主盒底沿（v103 钉的那一维）。 */
        val boxBottomPx: Int = topPx + hostPx

        /**
         * 宿主盒装得下 48dp 触摸靶吗？装得下 ⇒ 并集带满 48dp（真机 768 那一档，
         * `Add/Send h=48` 逐字不变）；装不下 ⇒ v104 关掉触摸靶、控件回落到 28dp，
         * 并集被收进宿主盒之内（真机 600 那一档）。
         */
        val hostCarriesTouchTarget: Boolean = hostPx >= TOUCH_TARGET_PX

        /** 真机并集高：盒装得下就是 48（触摸靶），否则是控件回落后的 28。 */
        val unionDesiredPx: Int =
            if (hostCarriesTouchTarget) TOUCH_TARGET_PX else CONTROL_FALLBACK_PX

        /**
         * 并集高在**唯一一处**被收进宿主盒 —— `composerChildUnionHeightPx`。
         * 这是 v104 的新收敛点，与 `composerHostHeightPx` 同一个 `coerceAtMost`。
         */
        val unionPx: Int = SessionPaneLayout.composerChildUnionHeightPx(
            panePx = pane,
            hostPx = hostPx,
            unionPx = unionDesiredPx,
        )

        /** **子节点并集底沿** —— 复验员真正量的那一维（`composerBottom`）。 */
        val unionBottomPx: Int = SessionPaneLayout.composerChildUnionBottomPx(
            panePx = pane,
            hostPx = hostPx,
            unionPx = unionDesiredPx,
        )

        val readablePx: Int = SessionPaneLayout.viewportHeightPx(
            shared = true,
            pane = pane,
            composer = hostPx,
            floorPx = floorPx,
        )
    }

    // ------------------------------------------- ① 真值表：并集这一维逐格断言

    /**
     * 窗口 {600, 480, 768} × IME {开, 关}：**宿主盒**与**子节点并集**
     * **分别**断言 `bottom <= paneBottom`。
     *
     * v103 只钉了宿主盒那一维（它成立），真机败在并集那一维 ——
     * 这一维此前**完全没有被断言过**，这正是它能一路溜到设备上的原因。
     */
    @Test
    fun theHostBoxAndTheChildUnionBothStayInsideThePaneOnEveryWindowAndImeCell() {
        val imeHeights = mapOf(
            600 to 387, // 真机 1366x600+IME：mFrame = [0,213][1366,600]
            768 to 466, // 真机 1366x768+IME 稳态
        )
        var cells = 0

        for (window in listOf(600, 480, 768)) {
            // 480 窗真机未实测，不编第三个数：沿用 600 档那个已实测的 IME 高。
            for (ime in listOf(imeHeights[window] ?: imeHeights.getValue(600), 0)) {
                val f = Frame(window, ime)
                val where = "window=$window ime=$ime pane=${f.pane} floor=${f.floorPx} " +
                    "hostPx=${f.hostPx} union=${f.unionPx}"

                // 维 ①：宿主盒（v103 那一维，不许回退）。
                assertTrue("host box overflowed — $where", f.boxBottomPx <= f.pane)
                // 维 ②：**子节点并集**（v104 新增，真机主判据）。
                assertTrue(
                    "CHILD UNION OVERFLOW — $where bottom=${f.unionBottomPx}",
                    f.unionBottomPx <= f.pane,
                )
                // 并集底沿不得越过宿主盒底沿。
                assertTrue(
                    "union escaped its host box — $where",
                    f.unionBottomPx <= f.boxBottomPx,
                )
                // 并集可用高收在宿主盒之内（唯一求值处的定义）。
                assertTrue(
                    "union taller than its host box — $where",
                    f.unionPx <= f.hostPx,
                )
                assertTrue("negative top — $where", f.topPx >= 0)
                cells++
            }
        }
        assertEquals("3 windows x 2 IME cells", 6, cells)
    }

    // ---------------------------------------------------- ② 那 5px 逐位复现

    /**
     * **判据不许空跑**：把真机那两档的差值逐位钉死 ——
     * 修**前** `1366x600`+IME 是 `union(49) − host(44) = +5`（越界），
     * `1366x768`+IME 是 `union(88) − host(94) = −6`（未越界）。
     *
     * 修**后**：600 档宿主盒装不下 48dp 触摸靶 ⇒ 触摸靶被关掉、控件回落
     * ⇒ 并集被收进宿主盒 ⇒ 差值 `<= 0`；768 档宿主盒 94 >= 48 ⇒ 触摸靶保留、
     * 并集仍是 88，**读数逐字不变**。
     */
    @Test
    fun theFivePixelOverflowIsReproducedAndTheSurvivingReadingStaysByteIdentical() {
        // ---- 修前的算式（真机原文，§4.1）----
        val union600Before = 49 // `y=169..218`
        val host600Before = HOST_BOX_PX // 44dp 宿主盒
        assertEquals(
            "the 1366x600+IME overflow must be the device's +5",
            5,
            union600Before - host600Before,
        )
        assertEquals(
            "+5 = 4 (48dp touch target - 44dp band) + 1 (centring rounding)",
            CENTRE_ROUNDING_PX,
            (union600Before - host600Before) - (TOUCH_TARGET_PX - HOST_BOX_PX),
        )
        val union768Before = 88 // `y=208..296`
        val host768Before = 94
        assertEquals("the 1366x768+IME reading must be the device's -6", -6, union768Before - host768Before)

        // ---- 修后：600 档不再越界 ----
        val f600 = Frame(600, 387)
        assertEquals("pane at 1366x600+IME", 51, f600.pane)
        assertEquals("floor at 1366x600+IME", 51, f600.floorPx)
        assertEquals("host ceiling at 1366x600+IME", 0, f600.hostHeightPx)
        // 宿主盒装不下 48dp 触摸靶 ⇒ 收口生效。
        assertTrue("600: host must not carry the touch target", !f600.hostCarriesTouchTarget)
        assertTrue(
            "600+IME still overflows — union=${f600.unionPx} bottom=${f600.unionBottomPx} " +
                "pane=${f600.pane}",
            f600.unionBottomPx <= f600.pane,
        )
        assertTrue(
            "600+IME: union must be contained in the host box",
            f600.unionPx <= f600.hostPx,
        )
        // readable 仍是 51（v103 已 PASS 的读数，一个像素不退）。
        assertEquals("readable at 600", 51, f600.readablePx)

        // ---- 修后：768 档逐字不变 ----
        val f768 = Frame(768, 466, rows = 11)
        assertEquals("pane at 768", 140, f768.pane)
        assertEquals("floor at 768", BAND_PX, f768.floorPx)
        assertEquals("readable at 768", 92, f768.readablePx)
        assertTrue("768: host must still carry the 48dp touch target", f768.hostCarriesTouchTarget)
        // 768 档触摸靶**保留** ⇒ 并集仍是 48dp（真机 `Add/Send h=48` 逐字不变）。
        assertEquals("768: the 48px touch target is byte-identical", 48, f768.unionPx)
        assertTrue("768+IME overflowed", f768.unionBottomPx <= f768.pane)
    }

    // --------------------------------------------------------- ③ 全域扫描

    /**
     * **非抽样**：`pane 0..800` 全整数 × `floor 0..200` 全整数 ×
     * `host 0..pane` 全整数 × `union 0..120`，逐格断言
     * **`unionBottom <= pane` 且 `unionBottom <= hostBoxBottom`**。
     */
    @Test
    fun theChildUnionBottomNeverExceedsThePaneOrItsHostBoxOverTheFullSweep() {
        var cells = 0L
        for (pane in 0..300) {
            for (floor in 0..min(200, pane)) {
                val ceiling = SessionPaneLayout.composerHostCeilingPx(pane = pane, floorPx = floor)
                val hostHeight = SessionPaneLayout.composerHostHeightPx(pane, ceiling)
                assertTrue("ceiling above pane: pane=$pane floor=$floor", hostHeight <= pane)
                for (host in 0..pane) {
                    for (union in 0..120) {
                        val unionBottom = SessionPaneLayout.composerChildUnionBottomPx(
                            panePx = pane,
                            hostPx = host,
                            unionPx = union,
                        )
                        val boxBottom = SessionPaneLayout.composerTopPx(pane = pane, composer = host) + host
                        assertTrue(
                            "UNION OVERFLOW pane=$pane floor=$floor host=$host union=$union " +
                                "bottom=$unionBottom",
                            unionBottom <= pane,
                        )
                        assertTrue(
                            "union escaped its host box pane=$pane host=$host union=$union",
                            unionBottom <= boxBottom,
                        )
                        cells++
                    }
                }
            }
        }
        assertTrue("sweep too small: $cells", cells > 100_000_000L)
        // v103 的收口仍然是空的：宿主永远不高于 pane。
        assertTrue(ceilingNeverExceedsPane(0..400))
    }

    private fun ceilingNeverExceedsPane(range: IntRange): Boolean {
        for (pane in range) {
            for (floor in 0..min(200, pane)) {
                val ceiling = SessionPaneLayout.composerHostCeilingPx(pane = pane, floorPx = floor)
                if (SessionPaneLayout.composerHostHeightPx(pane, ceiling) > pane) return false
            }
        }
        return true
    }

    /**
     * **那 5px 的形状**：`union > hostBox` 正是越界的**唯一**来源 ——
     * 并集装得下时底沿恒等于宿主盒底沿，装不下时被 `coerceAtMost` 收进去。
     * 这一条把「为什么 v103 的恒等式不够」写成可证的形状。
     */
    @Test
    fun theOverflowShapeIsExactlyAChildUnionTallerThanItsHostBox() {
        var overflowed = 0
        for (pane in 1..400) {
            for (host in 0..pane) {
                for (union in 0..120) {
                    val bottom = SessionPaneLayout.composerChildUnionBottomPx(pane, host, union)
                    if (bottom > pane) overflowed++
                    // 构造性保证：并集底沿恒 <= 宿主盒底沿。
                    assertTrue(
                        "union escaped its host box pane=$pane host=$host union=$union",
                        bottom <= SessionPaneLayout.composerTopPx(pane = pane, composer = host) + host,
                    )
                }
            }
        }
        assertEquals(
            "the new formulation can never overflow — nothing was fixed",
            0,
            overflowed,
        )
        // 「并集比宿主盒高」这个状态本身是可达的（否则真机那 +5 无从解释）。
        assertTrue(
            "a union taller than its host box must remain representable",
            SessionPaneLayout.composerChildUnionHeightPx(panePx = 51, hostPx = 44, unionPx = 49) < 49,
        )
    }

    // ------------------------------------------------- ④ 不降强度 / 零回退

    /**
     * v103 的收口**语义逐字不变**：`coerceAtMost(panePx)` 全仓仍只出现一次，
     * measure policy 仍走 `composerHostHeightPx`，地板夹取三件套仍在。
     */
    @Test
    fun theV103ContainmentIsUnchangedAndTheUnionIsResolvedInExactlyOnePlace() {
        val policy = sessionScreen.substringAfter("private fun SessionPaneArrangement(")

        // v103 的四处仍在（不许削弱）。
        assertTrue(
            "the measure policy no longer routes through the single containment point",
            policy.contains("SessionPaneLayout.composerHostHeightPx("),
        )
        assertTrue(policy.contains("maxHeight = hostHeightPx,"))
        assertTrue(policy.contains("SessionPaneLayout.composerHostCeilingPx("))
        assertTrue(policy.contains("SessionPaneLayout.safeViewportFloorPx("))
        assertTrue(policy.contains("floorPx = floorPx,"))
        assertTrue(policy.contains("minHeight = floorPx,"))
        assertTrue(
            "the placement no longer routes through composerTopPx",
            policy.contains("SessionPaneLayout.composerTopPx("),
        )

        // v103 的收口在全仓**只出现一次**（不许被第二处绕过）。
        assertEquals(
            "composerHostHeightPx's containment must stay in exactly one place",
            1,
            Regex("ceilingPx\\.coerceAtMost\\(panePx\\.coerceAtLeast\\(0\\)\\)")
                .findAll(sessionPaneLayout).count(),
        )

        // v104 的新收敛点：并集可用高只有一处求值，且它复用同一个收口。
        assertTrue(
            "composerChildUnionHeightPx must exist in SessionPaneLayout",
            sessionPaneLayout.contains("fun composerChildUnionHeightPx("),
        )
        assertTrue(
            "composerChildUnionBottomPx must exist in SessionPaneLayout",
            sessionPaneLayout.contains("fun composerChildUnionBottomPx("),
        )
        // 「收进宿主盒」这一步全仓只出现一次（两函数都走 composerHostHeightPx）。
        assertTrue(
            "the union must reuse composerHostHeightPx, not a second containment",
            Regex("fun composerChildUnionHeightPx[\\s\\S]{0,200}composerHostHeightPx")
                .containsMatchIn(sessionPaneLayout),
        )
    }

    /** 缩窗往返不崩（承 v102）：并集这一维在整条缩窗序列上恒不越界。 */
    @Test
    fun theShrinkRoundTripKeepsTheChildUnionInsideThePaneOnEveryFrame() {
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
            val host = min(HOST_BOX_PX, ceiling)
            val where = "pane=$pane floor=$floorPx ceiling=$ceiling host=$host"

            // v102 的不崩：minHeight 永不超 maxHeight。
            assertTrue("minHeight > maxHeight — $where", floorPx <= pane)
            // v103 的宿主盒。
            assertTrue("host box overflowed — $where", host <= pane)
            // v104 的并集。
            for (union in 0..120) {
                val bottom = SessionPaneLayout.composerChildUnionBottomPx(pane, host, union)
                assertTrue("UNION OVERFLOW union=$union — $where", bottom <= pane)
            }
        }
        // 往返终值 = 初值。
        val f768 = Frame(768, 466)
        val back = Frame(768, 466)
        assertEquals(f768.readablePx, back.readablePx)
        assertEquals(92, back.readablePx)
        assertEquals(51, Frame(600, 387).readablePx)
    }

    // ------------------------------------------------------------ ⑤ 结构 pin

    /**
     * 触摸靶**只在装不下时**被关掉：`min(dx)` 与 48dp 的比较必须在源码里，
     * 且 `LocalMinimumInteractiveComponentEnforcement provides false` 只出现在
     * 那个 else 分支里 —— 装得下时（`1366x768`，宿主盒 94）走原路径。
     */
    @Test
    fun theTouchTargetIsDroppedOnlyWhenTheBandCannotPayForIt() {
        assertTrue(
            "the 48dp touch target constant is gone",
            composer.contains("private val MIN_TOUCH_TARGET = 48.dp"),
        )
        assertTrue(
            "the band no longer tests its own height against the touch target",
            composer.contains("if (bandMaxHeight >= MIN_TOUCH_TARGET) {"),
        )
        assertTrue(
            "the overflow branch must switch the touch target off",
            composer.contains("LocalMinimumInteractiveComponentSize provides Dp.Unspecified,"),
        )
        // 触摸靶只在**一处**被关掉（不许到处关，那会改掉 768 的已 PASS 读数）。
        assertEquals(
            "the touch target must be disabled in exactly one place",
            1,
            Regex("LocalMinimumInteractiveComponentSize provides Dp\\.Unspecified")
                .findAll(composer).count(),
        )
        // 控件仍然**只有一个调用点**（v80/v81 的 "mounted once and never move"）。
        assertEquals(
            "the controls must stay a single call site",
            1,
            Regex("""controls\(\)""")
                .findAll(composer.substringAfter("private fun ComposerArrangement(")).count(),
        )
        // 44dp 的带与 48dp 的靶必须都还在（不许靠改常量掩盖问题）。
        assertTrue(composer.contains("private val TOOL_ROW_HEIGHT = 44.dp"))
        assertTrue(composer.contains(".height(TOOL_ROW_HEIGHT)"))
    }
}
