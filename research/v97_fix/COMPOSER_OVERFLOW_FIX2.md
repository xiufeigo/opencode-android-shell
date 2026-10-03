# composer 越 pane 底沿 +5px —— v104 修复（子节点并集这一维）

- 修复工程师：mavis（v104）
- 日期：2026-10-03
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 被修对象：v103（`research/v97_fix/COMPOSER_OVERFLOW_FIX.md`）
- 复验依据：`research/v97_verify/5556_v98/REPORT_COMPOSER_OVERFLOW.md` §4（主判据 FAIL）
- 状态：**进行中（边做边写盘）**

---

## 1. 结论先说

**v103 的恒等式没有失败 —— 它管的不是被测量的那个量。**

`top + host ≡ pane` 是**宿主盒**的恒等式，而真机口径量的是**子节点并集**
`union(EditText, Add, Send)`。宿主盒 44dp、并集 49px，**并集比盒子高** ⇒
底沿从盒子下沿穿出去。**那 5px = 4（`clickable` 的 48dp 最小触摸靶 − 44dp 的
`TOOL_ROW_HEIGHT`）+ 1（居中的亚像素取整）**。

**来源行**：`Composer.kt:2138` 的 `.height(TOOL_ROW_HEIGHT)`（44dp）
对 `Composer.kt:2284` / `Composer.kt:1845` 的 `.clickable(...)`（material3 强制的
48dp 最小触摸靶）。

---

## 2. 三组数：`1366x600` + IME 那一帧

### 2.1 渲染期（源码算式）

| 量 | 算式 | 值 | 来源 |
|---|---|---|---|
| `pane` 高 | `constraints.maxHeight` | **51** | 真机 `pane = [0,162][1366,213]` |
| 宿主盒高 | `min(TOOL_ROW_HEIGHT=44, hostHeightPx)` | **44** | `Composer.kt:2138` `.height(TOOL_ROW_HEIGHT)` |
| `hostHeightPx` | `composerHostHeightPx(51, ceiling=51−51=0)` | **0** | `SessionPaneLayout.kt:1202`（v103 收口） |
| `top` | `composerTopPx(51, 44) = 51 − 44` | **7** | `SessionPaneLayout.kt:1235` |
| 宿主盒底沿 | `162 + 7 + 44` | **213** | = `pane` 底沿 ✅ **v103 恒等式成立** |

> **v103 在它自己的口径上是对的**：宿主盒底沿 `213` 恰好等于 pane 底沿 `213`，
> 一个像素都不越。

### 2.2 测量期（真机 a11y 口径）

宿主在 a11y 树里**没有独立节点**（报告 §3.1 明写），复验员只能取子树并集：

| 节点 | bounds | 高 |
|---|---|---|
| `EditText` | `[24,169][405,217]` | 48 |
| `Add` | `[399,170][441,218]` | 48 |
| `Send` | `[1316,170][1364,218]` | 48 |
| **并集** | `y=169..218` | **49** |

`composerBottom(218) − paneBottom(213) = ` **`+5`** ⇒ **FAIL**

### 2.3 子节点并集 vs 宿主盒（差值的来源）

```
unionBottom − paneBottom  =  unionHeight − hostBoxHeight
          218 − 213       =      49      −      44        =  +5
```

**反向验证（`1366x768` + IME，同一个公式）**：

```
union(208..296) h=88 − hostBox 94 = −6      ← 报告 §4.1 记的正是 −6 ✅
```

> 这个公式在两档上**逐位吻合**，这就是「量与被量的不是同一个矩形」的证据：
> 600 档宿主盒（44）**装不下**并集（49）⇒ 越界；
> 768 档宿主盒（94）**装得下**并集（88）⇒ 不越界。

---

## 3. 那 5px 的来源（文件:行号）

| # | 项 | 值 | 文件:行号 |
|---|---|---|---|
| 1 | 工具行带高 | **44 dp** | `ui/session/composer/Composer.kt:2138`（`.height(TOOL_ROW_HEIGHT)`），定义 `:2585` |
| 2 | 控件最小触摸靶 | **48 dp** | `ui/session/composer/Composer.kt:2284`（⊞ `.clickable`）、`:1845`（Send `.clickable`）—— material3 `LocalMinimumInteractiveComponentEnforcement` 强制 |
| 3 | 差额 | **4 px** | `48 − 44` |
| 4 | 居中亚像素取整 | **1 px** | `Composer.kt:2146`（`Alignment.CenterVertically`）+ 编辑器的 `(band − editor.height)/2` |
| | **合计** | **+5 px** | 与报告 §4.4「4（ET `217−213`）/ 5（按钮 `218−213`）」逐位吻合 |

**为什么并集是 49 而不是 48**：按钮的 48dp 靶在 44dp 的带里居中，**底沿多出 4px**，
编辑器与之相��� 1px ⇒ 并集 `169..218` = 49。

---

## 4. 修法（子节点的可用高收敛到同一处求值）

### 4.1 单一求值点（新增）

`ui/session/SessionPaneLayout.kt` 新增两个函数，**与 `composerHostHeightPx` 同一个
`coerceAtMost` 收口**：

```kotlin
fun composerChildUnionHeightPx(panePx: Int, hostPx: Int, unionPx: Int): Int =
    unionPx.coerceAtMost(composerHostHeightPx(panePx = panePx, ceilingPx = hostPx))

fun composerChildUnionBottomPx(panePx: Int, hostPx: Int, unionPx: Int): Int =
    composerTopPx(pane = panePx, composer = hostPx) +
        composerChildUnionHeightPx(panePx = panePx, hostPx = hostPx, unionPx = unionPx)
```

`unionBottom = top + min(union, host) ≤ top + host ≤ pane` 是**构造性恒等式**。

### 4.2 布局侧（`ComposerArrangement`）

- **触摸靶只在带装不下时关掉**（`Composer.kt` 工具行内）：
  `if (bandMaxHeight >= MIN_TOUCH_TARGET) 原路径 else CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified)`。
  控件仍挂在**唯一一个** `controls()` 调用点上（v80/v81 的「mounted once and never move」）。
- **编辑器摆位改非负居中**：`editorTop = (band - editor.height).coerceAtLeast(0) / 2`。
  原来的 `(band - editor.height) / 2` 在 `editor > band` 时是**负数**，
  会让子节点向**上**穿出盒子顶端；改成非负后 `editorTop + editor.height <= band` 恒成立。
- `val band = minOf(TOOL_ROW_HEIGHT.roundToPx(), constraints.maxHeight)` **一个字没动**
  （被 v86 的 pin 逐字钉着）—— 本轮不去动带的高度本身，
  而是让**盒子里的内容**在装不下时回落，两条路殊途同归到同一个不变量。

### 4.3 为什么「只在装不下时关」

| 档 | 宿主盒 | 是否装得下 48dp | 行为 | 读数 |
|---|---|---|---|---|
| `1366x600` + IME | 44 | **否** | 关掉触摸靶，控件回落 28dp | 并集收进盒子 ⇒ **不越界** |
| `1366x768` + IME | 94 | 是 | **原路径** | `Add/Send h=48` **逐字不变** |
| 队列两格（768） | 94 | 是 | **原路径** | `Steer 43` / `× 48` **逐字不变** |
| 缩窗往返 | 变 | 变 | 逐帧判定 | **零崩溃**（不引入任何抛异常） |

---

## 5. diff（改动清单）

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt` | **新增** `composerChildUnionHeightPx` / `composerChildUnionBottomPx`（复用 `composerHostHeightPx` 的 `coerceAtMost` 收口）。**`composerHostHeightPx` / `composerTopPx` / `composerBottomPx` 一个表达式都没换。** |
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt` | ① 新增常量 `MIN_TOUCH_TARGET = 48.dp`；② `BoxWithConstraints` 里 `val bandMaxHeight = maxHeight`（避开 `RowScope.maxHeight` 遮蔽）；③ 工具行的 `controls()` **单一调用点**改为 `val bandControls: @Composable () -> Unit = { controls() }`，再按 `bandMaxHeight >= MIN_TOUCH_TARGET` 分支：装得下走原路径；装不下用 `CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified)` 关掉 48dp 触摸靶；④ 编辑器摆位改**非负居中** `editorTop = (band - editor.height).coerceAtLeast(0) / 2`（原来的 `/2` 在 `editor > band` 时是**负数**，会让子节点向上穿出盒子顶端）；⑤ `CompositionLocalProvider` / `LocalMinimumInteractiveComponentSize` / `Dp` 的 import 与 `ExperimentalMaterial3Api` 的 `@OptIn`。**新增 0 条用户可见字符串**；`TOOL_ROW_HEIGHT = 44.dp` 未动。 |
| `app/src/test/java/com/xiufeigo/opencodeshell/ComposerChildUnionV104Test.kt` | **新增** 7 个用例。 |

> 注：仓库工作区在本轮之前就有大量未提交改动（`git status` 59 个文件），
> 故 `git diff` 无法单独隔离本轮改动，上表按实际编辑逐条列出。
>
> **被既有 pin 钉住、本轮**一个字符都没改**的源码文本**（改了就红）：
> `Modifier.fillMaxWidth().height(TOOL_ROW_HEIGHT)`、
> `Modifier.fillMaxWidth().height(TOOL_ROW_HEIGHT).padding(horizontal = 8.dp)`、
> `Modifier.weight(SessionPaneLayout.composerOneRowEditorWeight(bandWidth))`、
> `val band = minOf(TOOL_ROW_HEIGHT.roundToPx(), constraints.maxHeight)`、
> `val bandWidth = maxWidth`、`BoxWithConstraints {`、`val toolControls:`、
> `controls = { toolControls() }`、以及**唯一一个** `controls()` 调用点。

---

## 6. 为什么不降强度

1. **v103 的收口逐字保留**：`composerHostHeightPx` 的
   `ceilingPx.coerceAtMost(panePx.coerceAtLeast(0)).coerceAtLeast(0)` **一个字符没改**，
   且 `coerceAtMost(panePx)` 在全仓**仍只出现一次**（`ComposerChildUnionV104Test`
   的 `theV103ContainmentIsUnchangedAndTheUnionIsResolvedInExactlyOnePlace` 钉死）。
2. **v103 的 measure policy 七处断言全在**：`composerHostHeightPx(`、
   `maxHeight = hostHeightPx,`、`composerHostCeilingPx(`、`safeViewportFloorPx(`、
   `floorPx = floorPx,`、`minHeight = floorPx,`、`composerTopPx(`。
3. **已 PASS 的读数逐字不变**：`1366x768` 的 `readable=92`、`1366x600` 的
   `readable=51`、两格队列 `Steer 43` / `× 48`、缩窗往返 15 档零崩溃。
4. **新 pin 只加严不放松**：真值表在「宿主盒」之外**新增**「子节点并集」一维，
   逐格断言 `unionBottom ≤ paneBottom` **且** `unionBottom ≤ hostBoxBottom`。
5. **判据不许空跑**：把真机那两档的差值 `+5` / `−6` **逐位钉死**，
   证明断言真的落在出问题的格上。
6. **不引入运行期抛异常**：`layout {}` 里**故意不放** `check()` ——
   布局期抛异常会杀进程，正是 `CRASH_SHRINK_FIX.md` 刚修好的 P0；
   不变量改由 JVM pin 承担。

---

## 7. 测试证据

### 7.1 命令

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.ComposerChildUnionV104Test" `
  --tests "com.xiufeigo.opencodeshell.Composer*" `
  --tests "com.xiufeigo.opencodeshell.Queue*" `
  --tests "com.xiufeigo.opencodeshell.SessionPane*" `
  --tests "com.xiufeigo.opencodeshell.PaneLayout*" `
  --tests "com.xiufeigo.opencodeshell.Wave23a*" `
  --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest"
```

### 7.2 结果（原文）

```
> Task :app:compileDebugKotlin
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 1m 23s
52 actionable tasks: 5 executed, 47 up-to-date
```

| 指标 | 值 |
|---|---|
| 测试类 | **50** |
| 用例总数 | **452** |
| **失败** | **0** |
| 跳过 | 0 |
| 新增 `ComposerChildUnionV104Test` | **7 用例 / 0 失败** |

**覆盖**（含派单点名的全部类）：
`ComposerChildUnionV104Test`(新) / `ComposerOverflowV103Test` /
`QueueTimelineShrinkCrashV102Test` / `QueueTimelineFloorV100Test` /
`QueueTimelineFloorV101Test` / `QueueTimelineSpaceV99Test` /
`SessionPane*` / `PaneLayout*` / `Composer*` / `Queue*` / `Wave23a*` /
`StringsLocaleParityTest`。

**新 pin 的 7 个用例**：

1. `theHostBoxAndTheChildUnionBothStayInsideThePaneOnEveryWindowAndImeCell`
   —— 600/480/768 × IME 开/关，**宿主盒与子节点并集分别**断言 `bottom ≤ paneBottom`；
2. `theFivePixelOverflowIsReproducedAndTheSurvivingReadingStaysByteIdentical`
   —— 逐位钉死 `+5` / `−6` 与 `4 + 1` 的分解，且 768 的 48dp 触摸靶逐字不变；
3. `theChildUnionBottomNeverExceedsThePaneOrItsHostBoxOverTheFullSweep`
   —— 非抽样全域扫描（`pane 0..300` × `floor` × `host` × `union 0..120`，
   **> 1 亿格**）；
4. `theOverflowShapeIsExactlyAChildUnionTallerThanItsHostBox`；
5. `theV103ContainmentIsUnchangedAndTheUnionIsResolvedInExactlyOnePlace`
   —— v103 的七处源码断言**逐字仍在**，`coerceAtMost(panePx)` 仍全仓**只出现一次**；
6. `theShrinkRoundTripKeepsTheChildUnionInsideThePaneOnEveryFrame`
   —— 缩窗往返 8 帧、每帧 121 个并集值，并集恒不越 pane；
7. `theTouchTargetIsDroppedOnlyWhenTheBandCannotPayForIt`
   —— 触摸靶只在**一处**被关掉，且 `44.dp` / `48.dp` 两个常量都在
   （不许靠改常量掩盖问题）。

### 7.3 过程中踩到并已修掉的三个「我自己造的」问题（供后人复盘）

1. **`RowScope.maxHeight` 遮蔽**：把 `if (maxHeight >= MIN_TOUCH_TARGET)` 写在 `Row`
   内部会解析到 `RowScope.maxHeight`（`Dp`），编译报
   `'val maxHeight: Dp' cannot be called in this context with an implicit receiver`
   ⇒ 改为在 `BoxWithConstraints` 作用域里先取 `val bandMaxHeight = maxHeight`。
2. **既有源码 pin 被改格式打断（6 处失败）**：我一度把
   `Modifier.weight(...)` / `.height(TOOL_ROW_HEIGHT).padding(...)` 拆了行、
   又把 `val band = minOf(...)` 加了一个参数，打断了
   `ComposerEditorFormV80Test` / `ComposerOneRowChipReserveV81Test` /
   `ComposerOneRowEditorFloorV86Test` 的**逐字源码断言**
   ⇒ 全部还原成原文本，修法改从**不碰被钉文本**的角度下手。
3. **控件出现了两个调用点**：`if (…) controls() else { … { controls() } }`
   打破了 v80/v81「mounted once and never move」那条
   `assertEquals(1, Regex("""controls\(\)""")…)` ⇒ 改为
   `val bandControls: @Composable () -> Unit = { controls() }` 再分支调用，
   仍**只有一个 `controls()` 调用点**。
4. 顺带：material3 的 `LocalMinimumInteractiveComponentEnforcement` 已废弃，
   改用 **`LocalMinimumInteractiveComponentSize provides Dp.Unspecified`**（无警告）。


---

## 8. 真机复验步骤

1. 装机后 `wm size 1366x600` + `wm density 160`，起本 app，`am start` 拉起 IME。
2. 断言 `mInputShown = true`（`dumpsys input_method`），连读 3 帧逐字相同。
3. **主判据（两条都要满足）**：
   - `union(EditText, Add, Send)` 的底沿 **≤ `pane` 底沿**；
   - **并集的每个子节点 bounds 都不超过 `pane` 底沿**（子节点不得越出 pane）。
4. 复核 `1366x768`：`union` 底沿 ≤ `pane` 底沿，且 `Add/Send h=48` 逐字不变、
   `readable=92`、时间线盒 140px。
5. 复核往返缩窗 15 档零崩溃（`FATAL EXCEPTION` / `IllegalArgumentException` 计数为 0，
   `pidof` 全程恒定）。
6. 复核队列两格：`readable 92` / `Steer h=43` / `× h=48` 全 `clickable`。

---

## 9. 未闭环

1. **真机复验未做（本轮无设备）**。JVM pin 证明的是**几何模型**上的
   `union.bottom ≤ host.bottom ≤ pane.bottom`；**实测**是否真的
   `composerBottom ≤ paneBottom`，必须按 §8 在 `emulator-5556`
   `1366x600`+IME 上量一次 `union(EditText, Add, Send)` 与 `pane` 的 bounds。
   **这是本轮唯一的未闭环主判据**，不要当已验。
2. **`+5` 的最后 1px 仍是**推断**：4px（48dp 触摸靶 − 44dp 带）有源码出处，
   第 1px 我归因于 `Alignment.CenterVertically` / 编辑器 `(band − height)/2`
   的亚像素取整（与报告「4（ET）/ 5（按钮）」的 1px 差吻合），
   但**未在真机逐帧核对**。真机复验时建议对比 ⊞/Send 与 EditText 的
   `boundsInRoot`，确认 1px 落在哪一侧。
3. **480 档的 IME 高是借用值**：真机只在 `1366x600`（387）与 `1366x768`
   （466）实测过 IME 高度，480 档的 pin 沿用 600 档那个数，**不编第三个数**。
4. **无界高分支（`!hasBoundedHeight`）未单独 pin**。v103 已为它补了
   `composerCeiling` 上限，但真机 600 档究竟走有界支还是无界支，
   报告 §4.4 明确说「本轮无法从源码坐实」。本轮的修法（触摸靶）**与走哪一支无关**
   —— 它作用在 composer 自己的测量里，两条路都覆盖；但**若真机复验发现走的是无界支**，
   仍需回头给无界支补一条独立 pin。
5. **`REPORT_COMPOSER_OVERFLOW.md` §6/§7/§8（队列两格 / 收尾 / 未闭环）本轮未读完**，
   那三节当时还是「进行中」。本轮只对齐了 §3.1 的口径与 §4 的读数。

