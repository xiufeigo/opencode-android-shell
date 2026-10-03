package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ModelPickerItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v69 桶 B：「模型变体跨会话泄漏」（5558，`research/v68_verify/5558/45_b_low.png`
 * → `46_A_model.png` → `48_A_after_switch.png`）：会话 B 里 `/model` 把变体改成
 * `low`，切回会话 A，**A 的 chip 也写着 low** —— 连同 `low` 一起被写回 A 的
 * 会话（`selectedModelPin` → `effectiveVariant`）。
 *
 * 模型早就按会话隔离了（`sessionPicks` / `recentModel`，
 * [SessionModelScopeTest] 钉死）：HANDOFF 的「模型作用域（会话模型不被默认盖/
 * 不掉包）」靠的就是那张表。变体却没有 —— `ChatViewModel._selectedVariant` 是
 * 一条**进程级**的 `StateFlow`，`selectVariant` 只写它，切会话的
 * `observeSession` / `newSession` 一个字都不碰。上游把两者当**一份保存态**：
 * `write({ agent, model, variant })` 落在 `saved.session[sessionID]`（无会话时
 * 是 `store.draft`），`providers/models/selection.tsx:288-299`。所以
 * 「变体应随会话」不是新语义，而是把变体接回它本来的那张表。
 *
 * 本类钉死接线的四个不变量：写入只经 [pinVariant] 一处、进出会话按 id 取回、
 * 清模型同时清变体、草稿提升时变体跟模型一起交接。
 */
class VariantScopeV69Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun vm() = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

    // ------------------------------------------------------------ 纯规则不变

    @Test
    fun theVariantResolutionRuleIsUntouched() {
        // 上游 `resolveModelVariant`（`variant.ts:31-35`）：未钉 = 不带，
        // `default` = 不带，只有目录里真有的标签才算。
        val variants = listOf("none", "low", "medium", "high", "xhigh")
        assertEquals("high", ModelPickerItems.resolveVariant("high", variants))
        assertNull(ModelPickerItems.resolveVariant(null, variants))
        assertNull(ModelPickerItems.resolveVariant("default", variants))
        assertNull(ModelPickerItems.resolveVariant("low", listOf("high")))
    }

    @Test
    fun aSessionThatPinnedNothingReadsTheDefaultEffort() {
        // 5558 的 A：它自己没钉过变体，所以显示/写回的必须是「没有变体」，
        // 而不是上一个会话留下的 `low`。
        assertNull(
            "an unpinned session must resolve to no variant",
            ModelPickerItems.resolveVariant(null, listOf("none", "low", "high")),
        )
    }

    // -------------------------------------------------------------- 接线四不变量

    @Test
    fun everyVariantWriteGoesThroughTheScopedWriter() {
        val body = vm()
        // `pinVariant` is the only *user-side* writer; the two v72 server-side
        // restores (the snapshot's `SessionRow.variant` adopt, the
        // `session.model.selected` frame reconcile) write the slot the value
        // already belongs to at the call site instead of re-filing it.
        val writes = Regex("_selectedVariant\\.value =").findAll(body).toList()
        assertEquals(
            "a bare `_selectedVariant.value =` outside pinVariant + the two restores re-opens the leak",
            3,
            writes.size,
        )
        assertTrue(writes.any { body.substring(maxOf(0, it.range.first - 120), it.range.first).contains("private fun pinVariant") })
        val writer = body.substringAfter("private fun pinVariant(value: String?)")
            .substringBefore("\n    }")
        assertTrue(writer, writer.contains("id = sessionID"))
        assertTrue(writer, writer.contains("if (id == null) {"))
        assertTrue(writer, writer.contains("draftVariant = value"))
        assertTrue(writer, writer.contains("if (value == null) sessionVariants.remove(id) else sessionVariants[id] = value"))
    }

    @Test
    fun openingASessionTakesTheEffortFromThatSessionOnly() {
        val body = vm()
            .substringAfter("fun observeSession(id: String, title: String? = null, directory: String? = null)")
            .substringBefore("fun newSession()")
        // 与 `modelOverride = sessionPicks[id]` 并排，同一张表、同一个 id。
        assertTrue(body, body.contains("modelOverride = sessionPicks[id]"))
        assertTrue(body, body.contains("pinVariant(sessionVariants[id])"))
        // 绝不能拿全局槽或持久化记忆来填。
        assertFalse(body, body.contains("pinVariant(draftVariant)"))
        // v72: 冷重启后内存表是空的，真正的回读是 snapshot 的 SessionRow.variant
        // adopt（见 SessionVariantRestoreV72Test），observeSession 只做内存恢复。
        assertFalse(body, body.contains("_selectedVariant.value = variant"))
        assertFalse(body, body.contains("_selectedVariant.value = rowVariant"))
    }

    @Test
    fun aDraftStartsFromItsOwnEffortAgain() {
        val body = vm()
            .substringAfter("fun newSession()")
            .substringBefore("/** True when [e] is the server's")
        // 切回草稿回到 `store.draft` 的变体，不是刚离开那个会话的。
        assertTrue(body, body.contains("modelOverride = recentModel"))
        assertTrue(body, body.contains("pinVariant(draftVariant)"))
    }

    @Test
    fun theDraftEffortIsPromotedToTheSessionItCreates() {
        val body = vm()
            .substringAfter("fun createSession(")
            .substringBefore("/**\n     * `GET /api/project` read")
        assertTrue(
            "upstream `session.promote()` hands the draft's selection over",
            body.contains("recentModel?.let { sessionPicks[newID] = it }"),
        )
        assertTrue(body, body.contains("draftVariant?.let { sessionVariants[newID] = it }"))
        // 与模型同一条链：先交接，再 observe，再写回服务端。
        assertTrue(
            body.indexOf("sessionVariants[newID] = it") < body.indexOf("observeSession(id = newID"),
        )
    }

    @Test
    fun aModelSwitchAndTheServerDefaultRowDropTheEffortToo() {
        // W5-C B4：变体标签属于某一条目录项，换模型时不能带着不相干的 effort 走；
        // v69：这次清理必须同时落到会话那张表，否则换回来又是它。
        val select = vm()
            .substringAfter("fun selectModel(providerID: String, modelID: String)")
            .substringBefore("private fun selectedModelPin()")
        assertTrue(select, select.contains("pinVariant(null)"))
        assertFalse(
            "the old flow-only drop would leave the session slot behind",
            select.contains("_selectedVariant.value = null"),
        )

        val clear = vm()
            .substringAfter("fun clearModelOverride()")
            .substringBefore("/**\n     * A plain prompt")
        assertTrue(clear, clear.contains("pinVariant(null)"))
        assertTrue(
            "model and effort are one saved state upstream",
            clear.indexOf("sessionPicks.remove(it)") < clear.indexOf("pinVariant(null)"),
        )
    }

    @Test
    fun theChipAndTheCycleReadTheScopedValue() {
        // 消费侧不需要改：`selectedVariant` 这条 flow 现在装的是**本会话**的值。
        val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        assertTrue(screen, screen.contains("chatViewModel.selectedVariant.collectAsState()"))
        val app = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
        assertTrue(app, app.contains("chatViewModel?.selectedVariant?.value"))
    }
}
