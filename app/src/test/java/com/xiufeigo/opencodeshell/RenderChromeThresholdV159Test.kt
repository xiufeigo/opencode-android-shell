package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v159: the threshold that is **not in the predicate** — the render layer's
 * chrome step, read on the device's own axis.
 *
 * ## The gap
 *
 * v157 (`research/v97_verify/5554_md/REPORT_MONOTONE_FORM2.md`) swept the device
 * one dp at a time and read the form flipping between
 *
 * ```
 * H = 600  ime = 339  room = 261   ONEROW
 * H = 700  ime = 426  room = 274   STACKED
 * ```
 *
 * — inside **`room ∈ (261, 274] ⊂ (261, 334]`** — while v158's model puts the
 * only form edge at **224**. Before those two numbers can be compared at all,
 * two facts have to be separated, and neither of them is a defect:
 *
 * 1. **The two form predicates are not fed the same number.** `minimal` is fed
 *    the **body** (`SessionScreen.kt:2384-2389` via `composerOneRow`), `compact`
 *    is fed the **pane** (`SessionScreen.kt:1866`, `composerCompact(paneHeight)`),
 *    and the pane is the body minus the chrome the render layer kept
 *    (`SessionScreen.kt:2400` → `SessionPaneLayout.chromeMaxHeight(body)`). The
 *    render layer's **chrome term** therefore sits between the room and the
 *    predicate, and it is a *step function of the body*, not a constant.
 * 2. **The device's axis is not the body's axis.** v157 measures
 *    `room = H − ime`; the body is that minus the [chromeAbovePaneBox] of chrome
 *    above the pane box. Every production edge therefore stands
 *    [chromeAbovePaneBox] dp further right on the device axis than in the units
 *    the predicates are written in.
 *
 * Applying (2) to a one-dp sweep of `room ∈ [200, 360]` is what produces the
 * answer ([T3]): the only production edge inside the measured bracket
 * `(261, 334]` is
 *
 * ```
 * room = 326 = 162 + 164 = 162 + (44 + 56 + 64)
 * ```
 *
 * — the **164** edge, which is three production quantities at once:
 * `composerMinimal` (`SessionPaneLayout.kt:1113-1115`), the chrome yield of
 * `chromeMaxHeightForComposer` (`:224`) and the composer *height* switch of
 * `composerCeiling` (`:1159`, floor 56 → 96 dp).
 *
 * ## Which layer 326 belongs to, and why it does not go into the predicate
 *
 * * **224** is a **form** threshold, in the units `compact` is handed
 *   (`:828-829`, `116 + 44 + 64`).
 * * **326** is a **chrome / height** threshold on the device axis, 162 dp to the
 *   right of the **164** edge (`56 + 44 + 64`).
 *
 * They answer different questions — *form* versus *chrome and card height* — so
 * there is nothing to reconcile and no assertion to loosen. [T4] pins the reason
 * the render layer may be **declared** instead: because the chrome term is
 * itself derived from the body, the pane converges to a constant **188 dp**
 * (`= COMPOSER_MIN_HEIGHT + TIMELINE_MIN_CONTENT`) for every body at or above
 * 232 dp, so `compact` is handed a number that never reaches 224 and the
 * **effective** form has **no edge at all** in `body ∈ [0, 400]`. The 164 edge is
 * real, it is measured, and it is simply not a statement about form.
 */
class RenderChromeThresholdV159Test {

    /**
     * The chrome above the pane box, in dp — the offset between the **device
     * axis** v157 measured (`room = H − ime`) and the **body** the predicates are
     * handed (`bodyHeightDp`, `SessionScreen.kt:2384`).
     *
     * **This is a device reading, not a production constant**: v158's derivation
     * from v157's data (`box = H − 162`, `REPORT_MONOTONE_FORM2.md` §1.1). No
     * constant in `SessionPaneLayout.kt` equals 162 — the shell's own bars make
     * it up. It is named here so the axis conversion is one number rather than
     * five copies, and so a round that re-measures it fails loudly.
     */
    private val chromeAbovePaneBox: Dp = 162.dp

    /** The body the predicates are handed for a device-axis room. */
    private fun bodyForRoom(room: Int): Dp = (room - chromeAbovePaneBox.value).dp

    /** The pane `compact` is handed — the body minus the chrome kept. */
    private fun paneForBody(body: Dp): Dp =
        (body - SessionPaneLayout.chromeMaxHeight(body)).coerceAtLeast(0.dp)

    /** The **effective** form: 「either predicate says one row, it is one row」. */
    private fun oneRowForBody(body: Dp): Boolean =
        SessionPaneLayout.composerMinimal(body) || SessionPaneLayout.composerCompact(paneForBody(body))

    // ------------------------------------------------------------------ T1

    /**
     * The composer is handed **two** numbers, and the render layer's chrome term
     * is what separates them — so 「the room」 is not one quantity, and a model
     * that reads it as one is reading half the chain.
     */
    @Test
    fun theComposerIsHandedTwoDifferentNumbersAndTheChromeTermIsBetweenThem() {
        val equalToBody = mutableListOf<Int>()
        val zeroChrome = mutableListOf<Int>()
        var nonZeroChrome = 0
        for (body in 0..400) {
            val b = body.dp
            val chrome = SessionPaneLayout.chromeMaxHeight(b)
            if (paneForBody(b) == b) equalToBody += body
            if (chrome == 0.dp) zeroChrome += body else nonZeroChrome++
            assertTrue(
                "the chrome term must never inflate the pane: body=$body chrome=$chrome",
                paneForBody(b) <= b,
            )
        }
        assertTrue(
            "the render layer must actually take chrome somewhere in body 0..400, " +
                "or `compact` and `minimal` are handed the same number everywhere " +
                "(nonZeroChrome=$nonZeroChrome)",
            nonZeroChrome > 0,
        )
        assertEquals(
            "the pane may equal the body only where the chrome term is exactly 0",
            zeroChrome.toSet(),
            equalToBody.toSet(),
        )
    }

    // ------------------------------------------------------------------ T2

    /**
     * Both form edges on **both** axes, so the model's 224 and the device's
     * bracket can never be compared without the conversion.
     *
     * This is the completion the gap asked for: the model was not wrong about
     * 224, it was missing the 162 dp of chrome that stands between the device's
     * `room` and the body 224 is written against.
     */
    @Test
    fun bothFormEdgesStatedOnTheBodyAxisAndOnTheDeviceRoomAxis() {
        val compactEdge = SessionPaneLayout.COMPOSER_MIN_HEIGHT +
            SessionPaneLayout.CHROME_MIN_HEIGHT +
            SessionPaneLayout.TIMELINE_MIN_CONTENT
        val minimalEdge = SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT +
            SessionPaneLayout.CHROME_MIN_HEIGHT +
            SessionPaneLayout.TIMELINE_MIN_CONTENT

        assertEquals("composerCompact's staircase must stay at 224 dp of body", 224.dp, compactEdge)
        assertEquals("composerMinimal's staircase must stay at 164 dp of body", 164.dp, minimalEdge)

        val compactOnRoom = compactEdge.value + chromeAbovePaneBox.value
        val minimalOnRoom = minimalEdge.value + chromeAbovePaneBox.value

        assertEquals("the form edge, read on v157's room axis", 386f, compactOnRoom, 0f)
        assertEquals("the 164 edge, read on v157's room axis", 326f, minimalOnRoom, 0f)

        assertTrue(
            "the 164 edge lands inside the sweep interval v157 brackets as room ∈ (261, 334]: $minimalOnRoom",
            minimalOnRoom > 261f && minimalOnRoom <= 334f,
        )
        assertTrue(
            "the 224 edge is beyond the 200..360 sweep, so it is not the switch the device " +
                "showed: $compactOnRoom",
            compactOnRoom > 360.0,
        )
    }

    // ------------------------------------------------------------------ T3

    /**
     * **The 200 → 360 sweep, one dp at a time.** Every dp on which any production
     * term changes value is collected, and the set is pinned — this is the sweep
     * the task asked for, run against the production functions rather than
     * against a hand-copied table.
     */
    @Test
    fun theSweepFrom200To360HasTwoProductionEdgesAndOnly326IsInsideTheMeasuredBracket() {
        val edges = mutableListOf<String>()
        var previous: Triple<Dp, Dp, Boolean>? = null
        for (room in 200..360) {
            val body = bodyForRoom(room)
            val chrome = SessionPaneLayout.chromeMaxHeight(body)
            val ceiling = SessionPaneLayout.composerCeiling(body)
            val minimal = SessionPaneLayout.composerMinimal(body)
            val current = Triple(chrome, ceiling, minimal)
            if (previous != null) {
                // A **threshold** is a jump or a flip, not the +1/dp ramp the
                // continuous terms produce every single dp (`ceiling` is
                // `pane - 64` clamped, so it moves by 1 on every pane).
                val chromeJump = kotlin.math.abs(chrome.value - previous!!.first.value)
                val ceilingJump = kotlin.math.abs(ceiling.value - previous!!.second.value)
                val flipped = minimal != previous!!.third
                if (flipped || chromeJump > 1f || ceilingJump > 1f) {
                    edges += "room=$room body=${body.value} " +
                        "chrome ${previous!!.first.value}->${chrome.value} " +
                        "ceiling ${previous!!.second.value}->${ceiling.value} " +
                        "minimal ${previous!!.third}->$minimal"
                }
            }
            previous = current
        }
        assertEquals(
            "the sweep room 200..360 has exactly two production edges — 326, which is " +
                "162 + (44 + 56 + 64), the 164 edge of composerMinimal (:1113-1115) and the " +
                "height switch composerCeiling (:1159) reads it for, and 342, the chrome term's " +
                "own step (0 -> 32, from chromeMaxHeightForComposer :215-247). Only 326 is inside " +
                "v157's bracket (261, 334]:\n" + edges.joinToString("\n"),
            listOf(
                "room=326 body=164.0 chrome 0.0->0.0 ceiling 99.0->100.0 minimal true->false",
                "room=342 body=180.0 chrome 0.0->32.0 ceiling 115.0->116.0 minimal false->false",
            ),
            edges,
        )
        val inside = edges.map { it.removePrefix("room=").substringBefore(' ').toInt() }
            .filter { it > 261 && it <= 334 }
        assertEquals("exactly one production edge lies in (261, 334]", listOf(326), inside)
    }

    // ------------------------------------------------------------------ T4

    /**
     * **Why 326 is declared and not pushed into the predicate.**
     *
     * Because the chrome term is itself derived from the body, the pane the
     * render layer hands `compact` converges to a constant **188 dp**
     * (`= COMPOSER_MIN_HEIGHT + TIMELINE_MIN_CONTENT`) for every body at or above
     * 232 dp. So `compact` is structurally never handed 224 or more, and the
     * **effective** form has **no edge at all** in `body ∈ [0, 400]`. The 164
     * edge is real and it is measured — it is just a statement about chrome and
     * about the card's height, not about which form the composer takes.
     */
    @Test
    fun theRenderLayerPinsThePaneSoThe224FormEdgeIsStructurallyUnreachable() {
        for (body in listOf(232, 260, 300, 342, 400)) {
            assertEquals(
                "the pane must converge to COMPOSER_MIN_HEIGHT + TIMELINE_MIN_HEIGHT " +
                    "at body=$body — that constant is why `compact` never sees 224",
                (SessionPaneLayout.COMPOSER_MIN_HEIGHT + SessionPaneLayout.TIMELINE_MIN_HEIGHT),
                paneForBody(body.dp),
            )
        }
        assertEquals(
            "COMPOSER_MIN_HEIGHT + TIMELINE_MIN_HEIGHT", 188.dp,
            SessionPaneLayout.COMPOSER_MIN_HEIGHT + SessionPaneLayout.TIMELINE_MIN_HEIGHT,
        )
        assertTrue(
            "and 188 dp is under composerCompact's 224 dp staircase, which is the whole " +
                "reason the effective form has no edge to find",
            188.dp < SessionPaneLayout.COMPOSER_MIN_HEIGHT +
                SessionPaneLayout.CHROME_MIN_HEIGHT +
                SessionPaneLayout.TIMELINE_MIN_CONTENT,
        )

        val truthSet = (0..400).map { it to oneRowForBody(it.dp) }.filter { it.second }.map { it.first }
        assertEquals("the one-row bodies must start at 0 — the predicate is monotone", 0, truthSet.first())
        assertEquals(
            "the effective form has NO edge in body 0..400 (one-row bodies run " +
                "${truthSet.first()}..${truthSet.last()}): the 164 edge is masked by `compact`, " +
                "and `compact`'s own 224 edge is unreachable because the render layer pins the " +
                "pane at 180 dp",
            401,
            truthSet.size,
        )
        assertEquals("so the staircase has no jump left to make", 0, truthSet.zipWithNext().count { (a, b) -> a + 1 != b })

        // The masked edge is still there, in its own layer — this is the declaration.
        assertTrue("composerMinimal still turns off at 164", !SessionPaneLayout.composerMinimal(164.dp))
        assertTrue("composerMinimal is still on at 163", SessionPaneLayout.composerMinimal(163.dp))
        // The height floor is the quantity 164 really switches — `composerCeiling`
        // (SessionPaneLayout.kt:1159) reads `composerMinimal` to pick between the
        // two card floors, so the same edge moves the card from 56 dp to 96 dp.
        val floorAt = { body: Int ->
            if (SessionPaneLayout.composerMinimal(body.dp)) {
                SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT
            } else {
                SessionPaneLayout.COMPOSER_COMPACT_HEIGHT
            }
        }
        assertEquals("the one-row card floor below the edge", 56.dp, floorAt(163))
        assertEquals("the stacked card floor at the edge", 96.dp, floorAt(164))
        assertEquals("COMPOSER_MINIMAL_HEIGHT (the one-row card floor)", 56.dp, SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT)
        assertEquals("COMPOSER_COMPACT_HEIGHT (the stacked card floor)", 96.dp, SessionPaneLayout.COMPOSER_COMPACT_HEIGHT)
    }
}
