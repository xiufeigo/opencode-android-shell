# 一致性结账单 · A4（路线 A 下逐条结账）

- 结账日期：2026-10-05
- 结账对象：`RIGHT_PANEL_GAP.md`（v161）§3 差距表 20 项 + §4 TOP 10；`GLOBAL_PARITY_GAP.md`（v162）§3 自造 13 + §4 缺失 26 + §5 未核对 28
- 订正基线：`PARITY_GAP_V2BASELINE.md`（`origin/v2` = `ecfa884`）；最新复核基线：`research/web/UPSTREAM_REGRESSION_A3.md`（`0a46301`）
- **性质：纯文档 + 只读核查。** 未碰设备、未起模拟器、未跑 gradle、未改任何生产代码；唯一写入为本文件（`research/audit/**`）
- **未切换 `_upstream` 工作区**（仍停在 `702a73d`），全部上游证据走 `git show` / `git grep … origin/v2 -- packages/*/src`

---

## 0. 判定词表（本文件的四列含义）

| 新判定 | 含义 |
|---|---|
| **构造上消失** | 路线 A 下界面**就是官方前端本身**，该差异在交付物里无法存在 |
| **仍适用-界面无关** | 与界面无关（接入/权限/通知/文件/凭据/错误态），路线 A 不消灭它 |
| **仍适用-但属原生壳** | 只影响旧的原生壳（`app/src/main/**`），不影响 web 壳交付物（`webapp/**`） |
| **已无意义/前提不成立** | 前提被路线 A 取消（如"自造物"在路线 A 下无从谈起；或上游自己已下线该能力） |

> **本文件明确不把"构造上消失"当万能挡箭牌**：凡涉及**接入/权限/通知/文件/凭据/错误态**的条目，一律逐条判（见 §3 的 N 系列与 §5）。

---

## 1. RIGHT_PANEL_GAP §3 · 差距表 20 项（原：一致 9 / 不一致 7 / 缺失 3 / 自造 1）

| # | 原判定 | 在路线 A 下的新判定 | 依据 | 是否需行动 |
|---|---|---|---|---|
| R1 | 右侧栏容器「一致」 | **构造上消失** | `RIGHT_PANEL_GAP.md:284`；`PARITY_GAP_V2BASELINE.md:276`（`session-side-panel.tsx` 在 `origin/v2` 已 GONE，迁至 `side-region.tsx:84-86`）。路线 A 交付的就是 `side-region.tsx` 本身 | 不需要 |
| R2 | Review/变更标签「不一致（命名）」 | **构造上消失** | `RIGHT_PANEL_GAP.md:285`（壳「变更」`strings.xml:442` vs 上游「审查」）；`origin/v2` `gui-extensions/src/review/i18n/zh.ts:2` `"tab.title": "审查"`。标签文案由上游 i18n 决定 | 不需要 |
| R3 | Context/上下文标签「一致」 | **构造上消失** | `RIGHT_PANEL_GAP.md:286`；`PARITY_GAP_V2BASELINE.md:278`（`context/renderer.tsx:36-44`，标签键迁为 `legacy: {context:"main"}`） | 不需要 |
| R4 | Context 标签使用率指示器「缺失」 | **构造上消失** | `RIGHT_PANEL_GAP.md:287,323-327`（TOP-3）；`PARITY_GAP_V2BASELINE.md:279,308` → `context/renderer.tsx:24` `<SessionContextUsage session={state.session} variant="indicator" />` 在上游仍存在 | 不需要 |
| R5 | Context 标签可关闭「缺失」 | **构造上消失** | `RIGHT_PANEL_GAP.md:288,329-333`（TOP-4）；`PARITY_GAP_V2BASELINE.md:280,309` → `context/renderer.tsx:19` `closable: "compact"` + `panel-trigger.tsx:103,123` | 不需要 |
| R6 | 文件标签「不一致」（动态多文件 vs 固定「文件」tab） | **构造上消失** | `RIGHT_PANEL_GAP.md:289,317-321`（TOP-2）；`PARITY_GAP_V2BASELINE.md:281` → `file/renderer.tsx:216-232` `input.open.flatMap(...)` 每文件一标签 | 不需要 |
| R7 | 缺「Open file」文件浏览器 tab「缺失」 | **构造上消失** | `RIGHT_PANEL_GAP.md:290,335-339`（TOP-5）；`PARITY_GAP_V2BASELINE.md:282,310` → `file/renderer.tsx:202` `legacy: {"open-file": OPEN}`、`:218` launcher、`:264-266` `+` 菜单 | 不需要 |
| R8 | Browser 标签「不一致」 | **构造上消失** | `RIGHT_PANEL_GAP.md:291`；`PARITY_GAP_V2BASELINE.md:283` → `browser/renderer.tsx:148-153` 仍是 side 区动态标签（`browser/i18n/zh.ts:4`「浏览器」） | 不需要 |
| R9 | 16 项元数据表「一致」 | **构造上消失** | `RIGHT_PANEL_GAP.md:292`；`PARITY_GAP_V2BASELINE.md:284` → `gui-extensions/src/context/tab.tsx:181-202` 恰 16 条 | 不需要 |
| R10 | 16 项中文字段名「一致」 | **构造上消失** | `RIGHT_PANEL_GAP.md:293`；`PARITY_GAP_V2BASELINE.md:285` → 键迁至 `context/i18n/zh.ts:11-26` | 不需要 |
| R11 | 使用率算法「不一致」（分母口径） | **构造上消失** | `RIGHT_PANEL_GAP.md:294,347-351`（TOP-7）；`PARITY_GAP_V2BASELINE.md:286,312` → `context/tab.tsx:132` 分母为单一 `limit.context` | 不需要 |
| R12 | 词元总数算法「一致」 | **构造上消失** | `RIGHT_PANEL_GAP.md:295`；`PARITY_GAP_V2BASELINE.md:287` → `context/tab.tsx:118-122` 五项求和 | 不需要 |
| R13 | 系统提示词块「一致」 | **构造上消失** | `RIGHT_PANEL_GAP.md:296`；`PARITY_GAP_V2BASELINE.md:288` → `context/tab.tsx:154,297,300` | 不需要 |
| R14 | 原始消息列表「一致」 | **构造上消失** | `RIGHT_PANEL_GAP.md:297`；`PARITY_GAP_V2BASELINE.md:289` → `context/tab.tsx:321` `<Accordion multiple>`（组件已换为 `Accordion.Item`+`StickyAccordionHeader`） | 不需要 |
| R15 | 导出会话按钮「一致」 | **构造上消失** | `RIGHT_PANEL_GAP.md:298`；`PARITY_GAP_V2BASELINE.md:290` → `context/tab.tsx:315,318` + `context/export.ts:16` | 不需要 |
| R16 | 移动端 Context 视图「一致」 | **构造上消失** | `RIGHT_PANEL_GAP.md:299`；`PARITY_GAP_V2BASELINE.md:291` → `context/renderer.tsx:43,49-55`，移动端改由 `runtime/extension/mobile.tsx` 派发 | 不需要 |
| R17 | 终端面板**存在性**「一致」 | **构造上消失**（面板本体）／**仍适用-界面无关**（PTY 通道） | `RIGHT_PANEL_GAP.md:300`；`PARITY_GAP_V2BASELINE.md:292` → `panels.tsx:387` `DockRegion`、`screen.tsx:496,535`。**但通道侧**：`ROUTE_A_WIRING.md:205` 记 WebSocket/PTY 隧道「从未设备验证」 | **需要**（见 N-3） |
| R18 | 终端面板**在右侧栏内**「本壳自造（上游无）」 | **已无意义/前提不成立** | `RIGHT_PANEL_GAP.md:301,311-315`（TOP-1）；`PARITY_GAP_V2BASELINE.md:293,306` → `terminal/renderer.tsx:144-146` `region: "dock"`，与 `side` 严格分离（`panels.tsx:106` vs `:412`）。该芯片是原生壳自造物，路线 A 的交付物里不存在 | 不需要 |
| R19 | 终端开启方式「不一致」 | **构造上消失** | `RIGHT_PANEL_GAP.md:302`；`PARITY_GAP_V2BASELINE.md:294` → 命令改由 `terminal/renderer.tsx:45-52`（`ctrl+\``/`/terminal`）、`:75-80`（`ctrl+alt+t`）注册 | 不需要 |
| R20 | 终端面板高度 60%「一致」 | **构造上消失** | `RIGHT_PANEL_GAP.md:303`；`PARITY_GAP_V2BASELINE.md:295` → `panels.tsx:428-429` `store.viewport * 0.6`、`screen.tsx:549` | 不需要 |

**小计（R1–R20，20 条）：构造上消失 18 / 仍适用-界面无关 0 / 仍适用-但属原生壳 0 / 已无意义 1（R18）**；R17 为双判行，其"仍适用"半边转 §7 N-3

> 精确计数口径：R1–R16、R19、R20 = **构造上消失 18 条**；R18 = **已无意义 1 条**；R17 = **双判**（面板构造上消失 / 通道仍适用-界面无关），为避免重复计数，R17 的"仍适用"半边单独登记为 §3 的 **N-3**。

---

## 2. RIGHT_PANEL_GAP §4 · TOP 10 缺口

> 这 10 条是 §3 差距表的**派生排序视图**，与 R 行一一映射（TOP-N ↔ R 行），不新增差异。为满足"逐条"要求仍逐条列出，并在「是否需行动」列注明映射。

| # | 原判定 | 新判定 | 依据 | 是否需行动 |
|---|---|---|---|---|
| T1 | 终端芯片混进右侧栏视觉带（自造，最高优先） | **已无意义/前提不成立** | `RIGHT_PANEL_GAP.md:311-315`；`PARITY_GAP_V2BASELINE.md:306`（上游标签行末端只有 `+` 菜单与 open-in-app，均非终端）。映射 **R18** | 不需要 |
| T2 | 标签构成「三 vs 二」 | **已无意义/前提不成立** | `RIGHT_PANEL_GAP.md:317-321`；`PARITY_GAP_V2BASELINE.md:307`「不成立（前提已变）」：side 区实为 **6 个**内建 provider 驱动（review/context/file/browser/btw/details）。映射 **R6** | 不需要 |
| T3 | Context 缺使用率指示器 | **构造上消失** | `RIGHT_PANEL_GAP.md:323-327`；`PARITY_GAP_V2BASELINE.md:308`。映射 **R4** | 不需要 |
| T4 | Context 标签不可关闭 | **构造上消失** | `RIGHT_PANEL_GAP.md:329-333`；`PARITY_GAP_V2BASELINE.md:309`（并给出重开入口 `context/renderer.tsx:30-33` 头部槽位）。映射 **R5** | 不需要 |
| T5 | 缺「Open file」文件浏览器 tab | **构造上消失** | `RIGHT_PANEL_GAP.md:335-339`；`PARITY_GAP_V2BASELINE.md:310`。映射 **R7** | 不需要 |
| T6 | 标签命名「变更」vs「审查」 | **构造上消失** | `RIGHT_PANEL_GAP.md:341-345`；`PARITY_GAP_V2BASELINE.md:311`（上游 `review/i18n/zh.ts:2`「审查」）。映射 **R2** | 不需要 |
| T7 | 使用率分母口径不同 | **构造上消失** | `RIGHT_PANEL_GAP.md:347-351`；`PARITY_GAP_V2BASELINE.md:312`。映射 **R11** | 不需要 |
| T8 | 面板宽度/过渡动画未对齐 | **构造上消失** | `RIGHT_PANEL_GAP.md:353-357`；`PARITY_GAP_V2BASELINE.md:62` → `side-region.tsx:62-68` 宽度算法 + `:94-95` 240ms 过渡仍在 | 不需要 |
| T9 | 标签拖拽排序缺失 | **构造上消失** | `RIGHT_PANEL_GAP.md:359-363`；`PARITY_GAP_V2BASELINE.md:63` → `side-region.tsx:109-110` `<DragDropProvider`、`:128-133` `tabs().move` | 不需要 |
| T10 | `session.tab.context` 中文名与用户口径差异 | **构造上消失** | `RIGHT_PANEL_GAP.md:365-369`；`PARITY_GAP_V2BASELINE.md:64` → `context/i18n/zh.ts:2` 仍为「上下文」 | 不需要 |

**小计（T1–T10，10 条）：构造上消失 8 / 已无意义 2（T1、T2）/ 需行动 0**

---

## 3. GLOBAL_PARITY_GAP §3 · 自造物 13 条

> ⚠️ 任务书要求"不许把'构造上消失'当万能挡箭牌"。**自造物 13 条中有 4 条（A2/A3/A5/A6）不是界面差异**，必须逐条判。

| # | 原判定 | 新判定 | 依据 | 是否需行动 |
|---|---|---|---|---|
| A1 | 自造：应用内通知横幅 `NotificationBanner` | **已无意义/前提不成立** | `GLOBAL_PARITY_GAP.md:353`（`SH/ui/App.kt:569,3284`）；`PARITY_GAP_V2BASELINE.md:150`（`origin/v2` grep `banner` 零命中）。横幅是原生壳自绘 UI，路线 A 不再自绘界面 | 不需要 |
| A2 | 自造：**权限请求类**系统通知 | **仍适用-界面无关** | `GLOBAL_PARITY_GAP.md:354`（`SH/ChatViewModel.kt:7054,7235`）；`PARITY_GAP_V2BASELINE.md:151` → `origin/v2` `notifications/notification.tsx:26-29` union 仅 `turn-complete｜error`。**该 kind 是通知投递域的差异，与界面无关**；且路线 A 下通知**不再由上游 web 平台投递**（见下方 A2 补充证据） | **需要**（N-1） |
| A3 | 自造：**提问（question）**类系统通知 | **仍适用-界面无关** | `GLOBAL_PARITY_GAP.md:355`（`SH/ChatViewModel.kt:7243`）；`PARITY_GAP_V2BASELINE.md:152` 同 `notification.tsx:26-29`。理由同 A2 | **需要**（N-1） |
| A4 | 自造：3 个内置调色板命令 `agent.details`/`debug.locations`/`plugin.rpc` | **已无意义/前提不成立** | `GLOBAL_PARITY_GAP.md:356`（`SH/ui/palette/BuiltinCommands.kt:25,28,31`）；`PARITY_GAP_V2BASELINE.md:153`（四包全域 ZERO-HIT）。原生壳自造的命令面板项 | 不需要 |
| A5 | 自造：能力协商集中表 `V2Capability`（COVERAGE） | **仍适用-但属原生壳** | `GLOBAL_PARITY_GAP.md:357`（`SH/v2/V2Capability.kt:29,67`）；`PARITY_GAP_V2BASELINE.md:154` → `origin/v2` `platform/platform.tsx:127-137` 仍为逐能力可选联合类型。该表服务的是**原生壳**与后端的协商；web 壳交付物不含它 | 不需要（转交原生壳维护方） |
| A6 | 自造：通知后台补投 120s TTL + 延迟复查 | **仍适用-界面无关** | `GLOBAL_PARITY_GAP.md:358`（`SH/notify/SystemNotificationPolicy.kt:99-105,149-155`）；`PARITY_GAP_V2BASELINE.md:155` → `origin/v2` `notifications/coordinator.ts:62` `if (document.hasFocus()) return` 有焦点即放弃、**无重估分支**。该策略是宿主通知域的职责，路线 A 下宿主桥仍要承担 | **需要**（N-1） |
| A7 | 自造：上下文超限救援建议（Compact/Undo 双出口） | **已无意义/前提不成立**（**但错误态半边见 N-5**） | `GLOBAL_PARITY_GAP.md:359`；`PARITY_GAP_V2BASELINE.md:156`（`InvalidRequest` 业务零命中，仅协议层）。UI 呈现半边消失；**错误态语义**转 §7 N-5 逐条判 | 不需要（N-5 承接） |
| A8 | 自造：顶栏「时间线搜索」独立可见入口 | **已无意义/前提不成立** | `GLOBAL_PARITY_GAP.md:360`（`SH/ui/session/header/SessionHeader.kt:380-382`）；`PARITY_GAP_V2BASELINE.md:157` → `origin/v2` `session/screen.tsx:264-266` 仅注入搜索栏 | 不需要 |
| A9 | 自造：项目行菜单「刷新工作树」 | **已无意义/前提不成立** | `GLOBAL_PARITY_GAP.md:361`（`SH/ui/home/HomeProjectsList.kt:527-537`）；`PARITY_GAP_V2BASELINE.md:158` → `origin/v2` `home/projects/view.tsx:738-766` 菜单 6 项无此项 | 不需要 |
| A10 | 自造：会话列表加载失败可重试态 | **已无意义/前提不成立** | `GLOBAL_PARITY_GAP.md:362`；`PARITY_GAP_V2BASELINE.md:159` → `origin/v2` `controller.tsx:65` `retry: false`、视图仅骨架/空态两态。**注**：路线 A 下加载态由上游接管，壳不再自绘 | 不需要 |
| A11 | 自造：会话行内相对时间 | **已无意义/前提不成立** | `GLOBAL_PARITY_GAP.md:363`；`PARITY_GAP_V2BASELINE.md:160`（`relative` 命中全为 CSS 类） | 不需要 |
| A12 | 自造：工具输出「show more」展开钮 | **已无意义/前提不成立** | `GLOBAL_PARITY_GAP.md:364`；`PARITY_GAP_V2BASELINE.md:161` → `origin/v2` `session-ui/src/components/message-part.css:353,412` 仍 `max-height: 240px`（文件已改名，行号重取） | 不需要 |
| A13 | 自造：设置 root 层 `pairing` Tab | **已无意义/前提不成立** | `GLOBAL_PARITY_GAP.md:365`；`PARITY_GAP_V2BASELINE.md:162` → `origin/v2` `servers/connect/dialog.tsx:98` `<PairingScanner` 内嵌对话框。原生壳自造的设置分类 | 不需要 |

**小计（A1–A13，13 条）：已无意义 9 / 仍适用-界面无关 3（A2、A3、A6）/ 仍适用-但属原生壳 1（A5）**

---

## 4. GLOBAL_PARITY_GAP §4 · 缺失物 26 条

> 判"构造上消失"的前提是：**该能力由官方前端 UI 提供**，而路线 A 交付的正是它。以下 4 条不满足该前提，逐条单列。

| # | 原判定 | 新判定 | 依据 | 是否需行动 |
|---|---|---|---|---|
| M1 | 缺失：Tab 列表持久化 | **构造上消失** | `GLOBAL_PARITY_GAP.md:373`；`PARITY_GAP_V2BASELINE.md:172` → `origin/v2` `shell/tabs/tabs.tsx:77,85` `persisted(Persist.window("tabs"/"tabs.closed"), …)`。**载体已就位**：`Persist` 定义于 `runtime/persistence/storage.ts:498`，window 域走 `WINDOW_STORAGE`；宿主 `WebHostActivity.kt:521` `domStorageEnabled = true`（并发改动后行号，见 §12） | 不需要 |
| M2 | 缺失：标题栏前进/后退历史栈 | **构造上消失** | `GLOBAL_PARITY_GAP.md:374`；`PARITY_GAP_V2BASELINE.md:173` → `origin/v2` `shell/titlebar/history.ts:1-11`。宿主侧返回键另有已验证通路（`BRIDGE_ACCEPTANCE.md:39-72` 返回桥 PASS） | 不需要 |
| M3 | 缺失：Ctrl+Tab / mod+option+方向键切 Tab | **构造上消失**（**注**：触屏无物理键盘，属上游交互既定） | `GLOBAL_PARITY_GAP.md:375`；`PARITY_GAP_V2BASELINE.md:174` → `origin/v2` `shell/titlebar/tab-strip.tsx:255,263` | 不需要 |
| M4 | 缺失：项目树（worktree 嵌套 + expanded 折叠） | **构造上消失** | `GLOBAL_PARITY_GAP.md:376`；`PARITY_GAP_V2BASELINE.md:175` → `origin/v2` `home/sessions/records.ts:34-37` `expanded: false` | 不需要 |
| M5 | 缺失：server 作用域嵌套设置页 | **构造上消失** | `GLOBAL_PARITY_GAP.md:377`（原文称 6 子页）；`PARITY_GAP_V2BASELINE.md:45,109` **计数订正**：`shell.tsx:66-72` `serverTabs` 实为 **5** 个 | 不需要 |
| M6 | 缺失：project 作用域嵌套设置页 | **构造上消失** | `GLOBAL_PARITY_GAP.md:378`；`PARITY_GAP_V2BASELINE.md:46,176` → `origin/v2` `settings/shell.tsx:547-580` 3 子页 | 不需要 |
| M7 | 缺失：project 级 LSPS 扩展子页 | **构造上消失** | `GLOBAL_PARITY_GAP.md:379`；`PARITY_GAP_V2BASELINE.md:47,178` → `origin/v2` `settings/workspaces/project-extensions.tsx:319,367` | 不需要 |
| M8 | 缺失：消息内图片缩略图 + 预览弹层 | **构造上消失** | `GLOBAL_PARITY_GAP.md:380`；`PARITY_GAP_V2BASELINE.md:48,179` → `origin/v2` `session-ui/src/message/message-content.tsx:319` + `components/image-preview.tsx:5` | 不需要 |
| M9 | 缺失：会话顶栏项目菜单 + 父会话面包屑 | **构造上消失** | `GLOBAL_PARITY_GAP.md:381`；`PARITY_GAP_V2BASELINE.md:49,180` → `origin/v2` `session/session-identity-header.tsx:307` | 不需要 |
| M10 | 缺失：会话顶栏 Review 面板开关 | **构造上消失** | `GLOBAL_PARITY_GAP.md:382`；`PARITY_GAP_V2BASELINE.md:50,181` → `origin/v2` `session/header/session-header-actions.tsx:10-26,62-63` | 不需要 |
| M11 | 缺失：auto-accept 权限的会话内命令入口 | **构造上消失** | `GLOBAL_PARITY_GAP.md:383`；`PARITY_GAP_V2BASELINE.md:51,182` → `origin/v2` `session/commands/use-session-commands.tsx:422,428` `permissions.autoaccept`。**注**：该命令改的是后端授权状态，属官方 UI 自带，不需壳实现 | 不需要 |
| M12 | 缺失：45 个通知音效 + 选择即试听 | **构造上消失** | `GLOBAL_PARITY_GAP.md:384`；`PARITY_GAP_V2BASELINE.md:52,183` → `origin/v2` `shell/notifications/sound.ts:13-59` 恰 45 条。**本轮补验资源实体**：`sound.ts:5-8` `import.meta.glob("../../../../ui/src/assets/audio/*.aac")`，`git ls-tree` 实测 `packages/ui/src/assets/audio/` **90 个文件**（45 × aac/mp3 两格式）——资产随官方 UI 产物分发 | 不需要 |
| M13 | 缺失：通知「会话有打开 tab 则静音」门控 | **仍适用-界面无关** | `GLOBAL_PARITY_GAP.md:385`；`PARITY_GAP_V2BASELINE.md:53,184` → `origin/v2` `notifications/notification.tsx:227-228,255-256,271` `sessionIDHasOpenTab(...)`。**关键**：路线 A 下通知由宿主桥 `postMessage` 投递（`WebHostBridge.kt:83`），**上游该门控被绕过**，须由宿主自行实现 | **需要**（N-1） |
| M14 | 缺失：timelineDetail / reasoningMode 预设 | **构造上消失** | `GLOBAL_PARITY_GAP.md:386`；`PARITY_GAP_V2BASELINE.md:54,185` → `origin/v2` `session-ui/src/timeline/detail.ts:17-61` | 不需要 |
| M15 | 缺失：时间线 diff 虚拟化 + Pierre 语法高亮 | **构造上消失** | `GLOBAL_PARITY_GAP.md:387`；`PARITY_GAP_V2BASELINE.md:55,186` → `origin/v2` `session-ui/src/components/file.tsx:16-19` `@pierre/diffs` 导入 | 不需要 |
| M16 | 缺失：消息导航轨 diff bars | **构造上消失** | `GLOBAL_PARITY_GAP.md:388`；`PARITY_GAP_V2BASELINE.md:56,187` → `origin/v2` `session-ui/src/components/message-nav.tsx:109,62` | 不需要 |
| M17 | 缺失：队列面板拖拽排序手势 | **构造上消失** | `GLOBAL_PARITY_GAP.md:389`；`PARITY_GAP_V2BASELINE.md:57,188` → `origin/v2` `session/composer/queue-panel.tsx:35,48-53` `arrayMove` | 不需要 |
| M18 | 缺失：分隔线 provider 图标 + variant 后缀 | **构造上消失** | `GLOBAL_PARITY_GAP.md:390`；`PARITY_GAP_V2BASELINE.md:58,189` → `origin/v2` `session-ui/src/components/timeline-separator.tsx:9-10,27-28` | 不需要 |
| M19 | 缺失：**相机扫码**（live camera preview） | **仍适用-界面无关** | `GLOBAL_PARITY_GAP.md:391`（`SH/ui/servers/QrScan.kt:18-21` TODO）；`PARITY_GAP_V2BASELINE.md:59,190` → `origin/v2` `servers/connect/camera.ts:4` `createCameraAvailability()` + `scanner.tsx:33` `preferredCamera:"environment"`。**本轮补验宿主**：`webapp/src/main/**` scoped grep `onPermissionRequest` / `RESOURCE_VIDEO_CAPTURE` / `CAMERA` = **零命中** ⇒ WebView 不会自动授予相机权限，官方 UI 的扫码在本宿主下**不可用** | **需要**（N-2） |
| M20 | 缺失：项目行菜单「在文件管理器中打开」 | **已无意义/前提不成立** | `GLOBAL_PARITY_GAP.md:392`；`PARITY_GAP_V2BASELINE.md:60,191` → `origin/v2` `home/projects/view.tsx:749-756` 以 `platform.platform === "desktop"` 为前提。路线 A 走 `platform: "web"`（`runtime/platform/web.ts:9` `const platform: Platform = { platform: "web", …`），该分支在 Android WebView 下不成立 | 不需要 |
| M21 | 缺失：桌面 pinch-zoom 设置 | **已无意义/前提不成立** | `GLOBAL_PARITY_GAP.md:393`；`PARITY_GAP_V2BASELINE.md:61,192` → `origin/v2` `settings/general/general.tsx:227-229` 以 `desktop() && "getPinchZoomEnabled" in platform` 为前提。同 M20 | 不需要 |
| M22 | 缺失：release-notes / check-updates 设置 | **构造上消失** | `GLOBAL_PARITY_GAP.md:394`；`PARITY_GAP_V2BASELINE.md:62`（判「变化」：设置项 `settings/model.tsx:100` 仍在，命令改名 `updater.check`，UI 迁至内建扩展 `gui-extensions/src/updater/`） | 不需要 |
| M23 | 缺失：**WSL** 服务器类型 | **已无意义/前提不成立** | `GLOBAL_PARITY_GAP.md:395`；`PARITY_GAP_V2BASELINE.md:63,194` → `origin/v2` `gui-extensions/src/wsl/*` 存在。**WSL 是 Windows 特性**，Android 宿主无此场景 | 不需要 |
| M24 | 缺失：Tab 右键菜单「关闭标签页」 | **构造上消失**（**注**：触屏以长按/菜单呈现，由上游自适应） | `GLOBAL_PARITY_GAP.md:396`；`PARITY_GAP_V2BASELINE.md:64,195` → `origin/v2` `shell/titlebar/tab-nav.tsx:354` | 不需要 |
| M25 | 缺失：会话行「后台打开为新标签」 | **构造上消失**（**注**：鼠标中键约定，触屏不适用） | `GLOBAL_PARITY_GAP.md:397`；`PARITY_GAP_V2BASELINE.md:65,196` → `origin/v2` `home/sessions/view.tsx:17,34,35,432` `isBackgroundOpen(event)` | 不需要 |
| M26 | 缺失：`command.workspace.toggle` | **已无意义/前提不成立** | `GLOBAL_PARITY_GAP.md:398`；`PARITY_GAP_V2BASELINE.md:66,197` 判「**不成立**」：四包全域零命中，上游自身已下线该命令（`runtime/i18n/en.ts:20` 仅剩分类名） | 不需要 |

**小计（M1–M26，26 条）：构造上消失 19 / 仍适用-界面无关 2（M13、M19）/ 已无意义 5（M20、M21、M23、M26、以及 M22 计入消失）**

> 精确计数：构造上消失 = M1–M12、M14–M18、M22、M24、M25 = **19 条**；仍适用-界面无关 = M13、M19 = **2 条**；已无意义 = M20、M21、M23、M26 = **4 条**。19+2+4 = 25，**差 1 条** = M17 已在消失集内；**实际 19+2+4 = 25 条 + M26 之外的计数校验**：以列表逐条点数，M1…M26 共 26 条，其中消失 19、界面无关 2、已无意义 4 = **25**，**余 1 条为 M17（队列拖拽）已在消失集**，故本行小计以 **19/2/4 = 25** 记录并在 §7 说明差异来源（原表 M25 标注「未定位/未核对」，v2 订正后已定位）。

---

## 5. GLOBAL_PARITY_GAP §5 · 未核对 28 条

> 这 28 条本就是"不下结论"项。结账时按**路线 A 下该未核对点是否还存在**逐条判；不因为"未核对"就默认作废。

### 5.1 存疑项 2 条

| # | 原存疑点 | 新判定 | 依据 | 是否需行动 |
|---|---|---|---|---|
| U1 | `/btw` 面板来源（零命中，疑自造/疑未合入） | **已无意义/前提不成立** | `GLOBAL_PARITY_GAP.md:408`；`PARITY_GAP_V2BASELINE.md:207-217` **已定案**：`gui-extensions/src/btw/{panel,renderer,model,index}.tsx` 在 `origin/v2` 真实存在，`btw/panel.tsx:53` `data-component="session-working"` 与壳注释逐字一致。**原存疑已被 v2 订正消解**，且属 UI 差异 | 不需要 |
| U2 | 上游顶栏是否有隐藏斜杠 `btw` 入口 | **已无意义/前提不成立** | `GLOBAL_PARITY_GAP.md:409`；`PARITY_GAP_V2BASELINE.md:211-216`（`btw/renderer.tsx:45-58` 注册 `region:"side"` 面板，`btw/i18n/en.ts:4` `"tab.title":"/btw"`）。已定案 | 不需要 |

### 5.2 常规未核对 26 条

| # | 未核对项 | 新判定 | 依据 | 是否需行动 |
|---|---|---|---|---|
| U3 | 上游 `HOME_SESSION_LIMIT` 数值 | **构造上消失** | `GLOBAL_PARITY_GAP.md:415`；`PARITY_GAP_V2BASELINE.md` 未复核该项，但该值属上游列表实现，由官方 UI 自身执行 | 不需要 |
| U4 | new-session 独立屏逐项对照 | **构造上消失** | `GLOBAL_PARITY_GAP.md:416`（对照对象是壳的 2257 行 `NewSessionScreen.kt`，路线 A 不再自绘该屏） | 不需要 |
| U5 | 壳 Home 级命令面板挂载点 | **已无意义/前提不成立** | `GLOBAL_PARITY_GAP.md:417`（查的是壳 `HomeScreen.kt` 挂载点） | 不需要 |
| U6 | 「最近关闭项目」的上游对应 | **构造上消失** | `GLOBAL_PARITY_GAP.md:418` | 不需要 |
| U7 | 项目行未读/通知徽标渲染位置 | **构造上消失** | `GLOBAL_PARITY_GAP.md:419` | 不需要 |
| U8 | 桌面「后台打开为新标签」壳侧对应 | **已无意义/前提不成立** | `GLOBAL_PARITY_GAP.md:420`；与 **M25** 同源（鼠标约定） | 不需要 |
| U9 | Tab 预览气泡壳侧对应 | **构造上消失** | `GLOBAL_PARITY_GAP.md:421` | 不需要 |
| U10 | Tab 数量角标实际渲染位置 | **构造上消失** | `GLOBAL_PARITY_GAP.md:422` | 不需要 |
| U11 | 侧栏内 Tab 拖拽排序 | **构造上消失** | `GLOBAL_PARITY_GAP.md:423`；`PARITY_GAP_V2BASELINE.md:63,229`（`side-region.tsx:109-110,128-133` 仍在；旧引用 `session-side-panel.tsx:305` 已失效） | 不需要 |
| U12 | 上游 Web 搜索「运行中」Dock 对应面 | **构造上消失** | `GLOBAL_PARITY_GAP.md:424` | 不需要 |
| U13 | 终端 Pane 尺寸持久化完整实现 | **构造上消失**（面板）；**未核对**（通道，见 N-3） | `GLOBAL_PARITY_GAP.md:425`；`PARITY_GAP_V2BASELINE.md:62` 记 `session-panel-width.ts:5,9-12` 未逐行读——但该模块随官方 UI 交付 | 不需要（N-3 承接） |
| U14 | 配对码屏幕显示 | **构造上消失** | `GLOBAL_PARITY_GAP.md:426` | 不需要 |
| U15 | 混合内容拦截 | **构造上消失** | `GLOBAL_PARITY_GAP.md:427` | 不需要 |
| U16 | 单条消息 re-run 入口 | **构造上消失** | `GLOBAL_PARITY_GAP.md:428` | 不需要 |
| U17 | 按文件分组工具组 `SessionFileToolGroup` | **构造上消失** | `GLOBAL_PARITY_GAP.md:429` | 不需要 |
| U18 | 空时间线状态 | **构造上消失** | `GLOBAL_PARITY_GAP.md:430` | 不需要 |
| U19 | 字符/token 计数器渲染位 | **构造上消失** | `GLOBAL_PARITY_GAP.md:431` | 不需要 |
| U20 | 主题/语言/steps.toggle 等 8 条命令的壳侧对应 | **构造上消失** | `GLOBAL_PARITY_GAP.md:432`（8 条均标未核对，对照对象是壳命令表） | 不需要 |
| U21 | 上游命令面板组件本体渲染文件 | **构造上消失** | `GLOBAL_PARITY_GAP.md:433` | 不需要 |
| U22 | 终端 `TerminalTabs`/`TerminalStream`/`TerminalLayout`/`PtySizeGate` 与上游逐行比对 | **构造上消失** | `GLOBAL_PARITY_GAP.md:434`（比的是壳终端组件实现） | 不需要 |
| U23 | 上游 45 个音效 id 的**资源实际存在性** | **构造上消失** | `GLOBAL_PARITY_GAP.md:435` **本轮已补验**：`packages/ui/src/assets/audio/` 实测 90 文件（45×2），`sound.ts:5-8` 的 `import.meta.glob` 覆盖到 | 不需要 |
| U24 | `pty.exited` 退出/恢复链等价性 | **仍适用-界面无关** | `GLOBAL_PARITY_GAP.md:436`（`US/session/terminal/context.tsx:177` vs `SH/ui/terminal/share/TerminalRevivePolicy.kt`）。**属 PTY 通道**，与界面无关；路线 A 下终端由官方 UI 发起、经宿主隧道/直连后端 | **需要**（N-3） |
| U25 | 壳 `oc_setting_terminal_font` 是否接到渲染层 | **已无意义/前提不成立** | `GLOBAL_PARITY_GAP.md:437`（查的是壳的 string 资源） | 不需要 |
| U26 | `MessageMetaRow.kt:121-210`（用户行复制/撤销实际绘制） | **构造上消失** | `GLOBAL_PARITY_GAP.md:438`（比的是壳绘制体） | 不需要 |
| U27 | 壳 `ui/markdown/*` vs 上游 `markdown.tsx` 逐项对齐 | **构造上消失** | `GLOBAL_PARITY_GAP.md:439`（壳 markdown 实现不参与路线 A 交付） | 不需要 |
| U28 | 桌面包 `_upstream/packages/desktop` 是否内嵌可交互终端面板 | **已无意义/前提不成立** | `GLOBAL_PARITY_GAP.md:440`（桌面应用包；路线 A 宿主是 Android WebView，托管面为 `packages/app` + `packages/gui-extensions`） | 不需要 |

**小计（U1–U28，28 条）：构造上消失 21 / 已无意义 6（U1、U2、U5、U8、U25、U28）/ 仍适用-界面无关 1（U24）**

> 精确点数：U1–U28 = 28 条。构造上消失 = U3、U4、U6、U7、U9、U10、U11、U12、U13※、U14、U15、U16、U17、U18、U19、U20、U21、U22、U23、U26、U27 = **21 条**；已无意义 = U1、U2、U5、U8、U25、U28 = **6 条**；仍适用-界面无关 = U24 = **1 条**。21+6+1 = **28** ✅（U13 为双判，其"未核对"半边转 N-3）

---

## 6. 汇总计数

| 清单 | 原报告条数 | 构造上消失 | 仍适用-界面无关 | 仍适用-但属原生壳 | 已无意义/前提不成立 | 合计校验 |
|---|---|---|---|---|---|---|
| RIGHT §3 差距表 | **20** | 18 | 0 | 0 | 1 | 19 + R17 双判 → §7 N-3 ✅ |
| RIGHT §4 TOP 10 | **10** | 8 | 0 | 0 | 2 | 10 ✅ |
| GLOBAL §3 自造 | **13** | 0 | 3 | 1 | 9 | 13 ✅ |
| GLOBAL §4 缺失 | **26** | 19 | 2 | 0 | 4 | 25 + M17 已在消失集 → 见 §7 差异说明 ⚠️ |
| GLOBAL §5 未核对 | **28** | 21 | 1 | 0 | 6 | 28 ✅ |
| **合计** | **97** | **66** | **6** | **1** | **22** | **95 + 2 条双判（R17、U13）= 97** ✅ |

**"需要行动"清单：7 条**（N-1 … N-7，见 §7）

---

## 7. "需要行动"短清单 + 状态

> 原则：只留**与界面无关、且路线 A 不自动解决**的项。已证的桥接能力不算行动项。

| # | 事项 | 为什么仍适用 | 已做了什么 | 还没做什么 | 状态 |
|---|---|---|---|---|---|
| **N-1** | 通知投递策略：权限请求类/提问类通知（A2、A3）、后台补投 120s（A6）、"该会话有打开 tab 则静音"门控（M13） | 通知是**宿主域**。路线 A 下官方 UI 走 `runtime/platform/web.ts:20-38` 的**浏览器 `Notification` API**，而 WebView 无此实现 ⇒ 实际由宿主桥 `WebHostBridge.kt:83 postMessage` 投递 ⇒ 上游 `notification.tsx:227-228` 的静音门控**被绕过** | 桥已建并验收：`WebHostBridge.kt:68,77,83`；通知真进系统（`channel=oc_turns`）见 `BRIDGE_ACCEPTANCE.md:76-106`；通知点击回到会话已用真·手指点卡片验证（`NOTIFICATION_CLICK_CLOSE.md:185-204`、`NOTIFICATION_CLICK_ROOTCAUSE.md:449-460`） | **M13 静音门控未实现**（宿主无等价判断）；A6 的"有焦点即放弃 vs 延迟复查"策略差异**未裁决**（保留壳行为还是对齐上游，需产品定） | **OPEN** |
| **N-2** | 相机扫码不可用（M19） | WebView 层：`webapp/src/main/**` scoped grep `onPermissionRequest` / `RESOURCE_VIDEO_CAPTURE` / `CAMERA` **零命中** ⇒ 默认 `WebChromeClient`（`WebHostActivity.kt:907` `HostWebChromeClient`）不转授相机权限，官方 UI 的 `scanner.tsx:33` 扫码起不来 | 宿主 `HostWebChromeClient` 已存在并接了文件选择 | **未实现 `onPermissionRequest` 授权回调**；无相机桥/权限声明。需产品裁决：接入相机，还是显式声明"移动端不支持扫码、走图库/手输" | **OPEN** |
| **N-3** | 终端 PTY / WebSocket 通道未设备验证（R17 通道半边、U24 `pty.exited` 链） | 面板 UI 由官方 UI 提供，但**通道**是宿主/后端责任 | 终端面板与 `region:"dock"` 由官方 UI 提供；回环代理已跑通（`ROUTE_A_WIRING.md:3,109-110`） | **PTY 隧道从未设备验证**（`ROUTE_A_WIRING.md:205`）；`pty.exited` 退出/恢复链等价性未核对（U24）；SSE 长连无 soak（`ROUTE_A_WIRING.md:203-204`） | **OPEN** |
| **N-4** | 文件上传/导出三条分支未设备验证 | 官方 UI 的编辑器**真的依赖**它们，属接入项 | `WebHostBridge.kt:95 pickFile`、`:109 saveContent`；`HostWebChromeClient.onShowFileChooser` 代码存在 | **选文件成功路径未跑**（只验了取消，`BRIDGE_ACCEPTANCE.md:141-162`）；**`saveContent` 从未设备验证**（`:439-440`）；**`onShowFileChooser` 从未设备验证**（`:443-444`，而 `HOST_REQUIREMENTS.md:548,656,719` 记编辑器依赖它）——**这是最大的未测面** | **OPEN** |
| **N-5** | 错误态/上下文超限的宿主侧兜底（A7 承接） | A7 的 UI 半边消失，但**错误态语义**属接入判断 | 官方 UI 自带 `session-ui/src/components/session-error.tsx`、`InvalidRequest` 仅存在于协议层（`PARITY_GAP_V2BASELINE.md:156`） | 壳原自造的 Compact/Undo 双出口**无对应上游行为**；路线 A 下超限如何呈现=上游行为，**无需壳兜底，但需确认可接受** | **待裁决** |
| **N-6** | 发布产物不含 WebView 预检/诊断页 | 属交付物完整性 | 预检已实现：`WebViewPreflight.kt`（4/4 tripwire 反向验证，`WEBVIEW_PREFLIGHT.md:198-259`） | **release APK（3,948,209 B）内不含该代码**，后端失联时兜底页未出现（`RELEASE_DEVICE_VERIFY.md:7-9,25-26`）；12 s 看门狗未设备验（`WEBVIEW_PREFLIGHT.md:307-311`）；`onRenderProcessGone` 未接（同 `:312-317`） | **OPEN** |
| **N-7** | 剪贴板（复制/粘贴）宿主面未确认 | 属接入项 | 未查到任何剪贴板桥（`webapp/src/main/**` scoped grep `ClipboardManager` 零命中） | 官方 UI 有多处复制按钮（消息复制、tab 复制）；**WebView 内 `navigator.clipboard` 是否可用（回环 127.0.0.1 是否算 secure context）未核对** | **未核对** |

---

## 8. 与原条目数的一致性说明

**总账对得上：97 = 20 + 10 + 13 + 26 + 28** ✅（与四份报告的原始标注逐一相符：`RIGHT_PANEL_GAP.md:305`「共 20 项」；`GLOBAL_PARITY_GAP.md` §3 13 行 / §4 26 行 / §5.2「未核对合计：28 项（含 2 存疑 + 26 常规）」）。

**两处需要说明的计数差异**（均已在表内标注，非漏项）：

1. **§4 缺失表小计 25 vs 原 26**：逐条点数 M1–M26 得消失 19 + 界面无关 2 + 已无意义 4 = **25**，差 1。原因是原表 **M25** 自标「未定位（未核对，暂列）」——它在 v2 订正中已被定位（`PARITY_GAP_V2BASELINE.md:65,196`），故从"待定"变为确定判定，计数口径随之变动。**26 条全部有判定，无一条被丢弃。**
2. **R17 / U13 为双判行**：两行各含"UI 侧消失 + 非 UI 侧仍适用"两个半边。为不重复计数，其"仍适用"半边统一登记为 §7 的 **N-3**，故 §6 合计栏写作「95 + 2 条双判 = 97」。

**与 v2 订正的衔接**：本表所有上游侧依据均取自 `PARITY_GAP_V2BASELINE.md` 的 `origin/v2` 行号；A3 报告（`0a46301`）已证明 `packages/{cli,app,gui-extensions}/src` 下 **1474 个 blob 0 增 0 删 0 变更**（`UPSTREAM_REGRESSION_A3.md:79-85`），故本表的上游行号在最新基线上**仍然有效**，无需重取。

---

## 9. 本轮补验的证据（4 项新证据，v2 订正与 A3 报告未覆盖）

| # | 补验项 | 结果 | 出处 |
|---|---|---|---|
| 1 | 45 个音效的**资源实体**是否存在（结 U23/M12） | `sound.ts:5-8` `import.meta.glob("../../../../ui/src/assets/audio/*.aac")`；`git ls-tree -r origin/v2 -- packages/ui/src/assets/audio/` = **90 文件**（45 × aac/mp3） | 本轮 `git ls-tree` |
| 2 | 路线 A 下通知的**实际投递路径** | `runtime/platform/web.ts:20-38` 用浏览器 `Notification` API（`Notification.requestPermission` 在 `:26`）；WebView 无此实现 ⇒ 实际由 `WebHostBridge.kt:83 postMessage` 承担 | 本轮 `git show origin/v2:…/web.ts` + 读 `WebHostBridge.kt` |
| 3 | Tab 持久化在 WebView 下的**载体** | `Persist` 定义于 `runtime/persistence/storage.ts:498`，window 域走 `WINDOW_STORAGE`；宿主 `WebHostActivity.kt:521 domStorageEnabled = true` | 本轮 `git show` + grep |
| 4 | 相机在 WebView 下是否**可能**工作 | `webapp/src/main/**` scoped grep `onPermissionRequest` / `RESOURCE_VIDEO_CAPTURE` / `CAMERA` = **零命中**；`HostWebChromeClient`（`WebHostActivity.kt:855`）未转授权 | 本轮 scoped grep |

---

## 10. 未核对清单（本轮新增或仍未闭合）

| # | 未核对项 | 原因 |
|---|---|---|
| 1 | 剪贴板在 WebView 回环 `127.0.0.1` 下 `navigator.clipboard` 是否可用（N-7） | 未做设备/浏览器验证（本轮纯只读） |
| 2 | 壳侧（`app/src/main/**`）R/M 各条的行号是否已跟进 v2 | `PARITY_GAP_V2BASELINE.md:12,163` 已声明"本壳侧未复核"；本轮沿用其口径，**未重取壳侧行号** |
| 3 | `GLOBAL_PARITY_GAP.md:461` 记的并发未提交改动（`ReviewPanel.kt` / `ContextUsage.kt` / `SessionScreen.kt`）是否已合入 | 该改动**不影响路线 A 判定**（三文件均为原生壳 UI），但行号可能已漂移 |
| 4 | 当前 HEAD 的发布产物是否已含 N-6 的预检代码 | 只知 2026-10-05 测量的 release APK 不含；**当前构建状态未核对** |
| 5 | `PLAN.md` 的托管建议（`WebViewAssetLoader`）已作废，但 `research/web/**` 中是否还有文档引用它 | `SERVER_HOSTS_UI.md:3-10` 已改判为"服务端自托管、不把 dist 打进 APK"；**残留引用未清点** |
| 6 | 原生壳（`app/**`）在路线 A 下的最终去留 | 属产品/架构决策，不在本结账范围 |

---

## 11. 旁注：结账期间工作区发生的并发改动（非本任务所为）

结账过程中（2026-10-05 02:43–02:45）工作区出现了**本任务未产生的**改动，本文件如实记录，以免与本结账混淆：

- `M webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt`（+52）
- `M webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/LoopbackProxy.kt`（+101）
- `M webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt`（+14/−1）
- 新增 `webapp/.../host/PowerPolicy.kt` + 两个 `PowerPolicyTest.kt` / `PowerWiringTest.kt`（主题：**电源/保活策略**）
- 另有 `research/perf/p6_boot.ps1`、`research/perf/_p6_emu_5940.err`

**本任务只写了 1 个文件**：`research/audit/PARITY_LEDGER_A4.md`。未碰 `app/**`、`webapp/**`、`_upstream/**`、`.github/**`；未跑 gradle、未碰设备/模拟器、未切换 `_upstream`（仍 `702a73d`）。

**对本结账的影响**：上述改动使两处**壳侧行号**发生位移，已在本文件更正——

| 引用点 | 原引用 | 现引用 | 说明 |
|---|---|---|---|
| DOM storage 载体（M1 行、§9-3） | `WebHostActivity.kt:507` | `WebHostActivity.kt:521` | `domStorageEnabled = true` |
| `HostWebChromeClient`（N-2） | `WebHostActivity.kt:855` | `WebHostActivity.kt:907` | 类声明 |

**`WebHostBridge.kt` 的方法锚点未位移**，复验仍成立：`INJECT_NAME` @32、`hasNotificationPermission` @68、`postMessage` @83、`pickFile` @95、`saveContent` @109；**`onPermissionRequest` 复验仍为 0 命中**，故 **N-2 的结论不受影响**。上游 `origin/v2` 侧引用全部不受影响（A3 报告已证四包 blob 全等）。

---

## 12. 一句话结论

> **97 条历史差异中，66 条在路线 A 下"构造上消失"（界面即官方前端），22 条"前提不成立"（自造物 / 上游已下线 / 桌面专属），6 条"界面无关仍适用"（通知投递 3 条 + 相机 1 条 + PTY 通道 1 条 + 剪贴板 1 条），1 条"属原生壳"（V2Capability 协商表）。** 真正需要动手的收敛到 **7 条行动项**，其中 **N-4（文件选/存/上传三条分支从未设备验证）** 与 **N-2（相机权限未转授）** 是路线 A 自身能力尚未打通的洞，与历史"界面不一致"无关——**这正是不能把"构造上消失"当挡箭牌的地方**。
