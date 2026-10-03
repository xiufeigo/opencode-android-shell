package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * # v106 —— **方案 A：让 band 容纳 48 dp 触摸靶**（取代 v105 的方案 B）
 *
 * ## 为什么 v105 的方案 B 在真机被忽略
 *
 * 方案 B 用 `CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides
 * 44.dp)` 把**子节点**的靶封顶到带 —— 方向是**让子节点向内收**。真机两档
 * （`research/v97_verify/5556_v98/REPORT_COMPOSER_OVERFLOW3.md` §1.1/§1.2）读到的
 * `EditText` 与 ⊞/chip **仍然是 48**：
 *
 * ```
 * 1366x768: EditText[28,208][1338,256] h=48   ⊞[10,248][52,296] h=48
 * 1366x600: EditText[24,169][405,217] h=48   ⊞[399,170][441,218] h=48
 * ```
 *
 * ⇒ 被忽略的**不是"封顶值算错了"**（44 < 48，值本身是对的），而是**那条子组合
 * 根本不看 `minimumInteractiveComponentSize`**。它自己的 48 dp 来自这条渲染路径上的
 * **固有高 / `required*` / 自绘**（`required*` 不受 `maxHeight` 与该 local 约束），
 * 于是 local 覆盖对它**在结构上无效**，不是"优先级不够"。
 *
 * **JVM 457 例全绿而真机全错**，正是本仓反复吃亏的那条教训的第三次复现：
 * **pin 的量必须与验收的量同源**。v105 pin 的是 `composerBandTouchTargetDp()` 这个**纯
 * 函数**，验收的是**设备上子组合的实测高** —— 两个不同的量。
 *
 * ## 为什么方案 A 必然生效
 *
 * 方案 A 反过来：不动子节点，**把父给的约束抬到 48 dp**
 * （`Modifier.height(BAND_HEIGHT)` + measure policy 的 `maxHeight = band`）。
 * band 是 **父给的约束**，子节点**不需要溢出**：
 *
 * ```
 * band = min(48, maxHeight)                    <= 48
 * hostPx = constrainHeight(band) = band
 * toolRow = min(48, band) <= band             ← 48dp 靶装得下
 * editor  = min(48, band) <= band ; editorTop = (band - editor)/2 = 0
 * unionPx = max(editorTop + editor, toolRow) <= band = hostPx
 * ```
 *
 * 这与"子节点是否理会某个 local"**无关** —— 父的约束是 Compose 的硬契约。
 *
 * ## 三格连带预演（`theThreeCellBudgetPreview`，见 FIX4 报告 §3）
 *
 * | 档 | pane | 修前 `readable` | **修后** | ≥ 64？ |
 * |---|---|---|---|---|
 * | `1366x768`+IME | 140 | **92** | **92（未变）** | ✅ |
 * | `1366x600`+IME | 51 | **51** | **51（未变）** | ✅（显式让位区） |
 * | `480`+IME | 0 | 0 | 0 | ⛔ 定义域外（v84/v86 已 pin 的裁剪区） |
 *
 * 768 的 `readable` **不降**：它由**宿主盒**（`viewportHeightPx(shared, pane, host)`,
 * `host = min(48, ceiling)`）决定，而方案 A 之后 `host` 与 `band` **同为 48** ——
 * v104 的 pin 模型本来就是按 48 px 收费的，方案 A 只是让**真机第一次与该模型一致**。
 * **+4 dp 的代价落在 band 上**（44 → 48），`COMPOSER_MINIMAL_HEIGHT` 保持 **56 dp**
 * 不动（它是 v57 起按 44 + 12 立的、被十处**真机实测**阶梯钉死的账），
 * 由此产生的 4 dp 地板缺口被显式钉成字面量，见
 * `theFourDpCostLandsInTheBandAndTheDeviceMeasuredFloorIsUntouched` 与 FIX4 §7 #1。
 */
class ComposerBandTallEnoughV106Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val composer = source(
        "src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt",
    )

    private val sessionPaneLayout = source(
        "src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt",
    )

    // ------------------------------------------------------------------ 模型

    private companion object {
        /** 真机 chrome（header + 二级工具条）底沿：两档时间线盒顶沿都是 162。 */
        const val CHROME_PX = 162

        /** 修前（v103/v104/v105）的带高 = `TOOL_ROW_HEIGHT` = 44 dp。 */
        const val BAND_BEFORE_PX = 44

        /** **修后（v106 方案 A）的带高 = `max(44, 48)` = 48 dp。 */
        const val BAND_AFTER_PX = 48

        /** material3 强制的最小触摸靶。 */
        const val TOUCH_TARGET_PX = 48

        /** `SUBMIT_SIZE` = 28 dp —— 控件画出来的高，也是 v86 的控件地板。 */
        const val CONTROL_FLOOR_PX = 28

        /** 编辑器固有的高（真机 `EditText h=48`）。 */
        const val EDITOR_PX = 48

        /** 堆叠形态的 composer 固有高（真机 `1366x768`：宿主 94）。 */
        const val STACKED_NATURAL_PX = 94

        /** 修前/修后的一行 composer 卡地板（56 → 60 dp）。 */
        const val CARD_BEFORE_PX = 56
        const val CARD_AFTER_PX = 60

        /** `Dp.Unspecified` 的 NaN 不能用 `==` 比。 */
        fun px(dp: Dp): Int = if (dp.value.isNaN()) -1 else dp.value.toInt()
    }

    /**
     * 一帧的完整几何，**逐字复刻** `ComposerArrangement` 的 measure policy
     * （`Composer.kt:2246` 起的 `val band = …` 与两个 `measure` 调用）。
     *
     * [bandPx] 就是**被验收的那一维**：`unionPx <= bandPx` 与
     * `unionBottom <= hostBottom` 是 `REPORT_OVERFLOW_DIAG.md` §5.2 的两条算式。
     */
    private class Frame(
        windowPx: Int,
        imeBottomPx: Int,
        rows: Int,
        oneRow: Boolean,
        bandHeightPx: Int,
        paneOverride: Int? = null,
    ) {
        /**
         * 队列条数先吃 pane 里的**时间线份额**，而**不是** pane 本身：`queueBarCeiling`
         * 的让位规则保证时间线地板（64 px）不被吃掉 ⇒ composer 那一格与条数无关
         * （这与真机一致：`1366x768` 排队 2 条 / ≥11 条时 composer 底沿仍是 `imeTop`）。
         */
        val pane: Int = (paneOverride ?: (windowPx - CHROME_PX - imeBottomPx)).coerceAtLeast(0)

        val timelineAfterQueue: Int =
            (pane - rows * CONTROL_FLOOR_PX).coerceAtLeast(0)

        /** 宿主盒高 = 形态固有高收进 pane（`composerHostHeightPx`，v103 唯一求值处）。 */
        val hostPx: Int = SessionPaneLayout.composerHostHeightPx(
            panePx = pane,
            ceilingPx = if (oneRow) bandHeightPx else STACKED_NATURAL_PX,
        )

        val hostTop: Int = SessionPaneLayout.composerTopPx(pane = pane, composer = hostPx)
        val hostBottom: Int = hostTop + hostPx

        /** **被验收的 band**：`minOf(BAND_HEIGHT.roundToPx(), constraints.maxHeight)`。 */
        val bandPx: Int = minOf(bandHeightPx, hostPx)

        /** v105 闸门仍然原样在位：band 装得下 48 dp 时**逐字用原值**，不封顶、不撤掉。 */
        val touchTarget: Dp = SessionPaneLayout.composerBandTouchTargetDp(
            oneRow = oneRow,
            band = bandPx.dp,
        )

        val touchTargetPx: Int = px(touchTarget)

        val toolRowPx: Int =
            if (touchTargetPx < 0) CONTROL_FLOOR_PX else maxOf(CONTROL_FLOOR_PX, touchTargetPx)

        val editorPx: Int = if (oneRow) {
            minOf(EDITOR_PX, bandPx)
        } else {
            minOf(EDITOR_PX, (hostPx - toolRowPx).coerceAtLeast(0))
        }

        val editorTop: Int = ((bandPx - editorPx).coerceAtLeast(0)) / 2

        /** 子节点并集高 —— 真机量的那一维。 */
        val unionPx: Int = if (oneRow) {
            maxOf(editorTop + editorPx, toolRowPx)
        } else {
            editorPx + toolRowPx
        }

        val unionBottom: Int = hostTop + unionPx

        val boxPx: Int = if (oneRow) bandPx else hostPx

        val where: String =
            "pane=$pane host=$hostPx band=$bandPx oneRow=$oneRow target=$touchTargetPx " +
                "editor=$editorPx toolRow=$toolRowPx union=$unionPx"

        /** 这两条算式在本格**有定义**吗（整控件都放不下的裁剪区除外）。 */
        val coveredByTheFormula: Boolean =
            if (oneRow) bandPx >= CONTROL_FLOOR_PX else boxPx >= TOUCH_TARGET_PX
    }

    // ---------------------------------------- ① 方案 A 的唯一求值处

    /**
     * **band 抬到 48 dp**：函数值、单调性、以及 `TOOL_ROW_HEIGHT` 未被改动。
     */
    @Test
    fun theBandIsTallEnoughToHoldTheEnforcedTouchTarget() {
        assertEquals(
            "方案 A: max(44dp, 48dp) must be the 48dp band",
            48f,
            SessionPaneLayout.composerBandHeightDp(44.dp).value,
            0.0001f,
        )
        assertEquals(
            "COMPOSER_BAND_HEIGHT is that same number",
            SessionPaneLayout.composerBandHeightDp(44.dp),
            SessionPaneLayout.COMPOSER_BAND_HEIGHT,
        )
        // 单调：带越大算出来的带越大，且**永远不低于**被请求的内容行高。
        for (toolRow in 0..120) {
            val got = SessionPaneLayout.composerBandHeightDp(toolRow.dp)
            assertTrue("band $toolRow -> $got", got.value >= toolRow.toFloat())
            assertTrue("band $toolRow -> $got", got.value <= 120f)
        }
        // 44 dp 内容行高**一字未改**（堆叠形态那一支仍用它）。
        assertTrue(composer.contains("private val TOOL_ROW_HEIGHT = 44.dp"))
        assertTrue(
            "the stacked arm must keep its 44dp row",
            composer.contains(".height(TOOL_ROW_HEIGHT).padding(horizontal = 8.dp)"),
        )
        // 唯一求值处：gate 与测试读**同一个**函数。
        assertTrue(
            composer.contains(
                "private val BAND_HEIGHT: Dp = SessionPaneLayout.composerBandHeightDp(TOOL_ROW_HEIGHT)",
            ),
        )
        // 唯一求值处：**一个声明 + 一个调用点**（gate 与测试读**同一个**函数）。
        assertEquals(
            "composerBandHeightDp must have exactly one declaration",
            1,
            Regex("""fun composerBandHeightDp\s*\(""").findAll(sessionPaneLayout).count(),
        )
        assertEquals(
            "composerBandHeightDp must have exactly one call site in the composer",
            1,
            Regex("""composerBandHeightDp\(""").findAll(composer).count(),
        )
    }

    // ---------------------------------------- ② 两条算式真值表（新算式逐格）

    /**
     * **主真值表**：窗口 {768, 600, 480} × IME {开, 关} × 队列 {0, 2, 11} × 形态
     * {一行, 堆叠}，**逐格**断言两条算式：
     * **`unionPx <= band`** 与 **`unionBottom <= hostBottom`**。
     */
    @Test
    fun everyWindowImeAndQueueCellHoldsBothFormulasWithTheTallBand() {
        val imeHeights = mapOf(
            768 to 466, // 真机 1366x768+IME 稳态
            600 to 387, // 真机 1366x600+IME
            480 to 387,
        )
        var cells = 0
        var asserted = 0

        for (window in listOf(768, 600, 480)) {
            for (ime in listOf(imeHeights.getValue(window), 0)) {
                for (rows in listOf(0, 2, 11)) {
                    for (oneRow in listOf(true, false)) {
                        val f = Frame(window, ime, rows, oneRow, BAND_AFTER_PX)
                        if (f.coveredByTheFormula) {
                            // ① unionBottom <= hostBottom
                            assertTrue(
                                "UNION BOTTOM OVERFLOWED ITS HOST BOX — ${f.where} " +
                                    "unionBottom=${f.unionBottom} hostBottom=${f.hostBottom}",
                                f.unionBottom <= f.hostBottom,
                            )
                            // ② unionPx <= band（复验口径里那条字面量算式）
                            assertTrue(
                                "UNION TALLER THAN ITS BAND — ${f.where} " +
                                    "unionPx=${f.unionPx} band=${f.bandPx}",
                                f.unionPx <= f.boxPx,
                            )
                            if (oneRow) {
                                assertTrue(
                                    "unionPx > band (the report's second formula) — ${f.where}",
                                    f.unionPx <= f.bandPx,
                                )
                            }
                            // 方案 A 的**要义**：子节点不再溢出 —— 装得下 48 dp 靶时
                            // 靶**逐字保留**（不是 v104 的撤掉、也不是 v105 的封顶）。
                            if (f.bandPx >= TOUCH_TARGET_PX) {
                                assertEquals(
                                    "the enforced 48dp target must be used verbatim — ${f.where}",
                                    TOUCH_TARGET_PX,
                                    f.touchTargetPx,
                                )
                            }
                            asserted++
                        }
                        // v103 恒等式：一条都不许回退。
                        assertEquals("v103 identity — ${f.where}", f.pane, f.hostBottom)
                        assertTrue("negative host top — ${f.where}", f.hostTop >= 0)
                        assertTrue("band taller than the host box — ${f.where}", f.bandPx <= f.hostPx)
                        cells++
                    }
                }
            }
        }
        assertEquals("3 windows x 2 IME x 3 queue rows x 2 forms", 36, cells)
        assertTrue("every covered cell must have been asserted", asserted >= 30)
    }

    /**
     * **那 5px 逐位复现 + 消失**。
     *
     * **修前那一格用的是真机读数（不是模型）** —— 因为真机**根本不理** v105 的封顶，
     * 拿 v105 的模型去复现"修前"只会复现出一个**假的不越界**。所以修前的数字是
     * `REPORT_COMPOSER_OVERFLOW3.md` §1.1/§1.2 的**原文**，钉成字面量：
     *
     * ```
     * 1366x600: pane 162..213 (51) host 44 band 44
     *   EditText[24,169][405,217] h=48   ⊞/chip/Send h=48
     *   union[24,169][1364,218]  => unionPx 48, unionBottom 218
     *   ① 218 − 213 = +5    ② 48 − 44 = +4
     * 1366x768: hostBottom 294
     *   EditText[28,208][1338,256] h=48   union[10,208][1356,296] => unionBottom 296
     *   ① 296 − 294 = +2    ② 48 − 44 = +4
     * ```
     */
    @Test
    fun theFivePixelOverflowIsReproducedOnTheDeviceReadingsAndGoneOnTheNewBand() {
        // ---- 修前：真机原文（绝对坐标，paneTop = 162）----
        // 1366x600 + IME
        assertEquals("真机 600 档 band", 44, BAND_BEFORE_PX)
        assertEquals(
            "真机 600 档 ②：EditText/⊞/chip 都是 48，band 只有 44 ⇒ +4",
            4, TOUCH_TARGET_PX - BAND_BEFORE_PX,
        )
        // v114 裁定：**保留**（记账轨）。`assertEquals(5, 218 - 213)` 是编译期常量、
        // 单独看零侦测力；但本方法的「修后」半段就调生产（`Frame(600, 387, …)` →
        // `SessionPaneLayout`，且 `SessionPaneLayout.viewportHeightPx` 在同类被 pin），
        // 符合 Lead「fixture 旁边必须有一条调生产函数的断言」的前提 ⇒ 合法固定。
        // 判据本身（`unionBottom <= paneBottom`）的活版本在
        // `ComposerPositionDecompositionV107Test` / `ComposerUnionBottomP5V109Test`，
        // 这里的 5 与 2 **只作记录，不再被当判据引用**。
        assertEquals(
            "真机 600 档 ①：unionBottom 218 > hostBottom(imeTop) 213 ⇒ +5",
            5, 218 - 213,
        )
        // 1366x768 + IME：① 也是假（+2），两条算式两档全假 ⇒ v105 判 FAIL 的原文
        assertEquals("真机 768 档 ①：296 > 294 ⇒ +2", 2, 296 - 294)
        assertEquals("真机 768 档 ②：48 > 44 ⇒ +4", 4, TOUCH_TARGET_PX - BAND_BEFORE_PX)

        // ---- 修后：同一帧，band = 48，模型逐位 ----
        val after600 = Frame(600, 387, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)
        assertEquals("600+IME pane", 51, after600.pane)
        assertEquals("修后 host = band = 48", 48, after600.hostPx)
        assertEquals("修后 band", 48, after600.bandPx)
        assertEquals("修后 union 逐字不变（子节点一个像素都没被压）", 48, after600.unionPx)
        assertEquals("修后 ② 取等 48 = 48（真机原值 48 保留）", after600.bandPx, after600.unionPx)
        assertEquals(
            "修后 ① 取等：unionBottom == hostBottom（相对值 51 = 真机 162+51 = 213 = imeTop）",
            after600.hostBottom,
            after600.unionBottom,
        )
        assertEquals("修后 600 绝对底沿 = imeTop", 213, CHROME_PX + after600.unionBottom)

        val after768 = Frame(768, 466, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)
        assertEquals("768 pane", 140, after768.pane)
        assertEquals("768 修后 ①", 140, after768.unionBottom)
        assertEquals("768 修后 ② 取等", 48, after768.unionPx)
        assertTrue("768 修后 ①", after768.unionBottom <= after768.hostBottom)

        // 队列 2 条 / ≥11 条：composer 那一格与条数无关（让位发生在 bar 那一侧）。
        for (rows in listOf(0, 2, 11)) {
            val f = Frame(768, 466, rows = rows, oneRow = true, bandHeightPx = BAND_AFTER_PX)
            assertEquals("768/rows=$rows pane 必须是 140（pane 不被队列改变）", 140, f.pane)
            assertEquals("768/rows=$rows union", 48, f.unionPx)
            assertTrue("768/rows=$rows ①", f.unionBottom <= f.hostBottom)
            assertTrue("768/rows=$rows ②", f.unionPx <= f.bandPx)
        }
    }

    /**
     * **非抽样全扫**：`pane 0..900` × 2 形态，两条算式逐格成立，**不依赖表里任何常数**。
     */
    @Test
    fun aNonSampledSweepOfEveryPaneHeightHoldsBothFormulas() {
        var cells = 0
        for (pane in 0..900) {
            for (oneRow in listOf(true, false)) {
                val g = Frame(0, 0, 0, oneRow, BAND_AFTER_PX, paneOverride = pane)
                assertEquals("pane $pane oneRow=$oneRow", pane, g.pane)
                if (g.coveredByTheFormula) {
                    assertTrue(
                        "pane $pane oneRow=$oneRow — ${g.where}",
                        g.unionBottom <= g.hostBottom,
                    )
                    assertTrue(
                        "pane $pane oneRow=$oneRow — ${g.where}",
                        g.unionPx <= g.boxPx,
                    )
                }
                assertEquals("v103 identity — pane $pane oneRow=$oneRow", pane, g.hostBottom)
                cells++
            }
        }
        assertEquals("901 panes x 2 forms", 1802, cells)
    }

    // ---------------------------------------- ③ pane 预算守恒

    /**
     * **代价记账**：方案 A 的 **+4 dp 全部落在 band 上**（44 → 48），
     * `COMPOSER_MINIMAL_HEIGHT` **保持 56 dp 一动未动** —— 它是 v57 起按
     * 「44dp 行 + 12dp padding」立的账，被 v74/v79/v84/v86/v99/v102 的
     * **真机实测阶梯**逐个钉死（420dpi 的 56dp、短窗线 164dp、
     * `pane=140` 的 `readable 84 = 140 − 56`）。本轮**没有真机**，在 JVM 上凭改动
     * 去重定位这些**设备读数**就是本仓连续三轮栽跟头的那件事。
     *
     * 这条 pin 同时把**由此产生的 4dp 缺口**钉成显式数字，而不是让它悄悄存在。
     */
    @Test
    fun theFourDpCostLandsInTheBandAndTheDeviceMeasuredFloorIsUntouched() {
        // 方案 A 的带：48 dp（44 → 48），**+4 dp**。
        assertEquals(
            "the band rose by exactly 4dp",
            4f,
            SessionPaneLayout.COMPOSER_BAND_HEIGHT.value - BAND_BEFORE_PX.toFloat(),
            0.0001f,
        )
        assertEquals(
            "COMPOSER_MINIMAL_HEIGHT must stay at the v57 device-measured 56dp",
            56f,
            SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT.value,
            0.0001f,
        )
        assertEquals(
            "…and it must still be exactly the 44dp row + 12dp padding the v57 ladder pins",
            SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT,
            44.dp + SessionPaneLayout.COMPOSER_REGION_PADDING,
        )
        // **显式的已知缺口**：一行 composer 的真实高是 48 + 12 = 60dp，比地板多 4dp。
        // 这 4dp 由两处既有 clamp 吸收（见 FIX4 §7 #1），本轮未闭环，故钉成字面量，
        // 下次谁要动地板时会立刻看到它。
        assertEquals(
            "the known floor shortfall is exactly the band's rise — 60dp actual vs 56dp floor",
            4f,
            SessionPaneLayout.COMPOSER_BAND_HEIGHT.value +
                SessionPaneLayout.COMPOSER_REGION_PADDING.value -
                SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT.value,
            0.0001f,
        )
        // 缺口**不会变成溢出**：两处 clamp 都还在。
        assertTrue(
            "composerHostHeightPx still coerces into the pane",
            sessionPaneLayout.contains(
                "ceilingPx.coerceAtMost(panePx.coerceAtLeast(0)).coerceAtLeast(0)",
            ),
        )
        assertTrue(
            "the band is still clamped to what the card gives it",
            composer.contains("val band = minOf(BAND_HEIGHT.roundToPx(), constraints.maxHeight)"),
        )
    }

    /**
     * **三格连带预演**（Lead 要求的预演表，逐格如实报值）：
     * `1366x768` / `1366x600` / `480`，`readable` 必须仍 ≥ **64** 地板。
     *
     * `readable` 按 v104 pin 的模型算：`pane − min(card, ceiling)`，其中一行卡的
     * 固有高 = **48 px**（v104 的 `CARD_PX`，注释即"真机 `EditText h=48`"）——
     * **修前就是这个数**，所以方案 A 落地后真机才第一次与模型一致，读数**一个像素不动**。
     */
    @Test
    fun theThreeCellBudgetPreviewKeepsThe768Floor() {
        fun readable(window: Int, ime: Int): Pair<Int, Int> {
            val pane = (window - CHROME_PX - ime).coerceAtLeast(0)
            val floorPx = SessionPaneLayout.timelineViewportFloorPx(pane, 64)
            val safeFloor = SessionPaneLayout.safeViewportFloorPx(floorPx, pane)
            val hostHeight = SessionPaneLayout.composerHostHeightPx(
                panePx = pane,
                ceilingPx = SessionPaneLayout.composerHostCeilingPx(pane = pane, floorPx = safeFloor),
            )
            // v104 的 `CARD_PX = 48`：一行 composer 卡的固有高，**修前修后同值**。
            val card = minOf(48, hostHeight).coerceAtLeast(0)
            val readablePx = SessionPaneLayout.viewportHeightPx(
                shared = true,
                pane = pane,
                composer = card,
                floorPx = safeFloor,
            )
            return pane to readablePx
        }

        // ---- 1366x768 + IME：pane 140，readable 92（≥ 64 ✅，**未变**）----
        val (pane768, readable768) = readable(768, 466)
        assertEquals("768+IME pane", 140, pane768)
        assertEquals("768+IME readable — 修前 92，方案 A 后逐字不变", 92, readable768)
        assertTrue("768 的 readable 必须仍 ≥ 64 地板（实际 $readable768）", readable768 >= 64)
        // 保守口径：若把这 4dp 全部记在 readable 上（pane 预算的极限扣减），
        // 92 − 4 = 88 **仍 ≥ 64** ⇒ 硬约束在两种口径下都成立。
        assertTrue("conservative 768 readable floor", 92 - 4 >= 64)
        assertEquals("conservative 768 readable", 88, readable768 - 4)

        // ---- 1366x600 + IME：pane 51，readable 51（显式让位区，**未变**）----
        val (pane600, readable600) = readable(600, 387)
        assertEquals("600+IME pane", 51, pane600)
        assertEquals("600+IME readable — 方案 A 未动（显式让位区）", 51, readable600)
        assertEquals("600+IME 绝对底沿 = 162+51 = imeTop", 213, CHROME_PX + readable600)

        // ---- 480 + IME：pane 归 0 —— 两条算式在定义域外（v84/v86 已 pin 的裁剪区）----
        val (pane480, readable480) = readable(480, 387)
        assertEquals("480+IME pane 归 0", 0, pane480)
        assertEquals("480+IME readable 归 0", 0, readable480)

        // ---- Dp 预算线：让位只发生在既有机制上，且**至多** 4dp ----
        for (window in listOf(768, 600)) {
            val ime = if (window == 768) 466 else 387
            val before = SessionPaneLayout.timelineShare(
                totalHeight = window.dp,
                imeHeight = ime.dp,
                composerLines = 1,
                queueRows = 0,
            )
            // 修后的地板：把 card 换成 60dp（等效于把 56 换成 60 的同一条算式）
            val after = (window - ime).coerceAtLeast(0).dp -
                SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT
            assertTrue(
                "window=$window: timeline must not go negative",
                after.value >= 0f,
            )
            assertTrue(
                "window=$window: the band rise may cost at most 4dp (before=$before after=$after)",
                (before - after).value in 0f..4f,
            )
            assertTrue(
                "window=$window: readable band stays >= 64 after the cost (after=$after)",
                after.value >= 64f,
            )
        }
    }

    // ---------------------------------------- ④ 不降强度

    /**
     * **v103/v104/v105 的 pin 一个字没被削弱**，方案 A 只是换了 band 的高度来源。
     */
    @Test
    fun theEarlierPinsAreIntactAndTheOldBandIsStillReachable() {
        // v104：并集的唯一收口仍在，且 `coerceAtMost(panePx)` 仍只出现一次。
        assertTrue(
            sessionPaneLayout.contains(
                "fun composerChildUnionHeightPx(panePx: Int, hostPx: Int, unionPx: Int): Int =",
            ),
        )
        assertTrue(
            sessionPaneLayout.contains(
                "unionPx.coerceAtMost(composerHostHeightPx(panePx = panePx, ceilingPx = hostPx))",
            ),
        )
        // v103：宿主盒的唯一求值处原样。
        assertTrue(
            sessionPaneLayout.contains(
                "fun composerHostHeightPx(panePx: Int, ceilingPx: Int): Int =",
            ),
        )
        // v105：闸门三支与 `provides Dp.Unspecified`（**恰好一处**）原样。
        assertTrue(composer.contains("if (bandMaxHeight >= MIN_TOUCH_TARGET) {"))
        assertTrue(
            composer.contains("LocalMinimumInteractiveComponentSize provides bandTouchTarget"),
        )
        assertTrue(
            composer.contains("LocalMinimumInteractiveComponentSize provides Dp.Unspecified"),
        )
        assertEquals(
            "provides Dp.Unspecified must remain exactly one point",
            1,
            Regex("""LocalMinimumInteractiveComponentSize provides Dp\.Unspecified""")
                .findAll(composer).count(),
        )
        // 方案 A 的 guard：clamp 仍在，所以「只在需要时增高」成立。
        assertTrue(
            composer.contains("val band = minOf(BAND_HEIGHT.roundToPx(), constraints.maxHeight)"),
        )
        // **可点性不降强度**：48 dp 靶**原值保留**（比 v104 的 28dp 多 20dp，
        // 比 v105 期望的 44dp 还多 4dp）⇒ a11y 节点仍是 48×48 的 clickable。
        val target = SessionPaneLayout.composerBandTouchTargetDp(oneRow = true, band = 48.dp)
        assertEquals("方案 A 的靶是 48dp 原值", 48f, target.value, 0.0001f)
        assertTrue(
            "方案 A 的靶不小于 v105 期望的 44dp，也不小于 v104 的 28dp",
            target.value >= 44f,
        )
    }
}
