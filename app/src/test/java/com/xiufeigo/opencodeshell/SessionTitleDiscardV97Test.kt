package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.header.InlineTitleEdit
import com.xiufeigo.opencodeshell.ui.session.header.TitleEditFence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v97 N3 fix2 (`research/v97_fix/TITLE_DISCARD_FIX2.md`): **leaving the session
 * must discard the inline title draft, not commit it.**
 *
 * ## Why fix1's guard could not work
 *
 * fix1 (`TITLE_EDITOR_DISCARD_FIX.md`) added `InlineTitleEdit.leaving` + a
 * `DisposableEffect` teardown latch, on the theory that the save came from the
 * **detach** blur of the torn-down composition. Device B4 falsified that
 * (`research/v97_verify/5556_v98/REPORT_FIXES.md`, `12:26:07` and `12:39:01`, two
 * builds): tap title → type `E2ECHK5556v98_discard` → tap 首页/Home → the draft
 * is already in `GET /api/session`.
 *
 * The real path is a **focus steal**, and it is a different event:
 *
 * ```
 * tap 首页/Home  ([8,30][56,78] — shell chrome, ui/App.kt, OUTSIDE SessionScreen)
 *   → the Home entry is a Modifier.clickable Box, and a clickable TAKES FOCUS
 *   → the header's BasicTextField loses focus while the session is still composed
 *   → SessionHeader.kt onFocusChanged → focused == true → commit()
 *   → edit.commit(draft): leaving == false, settled == false  ⇒ Decision(save = draft)
 *   → onCommit → if (renamed != title) onRename(renamed)
 *   → SessionScreen.kt onRename → chatViewModel.renameSession(...) → PATCH /session/{id}
 * …and only NOW does the navigation dispose the composition, so fix1's onDispose
 * latch armed *after the write had already left the shell*. It could not help:
 * `settled` was already true, so leave() returned Decision(close = false).
 * ```
 *
 * The tab-switch variant is the same root cause: the tab strip is chrome too, also
 * `Modifier.clickable`, also outside the session's composition.
 *
 * ## The fence
 *
 * A blur is therefore not a reason to save — an **authorization** is. Enter and
 * the IME's `Done` arrive while the field still holds focus and are never gated.
 * The one blur that means 「tapped away inside the session」 is the one
 * `SessionScreen.blurTitleEditorOnOutsideTap` produces, and it arms
 * [TitleEditFence] synchronously immediately before its `clearFocus()`. Every
 * other blur — the chrome's Home button, a tab, the back chevron, the drawer
 * entry, the teardown detach — arrives unarmed and is discarded.
 *
 * These pins split the way `SessionTitleInlineEditTest` does: the **decision** as
 * behaviour, the **wiring** as a source pin. fix1's error was pinning only the
 * path it believed in, so the source pins below deliberately name the *real*
 * path: the armed tap-away, the unarmed focus steal, and the absence of any
 * frame-delayed save.
 */
class SessionTitleDiscardV97Test {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun header() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt")

    private fun model() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/header/InlineTitleEdit.kt")

    private fun fenceSource() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/header/TitleEditFence.kt")

    private fun screen() = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private fun editor() = header().substring(header().indexOf("private fun InlineTitleInput("))

    /**
     * The composable's decision plumbing, transcribed so the assertions can be made
     * on behaviour instead of on a string. Every line is pinned against the real
     * source by [theBlurAndTheCommitExitAreUntouched] and
     * [theBlurIsGatedOnAnAuthorizationAndNotOnTiming], so the transcription cannot
     * drift away from the editor without a red build.
     *
     * [renames] is the `SessionHeader` `onRename` sink: `SessionHeader.kt`'s
     * `onCommit = { renamed -> editing = false; if (renamed != title) onRename(renamed) }`,
     * one hop downstream of `SessionScreen`'s `renameSession` (that last hop is
     * `SessionTitleInlineEditTest.theCommitStillReachesTheViewModelRename`'s).
     *
     * [fieldFocused] is the `onFocusEvent` observer — the field's *current* focus,
     * as distinct from [focused], the v32 "has ever been focused" latch. The two
     * together are what let a save be told apart from a blur.
     */
    private class EditorHarness(
        private val title: String,
        initial: String = title,
        /** Null models a host that never installed the screen's tap-away handler. */
        val fence: TitleEditFence? = TitleEditFence(),
    ) {
        val renames = mutableListOf<String>()
        val edit = InlineTitleEdit(initial)
        var value = initial
        var focused = false
        var fieldFocused = false

        /** `BasicTextField.onValueChange` → `value = HalfWidthText.normalizeName(it)`. */
        fun type(text: String) {
            value = text
        }

        /** `onFocusChanged` + `onFocusEvent` both firing `isFocused == true`. */
        fun gainsFocus() {
            focused = true
            fieldFocused = true
            fence?.disarm()
        }

        /** Enter, the IME's `Done`, and the blur below all land here. */
        fun commit() {
            if (!fieldFocused && fence?.consumeBlurSave() != true) {
                discard()
                return
            }
            val decision = edit.commit(value)
            if (decision.save != null) onCommit(decision.save) else if (decision.close) onCancel()
        }

        /** The blur nobody asked for — what `discard()` does. */
        fun discard() {
            if (edit.leave().close) onCancel()
        }

        /** `cancel()` — Escape. */
        fun cancel() {
            if (edit.cancel().close) onCancel()
        }

        /**
         * `onFocusChanged { … else if (focused) commit() }` — **unarmed**, i.e. the
         * device's B4 path: the chrome's clickable took the focus for itself.
         */
        fun losesFocus() {
            fieldFocused = false
            if (!focused) return
            commit()
        }

        /**
         * `blurTitleEditorOnOutsideTap`: the screen arms the fence and then clears
         * focus, so this blur is authorized and commits — upstream's `onBlur → save`
         * (`message-timeline.tsx:485`), kept 1:1.
         */
        fun inSessionTapAway() {
            fence?.arm()
            losesFocus()
        }

        /** `DisposableEffect(Unit) { onDispose { … } }` on the editor. */
        fun leaveComposition() {
            fence?.disarm()
            edit.leave()
        }

        private fun onCommit(renamed: String) {
            if (renamed != title) renames += renamed
        }

        private fun onCancel() = Unit
    }

    // --- ① the REAL path: an unarmed blur must never save --------------------------

    @Test
    fun anUnarmedBlurIsAFocusStealAndDiscardsTheDraft() {
        // The device's B4 reproduction, verbatim: the Home entry is a clickable that
        // takes focus, so the field blurs *while the session is still composed* and
        // with no arm anywhere. fix1's teardown latch cannot have run yet.
        val editor = EditorHarness(title = "Untitled")
        editor.gainsFocus()
        editor.type("E2ECHK5556v98_discard")
        editor.losesFocus()

        assertEquals(emptyList<String>(), editor.renames)
        assertTrue("the editor must latch the discard", editor.edit.leaving)
    }

    @Test
    fun anUnarmedBlurDoesNotCommitEvenWhenTheCompositionIsStillAlive() {
        // The precise fact fix1 got wrong, pinned on its own: the save is dispatched
        // while `editing` is still true and the composition is still composed. A fix
        // that relies on a teardown event cannot see this state at all.
        val editor = EditorHarness(title = "Untitled")
        editor.gainsFocus()
        editor.type("E2ECHK5556v98_discard")
        editor.losesFocus()

        assertEquals(emptyList<String>(), editor.renames)
        // …and no `leave()` was needed to get there: the state machine discarded on
        // the blur itself, not because the session went away.
        assertTrue(editor.edit.leaving)
    }

    @Test
    fun theChromeNeverArmsTheFenceItself() {
        // The header only ever *consumes*; the one and only `arm()` in the shell is
        // the screen's outside-tap handler. If a second arm site ever appeared, the
        // discard would silently become a save again.
        assertFalse(
            "only SessionScreen's outside-tap handler may arm the fence",
            editor().contains("titleEditFence?.arm()"),
        )
        val screenArms = Regex("""titleEditFence\.arm\(\)""").findAll(screen()).count()
        assertEquals("exactly one arm site in the shell", 1, screenArms)
    }

    // --- ② the authorization is single-use and cannot be inherited ------------------

    @Test
    fun anArmIsSpentByTheBlurItWasGivenFor() {
        val fence = TitleEditFence()
        fence.arm()
        assertTrue("an armed tap-away may save", fence.consumeBlurSave())
        assertFalse("and it is spent", fence.armed)
        // A later blur — the chrome's Home button this time — inherits nothing.
        assertFalse(fence.consumeBlurSave())
    }

    @Test
    fun aTapAwayCannotAuthorizeALaterFocusSteal() {
        val editor = EditorHarness(title = "Untitled")
        editor.gainsFocus()
        editor.type("first")
        // An armed in-session tap-away commits …
        editor.inSessionTapAway()
        assertEquals(listOf("first"), editor.renames)
    }

    @Test
    fun disarmingDropsAnUnspentAuthorization() {
        // `clearFocus()` on a field that was not focused produces no blur at all;
        // that unused arm must not outlive the editor it was handed to.
        val fence = TitleEditFence()
        fence.arm()
        fence.disarm()
        assertFalse(fence.armed)
        assertFalse(fence.consumeBlurSave())
    }

    @Test
    fun aHostWithoutTheTapAwayHandlerDiscardsRatherThanSaves() {
        // `titleEditFence` is nullable so previews still compose. Such a host has no
        // way to produce an in-session tap-away blur, so the only blurs it can see
        // are focus steals — and the safe default is the discard, not the save that
        // put `E2ECHK5556v98_discard` on the server.
        val editor = EditorHarness(title = "Untitled", fence = null)
        editor.gainsFocus()
        editor.type("Queued")
        editor.losesFocus()
        assertEquals(emptyList<String>(), editor.renames)
    }

    // --- ③ leaving the composition still discards (fix1's contract, kept) ----------

    @Test
    fun leavingTheScreenDiscardsTheDraftWithoutRenaming() {
        val editor = EditorHarness(title = "E2ECHK5562-01")
        editor.gainsFocus()
        editor.type("Queued")

        // The navigation: the composition goes away before any blur is served.
        editor.leaveComposition()
        // …and the detach that caused the navigation blur arrives afterwards. It
        // must decide nothing at all.
        editor.losesFocus()

        assertEquals(emptyList<String>(), editor.renames)
    }

    @Test
    fun theLeaveDiscardIsIndependentOfTheSettledLatch() {
        // `leaving` is checked in `commit` itself, so "a commit after the editor
        // stopped being the on-screen one can never save" is a property of the
        // state machine — not a consequence of whichever event happened to arrive
        // first. If `settled` were removed tomorrow, this still holds.
        val edit = InlineTitleEdit("Untitled")
        edit.leave()
        assertEquals(InlineTitleEdit.Decision(close = false), edit.commit("Queued"))

        // A commit arriving after the editor is gone decides nothing at all, and
        // `onRename` is never reached.
        val gone = EditorHarness("Untitled").apply {
            gainsFocus()
            type("Queued")
            leaveComposition()
            commit()
            losesFocus()
        }
        assertEquals(emptyList<String>(), gone.renames)
    }

    @Test
    fun theTeardownHookLandsBeforeTheDetachBlurCanBeServed() {
        // fix1's timing claim survives as a pin, but it is now a *second* line of
        // defence rather than the fix: a `DisposableEffect` is what puts the latch
        // in the `applyChanges` pass that detaches the node; a `LaunchedEffect`
        // would land a frame later.
        val body = editor()
        val leave = body.lastIndexOf("edit.leave()")
        assertTrue(leave > 0)
        val effect = body.lastIndexOf("DisposableEffect", leave)
        assertTrue(effect > 0)
        assertTrue(body.substring(effect, leave).contains("DisposableEffect(Unit)"))
        assertTrue(body.substring(effect, leave).contains("onDispose {"))
        assertFalse(
            "the leave latch must not be a coroutine that resumes after the detach",
            body.contains("LaunchedEffect(Unit) { edit.leave() }"),
        )
        // The latch has to be armed by the editor itself, not by the header that is
        // being torn down with it — the header's `editing` is dead by then.
        assertTrue(body.contains("val edit = remember { InlineTitleEdit(initial) }"))
    }

    @Test
    fun leaveIsADiscardAndNotARename() {
        val edit = InlineTitleEdit("Untitled")
        assertFalse(edit.leaving)
        assertEquals(InlineTitleEdit.Decision(close = true), edit.leave())
        assertTrue(edit.leaving)
        assertTrue(edit.settled)
        // Every later event is inert, exactly as after Escape.
        assertEquals(InlineTitleEdit.Decision(close = false), edit.commit("Queued"))
        assertEquals(InlineTitleEdit.Decision(close = false), edit.cancel())
    }

    @Test
    fun aBlankDraftIsStillNeverSubmittedOnTheWayOut() {
        val editor = EditorHarness(title = "Untitled")
        editor.gainsFocus()
        editor.type("   ")
        editor.losesFocus()
        assertEquals(emptyList<String>(), editor.renames)
    }

    // --- ④ the tap-away inside the session still commits (upstream `onBlur`) -------

    @Test
    fun aTapAwayInsideTheSessionStillCommits() {
        // The regression guard for the other half: upstream's `onBlur` is
        // `void saveTitleEditor()` (`message-timeline.tsx:485`), so a tap-away that
        // the screen itself produced must keep committing. fix2 must not have turned
        // the editor into an escape-proof field.
        val editor = EditorHarness(title = "Untitled")
        editor.gainsFocus()
        editor.type("Queued")
        editor.inSessionTapAway()
        assertEquals(listOf("Queued"), editor.renames)
    }

    @Test
    fun theArmedTapAwayAndTheUnarmedBlurDifferOnlyByTheAuthorization() {
        // Same editor, same draft, same blur — the one variable is the arm. This is
        // the pair fix1 could not express, and the pair the device disagrees about.
        val commits = EditorHarness("Untitled").apply {
            gainsFocus(); type("Queued"); inSessionTapAway()
        }
        val discards = EditorHarness("Untitled").apply {
            gainsFocus(); type("Queued"); losesFocus()
        }
        assertEquals(listOf("Queued"), commits.renames)
        assertEquals(emptyList<String>(), discards.renames)
    }

    // --- ⑤ Enter keeps committing, exactly once -------------------------------------

    @Test
    fun enterStillCommitsAndCommitsOnlyOnce() {
        val editor = EditorHarness(title = "Untitled")
        editor.gainsFocus()
        editor.type("Queued")
        editor.commit() // Enter, or the IME's `Done`.

        assertEquals(listOf("Queued"), editor.renames)

        // Committing detaches the field, and that detach blurs it. One edit must
        // still reach the server once — the v31/v32 contract.
        editor.losesFocus()
        editor.leaveComposition()
        assertEquals(listOf("Queued"), editor.renames)
    }

    @Test
    fun enterCommitsWithNothingArmedAndTheEditorStillFocused() {
        // Enter is never gated on the fence. B5 is a PASS on the device and must stay
        // one; the gate must not be able to swallow it.
        val editor = EditorHarness(title = "Untitled")
        editor.gainsFocus()
        editor.type("E2ECHK5556v98_enter")
        assertFalse("Enter is authorized by being focused, not by an arm", editor.fence!!.armed)
        editor.commit()
        assertEquals(listOf("E2ECHK5556v98_enter"), editor.renames)
    }

    @Test
    fun escapeStillCancelsAndEnterStillCommits() {
        val cancelled = EditorHarness("Untitled").apply {
            gainsFocus()
            type("Queued")
            cancel()
            losesFocus()
        }
        assertEquals(emptyList<String>(), cancelled.renames)

        val committed = EditorHarness("Untitled").apply {
            gainsFocus()
            type("Queued")
            commit()
        }
        assertEquals(listOf("Queued"), committed.renames)
    }

    // --- ⑥ source pins: the wiring, including the path fix1 missed ----------------

    @Test
    fun theBlurIsGatedOnAnAuthorizationAndNotOnTiming() {
        val body = editor()
        // The gate, verbatim, in the commit the blur reaches …
        assertTrue(
            "a blur must be gated on the fence, not committed unconditionally",
            body.contains("if (!fieldFocused && titleEditFence?.consumeBlurSave() != true) {"),
        )
        // … and it must run BEFORE the save is computed, or the write is already out.
        val gate = body.indexOf("if (!fieldFocused && titleEditFence?.consumeBlurSave() != true) {")
        val save = body.indexOf("val decision = edit.commit(value.text)")
        assertTrue("the gate must precede the decision", gate in 0 until save)
        // The unauthorized branch discards; it must not fall through to a save.
        val branch = body.substring(gate, body.indexOf("val decision = edit.commit(value.text)", gate))
        assertTrue(branch.contains("discard()"))
        assertTrue(branch.contains("return"))
    }

    @Test
    fun theSaveIsNeverDeferredToALaterFrame() {
        // The task's explicit prohibition, pinned: no "commit on the next frame",
        // no post-delay save, no `LaunchedEffect`/`withFrameNanos` gate anywhere on
        // the save path. A deferred save is a guess about dispatch order, which is
        // exactly the class of reasoning fix1 got wrong.
        val body = editor()
        assertFalse(body.contains("withFrameNanos"))
        assertFalse(body.contains("delay("))
        assertFalse(body.contains("snapshotFlow"))
        // The composable does have a `LaunchedEffect` — it has always owned one, to
        // request focus and raise the keyboard on open. What must not exist is a
        // save that is *deferred* onto it, so the coroutine itself is checked rather
        // than its mere presence.
        val effect = body.indexOf("LaunchedEffect")
        if (effect > 0) {
            val block = body.substring(effect, body.indexOf("}", effect))
            assertFalse("no save may be deferred onto a coroutine", block.contains("commit"))
            assertFalse(block.contains("discard"))
            assertFalse(block.contains("onCommit"))
        }
    }

    @Test
    fun theBlursOwnObserverSetsTheFocusTheGateReads() {
        // fix3: `commit()`'s gate reads `fieldFocused`, and the *only* observer that
        // is guaranteed to have run before `commit()` on the blur path is the one in
        // the same handler that calls it. fix2 relied on a second observer —
        // `.onFocusEvent`, a different node in the focus target chain — to keep
        // `fieldFocused` current. Whether Compose delivers one focus event to that
        // node before or after the one carrying `onFocusChanged` is the focus
        // system's business, not ours; if `onFocusChanged` wins the race while
        // `fieldFocused` is still `true`, the gate's `!fieldFocused` half is false,
        // the fence is never consulted, and the chrome's 首页/Home focus steal lands
        // on `edit.commit(value.text)` — fix2's device bug, with a green tree.
        // The mirror has to live in the blur's own handler.
        val body = editor()
        val changed = body.indexOf(".onFocusChanged { state ->")
        assertTrue("the blur observer must exist", changed > 0)
        val observer = body.substring(changed, body.indexOf("}", changed))
        assertTrue(
            "onFocusChanged must publish the live focus the gate reads",
            observer.contains("fieldFocused = state.isFocused"),
        )
        // …and it must do so **before** the `commit()` that same handler can reach,
        // or the gate reads the previous value.
        val mirror = observer.indexOf("fieldFocused = state.isFocused")
        // Anchored on the whole pinned line, not on the bare `commit()`: the
        // handler's own comment discusses `commit()`, and a bare indexOf would
        // match the prose and make this pin test the comment rather than the code.
        val pinnedBlur = "if (state.isFocused) focused = true else if (focused) commit()"
        val gateCall = observer.indexOf(pinnedBlur)
        assertTrue("the upstream blur-commit line must still be present", gateCall > 0)
        assertTrue(
            "the live focus must be published before the commit it gates",
            mirror in 0 until gateCall,
        )
        // The upstream blur-commit line itself is untouched (pinned verbatim by
        // `SessionTitleInlineEditTest.escapeCancels` and by
        // `theBlurAndTheCommitExitAreUntouched` above).
        assertTrue(observer.contains(pinnedBlur))
    }

    @Test
    fun theFieldObservesItsOwnCurrentFocus() {
        // `focused` is the v32 "has ever been focused" latch and stays true after a
        // blur, so it cannot answer "is the field focused right now?". The gate
        // needs the live answer, and gets it from an observer that never consumes.
        val body = editor()
        assertTrue(body.contains(".onFocusEvent { state ->"))
        val observer = body.indexOf(".onFocusEvent { state ->")
        val close = body.indexOf("}", observer)
        val onEvent = body.substring(observer, close)
        assertTrue(onEvent.contains("fieldFocused = state.isFocused"))
        assertTrue(onEvent.contains("titleEditFence?.disarm()"))
        // It observes only: the handler returns `false`, so no event is consumed on
        // the way to the `onFocusChanged` above it.
        assertTrue(
            "the observer must return false so the event is not consumed",
            onEvent.trimEnd().endsWith("false"),
        )
    }

    @Test
    fun theScreenArmsTheFenceInTheSameBreathAsItClearsFocus() {
        // This is the pin fix1's file never had: the authorization is granted by the
        // *outside-tap handler*, immediately before the `clearFocus()` it causes.
        val body = screen()
        val helper = body.indexOf("private fun Modifier.blurTitleEditorOnOutsideTap(")
        assertTrue(helper > 0)
        val modifier = body.substring(helper, body.indexOf("private fun Banner(", helper))
        val arm = modifier.indexOf("titleEditFence.arm()")
        val clear = modifier.indexOf("focusManager.clearFocus()")
        assertTrue(arm > 0 && clear > 0)
        assertTrue("the arm must precede the clearFocus it authorizes", arm < clear)
        // Synchronous: no frame, no coroutine, no delay between them.
        val between = modifier.substring(arm, clear)
        assertFalse(between.contains("withFrame"))
        assertFalse(between.contains("delay("))
        assertFalse(between.contains("LaunchedEffect"))
        // The v31 gate that makes an arm mean something is untouched …
        assertTrue(modifier.contains("awaitPointerEvent(PointerEventPass.Final)"))
        assertTrue(modifier.contains("event.changes.none { it.isConsumed }"))
        // … and a closed editor drops any arm it was still holding.
        assertTrue(modifier.contains("titleEditFence.disarm()"))
    }

    @Test
    fun theHeaderAndTheScreenShareOneFence() {
        val body = screen()
        // The screen owns it, hands the same instance to both the header and the
        // tap-away handler, and its `remember` scope is the session's.
        assertTrue(body.contains("val titleEditFence = remember { TitleEditFence() }"))
        assertTrue(body.contains("titleEditFence = titleEditFence,"))
        assertTrue(body.contains(".blurTitleEditorOnOutsideTap(titleEditing, focusManager, titleEditFence)"))
    }

    @Test
    fun theEditorLatchesTheDiscardWhenItsCompositionIsTornDown() {
        val body = editor()
        // `discard()` calls `edit.leave()` too, and it is declared above the
        // teardown — the *last* call is the dispose hook, which is the one this pin
        // is about.
        val leave = body.lastIndexOf("edit.leave()")
        assertTrue("InlineTitleInput must latch a discard on teardown (v97 N3)", leave > 0)
        val effect = body.lastIndexOf("DisposableEffect", leave)
        assertTrue(effect > 0 && body.substring(effect, leave).contains("DisposableEffect(Unit)"))
        // The teardown must not write state into a header that is itself going away.
        val hook = body.substring(effect, body.indexOf("}", leave))
        assertFalse(hook.contains("onCommit"))
        assertFalse(hook.contains("value ="))
        // …and it drops the authorization first: a permission dies with its editor.
        assertTrue(hook.contains("titleEditFence?.disarm()"))
        assertTrue(hook.indexOf("titleEditFence?.disarm()") < hook.indexOf("edit.leave()"))
    }

    @Test
    fun theBlurAndTheCommitExitAreUntouched() {
        // The upstream blur-commit line is byte-for-byte the one
        // `SessionTitleInlineEditTest.escapeCancels` pins; fix2 gates *inside* the
        // commit it reaches, so both of those pins survive unedited.
        val body = editor()
        assertTrue(body.contains("if (state.isFocused) focused = true else if (focused) commit()"))
        assertTrue(body.contains("val decision = edit.commit(value.text)"))
        assertTrue(
            body.contains(
                "if (decision.save != null) onCommit(decision.save) else if (decision.close) onCancel()",
            ),
        )
        // Enter and Escape are the two paths that stay reachable.
        assertTrue(body.contains("Key.Enter, Key.NumPadEnter -> {"))
        assertTrue(body.contains("Key.Escape -> {"))
    }

    @Test
    fun theHeaderStillPublishesAndDropsItsEditingStateAroundTheTeardown() {
        // The screen's tap-away arms on this; it must survive, and the header's own
        // `onDispose` must not have been repurposed by fix2.
        val body = header()
        assertTrue(body.contains("LaunchedEffect(editing) { onTitleEditing(editing) }"))
        assertTrue(body.contains("onDispose { onTitleEditing(false) }"))
        // The rename sink is untouched: the discard is upstream of it.
        assertTrue(body.contains("if (renamed != title) onRename(renamed)"))
    }

    @Test
    fun theFenceDocumentsThePathItExistsFor() {
        // The fence is the whole fix; its KDoc has to keep naming the two builds it
        // was written for, or a later reader sees only a boolean with no reason.
        val body = fenceSource()
        assertTrue(body.contains("class TitleEditFence"))
        assertTrue(body.contains("Modifier.clickable"))
        assertTrue(body.contains("E2ECHK5556v98_discard"))
        assertTrue(body.contains("consumeBlurSave"))
        assertTrue(body.contains("fun arm()"))
        assertTrue(body.contains("fun disarm()"))
    }

    @Test
    fun theModelDocumentsTheUpstreamGuardItMirrors() {
        val body = model()
        // The discard is a documented contract of the state machine, not an
        // undocumented branch: the KDoc must keep naming the upstream lines it
        // replicates, or a later reader loses the reason the field exists.
        assertTrue(body.contains("fun leave(): Decision"))
        assertTrue(body.contains("message-timeline.tsx:285-289"))
        assertTrue(body.contains("var leaving: Boolean = false"))
    }
}
