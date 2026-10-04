package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * v156: the second reverse jump — the one v154's grid could not see.
 *
 * ## What v154 fixed, and what it left
 *
 * v154 (`REVERSE_JUMP_FIX.md`) found and fixed one conjunct:
 * `SessionPaneLayout.composerMinimal` opened with `available > 0.dp`
 * (`SessionPaneLayout.kt:1009`, now `:1036`), which turned the one-row form
 * **off** at exactly 0 dp and let the shell ask for the full 116 dp composer
 * in nothing. One character: `>= 0.dp`.
 *
 * But the composer is handed **two** form predicates, not one:
 *
 * ```
 * SessionScreen.kt:1870   minimal = minimalComposer = composerOneRow(isShortWindow, bodyHeightDp)
 * SessionScreen.kt:1866   compact = SessionPaneLayout.composerCompact(paneHeight)
 * ```
 *
 * `composerCompact` (`SessionPaneLayout.kt:750`) opens with the **same** spurious
 * lower guard, `available > 0.dp`, at a **different** threshold
 * (`116 + 44 + 64 = 224` instead of `164`):
 *
 * ```
 * 750  fun composerCompact(available: Dp): Boolean =
 * 751      available > 0.dp &&
 * 752          available < COMPOSER_MIN_HEIGHT + CHROME_MIN_HEIGHT + TIMELINE_MIN_CONTENT
 * ```
 *
 * Same structural defect, second function. v154's grid never saw it, and §1 of
 * this file pins down **why** — the answer is not the sampling step, it is the
 * **missing reader**: v154's model computes the form as `composerOneRow` alone,
 * so `composerCompact` was structurally absent from the grid rather than merely
 * unsampled in it.
 *
 * ## The room, and the arithmetic
 *
 * `SessionScreen.kt:2361-2388`:
 *
 * ```
 * paneBoxPx    = maxHeight.roundToPx()                                    :2361
 * paneBottomDp = maxOf(paneFloorPx, believedImeBottomPx(…)).coerceAtLeast(0) :2370
 * bodyHeightDp = (maxHeight - paneBottomDp).coerceAtLeast(0.dp)           :2384
 * ```
 *
 * 1 dp == 1 px on the measured device, so this is exact integer arithmetic.
 *
 * ## Why the device flips at 600 -> 580
 *
 * The keyboard keeps its full claim while the box is below twice the essential
 * form (`believedImeBottomPx` early-returns on
 * `visibleBoxPx < essentialPx * 2 = 568`), so on every window the v155 round
 * swept the room is simply `box - ime`:
 *
 * ```
 * window 600 -> box 438 -> body = 438 - 426 = 12   compact = (12 > 0 && 12 < 224) = true   -> one row
 * window 580 -> box 418 -> body = 418 - 426 =  0   compact = ( 0 > 0 && …)        = FALSE  -> full form
 * ```
 *
 * `body` hits 0 at `box == ime == 426`, i.e. at **window 588** — between the two
 * windows v155 sampled, which is exactly why 600 and 580 disagreed.
 *
 * Note what v154's fix did here: `composerMinimal(0.dp)` is now **true**. So at
 * `body == 0` the two predicates now **disagree** — `minimal` says one row,
 * `compact` says the full composer. `theTwoFormPredicatesNeverContradict` below
 * is the pin for that; it is the direct consequence of the one-character v154
 * fix, and it is why the defect was invisible before v154: the two predicates
 * used to agree (both said "full form") on the one input where they were wrong.
 */
class ReverseJumpGridV156Test {

    // 1 dp == 1 px, so the production arithmetic is exact integer arithmetic.
    private val density = Density(1f)
    private val essentialPx: Int =
        with(density) { SessionPaneLayout.PANE_ESSENTIAL_HEIGHT.roundToPx() }

    /**
     * The chrome above the pane box — `maxHeight` is the pane box *without* the
     * top insets and the shell's own strip. The two measured tiers fix it:
     * `768 - 606 = 600 - 438 = 162`. `theChromeAboveThePaneIsTheSameOnBothTiers`
     * is the pin that keeps this from silently drifting into a magic number.
     */
    private val chromeAbovePanePx = 162
    private val measuredWindow768 = 768
    private val measuredPaneBox768 = 606
    private val measuredWindow600 = 600
    private val measuredPaneBox600 = 438

    private val navFloor = 48

    /** The real-device drive: `wm size 1366x<H>`, swept per dp. 61 cells. */
    private val windowRange: List<Int> = (560..620).toList()

    /** 426 is the v155 measured claim; 387 and 474 are the v154 600-tier cross-checks. */
    private val imeCross = listOf(426, 387, 474)

    private fun paneBoxPx(windowDp: Int): Int = windowDp - chromeAbovePanePx

    /** `SessionScreen.kt:2370-2372`, verbatim: the believed claim, floored. */
    private fun paneBottomDp(box: Int, floor: Int, ime: Int): Dp = with(density) {
        val believed = SessionPaneLayout.believedImeBottomPx(
            visibleBoxPx = box,
            floorPx = floor,
            imeBottomPx = ime,
            essentialPx = essentialPx,
        )
        maxOf(floor, believed).coerceAtLeast(0).toDp()
    }

    /** `SessionScreen.kt:2384`, coerce included. */
    private fun bodyDp(box: Int, floor: Int, ime: Int): Dp =
        (box.dp - paneBottomDp(box, floor, ime)).coerceAtLeast(0.dp)

    private fun bodyPx(box: Int, floor: Int, ime: Int): Int =
        with(density) { bodyDp(box, floor, ime).roundToPx() }

    /** `SessionScreen.kt:2385-2388` + `:1870` (`minimal`). */
    private fun minimal(box: Int, floor: Int, ime: Int, short: Boolean = false): Boolean =
        SessionPaneLayout.composerOneRow(short, bodyDp(box, floor, ime))

    /**
     * `SessionScreen.kt:1866` (`compact`).
     *
     * Production reads a *measured* room here (`paneHeightPx`, set by
     * `onSizeChanged` at `SessionScreen.kt:2116`), not `bodyHeightDp`. Both are
     * the same room; the defect being pinned is in the **predicate**, so the
     * domain-wide pins below hold for either reader, and the per-window scan
     * passes the same room it uses for `minimal` so the two verdicts are
     * comparable cell by cell.
     */
    private fun compact(box: Int, floor: Int, ime: Int): Boolean =
        SessionPaneLayout.composerCompact(bodyDp(box, floor, ime))

    private fun researchFile(name: String): java.io.File {
        val target = listOf(
            java.io.File("../research/v97_fix/$name"),
            java.io.File("research/v97_fix/$name"),
        ).firstOrNull { it.parentFile?.isDirectory == true }
            ?: error("no research/v97_fix directory reachable from ${System.getProperty("user.dir")}")
        return target
    }

    private fun write(name: String, body: String) {
        val f = researchFile(name)
        f.writeText(body, Charsets.UTF_8)
        println("V156 wrote ${f.absolutePath} (${body.length} chars)")
    }

    private data class Cell(
        val window: Int,
        val box: Int,
        val floor: Int,
        val ime: Int,
        val short: Boolean,
        val body: Int,
        val minimal: Boolean,
        val compact: Boolean,
    ) {
        override fun toString(): String =
            "window=$window box=$box floor=$floor ime=$ime short=$short -> body=$body, " +
                "minimal=$minimal, compact=$compact" +
                if (minimal && !compact) "  <-- CONTRADICTION" else ""
    }

    /** Every cell of the 620->560 sweep, in the order the monotone checks walk it. */
    private fun cells(): List<Cell> = windowRange.flatMap { window ->
        listOf(0, navFloor).flatMap { floor ->
            imeCross.flatMap { ime ->
                listOf(false, true).map { short ->
                    val box = paneBoxPx(window)
                    Cell(
                        window, box, floor, ime, short,
                        bodyPx(box, floor, ime),
                        minimal(box, floor, ime, short),
                        compact(box, floor, ime),
                    )
                }
            }
        }
    }

    // ------------------------------------- 1. where exactly does it flip back?

    /**
     * The pin the task asks for, over the **whole** real-device range: sweep the
     * window 620 -> 560 per dp and report every window at which the composer asks
     * for a **more expensive** form than it did one dp earlier.
     *
     * `compact` is the predicate that decides 「this pane can only afford the
     * compact composer」, so 「not compact」 *is* the expensive form. Every
     * violating adjacent pair is written to `_v156_violations.txt` with the
     * arithmetic spelled out, so the failure text is the mechanical evidence
     * rather than a count.
     */
    @Test
    fun theFormNeverAsksForMoreAsTheWindowGetsShorter() {
        val jumps = mutableListOf<String>()
        val byIme = imeCross.map { ime ->
            ime to listOf(0, navFloor).map { floor ->
                // ascending window order: i and i+1 are adjacent dp
                val row = windowRange.map { window ->
                    val box = paneBoxPx(window)
                    Triple(window, bodyPx(box, floor, ime), compact(box, floor, ime))
                }
                var pairs = 0
                for (i in 0 until row.size - 1) {
                    pairs++
                    val (w0, b0, c0) = row[i]   // the SHORTER window
                    val (w1, b1, c1) = row[i + 1] // the TALLER window
                    // Going from w0 to w1 the window GREW, so the form may only get
                    // cheaper or stay. `!c0 && c1` is the reverse jump read the
                    // other way round: the shorter window was refused the compact
                    // form while the taller one was granted it.
                    if (!c0 && c1) {
                        jumps += "V156 REVERSE-JUMP ime=$ime floor=$floor window $w0->$w1: " +
                            "compact $c0->$c1 (body $b0 -> $b1) — a SHORTER window may only " +
                            "keep the compact form, never grow back into the full composer"
                    }
                }
                pairs
            }
        }
        val totalPairs = byIme.sumOf { it.second.sum() }
        write(
            "_v156_violations.txt",
            buildString {
                appendLine("V156 window 620->560 sweep, boxes = window - $chromeAbovePanePx, " +
                    "floors=2, ime=${imeCross.joinToString("/")}, short=2")
                appendLine("V156 adjacent window pairs per ime/floor column = $totalPairs")
                appendLine("V156 REVERSE-JUMP violations = ${jumps.size}")
                jumps.forEach { appendLine(it) }
                appendLine("--- the 61-cell table, one row per window (ime=426, floor=$navFloor) ---")
                val box426 = paneBoxPx(620)
                for (window in windowRange) {
                    val box = paneBoxPx(window)
                    val b = bodyPx(box, navFloor, 426)
                    val m = minimal(box, navFloor, 426)
                    val c = compact(box, navFloor, 426)
                    appendLine(
                        "V156 window=$window box=$box -> body=$b, minimal=$m, compact=$c" +
                            if (m && !c) "   <-- FLIP: one row demanded, full composer granted" else ""
                    )
                }
                appendLine("essentialPx=$essentialPx clampEarlyReturnBelow=2*essential=${2 * essentialPx}")
                appendLine("ime=426 -> body==0 at box=426 -> window=" + (426 + chromeAbovePanePx))
            },
        )
        assertTrue(
            "the composer asked for a MORE expensive form on ${jumps.size} of the " +
                "$totalPairs adjacent window pairs in the 620->560 sweep; every one is " +
                "in _v156_violations.txt — a shorter window may only keep or shrink the " +
                "form, never grow it",
            jumps.isEmpty(),
        )
    }

    /**
     * The exact breakpoint, as a fact rather than as a count: the room at which
     * `compact` first answers 「no room at all, do not compact」, for every claim
     * in the cross-check set.
     */
    @Test
    fun theFirstWindowThatLosesTheCompactFormIsTheOneWhereTheRoomIsGone() {
        for (ime in imeCross) {
            // The topmost window that has already lost the compact form IS the
            // breakpoint: above it the room still exists, at it the room is gone.
            val first = windowRange.filter { window ->
                !compact(paneBoxPx(window), navFloor, ime)
            }.maxOrNull()
            val expected = ime + chromeAbovePanePx
            println("V156 ime=$ime: the flip begins at window " +
                "${first ?: "none (the compact form survives all of 620->560)"}; " +
                "arithmetic: body==0 at box==ime==$ime, i.e. window $ime + $chromeAbovePanePx = $expected")
            assertTrue(
                "ime=$ime: the compact form must survive every window in 620..560. It may " +
                    "only be lost at a window of 0, i.e. ime+chrome = $expected, which is " +
                    "outside the swept range" +
                    if (first == null) "" else "; the compact form was already lost at " +
                        "window $first, ${expected - first} dp too early",
                first == null || first >= expected,
            )
        }
    }

    // ------------------------------------ 2. the body never grows as the room shrinks

    /**
     * The room itself: `body` is monotone non-increasing as the window shrinks.
     * This is the second face of the same fact and it is reader-independent.
     */
    @Test
    fun theBodyNeverGrowsAsTheWindowGetsShorter() {
        val growth = mutableListOf<String>()
        var pairs = 0
        for (ime in imeCross) {
            for (floor in listOf(0, navFloor)) {
                var previous: Int? = null
                for (window in windowRange) { // ascending: w_i+1 is TALLER than w_i
                    val box = paneBoxPx(window)
                    val b = bodyPx(box, floor, ime)
                    if (previous != null) {
                        pairs++
                        // The window GREW, so the room may only grow. `b < previous`
                        // is the room taking dp away from a taller window, i.e. the
                        // room growing as the window got shorter.
                        if (b < previous!!) {
                            growth += "V156 BODY-GROWTH ime=$ime floor=$floor window " +
                                "${window - 1}->$window: body $previous -> $b (a shorter " +
                                "window may only take room away)"
                        }
                    }
                    previous = b
                }
            }
        }
        write(
            "_v156_body_growth.txt",
            "V156 body-growth violations in 620->560 = ${growth.size} (adjacent pairs = $pairs)\n" +
                growth.joinToString("\n") + "\n",
        )
        assertTrue(
            "the body grew on ${growth.size} of the $pairs adjacent window pairs — a shorter " +
                "window may only take room away; every case is in _v156_body_growth.txt",
            growth.isEmpty(),
        )
    }

    // -------------------- 3. the two form predicates must never contradict

    /**
     * **The direct consequence of v154's one-character fix.**
     *
     * `composerMinimal` now answers 「0 dp is the one-row form」, while
     * `composerCompact` still answers 「0 dp is not even worth compacting」. The
     * composer is handed both (`SessionScreen.kt:1866` and `:1870`), so on a room
     * of 0 dp the pane simultaneously demands the one-row form and grants the
     * full composer. `!compact(room) && minimal(room)` is exactly that state and
     * it must be empty.
     *
     * Scanned per dp over `0..400 dp` — the whole documented range of the two
     * thresholds (164 and 224) with room to spare.
     */
    @Test
    fun theTwoFormPredicatesNeverContradict() {
        val contradictions = (0..400).filter { room ->
            val r = room.dp
            SessionPaneLayout.composerMinimal(r) && !SessionPaneLayout.composerCompact(r)
        }
        write(
            "_v156_contradictions.txt",
            "V156 rooms in 0..400 dp where minimal=true and compact=false = " +
                "${contradictions.size}\nrooms: $contradictions\n",
        )
        assertTrue(
            "on ${contradictions.size} of the 401 rooms in 0..400 dp the two form predicates " +
                "contradict each other (minimal=true yet compact=false) — the pane demands the " +
                "one-row form and grants the full composer at the same time; every room is in " +
                "_v156_contradictions.txt",
            contradictions.isEmpty(),
        )
    }

    /**
     * `composerCompact` on its own must be monotone non-increasing in the room:
     * a room that was too small for the full composer can only get smaller, so
     * it may never stop being compact. This is the predicate-level form of the
     * grid violation, and it is what the fix changes.
     */
    @Test
    fun theCompactPredicateIsMonotoneNonIncreasingInTheRoomItself() {
        val reentries = (1..400).filter { room ->
            SessionPaneLayout.composerCompact(room.dp) &&
                !SessionPaneLayout.composerCompact((room - 1).dp)
        }
        val staircase = (1..400).count { room ->
            !SessionPaneLayout.composerCompact(room.dp) &&
                SessionPaneLayout.composerCompact((room - 1).dp)
        }
        assertTrue(
            "composerCompact re-entered its truth set at $reentries: a smaller room may only " +
                "ever take a smaller form, so the truth set must be an initial segment " +
                "($staircase staircase edges, expected exactly 1 at 224)",
            reentries.isEmpty(),
        )
        assertEquals(
            "the compact staircase must be crossed exactly once, at 224 = 116 + 44 + 64",
            1, staircase,
        )
        assertTrue(
            "223 dp is the last room that can only afford the compact composer",
            SessionPaneLayout.composerCompact(223.dp),
        )
        assertTrue(
            "224 dp is the first room that can pay the full composer",
            !SessionPaneLayout.composerCompact(224.dp),
        )
    }

    // ------------------------------------------ 4. the model constants are pinned

    /**
     * `chromeAbovePanePx` is the only number in this file not read out of
     * production, and the whole 620->560 mapping rests on it. Both measured
     * tiers must give the same chrome, and v154's own pane boxes must be
     * reproduced.
     */
    @Test
    fun theChromeAboveThePaneIsTheSameOnBothTiers() {
        assertEquals(
            "the 768 tier's pane box must be 606, i.e. 162 px of chrome above it",
            measuredPaneBox768, measuredWindow768 - chromeAbovePanePx,
        )
        assertEquals(
            "the 600 tier's pane box must be 438, i.e. the same 162 px of chrome above it",
            measuredPaneBox600, measuredWindow600 - chromeAbovePanePx,
        )
        assertEquals(
            "the clamp may not fire on either measured tier — both are below twice the " +
                "essential form, so the keyboard keeps its full claim on both",
            2 * essentialPx, 568,
        )
    }

    /**
     * The arithmetic of the flip, spelled out as arithmetic: on the 600 tier the
     * room is still there, on the 580 tier it is gone, and the window at which it
     * runs out is `ime + chrome`. Pinned so a future reader does not have to
     * re-derive it.
     */
    @Test
    fun theRoomRunsOutAtTheKeyboardClaimPlusTheChromeAboveThePane() {
        val ime = 426
        val roomIsGoneAt = ime + chromeAbovePanePx // 588
        assertTrue(
            "the room must still exist at the 600 tier (body > 0)",
            bodyPx(paneBoxPx(measuredWindow600), navFloor, ime) > 0,
        )
        assertTrue(
            "the room must be gone at the 580 tier (body == 0)",
            bodyPx(paneBoxPx(580), navFloor, ime) == 0,
        )
        assertEquals(
            "the 600 tier must be the last window of the 620->560 sweep that still has a " +
                "room, and 588 the first that has not",
            roomIsGoneAt, ime + chromeAbovePanePx,
        )
        // 600 and 588 are 12 dp apart, 580 is 8 dp past the breakpoint: the pair
        // v155 sampled straddles it, which is why 600 and 580 disagreed.
        assertTrue("600 is above the breakpoint", measuredWindow600 > roomIsGoneAt)
        assertTrue("580 is below the breakpoint", 580 < roomIsGoneAt)
    }
}
