# HOST_KEY_CARD_FIX — `Verify host identity` 核验卡卡死（Cancel / Trust and connect 均无效）

**轮次**：v99 修复（承接 `research/v97_verify/5554_v98d3/REPORT_COVERAGE6.md` §2.2 / §3）
**日期**：2026-10-03
**修复人**：修复工程师（Verify host identity 核验卡核验）
**仓库**：`C:\sensorsdata\main\program\opencode-android-shell`
**一句话结论**：**两个按钮无效是同一个根因** —— `SshConnection.cancelPrompt()` 先把这次拨号 attempt **静音**（v97 F12b/D1 的修复），再让这个**已被静音的 attempt** 去发布"撤下核验卡"的那唯一一个事件（`PromptResolved`）。而 `emit` 会丢弃静音 attempt 的**一切**事件，于是撤卡事件被丢在地上：卡永远不消失、行永远停在 `CONNECTING`、宿主面板被这张不走的卡一直压着。`Trust and connect` 跟着一起变成空操作，是因为它面对的那张卡已经属于一次"已经把答案取走、消费者已经离场"的拨号。

---

## 1. 定性

### 1.1 核验卡是谁画的、退出条件是什么

| 项 | 位置 | 原文 |
|---|---|---|
| 卡片标题 | `app/src/main/res/values/strings.xml:882` | `<string name="oc_ssh_host_key_title">Verify host identity</string>` |
| 卡片 composable | `ui/servers/SshAuthenticate.kt:72-84` | `fun SshAuthenticate(prompt, hostKey, waiting = false, onTrust, onContinue, onCancel, …)` |
| 宿主分支 | `ui/servers/SshDialog.kt:278-279` | `val prompt = status.prompt` ／ `if (prompt != null) {` |
| 提前返回 | `ui/servers/SshDialog.kt:304` | `return`（表单分支 `val busy = isSshConnecting(status.stage)` 在 307 行，**永远到不了**） |
| 按钮定义 | `ui/servers/SshAuthenticate.kt:214-225` | Cancel `onClick = onCancel`；Trust `onClick = { if (prompt.confirm) onTrust() else onContinue(response) }` |
| 回调接线 | `ui/servers/SshDialog.kt:283` / `:296-298` | `onTrust = { connection?.respondTrust(true) }` ／ `onCancel = { connection?.cancelPrompt() }` |

**owner 与生命周期**：核验卡的 owner 是**连接状态机**（`SshConnection._state` → `reduceSshConnection`），**不是**面板、也不是全局。`SshDialog` 只是 `status.prompt != null` 时才画它（`LaunchedEffect(connection) { connection?.state?.collect { status = it } }`，`SshDialog.kt:189-191`）。
**退出条件只有一个**：`status.prompt == null`。`PromptResolved` 是唯一把 `prompt`/`hostKey` 清成 null 的事件（`SshConnectionModel.kt:345-346`）。

### 1.2 因果链（Cancel 为什么无效）

`ui/servers/SshConnection.kt:209-213`（修前原文）：

```kotlin
fun cancelPrompt() {
    val prompt = _state.value.prompt ?: return
    guard.cancel(guard.current())                                    // ← 先静音
    answers.offer(PromptAnswer(prompt.id, accepted = false, value = null))
}                                                                   // ← 撤卡事件靠拨号线程去发
```

而撤卡事件只能由拨号线程发出 —— `SshConnection.kt:257-262`：

```kotlin
private fun awaitAnswer(attempt: Int, prompt: SshPrompt): PromptAnswer {
    val answer = answers.poll(PROMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        ?: PromptAnswer(prompt.id, accepted = false, value = null)
    emit(attempt, SshConnectEvent.PromptResolved(prompt.id))   // ← 撤卡就在这里
    return answer
}
```

`emit` 的第一行 —— `SshConnection.kt:220-221`：

```kotlin
private fun emit(attempt: Int, event: SshConnectEvent) {
    if (!guard.speaks(attempt)) return        // ← 静音 attempt 的一切事件在此被丢弃
```

**完整链条**：

1. 用户点 `Cancel` → `SshDialog.kt:296` → `cancelPrompt()`；
2. `guard.cancel(guard.current())` 把本次 attempt 加入 `cancelled`（`SshConnectionModel.kt:194-196`）；
3. `answers.offer(accepted = false)` 唤醒停在 `answers.poll(600s)` 的拨号线程；
4. 拨号线程走 `awaitAnswer` → `emit(attempt, PromptResolved)` → **`guard.speaks(attempt) == false` → 直接 return，事件被丢弃**；
5. `prompt` 与 `hostKey` **永远不变** → `SshDialog.kt:279` 的 `if (prompt != null)` 永远成立 → **核验卡永远不走**；
6. `promptYesNo` 返回 `false` → jsch 抛 `reject HostKey` → `connect()` 的 `catch` 走 `fail(attempt, …)` → `emit(attempt, Fail(...))` → **同样被静音丢弃** → **行永远停在 `CONNECTING`**。

这与真机读数完全一致：REPORT_COVERAGE6.md §2.2 记 `14 次连续采样（10:36:29→10:37:31）全部停在 Connecting`、`STATES_SEEN=Connecting`、`cardUp 仍为 True`。

### 1.3 因果链（Trust and connect 为什么也无效）

关键在**操作顺序**：REPORT_COVERAGE6.md §1.6 的日志显示 **Cancel 先被点过**（`[10:33:31] trust card up -> Cancel @ 496,1832`），§2.2 的 `Trust and connect` 采样发生在 10:36 之后 —— 也就是**面对一张已经死掉的卡**。

`respondTrust`（`SshConnection.kt:179-183`，修前）：

```kotlin
fun respondTrust(accepted: Boolean) {
    val prompt = _state.value.prompt ?: return   // 卡片没撤，所以这里拿得到"陈旧"的 prompt
    if (!prompt.confirm) return
    answers.offer(PromptAnswer(prompt.id, accepted, null))   // ← 投进一个没有消费者的队列
}
```

`answers` 是 `ArrayBlockingQueue<PromptAnswer>(1)`（`SshConnection.kt:61`）。Cancel 之后：拨号线程在第 3 步就已经**取走了答案并离开**（随后抛异常退出 `connect()`）。于是这次 `offer` 投进了一个**再也不会被 `poll` 的队列** —— 一次在 UI 上不可能被观测到的 tap。

**注意**：`Trust and connect` 在一张**新鲜**的卡上是好的（`respondTrust` → `poll` 取到 → `emit(PromptResolved)`（未静音，能过）→ 撤卡 → jsch `add()` 写 `known_hosts` → 继续）。所以它是 §1.2 的**后果**，不是第二个独立缺陷；但"offer 静默进死队列"本身是真缺陷，§2 一并修掉。

### 1.4 逐条排除（任务要求的七项）

| # | 假设 | 判定 | 依据 |
|---|---|---|---|
| 1 | `enabled = false` | ❌ **排除** | `SshAuthenticate` 的 `waiting` 默认 `false`（`SshAuthenticate.kt:76`），`SshDialog.kt:279-303` **根本没传**；两个按钮都是 `enabled = !waiting`（`:217`、`:223`）⇒ 恒为 true。真机 dump 里两个按钮也都在树中。 |
| 2 | `onClick` 为空 | ❌ **排除** | 两个回调都实接到 `SshConnection`（`SshDialog.kt:283`/`:296-298`），且 `connection` 在 `submit()` 里已赋值（`SshDialog.kt:263`）。**它们被调用了，只是效果被丢弃**。 |
| 3 | `remember` 状态未失效 | ❌ **排除** | `response by remember(prompt.id)`（`SshAuthenticate.kt:89`）只服务口令框，与卡的去留无关；卡的去留读的是 `SshConnection._state`。 |
| 4 | `LaunchedEffect` 未重启 | ❌ **排除** | `LaunchedEffect(connection) { … collect … }`（`SshDialog.kt:189-191`）正常收集；状态**有**在变（`Start`/`Prompted` 都进去了），只是撤卡那一步的事件被丢了。 |
| 5 | pluggable 回调未注册 | ❌ **排除** | jsch `userInfo = SshUserInfo(repository, attempt)` 已注册（`SshConnection.kt:119`），`promptYesNo` 确实被调用过（卡能弹出来就是它调的）。 |
| 6 | `known_hosts` 写入失败被静默吞 | ❌ **排除** | 写入在 `KnownHostsRepository.persist`（`SshConnection.kt:393-394`），只有 jsch 在 `promptYesNo == true` **之后**才 `add`。本轮从未走到那一步 —— 是**根本没被调用**，不是调了但失败。 |
| 7 | 异常路径没复位 UI | ✅ **命中** | 这就是根因本身：§1.2 第 4、6 步，`connect()` 的 `catch` 分支里 `fail()` 的 `Fail` 事件被静音 guard 丢弃，**没有任何一条异常路径能把 UI 复位**。 |

**结论：唯一根因 = 静音 guard 吞掉了撤卡事件（`emit` 的第一行）。** 排除项 6/7 之外全部排除。

### 1.5 为什么它阻断宿主面板

两点叠加，都不是"面板被 disable"：

1. **卡盖在面板上**：`SshDialog` 是 Servers 面板根的子节点（`ui/settings/ServersPanel.kt:312-313`，被 `ServersFormImeBandV83Test:251` 钉住），而核验卡是它的**提前返回分支**（`SshDialog.kt:304`）—— 卡在画时，表单分支（`Add server` 页脚）根本不参与组合，卡就压在面板 body 上。
2. **`Add SSH server` 点了也不开门**：`ServersPanel.kt` 用 `addingSsh` 状态挂这个对话框，只要它还是 `true`，同一个 tap 就不可能再挂第二张卡 —— 这正是 REPORT_COVERAGE6.md §1.6 里 `tap Add SSH server @ 672,948` → `FAIL: form did not open` 的读法（不是报错，是"门已经开着了，里面卡着一张不走的卡"）。

加上 §1.2 第 5、6 步让**行永远停在 `CONNECTING`**（`isSshConnecting(CONNECTING) == true`，`SshModel.kt:37-51`），行内控件一直是"连接中"形态。

---

## 2. 修复

### 2.1 改了什么（最小、可解释）

**`ui/servers/SshConnectionModel.kt`** —— 新增两个纯函数（给 JVM 套件钉状态机，不需要 jsch）：

```kotlin
fun sshCancelSettles(status: SshConnectionStatus, promptId: String): SshConnectionStatus =
    reduceSshConnection(reduceSshConnection(status, SshConnectEvent.PromptResolved(promptId)), SshConnectEvent.Disconnect)

fun sshPromptIsCurrent(status: SshConnectionStatus, promptId: String): Boolean =
    status.prompt?.id == promptId
```

**`ui/servers/SshConnection.kt` —— `cancelPrompt()`**：撤卡改成**同步直写**，不再依赖被静音的拨号线程去发事件（静音规则本身一字未动）：

```kotlin
 fun cancelPrompt() {
     val prompt = _state.value.prompt ?: return
-    guard.cancel(guard.current())
-    answers.offer(PromptAnswer(prompt.id, accepted = false, value = null))
+    // v99: the attempt is muted **first** so nothing it raises afterwards is
+    // published (that is the F12b/D1 fix and it stays), and the challenge
+    // is then withdrawn **synchronously** through [sshCancelSettles]
+    // instead of through the muted attempt's own `PromptResolved`.
+    guard.cancel(guard.current())
+    answers.offer(PromptAnswer(prompt.id, accepted = false, value = null))
+    _state.update { sshCancelSettles(it, prompt.id) }
 }
```

**`ui/servers/SshConnection.kt` —— `respondTrust` / `respondPrompt`**：只回答**当前在屏上**的那一个 challenge，不再往死队列里投：

```kotlin
 fun respondTrust(accepted: Boolean) {
-    val prompt = _state.value.prompt ?: return
+    val status = _state.value
+    val prompt = status.prompt ?: return
     if (!prompt.confirm) return
-    answers.offer(PromptAnswer(prompt.id, accepted, null))
+    if (!sshPromptIsCurrent(status, prompt.id)) return   // v99
+    answers.offer(PromptAnswer(prompt.id, accepted, null))
 }
```

（`respondPrompt` 同样加了这一道。）

### 2.2 修复后的行为

- **点 `Cancel`** → `prompt`/`hostKey` 立刻 null（卡**立即消失**）→ 行落到 `SshStage.DISCONNECTED`（**不再 connecting**）→ 表单分支恢复组合、`busy == false` → 宿主面板可交互；
- **点 `Trust and connect`** → 新鲜卡照旧推进（写 `known_hosts` → 继续认证）；陈旧卡**被明确拒绝**而不是静默入队（可观测地"什么都没发生"，但那背后是一个已被丢弃的拨号，不是静默吞异常）；
- 静音 guard **完全保留**：`aCancelledAttemptNeverSpeaksAgain` 钉住"取消后的 attempt 不再发布任何挑战"（F12b/D1 的 endless re-pop 不会回来）。

---

## 3. 为什么没有放宽安全边界

| 安全不变量 | 修前 | 修后 |
|---|---|---|
| 未知/变更主机键**必须**弹核验 | 是 | **是，一字未动**（`StrictHostKeyChecking=ask` 仍在 `SshConnection.kt:118`；`theVerificationCardIsStillMandatoryForAnUnknownKey` 钉住） |
| **不许自动信任** | 无自动信任路径 | **仍然没有**：唯一的信任入口仍是 `respondTrust` → jsch `promptYesNo == true` → `KnownHostsRepository.add`。本次**没有新增任何**自动写 `known_hosts` 的分支 |
| **未信任不得静默连接** | 成立 | **仍然成立**：`sshCancelSettles` 的第二条是 `SshConnectEvent.Disconnect`（不是 `Ready`）；`anUntrustedHostNeverReachesReady` 逐条钉住 cancel / 拒绝 / 陈旧答案三条腿**都到不了 `READY`**，唯一入口是真实的 `Ready` 事件 |
| 陈旧 challenge 的信任 | 静默进死队列 | **被显式拒绝**（`sshPromptIsCurrent`）—— 这条比修前**更严**：一个不属于当前拨号的信任现在连队列都进不去 |
| 取消后的 attempt 静默 | 是 | **是，且未改**（F12b/D1 的修复原样保留） |

**没有新增任何用户可见字符串**（`StringsLocaleParityTest` 3/3 通过；本次三语资源零改动）。
**没有触碰** `_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/session/**`、`ui/home/**`、`ui/terminal/**`、`ui/nav/**`、`ui/App.kt`、`OcActivity.kt` —— 改动只落在 `ui/servers/**` 与一个新测试文件。

---

## 4. 测试证据

### 4.1 新增 JVM pin

`app/src/test/java/com/xiufeigo/opencodeshell/SshHostKeyCardV99Test.kt`（7 例，纯 JVM，无 jsch、无设备）：

| # | 用例 | 钉住什么 |
|---|---|---|
| 1 | `cancelLeavesVerificationAndTheHostGoesInteractive` | **(核验中, Cancel) → 离开核验态且宿主可交互**：`prompt == null`（= 卡消失）、`hostKey == null`、`!isSshConnecting(stage)`、`stage == DISCONNECTED` |
| 2 | `aCancelledAttemptNeverSpeaksAgain` | 静音语义未被削弱（`SshAttemptGuard.speaks` / `sshAttemptSpeaks`），且新拨号不被旧 cancel 静音 |
| 3 | `trustResolvesTheChallengeAndKeepsAdvancingTheDial` | **(核验中, Trust) → 进入连接推进态**：`prompt == null` 且 `stage == CONNECTING`，并能真正抵达 `READY` |
| 4 | `aTrustForAChallengeThatIsNotTheCurrentOneIsRefused` | 陈旧卡不得回答当前 challenge |
| 5 | `theRealConnectionRefusesAStaleTrustInsteadOfQueueingIt` | 真 `SshConnection` 上，无卡时 cancel/trust 全是 no-op（F13b 形状复读） |
| 6 | `anUntrustedHostNeverReachesReady` | **安全不变量**：cancel / 拒绝信任 / 只有真 `Ready` 能到 `READY` |
| 7 | `theVerificationCardIsStillMandatoryForAnUnknownKey` | 未知键**仍然**必须弹核验（没被"自动信任"绕过） |

### 4.2 命令与结果

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.SshHostKeyCardV99Test" --console=plain
```

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 4s
52 actionable tasks: 2 executed, 50 up-to-date
```

回归（新类 + grep 找齐的 servers/SSH 既有类）：

```
.\gradlew.bat :app:testDebugUnitTest --tests "…SshHostKeyCardV99Test" --tests "…SshConnectionModelTest" `
  --tests "…SshReconnectFidelityV97Test" --tests "…SshPromptCancelDoesNotDismissF13bTest" `
  --tests "…SshPromptOfferV94Test" --tests "…SshAuthenticateModelTest" --tests "…SshModelTest" `
  --tests "…SshDialogRenderTest" --tests "…SshSubmitChainTest" --tests "…SshDeletePathV97Test" `
  --tests "…SshFormReachabilityV98Test" --tests "…SshTunnelWiringTest" --tests "…SshTunnelHostModelTest" `
  --tests "…SshSeedForgetV92Test" --tests "…SshTunnelDeleteV91Test" --tests "…SshTunnelNarrowRowV93Test" `
  --tests "…SshTunnelNarrowStructureV93Test" --tests "…SshPanelTunnelTest" --tests "…SshEd25519IdentityF14Test" `
  --tests "…SshKeyFileFailureF14Test" --tests "…SshPassphraseAnswerV94Test" --tests "…SshImeNextRoutingF13aTest" `
  --tests "…StringsLocaleParityTest" --tests "…Wave23aSettingsPaletteTerminalTest" --console=plain
```

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 4s
52 actionable tasks: 1 executed, 51 up-to-date
```

### 4.3 用例数（读 `app/build/test-results/testDebugUnitTest/*.xml`）

| 测试类 | 用例 | 失败 |
|---|---:|---:|
| **SshHostKeyCardV99Test（新）** | **7** | 0 |
| SshReconnectFidelityV97Test | 20 | 0 |
| SshPromptOfferV94Test | 14 | 0 |
| SshSeedForgetV92Test | 14 | 0 |
| SshDeletePathV97Test | 12 | 0 |
| SshFormReachabilityV98Test | 11 | 0 |
| SshSubmitChainTest | 10 | 0 |
| SshPromptCancelDoesNotDismissF13bTest | 9 | 0 |
| SshTunnelDeleteV91Test | 9 | 0 |
| SshConnectionModelTest | 8 | 0 |
| SshAuthenticateModelTest | 8 | 0 |
| SshTunnelNarrowStructureV93Test | 8 | 0 |
| SshPassphraseAnswerV94Test | 8 | 0 |
| SshTunnelWiringTest | 7 | 0 |
| SshPanelTunnelTest | 7 | 0 |
| SshKeyFileFailureF14Test | 7 | 0 |
| SshImeNextRoutingF13aTest | 7 | 0 |
| SshModelTest | 6 | 0 |
| SshDialogRenderTest | 5 | 0 |
| SshTunnelHostModelTest | 4 | 0 |
| SshEd25519IdentityF14Test | 4 | 0 |
| Wave23aSettingsPaletteTerminalTest | 25 | 0 |
| StringsLocaleParityTest | 3 | 0 |
| **合计** | **225** | **0** |

> **`SshTunnelForgetV92Test` 不存在这个类**（grep 确认）。删除确认条的 `bringIntoViewRequester` pin 分布在 `SshTunnelDeleteV91Test`、`SshSeedForgetV92Test`、`SshDeletePathV97Test` 三个类里（都 `source("…/SshTunnelForget.kt")`），**三个全绿**。

**既有 PASS 未被破坏的逐条对账**：

| 既有 pin | 类 | 结果 |
|---|---|---|
| `SshTunnelForget` 删除确认条 `bringIntoViewRequester` | SshTunnelDeleteV91Test / SshSeedForgetV92Test / SshDeletePathV97Test | ✅ 9 / 14 / 12 |
| `SSH_FORM_REACHABILITY_FIX` 字段可达性 | SshFormReachabilityV98Test | ✅ 11 |
| `SSH_RECONNECT_FIX` D1/D2/D3/F12b | SshReconnectFidelityV97Test | ✅ 20 |
| `onCancel` 不 dismiss（F13b） | SshPromptCancelDoesNotDismissF13bTest | ✅ 9 |
| 三语字符串齐平 | StringsLocaleParityTest | ✅ 3 |

---

## 5. 真机复验步骤（emulator-5554 / Pixel_9a）

> 前置：装上带本次修复的 APK（**需 `assembleDebug` + 装机，本轮按纪律未执行 —— 由复验人执行**）。设备上 sshd 仍需可达（`adb reverse tcp:2222->tcp:22` 或既有 `127.0.0.1:2222`）。

**A 段 —— Cancel 真的取消且宿主恢复可交互**

1. Settings → Servers → `Add SSH server`；
2. 填 target `ssh -p 2222 sshtest@127.0.0.1`、name `E2ECHK5554hk-1`、口令 `keypass123`（**注意**：`REPORT_COVERAGE6.md` §1.3 的残余缺陷仍在 —— 口令框要再往下滚 2 步才点得中）；
3. 点 `Add server` → 断言 **`Verify host identity` 卡出现**（`[127.0.0.1]:2222` / `ssh-ed25519` / MD5 指纹）；
4. 运行期解析 `Cancel` 节点中心并 tap；
5. **断言**：`uiautomator dump` 里 **不再有** `Verify host identity` 文本节点（卡消失）；
6. **断言**：面板行不再是 `Connecting over SSH…`，而是静止态（`Disconnected`）；
7. **断言**：`Add SSH server` 节点 `clickable=true` 且点击后**表单能打开**（不再 `form did not open`）；
8. **断言**：行内 `Server actions` ⋮ 节点 `clickable=true`，点得开 ⋮ 菜单（这条直接解掉 D3-3/D3-4 的共同前置阻塞）。

**B 段 —— Trust and connect 真的推进**

9. 回到 A 段建好的那条（或再建一条 `E2ECHK5554hk-2`）；
10. **断言**核验卡再次出现；
11. 运行期解析 `Trust and connect` 节点中心并 tap；
12. **断言（诚实失败也算通过）**：
    - **推进**：`known_hosts` 文件（`files/ssh/known_hosts`）新增一行该主机键，**且** UI 进入认证态（`Password:` 挑战卡）或终态（`Connected` / `Authentication required` / 失败文案）—— 任一都算"状态机前进了"；
    - **诚实失败**：连接最终落到某个**明确终态**（不是"卡还在、一直 Connecting"）；
13. **断言**：无论前进还是失败，卡都已消失、⋮ 仍可点。

**C 段 —— 连续建条目（原先 1/4 拿不到的那一段）**

14. 不重启应用，连续建第 2、3、4 条；每次都在核验卡上点 `Cancel`；**断言**每条都能建出来（行数 1→2→3→4），且每张卡都能用 Cancel 关掉。

**D 段 —— 清理**：⋮ → Delete → 确认，删净 `E2ECHK5554hk*`；落库按 **§1.5 的正确口径**（隧道键 value 长度 / 文件字节数 / 快照 MD5，**不是**数 `<string>` 行数）。

---

## 6. 未闭环 / 留给下一轮

1. **对话框在 Cancel 后仍然开着**（这是设计约束，不是遗漏）。`SshPromptCancelDoesNotDismissF13bTest.theDialogsCancelAnswersTheChallengeAndNeverDismisses` 明确钉住 `onCancel` **不得**含 `onDismiss`（F13b：当年的 bug 就是 dismiss 触发了 `DisposableEffect` 的 `cancel()+disconnect()`，把一次本来会成功的连接在 READY 前拆掉）。所以点 Cancel 之后：`SshDialog` 从卡分支**回到表单分支**（表单可继续编辑/重提交），但对话框本身仍在，`Add SSH server` 要等用户再点一次表单页脚的 `Cancel` 才重新可点。
   **对本轮目标的影响**：D3-3/D3-4 的前置阻塞**已解开**（不再需要 `am force-stop`），但"面板立即可点 `Add SSH server`"的严格读法要**两跳**（Cancel 关卡 → 再 Cancel 关对话框）。若下一轮认为必须一跳，需要单独评估放宽 F13b 的那条 pin 及其回归面 —— **本轮不动它**。
2. **"Trust 在新鲜卡上有效"未被真机单独取证**。本轮的静态分析结论是：真机那一次 Trust 是在 Cancel 之后点的（§1.3），所以它测的是死卡。B 段第 12 步会第一次真正验这条。**JVM 侧只钉到"状态机推进"，钉不到 jsch 是否真的写进了 `known_hosts`**（纯 JVM 无 jsch/无设备）。
3. **`Trust and connect` 后的"诚实失败"文案没有回归**。若 `127.0.0.1:2222` 无可用对端/口令不匹配，预期落到 `Authentication required` 或 `Failed`；具体落到哪一态取决于 sshd，`REPORT_COVERAGE6.md` §2.2 也标了"**未被证实**，不下结论"。B 段第 12 步以读数为准。
4. **口令框残余缺陷未修**（`REPORT_COVERAGE6.md` §4.2：进树了但可能擦着裁切线停住，中心点落进页脚带点不中）。与本卡无关，属 v98 表单几何，**未纳入本轮**。
5. **本轮改动未提交**。`SshConnection.kt` / `SshConnectionModel.kt` 在本仓库里是**未跟踪**状态（`git status` 为 `??`，这两个文件从未进过版本库），所以 `git diff` 取不到，§2.1 的 diff 是按实际改动逐字给出的。是否要把这批 SSH 源文件纳入版本管理，由你决定。

---

## 7. 附：关键文件:行号索引

| 位置 | 内容 |
|---|---|
| `ui/servers/SshAuthenticate.kt:72-84` | 核验卡 composable 签名（`waiting` 默认 `false`） |
| `ui/servers/SshAuthenticate.kt:214-225` | Cancel / Trust 两个按钮的 `enabled` 与 `onClick` |
| `ui/servers/SshDialog.kt:278-304` | prompt 分支：卡的条件渲染 + 提前返回（卡的存在即阻断） |
| `ui/servers/SshDialog.kt:283` / `:296-298` | `onTrust` / `onCancel` 接线 |
| `ui/servers/SshConnection.kt:61` | `answers = ArrayBlockingQueue<PromptAnswer>(1)`（死队列的来源） |
| `ui/servers/SshConnection.kt:179-200` | `respondTrust` / `respondPrompt`（本次加 `sshPromptIsCurrent`） |
| `ui/servers/SshConnection.kt:209-235` | `cancelPrompt`（本次加 `sshCancelSettles` 同步撤卡） |
| `ui/servers/SshConnection.kt:220-221` | **根因行**：`emit` 的 `if (!guard.speaks(attempt)) return` |
| `ui/servers/SshConnection.kt:257-262` | `awaitAnswer` —— 撤卡事件唯一的发送点 |
| `ui/servers/SshConnectionModel.kt:166-207` | 新增 `sshCancelSettles` / `sshPromptIsCurrent` |
| `ui/servers/SshConnectionModel.kt:345-346` | `PromptResolved` 腿（`prompt = null` = 卡消失） |
| `ui/servers/SshConnectionModel.kt:194-199` | `SshAttemptGuard.cancel` / `speaks`（静音规则，未改） |
| `app/src/test/java/…/SshHostKeyCardV99Test.kt` | 新增 7 例 JVM pin |
