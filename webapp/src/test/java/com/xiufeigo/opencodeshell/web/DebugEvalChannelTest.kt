package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * C1（任务 2）绊线：`--es oc_debug_eval` 调试注入通道的开关。
 *
 * ## 先说清一件事：`BuildConfig.DEBUG` 在 debug 变体里**并不是 false**
 * `research/env/PHONE_VIEWPORT_5636.md:109-110` 与 `NARROW_PHONE_E6.md:204` 记的是
 * 「该 web 包虽名为 `.web.debug`，但 `BuildConfig.DEBUG` 编译期为 false」。
 * **本轮实测这条前提不成立**（`webapp/build.gradle.kts` 里没有任何
 * `buildConfigField("boolean", "DEBUG", …)` 覆盖，AGP 生成的
 * `build/generated/source/buildConfig/debug/…/BuildConfig.java` 逐字是
 * `public static final boolean DEBUG = Boolean.parseBoolean("true");`）。
 *
 * 但通道当时确实没回日志（`OcWebHost/Eval` 零命中），**真实根因未闭环**（见
 * `research/web/CLEANUP_C1.md` §2.3）。本类的职责因此收窄为**守住这条通道的
 * 开关契约**，让「debug 开 / release 关」变成可断言的、而不是靠读代码的人记。
 *
 * ## 两条断言各自能红在哪
 * - `debugBuildEnablesTheChannel`：把 debug 变体的 `isDebuggable` 关掉 ⇒ `BuildConfig.DEBUG`
 *   变 false ⇒ **红**。
 * - `releaseBuildKeepsTheChannelClosed`：给 release 块加 `isDebuggable = true`，或给任意变体
 *   加 `buildConfigField("boolean", "DEBUG", …)` 覆盖 ⇒ **红**。
 */
class DebugEvalChannelTest {

    private fun moduleFile(relative: String): String {
        val f = File(relative)
        assertTrue("找不到 $relative（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        return f.readText()
    }

    private val buildGradle: String get() = moduleFile("build.gradle.kts")
    private val activitySource: String get() =
        moduleFile("src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt")

    @Test
    fun debugBuildEnablesTheChannel() {
        assertEquals(
            "本绊线必须在 debug 变体里跑（当前 BUILD_TYPE=${BuildConfig.BUILD_TYPE}）",
            "debug",
            BuildConfig.BUILD_TYPE,
        )
        assertTrue(
            "C1: debug 构建下 `oc_debug_eval` 通道必须开启。BuildConfig.DEBUG=false ⇒ " +
                "`WebHostActivity.consumeDebugEval` 开头直接 return，通道静默死掉。" +
                "查 webapp/build.gradle.kts 的 buildTypes.debug（多半是 isDebuggable 被关了）。",
            BuildConfig.DEBUG,
        )
    }

    @Test
    fun releaseBuildKeepsTheChannelClosed() {
        // 1) 没有任何变体用 buildConfigField 覆盖 DEBUG —— 那样 AGP 的
        //    「DEBUG == isDebuggable」不变式就断了，debug/release 的语义会漂。
        val override = Regex("""buildConfigField\s*\(\s*"boolean"\s*,\s*"DEBUG"""")
        assertFalse(
            "C1: 有人用 buildConfigField 覆盖了 DEBUG。`BuildConfig.DEBUG` 必须恒等于" +
                "该变体的 isDebuggable，否则「debug 开 / release 关」这条契约会漂。",
            override.containsMatchIn(buildGradle),
        )

        // 2) release 块不能把 isDebuggable 打开（AGP 默认 false，显式写 true 才是漏洞）。
        val releaseBlock = Regex("(?s)\\brelease\\s*\\{(.*?)\\n        \\}").find(buildGradle)
        assertTrue("解析不到 build.gradle.kts 的 `release { … }` 块", releaseBlock != null)
        assertFalse(
            "C1: release 变体把 isDebuggable 打开了 ⇒ `oc_debug_eval` 通道会在 release 里生效。" +
                "release 必须静默忽略该 extra。",
            Regex("""isDebuggable\s*=\s*true""").containsMatchIn(releaseBlock!!.groupValues[1]),
        )

        // 3) 源码门卫仍在：release 的 BuildConfig.DEBUG=false ⇒ 这行直接 return。
        assertTrue(
            "C1: `consumeDebugEval` 的 `if (!BuildConfig.DEBUG) return` 门卫被删了 —— " +
                "release 构建下 `--es oc_debug_eval` 将不再被忽略。",
            activitySource.contains("if (!BuildConfig.DEBUG) return"),
        )

        // 4) 通道接线仍然完整（extra 名不能悄悄改掉，research/env 下的脚本全靠它）。
        assertEquals("C1: 调试注入 extra 名被改了，research/env/*.ps1 全部失效",
            "oc_debug_eval", WebHostActivity.EXTRA_DEBUG_EVAL)
    }
}
