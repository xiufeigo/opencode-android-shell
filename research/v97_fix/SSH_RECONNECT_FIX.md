# v97 修复报告 — SSH 重连保真（D1 / D2 / D3 / F12b 回归）

**执行人**：修复工程师（SSH 域）  **日期**：2026-10-02
**仓库**：`C:\sensorsdata\main\program\opencode-android-shell`
**输入**：`research/v97_verify/5554_f14/REPORT.md`（F14 真机终验）
**F14a / F14b 未被触碰**（见 §6）。

---

## 0. 结论先说

> 三个缺陷 + 一条回归**共用同一个缺失事实**：**jsch 的回调属于哪一次拨号尝试（attempt）没有被记录**。
>
> `SshConnection` 每次 `connect()` 都开一条新协程、互不相识，于是：
> - **D1 / F12b** — Cancel 只回了一句"这个方法失败了"，jsch 于是**换下一个认证方法**；下一个方法铸出一个**新的 promptId**，而 offer 规则按 promptId 去重（`SshPromptOffer`），于是这张卡"从没被 offer 过"，**无限重弹**，且已经把 publickey 静默降级成 keyboard-interactive。
> - **D2** — 第二次 Reconnect 在第一次还卡在 `answers.poll(600 s)` 时又开了一条；赢的那条发 `Ready`，输的那条稍后醒来发 `Fail`，**覆盖在同一个状态对象上**。
> - **D3** — `forget` 拆了 session，却**没有作废那条停泊的协程**，它继续为一个刚被删掉的条目发状态、继续弹卡。
>
> 修复 = **一个 attempt 世代守卫**（`SshAttemptGuard`）+ 两条纯规则。改动面 3 个 main 文件 + 2 个测试文件，**无新增用户可见字符串**。

| 项 | 结果 |
|---|---|
| JVM 回归 | **216 tests, 0 failures, 0 errors** — `BUILD SUCCESSFUL` |
| 新增 pin | `SshReconnectFidelityV97Test` **20 用例全绿** |
| 新增字符串 | **0**（故三语资源未动，`StringsLocaleParityTest` 仍绿） |
| 真机复验 | **未做**（本轮不装机）→ 步骤见 §5，**这是最大未闭环项** |

---

## 1. 逐条根因

### D1（高）已保存条目的 Reconnect 不复用私钥 → 退化为口令卡

**先排除一个错误假设**：不是存储丢失。`ServerConfigStore.encodeSshEntries`（`ServerConfigStore.kt:672-673`）确实落盘了 `authKind` 与 `privateKeyPath`，`decodeSshEntries`（`:694-705`）也读回来了，`toRestingTunnel()`（`SshTunnelForget.kt:108-120`）把二者映射回 `SshAuthMethod.KEY` / `privateKeyPath`。**配方是完好的。**

**真因**在 `SshConnection.cancelPrompt()`（改前 `SshConnection.kt:164-167`）：

```kotlin
fun cancelPrompt() {
    val prompt = _state.value.prompt ?: return
    answers.offer(PromptAnswer(prompt.id, accepted = false, value = null))
}
```

`accepted = false` 在 jsch 语义里是 **"这一种认证方法失败了"**，不是 "这次拨号结束了"。jssch 于是走 `UserAuthPublicKey` → `UserAuthPassword` → `UserAuthKeyboardInteractive` 的方法链，下一个方法再次回调 `promptKeyboardInteractive("Password: ")`，`publishPrompt`（改前 `:183-188`）`UUID.randomUUID()` 铸一个**新 id**，而 `sshPromptOffer` 的去重键是 `offered[configId] == promptId`（`SshPromptOffer.kt:94`）——**新 id 对它而言是首次挑战**，于是又开一张卡。

因果链：
```
Cancel → accepted=false → jsch 换下一个认证方法
      → 新 promptId → offer 表未命中 → 重新弹卡
      → 重复 = 报告里的 7/7 全重弹
      → 过程中 publickey 被静默丢弃 = 报告里的 `Password:` 卡
      → 且卡上写的是"换个方法"，sshd 侧看不到任何新认证 = 报告里"无任何认证记录"
```

### D2（高）认证成功被报成失败

`sshPromptOffer` 之外，同一缺失事实的第二条路径：`SshConnection` 无 attempt 身份，`answers` 是**每连接一个**的 `ArrayBlockingQueue(1)`。Reconnect = `disconnect()` + `connect()`（`:144-147`），**不取消**上一条仍在 `answers.poll(PROMPT_TIMEOUT_SECONDS = 600 s)` 的协程（`:190-195`）。

于是两条拨号并存：
```
attempt#2 (新)  → Ready  → 面板 Connected
attempt#1 (旧)  → 稍后被唤醒 → Fail  → 覆盖同一个 _state
                → 面板变成 Authentication required / Could not establish…
```
报告 §5.1 的"稍后条目又自行变成 Connected"正是这个覆盖的**滞后症状**（某次后续 emission 又把行态摆回去了），而不是自愈。

### D3（中）删除弹窗对 `Authentication required` 条目无响应

`SshTunnelHost.forget`（改前 `:135-139`）只做三件事：`disconnect(id)` → `watchJobs.cancel()` → 丢行。
但 `disconnect()` → `answers.clear()`（`:139`）**唤不醒阻塞在 `poll()` 上的线程**（`ArrayBlockingQueue.clear()` 只清缓冲，不发信号），那条停泊的协程**继续活着**，随后仍可 `publishPrompt` 再弹一张卡、仍可 `Fail`。对一个已被删除的条目而言，**这两件事都发生在列表之外**，用户看到的就是"弹窗按钮点了没反应、弹窗不消失，必须先 Disconnect"。

补充修复：`ServersPanel` 的 offer 表在行被删后仍留着该 id，本轮一并回收（`ServersPanel.kt:155-168`）。

### F12b 回归（FAIL）

历史口径是 **"Cancel 后不重弹、条目保留、二次 Reconnect 必重弹"**。改前三条全不成立：重弹=D1 的链；条目虽在但行态被拖成 `Server not reachable`；`Reconnect` 消失是因为行态已不在 `isSshConnecting` 之外的可点范围被脚本判死。**根因与 D1 同源，无独立改动。**

---

## 2. 改动

### 2.1 新增 attempt 守卫（纯，可 JVM pin）

`app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshConnectionModel.kt:139-240`

```kotlin
class SshAttemptGuard {
    private var generation: Int = 0
    private val cancelled = mutableSetOf<Int>()

    fun begin(): Int { generation += 1; cancelled.retainAll(0 until generation); return generation }
    fun current(): Int = generation
    fun invalidate() { generation += 1; cancelled.retainAll(0 until generation) }
    fun cancel(attempt: Int) { if (attempt == generation) cancelled += attempt }
    fun speaks(attempt: Int): Boolean = attempt == generation && attempt !in cancelled
}

fun sshAttemptSpeaks(attempt: Int, currentAttempt: Int, cancelled: Boolean): Boolean =
    attempt == currentAttempt && !cancelled

fun sshPublishesTerminal(current: SshConnectionStatus, event: SshConnectEvent): Boolean =
    current.stage != SshStage.READY || event !is SshConnectEvent.Fail
```

`cancelled.retainAll(0 until generation)` 的作用：**只有当前 attempt 的 cancel 会被保留**——一条被遗弃的拨号上的 cancel 绝不能把用户刚发起的新拨号也静音掉。

### 2.2 `SshConnection.kt` —— 接线

| 位置 | 改动 |
|---|---|
| `:71` | `private val guard = SshAttemptGuard()` |
| `:90-91` | `connect()` 在**第一次 emit 之前** `val attempt = guard.begin()`——被遗弃的拨号醒来时已经是静音的 |
| `:119` | `SshUserInfo(repository, attempt)`——jsch 会话带上自己的 attempt |
| `:163-184` | `disconnect()` 也 `guard.begin()`：显式断开同样让停泊的拨号退场 |
| `:186-193` | **新增 `abort()`**：作废 attempt + 清 prompt，供删除用（D3） |
| `:200-213` | **`cancelPrompt()` 改为 `guard.cancel(guard.current())` 再 offer** —— 静默本次拨号，而不是把它改道 |
| `:216-227` | `emit(attempt, event)`：静音的 attempt 不说话；且 `sshPublishesTerminal` 挡住覆盖 READY 的 `Fail`（D2） |
| `:236-247` | `publishPrompt(...)` 返回 `SshPrompt?`，静音时返回 `null` |
| `:288-334` | 四个 jsch 回调全部 `?: return false` / `?: return null` —— **方法链在第一次拒绝处停下** |

`emit` 内部保持 `_state.update { next }`（而非 `=`），维持改前 `MutableStateFlow` 的原子 fold 语义。

**关键不变式**：`SshAuthMethod.KEY` 分支（`:100-110`）、`promptPassphrase` 的快路径（`config.passphrase?.let { return true }`）、`routeEd25519ThroughBouncyCastle()` 全部**逐字未动**。这是 F14a/F14b 不被破坏的根据。

### 2.3 `SshTunnelHost.kt` —— D3

```diff
 fun forget(id: String) {
+    val tunnel = _tunnels.value.firstOrNull { it.config.id == id }
+    tunnel?.connection?.abort()      // v97 D3：先让停泊的拨号失声
     disconnect(id)
     watchJobs.remove(id)?.cancel()
     _tunnels.update { list -> sshTunnelsWithout(list, id) }
 }
```

**v91 的释放顺序（disconnect → 取消 watcher → 丢行）原样保留**——`SshTunnelDeleteV91Test.forgetDisconnectsCancelsTheWatcherThenDropsTheRow` 仍在绿，说明没有被削弱。修复完全靠 `abort()` 静音，不靠换顺序。

### 2.4 `ServersPanel.kt` —— 补完 D3

`:155-168` 新增一个 `LaunchedEffect(sshTunnels)`：列表里已不存在的 configId 从 `sshOffered` 中回收（`sshOffer` 若指向它则清空）。id 每条目唯一，故只会回收真正删掉的东西。

---

## 3. 为什么不算降低断言强度

本轮有 **2 个既有 pin 被改写**。逐条交代（这是本报告最需要被审的部分）：

### 3.1 `SshPassphraseAnswerV94Test.thePromptBranchRetainsTheAnswerItCollected`

| | 断言 |
|---|---|
| **改前** | `body.contains("val answer = awaitAnswer(prompt)")` |
| **改后** | `body.contains("val answer = awaitAnswer(attempt, prompt)")` |

pin 的是**"答案必须先绑定再使用"这个语义**，不是参数表本身；`awaitAnswer` 因 D1/F12b 的修复合法地多了一个 attempt 参数。同时把反例断言**加严**：

```diff
- body.contains("return awaitAnswer(prompt).accepted")
+ body.contains("return awaitAnswer(prompt).accepted") ||
+     body.contains("return awaitAnswer(attempt, prompt).accepted")
```

**净强度：上升**（少了一种 v94 discard 的写法能蒙混过关）。

### 3.2 `SshPassphraseAnswerV94Test.aFormPassphraseAnswersWithoutEverPublishingAChallenge`

| | 断言 |
|---|---|
| **改前** | `body.contains("publishPrompt(message, confirm = false, hostKey = null)")` |
| **改后** | `body.contains("publishPrompt(attempt, message, confirm = false, hostKey = null)")` **+ 新增** `body.contains("?: return false")` |

同样只多了一个参数；**并新增**一条断言：静音的 attempt 必须被**拒绝**而不是发卡——这正是 D1/F12b 的修复本体，此前无任何 pin 覆盖。
**净强度：上升。**

### 3.3 未被改动的既有断言（重点）

- `SshTunnelDeleteV91Test.forgetDisconnectsCancelsTheWatcherThenDropsTheRow` —— **原样通过**，故 §2.3 的释放顺序是被保住的。
- `SshEd25519IdentityF14Test`（F14b，4 用例）、`SshKeyFileFailureF14Test`（F14a，7 用例）、`SshPromptOfferV94Test`（14）、`SshPromptCancelDoesNotDismissF13bTest`（9）、`SshSeedForgetV92Test`（14）—— 全部**零改动**通过。

---

## 4. 测试证据

**命令**（抢锁 → 前台同步跑完 → 立刻放锁）：

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --console=plain :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.SshReconnectFidelityV97Test" --tests "...SshConnectionModelTest" --tests "...SshModelTest" --tests "...SshDialogRenderTest" --tests "...SshAuthenticateModelTest" --tests "...SshPromptOfferV94Test" --tests "...SshPromptCancelDoesNotDismissF13bTest" --tests "...SshKeyFileFailureF14Test" --tests "...SshEd25519IdentityF14Test" --tests "...SshPassphraseAnswerV94Test" --tests "...SshTunnelDeleteV91Test" --tests "...SshSeedForgetV92Test" --tests "...SshTunnelWiringTest" --tests "...SshTunnelHostModelTest" --tests "...SshPanelTunnelTest" --tests "...SshSubmitChainTest" --tests "...SshImeNextRoutingF13aTest" --tests "...SshTunnelNarrowRowV93Test" --tests "...SshTunnelNarrowStructureV93Test" --tests "...Wave5ConnectTest" --tests "...ServerConfigStoreSshTest" --tests "...ServerErrorPhraseTableTest" --tests "...StringsLocaleParityTest"
```

**原文结尾**：

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 11s
52 actionable tasks: 5 executed, 47 up-to-date
```

**计数**（`app/build/test-results/testDebugUnitTest/*.xml` 汇总）：

```
TOTAL_TESTS=216  FAILURES=0  ERRORS=0  SKIPPED=0
```

首次运行（改动落地、`forget` 顺序与 pin 尚未对齐时）为 `216 tests completed, 7 failed`；7 条全部已定位并闭环：4 条是我自己的源码 pin 路径解析写错，2 条是 §3.1/§3.2 的调用字面量变化，1 条是 §2.3 首版换了释放顺序——**三者均已按上文修正并重跑至全绿**，无遗留。

**日志**：`research/v97_fix/gradle_run1.log`（首跑 7 败）、`gradle_run2.log`（`BUILD SUCCESSFUL in 11s`）。
> 诚实备注：这两个文件名撞了 `research/v97_fix/` 下**其它轮次已有的**同名日志，本轮用 `Tee-Object` 覆盖了它们。若那两份日志的作者还需要，请从 git / 各自上下文取回——本轮应使用更唯一的文件名。

**新 pin 20 用例**（`SshReconnectFidelityV97Test`，覆盖任务书 ①②③④）：

| 任务书要求 | 对应用例 |
|---|---|
| ① 已存条目重连的认证方式选择 | `aSavedPrivateKeyEntryKeepsItsAuthMethodAndKeyPathThroughARoundTrip`、`aSeededPrivateKeyRowIsNotDialedAsAPasswordRow`、`aKeyEntryWithNoPassphraseNeverDegradesToThePasswordFallback`、`theAuthMethodWalkIsCutAtTheCancelRatherThanAtTheEndOfTheList` |
| ② 成功/失败发布的顺序与最终态 | `aSettledSuccessIsNotReDecidedByALateFailure`、`aRealFailureBeforeReadyIsStillPublished`、`aSupersededAttemptCannotOverwriteTheWinner`、`successIsPublishedBeforeAnyFailureOnTheSameAttempt` |
| ③ 删除入口不因 `Authentication required` 被禁用 | `deleteIsOfferedInEveryStageIncludingAuthenticationRequired`、`aConfirmedDeleteTakesTheRowOutWhateverStateItIsIn`、`aForgottenRowIsGoneEvenWhileItsDialIsStillParked`、`abortIsTotalAndDisconnectionStillPublishes`、`forgetAbortsTheParkedDialBeforeTheRowLeavesTheList` |
| ④ Cancel 后 offer 的"已消费"语义 | `aCancelledPromptIsConsumedAndIsNeverOfferedAgain`、`aCancelledDialPublishesNoNewPromptIdToReOffer`、`aSecondReconnectRePromptsEvenThoughTheFirstIdWasCancelled`、`theOfferGateIsUnchangedByThisRound` |
| 连接侧接线 pin | `theCancelPathMutesTheAttemptBeforeAnswering`、`everyChallengeIsRefusedWhenTheAttemptIsMuted`、`connectStampsItsAttemptBeforeItSpeaks` |

---

## 5. 真机复验步骤（交 Lead 派测试员）

前置：`emulator-5554` / Pixel_9a、WSL sshd active、`reverse tcp:2222→22` 在位、私钥已铺入 app 私有目录 `files/id_ed25519`（passphrase `keypass123`）。可直接复用 `research/v97_verify/5554_f14/f14lib.ps1` 的 `FieldBelow` 定位算法。

### D1 + F12b（**同一脚本连测**）

1. 新建私钥条目 `E2ECHK-v97-a`：host `ssh -p 2222 sshtest@127.0.0.1`，auth=`Private key`，keypath=app 私有目录，**passphrase 留空** → 提交 → `Trust and connect` → 输 `keypass123` → 期望 `Connected`。
2. 点 `Reconnect` → 期望弹 **passphrase 卡**（标题 `SSH authentication`，副标题回显 keypath）。
3. 点 `Cancel` → **7 次采样（每 5s）**。
   - **断言：7/7 全部 `card=False`；行仍在列表里；行态不是 `Server not reachable`。**
   - **断言：全程不出现 `Password:` 卡**（这是 D1 的直接指纹）。
4. 再点 `Reconnect` → **断言：必重弹 passphrase 卡**（F12b 的"二次必重弹"）。
5. 输 `keypass123` → `Continue` → 断言 `Connected`。
6. **sshd 打点**：`journalctl -f | grep -E "Accepted (publickey|keyboard-interactive)|authentication failure"`
   - **期望：步骤 1/2/4/5 各一条 `Accepted publickey`；步骤 3 的时间窗内 `Accepted` 与 `authentication failure` 均为 0。**
   - 改前的指纹：步骤 3 窗内出现 `keyboard-interactive` 提示卡却 sshd 全静。
7. **logcat 打点**：`adb logcat -s SshConnection:*`（若无 tag，用 `ActivityManager` + dump 观察面板文案）。建议本轮在 `SshConnection.emit/publishPrompt` 加一行 `Log.d` 打印 `attempt/guard.speaks`，便于区分"没发起认证"与"发起了被拒"。

### D2

1. 新建 Password 模式条目 `E2ECHK-v97-b`，提交 → 弹 `Password` 卡。
2. 输**正确**口令 → `Continue`。
   - **断言：面板**在 sshd 记录 `Accepted` 的**同秒或更快**变成 `Connected`**；采样 10 次（每 2s）**不得出现** `Authentication required` / `Could not establish the SSH connection.`
3. 重复步骤 1-2 **3 轮**（报告只跑过 1 轮，竞态复现率未量化——见 §6）。
4. **sshd 打点**：`Accepted keyboard-interactive/pam` 的时间戳 vs 面板变 `Connected` 的时间戳，**面板必须不晚于 sshd**。
5. **反向验证**（真失败仍须报失败）：输**错误**口令 → 断言面板**确实**停在 `Authentication required`（防止修复把真失败吞掉，见 `aRealFailureBeforeReadyIsStillPublished`）。

### D3

1. 把 `E2ECHK-v97-b` 置于 `Authentication required`（D2 步骤 5 的终态即可）。
2. ⋮ → `Delete` → 断言弹窗出现 → 点删除按钮。
   - **断言：行立即消失、弹窗消失、`ssh_json` 里该 id 消失（`run-as … cat shared_prefs` 核对）。**
   - **断言：全程无需先 `Disconnect`。**
3. 停 30s 再看列表 —— **断言：不出现任何迟到的卡或迟到的行**（改前的 zombie dial 指纹）。

### F14b / F14a 不回归

- F14b：新建私钥条目首连 → **必须** `Accepted publickey`，25 分钟窗内**无** `keyboard-interactive`。
- F14a：keypath 填 app 读不到的 `/sdcard/Download/nope_id_ed25519` → 断言逐字命中 `Can't read the private key file. Check the path and read permission.`

---

## 6. 未闭环 / 已知局限

1. **★ 真机未复验**。本轮按任务书禁止装机，全部结论来自静态因果链 + JVM pin。§5 的四组步骤**必须由测试员跑一遍才能闭环**，尤其 D2 的竞态（步骤 3 跑 3 轮）。
2. **D3 的"弹窗按钮可点性"只修了一半**。我修的是**逻辑侧**（zombie dial 不再干扰 + offer 表回收）。报告里"必须先 Disconnect 按钮才可点"的**几何/命中**成因（确认条是否被同列下方的 `SshAuthenticate` 卡遮挡或挤出视口）**没有在无设备条件下复现**，故未动布局。若测试员复验时 D3 仍复现，方向是 Compose 命中测试而非状态机。
3. **D1 的"重复 Reconnect 竞态"是推断**。真机上"第二次点 Reconnect"是否真的并存两条拨号，我只有代码证据（`answers.poll(600 s)` + `reconnect()` 不取消旧协程），没有设备证据。若真机显示是别的机制（例如 jsch 自身重试），本修复的**静默语义仍然正确**且会一并止住症状，但根因描述需要按实测修订。
4. **D2 的 `sshPublishesTerminal` 是一道闸门，不是一台状态机**。它只保证"READY 不被迟到的 Fail 覆盖"。若未来出现"READY 之后真实掉线"的场景，`SshConnection` 目前并不发布 `Fail`（掉线是 `isConnected` 层面的事），故不受影响；但若将来引入，本闸门需要重新评估。
5. **未做跨机型/跨 dpi 回归**（沿用 F14 的遗留项，本轮未扩范围）。
6. **未提交**。所有改动留在工作区（`SshConnection.kt` 等整个 SSH 家族在 git 中仍是 untracked，本轮不改变这一状态）。
7. 未新增任何用户可见字符串，故 `values/` `values-zh-rCN/` `values-zh-rTW/` **未改动**，`StringsLocaleParityTest` 3 用例仍绿。

---

## 7. 改动文件清单

| 文件 | 性质 |
|---|---|
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshConnectionModel.kt` | 新增 `SshAttemptGuard` + `sshAttemptSpeaks` + `sshPublishesTerminal` |
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshConnection.kt` | attempt 接线、Cancel 静音、`abort()`、终态闸门 |
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshTunnelHost.kt` | `forget` 前置 `abort()`（释放顺序不变） |
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/settings/ServersPanel.kt` | offer 表回收 |
| `app/src/test/java/com/xiufeigo/opencodeshell/SshReconnectFidelityV97Test.kt` | **新增**，20 用例 |
| `app/src/test/java/com/xiufeigo/opencodeshell/SshPassphraseAnswerV94Test.kt` | 2 处 pin 重述（加严，见 §3） |

未触碰：`_upstream/`、`app/build.gradle.kts`、`ui/home/**`、`ui/nav/**`、`ui/App.kt`、`ui/session/**`、`ChatViewModel.kt`、`ui/settings/ConnectServerScreen.kt`、任何 `values*/`。
