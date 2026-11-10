package com.xiufeigo.opencodeshell.web.host

import android.content.Context
import android.provider.Settings

/**
 * 系统导航形态（3 键 / 2 键 / 手势），给 [NavigationBarVisual] 当**即时**判据。
 *
 * `WindowInsetsCompat.Type.tappableElement()` 在首帧经常还没算完，会把整条
 * `navigationBars` 原样抄过来。手势导航下这会被误判成 3 键，视口先按 32 CSS px
 * 让位（会话面板悬空），等下一轮 insets 才掉到手柄上沿。用户看到的就是
 * 「刚点进会话还是原样，过一会才降下去」。
 *
 * `Settings.Secure.navigation_mode` 与 `config_navBarInteractionMode` 不经过
 * insets 动画，Activity 一创建就能读到，用来盖过那帧假 3 键。
 */
object NavigationBarMode {
    const val UNKNOWN = -1
    const val THREE_BUTTON = 0
    const val TWO_BUTTON = 1
    const val GESTURAL = 2

    private const val SECURE_NAVIGATION_MODE = "navigation_mode"

    fun read(context: Context): Int {
        val fromSettings = runCatching {
            Settings.Secure.getInt(context.contentResolver, SECURE_NAVIGATION_MODE, UNKNOWN)
        }.getOrDefault(UNKNOWN)
        if (fromSettings in THREE_BUTTON..GESTURAL) return fromSettings

        val id = context.resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
        if (id != 0) {
            val fromRes = runCatching { context.resources.getInteger(id) }.getOrDefault(UNKNOWN)
            if (fromRes in THREE_BUTTON..GESTURAL) return fromRes
        }
        return UNKNOWN
    }
}
