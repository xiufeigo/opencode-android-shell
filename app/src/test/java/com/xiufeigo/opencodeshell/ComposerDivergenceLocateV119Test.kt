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
 * ## A（**v124 修正后 = 96px**；本文件原先记的 144px 已作废）：`believedImeBottomPx`
 * 的 v99 钳位**只在 768 档开**，且开的时候把 pane 钉成
 * **`PANE_ESSENTIAL_HEIGHT − floorPx`**（钳位返回 `roomPx + floorPx`）。
 * KDoc `SessionPaneLayout.kt:1528-1529` 那句「it caps the pane at exactly
 * [essentialPx] where it does fire」**只在 `floorPx = 0` 时逐字成立**。
 *
 * ## v124 的两处输入修正（**真机 insets 原文**，第 3 次终验 §4）
 *
 * 1. `imeBottomPx`：`474` ⇒ **`426`**。
 *    原文 `InsetsSource id=3 type=ime frame=[0,342][1366,768]` ⇒ `768 − 342 = 426`。
 *    旧值 474 反推的 ime 顶边是 `294`（v110 那一轮读数），**不是当前 build 的顶边**；
 *    而 `474 − 426 = 48` **恰好等于导航条** ⇒ 那个「说不不通的 −8」是这 48 被误记成 8 的残影。
 * 2. `floorPx`：`0` ⇒ **`48`**。生产 `SessionScreen.kt:2362-2363` 读的是
 *    `navigationBars`，原文 `frame=[0,720][1366,768]` ⇒ `768 − 720 = 48`。
 *
 * ⇒ **「多算 144」虚高了整整一个导航条**；实测口径下的多算是 **96**（见 §A.2/§A.3）。
 * 详见 `research/v97_fix/CRITERION_MODEL_FIX.md`。
 */
class ComposerDivergenceLocateV119Test {

    // ---- 真机读数（density 160 ⇒ 1dp == 1px；**故意保留的字面量**）----
    /** 768 档：pane 盒 = 606（= 窗口 768 − paneTop 162）。 */
    private val paneTop = 162
    private val paneBox768 = 606
    /** 768 档 ime 底 inset = `768 − imeTop(342)` = **426**（第 3 次终验 §4 insets 原文）。 */
    private val imeBottom768 = 426
    /** 768 档导航条底 inset = `768 − navBarTop(720)` = **48**（同一段 insets 原文）。 */
    private val navFloor768 = 48
    /** 768 档设备真实 pane 盒高（旧口径 `162 + 140 = 302`，v110 口径 `350 − 48 = 302` 都指着它）。 */
    private val devicePane768 = 140
    /** 600 档：pane 盒 = 438，键盘自称 387，设备 pane 盒 51（`162 + 51 = 213`）。 */
    private val paneBox600 = 438
    private val imeBottom600 = 387
    /** 600 档导航条底 inset = `600 − nonDecorBottom(552)` = **48**（600 档 insets 原文行）。 */
    private val navFloor600 = 48
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

    /**
     * 生产侧的 pane 盒高：**与生产同形** —— `SessionScreen.kt:2370-2371` 是
     * `maxOf(paneFloorPx, believedImeBottomPx)`，不是裸的 `believed`。
     *
     * v119 的旧写法两处都与生产不同形（`floorPx = 0` 且**不取** maxOf），
     * 那正是 −8 与 144 两个假象的来路。v124 起本函数是生产算式的逐字镜像。
     */
    private fun productionPanePx(paneBoxPx: Int, imeBottomPx: Int, floorPx: Int): Int =
        paneBoxPx - maxOf(
            floorPx,
            SessionPaneLayout.believedImeBottomPx(
                visibleBoxPx = paneBoxPx,
                floorPx = floorPx,
                imeBottomPx = imeBottomPx,
                essentialPx = essentialPx(),
            ),
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
                floorPx = navFloor600,
                imeBottomPx = imeBottom600,
                essentialPx = essentialPx(),
            ),
        )
        assertEquals(
            "600: production pane = 51 = the device's, digit exact",
            devicePane600,
            productionPanePx(paneBox600, imeBottom600, navFloor600),
        )
    }

    /**
     * §A.2 768 档：钳位一开，生产 pane 恰好等于 **`PANE_ESSENTIAL_HEIGHT − floorPx`**，
     * 而设备只给了 140 ⇒ **多算 96**。
     *
     * **v124 修正**：本条原先钉的是 `paneBottom = 446`、多算 `144`。
     * 那两个数是按 `floorPx = 0` 算的；代入真机 `floorPx = 48` 后**两个数都变**：
     * ```
     *  room    = 606 − 284 = 322        claimed = 426 − 48 = 378 ; 378 > 322 ⇒ 钳位开
     *  believed= room + floor = 322 + 48 = 370
     *  panePx  = 606 − 370 = **236** = 284 − 48   ← 构造性恒等：pane = essential − floor
     *  paneBottom = 162 + 236 = **398**           设备 302  ⇒ 多算 **96**（不是 144）
     * ```
     * `144 − 96 = 48` = **一个导航条** ⇒ 旧数虚高整整一个导航条。
     * 注意 `imeBottomPx` 在这条链上**只影响 `claimed` 与 `room` 的比较**
     * （`426 > 322` 与 `474 > 322` 同真），钳位返回的是 `room + floor` ⇒
     * **144 → 96 的误差源是 `floorPx = 0`，不是 ime**（与 v123 §3 结论一致）。
     */
    @Test
    fun `the seven six eight production pane is pinned to the essential form less the measured floor and overruns the device by ninety six`() {
        val believed = SessionPaneLayout.believedImeBottomPx(
            visibleBoxPx = paneBox768,
            floorPx = navFloor768,
            imeBottomPx = imeBottom768,
            essentialPx = essentialPx(),
        )
        val prodPanePx = paneBox768 - believed
        val prodPaneBottom = paneTop + prodPanePx
        val devPaneBottom = paneTop + devicePane768

        // 钳位后的 pane **就是** essential 减去 floor —— 不是近似，是构造性恒等（`roomPx + floorPx`）。
        assertEquals(
            "768: the clamp caps the pane at exactly PANE_ESSENTIAL_HEIGHT − floorPx",
            essentialPx() - navFloor768,
            prodPanePx,
        )
        assertEquals(
            "768: the nav bar floor is 48, so the pane is 284 − 48 = 236",
            236,
            prodPanePx,
        )
        assertEquals(
            "768: production pane bottom 398 (the v108 frozen reading's rival)",
            398,
            prodPaneBottom,
        )
        assertEquals(
            "768: the device's pane bottom, digit exact",
            302,
            devPaneBottom,
        )
        assertEquals(
            "768: production over-computes the pane by exactly 96px — NOT 144",
            96,
            prodPanePx - devicePane768,
        )
        assertEquals(
            "768: the divergence is digit-exact against (essential − floor − device)",
            essentialPx() - navFloor768 - devicePane768,
            prodPanePx - devicePane768,
        )
        assertEquals(
            "…and 144 was this same number with the nav bar floor left at zero: 144 − 96 = 48",
            48,
            144 - (prodPanePx - devicePane768),
        )
    }

    /**
     * §A.3 **96 的算式（本轮钉的就是它）** —— `96 = 84 + 60 − 48`。
     *
     * chrome 那一项**不变**（84 = `CHROME_FULL_HEIGHT`，它仍然在 `PANE_ESSENTIAL_HEIGHT` 里、
     * 而 606px 的盒从 `paneTop` 往下量，**含不到它** ⇒ 二次计入是几何事实）；
     * v120 的 60 也**不变**（`TIMELINE + COMPOSER + REGION − 设备 pane`，与 ime/floor 无关）；
     * 变的是**第三个词**：真机 `floorPx = 48` 让生产把导航条那一带从盒里扣掉一次。
     *
     * ```
     * 96 = 84 (chrome 二次计入)
     *    + 60 (堆叠地板 116 − 一行地板 56，v120 §1.1 归因到行)
     *    − 48 (真机 navigationBars 底 inset，768 − 720)
     * ```
     * 等价的二项写法：`96 = 84 + 12`，其中 `12 = 72 + 116 + 12 − 140 − 48`
     * （即 60 被导航条吃掉的 48 之后的残项）。
     * **旧写法 `144 = 84 + 60` 少写了这 −48，所以整笔虚高一个导航条。**
     */
    @Test
    fun `ninety six is the eighty four plus the sixty less the measured navigation bar floor`() {
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
        val divergence = essentialPx() - navFloor768 - devicePane768
        assertEquals(
            "the divergence is 96 on the measured inputs",
            96,
            divergence,
        )
        // 三项式，与 v119 的 84 / v120 的 60 对齐：
        assertEquals(
            "96 = 84 (chrome) + 60 (stacked-vs-one-row floor) − 48 (measured nav bar floor)",
            divergence,
            chrome + 60 - navFloor768,
        );
        assertEquals(
            "…digit exact, read off production's own terms rather than a literal 60",
            divergence,
            chrome + (SessionPaneLayout.TIMELINE_MIN_HEIGHT.value.toInt() +
                SessionPaneLayout.COMPOSER_MIN_HEIGHT.value.toInt() +
                regionBottomPaddingPx() - devicePane768) - navFloor768,
        )
        // 二项式等价写法：96 = 84 + 12。
        assertEquals(
            "…equivalently 96 = 84 + 12, and 12 is what 60 keeps after the 48 floor",
            divergence,
            chrome + (60 - navFloor768),
        );
        assertEquals(
            "60 = TIMELINE_MIN_HEIGHT + COMPOSER_MIN_HEIGHT + COMPOSER_REGION_PADDING − device pane",
            60,
            SessionPaneLayout.TIMELINE_MIN_HEIGHT.value.toInt() +
                SessionPaneLayout.COMPOSER_MIN_HEIGHT.value.toInt() +
                regionBottomPaddingPx() - devicePane768,
        )
    }

    /**
     * §A.4 承重自证：把 essential 换成别的数，768 的 pane 就不是 236。
     * ⇒ 「多算 96」是**钉出来的**，不是拟合出来的。
     *
     * 循环里 `floorPx` 同样用实测的 48，否则又回到 v119 的那个 144 假象。
     */
    @Test
    fun `the ninety six is load bearing it breaks for every essential but the production one`() {
        var reproducing = 0
        for (candidate in 200..320) {
            val pane = paneBox768 - maxOf(
                navFloor768,
                SessionPaneLayout.believedImeBottomPx(
                    visibleBoxPx = paneBox768,
                    floorPx = navFloor768,
                    imeBottomPx = imeBottom768,
                    essentialPx = candidate,
                ),
            )
            if (pane - devicePane768 == 96) {
                reproducing++
                assertEquals(
                    "only the production essential may reproduce the 96px divergence",
                    essentialPx(),
                    candidate,
                )
            }
        }
        assertEquals(
            "exactly one essential in [200,320] reproduces 96 — the production one",
            1,
            reproducing,
        );
        assertEquals(
            "…and none of them reproduces the retired 144, which was a floorPx = 0 artefact",
            0,
            (200..320).count { candidate ->
                val pane = paneBox768 - maxOf(
                    navFloor768,
                    SessionPaneLayout.believedImeBottomPx(
                        visibleBoxPx = paneBox768,
                        floorPx = navFloor768,
                        imeBottomPx = imeBottom768,
                        essentialPx = candidate,
                    ),
                )
                pane - devicePane768 == 144
            },
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
     *    `CHROME_FULL_HEIGHT` 去掉（v119 §4 的最小改动方案），则 essential 从 284 变 200
     *    ⇒ 本类 §A.2 的 `assertEquals(398, prodPaneBottom)` **变红**，
     *    §A.3 的「84 + 60 − 48」分解也**同时变红**。
     *    **v123 已实测：这么做会让 v77 的 P0（丢工具栏/tab 条）复发，故 `:1467-1468` 不可动。**
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

    /**
     * §D **v124 新增**：`imeBottomPx` 与 `floorPx` 这两个模型输入**必须等于真机读数**。
     *
     * 旧值是 `474`（反推 ime 顶边 294）与 `floorPx = 0`；两者都是 **v110 那一轮**的读数，
     * 与第 3 次终验的 insets 原文冲突。**把它们钉住，就是钉住「模型不许再漂回旧 epoch」。**
     * 本条**只增不减**：它不替代任何既有 pin，只把两个输入的字面量与来源锁死。
     */
    @Test
    fun `the model inputs are the measured insets and not the retired v110 readings`() {
        assertEquals(
            "768 ime bottom inset = 768 − imeTop(342); the retired 474 implied an imeTop of 294",
            426,
            imeBottom768,
        )
        assertEquals(
            "…and the retired 474 overshot the truth by exactly one navigation bar",
            navFloor768,
            474 - imeBottom768,
        )
        assertEquals(
            "768 floor = 768 − navBarTop(720) = 48; production reads it at SessionScreen.kt:2362-2363",
            48,
            navFloor768,
        )
        assertEquals(
            "the 600 tier's nav bar is the same 48 (600 − nonDecorBottom 552) — the 600 pane stays 51",
            48,
            navFloor600,
        )
        assertEquals(
            "600's ime reading needs no correction: 600 − imeTop(213) = 387 was already right",
            387,
            imeBottom600,
        )
    }
}
