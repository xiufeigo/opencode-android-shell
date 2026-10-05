# UX5 — 修「+」选择面板两个动作：打开文件透传上游「+」原行为、打开终端真的把终端打开

> 状态：**进行中**（2026-10-05）
> 任务来源：产品负责人实测打回 —— ①打开终端按了没正常打开终端；②打开文件变成了"选平板上的文件"（Android SAF），
> 要求的"打开文件"是**原本点右侧栏「+」号所触发的上游功能**。
> 上一轮实现：UX3（`research/web/UX3_PLUS_POSITION_FIX.md`）—— 面板挂点（右侧栏审查条「+」）是对的，保留；
> 但两项动作实现错：打开文件=宿主原生 SAF pickFile；打开终端=passThrough 点终端条「New terminal」。

## 0. 工作日志（每步写盘）

- 18:56 开始。读完 UX3 报告 + oc-host.js 现状 + 上游 v2 源码关键位。
- 19:05 环境：无 gradle 锁；4095 通；无在跑模拟器。建 `OC_UX5_Phone`（android-37.2 ps16k, pixel_6）。
- 19:08 起 OC_Deliver_R2@6590 + OC_UX5_Phone@6592；手机档首次启动卡死（WHPX 后无进展），杀掉（自建 OC_* 允许）稍后重试；平板 15s 就绪。
- 19:15 平板档：装当前 debug APK（UX4 字节体 a7377e069…）、pm clear、配口令连上 4095，官方 UI 加载（127.0.0.1:40495 代理）。
- 19:20 探针（oc_debug_eval，CSS px，dpr=1.5）：会话页 tablists=0，无终端条；唯一审查入口=Toggle review@(1622,78)。
- 19:24 真点 Toggle review（native 2454,138）→ 审查条出现：[Toggle file tree][Files Changed][Open file「+」@CSS(800,80)]。
- 19:27 **缺陷B复现**：真点「+」→面板弹出（截图 ux5_t11）；真点「打开文件」→ **Android SAF 系统选择器弹出**（ux5_t15_openfile_saf.png）。
- 19:29 **缺陷A复现**：真点「打开终端」→ **什么都没发生**；logcat 原文 `[oc-plus-chooser] no upstream terminal trigger found`（ux5_t16）。根因：终端面板收起时终端条不在 DOM，finder 找不到按钮。
- 19:30 **上游「+」原行为取证（注入）**：给上游 Open file 按钮打 data-oc-plus-chooser=1 绕过自家拦截后 .click() → 审查条出现「Open file」标签页 + 工作区文件树（Filter files/目录列表）——即"原本点 + 的功能"（ux5_t17_upstream_openfile.png）。
- 19:54 修复后平板档实测（全部**真实点击**，修复版 debug APK）：
  - 真点「+」⇒ 面板弹出（ux5_t21）；
  - 终端面板**收起态**真点「打开终端」⇒ logcat `upstream terminal.new keybind dispatched (ctrl+alt+t); panel closed case` ⇒ **终端面板出现 + Terminal 1 自动创建 + 活 PowerShell 提示符**（ux5_t22）；敲 `pwd` 有回显（Path/----/C:\sensorsdata\...，ux5_t25）——**终端真的打开且可用**；
  - 终端面板**已开态**真点「打开终端」⇒ logcat `upstream New terminal re-clicked via passThrough (panel already open)` ⇒ **Terminal 2** 创建并激活（ux5_t27）；
  - 真点「打开文件」⇒ logcat `upstream plus re-clicked via passThrough (open file)` ⇒ **审查条出现「Open file」标签页 + 应用内文件浏览器**（ux5_t26）；`dumpsys topResumedActivity` = 自家 WebHostActivity——**没有 SAF**；
  - 顶栏「+」回归：真点 ⇒ 顶栏出现上游「Session」草稿标签、**面板未弹、无 chooser 日志**（ux5_t28）。
- 20:03 修复后手机档实测（OC_UX5_Phone 1080×2400@420，全部真实点击）：
  - 首启灰态 + 「去设置服务器」+ 口令保存连接 ⇒ 官方 UI 首页（ux5_p1/p2）；
  - 真点终端条「+」⇒ 面板弹出（ux5_p5）；
  - 真点「打开终端」⇒ **Terminal 2** 创建、活 PowerShell、键盘弹出（ux5_p6）；
  - 真点「打开文件」⇒ 透传点击锚定「+」（手机档该「+」= 终端条 New terminal，审查条「+」仅桌面形态存在——v2 源码 SessionSidePanel 桌面限定 session-side-panel.tsx:257）⇒ **Terminal 3** 创建（ux5_p8），topActivity = 自家 WebHostActivity——**无 SAF**。
- 20:10 单测：`:webapp:testDebugUnitTest :app:testDebugUnitTest` ⇒ BUILD SUCCESSFUL；**webapp 218 / app 6136 / 合计 6354，0 失败 0 错误**（UX3 时 webapp 207 ⇒ 只增不减；未删/放宽任何既有断言）。
- 20:12 三份产物重建 + 落 `research/deliver/` + 含源自查 3/3 MATCH + 签名 CN=Android Debug（SHA-256 e86b7006…0510a）。
- 20:14 HANDOFF_WEB_SHELL.md §0.1 顶表 + 新增「UX5 复核」块已同步，UX4 及更早全部旧值显式作废。

## 2. 缺陷复现（修复前，UX4 字节体，平板档真点）

| 动作 | 现象 | 证据 |
|---|---|---|
| 真点「+」 | 面板正常弹出（这部分 UX3 是对的，保留） | `research/web/ux5_t11_plus_panel.png` |
| 真点「打开文件」 | **Android 系统文件选择器（SAF，Recent/Drive/Media picker/Photos）弹出**——产品负责人抱怨的"选我平板的文件" | `research/web/ux5_t15_openfile_saf.png` |
| 真点「打开终端」（终端面板收起态） | **什么都没发生**；logcat 原文 `10-05 11:29:03.659 ... [oc-plus-chooser] no upstream terminal trigger found; dispatched oc:open-terminal` | `research/web/ux5_t16_openterminal_bug.png` |

根因（打开终端）：终端面板收起时终端条**根本不在 DOM**（探针实测 `tablists=0`），
旧实现只 passThrough 点终端条「New terminal」按钮 ⇒ finder 找不到 ⇒ 静默失败。
且面板收起但缓存挂载（零高）时 `offsetParent` 仍非 null，旧 finder 的可见性判据也不可靠。

## 3. 修复后设备证据（全部为**真实点击** `adb shell input tap`；仅 §1 取证两步注明为注入）

### 平板档 emulator-6590（OC_Deliver_R2，2506×1673 @240dpi，dpr1.5，WebView 124）

| 步骤 | 结果 | 截图 / 日志 |
|---|---|---|
| 真点「+」 | 面板弹出（打开/打开终端/打开文件，浅色跟随主题） | `ux5_t21_panel_open_fixed.png` |
| 真点「打开终端」（**终端面板收起态**，走新增的上游 terminal.new 键位路） | 终端面板出现 + **Terminal 1 自动创建** + 活 PowerShell 提示符；logcat `upstream terminal.new keybind dispatched (ctrl+alt+t); panel closed case` | `ux5_t22_terminal_opened.png` |
| 在终端里敲 `pwd`（回显证明可用） | 终端显示 `Path / ---- / C:\sensorsdata\main\program\opencode-android-shell` | `ux5_t25_terminal_usable.png`（另有 echo 交互 `ux5_t23/t24`） |
| 真点「打开终端」（**终端面板已开态**，走 passThrough 点上游「New terminal」路） | **Terminal 2** 创建并激活；logcat `upstream New terminal re-clicked via passThrough (panel already open)` | `ux5_t27_terminal2.png` |
| 真点「打开文件」 | **审查条出现「Open file」标签页 + 工作区应用内文件浏览器**（与注入直点上游「+」的 ux5_t17 完全一致）；logcat `upstream plus re-clicked via passThrough (open file)`；**topResumedActivity = 自家 WebHostActivity（无系统选择器）** | `ux5_t26_openfile_fixed.png` |
| 真点**顶栏**「+」回归 | 顶栏出现上游「Session」草稿标签；**面板未弹、无 chooser 日志** | `ux5_t28_topbar_plus.png` |

### 手机档 emulator-6592（OC_UX5_Phone，1080×2400 @420dpi，dpr2.625，移动形态 411dp）

| 步骤 | 结果 | 截图 / 日志 |
|---|---|---|
| 首启灰态 →「去设置服务器」→ 口令保存 | 未配置灰态 + 入口正常（既有验收项回归） | `ux5_p1_launch.png` `ux5_p2_home.png` |
| 真点终端条「+」（手机档审查条「+」不存在，锚定终端条尾部「+」——与 UX3 一致） | 面板弹出 | `ux5_p5_panel.png` |
| 真点「打开终端」 | **Terminal 2** 创建、活 PowerShell、键盘弹出；logcat `upstream New terminal re-clicked via passThrough (panel already open)` | `ux5_p6_terminal2.png` |
| 真点「打开文件」 | 透传点击同一个锚定「+」⇒ 上游原行为（=新建终端标签，Terminal 3）；**topActivity = 自家 WebHostActivity，无 SAF** | `ux5_p8_openfile.png` |

**注入步骤声明**：§1 中"上游 Open file 原行为取证"（给按钮打 `data-oc-plus-chooser=1` 绕过自家
拦截后 `.click()`，ux5_t17）与"合成 ctrl+` 验证上游键位派发"（ux5_t18）是**注入**；
其余所有 "tap" 均为真实点击。修复后未再用任何注入完成动作——面板两项动作都是
真实点击驱动。

## 4. 修改内容（diff 摘要）

- 生产文件仅一个：`webapp/src/main/assets/www/oc-host.js`（+100/−32 行；`git diff --stat`）。
  宿主 Kotlin **零改动**。
- 「打开文件」`runFile()`：删除宿主 `pickFile` 路径 ⇒ `findPlusButton()` 找回被拦截的上游「+」
  → `passThrough=true` → `btn.click()`（构造上即"直接点 +"）。
- 「打开终端」`runTerminal()`：
  ① 新增 `terminalTriggerUsable()`（`offsetParent` + `getBoundingClientRect` 真实宽高双判据）；
  ② 触发器可见 ⇒ passThrough 点上游「New terminal」（原 UX3 行为，保留）；
  ③ 不可见/不存在 ⇒ `dispatchUpstreamKey("t", true, true)` 派**上游自己的 terminal.new 键位**
   （默认 ctrl+alt+t = openTerminal 序列）+ 900ms 后自检终端面板真的出现
   （`oc:open-terminal` 事件回读 `found` 标志；失败如实打日志，不装成功）。
- 新增绊线 `webapp/src/test/.../Ux5ChooserActionsTripwireTest.kt`（5 用例）。

## 5. 绊线与反向验证

绊线断言（静态扫描 `oc-host.js` 的 installPlusChooser 段）：
1. 「打开文件」必须 `findPlusButton()` + `passThrough`，且该段**不得出现** `pickFile(` 调用；
2. 「打开终端」必须两条上游路：rect 宽高可见性判据 + `dispatchUpstreamKey("t", true, true)`
   上游键位路（含 `KeyboardEvent("keydown"` 与 `oc:open-terminal` 自检）；
3. 反向样本（宿主 SAF 版 runFile / 只点按钮版 runTerminal）必须被判红（防假绿）。

**反向验证实测**（把 HEAD 旧 oc-host.js 喂同一扫描器）：
```
Ux5ChooserActionsTripwireTest > open file hands the click back to upstream plus, never host SAF FAILED
  AssertionError: 「打开文件」又调用了宿主原生 pickFile ……
Ux5ChooserActionsTripwireTest > open terminal must really open a terminal via upstream FAILED
  AssertionError: 终端触发器可见性必须用 getBoundingClientRect 的真实宽高判定 ……
```
⇒ 撤回修复时两条可断言量**真红**；新接线 5/5 绿（webapp 全套 218 绿）。

## 6. 已验收项回归（本轮 Kotlin 零改动，逻辑等价/单测覆盖）

| 项 | 状态 | 证据 |
|---|---|---|
| 面板位置（右侧栏审查条「+」） | ✅ 未动（finder 原文未改）；平板/手机真点都弹在正确挂点 | ux5_t21 / ux5_p5 |
| 面板配色跟随主题 | ✅ 未动；本轮截图均为浅色主题下正常渲染 | ux5_t21 |
| 顶栏「+」恢复上游原行为 | ✅ 真点 ⇒ Session 草稿标签、面板不弹 | ux5_t28 |
| 未配置灰态 +「去设置服务器」 | ✅ 手机档首启全程走通（平板档本轮早些时候亦验证） | ux5_p1/p2 |
| 空口令不启动代理（F21） | ✅ Kotlin 零改动；`EmptyPasswordLaunchGateTest` 在 6136 内全绿 | §7 |
| 终端杀 app 后续接（UX2） | ✅ Kotlin 零改动；`TerminalReattachTest` 在 218 内全绿 | §7 |

## 7. 单测

`.\gradlew.bat --console=plain :webapp:testDebugUnitTest :app:testDebugUnitTest` ⇒ **BUILD SUCCESSFUL，0 失败 0 错误**。
计数（test-results XML 汇总）：**webapp 218（+5 新增 UX5 绊线），app 6136，合计 6354**。
（UX3 时 webapp 207 ⇒ 用例数只增不减；未删/放宽任何既有断言；既有 UX3 绊线 5 条仍全绿。）

## 8. 三份新产物（`research/deliver/`）

| 产物 | 体积 | md5（当前有效） | 签名 |
|---|---|---|---|
| `opencode-shell-web.apk`（debug） | 5,438,212 B | `feae3854244badff58820607fe1d6913` | CN=Android Debug，证书 SHA-256 `e86b70066546bd0bdc2e6cb4d970704bc48b1a244af59c664564f030cca0510a` |
| `opencode-shell-web-release-nor8.apk` | 3,994,336 B | `a3a88537abdfab20b481e99ff2a92c19` | 同上（既有 sharedDebug signingConfig） |
| `opencode-shell-web-release-r8.apk`（releaseR8 变体） | 1,449,744 B | `94baad39812fa5e08a8e5fe386bd6632` | 同上 |

**包内含源自查**：三份 APK 解包 `assets/www/oc-host.js` 的 md5 均为 `ea950f4a8f061c67a07270787cf4a239`，
与工作区 `webapp/src/main/assets/www/oc-host.js` **逐位一致**（3/3 MATCH=True，ZipFile 解包实测）。

## 9. 交接文档同步

**已同步 `HANDOFF_WEB_SHELL.md` §0.1**：顶表三行更新为 UX5 新体积/md5 并标注"UX5 重建值"；
新增「UX5 复核」块（含修法说明、设备证据索引、包内含源自查值），并**显式作废** UX4 批
`9fb24886…`/`f3bee4f9…`/`a7377e06…`、UX3/UX2/F19/F18 及更早全部旧值。

## 10. 收尾复核输出（逐字）

```
qemu processes: 只剩 DSH_T102_Phone / DSH_T102_Tablet / DSH_T101_Tablet（均为负责人实例，
                本轮从未 touch；OC_Deliver_R2 / OC_UX5_Phone 进程已按 PID 精确匹配杀掉）
adb devices:    emulator-5610 / 5612 / 5740（= 上述 3 台负责人实例，非自建；本轮自建 0 台在线）
AVD OC_UX5_Phone: deleted（本轮自建，随收尾回收）
port 6590: FREE
port 6592: FREE
gradle lock released             （rm -- .agent-gradle-lock；lock exists: False）
```
补充：收尾时负责人的 `DSH_T*` 三台实例正好在跑（收尾前 adb devices 曾为空），本轮对它们
只有 `adb devices`/`dumpsys` 只读观察，无 tap/install/kill。

## 11. 本轮碰过的 serial + 是否碰过非自建实例

- 碰过：**emulator-6590**（AVD `OC_Deliver_R2`，复用自建，收尾已关）、**emulator-6592**（AVD `OC_UX5_Phone`，
  本轮自建，android-35 tablet 镜像 + pixel_6 机型 + `wm 1080x2400/420` 手机几何；ps16k 手机镜像本轮两次
  起不来（WHPX 激活后卡死，改镜像规避），收尾已关并已删 AVD 回收磁盘）。
- 未碰任何非自建实例：负责人各实例无 tap/install/kill；仅 `adb devices` 只读观察。
  后端 127.0.0.1:4095 未起停，仅 `adb reverse tcp:4095`。

## 12. 未闭环项

- 手机档「+」锚在终端条尾部按钮（审查条「+」为桌面形态限定，v2 源码 SessionSidePanel `<Show when={isDesktop()}>`），
  故手机档「打开文件」的上游原行为 = 点终端条「+」（新建终端标签）；与 UX3 既有取舍一致，如实标注。
- 「打开终端」键位路依赖上游默认键位 ctrl+alt+t；若用户在上游设置里改绑 `terminal.new` 且终端面板收起，
  自检会如实报 `found:false`（日志可见），不会装成功。设备实证默认键位下两档全通。


## 1. 上游源码证据（`_upstream` = v2 @702a73d）

- 审查条「+」（**唯一挂点，产品负责人指定**）：`packages/app/src/session/files/session-side-panel.tsx:458-536`
  - 无浏览器能力时 = 一键 "Open file" 按钮（:475-482，`onClick={() => openFileBrowser()}`）；
  - 有浏览器能力时 = "Add tab" 菜单（:490-533，打开文件 :503-516 + 浏览器 :517-530）。
  - `openFileBrowser()`（:205-208）= `previewTab(SESSION_OPEN_FILE_TAB)` → 打开**应用内文件浏览器标签页**（设备实证 ux5_t17）。
- 终端条「+」：`packages/app/src/session/terminal/panel.tsx:330-335` → `onClick={() => terminal.new()}`（只建 PTY 标签，**不开面板**）。
- 终端面板开合：`view().terminal.opened()`（panel.tsx:58）；面板开且 0 终端时自动建 Terminal 1（panel.tsx:105-115）。
- 上游自己的「打开终端」动作：`packages/app/src/session/commands/use-session-commands.tsx:195-199 openTerminal()`
  = `view().terminal.open()` + （有终端则 `terminal.new()`）；绑定在 `terminal.new` 命令（:402-408，默认键位 `ctrl+alt+t`）。
- 键位派发器：`packages/app/src/shell/commands/command.tsx:401-428` —— document keydown 捕获监听，
  **只看 event.key/ctrlKey/altKey，无 isTrusted 检查** ⇒ 合成 KeyboardEvent 能触发上游自己的命令（设备实证 ux5_t18 用 ctrl+` 验证）。

