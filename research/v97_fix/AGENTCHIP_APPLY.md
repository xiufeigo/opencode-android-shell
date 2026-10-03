# AgentChip `/agent` 重建即重弹 —— 补丁落地报告（v97 P2）

> 执行：修复工程师（fix-agentchip）。**已落地补丁 + 已跑回归**。
> 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
> 方案来源：`research/v97_fix/AGENTCHIP_PATCH.md`（只读诊断员产出，622 行）
>
> **状态：DONE**。`AgentChip.kt` +36/−3 行（341 → 374），新增 1 个测试类，
> 回归 **53 用例全绿、0 失败**，无既有 pin 因本次语义变化过时。

---

## 方案核对（每处锚点、与方案不一致的偏差）

### 逐锚点核对结论：**全部一致，零偏差**。方案里 3 个 hunk 的行号、上下文文本、
### 插入位置在盘上代码里逐字成立，无需以盘上为准做调整。

| # | 方案锚点 | 盘上核对 | 结论 |
|---|---|---|---|
| A1 | 缺陷本体 `AgentChip.kt:144-145` `LaunchedEffect(openRequest) { if (openRequest > 0) openMenu() }`，`:146` 闭合 | 修复前 read 输出完全一致：144/145/146 逐字吻合 | ✔ |
| A2 | hunk 1 插入点 = `:110` `val options = remember(agents) {…}` 与 `:111` `if (!visible \|\| options.isEmpty()) return` **之间** | 盘上 `:110`/`:111` 逐字一致；插入后闩落在 `:121`、早退 `:122`，顺序正确 | ✔ |
| A3 | hunk 2 上下文 = `:142` `val chooseLabel = stringResource(R.string.oc_composer_choose_agent)` + 紧随的 3 行 `LaunchedEffect` | 盘上 `:142` 逐字一致（修复后因 hunk 1 顺移到 `:153`） | ✔ |
| A4 | hunk 3 插入点 = `AgentChip` 闭合 `}` 之后、`/** [data-slot=menu-v2-item] …` 之前 | 盘上该 KDoc 头逐字一致（修复后 `:311`） | ✔ |
| A5 | `ModelChip.kt:227` `var seenRequest by remember { mutableStateOf(openRequest) }` | 盘上 `:227` 逐字一致 | ✔ |
| A6 | `ModelChip.kt:228-231` `LaunchedEffect(openRequest) { if (modelOpenRequestArrived(seenRequest, openRequest)) openPicker(); seenRequest = openRequest }` | 盘上 `:228-231` 逐字一致（含注释块 `:220-226` 全在代码块外） | ✔ |
| A7 | `ModelChip.kt:602-603` `internal fun modelOpenRequestArrived(seen, current) = current > 0 && current != seen` | 盘上 `:602-603` 逐字一致；`internal`（非 private）、同包同模块 | ✔ |
| A8 | `Composer.kt:467` `var agentOpenRequest by remember { mutableStateOf(0) }` | grep 命中 `:467`，逐字一致，**纯 `remember` 非 `rememberSaveable`** | ✔ |
| A9 | `Composer.kt:1045` `ComposerSuggestions.BUILT_IN_AGENT -> agentOpenRequest += 1` | grep 命中 `:1045`，逐字一致，全仓唯一递增点 | ✔ |
| A10 | `Composer.kt:1733` `openRequest = agentOpenRequest` | grep 命中 `:1733`（`AgentChip(` 在 `:1727`） | ✔ |
| A11 | `ComposerModelChipV75Test.kt:111-127` 逐条 `contains` 是本仓库 pin 模板 | 盘上逐字一致（`onlyANewRequestOpensThePanel`） | ✔ |
| A12 | `ComposerShortWindowV74Test.kt:246-253` 是**四行压平成一块**的断言，**不能**照搬到 AgentChip | 盘上逐字一致，确认其断言对象是 `modelChip`（ModelChip.kt），不是 AgentChip | ✔ |
| A13 | 测试无 compose 依赖 / 无 robolectric，只能纯 JVM | 盘上**实质成立、行号已漂**（见偏差 D1） | ✔ 实质 |

### 与方案不一致的偏差（共 1 条，**不影响落地**）

**D1（行号漂移，不影响结论）** —— 方案第 19 行与第 407 行两处把测试依赖记作
`app/build.gradle.kts:122-124`。盘上该文件已变更，现 `:122-124` 是 termux 相关的
注释块；真实的 test 依赖在 **`:139-141`**：

```
139: testImplementation("junit:junit:4.13.2")
140: testImplementation("org.json:json:20240303")
141: testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
```

**方案的实质判断仍然成立**：`grep testImplementation|robolectric|ui-test` 全文件
只有这 3 行，**无 `robolectric`、无 `ui-test-junit4`**。所以"pin 必须是纯 JVM"的
结论不变，测试文件按纯 JVM 形状写，**未新增任何依赖**。

> 除此之外无其他偏差。方案的三处「自我修正」（闩必须放早退之上、用 `remember`
> 不用 `rememberSaveable`、pin 不许压平成一块）均已复核成立并照做。

---

## 改动

### 1. `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/AgentChip.kt`

`+36 / −3` 行，341 → **374** 行。`Composer.kt` **一行未改**。

> **关于 diff 口径的说明（重要）**：`git diff` 对本文件报的是 `+132/−9`，
> **那不是本次改动**。`AgentChip.kt` 在我开工前**已被前序 v97 agent 改动过且尚未
> 提交**（`visible` 参数、`onRestoreFocus`、`closeReason`、`ocPopoverDismiss` 等
> v57 移交项都还在工作区里没进 HEAD），所以 `git diff` 对的是 HEAD 而非我改前的
> 盘上状态。
> 下表的 diff 是**按本次改动重建前像（341 行）后**逐字生成的，作用域精确到本次三个
> hunk，已自校验：前像 341 行、含 v74 原句 `if (openRequest > 0) openMenu()`、
> 不含 `seenRequest` / `agentOpenRequestArrived`。原始文件另存于
`research/v97_fix/AgentChip_scoped.diff`。

```diff
diff --git a/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/AgentChip.kt b/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/AgentChip.kt
--- a/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/AgentChip.kt
+++ b/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/AgentChip.kt
@@ -108,6 +108,17 @@ fun AgentChip(
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
     // v97（v57 移交项）：关闭原因。弹层内容卸载的那一刻才知道该不该把焦点还
@@ -141,8 +152,14 @@ fun AgentChip(
     // ACTION_CLICK is announced as a button.
     val chooseLabel = stringResource(R.string.oc_composer_choose_agent)
 
+    // `/agent` from the command menu: open the menu on the next composition.
+    //
+    // v97 P2: 只有**新**请求才开（[agentOpenRequestArrived]）。与
+    // [modelOpenRequestArrived]（v75 P2）同款：重建出的实例把挂载时那个值当作
+    // 已见过，所以「重建」不再等于「再弹一次」。
     LaunchedEffect(openRequest) {
-        if (openRequest > 0) openMenu()
+        if (agentOpenRequestArrived(seenRequest, openRequest)) openMenu()
+        seenRequest = openRequest
     }
     // v49: ESC 关闭 + 外点穿透，宿主窗口在 Popup 之外取（见 `OcPopoverDismiss`）。
     val hostView = LocalView.current
@@ -275,6 +292,22 @@ fun AgentChip(
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
 /** `[data-slot=menu-v2-item]` — name + muted description, check on the active row. */
 @Composable
 private fun AgentRow(
```

**hunk 算术自校验（与方案 §补丁自检表逐格吻合）**

| hunk | 方案头 | 盘上落点 | 校验 |
|---|---|---|---|
| 1 | `@@ -108,5 +108,16 @@` | 闩 `:121`、早退 `:122` | 新起始 108 ✔，闩在早退**之上** ✔ |
| 2 | `@@ -142,6 +153,12 @@` | 效果体 `:155-163` | 142+11 = **153** ✔ |
| 3 | `@@ -274,6 +291,22 @@` | KDoc `:295-307`、函数 `:308-309` | 274+11+6 = **291** ✔ |

落地后关键行实测：`seenRequest` 声明 `:121`、早退 `:122`、效果体 `:160-163`、
纯函数 `:308-309`、文件总行 **374**。与方案预期（341 + 33 = 374）**完全一致**。

**约束遵守自检**

- 闩用 `remember`（**无 key**）而非 `rememberSaveable` —— 与 `ModelChip.kt:227` 逐字同形，
  宿主 `Composer.kt:467` 亦为纯 `remember`，两侧对称，无状态不对称。
- 闩在 `:121` 早退 `:122` **之上** → chip 不可见 / agent 列表未到期间敲的 `/agent`
  不丢（`ChatViewModel.kt:581` 初值 `false` 正是真路径）。
- **未新增任何用户可见字符串**：`AgentChip.kt` 的 `R.string.` 引用仍是同样 3 条
  （`:149 oc_composer_agent`、`:153 oc_composer_choose_agent`、`:280 oc_composer_agent_default`）。
- 只改 1 个产品文件；`Composer.kt` 一行未动；`app/build.gradle.kts` 未动（已冻结）。
- `_upstream/` 未触碰。

### 2. 新增测试文件

`app/src/test/java/com/xiufeigo/opencodeshell/AgentChipOpenRequestV97Test.kt`（纯 JVM，
4 个 `@Test`，import 只有 `org.junit.*` + `java.io.File` + 那个 `internal` 纯函数，
**未引入任何新依赖**）

| # | 测试方法 | 钉住什么 | 关键断言 |
|---|---|---|---|
| ① | `onlyANewRequestOpensTheMenu` | **真值表**（纯函数） | `true`: `(0,1) (1,2) (7,8)`；`false`: `(1,1) (3,3) (0,0) (4,0)` |
| ② | `theChipRemembersTheRequestItHasSeen` | 闩声明 + 效果体用纯函数 + **v74 形状已消失** + 唯一消费者 | `contains` 三条 + `assertFalse("the v74 latch re-read must be gone", …)` + `assertEquals(1, LaunchedEffect\(openRequest\) 计数)` |
| ③ | `theLatchOutlivesTheHiddenAndLoadingEarlyReturn` | **闩在早退之上（下标比较）** | `latch >= 0`、`earlyReturn >= 0`、`latch < earlyReturn` |
| ④ | `theHostStillRaisesTheCounterAndHandsItOver` | 宿主仍在递增并下传 | `var agentOpenRequest by remember { mutableStateOf(0) }`、`BUILT_IN_AGENT -> agentOpenRequest += 1`、`AgentChip(…ModelChip(` 切片内 `openRequest = agentOpenRequest` |

> ② 里的效果体断言用的是**逐条 `contains`**（照 `ComposerModelChipV75Test.kt:123-126`），
> **没有**照搬 `ComposerShortWindowV74Test.kt:246-253` 的「四行压平成一块」——
> 闩（`:121`）与效果体（`:160-163`）之间隔着 38 行，压平后必不连着。
> ② 另加了一条**比方案更严**的断言：全文件 `LaunchedEffect(openRequest)` 出现次数
> 必须 **恰好 1**，杜绝别处再偷读一次宿主计数器。

---

## 测试证据

### 锁纪律

`.\agent-gradle-lock` 开工前**不存在**（`no lock`）→ 直接
`Set-Content .agent-gradle-lock "fix-agentchip"` 取得；跑完立刻
`Remove-Item`，复检 `lock clean`。**全程未等待、未硬抢、未删他人锁。**
`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"` 已设。
只跑了**授权的那一条** gradle 形态（无 `assembleDebug`、无 `clean`、无全量
`testDebugUnitTest`、**未装机**）。

### 命令原文

```powershell
.\gradlew.bat --console=plain :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.AgentChip*" --tests "com.xiufeigo.opencodeshell.ComposerModelChipV75Test" --tests "com.xiufeigo.opencodeshell.ComposerShortWindowV74Test" --tests "com.xiufeigo.opencodeshell.OcPopoverDismissTest" --tests "com.xiufeigo.opencodeshell.Wave5ComposerWiringTest"
```

### `BUILD SUCCESSFUL` 原文

```
> Task :app:compileDebugKotlin
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 6s
52 actionable tasks: 5 executed, 47 up-to-date
```

进程退出码 `0`。完整日志：`research/v97_fix/gradle_regression.log`。

### 用例数（解析 `app/build/test-results/testDebugUnitTest/*.xml`）

| 测试类 | 用例数 | 失败+错误 |
|---|---|---|
| `com.xiufeigo.opencodeshell.AgentChipFocusRestoreV97Test` | 4 | 0 |
| `com.xiufeigo.opencodeshell.AgentChipOpenRequestV97Test`（**本轮新增**） | 4 | 0 |
| `com.xiufeigo.opencodeshell.ComposerModelChipV75Test` | 6 | 0 |
| `com.xiufeigo.opencodeshell.ComposerShortWindowV74Test` | 8 | 0 |
| `com.xiufeigo.opencodeshell.OcPopoverDismissTest` | 20 | 0 |
| `com.xiufeigo.opencodeshell.Wave5ComposerWiringTest` | 11 | 0 |
| **合计** | **53** | **0** |

### 既有 pin 兼容性：**全部通过，无需更新任何既有 pin**

方案 §已核对的既有 pin 兼容性 逐条在本次跑动中兑现：

| 既有 pin | 断言 | 实测 |
|---|---|---|
| `AgentChipFocusRestoreV97Test:78-79` | `substringAfter("val openMenu: () -> Unit = {").substringBefore("}")` 切 `openMenu` 体 | hunk 1/2/3 **均未碰** `:128-135`（修复后 `:139-146`）的 `openMenu` 体 ✔ 4/4 绿 |
| 同上 `:51` | `assertFalse("fun restoresFocusOnUnmount" in agentChip)` | 新函数名 `agentOpenRequestArrived`，不匹配 ✔ |
| `OcPopoverDismissTest:190-212` | 4 个弹层宿主各需 marker + `flushPendingTap()` + marker 之后无 `Key.Escape` | hunk 3 的 KDoc 在 marker 之下但**不含** `Key.Escape`；marker(`:236`)与 `flushPendingTap()`(`:167`) 未动 ✔ 20/20 绿 |
| `Wave5ComposerWiringTest:45/:64` | `agentOpenRequest += 1`、`AgentChip(` | 读 `Composer.kt`，本补丁不改它 ✔ 11/11 绿 |
| `ComposerModelChipV75Test` / `ComposerShortWindowV74Test` | 全读 `ModelChip.kt`/`Composer.kt`/`SessionScreen.kt` | 与 `AgentChip.kt` 无交集 ✔ 14/14 绿 |

**没有任何既有 pin 因本次语义变化而过时，因此没有降强度、没有改断言。**

---

## 真机复验步骤（给 Lead / 测试员）

前置：`GET /agent` 已返回、`agentPickerVisible` 已为 true、chip 可见、**shellMode 关闭**。

**步骤 1 —— 缺陷不复现（重建不重弹）**
1. 在 composer 文本区敲 `/agent` → 命令菜单出现。
2. 点命令菜单里的 `agent` built-in 行 → **agent 菜单应打开**（`agentOpenRequest` 0→1）。
3. **关掉**该 agent 菜单（外点 / ESC / 选中任意一项，三条路径都算）。
4. **点 composer 文本区**触发一次重建（焦点落下 → 键盘起落 → 布局重测 → composer 重建）。
   > 这一击是本缺陷的最小复现触发器。修复前：agent 菜单会**凭空**再弹一次。
5. **断言：agent 菜单「不」出现。**（修复前必现，本轮修复后不应出现）
6. 追加：进出 shell 模式、切换会话各做一次 4-5 步，断言同样**不**出现。

**步骤 2 —— 正路径不破（新请求仍能开）**
7. 再次在命令菜单敲 `/agent` → 闩 1→2。
8. **断言：agent 菜单「能」打开**，且列表内容正确（选中项打勾、末尾有「默认」行）。

**步骤 3 —— 不可见期间的请求不丢（本轮布局的关键收益）**
9. 冷启进会话，**在 agent 列表加载完成前**敲 `/agent`（或先让 chip 不可见再敲）。
10. 等 agent 列表到达、chip 可见。
11. **断言：agent 菜单自动打开**（闩在早退之上，`ChatViewModel.kt:581` 初值
    `false` 这条真路径没被吃掉）。
    > 若这里打不开，说明闩被放到了早退**之下**，布局回退了。

**步骤 4 —— 焦点归还语义未回归**
12. 打开 agent 菜单 → **ESC 关闭** → 断言焦点/键盘**回到** composer 编辑器
    （`AgentChipFocusRestoreV97Test` 的桶B 语义未被本补丁破坏）。
13. 打开 agent 菜单 → **外点关闭** → 断言焦点**不**被抢回（那一击补发给底下控件）。

**步骤 5 —— 形态翻转不重弹**
14. 在短窗（如 720x1280 @420dpi）上打开 agent 菜单，用键盘把 body 顶到形态翻转档位。
15. **断言：弹层不闪、不开-毁循环**（`ModelChip` 当年 P1 的震荡症状不得在 agent chip 复现）。

---

## 未闭环 / 未验到

1. **真机复验未做** —— 本次是纯 JVM 回归，**未装机、未起模拟器**（纪律禁止）。
   上面 5 组步骤需 Lead / 测试员在闸门通过后执行。这是本轮唯一的**运行时**缺口。
2. **compose 运行时级 pin 未做** —— 按方案 §备选加强，需要在
   `app/build.gradle.kts` 追加 `ui-test-junit4` + `ui-test-manifest` + `robolectric`，
   用 `createComposeRule()` 写「`openRequest` 不变时重组 → 菜单不出现」。
   新增依赖面、拉长构建、与本仓库既有 pin 风格背离，**建议单开一轮**，未混进本次最小修复。
   （`app/build.gradle.kts` 已被另一 agent 冻结，本轮无论如何不能动。）
3. **R4 未修（方案 §风险 R4，建议另开单）** —— `AgentChip.kt:175` 的
   `.clickable(role = Role.Button) { openMenu() }` 只写本地 `expanded`，**没有**像
   `ModelChip` 那样带 `onRequestOpen` 让宿主兜底。当前 v81 P0 后形态翻转不再重建
   chip，故**不是活缺陷**；但 `shellMode` 翻转（`Composer.kt:1726`）与 `visible` 早退
   仍是两条真卸载路径：用户点开菜单的同时这两者翻转，菜单会丢且无处可捞。
   **本轮刻意未动**（混进来就不是"最小修复"了），建议另开单给 `AgentChip` 补 `onRequestOpen`。
4. **`AgentChip.kt:91` 的 `openRequest` KDoc 未更新** —— 仍只写「see [ModelChip.openRequest]」，
   没跟 v75/v97 的消费语义。方案 §未闭环 2 明确本轮不改（AgentChip 自己的新注释块已
   充分说明计数语义）。若要求两个 chip 文档对称，可后续补，会再多一处 diff。
5. **未跑全量闸门** —— 全量 `testDebugUnitTest` 与装机是 Lead 的职责，本轮只跑了授权的
   那 5 个 `--tests` 过滤器。**因此"其余测试类未受影响"这一点尚未由本轮证据背书**，
   需 Lead 的全量闸门确认。
6. **工作区未提交** —— `AgentChip.kt` 在我开工前**已被前序 v97 agent 改动且未提交**
   （`visible`/`onRestoreFocus`/`closeReason` 等 v57 移交项仍在工作区）。我的改动叠加在其上，
   `git status` 显示该文件为 `M`。**提交时的分组归属需 Lead 决定**（本次 v97 P2 三处 hunk
   与前序 v57/v97 焦点归还改动混在同一文件的未提交 diff 里），不要误当成单一原子提交。
