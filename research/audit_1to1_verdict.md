# 「是否与 opencode v2 完全一致」汇总裁决

> **后续进展**：路线 C（移动端形态对齐 + P0/P1 收敛）已执行完毕，本文 §3 的 P0/P1 缺口大部分已闭合、并额外修掉 6 个真 bug。执行结果与剩余项见 **`research/route_c_result.md`**；独立验证见 `research/verify_wave4.md`。本文保留为**审计基线**（路线 C 前的状态）。

> 结论：**不是完全一致，也不是接近一致。**
> 分四路只读审计（后台/接入/会话界面/设置与面板），共核对 **607 行**逐项对照，全部带双方 `file:line` 证据。
> 四份明细：`research/audit_api_events.md`、`audit_routes.md`、`audit_session_ui.md`、`audit_settings_palette.md`。
> 本文只汇总与裁决；每条最强结论均已由 Lead 抽样复核（复核修正见 §5）。

---

## 1. 两条根本性事实（决定"能不能一致"）

### 1.1 上游 v2 前端连的是 `/api/*`，我们连的是 v1 无前缀面

| | 上游 v2 | 本 App |
|---|---|---|
| API 前缀 | `/api/*`（`_upstream/packages/app/e2e/utils/mock-api.ts:36-281`） | 无前缀（`OpenCodeClient.kt`） |
| SSE | `GET /api/event` | `GET /global/event` |
| 说明 | `_upstream/packages/app/src/runtime/server/client.tsx:1-2` 用 `@opencode/client` | 决策记录见 `research/04_data_api.md:10-11,29-35,103` |

### 1.2 本机 server（opencode **1.18.31**）只实现了 v2 面的一部分 —— 实测

对 `http://127.0.0.1:4096` 逐路径探测（`curl -m 6`）：

| v2 路径 | 实测 | v2 路径 | 实测 |
|---|---|---|---|
| `/api/session` | ✅ JSON | `/api/config` | ❌ SPA HTML |
| `/api/session/:id` | ✅ JSON | `/api/vcs` | ❌ SPA HTML |
| `/api/session/:id/message` | ✅ JSON | `/api/file` | ❌ SPA HTML |
| `/api/provider` | ✅ JSON | `/api/find/file` | ❌ SPA HTML |
| `/api/agent` | ✅ JSON | `/api/permission` | ❌ SPA HTML |
| `/api/location` | ✅ JSON | `/api/question` | ❌ SPA HTML |
| `/api/event` | ✅ SSE | `/api/lsp`、`/api/mcp`、`/api/global/health`、`/api/info`、`/api/project` | ❌ SPA HTML |

**推论**：即使把 Android 全面改写成 v2 客户端，也无法在这台 server 上跑通（config/vcs/file/find/permission/question 全缺）。**"接入 1:1 与 v2"在当前 server 版本下不可达**；能做到的是"接入 1:1 与这台 server 实际提供的 v1 instance API"。

### 1.3 v1 SSE 帧形状已实测

`GET /global/event` 实际帧：`{"payload":{"id":"evt_…","type":"server.connected","properties":{}}}`
→ 无 `directory`、无 `sync` 包装；`ChatStore.unwrapGlobal`（`ChatStore.kt:433-434`）成立，与实测流式渲染一致。

---

## 2. 分层统计（607 行对照汇总）

| 审计面 | 行数 | 1:1 | 近似 | 缺失 | 死/未确认/不适用 | 1:1 占比 |
|---|---|---|---|---|---|---|
| 接入：REST 端点（已接入 22 个） | 22 | 9 | 12 | 0 | 未确认 1 | 41% |
| 接入：v1 事件（17 个） | 17 | 4 | 9 | 8 | 无上游定义 2 | 24% |
| 界面与路由（表 A–C） | 97 | 3 | 45 | 40 | 9 不适用 | 3% |
| 会话界面（8 分区） | 349 | 61 | 186 | 99 | 3 未确认 | 17.5% |
| 设置/命令面板/Providers | 122 | 6 | 30 | 53 | 死 15 + 未确认 1 + 独有 1 | 5% |
| **合计** | **607** | **83（13.7%）** | **282（46.5%）** | **200（33%）** | 41 | **13.7%** |

> 口径：`1:1` = 结构/字段/规格逐项相同；`近似` = 有实现但差异明确；`缺失` = 无；`死` = 有 UI 无消费方。界面部分为**规格级**审计（读 CSS/组件树），未做逐像素渲染比对。

---

## 3. 按严重度排序的缺口

### P0 —— 结构性 / 影响"能不能用"

1. **设置页 21 个字段里只有 3 个真正生效**（`colorScheme`、`autoAcceptPermissions`、`language`）。外观/通知/音效/Advanced 共 18 个字段是死设置。
   → **设备实测**：设置里「提供商」显示"没有已连接的提供商"、「模型」显示"没有可用模型"，而 server 实际有 **10 个已连接 provider**、模型目录完整（composer 的模型选择器正常）。两个 tab 的唯一数据构造点 `ui/App.kt:588` 从未赋值。
2. **part 类型 12 种只渲染 5 种**：`text/tool/reasoning/compaction/file`；缺 `subtask/step-start/step-finish/snapshot/patch/agent/retry`，且这 7 种连 payload 都没解析（`ChatStore.kt:304-324`），`raw` 未带入 `UiPart` → 数据不可逆丢失。
   → 实测 server 真实 part 集合：`reasoning, step-finish, step-start, text, tool`；上游对 `retry`/`agent`/`patch`/`subtask` 有专门行或卡片（`session-retry.tsx`、`projection.ts:668-688`、`tool-renderer.tsx:378-388`）。
3. **`message.part.removed` 完全未实现**（全仓零命中）→ 服务端删除的 part 永久留在时间线。
4. **命令面板 7 条命令里 5 条点了没反应**：`AppCommands.kt:41,46,51,56,67` 全是 `onSelect = {}`（`session.new/workspace.new/session.previous/session.next/review.toggle`）；上游注册 51 条。除 `mod+k`/`mod+shift+p`/`mod+,` 外没有任何 keybind 接入键盘。
5. **时间线"分组+折叠"心智未移植**（观感差异总根源）：上游默认 preset `compact`，所有工具 + reasoning 收进一个 `Used N <tools>` 折叠组；我们是"每个工具一张展开卡"，且分组行文案（"已探索 / 3 reads…"）与上游完全不同。

### P1 —— 明显可见

6. 工具卡**有面卡片**（`layer01` + 0.5dp 边框）vs 上游**透明 + 12px 内容导轨**（`collapsible.css:1-14`、`basic-tool.css:314-318`，已复核）。
7. `⋮` 图标是**竖三点**，上游是**横三点**（`ic_oc_dots.xml` `M12,5.5/M12,12/M12,18.5` vs `icon.tsx:141`，已复核）。
8. **上下文用量环是死控件**：`limit = null`（`SessionScreen.kt:272`）+ 不可点 + 无 tooltip。
9. **移动端形态照搬桌面**：会话抽屉是左侧 Material 抽屉，上游 v2 自己的手机分支是**底部 sheet**（`shell/mobile-drawer.css` 存在，已复核）；Review 手机端是四个整宽 tab，我们硬编码 340dp 侧栏。
10. 发送按钮配色相反（我们是扁平蓝 `#3b5cf6`；上游 light 深灰底 / dark 浅灰底 + 渐变 + 阴影）。
11. 助手/用户消息**无元信息**（Agent·Model·时长·复制·revert）。
12. 时间线缺 **Working shimmer** 与 **Jump to latest**。
13. diff 渲染 11sp 且整行染红绿；上游 13px/24px、只染背景保留语法前景。
14. `session.error` 把对象当字符串（`ChatStore.kt:376-379`）→ 错误卡显示整段 JSON；无任何测试覆盖（`grep session.error` 测试零命中）。
15. `always[]`、`metadata`、`tool` 权限字段解析后**零展示**（"始终允许"看不到将持久化的 pattern）。
16. 首启无服务器时**没有连接页**（上游 `servers/connect/screen.tsx` 整屏表单 + 连通性校验），只有内联提示；添加服务器也不做健康检查。

### P2 —— 功能面缺失（上游有、我们没有）

会话行菜单（删除/导出/归档/分叉）、会话摘要面板、时间线搜索栏、待发送队列面板、供应商连接/OAuth/API key 对话框、设置搜索与嵌套页（Projects/Workspaces/Extensions/Experimental/About）、快捷键录制的 9 行只读表 vs 上游可录制/冲突检测、移动端四 tab 会话视图（Session/Changes/Files/Terminal）、用量超限对话框链路、`GET /pty/shells`、分页/limit（长会话全量拉取）、`file.open` 命令、`GET /global/health` 协议探测。

### P3 —— 已有但细节不符

dock 底色弱一层（`layer01` vs 上游 `surface-raised-stronger`）、消息宽度/间距零散差异、Review 面板 filter/行号/词级 diff/行评论缺失、`session.status` 的 retry 细节丢弃、认证 2 处偏差（见 §5）。

---

## 4. 已经 1:1 或接近的部分（不是全盘差距）

- **接入层已接入的 22 个端点里 9 个 1:1**：`GET /config`、`GET /path`、`GET /file`、`POST /session/:id/abort`、`GET /permission`、`GET /question`、`POST /question/:id/reply`、`POST /question/:id/reject`、`GET /session/:id/diff`。
- **question 协议完整 1:1**（`multiple`、`custom`、`options.label/description`、`answers: string[][]`）。
- **权限协议字段解析 1:1**（7 字段全解析；差在 UI 只展示 2 个）。
- **docket 层保真度最高**：dock 分区 63 行中 19 项 1:1（30%）。
- **全局事件路由门控完整**（`eventSessionId` 三级回退 + `acceptsSession`）。
- 设备实测通过的实际工作流：连接 → 项目/会话列表 → draft 新建会话 → 发送 → SSE 流式渲染 → 工具卡 → question dock 全链路 → 中止 → Review 面板/文件树 → 命令面板（真实会话/文件数据）→ 模型选择器 + chip 默认模型回退。

---

## 5. Lead 复核修正（不采信 subagent 原话的条目）

1. **"P0-1：`sync` 包装导致全部 durable 事件被丢弃"→ 不成立**（至少本 server）。实测 `/global/event` 帧为 `{"payload":{...}}`，无 `syncEvent`；且流式渲染实测正常。属防御性缺口，不是当前故障。
2. **"P0-3：刷新路径基本失效"→ 需收窄**。实测：REST 改标题后界面 12 秒内自更新（`session.updated` 带 `info.id`，`sessionIdOf` 命中）。真实缺陷范围是：`session.idle`/`session.status`/`session.error`/`message.*`/`part.*` 不触发兜底刷新（`sessionIdOf` 读 `properties.id`，`ChatStore.kt:419-424`）。影响是"漏帧后不自动对账"，不是"刷新全坏"。
3. **认证偏差确认成立**（`OpenCodeClient.kt:54-57`）：`"$username:$password"` 在 password 为 null 时拼出字面量 `null`；上游硬编码 `opencode:` 并忽略 legacy username（`runtime/server/api.ts:5-6`）。本 server 无鉴权，故当前无影响。
4. **`⋮`/工具卡/part 覆盖/空壳面板/空命令** 五项均已亲自复核，结论维持。

---

## 6. 取证限制（影响结论边界）

- `_upstream` 是 **sparse checkout**：只有 `packages/{app,schema,session-ui,theme,ui}`；`protocol`、`server`、`core`、`cli`、`tui`、`sdk`、`client` 均不存在 → 上游 v1 端点定义、`@opencode/client` 的具体路由无法取证（相关行标 `未确认`）。
- `schema/src/v1/` 只有 5 个文件且是纯 schema，无端点定义。
- 界面审计为**规格级**（读 CSS/组件树），未做逐像素渲染比对；`1:1` 指规格值相同。
- 与上游源码不符的既有注释 8 处（如 `App.kt:84-90` 称上游用 `size="x-large"` 承载设置，实际上游是真实路由 + 全屏 surface），会误导后续开发，建议修正。

---

## 7. 若要与 v2 收敛：三条路线

| 路线 | 内容 | 可达"完全一致"? | 工作量 |
|---|---|---|---|
| A. 保持 v1 接入，只补 UI/功能 | 修 §3 的 P0/P1/P2，UI 对齐上游移动端分支 | 否（接入面结构性不同） | 中（可增量） |
| B. 迁移到 v2 `/api/*` | 需 server 升级到提供完整 `/api` 面 | 否（当前 server 缺 11 条路径） | 大（客户端重写 + 等 server） |
| C. A + 与上游移动端分支逐屏对齐 | 补 P0，按上游 `@media`/`isDesktop()` 双形态实现 | 界面可达"高保真"，接入仍为 v1 | 大 |

**建议**：先做 A 里的 P0（空壳面板接线、part 渲染 + 保留 raw、part.removed、命令面板副作用、死设置项接线或隐藏、用量环取 `limit.context`），这几条投入小、用户可感知收益最大；再决定是否走 C。
