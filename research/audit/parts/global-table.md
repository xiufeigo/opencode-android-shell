# GLOBAL_PARITY_GAP 全局对位表 —— 基于 `origin/v2` 基线的纠正复核

> **本文是对 `research/audit/GLOBAL_PARITY_GAP.md` 第 3 节（自造清单）与第 4 节（缺失清单）的复核纠正件。**
>
> - 复核基线：`origin/v2` = `ecfa884ff82e9116e475b71a0c99383b8438d710`（2026-10-04 18:21:24 +1000，`feat(session-ui): show model variant in message meta (#53112)`），较原审计所用快照 `702a73d`（2026-09-20）领先 526 提交。
> - 复核方式：全部经 `_upstream` 工作树**只读**读取 git 对象（`git show` / `git grep` / `git cat-file -e` / `git ls-tree`），未执行任何 `checkout` / `pull` / `switch` / `reset`，`_upstream` 仍停留在 `702a73d`。
> - 实际条目数：**自造 13 条 + 缺失 26 条 = 39 行**，与原文标注一致。
> - 判定词表：`成立` = 原判定在 `origin/v2` 依然为真；`不成立` = 已被 `origin/v2` 证伪；`变化` = 结论方向未变但所指实现/证据已迁移或改变。
> - 未执行：gradle、装机、起服务、设备操作、任何工作区写入（除本文件）。

---

## 1. 复核结果总表

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

## 2. 因基线过期而失效的判定（本次复核的头条结论）

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

## 3. 复核方法与未执行项

- 全部结论来自 `_upstream` 只读 git 对象读取；`_upstream` 工作树仍停留在 `702a73d`，未发生任何变异。
- `git grep` 全部限定 pathspec 于 `packages/*/src`，未做全树 grep。
- **未执行**：gradle、装机、起服务、设备操作、任何写操作（`write` 仅本文件一次）、任何对既有审计文档的修改。
- **未核对 0 项**：39 条判定全部取得 `origin/v2` 一手证据，无凭旧文档转述的判定。
- **本表不覆盖**：原文 §5.2 的 26 项常规未核对项与 §1/§2 的表面清单——本次范围仅限 §3 自造与 §4 缺失两节的逐条纠正。若需同法复核 §5，请另行指派。
