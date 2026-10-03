# EDITING_QUEUED_LOCK — v131：编辑队列行后 `Queue` 入口永久消失（`editingQueued` 只进不出）

- **状态：✅ 已修复 + 已回归（锁已释放；本文件即锁/交付物，仓库无独立 `*.lock` 约定）**
- 负责人：v131 修复工程师（mcode）
- 日期：2026-10-04
- 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
- 输入证据：`research/v97_verify/5554_md/REPORT_QUEUE_COVERAGE.md` §1.5「编辑贷款（`queueEditOpen`）卡死未复位」
- 环境：`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`
- 命令白名单遵守：全程只跑 `:app:testDebugUnitTest --tests "<类名>"`；未 `assembleDebug` / `clean` / 全量 / 装机
- 行尾：未用任何 git 回退/checkout 改行尾（`autocrlf=false`），只经编辑工具改动

---

## 0. 结论（一句话）

`editingQueued` 被**复制成两份状态**，而**只有一条复位路径同时关闭了两份**：提交（confirm）只清了行 `queueEdit`，
没清 chip 用的 `queueEditOpen` 闩；`ComposerDelivery.alternate()` 首行 `if (editingQueued) return STEER` 于是恒成立，
`Queue` 入口永久消失。v131 把贷款收敛成**单一状态**（chip 标志改为派生 `val`），并按上游补齐全部出口。

---

## 1. 定性：置位路径 / 复位路径 全列

> 行号分两栏：**修复前** = v130 定案时的行号（取证口径）；**修复后** = 当前工作区行号。

### 1.1 消费点（缺陷的放大器）

| 层 | 修复前 | 修复后 | 原文 |
|---|---|---|---|
| 送达 | `ComposerDelivery.kt:54-58` | 不变 | `fun alternate(running, behavior, editingQueued, hasContent) { if (editingQueued) return STEER; if (!running \|\| !hasContent) return null; … }` |
| 早返回 | **`ComposerDelivery.kt:55`** | 不变 | `if (editingQueued) return STEER` |
| composer | `Composer.kt:636` | `Composer.kt:646` | `ComposerDelivery.alternate(running, followUpBehavior, queueEditOpen, hasContent)` |
| 提交时算 delivery | `Composer.kt:1198` | 不变 | `val offered = ComposerDelivery.alternate(running, followUpBehavior, editing != null, hasContent)` |

`ComposerDelivery.kt:55` 的早返回**先于** `!running \|\| !hasContent`（与上游 `queue.ts:218-221` 同序，见 §1.4），
所以「编辑中」一旦为真，chip 恒显 `Steer`，**与运行态、有无内容都无关**。

### 1.2 UI 层（真凶）：`queueEdit` + `queueEditOpen` 两份状态

**置位（唯一入口）**

| 修复前 | 修复后 | 原文 |
|---|---|---|
| `Composer.kt:966-973` | `Composer.kt:976-983` | `fun editQueued(row) { queueEdit = row; queueEditOpen = true; … }` |
| `Composer.kt:968` | — | `queueEditOpen = true` ← **唯一的置位点** |
| `Composer.kt:967` | `Composer.kt:979` | `queueEdit = row` |

**复位**

| 出口 | 修复前 | 修复后 | 原文 | 是否关掉**两份** |
|---|---|---|---|---|
| 取消（Esc） | `Composer.kt:975-978` | `Composer.kt:987-989` | `fun cancelQueueEdit() { queueEdit = null; queueEditOpen = false }` | ✅ 两份都关 |
| 会话切换 | `Composer.kt:992` | `Composer.kt:1024` | `LaunchedEffect(draftKey) { … cancelQueueEdit() }` | ✅ 两份都关（但只因换了会话） |
| **提交（confirm）** | **`Composer.kt:1270-1272`** | **`Composer.kt:1300-1305`** | 修复前：`if (editing != null) { queueEdit = null; queueConfirm(editing.id, model) }` | ❌ **只关行，闩留在 true** ← **缺失的出口** |
| 行被消费 / 被删 / 被 Steer | — | `Composer.kt:1418-1425` | 修复前：`onSteer = queueSteerBy` / `onRemove = queueRemoveBy`（裸转发，不碰贷款） | ❌ 无此出口 |
| 回合结束（队列 drain） | — | `Composer.kt:996-1006` | 修复前：无 | ❌ 无此出口 |

**「只能进不能出」的机械原因**：`queueEditOpen` 的置位者 `editQueued()` 与复位者 `cancelQueueEdit()` 是一对，
而**提交路径绕过了这对函数**（自己写 `queueEdit = null`）。于是存在一条
`editQueued() → submit 提交 → 闩永久 true` 的边：进去有边，出来无边。

### 1.3 纯模型层（`ComposerQueue.Model.editingId`）——**本身不缺出口**

| 复位 | 行号 | 原文要点 |
|---|---|---|
| `remove(id)` | `ComposerQueue.kt:84-87` | `if (editingId == id) editingId = null` |
| `promoteToSteer(id)` | `ComposerQueue.kt:93-97` | 委托 `remove(id)` |
| `replace(rows)` | `ComposerQueue.kt:121-128` | `if (editingId != null && _rows.none { it.id == editingId }) editingId = null` |
| `confirmEdit()` | `ComposerQueue.kt:154-158` | `editingId = null` |
| `cancelEdit()` | `ComposerQueue.kt:161-163` | `editingId = null` |
| `clear()` | `ComposerQueue.kt:166-169` | `editingId = null` |

**关键定性**：纯模型**早就有**「行消失 ⇒ 贷款结束」的收敛语义（`replace`/`remove`），
但 native 的 composer **没有把这些语义接到 UI 的贷款上** —— UI 贷款与 `Model` 是两套独立状态。
`replace()` 的收敛甚至就是上游 `queue.ts:93-97` 的翻版，只是**只作用在模型上、够不到 chip**。

### 1.4 上游对照（为什么 native 才是错的）

`_upstream/packages/app/src/session/composer/queue.ts` 只有**一份**贷款（`state.editing`），
且**靠推导而非事件**维持（`:93-97`）：

```ts
createEffect(() => {
  const editing = state.editing
  if (!editing || mutation.isPending || queued().some((item) => item.id === editing.id)) return
  setState("editing", undefined)
})
```

`alternate()` 读的也是同一份（`:219` `if (state.editing) return "steer"`）。
上游出口清单：`:72` 变更后 cancelEdit、`:98` `onCleanup`、`:133` steer 命中、`:139` remove 命中、
`:152` 改开另一行、`:172-182` cancelEdit（`if (!editing) return` ⇒ **幂等**）、`:185` confirmEdit 守卫、`:197` pristine ⇒ cancelEdit。
⇒ **上游不可能锁死**：贷款是 `queued()` 的推导，掉出队列即掉贷款。
native 把它降级成**离散事件 + 第二个闩**，才产生只能进不能出的边。

### 1.5 为什么 Esc 也救不回来（v130 观测已确认）

- 代码上 Esc 唯一的复位入口是 `Composer.kt:1601-1604`：
  `if (event.key == Key.Escape && queueEdit != null && !popoverOpen) { cancelQueueEdit(); return true }`
- **提交之后 `queueEdit` 已经是 `null`**（`Composer.kt:1271`），所以 `queueEdit != null` 为 **false** ⇒
  **Esc 分支根本不再进入**，`cancelQueueEdit()` 不会被调用，闩永远没人关。
  → 修复前 Esc **在语法上就不可能**复位。
- 即便改判 `queueEditOpen`，`onPreviewKeyEvent` 也要先过 IME：v130 实测 keyevent 被 IME 先行消费
  （`mInputShown` 由 `true`→`false`，见 `REPORT_QUEUE_COVERAGE.md` §1.4/§1.5），事件未必触达 composer。
  → **「靠 Esc 复位」本身不可靠**，不能作为出口设计。
- 唯一可靠的绕过是新建会话（重建 composer，`LaunchedEffect(draftKey)` 才重新执行 `cancelQueueEdit()`）。

### 1.6 缺失的出口 —— 明确结论

> **缺的是「提交后复位」这一条**（`Composer.kt:1270-1272` 修复前）。
> 同时**结构性缺两条上游有的出口**：「行消失 ⇒ 贷款结束」（收敛）与「Steer/Remove 命中被编辑行 ⇒ 贷款结束」。
> 三者共同构成「编辑结束后 `editingQueued` 必为 false」的完整出口集；`回合一结束（队列 drain）`不是独立出口，
> 它是「行消失」的**实例**（drain ⇒ 行离开队列 ⇒ 收敛关闭），详见 §4.3。

---

## 2. 修复：让 `editingQueued` 必然复位

**思路（照上游形状）**：贷款**只留一份**，chip 标志由它**派生**；出口既补齐离散事件，又补上上游的**收敛**。
「两份状态发散」这一类缺陷从此**不可表达**。

### 2.1 纯函数出口表（新增，`ComposerQueue.kt:262-300`）

```kotlin
enum class EditLoanEvent { CONFIRM, CANCEL, SESSION_SWITCH, STEER, REMOVE }

fun endEditLoan(row: Row?, event: EditLoanEvent, targetId: String? = null): Row? = when (event) {
    EditLoanEvent.CONFIRM, EditLoanEvent.CANCEL, EditLoanEvent.SESSION_SWITCH -> null
    EditLoanEvent.STEER, EditLoanEvent.REMOVE -> if (row != null && row.id == targetId) null else row
}

fun convergeEditLoan(row: Row?, queuedIds: Collection<String>, busy: Boolean): Row? =
    if (row == null || busy) row else row.takeIf { it.id in queuedIds }
```

- **全函数 + 幂等**：对任意 (贷款, 事件) 都有定义；终态事件施加在已关闭的贷款上仍是 `null`（不抛、不复活）。
- `targetId` 让 STEER/REMOVE **只**在命中被编辑行时成为出口 —— 打别的行不是本贷款的出口（上游 `:133/:139` 同义）。
- `busy` = 上游 `mutation.isPending`：**只延后、不锁定**（下一次收敛必关，见 pin `aBusyMutationDelaysTheCloseButNeverLocksIt`）。

### 2.2 UI 侧接线（`Composer.kt`）

| 处 | 修复后 | 作用 |
|---|---|---|
| `:636-637` | `var queueEdit by remember { mutableStateOf<ComposerQueue.Row?>(null) }` / `val queueEditOpen = queueEdit != null` | **单一状态 + 派生标志**（原 `:627` 的闩删除） |
| `:979` | `queueEdit = row` | 置位（开新贷款天然覆盖旧贷款，上游 `queue.ts:152`） |
| `:988` | `queueEdit = ComposerQueue.endEditLoan(queueEdit, EditLoanEvent.CANCEL)` | 取消出口（Esc / 会话切换共用） |
| `:996-1006` | `LaunchedEffect(queuedRows, queueBusy) { … convergeEditLoan … }` | **收敛出口**：行被消费/被删/drain ⇒ 贷款结束 |
| `:1304` | `queueEdit = ComposerQueue.endEditLoan(editing, EditLoanEvent.CONFIRM)` | **补上缺失的提交出口** |
| `:1418-1425` | `onSteer` / `onRemove` 先关命中贷款再转发 VM | Steer/Remove 命中被编辑行的即时出口 |

### 2.3 diff 摘要

```
 app/.../ui/session/composer/Composer.kt  | 71 +++++++++++++++++-----
 app/.../ui/session/composer/ComposerQueue.kt | 62 +++++++++++++++++++
 2 files changed, 119 insertions(+), 14 deletions(-)
```

删除 14 行 = 第二个闩（`var queueEditOpen …`）＋三处裸 `queueEdit = null` / 裸转发；
新增 119 行 = 纯函数出口表（62）＋ UI 接线与真值说明（57）。**无新增用户可见字符串。**

---

## 3. 为什么不降强度

| 维度 | 处置 |
|---|---|
| `ComposerDelivery.kt` | **一个字节未改**。`if (editingQueued) return STEER` 原地保留，早返回语义与上游 `queue.ts:219` 一致。 |
| 队列条渲染 | `rowActions` / `showCount` / `isParkedFirst` 未动；面板入参 `editingId = queueEdit?.id` 语义不变。 |
| `Steer`/`Remove` 语义 | `rowActions` 期望值逐条钉死（编辑行仅 `Remove`、非编辑行 `Steer`+`Remove`、`SteerLabel` 运行/停机）。 |
| `followUpBehavior` 默认 `steer` | `delivery()` / `normalize()` / `isQueue()` / `BEHAVIORS` 全部钉死；`generation=V1` 门控钉死。 |
| v103–v130 全部 pin | 62 个相关类 / **557 个用例** 0 失败（§5），**用例数未减少**（新增 14，无删改既有断言）。 |
| 断言 | 只**新增** pin；**未放宽/删除任何既有断言**。首轮跑出的 4 处红是**新 pin 自身写错**（把上游次序当缺陷），修正的是 pin，不是产品（见 §4.4）。 |

---

## 4. 状态机真值表与 pin

新增 `app/src/test/java/com/xiufeigo/opencodeshell/QueueEditLoanExitV131Test.kt`（**14 个用例，纯函数，JVM 可判**）。

### 4.1 真值表 —— 「不存在只能进不能出的状态」

贷款状态只有 `{ 未编辑, 编辑中(row) }`。行是终态事件：

| # | 起点 | 事件 | 终态贷款 | chip（running+content, followUp=steer） | 判 |
|---|---|---|---|---|---|
| 1 | 编辑中 | 提交 CONFIRM | **未编辑** | `Queue` | ✅ 入口恢复 |
| 2 | 编辑中 | 取消 CANCEL（Esc） | **未编辑** | `Queue` | ✅ |
| 3 | 编辑中 | 会话切换 SESSION_SWITCH | **未编辑** | `Queue` | ✅ |
| 4 | 编辑中 | Steer 命中本行 | **未编辑** | `Queue` | ✅ |
| 5 | 编辑中 | Remove 命中本行 | **未编辑** | `Queue` | ✅ |
| 6 | 编辑中 | Steer/Remove **别的行** | 编辑中（保留） | `Steer` | ✅ 非本贷款出口 |
| 7 | 编辑中 | 行离开队列（被消费/被删/drain） | **未编辑** | `Queue` | ✅ 收敛出口 |
| 8 | 编辑中 | 回合结束且队列 drain | **未编辑** | `Queue` | ✅ drain ⇒ 行消失 ⇒ 收敛 |
| 9 | 编辑中 | 回合结束但队列 **parked**（行仍在） | 编辑中（保留） | `Steer`（且 `!running` 时 chip 恒 `Steer`） | ✅ 见 §4.3 |
| 10 | 编辑中 | 事件重复 1/2/3 次 | 恒 **未编辑** | 恒 `Queue` | ✅ 幂等 |
| 11 | 未编辑 | 任意事件（含任意 `targetId`） | 恒 **未编辑** | 恒 `Queue` | ✅ 不复活 |
| 12 | 编辑中 + `busy=true` | 行消失 | 编辑中（**延后**） | — | ✅ 下一次 `busy=false` 收敛必关 |
| 13 | 编辑中 + `busy=false` | 行消失 | **未编辑** | `Queue` | ✅ 延后不是锁定 |
| 14 | 编辑中 | 开另一行 | 编辑中（新行） | `Steer` | ✅ 同时只一个贷款 |

**全组合断言**：`noStateIsOneWayAcrossTheWholeEventSet`（3 起点 × 5 事件）、`everyTerminalEventClosesTheLoanFromEveryState`
（2 起点 × 5 终态）、`noReachablePostExitStatePinsTheChip`（2 起点 × 5 事件 × 3 `targetId` × 2 `busy` = 60 组）
—— 逐组断言「贷款已关 ⇒ chip 与『从未打开编辑』逐格相同；贷款仍在 ⇒ chip 就是编辑态」。

### 4.2 「早返回只在编辑中成立」pin

`theEarlyReturnOnlyHoldsWhileEditing`：贷款开着 ⇒ `alternate` 恒 `Steer`（2 behavior × 2 running）；
贷款一关 ⇒ 立刻让位给「行为取反」：followUp=`steer`→`Queue`、followUp=`queue`→`Steer`；
`!running` 或无内容 ⇒ `null`。**并把「编辑中优先于 idle/无内容」钉成契约**（上游 `queue.ts:219` 同序，v131 不动）。

`theQueueEntryComesBackAfterEveryTerminalExit`：2 起点 × 5 终态 ⇒ 每格都断言 `Queue`（followUp=steer）/ `Steer`（followUp=queue）。
**这正是 v130 里恒为 `Steer`、再也不出现的那一格。**

### 4.3 回合结束为何不是独立出口（诚实说明）

上游**没有**「回合结束就关编辑」的规则：`queue.ts` 只在**行离开 `queued()`** 时关贷款。
native 的 VM 在回合 settle 时 drain 队列 ⇒ 行离开 ⇒ 收敛关闭（真值表 #8）。
若队列 **parked**（行仍在 —— v130 §3.4 实测 idle 下队列条持续可见），贷款**应当保留**：
那一行仍可编辑，且 `!running` 时 chip 本就显示 `Steer`（上游 `queue.ts:219`），`Queue` 入口在下一回合 `running` 恢复时自然回来。
**强行在「回合结束」无条件关贷款反而会打断 parked 行的编辑**，故不引入该出口 —— 出口集按上游语义给足。

### 4.4 首轮 4 红的处置（不掩盖）

首跑新类红 4 个，**全部是新 pin 自己写错**，产品行为与上游一致：
① `noStateIsOneWayAcrossTheWholeEventSet` 把 STEER/REMOVE 瞄向固定 `row.id`，起点为 `other` 时当然不关（pin 逻辑错）；
②③ `alternate(editingQueued=true, hasContent=false/running=false)` 期望 `null`，实为 `Steer` —— **上游 `queue.ts:218-222` 就是这个次序**（pin 期望错）；
④ 结构性 pin 的正则 `queueEditOpen\s*=` 命中了派生式自己（pin 期望错）。
处置：**改 pin 使其断言上游真实契约**，产品代码一行未为此让步。

### 4.5 结构性 pin（防止第二个闩复活）

`theChipFlagIsDerivedFromTheLoanAndNotASecondLatch`：源码扫描断言
`val queueEditOpen = queueEdit != null` 在位、`var queueEditOpen` 不存在、`queueEditOpen =` 全文**恰好 1 次**、
`var queueEdit by remember` **恰好 1 次**、`EditLoanEvent.CONFIRM`/`CANCEL`/`convergeEditLoan(` 均接线、出口表在纯文件里。

---

## 5. 反向验证（去掉复位路径 ⇒ pin 必须红）

**做法**：把 `endEditLoan` 的三个终态事件临时改回旧行为（返回 `row`，即「无出口的闩」），
其余原样，跑同一个类。

**原文（`--tests com.xiufeigo.opencodeshell.QueueEditLoanExitV131Test`）**：

```
> Task :app:testDebugUnitTest FAILED
QueueEditLoanExitV131Test > repeatingATerminalEventIsHarmless FAILED
QueueEditLoanExitV131Test > everyTerminalEventClosesTheLoanFromEveryState FAILED
QueueEditLoanExitV131Test > noStateIsOneWayAcrossTheWholeEventSet FAILED
QueueEditLoanExitV131Test > theQueueEntryComesBackAfterEveryTerminalExit FAILED
14 tests completed, 4 failed
BUILD FAILED in 4s
```

失败断言原文：

```
=== theQueueEntryComesBackAfterEveryTerminalExit
org.junit.ComparisonFailure: after CONFIRM the Queue entry must be offered again (loan=Row(id=msg_a, …)) expected:<[queue]> but was:<[steer]>
=== everyTerminalEventClosesTheLoanFromEveryState
java.lang.AssertionError: start=Row(id=msg_a, …) CONFIRM(target=null) must end the loan, got Row(id=msg_a, …) expected null, but was:<Row(id=msg_a, …)>
=== noStateIsOneWayAcrossTheWholeEventSet
java.lang.AssertionError: CONFIRM(target=null) must close a loan on Row(id=msg_a, …) expected null, but was:<Row(id=msg_a, …)>
=== repeatingATerminalEventIsHarmless
java.lang.AssertionError: repeat #0 of CONFIRM must stay closed expected null, but was:<Row(id=msg_a, …)>
```

**关键一行**：`expected:<[queue]> but was:<[steer]>` —— 与 v130 真机症状（`evidence/state1.xml` 恒显 `Steer`）**逐字同形**。
随后已把 `endEditLoan` 恢复为修复版（§2.1），并重跑至全绿。

---

## 6. 回归证据

### 6.1 新 pin 类

```
.\gradlew :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.QueueEditLoanExitV131Test" --console=plain
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 3s
```
XML：`tests=14 failures=0 errors=0 skipped=0`
（`theQueueEntryComesBackAfterEveryTerminalExit` / `noStateIsOneWayAcrossTheWholeEventSet` / `everyTerminalEventClosesTheLoanFromEveryState` /
`repeatingATerminalEventIsHarmless` / `noReachablePostExitStatePinsTheChip` / `theEarlyReturnOnlyHoldsWhileEditing` /
`aTurnThatDrainsTheQueueEndsTheLoan` / `aBusyMutationDelaysTheCloseButNeverLocksIt` / `theEditedRowLeavingTheQueueClosesTheLoan` /
`steerOrRemoveOfAnotherRowIsNotAnExitForThisLoan` / `reopeningSupersedesThePreviousLoan` /
`theChipFlagIsDerivedFromTheLoanAndNotASecondLatch` / `theQueueRowActionSetIsUnchanged` / `followUpBehaviorStillDefaultsToSteer`）

### 6.2 受影响全部类（源码扫描三个改动文件的 56 个类 + 6 个纯函数队列类）

| 组 | 命令 | 结果 |
|---|---|---|
| 56 个源码扫描类 | `:app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.<各 56 类>" --console=plain` | `BUILD SUCCESSFUL in 46s`；**56 类 / 483 用例 / 0 失败 / 0 错误 / 0 跳过** |
| 6 个纯函数队列类 | 同上，`Wave24bComposerQueueTest`(23) `Wave24bQueueWiringTest`(11) `QueueRowActionsPinTest`(10) `ParkedSteerRowTest`(13) `QueuedLabelTest`(5) `PromptDeliveryStallTest`(12) | `BUILD SUCCESSFUL in 3s`；**74 用例 / 0 失败** |
| **合计** | — | **62 类 / 557 用例 / 0 失败** |

覆盖到的关键既有 pin（均未红、原文未动）：`QueueTimelineFloorV100Test`（`queueSupported && queuedRows.isNotEmpty()` 挂载规则）、
`Wave24bComposerWiringTest`（`editQueued(` / `onQueueConfirm` / `onQueueSteer` 接线）、`AttachmentDropZoneTest`、
`SessionTabFocusSendGateTest`、`AgentChipOpenRequestV97Test` / `AgentChipFocusRestoreV97Test`、
`ComposerSubmitTest` / `ComposerM1_5Test`（提交清空编辑器）、`ComposerPopoverEscTest`、`ComposerImeRestartCaretV81Test` 等。

---

## 7. 真机复验步骤（emulator-5554，`1366x768` / `160`）

前置：长回合「count from 1 to N」起跑 → 回合运行中（composer 显示 `Stop`）→ IME 打开且 composer 有内容 → chip 行出现 `Queue` → 点 `Queue` 造出队列条。
**断言顺序与期望**：

| # | 动作 | 期望（v131 后） | v130 实测（缺陷） |
|---|---|---|---|
| 1 | `adb shell input tap <row 正文中心>` 进编辑 | composer 回填该行文本；该行 `Steer` 消失、仅余 `Remove` | 同（此处 PASS） |
| 2 | composer 追加 ` EDITED-V131` → 点 `Send` **提交** | 队列条按设计消失/重投；**下一轮 `Queue` 入口必须重新出现** | ❌ chip 恒 `Steer`，`Queue` 不再出现 |
| 3 | 重新造队列行 → 进编辑 → **Esc/硬件键取消** | 贷款关闭；`Queue` 入口恢复 | ❌ Esc 被 IME 先行消费，救不回 |
| 4 | 重新造队列行 → 进编辑 → **不提交，直接让回合把该行消耗掉** | 行消失即贷款结束；下一轮 `Queue` 入口恢复 | ❌ 未测到（无此出口） |
| 5 | 复核队列条动作 | 停机首行 `Send`、运行中 `Steer`（v130 格三 FAIL 项，本轮**不在范围内**） | — |
| 6 | 复核 768/600 基线 | `readable` 140 / 92 / 51 **不回退** | — |
| 7 | 复核 `followUpBehavior` 默认 | 设置项默认 `steer`；空闲提交恒 steer | — |

取证建议：`uiautomator dump` 后 grep chip 行文本（`Queue` vs `Steer`）与队列行 `Steer`/`Remove` 标签；
**本轮未装机、未 gradle 打包**，以上 7 步尚未执行（见 §8）。

---

## 8. 未闭环

1. **真机复验未执行** —— v131 全程遵守「不装机 / 不 assembleDebug」，§7 的 7 步需由有设备的验证轮执行；
   本报告的结论目前只有 JVM 纯函数证据。
2. **VM 侧未接线核对** —— `ChatViewModel.kt` / `onQueueConfirm` / `onQueueSteer` / `onQueueRemove` 的实现
   **按纪律未读未改**（禁改区）。收敛出口依赖 VM 持续把 `queuedRows` 更新到最新（行离开队列）；
   若 VM 长期不刷新 `queuedRows`，收敛出口会晚触发（不锁死，Esc/再次编辑仍可用）。**未验证 VM 的刷新时效**。
3. **v130 格三 FAIL 未处理** —— parked 首行 `Steer`→`Send`（`REPORT_QUEUE_COVERAGE.md` §3）属独立缺陷，
   本轮 pin 只保证模型侧语义（`SteerLabel.SEND`）未被破坏，未修渲染层。
4. **`busy` 延后的上界未钉** —— `convergeEditLoan` 在 `busy=true` 时保留贷款；已钉「下一次 `busy=false` 必关」，
   但**未钉 `queueBusy` 一定会回落**（VM 契约，超出本轮写权限）。
5. **parked 编辑的取舍已记录** —— 回合结束但队列 parked 时贷款保留（§4.3），属刻意选择，
   若产品要求「回合结束即强制退出编辑」需另立项。
6. **`research/v97_verify/5554_md` 的 `/sdcard` 44 项残留** 属 v130 遗留（删除被权限网关拦截），本轮未触碰。

---

## 9. 改了哪些文件

| 文件 | 改法 |
|---|---|
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt` | 删第二个闩、贷款单一化 + 派生标志；补提交出口与收敛 effect；Steer/Remove 命中即关；`+57 / -14` |
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ComposerQueue.kt` | 新增纯函数出口表 `EditLoanEvent` / `endEditLoan` / `convergeEditLoan`（`+62`，纯逻辑） |
| `app/src/test/java/com/xiufeigo/opencodeshell/QueueEditLoanExitV131Test.kt` | 新增 14 个真值表 pin（新建） |
| `research/v97_fix/EDITING_QUEUED_LOCK.md` | 本报告（先写盘抢锁 → 定稿释放） |

**未触碰**：`_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/servers/**`、`ui/home/**`、
`ui/terminal/**`、`ui/nav/**`、`ui/App.kt`、`OcActivity.kt`。
`ComposerDelivery.kt` **零改动**。
