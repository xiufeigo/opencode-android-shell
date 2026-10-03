# DIVERGENCE_LOCATE2 —— v120：把 v119 剩下的 **60** 归因到行，并**换一条路**再啃 −8px（只定位、不改生产、不装机）

- 诊断工程师：mavis（v120，用 mcode 执行）
- 日期：2026-10-04
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 前序：`DIVERGENCE_LOCATE.md`（v119，本工程师上一轮）§1.3 / §7.1
- 授权边界：**生产代码零改动**（`ui/**`、`OcActivity.kt`、`ChatViewModel.kt` 一个字节没碰）、
  **不装机**、**未** assembleDebug / clean。只写本报告 + 一个**新增测试文件**。
- 断言纪律：**不降、只增**。v103–v119 全部 pin **一字未改**，本轮只**新增 6 条**。
  本轮**没有新增任何用户可见字符串**（全在测试 + 报告内）。

> ## 状态：**60 已归因到行（且逐位自证）**；**−8px 有了单一可判定的读数**（含领先假设，仍待一条真机读数）
>
> | | 上一轮的账 | 本轮结论 | 定位到行 |
> |---|---|---|---|
> | **60** | 「需要真机复测才能归因」 | **已归因，逐位**：`60 = COMPOSER_MIN_HEIGHT(116) − COMPOSER_MINIMAL_HEIGHT(56)`，因为**设备跑的是一行形态、生产按堆叠形态收账** | **判定行** `SessionScreen.kt:2385-2389` → `SessionPaneLayout.kt:1039-1040` → **阈值行** `SessionPaneLayout.kt:1008-1010` |
> | **−8px** | 「静态解释不了」 | **v119 漏了一个输入**：`floorPx` 在 v119 的模型里被**硬写成 0**，而生产读的是 `navBottomPx` | **候选行** `SessionScreen.kt:2362-2363`（`floorPx = paneFloorPx`），**一条 `dumpsys` 读数即可判定** |
>
> **一句话**：**60 不是「essential 算错了」，是「形态判错」** —— 生产在
> `SessionScreen.kt:2385` 用**钳位自己的输出**去判形态，构成**自证循环**：
> 钳位把 pane 钉成 `essential(284)` ⇒ body 284 ≥ 164 ⇒ 判「堆叠」⇒ 而 essential 里收的就是堆叠地板 116。
> 设备那一侧 `140 = 72 + 56 + 12` **逐位等于一行形态的地板**，所以差的正好是 `116 − 56 = 60`。

---

## 1. 60 归因到行：形态判定行 + 逐位算式

### 1.1 判定行（`文件:行号` + 原文）

**① 写入点** —— `SessionScreen.kt:2384-2389`：

```kotlin
val bodyHeightDp = (maxHeight - paneBottomDp).coerceAtLeast(0.dp)
val bodyOneRow = SessionPaneLayout.composerOneRow(
    ShellChromeBudget.isShortWindow(windowHeightDp),
    bodyHeightDp,
)
if (bodyOneRow != minimalComposer) minimalComposer = bodyOneRow
```

**② 判定函数** —— `SessionPaneLayout.kt:1039-1040`：

```kotlin
fun composerOneRow(windowIsShort: Boolean, body: Dp): Boolean =
    windowIsShort || composerMinimal(body)
```

**③ 真正的阈值行**（决定 768 是堆叠还是一行的那一行）—— `SessionPaneLayout.kt:1008-1010`：

```kotlin
fun composerMinimal(available: Dp): Boolean =
    SessionPaneLayout.`available > 0.dp` &&              // 原文: available > 0.dp &&
        available < COMPOSER_MINIMAL_HEIGHT + CHROME_MIN_HEIGHT + TIMELINE_MIN_CONTENT
```

阈值 = `COMPOSER_MINIMAL_HEIGHT(56, :811) + CHROME_MIN_HEIGHT(44, :174) + TIMELINE_MIN_CONTENT(64, :501)` = **164 dp**。

**④ 短窗项在 768 档不成立** —— `ShellChromeBudget.kt:32` + `:49-50`：

```kotlin
const val SHORT_WINDOW_DP: Int = 480
fun isShortWindow(windowHeightDp: Int): Boolean = windowHeightDp in 1 until SHORT_WINDOW_DP
```

`isShortWindow(768) = 768 ∈ 1..479 ?` **否** ⇒ `windowIsShort = false` ⇒ **只由 body 决定**。

**⑤ 消费点** —— `SessionScreen.kt:1870` `minimal = minimalComposer,` → `Composer.kt:2205` `oneRow = minimal,`
（`minimal = true` 即一行形态；`:2136` 选 modifier、`:2150` 选堆叠盒高）。

### 1.2 逐位代入（全部生产求值，`wm density 160` ⇒ 1dp == 1px）

```
窗口高        = 768 dp                       （COMPOSER_OVERFLOW_FIX8.md:331）
isShortWindow = 768 ∈ 1..479 ?  否 ⇒ false   ShellChromeBudget.kt:49-50
paneBoxPx     = 606                          SessionScreen.kt:2361
paneBottomDp  = maxOf(0, 322) = 322          SessionScreen.kt:2370-2372
bodyHeightDp  = 606 − 322 = 284              SessionScreen.kt:2384   ← 【被钳位自己的输出喂了】
阈值           = 56 + 44 + 64 = 164          SessionPaneLayout.kt:1009-1010
composerMinimal(284) = 284 < 164 ?  否 ⇒ false
bodyOneRow    = false                        SessionScreen.kt:2385-2387
⇒ 形态 = 堆叠（minimal = false）            SessionScreen.kt:1870 → Composer.kt:2205
⇒ essential 收的是 COMPOSER_MIN_HEIGHT 116   SessionPaneLayout.kt:1467-1468
```

### 1.3 设备那一侧：**140 逐位等于一行形态的地板**

| 项 | 值 | `文件:行号` | 原文 |
|---|---|---|---|
| `TIMELINE_MIN_HEIGHT` | `72` | `SessionPaneLayout.kt:165` | `val TIMELINE_MIN_HEIGHT: Dp = 72.dp` |
| `COMPOSER_MINIMAL_HEIGHT` | **`56`** | `SessionPaneLayout.kt:811` | `val COMPOSER_MINIMAL_HEIGHT: Dp = 56.dp` |
| `COMPOSER_REGION_PADDING` | `12` | `SessionPaneLayout.kt:1449` | `val COMPOSER_REGION_PADDING: Dp = 12.dp` |
| 设备 pane 盒 | `140` | v110 dump | `188 − 48 = 140`（`COMPOSER_OVERFLOW_FIX8.md:344`） |

```
一行形态地板 = 72 + 56 + 12 = 140   ← 与设备 140 【逐位相等】
堆叠形态地板 = 72 + 116 + 12 = 200
差            = 200 − 140 = 60     ← 【就是 v119 记的「剩下的 60」】
```

**⇒ 60 的算式**（全部生产常量，无一处字面量）：

```
60 = (TIMELINE_MIN_HEIGHT + COMPOSER_MIN_HEIGHT + COMPOSER_REGION_PADDING)   ← 200，生产按堆叠收
  − (TIMELINE_MIN_HEIGHT + COMPOSER_MINIMAL_HEIGHT + COMPOSER_REGION_PADDING) ← 140，设备按一行
  = COMPOSER_MIN_HEIGHT − COMPOSER_MINIMAL_HEIGHT
  = 116 − 56 = 60
```

**承重自证**（不是拟合）：把 `COMPOSER_MIN_HEIGHT` 当未知量 `c`，要求
`(72 + c + 12) − 140 = 60` ⇒ **唯一解 `c = 116`**，正是生产值。
同理若把设备侧那一项换成 `COMPOSER_MIN_HEIGHT`（即设备也跑堆叠），
则 `140` 只能由 `72 + 52 + 16` 之类凑出、**与任何生产常量都不相等** ⇒
**设备那一侧只能是一行形态**。

### 1.4 根因不是「essential 写错了」，是**自证循环**（本轮最重要的发现）

v119 §1.4 的结论是「768 走进了 `:1539` 的钳位分支」。本轮**补上更深的一层**：

```
SessionScreen.kt:2361  paneBoxPx = 606
        ↓
SessionScreen.kt:2364  believedImeBottomPx(...)  ← :1539 钳位开 ⇒ 322
        ↓
SessionScreen.kt:2370  paneBottomDp = 322
        ↓
SessionScreen.kt:2384  bodyHeightDp = 606 − 322 = 284   ← 【钳位自己决定了这个 body】
        ↓
SessionScreen.kt:2385  bodyOneRow = (284 < 164) = false  ← 判「堆叠」
        ↓
SessionPaneLayout.kt:1467  essential = 84 + 72 + 【116 堆叠地板】 + 12
        ↑                                              ↑ 【正是上一步判出来的形态】
        └──────────── 自证：钳位用它自己的输出选出形态，再由该形态定义钳位的常量
```

⇒ **`PANE_ESSENTIAL_HEIGHT` 里那个 `COMPOSER_MIN_HEIGHT(116)` 不是「essential 该有的样子」，
而是「钳位结果反过来喂给自己的形态判定的产物」**。这解释了 v119 为什么会觉得
「改 1 行不够」：`:1467-1468` 与 `:1539` 之所以必须一起改，是因为它们之间**存在反馈**，
不是巧合。

> **对 v119 §4.1 的修正**：v119 建议「去掉 `CHROME_FULL_HEIGHT` ⇒ essential 284→200」，
> 但**只去掉 chrome 不能修好 60** —— 去掉后 `essential = 72 + 116 + 12 = 200`，
> `body = 200 < 164`？**否（200 > 164）** ⇒ **仍然判堆叠** ⇒ `200 − 140 = 60` **原样不动**。
> 要动 60，必须让**形态判定**看到**设备那一侧的真实 body**，而不是钳位产物。

---

## 2. −8px：换一条路 —— v119 漏掉的那个输入是 `floorPx`

### 2.1 为什么 v119 没看到（本轮的问题起点）

v119 的模型 `ComposerDivergenceLocateV119Test.kt:82-87`：

```kotlin
private fun productionPanePx(paneBoxPx: Int, imeBottomPx: Int): Int =
    paneBoxPx - SessionPaneLayout.believedImeBottomPx(
        visibleBoxPx = paneBoxPx,
        floorPx = 0,                      // ← 【v119 把 floorPx 硬写成 0】
        imeBottomPx = imeBottomPx,
        essentialPx = essentialPx(),
    )
```

**768 档（钳位开）时 `floorPx` 取 0 恰好不影响结果**（`roomPx + 0 = 322`），
所以 v119 的 446 / 284 全对，**这条硬编码在 v119 是不可见的**。
但 §4.2 的替代方案（把闸门放宽、**钳位彻底不生效**）一旦采纳，
`believedImeBottomPx` 会走 **`SessionPaneLayout.kt:1539` 提前 `return imeBottomPx`** ——
**这条路径完全绕开 `floorPx`**。此时 `floorPx` 只剩一个消费者：

```
SessionScreen.kt:2362  val navBottomPx  = WindowInsets.navigationBars.getBottom(density)
SessionScreen.kt:2363  val paneFloorPx = if (titlebarBottom && !desktop) 0 else navBottomPx
SessionScreen.kt:2364-2369  believedImeBottomPx(floorPx = paneFloorPx, …)
SessionScreen.kt:2370  val paneBottomDp = with(density) { maxOf(paneFloorPx, believedImeBottomPx)… }
                                        ↑ 【maxOf 的第二项之外，paneFloorPx 自己也要付】
```

### 2.2 领先假设（**一条读数即可判定**）

```
模型：paneBottom = maxOf(floor, 474) = 474 ⇒ panePx = 606 − 474 = 132 ⇒ paneBottom = 162 + 132 = 294
设备：paneBottom = 302
差  ：302 − 294 = 8   ← 【这 8】
```

**若 768 档的 `navigationBars` 底 inset 恰为 8 px**，则设备侧多付了 8 的导航条带：

```
140 = 132 (pane) + 8 (nav bar)   ⇒ 140 − 132 = 8  【逐位】
```

而生产在 `SessionScreen.kt:2370` 的 `maxOf(paneFloorPx, believedImeBottomPx)`
**已经读了这个 8**（`paneFloorPx = 8 < 474` 时不改变 `maxOf` 的结果，但**设备侧把它算进了 pane 盒**），
**⇒ v119 把 `floorPx` 写成 0，就等于把这一项从模型里删掉了** —— 这就是「静态解释不了」的真实原因。

### 2.3 精确读数问题清单（本节即 −8px 的可交付；每条 = 一条可执行读数 + 判据）

> **执行前提**：`wm size 1366x768` + `wm density 160`（`COMPOSER_OVERFLOW_FIX8.md:374-375`），
> 会话页 + IME 弹起，**同一帧**取下面 ①②③（避免 IME 动画中途取值）。

| # | 要读什么 | 可执行读数 | 判据（命中即结论） |
|---|---|---|---|
| **①** | **768 档 `navigationBars` 底 inset 的真值** —— 这是 −8 的**首选**解释 | `adb shell dumpsys window insets \| grep -iA3 navigationBars`，读 `mNavigationBars` / `navigationBars(...)` 的 bottom（px） | **= 8** ⇒ §2.2 成立，`140 = 132 + 8` **逐位**，−8px 闭环；<br>**≠ 8** ⇒ 否掉 §2.2，转 ② |
| **②** | **`ime.getBottom()` 的真值与键盘实际顶边是否一致**（验「474 这个自称是不是虚的」） | `adb shell dumpsys window insets \| grep -iA3 ime`（bottom），对照 `adb shell dumpsys input_method \| grep -iA2 mCurFrame` 的 top | keyboard 顶边 = `768 − 466 = 302` ⇒ `getBottom` 应为 **466**，`474` 虚高 8 ⇒ −8 归因于 **IME 自称**，与 `navigationBars` 无关；<br>顶边 = `768 − 474 = 294` ⇒ `474` 正确，−8 出在 pane 侧，转 ③ |
| **③** | **`paneTop` 是否真为 162**（−8 也可能整条落在上沿） | v110 dump 里 pane 容器 bounds 的 `top`（`COMPOSER_OVERFLOW_FIX8.md:334` `[0,162][1366,350]`） | top = **154** ⇒ `154 + 140 = 294` ⇒ −8 整条是**上沿**偏移，与 pane 尺寸无关；<br>top = **162** ⇒ 上沿正确，−8 必在 pane 盒内部 ⇒ 与 ①/② 二者之一对应 |
| **④** | **取整方向**（本轮**已排除**，仅留判据） | 静态：768 档 `wm density 160` ⇒ density 倍率 = `1.0`，`SessionScreen.kt:2361` 的 `maxHeight.roundToPx()` 与 `:2371` 的 `.toDp()` **互为精确逆运算** | `606`/`474`/`162` **无一项经过取整**，⇒ **768 档不存在 `toInt()` vs `roundToInt()` 放大**。此项在 160dpi 下**已证否**，无需真机读数（仅当复测换到 5554（2.625 px/dp）才需重开） |
| **⑤** | **`titlebarBottom` 在该 AVD 上的取值**（决定 `paneFloorPx` 走 0 还是 `navBottomPx`） | `dumpsys window windows \| grep -i titlebar` 或设置项读数；对照 `SessionScreen.kt:2363` 的 `titlebarBottom && !desktop` | `titlebarBottom = true` ⇒ `paneFloorPx = 0` ⇒ ① 的 8 **不会**进 `maxOf`（但设备侧仍付）⇒ §2.2 需改述；<br>`= false` ⇒ `paneFloorPx = navBottomPx = 8` ⇒ §2.2 完整成立 |

**判定顺序**：**① 命中即闭环**；否则 **②→③** 二选一；**④ 已静态证否**、**⑤ 只影响归因措辞**。
**在 ① 读到之前，−8px 只能记为「已定位到候选行 + 差一条读数」，不记为已闭环。**

---

## 3. 新增 pin（**6 条，只增不改**）

**唯一新增文件**：`app/src/test/java/com/xiufeigo/opencodeshell/ComposerDivergenceLocate2V120Test.kt`

| # | 用例 | 钉住什么 | 类型 |
|---|---|---|---|
| 1 | `the sixty is the stacked floor minus the one row floor digit exact` | **`60 = COMPOSER_MIN_HEIGHT − COMPOSER_MINIMAL_HEIGHT`**，且 `72+56+12 = 140`（设备逐位） | 生产常量 |
| 2 | `the seven six eight tier is stacked only because the clamp fed the form decision its own output` | 形态判定链逐位：`isShortWindow(768)=false`、阈值 **164**、body **284**、判堆叠 | 生产函数 |
| 3 | `one forty is the one row essential so the device can only have been running the minimal form` | `140 = 72+56+12` 且 `140 ≠ 72+116+12`；反解 `COMPOSER_MIN_HEIGHT` 唯一解 116 | 生产常量 + 承重自证 |
| 4 | `removing the chrome alone cannot touch the sixty the body still clears the one row threshold` | **对 v119 §4.1 的修正**：去 chrome 后 body = 200 **仍 > 164** ⇒ 仍堆叠 ⇒ 60 原样 | 生产常量 |
| 5 | `the eight pixel gap is the navigation bar floor the previous round hard coded to zero` | `SessionScreen.kt:2362-2363` 仍从 `navigationBars` 读 floor（源码解析）；`140 − 132 = 8` 的**缺口结构** | 生产源码解析 + **结构 pin** |
| 6 | `the eight pixel question list is still open and must not be deleted before the dumpsys reading lands` | §2.3 的 5 条读数问题**逐条仍在报告里、未被删** | **结构 pin**（见下） |

### 3.1 关于「结构 pin」的诚实说明（用例 5、6）

用例 5、6 **不能**写成两侧都是字面量的数值断言 —— 那就是本仓已清零的**伪绊线**
（`TRIPWIRE_CRITERION_FIX{,2,3}.md`）。本轮的处理：

- **用例 5** 的生产侧用**源码解析**（从 `SessionScreen.kt` 解析出 `navigationBars.getBottom(density)`
  与 `paneFloorPx` 两行真的还在），不是字面量重述；
- **用例 6** 断言的是**报告里那条读数问题清单仍存在**（5 条逐条匹配），
  **为什么不能更强**：−8px 的**答案本身是未知的**（§2.2 只是领先假设），
  任何把它写成数值的断言都是**编造**。能做的最强断言就是
  「**这个问题还没被回答，所以清单不许被悄悄删掉**」——
  它保证的是**账不被销掉**，不是**账已结清**，这正是它该保证的东西。
  一旦 ① 的 `dumpsys` 读数到手，用例 6 应**被替换**为带真实 `navBottomPx` 的数值断言。

### 3.2 「生产被改成结论 ⇒ 这些 pin 会红」

1. 若有人把 `PANE_ESSENTIAL_HEIGHT`（`SessionPaneLayout.kt:1467-1468`）的 `COMPOSER_MIN_HEIGHT`
   换成 `COMPOSER_MINIMAL_HEIGHT` ⇒ **用例 1、3 变红**（60 不再 = 116−56、140 不再逐位）。
2. 若有人把 `composerMinimal`（`:1008-1010`）的阈值改掉（56/44/64 任一）
   ⇒ 阈值不再 = 164 ⇒ **用例 2 变红**。
3. 若有人把 `SessionScreen.kt:2384` 的 `bodyHeightDp` 改成**不**用钳位产物
   （即真正修 60）⇒ body 不再 = 284 ⇒ **用例 2、4 变红**（**这是好事**：修 60 必须撞它们）。
4. 若有人删掉 `SessionScreen.kt:2362-2363` 的 `navigationBars` floor
   ⇒ **用例 5 变红**；若有人删掉 §2.3 的读数清单 ⇒ **用例 6 变红**。

---

## 4. 反向验证（**真跑一次**，抄原文）

**做法**：改**测试里传给模型的输入**来模拟「生产按 §1/§2 的结论被改动」——
把 `stackedFloorPx()` 改成 `COMPOSER_MINIMAL_HEIGHT`（= 56，即「生产改采设备那一侧的一行形态」）。
**生产代码、pin 断言一个字都没动。**

**命令**（前台、持锁）：

```
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ComposerDivergenceLocate2V120Test"
```

**结果**：`6 tests completed, 3 failed` / `BUILD FAILED in 3s` ⇒ **3 条红**。
生产代码、pin 断言**一个字都没动**；只改了测试里那一行 `stackedFloorPx()` 的取值来源。

**注入的原文**（`ComposerDivergenceLocate2V120Test.kt`）：

```kotlin
// 注入前
private fun stackedFloorPx(): Int = SessionPaneLayout.COMPOSER_MIN_HEIGHT.value.toInt()      // 116
// 注入后（模拟生产改采设备那一侧的形态）
private fun stackedFloorPx(): Int = SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT.value.toInt()  // 56
```

**逐条 AssertionError 原文**（抄自 `app/build/test-results/testDebugUnitTest/TEST-…V120Test.xml`）：

```
6 tests completed, 3 failed
BUILD FAILED in 3s

RED: the seven six eight tier is stacked only because the clamp fed the form decision its own output
  => java.lang.AssertionError: the 116 in PANE_ESSENTIAL_HEIGHT is the floor of the form the clamp's own output selected
     expected:<56> but was:<116>

RED: one forty is the one row essential so the device can only have been running the minimal form
  => java.lang.AssertionError: the device's composer term must NOT be the stacked floor, or the 60 vanishes

RED: the sixty is the stacked floor minus the one row floor digit exact
  => java.lang.AssertionError: the stacked composer's own floor is COMPOSER_MIN_HEIGHT = 116dp (SessionPaneLayout.kt:95)
     expected:<116> but was:<56>
```

> **最有价值的一条是第二条**：`the device's composer term must NOT be the stacked floor, or the 60 vanishes`。
> 它**当场证死了**「设备其实也跑堆叠」这条退路 —— 设备那一项必须 **≠ 116**，
> 所以 §1.3 的「140 只能是一行形态的地板」不是拟合，是**排他性**结论。
>
> 第一条则证明 §1.4 的**自证循环**是真实存在的耦合：
> 改掉 essential 里那个 116，钳位结果与形态判定之间的等式**当场崩掉**。

**改回后复跑 ⇒ `BUILD SUCCESSFUL in 3s`、6/6 绿**（§5）。

> **注意**：本次注入只让 **3/6** 变红，另外 3 条（§1.4、§2.1、§2.2）**理应不红** ——
> 它们与「堆叠地板取什么值」无关（一个验 v119 方案的无效性、两个是 §2 的结构 pin）。
> **3/3/3 的分布是预期的**，不是漏网。

---

## 5. 测试证据

**环境**：`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`
**锁**：`Test-Path .agent-gradle-lock` = 不存在 → 写 `v120-divergence-locate2`
→ **前台**跑完全部运行 → **绝对路径字面量**删除。**未** assembleDebug、**未** clean、**未**装机。

### 5.1 回归命令（只跑 8 个指定类）

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.ComposerDivergenceLocate2V120Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerDivergenceLocateV119Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerCriterionParityV118Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerPaneBottomCriterionV108Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerCallFingerprintV116Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerTripwireIntegrityV115Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTallEnoughV106Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerUnionBottomP5V109Test"
```

**原文**：

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 3s
```

### 5.2 用例数（`app/build/test-results/testDebugUnitTest/*.xml` 实测）

| 类 | 用例数 | failures | errors | 本轮是否改动 |
|---|---|---|---|---|
| `ComposerDivergenceLocate2V120Test` | **6** | 0 | 0 | **新增** |
| `ComposerDivergenceLocateV119Test` | 7 | 0 | 0 | 未改 |
| `ComposerCriterionParityV118Test` | 8 | 0 | 0 | 未改 |
| `ComposerPaneBottomCriterionV108Test` | 8 | 0 | 0 | 未改 |
| `ComposerCallFingerprintV116Test` | 19 | 0 | 0 | 未改 |
| `ComposerTripwireIntegrityV115Test` | 9 | 0 | 0 | 未改 |
| `ComposerBandTallEnoughV106Test` | 7 | 0 | 0 | 未改 |
| `ComposerUnionBottomP5V109Test` | 11 | 0 | 0 | 未改 |
| **合计** | **75** | **0** | **0** | +6，**净增，无减少** |

### 5.3 过程中的一次编译错（**如实记账**，已修）

首跑 `:app:compileDebugUnitTestKotlin` **失败**（不是断言红）：

```
e: ...ComposerDivergenceLocate2V120Test.kt:183:74 Expression 'dp' of type 'androidx.compose.ui.unit.Dp' cannot be invoked as a function.
```

原因：`dp` 是**扩展属性**不是函数，测试里写成了 `.dp()`。
已全部改成 `.dp`（3 处：`:183` / `:189` / `:275`），**未触碰任何断言语义**。
修正后首跑 **5/6 绿**（第 6 条因单测工作目录是 `app/`、找不到报告文件而红），
已把报告路径改成**双根查找**（`research/…`、`app/research/…`、`../research/…`）⇒ **6/6 绿**。

### 5.1 写入范围

| 路径 | 状态 |
|---|---|
| `app/src/test/java/com/xiufeigo/opencodeshell/ComposerDivergenceLocate2V120Test.kt` | ✅ **新增**（测试文件，6 用例） |
| `research/v97_fix/DIVERGENCE_LOCATE2.md` | ✅ 新增（本报告） |
| `ui/**`（含 `SessionPaneLayout.kt`、`SessionScreen.kt`、`ShellChromeBudget.kt`、`composer/Composer.kt`） | ❌ **零改动** |
| `OcActivity.kt`、`ChatViewModel.kt` | ❌ **零改动** |
| 用户可见字符串 | ❌ **零新增** |
| 既有 pin（v103–v119 全部） | ❌ **一条未改、未删、未放宽** |

---

## 6. 反向验证原文 + 回归原文

反向验证的完整原文见 §4（3 条红，逐条 AssertionError 已抄）。
回归原文见 §5.1（`BUILD SUCCESSFUL in 3s`，8 类 75 用例、0 失败）。

**锁纪律**：`Test-Path .agent-gradle-lock` = 不存在 → 写 `v120-divergence-locate2`
→ **前台**跑完全部 3 次（首跑编译错 / 注入红 / 恢复回归）→
`rm -- "C:\sensorsdata\main\program\opencode-android-shell\.agent-gradle-lock"`（绝对路径字面量）
→ 回读 `Test-Path` = `False` ✅。
**未** assembleDebug、**未** clean、**未**装机、**未**杀模拟器进程。

**`git status --porcelain` 复核**（本轮变更集全文）：

```
?? app/src/test/java/com/xiufeigo/opencodeshell/ComposerDivergenceLocate2V120Test.kt
?? research/v97_fix/DIVERGENCE_LOCATE2.md
?? research/v97_fix/_V120_backup.kt
```

⇒ **三个都是新增文件，全在测试目录与 `research/v97_fix/**` 内**；
**没有任何生产文件出现在变更集里**（`ui/**`、`OcActivity.kt`、`ChatViewModel.kt` 均无）。

---

## 7. 未闭环（如实记账）

### 7.1 −8px **未闭环** —— 只差 §2.3 的 **①**（一条 `dumpsys` 读数）
v119 判「静态解释不了」**成立**，但**原因已定位到行**：v119 的模型把
`floorPx` 硬写成 `0`（`ComposerDivergenceLocateV119Test.kt:85`），
而生产读的是 `SessionScreen.kt:2362-2363` 的 `navigationBars`。
**领先假设 `140 = 132 + 8(nav bar)` 逐位自洽，但未经真机证实**，故**不记为已闭环**。
**下一轮该做的第一件事**：`dumpsys window insets` 读 768 档 `navigationBars` 底 inset。

### 7.2 「60 的归因」是**形态归因**，不是「行号修好了」
`:1467-1468` 与 `:1539` 之间的**反馈**（§1.4）意味着：**60 修得掉，但不是改一行**，
且**改法本身是 Lead 裁定项**（须同时重述 `SessionPaneImeBeliefV77Test.kt:380`
钉住的 `PANE_ESSENTIAL_SPARE_ROOM = 2`）。
**本轮不提出修法，只把它记成「哪个反馈在作祟」。**

### 7.3 §2.3 的读数清单**只能被真机回答**
①③④⑤ 都是设备/AVD 状态，②要同帧取两个来源。**静态代码到此为止**。

---

## 8. 一句话交付

> **60 已归因到行，逐位**：`60 = COMPOSER_MIN_HEIGHT(116) − COMPOSER_MINIMAL_HEIGHT(56)`，
> 因为设备那一侧 `140 = 72 + 56 + 12` **逐位等于一行形态的地板**，而生产按**堆叠**收账。
> **形态判定行**是 `SessionScreen.kt:2385-2389` → `SessionPaneLayout.kt:1039-1040`，
> **阈值行**是 `SessionPaneLayout.kt:1008-1010` 的 **164 dp**；
> 768 判堆叠的唯一原因是 `body = 284 ≥ 164`，
> 而 **284 是钳位（`:1539`）自己的输出**（`SessionScreen.kt:2384` 拿它当输入）——
> **钳位用它的输出选形态，形态又定义钳位的常量**，这是自证循环，也是 v119 觉得「改一行不够」的真正原因。
> **−8px 换路有进展**：v119 的模型把 `floorPx` 硬写成 0（`ComposerDivergenceLocateV119Test.kt:85`），
> 而生产读 `SessionScreen.kt:2362-2363` 的 `navigationBars`；
> **领先假设 `140 = 132 + 8(nav bar)` 逐位自洽**，**判据 = 一条 `dumpsys window insets` 读数**（§2.3 ①），
> 另附 ②③④⑤ 四条备用判据。**在读到 ① 之前不记为已闭环。**
