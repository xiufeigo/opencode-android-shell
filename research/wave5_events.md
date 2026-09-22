# Wave 5-E — SSE 事件消费 + 应用内通知（E2 / E3 / E5）

状态：**E3 全做、E2 全做（解析 + 消费）、E5 做数据与去抖闭环**；构建门见 §7。
工作目录 `C:\sensorsdata\main\program\opencode-android-shell`，2026-09-21。

改动文件（严格在允许集内）：

| 文件 | 类型 | 内容 |
|---|---|---|
| `app/src/main/java/com/xiufeigo/opencodeshell/ChatStore.kt` | 改 | `Signal` 枚举 + `Event.ServerSignal`；`part.delta` 任意 string field；`reply`/`answers` 入状态 |
| `app/src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt` | 改 | 5 条 revision 流 + 目录重拉 + 通知触发/暴露/设置门控 |
| `app/src/main/java/com/xiufeigo/opencodeshell/notify/AppNotifier.kt` | **新建** | `AppNotification` / `AppNotificationKind` / `AppNotifier` |
| `app/src/main/res/values*/strings.xml` ×3 | 改（只追加） | 6 个 `oc_notify_*` |
| `app/src/test/java/com/xiufeigo/opencodeshell/ChatStoreWave5EventTest.kt` | 新建 | E2+E3 单测 |
| `app/src/test/java/com/xiufeigo/opencodeshell/AppNotifierTest.kt` | 新建 | E5 单测（去抖/门控/上限） |
| `app/src/test/java/com/xiufeigo/opencodeshell/Wave5EventsWiringTest.kt` | 新建 | 源码级接线守卫 + 三语 string |
| `PermissionFlowTest.kt` / `QuestionFlowTest.kt` | 改 | 2 条断言按新契约收紧（`reply` / `answers` / `rejected`） |

**未改**：`EventStream.kt`、`SettingsStore.kt`、`OpenCodeClient.kt` 及任何 `ui/**`（见 §5 交给 Lead 的接线）。

---

## 1. 一句话结论

E3 的两个真 bug（`part.delta` 只认 `text`、`reply`/`answers` 被丢）已修且有单测；
E2 的 13 个未消费事件全部进入 `ChatStore` 并落到「目录重拉」或「revision 流」两种消费上；
E5 的通知数据、设置门控与 5s 去抖闭环完成，UI 呈现按边界留给 Lead（接线点见 §5.5）。

---

## 2. E2：未消费事件 → 已消费（表）

解析统一走 `ChatStore.parseEvent` 的新 `else` 分支（`ChatStore.kt:880`）：
未知 type 仍返回 `null`（`Signal.ofWireType` 未命中），命中的包装成
`Event.ServerSignal(signal, sessionID, subject)`（`ChatStore.kt:373`）。
`reduce` 对它是**空操作**（`ChatStore.kt:541`），所以不会污染 transcript。

| 上游事件 | 上游锚点 | 解析结果 | 消费（谁、做了什么） | UI 是否已更新 |
|---|---|---|---|---|
| `provider.updated` | `provider.ts:26` | `Signal.PROVIDER_UPDATED` | `ChatViewModel.onServerSignal` → `catalogRefresh()`（`ChatViewModel.kt:1366,1406`） | ✅ **自动**：`availableProviders` 是 StateFlow，模型选择器/chip 直接刷新，不再需要重进页面 |
| `model.updated` | `model.ts:12` | `Signal.MODEL_UPDATED` | 同上 | ✅ 自动 |
| `agent.updated` | `agent.ts:11` | `Signal.AGENT_UPDATED` | 同上（`refreshCatalogs()` 重拉 agent/command/skill） | ✅ 自动 |
| `config.updated` | `config.ts:134` | `Signal.CONFIG_UPDATED` | `refreshModelLabel()` + `catalogRefresh()` | ✅ 自动（`config.model` 变化后 chip 的默认模型跟上） |
| `project.updated` | `project.ts:59` | `Signal.PROJECT_UPDATED`，`subject` = 项目 id | `projectsRevision += 1`（`ChatViewModel.kt:170,1380`） | ❌ 留给 Lead（§5.1） |
| `vcs.branch.updated` | `vcs-event.ts:8` | `Signal.VCS_BRANCH_UPDATED` | `vcsRevision += 1`（`:180,1389`） | ❌ 留给 Lead（§5.3） |
| `file.edited` | `v1/filesystem.ts:7` | `Signal.FILE_EDITED`，`subject` = 文件路径 | `filesRevision += 1`（`:185,1390`） | ❌ 留给 Lead（§5.4） |
| `session.created` | `v1/session.ts:573` | `Signal.SESSION_CREATED`，`sessionID` 有值 | `sessionsRevision += 1`（`:175,1385-1387`） | ❌ 留给 Lead（§5.2） |
| `session.deleted` | `v1/session.ts:589` | `Signal.SESSION_DELETED`，`sessionID` 有值 | 同上 | ❌ 留给 Lead（§5.2） |
| `pty.created` | `pty.ts:34` | `Signal.PTY_CREATED`，`subject` = pty id | `ptyRevision += 1`（`:190,1392-1396`） | ❌ 未接线，见 §5.6（成本原因） |
| `pty.updated` | `pty.ts:35` | `Signal.PTY_UPDATED` | 同上 | 同上 |
| `pty.exited` | `pty.ts:36` | `Signal.PTY_EXITED` | 同上 | 同上 |
| `pty.deleted` | `pty.ts:37` | `Signal.PTY_DELETED` | 同上 | 同上 |

### 2.1 两个实现要点（评审容易踩的地方）

1. **信号必须在 session 门控之前消费**。`session.created` 的 `properties.sessionID` 就是**新会话自己的 id**，
   `ChatStore.acceptsSession(sessionID, inner)` 对它是 `false` → 若放在门控之后，别的项目建会话这条信号会被丢掉。
   现在顺序是：unwrap → `eventSessionId` → `session.*` liveness/节流快照（原样保留）→ `parseEvent` →
   `ServerSignal`/通知（`ChatViewModel.kt:425-428`）→ **然后**才是 `acceptsSession` 门控（`:429`）。
   `Wave5EventsWiringTest.theListenerConsumesSignalsAndNotificationsBeforeTheSessionGate` 用源码位置钉住这个顺序。
2. **目录重拉节流**：一次 config reload 会连发多帧 `provider/model/agent.updated`，
   每次重拉 = 4 个 REST 调用（`/provider` `/agent` `/command` `/skill`）。
   用 `CATALOG_REFRESH_THROTTLE_MS = 2000`（`ChatViewModel.kt:1563`）做突发保护，`refreshModelLabel()`
   （单次 `GET /config`）不参与节流。

### 2.2 明确**没有**做的相邻事件（不是漏，是判断）

| 事件 | 原因 |
|---|---|
| `session.updated` | 已有消费者：`maybeRefreshOnSession` 的节流快照会重拉并回填标题（audit §B-1 #2）。再给一条 revision 只会让会话列表被每一轮的 durable 事件刷屏。 |
| `session.compacted` / `mcp.*` / `lsp.*` / `server.heartbeat` | 无可刷新的读模型。`parseEvent` 保持 `null`，`ChatStoreWave5EventTest.unknownEventsStillFallThroughToNull` 钉住。 |
| `server.connected` / `global.disposed` / `server.instance.disposed` | 属连接生命周期，不是读模型失效；`EventStream` 已按 HTTP 200 + 35s 读超时管理连接（`EventStream.kt:126,205`）。要做应做成连接状态流，属于另一条线。 |
| `sync` 包装帧（audit §B-2.1 的高严重度风险） | 本 clone 内无 `sync` 的上游定义可对齐；不做猜测性解包，风险与建议保留在 audit §B-2.1，需要真机抓帧确认后单独处理。 |

---

## 3. E3：解析缺口（真 bug）

### 3.1 `message.part.delta` 支持任意 string field

旧代码 `ChatStore.kt` 的 `applyPartDelta` 第一行是 `if (event.field != "text") return state`，
除 `text` 外全部静默丢弃。现在按「`UiPart` 真的能承载的 string 字段」映射（`ChatStore.kt:597 withFieldDelta`）：

| 上游 `field` | 落到 `UiPart` | 说明 |
|---|---|---|
| `text` | `text` | 原行为不变 |
| `reasoning` | `text` | reasoning part 的正文就是 `text`（`v1/session.ts:118-128`），reasoning 增量不能再丢 |
| `state.output` / `output` | `toolOutput` | 工具输出增量；**受 16k 展示上限**保护（`appendToolText`） |
| `state.error` / `error` | `toolError` | 同上 |
| `mime` / `filename` / `url` | 同名字段 | file part 的可寻址 string 字段 |
| 其它（`toolInput`、`state.input`、未来的字段） | — | 返回 `null` → 整个 state 原样返回，**不猜测、不破坏对象型字段** |

复杂度/性能：增量仍是「先定位 message、再定位 part」，未命中直接返回原 state（`ChatStore.kt:579`），
与旧实现同阶；`toolOutput` 到上限后**不再做字符串拼接**（`appendToolText` 提前返回），避免长流下反复复制 16k 字符串。

### 3.2 `permission.replied.reply` / `question.replied.answers` 入状态

| 事件 | 旧行为 | 新行为 |
|---|---|---|
| `permission.replied` | 只取 `requestID` 删 pending，`reply`（`once`/`always`/`reject`）丢弃 | `Event.PermissionReplied(requestID, reply)`；`ChatState.permissionReplies: List<PermissionReply>`（`ChatStore.kt:209,231`） |
| `question.replied` | 只取 `requestID` 删 pending，`answers: string[][]` 丢弃 | `Event.QuestionResolved(requestID, answers)`；`ChatState.questionResolutions`（`:215,239`） |
| `question.rejected` | 与 replied 完全同形，无法区分 | `Event.QuestionResolved(requestID, rejected = true)`，回显可区分「用户点了 Dismiss」与「答了空数组」 |

语义约束（都写进了代码注释与测试）：

* **只记录本屏真的 pending 过的 requestID**。理由：a) 保持「重复/迟到帧是 no-op」的幂等契约（旧测试正是这么钉的）；
  b) 记录列表天然被限制在本会话自己的 ask 上，不会被全局流里别会话的 reply 撑爆；c) 与 ask 的可见性规则对称（别会话的 ask 本来也不进本屏）。
* **本机应答必须带真实值**：`ChatViewModel.replyPermission` 的乐观删除现在传 `reply`、`replyQuestion` 传 `answers`、
  `rejectQuestion` 传 `rejected = true`。否则本地应答会记成空串，而服务端随后的同名事件因「该 requestID 已不在 pending」
  变成 no-op → 回显拿不到数据（这是本轮自查发现并修掉的一个真缺口）。
* **pending 再次出现 ⇒ 旧答案作废**：`PermissionAsked` / `QuestionAsked` 会清掉该 requestID 的旧记录
  （唯一来源是重问，或应答 POST 失败后的回滚重发），避免出现「dock 还在等，回显却写着已批准 always」的自相矛盾。
* **上限 20 条**（`MAX_RESOLVED_REQUESTS`，`boundedAppend`），超出丢最旧。
* 访问器：`ChatState.permissionReply(requestID)` / `ChatState.questionResolution(requestID)`，供后续回显按 requestID 取。

> 给 Lead 的回显建议（未接线）：`permissionReplies` 可按 `PermissionRequest.tool.messageID/callID` 关联到工具卡，
> 显示「allowed once / always / denied」；`questionResolutions.answers` 可直接做问答回执。

---

## 4. E5：应用内通知最小闭环

### 4.1 数据结构（`notify/AppNotifier.kt`，新目录，与其它波次零冲突）

```kotlin
enum class AppNotificationKind { AGENT, PERMISSION, QUESTION, ERROR }

data class AppNotification(
    val id: String,            // "<kind>:<session>:<time>"
    val kind: AppNotificationKind,
    val sessionID: String?,
    val sessionTitle: String?,
    val detail: String?,       // ERROR=错误可读文案 / PERMISSION=permission 名 / QUESTION=首个 header
    val time: Long,
    val viewed: Boolean,       // 触发时该会话已是当前会话（上游同名字段）
)

class AppNotifier(enabled: (Kind) -> Boolean, now: () -> Long, dedupeWindowMs, maxItems) {
    val notifications: StateFlow<List<AppNotification>>   // 旧→新
    fun raise(...): AppNotification?                       // 被门控/去抖吞掉时返回 null
    fun markViewed(sessionID: String): Boolean
    fun dismiss(id: String): Boolean
    fun clear()
    val size: Int
}
```

纯 Kotlin（无 Android / 无 `NotificationManager` / 无音效），因此 JVM 可测；时钟可注入，测试不 sleep。

### 4.2 触发点 → 设置门控（三个死设置变成真消费方）

| 触发 | kind | 门控（`SettingsStore`） | 位置 |
|---|---|---|---|
| `permission.asked` | `PERMISSION` | `notifyPermissions` | `ChatViewModel.kt:1430-1434` |
| `question.asked` | `QUESTION` | `notifyPermissions` | `:1436-1440` |
| `session.error` / `session.status{error}` | `ERROR` | `notifyErrors` | `:1442-1444` |
| 一轮回复完成（`session.idle` 或 `session.status{idle}`） | `AGENT` | `notifyAgent` | `:1446-1452` |

* 门控实现在 `ChatViewModel.notificationEnabled`（`:226-233`）：`PERMISSION`/`QUESTION` 共用 `notifyPermissions`；
  `settingsStore == null`（preview/旧调用方）时按 `SettingsStore` 的默认值（三项全开）。
* 通知是**应用级**的：foreign session 的 ask / 完成也会产生条目（`viewed=false`），
  这正是「我在 A 会话，B 会话跑完了」这个场景；transcript 状态仍然只吃当前会话。
* `session.idle` 只在**当前会话且当时是 RUNNING** 时才当「一轮完成」（避免服务端对本来就 idle 的会话发 idle 帧导致误报）；
  foreign session 无法知道其运行状态，按事件本身采信（同会话同类型 5s 去抖兜底）。
* `viewed` 供 UI 抑制横幅；`markNotificationsViewed(sessionID)` 供进入会话后清「新」标记。

### 4.3 去抖与上限

* 同一 `(kind, session)` 在 `DEDUPE_WINDOW_MS = 5000` 内只出一条（`AppNotifier.kt:94-121`）；
  边界是排他的：正好 5s 后可以再出一轮。
* 列表上限 `MAX_ITEMS = 50`，丢最旧。
* 门控关闭的 kind **不会**写入去抖表（否则「开→关→再开」会吞掉第一帧）。

---

## 5. 需要 Lead 接线（本波次刻意未改 `ui/**`）

所有 revision 都是 `StateFlow<Int>`，从 0 起、每次信号 +1，**值是可粘的**：
即使信号到达时对应页面不可见（`ensureClient` 后 REST 仍可用），下一次 collect 也能看到变化。
注意 `ChatViewModel.start()`/`stop()` 由 SessionScreen 控制（`SessionScreen.kt:282-284`），
**SSE 只在会话页存活期间运行** —— Home/终端页本身不开流，这是 M1 契约，未改。

### 5.1 `project.updated` → Home 项目列表

* 读：`ChatViewModel.projectsRevision`。
* 改 `ui/home/HomeScreen.kt:86` 的 `LaunchedEffect(chatViewModel)` 为
  `LaunchedEffect(chatViewModel, projectsRevision)`，并在其中重跑 `viewModel.listProjects { serverProjects = it }`（`:100`）。
* `projectsRevision` 也建议作为 `ui/home/NewSessionScreen.kt` 项目选择器的刷新键（同一份 `GET /project`）。

### 5.2 `session.created` / `session.deleted` → 会话列表

* 读：`ChatViewModel.sessionsRevision`。
* 三处消费：
  * `ui/home/HomeScreen.kt:96-99`（Home 会话列）——加进同一个 `LaunchedEffect` 的 key，重跑 `listSessions`；
  * `ui/session/SessionScreen.kt:285-287`（drawer 打开时拉列表）——把 `sessionsRevision` 加进 `LaunchedEffect` key，
    这样别处新建/删除会话时抽屉内容跟着变；
  * `ui/session/drawer/SessionDrawer.kt` 的「删除当前会话后回到 Home」流程：`deleteSession` 已本地 `newSession()`，
    但现在服务端 `session.deleted` 到达时 revision 会变，抽屉不会残留已删行。
* 提醒：`snapshot` 的节流刷新（`ChatViewModel.kt:1349`）已覆盖**当前会话**的标题/时间变化，不需重复。

### 5.3 `vcs.branch.updated` → 分支 chip

* 读：`ChatViewModel.vcsRevision`。
* 改 `ui/session/SessionScreen.kt:292-303` 的 `LaunchedEffect(activeSession.id, reviewOpen, mobileReview)`，
  把 `vcsRevision` 加进 key，重跑 `reviewVcsInfo { ... }`（`:297-302`）即可让 `reviewBranch` / `reviewDefaultBranch` 跟随外部 `git checkout`。

### 5.4 `file.edited` → 评审面板失效

* 读：`ChatViewModel.filesRevision`。
* 同上把 `filesRevision` 加进 `LaunchedEffect` key，并在其中重跑 `refreshDiff()` / `refreshTodos()`（`:295-296`）；
  `ReviewPanel` 的 Files 树建议以 `filesRevision` 作为 `reviewListFiles` 的 key（`ChatViewModel.reviewListFiles`）。
* 注意节流：`file.edited` 在一次工具调用里可能连发很多帧，建议在 UI 侧做一次 `debounce(300ms)`（在 `ui/session/**` 内做，本波次不改）。

### 5.5 通知 UI（`ui/App.kt` 顶部横幅 **或** `SessionScreen` 内联）

* 读：`ChatViewModel.notifications: StateFlow<List<AppNotification>>`（`ChatViewModel.kt:207`）。
* 按钮：`dismissNotification(id)` / `clearNotifications()` / `markNotificationsViewed(sessionID)`。
* 文案：本波次已加 6 个 key（三语齐全）：
  `oc_notify_title_agent` / `oc_notify_title_permission` / `oc_notify_title_question` / `oc_notify_title_error`
  / `oc_notify_dismiss` / `oc_notify_dismiss_all`。
  建议 `kind → titleRes` 的映射写在一处；第二行直接用 `notification.sessionTitle ?: detail`。
* 建议门面：`ui/App.kt` 的 `OcTheme { ... }` 内、`DialogHost` 外挂一条置顶横幅
  （`chatViewModel` 在 `OcApp` 已经是可空参数 `App.kt:173`，拿不到就不渲染即可）；
  若要「点通知跳会话」，需要 `AppScaffold` 的 `navController` 与 `openSessions`（`App.kt:253-270`），
  属于 shell 内部状态 → 由 Lead 决定横幅挂在 `AppScaffold` 还是 `OcApp`。
* 抑制策略（推荐）：`!viewed` 才弹横幅，`viewed` 的条目只进列表；`notifications` 里 UI 不需要的就 `dismissNotification`。
* 本波次**没有**系统通知（`NotificationManager`）与音效播放器：那是 E5 的另一半（`sound*` 三个键仍未消费），
  需要 channel/权限/前台策略，明确留给 Lead（见 §6）。

### 5.6 `pty.*` → 终端多端同步：**信号已做，同步未接线（且成本高于本波次预算）**

* 已做：`pty.*` 四类解析成 `ServerSignal` 并累加 `ptyRevision`（`ChatViewModel.kt:190`）。
* 未做的两个原因（两条同时满足才值得接线）：
  1. `ui/terminal/share/PtyApi.kt` **没有** `GET /pty` 列表读（只有 `create`/`exists`/`remove`/`updateSize`），
     要做「别端新建 → 本端长出 tab」必须先补一个列表 API；而 `PtyApi.kt` 不在本波次允许改的文件集内。
  2. 终端是独立路由（`Route.TERMINAL` → `TerminalPanelHost`，`TerminalPanel.kt:134-156`），
     它不持有 `ChatViewModel`，而 SSE 只在 `SessionScreen` 存活期间开着（`start`/`stop` 由 SessionScreen 控制）
     —— 两个页面基本不同时存在，「信号驱动终端刷新」在当前路由/连接模型下收益很小。
  * 结论：`ptyRevision` 已就绪，接线需要 Lead 先决定「终端页是否也持有流」或「终端页打开时重放一次 revision」+ 补 `GET /pty`。

---

## 6. 未做清单（连同原因）

| 项 | 状态 | 原因 |
|---|---|---|
| E5 系统通知（`NotificationManager` / channel / 权限） | **未做** | 需要 Android 通知权限与后台策略，属 UI/平台层；本波次只做数据 + 去抖（任务原文：UI 呈现留给 Lead） |
| E5 音效播放器（`soundAgent`/`soundPermissions`/`soundErrors`） | **未做** | 独立子系统（`sound.ts` 的音频资源 + `SoundPool`），任务只点了通知 |
| `pty.*` 多端同步落地 | 半做 | 见 §5.6（缺 `GET /pty`，且路由模型不匹配） |
| `sync` 包装帧解包 | 未做 | 无上游定义可对齐，需真机抓帧确认（audit §B-2.1 风险仍在） |
| `server.connected` / `global.disposed` 连接态 | 未做 | 属连接生命周期（应做成连接状态流），非读模型失效 |
| `session.compacted` / `mcp.*` / `lsp.*` / `command.updated` / `skill.updated` / `installation.updated` 等目录类事件 | 未做 | 对应 UI 尚不存在（audit §B-1 #36 的“不适用”部分），先解析无用；`command.updated`/`skill.updated` 若将来做斜杠菜单热更新，`Signal` 枚举加一行即可 |
| E2 里 `project.updated`/`vcs.*`/`file.edited`/`session.*` 的 **UI 接线** | 未做 | 边界禁止改 `ui/**`，已写成 §5 的可执行清单 |
| `SettingsStore` | 未改 | 三个 notify 键已存在且默认值向后兼容，无需新增字段（只做加法 → 本轮加法为 0） |

---

## 7. 测试与构建

新增/修改的单测（全部 JVM，无 Android 依赖）：

| suite | 覆盖 |
|---|---|
| `ChatStoreWave5EventTest` | 13 个信号逐条映射 + 枚举与表全等 + 未知 type 仍 `null` + subject（project/pty/file）+ session 路由 + `reduce` 不动状态 + `/global/event` 包装帧上的信号与门控反例；`part.delta` 的 reasoning/state.output/state.error/mime/filename/url、未知字段与对象字段不被猜、孤儿 delta、16k 上限、`field` 缺省回退 `text`；`permission.replied` 的 `reply` 入状态/幂等/上限 20/重问作废旧答案；`question.replied` 的 `answers` 矩阵/畸形容错/`rejected` 区分/幂等 |
| `AppNotifierTest` | 门控（三个 kind 独立开关、关闭时零副作用）、去抖（4999ms 吞 / 5000ms 放行 / 跨会话与跨 kind 不互相吞）、上限 50（此处用 3 验证）、dismiss/clear/markViewed、`id`/`time`/`viewed` 契约 |
| `Wave5EventsWiringTest` | 源码级：信号与通知**在门控之前**（比较 index）、5 条 revision 各有生产者、store 侧 13 个 wire type 都在、`else -> null` 兜底仍在、`reply`/`answers` 通路仍在、`SettingsStore` 三个键被真读、6 个 string 三语齐全且非英文副本 |

构建门（原文命令，真实执行，非 up-to-date 假绿）：

```powershell
$env:JAVA_HOME='C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
# > Task :app:testDebugUnitTest   ← 真实执行（46s）
# > Task :app:assembleDebug       ← 已由同输入的上一轮构建完成
# BUILD SUCCESSFUL in 46s
```

```
suites=93  tests=906  failures=0  errors=0  skipped=0     # app/build/test-results/testDebugUnitTest
基线 86 suites / 827 tests → +7 suites / +79 tests（本波次 +38 tests，其余为并发波次新增）
本波次新增 suite：ChatStoreWave5EventTest=22 / AppNotifierTest=8 / Wave5EventsWiringTest=8，均 0 failures
APK：app/build/outputs/apk/debug/app-debug.apk（32,885,166 bytes, 10:34:17）
设备安装 / 模拟器：**未做**（按要求不安装模拟器，设备验证留给 Lead）
```

并发说明：本轮存在多个波次同时改 `ui/settings/**`、`ui/session/export/**`、`ui/session/composer/**`，
期间出现过 3 次与本次改动无关的失败（`ui.settings.SettingsModelKt` 初始化失败 ×37、`SettingsServer*` 编译错、
`classes.dex` 被并发构建删除），全部按「等 60-90s 重试、不改别人文件」处理；上表数字来自最后一次
**本波次自己发起、真实执行**的全量运行。


---

## 8. 复现与自检命令

```powershell
# 只跑本波次相关的三个 suite
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ChatStoreWave5EventTest" `
  --tests "com.xiufeigo.opencodeshell.AppNotifierTest" `
  --tests "com.xiufeigo.opencodeshell.Wave5EventsWiringTest" --console=plain

# 关键行为的手工确认点（无需真机）
# 1) 目录信号 → 模型选择器自动刷新：改服务端 config 后看 chatViewModel.availableProviders 是否有新值
# 2) 通知：在设置里关掉 notifications.errors，再触发一次 session.error → notifications 不增长
# 3) 去抖：连续两帧 permission.asked（同会话）→ 只出一条 AppNotification
```

## 9. 与审计文档的对齐（供复核者抽查）

* `research/audit_api_events.md` §B-1 的 #1/#3/#16/#26/#27/#36 从「缺失」变为「已消费」；
  #8（part.delta 只认 text）、#12（reply 丢弃）、#14（answers 丢弃）从「部分」变为「1:1 + 入状态」。
* 仍未动的审计项：#7/#9/#10/#11/#13/#15/#17…#25 的其余部分、#37/#38（无上游定义）、#29（v2 误触发 liveness 的副作用）——
  其中 #29 是**真实副作用**（`session.` 前缀会 bump liveness 时钟），本波次未改以免动到 idle 判定；
  若将来切 v2，需要把 `ChatViewModel.kt:410` 的 `startsWith("session.")` 收窄到 v1 具体 type 列表。
* `research/verify_wave4.md` §20 E2/E3/E5 三行：E2 的「面板不自动刷新」→ 目录类已自动刷新，列表类已给信号；
  E3 两条全修；E5 的「设置项已隐藏（无消费方）」→ 三个键已是真消费方。
