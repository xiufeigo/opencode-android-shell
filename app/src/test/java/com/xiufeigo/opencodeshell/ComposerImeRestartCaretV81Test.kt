package com.xiufeigo.opencodeshell

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerImeCommit
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v81 P1 「翻转后草稿头部游离字符」: an IME **re-attach** batch must not park a
 * non-empty line's caret in front of its text.
 *
 * The repro (`research/v80_verify/5554/29_480_pre_rot.png` → `30_480_landscape.png`,
 * 720x1280 @480dpi): the draft holds `STRAYCHK`, the device is rotated, and the
 * head of the line comes back as `RSTRAYCHK`. The character is the IME's — a
 * connection re-created around a rotation replays what it was holding — but what
 * puts it at the *head* is the app: the platform re-initialises the IME at offset
 * 0 (`GoogleInputMethodService.onStartInput … initialSelStart=0`, the logcat line
 * v63 already names), and the batch it sends back carries an **opened-but-empty
 * composing region at 0** — the phantom `ComposerCaretAdoptV63Test` drops — with
 * the caret at the head. `adopt` accepted that caret because it is *legal*: a
 * caret at 0 in a non-empty line is exactly the state the v63 guard was written
 * about (there, every later edit was applied at 0 and the field read as "locked"),
 * and it is also what makes the next IME commit land at the head of the draft.
 *
 * The rule added here is the smallest one that refuses the park: when the batch is
 * the re-attach fingerprint **and** the caret it carries is the head of a
 * non-empty line, the app keeps the caret it already had (folded into the text the
 * batch carried) — and only when that caret was not itself at the head, so a caret
 * the user put there is never moved. Everything else is v63's contract: a legal
 * value comes back as the same instance, an illegal range is folded, and the
 * phantom composition is dropped.
 */
class ComposerImeRestartCaretV81Test {

    /** The re-attach batch: an empty composing region opened at 0, caret at 0. */
    private fun restartBatch(text: String) =
        TextFieldValue(text, TextRange(0), TextRange(0, 0))

    // ---------------------------------------------------- the reported shape

    @Test
    fun `the restart batch cannot park a non-empty line's caret at the head`() {
        val previous = TextFieldValue("STRAYCHK", TextRange(8))
        val adopted = ComposerImeCommit.adopt(restartBatch("STRAYCHK"), previous = previous)
        assertEquals("STRAYCHK", adopted.text)
        assertEquals("the app's own caret is kept", TextRange(8), adopted.selection)
        assertNull("the phantom composition is still dropped", adopted.composition)
    }

    @Test
    fun `a replayed character lands after the caret the app held, not at the head`() {
        // The stray-char shape: the IME's batch carries the line *plus* a
        // character, with the head-parked caret. The caret must not follow it to
        // the head — that is the position the reported `RSTRAYCHK` was typed into.
        val previous = TextFieldValue("STRAYCHK", TextRange(8))
        val adopted = ComposerImeCommit.adopt(restartBatch("RSTRAYCHK"), previous = previous)
        assertEquals("RSTRAYCHK", adopted.text)
        assertEquals(TextRange(8), adopted.selection)
        assertNull(adopted.composition)
    }

    @Test
    fun `the kept caret is folded into the text the batch carried`() {
        // A caret the app held mid-line stays mid-line, and one past the new
        // text's end is folded back into it — `adopt`'s legality contract.
        assertEquals(
            TextRange(4),
            ComposerImeCommit.adopt(
                restartBatch("hello world!"),
                previous = TextFieldValue("hello world", TextRange(4)),
            ).selection,
        )
        assertEquals(
            "a caret past the new text's end is folded back into it",
            TextRange(3),
            ComposerImeCommit.adopt(
                restartBatch("abc"),
                previous = TextFieldValue("abcde", TextRange(5)),
            ).selection,
        )
    }

    // ------------------------------------------------ what must not change

    @Test
    fun `a caret the user put at the head is never moved`() {
        // The user tapped in front of the line: the app's caret is 0, so there is
        // nothing to protect it from and no park to refuse. The phantom is still
        // dropped (v63).
        val adopted = ComposerImeCommit.adopt(
            restartBatch("STRAYCHK"),
            previous = TextFieldValue("STRAYCHK", TextRange(0)),
        )
        assertEquals(TextRange(0), adopted.selection)
        assertNull(adopted.composition)
    }

    @Test
    fun `an emptied line has no head to park at`() {
        // A send's clear (and the draft-key reset) is the same shape on an empty
        // line: nothing to keep, nothing to fold.
        val adopted = ComposerImeCommit.adopt(
            restartBatch(""),
            previous = TextFieldValue("STRAYCHK", TextRange(8)),
        )
        assertEquals("", adopted.text)
        assertEquals(TextRange(0), adopted.selection)
        assertNull(adopted.composition)
    }

    @Test
    fun `a live composing region is not the fingerprint`() {
        // The IME owning text (a pinyin/word composition) is a normal edit: it is
        // the *collapsed* region at 0 that only a re-attach produces.
        val live = TextFieldValue("ab", TextRange(2), TextRange(0, 2))
        assertSame(live, ComposerImeCommit.adopt(live, previous = TextFieldValue("ab", TextRange(2))))
        val middle = TextFieldValue("aXb", TextRange(2), TextRange(1, 2))
        assertSame(middle, ComposerImeCommit.adopt(middle, previous = TextFieldValue("ab", TextRange(2))))
    }

    @Test
    fun `a legal value still comes back as the same instance`() {
        val value = TextFieldValue("hello", TextRange(5))
        assertSame(value, ComposerImeCommit.adopt(value))
        assertSame(value, ComposerImeCommit.adopt(value, previous = TextFieldValue("hell", TextRange(4))))
    }

    @Test
    fun `without the value the app held the v63 contract is unchanged`() {
        // The single-argument call is the v63 shape (and what the Home rename
        // field uses): a head caret stays where the IME put it, the phantom goes.
        val adopted = ComposerImeCommit.adopt(restartBatch("STRAYCHK"))
        assertEquals(TextRange(0), adopted.selection)
        assertNull(adopted.composition)
        assertSame(
            "a value with no phantom is reference-preserving",
            adopted,
            ComposerImeCommit.adopt(adopted),
        )
    }

    // ----------------------------------------------------------- the wiring

    @Test
    fun `the composer hands the guard the value it was holding`() {
        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
        assertTrue(composer.contains("fun adopt(input: TextFieldValue, previous: TextFieldValue? = null)"))
        val setText = composer.substringAfter("fun setText(next: TextFieldValue) {")
            .substringBefore("fun editQueued")
        assertTrue(setText.contains("ComposerImeCommit.adopt(next, previous = value)"))
        // The other two writes the composer owns hand it over too: the restore
        // adopt (a failed send / a switch) and the submit clear.
        assertTrue(composer.contains("previous = value,"))
        assertTrue(composer.contains("value = ComposerImeCommit.adopt(TextFieldValue(\"\"), previous = value)"))
    }

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?.replace("\r\n", "\n")
            ?: error("missing $relative")
}
