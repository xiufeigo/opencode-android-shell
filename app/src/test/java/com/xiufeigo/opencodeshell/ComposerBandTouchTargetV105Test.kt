package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v105 P0 —— **44dp 的带装不下子节点被强制的 48dp 最小触摸靶 ⇒ 子节点溢出自己的行**。
 *
 * 权威口径：`research/v97_verify/5556_v98/REPORT_OVERFLOW_DIAG.md` §5.2。两条算式**都要过**：
 *
 * ```
 * ✅ unionBottom <= hostBottom     真机 600 档：218 > 213 ❌（子节点并集底沿超出 composer 宿主盒）
 * ✅ unionPx     <= band           真机：      48  > 44 ❌（并集高超过 44dp 的带高）
 * ```
 *
 * ## 48dp 是**谁**强制的（不是本仓）
 *
 * `Add` / `Stop`(Send) / `Model` chip 每一个都挂 `Modifier.clickable`；Compose 的
 * `minimumInteractiveComponentSize()` 读的 `LocalMinimumInteractiveComponentSize`
 * 的**非 `Unspecified`** 值就是它拉起的地板，而 material3 把那个 local 置为 `48.dp`
 * （`Composer.kt:32` import 的正是 `androidx.compose.material3` 那一个）⇒ **material3 强制 48dp**。
 * 本仓**没有任何** `provides 48.dp`（本类 pin 断言：全树唯一命中是 v104 的 `provides Dp.Unspecified`）。
 *
 * ## v103/v104 为什么打不中
 *
 *  * v103 的 `top + host ≡ pane` 是**宿主盒**的恒等式，而真机量的是**子节点并集**；
 *  * v104 的闸门把 48dp 整条**撤掉**（`Dp.Unspecified`）⇒ 控件回落 28dp，可点面积
 *    少了 16dp，而 `1366x768` 那一档的 `h=48` 也被一并抹平。
 *
 * ## v105 取法：**受控覆盖 = 把靶封顶到带本身**（方案 B）
 *
 * `SessionPaneLayout.composerBandTouchTargetDp` 是**唯一求值处**，`Composer.kt` 的闸门与
 * 本类的真值表读**同一个数**：
 *
 * | 带 | 一行形态（一行带的 composer box **就是**这条带） | 堆叠形态（box = `editor + toolRow`） |
 * |---|---|---|
 * | `>= 48` | **48（原值，一像素不动）** | **48（原值，`1366x768` 的 `h=48` 逐字不变）** |
 * | `[28,48)` | **封顶到带本身**（44dp 带 ⇒ 44dp 靶，`unionPx = band`） | 48（堆叠 box 由 `editor + 40` 收着） |
 * | `< 28` | `Dp.Unspecified`（v86 地板之下，整控件都放不下） | 同左 |
 *
 * ## 可点性/a11y 未退化（本类 pin）
 *
 * 封顶值 44dp **大于**控件固有的 28dp（`SUBMIT_SIZE`）⇒ a11y 节点仍是 44×44 的 `clickable`，
 * **比 v104 的 28×28 多 16dp 可点面积**；而修前那 4px 底沿落在 **IME 顶沿之下**
 * （`unionBottom 218 > imeTop 213`）本就被键盘压住、点不到 ⇒ 收回带内**净增**真正可点的面积。
 */
class ComposerBandTouchTargetV105Test {

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

    private val sessionScreen = source(
        "src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt",
    )

    /**
     * v114: `composerRegion` 的垂直拆分，**从生产那一行解析**（并断言该行唯一），
     * 而不是在本文件里重写一遍。它是 criterion 1 里唯一「不是生产函数」的项，
     * 读它而不是复述它，这条判据才可能双向变红。
     */
    private fun regionPaddingDp(): Pair<Double, Double> {
        val all = Regex(
            """\.padding\(\s*start = 12\.dp,\s*end = 12\.dp,\s*top = ([0-9.]+)dp,\s*bottom = ([0-9.]+)dp\s*\)""",
        ).findAll(sessionScreen).toList()
        assertEquals("the composer region's padding line must stay unique and parseable", 1, all.size)
        val m = all[0]
        return (m.groupValues[1].toDouble()) to (m.groupValues[2].toDouble())
    }

    // ------------------------------------------------------------------ 模型

    private companion object {
        /** 真机 chrome（header + 二级工具条）底沿：两档时间线盒顶沿都是 162。 */
        const val CHROME_PX = 162

        /** `TOOL_ROW_HEIGHT` = 44 dp（`Composer.kt:2583`）—— **方案 B 不动它**。 */
        const val TOOL_ROW_PX = 44

        /** material3 强制的最小触摸靶。 */
        const val TOUCH_TARGET_PX = 48

        /** `SUBMIT_SIZE` = 28 dp —— 控件画出来的高，也是 v86 的控件地板。 */
        const val CONTROL_FLOOR_PX = 28

        /** 编辑器固有的高（真机 `EditText h=48`）。 */
        const val EDITOR_PX = 48

        /** 堆叠形态的 composer 固有高（真机 `1366x768`：宿主 94，`union 88` 装得下）。 */
        const val STACKED_NATURAL_PX = 94

        /** 模型输入：队列一行占掉的 pane 高（只用来平移 pane，不参与证明）。 */
        const val QUEUE_ROW_PX = 28

        /** `Dp.Unspecified` 的 NaN 不能用 `==` 比；用成员 `isUnspecified`。 */
        fun px(dp: Dp): Int = if (dp.value.isNaN()) -1 else dp.value.toInt()
    }

    /**
     * 一帧的完整几何，**逐字复刻** `SessionPaneArrangement` + `ComposerArrangement`：
     * 算式即 `REPORT_OVERFLOW_DIAG.md` §5.2 ①② 的逐行。
     */
    private class Frame(
        windowPx: Int,
        imeBottomPx: Int,
        rows: Int,
        oneRow: Boolean,
        queueRowPx: Int,
    ) {
        /** 队列条数先把 pane 吃掉（`rows * 行高`）—— 这是"队列条数"那一维。 */
        val pane: Int =
            (windowPx - CHROME_PX - imeBottomPx - rows * queueRowPx).coerceAtLeast(0)

        /** 宿主盒高 = 形态固有高收进 pane（`composerHostHeightPx`，v103 唯一求值处）。 */
        val hostPx: Int = SessionPaneLayout.composerHostHeightPx(
            panePx = pane,
            ceilingPx = if (oneRow) TOOL_ROW_PX else STACKED_NATURAL_PX,
        )

        /** `composerTopPx`（v76/v103）⇒ `hostTop + hostPx ≡ pane` 恒等。 */
        val hostTop: Int = SessionPaneLayout.composerTopPx(pane = pane, composer = hostPx)

        /** 宿主盒底沿 —— v103 钉的那一维。 */
        val hostBottom: Int = hostTop + hostPx

        /** 真正摆东西的带（`Composer.kt:2221` `min(TOOL_ROW_HEIGHT, constraints.maxHeight)`）。 */
        val bandPx: Int = minOf(TOOL_ROW_PX, hostPx)

        /** v105 唯一求值处：被强制的靶，封顶到带。 */
        val touchTarget: Dp = SessionPaneLayout.composerBandTouchTargetDp(
            oneRow = oneRow,
            band = bandPx.dp,
        )


        val touchTargetPx: Int = px(touchTarget)

        /** 工具行高 = `max(控件固有 28dp, 靶)`（`Dp.Unspecified` ⇒ 地板被撤掉 ⇒ 28dp）。 */
        val toolRowPx: Int =
            if (touchTargetPx < 0) CONTROL_FLOOR_PX else maxOf(CONTROL_FLOOR_PX, touchTargetPx)

        /** 编辑器被 measure policy 的 `maxHeight` 收着（`Composer.kt:2229-2235`）。 */
        val editorPx: Int = if (oneRow) {
            minOf(EDITOR_PX, bandPx)
        } else {
            minOf(EDITOR_PX, (hostPx - toolRowPx).coerceAtLeast(0))
        }

        /** 居中偏置（`Composer.kt:2253` `editorTop = (band - editor.height) / 2`）。 */
        val editorTop: Int = ((bandPx - editorPx).coerceAtLeast(0)) / 2

        /** 子节点并集高 —— 真机量的那一维。 */
        val unionPx: Int = if (oneRow) {
            maxOf(editorTop + editorPx, toolRowPx)
        } else {
            editorPx + toolRowPx
        }

        /** 子节点并集底沿。 */
        val unionBottom: Int = hostTop + unionPx

        /**
         * 物理上装得下并集的那个盒：一行形态**就是**那条带（`layout(..., constrainHeight(band))`），
         * 堆叠形态是 `editor + toolRow` 那个 composer 盒（宿主高）。
         */
        val boxPx: Int = if (oneRow) bandPx else hostPx

        val where: String = "pane=$pane host=$hostPx band=$bandPx oneRow=$oneRow " +
            "target=$touchTarget editor=$editorPx toolRow=$toolRowPx union=$unionPx"

        /**
         * 这两条算式在这一格里**有定义**吗？
         *
         *  * 一行形态：带里放不下一个 28dp 控件时（`band < 28`，v86 地板之下），
         *    控件是**被裁**的 —— 那是 v84/v86 已 pin 的行为，不是这两条算式的领域；
         *  * 堆叠形态：它自己的地板是 `EDITOR_MIN_HEIGHT(60) + TOOL_ROW(44) = 104`
         *    （`COMPOSER_MIN_HEIGHT` 的算法），且它的**盒**是 `editor + toolRow`，
         *    所以这一维的前置条件是「盒装得下 48dp 靶」。
         */
        val coveredByTheFormula: Boolean =
            if (oneRow) bandPx >= CONTROL_FLOOR_PX else boxPx >= TOUCH_TARGET_PX
    }

    // --------------------------------- ① band × 触摸靶 × 窗口 × IME × 队列条数

    /**
     * **主真值表**：4 窗口 × 3 IME × 4 队列条数 × 2 形态 = **96 格**，逐格断言
     * `unionBottom <= hostBottom` **且** `unionPx <= boxPx`。
     */
    @Test
    fun everyBandTouchTargetWindowImeAndQueueCellKeepsTheUnionInsideTheBox() {
        // 队列行高取真机 `ComposerQueuePanel` 的行高（`SessionPaneLayout` 里的常量）。
        // A queue row's share of the pane. Its only role is to MOVE the pane; the
        // proof itself is the non-sampled sweep in the next test, which does not
        // depend on this number at all.
        val queueRowPx = QUEUE_ROW_PX
        val imeHeights = mapOf(
            600 to 387, // 真机 1366x600+IME：mFrame = [0,213][1366,600]
            768 to 466, // 真机 1366x768+IME 稳态
        )
        var cells = 0

        for (window in listOf(600, 480, 768, 1280)) {
            for (ime in listOf(imeHeights[window] ?: imeHeights.getValue(600), 466, 0)) {
                for (rows in listOf(0, 2, 5, 11)) {
                    for (oneRow in listOf(true, false)) {
                        val f = Frame(window, ime, rows, oneRow, queueRowPx)
                        // 判据只有 pane >= 28（v86 的控件地板）：之下整控件都放不下，
                        // 那是**已 pin 的裁剪**行为，不是这两条算式的领域（见 ②）。
                        if (f.coveredByTheFormula) {
                            assertTrue(
                                "UNION BOTTOM OVERFLOWED ITS HOST BOX — ${f.where} " +
                                    "unionBottom=${f.unionBottom} hostBottom=${f.hostBottom}",
                                f.unionBottom <= f.hostBottom,
                            )
                            assertTrue(
                                "UNION TALLER THAN ITS BAND — ${f.where}",
                                f.unionPx <= f.boxPx,
                            )
                            // 一行形态的 `band` 就是 box，再钉一遍字面量那条算式。
                            if (oneRow) {
                                assertTrue(
                                    "unionPx > band (the report's second formula) — ${f.where}",
                                    f.unionPx <= f.bandPx,
                                )
                            }
                        }
                        // v103 恒等式：宿主底沿恒等于 pane 底沿（一条都不许回退）。
                        assertEquals("v103 identity — ${f.where}", f.pane, f.hostBottom)
                        assertTrue("negative host top — ${f.where}", f.hostTop >= 0)
                        assertTrue("band taller than the host box — ${f.where}", f.bandPx <= f.hostPx)
                        cells++
                    }
                }
            }
        }
        assertEquals("4 windows x 3 IME x 4 queue rows x 2 forms", 96, cells)
    }

    /** 非抽样全扫：`pane 0..900` × 2 形态，两条算式逐格成立。 */
    @Test
    fun aNonSampledSweepOfEveryPaneHeightAndFormHoldsBothFormulas() {
        var cells = 0
        for (pane in 0..900) {
            for (oneRow in listOf(true, false)) {
                val hostPx = SessionPaneLayout.composerHostHeightPx(
                    panePx = pane,
                    ceilingPx = if (oneRow) TOOL_ROW_PX else STACKED_NATURAL_PX,
                )
                val bandPx = minOf(TOOL_ROW_PX, hostPx)
                val targetPx = px(
                    SessionPaneLayout.composerBandTouchTargetDp(oneRow = oneRow, band = bandPx.dp),
                )
                if (if (oneRow) bandPx >= CONTROL_FLOOR_PX else hostPx >= TOUCH_TARGET_PX) {
                    val toolRow = if (targetPx < 0) CONTROL_FLOOR_PX else maxOf(CONTROL_FLOOR_PX, targetPx)
                    val editor = if (oneRow) {
                        minOf(EDITOR_PX, bandPx)
                    } else {
                        minOf(EDITOR_PX, (hostPx - toolRow).coerceAtLeast(0))
                    }
                    val union = if (oneRow) {
                        maxOf(((bandPx - editor).coerceAtLeast(0)) / 2 + editor, toolRow)
                    } else {
                        editor + toolRow
                    }
                    if (oneRow) {
                        assertTrue(
                            "one-row: the target must never exceed its band — pane=$pane " +
                                "band=$bandPx target=$targetPx",
                            targetPx <= bandPx,
                        )
                    } else {
                        // 堆叠形态的盒是 `editor + toolRow`（宿主高），**不是**那条 44dp 的行：
                        // 它保留 48dp 地板是 `1366x768` 那一档已 PASS 的读数，不许动。
                        assertTrue(
                            "stacked: 48dp is allowed only inside the composer's own box — " +
                                "pane=$pane host=$hostPx target=$targetPx",
                            targetPx <= hostPx,
                        )
                    }
                    assertTrue(
                        "union must never exceed the host box — pane=$pane oneRow=$oneRow " +
                            "union=$union host=$hostPx band=$bandPx target=$targetPx",
                        union <= hostPx,
                    )
                }
                cells++
            }
        }
        assertEquals("901 panes x 2 forms", 1802, cells)
    }

    // ------------------------------------------- ② 真机那 5px：修前越界 / 修后归位

    /**
     * `REPORT_OVERFLOW_DIAG.md` §5.1 的两档原文，逐位复现**修前**的越界，
     * 并钉死**修后**归位；`1366x768` 的 48dp 靶与 `readable` 读数**一像素不退**。
     */
    @Test
    fun theFivePixelOverflowIsGoneAndTheSurvivingReadingsStayByteIdentical() {
        // ---- 修前（真机原文）：48dp 靶装在 44dp 带里，并集底沿穿出 5px ----
        assertEquals(
            "1366x600+IME: union(48) > band(44) is the report's second FAIL",
            4,
            TOUCH_TARGET_PX - TOOL_ROW_PX,
        )
        assertEquals("the device's +5 (49 − 44)", 5, 49 - TOOL_ROW_PX)
        // v114: the device's own record, pinned as a record. This used to read
        // `assertTrue("the device's union bottom 218 > host bottom 213", 218 > 213)`
        // — a literal compared with itself, the same shape as the v109 class's
        // `assertTrue(unionBottomAbs > paneBottomAbs)`: true by construction, and no
        // production change could ever have turned it red. The reading is worth
        // keeping; the *claim* about production now lives in
        // [theDeviceOverrunIsGoneInProductionAndItsTripwireCanStillGoRed], which is
        // built out of `believedImeBottomPx` / `composerChildUnionBottomPx` /
        // `MIN_INTERACTIVE_COMPONENT_PX`.
        assertEquals("the device's union bottom 218 overran the host bottom 213 by 5", 5, 218 - 213)

        // ---- 修后：600 档 ----
        val f600 = Frame(600, 387, rows = 0, oneRow = true, queueRowPx = 64)
        assertEquals("pane at 1366x600+IME", 51, f600.pane)
        assertEquals("host box at 1366x600+IME", TOOL_ROW_PX, f600.hostPx)
        assertEquals("band at 1366x600+IME", TOOL_ROW_PX, f600.bandPx)
        assertEquals("the target is capped AT the band, not dropped to 28", 44, f600.touchTargetPx)
        assertEquals("unionPx == band", 44, f600.unionPx)
        assertEquals("unionBottom == hostBottom", f600.hostBottom, f600.unionBottom)
        assertTrue("600+IME still overflows its host box", f600.unionBottom <= f600.hostBottom)
        assertTrue("600+IME: union taller than the band", f600.unionPx <= f600.bandPx)

        // ---- 修后：768 档逐字不变（**不降强度**）----
        val f768 = Frame(768, 466, rows = 11, oneRow = false, queueRowPx = 0)
        assertEquals("pane at 1366x768+IME", 140, f768.pane)
        assertEquals("the 48px touch target is byte-identical", 48, f768.touchTargetPx)
        assertEquals("the 48px tool row is byte-identical", 48, f768.toolRowPx)
        assertTrue("768+IME overflowed its host box", f768.unionBottom <= f768.hostBottom)
        assertTrue("768+IME: union taller than its box", f768.unionPx <= f768.boxPx)

        // 已 PASS 的读数一个像素不退：600 的 readable=51、768 的 readable=92。
        assertEquals(51, SessionPaneLayout.viewportHeightPx(
            shared = true, pane = 51, composer = TOOL_ROW_PX, floorPx = 51,
        ))
        assertEquals(92, SessionPaneLayout.viewportHeightPx(
            shared = true, pane = 140, composer = 48, floorPx = 64,
        ))
    }

    // ------------------------------------------- ②b v114: 那条字面量 `assertTrue` 的替身

    /**
     * v114 —— 上一节里 `assertTrue(…, 218 > 213)` 的**活**替身。
     *
     * 尺子走 `believedImeBottomPx`（`SessionPaneLayout.kt:1531`）算 pane 盒，被测的
     * 并集底沿走 `composerChildUnionBottomPx`（`:1340`，内含 `composerTopPx` `:1426`
     * 与 `composerChildUnionHeightPx` `:1330`），宿主高走 `MIN_INTERACTIVE_COMPONENT_PX`
     * （`:108`）—— 口径与 `ComposerPaneBottomCriterionV108Test`（v112）、
     * `ComposerUnionBottomP5V109Test`（v113）**逐字相同**。
     *
     * 反向腿把 v110 之前那条 `top = 4.dp` 从**同一个缝**塞回去，要求判据翻红且越界量
     * 恰好是那条带自己的 4 px（补上真机的 +1 亚像素就落回 `218`）—— 逐位吻合 v98 读数，
     * 而不是「随便越界一点」。
     */
    @Test
    fun theDeviceOverrunIsGoneInProductionAndItsTripwireCanStillGoRed() {
        val hostPx = SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX // 48, :108
        val panePx = 438 - SessionPaneLayout.believedImeBottomPx( // 438 - 387 = 51, :1531
            visibleBoxPx = 438,
            // v126: was 0; the measured 600-tier navigation bar is
            // `600 − nonDecorBottom(552) = 48` (insets 原文 ROTATION_0 行的
            // nonDecorFrame). 48 也是生产在 `SessionScreen.kt:2363-2369` 递给
            // `believedImeBottomPx` 的 `navBottomPx`。本档结果不变，原因是**构造性**
            // 的：`438 < 284 * 2` 让 `SessionPaneLayout.kt:1539` 在读到 floorPx 之前
            // 就返回，不是"碰巧没影响"。
            floorPx = 48,
            imeBottomPx = 387, // 600 - imeTop(213)
            essentialPx = SessionPaneLayout.PANE_ESSENTIAL_HEIGHT.value.toInt(),
        )
        val paneBottom = CHROME_PX + panePx // 162 + 51 = 213
        val regionTopPx = regionPaddingDp().first.toInt() // 0 today (SessionScreen.kt:1827)
        val unionBottom = CHROME_PX + regionTopPx + SessionPaneLayout.composerChildUnionBottomPx(
            panePx = panePx,
            hostPx = hostPx,
            unionPx = hostPx,
        ) // 162 + 0 + (3 + 48) = 213

        assertEquals("the 600 tier's production pane is 51 px", 51, panePx)
        assertTrue(
            "unionBottom($unionBottom) <= paneBottom($paneBottom) — the device's 218 > 213 is gone",
            unionBottom <= paneBottom,
        )
        assertEquals("and it is flush, 0 px of slack", 0, unionBottom - paneBottom)
        // v114: kept **after** the criterion on purpose, so that putting the old
        // `top = 4.dp` back makes the test go red **on the criterion**, not on this
        // padding pin (that is what makes the reverse run's output readable).
        assertEquals("today's band top padding is the 0 dp v110 moved it to", 0, regionTopPx)
        assertEquals(
            "and the 12 dp region total is untouched by the move",
            12.0,
            regionPaddingDp().first + regionPaddingDp().second,
            0.0,
        )

        // ---- 反向：同一个缝塞回 v110 之前的 `top = 4.dp`，判据必须变红 ----
        val oldUnionBottom = unionBottom + 4
        assertTrue(
            "a restored `top = 4.dp` must break criterion 1 (was $oldUnionBottom vs $paneBottom)",
            oldUnionBottom > paneBottom,
        )
        assertEquals("…by exactly the band's own 4 px, not some other amount", 4, oldUnionBottom - paneBottom)
        assertEquals("…plus the device's +1 sub-pixel that lands on the v98 reading 218", 218, oldUnionBottom + 1)
    }

    // ------------------------------------------------ ③ 48dp 是谁强制的 / 结构 pin

    /**
     * 「48dp 是**谁**强制的」写成源码 pin：**material3**，不是本仓。
     * 顺带钉住 v105 的接线只有一处求值处、且**没有**放宽任何既有断言。
     */
    @Test
    fun theFortyEightDpFloorComesFromMaterialThreeAndTheCapHasOneEvaluationPoint() {
        // 1) 本仓 import 的是 material3 的那个 local ⇒ 地板来自 material3。
        assertTrue(
            "the app must read material3's LocalMinimumInteractiveComponentSize",
            composer.contains("import androidx.compose.material3.LocalMinimumInteractiveComponentSize"),
        )
        // 2) **全仓没有任何 `provides 48.dp`**：48dp 不是本仓设的。
        val tree = listOf(
            "src/main/java/com/xiufeigo/opencodeshell/ui/session",
        )
        val provides48 = tree.flatMap { dir ->
            File(dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }.count { file ->
            file.readText()
                .replace(Regex("(?s)/\\*.*?\\*/"), " ")
                .replace(Regex("(?m)^\\s*//.*$"), " ")
                .contains("provides 48.dp")
        }
        assertEquals("nobody in the app provides a 48dp touch floor", 0, provides48)

        // 3) 唯一求值处：闸门读它，测试读它。
        assertTrue(
            "the gate must read the one evaluation point",
            composer.contains("val bandTouchTarget = SessionPaneLayout.composerBandTouchTargetDp("),
        )
        assertEquals(
            "one call site only",
            1,
            Regex("composerBandTouchTargetDp\\(").findAll(composer).count(),
        )
        assertTrue(
            "the pure function must live in SessionPaneLayout",
            sessionPaneLayout.contains("fun composerBandTouchTargetDp("),
        )

        // 4) v104 的 pin 一个字没动（**保留**），且控件仍是**单一调用点**。
        assertTrue(composer.contains("private val MIN_TOUCH_TARGET = 48.dp"))
        assertTrue(composer.contains("if (bandMaxHeight >= MIN_TOUCH_TARGET) {"))
        assertTrue(composer.contains("LocalMinimumInteractiveComponentSize provides Dp.Unspecified,"))
        assertEquals(
            "the touch floor is switched off in exactly one place",
            1,
            Regex("LocalMinimumInteractiveComponentSize provides Dp\\.Unspecified")
                .findAll(composer).count(),
        )
        assertEquals(
            "the controls stay a single call site",
            1,
            Regex("""controls\(\)""")
                .findAll(composer.substringAfter("private fun ComposerArrangement(")).count(),
        )
        // 5) **方案 B 不动几何**：44dp 的带与 48dp 的常量都原样在。
        assertTrue(composer.contains("private val TOOL_ROW_HEIGHT = 44.dp"))
        assertTrue(composer.contains(".height(TOOL_ROW_HEIGHT)"))
        // v106（**方案 A**：band 抬到 48dp 容纳被强制的 48dp 触摸靶）：measure policy
        // 读的是 [BAND_HEIGHT]，而 `TOOL_ROW_HEIGHT = 44.dp` 本身**一字未改**（上一行
        // 的字面量 pin 仍逐字通过）—— 44dp 是堆叠形态那一支的**内容**行高，方案 A 改的
        // 是一行形态**装东西的那条带**，两者是不同量。clamp 仍在，所以「只在需要时增高」
        // 与 v86 的「band 永不超过卡片」语义**逐字不变**。
        assertTrue(composer.contains("val band = minOf(BAND_HEIGHT.roundToPx(), constraints.maxHeight)"))
        assertTrue(composer.contains("private val BAND_HEIGHT: Dp = SessionPaneLayout.composerBandHeightDp(TOOL_ROW_HEIGHT)"))
    }

    /** 封顶值的可点性/a11y 语义：**不低于** v104 那条被撤掉的地板。 */
    @Test
    fun theCappedTargetIsNeverSmallerThanTheControlItWraps() {
        for (band in 28..96) {
            val target = px(
                SessionPaneLayout.composerBandTouchTargetDp(oneRow = true, band = band.dp),
            )
            assertTrue("band=$band must never ask for more than the 48dp floor", target <= TOUCH_TARGET_PX)
            assertTrue("band=$band must never ask for more than the band", target <= band)
            assertTrue("band=$band target=$target shrank below the 28dp control", target >= CONTROL_FLOOR_PX)
        }
        // 堆叠形态：地板原样返回（`1366x768` 的 `h=48` 逐字不变）。
        for (band in listOf(28, 44, 48, 94, 140)) {
            assertEquals(
                "stacked form must keep the material floor",
                TOUCH_TARGET_PX,
                px(SessionPaneLayout.composerBandTouchTargetDp(oneRow = false, band = band.dp)),
            )
        }
        // v86 地板之下才交还 `Dp.Unspecified`（整控件都放不下，无靶可谈）。
        for (band in listOf(0, 8, 20, 27)) {
            assertTrue(
                "band=$band is below the control floor — the floor must be handed back",
                SessionPaneLayout
                    .composerBandTouchTargetDp(oneRow = true, band = band.dp)
                    .value.isNaN(),
            )
        }
    }
}
