package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.TerminalFocusRequest
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v73 P2 — 「终端键盘输入被劫持到 composer」 (emulator-5556 desktop,
 * `research/v73_verify/5556/30-40`, re-measured in `research/v73_repro2`).
 *
 * Opening the docked terminal left the keyboard on the session composer: the pane
 * was on screen with its prompt and block cursor, `dumpsys input_method` still
 * served `AndroidComposeView` (the composer's connection), and both `adb input
 * text` and the on-screen keyboard typed into the composer's draft — `echo OK`
 * could never be run. The re-run pins the two halves of the routing:
 *
 * - **nothing asked the terminal for the focus.** `requestFocusFromTouch()` lived
 *   only in the vendored `TerminalView`'s own tap handling (the A′ patch), so a
 *   terminal the user had just *opened* received no keystroke; tapping the
 *   surface did hand the keyboard over (`mServedView=com.termux.view.TerminalView`,
 *   `echo` echoed back), which is what shows the input channel itself is fine and
 *   the missing piece is the ask.
 * - the composer getting it back needs no work: a tap on its field re-targets the
 *   IME (`mServedView=AndroidComposeView`, the next `input text` landed in the
 *   composer's draft), so this fix only has to *give* the keyboard to the
 *   terminal — it must not take it away later.
 *
 * [TerminalFocusRequest] is the port of upstream's ask: `requestFocus` /
 * `focusRequested` / `consumeFocus` (`session/terminal/context.tsx:110-124`),
 * raised by `terminal.toggle` + `openTerminal`
 * (`session/commands/use-session-commands.tsx:198,366`), by a tab chip
 * (`session/terminal/tab.tsx:58`) and by `terminal.new()`
 * (`context.tsx:249`), and satisfied by the surface's `autoFocus`
 * (`session/terminal/panel.tsx:356-361`).
 */
class TerminalFocusRoutingV73Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun panel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt")

    // ------------------------------------------------------------- the ask

    @Test
    fun aMountWithNoAskNeverTakesTheKeyboard() {
        val ask = TerminalFocusRequest()
        // The one case that has to stay silent: the dock is already open and the
        // terminal's view is rebuilt for a reason that is not the user turning to
        // it — a workspace switch, a revive, a rotation. Upstream's `restoreFocus`
        // branch (`terminal/terminal.tsx:466-474`) hands the caret *back* in that
        // case; here it simply means "no ask, no focus".
        assertFalse(ask.pending("terminal-1"))
    }

    @Test
    fun thePanelOpeningAsksForWhicheverTerminalTurnsOutToBeActive() {
        val ask = TerminalFocusRequest()
        // The panel knows no id while it restores its tabs, so the ask names none —
        // upstream's `pending` request (`context.tsx:110-112`, `requestFocus(undefined, true)`).
        ask.ask()
        assertTrue(ask.pending("terminal-1"))
        assertTrue(ask.pending("terminal-7"))
        // …and it is spent by the first view that is created for it, so the *next*
        // remount (the workspace switch) cannot take the caret back.
        ask.consume()
        assertFalse(ask.pending("terminal-1"))
        assertFalse(ask.pending("terminal-7"))
    }

    @Test
    fun aChipOrANewTerminalAsksOnlyForThatTerminal() {
        val ask = TerminalFocusRequest()
        ask.ask("terminal-2")
        assertTrue(ask.pending("terminal-2"))
        assertFalse("another terminal's view being built is not the user's turn", ask.pending("terminal-3"))
        ask.ask("terminal-3")
        assertTrue(ask.pending("terminal-3"))
        assertFalse("the ask moved to the newest target", ask.pending("terminal-2"))
        ask.consume()
        assertFalse(ask.pending("terminal-3"))
        // A later ask re-arms — the state is per ask, not a one-time latch.
        ask.ask("terminal-2")
        assertTrue(ask.pending("terminal-2"))
        assertFalse(ask.pending("terminal-3"))
    }

    // ------------------------------------------------------------- the wiring

    @Test
    fun thePaneOpeningIsTheAskAndTheFactoriesRunBeforeAnyEffect() {
        val body = panel()
        // The ask cannot ride a `LaunchedEffect(Unit)`: the first slot's
        // `AndroidView` factory runs during the layout of this same frame, which
        // is *before* any effect body. It is the state's initial value instead.
        assertTrue(body.contains("val focusRequest = remember { TerminalFocusRequest().apply { ask() } }"))
    }

    @Test
    fun theSlotBeingCreatedTakesTheKeyboardAndSpendsTheAsk() {
        val body = panel()
        val factory = body.substring(
            body.indexOf("factory = { context ->"),
            body.indexOf("update = { view ->"),
        )
        assertTrue(
            "the ask is answered where the view starts to exist",
            factory.contains("if (focusRequest?.pending(runtime.id) == true)"),
        )
        assertTrue(
            "the factory runs before the view has a window, where a direct " +
                "requestFocusFromTouch() is a silent no-op",
            factory.contains("view.post { claimTerminalFocus(view, attempt = 0) }"),
        )
        // Consume *before* the post: the ask is spent by the view it was made for,
        // whatever the post does later.
        assertTrue(
            factory.indexOf("focusRequest.consume()") in 1 until
                factory.indexOf("view.post { claimTerminalFocus(view, attempt = 0) }"),
        )
        // `requestFocusFromTouch`, not `requestFocus`: leaving touch mode with the
        // tap the user is about to type after is what the A′ patch already settled
        // (TerminalView.java:164-175) — plain `requestFocus` spends the next key on
        // the framework leaving touch mode.
        assertTrue(body.contains("view.requestFocusFromTouch()"))
    }

    /**
     * v74: the ask above is only half of the routing. On device a single request
     * from the attach did not hand the IME over (`research/v74_verify/5556/`), so
     * the claim is re-asserted until the view really has the focus — and the
     * answer is reported, which is what lets the composer yield.
     */
    @Test
    fun theClaimIsReassertedUntilTheViewReallyHasTheFocus() {
        val body = panel()
        val claim = body.substring(
            body.indexOf("private fun claimTerminalFocus"),
            body.indexOf("private class OcTerminalViewClient"),
        )
        // Only while the focus has not landed…
        assertTrue(claim.contains("if (view.hasFocus()) return"))
        // …and only for a bounded number of frames: a terminal that still could
        // not take the focus must not keep fighting a tap on the composer.
        assertTrue(claim.contains("if (!view.hasFocus() && attempt < FOCUS_CLAIM_ATTEMPTS)"))
        // One attempt per frame, not per message-loop pass: `post` three times
        // would drain before the view could have been laid out at all.
        assertTrue(claim.contains("view.postOnAnimation { claimTerminalFocus(view, attempt + 1) }"))
        assertTrue(body.contains("private const val FOCUS_CLAIM_ATTEMPTS = 3"))
    }

    @Test
    fun theSlotReportsWhetherTheTerminalHoldsTheKeyboard() {
        val body = panel()
        // The IME follows the window's view focus, so the view's own focus
        // listener is the ground truth the composer reads.
        assertTrue(body.contains("view.setOnFocusChangeListener { _, hasFocus -> imeFocus?.report(hasFocus) }"))
        // A view that is released while focused never fires the listener again.
        val release = body.substring(
            body.indexOf("onRelease = { view ->"),
            body.indexOf("modifier = Modifier.fillMaxSize(),"),
        )
        assertTrue(release.contains("imeFocus?.report(false)"))
        // …and neither does the pane going away as a whole.
        val dispose = body.substring(
            body.indexOf("DisposableEffect(Unit) {"),
            body.indexOf("Column(modifier.fillMaxSize().background(c.bgBase))"),
        )
        assertTrue(dispose.contains("imeFocus?.report(false)"))
        // The slot is wired from the pane's single read of the channel.
        assertTrue(body.contains("val imeFocus = LocalTerminalImeFocus.current"))
        assertTrue(body.contains("imeFocus = imeFocus,"))
    }

    @Test
    fun everyUserTurnToATerminalAsksAndARestoreDoesNot() {
        val body = panel()
        // The chip the user picked (`terminal/tab.tsx:58`): the slot is rebuilt for
        // the new active id, so without the ask the caret would go nowhere at all.
        assertTrue(body.contains("focusRequest.ask(id)"))
        // A session terminal picked out of the picker.
        assertTrue(body.contains("focusRequest.ask(info.id)"))
        // `terminal.new()` — the `+`, the `terminal.new` chord and the pane's own
        // first terminal (`context.tsx:249`).
        assertTrue(body.contains("focusRequest.ask(result.value.id)"))
        // A revive re-adopts what the last process left behind: a restore, never a
        // user turn to the terminal.
        val revive = body.substring(
            body.indexOf("suspend fun reviveTerminals"),
            body.indexOf("fun createTerminal"),
        )
        assertFalse(
            "re-adopting persisted tabs must not steal the caret (upstream `restoreFocus`)",
            revive.contains("focusRequest"),
        )
    }

    @Test
    fun closingThePaneHandsTheCaretBackToTheComposer() {
        val body = panel()
        val dispose = body.substring(
            body.indexOf("DisposableEffect(Unit) {"),
            body.indexOf("Column(modifier.fillMaxSize().background(c.bgBase))"),
        )
        // Only while the terminal really held the keyboard: a pane that never took
        // it (a mobile tab the user never typed in) has nothing to hand back.
        assertTrue(
            dispose.contains("runtimes.values.any { it.terminalView?.hasFocus() == true }"),
        )
        // …and it hands it back down the shell's own path — the `input.focus`
        // request the session screen already maps onto the composer, so closing the
        // terminal behaves like every other close.
        assertTrue(dispose.contains("intents?.requestCommand(\"input.focus\")"))
        // The channel is read in the composition: `CompositionLocal.current` outside
        // one is an error, and this runs from `onDispose`.
        assertTrue(body.contains("val intents = LocalSessionCommandIntents.current"))
        // Before the views are dropped (the check asks the live views).
        assertTrue(dispose.indexOf("hasFocus()") < dispose.indexOf("runtimes.clear()"))
    }

    @Test
    fun theTapPathThatAlreadyWorkedIsLeftToTheVendoredView() {
        // The fix adds the *ask*; the tap route (`TerminalView`'s
        // `requestFocusFromTouch` patch) is what the device run showed working, so
        // the client must keep synthesising nothing and consuming nothing.
        val client = panel().substring(
            panel().indexOf("private class OcTerminalViewClient"),
            panel().indexOf("private class OcTerminalSessionClient"),
        )
        assertTrue(client.contains("override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false"))
        assertTrue(client.contains("override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false"))
    }
}
