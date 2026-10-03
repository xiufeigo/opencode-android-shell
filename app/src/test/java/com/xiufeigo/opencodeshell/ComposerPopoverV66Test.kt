package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions
import com.xiufeigo.opencodeshell.ui.session.composer.MentionText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v66: the three composer faults the v65 device re-verification filed against
 * 5556 / 5558, and the code each one names.
 *
 * Device evidence this pins:
 * - `research/v65_verify/5558/17_at_clean.png` / `18_at_wait.png` — the editor
 *   holds `v@` with **the caret at offset 0** (the blue caret sits in front of
 *   the `v`), no `@` panel, and every further keystroke landing in front of the
 *   line. Two of the three filed bugs are that one state: a caret at 0 makes
 *   backspace and select-all+delete no-ops, and it makes
 *   [MentionText.anchorAt] answer null, which is the whole `@` surface.
 * - `research/v65_verify/5556/25_cmd_model.png`, `26_cmd_undo.png`,
 *   `33_cmd_btw2.png` — the `/` menu listed its rows and **every pick did
 *   nothing**: no insert, no command, no close.
 * - `research/v65_verify/5558/36_rename_ent_typed.png` — a rename typed in
 *   three adb segments lost one character (`E2ECHK5` + `558-` + `ent` →
 *   `E2ECHK5558-nt`).
 */
class ComposerPopoverV66Test {

    // ------------------------------------------- MentionText.anchorAtEndOfLine

    @Test
    fun aBareAtIsAnAnchor() {
        assertEquals(MentionText.Anchor(0, ""), MentionText.anchorAtEndOfLine("@"))
    }

    @Test
    fun theAnchorIsTheLastAtBeforeTheEnd() {
        assertEquals(MentionText.Anchor(1, ""), MentionText.anchorAtEndOfLine("v@"))
        assertEquals(MentionText.Anchor(1, "src"), MentionText.anchorAtEndOfLine("v@src"))
        assertEquals(MentionText.Anchor(0, "中文"), MentionText.anchorAtEndOfLine("@中文"))
    }

    @Test
    fun whitespaceAfterTheAtClosesTheAnchor() {
        // Upstream's `/(?:^|\s)@([^\s@]*)$/` cannot match past a space either.
        assertNull(MentionText.anchorAtEndOfLine("hello @src "))
        assertNull(MentionText.anchorAtEndOfLine("@a b"))
        assertNull(MentionText.anchorAtEndOfLine("@a\nb"))
    }

    @Test
    fun noTriggerIsNoAnchor() {
        assertNull(MentionText.anchorAtEndOfLine(""))
        assertNull(MentionText.anchorAtEndOfLine("plain text"))
    }

    // ------------------------------------------------- ComposerSuggestions

    @Test
    fun aPinnedCaretNoLongerKillsTheMentionPanel() {
        // The v65 device state: text `v@`, caret 0. `anchorAt` alone answers
        // null here, which is why the panel never came up.
        assertNull(MentionText.anchorAt("v@", caret = 0))
        val trigger = ComposerSuggestions.triggerAt("v@", caret = 0)
        assertEquals(MentionText.Anchor(1, ""), trigger.mention)
        assertTrue(trigger.inMentionPosition)
        assertTrue(ComposerSuggestions.rowsFor(trigger, emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun aRealCaretStillWinsOverTheTextTail() {
        // The caret sits inside the anchor, so its exact span is the answer and
        // the fallback never runs — a mention completed mid-line keeps working.
        assertEquals(
            MentionText.Anchor(1, "src"),
            ComposerSuggestions.triggerAt("v@src/main", caret = 5).mention,
        )
    }

    @Test
    fun theFallbackIsOneToOneWithTheFoldedView() {
        // A Chinese IME's `／` / `＠` reaches the editor folded, so the tail
        // anchor must be found on the ASCII read view.
        assertEquals(
            MentionText.Anchor(0, "b"),
            ComposerSuggestions.triggerAt("＠b", caret = 0).mention,
        )
    }

    @Test
    fun theSlashMenuIsUnaffectedByTheFallback() {
        val trigger = ComposerSuggestions.triggerAt("/trm", caret = 4)
        assertEquals("trm", trigger.slashQuery)
        assertNull(trigger.mention)
    }

    // ------------------------------------------------------------- source

    @Test
    fun theComposerPicksFromTheSameTriggerItDraws() {
        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
        val accept = window(composer, "fun accept(picked: ComposerSuggestions.Item?) {", "fun submit() {")
        // BUG-3: the row list came from `live` while the branch read the two
        // `remember` mirrors, which a composer rebuilt with its draft does not
        // carry — the pick then returned without doing anything.
        assertTrue(accept.contains("val openSlash = live.slashQuery"))
        assertTrue(accept.contains("if (openSlash != null) {"))
        assertTrue(accept.contains("val active = live.mention ?: return"))
        assertTrue(
            "accept() must not branch on the slashQuery mirror",
            !accept.contains("if (slashQuery != null)"),
        )
        assertTrue(
            "accept() must not branch on the trigger mirror",
            !accept.lines().any { it.trim().startsWith("val active = trigger") },
        )
    }

    @Test
    fun aMentionPickFollowsTheAnchorWhenTheCaretIsLost() {
        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
        val accept = window(composer, "fun accept(picked: ComposerSuggestions.Item?) {", "fun submit() {")
        // Without this the insert refuses (`caret < anchorStart`) and the pick
        // is another silent no-op.
        assertTrue(accept.contains("?: (active.start + active.query.length)"))
    }

    @Test
    fun aRestoredDraftOpensWithItsCaretAtTheEnd() {
        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
        // BUG-2: `TextFieldValue(text)` seeds `TextRange.Zero`, i.e. a non-empty
        // line with the caret at 0 — the measured v65 state.
        assertTrue(
            composer.contains("TextFieldValue(restored, TextRange(restored.length))"),
        )
        assertTrue(
            "no bare TextFieldValue(text) initializer may survive",
            !composer.contains("TextFieldValue(HalfWidthText.normalize(ComposerDrafts.text(draftKey)))"),
        )
    }

    @Test
    fun theRenameEditorStopsAskingForFocusOnceItHasIt() {
        val home = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")
        // BUG-4: the ask ran on every relayout (a renaming row relayouts per
        // keystroke), and each repeat re-attaches the InputConnection, which
        // drops the character the IME was holding.
        assertTrue(home.contains("if (focused) return"))
        assertTrue(home.contains(".onGloballyPositioned { if (!focused) askForFocus() }"))
    }

    // ------------------------------------------------------------- helpers

    private fun window(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        require(start >= 0) { "missing $from" }
        val end = src.indexOf(to, start)
        require(end > start) { "missing $to after $from" }
        return src.substring(start, end)
    }

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")
}
