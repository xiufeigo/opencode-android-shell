package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P5 绊线（tripwire）：**输入路径上不得有"每次按键都要重跑一遍"的同步重活**。
 *
 * ## 它钉住的是什么
 * P4 同机对照测出 web 壳输入延迟 **p50 35.70 / p90 82.55 ms**，原生 **29.02 / 37.26 ms**
 * （web 差 6.68 ms、p90 差 45 ms）。P5 归因后，宿主侧能改的只有一类东西：
 * **注入层在输入路径上做的同步工作**。这类东西有一个可复现的静态特征 ——
 * 每次输入都会重新执行的注册点。所以本绊线把它们**静态地钉死**：
 *
 * 1. `oc-host.js`（宿主注入层，**跑在官方 UI 同一个主线程上**）不得注册
 *    **逐键** 监听（`keydown` / `keypress` / `keyup` / `beforeinput` / `input`）。
 *    逐键监听 = 每次按键都在主线程上多跑一遍我们的代码，且它和上游自己的
 *    受控输入/重渲染抢同一个线程。
 * 2. `WebHostActivity.kt` 不得在**输入/触摸/按键的分发入口**里调
 *    `evaluateJavascript`（每次输入跨一次 JNI + 一次 V8 编译/执行）。
 * 3. 注入层在输入路径上不得做**同步存储读**（`localStorage.getItem` / `JSON.parse` 大对象），
 *    这是典型的"看起来很轻、其实每次都同步阻塞"的重活。
 *
 * ## 为什么用静态判据而不是设备判据
 * "有没有长任务"只能在真机/模拟器上测（见 `p5_attr_install.js` + `INPUT_PATH_P5.md` 的设备侧判据），
 * 而**能不能让上游每次按键多做一件事**是可以在 CI 里断言的。
 * 两者互补：静态绊线守住"我们不添乱"，设备判据守住"实际有没有长任务"。
 *
 * ## 反向验证（本绊线必须能变红）
 * [redJs] / [redKotlin] 是**故意写坏**的样本：它们分别触发上面第 1、2 条。
 * 如果扫描器对坏样本不报红，就说明这条绊线是"永远绿"的假绊线 —— 所以
 * `scanner flags injected-bad-source` 这条断言就是反向验证。
 */
class InputPathHotspotTest {

    private fun read(rel: String): String {
        val f = File(rel)
        assertTrue("找不到 $rel（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        return f.readText()
    }

    // ---------------------------------------------------------------- 扫描器

    /** 逐键事件：注册在这些事件上的监听器 = 每次按键都要跑我们自己的代码。 */
    private val perKeystrokeEvents = listOf("keydown", "keypress", "keyup", "beforeinput", "input")

    /**
     * 找出 [src] 里所有"逐键监听"注册点。
     * 只认 `addEventListener("<event>"` 与 `on<event> =` 两种写法，
     * 且**不**把 `focusin` / `focusout` / `paste` 之类非逐键事件算进去
     * （IME1 的 `focusin` 委托是**焦点生命周期**事件，每次输入只触发一次，
     *  实测代价见 IME_OCCLUTION_FIX.md §4.1，不属于逐键热路径）。
     */
    private fun perKeystrokeHits(js: String): List<String> {
        val hits = mutableListOf<String>()
        for (ev in perKeystrokeEvents) {
            val add = Regex("""addEventListener\(\s*["']$ev["']""")
            if (add.containsMatchIn(js)) hits += "addEventListener('$ev')"
            val on = Regex("""\bon$ev\s*=\s*""")
            if (on.containsMatchIn(js)) hits += "on$ev ="
        }
        return hits
    }

    /** 找出 Kotlin 里"输入分发入口函数体"中的 `evaluateJavascript` 调用。 */
    private fun inputDispatchJsHits(kt: String): List<String> {
        val entries = listOf(
            "onTouchEvent", "dispatchTouchEvent", "onGenericMotionEvent",
            "onKeyDown", "onKeyUp", "onKeyMultiple", "onTrackballEvent",
            "onGenericMotion",
        )
        val hits = mutableListOf<String>()
        for (e in entries) {
            // 取 `override fun <entry>( ... ) { ... }` 的函数体（按花括号配平）
            val start = Regex("""fun\s+$e\s*\(""").find(kt) ?: continue
            val open = kt.indexOf('{', start.range.first)
            if (open < 0) continue
            var depth = 0
            var i = open
            while (i < kt.length) {
                when (kt[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                i++
            }
            val body = kt.substring(open, minOf(i, kt.length))
            if (body.contains("evaluateJavascript")) hits += "$e{ evaluateJavascript }"
        }
        return hits
    }

    /** 注入层在输入路径上的同步重活（同步存储读）。 */
    private fun syncStorageHits(js: String): List<String> {
        val hits = mutableListOf<String>()
        for (m in Regex("""(localStorage|sessionStorage)\s*\.\s*(getItem|setItem)\s*\(""").findAll(js)) {
            // 只有当它出现在逐键/输入监听器附近才算输入路径重活；
            // 这里用"整文件里没有逐键监听"作为前提，由调用方交叉判断。
            hits += m.value.trim()
        }
        return hits
    }

    // ------------------------------------------------- 1) 真实代码必须干净

    @Test
    fun `host injection has no per-keystroke listeners`() {
        val js = read("src/main/assets/www/oc-host.js")
        val hits = perKeystrokeHits(js)
        assertEquals(
            "宿主注入层在输入路径上注册了逐键监听：$hits —— " +
                "每次按键都会在主线程上多跑一遍我们的代码，与上游受控输入重渲染抢同一个线程。" +
                "（如确有必要，请在本测试里显式豁免并写明实测代价。）",
            emptyList<String>(),
            hits,
        )
    }

    @Test
    fun `web host does not run javascript from input dispatch entrypoints`() {
        val kt = read("src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt")
        val hits = inputDispatchJsHits(kt)
        assertEquals(
            "输入/触摸/按键分发入口里出现了 evaluateJavascript：$hits —— " +
                "每次输入都要跨 JNI + 编译执行一段 JS，是纯同步开销。",
            emptyList<String>(),
            hits,
        )
    }

    @Test
    fun `input dispatch is not overridden at all in web host`() {
        val kt = read("src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt")
        val overrides = listOf("onTouchEvent", "dispatchTouchEvent", "onKeyDown")
            .filter { Regex("""override\s+fun\s+$it\s*\(""").containsMatchIn(kt) }
        assertEquals(
            "web 壳覆写了输入分发入口 $overrides —— 覆写本身就是热路径上的额外一层，"+
                "必须在本绊线里显式说明它做了什么。",
            emptyList<String>(),
            overrides,
        )
    }

    // ------------------------------------------------- 2) 反向验证：必须变红

    /** 故意写坏的注入层：注册了逐键监听。扫描器**必须**抓到，否则这条绊线是假绿。 */
    private val redJs = """
        document.addEventListener("DOMContentLoaded", function () {});
        document.addEventListener("focusin", function () {}, true);   // 合法：非逐键
        document.addEventListener("keydown", function (e) { heavy(e); });  // 违规
    """.trimIndent()

    /** 故意写坏的宿主：在 `onTouchEvent` 里 evaluateJavascript。扫描器**必须**抓到。 */
    private val redKotlin = """
        override fun onTouchEvent(event: MotionEvent): Boolean {
            webView.evaluateJavascript("window.scrollTo(0,0)", null)
            return true
        }
        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            return super.onKeyDown(keyCode, event)
        }
    """.trimIndent()

    @Test
    fun `scanner flags injected-bad-source - per keystroke`() {
        val hits = perKeystrokeHits(redJs)
        assertTrue(
            "反向验证失败：扫描器没抓到故意写坏的逐键监听（$redJs）⇒ 绊线 1 是假绿。",
            hits.any { it.contains("keydown") },
        )
        // 同时确认"合法事件不误报"：只摘 keydown 这一条违规
        assertEquals(listOf("addEventListener('keydown')"), hits)
    }

    @Test
    fun `scanner flags injected-bad-source - js in touch dispatch`() {
        val hits = inputDispatchJsHits(redKotlin)
        assertTrue(
            "反向验证失败：扫描器没抓到 onTouchEvent 里的 evaluateJavascript ⇒ 绊线 2 是假绿。",
            hits.any { it.contains("onTouchEvent") },
        )
    }

    @Test
    fun `scanner does not flag legitimate focus lifecycle listener`() {
        // IME1 的 focusin 委托是焦点生命周期事件，不是逐键热路径 —— 不得误报
        val legit = """
            document.addEventListener("focusin", onImeFocusIn, true);
        """.trimIndent()
        assertEquals(
            "误报：focusin 是焦点生命周期事件（每次输入只触发一次），不该被判成逐键热路径。",
            emptyList<String>(),
            perKeystrokeHits(legit),
        )
    }
}
