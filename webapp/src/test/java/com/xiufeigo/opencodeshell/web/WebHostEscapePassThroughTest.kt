package com.xiufeigo.opencodeshell.web

import com.xiufeigo.opencodeshell.web.host.WebHostEscapePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 绊线：物理 ESC 必须**透传**给 WebView，绝不能变成 Android Back。
 *
 * ### 缺陷（v2.0.25 真机：Android 平板 + 物理键盘）
 * 终端里按 ESC 退不出 Vim 的插入模式，反而整页跳回首页：
 * `KEYCODE_BACK` → `installBackHandler` → `window.__ocAndroid.onBack()`
 * → `frontend/android/back.ts` 的 `navigate("/")`。
 *
 * ### 两层
 * 1. **判据层**（[WebHostEscapePolicy] 纯函数）：哪些 `KEYCODE_BACK` 其实是
 *    物理键盘上的 ESC（该还原成 ESCAPE），哪些是系统返回（不许动）。
 * 2. **资产契约层**（读 `WebHostActivity.kt` + `oc-host.js`）：断言修法**真的在**
 *    源码里。撤回修法 ⇒ 必红。
 *
 * 与 `:app` 的 `AndroidBackJsTest` 同构：ESC 不被消费、不 finish，
 * 真 Back 仍然走 `__ocAndroid.onBack()`。
 */
class WebHostEscapePassThroughTest {

    // ---- 1. 判据层：纯函数 ------------------------------------------------

    @Test
    fun `物理键盘上报成 BACK 的 ESC 会被还原成 ESCAPE`() {
        assertTrue(
            "键盘来源的 BACK 必须被还原成 ESC",
            WebHostEscapePolicy.shouldRemapBackToEscape(
                KeyEventCompat.KEYCODE_BACK,
                InputDeviceCompat.SOURCE_KEYBOARD,
                0,
            ),
        )
    }

    @Test
    fun `导航栏返回是虚拟硬键 不许改写`() {
        assertFalse(
            "系统合成的 BACK（FLAG_VIRTUAL_HARD_KEY）必须继续走正常返回路径",
            WebHostEscapePolicy.shouldRemapBackToEscape(
                KeyEventCompat.KEYCODE_BACK,
                InputDeviceCompat.SOURCE_KEYBOARD,
                KeyEventCompat.FLAG_VIRTUAL_HARD_KEY,
            ),
        )
    }

    @Test
    fun `触摸与手势来源的返回不是键盘 ESC`() {
        assertFalse(
            "触摸来源不是键盘 ESC",
            WebHostEscapePolicy.shouldRemapBackToEscape(
                KeyEventCompat.KEYCODE_BACK,
                InputDeviceCompat.SOURCE_TOUCHSCREEN,
                0,
            ),
        )
        assertFalse(
            "未知来源不是键盘 ESC",
            WebHostEscapePolicy.shouldRemapBackToEscape(
                KeyEventCompat.KEYCODE_BACK,
                InputDeviceCompat.SOURCE_UNKNOWN,
                0,
            ),
        )
        assertFalse(
            "手柄按键不是键盘 ESC",
            WebHostEscapePolicy.shouldRemapBackToEscape(
                KeyEventCompat.KEYCODE_BACK,
                InputDeviceCompat.SOURCE_GAMEPAD,
                0,
            ),
        )
    }

    @Test
    fun `只有 BACK 会被还原 其它按键原样通过`() {
        assertFalse(
            "真 ESC 本来就是 ESC，不需要还原",
            WebHostEscapePolicy.shouldRemapBackToEscape(
                KeyEventCompat.KEYCODE_ESCAPE,
                InputDeviceCompat.SOURCE_KEYBOARD,
                0,
            ),
        )
        assertFalse(
            "回车不是 BACK",
            WebHostEscapePolicy.shouldRemapBackToEscape(
                KeyEventCompat.KEYCODE_ENTER,
                InputDeviceCompat.SOURCE_KEYBOARD,
                0,
            ),
        )
    }

    @Test
    fun `导航栏的虚拟设备不是键盘 ESC`() {
        assertFalse(
            "AOSP 导航栏是虚拟设备（deviceId = -1），不是用户的 ESC",
            WebHostEscapePolicy.shouldRemapBackToEscape(
                KeyEventCompat.KEYCODE_BACK,
                InputDeviceCompat.SOURCE_KEYBOARD,
                0,
                VirtualDeviceCompat.ID,
            ),
        )
        assertTrue(
            "真实键盘的 deviceId ≥ 0，必须照常还原",
            WebHostEscapePolicy.shouldRemapBackToEscape(
                KeyEventCompat.KEYCODE_BACK,
                InputDeviceCompat.SOURCE_KEYBOARD,
                0,
                3,
            ),
        )
    }

    @Test
    fun `宿主永不消费 ESC 也永不因 ESC 退出`() {
        assertFalse("宿主不能消费 ESC", WebHostEscapePolicy.shellConsumesEscape())
        assertFalse("ESC 不能让 Activity 退出", WebHostEscapePolicy.shouldFinishOnEscape())
        assertTrue(
            "ESC 键事件要能被认出来",
            WebHostEscapePolicy.isEscape(KeyEventCompat.KEYCODE_ESCAPE),
        )
        assertFalse(
            "BACK 不是 ESC",
            WebHostEscapePolicy.isEscape(KeyEventCompat.KEYCODE_BACK),
        )
    }

    // ---- 2. 资产契约层：修法真的在源码里 ----------------------------------

    @Test
    fun `WebHostActivity 有 dispatchKeyEvent 且不消费 ESC`() {
        val src = File("src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt").readText()
        assertTrue("WebHostActivity 必须有 dispatchKeyEvent", src.contains("override fun dispatchKeyEvent(event: KeyEvent)"))
        assertTrue(
            "ESC 必须原样交给 WebView",
            src.contains("super.dispatchKeyEvent(event)") || src.contains("super.dispatchKeyEvent(escaped)"),
        )
        assertTrue(
            "被报成 BACK 的物理 ESC 必须还原成 ESCAPE",
            src.contains("shouldRemapBackToEscape"),
        )
        assertTrue(
            "还原后必须用 KEYCODE_ESCAPE 派发",
            src.contains("KeyEvent.KEYCODE_ESCAPE"),
        )
        // ESC 绝不能被消费掉（否则 xterm / Vim 收不到）。
        assertFalse(
            "shell must not consume ESC before the WebView / terminal",
            src.contains("KEYCODE_ESCAPE) return true") ||
                src.contains("keyCode == KeyEvent.KEYCODE_ESCAPE) return true"),
        )
        // ESC 绝不能让 Activity 退出。
        assertFalse("ESC 不得 finish()", src.contains("finish()"))
        assertFalse("不得覆盖 onKeyDown", src.contains("override fun onKeyDown"))
    }

    @Test
    fun `真正的返回仍然走 onBack 回调`() {
        val src = File("src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt").readText()
        assertTrue("返回回调仍然注册在 onBackPressedDispatcher 上", src.contains("onBackPressedDispatcher.addCallback"))
        assertTrue("返回仍然问页面 __ocAndroid.onBack", src.contains("__ocAndroid.onBack"))
        // ESC 的改写只发生在 dispatchKeyEvent 里，不能顺手把 installBackHandler 也改了。
        assertTrue("installBackHandler 仍然存在", src.contains("private fun installBackHandler()"))
    }

    @Test
    fun `注入层声明 ESC 透传`() {
        val js = File("src/main/assets/www/oc-host.js").readText()
        assertTrue("注入层必须声明 ESC 透传", js.contains("__ocAndroidEscapePassThrough"))
    }
}

/**
 * 只用编译期常量，避免 JVM 单测去加载 `android.jar` 的 stub（那些方法会抛
 * "not mocked"）。这里的值与 `android.view.KeyEvent` / `android.view.InputDevice`
 * 的公开常量一一对应。
 */
private object KeyEventCompat {
    const val KEYCODE_BACK = 4
    const val KEYCODE_ESCAPE = 111
    const val KEYCODE_ENTER = 66
    const val FLAG_VIRTUAL_HARD_KEY = 0x00000040
}

private object InputDeviceCompat {
    const val SOURCE_UNKNOWN = 0x00000000
    const val SOURCE_KEYBOARD = 0x00000101
    const val SOURCE_TOUCHSCREEN = 0x00001002
    const val SOURCE_GAMEPAD = 0x00000401
}

private object VirtualDeviceCompat {
    /** `PhoneWindowManager.VirtualDeviceId` —— AOSP 导航栏虚拟输入设备。 */
    const val ID = -1
}
