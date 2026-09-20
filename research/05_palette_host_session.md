# 05 · 会话路由内「命令面板宿主」取证 + 两个次要项核实

> **取证对象**：`github.com/anomalyco/opencode` 分支 **`dev`**，本轮锁定 revision
> **`9a53565790ddf7d2fba1fb870c1be727deb8c6a9`**（`research/_raw/opencode-src` 内 `git fetch origin dev --filter=blob:none --depth=1` 后 FETCH_HEAD；本地已建 ref `dev`）。
> **取证方式**：blobless 浅克隆离线查（`git ls-tree dev` / `git show dev:<path>` / `git grep <pattern> dev -- packages/app/src`），
> 全部结论**逐行读过源码**；同时用本工作区 vendored 产物 `app/src/main/assets/opencode-app/assets/` 做**交叉验证**（见 §3）。
> **上游文档链接**：`05` 只补 `02_ui_screens.md` §6.7 的缺口；编号沿用既有体系。

---

## 0. 一句话结论

> **上一轮的推测被否定了一半。** 会话路由里的命令面板**没有任何"会话页面组件"作为 JSX 宿主**；它是通过 `dialog.show()` 推进**全局 dialog 栈**的，
> 而 `DialogCommandPaletteV2` 的**真正渲染宿主是"多态外壳"`DialogSelectFile`**（`packages/app/src/components/dialog-select-file.tsx:30-44`，命中分支在 `:35-36`）。
> 会话路由（`packages/app/src/pages/session.tsx:1147-1154`）只做了一件事：**把 `command.palette` 重定向到 `file.open`**。
> `packages/app/src/pages/layout.tsx` 与 `layout-new.tsx` **完全不含 palette**（0 命中）——上一轮猜的"layout.tsx 宿主"不存在。

---

## 1. 【主问题】会话路由内 `DialogCommandPaletteV2` 的挂载宿主

### 1.1 完整调用链（5 跳，全部带行号）

| # | 位置 | 做了什么 |
|---|---|---|
| 1 | `packages/app/src/context/command.tsx:397-424` | `document` capture 阶段 keydown：`isPalette = palette().has(sig)` → `preventDefault+stopPropagation` → `showPalette()` |
| 2 | `packages/app/src/context/command.tsx:393-395` | `showPalette = () => run(PALETTE_ID, "palette")`；`PALETTE_ID="command.palette"`（:13）、`DEFAULT_PALETTE_KEYBIND="mod+k,mod+shift+p"`（:14，导出）、`palette()` memo 在 :351-355 |
| 3 | `packages/app/src/pages/session.tsx:1147-1154` | 会话路由**重注册** `command.palette`（注册 key = `"session-palette"`，`hidden:true`），`onSelect` **不打开 palette，而是** `command.trigger("file.open","palette")` |
| 4 | `packages/app/src/pages/session/use-session-commands.tsx:517-524`（注册）/ `:263-268`（`openFile`） | `file.open` 的 `onSelect = openFile` → 动态 import + `dialog.show(() => <x.DialogSelectFile onOpenFile={showAllFiles} />)`，**不传 `mode`** |
| 5 | `packages/app/src/components/dialog-select-file.tsx:30-44` | `DialogSelectFile` 按 `mode` + `newLayoutDesigns()` 三态分派；命中 `:35-36` → **`<DialogCommandPaletteV2/>`** |

**第 3 跳源码逐字**（`packages/app/src/pages/session.tsx:1147-1154`）：

```tsx
  command.register("session-palette", () => [
    {
      id: "command.palette",
      title: language.t("command.palette"),
      hidden: true,
      onSelect: () => command.trigger("file.open", "palette"),
    },
  ])
```

> 这 8 行位于 `export default function Page()` 内部（`Page()` 起于 `:353`；`:353-1300+` 之间无任何顶层函数/大括号闭合，
> 已用 `Select-String "^(function|export function|export default function|\}|const .* = \()"` 穷举验证）。
> `Page` 由 `SessionPage`（`:147-153`）包裹 → 由 `app.tsx:635 <Route path="/session/:id?" component={SessionRoute} />` 渲染。

**第 5 跳源码逐字**（`packages/app/src/components/dialog-select-file.tsx:30-44`）：

```tsx
export function DialogSelectFile(props: { mode?: DialogSelectFileMode; onOpenFile?: (path: string) => void }) {
  const platform = usePlatform()
  const settings = useSettings()
  const filesOnly = () => props.mode === "files"

  if (!filesOnly() && settings.general.newLayoutDesigns())                   // ← :35
    return <DialogCommandPaletteV2 onOpenFile={props.onOpenFile} />          // ← :36  ★真正的宿主 JSX

  if (filesOnly() && platform.platform === "desktop" && settings.general.newLayoutDesigns())
    return <DialogSelectFileDesktopV2 onOpenFile={props.onOpenFile} />       // ← :40

  return <DialogSelectFileLegacy filesOnly={filesOnly} onOpenFile={props.onOpenFile} />   // ← :43
}
```

第 4 跳源码逐字（`packages/app/src/pages/session/use-session-commands.tsx:263-268`）：

```tsx
  const openFile = () => {
    void openDialog(
      () => import("@/components/dialog-select-file"),
      (x) => dialog.show(() => <x.DialogSelectFile onOpenFile={showAllFiles} />),
    )
  }
```

### 1.2 "谁把它挂到 DOM 上"—— host 链条

```
app.tsx:399-428  AppBaseProviders
  └─ :417  <DialogProvider>                      ← 全局 dialog 宿主（包裹 props.children）
       └─ packages/ui/src/context/dialog.tsx:158-168
            <div data-component="dialog-stack">   ← 栈层都渲染在这里
              └─ :84-121 mount() → :98 <Kobalte.Portal> :99 <Kobalte.Overlay data-component="dialog-overlay">
                   └─ :104-117 <div data-dialog-layer={layer} style="position:fixed;inset:0;display:flex;align-items:center;justify-content:center;pointer-events:none">
                        └─ {element()}          ← = <DialogSelectFile/> → <DialogCommandPaletteV2/>
```

- **答案**：**不是页面组件挂的**，而是 `useDialog().show()`（`packages/ui/src/context/dialog.tsx:185-188`，内部 `ctx.show` 会**清空整个栈**再 `mount(..., layer 0)`，`:139-148`）。
- 会话页、Home 页、new-session 页三者**都是"把组件实例推进 dialog 栈"**，没有一个是"在 JSX 树里写 `<DialogCommandPaletteV2/>`"。
- z-index：`50 + layer*10`（`:80`）。
- 关闭：`Escape` 由 `window` capture 监听器统一处理（`:65-76`）→ `close()`，延迟 **100ms** 才真正 `dispose()`（`:57-62`）。
- `dialog.active` 非空时 `handleKeyDown` 直接 return（`command.tsx:398`）→ **palette 打开后快捷键不再重复触发**。

### 1.3 关键副产物修正（请更新 `02_ui_screens.md` §6.0）

> `02` §6.0 写「Ctrl+P（`file.open`）打开 **legacy** `DialogSelectFile`」——**这句在 v2 布局下已经不成立**：

- 会话路由内 **Ctrl+P / Ctrl+K / Ctrl+Shift+P 三者最终打开的是同一个 `DialogCommandPaletteV2`**，因为 `openFile()`（use-session-commands.tsx:263-268）**不传 `mode`**，落到 `filesOnly() === false`。
- 会话头部搜索框 `packages/app/src/components/session/session-header.tsx:300`（`onClick={() => command.trigger("file.open")}`）**同样**落到 `DialogCommandPaletteV2`。
- 只有**传了 `mode="files"`** 的入口才走别的分支：`packages/app/src/pages/session/session-side-panel.tsx:455-459`（右侧面板文件树的 `+` 按钮）→ 桌面 `DialogSelectFileDesktopV2`（即 `DialogSelectDirectoryV2`）/ 非桌面 legacy。
- 真·legacy `DialogSelectFileLegacy`（`:67-189`，用 `@opencode-ai/ui/list` + `Keybind` 的旧样式）**只在 `settings.general.newLayoutDesigns() === false` 时可达**。
- 默认值：`packages/app/src/context/settings.tsx:61 export const newLayoutDesignsDefault = true`；`:263-278` 的 `newLayoutDesigns` memo 在 v2 布局（`layoutUpgrade()` / `oldInterfaceRetired()`）下恒为 `true`。**Android 上必须按 v2 分支实现。**

### 1.4 注册覆盖语义（为什么"最后注册者胜出"）

`packages/app/src/context/command.tsx`：

| 行 | 逻辑 |
|---|---|
| `:117-119` | `addCommandRegistration` = `[entry, ...registrations]` → **前插（后注册者在数组头部）** |
| `:121-129` | `activeCommandRegistrations` 按注册 `key` 去重，**保留数组中最靠前者**（即同名 key 的最新注册） |
| `:289-308` | `registered()` 按 `opt.id` 去重，**保留第一个**（`console.warn "keeping first entry"`） |
| `:379-391` | `optionMap` / `run(id, source)` → `option?.onSelect?.(source)` |

→ 因此 Home / 会话 / new-session 三个注册 key（`home.palette` / `session-palette` / `new-session`）**互不冲突**，
而同一个 `id: "command.palette"` 由**最近挂载的路由页面**接管。**这是路由级 dispatch，不是全局配置。**

---

## 2. 【次要项 A】命令面板的**两处（实为三处）宿主**

`DialogCommandPaletteV2` 与 `DialogHomeCommandPaletteV2` **定义在同一个文件**：
`packages/app/src/components/dialog-command-palette-v2.tsx`
（`DialogCommandPaletteV2` :38-62；`DialogHomeCommandPaletteV2` :64-124；共用 `CommandPaletteView` :126+，外壳 `:191 <Dialog class="command-palette-v2" size="large">` / `:192 <DialogBody class="command-palette-v2-body">`）。

| 路由 | 注册处 | 注册 key | `onSelect` 干什么 | `Dialog` 里实际挂的组件 |
|---|---|---|---|---|
| **Home `/`** | `packages/app/src/pages/home/home-sessions-controller.tsx:134-164` | `home.palette` | 取 `home.server.focused()` + `focusedContext()`，**动态 import** `@/components/dialog-command-palette-v2`，`dialog.show(() => <DialogHomeCommandPaletteV2 server onSelectSession/>)` | **`DialogHomeCommandPaletteV2`**（空查询只出命令、无文件类；placeholder = `palette.search.placeholder.home`） |
| **会话 `/:dir/session/:id`** | `packages/app/src/pages/session.tsx:1147` | `session-palette` | `command.trigger("file.open","palette")` → `dialog.show(<DialogSelectFile/>)` | **`DialogCommandPaletteV2`**（经 `dialog-select-file.tsx:36`） |
| **new-session `/new-session`** | `packages/app/src/pages/new-session/use-new-session-commands.tsx:18-27` | `new-session` | 动态 import + `dialog.show(() => <DialogSelectFile />)`（**同样不传 mode**） | **`DialogCommandPaletteV2`**（同一分支） |

Home 侧源码逐字（`packages/app/src/pages/home/home-sessions-controller.tsx:134-164`）：

```tsx
  command.register("home.palette", () => [
    {
      id: "command.palette",
      title: language.t("command.palette"),
      hidden: true,
      onSelect: async () => {
        const conn = home.server.focused();       if (!conn) return
        const ctx  = home.server.focusedContext(); if (!ctx) return
        const { DialogHomeCommandPaletteV2 } = await import("@/components/dialog-command-palette-v2")
        void dialog.show(() => (
          <DialogHomeCommandPaletteV2
            server={conn}
            onSelectSession={(entry) => {
              if (!entry.sessionID || !entry.directory || !entry.server) return
              const sessionID = entry.sessionID, server = entry.server
              const directory = entry.project?.worktree ?? entry.directory
              ctx.projects.open(directory); ctx.projects.touch(directory)
              void startTransition(() => {
                const tab = tabs.addSessionTab({ server, sessionId: sessionID })
                tabs.select(tab)
              })
            }}
          />
        ))
      },
    },
  ])
```

> **判定**：`DialogHomeCommandPaletteV2` 与 `DialogCommandPaletteV2` 是**同一个 `CommandPaletteView` 的两种数据装配**，
> 差异仅在 `loadItems`（Home：`commandEntries().slice(0,5)`，无 file 类；会话：命令 + session + `file.searchFiles`）与 placeholder 文案。

---

## 3. vendored Android 产物的交叉验证（**结论一致**）

对 `app/src/main/assets/opencode-app/assets/` 内的 chunk 做字符串/片段比对：

| 证据 | vendored 内容 | 与 dev HEAD 的关系 |
|---|---|---|
| `index-CKBO9Mty.js`（2,766,233 B） | `g.register("session-palette",()=>[{id:"command.palette",title:a.t("command.palette"),hidden:!0,onSelect:()=>g.trigger("file.open","palette")}]);` | **与 `session.tsx:1147-1154` 逐字符一致** |
| `dialog-select-file-C8VXrdP3.js`（4,676 B） | `const g=()=>a.mode==="files"; return !g()&&u.general.newLayoutDesigns() ? n(A,{get onOpenFile(){return a.onOpenFile}}) : …`（`A` 来自 palette chunk） | **与 `dialog-select-file.tsx:30-44` 一致** |
| `dialog-command-palette-v2-DgYInv6l.js`（11,018 B） | `command-palette-v2` ×22、`-body`×1、`-search`×1、`-results`×1、`size:"large"`×1、`DialogHomeCommandPaletteV2`×1、`palette.search.placeholder.home`×1 | 与 `dialog-command-palette-v2.tsx` 一致 |
| `index-CKBO9Mty.js` @ offset ≈2,727,022 | `home.palette` 注册 + `r.show(()=>f(v,{server:p,onSelectSession:…}))` | 与 `home-sessions-controller.tsx:134-164` 一致 |
| `index-CKBO9Mty.js` 计数 | `session-palette`×1、`home.palette`×1、`command.palette`×8、`DialogSelectFile`×2、`dialog-stack`×1 | 与 dev 结构一致 |

→ **Android 壳内会话命令面板的宿主链与 dev HEAD 完全相同。**

---

## 4. 【次要项 B】会话内 tab 切换的实现

> 这里其实有**两套完全独立的 tab**，`02` 与上一轮容易混淆，务必分开：

### 4.1 应用级"会话标签页"（titlebar tab strip）—— `useTabs`

**文件**：`packages/app/src/context/tabs.tsx`（`TabsProvider` / `useTabs` 由 `createSimpleContext` 导出，`:53`）。

**数据结构**（`:17-51`）：

```ts
export type SessionTab = { type: "session"; server: ServerConnection.Key; sessionId: string }
export type DraftTab   = { type: "draft";   draftID: string; server: ServerConnection.Key; directory: string; worktree?: string }
export type Tab = SessionTab | DraftTab
export type TabInfo = { title?: string; directory?: string }

export const tabKey  = (tab: Tab) => tab.type === "draft" ? `draft:${tab.draftID}` : `${tab.server}\n${tabHref(tab)}`
export const tabHref = (tab: Tab) => tab.type === "draft" ? `/new-session?draftId=${encodeURIComponent(tab.draftID)}` : sessionHref(tab.server, tab.sessionId)
```

**store**：就是一个 `Tab[]`（顺序 = 标签栏顺序），`persisted(Persist.window("tabs"), createStore<Tab[]>([]))`（`:60-66`）；
另有三个旁路 persisted store：`tabs.recent`（`{key}`，只存一个"最近活动 key"，`:67`）、`tabs.info`（`Record<key,TabInfo>`，`:68`）、`tabs.closed`（关闭栈，`:69`）。

> ⚠️ **没有 `activeIndex` 之类的字段。当前活动 tab 不是 store 里的状态，而是 `URL 路由 + recent.key` 推导出来的。**

**切换逻辑**（核心 3 个函数）：

```ts
const navigateTab = (tab: Tab) => {                    // :149-153
  const href = tabHref(tab)
  setRecentKey(tabKey(tab))
  navigate(href)
}
// actions:
select: navigateTab,                                   // :357  ← 切换 = 导航
remember(tab) { const key = tabKey(tab); if (recentKey() !== key) setRecentKey(key) },  // :358-361
toggleHome(input: { home: boolean; current?: Tab }) {  // :362-374
  if (input.home) { const tab = store.find(t => tabKey(t) === recentKey()); if (tab) navigateTab(tab); return }
  if (input.current) { setRecentKey(tabKey(input.current)); navigate("/"); return }
  navigate("/")
},
```

其余 action：`addSessionTab`（`:180-193`，去重后 `push`）、`reorder(keys)`（`:194-203`）、`newDraft`（`:209-222`）、
`updateDraft`（`:223-230`）、`promoteDraft`（`:231-248`，draft → session 原地替换 + 导航原子化）、
`closeTab(index)`（`:253-258`，先 `pushClosedTab` 记入关闭栈）、`reopenClosedTab`（`:259-279`）、
`removeSessionTab`/`removeServer`/`removeSessions`（`:280-349`，会 `navigate` 到相邻 tab 或 `/`）。

**UI 触发**：

- `packages/app/src/components/titlebar-tab-strip.tsx:336-375`：`<For each={props.tabs}>`，`active={() => props.currentTab() === tab}`；
  键盘 `selectAdjacentTab(±1)`（`:250-255`，配合 `adjacentTabKey` / `mergeVisibleTabOrder`，来自 `./titlebar-tab-order`）。
- `packages/app/src/components/titlebar.tsx:238`：`const currentTab = () => matchRoute(layout.route())`；
  `:403-406` `onNavigate={(tab, el) => { tabs.select(tab); el?.scrollIntoView({behavior:"instant"}) }}`；
  `:264-284` `openNewTab`（会话内 → `tabs.newDraft`）；`:314` `toggleHome`；`:398-412` `<TitlebarTabStrip/>`。
- `app.tsx:648-666` `NewLayoutLegacySessionRedirect` 用 `tabs.store.filter(t => t.type === "session")` 做旧 URL → 新 URL 迁移。
- `app.tsx:654 <Show when={tabs.ready()}>` —— **tabs 持久化就绪前不解析路由**。

### 4.2 会话**内面板**的 tab（Review / Context / 文件）—— `createSessionTabs`

**文件**：`packages/app/src/pages/session/helpers.ts:31-97`。

它**不持有** tab 列表，只是从外部 store **派生**：

```ts
export const createSessionTabs = (input: TabsInput) => {
  const contextOpen  = createMemo(() => input.tabs().active() === "context" || input.tabs().all().includes("context"))
  const openFileOpen = createMemo(() => fileBrowser() && (input.tabs().active() === SESSION_OPEN_FILE_TAB || input.tabs().all().includes(SESSION_OPEN_FILE_TAB)))
  const panelTabs    = createMemo(...)   // 去掉 "context"/"review"，按 normalizeTab 去重
  const openedTabs   = createMemo(() => panelTabs().filter(t => t !== SESSION_OPEN_FILE_TAB))
  const activeTab    = createMemo(() => {        // :62-74  ← 唯一的"当前 tab"判定
    const active = input.tabs().active()
    if (active === "context") return active
    if (active === SESSION_OPEN_FILE_TAB && openFileOpen()) return active
    if (active === "review" && review()) return active
    if (active && input.pathFromTab(active)) return input.normalizeTab(active)
    const first = openedTabs()[0]; if (first) return first
    if (contextOpen()) return "context"
    if (review() && hasReview()) return "review"
    return "empty"
  })
  const activeFileTab = createMemo(...)   // :75-79
  const closableTab   = createMemo(...)   // :80-86
  return { contextOpen, openFileOpen, panelTabs, openedTabs, activeTab, activeFileTab, closableTab }
}
```

**真正的存储 / 切换**在 `packages/app/src/context/layout.tsx:994-1030`：

| 行 | 内容 |
|---|---|
| `:994-1007` | `tabs(sessionKey)` → `store.sessionTabs[key]`（`SessionTabs`，默认 `{ all: [] }`） |
| `:1008` | `active: createMemo(() => tabs().active)` |
| `:1009` | `all: createMemo(() => tabs().all.filter(t => t !== "review"))` ← **`"review"` 是被过滤掉的伪 tab** |
| `:1011-1019` | `setActive(tab \| undefined)` → `setStore("sessionTabs", session, "active", next)` ← **切换就是改这一个字符串** |
| `:1021+` | `setAll` / `openSessionTab` / `closeSessionTab` / `previewSessionTab`（来自 `./layout-tabs`，`:23`） |

调用点：`packages/app/src/pages/session.tsx:538`（主）、`components/session-context-usage.tsx:59`、`components/prompt-input-v2.tsx:99`、
`pages/session/file-tabs.tsx:233` 与 `:518`、`components/command-palette.ts:113`。

---

## 5. 【次要项 C】`settings-v2` 弹窗 DOM（左导航 `TabsV2 vertical` 完整结构）

**入口**：`packages/app/src/components/settings-dialog.tsx:27-43` `useSettingsCommand()` → 注册 `settings.open`（`mod+comma`）
→ `useSettingsDialog()`（`:7-25`）→ `import("@/components/settings-v2")` → `dialog.show(<DialogSettings sessionID defaultValue/>)`。
**主文件**：`packages/app/src/components/settings-v2/dialog-settings-v2.tsx:44-113`。

### 5.1 逐层 DOM（dev HEAD）

```
packages/ui/src/v2/components/dialog-v2.tsx:84-115
div[data-component="dialog-v2"][data-variant="settings"][data-size="x-large"]
└─ div[data-slot="dialog-container"]
   └─ Kobalte.Content → div[data-slot="dialog-content"].settings-v2-dialog
      └─ div[data-slot="dialog-body"]                      ← settings-v2.css:12-15 → padding:0; overflow:hidden
         └─ TabsV2  ← tabs-v2.tsx:20-35
            div[data-component="tabs-v2"][data-variant="settings"][data-orientation="vertical"].settings-v2
            │    （data-* 三个属性是 TabsV2Root 写死的，:26-28）
            ├─ div[data-slot="tabs-v2-list"]                ← Kobalte.List（tabs-v2.tsx:37-49）
            │   └─ div.flex.flex-col.justify-between.h-full.w-full
            │       ├─ div.flex.flex-col.gap-3.w-full
            │       │   └─ div.flex.flex-col.gap-3
            │       │       ├─ div.flex.flex-col.gap-1.5
            │       │       │   ├─ div[data-slot="tabs-v2-section-title"]  = t("settings.section.desktop")
            │       │       │   └─ div.flex.flex-col.gap-1.5.w-full
            │       │       │       ├─ TabsV2.Trigger value="general"    <Icon name="sliders"/>  + t("settings.tab.general")
            │       │       │       └─ TabsV2.Trigger value="shortcuts"  <Icon name="keyboard"/> + t("settings.tab.shortcuts")
            │       │       └─ div.flex.flex-col.gap-1.5
            │       │           ├─ div[data-slot="tabs-v2-section-title"]  = t("settings.section.server")
            │       │           └─ div.flex.flex-col.gap-1.5.w-full
            │       │               ├─ Trigger value="servers"   <Icon name="server"/>
            │       │               ├─ Trigger value="providers" <Icon name="providers"/>
            │       │               └─ Trigger value="models"    <Icon name="models"/>
            │       └─ div.settings-v2-nav-footer
            │           ├─ span = t("app.name.desktop")
            │           └─ span = "v{platform.version}"
            ├─ div[data-slot="tabs-v2-content"].settings-v2-panel  value="general"   → <SettingsGeneralV2 sessionID/>
            ├─ … value="shortcuts" → <SettingsKeybinds v2/>
            ├─ … value="servers"   → <SettingsServersV2/>
            ├─ … value="providers" → <SettingsProvidersV2 directory onBack={showProviders}/>
            └─ … value="models"    → <SettingsModelsV2/>
```

**每个 `TabsV2.Trigger` 的 DOM**（`packages/ui/src/v2/components/tabs-v2.tsx:51-87`，dev 源码逐字）：

```
div[data-slot="tabs-v2-trigger-wrapper"][data-value=VALUE]     ← :55，另带 onMouseDown/onAuxClick 中键处理
└─ <Kobalte.Trigger data-slot="tabs-v2-trigger" data-value=VALUE>   ← :73
   └─ span.inline-flex.items-center.gap-2[data-slot="tabs-v2-trigger-content"]   ← :74
      ├─ {props.children}      ← <Icon name=…/>（注意：来自 @opencode-ai/ui/icon，**不是** v2 的 IconV2）
      └─ <Show subtext> → span[data-slot="tabs-v2-subtext"].ms-2.text-xs.text-text-weak   ← :76-82（settings 未用）
```

`TabsV2.SectionTitle` = 纯 `div[data-slot="tabs-v2-section-title"]`（`:139-141`）；`TabsV2.Content` = `Kobalte.Content` + `data-slot="tabs-v2-content"`（`:123-137`）。

### 5.2 关键尺寸（`packages/ui/src/v2/components/tabs-v2.css:178-224` + `settings-v2.css`）

| 元素 | 声明 |
|---|---|
| root `[data-component="tabs-v2"][data-variant="settings"]` | `height:100%`（settings-v2.css:4-6） |
| `List`（`.settings-v2[…] [data-slot="tabs-v2-list"]`） | `flex-direction:column; width:240px; min-width:200px; height:100%; padding:12px; gap:4px; overflow-y:auto; border-inline-end:1px solid var(--v2-border-border-base)`；背景 `--v2-background-bg-layer-01`（settings-v2.css:177-179） |
| SectionTitle | `width:100%; padding-inline-start:4px; color:var(--v2-text-text-muted); font-size:12px; font-weight:500; user-select:none` |
| trigger-wrapper | `width:100%; height:28px; border-radius:4px; color:var(--v2-text-text-muted)` |
| trigger | `width:100%; height:100%; justify-content:flex-start; gap:6px; padding:0 6px`；基础 `font-size:13px; font-weight:440; line-height:100%; letter-spacing:-0.04px` |
| hover / selected | `background-color: var(--v2-background-bg-layer-03); color: var(--v2-text-text-base)`（选中态靠 `:has([data-selected])` 命中） |
| `.settings-v2-panel`（settings-v2.css:17-24） | `display:flex; flex-direction:column; height:100%; overflow-y:auto; scrollbar-width:none; user-select:none`（内部 input/textarea/contenteditable 恢复 `user-select:text`） |
| `.settings-v2-nav-footer`（:190-203） | `display:flex; flex-direction:column; gap:8px; padding:4px 0 4px 4px`；`> span` = `11px/440/faint` |
| 窄屏 `@media (max-width:639px)`（settings-v2.css:181-188） | List `width:144px; min-width:144px; padding-inline:8px` |
| dialog `x-large`（dialog-v2.css:172-175） | `width: min(calc(100vw - 32px), 980px); height: min(calc(100vh - 92px), 600px)` |

### 5.3 ⚠️ vendored（Android）产物多出一整套 ≤639px 覆盖 —— **dev HEAD 没有**

`app/src/main/assets/opencode-app/assets/index-D_rl_FNw.css` 偏移 **477,609** 起的 `@media(max-width:639px)` 块中，除 dev 那条 `width:144px` 外还有：

```css
[data-component=dialog-v2][data-variant=settings] [data-slot=dialog-container]{
  width:min(calc(100vw - 16px),980px); height:min(calc(100dvh - 16px),720px)}
.settings-v2[data-component=tabs-v2][data-variant=settings][data-orientation=vertical]{flex-direction:column}
.settings-v2[data-component=tabs-v2][data-variant=settings][data-orientation=vertical] [data-slot=tabs-v2-list]{
  box-sizing:border-box; width:100%; min-width:0; height:auto; max-height:none; flex:0 0 auto;
  flex-direction:column; padding:8px; overflow-x:hidden; overflow-y:visible;
  border-inline-end:none; border-bottom:1px solid var(--v2-border-border-base)}
.settings-v2[data-component=tabs-v2][data-variant=settings][data-orientation=vertical] [data-slot=tabs-v2-list]>div{height:auto}
.settings-v2-nav-footer{display:none}
.settings-v2-tab-header{padding:20px 16px 16px}
.settings-v2-tab-body{padding:0 16px 24px; gap:24px}
[data-component=dialog-v2][data-variant=settings] [data-component=select-v2-root],
[data-component=dialog-v2][data-variant=settings] [data-component=button-v2]{width:100%; max-width:100%}
[data-slot=settings-v2-row-control]{width:100%; justify-content:flex-start}
[data-component=settings-v2-row],.settings-v2-provider-row,.settings-v2-servers-row{flex-direction:column; align-items:stretch; flex-wrap:wrap}
[data-slot=settings-v2-row-copy]{width:100%; max-width:100%}
[data-slot=settings-v2-row-title],[data-slot=settings-v2-row-description]{white-space:normal; overflow:visible; text-overflow:unset}
/* 另一处 @media(max-width:639px) @ ≈479,451 */
.settings-v2-panel .settings-v2-tab-header{position:static; z-index:auto; background:var(--v2-background-bg-base)}
.settings-v2-panel .settings-v2-tab-header:not(.settings-v2-tab-header--stacked):not(.settings-v2-servers-header){display:none}
.settings-v2[data-component=tabs-v2][data-variant=settings] .settings-v2-nav-footer{display:none}
[data-component=dialog-v2][data-variant=settings] [data-slot=dialog-content]{min-height:0; overflow:hidden}
.settings-v2[data-component=tabs-v2][data-variant=settings] [data-slot=tabs-v2-content].settings-v2-panel{height:0; flex:1 1 auto; min-height:0; overflow-y:auto}
```

> **含义（对 Android 复刻很关键）**：vendored 构建在**窄屏（≤639px，手机）**把 settings 的"左导航"整条变成
> **顶部竖排导航条 + 底部 1px 分隔线**（root `flex-direction:column`，list `width:100%`、`border-inline-end:none`、`border-bottom`），
> 并隐藏 `nav-footer`、压缩面板内边距。
> **dev HEAD 无这些规则**（`settings-v2.css` 全文 729 行只有 `:181-188` 一条 639px 规则）。
> 另：vendored CSS 中 `tabs-v2` 仅出现 **13 次**，全部是 `-list` / `-content` / 根节点，
> **完全没有 dev `tabs-v2.css` 的 `[data-slot="tabs-v2-trigger"]`、`[…]tabs-v2-trigger-wrapper"]` 基础样式**。
> → **vendored 产物的 `packages/ui/src/v2/components/tabs-v2.css` 与 dev HEAD 不是同一修订**（详见 §6 未确证项 2）。

---

## 6. 仍未确证 / 不臆造

| # | 未确证项 | 现状 |
|---|---|---|
| 1 | `Kobalte.Portal` 的**最终挂载节点** | dev 源码只写 `<Kobalte.Portal>`（`packages/ui/src/context/dialog.tsx:98`），仓库内**无 Kobalte 源码**（无 node_modules）。业界默认是 `document.body`，**本轮未逐字核实**，标为"默认 body"。`<div data-component="dialog-stack">`（同文件 :163）本身是 React 式占位容器，不是最终宿主。 |
| 2 | **vendored 产物对应的确切上游 commit** | 已知：关键注册字符串与 dev HEAD **逐字符一致**；但 vendored CSS 的 `tabs-v2.css` 缺 dev 的基础选择器、且多出整套 ≤639px settings 覆盖。**无法判定 vendored 是"较新上游"还是"较旧上游 + Android 定制"**（vendored 目录内无 git 元数据）。**建议后续用 `git log -p -- packages/ui/src/v2/components/tabs-v2.css` 拉一次带历史的浅克隆来定版。** |
| 3 | 那条 ≤639px 覆盖 **是否为 Android 定制** | 该块与已知 Android 定制（`data-android-webview`、`tabPlusCreatesSession`）无直接字符串关联；同块内还有多条 dev 中不存在的响应式覆盖 → 趋势指向"上游后续修订"。**未确证，勿当结论。** |
| 4 | `Kobalte.List` / `Kobalte.Trigger` 的**实际元素与属性** | dev 源码未显式指定 `as`，渲染元素（预期 `div[role=tablist]` / `button[role=tab]`，含 `aria-selected`、`data-selected`）由 Kobalte 决定。CSS 用 `:has([data-selected])` 选中，说明 `data-selected` 确实存在；**元素名未核实**。 |
| 5 | `pages/layout.tsx`(87KB) / `layout-new.tsx` 是否曾参与 palette | **已否定**：`git show dev:<path> \| Select-String palette` 在两个文件上均为 **0 命中**（`layout-new.tsx` 全文仅 49 行）。上一轮推测的这两个候选**都不成立**（`session.tsx` 命中但只是"注册重定向"，不是"JSX 宿主"）。 |
| 6 | Home 之外的第三处宿主是否还有第四个 | 已对 `packages/app/src` 做全量 `git grep`：`DialogCommandPaletteV2` 只出现在 `dialog-command-palette-v2.tsx`（定义）与 `dialog-select-file.tsx`（唯一使用）；`DialogHomeCommandPaletteV2` 只出现在 `home-sessions-controller.tsx`（唯一使用）。**无第四处。** |

---

## 7. 取证方法与复现命令

```powershell
cd research\_raw\opencode-src
git remote -v                                   # origin = https://github.com/anomalyco/opencode.git  (fetch=blob:none)
git fetch origin dev --filter=blob:none --depth=1
git fetch origin dev:refs/heads/dev --filter=blob:none --depth=1     # 建本地 ref dev
git rev-parse dev                               # 9a53565790ddf7d2fba1fb870c1be727deb8c6a9

# 离线查路径 / 取文件（blob 按需下载，不走 GitHub API）
git ls-tree -r --name-only dev -- packages/app/src/pages
git show dev:packages/app/src/pages/session.tsx | Select-String palette
git show dev:packages/app/src/components/dialog-select-file.tsx

# 全仓语义检索（限定子树，blob 按需下载；勿对全仓跑以免拉取过多）
git grep -n "DialogCommandPaletteV2\|DialogHomeCommandPaletteV2" dev -- packages/app/src
git grep -n "command.palette\|PALETTE_ID\|DEFAULT_PALETTE_KEYBIND" dev -- packages/app/src ":(exclude)packages/app/src/i18n"
```

交叉验证用的 vendored 文件：
`app/src/main/assets/opencode-app/assets/index-CKBO9Mty.js`（主 chunk）、
`.../dialog-select-file-C8VXrdP3.js`（4,676 B）、`.../dialog-command-palette-v2-DgYInv6l.js`（11,018 B）、
`.../index-D_rl_FNw.css`（491,838 B）。

---

## 8. 对 `02_ui_screens.md` 的修订建议（供父代理合并）

1. **§6.7 "未确证项" → 结案**：改为"宿主 = `dialog-select-file.tsx:36` 的 `DialogCommandPaletteV2`，经全局 dialog 栈渲染"。
2. **§6.0 表**：Ctrl+P 在 v2 布局下**不再**是 legacy 文件选择器；会话内 Ctrl+K / Ctrl+P / 头部搜索框**都**打开同一个 v2 palette。
3. **新增事实**：`command.palette` 是**路由级注册**（Home / session / new-session 三处，各自 `hidden:true`），
   `command.register` 的"+后注册者胜出"语义来自 `addCommandRegistration` 前插 + `registered()` 保留首项。
4. **§5 settings**：补 §5.1 的完整 DOM 与 §5.3 的 vendored ≤639px 覆盖（Android 实机是**顶部导航条**，不是左导航）。
5. **新增 tab 双体系澄清**：应用级 tab = `context/tabs.tsx`（URL 驱动，store 无 active 字段）；
   会话内面板 tab = `context/layout.tsx:994-1030` + `pages/session/helpers.ts:31`（`active` 是一个字符串）。
