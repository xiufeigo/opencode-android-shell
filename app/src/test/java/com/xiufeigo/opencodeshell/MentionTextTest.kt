package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.MentionText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1.5 @-mention 规则单测（纯 JVM）：触发锚点、补全插入的幂等/失效判定、
 * 三类着色归类（file/agent/reference）、已发送正文的 token 扫描、候选裁剪。
 */
class MentionTextTest {

    // ------------------------------------------------------------- trigger

    @Test
    fun caretAtZeroHasNoMention() {
        assertNull(MentionText.anchorAt("@", 0))
        assertNull(MentionText.anchorAt("@src", 0))
    }

    @Test
    fun bareTriggerOpensAnEmptyQuery() {
        assertEquals(MentionText.Anchor(0, ""), MentionText.anchorAt("@", 1))
        assertEquals(MentionText.Anchor(4, ""), MentionText.anchorAt("see @", 5))
    }

    @Test
    fun queryIsTheTextBetweenAnchorAndCaret() {
        assertEquals(MentionText.Anchor(4, "src/Ma"), MentionText.anchorAt("see @src/Ma", 11))
    }

    @Test
    fun caretInsideTheTokenUsesTheCaretNotTheEnd() {
        // "see @src/Main.kt" with the caret after "@sr".
        assertEquals(MentionText.Anchor(4, "sr"), MentionText.anchorAt("see @src/Main.kt", 7))
    }

    @Test
    fun whitespaceClosesTheMention() {
        assertNull(MentionText.anchorAt("@src main", 9))
        assertNull(MentionText.anchorAt("@src\nmain", 9))
        assertNull(MentionText.anchorAt("@src\tmain", 9))
    }

    @Test
    fun lastTriggerBeforeTheCaretWins() {
        assertEquals(MentionText.Anchor(3, "b"), MentionText.anchorAt("@a @b", 5))
    }

    @Test
    fun midWordTriggerStillOpensLikeTheViewShell() {
        // ChatView has no "must be preceded by whitespace" rule; keep parity.
        assertEquals(MentionText.Anchor(1, "b"), MentionText.anchorAt("a@b", 3))
    }

    @Test
    fun outOfRangeCaretIsIgnored() {
        assertNull(MentionText.anchorAt("@src", 9))
        assertNull(MentionText.anchorAt("@src", -1))
    }

    // -------------------------------------------------------------- insert

    @Test
    fun insertReplacesThePartialTokenAndAppendsATrailingSpace() {
        val insertion = MentionText.insert("see @src/Ma", anchorStart = 4, caret = 11, path = "src/Main.kt")
        assertEquals("see @src/Main.kt ", insertion?.text)
        assertEquals(17, insertion?.caret)
    }

    @Test
    fun insertKeepsTextAfterTheCaret() {
        val insertion = MentionText.insert("@ab tail", anchorStart = 0, caret = 3, path = "a/b.txt")
        assertEquals("@a/b.txt  tail", insertion?.text)
        assertEquals(9, insertion?.caret)
    }

    @Test
    fun insertRejectsStaleAnchors() {
        // The '@' was deleted / already consumed by an earlier completion.
        assertNull(MentionText.insert("see src/Ma", 4, 10, "src/Main.kt"))
        // The caret moved before the anchor.
        assertNull(MentionText.insert("@src", 3, 1, "src/Main.kt"))
        // A space appeared between the anchor and the caret.
        assertNull(MentionText.insert("@src main", 0, 9, "src/Main.kt"))
        assertNull(MentionText.insert("@src", 0, 99, "src/Main.kt"))
        assertNull(MentionText.insert("@src", -1, 4, "src/Main.kt"))
    }

    // ------------------------------------------------------------ classify

    @Test
    fun fileNamesClassifyAsFile() {
        assertEquals(MentionText.Kind.FILE, MentionText.classify("src/Main.kt"))
        assertEquals(MentionText.Kind.FILE, MentionText.classify("README.md"))
        assertEquals(MentionText.Kind.FILE, MentionText.classify("app/build.gradle.kts"))
    }

    @Test
    fun directoryPathsClassifyAsReference() {
        assertEquals(MentionText.Kind.REFERENCE, MentionText.classify("src/main"))
        assertEquals(MentionText.Kind.REFERENCE, MentionText.classify("src/main/"))
        assertEquals(MentionText.Kind.REFERENCE, MentionText.classify("app/src"))
    }

    @Test
    fun bareNamesClassifyAsAgent() {
        assertEquals(MentionText.Kind.AGENT, MentionText.classify("build"))
        assertEquals(MentionText.Kind.AGENT, MentionText.classify("plan"))
        // A bare directory name is indistinguishable from an agent name; the
        // View shell's rule (no slash/dot → type colour) wins.
        assertEquals(MentionText.Kind.AGENT, MentionText.classify("src"))
    }

    // --------------------------------------------------------------- spans

    @Test
    fun spansFindEveryTokenWithOffsetsAndKinds() {
        val text = "check @src/Main.kt then ask @build and @src/main"
        val spans = MentionText.spans(text)
        assertEquals(3, spans.size)
        assertEquals(
            listOf(MentionText.Kind.FILE, MentionText.Kind.AGENT, MentionText.Kind.REFERENCE),
            spans.map { it.kind },
        )
        for (span in spans) {
            assertTrue(text.substring(span.start, span.end).startsWith("@"))
        }
        assertEquals("@src/Main.kt", text.substring(spans[0].start, spans[0].end))
        assertEquals("@build", text.substring(spans[1].start, spans[1].end))
    }

    @Test
    fun spansIgnoreBareAtSignsAndEmptyText() {
        assertTrue(MentionText.spans("no mentions here email@ x @ y").isEmpty())
        assertTrue(MentionText.spans("").isEmpty())
    }

    @Test
    fun triggerInsideAnInsertedMentionIsClosedByItsTrailingSpace() {
        val insertion = MentionText.insert("@sr", 0, 3, "src/Main.kt")
        val caret = insertion!!.caret
        assertNull("the trailing space closes the mention", MentionText.anchorAt(insertion.text, caret))
        assertEquals(1, MentionText.spans(insertion.text).size)
    }

    // ---------------------------------------------------------- candidates

    @Test
    fun candidatesTrimDropBlanksAndDedupe() {
        val candidates = MentionText.candidates(listOf(" a.kt", "", "a.kt", "b/c.md", "   "))
        assertEquals(listOf("a.kt", "b/c.md"), candidates.map { it.label })
        assertTrue(candidates.all { it.kind == MentionText.Kind.FILE })
    }

    @Test
    fun candidatesAreCappedAtThePopoverLimit() {
        val many = (1..40).map { "f$it.kt" }
        assertEquals(MentionText.LIMIT, MentionText.candidates(many).size)
        assertEquals(3, MentionText.candidates(many, limit = 3).size)
    }
}
