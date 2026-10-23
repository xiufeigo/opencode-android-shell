package com.xiufeigo.opencodeshell.web.host

import android.view.InputDevice
import android.view.KeyEvent

/**
 * 物理 **ESC 必须透传给 WebView**（xterm / Vim / OpenCode 自己的快捷键），
 * **绝不能**变成 Android Back。
 *
 * 背景（v2.0.25 真机：Android 平板 + 物理键盘）：
 * - 终端里按 ESC 没能退出 Vim 的插入模式，反而整页跳回了首页。
 * - 路径是 `KEYCODE_BACK` → `installBackHandler` → `window.__ocAndroid.onBack()`
 *   → `frontend/android/back.ts` 的 `navigate("/")`。
 * - 也就是说，部分 OEM 键盘把 ESC 报成了 `KEYCODE_BACK`；而
 *   `ComponentActivity.dispatchKeyEvent` 会把 `KEYCODE_BACK` 交给
 *   `onBackPressedDispatcher`，于是宿主自己的返回逻辑被误触发。
 *
 * 两条判定互不干扰：
 * - **真 ESC**（`KEYCODE_ESCAPE`）：原样交给 WebView，宿主不消费、不 finish。
 * - **被报成 BACK 的物理 ESC**：按键来源是键盘（`SOURCE_KEYBOARD`）且**不是**
 *   系统合成的虚拟按键（`FLAG_VIRTUAL_HARD_KEY`）⇒ 还原成 `KEYCODE_ESCAPE`
 *   再交给 WebView。
 *
 * ⚠️ 导航栏 / 手势返回**不能**被改写：系统合成的导航栏 BACK 带
 * `FLAG_VIRTUAL_HARD_KEY`，手势返回根本不是 KeyEvent（走
 * `OnBackInvokedCallback`）—— 两者都会继续走
 * [WebHostActivity.installBackHandler] → `__ocAndroid.onBack()` 那条老路。
 */
object WebHostEscapePolicy {
    /**
     * 键盘类输入设备的 class 位。`InputDevice` 没有公开 `SOURCE_CLASS_KEYBOARD`，
     * 但 `SOURCE_KEYBOARD` 自带 class 位（0x01），按位取出来即可。
     */
    private val KEYBOARD_SOURCE_CLASS: Int =
        InputDevice.SOURCE_KEYBOARD and InputDevice.SOURCE_CLASS_MASK

    /** 宿主**永不**消费 ESC：它必须先到达 xterm / Vim。 */
    fun shellConsumesEscape(): Boolean = false

    /** ESC 永远不是 Back，也永远不会让 Activity finish。 */
    fun shouldFinishOnEscape(): Boolean = false

    /** 这是不是一个 ESC 键事件（不论它被 OEM 报成了什么别的 keyCode）。 */
    fun isEscape(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_ESCAPE

    /**
     * AOSP 的导航栏是一台**虚拟**输入设备（`PhoneWindowManager.VirtualDeviceId` = -1）；
     * 物理键盘 / 蓝牙键盘的 deviceId 一定 ≥ 0。
     */
    private const val VIRTUAL_DEVICE_ID = -1

    /**
     * 一个 `KEYCODE_BACK` 事件是否其实是物理键盘上的 ESC。
     *
     * 判据：
     * 1. keyCode 确实是 `KEYCODE_BACK`；
     * 2. **不是**系统合成的虚拟硬键（导航栏返回带 `FLAG_VIRTUAL_HARD_KEY`）；
     * 3. 来源是键盘类（触摸 / 手势来源一律拒绝）；
     * 4. 来源真的是键盘，且不是 AOSP 的虚拟导航栏设备。
     *
     * @param deviceId 事件的设备 id；`null` = 未知（不施加第 4 条判据）。
     */
    fun shouldRemapBackToEscape(
        keyCode: Int,
        source: Int,
        flags: Int,
        deviceId: Int? = null,
    ): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK) return false
        // 导航栏返回是系统合成的虚拟按键，必须继续走正常的 Back 路径。
        if (flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY != 0) return false
        // 触摸 / 手势来源是系统返回，绝不是键盘上的 ESC。
        if (source and InputDevice.SOURCE_CLASS_MASK != KEYBOARD_SOURCE_CLASS) return false
        // 来源必须真的是键盘（游戏手柄的按键同样落在 button class 上）。
        if (source and InputDevice.SOURCE_KEYBOARD != InputDevice.SOURCE_KEYBOARD) return false
        // AOSP 的导航栏也报键盘来源，但它是一台虚拟设备：不是用户的 ESC。
        if (deviceId != null && deviceId == VIRTUAL_DEVICE_ID) return false
        return true
    }
}
