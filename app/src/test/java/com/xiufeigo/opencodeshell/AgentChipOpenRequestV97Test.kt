package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.agentOpenRequestArrived
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v97 P2：`/agent` 的打开请求必须**只被消费一次**。
 *
 * 取证（修复前）：`AgentChip.kt:144-145` 是
 * `LaunchedEffect(openRequest) { if (openRequest > 0) openMenu() }` —— 宿主那条
 * `agentOpenRequest`（`Composer.kt:467`）是单向闩、永不回落，这个 `LaunchedEffect`
 * 在**每次挂载**都以首 key 启动一次，而 `expanded`（`AgentChip.kt:112`）是纯
 * `remember`、重建即丢 ⇒ 用户敲过一次 `/agent` 之后，任何重建（点 composer 文本区
 * 让键盘与布局跟着变、`shellMode` 翻转、会话切换）都会「凭空」再弹一次 agent 菜单。
 *
 * 修法照 `ModelChip` 的 v75 P2（`ModelChip.kt:220-231` + `modelOpenRequestArrived`
 * `:602-603`）：记住本实例已经见过的值，只有**新**值才开。
 */
class AgentChipOpenRequestV97Test {

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

    // ---- ① 请求变化 → 打开     ② 重建/重组 → 不重弹     ③ 再次变化 → 再打开

    @Test
    fun onlyANewRequestOpensTheMenu() {
        // ① `/agent` 敲进命令菜单 → 宿主把闩从 0 抬到 1（`Composer.kt:1045`）。
        assertTrue(agentOpenRequestArrived(seen = 0, current = 1))
        // ③ 菜单关掉后再敲一次 → 闩到 2，又是**新**值 → 再打开。
        assertTrue(agentOpenRequestArrived(seen = 1, current = 2))
        assertTrue(agentOpenRequestArrived(seen = 7, current = 8))

        // ② 重建/重组不重弹：新实例挂载时 `remember` 的初值就是宿主此刻闩着的值，
        // 于是「挂载值 vs 当前值」相等 —— 这不是一条请求。
        assertFalse(agentOpenRequestArrived(seen = 1, current = 1))
        assertFalse(agentOpenRequestArrived(seen = 3, current = 3))
        // 一次都没敲过（闩还是初值）。
        assertFalse(agentOpenRequestArrived(seen = 0, current = 0))
        // 宿主把闩复位到 0 不算请求（否则每次复位都会误弹）。
        assertFalse(agentOpenRequestArrived(seen = 4, current = 0))
    }

    @Test
    fun theChipRemembersTheRequestItHasSeen() {
        val chip = flat(agentChip)
        // 与 `ModelChip.kt:227-231` 同形，但**不能**用 `ComposerShortWindowV74Test.kt:246-253`
        // 那种「四行压平成一块」的断言：ModelChip 的闩与效果是紧邻的两行，而 agent chip
        // 中间隔着 `expanded` / `closeReason` / `openMenu` / `anchor` / `label` 三十多行，
        // 压平后不连着。改用 `ComposerModelChipV75Test.kt:123-126` 的逐条 `contains`。
        assertTrue(chip.contains("var seenRequest by remember { mutableStateOf(openRequest) }"))
        assertTrue(chip.contains("LaunchedEffect(openRequest) {"))
        assertTrue(
            chip.contains(
                "LaunchedEffect(openRequest) { " +
                    "if (agentOpenRequestArrived(seenRequest, openRequest)) openMenu() " +
                    "seenRequest = openRequest }",
            ),
        )
        // v74 形状必须已经不在了。
        assertFalse(
            "the v74 latch re-read must be gone",
            chip.contains("if (openRequest > 0) openMenu()"),
        )
        // 只有**这一个** LaunchedEffect 消费宿主计数器，别处不许再偷读一次。
        assertEquals(
            "one consumer only",
            1,
            Regex("""LaunchedEffect\(openRequest\)""").findAll(agentChip).count(),
        )
    }

    @Test
    fun theLatchOutlivesTheHiddenAndLoadingEarlyReturn() {
        val chip = flat(agentChip)
        // 闩必须声明在 `if (!visible || options.isEmpty()) return` **之上**：chip 不可见
        // （`agentPickerVisible` 初值就是 false，`ChatViewModel.kt:581`）或 agent 列表
        // 还没到时整个 composable 早退，放在下面就等于「不可见期间敲的 `/agent`
        // 永远开不出来」。
        val latch = chip.indexOf("var seenRequest by remember { mutableStateOf(openRequest) }")
        val earlyReturn = chip.indexOf("if (!visible || options.isEmpty()) return")
        assertTrue("the latch must exist", latch >= 0)
        assertTrue("the early return must exist", earlyReturn >= 0)
        assertTrue("the latch must be declared above the early return", latch < earlyReturn)
    }

    @Test
    fun theHostStillRaisesTheCounterAndHandsItOver() {
        // 正路径没被改：slash 面板的 built-in 分支照旧抬闩，调用点照旧把闩传下来。
        assertTrue(composer.contains("var agentOpenRequest by remember { mutableStateOf(0) }"))
        assertTrue(
            composer.contains(
                "ComposerSuggestions.BUILT_IN_AGENT -> agentOpenRequest += 1",
            ),
        )
        val call = composer.substringAfter("AgentChip(").substringBefore("ModelChip(")
        assertTrue("the call must still pass the latch", call.contains("openRequest = agentOpenRequest"))
    }
}
