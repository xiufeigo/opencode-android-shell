package com.xiufeigo.opencodeshell.web

import com.xiufeigo.opencodeshell.web.host.SecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX6 · **终端重接（terminal reattach）在直连架构下的等价绊线**。
 *
 * 取代旧 `TerminalReattachTest` 的 StableOrigin 部分（`StableOrigin.kt` 已随
 * 回环代理删除）。它守的性质是：**页面 origin 跨启动必须稳定**，否则上游
 * 「持久化 `pty id` + 进终端页自动 attach」的机制（
 * `_upstream/packages/app/src/session/terminal/context.tsx:104` 起，数据存
 * `localStorage`）在杀进程重启后读不到，回来必然是空终端。
 *
 * 旧架构里 origin = `http://127.0.0.1:<代理端口>`，端口随机 ⇒ 需要 StableOrigin
 * 钉端口。新架构里 origin = 后端地址（`bootUrl()`），**天然稳定** ⇒ 该性质
 * 由结构保障，本类把它钉成可断言的源代码事实：
 *  1. 启动地址不含任何随机成分（没有 ServerSocket/临时端口）；
 *  2. 跨启动的地址来源是同一个持久键（KEY_BACKEND_URL）+ 同一默认值；
 *  3. 恢复 URL 的判定是 startsWith(backend)（深链可以原样恢复）。
 *
 * ## 删测试的理由（不许为了让测试过而放宽断言）
 * 旧的 8 条 StableOrigin 纯逻辑用例测的是**已被删除的类**本身（候选端口顺序、
 * bind 回退、origin 拼串）——被测对象不存在，用例没有可守护的生产代码。
 * 性质本身（origin 稳定 ⇒ 重接可用）由本类的 3 条结构断言 + 设备实测继续守护。
 */
class TerminalReattachTest {

    private fun sourceOf(rel: String): String {
        var dir: File? = File(".").absoluteFile
        repeat(4) {
            val f = File(dir, rel)
            if (f.isFile) return f.readText(Charsets.UTF_8)
            dir = dir?.parentFile
        }
        throw AssertionError("找不到 $rel")
    }

    private val activitySrc: String
        get() = sourceOf("src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt")

    @Test
    fun bootUrlHasNoRandomComponent() {
        // 随机端口是旧代理的 ServerSocket(0)；直连架构里出现它 = 有人把随机化接回来了。
        assertFalse(
            "启动路径不得再出现 ServerSocket（随机端口会让 origin 每次启动都变 ⇒ " +
                "上游 pty 持久化读不到 ⇒ 终端重接失效）",
            activitySrc.contains("ServerSocket"),
        )
    }

    @Test
    fun bootUrlIsStableAcrossLaunches() {
        // 同一持久键 + 同一默认值 ⇒ 什么都不改时，每次冷启动的 origin 逐字节相同。
        assertTrue(
            "bootUrl 必须来自 KEY_BACKEND_URL ?: DEFAULT_BACKEND_URL（持久键，非内存态）",
            activitySrc.contains(
                "SecretStore.getSecret(this, SecretStore.KEY_BACKEND_URL) ?: SecretStore.DEFAULT_BACKEND_URL",
            ),
        )
        assertEquals(
            "默认后端地址不得漂移（origin 稳定性的默认值那一半）",
            "http://127.0.0.1:4095",
            SecretStore.DEFAULT_BACKEND_URL,
        )
    }

    @Test
    fun restoredUrlIsReusedWhenItBelongsToBackend() {
        val start = activitySrc.indexOf("private fun launch(")
        assertTrue("找不到 launch()", start >= 0)
        val body = activitySrc.substring(start, minOf(start + 2500, activitySrc.length))
        assertTrue(
            "深链恢复必须按 startsWith(backend) 判定（同 origin 才复用，跨 origin 退回 / ）",
            body.contains("restored.startsWith(backend)"),
        )
    }
}
