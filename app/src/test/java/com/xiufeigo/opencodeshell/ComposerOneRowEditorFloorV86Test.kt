package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import com.xiufeigo.opencodeshell.ui.session.composer.MODEL_CHIP_COMPACT_BELOW
import com.xiufeigo.opencodeshell.ui.session.composer.MODEL_CHIP_MIN_WIDTH
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v86 P0 「720x1280 键盘弹起：一行 composer 里输入框只剩 39 / 25 dp — 用户看不到自己
 * 打的字」: the one-row band's split has a floor on the **editor** side.
 *
 * v81's 0.45 : 1 protects the chip and leaves the editor ~31 % of what is left
 * after the row's fixed children; on the two 720 px windows with the keyboard up
 * that share is smaller than the field's own padding plus a word:
 *
 * ```
 * 720x1280 @420dpi (2.625 px/dp)  window 274.29 dp  card 250.29  leftover 186.29
 *   editor 0.45/1.45 × 186.29 = 57.81 → text band 41.81 dp, and the field scrolls
 *   to the caret, so 「E2ECHKzzE2ECHK5554 hi」 shows as two characters
 *   (`research/v85_verify/5554/22_step2_ime_up_420_typed.png`)
 * 720x1280 @480dpi (3.0 px/dp)    window 240 dp     card 216     leftover 152
 *   editor 47.17 → text band 31.17 dp; the a11y placeholder measured 25.3 dp,
 *   i.e. 「As…」 (`40_480_ime_up.png`)
 * ```
 *
 * The stacked form (the tool row on its own row below the editor) cannot be laid
 * out on either window — the composer already owns the whole pane, and stacking
 * needs a 44 dp tool row *plus* a text line, while the pane caps the card at
 * 44 dp (420 dpi) and 31.33 dp (480 dpi). The band therefore keeps one row and
 * splits with a floor on each side:
 *
 *  * the editor keeps [SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_FLOOR] (64 dp:
 *    16 of field padding + 48 of text — exactly what the narrowest window this
 *    form runs on can pay beside the chip's floor:
 *    `240 − 24 − 64 − 88 = 64`);
 *  * the controls keep [SessionPaneLayout.COMPOSER_ONE_ROW_CONTROLS_FLOOR] —
 *    the chip's own v81 floor, [MODEL_CHIP_MIN_WIDTH] (88 dp = 48 of chrome +
 *    ~40 of label). The chip is the side that yields; its name survives in the
 *    picker and in its own `contentDescription`.
 *
 * What is pinned here is the arithmetic the ghost spacer's weight runs, and the
 * two measured windows it is for. `Row` still divides the band — the editor's
 * share is *measured* from the spacer (`editorShare`), never assumed here.
 */
class ComposerOneRowEditorFloorV86Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val composer =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    private val modelChip =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelChip.kt")

    /** The card's arrangement, for the structural pins. */
    private val arrangement = composer
        .substringAfter("private fun ComposerArrangement(")
        .substringBefore("private fun Modifier.editorBandTap(")

    /** The band the row's weights divide: what its fixed children leave. */
    private fun space(band: Float): Float =
        band - SessionPaneLayout.COMPOSER_ONE_ROW_FIXED_WIDTH.value

    /**
     * The share `Row`'s own weight arithmetic hands the editor at this band width
     * — `space × w / (w + 1)`, the split the ghost spacer reports and the
     * arrangement then measures the field with.
     */
    private fun editorShare(band: Float): Float {
        val w = SessionPaneLayout.composerOneRowEditorWeight(band.dp)
        return space(band) * w / (w + 1f)
    }

    private fun controlsShare(band: Float): Float = space(band) - editorShare(band)

    /** The band above which the editor's floor cannot bind — the ratio's own line. */
    private val bindBand: Float
        get() = SessionPaneLayout.COMPOSER_ONE_ROW_FIXED_WIDTH.value +
            SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_FLOOR.value *
            (SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_WEIGHT + 1f) /
            SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_WEIGHT

    // ------------------------------------------- the roomy bands are untouched

    @Test
    fun `the bands v81 measured keep their split bit for bit`() {
        // The floor can only bind under a 270.22 dp band (64 dp of fixed children
        // plus the 206.22 dp of weight space on which the ratio reaches 64 dp), so
        // every band above it gets v81's constant *itself* back — the same Float,
        // not a value that rounds to it — and `Row` divides the band to the same
        // pixel it did before v86.
        assertEquals(270.2222f, bindBand, 0.001f)
        for (band in listOf(270.3f, 271f, 300f, 320f, 548.29f, 800f)) {
            assertEquals(
                "band $band",
                SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_WEIGHT,
                SessionPaneLayout.composerOneRowEditorWeight(band.dp),
                0.0f,
            )
        }
        // …and the share those bands produce is the v81 ratio arithmetic on the nose.
        for (band in listOf(300f, 548.29f)) {
            assertEquals(
                "band $band",
                space(band) * SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_WEIGHT /
                    (SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_WEIGHT + 1f),
                editorShare(band),
                0.01f,
            )
        }
        // Under it the floor is what moves, and it moves the editor *up*: a hair
        // narrower than the line the weight is already above the constant.
        assertTrue(
            SessionPaneLayout.composerOneRowEditorWeight(270.dp) >
                SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_WEIGHT,
        )
        assertEquals(64f, editorShare(270f), 0.05f)
    }

    // -------------------------------------------------- the two measured windows

    @Test
    fun `the 420 keyboard band pays the editor's floor out of the chip's slack`() {
        // 720x1280 @420dpi, keyboard up: the composer card is the 274.29 dp window
        // minus the region's 12 + 12 dp of padding, and it is the tool row's
        // available width. Measured on the device: the card [401,515] px = 44 dp,
        // its controls centred on y = 459 px.
        val card = 274.29f - 24f
        assertEquals(250.29f, card, 0.01f)
        val editor = editorShare(card)
        val controls = controlsShare(card)
        // The editor lands on its floor, and the text band the user reads grows
        // from 41.81 dp to 48 dp (+15 %; the a11y EditText measured 38.9 dp,
        // +23 % on the device reading).
        assertEquals(SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_FLOOR.value, editor, 0.05f)
        assertEquals(
            SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_TEXT_FLOOR.value,
            editor - SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_PADDING.value,
            0.05f,
        )
        // The chip is *not* touched on this window: its 420 dp form is content-sized
        // at 118.5 dp (the a11y 「Model」 node [268,422][579,496] = 311 px), and the
        // 122.29 dp the controls keep is above it, so the full
        // 「GPT-6.1 Sol ▾」 label stays whole.
        assertTrue("controls $controls", controls >= 118.5f)
        assertTrue("controls $controls", controls >= SessionPaneLayout.COMPOSER_ONE_ROW_CONTROLS_FLOOR.value)
        // The chip is still the larger half.
        assertTrue("editor $editor vs controls $controls", editor < controls)
    }

    @Test
    fun `the 480 keyboard band splits exactly between the two floors`() {
        // 720x1280 @480dpi, keyboard up: a 240 dp window, a 216 dp card. This is
        // the boundary case the editor's floor is derived from, so both numbers are
        // exact rather than slack.
        val card = 240f - 24f
        assertEquals(216f, card, 0.01f)
        val editor = editorShare(card)
        val controls = controlsShare(card)
        assertEquals(SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_FLOOR.value, editor, 0.05f)
        assertEquals(48f, editor - SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_PADDING.value, 0.05f)
        // The chip keeps its whole v81 floor — it is not pushed under it, and it
        // keeps the 48 dp of chrome (icon + chevron) that make it look tappable:
        // 12 dp of padding + the 16 dp icon + 4 + 2 dp of spacers + the 14 dp chevron.
        assertEquals(MODEL_CHIP_MIN_WIDTH.value, controls, 0.05f)
        assertEquals(48f, 12f + 16f + 4f + 2f + 14f, 0.0f)
        val labelRoom = controls - 48f
        assertTrue("label room $labelRoom", labelRoom >= 40f - 0.05f)
        // The label room still fits the v81 rulers: six lowercase characters
        // (6.6 dp each) or five caps-heavy ones (8 dp each) beside the ellipsis.
        assertTrue("label chars ${labelRoom / 6.6f}", labelRoom / 6.6f >= 6f)
        assertTrue("caps label room $labelRoom", labelRoom >= 5f * 8f - 0.05f)
        // …and it is above the compact threshold, so the model name still has a
        // label rather than disappearing into the icon.
        assertTrue(MODEL_CHIP_COMPACT_BELOW.value <= controls)
        // What the editor gained, against the band the bug report measured: 31.17 dp
        // of text before (the a11y placeholder 25.3 dp = 「As…」), 48 dp after.
        val before = space(card) * SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_WEIGHT /
            (SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_WEIGHT + 1f)
        assertEquals(47.17f, before, 0.05f)
        assertTrue("text band ${editor - 16f} vs ${before - 16f}", editor - 16f > before - 16f)
        // The chip gave up exactly the surplus the ratio had left it above its floor
        // — 104.83 dp was 16.83 dp more than the chip can use, and that is what the
        // editor's text band gained.
        assertEquals(104.83f, space(card) - before, 0.05f)
        assertEquals(16.83f, editor - before, 0.05f)
    }

    @Test
    fun `the editor's floor is exactly what the narrowest window can pay`() {
        // 240 dp (the 480 dpi window) − the region's 12 + 12 dp of padding − 64 (the
        // row's fixed children) − 88 (the chip's floor) = 64: the floor is not a taste
        // number, it is what the narrowest window the one-row form runs on can pay.
        // (`composerRegion`'s own horizontal padding, the same pair the v81 suite
        // pins: `padding(start = 12.dp, end = 12.dp, …)`.)
        // v111: this was the literal `top = 4.dp, bottom = 8.dp`. It is still an
        // **exact literal** pin — of the split v110 actually shipped
        // (`top = 0.dp, bottom = 12.dp`) — and it is still exact for the reason the
        // comment above already gave: this suite's own author wrote the pin as
        // `padding(start = 12.dp, end = 12.dp, …)`, eliding the vertical pair on
        // purpose, because `240 − 24 − 64 − 88 = 64` consumes the **horizontal** 12 + 12
        // and the 12 dp region padding as a *total*. So the split it was really
        // after is pinned verbatim, and the two numbers the equation below
        // actually consumes are now pinned as numbers instead of hiding in a
        // literal. Same strength, plus two explicit numbers where there were none.
        val region =
            source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        assertTrue(
            region.contains(
                ".padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 12.dp)",
            ),
        )
        assertTrue(
            "the region's side padding is still 12 + 12 — the 24 the equation subtracts",
            region.contains("start = 12.dp, end = 12.dp"),
        )
        assertEquals(
            "and its vertical total is still the 12 dp the 240 dp equation adds back",
            SessionPaneLayout.COMPOSER_REGION_PADDING.value,
            0f + 12f,
            0.0f,
        )
        assertEquals(
            216.dp,
            SessionPaneLayout.COMPOSER_ONE_ROW_FIXED_WIDTH +
                SessionPaneLayout.COMPOSER_ONE_ROW_CONTROLS_FLOOR +
                SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_FLOOR,
        )
        assertEquals(
            240.dp,
            SessionPaneLayout.COMPOSER_ONE_ROW_FIXED_WIDTH +
                SessionPaneLayout.COMPOSER_ONE_ROW_CONTROLS_FLOOR +
                SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_FLOOR +
                12.dp + 12.dp,
        )
        assertEquals(
            SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_PADDING +
                SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_TEXT_FLOOR,
            SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_FLOOR,
        )
        assertEquals(64.dp, SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_FLOOR)
        assertEquals(16.dp, SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_PADDING)
        assertEquals(48.dp, SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_TEXT_FLOOR)
        assertEquals(64.dp, SessionPaneLayout.COMPOSER_ONE_ROW_FIXED_WIDTH)
        // The controls' floor is the chip's own constant, and the editor's is under
        // it — the editor is a text surface, the chip's label is not the thing being
        // typed on.
        assertEquals(SessionPaneLayout.COMPOSER_ONE_ROW_CONTROLS_FLOOR, MODEL_CHIP_MIN_WIDTH)
        assertEquals(88.dp, MODEL_CHIP_MIN_WIDTH)
        assertTrue(
            SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_FLOOR <
                SessionPaneLayout.COMPOSER_ONE_ROW_CONTROLS_FLOOR,
        )
    }

    // ------------------------------------------------------- the arithmetic itself

    @Test
    fun `the split is monotone, continuous and keeps both floors where it can`() {
        var previous = -1f
        var band = 60.dp
        while (band <= 720.dp) {
            val width = band.value
            val weight = SessionPaneLayout.composerOneRowEditorWeight(band)
            assertTrue("band $width: weight $weight", weight > 0f && weight.isFinite())
            val room = space(width)
            if (room > 0f) {
                val editor = editorShare(width)
                val controls = room - editor
                assertTrue("band $width: editor $editor", editor >= 0f && editor <= room)
                // Never under the editor's floor where the band can pay it beside
                // the controls' floor…
                if (room >= SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_FLOOR.value +
                    SessionPaneLayout.COMPOSER_ONE_ROW_CONTROLS_FLOOR.value
                ) {
                    assertTrue(
                        "band $width: editor $editor",
                        editor >= SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_FLOOR.value - 0.01f,
                    )
                }
                // …and the controls' viewport is never under the chip's floor where
                // the *editor's* floor leaves room for it: above a 127.6 dp weight
                // space (the band where `space − 88` reaches the ratio) the chip
                // keeps its whole floor, and below it the chip is the side that
                // yields — the ratio stands, exactly as v81 had it.
                if (room >= 128f) {
                    assertTrue(
                        "band $width: controls $controls",
                        controls >= SessionPaneLayout.COMPOSER_ONE_ROW_CONTROLS_FLOOR.value - 0.01f,
                    )
                }
                // The chip keeps the larger half on every band — the editor is one
                // line, the controls are the row's other four children.
                assertTrue("band $width: editor $editor controls $controls", editor <= controls + 0.01f)
                // A narrowing band can only take width away from the editor, never
                // hand it more (the one visible 720 dpi symptom of a policy that read
                // the wrong number here would be a flip at some band width, and this
                // sweep is what forbids it).
                if (previous >= 0f) {
                    assertTrue("band $width went backwards: $previous → $editor", editor >= previous - 0.02f)
                }
                previous = editor
            }
            band += 0.5.dp
        }
        assertTrue(previous > 0f)
    }

    @Test
    fun `the fixed children and the editor's padding are the row's own`() {
        // The row's fixed children: the ⊞ (the add menu), the two 4 dp gaps and Send.
        assertTrue(composer.contains("private val SUBMIT_SIZE = 28.dp"))
        assertTrue(composer.contains(".size(SUBMIT_SIZE)"))
        val addMenu = composer
            .substringAfter("private fun AddMenuButton(")
            .substringBefore("private fun AddMenuRow(")
        assertTrue(addMenu.contains(".size(28.dp)"))
        assertTrue(composer.contains("Spacer(Modifier.width(4.dp))"))
        assertTrue(composer.contains("private val TOOL_ROW_HEIGHT = 44.dp"))
        assertEquals(
            "the mirrors above must sum to the constant: 28 + 4 + 4 + 28",
            SessionPaneLayout.COMPOSER_ONE_ROW_FIXED_WIDTH.value,
            28f + 4f + 4f + 28f,
            0.0f,
        )
        // …and the field's own padding the editor's floor is built from is the 12 + 4
        // of its minimal chain (v80's one chain).
        assertTrue(composer.contains("start = if (minimal) 12.dp else 16.dp"))
        assertTrue(composer.contains("end = if (minimal) 4.dp else 16.dp"))
        assertEquals(
            SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_PADDING.value,
            12f + 4f,
            0.0f,
        )
        // The chip's floor is the chip's own: 48 dp of chrome + ~40 of label.
        assertTrue(modelChip.contains(".widthIn(min = MODEL_CHIP_MIN_WIDTH, max = widthCeiling)"))
        assertTrue(modelChip.contains(".padding(horizontal = if (compact) 2.dp else 6.dp)"))
        assertTrue(modelChip.contains("modifier = Modifier.size(16.dp)"))
        assertTrue(modelChip.contains("Spacer(Modifier.width(if (compact) 2.dp else 4.dp))"))
        assertTrue(modelChip.contains("Spacer(Modifier.width(if (compact) 0.dp else 2.dp))"))
        assertTrue(modelChip.contains("modifier = Modifier.size(if (compact) 12.dp else 14.dp)"))
        assertTrue(MODEL_CHIP_COMPACT_BELOW < MODEL_CHIP_MIN_WIDTH)
    }

    // ------------------------------------------------ the band inside the card

    @Test
    fun `the band never exceeds the card it is drawn in`() {
        // v86: the band is clamped to the card, so a card the pane caps under 44 dp
        // shrinks the tool row instead of overflowing it into the card's `clip`.
        assertTrue(
            arrangement.contains(
                "val band = minOf(BAND_HEIGHT.roundToPx(), constraints.maxHeight)",
            ),
        )
        // v106（方案 A）: the clamp itself is the "只在需要时增高" guard and must not be
        // weakened away — `BAND_HEIGHT` is 48 dp now, and the card the pane caps under
        // it still shrinks the band. Pinned strictly, both halves of the guard.
        assertEquals(
            "the band must be tall enough to hold the enforced touch target",
            SessionPaneLayout.MIN_INTERACTIVE_COMPONENT,
            SessionPaneLayout.composerBandHeightDp(44.dp),
        )
        // The measured 480 dpi keyboard card is 31.33 dp. With the flat 44 dp band
        // the 28 dp controls were centred in the 44 dp row, so their bottom edge
        // stood `44/2 + 14 = 36` from the card's top — 4.67 dp *below* the card's
        // own bottom edge, which is exactly the slice the card's `clip` took off
        // Send (`research/v85_verify/5554/40_480_ime_up.png`).
        val card = 31.33f
        val flat = 44f
        assertEquals(6.335f, flat / 2f - card / 2f, 0.01f)
        assertEquals(4.67f, flat / 2f + 14f - card, 0.01f)
        // Clamped, the same controls are whole inside the card.
        val clamped = minOf(flat, card)
        assertEquals(card, clamped, 0.0f)
        assertEquals(29.665f, clamped / 2f + 14f, 0.01f)
        assertTrue(clamped / 2f + 14f <= card + 0.01f)
        assertTrue(clamped / 2f - 14f >= -0.01f)
        // The v84 ladder's own line — the least composer it promises a positive band
        // (`COMPOSER_ROW_FLOOR` + `COMPOSER_REGION_PADDING` = 40 dp of pane) — leaves
        // a 28 dp card: the flat band would clip a third of Send, the clamp makes the
        // row exactly the card the ladder paid for.
        val lineCard = SessionPaneLayout.composerCeiling(40.dp).value -
            SessionPaneLayout.COMPOSER_REGION_PADDING.value
        assertEquals(40f, SessionPaneLayout.composerCeiling(40.dp).value, 0.01f)
        assertEquals(28f, lineCard, 0.01f)
        assertEquals(28f, minOf(flat, lineCard), 0.01f)
        // …and every card that can pay the band is unchanged.
        for (canPay in listOf(44f, 56f, 96f, 115.05f, 250.29f)) {
            assertEquals("card $canPay", flat, minOf(flat, canPay), 0.0f)
        }
        // Not the numbers, the frame: 720x1280 @420dpi's pane is 67.05 dp, the
        // composer ceiling takes it to the one-row form's 56 dp and the region's
        // padding leaves a 44 dp card — i.e. the clamp is inert on the window the
        // other half of this fix moves, which is why the 420 frame's controls were
        // never clipped.
        assertEquals(56.dp, SessionPaneLayout.composerCeiling(67.05.dp))
        assertEquals(44f, cardOn420(), 0.01f)
        assertEquals(44f, minOf(flat, cardOn420()), 0.0f)
    }

    /** 720x1280 @420dpi keyboard up: pane 67.05 dp → ceiling 56 → card 44 dp. */
    private fun cardOn420(): Float = SessionPaneLayout.composerCeiling(67.05.dp).value -
        SessionPaneLayout.COMPOSER_REGION_PADDING.value

    @Test
    fun `the band's width reaches the weight inside the measure pass`() {
        // The band is the tool row's available width, and `BoxWithConstraints`
        // composes its content inside its own measure pass — so a narrow band's
        // weight is this frame's, with no write from layout back into composition
        // (the v57 oscillation) and no second frame.
        assertTrue(arrangement.contains("BoxWithConstraints {"))
        assertTrue(arrangement.contains("val bandWidth = maxWidth"))
        assertEquals(
            1,
            Regex("""\.weight\(SessionPaneLayout\.composerOneRowEditorWeight\(bandWidth\)\)""")
                .findAll(arrangement).count(),
        )
        // The share is still *measured*: the ghost reports it in the same pass and
        // the arrangement measures the editor with exactly that number.
        assertTrue(arrangement.contains(".onSizeChanged { editorShare = it.width },"))
        assertTrue(arrangement.contains("maxWidth = editorShare.coerceIn(0, constraints.maxWidth),"))
        val toolRowMeasure = arrangement.indexOf("measurables[1].measure(")
        val editorMeasure = arrangement.indexOf("measurables[0].measure(")
        assertTrue(toolRowMeasure in 1 until editorMeasure)
        // Still one editor and one tool row, mounted once each: the wrapper encloses
        // the tool row, it is not a third child and not a second call site.
        assertEquals(1, Regex("""editor\(\)""").findAll(arrangement).count())
        assertEquals(1, Regex("""controls\(\)""").findAll(arrangement).count())
        assertEquals(3, Regex("""if \(minimal\) \{""").findAll(arrangement).count())
        // The weight has one home, and it is the band's function; the editor's own
        // chain carries none (v80/v81's invariant, restated where v86 could break it).
        val paneLayout = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt")
        assertTrue(paneLayout.contains("val COMPOSER_ONE_ROW_EDITOR_WEIGHT: Float = 0.45f"))
        assertTrue(paneLayout.contains("fun composerOneRowEditorWeight(bandWidth: Dp): Float {"))
        val code = composer
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("(?m)^\\s*//.*$"), " ")
        assertEquals(
            "one weighted ghost in the whole file",
            1,
            Regex("""composerOneRowEditorWeight\(bandWidth\)""").findAll(code).count(),
        )
        assertFalse(composer.contains(".weight(1f, fill = false)"))
    }
}
