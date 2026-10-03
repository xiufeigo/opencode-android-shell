# v97b 真机扫测报告 B — 全功能广度扫测（emulator-5562 = Small_Desktop）

- 测试员：session-e8091491-5562b（sweepB 第二轮）
- 日期：2026-10-02（host 18:03 / 设备 10:03）
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 纪律：不跑 gradle / 不装机 / 不杀进程 / 不碰 127.0.0.1:5555；不用 keyevent 4(BACK)；弹层内不用 keyevent 111(ESC)；截图只用 `screencap -p` + `pull`；空格用 keyevent 62；只动 `E2ECHK5562` 前缀会话。

> **状态：DONE**（78 条断言全部有结论；3 条明确标"未验到"，原因逐条写明；**未改任何代码**。）

### 关键结论一句话
新 APK（18:01 构建）上**会话生命周期 / 导出 / 删除 / 改名 / 附件上传 / 终端 / 时间线渲染 / Review 三页**基本全绿，但**时间线搜索整条链路是坏的**（首次查询之后永不重算、header 标题编辑态无取消路径、并会把搜索框内容当会话标题提交落库造成数据损坏），**通知域整体未实现**，另外 Review 的 Files 过滤器清空不恢复、Changes 的 diff 统计被截断且窄窗下超时不可恢复。


## 1. 环境读数

| 项 | 读数 | 证据 |
|---|---|---|
| 设备 serial | `emulator-5562`（`adb devices -l`：`product:sdk_gpc_x86_64`） | 本轮命令 |
| AVD 名称 | `adb -s emulator-5562 emu avd name` → **Small_Desktop** ✔ 与派单一致 | 本轮命令 |
| `wm size` | `Physical size: 1366x768`（无 override） | 本轮命令 |
| `wm density` | `Physical density: 160`（无 override） | 本轮命令 |
| SDK | `getprop ro.build.version.sdk` → **34** | 本轮命令 |
| APK | `versionName=1.4.0` `versionCode=26` `minSdk=26` `targetSdk=35` | 本轮命令 |
| `lastUpdateTime` | `2026-10-02 10:01:36`（设备时）= host 18:01:36 → **新 APK 已复核** ✔ | 本轮命令 |
| launcher | `com.xiufeigo.opencodeshell/.OcActivity` | 派单 |
| reverse | `host-19 tcp:4095 tcp:4095`、`host-19 tcp:2222 tcp:22` ✔ 两隧道均在 | 本轮命令 |
| 后端健康 | `GET /api/info` → 200 `{"version":"2.0.18","pid":260,"urls":["http://127.0.0.1:4095"],"paths":{"tmp":"/tmp/opencode"}}` ✔ | 本轮命令 |

## 2. 上轮（session-7fd8879d，REPORT.md）盘点

上轮报告写盘中断（结果总表 8 行全为「进行中/待测/累积中」），仅 22 张截图 + 14 个 uiautomator dump 落盘。
从证据文件名可反推实际完成度：

| 上轮证据 | 推断已做 | 本轮处置 |
|---|---|---|
| 000–004 launch/awake/focus/home | 启动 + 唤醒 + 回桌面 | 已完成（抽验 1 条） |
| 005–009 newsession/dirpicker/folder/closed/foldericon | 新建会话 + 目录选择器（文件夹图标/关闭） | 已完成（抽验 1 条） |
| 011–016 session/now/renametap/menu/rename/typed | 进入会话 + 重命名弹层 + 输入 | 已完成（抽验 1 条） |
| 018 empty_send | 空发送 | 已完成（抽验 1 条） |
| 019–022 at/atfilter/token | `@` 提及 + 过滤 + token 原子删除 | 部分完成（过滤+token 已验；**多文件过滤/上传未验**） |
| ui_023–025 bs1/bs2/bs3 | 连按 3 次退格 token | 已完成 |
| — | **中断**：流式收流、消息渲染、`/` 命令、附件、队列、Stop、时间线、导航轨、搜索、上下文浮层、Review 面板、终端、通知、会话删除/导出/切换 | **本轮补做** |

### 2.1 上轮已完成项的抽验（新 APK 无回归）

| 抽验项 | 结果 | 证据 |
|---|---|---|
| 进入 `E2ECHK5562` 会话（上轮 011–012） | PASS | `v97b_002_session01.png` — tab 标题正确、空态 `Build anything` 正常 |
| 空发送禁用 Send（上轮 018） | PASS | `v97b_027_scrollup.xml` — composer 仅含空白时 `C[Send] en=false` |
| `@` 提及 + 过滤（上轮 019–021） | PASS | `v97b_088_at.png` — `@REPORT` 命中 1 个 Skill(`Report`) + 多个 `File` 绝对路径行 |
| `@` token 整块选中（上轮 022 / ui_023–025） | PASS | `v97b_089_at_token.png` — 选中后 composer 变成单个 token `@/mnt/c/…/5558/REPORT.md` |

> 上轮的**目录选择器**（005–009）本轮通过 L8/L9 以"新建会话的运行位置选择器"路径覆盖（worktree 列表可见），未逐帧复现上轮的 006/007/008 截图。

### 2.2 本轮统计

| 域 | 断言数 | PASS | FAIL | 未验到 | 存疑/观察 |
|---|---|---|---|---|---|
| 3.1 Composer | 11 | 6 | 4 | 1 | 0 |
| 3.2 时间线渲染 | 12 | 10 | 1 | 1 | 0 |
| 3.3 会话/后端一致性 | 4 | 4 | 0 | 0 | 0 |
| 3.4 Review/上下文 | 10 | 6 | 1 | 0 | 3（存疑/观察） |
| 3.5 时间线搜索 | 10 | 1 | 7 | 0 | 2（观察） |
| 3.6 会话生命周期 | 12 | 11 | 1 | 0 | 0 |
| 3.7 附件/Stop/窄窗/通知 | 19 | 13 | 3 | 1 | 2（观察） |
| **合计** | **78** | **51** | **17** | **3** | **7** |

按缺陷条目（合并同类）：**高危 4（N1/N2/N3/N7 通知）、中危 6（N4/N5/N6/N8/N9 + P15）、低危 12（N10a–N10l + 发送气泡不显示附件）**。


## 3. 逐条判定表

（边做边追加。命令缩写：`S(n)`=`screencap -p /sdcard/<n>.png` + `pull`；`U(n)`=`uiautomator dump` + `pull`；`T(x)`=`_tools/input.ps1 -Text x`（≤8 字符分段，空格=keyevent 62）。）

### 3.1 Composer

| # | 断言 | 判定 | 命令 | 证据 | 读数 |
|---|---|---|---|---|---|
| C1 | composer 接收长文本、无丢字 | PASS | `T("Reply with a markdown heading, ... paste the output.")` | `v97b_017_prompt.png/.xml` | 135 字符原文完整出现在 `B[[28,262]…]`，Send `en=true` |
| C2 | 空内容时 Send 禁用 | PASS | 同上（清空后 dump） | `v97b_027_scrollup.xml` | composer 仅含 TAB 时 `C[Send] … en=false` |
| C3 | **Enter = 发送** | PASS | `input keyevent 66` | `v97b_018_enter.png/.xml` | 消息上屏、composer 清空、`C[Send]`→`C[Stop]` |
| C4 | `/` 命令清单弹出且可滚到底 | PASS | `input text "/"` + 反复 `swipe` | `v97b_003_slash.*` / `004`~`0140_s.xml` | 14 条：`/model /agent /init /review /btw /new /undo /redo /compact /fork /export /open /terminal /mcp`；第 11 次 swipe 后列表不再变化（到底） |
| C5 | `/` 过滤 | PASS | `input text "co"` | `v97b_015_slashfilter.png/.xml` | 仅剩 `/compact`；无命中时显示 `No matching commands` |
| C6 | `/` 查询 token **非**原子删除 | **FAIL(低)** | `input keyevent 67` ×1 于 `/co` | `v97b_016_slashdel1.xml` / `v97b_016b_probe.png` | 1 次 DEL 只去掉 `o` → `/c`，与 `@` 提及 token 的整块删除行为不一致；结果落入 `No matching commands` |
| C7 | 发送中再发 → 入队 | PASS | 发送后立刻 `T("Queued second message while streaming.")` + `keyevent 66` | `v97b_019_queue.png/.xml` | 出现 `T[Queued]` 徽标 + `Steer` + `×`；后端确认正文送达（见 3.5） |
| C8 | 队列消息可移除 | 未验到 | — | — | 界面上有 `×`，本轮未点（避免中断流） |
| C9 | **keyevent 61(TAB) 会把字面 TAB 写进 composer** | **FAIL** | `input keyevent 61` | `v97b_027_scrollup.xml` | `T[&#9;] B[[28,618][1338,666]]`；IME 未收起。派单指定的"收键盘用 TAB"在本 App 不成立 |
| C10 | 软键盘弹起时时间线被压到 ~45–90px | **FAIL(中)** | 见 C3/C7 期间截图 | `v97b_023_stream.png` | 768px 高窗口 + GBoard 占 y=340..768，可见时间线仅 y≈145..190；用户消息与流式输出全部被挤走 |
| C11 | 主视图 ESC 可收键盘 | PASS | `input keyevent 111`（**主视图、无弹层**） | `v97b_026_esc.png` | 键盘收起，时间线恢复 y=134..712 |

### 3.2 时间线渲染

| # | 断言 | 判定 | 命令 | 证据 | 读数 |
|---|---|---|---|---|---|
| T1 | 用户气泡右对齐 + 时间戳 + 动作行 | PASS | 滚动到顶 | `v97b_027_scrollup.png` | 气泡 `B[[400,186][1300,206]]`，`10:07 AM` + `C[Revert message]` + `C[Copy message]` |
| T2 | Markdown H1 | PASS | 同上 | `v97b_027_scrollup.png` | `T[OpenCode Android Shell] B[[16,378][1312,406]]` 大号标题 |
| T3 | 无序列表 | PASS | 同上 | 同上 | 3 个 `T[•]` 项目 |
| T4 | 行内 code 带底色 | PASS | 同上 | 同上 | `/mnt/c/sensorsdata/…`、`jira-SCB-Collector` 等 |
| T5 | 代码块（语言标签 + 展开 + 复制） | PASS | 同上 | `v97b_027_scrollup.xml` | `T[python] B[[28,547][1270,560]]` + `C[Copy code] B[[1270,529][1318,577]]` + 中部 `⌄` 展开钮 |
| T6 | 工具卡折叠态 | PASS | `input tap 664 340` | `v97b_027_scrollup.png` | `C[Toggle tool details] B[[16,326][1312,354]]`，`Used 1 Shell` + 右侧 `›` |
| T7 | 工具卡展开（Thought / Shell 两行） | PASS | `input tap 664 340` | `v97b_029_toolcard_expand.png` | `⌄ Used 1 Shell` → `Thought ›` + `>_ Shell pwd ›` |
| T8 | 工具详情展开（命令 + stdout + 复制） | PASS | `input tap 664 337` | `v97b_030_shelldetail.png` | 展开出 `pwd` + `/mnt/c/sensorsdata/main/program/opencode-android-shell` + 复制钮 |
| T9 | 工具卡**箭头不在点击区内** | **FAIL(低)** | `input tap 1332 340` | `v97b_028_toolcard_open.png` | 可见箭头画在 x≈1332，但 a11y 节点 `B[[16,326][1312,354]]` 止于 x=1312；点箭头无反应，点行主体才有反应 |
| T10 | 消息导航轨 | PASS | 滚动后 dump | `v97b_027_scrollup.xml` | 右轨 2 个 tick：`C[Reply with a markdown heading, a bullet list of …] B[[1316,343][1364,355]]`、`C[Queued second message while streaming.] B[[1316,355][1364,403]]` |
| T11 | 导航轨 tick 可跳转 | 未验到 | — | — | 轨已渲染、tick 有 `click=true`，本轮未逐个点（避免打乱后续坐标） |
| T12 | "Jump to latest" 按钮 | PASS | 上滚后出现 | `v97b_027_scrollup.xml` | `C[Jump to latest] B[[659,546][707,594]]` |

### 3.3 会话 / 后端一致性

| # | 断言 | 判定 | 命令 | 证据 | 读数 |
|---|---|---|---|---|---|
| S1 | 进入 `E2ECHK5562-01` 会话 | PASS（**上轮抽验**） | `input tap 575 338` | `v97b_002_session01.png` | tab 标题 `E2ECHK5562-01`，时间线 `Build anything` 空态 |
| S2 | 消息上后端 | PASS | `GET /api/session/ses_f040bf529ffeTya7uOPV8Cjuaa/message?order=asc&limit=50` | `api_msgs_e2echk5562-01.json` | 7 行：`model-switched` / `user` / `assistant`×3 / `idle`；user `text` 与 UI 逐字一致 |
| S3 | 工具调用落库 | PASS | 同上 | 同上 | `content[1] = {type:tool,name:shell,state.status:completed,input:{command:"pwd"},…exit:0}` |
| S4 | 队列消息正文送达 | PASS | 同上 | 同上 | 第 5 行 `type=user, text="Queued second message while streaming."` → **队列正文未丢**；模型回复"arrived empty"是模型自身误读，非 App 缺陷 |

### 3.4 Review 面板 / 上下文

| # | 断言 | 判定 | 命令 | 证据 | 读数 |
|---|---|---|---|---|---|
| R1 | `◎`(Context usage) 打开侧栏并定位 Context 页 | PASS | `input tap 1258 133` | `v97b_031_context.png/.xml` | 侧栏 `Review`/`main`/`Refresh`/`×`；`Messages (all frames)=7`（与后端 7 条一致）、`Context Limit 524,288`、`Total Tokens 8,806`、`Usage 2%`、`Input 187 / Output 38 / Reasoning 0` |
| R2 | Review 面板 tab 数 | 观察 | 同上 | 同上 | 实为 **3 个 tab**：`Changes / Context / Files`（派单写"Changes/Files 两个 tab"，与实现不符） |
| R3 | `◎` 与 `▣` 是同一面板 | 观察 | `input tap 1258 133` vs `input tap 1342 138` | `v97b_031` vs `v97b_038` | 两个入口开同一个侧栏，`◎` 只是预选 Context 页 |
| R4 | Changes tab 渲染工作区变更 | PASS | `input tap 1186 170` | `v97b_032_changes.png` | `Git changes`/`Branch changes` 切换、`Relative to main@refs/remotes/origi…`、`Show all lines`/`Hide non-diff lines`、`+153 -76`、D/A/M 徽章列表可滚动（12 次 swipe 从 5554 目录滚到 5562 自己的文件） |
| R5 | Changes 头部统计与 git 一致 | **存疑** | host `git diff --shortstat HEAD` | 见下 | 面板 `+153 -76`；`git diff --shortstat HEAD` = `207 files changed, 57407 insertions(+), 9201 deletions(-)`；`git diff --shortstat origin/main...HEAD` = `788 files, +83376 −5204`。三者互不相同，面板口径（疑似有上限截断）**未能确认** |
| R6 | Files tab 文件树与真实目录一致 | PASS | `input tap 1320 194` | `v97b_034_files.png` | 根级 `.git .github .gradle .idea .kotlin _upstream app build frontend gradle research scripts v36projB v36projC 2` — 与 `Get-ChildItem` 实测一致 |
| R7 | Files 树可展开 | PASS | `input tap 1197 473`(app) | `v97b_041_treeexpand.png` | `app` → `build`(dir) `src`(dir) `build.gradle.kts` `proguard-rules.pro` |
| R8 | Files 过滤器过滤 | PASS（范围窄） | Filter files → `gradle` | `v97b_035_filesfilter.png` | 只剩 `.gradle` / `gradle` 两行；`gradle.properties`、`build.gradle.kts` **未命中** → 只按当前层目录名匹配，不递归搜文件名 |
| R9 | **清空 Files 过滤器不恢复全量列表** | **FAIL(中)** | 退格清空 → Refresh → 切 tab | `v97b_036` / `037` / `038` / `040` | 清空后仍只剩 2 行；`Refresh` 弹 toast `Directory state rebuilt — in-flight permissions/forms may have been cancelled.` 但列表**未恢复**；只有**切走 tab 再切回**（或关闭重开侧栏）才恢复 |
| R10 | Refresh 行为 | PASS | `input tap 1296 134` | `v97b_037_refresh.png` | toast 如上，属预期语义 |

### 3.5 时间线搜索

| # | 断言 | 判定 | 命令 | 证据 | 读数 |
|---|---|---|---|---|---|
| Q1 | 首次搜索可用 | PASS | `input tap 902 133` → `pathlib` | `v97b_042_search.png` | 时间线收敛为唯一命中 `from pathlib import Path` 代码块 |
| Q2 | **改查询词后结果不重算（结果冻结在首次查询）** | **FAIL(高)** | `pathlib` → 退格×7 → `the` | `v97b_043_search_multi.png` | `the` 应命中"the shell command"/"The queued message…"，实际仍显示 `from pathlib import Path` |
| Q3 | 清空后再输新词仍是旧结果 | **FAIL(高)** | 退格×4 → `OpenCode`；再退格×9 → `Queued` | `v97b_045` / `v97b_048_search_queued.png` | `OpenCode` 应命中 H1 `OpenCode Android Shell`；`Queued` 应命中用户气泡 `Queued second message while streaming.`；两次都只显示 `from pathlib import Path` |
| Q4 | **ESC 只清过滤、不关搜索框** | **FAIL(中)** | `input keyevent 111`（主视图） | `v97b_046_searchclose.png/.xml` | 过滤消失（全量回来），但输入框仍是编辑态、仍留 `OpenCode`；header 的 `C[Search session]` 节点消失 |
| Q5 | 清空输入框不退出搜索模式 | **FAIL(中)** | 退格×9 | `v97b_047_searchemptied.xml` | `C[Search session]` / `C[Ask a side question]` 仍缺失 |
| Q6 | 搜索态跨导航保持（无法自愈） | **FAIL(高)** | 回 Home → 重开会话 | `v97b_054_open_renamed.xml` | 重新进入会话后 header 仍无 `Search session`；点标题不弹会话菜单 |
| Q7 | 附带效应：**搜索框内容被当成会话标题提交** | **FAIL(高，数据损坏)** | 在卡死输入框里输入 `Queued` → 回 Home | `v97b_053_homesearch_cleared.png` + 后端 | 后端 `GET /api/session` → `ses_f040bf529ffeTya7uOPV8Cjuaa` 的 `title` 从 `E2ECHK5562-01` 变成 **`Queued`** |
| Q8 | 标题栏 inline 编辑 + Enter 可改名并同步 tab | PASS | 输入框内 `E2ECHK5562-01b` + `keyevent 66` | `v97b_059_rename_done.xml` + 后端 | tab `T[E2ECHK5562-01b] B[[68,46][166,62]]`、header 恢复 `C[Search session]`/`C[Ask a side question]`、后端 `title='E2ECHK5562-01b'` → **tab 与后端双同步** |
| Q9 | More-options → Rename 在卡死态下是 no-op | **FAIL(中)** | `tap 1302 134` → `tap 1157 174` | `v97b_057_moreoptions.xml` / `v97b_058_rename_dlg.png` | 菜单项齐全（见 3.6），点 Rename 菜单关闭、**无任何弹层**、header 仍是搜索编辑态 |
| Q10 | 恢复途径 | 观察 | 见 Q8 | `v97b_059_rename_done.xml` | 只能"在卡死输入框里提交一个标题"来恢复；单独 ESC / 刷新 / 回 Home 均无效 |

### 3.6 会话生命周期

| # | 断言 | 判定 | 命令 | 证据 | 读数 |
|---|---|---|---|---|---|
| L1 | 启动落回 Home 会话列表 | PASS | `am start -n …/.OcActivity` | `v97b_001_launch.png` | `Search sessions` + Today/Older 分组 + 左侧 Projects 栏 |
| L2 | 进入会话 | PASS | `input tap 575 338` | `v97b_002_session01.png` | tab `E2ECHK5562-01`、空态 `Build anything` |
| L3 | 会话更多菜单项齐全 | PASS | `input tap 1302 134` | `v97b_057_moreoptions.xml` | `Rename / Export session / Import session / Delete / Undo / Fork session / Move to… / New worktree / Command palette / Toggle debug bar`（10 项） |
| L4 | **Home 搜索过滤** | **FAIL(高)** | Home → `Search sessions` → `E2ECHK5562` | `v97b_052_homesearch.png` | 同时渲染 `No sessions found for E2ECHK5562` **和**一行残留的 `\| Queued  opencode-android-shell  just now` — 空态文案与列表自相矛盾；列表行是被上一次时间线搜索（`Queued`）冻结的结果 |
| L5 | Home 搜索清空后列表恢复 | PASS | 退格×10 + ESC | `v97b_053_homesearch_cleared.png` | 回到 Today/Older 全量列表（`Queued / Untitled / Untitled / E2ECHK5560-b-t1 / Untitled …`），此时才看到标题已被改成 `Queued`（L4/Q7 的后果） |
| L6 | 会话切换（抽屉） | PASS（部分） | `input tap 62 134` | `v97b_049_home2.png` / `v97b_050_drawer.xml` | `Open sessions` 抽屉列出 Untitled / E2ECHK5554-pane / E2ECHK5558-draft…；**当前会话 E2ECHK5562-01 不在抽屉列表内**（打开 Home 后可正常进入） |
| L7 | 导出 | PASS | `More options → Export session` → SAF `SAVE` | `v97b_060_export.png` / `v97b_061_export_saved.png` / `E2ECHK5562-01b_export.json` | SAF 预填 `E2ECHK5562-01b.json`（= 当前标题）；落盘 `/sdcard/Download/E2ECHK5562-01b.json` 8,336 B；`info.title='E2ECHK5562-01b'`、`info.id=ses_f040bf529ffeTya7uOPV8Cjuaa`、7 条 message id 与后端 `…/message` **逐条一致**，schema 同为 `id/metadata/time/text/type` |
| L8 | 新建会话（项目/目录选择） | PASS | tab strip `+` → New session 卡片 | `v97b_097_newsession.png` | `New session` / `Session summary` 开关 / `Local ⌄` / `main` 分支 chip / `↑ Create session` |
| L9 | 运行位置选择器（仓库/worktree） | PASS | 点 `Local ⌄` | `v97b_098_localdrop.png` | `Run session in` → `Local repository` / `Create new worktree` / `Worktrees`：`curious-cabin` `happy-panda` `calm-eagle` `eager-lagoon` `tidy-orchid`，每项带 `Delete worktree`，底部 `Refresh worktrees` |
| L10 | 创建会话 | PASS | `Create session` | `v97b_100_created.xml` | 新 `Untitled` tab 打开（tab strip 变 2 个），`Build anything` 空态 + composer |
| L11 | 改名（新会话上，脱离卡死态） | PASS | 点标题 → 退格清空 → 输 `E2ECHK5562-tmp` → Enter | `v97b_101_titlemenu2.png` / `v97b_102_renamed_tmp.xml` | tab `T[E2ECHK5562-tmp] B[[214,46][314,62]]` 与 header 同步；`C[Search session]`/`C[Ask a side question]` 回归 |
| L12 | 删除会话 | PASS | `More options → Delete` | `v97b_103_delete_dlg.png` / `v97b_104_cancel.xml` / `v97b_105_deleted.png` | 确认框 `Delete session?` + `This deletes "E2ECHK5562-tmp" and all of its messages. This cannot be undone.` + `Cancel`/`Delete`；**Cancel 有效**；**Delete 生效**——tab strip 回落，后端 `GET /api/session` 只剩 `E2ECHK5562-01b` |

### 3.7 附件 / Stop / 窄窗 / 通知

| # | 断言 | 判定 | 命令 | 证据 | 读数 |
|---|---|---|---|---|---|
| P1 | composer `+` 菜单 | PASS | `input tap 31 690` | `v97b_062_addmenu.png/.xml` | `Attachments (Mod+U)` / `Commands (/)` / `Context (@)` / `Shell (!)` / `Paste` |
| P2 | 附件选择器 | PASS | `+ → Attachments` | `v97b_063_attachpicker.png/.xml` | SAF 打开，chip：`Images / Audio / Videos / Documents / Large files / This week` |
| P3 | 图片类型过滤 | PASS | 点 `Images` | `v97b_064_images.xml` | 只剩 `E2ECHK5562_attach_test.png`（1.35 kB / 10:22 AM） |
| P4 | composer 内缩略图预览 + 移除 | PASS | 选中图片 | `v97b_066_attach_preview.png/.xml` | composer 上方出现真实缩略图；`C[Remove attachment]` + `T[×]`；`C[Send] en=true` |
| P5 | 附件随消息上传并被模型消费 | PASS | 输 `Describe this image in one short sentence.` + Enter | `v97b_067_attach_sent` + 后端原始 JSON | user msg `msg_0fc23aea20016l0Z9Dl6mns3cj` 含 `files:[{data:"/9j/4AAQ…", mime:"image/jpeg", source:{type:"inline"}, name:"E2ECHK5562_attach_test.png"}]`；模型回复 **"A flat orange ellipse centered on a dark slate-blue background."** = 测试图内容 |
| P6 | 发送后的气泡显示附件 | **FAIL(低)** | 同上 | `v97b_068_attach_bubble.png` | 用户气泡只有纯文本，无缩略图/附件 chip |
| P7 | Enter = 发送 | PASS | `input keyevent 66` | `v97b_018_enter.xml` | 见 C3 |
| P8 | Shift+Enter = 换行 | PASS | `input keycombination 59 66` | `v97b_087_multiline.png/.xml` | `T[LINE1&#10;LINE2]`，composer 撑到 2 行，Send 由禁用转启用 |
| P9 | `/` 命令**执行** | PASS | `/model` → 点选 | `v97b_106_slashmodel` / `v97b_107_modelpicker.png/.xml` | 弹出左侧 Model sheet：`Search models` / `Variant`（Default✔ low medium high xhigh max）/ `OpenCode Go` 下 `DeepSeek V4 Flash` `… Vision Exp` `… Pro (New)` `V4.1 Flash` `GLM-5.2` `GLM-5.3` `GLM-5.3-Flash` `GPT-5.6 Luna` / `Use server default` / `Manage models`；执行后 composer 自动清空、模型未被改动（仍 `Space Bunny Free`） |
| P10 | Stop 行为 | PASS（**部分输出被丢弃**） | 流式中 `input tap 1332 320` | `v97b_082_before_stop` / `v97b_083_after_stop` / `v97b_090_narrow.png` + 后端 | 立即停流；`C[Stop]`→`C[Send]`、tab 的 `C[Working]` 消失；时间线出现 **`Interrupted`** 分隔线（窄窗下仍在）；**后端该轮没有 assistant 行**（只有 `user` + `idle`），即**已流出的部分内容被整段丢弃、不可回看** |
| P11 | 后台流式不中断 | PASS | `input keyevent 3` | `v97b_079_bg.png` / `v97b_081_back.xml` | 切桌面 4s 后回前台，`C[Stop]` 与 `C[Working]` 仍在 → SSE 连接保持 |
| P12 | 窄窗形态切换 | PASS | `wm size 560x768; wm density 160` | `v97b_090_narrow.png` | `Override size: 560x768`；自动切移动布局：底部 tab 条 `Session / Changes / Files / Terminal / ⋯`；header 精简 |
| P13 | 窄窗 Terminal tab | PASS | 点 `Terminal` | `v97b_091_narrow_term.png` | 全屏终端，`Terminal 9` + `E2ECHK5562-…` 两个 tab 及其**改名都跨布局保留**；`>_`/🗑/`+` 可见 |
| P14 | 窄窗 Files tab | PASS | 点 `Files` | `v97b_093_narrow_files.png` | `All files` + `Filter files` + 完整根目录树（含新增的 `.dsh-aci-recovery`） |
| P15 | 窄窗 Changes tab | **FAIL(中)** | 点 `Changes` → `Refresh` | `v97b_092` / `v97b_095_narrow_changes3.png` / `v97b_096_narrow_changes_retry.png` | 首次 `Loading` → 稳定在 `The diff request timed out or failed. Tap Refresh to try again.`；**Refresh 重试无效** |
| P16 | `wm` 复位 | PASS | `wm size reset; wm density reset` | 本轮末读数 | `Physical size: 1366x768` / `Physical density: 160`（无 Override） |
| P17 | 通知权限初始态 | 已记录 | `dumpsys package` / `cmd appops` | §4 N7 | `granted=false, flags=[USER_SET|USER_FIXED|…]`；`Uid mode: POST_NOTIFICATION: ignore` |
| P18 | 前台横幅 / 后台通知投递 | **FAIL(高·未实现)** | `pm grant` 后流式中切后台 | `v97b_079_bg.png` / `v97b_080_shade.png` | `dumpsys notification` 中该包记录数 **0**；无 NotificationChannel；无前台服务；通知栏无本 App 任何条目 |
| P19 | 清除通知 | 未验到 | — | — | 因 N7（无通知可清）而无法验到 |

## 4. 新发现

（累积。按严重度排序。每条 = 现象 + 最小复现 + 证据文件。**未改任何代码**。）

### N1 —【高危·数据损坏】时间线搜索的输入框会把内容提交成会话标题

- **现象**：走完时间线搜索并把输入框清空后，留在该输入框里的任意文本会在离开会话时被 **PATCH 成会话的新标题**，落库到后端。实测把 `E2ECHK5562-01` 改成了 `Queued`。
- **最小复现**：
  1. 打开任一会话 → 点 header 的 🔍 `Search session`（header 变成文本框）
  2. 输入任意词 → 时间线收敛到命中项
  3. 在**主视图**按 `keyevent 111`(ESC) → 过滤被清掉（全量回来），但**输入框仍是编辑态**、内容还在、🔍 图标不回来
  4. 把输入框清空，再输入 `Queued`
  5. 点 hamburger 或 ▦ 回 Home
  6. 后端 `GET /api/session` → 该 session 的 `title` 变成 `Queued`
- **后端读数**（`GET /api/session`）：
  ```
  事故前: ID=ses_f040bf529ffeTya7uOPV8Cjuaa TITLE='E2ECHK5562-01'
  事故后: ID=ses_f040bf529ffeTya7uOPV8Cjuaa TITLE='Queued'
  ```
- **证据**：`v97b_046_searchclose.png`（ESC 后过滤清了、框还在）、`v97b_047_searchemptied.xml`（清空后 `Search session` 节点仍缺失）、`v97b_048_search_queued.png`（输入 Queued）、`v97b_053_homesearch_cleared.png`（Home 列表里标题已是 `Queued`）。
- **补充**：修复合入的是"在同一个卡死输入框里输入新标题并按 Enter"，`Enter` 会正常改名并同步 tab（`v97b_059_rename_done.xml` + 后端 `title='E2ECHK5562-01b'`）。所以**受影响会话可以用同一路径自愈**。

### N2 —【高危】时间线搜索结果冻结在首次查询，后续改词永不重算

- **现象**：`Search session` 的过滤**只在第一次输入时计算一次**；之后无论增删改查询词，结果集都不变。
- **最小复现**：打开会话 → 🔍 → 输 `pathlib`（正确命中代码块 `from pathlib import Path`）→ 全选退格 → 输 `the` / `OpenCode` / `Queued`。
- **预期 vs 实测**：
  | 查询 | 应命中 | 实测显示 |
  |---|---|---|
  | `pathlib` | `from pathlib import Path` | ✔ 正确 |
  | `the` | "the shell command"、"The queued message…" | `from pathlib import Path` |
  | `OpenCode` | H1 `OpenCode Android Shell` | `from pathlib import Path` |
  | `Queued` | 用户气泡 `Queued second message while streaming.` | `from pathlib import Path` |
- **证据**：`v97b_042_search.png`（首次正确）、`v97b_043_search_multi.png`、`v97b_045_search_opencode.png`、`v97b_048_search_queued.png`。
- **影响**：时间线搜索实际是"一次性"的，第二次搜必错。

### N3 —【高危】header 标题编辑态无取消路径，跨导航不恢复

- **现象**：进入标题编辑态（点标题，或经 N1/N2 路径）后，header 的 `Search session` 🔍 与 `Ask a side question` ? **两个入口被移除且不会自己回来**；跨 Home 导航、跨关闭/重开 tab 都不恢复；此时点标题不再弹会话菜单；`More options → Rename` 也变成 no-op。
- **最小复现**：任意会话 → 点 header 标题（文本框出现、文本全选、🔍/? 消失）→ 按 ESC / 退格清空 / 回 Home → 再进会话 → 🔍 仍不存在；`More options → Rename` 无任何弹层。
- **唯一已验证的自愈**：在该输入框里输入并按 `Enter` 提交一个标题。
- **证据**：`v97b_055_titlemenu.png`（点标题只选中文字，无菜单）、`v97b_056_esc2.xml`（二次 ESC 仍无 `Search session`）、`v97b_057_moreoptions.xml` + `v97b_058_rename_dlg.png`（Rename 无弹层）、`v97b_054_open_renamed.xml`（重开会话仍未恢复）、`v97b_059_rename_done.xml`（Enter 提交后恢复）。
- **说明（避免误判）**："点标题进编辑态、🔍/? 隐藏" **本身是设计**（在新会话上复现同样如此，见 `v97b_101_titlemenu2.png`）。真正的缺陷是**没有取消路径**：ESC / 清空 / 导航都不退出，只有提交才退出。

### N4 —【中危】Home 会话搜索同时渲染"无结果"文案与残留行

- **现象**：在 Home 的 `Search sessions` 里输入一个**确实存在**的标题前缀，列表上方显示 `No sessions found for E2ECHK5562`，**紧接着下方仍渲染一行残留结果** `| Queued  opencode-android-shell  just now`。
- **最小复现**：先在会话里做 N1/N2（把搜索查询停在 `Queued`）→ 回 Home → `Search sessions` 输入 `E2ECHK5562`。
- **读数**：空态文案用新查询词，列表内容仍是上一次冻结的查询结果 —— 空态与列表**自相矛盾**。
- **证据**：`v97b_052_homesearch.png`。
- **同源**：与 N2 同一根因（过滤器只算一次）；清空 Home 搜索框后列表才恢复全量（`v97b_053_homesearch_cleared.png`）。

### N5 —【中危】Review → Files：清空过滤器不恢复全量列表，Refresh 也救不回

- **现象**：`Filter files` 输入过滤词后列表收敛；把输入框删空，列表**保持收敛状态**。点 `Refresh` 只弹 toast，列表不变。只有**切到别的 tab 再切回**（或关闭重开侧栏）才恢复。
- **最小复现**：▣ → `Files` → 过滤器输入 `gradle`（剩 `.gradle`/`gradle` 两行）→ 退格删空 → `Refresh` → 仍 2 行 → 切 `Context` 再切 `Files` → 恢复 15 行。
- **证据**：`v97b_035_filesfilter.png`（收敛）、`v97b_036_filterclear.png`（删空仍 2 行）、`v97b_037_refresh.png`（Refresh 只弹 `Directory state rebuilt — in-flight permissions/forms may have been cancelled.`）、`v97b_038_reopen.png`（关侧栏重开恢复）、`v97b_040_filtertabcycle2.png`（切 tab 恢复）。
- **附带**：`Filter files` 只按**当前层条目名**匹配，不递归搜文件名 —— 过滤器输入 `gradle` 时 `gradle.properties`、`build.gradle.kts` 均不命中。

### N6 —【中危】Review → Changes 的 diff 统计被截断；窄窗下直接超时且不可恢复

- **现象**：桌面形态下 Changes 头部只显示 `+153 -76`；同一仓库 `git diff --shortstat HEAD` 是 `207 files changed, 57407 insertions(+), 9201 deletions(-)`。窄窗（560x768）下 Changes 页直接进入错误态 `The diff request timed out or failed. Tap Refresh to try again.`，点 `Refresh` **重试仍失败**。
- **最小复现**：宽窗 ▣ → `Changes`（读 `+153 -76`）；`wm size 560x768; wm density 160` → 底部 `Changes` tab → 错误态 → `Refresh` → 仍错误态。
- **证据**：`v97b_032_changes.png`（宽窗 `+153 -76`）、`v97b_095_narrow_changes3.png`（错误态）、`v97b_096_narrow_changes_retry.png`（重试无效）；host 侧 `git diff --shortstat HEAD` / `git diff --shortstat refs/remotes/origin/main...HEAD`。
- **未验到**：截断/超时发生在客户端上限还是服务端分页，**本轮没定位到机制**，只确认"口径与真实工作区 diff 不一致且窄窗下不可用"。

### N7 —【中危】通知域整体未实现

- **现象**：APK 声明了 `POST_NOTIFICATIONS` 且代码里有 `NotificationPermissionRequest`，但**从未创建任何 `NotificationChannel`、从未投递任何通知、也没有前台服务**。前台流式时无横幅；`keyevent 3` 切后台（流式仍在跑）后 `dumpsys notification` 里 `com.xiufeigo.opencodeshell` 记录数仍为 **0**；下拉通知栏只有系统通知。
- **最小复现**：
  1. `pm grant com.xiufeigo.opencodeshell android.permission.POST_NOTIFICATIONS`（已 granted=true）
  2. 会话里发一条长 prompt（`Count slowly from 1 to 40…`），确认在流式（`C[Stop]` 存在）
  3. `input keyevent 3` 回桌面，等 4s
  4. `adb shell "dumpsys notification --noredact | grep -i opencodeshell"` → 只有 `AppSettings: … importance=DEFAULT userSet=true`，**无 NotificationRecord**
  5. `cmd statusbar expand-notifications` → 通知栏只有 `Android System` 系列
  6. `dumpsys activity services com.xiufeigo.opencodeshell` → `(nothing)`
- **初始权限态**：本机 `POST_NOTIFICATIONS: granted=false, flags=[USER_SET|USER_FIXED|…]`、`Uid mode: POST_NOTIFICATION: ignore`。因带 `USER_FIXED`，**未观察到运行时弹窗**属预期（系统不再提示），**不能据此判定权限申请逻辑有 bug**。
- **证据**：`v97b_076_long_start`…`v97b_080_shade.png`（下拉栏）、`v97b_079_bg.png`（后台态）、以及 `dumpsys` 读数（本报告 §1 风格，逐条已在正文命令中给出）。
- **结论**：清除通知 / 通知权限态之外的"前台横幅 / 后台通知投递 / 通道"三项**均未实现**。

### N8 —【中危】软键盘弹起时时间线被压到 45–90px（768px 高桌面窗）

- **现象**：1366x768 窗口 + GBoard 横屏键盘（占 y≈340..768）时，可见时间线只剩 y≈145..190（**约 45px**），用户消息与流式输出全部被挤到可视区外；队列条出现时更窄。Review 侧栏同病（`v97b_035`/`v97b_036` 里 Files 列表只剩 2 行可视）。
- **最小复现**：进会话 → composer 自动聚焦（键盘弹起）→ 观察 header 与 composer 之间的可视条带。
- **证据**：`v97b_023_stream.png`（流式中，队列条上方全空）、`v97b_025_tab.png`、`v97b_035_filesfilter.png`。
- **说明**：这是"矮桌面窗 + 软键盘"的组合问题，非平板/手机形态。

### N9 —【中危·扫测工具链】`keyevent 61`(TAB) 不会收键盘，反而往 composer 写一个字面 TAB

- **现象**：派单指定"收键盘用 `keyevent 61`(TAB)"，实测 **TAB 被当作输入字符插进 composer**（dump 里出现 `T[&#9;] B[[28,618][1338,666]]`），键盘不收、焦点不移动。
- **证据**：`v97b_027_scrollup.xml`。
- **可用的替代**：**主视图**（非设置/表单弹层）内 `keyevent 111`(ESC) 可收键盘（`v97b_026_esc.png`）。本轮所有 ESC 使用均在主视图，弹层内一律改用点外部/点按钮关闭。

### N10 —【低危】若干

| 项 | 现象 | 证据 |
|---|---|---|
| N10a | `/` 查询 token **非**原子删除：1 次 DEL 只去掉一个字符（`/co`→`/c`），与 `@` 提及整块删除行为不一致；结果落进 `No matching commands` | `v97b_016_slashdel1.xml` / `v97b_016b_probe.png` |
| N10b | 工具卡可见箭头画在 x≈1332，但 a11y 点击区 `B[[16,326][1312,354]]` 止于 x=1312；**点箭头无反应，点行主体才展开** | `v97b_028_toolcard_open.png` vs `v97b_029_toolcard_expand.png` |
| N10c | `/` 与 `@` 两个弹层都是**固定高度**，最后一行被下边缘**从字形中间切断**且无滚动提示；弹层还会**盖住 header 的 🔍 / ? 图标**，打开期间不可达 | `v97b_003_slash.png` / `v97b_088_at.png` |
| N10d | 发送后的用户气泡**不显示已上传的附件**（无缩略图、无附件 chip），尽管图片已成功上传且模型确实看到了 | `v97b_068_attach_bubble.png` + 后端 `files[0]{mime:"image/jpeg", source:{type:"inline"}}` |
| N10e | 附件上传时把 PNG **重编码为 JPEG**（`name=*.png` 但 `mime=image/jpeg`，base64 以 `/9j/` 开头），PNG 透明通道会丢 | 同上（后端原始 JSON） |
| N10f | 导出成功后**无任何成功提示**（无 toast / 无 snackbar） | `v97b_061_export_saved.xml`（回到会话页，dump 无提示节点） |
| N10g | 终端 tab 改名后，`…` 按钮的 `content-desc` 仍是旧名 `Terminal 10`，与可见标签 `E2ECHK5562-SH` 不一致（a11y 陈旧） | `v97b_075_term_renamed.xml` |
| N10h | Review 面板实际是 **3 个 tab**（`Changes / Context / Files`），不是派单写的 2 个；且 `◎`(Context usage) 与 `▣`(Toggle review panel) 开的是**同一个侧栏**，`◎` 只是预选 Context 页 | `v97b_031` vs `v97b_038` |
| N10i | 窄窗形态下 header 的 `Search session` 🔍 在 Terminal 视图消失（Session 视图有） | `v97b_090_narrow.xml` vs `v97b_091_narrow_term.xml` |
| N10j | 窄窗 Changes 多出一个桌面形态没有的口径 `Last turn changes` | `v97b_095_narrow_changes3.png` |
| N10k | `Open sessions` 抽屉列表**不包含当前会话**（`E2ECHK5562-01b` 不在其中），只能从 Home 进 | `v97b_049_home2.png` / `v97b_050_drawer.xml` |
| N10l | 模型选择 sheet 打开时点右侧空白（y=620）落在**软键盘**上，把字母打进 `Search models` 框而不是关闭 sheet —— sheet 在键盘区无"点击外部关闭"落点 | `v97b_108_after_model.png`（搜索框出现 `m`） |

### 已排除的疑似问题（避免误报）

- **终端命令回显丢词**：`v97b_070_term_echo.png` 里 `echo E2ECHK5562-TERM OK-5562` 被回显成 `echo OK-5562` —— 这是我第一次用 `input text "…带空格…"` 的 **adb 侧丢字**，不是 App 缺陷。改用合规分段输入（空格=keyevent 62）后 `echo E2ECHK5562-TERM-OK` **回显与输出都完全正确**（`v97b_071_term_echo2.png`）。
- **`Interrupted` 标记消失**：流式被 Stop 后一度以为该分隔线会消失；实际它在窄窗下仍在（`v97b_090_narrow.png` y=574），之前只是被 composer 挤出可视区。**标记是持久的，不算缺陷**；真正的缺陷是"部分输出被整段丢弃"（见 §3.7 P5）。
- **模型回复说"队列消息是空的"**：`api_msgs…json` 显示队列正文 `text="Queued second message while streaming."` 完整落库，是模型自身误读，**App 侧队列无丢字**。

## 5. 未闭环 / 未验到

| 项 | 原因 |
|---|---|
| 队列项的 `×`（移除）与 `Steer` 行为 | 需要一次长流式轮次；本轮只在发送后 3s 内看过队列条，未点这两个控件 |
| 附件的**非图片**文件预览 / 上传（只验了 PNG→图片预览+上传） | 时间预算 |
| `Import session`、`Fork session`、`Move to…`、`New worktree`、`Undo` | 菜单项存在已验（`v97b_057_moreoptions.xml`），执行未做 |
| `Session summary` 面板、`Ask a side question`(`/btw`) | 未打开 |
| `Revert message` / `Copy message` / `Copy response` / `Copy code` 的实际点击与剪贴板校验 | 控件存在已验，点击未做 |
| 终端 `>_`(Session terminals) / 🗑(Background shells) 面板 | 未打开 |
| 终端面板拖拽 resize / 双击 reset | 语义节点存在（`C[Drag to resize the terminal panel, double-tap to reset]`），未实际拖拽 |
| Home 左侧 Projects 行的 `…`/`✎`/`×`（`编辑项目` 面板按派单**排除**） | 按派单跳过 |
| AgentChip `/agent` 菜单重弹 | 按派单**排除**（Lead 并行修复中） |
| N6 的截断/超时机制（客户端上限 vs 服务端分页） | 未定位到代码路径，只拿到行为证据 |
| 通知：清除通知 / 通道管理 | 因为**根本没有任何通知**（N7）而无法验到 |
| `settings put secure show_ime_with_hard_keyboard` | 本轮**只核对未改**（读到已是 `1`），未写入 |
| 窄窗下的 composer 形态细节（`/`、`@`、附件弹层在 560px 下的表现） | 只验了窄窗的 Session/Changes/Files/Terminal 四个 tab |

### 遗留物（本轮在设备上留下的东西，均带 `E2ECHK5562` 前缀）
- 后端会话：`ses_f040bf529ffeTya7uOPV8Cjuaa`，标题 **`E2ECHK5562-01b`**（原 `E2ECHK5562-01`，被 N1 事故改名后修复）
- 设备文件：`/sdcard/Download/E2ECHK5562-01b.json`（8,336 B，导出）、`/sdcard/Download/E2ECHK5562_attach_test.png`（1,348 B，测试图）
- 证据目录内 90+ 个 `v97b_*.png` / `v97b_*.xml`、`api_msgs_e2echk5562-01.json`、`E2ECHK5562-01b_export.json`、`_tools/`（`input.ps1` 分段输入、`nodes.ps1` dump 解析）
- 设备设置改动：仅 `pm grant … POST_NOTIFICATIONS`（N7 需要）。`wm size` / `wm density` 已 `reset` 并复核为 `Physical size: 1366x768` / `Physical density: 160`
