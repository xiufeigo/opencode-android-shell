package com.xiufeigo.opencodeshell.ui.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 极窄 geometry for Home's two row lists — the session rows
 * (`HomeSessionsList.HomeSessionRow`) and the project rows
 * (`HomeProjectsList.ProjectRow`).
 *
 * Both rows are `h-10` / `h-7` strips of **fixed** furniture around one flexible
 * text cell, and both were laid out with the desktop numbers only. The fixed
 * halves add up to more than a 极窄 row is wide:
 *
 * ```
 * session row: pl-3 (12) + pr-10 (40, the archive slot) + avatar slot 24
 *              + 2 × gap 8 + time cell (≈80 dp for "23 minutes ago" @12sp)
 *              = 172 dp before the title gets a pixel
 * project row: px-1.5 (6) + 2 + avatar 18 + 4 × gap 8 + ⋯ ✎ ✕ (3 × 28)
 *              = 142 dp before the project name gets a pixel
 * ```
 *
 * A Compose `Row` measures its unweighted children **first** (the time cell, the
 * icon buttons) and hands the weighted ones whatever is left, so below those
 * widths the title/name cell is squeezed to zero while the trailing furniture
 * keeps its full width — the sub-250 dp defect this file exists for: the row
 * renders avatar + time (or avatar + three icons) and no text at all, and the
 * time cell itself starts clipping mid-word once even *it* does not fit.
 *
 * The compact shape therefore does three things at the row's width floor
 * ([NARROW_WIDTH]):
 *
 * - the trailing slot drops to upstream's own mobile padding — the session row
 *   is `py-3 ps-1.5 pe-3 md:ps-3 md:pe-10` (`home/sessions/view.tsx:597-601`), so
 *   `pe-3` = 12 dp is the width below `md:`, not an invention; the 40 dp archive
 *   slot is desktop-only furniture (`SHOW_HOME_SESSION_ARCHIVE === false`);
 * - the trailing time cell is capped ([timeMax]), so a long label can never eat
 *   the title the way it did — the cell ellipsizes instead;
 * - the session row's project label and the project row's duplicated ✎ / ✕ give
 *   way. The project label is the session's *secondary* text and the two icons
 *   repeat what the row's ⋯ menu already carries, so at the floor the row keeps
 *   what only it can show.
 *
 * The numbers are pure so the widths are JVM-testable (`HomeRowNarrowTest`), the
 * split `SettingsRowStackedWidth` / `SuggestionRowLayout` already use.
 */
internal object HomeRowLayout {

    /**
     * The width below which a Home row switches to its compact shape.
     *
     * Measured on the **row's own width**, not the window: `HomeScreen` insets
     * the card by `m-2` (8 dp a side) and the content column by 12 dp a side, so
     * a 320 dp phone gives a 280 dp row and a 280 dp window (split-screen, a
     * folded cover display) gives a 240 dp row. 250 dp is the width at which the
     * session row's fixed furniture (172 dp) plus a readable title (~40 dp) stops
     * fitting the desktop shape.
     */
    val NARROW_WIDTH: Dp = 250.dp

    /**
     * `true` while the row is narrower than [NARROW_WIDTH]. A zero (or negative)
     * width — a collapsed host, a preview — is **not** narrow: no measurement,
     * no behaviour change (`SettingsPanelLayout.compactHeader`'s rule).
     */
    fun narrow(width: Dp): Boolean = width > 0.dp && width < NARROW_WIDTH

    // ------------------------------------------------------- session row (h-10)

    /** `ps-3` = 12 dp of the session row / rename editor. */
    val SESSION_START: Dp = 12.dp

    /** `pe-10` = 40 dp: the trailing affordance slot. Upstream reserves it for
     * the archive button at `md:` and up (`SHOW_HOME_SESSION_ARCHIVE` is false
     * here, so the slot is empty on purpose); the group headers reserve the same
     * slot for the floating ✎ that sits on their first line. */
    val SESSION_END: Dp = 40.dp

    /** `pe-3` = 12 dp: upstream's own mobile end padding (below `md:`). */
    val SESSION_END_NARROW: Dp = 12.dp

    /** The avatar slot `HomeSessionLeading` occupies (18 dp avatar + marker). */
    val SESSION_LEADING: Dp = 24.dp

    /** `gap-2` = 8 dp between avatar / labels / time cell. */
    val SESSION_GAP: Dp = 8.dp

    /** `max-w-[min(70%,480px)]` — the title's share while a project label follows. */
    const val TITLE_WEIGHT: Float = 0.7f

    /** Safety cap on the trailing time cell at full width ("23 minutes ago"). */
    val TIME_MAX: Dp = 96.dp

    /** The same cap in the compact shape — the cell ellipsizes before the title. */
    val TIME_MAX_NARROW: Dp = 64.dp

    fun sessionEnd(narrow: Boolean): Dp = if (narrow) SESSION_END_NARROW else SESSION_END

    fun timeMax(narrow: Boolean): Dp = if (narrow) TIME_MAX_NARROW else TIME_MAX

    /** `h-7` group header — the sticky part of [LIST_MIN_HEIGHT]. */
    val HEADER_MIN_HEIGHT: Dp = 28.dp

    /** `h-10` session row — what [LIST_MIN_HEIGHT] keeps, and what the rename
     *  editor takes the place of. */
    val SESSION_ROW_HEIGHT: Dp = 40.dp

    /**
     * The platform minimum tap target. A row whose own height is smaller (the
     * 40 dp session row, the 28 dp project rows) is laid out inside a node of at
     * least this size — see `ocHitTargetAtLeast` — so the `clickable` node the
     * finger lands on measures 48 dp. Two devices measured the bare rows at
     * 41 dp (108 px @ 2.625 px/dp and 41 dp @ 160 dpi).
     */
    val MIN_HIT_TARGET: Dp = 48.dp

    /** The height the clickable node of a [visual]-tall row reports. */
    fun hitTarget(visual: Dp): Dp = if (visual > MIN_HIT_TARGET) visual else MIN_HIT_TARGET

    /**
     * v43 P1: the floor the sessions **list** area must keep, so the inline
     * rename editor can never become an invisible dead end.
     *
     * The bug: at `wm size 640x1500` + 420 dpi (≈243 dp wide, ≈571 dp tall) the
     * soft keyboard leaves less room than the column above the list needs, so
     * the list's `weight(1f)` share measured to **0 dp** — search field, group
     * headers, rows and the utility nav all gone, and the editor that was just
     * opened (in place, replacing its row) with it. There is no scroll position
     * that recovers from a 0 dp viewport, and a phone user has no Esc
     * (`research/v43_verify/tC/v43_G3/G8/G9/G6`).
     *
     * The floor is one group header (28 dp) plus one row (40 dp), i.e. the least
     * that still shows the group a row belongs to and the row itself: enough
     * for the editing row to be on screen, tappable and cancellable. Callers
     * apply it as `heightIn(min = ...)` on the list viewport.
     */
    val LIST_MIN_HEIGHT: Dp = HEADER_MIN_HEIGHT + SESSION_ROW_HEIGHT

    /**
     * How much of the mobile single column the 项目 dropdown may take at most,
     * as a share of the available height (40% of the card).
     */
    const val PROJECTS_DROPDOWN_MAX_FRACTION: Float = 0.4f

    /**
     * v44 P1: the card height below which the 项目 dropdown may no longer take
     * its plain 40 % — i.e. the band the soft keyboard puts a phone's card in
     * (a 1080×2424 @2.75 card measures ≈790 dp with the keyboard down and
     * ≈438 dp with it up; a 640×1500 @420 one measures ≈300 dp up).
     *
     * Below this the sessions list is being *edited*, so it keeps
     * [SESSIONS_LIST_MIN_FRACTION] of the card instead of only its floor.
     */
    val SHORT_CARD_HEIGHT: Dp = 640.dp

    /** v44 P1: the share of a short card the sessions list keeps while typing. */
    const val SESSIONS_LIST_MIN_FRACTION: Float = 0.5f

    /**
     * The search block the mobile sessions column puts above its list
     * viewport (a 36 dp field plus its padding, `SEARCH_HEIGHT`), so a
     * reserved budget for the list has to carry it too — the floor that keeps
     * the editing row on screen is [LIST_MIN_HEIGHT] *inside* that block.
     *
     * v45 P1: this was 44 dp, which under-counted the block by 4 dp (the
     * field is 36 dp and the column pads 12 dp under it). On a card measured
     * with the keyboard up every dp of the reserve is a dp the list does not
     * get, and the 4 dp is exactly the kind of rounding that turns "the row is
     * on screen" into "the row is half a pixel off it"
     * (`research/v45_verify/tC/v45_46/47`).
     */
    val SESSIONS_SEARCH_RESERVE: Dp = 48.dp

    /** The whole mobile sessions column's floor: the search block plus
     * [LIST_MIN_HEIGHT]. */
    val SESSIONS_COLUMN_MIN: Dp = SESSIONS_SEARCH_RESERVE + LIST_MIN_HEIGHT

    /** The mobile `HomeUtilityNav` (设置 / 帮助) at its intrinsic height — two
     * 28 dp rows, the 4 dp gap between them and the 12 dp of padding the host
     * wraps them in (`pt-4 pb-8`). It sits below the list, so whatever the
     * projects give up has to leave this reachable too.
     *
     * v45 P1: the reserve was 60 dp — the two rows and the gap, without the
     * padding the same host always applied. A budget that does not match the
     * thing it reserves is a budget that is always 12 dp short, and the list is
     * what pays for it. */
    val UTILITY_NAV_MIN: Dp = 72.dp

    /**
     * v45 P1: the list viewport's floor **at a given card height** — the whole
     * point of the v43/v44 budget was that the list must never be squeezed
     * into a viewport no scroll can recover from, and a fixed [LIST_MIN_HEIGHT]
     * cannot honour that once the card itself is shorter than the budget.
     *
     * Measured on the 640x1500 @420 dpi phone with the keyboard up: the card
     * measures ≈185 dp, and the fixed floor (68 dp) plus the search block
     * (48 dp) plus the nav (72 dp) is 188 dp — already 3 dp more than the card
     * has. The sessions column therefore *overflowed* its share, the utility
     * nav was laid out on top of the list's tail, and the editing row — the
     * one thing the floor exists to keep visible — was under it. No amount of
     * `heightIn(min = …)` fixes an overflow: the column has to fit
     * (`research/v45_verify/tC/v45_46/47`, against the v44_33/34 the fixed
     * floor did fix).
     *
     * So the floor is derived: header + row while the card affords it, and
     * otherwise **just the row** — with the group header no longer sticky
     * (see [groupHeaderSticky]), which is what lets one row of viewport show
     * one row.
     *
     * v47 P0 「窄屏编辑态分组头不渲染」: [editing] is whether a rename editor
     * is open. While one is, the host yields the utility nav's share
     * ([utilityNavReserve]), so a floor derived as if the nav still stood
     * under-counts the room by 72 dp: on the 185 dp card above the check read
     * 185 − 48 − 72 = 65 < 68 and dropped to the row-only floor, although the
     * list really has 185 − 48 = 137 dp and fits header + row — the sibling
     * rows and the group header the floor exists to keep were virtualized out
     * (`research/v47_verify/tC/v47_30/31`). The default keeps the v45 contract
     * for callers that do not know the editing state.
     */
    fun listMinHeight(available: Dp, editing: Boolean = false): Dp =
        if (listBand(available, utilityNavReserve(editing)) - LIST_TOP_PAD >= LIST_MIN_HEIGHT) {
            LIST_MIN_HEIGHT
        } else {
            LIST_SHORT_HEIGHT
        }


    /**
     * Whether the group header may stay **sticky** at a given list viewport.
     *
     * A sticky header is drawn *over* the rows at the viewport's top, so a
     * viewport that cannot hold header + row can never show a whole row: the
     * header covers its first [HEADER_MIN_HEIGHT]. Below the two together the
     * header gives up being sticky and becomes an ordinary item, which is the
     * only way the editing row can scroll into view and stay there
     * (`research/v45_verify/tC/v45_46/47`).
     */
    fun groupHeaderSticky(viewport: Dp): Boolean = viewport >= LIST_MIN_HEIGHT

    /**
     * v46 P0 「宽屏改名输入框文字不可见」: how much of a row the pinned group
     * header covers once the row is scrolled to the viewport's top.
     *
     * A sticky header is drawn *over* the rows (`research/v46_verify/tC/v46_05`:
     * the 1080x2424 @420 dpi phone shows the rename editor as a thin grey stub
     * directly under 今天 — 40 dp of row minus the 28 dp opaque header — with
     * uiautomator reading the draft off a node whose pixels never show). The
     * editor opens scrolled to the top (`HomeSessionList`'s
     * `animateScrollToItem`), so while headers stay sticky the field's text line
     * sits underneath `bgBase` and no keystroke is ever visible. Below
     * [LIST_MIN_HEIGHT] the header is already a plain item ([groupHeaderSticky],
     * the 640x1500 narrow case in `v46_20`) and nothing covers the row.
     */
    fun editorOverlap(sticky: Boolean): Dp = if (sticky) HEADER_MIN_HEIGHT else 0.dp

    /** What is left to see of a [SESSION_ROW_HEIGHT] row under [editorOverlap]. */
    fun editorVisibleHeight(sticky: Boolean): Dp = SESSION_ROW_HEIGHT - editorOverlap(sticky)

    /**
     * v47 P0 「窄屏编辑态分组头不渲染」: where the editor-open scroll lands.
     *
     * The scroll used to land on the editing row itself, which puts the row at
     * the viewport's top — and the row's own group header (a plain item while
     * an editor is open) just above it, i.e. out of view
     * (`research/v47_verify/tC/v47_30/31`: the editor with 昨天 directly under
     * it, no 今天 anywhere). Landing on the group's header instead keeps the
     * header and the row it names on screen together — but only while the
     * viewport fits header + pad + row; a shorter viewport can only ever show
     * one of them, and then it has to be the row being edited.
     */
    fun editorScrollToHeader(viewport: Dp): Boolean =
        viewport >= HEADER_MIN_HEIGHT + GROUP_PAD_HEIGHT + SESSION_ROW_HEIGHT

    /**
     * `pt-4`: the 16 dp step between a group header and its first row — an
     * item of its own inside the list, so the editor-open scroll landing
     * ([editorScrollToHeader]) counts it like the header and the row.
     */
    val GROUP_PAD_HEIGHT: Dp = 16.dp

    /**
     * v54 P1 「矮窗会话列表只渲染一行且被截断」: what a card too short for the
     * header + row floor falls back to ([listMinHeight]).
     *
     * v45 dropped it to a bare [SESSION_ROW_HEIGHT] on the reasoning that a
     * 40 dp viewport can "only ever show one row" — but a row is never shown on
     * its own: every row is preceded by its group header ([HEADER_MIN_HEIGHT])
     * and the [GROUP_PAD_HEIGHT] that separates the two, so a viewport thinner
     * than the three together composes the header, spends its band on the pad
     * and **never composes a row at all**. The measured 1440x810 @420dpi window
     * (`research/v54_verify/tE/v54_16_sessionlist.png`) rendered exactly one row,
     * sliced by the card's bottom edge, and after any scroll the band held
     * nothing but the two group headers — three further frames byte-identical,
     * i.e. no scroll range left to recover from (`v54_18/19/20/22`; the
     * `uiautomator` bounds in `u16.xml` put the row at `y627…663` under a card
     * ending at 660, while `u18.xml` has the two headers 74 px apart with
     * nothing between them).
     *
     * The floor is therefore the least that shows a **whole** row together with
     * the group it belongs to — which is also the least that leaves a scroll
     * range at all.
     */
    val LIST_SHORT_HEIGHT: Dp = HEADER_MIN_HEIGHT + GROUP_PAD_HEIGHT + SESSION_ROW_HEIGHT

    /**
     * v54 P1: the list viewport's own top step (`contentPadding pt-3`) at a
     * given card — [LIST_TOP_PAD] while the **band** the list composes rows in
     * can pay for it *and* still leave [listMin], and 0 while it cannot.
     *
     * The step is cosmetic and comes out of the same box the rows compose in,
     * so on a short card it is what pushes the one row a viewport can hold half
     * under the card's edge: the measured band was 59 dp against a 40 dp row and
     * its 16 dp header pad. The same rule as the message timeline's
     * `SessionPaneLayout.timelineTopPadding`, and it is what makes [listMin] a
     * band the list really has.
     *
     * [chrome] is whatever else the column spends on the card (the utility nav
     * and the projects panel); it is the same number the budget below reserves.
     */
    fun listTopPadding(
        card: Dp,
        chrome: Dp = 0.dp,
        listMin: Dp = listMinHeight(card),
    ): Dp {
        if (card <= 0.dp) return 0.dp
        val band = listBand(card, chrome)
        return if (band - LIST_TOP_PAD >= listMin) LIST_TOP_PAD else 0.dp
    }

    /**
     * v54 P1: the area the rows actually compose in on a card of [card] —
     * minus the search block and [chrome], floored at 0 (a card that is gone
     * takes the list with it; the viewport's own floor cannot fix that, see
     * [listMinHeight]).
     */
    fun listBand(card: Dp, chrome: Dp = 0.dp): Dp =
        (card - searchBlockHeight(card) - chrome).coerceAtLeast(0.dp)

    /**
     * v54 P1: the same elastic step for the **desktop** column, whose block is a
     * fixed `pt-12` above the field (the wide branch's `topInset`) against the
     * mobile column's. A 1366x768 @420dpi card measured ≈118 dp there, so a
     * fixed inset left the rows ≈10 dp: the 「今天」header rendered and not one
     * row with it, over an empty column with no copy to explain itself
     * (`research/v54_verify/tB/tB_50_home.png`).
     */
    fun desktopSearchTopInset(available: Dp, inset: Dp): Dp {
        // The band the rows compose in is the card minus this block (the inset,
        // the 36 dp field, the column's 12 dp pad) and the list's own top step;
        // the inset only survives while that band can hold
        // [LIST_SHORT_HEIGHT].
        val band = available - inset - SEARCH_FIELD_HEIGHT - 12.dp - LIST_TOP_PAD
        return if (band >= LIST_SHORT_HEIGHT) inset else 0.dp
    }

    /**
     * v46 P2 「极窄下其余行仍全消失」: the mobile single column above the list
     * viewport — `topInset` (24 dp) + the 36 dp field + its 12 dp bottom pad.
     * The viewport the rows (and, while short, the plain group header) are left
     * with is the card minus this block, the projects panel and the nav.
     */
    val MOBILE_SEARCH_BLOCK: Dp = 72.dp

    /** `contentPadding pt-3`: the 12 dp step between the search block and the
     * first header — inside the list viewport, so the rows keep it, not the
     * viewport. */
    val LIST_TOP_PAD: Dp = 12.dp

    /**
     * What the utility nav (设置 / 帮助) reserves from the mobile column's
     * budget. While a rename editor is open the nav steps aside (`HomeScreen`
     * hides it): on a keyboard-up card every dp of chrome is a dp the list does
     * not get, and 72 dp is the difference between "only the editing row" and
     * "header + editing row + a sibling" (`research/v46_verify/tC/v46_20/21`).
     */
    fun utilityNavReserve(editing: Boolean): Dp = if (editing) 0.dp else UTILITY_NAV_MIN

    /**
     * v52 P0 「矮窗会话列表整片空白」: the search block as the mobile column may
     * actually spend it — [MOBILE_SEARCH_BLOCK] once the card affords it, and the
     * bare field (`SEARCH_FIELD_HEIGHT` + its 12 dp bottom pad) once it does not.
     *
     * The 24 dp `topInset` is the only elastic part of the block, and on the
     * 1440x810 @420dpi window the card measures ≈131 dp: search block (72) plus
     * the utility nav (72) is already 144 dp, so the column overflowed before
     * the rows were ever measured and the list viewport — a hard 0 dp — took
     * every dp of the shortfall. Search / Settings / Help rendered and not one
     * session row did (`research/v52_verify/tE/v52_32_home`, `v52_33_listscroll`,
     * against `v52_38_big`).
     */
    fun searchBlockHeight(available: Dp): Dp =
        if (available >= MOBILE_SEARCH_BLOCK + SESSION_ROW_HEIGHT + UTILITY_NAV_MIN) {
            MOBILE_SEARCH_BLOCK
        } else {
            SEARCH_FIELD_BLOCK
        }

    /** The search block's share as a `topInset` for `HomeSessionsColumn`. */
    fun searchTopInset(available: Dp): Dp =
        (searchBlockHeight(available) - SEARCH_FIELD_HEIGHT - 12.dp).coerceAtLeast(0.dp)

    /** `36 dp` field + the column's `12 dp` pad under it. */
    val SEARCH_FIELD_BLOCK: Dp = 48.dp

    /** `HomeSearchField`'s 36 dp field (`SEARCH_HEIGHT`). */
    val SEARCH_FIELD_HEIGHT: Dp = 36.dp

    /**
     * v52 P0: the ceiling the mobile 设置 / 帮助 nav may take at a given card.
     *
     * The nav is 72 dp of chrome that sits **below** the rows, so on a card
     * shorter than the rows' own budget it is what starves them: it either
     * overflowed the column (covering the rows, `v52_32_home`) or, once the
     * projects dropdown had been capped for it, still held a share no scroll
     * could reclaim. It keeps its [UTILITY_NAV_MIN] only while a card affords it
     * on top of the search block, the list's top pad and the list's floor; on a
     * shorter card it steps aside entirely (`HomeScreen` hides it), because a
     * settings/help row is reachable from anywhere else in the shell while a
     * 0 dp session list is a dead end (`research/v52_verify/tE/v52_32_home`).
     */
    fun utilityNavMaxHeight(available: Dp, listMin: Dp): Dp {
        if (available <= 0.dp) return 0.dp
        // v54 P1: the reserve counts the *elastic* top step ([listTopPadding]),
        // not the fixed [LIST_TOP_PAD] — the step only exists while the band can
        // afford it, and counting it unconditionally both over-reserved the nav
        // on a healthy card and under-reserved the rows on a short one.
        val keep = searchBlockHeight(available) + listTopPadding(available, listMin = listMin) + listMin
        return if (available - keep >= UTILITY_NAV_MIN) UTILITY_NAV_MIN else 0.dp
    }

    /**
     * v52 P0: what the projects panel has to leave the mobile column — the
     * search block it really spends ([searchBlockHeight]), the list's own top
     * pad, the list's floor and the utility nav at its ceiling
     * ([utilityNavMaxHeight]). The v43–v47 reserve under-counted the search
     * block by 24 dp ([SESSIONS_SEARCH_RESERVE] = 48 against a 72 dp block) and
     * the list's 12 dp top pad entirely, so the ceiling it derived was 36 dp
     * more generous than the card could honour — the difference between "the
     * list has its floor" and "the list is 0 dp" on a short card.
     */
    fun mobileColumnReserve(
        available: Dp,
        listMin: Dp = listMinHeight(available),
        editing: Boolean = false,
    ): Dp {
        // v54 P1: the elastic top step ([listTopPadding]) replaces the fixed
        // [LIST_TOP_PAD] — see [utilityNavMaxHeight].
        val top = listTopPadding(available, listMin = listMin)
        if (editing) {
            return searchBlockHeight(available) + top + listMin
        }
        return searchBlockHeight(available) + top + listMin +
            utilityNavMaxHeight(available, listMin)
    }

    /**
     * The rows' own viewport on the mobile single column: the card minus the
     * search block, the projects panel and the nav reserve. Floored at 0 — the
     * list viewport's own `heightIn(min = …)` floor still applies on top.
     */
    fun mobileRowsViewport(card: Dp, projectsHeight: Dp, editing: Boolean): Dp =
        (card - projectsHeight - searchBlockHeight(card) - utilityNavReserve(editing) -
            listTopPadding(card, projectsHeight + utilityNavReserve(editing)))
            .coerceAtLeast(0.dp)

    /**
     * v43 P1: the ceiling the mobile 项目 dropdown is allowed to grow to.
     *
     * The dropdown is a plain, unweighted `Column` of fixed-height rows, so on a
     * narrow phone with the keyboard up it claimed more height than the card
     * had left, and the sessions list's `weight(1f)` share was clamped to 0 —
     * a dead end with no scroll recovery ([LIST_MIN_HEIGHT]).
     *
     * **v44 P1**: the floor alone was not enough. A card measured *with the
     * keyboard up* is short, and on a 1080×2424 phone the card is ≈438 dp
     * then; the 40 % fraction (175 dp) still won over the residual, leaving the
     * sessions list ≈123 dp — one group header, its 16 dp pad and exactly one
     * row. Opening the rename editor therefore made the whole list collapse to
     * the edited row plus its header at **every** width, and at
     * `wm size 640x1500` + 420 dpi it was not enough for even that, so the
     * field being edited was off screen (`research/v44_verify/tC/v44_13/14/18/
     * 33/34`, `v44_16_after_esc.png` — Esc restored the list by closing the
     * editor, which is what took the keyboard down).
     *
     * Three ceilings are combined:
     *  - the fractional one, so on a **tall** card the projects keep the small
     *    share they are a panel of;
     *  - the residual one, `available - [reserved]`, so whatever the projects
     *    give up is at least the sessions column's floor plus the utility nav;
     *  - the short-card share, `available * [SESSIONS_LIST_MIN_FRACTION]`, which
     *    only applies below [SHORT_CARD_HEIGHT]: while the user is typing the
     *    sessions list is the surface being edited, so the panel may not keep
     *    40 % of what is left — it gets at most the rest of a half.
     *
     * The result is what callers pass as `heightIn(max = ...)`; the dropdown
     * additionally scrolls, so projects beyond it stay reachable.
     */
    fun projectsDropdownMaxHeight(
        available: Dp,
        listMin: Dp = listMinHeight(available),
        // v52 P0: the reserve is derived from what the column really spends
        // ([mobileColumnReserve]) rather than the v43–v47 constant that
        // under-counted the search block and the list's top pad by 36 dp.
        reserved: Dp = mobileColumnReserve(available, listMin),
        fraction: Float = PROJECTS_DROPDOWN_MAX_FRACTION,
    ): Dp {
        if (available <= 0.dp) return 0.dp
        val byFraction = available * fraction
        val byFloor = (available - reserved).coerceAtLeast(0.dp)
        val listShare = if (available < SHORT_CARD_HEIGHT) available * SESSIONS_LIST_MIN_FRACTION else 0.dp
        val byListShare = (available - reserved - listShare).coerceAtLeast(0.dp)
        return minOf(byFraction, byFloor, byListShare).coerceAtLeast(0.dp)
    }

    /**
     * Whether the row still prints its project label. Upstream hides it while a
     * project is selected (`session.showProjectName`, `session/controller.tsx:256`);
     * at the width floor the two labels cannot both read, so the secondary one
     * goes the same way.
     */
    fun showProjectLabel(narrow: Boolean): Boolean = !narrow

    /**
     * The width the row's labels cell is measured with, mirroring what the
     * `Row` does: the row minus its paddings, the avatar slot, both 8 dp gaps and
     * the time cell — the cell capped at [timeMax] and never wider than what it
     * actually needs ([timeWidth]).
     */
    fun sessionLabelsWidth(rowWidth: Dp, narrow: Boolean, timeWidth: Dp): Dp {
        val fixed = SESSION_START + sessionEnd(narrow) + SESSION_LEADING +
            SESSION_GAP * 2 + minOf(timeWidth, timeMax(narrow))
        return (rowWidth - fixed).coerceAtLeast(0.dp)
    }

    /**
     * The width the **title** itself gets: [sessionLabelsWidth] while a project
     * label shares the cell (`TITLE_WEIGHT` of it), all of it in the compact
     * shape, where the project label has given way.
     */
    fun sessionTitleWidth(rowWidth: Dp, narrow: Boolean, timeWidth: Dp): Dp {
        val labels = sessionLabelsWidth(rowWidth, narrow, timeWidth)
        return if (showProjectLabel(narrow)) labels * TITLE_WEIGHT else labels
    }

    // -------------------------------------------------------- project row (h-7)

    /** `px-1.5` / `pr-16`-style insets: 6 dp leading, 2 dp trailing. */
    val PROJECT_START: Dp = 6.dp
    val PROJECT_END: Dp = 2.dp

    /** `ProjectAvatar`'s 18 dp square. */
    val PROJECT_AVATAR: Dp = 18.dp

    /** `icon-button-v2[size=large]` = 28 dp, the trailing cluster's unit. */
    val PROJECT_ACTION: Dp = 28.dp

    /** The row's own 8 dp gap between avatar / name / actions. */
    val PROJECT_GAP: Dp = 8.dp

    /**
     * Whether the row still draws its ✎ (新建会话) and ✕ (关闭) beside the ⋯.
     * All three are the row menu's own entries — the ✎ is
     * `command.session.new` (`home/projects/view.tsx:733-735`) and the ✕ is
     * `Close` (`:756-758`) — so at the width floor the ⋯ (the only *visible*
     * menu trigger, `SessionImportEntryPointsTest`) is the one that stays.
     */
    fun showProjectActions(narrow: Boolean): Boolean = !narrow

    /** The width the project **name** is measured with, for [ProjectRow]. */
    fun projectNameWidth(rowWidth: Dp, narrow: Boolean): Dp {
        val actions = if (showProjectActions(narrow)) PROJECT_ACTION * 3 + PROJECT_GAP * 3
        else PROJECT_ACTION + PROJECT_GAP
        val fixed = PROJECT_START + PROJECT_END + PROJECT_AVATAR + PROJECT_GAP + actions
        return (rowWidth - fixed).coerceAtLeast(0.dp)
    }
}
