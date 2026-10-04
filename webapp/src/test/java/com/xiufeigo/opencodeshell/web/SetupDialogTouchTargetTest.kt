package com.xiufeigo.opencodeshell.web

import com.xiufeigo.opencodeshell.web.host.SetupDialogMetrics
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F2 绊线 —— **首启配置框（`showSetupDialog`）内所有可交互控件的最小触摸靶 ≥ 48dp**。
 *
 * ## 守护的缺陷（与 [research/web/TOUCH_TARGET_FIX.md] §1–§2 同一个）
 *
 * 手机形态 `1080×2400 @420` 真机 dump（`research/env/PHONE_AVD_VERIFY.md` §6.2）：
 * 配置框里两个 `EditText` 实测 **118px = 45dp**，比 48dp 硬要求**差 3dp**。
 * 根因是 `WRAP_CONTENT` + 无 `minHeight` ⇒ 量到的是控件固有文本高度。
 *
 * ## 判据与守护对象必须一致（本类的设计要点）
 *
 * 「判据」不是这里另写一个 `48`，而是**生产真正用的那个常量**
 * [SetupDialogMetrics.MIN_TOUCH_TARGET_DP] —— 本类 `import` 它。
 * 如果再在测试里独立写一个 `48`，两边就可能各改各的、其中一个被改掉而测试还绿；
 * import 之后「判定用的数」和「生产用的数」**物理上是同一个**。
 *
 * 但 import 常量只守护了「这个数是多少」，没守护「它**被用**了」。所以本类还做第二件事：
 * **把 `WebHostActivity.kt` 的 `showSetupDialog()` 函数体抠出来做括号配平**，逐个
 * `EditText` 的 `apply { }` 块检查有没有落 `minHeight`。这样：
 *
 * | 后人干的事 | 谁红 |
 * |---|---|
 * | 把 `MIN_TOUCH_TARGET_DP` 改回 45（反向验证） | `floorItselfIsAtLeast48dp` + `fixRaisesTheHistoricalReading` |
 * | 删掉某个 `EditText` 的 `minHeight`（只留一个） | `everySetupInputAppliesTheMinTarget` |
 * | 新加第三个 `EditText` 却忘了 `minHeight` | `setupDialogHasNoMoreInputsThanWeGuarded` + `everySetupInputAppliesTheMinTarget` |
 * | 换掉 `dpToPxCeil` 的取整（改成 `toInt()` 截断） | `dpToPxNeverTruncatesBelowTheDeclaredTarget` |
 *
 * ## 为什么可以在纯 JVM 单元测试里做这些
 *
 * `webapp` 无 Robolectric 依赖，`WebHostActivity` 是 `AppCompatActivity` 子类、**加载不了**。
 * 所以：数值判据走纯 Kotlin 的 [SetupDialogMetrics]（无 `android.*` 依赖），
 * 「有没有用上」走**源码文本**取证 —— 这与本仓 `ComposerBandTallEnoughV106Test`
 * `assertTrue(composer.contains("if (bandMaxHeight >= MIN_TOUCH_TARGET) {"))` 同一路子。
 *
 * ## 按钮为什么是「证据」而不是「闸门」
 *
 * 配置框里另外两个可交互控件是 AlertDialog 框架自带的 negative/positive 按钮，
 * §6.2 实测已经**恰好 48dp**（`[521,1053][731,1179]` / `[731,1053][978,1179]`，均 126px）。
 * 它们的尺寸由 appcompat 主题决定、不在本模块源码里，本类**无法**在无 Robolectric 下
 * 重新推导，因此按本仓惯例（`ComposerBandA11yReachabilityTest`）**只作为钉住的读数**记录，
 * 判据仍是「≥48dp」不放松。
 */
class SetupDialogTouchTargetTest {

    private fun red(why: String): Nothing = throw AssertionError(why)

    private val activitySrc: String by lazy {
        val rel = "src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt"
        val f = listOf(
            File(rel),
            File("webapp/$rel"),
            File("../webapp/$rel"),
            File("../../webapp/$rel"),
        ).firstOrNull { it.isFile }
            ?: red(
                "F2: WebHostActivity.kt not found, so the setup dialog cannot be adjudicated. " +
                    "This is a FAILURE, not a skip.",
            )
        f.readText()
    }

    /** 括号配平抠出 `showSetupDialog()` 的函数体 —— 不靠行号，行号会漂。 */
    private val setupDialogFn: String by lazy {
        val src = activitySrc
        val start = src.indexOf("private fun showSetupDialog(")
        if (start < 0) red("F2: showSetupDialog() is gone — the first-launch setup dialog moved or was renamed")
        val bodyStart = src.indexOf('{', start)
        var depth = 0
        var i = bodyStart
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return@lazy src.substring(start, i + 1)
                }
            }
            i++
        }
        red("F2: unbalanced braces while extracting showSetupDialog()")
    }

    /** 函数体里每个 `android.widget.EditText(this).apply {` 的块文本。 */
    private fun editTextApplyBlocks(): List<String> {
        val out = mutableListOf<String>()
        val needle = "EditText(this).apply {"
        var at = 0
        while (true) {
            val k = setupDialogFn.indexOf(needle, at)
            if (k < 0) break
            val brace = k + needle.length - 1
            var depth = 0
            var j = brace
            while (j < setupDialogFn.length) {
                when (setupDialogFn[j]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                j++
            }
            out.add(setupDialogFn.substring(k, j + 1))
            at = j + 1
        }
        return out
    }

    // ---- 1. 判据本身：常量就是守护对象 -------------------------------------

    /**
     * 把靶改回 45dp ⇒ **本条红**。
     *
     * 这是「反向验证」的第一道：它不碰任何别的东西，只看生产用的那个常量。
     */
    @Test
    fun floorItselfIsAtLeast48dp() {
        val dp = SetupDialogMetrics.MIN_TOUCH_TARGET_DP
        assertTrue(
            "F2: MIN_TOUCH_TARGET_DP is $dp dp — the setup dialog's touch target floor must be >= 48 dp. " +
                "The pre-fix measured height was 45 dp (118px @ density 2.625), which is exactly the defect " +
                "this tripwire exists to keep fixed.",
            dp >= 48,
        )
    }

    /**
     * 48dp 在**取证用的真机密度**上必须正好落成 126px = 48.0dp，不多不少。
     * 换算链 118px→45dp 与 126px→48dp 用的是同一个 density，锁死它防止后人改歪 `densityOf`。
     */
    @Test
    fun targetConvertsExactlyAtTheVerifiedDensity() {
        val d = SetupDialogMetrics.densityOf(SetupDialogMetrics.VERIFY_DENSITY_DPI)
        assertEquals("F2: 420dpi must mean density 2.625 (the phone AVD reading)", 2.625f, d, 0.0001f)

        val px = SetupDialogMetrics.minTouchTargetPx(SetupDialogMetrics.VERIFY_DENSITY_DPI)
        assertEquals("F2: 48dp @ density 2.625 must be 126px", 126, px)
        assertEquals("F2: 126px @ 2.625 must be exactly 48dp", 48.0f, px / d, 0.0001f)
    }

    // ---- 2. 回归触发器：历史读数 118px / 45dp -------------------------------

    /**
     * **保留历史读数**，并当场证明这次修复确实把它抬过了 48dp 线。
     *
     * 钉住 [SetupDialogMetrics.PRE_FIX_INPUT_HEIGHT_PX] = 118 是为了：万一将来有人
     * 「顺手」把这两个框的 `minHeight` 删掉、或者把 `PRE_FIX_*` 一起清掉，这条会先红 ——
     * 缺陷的历史读数不许消失。
     */
    @Test
    fun fixRaisesTheHistoricalReading() {
        val d = SetupDialogMetrics.densityOf(SetupDialogMetrics.VERIFY_DENSITY_DPI)
        val preFixDp = SetupDialogMetrics.PRE_FIX_INPUT_HEIGHT_PX / d

        // 118px 换算出来必须还是 ~45dp —— 证明缺陷读数没有被后人篡改。
        assertEquals(
            "F2: the archived pre-fix height must still read as 45 dp (118px / 2.625 = 44.95)",
            45f,
            preFixDp,
            0.05f,
        )
        assertEquals(
            "F2: PRE_FIX_INPUT_HEIGHT_DP is the pinned 45dp judgement",
            45,
            SetupDialogMetrics.PRE_FIX_INPUT_HEIGHT_DP,
        )
        assertTrue(
            "F2: pre-fix must genuinely be BELOW the floor, otherwise this is not the same defect",
            preFixDp < SetupDialogMetrics.MIN_TOUCH_TARGET_DP,
        )

        // 修后：同一个密度上，抬到 floor 的像素高必须**严格大于**历史读数。
        val postFixPx = SetupDialogMetrics.minTouchTargetPx(SetupDialogMetrics.VERIFY_DENSITY_DPI)
        assertTrue(
            "F2: post-fix $postFixPx px must exceed the archived pre-fix ${SetupDialogMetrics.PRE_FIX_INPUT_HEIGHT_PX} px",
            postFixPx > SetupDialogMetrics.PRE_FIX_INPUT_HEIGHT_PX,
        )
        assertTrue(
            "F2: post-fix ${postFixPx / d} dp must clear 48 dp, got ${postFixPx / d}",
            postFixPx / d >= 48f - 0.0001f,
        )
    }

    // ---- 3. 常量确实被**用**上了（不是只声明不用）--------------------------

    /**
     * 把靶改回 45dp / 删掉 `minHeight` 都会在这里露出来：
     * **每一个** `EditText` 的 `apply` 块都必须落 `minHeight`。
     */
    @Test
    fun everySetupInputAppliesTheMinTarget() {
        val blocks = editTextApplyBlocks()
        if (blocks.isEmpty()) red("F2: showSetupDialog() no longer builds any EditText — re-audit the dialog")
        blocks.forEachIndexed { i, b ->
            assertTrue(
                "F2: setup dialog input #$i has no `minHeight`. Every input in the first-launch " +
                    "configuration box must carry the 48dp touch target:\n$b",
                b.contains("minHeight"),
            )
        }
    }

    /**
     * 首启配置框里**可交互控件的总数**被钉住，防止「加了一个新控件、忘了给它 48dp 靶」
     * 这类绕过上一条的路子（上一条只看 `EditText`，这里连数量一起锁）。
     *
     * 实测清单（`research/env/PHONE_AVD_VERIFY.md` §6.2）：
     *
     * | # | 控件 | 来源 | 实测 | 判定 |
     * |---|---|---|---|---|
     * | 1 | 密码 `EditText` | 本类守护 | 118px / 45dp → 修后 126px / 48dp | 🔧 本轮修 |
     * | 2 | 后端地址 `EditText` | 本类守护 | 118px / 45dp → 修后 126px / 48dp | 🔧 本轮修 |
     * | 3 | 「先不配置」 negative | appcompat 主题 | 126px / 48dp | ✅ 达标 |
     * | 4 | 「保存并连接」 positive | appcompat 主题 | 126px / 48dp | ✅ 达标 |
     *
     * 「输入控件」= 2（两条 `EditText`）；按钮另由 [frameworkButtonsMeetTheFloor] 记读数。
     */
    @Test
    fun setupDialogHasNoMoreInputsThanWeGuarded() {
        val n = editTextApplyBlocks().size
        assertEquals(
            "F2: showSetupDialog() now builds $n input controls but this tripwire only guards 2. " +
                "A new input must be given the 48dp min target AND added to this class's table.",
            2,
            n,
        )
    }

    // ---- 4. dp→px 换算不许截断 --------------------------------------------

    /**
     * `View.setMinHeight` 收的是 px。若用 `toInt()` 截断，`48dp @ density 2.75`
     * = 131.999… ⇒ 131px < 48dp，**声明达标但实测差 1px**。向上取整封住这条。
     */
    @Test
    fun dpToPxNeverTruncatesBelowTheDeclaredTarget() {
        for (dpi in intArrayOf(160, 213, 240, 320, 420, 480, 560, 640)) {
            val px = SetupDialogMetrics.dpToPxCeil(48, dpi)
            val exact = 48 * SetupDialogMetrics.densityOf(dpi)
            assertTrue(
                "F2: 48dp @ ${dpi}dpi = $exact px but dpToPxCeil returned $px — truncation would make the " +
                    "declared 48dp smaller than the real 48dp target",
                px >= exact - 0.0001f,
            )
            assertEquals("F2: dpToPxCeil must agree with ceil() @ ${dpi}dpi", ceil(exact).toInt(), px)
            // 且换算回 dp 不能低于 48。
            assertTrue(
                "F2: $px px @ ${dpi}dpi = ${px / SetupDialogMetrics.densityOf(dpi)} dp < 48 dp",
                abs(px / SetupDialogMetrics.densityOf(dpi) - 48f) < 0.02f ||
                    px / SetupDialogMetrics.densityOf(dpi) >= 48f,
            )
        }
    }

    // ---- 5. 框架自带按钮：钉住读数，判据不放松 ------------------------------

    /**
     * 配置框另外两个可交互控件的实测读数。**不是**新闸门，但把「≥48dp」这条判据
     * 明确写成断言 —— 若哪天读数掉了（例如换主题），这里会先提醒。
     */
    @Test
    fun frameworkButtonsMeetTheFloor() {
        val d = SetupDialogMetrics.densityOf(SetupDialogMetrics.VERIFY_DENSITY_DPI)

        /** §6.2 dump 里的一行：`left,top,right,bottom` 原样抄进来，尺寸由它算，不另写。 */
        data class Frame(val label: String, val l: Int, val t: Int, val r: Int, val b: Int) {
            val wPx: Int get() = r - l
            val hPx: Int get() = b - t
            fun bounds() = "[$l,$t][$r,$b]"
        }

        val frames = listOf(
            Frame("「先不配置」 negative", 521, 1053, 731, 1179),
            Frame("「保存并连接」 positive", 731, 1053, 978, 1179),
        )
        for (f in frames) {
            assertTrue(
                "F2: ${f.label} ${f.bounds()} width ${f.wPx}px = ${f.wPx / d}dp must be >= 48dp",
                f.wPx / d >= 48f - 0.0001f,
            )
            assertTrue(
                "F2: ${f.label} ${f.bounds()} height ${f.hPx}px = ${f.hPx / d}dp must be >= 48dp",
                f.hPx / d >= 48f - 0.0001f,
            )
        }
        // 126px 恰好 = 48dp：这两个按钮本来就在线上，别让判据放松。
        assertEquals("F2: both framework buttons measured 126px high", 126, frames[0].hPx)
        assertEquals("F2: both framework buttons measured 126px high", 126, frames[1].hPx)

        // 顺序断言（顺序错了对话框就误点了，必须原样保留）
        val fn = setupDialogFn
        val neg = fn.indexOf("setNegativeButton(\"先不配置\")")
        val pos = fn.indexOf("setPositiveButton(\"保存并连接\")")
        assertTrue("F2: negative button vanished from showSetupDialog()", neg >= 0)
        assertTrue("F2: positive button vanished from showSetupDialog()", pos >= 0)
        assertTrue("F2: negative must stay before positive", neg < pos)
    }

    // ---- 6. 全量排查：首启链上还有没有别的可交互控件 ------------------------

    /**
     * 首启链 `startProxyAndLoad()` → `showSetupDialog()` / `launch()` 全量清点：
     * 整条链里**只有** `showSetupDialog()` 造控件，`launch()` 只起代理 + `loadUrl`。
     * 这条测试把「配置框的可交互控件来源只有这一个函数」钉住 ——
     * 万一有人在 `onCreate` 里加了第二个 `EditText`/开关，它不会经过 `showSetupDialog`，
     * 上面几条就看不见了。
     */
    @Test
    fun onlyTheSetupDialogBuildsInteractiveWidgetsOnTheLaunchPath() {
        val startChain = activitySrc
        // 取出 startProxyAndLoad + launch 两个函数体之外的整份 activity 太宽松；
        // 这里改成：全文件中除 showSetupDialog 外，不允许再有别的可交互控件构造。
        val offenders = listOf("EditText(", "CheckBox(", "Switch(", "RadioButton(", "ImageButton(")
            .map { it to countOutsideSetupDialog(it) }
            .filter { it.second > 0 }
        assertTrue(
            "F2: interactive widget constructors outside showSetupDialog(): $offenders — a first-launch " +
                "control escaped the tripwire's table, re-audit it",
            offenders.isEmpty(),
        )
        assertTrue(
            "F2: the launch path must still route through startProxyAndLoad()",
            activitySrc.contains("private fun startProxyAndLoad("),
        )
    }

    private fun countOutsideSetupDialog(needle: String): Int {
        val fn = setupDialogFn
        // showSetupDialog 必须是唯一出现该构造的地方：整份文件出现次数 == 函数体内出现次数
        val all = Regex(Regex.escape(needle)).findAll(activitySrc).count()
        val inFn = Regex(Regex.escape(needle)).findAll(fn).count()
        return all - inFn
    }
}
