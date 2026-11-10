package com.xiufeigo.opencodeshell.web.host

/**
 * 底部系统导航条的**可视让位量**（[InsetsHost] 消费，`NavigationBarVisualTest` 钉住）。
 *
 * ### 为什么不能整条让出（本次缺陷）
 *
 * 手势导航下 `WindowInsetsCompat.Type.navigationBars()` 上报的是**整条上滑热区**
 * （Pixel Tablet 实测 `frame=[0,1536][2560,1600]`，即 64px / 32 CSS px），
 * 而系统只在这条带子的**正中**画那根小白条（实测屏幕 y∈[1564,1571]，即带宽 8px、
 * 上沿距屏幕底 36px = (64+8)/2）。
 *
 * 整条让出的后果：视口被压掉 32 CSS px，会话面板底边还要再让出 shell 自己的
 * 8px 外边距 ⇒ 面板底边离小白条上沿 **23 CSS px**（实测面板 bottom=759、
 * 视口=768、手柄上沿≈782），中间一条纯背景带，看着像悬空。
 *
 * 修后实测（同一台设备，2.0.32）：`--oc-safe-bottom` 由 32 降到 12 CSS px，
 * 视口高 768 → 788，面板底边 759 → 779，手柄上沿 782 ⇒ 只剩 3px 空隙。
 *
 * ### 判据
 *
 * - **3 键导航**：按钮本身就在这条带里（`tappableElement` 的底部 inset > 0），
 *   必须整条让出，否则按钮会压住界面。
 * - **手势导航**：`tappableElement` 为 0（没有可点按钮），这条带只是手势热区，
 *   手柄居中 ⇒ 只让到**手柄上沿再退一点空隙**。手势热区由系统在屏幕边缘优先消费，
 *   页面让出上半段不影响上滑返回。
 *
 * 以系统上报的 `tappableElement` 为准，不猜 OEM；读不到（0）时按手势处理，
 * 因为此时确实没有可点的导航按钮。
 *
 * ### 为什么代码里出现面板的 8px 外边距
 *
 * 面板底边 = 视口底边 − `--shell-bottom-inset`（上游 `shell.tsx` 默认 8px）。
 * 目标是让**面板底边**贴住手柄上沿，所以视口需要让出的量 =
 * 手柄上沿 + 空隙 − 面板外边距。
 */
object NavigationBarVisual {

    /** 手势手柄的绘制高度（Android 12+ 系统常量，4dp）。 */
    private const val HANDLE_HEIGHT_DP = 4

    /** 上游 shell 给会话面板的底部外边距（`--shell-bottom-inset` 默认值）。 */
    private const val SHELL_BOTTOM_MARGIN_DP = 8

    /** 手柄上沿与面板底边之间保留的空隙：小于 1 CSS px 时圆角会压到手柄。 */
    private const val HANDLE_GAP_DP = 2

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
        // 手柄上沿距屏幕底 (nav + handle) / 2；面板底边要落在它上面 gap 处。
        // 面板自己还带 margin 的底外边距，所以视口只需让出：
        //   handleTop + gap − margin
        val handleTop = (navBottomPx + (HANDLE_HEIGHT_DP * density).toInt()) / 2
        val margin = (SHELL_BOTTOM_MARGIN_DP * density).toInt()
        val gap = (HANDLE_GAP_DP * density).toInt()
        return (handleTop + gap - margin).coerceIn(0, navBottomPx)
    }
}
