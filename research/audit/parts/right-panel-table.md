# RIGHT_PANEL_GAP · v2 基线纠正复核表

- 复核日期：2026-10-04
- 权威基线：`origin/v2` = `ecfa884`（2026-10-04）
- 旧审计基线：`702a73d`（2026-09-20），已过期 526 个提交
- 原文档：`research/audit/RIGHT_PANEL_GAP.md`（v161），其 §3「差距表」为实际的 **20 项**清单；§4 为 TOP 10 缺口
- 复核对象：**原文档的每一条判定**，逐条对照 `origin/v2` 重新取证
- 取证方式：只读 git 对象（`git -C _upstream show/grep/ls-tree/cat-file origin/v2:...`），未改动 `_upstream` 工作树（仍停在 `702a73d`）

## 复核口径（必读）

1. **本表只复核「上游侧」证据。** 原文档每项都是「上游 vs 本壳」双向对比；本轮只对**上游侧**在 `origin/v2` 重新取证。本壳侧（`app/src/main/**`）的行号与结论**沿用原文档，本轮未复核**，在「依据」列中以「本壳侧未复核」标出。
2. **文件迁移 ≠ 功能消失。** `origin/v2` 已把 GUI 面板整体迁入内建扩展包 `packages/gui-extensions`（`gui-extensions/src/main.ts:19-32` 用 `Extension.compose(context, btw, debug, terminal, file, review, details, browser, pairing, updater, ssh, wsl)` 静态组合；`packages/app/package.json` 以 `workspace:*` 依赖它）。旧文档所有 `_upstream/packages/app/src/session/files/**` 引用均已失效。
3. **判定含义**：
   - `成立` = 原判定的**结论**在 `origin/v2` 依然正确（依据文件可能已换）
   - `不成立` = 原判定在 `origin/v2` 被证伪
   - `变化` = 结论方向仍对，但**判定所依赖的结构/计数/文件已改变**，需重述

---

## 一、§3 差距表 20 项逐条复核

| # | 原判定 | 新判定 | 依据（origin/v2 文件:行号 + 原文摘录） |
|---|---|---|---|
| 1 | 右侧栏容器「一致」（`session-side-panel.tsx:258-273` `<aside id="review-panel">`） | 变化 | `session-side-panel.tsx` 在 `origin/v2` **已不存在**（`git cat-file -e` → GONE；`ls-tree` 下 `packages/app/src/session/` 无 `files/` 目录）。内容迁至 `packages/app/src/runtime/extension/side-region.tsx:84-86`：`<aside` / `id="review-panel"` / `aria-label={language.t("session.panel.reviewAndFiles")}`。但**结构已变**：标签行不再是硬编码的 review/context 两个 Trigger，而是 `<For each={props.region.keys()}>`（`side-region.tsx:170-188`）动态渲染扩展注册的标签。本壳侧未复核 |
| 2 | Review/变更标签「不一致（命名）」 | 成立 | `packages/gui-extensions/src/review/renderer.tsx:137-139` `ctx.add(Panel, {` / `id: "main",` / `region: "side",`；`:154` `list: (input) => (!layout.narrow() && input.session.project ? [tab] : [])`。中文 `review/i18n/zh.ts:2` `"tab.title": "审查",`。上游半边仍为「审查」，本壳半边「变更」沿用原判定（未复核） |
| 3 | Context/上下文标签「一致」 | 变化 | `context/renderer.tsx:36-44` `ctx.add(` / `Panel,` / `(): Panel => ({` / `id: "main",` / `region: "side",` / `legacy: { context: "main", "usage:context": "main" },` / `list: (input) => (input.open.includes("main") ? [tab] : [])`；`context/i18n/zh.ts:2` `"tab.title": "上下文",`。**结论（上游有上下文标签）仍成立，但标签键已变**：旧文档的 `value="context"` 已迁移为 `legacy: { context: "main", "usage:context": "main" }` 的键重写契约（实现见 `app/src/runtime/extension/panels.tsx:126-130` 注释「keys stored before extensions (e.g. "context")」） |
| 4 | Context 标签使用率指示器「缺失」 | 成立 | `context/renderer.tsx:22-26` `label: (state) => (` / `<div class="flex items-center gap-2">` / `<SessionContextUsage session={state.session} variant="indicator" />` / `<div>{ctx.t("tab.title")}</div>`。上游 context 标签**仍内嵌指示器**，本壳无 → 「缺失」判定不变 |
| 5 | Context 标签可关闭「缺失」 | 成立 | `context/renderer.tsx:18-19` `draggable: false,` / `closable: "compact",`；渲染 `app/src/runtime/extension/panel-trigger.tsx:103` `if (closable === "compact") return compactClose()`、`:123` `onMiddleClick={() => props.onClose(props.value)}`、`:95` `<Tabs.CloseButton onClick={() => props.onClose(props.value)} .../>`。上游 context 标签仍可关闭（compact 样式 + 中键关闭），本壳三标签不可关 → 判定不变 |
| 6 | 文件标签「不一致」（动态多文件 vs 单个固定「文件」tab） | 成立 | `file/renderer.tsx:216-232` `list(input) {` / `return input.open.flatMap((id) => {` / `if (id === OPEN) return [launcher]` / `const created = fileTab(input.screen.file, id)` / `return [created]`。上游**仍是每文件一个动态标签**（能力整体迁到 `file` 扩展）。本壳单个固定「文件」tab 的差距不变 |
| 7 | 缺「Open file」文件浏览器 tab「缺失」 | 成立 | `file/renderer.tsx:202` `legacy: { "open-file": OPEN },`；`:218` `if (id === OPEN) return [launcher]`；入口菜单 `file/renderer.tsx:264-266` `ctx.add(MenuItem, {` / `menu: "session.panel",` / `id: "open",`。上游 Open-file 启动器标签仍存在（并注册进 `+` 菜单），本壳无 → 判定不变 |
| 8 | Browser 标签「不一致」 | 变化 | `browser/renderer.tsx:148-153` `ctx.add(Panel, {` / `id: "main",` / `region: "side",` / `list(input) {` / `const ids = model()?.tabs(input.session, input.open) ?? input.open`；`browser/i18n/zh.ts:4` `"tab.title": "浏览器",`。**Browser 在 `origin/v2` 仍是 side 区动态标签**（不再是旧 `session-side-panel.tsx:388-409` 的 `isSessionBrowserTab` 分支）。上游半边结论不变，实现位置全变 |
| 9 | 16 项元数据表「一致」 | 成立 | `context/tab.tsx:181-202` `const stats = [` … 恰好 16 条：`stats.session`(182) / `stats.messages`(183) / `stats.provider`(184) / `stats.model`(185) / `stats.limit`(186) / `stats.totalTokens`(187) / `stats.usage`(188) / `stats.inputTokens`(189) / `stats.outputTokens`(190) / `stats.reasoningTokens`(191) / `stats.cacheTokens`(193) / `stats.userMessages`(197) / `stats.assistantMessages`(198) / `stats.totalCost`(199) / `stats.sessionCreated`(200) / `stats.lastActivity`(201)。数量与字段名与原文档所列 16 项**逐项吻合**（文件由 `session/files/session-context-tab.tsx` 迁到 `gui-extensions/src/context/tab.tsx`） |
| 10 | 16 项中文字段名「一致」 | 变化 | 键**已从 `packages/app/src/runtime/i18n/zh.ts` 迁到扩展自己的 i18n**：`gui-extensions/src/context/i18n/zh.ts:11-26` `"stats.session": "会话",` … `"stats.lastActivity": …`。原文档引用的 `zh.ts:573-588` 在 `origin/v2` 已无这些键（`git grep 'stats.session' -- packages/app/src/runtime/i18n/zh.ts` 无命中）。中文文案本身未变 |
| 11 | 使用率算法「不一致」（分母口径） | 成立 | `context/tab.tsx:132` `usage: entry?.model?.limit.context ? Math.round((total / entry.model.limit.context) * 100) : null,`；`:129` `limit: entry?.model?.limit.context,`。上游分母**仍是单一 `limit.context`**，本壳取 `min(limit.input, limit.context)` 的差距不变 |
| 12 | 词元总数算法「一致」 | 成立 | `context/tab.tsx:118-122` `message.tokens.input +` / `message.tokens.output +` / `message.tokens.reasoning +` / `message.tokens.cache.read +` / `message.tokens.cache.write`。五项求和公式与原文档所录完全一致 |
| 13 | 系统提示词块「一致」 | 成立 | `context/tab.tsx:154` `const systemPrompt = createMemo(() => {`；`:297` `<Show when={systemPrompt()}>`；`:300` `<div class="text-12-regular text-text-weak">{ctx.t("systemPrompt.title")}</div>`；i18n `context/i18n/zh.ts:8` `"systemPrompt.title": "系统提示词",`。上游仍有该块（「本壳另有 API 指令块、上游无」这半句属本壳侧，未复核） |
| 14 | 原始消息列表「一致」 | 变化 | `context/tab.tsx:321` `<Accordion multiple>`；`:56-58` `<Accordion.Item value={props.message.id}>` / `<StickyAccordionHeader>` / `<Accordion.Trigger>`；`:310` `<div class="text-12-regular text-text-weak">{ctx.t("rawMessages.title")}</div>`；i18n `context/i18n/zh.ts:9` `"rawMessages.title": "原始消息",`。能力一致，但**组件已换**：旧的独立 `RawMessage` 组件被 `Accordion.Item` + `StickyAccordionHeader` 取代 |
| 15 | 导出会话按钮「一致」 | 变化 | 按钮仍在：`context/tab.tsx:315` `onClick={exportSession}`、`:318` `<span>{ctx.t("export.session")}</span>`（i18n `context/i18n/zh.ts:10` `"export.session": "导出会话",`）。**但导出实现换了模块**：`context/tab.tsx:16` `import { fetchSessionExport, sessionExportFilename } from "./export"` → `gui-extensions/src/context/export.ts`；`:215` `const filename = sessionExportFilename(data.info)`。原文档称其走 `session/commands/export.ts` **已不成立**——该文件在 `origin/v2` 仍存在，但 import 它的是另外三处：`home/sessions/controller.tsx:23`、`session/commands/use-session-commands.tsx:11`、`session/timeline/controller.tsx:18` |
| 16 | 移动端 Context 视图「一致」 | 变化 | 移动端 context 视图仍存在，但承载方式变了：`context/renderer.tsx:43` `mobile: { title: ctx.t("mobile.title"), order: 10, kind: "menu" },`；`:49-55` `<Show` / `when={panel.placement() !== "mobile"}` / `fallback={` / `<Suspense>` / `<SessionContextTab session={props.session} />`。原文档引用的 `screen.tsx:307-309`（整屏 usage view）**已不是那个东西**：`origin/v2` 的 `session/screen.tsx:306-314` 是移动端 `<DockRegion`（`view=` / `screen=` / `sidebar=` / `fill` / `embedded`）。移动端面板改由 `runtime/extension/mobile.tsx` 的 MobilePanel 系统派发 |
| 17 | 终端面板存在性「一致」 | 成立 | 容器：`app/src/runtime/extension/panels.tsx:467-470` `<aside` / `ref={root}` / `id="terminal-panel"`。两处位置仍在：`session/screen.tsx:496` `data-slot="session-side-terminal-region"`（side 堆叠，其内 `:511` `<DockRegion`）与 `session/screen.tsx:535` `data-slot="terminal-panel-presence"`（底部通栏，其内 `:559` `<DockRegion`）。**但实现组件已换**：旧 `session/terminal/panel.tsx` 在 `origin/v2` GONE，现为 `panels.tsx:387` `export function DockRegion(...)` |
| 18 | 终端面板在右侧栏内「本壳自造（上游无）」 | 成立（依据须重述） | 终端**不是 side 区标签**：`terminal/renderer.tsx:144-146` `ctx.add(Panel, {` / `id: "main",` / `region: "dock",`。两个 region 严格分离——`panels.tsx:412` `.filter((item) => item.value.region === "dock")`（dock 只收 dock 面板）vs `panels.tsx:106` `host.items(Panel).filter((item) => item.value.region === input.region)`（side 标签条只收 side）。故上游右侧栏标签行内确无终端标签。⚠️ 需限定口径：终端**仍可与右侧栏同栏堆叠**（`screen.tsx:496` 该 div 就位于 `SideRegion`（`:454`）所在的同一列内），且终端**内部**有自己的可拖拽标签条 `terminal/tab.tsx:13` `export function SortableTerminalTab(props: {` / `terminal: LocalPTY,`。所以准确表述是「上游无**右侧栏标签行内**的终端标签」，而非「终端与右侧栏毫无位置关系」 |
| 19 | 终端开启方式「不一致」 | 变化 | 原文档依据 `command.tsx:18` `EDITABLE_KEYBIND_IDS = new Set(["terminal.toggle", ...])` 在 `origin/v2` **已不存在**（`git grep EDITABLE_KEYBIND_IDS -- command.tsx` 无命中；该文件 `:79` 只剩 `export const CommandSection = Schema.Literals(["general", "session", "navigation", "model", "terminal", "prompt"])`）。命令改由终端扩展注册：`terminal/renderer.tsx:45-52` `id: "toggle",` / `bind: "ctrl+\`",` / `slash: { name: "terminal", after: "open" },` / `editable: true,`；`:75-80` `id: "new",` / `bind: "ctrl+alt+t",`。**结论方向仍成立**（上游靠命令/快捷键/`/terminal` 斜杠命令开终端，不靠右侧栏标签），但依据整段作废重写 |
| 20 | 终端面板高度 60%「一致」 | 变化 | 60% 规则仍在，但**位置换了模块**：`panels.tsx:428-429` `const max = () => store.viewport * 0.6` / `const regionHeight = () => Math.min(height(), max())`；底部 dock 的拖拽上限同值：`screen.tsx:549` `max={typeof window === "undefined" ? 600 : window.innerHeight * 0.6}`。原文档引用的 `session/terminal/panel.tsx:75-76` 在 `origin/v2` GONE |

**统计：成立 11 / 变化 9 / 不成立 0 / 未核对 0。**
（20 项的上游侧证据全部实读，无「未核对」。变化项中，**没有一项的结论方向被推翻**，但**每一项的依据文件都已改变或新增约束**——这正是旧审计误报的根源。）

---

## 二、§4 TOP 10 缺口逐条复核（补充）

| # | 原判定 | 新判定 | 依据（origin/v2 文件:行号 + 原文摘录） |
|---|---|---|---|
| TOP-1 | 终端芯片混进右侧栏视觉带 →「本壳自造，最高优先」 | 成立（依据须重述） | 上游右侧栏标签行**末端确实有控件位**，但里面没有终端：`side-region.tsx:189-196` `<div class="h-full shrink-0 sticky end-0 z-10 ...">` / `<AddMenu items={menu()} />`（`+` 菜单，内容来自 `menu === "session.panel"` 的 MenuItem，`:72-77`）；以及 `side-region.tsx:198-215` `data-slot="session-side-panel-actions"` / `class="session-review-v2-open-in-app-slot ..."` / `<ExtensionSlot at="session.panel.end" ...>`（file 扩展在 `file/renderer.tsx:293-298` 往这里挂 `<OpenInAppButton .../>`）。即：上游有「标签行右端的 + 与 open-in-app」两个 affordance，**均非终端**；终端归 dock（见上表 #18）。故「本壳那颗 28dp 终端芯片在上游无对应物」仍成立，但原文档用 `session-side-panel.tsx:23-27` 作依据的方式已作废 |
| TOP-2 | 标签构成「三 vs 二」 | **不成立（前提已变）** | 「二」这个计数在 `origin/v2` 不存在。side 区标签是**扩展注册表驱动**：`side-region.tsx:170-171` `<For each={props.region.keys()}>` / `{(key) => (`；注册表来自 `panels.tsx:106` `const providers = createMemo(() => host.items(Panel).filter((item) => item.value.region === input.region))` 与 `:108-122` 的 `entries` 合并。`region: "side"` 的内建 provider 有 **6 个**：`review`(review/renderer.tsx:139)、`context`(context/renderer.tsx:40)、`file`(file/renderer.tsx:201)、`browser`(browser/renderer.tsx:150)、`btw`(btw/renderer.tsx:47)、`details`(details/renderer.tsx:54)。且多数标签是**动态多实例**（file 每文件一个、browser 每页面一个）。「本壳三固定 tab vs 上游二标签」的对比框架需整体重写 |
| TOP-3 | Context 缺使用率指示器 | 成立 | `context/renderer.tsx:24` `<SessionContextUsage session={state.session} variant="indicator" />` |
| TOP-4 | Context 标签不可关闭 | 成立 | `context/renderer.tsx:19` `closable: "compact",`；`panel-trigger.tsx:103` `if (closable === "compact") return compactClose()`。**原文档担心的「关闭后如何再打开」在 `origin/v2` 有明确答案**：`context/renderer.tsx:30-33` `ctx.add(Slot, {` / `at: "session.header",` / `order: 10,` / `render: (input) => <SessionContextUsage session={input.session} placement="bottom" /> }` —— 头部槽位挂了一个可点的用量指示器作为重开入口（对应旧 `zh.ts:592` `context.usage.clickToView`，该键已迁至 `context/i18n/zh.ts:7` `"usage.view": "查看上下文用量",`） |
| TOP-5 | 缺「Open file」文件浏览器 tab | 成立 | `file/renderer.tsx:202` `legacy: { "open-file": OPEN },`；`:218` `if (id === OPEN) return [launcher]`；菜单入口 `:264-268` `menu: "session.panel",` / `id: "open",` / `get title() { return ctx.t("command.open") }` |
| TOP-6 | 标签命名「变更」vs「审查」 | 成立 | `review/i18n/zh.ts:2` `"tab.title": "审查",`（对比 `mobile.title.one/other` 为 `"更改"`，`:5-6`）——即上游同一扩展内**桌面标签叫「审查」、移动标题叫「更改」**，本壳桌面用「变更」；`context/i18n/zh.ts:2` `"tab.title": "上下文",` 与本壳 `oc_review_tab_context`「上下文」同字 |
| TOP-7 | 使用率分母口径不同 | 成立 | `context/tab.tsx:132` `usage: entry?.model?.limit.context ? Math.round((total / entry.model.limit.context) * 100) : null,` |
| TOP-8 | 面板宽度/过渡动画未对齐 | 成立（依据须重述） | 结论不变，实现换了文件：`side-region.tsx:62-68` `const panelWidth = createMemo(() => {` / `if (!visible()) return "0px"` / `if (tabsVisible()) return "auto"` / `return \`${fileTreeWidth()}px\``；`:94-95` `"transition-[width] duration-[240ms] ease-[cubic-bezier(0.22,1,0.36,1)] will-change-[width] motion-reduce:transition-none":` / `!props.size.active(),`。宽度算法模块仍在原路径且未被读（`session-panel-width.ts:5` `export const SESSION_PANEL_WIDTH_MIN = 450`、`:9-12` `sessionPanelWidthMax`，由 `screen-layout.ts:69` 调用），其内部细节本轮仍未逐行核对 |
| TOP-9 | 标签拖拽排序缺失 | 成立 | `side-region.tsx:109-110` `<DragDropProvider` / `sensors={[`；`:119-122` `modifiers={[` / `RestrictToHorizontalAxis,` / `RestrictToElement.configure({ element: () => tabList ?? null }),`；落点回调 `:128-133` `onDragEnd={(event) => {` … `tabs().move(source.id.toString(), source.index)`。上游 side 标签条仍整体可拖拽排序（原依据 `session-side-panel.tsx:283-306` 已 GONE） |
| TOP-10 | `session.tab.context` 中文名与用户口径差异 | 成立 | `context/i18n/zh.ts:2` `"tab.title": "上下文",`。键名已从 `session.tab.context` 变为扩展内相对键 `tab.title`；中文未变，仍非「上文」 |

---

## 三、因基线过期而失效的判定

以下旧判定在 `origin/v2` 上**被证伪或整段作废**，不得再被引用：

| 旧判定（RIGHT_PANEL_GAP.md） | 现状 | origin/v2 证据 |
|---|---|---|
| §0/§6：「上游右侧栏 **2 个标签**：`review` + `context`」 | **证伪** | side 标签条是动态注册表：`side-region.tsx:170` `<For each={props.region.keys()}>`；`panels.tsx:106` `host.items(Panel).filter((item) => item.value.region === input.region)`。`region: "side"` 的内建 provider 实为 6 个（review / context / file / browser / btw / details），且 file 与 browser 是每实例一个动态标签 |
| §1.1/差距表#1：「`_upstream/packages/app/src/session/files/session-side-panel.tsx:61` 定义 `SessionSidePanel`」 | **文件已删** | `git cat-file -e origin/v2:packages/app/src/session/files/session-side-panel.tsx` → 不存在；`git grep SessionSidePanel origin/v2 -- packages/app/src packages/gui-extensions/src` → 零命中。内容迁至 `packages/app/src/runtime/extension/side-region.tsx:39` `export function SideRegion(props: {` |
| §1.1/差距表#1：「`zh.ts:719` → `session.panel.reviewAndFiles` = 审查和文件」 | **行号作废，键仍在** | 键仍在 `packages/app/src/runtime/i18n/zh.ts:427` `"session.panel.reviewAndFiles": "审查和文件",`，消费点 `side-region.tsx:86`。旧行号 719 无效 |
| §1.2：「`session-side-panel.tsx:23-27` 只声明 `reviewTabID`/`browserTabID`/`fileBrowserTabPanelID`」 | **整段作废** | 该常量声明随文件删除。`origin/v2` 无 panel-id 常量，标签身份改由 `PanelTab.id` 承担（`context/renderer.tsx:14` `id: "main",`、`terminal/renderer.tsx:138` `id: "main",`），宿主用 `panelKey(extension, id)` 合成（`panels.tsx:115` `key: panelKey(item.extension, tab.id),`） |
| §1.3：「`zh.ts:715-719` 有 `session.tab.review` / `session.tab.context`」 | **键已迁出 app** | `git grep 'session.tab.review\|session.tab.context' origin/v2 -- packages/app/src/runtime/i18n/zh.ts` → 零命中。现为 `review/i18n/zh.ts:2` `"tab.title": "审查",` 与 `context/i18n/zh.ts:2` `"tab.title": "上下文",` |
| §1.4/§2.2：「16 项 stats 定义在 `session-context-tab.tsx:173-193`」 | **文件已删，行号作废** | `context/tab.tsx:181-202` 同 16 项（`const stats = [` … `] satisfies { label: string; value: () => JSX.Element }[]`） |
| §1.4：「导出实现 `session-context-tab.tsx:195-218` → `fetchSessionExport`（`session/commands/export.ts`）」 | **证伪（走错模块）** | `context/tab.tsx:16` `import { fetchSessionExport, sessionExportFilename } from "./export"` → 解析到 `gui-extensions/src/context/export.ts`。`session/commands/export.ts` 虽仍存在，但其 import 方是 `home/sessions/controller.tsx:23` / `session/commands/use-session-commands.tsx:11` / `session/timeline/controller.tsx:18` |
| §1.5/差距表#17：「终端面板实现 `_upstream/packages/app/src/session/terminal/panel.tsx:45-61`，`opened = view().terminal.opened()`」 | **文件已删** | `git cat-file -e origin/v2:packages/app/src/session/terminal/panel.tsx` → 不存在。现为 `panels.tsx:387` `export function DockRegion(props: {`，`:426` `const opened = createMemo(() => view().dock.opened())`（`terminal` → `dock`） |
| §1.5/差距表#20：「60% 高度算法在 `panel.tsx:75-76`」 | **文件已删** | `panels.tsx:428-429` `const max = () => store.viewport * 0.6` / `const regionHeight = () => Math.min(height(), max())` |
| §1.5：「布局判定 `session/session-panel-layout.ts:1-5` `sessionPanelLayout(...)`」 | **文件已删，逻辑内联** | `session/screen-layout.ts:72-75` `const panelLayout = createMemo(() => ({` / `visible: tabsOpen() \|\| dockSideOpen() \|\| fileTreeOpen(),` / `stacked: tabsOpen() && dockSideOpen(),` / `}))`。三输入/两判定形状不变，但新增用户设置来源 `screen-layout.ts:21` `const dockSide = createMemo(() => session.isDesktop() && settings.general.terminalPlacement() === "side")` |
| §1.5/§6：「命令注册 `shell/commands/command.tsx:18` `EDITABLE_KEYBIND_IDS = new Set(["terminal.toggle", "terminal.new", ...])`」 | **整段作废** | `command.tsx:79` 仅剩 `export const CommandSection = Schema.Literals([... "terminal" ...])`；`EDITABLE_KEYBIND_IDS` 全仓零命中。命令由扩展注册：`terminal/renderer.tsx:42-69`（`Command,` / `id: "toggle",` / `bind: "ctrl+\`",` / `editable: true,`）与 `:72-95`（`id: "new",` / `bind: "ctrl+alt+t",`） |
| §1.5/§6/§2.4：「上游开终端靠命令面板/快捷键」 | **结论仍成立，实现层全变** | 入口证据见上条；另 `terminal/renderer.tsx:147-153` `mobile: {` / `get title() { return ctx.t("tab.title") }` / `order: 30,` / `kind: "tab",` 说明**移动端**终端是 tab（`region: "dock"` 仍生效，移动端由 `screen.tsx:306` 的 `DockRegion fill embedded` 承载） |
| §2.4 注：「终端面板自身有 review-toggle 预留位 `panel.tsx:255` `classList={{ "pe-12": props.reserveReviewToggle }}`、`:341-343` 预留 `w-12` 空洞」 | **未核对（来源文件已删）** | `session/terminal/panel.tsx` 不存在；`terminal/panel.tsx:53` 有 `const newTerminalKeybind = createMemo(() => [...keybinds.keybind("terminal.new")])`，但 `reserveReviewToggle` 语义在 `origin/v2` 是否保留，本轮**未核对** |
| §5 未核对清单第 1、6、7 项（「`session-side-panel.tsx:443` 之后渲染尾部」「`session/review/panel.tsx`、`view.tsx` 未全量阅读」「`session-panel-width.ts` 未读取」） | **前两项自动作废** | `session-side-panel.tsx` 与 `session/review/` 目录在 `origin/v2` 均不存在（面板已整体迁至 `gui-extensions/src/review/`）。第三项 `session-panel-width.ts` 仍在，本轮读到 `:5`/`:9-12` 但未逐行核对算法，正确性仍属未核对 |
| 全文所有 `_upstream/packages/app/src/session/files/**` 路径引用 | **整目录作废** | `git ls-tree -r --name-only origin/v2 -- packages/app/src/session` 无 `files/` 任何条目；相关内容分布于 `app/src/runtime/extension/{side-region,panels,panel-trigger}.tsx` 与 `gui-extensions/src/{review,context,file,browser,btw,details,terminal}/` |

---

## 四、从 `origin/v2` 重新回答：右侧栏的实际形态

### (a) 右侧栏的实际 tab 集合 —— **不是「审查 + 上下文」**

**定义标签的代码位置**：标签条本体在 `packages/app/src/runtime/extension/side-region.tsx:151-197`（`<Tabs.List>` + `<For each={props.region.keys()}>` → `<PanelTrigger>`，`:170-188`）；**tab 集合本身不在任何硬编码列表里**，而是由扩展宿主在运行时汇总：

```ts
// packages/app/src/runtime/extension/panels.tsx:106
const providers = createMemo(() => host.items(Panel).filter((item) => item.value.region === input.region))
```

`region: "side"` 的内建 provider（全部经 `gui-extensions/src/main.ts:19-32` 的 `Extension.compose` 静态编入，故都是**必然存在**的）：

| 扩展 | 注册处（`ctx.add(Panel, …)`） | `region` | 中文标签 | 标签数量语义 | 关键属性 |
|---|---|---|---|---|---|
| review | `review/renderer.tsx:137-154` | `"side"`(:139) | **审查**（`review/i18n/zh.ts:2`） | 恒 1 个，仅当 `!layout.narrow() && input.session.project`(:154) | `transient: true`(:145)、`fallback: true`(:128)、`legacy: { review: TAB }`(:146) |
| context | `context/renderer.tsx:36-44` | `"side"`(:40) | **上下文**（`context/i18n/zh.ts:2`） | 恒 1 个，仅当 `input.open.includes("main")`(:44) | `closable: "compact"`(:19)、`draggable: false`(:18)、`fallback: true`(:21)、label 内嵌指示器(:24) |
| file | `file/renderer.tsx:199-233` | `"side"`(:201) | 每个文件一个（+ `OPEN` 启动器） | **动态多实例**：每打开文件 +1(:216-232) | `legacy: { "open-file": OPEN }`(:202)、有 `close`(:234-236) 与 `focus`(:253-261) |
| browser | `browser/renderer.tsx:148-181` | `"side"`(:150) | **浏览器**（`browser/i18n/zh.ts:4`）/ 页面标题(:123) | **动态多实例**：`model()?.tabs(input.session, input.open)`(:153) | 有 `close`(:179) 与 `focus`(:180) |
| btw | `btw/renderer.tsx:45-58` | `"side"`(:47) | **/btw**（`btw/i18n/en.ts:4` `"tab.title": "/btw",`；**无 zh 翻译**，回落 en） | 恒 1 个，仅当本会话有未落盘答案 `btw.has(input.session)`(:52) | `transient: true`(:48) |
| details | `details/renderer.tsx:52-62` | `"side"`(:54) | 会话详情（`details/i18n/zh.ts:2` `title: "会话详情",`） | `list: () => []`(:62) → **side 区恒 0 个**，仅移动端 drawer(`kind: "drawer"`, :60) | 无 |

此外标签条两端各有一个固定控件：起端是文件树侧栏开关 `side-region.tsx:163-168` `<SessionReviewV2SidebarToggle opened={props.sidebar.opened()} ... />`；末端是 `+` 菜单 `side-region.tsx:189-196`（数据源 `menu === "session.panel"` 的 MenuItem，`:72-77`；`origin/v2` 中有 `file/renderer.tsx:264` 与 `browser/renderer.tsx:92` 两项）与 `session.panel.end` 插槽 `:205-215`（`file/renderer.tsx:293-298` 挂 `OpenInAppButton`）。侧栏容器本体的 `aria-label` 仍是 `session.panel.reviewAndFiles` = 「审查和文件」（`side-region.tsx:86` + `packages/app/src/runtime/i18n/zh.ts:427`）。

> **对旧文档的纠正**：「上游右侧栏 tabs = 审查 + 上下文」**不成立**。正确表述是：**side 区有 6 个内建扩展各自贡献标签，其中 review/context/btw 各 1 个、file 与 browser 为动态多实例、details 在 side 区不产标签；外加标签条两端的侧栏开关与 `+` 菜单。** 旧审计的「审查+上下文两标签 / 动态文件标签 / Open file / Browser」清单方向没错，但**漏掉了 `/btw` 与 details 两个 provider**，且把「两标签」当成了穷举。

### (b) 终端面板相对右侧栏的位置与入口 —— 「不在右侧栏标签行内」成立，但两处细节需纠正

**位置：终端是 `dock` region 面板，与 `side` region 严格分离。**

```ts
// packages/gui-extensions/src/terminal/renderer.tsx:144-146
ctx.add(Panel, {
  id: "main",
  region: "dock",
```

- side 标签条只收 `region === "side"`：`panels.tsx:106`
- dock 只收 `region === "dock"`：`panels.tsx:412` `.filter((item) => item.value.region === "dock")`
- 两者各自渲染进不同宿主：`side-region.tsx:234-246` `<RegionContent region={props.region} .../>` vs `panels.tsx:387` `export function DockRegion(props: {`

**渲染位置（两处，与旧文档描述的拓扑一致，行号全变）：**

| 位置 | origin/v2 证据 | 说明 |
|---|---|---|
| side 堆叠（与右侧栏同列、上下叠放） | `session/screen.tsx:495-502` `data-slot="session-side-terminal-region"` / `style={{ height: screen.side.dock.height() }}`，内含 `:511` `<DockRegion ... fill framed={false} contentHeight={screen.side.dock.contentHeight()} reserve={!screen.side.region.open()} />` | 该 div 位于 `SideRegion`（`screen.tsx:454`）所在同一列内（`screen.tsx:467` `class="absolute start-0 bottom-0 flex flex-col" style={{ width: screen.side.contentWidth() }}`） |
| 底部通栏 | `session/screen.tsx:533-567` `data-slot="terminal-panel-presence"`，`:544-556` `<ResizeHandle direction="vertical" ... max={... window.innerHeight * 0.6} />`，`:559-566` `<DockRegion ... stacked={isDesktop()} />` | 60% 上限同 `panels.tsx:428` `store.viewport * 0.6` |
| 移动端整屏 | `session/screen.tsx:304-314` `<Show when={!isDesktop() && store.mobileDockCached}>` / `<DockRegion ... fill embedded present contentHeight="100%" />` | 替换旧文档的 `screen.tsx:298-302` |
| 堆叠与否 | `session/screen-layout.ts:72-75` `visible: tabsOpen() \|\| dockSideOpen() \|\| fileTreeOpen(),` / `stacked: tabsOpen() && dockSideOpen(),`；受用户设置控制：`screen-layout.ts:21` `settings.general.terminalPlacement() === "side"` | 旧 `session-panel-layout.ts` 已删，此为内联版 |

**入口：命令面板 / 快捷键 / 斜杠命令，由终端扩展注册**（旧文档依据 `command.tsx:18` 作废）：

| 入口 | origin/v2 证据 |
|---|---|
| 命令 `toggle` | `terminal/renderer.tsx:45-53` `id: "toggle",` / `title: ctx.t("command.toggle"),` / `section: "terminal",` / `bind: "ctrl+\`",` / `slash: { name: "terminal", after: "open" },` / `editable: true,` / `enabled: routed(),`（中文 `terminal/i18n/zh.ts:3` `"command.toggle": "切换终端",`） |
| 命令 `new` | `terminal/renderer.tsx:75-81` `id: "new",` / `bind: "ctrl+alt+t",`（中文 `terminal/i18n/zh.ts:4` `"command.new": "新建终端",`） |
| 键盘直接匹配 | `terminal/terminal.tsx:484` `return keybinds.matches("terminal.toggle", event)` |
| 命令分区 | `app/src/shell/commands/command.tsx:79` `export const CommandSection = Schema.Literals(["general", "session", "navigation", "model", "terminal", "prompt"])` |
| 移动端 tab | `terminal/renderer.tsx:147-153` `mobile: { ... order: 30, kind: "tab" }`（标签文案 `terminal/i18n/zh.ts:12` `"tab.title": "终端",`） |
| 终端**内部**标签条（多终端页签，可拖拽排序） | `terminal/tab.tsx:13-18` `export function SortableTerminalTab(props: {` / `terminal: LocalPTY,` / `workspace: TerminalWorkspace,` / `index: number,` / `onClose?: () => void` |

> **对旧文档的两点纠正**：
> 1. 「终端在右侧栏**外**，靠命令面板入口」——**方向成立**（`region: "dock"` ≠ `region: "side"`，标签行内确无终端标签），但依据整段重写：不是「command.tsx 注册」，而是「terminal 扩展自己 `ctx.add(Command, …)`」。
> 2. 「终端与右侧栏无关」是**过强的表述**。终端仍可作为 `session-side-terminal-region` 与右侧栏**同列堆叠**（`screen.tsx:496`），且堆叠与否现在还受 `settings.general.terminalPlacement()` 用户设置控制（`screen-layout.ts:21`）——这是 `origin/v2` 新增的、原文档完全没有覆盖的行为。旧文档差距表 #18 的表述「终端面板**在右侧栏内**：上游无」应精确为「**右侧栏标签行内**的上游侧无终端标签」。

---

## 五、本轮未核对 / 不在范围内的项

| 项 | 原因 |
|---|---|
| 本壳侧（`app/src/main/**`）全部行号与结论 | 本轮任务范围为「以 `origin/v2` 为准复核上游侧判定」。上表所有「新判定」只覆盖**上游半边**；本壳半边一律沿用 `RIGHT_PANEL_GAP.md` 原文，未重新实读，也未验证本壳工作树是否已跟进 v2 |
| 上游终端面板的 review-toggle 预留位（`reserveReviewToggle` / `pe-12` / `w-12`） | 来源文件 `session/terminal/panel.tsx` 已删；`terminal/panel.tsx` 中对应语义本轮未逐行核对 |
| `session-panel-width.ts` 宽度算法内部细节 | 只读到 `:5` `SESSION_PANEL_WIDTH_MIN = 450` 与 `:9-12` `sessionPanelWidthMax`，未逐行核对（原文档 §5 亦列为未核对） |
| `panels.tsx` 中段（约 :130-380）的持久化/重映射细节 | 只读了 `:1-130` 与 `:380-470`；`panels.tsx` 约 470 行，中段未读 |
| `screen.tsx` :315-450、:525-533、:570 之后的渲染 | 只读了 :298-315 与 :450-575 的相关片段 |
| `gui-extensions/src/{ssh,wsl,updater,pairing,debug}` 是否有 side/dock 面板 | 只 grep 了 `region:` 的全部命中（仅 browser/btw/context/details/file/review = side，terminal = dock），已足以支撑 (a)(b)；未逐个打开这五个扩展确认 |
| `context/export.ts` 的 payload 结构 | 只确认被 import 与调用（`context/tab.tsx:16,210-215`），未读实现（沿用原文档 §5 的未核对口径） |
| `_upstream` 工作树当前状态 | 按要求未做任何 `checkout/pull/switch/reset`；全部取证走 `origin/v2:<path>` 直读 git 对象 |
