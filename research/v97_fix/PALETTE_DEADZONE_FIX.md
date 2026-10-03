# PALETTE_DEADZONE_FIX — 命令面板打开后底栏点击死区（v98）

- **执行人**：Mavis（修复工程师）
- **日期**：2026-10-02 23:10 – 23:35
- **仓库**：`C:\sensorsdata\main\program\opencode-android-shell`
- **缺陷来源**：`research/v97_verify/5564_v98/REPORT_PERF.md` §0 结论表 + §6.3 死区段
- **状态**：**已修 + 0 失败回归**（真机复验待测试员执行，见 §5）

---

## 1. 结论先行

**命令面板没有自己的遮罩。** 它是 `DialogHost` 的一层，遮罩由宿主画（`DialogHost.kt:376-410`）。
死区的原因是：**`CommandPalette` 从不向宿主上报自己的卡片边界**，于是宿主的「点外部关闭」判定
退化成"和宿主量到的**内容盒**比"，而那个内容盒是调色板的**全宽布局盒**，不是可见卡片。
底栏正好落在那个盒子里 → 判定为"点在卡片内" → **既不关闭，也不下传** → 死区。

修法与仓库里其余 **13 个手搓弹层**完全一致：给卡片加上 `Modifier.dialogCardBounds(scrim)`。

---

## 2. 根因（文件:行号 + 原文 + 因果链）

### 2.1 宿主是谁

| 角色 | 位置 | 说明 |
|---|---|---|
| 宿主 | `ui/overlay/DialogHost.kt:197` `DialogHost` | 全局弹层栈，包住导航树 |
| 遮罩 | `ui/overlay/DialogHost.kt:383-409` | 宿主自己画的 `fillMaxSize` scrim + `detectTapGestures` |
| 判定 | `ui/overlay/DialogLayerPolicy.kt:218-224` `scrimTapDismisses` | 「点在内容盒外才关闭」 |
| 卡片 | `ui/palette/CommandPalette.kt:236` | 可见的、带阴影/边框的 `Column` |
| 布局盒 | `ui/palette/CommandPalette.kt:233` | `modifier.fillMaxWidth().height(shellBoxHeight)` — **死区就住在这里** |

`paletteShell`（`PaletteModel.kt:172-177`）只算尺寸，不是宿主。

### 2.2 遮罩**有**接到"点外部关闭"——但接错了对象

`DialogHost.kt:398-404` 原文：

```kotlin
Modifier.pointerInput(entry.id) {
    detectTapGestures(onTap = { local ->
        if (cardState.dismisses(local)) {
            controller.close()
        }
    })
}
```

`DialogScrim.kt:113-121`：

```kotlin
internal val guardedBounds: Rect? get() = cardBounds ?: contentBounds
fun dismisses(tap: Offset): Boolean =
    scrimTapDismisses(guardedBounds, tap + scrimOrigin, backdropBounds)
```

**`cardBounds ?: contentBounds`** 就是全部问题。`cardBounds` 只有卡片自己调
`Modifier.dialogCardBounds(...)`（`DialogScrim.kt:198-199`）才会有值。

`grep dialogCardBounds` 全仓共 21 处调用，**13 个手搓弹层 + `/btw` 面板全部接了**
（`DialogScrimResidueTest.kt:88-111` 把这条钉成了硬断言）——**唯独 `CommandPalette.kt` 没接**，
于是走 `contentBounds` 兜底。

### 2.3 兜底的那个盒子为什么能盖住底栏

`contentBounds` 由 `DialogHost.kt:470` 上报，量的是 `DialogLayer` 的内容盒，
而它的子节点就是 `CommandPalette.kt:233` 的全宽布局盒：

```kotlin
Box(
    modifier.fillMaxWidth().height(shellBoxHeight),
    contentAlignment = Alignment.TopCenter,
) {
```

它在窗口里 **居中**（`DialogHost.kt:442` `contentAlignment = Alignment.Center`）。由
`paletteBoxHeight = windowHeight - 2 * topMargin`（`PaletteModel.kt:194-195`）反推，
居中后**上沿正好落在 `topMargin`**——这正是 §6.3 的 `margin-top`，也是当初这么写的原因。
于是**下沿 = `windowHeight - topMargin`**。

而 `topMargin`（`PaletteModel.kt:175`）是：

```kotlin
val topMargin = maxOf(48f, (visibleHeight - 480f) / 2f)
```

`visibleHeight = windowHeight - imeBottom`（`CommandPalette.kt:110-111`）。

**`CommandPalette` 在打开时自动聚焦搜索框**（`CommandPalette.kt:128`
`LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }`），
**所以面板一开键盘就起来** → `visibleHeight` 变小 → `topMargin` 塌到 48dp 地板
→ 布局盒下沿被拉到离窗口底只剩 48dp → **吃掉整个底栏**。

实测（两种形态，取 `imeHeight = 300dp`、底栏高 80dp）：

| 形态 | 窗口高 dp | visibleHeight | topMargin | 布局盒下沿（=受保护边界） | 底栏顶沿 | 命中死区 |
|---|---|---|---|---|---|---|
| 移动 `1200x1600@320` | 800 | 500 | 48 | **752** | 720 | ✅ 落入盒内 |
| 默认 `2076x2152@390` | 882.9 | 582.9 | 51.4 | **831.5** | 802.9 | ✅ 落入盒内 |

键盘收着的时候（`topMargin` = 160）布局盒下沿 640 < 720，**不**死区——所以这跟形态无关、
跟「面板是不是开着（有没有自动聚焦）」有关，这也解释了报告里为什么两形态 100% 复现。

### 2.4 逐条排除

| 假设 | 判定 | 依据 |
|---|---|---|
| 卡死/性能问题 | ❌ 排除 | 报告 §0：28s 内 14 次轮询节点数恒定（31/40），同期 196 帧 p99=101ms，logcat 零 ANR/Choreographer |
| 宿主资源竞争 | ❌ 排除 | 报告 §6.2：CPU 26%，内存 33.6G 空闲 |
| 遮罩没接"点外部关闭" | ❌ 排除 | `DialogHost.kt:398-404` 确实接了 |
| z 序问题 | ❌ 排除 | 弹层是导航树的兄弟且在其后（`DialogHost.kt:253-283`），压住底栏是**模态的预期**，不是 bug |
| `pointerInput` 吞事件 | ⚠️ 部分成立，但是**设计内** | 遮罩是 `fillMaxSize` 的 `pointerInput`，命中它就停住不下传（`DialogLayerPolicy.kt:110-117` 已写明）。问题不在"吞"，在"该判为背景却判成了卡片" |
| `clickable(enabled=false)` | ❌ 排除 | 遮罩不是 `clickable`，是 `pointerInput`+`detectTapGestures`；`CommandPalette` 的 `clickable` 只在行上 |
| 覆盖层尺寸与可视区不一致 | ✅ **命中** | 可见卡片只有 `min(100vw-24, 640)` 宽、≤480dp 高；受保护的却是全宽、近满屏的布局盒 |
| `clearAndSetSemantics` 剥掉命中 | ❌ 排除 | `clearAndSetSemantics` 只在 `entry.closing` 时挂（`DialogHost.kt:482-486`），打开态不挂；它也只影响无障碍树，不影响命中测试 |
| dump 里没有可点的 scrim 节点 | ⚠️ **是设计，不是线索** | 遮罩**故意**不进无障碍树（`DialogScrim.kt:47-51`：`clickable` 会给背景加 `onClick` 语义，`detectTapGestures` 不带）。所以 `uiautomator dump` 看不到 scrim 是**预期的**，不能用来推断"没有遮罩" |

### 2.5 判定

> 底栏在面板打开时**本就不该可点**（模态语义）。按任务要求，这种情况必须做到
> **「点外部 = 关闭面板」**，而不能什么都不做。
> 修复后：点卡片以外任意位置 → 关闭面板；**不再存在"点了没反应"的区域**。

---

## 3. 改动 diff

### 3.1 `ui/palette/CommandPalette.kt`（2 处，+22 行）

```diff
@@ imports @@
 import com.xiufeigo.opencodeshell.ui.overlay.DialogSize
 import com.xiufeigo.opencodeshell.ui.overlay.LocalDialogController
+import com.xiufeigo.opencodeshell.ui.overlay.LocalDialogScrimState
+import com.xiufeigo.opencodeshell.ui.overlay.dialogCardBounds

@@ CommandPalette() @@ line 150
     val hostDialog = LocalDialogController.current
+    // v98: the box the layer's scrim guards is the box the host *measured* as
+    // this layer's content ([DialogScrimState.contentBounds]) unless the layer
+    // reports its own card. ...（完整说明见源码 135-149 行）
+    val scrim = LocalDialogScrimState.current

@@ the card Column @@ line 236-248
         Column(
             Modifier
                 .width(width)
                 .heightIn(min = PALETTE_MIN_HEIGHT.dp, max = maxHeight)
+                // After the size modifiers, before the card's own decoration —
+                // the placement rule in [DialogScrim].
+                .then(
+                    if (scrim != null) Modifier.dialogCardBounds(scrim) else Modifier,
+                )
                 .shadow(16.dp, RoundedCornerShape(12.dp))
                 .background(c.bgBase, RoundedCornerShape(12.dp))
```

标记落在**卡片**（`width(width)` + `heightIn` 之后、`shadow` 之前），**不是** `:233` 的全宽布局盒——
落在布局盒上等于把死区原样钉住。

### 3.2 `ui/palette/PaletteModel.kt`（+58 行，纯函数，无 Compose）

- `fun paletteFallbackGuardedBottom(windowHeight, topMargin): Float = windowHeight - topMargin`
  —— 把 §2.3 那个受保护盒子的下沿写成纯函数，供 JVM 钉死。
- `enum class PaletteLayerState { CLOSED, OPEN }`
- `fun onScrimTap(open: Boolean, tapInsideCard: Boolean): PaletteLayerState`
  —— 关闭出口的**目标状态转换**：打开 + 点背景 ⇒ `CLOSED`。**不存在第三种"吞掉"的状态。**

### 3.3 `app/src/test/java/com/xiufeigo/opencodeshell/PaletteScrimDeadZoneV98Test.kt`（新，10 例）

纯 JVM，无 Compose / 无 Robolectric。

### 3.4 未改动

`ui/overlay/DialogHost.kt`、`DialogScrim.kt`、`DialogLayerPolicy.kt` **一行未动**；
`ui/App.kt` 未动；未新增任何用户可见字符串。

---

## 4. 为什么不降强度

| 既有断言 | 处置 |
|---|---|
| `DialogScrimResidueTest.everyHandRolledModalRoutesThroughTheSharedScrim`（13 个弹层必须 `.dialogCardBounds(scrim)`） | **原样通过**——本次修复正是把 palette 归入同一套规则，方向与它一致，不是绕过它 |
| `DialogScrimResidueTest` 全树 residue 扫描（零个全屏 `clickable` 背景） | **原样通过**——palette 未新增任何 `clickable` 背景 |
| `DialogScrimTapOwnershipTest` / `anUnmeasuredCardNeverDismisses` | **原样通过**——「边界未知不关闭」的既有语义未动 |
| `DialogLayerPolicyTest` / `DialogExitHitTransparencyTest` / `DialogExitResidueTest`（v50 退出穿透） | **原样通过**——palette 未新增任何退出期 pointer 门控 |
| `EscapeOnPhoneV61Test` / `ModalEscapeTest`（ESC 语义） | **原样通过**——ESC 路径未改 |
| `Wave23aSettingsPaletteTerminalTest` / `StringsLocaleParityTest` | **原样通过**——未动字符串、未动设置页 |

**唯一的语义变化**：调色板**卡片以外**的暗区现在会关闭面板（这正是任务要求，也是 upstream
`pointer-events:none` + 交互子节点的原意）。卡片**以内**的行为一律不变。

首轮跑出 2 个失败，是**我自己新测试的断言写错了**（不是实现问题）：

1. 断言 `topMargin == 48f`——默认形态实际是 51.4（`(582.9-480)/2`），已改为断言
   `topMargin < 底栏高` 这一真正的不变量；
2. 断言 palette 不含 `.clickable`——但它的**行**本来就该可点，已删除该断言（全屏背景由
   `DialogScrimResidueTest` 的 residue 扫描负责）。

两处都只放宽了我自己新写的断言，**没有触碰任何既有断言**。

---

## 5. 测试证据

### 5.1 命令

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --console=plain `
  --tests "com.xiufeigo.opencodeshell.PaletteScrimDeadZoneV98Test" `
  --tests "com.xiufeigo.opencodeshell.PaletteDataTest" `
  --tests "com.xiufeigo.opencodeshell.PaletteSearchTest" `
  --tests "com.xiufeigo.opencodeshell.PaletteOpenQueryTest" `
  --tests "com.xiufeigo.opencodeshell.PaletteScrollTest" `
  --tests "com.xiufeigo.opencodeshell.PaletteHintClipTest" `
  --tests "com.xiufeigo.opencodeshell.PaletteGroupSpacingTest" `
  --tests "com.xiufeigo.opencodeshell.PaletteEntryPointsTest" `
  --tests "com.xiufeigo.opencodeshell.PaletteFuzzySearchV63Test" `
  --tests "com.xiufeigo.opencodeshell.PaletteRowScrollMarginTest" `
  --tests "com.xiufeigo.opencodeshell.PaletteRowDescriptionTest" `
  --tests "com.xiufeigo.opencodeshell.DialogScrimResidueTest" `
  --tests "com.xiufeigo.opencodeshell.DialogScrimTapOwnershipTest" `
  --tests "com.xiufeigo.opencodeshell.DialogLayerPolicyTest" `
  --tests "com.xiufeigo.opencodeshell.DialogExitResidueTest" `
  --tests "com.xiufeigo.opencodeshell.DialogExitHitTransparencyTest" `
  --tests "com.xiufeigo.opencodeshell.DialogViewportFitTest" `
  --tests "com.xiufeigo.opencodeshell.ModalEscapeTest" `
  --tests "com.xiufeigo.opencodeshell.EscapeOnPhoneV61Test" `
  --tests "com.xiufeigo.opencodeshell.OcPopoverDismissTest" `
  --tests "com.xiufeigo.opencodeshell.Wave23aSettingsPaletteTerminalTest" `
  --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest" `
  --tests "com.xiufeigo.opencodeshell.BtwScrimCardBoundsV71Test" `
  --tests "com.xiufeigo.opencodeshell.SettingsDialogGuardTest" `
  --tests "com.xiufeigo.opencodeshell.ChordPaletteReachabilityV82Test"
```

### 5.2 BUILD SUCCESSFUL 原文

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 4s
52 actionable tasks: 2 executed, 50 up-to-date
GRADLE_EXIT=0
```

### 5.3 用例数（来自 `app/build/test-results/testDebugUnitTest/*.xml`）

```
CLASSES=25   TESTS=201   FAILURES=0   SKIPPED=0
```

| 测试类 | 用例 | 失败 |
|---|---:|---:|
| **PaletteScrimDeadZoneV98Test（新）** | **10** | 0 |
| PaletteDataTest | 10 | 0 |
| PaletteSearchTest | 8 | 0 |
| PaletteEntryPointsTest | 11 | 0 |
| PaletteScrollTest | 11 | 0 |
| PaletteHintClipTest | 10 | 0 |
| PaletteGroupSpacingTest | 7 | 0 |
| PaletteFuzzySearchV63Test | 7 | 0 |
| PaletteOpenQueryTest | 6 | 0 |
| PaletteRowDescriptionTest | 6 | 0 |
| PaletteRowScrollMarginTest | 5 | 0 |
| DialogScrimResidueTest | 6 | 0 |
| DialogScrimTapOwnershipTest | 6 | 0 |
| DialogLayerPolicyTest | 4 | 0 |
| DialogExitResidueTest | 7 | 0 |
| DialogExitHitTransparencyTest | 8 | 0 |
| DialogViewportFitTest | 4 | 0 |
| ModalEscapeTest | 6 | 0 |
| EscapeOnPhoneV61Test | 7 | 0 |
| OcPopoverDismissTest | 20 | 0 |
| Wave23aSettingsPaletteTerminalTest | 25 | 0 |
| StringsLocaleParityTest | 3 | 0 |
| BtwScrimCardBoundsV71Test | 5 | 0 |
| SettingsDialogGuardTest | 4 | 0 |
| ChordPaletteReachabilityV82Test | 5 | 0 |
| **合计** | **201** | **0** |

新类的 10 个用例：

```
anOpenPaletteAlwaysHasAWorkingCloseExitOnItsBackdrop
aTapOnTheCardLeavesThePaletteOpen
aClosedLayerStaysClosed
theUnreportedLayoutBoxCoveredTheBottomTabStripOnBothFormFactors
theDeadZoneIsTheImeNotTheFormFactor
theReportedCardClearsTheTabStripOnBothFormFactors
thePaletteCardReportsItsBoundsSoTheScrimHasARealGuard
theBoundsMarkerRidesTheCardNotTheLayoutBox
thePaletteAddsNoBackdropOfItsOwn
theExitSemanticsAreUntouched
```

---

## 6. 真机复验步骤（给测试员）

> 前置：装上本次构建。设备沿用 `emulator-5564`（Pixel_10_Pro_Fold）。
> **第 ② 步的 `wm` 改动必须 reset。**

### ① 默认形态 — 死区主用例

```bash
adb -s emulator-5564 shell input keycombination 113 39     # Ctrl+K 打开命令面板
adb -s emulator-5564 shell input tap <底栏"首页"中心坐标>     # 点底栏
```

**断言：面板关闭**（`uiautomator dump` 里调色板的搜索框节点消失，底栏回到可点）。
> 修复前：既不关闭也不跳转，节点数 28s 恒定不变。
> 底栏坐标请用 `uiautomator dump` 读 `底栏节点` 的 `bounds` 取中心；
> **注意键盘可能盖住底栏——先 `input keyevent 111` 收一次键盘再点**，
> 或直接点键盘上沿之上、卡片下沿之外的那条暗区带（同样必须能关闭面板）。

### ② 移动形态

```bash
adb -s emulator-5564 shell wm size 1200x1600
adb -s emulator-5564 shell wm density 320
# …重复 ① …
adb -s emulator-5564 shell wm size reset      # ⚠️ 必须
adb -s emulator-5564 shell wm density reset   # ⚠️ 必须
adb -s emulator-5564 shell wm size; adb -s emulator-5564 shell wm density
# 期望：只有 Physical 行，无 Override 行
```

**断言：同 ①，面板关闭。**

### ③ 面板内行为不变

- 点某条命令 → 正常执行
- 点卡片内的空白（组标题之间的间隙、搜索框外侧留白）→ **面板不关闭**（这一条是"卡片以内不关闭"的回归护栏）
- 上下键移动高亮、Enter 执行 → 正常
- `input keyevent 111`（ESC）→ 手机形态连按两次能关（`EscapeOnPhoneV61Test` 语义）

### ④ 退出动画 / 穿透窗（v50，不许反转）

```bash
adb -s emulator-5564 shell input keycombination 113 39   # 打开
adb -s emulator-5564 shell input keyevent 111            # ESC 关闭
adb -s emulator-5564 shell input tap <底栏>               # 关闭后第一下就点底栏
```

**断言：关闭动画照常播放；关闭后的第一下点击直接到达底栏**（不被退场中的卡片吞掉）。
> `DialogLayerPolicyTest` / `DialogExitHitTransparencyTest` 已钉住结构，
> 但"动画观感"和"退出期首击落点"仍需真机看一眼。

### ⑤ 建议的补充观察

- `logcat -s DialogHost` 过滤 `layer close`，确认关闭走的确实是 `controller.close()`
- 打开面板后**不要收键盘**再点底栏——那条带子正是死区所在，应该同样能关闭

---

## 7. 未闭环

1. **真机复验未执行**（本任务禁止装机/assembleDebug）。§6 全部 5 步待测试员跑一遍。
2. **底栏带高 80dp 是本 pin 的取值**，不是从 `BottomTabStrip.kt` 实测出来的精确高度；
   死区结论对它不敏感（`topMargin` 48–51.4dp，只要带高 > 52dp 就命中），但精确值可后续补测。
3. **键盘遮住底栏的交互组合**（IME 盖住底栏时点底栏）本 pin 未建模——那次点击落在输入法上，
   不是 app 的死区。真机复验 ① 已提示测试员处理。
4. **`DialogScrimState.cardBounds` 首次布局前为 null**，此时 `scrimTapDismisses` 返回 false
   （"边界未知不关闭"，`DialogScrimResidueTest.anUnmeasuredCardNeverDismisses` 钉住）。
   这是**既有语义**、窗口仅首帧，本次未改。若日后要连这一帧也消灭，需要单独评估
   "未测量即关闭"对 13 个弹层的影响，不在本次范围。
5. 报告 §5/§8 中 perf 报告自身的未闭环项（样本量小、8 台模拟器并发）不属本次范围，未处理。

---

## 8. 交付物清单

| 文件 | 状态 |
|---|---|
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/palette/CommandPalette.kt` | 改（+22） |
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/palette/PaletteModel.kt` | 改（+58，纯函数） |
| `app/src/test/java/com/xiufeigo/opencodeshell/PaletteScrimDeadZoneV98Test.kt` | 新增（10 例） |
| `research/v97_fix/PALETTE_DEADZONE_FIX.md` | 本文件 |
