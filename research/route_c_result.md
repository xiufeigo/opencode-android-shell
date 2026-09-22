# 路线 C 结果报告（移动端形态对齐 + P0/P1 收敛）

> 目标：在**保持 v1 接入**的前提下，把 Android 原生前端按上游 v2 的移动端分支做高保真对齐，并补齐审计列出的 P0/P1 功能面。
> 执行方式：4 条工作流并行（各自独占写作用域）+ 1 名独立验证者（不得自证）；`research/wave4_contracts.md` 是团队共享规格。
> 明细分报告：`wave4_data_layer.md`、`wave4_session_fidelity.md`、`wave4_mobile_form.md`、`wave4_shell_wiring.md`、`verify_wave4.md`；设备证据在 `verify_wave4_shots/`（55 件）与 `wave4_shots/`。

---

## 1. 验收门（Lead 独立复跑）

| 项 | 结果 |
|---|---|
| `clean :app:testDebugUnitTest :app:assembleDebug` | **BUILD SUCCESSFUL** |
| 单测 | **72 suites / 637 tests / 0 failures / 0 errors / 0 skipped**（本 wave 前 63/537 → +9 套件 / +100 测试） |
| 平板 `emulator-5554`（2560×1600, 1280dp） | 桌面形态：透明工具卡、`使用了 1 思考` 分组、消息元信息（`Build · gpt-5.6-sol-fast · 52 秒`＋复制）、⋮ 与终端 chip 不相交（`更多选项 [2496,104][2528,136]` vs `打开终端 [2500,188][2532,220]`）、设置页真实 provider/模型、发送→流式→**ROUTEC_OK** |
| 折叠屏 `emulator-5556`（2076×2152, 852dp → `wm size 1080x2152` = 443dp 后复原） | 窄屏：**四个整宽 tab**（会话/变更/文件/终端）+ `⋯` 菜单、底部 sheet（16dp 圆角 + 28dp 拖拽柄 + scrim + 下滑关闭）、500ms 长按菜单（重命名/删除）、窄屏无浮动终端 chip |

---

## 2. 相对审计缺口的闭合情况

审计基线：`audit_1to1_verdict.md`（607 行逐项对照，1:1 占 13.7%）。

### 2.1 已闭合（P0/P1）

| 审计缺口 | 现状 |
|---|---|
| 设置 Providers/Models 是永久空壳（改为空态） | 接线 `availableProviders`（**已连接 10 / 全部 225**）+ 模型 catalog（**可用 36**，含 `上下文 1M/200k`），`SettingsPanelExtras` 改 StateFlow |
| 设置 21 字段只有 3 个生效 | 接线 6 行（reasoning 摘要、shell/edit 展开、fileTree、palette 搜索、terminalFont），**隐藏 13 行**并保证「渲染开关 ∪ 隐藏开关 = 写入白名单」（有单测） |
| part 12 型只渲染 5 型、payload 丢失 | `UiPart.rawJson` 白名单（7 型）双路径保留 + `RawParts.kt` 渲染 patch/subtask/agent/retry，step-* 折叠、snapshot 显式忽略 |
| `message.part.removed` 未实现 | 事件 + reduce（消息变空则整条移除）+ 单测 |
| 刷新兜底失效 | `sessionIdOf` 修正 + idle 结算时 `refreshSnapshotThrottled()` |
| 命令面板 5 条空实现 | 10 条命令**零空实现**（回调缺席即不注册），keybind 按上游（Ctrl+Shift+S / Ctrl+N / Ctrl+Shift+R / Ctrl+P / Ctrl+\），冷启动 Home 的 `file.open` 修复 |
| 工具卡有面卡片 | 透明无边框 + 12dp 内容导轨（对齐 `collapsible.css:1-14`、`basic-tool.css:314-318`） |
| 分组文案/策略 | `Used {count} {names}`（`使用了 1 Shell`）+ compact 默认全收进分组 |
| `⋮` 竖三点 | 横向三点 |
| 发送按钮配色相反 | 按 `submit.css` 的 submit variant（浅色深灰底/白箭头，深色反之） |
| 消息无元信息 | Agent·Model·时长 + 复制（助手）/ 时间 + 复制（用户） |
| 缺 Working / Jump to latest | 均已实现（实现过程中修掉两个真 bug：可见性判定与箭头方向） |
| 用量环死控件 | 真实分母（`limit.context`）+ 可点 + Cost/Context/Tokens 弹层 |
| 移动端照搬桌面 | 底部 sheet + 四整宽 tab + 长按菜单 + question 分页 |
| `session.error` 显示整段 JSON | 解析 `AssistantError` 联合（`data.message` → `name`），原始 JSON 存 `errorDetail` |
| 权限 `always[]` 无展示 | **已补**：dock 增「始终允许将记住」提示行（`permissionAlwaysHint()` 过滤与 `patterns` 重复项，4 个单测）——上游 dock 本身不展示 `always`，这是加性透明度改进 |
| 认证 `"user:null"` | 抽 `basicAuthorizationHeader()`，REST 与 SSE 共用；无密码不发头 |
| diff 渲染规格（工具体） | **已补**：工具体改「只染背景」（`stateSuccessBg`/`stateDangerBg`）+ 13sp/16sp（上游 `--line-height-compact`）；`apply_patch` 的 `patchText`/`files[].patch` 解析成 `+`/`-` diff 行（5 个单测）——此前该卡回退 JSON body，diff 渲染根本不出现 |

### 2.2 本 wave 额外发现并修复的**真 bug**（都不在审计清单里）

1. **终端必现崩溃**：vendor `TerminalView.setTextSize()` 才创建 renderer，而 `setTypeface()` 无判空 → 先调 setTypeface 即 NPE。两条入口（路由 chip 与内嵌 tab）都崩；已修正调用顺序，设备复验 PowerShell PTY 正常。
2. **主题 token 8 位 hex 解析顺序错误**（跨全仓）：`c()` 直接 `Color.parseColor`，但 9 个 8 位 token 是 CSS `#RRGGBBAA` → 亮色下边框/遮罩 **alpha=0**（scrim 不变暗），暗色变不透明错色。已在解析边界做 RRGGBBAA→AARRGGBB 转换。
3. **SSE 头同款认证缺陷**（与 REST 漂移）。
4. **弹层关闭后全局快捷键全失效**（F-1）：palette 自动聚焦搜索框后根焦点不归还；改为 `LaunchedEffect(active)` 在弹层关闭时重抢焦点。多轮验证（开→关→再开→再关→再开）全通过。
5. **冷启动 Home 的 `file.open` 请求被丢弃**（F-3）：无 in-run 会话时回落 `listSessions` 取最近会话。
6. 头部 `⋮` 的「删除」永久禁用（现与抽屉长按一致，接危险确认框）；设置页 Android 独有 footer 移除；单次 `Esc` 可关设置层。

### 2.3 自我更正（保留记录）

验证者最初报的 **F-2（Ctrl+P 不触发）与 F-6（环几何 16/2）经自查撤回**：F-2 是 dump 判据缺陷（截图证明面板已打开）；F-6 上游会话头用的是默认 compact variant，按原结论改会改错。两条都写进 `verify_wave4.md` 未删除。

---

## 3. 与 v2 的一致性结论（含接入面）

- **前端/功能面**：审计列出的 P0/P1 已闭合；剩余差异属「未实现的功能面」而非缺陷（见 §4）。
- **接入面：结构上无法与 v2 一致，瓶颈在 server 而非本仓库**。实测本机 `/api/*`：
  - 可用：`/api/session`、`/api/session/:id`、`/api/session/:id/message`、`/api/provider`、`/api/agent`、`/api/location`、`/api/event`
  - **未实现（返回 SPA HTML）**：`config`、`vcs`、`file`、`find/file`、`permission`、`question`、`info`、`global/health`、`project`、`lsp`、`mcp`
  - 且 v2 SSE 信封是 `{id,type,data}`，v1 是 `{payload:{id,type,properties}}`
  - ⇒ 切到 v2 会立刻丢 11 条能力面并需重写事件解包；**维持 v1（22 端点全可用 + `/global/event`）是当前唯一正确解**。

---

## 4. 仍未做的上游功能面（按用户可感知度）

1. 首启无服务器时的**连接页**（上游 `servers/connect/screen.tsx` 整屏表单 + 连通性校验；我们只有内联提示）
2. **会话摘要面板**（项目/服务器/后台任务）与 `SummaryPopover`
3. **时间线搜索栏** + 待发送**队列面板**
4. Providers **连接/OAuth/API Key** 对话框（当前面板只读）
5. 设置**嵌套页**（Projects / Workspaces / Extensions / Experimental / About）——现为禁用入口
6. 会话行 **Export / Archive**
7. 斜杠命令、附件上传进度与取消、消息导航轨、diff 行评论/词级 diff、用量超限对话框链路
8. 权限 `always[]` 的 pattern 展示；`MessageMeta` 的模型显示名（当前显示 modelID）
9. 设置页仍是 dialog 层而非上游的路由 surface（本轮只对齐注释与 `show` 语义）

---

## 5. 验证缺口（诚实记录）

- **`always` 提示行无法设备验证**：本机 server 没有 `permission` 规则，永不产生 `permission.asked`，dock 在设备上不会出现。逻辑由 4 个单测覆盖；要真验需在服务端配 `permission`。
- **工具体染色 diff 的展开截图未取到**：已在设备上取到「分组展开后 `Apply_patch` 卡存在」（`t-patchbody.png`），但点击行中心与右侧 chevron 都未触发展开（dump 前后一致，卡保持折叠）→ 疑似 completed 卡的命中区/锁定策略问题，已交给后续 wave 复核；解析与着色本身由 5 个单测 + 与评审面板同一手法覆盖。
- 验证者的 F-2/F-6 撤回、以及 `wm size` 缩宽/复原记录见 `verify_wave4.md`。

## 6. 交付物

- 代码：`app/src/**`（39 文件改动 +6039/−1228，新增 24 个文件，见 `git status`）；APK `app/build/outputs/apk/debug/app-debug.apk`
- 文档：本报告 + 4 份 workstream 报告 + `verify_wave4.md`（含独立复核、撤回记录、无法验证项）
- 证据：`research/verify_wave4_shots/`（55）、`research/wave4_shots/`（25 + dump）
- 工具：`research/e2e_harness.ps1`
- 未 git commit（按约定）
