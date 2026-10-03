package com.xiufeigo.opencodeshell

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerImeCommit
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSubmission
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions
import com.xiufeigo.opencodeshell.ui.session.composer.HalfWidthText
import com.xiufeigo.opencodeshell.ui.session.composer.MentionTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v75 「以空白结尾的批次完整落盘」 — the composer's input boundary is
 * **whitespace-lossless**.
 *
 * Device round v75 reported a segment that ended in a space losing it against
 * the next segment (`window ` + `test` → `windowtest`, once in five runs on
 * `emulator-5554`). The measurement on that same device with that same IME
 * (see `research/v75_ime_space/REPORT.md`) found the loss **upstream of the
 * app**: the harness's `adb shell input text 'window '` — a *literal* trailing
 * space in the argument — loses the space before any app code runs (a stock
 * Android `EditText` in Settings loses it identically with this app out of the
 * picture), while the same command with `%s` (the `input` command's own space
 * escape) lands `window ` and the composer keeps it across the segment
 * boundary 10/10 (uiautomator dump verified at every boundary).
 *
 * This suite pins the app's half of that contract, so the "the shell dropped
 * it" half can never become true again:
 *
 * 1. the W5-F fold is 1:1 — it rewrites `／`/`＠`/`U+3000` and nothing else, so
 *    a batch that ends in whitespace still ends in whitespace;
 * 2. the IME boundary ([ComposerImeCommit.adopt]) stores the committed text
 *    verbatim — it may fold a *range* into the text it belongs to, never edit
 *    the text itself — and leaves a legal value reference-identical, so the
 *    common keystroke schedules no recomposition and no `restartInput()`;
 * 3. the write-back's only text-removing rule is the mention-chip rule
 *    ([MentionTokens.snapImeDeletion]), which is token-scoped and fires only on
 *    a one-character deletion — never on an insertion batch;
 * 4. the **submit** boundary hands the editor's own text to the payload
 *    (`submit()` used to send `…normalize(value.text).trim()`, which dropped
 *    exactly the trailing whitespace of the last committed batch); `trim()`
 *    stays on the blank gate and on the `/name` / alias / `/btw` parsers,
 *    which read a shape. Upstream does the same: `request.ts:128` sends
 *    `input.text` itself and trims only for the gate (`submit.ts:200-203`).
 */
class ComposerWhitespaceBatchV75Test {

    /** Batches an IME can finish a segment with, trailing whitespace included. */
    private val batches = listOf(
        " ",
        "\u3000", // the ideographic space a Chinese IME's space bar commits
        "hello ",
        "hello short window ",
        "hello  ", // two spaces: neither may be folded away
        "a\t",
        "line\n",
        "／x ", // a full-width trigger plus the trailing space
        "＠ ", // ditto for the mention trigger
        "双 空格 ",
    )

    // ------------------------------------------------------- 1. the two folds

    @Test
    fun theEditingFoldNeverDropsATrailingWhitespaceCharacter() {
        for (batch in batches) {
            val folded = HalfWidthText.normalize(batch)
            assertEquals("length survives the fold: ${batch.length}", batch.length, folded.length)
            assertEquals(
                "the tail survives the fold — the ideographic space folds to ' ', no tail is dropped",
                HalfWidthText.halfWidth(batch.last()),
                folded.last(),
            )
        }
        // 1:1 and narrow: only the two trigger characters and U+3000 are rewritten.
        assertEquals("hello short window ", HalfWidthText.normalize("hello short window "))
        assertEquals("/x ", HalfWidthText.normalize("／x "))
        assertEquals("@ ", HalfWidthText.normalize("＠\u3000"))
        assertEquals("a\t", HalfWidthText.normalize("a\t"))
        // …and an untouched line comes back as the very same instance, so the
        // editor's state write stays reference-equal (no extra recomposition).
        val line = "hello short window "
        assertSame(line, HalfWidthText.normalize(line))
    }

    @Test
    fun theComparisonAndNameFoldsAreLengthPreservingToo() {
        for (batch in batches) {
            assertEquals(
                "toAscii keeps every character: $batch",
                batch.length,
                HalfWidthText.toAscii(batch).length,
            )
            assertEquals(
                "normalizeName keeps every character: $batch",
                batch.length,
                HalfWidthText.normalizeName(batch).length,
            )
            assertEquals(
                "normalizeName keeps the tail: $batch",
                HalfWidthText.halfWidth(batch.last()),
                HalfWidthText.normalizeName(batch).last(),
            )
        }
        // The comparison rule folds the ideographic space onto ASCII; it never
        // deletes it (`MentionText`'s candidate filter reads this view).
        assertEquals("a ", HalfWidthText.toAscii("a\u3000"))
    }

    // ------------------------------------------------ 2. the IME commit boundary

    @Test
    fun aWhitespaceTerminatedBatchReachesTheEditorTextIntact() {
        val committed = "hello short window "
        val shapes = listOf(
            // A plain commit batch: the caret lands after the trailing space.
            TextFieldValue(committed, TextRange(committed.length)),
            // The IME still owns the whitespace as its composing region.
            TextFieldValue(committed, TextRange(committed.length), TextRange(18, 19)),
            // A degenerate composition at 0 — the shape the caret guard drops.
            TextFieldValue(committed, TextRange(committed.length), TextRange(0, 0)),
            // An offset no editor owns: the guard folds it back, never the text.
            TextFieldValue(committed, TextRange(999), TextRange(7, 20)),
        )
        for (batch in shapes) {
            assertEquals(
                "the committed text is stored verbatim",
                committed,
                ComposerImeCommit.adopt(batch).text,
            )
        }
        // The narrowest batch of all: the IME committed the space and nothing else.
        assertEquals(" ", ComposerImeCommit.adopt(TextFieldValue(" ", TextRange(1))).text)
    }

    @Test
    fun anAlreadyLegalBatchIsNotRebuilt() {
        // Reference-equal answers are the contract: a rebuilt value would be a
        // text write-back to the IME, and Compose answers `oldValue.text !=
        // newValue.text` with `restartInput()` (see [ComposerImeCommit]).
        val legal = TextFieldValue("hello short window ", TextRange(19), TextRange(18, 19))
        assertSame(legal, ComposerImeCommit.adopt(legal))
        val plain = TextFieldValue("hello ", TextRange(6))
        assertSame(plain, ComposerImeCommit.adopt(plain))
    }

    // ------------------------------------------------- 3. the write-back rules

    @Test
    fun theChipRuleNeverTouchesAnInsertionBatch() {
        val token = MentionTokens.Token(
            kind = ComposerSuggestions.Category.FILE,
            label = "general",
            path = "general",
        )
        val old = "hello @general"
        val committed = "hello @general " // the IME's whitespace-ended batch
        assertNull(
            "an insertion batch is never widened into a deletion",
            MentionTokens.snapImeDeletion(old, committed, committed.length, listOf(token)),
        )
        assertNull(
            "no chip, no rule",
            MentionTokens.snapImeDeletion(old, committed, committed.length, emptyList()),
        )
    }

    @Test
    fun theOnlyTextRemovingRuleIsTheChipItself() {
        val token = MentionTokens.Token(
            kind = ComposerSuggestions.Category.FILE,
            label = "general",
            path = "general",
        )
        // The one deliberate removal: a backspace that lands on the chip's own
        // separator space takes the whole `@label ` at once (v68).
        assertEquals(
            MentionTokens.Deletion(start = 6, endExclusive = 15),
            MentionTokens.snapImeDeletion("hello @general ", "hello @general", 14, listOf(token)),
        )
        // Away from the chip the rule is silent, so a plain batch passes through.
        assertNull(MentionTokens.snapImeDeletion("hello ", "hello", 5, listOf(token)))
        assertNull(MentionTokens.snapImeDeletion("hello\u3000", "hello", 5, listOf(token)))
    }

    // ------------------------------------------------------ 4. the submit text

    @Test
    fun theSubmittedTextKeepsTheCommittedWhitespace() {
        val composer = composer()
        assertTrue(
            "the payload text is the editor's own, folded 1:1 and nothing else",
            composer.contains(
                "val outgoing = HalfWidthText.normalize(value.text)\n" +
                    "        val trimmed = outgoing.trim()",
            ),
        )
        assertFalse(
            "the sent text is never trimmed",
            composer.contains("HalfWidthText.normalize(value.text).trim()"),
        )
        assertTrue(
            "the structured payload carries it verbatim",
            composer.contains("ComposerSubmission.build(\n            text = outgoing,"),
        )
        assertTrue(
            "the legacy payload does too",
            composer.contains("text = outgoing,"),
        )
        assertTrue("the M1.5 fallback too", composer.contains("onSend(outgoing, attachments)"))
        assertTrue("history records the submit", composer.contains("history.add(outgoing, shellMode)"))
    }

    @Test
    fun theGateAndTheParsersStillReadTheTrimmedShape() {
        val composer = composer()
        assertTrue(composer.contains("ComposerSubmit.canSubmit(trimmed, attachments.size, shellMode)"))
        assertTrue(composer.contains("ComposerSuggestions.invocation(trimmed, known)"))
        assertTrue(composer.contains("ComposerSuggestions.aliasCommandId(trimmed,"))
        assertTrue(composer.contains("ComposerSuggestions.btwQuestion(trimmed)"))
    }

    @Test
    fun thePayloadModelCarriesTheWhitespaceBatch() {
        // The model the view model posts: no normalization of its own.
        assertEquals(
            "hello short window ",
            ComposerSubmission.build(text = "hello short window ").text,
        )
        // A Chinese IME's ideographic space is folded to its half-width twin,
        // not dropped.
        assertEquals("hello ", ComposerSubmission.build(text = HalfWidthText.normalize("hello\u3000")).text)
    }

    private fun composer(): String =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")
}
