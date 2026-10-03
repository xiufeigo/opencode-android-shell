package com.xiufeigo.opencodeshell

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerImeCommit
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions
import com.xiufeigo.opencodeshell.ui.session.composer.HalfWidthText
import com.xiufeigo.opencodeshell.ui.session.composer.MentionText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v30 BUG-2 — the composer's slash state machine across an IME/paste commit.
 *
 * The device repro (`research/v30_verify/tB_ui_spot/tB_12b_bug_bulktext_slash_lost.png`):
 * `/` in the composer, then the whole string `zzzzz` injected as **one bulk
 * edit** (the harness's `input text` — the equivalent of a paste), and the `/`
 * was gone with the command popover closed. Per-character input is fine
 * (`tB_12c_slash_empty_ok.png`: `/zzzzz` + the `No matching commands` card).
 *
 * Two halves are pinned here, matching the two halves of the composer:
 *
 * 1. **the state machine** — the slash query and the popover are a pure
 *    function of the editor *text*, so a bulk commit that leaves `/zzzzz` must
 *    land on the empty-state popover (the acceptance criterion), and every
 *    other commit shape must derive the same state the per-keystroke path
 *    would. This half is pure ([ComposerSuggestions.commandQuery] /
 *    [MentionText.anchorAt] / [ComposerSuggestions.commandItems]).
 * 2. **the IME boundary** ([ComposerImeCommit]) — the editor must not rewrite
 *    text the IME still owns a composing region for: Compose answers that
 *    rewrite with `InputMethodManager.restartInput()`, which resets the IME's
 *    session while the app's buffer has already committed the composing region,
 *    and the IME's next "replace my composing word" edit then deletes a
 *    character the app owns. Pinned as pure logic plus the composer wiring.
 */
class ComposerImeCommitTest {

    private fun command(name: String, description: String = "") = OpenCodeClient.CommandInfo(
        name = name,
        description = description,
        source = "command",
        template = "",
    )

    private fun composer(): String = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    /**
     * The composer's popover state for an editor value — the same derivation
     * `Composer.kt` performs on every composition (`live = triggerAt(text,
     * caret)`; `slashQuery`/`anchor` are `live`'s two halves, pinned by
     * `SuggestionKeybindTest`).
     */
    private class State(text: String, commands: List<OpenCodeClient.CommandInfo>) {
        val slashQuery: String? = ComposerSuggestions.commandQuery(text)
        val anchor: MentionText.Anchor? = MentionText.anchorAt(text, text.length)
        val items = slashQuery?.let { ComposerSuggestions.commandItems(it, commands) } ?: emptyList()
        val popoverOpen = items.isNotEmpty()
        val popoverVisible = popoverOpen || slashQuery != null || anchor != null
    }

    // ------------------------------------------------------ the acceptance case

    @Test
    fun aBulkCommitOntoTheSlashKeepsTheEmptyStatePopover() {
        val commands = listOf(command("init", "guided AGENTS.md setup"), command("review"))
        // What the repro should end with: the whole line, slash included.
        val state = State("/zzzzz", commands)
        assertEquals("zzzzz", state.slashQuery)
        assertEquals("no command matches", emptyList<ComposerSuggestions.Item>(), state.items)
        assertFalse("no rows, so the empty state is what the card shows", state.popoverOpen)
        assertTrue("the card stays mounted on the trigger alone", state.popoverVisible)
        // …and the neighbouring shapes, so the state is the *text's* state and
        // not a leftover of how the text arrived.
        assertEquals("", State("/", commands).slashQuery)
        assertEquals(3, State("/", emptyList()).items.size)
        assertNull("a completed command line closes the menu", State("/help foo", commands).slashQuery)
        assertNull("plain text is not a command position", State("zzzzz", commands).slashQuery)
    }

    @Test
    fun thePastePathRebuildsTheQueryFromTheWholeText() {
        // `insertText` (the 「+」menu 粘贴 row, `Ctrl+V`, and the text half of a
        // clipboard paste) splices the chunk at the caret; the open shape is not
        // mirrored anymore — it is re-read from the spliced value on the next
        // composition ([live], v67: the mirror this test used to pin could drift
        // from the caret the editor actually owned). Pinned at the source
        // because the splice lives inside the composable.
        val composer = composer()
        val insert = composer.indexOf("fun insertText(text: String)")
        assertTrue("insertText is the paste path", insert >= 0)
        assertTrue(
            "the chunk is folded before it is spliced",
            composer.contains("val chunk = HalfWidthText.normalize(text)"),
        )
        assertTrue(
            "the splice is a plain write-back; the trigger re-derives from it",
            composer.contains("val next = TextFieldValue(inserted, TextRange(caret + chunk.length))"),
        )
        assertFalse(
            "no mirrored slash query can drift from the editor text",
            composer.contains("var slashQuery by remember"),
        )
        assertFalse(
            "no mirrored mention anchor can drift from the editor caret",
            composer.contains("var trigger by remember"),
        )
        // The single source both surfaces render from:
        assertTrue(
            composer.contains("val live = ComposerSuggestions.triggerAt(value.text, value.selection.end)"),
        )
        // The splice itself, for the two shapes a paste can take: the pasted
        // text becomes the whole line, or it lands after a typed `/`.
        fun paste(existing: String, chunk: String): String = existing + chunk
        assertEquals("/help foo", paste("/", "help foo"))
        assertEquals("zzzzz", State(paste("/", "zzzzz"), emptyList()).slashQuery)
        assertEquals(0, State(paste("/", "zzzzz"), emptyList()).items.size)
    }

    // -------------------------------------------------------- the IME boundary

    @Test
    fun theEditorNeverRewritesImeText() {
        // v64: the committed case was the one left standing, and it is the one
        // that locks the composer. Compose answers any app-side text rewrite of
        // a value the IME owns with `restartInput()`, which re-initialises the
        // IME at offset 0 — measured on `emulator-5556` as `/` + `t` (swallowed)
        // + `r` = `rm/` (`research/v64_verify/5556/02_24…05_27`) and on
        // `emulator-5558` as `@de` surviving five `KEYCODE_DEL` and a
        // select-all + delete (`5558/01b*`, `21`). So the editor stores the
        // IME's own characters, composing or not, and the fold is a read view.
        val composing = TextFieldValue("／", TextRange(1), TextRange(0, 1))
        val committed = TextFieldValue("／", TextRange(1))
        assertEquals("／", composing.text)
        assertEquals("／", committed.text)
        // …and the read view is what the trigger logic sees, in both shapes.
        assertEquals("/", ComposerImeCommit.readable(composing.text))
        assertEquals("/", ComposerImeCommit.readable(committed.text))
        assertEquals("@src", ComposerImeCommit.readable("＠src"))
        // Reference-preserving for text with no full-width syntax in it, so an
        // ordinary keystroke reads as the same instance.
        val plain = "hello"
        assertSame(plain, ComposerImeCommit.readable(plain))
    }

    /**
     * The read view is 1:1, so every index it yields also addresses the stored
     * (unfolded) text — the anchor the popover opens on still splices correctly,
     * and the caret the IME reported is the caret the anchor is measured from.
     */
    @Test
    fun theReadViewIsOneToOneSoTheAnchorStillAddressesTheStoredText() {
        for (stored in listOf("／init", "＠src/Main.kt", "a／b＠c", "plain")) {
            val readable = ComposerImeCommit.readable(stored)
            assertEquals(stored.length, readable.length)
            for (caret in 0..stored.length) {
                assertEquals(
                    MentionText.anchorAt(HalfWidthText.normalize(stored), caret),
                    MentionText.anchorAt(readable, caret),
                )
                assertEquals(
                    ComposerSuggestions.commandQuery(HalfWidthText.normalize(stored)),
                    ComposerSuggestions.commandQuery(readable),
                )
            }
        }
    }

    @Test
    fun theTriggersAreDerivedFromTheFoldedTextInBothCases() {
        // The guard changes what the editor *stores*, never what the popover
        // *reads*: `commandQuery` folds through `toAscii` and the 1:1 fold keeps
        // the anchor's indices valid in the stored (unfolded) text.
        assertEquals("", ComposerSuggestions.commandQuery("／"))
        assertEquals("zzzzz", ComposerSuggestions.commandQuery("／zzzzz"))
        // The `@` detector itself stays ASCII (`MentionText` is the M1.5
        // contract) — the read view is what turns the IME's `＠` into a trigger.
        assertNull(MentionText.anchorAt("＠src", 4))
        assertEquals(MentionText.Anchor(start = 0, query = "src"), MentionText.anchorAt("@src", 4))
        assertEquals(
            MentionText.Anchor(start = 0, query = "src"),
            MentionText.anchorAt(ComposerImeCommit.readable("＠src"), 4),
        )
        // And the accept folds the stored value before it checks the trigger —
        // otherwise a composing `＠` would silently swallow the tap.
        val stored = "＠src"
        val anchor = MentionText.anchorAt(ComposerImeCommit.readable(stored), stored.length)!!
        assertEquals(0, anchor.start)
        val insertion = MentionText.insert(
            HalfWidthText.normalize(stored),
            anchor.start,
            stored.length,
            "src/Main.kt",
        )
        assertEquals("@src/Main.kt ", insertion?.text)
        assertNull(
            "the unfolded value is what the ASCII-trigger check rejects",
            MentionText.insert(stored, anchor.start, stored.length, "src/Main.kt"),
        )
    }

    @Test
    fun theComposerStoresTheImeValueAndDerivesTheTriggersFromTheReadView() {
        val composer = composer()
        val read = composer.indexOf("val readable = ComposerImeCommit.readable(input.text)")
        assertTrue("onValueChange builds the folded read view", read >= 0)
        // The stored value is the IME's own — the v64 BUG-1/2 guard.
        assertTrue("the editor stores the IME value", composer.contains("setText(input)"))
        assertFalse(
            "the value must never be rewritten on the editor write path",
            composer.contains("HalfWidthText.normalize(input)"),
        )
        assertFalse(
            "the old composing-regime fold guard is gone",
            composer.contains("if (ComposerImeCommit.folds(input))"),
        )
        assertTrue(
            "both triggers are derived live from the editor value, not mirrored in the write path",
            composer.contains("val live = ComposerSuggestions.triggerAt(value.text, value.selection.end)"),
        )
        assertFalse(
            "the `/` query is no longer mirrored in the write path",
            composer.contains("ComposerSuggestions.commandQuery(readable)"),
        )
        assertFalse(
            "the `@` anchor is no longer mirrored in the write path",
            composer.contains("MentionText.anchorAt(readable, input.selection.end)"),
        )
        // The mention accept folds the stored value (see the test above).
        assertTrue(
            composer.contains("HalfWidthText.normalize(value.text)"),
        )
    }

    /**
     * v64 BUG-2: the panel is open, and every keystroke after the trigger still
     * lands in the query string. The device read `rm/` for `/` + `t` + `r` — the
     * `t` was swallowed by the restart and the `r` went in at offset 0. With no
     * rewrite there is no restart, so the derivation is simply the text's.
     */
    @Test
    fun aTriggerOpensThePanelAndTheNextKeystrokesJoinTheQuery() {
        val commands = listOf(command("init"), command("review"))
        var text = ""
        var caret = 0
        val queries = mutableListOf<String?>()
        fun type(chunk: String) {
            text = text.substring(0, caret) + chunk + text.substring(caret)
            caret += chunk.length
            queries += ComposerSuggestions.commandQuery(ComposerImeCommit.readable(text))
        }
        // A Chinese IME's `/` is the shape that used to restart the input.
        type("／")
        type("t")
        type("r")
        assertEquals(listOf("", "t", "tr"), queries)
        // The line holds the IME's own characters — the panel reads the fold,
        // the editor never rewrites.
        assertEquals("／tr", text)
        assertEquals(3, text.length)
        // The empty-state card is still what an unmatched query shows.
        assertEquals("zzzzz", ComposerSuggestions.commandQuery(ComposerImeCommit.readable("／zzzzz")))
        assertEquals(0, State(ComposerImeCommit.readable("／zzzzz"), commands).items.size)
        assertEquals(5, State(ComposerImeCommit.readable("／"), commands).items.size)
    }
}
