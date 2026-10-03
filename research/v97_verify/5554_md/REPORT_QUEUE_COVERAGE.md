# REPORT_QUEUE_COVERAGE — OpenCode Android Shell 真机队列域覆盖（v130）

- **设备**：emulator-5554（Medium_Desktop）
- **包**：`com.xiufeigo.opencodeshell`（Activity `.OcActivity`）
- **执行日期**：2026-10-04
- **仓库根**：`C:\sensorsdata\main\program\opencode-android-shell`
- **证据目录**：`research/v97_verify/5554_md/evidence/`

---

## 0. 构建 / 后端 / 归属核对

| 项 | 期望 | 实测 | 判定 |
|---|---|---|---|
| 设备在线 | `emulator-5554` | `emulator-5554  device` | ✅ |
| 屏幕 Override | `1366x768` | `Override size: 1366x768` | ✅ |
| 密度 Override | `160` | `Override density: 160` | ✅ |
| 设备端 APK md5 | `940476cd6cca62f541449a1f6aea994e` | `940476cd6cca62f541449a1f6aea994e` | ✅ 逐位一致 |
| 设备端 `lastUpdateTime` | `2026-10-03 20:12:34` | `2026-10-03 20:12:34` | ✅ 逐位一致 |
| 版本 | — | `versionName=1.4.0` / `versionCode=26` | 记录 |
| 后端 v2 端口 | `4095` | `tcp6 ... [::]:4095 ... LISTEN` | ✅ |
| 反向端口 | `reverse tcp:4095` | `host-19 tcp:4095 tcp:4095` | ✅ |
| 认证态 | 已认证或可重填 | **启动即已认证**（composer + `Send` 同屏，无登录页） | ✅ 未动凭据 |

APK md5 取自设备端：
`/data/app/~~5U1c8fE76RIICDlpCMOHgw==/com.xiufeigo.opencodeshell-ucJTZdiGiY1RmpRr05U8zA==/base.apk`

**结论：构建与后端基线全部吻合，未 BLOCKED，全程未 gradle / 未装机 / 未杀进程。**

### 队列条复现姿势（沿用 v128，本轮复验通过）
长回合「count from 1 to N」→ 回合运行中（composer 显 `Stop`）→ IME 打开且 composer 有内容 → chip 行出现 `Queue` → 点它 ⇒ 队列条出现。删除按钮 a11y 标签确认为 **`Remove`**（非 `×`）。

---

## 1. 格一：队列行编辑态 —— **部分 PASS（进入编辑态 PASS；"提交后该行文本变化" 不适用）**

### 1.1 前置行文本（`evidence/q_multi.xml`）
| 位 | 正文 bounds | 动作 |
|---|---|---|
| row1 | `[71,192][323,208]` `E2ECHK5554u1s queued followup alpha` | `Steer` `[1263,177][1302,209]`、**`Remove`** `[1302,176][1350,208]` |
| row2 | `[71,224][324,240]` `E2ECHK5554u1s queued followup bravo` | `Steer` `[1263,209][1302,253]`、**`Remove`** `[1302,208][1350,256]` |

正文 `TextView` 自身 `clickable=false`，但其可点击祖先是整行 `View` `[22,176][1257,208]` / `[22,208][1257,256]`。

### 1.2 进入编辑态：PASS
命令：`adb -s emulator-5554 shell input tap 197 200`（row1 正文中心）

前后对照（`q_multi.xml` → `c1_aftertap.xml`）：

| 指标 | 前 | 后 | 判定 |
|---|---|---|---|
| composer `EditText` `[28,253][1338,301]` | `text=''` `focused=true` | **`text='E2ECHK5554u1s queued followup alpha'`** | ✅ 正文回填 composer |
| row1 的 `Steer` 按钮 | 存在 `[1263,177][1302,209]` | **消失** | ✅ 动作集降为仅 `Remove` |
| row1 的 `Remove` | 存在 | 仍在 `[1302,176][1350,208]` | ✅ 符合"编辑行保留 ✕ 以便退出" |

两项标志均与模型一致：
- `ComposerQueue.rowActions(running=true, editing=true)` → `actions = [REMOVE]`（`ComposerQueue.kt:235-238`）
- `Composer.kt:966-972` `editQueued()` = "Loads a queued row into the editor for editing"；`editingId = queueEdit?.id`（`Composer.kt:1381`）

### 1.3 提交后"该行内容确实变了"：**不适用（N/A by design）**
操作：composer 追加 `" EDITED-C1"` → `input tap 1332 316`（`Send` `[1308,292][1356,340]`）

结果（`c1_postSubmit.xml` / `c1_full.xml`）：**队列条整体消失**，composer 清空，时间线转入新一轮工作（`Build ··· 4s`），无任何队列行残留。

判定依据（源码，非推测）——该设计**不存在"原地改写行文本"**：
- `ComposerQueue.kt:66-68` — `editingId` 注释即 **"The one-shot edit loan: the queued prompt loaded in the composer"**
- `ComposerQueue.kt:160` — `cancelEdit()`：**"Abandons the edit; the queued row is untouched"**
- `ComposerQueue.kt:154-158` — `confirmEdit()` 只 `editingId = null` 并返回 id，**不回写行文本**

即：编辑是"把行文本借到 composer"，提交是**按新内容重新投递**，原行不被改写。故本子断言**无法成立**，不判缺陷；按任务要求记为**未测到（不适用）**。

### 1.4 取消则不变：**部分验证 + 限制**
- 源码：`cancelEdit()` 明确 "the queued row is untouched"（`ComposerQueue.kt:160-163`）。
- 设备侧：唯一取消入口是 **Esc**（`Composer.kt:965` 注释 "Esc cancels"；`cancelQueueEdit()` 在 `Composer.kt:975-978`），**composer 无可见取消按钮**（各帧 dump 未见 Cancel 控件）。
- 本轮为恢复可测状态按源码路径按了 **1 次 Esc(111)**；该 keyevent 被 IME 先行消费（`mInputShown` 由 `true`→`false`），**未触达 `cancelQueueEdit`**。

**⚠️ 纪律例外声明**：本轮使用了 1 次 `KEYCODE_ESCAPE(111)`。当时无弹层（`dumpsys window windows` 仅 `Window #9 ... OcActivity`），源码将其定义为队列编辑的取消件；已如实记录。若认为越界，此处应记为未闭环。

### 1.5 副发现：编辑贷款（`queueEditOpen`）**卡死未复位**（非本轮三格，但影响可测性）
- 现象：编辑行并提交后，chip 持续显示 **`Steer`** 而非 `Queue`，`Queue` 入口不再出现（`evidence/state1.xml` `[1277,309][1308,324] 'Steer'`）。
- 根因（源码）：`ComposerDelivery.alternate()` 首行 **`if (editingQueued) return STEER`**（`ComposerDelivery.kt:54-55`）。
- 一次 Esc 未清（keyevent 被 IME 吃掉）。**绕过手段**：新建会话（`New session` → `Create session`）才复位。此为可测性阻塞点，建议单独立项。

**格一小结**：**进入编辑态 PASS**；**"提交后该行文本变化 / 取消则不变" 按设计不适用**；编辑贷款卡死为副发现。

---

## 2. 格二：拖拽重排 —— **不支持（设计如此，非缺陷）**

### 2.1 试了什么
前置 2 行（`evidence/strip3.xml`）：
```
row1 [71,192][240,208] 'E2ECHK5554u1s rowA first'   (center 156,200)
row2 [71,224][263,240] 'E2ECHK5554u1s rowB second'  (center 167,232)
```

| # | 手势 | 命令 | 结果 |
|---|---|---|---|
| 1 | 长按拖拽（按下即拖，1500ms） | `input draganddrop 156 200 156 252 1500` | 行序**未变** |

- 拖后（`drag1.xml`）瞬时只见 rowB（重排/重绘瞬态），但随后 `pre_stop.xml` 复核 rowA 仍在首位，**净效果为无变化**。
- 落点选在正文区（x=156），**远离 `Remove` 靶心**（`[1302,*]`），排除误触删除。
- 落点 y=252 已越过 row2 下沿（240）。

### 2.2 前后行序对照
| | 拖前 | 拖后 |
|---|---|---|
| 第 1 行 | `E2ECHK5554u1s rowA first` | `E2ECHK5554u1s rowA first` |
| 第 2 行 | `E2ECHK5554u1s rowB second` | `E2ECHK5554u1s rowB second` |

**行序无变化。**

### 2.3 判定：未测到 / **不支持**（明确不判缺陷）
源码已声明该形态**刻意不含拖拽手势**：

> `ComposerQueuePanel.kt:55-57`
> "Native shape: **no drag-reorder gesture** (touch list keeps tap actions; reorder stays a pure-model op — `ComposerQueue.Model.reorder` — for the VM to drive if ever needed), rows tap-to-edit."

`QueueRow`（`ComposerQueuePanel.kt:164-279`）全函数无 `detectDragGestures*` / `pointerInput` / `draggable`；`busy` 仅用于 `clickable(enabled = !busy)`。

**结论：拖拽重排在本形态下无实现 → 判「不支持」，不判缺陷。** 若产品需要重排，需新增手势，属需求缺口而非回归。

---

## 3. 格三：parked 首行 `Steer`→`Send` —— **FAIL（与模型相悖）**

### 3.1 前置（运行中）
`evidence/pre_stop.xml`：
```
row1 [71,192][240,208] 'E2ECHK5554u1s rowA first'  Steer[1263,177][1302,209]  Remove[1302,176][1350,208]
row2 [71,224][263,240] 'E2ECHK5554u1s rowB second' Steer[1263,209][1302,253]  Remove[1302,208][1350,256]
composer: text="Stop"   (running=true)
```

### 3.2 操作链
1. `Stop` #1 → 时间线出现 **`Interrupted`**；rowA 被消耗（转为新一轮），composer 仍 `Stop`（仍 running）→ 首行仍 `Steer`。(`parked.xml`)
2. `Stop` #2 → 首行变为 `rowC third`，标签仍 `Steer`。(`parked3.xml`)
3. 补队 rowD/rowE/rowF 后再 `Stop`，**连续轮询 12 次**。(`catch2.ps1` → `evidence/pp.xml`、`parked_idle.xml`)

### 3.3 读数原文（关键）
```
poll#3  IDLE  firstRowLabel=STEER
   ROW@71,192-241,208 'E2ECHK5554u1s rowE fifth'   ACT@1263,177-1302,209 'Steer'
   ROW@71,224-245,240 'E2ECHK5554u1s rowF sixth'   ACT@1263,209-1302,253 'Steer'
...
poll#10 IDLE  firstRowLabel=STEER
   ROW@71,198-245,214 'E2ECHK5554u1s rowF sixth'   ACT@1263,183-1311,231 'Steer'
poll#12 IDLE  firstRowLabel=STEER   (同上)
```
**12 次全部 IDLE、首行标签恒为 `Steer`，从未出现 `Send`。**

### 3.4 判定：FAIL
- 队列条在 idle 下持续可见（行未被清空），首行即 parked 首行：`isParkedFirst(running=false, index=0)` = **true**（`ComposerQueue.kt:185`）。
- 模型强制 idle 用 `SEND`：
  > `ComposerQueue.kt:237` — `steerLabel = if (running) SteerLabel.STEER else SteerLabel.SEND`
  > `ComposerQueue.kt:54` — `enum class SteerLabel { STEER, SEND }`
- 面板收到的 `running` 即会话运行态：`running = runStatus == ChatStore.RunStatus.RUNNING`（`Composer.kt:439`）→ 传入 `ComposerQueuePanel(running = running, ...)`（`Composer.kt:1377`）。
- 排除了"按钮态 ≠ runStatus"的唯一辩解：`Composer.kt:609` / `:1821` 明确「stop 需 `running && 输入为空`」。轮询期间 composer 为
  `EditText [28,244][1338,292] text=''`（**空**，`parked_idle.xml` / `pp.xml` 复核），
  故按钮显示 `Send` **只能**意味着 `running=false`。
- 结论：**`running=false` + parked 首行 ⇒ 按模型应为 `Send`，实测恒为 `Steer`** ⇒ **FAIL，判缺陷**。

### 3.5 恢复回合复核变回：**未闭环**
因 `Send` 从未出现，缺少"变过去"的基线，**"恢复回合后复核变回 `Steer`"无法执行**。反向读数仅成立：全程 running 态下首行确为 `Steer`（3.1）。

**建议回归动作**：修复后在 `running=false` 且 `rows[0]` 存在时断言 `Send`，再起回合断言回落 `Steer`。

---

## 4. 收尾

| 项 | 动作 | 结果 |
|---|---|---|
| 屏幕/密度 | `wm size reset` → `wm size 1366x768`；`wm density reset` → `wm density 160` | ✅ 复核 `Override size: 1366x768` / `Override density: 160` |
| 队列条自建内容 | 6 条 `E2ECHK5554u1s row*` 全部被回合消耗 | ✅ 队列条已空（`menu.xml` 无队列行） |
| 会话时间线 | 长按 `(664,172)` 1400ms 试图唤起删除弹层 | ❌ **未唤起**（`menu.xml` 无 Delete/Copy/Edit 任何菜单项） |
| 弹层归属核验 | `dumpsys window windows` | 仅 `Window #9 com.xiufeigo.opencodeshell/.OcActivity`，**无外来弹层**（归属可确认，但因弹层未出现而未执行删除） |
| `/sdcard` | 44 个自建 `uiautomator dump` / `screencap` 产物 | ❌ **删除被权限网关拦截，未绕过**（见下） |

### 4.1 `/sdcard` 残留（未闭环，按纪律不绕过）
残留清单（`u0_a183`，均为本轮自建）：
`afteresc.xml base_auth.xml c1_aftertap.xml c1_full.xml c1_postSubmit.xml c1_preSubmit.xml cp.xml created.xml drag1.xml esc.xml final_strip.xml longstart.xml menu.xml newsess.xml newsess2.xml parked.xml parked2.xml parked3.xml pk.xml pp.xml pre_stop.xml probe.xml q_multi.xml q_open.xml qc.xml rebuilt.xml rq_strip.xml state1.xml state1b.xml strip3.xml t1.xml t2.xml t3.xml tb.xml tl.xml` + `s_drag_before.png s_parked.png s_parked2.png s_parked_idle.png s_prestop.png`

删除命令被权限网关判定为 delete-like 并拦截，**已按纪律停止，未改用其他删除方式**。需由有权限者执行清理，或下次测试改用 `uiautomator dump /dev/tty` 等不落盘方式。

### 4.2 未闭环清单
1. **编辑贷款 `queueEditOpen` 卡死** —— 提交后 chip 恒 `Steer`，`Queue` 入口不再出现；Esc 未复位；仅靠新建会话绕过。建议立项。
2. **parked 首行 `Send` 缺陷（格三 FAIL）** —— 根因未定位到具体失效层（`running` 传参 vs 渲染分支），需加日志复现。
3. **时间线自建消息未清理** —— 长按未唤起删除弹层，`E2ECHK5554u1s` 消息仍留在 `Friendly greeting` 与 `Untitled` 两个会话。
4. **`/sdcard` 44 项残留** —— 见 4.1。
5. **拖拽重排为"不支持"** —— 若产品预期支持，需先确认需求（见格二 2.3）。

---

## 5. 逐格判定汇总

| 格 | 判定 | 一句话 |
|---|---|---|
| 一 队列行编辑态 | **部分 PASS** | 进入编辑态 PASS（正文回填 composer + `Steer` 消失仅余 `Remove`）；"提交后该行文本变化/取消则不变"按设计**不适用**（编辑是"贷款"非原地改写） |
| 二 拖拽重排 | **不支持**（不判缺陷） | `draganddrop 156,200→156,252 1500` 后行序不变；源码明示无拖拽手势 |
| 三 parked 首行 `Steer`→`Send` | **FAIL** | 12 次稳定 idle 轮询 + composer 为空，首行动作恒为 `Steer`，与 `ComposerQueue.kt:237` 相悖 |

---

## 6. 证据文件索引（`research/v97_verify/5554_md/evidence/`）

| 文件 | 用途 |
|---|---|
| `probe.xml` / `base_auth.xml` | composer 定位、已认证确认 |
| `t1.xml`~`t3.xml` | 长回合发送、composer 有内容、`Queue` chip 出现 |
| `q_open.xml` / `q_multi.xml` | 队列条首开 / 双行基线（格一前置） |
| `c1_aftertap.xml` | **格一**：编辑态标志（正文回填 + `Steer` 消失） |
| `c1_preSubmit.xml` / `c1_postSubmit.xml` / `c1_full.xml` | **格一**：提交后队列条整体消失 |
| `strip3.xml` / `pre_stop.xml` | **格二**拖前基线、**格三**运行中前置 |
| `drag1.xml` | **格二**：拖拽后瞬态 |
| `esc.xml` / `afteresc.xml` | Esc(111) 后 IME 关闭、composer 移位 |
| `parked.xml` / `parked2.xml` / `parked3.xml` | 两次 `Stop` 的 `Interrupted` 与行消耗 |
| `pp.xml` / `parked_idle.xml` | **格三**关键读数：12 轮 idle 恒 `Steer`，composer `text=''` |
| `menu.xml` | 收尾：长按未唤起删除菜单 |
| `s_drag_before.png` / `s_prestop.png` / `s_parked.png` / `s_parked2.png` / `s_parked_idle.png` | 各阶段截图 |
