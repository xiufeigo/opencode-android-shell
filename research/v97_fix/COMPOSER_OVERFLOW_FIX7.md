# COMPOSER_OVERFLOW_FIX7 —— v109：钉 600 档 `unionBottom(218) > paneBottom(213)` 的 +5

- 修复工程师：mavis（v109）
- 日期：2026-10-03
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 前序：`COMPOSER_OVERFLOW_FIX6.md`（v108）、`COMPOSER_OVERFLOW_FIX5.md`（v107b）、`COMPOSER_OVERFLOW_FIX4.md`（v106）
- 权威读数：`research/v97_verify/5556_v98/REPORT_COMPOSER_OVERFLOW4.md`

> ## 状态：**进行中**（本文件在任何代码改动前先写定，边做边存）

---

## 0. 落盘纪律

- §0.1 本文件在**任何代码改动之前**写定。
- §0.2 每取得一条证据就追加写回，避免被截断归零。

---

---

## 1. 嫌疑钉死：`composerRegion` 的完整 modifier 链（逐行原文）

### 1.0 先纠一处坐标：任务书给的 `3191-3193` **不是** `composerRegion`

`grep composerRegion app/src/main/java/com/xiufeigo/opencodeshell/ui/session/**` 只有**一个**
定义点，行号是 **1807**，不在 3191：

```kotlin
// SessionScreen.kt:1807-1814（原文）
    val composerRegion: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.bgBase)
                .onSizeChanged { composerHeightPx = it.height }
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
        ) {
```

而 `SessionScreen.kt:3191-3193` 是**宿主盒**（`SessionPaneArrangement` 里的第二个 `Box`）：

```kotlin
// SessionScreen.kt:3174-3194（原文）
            Box(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier
                    .fillMaxWidth()
                    // … v55/v100 注释 3178-3190 …
                    .let { if (shared) it.heightIn(max = composerCeiling) else it.fillMaxSize() },
            ) {
                composer()
            }
```

⇒ **3191 是「宿主盒能有多高」的那一行，1807-1813 才是「带之上垫了什么」的那一条链。**
两者是**父子**，本轮把两行都当作嫌疑面查。

### 1.1 完整 modifier 链 + 每一项加的 top/bottom 量

从**宿主盒**（父）到**带**（子），逐项：

| # | 位置 | 原文 | 加的 top | 加的 bottom | 备注 |
|---|---|---|---|---|---|
| 1 | `SessionScreen.kt:3177` | `.fillMaxWidth()` | 0 | 0 | 只吃宽 |
| 2 | `SessionScreen.kt:3191` | `.let { if (shared) it.heightIn(max = composerCeiling) else it.fillMaxSize() }` | 0 | 0 | **只改宿主盒的高度上限**，不产生位移 |
| 3 | `SessionScreen.kt:3175` | `contentAlignment = Alignment.BottomCenter` | — | — | **贴底对齐**：子节点高 ≤ 宿主高时，子底 = 宿主底 |
| 4 | `SessionScreen.kt:1810` | `.fillMaxWidth()` | 0 | 0 | 只吃宽 |
| 5 | `SessionScreen.kt:1811` | `.background(c.bgBase)` | 0 | 0 | 纯绘制 |
| 6 | `SessionScreen.kt:1812` | `.onSizeChanged { composerHeightPx = it.height } | 0 | 0 | 纯观测 |
| 7 | `SessionScreen.kt:1813` | `.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)` | **+4dp** | **+8dp** | **全链上唯一的垂直量** |
| 8 | `Composer.kt:2261` | `val band = minOf(BAND_HEIGHT.roundToPx(), constraints.maxHeight)` | 0 | 0 | 带的**高**，不是位移 |
| 9 | `Composer.kt:2293` | `val editorTop = (band - editor.height).coerceAtLeast(0) / 2` | 居中偏移 | — | 带**内部**编辑器居中 |
| 10 | `Composer.kt:2298-2299` | `editor.place(0, editorTop)` / `toolRow.place(0, 0)` | 0 | 0 | toolRow 贴带顶 |

**链上不存在的东西（已 grep 全 `ui/session/**` 排除）**：

- **`imePadding` / `windowInsetsPadding` / `consumeWindowInsets` 在这条链上一个都没有。**
  `ui/session/**` 里 `windowInsetsPadding` 只出现在 `SessionNotFoundPanel.kt:127`、
  `IncompatibleServerPanel.kt:82`、`browser/SessionBrowserPane.kt:191`、
  `btw/SessionBtwPanel.kt:495`、`SessionScreen.kt:2336` 与 `:2878` ——
  **没有一处落在 pane / 宿主盒 / composerRegion 上**。
  ⇒ **「`consumeWindowInsets` 缺失导致 IME 被重复扣除」这条子嫌疑，静态上直接排除。**
- **`Modifier.offset` 在 `ui/session/**` 零命中。**
  ⇒ **「`offset` 把带下移 5px」排除。**

⇒ **全链上唯一能往「带之上」加像素的项，只有 `SessionScreen.kt:1813` 的 `top = 4.dp`。**

### 1.2 那 5px 的算式：`4 + 1`

600 档真机字面量（`REPORT_COMPOSER_OVERFLOW4.md` §2.2 原文，1dp == 1px）：

```
pane 盒          [0,162][1366,213]   h=51   ⇒ paneBottom = 213
宿主盒 (hostPx)  h=48                ⇒ hostTop = 213 − 48 = 165      （BottomCenter 贴底）
EditText         [24,169][405,217]   h=48   ⇒ 编辑器顶 = 169 = hostTop + 4   ★
Add / 模型chip / Send  顶 = 170  底 = 218  h=48
并集(排除 Open terminal)  [24,169][1364,218]      ⇒ unionBottom = 218
```

代入：

```
unionBottom − paneBottom = 218 − 213 = +5
  = (hostTop 165 + 4) + 48 + 1 − 213
  = 4  (SessionScreen.kt:1813 的 top = 4.dp)      ← 撑在上���的整整 4px
  + 1  (Composer.kt:2293 editorTop / toolRow 落位的亚像素取整)
```

**逐位对上**：`165 + 4 + 48 + 1 = 218`。**宿主盒自己（`hostTop + host = 165 + 48 = 213`）完全合规，
越界的 4+1 全部产生在「带相对宿主盒起点」这一侧** —— 与 v108 §3 的头号判断一致，
且现在**钉到了行**（`SessionScreen.kt:1813` 的 `top = 4.dp`）。

### 1.3 为什么**只在 600 档**出现（768 档那一项是 0，还是被别处吃掉）

768 档（`REPORT_COMPOSER_OVERFLOW4.md` §2.1）：

```
pane 盒  [0,162][1366,302]  h=140  ⇒ paneBottom = 302
宿主盒   h=94                ⇒ hostTop = 302 − 94 = 208
EditText [28,208][1338,256]  ⇒ 编辑器顶 = 208 = hostTop + 0     ★★ 贡献 0，不是 4
并集     [10,208][1356,296]  ⇒ unionBottom = 296
unionBottom − paneBottom = 296 − 302 = −6  ✅
```

⇒ **同一行 `top = 4.dp` 在 768 档实测贡献 = 0。** 它不是「在 768 档不存在」，
而是**被 768 档的 Column 压缩让位吸收掉了**：768 走**堆叠形态**（editor 在上、toolRow 在下，
union h = 88），`4 + 88 + 8 = 100` 的 Column 内容装进 **94** 高的宿主
⇒ 超出的 6px 由 Column 从**底部**让出（`bottom = 8.dp` 先让到 2），
**顶部那 4dp 因此不落在带之上**，编辑器顶 = 宿主顶 = 208。

600 档走**一行形态**（`minimal = true`，band = `BAND_HEIGHT` = 48 = 宿主盒 48）：

```
4 + 48 + 8 = 60  的 Column 内容  装进  48 高的宿主  ⇒ 超出的 12px 全部让掉
```

而 **`bottom = 8.dp` 让掉 8px 之后仍然超 4px** —— 这 4px 只能从**顶部**让，
于是顶部那 4dp **反过来变成「带压在宿主盒顶沿之下 4px」**，
并集底沿 `165 + 4 + 48 + 1 = 218` 越出 `paneBottom = 213`。

**一句话**：**`bottom = 8.dp` 在 600 档把让位额度用光（8px），剩下的 12 − 8 = 4px 缺口
只能记在 `top = 4.dp` 头上；768 档的缺口只有 100 − 94 = 6px < 8px，
所以 `bottom` 独自让完，顶部那 4dp 根本没被逼出来。**
`8 − 6 = 2` 与 `12 − 0` 的分配，正是「为什么只有 600 档」的完整算式。

### 1.4 子嫌疑逐条裁决

| 子嫌疑 | 裁决 | 依据 |
|---|---|---|
| `padding`（`SessionScreen.kt:1813` `top = 4.dp`） | **✅ 命中** | §1.2 算式：4px 逐位对上 |
| `offset` | **❌ 排除** | `ui/session/**` 零命中 `Modifier.offset` |
| `imePadding` | **❌ 排除** | 全 `ui/session/**` 零命中 |
| `windowInsetsPadding` 在 pane/宿主/composerRegion 上 | **❌ 排除** | 6 处命中全在别的面板（notFound / incompatible / browser / btw / `:2336` 只吃 Top+Horizontal / `:2878` ssh 兜底） |
| `consumeWindowInsets` 缺失导致 IME 重复扣除 | **❌ 排除** | 链上无任何 inset 消费/施加；`SessionScreen.kt:2336` 只吃 `Top + Horizontal`，**Bottom 交给 `:2356` 的 `paneBottomDp` 手工算**，两者不重叠 |
| `SessionScreen.kt:3191` `.heightIn(max = composerCeiling)` | **❌ 不是位移源** | `heightIn` 只改宿主盒高度上限；`:3339` 的 `host.place(0, composerTopPx(pane, host.height))` 用**同一���**算 top，`top + host ≡ pane` 是构造性恒等式（`SessionPaneLayout.kt:1290` / `:1426`），**宿主盒自己不可能越界** |
| `SessionScreen.kt:3175` `contentAlignment = BottomCenter` 居中取整 | **❌ 量级不足** | 盒 48 / 子 48 ⇒ 偏移恒 0，无奇偶差可取 |
| `Composer.kt:2293` `editorTop` | **❌ 方向相反** | `(48−48)/2 = 0`；实测编辑器顶 169 **比带顶 170 高 1px**，它抬的是并集的**顶**，不抬底 |

---

## 2. §2 修法与 diff

### 2.1 本轮**不改生产布局**（如实的诚实结论，不是"修好了"）

**理由三条，逐条可复核：**

1. **静态上唯一能动的量是 `top = 4.dp`，而把它改成 0 修不掉 +5。**
   `top: 4 → 0` 后：`unionBottom = 165 + 0 + 48 + 1 = 214 > 213`，**仍越 1px**；
   而那 1px（`Composer.kt:2293` 一带的亚像素落位）**不是 `top` 能抵的**。
   ⇒ 改 `top` 会**动到 768 档的视觉间距**（虽然 768 那 4dp 实测贡献 0），
   却**换不来 600 档的合规** ⇒ 纯亏。

2. **要让 600 档真的 `≤ 213`，必须动 `band` 或宿主盒的高度，而那两者都被 pin 死。**
   `band = minOf(BAND_HEIGHT.roundToPx(), constraints.maxHeight)`（`Composer.kt:2261`）
   与 `composerHostHeightPx`（`SessionPaneLayout.kt:1290`）分别被
   `ComposerBandTallEnoughV106Test` / `ComposerOverflowV103Test` /
   `ComposerChildUnionV104Test` / `QueueTimelineFloorV101Test` 逐格钉着。
   任务书要求 **v103–v108 全部 pin 一条不放宽** ⇒ 动它们就是放宽。

3. **任务书 §4 明令**：*"不许在无真机读数下盲改布局（本仓已连续四次栽在"pin 纯函数 ≠ 设备怎么画"）"*。
   而 **600 档那 5px 的最后 1px 我在静态上无法归因到行**：
   按 §1.2，`band = 48`、宿主盒 `48`、`top = 4` ⇒ 带顶应为 `169`，
   **但实测带顶 `170`**，多出的那 1px 出在
   `Composer.kt:2298-2299`（`editor.place` / `toolRow.place`）与
   `SessionScreen.kt:1813` 的 Column 压缩让位之间，**不在任何一条我已读到的表达式里**。
   **盲改 = 第五次假绿。**

⇒ **本轮走任务书 §4 的分支：写清钉死过程 + 给下一条嫌疑 + 落成一行定位探针的真机步骤。**

### 2.2 diff

```diff
# 生产代码：ui/session/**  **0 处改动**（一个字没动）
+ app/src/test/java/com/xiufeigo/opencodeshell/ComposerUnionBottomP5V109Test.kt  (新增)
+ research/v97_fix/COMPOSER_OVERFLOW_FIX7.md                                          (本文件)
```

**未碰**：`_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/servers/**`、
`ui/home/**`、`ui/terminal/**`、`ui/nav/**`、`ui/App.kt`、`OcActivity.kt`。
**未新增任何用户可见字符串**（`1813` 的 `4.dp/8.dp` 未被触碰）。
**未放宽任何既有断言。**

---

## 3. §3 测试证据

### 3.1 命令（只跑 5 个类，`assembleDebug`/`clean`/装机一律未做）

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --console=plain `
  --tests "com.xiufeigo.opencodeshell.ComposerUnionBottomP5V109Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerPaneBottomCriterionV108Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerOverflowV103Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerChildUnionV104Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTallEnoughV106Test"
```

### 3.2 结果（gradle 输出原文）

```
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 1m 5s
52 actionable tasks: 2 executed, 50 up-to-date
EXIT=0
```

| 类 | tests | failures | errors |
|---|---|---|---|
| `ComposerUnionBottomP5V109Test`（本轮新增） | **9** | 0 | 0 |
| `ComposerPaneBottomCriterionV108Test` | 7 | 0 | 0 |
| `ComposerOverflowV103Test` | 8 | 0 | 0 |
| `ComposerChildUnionV104Test` | 7 | 0 | 0 |
| `ComposerBandTallEnoughV106Test` | 7 | 0 | 0 |
| **合计** | **38** | **0** | **0** |

锁：`LOCK_ACQUIRED`（等 0s）→ 两次运行（第一次 4 条红，全在**新测试自身**的坐标系笔误）
→ 修正后 `BUILD SUCCESSFUL` → `EXIT=0` → `LOCK_RELEASED=True`。

> **诚实记账**：第一次跑红了 4 条（`sevenSixEight…` / `theBottomPaddingAbsorbs…` /
> `theFivePixel…` / `theHostBoxBottom…`）。**根因不是生产代码，是我在新测试里把
> `composerTopPx` / `composerBottomPx` 当成了绝对屏幕坐标，它们其实是
> **pane 相对**（pane 盒顶 = 0）**。已在文件里就地标注该坐标系，并逐条改成
> 「先 pane 相对、再加 `paneTop = 162` 转绝对」。** 没有一条既有 pin 被改动过。**

### 3.3 本轮新增的 9 条 pin 钉住了什么

| 用例 | 钉的东西 |
|---|---|
| `theFivePixelIsFourOfRegionTopPaddingPlusOneOfSubPixelPlacement` | 宿主盒 **合法**（`top+host ≡ pane`，相对 3+48=51，绝对 213）+ `+5 = 4 + 1` 逐位分解 + 复现 `218` |
| `theHostBoxBottomIsThePaneBottomInBothTiers` | 两档宿主盒底沿都等于 pane 高（`140` / `51`）—— 越界不在宿主盒 |
| `theBottomPaddingAbsorbsTheOverrunOnSevenSixEightButNotOnSixHundred` | **为什么只在 600 档**：`6 < 8`（768，`bottom` 独自让完，`top` 贡献 0）vs `12 > 8`（600，剩 4px 只能从顶部让） |
| `sevenSixEightTierHoldsTheContainmentOnEveryCell` | 768 × 队列 0/2/11 三格 `unionBottom ≤ paneBottom`，且 queue 0 那格逐位复现设备读数 `296` 与 `−6` |
| `theSixHundredTierIsOpenOnEveryQueueDepthAndPinnedAsSuch` | 600 + IME × 队列 0/2/11 **三格全部钉住 `+5` 开口**（断言它**仍然失败**）—— 修好的那天它会红 |
| `fourEightyTierStaysContainedAndNeverGoesNegative` | pane 51/40/28/12/0 × 队列 3 档：宿主被 `composerHostHeightPx` 夹进 pane、并集被夹进宿主 ⇒ 不越、不为负 |
| `theHostNeverExceedsThePaneOnAnyTier` | `host ≤ pane && host ≥ 0` 在 6 个 ceiling（含 `Int.MAX_VALUE`）上恒成立 |
| `theTouchTargetIsUntouchedByThisWholeInvestigation` | 48dp 触摸靶常量 = 48，**本轮一个像素没动** |
| `theRegionPaddingIsTheOnlyVerticalTermInTheChain` | `4 + 8 = 12` 与生产常量 `COMPOSER_REGION_PADDING` 一致（改坏了会红） |

**结构性 pin 全部走生产函数**（`composerTopPx` / `composerBottomPx` /
`composerHostHeightPx` / `composerChildUnionBottomPx` / `COMPOSER_REGION_PADDING` /
`MIN_INTERACTIVE_COMPONENT`），**不是自建模型**。

---

## 4. §4 为什么不降强度

| 约束 | 本轮状态 |
|---|---|
| `MIN_TOUCH_TARGET` / 48dp 触摸靶 | **一个像素没动**（`Composer.kt` 未动一字） |
| `BAND_HEIGHT` / `TOOL_ROW_HEIGHT` | **一个字没改** |
| v103–v108 全部 pin | **一条没放宽**（5 个类全绿，见 §3.2） |
| 768 档 | 时间线盒 **140**、`readable` **92**、`unionBottom ≤ paneBottom` 的 **−6** —— 未动 |
| 600 档 `readable` | **51**（§FIX4 让位声明）—— 未动 |
| 缩窗往返 | 未改 `QueueTimelineShrinkCrashV102Test` 的任何断言（本轮未跑该类，亦未改） |
| 用户可见字符串 | **零新增** |

**代价**：**600 档 `+5` 仍未修好。** 如实记在 §6，**不假装修好了**。

---

## 5. §5 真机复验步骤（给复验员；**本轮未装机、未跑真机**）

前置：`adb shell wm size 1366x768; adb shell wm density 160`（**显式重设，不用 reset**），
键盘升起（`mInputShown=true`），坐标从 `adb shell uiautomator dump --compressed` 解析
（沿用 `parse_5556.ps1`），`paneBottom` 取「时间线盒/pane 盒」的**容器底沿**。

| 步 | 动作 | 期望判据 |
|---|---|---|
| **1** | **600 + IME**，量 pane 容器底沿 + ⊞/模型 chip/Send（**排除 `Open terminal`**） | ① `unionBottom ≤ paneBottom`（基线 `218 vs 213` = **+5 ❌ 开口**）；② `unionPx ≤ band`（=0） |
| **2** | **768 + IME**，同上 | ① 成立（基线 `296 ≤ 302` = **−6 ✅**）；时间线盒 **140**、`readable` **92**（≥64） |
| 3 | 768 关 IME | ① 仍成立；140 / 92 不动 |
| 4 | 600 排队 0 / 2 / 11 条 | ① 三格都开口 5px（`218 vs 213`）—— 对齐本轮 pin |
| 5 | 缩窗往返 2 轮（768→600→768→600→768） | `pidof` 不变、无 `FATAL`/`IllegalArgumentException`、同档几何**逐位相同**（无振荡） |
| 6 | 队列条 | `Steer` h=43、**`×` h 仍 48**、三者 `clickable=true` |
| 7 | **768 的 8px 空隙**（§6 声明复核） | `paneBottom 302 − imeTop 294 = 8`，且 `unionBottom 296 ≤ 302` ⇒ 确认**无内容越出可用区** |

### 5.1 一行定位探针（**只在步 1 失败时上**；已落成可复现指令）

**位置 A —— `Composer.kt:2293`（`minimal` 一行形态那一支）：**

```kotlin
// 临时加（复验完删掉，不留在生产代码里）
Log.i("OCPROBE", "band=$band editorTop=$editorTop editor.h=${editor.height} toolRow.h=${toolRow.height}")
```

**位置 B —— `SessionScreen.kt:1812`（`composerRegion` 的 `.onSizeChanged` 那一行）：**

```kotlin
Log.i("OCPROBE", "region h=${it.height}")
```

**位置 C —— `SessionScreen.kt:3339`（摆位那一行）：**

```kotlin
Log.i("OCPROBE", "pane=$pane host.h=${host.height} hostTop=${SessionPaneLayout.composerTopPx(pane, host.height)}")
```

**步骤**：

1. `.\gradlew.bat :app:installDebug`（**仅复验员执行**；本轮纪律禁止装机，故未跑）
2. `adb logcat -c`；进会话页，`adb shell input tap` 聚焦输入框把键盘顶起
3. `adb shell wm size 1366x600; adb shell wm density 160`（**先切档再收键盘**，等 1s 稳态）
4. `adb logcat -d -s OCPROBE` 抓 `OCPROBE` 行
5. **判读表**：

| 观察 | 结论 | 下一步该动哪 |
|---|---|---|
| `region h=48`（= 宿主盒 48） | **顶部 4dp 已被 Column 吃掉** | `SessionScreen.kt:1813` |
| `region h=60`（= 4+48+8） | Column 未压缩，4dp 确实在带之上 | `SessionScreen.kt:1813` |
| `band=48 editorTop=0` 但带顶 `170 ≠ hostTop 165+4 = 169` | **那 1px 在 `toolRow.place` 的落位** | `Composer.kt:2298-2299` |
| `band=53`（> 48） | **+5 是带被撑高**，不是位移 | `SessionScreen.kt:3191` 的 `heightIn(max = composerCeiling)` |
| `hostTop ≠ 162 + 3`（绝对 165） | 宿主摆位就错了 | `SessionPaneLayout.kt:1426` |
| `toolRow.h=53` | 48dp 靶涨到了 53 | `Composer.kt:2204-2218` |

**六种观察互斥且穷尽** —— 无论真机给出哪一条，§1 的结论都不必重写，只有「改哪一行」需要填。

6. 复验完把三行 `Log.i` 删掉。

---

## 6. §6 Lead 裁定：768 的 8px 空隙 —— **显式声明为已知空隙，本轮不动 140/92**

### 6.1 空隙的算式

```
768 档 + IME（REPORT_COMPOSER_OVERFLOW4.md §2.1）
  pane 盒      [0,162][1366,302]  ⇒ paneBottom = 302
  IME 窗格     type=ime frame=[0,294][1366,768]  mInsetsHint bottom=474
               ⇒ imeTop = 768 − 474 = 294
  pane 越过 imeTop = 302 − 294 = +8
  并集 unionBottom = 296
```

### 6.2 为什么不构成用户可见缺陷（逐条复核）

| 判据 | 读数 | 结论 |
|---|---|---|
| 越过 `paneBottom` 了吗？ | `296 ≤ 302` | **没有**，富余 6px |
| 越过 `imeTop` 了吗？ | `296 > 294` | 越过 2px，但这 2px **落在 pane 自己那 8px 空隙之内** |
| 有内容被键盘遮住吗？ | — | **没有**：并集整体在 pane 内，pane 已整体退让 |
| 修它要动什么？ | pane 高 `140 → 132` | 要动 `QueueTimelineFloorV101Test` / v103–v107 pin 的**两个硬数字（140 / 92）** |

> **【已知空隙声明】** 768 档 + IME 下 `paneBottom(302) − imeTop(294) = 8px`。
> 该空隙由 `SessionScreen.kt:2428` 的 `padding(bottom = paneBottomDp)` 这条链产生：
> `believedImeBottomPx(606, 0, 474, 284)` ⇒ `474 > 606 − 284 = 322` ⇒ 夹取 ⇒
> `paneBottomDp = 322` ⇒ pane 高 `606 − 322 = 284`（= `PANE_ESSENTIAL_HEIGHT`），
> 而真机 a11y 读到的是 **140** ⇒ **中间还差一层未追到底的映射（§7 #3）**。
> **并集（296）完全在这 8px 之内，不越 pane 底沿 ⇒ 不构成用户可见缺陷；
> 修它必须动 140/92 两个硬 pin，风险大于收益 ⇒ 维持现状，正式声明为已知空隙。**

---

## 7. §7 未闭环（如实记账）

1. **600 档 `+5` 未修好（本轮诚实的结论）。** 头号嫌疑已从
   「`SessionScreen.kt:3191-3193` 的 modifier 链」**收敛并钉到行**：
   **`SessionScreen.kt:1813` 的 `top = 4.dp`**（算式 `4 + 1 = 5` 逐位对上），
   并**证伪了 `offset` / `imePadding` / `windowInsetsPadding` / `consumeWindowInsets` 重复扣除
   / `heightIn` 位移 / `BottomCenter` 取整 / `editorTop` 六条子嫌疑**。
   **但最后那 1px 未能归因到行**（带顶实测 170 ≠ 算式 169）⇒ **不改**。
   探针已落成 §5.1 的可复现步骤（六种判读互斥且穷尽）。

2. **`top: 4 → 0` 修不掉 +5**：`214 > 213` 仍越 1px。**任何只动 `top` 的改法都不够**，
   必须与那 1px 一起处理，而那 1px 要真机读数。**这是本轮不给「假修」的第二个理由。**

3. **`[0,162][1366,302] h=140` 究竟是「时间线盒」还是 `:2428` 的 pane 盒，仍未证到行。**
   按 `believedImeBottomPx` 算，`:2428` 那个盒在 768 应高 **284**，与实测 140 不符
   ⇒ a11y dump 的那个节点**不是它**。**这条链两轮了没追到底**，是 8px 声明里的未知项。

4. **`paneBoxPx` 在两档的真实值未独立复测**（§2.3 用 `window − 162` 反推，
   假设 `safeDrawing` top inset = 162px，未经 `dumpsys` 直接确认）。

5. **768 的 `readable` 92 仍是推导值**（`140 − 48`），非 a11y 直读（沿用 v105/v106 的同一限制）。

6. **本轮新增 pin 的 480 档格子是结构性格子，不是设备读数**（§3.3 已注明），
   真机补测后应替换为字面量。

7. **`/sdcard` 仍有往轮残留**（本轮未装机，未产生新文件；**不越权清理他人轮次产物**）。
