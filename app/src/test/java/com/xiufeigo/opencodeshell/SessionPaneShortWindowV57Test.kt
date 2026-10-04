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
 * v57 P0 「短窗消息区气泡不渲染 + composer 塌成麦克风条」, fifth pass at the
 * 1440x810 @420dpi window.
 *
 * The v51–v55 repairs all moved numbers inside the *pane*. An on-device
 * `onSizeChanged` probe (`research/v57_verify/tE`, temporary tags `V57PROBE` /
 * `V57BUDGET`, since removed) read the budget that window actually runs, and
 * the two numbers that decide both symptoms are:
 *
 * ```
 *                            before (v56)          after (v57)
 * window (screenHeightDp)         —                    308 dp
 * session body (maxHeight)    146.67 dp             146.67 dp
 * chrome ceiling               30.67 dp              0.00 dp
 * timeline pane               146.67 dp            146.67 dp
 * composer                     96.00 dp             56.38 dp   ← one row
 * item band (viewport − pad)   42.67 dp             74.29 dp
 * ```
 *
 * So the band was never zero — it was one and a half message lines, and the
 * "collapsed mic bar" was the composer's **editor** (60 dp `heightIn(min)`)
 * overflowing a pane the keyboard had already cut to 34 dp, so the column
 * clipped the editor and left the trailing sliver of the tool row. Two fixes,
 * both pinned below: the composer has a one-row form whose floor (44 dp) is
 * below every pane the shell hands it, and the form is chosen from the
 * **window**, never from a measured pane.
 */
class SessionPaneShortWindowV57Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val sessionScreen =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private val composer =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    private val app = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")

    /** The session body the probe measured: 146.67 dp. */
    private val measuredBody = 146.67.dp

    /** The composer it measured after the fix. */
    private val measuredComposer = 56.38.dp

    // ---------------------------------------------- the one-row composer form

    @Test
    fun `the minimal composer is one row and keeps the whole tool row`() {
        assertEquals(44.dp, SessionPaneLayout.COMPOSER_ROW_HEIGHT)
        assertEquals(
            SessionPaneLayout.COMPOSER_ROW_HEIGHT + 12.dp,
            SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT,
        )
        // It is *below* every pane the shell hands the composer without the IME
        // — that is the whole point: a floor the pane cannot honour is exactly
        // what clipped the tool row into a sliver in v51.
        for (pane in listOf(68.dp, 116.dp, measuredBody, 196.dp, 342.dp)) {
            assertTrue("pane $pane", SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT <= pane)
        }
        // Below it the composer is genuinely clipped: with the soft keyboard up
        // on the measured window the session body is 68 dp. The shell gives the
        // top strip up for the IME there (`ShellChromeBudget.yieldsToIme`) so
        // the composer gets whole, and the messages resume when the keyboard
        // closes — `research/v57_verify/tE/v57_50_reopen.png`.
        assertTrue(68.dp >= SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT)
    }

    @Test
    fun `only a short pane takes the one-row form`() {
        // Below chrome + one-row composer + a message band (164 dp).
        assertTrue(SessionPaneLayout.composerMinimal(120.dp))
        assertTrue(SessionPaneLayout.composerMinimal(measuredBody))
        assertTrue(SessionPaneLayout.composerMinimal(163.dp))
        assertTrue(!SessionPaneLayout.composerMinimal(164.dp))
        assertTrue(!SessionPaneLayout.composerMinimal(700.dp))
        // v154: the lower guard used to be `available > 0.dp`, which made the
        // predicate **non-monotone**: a room of 0 dp — the room that can least
        // afford the stacked composer — was told it could have one, and the
        // 600 tier's reverse jump (「键盘变高时形态反向跳变」) fell out of it
        // (`research/v97_fix/REVERSE_JUMP_FIX.md`). The staircase itself is
        // untouched: 163 is one row, 164 is the full form.
        assertTrue(
            "a room of 0 dp can least of all carry the stacked form, so it takes the one row; " +
                "the truth set of composerMinimal must be the initial segment {0 .. 163}",
            SessionPaneLayout.composerMinimal(0.dp),
        )
    }

    @Test
    fun `the ceiling the shared composer takes protects the band`() {
        for (pane in listOf(120.dp, measuredBody, 196.2.dp, 250.dp, 342.dp)) {
            val ceiling = SessionPaneLayout.composerCeiling(pane)
            assertTrue("pane $pane: ceiling $ceiling", ceiling in 1.dp..pane)
            assertTrue(
                "pane $pane: the composer took $ceiling of it",
                pane - ceiling > 0.dp,
            )
        }
        // The measured window: the ceiling is a bound the composer's own
        // natural height (56.38 dp, one row) sits under, so what the pane
        // really keeps is 90.29 dp — the 74.29 dp item band the probe read,
        // three message lines instead of one and a half.
        assertEquals(82.67.dp, SessionPaneLayout.composerCeiling(measuredBody))
        assertTrue(measuredComposer < SessionPaneLayout.composerCeiling(measuredBody))
        assertTrue(measuredBody - measuredComposer > 64.dp)
    }

    // --------------------------------------------------- the chrome's new band

    @Test
    fun `a body that cannot pay for chrome yields it before anything is measured`() {
        // The v57 branch: derived from the body alone, so it cannot feed back
        // into the composer that would then change it.
        for (body in listOf(120.dp, 146.67.dp, 163.dp)) {
            assertEquals(
                "body $body",
                0.dp,
                SessionPaneLayout.chromeMaxHeightForComposer(
                    body,
                    SessionPaneLayout.COMPOSER_MIN_HEIGHT,
                ),
            )
        }
        assertEquals(
            SessionPaneLayout.CHROME_COMPACT_HEIGHT,
            SessionPaneLayout.chromeMaxHeightForComposer(
                180.dp,
                SessionPaneLayout.COMPOSER_MIN_HEIGHT,
            ),
        )
    }

    @Test
    fun `the v51 v52 v54 v55 chrome tables are untouched`() {
        assertEquals(154.dp, SessionPaneLayout.chromeMaxHeight(342.dp))
        assertEquals(112.dp, SessionPaneLayout.chromeMaxHeight(300.dp))
        assertEquals(62.dp, SessionPaneLayout.chromeMaxHeight(250.dp))
        assertEquals(32.dp, SessionPaneLayout.chromeMaxHeight(196.2.dp))
        assertEquals(32.dp, SessionPaneLayout.chromeMaxHeight(180.dp))
        assertEquals(0.dp, SessionPaneLayout.chromeMaxHeight(100.dp))
        assertEquals(0.dp, SessionPaneLayout.chromeMaxHeight(0.dp))
    }

    // ------------------------------------------- the form may not be measured

    @Test
    fun `the composer's form is decided by the window, never by the pane`() {
        // Reading it off the measured body made the layout oscillate: the v57
        // build alternated the composer between 56 and 116 dp several times a
        // second (body 146.7 → composer 100 → chrome 40 → body 218.7 → …).
        assertTrue(sessionScreen.contains("ShellChromeBudget.isShortWindow("))
        assertTrue(
            "the form must not read the measured pane",
            !sessionScreen.contains(
                "minimal = SessionPaneLayout.composerMinimal(paneHeight)",
            ),
        )
    }

    @Test
    fun `a short window is a property of the window`() {
        // 1440x810 @420dpi is 308 dp and stays 308 whatever the IME does.
        assertTrue(ShellChromeBudget.isShortWindow(308))
        assertTrue(ShellChromeBudget.isShortWindow(479))
        assertTrue(!ShellChromeBudget.isShortWindow(480))
        assertTrue(!ShellChromeBudget.isShortWindow(914))
        assertTrue(!ShellChromeBudget.isShortWindow(0))
        // …and only such a window gives its top strip up for the IME.
        assertTrue(ShellChromeBudget.yieldsToIme(imeVisible = true, windowHeightDp = 308))
        assertTrue(!ShellChromeBudget.yieldsToIme(imeVisible = false, windowHeightDp = 308))
        assertTrue(!ShellChromeBudget.yieldsToIme(imeVisible = true, windowHeightDp = 914))
    }

    @Test
    fun theStatusBarInsetIsConsumedByTheStripOnlyWhileTheStripIsThere() {
        // v63 P0 「横屏+键盘：顶栏图标压状态栏」(`research/v63_verify/5560`):
        // the consumption used to follow the *setting* while the strip's render
        // follows the setting AND `yieldsToIme`. On a short window — every
        // landscape phone, and the measured 308 dp one — the keyboard takes the
        // strip away, and the destination still inherited a consumed status-bar
        // inset of 0 and drew its header row at y=0: the back chevron under the
        // clock, the trailing icons under LTE/signal/battery
        // (`72_land_ime.png`; `70_land_nokb.png`, no keyboard, is correct).
        for (short in listOf(308, 411, 479)) {
            for (healthy in listOf(480, 914)) {
                assertTrue(
                    "short=$short topTitlebar: the strip stands and owns the inset",
                    ShellChromeBudget.topStripOwnsStatusBar(topTitlebar = true, yieldsToIme = false) ||
                        !ShellChromeBudget.isShortWindow(short),
                )
                assertTrue(
                    "top strip on, no IME",
                    ShellChromeBudget.topStripOwnsStatusBar(topTitlebar = true, yieldsToIme = false),
                )
                assertTrue(
                    "top strip off: nothing consumes the inset, whatever the window",
                    !ShellChromeBudget.topStripOwnsStatusBar(topTitlebar = false, yieldsToIme = short < 480),
                )
            }
        }
        // The bug's own shape: short window + IME → the strip yielded, so it
        // consumes nothing and the destination keeps its own status-bar padding.
        assertTrue(ShellChromeBudget.yieldsToIme(imeVisible = true, windowHeightDp = 308))
        assertFalse(ShellChromeBudget.topStripOwnsStatusBar(topTitlebar = true, yieldsToIme = true))
        assertTrue(ShellChromeBudget.topStripOwnsStatusBar(topTitlebar = true, yieldsToIme = false))
    }

    // ------------------------------------------------------------------ wiring

    @Test
    fun `the composer lays its editor and tool controls out as one row`() {
        assertTrue(composer.contains("minimal: Boolean = false"))
        assertTrue(composer.contains("val editorField: @Composable (Modifier) -> Unit"))
        assertTrue(composer.contains("val toolControls:"))
        assertTrue(composer.contains("if (minimal) {"))
        // One definition each, mounted **once** in both forms: v80 gave the editor
        // that (its call site and its parent are the same in the one-row and the
        // stacked form, so the flip is a value change and can never dispose the
        // focused field — see `ComposerEditorSlotV79Test`), and v81 P0 completed it
        // for the controls: the card is one `Layout` whose two children are the
        // editor and the tool row in both forms, so the flip cannot move them
        // either (the v75 slot is retired — see `ComposerEditorFormV80Test`).
        assertEquals(1, Regex("""val editorField:""").findAll(composer).count())
        assertEquals(1, Regex("""val toolControls:""").findAll(composer).count())
        assertEquals(1, Regex("""editorField\(""").findAll(composer).count())
        assertEquals(1, Regex("""controls = \{ toolControls\(\) \}""").findAll(composer).count())
        assertFalse(composer.contains("toolControlsSlot"))
        assertFalse(composer.contains("val latestToolControls by rememberUpdatedState(toolControls)"))
        // The tool row keeps its own height in both forms — never squeezed.
        assertTrue(composer.contains(".height(TOOL_ROW_HEIGHT)"))
    }

    @Test
    fun `the screen passes the form through and caps the composer at its ceiling`() {
        assertTrue(sessionScreen.contains("minimal = minimalComposer"))
        assertTrue(
            sessionScreen.contains(
                "val composerCeiling = (paneHeight - SessionPaneLayout.TIMELINE_MIN_CONTENT)",
            ),
        )
        assertTrue(sessionScreen.contains("composerCeiling = composerCeiling"))
        assertTrue(
            sessionScreen.contains("SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT"),
        )
    }

    @Test
    fun `the shell gives its top strip up for the IME on a short window only`() {
        assertTrue(app.contains("ShellChromeBudget.yieldsToIme("))
        assertTrue(app.contains("if (topTitlebar && !yieldsToIme) {"))
    }

    @Test
    fun `the status bar consumption follows the strip's render, not the setting`() {
        // Same two conditions as the render, read once, so the two can never
        // drift apart again.
        assertTrue(
            app.contains(
                "ShellChromeBudget.topStripOwnsStatusBar(topTitlebar, yieldsToIme)",
            ),
        )
        assertTrue(
            "the bare `topTitlebar` consumption is the v63 defect",
            !app.contains("if (topTitlebar) Modifier.consumeWindowInsets(WindowInsets.statusBars)"),
        )
    }

    @Test
    fun `no probe is left behind`() {
        for (text in listOf(sessionScreen, composer, app)) {
            assertTrue("V57PROBE/V57PANE/V57BUDGET left in the source", !text.contains("V57PROBE"))
            assertTrue(!text.contains("V57PANE"))
            assertTrue(!text.contains("V57BUDGET"))
        }
    }
}
