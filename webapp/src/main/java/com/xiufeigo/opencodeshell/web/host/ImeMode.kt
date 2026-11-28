package com.xiufeigo.opencodeshell.web.host

/**
 * IME 呈现策略。
 *
 * 这是 insets 发布链与视口 resize 执行器共享的模式契约。
 *
 * - [VARS]：只把 insets 发成 CSS 变量 / JS 事件，WebView 铺满整窗。
 * - [RESIZE]：额外把 WebView 的**固定高度**压成 `窗口高 − max(nav, ime)`，
 *   让页面里的 `100dvh` / `position:fixed` 跟着一起缩（上游对话框按 `dvh` 布局）。
 *
 * 放成包级 enum（而不是塞在 companion 里）——跨文件引用更省心。
 */
enum class ImeMode {
    /** 只发变量。 */
    VARS,

    /** 发变量 **且** 压矮 WebView。 */
    RESIZE,

    ;

    companion object {
        /** 产品默认：resize 是唯一被验证过的生产路径，vars 仅保留给诊断页切换。 */
        val DEFAULT = RESIZE
    }
}
