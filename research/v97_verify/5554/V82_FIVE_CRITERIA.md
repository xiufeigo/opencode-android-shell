# v82 五条口径（权威原文，Lead 提供 — 2026-10-02 v97 轮）

> 用途：解除 `E2ECHK5554_v82five.ps1` 头部 `TODO(TASK-2 TEXT)`。原文依据 `research/HANDOFF.md`
> 第 71 行（v82 chord/设置弹层 IME 双 bug 桶）+ 第 34/35 行（v83/v82 真机取证结论）。
> 测试员在 v97 报告里记为「Lead 提供的 v82 口径原文」。

## S1 — Ctrl+K 打开命令面板（PALETTE）
- **命令**：`adb -s emulator-5554 shell input keycombination 113 39`
  （`KEYCODE_CTRL_LEFT=113`、`KEYCODE_K=39`。**绝不用 75=APOSTROPHE=Ctrl+'**，那是 `model.choose` 的
  默认键、按设计弹模型选择器 —— v82 那次「FAIL」就是这么来的；也不要用 42=KEYCODE_N=`tab.new`）。
- **判据（PASS）**：按键后出现**palette（命令面板）层**：dump 里出现面板容器/搜索框/命令分组行
  （命令候选行文本形如 `New session`、`Settings`、`Toggle theme`… 以及一个搜索 EditText）。
  面板**必须是 palette**，不是模型选择器、不是新会话草稿页。
- **证据**：`V82_S1_00_before` / `V82_S1_01_after` 截图 + 对应 dump 节点表。

## S2 — Ctrl+Shift+P 打开命令面板（PALETTE）

> ⚠️ **勘误（2026-10-02 18:1x，5556 测试员实测推翻本文档初版）**：Ctrl+Shift+P **不是** `113 44`。
> `input keycombination 113 44` = **Ctrl+P（无 Shift）**，命中 `file.open` → `PALETTE_FILES`（搜索框提示词
> `Search files`、`Commands` 分组 0 条）——**这是按设计的行为，app 无 bug，错的是脚本**（与 v82 那次
> `113 75` 同型）。源码三处互证：`ui/settings/SettingsModel.kt:1304` `ShortcutRow("command.palette", …,
> "Ctrl+K / Ctrl+Shift+P")`；`:1306` `ShortcutRow("file.open", …, "Ctrl+P")`；`ui/App.kt:2168-2172`
> `PALETTE`=mod+k / mod+shift+p，`PALETTE_FILES`=mod+p。

- **命令（修正后）**：`input keycombination 113 59 44`（`KEYCODE_CTRL_LEFT=113`、`KEYCODE_SHIFT_LEFT=59`、`KEYCODE_P=44`）。
- **判据（PASS）**：同上，出现 palette 层；且与 S1 是**同一个**面板（`effectiveGlobalChords` 里
  `ctrl+k` 与 `ctrl+shift+p` 都映到 PALETTE，JVM 已由 `ChordPaletteReachabilityV82Test` 钉死）。
- **反例**：输入态（焦点在 composer/输入框）时 chord **不让位**——即输入态下 Ctrl+K 也应打开 palette。

## S3 — 设置弹层给键盘让位（IME band）
- **步骤**：Home → Settings 打开弹层 → dump 记「卡片头行 y（IME 落下）」→ 点搜索框抬起键盘 → dump 记
  「卡片头行 y（IME 抬起）」。
- **判据（PASS）**：键盘抬起后卡片**收缩并抬到键盘之上**，与键盘**零重叠**，键盘以上带内可滚动到达所需行；
  库函数口径 `settingsDialogCardHeightDp(windowHeightDp, imeBottomDp)` = `min(window−92, 600)`，键盘态再取
  「键盘以上可见带」为上限、下限 **240dp**。
  > ⚠️ **Lead 裁决（2026-10-02 18:3x，依据 5556 实测）**：本文档初版写的「卡片顶边 y 不变（≤12px）」是
  > **误写，作废**。`settingsDialogCardHeightDp` 的语义就是「收缩 + 抬到键盘之上」，顶边必然移动。
  > 实测（5556，1366x768@160）：卡片底 **671→283**、键盘上沿 **284**、重叠 **0px**；卡片高 **580→238**
  > （≈240dp 地板）；两个滚动体 459/435→119/191；键盘以上带内**快滑**实测把 `Show agent`/`Terminal placement`
  > 从键盘下升到键盘上 ⇒ 功能目标成立，v82 旧 bug 三特征（顶边不动 / 下半被盖死 / scroll 不启用）**全不成立**。
  > **结论：S3 = PASS（按意图）。**
  > **遗留观察（另单跟进）**：键盘**落下**态实测高 **580** / 卡片顶边 **95**，与 HANDOFF 记的旧值 **600 / 84**
  > 差 20dp —— 疑似 IME-down 几何也变了（`windowHeightDp` 取扣掉桌面 shell 条后的高度）。该差异是「对照
  > HANDOFF 记录值」，**非本轮实测旧 APK**，已派单核实。
- **反例（v82 旧 bug）**：卡片顶边不动、下半被 IME 盖死、卡片自身不溢出 → 面板 scroll 永不启用。
- **证据**：`V82_S3_00_dialog` / `V82_S3_01_ime_up` 截图 + 两次 dump 的 y 读数。
- **纪律**：设置弹层内**禁用 ESC(111)**；收键盘用 `keyevent 61`(TAB)。

## S4 — Add/Edit server 表单 footer 键盘态可达
- **步骤**：Settings → Servers → `Add SSH server` → 点第一个字段抬键盘 → dump 找 `Add server` / `Cancel`。
- **判据（PASS）**：footer（`Add server`/`Cancel`）在**键盘顶边以上**可见可点；
  纯函数 `serverDialogBandDp(windowHeightDp, imeBottomDp, topInWindowDp)` = 键盘以上剩余可视带，
  下限 `SERVER_DIALOG_MIN_BAND_DP = 160`；卡片改为 `.heightIn(max = band)`，表单体 `verticalScroll` 生效。
  v83 的结论：**卡片自己 footer 在滚动体里**，所以「先在这条窄带里上滑一下能滚到底、Save 可见」即 PASS
  （不必一屏全见）。
- **证据**：`V82_S4_01_ime_up` 截图 + dump 的 footer y 区间 + 一次**有效**上滑（落点必须在表单体内，
  落在键盘区/表单外的 swipe 零位移 —— v83 测试员 9 次零位移就是落点问题）。

## S5 — 纯 SSE 掉线自愈后「Not connected — …」卡自行退休
- **判据（PASS）**：transport 恢复（`TransportRecovered` 路径）时，屏幕上由本壳写出的
  `Not connected — …` 家族卡片（`ChatViewModel.NOT_CONNECTED_LINE`，六句家族，含
  `Not connected to an OpenCode 2.x server.`）**自行消失**；依据 `TransportRetry.isClientNotConnectedText`
  （**行首**前缀 `not connected`，非子串）已并入 `isConnectionFailureText` → 归 TRANSPORT 可退休类。
- **限制（必须照写进报告）**：验证这一条要**打断共享后端 4095**（测试员禁止）。因此 S5 本机只做
  **被动取证**（截图 + 当前有无该卡），结论写 `NEEDS LEAD / 未验到`，**不许猜 PASS**。
  Lead 可在收口时用 `adb reverse` 摘除（`adb -s <serial> reverse --remove tcp:4095` 再挂回）制造
  纯客户端掉线，而**不动后端进程** —— 这属于 Lead 侧动作。

## 结论记录格式
每条写：`S<n> = PASS / FAIL / 未验到(NEEDS LEAD)` + 命令 + 证据文件相对路径 + 读数。
