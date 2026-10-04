# TOUCH_TARGET_FIX — F2：web 壳首启配置框触摸靶 45dp → 48dp

> **一句话结论**：45dp 来自 `WebHostActivity.kt` 里两个 **原生 `EditText`**（不是 HTML）——
> `WRAP_CONTENT` 高度 + **没有 `minHeight`**，量出来就是控件固有文本高度 `118px ÷ 2.625 = 45dp`。
> 已用 `minHeight = 48dp` 修掉（视觉只多 3dp 空白，字号/光标/下划线未动），
> 并加 8 条绊线；两次**真跑**反向验证均变红。回归：`:webapp` 8/8 绿、`:app` 16 类 100/100 绿。

**范围**：只改 `webapp/**` + 本报告。未碰 `app/**`、`_upstream/**`、`.github/**`。
**未碰任何设备**（本轮无 `adb`、无 `emulator-5636`、无 `emu kill`），**未起停后端**。

---

## 1. 定位：45dp 来自哪里

### 1.1 先纠正一处任务书里的假设

任务书提示「`webapp/src/main/assets/www/**` 的 HTML/CSS/JS」。**实测不成立**：
首启配置框**根本不是 HTML 页面内部的东西**，而是 `WebHostActivity` 用代码搭的**原生
`AlertDialog`**。全量搜索证据：

```
Select-String WebHostActivity.kt -Pattern 'AlertDialog|EditText|Button|CheckBox|Switch|...'
L316:        val urlInput = android.widget.EditText(this).apply {
L326:        val passInput = android.widget.EditText(this).apply {
L344:        androidx.appcompat.app.AlertDialog.Builder(this)
L346:            .setView(box)
L348:            .setNegativeButton("先不配置") { _, _ ->
L352:            .setPositiveButton("保存并连接") { _, _ ->
```

`assets/www/index.html` 是 W1 骨架自检**占位页**，与首启配置框无关（详见 §6 未闭环）。

### 1.2 首启链行号（本轮实测源码，HEAD `WebHostActivity.kt` 共 706 行）

| 环节 | 位置 |
|---|---|
| `onCreate` 调起 | `WebHostActivity.kt:196` `startProxyAndLoad(restored)` |
| 无密码 ⇒ 弹框 | `WebHostActivity.kt:214-225` `startProxyAndLoad()`；判定在 **L219** `if (password.isNullOrEmpty())` → **L221** `showSetupDialog(backend)` |
| **配置框实现** | **`WebHostActivity.kt:304-370` `showSetupDialog(defaultBackend)`** |
| 后端默认值 | `host/SecretStore.kt` `DEFAULT_BACKEND_URL = "http://127.0.0.1:4095"` |
| 存盘后起代理 | `WebHostActivity.kt:352-360`（positive）→ `launch(url)` L228-257 |

> `research/env/PHONE_AVD_VERIFY.md` §6.1 记的行号（L214-225 / L304-370）与本轮**逐字一致**，可直接对照。

### 1.3 改前原文（缺陷就在这两段，`git show HEAD:` 取出）

**密码框 / 后端地址框**（HEAD L316-338）：

```kotlin
        val urlInput = android.widget.EditText(this).apply {
            setText(defaultBackend)
            setHint("后端地址")
            isSingleLine = true
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,   // ← 高度 WRAP_CONTENT
            )
            setInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI)
        }                                                              // ← 全程没有 minHeight
        val passInput = android.widget.EditText(this).apply {
            hint = "服务器密码（用户名固定 ${LoopbackProxy.BASIC_USER}）"
            isSingleLine = true
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,   // ← 同样是 WRAP_CONTENT
            )
            // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD：输入时打码。
            setInputType(
                android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
            )
        }                                                              // ← 同样没有 minHeight
```

**根因**：`layoutParams.height = WRAP_CONTENT` 且**没有任何 `minHeight` 下限** ⇒
`EditText` 量到自己的固有文本高度 = 118px ⇒ `118 / 2.625 = 44.95 ≈ 45dp`，
比本仓 48dp 硬要求**差 3dp**。与 `wrap_content` 的"看起来正常"无关，缺的是**下限**。

### 1.4 缺陷证据（真机，勿删）

`research/env/PHONE_AVD_VERIFY.md` §6.2，`emulator-5730`（手机形态 `1080×1920 @420`）动态 dump：

| 控件 | bounds(px) | 尺寸 px | dp | 判定 |
|---|---|---|---|---|
| 密码 EditText | `[112,806][968,924]` | 856 × 118 | 326 × **45** | ⚠️ < 48dp |
| 后端地址 EditText | `[112,924][968,1042]` | 856 × 118 | 326 × **45** | ⚠️ < 48dp |
| 「先不配置」 button2 | `[521,1053][731,1179]` | 210 × 126 | 80 × 48 | ✅ |
| 「保存并连接」 button1 | `[731,1053][978,1179]` | 247 × 126 | 94 × 48 | ✅ |

`wm density 420` ⇒ `density = 420/160 = 2.625`；`118/2.625 = 44.95`。
另见 `research/env/PHONE_AVD.md` §Q3（L218-231）同一读数的首次记录。

---

## 2. 修法：`minHeight`（**没有**用 `TouchDelegate`）

### 2.1 为什么不用"视觉 45dp + 触摸区 48dp 用 padding 撑"

任务书说规范允许就优先用 padding 撑。**本例不能，因为判据必须与守护对象一致**：

产出上面这份证据的是 `uiautomator` dump 的 `bounds` 属性。`TouchDelegate`
（或 `ViewCompat.setTouchDelegate`）**只扩大命中判定矩形，不改变 View 的布局尺寸，
也不改变无障碍节点的 `bounds`**。改完之后重新 dump 仍然是 `118px / 45dp` ——
**产出证据的那把尺子永远看不见修复**，判据与守护对象就此对不上，绊线也无从回归。

而 `minHeight` 会真正参与 `WRAP_CONTENT` 测量（`getSuggestedMinimumHeight()`），
布局盒真的变成 126px，**同一把尺子量得到 48dp**。这是选它的唯一决定性理由。

### 2.2 视觉取舍（明确说明）

| | 改前 | 改后 |
|---|---|---|
| EditText 布局盒高 | 118px / 45dp | **126px / 48dp** |
| 字号 / 行高 / 光标 / 下划线 | 原样 | **原样，一个像素没动** |
| 框内可点/可输入区域 | 45dp | 48dp（+3dp） |
| 两框之间的间距 | 0（紧贴） | 6dp（各多 3dp 内部留白） |
| 对话框总高 | — | +6dp |

**取舍**：视觉盒本身长高 3dp，代价是两框之间出现 6dp 间隙。
`EditText` 的背景是**下划线式**（只画底部一条线），`EditText` 顶部本来就是空白，
所以"变高 3dp"在视觉上等于**在框内补 3dp 内边距**——这也是 Material 的标准做法。
3dp = 0.8mm，肉眼不可辨；对话框总高 +6dp（`research/web/TOUCH_TARGET_FIX.md` §7
引用的 E3 §6.2 教训：IME 弹出时对话框上移 335px，6dp 不会加剧该问题）。
**结论：视觉未被搞坏**，且这是比 `TouchDelegate` 更诚实的做法——尺寸与宣称一致。

### 2.3 diff（`git diff`，14 插入 0 删除）

```diff
--- a/webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt
+++ b/webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt
@@ -30,6 +30,7 @@ import com.xiufeigo.opencodeshell.web.host.PerfMarks
 import com.xiufeigo.opencodeshell.web.host.SecretStore
+import com.xiufeigo.opencodeshell.web.host.SetupDialogMetrics
 import com.xiufeigo.opencodeshell.web.host.WebHostBridge
@@ -300,9 +301,18 @@ class WebHostActivity : AppCompatActivity() {
      * 首次启动的配置对话框。用**原生**对话框而不是让页面自己弹：密码必须在进 JS
      * 之前落到 EncryptedSharedPreferences。
+     *
+     * F2：两个输入框原先是 `WRAP_CONTENT` 且无 `minHeight`，实测只有 **45dp** 触摸靶
+     * （`118px ÷ 2.625`，`research/env/PHONE_AVD_VERIFY.md` §6.2）⇒ 差 3dp 不达 48dp 硬要求。
+     * 现在两者都显式 `minHeight = SetupDialogMetrics.minTouchTargetPx(density)`。
+     * 取 `minHeight` 而不是 `TouchDelegate` 撑触摸区，理由见 [SetupDialogMetrics] 与
+     * `research/web/TOUCH_TARGET_FIX.md` §4（`TouchDelegate` 不改无障碍节点 bounds，
+     * 产出这份缺陷证据的 `uiautomator` 将**看不见**修复，判据与守护对象就对不上了）。
      */
     private fun showSetupDialog(defaultBackend: String) {
         val pad = (16 * resources.displayMetrics.density).toInt()
+        val densityDpi = resources.displayMetrics.densityDpi
+        val minTargetPx = SetupDialogMetrics.minTouchTargetPx(densityDpi)
@@ -322,6 +332,8 @@ class WebHostActivity : AppCompatActivity() {
             setInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI)
+            // F2：45dp → 48dp 触摸靶（WRAP_CONTENT 下的固有文本高度只有 45dp）。
+            minHeight = minTargetPx
         }
@@ -335,6 +347,8 @@ class WebHostActivity : AppCompatActivity() {
                 android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
             )
+            // F2：同上，密码框也抬到 48dp 触摸靶。两个框必须**都**写，漏一个测试就红。
+            minHeight = minTargetPx
         }
```

### 2.4 新增文件 `webapp/src/main/java/.../host/SetupDialogMetrics.kt`（纯 Kotlin，无 `android.*`）

```
MIN_TOUCH_TARGET_DP      = 48      // 判据
VERIFY_DENSITY_DPI       = 420     // 出处：PHONE_AVD_VERIFY.md §5.1
PRE_FIX_INPUT_HEIGHT_PX  = 118     // 回归触发器（历史读数，勿删）
PRE_FIX_INPUT_HEIGHT_DP  = 45      // 同上
densityOf(dpi)           = dpi/160f
dpToPxCeil(dp,dpi)       = ceil(dp * dpi/160)   // 向上取整，防截断
minTouchTargetPx(dpi)    = dpToPxCeil(48, dpi)  // 生产两个 EditText 都调它
```

**为什么单开一个文件**：`WebHostActivity` 是 `AppCompatActivity` 子类，纯 JVM 单测
**加载不了**（读一个 `const` 也会去解析 `android.*`）。把"唯一需要被守护的量"放进
无 `android.*` 依赖的纯 Kotlin object，测试才能**真的 import 它**（见 §4）。

**为什么取 `ceil` 而不是 `toInt()`**：`48dp @ density 2.75 = 131.999…`，
`toInt()` 截断成 131px ⇒ **声明 48dp 但实测 45.33dp 等效的 131px 不足 48dp**。
`ceil` 保证"声明 48dp"永远对应"实测 ≥ 48dp"。这条单独有测试
`dpToPxNeverTruncatesBelowTheDeclaredTarget` 守着（覆盖 160/213/240/320/420/480/560/640 八档密度）。

### 2.5 为什么安全（逐条）

1. **不改任何文案**：diff 里没有新增用户可见字符串；`minHeight` 改的是尺寸不是文字。
2. **不改输入语义**：`setInputType` 一字未动，密码仍打码、URL 仍是 URI 键盘。
3. **不改控件顺序**：`box.addView(passInput)` 先密码后 URL 未动（打开即可输密码，L353-356）。
4. **不改对话框结构**：`setTitle` / `setView` / `setCancelable(false)` / 两个按钮全未动。
5. **不改软键盘策略**：`SOFT_INPUT_ADJUST_RESIZE` 未动（E3 §6.2 那条 335px 上移教训保持）。
6. **只增不减高**：`minHeight` 是**下限**。在 `density ≥ 2.625` 的手机上必为 126px；
   即使某设备 `layoutParams` 给了更大高度，也只会更大，不会更小 ⇒ **不存在把框压小的风险**。
7. **没碰 `:app`**：`git status` 证明 `app/**` 零改动（见 §5.3）。

---

## 3. 全量排查：首启相关控件还有没有不达标的

以 §1.2 的首启链为准，`startProxyAndLoad()` → `showSetupDialog()` / `launch()` 全链清点：

| # | 控件 | 构造位置 | 改前 | 改后 | 判定 |
|---|---|---|---|---|---|
| 1 | 密码 `EditText` | `WebHostActivity.kt:326`（修后 338） | 45dp | **48dp** | 🔧 本轮修 |
| 2 | 后端地址 `EditText` | `WebHostActivity.kt:316`（修后 326） | 45dp | **48dp** | 🔧 本轮修 |
| 3 | 「先不配置」 negative | `:348` appcompat 主题 | 48dp | 48dp | ✅ 本来达标 |
| 4 | 「保存并连接」 positive | `:352` appcompat 主题 | 48dp | 48dp | ✅ 本来达标 |
| 5 | 标题「配置 opencode 后端」 | `:345` 文本 | 不可交互 | — | n/a |

**结论：首启链上"同类输入控件"就是这 2 个 `EditText`，已全量覆盖；其余 2 个可交互控件本就达标。**
不达标项**已清零**。

该结论不靠人工记忆，由 `onlyTheSetupDialogBuildsInteractiveWidgetsOnTheLaunchPath`
自动看守：全文件扫 `EditText(` / `CheckBox(` / `Switch(` / `RadioButton(` / `ImageButton(`，
**要求它们只出现在 `showSetupDialog()` 函数体内**（括号配平抠函数体，不依赖行号）。
以后有人在 `onCreate` 里加第二个输入控件而不经过配置框，这条会红。

---

## 4. 绊线：`webapp/src/test/java/.../SetupDialogTouchTargetTest.kt`

> `webapp` 原本**没有 `src/test/` 源集**（只有 `testImplementation("junit:junit:4.13.2")`）。
> 本轮**新建**该源集（AGP 默认 `src/test/java` 即 `:webapp:testDebugUnitTest` 的来源）。

### 4.1 判据与守护对象为什么是同一个（本仓最看重的一条）

- **判据不是**在测试里另写一个 `48` 再去比 —— 那样两边各改各的、其中一个被改掉测试还绿。
- 测试 `import com.xiufeigo.opencodeshell.web.host.SetupDialogMetrics`，**直接断言生产用的那个常量**。
  判定用的数和生产用的数**物理上是同一个**。
- 但 import 常量只守护"这个数是多少"，没守护"它**被用**了"。所以第二件事是把
  `WebHostActivity.kt` 的 `showSetupDialog()` 函数体**括号配平**抠出来，逐个
  `EditText` 的 `apply { }` 块检查有没有落 `minHeight`。

于是**同一个缺陷（45dp 输入框）**的各个退化路径都有对应看守：

| 后人干的事 | 谁红 |
|---|---|
| 把靶改回 **45dp** | `floorItselfIsAtLeast48dp` + `targetConvertsExactlyAtTheVerifiedDensity` + `fixRaisesTheHistoricalReading` |
| 删掉某个 `EditText` 的 `minHeight` | `everySetupInputAppliesTheMinTarget` |
| 新加第 3 个 `EditText` 忘 `minHeight` | `setupDialogHasNoMoreInputsThanWeGuarded` + `everySetupInputAppliesTheMinTarget` |
| 在首启链别处加可交互控件 | `onlyTheSetupDialogBuildsInteractiveWidgetsOnTheLaunchPath` |
| 换掉 `ceil` 改成截断取整 | `dpToPxNeverTruncatesBelowTheDeclaredTarget` |
| **把历史读数 118px/45dp 一起清掉** | `fixRaisesTheHistoricalReading`（`PRE_FIX_*` 钉死） |

### 4.2 8 条用例

| # | 用例 | 断言什么 |
|---|---|---|
| 1 | `floorItselfIsAtLeast48dp` | `MIN_TOUCH_TARGET_DP >= 48` |
| 2 | `targetConvertsExactlyAtTheVerifiedDensity` | `420dpi ⇒ density 2.625`；`48dp ⇒ 126px ⇒ 恰好 48.0dp` |
| 3 | `fixRaisesTheHistoricalReading` | `118px ⇒ 44.95 ≈ 45dp`（历史读数没被篡改）；45 **确实** < 48；修后 px 严格 > 118px 且 ≥ 48dp |
| 4 | `everySetupInputAppliesTheMinTarget` | 每个 `EditText` 的 `apply` 块都有 `minHeight`（失败时打印整个块原文） |
| 5 | `setupDialogHasNoMoreInputsThanWeGuarded` | 输入控件数量 == 2（新增控件必须同时改这里） |
| 6 | `dpToPxNeverTruncatesBelowTheDeclaredTarget` | 8 档密度下 `dpToPxCeil(48) >= 精确值` 且 == `ceil()` |
| 7 | `frameworkButtonsMeetTheFloor` | 两个按钮读数 126px/48dp ≥ 48；negative 必须在 positive 之前 |
| 8 | `onlyTheSetupDialogBuildsInteractiveWidgetsOnTheLaunchPath` | 可交互控件只在 `showSetupDialog()` 内构造；`startProxyAndLoad()` 仍在 |

用例 7 说明：按钮尺寸由 appcompat 主题决定、**不在本模块源码里**，无 Robolectric 无法重新推导，
按本仓惯例（`ComposerBandA11yReachabilityTest`）**只作钉住读数**，判据仍是 ≥48dp **未放松**。

---

## 5. 反向验证（**真跑**，抄原文）

### 5.1 反向验证 #1：把靶改回 45dp

改 `SetupDialogMetrics.kt`：`const val MIN_TOUCH_TARGET_DP: Int = 48` → `= 45`

```
> Task :webapp:testDebugUnitTest
BUILD FAILED in 3s
24 actionable tasks: 5 executed, 19 up-to-date

================= RAW FAILURE TEXT (reverse verification #1) =================
tests=8 failures=3 errors=0
FAILED: targetConvertsExactlyAtTheVerifiedDensity
  java.lang.AssertionError: F2: 48dp @ density 2.625 must be 126px expected:<126> but was:<119>
FAILED: fixRaisesTheHistoricalReading
  java.lang.AssertionError: F2: post-fix 45.333332 dp must clear 48 dp, got 45.333332
FAILED: floorItselfIsAtLeast48dp
  java.lang.AssertionError: F2: MIN_TOUCH_TARGET_DP is 45 dp — the setup dialog's touch target floor
  must be >= 48 dp. The pre-fix measured height was 45 dp (118px @ density 2.625), which is exactly
  the defect this tripwire exists to keep fixed.
```

`45 × 2.625 = 118.125` ⇒ `ceil` ⇒ **119px** ⇒ `119/2.625 = 45.33dp` ⇒ 三条红。
**注意 119px 与真机实测的 118px 只差 1px（`ceil` 所致）**——说明这条链复现的正是同一个缺陷。

### 5.2 反向验证 #2：删掉**一个** `minHeight`（靶仍是 48，但只修了一个框）

删 `urlInput` 块里的 `minHeight = minTargetPx`（保留注释）：

```
BUILD FAILED in 3s
24 actionable tasks: 5 executed, 19 up-to-date

================= RAW FAILURE TEXT (reverse verification #2) =================
tests=8 failures=1 errors=0
FAILED: everySetupInputAppliesTheMinTarget
  java.lang.AssertionError: F2: setup dialog input #0 has no `minHeight`. Every input in the
  first-launch configuration box must carry the 48dp touch target:
EditText(this).apply {
            setText(defaultBackend)
            setHint("后端地址")
            isSingleLine = true
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI)
            // F2：45dp → 48dp 触摸靶（WRAP_CONTENT 下的固有文本高度只有 45dp）。
        }
```

证明绊线守的是**"两个框都被修了"**，不只是"某个框被修了"。

### 5.3 复原后复跑

```
BUILD SUCCESSFUL in 6s
24 actionable tasks: 24 executed          （--rerun-tasks 强制全量）
com.xiufeigo.opencodeshell.web.SetupDialogTouchTargetTest:
  tests=8 failures=0 errors=0 skipped=0 time=0.089
```

---

## 6. 回归

### 6.1 `:webapp:testDebugUnitTest`（新建源集后首次运行）

```
TEST-com.xiufeigo.opencodeshell.web.SetupDialogTouchTargetTest.xml
  tests=8 failures=0 errors=0 skipped=0 time=0.089
    - everySetupInputAppliesTheMinTarget
    - setupDialogHasNoMoreInputsThanWeGuarded
    - frameworkButtonsMeetTheFloor
    - onlyTheSetupDialogBuildsInteractiveWidgetsOnTheLaunchPath
    - targetConvertsExactlyAtTheVerifiedDensity
    - fixRaisesTheHistoricalReading
    - floorItselfIsAtLeast48dp
    - dpToPxNeverTruncatesBelowTheDeclaredTarget
```

`:webapp` 改前 **0 个用例**（无源集）→ 改后 **8 个**，0 失败。

### 6.2 `:app:testDebugUnitTest --tests`（与 48dp / 触摸靶相关的既有类，**16 个**）

| 类 | tests | fail | err | skip |
|---|---|---|---|---|
| `ComposerBandA11yReachabilityTest` | 5 | 0 | 0 | 0 |
| `ComposerBandTouchTargetV105Test` | 6 | 0 | 0 | 0 |
| `ComposerBandTallEnoughV106Test` | 7 | 0 | 0 | 0 |
| `ComposerBandDeviceTruthTest` | 11 | 0 | 0 | 0 |
| `DesktopTabStripHomeTouchTargetTest` | 4 | 0 | 0 | 0 |
| `HomeTouchTargetTest` | 3 | 0 | 0 | 0 |
| `HomeRowHitTargetTest` | 6 | 0 | 0 | 0 |
| `SessionContextRingTargetTest` | 3 | 0 | 0 | 0 |
| `TabIconTouchTargetRingTest` | 4 | 0 | 0 | 0 |
| `HomeProjectRowCloseA11yTest` | 5 | 0 | 0 | 0 |
| `WorktreeDeleteHitTargetV71Test` | 4 | 0 | 0 | 0 |
| `ChordPaletteReachabilityV82Test` | 5 | 0 | 0 | 0 |
| `DirectoryPickerHomeTargetTest` | 9 | 0 | 0 | 0 |
| `HomeAddProjectTest` | 9 | 0 | 0 | 0 |
| `PrecisionT1And768bV148Test` | 6 | 0 | 0 | 0 |
| `ToolCardPolicyTest` | 13 | 0 | 0 | 0 |
| **合计** | **100** | **0** | **0** | **0** |

```
BUILD SUCCESSFUL in 5s
52 actionable tasks: 1 executed, 51 up-to-date
```

### 6.3 用例数只增不减

- `:webapp` 0 → **+8**。
- `:app` **100 → 100**，`app/**` **一个字节没动**（§6.4 证明）⇒ 只增不减成立。
- **没有删除或放宽任何断言**：本轮只新增 `webapp/src/test/**`（全新文件）与
  `webapp/src/main/**/SetupDialogMetrics.kt`（全新文件）；`WebHostActivity.kt` 为
  **纯增量 14 行、0 删除**。

### 6.4 工作区改动清单（`git status --short`，只看本轮的）

```
 M webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt   (+14 -0)
?? webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/SetupDialogMetrics.kt  (new)
?? webapp/src/test/                                                          (new)
?? research/web/TOUCH_TARGET_FIX.md                                          (new, 本报告)
```

`app/**` / `_upstream/**` / `.github/**` **零改动**。
`research/env/*` 的改动是 E3 那条线的，本轮未触碰。

> **一处需要交代的自查**：`WebHostActivity.kt` 在 HEAD 里有 2 行是 **CRLF**（L85-86
> `EXTRA_PERF_MODE` 与其注释），其余全 LF。我的首次编辑把这两行规范成了 LF，diff 里
> 出现了一段与本次修复无关的 `- / +` 对。已按字节还原（HEAD CR 数 = 2，还原后 CR 数 = 2，
> BOM 状态不变），最终 diff 干净为 `14 insertions(+)`、**0 deletions**。

---

## 7. 未闭环 / 超出本轮范围

### 7.1 未闭环

1. **没有在真机上复测 126px。** 本轮硬约束「不碰设备」（E3 在用手机、`emulator-5636` 是平板不许碰），
   所以 45dp → 48dp 的**设备侧读数**尚未复采。绊线守的是**布局常量与换算**这一层
   （`minHeight` 是 `WRAP_CONTENT` 的测量下限 ⇒ 必然 126px），但**建议**下一轮手机空闲时
   重新 dump 一次 `showSetupDialog` 的两个 `EditText`，确认 `bounds` 高度为 **126px**，
   补进 `research/env/PHONE_AVD_VERIFY.md`。**在此之前，"48dp 已达成"是源码级结论，不是实测级结论。**
2. **按钮读数（126px/48dp）仍是旧帧。** 沿用 §6.2 的 dump，来源真实，但**不是本轮复采**。
   且按钮尺寸由 appcompat 主题决定，不在本模块源码内，无 Robolectric 无法推导 ⇒
   只能钉读数，不能重算。若将来换主题，这条会提醒（但不会自己算出新值）。
3. **`dpToPxCeil` 覆盖的是 8 档常见密度**，未穷举全部 `(dpi/160, 8)`。真实密度是连续量，
   但 `ceil` 的正确性对任意 dpi 都成立（有单测逐档核对 == `ceil()`），风险可接受。

### 7.2 发现但**本轮未改**（明确不在「首启相关控件」范围内，故不静默扩大改动）

`webapp/src/main/assets/www/index.html` 的 4 个按钮（W1 骨架自检**占位页**，非首启）：

```css
/* index.html:52-55 */
button {
  font: inherit; margin-top: 6px; padding: 6px 10px; border-radius: 6px;
  border: 1px solid var(--line); background: transparent; color: var(--fg);
}
```

CSS 盒模型静态推导：内容行高 `14px × 1.5 = 21` + 上下 padding `6+6 = 12` + 上下 border `1+1 = 2`
⇒ **高 35px = 35dp**，**同样 < 48dp**（`index.html` L82/L85/L88/L91 四个 `<button>`）。

- **为什么没顺手改**：① 任务书把枚举范围明确限定为「**首启**相关控件」，
  这 4 个按钮在首启链之外（首启弹的是原生 `AlertDialog`，压根没加载这个页面）；
  ② 改它会动占位页视觉，超出"修首启配置框"；③ 页面文案一个字都不能新增/改写。
  静默扩大改动范围比留一条明确的未闭环更糟。
- **建议改法**（一行，待你定夺）：在 `index.html:52` 的 `button` 规则里加
  `min-height: 48px;`（视觉由 35→48dp，占位页仅自检用，无实际影响）。
- ⚠️ 该 35dp 是**静态推导，未上设备复核**（本轮不碰设备）。
- 另外：官方 UI（上游 `@opencode-ai/app` dist）**不在本仓 `assets/www/` 内**
  （E3 §6.2：`_assets/` 下 250+ 资源由回环代理转发），其内部元素的 48dp 靶区
  属 `research/env/PHONE_AVD.md` §5 遗留项 #5，**本模块无法守护**，不在本轮范围。

### 7.3 设备/后端纪律自证

本轮**零** `adb` 调用、**零** emulator 启停、**零** `emu kill`、**零**后端起停、
未触碰 `emulator-5636`。只用了本地 Gradle（`JAVA_HOME` 按指定
`"C:\Program Files\Android\Android Studio\jbr"`）。gradle 锁已释放，工作区无临时脚本
（本轮**未创建**任何 `.ps1`/临时文件；`research/env/logs/*.ps1` 是 E3 那条线的）。
