# v98 fix3 — 移动抽屉**入口不可达**（点会话槽位打不开抽屉）

**结论：根因是 fix2 把「打开」这件事做成了两跳手递，而其中一跳是 `SideEffect` 的
组合后写入 —— 点击到屏幕之间没有任何中间可观测量，所以失败是完全静默的。**
已把打开状态收敛为**单一状态源**（入口直接写宿主读的那个 `mutableStateOf`），
并给入口补上既有字符串的 a11y 标签，使它在 `uiautomator` dump 里可被定位。
fix2 的覆盖层结构（后置兄弟节点 + `if (open)` 门控）**一行未动**，空壳回归面未重新引入。

- 上一版修复单：`research/v97_fix/MOBILE_DRAWER_FIX2.md`（fix2）
- 真机 FAIL（本轮输入）：`research/v97_verify/5560_v98/REPORT_DRAWER4.md`（第四轮，7 次点击 `diff=0`）
- 真机 FAIL（前轮）：`research/v97_verify/5560_v98/REPORT_DRAWER3.md`（空壳回归已消失，89 节点 / 条 `y=1452..1548`）
- 修复日期：2026-10-02

---

## 1. 根因（文件:行号 + 原文 + 因果链）

### 1.1 排除项（先说不是哪些，避免测试员与后续轮次重复排查）

| 候选假设 | 判定 | 依据 |
|---|---|---|
| `SessionTab` 自身的切换语义吃掉点击 | **排除** | 底部条从未把 `onOpenSession` 接到槽位上：`BottomTabStrip.kt:268` 的 `onClick` 是唯一的槽位动作，`onOpenSession` 只被 `drawerContent` 里的抽屉行用（`:326`）。**不存在「切换会话」与「开抽屉」的语义竞争** —— 槽位在上游就是 `MobileDrawerTrigger`（`titlebar.tsx:472-519`），设计本身没问题。 |
| clickable 顺序 / 嵌套导致后者覆盖 | **排除** | 槽位可点节点是 `SessionTab.kt` 里 `Row` 的 `.clickable(role = Role.Tab, …)`（`BottomTabStrip.kt:406` 现行号），包住整个槽位；内部 ✕ `Box`（20 dp）只是子节点。真机 dump 里该节点 `clickable` 且 bounds `[84,1452][471,1548]`，测试员 E1 用的就是**从该次 pre-dump 解析出的真实中心**。 |
| 手势竞争 | **排除** | `MobileTabsDrawer` 内唯一手势是抽屉**内部**行的 `detectDragGesturesAfterLongPress`（重排），不在入口上；`App.kt` 侧无 `swipeable` / edge-drag。 |
| `App.kt` 宿主与状态源不是同一个 | **正是如此 ← 根因** | 见 §1.2。 |
| 空壳回归重新出现 | **排除** | 本轮只改状态归属与 a11y 标签，**未碰任何布局/测量代码**；`Column`/`Box`/`weight` 一行未动（§2.2 diff 可证）。 |

### 1.2 真因：「打开」是两跳手递，第一跳是**组合后**的写入

fix2 的形状是**两个状态对象**，中间靠一个 `SideEffect` 搬运：

| 环节 | 位置（fix2 时的行号） | 原文 |
|---|---|---|
| ① 点击写的状态 | `BottomTabStrip.kt:205` | `var drawerOpen by remember { mutableStateOf(false) }` ← **条内局部** |
| ② 唯一点击点 | `BottomTabStrip.kt:237` | `onClick = { drawerOpen = true }` |
| ③ 唯一搬运点 | `BottomTabStrip.kt:313-316` | `SideEffect {`<br>`    drawerMount.content = drawerContent`<br>`    drawerMount.open = drawerOpen`<br>`}` |
| ④ 宿主门控读的状态 | `App.kt:2041` | `if (mobileDrawerMount.open && mobileSheet != null) {` ← **另一个对象** |

**因果链（闭环到实测读数）：**

1. 槽位可点，tap 送达，`drawerOpen = true`（②）——**局部** state，宿主不知道。
2. 只有作用域是 `BottomTabStrip` 的重组会重跑 ③，而 `SideEffect` 按定义在**组合 + apply 之后**执行 —— 这次写入是**脱离点击事件的那一帧**发生的。
3. ③ 里 `drawerMount.open = drawerOpen` 写的是**祖先作用域**读的状态，要让 ④ 重新求值，还得**再来一帧**祖先重组。
4. 于是到屏幕的完整路径是：`点击 → 使条失效 → 条重组 → 组合后搬运 → 使祖先失效 → 第二帧重算门控`。**两个 state、两次失效、一次延后跳跃。**
5. **这次延后跳跃若没有产出后续帧，点击就彻底不可见**：没有 scrim、没有抽屉行、没有 a11y 差异、没有崩溃、没有日志。

第 5 条正是 `REPORT_DRAWER4.md` §2.2 的逐字签名：7 次点击（覆盖 3 个 x、含运行期解析的真实中心、含 20s 长 settle），**每次 post-dump 与 pre-dump 的 `Compare-Object` 为空集**，且 `logcat` 中 app 的 FATAL/ANR 计数为 **0**。**不是渲染错、不是层级错、不是没点到 —— 是这次点击在状态链路上没有任何一个可观测的落点。**

第 4 环还有一个**次生风险**：`content` 是**非 state** 的普通 `var`（`BottomTabStrip.kt:113`），只在条重组时由同一个 `SideEffect` 刷新。也就是说宿主的 `mobileSheet`（`App.kt:2040`）与 `mobileDrawerMount.open`（`App.kt:2041`）由**同一次延后写入**分别提供 —— 一旦这一次写入没兑现，宿主要么什么都不组合，要么组合一个**过期 lambda**。两种失败都同样静默。

### 1.3 次生根因：入口**无 a11y 名称**，真机无法瞄准

- `SessionTab` 里唯一的 `semantics {}` 挂在**内部 ✕ Box** 上（`contentDescription = closeLabel`），**槽位自己的 clickable 没有任何 content-desc**。
- 真机侧双向印证：`REPORT_DRAWER4.md` §2.1 记 `SessionTab [84,1452][471,1548]`（**clickable、无 content-desc**）。
- 后果：测试员没有任何「已命名的节点」可用来解析点击目标，只能靠写死 x 猜 —— 这也是 `REPORT_DRAWER4.md` §2.0 那个「同一实验里 `新建会话` 按钮 x 三次漂移」的测量陷阱来源。**单点入口 + 入口不可命名 = 一次状态失败就退化成「App 没有入口」，与 §4 无法区分。**

---

## 2. 改动

**写权限内只动了 2 个文件**：`ui/nav/BottomTabStrip.kt`、本轮测试文件。
**`ui/App.kt` 本轮一行未改**（宿主门控 `App.kt:2041` 读的仍是 `mobileDrawerMount.open`，
现在这个 state 成了权威源 —— 宿主的代码形状、位置、覆盖层结构全部原样）。
未新增任何用户可见字符串（`StringsLocaleParityTest` 3/3 绿可证）。
未触碰 nav 恢复 `navRestoreSettled`（`LastRouteRestoreV97Test` 15/15 + 原三处 pin 绿）。

### 2.1 `ui/nav/BottomTabStrip.kt`

```diff
-    var drawerOpen by remember { mutableStateOf(false) }
+    // v98 fix3 — no strip-local open flag any more.（注释略，见现行 :208-223）
@@ 会话槽位 @@
-                onClick = { drawerOpen = true },
+                // 入口直接写宿主读的那个 state：一次写入、一次失效，门控不可能漏
+                onClick = { drawerMount.open = true },
                 onClose = { onCloseSession(session) },
                 badge = tabBadge(session),
+                // v98 fix3：入口此前无 content-desc（REPORT_DRAWER4 §2.1），
+                // 真机无法瞄准；标签复用本行上方已加载的 sessionLabel，无新字符串
+                label = sessionLabel,
                 modifier = Modifier.widthIn(max = TAB_MAX_WIDTH),

@@ 手递出去的抽屉内容 @@
         MobileTabsDrawer(
-            open = drawerOpen,
-            onDismissRequest = { drawerOpen = false },
+            // 读 mount 自己的 state，而不是闭包捕获的局部副本
+            open = drawerMount.open,
+            onDismissRequest = { drawerMount.open = false },
             …
             onOpenSession = {   drawerOpen = false   →   drawerMount.open = false   },
             onNewSession   = {   drawerOpen = false   →   drawerMount.open = false   },
             onHome         = {   drawerOpen = false   →   drawerMount.open = false   },
             onOpenSettings = {   drawerOpen = false   →   drawerMount.open = false   },
             onOpenHelp     = {   drawerOpen = false   →   drawerMount.open = false   },
             onReopenClosed = {   drawerOpen = false   →   drawerMount.open = false   },

-    SideEffect {
-        drawerMount.content = drawerContent
-        drawerMount.open = drawerOpen      // ← 本轮删除：这就是缺陷本身
-    }
+    // v98 fix3：只发布 content。open 由入口与各关闭路径直接写，
+    // 绝不再从 SideEffect 搬运 —— 那个搬运就是缺陷。
+    SideEffect {
+        drawerMount.content = drawerContent
+    }
     DisposableEffect(drawerMount) { onDispose { … } }   // 原样未动

@@ SessionTab：新增可选标签（默认 null ⇒ 桌面条完全不变）@@
+    /**
+     * 槽位自身 clickable 节点的 a11y 标签，null = 不加。
+     * 默认值化且桌面条不传：这是**既有节点上的标签**，不是新入口，
+     * 不改几何、不改 clickable、不新增字符串资源。
+     */
+    label: String? = null,
 ) {
     Row(
         modifier = modifier
             .height(TAB_HEIGHT)
             .clip(shape)
             .background(Color.Transparent)
             .clickable(role = Role.Tab, onClick = onClick)
+            // 与 TabIconButton 同一写法：标签挂在**带 ACTION_CLICK 的那个节点**上。
+            // 条件化：label == null 时修饰符链与改动前逐字相同。
+            .then(
+                if (label != null) Modifier.semantics { contentDescription = label }
+                else Modifier,
+            )
             .padding(start = 6.dp, end = 2.dp),
```

**改动后锚点（现行行号）**：`open` 定义 `:117`；`if (session != null)` `:256`；
`onClick = { drawerMount.open = true }` `:268`；`label = sessionLabel` `:280`；
`open = drawerMount.open` `:318`；`onDismissRequest` `:319`；`SideEffect` `:361-362`；
`DisposableEffect` `:366`；`label: String? = null` `:414`；`semantics` `:438`。

### 2.2 未破坏项（逐条自检 + pin）

| 约束 | 状态 | 钉它的用例 |
|---|---|---|
| fix2 的覆盖层结构（`Box` 的后置兄弟 + `if (open)` 门控） | **一行未改**（`App.kt` 本轮零改动） | `the shell composes the sheet outside the column`（含 3 段逐字匹配） |
| 空壳回归面（宿主不得无条件在场） | 未动，`if (open)` 仍在 | 同上 |
| 关闭态/打开态条 y 逐像素一致 | 未动（无布局代码改动） | `the fixed tree keeps the NavHost height and the bar at the bottom` |
| 桌面形态 `DesktopTabStrip` | 未改一行；仍不挂 `MobileDrawerMount`；**不传 label** | `the NavHost weight, the bar and the desktop form are untouched`、`the entry is an announced node…`（`assertFalse(desktop.contains("label ="))`） |
| close-then-act 顺序（B-6 / B-20 / 设置 / Help / Home） | 6 条全部逐字钉住 | `the sheet is handed to the host…`（新增 6 条 for 循环断言） |
| `DialogScrim` / `dialogCardBounds` / 遮罩 | 未动 | `the sheet's fillMaxSize host is unchanged…` |
| nav 恢复 `navRestoreSettled` | **未触碰** | `the v97 route-restore gate is not disturbed…` + `LastRouteRestoreV97Test` 15 例 |
| v49/v82 窗口 / inset 口径 | 未动（外层 `Box` 仍无 inset） | `the window and inset accounting is not disturbed` |
| 用户可见字符串 | **无新增**（复用 `oc_tab_session`） | `StringsLocaleParityTest` 3 例 |
| 既有断言强度 | **只增不减**：3 条钉旧形状的断言按新形状**重写并加强**（新增反向断言），新增 4 个用例 | §3.1 |

### 2.3 为什么不把「槽位点击」换成新按钮

任务允许新增入口，但本轮**不新增第二个入口**，理由：

1. **上游语义就是槽位触发**。`titlebar.tsx:472-519` 的 `MobileDrawerTrigger` 就是
   「当前 tab 的头像 + 标题 + tab 计数徽标」并**打开** `MobileTabsDrawer`；
   `BottomTabStrip.kt:234-236` 的注释已声明这个对应关系。底部条**本来就没有**
   「点槽位切会话」的语义（§1.1 已证），所以槽位与抽屉不冲突 —— 它是**正确**的入口设计。
2. 加第二个入口会**掩盖**而不是修复缺陷：真机 12 次失败（含正向对照也失效）恰恰是因为
   入口只有一个、且失败时与「无入口」不可区分。修好状态链路后单入口即可用；
   再加一个按钮会让「哪个入口坏了」在下轮又变成新的不可区分项。
3. 本轮改为**给既有入口补上 a11y 名称**（复用 `oc_tab_session`，与 `TabIconButton`
   `:542` 完全同一写法）。这是「既有语义的重用、不新增用户可见字符串」的字面做法：
   它不新增节点、不新增字符串、不改几何，却让**任何** dump 驱动的点击都能可靠解析出目标 ——
   这正是第四轮 §2.0 测量陷阱的直接解药。

---

## 3. 测试证据

### 3.1 更新的 JVM pin

`app/src/test/java/com/xiufeigo/opencodeshell/MobileDrawerLayoutV97Test.kt`（纯 JVM；
本仓库 test 依赖仅 junit / org.json / mockwebserver，**未引入 compose / robolectric**）。
**13 例 → 17 例，0 失败。**

**(a) 钉住「入口 → 打开状态」的连接**（本轮核心，全部新增）：

- **新** `the entry writes the very state the host gate reads` — 钉**同一性**：
  `onClick = { drawerMount.open = true }`（入口写）、`if (mobileDrawerMount.open && …)`（宿主读）、
  `remember { MobileDrawerMount() }` 的下标 < `drawerMount = mobileDrawerMount,` 的下标 <
  门控下标（同一实例一路传下去），且 `App.kt` 中 `MobileDrawerMount()` **恰好出现 1 次**。
- **新** `the old two-object hand-off could swallow the open and the new one cannot` —
  纯函数模型 `openAfterTap(copyOnPublish, framesAfterTap)` 把缺陷写成算术：
  fix2 形状在 `framesAfterTap = 0` 时**丢掉 open**（屏幕上什么都不画），
  `= 1` 时才看到；fix3 形状在 `0` 与 `3` 下**都立即看到**。
  另加两条**反向**源码断言：不得再声明 `var drawerOpen`、不得再有 `SideEffect { … drawerMount.open … }`。
- **新** `the entry exists exactly while a session is on screen` —
  纯谓词 `hasDrawerEntry(bottomTitlebar, session) = bottomTitlebar && session != null`，
  三个象限各断言一次（会话态有入口 / 无条无入口 / 无会话无槽位），并回钉源码
  `App.kt:1992 if (bottomTitlebar) {` 与 `BottomTabStrip.kt:256 if (session != null) {`。
- **新** `the entry is an announced node so a tap can be aimed at it` — 钉标签挂载
  （`label: String? = null`、`.semantics { contentDescription = label }`、`label = sessionLabel`），
  并钉**它没有变成第二个入口**：`drawerMount.open = true` 在 `strip` 中**恰好 1 处**，
  且桌面条不含 `label =`。

**(b) 既有断言按新形状重写并加强**（不删一条）：

- `the sheet is handed to the host…`：原 `assertTrue("…drawerMount.open = drawerOpen")`
  → 换成 **3 条更强断言**（无局部 `var drawerOpen` / 无 `SideEffect` 搬运 / 抽屉读
  `open = drawerMount.open,`），并把 1 条 close-then-act 断言扩成 **6 条**。
- `the sheet's fillMaxSize host is unchanged…`：触发点断言改为
  `onClick = { drawerMount.open = true }`（断言意图不变，仍钉「入口未被改掉」）。
- `the mount is cleared when the strip leaves composition`：`DisposableEffect` 三条原样保留。
- 布局 / 桌面形态 / `navRestoreSettled` / inset 四组原样保留。

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
  --tests "com.xiufeigo.opencodeshell.MobileTitlebarRowTest" `
  --tests "com.xiufeigo.opencodeshell.PaletteEntryPointsTest" `
  --tests "com.xiufeigo.opencodeshell.DialogScrimResidueTest" `
  --tests "com.xiufeigo.opencodeshell.DialogExitResidueTest" `
  --tests "com.xiufeigo.opencodeshell.HomeTitleInputV40Test" `
  --tests "com.xiufeigo.opencodeshell.OcKeyboardOptionsTest" `
  --tests "com.xiufeigo.opencodeshell.LastRouteRestoreV97Test" `
  --tests "com.xiufeigo.opencodeshell.DropdownMenuDarkSurfaceTest" `
  --tests "com.xiufeigo.opencodeshell.SessionPaneShortWindowV55Test"
```

BUILD 末三行原文：

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 9s
52 actionable tasks: 5 executed, 47 up-to-date
```

用例数（`app/build/test-results/testDebugUnitTest/TEST-*.xml` 汇总）：

| 测试类 | 用例 | 失败 | 错误 |
|---|---:|---:|---:|
| **MobileDrawerLayoutV97Test（本轮更新 13→17）** | **17** | 0 | 0 |
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
| PaletteEntryPointsTest | 11 | 0 | 0 |
| DialogScrimResidueTest | 6 | 0 | 0 |
| DialogExitResidueTest | 7 | 0 | 0 |
| HomeTitleInputV40Test | 9 | 0 | 0 |
| OcKeyboardOptionsTest | 10 | 0 | 0 |
| LastRouteRestoreV97Test | 15 | 0 | 0 |
| DropdownMenuDarkSurfaceTest | 11 | 0 | 0 |
| SessionPaneShortWindowV55Test | 14 | 0 | 0 |
| **合计** | **180** | **0** | **0** |

> 选类口径：任务点名的 3 类 + `StringsLocaleParityTest`，再按 `app/src/test` 中引用
> `ui/App.kt` 布局 / `BottomTabStrip` / `MobileTabsDrawer` / `MobileDrawerMount`
> 的 pin 类取全集。fix2 因纪律未纳入的 8 类（`PaletteEntryPointsTest`、
> `DialogScrimResidueTest`、`DialogExitResidueTest`、`HomeTitleInputV40Test`、
> `OcKeyboardOptionsTest`、`LastRouteRestoreV97Test`、`DropdownMenuDarkSurfaceTest`、
> `SessionPaneShortWindowV55Test`）**本轮全部补跑**。
>
> 编译与用例**一次通过**，无返工（fix2 曾有两次编译期失败）。
> gradle 锁：跑前确认 `.agent-gradle-lock` 不存在，取锁写入、跑完立即删除（已确认删除）。

---

## 4. 真机复验步骤（给测试员，可复现）

前置：设备 `emulator-5560`（AVD `Pixel_Tablet`），包 `com.xiufeigo.opencodeshell`，
`adb reverse tcp:4095` / `tcp:2222`。
**本轮纪律禁止装机，需用本轮构建重新出包。**

> **本轮最重要的操作变化**：入口现在**有 content-desc**（`oc_tab_session` 的本地化值）。
> **不要再写死 x 坐标** —— 用带 label 的节点从 pre-dump 解析 bounds 再取中心。
> 这直接消除了 `REPORT_DRAWER4.md` §2.0 那个「同一实验里按钮 x 三次漂移」的测量陷阱。

| # | 步骤 | 期望 |
|---|---|---|
| **①** | 默认形态：`wm size/density reset` + 标签布局=**水平** | 正常。语义节点数 ≈ 153；桌面条 `y=36..132`；项目/会话列表齐全 |
| **②** | `appearance.tabLayout=vertical`（**写后读回复核**）+ `wm size 1200x1600` + `wm density 320` → 冷启动 → settle ≥8s | 冷启动 Home **89 节点**、底部条 `y=1452..1548`（与 fix2 读数逐项一致 ⇒ 空壳回归未重现） |
| **③** | **开会话** → 收键盘 → 冷启动还原 → 从 pre-dump 解析**带 label 的可点节点**（`SessionTab`）的 bounds，取中心 tap | **断言抽屉打开**：出现新覆盖层容器 + 抽屉内容行（设置 / Help / 会话行）。**判据任一命中即算打开**：① 新覆盖层容器；② 抽屉内容行；③ 抽屉行的 `data-slot="mobile-drawer-*"` 语义；④ 关闭态不可见的会话行出现 |
| **④** | 打开态 dump，**读底部标签条 y** | **仍在底部 `y≈1452..1548`**。缺陷签名是 `0..84`，出现即回归 |
| **⑤** | 打开态读 NavHost / 正文 | 正文高度与关闭态一致（**未被压零**）；`stateBar`（状态栏）**未被遮挡** |
| **⑥** | 关闭态 vs 打开态的条 bounds | 两次 dump 的条 bounds **逐字相同**（不要只看截图，动画过渡帧会误判） |
| **⑦** | 点 scrim（非抽屉卡片区域） | 抽屉关闭；关闭态条 y 与 ⑥ 的关闭态一致 |
| **⑧** | 抽屉内交互（切 tab / 重命名 / 设置 / Help） | 正常（`MobileTabsDrawer` 本轮未改一行，这些能力此前从未被验到过） |
| **⑨** | 收尾还原 | `wm size reset` + `wm density reset`（读回 `Physical size: 2560x1600`、无 Override 行）；`appearance.tabLayout` 回 **horizontal**（`oc_settings.xml` 复核）；确认 ① 恢复 |

命令：

```bash
adb -s emulator-5560 shell wm size 1200x1600
adb -s emulator-5560 shell wm density 320
adb -s emulator-5560 shell am force-stop com.xiufeigo.opencodeshell
adb -s emulator-5560 shell monkey -p com.xiufeigo.opencodeshell -c android.intent.category.LAUNCHER 1
# settle ≥8s → uiautomator dump（**必须 dump 全节点**，含无文本节点）
adb -s emulator-5560 shell uiautomator dump /sdcard/w.xml && adb -s emulator-5560 pull /sdcard/w.xml
# 解析带 content-desc 的 SessionTab 节点 → 取中心 tap（不要写死 x）
adb -s emulator-5560 shell input tap <cx> <cy>
```

**判读要点**

1. **③ 是本轮的核心断言**。若仍是 `diff=0`（无覆盖层、无抽屉行、条几何不变），
   说明状态链路仍断 —— **请在 tap 前后各留一次全屏 dump 与截图**，并同时点一次
   **同一条上的 `命令面板` 按钮**重建正向对照（第四轮的对照失效了，`REPORT_DRAWER4.md` §4）。
2. ④ 若条回到 `y=0..84` ⇒ 空壳回归被重新引入（本轮无布局改动，理论上不可能；
   若出现请连同 dump 一起回单）。
3. 若 ③ 仍失败，**新增一条判定步骤**：确认 tap 的 pre-dump 里那个带 label 的节点
   **bounds 非零**。若它为 0×0，则问题在命中测试而非状态链路。
4. ⑤ 的 NavHost 高度读数建议与 ② 的冷启动 Home 读数同口径比较。

---

## 5. 未闭环

| 项 | 状态 | 说明 |
|---|---|---|
| **真机验证「点会话槽位能打开抽屉」** | ❌ **未闭环** | 本轮纪律禁止装机/真机。§4 步骤 ③ 是本轮唯一需要的核心复验 |
| 打开态全部断言（条 y / NavHost 未压零 / 状态栏 / 开关一致性 / scrim 关闭 / 切 tab / 重命名 / 设置 / Help） | 未验到 | 打开态仍不可达则继续未验到；§4 ③④⑤⑥⑦⑧ 逐条对应 |
| **第四轮遗留：整条标签条是否无响应** | ⚠️ 未定论 | 第四轮 `命令面板` 两次也点不开（同条上），`首页` 可响应。本轮**未改** `onOpenPalette` / `onOpenHome` 路径，**若 §4 ③ 仍失败，请优先重建「同条按钮能响应」的对照**再下结论 |
| `Home` 页（未开会话）无抽屉入口 | 维持现状 | `App.kt:1992` ∧ `BottomTabStrip.kt:256` ⇒ 该形态下确无入口。纯谓词已 pin，但**真机未穷举验证**。属设计取舍（上游 `MobileDrawerTrigger` 也是会话态才存在），未改 |
| 2 个及以上 tab 时抽屉是否可打开 | 未测 | 第四轮遗留，本轮未覆盖（代码路径与 1 个 tab 相同） |
| 空壳态 ~45s 交互延迟 | 未定性 | 本轮无布局改动，未复现、未做重负载压测 |
| `App.kt` 未改 | 说明 | 宿主门控 `App.kt:2041` 读的 `mobileDrawerMount.open` 现已成为权威状态源，宿主无需改动即正确。这是有意的：**覆盖层结构与 `if (open)` 门控是 fix2 已验证过的部分，本轮不碰。** |

---

## 6. 回报

**状态：✅ 修复完成，JVM 180 例全绿（pin 13→17），真机复验待下一轮。**

**「槽位点不开」的真因**：打开状态在 fix2 里是**两个对象**——点击写的是
`BottomTabStrip.kt:205` 的条内局部 `drawerOpen`，而宿主门控 `App.kt:2041` 读的是
`MobileDrawerMount.open`；两者之间**唯一**的搬运是 `BottomTabStrip.kt:313-316` 的
`SideEffect` —— 一个**在组合与 apply 之后**发生的写入。于是点击到屏幕要跨
「使条失效 → 条重组 → 组合后搬运 → 使祖先失效 → 第二帧重算门控」**两次失效、一次延后跳跃**；
**该跳跃若没产出后续帧，点击就完全没有可观测落点**——没有 scrim、没有抽屉行、a11y 逐行相同、
0 崩溃，与 `REPORT_DRAWER4.md` §2.2 的 7 次 `diff=0` 完全吻合。次生问题：入口是全 App 唯一入口
且**无 content-desc**，真机无从解析点击目标（该节点只有 `if (session != null)` 成立时存在）。

**改了哪些文件**（2 个，均在写权限内）：
- `app/src/main/java/com/xiufeigo/opencodeshell/ui/nav/BottomTabStrip.kt` — 删除条内 `drawerOpen`；
  入口改为直接写 `drawerMount.open`；抽屉内容读 `open = drawerMount.open`；6 条 close-then-act
  改为写同一 state；`SideEffect` 降为**只**发布 `content`；`SessionTab` 新增默认 null 的
  `label` 参数 + 条件化 `semantics { contentDescription = label }`，底部条传 `label = sessionLabel`。
- `app/src/test/java/com/xiufeigo/opencodeshell/MobileDrawerLayoutV97Test.kt` — 13 → 17 例。
- **`ui/App.kt` 本轮零改动**（宿主结构原样；`navRestoreSettled` 未触碰）。

**命令与用例数**：
`.\gradlew.bat --console=plain :app:testDebugUnitTest` + 20 个 `--tests`（§3.2 全表）→
`BUILD SUCCESSFUL in 9s` / `52 actionable tasks: 5 executed, 47 up-to-date` →
**180 例，0 失败，0 错误**（`MobileDrawerLayoutV97Test` 17 例 0 失败；一次通过，无返工）。

**报告路径**：`research\v97_fix\MOBILE_DRAWER_ENTRY_FIX.md`
