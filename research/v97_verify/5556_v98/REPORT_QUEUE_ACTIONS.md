# 队列条 `×`（取消）与 `Steer` 真机复验 — emulator-5556 / v98

- 执行：真机复验测试员（mcode），队列条 `×` / `Steer` 修复复验
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 设备：`emulator-5556`（Medium_Desktop）
- 被验对象：`research/v97_fix/QUEUE_ACTIONS_FIX.md` §5 第 5 节 9 步
- 纪律：不跑 gradle / 不装机 / 不杀进程；只操作 5556；不碰 127.0.0.1:5555；绝不按 BACK(4)；
  弹层内禁 ESC(111)；截图只用 `screencap -p` + `adb pull`；坐标运行期从 dump 解析。

---

## 1. 环境读数

| 项 | 值 | 来源 |
|---|---|---|
| adb | `C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe` | 不在 PATH，需绝对路径 |
| 设备列表 | 5554 / **5556** / 5558 / 5562 / 5564 / 5600 | 本轮只碰 5556 |
| 物理分辨率 | 3840x2160 | `wm size` |
| **覆盖分辨率** | **1366x768** | `wm size`（Override 行） |
| 物理密度 | 320 | `wm density` |
| **覆盖密度** | **160** | `wm density`（Override 行） |
| `show_ime_with_hard_keyboard` | `1` ✅ | `settings get secure` — 硬键盘态不弹 IME，无需绕过 |
| 包名 | `com.xiufeigo.opencodeshell` | `pm list packages` |
| `adb reverse` | `tcp:4095→4095`、`tcp:2222→22` 已就绪 | `reverse --list` |
| 初始焦点 | `org.chromium.home.pc`（launcher） | `dumpsys window mCurrentFocus` |

> **覆盖分辨率/密度是本轮之前就存在的 Override，不是本轮施加。** 收尾按原值 1366x768@160 还原，
> 不执行 `wm size reset`（那会掉回 3840x2160@320，反而破坏原状态）。

---

## 2. 让队列真的非空

按 `QUEUE_ACTIONS_FIX.md` §5 的 9 步姿势，未改任何设置项，全走「Queue 切换 chip」这条路。

| 步 | 动作 | 证据 | 观察 |
|---|---|---|---|
| 1 | 新建自建会话（`Local`） | `qa_001_newsession.xml` → `qa_002_created.xml`，标题区 `Build anything` 空态 | 会话建立 |
| 2 | 发**长回合** `E2ECHK5556q-01_Count_slowly_from_1_to_40_…` | `qa_003_typed.xml` 节点 27 文本入 composer，**Send 由 `en=false` → `en=true`** | 提交可用 |
| 3 | 发送后确认 turn 在跑 | `qa_004_working.xml` 节点 39 `[1308,248][1356,296] DESC="Stop"`（取代 Send） | ✅ Working |
| 4 | turn 运行中输入第 2 条 `E2ECHK5556q-02_QUEUEPROBE1` | `qa_005_chip.xml` 节点 39 `TEXT="Queue"` @ ctr=1290,272 | ✅ **切换 chip 出现**（`ComposerDelivery.alternate` 的 `running && hasContent` 门满足） |
| 5 | **点该 `Queue` chip** | `qa_006_chiptoggled.xml` | ✅ **队列条组合出来了** |

> 这正是 `QUEUE_ACTIONS_FIX.md` §2.1 判定的那条因果链：默认 `followUpBehavior=steer` ⇒ 队列恒空 ⇒
> 面板从未组合。本轮**没有**去改 `Follow-up behavior` 设置项，仅点 chip 把 delivery 切到 `queue`，
> 面板立刻出现 —— **对该报告定性的直接正面验证**。

**队列条真的非空：1 行。** 面板实际节点（`qa_006_chiptoggled.xml`）：

| 节点 | bounds | ctr | clickable | 文案 |
|---|---|---|---|---|
| 32 | `[22,180][1257,228]` | 640,204 | true | （整行容器） |
| 33 | `[22,171][63,185]` | 42,178 | false | `text="Queued"` ← 本壳 device v11 行内 queued 标签 |
| 34 | `[71,196][286,212]` | 178,204 | false | `text="E2ECHK5556q-02_QUEUEPROBE1"` ← 队列项正文 |
| **36** | **`[1263,181][1302,224]`** | **1282,202** | **true** | **`text="Steer"`** ← §1 主判据之一 |
| **37** | **`[1302,180][1350,228]`** | **1326,204** | **true** | **`desc="Remove"`** ← §1 主判据之二（`×` 取消） |

同时刻 turn 仍在跑：节点 10 `DESC="Working"`、节点 46 `DESC="Stop"`。
⇒ 标签是 `Steer` 而**不是** `Send`，符合 `queue-panel.tsx:175` 的 `working() ? steer : send`
与本壳 `ComposerQueue.rowActions`（`running ? STEER : SEND`）**同款**取值。

---

## 3. 主判据：`×` 与 `Steer` 是否可见

### 3.1 判定：**PASS（两者均可见）**

- **`Steer`**：`bounds=[1263,181][1302,224]`，`ctr=(1282,202)`，`clickable=true`，文案 `text="Steer"`。
- **`×`（取消）**：`bounds=[1302,180][1350,228]`，`ctr=(1326,204)`，`clickable=true`，
  无可见文字（图标按钮），a11y 名为 **`content-desc="Remove"`** —— 逐字对应上游
  `aria-label={language.t("session.queue.remove")}`（`queue-panel.tsx:187`），文案零差异。

**次序校验（上游 JSX 次序 steer 在前、remove 在后）**：

```
Steer  x: 1263 → 1302
Remove x: 1302 → 1350
      ▲ Steer 完全位于 Remove 左侧，且两控件 x 区间首尾相接、互不重叠
```

⇒ **PASS**，与 `_upstream/packages/app/src/session/composer/queue-panel.tsx:159-190` 的
「steer 前 / remove 后、单行 flex」结构**逐项吻合**。

### 3.2 报告设想的 FAIL 前提均未成立

- **不是 hover 隐藏**：触屏上 `[@media(hover:none)]:opacity-100`，本壳无 `alpha` 门；
  两控件在**无任何悬停/长按**的 `uiautomator dump` 里直接以 `clickable=true` 呈现。
- **不是「≥2 项才显示」门槛**：本轮**仅 1 行**队列，两个控件**已经同时齐在**
  （对应 `QueueRowActionsPinTest.thePanelOnlyNeedsOneRowForTheControlsToExist`）。
- **不是无子节点的 `clickable=false` 空容器**：节点 32 是整行容器且 `clickable=true`，
  其下有 `Queued` 标签、正文、Steer、Remove 四个实子节点。
- **不是被裁剪**：动作区 1263→1350 完整落在屏幕 1366 宽内，48 px 命中区未被压缩。

### 3.3 影像

- 队列条 + 两控件：`qa_006_chiptoggled.png`
- 切换 chip 出现瞬间：`qa_005_chip.png`
- 基线（队列空、面板不存在）：`qa_000_start.png`

---

## 4. 交互语义判定

### 4.1 点 `Steer`（`ctr=1282,202`）—— **PASS**

**前后对比**

| 读数 | 前（`qa_006_chiptoggled.xml`） | 后（`qa_007_aftersteer.xml`） |
|---|---|---|
| 队列面板 | 存在，1 行 | **整体消失**（`Steer`/`Remove`/`Queued` 节点全无） |
| 节点总数 | 47 | 38（差 9 = 面板 5 节点 + 伴随 chrome 变化） |
| turn 状态 | 节点 10 `DESC="Working"`、节点 46 `DESC="Stop"` | **节点 10 `DESC="Working"`、节点 37 `DESC="Stop"` —— 仍在跑** |
| 会话标题 | `Counting 1 to 40 with detailed explanations` | 同上，未变 |
| `E2ECHK5556q-02_QUEUEPROBE1` 出现在 dump 中 | **True** | **False** |

**判定：PASS。** 三条独立读数同向：

1. **该条目离开队列** —— 队列面板从树里整体消失，且该条目文本在整棵树中再也搜不到。
2. **进入当前回合** —— turn 仍为 `Working`/`Stop`，**没有被中断、也没有新开一回合**，
   会话标题未变。这正是 `queue-panel.tsx:172-176` + `queue.ts:132-137` 的语义：
   `inbox.update({ delivery: "steer" })` = "Send without interrupting"，把该条**追加**给
   正在跑的回合，而不是取消它、也不是插队成新回合。
3. 对照 §5 第 7 步「点 `Steer` ⇒ 该行立即离开面板；`inbox` 项 `delivery` 变 `steer`；当前回合接手」
   —— 前两条可由 dump 直接断言，第三条（server 端 `delivery` 字段）本轮**未能经 API 直读**（见 §6.1）。

> 注：与 `×`（取消）的关键差异已在此显现 —— `Steer` **保留了 turn 的 Running 状态**，
> 回合继续跑完；取消则会让队列项直接消失且无任何回合接手。

### 4.2 点 `×`（取消，`ctr=1326,178`）—— **PASS**

Steer 之后队列已空，故按 §5 第 4 步重新让队列非空一次（`_fill.ps1` 全程运行期从 dump 解析坐标，
不写死坐标）：

- 发长回合 `E2ECHK5556q-06_LONGTURN_…` → turn 进入 `Working`
- 运行期输入 `E2ECHK5556q-07_CANCELME_QUEUEPROBE`，**运行期解析**到 `Queue` chip
  `bounds=[1271,265][1308,280] ctr=(1290,272)` 并点它
- ⇒ 队列条再次出现（`qa_012_queuecheck.xml`）：

| 节点 | bounds | ctr | 文案 |
|---|---|---|---|
| 30 | `[22,171][63,185]` | 42,178 | `text="Queued"` |
| 31 | `[71,170][961,186]` | 516,178 | `text="E2ECHK5556q-06_LONGTURN_…"`（正文折行，故行更高） |
| 33 | `[1263,181][1302,224]` | **1282,202** | `text="Steer"` |
| 34 | `[1302,154][1350,202]` | **1326,178** | `desc="Remove"` ← 本次点击目标 |

**同一行上 Steer/Remove 仍同时存在**，与 §3 完全一致（x 区间 1263→1302→1350 首尾相接）。
正文折行导致该行 y 从 `[180,228]` 变为 `[154,202]`，但动作区**宽度不变**（`1263→1302` / `1302→1350`）
⇒ 再次否掉「宽度不够被裁剪」。

**前后对比**

| 读数 | 前（`qa_012_queuecheck.xml`） | 后（`qa_013_afterremove.xml`） |
|---|---|---|
| `E2ECHK5556q-06_LONGTURN` 是否出现在整棵 dump 中 | **True** | **False** |
| `Queued` 标签是否存在 | **True** | **False** |
| 队列面板（`Queued`/`Steer`/`Remove`） | 存在，1 行 | **整体消失** |
| 该文本是否出现在右侧时间线（右列 `[1316,*][1364,*]`） | 不在 | **不在** |
| turn 状态 | 节点 41 `DESC="Stop"`（Working） | composer 回 `Ask anything…` 占位态、`Send` `en=false`（turn 已自然结束） |

**判定：PASS。** 关键在于「**消失**」与「**被发送**」被区分开了：

- 右侧时间线（右列 `x∈[1316,1364]`）在 `qa_013_afterremove.xml` 节点 37-41 中依次列出
  `E2ECHK5556q-01…` / `-02_QUEUEPROBE1` / `-03_QUEUEPROBE2_REMOVE_TEST` / `-04_…CANCELME` /
  `-07_CANCELME_QUEUEPROBE`，
  **`E2ECHK5556q-06_LONGTURN` 不在其中**，且整份 dump 全文搜索也为 `False`。
- ⇒ 该队列项是**被取消**的（`inbox.cancel` → `DELETE /api/session/{id}/inbox/{inboxID}`，
  `queue.ts:138-141`），**没有**被投递为消息。它既不在队列里，也不在时间线里 —— 这就是「取消」的完整定义。
- 与 `Steer` 的对照已完全成立：`Steer` 之后该条**出现在时间线**（`qa_008_chip2.xml` 节点 29
  `[1316,156][1364,204] DESC="E2ECHK5556q-02_QUEUEPROBE1"`）**且 turn 未被打断**；
  `×` 之后该条**哪里都不出现**。两者行为可区分，语义各自正确。

> 时间线里出现的 `-07_CANCELME_QUEUEPROBE` 来自 `_fill.ps1` 末尾那次普通 **Send**
> （它自始至终没进过队列，是作为 steer 消息正常发送的），与本次取消无关。

---

## 5. 既有项回归（不破坏）

| 检查项 | 结果 | 证据 |
|---|---|---|
| 队列条本身仍显示 | ✅ | `qa_006_chiptoggled.xml`、`qa_012_queuecheck.xml` 两次独立复现，面板与两控件均在 |
| 时间线渲染正常 | ✅ | `qa_013_afterremove.xml` 右列 37-41 连续列出 5 条用户消息，节点 42 出现 `Jump to latest`（正常滚动态）；`qa_000_start.png` 基线亦正常 |
| 发一条普通消息仍正常 | ✅ | `qa_009_flushed.xml`：点 Send 发 `E2ECHK5556q-03_…` 后 turn 正常进入 `Working`（节点 10）、composer 清空、按钮切 `Stop` |
| 面板出现/消失不残留脏节点 | ✅ | 取消后面板整体移除，节点总数 42 → 53 的增量全部来自时间线正常增长，无孤儿容器 |
| 无 `clickable=false` 的空壳容器 | ✅ | 面板行容器（节点 29 `[22,154][1257,202] click=true`）下挂 `Queued`/正文/`Steer`/`Remove` 四个实子节点 |

**全程未出现 `QUEUE_ACTIONS_FIX.md` §5 第 9 步所禁止的画面（"只剩一个 chip、没有 `×`/`Steer`"）。**

---

## 6. 收尾（**全部为收尾时实测读数，非预期值**）

| 项 | 状态 | 收尾实测 |
|---|---|---|
| `Follow-up behavior` 设置项 | ✅ **全程未改动** —— 本轮只走 `Queue` 切换 chip，从未打开设置页，故无需还原 | 未进入 Settings |
| `wm size` / `wm density` | ✅ **全程未改动** —— 1366x768@160 是本轮之前就存在的 Override，故**不执行** `reset`（`reset` 会掉回 3840x2160@320，反而破坏原状态） | `Physical 3840x2160` / `Override 1366x768`；`Physical 320` / `Override 160` |
| `show_ime_with_hard_keyboard` | ✅ 保持本轮原值 | `1` |
| `screen_off_timeout` | ✅ 本轮设为 `1800000`，**已还原** | `2147483647` |
| `/sdcard` 残留 | ✅ 本轮所有 `uiautomator dump` / `screencap` 产物用完即 `adb shell rm` | `ls /sdcard \| grep -E '^(qa_\|tmp_\|s\.xml)'` → **空** |
| 自建会话 | ⚠️ **tab 已关闭**（只点了第 2 个 tab 自己的 `Close tab` @466,54，**未碰**第 1 个既有 tab） | tab 栏已回到基线，仅剩既有 `Slow counting from 1 to 40` |

**关于自建会话的未完全清理（如实记录）**：
关闭 tab 后，在 `Open sessions` 抽屉里该会话**仍作为 server 端记录存在**，被自动命名为
`Counting 1 to 40 with detailed explanations`（首条消息 `E2ECHK5556q-01_…` 的自动摘要标题，
`qa_sesslist.xml` 节点 19，2 minutes ago）。其内部消息全部带 `E2ECHK5556q` 前缀，确属自建。
但**删除它需要执行 `Delete` 动作，而本轮纪律明令「绝不点 Delete 文案」**，
因此本轮**只做到关闭 tab，未做 server 端记录删除**。此项按纪律让位，留给持有删除授权的轮次。

**未使用任何禁用手段**（逐条自查）：
未跑 gradle ✅、未装机 ✅、未杀进程 ✅、只操作 `emulator-5556` ✅、未碰 `127.0.0.1:5555` ✅、
未按 BACK(4)（收尾关抽屉用的是 **tap scrim / tap 汉堡**，非 `keyevent 4`）✅、
未按 ESC(111) ✅、截图只用 `screencap -p` + `adb pull`（**零次** `exec-out`）✅、
未用 `%s` 代空格（改用下划线构词）✅、未点 `Delete` 文案 ✅、
坐标全部运行期从 dump 解析（`_fill.ps1` 的 `FindCtr`，含修掉一次 PowerShell 字符串拼接 bug）✅、
dump 一律 `--compressed` ✅、边做边写盘 ✅。


---

## 7. 未闭环

1. **server 端 `delivery` 字段未能直读**。`GET http://127.0.0.1:4095/api/session` 返回
   `401 Unauthorized`，本轮未取得可用凭据，因此
   `inbox.update{delivery:"steer"}`（Steer）与 `DELETE /api/session/{id}/inbox/{id}`（`×`）
   **只在 UI 可观测层闭环，未在 server API 层闭环**。这与被验报告 §6.2 是同一条缺口。
2. **「2 行队列」未复现**。本轮两次都只做到 **1 行**。原因是被验报告 §5 第 4 步要求
   「在同一个运行中 turn 内连续入队两项」，而实测该 turn（40 计数 + 逐项长解释）常在
   第二次入队前就结束，窗口不稳定。**1 行即两控件齐在**已足以判定主判据 PASS
   （对应 `thePanelOnlyNeedsOneRowForTheControlsToExist`），但 2 行布局未验。
3. **parked 态标签 `Steer` → `Send` 的翻转未取到读数**（§5 第 8 步）。需要「turn 已结束、
   队列仍有残留行」的稳态；本轮两次 turn 结束时队列都已为空。
4. **编辑态只剩 `×`（不渲染 `Steer`）未验**。需要能进入某队列行的行内编辑态，
   本轮未找到该入口的真机路径。
5. **图标形态偏差沿用被验报告 §6.3**：上游 steer 按钮带 `icon="arrow-up"`，本壳为纯文本按钮；
   `×` 用 `ic_oc_close`。属既有已知偏差，不在本次复验判定面内。

---

## 8. 结论

### 状态：**PASS**（主判据与两条交互语义全部通过）

| # | 判据 | 结论 | 关键读数 |
|---|---|---|---|
| 1 | 队列真的非空 | ✅ PASS | 1 行；`Queued` @`[22,171][63,185]` + 正文 + 两控件 |
| 2 | `×` 与 `Steer` **同时可见** | ✅ **PASS** | `Steer` `[1263,181][1302,224]`；`Remove` `[1302,154][1350,202]` |
| 3a | `Steer` 语义（离开队列 + 进当前回合，不打断） | ✅ PASS | 面板消失 + 文本全树搜不到 + turn 仍 `Working` + 该条出现在时间线 |
| 3b | `×` 语义（从队列消失 **且未发送**） | ✅ PASS | `LONGTURN` 全树 `True→False`，`Queued` `True→False`，时间线**无**该条 |
| 4 | 既有项不破坏 | ✅ PASS | 面板 2 次独立复现；时间线连续正常；普通 Send 正常 |
| 5 | 收尾 | ✅ PASS（1 项按纪律让位） | 唯一例外：server 端自建会话记录需 `Delete` 才能删，纪律禁止 |

### 对被验报告核心定性的判决

`QUEUE_ACTIONS_FIX.md` §0 的结论「**是条件渲染，不是回归**」—— **本轮以真机读数正面确认，成立。**

- 复现了它的因果链：turn 运行中点 `Queue` 切换 chip 使 delivery 切成 `queue` 后，
  **`ComposerQueuePanel` 立刻组合出来**，`×` 与 `Steer` 同时在 a11y 树中以 `clickable=true` 呈现。
- 复现了它的次序口径：**steer 在左、remove 在右**，与上游 `queue-panel.tsx:159-190` 的 JSX 次序一致。
- 复现了它的标签口径：turn 在跑 ⇒ 标签是 **`Steer`** 而非 `Send`（`queue-panel.tsx:175`）。
- **未发现任何 hover 隐藏、≥2 项门槛、宽度裁剪、空壳容器**等报告曾设想的失效形态。
- 本报告 §7.1-7.4 记录了被验报告 §6 未闭环项里，本轮**仍未闭环**的部分（server 端 `delivery`
  直读、2 行队列、parked 态 `Send` 标签、编辑态只剩 `×`），这些不影响主判据 PASS。

> 换言之：上一轮「`×`/`Steer` 消失」是**读数姿势问题**（用默认 Enter 走 `steer` 路径导致队列恒空），
> 而非设备缺陷；按正确姿势入队后，两控件稳定、可见、语义正确。

---

## 附录：本轮证据文件清单

| 文件 | 内容 |
|---|---|
| `qa_000_start.xml/.png` | 基线：队列空，面板不存在 |
| `qa_001_newsession.*` / `qa_002_created.*` | 新建自建会话 |
| `qa_003_typed.*` / `qa_004_working.*` | 长回合入队提交、turn 进入 Working |
| `qa_005_chip.*` | `Queue` 切换 chip 出现的瞬间 |
| **`qa_006_chiptoggled.*`** | **★ 主判据：队列条 + `Steer` + `×` 同屏** |
| `qa_007_aftersteer.*` | 点 `Steer` 之后（面板消失、turn 仍在跑） |
| `qa_008_chip2.xml` / `qa_009_flushed.xml` | 队列空态；普通消息回归 |
| `qa_010_chip3.*` | 第二次让队列非空（turn 已 parked 的读数） |
| `qa_011_queuepanel.png` | 第二次入队后的截图。**同批 `.xml` dump 失败**：uiautomator 与动画竞争，返回 0 字节；该 0 字节文件已删除，不作为证据。队列条读数一律以 `qa_012` 为准（紧接其后的重 dump，14342 字节，完整） |
| **`qa_012_queuecheck.xml/.png`** | **★ 第二次复现队列条 + 两控件（正文折行，行更高但动作区宽度不变）** |
| `qa_013_afterremove.*` | 点 `×` 之后（面板消失、该条全树搜不到、时间线亦无） |
| `qa_sesslist.xml` / `qa_final2.xml` | 收尾：会话抽屉 / 回到基线态 |
| `_shot.ps1` / `_parse.ps1` / `_fill.ps1` | 本轮方法脚本（dump+截图、dump 解析、入队驱动，坐标全部运行期解析） |

