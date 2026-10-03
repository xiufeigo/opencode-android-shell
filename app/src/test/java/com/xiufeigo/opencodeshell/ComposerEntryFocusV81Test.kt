package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerEntryFocus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v81 P1 「重开会话后编辑器失焦需二次点击」: the composer's session-entry focus
 * handoff is a **window**, not one ask.
 *
 * The device read (`research/v80_verify/5554/`): an existing session is reopened,
 * the editor shows a caret, and the first injected batch commits one character
 * and loses the rest — the caret is gone by the next screenshot and a *second*
 * tap is what makes typing work (`26_480_typed.png` → `27_480_recheck.png` →
 * `28_480_refocus.png`). At 420 dpi the same window loses everything but the
 * first character (`research/v79_verify/5554/35_taps.png` → `36_10chars2.png`).
 * A new session never shows it: its entry has no restore rebuild behind it.
 *
 * The two halves of the wiring pinned here are the ones the repo measured on the
 * only other editor a mount focuses (the Home rename field):
 *
 * - `requestFocus()` alone leaves a field with **no live `InputConnection`** (v43)
 *   — so the ask raises the keyboard with it, once, on the first attempt;
 * - one ask lands on a tree that is still settling, and "every later open worked"
 *   (v45) — so the window is retried, bounded, one ask per pass.
 *
 * What the retries must not do is ask again once the caret has landed: a repeat
 * `keyboard.show()` is a fresh `restartInput()` and an IME re-initialised
 * mid-word drops the character it holds (v66). `editorFocused` — the field's own
 * `onFocusChanged` — therefore ends the window, and only the first attempt raises
 * the keyboard.
 */
class ComposerEntryFocusV81Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val composer =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    /** The B7 entry effect, from its own launch to the chord that follows it. */
    private val entryEffect = composer
        .substringAfter("LaunchedEffect(draftKey) {")
        .substringBefore("LaunchedEffect(externalFocus)")

    // ------------------------------------------------------------ the budget

    @Test
    fun `the window is bounded and only the first ask raises the keyboard`() {
        // One attempt would be the effect this replaces (a single settled-frame
        // ask): the window has to outlive the rebuild it is waiting for.
        assertTrue(ComposerEntryFocus.MAX_ATTEMPTS >= 2)
        assertTrue(ComposerEntryFocus.raisesKeyboard(0))
        assertFalse(ComposerEntryFocus.raisesKeyboard(1))
        assertFalse(ComposerEntryFocus.raisesKeyboard(ComposerEntryFocus.MAX_ATTEMPTS - 1))
        // …and it ends: every attempt at (and past) the budget stops the loop.
        assertTrue(ComposerEntryFocus.retries(1))
        assertTrue(ComposerEntryFocus.retries(ComposerEntryFocus.MAX_ATTEMPTS - 1))
        for (attempt in ComposerEntryFocus.MAX_ATTEMPTS..ComposerEntryFocus.MAX_ATTEMPTS + 8) {
            assertFalse(ComposerEntryFocus.retries(attempt))
        }
        // A settle step, not a timeout: the retry must fit inside the entry's own
        // frame budget (the rename field's late retries are 120 ms apart).
        assertTrue(ComposerEntryFocus.RETRY_DELAY_MS in 40L..500L)
        assertTrue(
            "the window must be shorter than a user's 'second tap'",
            ComposerEntryFocus.MAX_ATTEMPTS * ComposerEntryFocus.RETRY_DELAY_MS <= 1_000L,
        )
    }

    // ------------------------------------------------------------ the wiring

    @Test
    fun `the effect asks on a settled frame and retries a bounded number of times`() {
        assertTrue(entryEffect.contains("withFrameNanos { }"))
        assertTrue(entryEffect.contains("delay(ComposerEntryFocus.RETRY_DELAY_MS)"))
        assertTrue(
            "the loop must stop at the budget",
            entryEffect.contains("if (!ComposerEntryFocus.retries(attempt)) return@LaunchedEffect"),
        )
        assertTrue(entryEffect.contains("ComposerEntryFocus.raisesKeyboard(attempt)"))
        // One ask per pass: the loop is the only thing that requests, and it
        // requests once.
        assertEquals(1, Regex("""focusRequester\.requestFocus\(\)""").findAll(entryEffect).count())
    }

    @Test
    fun `the window ends when the caret lands and yields to the terminal on every ask`() {
        assertTrue(composer.contains("var editorFocused by remember { mutableStateOf(false) }"))
        assertTrue(composer.contains(".onFocusChanged { editorFocused = it.isFocused }"))
        val frame = entryEffect.indexOf("withFrameNanos { }")
        val landed = entryEffect.indexOf("if (editorFocused) return@LaunchedEffect")
        val terminal = entryEffect.indexOf("if (terminalImeFocus?.holdsKeyboard == true) return@LaunchedEffect")
        val request = entryEffect.indexOf("focusRequester.requestFocus()")
        assertTrue("the ask comes after a settled frame", frame in 1 until landed)
        assertTrue("a field that already holds the caret ends the window", landed < terminal)
        // v74: every attempt is a mount-time ask, so every attempt yields.
        assertTrue("the terminal yield stays on the ask path", terminal < request)
    }

    @Test
    fun `the user gesture paths stay unconditional and keep the connection they find`() {
        val chord = composer
            .substringAfter("LaunchedEffect(externalFocus) {")
            .substringBefore("LaunchedEffect(externalModelOpen)")
        assertTrue(chord.contains("focusRequester.requestFocus()"))
        assertFalse("an explicit request must never yield", chord.contains("holdsKeyboard"))
        assertFalse("the chord is not part of the entry window", chord.contains("ComposerEntryFocus"))
        assertFalse(
            "only the entry handoff raises the keyboard (a repeat show is a restartInput)",
            chord.contains("keyboardController?.show()"),
        )
        assertEquals(1, Regex("""keyboardController\?\.show\(\)""").findAll(composer).count())
    }
}
