package com.xiufeigo.opencodeshell.web.host

import kotlin.math.ceil

/**
 * 首启配置框（`WebHostActivity.showSetupDialog()`）的触摸靶度量。
 *
 * ## 为什么单独一个文件
 *
 * `WebHostActivity` 是 `AppCompatActivity` 子类，纯 JVM 单元测试（`:webapp:testDebugUnitTest`）
 * **无法加载**它 —— 就算只想读一个 `const`，类加载也会去解析 `android.*`。
 * 所以把「最小触摸靶」这个**唯一需要被守护的量**放进一个不 import 任何 `android.*` 的
 * 纯 Kotlin object：
 *
 * - 生产侧 `showSetupDialog()` 的两个 `EditText` 从这里取 `minTouchTargetPx()`；
 * - 测试侧 `SetupDialogTouchTargetTest` **直接 import** 它做数值断言。
 *
 * 这样断言的是**生产真正用的那个常量**，而不是对源码做字符串匹配后自己重新推导一遍 ——
 * 「判据」与「守护对象」是同一个值，不会出现两边各写一个 48 而其中一个被改掉的情况。
 *
 * ## 改前实测（回归触发器，勿删）
 *
 * 手机形态 `1080×2400 @420`（`wm density 420` ⇒ `density = 2.625`），原生 `AlertDialog`
 * 动态 `uiautomator` dump，`research/env/PHONE_AVD_VERIFY.md` §6.2：
 *
 * | 控件 | bounds(px) | 尺寸 px | dp |
 * |---|---|---|---|
 * | 密码 EditText | `[112,806][968,924]` | 856 × 118 | 326 × **45** ⚠️ < 48 |
 * | 后端地址 EditText | `[112,924][968,1042]` | 856 × 118 | 326 × **45** ⚠️ < 48 |
 * | 「先不配置」 | `[521,1053][731,1179]` | 210 × 126 | 80 × 48 ✅ |
 * | 「保存并连接」 | `[731,1053][978,1179]` | 247 × 126 | 94 × 48 ✅ |
 *
 * 根因：两个 `EditText` 的 `layoutParams.height` 是 `WRAP_CONTENT` 且**没有 `minHeight`**
 * ⇒ 量出来就是控件的固有文本高度 118px = 118 / 2.625 = **44.95 ≈ 45 dp**，比 48 dp 差 3 dp。
 *
 * [PRE_FIX_INPUT_HEIGHT_PX] = 118 与 [PRE_FIX_INPUT_HEIGHT_DP] = 45 就是这个缺陷的
 * **历史读数**，留在常量里是为了：① 报告可追溯；② 单元测试能拿它反证「118px 确实是 45dp」
 * 这条换算链没被后人改歪。
 */
object SetupDialogMetrics {

    /**
     * Material / 无障碍通用的**最小触摸靶**，也是本仓对触摸靶的硬下限。
     *
     * 对应 `research/env/PHONE_AVD_VERIFY.md` §6.2 里两个已经达到 48 的按钮 ——
     * 本仓判定口径就是这一个数，配置框的输入框此前没达标（45dp）。
     */
    const val MIN_TOUCH_TARGET_DP: Int = 48

    /** 出处：`research/env/PHONE_AVD_VERIFY.md` §5.1 手机形态 AVD 的 `wm density`。 */
    const val VERIFY_DENSITY_DPI: Int = 420

    /** 改前两个 `EditText` 的实测高度（px），来自上述 dump。**回归触发器。** */
    const val PRE_FIX_INPUT_HEIGHT_PX: Int = 118

    /** 改前两个 `EditText` 的实测高度（dp），即 118 / (420/160) = 44.95 ≈ 45。 */
    const val PRE_FIX_INPUT_HEIGHT_DP: Int = 45

    /** `DisplayMetrics.density` = `densityDpi / 160f`。 */
    fun densityOf(densityDpi: Int): Float = densityDpi / 160f

    /**
     * dp → px，用于喂给 `View.setMinHeight`（那个 API 收 px）。
     *
     * 用 [ceil] 而不是 `toInt()`：`(48 * 2.625).toInt()` 在某些 density 下会**截断**
     * （例如 44dp @3.0 = 131.999… ⇒ 131px，差 1px 就不满 48dp 了）。向上取整保证
     * 「声明 48dp」**永远**对应「实测 ≥ 48dp」。
     */
    fun dpToPxCeil(dp: Int, densityDpi: Int): Int = ceil(dp * densityOf(densityDpi)).toInt()

    /**
     * 设备真实 density 下的最小触摸靶高度（px）。`showSetupDialog()` 两个输入框都调它。
     */
    fun minTouchTargetPx(densityDpi: Int): Int = dpToPxCeil(MIN_TOUCH_TARGET_DP, densityDpi)
}
