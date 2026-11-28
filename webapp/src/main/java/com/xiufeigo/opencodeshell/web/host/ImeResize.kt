package com.xiufeigo.opencodeshell.web.host

import kotlin.math.max
import kotlin.math.min

/**
 * "要压矮多少像素" —— IME 视口兜底的**纯函数**（[InsetsHost] 消费，[ImeViewportResizeTest] 钉住）。
 *
 * ### 为什么需要它（TERM1，2026-10-07）
 *
 * 边到边窗口下布局视口不随键盘变矮（实测：IME 336.38 CSS px 时 `innerHeight` 仍 915），
 * 早先只给 composer 垫了底部留白 ⇒ **终端画布被键盘遮住 310px**。
 * 研究里已预告过这个脆弱点（`research/env/FORMFACTORS_F4.md` §6 问题 3）：
 * "一旦 composer 让位逻辑在某个宽度上失效，**不会退化成 resize 兜底，而是直接被遮挡**"。
 *
 * 所以宿主自己把视口压矮，任何靠视口高度的界面都自动跟着收 —— 不再依赖"每个界面记得让位"。
 *
 * ### 为什么要有"僵死读数"守卫
 *
 * `WindowInsetsCompat.Type.ime()` 是平台每次交付的读数，平台自己也可能是**僵死**的：
 * v77 实测上报 2195px 而真实键盘只有 766px（窗口 2400px，占比 **91%**）。
 * 照抄这个数会把视口压成一条缝、下方留一大块空白。所以占比 ≥ [STALE_RATIO] 时**不压**
 * （退化成"页面自己让位"的老行为，而不是把界面压坏）。
 */
object ImeResize {

    /** 上报的 IME 高度占窗口比例达到这个值就判定为僵死读数（实测事故 91%）。 */
    const val STALE_RATIO = 0.85

    /**
     * 视口压矮的**合并窗口**（ms）：键盘动画期间平台可能逐帧上报不同的 IME 高度，
     * 逐帧改 padding 会让页面里的终端**反复重排**（产品负责人 2026-10-07 报的
     * "进度条闪一下 + 终端字体放大缩小一遍"就是这个形态）。等读数稳定这么久再落一次，
     * 一次开/关只重排一次。首次（还没有已提交值时）立即生效，避免启动时慢半拍。
     */
    const val COMMIT_DELAY_MS = 80L

    /**
     * **真正遮挡页面**的 IME 高度（px）；`0` = 屏幕上没有键盘。
     *
     * 平台的 `ime()` inset 量的是"IME 窗口占到哪"，不是"键盘画到哪"。IME 窗口可见
     * 但不画键盘时（接了实体键盘：MIUI/澎湃只浮一个"拼/英"状态角标），上报值
     * 不超过系统导航带高度。这段高度本来就归系统导航带，页面按
     * [NavigationBarVisual] 已经让过位；照 `max(ime, nav)` 再压一次，手势导航下
     * 视口就从"让到手柄上沿"变成"让出整条导航带" —— 输入框一拿焦点（进会话自动
     * 聚焦 / 点输入框）面板就抬高，点会话内容失焦才落回
     * （2026-11-11 MIUI 平板 + 实体键盘报单形态）。
     *
     * 判据：IME 没有高出系统导航带（`ime <= navBar`）⇒ 导航带以上没有任何键盘
     * 界面 ⇒ 按没有键盘处理。
     *
     * @param imeBottomPx 平台上报的 IME 底部 inset（不可见时传 0）
     * @param navBarBottomPx 系统导航带**整条**的底部 inset（不是可视让位量）
     */
    fun occludingImePx(imeBottomPx: Int, navBarBottomPx: Int): Int =
        if (imeBottomPx > navBarBottomPx) imeBottomPx else 0

    /**
     * 视口底部要让出的 px。`0` = 不动（IME 收起 / 读数不可信 / 几何无效）。
     *
     * 取 `max(ime, nav)`：键盘一定比导航条高，两者重叠时导航条已经被键盘盖住。
     */
    fun bottomPadPx(
        windowHeightPx: Int,
        imeBottomPx: Int,
        navBottomPx: Int,
    ): Int {
        if (windowHeightPx <= 0) return 0
        val pad = max(imeBottomPx, navBottomPx)
        if (pad <= 0) return 0
        // 僵死读数：见类注释（别把一个 91% 的假读数当成键盘）。
        if (pad >= windowHeightPx * STALE_RATIO) return 0
        return min(pad, windowHeightPx - 1)
    }
}
