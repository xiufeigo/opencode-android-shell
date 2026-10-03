# TRIPWIRE_HARDENING2.md —— v116：把"数量 ≥ 1"升级为"特定调用指纹"，并让桥参数的**来源**可验

> 任务：补掉 v115 自陈的两条最要命缺口 ——
> ①「加了新的删旧的」仍会绿；②「桥参数变字面量」会名义活实质死。
> 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
> 写权限：仅测试文件 + `research/v97_fix/**`。**生产代码零改动。**

---

## 0. 先确认 Lead 的纠正（以 Lead 为准）

Lead 说 v115 把 R5 的定位写错了，并给了真位置。本轮**逐行复核，Lead 全对**：

| 角色 | Lead 给的位置 | 实测 |
|---|---|---|
| R5 宿主方法 | `ComposerBandTallEnoughV106Test.kt:322` | ✅ `:322-323`，`theFivePixelOverflowIsReproducedOnTheDeviceReadingsAndGoneOnTheNewBand()` |
| R5 记录性 fixture | `:338-341`、`:343` | ✅ `:342-345`（`5, 218 - 213`）、`:347`（`2, 296 - 294`） |
| R5 侦测力来源 | `:347` 的 `Frame(600, 387, bandHeightPx = BAND_AFTER_PX)` | ⚠️ **在 `:351`**（v115 报告 §1.1 已记为 `:347`，是它自己的偏差；Lead 转述沿用了 v115 的行号） |
| 经由 `SessionPaneLayout` | 是 | ✅ `Frame` 私有类 `:122`，其默认值 `:141`/`:146`/`:153` 调生产 |

**判定不变、结论不变**（R5 在 V106、侦测力来自 `Frame(...)` → `SessionPaneLayout`），
仅行号以源码为准。v115 §1.1 表格里 `:347 → :348-358` 与其正文自相矛盾，
本轮统一按实测 `:351` 与 `:352-362`。

---

## 1. 缺口复述（来自 v115 报告 §8#1 / §8#2，如实引用）

> §8#1「校验器是文本级的……它守的是**数量 ≥ 1**，不是**那一条**还在。」
> §8#2「若有人把 `Frame` 的 `pane` / `bandPx` 改成纯字面量，R5 的"活断言"会
> **名义上**活着、实质上不调生产，这条 pin 不会红。」

两条都对。本轮把它们变成**代码**，不是变成注释。

---

## 2. 指纹机制设计（任务 A 第 1 点）

### 2.1 为什么"数数"必然漏

v115 的判据形状是 `live == 0 ⇒ 违规`。它是**一阶量**：只要方法里还存在**任意一条**
活断言，删掉指定那条就是不可见的。把"某条特定调用"钉住，必须引入一个
**对该调用敏感、对邻居不敏感**的量 —— 也就是**指纹**。

### 2.2 指纹三元组

```kotlin
internal data class CallFingerprint(
    val scope: String,            // 指纹只在该 fun/class 内成立
    val callee: String,           // 被调用的生产符号（Frame / SessionPaneLayout.composerTopPx …）
    val args: List<String>,       // 逐个顶层实参的**规范化**文本
    val minProductionArgs: Int,   // 至少几个实参必须是"生产派生"而非字面量
)
```

**规范化**（`canonical`）：折叠空白 → 去掉 `(` `)` `,` `=` 两侧空格。
于是 `rows = 0` 与 `rows=0` 等价（纯格式化提交不会误伤），
而 `0` 改成 `1` 必然失配（语义改动必被拒）。

**实参按顶层逗号切分**（`splitArgs`，跟踪 `()[]{}` 深度）：
`composerTopPx(pane = pane, composer = hostPx)` 是 2 个实参，
而 `ceilingPx = if (oneRow) bandHeightPx else STACKED_NATURAL_PX` 是 1 个 —— 里面的逗号不切。

### 2.3 判据 = 集合包含，不是计数

`validate()` 对每条 pin 要求：**在指定 scope 内，存在一组实参列表与 pin 逐字相等**。
匹配不上 ⇒ `fingerprint-missing`，并把**实际找到的候选**一并打进文案。

这直接关掉缺口 ①：新加的调用在 pin 集合里没有对应项，**补不回来**。

### 2.4 掩码复用（不重造轮子）

`CallFingerprintScanner.mask` 直接调 v115 的 `TripwireValidator.maskNonCode`。
理由：两条校验器必须对"什么算代码"有**同一份定义**；否则 v116 可能被 v115
注释掉的代码满足（或反之），出现互相打架的绿灯。KDoc 里写着
`Frame(600, 387, …)` 依旧买不到任何指纹分。

### 2.5 桥参数来源可验（任务 A 第 2 点 / 任务 B）

两道互补的机制：

1. **`minProductionArgs`**：一条已匹配的调用，若其实参**全是字面量**，仍判
   `hardcoded-argument`。字面量集合 = 数字 / `48.dp` 这类单位 / `true|false|null`。
   **布尔算字面量是刻意的**：`oneRow=true` 不是生产输入。
2. **`BridgeBindingPin`**：对 `Frame` 的**字段**单独钉"必须仍由自身输入派生"。
   `val pane` 必须仍引用 `windowPx / imeBottomPx / paneOverride`；
   `val bandPx` 必须仍引用 `bandHeightPx / hostPx`；
   `val hostPx` 必须仍引用 `composerHostHeightPx / bandHeightPx`。
   改成 `val pane: Int = 51` ⇒ `hardcoded-field`。

两者是**冗余**的：即使 v115 那三个调用原封不动，改字段也会红（实测 §4 变异③）。

### 2.6 五条 pin（对着真实源码写死）

| pin | scope | callee | 实参 | minProd |
|---|---|---|---|---|
| R5 | `fun theFivePixel…NewBand` | `Frame` | `600, 387, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX` | 1 |
| R6 | `fun theBottomPadding…SixHundred` | `SessionPaneLayout.composerTopPx` | `paneHeight768, 94` | 1 |
| F1 | `class Frame` | `composerHostHeightPx` | `panePx=pane, ceilingPx=if(oneRow)bandHeightPx else STACKED_NATURAL_PX` | 1 |
| F2 | `class Frame` | `composerTopPx` | `pane=pane, composer=hostPx` | 2 |
| F3 | `class Frame` | `composerBandTouchTargetDp` | `oneRow=oneRow, band=bandPx.dp` | 2 |

R5 的 `minProductionArgs=1` 之所以 load-bearing：`600 / 387 / rows=0 / oneRow=true`
**全是字面量**，唯一撑住"来自生产"的就是 `BAND_AFTER_PX`。
把它换成 `48` ⇒ 五实参全字面量 ⇒ tripwire 掏空。
（实测：`BAND_AFTER_PX = 48`（`V106:93`），所以这个变异**数值完全等价** ——
V106 自己的 7 个用例**照样全绿**，只有指纹校验器能看见。这就是"名义活、实质死"的字面含义。）

---

## 3. 校验器 diff

新增 `app/src/test/java/com/xiufeigo/opencodeshell/ComposerCallFingerprintV116Test.kt`
（**新增文件，未改任何既有文件**；v115 类与 V106/V109 宿主**一字未动**，见 §6）。

```diff
+internal data class CallFingerprint(scope, callee, args, minProductionArgs) {
+    fun render() = "$scope { $callee(${args.joinToString(", ")}) }"
+}
+
+internal object CallFingerprintScanner {
+    fun mask(src) = TripwireValidator.maskNonCode(src)          // 复用 v115
+    fun canonical(t) = t.replace(Regex("\\s+"), " ")
+                    .replace(Regex("\\s*([(),=])\\s*"), "$1").trim()
+    fun splitArgs(raw): List<String>          // 顶层逗号，跟踪 ()[]{} 深度
+    fun funBody(masked, name): String?        // 复用 TripwireValidator.matchBrace
+    fun classBody(masked, name): String?      // 跳过主构造参数列表
+    fun callsIn(scopeBody, callee): List<List<String>>
+    fun bindingRhs(scopeBody, name): String?
+    fun isLiteralArg(arg): Boolean            // 数字 / 单位 / true|false|null
+    fun productionArgCount(args): Int
+}
+
+internal object CallFingerprintValidator {
+    fun validate(scopeSource, required): List<Violation>          // 集合包含
+    fun validateBindings(scopeSource, className, required): List<Violation>
+}
```

判据核心（对照 v115 的 `live == 0`）：

```diff
-        if (recordOnly.isNotEmpty() && live == 0) { violations += … }
+        for (pin in required) {
+            val body = scopeBodyOf(pin) ?: run { violations += scope-missing; continue }
+            val found = callsIn(body, pin.callee)
+            if (found.none { it == pin.args }) {                       // ← 指纹，不是计数
+                violations += Violation(pin.render(), "fingerprint-missing",
+                    "no call in '${pin.scope}' matches this exact fingerprint; found " +
+                    "${pin.callee} at ${found.map{…}} — an added call does NOT substitute …")
+                continue
+            }
+            if (productionArgCount(pin.args) < pin.minProductionArgs) { // ← 来源可验
+                violations += Violation(pin.render(), "hardcoded-argument", …)
+            }
+        }
```

`callsIn` 的一个细节：调用点前一个字符若是 `字母数字 / _ / .` 则跳过，
所以 `Frame(` 不会误配 `SomeFrame(`，而 `SessionPaneLayout.composerTopPx(` 正常命中。

---

## 4. 反向验证（三条变异的输出原文，**全部实跑**）

三层证据：① 变异字符串喂进纯函数（12 个用例内含 6 个变异用例）；
② **真实文件级注入**（改真源码 → 只跑校验器所在 suite）；③ 还原校验。

### 4.1 变异①：删掉那条特定生产调用

**注入**（真文件 `V106`，删 `:351-362` 共 12 行：那条 `Frame(600, 387, …)`
及其全部下游断言，文件仍可编译）：

```
injected_lines_removed=12
after600_remaining=0
```

**跑**：
```
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ComposerCallFingerprintV116Test"
```

```
ComposerCallFingerprintV116Test > theRealSourcesContainEveryPinnedCallFingerprint FAILED
12 tests completed, 5 failed
BUILD FAILED in 3s
EXIT=1
```

**决定性一行原文**（`AssertionError` message 逐字）：

> `R5: [fun theFivePixelOverflowIsReproducedOnTheDeviceReadingsAndGoneOnTheNewBand { Frame(600, 387, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX) }/fingerprint-missing: no call in 'fun theFivePixelOverflowIsReproducedOnTheDeviceReadingsAndGoneOnTheNewBand' matches this exact fingerprint; found Frame at [768, 466, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX, 768, 466, rows=rows, oneRow=true, bandHeightPx=BAND_AFTER_PX] — an added call does NOT substitute for the pinned one, and editing an argument makes the pin miss on purpose]`

> 抄最短一行：
> `Frame(600, 387, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX) }/fingerprint-missing: no call in 'fun theFivePixel…' matches this exact fingerprint`

注意 `found` 里**仍列着两个 `Frame` 调用**却依旧判红 —— 这正是"不数数"。

### 4.2 变异②：加一条新的、同时删掉旧的（**本轮关键改进，v115 会绿的场景**）

**注入**（真文件 `V106:351`，旧调用换成 `BAND_BEFORE_PX`，并**新增**一条 `Frame(900, …)`）：

```diff
-        val after600 = Frame(600, 387, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)
+        val after600 = Frame(600, 387, rows = 0, oneRow = true, bandHeightPx = BAND_BEFORE_PX)
+        @Suppress("unused") val after900 = Frame(900, 387, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)
```

**跑**（**v115 与 v116 同时跑**，就是为了证明 v115 此刻是绿的）：

```
.\gradlew.bat :app:testDebugUnitTest \
  --tests "com.xiufeigo.opencodeshell.ComposerTripwireIntegrityV115Test" \
  --tests "com.xiufeigo.opencodeshell.ComposerCallFingerprintV116Test"
```

```
ComposerCallFingerprintV116Test > theRealSourcesContainEveryPinnedCallFingerprint FAILED
ComposerCallFingerprintV116Test > everyPinIsActuallyFoundInTheRealSource FAILED
ComposerCallFingerprintV116Test > addingANewCallAndDeletingTheOldOneIsStillReported FAILED
ComposerCallFingerprintV116Test > hardcodingThePinnedCallArgumentIsReported FAILED
ComposerCallFingerprintV116Test > deletingThePinnedCallIsReported FAILED
21 tests completed, 5 failed
BUILD FAILED in 3s
EXIT=1
```

**v115 在同一份被污染的源码上（原文）**：

```
TEST-com.xiufeigo.opencodeshell.ComposerTripwireIntegrityV115Test.xml
tests="9" skipped="0" failures="0" errors="0"
```

⇒ **21 个用例里 5 个红灯全部落在 V116，v115 的 9 个用例一个不红。**
这就是 v115 §8#1 那条缺口的**直接实证**，以及 v116 改进的**直接证据**。

**决定性一行原文**：

> `…{ Frame(600, 387, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX) }/fingerprint-missing: no call in 'fun theFivePixelOverflowIsReproducedOnTheDeviceReadingsAndGoneOnTheNewBand' matches this exact fingerprint; found Frame at [600, 387, rows=0, oneRow=true, bandHeightPx=BAND_BEFORE_PX, 900, 387, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX, 768, 466, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX, 768, 466, rows=rows, oneRow=true, bandHeightPx=BAND_AFTER_PX]`

> 抄最短一行：
> `found Frame at [600, 387, …, bandHeightPx=BAND_BEFORE_PX, 900, 387, …, bandHeightPx=BAND_AFTER_PX, 768, 466, …] — an added call does NOT substitute for the pinned one`

新加的 `900, 387, …` 就在列表里，**仍然红**。

### 4.3 变异③：把实参换成字面量（缺口 2）

**注入**（真文件，三处同时改）：

```diff
-        val after600 = Frame(600, 387, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)
+        val after600 = Frame(600, 387, rows = 0, oneRow = true, bandHeightPx = 48)
-        val pane: Int = (paneOverride ?: (windowPx - CHROME_PX - imeBottomPx)).coerceAtLeast(0)
+        val pane: Int = 51
-        val bandPx: Int = minOf(bandHeightPx, hostPx)
+        val bandPx: Int = 48
```

**跑**：

```
ComposerCallFingerprintV116Test > theRealSourcesContainEveryPinnedCallFingerprint FAILED
ComposerCallFingerprintV116Test > everyPinIsActuallyFoundInTheRealSource FAILED
ComposerCallFingerprintV116Test > deletingTheFacadeFieldIsReported FAILED
ComposerCallFingerprintV116Test > theFacadeFieldsAreStillDerived FAILED
ComposerCallFingerprintV116Test > addingANewCallAndDeletingTheOldOneIsStillReported FAILED
ComposerCallFingerprintV116Test > deletingThePinnedCallIsReported FAILED
12 tests completed, 6 failed
BUILD FAILED in 3s
EXIT=1
```

**行 1 原文（调用层）**：

> `R5: [… { Frame(600, 387, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX) }/fingerprint-missing: no call in 'fun …' matches this exact fingerprint; found Frame at [600, 387, rows=0, oneRow=true, bandHeightPx=48, 768, 466, …]`

> 抄最短一行：
> `found Frame at [600, 387, rows=0, oneRow=true, bandHeightPx=48, 768, 466, …]`

**行 2 原文（桥层，v115 §8#2 原文点名的那处）**：

> `Frame: [class Frame { val pane = 51 }/hardcoded-field: 'val pane' no longer derives from windowPx / imeBottomPx / paneOverride — it is now a hardcoded literal, so every downstream 'live' assertion is nominally alive and substantively dead, class Frame { val bandPx = 48 }/hardcoded-field: 'val bandPx' no longer derives from bandHeightPx / hostPx — it is now a hardcoded literal, so every downstream 'live' assertion is nominally alive and substantively dead]`

> 抄最短一行：
> `class Frame { val bandPx = 48 }/hardcoded-field: 'val bandPx' no longer derives from bandHeightPx / hostPx — it is now a hardcoded literal, so every downstream 'live' assertion is nominally alive and substantively dead`

**这一条最狠的地方**：`bandHeightPx = 48` 与 `BAND_AFTER_PX`（= 48）**数值完全等价**，
所以 `ComposerBandTallEnoughV106Test` 自己的 7 个用例**全绿**、
v115 的 `frameIsAFacadeOverTheProductionLayout`（三个调用都还在）**也全绿**。
**只有 v116 红。** 这就是"名义活着、实质死掉"的完整实证。

### 4.4 单元层变异用例（同一套纯函数，12 个用例内）

| 用例 | 期望 kind | 实测 |
|---|---|---|
| `deletingThePinnedCallIsReported` | `fingerprint-missing` | ✅ |
| `addingANewCallAndDeletingTheOldOneIsStillReported` | `fingerprint-missing` | ✅ |
| `hardcodingThePinnedCallArgumentIsReported` | `fingerprint-missing` + `hardcoded-argument` | ✅ |
| `hardcodingTheFacadeFieldsIsReported` | `hardcoded-field`（pane + bandPx） | ✅ |
| `deletingTheFacadeFieldIsReported` | `fingerprint-binding-missing` | ✅ |
| `hardcodingR6sProductionArgumentIsReported` | `fingerprint-missing` + `hardcoded-argument` | ✅ |
| `aCommentAdvertisingTheCallDoesNotSatisfyThePin` | `fingerprint-missing` | ✅ |
| `everyPinIsActuallyFoundInTheRealSource`（非恒真守卫） | — | ✅ |
| 正常源码（正向 ×2） | `[]` | ✅ |

其中 `hardcodingTheFacadeFieldsIsReported` **内建了对 v115 的反驳**：变异后立刻断言
`FRAME_CALLS` 的校验**仍返回空**（三个生产调用确实原封不动），
证明"红"来自字段来源而非调用存在性 —— 不是同一件事的重复计数。

### 4.5 还原校验

```
restored_hash=291B3D95CAA7028FFF4D5D4E1330B1D7A47464E085263E80D124C47A5094E004
matches_original=True
git_diff_lines=0
```

（注入前记录的 SHA256，还原后逐位一致；`git diff` 对该文件 0 行。）

---

## 5. 为什么不降强度

1. **v115 全部保留，一行未删。** v115 类、V106/V109 宿主文件**未改**。
   v116 是**加法**：新增一个更严的判据，而非替换旧的宽松判据。
   两层并存 ⇒ 旧的护栏（记录腿不许被静默删、fixture 不许改名/`@Ignore`、沿革不许丢）
   仍然生效，同时新增"那一条特定调用"与"实参来源"两层。
2. **宿主用例数与 v114/v115 完全一致**（V106 = 7、V109 = 11），
   ⇒ **没有放宽任何断言，没有新增任何用户可见字符串**（全在测试源文件，无 `R.string`）。
3. **新 pin 只加不减。** 五条 pin 全部对着**今天真实存在的源码**写出，
   没有任何一条是"为了让测试通过"而放宽的：正向用例在注入前就是绿的。
4. **恒真守卫。** `everyPinIsActuallyFoundInTheRealSource` 保证 pin 拼错时会红，
   避免"反向测试因为 pin 本身无效而通过"这种经典自欺。

---

## 6. 测试证据

抢锁：`Set-Content .agent-gradle-lock "v116-tripwire-fingerprint"` → 前台跑完 →
立刻 `rm -- "C:\sensorsdata\main\program\opencode-android-shell\.agent-gradle-lock"`
（**绝对路径字面量**；`LOCK_RELEASED=True`）。
`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`。
**未** assembleDebug / clean / 全量 / 装机。

### 6.1 终验（5 类，含两个宿主类）

```
.\gradlew.bat :app:testDebugUnitTest \
  --tests "com.xiufeigo.opencodeshell.ComposerCallFingerprintV116Test" \
  --tests "com.xiufeigo.opencodeshell.ComposerTripwireIntegrityV115Test" \
  --tests "com.xiufeigo.opencodeshell.SshRowMenuUpstreamParityV115Test" \
  --tests "com.xiufeigo.opencodeshell.ComposerUnionBottomP5V109Test" \
  --tests "com.xiufeigo.opencodeshell.ComposerBandTallEnoughV106Test"
```

原文：

```
BUILD SUCCESSFUL in 4s
52 actionable tasks: 2 executed, 50 up-to-date
EXIT=0
```

| 类 | tests | failures | errors |
|---|---|---|---|
| `ComposerCallFingerprintV116Test`（**本轮新增**） | 12 | 0 | 0 |
| `ComposerTripwireIntegrityV115Test` | 9 | 0 | 0 |
| `SshRowMenuUpstreamParityV115Test` | 7 | 0 | 0 |
| `ComposerUnionBottomP5V109Test`（宿主） | 11 | 0 | 0 |
| `ComposerBandTallEnoughV106Test`（宿主） | 7 | 0 | 0 |
| **合计** | **46** | **0** | **0** |

（v115 报告的基线是 34；本轮 +12 = 46，增量全部来自新类，宿主两类用例数未变。）

### 6.2 三轮注入汇总

| 轮 | 注入 | 结果 |
|---|---|---|
| 变异① | 删 `V106:351-362` | `12 tests completed, 5 failed` / `BUILD FAILED` / `EXIT=1` |
| 变异② | 加一条 + 删旧的 | `21 tests completed, 5 failed`（**5 红灯全在 V116**，v115 `9 tests, 0 failures`）/ `BUILD FAILED` / `EXIT=1` |
| 变异③ | 三处换字面量 | `12 tests completed, 6 failed` / `BUILD FAILED` / `EXIT=1` |

### 6.3 中途红灯（如实记账）

| 轮 | 现象 | 根因 | 处置 |
|---|---|---|---|
| 1 | 编译错 5 处 `Cannot access 'fun mask(src: String)': it is private` | 测试用例要直接调 `mask`，我把它声明成 `private` | 改 `public`（仅测试文件内可见性） |

---

## 7. 未闭环（如实记账，含新发现的缺口）

1. **指纹是文本级的，仍不是编译器级的。** `canonical` 会把
   `rows=0` 与 `rows = 0` 视为同一个指纹。若有人把实参**换个等价写法**
   （如 `BAND_AFTER_PX` → `48`、或 `paneHeight768` → 同值局部量），
   pin 必然失配 ⇒ 报红。这**偏严不偏松**（响亮失败），但也意味着
   **一次无害重构会要求同步改 pin**。这是刻意的取舍：要"那一条调用"可辨，
   就必须认它的写法。未做自动重写工具。
2. **新发现的缺口：`after768` 那条 `Frame(768, 466, …)` 没有被单独 pin。**
   变异①的 `found` 列表暴露了它（还有一条 `rows=rows` 的变体调用）。
   它目前只被"存在至少一条别的 Frame 调用"间接覆盖，不在指纹集合内 ——
   **同样的"加新的删旧的"手法对它仍然有效**。本轮未补，因为补它需要先判定
   哪一条才是 R5 该依赖的那条（`:351` 还是 `:364`），属**判据归属**问题，
   不是校验器问题。建议下轮按同一手法补 R6 侧的完整指纹集。
3. **`minProductionArgs` 是人工赋值，不是推导。** `Frame` 那条设 1、
   `composerTopPx` 那条设 2。设小了会漏（字面量伪装成生产符号仍可能），
   设大了会在等价重构时误伤。当前值按"今天哪些实参是生产派生"**逐条核对**得出
   （见 §2.6 表末列），但没有"自动算出应有值"的机制。
4. **R5 的退化文案仍然比 R6 锐利**（v115 §8#3 结转）：R5 的宣传性注释在方法体内，
   R6 的在签名之上。本轮**未移动**那段注释（会动 v114 记账位置），如实结转。
5. **`classBody` 假设主构造参数列表里没有 `{`。** 今天成立（`Frame` 的参数全是
   `Int / Boolean`）。若将来 `Frame` 的参数里出现 lambda 或带 `= {}` 默认值，
   需改为按深度跳过构造参数。已在注释里写明。
6. **本轮无真机读数**（环境纪律禁止装机）。三条变异都是纯源码结构判定，不需要设备。

---

## 8. 回报（给 Lead）

- **状态**：**两条缺口都补上并实跑验证**，三条变异**全部变红**，生产代码零改动。
- **「三条变异是否都变红（各抄一行原文）」**：**都红**。
  - ① 删掉特定调用 → `12 tests completed, 5 failed` / `BUILD FAILED`，
    `…{ Frame(600, 387, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX) }/fingerprint-missing: no call in 'fun theFivePixel…' matches this exact fingerprint`
  - ② 加新的 + 删旧的 → `21 tests completed, 5 failed`，**而 v115 同场 `tests="9" failures="0"`**，
    `found Frame at [600, 387, …, bandHeightPx=BAND_BEFORE_PX, 900, 387, …, bandHeightPx=BAND_AFTER_PX, 768, 466, …] — an added call does NOT substitute for the pinned one`
  - ③ 实参换字面量 → `12 tests completed, 6 failed`，
    `class Frame { val bandPx = 48 }/hardcoded-field: 'val bandPx' no longer derives from bandHeightPx / hostPx — it is now a hardcoded literal, so every downstream 'live' assertion is nominally alive and substantively dead`
- **「指纹怎么设计的」**：`CallFingerprint(scope, callee, args, minProductionArgs)` ——
  作用域 + 被调生产符号 + **逐个顶层实参的规范化文本**（折叠空白、去 `(`/`,`/`=` 侧空格；
  实参按 `()[]{}` 深度切顶层逗号）。判据是**集合包含**：指定 scope 内必须存在一组实参
  与 pin 逐字相等，**邻居调用补不回来**，且违规文案会把实际候选一并打出。
  另加 `minProductionArgs`（实参全字面量 ⇒ `hardcoded-argument`）与
  `BridgeBindingPin`（`pane`/`bandPx`/`hostPx` 必须仍由自身输入派生 ⇒ `hardcoded-field`），
  两道机制覆盖缺口 2。掩码复用 v115 的 `maskNonCode`，保证两层对"什么算代码"定义一致。
- **改了哪些文件**（全在允许范围内）：
  - **新增** `app/src/test/java/com/xiufeigo/opencodeshell/ComposerCallFingerprintV116Test.kt`（12 用例）
  - 本报告 + `research/v97_fix/_v116_backup/V106.bak`（注入前备份，用于还原校验）
  - **既有文件零改动**：`V106`/`V109`/v115 类注入后已按 SHA256 逐位还原
    （`matches_original=True`，`git diff` 0 行）；**生产代码零改动**
- **命令与用例数**：见 §6 —— **5 类 / 46 tests / 0 failures / 0 errors**，
  `BUILD SUCCESSFUL in 4s`，`EXIT=0`；三轮注入分别
  `12 tests completed, 5 failed` / `21 tests completed, 5 failed` / `12 tests completed, 6 failed`，
  全部 `BUILD FAILED`，`EXIT=1`。
- **Lead 的纠正已复核采纳**：R5 宿主 = `ComposerBandTallEnoughV106Test.kt:322`、
  记录性 fixture = `:342-345` 与 `:347`。**唯一偏差**：侦测力来源那行实测在 **`:351`**
  （v115 报告自身记的 `:347` 与其正文 `:348-358` 矛盾，已按源码统一）。
- **新发现的缺口（建议下轮）**：`:364` 的 `after768` 那条 `Frame(768, 466, …)`
  **未进指纹集合**，对它做"加新的删旧的"仍可能通过（§7#2）。
- **报告路径**：`research/v97_fix/TRIPWIRE_HARDENING2.md`
