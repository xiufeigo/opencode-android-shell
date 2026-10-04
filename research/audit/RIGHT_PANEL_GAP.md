# 会话右侧栏 · 逐项对齐审计（v161）

- 审计日期：2026-10-04
- 范围：`_upstream/packages/app/**`（上游网页端）vs `app/src/main/**`（本壳 Android）
- 性质：**只审计，不改代码**。所有判定均带双侧行号；无行号者一律记「未核对」。

---

## 0. 一句话结论（先给答案）

> **右侧栏不是「自造的」，而是「结构照上游、但多了一个上游右侧栏里不存在的独立标签（文件），且把上游「审查/上下文」两标签改写成了「变更/上下文/文件」三标签」**——元数据表、原始消息、导出会话这三块**本壳已经基本对齐**；真正的自造物是**终端入口芯片 `TerminalEntryButton`（本壳自造，上游右侧栏无此物）**。

用户说「内容与逻辑和我们的完全不一致」——**这个判断不成立**（依据见 §4 表格：本壳有 16 项元数据表、原始消息手风琴、导出会话按钮，标签与上游同源）。真正不一致的是**标签构成**与**多出来的终端芯片**。

---

## 1. 上游侧：会话右侧栏到底有什么

### 1.1 右侧栏容器

`_upstream/packages/app/src/session/files/session-side-panel.tsx:61` 定义 `SessionSidePanel`，`:258-273` 渲染 `<aside id="review-panel">`：

```tsx
// session-side-panel.tsx:258-262
<aside
  id="review-panel"
  aria-label={language.t("session.panel.reviewAndFiles")}
  aria-hidden={!open()}
  inert={!open()}
```

`zh.ts:719` → `"session.panel.reviewAndFiles": "审查和文件"`。

**上游自己把这个面板命名为「审查和文件」**，而不是「审查+上下文+文件」三标签同权。

### 1.2 上游右侧栏的标签构成

| 标签 | 渲染处 | i18n key | 中文（`zh.ts`） |
|---|---|---|---|
| **Review** | `session-side-panel.tsx:333-343` `<Show when={reviewTab() && props.canReview}>` → `Tabs.Trigger value="review"` | `session.tab.review` | **审查**（`zh.ts:716`） |
| **Context** | `session-side-panel.tsx:344-374` `<Show when={contextOpen()}>` → `Tabs.Trigger value="context"`，内含 `SessionContextUsage` 指示器 | `session.tab.context` | **上下文**（`zh.ts:717`） |
| **File 标签**（动态、每文件一个） | `session-side-panel.tsx:375-386` `<For each={panelTabs()}>` → `SortableTab` | — | 文件名 |
| **Open file**（文件浏览器） | `session-side-panel.tsx:410-442` `Tabs.Trigger value={SESSION_OPEN_FILE_TAB}` | — | — |
| **Browser** 标签 | `session-side-panel.tsx:388-409` `isSessionBrowserTab(tab)` | `session.tab.browser` | — |

Context 触发标签原文（`session-side-panel.tsx:344-373`）：

```tsx
<Show when={contextOpen()}>
  <Tabs.Trigger
    value="context"
    onMiddleClick={() => tabs().close("context")}
    ...
  >
    <div class="flex items-center gap-2">
      <SessionContextUsage variant="indicator" />
      <div>{language.t("session.tab.context")}</div>
    </div>
  </Tabs.Trigger>
</Show>
```

**触发方式**：`contextOpen()` 来自 `createSessionTabs`（`session-side-panel.tsx:170-179`），即**由布局状态驱动、点击切换**；非自动弹出。Review 标签由 `view().reviewPanel.opened()`（`:90`）驱动。

### 1.3 ⚠️ 关于用户说的「上文」

**上游中文是「上下文」，不是「上文」**：

```ts
// _upstream/packages/app/src/runtime/i18n/zh.ts:715-719
"session.tab.session": "会话",
"session.tab.review": "审查",
"session.tab.context": "上下文",
"session.tab.unknown": "未知会话",
"session.panel.reviewAndFiles": "审查和文件",
```

用户转述为「上文」，与上游 zh 源**不一致**——疑为口语转写误差，不是本壳 bug。

### 1.4 Context 标签渲染了什么（16 项元数据表）

`_upstream/packages/app/src/session/files/session-context-tab.tsx:85` `SessionContextTab`。

**16 项 stats 定义（`:173-193`）**，与用户转述的 16 字段**逐项吻合**：

```tsx
// session-context-tab.tsx:173-193
const stats = [
  { label: "context.stats.session", value: () => info()?.title ?? params.id ?? "—" },
  { label: "context.stats.messages", value: () => counts().all.toLocaleString(language.intl()) },
  { label: "context.stats.provider", value: providerLabel },
  { label: "context.stats.model", value: modelLabel },
  { label: "context.stats.limit", value: () => formatter().number(ctx()?.limit) },
  { label: "context.stats.totalTokens", value: () => formatter().number(ctx()?.total) },
  { label: "context.stats.usage", value: () => formatter().percent(ctx()?.usage) },
  { label: "context.stats.inputTokens", value: () => formatter().number(ctx()?.input) },
  { label: "context.stats.outputTokens", value: () => formatter().number(ctx()?.tokens.output) },
  { label: "context.stats.reasoningTokens", value: () => formatter().number(ctx()?.tokens.reasoning) },
  { label: "context.stats.cacheTokens", value: () => `${...cache.read)} / ${...cache.write)}` },
  { label: "context.stats.userMessages", value: () => counts().user.toLocaleString(...) },
  { label: "context.stats.assistantMessages", value: () => counts().assistant.toLocaleString(...) },
  { label: "context.stats.totalCost", value: cost },
  { label: "context.stats.sessionCreated", value: () => formatter().time(info()?.time.created) },
  { label: "context.stats.lastActivity", value: () => formatter().time(ctx()?.message.time.created) },
]
```

**字段数据来源（关键）**：

- 会话标题/成本/创建时间 ← `useData().session.get(params.id)`（`:94`）
- 消息列表 ← `data.session.message.list(id)`（`:96-104`）
- provider/model/limit ← `providers.all().get(model.providerID)` + `model.limit.context`（`:117-118`, `:130`）
- 词元 ← 最后一条带 `tokens` 的 assistant 消息（`:115-135`）
- 总成本 ← `info()?.cost`（`:139`）

**使用率算法**（`:133`）：`Math.round((total / model.limit.context) * 100)`。
**总数算法**（`:119-124`）：`input + output + reasoning + cache.read + cache.write`。

**系统提示词块**（`:283-292`）：取最后一条 `system` 消息。
**原始消息列表**（`:294-312`）：`<Accordion multiple>` 逐条 `RawMessage`，标题 `{type} • {id}` + 时间戳（`:65`, `:68`）。
**导出会话按钮**（`:296-306`）：

```tsx
<div class="text-12-regular text-text-weak">{language.t("context.rawMessages.title")}</div>
<Button size="small" variant="ghost" onClick={exportSession}>
  <Icon name="download" size="small" />
  <span>{language.t("context.export.session")}</span>
</Button>
```

`zh.ts:570-571` → 原始消息 / **导出会话**。
导出实现 `session-context-tab.tsx:195-218` → `fetchSessionExport`（`session/commands/export.ts`）。

### 1.5 上游到底有没有「终端面板」

**有终端面板，但它不在右侧栏里。**

- 终端面板实现：`_upstream/packages/app/src/session/terminal/panel.tsx:45-61`，`opened = view().terminal.opened()`（`:58`）
- **位置一（侧边堆叠）**：`screen.tsx:473-500`，`data-slot="session-side-terminal-region"`，与 Review 面板**同栏堆叠**（vertical ResizeHandle，`screen.tsx:457-460`）
- **位置二（底部通栏）**：`screen.tsx:507-539`，`<TerminalPanel stacked={isDesktop()} .../>`（`:534-538`）
- 布局判定：`session/session-panel-layout.ts:1-5`
  ```ts
  export function sessionPanelLayout(input: { review: boolean; terminal: boolean; files: boolean }) {
    return { visible: input.review || input.terminal || input.files, stacked: input.review && input.terminal }
  }
  ```
- 移动端：整屏 terminal view（`screen.tsx:298-302`）
- 命令注册：`shell/commands/command.tsx:18` `EDITABLE_KEYBIND_IDS = new Set(["terminal.toggle", "terminal.new", ...])` → 终端靠**命令面板 / 快捷键**开启

**结论：上游无「右侧栏内的终端标签」**。终端是**独立面板**（侧边堆叠或底部通栏），入口在命令/快捷键，不在标签行。

---

## 2. 本壳侧：右侧栏现在渲染什么

### 2.1 右侧栏容器与标签

`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewPanel.kt:53`：

```kotlin
/** Review side panel tabs, upstream `SessionReviewV2` sidebar modes. */
enum class ReviewPanelTab { CHANGES, CONTEXT, FILES }
```

标签行 `ReviewPanel.kt:339-357`（三个等宽 `ReviewTabButton`）：

```kotlin
// ReviewPanel.kt:339-357
Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
    ReviewTabButton(label = stringResource(R.string.oc_review_tab_changes), selected = tab == ReviewPanelTab.CHANGES, onClick = { onTabChange(ReviewPanelTab.CHANGES) }, ...)
    ReviewTabButton(label = stringResource(R.string.oc_review_tab_context), selected = tab == ReviewPanelTab.CONTEXT, onClick = { onTabChange(ReviewPanelTab.CONTEXT) }, ...)
    ReviewTabButton(label = stringResource(R.string.oc_review_tab_files),   selected = tab == ReviewPanelTab.FILES,   onClick = { onTabChange(ReviewPanelTab.FILES) }, ...)
}
```

中文标签（`res/values-zh-rCN/strings.xml:441-444`）：

```xml
<string name="oc_review_title">评审</string>
<string name="oc_review_tab_changes">变更</string>
<string name="oc_review_tab_context">上下文</string>
<string name="oc_review_tab_files">文件</string>
```

内容分发 `ReviewPanel.kt:363-390`：FILES → `ReviewFilesTab`；CONTEXT → `SessionContextPanel`；else → `ReviewChangesTab`。

**上游是两标签（审查/上下文）+ 动态文件标签；本壳是固定三标签（变更/上下文/文件）。**

### 2.2 本壳的 Context 标签内容

`ReviewPanel.kt:381-384`：

```kotlin
ReviewPanelTab.CONTEXT -> SessionContextPanel(
    snapshot = contextSnapshot,
    modifier = Modifier.fillMaxSize(),
)
```

`ui/session/context/SessionContextPanel.kt:89-94` 是本壳版 `SessionContextTab`；其 KDoc `:64-68` 自述对齐上游：

```
 * Session **context** tab (B2) — the body of upstream's `SessionContextTab`
 * (`_upstream/packages/app/src/session/files/session-context-tab.tsx`), mounted
 * by the right panel's third tab (`session-side-panel.tsx:345-373`) and, on
 * mobile, as the whole `usage` view (`session/screen.tsx:307-309`).
```

**16 项枚举**（`SessionContextModel.kt:67-93`）逐项对应上游 `session-context-tab.tsx:173-193`：
`SESSION, MESSAGES, PROVIDER, MODEL, LIMIT, TOTAL_TOKENS, USAGE, INPUT_TOKENS, OUTPUT_TOKENS, REASONING_TOKENS, CACHE_TOKENS, USER_MESSAGES, ASSISTANT_MESSAGES, TOTAL_COST, SESSION_CREATED, LAST_ACTIVITY`

中文标签（`res/values-zh-rCN/strings.xml:484-499`）与上游 `zh.ts:573-588` **逐字一致**（唯一增强见 §4 注）。

渲染：16 行网格 `SessionContextPanel.kt:104-119`（`SessionContextModel.columns` 决定 1/2 列，对应上游 `:277` 的 `grid-cols-1 @[32rem]:grid-cols-2`）；系统提示词 `:122-145`；**原始消息 + 导出会话** `:160-179`：

```kotlin
// SessionContextPanel.kt:176-178
SectionLabel(stringResource(R.string.oc_context_raw_messages_title))
Spacer(Modifier.weight(1f))
ExportSessionButton(export)
```

中文 `strings.xml:500-502` → 系统提示词 / 原始消息 / **导出会话**。

### 2.3 `Open terminal` 芯片：谁、在哪一行、点了会怎样

**定义**：`ui/terminal/TerminalPanel.kt:2030-2057`

```kotlin
// TerminalPanel.kt:2023-2039
/**
 * Entry affordance for the session route (`App.kt`): 28 dp ghost square.
 *
 * [active] highlights it while the docked pane is open, so the chip reads as a
 * toggle rather than a "go somewhere" button (upstream's side panel toggle wears
 * the same accent treatment).
 */
@Composable
fun TerminalEntryButton(onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false) {
    val openLabel = stringResource(R.string.oc_terminal_open)   // :2039
```

标签文案：`res/values/strings.xml:466` `<string name="oc_terminal_open">Open terminal</string>`

**挂载点**：`ui/session/SessionScreen.kt:2254-2259`

```kotlin
// SessionScreen.kt:2254-2259
if (desktop && onToggleTerminalDock != null) {
    TerminalEntryButton(
        onClick = onToggleTerminalDock,
        active = terminalDocked,
        modifier = Modifier.align(Alignment.TopEnd)
```

注释 `SessionScreen.kt:2251-2253` 说明：v43 起该芯片**归时间线面板所有**，以免压住右侧栏标签行。

**点了会怎样**：`onToggleTerminalDock` → 打开/关闭**停靠式终端**。停靠槽位二选一：
- `SessionScreen.kt:2521-2523` `TerminalDock(placement = TerminalPlacement.SIDE, ...)` —— 与右侧栏同一行（右侧堆叠）
- `SessionScreen.kt:2545-2547` `TerminalDock(placement = TerminalPlacement.BOTTOM, ...)` —— 底部通栏

注释 `SessionScreen.kt:2541-2543` 承认对齐上游：`at 60% of the viewport exactly like panel.tsx:75-76 (Math.min(height, view * 0.6))`。

### 2.4 「是否自造」的判断依据

**判定：停靠行为是照上游的；但「右侧栏/时间线角上的这颗 28dp 芯片」这一具体入口形态，上游无对应物 → 本壳自造。**

依据：

1. **停靠位置对齐**：`SessionScreen.kt:2541-2543` 明确引用上游 `panel.tsx:75-76` 的 60% 高度算法；SIDE/BOTTOM 两槽位对应上游 `screen.tsx:473-500`（side）与 `screen.tsx:507-539`（bottom）。
2. **但上游右侧栏标签行里没有终端标签**：`session-side-panel.tsx:23-27` 只声明了 `reviewTabID`/`browserTabID`/`fileBrowserTabPanelID`，`:333-374` 只有 review + context 两个 `<Tabs.Trigger>`，`:375+` 才是文件/浏览器标签。**无 terminal tab**。
3. **上游开终端靠命令面板/快捷键**：`shell/commands/command.tsx:18` 注册 `terminal.toggle` / `terminal.new`。
4. **本壳芯片的 KDoc 自述是本壳设计决策**：`TerminalPanel.kt:2023-2028`「28 dp ghost square」「upstream's side panel toggle wears the same accent treatment」——只说**借用了配色**，没说上游有此按钮。
5. 命中坐标 `[1320,192][1366,240]`：宽 46px / 高 48px，与 `TerminalPanel.kt:2042` 的 `.size(28.dp)` 芯片（1.5~1.75x 密度下约 42~49px）吻合，且位置在面板右上角，符合 `SessionScreen.kt:2259` `.align(Alignment.TopEnd)`。

**注**：上游终端面板自身有一个 review-toggle 预留位（`panel.tsx:255` `classList={{ "pe-12": props.reserveReviewToggle }}`、`:341-343` 预留 `w-12` 空洞），但那是**终端面板内给 Review 侧栏切换按钮留位**，方向与本壳「用终端芯片开终端」相反。

---

## 3. 差距表

| # | 能力 | 上游（file:line） | 本壳（file:line） | 判定 | 用户可见影响 |
|---|---|---|---|---|---|
| 1 | 右侧栏容器 | `session-side-panel.tsx:258-273` `<aside id="review-panel">` | `ReviewPanel.kt:149`（`ReviewPanel` composable），停靠于会话屏末端 | **一致** | 无感 |
| 2 | Review/变更标签 | `session-side-panel.tsx:333-343`（`value="review"`，可关闭性由 `canReview` 决定） | `ReviewPanel.kt:340-345` + `strings.xml:442`「变更」 | **不一致（命名）** | 上游「审查」vs 本壳「变更」，文案差异 |
| 3 | Context/上下文标签 | `session-side-panel.tsx:344-374`（`value="context"`，中键关闭 + closeButton） | `ReviewPanel.kt:346-351` + `strings.xml:443`「上下文」 | **一致** | 无感 |
| 4 | Context 标签的使用率指示器 | `session-side-panel.tsx:370` `<SessionContextUsage variant="indicator" />` | `ReviewPanel.kt:346-351`（**无指示器，仅文字 tab**） | **缺失** | 本壳 tab 上没有环形/百分比用量指示 |
| 5 | Context 标签可关闭（×/中键） | `session-side-panel.tsx:348-367`（`Tabs.CloseButton` + `tabs().close("context")`） | `ReviewPanel.kt:339-357`（三个 tab 均无关闭） | **缺失** | 本壳三标签不可关闭，与上游可关闭的 context tab 行为不同 |
| 6 | 文件标签 | `session-side-panel.tsx:375-386`（动态、每文件一个、可拖拽排序） | `ReviewPanel.kt:352-357`（单个固定「文件」tab） | **不一致** | 本壳无多文件并行 tab、无拖拽排序 |
| 7 | Open file（文件浏览器）tab | `session-side-panel.tsx:410-442` | 无 | **缺失** | 缺上游的「打开文件」文件浏览器入口 |
| 8 | Browser 标签 | `session-side-panel.tsx:388-409` | `SessionBrowserPane.kt`（独立 pane，非 tab） | **不一致** | 浏览器以独立面板呈现，不占右侧栏标签位 |
| 9 | 上文元数据表（16 项） | `session-context-tab.tsx:173-193` | `SessionContextModel.kt:67-93`（枚举）+ `SessionContextPanel.kt:104-119`（渲染） | **一致** | 无感 |
| 10 | 16 项中文字段名 | `zh.ts:573-588` | `strings.xml:484-499` | **一致**（「消息数（全部帧）」为本壳口径增强） | 本壳标签更长，语义更准 |
| 11 | 使用率算法 | `session-context-tab.tsx:133` `round(total/limit.context*100)` | `SessionContextModel.kt:80-81`（`min(limit.input, limit.context)`，见 `project`） | **不一致** | 分母口径不同：上游用 `limit.context`，本壳取 `input`/`context` 较小值 |
| 12 | 词元总数算法 | `session-context-tab.tsx:119-124`（input+output+reasoning+cache.read+cache.write） | `SessionContextModel.kt:82` `TOTAL_TOKENS` | **一致** | 无感 |
| 13 | 系统提示词块 | `session-context-tab.tsx:283-292` | `SessionContextPanel.kt:122-145` | **一致**（本壳另有 API 指令块 `:147-158`，上游无） | 本壳多一块 API 管理指令 |
| 14 | 原始消息列表 | `session-context-tab.tsx:294-312` `<Accordion multiple>` + `RawMessage:54-81` | `SessionContextPanel.kt:160-179` + `SessionContextModel.RawMessage:98-113`（`title = "$type • $id"`） | **一致** | 无感 |
| 15 | 导出会话按钮 | `session-context-tab.tsx:296-306` → `exportSession:195-218` | `SessionContextPanel.kt:178` `ExportSessionButton(export)` + `strings.xml:502` | **一致** | 无感 |
| 16 | 移动端 Context 视图 | `screen.tsx:307-309`（整屏 `usage` view） | `SessionScreen.kt:2614-2618` `MobileUsagePanel` → `MobileUsagePanel.kt:120` `SessionContextPanel` | **一致** | 无感 |
| 17 | 终端面板**存在性** | `screen.tsx:473-500`（side）+ `screen.tsx:507-539`（bottom） | `SessionScreen.kt:2521-2523`（SIDE）+ `2545-2547`（BOTTOM） | **一致** | 无感 |
| 18 | 终端面板**在右侧栏内** | **无**（`session-side-panel.tsx:23-27, 333-374` 无 terminal tab） | `TerminalPanel.kt:2030-2057` `TerminalEntryButton` + `SessionScreen.kt:2255` | **本壳自造（上游无）** | 多出一颗 28dp 终端芯片，即用户所说「丑丑的终端标」 |
| 19 | 终端开启方式 | `command.tsx:18`（命令面板 `terminal.toggle`/`terminal.new`） | 芯片点击 + `SessionScreen.kt:1397-1402`（`terminal.new` 命令） | **不一致** | 本壳多一条角标入口，命令路径本身对齐 |
| 20 | 终端面板高度 60% | `panel.tsx:75-76`（`Math.min(height, view*0.6)`，见本壳 `SessionScreen.kt:2541-2543` 引用） | `SessionScreen.kt:2541-2551` + `TerminalPaneSize` | **一致** | 无感 |

**统计：共 20 项。一致 9 / 不一致 7 / 缺失 3 / 本壳自造 1。**

---

## 4. 按用户可见影响排序的 TOP 10 缺口

### TOP-1 ▸ 终端芯片混进右侧栏视觉带（自造，最高优先）
- **缺口**：上游右侧栏标签行**没有**终端入口；本壳在 `SessionScreen.kt:2255` 挂了 `TerminalEntryButton`（定义 `TerminalPanel.kt:2030-2057`），`align(TopEnd)` 落在标签行同一条视觉带上。
- **补在哪几行**：`SessionScreen.kt:2254-2259`（移除/移位）、`TerminalPanel.kt:2030-2057`（保留组件但从右侧栏带移出）。
- **需要什么数据/接口**：无新接口，纯布局调整；终端能力本身已由 `TerminalDock` 承担。
- **触及其它约束**：⚠️ 移动端入口靠 `MobileForm.Tab.TERMINAL`（`SessionScreen.kt:1402`、`:1755`），**不能整体删芯片**，只能改宽屏位置；`SessionScreen.kt:2251-2253` 注释说明 v43 已因遮挡「文件」tab 而搬过一次位置，再动需复验宽屏截图。

### TOP-2 ▸ 标签构成三 vs 二（文件 tab 语义错位）
- **缺口**：上游右侧栏 = 审查 + 上下文 + 动态文件标签；本壳 = 变更/上下文/文件 三个固定等权 tab。
- **补在哪几行**：`ReviewPanel.kt:53`（枚举）、`ReviewPanel.kt:339-357`（标签行）、`ReviewPanel.kt:363-390`（分发）。
- **需要什么数据/接口**：若要还原动态文件 tab，需上游 `panelTabs()` 的多文件状态（`session-side-panel.tsx:170-184`）+ dnd-kit 拖拽排序（`:283-306`）。
- **触及其它约束**：⚠️ `ReviewPanel.kt:151-155` 说明 tab 是 **hoisted** 状态，供 `ui/palette/AppCommands.kt` 的 `file.open` 从外部切换到 Files——改造必须保留该 hoist 契约。

### TOP-3 ▸ Context 标签缺使用率指示器
- **缺口**：上游 tab 内嵌 `<SessionContextUsage variant="indicator" />`（`session-side-panel.tsx:370`）；本壳只有纯文字。
- **补在哪几行**：`ReviewPanel.kt:346-351`（CONTEXT 按钮内加指示器）。
- **需要什么数据/接口**：本壳已有 `ChatViewModel.contextLimit` / `contextTokens`（`MobileUsagePanel.kt:39-41` 证实）+ `header/ContextRing.kt`、`ContextUsage.kt`、`ContextUsagePopover.kt` 现成组件，可直接复用。
- **触及其它约束**：低。需保证 `contextLimit` 为 null 时不渲染（与 `MobileUsagePanel.kt:47` 的全 `—` 行为一致）。

### TOP-4 ▸ Context 标签不可关闭
- **缺口**：上游 context tab 有 `Tabs.CloseButton` + 中键关闭（`session-side-panel.tsx:348-367`）；本壳三标签均不可关。
- **补在哪几行**：`ReviewPanel.kt:339-357`。
- **需要什么数据/接口**：需要「context tab 曾被打开过」的持久化状态（上游由 `createSessionTabs` 的 `contextOpen()` 表达，`session-side-panel.tsx:179`）。
- **触及其它约束**：⚠️ 若允许关闭，**必须同时定义关闭后如何再打开**——上游靠 `SessionContextUsage` 点击（`zh.ts:592` `context.usage.clickToView`「点击查看上下文」）与 `fileBrowserVisible` 的 active 值回退（`session-side-panel.tsx:228-231`）。本壳需补等价入口，否则用户会永久失去该 tab。

### TOP-5 ▸ 缺「Open file」文件浏览器 tab
- **缺口**：上游 `session-side-panel.tsx:410-442` 有独立文件浏览器 tab（带关闭按钮、焦点管理）。
- **补在哪几行**：`ReviewPanel.kt:52-53`（枚举扩展）、`339-357`、`363-390`。
- **需要什么数据/接口**：目录树懒加载（`ReviewFilesTab` 已有 `onLoadChildren`，`ReviewPanel.kt:364-370`）+ 文本预览。
- **触及其它约束**：中。会牵动 `file.open` 命令的 tab 目标语义（见 TOP-2）。

### TOP-6 ▸ 标签命名与上游不一致（「变更」vs「审查」）
- **缺口**：`strings.xml:442` 「变更」/ `:441` 「评审」 vs 上游 `zh.ts:716` 「审查」+ `session.tab.review`；且 `oc_review_title`「评审」又与「审查」第三种说法并存。
- **补在哪几行**：`res/values-zh-rCN/strings.xml:441-444`（及各语言变体）。
- **需要什么数据/接口**：无，纯文案。
- **触及其它约束**：⚠️ `strings.xml:567-568` 有注释声明这些 key 属于**对外冻结的文案契约**（列出 `oc_review_tab_changes` / `oc_review_tab_files`），改动需先确认契约归属方。

### TOP-7 ▸ 使用率分母口径不同
- **缺口**：上游 `limit.context`（`session-context-tab.tsx:133`）；本壳 `min(limit.input, limit.context)`（`SessionContextModel.kt:80-81`）。
- **补在哪几行**：`SessionContextModel.kt` 的 `project`/LIMIT 计算处。
- **需要什么数据/接口**：provider model 的 `limit.context` / `limit.input`（两者都已在用）。
- **触及其它约束**：⚠️ 会连带改变 `header/ContextRing.kt` 环形指示的分母与 `strings.xml:490`「使用率」显示值；本壳口径**可能是有意为之**（更保守），需产品确认而非直接改。

### TOP-8 ▸ 面板宽度/过渡动画未对齐
- **缺口**：上游 `session-side-panel.tsx:104-108` 面板宽度 `auto`/px 自适应 + `:268-269` 240ms 宽度过渡、`session-panel-width.ts` 独立模块。
- **补在哪几行**：`ReviewPanel.kt:181`（`widthDp` 记忆）、`:574` `LEGACY_PANEL_WIDTH = 340.dp`。
- **需要什么数据/接口**：无。
- **触及其它约束**：中。宽度动画在 Compose 侧需 `animateDpAsState`，且要与 `SessionScreen.kt:2258` 芯片位置联动（芯片偏移依赖面板宽度）。

### TOP-9 ▸ 标签拖拽排序缺失
- **缺口**：上游文件标签可拖拽排序（`session-side-panel.tsx:283-306` DragDropProvider + `tabs().move`）。
- **补在哪几行**：需新建组件（`FileTabStrip.kt` 已是本壳 tab 条候选宿主）。
- **需要什么数据/接口**：多文件 tab 状态（依赖 TOP-2）。
- **触及其它约束**：高（依赖 TOP-2/TOP-5 先落地），暂不可单做。

### TOP-10 ▸ `session.tab.context` 中文名与用户口径差异
- **缺口**：用户转述「上文」，上游 `zh.ts:717` 为「上下文」，本壳 `strings.xml:443` 也是「上下文」。
- **补在哪几行**：`res/values-zh-rCN/strings.xml:443`。
- **需要什么数据/接口**：无。
- **触及其它约束**：⚠️ **不建议改**——两侧同为「上下文」，说明本壳已对齐上游；若改「上文」反而制造新的不一致。需向用户澄清其转述误差。

---

## 5. 未核对清单（不下结论）

| 项 | 原因 |
|---|---|
| 上游 `session-side-panel.tsx:443` 之后的渲染尾部 | 读取被截断（`original_bytes=42060`），未逐行核对尾部 file-browser / browser 分支细节 |
| 上游 mobile 端 `screen.tsx:307-309` 的完整 `usage` view 实现 | 仅由本壳 `SessionContextPanel.kt:68` 的 KDoc 转述佐证，未直读上游该段原文 |
| 上游 `session/commands/export.ts` 的导出格式细节 | 仅确认被调用（`session-context-tab.tsx:199-204`），未核对 payload 结构 |
| 本壳 `SessionContextPanel.kt:180+`（约 526 行）导出手势与原始行展开交互 | 只读了 `:60-179`，展开/滚动部分未逐行核对 |
| 本壳 `SessionExport*.kt` 5 个文件的具体落盘实现 | 仅确认 `ExportSessionButton` 接线（`:178`），未核对保存/导入链路 |
| 上游是否有第 4 种「审查」子面板（如 lint/注释） | `session/review/panel.tsx`、`view.tsx` 未全量阅读 |
| 上游 `session-panel-width.ts` 宽度算法细节 | 未读取 |
| 本壳宽屏真机截图与本报告的对应关系 | 本轮禁设备操作；`[1320,192][1366,240]` 尺寸为**推断**（据 `TerminalPanel.kt:2042` 的 28dp），非实测复核 |

---

## 6. 关键结论速览

- **上游右侧栏 2 个标签**：`review`（`session-side-panel.tsx:333-343`，「审查」）、`context`（`session-side-panel.tsx:344-374`，「上下文」）；另有动态文件 tab（`:375-386`）、Open file（`:410-442`）、Browser（`:388-409`）。
- **上游无「右侧栏内的终端标签」**；终端是独立面板（`screen.tsx:473-500` 侧 / `screen.tsx:507-539` 底），入口在命令面板（`command.tsx:18`）。
- **本壳 `Open terminal` = `TerminalPanel.kt:2039` 的 `openLabel`，组件 `TerminalPanel.kt:2030-2057`，挂载 `SessionScreen.kt:2255`**；**芯片形态为本壳自造**（停靠行为本身照上游 `panel.tsx:75-76`）。
- **用户「内容与逻辑完全不一致」的判断不成立**：16 项元数据表 / 原始消息 / 导出会话三项本壳均已对齐（差距表 #9、#14、#15 判「一致」）。
