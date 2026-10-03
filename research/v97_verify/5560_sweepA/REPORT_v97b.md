# v97b 真机扫测报告 — emulator-5560 (Pixel_Tablet, 2560x1600 @320dpi)

> 任务：v97 上轮（`REPORT.md`，覆盖到 Home §1.15）**未做域**的补扫 —— 导航与标签 / 命令面板 / 设置各面板 / Home 最近关闭 / 回归抽验。
> **明确排除**：`编辑项目` 面板（⋮→编辑项目，Lead 并行修复中）与 `AgentChip /agent` 重弹 —— 本轮不测。
> 测试员：v97b sweepA。证据目录：同 `research/v97_verify/5560_sweepA/`，文件前缀 `b*`。

## 环境读数

| 项 | 命令 | 值 |
|---|---|---|
| 设备核验 | `adb -s emulator-5560 emu avd name` | **Pixel_Tablet** ✅ 与任务书一致 |
| 系统版本 | `adb -s emulator-5560 shell getprop ro.build.version.release` | **15** |
| 屏幕 | `adb -s emulator-5560 shell wm size` | **Physical size: 2560x1600**（本轮**未改** `wm size`） |
| 密度 | `adb -s emulator-5560 shell wm density` | **Physical density: 320**（本轮**未改**） |
| APK | `dumpsys package com.xiufeigo.opencodeshell` | `versionName=1.4.0`，**`lastUpdateTime=2026-10-02 10:01:35`** ✅ 与任务书所述 18:01 新构建一致（复核通过） |
| 后端健康 | `GET /api/info` (Basic `opencode:opencode`) | **HTTP 200** `{"version":"2.0.18","pid":260,...}` |
| reverse 隧道 | `adb -s emulator-5560 reverse --list` | `tcp:4095 tcp:4095`、`tcp:2222 tcp:22` 均在 |
| IME | `settings get secure show_ime_with_hard_keyboard` | `1` |

> 纪律自检：本轮**未**跑 gradle、**未**装机、**未**杀进程、**未**碰 `127.0.0.1:5555`；**未**按 BACK(`keyevent 4`)；设置/表单弹层内**未**按 ESC(`keyevent 111`)；截图全部 `screencap -p` + `pull`（无 `exec-out`）；`wm size`/`wm density` 未改 → 无需 reset。
> 输入纪律：空格一律 `keyevent 62`，**未**用 `%s`；文本 ≤8 字符分段 + 段间 1.5s sleep（见 `helpers_b.ps1`）。

## 0. 启动基线

![b00_boot](b00_boot.png)

- `am force-stop` → `am start -n com.xiufeigo.opencodeshell/.OcActivity` → `mCurrentFocus=...OcActivity` ✅
- Home 渲染正常：项目列 8 行（mighty-river / v27projC / sensorsdata / .copilot / E2ECHK_renamed / opencode-android-… / E2ECHK_xiufeigo / `/`），会话列「今天 / 更早」分组正常。
- 顶部标签条 3 按钮（XML 实测坐标）：
  - `首页` = `[16,36][112,132]`（⛶ 图标）
  - `新建会话` = `[116,36][184,132]`（+ 图标）
  - `命令面板` = `[184,36][280,132]`（`>_` 图标）
- 抽屉底部：`设置` `[248,1316][784,1380]`、`帮助` `[248,1380][784,1476]`

---

## 逐条判定表

> 判定：**PASS** / **FAIL** / **未验到**。


### 1. 导航与标签（桌面形态 DesktopTabStrip）

| # | 断言 | 判定 | 命令 | 证据 | 读数 |
|---|---|---|---|---|---|
| 1.1 | 桌面标签条渲染（Home / 新建会话 / 命令面板 3 槽） | PASS | `am force-stop` → `am start .OcActivity` | [b00_boot.png](b00_boot.png) [b00_boot.xml](b00_boot.xml) | XML：`首页`=`[16,36][112,132]`、`新建会话`=`[116,36][184,132]`、`命令面板`=`[184,36][280,132]`，全部 `clickable=true enabled=true`。首屏无 tab（`openSessions` 空）→ 3 槽即「Home 按下 + `+` + palette」形态 |
| 1.2 | 标签条 `+` → 新建会话草稿 route + `<` 返回层级 | PASS | `input tap 150 84` → `input tap 56 200` | [b01_newsession_route.png](b01_newsession_route.png) [b02_back_to_home.png](b02_back_to_home.png) | `+` → NEW_SESSION route，居中表单「新建会话 / 会话摘要 / `.cache` 目录选择器 / `master` / `创建会话`」；左上 `<` = `返回` `[8,152][104,248]`，点击后回 Home（项目列 8 行 + 会话列分组全部复原） |
| 1.3 | 标签**新建**：`创建会话` → session route + 新 tab 落条 | PASS | `tap 1302 960` | [b03_session_created.png](b03_session_created.png) [b03_session_created.xml](b03_session_created.xml) | 出现 tab `Untitled` = `[124,36][267,132]`，其 `✕` = `关闭标签页` `[203,36][267,132]`；会话页标题 `Untitled`、空态「构建任何内容」、composer 就位 |
| 1.4 | 标签**重命名**：长按 → 菜单 → 内联输入框 | PASS | `input swipe 160 84 166 84 1800`（长按）→ `tap 188 176` | [b04c_longpress_menu.png](b04c_longpress_menu.png) [b05_rename_field.xml](b05_rename_field.xml) | 长按弹出 `DropdownMenu`，**唯一项 `重命名`** `[148,156][228,196]`；点击后**原位**替换为 `EditText` `[136,36][263,132]` `text="Untitled"` **`enabled=true focused=true`**（对比上轮 `编辑项目` 面板两个 EditText 均 `enabled=false`——本条是绿的） |
| 1.5 | 重命名**生效**（标签条 + 会话页标题同帧更新） | PASS | `keycombination 113 29`(全选) → 分段输入 → `keyevent 66`(Enter) | [b06d_rename_typed3.xml](b06d_rename_typed3.xml) [b07_rename_done.png](b07_rename_done.png) | 字段逐字达到 `E2ECHK5560-b-t1`；Enter 确认后**同一帧**标签条 `E2ECHK5560-b-t1 ×`（槽宽 124→397px 自适应）**且**会话页 header 标题同步为 `E2ECHK5560-b-t1` |

> **环境插曲（非应用缺陷，已隔离）**：本机 Gboard 处于**中文拼音**态（开机 toast「已将键盘语言更改为中文（简体）（拼音）」），`input text E2ECHK5560-b-t1` 落进 `TabRenameField` 后被拼音引擎改写成 `E2ECHKDSK556-不-0`。`adb shell pm clear com.google.android.inputmethod.latin`（**设备本地**归一化，非应用改动）恢复拉丁输入后，同一字段逐字正确。证据：[b06_rename_typed.png](b06_rename_typed.png)。后续全部输入均在拉丁态下进行。

| 1.6 | 标签**切换**（点 tab 切 route，双向） | PASS | `tap 230 84` → `tap 500 84` | [b13_switch_to_tab1.png](b13_switch_to_tab1.png) [b14_switch_to_tab2.png](b14_switch_to_tab2.png) | 2 tab 时 tab1=`[124,36][405,132]`、tab2=`[432,36][575,132]`（各自带 `✕` `关闭标签页`）。点 tab1 → header 标题 `E2ECHK5560-b-t1`；点 tab2 → header 标题 `Untitled`。**双向都换 route** |
| 1.7 | 标签**关闭**（`✕`，且只关被点的那一个） | PASS | `tap 375 84`（tab1 的 ✕ `[333,36][417,132]`） | [b15_close_tab1.png](b15_close_tab1.png) | 关闭后全 XML 中 `E2ECHK5560-b-t1` 出现次数 = **0**（标签条与 header 同时清掉）；当前 route **保持在 tab2**（header 仍 `Untitled`），未被误关 |
| 1.8 | **tab / route 持久化（重启 app 回到上次 route）** | **FAIL** | 见「新发现 N-1」 | [b17_open_session_from_home.png](b17_open_session_from_home.png) [b18_restore_t3s.png](b18_restore_t3s.png) [b19_restore_t9s.xml](b19_restore_t9s.xml) [b24_r1.xml](b24_r1.xml) … [b24_r8.xml](b24_r8.xml) | **写入侧正常**、**读回侧失效**。见下方 N-1 详述 |

### N-1（新发现 / BUG）：冷启动不恢复上次会话 route —— `LastRouteStore` 写对了，`NavRestore` 没把它读回来

**现象**：在任意会话 route 上 `am force-stop` → `am start`，应用**直接落在 Home**，标签条为空，上次会话既没恢复 route 也没恢复 tab。

**最小复现步骤**（本机 emulator-5560，3 次独立复现）：

```
# 1) 进任意会话（Home 会话列首行）
adb -s emulator-5560 shell input tap 1592 496 ; sleep 6
# 2) 读落盘值 —— 写入侧正确
adb -s emulator-5560 shell run-as com.xiufeigo.opencodeshell \
    cat /data/data/com.xiufeigo.opencodeshell/shared_prefs/oc_nav_state.xml
#   -> <string name="oc_last_route">session</string>
#      <string name="oc_last_session_id">ses_f03e2d5b8ffeBCDbQGqZHCliRd</string>
# 3) 杀进程，落盘值仍在
adb -s emulator-5560 shell am force-stop com.xiufeigo.opencodeshell ; sleep 2
#   -> 同上，session + id 未丢
# 4) 冷启动并连续抓 UI 树
adb -s emulator-5560 shell am start -n com.xiufeigo.opencodeshell/.OcActivity
for i in 1..8; do adb -s emulator-5560 shell uiautomator dump /sdcard/win.xml; adb pull ...; done
#   -> r1..r8 全部：HOME（`添加项目` 在），无 `关闭标签页`（= 无 tab），无会话 composer
# 5) 结束后再读落盘值
#   -> <string name="oc_last_route">home</string>     <-- 被改写回 home
```

**读数（决定性）**：
- 落盘值在 `force-stop` **之后仍然正确**（`oc_last_route=session` + 合法 session id `ses_f03e2d5b8ffeBCDbQGqZHCliRd`）→ **写入路径 `LastRouteStore.save()` 正常**。
- 冷启动的**第 1 棵树**（`b24_r1.xml`）就已经是 Home，之后 8 棵全是 Home → 会话 route **从未成为当前 destination**。
- 冷启动结束后落盘值被改写成 `oc_last_route=home` → `NavRestore.toStore(HOME, null)` 确实被调用过，即 NavHost 只发出了 HOME。
- 对照组：把落盘值置为 `home` 时冷启动同样直接是 Home（`b23_d1..d8`），即**两种情况无法区分**——恢复路径对 `session` 与 `home` 表现完全相同，说明 `target != Route.HOME` 的分支**从未走到**。

**源码锚**（只读定位，未改任何代码）：
- `ui/nav/LastRouteStore.kt:23-37` `LastRouteStore` / `NavRestore.stored()` / `target()`：纯逻辑，`stored("session", id)` → `Stored(SESSION, id)`，`target(...)` → `server/{serverKey}/session/{id}`，**逻辑本身无问题**。
- `ui/App.kt:773-793`：读回 + 导航
  ```kotlin
  LaunchedEffect(lastRoute, serverKey) { restoreTarget = NavRestore.target(...); navRestoreSettled = true }
  LaunchedEffect(restoreTarget, navRestoreSettled) {
      if (navRestoreSettled && target != null && target != Route.HOME) navController.navigate(target){...}
  }
  ```
- `ui/App.kt:797-836` `OnDestinationChangedListener`：写回侧。
- `OcActivity.kt:128,142`：`lastRoute` 已注入（日志 `AppScaffold(..., NavMemory, ...)` 参数非空），排除「store 为 null」。

**判定**：**FAIL**。这是本轮唯一的功能性 FAIL，属**冷启动 route 恢复**域（`LastRouteStore` / `NavRestore` / PLAN §3「restore the last page」），**不是**上轮在修的 `编辑项目` 面板 / `AgentChip` 问题，**未做任何代码改动**，已上报 Lead 定位。

---

### 2. 命令面板（Command palette）

> 键码按任务书指定，**未**使用 v82 的坑码（`113 75`=Ctrl+'、`113 42`=Ctrl+N）。

| # | 断言 | 判定 | 命令 | 证据 | 读数 |
|---|---|---|---|---|---|
| 2.1 | **Ctrl+K** 打开 palette | PASS | `input keycombination 113 39` | [b30_palette_ctrlk.png](b30_palette_ctrlk.png) [b30_palette_ctrlk.xml](b30_palette_ctrlk.xml) | 弹出居中卡片：搜索框 placeholder `搜索命令和会话`（= `oc_palette_placeholder_home`，**Home 宿主变体**正确）+ 分组 `命令` + 行 `打开设置`(Ctrl+,)/`新建会话`(Ctrl+Shift+S)/`新建工作区`/`上一个会话`/`下一个会话`/`切换终端`(Ctrl+`)/`切换审查面板`(Ctrl+Shift+R)；**首行 `打开设置` 默认高亮** |
| 2.2 | **Ctrl+Shift+P** 打开 palette | PASS | `input keycombination 113 44` | [b35_palette_ctrlshiftp.png](b35_palette_ctrlshiftp.png) | 同样打开 palette（搜索框 placeholder 重新出现，**query 确实复位为空**）。两条 chord 互不串扰 |
| 2.3 | **搜索过滤** | PASS | `input text agent` → `input text settings` | [b31_palette_filter_agent.png](b31_palette_filter_agent.png) [b31_palette_filter_agent.xml](b31_palette_filter_agent.xml) [b36_palette_filter_settings.xml](b36_palette_filter_settings.xml) | `agent` → 分组变成 `会话`(顺便问一下 / 下一条消息 Ctrl+Alt+] / Quick OK acknowledgment / …) + `命令`(查看 agent 详情)；`settings` → `命令`(打开设置 Ctrl+,) + `会话`(顺便问一下)。`EditText` 读数 `t='agent' en=true foc=true` —— 过滤随输入实时刷新，`enabled/focused` 正常 |
| 2.4 | **键盘上下选择** | PASS | `input keyevent 20` ×3（DPAD_DOWN） | [b31_palette_filter_agent.png](b31_palette_filter_agent.png) → [b32_palette_down3.png](b32_palette_down3.png) | 高亮从第 1 行 `顺便问一下` 移到第 4 行 `查看 agent 详情`（3 次 Down，跨 `会话`+`命令` 两个分组连续计数）。截图对比可见底色高亮换行 |
| 2.5 | **Enter 执行** | PASS | `input text settings` → `input keyevent 66` | [b36_palette_filter_settings.xml](b36_palette_filter_settings.xml) [b37_palette_enter_settings.png](b37_palette_enter_settings.png) | 搜索框 `t='settings'`，首行 `打开设置`；Enter 后 palette 整体消失（`搜索命令和会话` 不再出现）**且设置弹层打开**、落在 `通用` 页 —— Enter 走的是 `handleSelect` 而非仅关闭 |
| 2.6 | **Esc 关闭**（palette 非设置弹层，允许 ESC） | PASS | `input keyevent 111` | [b34_palette_esc_closed.xml](b34_palette_esc_closed.xml) | 按下后 XML 中 `搜索命令和会话` / palette 行**全部消失**，回到 Home（`设置`/`帮助`/`搜索会话` 在）。Esc 未穿透到下层路由（Home 未被 pop） |

> 源码对照（只读）：`ui/palette/CommandPalette.kt:227-259` `onPreviewKeyEvent` —— `Key.DirectionDown/Up` 移动 active、`Key.Enter/NumPadEnter` → `handleSelect(active)`、`Key.Escape` → `onClose()`。设备实测三键行为与之一致。
>
> **观察项（非 FAIL，待 Lead 判定）**：palette 关闭后**再次打开**时，`query` 会复位为空（符合 `PaletteModel.kt:129-135` `PALETTE_OPEN_QUERY`「every open starts from blank」），但**结果区滚动位置与 active 行被保留**——`b35` 二次打开后画面停在目录尾部、`查看 agent 详情` 仍是高亮行，而不是回到首行 `打开设置`。成因是 `CommandPalette.kt:183` `activeId` 与 `listState` 都是 `remember` 态，弹层内容未被销毁。`CommandPalette.kt:179-182` 的注释主张「upstream keeps `store.active`（entry id）」，因此**是否算偏离需产品判定**，本报告不单列 FAIL。

---

### 3. 设置弹层各面板（Settings dialog）

入口：palette → `打开设置`（Ctrl+K → 输入 `settings` → Enter）。左栏共 14 项：`通用 / 外观 / 通知 / 快捷键 / 配对 / 项目 / 工作树 / 提供商 / 模型 / 扩展 / 服务器 / Shell / 实验性功能 / 关于`。
本轮按任务书抽 **General / Appearance / Notifications / Servers / Shell / Experimental / About** 七面板，每面板 1–2 个交互。**所有开关类交互均已改回原值**（证据 XML 前后读数一致）。

| # | 断言 | 判定 | 命令 | 证据 | 读数 |
|---|---|---|---|---|---|
| 3.1 | **General（通用）** 面板渲染 | PASS | `tap 540,445` | [b38_settings_noime.png](b38_settings_noime.png) [b40_general.xml](b40_general.xml) | 行：`语言`(简体中文) / `自动接受权限`(ON) / `显示智能体`(OFF) / `终端位置`(侧边) / `后续消息行为`(引导)。左栏 `通用` 高亮 |
| 3.2 | General 交互①：**开关** `显示智能体` OFF→ON | PASS | `tap 2100,741` | [b41_general_showagents_on.xml](b41_general_showagents_on.xml) [b42_general_restored.xml](b42_general_restored.xml) | XML `checked`：`[2052,693][2148,789] false → true → false`（已还原）。落盘 `oc_settings.xml` 出现 `<boolean name="opencode.android.showCustomAgents" value="true"/>`，证明写入生效 |
| 3.3 | General 交互②：**选择器** `语言` 下拉 | PASS | `tap 1556,374` → 再 `tap 1556,374` | [b43_general_lang_dropdown.png](b43_general_lang_dropdown.png) [b44_general_lang_closed.png](b44_general_lang_closed.png) | 下拉展开 3 项 `English` / `简体中文`(蓝色=当前) / `繁體中文`；再次点击收起。`oc_settings.xml` `opencode-language` 保持 `zh` —— **未改动语言** |
| 3.4 | **Appearance（外观）** 面板渲染 | PASS | `tap 540,509` | [b45_appearance.png](b45_appearance.png) | 副标题`自定义主题与字体`；行：`配色方案`(浅色) / `主题`(OpenCode) / `界面字体`(Inter（默认）) / `代码字体`(系统等宽（默认）) |
| 3.5 | Appearance 交互：**选择器** `配色方案` 下拉 | PASS | `tap 1556,664` → `tap 1999,635` | [b46_appearance_scheme_dropdown.png](b46_appearance_scheme_dropdown.png) [b47_appearance_scheme_closed.png](b47_appearance_scheme_closed.png) | 下拉 3 项 `跟随系统` / `浅色`(蓝色=当前) / `深色`；收起后 `oc_settings.xml` `opencode-color-scheme` 仍为 `light` —— **未改动主题** |
| 3.6 | **Notifications（通知）** 面板渲染 | PASS | `tap 540,573` | [b48_notifications.png](b48_notifications.png) | 副标题`选择何时接收通知与声音`；`系统通知`组：`智能体`(ON) / `权限`(ON) / `错误`(OFF)；下方另有 `音效` 组 |
| 3.7 | Notifications 交互：**开关** `错误` OFF→ON | PASS | `tap 2100,1152` ×2 | [b49_notifications_err_on.xml](b49_notifications_err_on.xml) [b50_notifications_restored.xml](b50_notifications_restored.xml) | 三个开关读数 `true / true / false` → `true / true / **true**` → 还原 `true / true / false`。只动了第 3 个 |
| 3.8 | **Servers（服务器）** 面板渲染 | PASS | `tap 540,1101` | [b51_servers.png](b51_servers.png) [b51_servers.xml](b51_servers.xml) | 标题旁 `添加 SSH 服务器` + `+`；`搜索服务器` 输入框 `EditText [886,454][2156,550] en=true`；列表 2 台：`127.0.0.1:4095`（**绿点** / `v2.0.18 • opencode` / 徽标`默认`）与 `127.0.0.1:4097`（红点 / `opencode`），各带 `⋮`(content-desc=`服务器操作`) |
| 3.9 | Servers 交互：`⋮` 菜单可开可关（**只读，不改共享配置**） | PASS | `tap 2112,665` → `tap 2112,665` | [b52_servers_menu.png](b52_servers_menu.png) [b53_servers_menu_closed.xml](b53_servers_menu_closed.xml) | 菜单 3 项：`编辑` / `设为默认` / `删除`；再次点击同一 `⋮` 即收起。`oc_server_config.xml`（EncryptedSharedPreferences，4 条密文）**未被写入**——遵守「不改别人项目/配置」纪律 |
| 3.10 | **Shell** 面板渲染 | PASS | `tap 540,1166` | [b54_shell.png](b54_shell.png) [b54_shell.xml](b54_shell.xml) | 标题 `终端 Shell` + 按钮 `重新加载`；副标题`终端和智能体工具使用的 Shell`；候选列表 5 项：`sh` / `bash`(徽标 `当前`) / `rbash` / `systemd-home-fallback-shell` / `git-shell`；下方 `Shell 命令` = `EditText [886,1248][2156,1344] t='bash' en=true focusable=true` |
| 3.11 | Shell 交互①：`重新加载` 按钮 | PASS | `tap 2100,410` | [b55_shell_reloaded.xml](b55_shell_reloaded.xml) | 点击后 5 个 shell 行**全部仍在**（`sh / bash / rbash / systemd-home-fallback-shell / git-shell`），`bash` 仍带 `当前` 徽标 |
| 3.12 | Shell 交互②：`Shell 命令` 输入框可聚焦（不改值） | PASS | `tap 1521,1296` | [b56_shell_cmd_focused.xml](b56_shell_cmd_focused.xml) | 聚焦后 `foc=true`、`mInputShown=true`、**`t='bash'` 未变**；随后用 Gboard 收起键隐藏键盘，未写入任何字符 |
| 3.13 | **Experimental（实验性功能）** 面板渲染 | PASS | `tap 540,1246` | [b57_experimental.png](b57_experimental.png) [b57_experimental.xml](b57_experimental.xml) | 副标题`试用实验性功能`；行：`标签页`(水平) / `显示项目名称`（**带徽标 `本客户端暂不支持`**）/ `v1 历史迁移`(`迁移已完成`) |
| 3.14 | Experimental 交互：`标签页` 选择器 水平→垂直 | PASS | `tap 1556,628` → `tap 1910,786` | [b58_experimental_tabs_dropdown.png](b58_experimental_tabs_dropdown.png) [b59_experimental_tabs_vertical.xml](b59_experimental_tabs_vertical.xml) | 下拉 `水平`/`垂直`；点 `垂直` 后选择器读数变 `垂直`，落盘 `oc_settings.xml` 出现 `<string name="appearance.tabLayout">vertical</string>`。**该值用于 §4 移动形态底部标签条测试，收尾时已还原为 `horizontal`** |
| 3.15 | **About（关于）** 面板渲染 | PASS | `tap 540,1326` | [b60_about.png](b60_about.png) [b60_about.xml](b60_about.xml) | `OpenCode 安卓版` / **`版本 1.4.0`** / `基于 MIT 许可证发布` / 作者与插图署名 / `首次出版于美国密苏里州` / `www.opencode.ai` / 商标声明 / `使用 Inter 和 IBM Plex Mono 排版`。**版本号与 `dumpsys package` 的 `versionName=1.4.0` 一致**（纯信息面板，无可点交互） |
| 3.16 | 设置弹层 `关闭` 可用 | PASS | `tap 2182,248` | [b61_settings_closed.xml](b61_settings_closed.xml) | 关闭后 `搜索设置` 字段消失，弹层整体卸载 |
| 3.17 | **IME 带：键盘弹起时卡片顶边位移 ≤ 12px** | **FAIL（实测 Δ152px）** | 见 N-2 | [b38_settings_noime.png](b38_settings_noime.png) [b39_settings_ime_from_search.png](b39_settings_ime_from_search.png) | 卡片顶边 y：**201px（无 IME）→ 49px（有 IME）= 位移 152px**，是 12px 预算的 **12.7 倍**。5 列（x=400/900/1280/1700/2200）取中位数，5 列读数完全一致（201 / 49），非采样噪声。**但 `ui/settings/SettingsScreen.kt:334-345` 明确记载这是 v82 的有意设计**（`imePadding()` 把卡片从窗口居中「抬出」并压到键盘上方的高度，理由是旧行为会让快捷键行和服务器表单的保存按钮被键盘盖住且无从滚动）→ **本条按任务书断言判 FAIL，但性质是「规格冲突」而非实现缺陷，需 Lead 裁决哪条为准**。详见 N-2 |

#### 测量方法（N-2 用）
`cardtop.py`（本目录）：对截图 5 个固定列自上而下扫描，取**首个「亮且中性」像素**（`max(RGB)≥235` 且 `max-min≤12`）作为卡片顶边——卡片是盖在暗色 scrim 上的不透明白面，边界锐利。脚本与读数一并留档，可用同法复核。

---

### 1b. 移动形态（BottomTabStrip + MobileTabsDrawer）

前置：`设置 → 实验性功能 → 标签页 = 垂直`（落盘 `appearance.tabLayout=vertical`，§3.14）→ **重启 app**（`AppearancePrefs` 每次启动读一次）→ `wm size 1200x1600` + **同时** `wm density 320`（= 600dp 宽 < `MobileForm.isDesktop` 的 768dp 断点）→ `am force-stop` + `am start`。

| # | 断言 | 判定 | 命令 | 证据 | 读数 |
|---|---|---|---|---|---|
| 1.9 | 窄屏 + `tabLayout=vertical` → 标签条落**底部** | PASS | `wm size 1200x1600` + `wm density 320` → `force-stop` → `am start` | [b63_mobile_form.png](b63_mobile_form.png) | 标签条 4 槽出现在 **y≈1452..1548（贴底）**：`首页`=`[0,1452][84,1548]` / tab=`[84,1452][227,1548]`(✕ `[163,1452][227,1548]`) / `新建会话`=`[227,1452][295,1548]` / `命令面板`=`[295,1452][391,1548]`。同一时刻**顶部没有** DesktopTabStrip —— 桌面/移动两个停靠点互斥，符合 `App.kt:894-896` `bottomTitlebar = !desktopTabs && bottomTabStrip` |
| 1.10 | 移动形态 Home = 上下堆叠（项目列压会话列） | PASS | 同上 | [b63_mobile_form.png](b63_mobile_form.png) | 600dp 宽下 Home 改为单列堆叠：项目列表（`.cache` / mighty-river / v27projC / sensorsdata / `.copilot`）在上，`搜索会话` 框 + `今天` 分组在下 |
| 1.10b | 移动形态会话页 = **四个全宽视图标签** | PASS | 打开会话 | [b64_mobile_session.xml](b64_mobile_session.xml) [b65_mobile_drawer.png](b65_mobile_drawer.png) | 顶部 `会话` / `变更` / `文件` / `终端` 四等分（`[114,90][166,127]` 起，各 280px 宽），`会话` 为选中态 —— `ui/session/drawer/MobileForm.kt:42`「Mobile = bottom-sheet drawer + the four full-width view tabs」 |
| 1.11 | `MobileTabsDrawer` 可由底部 tab 打开 | PASS | `tap 110,1500`（先 `tap 86,1546` 收键盘，否则键盘盖住底部条） | [b66_mobile_drawer2.png](b66_mobile_drawer2.png) [b66_mobile_drawer2.xml](b66_mobile_drawer2.xml) | 底部弹出 sheet：拖拽条 ▬ + `Untitled ×`(=`[24,1299][1176,1395]`，✕=`[107,1299][203,1355]`) + `+ 新建会话` + `⛶ 首页` + `☼ 设置` / `? 帮助`。点遮罩 `(600,600)` 关闭成功 → [b68_drawer_closed.xml](b68_drawer_closed.xml) |
| 1.12 | **抽屉打开时底部标签条位置** | **FAIL** | 见 N-3 | [b66_mobile_drawer2.xml](b66_mobile_drawer2.xml) [b67_drawer_still_open.png](b67_drawer_still_open.png) | 抽屉打开时 `BottomTabStrip` 被摆到 **y=0..84**（`首页`=`[0,0][84,84]`、tab=`[84,0][227,84]`、`+`=`[227,0][295,84]`、palette=`[295,0][391,0..84]`），**直接压在状态栏上**，时钟 `10:30`/`10:31` 与状态图标被覆盖（见截图左上角文字重叠）。关闭抽屉后立即回到 y=1452..1548 |

---

### 4. Home「最近关闭」空态

| # | 断言 | 判定 | 命令 | 证据 | 读数 |
|---|---|---|---|---|---|
| 4.1 | 「最近关闭」空态可渲染 | **未验到（可达性受限）** | 源码锚（本轮已在**当前**工作树上复核行号） | [b00_boot.xml](b00_boot.xml) [b63_mobile_form.png](b63_mobile_form.png) | `ui/home/HomeProjects.kt:304-305`（**行号较上轮报告的 274-275 已下移，文件在此期间被改过**）：<br>`fun showsClosed(projects, recentlyClosed) = projects.isEmpty() && recentlyClosed.isNotEmpty()`<br>即该分区**仅在「打开的项目列表为空」时**才渲染。本机项目列有 9–10 个**别人也在用的共享项目**（mighty-river / v27projC / sensorsdata / .copilot / E2ECHK_renamed / opencode-android-… / E2ECHK_xiufeigo / `/`），要把列表清空必须逐个 `⋮→关闭`，**会破坏共享后端状态** → 按任务书要求**不制造该场景**。本设备上**未验到**。 |

---

### 5. 回归抽验（上轮已 PASS 项，在新 APK 上复验）

| # | 断言 | 判定 | 命令 | 证据 | 读数 |
|---|---|---|---|---|---|
| 5.1 | 目录选择器打开 + `~` 面包屑**非死区** | PASS | `tap 756,390` → `tap 909,293` | [b75b_after_tap.png](b75b_after_tap.png) [b76_picker_tilde.xml](b76_picker_tilde.xml) | 弹层 `选择项目目录`，路径框 `/home/xiufeigo/.cache`，面包屑 `~`/`根目录`/`上级`，底栏 `取消`/`选择此目录`(蓝=可用)。点 `~` 后**路径框与底部 cwd 标签同时**变 `/home/xiufeigo`，列表刷新为 `.agents/.android/.antigravity-ide-server/.cache/.cargo/.claude/…`（隐藏目录可见） |
| 5.2 | 目录选择器**两段确认** | PASS | `tap 1593,1468` → `tap 1567,1468` | [b80_cargo_entered.xml](b80_cargo_entered.xml) [b81_cargo_stage1.png](b81_cargo_stage1.png) [b82_cargo_added.png](b82_cargo_added.png) | 进 `.cargo` 后路径框/footer 同步 `/home/xiufeigo/.cargo`；**第 1 段**按钮文案 `选择此目录`→**`确认选择此目录`**（`[1476,1450][1658,1487]`）；**第 2 段**点下去弹层关闭、项目行 `.cargo` **新增并自动选中**（行底色高亮）、会话列切到空态 `这里还没有内容 / 创建一个会话以开始 / 新建会话`、搜索框变 `在 .cargo 中搜索会话`。项目行数 9 → **10** |
| 5.3 | 项目 `⋮` 菜单**六项** | PASS | `tap 620,480` | [b83_cargo_menu.png](b83_cargo_menu.png) | 6 项全在：`新建会话` / `导入会话` / `编辑项目` / `刷新工作区` / `清除通知`(**灰显** —— `.cargo` 无通知，符合预期) / `关闭`，`关闭` 之前有分隔线。与上轮 §1.6 枚举一致 |
| 5.4 | 关闭项目（确认框 + 移除 + 不删服务器内容） | PASS | `tap 301,1132` → `tap 2416,909` | [b84_close_confirm.xml](b84_close_confirm.xml) [b85_cargo_closed.xml](b85_cargo_closed.xml) | 确认框文案完整：`关闭项目？` / `「.cargo」将从此列表中移除。项目及其会话仍保留在服务器上，不会删除任何内容。` / `取消`/`关闭`；确认后全 XML 中 `.cargo` 出现次数 **0**，**共享后端未被改动**（本条同时完成本轮的状态复原） |

---

## 新发现段（本轮发现的 bug / 冲突，均未改代码）

### N-1 — 冷启动不恢复上次会话 route/tab（**FAIL，功能性**）
见上文 §1.8 的完整复现与源码锚。**一句话**：落盘 `oc_nav_state.xml` 写对了（`oc_last_route=session` + 合法 id，`force-stop` 后仍在），冷启动却从未进入该 route，直接落 Home 并把落盘值改写回 `home`。
- 域：冷启动 route 恢复（`LastRouteStore` / `NavRestore` / PLAN §3「restore the last page」）
- 与 Lead 在修的 `编辑项目` 面板 / AgentChip **无关**
- 复现 3 次，稳定；证据 `b17`→`b24_r1..r8`

### N-2 — 设置弹层：键盘弹起时卡片顶边位移 152px（**FAIL（按任务书断言） / 规格冲突**）
- 现象：设置卡片顶边 y 由 **201px（无 IME）→ 49px（有 IME）**，位移 **152px**，远超任务书给的 12px 预算（12.7×）。两处独立触发（palette 交接时 IME 已弹、点设置搜索框主动弹起）读数一致，5 列像素扫描无噪声。
- 源码锚：`ui/settings/SettingsScreen.kt:334-345` —— 注释明确写这是 **v82 的有意设计**：卡片由 `settingsDialogCardHeightDp(…, imeBottomDp)` 压到键盘上方的高度，并被 `Modifier.imePadding()`（`:366-380`）「抬出」窗口居中，理由是旧行为会让快捷键行与服务器表单的保存按钮被键盘盖住且无从滚动。
- **判定说明**：按任务书断言判 **FAIL**；但实现侧有明确的设计决策在案，**这不是实现缺陷而是两条规格冲突**。**请 Lead 裁决**：要么把 ≤12px 预算改成「卡片必须整体在 IME 之上即可」（则本条记 PASS），要么要求 `imePadding` 换成不改变顶边的方式。扫测侧不替产品做决定。

### N-3 — 移动形态：抽屉打开时底部标签条跳到屏幕顶部压住状态栏（**FAIL，视觉/布局**）
- 现象：`MobileTabsDrawer` 打开的瞬间，`BottomTabStrip` 的 4 个槽从 **y=1452..1548** 跳到 **y=0..84**，盖在状态栏上（时钟/信号/电量图标与标签文字重叠，见 `b66`/`b67` 截图左上角）。关闭抽屉立即复原。
- 最小复现：
  1. 设置→实验性功能→`标签页 = 垂直`；重启 app
  2. `adb shell wm size 1200x1600` + `adb shell wm density 320`；重启 app
  3. 打开任意会话（软键盘会盖住底部条，先用 Gboard 左下 `▽` 收起）
  4. `adb shell input tap 110 1500` → 抽屉弹出，**顶部立刻出现一条标签条压住状态栏**
- 源码锚与成因：`ui/App.kt:1570` 的 `Column(Modifier.fillMaxSize())` 是这两者的共同父容器，子节点顺序为 `NavHost`(weight=1f) → `DebugBar` → `BottomTabStrip(...)`。而 `ui/nav/BottomTabStrip.kt:147-199` 先发射 strip 的 `Row`、**紧接着在 `:201` 把 `MobileTabsDrawer` 作为兄弟节点发射**，而后者内部是 `Box(Modifier.fillMaxSize())`（`ui/nav/MobileTabsDrawer.kt:148`）。Compose `Column` **先测量非 weight 子节点**：`fillMaxSize()` 的 Box 吃掉整列剩余高度，`weight(1f)` 的 NavHost 被挤到 ≈0，于是排在最前面的 strip `Row` 被顶到 y=0。
- 影响面：仅移动形态（`bottomTitlebar=true`，即宽度 <768dp 且 `tabLayout=vertical`）且仅在抽屉打开时；桌面形态的 `DesktopTabStrip` 不经过这条路径。
- 复现 2 张截图（间隔约 1 分钟）像素级一致，排除动画过渡帧。

---

## 未闭环 / 未验到清单

| 项 | 状态 | 原因 | 后续建议 |
|---|---|---|---|
| N-1 冷启动 route 恢复 | **FAIL 已定位未修** | 本轮只读定位，未改代码 | 请 Lead 看 `ui/App.kt:775-793`（`LaunchedEffect(lastRoute, serverKey)` → `LaunchedEffect(restoreTarget, navRestoreSettled)`）与 `ui/nav/LastRouteStore.kt` |
| N-2 设置卡片 IME 位移 | **规格冲突待裁决** | 任务书 ≤12px 断言 vs `SettingsScreen.kt:334-345` 的 v82 有意设计 | 请 Lead 指定以哪条为准 |
| N-3 移动抽屉打开时标签条压状态栏 | **FAIL 已定位未修** | 本轮只读定位，未改代码 | 建议把 `MobileTabsDrawer` 的 `Box(fillMaxSize())` 换成弹层宿主（Dialog/独立窗口），或给 `BottomTabStrip` 一个包住两者的 `Box` |
| Home「最近关闭」空态 | **未验到（可达性受限）** | 需清空全部打开的项目，会破坏共享后端状态 | 需要一台空项目列表的设备/独立后端实例再验 |
| 设置面板：快捷键 / 配对 / 项目 / 工作树 / 提供商 / 模型 / 扩展 | **未测（任务书未要求）** | 任务书只列了 7 个面板 | 若要扩面可下一轮补 |
| 标签**拖拽重排** / 终端 tab | **未测（任务书未要求）** | 域外 | — |
| `编辑项目` 面板 / AgentChip `/agent` | **按任务书跳过** | Lead 并行修复中 | 修复后单独复验 |
| 设备 IME 语言 | **本轮做过设备本地归一化** | Gboard 原为中文拼音态，会吞改 `input text`（`E2ECHK5560-b-t1` → `E2ECHKDSK556-不-0`） | `adb shell pm clear com.google.android.inputmethod.latin` 已恢复拉丁输入；**不影响应用**，但下一轮测试员需注意 |

## 收尾状态自检（全部复原）

| 项 | 复原后读数 | 命令 |
|---|---|---|
| 屏幕 | `Physical size: 2560x1600`（`Override` 已清） | `adb -s emulator-5560 shell wm size` |
| 密度 | `Physical density: 320`（`Override` 已清） | `adb -s emulator-5560 shell wm density` |
| `appearance.tabLayout` | `horizontal` | `run-as … cat shared_prefs/oc_settings.xml` |
| `opencode.android.showCustomAgents` | `false`（原值） | 同上 |
| 通知「错误」开关 | OFF（原值） | 面板读数 `true/true/false` |
| 配色方案 / 语言 | `light` / `zh`（原值） | 同上 |
| Shell 命令 | `bash`（未改） | 面板读数 `t='bash'` |
| 服务器配置 | 未写入（只开合了一次 `⋮` 菜单） | `oc_server_config.xml` 为原始 4 条密文 |
| 项目列表 | `.cargo` 已移除，回到 9 行 | [b85_cargo_closed.xml](b85_cargo_closed.xml) |
| 本轮新建的会话 | `Untitled` / `E2ECHK5560-b-t1`（`E2ECHK5560` 前缀，`.cache` 目录下），**未删除任何别人的会话** | — |
