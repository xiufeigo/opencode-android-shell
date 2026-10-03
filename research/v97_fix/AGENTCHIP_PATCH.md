# AgentChip `/agent` 重建即重弹 —— 最小修复方案 + 单测 pin（v97 P2）

> 只读诊断产出。**未修改 `app/` 下任何源码**，未运行任何 gradle 命令。
> 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
>
> **两处与移交单不符的取证更正（先看这两条，路径/依赖都变了）：**
>
> 1. **文件不在 `ui/components/`**。真实路径是
>    `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/AgentChip.kt`
>    （`ModelChip.kt` 同目录）。移交单给的 `ui/components/AgentChip.kt` **不存在**
>    （`read` 直接 `not found`；`glob **/AgentChip.kt` 只命中 composer 一处）。
>    缺陷行号 `143-145` 与移交单**完全一致**（`LaunchedEffect(openRequest)` 在 144，
>    `if (openRequest > 0) openMenu()` 在 145，闭合在 146），缺陷定性成立。
> 2. **`AgentChipFocusRestoreV97Test` 不使用任何 compose 测试依赖**。它的 import 只有
>    `org.junit.*` + `java.io.File`（`AgentChipFocusRestoreV97Test.kt:1-9`），
>    `app/build.gradle.kts:122-124` 的 test 依赖只有 `junit:junit:4.13.2` /
>    `org.json:json:20240303` / `okhttp3:mockwebserver:4.12.0` —— **无 `ui-test-junit4`、
>    无 robolectric**。所以本仓库的「pin 写法」是 **纯 JVM**：一条 `internal` 纯函数
>    断言 + 对源码文本的 `contains` 断言。下面的测试方案照此形状写，不引入新依赖。

---

## 现状代码（AgentChip.kt 逐行引用）

文件：`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/AgentChip.kt`（共 341 行）

### 1. 签名与 `openRequest` 参数

```kotlin
 82: @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
 83: @Composable
 84: fun AgentChip(
 85:     current: String?,
 86:     agents: List<OpenCodeClient.Agent>,
 87:     overrideActive: Boolean,
 88:     onSelect: (String) -> Unit,
 89:     onUseDefault: () -> Unit,
 90:     modifier: Modifier = Modifier,
 91:     /** `/agent` from the command menu opens this menu (see [ModelChip.openRequest]). */
 92:     openRequest: Int = 0,
 93:     /**
 ...
 98:     visible: Boolean = true,
 ...
107:     onRestoreFocus: (() -> Unit)? = null,
108: ) {
```

- `openRequest: Int = 0` 在 **`AgentChip.kt:92`**，KDoc（`:91`）明确它就是 `/agent`
  命令菜单的打开请求，并指向 `[ModelChip.openRequest]`。
- 注意 KDoc **只写了「见 ModelChip.openRequest」，没跟上 v75 P2 的消费语义**
  （对照 `ModelChip.kt:120-128` 那一大段 v75 P2 说明）—— 这就是 v74 形状在
  AgentChip 侧**整体没被搬过去**的根因。

### 2. 状态变量与 `openMenu()` / `closeMenu()` 的真实写法

```kotlin
109:     val c = OcTheme.colors
110:     val options = remember(agents) { OpenCodeClient.Agent.selectable(agents) }
111:     if (!visible || options.isEmpty()) return
112:     var expanded by remember { mutableStateOf(false) }
113:     // v97（v57 移交项）：关闭原因。弹层内容卸载的那一刻才知道该不该把焦点还
114:     // 回去 —— `null` = 这次卸载**不是**一次关闭（组件被重建而卸载：形态翻转、
115:     // 会话切换、控件结构变化）。照 v75 已定语义，那种卸载**不**归还：归还会把
116:     // 键盘重新拉起来，键盘一起布局又翻回去、组件再被重建，来回震荡，弹层永远
117:     // 来不及显示（`ModelChip` 的 P1 注释在案）。
118:     var closeReason by remember { mutableStateOf<OcPopoverCloseReason?>(null) }
119:     val closeMenu: (OcPopoverCloseReason) -> Unit = { reason ->
120:         closeReason = reason
121:         expanded = false
122:     }
123:     // 打开之前键盘在不在：归还焦点后只有原本可见时才把键盘叫回来（原本没有就
124:     // 不多事），这一条决定了下一次打开时量到的 composer 位置和这一次相同。
125:     val imeVisibleNow = WindowInsets.isImeVisible
126:     val keyboardController = LocalSoftwareKeyboardController.current
127:     var imeVisibleAtOpen by remember { mutableStateOf(false) }
128:     val openMenu: () -> Unit = {
129:         imeVisibleAtOpen = imeVisibleNow
130:         // 清掉上一轮的关闭原因：归还判定读的是**卸载那一刻**的原因，留着上一
131:         // 轮的值会让这一次的「重建卸载」被误判成一次关闭（`closeMenu` 与
132:         // `expanded = false` 同帧生效，弹层内容不会再多组合一次）。
133:         closeReason = null
134:         expanded = true
135:     }
```

状态变量名（逐字）：`expanded`（`:112`）、`closeReason`（`:118`）、`imeVisibleAtOpen`（`:127`）、
`anchor`（`:136`）、`popoverDismiss`（`:149`）。
动作：`closeMenu: (OcPopoverCloseReason) -> Unit`（`:119`）、`openMenu: () -> Unit`（`:128`）。

**`:111` 的早退是关键结构**：`visible == false` 或 agent 列表为空时整个 composable
`return`，`:112` 之后的所有 `remember` / `LaunchedEffect` 都不存在。

### 3. 缺陷本体（v74 形状）

```kotlin
142:     val chooseLabel = stringResource(R.string.oc_composer_choose_agent)
143: 
144:     LaunchedEffect(openRequest) {
145:         if (openRequest > 0) openMenu()
146:     }
```

`LaunchedEffect(openRequest)` 在**每次挂载**都会以首个 key 启动一次（`openRequest > 0`
恒真），而 `expanded`（`:112`）是纯 `remember`、重建即丢。合起来 = **重建即凭空重弹**。

### 4. `onDismissRequest` / ESC / 选中的三条关闭路径

```kotlin
207:                 onDismissRequest = { closeMenu(OcPopoverCloseReason.OUTSIDE) },
...
219:                     modifier = Modifier.ocPopoverDismiss(popoverDismiss) { closeMenu(OcPopoverCloseReason.KEY) },
...
250:                                         closeMenu(OcPopoverCloseReason.PICK)
251:                                         onSelect(agent.name)
...
267:                                         closeMenu(OcPopoverCloseReason.PICK)
268:                                         onUseDefault()
```

chip **自身**的点击（不走宿主计数器，纯本地写）：

```kotlin
158:                 .clickable(role = Role.Button) { openMenu() }
```

> 与 `ModelChip` 的**结构性差异**：`ModelChip` 有 `onRequestOpen`（`ModelChip.kt:151`），
> chip 自己那一下会同时抬起宿主计数器（`ModelChip.kt:139-149` 的 v74 P0 KDoc、
> `ComposerShortWindowV74Test.kt:231-235` 钉住）；**`AgentChip` 没有这个参数**，
> 也没有 KDoc 记录这个缺席。见 §回归风险 R5。

### 5. 调用点全清单（`AgentChip(` 全仓只有 1 处产品调用）

```kotlin
// app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt
1726:                     if (!shellMode) {
1727:                         AgentChip(
1728:                             current = selectedAgent,
1729:                             agents = agents,
1730:                             overrideActive = agentOverrideActive,
1731:                             onSelect = onSelectAgent,
1732:                             onUseDefault = onUseDefaultAgent,
1733:                             openRequest = agentOpenRequest,
1734:                             visible = agentPickerVisible,
1739:                             onRestoreFocus = { runCatching { focusRequester.requestFocus() } },
1740:                         )
1741:                         if (agentPickerVisible) Spacer(Modifier.width(4.dp))
1742:                         ModelChip(
1752:                             openRequest = modelOpenRequest,
```

**计数器的宿主侧**（`Composer.kt`）：

```kotlin
465:     // `/model` and `/agent` open the chips' menus: a bumped counter each.
466:     var modelOpenRequest by remember { mutableStateOf(0) }
467:     var agentOpenRequest by remember { mutableStateOf(0) }
```

```kotlin
// Composer.kt — 唯一的递增点，在 slash 面板的 built-in 分支
1043:                 when (item.label.removePrefix("/")) {
1044:                     ComposerSuggestions.BUILT_IN_MODEL -> modelOpenRequest += 1
1045:                     ComposerSuggestions.BUILT_IN_AGENT -> agentOpenRequest += 1
```

- 递增点**有且仅有一处**（全仓 `agentOpenRequest` 只出现在 `Composer.kt:467` / `:1045` / `:1733`）。
- `Composer.kt:467` 是**纯 `remember`，不是 `rememberSaveable`** → 单向闩，永不回落，
  会话切换（`Composer.kt:455` 的 `key = ComposerDrafts.saveableSlot(draftKey, …)`）时整个
  composer 重挂 → 计数器一并归零。
- `/agent` 这个 slash 行**不受 `agentPickerVisible` 过滤**：`ComposerSuggestions.kt:294-303`
  无条件 `add(...)`，函数签名里根本没有 visibility 入参。

---

## 参照实现（ModelChip 修法逐行引用）

文件：`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelChip.kt`（共 656 行）

### 1. 消费代码骨架（`ModelChip.kt:220-231`）

```kotlin
220:     // `/model` from the command menu: open the picker on the next composition.
221:     //
222:     // v75 P2: 只有**新**请求才开（[modelOpenRequestArrived]）。宿主那条计数器是
223:     // 单向闩、永不回落，v74 让 chip 在每次挂载时重读它 —— 于是用户点过一次之后，
224:     // 此后任何重建都会再弹一次面板：点 composer 文本区（焦点一落，键盘与布局跟着
225:     // 变，composer 重建）时模型选择器"凭空"出现。这里记住本实例已经见过的值，
226:     // 重建后的实例把当前值当作已见过。
227:     var seenRequest by remember { mutableStateOf(openRequest) }
228:     LaunchedEffect(openRequest) {
229:         if (modelOpenRequestArrived(seenRequest, openRequest)) openPicker()
230:         seenRequest = openRequest
231:     }
```

**它凭什么做到「重建不重弹」—— 三条机制，缺一不可：**

| # | 机制 | 代码 | 为什么够 |
|---|---|---|---|
| M1 | `remember`（**无 key**）的初值只在**首次组合**求值 | `:227` `remember { mutableStateOf(openRequest) }` | 重建出的新实例，初值 = 宿主此刻闩着的值，**当场当成「已消费」**。这是全部关键：不是「记住上次值」而是「**挂载即已见**」。 |
| M2 | 纯函数把「到达」定义成 `current > 0 && current != seen` | `ModelChip.kt:602-603` | `current == seen`（重建/未变）⇒ 不开；`current > 0 && != seen`（`/model` 或 chip 自己那一下抬起计数器）⇒ 开。**闩回落（`current == 0`）也不算请求**，防止宿主复位造成误开。 |
| M3 | 效果体**无条件**回写 `seenRequest` | `:230` | 即使这一帧没开（比如闩是 0），也把 `seen` 推到 `current`，避免下一次同值比较重复触发。 |

> M1 成立的前提是**这个 remember 活过了重建**——而重建时 chip 的 `remember` 全部重建，
> 所以 M1 的实际语义是「每个新实例都把挂载值当已消费」。配合 M2 的 `!=`，
> 效果就是：**只有挂载之后闩再被抬高，才开**。

### 2. 纯函数本体（`ModelChip.kt:587-603`）

```kotlin
587: /**
588:  * v75 P2: whether [current] is a request this chip instance has not seen yet.
589:  * The host's counter (`externalModelOpen`) is a one-way latch that never falls
590:  * back, and v74 let the chip re-read it on **every** mount — so once the user had
591:  * tapped the chip, any later rebuild of the composer re-opened the picker out of
592:  * nowhere (a tap on the composer's text area moves the keyboard and the layout,
593:  * which is exactly a rebuild). A rebuilt instance now treats the value it mounts
594:  * with as already seen, and only a genuinely **new** value opens the panel; the
595:  * v74 guarantee ("a composer rebuilt under the open picker keeps it") is carried
596:  * by `Composer`'s movable tool-controls slot instead — a form flip **moves** the
597:  * chip, so its popup is never unmounted at all.
598:  *
599:  * @param seen the value this instance has already seen (its mount value).
600:  * @param current the value the host publishes now.
601:  */
602: internal fun modelOpenRequestArrived(seen: Int, current: Int): Boolean =
603:     current > 0 && current != seen
```

- `internal`（不是 `private`）→ **同模块 JVM 单测可直接 import**
  （`ComposerModelChipV75Test.kt:7` 就是这么 import 的）。
- 纯函数、无 Compose 依赖 → **不需要 `ui-test`，不需要 robolectric**。

### 3. 既有 pin 的写法（照抄模板）

`ComposerModelChipV75Test.kt:111-127`：

```kotlin
111:     @Test
112:     fun onlyANewRequestOpensThePanel() {
113:         // The value a rebuilt instance mounts with is what the host's latch
114:         // already held — that is not a request.
115:         assertFalse(modelOpenRequestArrived(seen = 1, current = 1))
116:         assertFalse(modelOpenRequestArrived(seen = 0, current = 0))
117:         // A host that falls back to zero is not raising a request either.
118:         assertFalse(modelOpenRequestArrived(seen = 4, current = 0))
119:         // `/model` and the chip's own tap both land as a **new** value.
120:         assertTrue(modelOpenRequestArrived(seen = 0, current = 1))
121:         assertTrue(modelOpenRequestArrived(seen = 1, current = 2))
122:         // …and the chip's effect is the only consumer of the host counter.
123:         val chip = flat(modelChip)
124:         assertTrue(chip.contains("var seenRequest by remember { mutableStateOf(openRequest) }"))
125:         assertTrue(chip.contains("if (modelOpenRequestArrived(seenRequest, openRequest)) openPicker()"))
126:         assertTrue(chip.contains("seenRequest = openRequest"))
127:     }
```

`ComposerShortWindowV74Test.kt:246-253`（整块压平的写法）：

```kotlin
246:         assertTrue(
247:             modelChip.replace(Regex("\\s+"), " ").contains(
248:                 "var seenRequest by remember { mutableStateOf(openRequest) } " +
249:                     "LaunchedEffect(openRequest) { " +
250:                     "if (modelOpenRequestArrived(seenRequest, openRequest)) openPicker() " +
251:                     "seenRequest = openRequest }",
252:             ),
253:         )
```

> ⚠️ **这条更强的压平断言不能照搬到 AgentChip**，这是本次诊断里最容易踩的一个坑：
> 它要求 `seenRequest` 声明与 `LaunchedEffect` **相邻、中间无注释**。
> `ModelChip.kt` 恰好做到了（注释全在 `:220-226`，代码 `:227-231` 连续）。
> 而 `AgentChip.kt` 的 `openRequest` 效果在 `:144`、其余状态（`expanded` `:112`、
> `closeReason` `:118`、`closeMenu` `:119-122`、`openMenu` `:128-135`、`anchor` `:136`、
> `label` `:138`、`chooseLabel` `:142`）全部夹在中间 —— 压平之后那四行**不连着**，
> 断言必红。
> 因此 `AgentChip` 的 pin 改用 `ComposerModelChipV75Test.kt:123-126` 的**逐条**
> `contains`（声明一条、效果体一条、v74 形状消失一条），语义等价且不受排版影响。

### 4. `remember` vs `rememberSaveable`（`ModelChip` 的选择与理由）

`ModelChip.kt:227` 用的是 `remember`，**不是** `rememberSaveable`。
把这一条当成决策依据（在本仓库内是硬约束，不是风格偏好）：

| 判据 | 证据 | 指向 |
|---|---|---|
| 要保护的东西本身只活 `remember` | `ModelChip.kt:180` `var expanded by remember { mutableStateOf(false) }`；`AgentChip.kt:112` 同款 | 弹层开着与否只 `remember` 作用域。`seenRequest` 跨进程存活**保护不了任何东西** —— 进程回来时 `expanded` 也是 false，弹层早没了 |
| 宿主计数器不是 saveable | `Composer.kt:466-467` 两个计数器都是纯 `remember` | 进程重建后宿主闩**归零**，而 saveable 的 `seen` 会还原成旧值（如 3）→ 状态**不对称**：下次 `/agent` 把闩抬到 1，`1 != 3` 虽仍会开，但中间任何 `current == seen` 的偶合都会误吞一次请求 |
| 配置变更同理 | 旋转/深色切换重建 Activity → `remember` 与计数器**同时**归零，语义自洽 | `rememberSaveable` 会让 `seen` 单独活下来，破坏自洽 |
| 会话切换 | `Composer.kt:455` `key = ComposerDrafts.saveableSlot(draftKey, …)` 整体重挂 | `remember` 干净地跟着实例走 |

**结论：用 `remember { mutableStateOf(openRequest) }`（无 key）**，与 `ModelChip.kt:227`
逐字一致。`rememberSaveable` 在这里**只带来不对称状态，没有收益**。

---

## 最小补丁（unified diff 原文，可直接 apply）

目标文件：`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/AgentChip.kt`
（341 行 → 374 行；`app/` 下**只改这一个文件**，`Composer.kt` 不用动）

```diff
--- a/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/AgentChip.kt
+++ b/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/AgentChip.kt
@@ -108,5 +108,16 @@
 ) {
     val c = OcTheme.colors
     val options = remember(agents) { OpenCodeClient.Agent.selectable(agents) }
+    // v97 P2（照 `ModelChip` 的 v75 P2 修法）：本实例**已经消费过**的 `/agent` 请求
+    // 计数。宿主那条 `agentOpenRequest`（`Composer.kt:467`）是单向闩、永不回落，
+    // v74 却让 chip 在**每次挂载**时重读它 —— 用户敲过一次 `/agent` 之后，此后
+    // 任何重建（点 composer 文本区让键盘与布局跟着变、进出 shell 模式
+    // `Composer.kt:1726`、会话切换）都会「凭空」再弹一次 agent 菜单。重建出来的
+    // 实例把挂载时那个值当作已见过，只有真正**新**到的值才开
+    // （`agentOpenRequestArrived`）。
+    // 刻意放在下一行早退的**之上**：chip 不可见 / agent 列表还没到时这个 composable
+    // 整段 return（`agentPickerVisible` 的初值就是 false，`ChatViewModel.kt:581`），
+    // 闩仍然活着 —— 那之前敲的 `/agent` 在 chip 可见之后照样开，正路径不破。
+    var seenRequest by remember { mutableStateOf(openRequest) }
     if (!visible || options.isEmpty()) return
     var expanded by remember { mutableStateOf(false) }
@@ -142,6 +153,12 @@
     val chooseLabel = stringResource(R.string.oc_composer_choose_agent)
 
-    LaunchedEffect(openRequest) {
-        if (openRequest > 0) openMenu()
-    }
+    // `/agent` from the command menu: open the menu on the next composition.
+    //
+    // v97 P2: 只有**新**请求才开（[agentOpenRequestArrived]）。与
+    // [modelOpenRequestArrived]（v75 P2）同款：重建出的实例把挂载时那个值当作
+    // 已见过，所以「重建」不再等于「再弹一次」。
+    LaunchedEffect(openRequest) {
+        if (agentOpenRequestArrived(seenRequest, openRequest)) openMenu()
+        seenRequest = openRequest
+    }
     // v49: ESC 关闭 + 外点穿透，宿主窗口在 Popup 之外取（见 `OcPopoverDismiss`）。
     val hostView = LocalView.current
     val popoverDismiss = remember { OcPopoverDismiss(hostView) }
@@ -274,6 +291,22 @@
         }
     }
 }
 
+/**
+ * v97 P2: whether [current] is a request this chip instance has not seen yet.
+ * The host's counter (`agentOpenRequest`, `Composer.kt:467`) is a one-way latch
+ * that never falls back, and v74 let the chip re-read it on **every** mount — so
+ * once the user had typed `/agent`, any later rebuild of the composer re-opened
+ * the menu out of nowhere (a tap on the composer's text area moves the keyboard
+ * and the layout, which is exactly a rebuild). A rebuilt instance now treats the
+ * value it mounts with as already seen, and only a genuinely **new** value opens
+ * the menu — the same shape as [modelOpenRequestArrived] (v75 P2).
+ *
+ * @param seen the value this instance has already seen (its mount value).
+ * @param current the value the host publishes now.
+ */
+internal fun agentOpenRequestArrived(seen: Int, current: Int): Boolean =
+    current > 0 && current != seen
+
 /**
  * `[data-slot=menu-v2-item]` — name + muted description, check on the active row.
  */
 @Composable
```

### 补丁自检（`git apply` 前可自查的算术）

| hunk | 头 | 原行数 | +行 | −行 | 新行数 | 新起始行 |
|---|---|---|---|---|---|---|
| 1 | `@@ -108,5 +108,16 @@` | 5（`:108-112`） | 11（10 注释 + 1 声明） | 0 | 16 | 108 |
| 2 | `@@ -142,6 +153,12 @@` | 6（`:142-147`） | 9（5 注释 + 4 代码） | 3 | 12 | 142+11 = **153** ✔ |
| 3 | `@@ -274,6 +291,22 @@` | 6（`:274-279`） | 16（1 空 + 12 KDoc + 2 函数 + 1 空） | 0 | 22 | 274+11+6 = **291** ✔ |

合计 `+36 / −3`，341 → **374** 行。

### 补丁要点逐条对照

| 要求 | 落点 | 满足情况 |
|---|---|---|
| 记住上一次已消费的请求计数 | hunk 1，`var seenRequest by remember { mutableStateOf(openRequest) }` | ✔ 无 key `remember`，理由见 §参照实现 4 |
| 计数**变化**时仍能正常打开（正路径不破） | hunk 2 `agentOpenRequestArrived(seen=0, current=1) = true` | ✔ 且因为闩放在早退之上，chip 不可见期间敲的 `/agent` 也会在可见后打开（v74 时代没有这个保证） |
| 重建不重弹 | hunk 1 + 2 | ✔ 新实例初值 = 挂载值 ⇒ `current == seen` ⇒ 不开 |
| 不引入新的用户可见字符串 | 全文 | ✔ 只加注释 + 一个 `internal` 纯函数；`R.string.*` 引用数不变（`AgentChip.kt:138` `oc_composer_agent`、`:142` `oc_composer_choose_agent`、`:263` `oc_composer_agent_default` 均未动） |
| 最小 | 1 个文件、`+36/−3` 行（341 → 374） | ✔ `Composer.kt` 一行不用改 |
| 排版对齐 `ModelChip.kt:220-231` | 注释全在代码块外 | ✔ 但**注意**：agent chip 的闩与效果之间隔着三十多行（`expanded`/`closeReason`/`openMenu`/`anchor`/`label`），所以测试**不能**照搬 `ComposerShortWindowV74Test.kt:246-253` 的整块压平断言，只能用 `ComposerModelChipV75Test.kt:123-126` 的逐条 `contains` |

### 已核对的既有 pin 兼容性（打补丁后不会红）

| 既有测试 | 断言位置 | 与本补丁的关系 |
|---|---|---|
| `AgentChipFocusRestoreV97Test.everyClosePathCarriesAReasonAndOpenClearsIt` | `AgentChipFocusRestoreV97Test.kt:78-79` 用 `substringAfter("val openMenu: () -> Unit = {").substringBefore("}")` 切出 openMenu 体 | hunk 1 落在 `:110-111` 之间、hunk 2 落在 `:144-146`，**都没碰 `:128-135` 的 openMenu 体** ⇒ 不受影响 |
| 同上 | `:51` `assertFalse("fun restoresFocusOnUnmount" in agentChip)` | 新函数名 `agentOpenRequestArrived`，不匹配 ⇒ 仍为 false ✔ |
| `OcPopoverDismissTest.everyPopoverMenuHostsTheDismissContract` | `OcPopoverDismissTest.kt:190-212` | 要求 `ocPopoverDismiss(popoverDismiss`（`:219`）、`flushPendingTap()`（`:150`）、marker 之后无 `Key.Escape` —— 三处均未动 ✔ |
| `Wave5ComposerWiringTest` | `:45` `agentOpenRequest += 1`、`:64` `AgentChip(` | 读 `Composer.kt`，本补丁不改它 ✔ |
| `ComposerModelChipV75Test` / `ComposerShortWindowV74Test` | 全部只读 `ModelChip.kt` / `Composer.kt` / `SessionScreen.kt` | 与 `AgentChip.kt` 无交集 ✔ |
| `AgentChipFocusRestoreV97Test.theComposerHandsTheEditorBackToTheChip` | `:102` `composer.substringAfter("AgentChip(").substringBefore("ModelChip(")` | 调用点未改 ✔ |

---

## 单测 pin 方案（类名/方法名/断言代码）

### 依赖前提（重要）

`app/build.gradle.kts:122-124` —— test 只有 `junit:junit:4.13.2` / `org.json` / `mockwebserver`。
**不引入新依赖**（加 `ui-test-junit4` + robolectric 是一次依赖面变更，超出「最小修复」，
且与本仓库既有 pin 风格不一致）。测试按 `AgentChipFocusRestoreV97Test.kt` /
`ComposerModelChipV75Test.kt` 的纯 JVM 形状写。

### 新增文件

`app/src/test/java/com/xiufeigo/opencodeshell/AgentChipOpenRequestV97Test.kt`

```kotlin
package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.agentOpenRequestArrived
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v97 P2：`/agent` 的打开请求必须**只被消费一次**。
 *
 * 取证（修复前）：`AgentChip.kt:144-145` 是
 * `LaunchedEffect(openRequest) { if (openRequest > 0) openMenu() }` —— 宿主那条
 * `agentOpenRequest`（`Composer.kt:467`）是单向闩、永不回落，这个 `LaunchedEffect`
 * 在**每次挂载**都以首 key 启动一次，而 `expanded`（`AgentChip.kt:112`）是纯
 * `remember`、重建即丢 ⇒ 用户敲过一次 `/agent` 之后，任何重建（点 composer 文本区
 * 让键盘与布局跟着变、`shellMode` 翻转、会话切换）都会「凭空」再弹一次 agent 菜单。
 *
 * 修法照 `ModelChip` 的 v75 P2（`ModelChip.kt:220-231` + `modelOpenRequestArrived`
 * `:602-603`）：记住本实例已经见过的值，只有**新**值才开。
 */
class AgentChipOpenRequestV97Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val agentChip =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/AgentChip.kt")

    private val composer =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    private fun flat(source: String): String = source.replace(Regex("\\s+"), " ")

    // ---- ① 请求变化 → 打开     ② 重建/重组 → 不重弹     ③ 再次变化 → 再打开

    @Test
    fun onlyANewRequestOpensTheMenu() {
        // ① `/agent` 敲进命令菜单 → 宿主把闩从 0 抬到 1（`Composer.kt:1045`）。
        assertTrue(agentOpenRequestArrived(seen = 0, current = 1))
        // ③ 菜单关掉后再敲一次 → 闩到 2，又是**新**值 → 再打开。
        assertTrue(agentOpenRequestArrived(seen = 1, current = 2))
        assertTrue(agentOpenRequestArrived(seen = 7, current = 8))

        // ② 重建/重组不重弹：新实例挂载时 `remember` 的初值就是宿主此刻闩着的值，
        // 于是「挂载值 vs 当前值」相等 —— 这不是一条请求。
        assertFalse(agentOpenRequestArrived(seen = 1, current = 1))
        assertFalse(agentOpenRequestArrived(seen = 3, current = 3))
        // 一次都没敲过（闩还是初值）。
        assertFalse(agentOpenRequestArrived(seen = 0, current = 0))
        // 宿主把闩复位到 0 不算请求（否则每次复位都会误弹）。
        assertFalse(agentOpenRequestArrived(seen = 4, current = 0))
    }

    @Test
    fun theChipRemembersTheRequestItHasSeen() {
        val chip = flat(agentChip)
        // 与 `ModelChip.kt:227-231` 同形，但**不能**用 `ComposerShortWindowV74Test.kt:246-253`
        // 那种「四行压平成一块」的断言：ModelChip 的闩与效果是紧邻的两行，而 agent chip
        // 中间隔着 `expanded` / `closeReason` / `openMenu` / `anchor` / `label` 三十多行，
        // 压平后不连着。改用 `ComposerModelChipV75Test.kt:123-126` 的逐条 `contains`。
        assertTrue(chip.contains("var seenRequest by remember { mutableStateOf(openRequest) }"))
        assertTrue(chip.contains("LaunchedEffect(openRequest) {"))
        assertTrue(
            chip.contains(
                "LaunchedEffect(openRequest) { " +
                    "if (agentOpenRequestArrived(seenRequest, openRequest)) openMenu() " +
                    "seenRequest = openRequest }",
            ),
        )
        // v74 形状必须已经不在了。
        assertFalse(
            "the v74 latch re-read must be gone",
            chip.contains("if (openRequest > 0) openMenu()"),
        )
        // 只有**这一个** LaunchedEffect 消费宿主计数器，别处不许再偷读一次。
        assertEquals(
            "one consumer only",
            1,
            Regex("""LaunchedEffect\(openRequest\)""").findAll(agentChip).count(),
        )
    }

    @Test
    fun theLatchOutlivesTheHiddenAndLoadingEarlyReturn() {
        val chip = flat(agentChip)
        // 闩必须声明在 `if (!visible || options.isEmpty()) return` **之上**：chip 不可见
        // （`agentPickerVisible` 初值就是 false，`ChatViewModel.kt:581`）或 agent 列表
        // 还没到时整个 composable 早退，放在下面就等于「不可见期间敲的 `/agent`
        // 永远开不出来」。
        val latch = chip.indexOf("var seenRequest by remember { mutableStateOf(openRequest) }")
        val earlyReturn = chip.indexOf("if (!visible || options.isEmpty()) return")
        assertTrue("the latch must exist", latch >= 0)
        assertTrue("the early return must exist", earlyReturn >= 0)
        assertTrue("the latch must be declared above the early return", latch < earlyReturn)
    }

    @Test
    fun theHostStillRaisesTheCounterAndHandsItOver() {
        // 正路径没被改：slash 面板的 built-in 分支照旧抬闩，调用点照旧把闩传下来。
        assertTrue(composer.contains("var agentOpenRequest by remember { mutableStateOf(0) }"))
        assertTrue(
            composer.contains(
                "ComposerSuggestions.BUILT_IN_AGENT -> agentOpenRequest += 1",
            ),
        )
        val call = composer.substringAfter("AgentChip(").substringBefore("ModelChip(")
        assertTrue("the call must still pass the latch", call.contains("openRequest = agentOpenRequest"))
    }
}
```

### 断言 ↔ 需求对照

| 需求 | 测试方法 | 关键断言 |
|---|---|---|
| ① 请求变化 → 打开 | `onlyANewRequestOpensTheMenu` | `assertTrue(agentOpenRequestArrived(seen = 0, current = 1))` |
| ② 重建/重组 → 不重弹 | `onlyANewRequestOpensTheMenu` + `theChipRemembersTheRequestItHasSeen` | `assertFalse(agentOpenRequestArrived(seen = 1, current = 1))`；`assertFalse(chip.contains("if (openRequest > 0) openMenu()"))` |
| ③ 再次变化 → 再打开 | `onlyANewRequestOpensTheMenu` | `assertTrue(agentOpenRequestArrived(seen = 1, current = 2))` |
| 附加：正路径接线未被改坏 | `theHostStillRaisesTheCounterAndHandsItOver` | `agentOpenRequest += 1` + `openRequest = agentOpenRequest` |
| 附加：不可见期间的请求不丢 | `theLatchOutlivesTheHiddenAndLoadingEarlyReturn` | `latch < earlyReturn`（下标比较） |

### 跑法（**等 Lead 的全量闸门结束后**，现在不要跑）

```powershell
# 单类，命名与仓库既有写法一致
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.AgentChipOpenRequestV97Test"

# 连带回归（这几类与 AgentChip.kt / Composer.kt 相邻，建议一起）
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.AgentChip*" `
  --tests "com.xiufeigo.opencodeshell.ComposerModelChipV75Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerShortWindowV74Test" `
  --tests "com.xiufeigo.opencodeshell.OcPopoverDismissTest" `
  --tests "com.xiufeigo.opencodeshell.Wave5ComposerWiringTest"
```

> ⚠️ **本次诊断没有运行、也不建议现在运行任何 gradle 命令**（Lead 正在跑全量闸门，
> 会被锁拦并抢构建目录）。

### 备选加强（本轮**不做**，仅备案）

若 Lead 希望 pin 到**真实 Compose 运行时**（而不是纯函数 + 源码文本），需要在
`app/build.gradle.kts:122-124` 追加 `androidx.compose.ui:ui-test-junit4` +
`androidx.compose.ui:ui-test-manifest` + `org.robolectric:robolectric`，并用
`createComposeRule()` 写「`openRequest` 不变时重组 → 菜单不出现」。这会新增依赖面、
拉长构建，且与本仓库现有 pin 风格背离，**建议单开一轮**，不要混进本次最小修复。

---

## 回归风险与调用点清单

### 调用点清单（全仓穷举）

| # | 调用点 | 证据 | 本补丁的影响 |
|---|---|---|---|
| C1 | `Composer.kt:1727-1740`（唯一产品调用） | 全仓 `AgentChip(` 只命中此处 | 只改消费语义，参数与接线不变 |
| C2 | `Composer.kt:467` 计数器声明 | `var agentOpenRequest by remember { mutableStateOf(0) }` | 不改 |
| C3 | `Composer.kt:1045` 唯一递增点 | `ComposerSuggestions.BUILT_IN_AGENT -> agentOpenRequest += 1` | 不改 |
| C4 | `Composer.kt:1726` `if (!shellMode)` 包住两个 chip | — | **受益**：进出 shell 模式 = 整段重挂，v74 会重弹，现在不会 |
| C5 | `Composer.kt:1734` `visible = agentPickerVisible` | ← `SessionScreen.kt:856/1877` ← `ChatViewModel.kt:576-581` | **受益 + 一处行为变化**，见 R2 |
| C6 | 设置面板 | **未共用 AgentChip**：`AgentChip(` 全仓仅 C1 | 无影响 |
| C7 | 会话页 header | 不用 `AgentChip`（`ui/session/header/SessionHeader.kt` 只在 `OcPopoverDismissTest.kt:200` 作为另一个 `ocPopoverDismiss` 宿主被点名） | 无影响 |

### 风险清单

| # | 风险 | 证据 | 评估 |
|---|---|---|---|
| R1 | **「重建后重开」这条 v74 保证被移除** | `ModelChip.kt:126-127` 明确记录：v74 靠「重建后重开」保住形态翻转下开着的面板，现改由 composer 的单挂载承担（v81 P0 退役 `movableContentOf`，`ComposerModelChipV75Test.kt:63-87` 钉住 `controls = { toolControls() }` 单挂载） | **已闭环**。agent chip 同样在 `toolControls` 内（`Composer.kt:1727`），形态翻转不重建它，弹层不会被卸载 ⇒ 这条保证由 v81 承担，不因本补丁丢失 |
| R2 | **chip 不可见期间敲的 `/agent` 的行为** | `ChatViewModel.kt:581` `SharingStarted.Eagerly, false` ⇒ 首帧 `agentPickerVisible = false`；`ComposerSuggestions.kt:294-303` 不过滤 `/agent` 行 | v74：chip 后出现时**会**弹（副作用，不是承诺）。本补丁把闩放在早退之上 ⇒ **仍然会弹**，比 v74 更可控。若 Lead 选择「闩放在早退之下」的更小 diff，这条就会**丢**——这就是推荐 B' 布局的唯一理由 |
| R3 | agent 列表为空时的早退 | `AgentChip.kt:110-111`，`GET /agent` 返回前 `options` 为空 | 同 R2，闩在上 ⇒ 请求不丢 |
| R4 | **chip 自身点击没有宿主兜底（v74 P0 同款，**未**在本次修）** | `AgentChip.kt:158` `.clickable { openMenu() }` 只写本地 `expanded`；`ModelChip` 有 `onRequestOpen`（`ModelChip.kt:151` + `ComposerShortWindowV74Test.kt:231-235` 钉住）而 `AgentChip` **没有**；`research/HANDOFF.md:63` 记着「chip 点不开 = expanded 被 composer 重建抹掉」 | **当前不是活缺陷**（v81 P0 后形态翻转不再重建 chip，见 R1）。但 `shellMode`（C4）与 `visible` 早退（C5）仍是两条真卸载路径：用户点开 agent 菜单的同时这两者翻转，菜单会丢且**无处可捞**。**建议另开单**给 `AgentChip` 补 `onRequestOpen`，不要混进本次最小修复 |
| R5 | 新 `internal fun` 的包可见性 | 与 `modelOpenRequestArrived`（`ModelChip.kt:602`，同包 `internal`）同款，同模块单测可 import | 无风险 |
| R6 | KDoc 交叉引用 `[modelOpenRequestArrived]` | 同包同模块 `internal` ⇒ KDoc 链接可解析（`AgentChip.kt:91` 已有同款 `[ModelChip.openRequest]` 先例） | 无风险 |
| R7 | 行长/风格 | 无项目级 `.editorconfig`（`glob **/.editorconfig` 只命中 `_upstream` 与 `node_modules`）；新增行均 ≤ 100 列，与 `ModelChip.kt:587-603` 同宽 | 无风险 |
| R8 | 既有 4 个相关 pin 变红 | 已在补丁章节逐条核对 | 无风险 |
| R9 | 槽位（slot）稳定性 —— 把 `remember` 放在早退之上会不会让槽位错位 | 本文件**已经**是「早退之上有 remember（`:110` `options`）、之下有 remember（`:112` `expanded`）」的形状且工作正常 | 无风险。hunk 1 只是在 `:110` 之后再加一个无条件 remember，早退的分支结构不变 |

### 未闭环项（本轮**不做**，需 Lead 决策）

1. **R4：`AgentChip` 缺 `onRequestOpen`** —— 与 ModelChip 的 v74 P0 不对称，建议另开单。
2. **`AgentChip.kt:91` 的 `openRequest` KDoc 未更新** —— 本补丁没改它（`AgentChip` 自己的
   KDoc 已足够说明计数语义；`ModelChip.kt:120-128` 的长篇 v75 P2 说明可以不照搬）。
   若 Lead 要求两个 chip 文档对称，可后续补，但会再多一处 diff。
3. **真机复验未做** —— 本次是只读诊断，未跑设备。建议闸门通过后按
   `research/v97_verify/` 的既有格式补一组：敲 `/agent` → 关掉 → 点 composer 文本区
   （触发重建）→ 断言菜单**不**出现；再敲 `/agent` → 断言**出现**。
4. **compose 运行时级 pin 未做** —— 见「备选加强」，需加依赖，建议单开一轮。

---

## 附：本次取证的每条结论出处

| 结论 | 证据 |
|---|---|
| 文件真实路径在 `ui/session/composer/` | `read` 对 `ui/components/AgentChip.kt` 报 `not found`；`glob **/AgentChip.kt` 只命中 `app\src\main\java\com\xiufeigo\opencodeshell\ui\session\composer\AgentChip.kt` |
| 缺陷在 `AgentChip.kt:144-145` | 全文 read 的行号输出 |
| `openRequest` 唯一递增点是 `Composer.kt:1045` | `grep agentOpenRequest` 命中 `Composer.kt:467/1045/1733` |
| `AgentChip(` 唯一产品调用是 `Composer.kt:1727` | `grep "AgentChip\("` 命中 `Composer.kt:1727` + 2 个测试文件 |
| ModelChip 修法在 `ModelChip.kt:220-231` + `:602-603` | `grep modelOpenRequestArrived` + 分段 read |
| 测试只有 junit/json/mockwebserver | `grep testImplementation app/build.gradle.kts` |
| `AgentChipFocusRestoreV97Test` 无 compose 依赖 | `AgentChipFocusRestoreV97Test.kt:1-9` import 块 |
| 该缺陷是上一轮已备案未修项 | `research/v97_verify/residue/REPORT.md:91`、`research/v97_RESUME.md:63`、`research/v97_NEW_SESSION_PROMPT.md:37`、`research/v97_KICKOFF.md:34` |
