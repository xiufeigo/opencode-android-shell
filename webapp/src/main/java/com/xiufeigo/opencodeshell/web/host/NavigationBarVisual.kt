package com.xiufeigo.opencodeshell.web.host

/**
 * 底部系统导航条的**可视让位量**（[InsetsHost] 消费，`NavigationBarVisualTest` 钉住）。
 *
 * ### 为什么不能整条让出（2026-11-10 第一轮）
 *
 * 手势导航下 `WindowInsetsCompat.Type.navigationBars()` 上报的是**整条上滑热区**
 * （Pixel Tablet 实测 `frame=[0,1536][2560,1600]`，即 64px / 32 CSS px），
 * 而系统只在这条带子的**正中**画那根小白条（实测屏幕 y∈[1564,1571]，即带宽 8px、
 * 上沿距屏幕底 36px = (64+8)/2）。
 *
 * 整条让出的后果：视口被压掉 32 CSS px，会话面板底边还要再让出 shell 自己的
 * 8px 外边距 ⇒ 面板底边离小白条上沿 **23 CSS px**，中间一条纯背景带，看着像悬空。
 *
 * ### 为什么还要再收（2026-11-10 第二轮）
 *
 * 第一轮让到「手柄上沿 + 2px」，面板与手柄之间仍有 3px，且会话输入框还在面板里
 * 再缩 12px ⇒ 输入框底边距手柄 15px，用户仍觉得"留的空隙太大"。
 * 现在直接让**面板底边压在手柄上沿**：不再为手柄额外留空隙，
 * 输入框与手柄之间的视觉距离交给 `oc-host.js` 的 dock 内边距收紧（12px → 4px）。
 *
 * 判据
 *
 * - **3 键导航**：按钮本身就在这条带里（`tappableElement` 的底部 inset > 0），
 *   必须整条让出，否则按钮会压住界面。
 * - **手势导航**：`tappableElement` 为 0（没有可点按钮），这条带只是手势热区，
 *   面板底边对齐手柄上沿即可。手势热区由系统在屏幕边缘优先消费，
 *   面板让出上半段不影响上滑返回。
 *
 * 以系统上报的 `tappableElement` 为准，不猜 OEM；读不到（0）时按手势处理，
 * 因为此时确实没有可点的导航按钮。
 *
 * ### 为什么代码里出现面板的 8px 外边距
 *
 * 面板底边 = 视口底边 − `--shell-bottom-inset`（上游 `shell.tsx` 默认 8px）。
 * 目标是让**面板底边**落在手柄上沿，所以视口需要让出的量 =
 * 手柄上沿 − 面板外边距。
 */
object NavigationBarVisual {

    /** 手势手柄的绘制高度（Android 12+ 系统常量，4dp）。 */
    private const val HANDLE_HEIGHT_DP = 4

    /** 上游 shell 给会话面板的底部外边距（`--shell-bottom-inset` 默认值）。 */
    private const val SHELL_BOTTOM_MARGIN_DP = 8

    /**
     * 需要让出的底部像素（与入参同单位，即设备 px）。
     *
     * @param navBottomPx `navigationBars` 的底部 inset
     * @param tappableBottomPx `tappableElement` 的底部 inset（3 键导航时 > 0）
     * @param density 屏幕密度（设备 px → dp/CSS px 的换算）
     */
    fun insetPx(navBottomPx: Int, tappableBottomPx: Int, density: Float): Int {
        if (navBottomPx <= 0) return 0
        // 这条带里有可点元素（3 键导航）：整条让出，按钮不能压在界面上。
        if (tappableBottomPx > 0) return navBottomPx
        // 手柄上沿距屏幕底 (nav + handle) / 2；面板底边要对齐它，
        // 面板自己还带 margin 的底外边距，所以视口只需让出：
        //   handleTop − margin
        val handleTop = (navBottomPx + (HANDLE_HEIGHT_DP * density).toInt()) / 2
        val margin = (SHELL_BOTTOM_MARGIN_DP * density).toInt()
        return (handleTop - margin).coerceIn(0, navBottomPx)
    }

    /** 该形态下是否有可见的手势手柄（3 键导航返回 false）。 */
    fun hasGestureHandle(tappableBottomPx: Int): Boolean = tappableBottomPx <= 0
}
