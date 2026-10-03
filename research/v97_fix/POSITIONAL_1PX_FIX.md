# POSITIONAL_1PX_FIX — v136：600 档「条在屏」形态的两个 1px

状态：**部分闭环** —— 1px 并集对齐**已修 + 已钉**；1px 位置性越界**未修（授权外 + 无真机验证手段）** · 2026-10-04

> 锁约定沿用 `EDITING_QUEUED_LOCK.md:3`（仓库无独立 `*.lock`，本报告文件即锁）。
> 抢锁时刻 = 写入本文件（当时无同名文件、git 树 clean）；本文件定稿 = 释放锁。

---

## 0. 任务与边界

- 目标：把 v135 记账的 600 档「条在屏」`+2`（`unionBottom 263 > paneBottom 261`）拆掉。
- 目标态：`① ≤ 0` 且 `unionPx ≤ band`（回到 48）。
- 硬约束：768 档 `140 / 92`、两档条关 `① = −10 / −12`、600 条关 `readable 51`、48dp 触摸靶一像素不动、缩窗往返不崩、v103–v135 pin 不许放宽。
- 纪律：只跑 `--tests "<类名>"`；未 `assembleDebug` / `clean` / 全量 / 装机；未新增用户可见字符串；未放宽或删除任何既有断言；未造假绊线。

**一句话结论：两个 1px 里只有并集对齐那 1px 在本轮授权内被修掉并钉死；位置性越界那 1px 的成因在区域 padding 与队列条下推量的**关系**上，改它要动 v135 已 pin 的 `COMPOSER_REGION_PADDING` 或队列条预算，而本轮无真机可复验 ⇒ 不动，明确留账。因此真机预期是 `+2 → +1`，**不是** `≤ 0`。**

---

## 1. 两个 1px 的行级归因

### 1.0 复算的原始读数（本轮从真机帧重出，未抄 v135）

`research/v97_verify/5554_md/v134/k600_MEASURE_BARON.xml`（`wm density 160` ⇒ 1dp == 1px）：

| 节点 | bounds | top | h | bottom |
|---|---|---|---|---|
| 宿主盒（pane） | `[0,162][1366,261]` | 162 | 99 | **261** |
| 队列条行 | `[18,184][1348,216]` | 184 | 32 | 216 |
| 队列行 48dp 靶 | `[22,176][1257,224]` | 176 | 48 | 224 |
| **EditText** | `[24,214][405,262]` | **214** | **48** | **262** |
| `Add` 按钮 | `[399,215][441,263]` | **215** | **48** | **263** |
| `Model` chip | `[441,215][559,263]` | 215 | 48 | 263 |
| `Stop` 按钮 | `[1316,215][1364,263]` | 215 | 48 | 263 |

```
unionBottom = max(262, 263) = 263
① = 263 − 261 = +2
```

### 1.1 并集对齐那 1px：`EditText 214` vs 按钮组 `215`

**归因行：`Composer.kt` 一行形态那个 `Row` 的 `verticalAlignment = Alignment.CenterVertically`（修前 `:2195`）。**

算式（机制）：

```
带高 band            = BAND_HEIGHT = composerBandHeightDp(44dp) = max(44, 48) = 48 dp
                                        (Composer.kt:2698 / SessionPaneLayout.kt:150-156)
行内控件实测高        = 奇数（48dp 触摸靶包着一个更矮的视觉体）
CenterVertically 偏移 = roundToInt( (band − child) / 2f )
                      = roundToInt( (48 − 47) / 2f ) = roundToInt(0.5) = 1     ← 半像素**进位**
⇒ 控件被放在 +1，不是 0；它的 48dp 触摸靶再绕着自己居中
⇒ a11y 读出 215..263，而 EditText 读出 214..262
⇒ 并集高 = 263 − 214 = 49，基线 48      ⇒ ① 里的第 2 个 +1
```

**为什么是「位置性对齐」而不是尺寸**：两者**都是 48 高**（上表逐位可查）。差的只是**顶沿**。`49 = 48 + 1` 里的那个 1 是**摆位**多出来的，不是控件大出来的。

**为什么条关形态不暴露这 1px**：条关形态走的是**同一条** `CenterVertically` 代码，但那一帧的 `h600_BARON2.xml` 里 `EditText [24,201][399,249]` 的底沿 249 与宿主底沿 261 之间有 12px 区域 padding 的余量；`① = 249 − 261 = −12` **恰好等于 padding**，即使并集再多 1px 也在 0 以内。**padding 把对齐那 1px 一起吸收了 —— 条关形态不是"没有这 1px"，是"有 1px 但轮不到它说话"。** 条在屏形态把 12px 余量吃掉了 13 − 12 = 1px 之后，这 1px 才第一次顶到判据线上。

### 1.2 位置性越界那 1px：`EditText` 底沿 262 > 宿主底沿 261

**归因行：`SessionScreen.kt:1827` 的区域底部 padding 与队列条下推量之间的关系。**

```
区域底部 padding        = 12 dp   (SessionScreen.kt:1827 `bottom = 12.dp`)
                                     = SessionPaneLayout.COMPOSER_REGION_PADDING (:1449)
条关形态  card top/bot  = 201 / 249
        249 = 261 − 12              ⇒ ① = −12（恰等于 padding，零越界）
条在屏形态 card top     = 214        = 201 + 13     ← 队列条的下推量 push
条在屏形态 card bot     = 262        = 249 + 13     ← 整卡平移，不是长高
真实越界 = 262 − 261   = +1
        push 13 > padding 12         ⇒ 差 1px
```

**它不是布局摆位算错，是预算关系不等**：`队列条下推量 (13) > 区域底部 padding (12)`。`push` 由队列条在 strip `Column`（`Composer.kt:1397` 的 `Column`，`:1407-1436` 的 `ComposerQueuePanel` 是**第一个**子节点）里占位决定；`padding` 由 `SessionScreen.kt:1827` 写死为 12dp。二者没有任何一处代码约束 `push ≤ padding` —— **缺口是「没写这条不等式」，不是「算错了」。**

**为什么条关形态不暴露**：条关时 `push = 0`，`0 ≤ 12` 恒成立，不等式根本用不上。

### 1.3 加总（与 v135 一致）

```
① = unionBottom − paneBottom = 263 − 261 = +2 = 1（§1.2 位置性越界）+ 1（§1.1 并集对齐）
```

---

## 2. 改了什么（diff）

改了 **2 个生产文件、1 个测试文件**，全部在授权内（`ui/session/**` 的 panes/composer 相关）。

### 2.1 `ui/session/composer/Composer.kt`

**(a) 一行形态改为顶沿对齐 —— 消掉 §1.1 的 1px**

```diff
-                    verticalAlignment = Alignment.CenterVertically,
+                    // v136 P0: 一行形态**顶沿对齐**，不居中。
+                    verticalAlignment = if (minimal) Alignment.Top else Alignment.CenterVertically,
```

`band == child` 时 `Top` 与 `CenterVertically` 偏移**都是 0**，逐位相同 ⇒ **48dp 触摸靶一个像素不动**；只有奇数高（本就是错的那一档）被拉回 0。堆叠形态一行未动。

**(b) 两个子节点改用同一条摆位规则**

```diff
-            val unionPx = maxOf(editor.height, toolRow.height)
-            val editorTop = (band - editor.height).coerceAtLeast(0) / 2
+            val editorTop  = SessionPaneLayout.composerBandChildTopPx(band, editor.height)
+            val toolRowTop = SessionPaneLayout.composerBandChildTopPx(band, toolRow.height)
+            val unionBottom = SessionPaneLayout.composerBandUnionBottomPx(band, editor.height, toolRow.height)
             layout(...) {
                 editor.place(0, editorTop)
-                toolRow.place(0, 0)
+                toolRow.place(0, toolRowTop)
+                SessionComposerBandUnion.record(unionBottom, band)   // 只读点，不夹紧不抛
             }
```

原代码是**两套规则**（编辑器居中 / 工具行钉顶沿）。`band=48`、两者都 48 高时两套都给 0，所以**那 1px 不是这一行造成的**；但只要两者高度不等，「一个居中一个钉顶」就会把并集底沿推出带外。现在只有一个规则。

`unionBottom` **刻意不夹紧、不 `check()`** —— 夹紧就是 v135 §1.6 的假绿陷阱，`check()` 在 `layout` 里会杀进程（`CRASH_SHRINK_FIX.md`）。

### 2.2 `ui/session/SessionPaneLayout.kt`

新增两个生产函数（判据量在生产代码里有了可被测试调用的求值点）：

- `composerBandChildTopPx(bandPx, childPx) = (band − child).coerceAtLeast(0) / 2` —— 单一摆位规则，`top + 自己的高 ≤ band` 恒成立。
- `composerBandUnionBottomPx(bandPx, editorPx, toolRowPx)` —— 摆位后的**真实**并集底沿，不收口。

外加只读点 `object SessionComposerBandUnion`（`unionBottomPx` / `bandPx`）。

### 2.3 明确**没有**改的

- `SessionScreen.kt:1827` 的 `bottom = 12.dp` —— 改它要动 v135 的 `theRegionBottomPaddingIsTwelveDpInBothProductionAndSource` 与 768 的 `−10`。
- `queueBarCeiling` / `SessionScreen.kt:1893` —— 改它直接改变队列条渲染高，冲 48/48 全 clickable 与 768 读数。
- `composerBandTouchTargetDp` / `BAND_HEIGHT` / `TOOL_ROW_HEIGHT` —— 48dp 靶与 44dp 行高**逐字未动**。

---

## 3. 补的 pin

新增 `app/src/test/java/com/xiufeigo/opencodeshell/ComposerBandUnionV136Test.kt`（**7 用例**），全部调**生产函数**、不自我复述：

| 用例 | 咬住什么 |
|---|---|
| `eachBandChildIsPlacedByOneRuleAndNeverExceedsTheBand` | `composerBandChildTopPx`：`48/47` 全域 `top + h ≤ band`（含真机那对 47） |
| `theUnionBottomReturnsToTheBandBaselineForTheDevicePair` | 真机那对输入下并集底沿**回到 48**，并记下修前的 49 |
| `theUnionBottomIsBoundedByTheBandForEveryAdmissiblePair` | 8×(band²) 全组合 `union ≤ band` |
| `theOneRowFormAlignsControlsToTheTopEdgeAndTheStackedFormDoesNot` | 解析 `Composer.kt` 源码，`Alignment.Top` 分支在位 |
| `bothBandChildrenArePlacedThroughTheSharedProductionFunction` | 解析源码：两个子节点都走 `composerBandChildTopPx` |
| `theTouchTargetIsStillFortyEightDpOnAPixel` | 48dp 靶 / 48dp 带 / 44dp 封顶，三条硬约束 |
| `theArchivedFrameStillReadsPlusTwoSoTheNextDeviceRunIsTriggered` | **v135 真机读数常量逐字保留为回归触发器**（含 `+2 = 1 + 1`） |

**关键**：第 7 条**仍然断言归档帧读数是 `+2`**。本轮**没有**把 `+2` 改写成「可接受」，也**没有**宣布判据 ① 成立。

---

## 4. 反向验证（撤回修法 ⇒ 必须红）

**注入**：把 `Composer.kt` 一行形态的 `verticalAlignment` 改回 `Alignment.CenterVertically`（即 §1.1 的修法被撤回）。

**命令**（`JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`）：

```
.\gradlew :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ComposerBandUnionV136Test"
```

**结果原文（`_v136_reverse.log`）：**

```
ComposerBandUnionV136Test > theOneRowFormAlignsControlsToTheTopEdgeAndTheStackedFormDoesNot FAILED
    java.lang.AssertionError at ComposerBandUnionV136Test.kt:149

7 tests completed, 1 failed

FAILURE: Build failed with an exception.
> There were failing tests.
BUILD FAILED in 5s
```

**7 条中 1 条变红** —— pin 咬住了生产源。

**回滚核验**：已用编辑工具改回（**未用 git checkout**，遵守 `autocrlf=false` 的行尾纪律），随后回归全绿（§5）。

**中途一次真实红灯保留在案**：第一次编译失败 —— 我把 `object SessionComposerBandUnion` 插到了 `object SessionPaneLayout` 内部，提前闭合了大对象（`SessionPaneLayout.kt:1858` 报 `Syntax error: Expecting a top level declaration`）。已移到文件末尾并补 import。**这处自曝不抹。**

---

## 5. 测试证据

**命令**：

```
.\gradlew :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ComposerBandUnionV136Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBarOnScreenCriterionV135Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerPaneBottomCriterionV108Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerChildUnionV104Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTouchTargetV105Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTallEnoughV106Test"
```

**原文（`_v136_regression.log` 尾部）：**

```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 46s
52 actionable tasks: 4 executed, 48 up-to-date
```

| 类 | 用例数 | 失败 |
|---|---|---|
| `ComposerBandUnionV136Test`（本轮新增） | 7 | 0 |
| `ComposerBarOnScreenCriterionV135Test` | 7 | 0 |
| `ComposerPaneBottomCriterionV108Test` | 9 | 0 |
| `ComposerChildUnionV104Test` | 7 | 0 |
| `ComposerBandTouchTargetV105Test` | 6 | 0 |
| `ComposerBandTallEnoughV106Test` | 7 | 0 |
| **合计** | **43** | **0** |

**用例数未减少**：v103–v135 的既有 6 类共 36 条逐条照旧通过，本轮净增 7 条。
**合规**：只跑 `--tests`；未 `assembleDebug`、未 `clean`、未全量、未装机。
**`git diff --stat`**：`SessionPaneLayout.kt +72 / Composer.kt +55`（2 files, 122 insertions, 5 deletions），全在授权内。

---

## 6. 为什么不降强度

1. **判据 ① 的定义一字未改**（`unionBottom ≤ paneBottom`）；本报告**不宣布**它在条在屏形态下成立。
2. **没有把 `+2` 改写成可接受** —— 新 pin 的第 7 条仍断言归档帧是 `+2`，是**回归触发器**。
3. **没有放宽或删除任何既有断言** —— v103–v135 共 36 条逐条照旧。
4. **48dp 触摸靶一像素不动** —— `band` 仍 `BAND_HEIGHT`、子节点仍以 `maxHeight = band` 测量、`composerBandTouchTargetDp` 未被触碰；`band == child` 时 `Top` 与 `CenterVertically` 偏移都是 0。
5. **不夹紧、不 `check()`** —— `unionBottom` 原样记录，缺陷不会被生产代码藏起来。
6. **没动的就写没动** —— 位置性越界那 1px 见 §7。

---

## 7. 未闭环（本轮**没有**修掉的）

### 7.1 位置性越界那 1px（`EditText` 底沿 `262 > 261`）—— **未修**

- **成因已定到行**：§1.2，`队列条下推量 13 > 区域底部 padding 12`。
- **为什么本轮不动**：要消掉它只有两个杠杆，两个都撞硬约束 ——
  | 杠杆 | 位置 | 撞到什么 |
  |---|---|---|
  | 区域 padding 12 → 13 | `SessionScreen.kt:1827` | v135 `theRegionBottomPaddingIsTwelveDpInBothProductionAndSource` + 768 的 `−10` + 两档条关 `−12` |
  | 队列条预算 −1px | `SessionScreen.kt:1893` `queueBarCeiling` | 48/48 全 clickable 的真机读数 + v135 第 6 条 pin |
  本轮**无真机**，改任一个都无法复验 —— 这正是本仓三轮踩过的假绿坑（`COMPOSER_OVERFLOW_FIX4.md` §1.3）。
- **账目**：该 1px 原样留在 v135 的账上，未被本轮任何改动掩盖。

### 7.2 真机预期：`+2 → +1`，**不是 `≤ 0`**

§1.1 的 1px 修掉后，归档帧的算术变成：

```
unionBottom = 262（按钮组回到 214..262，与 EditText 同顶沿）
① = 262 − 261 = +1        ← 仍是正值，判据 ① 在本形态下**依然不成立**
```

**这是本报告最要紧的一句话：任务书要求「`① ≤ 0` 且 `unionPx ≤ 48`」，本轮只做到了后一半（`unionPx` 回到 48 的机制已修并钉死），前一半因 §7.1 未达成。**

### 7.3 §1.1 的机制归因**需要真机确认**

`(48 − 47)/2f` 半像素进位是与代码逐位自洽的最可能机制（它同时解释了「同样 48 高」与「顶沿差 1」），但**本轮无真机，未验证**。若真机复验发现 `①` 仍是 `+2`，则说明这 1px 另有来源，`theOneRowFormAlignsControlsToTheTopEdgeAndTheStackedFormDoesNot` 仍会红（它咬的是源码），但归因需重做。

### 7.4 其他

- 768 档 `时间线盒 140 / readable 92`、768 判据 ① 的 `+2`：本轮**未触碰**。
- 缩窗往返、60 条关 `readable 51`：仅由 JVM pin 间接覆盖，**未上真机**。

---

## 8. 真机复验步骤（下一轮，emulator-5554）

1. `wm size 1366x600; wm density 160`，进会话页，IME 弹起，**队列非空**（条在屏形态）。
2. `uiautomator dump`，取宿主盒与 `EditText` / `Add` / `Stop` 的 bounds。
3. **判据 A（并集对齐，本轮目标）**：`EditText.top` **必须 ==** `Add.top`（修前 214 vs 215）；并集高 **必须 == 48**（修前 49）。若仍差 1 ⇒ §7.3 成立，归因重做。
4. **判据 B（位置性越界）**：`unionBottom ≤ 261`？按 §7.2 预期读到 `262` / `① = +1`。
5. **复核硬约束**：768 档 `时间线盒 140` / `readable 92`；两档条关 `① = −10 / −12`；600 条关 `readable 51`；`Add` / `Stop` / chip 的 `h = 48` 全 clickable。
6. **缩窗往返**：`wm size` 来回切若干次，确认不崩、composer 不塌。
7. 若要闭合 §7.1，需先拿到「装机 + 复验」授权，再按 §7.1 表格择一杠杆，并**同时**重测 4/5/6 全部项。

---

## 9. 改了哪些文件

| 文件 | 性质 |
|---|---|
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt` | 生产：一行形态 `Alignment.Top` + 统一摆位 + import |
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt` | 生产：2 个新函数 + 1 个只读点对象 |
| `app/src/test/java/com/xiufeigo/opencodeshell/ComposerBandUnionV136Test.kt` | 测试：新增 7 用例 |
| `research/v97_fix/POSITIONAL_1PX_FIX.md` | 本报告（先写盘抢锁 → 定稿释放） |
| `research/v97_fix/_v136_run1.log` / `_v136_reverse.log` / `_v136_regression.log` | 运行日志 |

**未触碰**：`_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/servers/**`、`ui/home/**`、`ui/terminal/**`、`ui/nav/**`、`ui/App.kt`、`OcActivity.kt`。
**行尾**：全程只用编辑工具，未用 `git checkout` / `git restore` 改行尾（遵守 `autocrlf=false`）。
