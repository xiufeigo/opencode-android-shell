package com.xiufeigo.opencodeshell.web.host

/**
 * 底部系统导航条的**可视让位量**（[InsetsHost] 消费，`NavigationBarVisualTest` 钉住）。
 *
 * ### 为什么不能整条让出（2026-11-10）
 *
 * 手势导航下 `WindowInsetsCompat.Type.navigationBars()` 上报的是**整条上滑热区**
 * （Pixel Tablet 实测 `frame=[0,1536][2560,1600]`，即 64px / 32 CSS px），
 * 而系统只在这条带子的**正中**画那根小白条（实测屏幕 y∈[1564,1571]，即带宽 8px、
 * 上沿距屏幕底 36px = (64+8)/2）。
 *
 * 整条让出的后果：视口被压掉 32 CSS px，会话面板底边还要再让出 shell 自己的
 * 8px 外边距 ⇒ 面板底边离小白条上沿 **23 CSS px**，中间一条纯背景带，看着像悬空。
 *
 * 只动这一段距离：让**白色会话底框**贴住小白条上沿。官方 UI 的输入框、
 * `session-composer-dock` 的 `pb-3`、消息区和输入区之间的缝隙一律不改。
 *
 * 判据
 *
 * - **3 键 / 2 键**：按钮在这条带里，必须整条让出。
 * - **手势导航**：这条带只是上滑热区，面板底边对齐手柄上沿。
 *
 * 手势 vs 3 键**优先**看 [NavigationBarMode]（系统设置，首帧就准）。
 * 只靠 `tappableElement` 会在首帧把它抄成整条 `navigationBars`，会话面板先按
 * 整条让位悬空，过一会 insets 稳定才降下来。读不到系统设置时才退回
 * `tappableElement`：底部 inset > 0 当 3 键，= 0 当手势。
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
     * @param tappableBottomPx `tappableElement` 的底部 inset（3 键导航稳定后 > 0）
     * @param density 屏幕密度（设备 px → dp/CSS px 的换算）
     * @param navigationMode [NavigationBarMode.read] 的结果；未知时退回 tappable
     */
    fun insetPx(
        navBottomPx: Int,
        tappableBottomPx: Int,
        density: Float,
        navigationMode: Int = NavigationBarMode.UNKNOWN,
    ): Int {
        if (navBottomPx <= 0) return 0
        val threeButton = when (navigationMode) {
            NavigationBarMode.THREE_BUTTON, NavigationBarMode.TWO_BUTTON -> true
            NavigationBarMode.GESTURAL -> false
            else -> tappableBottomPx > 0
        }
        // 3 键 / 2 键：按钮在这条带里，整条让出。
        if (threeButton) return navBottomPx
        // 手柄上沿距屏幕底 (nav + handle) / 2；面板底边要对齐它，
        // 面板自己还带 margin 的底外边距，所以视口只需让出：
        //   handleTop − margin
        val handleTop = (navBottomPx + (HANDLE_HEIGHT_DP * density).toInt()) / 2
        val margin = (SHELL_BOTTOM_MARGIN_DP * density).toInt()
        return (handleTop - margin).coerceIn(0, navBottomPx)
    }
}
