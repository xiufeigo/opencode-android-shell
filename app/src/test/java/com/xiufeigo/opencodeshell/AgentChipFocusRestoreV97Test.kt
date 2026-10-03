package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.components.OcPopoverCloseReason
import com.xiufeigo.opencodeshell.ui.session.composer.restoresFocusOnUnmount
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v97（task-4，v57 修复批移交项）— AgentChip 的 agent 弹层补上「桶B」焦点归还。
 *
 * 取证：移交项点名 AgentChip.kt / Composer.kt 两处 `ocPopoverDismiss` 弹层。
 * Composer.kt 的 `+` 菜单已在 **v63 BUG-1** 落地（`openedOnce` 闩 +
 * `onRestoreFocus`，只在真的开过一次之后的关闭触发），不动它；AgentChip 侧一直
 * 没修 —— 弹层根节点的初始聚焦（`ocPopoverDismiss` 的 `requestInitialFocus`，
 * ESC 的必要条件）把焦点与 IME 从 composer 编辑器手里抢走，卸载后没人接手，
 * 布局永久停在「无键盘」那一档，下一次弹层的锚点就永久偏移一个键盘高度
 * （`OcPopoverDismiss` 文件头「关掉之后焦点还给谁」一节）。
 *
 * 修法照 `ModelChip`（v57/v75 已定语义）：关掉时记下原因，弹层内容卸载那一刻
 * 只有「真的关过一次」（原因非 null 且非 OUTSIDE）才把焦点与键盘还回去。
 */
class AgentChipFocusRestoreV97Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val agentChip =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/AgentChip.kt")

    private val composer =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    private fun flat(source: String): String = source.replace(Regex("\\s+"), " ")

    @Test
    fun onlyARealCloseHandsTheFocusBack() {
        // v75 P1 语义钉在共享判定上，agent 弹层复用同一条，不得本地另写一份。
        assertFalse("a rebuild is not a close", restoresFocusOnUnmount(null))
        assertTrue(restoresFocusOnUnmount(OcPopoverCloseReason.KEY))
        assertTrue(restoresFocusOnUnmount(OcPopoverCloseReason.PICK))
        assertFalse(
            "the outside tap is replayed onto the control underneath, not answered with focus",
            restoresFocusOnUnmount(OcPopoverCloseReason.OUTSIDE),
        )
        assertFalse("the chip must re-use the shared policy, not re-define it", "fun restoresFocusOnUnmount" in agentChip)
    }

    @Test
    fun everyClosePathCarriesAReasonAndOpenClearsIt() {
        // 关闭原因：null = 这次卸载不是一次关闭（组件被重建而卸载）。
        assertTrue(
            agentChip.contains("var closeReason by remember { mutableStateOf<OcPopoverCloseReason?>(null) }"),
        )
        // 三条关闭路径都带原因：外点（Popup 自己的 dismiss）、ESC
        // （ocPopoverDismiss）、选中（列表行 + 默认行）。
        assertEquals(
            1,
            Regex("""onDismissRequest = \{ closeMenu\(OcPopoverCloseReason\.OUTSIDE\) \}""")
                .findAll(agentChip).count(),
        )
        assertTrue(
            agentChip.contains("ocPopoverDismiss(popoverDismiss) { closeMenu(OcPopoverCloseReason.KEY) }"),
        )
        assertEquals(
            "both pick rows close through the same reason",
            2,
            Regex("""closeMenu\(OcPopoverCloseReason\.PICK\)""").findAll(agentChip).count(),
        )
        // 打开时清掉上一轮的原因 + 记下键盘当时可见与否 —— 这两条一起保证
        // 「重建卸载」不会被误判成一次关闭，且键盘只在原本可见时才叫回来。
        val open = agentChip
            .substringAfter("val openMenu: () -> Unit = {")
            .substringBefore("}")
        assertTrue("opening must reset the close reason", open.contains("closeReason = null"))
        assertTrue("opening must snapshot the IME state", open.contains("imeVisibleAtOpen = imeVisibleNow"))
    }

    @Test
    fun theTeardownRestoresAtDisposeTimeAndReShowsOnlyWhatWasVisible() {
        val chip = flat(agentChip)
        assertTrue(
            "the teardown reads the reason it actually ended on",
            chip.contains("if (restoresFocusOnUnmount(closeReason)) {"),
        )
        // 回调读卸载那一刻的那一份（rememberUpdatedState），不是组合时捕获的。
        assertTrue(chip.contains("val latestRestore by rememberUpdatedState(onRestoreFocus)"))
        // 键盘只在打开时可见才补 show —— 原本没有就不多事，下一次打开量到的
        // composer 位置才和这一次相同。
        assertTrue(chip.contains("if (imeVisibleAtOpen) keyboardController?.show()"))
    }

    @Test
    fun theComposerHandsTheEditorBackToTheChip() {
        // Composer 的调用点把编辑器的 focusRequester 交过去 —— 与 ModelChip 同款
        // 的接线（弹层真关过一次时把焦点/IME 还给编辑器，锚点基准不漂）。
        val call = composer.substringAfter("AgentChip(").substringBefore("ModelChip(")
        assertTrue(
            "the AgentChip call must wire onRestoreFocus",
            call.contains("onRestoreFocus = { runCatching { focusRequester.requestFocus() } }"),
        )
    }
}
