package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.TerminalImeFocus
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v74 P0 — the input-connection domain (`research/v74_verify/`).
 *
 * Two reports, one domain: the composer and the docked terminal are two IME
 * targets in one window, and the shell let them take the connection from each
 * other.
 *
 * - **the terminal received nothing.** Opening the docked pane left the keyboard
 *   on the composer: `research/v74_verify/5556/08_term_type_notap.png` and
 *   `13_term_tap2.png` show the pane up with its prompt and block cursor while
 *   the IME's Enter key still wears the composer's Send action. The v73 ask was
 *   made (`TerminalFocusRoutingV73Test`), but it was a single fire-and-forget
 *   request from the `AndroidView` factory, and nothing said whether it landed.
 * - **the composer lost characters.** `research/v74_verify/5554/11_typed.png`:
 *   `hello short window test` came back as `loot`, with 85× Gboard's
 *   `Ignore [FetchSuggestions] diff due to stale request … lastInputStateId=2043`
 *   in logcat — a connection that never advances, because the app keeps handing
 *   the IME a *new* editor instead of committing state to the one it already
 *   owns. The editor was rebuilt whenever the pane's arrangement flipped, and on
 *   a short window the composer's own content (a wrapped line, the `/`/`@` card,
 *   the model picker) is enough to flip it.
 *
 * The wiring pinned here is the two halves of the fix: the composer keeps its
 * identity across the flip (`movableContentOf`) and yields the keyboard it never
 * claimed (a mount is not a user gesture); the terminal's claim is re-asserted
 * until the view really owns the focus, and reported so the yield has something
 * to read.
 */
class InputConnectionV74Test {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        // CRLF sources (`SessionScreen.kt`) would break `\n` pins otherwise.
        return file.readText().replace("\r\n", "\n")
    }

    private fun sessionScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private fun composer() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    private fun panel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt")

    // ------------------------------------------------------- the owner signal

    @Test
    fun theOwnerIsSilentUntilATerminalViewTakesTheFocus() {
        val focus = TerminalImeFocus()
        // A pane nobody typed in holds nothing: the composer's own entry focus is
        // the normal case.
        assertFalse(focus.holdsKeyboard)
        focus.report(terminalFocused = true)
        assertTrue(focus.holdsKeyboard)
        // The view losing the focus (the user tapped the composer, a dialog opened)
        // hands the keyboard back.
        focus.report(terminalFocused = false)
        assertFalse(focus.holdsKeyboard)
    }

    @Test
    fun theScreenOwnsTheChannelAndProvidesItAboveBothSurfaces() {
        val screen = sessionScreen()
        assertTrue(screen.contains("val terminalImeFocus = remember { TerminalImeFocus() }"))
        assertTrue(screen.contains("CompositionLocalProvider(LocalTerminalImeFocus provides terminalImeFocus) {"))
        // The provider wraps the outermost node of the screen, so it is above the
        // composer (inside `timelinePane`) *and* the docked terminal (`TerminalDock`)
        // whatever arrangement the pane picks.
        val provider = screen.indexOf("CompositionLocalProvider(LocalTerminalImeFocus provides terminalImeFocus) {")
        assertTrue(provider in 1 until screen.indexOf("val sshFallbackHost = sshHost"))
        assertTrue(screen.contains("val LocalTerminalImeFocus = staticCompositionLocalOf<TerminalImeFocus?> { null }"))
    }

    @Test
    fun theTerminalReportsEveryFocusChangeAndEveryDetach() {
        val body = panel()
        assertTrue(body.contains("val imeFocus = LocalTerminalImeFocus.current"))
        assertTrue(body.contains("imeFocus = imeFocus,"))
        // Ground truth: the IME follows the window's view focus.
        assertTrue(body.contains("view.setOnFocusChangeListener { _, hasFocus -> imeFocus?.report(hasFocus) }"))
        assertTrue(body.contains("imeFocus?.report(false)"))
    }

    // ---------------------------------------------------- the composer's yield

    @Test
    fun theMountFocusYieldsToATerminalThatHoldsTheKeyboard() {
        val body = composer()
        assertTrue(body.contains("val terminalImeFocus = LocalTerminalImeFocus.current"))
        // The B7 effect: one frame for the field to exist, then the yield, then the
        // request — a mount is not the user turning to the editor.
        val effect = body.substringAfter("LaunchedEffect(draftKey) {")
            .substringBefore("LaunchedEffect(externalFocus)")
        val frame = effect.indexOf("withFrameNanos { }")
        val yield = effect.indexOf("if (terminalImeFocus?.holdsKeyboard == true) return@LaunchedEffect")
        val request = effect.indexOf("focusRequester.requestFocus()")
        assertTrue("the yield must come before the request", frame in 1 until yield)
        assertTrue("the request must stay for every other mount", yield < request)
    }

    @Test
    fun everyUserTurnToTheEditorStillTakesTheKeyboard() {
        val body = composer()
        // The `input.focus` chord and the popovers' hand-back are user gestures,
        // not mounts: they must not consult the owner at all.
        val chord = body.substringAfter("LaunchedEffect(externalFocus) {")
            .substringBefore("LaunchedEffect(externalModelOpen)")
        assertTrue(chord.contains("focusRequester.requestFocus()"))
        assertFalse("an explicit request must never yield", chord.contains("holdsKeyboard"))
        val handBack = body.substringAfter("val dialogActive = LocalDialogActive.current")
            .substringBefore("val drafts by ComposerDrafts.state")
        assertTrue(handBack.contains("focusRequester.requestFocus()"))
        assertFalse(handBack.contains("holdsKeyboard"))
        assertTrue(body.contains("onRestoreFocus = { runCatching { focusRequester.requestFocus() } }"))
    }

    // -------------------------------------------------- the composer's identity

    @Test
    fun theComposerSurvivesTheArrangementFlipAsMovableContent() {
        val screen = sessionScreen()
        // v76 P0: the flip is not even a *move* any more — the pane measures one
        // node tree (`SessionPaneArrangement`) and the flip only changes the
        // viewport's constraints and the host's own modifier, so the editor's IME
        // session, its remembered draft/tokens and an open picker popup survive by
        // construction. The slot itself is kept for the one path that still moves
        // the subtree: the mobile view-tab switch, which disposes the pane.
        assertTrue(
            screen.contains(
                "val composerSlot: @Composable (@Composable () -> Unit) -> Unit = remember {",
            ),
        )
        assertTrue(screen.contains("movableContentOf<@Composable () -> Unit> { region -> region() }"))
        // The content is read through the movable lambda's parameter, not a
        // `rememberUpdatedState` mirror: the parameter is this frame's lambda by
        // construction, and updating it is a slot write — never a snapshot write
        // the runtime would have to re-target while the subtree is in flight
        // (that re-targeting is the runtime side of the v76 crash).
        assertFalse(screen.contains("val latestComposerRegion by rememberUpdatedState(composerRegion)"))
        // The alignment lives on the host `Box` inside `SessionPaneArrangement`, so
        // the composer itself is a plain composable both arrangements invoke.
        assertTrue(screen.contains("val composerRegion: @Composable () -> Unit = {"))
        assertFalse(screen.contains("val composerRegion: @Composable BoxScope.() -> Unit = {"))
    }

    @Test
    fun theComposerKeepsItsOwnConnectionWithoutAnAppSideRewrite() {
        // The rule the char loss is *not* allowed to come back through: the editor
        // never rewrites text the IME produced (a rewrite is a `restartInput()`,
        // which is what freezes Gboard's input state — `ComposerImeCommit`).
        val body = composer()
        val onValueChange = body.substringAfter("onValueChange = { input ->")
            .substringBefore("modifier = editorModifier")
        assertTrue(onValueChange.contains("val readable = ComposerImeCommit.readable(input.text)"))
        assertTrue(onValueChange.contains("setText(input)"))
        assertFalse("the fold must stay a read view", onValueChange.contains("HalfWidthText.normalize(input.text)"))
    }
}
