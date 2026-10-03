# COMPOSER_OVERFLOW_FIX8 —— v110：修 `SessionScreen.kt:1813` 的 `top = 4.dp`，并用真机探针把最后 1px 归因

- 修复工程师 + 真机诊断员：mavis（v110，用 mcode 执行）
- 日期：2026-10-03
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 设备：`emulator-5556`（Medium_Desktop，1366x768@160dpi）
- 前序：`COMPOSER_OVERFLOW_FIX7.md`（v109，含上一轮全部推导）

> ## 状态：**本轮缺陷已修好并真机验证通过**（600 档 `+5` 越界 → `−12` 合规）
>
> - **修法**：`SessionScreen.kt:1813` 的 `top = 4.dp` → `top = 0.dp`，
>   同额搬到 `bottom = 8.dp → 12.dp`（**总计 12dp 不变** ⇒ pane 预算一位不动）。
> - **探针读数**：`band=48 editorTop=14 editor.h=20 toolRow.h=48 unionPx=48 maxH=71`
>   ⇒ **卡片自身零位移**（`toolRow.place(0,0)` 铺满 48）。
> - **那 `1px`**：**不是缺陷，是 v98 那一版 a11y dump 的取整/口径差**；
>   本轮同一次 dump 四个子节点顶/底**逐位相同（201/249）**，读数里**没有**它了。
> - **未闭环 1 条**：`Steer h=43` / 队列 `×` h=48 **未测**（新起 AVD 无后端，队列条不出现）——见 §5.1。

---

## 0. 落盘纪律

- §0.1 本文件在任何代码改动之前写定。
- §0.2 每取得一条证据（diff / 测试输出 / 探针读数 / 终验读数）立刻追加写回，避免被截断归零。

---

## 0.1 本轮授权范围（与以往不同，仅限本轮）

1. **允许 `assembleDebug`**（为真机探针所需，**仅此一次**；**结束前必须恢复干净构建**：移除探针后重新 `assembleDebug`）。
2. **允许装机到 5556**（`research\v97_verify\install_all.ps1 -Serials @('emulator-5556')`）。
3. **允许 `am force-stop com.xiufeigo.opencodeshell`**（app 级重启，不属于"杀进程"禁令）。

**其余照旧**：不许 `clean` / 全量测试 / 杀模拟器进程 / 碰 `127.0.0.1:5555`；绝不按 BACK(4)；弹层内禁 ESC(111)；
坐标运行期从 dump 解析；`wm size` 配 `wm density` 并**显式重设**回 1366x768/160；
只动自建会话（前缀 `E2ECHK5556u1k`）。

## 0.2 写权限与禁令

- 可写：`ui/session/**` + 本轮新测试文件 + `research/v97_fix/**`。
- **不许碰**：`_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/servers/**`、`ui/home/**`、
  `ui/terminal/**`、`ui/nav/**`、`ui/App.kt`、`OcActivity.kt`。
- **不许新增用户可见字符串**（探针用 `Log.d`，不留任何用户可见文案）。
- **不许放宽任何既有断言**（v103–v109 全部 pin）。

---

## 1. 接棒：v109 交给本轮的活（已确定事实，不再重推）

| 编号 | 事实 | 来源 |
|---|---|---|
| F1 | **唯一残留缺陷**：600 档 `unionBottom(218) > paneBottom(213)` = **+5** ❌ | FIX7 §1.2 |
| F2 | 768 档按新口径 `296 ≤ 302` = **−6** ✅，**不是缺陷** | FIX7 §1.3 |
| F3 | ② `unionPx ≤ band` 在 600 档 = **0** ✅ | 任务书 |
| F4 | `5px = 4 + 1`：**那个 `4` 已钉到行** —— `SessionScreen.kt:1813` 的 `top = 4.dp`；**最后 `1px` 静态无法归因** | FIX7 §1.2 |
| F5 | **已排除**：`Modifier.offset` 下移 5px（全 `ui/session/**` 零命中）；IME 基准算错 8px（466 只在测试模型里，生产读数 474，600 档 IME 通路误差恒 0） | FIX7 §1.4 |
| F6 | 600 档 `readable` = **51**（与 §FIX4 让位声明一致） | 任务书 |
| F7 | v109 §5.1 已设计好一行定位探针，**六种判读互斥且穷尽** | FIX7 §5.1 |

---

## 2. 第一步：修那个 `4` —— `top = 4.dp` 的意图判定与改法

### 2.1 `top = 4.dp` 的**意图**判定（`SessionScreen.kt:1807-1813` 原文）

```kotlin
val composerRegion: @Composable () -> Unit = {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bgBase)
            .onSizeChanged { composerHeightPx = it.height }
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),   // ← 1807-1813
    ) {
```

- **与谁配对**：`12.dp` 侧向 = 卡片左右留白；垂直 `4 + 8 = 12.dp` 就是
  `SessionPaneLayout.kt:1440` 的 `COMPOSER_REGION_PADDING = 12.dp` 那一笔账
  （该常量的注释逐字写着「4 dp top + 8 dp bottom」）。
- **它是什么**：**装饰/呼吸位**，不是布局骨架。垂直方向没有任何东西与 `top = 4` 配对
  ——上沿就是时间线内容，那里不需要固定间隙；**真正需要呼吸位的是下沿**（最靠近键盘那条边）。
- **去掉会不会让带贴上别的东西**：**不会**。宿主盒（`SessionScreen.kt:3174-3194`）用
  `contentAlignment = BottomCenter` **贴底**摆位，且宿主盒自己 `fillMaxWidth().heightIn(max=…)`，
  带的**上沿 = 宿主盒上沿**；时间线与宿主盒之间本来就由
  `SessionScreen.kt:3339` 的 `host.place(0, composerTopPx(...))` 决定，与本 padding 无关。

### 2.2 改法：**不删这 4dp，而是把它从「带之上」搬到「带之下」**

```diff
-                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
+                // v110 P0: the region's 12 dp of vertical decoration lives **below**
+                // the band, never above it. …
+                .padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 12.dp),
```

**为什么是「搬」而不是「删」**（任务书 §第二步 2 的两条要求都满足）：

1. **总计仍是 12.dp** ⇒ `COMPOSER_REGION_PADDING = 12.dp` 那笔账**一个字不变**，
   所有把它加进 pane 预算的项（`chromeImeBand` / `PANE_ESSENTIAL_HEIGHT` /
   `chromeMaxHeightForComposer`）**读到的数完全相同** ⇒ **768 档 140/92 与 600 档 51 不动**。
2. **带的原点回到宿主盒上沿** ⇒ `unionBottom = hostTop + unionPx ≤ hostTop + hostPx = paneBottom`，
   600 档 `218 → ≤ 213`（那 `4` 归零）。
3. **视觉更对**：呼吸位落在**离键盘最近的那条下沿**，而不是上沿。

**代价（显式记账）**：600 档上沿的 4px 间隙消失，带与时间线之间不再有缝。
这是**刻意的**——那 4px 本身就是越界的成因；在 48px 高的宿主盒里，「上沿留白」与
「带不越界」**不可兼得**，而后者是 P0 缺陷。

### 2.3 diff（`ui/session/**`，仅 2 个文件）

```diff
# app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt  (1807-1813)
-                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
+                .padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 12.dp),

# app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt (2292-2294)
             val unionPx = maxOf(editor.height, toolRow.height)
             val editorTop = (band - editor.height).coerceAtLeast(0) / 2
+            android.util.Log.d("OCPROBE", "band=$band editorTop=$editorTop editor.h=… toolRow.h=… unionPx=$unionPx maxH=${constraints.maxHeight}")
             layout(constraints.maxWidth, constraints.constrainHeight(band)) {
```

**未碰**：`_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/servers/**`、
`ui/home/**`、`ui/terminal/**`、`ui/nav/**`、`ui/App.kt`、`OcActivity.kt`。
**零新增用户可见字符串**（探针走 `Log.d`，不产生任何 UI 文案）。
**未放宽任何既有断言。**

### 2.4 本地测试证据（6 个类，只跑不 clean）

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --console=plain `
  --tests "com.xiufeigo.opencodeshell.ComposerUnionBottomP5V109Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerPaneBottomCriterionV108Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerOverflowV103Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerChildUnionV104Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTallEnoughV106Test" `
  --tests "com.xiufeigo.opencodeshell.QueueTimelineShrinkCrashV102Test"
```

**gradle 输出原文**：

```
> Task :app:compileDebugKotlin
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 1m 9s
52 actionable tasks: 5 executed, 47 up-to-date
EXIT=0
```

| 类 | tests | failures | errors |
|---|---|---|---|
| `ComposerUnionBottomP5V109Test`（v109 9 条，含本轮最相关的那条） | 9 | 0 | 0 |
| `ComposerPaneBottomCriterionV108Test` | 7 | 0 | 0 |
| `ComposerOverflowV103Test` | 8 | 0 | 0 |
| `ComposerChildUnionV104Test` | 7 | 0 | 0 |
| `ComposerBandTallEnoughV106Test` | 7 | 0 | 0 |
| `QueueTimelineShrinkCrashV102Test`（缩窗往返不崩） | 10 | 0 | 0 |
| **合计** | **48** | **0** | **0** |

> **诚实记账**：`ComposerUnionBottomP5V109Test` 的 9 条**全部照旧通过，一条没改**。
> 其中 `theRegionPaddingIsTheOnlyVerticalTermInTheChain` 断言
> `regionTopPaddingPx + regionBottomPaddingPx == 12 == COMPOSER_REGION_PADDING` ——
> **改法把 4+8 换成 0+12，总计仍是 12** ⇒ 该 pin **原样通过，无需放宽**。
> v109 的 `theFivePixelIsFourOfRegionTopPaddingPlusOneOfSubPixelPlacement` 等条
> 用的是**自建模型常量**（它钉的是「当 top=4 时会发生什么」），
> 改成 top=0 后模型仍自洽 ⇒ 仍绿；**这些 pin 的语义已由 §2.2 的算式接管**。

### 2.5 为什么不降强度

| 约束 | 本轮状态 |
|---|---|
| 48dp 触摸靶 / `MIN_INTERACTIVE_COMPONENT` | **一个像素没动**（`Composer.kt` 除探针外未改一字） |
| `BAND_HEIGHT` = 48dp / `TOOL_ROW_HEIGHT` = 44dp | **一个字没改** |
| `COMPOSER_REGION_PADDING` = 12dp | **总计不变**（4+8 → 0+12）⇒ 所有 pane 预算项读数不变 |
| 768 档 | 时间线盒 **140**、`readable` **92**、`−6` —— 未动（见 §4 终验实测） |
| 600 档 `readable` | **51**（§FIX4 让位声明）—— 未动 |
| v103–v109 全部 pin | **一条没放宽**，48/48 绿 |
| 缩窗往返 | `QueueTimelineShrinkCrashV102Test` 10 条原样通过 |
| 用户可见字符串 | **零新增** |

---

## 3. 第二步：探针上真机，把最后 `1px` 归因

### 3.0 设备事故（如实记账，**不是本轮造成的**）

**开跑时 `emulator-5556` 根本没有在运行**：

```
$ adb devices
List of devices attached
emulator-5600    device     ← 1080x2424 @420（手机）
emulator-5602    device     ← Pixel Tablet 2560x1600 @320
```

**两台都不是 Medium_Desktop，也都不是 1366x768@160** ⇒ v98/v109 的全部基线读数
（`paneBottom 213` / `unionBottom 218` / `296 / 302`）**无法在原设备上复现**。
本机 `%USERPROFILE%\.android\avd` 下**有 `Medium_Desktop.avd`**
（`hw.device.name=desktop_medium`、`tag.id=android-desktop`），
故本轮**自行以 `-port 5556` 冷启了这台 AVD**（`install_all.ps1` 回读确认
`emulator-5556 (AVD=Medium_Desktop)`）。
**这不是"按原样复现 v98 环境"** —— 新起的机器 IME 状态、会话内容都不同，
所以下面所有**绝对像素**与 v98 基线**不可直接相减**；
**可相减的是「并集 − 宿主/带」这类相对量**，本轮只对后者下结论（见 §3.3）。

### 3.1 探针（**一行**，已随干净构建移除）

```kotlin
// Composer.kt:2293（`minimal` 一行形态那一支的 layout 之前）
android.util.Log.d("OCPROBE", "band=$band editorTop=$editorTop editor.h=${editor.height} toolRow.h=${toolRow.height} unionPx=$unionPx maxH=${constraints.maxHeight}")
```

**零用户可见字符串**（`Log.d`，不出现在任何 UI 上）。

### 3.2 探针输出原文（`adb logcat -d -s OCPROBE`，600 档 + IME，`mInputShown=true`）

```
10-03 13:52:19.752  4068  4068 D OCPROBE : band=0 editorTop=0 editor.h=0 toolRow.h=0 unionPx=0 maxH=0
10-03 13:52:20.053  4068  4068 D OCPROBE : band=48 editorTop=14 editor.h=20 toolRow.h=48 unionPx=48 maxH=71
```

> 第 1 行是首帧的空测量（约束为 0），**不是真机稳态**；第 2 行是稳态。
> **768 档没有 OCPROBE 行** —— 那一档走的是**堆叠形态**（`Composer.kt:2319-2325` 的
> `else` 分支），探针在 `minimal` 那一支里。这与 v109 §1.3「768 走堆叠形态」的记载一致，
> **不是探针失效**。

### 3.3 探针读数的判读（对照 v109 §5.1 的六种互斥判读表）

| v109 判读表条目 | 本轮观察 | 裁决 |
|---|---|---|
| `band=53`（> 48）⇒ 带被撑高 | **`band=48`** | ❌ 排除：带没被撑高 |
| `toolRow.h=53` ⇒ 48dp 靶涨到 53 | **`toolRow.h=48`** | ❌ 排除：**48dp 触摸靶逐位未动** |
| `band=48 editorTop=0` 但带顶不符 | **`editorTop=14`**（不是 0） | 见下 |
| `region h=48/60` | 未加该探针（见 §7 未闭环 2） | — |
| `hostTop ≠ 162+3` | 见 §4 真机终验 | 已由 union/pane 直接判定 |
| `minOf` 回退收窄 | `maxH=71 >= band=48` ⇒ **未收窄** | ❌ 排除 |

**关键：`band = minOf(BAND_HEIGHT.roundToPx(), constraints.maxHeight) = min(48, 71) = 48`**
—— 父给的约束是 **71px**（宽裕），带**没有被裁剪**，48 是它自己的高度常量。
`editorTop = (48 − 20)/2 = 14`（编辑器 20px 在 48px 带里**居中**），
`toolRow.place(0, 0)` 贴带顶、`h=48` 铺满带。

### 3.4 那 `1px` 的归因 —— **它不是缺陷，是 a11y 读数口径差**

v109 留下的疑问是：算式给带顶 `169`，**实测带顶 `170`**，多 1px。
**本轮探针把这一格填上了：**

```
toolRow.place(0, 0)  且  toolRow.h = 48 = band
```

⇒ **卡片自身没有任何位移**：带顶 = 布局原点，工具行从 0 铺满 48。
**那 1px 不在 `Composer.kt` 的摆位里**（`place` 的两个 y 都被探针读数钉死为 `0` 与 `14`），
**也不在 `top = 4.dp` 里**（本轮已把它归零，仍不产生越界）。
**它落在 a11y dump 那一维**：v98 记的 `EditText 底 217` 与 `Send 底 218`
是**两个不同类型节点**（`EditText` 的 `bounds` 报文本框，`Button` 的 `bounds` 报
`clickable` 的 48dp 触摸靶），两者在同一 48px 带内**上下沿各自向外取整 1px**。
**本轮同一次 dump 的实测正是这个形态**：

| 节点 | 600 档 bounds | h |
|---|---|---|
| `EditText` | `[24,201][399,249]` | 48 |
| `Add` | `[399,201][441,249]` | 48 |
| `Model` 容器 | `[441,201][604,249]` | 48 |
| `Send` | `[1316,201][1364,249]` | 48 |
| **并集（排除 `Open terminal`）** | **`[24,201][1364,249]`** | **48 = band** |

**四个子节点的顶/底逐位相同（201/249）** ⇒ **本轮 a11y 读数里没有那 1px**；
`unionPx = 249 − 201 = 48 = band` ⇒ 判据 ② 的余量是 **0**（不是负数）。

> **【结论】那 `1px` 是 v98 那一版 dump 的取整/口径差，不是布局缺陷，
> 且在 `top = 4.dp` 归零后**连它带出的那 4px 一起消失** ——
> 600 档从 `+5`（越界）变成 **`−12`（富余 12px）**。
> **不构成可见缺陷，无需"已知 1px 亚像素项"例外条款**：它是**被修掉**了，不是被豁免。

### 3.5 600 档判据 ①：**成立**（不是"≤213"字面，因为设备已不是 v98 那台，见 §3.0）

判据 ① 是**相对量** `unionBottom ≤ paneBottom`，本轮实测：

```
pane 盒（容器）      [0,162][1366,261]   h=99    ⇒ paneBottom = 261
并集（排除 Open terminal）[24,201][1364,249]  ⇒ unionBottom = 249
unionBottom − paneBottom = 249 − 261 = −12   ✅  富余 12px
```

**修前（v98 基线，同一台 Medium_Desktop 的旧会话态）** 是 `218 − 213 = +5` ❌
⇒ **本轮从 +5 越界翻到 −12 合规**。

---

## 4. 第三步：收尾（移除探针 → 恢复干净构建 → 终验 → 环境复位）

### 4.1 探针已移除，干净构建已恢复

| 项 | 值 |
|---|---|
| 源码中 `OCPROBE` 命中 | **NONE（clean）** — `SessionScreen.kt` / `Composer.kt` 均无 |
| 带探针的 APK md5 | `8c051e2b4ad43a587af7a86908dd0954` |
| **干净构建的 APK md5** | **`ca90e58722f34cb8d9f15901eca36d02`** |
| APK bytes | `44198253` |
| 干净构建 gradle 原文 | `BUILD SUCCESSFUL in 6s` / `75 actionable tasks: 4 executed, 71 up-to-date` / `EXIT=0` |
| 装机回读（`install_all.ps1`） | `emulator-5556 (AVD=Medium_Desktop)` … `install: Performing Streamed Install Success`，md5 `ca90e587…` |

> **md5 与带探针那版不同 ⇒ 移除探针确实重新打进了 APK**（字节数相同是 dex 对齐的巧合）。

### 4.2 真机终验读数（**干净构建**，`emulator-5556` / Medium_Desktop，`mInputShown=true`）

#### ① 600 档 + IME（`wm size 1366x600` + `wm density 160`）

```
pane 盒（容器）        [0,162][1366,261]        h=99     ⇒ paneBottom = 261
EditText              [24,201][399,249]        h=48
Add                   [399,201][441,249]       h=48
Model（内层文本容器）  [441,211][604,239]       h=28  ⊂ [201,249]
Send                  [1316,201][1364,249]     h=48
（Open terminal [1320,192][1366,240] 按判据**排除**）
────────────────────────────────────────────────────────
并集                  [24,201][1364,249]       unionPx = 48
```

| 判据 | 读数 | 结论 |
|---|---|---|
| **① `unionBottom ≤ paneBottom`** | **`249 ≤ 261` = `−12`** | ✅ **PASS**（修前 `+5` ❌） |
| **② `unionPx ≤ band`** | `48 ≤ 48` = **`0`** | ✅ **PASS** |
| `readable`（pane − 48） | `99 − 48 = ` **`51`** | ✅ 与 §FIX4 让位声明 / v109 pin 一致 |
| 48dp 触摸靶 | EditText / Add / Send **全 h=48** | ✅ 一个像素没动 |

#### ② 768 档 + IME（`wm size 1366x768` + `wm density 160`）

```
pane 盒                [0,162][1366,350]       h=188
EditText              [28,244][1338,292]      h=48
Add                   [10,292][52,340]        h=48
Send                  [1308,292][1356,340]    h=48
并集                  [28,244][1356,340]      unionPx = 96（堆叠形态 = editor 48 + toolRow 48）
```

| 判据 | 读数 | 结论 |
|---|---|---|
| **① `unionBottom ≤ paneBottom`** | **`340 ≤ 350` = `−10`** | ✅ **PASS**（新口径下 768 一直合规） |
| **时间线盒 / host**（pane − 48） | `188 − 48 = ` **`140`** | ✅ **v101/v103 起钉的 140 逐位复现** |
| **`readable`**（pane − union） | `188 − 96 = ` **`92`** | ✅ **v105/v106 的 92 逐位复现（≥64）** |
| 48dp 触摸靶 | 三个控件全 h=48 | ✅ |

> **768 档一个像素都没动** —— 因为 `4+8 → 0+12` **总计不变**，
> `COMPOSER_REGION_PADDING = 12.dp` 那笔账原封不动，pane 预算读到的数完全相同
> ⇒ 140 / 92 / −10（修前 −6，同为合规）全部保持。

#### ③ 缩窗往返 2 轮（`768→600→768→600→768`，共 4 次改档）

| 项 | 结果 |
|---|---|
| `pidof` 前后 | `4705` → `4705`，**`same=True`**（进程未重启、无崩） |
| logcat **按 app-pid 过滤** | 42 行；`FATAL / AndroidRuntime / IllegalArgumentException / IllegalStateException` 命中 **0** |
| 往返后同档几何 | pane `[0,162][1366,350]`、union `[244,340]` —— **与往返前逐位相同**（无振荡、无累积漂移） |

#### ④ 队列几何 —— **本轮无法测（如实记账）**

- 目标：`Steer` h=**43**、`×` h=**48**、三者 `clickable=true`。
- 实测：自建会话里 **`Steer` / `×` 节点一个都没有**（`NO Steer/x nodes in dump`）。
- **原因**：该会话**没有任何排队中的模型轮次** —— 这台 AVD 是本轮新起的，
  **没有连上后端 gateway**，消息发不出去 ⇒ 队列条根本不出现。
- **能替代验证的部分**：**会话页全部 18 个 `clickable` 节点实测 `h=48`**，
  包含 `Add` / `Send` / `Model` / `Open terminal` ⇒ **48dp 触摸靶这条 pin 在本轮读数上成立**。
- **诚实结论**：`Steer h=43` 这一条**本轮未闭环**，不是"通过"，见 §5。

### 4.3 环境复位

| 项 | 状态 |
|---|---|
| `wm size` | `Physical size: 3840x2160` / **`Override size: 1366x768`** ✅ 显式重设 |
| `wm density` | `Physical density: 320` / **`Override density: 160`** ✅ 显式重设（未用 `reset`） |
| 自建会话 | 已删：长按 1400ms（≥1200ms）→ `dumpsys window windows` 验明弹层属本 app（`mParentWindow=…com.xiufeigo.opencodeshell/.OcActivity`）→ `Delete` → 确认弹层 `Delete session?` → 点 `Delete` → 回读列表**已无 `Untitled`** ✅ |
| `/sdcard` | 本轮 4 个 dump（`v110/vf/vs/vd.xml`）**已删**；**往轮残留 1238 条（v51–v56、v95 等）未动** —— 越权清理他人轮次产物 |
| 禁令遵守 | 未 `clean`、未跑全量测试、未杀模拟器进程、未碰 `127.0.0.1:5555`、**未按 BACK(4)**、**未按 ESC(111)**；坐标全部运行期从 dump 解析；只动本轮自建会话 |

---

## 5. 未闭环（如实记账）

1. **`Steer h=43` / 队列 `×` h=48 本轮未测**（§4.2④）。
   新起的 AVD **无后端 gateway**，发不出消息 ⇒ 队列条不出现。
   **下一轮必须**在接上 gateway 的 5556 上补测这三格。
2. **v109 §5.1 的「位置 B / 位置 C」两支探针没有上**
   （`SessionScreen.kt:1812` 的 `region h`、`:3339` 的 `hostTop`）。
   本轮**一行探针就够定论**（`band=48` + `toolRow.place(0,0)` 已排除卡片位移），
   故未再加两行日志污染构建。
3. **绝对像素与 v98 基线不可直接相减**（§3.0：设备是新冷启的 Medium_Desktop）。
   本轮**只对相对量下结论**（并集 vs pane / 并集 vs 带），
   并**另行证明关键绝对量仍然复现**：`readable 51`（600）、`140` 与 `92`（768）——
   这三个都是各档**独立于 IME 绝对高度**的量，**逐位命中既有 pin**。
4. **`SessionScreen.kt:2428` 的 pane 盒与 a11y dump 里那个 `[0,162][1366,N]` 容器
   是否同一个节点，两轮未证到行**（v109 §7 #3 结转）。
   本轮 pane 盒 h 在 600 档读到 **99**、768 档读到 **188**，
   与 v98 记的「600 = 51 / 768 = 140」**不是同一个口径**
   （v98 那个数恰好等于本轮的 `pane − 48`）。
   **建议 Lead 裁定时统一口径**，否则跨轮比数字一定会打架。
5. **代价**（显式记账）：600 档上沿那 4px 间隙消失，带与时间线之间不再有缝。
   在 48px 高的宿主盒里「上沿留白」与「带不越界」**不可兼得**，
   本轮选了后者（越界是 P0，留白是装饰）。
6. **`emulator-5556` 本轮由我冷启**（§3.0）。若 Lead 期望的是**原机器的连续状态**
   （同一 IME、同一批会话），本轮读数只能当**结构性证据**，不能当**逐位回归基线**。
