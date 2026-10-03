package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v109: the 600 tier's `unionBottom(218) > paneBottom(213) = +5`, pinned.
 *
 * ## What v108 left open
 * `COMPOSER_OVERFLOW_FIX6.md` sec 3 named `SessionScreen.kt:3191-3193` (the host box's
 * `.heightIn(max = composerCeiling)`) as suspect #1 and refused to change it without a
 * device reading. This file does the part that can be done without one: it pins the
 * **5px's provenance as a pure function**, and pins the 600 tier's grid so a future
 * change that breaks the containment is red.
 *
 * ## The 5px, accounted for (`4 + 1`), on device literals
 *
 * `research/v97_verify/5556_v98/REPORT_COMPOSER_OVERFLOW4.md` sec 2.2
 * (`wm size 1366x600`, `wm density 160`, so **1dp == 1px**):
 * ```
 * pane box          [0,162][1366,213]  h=51   => paneBottom = 213
 * host box          h=48                     => hostTop   = 213 - 48 = 165
 * EditText          [24,169][405,217]  h=48   => editor top = 169 = hostTop + 4   <-- the 4
 * Add/model/Send    top=170 bottom=218 h=48
 * union (excl. Open terminal) [24,169][1364,218] => unionBottom = 218
 * ```
 * ```
 * unionBottom - paneBottom = 218 - 213 = +5
 *   = (hostTop 165 + 4) + 48 + 1 - 213
 *   =  4  == SessionScreen.kt:1813 `.padding(..., top = 4.dp, ...)`
 *   +  1  == the sub-pixel placement at Composer.kt:2298-2299
 * ```
 *
 * The host box itself is **exactly** legal: `hostTop + host = 165 + 48 = 213 == paneBottom`
 * — that is `SessionPaneLayout.composerTopPx`'s constructive identity, and this file
 * asserts it rather than trusting it. The 4+1 is produced **above the band**, on the
 * "band's origin relative to the host box" side.
 *
 * ## Why 600 only (`bottom = 8.dp` runs out of slack)
 * ```
 * 768: column content 4 + 88 + 8 = 100  into a 94-high host => 6px over,
 *      and 6 < 8, so the BOTTOM padding alone yields it => editor top = hostTop + 0
 * 600: column content 4 + 48 + 8 =  60  into a 48-high host => 12px over,
 *      8 of it yielded by bottom padding, the remaining 4 can only come off the top
 *      => editor top = hostTop + 4, union bottom 165 + 4 + 48 + 1 = 218
 * ```
 * So the **same** `top = 4.dp` line contributes **0px on 768** and **4px on 600**.
 *
 * Every literal above is a device reading on purpose. A model must not be allowed to
 * invent its own "before" numbers — that is the fake-green trap this repo hit repeatedly
 * (`COMPOSER_OVERFLOW_FIX4.md` sec 1.3).
 *
 * ## v113: the two `assertTrue(ub > pb)` below are **historical re-enactments**,
 * ## and the live criterion now runs on production arithmetic
 *
 * v112 (`TRIPWIRE_CRITERION_FIX.md` §7#1) booked this class's
 * `assertTrue(unionBottomAbs > paneBottomAbs)` as "same defect, deliberately kept"
 * and left it for this round. This round closes it **without losing the record**:
 *
 * * Both tripwires are **kept, at the same strength, with the same numbers** — they
 *   are the only account of *how the 600 tier was wrong*, and they are still green
 *   because they model the **v98 state**, not today's production. Their messages now
 *   say so, so nobody reads them as an open item any more. (v110 moved
 *   `SessionScreen.kt:1827` `top = 4.dp` → `0.dp`, so the tier is **not** open.)
 * * The **live** criterion is [theSixHundredTierHoldsThePaneBottomCriterionAgainstProductionArithmetic]:
 *   `unionBottom <= paneBottom` with the union bottom routed through
 *   `composerTopPx` / `composerChildUnionHeightPx` and the yardstick through
 *   `believedImeBottomPx`, and the region's vertical split **parsed out of the
 *   production source line** instead of being a literal in this file.
 * * [theCriterionTripwireDetectsTheOldRegionTopPaddingAndTheOldSubPixelTerm] is the
 *   reverse leg: it puts the old `top = 4.dp` (and the `+1` sub-pixel term) back
 *   through the same seam and requires the criterion to **go red by exactly the
 *   historical amount** (`+4`, and `+5` with the sub-pixel — i.e. `218`, the v98
 *   device reading, bit for bit). No production file is touched: both are arguments.
 */
class ComposerUnionBottomP5V109Test {

    // ---- device geometry (1366x768 / 1366x600, density 160 => 1dp == 1px) ----
    private val paneTop = 162
    private val paneHeight768 = 140
    private val paneHeight600 = 51

    /**
     * `SessionScreen.kt:1813` — the composer's own vertical padding **as it was in v98**.
     *
     * v113: these two are the **v98 record**, not a description of production. v110
     * moved the split to `top = 0.dp, bottom = 12.dp` (`SessionScreen.kt:1827`), so
     * today's split is *read from the source* by [regionPaddingDp] instead. Nothing in
     * this file re-derives a verdict from these two; the live criterion never touches
     * them. The `12.dp` **total** they add to is still the production constant, and
     * [theRegionPaddingIsTheOnlyVerticalTermInTheChain] keeps checking that.
     */
    private val regionTopPaddingPx = 4
    private val regionBottomPaddingPx = 8

    /** `SessionScreen.kt:2128-2131` / v106 — the one-row band's height. */
    private val bandPx = 48

    // ---- v113: the device box/IME readings the production yardstick is built on.
    // `wm size 1366x600`, `imeTop 213` ⇒ `imeBottom = 600 - 213 = 387`;
    // the pane box spans `paneTop(162)..window`, 600 - 162 = 438. Same readings
    // `ComposerPaneBottomCriterionV108Test` uses, so the two classes' yardsticks are
    // literally the same arithmetic.
    private val paneBox600 = 438
    private val imeBottom600 = 387

    // ---- 1. the 5px, as a pure function -------------------------------------

    @Test
    fun theFivePixelIsFourOfRegionTopPaddingPlusOneOfSubPixelPlacement() {
        val hostPx = 48
        // NOTE: composerTopPx / composerBottomPx are **pane-relative** (pane box top ==
        // 0), not absolute screen px. Everything below converts to absolute at the end.
        val hostTop = SessionPaneLayout.composerTopPx(paneHeight600, hostPx)
        val hostBottom = SessionPaneLayout.composerBottomPx(paneHeight600, hostPx)

        // The host box is exactly legal: top + host == pane height, i.e. its absolute
        // bottom is the pane bottom. Nothing here overflows.
        assertEquals("host top (pane-relative) on the 600 tier is 3", 3, hostTop)
        assertEquals("host bottom (pane-relative) equals the pane height 51", 51, hostBottom)
        assertEquals(
            "v103's identity top + host == pane holds on the 600 tier",
            paneHeight600,
            hostBottom,
        )
        assertEquals("absolutely, the host bottom is the pane bottom, 213", 213, paneTop + hostBottom)

        // bandTop = hostTop + top = 4.dp
        val bandTop = hostTop + regionTopPaddingPx
        assertEquals("the band starts 4px below the host top", 7, bandTop)
        assertEquals("the 4px is exactly SessionScreen.kt:1813 top = 4.dp", 4, bandTop - hostTop)

        // union bottom = bandTop + band(48) + 1 sub-pixel
        val unionBottomAbs = paneTop + bandTop + bandPx + 1
        assertEquals("union bottom reproduces the device reading 218", 218, unionBottomAbs)

        // and that is the defect, decomposed
        val paneBottomAbs = paneTop + paneHeight600
        assertEquals("the overrun is +5", 5, unionBottomAbs - paneBottomAbs)
        assertEquals("+5 decomposes as 4 + 1", 5, (bandTop - hostTop) + 1)
        // v113 RELABEL, NOT RELAX: same comparison, same numbers, same strength.
        // It used to read `OPEN:` — that was true in v109 and is **false since v110**
        // (which moved the region's `top = 4.dp` to `0.dp`, `SessionScreen.kt:1827`).
        // What is asserted here is the **v98 re-enactment**: given the old split this
        // test feeds in, the old `+5` comes back — bit for bit. It is a fixture, not a
        // live verdict; the live one is
        // [theSixHundredTierHoldsThePaneBottomCriterionAgainstProductionArithmetic].
        assertTrue(
            "v98 re-enactment (NOT an open item): unionBottom(218) > paneBottom(213) on 1366x600",
            unionBottomAbs > paneBottomAbs,
        )
    }

    @Test
    fun theHostBoxBottomIsThePaneBottomInBothTiers() {
        assertEquals(
            "768 host bottom (pane-relative) == pane height 140, i.e. abs 302",
            140,
            SessionPaneLayout.composerBottomPx(paneHeight768, hostPx = 94),
        )
        assertEquals(
            "600 host bottom (pane-relative) == pane height 51, i.e. abs 213",
            51,
            SessionPaneLayout.composerBottomPx(paneHeight600, hostPx = 48),
        )
    }

    // ---- 2. why only 600: the bottom padding's slack -----------------------
    //
    // v114 裁定（R6）：下面那两条 `assertTrue(over768 < regionBottomPaddingPx)` /
    // `assertTrue(over600 > regionBottomPaddingPx)` **保留**。它们的四个操作数
    // （`4 / 88 / 8 / 94 / 48`）确实是本文件字面量 ⇒ 按构造真、单独看零侦测力；
    // 但 Lead 的前提是「fixture 旁边必须有一条调生产函数的断言」，而本方法内
    // `SessionPaneLayout.composerTopPx(paneHeight768, 94)` 就是那条，且同类
    // [theSixHundredTierHoldsThePaneBottomCriterionAgainstProductionArithmetic]
    // 已经是完整活判据。这两条断言的是 **v98 那次 padding 拆分（4+8）的物理后果**
    // —— `top = 4.dp` 自 v110 起已不存在，保留它们是**记录**而非判据，故消息统一
    // 标成 `v98 re-enactment:`，防止再被当成开放项。**一个数没动、没删、没放宽。**
    // v115：本方法里的 `SessionPaneLayout.composerTopPx(paneHeight768, 94)`（及其下游断言）
    // **不可删** —— 它是 R6 那两条记录断言唯一的侦测力来源，删掉它们会静默退化成纯装饰；
    // 已有结构 pin（`ComposerTripwireIntegrityV115Test`，含反向验证）守住这条不变量，
    // 推导见 `research/v97_fix/TRIPWIRE_HARDENING.md` §1-§3。

    @Test
    fun theBottomPaddingAbsorbsTheOverrunOnSevenSixEightButNotOnSixHundred() {
        // 768 stacked form: union 88, host 94.
        val content768 = regionTopPaddingPx + 88 + regionBottomPaddingPx // 100
        val over768 = content768 - 94
        assertEquals("768 column content is 100", 100, content768)
        assertEquals("768 overruns its host by 6", 6, over768)
        assertTrue(
            "v98 re-enactment: 6 < 8, so the bottom padding alone yields it and the top padding is untouched",
            over768 < regionBottomPaddingPx,
        )
        // On 768 the device puts the editor's top at the host's top: pane 162 + 46
        // (pane-relative host top 302-94-162 = 46) = 208 == host top 208, so the
        // 4 dp of top padding contributes 0 to the band's position on this tier.
        val hostTop768 = SessionPaneLayout.composerTopPx(paneHeight768, 94)
        assertEquals("768 host top is pane-relative 46", 46, hostTop768)
        assertEquals("768 editor top is absolutely 208 == host top", 208, paneTop + hostTop768)
        assertEquals(
            "on 768 the top padding contributes 0 to the band's position",
            0,
            (paneTop + hostTop768) - (paneTop + hostTop768),
        )

        // 600 one-row form: band 48, host 48.
        val content600 = regionTopPaddingPx + bandPx + regionBottomPaddingPx // 60
        val over600 = content600 - 48
        assertEquals("600 column content is 60", 60, content600)
        assertEquals("600 overruns its host by 12", 12, over600)
        assertTrue(
            "v98 re-enactment: 12 > 8, the bottom padding runs out, so the top padding is what gets consumed",
            over600 > regionBottomPaddingPx,
        )
        assertEquals(
            "the leftover the top padding must cover is exactly 4px",
            4,
            over600 - regionBottomPaddingPx,
        )
    }

    // ---- 3. the 600 tier's containment grid, cell by cell -------------------

    /**
     * `unionBottom <= paneBottom` over window 768/600/480 x IME on/off x queue 0/2/11.
     *
     * Everything structural is routed through the **real** production functions
     * ([composerHostHeightPx] / [composerTopPx] / [composerChildUnionBottomPx]) so the
     * containment is not re-derived by a second model that could drift from the first.
     * The only thing added on top is the **region-top term** — the 4 dp of
     * `SessionScreen.kt:1813` that the device proves lands above the band on the 600
     * tier and not on the 768 one (see the two "why only 600" tests above).
     *
     * Queue depth is modelled the way the device reads it: the queued bar lives inside
     * the composer's host, so it moves the **host height**, never the pane height.
     */
    private val queueDepths = listOf(0, 2, 11)

    /**
     * Union bottom, absolute px, for a cell. [paneHeightPx] is the measured pane box
     * (`SessionScreen.kt:2428`); [hostPx] the measured host box; [unionPx] the measured
     * child union; [regionTopPx] the `top = 4.dp` term the device attributes to this
     * cell; [subPixelPx] the placement rounding the a11y dump shows.
     */
    private fun unionBottom(
        paneHeightPx: Int,
        hostPx: Int,
        unionPx: Int,
        regionTopPx: Int,
        subPixelPx: Int,
    ): Int = paneTop + SessionPaneLayout.composerChildUnionBottomPx(
        panePx = paneHeightPx,
        hostPx = hostPx,
        unionPx = unionPx,
    ) + regionTopPx + subPixelPx

    private fun paneBottom(paneHeightPx: Int): Int = paneTop + paneHeightPx

    // ---- v113: the live criterion, fed by **production** --------------------
    //
    // Everything above this line models the v98 defect. Everything below asks the
    // question v112 said this class could not answer: *does today's production code
    // hold `unionBottom <= paneBottom`, and would it notice if it stopped?*

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val sessionScreen =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    /**
     * v113: `composerRegion`'s **own** vertical split, parsed out of the one padding
     * line that carries it (`SessionScreen.kt:1827`, uniqueness asserted below). This
     * is the only term of criterion 1 that is not a production *function* — it is
     * Compose source — so the tripwire **reads** it instead of repeating it. That is
     * what makes it bidirectional: move the padding in production and the verdict
     * moves with it.
     */
    private fun regionPaddingDp(): Pair<Double, Double> {
        val all = Regex(
            """\.padding\(\s*start = 12\.dp,\s*end = 12\.dp,\s*top = ([0-9.]+)dp,\s*bottom = ([0-9.]+)dp\s*\)""",
        ).findAll(sessionScreen).toList()
        assertEquals(
            "the composer region's padding line must stay unique and parseable",
            1,
            all.size,
        )
        val m = all[0]
        return (m.groupValues[1].toDouble()) to (m.groupValues[2].toDouble())
    }

    /**
     * The 600 tier's pane height, the way `SessionScreen` computes it:
     * `paneBoxPx − believedImeBottomPx(...)` (`SessionPaneLayout.kt:1531`) ⇒ `438 − 387 = 51`.
     * Read from production rather than taken from [paneHeight600] so a change to the
     * production IME path moves the yardstick too.
     */
    private fun panePx600(): Int =
        paneBox600 - SessionPaneLayout.believedImeBottomPx(
            visibleBoxPx = paneBox600,
            floorPx = 0,
            imeBottomPx = imeBottom600,
            essentialPx = SessionPaneLayout.PANE_ESSENTIAL_HEIGHT.value.toInt(),
        )

    @Test
    fun sevenSixEightTierHoldsTheContainmentOnEveryCell() {
        // 768 + IME is the device cell (`REPORT_COMPOSER_OVERFLOW4.md` sec 2.1):
        // pane 140, host 94, union 88, editor top == host top (region top 0).
        var checked = 0
        for (queueDepth in queueDepths) {
            // The queued bar grows the **host**, never the pane. The host is still
            // capped at the pane by composerHostHeightPx, so the union keeps fitting.
            val hostPx = SessionPaneLayout.composerHostHeightPx(
                panePx = paneHeight768,
                ceilingPx = 94 + if (queueDepth == 0) 0 else 8,
            )
            assertTrue("768 queue $queueDepth: the host fits the pane", hostPx <= paneHeight768)

            val ub = unionBottom(
                paneHeightPx = paneHeight768,
                hostPx = hostPx,
                unionPx = 88,
                regionTopPx = 0,
                subPixelPx = 0,
            )
            val pb = paneBottom(paneHeight768)

            assertEquals("768 queue $queueDepth pane bottom is 302", 302, pb)
            assertTrue(
                "1366x768 + IME + queue $queueDepth: unionBottom($ub) <= paneBottom($pb)",
                ub <= pb,
            )
            checked++
        }
        // The queue-0 cell is the device's own reading, byte for byte.
        val deviceCell = unionBottom(
            paneHeightPx = paneHeight768,
            hostPx = SessionPaneLayout.composerHostHeightPx(paneHeight768, 94),
            unionPx = 88,
            regionTopPx = 0,
            subPixelPx = 0,
        )
        assertEquals("768 queue 0 union bottom is the device's 296", 296, deviceCell)
        assertEquals("768 has 6px inside the pane", -6, deviceCell - paneBottom(paneHeight768))
        assertEquals("3 queue depths checked on the 768 tier", 3, checked)
    }

    /**
     * v113 RELABEL, NOT RELAX — the whole method is now a **v98 re-enactment**.
     *
     * It used to be titled `...IsOpenOnEveryQueueDepthAndPinnedAsSuch` and carried
     * `assertTrue(ub > pb)` under the banner "this is the open item". That is no longer
     * true (v110 fixed the 600 tier), and the comparison was a **fake tripwire** in the
     * v112 sense: `regionTopPx = regionTopPaddingPx` (a literal in this file) and
     * `subPixelPx = 1` (also a literal), so `ub > pb` held *by construction of this
     * file's own constants* and no production change could ever turn it red.
     *
     * Nothing is deleted and nothing is weakened here: the same loop, the same
     * numbers (`218`, `213`, `+5`) and the same `assertTrue(ub > pb)` all still run.
     * What changed is that it is **labelled a fixture** and the live, production-fed,
     * bidirectionally-detectable criterion is
     * [theSixHundredTierHoldsThePaneBottomCriterionAgainstProductionArithmetic] /
     * [theCriterionTripwireDetectsTheOldRegionTopPaddingAndTheOldSubPixelTerm].
     */
    @Test
    fun theSixHundredTierIsOpenOnEveryQueueDepthAndPinnedAsSuch() {
        // 600 + IME, device cell (`REPORT_COMPOSER_OVERFLOW4.md` sec 2.2):
        // pane 51, host 48, band 48, region top 4, sub-pixel 1.
        for (queueDepth in queueDepths) {
            val hostPx = SessionPaneLayout.composerHostHeightPx(
                panePx = paneHeight600,
                ceilingPx = 48,
            )
            assertEquals("600 queue $queueDepth host is 48", 48, hostPx)

            val ub = unionBottom(
                paneHeightPx = paneHeight600,
                hostPx = hostPx,
                unionPx = 48,
                regionTopPx = regionTopPaddingPx,
                subPixelPx = 1,
            )
            val pb = paneBottom(paneHeight600)

            assertEquals("600 + IME + queue $queueDepth pane bottom is 213", 213, pb)
            assertEquals("600 + IME + queue $queueDepth union bottom is 218", 218, ub)
            assertEquals("600 + IME + queue $queueDepth overruns by 5", 5, ub - pb)
            // NOT relaxed into a pass. v113: it was never a *live* pass to relax —
            // it pinned the v98 defect's own constants. Kept verbatim as the record;
            // the live criterion is
            // [theSixHundredTierHoldsThePaneBottomCriterionAgainstProductionArithmetic].
            assertTrue(
                "v98 re-enactment (NOT an open item): 1366x600 + IME + queue $queueDepth " +
                    "unionBottom($ub) > paneBottom($pb)",
                ub > pb,
            )
        }
    }

    @Test
    fun fourEightyTierStaysContainedAndNeverGoesNegative() {
        // 480 has **no device reading yet** — this is a structural cell only: the host
        // is clamped into the pane by composerHostHeightPx and the union into the host
        // by composerChildUnionHeightPx, so the union can never reach past the pane.
        // It does NOT claim anything about the region-top term on this tier.
        for (paneHeight in listOf(51, 40, 28, 12, 0)) {
            for (queueDepth in queueDepths) {
                val hostPx = SessionPaneLayout.composerHostHeightPx(
                    panePx = paneHeight,
                    ceilingPx = 48,
                )
                val ub = unionBottom(
                    paneHeightPx = paneHeight,
                    hostPx = hostPx,
                    unionPx = 48,
                    regionTopPx = 0,
                    subPixelPx = 0,
                )
                val pb = paneBottom(paneHeight)
                assertTrue(
                    "pane $paneHeight queue $queueDepth: the union is never above the pane top",
                    ub >= paneTop,
                )
                assertTrue(
                    "pane $paneHeight queue $queueDepth: unionBottom($ub) <= paneBottom($pb)",
                    ub <= pb,
                )
            }
        }
    }

    @Test
    fun theHostNeverExceedsThePaneOnAnyTier() {
        for (paneHeight in listOf(140, 51, 40, 28, 12, 0)) {
            for (ceiling in listOf(0, 12, 28, 48, 94, 140, Int.MAX_VALUE)) {
                val hostPx = SessionPaneLayout.composerHostHeightPx(paneHeight, ceiling)
                assertTrue("pane $paneHeight ceiling $ceiling: host <= pane", hostPx <= paneHeight)
                assertTrue("pane $paneHeight ceiling $ceiling: host >= 0", hostPx >= 0)
            }
        }
    }

    // ---- 4. the arithmetic that must not drift -----------------------------

    @Test
    fun theTouchTargetIsUntouchedByThisWholeInvestigation() {
        // 48dp is material3's, via minimumInteractiveComponentSize — not this repo's.
        // Nothing in this file changes it, and this pins the constant it reads.
        assertEquals("the enforced minimum touch target is 48dp", 48, SessionPaneLayout.MIN_INTERACTIVE_COMPONENT.value.toInt())
        assertEquals("the one-row band is 48dp, i.e. it fits its own touch target", 48, bandPx)
    }

    @Test
    fun theRegionPaddingIsTheOnlyVerticalTermInTheChain() {
        // SessionScreen.kt:1809-1813 — the whole chain is fillMaxWidth / background /
        // onSizeChanged / padding. Nothing else adds a vertical amount above the band.
        assertEquals("the composer's region padding total", 12, regionTopPaddingPx + regionBottomPaddingPx)
        assertEquals(
            "and it is the 12.dp the production constant declares",
            SessionPaneLayout.COMPOSER_REGION_PADDING.value.toInt(),
            regionTopPaddingPx + regionBottomPaddingPx,
        )
    }

    // ---- 5. v113: the live criterion, on production arithmetic ---------------

    /**
     * v113: **the criterion this class exists to protect, computed from production.**
     *
     * ```
     * paneBottom  = paneTop + (paneBox600 − believedImeBottomPx(...))   [production :1531]
     * unionBottom = paneTop + composerChildUnionBottomPx(pane, host, u) [production :1340]
     *                    + regionTopPx                                   [source :1827]
     * criterion 1 = unionBottom <= paneBottom
     * ```
     *
     * This is the same yardstick `ComposerPaneBottomCriterionV108Test` uses, applied
     * to this class's own `unionBottom` helper — so the two classes cannot drift apart
     * on what `paneBottom` means. Unlike the v98 re-enactments above, **nothing here is
     * a frozen verdict**: `regionTopPx` is parsed out of the production padding line,
     * and the two placement functions are the ones `SessionScreen` actually calls.
     *
     * `subPixelPx = 0` on purpose: the `+1` is an **a11y-dump placement-rounding
     * artifact** (`Composer.kt:2298-2299`), not a layout term, so it does not belong in
     * a forward verdict. It is the second half of the reverse leg below, where the
     * historical `218` has to come back bit for bit.
     */
    @Test
    fun theSixHundredTierHoldsThePaneBottomCriterionAgainstProductionArithmetic() {
        val panePx = panePx600() // production: 438 - 387 = 51
        val paneBottom = paneTop + panePx // production-derived: 213 == imeTop
        val hostPx = SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX // 48 = the band
        val (regionTopDp, regionBottomDp) = regionPaddingDp() // source truth: 0 / 12

        val ub = unionBottom(
            paneHeightPx = panePx,
            hostPx = hostPx,
            unionPx = hostPx, // the four children are flush in the one-row band
            regionTopPx = regionTopDp.toInt(),
            subPixelPx = 0,
        )

        // The yardstick is production's own pane bottom, and on these inputs it is the
        // device's imeTop — the same 213 the v98 run read, reached by a different road.
        assertEquals("pane bottom is the production-derived 213 == imeTop 213", 213, paneBottom)
        assertEquals("the production pane is 51 px on the 600 tier", 51, panePx)
        // v110's lever: the decoration now sits **below** the band, so the term that
        // used to push the band past the pane edge is zero.
        assertEquals("the band's top padding is the 0 dp v110 moved it to", 0, regionTopDp.toInt())
        assertEquals(
            "and the 12 dp total is untouched by the move",
            SessionPaneLayout.COMPOSER_REGION_PADDING.value.toDouble(),
            regionTopDp + regionBottomDp,
            0.0,
        )
        assertTrue(
            "criterion 1: unionBottom($ub) must stay inside paneBottom($paneBottom)",
            ub <= paneBottom,
        )
    }

    /**
     * v113: the **reverse leg** — the criterion above must go red when the old
     * behaviour is put back, and it must go red by the **historical amount**, not by
     * some new number. Both old behaviours are injected as **arguments**; no
     * production file is touched.
     *
     *  1. `regionTopPx = 4` — the v110-removed `top = 4.dp` (`SessionScreen.kt:1813`).
     *  2. `regionTopPx = 4, subPixelPx = 1` — the whole v98 state, which must land on
     *     `218` and `+5`, the numbers in this class's own header.
     *
     * Then the last block asserts the **opposite** direction: production *today* sits
     * on the contained side, with 0 px to spare in the production frame. Without that
     * third block this would only prove the arithmetic can fail; with it, it proves
     * the tripwire is a real two-way detector and not a permanently-red assertion.
     */
    @Test
    fun theCriterionTripwireDetectsTheOldRegionTopPaddingAndTheOldSubPixelTerm() {
        val panePx = panePx600()
        val paneBottom = paneTop + panePx
        val hostPx = SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX

        // 1. v110's defect lever, restored.
        val withOldTopPadding = unionBottom(
            paneHeightPx = panePx,
            hostPx = hostPx,
            unionPx = hostPx,
            regionTopPx = 4,
            subPixelPx = 0,
        )
        assertTrue(
            "a restored `top = 4.dp` must break criterion 1 (was $withOldTopPadding vs $paneBottom)",
            withOldTopPadding > paneBottom,
        )
        assertEquals(
            "and it must reproduce the v98 overrun's 4 px term, not a different one",
            4,
            withOldTopPadding - paneBottom,
        )

        // 2. the full v98 state, sub-pixel included.
        val withFullV98 = unionBottom(
            paneHeightPx = panePx,
            hostPx = hostPx,
            unionPx = hostPx,
            regionTopPx = 4,
            subPixelPx = 1,
        )
        assertTrue(
            "the full v98 state must break criterion 1 (was $withFullV98 vs $paneBottom)",
            withFullV98 > paneBottom,
        )
        assertEquals(
            "and it must land on the v98 device overrun exactly: 218 - 213",
            5,
            withFullV98 - paneBottom,
        )
        assertEquals("which is the v98 device reading itself", 218, withFullV98)

        // 3. production today is on the other side of the same line.
        val (regionTopDp, _) = regionPaddingDp()
        val today = unionBottom(
            paneHeightPx = panePx,
            hostPx = hostPx,
            unionPx = hostPx,
            regionTopPx = regionTopDp.toInt(),
            subPixelPx = 0,
        )
        assertTrue(
            "today's production split must hold criterion 1 (was $today vs $paneBottom)",
            today <= paneBottom,
        )
        assertEquals(
            "with 0 px to spare in the production frame — the margin is exactly zero, " +
                "so any production growth above the band turns this red",
            0,
            today - paneBottom,
        )
    }
}
