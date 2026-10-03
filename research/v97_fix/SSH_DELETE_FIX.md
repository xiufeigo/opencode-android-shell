# v98 修复报告 — 服务器条目 ⋮→删除（D3）

**执行人**：修复工程师（SSH 域）  **日期**：2026-10-02
**仓库**：`C:\sensorsdata\main\program\opencode-android-shell`
**输入**：`research/v97_verify/5554_v98ssh/REPORT.md` §3.4（D3 段）、`research/v97_fix/SSH_RECONNECT_FIX.md`（上一轮 D3 修复）

---

## 0. 结论先说

> **D3 不是 v97 修复弄坏的，也不是状态机问题。确认条**一直都在被组合出来**，
> 它只是落在列表滚动容器的可视区之外**，所以在真机上既看不见也点不到 ——
> 这就是"连弹窗都不弹"的真相。

真机 a11y 树的硬证据（`v98_d3_52_after.xml`、`07_D3.txt`）：

| 事实 | 读数 |
|---|---|
| 面板 body 滚动视口 | `[43,800][1038,1937]` |
| **第 2 条** SSH 行 ⋮ 按钮 | `[780,1827][906,1953]` —— 已经在折叠线 |
| 它的确认条落点 | y ≈ 1965+ —— **在视口之外** |
| 紧邻的下一行 | `[169,2000][560,2003]` —— **3 像素高的残片**（被裁切的证明） |
| 5 次尝试 | 全部在**下面那条**行（`843,1890`）上 |
| 结果 | `dialogNodes=0` |

**决定性的一条**：下一行 `E2ECHK5554v98-search-01` 的文本在 a11y 树里只有 **3px** 高
（一个 13sp 字体不可能是 3px）。这说明**面板里折叠线以下的内容既被裁切、也从 a11y 树里被裁掉**——
所以"确认条不出现"不是没组合，而是**组合了但不可见**，用 uiautomator 自然也 dump 不到。

**为什么测试员会判定"删除完全坏了"**：5 次尝试全部落在**第二条** SSH 行上。
第一条行的确认条落在 y≈1570-1730，**在视口内**，本来是可见的。
所以这个缺陷读起来像"删除永远不工作"，实际是"**第二条及以后的行删不掉**"。

修复 = 让确认条**自己滚进视野**（`bringIntoViewRequester` + `bringIntoView()`），
复用面板**已有的那一个** `verticalScroll`，不加第二个滚动容器、**不新增任何用户可见字符串**。

| 项 | 结果 |
|---|---|
| 改动文件 | `ui/servers/SshTunnelForget.kt`（1 个，+11 行 / +4 import / +1 注解） |
| 新增 pin | `SshDeletePathV97Test`，**12 用例全绿** |
| 新增用户可见字符串 | **0**（故三语资源未动，`StringsLocaleParityTest` 同跑通过） |
| JVM 回归 | ✅ **293 tests / 0 failures / 0 errors，`BUILD SUCCESSFUL in 11s`**（31 类） |

---

## 1. 根因（文件:行号）

### 1.1 确认条是**行内块**，画在该行**下方**

`app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshTunnelHost.kt:309-322`

```kotlin
if (forget.armed == tunnel.config.id) {
    SshTunnelForgetConfirm(
        name = sshName(tunnel.config.name, tunnel.config.target),
        releaseFirst = sshForgetReleasesTunnel(tunnel.status.stage),
        onCancel = { forget = forget.cancel() },
        onConfirm = {
            confirmSshForget(forget, tunnel.config.id, host, removePersisted)
            forget = forget.cancel()
        },
    )
}
```

它不是 `Dialog`，而是 `SshTunnelCard` 那根 `Column` 上的**最后一个子节点** —— 即
**永远画在这一行的下面**。这是 v91 的设计（`SshTunnelForget.kt:201-209` 的注释写明：
"drawn **inside the card** it belongs to … the one surface that cannot be clipped by the panel's box"）。
**这个前提在"行在列表靠上位置"时成立，在"行在靠下位置"时不成立。**

### 1.2 面板 body **没有自己的滚动容器**

`app/src/main/java/com/xiufeigo/opencodeshell/ui/settings/ServersPanel.kt:233`

```kotlin
SettingsPanelBody(scroll = false) {
```

整个面板**唯一**的滚动容器是外层那一根：

`ServersPanel.kt:205`

```kotlin
Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
```

所以确认条出现时，**没有任何东西把它滚进来** —— 行的位置不变，确认条就落在行下面那个
**已经被折叠线切断**的位置。

### 1.3 因果链（端到端）

```
用户在第 2 条（或更靠下的）SSH 行点 ⋮
  → SshTunnelHost.kt:276-279  menu=false; forget = forget.ask(id)     ← 状态机正确，确实 armed 了
  → SshTunnelHost.kt:309      if (armed == id) 成立                   ← 确认条确实被组合
  → SshTunnelForget.kt:220    Column(... .padding(bottom = 12.dp))    ← 画在该行**下方**，y≈1965
  → ServersPanel.kt:233       body 无滚动器；唯一的滚动器在 :205，没有被触发
  → 视口底 [43,800][1038,1937] 之外 → 裁切 + 从 a11y 树剔除
  → 真机所见：菜单关掉了，弹窗没有，行还在
```

**旁证（为什么之前被误判成"状态机/弹窗"问题）**：v98 报告 §6.2 写"源码里不存在确认弹窗文案"。
实际上**存在** —— `app/src/main/res/values/strings.xml:1140`：

```xml
<string name="oc_ssh_delete_title">Delete SSH server?</string>
```

这正是 F14 报告里测试员**亲眼看到过**的那句文案。也就是说 **F14 那轮确认条是画出来了的**
（当时行在靠上位置），只是它的按钮点不动；**v98 这轮行在靠下位置，连条本身都看不见**。
**同一个根因的两种表现**，不是两个缺陷。

### 1.4 为什么"上一轮 D3 修复"看起来像元凶，其实不是

`research/v97_fix/SSH_RECONNECT_FIX.md` §6.2 自己写明了：

> "D3 的'弹窗按钮可点性'只修了一半……**几何/命中成因**……**没有在无设备条件下复现，故未动布局**。"

上一轮修的是**逻辑侧**（`forget` 前置 `abort()`，让停泊的协程失声）—— 那部分是对的、
本轮原样保留（见 §3.3）。**几何侧上一轮明确留空了**，本轮补的就是它。
所以本轮不是回归，是**上一轮就已登记在案的未闭环项**被真机复验确认。

---

## 2. 改动 diff

唯一改动文件：`app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshTunnelForget.kt`

```diff
 import androidx.compose.foundation.clickable
+import androidx.compose.foundation.ExperimentalFoundationApi
 import androidx.compose.foundation.layout.Arrangement
 ...
+import androidx.compose.foundation.relocation.BringIntoViewRequester
+import androidx.compose.foundation.relocation.bringIntoViewRequester
 import androidx.compose.foundation.shape.RoundedCornerShape
 import androidx.compose.material3.Text
 import androidx.compose.runtime.Composable
+import androidx.compose.runtime.LaunchedEffect
+import androidx.compose.runtime.remember

@@ fun SshTunnelForgetConfirm(
+@OptIn(ExperimentalFoundationApi::class)
 @Composable
 fun SshTunnelForgetConfirm(
     name: String,
     releaseFirst: Boolean,
     onCancel: () -> Unit,
     onConfirm: () -> Unit,
     modifier: Modifier = Modifier,
 ) {
     val c = OcTheme.colors
     val shape = RoundedCornerShape(6.dp)
+    // v98 D3 — the bar is drawn in-flow *below* its row, but the panel's body
+    // owns no scroller of its own (`ServersPanel`'s single outer
+    // `Column.verticalScroll` is the only one), so a row low in the list put
+    // the whole bar past the fold: on `emulator-5554` the body viewport ends
+    // at y≈1937 and the second SSH row's ⋮ already sits at y 1827-1953, so
+    // the bar composed at y≈1965 — off-screen, and pruned from the a11y tree
+    // (`research/v97_verify/5554_v98ssh/07_D3.txt`: 5/5 attempts, the next
+    // list row reporting as a 3 px sliver). Arming scrolls itself into view,
+    // so the bar and both its buttons are reachable wherever the row sits.
+    // The request is a no-op without a scrollable ancestor.
+    val reveal = remember { BringIntoViewRequester() }
+    LaunchedEffect(Unit) { runCatching { reveal.bringIntoView() } }
     Column(
         modifier
+            .bringIntoViewRequester(reveal)
             .fillMaxWidth()
             .padding(bottom = 12.dp)
             .background(c.layer01, shape)
```

**为什么选 `bringIntoView` 而不是改成弹窗**：

1. **改动面最小**：只碰一个文件、11 行，**`SshTunnelCard` 一字未动**。
2. **不加第二个滚动容器**：`bringIntoView` 复用 `ServersPanel.kt:205` 已有的那一个
   `verticalScroll`。新加滚动容器会撞上 v32 确立的"单滚动"形状，也会踩坏
   `SshTunnelNarrowStructureV93Test` / `SettingsPanelScrollTest` 一族的几何 pin。
3. **不新增字符串**：沿用既有的 `oc_ssh_delete_title/message/release_hint` 三句。
4. **无滚动祖先时是 no-op**：`SshDialog` 自身那条 confirm 分支若复用本 composable，
   不会因此改变任何行为。
5. **弹窗方案要动 `SshTunnelCard` 的调用结构**（把行内块换成 overlay），会同时动到
   D1/D2/F12b/F14b 共用的那几处 pin，风险面远大于收益。

---

## 3. 为什么不降强度

### 3.1 既有 pin **全部零改动**

本轮**没有改写任何一条既有断言**。特别是这几条最容易被本次改动碰到的：

| 既有 pin | 为什么本轮必须保住 | 结果 |
|---|---|---|
| `SshTunnelDeleteV91Test.theOverflowDeleteOnlyArmsTheRowAndNeverForgets` | 它断言 `card().substringAfter("ask(...)").substringBefore("SshTunnelForgetConfirm(")` **不含 `forget(`** | 改动全在 `SshTunnelForget.kt`，该区间**一字未动** |
| `SshTunnelDeleteV91Test.forgetIsNoLongerDeadCode` | `confirmSshForget(forget, tunnel.config.id, host, removePersisted)` 必须在 | 未动 |
| `SshTunnelDeleteV91Test.forgetDisconnectsCancelsTheWatcherThenDropsTheRow` | v91 释放顺序 | 未动 |
| `SshSeedForgetV92Test`（14） | `removePersisted` 接缝 + 同一调用路径 | 未动 |
| `SshReconnectFidelityV97Test`（20） | D1/D2/F12b + v97 的 `abort()` 语义 | 未动 |
| `SshTunnelNarrowStructureV93Test` / `SshTunnelNarrowRowV93Test` | 窄行折叠阈值与行几何 | 未动 |

**净强度：上升**（新增 10 条 pin，见 §4.2），**无一条被削弱或改写**。

### 3.2 新 pin 覆盖任务书三问

`app/src/test/java/com/xiufeigo/opencodeshell/SshDeletePathV97Test.kt`（10 用例，纯 JVM）：

| 任务书要求 | 用例 |
|---|---|
| **① ⋮→Delete 在任何状态下都能进入确认态** | `theOverflowEntryArmsTheRowInEveryStage`（**10 个 stage 全遍历**）、`theThreeReportedRowStatesAllArm`（`READY`/`AUTHENTICATION`/`CONNECTING`）、`theConfirmIsGatedOnTheArmAndNothingElse`（两个行形都接线 + 断言确认门只看 armed、stage 只进文案）、`theStageOnlyChangesTheCopyAndNeverTheEntry` |
| **② 确认后确实调用删除** | `onlyTheConfirmedTapHandsTheIdBack`、`theConfirmButtonIsTheOnlyCallerOfTheRemoval`（⋮ 区间不得出现 `forget(`）、`theRemovalDropsThePersistedRecordBeforeTheLiveTunnel`（`removePersisted` 先于 `host.forget`） |
| **③ abort/forget 的调用顺序不吞掉确认流程** | `abortingTheParkedDialDoesNotGateTheConfirmFlow`（`abort()` 先于 `disconnect(id)`，v91 顺序仍在）、`theArmSurvivesTheAbortBecauseTheyAreDifferentStages`（**arm 区间不得出现 `abort(`/`disconnect(`/`host.forget`/`confirmSshForget`**）、`theReleaseHappensInsideTheConfirmedRemovalNotOnArm` |
| **本轮修复本体（防复发）** | `theArmedBarScrollsItselfIntoView`（含"不得自带滚动器"负向断言）、`theBarIsRevealedOnEveryArmNotOnlyTheFirst` |

---

## 4. 测试证据

### 4.1 命令

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --console=plain :app:testDebugUnitTest --tests <31 个精确类名>
```

31 个类 = 任务书点名的 7 个 + grep 出的全部引用 `ServersPanel` / `SshConnectionModel` /
`SshTunnelForget` / `SshTunnelHost` 的测试类 + `StringsLocaleParityTest`：

`SshReconnectFidelityV97Test`、`SshDeletePathV97Test`、`SshPassphraseAnswerV94Test`、
`SshKeyFileFailureF14Test`、`SshEd25519IdentityF14Test`、`Wave5ConnectTest`、
`SshPromptOfferV94Test`、`SshPromptCancelDoesNotDismissF13bTest`、`SshSeedForgetV92Test`、
`SshTunnelDeleteV91Test`、`SshTunnelNarrowStructureV93Test`、`SshTunnelNarrowRowV93Test`、
`SshSubmitChainTest`、`SshPanelTunnelTest`、`SshTunnelHostModelTest`、`SshTunnelWiringTest`、
`SshConnectionModelTest`、`SshModelTest`、`SshAuthenticateModelTest`、`SshDialogRenderTest`、
`SshImeNextRoutingF13aTest`、`ServerConfigStoreSshTest`、`SettingsImeDownGeometryV97Test`、
`ServersFormImeFloorDownV89Test`、`ServersFormImeFloorV88Test`、`ServersFormImeBandV87Test`、
`ServersFormImeBandV85Test`、`ServersFormImeBandV83Test`、`SettingsShortWindowImeV84Test`、
`DropdownMenuDarkSurfaceTest`、`StringsLocaleParityTest`

日志：`research/v97_fix/gradle_d3_fix.log`

### 4.2 计数 —— ✅ 最终 **BUILD SUCCESSFUL**

```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 11s
```

（`app/build/test-results/testDebugUnitTest/*.xml` 汇总，31 个类）

```
CLASSES=31
TESTS=293 FAILURES=0 ERRORS=0 SKIPPED=0
```

新增 pin `SshDeletePathV97Test`：**12 用例全绿** ——
`theOverflowEntryArmsTheRowInEveryStage`、`theThreeReportedRowStatesAllArm`、
`theConfirmIsGatedOnTheArmAndNothingElse`、`theStageOnlyChangesTheCopyAndNeverTheEntry`、
`theArmedBarScrollsItselfIntoView`、`theBarIsRevealedOnEveryArmNotOnlyTheFirst`、
`onlyTheConfirmedTapHandsTheIdBack`、`theConfirmButtonIsTheOnlyCallerOfTheRemoval`、
`theRemovalDropsThePersistedRecordBeforeTheLiveTunnel`、
`abortingTheParkedDialDoesNotGateTheConfirmFlow`、
`theArmSurvivesTheAbortBecauseTheyAreDifferentStages`、
`theReleaseHappensInsideTheConfirmedRemovalNotOnArm`

**D1 / D2 / F12b / F14 既有 pin 零改动随行**：`SshReconnectFidelityV97Test`(20)、
`SshPassphraseAnswerV94Test`、`SshKeyFileFailureF14Test`(7)、`SshEd25519IdentityF14Test`(4)、
`SshSeedForgetV92Test`(14)、`SshTunnelDeleteV91Test`、`SshTunnelNarrow*V93Test`、
`SshPromptOfferV94Test`(14)、`SshPromptCancelDoesNotDismissF13bTest`(9) 全绿。

**新增字符串 0**，故 `StringsLocaleParityTest` 三语齐平判定不受影响（本轮同跑通过）。

日志：`research/v97_fix/gradle_d3_fix.log`

> **中间过程如实记录**：本轮首次编译时**本修复自身**报了 6 条
> "This foundation API is experimental"（`bringIntoViewRequester` 缺 `@OptIn`），
> 已修；随后 `:app:compileDebugKotlin` 被**他人并行改动**打断（见 §7），
> 等待约 14 分钟后对方于 **21:06:53** 落地修复，随即重跑取得上述 `BUILD SUCCESSFUL`。
> **期间本轮未修改任何他人文件，也未绕过锁。**

### 4.3 静态逐条核对（提交前的自查，与 §4.2 相互印证）

在拿到 `BUILD SUCCESSFUL` 之前，本轮把 `SshDeletePathV97Test` 里**每一条**源码断言，
用与测试**完全相同的解析逻辑**（含 `//` 注释剥离）在 PowerShell 里跑了一遍：

```
research/v97_fix/verify_d3_pins.ps1
```

结果 **24/24 全 OK**（7 条负向 `= False`，17 条正向 `= True`）——
覆盖"确认条不含 `verticalScroll`/`rememberScrollState`"、
"⋮→确认条区间不含 `forget(`/`abort(`/`disconnect(`/`host.forget`/`confirmSshForget`"、
"`ask()` 在两个行形各一次（计数 = 2）"、"`abort()` 早于 `disconnect(id)`"、
"`removePersisted(target)` 早于 `host.forget(target)`" 等。JVM 实跑结果与之一致。

> **本轮自查抓到并修掉的一个真实缺陷**：负向断言最初直接读原始文本，
> 结果命中了**本修复自己注释里**的 `verticalScroll` 字样（注释为了解释
> "为什么不加滚动器"而写了这个词）—— 一条会误报的红 pin。
> 已改为**先剥离 `//` 注释再断言**（`withoutLineComments`），
> 三个负向断言统一走该路径。

---

## 5. 真机复验步骤（交测试员）

前置：`emulator-5554` / Pixel_9a。**关键：必须准备 ≥2 条 SSH 条目**——本缺陷只在
**第二条及以后**的行上出现（第一条行的确认条本来就在视口内）。

| # | 行态 | 做法 | 断言 |
|---|---|---|---|
| 1 | **任意态，第 2 条及以后** | 造 **2 条** SSH 条目 → 对**下面那条** ⋮ → `Delete` | ①列表**自动滚动**，确认条 `Delete SSH server?` **完整可见**（含 `Cancel` + `Delete` 两个按钮）②a11y 树里能 dump 到 `Delete SSH server?` ③点 `Delete` → **行立即消失** |
| 2 | `Connected` | 上面那条连上，切到下面那条再走 1 | 同上；额外：**不需先 `Disconnect`** |
| 3 | `Connecting` | 新建条目提交后立刻 ⋮ → `Delete` | 同上；行在拨号中也能删掉 |
| 4 | `Authentication required` | **造法**：新建一条 Password 条目 → 提交后输**错误**口令（D2 步骤 5） | 同上；这是 D3 的原始场景 |
| 5 | 删除不复活 | 删完后**等 30s**，再离开 Settings 再进 | 断言：`ssh_json` 里该 id 消失（`run-as com.xiufeigo.opencodeshell cat shared_prefs/…` 核对），**行不复活、不出现迟到的卡** |
| 6 | 不回归 D1/D2/F12b/F14b | 同上轮步骤 | F14b 必须 `Accepted publickey`；D2 正确口令必须 10/10 `Connected` |

**取证要点**（给脚本）：确认条**不是 Dialog，是行内块**，没有独立 window。
别再用 `dialogNodes` 判它 —— 那正是上一轮误判的原因。请改为**在 a11y 树里
搜索 `Delete SSH server?` 文本节点**，并记录其 bounds 是否完整落在
面板可视区内。

---

## 6. 未闭环 / 已知局限

1. **★ 真机未复验**。本轮按纪律**禁止装机**，全部结论来自 a11y 树的静态读数 + 源码因果链 + JVM pin。
   §5 的 6 步**必须由测试员跑一遍才算闭环**。
2. **未验到 `Authentication required` 态下的删除**（v98 报告 §8-1 同样未覆盖）。
   原因不变：SSH 行的 ⋮ 菜单**没有 `Edit`**，改不成错口令，只能另建一条。
   §5 步骤 4 已给出造法，但本轮**没有真机执行**。
4. **未处理"删除后行不复活"的持久化路径**：`confirmSshForget` 的
   `removePersisted` 先于 `host.forget`（v92 已修、pin 在），本轮**只 pin 未改动**。
5. **F12b 残留未闭环**（v98 报告 §8-3：Cancel 后行态卡在 `Connecting over SSH…`）——
   与本缺陷无关，本轮**未触碰**，建议单独立 issue。

---

## 7. 阻塞记录：`ui/session/**` 并行改动打断编译

本轮首次跑 `:app:compileDebugKotlin` 时，主源码编译失败，**报错文件不属于本任务的写权限范围**：

```
e: …/ui/session/header/SessionHeader.kt:195:5 Function 'public' exposes its 'internal' parameter type 'TitleEditFence'.
e: …/ui/session/header/SessionHeader.kt:568:13 Unresolved reference 'discard'.
```

核实：

- `git status` 显示该文件为 ` M`（未提交的工作区改动）；
- `LastWriteTime = 2026-10-2 20:54:52`，**晚于本会话起点 20:49:32**；
- `ui/session/**` 在本任务的**禁止触碰清单**内。

⇒ 判定为**并行 agent 的在途改动**导致的全项目编译中断。按纪律本轮
**未修改该文件**（不越权、不掩盖他人工作），改为**等待其自行修复**。

**闭环结果**：该文件 `LastWriteTime` 在 **20:54:52 → 21:06:53** 之间停滞约 12 分钟，
随后由对方自行修复；本轮抢到锁后重跑 §4.1 的命令，取得
**`BUILD SUCCESSFUL in 11s` / 293 tests 0 failures**（§4.2）。

**本轮全程未修改任何 `ui/session/**` 文件，也未绕过抢锁协议**
（等待期间由对方持锁，本轮按 20s 轮询等到了自己的回合）。
