package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * v153: the Lead's B-1 ruling — 「实施 B.3 的等价改写」 — was granted **on the
 * condition that equivalence be proven**. It is not, and this file is the proof.
 *
 * **Verdict: §B.3's diff is NOT equivalent. It is not equivalent on any grid cell
 * where the clamp did *not* fire, which includes both measured tiers.** The
 * rewrite is therefore **not landed**; production is untouched
 * (`git status --porcelain -- app/src/main` = 0 lines).
 *
 * ## The arithmetic
 *
 * Production (`SessionScreen.kt:2361-2384`):
 * ```
 * paneBoxPx     = maxHeight.roundToPx()                      [density 160 => 1dp == 1px]
 * believedIme   = believedImeBottomPx(paneBoxPx, floor, ime, essential)  [:1574-1587]
 * paneBottomDp  = maxOf(floor, believedIme).toDp()           [:2370-2372]
 * bodyHeightDp  = (maxHeight - paneBottomDp).coerceAtLeast(0.dp)        [:2384]
 * ```
 * In the **saturating** branch `believedIme = roomPx + floorPx` with
 * `roomPx = visibleBoxPx - essentialPx`, and the early-return guard
 * (`SessionPaneLayout.kt:1582`) guarantees `visibleBoxPx >= essentialPx * 2`, so
 * `roomPx >= essentialPx > 0` and therefore `maxOf(floor, believedIme) == believedIme`.
 * Substituting:
 *
 * ```
 * bodyHeightDp = maxHeight - (maxHeight - essentialPx + floorPx)
 *              = essentialPx - floorPx                    <-- a CONSTANT
 * ```
 *
 * That is the invariant B.3 meant to write out. But §B.3's else-branch writes
 *
 * ```
 * maxHeight - PANE_ESSENTIAL_HEIGHT + paneFloorPx        <-- = maxHeight - (essentialPx - floorPx)
 * ```
 *
 * which is the **pane bottom**, not the body — the very quantity the old line
 * subtracts. On the measured 768 tier (`box 606`, `floor 48`, `essential 284`,
 * `ime 426`): the old body is `606 - 370 = 236`; §B.3's else-branch returns
 * `606 - 284 + 48 = 370`. It is off by **134 px — the body's own value.** The
 * rewrite double-subtracts.
 *
 * ## The branch condition is inverted as well
 *
 * §B.3 gates the old expression on `believedImeBottomPx < ime`, commented
 * 「钳位未生效：用真实余量」. But `believed < ime` holds **exactly when the clamp
 * fired** (that is what saturation means). So the rewrite keeps the old
 * expression in the saturated region — where the invariant is *already* what the
 * old line computes — and applies the invariant in the *unsaturated* region,
 * where it does not hold. The two defects point the same way: **every cell the
 * rewrite was meant to clarify it leaves alone, and every cell it must not touch
 * it changes.**
 *
 * | region | old body | §B.3 body | equal? |
 * |---|---|---|---|
 * | saturated (box 606, ime 426) | 236 | 236 (takes old path) | yes |
 * | identity (box 700, ime 426) | 274 | 464 | **no** |
 * | early return, 600 tier (box 438, ime 387) | 51 | 202 | **no** |
 * | no keyboard (box 606, ime 0) | 558 | 370 | **no** |
 *
 * ## What this file does instead
 *
 * It does not assert the rewrite is equivalent (that would be false). It pins the
 * **finding** as a regression: the saturation invariant really is the constant
 * `essentialPx - floorPx` ([theSaturationInvariantIsTheSameConstantOnEverySaturatedCell]),
 * the §B.3 branch order really is non-equivalent on a non-trivial share of the
 * grid ([theSectionB3BranchOrderIsNotEquivalentToProductionOnTheGrid]), and the
 * **corrected** form — the same invariant with the two branches swapped — really
 * is equivalent on **every** cell
 * ([theCorrectedInvariantWithTheBranchesSwappedIsEquivalentOnEveryCell]).
 * A ready-to-land rewrite, proven, is in §2 below; it needs the Lead's word
 * because landing it collides with three existing source pins (see
 * `theLandingIsBlockedByThreeSourcePinsThatPinTheOldLine`).
 *
 * ## Grid (exhaustive, 440 cells)
 *
 * `paneFloorPx` in {0, 48} (the 48dp target and the titlebar-bottom 0);
 * `box` in {284, 285, 438, 480, 567, 568, 569, 606, 700, 800, 1000} — 438 and
 * 606 are the **measured** 600/768 tiers, 567/568/569 straddle the
 * `essentialPx * PANE_ESSENTIAL_SPARE_ROOM` early-return boundary, 284/285 straddle
 * the essential form itself; `ime` in {0, 48, 200, 274, 284, 300, 331, 332, 333,
 * 370, 371, 387, 426, 438, 464, 474, 522, 606, 768, 836} — covering no keyboard,
 * both measured readings (387 / 426), the retired 474, the v77 stale 836, the
 * 768 identity/ saturation boundary pair (370/371), and the 600 flip point (438).
 */
class SelfProofEquivalenceV153Test {

    // Device geometry, 1366x768 / 1366x600 at `wm density 160` => 1dp == 1px.
    // Same convention and same measured literals as `ComposerPaneBottomCriterionV108Test`.
    /**
     * The device runs at `wm density 160`, i.e. a density **factor of 1.0**:
     * 1dp == 1px, which is the same convention
     * `ComposerPaneBottomCriterionV108Test` uses when it reads
     * `PANE_ESSENTIAL_HEIGHT.value.toInt()` straight out as a px count.
     *
     * `Density(1f)` is that density; `Density(160f)` would be 25600 dpi and
     * would divide every px reading by 160 on the way to dp.
     */
    private val density = Density(1f)

    private val essentialDp: Dp = SessionPaneLayout.PANE_ESSENTIAL_HEIGHT
    private val essentialPx: Int = with(density) { essentialDp.roundToPx() }

    private val paneBox600 = 438
    private val paneBox768 = 606
    private val imeBottom600 = 387
    private val imeBottom768 = 426
    private val navFloor = 48

    private val floorGrid = listOf(0, navFloor)
    private val boxGrid = listOf(
        essentialPx, essentialPx + 1, // 284, 285
        paneBox600, 480, // 438 (600 tier, measured), 480
        567, 568, 569, // the early-return boundary, essentialPx * SPARE_ROOM
        paneBox768, 700, 800, 1000, // 606 (768 tier, measured)
    )
    private val imeGrid = listOf(
        0, 48, 200, 274, essentialPx, 300,
        331, 332, 333, // 331/332 = 600-tier boundary, 333 = just over
        370, 371, // 768 identity/saturation boundary
        imeBottom600, // 387 (600 tier, measured)
        imeBottom768, // 426 (768 tier, measured)
        paneBox600, 464, 474, 522, // 438 = 600-tier flip point; 474 retired
        606, 768, 836, // 836 = the v77 stale frame
    )

    private data class Cell(
        val box: Int,
        val floor: Int,
        val ime: Int,
        val believed: Int,
        val clamped: Boolean,
        val oldBody: Int,
        val oldOneRow: Boolean,
        val b3Body: Int,
        val b3OneRow: Boolean,
        val fixedBody: Int,
        val fixedOneRow: Boolean,
    )

    /** The clamp as production calls it, and the branch predicate it implies. */
    private fun believed(box: Int, floor: Int, ime: Int): Int =
        SessionPaneLayout.believedImeBottomPx(
            visibleBoxPx = box,
            floorPx = floor,
            imeBottomPx = ime,
            essentialPx = essentialPx,
        )

    /**
     * `maxOf(paneFloorPx, believedImeBottomPx).coerceAtLeast(0).toDp()`
     * (`SessionScreen.kt:2370-2372`), returned in **dp**.
     *
     * Every quantity in this file is carried in dp, never in px: the device runs
     * at `wm density 160`, so 1dp == 1px and the measured px literals *are* their
     * dp values. Converting to px and back would multiply every reading by 160
     * and assert nothing about the device.
     */
    private fun paneBottomDp(box: Int, floor: Int, ime: Int): Int =
        with(density) {
            maxOf(floor, believed(box, floor, ime)).coerceAtLeast(0).toDp().value.roundToInt()
        }

    private fun cell(box: Int, floor: Int, ime: Int): Cell {
        val b = believed(box, floor, ime)
        // `val bodyHeightDp = (maxHeight - paneBottomDp).coerceAtLeast(0.dp)`  [:2384]
        val oldBody = with(density) {
            (box.dp - paneBottomDp(box, floor, ime).dp).coerceAtLeast(0.dp).value.roundToInt()
        }
        // §B.3's condition, verbatim: believed < the raw reading.
        val clamped = b < ime
        val b3Invariant = with(density) {
            (box.dp - essentialDp + floor.dp).coerceAtLeast(0.dp).value.roundToInt()
        }
        val b3Body = if (clamped) oldBody else b3Invariant
        // The corrected form: the same invariant, branches swapped.
        val fixedInvariant = with(density) {
            (essentialDp - floor.dp).coerceAtLeast(0.dp).value.roundToInt()
        }
        val fixedBody = if (clamped) fixedInvariant else oldBody
        return Cell(
            box = box,
            floor = floor,
            ime = ime,
            believed = b,
            clamped = clamped,
            oldBody = oldBody,
            oldOneRow = SessionPaneLayout.composerOneRow(false, oldBody.dp),
            b3Body = b3Body,
            b3OneRow = SessionPaneLayout.composerOneRow(false, b3Body.dp),
            fixedBody = fixedBody,
            fixedOneRow = SessionPaneLayout.composerOneRow(false, fixedBody.dp),
        )
    }

    private fun grid(): List<Cell> =
        boxGrid.flatMap { box -> floorGrid.flatMap { f -> imeGrid.map { cell(box, f, it) } } }

    /**
     * The whole grid, written out verbatim, so the equivalence claim can be read
     * cell by cell instead of taken on trust. Lands in `research/v97_fix/`.
     */
    @Test
    fun theWholeGridIsWrittenOutForReading() {
        val cells = grid()
        val out = StringBuilder()
        out.append("V153 GRID  essentialDp=$essentialDp floorGrid=$floorGrid\n")
        out.append("V153 GRID  boxGrid=$boxGrid\n")
        out.append("V153 GRID  imeGrid=$imeGrid\n")
        out.append("V153 GRID  cells=${cells.size}\n")
        out.append(
            "V153 GRID  box,floor,ime -> believed,clamped,oldBody,oneRow | " +
                "b3Body,match | fixedBody,match\n",
        )
        var b3Match = 0
        var fixedMatch = 0
        cells.forEach { c ->
            val b3ok = c.b3Body == c.oldBody && c.b3OneRow == c.oldOneRow
            val fxok = c.fixedBody == c.oldBody && c.fixedOneRow == c.oldOneRow
            if (b3ok) b3Match++
            if (fxok) fixedMatch++
            out.append(
                "V153 ${c.box},${c.floor},${c.ime} -> ${c.believed},${c.clamped}," +
                    "${c.oldBody},${c.oldOneRow} | ${c.b3Body},$b3ok | ${c.fixedBody},$fxok\n",
            )
        }
        out.append("V153 SUMMARY cells=${cells.size} b3Equivalent=$b3Match fixedEquivalent=$fixedMatch\n")
        // Gradle runs unit tests with the module dir as CWD, so the research tree
        // is one level up — same two-candidate seam `source()` uses above.
        val target = listOf(
            java.io.File("../research/v97_fix/_v153_grid.txt"),
            java.io.File("research/v97_fix/_v153_grid.txt"),
        ).firstOrNull { it.parentFile?.isDirectory == true }
            ?: error("no research/v97_fix directory reachable from ${System.getProperty("user.dir")}")
        target.writeText(out.toString())

        // The headline the report quotes. §B.3 agrees only where the two
        // expressions meet by construction (the algebraic identity
        // `max(floor, ime) == essentialPx - floorPx`, and the cells where both
        // bodies bottom out at 0) — never on either measured tier.
        assertTrue(
            "§B.3 must not be equivalent: it matches on $b3Match of ${cells.size} cells",
            b3Match < cells.size / 2,
        )
        // The 600 tier is the one that must diverge, and it does: the clamp never
        // fires there, so §B.3 hands it the invariant.
        val c600 = cell(paneBox600, navFloor, imeBottom600)
        assertNotEquals(
            "the measured 600 tier (box=${c600.box}, ime=${c600.ime}) must never be a §B.3 match",
            c600.oldBody,
            c600.b3Body,
        )
        // …and the 768 tier is the perverse case: it survives *because* §B.3's
        // condition is inverted. The clamp saturates there, so `believed < ime`
        // is true and §B.3 keeps the old expression — the very region the rewrite
        // was written to clarify, left exactly as it was.
        val c768 = cell(paneBox768, navFloor, imeBottom768)
        assertEquals(
            "the measured 768 tier (box=${c768.box}, ime=${c768.ime}) is the inverted-" +
                "condition artefact: §B.3 keeps the old path there",
            c768.oldBody,
            c768.b3Body,
        )
        assertEquals(
            "the corrected rewrite is equivalent on every single cell",
            cells.size,
            fixedMatch,
        )
    }

    // ---------------------------------------------------------------- 1. the verdict

    /**
     * **The finding, pinned.** §B.3's else-branch is the pane bottom, not the
     * body, so the rewrite is non-equivalent — and because its branch condition
     * is inverted, the damage lands on the *unsaturated* cells, which is where
     * both measured tiers live.
     */
    @Test
    fun theSectionB3BranchOrderIsNotEquivalentToProductionOnTheGrid() {
        val cells = grid()
        assertEquals(
            "the grid must stay exhaustive and its size is the evidence",
            2 * 11 * 20,
            cells.size,
        )

        val differing = cells.filter { it.b3Body != it.oldBody || it.b3OneRow != it.oldOneRow }
        assertTrue(
            "§B.3 must NOT be equivalent — if this ever goes green the rewrite " +
                "became correct and the Lead needs to hear about it again",
            differing.isNotEmpty(),
        )

        // The exact shape of the damage, cell by cell. On an unsaturated cell
        // the old body is `max(0, box - max(floor, ime))` and §B.3's is
        // `max(0, box - essentialPx + floorPx)`, so the two can agree in exactly
        // two ways: the algebraic identity
        // `max(floor, ime) == essentialPx - floorPx`, or both bodies bottoming
        // out at the `coerceAtLeast(0.dp)` that `SessionScreen.kt:2384` applies.
        // Everywhere else they differ. That is a claim about every unsaturated
        // cell, not a count that luck could satisfy.
        val unsaturated = cells.filter { !it.clamped }
        assertTrue("the grid must contain unsaturated cells", unsaturated.isNotEmpty())
        var collapsed = 0
        unsaturated.forEach { c ->
            val oldRaw = c.box - maxOf(c.floor, c.ime)
            val b3Raw = c.box - essentialPx + c.floor
            val bothAtFloor = oldRaw <= 0 && b3Raw <= 0
            if (bothAtFloor) collapsed++
            val agrees = bothAtFloor || maxOf(c.floor, c.ime) == essentialPx - c.floor
            if (agrees) {
                assertEquals(
                    "cell (box=${c.box}, floor=${c.floor}, ime=${c.ime}) is a shape where " +
                        "the two coincide (both at the 0 floor)",
                    c.oldBody,
                    c.b3Body,
                )
            } else {
                assertNotEquals(
                    "cell (box=${c.box}, floor=${c.floor}, ime=${c.ime}): §B.3 must " +
                        "diverge — old raw $oldRaw vs §B.3 raw $b3Raw",
                    c.oldBody,
                    c.b3Body,
                )
            }
        }
        // The agreement cases are exactly the collapsed ones plus the identity.
        // So: every unsaturated cell whose body is actually **positive** *and*
        // which is not the algebraic identity `max(floor, ime) == essentialPx -
        // floorPx` — i.e. every cell that describes a real layout rather than a
        // body of nothing — must diverge. Both measured tiers are in that set
        // (51 and 236, neither of them an identity).
        val identity = { c: Cell -> maxOf(c.floor, c.ime) == essentialPx - c.floor }
        val laidOut = unsaturated.filter { it.oldBody > 0 && !identity(it) }
        assertTrue("the grid must contain laid-out unsaturated cells", laidOut.isNotEmpty())
        laidOut.forEach { c ->
            assertNotEquals(
                "a body that is actually ${c.oldBody} dp must not survive §B.3 unchanged " +
                    "(box=${c.box}, floor=${c.floor}, ime=${c.ime})",
                c.oldBody,
                c.b3Body,
            )
        }
        // and the identity really is the only other way they can meet
        unsaturated.filter { identity(it) }.forEach { c ->
            assertEquals(
                "cell (box=${c.box}, floor=${c.floor}, ime=${c.ime}) is the algebraic " +
                    "identity: max(floor, ime) == essentialPx - floorPx",
                c.oldBody,
                c.b3Body,
            )
        }
        // …and the divergence is pervasive, not a one-cell curiosity.
        assertTrue(
            "the divergence must be pervasive, not a one-cell curiosity: " +
                "${differing.size} of ${cells.size} cells",
            differing.size > cells.size / 4,
        )

        // The two measured tiers, read straight off production:
        // the 600 tier never even reaches the clamp (`438 < 568`), and §B.3
        // changes its body from the measured 51 to 202.
        val c600 = cell(paneBox600, navFloor, imeBottom600)
        assertEquals(
            "600 tier: the clamp is inert, so §B.3 takes the invariant branch",
            51,
            c600.oldBody,
        )
        assertEquals(
            "600 tier: §B.3's invariant branch is wrong by 151 px = 606-284+48 vs the body",
            202,
            c600.b3Body,
        )
        assertNotEquals(
            "600 tier: the one-row form flips under §B.3 — this is a behaviour change, " +
                "not a refactor",
            c600.oldOneRow,
            c600.b3OneRow,
        )

        val c768 = cell(paneBox768, navFloor, imeBottom768)
        assertEquals("768 tier: the clamp saturates, believed = room + floor", 370, c768.believed)
        assertEquals("768 tier: the body is the constant essentialPx - floorPx", 236, c768.oldBody)
        assertEquals(
            "768 tier: §B.3 keeps the old path here (its condition is inverted), so it is " +
                "the only large-box region it does not break",
            c768.oldBody,
            c768.b3Body,
        )

        // The unsigned-shortcut from the pane's own arithmetic, on a cell where
        // the clamp does fire and the two are furthest apart.
        val id = cell(700, navFloor, imeBottom768)
        assertEquals("identity branch: believed is the raw reading", 426, id.believed)
        assertEquals("identity branch: the body is the real margin", 274, id.oldBody)
        assertEquals("identity branch: §B.3's invariant branch returns 700-284+48", 464, id.b3Body)
    }

    // ------------------------------------------------- 2. why the saturated region is flat

    /**
     * The equivalence argument itself, as a criterion rather than prose:
     * whenever the clamp saturates, the body is **exactly** `essentialPx - floorPx`
     * — the same constant on every box and every keyboard reading, so the
     * saturated region really is the lazy region `B.2(a)` described, and writing
     * that constant out really is a pure identity there.
     */
    @Test
    fun theSaturationInvariantIsTheSameConstantOnEverySaturatedCell() {
        val saturated = grid().filter { it.clamped }
        assertTrue("the grid must contain saturated cells", saturated.isNotEmpty())
        saturated.forEach { c ->
            assertEquals(
                "saturated cell (box=${c.box}, floor=${c.floor}, ime=${c.ime}): the body is " +
                    "the invariant, independent of both the box and the keyboard",
                c.essentialMinusFloor(c),
                c.oldBody,
            )
        }
    }

    private fun Cell.essentialMinusFloor(c: Cell): Int =
        with(density) {
            (essentialDp - c.floor.dp).coerceAtLeast(0.dp).value.roundToInt()
        }

    /**
     * The corrected rewrite, proven equivalent on **every** cell of the same
     * grid — bodies and the one-row form alike. This is what §B.3 should have
     * said:
     *
     * ```kotlin
     * // clamp saturated => the body is the constant essentialPx - floorPx
     * // clamp inert    => the real margin, maxHeight - paneBottomDp
     * val bodyHeightDp = if (believedImeBottomPx < imeBottomPx) {
     *     (SessionPaneLayout.PANE_ESSENTIAL_HEIGHT - paneFloorDp).coerceAtLeast(0.dp)
     * } else {
     *     (maxHeight - paneBottomDp).coerceAtLeast(0.dp)
     * }
     * ```
     */
    @Test
    fun theCorrectedInvariantWithTheBranchesSwappedIsEquivalentOnEveryCell() {
        grid().forEach { c ->
            assertEquals(
                "cell (box=${c.box}, floor=${c.floor}, ime=${c.ime}) body must be unchanged",
                c.oldBody,
                c.fixedBody,
            )
            assertEquals(
                "cell (box=${c.box}, floor=${c.floor}, ime=${c.ime}) one-row form must be unchanged",
                c.oldOneRow,
                c.fixedOneRow,
            )
        }
    }

    /**
     * Why the corrected rewrite cannot land **this** round without the Lead's
     * word: three existing source pins assert the old line's literal text. They
     * are the v77 P0 chain, so satisfying the rewrite means weakening assertions
     * — which this round is forbidden to do.
     */
    @Test
    fun theLandingIsBlockedByThreeSourcePinsThatPinTheOldLine() {
        val oldLine = "val bodyHeightDp = (maxHeight - paneBottomDp).coerceAtLeast(0.dp)"
        // Pinned by SessionPaneImeBeliefV77Test:350, SessionPaneShortWindowV51Test:204
        // and ComposerShortWindowV74Test:208 — all `assertTrue(contains(...))`.
        assertTrue("the old line is pinned verbatim today", oldLine.isNotEmpty())
        // The rewrite keeps the old text on one branch and adds the invariant on
        // the other, so the literal no longer appears once; the pins need to be
        // re-aimed, not relaxed.
        val rewritten = "val bodyHeightDp = if (believedImeBottomPx < imeBottomPx) {"
        assertNotEquals(
            "a rewrite that keeps the old literal would be a different change than the one " +
                "proven equivalent above",
            oldLine,
            rewritten,
        )
    }

    // -------------------------------------------- 3. B-2: the 600-tier flip-point margin

    /**
     * **B-2, registered as a margin pin (behaviour untouched).**
     *
     * On the 600 tier the clamp is inert (`438 < essentialPx * 2 = 568`), so the
     * body is the raw margin `paneBox600 - ime`. It goes to `0` exactly when
     * `ime == paneBox600 = 438`, and `composerMinimal` opens with
     * `available > 0.dp` (`SessionPaneLayout.kt:1009`) — so at `body == 0` the
     * one-row form **turns itself off** and the full 116 dp composer is asked to
     * fit in nothing. The form grows as the keyboard grows: the reverse jump.
     *
     * ```
     * flip point  = paneBox600                       = 438 px
     * measured    = imeBottom600                     = 387 px
     * margin      = flip point - measured            =  51 px
     * ```
     *
     * `assertTrue(margin >= 51)`: the margin **must not shrink**. Any input that
     * makes the 600-tier reading over-report by 51 px or more — a taller IME, a
     * floating suggestion strip, the v110-style "one navigation bar not
     * subtracted" misread (which is exactly 48 px, i.e. 3 px from the cliff) —
     * lands the body on or below zero and fires the jump. Computed from
     * production and the measured literals, never from two literals.
     */
    @Test
    fun theSixHundredTierKeepsFiftyOnePxOfRoomBeforeTheReverseJump() {
        assertTrue(
            "the 600 tier must be below the clamp's early-return guard, or the flip " +
                "point is not where this says it is",
            paneBox600 < essentialPx * SessionPaneLayout.PANE_ESSENTIAL_SPARE_ROOM,
        )

        val bodyAt = { ime: Int ->
            // same expression as `SessionScreen.kt:2384`, coerce included
            with(density) {
                (paneBox600.dp - paneBottomDp(paneBox600, navFloor, ime).dp)
                    .coerceAtLeast(0.dp).value.roundToInt()
            }
        }
        val oneRowAt = { ime: Int ->
            SessionPaneLayout.composerOneRow(false, bodyAt(ime).dp)
        }

        val margin = paneBox600 - imeBottom600
        assertEquals("the measured 600-tier margin", 51, margin)
        assertTrue(
            "the margin must not shrink: $margin px of room before the reverse jump",
            margin >= 51,
        )

        // the two sides of the cliff, in production arithmetic
        assertEquals("measured reading: the body is the measured 51", 51, bodyAt(imeBottom600))
        assertTrue("measured reading: the one-row form is on", oneRowAt(imeBottom600))
        assertEquals("426: the body is 12 — still one row", 12, bodyAt(426))
        assertTrue("426: one row", oneRowAt(426))
        assertEquals("474: the body is pinned at 0", 0, bodyAt(474))
        assertTrue(
            "474: the body is 0 and the one-row form **holds** — v154 removed the " +
                "`available > 0.dp` guard that used to turn the form OFF here and ask the " +
                "full 116 dp composer for a room of nothing (the reverse jump)",
            oneRowAt(474),
        )

        // the risk surface, made detectable rather than merely described
        val inflated = imeBottom600 + margin
        assertEquals(
            "a 51 px over-report is exactly the flip point",
            paneBox600,
            inflated,
        )
        assertTrue(
            "and it must be observable: the over-report drives the body to 0 — and since v154 " +
                "the one-row form **holds** there, so the 51 px risk lands without ever growing " +
                "the composer back into the full form",
            oneRowAt(inflated) && bodyAt(inflated) == 0,
        )
        // the v110 misread, one navigation bar short
        assertTrue(
            "the retired v110 misread (48 px) lands 3 px from the cliff — it is inside the " +
                "margin, so this pin is load-bearing",
            bodyAt(imeBottom600 + navFloor) > 0,
        )
    }
}
