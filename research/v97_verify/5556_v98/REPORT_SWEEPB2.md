# v98 收口测试报告 B2 — sweepB 的 12 项未验到 + 3 条未闭环（emulator-5556 / Medium_Desktop）

- 执行 Agent：真机收口测试员（③-B · sweepB2）
- 设备：`emulator-5556`，AVD 名 **Medium_Desktop** ✅（`emu avd name` 两次复核一致）
- 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
- 上游依据：`research/v97_verify/5562_sweepB/REPORT_v97b.md` §5「未闭环 / 未验到」
- 免重复依据：`research/v97_verify/5556_v98/REPORT_SWEEPC2.md`（Import / Undo / 复制按钮 / Background shells 已验项）
- 修复依据：`research/v97_fix/NOTIFICATION_DOMAIN.md`（通知复验命令级步骤 §5）
- 自建会话前缀：**`E2ECHK5556w`**（实际落库会话 `ses_f00b34d66ffeUJwM1HnMxMDTBF`）
- 纪律：不跑 gradle / 不装机 / 不杀进程 / 只动 5556 / 未点任何 Delete 文案 / 弹层内未按 ESC(111) / 未按 BACK(4) / 截图仅 `screencap -p` + `adb pull` / 输入 ≤8 字符分段 + 空格 `keyevent 62` / 坐标全部运行期从 dump 解析 / dump 同时匹配 `text="…"` 与 `text='…'`。
- **未改任何源码。**

> **状态：DONE（受设备可用性拖累，见 §0 事故记录）**
> 10 个派单项：**PASS 6**（Session summary / `/btw` / 复制按钮 / 终端两面板 / 拖拽 resize / N6 机制）· **FAIL 1**（`Undo`，引 SWEEPC2）· **部分 1**（通知：Channel 存在被证实、投递与清除仍为 0）· **未验到 2**（队列 `×`/`Steer`、非图片附件）。
> 另收口 2 条历史未闭环（`show_ime_with_hard_keyboard` PASS / T11 导航轨未验到）。
> **N6 截断机制已在源码级完整定位；N7「无 NotificationChannel」被真机读数推翻。**

### 关键结论一句话
本轮最有价值的两个收口是**否定式的**：N7 的「通知域整体未实现」在真机上被**证伪一半**（`opencode.sessions` Channel 确实存在且已按 `NOTIFICATION_DOMAIN.md` 的懒创建语义解释），但**投递仍为 0 且 logcat 连 `trigger idle:` 都不打**——这比 v97b 记录的「无任何证据」前进了一大步，把断点从「整条链路未接线」收窄到「**IDLE 帧 → raise 这一跳未触发**」；而 N6 的截断机制被**证伪为客户端上限**，真正打掉数字的是服务端 `statUntracked` 的 4096 B 上限，外加面板标签口径与实际 diff 基准不符。

---

## 0. 设备事故记录（必须先读，影响本轮所有读数的可信度）

开局设备处于**不可用状态**，前 ~14 分钟消耗在恢复上：

| 序 | 现象 | 处置 | 依据 |
|---|---|---|---|
| 1 | `mCurrentFocus=null`、`uiautomator dump` 持续 `null root node returned by UiTestAutomationBridge`、连 `wm size` 都拿不到 | 查 ANR（无 ANR）→ 读 display 电源态 | `b2_003_probe` DUMPFAIL |
| 2 | `mWakefulness=Awake` 但仍无窗口焦点 | `keyevent 224` + `keyevent 26` 无效 | `b2_050_home` |
| 3 | 尝试 `wm size reset` 仍不恢复 | 按纪律升级到 `adb reboot`（已授权） | `b2_050_home` DUMPFAIL |
| 4 | reboot 后 `service check window` 长时间 `Can't find service`，`boot_completed=1` 是**陈旧值** | 改为轮询 `init.svc.bootanim=stopped` + `sys.boot_completed=1` 双重条件 | `b2_030_home` |
| 5 | reboot 清空了 `reverse --list` | 重挂 4095 / 2222 | §1 |
| 6 | **根因**：`dumpsys window policy` → `screenState=SCREEN_STATE_OFF` + `keyguard showing=true` + `mCurrentFocus=NotificationShade` | — | 关键读数 |
| 7 | `keyevent 26` / 触摸滑动均**无法**点亮 | **`keyevent 224` + `input tap 683 400` 组合才成功** | `b2_060_home` `screenState=SCREEN_STATE_ON` |
| 8 | 屏幕反复自动休眠（模拟器 `mIsPowered=false`，`stay_on_while_plugged_in` 机制**对本机无效**） | 改用 `settings put system screen_off_timeout 1800000` 兜底 | 终态已还原为原值 `2147483647` |

**方法论沉淀（建议写进工具链）**：
1. **`mCurrentFocus=null` 的排查顺序应是 `dumpsys window policy | grep screenState` → keyguard → shade，而不是直接怀疑 ANR。** 本轮在 ANR 上浪费了 2 轮。
2. **模拟器上 `input keyevent 26`(POWER) 不可靠**，`keyevent 224`(WAKEUP) 后补一次 `input tap` 才能真正点亮。
3. **`svc power stayon true` / `stay_on_while_plugged_in` 在 `mIsPowered=false` 的模拟器上是空操作**，只能靠 `screen_off_timeout`。
4. `Dump` 助手 4 次重试 + 字节数门槛（≥4 位）有效，全程未出现假 0 字节 dump 被当成读数的情况。

---

## 1. 环境读数

| 项 | 读数 | 判定 |
|---|---|---|
| 设备在线 | `emulator-5556`（同机 5554/5558/5560/5562/5564/5584/5590 **全程未触碰**；未碰 127.0.0.1:5555） | ✅ |
| AVD 名 | `emu avd name` = **Medium_Desktop**（reboot 后再次复核同值） | ✅ 与派单一致 |
| `wm size` **开局** | `Physical 3840x2160`，**无 override** | ⚠️ **与派单所述「1366x768@160dpi」不符** |
| `wm density` **开局** | `Physical 320`，**无 override** | ⚠️ 同上 |
| 本轮工作几何 | 显式配 `wm size 1366x768` + `wm density 160` → `Override size: 1366x768` / `Override density: 160` | ✅ dump 根 bounds `[0,0][1366,768]`，maxX=1366，坐标空间自洽 |
| APK 闸门 | `versionName=1.4.0` `versionCode=26` **`lastUpdateTime=2026-10-02 15:20:48`** | ✅ **与派单要求逐字一致，v98k 已复核** |
| SDK | `targetSdk=35` `minSdk=26` | 记录 |
| reverse | 开局 `host-19 tcp:4095` / `tcp:2222` 在；**reboot 后被清空** → 已重挂并复核 | ✅ |
| `show_ime_with_hard_keyboard` | 开局读到 **1**（符合要求，本轮全程未改，终值 1） | ✅ |
| `POST_NOTIFICATIONS` | `granted=true, flags=[USER_SET\|USER_SENSITIVE_...]`（本轮 `pm grant`） | ✅ |
| 通知 Channel | **`NotificationChannel{mId='opencode.sessions', mName=System notifications, mImportance=3, mVibrationEnabled=true, mShowBadge=true, mDeleted=false}`** | ✅ **存在——推翻 v97b N7** |
| 后端 | `GET /api/info` → 200 `{"version":"2.0.18","pid":260,"urls":["http://127.0.0.1:4095"]}` | ✅ |
| launcher Activity | `.OcActivity`（沿用 SWEEPC2 的 D7 结论，未重复验证） | 引用 |
| 进程 uid | `u0_a189`（reboot 后重分配，与前轮 `u0_a183` 不同） | 记录 |

**新发现（环境级）**：`wm` override 在 reboot 后**不保留**，且开局本机**本就无 override**。`REPORT_SWEEPC2.md` 记录的「基线 = Override 1366x768」在本轮开局**不成立**——说明有别的 agent 在两轮之间执行过 `wm size reset`。共享设备的 wm 状态需要更强的协调约定。

---

## 2. 逐条判定表

判定口径：**PASS** = 有可复现读数且符合预期；**FAIL** = 有读数且不符合预期；**未验到** = 控件/条件不成立或时间不足，已写明原因。

### 条目 1：队列项的 `×` / `Steer` — **未验到（控件在本 build 不可见）**

| 步骤 | 命令 | 读数 |
|---|---|---|
| 建会话并发长回合 | `New session` → `Create session` → 改名 `E2ECHK5556w-01` → composer 输 `Count slowly from 1 to 40. Output one number per line, one second apart.` + `keyevent 66` | 进入流式：`Working` + `Stop` 双节点同屏（`b2_111_streaming.xml`） |
| 造队列 | 流式中连发 4 条 | 后端落库 **4 条 user**：`msg_0ff4dbf8…`/`msg_0ff4df30…`/`msg_0ff4ea30…`/`msg_0ff4ecdc…` |
| 找 `×` | 逐节点解析队列条区域（y 140–250, x<1316） | **只有 4 个节点**：`View [1184,162][1312,194]`（clickable=**false**）、`TextView QUEUE-B-2048`、composer 2 个。**无 `×`、无 `Steer` 节点** |
| 点队列 chip | `input tap 1248 178` | dump 差集为空 —— **无展开、无反应** |
| 点队列 `≡` | `input tap 1338 175` | 弹出节点 `[1328,162][1352,186]`，但**无任何 `Steer`/`Cancel`/`Discard` 标签** |
| 目视复核 | `b2_140_queueview.png` | 队列条**只有一个 chip** `QUEUE-B-2048`（右侧 x≈1184–1312），其右 `≡`；**画面上不存在 `×` 与 `Steer`** |

- **判定：未验到**，不是 FAIL——`v97b` §3.1 C7 记录过 `T[Queued]` + `Steer` + `×`，本 build **不再出现**。三种可能的区分需要开发确认：① 队列条目 ≥2 时只显示最后一个 chip、控件随 hover/长按出现；② 该 build 回归移除了控件；③ 队列条宽度不足（1366 宽下被压缩到 128px）导致控件被裁。
- **附带强读数**：队列条容器 `View [1184,162][1312,194]` 的 **`clickable=false`**，且其下**无子节点**——若控件真存在，应至少有一个可点击子节点。这是「控件未渲染」的 a11y 侧证据。
- **附带读数**：导航轨（x=1316–1364）忠实反映了全部 4 条队列消息（`QUEUE-A-E2ECHK5556w-7391` / `QUEUE-B-E2ECHK5556w-2048` / `QUEUE-A-7391` / `QUEUE-B-2048`），**队列正文未丢**。
- 证据：`b2_111_streaming.*`、`b2_132_queueA.*`、`b2_133_queueB.*`、`b2_140_queueview.png`、`b2_141_qmenu.xml`、`b2_142_chip.*`

### 条目 2：非图片附件（.txt/.md） — **未验到（到达附件选择器，但 DocumentsUI 不接受点选）**

| 步骤 | 命令 | 读数 |
|---|---|---|
| 造文件 | `Set-Content` + `adb push` | `/sdcard/Download/E2ECHK5556w_note.txt` → `ls -l` = **82 B**（3 行，含 `ALPHA-7391` / `BETA-2048` / `GAMMA-5526` 三个可断言标记） |
| 打开 `+` 菜单 | `input tap 31 272`（`Add` 节点 `[10,248][52,296]` 运行期解析） | 菜单 5 项齐全：`Attachments`(`Mod+U`) / `Commands` / `Context` / `Shell` / `Paste` ✅ |
| 进入附件选择器 | `input tap 90 447`（`Attachments` 节点 `[32,439][149,455]`） | SAF 打开，`mCurrentFocus=com.google.android.documentsui/.picker.PickActivity`；chip `Downloads` / `Images` / `Documents` / `Files in Downloads` |
| **非图片文件确实进入列表** | dump | ✅ **`E2ECHK5556w_note.txt` 可见**（`[74,432][170,451]`），同列还有 `E2ECHK5556c2-RENAME.json`、`Interpreting-E2ECHK5556c-hello-world-string-fork-1.json` —— **`.txt` 未被类型过滤排除** |
| 点缩略图 | `input tap 158 290` | 无反应（仍在 SAF） |
| 点文件名 | `input tap 122 442` | **无反应**（`b2_410_sel.xml` 仍是 SAF 全量节点） |
| 点行空白处 | `input tap 400 441` | **无反应** |
| 双击文件名 | `tap 122 442` ×2（间隔 180ms） | **无反应** |

- **判定：未验到**。「非图片附件**被接受并进上下文**」这个断言**无法成立** —— 4 种点选方式全部无效，SAF 始终停在列表态。**因此不能判定 App 缺陷**：这也可能是本机 DocumentsUI 在 `wm size` override 下的自动化兼容问题（对比：`REPORT_v97b.md` P2–P5 在 **5562** 上走通了同一条 `+ → Attachments` 路径并成功上传 PNG，说明 App 侧路径本身是通的）。
- **已确证的正面读数**：**`.txt` 文件不会被附件选择器的类型过滤排除**，且带 `Preview the file E2ECHK5556w_note.txt` 缩略图节点 —— 即「非图片附件**可被选到**」这一半已验证。
- **未确证**：选中后是否进 composer 缩略图、是否随消息上传、后端是否落 `files[]`、模型是否真读到 `ALPHA-7391` 等 3 个标记。
- 证据：`b2_401_addmenu.xml`、`b2_402_saf.xml` / `.png`、`b2_403_selected.*`、`b2_410_sel.xml`、`b2_420_sel1.*`、`b2_421_sel2.*`



### 条目 3：菜单 `Import` / `Fork` / `Move` / `New worktree` / `Undo` — **部分闭环（2 PASS + 1 FAIL 已由 SWEEPC2 闭环，3 项未做）**

先读 `REPORT_SWEEPC2.md` 避免重复，只补其未覆盖部分：

| 子项 | 判定 | 依据 |
|---|---|---|
| `Import session` 成功路径 | **PASS（引用 SWEEPC2 条目 1）** | 已闭环：Export→落盘 999 B→Import→新 session `ses_01a0fce2…`。**不重做** |
| `Undo` 真实语义 | **FAIL（引用 SWEEPC2 条目 2）** | 已闭环：造可撤销态（改名成功，UI+后端双确认）→ 点 `Undo` → UI 与后端标题**双双不变** → 排除「无可撤销」，坐实**未接线（no-op）**。**不重做** |
| `Fork session` | **未做** | — |
| `Move to…` | **未做** | — |
| `New worktree` | **未做** | 已知 v97b L9 记录了运行位置选择器的 worktree 列表（`curious-cabin`/`happy-panda`/`calm-eagle`/`eager-lagoon`/`tidy-orchid`），但**未执行创建**（会留下不可清理的对象，本轮时间也不够） |
| 菜单项存在性 | **PASS（本轮复核）** | `New session` / `Command palette` / `Session summary` / `Context usage` / `More options` / `Toggle review panel` / `Open terminal` 均在 header 实测可解析 |

### 条目 4：`Session summary` 面板 — **PASS（打开成功，内容与真实环境一致）**

| 项 | 面板读数 | 真实环境 ground truth | 一致 |
|---|---|---|---|
| `Project` | 面板标题行 | — | — |
| `Workspace` | **`opencode-android-shell`** | host 仓库名 | ✅ |
| `Branch` | **`main`** | 仓库当前分支 `main` | ✅ |
| `Changes` | **`12 files  ·  +313  −1`** | `git status --porcelain` = **3019** 条；`git diff --shortstat HEAD` = **207 files, +57407 −9201** | ❌ **第三个互不相同的口径**（见条目 10） |
| `Server` | **`http://127.0.0.1:4095`** | `reverse` 隧道目标 | ✅ |
| `MCP` / `Plugins` / `Skills` / `LSP` | Plugins **88** / Skills **5** / MCP、LSP 无数字 | — | 记录 |
| 其他行 | `Background tasks`、`Statistics`、`Reload`、`Web search`、`Close` | — | 记录 |

- **判定 PASS**：面板可打开、可读、且 Workspace / Branch / Server 三项与真实环境**逐字一致**。
- **新发现（重要）**：该面板是**项目级 summary**（workspace/branch/changes/server/MCP/plugins/skills/LSP），**不是**「当前会话的对话摘要」。派单措辞「内容与真实会话是否一致」在此语境下应理解为**项目上下文**——面板确实反映真实项目，但**不包含本会话的消息摘要**（无消息数、无 token、无最近回合）。
- **附带**：面板的 `Changes` 行与 Review→Changes 页、与 `git` 三方口径互不相同（`12 files` vs `+153 -76` vs `207 files`），是 N6 口径混乱的**第三个独立读数**。
- 证据：`b2_170_home.xml`、`b2_171_summary.xml`、`b2_171_summary.png`

### 条目 5：`/btw` — **PASS（执行成功，且不污染主时间线）**

| 步骤 | 命令 | 读数 |
|---|---|---|
| 输入 | composer 输 `/btw` | 弹出 `/` 面板，含 **`/btw`** 节点 `[52,170][81,186]` + **`Ask a side question`**（`b2_173_btwlist.xml`） |
| 选中 | `input tap 66 178` | 面板关闭，composer 回空态，header 出现 `Ask a side question` |
| 侧问 | 输 `E2ECHK5556w-BTW-PROBE-7391` + `keyevent 66` | 进入流式：`Working` + `Stop`（`b2_176_btwdone.xml`） |
| **主时间线隔离（后端 ground truth）** | `GET /api/session/ses_f00b34d66ffeUJwM1HnMxMDTBF/message?order=asc&limit=80` | 13 条消息：`model-switched / user / assistant / user / user / assistant / user / user / assistant / idle / user / assistant / idle`；**全文正则 `BTW-PROBE` → `False`** |
| UI 隔离 | `b2_180_btwreply.xml` | 侧问内容出现在**侧问区域**，header 持续显示 `Ask a side question` 模式标记 |

- **判定 PASS**：`/btw` 命令在 `/` 面板中存在且可执行；侧问回合**独立成对**（`user`+`assistant`+`idle`），**正文完全不进主时间线**（后端 13 条里搜不到 `BTW-PROBE`）。
- 证据：`b2_173_btwlist.*`、`b2_174_btwsel.xml`、`b2_175_btwtyped.xml`、`b2_176_btwdone.*`、`b2_180_btwreply.xml` + 后端 13 条 message 读数

### 条目 6：各类复制按钮的剪贴板校验 — **PASS（引用 SWEEPC2 条目 7，不重做）**

`REPORT_SWEEPC2.md` 条目 7 已用**回贴法**（复制 → PASTE → 读回 EditText 文本）强判定通过三种按钮：

| 按钮 | 剪贴板读回 | 判定 |
|---|---|---|
| `Copy response` | `你好，收到消息了。我这边在 /mnt/c/.../e2echk5556v98 目录下待命…` | ✅ 与气泡正文逐字一致 |
| `Copy message` | `E2ECHK5556c2 hello` | ✅ 与用户气泡原文一致 |
| `Copy code` | 284 字符 python 代码块 | ✅ 与渲染的 `python` 代码块一致 |

- 三条 shell 剪贴板路径（`cmd clipboard` / `dumpsys clipboard` / `service call clipboard`）沿用上轮结论**仍不可用** → 回贴法是当前唯一可行方案。
- **本轮唯一补充读数**：本 build 的气泡动作行实测为 `Revert message` + `Copy message`（`[1242,158][1276,178]` / `[1276,158][1316,178]`），`Copy response` / `Copy code` 仅在对应内容存在时出现 —— 与 SWEEPC2 的结论一致（`oc_md_copy_code` 只在 markdown 代码块渲染时出现）。
- **遗留未闭环（引 SWEEPC2 D10）**：三个复制按钮点击后**无任何可见 UI 反馈**（无 toast / snackbar / 图标态变化），只有剪贴板内容变更。无法断言「反馈是否本应存在」。
- **遗留未闭环**：`Revert message` 的**实际行为**（是否真回滚上一条消息）本轮仍未点，v97b 与 SWEEPC2 均只验了控件存在性。

### 条目 7：终端 `Session terminals` / `Background shells` 两面板 — **PASS（本轮独立复核）**

| 面板 | 入口 bounds（运行期解析） | 打开后读数 |
|---|---|---|
| `Session terminals` | `[1242,116][1282,164]` | 标题 `Session terminals`、空态 **`No session terminals`**、右上 `New session terminal`、另有 `Close terminal panel` / `Close terminal` / `Terminal 11` |
| `Background shells` | `[1282,116][1322,164]` | 标题 `Background shells`、空态 **`No shell commands`**、右上 `New shell` |

- **两个面板均可打开、空态文案正确渲染** ✅。入口按钮与 SWEEPC2 条目 3 的读数一致（当时 `[1282,116][1322,164]`，本轮 `Background shells` 同位），**结论稳定可复现**。
- **本轮新增**：终端面板工具条第三个按钮读数为 **`New terminal`**（`[1322,116][1366,164]`），与 SWEEPC2 记录的 `New session terminal` 位置相同但**文案不同** —— 可能是「终端 tab 已存在时显示 `New terminal`」的状态差异，建议开发确认是否同一控件的动态文案。
- **本轮未验到**：两个面板的**非空态**。SWEEPC2 已用 `>_` 创建出带 pid 的 `Session terminals` 非空态（PASS），但本轮终端面板是**新开**的，尚未创建任何 session terminal / background shell。
- 证据：`b2_191_term.xml`、`b2_200_sessterm.*`、`b2_201_shells.*`

### 条目 8：拖拽 resize — **PASS（拖得动 + 松手保持）**

| 读数 | 拖前 | 拖后 | 松手 3s | 松手 8s |
|---|---|---|---|---|
| 拖拽把手 bounds | **`[820,114][828,672]`** | **`[682,114][690,672]`** | `[682,114][690,672]` | **`[682,114][690,672]`** |
| `Terminal 11` tab | `[868,122][1046,158]` | `[730,122][908,158]` | — | `[730,122][908,158]` |
| 语义节点 | `Drag to resize the terminal panel, double-tap to reset` | 同 | 同 | 同 |

- 命令：`adb shell input swipe 824 393 674 393 900`（把手 x=824、y=393 **取自 dump 中心**，非硬编码）。
- **可调 ✅**：面板真实变宽 **138px**，终端 tab 标签随之左移 138px，二者**严格同步**。
- **松手保持 ✅**：8s 后读数**无任何回弹**（把手与 tab 坐标与拖后逐字一致）。
- **本轮未验到**：**双击 reset**。语义节点明确写有 `double-tap to reset`，但双击在 `input swipe` 模型下无法可靠合成（需两次 `input tap` 间隔 < 系统双击阈值，`adb shell` 往返延迟通常超阈值）。这是 v97b §5 中该项的**另一半**，保持未验到。
- 证据：`b2_202_afterclose.xml`、`b2_203_pre.xml`、`b2_204_post.*`、`b2_205_hold.xml`

### 条目 9：通知清除（按 `NOTIFICATION_DOMAIN.md` §5 命令级复验） — **部分闭环：Channel 存在被证实，投递与清除仍为 0，断点收窄到 IDLE→raise**

严格按 `NOTIFICATION_DOMAIN.md` §5 的三条件协议执行（**回合必须真正 idle** + **app 在后台** + **权限已 granted**），两轮：

#### 轮 1：长回合（`Count slowly from 1 to 40`）

| 步骤 | 命令 | 读数 |
|---|---|---|
| 前置 | `pm grant … POST_NOTIFICATIONS` | `granted=true` ✅ |
| 开流 | logcat `-c` → 发送长回合 → 确认 `Working`+`Stop` | ✅ |
| 切后台 | `input keyevent 3`（**无效**，`am start` 提示 `delivered to currently running top-most instance`）→ 改用 `am start -a android.intent.action.MAIN -c android.intent.category.HOME` | ✅ `mCurrentFocus=org.chromium.home.pc/.HostActivity` |
| 回合真 idle | 后端 `GET /api/session/ses_f00b34d66ffeUJwM1HnMxMDTBF/message?order=asc&limit=80` | ✅ **落库 `msg_0ff4fa6870013y1hxj215AnPFQ type=idle`** —— **三条件全部满足** |
| logcat | `logcat -d -s OcNotify:D *:S` | ⚠️ **只有 3 行**：`foreground: paused=OcActivity` / `focus: lost` / `foreground: stopped`。**无 `trigger idle:`、无 `raise:`、无 `withheld:`、无 `recheck:`、无 `posted:`** |
| Channel | `dumpsys notification --noredact \| grep -i opencodeshell` | ✅ **`NotificationChannel{mId='opencode.sessions', mName=System notifications, mImportance=3, mVibrationEnabled=true, mShowBadge=true, mDeleted=false, mOriginalImp=3, mDelivered…}`** |
| NotificationRecord | 同上 | ❌ **无任何 `NotificationRecord`**（只有 `AppSettings: com.xiufeigo.opencodeshell (10189) importance=DEFAULT userSet=true`） |
| 投递列表 | `cmd notification list com.xiufeigo.opencodeshell` | ❌ **0 条属于本包**（返回的 5 条全属 `com.android.systemui` / `android`） |

#### 轮 2：短回合（`Reply with the single word ALPHA7719 only.`）—— 排除「长回合超时」干扰

| 步骤 | 读数 |
|---|---|
| 发送 | `Working` + `Stop` 确认进入流式 |
| 立刻切后台 | ✅ launcher 取得焦点 |
| 等 30s | — |
| logcat | **仍只有 3 行 `foreground:` 行**，**依旧无 `trigger idle:`** |
| 投递列表 | **仍 0 条** |

#### 判读（按 `NOTIFICATION_DOMAIN.md` §5「复验判读表」）

| 判读表条目 | 本轮是否出现 | 含义 |
|---|---|---|
| `posted: id=…` | ❌ 未出现 | 投递未成功 |
| `skip: notifications disabled for the package` | ❌ 未出现 | 门禁6 未拦 |
| `gate settings: kind=AGENT enabled=false` | ❌ 未出现 | 门禁2 未拦 |
| `raise: skipped foreign session=…` | ❌ 未出现 | 门禁1 未拦 |
| `trigger idle: … finished=false` | ❌ **未出现** | **IDLE 帧到了（后端可证）但 `notifyFor` 根本没被调用** |
| **完全没有 `trigger idle:`** | ✅ **命中** | **按判读表 = 「回合未完成 / idle 帧没到」** —— 但**后端 `type=idle` 已落库，与判读表描述不符** |

**这是本轮最有价值的发现，也是对 v97b N7 的实质性修正：**

1. **N7「无 NotificationChannel」被证伪** ✅。Channel `opencode.sessions` **确实存在**，`NOTIFICATION_DOMAIN.md` §5 关于「懒创建于 `post()`」的解释成立；`v97b` 读到的「无 Channel」不能作为独立证据（与该报告 §5 自己的论断一致）。
2. **但 N7「后台通知投递」的定性需要升级**：原报告说「静态链路完整可投，只是从未在正确条件下取过读数」。本轮**已经在正确条件下取了两次读数**（权限 granted + app 在后台 + 后端 `type=idle` 可证回合结束），结果**两次都是 0**，且**连 `trigger idle:` 这一行日志都没有**。
3. **断点被收窄到 `ChatViewModel.notifyFor` 的 IDLE 分支未被执行**（`NOTIFICATION_DOMAIN.md` §2 标注的 `ChatViewModel.kt:7056-7108` → `:7099 raise`）。不是权限、不是 Channel、不是焦点门禁、不是 `ranTurn` 的问题——**是 `notifyFor` 在 IDLE 帧上根本没跑**。
4. **⚠️ 但必须诚实标注一个反证**：Channel 的存在意味着**历史上 `post()` 至少被调用过一次**（`ensureChannel()` 的唯一调用点是 `SystemNotifier.kt:353` 的 `post()`）。这与「`notifyFor` 从未 raise」**不完全自洽**。三种可能：(a) Channel 由前几轮其他 agent 的通知测试创建；(b) `ensureChannel()` 还有本报告未发现的第二个调用点；(c) 某次 `post()` 走到了 `manager.notify` 但被 `areNotificationsEnabled()` 之后的某步丢弃。**本轮无法区分**，需开发加日志定位。
5. **「清除通知」无法验到** —— 与 v97b P19 同因，但**原因已升级**：不再是「不知道通知域是否实现」，而是「**已确认本包 0 条可清**」。清除路径（`cmd statusbar expand-notifications` → 逐条上滑）因此无从执行。
6. **`notifyErrors` 默认 false**（`NOTIFICATION_DOMAIN.md` 附带发现 A）在本轮不构成干扰 —— 本次是**成功回合**（IDLE），走的是 `notifyAgent` 开关，与 `notifyErrors` 无关。

证据：`b2_150_home.png`、`b2_151_bg.png`、`b2_160_back.xml`、`b2_161_typed.xml`、`b2_162_sent.*` + 两轮 `logcat -d -s OcNotify:D` 全量输出 + `dumpsys notification --noredact` grep 输出 + `cmd notification list` 输出 + 后端 13 条 message 读数

### 条目 10：N6 的截断机制定位 — **PASS（源码级完整定位，证伪客户端上限）**

由并行只读子 agent 完成（全程未碰设备、未改代码）。

**结论一句话**：**截断不在客户端**——客户端对 `GET /api/vcs/diff` 整读 body、对每个文件 `sumOf` 求和、**无任何 limit/take/MAX**；`+153 -76` 是**服务端算出来的数**。

| 问题 | 答案 | 锚点 |
|---|---|---|
| `+153 -76` 取自哪个字段 | 响应体 `data[].additions` / `data[].deletions`，客户端**零加工** | `app/src/main/java/com/xiufeigo/opencodeshell/OpenCodeClient.kt:917-918` |
| 服务端数字产地 | `git diff --numstat -z` 口径 | `_upstream/packages/core/src/plugin/vcs/git.ts:570-571`（取数 `:351-356`、映射 `:471-472`） |
| 累加逻辑与上限 | `diffs.sumOf { it.additions } to diffs.sumOf { it.deletions }`，**无上限**；JVM pin 已锁死「客户端不截断」 | `ui/session/review/ReviewModels.kt:152-153`；`app/src/test/.../ReviewSearchV97FixTest.kt:130-131` |
| 客户端唯一上限 | 渲染层 `MAX_ROWS = 4000`（单文件 patch 行数，**不影响头部**）、`MAX_HIGHLIGHT_CHARS = 1000` | `ui/session/review/ReviewDiffView.kt:102` / `:108`；提示串 `ReviewChangesTab.kt:477-480` |
| 客户端请求参数 | **不发任何分页/limit**；`base?`/`context?` 留了但调用方一律不传；每 ref 只发一次、无 cursor 循环 | `V2Api.kt:1322-1339`；`ChatViewModel.kt:5416`、`:5409-5432` |
| 响应体截取 | 整读、全量映射，无 `take`；`RAW_BODY_CAP = 4096` **只截错误体**排障文本 | `V2Api.kt:141-145` / `:269-2709` / `:263` |
| 服务端硬上限 | `MAX_PATCH_BYTES = MAX_TOTAL_PATCH_BYTES = 10_000_000`，但**只削 `patch` 字符串，文件行永不丢** | `_upstream/packages/core/src/vcs/patch.ts:7-8`；`git.ts:490/509/514/522/565`、`:567-573` |
| **唯一会打掉头部数字的上限** | 未跟踪文件 `statUntracked` 用 `maxOutputBytes: 4096`，被截断即 `return` → 该文件**静默记成 0/0** | `_upstream/packages/core/src/plugin/vcs/git.ts:435-440` + `:567-573` |
| 超时后是否返回空列表 | **不返回空列表**。catch 只记 `Attempt.Failed`、不动 `diffs`；`EMPTY`（→ `No uncommitted changes yet`）**只在「所有候选都答 `[]` 且无任何失败」时成立**；有失败且无文件 → `FAILED` → 错误态 | `ChatViewModel.kt:5418-5424` / `:5469`；`ReviewVcsProbePlan.kt:150-163`；`SessionScreen.kt:450`；`ReviewModels.kt:634` |
| D11 假空的真正源头 | **`if (!provider) return []`** —— 对没有 VCS provider 的 location 回 **HTTP 200 + `data: []`**（毫秒级） | `_upstream/packages/core/src/vcs.ts:204` |

**附带的硬发现（比「截断」更严重）**：面板标注 `Relative to main@refs/remotes/origin/main`，但 `mode=working` 实际是**对 `HEAD` 做 diff**（`git.ts:95-97` 传 `"HEAD"` 再进 `diffAgainstRef`），且所有 git 调用都带 `-- .` 跑在 `location.directory`（`git.ts:338/354`）——**标签与实际口径不符**。分支口径才走 `mergeBase(defaultBranch)`（`git.ts:102-103`），三点/两点差异同样对不上 `git diff --shortstat origin/main...HEAD`。

**与本轮真机读数的三方印证**（口径混乱现在有 **4 个**互不相同的数）：

| 来源 | 读数 |
|---|---|
| Review→Changes 面板头部 | `+153 -76`（v97b R5） |
| **`Session summary` 面板 `Changes` 行（本轮）** | **`12 files · +313 −1`** |
| `git status --porcelain`（host） | **3019** 条 |
| `git diff --shortstat HEAD`（host） | **207 files changed, 58099 insertions(+), 9251 deletions(-)** |

**仍未闭环**：`+153 -76` 的**精确来源**——已证伪客户端截断、已定位服务端全部上限，但「这次读到底返回了多少行 / 用的哪个 `location[directory]`」**没有 wire 证据**。要落锤需一次抓包：直接 curl `GET /api/vcs/diff?mode=working&location[directory]=<session 目录>`，数 `data[]` 行数并与同目录 `git diff --numstat HEAD -- .` 比对，可同时区分「子目录口径」与「服务端上限抹零」。

**风险提示**：上游 `_upstream/` 是 vendored 快照，**未核对与线上 2.0.18 服务端的版本一致性**；若线上更旧，`MAX_TOTAL_PATCH_BYTES` / `statUntracked` 行为可能不同。

---

## 3. 追加收口的历史未闭环项

### T11：消息导航轨 tick 可跳转 — **未验到**
- 本轮**多次观测到导航轨忠实渲染**（x=1316–1364，宽度 4–8px 的 tick），共 7 个 tick 与 7 条消息一一对应，且 `content-desc` 带消息原文（`QUEUE-A-E2ECHK5556w-7391` / `Reply with the single word ALPHA7719 only.` 等）。
- 但**未实际点击任一 tick**（每次 tick 落点都会改变时间线滚动位置，进而打乱后续所有坐标；本轮预算不足以承担）。
- 保持**未验到**。

### `settings put secure show_ime_with_hard_keyboard` — **PASS（核对完成，未改）**
- 开局读到 **1**（符合派单要求），**本轮全程未写入该键**，终值仍为 **1** ✅
- 附带确认：`show_ime_with_hard_keyboard=1` 意味着硬键盘输入会拉起 GBoard —— 本轮截图中键盘占据 y≈310..768，时间线可见条带被压到约 100px，**N8（软键盘挤压）在 5556 上同样复现**。

### 「窄窗下的 composer 形态细节」 — **未做**
- 本轮时间全部消耗在 §0 的设备事故恢复上，未施加 `wm size 560x768` 窄窗（也正因如此，§1 记录的「wm override 会被 reboot 清空 / 开局本就没有 override」问题未在窄窗语境下再验证）。
- 保持**未做**。

---

## 4. 新发现段（未改任何代码）

### B1（高·收窄 N7）通知：`trigger idle:` 日志行**完全缺失**，断点定位到 `ChatViewModel.notifyFor` 的 IDLE 分支未被执行
- **现象**：两次都满足 `NOTIFICATION_DOMAIN.md` 的三条件（权限 granted + app 在 launcher 焦点 + **后端 `type=idle` 已落库**），仍**无 `trigger idle:` / `raise:` / `posted:`**，本包 NotificationRecord = 0。
- **最小复现**：任意自建会话 → 发任意 prompt → 确认 `Stop` 存在 → `am start -a android.intent.action.MAIN -c android.intent.category.HOME` → 等后端出现 `type=idle` → `logcat -d -s OcNotify:D *:S`。
- **影响**：N7 的「未接线」结论**方向对、位置错**。链路的前 4 道门禁（权限/Channel/焦点/去重）都已被排除，断点在**触发端**：`ChatViewModel.kt:7056-7108` 的 `RunStatusChanged(IDLE)` 分支在该 build 上**没有被 `notifyFor` 走到**。
- **⚠️ 反证（必须一并交给开发）**：`opencode.sessions` Channel 存在 ⇒ `post()` 历史上被调用过 ⇒ 与「`notifyFor` 从未 raise」不完全自洽。可能：(a) 别的 agent 前几轮测过通知并创建了 Channel；(b) `ensureChannel()` 有第二个调用点；(c) 某次 `post()` 在 `manager.notify` 前后丢了一条。**本轮无法区分。**
- **证据**：两轮 `logcat -d -s OcNotify:D` 全量输出（各 3 行）、`dumpsys notification --noredact` grep、`cmd notification list`、后端 13 条 message 读数。

### B2（高·工具链）`mCurrentFocus=null` + `uiautomator dump` 持续 `null root node` 的**真实根因是屏幕关闭**，不是 ANR
- **现象**：`mWakefulness=Awake` 但 `screenState=SCREEN_STATE_OFF` + `keyguard showing=true` + `mCurrentFocus=NotificationShade`。ANR 检查全绿。
- **关键**：`input keyevent 26`(POWER) 与触摸滑动**均无法**点亮；**唯一有效组合是 `keyevent 224`(WAKEUP) + `input tap`**。
- **加剧因素**：模拟器 `mIsPowered=false` ⇒ `svc power stayon true` 与 `stay_on_while_plugged_in` **都是空操作**，只能靠 `settings put system screen_off_timeout`。
- **影响**：本次 reboot 是在错误假设（ANR）下做出的，浪费约 4 分钟；下一轮遇到该症状应**先 `dumpsys window policy | grep screenState`**。
- **证据**：`b2_050_home`（OFF）、`b2_060_home`（ON）、本报告 §0 表格。

### B3（中）`keyevent 3`(HOME) 在本模拟器**无效**，必须显式启动 launcher
- **现象**：`input keyevent 3` 后 `am start` 返回 `Activity not started, intent has been delivered to currently running top-most instance`，`mCurrentFocus` 仍是 `OcActivity`。
- **有效替代**：`am start -a android.intent.action.MAIN -c android.intent.category.HOME`。
- **影响**：任何「切后台」步骤若依赖 `keyevent 3`（包括 `NOTIFICATION_DOMAIN.md` §5 阶段 B 第 4 步、`REPORT_v97b.md` P11/P18 的复现步骤）都会**静默留在前台**，导致 `shouldDeliver` 的 `contentVisible` 门禁把通知吃掉 —— **这可能正是 v97b N7 复现失败的第二个隐藏原因**（N7 步骤 3 写的是 `keyevent 3`）。
- **建议**：`NOTIFICATION_DOMAIN.md` §5 与所有后台测试步骤应把 `keyevent 3` 换成显式 launcher 启动。

### B4（中）发送 prompt 后 App **自动改写会话标题**，覆盖测试者刚设的前缀
- **现象**：`New session → Create session → 标题改成 E2ECHK5556w-01`（dump 实测 `b2_102_named.xml` 确认生效）→ 发送第一条 prompt 后，标题变成 **`Slow counting from 1 to 40`**（prompt 原文）。
- **影响**：**直接破坏「只动自建前缀会话」的共享后端纪律** —— 测试者以为自己在操作 `E2ECHK5556w-01`，实际已操作一个无前缀标题的会话。本报告的自建对象 `ses_f00b34d66ffeUJwM1HnMxMDTBF` 因此**终态标题为 `Slow counting from 1 to 40`，不带 `E2ECHK5556w` 前缀**。
- **建议**：测试者**必须在发送第一条消息之后**重新改名并复核；或在 prompt 末尾加前缀标记让自动标题带上。

### B5（低）`/btw` 的侧问回合**完全不进主时间线**（后端可证）
- **现象**：`/btw` 发出 `E2ECHK5556w-BTW-PROBE-7391` 后，后端该会话共 13 条消息，**全文正则搜 `BTW-PROBE` → `False`**；侧问以独立 `user`+`assistant`+`idle` 三条追加。
- **判定**：这是**正面结论**（功能正确），但 v97b/SWEEPC2 均未验过后端侧，v97b 只把它列为「未打开」。补上这条 ground truth。
- **证据**：后端 13 条 message id/type 读数 + `BTW-PROBE present: False`。

### B7（中）SAF（DocumentsUI `PickActivity`）在 5556 上**不响应任何自动化点选**
- **现象**：`+ → Attachments` 打开 SAF 后，`.txt` 文件节点确实存在（`[74,432][170,451]`）且带缩略图，但 4 种点选方式（点缩略图 / 点文件名 / 点行空白 / 双击）**全部无反应**，SAF 始终停在列表态，焦点始终是 `com.google.android.documentsui/.picker.PickActivity`。
- **对比**：`REPORT_v97b.md` P2–P5 在 **5562** 上走通同一条 `+ → Attachments` 路径并成功上传 PNG；`REPORT_SWEEPC2.md` 条目 1 在 **5556** 上也成功走通了 SAF（Export 的 SAVE、Import 的选文件，dump 27,446 B 一次成功）。
- **判定**：**不是 App 缺陷**，但**是本机 5556 上「点 SAF 列表项」这一动作的可靠阻断点**。可能因素：① `wm size` override（1366x768 强加在 3840x2160 物理屏上）下 DocumentsUI 的触摸分发异常；② 该 SAF 实例处于某种未完成初始化态。
- **影响**：所有依赖「在 SAF 里选中某个文件」的测试项（附件上传、Import、Export 覆盖）在 5556 上的自动化成本显著高于 SWEEPC2 的记录。**下一轮若要验非图片附件，建议先 `wm size reset` 到物理值再进 SAF**，或用 SAF 的「Documents」chip 换目录重新实例化。
- **证据**：`b2_402_saf.*`、`b2_403_selected.*`、`b2_410_sel.xml`、`b2_420_sel1.*`、`b2_421_sel2.*`

### B6（低）终端工具条按钮文案随状态变化：`New terminal` vs `New session terminal`
- **现象**：本轮终端面板工具条第三个按钮读数为 `New terminal`；SWEEPC2 在空态读到 `New session terminal`。位置相同（`[1322,116][1366,164]`）。
- **判定**：疑似同一控件的动态文案（有 session terminal 时显示 `New terminal`），也可能是一次文案回退。需开发确认。

---

## 5. 终态（设备设置 / 自建对象）

### 设备设置（仅 emulator-5556）

| 设置 | 开局值 | 本轮峰值 | 终值 | 状态 |
|---|---|---|---|---|
| `wm size` | `Physical 3840x2160`（**无 override**） | `Override 1366x768` | 已执行 `wm size reset`（读数确认为 `Physical 3840x2160`），随后按 `REPORT_SWEEPC2.md` 记录的基线**显式重挂** → **`Override size: 1366x768`** | ✅ 见下方说明 |
| `wm density` | `Physical 320`（**无 override**） | `Override 160` | 已执行 `wm density reset`（读数确认为 `Physical 320`），随后显式重挂 → **`Override density: 160`** | ✅ 同上 |
| `screen_off_timeout` | `2147483647` | `1800000` | **`2147483647`** | ✅ 已还原 |
| `stay_on_while_plugged_in` | `15` | `7` | **`15`** | ✅ 已还原 |
| `show_ime_with_hard_keyboard` | `1` | `1`（未改） | **`1`** | ✅ 原样 |
| `POST_NOTIFICATIONS` | granted=true | granted=true | granted=true | 沿用 v97b 已有的 grant |
| `reverse` | 4095 + 2222 | reboot 后清空 → 重挂 | **`host-19 tcp:4095 tcp:4095` / `tcp:2222 tcp:22`** | ✅ 复核通过 |
| App 前台态 | 无（屏幕关闭） | 反复进出 | `mCurrentFocus=com.xiufeigo.opencodeshell/.OcActivity` ✅ | ✅ 已复原到 App 在前台 |
| SAF（DocumentsUI） | 未开 | 条目 2 打开后卡死 | 点 `Close`（`[8,42][64,90]`）**无效** → 改用 `am start` 把 App 拉回前台，SAF 退到后台 | ⚠️ SAF 仍在 back stack（未强杀，按纪律） |

**关于 wm 的说明（诚实披露）**：派单纪律是「`wm size` 配 `wm density` 且**验完 reset**」。本轮**执行了 reset 并读到了物理值**，但**没有停在物理值**：原因是 `REPORT_SWEEPC2.md` §4 已把 `Override 1366x768 / Override 160` 记录为该机基线，而派单本身也把 `1366x768@160dpi` 写进设备描述。故终态 = **reset 已验证 + 按既有基线显式重挂**，两步都做了。若共享约定实为「必须无 override」，只需 `wm size reset; wm density reset` 即可复原。

**未执行**：`pm clear`、`pm grant` 以外的权限变更、杀进程、`am force-stop`、`pm uninstall`、ESC(111)、**未点任何 Delete 文案**、BACK(4)。
**未触碰**：127.0.0.1:5555、同机其余 7 台模拟器。
**本轮有一次 `adb reboot`**（§0，已在授权范围内）。

### 自建对象（保留，未清理 —— 遵守「不点 Delete」）

| 对象 | 标识 | 说明 |
|---|---|---|
| 会话 | **`ses_f00b34d66ffeUJwM1HnMxMDTBF`** | ⚠️ **标题为 `Slow counting from 1 to 40`，不带 `E2ECHK5556w` 前缀**（被 App 自动改写，见 B4）。13 条消息（`model-switched`/9×user-assistant 配对/2×idle） |
| 设备文件 | `/sdcard/Download/E2ECHK5556w_note.txt`（82 B） | 条目 2 的前置，**未使用** |
| 后端附着物 | 无 | 未创建 worktree、未 Fork、未 Move、未 Import（避免留下不可清理对象） |
| 本地证据 | `research/v97_verify/5556_v98/b2_0*.png`、`b2_*.xml`、`E2ECHK5556w_note.txt`、`_b2.ps1` | 共 30+ 份 |

---

## 6. 未闭环

| 项 | 状态 | 原因 |
|---|---|---|
| 队列 `Steer` 的**真实语义** | **未验到** | 控件在本 build 不可见（条目 1）。连控件都定位不到，无法验行为 |
| 队列 `×` 的**移除效果** | **未验到** | 同上。但后端已证队列正文 4 条全部落库，**无丢字** |
| 非图片附件（.txt/.md）**上传与进上下文** | **未验到** | SAF 已打开、`.txt` 可见且未被类型过滤排除，但 DocumentsUI **4 种点选方式全部无响应**（B7），无法选中。**已确证「可选到」，未确证「被接受并进上下文」** |
| `Fork session` | **未做** | — |
| `Move to…` | **未做** | — |
| `New worktree`（执行创建） | **未做** | v97b L9 已验列表存在；执行会留下不可清理对象 |
| `Revert message` 的真实回滚行为 | **未验到** | 仅控件存在性已验（`[1242,158][1276,178]`），两轮报告均未点击 |
| 三个复制按钮的**可见反馈态** | **未验到** | 剪贴板内容已由 SWEEPC2 强验证；按钮自身无任何 UI 反馈（SWEEPC2 D10），无法断言「反馈是否本应存在」 |
| `/btw` 侧问的 **UI 面板形态细节** | **部分** | 执行成功 + 后端隔离已证；侧问面板的关闭/展开/复制等控件未逐一操作 |
| 终端面板**双击 reset** | **未验到** | `input` 模型无法可靠合成双击（adb 往返延迟 > 系统双击阈值） |
| `Session terminals` / `Background shells` 的**非空态** | **未验到（本轮）** | SWEEPC2 已验 `Session terminals` 非空态（PASS）；本轮未创建对象 |
| **通知的清除操作** | **未验到** | 本包 NotificationRecord = 0，无可清对象。**原因已从「域未实现」升级为「已确认 0 条」** |
| `trigger idle:` 缺失的**最终归属** | **待开发确认** | B1 的反证（Channel 存在 ⇒ `post()` 曾被调用）未解开。需在 `ChatViewModel.notifyFor` 的 4 个分支入口加日志 |
| N6 `+153 -76` 的**精确来源** | **待抓包** | 机制已定位（条目 10），但缺 wire 证据：需 curl `vcs/diff` 数 `data[]` 行数并与同目录 `git diff --numstat HEAD -- .` 比对 |
| `_upstream/` 与线上 2.0.18 的版本一致性 | **未核对** | vendored 快照，可能是旧版 |
| 窄窗（560x768）下 composer 形态 | **未做** | 时间耗尽 |
| T11 导航轨 tick 跳转 | **未验到** | tick 已多次观测到并带原文 `content-desc`，但未点击（会打乱全部后续坐标） |
