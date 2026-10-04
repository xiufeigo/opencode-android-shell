# SELFPROOF_REWRITE — v153：Lead 对自证循环的裁定落地（等价改写 + 翻转点余量 pin）

状态：**🔴 B-1 未落地（按任务书的"发现不等价就停"分支处置）· 任务 2 已落地并加 pin** · 日期：2026-10-04
来源裁定：`research/v97_fix/V108_AND_SELFPROOF.md`（v150）§B.3 / §B.4

> **锁约定**（沿用 `PIN_DEVICE_HARDLINK.md:6-7`）：本报告文件即锁（首次写入 10:49 抢锁）。
> Gradle 锁 `.agent-gradle-lock`：先等 v152（release 体积实测）释放 → 10:54:49 写锁 →
> 全部 gradle **前台**跑完 → 释放用**单条顶层** `rm --`（输出逐字
> `mavis-trash: moved to trash: '…\.agent-gradle-lock'`），释放后 `Test-Path` 复核 `True`。
> 取锁期间 v152 未被阻塞。**授权**：只跑 `:app:testDebugUnitTest --tests "<类名>"`；
> **未** `assembleDebug` / `clean` / 全量 / **装机**。
> **行尾**：全程只用编辑/写入工具，`core.autocrlf=false`，未用 `git checkout`/`restore`。
> 新测试文件实测 `CRLF=0`（纯 LF，504 行）。
> **生产代码零改动**：`git status --porcelain -- app/src/main` = **0 行**。

---

## 0. 结论速览（先说不能做的那件）

> ## 🔴 B-1 不能落地：§B.3 的 diff **不等价**，差 313/440 格
>
> Lead 的放行条件是「**必须证明等价**」。**网格证伪了它**：
> 440 格穷举网格上，§B.3 的写法只在 **127 格**与生产一致，**313 格不等价**，
> 且**两档实测都在不等价的那一侧**（600 档 20/20 全错）。
>
> 按任务书原文处置：**「不要改写，如实报告"不等价，因为 X"并停下等 Lead」**。
> 本轮**未改任何生产代码**，等裁定。
>
> **两处缺陷**（下面 §1 有逐行推导）：
> 1. **算错了量**：else 分支写的是 `maxHeight − PANE_ESSENTIAL_HEIGHT + paneFloorPx`，
>    那是 **`paneBottomDp`（被减数）**，不是 `bodyHeightDp`（差）。它把房高减了两次。
> 2. **分支条件反了**：`believedImeBottomPx < ime` 成立的**恰恰是钳位生效**的时候，
>    而注释写的是「钳位未生效」。
>
> 两个缺陷指向同一处：**该改写的区域它原样保留，不该动的区域它全改了。**
>
> **但裁定本身是对的**：饱和区里房高确实是常数 `PANE_ESSENTIAL_HEIGHT − paneFloorPx`，
> 显式写出来**确实是等价变换**。**把两个分支对调、并把 `maxHeight −` 去掉**之后，
> 网格 440/440 逐格一致（§3）。修正版已备好，差 Lead 一句话。
> 另有一处**硬阻塞**：修正版会撞三条钉死旧行字面量的源码 pin（§5）。
>
> **任务 2（B-2 翻转点余量 pin）已落地**，纯测试侧、零行为改动，5 条断言全绿（§6）。

---

## 1. 等价性论证：为何饱和区是常数，以及 §B.3 差在哪

### 1.1 生产算式（`SessionScreen.kt:2361-2384`，逐行）

```
paneBoxPx    = maxHeight.roundToPx()                                     [:2361]
paneFloorPx  = if (titlebarBottom && !desktop) 0 else navBottomPx         [:2363]
believedIme  = believedImeBottomPx(paneBoxPx, paneFloorPx, ime, essential) [:2364-2369]
paneBottomDp = maxOf(paneFloorPx, believedIme).coerceAtLeast(0).toDp()   [:2370-2372]
bodyHeightDp = (maxHeight - paneBottomDp).coerceAtLeast(0.dp)             [:2384]
```

钳位（`SessionPaneLayout.kt:1574-1587`）：

```
1582  if (visibleBoxPx < essentialPx * PANE_ESSENTIAL_SPARE_ROOM) return imeBottomPx  // 早退
1583  val claimedPx = (imeBottomPx - floorPx.coerceAtLeast(0)).coerceAtLeast(0)
1584  val roomPx    = visibleBoxPx - essentialPx
1585  if (claimedPx <= roomPx) return imeBottomPx        // 恒等分支（单射）
1586  return roomPx + floorPx.coerceAtLeast(0)           // 饱和分支（常数）
```

### 1.2 饱和区为什么是常数（Lead 的意图成立）

饱和分支只在通过 `:1582` 后可达，故 `visibleBoxPx >= essentialPx * 2`，于是
`roomPx = visibleBoxPx − essentialPx >= essentialPx > 0`。所以
`maxOf(floorPx, believedIme) = believedIme = roomPx + floorPx`，代入：

```
bodyHeightDp = maxHeight − (maxHeight − essentialPx + floorPx)
             = essentialPx − floorPx                      ★ 与 ime 无关、与 maxHeight 无关
```

768 档实测：`284 − 48 = 236`。网格里 `box = 568/606/700/1000` 全部落在 236 —— 逐格相等，
这正是 v150 §B.2(a) 说的「惰性」。**把这个常数显式写出来，是纯粹的恒等变换。裁定成立。**

### 1.3 §B.3 错在哪（一句话：减了两次）

§B.3 的 else 分支写 `maxHeight − PANE_ESSENTIAL_HEIGHT + paneFloorPx`，而

```
maxHeight − PANE_ESSENTIAL_HEIGHT + paneFloorPx  ≡  maxHeight − (essentialPx − floorPx)  ≡  paneBottomDp
```

**这正是 `:2384` 那个被减数本身，不是差。** 768 档实测：

| | 值 |
|---|---|
| 钳位返回 | `roomPx + floorPx = 322 + 48 = 370` |
| 旧 body | `606 − 370 = ` **`236`** |
| §B.3 else | `606 − 284 + 48 = ` **`370`** ← 这是 paneBottom，不是 body |
| 差 | **134 px = 差值自己的大小** |

顺带一处编译问题：diff 里 `maxHeight − PANE_ESSENTIAL_HEIGHT + paneFloorPx` 是
`Dp − Dp + Int`，`Dp.plus(Int)` 不存在，**按字面写编译不过**（需 `paneFloorPx.toDp()`）。
本报告 §4 的修正版已按 dp 处理。

### 1.4 分支条件反了

`believedImeBottomPx < imeBottomPx` 成立的**恰恰是钳位饱和**（饱和时 `roomPx + floorPx < ime`，
恒等/早退时 `believed == ime`）。而 §B.3 把这个分支注释成「钳位**未生效**：用真实余量」。
净效果：

| 区域 | 钳位 | §B.3 走哪支 | 结果 |
|---|---|---|---|
| 饱和（`box 606, ime 426`） | 生效 | **旧算式**（因为条件反了） | 侥幸一致 |
| 恒等（`box 700, ime 426`） | 未生效 | **不变量支** | **错** 274 → 464 |
| 早退·600 档（`box 438, ime 387`） | 不生效 | **不变量支** | **错** 51 → 202 |
| 无键盘（`box 606, ime 0`） | 不生效 | **不变量支** | **错** 558 → 370 |

**改写要澄清的饱和区它一个字没动；它不能碰的未饱和区全变了。**

---

## 2. 网格测试：规模与逐格结果

`SelfProofEquivalenceV153Test.theWholeGridIsWrittenOutForReading`
把整张网格落盘到 `research/v97_fix/_v153_grid.txt`（原样输出，440 行 + 头 4 行）。

**规模：440 格** = `box`(11) × `paneFloorPx`(2) × `ime`(20)

| 轴 | 取值 | 覆盖理由 |
|---|---|---|
| `paneFloorPx` | `{0, 48}` | 48dp 靶；`titlebarBottom` 形态的 0 |
| `box` | `{284, 285, 438, 480, 567, 568, 569, 606, 700, 800, 1000}` | **438/606 = 两档实测**；`567/568/569` 跨 `:1582` 早退边界 `essentialPx*2`；`284/285` 跨 essential 本体 |
| `ime` | `{0, 48, 200, 274, 284, 300, 331, 332, 333, 370, 371, 387, 426, 438, 464, 474, 522, 606, 768, 836}` | 无键盘；**387/426 = 两档实测**；`474` 退役；`836` v77 陈旧帧；**370/371 = 768 恒等/饱和边界**；`331/332/333` = 600 档边界；`438` = 600 档翻转点 |

**汇总行（原文）**：

```
V153 SUMMARY cells=440 b3Equivalent=127 fixedEquivalent=440
```

- **§B.3：440 格里只 127 格一致 ⇒ 313 格不等价**（含两档实测）。
- **修正版：440 / 440 逐格一致**（body 与 oneRow 双取值同时比对）。

**600 档逐格（`box=438, floor=48`，20 行原样，`旧body,oneRow | §B.3,一致? | 修正版,一致?`）**：

```
V153 438,48,0   -> 0,false,390,false   | 202,false | 390,true
V153 438,48,274 -> 274,false,164,false | 202,false | 164,true
V153 438,48,300 -> 300,false,138,true  | 202,false | 138,true
V153 438,48,331 -> 331,false,107,true  | 202,false | 107,true
V153 438,48,387 -> 387,false,51,true   | 202,false | 51,true     <-- 实测 387
V153 438,48,426 -> 426,false,12,true   | 202,false | 12,true
V153 438,48,438 -> 438,false,0,false   | 202,false | 0,true      <-- 翻转点
V153 438,48,474 -> 474,false,0,false   | 202,false | 0,true
V153 438,48,836 -> 836,false,0,false   | 202,false | 0,true
```

600 档 **20/20 全错**：`438 − 284 + 48 = 202` 恒定，而真实余量在 390…0 之间变动。
**形态也被改**：实测 387 本该 `body=51 → oneRow=true`，§B.3 给 `202 → oneRow=false`
（composer 形态判据直接翻转）。这不是重构，是行为变更。

**768 档逐格（`box=606, floor=48`）**：

```
V153 606,48,0   -> 0,false,558,false   | 370,false | 558,true
V153 606,48,300 -> 300,false,306,false | 370,false | 306,true
V153 606,48,370 -> 370,false,236,false | 370,false | 236,true     <-- 恒等分支最后一个读数
V153 606,48,371 -> 370,true,236,false  | 236,true  | 236,true     <-- 饱和从此开始
V153 606,48,426 -> 370,true,236,false  | 236,true  | 236,true     <-- 实测 426
V153 606,48,474 -> 370,true,236,false  | 236,true  | 236,true
V153 606,48,836 -> 370,true,236,false  | 236,true  | 236,true     <-- v77 陈旧帧
```

768 档 `ime >= 371` 饱和段的 §B.3 一致 —— **正因为它的条件反了**，它在那里退回旧算式。
`ime <= 370` 的 10 行全错。饱和区那段 `236` 在两栏里逐格相等，
**即 §B.1 的等价性论证在网格上成立**（见
`theSaturationInvariantIsTheSameConstantOnEverySaturatedCell`）。

**不一致的 127 格是什么？** 只有两种「凑巧」：
① 代数恒等 `max(floor, ime) == essentialPx − floorPx`（如 `box=1000,floor=0,ime=284`）；
② 两边都被 `coerceAtLeast(0.dp)` 压到 0（如 `box=284,ime=300`）。
**两档实测都不在这两类里** —— 已作为断言钉住
（`theWholeGridIsWrittenOutForReading` 断言 600 档必不一致、768 档必一致）。

---

## 3. diff

### 3.1 本轮**没有**生产 diff（未落地）

`git status --porcelain -- app/src/main` = **0 行**。唯一改动文件：

```
?? app/src/test/java/com/xiufeigo/opencodeshell/SelfProofEquivalenceV153Test.kt
?? research/v97_fix/SELFPROOF_REWRITE.md
?? research/v97_fix/_v153_*.log, _v153_grid.txt
```

> ⚠️ `app/build.gradle.kts` 显示 ` M`，**不是本轮改的** —— 是 v152（release 体积实测）
> 那路的改动。本轮未碰该文件。

### 3.2 待裁定版本（**已证明等价，440/440**，但**未落地**）

把 §B.3 的两处缺陷修掉：对调分支 + 去掉 `maxHeight −`：

```diff
--- a/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt
+++ b/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt
@@ -2364,7 +2364,10 @@
+        val imeBottomPx = WindowInsets.ime.getBottom(density)
         val believedImeBottomPx = SessionPaneLayout.believedImeBottomPx(
             visibleBoxPx = paneBoxPx,
             floorPx = paneFloorPx,
-            imeBottomPx = WindowInsets.ime.getBottom(density),
+            imeBottomPx = imeBottomPx,
             essentialPx = with(density) { SessionPaneLayout.PANE_ESSENTIAL_HEIGHT.roundToPx() },
         )
@@ -2384,7 +2387,14 @@
-        val bodyHeightDp = (maxHeight - paneBottomDp).coerceAtLeast(0.dp)
+        // v153: 形态判据不再用钳位自己的输出反推房高。钳位饱和时
+        // （SessionPaneLayout.kt:1586 返回 roomPx + floorPx）房高恒等于
+        // essentialPx - floorPx，与 ime、与 maxHeight 都无关，是常数——
+        // 直接写出这个不变量。钳位未生效时用真实余量。
+        val bodyHeightDp = if (believedImeBottomPx < imeBottomPx) {
+            (SessionPaneLayout.PANE_ESSENTIAL_HEIGHT - paneFloorDp).coerceAtLeast(0.dp)
+        } else {
+            (maxHeight - paneBottomDp).coerceAtLeast(0.dp)
+        }
         val bodyOneRow = SessionPaneLayout.composerOneRow(
```

---

## 4. 反向验证（**真跑抄原文**）

### 4.1 网格等价 —— 把修正版改错一侧 ⇒ 必须红

变异：`fixedInvariant` 换回 §B.3 的 `box − essentialDp + floor.dp`（其余一字不动）。
日志 `_v153_rev_grid.log`：

```
SelfProofEquivalenceV153Test > theCorrectedInvariantWithTheBranchesSwappedIsEquivalentOnEveryCell FAILED
5 tests completed, 1 failed

BUILD FAILED in 4s
52 actionable tasks: 2 executed, 50 up-to-date
EXIT=1

FAILED: theCorrectedInvariantWithTheBranchesSwappedIsEquivalentOnEveryCell
   java.lang.AssertionError: cell (box=568, floor=48, ime=333) body must be unchanged expected:<236> but was:<332>
```

`box=568` 正是 `:1582` 边界那一格（`568 == essentialPx * SPARE_ROOM`），`236` 是不变量、
`332` 是 §B.3 的 paneBottom —— **红在最能说明问题的那一格**。

### 4.2 翻转点余量 —— 把余量算错 ⇒ 必须红

变异：`margin` 改成 `imeBottom768 - imeBottom600`（39，模拟「误用 768 读数」的移植事故）。
日志 `_v153_rev_margin.log`：

```
SelfProofEquivalenceV153Test > theSixHundredTierKeepsFiftyOnePxOfRoomBeforeTheReverseJump FAILED
5 tests completed, 1 failed

BUILD FAILED in 3s
52 actionable tasks: 2 executed, 50 up-to-date
EXIT=1

FAILED: theSixHundredTierKeepsFiftyOnePxOfRoomBeforeTheReverseJump
   java.lang.AssertionError: the measured 600-tier margin expected:<51> but was:<39>
```

---

## 5. 硬阻塞：修正版落地会撞三条源码 pin（本轮无权处理）

旧行字面量 `val bodyHeightDp = (maxHeight - paneBottomDp).coerceAtLeast(0.dp)` 被三处
`assertTrue(contains(...))` 钉死：

| 类 | 行 |
|---|---|
| `SessionPaneImeBeliefV77Test` | `:350`（**v77 P0 链**） |
| `SessionPaneShortWindowV51Test` | `:204` |
| `ComposerShortWindowV74Test` | `:208` |

改写后该字面量只在一支出现、不再整行匹配 ⇒ 三条会红。
**把它们改掉 = 放宽/改写既有断言，本轮明令禁止** ⇒ 修正版即便等价也**不能本轮落地**。
已作为判据钉住：`theLandingIsBlockedByThreeSourcePinsThatPinTheOldLine`。

---

## 6. B-2：翻转点余量 pin（**已落地**，纯测试侧，零行为改动）

### 6.1 算式（全部由生产函数 + 实测读数算出，非两侧字面量）

```
600 档钳位失效：438 < essentialPx * SPARE_ROOM = 568          [SessionPaneLayout.kt:1582]
⇒ body = 钳位未生效 = max(0, paneBox600 - max(floor, ime))     [SessionScreen.kt:2384]

翻转点  ime == paneBox600 = 438   （此时 body == 0）
实测    imeBottom600     = 387    （V108 实测，`window - imeTop(213)`）
────────────────────────────────────────────────────────────
余量    = 翻转点 − 实测  = 438 − 387 = 51 px
```

翻转机制：`composerMinimal` 头一句是 `available > 0.dp`（`SessionPaneLayout.kt:1009`），
所以 `body == 0` 时 oneRow **自己关掉**，反过来向 116dp 完整 composer 要空间 —— 键盘越高形态越大。

### 6.2 断言形式（5 条，`theSixHundredTierKeepsFiftyOnePxOfRoomBeforeTheReverseJump`）

```
margin = paneBox600 - imeBottom600
assertEquals("the measured 600-tier margin", 51, margin)
assertTrue("the margin must not shrink: … px of room before the reverse jump", margin >= 51)
```

**「余量不得变小」= `margin >= 51`**，风险面做成**可侦测**而非口头描述：

| 断言 | 调用的生产量 |
|---|---|
| `438 < essentialPx * PANE_ESSENTIAL_SPARE_ROOM` | `PANE_ESSENTIAL_HEIGHT` / `PANE_ESSENTIAL_SPARE_ROOM` |
| `bodyAt(387) == 51` 且 oneRow 为真 | `believedImeBottomPx` + `composerOneRow` |
| `bodyAt(426) == 12` 且 oneRow 为真 | 同上（**426 → 474 触发反向跳变**的起点） |
| `bodyAt(474) == 0` 且 oneRow 为**假** | 同上（终点：形态反向） |
| `imeBottom600 + margin == 438` 且 `bodyAt(inflated) == 0` 且 oneRow 为假 | 同上（**51px 多报 = 精确落在翻转点**） |
| `bodyAt(387 + 48) > 0` | 退役 v110「少扣一根导航栏」的 48px 错读，**离悬崖只剩 3 px** |

没有「两侧都是字面量」的对比：`51` 是**算出来的**，`12`/`0`/`202`/`236` 全部来自生产函数。

---

## 7. 为什么不降强度

- **未放宽、未删除任何既有断言。** 全部改动是**新增一个测试类**（6 例）。
- **既有 18 个类 150 例原样全绿**，用例数**只增不减**（v150 基线 11 → 现 18 类 / 150 例）。
- §B.3 若按字面落地，会在 600 档把 oneRow 从 `true` 翻成 `false`（51 → 202）——
  那是**行为变更**。本轮把它挡在生产之外，正是「不降强度」的落点。
- 反向验证两次都真红（§4），说明新 pin 有牙，不是恒真断言。
- 自证的三处红（第一轮 harness 单位错、第二轮 `Density(160f)`、第三轮漏 `coerceAtLeast`）
  全部是**我自己写错的 harness**，已如实记录在 `_v153_run1/2/3.log`，与生产无关。

---

## 8. 测试证据

命令（全程前台，均在 `.agent-gradle-lock` 内，`JAVA_HOME` = Android Studio JBR）：

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.SelfProofEquivalenceV153Test" --console=plain
```

**终态绿（原文，`_v153_final.log`）**：

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 3s
52 actionable tasks: 2 executed, 50 up-to-date
EXIT=0
FINAL: tests=6 failures=0 errors=0 skipped=0
[OK] theWholeGridIsWrittenOutForReading
[OK] theSectionB3BranchOrderIsNotEquivalentToProductionOnTheGrid
[OK] theCorrectedInvariantWithTheBranchesSwappedIsEquivalentOnEveryCell
[OK] theSixHundredTierKeepsFiftyOnePxOfRoomBeforeTheReverseJump
[OK] theSaturationInvariantIsTheSameConstantOnEverySaturatedCell
[OK] theLandingIsBlockedByThreeSourcePinsThatPinTheOldLine
```

**回归（18 类 / 150 例 / 0 失败，`_v153_regression.log`）**：

| 类 | 用例 | 类 | 用例 |
|---|---|---|---|
| `SelfProofEquivalenceV153Test`（新） | 6 | `ComposerDivergenceLocateV119Test` | 8 |
| `ComposerPaneBottomCriterionV108Test` | 11 | `ComposerDivergenceLocate2V120Test` | 6 |
| `SessionPaneImeBeliefV77Test`（**v77 P0**） | 11 | `ComposerCriterionParityV118Test` | 12 |
| `SessionPaneShortWindowV51Test` | 10 | `SettingsModalImeBandV92Test` | 9 |
| `SessionPaneShortWindowV78Test` | 10 | `ServersFormImeFloorV88Test` | 9 |
| `SessionPaneChromeFloorV79Test` | 6 | `PrecisionT1And768bV148Test` | 6 |
| `SessionPaneImeBandV84Test` | 11 | `ComposerUnifiedCriterionV129Test` | 6 |
| `ComposerShortWindowV74Test` | 8 | `ComposerBandTouchTargetV105Test` | 6 |
| `ComposerPositionDecompositionV107Test` | 4 | `ComposerUnionBottomP5V109Test` | 11 |

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 3s
52 actionable tasks: 2 executed, 50 up-to-date
EXIT=0
REGRESSION: classes=18 tests=150 failures=0 errors=0
```

**基线与红线未回退**（全部在上述绿跑内）：
768 的 **140/92**、600 的 **51**、两档 ①(i) **−10/−12**、**48dp** 靶、
v77 P0（`the stale 836 dp frame is capped at the pane's essential form`）、
`quarantine/` 分工（`ModelVsDeviceMismatchTest` 仍在 `research/v97_fix/quarantine/`，未动）。
本类还把 **600 档 51 / 768 档 236** 作为断言重新钉了一遍。

---

## 9. 真机复验步骤（交给 Lead，**本轮未装机**）

1. **无需为 B-1 跑真机** —— 改写没落地，生产一行未动。
2. 若 Lead 点头落地 §3.2 的修正版，则按 v150 §B.3 的三步复验：
   ① 1366x768 / 1366x600 两档，**工具栏与 tab 条必须在位**（v77 P0 回归）；
   ② 键盘开/关过程中形态**不得反向跳**（600 档离翻转点仅 51px）；
   ③ 缩窗/分屏/旋转/IME 开关连打，确认**无新增一帧滞后**（v150 §B.2(d)）。
   附带：落地时必须**一并重瞄**那三条源码 pin（§5），不能删。
3. **B-2 的 51px 余量**：建议真机在 600 档换更高的输入法 / 浮出联想条，
   看能否把 387 推到 438。推到 438 即复现反向跳变，可直接立项修 `available > 0.dp`。

---

## 10. 未闭环 / 需裁定

| # | 事项 | 状态 |
|---|---|---|
| 1 | **§B.3 等价改写** | **未落地**：网格证伪（313/440 不等价）。修正版 440/440 已备好，**等 Lead 点头** |
| 2 | 修正版的**三条源码 pin 重瞄** | **未做**（本轮禁止改既有断言） |
| 3 | **B-2 `438` 反向跳变**（`available > 0.dp`） | **仍单独立项**，本轮只加余量 pin，未改行为 |
| 4 | v150 §B.2(d) 测量量晚一帧 | 只登记，未动 |
| 5 | v150 §B.2(c) 震荡 | 无证据，不主张 |
| 6 | 真机复验 3 项 | **未做**（本轮禁装机） |
| 7 | §B.3 字面 diff 的 `Dp + Int` 编译错 | 已在本报告指出，未落地故未触发 |
| 8 | `_v153_grid.txt` 由测试写入 `research/` | 沿用本仓一次性探针的既有做法（v150 同款） |
