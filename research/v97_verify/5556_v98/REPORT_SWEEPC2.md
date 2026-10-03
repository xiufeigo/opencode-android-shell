# v98 补盲扫测 C 第二轮（emulator-5556 / Medium_Desktop）

- 执行 Agent：真机扫测员（补盲扫测 C · 第二轮）
- 设备：`emulator-5556`，AVD 名 **Medium_Desktop** ✅
- 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
- 上游依据：`research/v97_verify/5556_v98/REPORT_SWEEPC.md` 第 5 节「未闭环」
- 修复依据：`research/v97_fix/REVIEW_FIX.md`（N6-a / N6-b / N5 / N4）
- 自建会话前缀：**`E2ECHK5556c2`**（原会话 `E2ECHK5556c2 RENAME` = `ses_f0325ead9ffe1ipET5d1fHerY2`；导入生成 `ses_01a0fce27967sl02VHxexG8KX9`）
- 纪律：不跑 gradle / 不装机 / 不杀进程 / 只动 5556；未点任何 Delete 文案；弹层内未按 ESC(111)；截图仅 `screencap -p` + `adb pull`；输入 ≤8 字符分段 + 空格用 `keyevent 62`；坐标全部运行期从 dump 解析；共享后端只动 `E2ECHK5556c2*`。

> **本轮方法论改进**：建立 `dumpok()` 重试助手（首跑 0 字节自动重跑，上轮 D1 的 SAF dump 不稳定问题**本轮未复现**）；剪贴板一律用「复制 → PASTE 回贴 → 读回 EditText」做**强判定**；用后端 API 做 ground truth 交叉验证。

---

## 1. 环境读数

| 项 | 读数 | 判定 |
|---|---|---|
| 设备在线 | `emulator-5556 device`（同机 5554/5558/5560/5562/5564/5584/5590 **全程未触碰**） | ✅ |
| AVD 名 | `emu avd name` = **Medium_Desktop** | ✅ 符合要求 |
| 屏幕（基线） | `Physical 3840x2160` / **`Override 1366x768`** | 记录为基线 |
| 密度（基线） | `Physical 320` / **`Override 160`** | 记录为基线 |
| APK 闸门构建 | `versionName=1.4.0` `versionCode=26` **`lastUpdateTime=2026-10-02 13:18:56`** | ✅ 与任务书 21:18 闸门一致 |
| reverse 端口 | 开局 **空**（复现上轮告警）→ 已重挂 `host-19 tcp:4095 tcp:4095` / `tcp:2222 tcp:22` | ✅ 已修复 |
| 软键盘（初值） | `show_ime_with_hard_keyboard` = **1** | ✅ 符合要求；中途置 0 绕开键盘遮挡，**终态已还原 1** |
| 后端 | `http://127.0.0.1:4095` Basic `opencode:opencode`，开局 50 sessions | ✅ |
| 启动 Activity | 真实 launcher = **`.OcActivity`**（`.MainActivity` 未导出，`am start` 报 SecurityException） | 记为 D7 |

**自建对象**（全部保留，未点任何 Delete）：会话 `E2ECHK5556c2 RENAME` + 导入副本 `ses_01a0fce27967sl02VHxexG8KX9`；session terminal `Terminal`（pid 22644）；导出/导入文件 `/sdcard/Download/E2ECHK5556c2-RENAME.json`（999 B）。

---

## 2. 逐条判定表

### 条目 1：`Import session` 成功路径 — **PASS（本轮闭环）**

按任务书预案先 Export 再 Import 回环，**成功路径完整走通**：

| 步骤 | 命令 | 读数 / 证据 |
|---|---|---|
| 1. Export | `More options → Export session → SAF → SAVE` | 文件名预填 `E2ECHK5556c2-RENAME.json`；`adb shell ls -l /sdcard/Download/` → **999 B** 真实落盘（`c2_033_safsave.xml`） |
| 2. 导出内容校验 | `adb pull` + `ConvertFrom-Json` | 顶层 `{info, messages}`；`info.id=ses_f0325ead9ffe1ipET5d1fHerY2`（= 真实会话 id）✅ |
| 3. Import | `More options → Import session → SAF → 点文件名节点` | SAF dump **一次成功**（27,446 B，见下） |
| 4. 结果 | dump + 后端 | 页面出现**第 2 个同名 tab**；后端 `/api/session` 新增 **`ses_01a0fce27967sl02VHxexG8KX9`** |

- **双证据（按预案）**：SAF dump 本轮稳定（27,446 B），**未复现上轮 D1 的 0 字节首跑** —— `dumpok()` 重试助手是主因。
- Home 页截图可见两条 `E2ECHK5556c2 RENAME`（3 minutes / 4 minutes ago）→ 导入**真实生成新 session**，非覆盖。
- 证据：`c2_032_safsave.png`、`c2_033_safsave.xml`、`c2_037_safopen.png`、`c2_038_safopen.xml`、`c2_039_imported.png`、`c2_040_afterimport.xml`、`c2_041_export.json`

### 条目 2：`Undo` 真实语义 — **FAIL（未接线）**

任务书要求区分「无可撤销」与「未接线」。本轮用**已知可撤销动作 + ground truth** 判定：

| 步骤 | 读数 |
|---|---|
| 造可撤销态 | `More options → Rename` → 改名 `E2ECHK5556c2 RENAME`；UI 标题与后端**双双确认已生效** |
| 点 `Undo`（`[1132,282][1166,294]`） | 菜单关闭，**UI 标题仍为 `E2ECHK5556c2 RENAME`**（`c2_030_undostate2.xml`） |
| **后端读回** | `GET /api/session` → `ses_f0325ead9ffe1ipET5d1fHerY2 -> 'E2ECHK5556c2 RENAME'` —— **未回滚** |
| 对照：Undo 前 | 后端消息数 = **4**（4 个 msg id） |

- **判定 FAIL**：Rename 明确产生了可撤销的状态变化，Undo 之后**服务端标题原样保留** → 排除「无可撤销」，坐实 **Undo 未接线（no-op）**。
- 复现上轮结论但**本轮给出了区分性证据**（上轮只能记「无可观测效果」）。
- 证据：`c2_028_menu3.xml`（Undo 坐标）、`c2_030_undostate2.xml`（Undo 后 UI）、后端 `/api/session` 读回

### 条目 3：`Background shells` 面板 — **PASS（修正上轮 D6）**

上轮记为「有字符串无入口」。本轮**实测推翻**：

- 入口：终端面板工具条 `content-desc="Background shells"`，bounds `[1282,116][1322,164]`（运行期解析，中心 `1302,140`）
- 打开后面板标题 `Background shells` `[876,180][1318,197]`，**空态文案 `No shell commands`** `[842,218][964,234]`，右上 `New shell` `[1318,164][1366,212]`
- 源码锚印证：`ui/terminal/TerminalPanel.kt:1236-1239`（`IconAction` → `onShowShells`，仅 `v2api != null` 时显示，`:718-719`）+ `ui/terminal/BackgroundShellPanel.kt:178`（标题）、`:272`（空态）
- 证据：`c2_043_term.xml`（入口按钮）、`c2_044_shells.png`、`c2_045_shells.xml`
- **同时** `Session terminals` 面板开箱 PASS：空态 `No session terminals` `[842,218][973,234]`（`c2_047_sessterm.xml`）

### 条目 4：`>_` 新建 session terminal 后的非空态 — **PASS（部分）**

| 步骤 | 读数 |
|---|---|
| 点 `New session terminal` `[1318,164][1366,212]` | 新增 **`Terminal 2`** tab（`[1176,116][1224,164]`），persistent terminal 真实创建 |
| 重新打开 `Session terminals` 列表 | **`No session terminals` 空态消失** → 列表转非空 ✅ |
| 列表行读数 | `xiufeigo@HomeMINISFORUM:/mnt/c/sensorsdata/main/program/e2echk5556v98` + **`Running · bash · pid 22644`** |
| 终端体 | 出现 `last login: Mon Oct 2 13:52:49 2026` |

- **非空态 PASS**：由「空态文案」翻转为「带 pid 的运行中进程行」，是列表真实装载的强读数。
- **`echo E2ECHK5556c2` 回显未验到**：终端体已获焦点（出现块状光标），但 `input text` 连续 3 次未进入 PTY（`c2_052/054/055/059*.png` 始终只有 login 行）。终端正文**不进 a11y 树**（`class="android.view.View"` 无文本节点），无法用 XML 断言，只能靠截图。→ 记为**未验到（输入未送达 PTY）**，见 D8。
- 证据：`c2_049_newterm.png`、`c2_050_newterm.xml`、`c2_057_stlist.xml`（pid 22644 读数）

### 条目 5：Home「最近关闭」空态 — **未验到（可达性受限，判定锚已升级为可执行判据）**

- **本轮未构造**：Home 实测**仍有 8 个 `Close` 按钮**（`c2_086_home_final.xml`），即 `projects.isEmpty()` 恒 false。关闭它们需动其他 agent 的项目，违反共享后端纪律 → 主动放弃构造。
- 实测 Home 无 `Recently closed` 节点（`$hf -match 'Recently closed'` → **False**），与「未渲染」一致。
- **源码锚本轮已升级**（上轮只有孤立的谓词）：
  - 谓词仍在原位未变：`ui/home/HomeProjects.kt:304-305` → `projects.isEmpty() && recentlyClosed.isNotEmpty()`
  - **仅 2 处调用**，都只守护同一个私有 composable `RecentlyClosedSection`（`HomeProjectsList.kt:576`）：`HomeProjectsList.kt:235-237`（桌面列）与 `:762-764`（移动下拉）
  - 标题串 `oc_home_recently_closed` = "Recently closed"（`strings.xml:311`）
  - **该区块无专属空态串**（列表非空才渲染）；Home 空态是 `oc_home_empty` = "Nothing here yet"（`:336`）
  - `projects.isEmpty()` **确为硬前置**，无任何其他路径在有打开项目时渲染最近关闭
- 判定：**未验到（可达性受限）**，依据为上述可执行判据而非猜测。

### 条目 6：Review 面板 resize 把手 — **PASS**

| 读数 | 拖前 | 拖后 | 松手 4s | 松手 8s |
|---|---|---|---|---|
| 把手 bounds | `[1126,114][1140,672]` | `[952,114][966,672]` | `[952,…]` | `[952,…]` |
| `Review` 标题 x | 1152 | 978 | 978 | 978 |
| `Changes` tab x | — | 1013 | — | — |

- 命令：`adb shell input swipe 1133 393 950 393 900`（把手 x=1133 取自 dump 中心）
- **可调 ✅**（面板真实变宽 174px，标题与 tab 同步左移）；**松手保持 ✅**（8s 后读数无回弹）
- 证据：`c2_063_review.xml` → `c2_064_dragged.png` → `c2_065_dragged.xml` → `c2_067_hold2.xml`

### 条目 7：消息气泡复制类按钮 — **PASS（三种按钮全部强判定）**

三种按钮**全部**通过「复制 → PASTE 回贴 → 读回 EditText 文本」真读回（强于任务书的弱判定下限）：

| 按钮 | dump bounds | 剪贴板读回内容 | 与源比对 |
|---|---|---|---|
| `Copy response` | `[4,393][52,441]` | `你好，收到消息了。我这边在 /mnt/c/.../e2echk5556v98 目录下待命，需要我做什么？` | ✅ 与气泡正文逐字一致 |
| `Copy message` | `[1314,206][1362,254]` | **`E2ECHK5556c2 hello`** | ✅ 与用户气泡原文一致 |
| `Copy code` | `[1270,287][1318,335]` | **284 字符** python 代码块（`def hello(name: str) -> str:` …） | ✅ 与渲染的 `python` 代码块一致 |

- 三条 shell 剪贴板路径（`cmd clipboard` / `dumpsys clipboard` / `service call clipboard`）沿用上轮结论仍不可用，故用回贴法。
- **注意（方法论陷阱）**：`uiautomator dump` 的 `text` 属性**可能用单引号**（`text='…'`），用 `"` 匹配会漏读 —— 本轮首次读回因此误判为「空」，改用单引号正则后立即读到 284 字符。记为 D9。
- **UI 反馈偏弱**：`Copy response` 点击后 dump **无新增任何文本节点**（无 snackbar/toast/图标态变化），仅剪贴板内容变更。→ 记为 D10。
- 源码锚：`res/values/strings.xml:533` `<string name="oc_md_copy_code">Copy code</string>` —— 该按钮**仅在渲染出 markdown 代码块时出现**（首次普通提问未出现，专门要求代码块后出现）。
- 证据：`c2_013_pasted.xml`、`c2_015_msgpasted.xml`、`c2_020_codepasted2.xml`、`c2_013_clipboard_readback.txt`、`c2_020_clipboard_code.txt`

### 条目 8：窄窗 560x768 下 Review→Changes 超时后 Refresh 恢复 — **PASS（恢复生效，但恢复结果本身是假空）**

按 `REVIEW_FIX.md` N6-a 的修法（**瞬时失败不得烧掉 fresh-graph 候选** → 4xx 才退休候选）判读，并**已验完 reset**。

| 步骤 | 读数 |
|---|---|
| 施加窄窗 | `wm size 560x768` + `wm density 160`；布局切为 tab 式 `Session/Changes/Files/Terminal` |
| 第一次进 Changes（自建项目 `e2echk5556v98`） | 显示 **`Create a Git repository`**，**不是**超时 |
| 根因核实 | `e2echk5556v98` **不是 git 仓库**（host 侧 `Test-Path …\.git` → False）→ 走的是「无仓库」分支 |
| 换到真实仓库会话 `E2ECHK5556v98_recheck2` | T+14s 出现 **`The diff request timed out or failed. Tap Refresh to try again.`** `[92,458][469,474]` |
| 后端交叉验证 | `GET /api/vcs/diff?mode=working&location[directory]=/mnt/c/…/opencode-android-shell` → **60.2s 超时未返回**（客户端 20s `REVIEW_VCS_TIMEOUT_MS` 先到）✅ 超时真实存在 |
| 点 `Refresh` `[500,235][544,250]` | T+23s：**超时横幅消失**，`main` ref chip 出现，状态 `No uncommitted changes yet` |

- **N6-a 修复在真机上生效 ✅**：瞬时超时**没有**烧掉候选，Refresh 后成功**重新探测**（`main` chip 只有在二次探测成功时才会出现）并进入稳定态。上一轮「刻意未做 wm override」的空缺本轮补上。
- **但恢复后是「假空」⚠ → D11（高）**：host 侧 ground truth `git status --porcelain` = **3,019 条**；`git diff --shortstat HEAD` = **`207 files changed, 58099 insertions(+), 9251 deletions(-)`**。App 却显示 `No uncommitted changes yet` —— 与真实状态**严重不符**。这正是 `REVIEW_FIX.md`「未跟踪」第 1 条点名要验的「超时后是否会呈现短 transcript / 假完整」风险，**本轮实测命中**。
- 证据：`c2_069_narrow.xml`、`c2_077_at14s.xml`、`c2_079_at26s.xml`、`c2_082_refresh23s.png`、`c2_083_refresh23s.xml`、后端 60.2s 超时读数、`git diff --shortstat` 读数

---

## 3. 新发现段（未改任何代码）

### D11（高）Review→Changes 超时后 Refresh **恢复成「假空」**：真实 207 文件改动被显示为「无未提交改动」
- **现象**：窄窗下点 `Refresh` 后，超时横幅消失、出现 `main` chip，页面宣称 `No uncommitted changes yet`。
- **ground truth**：`git status --porcelain` = **3019** 条；`git diff --shortstat HEAD` = **`207 files changed, 58099 insertions(+), 9251 deletions(-)`**。
- **最小复现**：窄窗 560x768 → 真实仓库会话 → `Changes` → 等超时 → 点 `Refresh` → 读正文。
- **影响**：超时后的「恢复成功」会给出**错误的干净结论**，用户据此认为工作区干净，**比持续报错更危险**。与 `REVIEW_FIX.md` N6-b「失败态必须诚实」及「未跟踪」第 1 条直接相关 —— 该文件自述「超时后是否会呈现短 transcript 当作完整」**此前未验证，本轮验证为「是，且退化为空」**。
- **证据**：`c2_082_refresh23s.png`、`c2_083_refresh23s.xml` + host `git diff --shortstat` 读数

### D10（中）三个复制按钮点击后**无任何可见 UI 反馈**
- **现象**：`Copy response` / `Copy message` / `Copy code` 点击后，dump 中**无新增文本节点**、无 toast/snackbar、无图标态切换（对比上轮侧问面板复制图标会变 ✓）。用户唯一反馈是「粘出来是对的」。
- **对比**：上轮 `c_034_pasted.png` 记录侧问面板复制图标有 ✓ 态 → 三个气泡复制按钮**缺同款反馈**，体验不一致。
- **证据**：`c2_011_dismissed.xml` vs `c2_012_copyresp.xml` 差集 = 空

### D9（中，方法论陷阱）`uiautomator dump` 的 `text` 属性**可能用单引号**，双引号正则会漏读
- **现象**：`Copy code` 回贴首读为「空」，实际 EditText 内容以 `text='…'`（**单引号**）呈现，改用 `text='([^']*)'` 后立即读到 284 字符。
- **影响**：任何用 `text="([^"]*)"` 解析 dump 的脚本会在部分节点**静默漏读**（Compose 的 `EditText` 尤其明显），极易误判为「功能没生效」。
- **建议**：解析时同时匹配 `text="…"` 与 `text='…'`。

### D8（低）persistent session terminal 获焦点后，`input text` **未送达 PTY**（3 次重试均无回显）
- **现象**：新建 `Terminal` 后终端体出现块状光标、显示 `last login` 行，但 `input text "echo"` + `keyevent 62` + `input text "E2ECHK5556c2"` + `keyevent 66` 连续 3 轮，终端正文始终无任何输入/输出。
- **对比**：上轮在**桌面停靠的临时终端**里同类输入**回显正常** → 疑似**仅 persistent session terminal** 存在输入投递问题。
- **附带**：终端正文**不进 a11y 树**（dump 无文本节点），该路径**无法用 XML 断言**，只能靠截图读数 —— 自动化成本高。
- **证据**：`c2_052_echo.png`、`c2_054_echo2.png`、`c2_055_echo3.png`、`c2_059_echoed.png`

### D7（低）真实 launcher Activity 是 `.OcActivity`，`.MainActivity` 未导出
- **现象**：`am start -n com.xiufeigo.opencodeshell/.MainActivity` → `SecurityException: … not exported from uid 10189`；`cmd package resolve-activity` → **`com.xiufeigo.opencodeshell/.OcActivity`**。
- **影响**：任何按包名猜 Activity 的自动化脚本首跑必失败；应先 `resolve-activity`。
- **证据**：命令输出（本轮首条 `am start` 报错栈）

### D6-更正（推翻上轮结论）`Background shells` 面板**入口确实存在**
- 上轮 D6 记「有字符串无入口，仅 `Session terminals` 一个列表项」→ **本轮实测推翻**：`TerminalPanel.kt:1236-1239` 的 `Background shells` 按钮在本 build 真实可见可点（`[1282,116][1322,164]`），面板与空态 `No shell commands` 均正常渲染。
- **推断**：上轮未找到入口，很可能是因为**当时未接后端 / `v2api == null`**（该按钮条件是 `v2api != null`，`TerminalPanel.kt:718-719`）。**接线状态下可达，离线状态下不可见** —— 属条件性可见，非死字符串。

---

## 4. 终态（设备设置 / 自建对象）

**设备设置（仅 emulator-5556）**

| 设置 | 初值 | 峰值 | 终值 | 状态 |
|---|---|---|---|---|
| `wm size` | `Override 1366x768` | `Override 560x768`（项 8） | **`Override size: 1366x768`** | ✅ **已还原到基线 override** |
| `wm density` | `Override 160` | `Override 160` | **`Override density: 160`** | ✅ 原样 |
| `show_ime_with_hard_keyboard` | `1` | `0`（绕开 Gboard 遮挡） | **`1`** | ✅ **已还原** |
| `reverse` | **空** | 重挂 4095/2222 | `host-19 tcp:4095 tcp:4095` / `tcp:2222 tcp:22` | ✅ 复核通过 |

- **重要修正**：`wm size reset` 会退回**物理** 3840x2160，而基线本身是 **override** 1366x768。故终态用 `wm size 1366x768` + `wm density 160` **显式还原 override**，并复核 `Override size: 1366x768` / `Override density: 160` —— 与开局逐字一致。
- 布局复核：还原后 dump 根节点 bounds = **1366x768** ✅
- 未执行：`pm clear`（任何包）、`pm grant`、杀进程、`am force-stop`、ESC(111)、**未点任何 Delete 文案**
- 未触碰：127.0.0.1:5555、同机其余 7 台模拟器
- **过程披露（诚实记录）**：条目 1 收尾时我在一条命令里误写了 `input keyevent 4 -`（BACK）。实测**未生效** —— 命令返回空且随后 dump 字节数与点击前**完全一致**（13,006 B → 13,006 B），仍停留在会话页，说明 BACK 未送达设备。发现后未再尝试，已如实记录。

**自建对象（按纪律保留，不清理以免破坏共享后端）**

- 会话：`E2ECHK5556c2 RENAME`（`ses_f0325ead9ffe1ipET5d1fHerY2`）+ 导入副本 `ses_01a0fce27967sl02VHxexG8KX9`）
- session terminal：`Terminal`（pid 22644，运行中）
- 设备文件：`/sdcard/Download/E2ECHK5556c2-RENAME.json`（999 B）
- 本地证据：`research/v97_verify/5556_v98/` 下 `c2_0*.png` / `c2_0*.xml` + `c2_013_clipboard_readback.txt` + `c2_020_clipboard_code.txt` + `c2_041_export.json` + 助手 `_h2.ps1`

---

## 5. 未闭环

| 项 | 状态 | 原因 |
|---|---|---|
| 条目 4 的 `echo E2ECHK5556c2` **回显** | **未验到** | persistent session terminal 获焦点后 `input text` 3 次未送达 PTY（D8）；终端正文不进 a11y 树，只能靠截图，无法用 XML 断言 |
| 条目 5 Home「最近关闭」空态 | **未验到（可达性受限）** | 需 `projects.isEmpty()`，即关闭**其他 agent 的 8 个项目**，违反共享后端纪律。判据已升级为可执行条件（`HomeProjects.kt:304-305` + 仅 2 处调用 + 无专属空态串） |
| 条目 8 的**宽窗**对照 | 未做 | 任务书只要求窄窗；窄窗已足够证明 N6-a 生效与 D11 假空 |
| D11 假空的**归属** | 待开发确认 | 无法从 UI 区分「diff 真返回空」与「超时后 fallback 到空 scope」；需在 `V2Api.vcsDiff` / `ReviewScopes.diffsFor` 处加日志或读数才能定位到具体行 |
| 3 个复制按钮的**反馈态** | 部分未验到 | 剪贴板内容已强验证；但按钮自身无可见反馈态（D10），无法断言「反馈是否本应存在」 |
