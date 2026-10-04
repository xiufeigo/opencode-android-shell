package com.xiufeigo.opencodeshell.ui.session

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.session.drawer.MobileForm
import com.xiufeigo.opencodeshell.ui.session.header.HEADER_HEIGHT

/**
 * v51 P0 「消息区空白 + composer 裁切」: the vertical budget of the **mobile**
 * session pane — the header row, the view tab strip, the timeline viewport and
 * the floating composer that shares the timeline's `Box` (upstream's
 * `session-composer-region` floats over the timeline; `SessionScreen`'s
 * `timelinePane` anchors it `BottomCenter` and pushes the timeline's
 * `contentPadding` up by the measured `composerHeight`).
 *
 * The bug is that nothing in that stack had a budget. The mobile body column is
 * `chrome (fixed) + content viewport (weight 1f)`, and the composer is a
 * *wrap-content overlay inside* the viewport, so on a short window the viewport
 * measured smaller than the composer:
 *
 *  * the composer — measured with the viewport's height as its ceiling — had its
 *    trailing tool row (`Modifier.height(TOOL_ROW_HEIGHT)`) coerced down to a
 *    few dp: ⊞ / 模型 chip / Send collapsed into a sliver, Send became a ~26 px
 *    vertical bar and stopped being tappable, so only Enter could send
 *    (`research/v51_verify/tE/v51_07_typed`, `v51_09_sent`);
 *  * the timeline viewport, whose whole usable area is `viewport − composer −
 *    gap` because the composer covers it, went to **0 dp** — the messages were
 *    loaded (the server's `GET message` is complete, and a relaunch does not
 *    help) but nothing could be on screen: a blank body
 *    (`v51_12_msgs`, `v51_14_relaunch`). With the keyboard up at
 *    `wm size 640x1500` the same accounting took the viewport to 0 again and
 *    crushed the tool row onto the model chip (`tC/tC_55_narrow`, against
 *    `tC_56` with no keyboard).
 *
 * The fix follows the same shape as `ui/home/HomeRowLayout` (v43–v47): the
 * chrome is the **elastic** band and it gets a ceiling derived from the height
 * the window actually has, so the composer keeps its intrinsic height and the
 * timeline keeps a floor.
 *
 * v54 P0, the third pass at the same 1440x810 @420dpi window: the two repairs
 * above were correct and still changed nothing on screen, because the pane that
 * window actually leaves is **196 dp**, not the ~250 dp they were written
 * against, and because the v52 floor was counted against the viewport instead of
 * against the item band. On-device `layoutInfo` for the failing frame is in
 * [timelineContentBottom]; the two numbers it moved are [timelineTopPadding] (the
 * top step v52 forgot) and [chromeMaxHeight]'s floor.
 * v55 P0, the same window a fourth time — and the one that finally names what
 * the three repairs above were missing. The `u*.xml` a11y dumps of the v54 run
 * read the failing frame directly (1440x810 @420dpi, 2.625 px/dp):
 *
 * ```
 * session column   [0,299][1440,684]  = 146.7 dp
 *   chrome band    [0,299][1440,380]  =  30.9 dp   (header row only — the tab
 *                                                 strip is clipped away)
 *   timeline pane  [0,380][1440,684]  = 115.8 dp   ← the LazyColumn
 *     composer     [0,413][1440,684]  = 103.2 dp   ← opaque, BottomCenter
 * message nav ticks[1309,380][1435,538] = 48 x 60 dp (the rail, at the trailing
 *                                                 edge — not a squeezed row)
 * ```
 *
 * So the "126 px narrow strip" the v54 notes took for a crushed message row is
 * the message **nav rail** (`MessageNavRail`, trailing edge, `content-desc`
 * `V54CHECK_MSG_ONE`); the rows themselves measure `[42,389][1309,515]` and the
 * horizontal constraint was never broken. What *was* broken is the vertical
 * one, and it is not arithmetic:
 *
 *  * `contentPadding`'s bottom reserve is capped at `viewport − top −
 *    TIMELINE_MIN_CONTENT` = 115.8 − 4 − 64 = **47.8 dp**, while the composer
 *    that reserve exists to clear is **103.2 dp** tall;
 *  * the band the cap buys — `viewport − top − bottom` = 64 dp, starting 4 dp
 *    below the pane's top edge — therefore lies *entirely inside the
 *    composer's opaque rectangle*, which begins 13 px into the pane;
 *  * a positive band under an opaque overlay is exactly a blank body. Every
 *    number the v52–v54 repairs moved was moving the size of a band nobody can
 *    see.
 *
 * The floating overlay and the band are mutually exclusive: one of them has to
 * give way. [composerSharesViewport] is that decision, [visibleItemBand] is the
 * invariant it buys, and the screen honours both by laying the composer out
 * **beside** the timeline (below it, in the pane's column) when the overlay
 * would swallow the band.
 *
 */
internal object SessionPaneLayout {

    /**
     * The composer's floor: `Composer`'s editor (`EDITOR_MIN_HEIGHT` = 60 dp)
     * plus its tool row (`TOOL_ROW_HEIGHT` = 44 dp) plus the 4/8 dp padding of
     * the region column in `SessionScreen.composerRegion`.
     *
     * This is the share the pane hands over **first**: a window too short for
     * composer + chrome + timeline keeps the composer whole, because a composer
     * that lost its tool row cannot be submitted by touch at all.
     */
    val COMPOSER_MIN_HEIGHT: Dp = 116.dp

    /**
     * v105 P0: the **enforced minimum touch target** Compose's `Modifier.clickable`
     * carries — `minimumInteractiveComponentSize` reading material3's
     * `LocalMinimumInteractiveComponentSize`, whose default is **48 dp**. It is
     * material3 that forces it, not this repo: there is no `provides 48.dp`
     * anywhere in `app/src/main` (the only `LocalMinimumInteractiveComponentSize`
     * provider in the tree is the v104 `provides Dp.Unspecified`).
     *
     * Kept here (not in `Composer.kt`) so the composer's gate and the JVM truth
     * table read **one** number — see [composerBandTouchTargetPx].
     */
    const val MIN_INTERACTIVE_COMPONENT_PX: Int = 48

    /**
     * v105 P0: a composer's control floor — `SUBMIT_SIZE` = 28 dp, the size the
     * ⊞ / Send / chip draw at. Below it the frame cannot carry a whole control
     * (`SessionPaneImeBandV84Test` pins `>= 28 dp` of controls inside the `>= 28 dp`
     * the ceiling leaves), so [composerBandTouchTargetDp] returns `Dp.Unspecified`
     * there and the caller hands the local back to `Dp.Unspecified`.
     */
    const val CONTROL_FLOOR_PX: Int = 28

    /** v105: the same floor as a [Dp] — the gate in `Composer.kt` speaks Dp. */
    val CONTROL_FLOOR: Dp = CONTROL_FLOOR_PX.dp

    /** v105: material3's enforced 48 dp floor, as a [Dp]. */
    val MIN_INTERACTIVE_COMPONENT: Dp = MIN_INTERACTIVE_COMPONENT_PX.dp

    /**
     * v106 P0: **方案 A —— 让带本身装得下那个被强制的触摸靶**，唯一求值处。
     *
     * ```
     * band = max(toolRow, minInteractive)      (48dp)
     * ```
     *
     * **为什么方案 B 必然在真机失效**：v105 用
     * `CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 44dp)`
     * 把靶**封顶到带**，方向是**子节点向内收**。真机两档（`REPORT_COMPOSER_OVERFLOW3.md`
     * §1.1/§1.2）读到的 `EditText`/⊞/chip **仍是 48** ⇒ 那条子组合**没有**走
     * `minimumInteractiveComponentSize` 可覆盖的那条布局路径（`required*` / 自绘 /
     * 盒模型自带最小高），composition local 对它**无效**。**被忽略的不是"值算错了"，
     * 是"这条路径根本不看那个 local"。**
     *
     * **方案 A 为什么必然生效**：band 是**父给的约束**
     * （`Modifier.height(BAND_HEIGHT)` + measure policy 的 `maxHeight = band`）。
     * 子节点**不需要溢出**——它 48dp 的固有高在一条 48dp 的带里**装得下**
     * （`unionPx = max(editorTop + editor, toolRow) = 48 = band`）。
     * 这与"子节点是否理会某个 local"**无关**：父的约束是 Compose 的硬契约。
     *
     * **只在需要时增高**：`Composer.kt` 里 band 仍经 `minOf(BAND_HEIGHT, constraints.maxHeight)`
     * 收口 ⇒ pane 付不起 48 时自动退回收窄（即 v84/v86 已 pin 的裁剪区），
     * `COMPOSER_MINIMAL_HEIGHT` 的地板照旧兜底。**pin**：`ComposerBandTallEnoughV106Test`。
     */
    fun composerBandHeightDp(
        toolRow: Dp,
        minInteractive: Dp = MIN_INTERACTIVE_COMPONENT,
    ): Dp = maxOf(toolRow.value, minInteractive.value).dp

    /** v106: `composerBandHeightDp(44dp)` = **48 dp** —— 方案 A 的带高。 */
    val COMPOSER_BAND_HEIGHT: Dp = composerBandHeightDp(44.dp)

    /**
     * The timeline viewport's floor — one message line plus the bottom gap, so
     * the body is never blank and the last message can always scroll clear of
     * the composer. Applied *through* the chrome ceiling rather than as a
     * `heightIn(min = …)`: a floor alone cannot be honoured by a column that is
     * already over budget (the weighted viewport simply measures to 0).
     */
    val TIMELINE_MIN_HEIGHT: Dp = 72.dp

    /**
     * What the chrome may still keep once the composer and the timeline floor
     * have been reserved: the view tab strip (会话 / 变更 / 文件 / 终端), the
     * one control that has to stay reachable for the pane to be navigable. The
     * header row scrolls inside the remaining share rather than pushing the
     * composer out of the window.
     */
    val CHROME_MIN_HEIGHT: Dp = 44.dp

    /**
     * The ceiling for the chrome band (header row + tab strip) at a given pane
     * height — the post-inset height of the body column, i.e. with the soft
     * keyboard's `ime` inset already subtracted.
     *
     * Priority, strongest first: the composer's [COMPOSER_MIN_HEIGHT], then the
     * timeline's [TIMELINE_MIN_HEIGHT], then [CHROME_MIN_HEIGHT] of chrome, and
     * only what is left over is chrome. A pane too short even for the composer
     * gets a 0 dp ceiling — the composer then spans the pane and the tool row
     * stays at its intrinsic height (`Composer`'s editor is the weighted,
     * shrinking half).
     *
     * On a healthy window this is inert: the chrome measures ~82 dp against a
     * ceiling of hundreds of dp.
     *
     * v54 P0: the floor was [CHROME_MIN_HEIGHT] even on a pane that could not
     * afford it. Measured on 1440x810 @420dpi the pane is **196 dp** — composer
     * 112 + chrome 44 leaves the messages 40 dp, less than one row — so the
     * floor now gives way to [CHROME_COMPACT_HEIGHT] as soon as the pane cannot
     * pay for composer + chrome + a message band. The band scrolls, so a
     * compacted header row stays reachable; above that pane height the ceiling
     * is exactly what it was (154 dp at 342 dp, asserted by the v52 suite).
     */
    fun chromeMaxHeight(available: Dp): Dp =
        chromeMaxHeightForComposer(available, COMPOSER_MIN_HEIGHT)

    /**
     * [chromeMaxHeight] against the composer the pane actually has.
     *
     * v55 P0: the v51–v54 ceiling was derived from [COMPOSER_MIN_HEIGHT] alone,
     * which is the *widest* composer the pane can be asked for — so on the very
     * window the suite was written for it kept the band while the measured
     * composer stood 13 dp taller than the 64 dp it was keeping, and the band
     * it protected was under the composer's opaque background anyway. Passing
     * the measured height is what lets the yield below fire.
     *
     * On every pane the previous tests pin, the composer **is**
     * [COMPOSER_MIN_HEIGHT] and the numbers are unchanged.
     */
    fun chromeMaxHeightForComposer(available: Dp, composerHeight: Dp): Dp {
        if (available <= 0.dp) return 0.dp
        // v57 P0: the last band, and the only one derived without measuring
        // anything. A body shorter than chrome + one-row composer + a message
        // band (164 dp) cannot pay for a clipped header row, so the chrome goes
        // first — and because it goes *before* the composer is measured it
        // cannot feed back into it. (On the measured 1440x810 @420dpi window the
        // body is 146.7 dp, so this is the branch that runs there; every pane the
        // earlier suites pin is above the line and unchanged.)
        if (available < CHROME_MIN_HEIGHT + COMPOSER_MINIMAL_HEIGHT + TIMELINE_MIN_CONTENT) {
            return 0.dp
        }
        val composer = if (composerHeight > 0.dp) composerHeight else COMPOSER_MIN_HEIGHT
        val room = available - composer
        if (room <= 0.dp) return 0.dp
        // v55 P0: the last band. The chrome is the only band with nowhere to
        // go — the header row scrolls, but a ceiling that keeps a *partly*
        // visible row is worth nothing to anyone and costs the messages the
        // whole pane. So a pane that cannot pay the composer, the compact
        // chrome **and** [TIMELINE_MIN_CONTENT] drops the chrome altogether,
        // provided dropping it actually buys the compact chrome back (below
        // that the ceiling was already under the compact floor and every dp
        // taken is a dp the messages would not have got).
        if (room - TIMELINE_MIN_CONTENT < 0.dp && room >= CHROME_COMPACT_HEIGHT) return 0.dp
        val floor = if (
            available >= COMPOSER_MIN_HEIGHT + CHROME_MIN_HEIGHT + TIMELINE_MIN_CONTENT
        ) {
            CHROME_MIN_HEIGHT
        } else {
            CHROME_COMPACT_HEIGHT
        }
        return minOf(room, maxOf(room - TIMELINE_MIN_HEIGHT, floor))
    }

    /**
     * The floor a pane too short to pay for the full [CHROME_MIN_HEIGHT] falls
     * back to — the header row's action icons, half a row. It is the last dp
     * before the messages get nothing at all, so nothing may be claimed on top
     * of it: the budget stays a *ceiling*, and the band scrolls.
     */
    val CHROME_COMPACT_HEIGHT: Dp = 32.dp

    /**
     * The ceiling **one** chrome band may take when the pane mounts
     * [chromeBands] of them.
     *
     * v52 P0 「短窗消息区整片空白」: [chromeMaxHeight] derives a **total** for the
     * chrome, but the mobile pane mounts it **twice** — the view tab strip above
     * the content and, with「底部导航」on, the header row below it (both
     * `SessionScreen`'s `Column`s take the ceiling). A per-band ceiling therefore
     * handed the pane twice the budget it had derived, and on a 1440x810 @420dpi
     * window (≈342 dp, ≈250 dp of pane once the shell's bottom tab strip is off)
     * the two bands took 62 dp *each* and left the timeline nothing at all
     * (`research/v52_verify/tE/v52_05_msgs`, `v52_42_shortagain`, against
     * `v52_39_bigmsgs`).
     *
     * The budget is therefore split over the bands it actually has to cover.
     * Each band scrolls (`verticalScroll`), so a halved band stays reachable;
     * the timeline is what has to be whole. One band is unchanged.
     */
    fun chromeBandMaxHeight(available: Dp, chromeBands: Int): Dp =
        chromeBandMaxHeight(available, chromeMaxHeight(available), chromeBands)

    /** [chromeBandMaxHeight] against an already-derived ceiling (v55 P0). */
    fun chromeBandMaxHeight(available: Dp, chromeCeiling: Dp, chromeBands: Int): Dp {
        if (chromeBands <= 1) return chromeCeiling
        return chromeCeiling / chromeBands
    }

    /**
     * v78 P0 「短窗+键盘：整个会话头顶栏消失」: the chrome band the pane keeps
     * while the soft keyboard owns the body — 0, the header row (back / title /
     * search / more), or the whole band ([CHROME_FULL_HEIGHT], the header row
     * plus the view tab strip).
     *
     * A ceiling alone cannot say this: [chromeMaxHeightForComposer] yields the
     * chrome wholesale once the body drops under its own line, so the pane is
     * left with no back button, no session title and no 会话/变更/文件/终端
     * strip — a pane that cannot be navigated *from*. Measured on emulator-5554
     * at 720x1280 @480dpi (3.0 px/dp, a 240x426.7 dp window,
     * `research/v76_verify/5554/t1_density480_open2.png` against
     * `t1_density480_nokb.png`): the IME inset is 849 px (283 dp), the body the
     * pane is built on is ~114 dp, and the one-row composer measures ~54 dp.
     * That body is below the 164 dp line ([chromeMaxHeightForComposer] yields
     * the whole chrome) and below the ~180 dp line (the [chromeMaxHeight] half
     * yields it through the caller's `.coerceAtMost(chromeBudget)`), so the
     * ceiling is 0 dp and the session's own chrome — header row and tab strip —
     * measures to nothing. (The shell strip above them yields by design,
     * `ShellChromeBudget.yieldsToIme`.)
     *
     * The band is therefore floored, in the two steps the composer's
     * **controls** allow — every control in the tool row is 28 dp
     * ([COMPOSER_ROW_FLOOR]) and the region's 12 dp of padding
     * ([COMPOSER_REGION_PADDING]) is the decoration that yields first, because a
     * composer that lost its tool row cannot be submitted by touch at all (v51):
     *
     *  * the whole band while the body can pay it beside those controls
     *    ([CHROME_FULL_HEIGHT] + 28 + 12 = 124 dp);
     *  * the header row alone down to [HEADER_HEIGHT] + the same controls
     *    (88 dp) — the v79 line, the tab strip yielding first;
     *  * nothing below that, where the composer owns the pane whole.
     *
     * v79 P0 「短窗+键盘：头部阶梯全塌」: the first line used to be
     * [COMPOSER_MINIMAL_HEIGHT] — the composer's whole one-row **form**,
     * region padding included — and the window it was written for is 0.33 dp
     * under it, so it never fired where it was needed and both chrome rows
     * collapsed:
     *
     * ```
     * emulator-5554, 720x1280 @480dpi (3.0 px/dp, 240x426.67 dp), keyboard up:
     *   window            1280 px
     *   status bar/cutout  156 px =  52.0 dp   ← not the 24 dp the v78 note
     *                                            assumed
     *   ime                849 px = 283.0 dp
     *   body              (1280 − 156) − 849 = 275 px =  91.67 dp
     *   header + minimal composer            48 + 56 = 104 dp   ← 12.33 dp short
     * ```
     *
     * `dumpsys window displays` on that frame reads the three insets above, and
     * the a11y tree carries no back button, no session title and no view tabs at
     * all — the v78 floor's `body >= 104` was simply never true on the device it
     * was calibrated against.
     *
     * The line is therefore the composer's **controls** floor, not its form
     * floor: 88 dp is what that needs, and the measured 91.67 dp clears it —
     * the header keeps its whole row, the tab strip scrolls out of the band, and
     * the composer the pane then lays out (40 dp at the line, 43.67 dp on the
     * measured window) still holds every control it has: at the line the region
     * is capped to 40 dp, its padding takes 12 and the 28 dp controls are left
     * the whole card.
     *
     * v84 P0 「720x1280 @420dpi：会话页头与视图 tab 整条不渲染」: both rounds
     * above gated the floor on [ShellChromeBudget.isShortWindow] — the *display*
     * height — because the window they calibrated against (426.67 dp at
     * 480 dpi) was the short one, and v79 read the 420 dpi form of the same
     * 1280 px window as "simply not a *short window* (487 dp), so this ladder
     * never runs there at all". It runs there, and it is needed there:
     *
     * ```
     * emulator-5554, 720x1280 @420dpi (2.625 px/dp, 274.29 x 487.62 dp),
     * keyboard up:
     *   pane box (body column: top insets and the shell strip out)  411.43 dp
     *   ime                                                         283.0 dp
     *   body                                                        128.38 dp
     *   display height                                              487.62 dp
     *                                             ← 7.62 dp above the 480 dp line
     * ```
     *
     * 487.62 dp is above the short-window line, so the gate was false, the shell
     * strip does not yield (it reads the same display) and the ceiling is 0 dp —
     * while the body the keyboard really leaves is 128.38 dp, 4.38 dp *over* the
     * whole band the ladder can pay and far under the 164 dp the ceiling needs.
     * The result is the reported frame (`research/v84_verify/5554/29_small_sess.png`,
     * `32_420_again.png`, `35_420_scrolled.png`): no back chevron, no ☰, no
     * title, no search, no ⋮ and no view tabs — only the shell strip, the
     * composer and the keyboard, and no scroll that brings them back. The same
     * window at 320 dpi (a 640 dp display, a ~281 dp body) and at 480 dpi
     * (426.67 dp, 91.67 dp) shows both rows.
     *
     * The display height is a proxy for the room the pane has, and a density
     * breaks it: the window is 1280 **px** in all three configurations while the
     * status bar, the shell strip and the keyboard are laid out in dp, so the
     * body the pane is really built on swings by ~150 dp on one `wm density`.
     * The room the body has is the fact, and while the keyboard is up it is a
     * fact the platform corroborates ([believedImeBottomPx]). [imeVisible] is
     * therefore the gate's other half: a body shortened by the keyboard keeps
     * its chrome, and the no-keyboard bodies the earlier rounds pinned
     * (1440x810 @420dpi's 146.67 dp with chrome 0 and a 74 dp band — v57) are
     * untouched, because the band is never floored without a keyboard.
     *
     * What the band costs is the message area: at 420 dpi the pane the whole
     * band leaves is 44.38 dp, the shared composer takes it and the messages are
     * composed in a 0 dp viewport. That is the same trade v78/v79 accepted one
     * step down (「typing, not reading」, `SessionPaneImeBandsV81Test`), and it
     * lasts exactly as long as the keyboard does — dismissing it restores the
     * 411.43 dp body, the 231.43 dp chrome ceiling and the whole transcript.
     *
     * The caller applies the band per stop ([chromeBandHeaderShare] /
     * [chromeBandStripShare]): the header row and the tab strip share one column
     * by default and are two stops with「底部导航」on.
     */
    fun chromeImeBand(imeVisible: Boolean, body: Dp): Dp = when {
        !imeVisible -> 0.dp
        // 84 + 28 + 12: the whole band and the composer's controls.
        body >= CHROME_FULL_HEIGHT + COMPOSER_ROW_FLOOR + COMPOSER_REGION_PADDING ->
            CHROME_FULL_HEIGHT

        // 48 + 28 + 12: the header row and the composer's controls (v79).
        body >= HEADER_HEIGHT + COMPOSER_ROW_FLOOR + COMPOSER_REGION_PADDING ->
            HEADER_HEIGHT

        else -> 0.dp
    }

    /**
     * The header row's share of a [chromeImeBand] — what the stop that mounts the
     * row may claim: the row whole whenever the band is positive, and never more
     * than the band, so a band derived for a different stop can never open an
     * empty gap under it.
     */
    fun chromeBandHeaderShare(band: Dp): Dp = minOf(band, HEADER_HEIGHT)

    /**
     * The view tab strip's share of a [chromeImeBand] — nothing until the body
     * can pay the whole band ([chromeImeBand]'s first step). It is the elastic
     * half of the chrome: the strip is what gives way first when the keyboard
     * shortens the body.
     */
    fun chromeBandStripShare(band: Dp): Dp = (band - HEADER_HEIGHT).coerceAtLeast(0.dp)

    /**
     * The timeline's trailing content padding at a given viewport — how far the
     * messages have to clear the floating composer.
     *
     * v52 P0 kept this from summing past the viewport, but it counted the floor
     * as a share of the **viewport**, which is not what the list spends it on: the
     * `LazyColumn`'s own top step ([timelineTopPadding]) comes out of the same
     * box, so the band's real area was `viewport − top − bottom`, not
     * `viewport − bottom`.
     *
     * v54 P0 (device 5554, 1440x810 @420dpi, the on-device `layoutInfo` probe): with the pane
     * at 196 dp the budget produced `top 16 + bottom 112` inside a 152 dp
     * viewport — an item band of **24 dp**. `layoutInfo` then reported
     * `viewportStartOffset=-294 … viewportEndOffset=+105` (the bottom 40 dp),
     * `visibleItemsInfo` = `0:<idle>@0,0` and `1:<assistant>@63,256`: only the
     * *bottom 40 dp* of the newest row — its meta line — was inside the band, the
     * 256 dp row itself ran under the composer's opaque background, and every
     * older row was never composed at all. A session whose newest row starts
     * with padding or a tool meta chip therefore rendered as a literally empty
     * body (`research/v53_verify/tE/v53_05_msgs`, `v53_06_wait20`, against
     * `v53_25_big_msgs2`).
     *
     * The floor is therefore [TIMELINE_MIN_CONTENT] of **item** area, taken after
     * the top step, so `viewport > composer` always leaves a row's worth of
     * composable band instead of a sliver.
     */
    fun timelineContentBottom(viewport: Dp, composerHeight: Dp, gap: Dp): Dp {
        if (viewport <= 0.dp) return 0.dp
        val wanted = composerHeight + gap
        val allowed = (
            viewport - timelineTopPadding(viewport, composerHeight) - TIMELINE_MIN_CONTENT
            ).coerceAtLeast(0.dp)
        return wanted.coerceAtMost(allowed)
    }

    /**
     * The `LazyColumn`'s own top step, and the one number the v52 floor forgot.
     *
     * [TIMELINE_TOP_PADDING] is the healthy-window step; it is a *cosmetic* gap
     * above the oldest row, so when the composer already leaves less than
     * [TIMELINE_MIN_CONTENT] plus that step the band cannot also pay for it and
     * it drops to [TIMELINE_TOP_PADDING_COMPACT]. On a big window this is inert.
     */
    fun timelineTopPadding(viewport: Dp, composerHeight: Dp): Dp {
        if (viewport <= 0.dp) return 0.dp
        val room = viewport - composerHeight
        return if (room >= TIMELINE_MIN_CONTENT + TIMELINE_TOP_PADDING) {
            TIMELINE_TOP_PADDING
        } else {
            TIMELINE_TOP_PADDING_COMPACT
        }
    }

    /** The `LazyColumn` content padding's own top step (the healthy window's). */
    val TIMELINE_TOP_PADDING: Dp = 16.dp

    /** The short window's top step — the band is smaller than the gap. */
    val TIMELINE_TOP_PADDING_COMPACT: Dp = 4.dp

    /**
     * How much of the timeline viewport the messages keep whatever the composer
     * covers: one message line with its meta row — 「一行半」 — and never 0
     * ([timelineItemBand]).
     *
     * v54 P0: 40 dp was not enough. It was counted against the viewport instead
     * of the item band, so after the top step the list was left 24 dp, and a
     * `reverseLayout` list that composes into a band thinner than its own first
     * row shows only that row's trailing meta line under the composer.
     *
     * 64 dp is where it lands for the right reason: the reserve only yields down
     * to this floor, so on the measured pane the band is 64 dp instead of the
     * 24 dp that rendered as an empty body — and the price is bounded and
     * visible, the newest row's own tail (~16 dp, its meta line) sliding under
     * the composer, which is scrollable. On any pane that can afford it the
     * reserve is the full `composer + gap` and this floor never binds.
     */
    val TIMELINE_MIN_CONTENT: Dp = 64.dp

    /**
     * The area the `LazyColumn` is actually left to compose items in — the
     * invariant the short-window bug lived in, expressed once so both the budget
     * and the tests read the same number.
     *
     * `viewport > 0` ⇒ this is a real, positive band whenever the composer is not
     * taller than the viewport, i.e. at least one row is composed.
     */
    fun timelineItemBand(viewport: Dp, composerHeight: Dp, gap: Dp): Dp {
        if (viewport <= 0.dp) return 0.dp
        return viewport -
            timelineTopPadding(viewport, composerHeight) -
            timelineContentBottom(viewport, composerHeight, gap)
    }

    /**
     * The viewport's floor at a given pane height — the timeline share that
     * survives once the composer and the chrome's [chromeMaxHeight] are paid
     * for. Unlike [TIMELINE_MIN_HEIGHT] this one *gives way* (to 0 dp) instead
     * of pushing the composer out of the window, so it is what a caller may
     * apply unconditionally.
     */
    fun timelineMinHeight(available: Dp): Dp {
        if (available <= 0.dp) return 0.dp
        val viewport = available - chromeMaxHeight(available)
        return minOf(TIMELINE_MIN_HEIGHT, (viewport - COMPOSER_MIN_HEIGHT).coerceAtLeast(0.dp))
    }

    // ------------------------------------------------------------- v55 P0:
    // the composer is opaque, so a band it covers is not a band at all.

    /**
     * Whether the floating composer has to leave the timeline's viewport and
     * share the pane's column with it instead.
     *
     * The composer is a `BottomCenter` **overlay** with a solid
     * `background(c.bgBase)` (`SessionScreen.composerRegion`), anchored inside
     * the timeline's own `Box`. Upstream's desktop layout wants exactly that —
     * on a tall pane the transcript scrolls *under* the composer. On a pane
     * shorter than `composer + TIMELINE_MIN_CONTENT` there is no room above the
     * overlay, so the messages end up composed **inside** it: composed, laid
     * out, present in the a11y tree — and painted over. That is the v51–v54
     * 「消息区零渲染」, and no amount of moving the padding's numbers changes it.
     *
     * So the two arrangements are mutually exclusive and the pane picks one:
     *
     *  * `false` — the composer floats, and [timelineContentBottom] reserves
     *    its full height, which is what leaves [TIMELINE_MIN_CONTENT] of band
     *    **above** it (the band is `viewport − top − bottom`, i.e. the top of
     *    the pane; the composer starts at `viewport − composer`, and
     *    `composer ≥ top + TIMELINE_MIN_CONTENT` on every pane this accepts);
     *  * `true` — the composer is laid out **below** the timeline inside the
     *    pane's column, takes no part of the viewport, and every dp of that
     *    viewport is visible messages.
     *
     * The predicate is monotone under the switch (sharing only ever shrinks
     * the viewport), so it cannot oscillate.
     */
    fun composerSharesViewport(viewport: Dp, composerHeight: Dp): Boolean {
        if (composerHeight <= 0.dp) return false
        if (viewport <= composerHeight) return true
        return viewport - composerHeight < TIMELINE_MIN_CONTENT
    }

    /**
     * The trailing content padding when the composer shares the pane: the gap
     * below the last row, and nothing else — the composer is a sibling of the
     * list here, so reserving its height would push every row up out of a
     * viewport that is already only a row tall.
     *
     * v58 P0: it is the same [TIMELINE_BOTTOM_GAP] the floating form pays, not a
     * token one. 4 dp put the last line's descender 4 dp above the composer's
     * own rounded surface, which reads as「正文紧贴 composer」on the window
     * where the shared form is actually picked — 720x1280 @420dpi with the IME
     * up measures a 162.7 dp pane and a 58.1 dp composer, i.e. exactly the state
     * that shares (`research/v57_verify/5554/47_kb3.png`). The cost is 8 dp of
     * an already-short band; the alternative is a last row that looks pasted to
     * the composer.
     */
    val TIMELINE_BOTTOM_GAP_SHARED: Dp = 12.dp

    /**
     * The `LazyColumn`'s own top step while a pinned (sticky) row is mounted.
     *
     * The pinned row is an opaque overlay drawn **over** the list, so the inset
     * is not cosmetic: it is the only thing keeping the list's first pixels out
     * from under the bar. It therefore has to be the row's **measured** height,
     * and never less than what that row paints.
     *
     * A zero-height reading can only come from a first measure that has not run
     * (`Dp.Unspecified`/zero), in which case the caller falls back to the
     * constant; anything measured is taken as-is, over- or under the constant.
     */
    fun headerInset(measured: Dp): Dp =
        if (measured > 0.dp) measured else HEADER_HEIGHT

    /** The constant the caller starts from before the pinned row has measured. */
    val HEADER_INSET_FALLBACK: Dp = HEADER_HEIGHT

    /**
     * How much of the pane a reader can actually see of the messages, under
     * whichever arrangement [composerSharesViewport] picks. This is the
     * invariant the three previous passes kept re-deriving in the wrong place:
     * a band is only worth budgeting for if nothing opaque is painted over it.
     *
     * v74 P0: the floating branch returned the band's *size* without asking
     * whether the composer's opaque rectangle begins inside it, which it does
     * whenever the viewport's room is just under `top + TIMELINE_MIN_CONTENT`
     * (the reserve is clamped to `viewport − top − TIMELINE_MIN_CONTENT`, and
     * the compact top step is only 4 dp): the last row of the band then sits
     * under the composer — the reported「内容被切开、末条不可见」. The reading is
     * now capped at what the composer really leaves uncovered, so the number
     * means what its name says.
     */
    fun visibleItemBand(viewport: Dp, composerHeight: Dp, gap: Dp): Dp {
        if (viewport <= 0.dp) return 0.dp
        if (!composerSharesViewport(viewport, composerHeight)) {
            val uncovered = (
                viewport - composerHeight - timelineTopPadding(viewport, composerHeight)
                ).coerceAtLeast(0.dp)
            return minOf(timelineItemBand(viewport, composerHeight, gap), uncovered)
        }
        return (viewport - composerHeight - TIMELINE_TOP_PADDING_COMPACT - TIMELINE_BOTTOM_GAP_SHARED)
            .coerceAtLeast(0.dp)
    }

    /**
     * How far the empty state has to move off the pane's centre so it sits in
     * the **band** instead — the v74 P0 「空态被 composer 边缘切半」.
     *
     * The empty state is `Alignment.Center` of the pane. In the floating form
     * the composer is an opaque rectangle on the pane's bottom edge, so the
     * centre sits inside it as soon as the pane is shorter than twice the
     * composer: the reported frame showed the one-line 「Build anything」 cut by
     * that edge. The band's own centre is `top + band / 2` and the pane's is
     * `viewport / 2`, so the shift is `(top − bottom) / 2` — upwards, because
     * `bottom` is the composer's reserve.
     *
     * The shared form needs no shift: the composer is a sibling there, so the
     * timeline box the empty state is centred in **is** the band, and its centre
     * is already the band's.
     *
     * v86 P0: the v74 note also claimed that box "is never smaller than
     * [TIMELINE_MIN_CONTENT]" — an assumption about the *shared* form's box, not
     * about this offset, and one the 420 dpi frame falsifies: the box is
     * `pane − composer`, and the frame is 11.05 dp (see [emptyStateFits]). A shift
     * cannot help a box too short for the line — no offset centres a 20 dp label
     * in 11.05 dp — so "is it big enough to draw in" is its own decision
     * ([emptyStateFits]/[emptyStateRoom]) and this function stays what its name
     * says: an offset, 0 in the shared form at every box height.
     */
    fun emptyStateShift(shared: Boolean, viewport: Dp, composerHeight: Dp, gap: Dp): Dp {
        if (shared || viewport <= 0.dp) return 0.dp
        return (
            timelineTopPadding(viewport, composerHeight) -
                timelineContentBottom(viewport, composerHeight, gap)
            ) / 2
    }

    /**
     * The empty state's one line — `R.string.oc_timeline_empty` (13 sp) at the
     * default font scale, measured as a ~20 dp line box on the v74/v86 frames.
     *
     * It is the pane's only text height that is neither derived from the composer
     * nor scrollable: everything else in the vertical budget can be given way,
     * this label either fits whole or is not drawn at all ([emptyStateFits]). The
     * caller scales it by the configuration's `fontScale`, so a user on 2× type
     * needs 40 dp of box; 20 dp is what the constant pins at scale 1.
     */
    val EMPTY_STATE_LINE_HEIGHT: Dp = 20.dp

    /**
     * The room the empty state really has where [emptyStateShift] puts it — the
     * number v74's shared-form sentence assumed instead of measuring.
     *
     *  * `shared` — the composer is a **sibling**, not a cover, so the timeline
     *    box is entirely drawable and the room is the box itself: `viewport`,
     *    i.e. `pane − composer` as the arrangement measured it. Nothing floors
     *    it: the flip only asks that the composer *fits* the pane
     *    ([composerSharesViewport] takes any box down to 0), so this is the one
     *    band in the pane with no [TIMELINE_MIN_CONTENT] behind it — 11.05 dp on
     *    the 720x1280 @420dpi keyboard frame.
     *  * floating — the empty state is centred in the band, which the flip keeps
     *    at [TIMELINE_MIN_CONTENT] or more, and only the part of the box above the
     *    composer's opaque rectangle is drawable: the room is what that rectangle
     *    leaves after the list's own top step ([timelineTopPadding]).
     *
     * The list's own trailing gap is not subtracted in either form: it is the
     * `LazyColumn`'s content padding, inside the list, never the label's box.
     */
    fun emptyStateRoom(shared: Boolean, viewport: Dp, composerHeight: Dp): Dp {
        if (viewport <= 0.dp) return 0.dp
        if (shared) return viewport
        return (
            viewport - composerHeight - timelineTopPadding(viewport, composerHeight)
            ).coerceAtLeast(0.dp)
    }

    /**
     * Whether the empty state may be drawn at all (v86 P0 「720x1280 @420dpi 键盘
     * 弹起：空态文案只剩半截字，屏上读成『Build anvthing』」).
     *
     * On that frame the pane is 67.05 dp, the one-row composer takes
     * [COMPOSER_MINIMAL_HEIGHT] of it, and the shared arrangement's timeline box —
     * the box the label is `Alignment.Center` of — is 11.05 dp
     * (`research/v85_verify/5554/03_created.png`, per the v86 composer metering).
     * A 20 dp line centred in 11.05 dp hangs 4.475 dp past **both** ends: the top
     * slice leaves the pane and the bottom slice goes under the composer's opaque
     * card, so what the screen kept was the middle of the glyphs — the y's
     * descender read as "v", not as missing text. The empty state is a fallback
     * ("never a blank screen"), and a half line is worse than none of it: the
     * label is therefore drawn only when its whole line fits where it is
     * centred, and the empty box is the fallback below that — which is exactly
     * what the pane already showed on the 480 dpi and v84 frames, where the box
     * is 0 dp.
     *
     * The floating form always passes and is unchanged to the pixel: the flip
     * keeps `viewport − composer >= TIMELINE_MIN_CONTENT` (64 dp), so the room is
     * at least 48 dp once the top step is out. No visible string is added or
     * changed anywhere — the label is either rendered as it was or not composed.
     */
    fun emptyStateFits(
        shared: Boolean,
        viewport: Dp,
        composerHeight: Dp,
        lineHeight: Dp = EMPTY_STATE_LINE_HEIGHT,
    ): Boolean {
        if (viewport <= 0.dp) return false
        return emptyStateRoom(shared, viewport, composerHeight) >= lineHeight
    }

    /**
     * The composer's compact form: [Composer]'s editor drops from
     * `EDITOR_MIN_HEIGHT` (60 dp) to [EDITOR_MIN_HEIGHT_COMPACT] (40 dp). The
     * tool row keeps its own 44 dp — a compressed ⊞ / 模型 / Send row is the v51
     * bug, not the fix for it.
     *
     * The pane asks for it when it is too short to carry the full composer, the
     * chrome and a message row (224 dp): the measured short window leaves
     * 146.7 dp, where the compact composer buys 16 dp back for the messages.
     */
    val COMPOSER_COMPACT_HEIGHT: Dp = 96.dp

    /** `EDITOR_MIN_HEIGHT` while the pane is short — see [COMPOSER_COMPACT_HEIGHT]. */
    val COMPOSER_EDITOR_COMPACT_HEIGHT: Dp = 40.dp

    /** Whether a pane of this height can only afford the compact composer. */
    fun composerCompact(available: Dp): Boolean =
        available > 0.dp && available < COMPOSER_MIN_HEIGHT + CHROME_MIN_HEIGHT + TIMELINE_MIN_CONTENT

    // ------------------------------------------------------------- v57 P0:
    // the composer took 96 dp of the measured 146.7 dp pane.

    /**
     * The one-row composer: the editor and the tool controls share a single
     * [COMPOSER_ROW_HEIGHT] row, so the composer's floor is the band plus the 12 dp
     * of `SessionScreen.composerRegion` padding instead of 96.
     *
     * v106 P0（**方案 A 的代价记账**）: 原来是字面量 `56.dp`（= 44dp 带 + 12dp padding）。
     * 一行 composer 的卡高被方案 A 抬到 48dp（[composerBandHeightDp]），**这份地板必须
     * 跟着抬到 60dp**，否则 pane 会按 56 记账、按 60 摆放 —— **那 4dp 就是本轮
     * 一直在根治的"pin 的量与验收的量不同源"**。改成**由带推导**，两者不可能再漂。
     *
     * 后果是诚实的：**pane 预算少 4dp**。让位由既有机制承担，一处新机制都没有加：
     * [safeViewportFloorPx]（v100）、[composerHostCeilingPx]（v100）、
     * [queueBarCeiling]（v99）。三格的连带预演见
     * `research/v97_fix/COMPOSER_OVERFLOW_FIX4.md` §3 与
     * `ComposerBandTallEnoughV106Test.theThreeCellBudgetPreviewKeepsThe768Floor`。
     *
     * v57 P0 measured on 1440x810 @420dpi, 2.625 px/dp (`research/v57_verify`),
     * from an on-device `onSizeChanged` probe (`V57PROBE`/`V57BUDGET`):
     *
     * ```
     * maxHeight (the whole session body)  146.67 dp
     * chrome ceiling                       30.67 dp
     * timeline pane (= maxHeight − chrome) 146.67 dp   [chrome yields to 0]
     * composer                             96.00 dp
     * timeline viewport                    50.67 dp
     * item band = viewport − 4 − 4         42.67 dp
     * ```
     *
     * So the band was positive and the composer still took 65 % of the pane:
     * one and a half message lines, and with the IME up (`maxHeight` 33.9 dp)
     * nothing at all — the editor's own `heightIn(min = 40)` overflowed the
     * pane, the column clipped the **editor** away and the "composer" the user
     * saw was the trailing sliver of the tool row.
     *
     * 56 dp buys the measured window 40 dp more messages (a 90.67 dp viewport,
     * an 82.67 dp band — three lines) and, more importantly, a composer whose
     * floor is *below* every pane the shell ever hands it, so the tool row can
     * no longer be the thing that gets clipped.
     */
    // v106（方案 A）: **保持 56.dp 不动**。这不是"忘了记代价"，是记代价的位置问题：
    // 方案 A 的 +4dp 全部落在 **band / 宿主盒**上（44 → 48，见
    // [composerBandHeightDp]），而本地板是 **v57 起按 44dp 行 + 12dp padding 立的账**，
    // 被 v74/v79/v84/v86/v99/v102 的**真机实测阶梯**逐个钉死（420dpi 的 56dp、
    // 短窗线 164dp、pane=140 的 `readable 84 = 140 − 56`）。
    //
    // 把地板抬到 60dp 会移动那 10 处**设备实测**读数，而本轮**没有真机**可用来重测它们
    // —— 在 JVM 上凭改动作��重定位设备读数，正是本仓连续三轮栽跟头的那件事
    // （"pin 的量必须与验收的量同源"）。故：**地板不动，代价记在 band 上。**
    //
    // **由此产生的已知缺口（本轮未闭环，见 FIX4 报告 §7 #1）**：
    // 一行 composer 的真实高是 `48 + 12 = 60dp`，比本地板**多 4dp**。该 4dp
    // 在 pane 装不下的那一格由两处既有 clamp 吸收，**不会溢出**：
    // [composerHostHeightPx] 的 `coerceAtMost(pane)` 与 `Composer.kt` 的
    // `minOf(BAND_HEIGHT, constraints.maxHeight)`。是否要把整条 v57–v84 阶梯
    // 在真机上重测后整体重定位，**交 Lead 裁定**（不在本轮偷偷改）。
    val COMPOSER_MINIMAL_HEIGHT: Dp = 56.dp

    /** The single row the minimal composer lays out (the tool row's own height). */
    val COMPOSER_ROW_HEIGHT: Dp = 44.dp

    /**
     * v79 P0: the composer's **controls** floor — the least its tool row can be
     * laid out in without clipping a control.
     *
     * Every control that row holds is 28 dp: the `⊞` attach menu
     * (`Composer`'s `AddMenuButton`), the model chip and `SUBMIT_SIZE`'s Send
     * button are all `size(28.dp)`, and the row's own 44 dp
     * ([COMPOSER_ROW_HEIGHT]) is the upstream `h-11` it is drawn at, not a
     * content bound. So a card that keeps 28 dp keeps every control whole, which
     * is the v51 invariant ("a composer that lost its tool row cannot be
     * submitted by touch at all") in the terms the row is actually made of.
     *
     * It is what [chromeImeBand] may claim the header on top of, and it is why
     * the header can be kept 16 dp lower than the whole one-row form
     * ([COMPOSER_MINIMAL_HEIGHT]) asks for: that 16 dp is
     * [COMPOSER_ROW_HEIGHT] minus this, the row's own slack above its controls —
     * the region's [COMPOSER_REGION_PADDING] is still inside the composer the
     * pane lays out behind the line.
     */
    val COMPOSER_ROW_FLOOR: Dp = 28.dp

    /**
     * v81 P2: the editor's share of the one-row form's weighted split — the
     * editor takes this weight against the tool controls' `1f`, i.e. just
     * under a third of the row past the fixed `⊞` / gaps / Send, the controls
     * the rest.
     *
     * The 50/50 the v57 form used leaves the chip ~76 dp on a 240 dp window
     * (720x1280 @480dpi: card 216 − `⊞` 28 − gaps 8 − Send 28 = 152), so a
     * `GLM-5.2 · opencode-go` label ellipsizes to `ope...` with the chevron —
     * the one hint the chip opens the picker — barely inside the row
     * (`research/v80_verify/5554/16_480_session.png`). The 1:2 split (weight
     * 0.5) repaired that for lowercase labels (~101 dp: icon + ~8 lowercase
     * chars + chevron) but a caps-heavy model name still lost one char too
     * many — `GPT-6.1 Sol` measured `GPT-6....`, five chars, on the same
     * window (`research/v81_verify/5554/s18_chip_crop.png`), because
     * caps/figures run ~8 dp per char at 13 sp against the 6.6 dp lowercase
     * ruler. 0.45 moves ~3.5 dp from the editor to the controls: ~105 dp
     * there (a ~57 dp label fits six caps chars + the ellipsis + the chevron
     * beside the chip's own
     * [com.xiufeigo.opencodeshell.ui.session.composer.MODEL_CHIP_MIN_WIDTH])
     * while the editor keeps ~47 dp — still past the 44 dp tap floor, and the
     * field is one line whose text, unlike the chip's label, is elastic. On a
     * 274 dp window (420dpi) the same split is ~58 / ~129, so a long label
     * fits whole. The stacked form is untouched: there the editor is the
     * row's only weighted child and takes the whole card whatever this value
     * is.
     */
    val COMPOSER_ONE_ROW_EDITOR_WEIGHT: Float = 0.45f

    // --------------------------------------------------------------- v86 P0:
    // the one-row band's editor floor

    /**
     * v86 P0 「720x1280 键盘弹起：一行 composer 里输入框只剩 39 / 25 dp — 用户看
     * 不到自己打的字」.
     *
     * The one-row band's split used to be the ratio alone
     * ([COMPOSER_ONE_ROW_EDITOR_WEIGHT] against the controls' `1f`), and on the two
     * 720 px windows with the keyboard up the editor's share is smaller than the
     * field's own horizontal padding plus a word. Measured on `emulator-5554`
     * (`research/v85_verify/5554`, 2.625 px/dp at 420 dpi, 3.0 px/dp at 480 dpi;
     * the a11y bounds are the *touch-inflated* ones, so the text band is read as
     * `share − the field's padding`):
     *
     * ```
     * 720x1280 @420dpi   window 274.29 dp   card 250.29 dp   leftover 186.29
     *   editor 0.45/1.45 × 186.29 = 57.81 dp   text band 41.81 dp
     *   a11y EditText [55,396][157,522] = 102 px = 38.9 dp wide, and the field is
     *   scrolled to the caret, so the draft 「E2ECHKzzE2ECHK5554 hi」 shows as
     *   two characters (`22_step2_ime_up_420_typed.png`)
     * 720x1280 @480dpi   window 240 dp      card 216 dp      leftover 152
     *   editor 0.45/1.45 × 152 = 47.17 dp      text band 31.17 dp
     *   a11y placeholder [72,148] = 76 px = 25.3 dp → 「As…」
     *   (`40_480_ime_up.png`)
     * ```
     *
     * The form-flip answer — the stacked form, the tool row on its own row below
     * the editor — cannot be laid out on either window: the composer already owns
     * the whole pane, and the stacked form is one 44 dp tool row *plus* a text
     * line:
     *
     * ```
     * @420dpi kb up: pane box [232,534] = 115.05 dp, chrome band 48 (115 < 124)
     *   pane 67.05 → composer ceiling 56 → card 44 dp   (measured [401,515] = 44)
     * @480dpi kb up: pane box [156,426] =  90.00 dp, chrome band 48 ( 90 ≥  88)
     *   pane 42.00 → composer ceiling 42 → card 30 dp   (measured [312,406] = 31.3)
     * stacked card = tool row 44 + editor ≥ 20 = ≥ 64 dp → ≈ 20 / ≈ 33 dp short
     * ```
     *
     * So the band keeps its one row and splits with a **floor on each side**:
     *
     *  * the editor — [COMPOSER_ONE_ROW_EDITOR_FLOOR]: its own
     *    [COMPOSER_ONE_ROW_EDITOR_PADDING] plus [COMPOSER_ONE_ROW_EDITOR_TEXT_FLOOR]
     *    of text (≈7 latin or ≈3.7 CJK characters at 13 sp). 64 dp is exactly what
     *    the narrowest window this form runs on can pay beside the chip's floor:
     *    `240 (window) − 24 (the region's padding) − 64 (the fixed children) − 88
     *    (the chip) = 64`.
     *  * the controls — [COMPOSER_ONE_ROW_CONTROLS_FLOOR] = the chip's own v81
     *    floor (`ui.session.composer.MODEL_CHIP_MIN_WIDTH`): 48 dp of chip chrome
     *    plus ~40 dp of label, about six 13 sp characters. The chip is the side that
     *    yields, which is the
     *    trade the v81 note already argued — the editor is the surface the user is
     *    *writing on*, and the chip's label repeats what the picker (and the chip's
     *    own `contentDescription`) already names.
     *
     * What the reader gets out of it (`Row`'s own px arithmetic, the same read-back
     * as v81 — the share is still measured, never assumed):
     *
     * ```
     * window   card    leftover   v81 editor/text      v86 editor/text
     * 274.29   250.29   186.29    57.81 / 41.81        64.00 / 48.00
     * 240      216      152       47.17 / 31.17        64.00 / 48.00
     * ```
     */
    fun composerOneRowEditorWeight(bandWidth: Dp): Float {
        // The band the row's own weights divide: what is left of the tool row's
        // available width after its fixed children — the `⊞`, the two 4 dp gaps and
        // Send. Everything below is measured in dp and returns a *ratio*, so the
        // density cancels; the row still does the px division.
        val space = bandWidth - COMPOSER_ONE_ROW_FIXED_WIDTH
        if (space <= 0.dp) return COMPOSER_ONE_ROW_EDITOR_WEIGHT
        val ratio = COMPOSER_ONE_ROW_EDITOR_WEIGHT
        val ratioShare = space * ratio / (ratio + 1f)
        // The editor's floor, bounded by what the chip's floor leaves for it: on a
        // band that cannot pay both, the chip's floor wins and the editor takes the
        // rest (which is always more than the ratio would have given it).
        val floored = minOf(
            COMPOSER_ONE_ROW_EDITOR_FLOOR,
            (space - COMPOSER_ONE_ROW_CONTROLS_FLOOR).coerceAtLeast(0.dp),
        )
        // Where the ratio already pays the floor the split is v81's, bit for bit —
        // the same `Float`, so every window v81 measured keeps its pixel.
        if (floored <= ratioShare) return ratio
        // Otherwise the weight that lands the editor on the floor:
        // `space × w / (w + 1) = floored`. `floored < space` by construction (the
        // floor is at most `space − the chip's floor` or at most 64 dp, and the
        // branch above already covered `floored <= ratioShare < space`).
        return floored.value / (space - floored).value
    }

    /**
     * The one-row band's **fixed** children — the `⊞` attach menu
     * (`AddMenuButton`'s 28 dp), the two 4 dp gaps and Send
     * (`SUBMIT_SIZE` 28 dp). v86 needs them to know the weight space
     * [composerOneRowEditorWeight] divides; the *share* still comes out of `Row`'s
     * own weight arithmetic (`ComposerArrangement`), never out of this constant.
     */
    val COMPOSER_ONE_ROW_FIXED_WIDTH: Dp = 64.dp

    /**
     * The one-row field's own horizontal padding inside its share — the `start =
     * 12.dp` and `end = 4.dp` of the editor's chain in the minimal form. What the
     * user can read is the share minus this.
     */
    val COMPOSER_ONE_ROW_EDITOR_PADDING: Dp = 16.dp

    /**
     * The text the one-row editor may never be squeezed below: 48 dp at 13 sp is
     * about seven latin or three and a half CJK characters — a caret, the word
     * being typed and the one before it. It is the smallest band on which a draft
     * is *readable* rather than merely editable.
     */
    val COMPOSER_ONE_ROW_EDITOR_TEXT_FLOOR: Dp = 48.dp

    /**
     * The editor's share floor: [COMPOSER_ONE_ROW_EDITOR_PADDING] +
     * [COMPOSER_ONE_ROW_EDITOR_TEXT_FLOOR] — the bound `Row` gives the field, so
     * the field's text band is never under the text floor.
     */
    val COMPOSER_ONE_ROW_EDITOR_FLOOR: Dp =
        COMPOSER_ONE_ROW_EDITOR_PADDING + COMPOSER_ONE_ROW_EDITOR_TEXT_FLOOR

    /**
     * The controls' floor inside the band — the chip's own v81 floor
     * ([com.xiufeigo.opencodeshell.ui.session.composer.MODEL_CHIP_MIN_WIDTH], which
     * `ComposerOneRowChipReserveV81Test` pins at 88 dp; kept as a number here so this
     * file does not depend on the composer package). Below it the chip's label is
     * crushed to an ellipsis and then to nothing, so it is the last dp the editor may
     * take from that side.
     */
    val COMPOSER_ONE_ROW_CONTROLS_FLOOR: Dp = 88.dp

    /**
     * Whether a pane of this height can only afford the one-row composer: it
     * cannot carry the [COMPOSER_MINIMAL_HEIGHT] composer, the chrome band and
     * [TIMELINE_MIN_CONTENT] of messages at the same time (164 dp).
     *
     * Monotone in [available] and disjoint from nothing — [composerCompact] is
     * still true here, because the minimal form *is* the compact form with the
     * editor's floor removed.
     *
     * **v154: the lower guard is `>= 0.dp`, not `> 0.dp`.** The predicate above
     * is one question — *can this room carry the stacked form?* — and the room
     * that can least afford it is the one with nothing in it. `> 0.dp` answered a
     * second, different question (*is there any room at all?*) with "no, so do not
     * pick a form", which made the predicate **non-monotone**: at exactly 0 dp it
     * turned the one-row form *off* and let the shell ask the full 116 dp
     * [COMPOSER_COMPACT_HEIGHT] composer to fit in nothing.
     *
     * That is the B-2 reverse jump, measured on the 600 tier (`paneBox600 = 438`):
     *
     * ```
     * ime 387 -> body  51, one row      (the measured reading)
     * ime 426 -> body  12, one row
     * ime 438 -> body   0, FULL FORM    <- keyboard taller, asking for 116 dp
     * ```
     *
     * The room is monotone non-increasing in the keyboard (a taller keyboard can
     * only take room away), so the form has to be monotone too — otherwise the
     * two disagree about which way is 「tighter」. `>= 0.dp` makes the truth set
     * an initial segment of `[0, 164)` — `{0 .. 163}` — which is what
     * 「monotone in [available]」 above always claimed and never was. The
     * *staircase* is untouched: 163 dp is one row and 164 dp is the stacked form,
     * exactly as v57 and v74 pin it.
     *
     * [composerCeiling] reads this too, and is unaffected: it returns on
     * `pane <= 0.dp` before asking, so no ceiling moves.
     */
    fun composerMinimal(available: Dp): Boolean =
        available >= 0.dp &&
            available < COMPOSER_MINIMAL_HEIGHT + CHROME_MIN_HEIGHT + TIMELINE_MIN_CONTENT

    /**
     * Whether the composer takes its **one-row** form on a body of [body] — the
     * v74 P0 「短窗消息列表塌成细条」.
     *
     * The form used to be the *display* height alone (a
     * `ShellChromeBudget.isShortWindow` of `LocalConfiguration.screenHeightDp`),
     * which is not a fact about the room the composer has. On emulator-5554 at
     * 720x1280 @420dpi — the window the v58 acceptance measured — the display is
     * 487 dp, i.e. **above** the 480 dp threshold, while the session body with
     * the keyboard up is 162.7 dp: 13 dp short of the 164 dp the stacked
     * composer, the chrome band and a message row need together. The composer
     * therefore kept its stacked form (96–116 dp) on a 162.7 dp pane, and
     * [composerSharesViewport] then picked the shared arrangement — leaving the
     * timeline `pane − composer` ≈ 61 dp, the reported「细条」, with the newest
     * row under the composer and the empty state cut in half by its edge.
     *
     * [body] is the **post-inset** body height (`BoxWithConstraints.maxHeight`
     * inside the body column), never the measured pane: the pane is
     * `body − chrome` and the chrome is derived from the composer, so reading it
     * would let the form argue with itself (the v57 oscillation, and the
     * bistability of the two fixed points). The body exists before any composer
     * does.
     *
     * The window term stays: a genuinely short *window* (the v57 measurement,
     * 308 dp) takes the one-row form whatever the keyboard does, so that
     * window's behaviour is unchanged.
     */
    fun composerOneRow(windowIsShort: Boolean, body: Dp): Boolean =
        windowIsShort || composerMinimal(body)

    /**
     * The ceiling the shared composer may take out of the pane — the band is
     * what it protects, so on a pane too short for
     * [COMPOSER_MINIMAL_HEIGHT] the composer keeps only the one row it needs
     * and the messages get the rest.
     *
     * This is what v55's `coerceAtLeast(COMPOSER_COMPACT_HEIGHT)` got wrong: the
     * 96 dp floor is the *stacked* composer's intrinsic height, and on a pane
     * that cannot pay for it the floor is exactly what starved the messages.
     */
    fun composerCeiling(pane: Dp): Dp {
        if (pane <= 0.dp) return 0.dp
        val floor = if (composerMinimal(pane)) COMPOSER_MINIMAL_HEIGHT else COMPOSER_COMPACT_HEIGHT
        return (pane - TIMELINE_MIN_CONTENT).coerceAtLeast(floor).coerceAtMost(pane)
    }

    // --------------------------------------------------------------- v76 P0:
    // the two arrangements as **arithmetic on one node tree**

    /**
     * v76 P0: the viewport's height under either arrangement, in px — the
     * measured value the pane's `Layout` hands its first child.
     *
     * The two arrangements used to be two different *parents* for the composer
     * (a `Box` inside the viewport in the floating form, a sibling `Box` after
     * it in the shared one), and the flip between them moved the composer's
     * whole node subtree — the `movableContentOf` slot's old anchor and its new
     * anchor in the same frame, which is what crashed on device
     * (`CopyNodesToNewAnchorLocation` → "Cannot insert LayoutNode because it
     * already has a parent"). The arrangement is now a matter of this
     * arithmetic alone: the children, and therefore their parents, are the same
     * nodes in both forms — only the constraints they are measured with and the
     * place they are put at change.
     *
     * The floating form gives the viewport the whole pane (the composer is an
     * opaque cover over it), the shared form takes the composer out of it.
     */
    fun viewportHeightPx(shared: Boolean, pane: Int, composer: Int): Int =
        if (!shared) pane else (pane - composer).coerceAtLeast(0)

    /**
     * v100 P0: the same subtraction, with the timeline's floor made a **hard**
     * number instead of a hope.
     *
     * [viewportHeightPx] floors the remainder at **0**, so the viewport is a
     * *remainder*, not a *minimum*: whenever the composer host measures taller
     * than `pane`, the whole deficit lands on the timeline and the messages get
     * whatever is left — which is how the device read **16 px** in a window whose
     * other frame read 140 px
     * (`research/v97_verify/5556_v98/REPORT_U1B.md` §4.1). [TIMELINE_FLOOR_UNDER_QUEUE]
     * was never in this equation at all.
     *
     * `floorPx` is a parameter, not a constant, for two reasons: the default
     * `0` keeps every existing pin byte-identical to what it saw, and the
     * call site supplies the floor the pane can actually afford
     * ([timelineViewportFloor]).
     *
     * The floor is applied with `coerceAtMost(pane)` as well, so a pane smaller
     * than the floor still lays out exactly one pane (no overflow) — the band is
     * protected down to the point where the pane itself runs out, and below that
     * the honest reading is 「this pane cannot show both」 rather than a
     * 0-height viewport.
     */
    fun viewportHeightPx(
        shared: Boolean,
        pane: Int,
        composer: Int,
        floorPx: Int,
    ): Int = if (!shared) {
        pane
    } else {
        (pane - composer).coerceAtLeast(floorPx).coerceAtMost(pane)
    }

    /**
     * v100 P0: the tallest the composer host may be while the timeline keeps
     * [TIMELINE_FLOOR_UNDER_QUEUE] — the ceiling the host is measured against
     * **before** the viewport is given what is left.
     *
     * This is where「空间不够时先让队列条/卡片让路」is actually enforced. The host
     * is measured first (it has to be — the last-row form needs its height before
     * the viewport can be sized), and before v100 it was measured against the
     * **whole** pane with `minHeight = 0`, so it was free to claim everything
     * and leave the timeline the remainder. Handing it `pane − floor` instead
     * makes the band a term in the host's own constraints: the queue bar inside
     * it already yields first (its `heightIn(max = queueBarCeiling(…))` and its
     * one-row viewport floor — `ComposerQueuePanel`), and only what the bar
     * declines reaches the card.
     */
    fun composerHostCeilingPx(pane: Int, floorPx: Int): Int =
        (pane - floorPx).coerceAtLeast(0)

    /**
     * v101 P0: the smallest pane at which the hard [TIMELINE_FLOOR_UNDER_QUEUE]
     * is **guaranteed** — i.e. the pane at and above which the floor never
     * yields, and below which it yields to the pane by a **declared** rule rather
     * than incidentally.
     *
     * This is not a softened floor, it is the **boundary of a two-branch rule**
     * whose branches are now written down ([timelineViewportFloor],
     * [timelineFloorGuaranteed], [timelineReadableFloorPx]) instead of being an
     * emergent property of `coerceAtMost`. See
     * `research/v97_fix/QUEUE_TIMELINE_SPACE_FIX4.md` §2 for the ruling and §3
     * for the 600 px budget that motivates it.
     */
    val TIMELINE_FLOOR_YIELD_PANE: Dp
        get() = TIMELINE_FLOOR_UNDER_QUEUE

    /**
     * v101 P0: the band's own floor, as an **explicit two-branch rule**.
     *
     * ```
     * pane >= 64  ->  64                                    // hard, never scaled
     * pane <   64  ->  pane                                 // declared yield
     * ```
     *
     * Numerically identical to v100's `min(64, pane)`, deliberately: the ruling
     * in `QUEUE_TIMELINE_SPACE_FIX4.md` §2 is that the yield below 64 is **not a
     * defect** — a pane of 51 px cannot show 64 px of band inside itself, and
     * the floor must not claim space that does not exist or the whole layout
     * overflows the window. What v101 adds is that the yield is now a **named,
     * pinned, stated** boundary ([TIMELINE_FLOOR_YIELD_PANE]) with a predicate
     * ([timelineFloorGuaranteed]) the suite asserts against, so it can never
     * regress into a silent threshold change again.
     */
    fun timelineViewportFloor(pane: Dp): Dp = when {
        pane <= 0.dp -> 0.dp
        timelineFloorGuaranteed(pane) -> TIMELINE_FLOOR_UNDER_QUEUE
        else -> pane
    }

    /**
     * v101 P0: whether [pane] can afford the hard band — `pane >= 64 dp`.
     *
     * This is the feasibility question the device read 51 px against
     * (`REPORT_U1D.md` §7.3): the answer there is **false**, so the floor yields
     * and the timeline is handed the **entire** pane. Kept as a function so the
     * truth table can ask it per cell instead of hard-coding a `min`.
     */
    fun timelineFloorGuaranteed(pane: Dp): Boolean =
        pane >= TIMELINE_FLOOR_YIELD_PANE

    /**
     * v101 P0: [timelineViewportFloor] in the px domain, so a **measured**
     * `constraints.maxHeight` and the composition-time `paneHeight` (a `Dp` read
     * a frame earlier) can be compared as the same number.
     *
     * v100 fed the floor from `paneHeight` alone, so a pane that had already
     * grown past the band by measure time could still be floored at the stale,
     * smaller `paneHeight` — the floor would then yield for a pane that could
     * have paid it. The measure policy now takes the **larger** of the two (see
     * `SessionPaneArrangement`), so the floor can only ever yield when the pane
     * that *actually exists* at measure time is itself under the band.
     *
     * `floorPx` is the band's floor already rounded at the pane's density, so
     * this stays density-free and byte-comparable with the Dp branch above.
     */
    fun timelineViewportFloorPx(panePx: Int, floorPx: Int): Int = when {
        panePx <= 0 -> 0
        floorPx <= 0 -> 0
        panePx >= floorPx -> floorPx
        else -> panePx
    }

    /**
     * v101 P0: the **declared** minimum readable band for a measured pane — the
     * number every truth-table cell is held to and the one the device is asked
     * about: [floorPx] on a pane that can afford it, and the **whole pane** on
     * one that cannot.
     *
     * `readable >= this` is the per-cell assertion; `readable >= 64` is the
     * assertion on every pane of 64 dp or more.
     */
    fun timelineReadableFloorPx(panePx: Int, floorPx: Int): Int =
        timelineViewportFloorPx(panePx, floorPx)

    /**
     * v102 P0: the band **as a legal lower bound** — [floorPx] clamped into the
     * space the pane can actually hand out, so a measure policy can never build
     * a `Constraints` whose `minHeight` exceeds its `maxHeight`.
     *
     * ```
     * availablePx <= 0  ->  0            // no pane at all: no band either
     * floorPx     <= 0  ->  0
     * floorPx >= availablePx -> availablePx
     * otherwise          ->  floorPx
     * ```
     *
     * **为什么必须单独存在**（`research/v97_fix/CRASH_SHRINK_FIX.md`）：地板的**来源**
     * 与地板的**宿主**在时间上不同步。`timelineViewportFloor` 喂的是组合期读到的
     * `paneHeight`（一个由 `onSizeChanged` 写的 `Dp` state，**比测量晚一帧**），
     * 而 `constraints.maxHeight` 是**这一帧真实的** pane。缩窗（`wm size 768 -> 600`、
     * 分屏、旋转、IME 开关）会让某几帧先收到 `maxHeight = 12` 而 `paneHeight` 仍是
     * 缩之前的读数，于是 `floorPx = 64`；`Constraints.copy(minHeight = 64,
     * maxHeight = 12)` 抛 `IllegalArgumentException: maxHeight(12) must be >=
     * minHeight(64)`（`SessionScreen.kt` 的 `SessionPaneArrangement`，
     * `research/v97_verify/5556_v98/REPORT_U1E.md` §6.2），**app 进程死亡**。
     *
     * 关键点：`maxOf(declaredFloorPx, measuredFloorPx)`（v101 引入）**救不了这一帧**，
     * 因为 [timelineViewportFloorPx] 的返回值恒 `<= floorPx`，
     * 所以 `maxOf(declared, measured) == declared` —— 这个 `maxOf` 是**可证的恒等**，
     * 它**从不**把地板调小。**唯一的合法位置就是在这里：约束落地的那一步。**
     *
     * **为什么不回退 v101 语义**：pane 装得下时本函数是恒等的（`floorPx < availablePx`
     * ⇒ 原值返回），`1366x768` 的 92px 读数与「≥11 条 / 2–5 条」两格逐字不变；
     * pane 装不下时返回的正是 [timelineViewportFloorPx] 早已声明的让位值 `panePx`，
     * 即**让位照旧，只是让位发生在写进 `minHeight` 之前**。
     */
    fun safeViewportFloorPx(floorPx: Int, availablePx: Int): Int = when {
        availablePx <= 0 -> 0
        floorPx <= 0 -> 0
        floorPx >= availablePx -> availablePx
        else -> floorPx
    }

    /**
     * v103 P0: **the single containment point** — the composer host may be
     * exactly this tall and never one pixel more.
     *
     * ```
     * panePx <= 0        ->  0            // no pane: no host
     * ceilingPx <= 0     ->  0            // the ceiling really is zero
     * ceilingPx >= panePx->  panePx        // a ceiling can never outgrow the pane
     * otherwise          ->  ceilingPx
     * ```
     *
     * **为什么必须只有这一处**（`research/v97_fix/COMPOSER_OVERFLOW_FIX.md` §3）：
     * 改之前「宿主能有多高」在**两处各求一次值**——
     * [composerHostCeilingPx]（measure policy，`SessionScreen.kt` 的
     * `maxHeight = …`）与 [composerTopPx]（place，`host.place(0, …)`）——
     * 而**两处求的是「pane 减掉某个数再夹到 0」，减的数却不是同一个**
     * （一个减 `floorPx`，一个减 `host.height`），**中间没有任何一步要求
     * `host.height <= ceiling`**。于是「宿主比 pane 高」这种状态在摆位处
     * 被 [composerTopPx] 原来的 `coerceAtLeast(0)` **静默转成「top 钉死在 0、
     * 底沿 = host.height 越出 pane」** —— 这正是真机 `1366x600` + IME 下
     * 底沿越过 pane 底沿 4–5px 的形状（`research/v97_verify/5556_v98/
     * REPORT_U1E.md` §5.3）。
     *
     * 本函数把「宿主可高」收敛成**一个数**：measure policy 用它写 `maxHeight`，
     * 摆位用它算 `top`，于是 **`top + host.height == pane` 成为构造性恒等式**，
     * 而不是需要两处恰好一致的巧合。
     *
     * **为什么对既有读数逐字恒等**：`floorPx >= 0` 时
     * [composerHostCeilingPx] 本身已经 `<= pane`，故
     * `coerceAtMost(pane)` 是**空操作** —— `1366x768` 的 92px、
     * `1366x600` 的 51px、「≥11 条 / 2–5 条」两格**一个像素都不动**。
     * 本函数只对「`floorPx < 0`」这种**当前不可达**的状态多一层兜底。
     */
    fun composerHostHeightPx(panePx: Int, ceilingPx: Int): Int =
        ceilingPx.coerceAtMost(panePx.coerceAtLeast(0)).coerceAtLeast(0)

    /**
     * The composer's **placed** bottom edge, in px — `top + host` after both have
     * been through [composerHostHeightPx].
     *
     * 有了它，「底沿不越界」不再需要靠 `top + height` 手算：
     * `composerBottomPx(pane, host) <= pane` 就是被钉的那条断言，
     * 而宿主落在 pane 之内时它恒**等于** `pane`（底对齐，不留缝也不越界）。
     */
    fun composerBottomPx(panePx: Int, hostPx: Int): Int =
        composerTopPx(pane = panePx, composer = hostPx) +
            composerHostHeightPx(panePx = panePx, ceilingPx = hostPx)

    /**
     * v104 P0: the composer's **child union** — the rectangle a11y/真机 actually
     * measures, `union(EditText, Add, Send)` — 收进**宿主盒之内**的可用高。
     *
     * **v103 恒等式为什么在真机上没兜住**（`research/v97_verify/5556_v98/
     * REPORT_COMPOSER_OVERFLOW.md` §4）：v103 的 `top + host ≡ pane` 是**宿主盒**
     * 的恒等式，而复验员量的是**子节点并集**。宿主盒 = `TOOL_ROW_HEIGHT = 44dp`
     * （`Composer.kt:2501`，`:2134` 处 `.height(TOOL_ROW_HEIGHT)`），并集里每个
     * 子节点却带 Compose 的 **48dp 最小触摸靶**（`clickable` ⇒
     * `minimumInteractiveComponentSize`，`Composer.kt:1841` / `:2284`）
     * ⇒ 并集比宿主盒高，子节点底沿**从宿主盒底沿下方穿出去**。
     *
     * 两档真机读数逐位对上（`REPORT_COMPOSER_OVERFLOW.md` §4.1 原文）：
     * ```
     * 1366x600 + IME: union 169..218 (h=49), host 44 ⇒ 49 - 44 = +5   ← 越界
     * 1366x768 + IME: union 208..296 (h=88), host 94 ⇒ 88 - 94 = -6   ← 未越界
     * ```
     * ⇒ **那 5px = 48dp 触摸靶 − 44dp 宿主盒 = 4，再加编辑器在带内居中的 1px
     * 亚像素取整 = 5**（`REPORT_U1E.md` §5.3 的「4（ET）/ 5（按钮）」逐位吻合）。
     *
     * 本函数是**子节点可用高的唯一求值处**，与 [composerHostHeightPx] 同一个
     * `coerceAtMost` 收口：宿主放得下时**原值返回**（`coerceAtMost` 空操作，
     * 768 的 92 / 600 的 51 / 两格队列读数一个像素不动），
     * 宿主装不下时返回**宿主能给的全部**，于是并集底沿恒 `<= 宿主底沿 <= pane`。
     */
    fun composerChildUnionHeightPx(panePx: Int, hostPx: Int, unionPx: Int): Int =
        unionPx.coerceAtMost(composerHostHeightPx(panePx = panePx, ceilingPx = hostPx))

    /**
     * v104 P0: 子节点并集的**底沿** px —— 真机复验的判据量。
     *
     * `top + min(union, host) <= top + host <= pane` 是**构造性恒等式**，
     * 因此「实测 `composerBottom <= paneBottom`」在**并集**这一维上恒成立，
     * 不再依赖宿主盒自己守规矩。
     */
    fun composerChildUnionBottomPx(panePx: Int, hostPx: Int, unionPx: Int): Int =
        composerTopPx(pane = panePx, composer = hostPx) +
            composerChildUnionHeightPx(
                panePx = panePx,
                hostPx = hostPx,
                unionPx = unionPx,
            )

    /**
     * v136 P0: 一行形态那条带里**每个子节点的摆位** —— 两个孩子用**同一条**规则。
     *
     * v104 让两个子节点各自受 `maxHeight = band` 约束（尺寸对齐了），但**摆位**仍是
     * 两套：编辑器按 `(band - editor)/2` 居中，而工具行被 `place(0, 0)` 钉在带的顶沿
     * （`Composer.kt` 的 minimal 分支）。两套规则只在一行形态下才可能给出**不同**的顶沿：
     * 只要两个子节点的实测高**不同**（工具行里有被 `minimumInteractiveComponentSize`
     * 撑到 48dp 的控件，编辑器是 `heightIn` 的内容高），「一个居中、一个钉顶」就会把
     * 1px 的差留在并集里 —— 这正是 v135 记的 `unionPx 49` vs 基线 `48`
     * （真机 `EditText [24,214][405,262]` vs 按钮组 `[399,215][441,263]`：同为 48 高，
     * 顶沿差 1px）。
     *
     * 本函数把那 1px 的自由度**收掉**：两个子节点都按「带 − 自己」居中，于是
     * * 顶沿相同当且仅当两者同高（差 0），或相差 1px 时**各自居中到同一个整数位**；
     * * 底沿 `composerBandChildTopPx + 自己的高 <= band` **恒成立**（`band >= child`
     *   时 `(band - child)/2 + child <= band`，`band < child` 时被 `coerceAtLeast(0)`
     *   收成 0 并由父的 `maxHeight = band` 裁到 `band`）。
     *
     * **单调性**：`0 <= top <= band/2`（`child` 越大顶沿越高），且
     * `top <= band - child`，所以它是「离顶沿最近的那个居中位」—— 摆位只由带高和
     * 自己的高决定，**不含任何别的子节点**，也就没有两个子节点互相错开的可能。
     */
    fun composerBandChildTopPx(bandPx: Int, childPx: Int): Int =
        (bandPx - childPx.coerceAtLeast(0)).coerceAtLeast(0) / 2

    /**
     * v136 P0: 一行形态**子节点并集的底沿**（相对带顶沿）—— 真机量的那个矩形。
     *
     * 与 [composerChildUnionHeightPx] 的区别是那一处量的是**可用高**（用
     * `coerceAtMost` 收进宿主，因此在并集这一维上结构上恒 `<= host`，也就是 v135
     * §1.6 指认的**假绿陷阱**：它对任何输入都返回非正残差，验证不了真机读数）。
     * 本函数量的是**摆位后的真实底沿**，不收口、不夹紧 —— 它可以返回大于 `band` 的
     * 值，而那正是缺陷本身。把它做成生产函数、并让 `Composer.kt` 用它摆位，是为了让
     * 「并集底沿 vs 基线」这条判据在**生产代码里**有一个可被测试调用的求值点。
     */
    fun composerBandUnionBottomPx(bandPx: Int, editorPx: Int, toolRowPx: Int): Int =
        maxOf(
            composerBandChildTopPx(bandPx = bandPx, childPx = editorPx) +
                editorPx.coerceAtLeast(0),
            composerBandChildTopPx(bandPx = bandPx, childPx = toolRowPx) +
                toolRowPx.coerceAtLeast(0),
        )

    /**
     * v105 P0: **被强制的最小触摸靶**（Compose `Modifier.clickable` 挂的
     * `minimumInteractiveComponentSize`，material3 的 48dp 地板）在**一行带**里的
     * 容许高度 —— 唯一求值处，`Composer.kt` 的闸门读它、测试的真值表断言它。
     *
     * **48dp 是谁强制的**（不是本仓设的）：`Add` / `Stop`(Send) / `Model` chip
     * 每一个都挂 `Modifier.clickable`（`Composer.kt` 的 `controls()` 链），而
     * foundation 的 `minimumInteractiveComponentSize()` 在这两个 local 里的
     * **非 `Unspecified`** 值就是它读的地板：material3 在 `MaterialTheme` 里
     * `provides 48.dp`（`androidx.compose.material3.LocalMinimumInteractiveComponentSize`，
     * `Composer.kt:32` 正是 import 的它）⇒ **48dp 由 material3 强制**。
     * 本仓自己**没有**任何一处 `provides 48.dp`（grep 全仓唯一命中是
     * `Composer.kt:2186` 的 `provides Dp.Unspecified`，即 v104 那处关闭）。
     *
     * **为什么它就是那 +5px**（`REPORT_OVERFLOW_DIAG.md` §5.1 逐位）：
     * ```
     * 一行带 = TOOL_ROW_HEIGHT = 44dp      (Composer.kt:2583)
     * 触摸靶 = 48dp  (material3 强制)      ⇒ 高出 4px
     * 控件在 44dp 的带里居中               ⇒ 上下各溢出 2px
     * 居中的亚像素取整                     ⇒ +1px      ⇒ union 底沿 +5px
     * ```
     * 真机两档原文（`REPORT_OVERFLOW_DIAG.md` §2.1/§2.2）：
     * `1366x600`+IME `Add/Stop/chip h=48` 装在 `band=44` 里 ⇒ `union(48) > band(44)`；
     * `1366x768`+IME 宿主盒 94 装得下 ⇒ `union(88) <= 94` 合格。
     *
     * **本函数把"靶能不能装进它自己的带"收敛成一个数**：
     *  * [oneRow] = `true`（一行形态）：composer's box **就是**这条带
     *    （`Composer.kt:2254` `layout(..., constrainHeight(band))`）⇒ 靶**封顶到带**。
     *    `>= 48` ⇒ 原值 48（带装得下，一像素不动）；`[28,48)` ⇒ **封顶到带本身**，
     *    而**不是** v104 的 `Dp.Unspecified`（那会把地板整条撤掉、控件回落到 28dp
     *    ⇒ 本函数在那一段返回 `band`，比 v104 **多 0–20dp 的可点面积**）；
     *  * [oneRow] = `false`（堆叠形态）：composer's box 是 `editor + toolRow`，
     *    由宿主 `Constraints.maxHeight` 收着，且 `1366x768` 那一档宿主 94 装得下
     *    48 ⇒ **原值返回**，真机 `Add/Send h=48` 逐字不变。
     *  * 带比 [controlFloor] 还矮（`28dp` 控件都放不下）⇒ 返回 [Dp.Unspecified]，
     *    调用方据此把 local 交还给 `Dp.Unspecified`（v86 的地板之下无解）。
     *
     * **单调性**（pin 在 `ComposerBandTouchTargetV105Test`）：结果恒 `<= 48`、
     * 恒 `>= min(band, 48)`，且 `band >= 28` 时 `结果 <= band` ⇒
     * `unionPx <= band` 恒成立（`unionPx = max(editorPx, toolRowPx)`，
     * `toolRowPx = max(28, 靶)`，两者都被同一个 `band` 收着）。
     */
    fun composerBandTouchTargetDp(
        oneRow: Boolean,
        band: Dp,
        minInteractive: Dp = MIN_INTERACTIVE_COMPONENT,
        controlFloor: Dp = CONTROL_FLOOR,
    ): Dp {
        val cap = if (oneRow) {
            minOf(minInteractive.value, band.value)
        } else {
            minInteractive.value
        }
        return when {
            cap >= controlFloor.value -> cap.dp
            // 带上连一个整控件都放不下（v86 地板之下）：没有可谈的靶，撤掉地板。
            else -> Dp.Unspecified
        }
    }

    /**
     * v76 P0: the composer host's top edge, in px — the pane's bottom minus the
     * host's own height, i.e. the same rectangle in both arrangements (the
     * floating form's host fills the pane and bottom-aligns its content, so its
     * top is 0; the shared form's host is the composer itself, so its top is
     * the viewport's bottom edge — `viewportHeightPx` + the host's height is the
     * pane in both forms).
     *
     * **v103 P0：** [hostPx] 先经 [composerHostHeightPx] **收进 pane 之内**，
     * 于是返回值恒 `>= 0`，且 `top + host <= pane` **恒成立**。
     *
     * 与 v76 的 `(pane - composer).coerceAtLeast(0)` 相比**逐格恒等**：
     * `composer <= pane` 时两者都等于 `pane - composer`（`coerceAtMost` 空操作）；
     * `composer > pane` 时旧式返回 `0`、新式也返回 `pane - pane = 0`。
     * ⇒ `SessionPaneArrangementV76Test` 的 4 条既有逐位断言
     * （`800/800 → 0`、`100/400 → 0`、`0/120 → 0`、`800/120 → 680`）**全部照旧通过**，
     * 而「宿主比 pane 高时底沿越界」从「结构上可能」变成「结构上不可能」。
     */
    fun composerTopPx(pane: Int, composer: Int): Int =
        pane.coerceAtLeast(0) - composerHostHeightPx(panePx = pane, ceilingPx = composer)

    // --------------------------------------------------------------- v77 P0:
    // the keyboard the platform claims

    /**
     * The chrome band as the mobile pane mounts it: the header row plus the view
     * tab strip (`MobileSessionTabs`), both of which the pane can be navigated
     * *from* — the tab strip is the only way to reach Changes / Files / Terminal.
     */
    val CHROME_FULL_HEIGHT: Dp = HEADER_HEIGHT + MobileForm.TAB_STRIP_HEIGHT_DP.dp

    /**
     * `SessionScreen.composerRegion`'s own vertical padding **in total**
     * (v110: `0 dp top + 12 dp bottom`; before that `4 dp top + 8 dp bottom`).
     *
     * The **total** is the only part anything downstream reads: every pane-budget
     * term below sums this one number. The *split* is free to change and did —
     * v110 moved the 4 dp off the top edge, where the band is placed flush with
     * the host box's top edge and any top padding is a pure overrun
     * (`hostTop 165 + 4 + 48 = 217 > paneBottom 213`), down to the bottom edge.
     */
    val COMPOSER_REGION_PADDING: Dp = 12.dp

    /**
     * What the pane cannot be squeezed below and stay a session pane: the whole
     * chrome band ([CHROME_FULL_HEIGHT]), the timeline's floor
     * ([TIMELINE_MIN_HEIGHT]) and the composer ([COMPOSER_MIN_HEIGHT], the
     * stacked floor, plus [COMPOSER_REGION_PADDING]).
     *
     * It is the floor [believedImeBottomPx] protects, and it is deliberately the
     * *full* form rather than [CHROME_MIN_HEIGHT] / [COMPOSER_MINIMAL_HEIGHT]:
     * the yields those two constants describe are the ones the pane chooses for
     * itself when the window really is short, and a pane that may still show its
     * own navigation must not be pushed *into* them by a number the window does
     * not corroborate. The sum also pays for both chrome rows an ordinary pane
     * pays (`chromeMaxHeightForComposer(284 dp, 116 dp)` is 96 dp against the
     * 84 dp band), so the pane that comes out of the clamp shows the header row
     * and the strip whole.
     */
    val PANE_ESSENTIAL_HEIGHT: Dp =
        CHROME_FULL_HEIGHT + TIMELINE_MIN_HEIGHT + COMPOSER_MIN_HEIGHT + COMPOSER_REGION_PADDING

    /**
     * v77 P0 「wm size/density 改后重置，会话页丢工具栏/tab 条」: how much of the
     * platform's IME inset the session body may believe.
     *
     * All four arguments are px in the **window's** frame, and the return value
     * is the inset to pad by (window-relative, so the caller may hand it straight
     * to a bottom padding):
     *
     *  * [visibleBoxPx] — the pane's own box: the body column *without* the IME
     *    inset (what `BoxWithConstraints.maxHeight` gives inside the body);
     *  * [floorPx] — the bottom inset the box already excludes (the navigation
     *    bar; 0 where the shell's bottom strip pays it instead);
     *  * [imeBottomPx] — `WindowInsets.ime`'s bottom, exactly as reported;
     *  * [essentialPx] — [PANE_ESSENTIAL_HEIGHT] at the current density.
     *
     * The reported inset is normally just believed: a docked keyboard's top is
     * where the composer has to stop, and the pane's own yields
     * ([chromeMaxHeightForComposer], [composerCompact], [composerMinimal]) are
     * what make a genuinely short window work (`research/v57_verify`, 308 dp of
     * window with 253 dp of keyboard).
     *
     * v77 P0 measured on `emulator-5554` (`research/v73_verify/5554/61_session_tab.png`):
     * after `wm size`/`wm density` had been overridden and then reset, the
     * platform kept reporting `ime.bottom` = 2195 px (836 dp) on a 512x1140 dp
     * window while the keyboard on screen was a stale **720x766 px floating**
     * window in the top-left corner — its top edge at y=797 px, 73 % of the
     * window *below* it, and the app's whole layout believed it:
     *
     * ```
     * window            2992 px = 1140 dp @420dpi
     * believed ime      2195 px =  836 dp
     * body              window − strip(101.7 dp) − ime = 209…226 dp  (the pane's
     *                   bottom measured 19 px above the IME window's own top edge;
     *                   either reading is far under the 284 dp essential form)
     * chrome ceiling    min(chromeMaxHeightForComposer(209, 96) = 41 dp,
     *                       chromeMaxHeight(209) = 32 dp) = 32 dp   ← the header
     *                   row clipped, the view tab strip gone: 「丢工具栏/tab 条」
     * composer          compact on the measured 209 dp pane (209 < 224), anchored
     *                   to the body's bottom = the reported keyboard top:
     *                   「composer 悬浮」
     * ```
     *
     * The inset is not app state — the same relaunch re-derives it, which is why
     * a force-stop did not clear the broken layout — and the platform releases
     * it only when the IME finally re-lays-out. So the app's half of that
     * failure has to be not believing it blindly, which is the judgement
     * `pickerCardMax` already makes for the directory picker (「an inset that
     * leaves the picker less than a usable viewport cannot be telling the truth
     * about a keyboard」).
     *
     * The bound is only applied where the window itself corroborates the
     * contradiction: a box that can pay [essentialPx] **twice** has room to
     * spare, so a keyboard claiming more than half of it *and* leaving less than
     * the essential form is not describing a docked keyboard. Below that — every
     * short window, and every landscape/desktop body the v51–v74 rounds measured
     * (a 414 dp landscape box, a 696 dp desktop one) — the keyboard keeps its
     * full claim, because there the platform's number is a keyboard and not a
     * mis-sized IME window. The bound is therefore inert on every window the
     * existing suites pin, and it caps the pane at exactly [essentialPx] where it
     * does fire.
     */
    fun believedImeBottomPx(
        visibleBoxPx: Int,
        floorPx: Int,
        imeBottomPx: Int,
        essentialPx: Int,
    ): Int {
        if (imeBottomPx <= 0) return imeBottomPx
        if (visibleBoxPx <= 0 || essentialPx <= 0) return imeBottomPx
        if (visibleBoxPx < essentialPx * PANE_ESSENTIAL_SPARE_ROOM) return imeBottomPx
        val claimedPx = (imeBottomPx - floorPx.coerceAtLeast(0)).coerceAtLeast(0)
        val roomPx = visibleBoxPx - essentialPx
        if (claimedPx <= roomPx) return imeBottomPx
        return roomPx + floorPx.coerceAtLeast(0)
    }

    /**
     * How much room the box must have beyond [PANE_ESSENTIAL_HEIGHT] before the
     * pane may doubt a keyboard — see [believedImeBottomPx]. Two, i.e. the
     * keyboard would have to claim *more than half* of a box that is already
     * worth twice what the pane needs.
     */
    const val PANE_ESSENTIAL_SPARE_ROOM: Int = 2

    // --------------------------------------------------------------- v99 P0:
    // the queue bar shares the pane with the timeline.

    /**
     * v99 P0 「队列条把时间线压到 16px 且 100% 覆盖」: the queued-prompts bar's
     * upstream cap, moved here so the pane budget and `ComposerQueuePanel` read
     * **one** number (`max-h-[148px]`, `queue-panel.tsx:34`).
     *
     * It was a *constant* before, and a constant is a bar with no budget: the
     * bar was measured at its full intrinsic height on every pane, the whole
     * host was handed to [viewportHeightPx] as "the composer", and the bar's
     * height came straight out of the timeline.
     */
    val QUEUE_BAR_MAX_HEIGHT: Dp = 148.dp

    /** `h-8` queue rows (`queue-panel.tsx:85`). */
    val QUEUE_ROW_HEIGHT: Dp = 32.dp

    /**
     * The panel's own chrome: the 6 dp of top padding plus the 12 dp `-mb-3`
     * overlap that slides it under the composer card (`ComposerQueuePanel`).
     */
    val QUEUE_BAR_CHROME: Dp = 18.dp

    /**
     * v99-b: the smallest a queue bar may be **drawn** at — one whole `h-8` row
     * plus the panel's chrome.
     *
     * This is the height at which the bar stops being able to hold a row's
     * **Steer** / **×** actions, so it is the line the budget snaps to (see
     * [queueBarCeiling]): a bar with less room than this is not a bar, because
     * what would be left of its only row is a sliver whose action buttons are
     * clipped out of the layout — and a clipped node is not in the
     * accessibility tree at all, which is how the queue-actions round's
     * `Steer` / **×** regression showed up on device
     * (`research/v97_verify/5556_v98/REPORT_U1.md` §3 判据四).
     */
    val QUEUE_BAR_MIN_HEIGHT: Dp = QUEUE_ROW_HEIGHT + QUEUE_BAR_CHROME

    /**
     * What the queue bar is measured at before any ceiling: `n` rows plus the
     * panel's chrome. A bar with no rows draws nothing at all
     * (`ComposerQueuePanel` returns early on an empty list), which is why 0 rows
     * is 0 dp and not a chrome-sized sliver.
     */
    fun queueBarIntrinsicHeight(rows: Int): Dp =
        if (rows <= 0) 0.dp else QUEUE_ROW_HEIGHT * rows + QUEUE_BAR_CHROME

    /**
     * v99-b: the **row-level** floor — the height of the bar's scroll viewport
     * while it has rows to show. One whole `h-8` row, never a fraction of one.
     *
     * [QUEUE_BAR_MIN_HEIGHT] is the bar's floor; this is the *row's*, and the two
     * answer different questions. The bar's floor asks「may the bar be drawn at
     * all」; this asks「how much of a row may the viewport show」, which is the
     * question a `Steer` / **×** hit target depends on. `ComposerQueuePanel`
     * applies it as `heightIn(min = …)` on its `LazyColumn`, so a queue of 11 or
     * 23 rows scrolls inside a viewport that is still a whole row tall instead of
     * being flattened into 2–3 px slivers.
     *
     * `minOf` with the intrinsic height keeps the claim honest for a caller that
     * asks about fewer rows than one row's worth (and for 0 rows, which draws
     * nothing at all).
     */
    fun queueBarViewportFloor(rows: Int): Dp =
        if (rows <= 0) 0.dp else minOf(QUEUE_ROW_HEIGHT, queueBarIntrinsicHeight(rows))

    /**
     * v99-b: how many rows a bar of [barHeight] shows **whole**. The rest are
     * reachable by scrolling the viewport down to [queueBarViewportFloor], which
     * is the property the queue-actions round needs: a 23-row queue is not 23
     * readable rows, it is 「these many, and the bar scrolls」.
     */
    fun queueBarVisibleRows(rows: Int, barHeight: Dp): Int {
        if (rows <= 0 || barHeight <= 0.dp) return 0
        val floor = queueBarViewportFloor(rows)
        if (floor <= 0.dp) return 0
        return ((barHeight - QUEUE_BAR_CHROME).coerceAtLeast(floor) / floor).toInt()
            .coerceIn(0, rows)
    }

    /**
     * v99 P0: the bar's share of a pane — the **only** band a queued prompt may
     * buy, and the reason 124 dp of it does not come out of the timeline.
     *
     * The bar is the first child of the composer's own `Column`
     * (`Composer`'s strip), so in a height-constrained host it is measured
     * *before* the card: whatever the pane could not pay for simply went into
     * the bar, and the card — the surface carrying the Send button — was
     * squeezed instead. That is the「队列条优先」the report measured. The order
     * inverts it to the order the pane can actually honour:
     *
     *  1. the timeline keeps [TIMELINE_FLOOR_UNDER_QUEUE] (「一行半」);
     *  2. the composer card keeps [COMPOSER_MINIMAL_HEIGHT] (its tool row, v51);
     *  3. **only what is left over is the bar's**, capped at
     *     [QUEUE_BAR_MAX_HEIGHT].
     *
     * A bar past the cap does not grow: it scrolls, because it is already a
     * `LazyColumn` — and a scrolling bar keeps every row's `Steer` / **×**
     * tappable, which is the invariant the queue-actions round had to fight for.
     *
     * v99-b, the **lower** end of the same clamp: the bar is only handed a
     * ceiling it can actually use. A ceiling below [QUEUE_BAR_MIN_HEIGHT] is not
     * a small bar, it is a cropped one — the viewport is shorter than a row, so
     * the row is clipped, and a clipped row's `Steer` / **×** are laid out
     * outside the viewport and therefore never enter the accessibility tree
     * (`uiautomator dump` only reports what is on screen). Declining the room
     * costs the band nothing: this ceiling is the bar's **only** claim on the
     * pane, so everything it declines goes back to the timeline, and the bar
     * draws as bare chrome until the pane grows.
     *
     * The priority this encodes, in order: the timeline's band, then the
     * composer's card, then the bar — and a bar that cannot be shown whole is
     * shown **not at all** rather than shown in pieces.
     *
     * Note the floor is [TIMELINE_MIN_CONTENT] and not [TIMELINE_MIN_HEIGHT]:
     * the latter is a *pane* budget (chrome + composer + band) that is applied
     * through the chrome ceiling and, on a body that is over budget, is
     * legitimately 0. What the queue bar must never be able to do is eat the
     * band itself, and [TIMELINE_MIN_CONTENT] is that band.
     */
    fun queueBarCeiling(
        pane: Dp,
        timelineFloor: Dp = TIMELINE_FLOOR_UNDER_QUEUE,
        composerFloor: Dp = COMPOSER_MINIMAL_HEIGHT,
    ): Dp {
        if (pane <= 0.dp) return 0.dp
        val room = pane - timelineFloor - composerFloor
        if (room < QUEUE_BAR_MIN_HEIGHT) return 0.dp
        return room.coerceIn(0.dp, QUEUE_BAR_MAX_HEIGHT)
    }

    /**
     * The bar's height the pane actually lays out: its intrinsic height under
     * [queueBarCeiling]. This is the number the fix pins — a bar may shrink and
     * scroll, but it can never grow into the band's share, and (v99-b) it is
     * never a value between 0 and [QUEUE_BAR_MIN_HEIGHT]: a bar that has rows is
     * either whole or not drawn.
     *
     * v99-b: `composerFloor` is a parameter for the same reason
     * [timelineShare] charges the pane its own [composerFloorFor] card — the bar
     * must be budgeted against **the card it actually shares the pane with**.
     * Left on the cheap one-row default, a two-line composer was handed a bar
     * 60 dp larger than the room that was actually there, and the two halves of
     * the truth table (band on one side, bar on the other) stopped adding up to
     * the pane. The default is unchanged, so the existing pins see exactly what
     * they saw.
     */
    fun queueBarHeight(
        rows: Int,
        pane: Dp,
        composerFloor: Dp = COMPOSER_MINIMAL_HEIGHT,
    ): Dp = queueBarIntrinsicHeight(rows)
        .coerceAtMost(queueBarCeiling(pane, composerFloor = composerFloor))

    /**
     * v99 P0: the truth table the JVM suite pins — given a total height, a
     * keyboard height, a composer line count and a queue-row count, how much
     * timeline is left.
     *
     * ```
     * pane     = total − keyboard                  (the post-inset body)
     * card     = one composer line? minimal : stacked
     * bar      = queueBarHeight(queueRows, pane)   ← capped, scrollable
     * timeline = pane − card − bar
     * ```
     *
     * It is deliberately a *pure* function of the four device-read numbers, so
     * the failing frame can be replayed as data: `total 720 / keyboard 378 /
     * 2 composer lines / 2 queue rows` is the REPORT_SWEEPD3 §2 state ③, and
     * [timelineFloorHolds] is the assertion the device could not make.
     */
    fun timelineShare(
        totalHeight: Dp,
        imeHeight: Dp,
        composerLines: Int,
        queueRows: Int,
    ): Dp {
        val pane = (totalHeight - imeHeight).coerceAtLeast(0.dp)
        if (pane <= 0.dp) return 0.dp
        val card = composerFloorFor(composerLines)
        // v99-b: through [queueBarHeight], so the band and the bar are two halves
        // of **one** budget and always add up to the pane.
        val bar = queueBarHeight(queueRows, pane, composerFloor = card)
        return (pane - card - bar).coerceAtLeast(0.dp)
    }

    /**
     * The composer card's floor at a given line count — the number the bar's
     * ceiling and [timelineShare] both charge the pane for, so the two cannot
     * disagree. One composer line is the one-row form
     * ([COMPOSER_MINIMAL_HEIGHT]: the tool row plus the region's padding), two or
     * more the stacked form ([COMPOSER_MIN_HEIGHT]: the editor, the tool row and
     * that padding).
     *
     * The caller that lays the card for real passes the same shape's floor
     * ([queueBarCeiling]'s `composerFloor`) — the bar must never be budgeted
     * against a cheaper card than the one it shares the pane with, or the 60 dp
     * of difference is exactly the 60 dp the messages would have lost.
     */
    fun composerFloorFor(composerLines: Int): Dp =
        if (composerLines <= 1) COMPOSER_MINIMAL_HEIGHT else COMPOSER_MIN_HEIGHT

    /**
     * Whether the bar's occupancy may be said to have left the timeline a real
     * band. A pane that cannot pay card + band + one bar row at all has nothing
     * to protect — the bar yields to 0 there by [queueBarCeiling] — so the
     * invariant is scoped to the panes where a band is possible at all, and
     * below that line the honest answer is「this pane cannot show both」rather
     * than a false pass.
     */
    fun timelineFloorHolds(
        totalHeight: Dp,
        imeHeight: Dp,
        composerLines: Int,
        queueRows: Int,
    ): Boolean {
        val pane = (totalHeight - imeHeight).coerceAtLeast(0.dp)
        val card = composerFloorFor(composerLines)
        if (pane < card + TIMELINE_FLOOR_UNDER_QUEUE) return true
        return timelineShare(totalHeight, imeHeight, composerLines, queueRows) >=
            TIMELINE_FLOOR_UNDER_QUEUE
    }

    /**
     * The band the queue bar may never eat — the timeline's own item floor.
     *
     * 64 dp is [TIMELINE_MIN_CONTENT], the same number the v52–v74 rounds settle
     * on as「一行半」: one message line with its meta row, and never 0. The ③
     * frame read **16 px** with the bar covering 100 % of it, so 16 dp is the
     * failure this floor exists to make unrepresentable.
     */
    val TIMELINE_FLOOR_UNDER_QUEUE: Dp = TIMELINE_MIN_CONTENT
}

/**
 * v136: the one-row band publishes its **last measured** child-union bottom edge, so
 * the quantity the device measures (`unionBottom` -- a11y's `max(child.y2)`) has a
 * production read point instead of living only in a device dump.
 *
 * It is a **read point, not an assertion**: nothing is clamped, nothing is thrown
 * (a `check()` inside `layout` kills the process -- `CRASH_SHRINK_FIX.md`). Its
 * purpose is that the number is *reachable*, so a placement regression is visible to
 * a JVM pin rather than only on a device frame.
 */
object SessionComposerBandUnion {
    /** Last `unionBottom` the one-row arrangement computed; `null` before the first pass. */
    @Volatile
    var unionBottomPx: Int? = null
        private set

    /** The band it was measured against -- `unionBottom <= bandPx` needs both. */
    @Volatile
    var bandPx: Int? = null
        private set

    fun record(unionBottom: Int, bandPx: Int) {
        unionBottomPx = unionBottom
        this.bandPx = bandPx
    }
}