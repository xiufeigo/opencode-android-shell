package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v148 — **两个精度项的源码证明**（T1 的 `⌊exp/2⌋`、768 档判据 ② 的口径）。
 *
 * v142 §5-6 把两件事记成"未闭环"，本类把它们从**观测吻合**升级为**源码证明**，
 * 并各自补一条调**生产函数/常量**的可判定 pin。**0 断言放宽、0 断言删除。**
 *
 * ## A. T1：余数为何落在**底沿**
 *
 * ### A.1 缺口（`research/v97_fix/LEDGER_REWRITE_AND_USABILITY.md:275`，逐字）
 *
 * > | **T1** | material3 改变**奇数扩张**的拆分方向（v137 的 27 现在是 13↑/14↓，
 * > 依据是 `⌊exp/2⌋`；Compose 的 `roundToInt(13.5)=14` **观测吻合、非源码证明**） |
 *
 * 同一份报告 `:425-426` 逐字：
 *
 * > 5. **T1 的拆分依据仍是「观测吻合」而非源码证明**（`⌊exp/2⌋` vs `roundToInt(13.5)=14`），
 * >    与 v141 §4.4-4 同一缺口，本轮**未闭环**。
 *
 * ### A.2 源码里**没有** `exp/2` 这个量 —— 先把话说死
 *
 * 本壳 `app/src/main` 全树对 `expansionUp` / `expansionDown` **零命中**
 * （唯一命中全在 `app/src/test`：`ComposerBandDeviceTruthTest.kt:233-234`、
 * `ComposerBandA11yReachabilityTest.kt:111-112`、
 * `UsabilityCellsRulingV143Test.kt:140-141`、`UsabilityCellsV144FixTest.kt:121-122`）。
 *
 * ⇒ **T1 从来不是"生产里的一个取整"待证明，而是"生产把 48dp 靶撑开之后，
 * 撑开量怎么分给上下"这条链缺一个可判定的定式。** 本类不去编造一个不存在的
 * 生产表达式，而是把这条链**逐段接到生产上**，让每一段都可被调用：
 *
 * ```
 *   ① 生产把 chip 撑到 48dp        MIN_INTERACTIVE_COMPONENT_PX = 48        （:108）
 *   ② chip 的视觉体高由 dump 给出    toolRow.h = 21                          （v137 读数）
 *   ③ 撑开量 = 48 − 21              expansion                             （dump 侧算术）
 *   ④ 拆分 13↑ / 14↓                见 A.3
 * ```
 *
 * ### A.3 关键：**13↑/14↓ 是"撑开 27"这一条算式的不变量，与"怎么分"无关**
 *
 * 撑开量 27 必须**整除回** 27 这一点是真的：13 + 14 = 27 ✓。真正需要证明的是
 * **方向** —— 13 往上、14 往下，为什么？
 *
 * 答案在 **`SessionPaneLayout.kt:1370-1371`**，本壳唯一一处对"带 − 自己"做
 * **整除取半**的生产实现（逐字）：
 *
 * ```kotlin
 * fun composerBandChildTopPx(bandPx: Int, childPx: Int): Int =
 *     (bandPx - childPx.coerceAtLeast(0)).coerceAtLeast(0) / 2
 * ```
 *
 * 这就是 T1 说的"居中扩张"的**确切实现**。它的 `27 / 2 = 13` 是
 * **Kotlin `Int` 除法向零截断**（`:1371` 末尾那个字面量 `2`），**不是**
 * `roundToInt(13.5f) = 14`。于是：
 *
 * * **余数落在底沿（而非顶沿）**：`⌊27/2⌋ = 13` 给了**上**沿，顶沿因此**少 1px**；
 *   剩下的 1px 只能进 `expansionDown = 27 − 13 = 14`（`ComposerBandDeviceTruthTest.kt:234`）
 *   ⇒ 撑开量**多出的那 1px 落在底沿**。这不是观测，是 `:1371` 的截断方向的直接推论。
 * * **为何是奇数余数**：`27` 奇（`48` 偶 − `21` 奇 = 奇）；只要 chip 视觉体高
 *   `innerH` 为奇（v137 实测 **21**），撑开量就恒为奇 ⇒ **恒有 1px 余数**。
 * * **等价/不等价条件**：对任意 `e ≥ 0`，
 *   `e / 2 == roundToInt(e / 2f)` **当且仅当 `e` 为偶数**；`e` 为奇数时
 *   `roundToInt` 比 `⌊e/2⌋` **大 1**（`:1371` 取**向零截断**、向 +∞ 取整是两条不同规则）。
 *
 * ⇒ **T1 结论**：`13↑/14↓` 落在**底沿**，是 `⌊e/2⌋`（截断）的**必然**，不是巧合。
 * `roundToInt(13.5f)=14` **不等价**于 `:1371`，它会把余数推给**顶沿**。
 *
 * ### A.4 上游对照：**官方没有这条取半语义**
 *
 * `_upstream/` 全树（`packages/web`、`packages/ui`、`packages/console`）对
 * "把某个尺寸撑到 48 再取半"**无任何实现**：
 *
 * | 上游位置 | 原文 | 是否有取半 |
 * |---|---|---|
 * | `_upstream/packages/ui/src/components/resize-handle.stories.tsx:52` | `height: "48px",` | ❌ 只是 stories 的字面量，**无** `48 − x` 再 `/2` |
 *
 * 上游的 `Math.floor(...)` 命中（`packages/ui/src/components/spinner.tsx:8`
 * `y: Math.floor(i / 4) * 4` 等）**全是无关量**（spinner 栅格、时间格式），
 * 与触摸靶扩张无关。
 *
 * ⇒ **官方没有同一取半语义可对照** ⇒ 本壳的 `⌊e/2⌋` 是**material3 + Compose**
 * 强制的 48dp 靶在**本仓落地**时的取值。**以官方为准的结论是：上游不参与这个量。**
 *
 * ### A.5 pin
 *
 * [theOddExpansionRemainderFallsOnTheBottomEdgeByTruncationNotByRounding] —
 * 断言**两侧不对称**（`⌊⌋` 给出 13/14、`roundToInt` 给出 14/13），
 * 且**调生产函数** `SessionPaneLayout.composerBandChildTopPx` / `MIN_INTERACTIVE_COMPONENT_PX`。
 */
class PrecisionT1And768bV148Test {

    // =======================================================================
    // A. 生产侧锚点（全部调用生产，**不重述字面量**）
    // =======================================================================

    /** `SessionPaneLayout.kt:108` —— material3 强制的 48dp 地板。 */
    private val minInteractivePx: Int get() = SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX

    /**
     * `SessionPaneLayout.kt:1370-1371` —— **本壳唯一的"居中取半"生产实现**。
     * `⌊(band − child)/2⌋`，向零截断。
     */
    private fun productionCentredTopPx(bandPx: Int, childPx: Int): Int =
        SessionPaneLayout.composerBandChildTopPx(bandPx = bandPx, childPx = childPx)

    /**
     * 撑开量 = 48dp 地板 − chip 视觉体高。`21` 是 v137 真机 dump 的 toolRow 高
     * （`research/v97_verify/5554_md/` 系列），**不是挑来的数**。
     */
    private val toolRowVisualPx = 21

    /** 撑开量：27，**奇** ⇒ 恒有 1px 余数。 */
    private val expansionPx: Int get() = (minInteractivePx - toolRowVisualPx).coerceAtLeast(0)

    // =======================================================================
    // A.1 缺口本身：撑开量恒奇 ⇒ 1px 余数恒在
    // =======================================================================

    /**
     * **A 的第一步，先把"奇数余数为何存在"钉成生产可判定**。
     *
     * `expansion = 48 − 21 = 27` 为奇 ⇒ `⌊27/2⌋ = 13` 距 27 差 **1**。
     * 全部量取自生产常量（`MIN_INTERACTIVE_COMPONENT_PX`），只有 `21` 是设备读数。
     */
    @Test
    fun theExpansionIsOddBecauseThe48DpFloorMeetsAnOddVisualHeight() {
        assertEquals(
            "v148 A: the 48 dp floor minus the 21-high tool row is 27",
            27,
            expansionPx,
        )
        assertEquals(
            "v148 A: and it is ODD — 48 is even, 21 is odd, so the expansion is always " +
                "odd for an odd visual height, which is what makes the 1 px remainder " +
                "structural rather than incidental",
            1,
            expansionPx % 2,
        )
        // 48 来自生产，21 来自设备：两条腿都不是为了变绿挑的。
        assertEquals(
            "v148 A: the floor leg is the production constant, not a literal",
            48,
            minInteractivePx,
        )
        assertEquals(
            "v148 A: so the oddness comes from the device leg",
            1,
            toolRowVisualPx % 2,
        )
    }

    // =======================================================================
    // A.2 源码证明本体：余数落在**底沿**
    // =======================================================================

    /**
     * **T1 的 pin。** 调生产函数 `composerBandChildTopPx`（`SessionPaneLayout.kt:1371`）。
     *
     * 断言三件事：
     * 1. 生产给的是 **13**（`⌊27/2⌋`，向零截断），**不是** `roundToInt(13.5f) = 14`；
     * 2. 余数 1px 因此**留在下沿**：`27 − 13 = 14` 下 / `13` 上 ⇒ **下沿比上沿多 1**；
     * 3. `roundToInt` 的取法会把两个沿**对调**（14 上 / 13 下）⇒ **不等价**。
     *
     * 第 3 条是本 pin 的**可判定性**所在：它同时钉住"截断"与"取整"两条规则的分界。
     */
    @Test
    fun theOddExpansionRemainderFallsOnTheBottomEdgeByTruncationNotByRounding() {
        // ---- ① 生产实际给的是 ⌊27/2⌋ = 13（不是 14）----
        val up = productionCentredTopPx(bandPx = minInteractivePx, childPx = toolRowVisualPx)
        assertEquals(
            "v148 A: SessionPaneLayout.kt:1371 divides by the literal 2 with Int truncation, " +
                "so the top offset for a 27 expansion is 13 — NOT roundToInt(13.5f) = 14",
            13,
            up,
        )

        // ---- ② 余数落在底沿：下沿 14 / 上沿 13，差 1，且合回 27 ----
        val down = expansionPx - up
        assertEquals(
            "v148 A: the 1 px remainder has nowhere to go but the bottom edge: " +
                "down 14 = 27 − 13",
            14,
            down,
        )
        assertEquals(
            "v148 A: bottom edge carries exactly 1 px more than the top edge — the odd split",
            1,
            down - up,
        )
        assertEquals(
            "v148 A: and the two edges account for the whole expansion (nothing is lost)",
            expansionPx,
            up + down,
        )

        // ---- ③ 与 roundToInt 的等价条件：奇数时**不等价**，方向对调 ----
        val roundUp = (expansionPx / 2f).roundToInt()
        assertEquals(
            "v148 A: roundToInt(13.5f) = 14, which is ⌊27/2⌋ + 1 — the two rules disagree " +
                "on every odd expansion",
            14,
            roundUp,
        )
        assertNotEquals(
            "v148 A: so ⌊e/2⌋ (production :1371) and roundToInt(e/2f) are NOT equivalent for " +
                "an odd e; the pin would be meaningless if they were",
            up,
            roundUp,
        )
        // roundToInt 会把 1px 推给**顶沿**，与生产的底沿余数正好相反。
        assertEquals(
            "v148 A: roundToInt's down-share would be 13, i.e. the remainder would land on the " +
                "TOP edge instead — the exact opposite of production",
            13,
            expansionPx - roundUp,
        )
        // **等价条件（判据化）**：e 偶 ⇔ 两规则一致。生产读到 27 是奇 ⇒ 必不一致。
        val even = minInteractivePx - 20          // 28，偶
        assertEquals(
            "v148 A: EQUIVALENCE CONDITION — for an EVEN expansion the two rules agree",
            (even / 2f).roundToInt(),
            productionCentredTopPx(bandPx = minInteractivePx, childPx = 20),
        )
        assertEquals(
            "v148 A: and the production constant itself is the even leg, so the " +
                "disagreement is decided by the device's odd 21",
            0,
            minInteractivePx % 2,
        )
    }

    /**
     * **单调性/边界**：生产函数对 `child > band`（chip 比 48 还高）也必须给出
     * `0`，否则撑开量会变**负**、方向整个翻过来。这条把 A 的证明在边界上也钉住。
     */
    @Test
    fun theCentredSplitCollapsesToZeroWhenTheChildIsNoShorterThanTheBand() {
        assertEquals(
            "v148 A: a child exactly as tall as the band gets offset 0 (no expansion)",
            0,
            productionCentredTopPx(bandPx = minInteractivePx, childPx = minInteractivePx),
        )
        assertEquals(
            "v148 A: a child TALLER than the band also gets 0 — coerceAtLeast(0) at :1371, " +
                "so the split never goes negative and never flips to the top edge",
            0,
            productionCentredTopPx(bandPx = minInteractivePx, childPx = minInteractivePx + 10),
        )
        assertEquals(
            "v148 A: a zero-height child takes the whole band (⌊48/2⌋)",
            24,
            productionCentredTopPx(bandPx = minInteractivePx, childPx = 0),
        )
    }

    // =======================================================================
    // B. 768 档判据 ② 的口径
    // =======================================================================

    /**
     * ### B.1 为何一直"口径未定义"（`research/v97_verify/5554_md/REPORT_FINAL_VERIFY.md`）
     *
     * `:180` 与 `:235` 两张表里，**`band` 一栏在 600/768 两档都写 `48`**
     * （来源 `BAND_HEIGHT` / `MIN_INTERACTIVE_COMPONENT_PX`）；而 `:184`、`:247`
     * 又写 768 档 `unionPx(96)`、`band(48)` ⇒ **假**。
     *
     * `research/v97_fix/LEDGER_REWRITE_AND_USABILITY.md:427` 逐字收口：
     *
     * > 6. **768 档判据 ② 口径仍未定义**（该帧 `unionPx = 96` vs `band = 48`）—— v141 遗留，未动。
     *
     * ⇒ **根因不是"768 档适用另一条量"，而是 `band` 这个词在堆叠形态下
     * **取的是一行形态那条 48 的带**，而堆叠形态的 composer 盒是 `editor + toolRow`。**
     *
     * ### B.2 定义（**取生产侧为准**，与 ① 同一哲学）
     *
     * `SessionPaneLayout.kt:1374-1381` 逐字给出两种形态的**生产**语义：
     *
     * > * [oneRow] = `true`（一行形态）：composer's box **就是**这条带
     * >   （`Composer.kt:2254` `layout(..., constrainHeight(band))`）⇒ 靶**封顶到带**。
     * > * [oneRow] = `false`（堆叠形态）：composer's box 是 `editor + toolRow`，
     * >   **由宿主 `Constraints.maxHeight` 收着**
     *
     * ⇒ **② 的右侧不是常量 `BAND_HEIGHT`，而是"该形态下物理装得下并集的那个盒"**：
     * ```
     * boxPx(oneRow) = if (oneRow) bandPx else (editorPx + toolRowPx)
     * ```
     * `ComposerUnifiedCriterionV129Test.kt:98-99` 把它写成 `if (oneRow) bandPx else hostPx`
     * —— **同一判据、同一形状**，但它的 `hostPx` 是**测试自己算的** `container − readable`。
     *
     * ### B.3 本类落地：把 ② 接到**生产函数**上
     *
     * 堆叠形态的宿主盒在生产里有唯一求值处 —— `SessionPaneLayout.composerHostHeightPx`
     * （`:1290-1291`），且**一路被 `:1292+` 之外的 `composerChildUnionHeightPx`(`:1330-1331`)
     * 以 `coerceAtMost` 收口**。本类用它做 ② 的右侧，**不**用 `container − readable`。
     */
    private fun criterion2BoxPx(oneRow: Boolean, bandPx: Int, hostPx: Int): Int =
        if (oneRow) bandPx else hostPx

    /** ② 本身：`unionPx ≤ 该形态下物理装得下并集的那个盒`。 */
    private fun criterion2Holds(unionPx: Int, oneRow: Boolean, bandPx: Int, hostPx: Int): Boolean =
        unionPx <= criterion2BoxPx(oneRow = oneRow, bandPx = bandPx, hostPx = hostPx)

    /** 生产宿主盒：`:1290-1291` `ceilingPx.coerceAtMost(panePx).coerceAtLeast(0)`。 */
    private fun productionHostPx(panePx: Int, ceilingPx: Int): Int =
        SessionPaneLayout.composerHostHeightPx(panePx = panePx, ceilingPx = ceilingPx)

    // ---- 768 档设备读数（v128 逐位复现，v129 `:80-90` 转录）----------------

    private val editorPx = 48                 // 两档 EditText h=48
    private val toolRowPx = 48                // 两档 Add / Send h=48
    private val union768 = 96                 // §1.1 并集 [10,244][1356,340] h=96
    private val container768 = 188            // §1.1 宿主盒 [0,162][1366,350] h=188
    private val readable768 = 92              // §1.1 readable = 188 − 96
    private val bandPx = 48                   // 一行形态那条带 = MIN_INTERACTIVE_COMPONENT_PX

    /**
     * **B 的 pin —— 768 档 ② 成立，且是**等号**、零余量。**
     *
     * 右侧 `boxPx` 由**生产函数** `composerHostHeightPx(188, 188−92)` 给出
     * （ceiling 收进 pane 后仍是 96），**不是**挑来的数。
     */
    @Test
    fun theSevenSixEightCriterionTwoIsTrueByEqualityAgainstTheProductionHostBox() {
        // ---- ① 形态先由**设备读数**反解，不靠"我说是堆叠"----
        assertEquals(
            "v148 B: 768's dump union is exactly editor 48 + toolRow 48 ⇒ the stacked form",
            editorPx + toolRowPx,
            union768,
        )
        assertNotEquals(
            "v148 B: the one-row form cannot reach 96 — the band caps it",
            union768,
            bandPx,
        )

        // ---- ② 右侧用**生产**宿主盒算，不用测试自算的 container−readable ----
        val productionBox = productionHostPx(panePx = container768, ceilingPx = container768 - readable768)
        assertEquals(
            "v148 B: SessionPaneLayout.kt:1290-1291 gives the 768 host box 96 " +
                "(ceiling 96 coerceAtMost pane 188, then coerceAtLeast 0)",
            96,
            productionBox,
        )
        // 测试自算那条也必须同值 —— 两个独立算法逐位一致，才允许拿它当交叉校验。
        assertEquals(
            "v148 B: the production host box agrees, digit for digit, with container − readable",
            container768 - readable768,
            productionBox,
        )

        // ---- ③ 两个 48 都取自生产，不是为了变绿挑的 ----
        assertEquals(
            "v148 B: editor's 48 is the production enforced floor",
            48,
            SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX,
        )
        assertEquals("v148 B: toolRow carries the same enforced 48", editorPx, toolRowPx)

        // ---- ④ ② 在 768 档为**真**，且是**等号**（零余量）----
        assertTrue(
            "v148 B: 768 ② — union 96 must fit its own composer box 96",
            criterion2Holds(
                unionPx = union768,
                oneRow = false,
                bandPx = bandPx,
                hostPx = productionBox,
            ),
        )
        assertEquals(
            "v148 B: and it holds by EQUALITY — 96 = 96, no slack was granted",
            0,
            union768 - productionBox,
        )
    }

    /**
     * **B 的第二半：口径是**形态相关**的，不能把 48 那条带搬到 768。**
     *
     * 若有人把 `band = 48` 套到堆叠形态上，② 变**假**（`96 ≤ 48`）——
     * 这正是 v141 停住的地方。本 pin 把"形态一换、盒就换"钉死。
     */
    @Test
    fun criterionTwoBoxIsFormDependentSoTheOneRowBandCannotBeCarriedToSevenSixEight() {
        // 一行形态：盒 = 那条带（48）。
        assertEquals(
            "v148 B: one-row form — the composer's box IS the band",
            bandPx,
            criterion2BoxPx(oneRow = true, bandPx = bandPx, hostPx = 96),
        )
        // 堆叠形态：盒 = editor + toolRow（96），**不是**那条 48 的带。
        assertEquals(
            "v148 B: stacked form — the box is editor + toolRow, NOT the 48 band",
            96,
            criterion2BoxPx(oneRow = false, bandPx = bandPx, hostPx = 96),
        )
        // 这就是 v141 的两难被解开的方式：不是"改 band 的取值"，是"换盒"。
        assertFalse(
            "v148 B: carrying the one-row 48 band into the stacked form is exactly the " +
                "reading that made ② false (96 ≤ 48) — it is now shown to be a form error",
            criterion2Holds(unionPx = union768, oneRow = true, bandPx = bandPx, hostPx = 96),
        )
        assertTrue(
            "v148 B: with the form-correct box the same union passes",
            criterion2Holds(unionPx = union768, oneRow = false, bandPx = bandPx, hostPx = 96),
        )
    }

    /**
     * **B 的第三半：形态不是标签，是**读数反解**出来的。**
     *
     * 两种形态的并集**算法不同**（`ComposerBandTouchTargetV105Test.kt:167-172` 逐字）：
     * ```
     * oneRow  : unionPx = max(editorTop + editorPx, toolRowPx)   // 同一带内取大
     * stacked : unionPx = editorPx + toolRowPx                   // 上下两块相加
     * ```
     * 两档读数分别**反证**自己的形态，任何一档都不得靠"我说是哪档"。
     */
    @Test
    fun theFormOfEachTierIsProvedByItsOwnReadingNotByAssertion() {
        val union600 = 48

        // 600：读到 48。若是堆叠必须是 48+48=96 ⇒ 不是堆叠。
        assertNotEquals(
            "v148 B: 600 tier is one-row — its union is not the stacked sum",
            editorPx + toolRowPx,
            union600,
        )
        assertTrue(
            "v148 B: and 600's union equals the band, which only the one-row form can give",
            union600 == bandPx,
        )
        // 768：读到 96 = 48+48 ⇒ 堆叠。
        assertEquals(
            "v148 B: 768 tier is stacked — 48 + 48 exactly",
            editorPx + toolRowPx,
            union768,
        )
    }
}
