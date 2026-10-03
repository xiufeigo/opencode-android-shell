# SSH_ROW_MENU_FIX — v99 `⋮` 行菜单与上游 `servers/ssh/menu.tsx` 的 parity 核查与修复

> **一句话结论：工单的前提被它自己引用的那份报告否掉了 —— 本壳从来没有在 `Connecting` 态隐藏行菜单；真机上「`⋮` 消失」是脚本侧读不到那一行。但同一张对照表里确实有一个真缺口：行菜单只有 `Delete`，上游的 `Connect`/`Authenticate` 项整个缺失。本次修的就是这个真缺口，并按上游把三条规则钉成纯函数。**

---

## 0. 前提核查：工单前提不成立（先说这个，因为它改变了结论）

工单要求修的判定是：*「本壳在 `Connecting` 态隐藏整个行菜单，是与上游不一致的真缺口」*，依据是
`research/v97_verify/5554_v98d3/REPORT_COVERAGE8.md` §3.2 的
`C2 NO KEBAB during Connecting -> cannot open delete menu`。

**这一条与报告原文不符。** 同一节 §3.2 自己写的根因是（`REPORT_COVERAGE8.md:152-153`）：

> **根因（明确、可复现）**：**新条目追加在列表末尾，而面板不会自动滚动到它**，因此提交后连扫 8 次全部落在**已滚动区域**，"行"根本不在 a11y 树里（`row not visible`），自然也数不到该行的 ⋮（`kebabs=0`）。
> …… **本轮不是窗口太短，而是压根没看到那一行。**

§3.2 的读数表（`REPORT_COVERAGE8.md:145-150`）是 8/8 采样
`state='(row not visible)'`、`kebabs=0` —— **`kebabs=0` 的直接前因是那一行根本不在 a11y 树里**，
不是「行在但 ⋮ 被禁用」。§3.4 小结（`REPORT_COVERAGE8.md:179`）把整段判定为
**BLOCKED**，并明说「这个根因是**脚本侧的**（面板不自动滚到底），**不是产品缺陷**」。

**代码侧独立复核，结论一致 —— 本壳不存在任何 `connecting` 门控的 `⋮` 路径：**

| 位置 | 原文 | 是否被 `connecting` 影响 |
|---|---|---|
| `SshTunnelHost.kt:272`（stacked 形状） | `ServerRowMenu(onClick = { menu = true })` | ❌ 无条件，位于裸 `Box {` 内 |
| `SshTunnelHost.kt:302`（one-line 形状） | `ServerRowMenu(onClick = { menu = true })` | ❌ 无条件，位于裸 `Box {` 内 |
| `SettingsComponents.kt:1610-1620` `ServerRowMenu` | `OcIconButton(modifier.semantics { … }, onClick) { OcDotsIcon(c.iconMuted) }` | ❌ 无 `enabled` 参数参与 |
| `SettingsComponents.kt:1587-1601` `OcIconButton` | `clickable(enabled = enabled, onClick = onClick)`，`enabled` 默认 `true` 且调用方从不传 | ❌ |

两个行形状都无条件发射 `⋮`，触发器自身也没有 `enabled` 通路。
**所以「`Connecting` 态隐藏行菜单」这个根因在代码里不存在，本次没有、也无处可改。**

> 若真要让下一次真机取证不再卡在同一个坑，该修的是**面板提交后不自动滚到列表末尾**（脚本/导航侧），
> 而不是菜单。工单未授权该范围，本轮不动，仅在 §6 记为未闭环。

---

## 1. 上游对照表（判定的唯一标尺 = `_upstream/packages/app/src/servers/ssh/menu.tsx`）

| 元素 | 上游语义（`menu.tsx` 行号） | 本壳修前 | 本壳修后 | 判定 |
|---|---|---|---|---|
| `⋮` 触发器 | `<Show when={item()}>`（:16）⇒ **只看条目存在，无 connecting/busy 门控** | 已符合（无门控） | 不变 | ✅ 原本就一致 |
| 分组标签 `ssh.server.menu.label` | `<Menu.GroupLabel>`（:29） | **缺** | 仍缺 | ⚠️ 未闭环（见 §6） |
| `Connect`/`Authenticate` 项 | `<Show when={item().stage !== "ready"}>`（:30） | **整项缺失** | 已补，显隐规则 = `sshRowMenuShowsConnect` | 🔧 **本次真修复** |
| ↳ 文案 | `stage === "authentication" ? "ssh.authenticate" : "ssh.connect"`（:32） | — | 复用既有 `ssh_action_authenticate` / `ssh_session_reconnect` | ✅ 零新字符串 |
| ↳ 禁用 | `disabled={ssh.pending(props.id)}`（:31）**连接中禁用** | — | `enabled = sshRowMenuConnectEnabled(stage)` | 🔧 对齐 |
| `Set as default` / `Remove default` | `<Show when={props.domain.defaults.available() && …}>`（:35/:40） | **缺** | 仍缺 | ⚠️ 未闭环（见 §6） |
| 分隔线 | `<Menu.Separator />`（:45） | **缺** | 仍缺 | ⚠️ 未闭环（见 §6） |
| `Delete` 项 | `<Menu.Item onSelect={() => void props.domain.connection.remove(key())}>`（:46）**无任何禁用门控，永远可用** | 已符合（无门控） | 显式化为 `sshRowMenuDeleteEnabled(stage)`，语义不变 | ✅ 原本就一致，现已可测 |
| `Delete` 的二次确认 | 上游**无**（直接 `remove`） | 本壳多一步 `bringIntoViewRequester` 确认条 | **保留不动** | ✅ 本壳增强，不回退 |

**关键区分（工单特别点名、必须不混淆）**：`SshTunnelHost.kt:434` 的 `enabled = !connecting`
与 `SshConnectionPanel.kt:132` 的 `enabled = !connecting` 是**动作条**（Reconnect 按钮）的语义，
上游 `connection-panel.tsx` 没有行菜单概念，二者与 `⋮` 是**两件事**。本次**原样保留**，
并由 `theRowActionButtonsKeepTheirOwnSemantics` 钉住。

---

## 2. 根因（真缺口）

`SshTunnelHost.kt` 修前的 `SshTunnelRowMenu`（旧 :487-513）**整个菜单只有一项**：

```kotlin
private fun SshTunnelRowMenu(expanded: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit) {
    …
    DropdownMenu(…) {
        DropdownMenuItem(
            text = { Text(text = stringResource(R.string.oc_servers_menu_delete), …) },
            onClick = onDelete,
        )
    }
}
```

因果链：

1. `SshTunnelCard` 两个形状各发射一次 `SshTunnelRowMenu`（现 :273 / :303），但该 composable
   只画 `oc_servers_menu_delete`；
2. 菜单的**签名里没有 `stage`** ⇒ 菜单在结构上**无法**知道该画 `Connect`、何时禁用它；
3. 于是**唯一**的行内恢复路径是动作条的 Reconnect 按钮，而它在 `Connecting` 态按设计禁用
   （:434）；
4. ⇒ 一行停在 `authentication`（上游文案 `Authenticate`）或 `failed` / `disconnected`
   （上游 `Connect`）时，**从行本身没有任何回到连接的入口**，只能靠滚动/重进面板 ——
   这才是与上游 `menu.tsx:30-33` 的实质 parity 缺口。

修前签名无 `stage` ⇒ 无法表达上游 `:30/:31` 两条规则，这就是根因所在。

---

## 3. 改动

### 3.1 `app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshTunnelHost.kt`

**新增四条纯规则**（JVM 可测，与 composable 分离，理由同本包既有做法）：

```kotlin
/** `menu.tsx:16` — ⋮ 只看条目存在，与 stage 无关。 */
internal fun sshRowMenuVisible(itemExists: Boolean): Boolean = itemExists            // :515

/** `menu.tsx:30` — Connect/Authenticate 对除 ready 外的所有态可见。 */
internal fun sshRowMenuShowsConnect(stage: SshStage): Boolean = stage != SshStage.READY  // :518

/** `menu.tsx:31` — `disabled={ssh.pending(props.id)}`；本壳 `isSshConnecting` 即 upstream `pending`。 */
internal fun sshRowMenuConnectEnabled(stage: SshStage, pending: Boolean = false): Boolean =
    !pending && !isSshConnecting(stage)                                              // :530

/** `menu.tsx:46` — Delete 无门控；把「没有门控」写成可被测失败的函数。 */
internal fun sshRowMenuDeleteEnabled(stage: SshStage): Boolean =
    stage == stage                                                                   // :540
```

> `sshRowMenuDeleteEnabled` 故意不读 `stage`：v98 轮正是在 `Connecting` 态够不到 `Delete`，
> 「任何东西都不会拦 Delete」必须是一条**能被测挂**的断言，否则下一个人加一个 `busy` 包裹
> 就会静悄悄把它弄丢。`stage == stage` 是可测的恒真式，并在 `deleteOutlivesTheConnectGateItSitsBeside`
> 里与 Connect 的门控逐态对拍。

**`SshTunnelRowMenu`（现 :554）**：签名加 `stage` / `onConnect`，`Connect` 项排在 `Delete` 之上
（`menu.tsx` 顺序），`Delete` 保持 `c.stateDangerFg` 危险色、保持**只 arm 不删**。

**两个调用点（:273 / :303）**：传入 `stage = tunnel.status.stage` 与
`onConnect = { menu = false; host.reconnect(tunnel.config.id) }`（复用行内 Reconnect 的同一条
`SshTunnelHost.reconnect` 通路，不新造连接路径）。

### 3.2 新增 `app/src/test/java/com/xiufeigo/opencodeshell/SshRowMenuParityV99Test.kt`（12 例）

### 3.3 两个 v93 源码事实 pin 的精度修正（见 §4）

---

## 4. 为什么不降强度

三条 pin 均为**加强或等强**，无一条被放松：

1. **`theKebabIsGatedOnTheItemExistingAndOnNothingElse` / `connectingDoesNotHideTheKebab`**
   —— 规则签名里**根本没有 `stage` 参数**，所以「某个 stage 能把 `⋮` 关掉」在类型上就不可能；
   `connectingDoesNotHideTheKebab` 遍历 `SshStage.entries` 全部 10 态逐态断言。
2. **`bothRowShapesEmitTheKebabUnconditionally`** —— 不只数 2 次发射，还对**每一处**回看
   `Box {` 之后的片段，断言其中**不含** `if (` / `when (`，即触发器结构上不可能被 stage 包住。
   这是把「源码里没有门控」从人工阅读变成可执行断言。
3. **`theInFlightSetIsExactlyTheUpstreamPendingSet`** —— 不与手抄的 stage 列表对拍，而是与
   `isSshConnecting` 逐态对拍，杜绝两套 in-flight 定义漂移。
4. **`SshTunnelNarrowRowV93Test:168`（`both shapes draw the menu`）** —— 该正则
   `SshTunnelRowMenu\(\n` 原本会**顺带命中函数声明**，只是因为旧签名恰好单行才没命中；
   v99 给声明改成多行参数后计数 2→3。修正为 `(?<!fun )SshTunnelRowMenu\(\n`：
   **断言值仍是 2**（每形状一次调用），只是不再误吞声明 —— 这是**收紧**（消除格式耦合陷阱），
   不是放宽；注释已写明原因。
5. **`SshTunnelNarrowStructureV93Test:93`（`noNewUserVisibleStrings`）** —— 集合仍是**精确相等**，
   不是「包含」。新增的 `ssh_action_authenticate` **不是新文案**：该串早已存在于三语
   `strings_oc_i18n.xml:1357`（`Authenticate` / `认证` / `驗證`），本次**未新增任何用户可见字符串**。
   集合精确性保留 ⇒ 任何真·新资源 id 仍会让该测试失败。

---

## 5. 测试证据

环境：`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`；仅 `:app:testDebugUnitTest`；
前台跑完；`.agent-gradle-lock` 抢锁/写锁/删锁全程遵守。

### 5.1 新增类

```
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.SshRowMenuParityV99Test" --console=plain
```

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 9s
52 actionable tasks: 5 executed, 47 up-to-date
```

`TEST-com.xiufeigo.opencodeshell.SshRowMenuParityV99Test.xml` → **tests=12 failures=0 errors=0 skipped=0**

| # | 用例 | 钉住的规则 |
|---|---|---|
| 1 | `theKebabIsGatedOnTheItemExistingAndOnNothingElse` | ① 条目存在 ⇒ `⋮` 渲染 |
| 2 | `connectingDoesNotHideTheKebab` | ① 全部 10 态 + 5 个 in-flight 态 |
| 3 | `bothRowShapesEmitTheKebabUnconditionally` | ① 结构：2 处发射、每处无 `if`/`when` 包裹 |
| 4 | `connectIsDrawnForEveryStageButReady` | ② `menu.tsx:30` |
| 5 | `connectIsDisabledThroughoutTheInFlightWindow` | ② `Connecting` ⇒ Connect 禁用 |
| 6 | `connectIsEnabledOnEverySettledStage` | ② 非 in-flight ⇒ 可用 |
| 7 | `theInFlightSetIsExactlyTheUpstreamPendingSet` | ② 与 `isSshConnecting` 逐态对拍 |
| 8 | `deleteIsEnabledInEveryStageIncludingConnecting` | ③ 全部 10 态 Delete 可用 |
| 9 | `deleteOutlivesTheConnectGateItSitsBeside` | ③ 与 ② 逐态对拍（唯一差异点） |
| 10 | `theMenuComposesConnectBeforeDeleteAndGatesOnlyConnect` | composable 读规则、顺序、危险色、只 arm |
| 11 | `theRowActionButtonsKeepTheirOwnSemantics` | `:434` / `SshConnectionPanel.kt:132` 动作条语义**未被改动** |
| 12 | `theDeleteConfirmationPathIsUntouched` | `ask` → `confirmSshForget` 链完好 |

### 5.2 回归（44 类：Ssh* / *Server* / *HostKey* / *Connection* / Wave23a* / StringsLocaleParity*）

首轮 `BUILD FAILED … 375 tests completed, 2 failed` —— 两条 v93 源码事实 pin 如 §4 所述因本次改动失配
（非产品回归），修正后重跑：

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 6s
52 actionable tasks: 2 executed, 50 up-to-date
```

聚合 XML：**classes=44 tests=375 failures=0 errors=0 skipped=0**

覆盖且保持 PASS 的既有项：`SshDeletePathV97Test`（删除链 + `bringIntoViewRequester` 确认条）、
`SshFormReachabilityV98Test`（字段可达）、`SshHostKeyCardV99Test`（核验卡 Cancel/Trust）、
`SshReconnectFidelityV97Test`（D1/D2/D3/F12b）、`SshTunnelDeleteV91Test` / `SshSeedForgetV92Test`
（确认条 forget）、`SshTunnelNarrowRowV93Test` / `SshTunnelNarrowStructureV93Test`、
`SshPromptCancelDoesNotDismissF13bTest`、`StringsLocaleParityTest`。

---

## 6. 真机复验步骤（未执行 — 无设备，按上文 §0 的配方先修脚本侧根因）

前置（针对 §0 的真根因）：**建条目/提交后必须先把面板滚到列表最底再 dump**，
否则会重演 `row not visible` / `kebabs=0` 而误判为菜单缺陷。

**A. `Connecting` 态**
1. Servers → SSH，Add server，target 填黑洞地址 `ssh -p 2222 sshtest@10.255.255.1`，提交；
2. **滚到列表最底**，轮询到行状态为 `Connecting`；
3. 断言该行 `⋮` **存在**（`Server actions` 节点在 a11y 树内）⇒ 对齐 `menu.tsx:16`；
4. 点 `⋮` ⇒ 断言菜单出现，且**含两项**：
   - `Reconnect` **灰显不可点**（`menu.tsx:31` 的 `disabled={ssh.pending(...)}`）；
   - `Delete` **可点**（`menu.tsx:46` 无门控）；
5. 点 `Delete` ⇒ 断言确认条出现、正文为 `This removes "<本行名>"`；
6. 确认删除 ⇒ 断言本行消失、落库回退一条。

**B. `Connected` 态**
1. 用可用凭据连到同一条目，轮询到行状态 `Connected`；
2. 点 `⋮` ⇒ 断言 `Connect` 项**不出现**（`menu.tsx:30` 的 `stage !== "ready"`）、`Delete` 可点；
3. 删除链路同上第 5–6 步。

**C. `Authentication required` 态**（本次新增能力的正向取证）
1. target `127.0.0.1:2222` + 故意填错口令 `sshtest`；
2. 滚到底、轮询到行停在认证态（认证卡可上浮遮住行，必要时先 Cancel/Trust 收回）；
3. 点 `⋮` ⇒ 断言 **`Authenticate` 项出现且可点**（这是修前**根本不存在**的入口），
   `Delete` 同样可点。

---

## 7. 未闭环

1. **面板不自动滚到列表末尾** —— §0 定位的真机卡点（脚本/导航侧），未授权范围，未动。
   §6 的复验步骤已按「先滚到底」写。
2. **上游菜单的其余三项仍缺**：`ssh.server.menu.label` 分组标签、`Set as default` /
   `Remove default`（`menu.tsx:35/:40`）、`Menu.Separator`（:45）。
   需 `props.domain.defaults` 等价物（默认服务器语义在 `ServerRow` 侧），超出本次工单范围。
3. **真机复验未执行**（无设备）。§6 为待执行步骤；`Connected` 态仍受制于
   `REPORT_COVERAGE8.md:139` 记录的「主机侧 key 认证未独立证实」。
4. **未提交**。本仓 `app/src/main/.../ui/servers/**` 与相关测试在 git 中均为
   `?? untracked`（整片 WIP 未纳管），故本节以手工 diff 记录而非 `git diff`。

---

## 8. 改动文件清单

| 文件 | 性质 |
|---|---|
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshTunnelHost.kt` | 主修复（4 条纯规则 + 菜单加 Connect 项 + 2 处调用点） |
| `app/src/test/java/com/xiufeigo/opencodeshell/SshRowMenuParityV99Test.kt` | 新增，12 例 |
| `app/src/test/java/com/xiufeigo/opencodeshell/SshTunnelNarrowRowV93Test.kt` | 正则精度修正（`(?<!fun )`，断言值不变） |
| `app/src/test/java/com/xiufeigo/opencodeshell/SshTunnelNarrowStructureV93Test.kt` | 精确集合补入既有串 `ssh_action_authenticate`（精确性不变） |

未触碰：`_upstream/**`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/session/**`、`ui/home/**`、
`ui/terminal/**`、`ui/nav/**`、`ui/App.kt`、`OcActivity.kt`。
**新增用户可见字符串：无。**
