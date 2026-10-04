# 一致性差距审计 · v2 基线订正版（PARITY GAP — V2 BASELINE）

> **只审计、不改代码。** 交付物：本文件。写权限限定 `research/audit/**`；未执行 gradle / 装机 / 起服务 / 设备操作。
> **基线**：`origin/v2` = `ecfa884`（2026-10-04，release **v2.0.22**）
> **订正对象**：`GLOBAL_PARITY_GAP.md`、`RIGHT_PANEL_GAP.md`（均基于 `702a73d` 旧快照）

---

## 0. 方法学教训（为什么上一轮结论不可靠）

### 0.1 实锤误判

上一轮审计把 `/btw` 判为「疑似自造」。在 `origin/v2` 上 `/btw` **真实存在**：

| 文件 | 说明 |
| --- | --- |
| `packages/gui-extensions/src/btw/panel.tsx` | 面板 UI |
| `packages/gui-extensions/src/btw/renderer.tsx` | 扩展装配（`Command` + `Panel`） |
| `packages/gui-extensions/src/btw/model.ts` | 状态模型 |
| `packages/gui-extensions/src/btw/index.ts` | 扩展定义 `id: "btw"` |

### 0.2 根因：旧快照**根本没有 `gui-extensions` 这个包**

`702a73d` 的 `packages/` 下没有 `gui-extensions`。而 `origin/v2` 有，且它**承载了会话界面的一大半功能**（`review` / `context` / `btw` / `terminal` / `file` / `details` / `browser` / `ssh` / `wsl` / `updater` / `pairing` / `debug`）。

这意味着：**上一轮凡是得出「上游没有 X」「我们自造了 X」的 UI 判定，都可能只是因为审计用的快照里装不下这些包**。这类判定按构造就是不可靠的，不是逐条修正的问题。

### 0.3 第二个根因：文件搬家 ≠ 功能消失

`f406d93 refactor(app): move GUI features into built-in extensions (#52369)` 把原本在 `packages/app/` 的 GUI 功能搬进了内置扩展。所以：
- 旧文档引用的 `session-side-panel.tsx` 在 `origin/v2` **不存在**，但这**不能**推出「侧栏没了」——它在 `packages/app/src/runtime/extension/side-region.tsx` 与 `packages/gui-extensions/src/{review,context,btw}/`。

### 0.4 本轮采用的做法

1. **不切工作区**：`_upstream` 仍停在 `702a73d`，全程只用 `git show` / `git grep … origin/v2` / `ls-tree` 读 git 对象。
2. **pathspec 永远限定 `packages/*/src`**，避免 `_upstream/node_modules` 拖死全树 grep。
3. **判定必须带 `origin/v2` 的 `file:line` + 原文**；拿不到就写「未核对」，**禁止沿用旧判定**。
4. 先查「功能是否搬家」再谈「功能是否缺失」。

---

## 1. 基线差异归类（`702a73d..origin/v2`）

- 区间总提交数 **526**；触及 `packages/app` + `packages/cli` + `packages/gui-extensions` + `packages/session-ui` 的共 **140** 个。
- 按 scope：`app` 44、`acp` 30、`cli` 19、`desktop` 8、`session-ui` 6、`gui-extensions` 4、`core` 3、`tui` 3、`client` 2，其余 5。
- **版本阶梯**：区间内 10 次 `sync release versions`，从 v2.0.13 走到 **v2.0.22**。**我们的后端是 2.0.14 → 落后 8 个版本。**

按主题归类（只列影响会话界面的）：

| 主题 | 代表提交 |
| --- | --- |
| **会话界面 / timeline** | `#51090` reasoning-only 轮次保持 Working、`#52639` 标题淡出修复、`#52207` 相邻 read 合并成一行、`#52210` Used 分组头吸顶、`#52009` moved 提示改为时间线分隔线、`#53112` 消息 meta 显示 model variant |
| **侧栏 / panel** | `#53081` 会话切换时保留侧栏 tab、`#51640` review 面板显示上一轮改动、`#52632` 布局切换时保持 browser surface 对齐 |
| **composer** | `#51124` 队列中的 prompt 可撤销回输入框、`#51047` 触屏显示附件移除按钮 |
| **设置** | `#49373` 精简实验性设置、`#51067` 移动端生产环境显示底部导航开关 |
| **扩展（结构性）** | `#52369` GUI 功能搬进内置扩展、`#52868` 类型化组合与生命周期原语、`#53078` 收紧 SDK 表面、`#53075` 面板状态/更新检查修复、`#53077` 去掉启动往返 |
| **账户 / 配对** | `#51266` provider 账号切换、`#50972`+`#50970` 口令配对改一次性链接 |
| **其余** | `#51455` 非 Git 项目恢复 Git 初始化、`#52231` markdown 引用可打开会话、`#51064` 嵌套 tab 分组与完整会话路径、`#52620` A/B 审计发现的扩展化回归修复 |

**一句话**：这 526 个提交里，影响会话界面的核心变化是**「GUI 功能被整体搬进内置扩展 + 面板/时间线细节大量修复 + 版本从 2.0.13 走到 2.0.22」**。

---

## 2. 四条关键确认

### ✅ 确认 1：右侧栏标签 —— **判定「变化」**（不再是固定的「审查 + 上下文」）

`session-side-panel.tsx` 在 `origin/v2` **已不存在**。侧栏现在由扩展注册：

- 标签集合来自扩展点 `Panel`（`packages/gui-extensions/src/sdk/points.ts:239` → `readonly region: "side" | "dock"`）。
- app 侧只负责渲染：侧栏标签条 `packages/app/src/runtime/extension/side-region.tsx:75`
  > `.flatMap((item) => (item.menu === "session.panel" ? [item] : []))`
- 侧栏区域声明：`packages/app/src/session/screen.tsx:69-74` → `createRegion({ region: "side", … tabs: session.layout.tabs })`
- 无障碍名 `packages/app/src/runtime/extension/side-region.tsx:86` → `aria-label={language.t("session.panel.reviewAndFiles")}`，英文值 `packages/app/src/runtime/i18n/en.ts:488` = **"Review and files"**（不是「审查 + 上下文」）。

`region: "side"` 的实际注册方（每个都是一个 tab 来源）：

| 扩展 | 位置 | tab id |
| --- | --- | --- |
| `review` | `gui-extensions/src/review/renderer.tsx:137-139` | `TAB = "changes"`（`:32`） |
| `context` | `gui-extensions/src/context/renderer.tsx:40` | `"main"` |
| `btw` | `gui-extensions/src/btw/renderer.tsx:47` | `"main"` |
| `details` | `gui-extensions/src/details/renderer.tsx:54` | — |
| `file` | `gui-extensions/src/file/renderer.tsx:201` | — |
| `browser` | `gui-extensions/src/browser/renderer.tsx:150` | — |

**结论**：旧判定「标签 = 审查 + 上下文」在 `origin/v2` **不成立**。真实结构是**可扩展的面板槽位**（当前 6 个 side 面板），标签由扩展运行时注册，宿主不再硬编码。

### ✅ 确认 2：终端面板 —— **判定「成立，但机制已变」**

旧判定：终端在右栏**之外** + 命令面板入口。**结构上仍然成立**，但实现方式完全变了：

- 终端现在是内置扩展，且明确挂在 **dock** 区，不是 side 区：
  `packages/gui-extensions/src/terminal/renderer.tsx:144-146`
  > `ctx.add(Panel, { id: "main", region: "dock", …`

  对照 `points.ts:239` 的 `"side" | "dock"` 联合类型，可证 terminal 与 review/context/btw 属于**不同区域**。
- dock key：`terminal/renderer.tsx:6` → `const DOCK = "terminal:main"`
- 命令面板入口仍在：`terminal/renderer.tsx:45-51`
  > `id: "toggle"`, `section: "terminal"`, `bind: "ctrl+\`"`, `slash: { name: "terminal", after: "open" }`
- 另有两个命令：`new`（`ctrl+alt+t`，`:76-80`）、`close`（`mod+w`，`:104`）。
- 会话布局里 side 与 dock 是**两个独立 region**：`packages/app/src/session/session-layout.ts:35-42`（`dockOpened/dockHeight` 与 `sideOpened/sessionWidth`）。

**结论**：原判定**成立**——终端仍在右栏之外、仍由命令面板进入。但它已从「app 内置组件」变成「dock 区的内置扩展」，`screen.tsx` 里不再直接持有终端。

### ✅ 确认 3：`/_assets/` 资源前缀 —— **判定「成立」**

`packages/app/vite.config.ts:50`
> `assetsDir: "_assets",`

该配置在本基线未变更，`/_assets/` 前缀仍是它。

### ✅ 确认 4：`gui-extensions` 的角色 —— **判定「编译进 app」**（不是运行时加载的外部扩展）

| 证据 | 位置 |
| --- | --- |
| app 直接依赖该 workspace 包 | `packages/app/package.json:66` → `"@opencode/gui-extensions": "workspace:*"` |
| 内置扩展被**静态组合**进产物 | `packages/gui-extensions/src/main.ts:19` → `export const builtins = Extension.compose(context, btw, debug, terminal, file, review, details, browser, pairing, updater, ssh, wsl)` |

注释自证其性质（`main.ts:16-18`）：
> `Built-in extensions with their main entries. Lists every built-in so their ids stay reserved.`

**对「托管官方 UI 是否自动带上它」的直接回答：是。** 它是 app 的编译期依赖，官方 UI 一旦托管过来，`review`/`context`/`btw`/`terminal`/`file`/`details`/`browser` 等面板**自动随之获得**，不需要单独接插件。反过来说，**任何以「我们没有这个面板」为前提的排期，都要先确认是不是漏了这条编译依赖**。

---

## 3. 两张表的订正版

> 见 `research/audit/parts/global-table.md`（GLOBAL 13 自造 / 26 缺失）与 `research/audit/parts/right-panel-table.md`（RIGHT_PANEL 20 项）。
> 每条均带 `origin/v2` `file:line`；无实证者一律标「未核对」。

#### GLOBAL_PARITY_GAP 全局对位表 —— 基于 `origin/v2` 基线的纠正复核

> **本文是对 `research/audit/GLOBAL_PARITY_GAP.md` 第 3 节（自造清单）与第 4 节（缺失清单）的复核纠正件。**
>
> - 复核基线：`origin/v2` = `ecfa884ff82e9116e475b71a0c99383b8438d710`（2026-10-04 18:21:24 +1000，`feat(session-ui): show model variant in message meta (#53112)`），较原审计所用快照 `702a73d`（2026-09-20）领先 526 提交。
> - 复核方式：全部经 `_upstream` 工作树**只读**读取 git 对象（`git show` / `git grep` / `git cat-file -e` / `git ls-tree`），未执行任何 `checkout` / `pull` / `switch` / `reset`，`_upstream` 仍停留在 `702a73d`。
> - 实际条目数：**自造 13 条 + 缺失 26 条 = 39 行**，与原文标注一致。
> - 判定词表：`成立` = 原判定在 `origin/v2` 依然为真；`不成立` = 已被 `origin/v2` 证伪；`变化` = 结论方向未变但所指实现/证据已迁移或改变。
> - 未执行：gradle、装机、起服务、设备操作、任何工作区写入（除本文件）。

---

### 1. 复核结果总表

### 1.1 自造清单（本壳有、上游没有）—— 13 条

| # | 原判定 | 新判定 | 依据（origin/v2 文件:行号 + 原文摘录） | 引用文件在 origin/v2 的存续 |
|---|---|---|---|---|
| A1 | 自造：应用内通知横幅 NotificationBanner | **成立** | `git grep -i "banner" origin/v2 -- packages/app/src packages/gui-extensions/src` → **零命中**；`NotificationBanner` 同样零命中 | 反证面为"全树无命中"，无具体文件可指 |
| A2 | 自造：权限请求类系统通知 | **成立** | `packages/app/src/shell/notifications/notification.tsx:26-29`：`export const Notification = Schema.Union([` / `type: Schema.Literal("turn-complete")` / `type: Schema.Literal("error")` —— union 仅 2 个 kind，无权限请求类（原文引 `:30-33`，行号漂移 4 行） | exists |
| A3 | 自造：提问（question）类系统通知 | **成立** | 同上 `notification.tsx:26-29`，union 内无 question kind | exists |
| A4 | 自造：3 个内置调色板命令 `agent.details` / `debug.locations` / `plugin.rpc` | **成立** | 三个 id 在 `packages/app/src`、`packages/cli/src`、`packages/gui-extensions/src`、`packages/session-ui/src` 全域 **ZERO-HIT**；对照命中 `packages/app/src/runtime/i18n/en.ts:22 "command.logs.export": "Export logs"` | exists（对照组）；被查 3 个 id 无文件 |
| A5 | 自造：能力协商集中表 V2Capability | **成立** | `packages/app/src/runtime/platform/platform.tsx:127-137`：`export type Platform = PlatformBase & ( \| { platform: "web"; ... } \| { platform: "desktop"; ... } )` —— 仍为**逐能力可选**的联合类型，无集中协商表（原文引 `:135`，该行现为 `openDirectoryPickerDialog(...)`，语义已变） | exists（证据行漂移） |
| A6 | 自造：通知后台补投 120s TTL + 延迟复查 | **成立** | `packages/app/src/shell/notifications/coordinator.ts:62`：`if (typeof document !== "undefined" && document.hasFocus()) return` —— **读到焦点即放弃，无重估分支**，与原文引用行号完全一致 | exists（行号精确命中） |
| A7 | 自造：上下文超限救援建议（Compact / Undo 双出口） | **成立** | `InvalidRequest` 在 `packages/app/src` 与 `packages/gui-extensions/src` **零业务命中**，仅协议层 `packages/cli/src/acp/error.ts:43 export class InvalidRequestError`、`packages/cli/src/acp/turn.ts:369` | exists（协议层反证面） |
| A8 | 自造：顶栏「时间线搜索」独立可见入口 | **成立** | `packages/app/src/session/screen.tsx:264-266`：`search={{ ... <TimelineSearchBar controller={timelineSearch} /> }}` —— 仅注入搜索栏，无独立触发按钮（原文引 `:273-277`） | exists |
| A9 | 自造：项目行菜单「刷新工作树」 | **成立** | `packages/app/src/home/projects/view.tsx:738-766` 菜单项共 6 项：`onOpenProjectNewSession` / `onImportSession` / `onEditProject` / `onRevealProject` / `onClearNotifications` / `onCloseProject`，**无刷新工作树项**（原文引 `:734-762`，区间微移） | exists |
| A10 | 自造：会话列表加载失败可重试态 | **成立** | `packages/app/src/home/sessions/controller.tsx:65`：`retry: false,` —— 索引查询**显式关闭重试**；`packages/app/src/home/sessions/view.tsx:140-152` 仅 `HomeSessionSkeleton` / `HomeSessionsEmpty` 两态 | exists |
| A11 | 自造：会话行内相对时间 | **成立** | `packages/app/src/home/sessions/view.tsx` 内 `relative` 命中全为 CSS 类（`:135`、`:224`、`:254`、`:322`、`:375`、`:563`），**无相对时间格式化**；`records.ts:30 .sort(compareSessionTime)` 仅排序不渲染 | exists |
| A12 | 自造：工具输出「show more」展开钮 | **成立** | `packages/session-ui/src/components/message-part.css:353` 与 `:412`：`max-height: 240px;` —— **仍为 240px 滚动，无展开钮** | **renamed**：原 `session-ui/src/message/message-part.css` → 现 `session-ui/src/components/message-part.css` |
| A13 | 自造：设置 root 层 `pairing` Tab | **成立** | `git grep -i "pairing" origin/v2 -- packages/app/src/settings/shell.tsx` → **零命中**；配对仍是对话框内嵌：`packages/app/src/servers/connect/dialog.tsx:98` `<PairingScanner` | exists（行号精确命中） |

**自造小计：成立 13 / 不成立 0 / 变化 0 / 未核对 0。** 13 条自造判定在正确基线上全部存活（其中 A12 的引用文件被改名，证据强度不变）。

---

### 1.2 缺失清单（上游有、本壳没有）—— 26 条

| # | 原判定 | 新判定 | 依据（origin/v2 文件:行号 + 原文摘录） | 引用文件在 origin/v2 的存续 |
|---|---|---|---|
| M1 | 缺失：Tab 列表持久化 | **成立** | `packages/app/src/shell/tabs/tabs.tsx:77`：`const [store, setStore, _, ready] = persisted(Persist.window("tabs"), TabStorage.Tabs, [])`；`:85` `persisted(Persist.window("tabs.closed"), TabStorage.Closed, [])` —— 含**已关闭 Tab** 持久化 | exists（行号漂移，原 `:72-78` → 现 `:77-85`） |
| M2 | 缺失：标题栏前进/后退历史栈 | **成立** | `packages/app/src/shell/titlebar/history.ts:1-11`：`export const MAX_TITLEBAR_HISTORY = 100` / `export type TitlebarHistory = { stack: HistoryLocation[]; index: number; action: TitlebarAction }` —— **行号精确命中** | exists（行号精确命中） |
| M3 | 缺失：Ctrl+Tab / mod+option+方向键 切 Tab | **成立** | `packages/app/src/shell/titlebar/tab-strip.tsx:255`：`keybind: \`mod+option+ArrowLeft,ctrl+shift+tab\``；`:263` `keybind: \`mod+option+ArrowRight,ctrl+tab\`` | exists（原文 `:256,264`，偏 1 行） |
| M4 | 缺失：项目树（worktree 嵌套 + expanded 折叠） | **成立** | `packages/app/src/home/sessions/records.ts:34-37`：`worktree: session.location.directory,` / `expanded: false,` —— 项目记录携带 `expanded` 折叠位 | exists（原文 `:36-44` → 现 `:34-37`） |
| M5 | 缺失：server 作用域嵌套设置页 | **成立** | `packages/app/src/settings/shell.tsx:66-72`：`const serverTabs = [ projects / workspaces / providers / models / extensions ]` —— **子页由原文 6 个变为 5 个**，`extensions` 仍在 | exists（计数变化） |
| M6 | 缺失：project 作用域嵌套设置页 | **成立** | `packages/app/src/settings/shell.tsx:547-580`：`nestedProjectTabs` + `Tabs.Content value="general"` / `value="workspaces"` / `value="extensions"` —— **确认 3 个子页** | exists（原文 `:64-68,419` → 现 `:547-580`） |
| M7 | 缺失：project 级 LSPS 扩展子页 | **成立** | `packages/app/src/settings/workspaces/project-extensions.tsx:319` `<Tabs.Trigger value="lsps">{language.t("project.settings.extensions.tab.lsps")}</Tabs.Trigger>`；`:367` `<Tabs.Content value="lsps">` —— **两个行号均精确命中** | exists（行号精确命中） |
| M8 | 缺失：消息内图片缩略图 + 预览弹层 | **成立** | `packages/session-ui/src/message/message-content.tsx:319`：`onClick={() => dialog.show(() => <ImagePreview src={url()} alt={name()} />)}`；`packages/session-ui/src/components/image-preview.tsx:5` `export function createImagePreview()` | exists（原文 `:265-272` → 现 `:319`；`:5` 精确） |
| M9 | 缺失：会话顶栏项目菜单 + 父会话面包屑 | **成立** | `packages/app/src/session/session-identity-header.tsx:307`：`if (props.session?.parentID) return props.session.parentID` —— 父会话解析存在；同文件 `:7-10` 引入 `ProjectAvatar` | exists（原文 `:38-205` → 现 `:307`） |
| M10 | 缺失：会话顶栏 Review 面板开关 | **成立** | `packages/app/src/session/header/session-header-actions.tsx:10-26` `export function SessionReviewToggle()`；`:62-63` `state={props.state.reviewOpened ? "pressed" : undefined}` / `onClick={props.state.onReviewToggle}` | exists（原文 `:37-73` → 现 `:10-26, 36-72`） |
| M11 | 缺失：auto-accept 权限的会话内命令入口 | **成立** | `packages/app/src/session/commands/use-session-commands.tsx:422` `id: "permissions.autoaccept",`；`:428` `onSelect: toggleAutoAccept,`（`:225 const toggleAutoAccept = () => {`） | exists（原文 `:443-444` → 现 `:420-428`） |
| M12 | 缺失：45 个通知音效 + 选择即试听 | **成立** | `packages/app/src/shell/notifications/sound.ts:13` `export const SOUND_OPTIONS = [`，块范围 **13..59**，逐项计数 `{ id:` **恰为 45 条**（与原文数字一致）；试听 `packages/app/src/settings/general/controllers.ts:117` `const preview = createSoundPreviewController(playSoundById)`、`:129 preview.play(...)` | exists（计数精确复核通过） |
| M13 | 缺失：通知「会话有打开 tab 则静音」门控 | **成立** | `packages/app/src/shell/notifications/notification.tsx:227-228` `const hasOpenTab = sessionIDHasOpenTab(tabs.store, input.key, sessionID)` / `if (hasOpenTab && settings.sounds.agentEnabled())`；`:255-256`、`:271` 同构 | exists（原文 `:231,258` → 现 `:227-228,255-256`） |
| M14 | 缺失：timelineDetail / reasoningMode 预设 | **成立** | `packages/session-ui/src/timeline/detail.ts:17` `export const timelinePresets = [`，至 `:61` 覆盖 `everything` / `detailed` / `compact` / `quiet` 等 —— **行号精确命中** | exists（行号精确命中） |
| M15 | 缺失：时间线 diff 虚拟化 + Pierre 语法高亮 | **成立** | `packages/session-ui/src/components/file.tsx:16-19`：`VirtualizedFile,` / `VirtualizedFileDiff,` / `Virtualizer,` `} from "@pierre/diffs"` | exists（原文 `:775,880,1048` 行号已失效，实现仍在） |
| M16 | 缺失：消息导航轨 diff bars | **成立** | `packages/session-ui/src/components/message-nav.tsx:109` `function MessageDiffBars(props: { changes: { additions: number; deletions: number }[] })`；`:62` `<MessageDiffBars changes={local.getChanges?.(message) ?? []} />` | exists（原文 `:109-151`，`:109` 精确） |
| M17 | 缺失：队列面板拖拽排序手势 | **成立** | `packages/app/src/session/composer/queue-panel.tsx:35` `<DragDropProvider`；`:48-53` `onDragEnd={(event) => { ... void props.queue.reorder(arrayMove(...` —— **行号精确命中** | exists（行号精确命中） |
| M18 | 缺失：分隔线 provider 图标 + variant 后缀 | **成立** | `packages/session-ui/src/components/timeline-separator.tsx:9-10`：`providerID?: string` / `variant?: string`；`:27-28` `<Show when={props.providerID}>{(providerID) => <ProviderIcon id={providerID()} .../>}` | exists（原文 `:9-11,16-27` → 现 `:9-10, 27-28`） |
| M19 | 缺失：相机扫码（live camera preview） | **成立** | `packages/app/src/servers/connect/camera.ts:4` `export function createCameraAvailability() {`（**行号精确命中**）；`packages/app/src/servers/connect/scanner.tsx:33` `{ preferredCamera: "environment", maxScansPerSecond: 10, ... }` | exists（`camera.ts:4` 精确；`scanner.tsx` 原文 `:9` → 现 `:33`） |
| M20 | 缺失：项目行菜单「在文件管理器中打开」 | **成立** | `packages/app/src/home/projects/view.tsx:749-756` `<Show when={props.canRevealProject(props.server)}>` / `<Menu.Item onSelect={() => props.onRevealProject(...)}>` / `fileManagerApp(platform.platform === "desktop" ? ... )` | exists（原文 `:745-752` → 现 `:749-756`） |
| M21 | 缺失：桌面 pinch-zoom 设置 | **成立** | `packages/app/src/settings/general/general.tsx:227-229` `const [pinchZoom, { mutate: setPinchZoom }] = createResource(` / `() => desktop() && "getPinchZoomEnabled" in platform,`；`:268` `title={language.t("settings.general.row.pinchZoom.title")}` | exists（原文 `:347` → 现 `:227-272`） |
| M22 | 缺失：release-notes / check-updates 设置 | **变化** | 设置项仍在：`packages/app/src/settings/model.tsx:100` `releaseNotes: Persistence.optional(Schema.Boolean),`；命令已改名 `packages/app/src/settings/keybinds/migration.ts:10` `"app.checkForUpdates": "updater.check",`。**但 UI 已迁出 general.tsx**（`git grep -i "releaseNotes\|checkForUpdates" origin/v2 -- packages/app/src/settings/general/general.tsx` → 零命中），改为内置扩展 `packages/gui-extensions/src/updater/actions.tsx` / `action.ts`。结论方向不变（壳侧仍缺），但引用文件已迁移 | **renamed/迁出**：原 `settings/general/general.tsx:439,452` → 现 `gui-extensions/src/updater/` |
| M23 | 缺失：WSL 服务器类型 | **成立** | WSL 扩展在 `origin/v2` 存在：`packages/gui-extensions/src/wsl/dialog.tsx`、`packages/gui-extensions/src/wsl/renderer.tsx`、`packages/gui-extensions/src/wsl/runtime.ts`、`servers.ts`、`main.ts`。**壳侧仍无 WSL 服务器类型，缺失结论不变** | **moved**：原 `packages/app/src/servers/wsl/{dialog,settings}.tsx` → 现 `packages/gui-extensions/src/wsl/` |
| M24 | 缺失：Tab 右键菜单「关闭标签页」 | **成立** | `packages/app/src/shell/titlebar/tab-nav.tsx:354` `<Menu.Item onSelect={props.onClose}>{language.t("common.closeTab")}</Menu.Item>` | exists（原文 `:361-364` → 现 `:354`） |
| M25 | 缺失：会话行「后台打开为新标签」 | **成立** | `packages/app/src/home/sessions/view.tsx:17` `import { shouldOpenSessionInBackground } from "./open"`；`:34` 注释 `tab in the background without navigating, matching browser conventions.`；`:35 function isBackgroundOpen(event: MouseEvent)`；`:432` `onClick={(event) => props.onSearchSelect(props.record, { background: isBackgroundOpen(event) })}` | exists（原文 `:34-43,399-404`；`:34` 精确） |
| M26 | 缺失：`command.workspace.toggle`（sidebar worktrees 开关） | **不成立** | `git grep -rn "workspace\.toggle" origin/v2 -- packages/app/src packages/session-ui/src packages/gui-extensions/src packages/cli/src` → **零命中**；`packages/app/src/runtime/i18n/en.ts:20` 仅剩 `"command.category.workspace": "Worktree",`，**上游自身已删除该命令**。这不是壳侧缺失，是上游功能下线，原判定归因错误 | exists（被查文件在，但该命令已不存在） |

**缺失小计：成立 24 / 不成立 1 / 变化 1 / 未核对 0。**

---

### 2. 因基线过期而失效的判定（本次复核的头条结论）

以下旧判定在 `702a73d` 快照下得出，在 `origin/v2` 上**已被直接证伪或归因错误**。

### 2.1 `/btw` 面板被判「疑似自造 / 疑为未合入」—— **完全错误**（原文 §5.1 第 1、2 条）

旧判定基于"`_upstream/packages/app/src` grep `\bbtw\b` 零命中、无 `btw/` 目录"。该结论是**基线过期 + 检索范围双重错误**：上游把 GUI 功能搬进了**静态组合进应用的内置扩展包** `packages/gui-extensions`，而非删掉。原审计只在 `packages/app/src` 一个包内 grep，自然零命中。

`origin/v2` 实证：
- `packages/gui-extensions/src/btw/panel.tsx`、**`renderer.tsx`**、**`model.ts`**、**`index.ts`** 均存在。
- `packages/gui-extensions/src/btw/panel.tsx:53`：`data-component="session-working"` —— 与壳 `SessionBtwPanel.kt` 注释所指的属性名**逐字一致**。
- `packages/gui-extensions/src/btw/panel.tsx:36-46` 为 `<Show when={answer()}>` + 复制按钮 + `aria-label`，与壳注释所述 `panel.tsx:36-53` 区间**逐行吻合**。
- 壳注释自述的来源提交 `8ce629b` 已被 526 个提交超越，但该面板**从未被删除，只是换了包**。
- 内置性佐证：`packages/app/package.json` 声明 `"@opencode/gui-extensions": "workspace:*"`，`packages/gui-extensions/src/main.ts` 经 `Extension.compose(...)` 静态组合 `context, btw, debug, terminal, file, review, details, browser, pairing, updater, ssh, wsl`。
- **审计方法论纠正**：判定"上游没有某功能"前，必须至少覆盖 `packages/app/src` + `packages/gui-extensions/src` + `packages/session-ui/src` 三个包；只查 `packages/app/src` 会系统性产生假阳性。

### 2.2 `command.workspace.toggle` 被列为壳侧缺失 —— **归因错误**（M26）

旧判定引 `US/runtime/i18n/en.ts:144-145`。`origin/v2` 上 `workspace.toggle` 在 app / session-ui / gui-extensions / cli **四包全域零命中**，仅存 `en.ts:20 "command.category.workspace": "Worktree",`。即**上游自己已下线该命令**，不能记为壳侧缺失。属"上游能力退役"而非"壳侧未跟进"。

### 2.3 三处"文件不存在"实为**跨包迁移**，缺失结论侥幸成立但依据错误

| 旧引用 | `origin/v2` 实际位置 | 影响 |
|---|---|---|
| `US/servers/wsl/{dialog,settings}.tsx` | `packages/gui-extensions/src/wsl/dialog.tsx` 等 | M23 缺失结论仍成立（壳侧确无 WSL），但反证路径必须改写 |
| `session-ui/src/message/message-part.css` | `packages/session-ui/src/components/message-part.css` | A12 成立，但行号需重取 |
| （原文 §5.1 假定 `session-side-panel.tsx` 在 app/src） | 该文件在 `origin/v2` **确不存在**；会话 UI 已迁至 `packages/app/src/session/`（`screen.tsx`、`session-layout.ts`、`session-panel-width.ts`、`composer/`、`timeline/`、`requests/`、`header/`） | 原文 §5.1 第 11 条（侧栏 Tab 拖拽排序）引 `session-side-panel.tsx:305` 的路径已失效 |

### 2.4 大量行号已漂移，**原引用不可直接复用**

自造小节：A2/A3（`:30-33` → `:26-29`）、A5（`:135` 现为 `openDirectoryPickerDialog`，语义已变）、A8（`:273-277` → `:264-266`）、A9（`:734-762` → `:738-766`）。
缺失小节：M1（`:72-78` → `:77-85`）、M3（`:256,264` → `:255,263`）、M4（`:36-44` → `:34-37`）、M8（`:265-272` → `:319`）、M10（`:37-73` → `:10-26,36-72`）、M11（`:443-444` → `:420-428`）、M13（`:231,258` → `:227-228,255-256`）、M15（`:775,880,1048` → `:16-19` 导入段）、M18（`:16-27` → `:27-28`）、M19（`scanner.tsx:9` → `:33`）、M20（`:745-752` → `:749-756`）、M21（`:347` → `:227-272`）、M22（`:439,452` → 已迁出 general.tsx）、M24（`:361-364` → `:354`）。

**行号精确未漂移、可直接复用的强证据**：M2（`history.ts:1-11`）、M6 反证核心 `coordinator.ts:62`（对应 A6）、M7（`project-extensions.tsx:319,367`）、M12（`sound.ts:13` 起、45 条整）、M14（`detail.ts:17-61`）、M17（`queue-panel.tsx:35,48-53`）、M19（`camera.ts:4`）、M25（`view.tsx:34`）、A13（`dialog.tsx:98`）、A8 未漂移项 `screen.tsx:266`。

### 2.5 一处计数被证伪：server 作用域设置子页

旧判定称 6 个子页（`shell.tsx:59-62`）。`origin/v2` 的 `shell.tsx:59-64` 是 **root** `clientTabs`（general/appearance/notifications/shortcuts），`shell.tsx:66-72` 才是 **server** `serverTabs`，且**只有 5 个**（projects/workspaces/providers/models/extensions）。原文把 root 层与 server 层行号混引。

---

### 3. 复核方法与未执行项

- 全部结论来自 `_upstream` 只读 git 对象读取；`_upstream` 工作树仍停留在 `702a73d`，未发生任何变异。
- `git grep` 全部限定 pathspec 于 `packages/*/src`，未做全树 grep。
- **未执行**：gradle、装机、起服务、设备操作、任何写操作（`write` 仅本文件一次）、任何对既有审计文档的修改。
- **未核对 0 项**：39 条判定全部取得 `origin/v2` 一手证据，无凭旧文档转述的判定。
- **本表不覆盖**：原文 §5.2 的 26 项常规未核对项与 §1/§2 的表面清单——本次范围仅限 §3 自造与 §4 缺失两节的逐条纠正。若需同法复核 §5，请另行指派。

#### RIGHT_PANEL_GAP · v2 基线纠正复核表

- 复核日期：2026-10-04
- 权威基线：`origin/v2` = `ecfa884`（2026-10-04）
- 旧审计基线：`702a73d`（2026-09-20），已过期 526 个提交
- 原文档：`research/audit/RIGHT_PANEL_GAP.md`（v161），其 §3「差距表」为实际的 **20 项**清单；§4 为 TOP 10 缺口
- 复核对象：**原文档的每一条判定**，逐条对照 `origin/v2` 重新取证
- 取证方式：只读 git 对象（`git -C _upstream show/grep/ls-tree/cat-file origin/v2:...`），未改动 `_upstream` 工作树（仍停在 `702a73d`）

#### 复核口径（必读）

1. **本表只复核「上游侧」证据。** 原文档每项都是「上游 vs 本壳」双向对比；本轮只对**上游侧**在 `origin/v2` 重新取证。本壳侧（`app/src/main/**`）的行号与结论**沿用原文档，本轮未复核**，在「依据」列中以「本壳侧未复核」标出。
2. **文件迁移 ≠ 功能消失。** `origin/v2` 已把 GUI 面板整体迁入内建扩展包 `packages/gui-extensions`（`gui-extensions/src/main.ts:19-32` 用 `Extension.compose(context, btw, debug, terminal, file, review, details, browser, pairing, updater, ssh, wsl)` 静态组合；`packages/app/package.json` 以 `workspace:*` 依赖它）。旧文档所有 `_upstream/packages/app/src/session/files/**` 引用均已失效。
3. **判定含义**：
   - `成立` = 原判定的**结论**在 `origin/v2` 依然正确（依据文件可能已换）
   - `不成立` = 原判定在 `origin/v2` 被证伪
   - `变化` = 结论方向仍对，但**判定所依赖的结构/计数/文件已改变**，需重述

---

#### 一、§3 差距表 20 项逐条复核

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

#### 二、§4 TOP 10 缺口逐条复核（补充）

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

#### 三、因基线过期而失效的判定

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

#### 四、从 `origin/v2` 重新回答：右侧栏的实际形态

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

#### 五、本轮未核对 / 不在范围内的项

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



## 4. 官方 UI 对后端的硬依赖清单

> 面向「托管官方 UI」：下表只列 **UI 运行时真的向后端要** 的东西。全部依据为 `origin/v2` 一手 `file:line`。
> 补充细节见 `research/audit/parts/ui-backend-deps.md`（若该文件已生成）。

### 4.1 HTTP / RPC 端点

| 依赖项 | 调用点（origin/v2） | 会话界面硬依赖 | 2.0.14 是否已提供 |
| --- | --- | --- | --- |
| `GET /api/event`（事件流） | `packages/client/src/promise/generated/client.ts:1692` → `{ method: "GET", path: \`/api/event\`, successStatus: 200, declaredStatuses: [400, 401] }`；app 侧封装 `packages/app/src/runtime/server/client.tsx:24` `createOpenCodeEventSource()` | **是**（无它界面不更新） | **未核对** |
| `server.info()`（版本/健康） | `packages/app/src/runtime/server/health.ts:104` → `.server.info({ signal })`，`:105` 归一为 `{ healthy: true, version: status.version }` | **是**（连接建立即用） | **未核对** |
| `session.get()` | `packages/app/src/session/requests/model.ts:49`；`packages/gui-extensions/src/context/export.ts:8`；`packages/app/src/session/commands/export.ts:15` | **是** | **未核对** |
| `message.list()` + `cursor.next` 翻页 | `packages/gui-extensions/src/context/export.ts:9`、`:16`、`:22`；循环条件 `:20` → `while (pages.at(-1)?.cursor.next)`，`limit: 200`、`order: "asc"` | **是**（长会话完整性） | **未核对** |
| PTY 客户端（终端） | `packages/client/src/solid/pty.ts:21` `createPtyClient(...)`；app 装配 `packages/app/src/runtime/server/client.tsx:130` → `pty: createPtyClient(api, { url: http.url })` | **是**（终端面板；无则 `terminal` 扩展不可用） | **未核对** |
| `session.interrupt()` | `packages/app/src/session/revert.ts:47` | 否（revert 流程） | **未核对** |
| `session.compact()` | `packages/app/src/session/commands/use-session-commands.tsx:246` | 否（手动压缩命令） | **未核对** |
| `session.active()` | `packages/app/src/session/requests/auto-approve.ts:82` | 否（auto-approve 流程） | **未核对** |
| `session.background()` | `packages/app/src/session/requests/model.ts:90` | 否（后台会话） | **未核对** |
| `websearch.providers()` | `packages/app/src/session/requests/model.ts:50` | 否（websearch 请求坞） | **未核对** |
| `FileDiffInfo` 类型（review 面板 diff） | `packages/gui-extensions/src/review/contract.ts:1` → `import type { FileDiffInfo } from "@opencode/client/promise"` | **是**（review 面板本体） | **未核对** |

> **说明**：上表「2.0.14 是否已提供」全部为**未核对**——本轮禁止起服务/装机/设备操作，无法验证我方后端实际行为。这是当前最大的未知。

### 4.2 事件 / 流契约

- 事件类型全集在 `packages/client/src/effect/api/api.ts`，本基线共 **48 种** `type: "…"` 事件（`session.*` 44 种 + `file` / `running` / `text` 3 种）。
- UI 侧按类型安全订阅：`packages/app/src/runtime/server/client.tsx:13`
  > `type OpenCodeEventMap = { [Type in OpenCodeEvent["type"]]: Extract<OpenCodeEvent, { type: Type }> }`
  订阅入口 `:16` `on<Type extends OpenCodeEvent["type"]>(type, handler)`，并按目录过滤 `:31`（`event.location?.directory !== directory` 时丢弃）。
- app 内实证订阅到的事件类型（`git grep` 结果）：`config.updated`、`filesystem.changed`、`permission.asked`、`session.agent.selected`、`session.deleted`、`session.inbox.enqueued`、`session.model.selected`、`session.step.failed`，以及 `session.status`（`packages/app/src/session/usage-exceeded-dialogs.tsx:84`）。
  - 逐点：`:70` `.on("session.step.failed"`、`:84` `.on("session.status"`、`packages/app/src/session/requests/auto-approve.ts:18` `.on("permission.asked"`、`packages/app/src/runtime/server/data.ts:11` `.on("session.deleted"`。
- **风险点**：时间线增量渲染依赖 `session.*` 事件族（本基线 44 种）。**2.0.14 是否推送其中全部（尤其 `session.text.*` / `session.tool.*` / `session.step.streamed` 这类流式事件）未核对**——这是托管官方 UI 最可能「连上但不刷新」的点。

### 4.3 扩展 / 运行时端点（编译进 app，非外部插件）

`packages/gui-extensions/src/sdk/points.ts` 定义 UI 与宿主的 IPC 面：`Panel`（`:239` `region: "side" | "dock"`）、`Command`、`MenuItem`（`points.ts:79` `readonly menu: "session.panel"`）、`Slot`、`Contract`。

- 关键契约 `review.changes`：`packages/gui-extensions/src/review/contract.ts` 末行 `Contract.define<Changes, "review.changes">("review.changes")`，其数据面为 `readonly diffs(session): readonly FileDiffInfo[]` 等。
- 组合入口 `packages/gui-extensions/src/main.ts:19` `Extension.compose(...)`，静态内联 `context, btw, debug, terminal, file, review, details, browser, pairing, updater, ssh, wsl`。
- **对托管的含义**：这一层不经过 HTTP，是**编译期**依赖。托管官方 UI 即自动获得；反之若只搬 `packages/app` 而漏掉 `packages/gui-extensions`，六个侧栏面板与终端面板会**静默消失**（不报错，只是没有 tab）。

### 4.4 版本下限

- UI 取版本：`packages/app/src/runtime/server/health.ts:99-105`（`OpenCode.make(...).server.info({signal})` → `status.version`）。
- **`incompatible` 标记并非由版本号比较得出**：全仓 `git grep semver|gte(|MIN_VERSION` 在 `packages/app/src/runtime` 内**零命中**；`health.ts:171` 与 `:198` 均写作
  > `conn.stage === "incompatible" ? { healthy: false, incompatible: true }`

  即只由**连接 stage** 决定，HTTP 连接路径下（`health.ts:98-111`）不做任何版本比较。
- 不兼容时的展示：`packages/app/src/session/incompatible-server-panel.tsx:19-20` → `language.t("session.error.incompatible.description", { server: serverName(server.conn), version: server.health?.version ?? "1" })`。
- **结论**：UI **没有硬性 semver 下限**。这不意味着 2.0.14 能满足——只意味着**不会在握手时被挡住**，而会在缺事件/缺字段时**静默降级**。这比直接报错更难排查，**因此 §5 的 P0-1 才是真正阻塞点**。

### 4.5 详表（逐端点 / 逐事件 / 逐 IPC 点）

#### 官方 UI 对后端的硬依赖清单（origin/v2 基线）

- 取证日期：2026-10-04
- 权威基线：`origin/v2` = `ecfa884`（release v2.0.22）
- 对照旧基线：`702a73d`（2026-09-20）
- 取证方式：只读 git 对象（`git -C _upstream show / grep / ls-tree / log origin/v2:...`），**未改动 `_upstream` 工作树**（仍停在 `702a73d`）
- UI 侧代码范围：`packages/app/src` + `packages/gui-extensions/src`
- 路由侧代码范围：`packages/client/src/promise/generated/client.ts`（生成的类型化客户端，权威 path 映射）、`packages/protocol/src/groups/*.ts`（服务端 HttpApiGroup 定义）

#### 口径说明（必读）

1. 「是否会话界面硬依赖」的判据：**去掉该项后，会话界面（时间线 + 输入框 + 请求坞）无法正常渲染或交互**。右侧栏/浏览器/终端/设置等一律标为「否（外围）」。
2. **本节所有 `file:line` 均为我在 `origin/v2` 实际 `git show` / `git grep` 观察到的行号。** 凡未亲自观察到的，标记 `未核对`，不做推测。
3. **关于 2.0.14 后端的行为，本文一律 `未核对`** —— 本轮不允许启动任何服务，未运行过 2.0.14 二进制。凡涉及 2.0.14 实际表现的结论均不给出。
4. `packages/gui-extensions` 在 `702a73d` **完全不存在**（`git -C _upstream ls-tree 702a73d -- packages/gui-extensions` → 空输出），由 `f406d93 refactor(app): move GUI features into built-in extensions (#52369)` 在本区间内新建。因此下文凡在 `gui-extensions` 下的依据，**一律是本基线新增**。

---

#### A. HTTP / RPC 端点硬依赖

### A1. 会话界面硬依赖（load-bearing）

| 依赖项 | 类型 | origin/v2 依据（文件:行号 + 原文） | 是否会话界面硬依赖 | 说明 |
|---|---|---|---|---|
| `session.get` | `GET /api/session/{sessionID}` | `packages/client/src/promise/generated/client.ts:635` `path: \`/api/session/${encodeURIComponent(input.sessionID)}\`,`；UI 调用 `packages/app/src/session/requests/model.ts:49` `const session = data.session.get(sessionID) ?? (await serverSDK.api.session.get({ sessionID }))` | **是** | 会话标题/状态/元数据首屏来源。另有 7 处调用：`shell/routes/session-ui-provider.tsx:48`、`session/commands/export.ts:15`、`gui-extensions/src/context/export.ts:8`、`home/sessions/controller.tsx:289`、`shell/commands/palette.ts:148`、`shell/titlebar/titlebar.tsx:206`、`home/sessions/command-palette.tsx:33` |
| `session.list` | `GET /api/session` | `packages/client/src/promise/generated/client.ts:540` `path: \`/api/session\`,`；query 见 `:541-545` `limit / order / search / parentID` | **是** | 首页会话列表 + 命令面板。`packages/app/src/home/sessions/controller.tsx:63` `({ signal }) => loadHomeSessionIndex((input, options) => ctx.sdk.api.session.list(input, options), signal)` |
| `session.list` 游标分页 | 同上，`cursor` 往返 | `packages/app/src/session/list.ts:5` `const result = await api.list({ ...input, limit: input.limit ?? 100, cursor })`；`:7` `if (result.data.length === 0 || !result.cursor.next) return sessions`；`:8` `return [...sessions, ...(await load(result.cursor.next))]` | **是** | **契约关键：响应信封必须含 `result.cursor.next`**。该文件在 `702a73d..origin/v2` 区间**无提交**（UNCHANGED）→ 长期存在，非本基线新增 |
| `message.list` | `GET /api/session/{sessionID}/message?limit&order&cursor&type` | `packages/client/src/promise/generated/client.ts:1102` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/message\`,`；`:1103` `query: { limit: input["limit"], order: input["order"], cursor: input["cursor"], type: input["type"] },`；`:1105` `declaredStatuses: [400, 401, 404, 500],` | **是** | 消息分页拉取。服务端声明 404/500，UI 需能容忍 |
| `message.list` `cursor.next` 循环 | 同上 | `packages/gui-extensions/src/context/export.ts:9` `input.api.message.list({ sessionID: input.sessionID, limit: 200, order: "asc" }),`；`:14` `while (pages.at(-1)?.cursor.next) {`；`:19` `cursor: pages.at(-1)!.cursor.next ?? undefined,` | **是** | 与 `session/list.ts` 同契约。**`order: "asc"` 为显式入参**，2.0.14 若不支持 `order` 取值将导致顺序错乱（未核对）。同名实现在 `packages/app/src/session/commands/export.ts:16,22` 重复一份 |
| 事件流（SSE） | `GET /api/event` | `packages/client/src/promise/generated/client.ts:1692` `{ method: "GET", path: \`/api/event\`, successStatus: 200, declaredStatuses: [400, 401], empty: false },` | **是** | 详见 §B。协议定义 `packages/protocol/src/groups/event.ts:42` `"server.event"`、`:44` `"event.subscribe"` |
| SSE 内容类型校验 | — | `packages/client/src/promise/generated/client.ts:366` `if (!isContentType(response, "text/event-stream"))` | **是** | **后端必须以 `text/event-stream` 返回 `/api/event`**，否则 UI 直接判定失败 |
| `server.info` | `GET /api/info` | `packages/client/src/promise/generated/client.ts:424` `{ method: "GET", path: \`/api/info\`, successStatus: 200, declaredStatuses: [400, 401], empty: false },` | **是** | 健康检查唯一入口。`packages/app/src/runtime/server/health.ts:104` `.server.info({ signal })`；另 `packages/app/src/composer/attachments/destination.ts:26` `const info = await sdk.api.server.info({ signal })` |
| `session.diff` | `GET /api/session/{sessionID}/diff` | `packages/client/src/promise/generated/client.ts:870` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/diff\`,`；UI `packages/gui-extensions/src/review/model.ts:163` `? () => view().server.client.session.diff({ sessionID: view().id })`；`:339` `queryFn: () => view().server.client.session.diff({ sessionID: view().id, context: 3 }),` | **是** | Review 面板的会话内变更。**`context: 3` 上下文行数为显式入参**（未核对 2.0.14 是否支持） |
| `vcs.diff` | `GET /api/vcs/diff?location&mode&base&context` | `packages/client/src/promise/generated/client.ts:2111` `path: \`/api/vcs/diff\`,`；`:2112` `query: { location: input["location"], mode: input["mode"], base: input["base"], context: input["context"] },`；`:2114` `declaredStatuses: [400, 401, 404, 503],` | **是** | 未提交变更。UI `packages/gui-extensions/src/review/model.ts:182` `mode: "working"`（未提交工作区）、`:166`、`:360`；`packages/app/src/settings/workspaces/workspaces.tsx:215` `mode: "branch"`。**`mode` 取值域含 `working` / `branch`，且声明了 503**（后端可能因仓库过大而拒绝） |
| `file.read` | `GET /api/fs/read/{path}`（二进制） | `packages/client/src/promise/generated/client.ts:1598` `path: \`/api/fs/read/${encodePath(input.path)}\`,`；`:1603` `binary: true,`；服务端定义 `packages/protocol/src/groups/fs.ts:32` `HttpApiEndpoint.get("fs.read", "/api/fs/read/*", {`；UI `packages/app/src/runtime/server/image.ts:28` `const bytes = await api.file.read(` | **是** | 消息内嵌图片渲染。**注意路由为通配 `/api/fs/read/*`，且响应体是 `Schema.Uint8Array`（`fs.ts:34`）而非 JSON** |
| `file.list` | `GET /api/fs/list` | `packages/client/src/promise/generated/client.ts:1611` `path: \`/api/fs/list\`,`；服务端 `packages/protocol/src/groups/fs.ts:47` `HttpApiEndpoint.get("fs.list", "/api/fs/list", {`；UI `packages/app/src/workspaces/files/model.tsx:63` `serverSDK.api.file.list({ path: dir, location: { directory: scope() } })` | 是（文件面板/选目录） | 服务端语义 `fs.ts:57` `"List direct children using an absolute path or a path relative to the requested location, including parents and siblings outside its directory."` |
| `permission.request.list` | `GET /api/permission/request` | `packages/client/src/promise/generated/client.ts:1504` `path: \`/api/permission/request\`,`；UI `packages/app/src/session/requests/auto-approve.ts:62-63` `input.sdk.api.permission.request` / `.list({ location: { directory: location.directory } })` | **是** | 自动批准需先列出待处理权限请求 |
| `permission.reply` | `POST /api/session/{sessionID}/permission/{requestID}/reply` | `packages/client/src/promise/generated/client.ts:1584` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/permission/${encodeURIComponent(input.requestID)}/reply\`,`；UI `packages/app/src/session/requests/model.ts:115` `serverSDK.api.permission` / `.reply({ sessionID: perm.sessionID, requestID: perm.id, decision: response })` | **是** | 权限坞应答。自动批准同形：`auto-approve.ts:117-118` `.reply({ sessionID: permission.sessionID, requestID: permission.id, decision: "once" })` |
| `session.interrupt` | `POST /api/session/{sessionID}/interrupt` | `packages/client/src/promise/generated/client.ts:980` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/interrupt\`,`；UI `packages/app/src/session/revert.ts:47` `await server.api.session.interrupt({ sessionID }).catch(() => undefined)` | **是** | 停止生成 / 回滚前置 |
| `session.compact` | `POST /api/session/{sessionID}/compact` | `packages/client/src/promise/generated/client.ts:800` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/compact\`,`；UI `packages/app/src/session/commands/use-session-commands.tsx:246` `await serverSDK.api.session.compact({ sessionID })` | 是（会话命令） | 压缩上下文 |
| `session.prompt` | `POST /api/session/{sessionID}/prompt` | `packages/client/src/promise/generated/client.ts:717` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/prompt\`,` | **是** | 输入框提交。UI 侧调用点 `未核对`（本轮未逐一定位 `session.prompt` 的调用行） |
| `session.active` | `GET /api/session/active` | `packages/client/src/promise/generated/client.ts:624` `path: \`/api/session/active\`,`；UI `packages/app/src/session/requests/auto-approve.ts:82` `const active = await input.sdk.api.session.active().catch(() => undefined)` | 是（自动批准前置） | |
| `session.background` | `POST /api/session/{sessionID}/background` | `packages/client/src/promise/generated/client.ts:992` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/background\`,`；UI `packages/app/src/session/requests/model.ts:90` `await serverSDK.api.session.background({ sessionID }).catch((error) => {` | 是（请求坞） | |
| `websearch.providers` | `GET /api/websearch/provider` | `packages/client/src/promise/generated/client.ts:2167` `path: \`/api/websearch/provider\`,`；UI `packages/app/src/session/requests/model.ts:50` `const result = await serverSDK.api.websearch.providers({` | 是（websearch 请求坞） | 对应 UI 文件 `session/requests/session-websearch-dock.tsx` |

### A2. 外围（非会话界面硬依赖）

| 依赖项 | 类型 | origin/v2 依据（文件:行号 + 原文） | 是否会话界面硬依赖 | 说明 |
|---|---|---|---|---|
| `session.command` | `POST /api/session/{sessionID}/command` | `generated/client.ts:738` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/command\`,`；`packages/app/src/new-session/composer-adapter.ts:144` `command: (input) => afterCreation(() => serverSDK.api.session.command(input)),` | 否（外围） | 斜杠命令 |
| `session.shell` | `POST /api/session/{sessionID}/shell` | `generated/client.ts:788` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/shell\`,`；`composer-adapter.ts:145` `shell: (input) => afterCreation(() => serverSDK.api.session.shell(input)),` | 否（外围） | |
| `session.switchAgent` | `POST /api/session/{sessionID}/agent` | `generated/client.ts:669` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/agent\`,`；`composer-adapter.ts:146` `switchAgent: (input) => afterCreation(() => serverSDK.api.session.switchAgent(input)),` | 否（外围） | |
| `session.switchModel` | `POST /api/session/{sessionID}/model` | `generated/client.ts:681` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/model\`,`；`composer-adapter.ts:147` `switchModel: (input) => afterCreation(() => serverSDK.api.session.switchModel(input)),` | 否（外围） | |
| `session.update`（PATCH） | `PATCH /api/session/{sessionID}` | `generated/client.ts:693` `path: \`/api/session/${encodeURIComponent(input.sessionID)}\`,`；`packages/app/src/shell/titlebar/tab-strip.tsx:118` `await ctx.sdk.api.session.update({ sessionID: value.id, title })` | 否（外围） | 标签页重命名 |
| `session.remove` | `DELETE /api/session/{sessionID}` | `generated/client.ts:646` `path: \`/api/session/${encodeURIComponent(input.sessionID)}\`,`；`packages/app/src/runtime/server/runtime.tsx:163` `remove: (sessionID) => sdk.api.session.remove({ sessionID }),` | 否（外围） | |
| `session.view` | `GET /api/session/{sessionID}/view` | `generated/client.ts:1088` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/view\`,` | 未核对 | 本轮未定位 UI 调用点 |
| `session.context` | `GET /api/session/{sessionID}/context` | `generated/client.ts:859` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/context\`,` | 否（外围） | 对应 Context 面板 |
| `session.inbox` | `GET/POST /api/session/{sessionID}/inbox` | `generated/client.ts:883` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/inbox\`,` | 否（外围） | 事件 `session.inbox.*` 见 §B |
| `mcp.list` | `GET /api/mcp` | `generated/client.ts:1329` `path: \`/api/mcp\`,`；`packages/app/src/new-session/mcp.ts:62` `const catalog = await sdk.api.mcp.list({ location: { directory } })` | 否（外围） | 新建会话页 |
| `mcp.connect` / `mcp.disconnect` | `POST /api/mcp` | `generated/client.ts:1329`（同一 group）；`packages/app/src/runtime/server/sync.tsx:299` `await serverSDK.api.mcp.connect({ server: name, location: { directory: key } })` | 否（外围） | |
| `location.get` | `GET /api/location` | `generated/client.ts:449` `path: \`/api/location\`,`；`packages/app/src/runtime/server/global-sync/bootstrap.ts:152` `retry(() => input.api.location.get({ location: { directory: input.directory } }))` | 否（外围） | 全局 bootstrap 必需，非会话界面必需 |
| `vcs.status` | `GET /api/vcs/status` | `generated/client.ts:2085` `path: \`/api/vcs/status\`,`；`packages/app/src/settings/workspaces/workspaces.tsx:214` `context.sdk.api.vcs.status({ location: { directory: workspace.directory } }),` | 否（外围） | |
| `config.get` / `config.update` / `config.shells` | `GET /api/config` 等 | `generated/client.ts:2194` `path: \`/api/config\`,`、`:2206` `/api/config/shell`；`packages/app/src/settings/general/controllers.ts:35` `context.sdk.api.config.get().catch(() => []),` | 否（外围） | |
| `plugin.list` | `GET /api/plugin` | `generated/client.ts:500` `path: \`/api/plugin\`,`；`packages/app/src/settings/providers/extensions.tsx:51` `() => serverSdk.api.plugin.list().then((result) => result.data),` | 否（外围） | |
| `project.list` / `project.update` | `GET /api/project` | `generated/client.ts:1463` `{ method: "GET", path: \`/api/project\`, successStatus: 200, declaredStatuses: [400, 401, 404], empty: false },`；`packages/app/src/settings/workspaces/queries.ts:12` `queryFn: () => sdk.api.project.list(),` | 否（外围） | |
| `worktree.create` | `POST /api/worktree` | `generated/client.ts:2023` `path: \`/api/worktree\`,`；`packages/app/src/workspaces/create.ts:12` `const created = await input.api.worktree.create({` | 否（外围） | |
| `integration.get` | `GET /api/integration` | `generated/client.ts:1182` `path: \`/api/integration\`,`；`packages/app/src/providers/connect/mcp.ts:43` `const integration = await serverSDK.api.integration.get({ integrationID: current.integrationID, location: ref })` | 否（外围） | |
| `credential.remove` | `DELETE /api/credential` | `generated/client.ts:1411` `path: \`/api/credential\`,`；`packages/app/src/settings/providers/providers.tsx:218` `credentials.map((credential) => serverSdk.api.credential.remove({ credentialID: credential.id })),` | 否（外围） | |
| `pty` | `POST /api/pty` | `generated/client.ts:1701` / `:1713` `path: \`/api/pty\`,` | 否（外围） | 终端扩展 |
| `api.shell.output` | 路由 `未核对` | UI 调用点 `packages/app/src/shell/routes/session-ui-provider.tsx:79` `shellOutput={(input) => serverSDK.api.shell.output(input)}`；`generated/client.ts:1911` / `:1923` 存在 `path: \`/api/shell\`,` 但**本轮未逐一确认哪一条对应 `shell.output`** | 否（外围） | 路由映射标 `未核对` |

---

#### B. 事件 / 流契约

**传输层**：`GET /api/event`，SSE。客户端订阅入口 `packages/client/src/solid/connection.ts:97` `const iterator = api.event.subscribe({ signal: request.signal, onActivity: touch })[Symbol.asyncIterator]()`；断线重连日志 `:162` `"event stream disconnected"`。

**UI 侧订阅点**：`packages/app/src/runtime/server/sync.tsx:227` `const unsub = serverSDK.event.listen((event) => {` —— 单一全局订阅，按 `event.location.directory` 分发到子 store（`:241-243`）。事件源封装 `packages/app/src/runtime/server/client.tsx:93-95` `onEvent(event) { events.publish(event) }`。

**事件 → UI 状态的落地映射**在 `packages/client/src/solid/data.ts`（`case "<name>":` 逐事件归约）。下表为会话界面渲染真正需要的事件：

| 事件名 | origin/v2 依据（文件:行号 + 原文） | 是否会话界面硬依赖 | 说明 |
|---|---|---|---|
| `session.step.started` | `packages/client/src/solid/data.ts:838` `case "session.step.started"` | **是** | 一轮 assistant 步骤开始 |
| `session.step.streamed` | `data.ts:873` `case "session.step.streamed"` | **是** | 流式内容 |
| `session.step.ended` | `data.ts:878` `case "session.step.ended"` | **是** | |
| `session.step.failed` | `data.ts:890` `case "session.step.failed"` | **是** | 另 UI 直接订阅 `packages/app/src/session/usage-exceeded-dialogs.tsx:70` `sdk().event.on("session.step.failed", (evt) => {` |
| `session.text.started` / `.delta` / `.ended` | `data.ts:904` / `:909` / `:914` `case "session.text.delta"` 等 | **是** | 文本 part 三段式 |
| `session.tool.input.started` / `.delta` / `.ended` | `data.ts:919` / `:930` / `:935` | **是** | 工具入参流；`data.ts:932` `if (tool.state.status === "streaming") tool.state.input += event.data.delta` |
| `session.tool.called` / `.progress` / `.success` / `.failed` | `data.ts:940` / `:948` / `:953` / `:967` | **是** | 工具生命周期四态 |
| `session.reasoning.started` / `.delta` / `.ended` | `data.ts:982` / `:992` / `:997` | **是** | 思考过程 part |
| `session.execution.started` / `.succeeded` / `.failed` / `.interrupted` | `data.ts:1009` / `:1025` / `:1026` / `:1027`（另 `:1033`、`:1039`、`:1041` 有二次判定） | **是** | 会话运行态，驱动停止/忙碌指示 |
| `session.retry.scheduled` | `data.ts:1004` `case "session.retry.scheduled"` | 是 | |
| `permission.asked` | `data.ts:1160` `case "permission.asked"`；UI 直接订阅 `packages/app/src/session/requests/auto-approve.ts:18` `input.sdk.event.on("permission.asked", (event) => {` | **是** | 权限坞出现的前提 |
| `permission.replied` | `data.ts:1167` `case "permission.replied"` | **是** | 坞消失 |
| `session.created` | `data.ts:631` `case "session.created"` | 是 | 另 UI 订阅 `packages/app/src/providers/models/selection.tsx:329` `serverSDK.event.on("session.deleted", (event) => {` |
| `session.deleted` | `data.ts:642` `case "session.deleted"` | 是 | |
| `session.renamed` | `data.ts:687` `case "session.renamed"` | 是 | 标题实时更新 |
| `session.status` | UI 订阅 `packages/app/src/session/usage-exceeded-dialogs.tsx:84` `sdk().event.on("session.status", (evt) => {` | 是（配额弹窗） | |
| `session.model.selected` / `session.agent.selected` | `data.ts:665` / `:652`；UI 订阅 `packages/app/src/providers/models/selection.tsx:327` / `:326` | 是（头部模型/Agent 显示） | |
| `session.compaction.started` / `.delta` / `.ended` / `.failed` | `data.ts:1012` / `:1093` / `:1099` / `:1133` | 是 | 压缩上下文进度 |
| `session.revert.staged` / `.cleared` / `.committed` | `data.ts:1068` / `:1072` / `:1076` | 是 | 回滚 UI |
| `session.shell.started` / `.ended` | `data.ts:815` / `:828` | 是 | |
| `session.moved` | `data.ts:704` `case "session.moved"` | 否（外围） | |
| `session.permissions` | `data.ts:700` `case "session.permissions"` | 否（外围） | |
| `session.usage.updated` | `data.ts:645` `case "session.usage.updated"` | 否（外围，Context 面板） | |
| `session.inbox.delivered` / `.enqueued` / `.cancelled` / `.delivery.changed` | `data.ts:749` / `:773` / `:768` / `:765`；UI 订阅 `packages/app/src/new-session/composer-adapter.ts:182` `event.on("session.inbox.enqueued"` | 否（外围） | |
| `session.instructions.updated` | `data.ts:791` `case "session.instructions.updated"` | 否（外围） | |
| `session.synthetic` | `data.ts:805` `case "session.synthetic"` | 否（外围） | |
| `session.viewed` | `data.ts:1063` `case "session.viewed"` | 否（外围） | |
| `form.replied` / `form.cancelled` | `data.ts:1170` / `:1171` | 否（外围） | |
| `server.connected` | `data.ts:595` `case "server.connected"` | 否（外围） | |
| `project.updated` | `data.ts:628` `case "project.updated"`；`sync.tsx:229` `if (event.type === "project.updated") applyProjectUpdate(event.data)` | 否（外围） | |
| `worktree.resolved` | `data.ts:727` `case "worktree.resolved"` | 否（外围） | |
| `config.updated` | `data.ts:1275` `case "config.updated"`；UI 订阅 `packages/app/src/settings/workspaces/project-extensions.tsx:133` `server.event.on("config.updated", (event) => {` | 否（外围） | |
| `filesystem.changed` | UI 订阅 `packages/app/src/workspaces/files/model.tsx:236` `const stop = sdk().event.on("filesystem.changed", (even...` | 否（外围，文件面板） | 该事件在 `data.ts` 中无 `case`，走独立订阅 |
| `mcp.status.changed` | `data.ts:1287` `case "mcp.status.changed"` | 否（外围） | |
| **question / 问题请求事件** | — | **`未核对`** | `git -C _upstream grep -n "question" origin/v2 -- packages/client/src/solid/data.ts` **无命中**；UI 侧存在 `packages/app/src/session/requests/session-question-dock.tsx`，但其事件来源与对应 `case` 本轮**未定位**。不排除它复用 `form.*` 或 `session.inbox.*` 通道 |

**事件契约总判**：`/api/event` 的 SSE 流 + 上述约 25 个 `session.*` / `permission.*` 事件名，是会话界面的**单一实时数据来源**。`data.ts` 在 `702a73d..origin/v2` 区间仅 1 个提交（`53179da feat(core): order projects by recent activity (#50790)`，与本表所列事件名无直接关系）→ **事件名集合长期稳定，非本基线新增**。

---

#### C. 扩展 / 运行时（renderer ↔ host IPC）

扩展点定义集中在 `packages/gui-extensions/src/sdk/points.ts`。**这些点全部位于本基线新建的 `packages/gui-extensions` 包内（`702a73d` 无此包）→ 均为本基线新增**。

| 扩展点 / 契约 | origin/v2 依据（文件:行号 + 原文） | 是否会话界面硬依赖 | 说明 |
|---|---|---|---|
| `Panel` | `packages/gui-extensions/src/sdk/points.ts:637` `export const Panel = Point.define<Panel>("panel")` | **否（外围）** | 右侧栏/底部坞的全部来源。无 Panel 时侧栏为空，**但时间线与输入框仍完整可用** |
| `Panel.region` | `points.ts:239` `readonly region: "side" \| "dock"` | 否（外围） | |
| `Panel` 注册（`side` 区） | `browser/renderer.tsx:150` `region: "side",`；`btw/renderer.tsx:47`；`context/renderer.tsx:40`；`details/renderer.tsx:54`；`file/renderer.tsx:201`；`review/renderer.tsx:139` | 否（外围） | 6 个 side 标签 |
| `Panel` 注册（`dock` 区） | `terminal/renderer.tsx:146` `region: "dock",` | 否（外围） | |
| `Command` | `points.ts:612` `export const Command = Point.define<Command>("command")` | 否（外围） | |
| `MenuItem` | `points.ts:622` `export const MenuItem = Point.define<MenuItem>("menu-item")`；联合类型 `points.ts:127` `export type MenuItem = SessionPanelMenuItem \| ServerAddMenuItem \| ServerRowMenuItem` | 否（外围） | |
| `Slot` / `SlotMap` | `points.ts:687` `export const Slot = Point.define<Slot>("slot")`；`points.ts:550` `export interface SlotMap`；`points.ts:579` `export type Slot = {` | 否（外围） | |
| `Server` / `ServerEntry` | `points.ts:476` `export interface Server`；`points.ts:421` `export interface ServerEntry` | 否（外围） | 扩展贡献的服务器 |
| `Contract` 定义机制 | `packages/gui-extensions/src/sdk/core.ts:940` `* export const Changes = Contract.define<Changes, "review.changes">("review.changes")` | 否（外围） | 文档示例行 |
| **`review.changes` 契约** | `packages/gui-extensions/src/review/contract.ts:39` `export const Changes = Contract.define<Changes, "review.changes">("review.changes")`；契约面 `:7-36` `export interface Changes`，含 `:9` `diffs(session: SessionRef): readonly FileDiffInfo[]`、`:20` `details(session: SessionRef): readonly FileDiffInfo[] \| undefined`、`:33` `watch(screen: SessionScreen, source: "tree" \| "files" \| "details"): () => void` | **否（外围）** | **不是会话界面所需**。`git grep -rn "review.changes" origin/v2 -- packages/gui-extensions/src packages/app/src` 命中仅 2 处：定义处（`contract.ts:39`）与文档示例（`core.ts:940`），**无消费方注册**。它是 review 扩展对 details 扩展的**内部**契约，不跨进程 |
| `ServerState`（含 `incompatible`） | `points.ts:372` `export type ServerState = "stopped" \| "starting" \| "auth" \| "ready" \| "failed" \| "incompatible"` | 否（外围） | 见 §D |

**C 段结论**：**没有任何 `Contract` 是会话界面的硬依赖。** 扩展点（Panel / Command / MenuItem / Slot）全部只影响右侧栏、命令面板、菜单与坞区。即使后端 fork 移除了全部内建扩展的注册，会话时间线与输入框仍依赖 §A 的 HTTP 端点 + §B 的事件流。

---

#### D. 版本下限（Version floor）

**这是本轮最重要的发现，且与任务预设的预期相反。**

| 检查项 | origin/v2 依据（文件:行号 + 原文） | 结论 |
|---|---|---|
| `incompatible-server-panel.tsx` 是否做版本比较 | `packages/app/src/session/incompatible-server-panel.tsx:19-22` `{server: serverName(server.conn),` / `version: server.health?.version ?? "1",` / `})} ` | **否**。该文件**仅把版本号渲染到文案里**，无任何比较、分支或阈值 |
| 健康检查是否比较版本 | `packages/app/src/runtime/server/health.ts:104-105` `.server.info({ signal })` / `.then((status) => ({ data: { healthy: true as const, version: status.version } }))` | **否**。`checkServerHealth`（`health.ts:78-113`）只**记录** `version`，从不与任何常量比较。类型 `ServerHealth`（`health.ts:8-14`）的 `incompatible?: boolean`（`:11`）在 HTTP 路径上**恒不置位** |
| `incompatible` 何时出现 | `packages/app/src/runtime/server/health.ts:161` `stage: conn.type === "extension" ? conn.state : undefined,`；`:171-172` `: conn.stage === "incompatible"` / `? { healthy: false, incompatible: true }` | **仅对 `type === "extension"` 的连接生效**（即 SSH 等由扩展贡献的服务器）。`sidecar` / 普通 HTTP 连接的 `stage` 恒为 `undefined`，**永远不会进入 `incompatible` 分支** |
| `incompatible` 的语义声明 | `packages/gui-extensions/src/sdk/points.ts:370` `* - \`incompatible\`: running a version this app does not support.` | 注释声称是版本不支持，但**产生该状态的实际判定并非版本比较**（见下条） |
| 实际判定逻辑 | `packages/gui-extensions/src/ssh/remote-cli.ts:19-23` `export function requireVersion(version: string) {` / `  if (version !== "local" && !/^[0-9][a-zA-Z0-9.+-]*$/.test(version))` / `    throw new Failure({ code: "version", detail: version })` / `  return version` | **这是字符串格式检查，不是 semver 下限比较。** 正则 `^[0-9][a-zA-Z0-9.+-]*$` 只要求「以数字开头且后续是合法版本字符」。`2.0.14` **能通过**；`2.0.9`、`2.0.1` 同样能通过 |
| 状态映射 | `packages/gui-extensions/src/ssh/controller.ts:225` `stage: code === "version" ? "incompatible" : "failed",` | SSH 连接失败时把 `code === "version"` 映射为 `incompatible` |
| 失败码取值域 | `packages/gui-extensions/src/ssh/remote-cli.ts:7` `code: Schema.Literals(["platform", "version", "install"]),` | 三个码，**无"版本过低"语义** |

### D 段结论

> **官方 UI 在 `origin/v2`（v2.0.22）中不存在任何显式的服务器版本兼容性检查，也没有任何硬编码的最低服务器版本常量。**

取证范围与反证：

1. `git -C _upstream grep -n -E "MINIMUM_SERVER|MIN_SERVER|minimum.*version|serverVersion|requiredVersion|minVersion|SUPPORTED_" origin/v2 -- packages/app/src packages/gui-extensions/src` → **仅命中 i18n 文案**，无常量。
2. 对 `app` / `gui-extensions` / `desktop` / `cli` / `server` / `client` / `sdk` / `tui` / `web` / `session-ui` / `core` 逐包 grep 硬编码版本字面量 `"2.x.y"` → **零命中**。
3. `git grep -n -E "semver|compare.*version|satisfies\(" origin/v2 -- packages/app/src packages/gui-extensions/src` → **零命中**。
4. 唯一名为「版本兼容」的状态 `incompatible`，其唯一产生路径是 SSH 远端 CLI 的**版本字符串格式**校验（`remote-cli.ts:19-23`），且只作用于扩展型连接。

### D 段的基线新颖度

| 项 | 判定 | 依据 |
|---|---|---|
| `packages/app/src/session/incompatible-server-panel.tsx` | **长期存在，本区间无改动** | `git -C _upstream ls-tree 702a73d -- packages/app/src/session/incompatible-server-panel.tsx` → `100644 blob 7dbfc7fd5b8c50831949451c0f85b44e29ca8478`（旧基线已存在）；`git log --oneline 702a73d..origin/v2 -- <该文件>` → **无输出** |
| `packages/app/src/runtime/server/health.ts` | 本区间有 2 个提交 | `f406d93 refactor(app): move GUI features into built-in extensions (#52369)`、`2caba90 feat(app): replace password pairing with one-time links (#50972)` |
| `packages/gui-extensions/src/ssh/remote-cli.ts` | **本基线新增文件** | `packages/gui-extensions` 包在 `702a73d` 不存在；该文件随 `f406d93` 迁入 |
| 其余基线新颖度 | 见下表 | |

### 全部依据的基线新颖度一览

| 文件 | `702a73d..origin/v2` 状态 |
|---|---|
| `packages/gui-extensions/**`（全包，含 `sdk/points.ts`、`review/*`、`context/export.ts`、`ssh/*`） | **本基线新增**（`702a73d` 无此包；由 `f406d93` 引入）。`points.ts` 区间内有 6 个提交（`5589ca4`、`0048c97`、`56145fc`、`f406d93` 等）；`review/model.ts` 6 个；`context/export.ts` 1 个（`0048c97 feat(gui-extensions): add typed composition and lifetime primitives (#52868)`） |
| `packages/app/src/session/list.ts` | **UNCHANGED**（区间内无提交） |
| `packages/app/src/session/incompatible-server-panel.tsx` | **UNCHANGED**（旧基线已存在，区间内无提交） |
| `packages/app/src/runtime/server/health.ts` | CHANGED（2 个提交） |
| `packages/client/src/promise/generated/client.ts` | CHANGED（10 个提交，含 `3e1aa1e fix(server): return 404 when a location folder is missing (#52668)`、`41516c7`、`19f2038 feat(plugin): support parent session creation (#52359)`） |
| `packages/client/src/solid/data.ts` | CHANGED（1 个提交 `53179da`，与本表所列事件名无直接关系） |

---

#### 对 v2 后端 2.0.14 的判定建议

> **前置声明**：本轮**未启动任何服务、未运行 2.0.14 二进制**。下表中凡涉及 2.0.14 实际行为的判断，一律为 `未核对`。「已核对」仅表示**该依赖在 `origin/v2` UI 侧的契约已取证**，不表示 2.0.14 满足它。

### 硬依赖逐条

| 硬依赖 | UI 侧契约 | 对 2.0.14 的判定 | 需运行期验证的具体内容 |
|---|---|---|---|
| `GET /api/session/{id}` | `已核对` | `未核对` | 2.0.14 是否存在该路由，且响应体形状与 `SessionGetOutput` 一致 |
| `GET /api/session` + `cursor.next` 信封 | `已核对`（`session/list.ts` UNCHANGED，契约长期稳定） | `未核对` | **重点**：2.0.14 的 `/api/session` 响应是否含 `{ data, cursor: { next } }` 信封。若返回裸数组，`listAllSessions` 的 `result.cursor.next` 会抛错，会话列表**整体不可用** |
| `GET /api/session/{id}/message` + `cursor.next` | `已核对` | `未核对` | 同上信封问题；另需确认是否支持 `order=asc` 与 `limit` |
| `GET /api/event`（SSE） | `已核对` | `未核对` | **最高优先级**：2.0.14 是否暴露 `/api/event`，是否以 `text/event-stream` 返回（`generated/client.ts:366` 会硬校验），是否支持长连接与断线重连 |
| §B 的约 25 个事件名 | `已核对`（事件名集合长期稳定，`data.ts` 区间内仅 1 个无关提交） | `未核对` | 2.0.14 是否发布全部事件名。**缺失任一 `session.text.*` / `session.tool.*` / `permission.asked` 即导致时间线不渲染或权限坞不出现** |
| `GET /api/info` | `已核对` | `未核对` | 2.0.14 是否提供 `/api/info`；缺失则健康检查失败（`health.ts:104`） |
| `GET /api/session/{id}/diff` | `已核对` | `未核对` | 是否支持 `context` 参数（UI 传 `context: 3`） |
| `GET /api/vcs/diff`（`mode=working` / `branch`） | `已核对` | `未核对` | `mode` 取值域是否含 `working`。**缺失则右侧 review 面板与「未提交变更」整体失效**；另注意 UI 容忍 503 |
| `GET /api/fs/read/*`（二进制） | `已核对` | `未核对` | 路径是否仍为通配 `/api/fs/read/*`；响应体是否仍为原始 `Uint8Array` 而非 JSON/base64 |
| `GET /api/fs/list` | `已核对` | `未核对` | |
| `GET /api/permission/request` + `POST .../permission/{id}/reply` | `已核对` | `未核对` | `reply` 入参 `decision` 的取值域（UI 用 `"once"` 与 `response`）。**若 2.0.14 的 decision 枚举不同，权限坞无法应答 → 会话卡死** |
| `POST /api/session/{id}/interrupt` | `已核对` | `未核对` | |
| `POST /api/session/{id}/compact` | `已核对` | `未核对` | |
| `POST /api/session/{id}/prompt` | `未核对`（路由已核对，UI 调用行未定位） | `未核对` | 请求体 schema（未在本文逐字段取证） |
| `GET /api/session/active`、`POST .../background`、`GET /api/websearch/provider` | `已核对` | `未核对` | |

### 版本下限判定（对 §D 的直接回答）

| 问题 | 答案 |
|---|---|
| 官方 UI 要求的最低服务器版本是多少？ | **不存在这个数字。** UI 在 `origin/v2` 未硬编码任何最低版本常量（§D 反证 1–3）。唯一名为 `incompatible` 的状态由 `remote-cli.ts:19-23` 的**版本字符串格式正则**产生，不是 semver 下限 |
| 2.0.14 会被 UI 判为 `incompatible` 吗？ | 对**普通 HTTP / sidecar 连接：不会** —— `health.ts:161` 使这类连接的 `stage` 恒为 `undefined`，永不进入 `incompatible` 分支。对 **SSH 扩展连接：不会** —— `2.0.14` 能通过正则 `^[0-9][a-zA-Z0-9.+-]*$` |
| 既然没有版本闸门，2.0.14 能否承载官方 UI？ | **版本号本身不构成阻碍；真正的阻碍是 §A/§B 的路由与事件契约漂移。** 2.0.14 与 2.0.22 之间跨 526 个提交（`packages/client/src/promise/generated/client.ts` 在此区间有 10 个提交），路由与事件集可能已发生实质变化 |
| 建议的最小验证动作 | 启动 2.0.14 后依次 curl：① `GET /api/info` ② `GET /api/session`（**检查是否含 `cursor.next` 信封**）③ `GET /api/session/{id}/message?limit=200&order=asc`（同上）④ `curl -N -H 'Accept: text/event-stream' /api/event`（**检查 Content-Type 与是否推送 `session.text.delta` / `permission.asked`**）⑤ `GET /api/vcs/diff?directory=…&mode=working` ⑥ `GET /api/fs/read/<file>` |
| 本轮未做 | 上述 6 项 curl **全部未执行**（本轮禁止启动服务）。故 2.0.14 的实际行为**全部为 `未核对`** |

---

#### 未核对清单（本轮明确未取证，勿据此下结论）

1. `session.prompt`（`generated/client.ts:717`）在 UI 侧的**具体调用行**与请求体 schema。
2. `api.shell.output` 对应的**确切路由**（`generated/client.ts:1911` / `:1923` 未逐一确认）。
3. `session.view`（`generated/client.ts:1088`）的 UI 调用点。
4. **question / 问题请求的事件来源** —— `session-question-dock.tsx` 存在，但 `data.ts` 中无 `question` 相关 `case`，其数据通路未定位。
5. §A 各端点的**请求体/响应体完整 schema**（本文只取证了路由 path、query 参数名与部分 success 形状）。
6. **2.0.14 后端的任何实际行为** —— 未启动、未运行。
7. `packages/server/src` 侧的路由**实现**（本文路由依据取自 `packages/protocol/src/groups/*.ts` 的 `HttpApiEndpoint` 定义与生成客户端，未逐一核对 `server` 包的 handler 实现）。

---

## 5. 仍然成立的差距（真清单）

**计数口径**：GLOBAL 表 39 条 → **成立 24 + 变化 1 = 25 条仍成立**（1 条不成立、0 条未核对）；RIGHT_PANEL 表 20 条 → **成立 11 + 变化 9 = 20 条仍成立**（0 条不成立）。合计 **45 条**在 `origin/v2` 上依然成立。

但**逐条排期是错的**。既然 `gui-extensions` 是**编译期依赖**（见确认 4），绝大多数侧栏/面板类差距的根因是同一个。真正需要排期的是下面这几条。

### P0 —— 与「托管官方 UI」直接冲突（先解决，否则托管即坏）

| # | 差距 | 依据 | 说明 |
| --- | --- | --- | --- |
| **P0-1** | **后端版本落差：UI 侧 2.0.22 vs 我方 2.0.14** | 区间内 10 次 `sync release versions`（2.0.13→2.0.22）；UI 取版本 `packages/app/src/runtime/server/health.ts:99-105`（`OpenCode.make(...).server.info({signal})` → `{healthy:true, version}`） | 落后 8 个版本。**未发现 UI 侧硬性 semver 下限**（`incompatible` 仅由连接 stage 决定，`health.ts:171`、`:198`），但事件/接口的新增项需逐一核对——见第 4 节 |
| **P0-2** | **扩展宿主 / `Panel` 扩展点缺失 → 六个侧栏面板全部落空** | 扩展点定义 `packages/gui-extensions/src/sdk/points.ts:239` → `readonly region: "side" \| "dock"`；宿主聚合 `packages/app/src/runtime/extension/side-region.tsx:75` → `.flatMap((item) => (item.menu === "session.panel" ? [item] : []))`；六个 `region:"side"` 注册方见 `review/renderer.tsx:139`、`context/renderer.tsx:40`、`btw/renderer.tsx:47`、`details/renderer.tsx:54`、`file/renderer.tsx:201`、`browser/renderer.tsx:150` | **单点前置**。不接 `packages/gui-extensions`（`app/package.json:66` workspace 依赖），review / context / btw / file / details / browser 一个都不会出现。旧 RIGHT 表中 9 条「变化」判定（#1/2/3/8/14/15/16/19/20）根因都在这里 |
| **P0-3** | **review 面板的后端契约** | `packages/gui-extensions/src/review/contract.ts:1` → `import type { FileDiffInfo } from "@opencode/client/promise"`；契约 `Contract.define<Changes, "review.changes">` 同文件末行 | review 面板硬依赖 `FileDiffInfo`（文件 diff / 未提交改动）。**我方 2.0.14 是否提供该类型对应接口：未核对** |
| **P0-4** | **消息分页游标契约** | `packages/gui-extensions/src/context/export.ts:16-26` → `api.message.list({sessionID, limit: 200, order: "asc"})` + `while (pages.at(-1)?.cursor.next)` 循环 | 导出/上下文面板按 `cursor.next` 翻页。**2.0.14 是否同形：未核对** |

### P1 —— 会话界面本体差距（35 条，属功能补齐，非托管阻塞）

- **GLOBAL 缺失 25 条**（M1–M25，M22 判「变化」）：Tab 持久化(M1)、标题栏历史栈(M2)、Ctrl+Tab 切 Tab(M3)、worktree 嵌套(M4)、server/project 作用域设置页(M5/M6)、LSPS 子页(M7)、图片预览(M8)、会话顶栏项目菜单+面包屑(M9)、Review 开关(M10)、auto-accept 命令(M11)、45 个通知音效(M12)、静音门控(M13)、timeline 预设(M14)、diff 虚拟化(M15)、diff bars(M16)、队列拖拽(M17)、分隔线 provider 图标(M18)、相机扫码(M19)、文件管理器打开(M20)、pinch-zoom(M21)、release-notes/更新检查(M22)、WSL 服务器类型(M23)、Tab 右键关闭(M24)、后台打开新标签(M25)。
  *已排除*：M26 `command.workspace.toggle` —— 上游自己已下线，**不是我们的差距**。
- **RIGHT_PANEL 成立 11 条**（#4、5、6、7、9、11、12、13、17 + TOP-3/4/5/6/7/9/10 中对应项）：Context 用量指示器缺失、Context 关闭按钮缺失、无 Open file 空白态、16 元用量算法/字段、统一历史显示、置顶/搜索结果高亮、移动端 Context 入口等。
  *已排除*：TOP-1「卡片空态」与 #18「右栏内无会话切换」在新基线上前提已变（面板已扩展化），需重写而非照做。

### P2 —— 已被证伪、不必排期

- `/btw` 判「疑似自造」——**撤销**。`packages/gui-extensions/src/btw/{panel,renderer,model,index}` 均存在，`panel.tsx:53` 的 `data-component="session-working"` 与我方注释逐字吻合。
- `command.workspace.toggle` 判「壳侧缺失」——**撤销**（上游已下线）。
- 「server 设置子页 6 个」——**计数错**，实为 5 个（`settings/shell.tsx:66-72`）；旧文把 root 层 `clientTabs`（`:59-64`）误当 server 层。
- 「右栏 tabs = 审查+上下文」——**撤销**，实为 6 个扩展注册的 side 面板。
- 「终端入口靠 `command.tsx:18` `EDITABLE_KEYBIND_IDS`」——**撤销**，`command.tsx:79` 只剩 `"terminal"` 字符串字面量；命令改由 `terminal/renderer.tsx:45/50/75-80` 注册。

---

## 6. 未核对清单

以下为**本轮确实未取到 `origin/v2` 一手证据**的部分，不得当作结论使用：

1. **我方后端（`opencode2.exe` 2.0.14）的实际行为**——本轮禁止起服务/装机/设备操作，因此**完全未核对**。具体待核：
   - `server.info()` 返回结构与 `version` 字段；
   - `FileDiffInfo` 对应的 diff / 未提交改动接口是否存在且同形；
   - `message.list` 的 `cursor.next` 分页语义；
   - `/api/event` 事件流是否推送本基线 UI 订阅的全部事件类型（见第 4 节）。
2. **RIGHT_PANEL 表的壳侧（我方实现）一半**——该表只复核了上游侧证据，壳侧是否真缺未逐条核。
3. **旧 GLOBAL 文档 §5.2 的 26 项「未核对」与 §1/§2 表面清单**——本轮范围限定 §3/§4，未复核。
4. `right-panel-table.md` 自列的未核对项：`reserveReviewToggle`、`session-panel-width.ts` 内部实现、`panels.tsx` 中段若干行。
5. **依赖清单侧的 7 项未核对**（来自 `parts/ui-backend-deps.md`）：
   - `session.prompt` 的调用点与 body schema；
   - `api.shell.output` 的确切路由（`client.ts` 1911 vs 1923 二选一未决）；
   - `session.view` 的调用方；
   - **提问（question）请求的事件来源**——`packages/app/src/session/requests/session-question-dock.tsx` 存在，但 `packages/client/src/solid/data.ts` 内**没有** `question` 分支，其数据来源未定；
   - 各 request/response 的 body schema；
   - `packages/server/src` 侧 handler 实现（该表改引 `protocol/src/groups` + 生成客户端，未逐一核服务端实现）；
   - **一切 2.0.14 的实际行为**——本轮未运行任何东西，全部 `未核对`。
6. **终端面板的最终位置有一处需产品决策，不属「未核对」但需确认**：`terminal/renderer.tsx:146` 为 `region:"dock"`，但 `screen.tsx:496` 存在 `data-slot="session-side-terminal-region"`，且 `screen-layout.ts:21` 依据 `settings.general.terminalPlacement()` 允许 `"side"`。即**终端默认在 dock，但用户可切到与右栏同列**。托管时该开关需一并带过去。

---

## 7. 一句话结论

上一轮审计的**结论方向大体正确，但证据链大面积失效**：`/btw`、`command.workspace.toggle`、右栏 tab 集合、「6 个设置子页」、终端入口路径等 5 类判定被本轮直接证伪或归因错误；而 **13 条「自造」判定在正确基线上 13/13 全部存活**。真正的头号风险不是某个 UI 细节，而是 **`origin/v2` 把 GUI 功能整体搬进了编译期依赖 `@opencode/gui-extensions`**——这既让多条「上游没有」变成误判，也意味着**托管官方 UI 时六个侧栏面板会随之自动获得**，而**我方 2.0.14 后端与 UI 2.0.22 之间 8 个版本的落差**才是真正需要先验证的阻塞点。
