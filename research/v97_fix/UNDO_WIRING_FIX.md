# v98 缺陷修复报告 — 会话 ⋮ 菜单 `Undo`

- 执行：缺陷猎人 + 修复工程师（mcode）
- 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
- 触发依据：`research/v97_verify/5556_v98/REPORT_SWEEPC2.md` 条目 2（记 FAIL）、`REPORT_SWEEPB2.md`（引用同一 FAIL）
- 结论一句话：**上游 `Undo` 撤销的是"最后一条消息"，不是会话标题；本壳的 `Undo` 链路从渲染到写入全程已接线，不存在 no-op。原 FAIL 的判据（改名后标题不变）本身违反上游语义。**

---

## 1. 上游口径（判定的唯一标尺）

### 1.1 `Undo` 撤销什么 —— 一条消息

`_upstream/packages/web/src/content/docs/tui.mdx:259` 原文：

> ### undo
> Undo last message in the conversation. Removes the most recent user message, all subsequent responses, and any file changes.

实现（`_upstream/packages/app/src/session/revert.ts:92-99`）：

```ts
const undo = async () => {
  const messages = input.session.history.userMessages()
  const reverted = input.session.data.revertMessageID()
  const boundary = reverted ? messages.findIndex((message) => message.id === reverted) : messages.length
  if (boundary <= 0) return
  const message = messages[boundary - 1]
  if (message) await stage(message, messages[boundary - 2])
}
```

- **作用实体**：`SessionMessageUser`（一条用户消息），不是 session。
- **动作**：`stage()`（`revert.ts:49-82`）→ `POST /api/session/{id}/revert/stage {messageID}`（`:57`），把 revert 边界**停在最近一条用户消息上**；投影侧 `message.id >= boundary` 隐藏（`timeline/controller-projection.ts:25`）。净效果：**最近一条用户消息连同它之后的全部回复一起离开时间线**。
- 上游自己的串同样这么说：`command_session_undo` = 「撤销」、`command_session_undo_description` = 「撤销上一条消息」（本壳 `values-zh-rCN/strings_oc_i18n.xml:91-92` 逐字复用；英文 "Undo the last message" 见 `values/strings_oc_i18n.xml:92`）。

### 1.2 出现条件（使能规则）

`use-session-commands.tsx:256-263`：

```
id: "session.undo",
disabled: !actions.session.identity.params.id || actions.session.history.visibleUserMessages().length === 0,
onSelect: undo,
```

即：**有会话 id** 且 **可见用户消息非空**。

### 1.3 成功 / 失败的 UI 反馈

- **成功**：无 toast。`stage()` 自身只在**失败**时提示（`revert.ts:21-30`）：
  ```ts
  .catch((error) => { showToast({ title: language.t("common.requestFailed"), description: … }) ; return false })
  ```
  成功时唯一的可观测信号是**时间线少了那一轮**（投影结果）。
- **失败**：toast `common.requestFailed`。

本壳已按此实现：失败复用 `R.string.common_requestFailed`（`SessionActionsHost.kt:143`），成功不弹 toast，**未新增任何用户可见字符串**。

### 1.4 与本壳既有能力的关系

**已有对应实现，无需新增功能**：`revertToMessage`（气泡上的「撤销到此」）/`redoSession`（重做）走的是同一条 `session/revert/stage` + `revert/commit` 链路。`Undo` 复用该链路，只是把目标消息交给纯函数 `undoStep` 计算。

---

## 2. 本壳断点（`文件:行号` + 因果链）

逐环核查 `渲染 → 点击 → 宿主 → ViewModel → 传输 → 接口`，**每一环都存在**：

| 环 | 位置 | 原文 / 判定 |
|---|---|---|
| 渲染 | `ui/session/header/SessionHeader.kt:865-872` | `MenuRow(label = stringResource(R.string.command_session_undo), enabled = actionsMenu.undoEnabled, onClick = { expanded = false; actionsHost.start(SessionActionsMenuItem.UNDO) })` |
| 使能 | `ui/session/actions/SessionActionsModel.kt:233-245` | `undoEnabled = visible.isNotEmpty()`（对应上游 `visibleUserMessages().length === 0` 的 disabled） |
| 宿主 | `ui/session/actions/SessionActionsHost.kt:77-79` | `SessionActionsMenuItem.UNDO -> viewModel.undoSession { ok -> if (!ok) failed() }` |
| 纯规则 | `ui/session/actions/SessionActionsModel.kt:175-183` | `undoStep(userMessageIDs, revertMessageID)`，与 `revert.ts:92-99` 逐行对应 |
| 写入 | `ChatViewModel.kt:4536-4583` | `interruptIfRunning` → `stageRevert(id, target)` → v2 `commitRevert(id)` → `_revertMessageID.value = boundary` |
| 传输 | `v2/V2Transport.kt:372-384` | `stageRevert`/`commitRevert` 均有实现 |
| 接口 | `v2/V2Api.kt:875-898` | `POST session/{id}/revert/stage`、`POST session/{id}/revert/commit` |
| 投影 | `ChatViewModel.kt:1013-1031` | `state = combine(_state, _revertMessageID) { … withoutRevertedTail(…) }` → `hiddenMessageIDs` |

**接口在上游确实存在**（读 `_upstream/packages/protocol/openapi.json` 实测，响应码逐字核对）：

```
ROUTE /api/session/{sessionID}/revert/stage  [POST]   opId=session.revert.stage  resp=200,400,401,404,409,500
ROUTE /api/session/{sessionID}/revert/commit [POST]   opId=session.revert.commit resp=204,400,401,404,409
ROUTE /api/session/{sessionID}/revert        [DELETE] opId=session.revert.clear  resp=204,400,401,404,409,500
```

### 断点判定：**不在接线，而在判据**

`REPORT_SWEEPC2.md:56-61` 的造态与读数是：

1. 造可撤销态 = **给会话改名**（`More options → Rename`）；
2. 点 `Undo`；
3. 断言 = **UI 标题 + 后端 `time.updated` 不变** → 判 FAIL。

三处错位：

- **撤销面错**：`session.title` 从来不在 `/undo` 的撤销面内。改名不产生"可撤销态"——用它造态，等于拿一个上游根本不管的属性去检验。
- **断言对象错**：上游成功后的可观测信号是**时间线少了那一轮**，不是标题。标题当然不变，**这正是正确行为**。
- **真机 dump 反证**：我解析了报告自带的 `c2_030_undostate2.xml`（Undo 点击后的 dump），全部文本节点为：
  ```
  E2ECHK5556c2 RENAME / E2ECHK5556c2 RENAME / Build anything / E2ECHK5556c2 hello / Space Bunny Free
  ```
  用户消息 **`E2ECHK5556c2 hello` 仍在时间线上**。按上游语义，Undo 应让它消失。所以"该轮没被撤销"是**真信号**——但它指向的是**运行时行为**（写请求是否落地），不是"菜单项没接线"。

结论：**本壳 `Undo` 没有断在任何一环的代码上**。若真机复验仍复现"点了没反应"，落点在 `stage/commit` 的**运行时**（网络、`_state` 是否有用户消息、是否 409 `SessionBusyError`），需在真机按 §5 步骤定位，**不臆造代码改动**。

---

## 3. 改动（diff）

**未改任何生产代码。** 判定依据：链路完整、接口存在、语义已与上游一致——按任务书"若上游依赖的接口在本壳不存在则写死缺口"，本例接口**存在且已接上**，故无可修的缺口；强行改动只会引入偏离上游的行为。

唯一新增：JVM 钉桩（纯新增文件，不改既有断言）。

### 新增文件 `app/src/test/java/com/xiufeigo/opencodeshell/SessionUndoWiringTest.kt`（8 个用例）

| 用例 | 类型 | 钉住什么 |
|---|---|---|
| `undoTargetsAMessageNotTheSessionTitle` | 语义 | **撤销面是消息不是标题**；改名不改变撤销面（直接否定 SWEEPC2 的造态前提） |
| `undoingRemovesTheLastUserMessageAndEverythingAfterIt` | **状态转换** | 可撤销态 → 撤销后：目标 `u3`、隐藏集 `[u3, a3]`、可见集 `[u1,u2]`（= 上游语义预期） |
| `consecutiveUndosWalkTheBoundaryBackwardsOneMessageAtATime` | 状态转换 | `u3 → u2 → u1`，退到第一条时为 `null`（上游 `boundary <= 0`） |
| `aSuccessfulUndoIsObservableAsRedoBecomingAvailable` | 状态转换 | 撤销成功 = 边界移动 = `redoEnabled` 由 false 变 true（no-op 则永远 false） |
| `theUndoRowIsWiredToANonNullAction` | **结构 pin** | `Undo` 行存在 ⇒ 绑到非空动作（标签/使能/派发/收菜单四段） |
| `theUndoMenuItemReachesTheViewModelAndReportsFailure` | 结构 pin | `UNDO` 分支非空、接到 `undoSession`、失败走 `common_requestFailed` |
| `undoSessionIssuesTheRevertWriteAndStoresTheBoundary` | 结构 pin | 真的发 `stageRevert` + `commitRevert`；**且 `if (!committed)` 早于写边界**（失败绝不留下幻影边界） |
| `theRevertedTailIsCutByTheSharedPureProjection` | 结构 pin | 裁尾巴走共享纯函数，单一实现 |

---

## 4. 为什么不降强度

- **既有 0 改动**：`SessionActionsTest.kt` 等既有类**逐字未动**；`SessionUndoWiringTest` 为纯新增文件，无任何 `assert` 被删改或放宽。
- **新增断言只会更严**：除正向钉桩外，`undoSessionIssuesTheRevertWriteAndStoresTheBoundary` 用 `assertTrue(body.indexOf("if (!committed)") < body.indexOf("_revertMessageID.value = boundary"))` 钉住**失败路径不得发布边界**——这比原报告"点了没反应"的观察更细。
- **三语零新增**：`command_session_undo` / `common_requestFailed` 全部复用上游既有 key（`values/`、`values-zh-rCN/`、`values-zh-rTW/` 三处本就齐备），故 `StringsLocaleParityTest` 无需新增条目即通过。

---

## 5. 测试证据

命令（前台跑完，仅 `:app:testDebugUnitTest --tests`，抢锁→写锁→跑完删锁）：

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.SessionUndoWiringTest" `
  --tests "com.xiufeigo.opencodeshell.SessionActionsTest" `
  --tests "com.xiufeigo.opencodeshell.SessionRevertRestoreTest" `
  --tests "com.xiufeigo.opencodeshell.SessionOverflowMenuTest" `
  --tests "com.xiufeigo.opencodeshell.SessionHeaderNarrowTitleTest" `
  --tests "com.xiufeigo.opencodeshell.SessionHeaderReviewSlotTest" `
  --tests "com.xiufeigo.opencodeshell.SessionTitleInlineEditTest" `
  --tests "com.xiufeigo.opencodeshell.SessionRenameSeedTest" `
  --tests "com.xiufeigo.opencodeshell.TimelineRowsTest" `
  --tests "com.xiufeigo.opencodeshell.TimelineFidelityTest" `
  --tests "com.xiufeigo.opencodeshell.ChatStoreTest" `
  --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest" `
  --console=plain
```

原文结尾：

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 5s
52 actionable tasks: 2 executed, 50 up-to-date
```

逐类用例数（`app/build/test-results/testDebugUnitTest/`，**共 140 例 / 0 失败 / 0 错误 / 0 跳过**）：

| 类 | 用例 | 失败 |
|---|---|---|
| `SessionUndoWiringTest`（新） | 8 | 0 |
| `SessionActionsTest` | 12 | 0 |
| `SessionRevertRestoreTest` | 3 | 0 |
| `SessionOverflowMenuTest` | 6 | 0 |
| `SessionHeaderNarrowTitleTest` | 5 | 0 |
| `SessionHeaderReviewSlotTest` | 6 | 0 |
| `SessionTitleInlineEditTest` | 10 | 0 |
| `SessionRenameSeedTest`（改名语义） | 12 | 0 |
| `TimelineRowsTest` | 23 | 0 |
| `TimelineFidelityTest` | 40 | 0 |
| `ChatStoreTest` | 12 | 0 |
| `StringsLocaleParityTest` | 3 | 0 |
| **合计** | **140** | **0** |

> 迭代记录：新类首跑 8 例中 1 例红，定位为**我的切片写法缺陷**（`substringAfter` 吃掉了待断言的标签本身），非产品缺陷；改为按 `MenuRow(` 切块后全绿。产品代码全程未动，故不存在"改测试迁就实现"。

---

## 6. 真机复验步骤（**按上游语义，不要再用改名造态**）

> 关键修正：**不要再用改名验证 `Undo`**。要造"可撤销态"，必须造**一轮真实的对话**。

1. **造可撤销态**（新会话）：`New session → Create session`，composer 输 `UNDO-PROBE-<随机串>` + `keyevent 66`，等 `idle`（后端 `GET /api/session/{id}/message?order=asc&limit=80` 出现 `type=idle` 为准）。
2. **记录 undo 前状态**：记下**最后一条 user 消息的 id** 与消息总数。
3. 点 `More options → Undo`。
4. **断言 UI**（这是上游的成功信号，不是标题）：
   - **最后一条用户气泡 `UNDO-PROBE-*` 从时间线消失**；
   - 弹出的 `Redo` 行**变为可用**（`redoEnabled` 由 false 变 true）——no-op 则它永远灰。
5. **断言后端**（ground truth）：
   - `GET /api/session/{id}/message?order=asc&limit=80` → 被撤销轮次的消息**不再返回**（v2 已 commit 落库）；
   - 或 `GET /api/session/{id}` → `revert.messageID` = 第 2 步记下的那条 id。
6. **失败判读**：若第 4 步两项都没发生，抓 `logcat -s ChatViewModel:*`（`undoSession` 有 `Log.w(TAG, "revert failed: …")` / `"revert commit failed: …"`）与 toast `common_requestFailed`：
   - 出现 `revert commit failed` → 服务端对 `revert/commit` 返非 2xx（如 409 `SessionBusyError`，会话仍 running）；
   - 两者都没有、也没 toast → `undoStep` 返回 `null`（会话里没有可见用户消息）→ 应查 `_state` 是否已装载该轮消息。
7. **顺带复核非回归**：`Fork session` / `Import session` / `Move to…` / `New worktree` 菜单项仍在且可用；会话改名提交语义不变（改名本就不受 `Undo` 影响）。

---

## 7. 未闭环

| 项 | 状态 | 原因 |
|---|---|---|
| 真机 `Undo` 端到端复验 | **未做** | 本轮纪律为纯 JVM（只允许 `testDebugUnitTest`，禁止装机/跑 gradle 以外的真机动作），且无设备可用窗口。§6 步骤已写成可直接执行的判据。 |
| 若真机仍无反应，落点归属 | **待真机定位** | 代码侧八环齐全、接口在 openapi 中存在；剩余可能全在运行时（`stage/commit` 返回码、`_state` 未装载用户消息、409 busy）。§6.6 给出逐条判读。**在拿到 logcat 读数前不改代码**——那会变成臆造。 |
| `Undo` 成功后的显式反馈 | **按上游保持"无 toast"** | 上游成功不发 toast（`revert.ts:21-30` 只在 catch 里提示）。本壳照做，不擅自新增反馈串。 |
| 菜单项「Redo」文案 | **既有行为，未动** | `SessionActionsModel.showsRedo` 使其仅在可用时显示（v51 决策），属既有 PASS 面，本轮不触碰。 |
