package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.REASONING_MODE_DEFAULT
import com.xiufeigo.opencodeshell.ui.settings.ocSelectOptionSelected
import com.xiufeigo.opencodeshell.ui.settings.reasoningModeOrDefault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v46 真机批：General 页 Model reasoning 下拉折叠态标签为空
 * (`research/v46_verify/tD/tD_63_gen3.png` / `tD_67_reasoning_open.png` /
 * `tD_68_reasoning_set.png`)。
 *
 * 现象：未显式设置时（`reasoningMode=""`，unset → 跟随 preset）折叠态只有
 * 箭头没有当前值文字，展开后 Hidden/Compact/Full 三项没有任何选中标记；
 * 手动选 Full 写入 `reasoningMode=full` 后才正常。两半原因：
 *
 * 1. 空值没有映射到标签 —— 行渲染拿 `""` 去 options 里找，找到 nothing 就
 *    直接把 `""` 当标签画（`SettingsPanels.kt` 的 SELECT 分支）。
 * 2. 选中态永远标不上 —— 所有 `OcSelect` 调用点传的都是 option **label**，
 *    而 `OcSelectList` 拿 option **value** 去比 (`optionValue == value`)，
 *    label 和 id 本来就不相等，所以不止 reasoning 行，每个下拉展开后都是
 *    全不标记。
 *
 * 修法：未显式设置（空/未知串）读成 [REASONING_MODE_DEFAULT]（Hidden），
 * 折叠态就有标签；选中谓词同时比 value 和 label（[ocSelectOptionSelected]），
 * 展开态就标出当前项。都不动调用签名。
 */
class ReasoningModeDefaultTest {

    @Test
    fun theDefaultIsHidden() {
        assertEquals("hidden", REASONING_MODE_DEFAULT)
    }

    @Test
    fun unsetReadsAsHidden() {
        // The v46 symptom: nothing stored yet, the row must still read Hidden.
        assertEquals("hidden", reasoningModeOrDefault(""))
    }

    @Test
    fun nullAndGarbageReadAsHidden() {
        assertEquals("hidden", reasoningModeOrDefault(null))
        // `optString` hands a missing key back as the literal "null", which is
        // not a stop either — it must not leak into the trigger.
        assertEquals("hidden", reasoningModeOrDefault("null"))
        assertEquals("hidden", reasoningModeOrDefault("bogus"))
    }

    @Test
    fun explicitStopsArePreserved() {
        assertEquals("hidden", reasoningModeOrDefault("hidden"))
        assertEquals("compact", reasoningModeOrDefault("compact"))
        assertEquals("full", reasoningModeOrDefault("full"))
    }

    // ---- the selected mark -------------------------------------------------------

    @Test
    fun aLabelMatchMarksTheRow() {
        // What every call site actually hands the list: the resolved label.
        assertTrue(ocSelectOptionSelected("hidden", "Hidden", "Hidden"))
        assertTrue(ocSelectOptionSelected("full", "Full", "Full"))
    }

    @Test
    fun aValueMatchStillMarksTheRow() {
        // A caller passing the raw id keeps working exactly like before.
        assertTrue(ocSelectOptionSelected("full", "Full", "full"))
    }

    @Test
    fun anotherStopDoesNotMarkTheRow() {
        assertFalse(ocSelectOptionSelected("compact", "Compact", "Full"))
    }

    @Test
    fun anUnsetValueMarksNothing() {
        // The v46 open-list symptom at the predicate level: "" matches no
        // stop, so the fix must happen above (the default mapping), not here.
        assertFalse(ocSelectOptionSelected("hidden", "Hidden", ""))
        assertFalse(ocSelectOptionSelected("compact", "Compact", ""))
        assertFalse(ocSelectOptionSelected("full", "Full", ""))
    }

    // ---- the wiring ----------------------------------------------------------------

    private fun file(name: String): String {
        val file = File("src/main/java/com/xiufeigo/opencodeshell/ui/settings/$name")
            .takeIf { it.isFile }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/ui/settings/$name")
        assertTrue("missing source: $name", file.isFile)
        return file.readText()
    }

    @Test
    fun theReasoningRowMapsUnsetToTheDefault() {
        val body = file("SettingsPanels.kt")
        assertTrue(body.contains("reasoningModeOrDefault(stored)"))
        assertTrue(body.contains("\"settings-reasoning-mode\""))
    }

    @Test
    fun theListMarksThroughTheSharedPredicate() {
        val body = file("SettingsComponents.kt")
        assertTrue(body.contains("val selected = ocSelectOptionSelected(optionValue, label, value)"))
    }
}
