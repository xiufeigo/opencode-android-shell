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
 * v158: the monotonicity judge moves from the **window height `H`** to the **room**.
 *
 * ## Why the judge had to move (Lead's ruling, v157 measured)
 *
 * The v155/v156/v157 rounds all stated the monotonicity claim over `H`, the window
 * height, because that is what the device is driven with (`wm size 1366x<H>`). v157
 * measured that premise and falsified it:
 *
 * ```
 * H = 620  ime = 426   (dumpsys window: type=ime frame=[0,194][1366,620])
 * H = 600  ime = 339   (… frame=[0,261][1366,600])
 * H = 599  ime = 261   (… frame=[0,334][1366,599])   <- the keyboard collapsed
 * H = 560  ime = 261   (… frame=[0,299][1366,560])
 * ```
 *
 * **The keyboard shrinks itself as the window gets shorter — Gboard, on its own,
 * with no app involvement: `426 -> 339 -> 261`, and the collapse happens across
 * the single dp `600 -> 599`.** Since
 *
 * ```
 * room = box - ime = (H - 162) - ime
 * ```
 *
 * the map `H -> room` is **not a function the app controls**: at `H = 600` the
 * observable room is `261`, at `H = 599` it is `338` — the room **grew by 77** while
 * the window lost 1 dp. So a claim of the form 「`H` smaller ⇒ the composer may not
 * ask for a more expensive form」 is not a statement about the app at all; it is a
 * statement about Gboard's resize policy, and v157 duly found it 「violated」 at
 * `600 -> 599` with the composer doing the **right** thing (a bigger room deserves
 * the bigger form).
 *
 * ⇒ **The judge is `room`.** The form must be monotone non-increasing in the room,
 * and `H` must never be used as the judge axis again. This file is that pin.
 *
 * ## What `room` is here, exactly
 *
 * The room the composer is **handed** — the argument of the two form predicates:
 * `SessionScreen.kt:2384` `bodyHeightDp` (`minimal`, `SessionScreen.kt:1870`) and the
 * `available` of `composerCompact` (`SessionPaneLayout.kt:786`). It is
 * `(box - paneBottom).coerceAtLeast(0)` with `box = H - 162`.
 *
 * v157's report writes the observable as `room = H - ime` (no chrome subtracted) and
 * the two differ by exactly the 162 px of chrome above the pane box, on the whole
 * `560..620` range, because `believedImeBottomPx` early-returns there
 * (`box < 2 * PANE_ESSENTIAL_HEIGHT = 568` ⇒ `box = 398..458`) so the keyboard keeps
 * its **full** claim. Both are affine in each other; only the predicate argument is
 * a judge, and this file uses the predicate argument.
 *
 * ## The effective form
 *
 * Production hands the composer two form predicates. Their disagreement is pinned
 * empty by v156 (`theTwoFormPredicatesNeverContradict`), so the effective form is
 * 「one row if **either** says one row」:
 *
 * ```
 * oneRow(room) = composerMinimal(room) || composerCompact(room)
 *              = (room < 164) || (room < 224) = room < 224
 * ```
 *
 * a staircase with exactly one edge, at `224 = 116 + 44 + 64`. Monotone
 * non-increasing in `room` by inspection, and pinned as arithmetic below rather
 * than taken on trust.
 */
class ReverseJumpRoomJudgeV158Test {

    // 1 dp == 1 px, so the production arithmetic is exact integer arithmetic.
    private val density = Density(1f)
    private val essentialPx: Int =
        with(density) { SessionPaneLayout.PANE_ESSENTIAL_HEIGHT.roundToPx() }

    /** 162 px of chrome above the pane box — `768-606 = 600-438`, v156-pinned. */
    private val chromeAbovePanePx = 162

    private val navFloor = 48

    /** The real-device drive, swept per dp: 61 cells, `560..620`. */
    private val windowRange: List<Int> = (560..620).toList()

    /**
     * **v157's measured keyboard, per window height.**
     *
     * Read off `dumpsys window`'s `InsetsSource id=3 type=ime frame=[..][1366,H]`
     * at every swept level (`REPORT_MONOTONE_FORM2.md` §1). Three distinct values:
     * **426, 339, 261**. This is *measured device behaviour*, not an assumption —
     * it is the exact reason the judge cannot be `H`.
     */
    private fun measuredImePx(window: Int): Int = when {
        window >= 605 -> 426
        window >= 600 -> 339
        else -> 261
    }

    // V158 REVERSE-VERIFICATION MUTATION (revert before commit): the v156-era
    // premise that the keyboard holds a constant claim, which is exactly what
    // "judge the form on the window height H" silently assumes -- H -> room then
    // becomes an affine function of H, so the room axis carries no new information
    // and the 600->599 collapse disappears from the model.
    // Doing exactly that made 2 of the 5 cases go red on 2026-10-04; the verbatim
    // failure text is in research/v97_fix/_v158_reverse_verification.txt and
    // MONOTONE_JUDGE_FIX.md section 5. It is recorded here so the mutation is
    // reproducible rather than folklore.

    /** The room, as production computes it: `box - paneBottom`, clamp included. */
    private fun bodyDp(box: Int, floor: Int, ime: Int): Dp {
        val believed = SessionPaneLayout.believedImeBottomPx(
            visibleBoxPx = box,
            floorPx = floor,
            imeBottomPx = ime,
            essentialPx = essentialPx,
        )
        val paneBottom = maxOf(floor, believed).coerceAtLeast(0)
        return (box - paneBottom).dp.coerceAtLeast(0.dp)
    }

    private fun bodyPx(box: Int, floor: Int, ime: Int): Int =
        with(density) { bodyDp(box, floor, ime).roundToPx() }

    /**
     * The effective form: one row if **either** predicate asks for one row.
     * v156 pins that the two never contradict, so this is a total function of the
     * room alone — which is precisely what makes the room a usable judge axis.
     */
    private fun oneRow(room: Int): Boolean =
        SessionPaneLayout.composerMinimal(room.dp) || SessionPaneLayout.composerCompact(room.dp)

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
        println("V158 wrote ${f.absolutePath} (${body.length} chars)")
    }

    /**
     * One cell of the device grid. [room] is the judge's independent variable;
     * [window] is the thing the device was driven with and is **not** a judge.
     */
    private data class Cell(
        val window: Int,
        val box: Int,
        val ime: Int,
        val room: Int,
    ) {
        val oneRow: Boolean get() = oneRowOf(room)
        val form: String get() = if (oneRow) "ONEROW" else "STACKED"
    }

    private companion object {
        /** Kept out of the data class so [Cell.oneRow] stays a pure function of the room. */
        fun oneRowOf(room: Int): Boolean =
            SessionPaneLayout.composerMinimal(room.dp) || SessionPaneLayout.composerCompact(room.dp)
    }

    private fun cells(): List<Cell> = windowRange.map { window ->
        val box = window - chromeAbovePanePx
        Cell(window = window, box = box, ime = measuredImePx(window), room = bodyPx(box, navFloor, measuredImePx(window)))
    }

    // ------------------- 1. the room is the independent variable. Full stop.

    /**
     * **The pin the task asks for: the judge's independent variable is `room`, not the
     * window height.**
     *
     * The 61 device cells are re-sorted by `room` and walked in that order. Walking
     * *down* the room axis (room shrinking), the form may only keep or become
     * **cheaper**: a cell with a smaller room must never ask for a more expensive
     * form than the cell before it. That is the whole claim, stated on the only
     * axis the app controls.
     *
     * The 61-cell table is written out in full, in **room order**, one row per cell,
     * so the claim can be read line by line instead of taken on trust.
     */
    @Test
    fun theFormIsMonotoneNonIncreasingInTheRoomAndNotInTheWindowHeight() {
        val byRoom = cells().sortedBy { it.room }
        val out = StringBuilder()
        out.append("V158 ROOM-AXIS JUDGE — the independent variable is the room, NOT the window height.\n")
        out.append("V158 ime collapses with the window (Gboard): 426 / 339 / 261, collapse at 600->599.\n")
        out.append("V158 room = box - ime = (H - $chromeAbovePanePx) - ime, 1 dp == 1 px, floor=$navFloor\n")
        out.append("V158 cells=${byRoom.size}  (window 620->560 per dp, re-sorted by room ascending)\n")
        out.append("V158 | room | body | form | source window | ime |\n")

        val violations = mutableListOf<String>()
        var prev: Cell? = null
        for (c in byRoom) {
            val body = bodyPx(c.box, navFloor, c.ime)
            out.append(
                "V158 | ${c.room} | $body | ${c.form} | window=${c.window} | ime=${c.ime} |\n",
            )
            if (prev != null) {
                // Walking DOWN the room axis: room shrank, so the form may only get
                // cheaper. `prev.oneRow && !c.oneRow` = 「asked for more with less
                // room」 — the reverse jump, restated on the room.
                if (prev!!.room > c.room && prev!!.oneRow && !c.oneRow) {
                    violations += "V158 ROOM REVERSE-JUMP room ${prev!!.room}->${c.room}: " +
                        "oneRow ${prev!!.oneRow}->${c.oneRow} (window ${prev!!.window}->${c.window}, " +
                        "ime ${prev!!.ime}->${c.ime}) — a smaller room may only keep or shrink the form"
                }
            }
            prev = c
        }
        out.append("V158 ROOM REVERSE-JUMP violations = ${violations.size} (room-adjacent pairs = ${byRoom.size - 1})\n")
        violations.forEach { out.append(it).append('\n') }

        // The same 61 cells in *window* order, kept in the file only to show the two
        // orders are NOT the same order. This is why `H` is not a judge.
        out.append("--- the same 61 cells in WINDOW order (not a judge; kept for contrast) ---\n")
        for (c in cells()) {
            out.append("V158 | room=${c.room} | ${c.form} | window=${c.window} | ime=${c.ime} |\n")
        }
        write("_v158_room_grid.txt", out.toString())

        assertEquals(
            "the room axis must actually be the axis walked: the 61 device cells, sorted by " +
                "room, must be ${windowRange.size} of them and every room distinct",
            windowRange.size,
            byRoom.size,
        )
        assertTrue(
            "the composer asked for a MORE expensive form on ${violations.size} room-adjacent " +
                "pairs of the 620->560 sweep — a smaller room may only keep or shrink the form, " +
                "never grow it; every one is in _v158_room_grid.txt. The judge is the room: " +
                "ime collapses with the window (426/339/261 at 600->599), so H -> room is not " +
                "a function the app controls.",
            violations.isEmpty(),
        )
    }

    /**
     * **`body` must not grow as `room` shrinks.** The second face of the same fact,
     * read from the room's side.
     *
     * `room` *is* the body here — that identity is itself part of the claim and is
     * pinned: every cell's computed `body` equals its `room`, so nothing downstream
     * can quietly substitute a different quantity for the judge.
     */
    @Test
    fun theBodyNeverGrowsAsTheRoomGetsSmaller() {
        val byRoom = cells().sortedByDescending { it.room } // shrinking room
        val growth = mutableListOf<String>()
        var pairs = 0
        for (i in 0 until byRoom.size - 1) {
            val bigger = byRoom[i]
            val smaller = byRoom[i + 1]
            pairs++
            val b1 = bodyPx(bigger.box, navFloor, bigger.ime)
            val b2 = bodyPx(smaller.box, navFloor, smaller.ime)
            if (b2 > b1) {
                growth += "V158 BODY-GREW room ${bigger.room}->${smaller.room}: body $b1 -> $b2 " +
                    "(window ${bigger.window}->${smaller.window}, ime ${bigger.ime}->${smaller.ime})"
            }
        }
        write(
            "_v158_body_growth.txt",
            "V158 body-growth violations on the room axis (620->560 sweep) = ${growth.size} " +
                "(room-adjacent pairs = $pairs)\n" + growth.joinToString("\n") + "\n",
        )
        assertTrue(
            "the body grew on ${growth.size} of the $pairs room-adjacent pairs — a smaller " +
                "room may only take room away; every case is in _v158_body_growth.txt",
            growth.isEmpty(),
        )

        // The identity that makes `room` a judge at all.
        val mismatched = cells().filter { it.room != bodyPx(it.box, navFloor, it.ime) }
        assertTrue(
            "the judge variable must be exactly what the composer is handed: ${mismatched.size} " +
                "cells where body != room — ${mismatched.map { "window=${it.window} room=${it.room} body=" +
                    bodyPx(it.box, navFloor, it.ime) }}",
            mismatched.isEmpty(),
        )
    }

    // ------------------- 2. the predicate itself, as arithmetic

    /**
     * The room-axis claim at its strongest: a **dense per-dp sweep** of the room
     * itself. Monotone non-increasing means the rooms that take the one-row form are
     * an **initial segment** — nothing may re-enter — and the single legal edge is
     * the full composer's own budget.
     */
    @Test
    fun theEffectiveFormIsMonotoneNonIncreasingInTheRoomItself() {
        val reentries = (1..400).filter { room -> oneRow(room) && !oneRow(room - 1) }
        val edges = (1..400).filter { room -> !oneRow(room) && oneRow(room - 1) }
        val compactStaircase = (1..400).filter {
            !SessionPaneLayout.composerCompact(it.dp) && SessionPaneLayout.composerCompact((it - 1).dp)
        }
        val minimalStaircase = (1..400).filter {
            !SessionPaneLayout.composerMinimal(it.dp) && SessionPaneLayout.composerMinimal((it - 1).dp)
        }
        write(
            "_v158_room_axis.txt",
            buildString {
                appendLine("V158 effective form = composerMinimal(room) || composerCompact(room)")
                appendLine("V158 rooms in 0..400 dp where the form re-enters (false -> true) = ${reentries.size}: $reentries")
                appendLine("V158 staircase edges (true -> false) = ${edges.size}: $edges")
                appendLine("V158 composerMinimal staircase = $minimalStaircase")
                appendLine("V158 composerCompact staircase = $compactStaircase")
                appendLine("V158 0..400 dp, one row per room:")
                for (room in 0..400) {
                    appendLine(
                        "V158 room=$room minimal=${SessionPaneLayout.composerMinimal(room.dp)} " +
                            "compact=${SessionPaneLayout.composerCompact(room.dp)} " +
                            "form=${if (oneRow(room)) "ONEROW" else "STACKED"}",
                    )
                }
            },
        )
        assertTrue(
            "the effective form re-entered the one-row truth set at $reentries: a smaller room " +
                "may only ever take a cheaper form, so the truth set must be an initial segment",
            reentries.isEmpty(),
        )
        assertEquals(
            "the room-axis truth set must be exactly {0 .. 223}, i.e. one staircase edge at " +
                "224 = COMPOSER_MIN_HEIGHT 116 + CHROME_MIN_HEIGHT 44 + TIMELINE_MIN_CONTENT 64",
            listOf(
                (SessionPaneLayout.COMPOSER_MIN_HEIGHT +
                    SessionPaneLayout.CHROME_MIN_HEIGHT +
                    SessionPaneLayout.TIMELINE_MIN_CONTENT).value.roundToInt(),
            ),
            edges,
        )
        // v154's and v156's two stairs, still intact underneath the composition.
        assertEquals("composerMinimal's own staircase", listOf(164), minimalStaircase)
        assertEquals("composerCompact's own staircase", listOf(224), compactStaircase)
    }

    // ------------------- 3. the v157 measurement, pinned

    /**
     * **The external behaviour that moved the judge, pinned as a fact.**
     *
     * The keyboard collapses with the window: `426 -> 339 -> 261`, across the single
     * dp `600 -> 599`. Recorded here so that no future round re-derives the monotonicity
     * claim over `H` and gets 「a violation at 600 -> 599」 that is really just Gboard.
     */
    @Test
    fun theKeyboardCollapsesWithTheWindowSoTheWindowHeightIsNotAUsableJudgeAxis() {
        // The three distinct claims v157 read off `dumpsys window`.
        val distinct = cells().map { it.ime }.distinct().sorted()
        assertEquals(
            "v157 measured exactly three keyboard claims (dumpsys window: " +
                "`InsetsSource id=3 type=ime frame=[..][1366,H]`): 426, 339, 261 — the keyboard " +
                "collapses as the window gets shorter, with no app involvement",
            listOf(261, 339, 426),
            distinct,
        )

        // The collapse point itself: a single dp of window height.
        val at600 = cells().single { it.window == 600 }
        val at599 = cells().single { it.window == 599 }
        assertEquals("v157: at H=600 the keyboard claims 339", 339, at600.ime)
        assertEquals("v157: at H=599 the keyboard claims 261", 261, at599.ime)
        assertEquals("the keyboard drops 78 px across the single dp 600 -> 599", 78, at600.ime - at599.ime)
        assertTrue(
            "the room therefore GROWS across 600 -> 599 (v157 observed 261 -> 338, a 77 px jump): " +
                "room ${at599.room} - ${at600.room}",
            at599.room > at600.room,
        )

        // The two axes are genuinely different orders — the mechanical reason `H` is
        // not a judge. If they ever coincided, this pin would fail and the whole
        // argument for moving the judge would need re-reading.
        val roomOrder = cells().sortedBy { it.room }.map { it.window }
        val windowOrder = cells().map { it.window }
        assertTrue(
            "sorting the 61 cells by room must NOT reproduce the window order — if it did, the " +
                "two axes would be the same axis and this file's premise would be void",
            roomOrder != windowOrder,
        )
    }

    /**
     * **The anti-misjudgment guard.**
     *
     * v155/v156 saw a 「reverse jump」 at `600 -> 580` and v157 re-located it to
     * `600 -> 599`. Read over `H` the form appears to grow as the window shrinks. Read
     * over `room` — the axis the composer is actually handed — nothing flips: across
     * that pair the room **grows**, so a richer form is the **correct** answer, and the
     * room-axis judge in `theFormIsMonotoneNonIncreasingInTheRoomAndNotInTheWindowHeight`
     * is green.
     *
     * This test states that reading explicitly so the next round does not re-open it as
     * a defect.
     */
    @Test
    fun theFormGrowingAcrossTheImeCollapseIsCorrectBecauseTheRoomGrew() {
        val at600 = cells().single { it.window == 600 }
        val at599 = cells().single { it.window == 599 }
        assertTrue(
            "600 -> 599: the window shrank by 1 dp but the room grew " +
                "(${at600.room} -> ${at599.room}), so no reverse jump happened on the axis the " +
                "app controls; a richer form there is correct behaviour, not a defect",
            at599.room > at600.room,
        )
        // And therefore the room-axis judge really is satisfied across that pair.
        val pair = listOf(at600, at599).sortedBy { it.room }
        val (smaller, bigger) = pair
        assertTrue(
            "walking the room axis across 600 -> 599 (room ${smaller.room} -> ${bigger.room}, " +
                "window ${smaller.window} -> ${bigger.window}) the form may only shrink or stay",
            !smaller.oneRow || bigger.oneRow,
        )
    }
}
