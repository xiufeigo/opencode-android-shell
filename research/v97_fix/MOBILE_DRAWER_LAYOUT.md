# v97 N-3 — 移动形态：抽屉打开时底部标签条跳到 y=0 压住状态栏

**结论：成因复核通过（测试员的定位正确，行号略有漂移），已修。**
抽屉原本是 shell `Column` 的**流内兄弟节点**；它的 `Box(fillMaxSize())` 宿主吃掉整列剩余高度，
`Column` 又**先测量非 weight 子节点**，于是 `weight(1f)` 的 NavHost 被压成 0 px，排在它前面的
`BottomTabStrip` 被顶到 y=0。现在抽屉改为**覆盖层（overlay）**，不再参与该 Column 的高度分配。

- 缺陷来源：`research/v97_verify/5560_sweepA/REPORT_v97b.md` §N-3
- 修复日期：2026-10-02
- 形态：仅移动形态（`bottomTitlebar` + `tabLayout=vertical`），桌面形态不经过此路径

---

## 1. 根因：真实约束链

按 Compose `Column` 的真实测量顺序逐步给出（行号为**修复前**的当前真实位置，已用 grep/read 复核）：

### 步骤 1 — 共同父容器是一个 `Column`

`app/src/main/java/com/xiufeigo/opencodeshell/ui/App.kt:1570`（修复前）：

```kotlin
Column(Modifier.fillMaxSize().background(OcTheme.colors.bgDeep)) {
```

### 步骤 2 — 第一个子节点是带权重的 NavHost

`ui/App.kt:1614-1617`：

```kotlin
Box(
    Modifier
        .fillMaxWidth()
        .weight(1f)
```

即 NavHost 宿主是 `weight(1f)`，它的**高度完全来自 Column 剩下的空间**。

### 步骤 3 — 移动形态下，`BottomTabStrip` 是同一个 Column 的子节点

`ui/App.kt:1971-1973`：

```kotlin
if (bottomTitlebar) {
    val activeTab = openSessions.firstOrNull { it.id == currentSession }
    BottomTabStrip(
```

### 步骤 4 — `BottomTabStrip` 的函数体里，Row 与 `MobileTabsDrawer` 是**两个并列发射的兄弟**

`ui/nav/BottomTabStrip.kt:147`（Row 的 `Row(`）与 `ui/nav/BottomTabStrip.kt:201`（`MobileTabsDrawer(`）。
`BottomTabStrip` 自身**没有外层布局容器**（没有 `Box`/`Column` 包住），所以这两行直接落在
shell `Column` 的 content lambda 里 —— `Column` 实际看到的是**三个**子节点：
`NavHost(weight=1f)` → `BottomTabStrip` 的 `Row` → `MobileTabsDrawer` 的宿主 `Box`。

### 步骤 5 — 抽屉宿主是 `fillMaxSize`

`ui/nav/MobileTabsDrawer.kt:148`：

```kotlin
Box(modifier = modifier.fillMaxSize()) {
```

### 步骤 6 — 约束链闭合

Compose `Column` 的测量策略（`ColumnMeasurePolicy`）：

1. **先测量所有非 weight 子节点**，每个拿到「当前剩余空间」作为 max；
2. 再把剩余空间按 `weight` 分配给带权重子节点；
3. **按声明顺序自上而下摆放**。

于是抽屉打开时：

| 子节点 | 测量 | 摆放 |
|---|---|---|
| `NavHost` `weight(1f)` | 剩余空间已被抽屉吃光 → **0 px** | y=0 |
| `BottomTabStrip` `Row` | 自身 72 px + 导航栏 inset | y=**0**（紧跟 0 高的 NavHost） |
| 抽屉 `Box(fillMaxSize)` | 拿走整列剩余 | y=96 |

→ 现象与实测完全一致：`BottomTabStrip` 由 `y=1452..1548` 跳到 `y=0..84`（36 dp bar @320dpi
= 2× → 72 px，加 12 px 导航栏 inset = 84 px），并且抽屉的 scrim 画在其上、把整条 bar 压成灰色。
关闭抽屉时该子节点直接 `return`（`MobileTabsDrawer.kt:126` `if (!open) return`），
Column 少一个 `fillMaxSize` 子节点，NavHost 立刻恢复，bar 复原 —— 与「关掉立即复原」一致。

### 步骤 7 — 上游对照：抽屉是覆盖层，不参与高度分配

`_upstream/packages/app/src/shell/mobile-drawer.css:1-7`：

```css
[data-slot="mobile-drawer-overlay"] {
  position: fixed;
  inset: 0;
  z-index: 50;
  background: var(--v2-overlay-simple-overlay-scrim);
```

`_upstream/packages/app/src/shell/mobile-drawer.css:13-19`：

```css
[data-slot="mobile-drawer-content"] {
  box-sizing: border-box;
  position: fixed;
  inset-inline: 0;
  bottom: 0;
  z-index: 51;
```

**两半都是 `position: fixed`**：`fixed` 元素相对视口定位，**脱离文档流**，
既不占父容器高度，也不影响父容器对其它子节点的测量 —— 这正是 Android 形态缺失的语义。
`z-index:50/51` 说明它也**覆盖**在 bar 之上（与修复后保留的绘制顺序一致）。

结论：**正确形状 = 抽屉必须是 Column 的兄弟覆盖层，而不是 Column 的流内子节点。**

---

## 2. 改动

思路：把「抽屉挂在哪里」从 `BottomTabStrip` 的**流内发射**改成**宿主提供的覆盖层插槽**。
`BottomTabStrip` 仍然拥有抽屉状态与全部回调（close-then-act 顺序、B-6 关闭标签行、
B-20 拖拽重排/长按重命名一行未动），**只有挂载点变了**；`BottomTabStrip` 的公开签名零变更，
因此 16 个既有 source-pin 测试类全部不受影响。

### 2.1 `ui/nav/BottomTabStrip.kt`

新增一个 composition local（挂载点插槽，默认保持原行为）：

```kotlin
/**
 * v97 N-3 — **where the mobile tabs sheet is mounted**.
 * ...
 */
internal val LocalMobileDrawerOverlay =
    compositionLocalOf<@Composable (drawer: @Composable () -> Unit) -> Unit> {
        { drawer -> drawer() }
    }
```

抽屉发射改为经由插槽（`open`/回调体逐字保留，仅换了一层壳）：

```diff
-    MobileTabsDrawer(
-        open = drawerOpen,
-        onDismissRequest = { drawerOpen = false },
-        sessions = sessions,
-        ...
-        onRenameSession = onRenameSession,
-    )
+    // v97 N-3: the sheet leaves this composable's flow. ...
+    LocalMobileDrawerOverlay.current {
+        MobileTabsDrawer(
+            open = drawerOpen,
+            onDismissRequest = { drawerOpen = false },
+            sessions = sessions,
+            activeSessionId = activeSessionId,
+            sessionLabel = sessionLabel,
+            tabBadge = tabBadge,
+            onOpenSession = { ... },
+            onCloseSession = onCloseSession,
+            onNewSession = { ... },
+            onHome = { ... },
+            onOpenSettings = { ... },
+            onOpenHelp = { ... },
+            closedTab = closedTab,
+            onReopenClosed = { ... },
+            onMoveSession = onMoveSession,
+            onRenameSession = onRenameSession,
+        )
+    }
```

（`closedTab = closedTab` / `onReopenClosed = {` / `onMoveSession = onMoveSession` /
`onRenameSession = onRenameSession` 四条既有 pin 逐字保留，见
`ChordCommandWiringTest:111-113`、`TabDragWiringTest:81-83`。）

### 2.2 `ui/App.kt`（只动布局那一段，未碰 nav 恢复代码）

在 shell `Column` 外包一层全尺寸 `Box`，并由它提供覆盖层插槽：

```diff
+        // v97 N-3: the mobile tabs sheet is an **overlay**, so it is mounted as a
+        // sibling of the shell column rather than as one of its flow children.
+        // ... (`_upstream/packages/app/src/shell/mobile-drawer.css:1-31`)
+        Box(Modifier.fillMaxSize()) {
+            CompositionLocalProvider(
+                LocalMobileDrawerOverlay provides { drawer ->
+                    // The last child of the box, so the sheet and its scrim
+                    // paint over the column — bar included, as before.
+                    Box(Modifier.fillMaxSize()) { drawer() }
+                },
+            ) {
         Column(Modifier.fillMaxSize().background(OcTheme.colors.bgDeep)) {
             ...  // 内容逐字不变
+            }
+        }
```

+1 行 import：`import com.xiufeigo.opencodeshell.ui.nav.LocalMobileDrawerOverlay`
（`CompositionLocalProvider` 早已 import，未新增）。

> 为何不用「零高度 `Modifier.layout` 覆盖层」：那样虽然不撑父容器，但 Compose 的命中测试按节点
> 自身 bounds 裁剪（`withinLayerBounds`），0×0 的节点**收不到点击**，scrim 的关闭手势和抽屉行会全部失效。
> 覆盖层必须是**真实的全尺寸兄弟节点**。

### 2.3 未破坏项（逐条自检 + 测试 pin）

| 约束 | 状态 |
|---|---|
| 桌面形态 `DesktopTabStrip` | 未改一行；`DesktopTabStrip.kt` 仍不挂载 `MobileTabsDrawer`（测试 pin） |
| 移动形态底部条自身位置 | 仍是 Column 最后一个子节点，`windowInsetsPadding(navigationBars)` + `.height(BAR_HEIGHT)` 原样 |
| 抽屉开关动画与遮罩 | `DialogScrim` / `dialogCardBounds` / 关闭顺序未动；覆盖层是全尺寸 Box，scrim 仍覆盖整窗 + bar |
| `NavHost` 的 `weight(1f)` 高度 | 未动；抽屉离流后 Column 恒有剩余空间，NavHost 不再被压零 |
| v49/v82 窗口 / inset 口径 | 未动 `App.kt` 的 `consumeWindowInsets` / `WindowInsets` 段 |
| 本轮 `navRestoreSettled` 门控 | 未触碰（测试 pin 三处：声明、restore 门、通知门） |
| 用户可见字符串 | 无新增 |
| 既有断言强度 | 无降低 |

---

## 3. 测试证据

### 3.1 新增 JVM pin

`app/src/test/java/com/xiufeigo/opencodeshell/MobileDrawerLayoutV97Test.kt`（纯 JVM；
本仓库 test 依赖仅 junit/org.json/mockwebserver，**未引入 compose / robolectric**）。

8 个用例，两类断言：

**(a) 纯函数表达修复语义** —— 把 `Column` 的测量策略建模为
`allocateColumn(windowHeightPx, children, overlayInFlow)`：先测非 weight 子节点、
再把剩余按 weight 分配、最后按声明顺序摆放；`overlayInFlow=false` 即修复后的形状。

- `the old tree reproduces the reported bar at y equals 0`
  → 断言抽屉宿主吃掉 `window-96`、`NavHost == 0`、`bottomBar.top == 0`（即实测的 y=0）。
- `the fixed tree keeps the NavHost height and the bar at the bottom`
  → 断言开/关抽屉时 `heights` 与 `tops` **逐项相等**（开抽屉不再改变 Column 一个像素）、
  `NavHost == window-96`、`bottomBar.top == window-96`（贴底）、抽屉不再是 Column 子节点。

**(b) 源码结构 pin** —— 抽屉宿主不得与该 Column 的直接子节点同级；抽屉必须在覆盖层中；
`BottomTabStrip` 所在行的位置不受抽屉影响；以及「不许破坏」清单
（`weight(1f)` / `BAR_HEIGHT` / `navigationBars` / 桌面条不挂抽屉 / `navRestoreSettled` 三处）。

### 3.2 命令与结果

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --console=plain :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.MobileDrawerLayoutV97Test" `
  --tests "com.xiufeigo.opencodeshell.ChordCommandWiringTest" `
  --tests "com.xiufeigo.opencodeshell.TabDragWiringTest" `
  --tests "com.xiufeigo.opencodeshell.ExperimentalTabLayoutTest" `
  --tests "com.xiufeigo.opencodeshell.PaletteEntryPointsTest" `
  --tests "com.xiufeigo.opencodeshell.SessionTabStripEdgeFadeTest" `
  --tests "com.xiufeigo.opencodeshell.DialogScrimResidueTest" `
  --tests "com.xiufeigo.opencodeshell.DialogExitResidueTest" `
  --tests "com.xiufeigo.opencodeshell.TabRenameFieldSelectionTest" `
  --tests "com.xiufeigo.opencodeshell.TabIconTouchTargetRingTest" `
  --tests "com.xiufeigo.opencodeshell.DesktopTabStripHomeTouchTargetTest" `
  --tests "com.xiufeigo.opencodeshell.HomeTitleInputV40Test" `
  --tests "com.xiufeigo.opencodeshell.OcKeyboardOptionsTest" `
  --tests "com.xiufeigo.opencodeshell.LastRouteRestoreV97Test" `
  --tests "com.xiufeigo.opencodeshell.DropdownMenuDarkSurfaceTest" `
  --tests "com.xiufeigo.opencodeshell.SessionPaneShortWindowV55Test"
```

BUILD SUCCESSFUL 原文（末行）：

```
BUILD SUCCESSFUL in 7s
52 actionable tasks: 5 executed, 47 up-to-date
```

用例数（`app/build/test-results/testDebugUnitTest/*.xml` 汇总）：

| 测试类 | 用例 | 失败 | 错误 |
|---|---:|---:|---:|
| MobileDrawerLayoutV97Test（新） | 8 | 0 | 0 |
| ChordCommandWiringTest | 6 | 0 | 0 |
| TabDragWiringTest | 4 | 0 | 0 |
| ExperimentalTabLayoutTest | 4 | 0 | 0 |
| PaletteEntryPointsTest | 11 | 0 | 0 |
| SessionTabStripEdgeFadeTest | 8 | 0 | 0 |
| DialogScrimResidueTest | 6 | 0 | 0 |
| DialogExitResidueTest | 7 | 0 | 0 |
| TabRenameFieldSelectionTest | 6 | 0 | 0 |
| TabIconTouchTargetRingTest | 4 | 0 | 0 |
| DesktopTabStripHomeTouchTargetTest | 4 | 0 | 0 |
| HomeTitleInputV40Test | 9 | 0 | 0 |
| OcKeyboardOptionsTest | 10 | 0 | 0 |
| LastRouteRestoreV97Test | 15 | 0 | 0 |
| DropdownMenuDarkSurfaceTest | 11 | 0 | 0 |
| SessionPaneShortWindowV55Test | 14 | 0 | 0 |
| **合计** | **127** | **0** | **0** |

既有 119 个用例是「本轮真正引用 `BottomTabStrip` / `MobileTabsDrawer` / `TabLayout` / `App.kt` 布局」的
全部测试类（grep `app/src/test` 命中 16 个文件），全绿。

> 首轮编译曾失败一次（`@Composable invocations can only happen from the context of a @Composable
> function`，App.kt:1588 / BottomTabStrip.kt:92）：插槽的**外层** lambda 类型漏了 `@Composable`。
> 已把类型改为 `compositionLocalOf<@Composable (drawer: @Composable () -> Unit) -> Unit>`，
> 上表为修正后重跑的结果。

---

## 4. 真机复验步骤

```bash
# 1. 前置：设置 → 实验性功能 → 标签页布局=垂直；重启 app
adb -s emulator-5560 shell wm size 1200x1600
adb -s emulator-5560 shell wm density 320
# 2. 重启 app → 打开任意会话 → 收键盘（Gboard 左下 ▽）
adb -s emulator-5560 shell input tap 110 1500      # 打开移动抽屉
```

复验断言（`uiautomator dump` 读 `BottomTabStrip` 四个槽位的 bounds）：

1. **标签条仍在底部**：`首页` / `tab` / `+` / palette 四槽的 bounds 仍为
   `y≈1452..1548`（窗口底 1600 − 导航栏 inset），**不得**出现 `y=0..84`。
2. **NavHost 未被压零**：抽屉打开时 `会话` 页正文/编辑区 bounds 高度与关闭时一致
   （可对比 `dumpsys` 或两次 dump 中正文节点 bounds）。
3. **状态栏未被压**：左上角状态栏区域无 tab 文字，截图与关闭抽屉时状态栏像素一致。
4. 遮罩与交互回归：点 scrim 可关闭；点某 tab 切会话且抽屉自动收起；
   长按拖拽重排、长按菜单 Rename、设置/Help 行均可点（验证覆盖层的命中测试正常）。

建议同时留两张间隔 ≥1 分钟的截图，确认不是动画过渡帧（与原报告同口径）。

---

## 5. 未闭环

| 项 | 状态 | 原因 / 后续 |
|---|---|---|
| 真机复验 | **未做** | 本轮环境纪律禁止装机（`assembleDebug`/装包），只允许跑 JVM 单测；需测试员按 §4 复验并回填截图 |
| 覆盖层默认值的兜底 | 已留 | `LocalMobileDrawerOverlay` 默认 `{ drawer -> drawer() }`（原位渲染）保持未升级宿主行为；当前唯一调用方是 `App.kt`，已升级。后续若有新宿主，需一并提供覆盖层，否则会退回本缺陷 |
| N-1 冷启动 route 恢复 / N-2 设置卡片 IME 位移 | 不在本任务范围 | 分别由 `LastRouteRestoreV97Test` 所在轮次与 Lead 裁决处理；本次只动布局段，`navRestoreSettled` 三处 pin 已证明未被波及 |
| 全量测试 | 未跑 | 环境纪律仅允许精确 `--tests` 白名单；本次跑了全部 16 个相关类（127 例） |
