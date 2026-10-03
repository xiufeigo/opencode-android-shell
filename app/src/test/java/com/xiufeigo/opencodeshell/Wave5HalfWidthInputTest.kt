package com.xiufeigo.opencodeshell

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
 * W5-F: the full-width (全角) input fix for B1.
 *
 * The device failure this suite exists for (measured on `emulator-5554`,
 * `research/wave5_composer.md` §8): a Chinese IME turns the slash/at keys into
 * `／` (U+FF0F) / `＠` (U+FF20), so the editor never held the ASCII trigger that
 * `ComposerSuggestions.commandQuery` (`^/(\S*)$`) requires — the `/` menu stayed
 * closed even though every W5-C unit test was green. The pure rules were right;
 * the *boundary* was missing. Hence both halves below: the pure fold/matching
 * rules, and the source guard that pins the boundary (the composer must fold
 * before it derives the triggers).
 */
class Wave5HalfWidthInputTest {

    private fun command(name: String, description: String = "", source: String = "command") =
        OpenCodeClient.CommandInfo(
            name = name,
            description = description,
            source = source,
            template = "",
        )

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    // ------------------------------------------------- the B1 regression itself

    @Test
    fun theFullWidthSlashOpensTheCommandMenuExactlyLikeTheAsciiOne() {
        // What the IME actually commits for KEYCODE_SLASH (device dump: U+FF0F).
        assertEquals("", ComposerSuggestions.commandQuery("／"))
        assertEquals("", ComposerSuggestions.commandQuery("/"))

        val commands = listOf(command("init", "guided AGENTS.md setup"), command("review"))
        // `commandItems` takes the *query* (`commandQuery`'s answer), not the raw
        // editor text — so compare the two trigger widths end to end.
        val halfWidth = ComposerSuggestions.commandQuery("/")!!
        val fullWidth = ComposerSuggestions.commandQuery("／")!!
        assertEquals(
            ComposerSuggestions.commandItems(halfWidth, commands),
            ComposerSuggestions.commandItems(fullWidth, commands),
        )
        // …and the popover is non-empty, which is what `popoverOpen` keys on:
        // with an empty server catalog the two built-ins (and the locally-run
        // `/btw`) still have to be there.
        assertEquals(
            listOf("/model", "/agent", "/btw"),
            ComposerSuggestions.commandItems(fullWidth, emptyList()).map { it.label },
        )
    }

    @Test
    fun aFullyFullWidthCommandStillFindsAndSubmitsItsServerCommand() {
        val commands = listOf(command("init", "guided setup"))
        // A user whose IME is in 全角 mode types `／ｉｎｉｔ`; the menu filters and
        // the submit resolves it to the command the server actually lists.
        val query = ComposerSuggestions.commandQuery("／ｉｎｉｔ")!!
        assertEquals("init", query)
        assertEquals(
            listOf("/init"),
            ComposerSuggestions.commandItems(query, commands).map { it.label },
        )
        // U+3000 (the IME's space) separates name and arguments like an ASCII space.
        assertEquals(
            ComposerSuggestions.Invocation("init", "focus on tests"),
            ComposerSuggestions.invocation("／ｉｎｉｔ　focus on tests", setOf("init")),
        )
        assertEquals(
            ComposerSuggestions.Invocation("init", ""),
            ComposerSuggestions.invocation("／init", setOf("init")),
        )
        // The built-ins are not server commands, full-width or not.
        assertNull(ComposerSuggestions.invocation("／ｍｏｄｅｌ", setOf("init")))
        assertNull(ComposerSuggestions.invocation("／nope", setOf("init")))
    }

    @Test
    fun fullWidthMentionsAndFiltersBehaveLikeHalfWidthOnes() {
        // `@` is the other trigger the composer keys on — same device failure, and
        // the same fix: the detector itself stays ASCII (`MentionText` is the M1.5
        // contract), the composer folds the value before it ever sees it.
        assertEquals("@src", HalfWidthText.normalize("＠src"))
        assertEquals(MentionText.Anchor(start = 0, query = "src"), MentionText.anchorAt("@src", 4))
        assertNull("the detector itself is half-width only — the fold is what makes `＠` work", MentionText.anchorAt("＠src", 4))
        assertTrue(ComposerSuggestions.matches("ＭＯＤＥＬ", "model"))
        assertTrue(ComposerSuggestions.matches("ｐｌａｎ", "plan mode"))
        assertFalse(ComposerSuggestions.matches("ｐｌａｎ", "build mode"))
        assertTrue(ComposerSuggestions.matches("／", "/model"))
    }

    // ----------------------------------------------------- the editing fold

    @Test
    fun theEditingFoldRewritesTheSyntaxCharacters() {
        assertEquals("/", HalfWidthText.normalize("／"))
        assertEquals("@", HalfWidthText.normalize("＠"))
        assertEquals("a b", HalfWidthText.normalize("a\u3000b"))
        assertEquals("请用/命令@文件，再@一次", HalfWidthText.normalize("请用／命令＠文件，再@一次"))
        // 1:1 and length-preserving, which is what keeps the caret valid.
        val mixed = "／init ＠src／main.kt"
        assertEquals(mixed.length, HalfWidthText.normalize(mixed).length)
    }

    @Test
    fun halfWidthInputAndPureChineseProseAreUntouched() {
        // Half-width: nothing to do, and the very same instance comes back
        // (no state write, no extra recomposition).
        for (ascii in listOf("", "/", "@", "/init focus on tests", "plain english prompt 123")) {
            assertSame(ascii, HalfWidthText.normalize(ascii))
        }
        // Pure Chinese, CJK punctuation included: the composer does not rewrite
        // the user's prose — only the three characters it reads as syntax.
        for (prose in listOf("帮我看一下这个文件", "帮我看一下这个文件，谢谢！", "这个、那个。")) {
            assertSame(prose, HalfWidthText.normalize(prose))
            assertFalse("prose is never a shell trigger", HalfWidthText.isShellTrigger(prose))
        }
    }

    @Test
    fun theComparisonFoldIsWiderThanTheEditingFoldAndIsNeverWrittenBack() {
        // `，` is the full-width twin of `,`: the *lookup* fold may translate it,
        // but the editing fold must leave the user's punctuation alone.
        assertEquals(",", HalfWidthText.toAscii("，"))
        assertEquals("，", HalfWidthText.normalize("，"))
        // `。` (U+3002) has no half-width twin and stays in both rules.
        assertEquals("。", HalfWidthText.toAscii("。"))
        assertEquals("model", HalfWidthText.toAscii("ｍｏｄｅｌ"))
        assertEquals("init 123 ABC", HalfWidthText.toAscii("ｉｎｉｔ　１２３　ＡＢＣ"))
        assertSame("plain", HalfWidthText.toAscii("plain"))
    }

    @Test
    fun theEditorValueFoldKeepsTheCaretAndTheComposingRegion() {
        val fold = HalfWidthText.normalize(TextFieldValue("／init", TextRange(5), TextRange(0, 5)))
        assertEquals("/init", fold.text)
        assertEquals(TextRange(5), fold.selection)
        assertEquals(TextRange(0, 5), fold.composition)

        val plain = TextFieldValue("plain", TextRange(3))
        assertSame(plain, HalfWidthText.normalize(plain))
    }

    @Test
    fun theShellTriggerAcceptsBothWidthsButNeverProse() {
        assertTrue(HalfWidthText.isShellTrigger("!"))
        assertTrue(HalfWidthText.isShellTrigger("！"))
        assertFalse(HalfWidthText.isShellTrigger(""))
        assertFalse(HalfWidthText.isShellTrigger("!!"))
        assertFalse(HalfWidthText.isShellTrigger("! "))
        assertFalse(HalfWidthText.isShellTrigger("太好了！"))
    }

    // -------------------------------------------------- the boundary (B1's root)

    @Test
    fun theComposerFoldsEveryTextEntryPointBeforeItReadsTheTriggers() {
        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

        // The guarded ordering: the IME value is folded into a *read view* first,
        // and the `/` query / `@` anchor are derived from that view. This is the
        // exact shape whose absence kept the menu closed on the device.
        //
        // v64: the fold is a read, never a write-back — folding the stored value
        // is what made Compose `restartInput()` and parked the caret at 0
        // (`research/v64_verify/5556/02_24…05_27`, `5558/01b*`, `21`).
        val fold = composer.indexOf("val readable = ComposerImeCommit.readable(input.text)")
        assertTrue("onValueChange folds the IME value for reading", fold >= 0)
        // v67: the trigger derivation moved wholesale into
        // `ComposerSuggestions.triggerAt`, which folds first and then reads the
        // query/anchor off the folded view; the editor-side `live` is the single
        // source (the old `trigger`/`slashQuery` mirrors are gone).
        val suggestions = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ComposerSuggestions.kt")
        val sFold = suggestions.indexOf("val ascii = HalfWidthText.toAscii(text)")
        val sQuery = suggestions.indexOf("slashQuery = commandQuery(ascii)")
        val sAnchor = suggestions.indexOf("MentionText.anchorAt(ascii, caret)")
        assertTrue("triggerAt folds the text before it reads", sFold >= 0)
        assertTrue("the `/` query is derived from the folded value", sQuery > sFold)
        assertTrue("the `@` anchor is derived from the folded value", sAnchor > sFold)
        assertTrue(
            "the editor reads triggers through the single folded source",
            composer.contains("ComposerSuggestions.triggerAt(value.text"),
        )
        assertTrue("the IME's own value is what the editor stores", composer.contains("setText(input)"))
        assertFalse(
            "the stored value is never rewritten",
            composer.contains("HalfWidthText.normalize(input)"),
        )

        // Every other way text enters: the `+`/paste insert, the draft restore,
        // the history recall and the outgoing payload.
        assertTrue("inserts are folded", composer.contains("val chunk = HalfWidthText.normalize(text)"))
        assertTrue("a restored draft is folded", composer.contains("HalfWidthText.normalize(restored)"))
        assertTrue("a recalled history entry is folded", composer.contains("val text = HalfWidthText.normalize(recalled)"))
        assertTrue("the submitted text is folded", composer.contains("val outgoing = HalfWidthText.normalize(value.text)"))
        // v75: the fold is 1:1 and the payload keeps the whitespace the IME
        // committed; only the blank gate / the `/name` parsers read the trimmed
        // shape (upstream `request.ts:128` sends `input.text` itself).
        assertTrue("the gate trims, the payload does not", composer.contains("val trimmed = outgoing.trim()"))
        assertTrue(
            "a whitespace-terminated batch is not edited out of the payload",
            composer.contains("ComposerSubmission.build(\n            text = outgoing,"),
        )
        assertTrue("the shell trigger accepts the IME's `！`", composer.contains("HalfWidthText.isShellTrigger(readable)"))
    }
}
