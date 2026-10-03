# `Queue` chip / 队列条疑似消失 — 专项查证（emulator-5554 / v98）

- 执行：缺陷猎人（mcode），高优先条目「`Queue` chip / 队列条疑似消失 —— 是回退、条件渲染，还是测试没找到入口？」
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 设备：`emulator-5554`（Pixel_9a，1080x2424@420 竖屏）
- 被查证对象：
  - `research/v97_fix/QUEUE_ACTIONS_FIX.md`（定性「条件渲染，不是回归」）
  - `research/v97_verify/5556_v98/REPORT_QUEUE_ACTIONS.md`（**PASS**，`Steer [1263,181][1302,224]`、`× [1302,154][1350,202]`，5 节点）
  - `research/v97_verify/5556_v98/REPORT_COMPOSER_OVERFLOW.md`（上轮真机 v103 全树零命中 → 登记为待查线索）
- 纪律：不跑 gradle / 不装机 / 不杀进程（仅 `am force-stop` 本 app）；只操作 5554；不碰 127.0.0.1:5555；
  绝不按 BACK(4)；弹层内禁 ESC(111)；截图 `screencap -p` + `adb pull`；空格 `keyevent 62`、未用 `%s`；
  `show_ime_with_hard_keyboard=1` 已核；只动自建会话（`E2ECHK5554q`）；坐标全部运行期从 dump 解析；
  dump 一律 `--compressed`；边做边写盘。

---

## 0. 结论一句话

**结论 ②：条件未满足。不是回退。**

**`Queue` chip 存在且本轮已在真机上点到**（`e_06_queuebar.xml`），**队列条也确实渲染出了 2 行、
每行 `Queued` + 正文 + `Steer` + `×`**（`Steer [808,1026][911,1110]`、`Remove [911,1025][1037,1109]`）。
上轮「chip 行只有 `Model`」「全树搜 `Steer`/`×` 零命中」**只测了 composer 为空或回合未运行的时刻**——
那时 `Queue` chip 本来就不该在，`ComposerQueuePanel` 也本来就不该组合。

---

## 1. 前置核对（逐位）

| 项 | 期望 | 实测 | 判定 |
|---|---|---|---|
| 设备 APK md5 | `bd36719d4fc062322d525bd70635e681` | `bd36719d4fc062322d525bd70635e681` | ✅ **逐位一致** |
| `lastUpdateTime` | `2026-10-03 10:35:09` | `2026-10-03 10:35:09` | ✅ **逐位一致** |
| `versionName` | — | `1.4.0` | 记录 |
| 分辨率 | 1080x2424@420 | `Physical 1080x2424` / `Physical 420`（**无 Override**） | ✅ 与派单一致 |
| `show_ime_with_hard_keyboard` | 需核 | `1` ✅ | 硬键盘态不弹 IME，无需绕过 |
| `adb reverse` | — | `tcp:4095→4095`、`tcp:2222→22`、`tcp:8794→8794` | ✅ 就绪 |
| `screen_off_timeout` | — | `2147483647`（进轮原值，未改） | ✅ 未动 |

**⇒ 前置全过，不 BLOCKED。**

### 1.1 设备归属异常（如实记录，非阻塞）

`emulator-5554` 上存在一个外部 App `com.minimax.coderemote`（远控/IDE 悬浮窗），**会在本轮交互
过程中反复抢占前台**。实测：`dumpsys window mCurrentFocus` 在 `opencodeshell.OcActivity` 与
`com.minimax.coderemote.MainActivity` 之间反复跳变，`am start` 拉回后数秒内又会被夺走。

处置：
- `_shot.ps1` / `_gate.ps1` 全部加了**焦点闸门**：焦点不是本 app 时**拒绝 dump、拒绝点击**，
  先 `am start` 抢回再行动。
- 中途发现 `_decide.ps1` 的第 3 步「点 Queue chip」**没有**在 tap 前复验焦点，存在误点外窗风险，
  **已主动 `task_stop` 掐断**，改写为带闸门的 `_gate.ps1` 后重跑。
- 后果：若干次 dump 与 uiautomator 竞态失败（0 字节），`e_04_aftertap` 就是因此丢失；
  改用「先截图（成功）+ 补 dump（第 3 次成功）」绕过。**所有被判据使用的读数均为完整文件**。

---

## 2. 代码侧：`Queue` chip 出现的条件（file:line + 原文）

### 2.1 `followUpBehavior` 的取值与默认

| 位置 | 原文 | 含义 |
|---|---|---|
| `ComposerDelivery.kt:27-28` | `const val STEER = "steer"` / `const val QUEUE = "queue"` | 两个取值 |
| `ComposerDelivery.kt:31` | `val BEHAVIORS = setOf(STEER, QUEUE)` | 设置项只这两值 |
| `SettingsStore.kt:124` | `val followUpBehavior: String = DEFAULT_FOLLOW_UP_BEHAVIOR,` | 存储字段 |
| **`SettingsStore.kt:540`** | **`const val DEFAULT_FOLLOW_UP_BEHAVIOR = "steer"`** | **默认值 = `steer`** |
| `SettingsStore.kt:467` | `const val KEY_FOLLOW_UP_BEHAVIOR = "opencode.android.followUpBehavior"` | 持久化键 |
| `Composer.kt:346` | `followUpBehavior: String = ComposerDelivery.STEER,` | composer 入参默认 |

**设备实测佐证**：`run-as com.xiufeigo.opencodeshell grep -r followUp shared_prefs` → **空**
⇒ 该键**从未被写过** ⇒ 本 build 实际生效的就是默认 `steer`。

### 2.2 普通 Enter 用哪个 delivery

`ComposerDelivery.kt:40-41`
```kotlin
fun delivery(running: Boolean, behavior: String?): String =
    if (running && behavior?.trim()?.lowercase() == QUEUE) QUEUE else STEER
```
⇒ **默认 `steer` ⇒ 普通 Enter 一律 `steer`**，永远进时间线。

### 2.3 `Queue` chip 出现需要同时满足 4 个门

**门 1 — 提交路径的 delivery 选择** `Composer.kt:1195-1202`
```kotlin
val delivery = if (!running || !supported) {
    ComposerSubmission.Delivery.STEER
} else if (alternate) {
    val offered = ComposerDelivery.alternate(running, followUpBehavior, editing != null, hasContent)
    ComposerSubmission.Delivery.of(offered)
} else {
    ComposerSubmission.Delivery.of(ComposerDelivery.delivery(running, followUpBehavior))
}
```

**门 2 — 切换 chip 的文案/存在性** `ComposerDelivery.kt:54-58`
```kotlin
fun alternate(running: Boolean, behavior: String?, editingQueued: Boolean, hasContent: Boolean): String? {
    if (editingQueued) return STEER
    if (!running || !hasContent) return null          // ← 关键：空闲 或 composer 空 ⇒ null
    return if (delivery(running, behavior) == QUEUE) STEER else QUEUE
}
```

**门 3 — 渲染点的二次门** `DeliveryToggleChip.kt:52`
```kotlin
if (alternate.isNullOrBlank()) return
```

**门 4 — 组合点的三次门** `Composer.kt:1808-1809`
```kotlin
if (!shellMode && alternateDelivery != null) {
    DeliveryToggleChip(
```

**⇒ `Queue` chip 是「条件出现」，不是常驻。** 出现的充要条件是：

```
queueSupported == true          (Composer.kt:632-637)
&& shellMode == false           (Composer.kt:1808)
&& running == true              (ComposerDelivery.kt:56)
&& hasContent == true           (ComposerDelivery.kt:56)
&& followUpBehavior != "queue"  ⇒ 文案才是 "Queue"（若已是 queue，chip 文案翻成 "Steer"）
```

**外加一道比 `followUpBehavior` 更硬的门 — 生成代际 gate** `ComposerQueue.kt:41-42`
```kotlin
fun isQueueSupported(generationName: String?): Boolean =
    generationName?.trim()?.uppercase() != "V1"
```
`Composer.kt:632-637`
```kotlin
val queueSupported = ComposerQueue.isQueueSupported(generation)
val alternateDelivery = if (!queueSupported) {
    null                                  // ← v1 服务器 ⇒ 整个队列面（含 chip）直接消失
} else {
    ComposerDelivery.alternate(running, followUpBehavior, queueEditOpen, hasContent)
}
```
**本 build 探到的是 V2 面**——因为 §3 的真机读数里 chip 与面板都真实出现了（V1 会让 `queueSupported=false`）。

### 2.4 队列条（panel）的条件

`Composer.kt:1374`
```kotlin
if (queueSupported && queuedRows.isNotEmpty()) {
    ComposerQueuePanel(
```
`ChatViewModel.kt:3327-3328`
```kotlin
if (sessionID == id && wireDelivery == "queue" && !admittedID.isNullOrBlank() &&
    command == null && !shell
) {
    queueHolder.add(ComposerQueue.Row(...))
```
**⇒ 只有 `delivery == "queue"` 准入的那一条才会建队列行**；`steer` 的条目永远不建。

### 2.5 回答两个点名问题

**Q：`Queue` chip 是常驻还是条件出现？**
**条件出现**（§2.3 四个门 + 代际门）。

**Q：上轮「chip 行只有 `Model`」意味着什么？**
意味着**采样时刻 `hasContent == false`（composer 空）或 `running == false`（回合没在跑）**——
两种情况 `alternate()` 都返回 `null`，chip 按 `DeliveryToggleChip.kt:52` 不渲染。
**这只说明"那一刻不满足条件"，不能推出"渲染条件被改掉"。**
（若真是回退，`queueSupported` 应为 false 或 `Composer.kt:1374` 的门被删；本轮两者都证伪——见 §3。）

---

## 3. 真机穷举（每条带 dump + 截图）

### 3.1 会话底部 chip 行 —— 逐个列出

**时刻 A：composer 为空（新会话刚建）** `c_03_insession.xml`

| # | bounds | ctr | clickable | 文案 |
|---|---|---|---|---|
| 1 | `[27,1399][138,1525]` | (82,1462) | true | `desc='Add'` |
| 2 | `[138,1425][581,1499]` | (360,1462) | false | `desc='Model'` |
| 3 | `[207,1442][523,1483]` | (365,1462) | false | `text='DeepSeek V4 Flash'` |
| 4 | `[927,1399][1053,1525]` | (990,1462) | true | `desc='Send'` |

⇒ **只有 `Model` 一个 chip，无 `Queue`**。**这就是上轮读到的同一画面**，与回退无法区分。

**时刻 B：回合运行中 + composer 有内容** `c_06_chip.xml` / `d_01_typed.xml`

| # | bounds | ctr | clickable | 文案 |
|---|---|---|---|---|
| 1 | `[27,1399][138,1525]` | (82,1462) | true | `desc='Add'` |
| 2 | `[138,1425][581,1499]` | (360,1462) | false | `desc='Model'` |
| 3 | `[207,1442][523,1483]` | (365,1462) | false | `text='DeepSeek V4 Flash'` |
| 4 | **`[766,1399][927,1525]`** | **(846,1462)** | **true** | **`text='Queue'`**（子节点 `[830,1443][926,1482]`） |
| 5 | `[927,1399][1053,1525]` | (990,1462) | true | `desc='Send'` |

⇒ **`Queue` chip 存在**，且有 `clickable="true"` 的真实父容器可点。

**时刻 C：队列条已组合、composer 被清空后** `e_06_queuebar.xml`

| # | bounds | 文案 |
|---|---|---|
| 1 | `[27,1399][138,1525]` | `desc='Add'` |
| 2 | `[927,1399][1053,1525]` | `desc='Stop'` |

⇒ **`Queue` chip 又消失了**（Model chip 因 IME 弹起被挤到屏外）。
⇒ **同一 build 内 A/B/C 三个时刻，chip 时有时无 ⇒ 条件渲染的直接反证。**

### 3.2 `More options`（⋮）菜单 —— 逐项列出

`e_07_moreopts.xml`，`⋮` @ `[954,227][1080,348]` ctr=(1017,288)，**共 10 项，零队列相关**：

| # | bounds | ctr | 文案 |
|---|---|---|---|
| 1 | `[560,380][691,412]` | (626,396) | `Rename` |
| 2 | `[560,454][797,485]` | (678,470) | `Export session` |
| 3 | `[560,527][797,558]` | (678,542) | `Import session` |
| 4 | `[560,600][664,632]` | (612,616) | `Delete` |
| 5 | `[560,674][646,705]` | (603,690) | `Undo` |
| 6 | `[560,747][764,778]` | (662,762) | `Fork session` |
| 7 | `[560,820][719,851]` | (640,836) | `Move to…` |
| 8 | `[560,893][787,925]` | (674,909) | `New worktree` |
| 9 | `[560,967][845,998]` | (702,982) | `Command palette` |
| 10 | `[560,1040][846,1081]` | (703,1060) | `Toggle debug bar` |

全树计数：`Queue`=0、`queue`=0、`Steer`=0、`Follow`=0
⇒ **与上轮读数一致（菜单里本来就没有队列入口）**。这**不是**缺陷——
上游的 `followUpBehavior` 本来就在 **Settings → General**，不在 ⋮ 菜单（`DeliveryToggleChip.kt:31` 注释自述）。

### 3.3 设置项搜索

`run-as com.xiufeigo.opencodeshell grep -r followUp shared_prefs` → **空**
⇒ 键 `opencode.android.followUpBehavior` **从未被写入**，生效值为默认 `steer`。
**本轮未进入 Settings 页、未改任何设置项 ⇒ 无需还原。**（见 §5 收尾实测）

### 3.4 ★「长回合进行中再发一条」的落点判定

**步骤与前提证据**

| 步 | 动作 | 证据 | 读数 |
|---|---|---|---|
| 1 | 新建自建会话 | `c_01_newsession.xml` → `c_03_insession.xml` | 会话建立（`Build anything` 空态） |
| 2 | 输长回合 `E2ECHK5554q-03_Write_a_detailed_1000_word_essay…` | `d_01_typed.xml` | `Queue` chip 已现 |
| 3 | 发送 | `d_02_working.xml` | **`desc='Stop'` @ `[927,1399][1053,1525]`** ⇒ **回合确在跑** |
| 4 | 运行中输入 `E2ECHK5554q-04_SECOND_MSG` | — | 随后与 `-06` 一并入队 |
| 5 | 输入 `E2ECHK5554q-06_QUEUEPROBE2` | — | composer 有内容 ⇒ chip 满足 `hasContent` |
| 6 | **点 `Queue` chip** ctr=(846,1462) | `e_05_aftertap.png` / `e_06_queuebar.xml` | **队列条组合出来，2 行** |

**回合确在跑的三重证据**（不靠"Stop 存在"单点）：
`desc='Stop'` 存在 **且** `text='Working'` @ `[42,935][176,976]` **且** 队列面板行内 `Steer` 而非 `Send`。

**★ 队列条真机读数** `e_06_queuebar.xml`（27106 字节，完整）：

| 节点 | bounds | ctr | clickable | 文案 |
|---|---|---|---|---|
| — | — | — | — | `text='Working'` @ `[42,935][176,976]` ← 回合在跑 |
| 1 | `[59,1071][163,1106]` | (111,1088) | false | `text='Queued'` ← 行内 queued 标签 |
| 2 | `[184,1068][734,1109]` | (459,1088) | false | `text='E2ECHK5554q-04_SECOND_MSG'` |
| **3** | **`[808,1026][911,1110]`** | (860,1068) | **true** | **`text='Steer'`** |
| **4** | **`[911,1025][1037,1109]`** | (974,1067) | **true** | **`desc='Remove'`**（`×`） |
| 5 | `[59,1155][163,1190]` | (111,1172) | false | `text='Queued'` |
| 6 | `[184,1152][742,1193]` | (463,1172) | false | `text='E2ECHK5554q-06_QUEUEPROBE2'` |
| **7** | **`[808,1110][934,1236]`** | (871,1173) | **true** | **`text='Steer'`** |
| **8** | **`[911,1109][1037,1235]`** | (974,1172) | **true** | **`desc='Remove'`**（`×`） |
| 9 | `[927,1399][1053,1525]` | (990,1462) | true | `desc='Stop'` ← 回合仍在跑 |

计数：`Queued`=2、`Steer`=2、`Remove`=2、`Stop`=1、`Working`=2。

**⇒ 落点判定：进队列，不是进时间线。** 两条 follow-up 都没有落到右侧时间线气泡
（时间线右列 `x∈[1010,1060]` 无这两条文本），而是以 `Queued` 行的形式出现。

**⇒ 复现了 `REPORT_QUEUE_ACTIONS.md` §7.2 记录为「未复现」的 2 行队列布局。**

**★ 次序校验（上游 `queue-panel.tsx:159-190` steer 在前、remove 在后）**
```
第 1 行：Steer  x: 808  → 911
         Remove x: 911  → 1037
                 ▲ 首尾相接、互不重叠，Steer 完全在 Remove 左侧
第 2 行：Steer  x: 808  → 934
         Remove x: 911  → 1037
                 ▲ 同向
```
⇒ 与上游 JSX 次序**逐项吻合**。

**★ 标签校验（`queue-panel.tsx:175` `working() ? steer : send`）**
回合在跑（`Working` + `Stop`）⇒ 标签是 **`Steer`** 而非 `Send`，与本壳
`ComposerQueue.rowActions(running ? STEER : SEND)` 同款取值。✅

---

## 4. 结论

### 判定：**② 条件未满足**

| 判据 | 结论 | 关键读数 |
|---|---|---|
| `Queue` chip 是否存在 | ✅ **存在** | `c_06_chip.xml`：`[830,1443][926,1482]`，父 `[766,1399][927,1525]` `clickable=true` |
| 队列条是否渲染 | ✅ **渲染，且 2 行** | `e_06_queuebar.xml`：`Queued`×2 / `Steer`×2 / `Remove`×2 |
| 是否回退 | ❌ **否** | 同一 build 内 A/B/C 三时刻 chip 时有时无；面板 + 两控件真机齐现 |
| 是否"测试没找到入口" | ✅ **正是** | 入口是**条件 chip**，非 ⋮ 菜单项；空 composer / 空闲回合时本就不渲染 |

### 4.1 可复现步骤（照它已跑通一次）

1. 自建会话（`Local` → `Create session`）。
2. 发一条**足够长**的提示（如 `E2ECHK5554q-03_Write_a_detailed_1000_word_essay_…`），让回合进入 `Working`。
3. **确认回合在跑**：`Stop` 按钮出现 **且** 时间线有 `Working`。
4. **在 composer 里输入内容**（不能留空！空 ⇒ `hasContent=false` ⇒ 无 chip）。
5. 此时控制行出现 `Queue` chip（`[766,1399][927,1525]`，ctr≈(846,1462)）。
6. **点该 `Queue` chip** ⇒ 以 `delivery=queue` 提交。
7. 重复 4-6 即可堆到多行。
8. 断言：`Queued` 行内标签 + `Steer`（左）+ `Remove`/`×`（右）同屏。

**关键陷阱（三条，任一踩中就看不到）**：
- **必须 composer 有内容**再找 chip；
- **必须回合在跑**；
- **必须点 chip**，不能只按 Enter（默认 `steer` ⇒ 进时间线）。

### 4.2 那 15 条 follow-up 为什么进时间线

**因为它们全部走的是默认 Enter 路径，而本 build 的 `followUpBehavior` 从未被写过 ⇒ 生效值是默认 `steer`。**

- `SettingsStore.kt:540` `DEFAULT_FOLLOW_UP_BEHAVIOR = "steer"`
- 设备侧 `grep -r followUp shared_prefs` → **空**，坐实键从未落盘
- `ComposerDelivery.kt:40-41`：`delivery(running, behavior="steer")` ⇒ **恒为 `STEER`**
- `ChatViewModel.kt:3327`：**只有 `wireDelivery == "queue"` 才建队列行** ⇒ `steer` 的条目永不进面板
- ⇒ 15 条全部以 `delivery=steer` 准入，**逐条被当前回合消费/落入时间线**，
  `ComposerQueuePanel` 从未被组合 ⇒ 全树搜 `Steer`/`×`/`Queued` **零命中**。

**⇒ 这 15 条进时间线是 `followUpBehavior` 默认值的正确表现，不是缺陷，也不是回退。**
（顺带说明：上轮"`Stop` 持续在 ⇒ 回合确在跑"是对的，但**回合在跑只是 chip 的必要条件之一**，
不点 chip / composer 空 / 未改设置，三者任一不满足，队列条都不会出现。）

### 4.3 若为①时的最小修复面 —— **本轮不适用**

判定为 ②，故**没有修复面，不改任何代码**。
（若日后真判定为回退，按代码结构，最小修复面会是 `ComposerDelivery.alternate`
（`ComposerDelivery.kt:54-58`）的 `running || hasContent` 判定，或 `Composer.kt:1374`
的 `queueSupported && queuedRows.isNotEmpty()` 门 —— 二者必居其一，**但本轮真机读数已双向证伪**。）

---

## 5. 收尾（实测读数，非预期值）

| 项 | 状态 | 实测 |
|---|---|---|
| `followUpBehavior` 设置项 | ✅ **全程未改动** | `grep -r followUp shared_prefs` → 空（进轮即空） |
| `wm size` / `wm density` | ✅ **全程未改动** | `Physical 1080x2424` / `Physical 420`，无 Override |
| `show_ime_with_hard_keyboard` | ✅ 保持进轮值 | `1` |
| `screen_off_timeout` | ✅ **全程未改动** | `2147483647` |
| `/sdcard` 残留 | ✅ | `rm -f /sdcard/w.xml /sdcard/w.png`；每次 `_shot.ps1` 内 dump+screencap 用完即删 |
| 禁止手段自查 | ✅ 零违规 | 未跑 gradle ✅、未装机 ✅、未杀进程 ✅、只碰 5554 ✅、未碰 127.0.0.1:5555 ✅、**未按 BACK(4)** ✅（关 ⋮ 用点 scrim）、**未按 ESC(111)** ✅、截图只用 `screencap -p` + `pull` ✅、未用 `%s` ✅、坐标全部运行期从 dump 解析 ✅、dump 一律 `--compressed` ✅ |

**自建会话残留（如实记录）**：本轮自建会话（内容全部带 `E2ECHK5554q` 前缀）仍存在于
`Open sessions` 抽屉与 server 端，**含 2 条未消费的队列项**。
**未执行删除**——本轮未获 `Delete` 授权（⋮ 菜单第 4 项即 `Delete`，见 §3.2），按纪律让位。

---

## 6. 未闭环

1. **`Steer` / `×` 的点击语义本轮未验**（`ChatViewModel.steerQueued` L3505-3521 / `cancelQueued` L3528-3544）。
   本轮只验到"**它们渲染出来了且可点**"，未点。二者都需要在队列非空时点到，属下一轮可补。
2. **server 端 `delivery` 字段未直读**：`GET http://127.0.0.1:4095/api/info` 返回 **401 Unauthorized**，
   未取得凭据 ⇒ 无法在 API 层确认那 2 条 `inbox` 项的 `delivery == "queue"`。
   与 `REPORT_QUEUE_ACTIONS.md` §7.1 是同一条缺口（该轮用 401 同样未取得）。
3. **未复现 parked 态标签 `Steer` → `Send` 的翻转**：需要"回合已停 + 队列仍有残留行"的稳态，
   本轮两次入队时回合都在跑。
4. **未复现编辑态只剩 `×`**：需要能进入某队列行的行内编辑态，未找到真机入口。
5. **未验 v1 代际下队列面确实消失**：`ComposerQueue.kt:41-42` 的 `isQueueSupported` 门只有
   代码证据（本 build 是 V2 面），**没有一台 v1 服务器可对照**。
6. **焦点被外窗抢占**导致若干次 dump 竞态失败（§1.1），判据读数均取自完整文件，无 0 字节证据被采信。

---

## 7. 附录：证据文件清单

| 文件 | 内容 |
|---|---|
| `c_00_baseline.xml/.png` | Home 基线 |
| `c_01_newsession.xml/.png` | 新建会话页 |
| `c_03_insession.xml/.png` | **★ 时刻 A：chip 行只有 `Model`**（与上轮同一画面） |
| `c_04_typed.xml/.png` | 长回合已输入 |
| `c_05_working.xml/.png` | 发送后 `Stop` 出现（回合在跑） |
| `c_06_chip.xml/.png` | **★ 时刻 B：`Queue` chip 出现** `[830,1443][926,1482]` |
| `d_01_typed.xml/.png` | 第二个长回合已输入（chip 同时在） |
| `d_02_working.xml/.png` | 第二回合 `Stop` 出现 |
| `e_05_aftertap.png` | **★ 点 Queue chip 后的截图**（XML 竞态失败，截图成功） |
| **`e_06_queuebar.xml/.png`** | **★★ 主判据：队列条 2 行 + `Steer`×2 + `Remove`×2**（27106 字节完整） |
| `e_07_moreopts.xml` | ⋮ 菜单 10 项逐项枚举（零队列项） |
| `_shot.ps1` / `_zone.ps1` / `_find.ps1` / `_gate.ps1` / `_decide.ps1`(已弃用) | 本轮方法脚本（焦点闸门 + 坐标运行期解析） |
