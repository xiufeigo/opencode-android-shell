package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions
import com.xiufeigo.opencodeshell.ui.session.composer.MentionText
import com.xiufeigo.opencodeshell.ui.session.composer.MentionTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v68 mention token 删除原子性：`@general` 在 5554/5556/5560 上要连退格 6
 * 次才删净，中间每个 `@gener` 都是非法 mention——丢结构性载荷不说，存活的
 * anchor 还会重开面板。上游 token 是原子 chip，一次退格删整块。
 *
 * 修法：退格落在 token 边界（span 内、span 尾、span 尾随的一格分隔空格）时
 * 整块删除，`@` 跟着走所以删后无存活 anchor、面板不重开。硬件退格走编辑器
 * key 分支，软键盘退格（IME 直接改字，只到 `onValueChange`）走 snap 扩大，
 * 两条路调同一个纯函数。
 */
class MentionTokensAtomicDeleteV68Test {

    private fun token(label: String, kind: ComposerSuggestions.Category = ComposerSuggestions.Category.AGENT) =
        MentionTokens.Token(kind = kind, label = label)

    private fun general() = token("general")

    // ------------------------------------------------------------------ spans

    @Test
    fun spansLocateTheAcceptedToken() {
        val spans = MentionTokens.spans("@general ", listOf(general()))
        assertEquals(listOf(MentionTokens.TokenSpan(0, 8, general())), spans)
    }

    @Test
    fun spansPairDuplicatesInOrder() {
        val tokens = listOf(token("plan"), token("plan"))
        val spans = MentionTokens.spans("@plan @plan ", tokens)
        assertEquals(2, spans.size)
        assertEquals(MentionTokens.TokenSpan(0, 5, token("plan")), spans[0])
        assertEquals(MentionTokens.TokenSpan(6, 11, token("plan")), spans[1])
    }

    @Test
    fun damagedLabelYieldsNoSpan() {
        // 剥落中的 `@gener` 不是合法 mention：没有 span，也就没有守卫对象。
        assertTrue(MentionTokens.spans("@gener ", listOf(general())).isEmpty())
        assertTrue(MentionTokens.spans("@generalfoo ", listOf(general())).isEmpty())
    }

    // --------------------------------------------------------------- backspace

    @Test
    fun chipAndItsSeparatorGoInOnePress() {
        assertEquals(
            MentionTokens.Deletion(0, 9),
            MentionTokens.backspace("@general ", 9, listOf(general())),
        )
    }

    @Test
    fun chipWithoutTrailingSpaceDeletesExactlyTheChip() {
        assertEquals(
            MentionTokens.Deletion(0, 8),
            MentionTokens.backspace("@general", 8, listOf(general())),
        )
    }

    @Test
    fun caretInsideTheChipDeletesTheWholeChip() {
        assertEquals(
            MentionTokens.Deletion(0, 9),
            MentionTokens.backspace("@general ", 4, listOf(general())),
        )
    }

    @Test
    fun midTextDeletionCollapsesTheSingleSeparator() {
        assertEquals(
            MentionTokens.Deletion(3, 12),
            MentionTokens.backspace("hi @general there", 11, listOf(general())),
        )
    }

    @Test
    fun secondChipDeletesWithoutTouchingTheFirst() {
        val tokens = listOf(token("a"), token("b"))
        assertEquals(
            MentionTokens.Deletion(3, 6),
            MentionTokens.backspace("@a @b ", 6, tokens),
        )
        // 紧贴上一块分隔空格的 caret 按 upstream chip 语义删上一整块。
        assertEquals(
            MentionTokens.Deletion(0, 3),
            MentionTokens.backspace("@a @b ", 3, tokens),
        )
    }

    @Test
    fun caretOutsideEveryChipIsUntouched() {
        val tokens = listOf(general())
        assertNull(MentionTokens.backspace("hi @general ", 2, tokens))
        assertNull(MentionTokens.backspace("@general ", 0, tokens))
        assertNull(MentionTokens.backspace("plain ", 6, tokens))
        assertNull(MentionTokens.backspace("@general ", 9, emptyList()))
        assertNull(MentionTokens.backspace("@general ", 0, tokens))
        assertNull(MentionTokens.backspace("@general ", 10, tokens))
    }

    // ------------------------------------------------------- snapImeDeletion

    @Test
    fun imeSpaceDeletionAfterTheChipWidensToTheChip() {
        // Gboard 第一退格先吃分隔空格：扩大为整块，一次删净。
        assertEquals(
            MentionTokens.Deletion(0, 9),
            MentionTokens.snapImeDeletion("@general ", "@general", 8, listOf(general())),
        )
    }

    @Test
    fun imeCharDeletionInsideTheChipWidens() {
        assertEquals(
            MentionTokens.Deletion(0, 9),
            MentionTokens.snapImeDeletion("@general ", "@genera ", 7, listOf(general())),
        )
    }

    @Test
    fun nonChipEditsPassThrough() {
        val tokens = listOf(general())
        // 普通文本删除、插入、多字删除、caret 对不上，一律不动 IME 的值。
        assertNull(MentionTokens.snapImeDeletion("ab @general ", "a @general ", 1, tokens))
        assertNull(MentionTokens.snapImeDeletion("@general", "@general ", 9, tokens))
        assertNull(MentionTokens.snapImeDeletion("@general foo", "@ge foo", 3, tokens))
        assertNull(MentionTokens.snapImeDeletion("@general ", "@genera ", 6, tokens))
        assertNull(MentionTokens.snapImeDeletion("@general ", "@general", 8, emptyList()))
    }

    // ------------------------------------------------------- 删后不断言会断

    @Test
    fun atomicDeletionLeavesNoLiveAnchorSoThePanelStaysShut() {
        val deletion = MentionTokens.backspace("@general ", 9, listOf(general()))!!
        val text = "@general ".removeRange(deletion.start, deletion.endExclusive)
        assertEquals("", text)
        assertNull(MentionText.anchorAt(text, deletion.start))
        assertNull(MentionText.anchorAtEndOfLine(text))
    }

    @Test
    fun atomicDeletionDropsExactlyTheDeletedToken() {
        val tokens = listOf(token("general"), token("plan"))
        val deletion = MentionTokens.backspace("@general @plan ", 9, tokens)!!
        val text = "@general @plan ".removeRange(deletion.start, deletion.endExclusive)
        assertEquals("@plan ", text)
        assertEquals(listOf(token("plan")), MentionTokens.retokenize(text, tokens))
    }

    @Test
    fun theComposerWiresBothBackspacePaths() {
        val composer = source(
            "src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt",
        )
        assertTrue(composer, composer.contains("MentionTokens.backspace(value.text"))
        assertTrue(composer, composer.contains("MentionTokens.snapImeDeletion("))
        // snap 长在 setText 唯一的收口里：硬件分支先删，IME 到值后 snap 扩大，
        // 最后 retokenize 掉已删 token——顺序不能反。
        val setText = composer.indexOf("fun setText(next: TextFieldValue)")
        val snap = composer.indexOf("MentionTokens.snapImeDeletion(", setText)
        val retokenize = composer.indexOf("MentionTokens.retokenize(snapped.text", setText)
        assertTrue("$setText $snap $retokenize", setText in 0 until snap && snap < retokenize)
    }

    private fun source(path: String): String = File(path).readText()
}
