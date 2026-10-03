# NOTIFICATION_IDLE_DIAG — `ChatViewModel.notifyFor` IDLE 分支诊断（B1 矛盾定案）

- 角色：诊断 + 修复工程师（通知链路）
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 被裁定的两份报告：
  - `research/v97_fix/NOTIFICATION_DOMAIN.md`（判「链路完整」，判 N7 为误报）
  - `research/v97_verify/5556_v98/REPORT_SWEEPB2.md` §B1（判「`trigger idle:` 完全缺失 ⇒ IDLE 分支未执行」，并自认与「Channel 存在」**反证**无法调和）
- 分支基线：`main`，未改动任何生产代码（见 §B）

> **两份报告谁对？**——**两份都对，但都不完整**。
> `NOTIFICATION_DOMAIN.md` 的**代码结论**对（IDLE 分支进得去，链路完整），但它给出的**误报理由是错的**（不是「回合没在 4s 内 idle」，而是回合在客户端侧从未被读成 idle 帧）。
> `REPORT_SWEEPB2.md` B1 的**观测是对的**（`trigger idle:` 确实一次没出），但它的**推论链断在「Channel 存在 ⇒ post() 被调用过」这一步**——这个蕴含是假的。B1 自己列的三个假设里，**(b) 和 (c) 已被 grep 证伪，(a) 成立**。

---

## 0. 结论摘要（三句话）

1. **IDLE 分支进得去**，且其日志点**无任何降级**（真 `android.util.Log`、无 `BuildConfig.DEBUG` 判断、B1 那次同 tag 同 level 捕到了 3 行 ⇒ 通道是通的）。B1 观测到的「一行都没有」= **分支真的没进**，不是日志被吞。
2. **矛盾解开**：`opencode.sessions` Channel **确实只能由 `post()` 创建**（`createNotificationChannel` 全仓单点），**但它不是本轮观测量**——channel 行存在系统 `NotificationManager` 的库里，**跨进程死亡、跨 APK 升级都存活，只有卸载才清**。所以「Channel 存在」是**跨轮次累计高水位**，与「本轮投了 0 条」**毫无矛盾**。B1 的「反证」不成立。
3. **不改代码**。真实失败模式是：app 切后台后**进程被冻结/死亡**，收回合结束那一帧时 SSE 读流已经不在跑了 ⇒ 两个兜底（`watchdog settle:` / `trigger reconcile-settle:`）也一起哑火。给测试员的复验脚本在 §E。

---

## A. 定性

### A1. `notifyFor` 全部入口与分支 · 触发条件表

**唯一入口**：`ChatViewModel.onV2Frame` 的帧循环。

| # | 位置（`文件:行号`） | 原文 / 行为 |
|---|---|---|
| E1 | `app/src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt:2018` | `val events = transport.parseFrame(eventType, data)` |
| E2 | `ChatViewModel.kt:2019` | `for (event in events) {` |
| E3 | `ChatViewModel.kt:2020-2023` | `if (event is ChatStore.Event.ServerSignal) { onServerSignal(event); continue }` — **唯一在 `notifyFor` 之前 `continue` 的类型** |
| E4 | `ChatViewModel.kt:2039-2044` | `if (event is RunStatusChanged && event.status == RUNNING && !stoppedTurn.allowsRunAssert(routed, eventType)) continue` — **只丢 RUNNING，IDLE 不受影响** |
| E5 | `ChatViewModel.kt:2045` | `notifyFor(event, routed)` ← **对任何 session 的任何帧都喂**（在 `acceptsV2Session` 门禁 `:2047` **之前**，故 off-session 帧也进） |

**分支表**（`ChatViewModel.kt:7178-7230`）：

| 分支 | 行号 | 触发事件（来源） | 前置条件 | 门禁 | 本分支日志点 |
|---|---|---|---|---|---|
| **PERMISSION** | `:7180-7184` | `Event.PermissionAsked` | SSE 帧被读到 | `notifyPermissions` | 无分支内日志；落到 `raise` 的 `gate settings:` `:825` + `raise:` `:7311` |
| **QUESTION** | `:7188-7192` | `Event.FormCreated` | SSE 帧被读到 | `notifyPermissions` | 同上 |
| **ERROR** | `:7195-7200` | `RunStatusChanged(ERROR)`（适配 `session.error`） | SSE 帧被读到 | **`notifyErrors`，默认 `false`**（`SettingsStore.kt:190`，上游语义 `settings/model.tsx:261`） | 无分支内日志；同上 |
| **IDLE** | `:7202-7223` | `RunStatusChanged(IDLE)` | **只要客户端读到了该帧** | 见 A2-a | **`Log.d(NOTIFY_TAG, "trigger idle: …")` `:7215-7219`（无条件，先于 raise 判定）** |
| **RUNNING** | `:7225` | `RunStatusChanged(RUNNING)` | — | — | **无日志**；只 `turnRunStatus.running(routed)` |
| else | `:7228` | 其余所有事件 | — | — | 无 |

**IDLE 分支内部**（`:7202-7223`）：

```
:7203  val foreign  = routed != null && routed != sessionID
:7210  val finished = !foreign && turnRunStatus.ranTurn(routed)   // running && sawStep
:7213  val since    = turnRunStatus.since(routed)
:7214  turnRunStatus.settled(routed)
:7215  Log.d(NOTIFY_TAG, "trigger idle: routed=… foreign=… finished=… since=…")   // ← 无条件
:7220  if (foreign || finished) raise(AGENT, sessionID = routed, since = since)
```

**`raise` 之后的门禁链**（`ChatViewModel.kt:7303-7315` → `notify/AppNotifier.kt` → `notify/SystemNotifier.kt`）：

| 门禁 | 位置 | 判据 |
|---|---|---|
| 本机归属 | `:7305-7308` | `sessionOwnership.owns(sessionID)`，否则 `raise: skipped foreign session=` |
| 观看时长 | `:7309` | `notificationDelivery.watchedThrough(since)` |
| 设置开关 | `AppNotifier.raise` → `ChatViewModel.notificationEnabled` `:818-827` | AGENT→`notifyAgent`；PERMISSION/QUESTION→`notifyPermissions`；ERROR→`notifyErrors` |
| 焦点 | `SystemNotifier.deliver` `:~295-313` | 前台**且**viewed ⇒ 跳过直接 post，转 `withheld` + `scheduleRecheck`（2s 后补投） |
| 包级开关 | `SystemNotifier.post` `:344-347` | `areNotificationsEnabled()` 为 false 则**直接 return**（注意：在 `ensureChannel()` **之前**） |
| 去重 | `AppNotifier` dedupe window | 同 session+kind 窗口内只一条 |

### A2. 三问三答

#### a) IDLE 分支的前置条件在真机上何时成立？

**代码可达性已证** —— 适配器把三种帧都映射成 `RunStatusChanged(IDLE)`：

| 帧 | 映射 | 位置 |
|---|---|---|
| `session.status{type:idle}` | `RunStatusChanged(IDLE)` | `v2/V2EventAdapter.kt:109` |
| `session.idle` | `RunStatusChanged(IDLE)` | `v2/V2EventAdapter.kt:113` |
| `session.execution.succeeded` | `RunStatusChanged(IDLE)` | `v2/V2EventAdapter.kt:117` |

再叠加 E4「入口门禁只丢 RUNNING」，可得：

> **IDLE 分支的唯一前置条件 = 本客户端在 SSE 连接活着的时候，真正读到了上述三帧之一。**
> 分支体内**没有**任何额外前置（`:7203/:7210` 的 `foreign`/`finished` **只 gate `raise`，不 gate 日志**）。

**回答你列的几个猜测**：
- **需要「running→idle 边沿」吗？** —— **进入分支不需要**；`TurnRunStatus` 的 `running/sawStep` 边沿判定只决定**要不要 raise**（`:7210`），不影响分支是否执行。
- **需要前台/后台吗？** —— **进入分支不需要**（`raise` 才是焦点门禁的地方）。**但要真的投出通知，必须让 app 不在前台**，见 E 步骤 5。
- **需要 `notifyOnIdle` 开关吗？** —— **没有 `notifyOnIdle` 这个键**。IDLE 只映射 `AppNotificationKind.AGENT`，门禁是 `notifyAgent`（`notificationEnabled` `:821`）。`notifyErrors` 只管 ERROR 分支，**对 IDLE 零影响**。
- **真机上何时成立？** —— app 进程存活 **且** SSE 读流在跑。**回合计时器在服务端跑完 ≠ 客户端读到了帧**；后台冻结的进程读不到帧（B1 的失败模式）。

**判定方法（可执行）**：`logcat -d -s OcNotify:D *:S | grep -F 'trigger idle:'`。**这一行是分支是否执行的充分且必要证据**——它无条件打印，且同 tag 已被证明可捕获。

#### b) `Channel 存在` 是否只能由 `post()` 造成？

**是，只能由 `post()` 造成——但这恰恰说明它不能当本轮证据。**

`app/src/main` 全量 grep `createNotificationChannel` → **唯一一处**：

- `notify/SystemNotifier.kt:479` `manager.createNotificationChannel(channel)`，位于 `private fun ensureChannel()`（`SystemNotifier.kt:464`）内。

`ensureChannel` 是 **`private`**，全仓调用点 grep → **唯一一处**：

- `notify/SystemNotifier.kt:353`，位于 `private fun post(...)`（`SystemNotifier.kt:339-369`）内，**紧邻** `manager.notify(id, build(notification))`（`:354`）。

**⇒ B1 的三个假设逐个判：**

| B1 假设 | 判定 | 依据 |
|---|---|---|
| (a) 前几轮其它 agent 测过通知、建了 Channel | ✅ **成立** | 见下 |
| (b) `ensureChannel()` 有第二个调用点 | ❌ **grep 证伪** | `private` + 全仓单调用点 `:353` |
| (c) `post()` 走到 `manager.notify` 前后被丢 | ❌ **代码顺序证伪** | `areNotificationsEnabled()` 的早退在 `:344`，**在** `:353` **之前**；`:353→:354` 之间无可丢步骤，`ensureChannel()` 先于 `notify()` 执行 |

**关键推论（矛盾的解）**：channel 行存在**系统** `NotificationManager` 数据库里，按 (package, id) 索引。它**跨进程死亡、跨 app 重启、跨 APK 升级都存活**，**只有卸载才清除**。B1 记录的 APK `lastUpdateTime=2026-10-02 15:20:48` —— 也就是说，这条 Channel 可以是**此前任何一轮**（v97b 的 N7 测试、或更早的其它 agent）在这台设备这次安装里留下的。

> **所以：`Channel 存在` 是「本设备本次安装以来的累计高水位」，不是「本轮观测量」。它无法与「本轮 NotificationRecord = 0」共存，也**不构成反证**。**
> 上一轮据此写下的 ⚠️反证「Channel 的存在意味着历史上 `post()` 至少被调用过……与 notifyFor 从未 raise 不完全自洽」——**这个「不完全自洽」被消解了**：那个 `post()` 就是**历史**的，不是本轮的。B1 的断点收窄方向（触发层）没错，但**它把一个跨轮次高水位当成了本轮观测量**。

**这也顺带给出一条新的复验纪律**（已写进 §E）：
> **判据必须是 `NotificationRecord`（投出的通知），不是 `NotificationChannel`（渠道）。** 两者在 `dumpsys notification` 里长得极像，务必按 `NotificationRecord(` 与 `NotificationChannel{` 区分。

#### c) `trigger idle:` 缺失是分支没进，还是日志点缺失/被降级？

**是分支没进。日志点完好、无降级。** 五条独立证据：

1. `ChatViewModel.kt:3` `import android.util.Log` —— **真** `android.util.Log`，不是可被开关静音的自定义包装器。
2. `ChatViewModel.kt:7453` `private const val NOTIFY_TAG = "OcNotify"` —— 与 `logcat -s OcNotify` 完全对齐。
3. `ChatViewModel.kt:7215` 的 `Log.d` **不在**任何 `if (BuildConfig.DEBUG)` 内，全仓 `ChatViewModel` 无 `BuildConfig` 判断包裹。
4. `:7215-7219` 的 `Log.d` 位于 `if (foreign || finished)`（`:7220`）**之前**，且在分支函数体内**无条件**执行 —— 只要进了分支就必出。
5. **B1 那次 `logcat -d -s OcNotify:D *:S` 恰好捕到了同 tag 同 level 的 3 行**（`foreground: paused=OcActivity` / `focus: lost` / `foreground: stopped`）⇒ **该 tag 该 level 的日志通道当时是通的**。若是 tag 过滤或级别降级，这 3 行也出不来。

**⇒ 结论：不是日志点问题，是事件没到。**

**再进一步：为什么连兜底也没救回来？**
两个为「终帧丢失」而专门设计的兜底也**同样一行没出**：

- `ChatViewModel.kt:2215` `Log.d(NOTIFY_TAG, "watchdog settle: session=… live=…")`（`TurnIdleAction.SETTLE_IDLE`，宽限期 `IDLE_GRACE_MS = 10_000L`，`ChatViewModel.kt:7455`）
- `ChatViewModel.kt:6892` `Log.d(NOTIFY_TAG, "trigger reconcile-settle: session=… since=…")`（`refreshSnapshot` 内的 reconcile-settle）

若进程只是「漏了一帧但还活着」，watchdog 至迟 10 s 就会探一次并发日志。它没发 ⇒ **回合结束的那段时间里，进程 / SSE 读流整体不在跑**——正是 Android cached-process 冻结（`notify/TurnRunStatus.kt:33-39` 与 `notify/ForegroundTracker.kt:13-21` 都把这个「后台即冻结、终帧分钟后才被读到」写进了设计注释）。B1 的 logcat 恰好**停在 `foreground: stopped`（按 HOME 的那一刻）**，之后什么都没有——与该图景完全吻合。

**B1 判读表里那条自相矛盾的行**（原文「**完全没有 `trigger idle:`** ⇒ 按判读表 = 「回合未完成 / idle 帧没到」— **但后端 `type=idle` 已落库，与判读表描述不符**」）——**现在可以结案了**：不是「与判读表不符」，而是**判读表缺了「进程已冻结」这一行**。后端落库只证明**服务端**跑完，不证明**客户端**读到了帧。

---

## B. 修 / 不修

### **判定：链路本来就是好的 —— 不改代码。**

依据三条，缺一不可：

1. **A2-a 证明分支可达**：三条帧映射（`V2EventAdapter.kt:109/113/117`）+ 入口只丢 RUNNING（`:2039-2044`）+ 分支内无条件日志（`:7215`）⇒ 「分支进不去」在代码层**不成立**。
2. **A2-c 证明日志点无降级** ⇒ B1 的空日志是**真实观测**，指向运行时（进程冻结），不是代码缺陷。
3. **A2-b 证明「反证」不成立** ⇒ 唯一支撑「代码有缺口」的论据塌了。

**因此不动 `ChatViewModel.kt`、不动 `notify/**`、不动 `ui/settings/**`。**
约束遵守：未触碰 `_upstream/`、`app/build.gradle.kts`、`ui/servers/**`、`ui/session/review/**`、`ui/home/**`、`ui/terminal/**`、`OcActivity.kt`、`ui/App.kt`、`ui/nav/**`；**未新增任何用户可见字符串**（三语资源零改动，`StringsLocaleParityTest` 亦为佐证）；**未降低任何既有断言强度**（本次未改任何测试）。

**工作树诚实声明**：`git status --porcelain` 在 `app/src/main` 与 `app/src/test` 下确实列出了大量 `M` 条目，但**那些全是本轮之前累积的未提交团队产出，不是本 agent 改的**。核对 mtime 佐证：写入范围内的文件最后修改时间均**早于**本会话起点（`ChatViewModel.kt` 2026-10-02 22:52:16、`notify/AppNotifier.kt` 2026-09-29 13:29:06、`notify/SystemNotifier.kt` 2026-09-29 17:10:56、`SettingsStore.kt` 2026-09-30 01:43:18、`AppNotifierTest.kt` 2026-09-28 11:54:25），而本会话起于 2026-10-03 09:56。**本 agent 的全部写入仅两个 research 文件**（本报告 + 回归日志）。

**同时说明：`NOTIFICATION_DOMAIN.md` 的「误报」判定成立，但它给的理由要更正**——
原文 §表第 3 行写的是「`Count slowly from 1 to 40` 太慢，计数 4s 时**回合尚未 idle**」。
真正的机制**不是**「回合没跑完」，而是**回合跑完了、但客户端那一帧从未被读到**（进程冻结）。两者的处置完全不同：前者要延长等待，后者要保证进程存活。**下一轮照 §E 复验，不要按「等久一点」的思路重试。**

### 上一轮为什么读到 0（完整解释）

| 环节 | 上一轮实际状态 | 后果 |
|---|---|---|
| 后端回合 | **跑完了**，13 条 message 落库含 `type=idle` | 服务端侧无异常 |
| app 进程 | 按 HOME 后 `foreground: stopped`，随后**冻结/死亡** | SSE 读流停摆 |
| `session.idle` 帧 | 服务端发出，**客户端从未读到** | `parseFrame` 产不出 `RunStatusChanged(IDLE)` |
| `notifyFor` IDLE 分支 | **未执行** | 无 `trigger idle:` |
| 两个兜底 | 进程不在跑，**也未执行** | 无 `watchdog settle:` / 无 `trigger reconcile-settle:` |
| `posted:` | 0 | `NotificationRecord = 0` |
| Channel | **历史遗留**（本次安装早前某轮建的） | **误导性观测量**——被 B1 误当成反证 |

设备侧诱因（B1 §0 自述，已连坐）：`mCurrentFocus=null` ×2、`keyguard showing=true` / `screenState=SCREEN_STATE_OFF`、两次 `adb reboot`（且 reboot 会清掉 `reverse` 映射 → SSE 目标 `127.0.0.1:4095` 一度不可达）。**在这种设备状态下取的通知读数不具备证据力。**

---

## C. 回归跑

命令（`:app:testDebugUnitTest --tests "<类名>"` 逐类白名单，前台跑完）：

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.BannerPolicyTest" `
  --tests "com.xiufeigo.opencodeshell.ChatStoreErrorMessageTest" `
  --tests "com.xiufeigo.opencodeshell.ChatStoreReviewEventTest" `
  --tests "com.xiufeigo.opencodeshell.ChatStoreTest" `
  --tests "com.xiufeigo.opencodeshell.ChatStoreToolCapTest" `
  --tests "com.xiufeigo.opencodeshell.ChatStoreWave4EventTest" `
  --tests "com.xiufeigo.opencodeshell.ChatStoreWave5EventTest" `
  --tests "com.xiufeigo.opencodeshell.ForegroundTrackerTest" `
  --tests "com.xiufeigo.opencodeshell.NotificationSoundsModelTest" `
  --tests "com.xiufeigo.opencodeshell.NotificationSystemTest" `
  --tests "com.xiufeigo.opencodeshell.NotificationWithheldOnBackgroundV63Test" `
  --tests "com.xiufeigo.opencodeshell.NotifyBackgroundDeliveryTest" `
  --tests "com.xiufeigo.opencodeshell.NotifyCoveredWindowV68Test" `
  --tests "com.xiufeigo.opencodeshell.StoppedTurnTest" `
  --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest" `
  --tests "com.xiufeigo.opencodeshell.TurnRunStatusTest" `
  --tests "com.xiufeigo.opencodeshell.Wave23aSettingsPaletteTerminalTest" `
  --tests "com.xiufeigo.opencodeshell.AppNotifierTest"
```

类名来自 `Notification*` / `*Notify*` / `Wave23a*` / `ChatStore*` / `StringsLocaleParity*` / `TurnRun*` / `Foreground*` / `StoppedTurn*` / `BannerPolicy*` / `AppNotifier*` 的全量扫描（`app/src/test` 递归 + `BaseName` 去重）。

**结果：见 §C1（`research/v97_fix/mcode_notify_idle_regression.log`）。**

### C1. 结果 —— **BUILD SUCCESSFUL，18 类 / 199 用例，0 失败 0 错误 0 跳过**

环境：`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`；锁 `.agent-gradle-lock` 先等消失→写→跑完删（已确认删除）。
完整日志：`research/v97_fix/mcode_notify_idle_regression.log`（`EXITCODE=0`）。

**gradle 尾部原文**：

```
> Task :app:compileDebugKotlin UP-TO-DATE
> Task :app:compileDebugUnitTestKotlin UP-TO-DATE
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 5s
52 actionable tasks: 1 executed, 51 up-to-date
=== EXITCODE=0 ===
```

**逐类用例数**（取自 `app/build/test-results/testDebugUnitTest/TEST-*.xml`，全部 mtime = 2026-10-03 09:58:26，证明是本次新鲜跑出，非历史残留）：

| 测试类 | 用例 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| `AppNotifierTest` | 17 | 0 | 0 | 0 |
| `BannerPolicyTest` | 20 | 0 | 0 | 0 |
| `ChatStoreErrorMessageTest` | 6 | 0 | 0 | 0 |
| `ChatStoreReviewEventTest` | 5 | 0 | 0 | 0 |
| `ChatStoreTest` | 12 | 0 | 0 | 0 |
| `ChatStoreToolCapTest` | 4 | 0 | 0 | 0 |
| `ChatStoreWave4EventTest` | 11 | 0 | 0 | 0 |
| `ChatStoreWave5EventTest` | 19 | 0 | 0 | 0 |
| `ForegroundTrackerTest` | 9 | 0 | 0 | 0 |
| `NotificationSoundsModelTest` | 5 | 0 | 0 | 0 |
| `NotificationSystemTest` | 21 | 0 | 0 | 0 |
| `NotificationWithheldOnBackgroundV63Test` | 5 | 0 | 0 | 0 |
| `NotifyBackgroundDeliveryTest` | 12 | 0 | 0 | 0 |
| `NotifyCoveredWindowV68Test` | 4 | 0 | 0 | 0 |
| `StoppedTurnTest` | 10 | 0 | 0 | 0 |
| `StringsLocaleParityTest` | 3 | 0 | 0 | 0 |
| `TurnRunStatusTest` | 11 | 0 | 0 | 0 |
| `Wave23aSettingsPaletteTerminalTest` | 25 | 0 | 0 | 0 |
| **合计（18 类）** | **199** | **0** | **0** | **0** |

`MISSING_XML: none`（18 类全部产出结果文件，无类被 `--tests` 过滤器静默漏掉）。

**注意**：本轮**未新增** JVM pin 测试——因为 §B 判定「链路本来就是好的，**不改代码**」，任务书要求「若你改了代码：补 JVM pin」，未改代码故不补；既有 `TurnRunStatusTest`（11 例）已覆盖 `running/settled/ranTurn` 的边沿真值表，本报告 §A1 的 IDLE 分支真值表与之一致，无需重复 pin。

**未跑（纪律）**：未 `assembleDebug`、未 `clean`、未跑全量 `testDebugUnitTest`、未装机（装机由 Lead 做）。

---

## D. 交付判定

| 问题 | 判定 |
|---|---|
| IDLE 分支能否进入 | **能**（三条帧映射 + 入口不拦 IDLE + 无条件日志） |
| 需特定条件吗 | **需**：客户端须在 SSE 活着时**真正读到** `session.idle` / `session.status{idle}` / `session.execution.succeeded`；要**投出**通知还须 `notifyAgent` 开、包通知开、app 不在前台（或 viewed=false） |
| 上一轮读到 0 的原因 | **进程在后台被冻结，终帧从未被读到**；两个兜底随之一起哑火；「Channel 存在」是**跨轮次累计高水位**，非本轮观测量 |
| 是否改了代码 | **否**（未发现缺口） |

---

## E. 给测试员的照抄式真机步骤

> 设备：`emulator-5556` / AVD `Medium_Desktop`；包 `com.xiufeigo.opencodeshell`；后端 `http://127.0.0.1:4095`。
> **装机由 Lead 做**（本轮未装机、未 assembleDebug）。
> **本节唯一的新纪律：判据是 `NotificationRecord`（投出的通知），不是 `NotificationChannel`（渠道）。**

### 步骤 0 — 设备健康闸门（**不通过就别往下做**）

上一轮的读数作废，就是因为没做这一步。逐条 `adb -s emulator-5556`：

```bash
adb -s emulator-5556 shell dumpsys window policy | grep -E 'screenState|keyguard'
#   期望：screenState=SCREEN_STATE_ON，且 keyguard showing=false
#   若 SCREEN_STATE_OFF：先 keyevent 224 + input tap 683 400 唤醒，再继续

adb -s emulator-5556 shell wm size
adb -s emulator-5556 shell wm density
#   期望：Override size: 1366x768 / Override density: 160（与上一轮一致，别 reset）

adb -s emulator-5556 reverse --list
#   期望：两行 host-19 tcp:4095 与 tcp:2222
#   缺了就补：adb -s emulator-5556 reverse tcp:4095 tcp:4095

adb -s emulator-5556 shell getprop sys.boot_completed     # 期望 1
curl -s http://127.0.0.1:4095/api/info                    # 期望 200 {"version":...}
```

**本轮纪律：一旦 `adb reboot`，后面必须重跑本步骤**（reboot 会清 `reverse`）。

### 步骤 1 — 开日志流（**必须在发 prompt 之前**）

另开一个终端持续抓（不要事后 `logcat -d` 碰运气）：

```bash
adb -s emulator-5556 logcat -c
adb -s emulator-5556 logcat -v time -s OcNotify:D *:S
```

### 步骤 2 — 建会话 + 发一个**足够长**的回合

用 B1 同款提示词（1..40，每秒一行 ⇒ 约 40 s）：

```
Count slowly from 1 to 40. Output one number per line, one second apart.
```

- 期望：界面出现 `Working` + `Stop` ⇒ **RUNNING 帧已被读到**（`notifyFor` 的 RUNNING 分支 `:7225` 无日志，所以此时日志里可能仍无 `OcNotify` 通知行，**这是正常的**）。

### 步骤 3 — 切后台（**回合进行中，不是刚发完**）

等约 25 s（回合约 60% 进度）再切，留足「切后台后进程仍存活」的时间窗：

```bash
adb -s emulator-5556 shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

- 期望日志出现：`foreground: paused=OcActivity` … `focus: lost` … `foreground: stopped`

### 步骤 4 — 等后端真的 idle（**唯一可信的回合结束判据**）

```bash
curl -s "http://127.0.0.1:4095/api/session/<SESSION_ID>/message?order=asc&limit=80"
```

- 期望：消息序列末尾出现 `type=idle`（或 `session.status{idle}`）。
- **这一步是「回合真正 idle」的判据**——上一轮就是只信了它，却没检查客户端读没读到帧。

### 步骤 5 — 读日志（**这一步直接判生死**）

```bash
adb -s emulator-5556 logcat -d -s OcNotify:D *:S | grep -E 'trigger idle|raise:|posted:|watchdog settle|reconcile-settle|withheld|recheck'
```

**先别回 app**（回 app 会改焦点状态，污染判定）。逐档判读：

| 看到 | 结论 |
|---|---|
| `trigger idle: … finished=true` **+** `raise: kind=AGENT` **+** `posted: id=…` | ✅ **链路完整，PASS**。IDLE 分支执行了、raise 了、post 了 |
| `trigger idle: … finished=false` 且无 `raise:` | ⚠️ 分支执行了，但 `ranTurn` 为 false（本客户端没看到 step）——**属边沿判定，非链路断裂**；此时应看 `watchdog settle:` 是否接手 |
| `trigger idle: … foreign=true` + `raise: skipped foreign session=` | 命中 `SessionOwnership` 门禁（本机归属），非 bug |
| 只有 `watchdog settle:` / `trigger reconcile-settle:` **+** `posted:`，无 `trigger idle:` | ✅ **兜底路径生效**（终帧丢了但通知仍投出）——链路完整。**这正是上一轮缺失的那两行** |
| 有 `raise: kind=AGENT` + `skip: app is foreground …` + `withheld:` + 之后 `posted:` | ✅ 焦点门禁走了「前台→后台」补投路径，正常 |
| **一条 `OcNotify` 都没有**（连 `foreground: stopped` 都没有） | ❌ **进程已死/被杀**。回步骤 0 查设备，先解决设备再重测 |

### 步骤 6 — 查通知（**按 Record 判，不按 Channel 判**）

```bash
adb -s emulator-5556 shell dumpsys notification --noredact | grep -i opencodeshell
```

- ✅ PASS 的判据是出现 **`NotificationRecord(`**（含 `pkg=com.xiufeigo.opencodeshell` 的那一条）
- ❌ **`NotificationChannel{mId='opencode.sessions'…}` 单独存在不算数** —— 它是历史累计（见 A2-b），上一轮就是在这里误判的

### 步骤 7 — 交叉验证

```bash
adb -s emulator-5556 shell cmd notification list | grep -i opencodeshell
```

- 期望能看到步骤 6 那条 Record 的 `pkg` 行。

### 失败时的分诊决策树

```
logcat 无任何 OcNotify 行
  └─ 进程死了 → 查设备（步骤 0）+ 查 `adb shell pidof com.xiufeigo.opencodeshell`
logcat 有 foreground: stopped 但无 trigger idle:、无 watchdog settle:
  └─ 进程被冻结（cached process）→ 本轮失败模式
       ├─ 处置 A（推荐）：重测时不要 reboot、不要反复压屏，设备保持健康再跑一遍 §步骤 0→7
       └─ 处置 B：把 app 切回前台，等 IDLE_GRACE_MS(10s)+一次探针
                   adb shell am start -n com.xiufeigo.opencodeshell/.OcActivity
                   期望补出 `watchdog settle:` + `posted:` ⇒ 即可证明链路完好
```

---

## F. 未闭环

1. **真机复验本轮未执行** —— 装机由 Lead 做，本 agent 未 assembleDebug / 未装机。§E 脚本待下一轮测试员执行。
2. **【真实功能缺口，非本轮范围】进程冻结窗口内的通知会丢。** 本次诊断确认的失败模式是：app 切后台被系统冻结后，终帧与两个兜底**全部无法执行**。兜底（watchdog / reconcile-settle）都跑在**进程内协程**里，对「冻结/被杀」这一档**无能为力**。这需要 `AlarmManager` / `WorkManager` / 前台服务一类的**进程外**载体，属于架构级改动，超出「IDLE 分支最小修复」授权，**本轮不动**。建议单开一项。
3. **`report` 的 B1 原文未修改** —— `REPORT_SWEEPB2.md` §B1 的 ⚠️反证段落按原样留在那里（别人的产出）。本文件即为该反证的裁定；是否回填由 Lead 决定。
4. **未覆盖**：`session.error`（ERROR）分支的真机读数（本轮 `notifyErrors` 默认 `false`，与上游 `settings/model.tsx:261` 一致，按约束**不得改动**；要测 ERROR 需测试员先在设置里打开 `notifyErrors` 开关）。
