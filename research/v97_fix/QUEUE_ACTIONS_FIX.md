# 队列条 `×`（取消）与 `Steer` 整组消失 — 定性与修复

- 执行：缺陷猎人 + 修复工程师（mcode），队列条 `×` / `Steer` 条目
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 缺陷来源：`research/v97_verify/5556_v98/REPORT_SWEEPB2.md` 条目 1
- 历史对照：`research/v97_verify/5562_sweepB/REPORT_v97b.md` §3.1 C7
- 唯一标尺：`_upstream/`（opencode TUI + web app）

---

## 0. 结论一句话

**是条件渲染，不是回归。** 队列条面板 `ComposerQueuePanel` 在源码里完整实现了上游的
`Steer`/`Send` 按钮与 `×` 取消按钮，且**没有任何回归把它摘掉**；那一轮真机上
`×` 与 `Steer` 之所以不存在，是因为**当时根本没有一条 `delivery=queue` 的队列项**——
测试员连发 4 条 follow-up 时用的是 **Enter 默认路径**，`followUpBehavior` 默认值是
`steer`，所以 4 条全部以 `delivery=steer` 准入，队列模型始终为空，
`ComposerQueuePanel` 从未被组合。报告里看到的 `QUEUE-B-2048` chip 是**时间线里的
用户消息气泡**，不是队列条。

修复面因此是「让动作集合变得可断言、且明确禁止再引入 hover 隐藏」，而不是补渲染
分支——补渲染分支等于凭空造一个上游不存在的功能。

---

## 1. 上游口径（判定的唯一标尺）

### 1.1 队列项 → 哪些控件、什么顺序

`_upstream/packages/app/src/session/composer/queue-panel.tsx:150-191`
（`data-slot="session-queue-actions"` 容器）：

```
150  <div
151    data-slot="session-queue-actions"
152    class="flex shrink-0 items-center gap-1.5"
153    classList={{
154      "opacity-0 focus-within:opacity-100 group-hover/queue-row:opacity-100 [@media(hover:none)]:opacity-100":
155        !active() && !editing(),
156      "pointer-events-none": props.queue.busy(),
157    }}
158  >
159    <Show when={!editing()}>
160      <Tooltip … value={language.t("session.queue.steerTooltip")}>
165        <Button
166          data-action="session-queue-steer"
170          icon="arrow-up"
173          onClick={() => void props.queue.steer(props.id)}
174        >
175          {props.queue.working() ? language.t("session.queue.steer") : language.t("session.queue.send")}
176        </Button>
178    </Show>
179    <Tooltip placement="top" value={language.t("session.queue.remove")}>
180      <IconButton
181        data-action="session-queue-remove"
185        icon={<Icon name="outline-xmark" />}
187        aria-label={language.t("session.queue.remove")}
188        onClick={() => void props.queue.remove(props.id)}
189      />
190    </Tooltip>
191  </div>
```

要点：

| 项 | 上游口径 |
|---|---|
| 动作集合 | `steer`（按钮）+ `remove`（`×` 图标按钮），**永远同时存在** |
| 顺序 | JSX 里 **steer 在前、remove 在后**；容器是单行 flex，只能是这个左右次序 |
| 唯一会减少动作的情况 | `<Show when={!editing()}>`（L159）**只包住 steer** → 编辑中的行只剩 `×` |
| 可见性 | L154 的 hover 透明度门**以 `[@media(hover:none)]:opacity-100` 收尾** → 触屏上上游自己就是常亮；只有 `busy()`（L156）让它失效 |
| parked 首行 | L92 `const active = () => !props.queue.working() && props.index === 0` —— 停机时第 0 行**无需 hover** 就亮 |

### 1.2 `×` 与 `Steer` 的语义（各一句）

- **`×` = 取消这条队列项**：`onClick={() => void props.queue.remove(props.id)}`（L188），
  实现是 `_upstream/packages/app/src/session/composer/queue.ts:138-141`
  `inbox.cancel({ sessionID, inboxID: id })` → `DELETE /api/session/{id}/inbox/{inboxID}`。
  若该行正被编辑，`remove` 先 `cancelEdit()` 再取消（`queue.ts:139`）。
- **`Steer` = 把这条队列项插进当前正在跑的回合**：`onClick={() => void props.queue.steer(props.id)}`（L173），
  实现是 `queue.ts:132-137` `inbox.update({ sessionID, inboxID: id, delivery: "steer" })`
  → `PATCH /api/session/{id}/inbox/{inboxID}{delivery:"steer"}`；该行随即离开队列，
  正在跑的回合接手（提示语 `session.queue.steerTooltip` = "Send without interrupting"，
  即"不打断当前回合地追加"）。
  注意上游并没有"字面意义的插入位点"，`steer` 就是把 delivery 从 `queue` 改成 `steer`。

### 1.3 文案（本壳已存在，逐字一致，无需新增）

上游 `_upstream/packages/app/src/runtime/i18n/en.ts:772-780`：

```
774  "session.queue.steer": "Steer",
775  "session.queue.send": "Send",
776  "session.queue.steerTooltip": "Send without interrupting",
777  "session.queue.remove": "Remove",
```

本壳 `app/src/main/res/values/strings_oc_i18n.xml:903-909`（生成包，**零新增字符串**）：

```xml
905  <string name="session_queue_remove">Remove</string>
906  <string name="session_queue_reorder">Reorder queued prompt</string>
907  <string name="session_queue_send">Send</string>
908  <string name="session_queue_steer">Steer</string>
909  <string name="session_queue_steerTooltip">Send without interrupting</string>
```

对照表：逐字相同，**没有需要兜底的同义串**。`StringsLocaleParityTest` 本轮 PASS（3 用例），
三语 bundle 无缺口。

### 1.4 关键：**什么条件下才有队列项**（本条决定了整个定性）

`_upstream/packages/app/src/session/composer/queue.ts:80-84`

```
80  const queued = createMemo(() =>
81    data.session.pending
82      .list(input.sessionID)
83      .filter((item): item is QueuedPrompt => item.type === "user" && item.delivery === "queue"),
84  )
```

`queue.ts:217`

```
217  delivery: () => (input.working() ? input.behavior() : "steer"),
```

即：**只有 `delivery === "queue"` 的 user inbox 项才是队列条的一行**；
而**普通 Enter 提交用的是 `followUpBehavior` 设置项**，其默认值为 `steer`。

---

## 2. 本壳定性：条件渲染（非回归）

### 2.1 因果链（每一跳都有 file:line）

1. 真机操作：turn 运行中，测试员连发 4 条 follow-up，每条都是打字 + `keyevent 66`（Enter）。
   **全程没有点过 composer 的 Steer/Queue 切换 chip。**
2. `ComposerDelivery.delivery(running = true, behavior = followUpBehavior)`
   —— `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ComposerDelivery.kt:40-41`。
   `followUpBehavior` 默认 `steer`（`Composer.kt:324`
   `followUpBehavior: String = ComposerDelivery.STEER`；
   注释 `Composer.kt:318-320` 明写"default `steer` — Wave 2.3a `SettingsStore.followUpBehavior`"）。
   ⇒ 4 条全部以 `delivery=steer` 准入。
3. `ChatViewModel.kt:3327-3332` —— **只有 `delivery == "queue"` 才建队列行**：
   ```kotlin
   if (sessionID == id && wireDelivery == "queue" && !admittedID.isNullOrBlank() && command == null && !shell) {
       queueHolder.add(ComposerQueue.Row(id = admittedID, …))
   }
   ```
   与上游 `queue.ts:83` 的 `item.delivery === "queue"` 过滤**口径一致**。
4. ⇒ `queueHolder.rows` 恒为空 ⇒ `SessionScreen.kt:1919 queuedRows = inboxRows` 恒为空。
5. `Composer.kt:1352` —— `if (queueSupported && queuedRows.isNotEmpty())` 为 false，
   **`ComposerQueuePanel` 从未被组合**，`×` / `Steer` 自然不在 a11y 树里。
6. 切换 chip 也看不见，因为它有自己的门：`ComposerDelivery.alternate(...)`
   （`ComposerDelivery.kt:54-58`）要求 `running && hasContent`，而 dump 时刻 composer
   已被清空（发送后），故 `DeliveryToggleChip`（`DeliveryToggleChip.kt:52`
   `if (alternate.isNullOrBlank()) return`）同样不渲染。

### 2.2 真机 dump 直接证据（不是推理）

`research/v97_verify/5556_v98/b2_142_chip.xml`（`b2_141_qmenu.xml` 完全一致）全量 a11y 树里
**不存在** `Queued` / `Steer` / `Remove` 任一节点；composer 区（y 208-296）只有：

```
[28,208][1338,256] click=true
 [28,222][319,242]  click=false text='Ask anything, / for commands, @ for context...'   ← 空编辑器（占位符）
[10,248][52,296]    click=true desc='Add'
[52,248][215,296]   click=true desc='Model' / 'Space Bunny Free'
[1308,248][1356,296] click=true desc='Stop'                                             ← turn 在跑
```

而测试员以为的"队列条 chip"实际坐标在 **y 142-206**（时间线右列），且带
`Revert message` / `Copy message` 动作——这是**时间线的用户消息气泡**：

```
[1316,142][1364,150] click=true desc='E2ECHK5556w-01Count slowly from 1 to 40. Output …'
[1316,150][1364,158] click=true desc='QUEUE-A-E2ECHK5556w-7391'
[1316,158][1364,206] click=true desc='QUEUE-B-E2ECHK5556w-2048'
[1242,158][1276,206] click=true desc='Revert message'
[1276,158][1316,206] click=true desc='Copy message'
```

⇒ 报告条目 1 里的 "chip / `≡` / clickable=false" 读数，是把**时间线消息气泡**当成了
**composer 队列条**。`Steer`/`×` 从未在这个 build 上被"删掉"，它们只是从未被需要。

### 2.3 逐条否掉报告的三种可能

| 报告的猜想 | 判定 | 依据 |
|---|---|---|
| ① ≥2 项时只显示最后一个 chip、控件随 hover/长按出现 | ❌ 否 | 无 hover 门：`ComposerQueuePanel.kt` 动作区无任何 `alpha`/条件；上游 L154 的 hover 门自带 `[@media(hover:none)]:opacity-100` 兜底，触屏恒亮。且 `×`/`Steer` 从未出现，不是"被 hover 藏起来"。 |
| ② 本 build 回归移除了控件 | ❌ 否 | `ComposerQueuePanel.kt:185-212`（改前）已完整渲染 `session_queue_steer` 文本按钮 + `ic_oc_close` 取消按钮（`contentDescription = session_queue_remove`），与 `queue-panel.tsx:159-190` 一一对应。 |
| ③ 队列条宽度不够被裁剪 | ❌ 否 | 行布局：左侧文本块 `Modifier.weight(1f)`、右侧动作区为**非 weighted** 子节点，Compose 先按 intrinsic 测量未加权子节点再分配剩余空间给 weight，**动作区不会被文本挤压**；文本自身 `maxLines=1 + TextOverflow.Ellipsis`。且当轮根本没有队列行，无从裁剪。 |

### 2.4 与历史记录的关系

`5562_sweepB/REPORT_v97b.md` §3.1 C7 记录过 `T[Queued]` + `Steer` + `×`。
本轮"消失"与它**不矛盾**：C7 那一轮确实把 `followUpBehavior` 切到 `queue`（或点了
`Queue` 切换 chip）才产生队列行；本轮用默认 Enter 路径，所以没有队列行。
两次读数测的**不是同一个状态**。`T[Queued]` 前缀正是本壳为满足 device v11 补的
行内 queued 标签（`ComposerQueuePanel.kt:145-160`，`ui.message.queued`），
与 C7 的观察自洽。

---

## 3. 改动（diff）

判定为条件渲染 ⇒ **不补渲染分支**（凭空造上游不存在的功能）。但缺陷暴露了一个真实
的结构问题：**「队列项渲染出哪些动作」这件事只活在 `QueueRow` composable 里，
JVM 够不着**，所以历史上任何一次误删/误加 hover 门都测不出来。本次把动作集合
下沉为纯模型并让 UI 消费它，再钉死。

### 3.1 `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ComposerQueue.kt`（+53）

新增纯模型（零 UI 依赖，JVM 可测）：

- `enum class RowAction { STEER, REMOVE }` —— 逐条注明上游 `data-action` 与行号
  （`session-queue-steer` L166 / `session-queue-remove` L181-188）。
- `enum class SteerLabel { STEER, SEND }` —— `queue-panel.tsx:175`。
- `data class RowActions(actions, steerLabel)` + `hasSteer` / `hasRemove`。
- `fun rowActions(running: Boolean, editing: Boolean): RowActions`：
  - `editing == false` → `[STEER, REMOVE]`（上游默认态）
  - `editing == true` → `[REMOVE]`（`<Show when={!editing()}>` L159 只包 steer）
  - 标签 `running ? STEER : SEND`（L175）
  - 注释里写明**为什么没有可见性开关**：上游 L154 以 `[@media(hover:none)]:opacity-100`
    收尾，触屏恒亮；把桌面 hover 门搬过来会把唯一的取消入口藏到平台没有的手势后面。

### 3.2 `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ComposerQueuePanel.kt`（+110 / −27）

`QueueRow` 的动作区由"硬编码一个 Steer 文本 + 一个 close Box"改为
`forEachIndexed { position, action -> … when (action) { … } }` 遍历
`ComposerQueue.rowActions(running, editing).actions`，并按 `position > 0` 插入 4 dp 间隔。

- **布局逐像素不变**：常态动作集合仍是 `[STEER, REMOVE]`，间隔仍是 `Spacer(8)` + `Spacer(4)`，
  与改前完全一致；仅编辑态由「steer 仍渲染」修正为**与上游一致地不渲染 steer**。
- 字符串键全部沿用既有 `session_queue_steer` / `session_queue_send` / `session_queue_remove`，
  **未新增任何用户可见字符串**。
- 未触碰 `_upstream/`、`ChatViewModel.kt`、`app/build.gradle.kts`、`ui/session/review/**`、
  `ui/home/**`、`ui/servers/**`、`ui/terminal/**`、`OcActivity.kt`、`ui/App.kt`。

### 3.3 为什么不降强度

- 既有用例**一条没删、一条没放宽**：`Wave24bComposerQueueTest`（23 例）、
  `Wave24bQueueWiringTest`（11 例）、`QueuedLabelTest`（5 例）、`ParkedSteerRowTest`（13 例）
  全部原样保留并全绿。
- 新 pin 是**加法**：把此前无法断言的事实（动作集合 + 次序 + 编辑态 + 无 hover 门）
  变成断言，方向上只会让未来更严。
- 唯一的行为变化是编辑态不再渲染 `steer` 按钮 —— 这是**向上游口径看齐**
  （`queue-panel.tsx:159`），不是放宽；并且 `remove` 仍在，所以编辑态不会变成死路。

---

## 4. 测试证据

### 4.1 新增 pin：`QueueRowActionsPinTest`（10 例，纯 JVM）

`app/src/test/java/com/xiufeigo/opencodeshell/QueueRowActionsPinTest.kt`
（数据/结构 pin：队列项 → 动作列表必须含 cancel 与 steer，且顺序与上游一致）

| 用例 | 钉住什么 |
|---|---|
| `aQueuedPromptRendersBothCancelAndSteer` | 设备缺陷本体：队列项必须可取消 + 可 steer |
| `aParkedQueuedPromptStillRendersBothControls` | 停机（parked）态同样两控件齐在，标签转 `Send` |
| `steerPrecedesRemoveInTheUpstreamOrder` | **次序** `[STEER, REMOVE]`（换位是视觉回归，集合相同也拦） |
| `theRemoveControlIsNeverTheOnlyOneItCouldHaveBeen` | `×` 永不做行的首元素 |
| `anEditingRowDropsSteerButKeepsCancel` | `<Show when={!editing()}>`：编辑态只剩 `REMOVE`，不成为死路 |
| `theSteerLabelFollowsTheTurnState` | `queue-panel.tsx:175` 的 Steer/Send 标签 |
| `theActionSetCarriesNoVisibilityFlag` | **反射断言 `RowActions` 不得长出 visible/hover/opacity 方法** —— 从 API 层面封死"触屏 hover 门"回归 |
| `everyRowOfThePanelResolvesTheSameActionShape` | 3 行队列 × 2 运行态，逐行都保留两控件（正面否掉报告猜想①） |
| `thePanelOnlyNeedsOneRowForTheControlsToExist` | 单行队列边界：1 行即两控件，无"N 项起才显示"门槛（正面否掉报告的"只有一个 chip"） |
| `panelChromeRulesAreUnchanged` | 重申既有 chrome 规则（`showCount` / `isParkedFirst`）未松动 |

### 4.2 命令与结果

环境：`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`；抢锁 `.agent-gradle-lock` → 跑完释放（未跑 assembleDebug / clean / 全量 / 装机）。

**第一轮（只跑新类，编译快检）**

```
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.QueueRowActionsPinTest" --console=plain
```

```
> Task :app:compileDebugKotlin
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 9s
52 actionable tasks: 5 executed, 47 up-to-date
```

**第二轮（新类 + 队列 / 时间线 / composer / Wave23a / StringsLocaleParity 全回归，52 个类）**

```
.\gradlew.bat :app:testDebugUnitTest --console=plain `
  --tests "com.xiufeigo.opencodeshell.QueueRowActionsPinTest" `
  --tests "com.xiufeigo.opencodeshell.Wave24bComposerQueueTest" `
  --tests "com.xiufeigo.opencodeshell.Wave24bQueueWiringTest" `
  … （Composer* 全量、Timeline* 全量、QueuedLabelTest、ParkedSteerRowTest、
      AttachmentRoundQueueTest、Wave5Composer*、Wave23aSettingsPaletteTerminalTest、
      StringsLocaleParityTest）…
```

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 6s
52 actionable tasks: 1 executed, 51 up-to-date
```

**逐类读数**（`app/build/test-results/testDebugUnitTest/TEST-*.xml` 汇总）：

```
CLASSES=52 TESTS=539 FAILURES=0 ERRORS=0 SKIPPED=0
```

其中与本条目直接相关的：

| 类 | 用例 | fail/err |
|---|---|---|
| `QueueRowActionsPinTest`（新） | 10 | 0 / 0 |
| `Wave24bComposerQueueTest` | 23 | 0 / 0 |
| `Wave24bQueueWiringTest` | 11 | 0 / 0 |
| `QueuedLabelTest` | 5 | 0 / 0 |
| `ParkedSteerRowTest` | 13 | 0 / 0 |
| `TimelineFidelityTest` | 40 | 0 / 0 |
| `TimelineRowsTest` | 23 | 0 / 0 |
| `TimelineSearchTest` | 18 | 0 / 0 |
| `TimelineGroupingTest` / `TimelineFollowTest` | 13 / 13 | 0 / 0 |
| `TimelineFoldTest` / `TimelineFingerprintTest` | 12 / 9 | 0 / 0 |
| `TimelineBodySingleLayerTest` | 3 | 0 / 0 |
| `Wave23aSettingsPaletteTerminalTest` | 25 | 0 / 0 |
| `StringsLocaleParityTest` | 3 | 0 / 0 |
| `Composer*`（30 个类合计） | 267 | 0 / 0 |
| `Wave5Composer*`（4 个类） | 42 | 0 / 0 |

**期望 0 失败 —— 达成（0 failures / 0 errors / 0 skipped）。**

---

## 5. 真机复验步骤（复现「`×` 与 `Steer` 出现」的正确姿势）

关键：**必须让提示词以 `delivery=queue` 准入**，否则面板不会出现（这正是本条目未验到的原因）。

准备：`adb reverse tcp:4095 tcp:4095`；`show_ime_with_hard_keyboard=1`；屏幕常亮
（本轮设备教训：`keyevent 224` + `input tap` 才点得亮；`svc power stayon` 在该模拟器上是空操作，
改 `settings put system screen_off_timeout 1800000`，测完还原 `2147483647`）。

1. 新建会话，发送一条**足够长**的提示（例如 `Count slowly from 1 to 40…`），让 turn 进入 `Working`。
2. 在 turn 运行中，在 composer 输入 `QUEUE-PROBE-1`。
   → 此时控制行应出现切换 chip（`ComposerDelivery.alternate` 的 `running && hasContent` 门满足），
   文案为 `Queue`（当前行为是 `steer`，alternate 给对偶）。
3. **点该 `Queue` 切换 chip**（或 `keyevent` 组合键），使其以 `delivery=queue` 提交。
   （另一条路：先在 Settings → General → Follow-up behavior 设为 `Queue`，之后普通 Enter 即入队。）
4. 再输入 `QUEUE-PROBE-2`，同样点 `Queue` 提交 ⇒ 面板应出现 **2 行**。
5. `uiautomator dump` 断言（每行都应命中）：
   - 出现 `text="Queued"`（本壳 device v11 补的行内标签）
   - 出现 `text="Steer"`（turn 在跑 ⇒ `Steer`，不是 `Send`）
   - 出现 `content-desc="Remove"`（`×` 取消按钮，28 dp 命中区）
   - 命中区 x 顺序：Steer 在 `Remove` 左侧（上游 JSX 次序）
6. 点 `×` ⇒ 该行消失，剩 1 行；`GET /api/session/{id}/inbox` 中该项消失。
7. 点 `Steer` ⇒ 该行立即离开面板；`inbox` 项 `delivery` 变 `steer`；当前回合接手。
8. 让 turn 结束（parked）⇒ 面板若仍有行，其动作标签应从 `Steer` 变为 **`Send`**。
9. 回归确认：全程不出现"只剩一个 chip、没有 `×`/`Steer`"的画面。

> 附注：`b2_140_queueview.png`（报告条目 1 引用的目标截图）在
> `research/v97_verify/5556_v98/` 目录中**并不存在**，该条目引用的关键影像证据缺失，
> 这也是它把时间线气泡误读成队列条的直接原因。

---

## 6. 未闭环

1. **未做真机复验**。本条目纪律为"不跑 gradle / 不装机"的真机测试员；以上复验步骤尚未
   在 `emulator-5556` 上实际执行，需要一台可点亮的设备补跑（第 5 节全 9 步）。
2. **§2.2 的读数来自既有 dump 的离线复核**，未重新 dump。离线复核足以证伪"面板被删"，
   但"面板在正确入队路径下确实渲染出 `Steer`+`×`"仍需第 5 节的真机读数闭环。
3. **`×` / `Steer` 的图标形态**未做像素级对齐：上游 steer 按钮带 `icon="arrow-up"`
   （`queue-panel.tsx:170`），本壳 `ComposerQueuePanel.kt` 的 Steer 是**纯文本按钮**，
   `×` 用 `ic_oc_close` 对应 `outline-xmark`。属既有已知偏差，不在本条目改动面内，未动。
4. **拖拽重排序未实现**（`session.queue.reorder` / `queue-panel.tsx:121` 的 6 点拖拽手柄）。
   本壳刻意不做拖拽手势，重排留在纯模型 `ComposerQueue.Model.reorder`（已有 `Wave24bComposerQueueTest`
   覆盖）。上游的 reorder 按钮 `aria-label` 串 `session_queue_reorder` 已在 bundle 里但当前无 UI 消费点。
5. **并行 agent 冲突面**：`ui/session/composer/**` 内的 `ComposerQueuePanel.kt` 若同期被其它
   agent 改动，需以本报告 §3.2 的"遍历 `rowActions`"形态为准做冲突消解（模型侧
   `ComposerQueue.rowActions` 为唯一真源）。
