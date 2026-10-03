package com.xiufeigo.opencodeshell

import androidx.compose.ui.text.TextRange
import com.xiufeigo.opencodeshell.ui.session.header.InlineTitleEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v31 BUG-2: the header's inline session-title editor must reach the server.
 *
 * Device run: the editor opened and accepted typing, but Enter, an outside tap
 * and Escape all left the server title untouched — only the ⋮ → Rename dialog
 * ever wrote. Two of the three paths could not work on Android:
 *
 * - a hardware Enter (`input keyevent 66`, a docked keyboard) is a plain key
 *   event; Compose never turns it into the IME's `Done` action, so nothing
 *   committed;
 * - Compose only moves focus when something *takes* it, so an outside tap never
 *   blurred the field and its blur-commit never ran.
 *
 * v32 BUG-1/BUG-2 — the same field, the opposite failures (`research/v32_verify/tA/`):
 *
 * - **Escape saved** (`tA_19_esc_cancel.png`). Closing the editor detaches the focused
 *   field, and `FocusTargetNode.onDetach` force-clears focus with
 *   `refreshFocusEvents = true` (`ui-android:1.7.6`), so the field's `onFocusChanged`
 *   observer fires `isFocused == false` *after* the cancel — the blur-commit the editor
 *   wants for a tap-away then wrote the draft the cancel had just discarded.
 * - **The draft spliced into the old title** (`tA_17_title_typed.png`): the editor opened
 *   with the cursor *in front of* `Untitled` and the typed text landed before it, because
 *   the String overload of `BasicTextField` seeds `TextFieldValue(text = value)` — i.e.
 *   `TextRange.Zero`. Upstream selects the whole title instead (`titleRef.select()`,
 *   `message-timeline.tsx:270-277`).
 *
 * The decision is [InlineTitleEdit]'s and is pinned as **behaviour**; the editor and its
 * wiring live inside `@Composable` functions, which a plain JVM test cannot call, so the
 * wiring half stays a source-level pin — the same split `ComposerPopoverEscTest` uses.
 */
class SessionTitleInlineEditTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun header() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt")

    private fun model() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/header/InlineTitleEdit.kt")

    private fun screen() = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    // --- v32 BUG-1: Escape cancels, and the blur that follows it decides nothing ----

    @Test
    fun escapeCancelsAndSwallowsTheBlurThatClosingTheEditorProduces() {
        val edit = InlineTitleEdit("Untitled")
        assertEquals(InlineTitleEdit.Decision(close = true), edit.cancel())
        assertTrue(edit.settled)
        // Closing the editor detaches the focused field, so `onFocusChanged` fires
        // `isFocused == false` after the cancel and `commit()` runs on the draft the
        // cancel threw away. It must not reach the server.
        assertEquals(InlineTitleEdit.Decision(close = false), edit.commit("e2echj-tntoe-w12Untitled"))
        // A second Escape (or any later event) is equally inert.
        assertEquals(InlineTitleEdit.Decision(close = false), edit.cancel())
    }

    @Test
    fun enterAndBlurStillCommit() {
        // Enter, the IME's Done action and a tap-away blur all land on `commit`.
        val enter = InlineTitleEdit("Untitled")
        assertEquals(InlineTitleEdit.Decision(close = true, save = "w12"), enter.commit("  w12 "))
        // The blur of the teardown that follows writes nothing a second time, so one
        // rename reaches the server per edit.
        assertEquals(InlineTitleEdit.Decision(close = false), enter.commit("w12"))
    }

    @Test
    fun aBlankDraftIsNeverSubmitted() {
        assertEquals(InlineTitleEdit.Decision(close = true), InlineTitleEdit("Untitled").commit("   "))
        assertEquals(InlineTitleEdit.Decision(close = true), InlineTitleEdit("Untitled").commit(""))
    }

    // --- v32 BUG-2: the editor opens with the whole title selected -------------------

    @Test
    fun enteringTheEditorSelectsTheWholeTitle() {
        assertEquals(TextRange(0, 8), InlineTitleEdit("Untitled").entrySelection)
        // Nothing to select in an empty title, and the range stays valid.
        assertEquals(TextRange(0, 0), InlineTitleEdit("").entrySelection)
    }

    @Test
    fun theFieldCarriesTheEntrySelectionAndDrivesTheDecision() {
        val input = header().substring(header().indexOf("private fun InlineTitleInput("))
        assertTrue(input.contains("val edit = remember { InlineTitleEdit(initial) }"))
        // The value overload, so the entry selection can be expressed at all.
        assertTrue(input.contains("TextFieldValue(initial, edit.entrySelection)"))
        assertTrue(input.contains("onValueChange = { value = HalfWidthText.normalizeName(it) }"))
        // One decision, honoured on every path — a save writes, a cancel closes without
        // writing, a settled editor does neither.
        assertTrue(input.contains("val decision = edit.commit(value.text)"))
        assertTrue(
            input.contains(
                "if (decision.save != null) onCommit(decision.save) else if (decision.close) onCancel()",
            ),
        )
        assertTrue(input.contains("if (edit.cancel().close) onCancel()"))
        // The blank-draft rule is `InlineTitleEdit.commit`'s (behaviour pinned above).
        assertTrue(model().contains("trimmed.isEmpty()"))
    }

    // --- v31: the two paths the device never reached ---------------------------------

    @Test
    fun enterCommitsAsBothAKeyEventAndAnImeAction() {
        val body = header()
        val editor = body.indexOf("private fun InlineTitleInput(")
        assertTrue(editor > 0)
        val input = body.substring(editor)
        // The phone's IME (Done) …
        assertTrue(input.contains("keyboardActions = KeyboardActions(onDone = { commit() })"))
        // … and a hardware Enter, which Compose never maps onto that action.
        // Same pair `TabRenameField` / `OcTerminalRenameField` keep.
        assertTrue(input.contains("Key.Enter, Key.NumPadEnter -> {"))
        assertTrue(input.contains("commit()"))
    }

    @Test
    fun escapeCancels() {
        val body = header()
        val input = body.substring(body.indexOf("private fun InlineTitleInput("))
        assertTrue(input.contains("Key.Escape -> {"))
        assertTrue(input.contains("onCancel()"))
        // Blur still saves, which is the upstream contract.
        assertTrue(input.contains("if (state.isFocused) focused = true else if (focused) commit()"))
    }

    @Test
    fun anOutsideTapBlursTheEditor() {
        val body = screen()
        // The screen arms the tap-away on the header's editing state …
        assertTrue(body.contains("onTitleEditing = { titleEditing = it }"))
        assertTrue(body.contains(".blurTitleEditorOnOutsideTap(titleEditing, focusManager, titleEditFence)"))
        // … and the modifier blurs on a press nothing consumed: an interactive
        // target keeps its own press, and a target that takes focus (the
        // composer) blurs the field by itself.
        val helper = body.indexOf("private fun Modifier.blurTitleEditorOnOutsideTap(")
        assertTrue(helper > 0)
        val modifier = body.substring(helper)
        assertTrue(modifier.contains("awaitPointerEvent(PointerEventPass.Final)"))
        assertTrue(modifier.contains("event.changes.none { it.isConsumed }"))
        assertTrue(modifier.contains("focusManager.clearFocus()"))
        // Inert while the editor is closed — it must not steal presses from the
        // timeline / composer the rest of the time.
        assertTrue(modifier.contains("if (!editing) return@pointerInput"))
    }

    @Test
    fun theHeaderPublishesItsEditingState() {
        val body = header()
        assertTrue(body.contains("onTitleEditing: (Boolean) -> Unit = {}"))
        assertTrue(body.contains("LaunchedEffect(editing) { onTitleEditing(editing) }"))
        // …and drops it with the row, so the screen's tap-away cannot outlive
        // the editor.
        assertTrue(body.contains("onDispose { onTitleEditing(false) }"))
    }

    @Test
    fun theCommitStillReachesTheViewModelRename() {
        // The three paths converge on one call: `SessionHeader.onRename` →
        // `SessionScreen`'s optimistic `pendingTitle` + `renameSession`, the
        // same write the ⋮ → Rename dialog performs.
        val body = screen()
        val onRename = body.indexOf("onRename = { newTitle ->")
        assertTrue(onRename > 0)
        val wiring = body.substring(onRename, body.indexOf("onBack = onBack", onRename))
        assertTrue(wiring.contains("pendingTitle = newTitle"))
        assertTrue(wiring.contains("chatViewModel.renameSession(activeSession.id, newTitle)"))
        assertTrue(wiring.contains("if (!ok) pendingTitle = null"))
    }
}
