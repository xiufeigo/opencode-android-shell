# v97 residue — task-4（v57 修复批移交项：AgentChip/Composer 弹层焦点归还 + HomeProjectsList ROW_GAP 死缝）

- 日期：2026-10-02
- 范围：`research/HANDOFF.md:70`「v57 修复批移交项（下轮顺手）」两条
- 口径：先取证是否仍未落地；未落地的才动手；已修好的只写取证不动码

## 结论一览

| # | 移交项 | 判定 | 处置 |
|---|---|---|---|
| 1a | `Composer.kt` 的 `ocPopoverDismiss` 弹层 autofocus 抢焦点不归还 | **已落地（v63 BUG-1）** | 不动码（取证见 §1.1） |
| 1b | `AgentChip.kt` 的 agent 弹层同款抢焦点不归还 | **未修 → 本轮修复** | 照桶B 模式补（§1.2） |
| 2 | `HomeProjectsList.kt` 项目行 ROW_GAP 死缝 | **已落地（结构性消除）** | 不动码 + 取证 pin（§2） |

## 1. 弹层焦点归还（锚点漂移线）

### 1.1 `Composer.kt` —— 已落地，别硬改

移交项里的「约 1435-1493 行」是 v57 时代的行号；现行代码里 `Composer.kt` 的 `ocPopoverDismiss` 弹层是 `+` 菜单（`AddMenuButton`，现为 `Composer.kt:2213-2342`）。该弹层**已在 v63 BUG-1 落地**焦点归还：

- 证据 1：`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt:2229-2243` —— `openedOnce` 闩 + `LaunchedEffect(open)`，只在「真的开过一次」之后的关闭触发 `onRestoreFocus()`（注释明写 v63 BUG-1、"fired only on a close that follows a real open"，符合 v75 已定语义：重建卸载不归还）。
- 证据 2：`Composer.kt:1710` —— 调用点 `onRestoreFocus = { runCatching { focusRequester.requestFocus() } }`。
- 与 ModelChip 桶B 的差异（v63 版不区分 OUTSIDE、不补 `keyboardController.show()`）是 v63 的既定语义，本任务书明令「已修好的别硬改」，故保持原样。

### 1.2 `AgentChip.kt` —— 未修，本轮照桶B 模式修复

**取证（未落地）**：修复前 `AgentChip.kt` 的 agent 弹层（`PopupProperties(focusable=true)` + `ocPopoverDismiss(popoverDismiss) { expanded = false }`）：
- 无 `onRestoreFocus` 参数、无 `closeReason`、无卸载归还 —— 全仓 `restoresFocusOnUnmount` 只有 `ModelChip.kt:655` 一处定义、一处使用；
- 弹层根节点初始聚焦（`ocPopoverDismiss` 的 `requestInitialFocus` 默认 true，ESC 的必要条件）把焦点与 IME 从 composer 编辑器手里抢走，卸载后没人接手 → 布局永久停在「无键盘」档，下一次弹层锚点漂一个键盘高度（`OcPopoverDismiss.kt:79-87` 文件头「关掉之后焦点还给谁」一节在案）。

**修复（照 `ModelChip` 的 v57/v75 语义，未反转任何已定语义）**：
- 新参数 `onRestoreFocus: (() -> Unit)? = null`（`AgentChip.kt:98-106`，KDoc 记偏离与出处）；
- `closeReason` 状态 + `closeMenu(reason)`：三条关闭路径全带原因 —— 外点 `onDismissRequest → OUTSIDE`、ESC `ocPopoverDismiss → KEY`、两个 `AgentRow` 选中 `→ PICK`；
- `openMenu()`：打开时清掉上一轮 `closeReason`（重建卸载不会被误判成关闭）并快照 `imeVisibleAtOpen`；
- 卸载归还：弹层内容内 `DisposableEffect(Unit) { onDispose { if (restoresFocusOnUnmount(closeReason)) { latestRestore?.invoke(); if (imeVisibleAtOpen) keyboardController?.show() } } }` —— **只有「真的关过一次」（非 null 且非 OUTSIDE）才归还**，外点关闭不还（那一击由 `flushPendingTap` 补发给底下的控件），重建卸载不还（v75 P1 防开/毁震荡语义原样保留）；
- `restoresFocusOnUnmount` 复用 `ModelChip.kt` 的共享判定（同包 internal，未另写一份）。
- 调用点：`Composer.kt:1727-1738` 给 `AgentChip` 接 `onRestoreFocus = { runCatching { focusRequester.requestFocus() } }`（与 ModelChip 的接线同款）。
- 无新增用户可见字符串（三语 res 未动）。

### 1.3 pin 测试

新增 `app/src/test/java/com/xiufeigo/opencodeshell/AgentChipFocusRestoreV97Test.kt`（4 例）：
1. `onlyARealCloseHandsTheFocusBack` —— 共享判定语义（null/KEY/PICK/OUTSIDE）+ 禁本地重定义；
2. `everyClosePathCarriesAReasonAndOpenClearsIt` —— 三条关闭路径带原因、`openMenu` 清原因 + 快照 IME；
3. `theTeardownRestoresAtDisposeTimeAndReShowsOnlyWhatWasVisible` —— 卸载时读当刻原因/回调、只在打开时可见才补键盘；
4. `theComposerHandsTheEditorBackToTheChip` —— Composer 调用点接线。

未改任何他人测试文件（`ComposerModelChipV75Test`/`OcPopoverDismissTest`/`Wave5ComposerWiringTest` 的既有 pin 与本修复兼容，原样通过）。

## 2. HomeProjectsList ROW_GAP 死缝 —— 已落地（结构性消除）

**取证**：
- 现行 `HomeProjectsList.kt` **无 `ROW_GAP` 常量、行间无任何 Spacer**：两个挂载点（桌面列 `HomeProjectsColumn` `HomeProjectsList.kt:188`、手机下拉 `HomeProjectsDropdown` `HomeProjectsList.kt:717`）的 `projects.forEach` 都直接背靠背发射 `ProjectRow`；文件里仅有的 Spacer 是表头下 16dp（上游 `gap-4`，`:181`）、utility nav 前的弹性/16dp（`:239,:242`）、Recently-closed 头前 12dp（上游 `mt-3`，`:568`）—— 均为段落间隔，非行缝。
- 每行带 `.ocHitTargetAtLeast(HomeRowLayout.MIN_HIT_TARGET = 48dp)`（`ProjectRow` `:368`、`AddProjectRow` `:259`、`RecentlyClosedRow` `:596`）：视觉 28dp 的行落在一个 ≥48dp 的命中节点里，相邻行的 48dp 命中区**无缝密铺**（行内 28dp clickable 被平台最小触摸目标居中扩到 48dp，恰好铺满节点），行与行之间不存在「谁也不认领」的像素 —— v57「缝隙误触/死缝」的形状在现行代码里结构性不可能。
- 桶C 的「gap 归行热区」语义仍由会话列表承担：`HomeSessionsList.kt:982-984`（`gap-px` 是行自己的 `.padding(bottom = ROW_GAP)`，位于 `combinedClickable` 之后）+ `ROW_GAP = 1.dp`（`:2076`），pin 在 `HomeSessionGapTapV58Test`。

**处置**：不动产品码。新增取证 pin `app/src/test/java/com/xiufeigo/opencodeshell/HomeProjectRowSeamV97Test.kt`（2 例）：
1. `projectRowsAreEmitedBackToBack`（实际方法名 `projectRowsAreEmittedBackToBack`）—— 禁 `ROW_GAP` 回潮 + 每个 `projects.forEach` 到随后行调用之间不许出现 Spacer；
2. `theRowHitFloorTilesTheColumn` —— 28dp 视报导 + 48dp 命中地板（`HomeRowLayout.MIN_HIT_TARGET`/`hitTarget(28.dp)` 纯算术）+ 三个行挂载都带地板且次序正确（`OcTouchTarget.kt` 的次序规则）。

## 3. 命令与结果

- 环境约定：`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`，`.\gradlew.bat --console=plain :app:testDebugUnitTest --tests "<精确类名>"`；无通配、无全量、无 assembleDebug、无 clean、无装机。
- Gradle 锁：跑前确认 `.agent-gradle-lock` 不存在 → `New-Item -ItemType File -Force .agent-gradle-lock` → 跑完 `[System.IO.File]::Delete` 释放（实测释放成功）。
- 第一次运行：`--tests` 上表 6 类 → `compileDebugKotlin` 失败（`AgentChip.kt:124:38` `WindowInsets.isImeVisible` 需 `ExperimentalLayoutApi`，与 `ModelChip.kt:103` 同款）→ 给 `fun AgentChip` 补 `@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)` 后重跑。
- 第二次运行（同 6 类）：`BUILD SUCCESSFUL in 8s`；JUnit XML 读数（`app/build/test-results/testDebugUnitTest/`）：

| 类 | tests | failures | errors | skipped |
|---|---|---|---|---|
| AgentChipFocusRestoreV97Test | 4 | 0 | 0 | 0 |
| HomeProjectRowSeamV97Test | 2 | 0 | 0 | 0 |
| OcPopoverDismissTest | 20 | 0 | 0 | 0 |
| Wave5ComposerWiringTest | 11 | 0 | 0 | 0 |
| ComposerModelChipV75Test | 6 | 0 | 0 | 0 |
| HomeSessionGapTapV58Test | 4 | 0 | 0 | 0 |
| 合计 | **47** | **0** | **0** | **0** |

## 4. 改动文件

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/AgentChip.kt` | 桶B 焦点归还（onRestoreFocus 参数、closeReason/openMenu/closeMenu、卸载 DisposableEffect、@OptIn） |
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt` | AgentChip 调用点接 `onRestoreFocus`（仅此一处，+5 行） |
| `app/src/test/java/com/xiufeigo/opencodeshell/AgentChipFocusRestoreV97Test.kt` | 新建 pin（4 例） |
| `app/src/test/java/com/xiufeigo/opencodeshell/HomeProjectRowSeamV97Test.kt` | 新建取证 pin（2 例） |
| `research/v97_verify/residue/REPORT.md` | 本报告 |

## 5. 未闭环项

1. **真机复验待装机**（本轮纯 JVM，按纪律禁 assembleDebug/禁装机）：复验口径 = ①打开 agent 弹层（chip 点击或 `/agent`）→ ESC 关闭 → 编辑器光标回来、键盘若开前可见则回位，composer 位置与开前同档；②选中某 agent（PICK）同上；③外点关闭后**不**抢焦点（那一击落到底下控件）；④反复关/开，锚点不漂一个键盘高度（对齐 v58 5554 的 0px 漂移口径）。
2. **新发现（未越权修，报告备案）**：`AgentChip.kt` 的 `/agent` 打开请求仍是 v74 时代的 `LaunchedEffect(openRequest) { if (openRequest > 0) openMenu() }`（`AgentChip.kt:143-145`）—— 本实例每次挂载都会重读宿主单向闩，用户用过一次 `/agent` 之后任何重建（点 composer 文本区等）都会「凭空」再弹一次菜单。这正是 ModelChip 在 v75 P2 用 `modelOpenRequestArrived(seen, current)` 修掉的同款形状；建议 Lead 派单给 composer 域 owner（同文件同族，修法可整套照搬）。
3. **工具限制**：本 session 没有 `team_task_get/claim/update` 工具（harness 未授予），task-4 的 complete 需由 Lead 执行；本报告 + 回传消息即交付物。
