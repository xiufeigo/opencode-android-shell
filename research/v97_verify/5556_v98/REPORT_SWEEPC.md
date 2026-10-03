# v98 补盲扫测 C 报告（emulator-5556 / Medium_Desktop）

- 执行 Agent：真机扫测员（补盲扫测 C）
- 设备：`emulator-5556`，AVD 名 **Medium_Desktop**
- 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
- 上游依据：`research/v97_verify/5562_sweepB/REPORT_v97b.md` 第 5 节「未闭环 / 未验到」
- 会话标题前缀：`E2ECHK5556c`（实际生成 `Interpreting E2ECHK5556c hello-world string` 及其 fork）
- 纪律遵守：不跑 gradle / 不装机 / 不杀进程 / 不碰 127.0.0.1:5555；**未按 BACK(4)**；设置与表单弹层内**未按 ESC(111)**；截图仅 `screencap -p` + `adb pull`；输入分段 ≤8 字符 + 段间 sleep；**坐标全部运行期从 dump 解析**；共享后端只动 `E2ECHK5556c*` 自建会话；**全程未点任何 Delete 文案**。

---

## 1. 环境读数

| 项 | 读数 | 命令 |
|---|---|---|
| 设备在线 | `emulator-5556 device`（同机另有 5554/5558/5560/5562/5564，**全程未触碰**） | `adb devices` |
| AVD 名 | **Medium_Desktop** ✅ 符合要求 | `adb -s emulator-5556 emu avd name` |
| 屏幕 | `Physical size: 3840x2160` / `Override size: 1366x768` | `adb shell wm size` |
| 密度 | `Physical density: 320` / `Override density: 160` | `adb shell wm density` |
| 设备时间 | `Fri Oct 2 11:41:58 GMT 2026` | `adb shell date` |
| APK 闸门构建 | `versionName=1.4.0` `versionCode=26` **`lastUpdateTime=2026-10-02 11:39:32`** ✅ 与任务书 19:38 闸门一致 | `dumpsys package com.xiufeigo.opencodeshell` |
| reverse 端口 | `host-19 tcp:2222 tcp:22` / `host-19 tcp:4095 tcp:4095` ✅ | `adb reverse --list` |
| 软键盘（初始） | `show_ime_with_hard_keyboard` = **1** ✅ 已满足 | `settings get secure` |
| 默认 IME | `com.google.android.inputmethod.latin/…LatinIME`（**英文态**） | `ime list -s` |
| 系统版本 | **Android 14** | `getprop ro.build.version.release` |
| 后端 | `http://127.0.0.1:4095` Basic `opencode:opencode` 可用 | `Invoke-RestMethod` |

**自建对象**（均保留未删）：会话 `Interpreting E2ECHK5556c hello-world string` + fork `… (fork #1)`（`ses_f038f6a55ffeyDXANBHWlyCIHX`）、worktree `sunny-island`、导出文件 `/sdcard/Download/Interpreting-E2ECHK5556c-hello-world-string-fork-1.json`（2,724 B）。

---

## 2. 逐条判定表

### 条目 1：会话菜单动作 — **4/5 PASS，Undo 无可观测效果**

入口：会话页 header `More options`（content-desc 解析坐标 `(1302,134)`）。菜单实测**共 10 项**：
`Rename`(y=174) / `Export session`(203) / `Import session`(231) / `Delete`(259) / `Undo`(288) / `Fork session`(316) / `Move to…`(344) / `New worktree`(372) / `Command palette`(401) / `Toggle debug bar`(431) — 证据 `c_010_menu.xml`。

| 动作 | 判定 | 现象 / 行为 | 证据 |
|---|---|---|---|
| `Undo` | **未验到效果（no-op 观感）** | 点击后菜单关闭；**时间线、标题、状态条全无变化**，无 toast/snackbar/对话框。点击前后 dump 唯一差异是 `Working` 文本 y 位移（182→176，键盘显隐导致），**消息集合未减少** | `c_010_menu.png` → `c_011_undo.xml` / `c_011_undo.png` |
| `Fork session` | **PASS** | 打开 `Fork from message` 消息选择面板（消息行 + `Cancel`）。选中消息行后**真实新建** `Interpreting E2ECHK5556c hello-world string (fork #1)`，tab 栏出现第 3 tab | `c_012_fork.png` → `c_016_forkok.xml` |
| `Move to…` | **PASS** | 模态 `Choose a project directory`（路径 EditText + `~`/`Root`/`Parent` 面包屑 + 目录列表 + `Cancel`/`Select this directory`）。确认后页面顶部出现**两条 `Location changed` 横幅**（`opencode-android-shell` → `sunny-island`） | `c_017_move.png` → `c_018_moveok.png` → `c_019_movedone.xml` |
| `New worktree` | **PASS** | `New worktree` 面板，副标题 `from /mnt/c/sensorsdata/main/program/opencode-android-shell`，列分支（`main`/`origin`/`origin/main`/`origin/cursor/*`）+ `Back`/`Create new worktree`。选 `main` 后**真实生成 worktree `sunny-island`** + `Location changed` 横幅 | `c_020_worktree.xml` → `c_021_wtcreated.xml`；后端 `location-switched` 佐证 directory `=/home/xiufeigo/.local/share/opencode/worktree/ae5407/sunny-island` |
| `Import session` | **PASS（打开路径）** | 拉起**系统 SAF**（`com.google.android.documentsui`），非 App 内面板。选文件即导入。**成功路径未闭环**（无历史导入样本，见未闭环） | `c_022_import.png` + `c_022b_saf.xml`（`package="com.google.android.documentsui"`） |

**成功路径**：Fork = `More options → Fork session → 点消息行`；Move to = `More options → Move to… → 选目录 → Select this directory → Confirm this directory`；New worktree = `More options → New worktree → 选分支 → Create new worktree`；Import = `More options → Import session → SAF 选 JSON`。

### 条目 2：Session summary 面板 — **PASS（可打开 / 与真实会话一致 / 无报错）**

- 入口：header `Session summary`（运行期坐标 `(1218,134)`）
- 内容与真实状态**一致**：`Project → Workspace  sunny-island` ✅ 正是条目 1 `New worktree` 实际创建的 worktree；`Server → http://127.0.0.1:4095` ✅ 与后端一致；另有 `Background tasks`/`MCP`(`88`)/`Plugins`/`Skills`(`5`)/`LSP`/`Web search` 折叠组 + `Statistics` + `Reload` + `Close`
- **无报错**（无 error/toast/异常文案）
- **子缺陷**：`Statistics` 点 `Reload` 后仍**只有标题、零数值** → 新发现 D2
- 证据：`c_026_summary.png` / `c_026_summary.xml` / `c_027_stats.xml` / `c_044_sum.xml` / `c_045_bgtasks.xml`

### 条目 3：`/btw` 侧问 — **PASS（侧问面板出现，且不污染主时间线）**

- 输入 `More options` 无关，直接在 composer 敲 `/btw` → 弹出命令建议且**未出现 `No matching commands`**，证明 token 被识别
- 发送 `/btw port is 4095` 后：出现**独立侧问面板**，标题 `port is 4095`，内含模型答复（中文，反问端口指什么）+ 面板内独立 `Ask a side question` 输入框 + 面板头复制图标
- **不污染主时间线** ✅：截图 `c_031_btwsent.png` 中主时间线仍只有两条 `Location changed`，侧问内容全部在模态面板内
- 证据：`c_028_btwslash.xml`（token 识别）→ `c_030_btwok.xml`（`/btw port is 4095`）→ `c_031_btwsent.png`（侧问面板 + 主时间线干净）
- 附带：侧问面板**自带复制按钮**，被复用于条目 4 的剪贴板验证

### 条目 4：复制类按钮剪贴板校验 — **PASS（强判定：真读回剪贴板内容）**

**先记录能力探测（按任务书预案）**：

| 手段 | 结果 |
|---|---|
| `adb shell cmd clipboard` | ❌ `No shell command implementation.` |
| `adb shell dumpsys clipboard` | ❌ 无任何输出（本 build 不支持） |
| `adb shell service call clipboard 1/2/3` | ❌ 返回 Parcel 内嵌**权限拒绝串** `Package com.xiufeigo.opencodeshell does not have permission to access com.android.server.clipboard.Clipboard…`（Android 14 对 shell 读剪贴板硬拒绝） |

→ 三条 shell 路径**全部不可用**，故改用**「复制后回贴」**做真读回（强度：强，强于任务书的弱判定下限）：

1. 点侧问面板复制图标 `(891,203)` → 面板头图标**由 🗐 变为 ✓**（复制态反馈，见 `c_034_pasted.png`）
2. 聚焦面板内 `Ask a side question` 输入框 `(663,516)`，`keyevent 279`（PASTE）
3. **回显读数**：输入框真实出现被复制文本首行 —— `这个会话目前还没有任何关于端口的上下文——之前只有工作目录变更` ✅ 与 `/btw` 答复首句逐字一致

- 判定：**PASS（强）**。剪贴板内容真实可达，非仅「点了有反馈」的弱判定
- 已知不足：该复制按钮在 a11y 树中**无 `content-desc`**（图标按钮未标注），自动化只能靠坐标
- 证据：`c_032_copied.xml` / `c_033_aftercopy.xml` / `c_034_pasted.png`（✓ 图标 + 回贴文本）

### 条目 5：终端面板 — **PASS（两个面板均可打开 + 回显正常）**

| 子项 | 判定 | 读数 / 现象 | 证据 |
|---|---|---|---|
| 终端面板本体 | **PASS** | header `Open terminal` 打开右侧停靠面板，`Terminal 1` 标签，提示符 `[xiufeigo@HomeMINISFORUM sunny-island]$` ✅ **确认 worktree 生效** | `c_036_term.png` / `c_036_term.xml` |
| `Session terminals` | **PASS（含空态）** | 面板内 `>_` 按钮打开 **`Session terminals`** 列表，空态文案 **`No session terminals`** + `Shut down persistent terminals` + `+`（New session terminal） | `c_037_termtabs.png` / `c_037_termtabs.xml` |
| `Background shells` | **部分 PASS** | 该字符串在源码存在（`strings.xml:476 oc_terminal_shells_title` = `Background shells`，空态 `No shell commands`），但**桌面停靠的 `>_` 列表里只列出 `Session terminals` 一个入口**；等价空态在 `Session summary → Background tasks` 中以 **`No background tasks`** 呈现。→ 见新发现 D6 | `c_038_termmore.xml`（终端溢出仅 `Rename`/`Close terminal`）、`c_045_bgtasks.xml`（`No background tasks`）、`app/src/main/res/values/strings.xml:476-489` |
| 终端输入回显 | **PASS** | 键入字符**全部正确回显**并换出新提示符。首次 `echo` 因空格被吞变成 `echoE2ECHK5556C-TERM-OK` → `bash: command not found`（属 D4 输入缺陷，**非回显缺陷**） | `c_041_echo.png`、`c_042_echo_clean.png` |

### 条目 6：拖拽 resize — **PASS（可调 + 松手保持）**

- 拖拽把手 a11y 锚：`Drag to resize the terminal panel, double-tap to reset`，初始 `bounds=[820,114][828,672]`
- 拖拽前：把手 x=**824**，`Terminal 1` 标签 x=**868**
- 执行 `adb shell input swipe 824 393 1000 393 900` → 拖拽后：把手 x=**988**（右移 164px），`Terminal 1` 标签 x=**1036**（同步右移，**面板真实变宽**）
- **松手保持** ✅：松手 3s 后再 dump，读数仍为 988/1036，未回弹
- 证据：`c_039_bgshells.xml`（拖前）→ `c_040_dragged.xml`（拖后）
- 附带：`strings.xml:577` 另有 `oc_review_resize_handle`（Review 面板同款把手），本轮未单独验证 Review 面板把手

### 条目 7：导出 vs 后端 transcript — **PASS（条数与 id 逐个一致）**

- App 侧：`More options → Export session` → SAF 保存（文件名预填 `Interpreting-E2ECHK5556c-hello-world-string-fork-1.json`）
- 后端侧：`GET http://127.0.0.1:4095/api/session/ses_f038f6a55ffeyDXANBHWlyCIHX/message`

| 源 | 条数 | id |
|---|---|---|
| App 导出 JSON（`messages[]`） | **5** | `msg_0fc7095ab001fCFGDqJ5nTzXnH_1`、`msg_0fc7179b20025uQo9plVjHVv96`、`msg_0fc7179c0001vwLeMas5tmMRF3`、`msg_0fc71ffa8002WbXV6BugLeLert`、`msg_0fc71ffb2001onXFThM6yAugNU` |
| 后端 `/api/session/<id>/message` | **5** | **同 5 个，id 逐个一致** |

- 导出顶层结构 `{ info, messages }`；`info.id` 与 `info.title` 均与真实会话一致
- 后端事件类型佐证条目 1 真实生效：`model-switched`×1、`location-switched`×2、`idle`×2
- 导出**无任何可见成功提示** → **复现并确认上轮 N10f**
- 证据：`c_024_export.png` / `c_025_saved.xml` / `export_fork1.json`

### 条目 8：Home「最近关闭」空态 — **未验到（可达性受限）**

- 源码锚（任务书指定）：`app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjects.kt:304-305`

```kotlin
fun showsClosed(projects: List<Project>, recentlyClosed: List<Project>): Boolean =
    projects.isEmpty() && recentlyClosed.isNotEmpty()
```

- **本轮补充的精确结论**（比空锚更进一步）：该段上方 `HomeProjects.kt:296-302` 注释明确 —— 上游只在**唯一一个位置**（`HomeProjectEmpty`，即 Projects 列**一个打开项目都不剩**时的兜底）渲染 recently-closed；行的 `✕ Close` 会把该行移出列表，「stack 只在列表为空时作为返回路径重新出现」。故渲染条件是 **`projects.isEmpty()` 与 `recentlyClosed.isNotEmpty()` 同时成立**。
- **为何本机无法构造**：本机 Home 仍有**其他 agent 的 7+ 个项目**处于打开态（实测可见 `lucky-planet`、`v36projC`、`E2ECHK_renamed` 等，`c_047_home3.xml`），`projects.isEmpty()` 恒为 false。要触发只能把**别人**的项目全部 Close —— 直接违反「不破坏共享后端」纪律，故**主动放弃构造**。
- 实测 Home 无 `closed`/`recent` 任何节点（`c_047_home3.xml` 匹配为空），与「未渲染」一致。
- 判定：**未验到（可达性受限）**，判定依据为上述源码条件，非猜测。

---

## 3. 新发现段（现象 + 最小复现 + 证据，**未改任何代码**）

### D1（中）`Import session` 拉起系统 SAF，且 `uiautomator` dump 结果**不稳定（首跑 0 字节）**
- **现象**：点 `Import session` 后窗口切到 `com.google.android.documentsui`。**首次** `uiautomator dump` 返回 **0 字节空文件**（`c_022_import.xml` 为空，解析脚本报空），**重跑一次**才拿到 59,785 B 完整层级。
- **最小复现**：`Import session` → 立即 dump → 观察 0 字节 → 重跑 → 正常
- **影响**：SAF 属系统包，App 侧 XML 坐标自动化在此**取不到窗口**；`Import` 成功路径无法在无人工介入下闭环。
- **证据**：`c_022_import.png`（系统 Recent 窗口）、`c_022b_saf.xml`（`package="com.google.android.documentsui"`，含 `Recent`/`Documents` 节点）

### D2（中）`Session summary → Statistics` 点 `Reload` 后**恒为空**
- **现象**：`Statistics` 行初始即显示 `·Statistics`（前缀圆点 + 标题，**无任何数值**）。点 `Reload` 后重新 dump，**仍只有标题**，tokens / context / cost / message 数一项都不出。
- **最小复现**：会话页 → `Session summary` → 点 `Reload` → dump
- **证据**：`c_026_summary.png`（`·Statistics` + `Reload`）、`c_027_stats.xml`（Reload 后仅剩 `Statistics` 节点）
- **同源**：与上轮 N10h 记录的 Context tab 统计入口缺失同族。

### D3（低）软键盘遮挡 `Fork from message` 面板，误触**直接污染 composer**
- **现象**：IME 开启时该面板仅露 `y≈370..467`，与 Gboard 按键区 `y≈340..768` **完全重叠**。按 dump 解析出的消息行坐标 `(651,409)` 点击，实际命中 Gboard 的 **`y` 键**，composer 被写入杂散字符 `y`。
- **规避**：`settings put secure show_ime_with_hard_keyboard 0` 后面板完整可点。
- **证据**：`c_012_fork.png`（面板被压扁）vs `c_013_forked.xml`（`EditText text="y"`）vs `c_015_kboff.png`（面板完整）
- **已清理**：该杂散 `y` 用 `keyevent 67` 清除。

### D4（低）`input text` **静默吞空格**，终端与 composer 同时中招
- **现象**：`input text "E2ECHK5556c hello"` → 落字 `E2ECHK5556chello`（空格消失）。改用 `input keyevent 62` 后正常。
- **终端同源**：`echo` + 空格 + `E2ECHK...` → 实际成为单 token `echoE2ECHK5556C-TERM-OK` → `bash: command not found`（**回显本身正确**，是输入层丢空格）。
- **附带**：段内自带空格（`" world"`）同样被吞，空格**必须单独用 `keyevent 62` 补**。
- **证据**：`c_005_typed.xml`（`E2ECHK5556chello`）vs `c_006_retyped.xml`（`E2ECHK5556c helloworld`）、`c_029_btwfull.xml`（`/btw Whatismyport4095d`，且 `?` 落成 `d`）、`c_041_echo.png`
- **说明**：本轮 Gboard 为**拉丁英文态**（`default_input_method=…LatinIME`），**未**触发中文态吞字符，故**未执行** `pm clear com.google.android.inputmethod.latin` 归一化。

### D5（低，校验陷阱）后端 `/api/session/<id>/message` 返回 **`{"data":[...]}` 包裹**，按裸数组解析会得到**假读数 count=1**
- **现象**：首次解析得 `count = 1`，与导出 JSON 的 5 条严重不符，一度误判为导出丢消息。改用 `raw length = 1463` 逐字节核对后确认是**包裹层**问题，正确读数为 **5**。
- **最小复现**：`Invoke-RestMethod …/message` 后直接 `.Count` → `1`；应取 `.data.Count` → `5`
- **影响**：任何拿该接口做 transcript 校验的脚本都会**系统性少报**，建议后续扫测一律先解包 `data`。
- **证据**：本轮两轮读数对照（`count=1` 假读数 → `backend data count = 5` 真读数）

### D6（低）`Background shells` 有字符串、有空态文案，但桌面 `>_` 列表**只有 `Session terminals` 一个入口**
- **现象**：`strings.xml:476-477` 定义了 `Background shells` / `No shell commands`，`V2Capability.kt:352` 也把 `shell.list` 标为「Background shells」；但桌面停靠终端面板 `>_` 展开后**只有 `Session terminals`**（`c_037_termtabs.xml`），终端溢出菜单也只有 `Rename` / `Close terminal`（`c_038_termmore.xml`）。等价空态只能在 `Session summary → Background tasks` 以 `No background tasks` 见到。
- **最小复现**：打开终端面板 → 点 `>_` → 观察仅 1 个列表项
- **判定**：**不判 FAIL**（功能面存在且空态可见），但**入口命名与可达性不一致**，记为待确认项。

---

## 4. 终态（设备设置 / 自建对象清理）

**设备设置（仅 emulator-5556）**

| 设置 | 初值 | 峰值 | 终值 | 状态 |
|---|---|---|---|---|
| `show_ime_with_hard_keyboard` | `1` | `0`（绕开 D3 键盘遮挡） | **`1`** | ✅ **已还原** |
| `wm size` | 1366x768 | 未改 | `Override size: 1366x768` | ✅ 原样 |
| `wm density` | 160 | 未改 | `Override density: 160` | ✅ 原样 |

- 未执行：`pm clear`（任何包）、`pm grant`、杀进程、`am force-stop`、BACK(4)、ESC(111)
- 未触碰：127.0.0.1:5555、同机其余 5 台模拟器（5554/5558/5560/5562/5564）
- **全程未点任何 Delete 文案**；SAF 一律用**自身返回箭头 / 自身 Cancel** 退出（`c_023_backfromsaf.xml`）

**自建对象（按纪律保留，不清理以免破坏共享后端）**

- 会话：`Interpreting E2ECHK5556c hello-world string` + `… (fork #1)`（`ses_f038f6a55ffeyDXANBHWlyCIHX`）
- worktree：`sunny-island`（`/home/xiufeigo/.local/share/opencode/worktree/ae5407/sunny-island`）
- 设备文件：`/sdcard/Download/Interpreting-E2ECHK5556c-hello-world-string-fork-1.json`（2,724 B，终态仍在）
- 本地证据：`research/v97_verify/5556_v98/` 下 47 组 `c_0*.png` / `c_0*.xml` + `export_fork1.json` + `_tools/`（`dump.ps1`/`shot.ps1`/`find.ps1`/`tap.ps1`）

---

## 5. 未闭环

| 项 | 原因 |
|---|---|
| `Import session` **成功路径**未闭环 | 拉起 SAF 后**无既有导入样本**；造样本需先 Export 再 Import，但本机 Downloads 仅有本轮刚导出的文件，回到 Import 时 SAF 首次 dump 又是 0 字节（D1），无稳定坐标选中目标文件。为不破坏共享后端未强行构造 |
| `Undo` 真实语义 | 仅观测到「无可观测效果」，**未能区分**是「该会话无可撤销操作」还是「Undo 未接线」。构造可撤销态需 `Revert message` 等高风险操作，超出本轮授权 |
| `Background shells` 面板本体 | 只见到 `Session summary → Background tasks` 的等价空态与 `strings.xml` 定义，**未验到**标题为 `Background shells` 的面板实例（D6） |
| Home「最近关闭」空态渲染 | **未验到（可达性受限）**：需 `projects.isEmpty()` 成立，即关闭**其他 agent 的全部项目**，违反共享后端纪律。判定锚 `ui/home/HomeProjects.kt:304-305`（含本轮补充的精确渲染条件） |
| `Revert message` / `Copy message` / `Copy response` / `Copy code` | 本轮仅验到**侧问面板的复制按钮**（条目 4 强 PASS）；消息气泡上的同类复制按钮因时间预算未逐个验 |
| Review 面板 resize 把手 | `strings.xml:577` 存在 `oc_review_resize_handle`，本轮只验了终端面板把手（条目 6） |
| `>`_` 面板内新建 session terminal 后的非空态 | 只验到空态 `No session terminals`，未新建 persistent terminal 观察非空列表 |
