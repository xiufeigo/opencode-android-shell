# DIVERGENCE_LOCATE —— v119：把 v118 记的两处偏差**静态定位到行**（只定位、不改生产、不装机）

- 诊断工程师：mavis（v119，用 mcode 执行）
- 日期：2026-10-04
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 前序：`CRITERION_PARITY.md`（v118）§7.1（2px）、§7.2（144px）两笔未闭环账
- 授权边界：**生产代码零改动**（`ui/**`、`OcActivity.kt`、`ChatViewModel.kt` 一个字节没碰）、
  **不装机**、**未** assembleDebug / clean。只写本报告 + 一个**新增测试文件**。
- 断言纪律：**不降、只增**。v103–v118 全部 pin **一字未改**，本轮只**新增 7 条**。
  本轮**没有新增任何用户可见字符串**（全在测试 + 报告内）。

> ## 状态：**已定位到行**（A、B 均定位到行 + 最小改动方案 + pin + 反向验证变红）
>
> | | 偏差 | 定位到行 | 最小改动方案 | pin | 反向验证 |
> |---|---|---|---|---|---|
> | **A** | 768 多算 **144px** | `SessionPaneLayout.kt:1467-1468`（值）<br>`SessionPaneLayout.kt:1539`（档位分支） | §4：改 **1 行** | ✅ | ✅ 红 |
> | **B** | 堆叠形态差 **2px** | `Composer.kt:2150`（盒高）+ `Composer.kt:2152`（居中） | §5：改 **1 行** | ✅ | ✅ 红 |
>
> **一句话**：**144 是生产多算的** —— `PANE_ESSENTIAL_HEIGHT` 把 `CHROME_FULL_HEIGHT`（84）
> 算进了一个**从 chrome 底下开始量**的盒子，其中 **84 是二次计入**（纯几何可证），
> 且 v99 钳位**只在 768 档开**（`606 ≥ 284×2`），一开就把 pane **钉死成 essential**。
> **2px 确实由居中取整产生**：`(48 − 44)/2 = 2`，`Composer.kt:2150`+`:2152` 逐位复现 `340`。

---

## 1. A：768 档的 144px —— 逐项算式

### 1.1 生产侧：`606` / `322` / `284` / `446` 各自从哪一行来

设备几何（`wm size 1366x768` + `wm density 160` ⇒ **1dp == 1px**；`COMPOSER_OVERFLOW_FIX8.md:333-345`）：

| 符号 | 值 | `文件:行号` | 原文 | 含义 |
|---|---|---|---|---|
| `paneTop` | `162` | v110 dump | pane 盒上沿 | 时间线上沿 |
| `paneBoxPx` | **`606`** | `SessionScreen.kt:2361` | `val paneBoxPx = with(density) { maxHeight.roundToPx() }` | **pane 盒高**（`768 − 162`，**不含** IME inset、**已在 chrome 之下**） |
| `imeBottomPx` | `474` | `SessionScreen.kt:2367` | `imeBottomPx = WindowInsets.ime.getBottom(density),` | 键盘**自称**的底 inset |
| `essentialPx` | `284` | `SessionPaneLayout.kt:1467-1468` | `val PANE_ESSENTIAL_HEIGHT: Dp =`<br>`    CHROME_FULL_HEIGHT + TIMELINE_MIN_HEIGHT + COMPOSER_MIN_HEIGHT + COMPOSER_REGION_PADDING` | pane 的「essential 形态」= `84 + 72 + 116 + 12` |
| ↳ `CHROME_FULL_HEIGHT` | `84` | `SessionPaneLayout.kt:1437` | `val CHROME_FULL_HEIGHT: Dp = HEADER_HEIGHT + MobileForm.TAB_STRIP_HEIGHT_DP.dp` | header 48（`SessionHeader.kt:1075`）+ tab 条 36（`MobileForm.kt:116`） |
| ↳ `TIMELINE_MIN_HEIGHT` | `72` | `SessionPaneLayout.kt:165` | `val TIMELINE_MIN_HEIGHT: Dp = 72.dp` | 时间线地板 |
| ↳ `COMPOSER_MIN_HEIGHT` | `116` | `SessionPaneLayout.kt:95` | `val COMPOSER_MIN_HEIGHT: Dp = 116.dp` | composer **堆叠**形态地板 |
| ↳ `COMPOSER_REGION_PADDING` | `12` | `SessionPaneLayout.kt:1449` | `val COMPOSER_REGION_PADDING: Dp = 12.dp` | region 竖向装饰总量（`SessionScreen.kt:1827` 的 `bottom = 12.dp`） |
| ↳ `PANE_ESSENTIAL_SPARE_ROOM` | `2` | `SessionPaneLayout.kt:1552` | `const val PANE_ESSENTIAL_SPARE_ROOM: Int = 2` | 闸门倍数 |
| `believedImeBottomPx` | **`322`** | `SessionPaneLayout.kt:1531-1544` | `val roomPx = visibleBoxPx - essentialPx`（`:1541`）<br>`return roomPx + floorPx.coerceAtLeast(0)`（`:1543`） | **被相信的**键盘底 = `606 − 284 = 322` |
| `panePx` | **`284`** | `SessionScreen.kt:2384` | `val bodyHeightDp = (maxHeight - paneBottomDp).coerceAtLeast(0.dp)` | **pane 盒高** = `606 − 322` |
| `paneBottom` | **`446`** | `162 + 284` | 同上 | 生产认为的 pane 下沿 |

**代入（全部生产求值）**：

```
paneBoxPx   = 606                                  SessionScreen.kt:2361
essentialPx = 84 + 72 + 116 + 12 = 284            SessionPaneLayout.kt:1467-1468
闸门        : 606 < 284*2 = 568 ?  否 ⇒ 钳位【开】   SessionPaneLayout.kt:1539
roomPx      = 606 − 284 = 322                      SessionPaneLayout.kt:1541
claimedPx   = 474 − 0 = 474 ;  474 <= 322 ? 否     SessionPaneLayout.kt:1542
believed    = 322 + 0 = 322                        SessionPaneLayout.kt:1543
panePx      = 606 − 322 = 284   ← 【恰好 = essential】 SessionScreen.kt:2384
paneBottom  = 162 + 284 = 446
```

> **注意 `panePx = essentialPx` 不是巧合，是构造性恒等**：钳位返回 `roomPx + floorPx = 可见盒 − essential + floor`，
> 于是 `panePx = 可见盒 − believed = essential − floor`。KDoc `SessionPaneLayout.kt:1528-1529` 自己写着：
> 「it caps the pane at exactly [essentialPx] where it does fire」。

### 1.2 设备侧：`140` 与底 `302`

```
pane 盒（容器，含宿主） [0,162][1366,350]  h=188   COMPOSER_OVERFLOW_FIX8.md:334
dump 容器高 188 − 宿主带高 48 = 140               ⇒ 生产口径 pane 盒 = 140
paneBottom = 162 + 140 = 302                      （v98 旧口径同样指着 302）
```

对照 `dump` 口径：`140` 是**时间线盒/host 盒**（= 容器减整一个 48dp 宿主带），
`302` 是它的下沿。**这两条是 v98 与 v110 两轮独立读数的交点**（`302 − 162` 与 `350 − 48 − 162` 都等于 140）。

### 1.3 差的 144px 由哪几项构成

```
144 = 284(essential) − 140(设备)
    = 【84】 + 【60】
```

| 项 | 值 | 说明 | 可证性 |
|---|---|---|---|
| **chrome 二次计入** | **84** | `PANE_ESSENTIAL_HEIGHT` 把 `CHROME_FULL_HEIGHT`（84）算了进去，而 `paneBoxPx = 606` 是**从 `paneTop = 162 往下量**的盒，chrome 带在 `paneTop` **之上**（`84 ≤ 162`）⇒ 这个 84 **不可能重进这个盒**。 | **纯几何**，不需真机复测（pin：`eighty four of the one forty four…`） |
| 余项 | **60** | `72 + 116 + 12 − 140 = 60`，即 essential 里「时间线地板 + 堆叠 composer 地板 + region 装饰」三项之和，比设备实际的 140 多 60。 | 算术逐位；**归因需真机复测**（见 §7.1） |

```
逐位验算：84 + (72 + 116 + 12 − 140) = 84 + 60 = 144 ✅
```

### 1.4 是谁多算了：**生产**，而且**768 与 600 走的是不同分支**

**分支条件与行号**：`SessionPaneLayout.kt:1539`

```kotlin
if (visibleBoxPx < essentialPx * PANE_ESSENTIAL_SPARE_ROOM) return imeBottomPx
```

| 档 | `visibleBoxPx` | 闸门 `284×2` | 分支 | 结果 |
|---|---|---|---|---|
| **600** | `438` | `568` | `438 < 568` ⇒ **短路**，原样相信 387 | `panePx = 438 − 387 = 51` = 设备 ✅ |
| **768** | `606` | `568` | `606 ≥ 568` ⇒ **钳位开** | `panePx = 606 − 322 = 284` ≠ 设备 140 ❌ |

⇒ **答案**：不是「把某个不该扣的扣了」，也不是「把某个量当成了别的量」，
而是 **768 档走进了 600 档从不进入的 v99 钳位分支**（`SessionPaneLayout.kt:1539`），
而该分支一旦进入，就把 pane **钉成 `PANE_ESSENTIAL_HEIGHT`** —— 这个 essential **本身就多算了 144**
（其中 84 可纯几何证明是 chrome 二次计入）。**600 档之所以「对」，是因为它根本没进这个分支。**

---

## 2. B：堆叠形态的 2px —— 逐位算式与行号

| 符号 | 值 | `文件:行号` | 原文 |
|---|---|---|---|
| 工具行盒（**堆叠**） | **`44`** | `Composer.kt:2150` | `Modifier.fillMaxWidth().height(TOOL_ROW_HEIGHT).padding(horizontal = 8.dp)` |
| `TOOL_ROW_HEIGHT` | `44` | `Composer.kt:2623` | `private val TOOL_ROW_HEIGHT = 44.dp` |
| 强制触摸靶 | **`48`** | `SessionPaneLayout.kt:108` | `const val MIN_INTERACTIVE_COMPONENT_PX: Int = 48`（material3 的 `LocalMinimumInteractiveComponentSize`，`Composer.kt:32` import） |
| 靶的摆放 | **居中** | `Composer.kt:2152` | `verticalAlignment = Alignment.CenterVertically,` |
| 一行形态的带 | `48` | `Composer.kt:2655` | `private val BAND_HEIGHT: Dp = SessionPaneLayout.composerBandHeightDp(TOOL_ROW_HEIGHT)`（v106 **只**改了这一支） |
| region 下沿装饰 | `12` | `SessionScreen.kt:1827` | `.padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 12.dp),` |

**算式**：

```
靶比盒高       = 48 − 44 = 4
居中 ⇒ 下沿探出 = 4 / 2 = 2                      ← 【这 2px】
模型只知宿主盒  = 350 − 12 = 338
设备并集底      = 350 − 12 + 2 = 340   ✅ 逐位
差              = 340 − 338 = 2          ✅
```

> **承重自证**（`the two pixels are load bearing…`）：把 2px 当**已知观测量**反解盒高，
> 用**精确方程** `band − box = 2 × overhang` 扫描 `[36,56]`，**唯一解就是 44**。
> ⇒ **2px 确由居中取整产生，且只属堆叠形态**（一行形态的带已是 48，overhang = 0）。
>
> 注：pin **特意不用整除** `(48−box)/2` 做唯一性自证 —— 整除会把 43 与 44 **一起**映射成 2，
> 那是量化的假唯一性（本轮第一次跑就撞上了，已改为精确方程）。

---

## 3. 新增 pin

**唯一新增文件**：`app/src/test/java/com/xiufeigo/opencodeshell/ComposerDivergenceLocateV119Test.kt`（**7 个用例**）

| # | 用例 | 钉住什么 |
|---|---|---|
| 1 | `the seven six eight tier is the only measured one that trips the v99 clamp` | 闸门 `284×2=568`：**768（606）开、600（438）不开**；600 的 `387` 原样返回、`panePx = 51` 逐位 |
| 2 | `the seven six eight production pane is pinned to the essential form and overruns the device by one hundred forty four` | **当前生产值 == 偏差值**：`panePx = essential = 284`、`paneBottom = 446`、设备 `302`、`284 − 140 = 144` |
| 3 | `eighty four of the one forty four is the chrome band charged to a box that starts below it` | **84 + 60 = 144** 的分解；`84 ≤ paneTop(162)` 的几何前提 |
| 4 | `the one forty four is load bearing it breaks for every essential but the production one` | 扫 `[200,320]`：**只有生产的 essential** 复现 144 |
| 5 | `the two pixel stacked gap is the centred forty eight dp target hanging two px out of the forty four dp box` | 堆叠盒 `44`、靶 `48`、overhang `2`、`350 − 12 + 2 = 340` |
| 6 | `the two pixels are load bearing only the stacked forty four dp box reproduces them` | 精确方程唯一解 = 44；一行形态带 = 48 ⇒ overhang 0（2px **只**属堆叠） |
| 7 | `both pins are pinned on production values so a fix has to break them loudly` | 本轮所有生产侧读数逐位（284/84/12/44/48/闸门 2） |

**不造假绊线**：生产侧一律取自 `SessionPaneLayout` 的**真函数/真常量**
（`believedImeBottomPx`、`PANE_ESSENTIAL_HEIGHT`、`CHROME_FULL_HEIGHT`、
`COMPOSER_MIN_HEIGHT`、`COMPOSER_ROW_HEIGHT`、`composerBandHeightDp`、
`MIN_INTERACTIVE_COMPONENT_PX`、`PANE_ESSENTIAL_SPARE_ROOM`），
`12` 与堆叠盒高 `44` **从生产源码解析**（`SessionScreen.kt:1827` / `Composer.kt:2150`+`:2623`，
沿用 v112 起的手法）。**字面量只出现在真机读数一侧**（v110 dump 原文）。
本轮**主动删掉**了自己最初写的一条伪 pin（`assertEquals(200, 606 - (606 - 200))` —— 两侧都是字面量）。

### 「生产被改成建议方案 ⇒ 这条 pin 会红」

1. **A**：若 `PANE_ESSENTIAL_HEIGHT`（`SessionPaneLayout.kt:1467-1468`）去掉 `CHROME_FULL_HEIGHT`
   ⇒ essential `284 → 200`、768 的 `paneBottom` `446 → 362`
   ⇒ 用例 **2**（`expected:<446> but was:<362>`）、**3**、**4**、**7** 变红。
2. **B**：若 `Composer.kt:2150` 改用 `BAND_HEIGHT(48)`
   ⇒ overhang `2 → 0`、dump 并集底 `340 → 338`
   ⇒ 用例 **5**、**6**、**7** 变红。
3. **⚠️ 额外发现（比上面两条更重要）**：A 的最小改动**会顺手弄坏 600 档** ——
   essential 降到 200 后闸门变成 `200×2 = 400`，而 **600 的 `438 ≥ 400` ⇒ 钳位会在 600 档误开**。
   这条**已被 pin 1 提前钉住**，反向验证时原文见 §4.2。

---

## 4. 最小改动方案（**只写方案，本轮未改任何生产代码**）

### 4.1 A 的方案

**改这一行**：`SessionPaneLayout.kt:1467-1468`

```kotlin
// 现状
val PANE_ESSENTIAL_HEIGHT: Dp =
    CHROME_FULL_HEIGHT + TIMELINE_MIN_HEIGHT + COMPOSER_MIN_HEIGHT + COMPOSER_REGION_PADDING
// 建议
val PANE_ESSENTIAL_HEIGHT: Dp =
    TIMELINE_MIN_HEIGHT + COMPOSER_MIN_HEIGHT + COMPOSER_REGION_PADDING   // 去掉 CHROME_FULL_HEIGHT
```

**理由**：`visibleBoxPx` 是 `maxHeight`，KDoc `SessionPaneLayout.kt:1478-1479` 写明它是
「the body column *without* the IME inset」，而 `SessionScreen.kt:2356-2358` 补一句
「the top insets and the shell's own strip are already out of it」
—— **chrome 早就在这个盒之外了**，再收一遍是二次计入，84px 纯几何可证。

**改后 768 的预测读数**：

```
essential = 72 + 116 + 12 = 200
闸门      = 200 * 2 = 400 ;  606 >= 400 ⇒ 钳位仍开
roomPx    = 606 − 200 = 406 ;  claimed 474 > 406 ⇒ believed = 406
panePx    = 606 − 406 = 200
paneBottom= 162 + 200 = 362        （原 446）
偏差      = 200 − 140 = 60         （原 144）⇒ 144 中的 84 被消掉，余 60 仍需真机归因
dump 侧   = 362 + 48 = 410、362 + 48 − 12 = 398（**与 v110 的 350/340 不符 ⇒ 必须真机复测**）
```

> ⚠️ **这个方案会碰坏 600 档 —— 所以它现在不能单独执行**：
> 闸门 `200×2 = 400 < 438` ⇒ **600 档的钳位会误开**，`panePx` 从 51 变成 `438 − (438−200) = 200`，
> `paneBottom` 从 213 变 362，**600 的 249 ≤ 261 判据直接崩**。
> 要用它，**必须同时**把 `SessionPaneLayout.kt:1539` 的闸门从「按 essential 倍数」改成
> 「按 essential 的一半再让开一段」或等价物；两处一起改才安全。
> **这是 Lead 裁定项**（涉及 `SessionPaneImeBeliefV77Test.kt:380` 的 `assertEquals(2, PANE_ESSENTIAL_SPARE_ROOM)`
> 与 `:202/:213-222` 的闸门语义），**不在本轮授权内**。

### 4.2 A 的替代方案（不动常量，只动闸门）

**改**：`SessionPaneLayout.kt:1539` 的闸门倍数（`PANE_ESSENTIAL_SPARE_ROOM`：`2 → 3`），
使 768 的钳位**彻底不生效**，键盘自称的 474 被原样相信：

```
panePx     = 606 − 474 = 132 ;  paneBottom = 162 + 132 = 294
偏差       = 132 − 140 = −8  （8px 残差，见 §7.1）
600        = 438 < 284*3 = 852 ⇒ 仍短路，213/51/249/261 【一个像素不动】
```

**为什么不碰 600**：闸门在 600 那一档**先于** `essentialPx` 生效
（`SessionScreen.kt:2361-2369` 把 600 的 `438` 交给 `:1539` 判短路 ⇒ `:1537-1539` 直接 `return imeBottomPx`），
**`essentialPx` 后续根本没被读** ⇒ 恒等、不可能受 `PANE_ESSENTIAL_SPARE_ROOM` 取值影响。
**代价**：会同时废掉 v99 钳位对 5554「陈旧浮动 IME 窗」的防护（`PANE_ESSENTIAL_SPARE_ROOM` 被
`SessionPaneImeBeliefV77Test.kt:380` 逐字钉住），**必须重跑 5554 真机复验**才能采纳。

### 4.3 B 的方案

**改这一行**：`Composer.kt:2150`

```kotlin
// 现状
Modifier.fillMaxWidth().height(TOOL_ROW_HEIGHT).padding(horizontal = 8.dp)
// 建议
Modifier.fillMaxWidth().height(BAND_HEIGHT).padding(horizontal = 8.dp)   // 与 v106 的一行形态对齐
```

**改后预测读数**：

```
overhang = (48 − 48)/2 = 0
dump 并集底 = 350 − 12 + 0 = 338     ← 模型的 338 变【逐位】，2px 偏差归零
```

**对 `×`（Add/Stop）的 h=48 与可点性**：
- **h 不变，仍是 48** —— 48 是 material3 的**强制地板**（`Composer.kt:32` / `SessionPaneLayout.kt:108`），
  与盒高无关；`composerBandTouchTargetDp(oneRow=false, …)` 对堆叠形态**原值返回 48**
  （`SessionPaneLayout.kt:1396-1400`），**一个像素都不动**。
- **可点性：不变或略增** —— 靶从「探出盒底 2px」变成「正好填满自己的盒」，
  消除的是**裁剪/溢出**风险，不是可点面积（面积恒 48×48）。
- **代价（显式记账）**：堆叠 composer 卡高 **+4dp**，从 editor 的份额里出（editor 受
  `heightIn`/`EDITOR_MIN_HEIGHT` 兜底）；`COMPOSER_MINIMAL_HEIGHT = 56dp`（`SessionPaneLayout.kt:811`）
  的 v57 账需重算。
- **⚠️ 该行被既有 pin 逐字锁住**：`ComposerEditorFormV80Test.kt:148/154/261` 钉的是
  `"} else { Modifier.fillMaxWidth().height(TOOL_ROW_HEIGHT).padding(horizontal = 8.dp) }"`
  **整行字符串**，`ComposerBandTallEnoughV106Test.kt:216-219`、`ComposerChildUnionV104Test.kt:495-496`、
  `ComposerBandTouchTargetV105Test.kt:480-481`、`ComposerEditorFormV80Test.kt:264`、
  `ComposerOneRowEditorFloorV86Test.kt:328`、`ComposerModelChipV75Test.kt:141` 钉 `TOOL_ROW_HEIGHT = 44.dp`。
  ⇒ 改这一行**必须同时改这 6 个既有 pin**（不是放宽，是随设计意图变更而重述），
  **超出本轮授权**，本轮只出方案。

---

## 5. 反向验证（**真跑一次**，抄原文）

**做法**：改**测试里传给模型的输入**来模拟「生产被改成 §4/§5 的建议方案」——
A：把 `essentialPx()` 改成 `PANE_ESSENTIAL_HEIGHT − CHROME_FULL_HEIGHT`（= 200）；
B：把 `stackedToolRowDp()` 改成返回 `48`。生产代码、pin 断言**一个字都没动**。

**命令**（前台、持锁）：

```
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ComposerDivergenceLocateV119Test"
```

**结果**：`7 tests completed, 7 failed` / `BUILD FAILED in 3s` ⇒ **7 条全红**。

**逐条 AssertionError 原文**（抄自 `app/build/test-results/testDebugUnitTest/TEST-…V119Test.xml`）：

```
RED: the seven six eight tier is the only measured one that trips the v99 clamp
  => java.lang.AssertionError: 600's pane box (438) must stay under the gate (400) — so 600 is untouched

RED: the seven six eight production pane is pinned to the essential form and overruns the device by one hundred forty four
  => java.lang.AssertionError: 768: production pane bottom 446 (the v108 frozen reading's rival)
     expected:<446> but was:<362>

RED: eighty four of the one forty four is the chrome band charged to a box that starts below it
  => java.lang.AssertionError: 84 of the 144 is the chrome double count
     expected:<84> but was:<0>

RED: the one forty four is load bearing it breaks for every essential but the production one
  => java.lang.AssertionError: only the production essential may reproduce the 144px divergence
     expected:<200> but was:<284>

RED: the two pixel stacked gap is the centred forty eight dp target hanging two px out of the forty four dp box
  => java.lang.AssertionError: the stacked form's tool row box is still TOOL_ROW_HEIGHT = 44dp (Composer.kt:2150)
     expected:<44> but was:<48>

RED: the two pixels are load bearing only the stacked forty four dp box reproduces them
  => java.lang.AssertionError: band − stackedBox = 2 × the measured 2px overhang, digit exact
     expected:<0> but was:<4>

RED: both pins are pinned on production values so a fix has to break them loudly
  => java.lang.AssertionError: expected:<284> but was:<200>
```

> **最有价值的一条是第一条**：`600's pane box (438) must stay under the gate (400)`。
> 它**当场证明**了 §4.1 的方案**不是免费的** —— 去掉 chrome 会把 600 档一起拖下水。
> **这正是本轮最该被记住的发现**：144 的根因定位到了行，但**修它的最小改动会碰坏 600**，
> 所以「改哪一行」与「能不能单独改」必须分开回答。

**改回后复跑 ⇒ `BUILD SUCCESSFUL in 3s`**（§6）。

---

## 6. 测试证据

**环境**：`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`
**锁**：`Test-Path .agent-gradle-lock` = 不存在 → `Set-Content .agent-gradle-lock "v119-divergence-locate"`
→ **前台**跑完全部 4 次 → `rm -- "C:\sensorsdata\main\program\opencode-android-shell\.agent-gradle-lock"`（绝对路径字面量）。
**未** assembleDebug、**未** clean、**未**装机。

### 6.1 回归命令（只跑 7 个指定类）

```powershell
.\gradlew.bat :app:testDebugUnitTest `
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
52 actionable tasks: 2 executed, 50 up-to-date
```

### 6.2 用例数（`app/build/test-results/testDebugUnitTest/*.xml` 实测）

| 类 | 用例数 | failures | errors | 本轮是否改动 |
|---|---|---|---|---|
| `ComposerDivergenceLocateV119Test` | **7** | 0 | 0 | **新增** |
| `ComposerCriterionParityV118Test` | 8 | 0 | 0 | 未改 |
| `ComposerPaneBottomCriterionV108Test` | 8 | 0 | 0 | 未改 |
| `ComposerCallFingerprintV116Test` | 19 | 0 | 0 | 未改 |
| `ComposerTripwireIntegrityV115Test` | 9 | 0 | 0 | 未改 |
| `ComposerBandTallEnoughV106Test` | 7 | 0 | 0 | 未改 |
| `ComposerUnionBottomP5V109Test` | 11 | 0 | 0 | 未改 |
| **合计** | **69** | **0** | **0** | +7，**净增，无减少** |

### 6.3 写入范围

| 路径 | 状态 |
|---|---|
| `app/src/test/java/com/xiufeigo/opencodeshell/ComposerDivergenceLocateV119Test.kt` | ✅ **新增**（测试文件，7 用例） |
| `research/v97_fix/DIVERGENCE_LOCATE.md` | ✅ 新增（本报告） |
| `research/v97_fix/_V119_backup.kt`、`_v119_run*.log`、`_v119_reverse.log`、`_v119_regression.log` | ✅ 新增（过程证据） |
| `ui/**`（含 `SessionPaneLayout.kt`、`SessionScreen.kt`、`composer/Composer.kt`） | ❌ **零改动** |
| `OcActivity.kt`、`ChatViewModel.kt`、`_upstream/`、`app/build.gradle.kts` | ❌ **零改动** |
| 用户可见字符串 | ❌ **零新增**（全在测试 + 报告内） |
| 既有 pin | ❌ **一条未改、未删、未放宽** |

`git status` 复核：本轮仅新增上述测试文件与 `research/v97_fix/**`；无生产文件出现在变更集里。

---

## 7. 未闭环（如实记账）

### 7.1 144 里的**余 60 尚未归因到具体行**
84 已纯几何证死（chrome 二次计入）。剩下的 60 = `72 + 116 + 12 − 140`，
指向「时间线地板 72 / 堆叠 composer 地板 116 / region 装饰 12」三项与设备实际 140 的差额，
**需要一次真机复测**（在 768 档把 chrome 实际占高、堆叠形态实际占高、region 装饰实际占高
各读一次）才能判定是「essential 形态选错」还是「设备口径另有一套」。
另外：即使把钳位彻底关掉（§4.2），768 仍差 **−8px**（`132` vs `140`）——
**这 8px 本轮无法从静态代码解释**，同样必须真机复测。

### 7.2 A 的最小改动**不能单独执行**
见 §4.1 与 §5 第一条红字：去掉 `CHROME_FULL_HEIGHT` 会让 600 档的闸门从 `568` 掉到 `400`，
**600 的钳位会误开**、`panePx` 51 → 200、`paneBottom` 213 → 362、`249 ≤ 261` 判据崩。
⇒ 「改哪一行」已定位，**「能不能单独改」已证否**。要采纳必须 `:1467-1468` 与 `:1539` 一起改，
并重跑 `SessionPaneImeBeliefV77Test`（其 `:380` 逐字钉住 `PANE_ESSENTIAL_SPARE_ROOM = 2`）。
**这是 Lead 裁定项，不在本轮授权内。**

### 7.3 B 的改动会撞 6 个既有 pin
`ComposerEditorFormV80Test.kt:148/154/261` 钉的是**整行字符串**，
另 5 处钉 `TOOL_ROW_HEIGHT = 44.dp`（§4.3 已列全）。改 `Composer.kt:2150` 必须同时重述这 6 个 pin。

### 7.4 A/B 两处改完都必须真机复测
两者都会改变 768 档的**绝对坐标**（A 动 pane 下沿、B 动并集下沿），
v110 的 `350 / 340` 读数**不能**直接沿用；按本仓惯例，**先真机复测、再改 pin**。

### 7.5 锁与写盘
抢锁：`Test-Path .agent-gradle-lock` = 不存在 → 写 `v119-divergence-locate` → **前台**跑完 4 次
（2 次修正 + 1 次反向 + 1 次回归）→ 绝对路径字面量删除。本报告**先落盘**再补证据。

---

## 8. 一句话交付

> **144px 是生产多算的**：错在 `SessionPaneLayout.kt:1467-1468` 的 `PANE_ESSENTIAL_HEIGHT`
> —— 它把 `CHROME_FULL_HEIGHT`（84）算进了一个**从 chrome 底下开始量**的 `visibleBoxPx`（`SessionScreen.kt:2361`），
> **84 二次计入**是纯几何可证的；其余 60 = `72+116+12−140`，待真机归因。
> **放大它的是档位分支** `SessionPaneLayout.kt:1539`（`606 ≥ 284×2` 开、`438 < 568` 不开）——
> 600 档之所以对，是因为**从不进这个分支**；一进就把 pane 钉成 essential（`paneBottom = 446` vs 设备 `302`）。
> **2px 确由居中取整产生**：`Composer.kt:2150` 的 44dp 盒装 `SessionPaneLayout.kt:108` 的 48dp 靶、
> 在 `Composer.kt:2152` `Alignment.CenterVertically` 居中 ⇒ `(48−44)/2 = 2` ⇒ `350 − 12 + 2 = 340` 逐位。
> **但两个最小改动都不是免费的**：A 会把 600 档闸门从 568 拖到 400（**已由 pin 提前钉住并当场变红**），
> B 会撞 6 个逐字 pin —— 两者都必须与既有 pin 一起重述、且先真机复测，**属 Lead 裁定**。
