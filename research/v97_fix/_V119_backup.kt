package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v119：把 v118 记的两笔未闭环账（`CRITERION_PARITY.md` §7.1 / §7.2）
 * **静态定位到行**，并把「偏差存在」这件事**钉成断言**。
 *
 * ## 纪律
 *
 * 1. **生产代码零改动** —— 本类只读生产函数/常量。
 * 2. **不许造假绊线** —— 每一格的生产侧都取自 [SessionPaneLayout] 的**真函数/真常量**
 *    （v112 起「region 切分从源码解析、不在测试里重述」的手法沿用），
 *    字面量只允许出现在**真机读数**一侧（`COMPOSER_OVERFLOW_FIX8.md` 原文，
 *    `wm size 1366x600`/`1366x768` + `wm density 160` ⇒ **1dp == 1px**）。
 * 3. **只钉「当前生产值 == 偏差值」**，不实现未来态。
 *    「当有人把生产改成 §4/§5 的建议方案时本 pin 会红」写在 KDoc 与 §6 的反向验证里。
 *
 * ## A（144px）：`believedImeBottomPx` 的 v99 钳位**只在 768 档开**，且开的时候
 * **把 pane 钉成 `PANE_ESSENTIAL_HEIGHT` 一个数**（KDoc `SessionPaneLayout.kt:1528-1529`
 * 自己写着「it caps the pane at exactly [essentialPx] where it does fire」）。
 */
class ComposerDivergenceLocateV119Test {

    // ---- 真机读数（v110 dump 原文，density 160 ⇒ 1dp == 1px；**故意保留的字面量**）----
    /** 768 档：pane 盒 = 606（= 窗口 768 − paneTop 162），键盘自称 474。 */
    private val paneTop = 162
    private val paneBox768 = 606
    private val imeBottom768 = 474
    /** 768 档设备真实 pane 盒高（旧口径 `162 + 140 = 302`，v110 口径 `350 − 48 = 302` 都指着它）。 */
    private val devicePane768 = 140
    /** 600 档：pane 盒 = 438，键盘自称 387，设备 pane 盒 51（`162 + 51 = 213`）。 */
    private val paneBox600 = 438
    private val imeBottom600 = 387
    private val devicePane600 = 51
    /** 768 档 v110 堆叠形态：dump 容器底 350、dump 并集底 340。 */
    private val dumpContainerBottom768 = 350
    private val dumpUnionBottom768 = 340

    // ---- 生产量（一律取自生产）----------------------------------------------
    private fun essentialPx(): Int = SessionPaneLayout.PANE_ESSENTIAL_HEIGHT.value.toInt()
    private fun chromeFullPx(): Int = SessionPaneLayout.CHROME_FULL_HEIGHT.value.toInt()
    private val bandPx: Int get() = SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX

    /** 768 档 v110 设备几何（窗口 768 − paneTop 162 = 生产侧 pane 盒）。 */
    private val windowHeight768 = paneBox768 + paneTop

    /** `SessionScreen.kt:1827` 的 region 竖向切分，从**生产源码**解析。 */
    private fun regionBottomPaddingPx(): Int {
        val all = Regex(
            """\.padding\(\s*start = 12\.dp,\s*end = 12\.dp,\s*top = ([0-9.]+)dp,\s*bottom = ([0-9.]+)dp\s*\)""",
        ).findAll(productionText("SessionScreen.kt")).toList()
        assertEquals("the composer region's padding line must stay unique and parseable", 1, all.size)
        return all[0].groupValues[2].toDouble().toInt()
    }

    /** 堆叠形态那条工具行的 modifier —— 从**生产源码**解析出它用的盒高。 */
    private fun stackedToolRowDp(): Int {
        val src = productionText("composer/Composer.kt")
        val m = Regex(
            """\} else \{[^}]*?Modifier\.fillMaxWidth\(\)\.height\((\w+)\)\.padding\(horizontal = 8\.dp\)""",
        ).find(src)
        assertTrue("the stacked tool row's else-branch must stay parseable", m != null)
        val name = m!!.groupValues[1]
        val decl = Regex("""(?:private )?val $name\s*(?::\s*Dp)?\s*=\s*([0-9.]+)\.dp""").find(src)
        assertTrue("the stacked tool row's height constant $name must be a dp literal in source", decl != null)
        return decl!!.groupValues[1].toDouble().toInt()
    }

    private fun productionText(relative: String): String = listOf(
        File("src/main/java/com/xiufeigo/opencodeshell/ui/session/$relative"),
        File("app/src/main/java/com/xiufeigo/opencodeshell/ui/session/$relative"),
    ).first { it.isFile }.readText().replace("\r\n", "\n")

    /** 生产侧的 pane 盒高：pane 盒 − 被相信的键盘底。 */
    private fun productionPanePx(paneBoxPx: Int, imeBottomPx: Int): Int =
        paneBoxPx - SessionPaneLayout.believedImeBottomPx(
            visibleBoxPx = paneBoxPx,
            floorPx = 0,
            imeBottomPx = imeBottomPx,
            essentialPx = essentialPx(),
        )

    // =======================================================================
    //  §A  768 档的 144px —— 谁多算了，差在哪一行
    // =======================================================================

    /**
     * §A.1 档位分支：768 开、600 不开，**分界就是 `SessionPaneLayout.kt:1539` 那一行闸门**。
     *
     * 闸门原文：`if (visibleBoxPx < essentialPx * PANE_ESSENTIAL_SPARE_ROOM) return imeBottomPx`
     */
    @Test
    fun `the seven six eight tier is the only measured one that trips the v99 clamp`() {
        val gate = essentialPx() * SessionPaneLayout.PANE_ESSENTIAL_SPARE_ROOM
        assertTrue(
            "768's pane box ($paneBox768) must clear the clamp gate ($gate) — this is the branch",
            paneBox768 >= gate,
        )
        assertTrue(
            "600's pane box ($paneBox600) must stay under the gate ($gate) — so 600 is untouched",
            paneBox600 < gate,
        )

        // 600 档：闸门短路，键盘自称的 387 **原样**被相信 ⇒ pane = 51 = 设备读数（逐位）。
        assertEquals(
            "600: the clamp is inert, so the claim comes back verbatim",
            imeBottom600,
            SessionPaneLayout.believedImeBottomPx(
                visibleBoxPx = paneBox600,
                floorPx = 0,
                imeBottomPx = imeBottom600,
                essentialPx = essentialPx(),
            ),
        )
        assertEquals(
            "600: production pane = 51 = the device's, digit exact",
            devicePane600,
            productionPanePx(paneBox600, imeBottom600),
        )
    }

    /**
     * §A.2 768 档：钳位一开，生产 pane **恰好等于** `PANE_ESSENTIAL_HEIGHT`，
     * 而设备只给了 140 ⇒ **多算 144**，且多算量逐位等于 `essential − device`。
     */
    @Test
    fun `the seven six eight production pane is pinned to the essential form and overruns the device by one hundred forty four`() {
        val believed = SessionPaneLayout.believedImeBottomPx(
            visibleBoxPx = paneBox768,
            floorPx = 0,
            imeBottomPx = imeBottom768,
            essentialPx = essentialPx(),
        )
        val prodPanePx = paneBox768 - believed
        val prodPaneBottom = paneTop + prodPanePx
        val devPaneBottom = paneTop + devicePane768

        // 钳位后的 pane **就是** essential —— 不是近似，是构造性恒等（`roomPx + floorPx`）。
        assertEquals(
            "768: the clamp caps the pane at exactly PANE_ESSENTIAL_HEIGHT",
            essentialPx(),
            prodPanePx,
        )
        assertEquals(
            "768: production pane bottom 446 (the v108 frozen reading's rival)",
            446,
            prodPaneBottom,
        )
        assertEquals(
            "768: the device's pane bottom, digit exact",
            302,
            devPaneBottom,
        )
        assertEquals(
            "768: production over-computes the pane by exactly 144px",
            144,
            prodPanePx - devicePane768,
        )
        assertEquals(
            "768: the divergence is digit-exact against the essential form",
            essentialPx() - devicePane768,
            prodPanePx - devicePane768,
        )
    }

    /**
     * §A.3 **144 里整整 84 是 chrome 被算了两次** —— 这一格是**纯几何**证出来的，
     * 不需要任何真机再测：pane 盒是从 `paneTop` 往下量的，而 chrome 带在 `paneTop` **之上**。
     *
     * `PANE_ESSENTIAL_HEIGHT`（`:1467-1468`）把 `CHROME_FULL_HEIGHT`（`:1437`）也算了进去，
     * 而 `visibleBoxPx` 的量法（`SessionScreen.kt:2361` 的 `maxHeight`，
     * KDoc `:1478-1479`「the body column *without* the IME inset」）**已经在 chrome 之下**。
     */
    @Test
    fun `eighty four of the one forty four is the chrome band charged to a box that starts below it`() {
        val chrome = chromeFullPx()
        assertEquals(
            "the chrome band is HEADER_HEIGHT 48 + TAB_STRIP 36 = 84 (SessionPaneLayout.kt:1437)",
            84,
            chrome,
        )
        // 几何：pane 盒从 paneTop 往下量，chrome 在 paneTop 之上 ⇒ 不可能重进这个盒。
        assertEquals(
            "the measured pane box is window - paneTop, i.e. it starts AT paneTop",
            windowHeight768,
            paneBox768 + paneTop,
        )
        assertTrue(
            "the chrome band sits above paneTop, so the 606px box never contains it",
            chrome <= paneTop,
        )
        val divergence = essentialPx() - devicePane768
        assertEquals(
            "84 of the 144 is the chrome double count",
            chrome,
            divergence - 60,
        )
        assertEquals(
            "the residual 60 is the timeline+composer+region terms vs the device's 140",
            60,
            divergence - chrome,
        )
        // 剩下的 60 = 72(timeline) + 116(composer) + 12(region padding) − 140(设备 pane)。
        assertEquals(
            "60 = TIMELINE_MIN_HEIGHT + COMPOSER_MIN_HEIGHT + COMPOSER_REGION_PADDING − device pane",
            SessionPaneLayout.TIMELINE_MIN_HEIGHT.value.toInt() +
                SessionPaneLayout.COMPOSER_MIN_HEIGHT.value.toInt() +
                regionBottomPaddingPx() - devicePane768,
            divergence - chrome,
        )
    }

    /**
     * §A.4 承重自证：把 essential 换成别的数，768 的 pane 就不是 284。
     * ⇒ 「多算 144」是**钉出来的**，不是拟合出来的。
     */
    @Test
    fun `the one forty four is load bearing it breaks for every essential but the production one`() {
        var reproducing = 0
        for (candidate in 200..320) {
            val pane = paneBox768 - SessionPaneLayout.believedImeBottomPx(
                visibleBoxPx = paneBox768,
                floorPx = 0,
                imeBottomPx = imeBottom768,
                essentialPx = candidate,
            )
            if (pane - devicePane768 == 144) {
                reproducing++
                assertEquals(
                    "only the production essential may reproduce the 144px divergence",
                    essentialPx(),
                    candidate,
                )
            }
        }
        assertEquals(
            "exactly one essential in [200,320] reproduces 144 — the production one",
            1,
            reproducing,
        )
    }

    // =======================================================================
    //  §B  堆叠形态的 2px —— 48dp 靶在 44dp 盒里居中
    // =======================================================================

    /**
     * §B.1 **2px 逐位 = (48 − 44) / 2**：v106 只把**一行**形态的带改成 48
     * （`BAND_HEIGHT`，`Composer.kt:2655`），**堆叠**形态的工具行仍是 44
     * （`Composer.kt:2150` 的 `.height(TOOL_ROW_HEIGHT)`），而 material3 强制的
     * 48dp 靶在 `Composer.kt:2152` 的 `Alignment.CenterVertically` 下**居中**
     * ⇒ 靶比盒高 4px，**下沿探出盒底 2px**。
     */
    @Test
    fun `the two pixel stacked gap is the centred forty eight dp target hanging two px out of the forty four dp box`() {
        val stackedBoxPx = stackedToolRowDp()
        val overhangPx = (bandPx - stackedBoxPx) / 2

        assertEquals(
            "the stacked form's tool row box is still TOOL_ROW_HEIGHT = 44dp (Composer.kt:2150)",
            44,
            stackedBoxPx,
        )
        assertEquals(
            "the enforced target is material3's 48dp floor (SessionPaneLayout.kt:108)",
            48,
            bandPx,
        )
        assertEquals(
            "centring a 48dp target in a 44dp box pushes it 2px out at the bottom",
            2,
            overhangPx,
        )
        assertEquals(
            "768: the device's union bottom = container bottom − region padding + the 2px overhang",
            dumpContainerBottom768 - regionBottomPaddingPx() + overhangPx,
            dumpUnionBottom768,
        )
        assertEquals(
            "the model that knows only the host box misses exactly those 2px",
            2,
            dumpUnionBottom768 - (dumpContainerBottom768 - regionBottomPaddingPx()),
        )
    }

    /**
     * §B.2 承重自证：把堆叠盒高换成别的数，2px 就不复现；
     * 而**一行**形态（`BAND_HEIGHT`）与 48dp 靶齐平，**不留**这 2px。
     */
    @Test
    fun `the two pixels are load bearing only the stacked forty four dp box reproduces them`() {
        val stackedBoxPx = stackedToolRowDp()
        val measuredOverhang = dumpUnionBottom768 - (dumpContainerBottom768 - regionBottomPaddingPx())

        // 承重自证：把 2px 当**已知观测量**反解盒高，答案**唯一**且就是生产里的 44。
        // （这里用**精确方程** `band − box = 2 × overhang`，不用整除 ——
        //  整除会把 43 和 44 一起映射成 2，那是量化的假唯一性，不算自证。）
        assertEquals(
            "band − stackedBox = 2 × the measured 2px overhang, digit exact",
            bandPx - stackedBoxPx,
            2 * measuredOverhang,
        )
        var reproducing = 0
        for (candidate in 36..56) {
            if (bandPx - candidate == 2 * measuredOverhang) {
                reproducing++
                assertEquals("only the production stacked box solves it", stackedBoxPx, candidate)
            }
        }
        assertEquals("exactly one stacked box height in [36,56] solves the overhang", 1, reproducing)

        // 一行形态：v106 已把带抬到 48 ⇒ 靶与盒齐平，overhang = 0（**这 2px 只属堆叠形态**）。
        val oneRowBandPx = SessionPaneLayout.composerBandHeightDp(
            toolRow = SessionPaneLayout.COMPOSER_ROW_HEIGHT,
        ).value.toInt()
        assertEquals(
            "the one-row form's band is BAND_HEIGHT = 48 (Composer.kt:2655) — no overhang",
            bandPx,
            oneRowBandPx,
        )
        assertEquals(
            "so the one-row form leaves 0px of overhang: the 2px is the stacked form's alone",
            0,
            (bandPx - oneRowBandPx) / 2,
        )
        assertNotEquals(
            "the two forms are genuinely different boxes",
            oneRowBandPx,
            stackedBoxPx,
        )
    }

    // =======================================================================
    //  §C  「改了生产就会红」的说明（不实现未来态，只留话）
    // =======================================================================

    /**
     * §C 两处未来态说明（**本轮不实现**）：
     *
     * 1. 若有人把 `PANE_ESSENTIAL_HEIGHT`（`SessionPaneLayout.kt:1467-1468`）里的
     *    `CHROME_FULL_HEIGHT` 去掉（§4 的最小改动方案），则 essential 从 284 变 200，
     *    768 的 pane 变 `606 − (606 − 200) = 200`、paneBottom 变 362，
     *    ⇒ 本类 §A.2 的 `assertEquals(446, prodPaneBottom)` **变红**，
     *    §A.3 的「84 + 60 = 144」分解也**同时变红**（`divergence - 60` 不再等于 84）。
     * 2. 若有人把堆叠形态那条工具行也抬到 48（`Composer.kt:2150` 改用 `BAND_HEIGHT`），
     *    则 overhang 从 2 变 0、dump 并集底从 340 变 338，
     *    ⇒ 本类 §B.1 的 `assertEquals(2, overhangPx)` 与
     *    `assertEquals(dumpUnionBottom768, …)` **同时变红**。
     *
     * 两处都是**收紧**：pin 现在钉的是「当前生产值 == 偏差值」，
     * 偏差一旦被修掉，**必须有人来改这两条 pin 并附上新的真机读数** ——
     * 不允许它们悄悄变绿。
     */
    @Test
    fun `both pins are pinned on production values so a fix has to break them loudly`() {
        // 生产侧全部来自 SessionPaneLayout 的真函数/真常量（本轮未改生产 ⇒ 下面逐位成立）。
        assertEquals(284, essentialPx())
        assertEquals(84, chromeFullPx())
        assertEquals(12, regionBottomPaddingPx())
        assertEquals(44, stackedToolRowDp())
        assertEquals(48, bandPx)
        assertEquals(2, SessionPaneLayout.PANE_ESSENTIAL_SPARE_ROOM)
    }
}
