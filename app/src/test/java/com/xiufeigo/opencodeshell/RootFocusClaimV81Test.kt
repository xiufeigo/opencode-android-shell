package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.rootClaimsFocus
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v81 P1 「重开会话后编辑器失焦需二次点击」: the key-chord root may **claim** the
 * focus, never steal it.
 *
 * `GlobalKeybindFocus` re-claims the window focus whenever the dialog stack
 * drains, because the palette autofocuses its own search field and after closing
 * it *nothing* held the focus — every chord was dead until a restart (the F-1
 * verifier case). The re-claim was unconditional, and the root is an ancestor of
 * the whole navigation tree: asking it to focus makes a focused text field
 * inactive, i.e. it takes the caret away and leaves the keyboard up, and the user
 * has to tap the field again to type (`research/v80_verify/5554/26_480_typed.png`
 * → `27_480_recheck.png` → `28_480_refocus.png`). A layer dismissed as a session
 * opens keeps `LocalDialogActive` true for another ~100 ms of exit transition, so
 * that drain lands inside a session's entry window — the reported reopen.
 *
 * [rootClaimsFocus] is the judgement: claim only while a layer is not up **and**
 * nothing below the root holds the focus (the root's `FocusState.hasFocus` covers
 * its whole subtree). The chords keep working either way — the root is in the
 * focus chain of whatever is focused below it, so its `onPreviewKeyEvent` still
 * runs first, and [rootChordYieldsToTextInput] still stands bare keys down
 * while a field is being typed into.
 */
class RootFocusClaimV81Test {

    @Test
    fun `nothing focused and no layer is the state the claim exists for`() {
        // The F-1 case: the palette (and its autofocused search field) is gone, so
        // the root has to take the focus back or every chord stays dead.
        assertTrue(rootClaimsFocus(active = false, subtreeHasFocus = false))
    }

    @Test
    fun `a live caret is never taken away`() {
        // The composer holds the caret (and the IME): the root must not claim.
        assertFalse(rootClaimsFocus(active = false, subtreeHasFocus = true))
        // …whatever else is focused below it (a menu row, a rename box).
        assertFalse(rootClaimsFocus(active = false, subtreeHasFocus = true))
    }

    @Test
    fun `a layer owns the keys while it is up`() {
        assertFalse(rootClaimsFocus(active = true, subtreeHasFocus = false))
        assertFalse(rootClaimsFocus(active = true, subtreeHasFocus = true))
    }

    @Test
    fun `the whole truth table`() {
        assertTrue(rootClaimsFocus(false, false))
        assertFalse(rootClaimsFocus(false, true))
        assertFalse(rootClaimsFocus(true, false))
        assertFalse(rootClaimsFocus(true, true))
    }

    @Test
    fun `the root tracks its own subtree and keys the claim on it`() {
        val app = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
        assertTrue(app.contains("var rootHasFocus by remember { mutableStateOf(false) }"))
        assertTrue(app.contains(".onFocusChanged { rootHasFocus = it.hasFocus }"))
        assertTrue(app.contains("LaunchedEffect(active, rootHasFocus) {"))
        assertTrue(
            app.contains("if (rootClaimsFocus(active = active, subtreeHasFocus = rootHasFocus)) {"),
        )
        // The request itself is still there — the claim is gated, not removed.
        val effect = app.substringAfter("LaunchedEffect(active, rootHasFocus) {")
            .substringBefore("Box(")
        assertTrue(effect.contains("runCatching { focus.requestFocus() }"))
    }

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?.replace("\r\n", "\n")
            ?: error("missing $relative")
}
