# v99 `Undo` 运行时修复 — 真机复验报告（emulator-5556）

- 执行：`Undo` 运行时修复复验测试员（mcode）
- 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
- 设备：**`emulator-5556`**（AVD `Medium_Desktop`）
- 后端：`http://127.0.0.1:4095`，Basic `opencode:opencode`
- 复验对象：`research/v97_fix/UNDO_RUNTIME_FIX.md` 的运行时修复（`ChatViewModel.dropRevertedTail` + `V2Transport` 新增日志）
- 会话前缀：自建会话 **`E2ECHK5556u`**；复验会话 id `ses_f003f8245ffeuUHC91lGKjGJYp`（收尾已删）
- 纪律：不跑 gradle / 不装机 / 不杀进程；只操作 5556；不碰 127.0.0.1:5555；不按 BACK(4)；弹层内不按 ESC(111)；截图只用 `screencap -p` + `adb pull`；空格走 `keyevent 62`、≤8 字符分段；坐标运行期从 dump 解析；dump 同时匹配 `text="…"` / `text='…'`，优先 `--compressed`

---

## 判定（先给结论）

> # ✅ **PASS**（按上游正确口径）
>
> **`Undo` 在真机上不再 no-op。后端 5 → 1 条真实删除，UI 时间线清空并回到空态，logcat 出现修复新增的完整 6 行成功链（含核心信号 `undo: dropped 4 reverted row(s) from the store`）。**
>
> **但必须附一条判据冲突**：任务给的字面判据「`revert` 必须非空」**按上游契约永远不成立**——`revert/commit` 删完行就把边界一起清空，`UNDO_RUNTIME_FIX.md` §1.c/§6.7 已用实测证明（`ChatViewModel.kt:4520-4522` 的 KDoc 早有此说明）。若照字面判据打分，本次会被误判为 FAIL。详见 §5.1。

---

## 0. 环境读数（基线）

`adb` 不在本轮 shell 的 PATH 上，已定位并固定为
`C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe`（下文所有命令均带此全路径）。

| 项 | 命令 | 读数 |
|---|---|---|
| 设备列表 | `adb devices` | `emulator-5554/5556/5558/5562/5564/5600` 全 `device`（本轮**只操作 5556**） |
| boot | `shell getprop sys.boot_completed` | `1` |
| wm size | `shell wm size` | `Physical 3840x2160` / **`Override 1366x768`** |
| wm density | `shell wm density` | `Physical 320` / **`Override 160`** |
| screenState | `dumpsys window policy` | **`screenState=SCREEN_STATE_ON`** |
| keyguard | 同上 | `mKeyguardOccluded=false`、无 `keyguard showing`（= 未锁） |
| mCurrentFocus（启动前） | `dumpsys window` | `org.chromium.home.pc/HostActivity`（桌面） |
| mCurrentFocus（启动后） | 同上 | **`com.xiufeigo.opencodeshell/OcActivity`** —— **非 ANR** |
| reverse | `adb reverse --list` | `host-19 tcp:4095 tcp:4095` / `host-19 tcp:2222 tcp:22` ✅ |
| `show_ime_with_hard_keyboard` | `settings get global` | **`null`（未设置）→ 已先核，本轮不改此键** |
| SDK | `getprop ro.build.version.sdk` | `34` |
| 鉴权 | `GET /api/session?limit=5` 裸请求 | **`401`** |
| 鉴权 | Basic `opencode:opencode` | **`200`**，`len=42311` |

**设备健康闸门通过**：屏 ON、keyguard 未锁、reverse 4095 在位、`boot_completed=1`、`mCurrentFocus` **不是** `Application Not Responding` ⇒ **未触发 `adb reboot` 条件**。

**`wm` 说明**：Override 本来就是 `1366x768 / 160`，**本轮未执行任何 `wm size` / `wm density` 写入**，故不产生"验完 reset"义务；收尾已复查仍为 `1366x768 / 160`（见 §7）。

---

## 0.1 前置闸门：设备上所装构建**是否含本次修复**（本报告最关键的前置条件）

> `UNDO_RUNTIME_FIX.md` §7 自己写明「修复后的真机复验 **未做**」——当时纪律禁止装机。**若设备上是旧构建，本报告测的就是一个不含修复的二进制，结论对修复无效。** 故先做字节级确认，再开始点任何东西。

| 项 | 读数 |
|---|---|
| 包名 | `com.xiufeigo.opencodeshell`，`versionName=1.4.0` |
| `firstInstallTime` | `2026-09-23 13:58:18` |
| **`lastUpdateTime`** | **`2026-10-03 03:09:28`**（晚于 sweep 报告的 02:31–02:36 时段 ⇒ 是一次**新的**安装） |
| APK | `/data/app/~~XaBVFkL7gDxkTl3OPIf7Sw==/com.xiufeigo.opencodeshell-qy3G2iE-E8KY6FRbMxtUvg==/base.apk`（44,198,253 字节） |

### 探针方法与两次自我纠错（避免假阴性，必须记录）

1. **第一版（设备端 grep）——作废。** `adb shell grep -ac '<pat>' base.apk` 对**所有**模式返回 `0`，包括**必然存在**的 `com.xiufeigo.opencodeshell`。正向对照同为 0 ⇒ **该 grep 因权限静默失败**，其"未命中"结论**无信息量**，丢弃。
2. **第二版（拉包后直接扫 zip 字节）——同样作废。** `adb pull` 成功，但 11 个模式**全部 0**，连包名都扫不到。原因：APK 内 `.dex` 条目是 **DEFLATE 压缩**的，原始 zip 字节中不存在明文。
3. **第三版（解压 dex 后扫描）——有效。** `System.IO.Compression` 取出 **20 个 `classes*.dex`**，解压后累计 **50,844,440** 字符再扫。

> 这两次假阴性说明：**在没有正向对照的情况下，"grep 不到"极易被误读成"修复没装上"。** 本轮每版探针都强制配对照。

### 第三版探针读数（`apk_dex_scan.txt`）

| 模式 | 类别 | 命中 |
|---|---|---|
| `V2Transport` | **正向对照** | **71** ✅ 探针有效 |
| `undoSession` | 正向对照 | 16 |
| `stageRevert` / `revertStage` | 正向对照 | 2 / 2 |
| `OcNotify` | 正向对照 | 3 |
| **`undo: dispatch`** | **仅修复后存在** | **1** ✅ |
| **`undo: settle`** | **仅修复后存在** | **1** ✅ |
| **`undo: dropped`** | **仅修复后存在** | **3** ✅ |
| **`revert: POST session`** | **仅修复后存在** | **1** ✅ |
| **`commit 2xx`** | **仅修复后存在** | **1** ✅ |
| **`dropRevertedTail`** | **仅修复后存在** | **5** ✅ |

### 结论

> **设备所装构建已包含本次修复**，6 个"仅修复后存在"的字符串全部命中，且与源码树侧（`ChatViewModel.kt:4547/4551/4556/4596/4627/4636`、`v2/V2Transport.kt:381/394/396`）逐条对应。
> ⇒ **前置闸门通过：本次复验测的确实是修复后的二进制。**

---

## 1. 造可撤销态（上游口径：1 条 user 消息 + ≥1 条 assistant 回复）

| 步骤 | 命令 / 坐标（**全部运行期从 dump 解析**） | 读数 |
|---|---|---|
| 启动 | `monkey -p com.xiufeigo.opencodeshell -c android.intent.category.LAUNCHER 1` | `mCurrentFocus=OcActivity` |
| 新建会话 | `input tap 299 54`（`content-desc="New session"`，`u_t0_launch.xml`） | 弹层 `New session` / `Create session` |
| 确认创建 | `input tap 683 453`（`Create session` 标签 `[647,445][741,461]` 所在的**可点击行** `[614,429][752,477]`） | 新会话 **`ses_f003f8245ffeuUHC91lGKjGJYp`**，标题 `Untitled` |
| 输入 | `input tap 683 232`（composer `EditText`） | — |
| 打字 | `text "E2ECHK55"` → `text "56u"` → `keyevent 62`（空格）→ `text "undo"` → `keyevent 62` → `text "probe"` | `EditText` 回读 = **`E2ECHK5556u undo probe`**（逐字一致） |
| 发送 | `input tap 1332 272`（`content-desc="Send"`） | 11:13:48 |

### 回合落库（后端轮询 `GET /api/session/{id}/message?order=asc&limit=80`）

```
[ 1] count=3 user=1 assistant=1 idle=0
... 稳定 60s+ ...
types = model-switched, user, assistant
```

助手回复持续到 3 条后稳定，**满足"1 user + ≥1 assistant"的造态要求**。
> 该轮始终未出现 `idle`（UI 侧显示 `Working` 芯片），会话 `time` 为 `null`，实测**不构成 busy**，故仍可安全执行 `revert/stage`（未遇 409）。

---

## 2. 点 `Undo` **之前**的三路基线

### 2.1 后端（ground truth）

```
[PRE] count=5  users=1  revert=null
  types: model-switched, user, assistant, assistant, assistant
   model-switched  msg_0ffc07de6001Q5tI1D2EKMqn3Y
   user            msg_0ffc0ecae0017XiUzx9YrglEGo   E2ECHK5556u undo probe
   assistant       msg_0ffc0ed01001SgVmtFqLE57Tl3
   assistant       msg_0ffc2cc94001NEHjPv7d88BQ4c
   assistant       msg_0ffc34168001eLTQJxTKzRfy4w
```

**撤销边界目标 = `msg_0ffc0ecae0017XiUzx9YrglEGo`**（最后一条 user 消息）。撤销后应删除它**连同其后 3 条 assistant = 共 4 条**。

### 2.2 UI（`u_pre_undo.xml` / `.png`）

```
E2ECHK5556r probe attachment check | Undo probe E2ECHK5556u | Working |
Undo probe E2ECHK5556u | Ask anything, / for commands, @ for context... | Space Bunny Free
```

- 命中 `E2ECHK5556u` 的节点 **2 个**，均**不是**消息气泡：`[320,46][478,62]`（标签页标题）+ `[84,110][268,158]`（会话头标题按钮）。
- **空态 `Build anything` 不存在** ⇒ 时间线**有内容**。
- 状态芯片 = **`Working`**。

### 2.3 logcat

点前已 `adb logcat -c` 清空，**基线为 0 行**（下面的每一行都是本轮点击新产生的）。

### 2.4 菜单定位（纪律：解析**可点击行**，不用标签坐标）

`u_undo_menu.xml` 解析结果（`⋮` 在 `content-desc="More options"` → `input tap 1302 134`）：

| 菜单项 | 标签 bounds | **可点击行 bounds** | 行中心 |
|---|---|---|---|
| Rename | `[1132,168][1182,181]` | `[1122,158][1322,181]` | (1222,170) |
| Export session | `[1132,197][1225,209]` | `[1122,181][1322,209]` | (1222,195) |
| Import session | `[1132,225][1224,237]` | `[1122,209][1322,237]` | (1222,223) |
| **Delete** | `[1132,253][1172,266]` | `[1122,237][1322,266]` | **(1222,252)** |
| **Undo** | `[1132,282][1166,294]` | **`[1122,266][1322,294]`** | **(1222,280)** |
| Fork session | `[1132,310][1212,322]` | `[1122,294][1322,322]` | (1222,308) |
| Move to… | `[1132,338][1194,350]` | `[1122,322][1322,350]` | (1222,336) |
| New worktree | `[1132,366][1221,379]` | `[1122,350][1322,379]` | (1222,364) |

> ⚠️ **`Delete` 行中心 (1222,252) 与 `Undo` 行中心 (1222,280) 仅隔 28px。** 为杜绝误触，本轮 `Undo` 统一取 **y=284**（落在 Undo 行 `[266,294]` 内、距 Delete 行下沿 `266` 有 18px 余量），**全程未点 Delete**。
> ✅ 同时再次印证 `UNDO_RUNTIME_FIX.md` §1.a：标签节点 `clickable=false`，`enabled` 挂在标签上**与 `undoEnabled` 无关**——必须读**可点击行**的 `enabled`。

---

## 3. 点 `Undo` 之后 —— 三路读数（本报告核心）

**执行**：`input tap 1222 284`

### 3.1 ✅ logcat —— 完整 6 行成功链（**原文**）

```
10-03 03:16:44.722 13301 13301 D ChatViewModel: undo: dispatch session=ses_f003f8245ffeuUHC91lGKjGJYp users=1 boundary=null target=msg_0ffc0ecae0017XiUzx9YrglEGo transport=true
10-03 03:16:44.891 13301 13352 D V2Transport: revert: POST session/ses_f003f8245ffeuUHC91lGKjGJYp/revert/stage {messageID=msg_0ffc0ecae0017XiUzx9YrglEGo} -> messageID=msg_0ffc0ecae0017XiUzx9YrglEGo
10-03 03:16:45.169 13301 13567 D V2Transport: revert: POST session/ses_f003f8245ffeuUHC91lGKjGJYp/revert/commit
10-03 03:16:45.195 13301 13567 D V2Transport: revert: commit 2xx (rows deleted, revert cleared server-side)
10-03 03:16:45.255 13301 13301 D ChatViewModel: undo: settle session=ses_f003f8245ffeuUHC91lGKjGJYp target=msg_0ffc0ecae0017XiUzx9YrglEGo stagedBoundary=msg_0ffc0ecae0017XiUzx9YrglEGo committed=true
10-03 03:16:45.260 13301 13301 D ChatViewModel: undo: dropped 4 reverted row(s) from the store
```

逐环判读（对照 `UNDO_RUNTIME_FIX.md` §6.8 失败判读表）：

| 环 | 证据行 | 判定 |
|---|---|---|
| 点击到达派发 | `undo: dispatch … users=1 boundary=null target=msg_0ffc0ecae… transport=true` | ✅ `target` 正是 PRE 记下的那条 user 消息 |
| stage 发出并拿到边界 | `revert: POST …/revert/stage {messageID=…} -> messageID=…` | ✅ |
| commit 发出 | `revert: POST …/revert/commit` | ✅ |
| commit 2xx | `revert: commit 2xx (rows deleted, revert cleared server-side)` | ✅ **无 `revert: stage FAILED`** |
| 结算 | `undo: settle … stagedBoundary=… committed=true` | ✅ |
| **截断落进 store** | **`undo: dropped 4 reverted row(s) from the store`** | ✅✅ **本轮修复的核心信号，命中** |

> `dropped 4` 与 PRE 的 `1 user + 3 assistant` **精确对上（4 = 1+3）**。这一行在旧构建里**根本不存在**（旧代码成功路径完全静默），所以它的出现本身就是"修复已生效"的直接证据。
> **失败判读表中任何一格都没有命中**（无 `undo: dropped —`、无 `revert: stage FAILED`、无 `undiscovered`）。

### 3.2 ✅ 后端 —— 消息真实删除，条数 5 → 1

```
[POST] count=1  users=0  revert=null
  types: model-switched
   model-switched  msg_0ffc07de6001Q5tI1D2EKMqn3Y
```

| 项 | 点前 | 点后 | 判定 |
|---|---|---|---|
| 消息条数 | **5** | **1** | ✅ 减少 4 |
| `type=user` | 1 条（`msg_0ffc0ecae…`） | **0 条** | ✅ 撤销边界及其后全部回复消失 |
| `type=assistant` | 3 条 | **0 条** | ✅ |
| 幸存 | `model-switched` | `model-switched` | ✅ 合理（模型切换属会话元信息，不在撤销范围） |
| `revert` | `null` | `null` | ⚪ **按契约必然为 null**，见 §5.1 |

### 3.3 ✅ UI —— 时间线截断并回到空态

`u_post_undo2.xml` 的**全部**文本节点（按 y 排序，含几何）：

```
TextView  E2ECHK5556r probe attachment check   [68,46][250,62]      ← 另一个会话的标签页
TextView  Undo probe E2ECHK5556u               [320,46][478,62]     ← 标签页标题
Button    Undo probe E2ECHK5556u               [84,110][268,158]    ← 会话头标题
TextView  Build anything                       [639,154][727,170]   ← ✅ 时间线空态
EditText  E2ECHK5556u undo probe               [28,210][1338,258]   ← ⚠️ composer，非气泡
TextView  Space Bunny Free                     [78,264][193,280]    ← 模型选择
```

| UI 判据 | 点前 | 点后 | 判定 |
|---|---|---|---|
| **空态 `Build anything`** | **不存在** | **存在**（`[639,154][727,170]`） | ✅ **时间线已清空** |
| 状态芯片 | `Working` | 无 | ✅ |
| 消息气泡（user/assistant）行 | 时间线有内容 | **0 行** | ✅ **整轮离场** |

> ⚠️ **一个必须澄清的读数陷阱**：点后 dump 里**确实还能搜到字符串 `E2ECHK5556u undo probe`，但它是 composer 的 `EditText`**（`class=android.widget.EditText`，`bounds=[28,210][1338,258]`），**不是幸存的用户气泡**。若只做"文本是否还出现在 dump 里"的判读，会误判成"UI 没截断"。我按 `class` 排除了 `EditText` 后，非 composer 的消息节点 = **0**。
> （顺带观察：发送后 composer 未自动清空，是与本次修复无关的既有行为；下一轮发送时它自行清空了。）

### 3.4 `Redo`

| 判据 | 读数 |
|---|---|
| `Redo` 行是否存在 | **不存在**（重开菜单，`Redo present = 0`） |
| 菜单其余项 | `Rename / Export session / Import session / Delete / Undo / Fork session / Move to… / New worktree / Command palette / Toggle debug bar` 十项**全部在位** |

> ⚠️ `Redo` 未出现**不是缺陷**：`UNDO_RUNTIME_FIX.md` §6.7 明确 "`Redo` 行按 v51 既有决策（`SessionActionsModel.showsRedo`）本就只在可用时出现，撤销后不出现是**既有行为**"。且上游 `revert/commit` 删完行即清空边界，服务端**没有**可供 redo 的状态。
> **任务字面要求的「`Redo` 变可用」这一条在本轮不成立**，如实记录，不粉饰。

---

## 4. 二次撤销对照（幂等 / 边界前移）

**执行**：撤销后再次 `input tap 1222 284`（同一 `Undo` 行）。

| 读数 | 结果 | 判定 |
|---|---|---|
| **logcat** | **一条都没有**（`revert|undo:` 过滤结果为空） | 见下 |
| 后端条数 | `count=1`（**未变**） | ✅ 未误删 `model-switched` |
| 后端 `revert` | `null` | — |
| 菜单是否关闭 | **未关闭**（dump 仍列出全部 10 个菜单项） | ⇒ **点击根本没进 handler** |

### 根因：`Undo` 行已被**置灰禁用**，不是"点了没反应"

重开菜单后读取**可点击行**（非标签）的 `enabled`：

| 菜单项 | rowEnabled | | 菜单项 | rowEnabled |
|---|---|---|---|---|
| Rename | `true` | | **Undo** | **`false`** ⬅ |
| Export session | `true` | | **Fork session** | **`false`** ⬅ |
| Import session | `true` | | Move to… | `true` |
| Delete | `true` | | New worktree | `true` |
| | | | Command palette | `true` |
| | | | Toggle debug bar | `true` |

- **`Undo` 行 `enabled=false`** ⇒ Compose `clickable(enabled=false)` **不消费点击** ⇒ 菜单不关闭、handler 不执行、logcat 无输出。三者**完全自洽**。
- **`Fork session` 行同时变 `false`**，这是一条**独立的交叉验证**：fork 需要有消息可选，而现在**整条时间线已被撤空** ⇒ 从另一个入口独立证实"后端确实删干净了、store 确实空了"。

### 语义合理性判定

> **二次撤销 = 语义合理。** 不是"静默无变化"的失败，而是**收敛到正确终点**：无可撤销对象时 `Undo` 主动置灰（`undoEnabled = 边界之后仍有 user 消息`），把边界前移到了"没有下一轮"的位置，并把"此处无事可做"通过禁用态**如实暴露**给用户。
> 这恰好命中 `SessionUndoRuntimePinTest` 的收敛方向用例 `aDisabledUndoRowIsExactlyTheOneWithNothingLeftToRewind`（"可点但无事可做"不可能出现）。
>
> 唯一可挑剔之处：禁用态**没有附带文字/toast 解释**"没有可撤销的内容了"，纯靠置灰表达。属**体验增强项**，非缺陷。

---

## 5. `Redo` 恢复 与 普通发消息非回归

### 5.1 `Redo` 对照

**无法执行**：`Redo` 行在撤销后**不存在**（§3.4），菜单里没有可点对象。**本轮 `Redo` 恢复路径未闭环**，原因是上游 `commit` 后服务端不留 redo 状态 + `showsRedo` 的既有决策，非本次修复引入。**如实记为未闭环。**

### 5.2 发一条普通新消息（**非回归 PASS**）

**执行**：点菜单外空白处关闭弹层（未用 BACK/ESC）→ `Send` 按钮（`content-desc="Send"`，`[1308,248][1356,296]`）→ `input tap 1332 272`

| 读数 | 结果 | 判定 |
|---|---|---|
| 后端新回合 | `count=3  user=1  assistant=1`，`types=model-switched, user, assistant` | ✅ 正常落库并出回复 |
| UI | 空态 `Build anything` **消失**，`Working` 芯片出现，composer 清空 | ✅ 正常 |
| **`Undo` 行 rowEnabled** | **`true`**（由 `false` 恢复） | ✅ **撤销能力正确重新武装** |
| **`Fork session` 行 rowEnabled** | **`true`**（由 `false` 恢复） | ✅ |

> ⇒ 撤销**没有把会话或传输层搞坏**：发消息、收回复、`Undo`/`Fork` 重新可用，**非回归通过**。

### 5.3 判据冲突说明（务必读）

任务字面判据写的是「**`revert` 必须非空**且 UI 反映截断 → PASS」。本次实测 `revert = null`。但：

- `UNDO_RUNTIME_FIX.md` §1.c 实测并论证：`revert/commit` **删完行就把边界一起清掉**，`revert` 之后必然为 `null`；
- 源码 `ChatViewModel.kt:4520-4522` 的 KDoc 早已写明这一点；
- 上游契约（`core/src/session/revert.ts:78-84` → `projector.ts:731-767`）亦然。

⇒ **「`revert` 非空」按上游正确口径是一个不成立的判据**（`REPORT_FINAL_SWEEP.md` 条目 6 正是因此误判）。本报告**按上游正确口径**判定：**后端行数减少 + UI 截断 + logcat 成功链** 三者同时成立 ⇒ **PASS**。

---

## 6. 汇总判定表

| # | 判据 | 期望 | 实测 | 判定 |
|---|---|---|---|---|
| 1 | 前置：设备构建含修复 | 含 | 6 个修复专有字符串全部命中 | ✅ |
| 2 | logcat `undo: dispatch` | 出现 | 出现，`target=msg_0ffc0ecae…` | ✅ |
| 3 | logcat `revert: stage/commit 2xx` | 出现 | 出现，无 `FAILED` | ✅ |
| 4 | **logcat `undo: dropped 4 reverted row(s)`** | **出现** | **出现（4 = 1 user + 3 assistant）** | ✅ **核心** |
| 5 | 后端消息条数 | 减少 | **5 → 1** | ✅ |
| 6 | 后端 user 消息及其后回复 | 不再返回 | `users=0`，`assistant` 归零 | ✅ |
| 7 | **UI 时间线截断** | 截断/空态 | **空态 `Build anything` 出现，气泡 0 行** | ✅ |
| 8 | 二次撤销 | 边界前移或诚实反馈 | **`Undo` 行 `enabled=false`，不消费点击** | ✅ 语义合理 |
| 9 | 发新消息非回归 | 正常 | 新回合落库，`Undo`/`Fork` 重新启用 | ✅ |
| 10 | UI `Redo` 变可用 | 出现 | **未出现** | ⚪ 既有决策，非缺陷（但**任务字面判据不成立**） |
| 11 | `revert` 非空 | 非空 | **`null`** | ⚪ **按契约必然 null，判据本身不成立** |
| 12 | `Redo` 恢复路径 | 可点恢复 | **无 `Redo` 可点** | ⛔ **未闭环**（上游不留 redo 状态） |

> **一句话**：**`Undo` 真机复验 PASS** —— 修复前"后端真删、UI 一帧未变"的自相矛盾已消除，**UI 与后端现在一致反映截断**；`logcat` 首次给出完整可观测的成功链。仅 `Redo` 路径因上游契约而不闭环。

---

## 7. 纪律遵守与收尾

| 项 | 读数 |
|---|---|
| gradle / 装机 | **全程未执行**（只做只读字节探针 + 交互复验） |
| 杀进程 | **未执行**（`am force-stop` 也未用） |
| 只操作 5556 | ✅ 其余 5 台 emulator 未发任何命令 |
| 未碰 `127.0.0.1:5555` | ✅ |
| 未按 BACK(4) | ✅ 关闭弹层一律用"点菜单外空白" |
| 弹层内未按 ESC(111) | ✅ |
| 截图 | 仅 `screencap -p` + `adb pull`（**未用 exec-out**） |
| 空格输入 | 走 `keyevent 62`；打字分段 `E2ECHK55` / `56u` / `undo` / `probe`（均 ≤8 字符），**未用 `%s`** |
| `show_ime_with_hard_keyboard` | **已先核 = `null`，全程未改** |
| `wm size` / `wm density` | **全程未写入**；收尾复查仍为 `Override 1366x768` / `Override 160`（与基线一致） |
| 坐标 | **全部运行期从 dump 解析**（含 `Delete`/`Undo` 的可点击行），未手填 |
| dump 解析 | 同时匹配 `text="…"` / `text='…'`，统一 `--compressed` |
| **绝不点 Delete 文案** | ✅ **全程未点 Delete**；自建会话改用后端 `DELETE /api/session/{id}` 精确删除（**204**） |
| 自建会话清理 | `ses_f003f8245ffeuUHC91lGKjGJYp` 已删；`GET /api/session` 复查**不再出现** |
| `/sdcard` 残留 | 本轮 18 个 dump/截图文件**已全部 `rm -f`**；复查 **`(no u_* leftovers)`** |
| 收尾 `mCurrentFocus` | `com.xiufeigo.opencodeshell/OcActivity`（非 ANR） |

> `wm` 复检输出：
> ```
> Physical size: 3840x2160      Override size: 1366x768
> Physical density: 320         Override density: 160
> ```
> 与基线逐字一致，**本轮从未写入，无需 reset**。

---

## 8. 证据文件

| 文件 | 内容 |
|---|---|
| `apk_dex_scan.txt` | APK dex 字节扫描结果（前置闸门） |
| `u_t0_launch.xml` | 启动后首屏（`New session` 坐标来源） |
| `u_t1_newses.xml` | `New session` 弹层（`Create session` 坐标来源） |
| `u_t2_created.xml` | 新会话已建（composer 坐标来源） |
| `u_t3_typed.xml` | composer 回读 `E2ECHK5556u undo probe` |
| `u_pre_undo.xml` / `.png` | **点 Undo 前 UI 基线** |
| `u_undo_menu.xml` | `⋮` 菜单（`Undo` / `Delete` 可点击行坐标） |
| `u_undo_post.xml` / `.png` | 点 Undo 后首次 dump |
| `u_post_undo2.xml` / `.png` | **点 Undo 后 UI 空态（截断证据）** |
| `u_menu_after_undo.xml` | 撤销后菜单（`Redo` 缺失读数） |
| `u_undo2_post.xml` | 二次 Undo 后（菜单未关、logcat 空） |
| `u_menu_rows2.xml` | **可点击行 `enabled` 读数（Undo/Fork = false）** |
| `u_t4_nonreg.xml` | 关闭弹层后（`Send` 坐标来源） |
| `u_post_nonreg.xml` / `.png` | 新消息后 UI |
| `u_menu_rearmed.xml` | **`Undo` 重新 `enabled=true`** |
| `undo_tools.ps1` | 本轮使用的 dump/坐标/后端读数脚本 |

---

## 9. 未闭环

| 项 | 状态 | 原因 |
|---|---|---|
| `Redo` 恢复路径 | **未闭环** | 撤销后 `Redo` 行不出现（`showsRedo` 既有决策 + 上游 `commit` 后服务端不留 redo 状态），**无可点对象**。非本次修复引入 |
| 任务判据「`revert` 非空」 | **不成立** | 按上游契约 `commit` 后必然清空；据此打分会把 PASS 误判为 FAIL |
| UI「`Redo` 变可用」 | **未成立** | 同上 |
| 二次撤销的显式文字反馈 | **无** | 仅靠行置灰表达"无可撤销"，无 toast/说明。体验增强项 |
| 回合未等 `idle` | **观察项** | 该轮 3 条 assistant 后稳定但始终无 `idle`（UI 显示 `Working`）；会话 `time=null`，未阻塞 `revert/stage`，不影响判定 |
| composer 发送后未自动清空 | **观察项** | 与本次修复无关的既有行为；下一轮发送自行清空 |
| 备份 APK | **已删除** | `base_5556.apk`（44MB）为构建产物，结论已固化在 `apk_dex_scan.txt`，不入库 |
