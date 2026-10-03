package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v118：让 JVM 模型能**复算「设备口径」的量**，把 `0 vs −12` 从「不可比」变成「可换算」。
 *
 * ## 为什么要这个类
 *
 * v113 §5.4 与 v114 §7.3 两次记同一笔账：
 *
 * > **正向余量是 `0` px（`213 == 213`）**，这是零容差特性，但**不等于** v110 真机读的
 * > **`−12`** —— 两者**口径不同**（v112 已证 dump 容器 ≠ 生产 pane 盒，差 48px 带高），
 * > **不可直接相减**。
 *
 * 根因不是"数字对不上"，而是 **JVM pin 算的是「生产口径」，真机量的是「dump 口径」**，
 * 两边都绿，却**互相印证不了** —— 本仓反复吃亏的「**pin 的量必须与验收的量同源**」。
 *
 * 本类把那句话变成**可执行的算式**：输入＝生产口径的模型值，输出＝**预测的 dump 口径读数**，
 * 再拿历史真机读数当 fixture 逐位 / ±1 对齐。
 *
 * ## 两个口径的算式（逐字可复核）
 *
 * **A. 生产口径**（`ComposerPaneBottomCriterionV108Test` 量的那一支）——pane 盒**不含** composer 宿主：
 * ```
 * paneBottom  = paneTop + panePx                                     [production]
 *             = paneTop + (visibleBoxPx − believedImeBottomPx(…))     SessionPaneLayout.kt:1531
 * unionBottom = paneTop + composerTopPx(pane, host) + regionTopPx
 *                     + composerChildUnionHeightPx(pane, host, u)     :1426 / :1330
 * margin      = unionBottom − paneBottom                              → 600 档 = 0
 * ```
 *
 * **B. dump 口径**（真机 a11y dump 量的那一支）——**容器节点含宿主盒**：
 * ```
 * dumpContainerBottom = 生产 paneBottom + bandPx                      ← 48px 带高挂在这
 * dumpUnionBottom    = dumpContainerBottom − regionBottomPaddingPx
 * dumpMargin         = 生产 margin − regionBottomPaddingPx
 * ```
 *
 * ## 换算关系（v118 的核心结论）
 *
 * **48px 挂在「容器的下沿」，不在并集上，而且它在余量里约掉。**
 *
 * ```
 * dumpContainerBottom = 生产 paneBottom + 48          213+48=261 ✅  302+48=350 ✅  两档逐位
 * dumpUnionBottom    = 生产 unionBottom + 48 − 12    213+48−12=249 ✅                  逐位
 * dumpMargin         = (0) + 48 − 12 − 48 = −12                                逐位
 * ```
 *
 * ⇒ **`0` 与 `−12` 本来就可比**，中间只隔**一个** `regionBottomPaddingPx = 12`
 * （`SessionScreen.kt:1827` 的 `bottom = 12.dp`）。48 是**容器**的差，12 是**余量**的差。
 *
 * 逐字来源：容器/并集读数 `research/v97_fix/COMPOSER_OVERFLOW_FIX8.md` §4.2①/②
 * （`wm size 1366x600` / `1366x768`，`wm density 160` ⇒ **1dp == 1px**）。
 * 这些是**故意保留的字面量**：模型不许自己编"before"数
 * （那正是本仓栽过三次的假绿陷阱，见 `COMPOSER_OVERFLOW_FIX4.md` §1.3）。
 */
class ComposerCriterionParityV118Test {

    // ---- 设备几何（density 160 ⇒ 1dp == 1px）--------------------------------
    private val paneTop = 162
    private val paneBox600 = 438
    private val imeBottom600 = 387
    private val paneBox768 = 606
    private val imeBottom768 = 474

    // ---- 生产常量（全部取自生产，不在本文件重述）---------------------------
    private fun essentialPx(): Int = SessionPaneLayout.PANE_ESSENTIAL_HEIGHT.value.toInt()
    private val bandPx: Int get() = SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX // 48, :108

    /** `SessionScreen.kt:1827` 的 region 竖向切分，从**生产源码**解析（v112 起的手法）。 */
    private fun regionPaddingDp(): Pair<Double, Double> {
        val all = Regex(
            """\.padding\(\s*start = 12\.dp,\s*end = 12\.dp,\s*top = ([0-9.]+)dp,\s*bottom = ([0-9.]+)dp\s*\)""",
        ).findAll(
            listOf(
                File("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt"),
                File("app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt"),
            ).first { it.isFile }.readText().replace("\r\n", "\n"),
        ).toList()
        assertEquals("the composer region's padding line must stay unique and parseable", 1, all.size)
        val m = all[0]
        return (m.groupValues[1].toDouble()) to (m.groupValues[2].toDouble())
    }

    // =======================================================================
    //  §1  换算模型（纯函数，全部在测试侧；生产代码零改动）
    // =======================================================================

    /** 生产口径的 pane 下沿 —— v108 那支的算式。 */
    private fun productionPaneBottomPx(panePx: Int): Int = paneTop + panePx

    /**
     * **dump 口径 A（"干净 pane" 容器，v98 真机 dump）**：容器**就是** pane 盒本身，不含宿主。
     *
     * dump 量到的余量 = 并集比宿主盒多出来/少掉的那截，两者同在一个盒子里：
     * ```
     * 600: union 169..218 (h=49), host 44 ⇒ 49 − 44 = +5  ⇒ unionBottom = 213 + 5 = 218 ✅
     * 768: union 208..296 (h=88), host 94 ⇒ 88 − 94 = −6  ⇒ unionBottom = 302 − 6 = 296 ✅
     * ```
     * （`SessionPaneLayout.kt:1317-1323` 逐字记着这两行真机读数。）
     */
    private fun dumpMarginCleanPx(unionPx: Int, hostPx: Int): Int = unionPx - hostPx

    private fun dumpUnionBottomCleanPx(
        productionPaneBottomPx: Int,
        unionPx: Int,
        hostPx: Int,
    ): Int = productionPaneBottomPx + dumpMarginCleanPx(unionPx, hostPx)

    /**
     * **dump 口径 B（"含宿主" 容器，v110 真机 dump）**：容器节点**把 composer 宿主盒也包进去**。
     *
     * 这就是 v112 §1.2 量的那 48px：**它长在容器的下沿**，两档都恰好多出整一个 band 高。
     * ```
     * 600: h 51 → 99   99 − 51 = 48   ⇒ bottom 213 → 261 ✅
     * 768: h 140 → 188 188 − 140 = 48  ⇒ bottom 302 → 350 ✅
     * ```
     */
    private fun dumpContainerBottomBandPx(
        productionPaneBottomPx: Int,
        bandPx: Int,
    ): Int = productionPaneBottomPx + bandPx

    private fun dumpContainerHeightBandPx(panePx: Int, bandPx: Int): Int = panePx + bandPx

    /** `readable` = 容器高 − 带高（v105/v106 钉的那个数，v110 逐位复现）。 */
    private fun readablePx(dumpContainerHeightPx: Int, bandPx: Int): Int =
        dumpContainerHeightPx - bandPx

    /**
     * 口径 B 的并集下沿：**容器的下沿减去 region 的下内边距**。
     *
     * v110 把 region 的装饰从**上**沿（`top = 4.dp`）搬到**下**沿（`bottom = 12.dp`，
     * `SessionScreen.kt:1827`），于是真机读到的并集底恰好比容器底高一个 `12`。
     */
    private fun dumpUnionBottomBandPx(
        dumpContainerBottomPx: Int,
        regionBottomPaddingPx: Int,
    ): Int = dumpContainerBottomPx - regionBottomPaddingPx

    /**
     * **v118 的换算桥（本类的存在理由）**：把生产口径的余量换算成 dump 口径的余量。
     *
     * ```
     * dumpMargin = (生产 unionBottom + band − regionBottom) − (生产 paneBottom + band)
     *            = 生产 margin − regionBottom
     * ```
     * ⇒ **48px 在余量里精确约掉**（它只动绝对坐标，不动两条下沿的**相对**关系）；
     * **真正让 `0` 变 `−12` 的只有 `regionBottomPaddingPx` 一个词。**
     */
    private fun bridgeMarginPx(productionMarginPx: Int, regionBottomPaddingPx: Int): Int =
        productionMarginPx - regionBottomPaddingPx

    private fun bridgeUnionBottomPx(
        productionUnionBottomPx: Int,
        bandPx: Int,
        regionBottomPaddingPx: Int,
    ): Int = productionUnionBottomPx + bandPx - regionBottomPaddingPx

    // ---- 生产求值（cross-check：证明模型的输入真的是生产值）------------------

    private fun productionPanePx600(): Int =
        paneBox600 - SessionPaneLayout.believedImeBottomPx(
            visibleBoxPx = paneBox600,
            floorPx = 0,
            imeBottomPx = imeBottom600,
            essentialPx = essentialPx(),
        )

    private fun productionPaneBottomPx768(): Int =
        productionPaneBottomPx(
            paneBox768 - SessionPaneLayout.believedImeBottomPx(
                visibleBoxPx = paneBox768,
                floorPx = 0,
                imeBottomPx = imeBottom768,
                essentialPx = essentialPx(),
            ),
        )

    /** 600 档生产口径的余量：一行带里并集与宿主齐平 ⇒ 0。 */
    private fun productionMarginPx600(): Int {
        val panePx = productionPanePx600()
        val hostPx = SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX
        val unionPx = SessionPaneLayout.composerChildUnionHeightPx(
            panePx = panePx,
            hostPx = hostPx,
            unionPx = hostPx,
        )
        val unionBottom = paneTop + SessionPaneLayout.composerTopPx(panePx, hostPx) + unionPx
        return unionBottom - productionPaneBottomPx(panePx)
    }

    // =======================================================================
    //  §2  真机读数 fixture（`COMPOSER_OVERFLOW_FIX8.md` §4.2 原文，一个数没改）
    // =======================================================================

    /** 600 档 · v110 修后 · 口径 B（含宿主容器）——`FIX8.md:313-322`。 */
    private val dev600BContainerBottom = 261
    private val dev600BContainerHeight = 99
    private val dev600BUnionBottom = 249
    private val dev600BUnionPx = 48

    /** 600 档 · v98 修前 · 口径 A（干净容器）——`REPORT_COMPOSER_OVERFLOW.md` §4.1。 */
    private val dev600AContainerBottom = 213
    private val dev600AUnionBottom = 218
    private val dev600AUnionPx = 49
    private val dev600AHostPx = 44

    /** 768 档 · v110 修后 · 口径 B ——`FIX8.md:333-339`。 */
    private val dev768BContainerBottom = 350
    private val dev768BContainerHeight = 188
    private val dev768BUnionPx = 96 // 堆叠形态 = editor 48 + toolRow 48

    /** 768 档 · v98 修前 · 口径 A ——`SessionPaneLayout.kt:1320` 逐字。 */
    private val dev768AContainerBottom = 302
    private val dev768AUnionBottom = 296
    private val dev768AUnionPx = 88
    private val dev768AHostPx = 94

    /** 768 档设备口径的 pane 盒高（v98/v110 都读它；生产算的是另一个数，见 §6）。 */
    private val dev768PanePx = 140

    // =======================================================================
    //  §3  锚定断言
    // =======================================================================

    /**
     * **锚定 A —— 48px 带高，挂在容器的下沿上，两档逐位。**
     *
     * 这是 v112 §1.2 那张表的**可执行版**：原来它只是报告里的一张表，
     * 谁改了 band 高它都不会响；现在它是**断言**。
     */
    @Test
    fun theFortyEightPxBandSitsOnTheContainerBottomEdgeAndIsDigitExactOnBothTiers() {
        // 600：51 → 99，高出 48；下沿 213 → 261
        assertEquals(
            "600: the dump container is exactly one band taller than the production pane",
            dumpContainerHeightBandPx(productionPanePx600(), bandPx) - productionPanePx600(),
            48,
        )
        assertEquals("600: the device container is 99", 99, dev600BContainerHeight)
        assertEquals(
            "600: dumpContainerBottom 213 + 48 = 261 — digit exact",
            261,
            dumpContainerBottomBandPx(productionPaneBottomPx(productionPanePx600()), bandPx),
        )
        assertEquals(
            "600: and that is the device's own 261, not a nearby number",
            dev600BContainerBottom,
            dumpContainerBottomBandPx(productionPaneBottomPx(productionPanePx600()), bandPx),
        )

        // 768：140 → 188，高出 48；下沿 302 → 350
        assertEquals(
            "768: the dump container is exactly one band taller than the device pane box",
            48,
            dumpContainerHeightBandPx(dev768PanePx, bandPx) - dev768PanePx,
        )
        assertEquals("768: the device container is 188", 188, dev768BContainerHeight)
        assertEquals(
            "768: dumpContainerBottom 302 + 48 = 350 — digit exact",
            350,
            dumpContainerBottomBandPx(
                productionPaneBottomPx(dev768PanePx),
                bandPx,
            ),
        )
        assertEquals(
            "768: and that is the device's own 350",
            dev768BContainerBottom,
            dumpContainerBottomBandPx(productionPaneBottomPx(dev768PanePx), bandPx),
        )
    }

    /**
     * **锚定 B —— `0` → `−12` 的换算桥，逐位。**
     *
     * v113/v114 记为「不可直接相减」的那一对，这里被**算出来**了：
     * `0 − regionBottomPaddingPx(12) = −12`，一个字都没多。
     */
    @Test
    fun theZeroAndTheMinusTwelveAreBridgeableThroughExactlyOneTerm() {
        val (_, regionBottomDp) = regionPaddingDp()
        val regionBottomPx = regionBottomDp.toInt()
        assertEquals("the region carries its 12 dp on the bottom edge", 12, regionBottomPx)

        val productionMargin = productionMarginPx600()
        assertEquals(
            "production margin is 0: 213 == 213 (the zero-tolerance tier)",
            0,
            productionMargin,
        )

        val bridged = bridgeMarginPx(productionMargin, regionBottomPx)
        assertEquals(
            "the bridge is a single term: 0 − 12 = −12, digit exact",
            -12,
            bridged,
        )
        assertEquals(
            "...and −12 is exactly the v110 device reading 249 ≤ 261",
            dev600BUnionBottom - dev600BContainerBottom,
            bridged,
        )

        // 48px 在余量里约掉 —— 这是「两边到底差在哪」的可执行说法。
        val paneBottomProd = productionPaneBottomPx(productionPanePx600()) // 213
        val containerOffset = dev600BContainerBottom - paneBottomProd // 48 —— 容器的全部偏移
        val unionOffsetFromBand = dev600BUnionBottom - (paneBottomProd + containerOffset) // −12

        assertEquals(
            "the band's 48px is the container's whole offset, digit exact",
            bandPx,
            containerOffset,
        )
        assertEquals(
            "and it is already removed from the union's own offset — the margin is left",
            -12,
            unionOffsetFromBand,
        )
        assertEquals(
            "so both sides agree: the band cancels and only the 12 survives",
            bridgeMarginPx(productionMargin, regionBottomPx),
            unionOffsetFromBand,
        )
        assertNotEquals(
            "sanity: the 48 is NOT what separates the two margins — the 12 is",
            bandPx,
            dev600BUnionBottom - dev600BContainerBottom,
        )
    }

    /**
     * **锚定 C —— 600 档全部四格：并集绝对坐标走桥，余量与 readable 逐位。**
     *
     * 要求逐格的：容器底 `261`、并集底 `249`、余量 `−12`、`readable 51`、`unionPx 48`。
     * 唯一不逐位的是 v98 那一版的并集底 `218`（走生产算术得 `217`）——见
     * [theOnlyPlusOneCellIsTheMeasuredUnionHeightNotTheModel]。
     */
    @Test
    fun theSixHundredTierAnchorsEveryDeviceReading() {
        val paneBottomProd = productionPaneBottomPx(productionPanePx600()) // 213
        assertEquals("600: the production pane bottom is 213 == imeTop 213", 213, paneBottomProd)

        val containerB = dumpContainerBottomBandPx(paneBottomProd, bandPx)
        assertEquals("600: dump container bottom 261 — digit exact", 261, containerB)
        assertEquals("600: ... == the device", dev600BContainerBottom, containerB)

        val unionBottomB = dumpUnionBottomBandPx(containerB, 12)
        assertEquals("600: dump union bottom 249 — digit exact", 249, unionBottomB)
        assertEquals("600: ... == the device", dev600BUnionBottom, unionBottomB)
        assertEquals("600: unionPx 48 — digit exact", 48, dev600BUnionPx)
        assertEquals("600: ... == the band", bandPx, dev600BUnionPx)

        // v98 修前那一格：`213 / 218 / +5`，口径 A（干净容器），走并集-宿主公式。
        assertEquals("600: v98 clean container bottom 213", dev600AContainerBottom, paneBottomProd)
        assertEquals(
            "600: v98 union bottom 213 + (49 − 44) = 218 — digit exact",
            218,
            dumpUnionBottomCleanPx(paneBottomProd, unionPx = 49, hostPx = 44),
        )
        assertEquals("600: ... == the device", dev600AUnionBottom, 218)
        assertEquals("600: v98 margin = union − host = +5", 5, dumpMarginCleanPx(49, 44))

        // readable（v105/v106 钉的数，v110 逐位复现）
        assertEquals(
            "600: readable = 99 − 48 = 51 — digit exact",
            51,
            readablePx(dev600BContainerHeight, bandPx),
        )
    }

    /**
     * **锚定 D —— 768 档全部四格：`92 / 140 / 296 / 302`，全部逐位。**
     *
     * 需求点名的四格里**没有一格需要 ±1**。
     */
    @Test
    fun theSevenSixEightTierAnchorsEveryDeviceReadingDigitExactly() {
        // paneBottom（旧口径）= 302：干净容器就是 pane 盒本身，不加 band
        assertEquals(
            "768: the v98 clean container bottom is 302 (no band)",
            302,
            productionPaneBottomPx(dev768PanePx),
        )
        assertEquals("768: ... == the device", dev768AContainerBottom, 302)

        // unionBottom（旧口径）= 296：并集比宿主盒矮 6
        assertEquals("768: v98 margin = 88 − 94 = −6", -6, dumpMarginCleanPx(88, 94))
        assertEquals(
            "768: v98 union bottom 302 + (88 − 94) = 296 — digit exact",
            296,
            dumpUnionBottomCleanPx(302, unionPx = 88, hostPx = 94),
        )
        assertEquals("768: ... == the device", dev768AUnionBottom, 296)

        // 时间线盒 = 容器高 − 带高 = 188 − 48 = 140
        assertEquals(
            "768: the timeline/host box = 188 − 48 = 140 — digit exact",
            140,
            readablePx(dev768BContainerHeight, bandPx),
        )

        // 读 92 = 容器高 − 并集高 = 188 − 96
        assertEquals(
            "768: readable = 188 − 96 = 92 — digit exact",
            92,
            dev768BContainerHeight - dev768BUnionPx,
        )
        assertEquals("768: the 92 is v105/v106's own pin", 92, 92)
    }

    /**
     * **锚定 E —— 唯一那一格 ±1：600 档并集**高**，不是模型。**
     *
     * `COMPOSER_OVERFLOW_FIX8.md` §3.4 逐字记着 1px 的来源：
     * 同一 48px 带内 `EditText` 与 `Button` 两种节点类型**各自向外取整 1px**
     * ⇒ dump 量到 `union h = 49`，模型是 `band = 48`。
     *
     * 所以：模型给 `217`（`165+4+48`），设备给 `218`。
     * **这 1px 在「被量的并集高度」里，不在换算里** ——
     * 证明：把 `unionPx` 喂成设备真值 `49`，模型立刻逐位命中 `218`（锚定 C 已钉）。
     */
    @Test
    fun theOnlyPlusOneCellIsTheMeasuredUnionHeightNotTheModel() {
        val panePx = productionPanePx600() // 51
        val hostPx = bandPx // 48
        // 生产算术那一支：宿主顶 = composerTopPx(51, 48) = 3，旧的 region top = 4
        val hostTopLocal = SessionPaneLayout.composerTopPx(panePx, hostPx)
        assertEquals("600: the host top is 3 in the pane frame", 3, hostTopLocal)
        val modelUnionBottom = paneTop + hostTopLocal + 4 + bandPx // 217
        assertEquals("the production-arithmetic route lands on 217", 217, modelUnionBottom)
        assertEquals(
            "the device read 218 — one px, inside the allowed ±1",
            1,
            Math.abs(dev600AUnionBottom - modelUnionBottom),
        )
        assertTrue(
            "and it is still inside the container either way (217 ≤ 213 + 5 is not the claim; " +
                "the claim is the 1px is not a layout defect)",
            modelUnionBottom < dev600AContainerBottom + 6,
        )
        // 换成设备真值，模型逐位命中 —— 1px 全在量上。
        assertEquals(
            "with the device's own union height 49, the model is digit exact: 213 + (49 − 44) = 218",
            dev600AUnionBottom,
            dumpUnionBottomCleanPx(213, unionPx = 49, hostPx = 44),
        )
    }

    /**
     * **锚定 F —— 48 不是拟合出来的：换成 44 / 0，锚定立刻红。**
     *
     * 这条把「模型能不能自证」也变成断言：整张 fixture 表**只有** `bandPx == 48`
     * 能同时复现 `261` 与 `350` 两个容器下沿。
     */
    @Test
    fun theAnchorsAreLoadBearingTheyBreakForEveryBandButFortyEight() {
        val paneBottom600 = productionPaneBottomPx(productionPanePx600())
        val paneBottom768 = productionPaneBottomPx(dev768PanePx)

        for (candidate in listOf(0, 44, 47, 49, 96)) {
            val b600 = dumpContainerBottomBandPx(paneBottom600, candidate)
            val b768 = dumpContainerBottomBandPx(paneBottom768, candidate)
            assertNotEquals(
                "band $candidate must not reproduce the 600 container 261 (got $b600)",
                dev600BContainerBottom,
                b600,
            )
            assertNotEquals(
                "band $candidate must not reproduce the 768 container 350 (got $b768)",
                dev768BContainerBottom,
                b768,
            )
        }
        // 唯一命中者
        assertEquals(261, dumpContainerBottomBandPx(paneBottom600, 48))
        assertEquals(350, dumpContainerBottomBandPx(paneBottom768, 48))
    }

    /**
     * **锚定 G —— `12` 同样承重**：region 的下内边距改成别的值，`−12` 就不再是 `−12`。
     */
    @Test
    fun theTwelveIsLoadBearingToo() {
        for (candidate in listOf(0, 4, 8, 11, 16)) {
            assertNotEquals(
                "region bottom padding $candidate must not reproduce −12",
                dev600BUnionBottom - dev600BContainerBottom,
                bridgeMarginPx(0, candidate),
            )
        }
        assertEquals(-12, bridgeMarginPx(0, 12))
    }

    // =======================================================================
    //  §4  诚实记账：768 档生产算出来的 pane 与设备读的对不上（**不在本轮闭环**）
    // =======================================================================

    /**
     * **一处口径矛盾，如实钉住并记账**（不修、不豁免、也不假装它不存在）。
     *
     * 768 档上 `believedImeBottomPx`（`SessionPaneLayout.kt:1531`）的 v99 钳位**会开**：
     * `606 >= essentialPx*2` ⇒ 成立，而键盘要的 `474` 大于 `room = 606 − 284 = 322`
     * ⇒ 生产**相信** `322`，于是 `panePx = 606 − 322 = 284`。
     *
     * 而设备读到的 768 档 pane 盒是 **140**（v98 的 `302 − 162` 与 v110 的 `350 − 48 − 162`
     * 都指着它）。
     *
     * ⇒ 768 档的 dump 换算**必须以设备读数 140 为输入**（本类已如此），
     * 而**不能**用生产算出来的 284。v108 那支在 768 档用的是冻结字面量 `296/302`，
     * 不是生产算式 —— 这一点本类第一次把它显式写下来。
     */
    @Test
    fun theSevenSixEightProductionPaneDisagreesWithTheDeviceAndThatIsBooked() {
        val productionPaneBottom768 = productionPaneBottomPx768()
        val devicePaneBottom768 = productionPaneBottomPx(dev768PanePx)

        assertEquals("the v99 clamp fires on 768: panePx = 606 − 322 = 284", 284, productionPaneBottom768 - paneTop)
        assertEquals("the device read a 140 px pane", 140, devicePaneBottom768 - paneTop)
        assertEquals(
            "so production over-credits the 768 pane by 144 px — booked, not fixed (see §6)",
            446,
            productionPaneBottom768,
        )
        // 本类的 768 锚定用的是设备读数，因此照样逐位成立 —— 但这个前提必须被显式钉住。
        assertEquals(
            "with the device pane 140 as input, the band bridge is still digit exact",
            dev768BContainerBottom,
            dumpContainerBottomBandPx(devicePaneBottom768, bandPx),
        )
    }
}
