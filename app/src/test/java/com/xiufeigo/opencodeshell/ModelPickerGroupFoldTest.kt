package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ModelPickerItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v58 (`research/v58_verify/5556/26_ocg.png` `30_ocg3.png` `42_ocg4.png`): 点
 * 「OpenCode Go」三次都没反应 —— 它是**分组头**，一行都没有 clickable，和同列的
 * `DeepSeek V4 Pro` / `V4 Flash` / `Space Bunny Free` / `Use server default` 长得
 * 一样、放在同一个列表里，于是读起来就是一行点不动的死行。
 *
 * 折叠/展开是 provider 头唯一能诚实做的动作（provider 不是 model，发一个没有
 * model id 的 `POST /api/session/{id}/model` 会把会话的模型弄丢），所以接头变成
 * 控件：折叠集合的规则纯在这里，UI 只照着画。
 */
class ModelPickerGroupFoldTest {

    @Test
    fun nothingIsFoldedToStartWith() {
        assertFalse(ModelPickerItems.isCollapsed(emptySet(), "opencode-go"))
        assertTrue(
            "the first tap folds rather than unfolds",
            ModelPickerItems.isCollapsed(
                ModelPickerItems.toggleCollapsed(emptySet(), "opencode-go"),
                "opencode-go",
            ),
        )
    }

    @Test
    fun aTapFoldsTheGroupAndTheNextTapUnfoldsIt() {
        val folded = ModelPickerItems.toggleCollapsed(emptySet(), "opencode-go")
        assertTrue(ModelPickerItems.isCollapsed(folded, "opencode-go"))
        val unfolded = ModelPickerItems.toggleCollapsed(folded, "opencode-go")
        assertFalse("the second tap has to undo the first", ModelPickerItems.isCollapsed(unfolded, "opencode-go"))
        assertEquals(emptySet<String>(), unfolded)
    }

    @Test
    fun foldingOneGroupLeavesTheOthersAlone() {
        val folded = ModelPickerItems.toggleCollapsed(emptySet(), "opencode-go")
        assertFalse(ModelPickerItems.isCollapsed(folded, "opencode"))
        val both = ModelPickerItems.toggleCollapsed(folded, "anthropic")
        assertEquals(setOf("opencode-go", "anthropic"), both)
    }

    @Test
    fun anUnrelatedProviderIdIsNeverFolded() {
        val folded = ModelPickerItems.toggleCollapsed(emptySet(), "opencode-go")
        assertFalse(ModelPickerItems.isCollapsed(folded, "opencode-go-faster"))
    }

    @Test
    fun theGroupHeaderIsARealControlAndTheFoldDecidesTheRows() {
        val source = File("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelPicker.kt")
            .takeIf { it.isFile }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelPicker.kt")
        assertTrue("missing ModelPicker.kt", source.isFile)
        val text = source.readText()
        // The header carries a click target of its own...
        assertTrue(
            "the header row answers taps",
            text.contains("ModelPickerItems.toggleCollapsed("),
        )
        // ...declared before the padding, so the whole band is the target and not
        // just the label's own pixels.
        val header = text.substringAfter("item(key = \"provider:\${group.providerID}\")")
            .substringBefore("items(")
        val clickAt = header.indexOf(".clickable(")
        val padAt = header.indexOf(".padding(")
        assertTrue("the header has a click target", clickAt >= 0)
        assertTrue("the click target is declared before the padding", clickAt < padAt)
        // A folded group draws no model rows.
        assertTrue(
            "a folded group hides its rows",
            text.contains("if (!ModelPickerItems.isCollapsed(collapsed, group.providerID)) {"),
        )
    }
}
