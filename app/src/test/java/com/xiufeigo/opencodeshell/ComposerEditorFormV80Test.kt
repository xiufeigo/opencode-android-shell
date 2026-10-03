package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.ShellChromeBudget
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v80 P0 「720x1280 @420dpi composer 首击被吞」: the form flip must not *rebuild*
 * the editor.
 *
 * v79 kept the two forms as two `if (minimal)` branches, each with its own
 * `editorField(…)` call — and a different parent for the field (a `Row` in the
 * one-row form, the card's `Column` in the stacked one). Two call sites means the
 * flip disposes the focused field and composes a fresh one, and v79 answered that
 * with a `LaunchedEffect(minimal)` that re-requested the caret one frame later.
 * On device that lost the race
 * (`research/v79_verify/5554/32-36`, 720x1280 @420dpi, `dumpsys window` for
 * `mInputShown`):
 *
 * ```
 * T1..T4  tap composer  → mInputShown=false, no caret, injected text nowhere
 * T5      tap composer  → the keyboard finally sticks
 * 1 in 3  taps          → a stray character, the re-create catching an IME commit
 * ```
 *
 * — the flip's own disposal had already asked the platform to hide the keyboard,
 * the re-focus landed on a field whose input connection was on its way down, the
 * body grew back, the form flipped *back* (a half of the flip the effect, gated
 * on `minimal`, never spoke about), and the pane settled where it started.
 *
 * v80 removes the rebuild instead of compensating for it: the editor has **one**
 * call site and **one** parent across both forms, and only the *values* of the
 * field's chain follow the form — a value change updates nodes in place and never
 * disposes them, so the focus, the input session and the IME connection survive
 * the flip by construction. The first tap then behaves exactly as it does on a
 * short window, where the form is already minimal and never flips
 * (`research/v79_verify/5554/12_480_nokb.png` → `13_480_kb.png`: one tap,
 * keyboard up, caret in the field).
 *
 * v81 P0 keeps that and completes it for the card's other half: the editor and
 * the tool row are now the two children of one `Layout` in both forms
 * ([com.xiufeigo.opencodeshell.ui.session.composer.ComposerArrangement]), so a
 * flip neither disposes nor *moves* anything — the controls' v75
 * `movableContentOf` slot is retired, and with it the move that threw
 * `Cannot insert LayoutNode@… because it already has a parent` on the 420 window
 * (`research/v80_verify/5554/app_fatals.txt`, 4/4 opening a session).
 *
 * The geometry the flip produces is unchanged, and this file pins both forms'
 * arithmetic:
 *
 * ```
 * one row   ComposerArrangement(minimal) — one Layout, two children:
 *             editor(fill, bounded by the band's editor share)
 *             toolRow(fillMaxWidth + 44 dp) { ghost(weight = the band's, v86); controls(weight 1f) }
 * stacked   ComposerArrangement(stacked) — the same two children:
 *             editor(fill, bounded by card − TOOL_ROW_HEIGHT)
 *             toolRow(fillMaxWidth + 44 dp + 8 dp) { ghost(no weight); controls }
 * ```
 *
 * The one-row share is still the row's own weight arithmetic, not a number copied
 * into the arrangement: the tool row's first child is a zero-width spacer
 * carrying [SessionPaneLayout.composerOneRowEditorWeight] of the band's own width
 * — v81's [SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_WEIGHT] wherever that ratio
 * already pays the editor's floor, and the weight that pays it where the band is
 * too narrow (v86) — against the controls' `1f`, and the arrangement measures the
 * editor with the width that
 * spacer reported. `fillMaxWidth` then makes the field *take* that bound —
 * `TextFieldDelegate.layoutText` fixes the width to the bound when
 * `minWidth == maxWidth` ("if minWidth == maxWidth the width is fixed",
 * foundation 1.7.6) and would otherwise leave it at the content's intrinsic
 * width. v81 narrows the share from 1/2 against `1f` (0.45): on a 240 dp
 * window the chip's viewport grows from ~77 dp to ~105 dp, so the label
 * survives beside its chevron (`research/v80_verify/5554/16_480_session.png`,
 * `research/v81_verify/5554/s18_chip_crop.png`), while the editor — one line,
 * its text elastic where the chip's label is not — keeps taps, typing and
 * scrolling on what is left. v86 bounds that from the other side: on the two
 * 720x1280 keyboard-up windows the ratio left the editor 39 / 25 dp of text, so
 * the weight there is the one that lands it on
 * [SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_FLOOR] and the chip keeps its own
 * floor — see `ComposerOneRowEditorFloorV86Test`. Measured at 480 dpi
 * (`research/v79_verify/5554/13_480_kb.png`): the editor takes its whole share
 * around the draft line — the ⊞ starts exactly at the share's edge and Send ends
 * flush with the card's right edge.
 *
 * The chain's *structure* is the same in both forms on purpose: `NodeChain`
 * updates a modifier node when the new element is of the same class and replaces
 * it otherwise (`actionForModifiers`), and a replaced `pointerInput` node cancels
 * the gesture that is mid-flight — a second way for a first tap to be swallowed.
 */
class ComposerEditorFormV80Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val composer =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    /** The card's arrangement, for the structural pins. */
    private val arrangement = composer
        .substringAfter("private fun ComposerArrangement(")
        .substringBefore("private fun Modifier.editorBandTap(")

    /** The composer with runs of whitespace collapsed, for chain assertions. */
    private val flat = composer.replace(Regex("\\s+"), " ")

    /** The composer with every comment (line and block) stripped, for code pins. */
    private val code = composer
        .replace(Regex("(?s)/\\*.*?\\*/"), " ")
        .replace(Regex("(?m)^\\s*//.*$"), " ")
        .replace(Regex("\\s+"), " ")

    // ------------------------------------------- the flip is a value change

    @Test
    fun `the editor has one call site in both forms`() {
        // One definition, one mount: the editor is the arrangement's first child,
        // and the form is not allowed to be part of that call site.
        assertEquals(1, Regex("""val editorField:""").findAll(composer).count())
        assertEquals(1, Regex("""editorField\(""").findAll(composer).count())
        // …and the field itself exists once: a second `BasicTextField` anywhere in
        // this file would be a second call site by definition.
        assertEquals(1, Regex("""BasicTextField\(""").findAll(composer).count())
        // The one mount is the arrangement's `editor = { … }` argument.
        assertEquals(1, Regex("""editor = \{""").findAll(composer).count())
    }

    @Test
    fun `the card picks its form by the arrangement's values`() {
        // The form lives in the tool row's own modifier — the one-row band, or the
        // stacked row with its 8 dp of padding — which is one call site with the
        // two `Modifier` values as the arms of an `if` on the same `Row(`.
        // v106（方案 A）: the one-row arm is now `.height(BAND_HEIGHT)` = 48 dp — the
        // band the composer's own controls must fit inside. Strictness is unchanged
        // (still an exact, whole-line match, still one `if (minimal)` call site), and
        // the *stacked* arm is byte-identical: the 44 dp `TOOL_ROW_HEIGHT` row it
        // pins is still what the stacked form draws.
        assertTrue(
            code.contains(
                "modifier = if (minimal) { Modifier.fillMaxWidth().height(BAND_HEIGHT) " +
                    "} else { Modifier.fillMaxWidth().height(TOOL_ROW_HEIGHT).padding(horizontal = 8.dp) }",
            ),
        )
        // …and the stacked arm's 44 dp row must survive verbatim (a second pin, so
        // raising the one-row band cannot quietly take the stacked row with it).
        assertTrue(
            code.contains("Modifier.fillMaxWidth().height(TOOL_ROW_HEIGHT).padding(horizontal = 8.dp)"),
        )
        assertEquals(1, Regex("""modifier = if \(minimal\) \{""").findAll(composer).count())
        assertEquals(1, Regex("""Row\(\s*modifier = if \(minimal\)""").findAll(composer).count())
    }

    @Test
    fun `the card's two children are mounted once and never under a form branch`() {
        // One arrangement, exactly two children, each mounted exactly once — and
        // the arrangement's own `if (minimal)`s are *values*: the tool row's
        // modifier, the share ghost's weight, and the measure block's branch. None
        // of them is a call site, which is what makes a flip a value change rather
        // than a re-create or a move.
        // One declaration and one call site: the card mounts the arrangement once.
        assertEquals(2, Regex("""ComposerArrangement\(""").findAll(composer).count())
        // Two children, each measured exactly once per form branch.
        assertEquals(2, Regex("""measurables\[0\]""").findAll(composer).count())
        assertEquals(1, Regex("""measurables\[1\]""").findAll(composer).count())
        assertEquals(1, Regex("""editor\(\)""").findAll(arrangement).count())
        assertEquals(1, Regex("""controls\(\)""").findAll(arrangement).count())
        assertEquals(3, Regex("""if \(minimal\) \{""").findAll(arrangement).count())
        // The two children, in order, from the single content lambda.
        assertTrue(
            flat.contains(
                "ComposerArrangement( minimal = minimal, editor = { editorField(",
            ),
        )
        assertTrue(flat.contains("controls = { toolControls() },"))
        // The stacked form's editor bound is the card minus the tool row, exactly
        // what the column child it used to be was measured against.
        assertTrue(
            arrangement.contains(
                "maxHeight = (constraints.maxHeight - toolRow.height).coerceAtLeast(0),",
            ),
        )
        // …and the one-row form measures the editor with the share the tool row's
        // own weight arithmetic reported, never with the whole band.
        assertTrue(arrangement.contains("maxWidth = editorShare.coerceIn(0, constraints.maxWidth),"))
        // The report is read *after* the tool row has been measured, inside the same
        // measure pass — the share is this frame's band, not a frame-old one. That
        // ordering is the whole reason the arrangement may read a layout fact here.
        val toolRowMeasure = arrangement.indexOf("measurables[1].measure(")
        val editorMeasure = arrangement.indexOf("measurables[0].measure(")
        assertTrue(
            "the tool row must be measured first (was $toolRowMeasure vs $editorMeasure)",
            toolRowMeasure >= 0 && toolRowMeasure < editorMeasure,
        )
    }

    @Test
    fun `the editor keeps one chain and the form is in its values`() {
        // The chain is the same *structure* in both forms — `fillMaxWidth` for the
        // width (the arrangement hands the field its bound), `heightIn` for the
        // stacked floor, the band tap, the padding — and every form-dependent part
        // is a value inside it. One chain, one `fillMaxWidth`, one band tap, one
        // padding: a form that changed the chain's shape would have `NodeChain`
        // replace the modifier nodes (see the class note), starting with the band
        // tap's `pointerInput`.
        assertTrue(flat.contains(".fillMaxWidth() .heightIn( min = if (minimal) {"))
        assertEquals(1, Regex("""\.editorBandTap\(focusRequester\)""").findAll(composer).count())
        assertTrue(flat.contains(".editorBandTap(focusRequester) .padding("))
        assertTrue(flat.contains("start = if (minimal) 12.dp else 16.dp"))
        assertTrue(flat.contains("end = if (minimal) 4.dp else 16.dp"))
        assertTrue(flat.contains("top = if (minimal) 0.dp else 16.dp"))
        assertTrue(flat.contains("bottom = if (minimal) 0.dp else 8.dp"))
        // The one-row floor is 0, not the stacked editor's floor: the band's own
        // 44 dp bounds the field there, the field is one line, and the arrangement
        // centres it (a 60 dp floor inside a 44 dp band would sit the text high).
        assertEquals(1, Regex("""min = if \(minimal\) \{\s*0\.dp""").findAll(composer).count())
        // The field carries no `weight` of its own any more: the one-row share is
        // the ghost spacer's, and the field takes the bound the arrangement hands
        // it (`fillMaxWidth`). One weighted ghost in the whole file — its weight
        // the band's own function since v86
        // ([SessionPaneLayout.composerOneRowEditorWeight]; on a band where the floor
        // does not bind it returns the v81 constant *itself*, pinned below) — and no
        // `weight(1f, fill = false)` pair left to move the editor between forms.
        assertEquals(
            1,
            Regex("""\.weight\(SessionPaneLayout\.composerOneRowEditorWeight\(bandWidth\)\)""")
                .findAll(composer).count(),
        )
        assertEquals(0.45f, SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_WEIGHT, 0.0f)
        assertEquals(
            "a band the floor cannot bind on is v81's constant, bit for bit",
            SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_WEIGHT,
            SessionPaneLayout.composerOneRowEditorWeight(600.dp),
            0.0f,
        )
        assertFalse(composer.contains(".weight(1f, fill = false)"))
    }

    @Test
    fun `the controls are mounted once and never move`() {
        // v75 gave the controls a `movableContentOf` slot because the two forms
        // were two parents and the flip was a **move** of that subtree. v81
        // removes the second parent instead, so there is nothing left to move: the
        // tool row is the arrangement's second child in both forms, and
        // `toolControls` is mounted exactly once — which is also why the chip's
        // `expanded` and the popup it owns cannot be lost to a flip.
        assertFalse("no movable machinery left in the composer", code.contains("movableContentOf"))
        assertFalse("the v75 slot is retired", code.contains("toolControlsSlot"))
        assertEquals(1, Regex("""val toolControls:""").findAll(composer).count())
        assertEquals(1, Regex("""controls = \{ toolControls\(\) \}""").findAll(composer).count())
        // The tool row keeps its full 44 dp in both forms — never squeezed — and
        // the stacked form keeps the 8 dp its controls have always had.
        assertTrue(
            flat.contains(
                "Modifier.fillMaxWidth().height(TOOL_ROW_HEIGHT).padding(horizontal = 8.dp)",
            ),
        )
        assertTrue(composer.contains("private val TOOL_ROW_HEIGHT = 44.dp"))
    }

    // --------------------------------------- the flip still happens (v74's form)

    @Test
    fun `the 420 window still flips and the 480 window still does not`() {
        // The fix is *not* a frozen form: v74's one-row decision is untouched, and
        // the window that flips is still the window that flips. (The caret
        // survives it now; the geometry is the same.)
        assertTrue(!ShellChromeBudget.isShortWindow(487))
        assertTrue(!SessionPaneLayout.composerOneRow(false, 411.43.dp))
        assertTrue(SessionPaneLayout.composerOneRow(false, 128.38.dp))
        assertTrue(ShellChromeBudget.isShortWindow(426))
        assertTrue(SessionPaneLayout.composerOneRow(true, 374.67.dp))
        // 480 dpi is a short window: already one row at rest, so its first tap
        // never had a flip to survive.
        assertTrue(SessionPaneLayout.composerOneRow(true, 91.67.dp))
    }
}
