package com.xiufeigo.opencodeshell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v69 桶 C3：「重命名首次 Enter 偶发不提交」——⋯ → 重命名 → 输入 → 第一次 Enter
 * 无效（编辑器还在、字还在），第二次成功（`research/v68_verify/5558/07_rename.png`
 * → `10_rename2.png`）。
 *
 * 定性：提交链本身是通的（[com.xiufeigo.opencodeshell.ui.home.HomeRowMenu.shouldCommit]
 * 对 `ENTER` 只看 `saving`，`HomeRenameCommitGate` 也只放行一次），所以丢的不是
 * 「提交被拒绝」，而是**按键没落到字段上**——`onPreviewKeyEvent` 走
 * `FocusOwner.dispatchKeyEvent`，字段不在焦点链上时它压根不会被调用。v43 已经把
 * 这条链写成过一遍（`research/v43_verify/tC/v43_C1/C2/C5`：`requestFocus()` 之后
 * 字段没有活的 `InputConnection`，首击字符和 Enter/✓ 全部无处可落），v45/v66 又
 * 各修了一半。
 *
 * v66 留下的洞在**预算的算法**上：`askForFocus()` 先 `focusTries += 1` 再
 * `runCatching { requestFocus() }`，而 `requestFocus()` 在 `LazyColumn` 还没挂上
 * 焦点节点时抛 `FocusRequester is not initialized`（v45 量到的正是这个 no-op）。
 * 也就是说：一次**注定失败**的问焦把预算花掉了，于是后面两次真正有机会的
 * 问焦（`withFrameNanos` 之后的稳定帧 + `onGloballyPositioned` 重试）都被
 * `focusTries >= FOCUS_TRIES` 挡在门外，字段停在「有草稿、无 InputConnection」
 * 的状态 —— Enter 无处可落，而输入是平台自己兜住的（所以字看得见）。冷列表
 * 第一次打开时最容易命中，也解释了「偶发」。
 *
 * 修法：**只有问焦成功才计费**。v66 的不变量（已持焦后不再重复问焦，重挂 IME
 * 会吞掉在途字符）由 `if (focused) return` 独立保证，不依赖这个计数器，所以本次
 * 改动不会把 v66 那个丢字符的 bug 放回来。
 */
class RenameFocusV69Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun list() = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")

    @Test
    fun onlyASuccessfulAskSpendsTheFocusBudget() {
        val body = list().substringAfter("fun askForFocus()").substringBefore("\n    }")
        // 计费必须在 `isSuccess` 之内。
        assertTrue(
            "a failed ask must stay retryable",
            body.contains("if (runCatching { focusRequester.requestFocus() }.isSuccess) {"),
        )
        assertTrue(body, body.indexOf("focusTries += 1") > body.indexOf("isSuccess"))
        assertTrue(
            "the keyboard follows a landed ask, not a swallowed one",
            body.indexOf("keyboard?.show()") > body.indexOf("focusTries += 1"),
        )
        assertFalse(
            "no advance before the call — that is the v69 hole",
            body.contains("focusTries += 1\n        runCatching"),
        )
    }

    @Test
    fun theV66GuardAgainstReAskingAFocusedFieldIsStillThere() {
        // 回归闸：v66 的丢字符修复（`E2ECHK5`+`558-`+`ent` → `E2ECHK5558-nt`，
        // `research/v65_verify/5558/36_rename_ent_typed.png`）靠的是这条早退，
        // 与预算计费方式无关。
        val body = list().substringAfter("fun askForFocus()").substringBefore("\n    }")
        assertTrue(body, body.contains("if (focused) return"))
        assertTrue(body, body.contains("if (focusTries >= FOCUS_TRIES) return"))
    }

    @Test
    fun theRetryPathsThatCanStillLandAreKept() {
        val body = list().substringAfter("fun askForFocus()")
            .substringBefore("if (project != null && showProjectName)")
        // 稳定帧的双问 + 布局重试都还在，只是现在它们不会被失败的问焦饿死。
        assertTrue(body, body.contains("withFrameNanos { }\n        askForFocus()"))
        assertTrue(body, body.contains(".onGloballyPositioned { if (!focused) askForFocus() }"))
        assertTrue(list(), list().contains("private const val FOCUS_TRIES = 3"))
    }

    @Test
    fun theCommitChainItselfIsUnchanged() {
        // 提交语义不动：Enter/✓ 只被 `saving` 挡，blur 还要求真的持过焦 ——
        // 那是 v43 P0 的修复面（Enter 0/5、✓ 0/2、blur 0/2，一个 PATCH 都没发）。
        val menu = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeRowMenu.kt")
        assertTrue(
            menu,
            menu.contains("CommitTrigger.IME_DONE, CommitTrigger.ENTER -> !saving"),
        )
        assertTrue(menu, menu.contains("CommitTrigger.BLUR -> !saving && focused"))
    }
}
