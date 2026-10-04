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
 * v154: the B-2 reverse jump — 「键盘变高时形态反向跳变、body 归 0」.
 *
 * `SELFPROOF_REWRITE.md`（v153）§6 registered the 600 tier's **51 px** of room
 * as a margin pin and left the defect itself untouched, because fixing it means
 * touching `available > 0.dp` (`SessionPaneLayout.kt:1009`) and the v55/v57
 * staircase. This file is the round that touches it.
 *
 * ## The defect, stated as two monotonicity claims
 *
 * The room the composer has is **monotone non-increasing in `ime`** — a taller
 * keyboard can only take room away. The *form* it is given must therefore also be
 * monotone: once the one-row form is on, a taller keyboard may only keep it.
 * Stated over an ascending `ime` grid, per room:
 *
 * - **(1) the form never flips back.** `oneRow(ime_i) && !oneRow(ime_{i+1})` is a
 *   reverse jump: the keyboard grew and the composer asked for **more**.
 * - **(2) the body never grows.** `body(ime_{i+1}) > body(ime_i)` is the same
 *   fact seen from the room's side.
 *
 * ## What the current implementation does (`ime` ascending, box 438, floor 48)
 *
 * ```
 * ime  387 -> body  51, oneRow true    (the measured reading, 51 px of room)
 * ime  426 -> body  12, oneRow true
 * ime  438 -> body   0, oneRow FALSE   <- flip point, the reverse jump
 * ime  474 -> body   0, oneRow false   <- keyboard taller, asking for 116 dp
 * ime  836 -> body   0, oneRow false
 * ```
 *
 * The cause is one conjunct. `composerMinimal` opens with `available > 0.dp`
 * (`SessionPaneLayout.kt:1009`), so at `body == 0` the predicate answers
 * 「there is no room at all」 and the shell falls back to the **full 116 dp**
 * composer. That answer is non-monotone: `available < 164 dp` is the question the
 * predicate is documented to ask, and 0 dp is the room that can least afford the
 * stacked form.
 *
 * `SessionScreen.kt:2384`'s own `coerceAtLeast(0.dp)` cannot be the place to fix
 * this: the v77 P0 pin (`SessionPaneImeBeliefV77Test:304-310`) requires the form's
 * third argument to be literally `bodyHeightDp`, and that value is 0. The defect
 * is in the predicate, not in the room.
 */
class ReverseJumpMonotoneV154Test {

    // 1 dp == 1 px, so the production arithmetic is exact integer arithmetic.
    private val density = Density(1f)
    private val essentialPx: Int =
        with(density) { SessionPaneLayout.PANE_ESSENTIAL_HEIGHT.roundToPx() }

    // The two measured tiers (v108/v150/v153).
    private val paneBox600 = 438
    private val paneBox768 = 606
    private val imeBottom600 = 387
    private val imeBottom768 = 426
    private val navFloor = 48

    // v153's axes, unchanged, so the two rounds' grids are comparable cell by cell.
    private val boxGrid = listOf(284, 285, 438, 480, 567, 568, 569, 606, 700, 800, 1000)
    private val floorGrid = listOf(0, navFloor)
    private val imeGrid = listOf(
        0, 48, 200, 274, 284, 300, 331, 332, 333, 370, 371,
        387, 426, 438, 464, 474, 522, 606, 768, 836,
    )

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

    /** `SessionScreen.kt:2385-2388`. `windowIsShort` is `false` on both tiers. */
    private fun oneRow(box: Int, floor: Int, ime: Int, short: Boolean = false): Boolean =
        SessionPaneLayout.composerOneRow(short, bodyDp(box, floor, ime))

    private data class Cell(val box: Int, val floor: Int, val ime: Int, val body: Int, val form: Boolean)

    /** Every cell of the grid, in the order the monotone checks walk it. */
    private fun cells(short: Boolean = false): List<Cell> =
        boxGrid.flatMap { box ->
            floorGrid.flatMap { floor ->
                imeGrid.map { ime -> Cell(box, floor, ime, bodyPx(box, floor, ime), oneRow(box, floor, ime, short)) }
            }
        }

    private fun researchFile(name: String): java.io.File {
        val target = listOf(
            java.io.File("../research/v97_fix/$name"),
            java.io.File("research/v97_fix/$name"),
        ).firstOrNull { it.parentFile?.isDirectory == true }
            ?: error("no research/v97_fix directory reachable from ${System.getProperty("user.dir")}")
        return target
    }

    // ------------------------------------------- 1. the reverse jump itself

    /**
     * **(1) The form must never flip back from one row as the keyboard grows.**
     *
     * The pin the task asks for. Every violating adjacent pair of the grid is
     * written out to `research/v97_fix/_v154_violations.txt` before the assertion,
     * so the failure text is the mechanical evidence rather than a count.
     */
    @Test
    fun theFormNeverFlipsBackFromOneRowAsTheKeyboardGrows() {
        val jumps = mutableListOf<String>()
        var pairs = 0
        for (short in listOf(false, true)) {
            for (box in boxGrid) {
                for (floor in floorGrid) {
                    var prev: Cell? = null
                    for (ime in imeGrid) {
                        val now = Cell(box, floor, ime, bodyPx(box, floor, ime), oneRow(box, floor, ime, short))
                        if (prev != null) {
                            pairs++
                            if (prev.form && !now.form) {
                                jumps += "V154 REVERSE-JUMP short=$short box=$box floor=$floor " +
                                    "ime ${prev.ime}->${now.ime}: body ${prev.body}->${now.body} px, " +
                                    "oneRow ${prev.form}->${now.form}"
                            }
                        }
                        prev = now
                    }
                }
            }
        }
        val out = StringBuilder()
        out.append("V154 grid boxes=${boxGrid.size} floors=${floorGrid.size} ime=${imeGrid.size} ")
        out.append("cells=${boxGrid.size * floorGrid.size * imeGrid.size} adjacentPairs=$pairs\n")
        out.append("V154 REVERSE-JUMP violations=${jumps.size}\n")
        jumps.forEach { out.append(it).append('\n') }
        researchFile("_v154_violations.txt").writeText(out.toString())

        assertEquals(
            "the form flipped back from one row on ${jumps.size} of $pairs adjacent pairs; " +
                "every one is in _v154_violations.txt — a taller keyboard may only keep the " +
                "one-row form, never grow back into the full 116 dp composer",
            0,
            jumps.size,
        )
    }

    /**
     * **(2) The body must never grow as the keyboard grows.**
     *
     * The same fact from the room's side. The room is the believed claim plus the
     * clamp, and the clamp is monotone by construction, so this holds on the whole
     * grid — it is pinned so that a future change to `believedImeBottomPx` that
     * made the *room* non-monotone could not hide behind a monotone form.
     */
    @Test
    fun theBodyNeverGrowsAsTheKeyboardGrows() {
        val grows = mutableListOf<String>()
        var pairs = 0
        for (box in boxGrid) {
            for (floor in floorGrid) {
                var prevBody = bodyPx(box, floor, imeGrid.first())
                for (ime in imeGrid.drop(1)) {
                    val now = bodyPx(box, floor, ime)
                    pairs++
                    if (now > prevBody) {
                        grows += "V154 BODY-GREW box=$box floor=$floor ime->$ime: $prevBody->$now"
                    }
                    prevBody = now
                }
            }
        }
        assertEquals(
            "the body grew with the keyboard on ${grows.size} of $pairs adjacent pairs: " +
                "a taller keyboard can only take room away",
            0,
            grows.size,
        )
    }

    // ------------------------------- 2. the contradiction at body == 0

    /**
     * A room of 0 dp must take the **cheapest** form, not the full one: 0 dp
     * cannot carry the stacked composer, which asks for
     * [SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT]'s bigger sibling
     * ([SessionPaneLayout.COMPOSER_COMPACT_HEIGHT], 116 dp). This is the
     * 「需要 116dp 却给 0」 half of the task, as a cell count over the grid.
     */
    @Test
    fun aRoomOfZeroTakesTheCheapestFormAndNotTheFullOne() {
        val offenders = cells().filter { it.body == 0 && !it.form }
        researchFile("_v154_zero_room.txt").let { f ->
            f.writeText(
                buildString {
                    appendLine("V154 zero-room cells asking for the full composer: ${offenders.size}")
                    offenders.forEach { appendLine("V154 box=${it.box} floor=${it.floor} ime=${it.ime} body=0 oneRow=false") }
                },
            )
        }
        assertEquals(
            "on ${offenders.size} cells the body is 0 dp and the shell still asks for the full " +
                "116 dp composer — the contradiction is the reverse jump's other face",
            0,
            offenders.size,
        )
    }

    /**
     * The **sufficient** condition, and the monotonicity proof: over a dense dp
     * grid, `composerMinimal` must be monotone **non-increasing** in the room. If
     * that holds, then any monotone room yields a monotone form, and fixing the
     * one non-monotone input (0 dp) is provably sufficient rather than merely
     * observed to be.
     */
    @Test
    fun thePredicateIsMonotoneNonIncreasingInTheRoomItself() {
        // monotone non-increasing == the rooms that take the one-row form are an
        // **initial segment** of [0, 400]: no room may re-enter the set after
        // leaving it, and the set must be unbroken from 0 upwards.
        val reentries = mutableListOf<Int>()   // false -> true : a room that came back
        val staircases = mutableListOf<Int>()  // true  -> false: the one legal edge
        var previous = SessionPaneLayout.composerMinimal(0.dp)
        for (room in 1..400) {
            val now = SessionPaneLayout.composerMinimal(room.dp)
            if (!previous && now) reentries += room
            if (previous && !now) staircases += room
            previous = now
        }
        assertEquals(
            "composerMinimal re-entered the one-row form at $reentries: a smaller room may only " +
                "ever take a smaller form, so the truth set must be an initial segment",
            0,
            reentries.size,
        )
        // …and the single legal edge is the stacked form's own budget, the
        // staircase v57/v74 pin. 163 is one row, 164 is the full form.
        assertEquals(
            "the one-row truth set must be exactly {0 .. 163}, saw ${staircases.size} edges",
            listOf(
                (SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT +
                    SessionPaneLayout.CHROME_MIN_HEIGHT +
                    SessionPaneLayout.TIMELINE_MIN_CONTENT).value.roundToInt(),
            ),
            staircases,
        )
        assertTrue(
            "a room of nothing must take the cheapest form",
            SessionPaneLayout.composerMinimal(0.dp),
        )
    }

    // ------------------------------------------- 3. the measured readings

    /**
     * The 600 tier's measured reading is unchanged: 51 px of room, one-row form.
     * This is the v153 margin pin's subject, re-asserted after the fix.
     */
    @Test
    fun theSixHundredTierMeasuredReadingStillHasFiftyOnePxOfRoom() {
        assertEquals("the measured 600-tier body", 51, bodyPx(paneBox600, navFloor, imeBottom600))
        assertTrue("the measured 600-tier form", oneRow(paneBox600, navFloor, imeBottom600))
        assertEquals("the measured 600-tier margin", 51, paneBox600 - imeBottom600)
        // 426 is where the retired epoch's reading sat: still one row.
        assertEquals("426: the body is 12", 12, bodyPx(paneBox600, navFloor, 426))
        assertTrue("426: one row", oneRow(paneBox600, navFloor, 426))
    }

    /**
     * The 768 tier's measured reading is unchanged: the clamp saturates, the body
     * is the constant 236, the form is one row — exactly as v150 §B.2(a) and v153
     * found it.
     */
    @Test
    fun theSevenSixtyEightTierStillSaturatesToTheSameConstant() {
        assertEquals("768 tier body at the measured 426", 236, bodyPx(paneBox768, navFloor, imeBottom768))
        // 236 dp is **above** the 164 dp staircase, so the full form is the
        // correct one here — the saturating room still pays for the stacked
        // composer. This is the v150 §B.2(a) laziness, not the B-2 jump.
        assertTrue("768 tier form at the measured 426", !oneRow(paneBox768, navFloor, imeBottom768))
        // the saturation invariant, on the cells where the clamp actually
        // saturated: the clamp returns `roomPx + floorPx` there, so the body is
        // the one constant `essentialPx - floor` regardless of the keyboard and
        // regardless of the box. v150 §B.2(a); re-asserted after the v154 fix.
        val saturated = cells().filter { ime ->
            ime.ime > 0 &&
                SessionPaneLayout.believedImeBottomPx(
                    visibleBoxPx = ime.box,
                    floorPx = ime.floor,
                    imeBottomPx = ime.ime,
                    essentialPx = essentialPx,
                ) != ime.ime
        }
        val constants = saturated.map { it.body }.distinct().sorted()
        assertEquals(
            "the saturated region's body must be the one constant essentialPx - floor, saw $constants",
            listOf(essentialPx - navFloor, essentialPx).sorted(),
            constants,
        )
    }

    /**
     * The whole grid, written out verbatim, so the monotonicity claim can be read
     * cell by cell instead of taken on trust. Lands in `research/v97_fix/`.
     */
    @Test
    fun theWholeGridIsWrittenOutForReading() {
        val out = StringBuilder()
        out.append("V154 box,floor,ime -> body,oneRow   (density 160, 1dp == 1px)\n")
        out.append("V154 box,floor,ime -> body,oneRow,shortWindow\n")
        for (short in listOf(false, true)) {
            for (c in cells(short)) {
                out.append(
                    "V154 ${c.box},${c.floor},${c.ime} -> ${c.body},${c.form},$short\n",
                )
            }
        }
        researchFile("_v154_grid.txt").writeText(out.toString())
        assertEquals(
            "the grid is ${boxGrid.size * floorGrid.size * imeGrid.size} cells per window term",
            boxGrid.size * floorGrid.size * imeGrid.size,
            cells().size,
        )
    }
}
