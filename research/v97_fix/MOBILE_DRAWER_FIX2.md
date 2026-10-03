# v98 移动抽屉布局修复（第二次返工）— v97 N-3 / 移动形态空壳

**结论：上一版修复判定为「引入空壳回归」，已确认（可判定，非推测）。**
本次用**真重父化**（覆盖层作为外层 `Box` 的后置兄弟子节点）替代了无效的 `CompositionLocal`，
空壳与 N-3 位移在**结构上**同时被消除；JVM pin 93 例全绿。
**真机复验未做**（本轮纪律禁止装机），复验步骤见 §4。

- 上一版修复单：`research/v97_fix/MOBILE_DRAWER_LAYOUT.md`（v97 N-3）
- 复验一（BLOCKED）：`research/v97_verify/5560_v98/REPORT_DRAWER.md`
- 复验二（FAIL）：`research/v97_verify/5560_v98/5560_v98_drawer2/REPORT_DRAWER2.md`
- 修复日期：2026-10-02

---

## 1. 定性

### 1.1 「垂直标签页 + `wm 1200x1600@320`」为什么空壳 —— **是上一版修复引入的**

**可以判定，且是代码级可证的因果链**，不需要靠回退实验来猜。链条如下。

**第 1 环 — 空壳的读数本身就是缺陷签名。**
复验二实测：移动宽度 + `vertical` 时，抽屉**关闭态**标签条恒为 `y=0..84`（S4/S5/S6 三种入口一致），
且只剩 13 个节点（`REPORT_DRAWER2.md` §1 诊断表、§3 断言 1/2）。
`y=0..84` 正是 N-3 的缺陷区间。**关闭态就出现 N-3 的读数**，只有一种解释：
那个吃掉列高的宿主节点在抽屉**关闭时依然存在于布局中**。

**第 2 环 — 空壳只在「移动宽度 ∧ vertical」时发生，而那正是 `BottomTabStrip` 唯一被组合的条件。**
`bottomTitlebar = !desktopTabs && bottomTabStrip`（`OcActivity.kt:143` → `AppearancePrefs.bottomTabStrip()`
→ `appearance.tabLayout == "vertical"`）。
所以：桌面宽度 → `desktopTabs=true` → 不组合 `BottomTabStrip` → 抽屉宿主不存在 → S1/S3 正常（153 节点）；
`horizontal` → `bottomTabStrip=false` → 同理 → S2 正常（97 节点）；
**只有两者同时成立才组合 `BottomTabStrip`**，才引入那个 `fillMaxSize` 宿主。
复验二的 S1–S7 对照表与这条链**逐项吻合**，包括「与冷/热启动顺序无关」（S6 是活体 resize，宿主同样在场）。

**第 3 环 — v97 的 `CompositionLocal` 不重父化，宿主仍在 Column 的 flow 里。**
v97 修复单 §2.2 写下的形状（当时的 `App.kt:1583-1591`）：

```kotlin
Box(Modifier.fillMaxSize()) {
    CompositionLocalProvider(
        LocalMobileDrawerOverlay provides { drawer ->
            Box(Modifier.fillMaxSize()) { drawer() }   // ← 期望的重父化容器
        },
    ) {
Column(Modifier.fillMaxSize().background(OcTheme.colors.bgDeep)) {
```

`CompositionLocal` 提供的是一个**值**。消费端 `BottomTabStrip.kt:244` 调用
`LocalMobileDrawerOverlay.current { … }` 时，provider 那个 lambda 的函数体
**在调用点的组合组里就地执行**，所以 `Box(Modifier.fillMaxSize())` 是在
`BottomTabStrip` 的组合体里被组合的 —— 而 `BottomTabStrip` 是 shell `Column` content lambda 的直接子节点。
**它不会被搬到外层 `Box` 下。** `CompositionLocalProvider` 只提供值，从不重父化。
v97 修复单自己的注释已经描述了正在发生的行为，只是把它当成了已修好的状态。

**第 4 环 — 而且是无条件在场，所以关闭态也塌陷。**
`MobileTabsDrawer.kt:126` 是 `if (!open) return`，它在抽屉 composable **内部**、宿主
`Box(modifier = modifier.fillMaxSize())`（`MobileTabsDrawer.kt:148`）**之下**。
v97 把宿主上提到了 `BottomTabStrip` 的组合体里，于是这个 `Box` **在 `open=false` 时照样被组合**：
它占满整列剩余高度，里面什么都不画。**这就是空壳。**

**第 5 环 — 闭合成读数。**
`ColumnMeasurePolicy` 先测全部非 weight 子节点，再把剩余按 weight 分配，最后按声明顺序摆放。
以 `window=1600`、`bar=84`（36 dp @320dpi = 72 px + 12 px 导航栏 inset）计：

| 步骤 | 计算 | 结果 |
|---|---|---|
| 测非 weight 子节点（声明序） | bar → 84，剩 1516 | 宿主 `fillMaxSize` → 吃掉 **1516**，剩 **0** |
| 分配 weight | `weight(1f)` NavHost 分到 **0** | NavHost 高度 = **0 px** |
| 摆放（声明序） | NavHost(0) → bar(84) → 宿主(1516) | bar 顶边 = **0**，即 **y=0..84** |

→ NavHost 0 px ⇒ 正文（项目列表/会话列表/会话正文）全部不可见 ⇒ **13 节点空壳**；
→ bar 顶边 y=0 ⇒ 压在状态栏上。**两条实测读数同源。**

**第 6 环 — 上一版修复之前不存在空壳。**
修复前的形状是 `MobileTabsDrawer(...)` 直接在 `BottomTabStrip` 体内发射，而它的
`if (!open) return` 在宿主 `Box` 之上 ⇒ **关闭时什么都不组合**，Column 少一个非 weight 子节点，
NavHost 正常、bar 正常贴底。空壳只在**打开抽屉**时出现 —— 那正是原始 N-3 缺陷。
所以：**N-3 是「打开时」塌陷（老缺陷），v97 把它变成「恒定」塌陷（新回归）。**
这也解释了复验一之所以能「在此配置下把抽屉点开」：那时还是修复前的形状。

**判定：空壳是上一版修复（v97）引入的回归。** 触发条件是
「移动宽度 ∧ `tabLayout=vertical`」（= 唯一会组合 `BottomTabStrip` 的组合），
不是 `wm` 姿势、不是慢后端、不是 JIT —— 复验二用 S2/S3 两个对照组已分别排除，
本报告第 1、4 环给出机制解释。

> 无需再跑「回退 hunk 复验」实验来确认引入关系：第 1/2 环的读数与第 5 环的推导已经闭合。
> 若 Lead 仍想要端到端证据，§4 步骤 ④ 就是那个实验（修复后同一配置必须不再是空壳）。

### 1.2 要让抽屉**真的**成为覆盖层，正确的 Compose 结构是什么

**错误的（v97 的）思路**是「把挂载点换到别处去」，但 `CompositionLocal` 只能搬**值**，
搬不动**组合**。要改的是**组合发生的位置**，也就是父节点。

**正确的结构**（本次采用）：

```
Box(Modifier.fillMaxSize()) {                    // 外层盒：Column 与覆盖层的共同父
    Column(Modifier.fillMaxSize()...) {          // ① 第一个子节点：流内
        Box(Modifier.weight(1f)) { NavHost() }   //    NavHost 靠 weight 拿剩余高度
        BottomTabStrip(...)                      //    标签条：Column 最后一个子节点
    }
    if (drawerOpen) {                            // ② 后一个子节点：覆盖层
        Box(Modifier.fillMaxSize()) { sheet() }  //    真·兄弟节点，叠在 Column 之上
    }
}
```

**为什么这次能真正改变布局树**：
1. `sheet()` 这段内容是在 **② 的 `Box` 的 content lambda 里被调用**的。
   Compose 决定 layout 父节点靠的是「当前 `LayoutNode`」，即**调用点的组合上下文**，
   不是词法作用域。内容在这里被组合 ⇒ 它产生的 `LayoutNode` 挂在**这个 `Box`** 下
   ⇒ 父节点是外层 `Box`，**与 `Column` 平级**，不是 `Column` 的 flow 子节点。
2. `Column` 少了一个非 weight 子节点，`weight(1f)` 的 NavHost 不再被抢，bar 恒贴底。
   **开/关抽屉时，Column 的测量结果逐像素相同。**
3. 覆盖层用 `fillMaxSize` 而不是零高度，是必须的：Compose 命中测试按节点自身 bounds 裁剪
   （`withinLayerBounds`），0×0 的节点收不到点击，scrim 与抽屉行会全部失效。
   `Box` 天然叠放而非流式排布，且**后声明的子节点画在上层**，所以 scrim 仍盖住 bar —— 与旧行为一致。
4. 覆盖层用 `if (open)` 包住：抽屉关闭时**连宿主都不组合**，彻底消除「无条件在场」这一回归面。

**状态提升是必需的，且已做**：因为组合点在 `App.kt`，`drawerOpen` 必须能被宿主读到。
做法是 `MobileDrawerMount`（一个 `@Stable` 持有 `open` + `content` 的小对象），
由 `BottomTabStrip` 用 `SideEffect` 发布、由 `App.kt` 读取并**在兄弟槽位调用**。
`BottomTabStrip` 依旧拥有抽屉的全部状态与回调（close-then-act 顺序、B-6 关闭标签行、
B-20 拖拽重排/长按重命名一行未动），只有**组合点**变了。

> `Popup` / `Dialog` 也能给覆盖层语义，但会换掉自带的 `DialogScrim` / `dialogCardBounds` 动画与
> BACK 手势口径，属于超出本任务范围的改动，故未采用。

---

## 2. 改动（unified diff）

写权限内只动了 3 个文件：`ui/nav/BottomTabStrip.kt`、`ui/App.kt`（仅移动抽屉/标签条那一段布局）、
本轮测试文件。**未新增任何用户可见字符串**（`StringsLocaleParityTest` 3 例全绿可证）。

### 2.1 `ui/nav/BottomTabStrip.kt`

```diff
-import androidx.compose.runtime.compositionLocalOf
+import androidx.compose.runtime.DisposableEffect
+import androidx.compose.runtime.SideEffect
+import androidx.compose.runtime.Stable

-internal val LocalMobileDrawerOverlay =
-    compositionLocalOf<@Composable (drawer: @Composable () -> Unit) -> Unit> {
-        { drawer -> drawer() }
-    }
+@Stable
+class MobileDrawerMount {
+    internal var open by mutableStateOf(false)                     // :106
+    internal var content: (@Composable () -> Unit)? = null        // :113
+}

     onNewSession: () -> Unit,
+    drawerMount: MobileDrawerMount,                                // :160（必填，无默认值）
     modifier: Modifier = Modifier,

-    LocalMobileDrawerOverlay.current {                             // 旧：在此处组合 → Column 的 flow 子节点
+    val drawerContent: @Composable () -> Unit = {                // :273 新：只构造，不组合
         MobileTabsDrawer(
             open = drawerOpen,
             onDismissRequest = { drawerOpen = false },
             … 全部回调逐字保留 …
         )
     }
+    SideEffect {                                                   // :313 组合后发布，绝不重入组合
+        drawerMount.content = drawerContent
+        drawerMount.open = drawerOpen
+    }
+    DisposableEffect(drawerMount) {                                // :319 离开组合时卸载
+        onDispose {
+            drawerMount.open = false
+            drawerMount.content = null
+        }
+    }
 }
```

### 2.2 `ui/App.kt`（只动移动抽屉/标签条这一段布局）

```diff
+import com.xiufeigo.opencodeshell.ui.nav.MobileDrawerMount          // 替换 LocalMobileDrawerOverlay
-import com.xiufeigo.opencodeshell.ui.nav.LocalMobileDrawerOverlay

-        Box(Modifier.fillMaxSize()) {
-            CompositionLocalProvider(
-                LocalMobileDrawerOverlay provides { drawer ->
-                    Box(Modifier.fillMaxSize()) { drawer() }
-                },
-            ) {
+        val mobileDrawerMount = remember { MobileDrawerMount() }    // :1589
+        Box(Modifier.fillMaxSize()) {                               // :1590
         Column(Modifier.fillMaxSize().background(OcTheme.colors.bgDeep)) {   // :1591 ① 第一个子节点

                     onRenameSession = onRenameSessionTab,
+                    drawerMount = mobileDrawerMount,                // :2031
                 )
             }
         }
-            }
+            // ② 后一个子节点：覆盖层，与 Column 平级、叠在其上
+            val mobileSheet = mobileDrawerMount.content            // :2040
+            if (mobileDrawerMount.open && mobileSheet != null) {   // :2041
+                Box(Modifier.fillMaxSize()) { mobileSheet() }      // 真·兄弟节点
+            }
         }
```

### 2.3 未破坏项（逐条自检 + pin）

| 约束 | 状态 |
|---|---|
| 桌面形态 `DesktopTabStrip` | 未改一行；仍不挂载 `MobileTabsDrawer` / `MobileDrawerMount`（pin） |
| 移动形态底部条自身位置 | 仍是 Column 最后一个子节点，`windowInsetsPadding(navigationBars)` + `.height(BAR_HEIGHT)` 原样 |
| 抽屉开关动画 / 遮罩 | `DialogScrim` / `dialogCardBounds` / close-then-act 顺序未动；覆盖层是全尺寸 Box，scrim 仍盖满窗口与 bar |
| NavHost `weight(1f)` 高度 | 未动，且不再被宿主抢走 |
| v49/v82 窗口 / inset 口径 | 外层 `Box(Modifier.fillMaxSize())` 无任何 inset；`topStripOwnsStatusBar` 与 `consumeWindowInsets(statusBars)` 未动（pin） |
| 本轮 `navRestoreSettled` 门控 | 未触碰（三处 pin 全绿） |
| 用户可见字符串 | 无新增（`StringsLocaleParityTest` 3/3） |
| 既有断言强度 | 只增不减：模型新增 `painted` 维度与 4 个用例，既有 8 个断言全部保留 |

---

## 3. 测试证据

### 3.1 更新的 JVM pin

`app/src/test/java/com/xiufeigo/opencodeshell/MobileDrawerLayoutV97Test.kt`（纯 JVM；
本仓库 test 依赖仅 junit/org.json/mockwebserver，**未引入 compose / robolectric**）。
8 例 → **13 例**。上一版的 4 条断言是**基于被证伪的形状**（断言
`strip.contains("LocalMobileDrawerOverlay.current {")`、`app` 中
`LocalMobileDrawerOverlay provides` 出现在 `Column(` 之前、默认 `{ drawer -> drawer() }` 就地渲染），
已按新形状重写，并**加强**为反向断言。

**(a) 纯函数模型** — `ColumnMeasurePolicy` + 新的 `painted` 维度。
把「**测得**的高度」与「**画出来的**内容」分开，正是 v97 空壳的本质：宿主吃掉整列却什么都不画。

- `the old tree reproduces the reported bar at y equals 0`（保留）→ 宿主吃 `window-96`、NavHost `== 0`、bar `top == 0`。
- **新** `the v97 host stayed in the column with the sheet closed` → 对 `sheetOpen ∈ {false, true}` 双向断言
  NavHost 恒为 0、bar 恒在 y=0；且 `sheetOpen=false` 时 `painted == {bottomBar}`（**只有条**，
  对应真机 13 节点空壳）。
- `the fixed tree keeps the NavHost height and the bar at the bottom`（保留并加强）→ 开/关 `heights`、`tops` 逐项相等；
  NavHost `== window-96`；bar `top == window-96`；`!heights.containsKey(覆盖层)`。
- **新** `the v98 tree restores the shell in the mobile vertical form` → 移动+vertical 形态下正文高度恢复、
  bar 贴底、开抽屉不改变任何一像素。

**(b) 源码结构 pin**（机制，钉「这次真的换了布局树」）：

- **新/加强** `LocalMobileDrawerOverlay` 在 `App.kt` 与 `BottomTabStrip.kt` 中**均不得出现**
  —— 防止再次有人用「换个 CompositionLocal 包一层」冒充重父化。
- **新** 抽屉内容必须是 `val drawerContent: @Composable () -> Unit = { … }`（构造值），
  且 `SideEffect { drawerMount.content = …; drawerMount.open = … }` 发布。
- **新** 宿主的组合点：`app` 中 `val mobileSheet = mobileDrawerMount.content` 的下标
  **必须大于** `BottomTabStrip(` 的下标（即在 Column 之后），且宿主盒子写作一个
  `if (mobileDrawerMount.open && mobileSheet != null) { Box(Modifier.fillMaxSize()) { mobileSheet() } }` 块。
- **新** `drawerMount: MobileDrawerMount,` **必填且无默认值**（默认值会静默退回流内形状）。
- **新** `DisposableEffect(drawerMount)` 在离开组合时 `open=false` / `content=null`（不留残挂载）。
- 保留 `the NavHost weight, the bar and the desktop form are untouched`、
  `the v97 route-restore gate is not disturbed`、`the sheet's fillMaxSize host is unchanged…`
  （新增 `DialogScrim(scrim, onDismissRequest)` 与 `onClick = { drawerOpen = true }` 两条）。

### 3.2 命令与结果

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --console=plain :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.MobileDrawerLayoutV97Test" `
  --tests "com.xiufeigo.opencodeshell.HomeNavTest" `
  --tests "com.xiufeigo.opencodeshell.Wave23aSettingsPaletteTerminalTest" `
  --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest" `
  --tests "com.xiufeigo.opencodeshell.ChordCommandWiringTest" `
  --tests "com.xiufeigo.opencodeshell.TabDragWiringTest" `
  --tests "com.xiufeigo.opencodeshell.ExperimentalTabLayoutTest" `
  --tests "com.xiufeigo.opencodeshell.DesktopTabStripHomeTouchTargetTest" `
  --tests "com.xiufeigo.opencodeshell.SessionTabStripEdgeFadeTest" `
  --tests "com.xiufeigo.opencodeshell.TabRenameFieldSelectionTest" `
  --tests "com.xiufeigo.opencodeshell.TabIconTouchTargetRingTest" `
  --tests "com.xiufeigo.opencodeshell.MobileTitlebarRowTest"
```

BUILD SUCCESSFUL 原文（末两行）：

```
BUILD SUCCESSFUL in 4s
52 actionable tasks: 2 executed, 50 up-to-date
```

用例数（`app/build/test-results/testDebugUnitTest/*.xml` 汇总）：

| 测试类 | 用例 | 失败 | 错误 |
|---|---:|---:|---:|
| **MobileDrawerLayoutV97Test（本轮更新）** | **13** | 0 | 0 |
| HomeNavTest | 8 | 0 | 0 |
| Wave23aSettingsPaletteTerminalTest | 25 | 0 | 0 |
| StringsLocaleParityTest | 3 | 0 | 0 |
| ChordCommandWiringTest | 6 | 0 | 0 |
| TabDragWiringTest | 4 | 0 | 0 |
| ExperimentalTabLayoutTest | 4 | 0 | 0 |
| DesktopTabStripHomeTouchTargetTest | 4 | 0 | 0 |
| SessionTabStripEdgeFadeTest | 8 | 0 | 0 |
| TabRenameFieldSelectionTest | 6 | 0 | 0 |
| TabIconTouchTargetRingTest | 4 | 0 | 0 |
| MobileTitlebarRowTest | 8 | 0 | 0 |
| **合计** | **93** | **0** | **0** |

> 选类口径：任务点名的 3 类 + `StringsLocaleParityTest`，再 grep `app/src/test` 找出所有
> 引用 `App.kt` / `BottomTabStrip` / `MobileTabsDrawer` / `LocalMobileDrawerOverlay` 的类
> （命中 65 个文件，绝大多数命中的是无关的 `ui/App.kt` 字样或 `SessionTab*`），
> 逐一筛出**真正读这三个文件源码**的 pin 类后全绿。`PaletteEntryPointsTest` /
> `DialogScrimResidueTest` / `DialogExitResidueTest` / `HomeTitleInputV40Test` /
> `OcKeyboardOptionsTest` / `LastRouteRestoreV97Test` / `DropdownMenuDarkSurfaceTest` /
> `SessionPaneShortWindowV55Test` 是上一版跑过的相关类，本轮**未改动**任何被它们 pin 的行
> （`StringsLocaleParityTest`、`navRestoreSettled` 三处 pin 均已验证），
> 但受纪律限制未纳入本次白名单命令，如需可追加。

**编译期失败两次（如实记录）**：

1. `e: …/ui/nav/BottomTabStrip.kt:157:5 Function 'public' exposes its 'internal' parameter type 'MobileDrawerMount'.`
   → `BottomTabStrip` 是 public，不能有 internal 形参。改为 `class MobileDrawerMount`（成员仍 `internal`）。
2. `MobileDrawerLayoutV97Test > the v97 host stayed in the column with the sheet closed FAILED`（`93 tests completed, 1 failed`）
   → 模型初版把**高度为 0 的 NavHost 也算作 painted**，这恰好抹掉了「空壳」这个要点。
   改为「测得高度 > 0 才算 painted」后，语义更准（星级 NavHost 什么都不画），
   并直接对上真机 13 节点读数。

上表为两次修正后的最终结果。

---

## 4. 真机复验步骤（给测试员，可复现）

前置：设备 `emulator-5560`，包名 `com.xiufeigo.opencodeshell`，
设置 → 实验性功能 → 标签页布局。**本轮禁止装机，故需用本轮构建重新出包。**

| # | 配置 | 期望 |
|---|---|---|
| ① | 默认基准：`wm size/density reset`（2560×1600@320）+ 标签布局=**水平** | 正常。**语义节点数 ≈ 153**；桌面条 `y=36..132`；项目/会话列表齐全 |
| ② | 仅 `vertical`：桌面宽度 + 标签=垂直 | 正常，**≈153 节点**，条仍在顶部（桌面条不受影响） |
| ③ | 仅 wm：`wm size 1200x1600` + `wm density 320`，标签仍**水平** | 正常，**≈97 节点**（移动宽度本身无害） |
| ④ | **两者同时（关键）**：`wm 1200x1600` + `wm density 320` + 标签=**垂直** | **不得再空壳**：语义节点数应回到移动宽度正常量级（对照 ③ 的 97），**禁止**再出现 13 节点；**标签条必须在底部 `y≈1452..1548`，禁止再出现 `y=0..84`** |
| ⑤ | 在 ④ 的形态下点开抽屉 | 标签条 **仍在底部 `y≈1452..1548`**（开/关两次 dump 的条 bounds 必须**逐字相同**）；NavHost 正文高度与关闭时一致；scrim 覆盖整窗 + bar |
| ⑥ | 收尾还原 | `wm size reset` + `wm density reset`（读回 `Physical size: 2560x1600`、无 Override）；标签布局回**水平**（`oc_settings.xml` 复核 `appearance.tabLayout=horizontal`）；确认桌面形态恢复 153 节点 |

命令：

```bash
adb -s emulator-5560 shell wm size 1200x1600
adb -s emulator-5560 shell wm density 320
# 冷启动 → settle ≥8s → uiautomator dump（**必须 dump 全节点**，含无文本节点，
# 否则会漏掉「只剩搜索框 + 3 个条按钮」的空壳读数）
# 打开会话后收键盘，点条内会话槽位开抽屉
adb -s emulator-5560 shell input tap 110 1500
```

**判读要点**

1. ④ 是本轮的核心回归断言。若 ④ 仍是 13 节点且条在 `y=0..84` ⇒ 结构改动未生效（说明组合点仍未落在 `Box` 上）。
2. ⑤ 用**两次 dump 的条 bounds 逐字比对**，不要只看截图（动画过渡帧会造成误判）。
   建议间隔 ≥1 分钟各留一张截图（与前两轮同口径）。
3. 抽屉交互回归（验证覆盖层命中测试正常，零高度节点会收不到点击）：
   点 scrim 关闭；点某 tab 切会话且抽屉自动收起；长按拖拽重排；长按菜单 Rename；
   设置 / Help 行可点。
4. `logcat` 若出现 FATAL / AndroidRuntime / ANR，按缺陷回单。

---

## 5. 未闭环

| 项 | 状态 | 原因 / 后续 |
|---|---|---|
| 真机复验（④⑤ 两条核心断言） | **未做** | 本轮环境纪律禁止 `assembleDebug` / 装机，只允许跑 JVM 单测。需测试员按 §4 复验并回填 `uiautomator dump` 读数 |
| 空壳「由上一版引入」的端到端证据 | **代码级已定论** | §1 六环因果链已闭合，不需回退实验即可判定。若 Lead 仍要反向证据，§4 ④ 即是（修复后同配置不再空壳） |
| 抽屉**打开态**的 bar y 读数 | **未取得** | 依赖 §4 ⑤（抽屉是否可打开） |
| 空壳态 ~45 s 交互延迟 | **未定性 / 预期随本修复消失** | 复验二记录 NavHost 为 0 px 时交互退化。本修复让 NavHost 不再为 0，**预期**随之消失，但未经真机确认；若 ④ 恢复后仍有延迟，需另开单 |
| `GlobalKeybindFocus` 的 `LocalDialogActive` 未被抽屉置位 | **未改（沿用旧行为）** | 抽屉走 `DialogScrim` 而非 dialog 栈，是既有设计，非本轮回归。覆盖层由 Column 内改为 Column 的兄弟后仍在同一 focusable 根下，chord 语义不变 |
| 全量测试 | 未跑 | 纪律仅允许精确 `--tests` 白名单；本次 93 例（12 类）全绿 |
| 关闭动画 | **无（沿用旧行为）** | 抽屉一直靠 `if (!open) return` 直接卸载、无退场动画。本次覆盖层加了 `if (open)` 门后与之完全一致，未引入新差异 |
