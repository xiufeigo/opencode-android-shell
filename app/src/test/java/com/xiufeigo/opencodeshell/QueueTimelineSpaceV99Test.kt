package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v99 P0 「队列条把时间线压到 16px 且 100% 覆盖」: the queued-prompts bar must
 * not be able to take the timeline's readable band out of the pane.
 *
 * The device reading (`research/v97_verify/5562_v98/REPORT_SWEEPD3.md` §2,
 * emulator-5562 `Small_Desktop`, 1366x768 @160, content `0..720`, IME top
 * `y=342`):
 *
 * ```
 * ① streaming, no queue bar    timeline [0,162][1366,350]  h=188
 * ② streaming + 2 queued,      timeline [0,162][1366,350]  h=188   bar y=154..248
 *    composer 1 line
 * ③ streaming + 2 queued,      timeline [0,162][1366,178]  h=16    bar y=154..248
 *    composer 2 lines                                    ← 16 px, 100 % covered
 * ```
 *
 * ②→③ changes exactly one thing — the composer folding to a second line
 * (+48 px) — and the timeline loses **172 px**. The report's own arithmetic
 * leaves **124 px to the bar itself**: the bar is not charged a share of the
 * pane, it is measured at its full intrinsic height on *every* pane and the
 * whole host (bar + card) is what the pane subtracts from the timeline
 * ([SessionPaneLayout.viewportHeightPx]).
 *
 * These tests pin the budget as a **pure truth table** — total height, keyboard
 * height, composer line count, queue row count in, a timeline share out — so
 * the failing frame is replayable as data rather than only as a screenshot, plus
 * the structural pins that keep the composer's card and the bar from drifting
 * back into one number.
 */
class QueueTimelineSpaceV99Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun sessionScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private fun composer() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    private fun queuePanel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ComposerQueuePanel.kt")

    private fun paneLayout() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt")

    // ------------------------------------------------ the device frame, as data

    /** 1366x768 @160: the app's content area, 720 px, and the keyboard's 378 px. */
    private val contentHeight = 720.dp
    private val imeHeight = 378.dp

    /**
     * The one assertion the device could not make: on the ③ frame the timeline
     * measured **16 px** and 100 % of it sat under the bar. Replayed here, the
     * same four numbers must leave a real band.
     */
    @Test
    fun theFailingFrameLeavesATimelineBand() {
        val share = SessionPaneLayout.timelineShare(
            totalHeight = contentHeight,
            imeHeight = imeHeight,
            composerLines = 2,
            queueRows = 2,
        )
        assertTrue(
            "③ replayed: timeline share $share must be a real band, not the 16 px the device read",
            share >= SessionPaneLayout.TIMELINE_FLOOR_UNDER_QUEUE,
        )
        assertTrue(
            "the 16 px sliver must not be representable: got $share",
            share > 16.dp,
        )
        assertTrue(
            "queueFloorHolds must hold on the failing frame",
            SessionPaneLayout.timelineFloorHolds(contentHeight, imeHeight, 2, 2),
        )
    }

    /**
     * ①→②→③: the bar appearing is what took the timeline down, so adding queued
     * rows must cost the messages **nothing at all** on a pane that can pay for
     * them. The bar's rows are the bar's business; the band is not for sale.
     */
    @Test
    fun theBandsHeightIsIndependentOfHowManyPromptsAreQueued() {
        val withNoBar = SessionPaneLayout.timelineShare(contentHeight, imeHeight, 2, 0)
        for (rows in 1..20) {
            val share = SessionPaneLayout.timelineShare(contentHeight, imeHeight, 2, rows)
            assertTrue(
                "rows=$rows: the timeline fell to $share (no-bar frame: $withNoBar) — " +
                    "the bar grew into the band",
                share >= SessionPaneLayout.TIMELINE_FLOOR_UNDER_QUEUE,
            )
        }
        // The floor is the invariant, not equality: a bar *may* cost the band its
        // own share where the pane is genuinely too short, but never the floor.
        assertTrue(
            "a 20-row bar on a roomy pane must not squeeze the composer to nothing",
            SessionPaneLayout.timelineShare(contentHeight, imeHeight, 2, 20) > 0.dp,
        )
    }

    /**
     * The bar is the **elastic** band: on a pane too short for bar + card +
     band it is the bar that shrinks (and then scrolls), never the band's floor
     * and never the card's tool row (v51).
     */
    @Test
    fun theBarYieldsBeforeTheBandAndBeforeTheComposer() {
        val pane = 180.dp // card 56 + band 64 + 60 dp spare: a two-row bar does not fit
        val intrinsic = SessionPaneLayout.queueBarIntrinsicHeight(2)
        assertTrue(
            "the fixture must actually be tight: intrinsic $intrinsic vs pane $pane",
            intrinsic > SessionPaneLayout.queueBarCeiling(pane),
        )
        assertEquals(
            "the bar must be capped exactly at the room the pane can pay",
            pane - SessionPaneLayout.TIMELINE_FLOOR_UNDER_QUEUE -
                SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT,
            SessionPaneLayout.queueBarCeiling(pane),
        )
        assertTrue(
            "the capped bar must be shorter than its intrinsic height",
            SessionPaneLayout.queueBarHeight(2, pane) < intrinsic,
        )
        assertEquals(
            "card + capped bar + band must still fit the pane exactly",
            pane,
            SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT +
                SessionPaneLayout.queueBarHeight(2, pane) +
                SessionPaneLayout.TIMELINE_FLOOR_UNDER_QUEUE,
        )
    }

    /** The bar's ceiling is a *ceiling*: it never goes negative, never above upstream's 148 dp. */
    @Test
    fun theBarCeilingIsBoundedAtBothEnds() {
        for (step in 0..286) {
            val pane = (step * 7).dp
            val ceiling = SessionPaneLayout.queueBarCeiling(pane)
            assertTrue("pane=$pane gave ceiling $ceiling", ceiling.value >= 0f)
            assertTrue(
                "pane=$pane gave ceiling $ceiling, above the upstream max-h-[148px]",
                ceiling <= SessionPaneLayout.QUEUE_BAR_MAX_HEIGHT,
            )
        }
        assertEquals(0.dp, SessionPaneLayout.queueBarCeiling(0.dp))
        assertEquals(
            SessionPaneLayout.QUEUE_BAR_MAX_HEIGHT,
            SessionPaneLayout.queueBarCeiling(2000.dp),
        )
    }

    /**
     * The bar's appearing/disappearing **timing** is not touched by this fix: a
     * pane with no queued prompts draws no bar at all, and the intrinsic height
     * of zero rows is zero rather than a chrome-sized sliver.
     */
    @Test
    fun noQueuedPromptsMeansNoBarHeight() {
        assertEquals(0.dp, SessionPaneLayout.queueBarIntrinsicHeight(0))
        assertEquals(0.dp, SessionPaneLayout.queueBarHeight(0, 720.dp))
        assertEquals(0.dp, SessionPaneLayout.queueBarHeight(-3, 720.dp))
        assertTrue(
            "the bar must still be able to show at all on a normal pane",
            SessionPaneLayout.queueBarHeight(2, 720.dp) > 0.dp,
        )
        assertEquals(
            "one row + the panel's 6 dp pad + its 12 dp -mb-3 overlap",
            SessionPaneLayout.QUEUE_ROW_HEIGHT + SessionPaneLayout.QUEUE_BAR_CHROME,
            SessionPaneLayout.queueBarIntrinsicHeight(1),
        )
    }

    /**
     * The truth table, swept: **every** pane this shell can hand the session
     * keeps the band while the pane can pay for card + band at all. The report's
     * ③ frame is one point on this grid, not a special case.
     */
    @Test
    fun everyPaneThatCanPayForTheBandKeepsIt() {
        var checked = 0
        for (totalStep in 0..55) {
            val total = (300 + totalStep * 20).dp
            for (imeStep in 0..15) {
                val ime = (imeStep * 40).dp
                for (lines in 1..3) {
                    for (rows in 0..12) {
                        val floor = SessionPaneLayout.timelineFloorHolds(total, ime, lines, rows)
                        if (!floor) {
                            val pane = (total.value - ime.value).coerceAtLeast(0f).dp
                            val card = SessionPaneLayout.composerFloorFor(lines)
                            // The only panes allowed to miss the floor are the ones
                            // that cannot pay card + band in the first place.
                            assertTrue(
                                "total=$total ime=$ime lines=$lines rows=$rows: pane $pane " +
                                    "cannot pay card $card + band " +
                                    "${SessionPaneLayout.TIMELINE_FLOOR_UNDER_QUEUE}, so the " +
                                    "floor cannot be claimed",
                                (pane.value - (card + SessionPaneLayout.TIMELINE_FLOOR_UNDER_QUEUE)
                                    .value) < 0f,
                            )
                        }
                        checked++
                    }
                }
            }
        }
        assertTrue("the sweep must actually cover something", checked > 1000)
    }

    // -------------------------------------------------------- structure pins

    /**
     * The floor has to exist **on the timeline**, and it has to be a floor: a
     * `weight(1f)` alone hands out whatever is left, which is 0 on a pane the
     * bar has already eaten.
     */
    @Test
    fun theTimelinePaneCarriesAMinimumHeightConstraint() {
        val screen = sessionScreen()
        assertTrue(
            "the timeline pane must keep a weight + a min-height floor",
            screen.contains(".weight(1f)") && screen.contains(".heightIn(min = viewportMinHeight)"),
        )
        assertTrue(
            "that floor must be derived, so it gives way instead of overflowing",
            screen.contains("SessionPaneLayout.timelineMinHeight("),
        )
        assertNotNull(SessionPaneLayout.timelineMinHeight(720.dp))
    }

    /**
     * The bar must take its ceiling as a **parameter** driven by the pane's
     * budget. A hard-coded 148 dp inside the panel is the defect: the panel is
     * the host's first child, so it is measured before the card and grows to its
     * full intrinsic height on every pane.
     */
    @Test
    fun theBarTakesItsCeilingFromThePaneNotFromAConstant() {
        val panel = queuePanel()
        assertTrue(
            "the panel must take its ceiling as a parameter",
            panel.contains("maxHeight: Dp = SessionPaneLayout.QUEUE_BAR_MAX_HEIGHT"),
        )
        assertTrue(
            "the LazyColumn must cap itself at the caller's ceiling",
            panel.contains(".heightIn(max = maxHeight)"),
        )
        assertFalse(
            "the 148 dp constant must not be re-inlined in the panel (v99 P0)",
            panel.contains("heightIn(max = 148.dp)"),
        )
        assertTrue(
            "the bar must stay scrollable so a capped bar keeps its rows tappable",
            panel.contains("LazyColumn("),
        )
        assertTrue(
            "the row actions must survive the cap — Steer stays a clickable",
            panel.contains("R.string.session_queue_steer") &&
                panel.contains("R.string.session_queue_remove"),
        )

        val composerSource = composer()
        assertTrue(
            "Composer must forward the pane's ceiling to the panel",
            composerSource.contains("maxHeight = queueBarMaxHeight"),
        )
        assertTrue(
            "Composer's parameter must default to the upstream 148 dp",
            composerSource.contains("queueBarMaxHeight: Dp = SessionPaneLayout.QUEUE_BAR_MAX_HEIGHT"),
        )

        val screen = sessionScreen()
        assertTrue(
            "the screen must pass the pane-derived ceiling",
            screen.contains("queueBarMaxHeight = SessionPaneLayout.queueBarCeiling("),
        )
        assertTrue(
            "the ceiling must be budgeted against the card the composer will " +
                "actually lay out, or the difference is the messages' dp",
            screen.contains("composerFloor = if (minimalComposer)"),
        )
    }

    /**
     * The bar must be derived from the **pane**, never from the host it is about
     * to be measured into — otherwise the bar argues with the budget it is
     * charged to (the v57 oscillation this file's neighbours already fixed once).
     */
    @Test
    fun theBarCeilingIsDerivedFromThePaneNotFromTheHost() {
        val screen = sessionScreen()
        assertFalse(
            "the ceiling must not be derived from the composer's own measured height",
            screen.contains("queueBarCeiling(paneHeight, composerHeight"),
        )
        assertTrue(
            "the ceiling must come from the pane",
            screen.contains("SessionPaneLayout.queueBarCeiling(") &&
                screen.contains("paneHeight,"),
        )
    }

    /**
     * The bar's priority is the whole fix, so it is pinned as an ordering: the
     * band and the card are paid **before** the bar, and the bar's cap is
     * exactly what is left of the pane.
     */
    @Test
    fun theBandAndTheComposerArePaidBeforeTheBar() {
        val source = paneLayout()
        assertTrue(
            "queueBarCeiling must subtract the timeline floor and the composer floor",
            source.contains("val room = pane - timelineFloor - composerFloor"),
        )
        assertTrue(
            "the floor must be the timeline's own item band",
            source.contains("val TIMELINE_FLOOR_UNDER_QUEUE: Dp = TIMELINE_MIN_CONTENT"),
        )
        assertTrue(
            "the composer floor must be the form that keeps its tool row (v51)",
            source.contains("composerFloor: Dp = COMPOSER_MINIMAL_HEIGHT"),
        )
    }

    // ------------------------------------------------ v99-b: the bar's own floor
    //
    // v99 fixed the *band* (the bar stopped eating it) and introduced a bar that
    // could be drawn shorter than one row. The device round then measured the
    // consequence: queue rows reported at 2–3 px and `Steer` / `×` absent from
    // the accessibility tree (REPORT_U1 §3 判据四), breaking a PASS the queue
    // actions round had earned (REPORT_QUEUE_ACTIONS §3.1: `Steer`
    // [1263,181][1302,224], `×` [1302,154][1350,202], ~32 dp rows). A viewport
    // shorter than a row crops the row, and a cropped node is not in the tree.
    //
    // The floor therefore has to be expressed at the **row**, not only at the
    // bar: `queueBarViewportFloor` is the row's, applied as `heightIn(min = …)`
    // on the panel's LazyColumn, and `queueBarCeiling` refuses to hand the bar a
    // ceiling it can only use to crop a row.

    /** 1366x600 @160: the short window the device round could not read a queue in. */
    private val shortContentHeight = 552.dp
    private val shortImeHeight = 291.dp

    /**
     * The ceiling's **lower** end: never a value that can only crop a row. Swept
     * over every pane this shell can hand the session, the bar's claim on the
     * pane is either nothing at all or at least one whole row plus its chrome.
     *
     * This is the assertion the previous version could not make: it clamped the
     * ceiling to `0.dp`, so any pane in the window
     * `timelineFloor + composerFloor … + QUEUE_BAR_MIN_HEIGHT` was handed a
     * ceiling in `(0, 50)` — a bar drawn shorter than the row inside it.
     */
    @Test
    fun theBarIsNeverHandedACeilingThatCanOnlyCropARow() {
        for (step in 0..400) {
            val pane = (step * 5).dp
            for (card in listOf(
                SessionPaneLayout.composerFloorFor(1),
                SessionPaneLayout.composerFloorFor(2),
                SessionPaneLayout.composerFloorFor(3),
            )) {
                val ceiling = SessionPaneLayout.queueBarCeiling(pane, composerFloor = card)
                assertTrue("pane=$pane gave a negative ceiling $ceiling", ceiling.value >= 0f)
                assertTrue(
                    "pane=$pane card=$card: ceiling $ceiling is a cropped bar — it must be " +
                        "0 (no bar) or at least one whole row " +
                        "(${SessionPaneLayout.QUEUE_BAR_MIN_HEIGHT})",
                    ceiling == 0.dp || ceiling >= SessionPaneLayout.QUEUE_BAR_MIN_HEIGHT,
                )
            }
        }
        // The exact boundary: one dp under the whole-row line the bar declines,
        // one dp on it the bar is drawn.
        val boundary = SessionPaneLayout.TIMELINE_FLOOR_UNDER_QUEUE +
            SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT + SessionPaneLayout.QUEUE_BAR_MIN_HEIGHT
        assertEquals(
            "one dp short of a whole row must not draw a cropped bar",
            0.dp,
            SessionPaneLayout.queueBarCeiling(boundary - 1.dp),
        )
        assertEquals(
            "exactly one whole row of room must be handed over",
            SessionPaneLayout.QUEUE_BAR_MIN_HEIGHT,
            SessionPaneLayout.queueBarCeiling(boundary),
        )
    }

    /**
     * The bar's height, over the queue depths the device round reported
     * (2 / 5 / 11 / 23), is either its intrinsic height or a value that still
     * holds one whole row. There is no in-between: a bar between 0 and
     * [SessionPaneLayout.QUEUE_BAR_MIN_HEIGHT] is the regression itself.
     */
    @Test
    fun aBarWithRowsIsEitherWholeOrNotDrawn() {
        for (rows in listOf(1, 2, 5, 11, 23)) {
            for (step in 0..300) {
                val pane = (step * 5).dp
                val bar = SessionPaneLayout.queueBarHeight(rows, pane)
                assertTrue(
                    "rows=$rows pane=$pane: bar $bar is neither 0 nor a whole row",
                    bar == 0.dp || bar >= SessionPaneLayout.QUEUE_BAR_MIN_HEIGHT,
                )
                assertTrue(
                    "rows=$rows pane=$pane: bar $bar must never exceed the pane's own " +
                        "intrinsic height ${SessionPaneLayout.queueBarIntrinsicHeight(rows)}",
                    bar <= SessionPaneLayout.queueBarIntrinsicHeight(rows),
                )
            }
        }
    }

    /**
     * **The row floor, at every queue depth**: whatever the queue length, the
     * viewport the rows scroll inside is at least one whole `h-8` row, so the
     * `Steer` / **×** targets inside it are laid out inside the viewport and
     * reach the accessibility tree. The depth buys scrolling, never squashing.
     */
    @Test
    fun theRowKeepsItsHeightAtEveryQueueDepth() {
        for (rows in listOf(2, 5, 11, 23)) {
            assertEquals(
                "rows=$rows: the viewport floor must be exactly one `h-8` row",
                SessionPaneLayout.QUEUE_ROW_HEIGHT,
                SessionPaneLayout.queueBarViewportFloor(rows),
            )
            assertTrue(
                "rows=$rows: a row floor below the upstream h-8 row is what REPORT_U1 read " +
                    "as 2–3 px rows",
                SessionPaneLayout.queueBarViewportFloor(rows) >= 32.dp,
            )
        }
        // Nothing to show, nothing to hold open.
        assertEquals(0.dp, SessionPaneLayout.queueBarViewportFloor(0))
        assertEquals(0, SessionPaneLayout.queueBarVisibleRows(0, 148.dp))

        // Depth converts into hidden rows, not thin rows: on a roomy pane a
        // 23-row bar shows its own ceiling worth of whole rows and scrolls.
        for (rows in listOf(2, 5, 11, 23)) {
            val bar = SessionPaneLayout.queueBarHeight(rows, 342.dp)
            val shown = SessionPaneLayout.queueBarVisibleRows(rows, bar)
            assertTrue(
                "rows=$rows: a drawn bar must show at least one whole row (bar=$bar)",
                shown >= 1,
            )
            assertTrue(
                "rows=$rows: a bar cannot show more whole rows than it has (bar=$bar, shown=$shown)",
                shown <= rows,
            )
            // …and the rows it shows are the full row height, so each one carries
            // its `Steer` / **×` hit targets at the height REPORT_QUEUE_ACTIONS
            // §3.1 measured.
            assertTrue(
                "rows=$rows: shown=$shown whole rows must be shown at full height",
                shown * SessionPaneLayout.QUEUE_ROW_HEIGHT.value <=
                    (bar - SessionPaneLayout.QUEUE_BAR_CHROME).coerceAtLeast(
                        SessionPaneLayout.QUEUE_ROW_HEIGHT,
                    ).value,
            )
        }
    }

    /**
     * Both floors at once, on the two windows the device round used: the band
     * keeps ≥ its own floor **and** the bar keeps a whole row, at 2 / 5 / 11 /
     * 23 queued prompts. The previous version could only assert the first, which
     * is how the second broke unnoticed.
     */
    @Test
    fun bothFloorsHoldAtEveryQueueDepthOnBothWindows() {
        val windows = listOf(
            "1366x768" to (contentHeight to imeHeight),
            "1366x600" to (shortContentHeight to shortImeHeight),
        )
        for ((name, window) in windows) {
            val (total, ime) = window
            for (lines in 1..2) {
                val card = SessionPaneLayout.composerFloorFor(lines)
                for (rows in listOf(2, 5, 11, 23)) {
                    val label = "$name lines=$lines rows=$rows"
                    val pane = (total - ime).coerceAtLeast(0.dp)
                    // The bar is charged the *same* card the band is, or the two
                    // halves of the budget do not add up to the pane.
                    val bar = SessionPaneLayout.queueBarHeight(rows, pane, composerFloor = card)
                    val timeline = SessionPaneLayout.timelineShare(total, ime, lines, rows)

                    assertTrue(
                        "$label: the band must keep its floor (timeline=$timeline, pane=$pane)",
                        timeline >= SessionPaneLayout.TIMELINE_FLOOR_UNDER_QUEUE,
                    )
                    assertTrue(
                        "$label: the timeline, card and bar must fit the pane " +
                            "(timeline=$timeline + card=$card + bar=$bar vs pane=$pane)",
                        timeline + card + bar <= pane,
                    )
                    assertTrue(
                        "$label: a drawn bar must hold a whole row (bar=$bar)",
                        bar == 0.dp || bar >= SessionPaneLayout.QUEUE_BAR_MIN_HEIGHT,
                    )
                    if (bar > 0.dp) {
                        assertTrue(
                            "$label: a drawn bar must show at least one whole row (bar=$bar)",
                            SessionPaneLayout.queueBarVisibleRows(rows, bar) >= 1,
                        )
                    }
                }
            }
        }
    }

    /**
     * The short window (`1366x600`, keyboard up) with a deep queue is the case
     * the previous round left unreadable. Pinned as numbers, because "it
     * scrolls" is not a claim a device screenshot can make.
     */
    @Test
    fun theShortWindowPaysTheBandAndTheFirstQueueRow() {
        val pane = shortContentHeight - shortImeHeight
        for (lines in 1..2) {
            val card = SessionPaneLayout.composerFloorFor(lines)
            for (rows in listOf(2, 5, 11, 23)) {
                val timeline = SessionPaneLayout.timelineShare(
                    totalHeight = shortContentHeight,
                    imeHeight = shortImeHeight,
                    composerLines = lines,
                    queueRows = rows,
                )
                assertTrue(
                    "1366x600 lines=$lines rows=$rows: the band is gone again ($timeline)",
                    timeline >= SessionPaneLayout.TIMELINE_FLOOR_UNDER_QUEUE,
                )
                val bar = SessionPaneLayout.queueBarHeight(rows, pane, composerFloor = card)
                assertTrue(
                    "1366x600 lines=$lines rows=$rows: the bar must still be drawable whole " +
                        "on a short window (pane=$pane, bar=$bar)",
                    bar >= SessionPaneLayout.QUEUE_BAR_MIN_HEIGHT,
                )
                assertTrue(
                    "1366x600 lines=$lines rows=$rows: the bar and the band and the card must " +
                        "fit the pane ($timeline + $card + $bar vs $pane)",
                    timeline + card + bar <= pane,
                )
            }
        }
        // The 2-line short window is the tightest: pane 261 − card 116 − band 64
        // leaves exactly one whole row for the bar, which is the whole point.
        assertEquals(
            261.dp - SessionPaneLayout.COMPOSER_MIN_HEIGHT -
                SessionPaneLayout.TIMELINE_FLOOR_UNDER_QUEUE,
            SessionPaneLayout.queueBarCeiling(261.dp, composerFloor = SessionPaneLayout.COMPOSER_MIN_HEIGHT),
        )
    }

    // ------------------------------------------------ v99-b structure pins

    /**
     * The row floor has to be **in the panel**, on the scroll viewport, and
     * under the caller's cap so the budget still wins. A floor written only in
     * the pane's arithmetic is not a floor the layout honours.
     */
    @Test
    fun thePanelHoldsTheViewportAtOneWholeRow() {
        val panel = queuePanel()
        val floor = ".heightIn(min = SessionPaneLayout.queueBarViewportFloor(rows.size))"
        val cap = ".heightIn(max = maxHeight)"
        assertTrue(
            "the LazyColumn viewport must carry the one-row floor, or a deep queue squashes " +
                "its rows (REPORT_U1 判据四)",
            panel.contains(floor),
        )
        assertTrue(
            "the floor must be applied under the cap so the pane budget still wins",
            panel.indexOf(floor) in 0 until panel.indexOf(cap),
        )
        assertFalse(
            "the floor must be derived from the row count, not hard-coded",
            panel.contains("heightIn(min = 32.dp)"),
        )
        // The row itself is an exact height: it cannot be squeezed by a parent.
        val exactRow = ".height(ROW_HEIGHT)"
        assertTrue(
            "queue rows are exact-height, so only the viewport can ever be short",
            panel.contains(exactRow),
        )
        // …and that exact height must not be a *weighted* one. `weight` hands
        // out what is left after everyone else, and on a short pane what is left
        // is 0 — a `weight`ed row is the one shape that turns "the bar is short"
        // into "the rows are 2–3 px". (The row's inner text legitimately weights
        // horizontally; that is not the vertical claim being pinned here.)
        val rowChain = panel.substring(
            panel.indexOf(exactRow).let { (it - 240).coerceAtLeast(0) },
            panel.indexOf(exactRow) + exactRow.length,
        )
        assertFalse(
            "the row's own height must be exact, not weight-allocated: $rowChain",
            rowChain.contains("weight("),
        )
        assertFalse(
            "the bar must not fill the host's height, or the pane budget stops being a budget",
            panel.contains("fillMaxHeight"),
        )
        assertTrue(
            "the bar must stay a LazyColumn, which is what turns a deep queue into scrolling",
            panel.contains("LazyColumn("),
        )

        val source = paneLayout()
        assertTrue(
            "the row floor must be derived from the upstream h-8 row",
            source.contains("fun queueBarViewportFloor(rows: Int): Dp ="),
        )
        assertTrue(
            "the ceiling must decline a room it can only use to crop a row",
            source.contains("if (room < QUEUE_BAR_MIN_HEIGHT) return 0.dp"),
        )
    }

    /**
     * The v99 sweep is re-asserted here with the new lower bound in place: the
     * band survives every pane that can pay for it, and the bar never claims
     * more than its ceiling. (Same claim as
     * [everyPaneThatCanPayForTheBandKeepsIt], plus the bar's own bound.)
     */
    @Test
    fun theBarNeverExceedsItsCeilingAnywhereInTheSweep() {
        for (totalStep in 0..55) {
            val total = (300 + totalStep * 20).dp
            for (imeStep in 0..15) {
                val ime = (imeStep * 40).dp
                for (lines in 1..3) {
                    val card = SessionPaneLayout.composerFloorFor(lines)
                    for (rows in 0..23) {
                        val pane = (total - ime).coerceAtLeast(0.dp)
                        val bar = SessionPaneLayout.queueBarHeight(
                            rows,
                            pane,
                            composerFloor = card,
                        )
                        assertTrue(
                            "total=$total ime=$ime lines=$lines rows=$rows: bar $bar exceeds its " +
                                "ceiling ${SessionPaneLayout.queueBarCeiling(pane, composerFloor = card)}",
                            bar <= SessionPaneLayout.queueBarCeiling(pane, composerFloor = card),
                        )
                        assertTrue(
                            "total=$total ime=$ime lines=$lines rows=$rows: a drawn bar must " +
                                "hold a whole row (bar=$bar)",
                            bar == 0.dp || bar >= SessionPaneLayout.QUEUE_BAR_MIN_HEIGHT,
                        )
                        if (bar > 0.dp) {
                            assertTrue(
                                "total=$total ime=$ime lines=$lines rows=$rows: a drawn bar must " +
                                    "still show a whole row",
                                SessionPaneLayout.queueBarVisibleRows(rows, bar) >= 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
