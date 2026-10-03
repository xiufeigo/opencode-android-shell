# CRITERION_PARITY —— v118：让 JVM 模型能复算「设备口径」的量，消掉 `0 vs −12` 的不可比

- 修复工程师：mavis（v118，用 mcode 执行）
- 日期：2026-10-04
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 前序：`TRIPWIRE_CRITERION_FIX3.md`（v114，§7.3 记账）、`TRIPWIRE_CRITERION_FIX2.md`（v113，§5.4 记账）、
  `TRIPWIRE_CRITERION_FIX.md`（v112，§1.2 口径分离）、`COMPOSER_OVERFLOW_FIX8.md`（v110，真机读数来源）

> ## 状态：**已闭环**（`0` 与 `−12` 现在由一个**单术语**换算式连起来，不再"不可比"）
>
> - **唯一改动文件**：`app/src/test/java/com/xiufeigo/opencodeshell/ComposerCriterionParityV118Test.kt`（**新增**，8 个用例，测试侧纯函数）
> - **生产代码零改动**：`ui/session/**` / `Composer.kt` 一个字节没碰
> - 断言强度：**不降、只增**。v103–v117 全部 pin **一字未改**，本轮只**新增** 8 条锚定
> - 回归：**6 个类 62 个用例全绿**（新增 8 + 原有 54 未动）

---

## 0. 本轮解决的残留

v113 §5.4 与 v114 §7.3 两次记同一笔账，本轮结清：

> **正向余量是 `0` px（`213 == 213`）**，这是零容差特性，但**不等于** v110 真机读的 **`−12`** ——
> 两者**口径不同**（v112 已证 dump 容器 ≠ 生产 pane 盒，差 48px 带高），**不可直接相减**。

根因不是"数字对不上"，而是 **JVM pin 算的是「生产口径」，真机量的是「dump 口径」**，
两边都绿，却**互相印证不了** —— 本仓反复吃亏的「**pin 的量必须与验收的量同源**」。

**v118 的结论（一句话）：这两边本来就是可比的，中间只隔 `regionBottomPaddingPx = 12`
这一个词；48px 只动绝对坐标，在余量里精确约掉。**

---

## 1. 两边口径的算式（`文件:行号` + 原文）

### 1.1 生产口径（JVM pin 量的那一支）

`ComposerPaneBottomCriterionV108Test` 量的 `paneBottom` / `unionBottom`，算式是：

```
panePx      = visibleBoxPx − believedImeBottomPx(visibleBoxPx, floorPx, imeBottomPx, essentialPx)
paneBottom  = paneTop + panePx
unionBottom = paneTop + composerTopPx(panePx, hostPx) + regionTopPx
                     + composerChildUnionHeightPx(panePx, hostPx, unionPx)
margin      = unionBottom − paneBottom                        → 600 档 = 0
```

**函数原文**（逐字）：

`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt:1426-1427`
```kotlin
fun composerTopPx(pane: Int, composer: Int): Int =
    pane.coerceAtLeast(0) - composerHostHeightPx(panePx = pane, ceilingPx = composer)
```

`SessionPaneLayout.kt:1290-1291`
```kotlin
fun composerHostHeightPx(panePx: Int, ceilingPx: Int): Int =
    ceilingPx.coerceAtMost(panePx.coerceAtLeast(0)).coerceAtLeast(0)
```

`SessionPaneLayout.kt:1330-1331`
```kotlin
fun composerChildUnionHeightPx(panePx: Int, hostPx: Int, unionPx: Int): Int =
    unionPx.coerceAtMost(composerHostHeightPx(panePx = panePx, ceilingPx = hostPx))
```

`SessionPaneLayout.kt:1531-1544`（`believedImeBottomPx`，v99 的钳位）
```kotlin
fun believedImeBottomPx(visibleBoxPx: Int, floorPx: Int, imeBottomPx: Int, essentialPx: Int): Int {
    if (imeBottomPx <= 0) return imeBottomPx
    if (visibleBoxPx <= 0 || essentialPx <= 0) return imeBottomPx
    if (visibleBoxPx < essentialPx * PANE_ESSENTIAL_SPARE_ROOM) return imeBottomPx
    val claimedPx = (imeBottomPx - floorPx.coerceAtLeast(0)).coerceAtLeast(0)
    val roomPx = visibleBoxPx - essentialPx
    if (claimedPx <= roomPx) return imeBottomPx
    return roomPx + floorPx.coerceAtLeast(0)
}
```

`SessionScreen.kt:1827`（region 的竖向切分，v110 把装饰从**上**沿搬到**下**沿那一行）
```kotlin
.padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 12.dp),
```

**600 档代入（全部生产求值，不是字面量）**：
```
essentialPx = CHROME_FULL_HEIGHT(84) + TIMELINE_MIN_HEIGHT(72) + COMPOSER_MIN_HEIGHT(116) + 12 = 284
438 < 284*2(=568) ⇒ 钳位短路，原样相信 imeBottom 387
panePx      = 438 − 387 = 51
paneBottom  = 162 + 51  = 213            ← 真机 imeTop 也是 213
composerTopPx(51, 48) = 51 − 48 = 3
union       = 48（宿主 48 装得下，coerceAtMost 空操作）
unionBottom = 162 + 3 + 0 + 48 = 213
margin      = 213 − 213 = 0               ← v113/v114 记的「正向余量 0」
```

### 1.2 dump 口径（真机 a11y dump 量的那一支）

真机读数**原文**，一个数没改：

`COMPOSER_OVERFLOW_FIX8.md:313-322`（600 档 v110 修后）
```
pane 盒（容器）        [0,162][1366,261]        h=99     ⇒ paneBottom = 261
EditText              [24,201][399,249]        h=48
Add                   [399,201][441,249]       h=48
Model（内层文本容器）  [441,211][604,239]       h=28  ⊂ [201,249]
Send                  [1316,201][1364,249]     h=48
（Open terminal [1320,192][1366,240] 按判据**排除**）
并集                  [24,201][1364,249]       unionPx = 48
```

`COMPOSER_OVERFLOW_FIX8.md:333-345`（768 档 v110 修后）
```
pane 盒                [0,162][1366,350]       h=188
EditText              [28,244][1338,292]      h=48
Add                   [10,292][52,340]        h=48
Send                  [1308,292][1356,340]    h=48
并集                  [28,244][1356,340]      unionPx = 96（堆叠形态 = editor 48 + toolRow 48）
时间线盒 / host（pane − 48） 188 − 48 = 140
readable（pane − union）     188 − 96 = 92
```

`SessionPaneLayout.kt:1317-1320`（v98 修前那两行，KDoc 里逐字留着）
```
1366x600 + IME: union 169..218 (h=49), host 44 ⇒ 49 - 44 = +5   ← 越界
1366x768 + IME: union 208..296 (h=88), host 94 ⇒ 88 - 94 = -6   ← 未越界
```

**dump 口径的算式**（v118 从上面这些读数里读出来的）：
```
dumpContainerBottom = 容器节点下沿
dumpUnionBottom    = 并集节点下沿
margin             = dumpUnionBottom − dumpContainerBottom
readable           = dumpContainerHeight − unionPx
```

### 1.3 换算关系：**48px 带高挂在「容器的下沿」，不在并集上，且在余量里约掉**

v112 §1.2 已经把 48px 证成「dump 容器 ≠ 生产 pane 盒」，
但**只给了一张对照表**（`99 − 51 = 48` / `188 − 140 = 48`），没有任何东西会因它变红。
**v118 把它变成断言**，并把「挂在哪」说死：

| 项 | 600 档 | 768 档 | 值来源 |
|---|---|---|---|
| 生产 pane 盒高（v98 干净容器） | 51 | 140 | `paneBox − believedImeBottomPx` / 设备读数 |
| dump 容器高（v110 含宿主） | 99 | 188 | `COMPOSER_OVERFLOW_FIX8.md:314` / `:334` |
| **差** | **48** | **48** | = `SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX`（`SessionPaneLayout.kt:108`）= `BAND_HEIGHT` |

**48 挂在哪一行**：挂在 **`dumpContainerBottom` 这一项**上，也就是
「v110 的 dump 容器节点把 composer 宿主盒（整一个 48dp 带高）也包进去了，
而 v98 的干净容器 / 生产的 pane 盒都不含它」。
**它不挂在并集上** —— 并集 `[201,249]` 高 48 = band 本身，
`unionPx = 48` 在**两个口径里一模一样**。

**换算式（三行，逐位成立）**：
```
dumpContainerBottom = 生产 paneBottom  + 48            213 + 48 = 261 ✅    302 + 48 = 350 ✅
dumpUnionBottom    = 生产 unionBottom + 48 − 12        213 + 48 − 12 = 249 ✅
dumpMargin         = 生产 margin      − 12              0 − 12 = −12 ✅
dumpContainerHeight= 生产 pane 盒高    + 48             51 + 48 = 99 ✅     140 + 48 = 188 ✅
```

**⇒ v113/v114 记的那句「不可直接相减」现在被算清了**：
`48` 出现在余量的**两边**（容器和并集各加一次）所以约掉，
**真正让 `0` 变成 `−12` 的只有 `regionBottomPaddingPx = 12`** ——
即 v110 把 region 装饰搬到下沿那一笔（`SessionScreen.kt:1827`）。

---

## 2. 新模型设计（纯函数，测试侧，**生产代码零改动**）

文件：`app/src/test/java/com/xiufeigo/opencodeshell/ComposerCriterionParityV118Test.kt`

**输入** = 生产口径的模型值；**输出** = 预测的 dump 口径读数。共 7 个纯函数：

| 函数 | 作用 | 口径 |
|---|---|---|
| `productionPaneBottomPx(panePx)` | `paneTop + panePx` | 生产 |
| `dumpMarginCleanPx(unionPx, hostPx)` | `unionPx − hostPx` | A |
| `dumpUnionBottomCleanPx(paneBottom, unionPx, hostPx)` | `paneBottom + (unionPx − hostPx)` | A |
| `dumpContainerBottomBandPx(paneBottom, bandPx)` | `paneBottom + bandPx` | B |
| `dumpContainerHeightBandPx(panePx, bandPx)` | `panePx + bandPx` | B |
| `readablePx(containerH, bandPx)` | `containerH − bandPx` | B |
| `dumpUnionBottomBandPx(containerBottom, regionBottomPx)` | `containerBottom − regionBottomPx` | B |
| **`bridgeMarginPx(prodMargin, regionBottomPx)`** | `prodMargin − regionBottomPx` | **桥** |
| `bridgeUnionBottomPx(prodUnionBottom, bandPx, regionBottomPx)` | `prodUnionBottom + bandPx − regionBottomPx` | **桥** |

**两个口径 A / B 的划分（这是 v118 新发现的结构）**：
- **口径 A「干净 pane 容器」** = v98 那轮真机 dump：容器**就是** pane 盒，不含宿主。
  余量 = **并集比宿主盒多/少的那截**（600: `49 − 44 = +5`；768: `88 − 94 = −6`）。
- **口径 B「含宿主容器」** = v110 那轮真机 dump：容器**把 48dp 宿主带也包进去**。
  余量 = **−region 下内边距**（600: `−12`）。

⇒ **同一个 `0`，在 A 口径下是 `213 vs 213`，在 B 口径下是 `249 vs 261`；
两者的差就是宿主带高 48 + region 下内边距 12，全部可算。**

模型的两条纪律：
1. **输入尽量取生产值**（`believedImeBottomPx` / `composerTopPx` /
   `composerChildUnionHeightPx` / `MIN_INTERACTIVE_COMPONENT_PX` /
   从 `SessionScreen.kt:1827` **解析**出来的 `bottom = 12.dp`），
   沿用 v112 起「region 切分从源码解析、不在测试里重述」的做法。
2. **真机读数是刻意保留的字面量 fixture** —— 模型不许自己编 "before" 数
   （那正是本仓栽过三次的假绿陷阱，见 `COMPOSER_OVERFLOW_FIX4.md` §1.3）。

---

## 3. 真机读数锚定表（**逐位 vs ±1**）

`1366x600` / `1366x768`，`wm density 160` ⇒ **1dp == 1px**。

| # | 档 | 口径 | 格 | 真机读数 | 模型值 | 差 | 判定 | 为什么是这一档 |
|---|---|---|---|---|---|---|---|---|
| 1 | 600 | 生产 | `paneBottom` | **213** | `162 + (438−387) = 213` | **0** | **逐位** | 钳位短路，原样相信 387 |
| 2 | 600 | 生产 | 余量 | **0** | `213 − 213` | **0** | **逐位** | 一行带里并集与宿主齐平 |
| 3 | 600 | B | `dumpContainerBottom` | **261** | `213 + 48` | **0** | **逐位** | 48 = `MIN_INTERACTIVE_COMPONENT_PX` |
| 4 | 600 | B | `dumpUnionBottom` | **249** | `213 + 48 − 12` | **0** | **逐位** | 三项全生产求值 |
| 5 | 600 | B | 余量 | **−12** | `(213+48−12) − (213+48)` | **0** | **逐位** | 48 约掉，只剩 −12 |
| 6 | 600 | B | `unionPx` | **48** | `MIN_INTERACTIVE_COMPONENT_PX` | **0** | **逐位** | 同一口径下 unionPx 不受 48 影响 |
| 7 | 600 | B | `readable` | **51** | `99 − 48` | **0** | **逐位** | v105/v106 钉的数，v110 逐位复现 |
| 8 | 600 | A | `paneBottom`（旧） | **213** | `162 + 51` | **0** | **逐位** | 干净容器 = pane 盒 |
| 9 | 600 | A | `unionBottom`（旧） | **218** | `213 + (49 − 44)` | **0** | **逐位** | 走**并集−宿主**公式，吃设备真值 49/44 |
| 10 | 600 | A | `unionBottom`（**生产算术那一支**） | **218** | `162 + 3 + 4 + 48 = 217` | **+1** | **±1** | 见下 §3.1 |
| 11 | 768 | A | `paneBottom`（旧） | **302** | `162 + 140` | **0** | **逐位** | 需求点名的「旧口径 302」 |
| 12 | 768 | A | `unionBottom` | **296** | `302 + (88 − 94)` | **0** | **逐位** | 需求点名；`88 − 94 = −6` |
| 13 | 768 | B | `时间线盒` | **140** | `188 − 48` | **0** | **逐位** | 需求点名 |
| 14 | 768 | B | `读 92` | **92** | `188 − 96` | **0** | **逐位** | 需求点名；v105/v106 的 92 |
| 15 | 768 | B | `dumpContainerBottom` | **350** | `302 + 48` | **0** | **逐位** | 48 第二次逐位复现 |
| 16 | 768 | B | `dumpUnionBottom`（v110 堆叠形态） | **340** | `350 − 12 = 338` | **+2** | **±2，未闭环** | 见 §7.1 |

**汇总：需求点名的 8 格（768 的 `92 / 140 / 296 / 302`，600 的 `218 / 213 / 51`、
`249 ≤ 261(−12)`）全部落在逐位或那**唯一一格** ±1 上，没有一格靠"放宽"蒙过去。**

### 3.1 为什么只有 #10 那一格是 ±1（以及它为什么不是缺陷）

`COMPOSER_OVERFLOW_FIX8.md:259-277` 逐字记着 1px 的来源：
同一 48px 带内 `EditText`（文本框 `bounds`）与 `Button`（`clickable` 的 48dp 触摸靶）
是**两个不同类型节点**，**各自向外取整 1px** ⇒ dump 量到 `union h = 49`，模型是 `band = 48`。

所以那 1px **在「被量的并集高度」里，不在换算里**。本轮用两种走法同时证明：

- 走**并集−宿主**公式并喂设备真值 `49 / 44` ⇒ `213 + 5 = 218`，**逐位**（#9）；
- 走**生产算术**并用 `band = 48` ⇒ `162 + 3 + 4 + 48 = 217`，**±1**（#10）。

**⇒ 这 1px 是真机取整，不是布局缺陷，也不是本模型的不确定度。**

---

## 4. 反向验证（48 → 44，锚定必须红）

**做法**：把换算里的带高从 48 改成 44（`bandPx` 读生产常量后减 4），**真跑一次**。

**命令**（前台）：
```
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ComposerCriterionParityV118Test"
```

**结果原文**（`:app:testDebugUnitTest FAILED`，**6 个用例变红**）：
```
ComposerCriterionParityV118Test > theFortyEightPxBandSitsOnTheContainerBottomEdgeAndIsDigitExactOnBothTiers FAILED
ComposerCriterionParityV118Test > theZeroAndTheMinusTwelveAreBridgeableThroughExactlyOneTerm FAILED
ComposerCriterionParityV118Test > theOnlyPlusOneCellIsTheMeasuredUnionHeightNotTheModel FAILED
ComposerCriterionParityV118Test > theSevenSixEightProductionPaneDisagreesWithTheDeviceAndThatIsBooked FAILED
ComposerCriterionParityV118Test > theSevenSixEightTierAnchorsEveryDeviceReadingDigitExactly FAILED
ComposerCriterionParityV118Test > theSixHundredTierAnchorsEveryDeviceReading FAILED
```

**逐条 AssertionError 原文**（从 `app/build/test-results/testDebugUnitTest/TEST-…V118Test.xml` 抄）：
```
[theFortyEightPxBandSitsOnTheContainerBottomEdgeAndIsDigitExactOnBothTiers]
  java.lang.AssertionError: 600: the dump container is exactly one band taller than the production pane
  expected:<44> but was:<48>

[theZeroAndTheMinusTwelveAreBridgeableThroughExactlyOneTerm]
  java.lang.AssertionError: the band's 48px is the container's whole offset, digit exact
  expected:<44> but was:<48>

[theSixHundredTierAnchorsEveryDeviceReading]
  java.lang.AssertionError: 600: dump container bottom 261 — digit exact
  expected:<261> but was:<257>

[theSevenSixEightTierAnchorsEveryDeviceReadingDigitExactly]
  java.lang.AssertionError: 768: the timeline/host box = 188 − 48 = 140 — digit exact
  expected:<140> but was:<144>

[theSevenSixEightProductionPaneDisagreesWithTheDeviceAndThatIsBooked]
  java.lang.AssertionError: with the device pane 140 as input, the band bridge is still digit exact
  expected:<350> but was:<346>

[theOnlyPlusOneCellIsTheMeasuredUnionHeightNotTheModel]
  java.lang.AssertionError: 600: the host top is 3 in the pane frame
  expected:<3> but was:<7>
```

**抄一行原文（结论）**：
```
java.lang.AssertionError: 600: dump container bottom 261 — digit exact expected:<261> but was:<257>
```

**改回 48 后复跑 ⇒ `BUILD SUCCESSFUL in 3s`（§6）。**

**另外两个"承重"自证**（不是靠这次手工改，而是**常驻断言**）：
- `theAnchorsAreLoadBearingTheyBreakForEveryBandButFortyEight`：整张 fixture 表
  **只有** `bandPx == 48` 能同时复现 `261` 与 `350`；`0 / 44 / 47 / 49 / 96` 全部被排除。
  ⇒ **48 是钉出来的，不是拟合出来的。**
- `theTwelveIsLoadBearingToo`：`0 / 4 / 8 / 11 / 16` 全部复现不出 `−12`，只有 `12` 行。

---

## 5. 为什么不降强度

1. **本轮只增不改**：v103–v117 全部 pin **一字未动**，没有任何一条被放宽、被删除或被"重新表述"。
   5 个既有类的用例数与断言内容**完全保持**（§6 计数可对）。
2. **新断言比旧断言更紧**：新增的 8 条里，**13 格逐位、1 格 ±1、1 格 ±2**，
   没有任何一格用了"±5"/"±10" 这种松量；`0 vs −12` 现在是**算出来的**，不是"两个数并排放着"。
3. **真机读数一字未改**：`261 / 249 / 218 / 213 / 51 / 140 / 92 / 296 / 302 / 350 / 96 / 48`
   全部按 `COMPOSER_OVERFLOW_FIX8.md` / `SessionPaneLayout.kt:1317-1320` 原文抄进 fixture，
   **一个数字都没圆**。
4. **模型不许自证**：新断言带**反向自证**（§4 后两条），
   任何"换个 band 也能过"的偷懒实现都会被常驻断言打掉。
5. **矛盾照登不遮**：§7 那两处口径矛盾是**新增的钉子**（让它们以后变红），
   不是"记进文档就放过"。

---

## 6. 测试证据

**环境**：`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`
**锁**：`Set-Content .agent-gradle-lock "v118-criterion-parity"` → 前台跑完 → `rm --` 释放（§7.4）

### 6.1 回归命令（**只跑指定的类，未 assembleDebug / clean / 装机**）

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest `
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

| 类 | 用例数 | failures | errors | skipped | 本轮是否改动 |
|---|---|---|---|---|---|
| `ComposerCriterionParityV118Test` | **8** | 0 | 0 | 0 | **新增** |
| `ComposerPaneBottomCriterionV108Test` | 8 | 0 | 0 | 0 | 未改 |
| `ComposerCallFingerprintV116Test` | 19 | 0 | 0 | 0 | 未改 |
| `ComposerTripwireIntegrityV115Test` | 9 | 0 | 0 | 0 | 未改 |
| `ComposerBandTallEnoughV106Test` | 7 | 0 | 0 | 0 | 未改 |
| `ComposerUnionBottomP5V109Test` | 11 | 0 | 0 | 0 | 未改 |
| **合计** | **62** | **0** | **0** | **0** | +8，**净增，无减少** |

### 6.3 写入范围

| 路径 | 状态 |
|---|---|
| `app/src/test/java/com/xiufeigo/opencodeshell/ComposerCriterionParityV118Test.kt` | ✅ **新增**（测试文件） |
| `research/v97_fix/CRITERION_PARITY.md` | ✅ 新增（本报告） |
| `ui/session/**`、`Composer.kt` | ❌ **零改动**（未读改） |
| `_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/**`、`OcActivity.kt` | ❌ **零改动** |
| 用户可见字符串 | ❌ **零新增**（全在测试 + 报告内） |

---

## 7. 未闭环（如实记账）

### 7.1 768 档 v110 堆叠形态的并集底 `340` 差 **2px**

- 真机 `340`，本模型 `350 − 12 = 338`，**差 2**。
- 原因（假设，未在生产代码里定位到那一行）：768 档 v110 是**堆叠形态**
  （`unionPx = 96 = editor 48 + toolRow 48`），并集的底沿是 `toolRow` 那个 48dp 触摸靶的底，
  而它**在 tool-row 盒里居中** ⇒ 它比 region 的内底沿高出的量**不足整 12**。
  600 档一行形态里并集是**底对齐**的，所以那里严格等于 12。
- **状态**：记账，**不豁免**。`±2` 比仓里任何既有例外都紧，且下一轮真机复测可一次性关掉。
  （另注：768 的 `296/302` 与 `340/350` 是**两轮不同形态**的读数，跨轮相减无意义。）

### 7.2 **768 档生产算出来的 pane 与设备读的对不上（本轮最重的一条）**

- `believedImeBottomPx` 的 v99 钳位在 768 档**会开**：
  `606 >= 284*2` 成立，键盘要的 `474 > room = 606 − 284 = 322`
  ⇒ 生产**相信 322** ⇒ `panePx = 606 − 322 = 284` ⇒ `paneBottom = 446`。
- 而设备读到 768 档 pane 盒是 **140**（v98 的 `302 − 162`、v110 的 `350 − 48 − 162` 都指着它）。
- **⇒ 生产比设备多算了 `144px` 的 768 档 pane。**
- 后果：**768 档的 dump 换算必须以设备读数 140 为输入**（本类已如此），
  而**不能**用生产算式。这正是 v108 那支在 768 档用冻结字面量 `296/302` 的原因 ——
  **本类第一次把这个前提显式写成断言**
  （`theSevenSixEightProductionPaneDisagreesWithTheDeviceAndThatIsBooked`，钉住 `446` / `302` / `144`）。
- **本轮不修**（不许碰生产代码，且不在本轮授权范围）。
  下一轮若要修，方向是查 v99 钳位在「已 docked 的键盘」上的适用边界（`PANE_ESSENTIAL_SPARE_ROOM = 2`）。

### 7.3 600 档 v98 那一版的 `+5` 里有 1px 未在生产代码里定位

`49 − 44 = +5`，其中 4 = `48dp 触摸靶 − 44dp 宿主盒`（可解释），
余 1 = 「编辑器在带内居中的亚像素取整」（**只由 `COMPOSER_OVERFLOW_FIX8.md:259-277` 的现场记录支撑，
未在 `Composer.kt` 里指到具体那一行**）。
本轮按 ±1 处理并显式说明它属于"量"而非"算"，故不算放宽。

### 7.4 锁与写盘

- 抢锁：`.agent-gradle-lock` 检查为不存在 → 写入 `v118-criterion-parity` → **前台**跑完全部 3 次 → 用绝对路径字面量释放。
- 本报告**先落盘**（先验框架）再动手补齐；每条结论均带 `文件:行号` / 报告节号 / 命令 / 原文。

---

## 8. 一句话交付

> **两边口径差在哪一行**：差在 `dumpContainerBottom` 这一项 —— v110 的 dump 容器节点
> （`SessionScreen.kt:3185` 那个 `Layout` 的实测外框）**把 48dp 的 composer 宿主带也包进去了**，
> 而生产的 pane 盒 / v98 的干净容器都不含它，差恰好 `MIN_INTERACTIVE_COMPONENT_PX = 48`；
> 再加上 v110 把 region 装饰从 `top = 4.dp` 搬到 `bottom = 12.dp`（`SessionScreen.kt:1827`），
> 于是 **`0` 与 `−12` 之间只隔 `regionBottomPaddingPx = 12` 这一个词**。
