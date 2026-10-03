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
 * v79 P0 「720x1280 @420dpi composer 完全假死」: the composer's form flip must not
 * cost the caret.
 *
 * `SessionPaneLayout.composerOneRow` keys the one-row form on the post-inset
 * body (v74), and the body shrinks by the keyboard's inset the moment the editor
 * takes focus. So on a window whose form is not already minimal at rest, the tap
 * that focuses the editor is what flips the composer's `if (minimal)` branch —
 * and the two branches are different call sites, so the flip disposes the
 * focused field and composes a fresh one. The caret, the focus and the IME
 * session go with the old nodes; the platform tears the input connection down
 * (`onStartInput` with `inputType=0` right after the keyboard had started) and
 * the pane settles back into the form it came from.
 *
 * Measured on emulator-5554, `research/v79_repro/rec.mp4` (30 fps, `dumpsys
 * window displays` for the insets):
 *
 * ```
 * 720x1280 @420dpi   2.625 px/dp   274.29 x 487.62 dp window
 *   statusBars top     137 px =  52.19 dp
 *   ime bottom         806 px = 307.05 dp
 *   body at rest      (1280 − 137) / 2.625 − 24        = 411.43 dp → stacked
 *   body + keyboard   (1280 − 137 − 806) / 2.625       = 128.38 dp → one row
 * ```
 *
 * The tap focuses the field for one frame (the caret is visible), the keyboard
 * starts to rise, the form flips and the keyboard falls straight back out:
 * `mInputShown=false` for every tap, no caret, injected text landing nowhere,
 * 3/3. The same window at 480 dpi is a *short window*, so its form is minimal
 * before the keyboard exists and never flips — and at 380 dpi the body keeps
 * 200.6 dp with the keyboard up, so the form stays stacked and the field focuses
 * normally. Both controls say the same thing: the flip is what is fatal, not
 * either form.
 *
 * v79 answered that with a gated focus hand-back after the flip
 * (`LaunchedEffect(minimal)`), and on device it lost the race — see
 * `ComposerEditorFormV80Test` for the measurement and for what replaced it. The
 * one thing this file still pins is the *reason* the field takes no
 * `movableContentOf` slot of its own: a movable move is a remove plus an insert
 * of the same node (`Applier.move` cannot cross parents, so
 * `CopyNodesToNewAnchorLocation` inserts at the new anchor after the old one is
 * removed), and the removal detaches the node, whose `FocusTargetNode.onDetach`
 * clears focus (`clearFocus(force = true)`). A moved text field is an unfocused
 * one. (The v80 fix is not a move either: the field is *not moved* at all — one
 * call site, one parent, both forms. v81 extends that to the card's other half:
 * the controls' slot is retired too, because the tool row is now the arrangement's
 * second child in both forms — see `ComposerEditorFormV80Test`.)
 */
class ComposerEditorSlotV79Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val composer =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    /** The composer with whole-line comments stripped: pins read code, not prose. */
    private val composerCode = composer.replace(Regex("(?m)^\\s*//.*$"), "")

    private fun oneRow(windowHeightDp: Int, body: androidx.compose.ui.unit.Dp): Boolean =
        SessionPaneLayout.composerOneRow(ShellChromeBudget.isShortWindow(windowHeightDp), body)

    @Test
    fun `the 420 window flips its composer form when the keyboard appears`() {
        // 487 dp is above the short-window gate, so the form is the body's alone.
        assertTrue(!ShellChromeBudget.isShortWindow(487))
        assertTrue(!oneRow(487, 411.43.dp))
        assertTrue(oneRow(487, 128.38.dp))
        // 164 dp is the line it crosses.
        assertTrue(!oneRow(487, 164.dp))
        assertTrue(oneRow(487, 163.99.dp))
    }

    @Test
    fun `the 480 window never flips and the 380 window never crosses the line`() {
        // 480 dpi: a short window, so the one-row form holds on both bodies.
        assertTrue(ShellChromeBudget.isShortWindow(426))
        assertTrue(oneRow(426, 91.67.dp))
        assertTrue(oneRow(426, 374.67.dp))
        // 380 dpi: the body keeps 200.6 dp with the keyboard up, so the form is
        // stacked on both and the flip never happens.
        assertTrue(!ShellChromeBudget.isShortWindow(538))
        assertTrue(!oneRow(538, 486.76.dp))
        assertTrue(!oneRow(538, 200.6.dp))
    }

    @Test
    fun `the caret is no longer handed back because it never leaves`() {
        // The v79 effect is gone (v80): a flip that cannot dispose the field has
        // nothing to hand back, and the effect could only race the disposal it was
        // written for — or pull the caret out of a surface the user had turned to
        // (any IME that shortened the body flipped the form, and its gate only
        // knew about the terminal).
        assertTrue(!composerCode.contains("LaunchedEffect(minimal)"))
        assertTrue(!composerCode.contains("LaunchedEffect(minimal, imeVisible)"))
        // The keyboard is still read for the popover's ESC (B-12), so the removal
        // is the effect's, not the state's.
        assertTrue(composer.contains("val imeVisible = WindowInsets.isImeVisible"))
    }

    @Test
    fun `the field keeps no movable slot of its own`() {
        // A move cannot preserve focus (the node is detached and
        // `FocusTargetNode.onDetach` clears it), so a slot here would buy the
        // field's internal state at the price of moving a subtree a keystroke
        // invalidates — the pattern this app has already seen crash on device
        // (the controls' `toolControlsSlot`, retired in v81 for the same reason).
        // v80 instead gives the field one call site and one parent, which needs no
        // slot at all.
        assertTrue(!composer.contains("editorFieldSlot"))
        assertEquals(1, Regex("""editorField\(""").findAll(composer).count())
    }

    @Test
    fun `the tool row keeps its nodes without a slot next to the editor`() {
        // The controls were given a slot in v76 — they are the other half of the
        // flip, and their state is what a re-create would lose — but v81 retires
        // it: the tool row is the arrangement's second child in *both* forms, so a
        // flip has nothing to move and the slot has nothing to carry.
        assertFalse(composer.contains("val toolControlsSlot"))
        assertFalse(
            "no movable machinery left in the composer",
            composer
                .replace(Regex("(?s)/\\*.*?\\*/"), " ")
                .replace(Regex("(?m)^\\s*//.*$"), " ")
                .contains("movableContentOf"),
        )
        assertEquals(1, Regex("""controls = \{ toolControls\(\) \}""").findAll(composer).count())
    }

    @Test
    fun `the draft and the caret position are hoisted above the flip`() {
        // What a re-create would otherwise cost is held at the composer's own
        // level, so the flip cannot lose it: the value (with its selection) and
        // the two mode flags.
        assertTrue(composer.contains("var value by rememberSaveable("))
        assertTrue(composer.contains("var shellMode by rememberSaveable("))
        assertTrue(composer.contains("var selected by remember { mutableStateOf(0) }"))
    }
}
