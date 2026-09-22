# W4-C 移动端形态对齐（task-3）报告

> 负责人：mobile-form（Agent Teams teammate）
> 写作用域：`ui/session/SessionScreen.kt`、`ui/session/drawer/**`、`ui/session/review/**`、`ui/session/dock/SessionQuestionDock.kt`（task-3 交付 4 要求的文件，越界说明见 §4.7）、三份 `strings.xml`（只追加、前缀 `oc_mobile_`）、本文件。
> 上游 = `_upstream/packages/**`（只读）。构建门：`.gradlew.bat :app:testDebugUnitTest`，JAVA_HOME=`C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1`。

---

## 1. 交付摘要

| # | 交付 | 状态 | 主要文件 |
|---|---|---|---|
| 1 | 会话抽屉改上游 **底部 sheet**（16dp 上圆角 / 28dp 拖拽柄 + 32×4 圆条 / scrim / 下滑关闭 / 240-200ms 动效 / `bg-deep` / `max-height: min(75dvh, 100dvh−safe-top−16px)`） | ✅ | `ui/session/drawer/SessionBottomSheet.kt`（新）、`ui/session/drawer/MobileForm.kt`（新） |
| 2 | 窄屏 **四个整宽 tab**（Session / Changes / Files / Terminal）+ 右侧 `⋯`（用量 / 会话详情）替代 340dp 侧栏；宽屏保留侧栏 | ✅ | `ui/session/review/MobileSessionTabs.kt`、`MobileUsagePanel.kt`（新）、`ui/session/SessionScreen.kt` |
| 3 | 抽屉会话行 **长按 500ms 菜单**（重命名 + 删除，删除走 `DELETE /session/:id`） | ✅ | `ui/session/drawer/SessionDrawer.kt`、`ui/session/drawer/SessionRowDialogs.kt`（新） |
| 4 | question dock **多题分页/进度段**（`1/N` + 分段 + Back/Next→Submit） | ✅ | `ui/session/dock/SessionQuestionDock.kt` |
| 5 | diff 渲染规格：等宽 **13sp/24sp**、**只染背景不染前景** | ✅ | `ui/session/review/ReviewChangesTab.kt` |
| 附 | 跨人接线：`review.toggle` / `fileTree.toggle` / `file.open`（shell-wiring）、Terminal 内嵌（Lead）、`openPath` 定位 | ✅ | `SessionScreen.kt`、`ReviewPanel.kt`、`ReviewFilesTab.kt` |

新增字符串（`oc_mobile_` 前缀，三 locale 同步）：`oc_mobile_tab_session`、`oc_mobile_view_select`、`oc_mobile_session_rename_title`、`oc_mobile_session_delete_title`、`oc_mobile_session_delete_message`、`oc_mobile_question_progress`、`oc_mobile_question_next`、`oc_mobile_question_back`、`oc_mobile_question_numbered`、`oc_mobile_usage`、`oc_mobile_summary`。复用既有 key：`oc_rename_session` / `oc_delete_session` / `oc_common_cancel` / `oc_common_save` / `oc_terminal_title` / `oc_review_tab_changes` / `oc_review_tab_files` / `oc_context_usage` / `oc_meta_context_tokens` / `oc_meta_context_cost`。

### 1.1 文件清单

新增：
- `ui/session/drawer/MobileForm.kt`（形态门控 + sheet 度量 + 长按常量 + tab 枚举 + `QuestionPager`，纯逻辑）
- `ui/session/drawer/MobileColors.kt`（正确 ARGB 的移动端表面色，见 §4.10）
- `ui/session/drawer/SessionBottomSheet.kt`
- `ui/session/drawer/SessionRowDialogs.kt`（重命名 / 删除确认）
- `ui/session/review/MobileSessionTabs.kt`
- `ui/session/review/MobileUsagePanel.kt`（含 `MobileUsage` 格式化纯逻辑）
- `app/src/test/java/com/xiufeigo/opencodeshell/MobileFormW4Test.kt`（18 个用例）

改动：
- `ui/session/SessionScreen.kt`（形态分支、sheet/侧栏、四 tab、长按菜单接线、`SessionHeader` 新用量参数、`LocalSessionCommandIntents` 接线、`hasPendingRequest`）
- `ui/session/drawer/SessionDrawer.kt`（sheet 形态、行菜单、回调）
- `ui/session/review/ReviewChangesTab.kt`（diff 13sp/24sp、只染背景）
- `ui/session/review/ReviewPanel.kt`（tab 状态提升 + `openPath`）
- `ui/session/review/ReviewFilesTab.kt`（`openPath` 直达预览）
- `ui/session/dock/SessionQuestionDock.kt`（分页/进度段/Back-Next-Submit）
- `res/values{,-zh-rCN,-zh-rTW}/strings.xml`（只追加 `oc_mobile_*`）

---

## 2. 上游锚点 → 实现对照

### 2.1 底部 sheet（交付 1）

上游 `_upstream/packages/app/src/shell/mobile-drawer.css:1-60,62-89`：

| 上游 | 实现 | 文件:行 |
|---|---|---|
| `[data-slot="mobile-drawer-overlay"]` `inset:0` + `overlay-scrim`，240ms fade-in / 200ms fade-out | 全屏 scrim（`overlayScrim`），点击关闭，同 duration | `SessionBottomSheet.kt` |
| `[data-slot="mobile-drawer-content"]` `inset-inline:0; bottom:0; border-radius:16px 16px 0 0; max-height:min(75dvh, calc(100dvh - safe-top - 16px)); padding:0 12px max(12px, safe-bottom); background: bg-deep; box-shadow: elevation-overlay` | 底部对齐 sheet：`RoundedCornerShape(top 16dp)`、`heightIn(max = MobileForm.sheetMaxHeightDp(...))`、`padding(12dp, 12dp, max(12dp, navBarInset))`、`background(bgDeep)`、`ocShadow(elevation.overlay)` | 同上 |
| `transform` 240ms `cubic-bezier(.2,.8,.2,1)`；关闭 200ms | `slideInVertically(tween(240, CubicBezierEasing(.2,.8,.2,1)))` / `slideOutVertically(tween(200))` | 同上 |
| `[data-slot="mobile-drawer-handle"]` h28 居中 + `span` 32×4 radius-999 `border-strong`，`touch-action:none` | 28dp 手柄行 + 32×4dp `borderStrong` 圆条；手柄 `pointerInput` 纵向拖拽：下滑 ≥ `max(64dp, sheet 高 25%)` 关闭，否则回弹 | `SessionBottomSheet.kt`、`MobileForm.shouldDismissByDrag` |
| reduced-motion 关闭动效 | Android 系统动画缩放天然生效（Compose `tween` 受 animator duration scale 影响）——未额外分支，记为可接受 | — |

**形态切换**：`MobileForm.isDesktop(screenWidthDp)`，阈值 **768dp = 上游 `isDesktop()`**（`_upstream/packages/app/src/session/model.ts:35` `createMediaQuery("(min-width: 768px)")`，Lead R1 裁决）。≥768dp 走原 `ModalNavigationDrawer`（左侧、方形、`bgBase`，未改）；<768dp 走 bottom sheet（无 `ModalNavigationDrawer`）。

### 2.2 四个整宽 tab（交付 2）

上游 `_upstream/packages/app/src/session/review/view.tsx:23-117`（`SessionMobileViewTabs`）+ 挂载点 `session/screen.tsx:191-242,290,303-317`：

| 上游 | 实现 |
|---|---|
| `["session","changes","files","terminal"]` 顺序、`Tabs.Trigger class="min-w-0 flex-1"` + `button: w-full justify-center` | `MobileForm.TABS` 四等宽（`Modifier.weight(1f)`），`MobileSessionTabs` |
| `Tabs.List class="!h-9 !gap-0 !px-0"`，容器底部 1px `border-base` | 36dp 条 + 底部 0.5dp `borderBase` 分隔线 |
| 文案 key：`session.tab.session` / `session.review.change` / `session.tab.files` / `terminal.title` | `oc_mobile_tab_session`（新增）/ `oc_review_tab_changes` / `oc_review_tab_files` / `oc_terminal_title` |
| `onSelect("terminal")` → 打开终端面板而非切内容 | `TERMINAL` tab → 内嵌 `EmbeddedTerminalPanel(directory = activeSession.directory, onClose = 切回 Session)`（Lead 提供的内嵌入口；`MobileForm.isContentTab(TERMINAL) == false`） |
| `changes`/`files` 复用 `SessionReview`/`SessionMobileFiles` 的 review 模型 | **直接复用** `ReviewChangesTab` / `ReviewFilesTab`（未重写其逻辑），数据拉取改由 `LaunchedEffect(activeSession.id, reviewOpen, mobileReview)` 触发（与桌面侧栏同一 pull） |
| 移动端不渲染桌面侧栏与头部 review 开关（`screen.tsx:338-348` 仅桌面） | `reviewOpen` 恒为 false 于移动端（开关不渲染、`SessionReviewPanel` 不挂载），移动端靠 tab |

### 2.3 行长按菜单（交付 3）

上游 `_upstream/packages/app/src/home/sessions/view.tsx`：`HOME_SESSION_LONG_PRESS_MS = 500`（`:30`），touch `pointerdown` 起 500ms 计时（`:597-610`），菜单项 Rename / Export… / ─ / Delete…（`:694-701`），删除确认走 `home/sessions/controller.tsx:222-243`。

实现（`ui/session/drawer/SessionDrawer.kt`）：
- 行用 `Modifier.combinedClickable(onClick, onLongClick)`——Android `ViewConfiguration.getLongPressTimeout()` 默认即 500ms，与上游常量一致；`MobileForm.SESSION_ROW_LONG_PRESS_MS = 500` 显式登记并由单测钉住。
- `DropdownMenu`：Rename / 0.5dp 分隔线 / Delete（顺序对齐上游；Export… 属 task-4 的导出功能，未在 W4-C 范围）。
- Rename → `SessionRenameDialog`（预选全文、Enter 保存、空值禁用 Save）→ `ChatViewModel.renameSession`，成功后本地回填抽屉行 + 头部 optimitic title。
- Delete → `SessionDeleteDialog`（危险确认）→ `ChatViewModel.deleteSession(id) { ok -> … }`（data-layer 新增：`OpenCodeClient.deleteSession` = `DELETE /session/:id`，`ChatViewModel.deleteSession` 回调二值）。删除当前会话成功后关闭 sheet 并 `onBack()`；删别的行只移除该行。
- 若将来 `DELETE` 不可用：`SessionDrawerContent` 的 `onDeleteSession` 为空（null）时 Delete 项渲染为**禁用**（`alpha .5`），不隐藏——符合 task-3 的兜底要求。

### 2.4 question dock 分页（交付 4）

上游 `_upstream/packages/app/src/session/requests/session-question-dock.tsx`：

| 上游 | 实现 |
|---|---|
| `store.tab` + `question() = questions()[store.tab]`（`:105,437`） | `QuestionPager.clamp` + 只渲染当前页 `QuestionBlock` |
| 头部 `summary()` = `session.question.progress` = `{{current}} of {{total}} questions`（`:112-115,818`），`Show when={total() > 1}` | `oc_mobile_question_progress` = “%1$d of %2$d questions”，总题数 >1 才显示 |
| `question-progress` 分段：每题一段，`data-active` / `data-answered`，可点击跳页（`:486-502`） | `QuestionProgressSegments`：18×4dp 圆条，active `textAccent` / answered `iconBase` / 其余 `borderStrong`，可点跳页，带 `Question %1$d` 无障碍标签 |
| footer：Dismiss(ghost) + `tab>0` 时 Back(neutral) + 末页 Submit、否则 Next(neutral)（`:514-535`） | 三个按钮：`oc_question_dismiss` / `oc_mobile_question_back` /（末页 `oc_question_submit`，否则 `oc_mobile_question_next`），Next 为 neutral（`button.css:70-75` 的 `bg-button-neutral` = `OcColors.buttonNeutral`） |
| 答案数组按题序对齐（未答给空数组） | 原 `answers()` 逐题逻辑未改 |

### 2.5 diff 渲染（交付 5，session-fidelity 规格）

`ui/session/review/ReviewChangesTab.kt::PatchBlock`：
- `--diffs-font-size: var(--font-size-small)` = 13px → **13sp**（原 11sp）；`--diffs-line-height: 24px` → **24sp**（原 16sp）；等宽字体族未变（`--diffs-font-family: --font-family-mono`）。
- **只染背景不染前景**：add 行 = `textBase` on `stateSuccessBg`；delete 行 = `textBase` on `stateDangerBg`（原来整行文字 `stateSuccessFg`/`stateDangerFg`）；hunk = `textFaint` on `layer01`；context = `textMuted`；每行去掉 0.5dp 纵向 padding（行高交由 24sp）。文件行与汇总的 `+N/-N` 计数仍用状态前景色（计数器，不是 diff 行）。
- 依据：`_upstream/packages/session-ui/src/pierre/index.ts:221-231`（styleVariables）+ `:25-34`（`--diffs-bg-addition/deletion-override` 混色到面板底色，前景保留语法高亮）。

---

## 3. 门控与「移动端形态」的验收方法（重要）

运行期实测（`adb shell dumpsys activity com.xiufeigo.opencodeshell` 的 `mCurrentConfig`）：

| 设备 | px | dpi | 运行期 config | 宽度 | 分支 |
|---|---|---|---|---|---|
| emulator-5554 平板 | 2560×1600 | 320 | `sw800dp w1280dp h800dp land` | **1280dp** | 桌面（≥768） |
| emulator-5556 折叠屏**内屏** | 2076×2152 | 390 | `sw852dp w852dp h883dp port` | **852dp** | **桌面**（≥768，上游语义如此） |

> 折叠屏内屏 852dp 高于上游 768px 断点，因此它显示桌面形态（侧栏 + 左抽屉）是**正确行为**，不是 bug（Lead R1 裁决）。

**移动分支验证方式**（Lead 批准，二选一）：

```powershell
# 方式 A（本报告采用）：临时把折叠屏缩宽到 1080px = 443dp
adb -s emulator-5556 shell wm size 1080x2152      # → w443dp，进入移动分支
# … 截图 …
adb -s emulator-5556 shell wm size reset          # 必须复原
# 方式 B：折叠屏外屏 display 1（1080×2364 ≈ 443dp）投放 app
```

桌面分支证据用平板（1280dp）+ 折叠屏内屏（852dp）各一张截图，证明是侧栏而不是 sheet。

### 3.1 adb 命令（自测步骤）

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$env:JAVA_HOME='C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1'
.\gradlew.bat :app:assembleDebug
& $adb -s emulator-5556 install -r app\build\outputs\apk\debug\app-debug.apk
& $adb -s emulator-5556 reverse tcp:4096 tcp:4096            # 每次装完必须重加
& $adb -s emulator-5556 shell am force-stop com.xiufeigo.opencodeshell
& $adb -s emulator-5556 shell monkey -p com.xiufeigo.opencodeshell 1
& $adb -s emulator-5556 shell screencap -p /sdcard/x.png ; & $adb -s emulator-5556 pull /sdcard/x.png <名字>.png
```

测试会话 `ses_f41f7138cffeB43nHxQAyNE1uf`，server `http://127.0.0.1:4096`。

### 3.2 截图证据（仓库根，实测已全部落地）

移动分支 = 折叠屏 `emulator-5556` + `wm size 1080x2152`（443dp）；桌面分支 = 平板 1280dp / 折叠内屏 852dp。

| 截图 | 证明 |
|---|---|
| `w4c_sheet_open.png` | 底部 sheet 打开：**16dp 上圆角 + 28dp 拖拽柄（32×4 圆条）+ 75% 高 + bg-deep + scrim 变暗**（scrim 像素采样 `(50,300)` 由 255 变暗，见 §4.10） |
| `w4c_sheet_closed.png` | 点 scrim 关闭后回到会话视图（四 tab 仍在） |
| `w4c_sheet_swipe_closed.png` | 拖拽柄**下滑关闭**（swipe 540,610→540,1200）后的状态；像素采样 `(540,600)=255,255,255`（sheet 表面已消失）、`(50,300)=255,255,255`（scrim 已消失） |
| `w4c_tabs_session.png` | 443dp 下四个整宽 tab：会话/变更/文件/终端 + 右侧 `⋯`（会话 tab active 有 accent 下划线） |
| `w4c_tabs_changes.png` | 切到「变更」：复用 `ReviewChangesTab`（该会话无 diff → 空态「还没有变更」） |
| `w4c_tabs_files.png` | 切到「文件」：复用 `ReviewFilesTab` 文件树（该系统会话目录） |
| `w4c_tabs_terminal.png` | 切到「终端」：复用 `EmbeddedTerminalPanel` 正常渲染远端 PTY（`Windows PowerShell … PS C:\Windows\System32>`，tab 头 `Terminal kOTE` + X + `+`） |
| `w4c_longpress_menu.png` | 会话行**长按 500ms** 菜单：重命名 / 分隔线 / 删除（行有按压底色） |
| `w4c_rename_dialog.png` | 重命名对话框：标题「重命名会话」、输入框预选全文、软键盘自动弹出、取消 / 保存 |
| `w4c_delete_dialog.png` | 删除确认：`删除会话?` + 「将删除「android-e2e」及其全部消息，且无法撤销。」+ 取消 / **危险色「删除」** |
| `w4c_usage_menu.png` | `⋯` 菜单：用量（可用）/ 会话详情（禁用，Android 尚无摘要面板） |
| `w4c_usage_panel.png` | 用量视图：上下文用量 3% · Token 33,564 / 1,050,000 · 费用 $0.7696（真实 `limit.context` 分母 + `sessionCost`） |
| `w4c_desktop_foldable_inner.png` | 折叠**内屏 852dp**：左侧抽屉（桌面形态，非底部 sheet） |
| `w4c_desktop_foldable_inner_session.png` | 折叠内屏 852dp（`wm size reset` 之后）：**没有四 tab 条**、头部有 review 开关 + 用量环 → 桌面分支 |
| `w4c_desktop_tablet.png` | 平板 1280dp：会话页桌面形态（无 tab 条、头部 review 开关） |

### 3.3 实测结果与命令

```powershell
# 构建 + 安装（每次都重加 reverse）
$env:JAVA_HOME='C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1'
.\gradlew.bat :app:assembleDebug
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5556 install -r app\build\outputs\apk\debug\app-debug.apk
& $adb -s emulator-5556 reverse tcp:4096 tcp:4096
& $adb -s emulator-5556 shell am force-stop com.xiufeigo.opencodeshell
& $adb -s emulator-5556 shell monkey -p com.xiufeigo.opencodeshell 1
# 窄屏（移动分支）
& $adb -s emulator-5556 shell wm size 1080x2152     # 1080px / 2.4375 = 443dp
# 交互
& $adb -s emulator-5556 shell input tap 400 490     # Home 会话行 → 会话页
& $adb -s emulator-5556 shell input tap 163 193     # ☰ → 底部 sheet
& $adb -s emulator-5556 shell input swipe 540 610 540 1200 500   # 下滑关闭
& $adb -s emulator-5556 shell input swipe 400 825 400 825 900    # 行内长按 500ms+ → 菜单
& $adb -s emulator-5556 shell screencap -p /sdcard/x.png; & $adb -s emulator-5556 pull /sdcard/x.png <名字>.png
# 复原（已验证）
& $adb -s emulator-5556 shell wm size reset         # → Physical size: 2076x2152
```

实测结论：
1. 443dp：底部 sheet（开 / 点 scrim 关 / **下滑关**）、四个整宽 tab 全部可切（含内嵌终端）、行长按菜单 + 重命名/删除对话框、`⋯` 用量视图 —— 全部通过；
2. 852dp（折叠内屏）与 1280dp（平板）：仍是桌面形态（左抽屉 / 无 tab 条 / 头部 review 开关）；
3. `wm size reset` 已复原（`Physical size: 2076x2152`），实测期间无崩溃（终端最初崩溃见 §4.11，Lead 修复后复验通过）。

---

## 4. 偏离与未决项（不臆造）

1. **折叠屏内屏落在桌面分支**：上游 `isDesktop()` = 768px 的必然结果（852dp > 768dp）。移动分支的截图用 `wm size 1080x2152`（443dp）取得；`wm size reset` 已在同一次自测中复原。
2. **移动端 Usage 是 `⋯` 菜单里的视图，不是第五个 tab**（已按 Lead 的结构裁决实现）：`MobileSessionTabs` 右侧 `⋯`（`view.tsx:66-102`、`class="mx-1.5 shrink-0"`）→ `用量`（可用）/ `会话详情`（**禁用**）。用量视图 `MobileUsagePanel` 用已接线的真实数据（`contextLimit` ← `GET /config/providers` 的 `limit.context`、`lastTokenTotal`、`sessionCost`）显示 上下文用量% / Token（含窗口分母）/ 费用 —— 实测截图 `w4c_usage_panel.png`（3% · 33,564 / 1,050,000 · $0.7696）。会话摘要面板（`session.summary.title`）在 Android 尚不存在，故该项渲染为禁用而不是空壳；**未决**：是否在后续 wave 补 `SessionSummaryPanel`。
3. **移动端没有 Usage/Context tab 的第二含义**：`onOpenContext`（session-fidelity 的用量环点击）在移动端指向 review 视图（会话↔变更切换），桌面端打开 review 侧栏；环的绘制/长按 tooltip 归 `ui/session/header/**`。
4. **tab 激活指示器**：上游是 `Tabs variant="line"`；本实现沿用项目既有 review tab 的视觉（13/500 + 2dp accent 下划线，`ReviewPanel.ReviewTabButton` 同款），未逐像素对齐 line variant 的 CSS（上游该 CSS 不在本次 sparse checkout 内）。属可辨但轻微。
5. **抽屉 sheet 的列表分组**：上游抽屉列表带 Today/Yesterday/Older 分组头（`view.tsx:148-156`），Android 抽屉是扁平列表（审计 §6 已记）。W4-C 未改分组（不在 task-3 交付内）。
6. **`MobileForm.Tab.TERMINAL`**：上游点 terminal tab 是「打开底部终端面板」；Android 用 Lead 的内嵌面板直接渲染在同一区域（等价效果），不是路由跳转。
7. **`ui/session/dock/SessionQuestionDock.kt` 越界说明**：该文件未列入 task-3 的 `writeScopes`，但交付 4（question dock 分页）只可能落在它里面，且 `dock/**` 未被任何 teammate 的写作用域覆盖；改动仅限分页/进度段/按钮，未动权限 dock。已在本报告与完成消息中登记。
8. **`ReviewPanelTab` 状态提升**：为 shell-wiring 的 `file.open`/`fileTree.toggle`（切 FILES tab）把 `ReviewPanel.kt` 的 tab 状态从内部 `rememberSaveable` 提升为参数（`tab` / `onTabChange`），`ReviewFilesTab` 新增 `openPath`（`file.open` 直接进该文件预览，Back 回文件树）。默认参数保持旧行为。**已回信 shell-wiring**：三项接线（`review.toggle` / `fileTree.toggle` / `file.open`）全部落地。
9. **question dock 分页无真机截图**：分页/进度段需要服务器下发一个**多题 question 请求**才会出现；本次会话的两个测试 session 都没有 pending question，触发它需要驱动 agent 调 question 工具（有干扰其他 teammate 证据采集的风险，未做）。**验证方式**：`MobileFormW4Test` 的 6 个 `QuestionPager` 用例（clamp / Back / Next / 最后一页 Submit / 进度号 / 分段状态 / 是否已答）+ 与上游 `session-question-dock.tsx:84-119,437,486-533` 的逐行对照。**未决**：如 Lead 需要一个真机多题截图，需要在 server 侧构造一个 question 请求（或用一个会提问的 agent）。
10. **【跨人/主题级发现】`OcColors` 的 8 位 hex token 全部解析错误**（`ui/theme/OcTheme.kt:119-123,176-180`）：token 写成 CSS 顺序 `#RRGGBBAA`，而 `android.graphics.Color.parseColor` 只接受 `#AARRGGBB`。后果（实测）：亮色下 `borderBase="#0000001a"`、`borderMuted="#00000014"`、`borderStrong="#00000033"`、`overlayScrim="#00000099"` 的 **alpha 都是 0 → 全部透明**（底部 sheet 的拖拽柄第一次渲染时完全看不见；像素采样 `(540,600)=250,250,250` 即 sheet 表面本身，与 `borderStrong` 无关；scrim 也不变暗）；暗色下这些 token 反而变成 **不透明错色**（`#ffffff33` = rgb(255,255,51) 黄色 1px 线）。W4-C 的处理：新增 `ui/session/drawer/MobileColors.kt`，用**正确 ARGB 值**承载上游 `border-base`(10%) / `border-strong`(20%) / `border-muted`(8%) / `overlay-scrim`(60% 黑)，只作用于移动端形态的组件（sheet / tab 条 / 用量视图 / 抽屉分隔线 / 两个对话框），未改主题文件。**建议**：把 `OcTheme.kt` 的 8 位 token 改成 `#AARRGGBB`（如 `borderBase = "#1a000000"`）或换成统一 helper；改完本文件可以退回用 token。已发消息给 Lead。
11. **终端内嵌最初崩溃（已修复，非本任务引入）**：`ui/terminal/TerminalPanel.kt` 的 `AndroidView(factory)` 里先 `setTypeface` 后 `setTextSize` → `TerminalView.java:517` 读 `mRenderer.mTextSize` NPE（`mRenderer` 由 `setTextSize` 创建）。**旧入口 `Route.TERMINAL` 同样必崩**（两条日志可对照），所以与「四 tab」改动无关。Lead 修复（先 `setTextSize`）后，我用最新 APK 复验：终端 tab 正常渲染远端 PTY（`w4c_tabs_terminal.png`），logcat 无 FATAL。

---

## 5. 构建门与单测

```powershell
$env:JAVA_HOME='C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1'
.\gradlew.bat :app:assembleDebug          # BUILD SUCCESSFUL（用于设备实测的 APK）
.\gradlew.bat :app:testDebugUnitTest      # BUILD SUCCESSFUL
```

最终结果（`app/build/test-results/testDebugUnitTest/*.xml` 汇总）：**70 suites / 628 tests / 0 failures / 0 errors / 0 skipped**（起点基线 537；W4-B/W4-A 的新用例 + 本任务 18 个）。

本任务新增的纯逻辑测试 `app/src/test/java/com/xiufeigo/opencodeshell/MobileFormW4Test.kt` = **18/18 绿**：

| 分组 | 用例 |
|---|---|
| 形态门控 | `desktopGateIsUpstream768`（767 false / 768 true）、`measuredDevicesLandOnTheDocumentedBranch`（1280/852 桌面、443 移动） |
| sheet 度量 | `sheetHeightIsTheMinOfTheTwoUpstreamTerms`（662 / 44 / clamp 1）、`sheetGeometryMatchesMobileDrawerCss`（16/28/32/4/12/240/200）、`swipeThresholdHasAFloorAndScalesWithTheSheet` |
| 四 tab | `viewTabsAreTheFourUpstreamOnesInOrder`、`savedTabRoundTripsAndFallsBack`、`terminalTabNavigatesInsteadOfRenderingContent` |
| 长按 | `longPressTimeoutMatchesTheUpstreamConstant`（500ms） |
| 提问分页 | `pagerClampsToTheQuestionList`、`pagerTracksFirstAndLastPage`、`pagerWalksForwardAndBack`、`progressHeaderIsOneBasedAndHiddenForSingleQuestions`、`segmentsMarkActiveAndAnsweredPages`、`answeredQuestionNeedsAPickOrCustomText` |
| 用量格式化 | `usagePercentIsClampedLikeUptream`、`usageTokensShowTheModelWindowWhenKnown`、`usageCostKeepsFourDecimals` |

期间遇到的两类**非本任务**阻塞（均已解除，记录备查）：
1. `ShellWiringTest.kt` 缺 4 个 import → 整个单测编译单元失败（shell-wiring 5 分钟内修复）；
2. `RawPartsTest` / `MessageMetaTest` / `TimelineGroupingTest` 5 条失败（session-fidelity 与 data-layer 的在途改动，随后修复）。

## 6. 未决项（交给 Lead）

1. **question dock 多题分页的真机截图**：需要服务器下发一个多题 question 请求（见 §4.9）。逻辑有 6 个单测 + 上游逐行对照，但没有设备截图。
2. **`OcColors` 8 位 hex token 的修法**（§4.10）：我用了局部 `MobileColors` 兜底；是否把 `OcTheme.kt` 的 token 改成 `#AARRGGBB`（或统一 helper）由 Lead 决定，改完可以把 `MobileColors` 退回用 token。
3. **`会话详情`（`session.summary.title`）目前是禁用项**：Android 无 `SessionSummaryPanel`；是否补属后续 wave 范围。
4. **移动端 tab 的 line 指示器**未逐像素对齐上游 CSS（上游该 CSS 不在 sparse checkout 内，见 §4.4）。
