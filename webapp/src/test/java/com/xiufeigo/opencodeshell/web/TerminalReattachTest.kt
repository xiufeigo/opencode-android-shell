package com.xiufeigo.opencodeshell.web

import com.xiufeigo.opencodeshell.web.host.StableOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.File
import java.net.ServerSocket

/**
 * UX2 · 「杀掉 app 再打开能接回同一终端会话」的绊线。
 *
 * ## 这组用例守护的**前提**（不是终端功能本身）
 *
 * 上游已经有「持久化 `pty id` + 进终端页自动 attach」
 * （`_upstream/packages/app/src/session/terminal/context.tsx:104`、
 * `terminal.tsx:573-586`、`terminal.tsx:543-552` 的 404 干净降级），
 * 它存在 **`localStorage`** 里。而 `localStorage` **按 origin 隔离**，
 * origin = `http://127.0.0.1:<回环端口>`。
 *
 * ⇒ 改前端口是 `ServerSocket(0)`（临时端口）⇒ **origin 每次启动都变** ⇒
 * 上游那份持久化**永远读不到** ⇒ 回来只能是空终端。
 *
 * ⇒ 本组钉住的就是**这条因果链的前半截**：端口必须被钉住。
 * 真正"接回同一会话"的证据在设备实测（见 `research/web/UX2_TERMINAL_REATTACH.md` §4）。
 */
class TerminalReattachTest {

    // ---- 纯逻辑：候选顺序 -------------------------------------------------

    @Test
    fun pinnedPortIsPreferredOverEphemeral() {
        assertEquals(listOf(40_495, 0), StableOrigin.candidates(40_495))
    }

    @Test
    fun zeroPreferredDegradesToEphemeralOnly() {
        // 传 0（改前语义）⇒ 只剩临时端口，不假装能钉住。
        assertEquals(listOf(0), StableOrigin.candidates(0))
        assertFalse(StableOrigin.isPinned(0))
        assertFalse(StableOrigin.isPinned(-1))
    }

    @Test
    fun ephemeralOnlyMeansOriginIsNotGuaranteedStable() {
        // 这条是**反向断言**：只走临时端口时，不能声称 origin 稳定。
        assertEquals(1, StableOrigin.candidates(0).size)
        assertTrue(StableOrigin.candidates(0).single() == 0)
    }

    @Test
    fun bindActuallyUsesThePinnedPortWhenFree() {
        // 真绑一个**空闲**端口：证明 bind 走的**第一个**候选就是钉的那个。
        val free = ServerSocket(0).use { it.localPort }
        val seen = mutableListOf<Int>()
        val fake = object : ServerSocket() {
            override fun close() = Unit
        }
        val got = StableOrigin.bind(free) { candidate ->
            seen += candidate
            fake
        }
        // 只调了一次，且用的就是钉的端口（**没有**退回临时端口）。
        assertEquals(listOf(free), seen)
        assertSame(fake, got)
    }

    @Test
    fun bindFallsBackToEphemeralWhenPinnedPortIsTaken() {
        // 钉的端口**已被占用** ⇒ 必须退到临时端口，**不能抛、不能起不来**。
        ServerSocket(0).use { taken ->
            val seen = mutableListOf<Int>()
            val fake = object : ServerSocket() {
                override fun close() = Unit
            }
            StableOrigin.bind(taken.localPort) { candidate ->
                seen += candidate
                if (candidate != 0) throw IOException("address already in use")
                fake
            }
            assertEquals(listOf(taken.localPort, 0), seen)
        }
    }

    @Test
    fun bindThrowsOnlyWhenEveryCandidateFails() {
        val error = runCatching {
            StableOrigin.bind(40_495) { throw IOException("boom") }
        }.exceptionOrNull()
        assertTrue(error is IOException)
        // 明确断言"失败信息被透传"，而不是被吞成 null。
        assertEquals("boom", error?.message)
    }

    @Test
    fun originMatchesLoopbackProxyPublicOrigin() {
        assertEquals("http://127.0.0.1:40495", StableOrigin.origin(40_495))
    }

    @Test
    fun defaultPreferredPortIsUnprivilegedAndNotTheBackendPort() {
        // 不能占用特权端口（<1024），也不能和上游后端默认 4095 撞。
        assertTrue(StableOrigin.DEFAULT_PREFERRED_PORT > 1023)
        assertTrue(StableOrigin.DEFAULT_PREFERRED_PORT != 4095)
    }

    // ---- 源码接线检查：撤回改动必须真红 ------------------------------------

    private fun sourceOf(relative: String): String {
        // 沿用本仓库既有的"从模块目录往上找源码"口径（同 KeepScreenOnWiringTest.src）。
        var dir: File? = File(".").absoluteFile
        repeat(4) {
            val f = File(dir, relative)
            if (f.isFile) return f.readText(Charsets.UTF_8)
            dir = dir?.parentFile
        }
        throw AssertionError("找不到源文件: $relative（自 ${File(".").absolutePath} 往上找了 4 层）")
    }

    private val proxySource: String
        get() = sourceOf("src/main/java/com/xiufeigo/opencodeshell/web/host/LoopbackProxy.kt")

    private val stableSource: String
        get() = sourceOf("src/main/java/com/xiufeigo/opencodeshell/web/host/StableOrigin.kt")

    private val activitySource: String
        get() = sourceOf("src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt")

    @Test
    fun loopbackProxyNoLongerBindsEphemeralPortZero() {
        val src = proxySource
        // 改前就是这一行：`val socket = ServerSocket(0, 50, …)`。
        val ephemeralBind = Regex("""ServerSocket\(\s*0\s*,""")
        assertFalse(
            "LoopbackProxy 仍在绑临时端口 0 ⇒ origin 每次启动都变 ⇒ 上游的 pty 持久化读不到",
            ephemeralBind.containsMatchIn(src),
        )
    }

    @Test
    fun loopbackProxyStartGoesThroughStableOriginBind() {
        val src = proxySource
        assertTrue("start() 必须走 StableOrigin.bind", src.contains("StableOrigin.bind(preferredPort)"))
        assertTrue("必须把 preferredPort 传进去", src.contains("private val preferredPort: Int"))
    }

    /**
     * 红线复核：**不许**因为这次改动，让"没配服务器也能用"。
     * F21 的性质是「口令为空 ⇒ 绝不启动代理」。
     */
    @Test
    fun stableOriginDoesNotWeakenTheEmptyPasswordGate() {
        val stable = stableSource
        val activity = activitySource
        // StableOrigin 本身**不认识**口令，也不碰加载路径 ⇒ 它没法绕过 F21。
        assertFalse(stable.contains("mayLaunch"))
        assertFalse(stable.contains("loadUrl"))
        assertFalse(stable.contains("SecretStore"))
        // 闸门仍在：空口令分支依旧在起代理之前 return。
        assertTrue(activity.contains("LaunchGuard"))
        val gate = activity.indexOf("mayLaunch")
        val proxyStart = activity.indexOf(".start()")
        assertTrue("闸门下标必须早于代理启动", gate in 0 until proxyStart)
    }
}
