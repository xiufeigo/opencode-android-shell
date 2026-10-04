# 窄屏下官方 UI「侧栏消失」核查（W2）

- **核查人**：一致性核查工程师 W2（mcode）
- **日期**：2026-10-04
- **硬期限**：2026-10-07 ✅ 提前完成
- **基线**：`origin/v2` = `ecfa884` = **v2.0.22**（`feat(session-ui): show model variant in message meta (#53112)`）
- **性质**：**纯只读调研**。未跑 gradle、未装机、未改任何生产代码、未碰设备。唯一写入为本文件（`research/web/**`）。

---

## 一、判定（先给结论）

# ✅ 判定：**上游设计**（我们与官方一致）

**窄屏下侧栏被隐藏，是 `origin/v2` 自己写的硬性响应式门控，不是我们的宿主挡的。**

上游不仅"隐藏"侧栏，它还**专门实现了一套窄屏替代 UI**（底部视图切换栏 + 溢出菜单 + 抽屉）。E6 观察到的"侧栏整体消失"与上游设计**完全吻合**。

> ⚠️ 但 E6 说的第二半句"**且没有抽屉**"需要单独跟进 —— 按上游设计，窄屏**应该**出现底部视图栏（Session / Review / Files / Terminal），抽屉在其溢出菜单里。见 §六「待跟进」。

---

## 二、核心证据：断点常量

### `origin/v2:packages/app/src/session/model.ts:29`

```ts
const isDesktop = createMediaQuery("(min-width: 768px)")
```

这是整个会话界面的唯一断点。E6 实测内视口 **412×915**，412 < 768 ⇒ `isDesktop() === false`。

---

## 三、侧栏被整块卸载（不是 CSS 隐藏，是根本不在 DOM）

### 3.1 `SideRegion` 组件整体被门控

**`origin/v2:packages/app/src/runtime/extension/side-region.tsx:54, 83-84`**

```tsx
 54:  const isDesktop = createMediaQuery("(min-width: 768px)")
...
 83:    <Show when={isDesktop() && !!params.id}>
 84:      <aside
 85:        id="review-panel"
```

侧栏根节点 `<aside id="review-panel">` 包在 `<Show when={isDesktop() && ...}>` 内 —— 窄屏下**根本不渲染**，不是 `display:none`，是 DOM 里没有。

### 3.2 连内部状态都被二次门控

**`origin/v2:packages/app/src/runtime/extension/side-region.tsx:55-59`**

```tsx
 55:  const tabsOpen = createMemo(() => isDesktop() && view().side.opened())
 56:  const tabsVisible = createMemo(() => tabsOpen() || !!props.present)
 57:  const fileOpen = createMemo(() => isDesktop() && props.fileTree)
 58:  const open = createMemo(() => tabsOpen() || fileOpen())
 59:  const visible = createMemo(() => tabsVisible() || fileOpen())
```

`tabsOpen` / `fileOpen` 全部乘了 `isDesktop()`。

### 3.3 布局层同步让路

**`origin/v2:packages/app/src/session/screen-layout.ts:19, 25-27, 85`**

```ts
 19:  const tabsOpen = createMemo(() => session.isDesktop() && view().side.opened() && !!session.identity.params.id)
...
 25:  const fileTreeOpen = createMemo(
 26:    () => session.isDesktop() && input.sidebar() && settings.visibility.fileTree() && layout.fileTree.opened(),
 27:  )
...
 85:  const sideRegionOpen = createMemo(() => tabsOpen() || fileTreeOpen())
```

**`origin/v2:packages/app/src/session/screen.tsx:111`**

```ts
111:  const sideVisible = createMemo(() => isDesktop() && screen.side.layout().visible)
```

### 3.4 顶栏的侧栏开关同样被门控

**`origin/v2:packages/app/src/session/screen.tsx:353, 357`**

```tsx
353:      <Show when={isDesktop() && messagesReady() && session.identity.params.id}>
...
357:            data-slot="session-review-toggle"
```

**`origin/v2:packages/app/src/session/header/session-header.tsx:5, 8`**

```tsx
 5:  const isDesktop = createMediaQuery("(min-width: 768px)")
...
 8:    <Show when={isDesktop() && props.visible}>
```

### 3.5 侧栏内的折叠按钮也在门控内

**`origin/v2:packages/app/src/runtime/extension/side-region.tsx:164-168`**（`SessionReviewV2SidebarToggle`）位于第 83 行那个 `<Show>` 内部 ⇒ 窄屏不渲染。

### 3.6 同一断点在全仓的其它用法（证明这是系统性的设计约定）

`git grep "min-width: 768px"` 在 `packages/app/src` + `packages/gui-extensions/src` 命中 14 处：

```
origin/v2:packages/app/src/home/route.tsx:14:  const mobile = createMediaQuery("(max-width: 767px)")
origin/v2:packages/app/src/runtime/extension/host-apis.tsx:83:  const desktop = createMediaQuery("(min-width: 768px)")
origin/v2:packages/app/src/runtime/extension/host-apis.tsx:368:  const desktop = createMediaQuery("(min-width: 768px)")
origin/v2:packages/app/src/runtime/extension/panels.tsx:184:  const desktop = createMediaQuery("(min-width: 768px)")
origin/v2:packages/app/src/runtime/extension/panels.tsx:402:  const isDesktop = createMediaQuery("(min-width: 768px)")
origin/v2:packages/app/src/runtime/extension/side-region.tsx:54:  const isDesktop = createMediaQuery("(min-width: 768px)")
origin/v2:packages/app/src/session/header/session-header.tsx:5:  const isDesktop = createMediaQuery("(min-width: 768px)")
origin/v2:packages/app/src/session/model.ts:29:  const isDesktop = createMediaQuery("(min-width: 768px)")
origin/v2:packages/app/src/session/timeline/virtualizer.tsx:89:  const isDesktop = createMediaQuery("(min-width: 768px)")
origin/v2:packages/app/src/shell/shell.tsx:19:  const mobile = createMediaQuery("(max-width: 767px)")
origin/v2:packages/app/src/shell/titlebar/titlebar.tsx:60:  const mobile = createMediaQuery("(max-width: 767px)")
```

---

## 四、上游的窄屏替代 UI（这是"设计"而非"缺陷"的关键）

### 4.1 窄屏挂载 `MobileViewTabs`

**`origin/v2:packages/app/src/session/screen.tsx:282-296`**

```tsx
282:      <Show when={!isDesktop() && !!session.identity.params.id}>
283:        <Show when={session.identity.sessionKey()} keyed>
284:          {(_key) => (
285:            <MobileViewTabs
286:              screen={props.screen}
287:              views={mobile}
288:              region={region}
289:              current={mobileView()}
290:              session={props.view()}
291:              sidebar={sidebar}
292:              onSelect={selectMobile}
293:            />
294:          )}
295:        </Show>
296:      </Show>
```

**`origin/v2:packages/app/src/session/screen.tsx:24`** —— `import { createMobileViews, MobileViewTabs } from "@/runtime/extension/mobile"`

### 4.2 底部视图栏：会话 + 各面板视图 + 溢出菜单

**`origin/v2:packages/app/src/runtime/extension/mobile.tsx:60`**

```tsx
 60: /** The narrow-screen view switcher: the conversation, each panel's view, and an overflow menu. */
```

**`origin/v2:packages/app/src/runtime/extension/mobile.tsx:91-94`**

```tsx
 91:    <div
 92:      class="relative flex shrink-0 items-center before:pointer-events-none ..."
 93:      data-slot="session-mobile-view-navigation"
 94:    >
```

**`origin/v2:packages/app/src/runtime/extension/mobile.tsx:96-104`** —— 第一个固定 tab 是"会话"：

```tsx
 96:        <Tabs.List aria-label={language.t("session.view.select")} class="!h-9 !gap-0 !px-0 before:!hidden">
 97:          <Tabs.Trigger
 98:            value="session"
...
103:            {language.t("session.tab.session")}
```

**`origin/v2:packages/app/src/runtime/extension/mobile.tsx:105-116`** —— 之后是各面板的窄屏 tab：

```tsx
105:          <For each={props.views.tabs()}>
106:            {(entry) => (
107:              <Tabs.Trigger
108:                value={entry.key}
...
113:                {entry.mobile.title}
```

**`origin/v2:packages/app/src/runtime/extension/mobile.tsx:52-53`** —— tab / menu+drawer 的分流：

```tsx
 52:    tabs: createMemo(() => sorted(["tab"])),
 53:    menu: createMemo(() => sorted(["menu", "drawer"])),
```

### 4.3 溢出菜单（汉堡按钮）→ 抽屉

**`origin/v2:packages/app/src/runtime/extension/mobile.tsx:127-138`**

```tsx
127:        <Menu.Trigger
128:          as={IconButton}
...
132:          icon={<Icon name="menu" />}
133:          variant="ghost-muted"
134:          size="normal"
135:          class="mx-1.5 shrink-0"
...
137:          aria-label={language.t("common.moreOptions")}
138:        />
```

**`origin/v2:packages/app/src/runtime/extension/mobile.tsx:147-157`** —— 菜单项点击：非 `menu` 的（即 `drawer`）改为挂载抽屉：

```tsx
147:            <For each={props.views.menu()}>
148:              {(entry) => (
149:                <Menu.Item
150:                  onSelect={() => {
151:                    if (entry.mobile.kind === "menu") return props.onSelect(entry.key)
152:                    setStore({ pending: entry.key, menu: false })
153:                  }}
```

**`origin/v2:packages/app/src/runtime/extension/mobile.tsx:162-188`** —— 抽屉实际渲染：

```tsx
162:      <Show when={store.loaded}>
...
164:          <MobilePanelDrawer
165:            title={drawer()?.mobile.title ?? ""}
166:            open={!!store.drawer}
...
174:                  <DrawerContext.Provider value={{ close: () => setStore("drawer", undefined) }}>
175:                    <MobilePanel
```

**`origin/v2:packages/app/src/shell/mobile-panel-drawer.tsx:7-42`** —— 抽屉组件本体（`MobileDrawer` + 标题 + 关闭按钮 + 内容槽）：

```tsx
  7: export function MobilePanelDrawer(
...
 17:    <MobileDrawer
 18:      open={props.open}
 19:      onOpenChange={props.onOpenChange}
 20:      returnFocus={props.returnFocus}
...
 24:      <MobileDrawerContent>
 25:        <div data-slot="mobile-panel" data-corvu-no-drag>
 26:          <div data-slot="mobile-panel-header">
 27:            <MobileDrawerLabel>{props.title}</MobileDrawerLabel>
 28:            <MobileDrawerClose
...
 37:          <div data-slot="mobile-panel-content">{props.children}</div>
```

### 4.4 内置面板确实声明了窄屏视图（逐一列举）

| 面板 | 位置（`origin/v2`） | `kind` | 窄屏呈现 |
|---|---|---|---|
| Review（评审） | `packages/gui-extensions/src/review/renderer.tsx:147` | `"tab"` | 底部 tab |
| Files（文件） | `packages/gui-extensions/src/file/renderer.tsx:209` | `"tab"` | 底部 tab |
| Terminal（终端） | `packages/gui-extensions/src/terminal/renderer.tsx:147` | `"tab"` | 底部 tab |
| Details（详情） | `packages/gui-extensions/src/details/renderer.tsx:55` | `"drawer"` | 溢出菜单 → 抽屉 |
| Context（上下文） | `packages/gui-extensions/src/context/renderer.tsx:43` | `"menu"` | 溢出菜单 → 切视图 |

原文示例 —— **`origin/v2:packages/gui-extensions/src/review/renderer.tsx:147`**：

```tsx
147:    mobile: {
148:      get title() {
149:        return ctx.plural("mobile.title", 0)
150:      },
151:      order: 10,
152:      kind: "tab",
153:    },
```

原文 —— **`origin/v2:packages/gui-extensions/src/details/renderer.tsx:51-60`**：

```tsx
 51:  // The narrow-screen details drawer, offered for root sessions of a project.
 52:  const panel: Panel = {
 53:    id: "main",
 54:    region: "side",
 55:    mobile: {
 56:      get title() {
 57:        return ctx.t("title")
 58:      },
 59:      order: 20,
 60:      kind: "drawer",
```

原文 —— **`origin/v2:packages/gui-extensions/src/context/renderer.tsx:43`**：

```tsx
 43:      mobile: { title: ctx.t("mobile.title"), order: 10, kind: "menu" },
```

### 4.5 SDK 层面的契约（"narrow-screen view" 是正式 API）

**`origin/v2:packages/gui-extensions/src/sdk/points.ts:263-267`**

```ts
263:  /**
264:   * A narrow-screen view of this panel. The render sees `usePanel().placement() === "mobile"` and receives a tab with
265:   * the panel's `id` and the view's `title`.
266:   */
267:  readonly mobile?: MobileView
```

**`origin/v2:packages/gui-extensions/src/sdk/points.ts:347`**

```ts
347:   * - `mobile`: narrow screens only.
```

**`origin/v2:packages/gui-extensions/src/sdk/solid.ts:92-94`**

```ts
 92:   * - `mobile`: a narrow-screen view (`Panel.mobile`), or a dock embedded in one.
 94:  readonly placement: Accessor<"side" | "bottom" | "mobile">
```

### 4.6 上游注释里写明的设计意图（最直接的"这是设计"的证据）

**`origin/v2:packages/gui-extensions/src/review/contract.ts:23`**

```ts
 23:  /** Shows the changes: opens the side region, or on narrow screens switches to the Changes view. */
```

**`origin/v2:packages/gui-extensions/src/review/mobile.tsx:16`**

```tsx
 16: /** The narrow-screen review: every changed file as one scrollable list. */
```

**`origin/v2:packages/gui-extensions/src/file/renderer.tsx:418-419`**

```tsx
418:          // A tapped link switches the narrow-screen view; the side region still opens for when the window is wide.
419:          if (layout.narrow() && !layout.side.opened(session)) layout.side.toggle(session)
```

**`origin/v2:packages/app/src/runtime/extension/panels.tsx:183-184`**

```tsx
183:  // Narrow screens never select a transient tab: a stored one falls back like a missing tab.
184:  const desktop = createMediaQuery("(min-width: 768px)")
```

---

## 五、我们的宿主有没有挡住？—— 没有

### 5.1 WebView 视口设置

**`webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt:392-396`**

```kotlin
392:    useWideViewPort = false
393:    loadWithOverviewMode = false
...
396:    displayZoomControls = false
```

**关键判断**：`useWideViewPort = false` 表示 WebView **不采纳** `<meta viewport>` 的 `width=` 指令，布局视口取物理宽度。这**不会把视口撑宽到 768px 以上**。

E6 实测内视口为 **412×915** ⇒ `matchMedia("(min-width: 768px)").matches === false` ⇒ **上游自己的门控生效**。

> 我们的宿主既没有加宽视口，也没有强制缩放。它只是"如实"呈现了 412 CSS px 的窄屏宽度，然后**上游按自己的设计**隐藏了侧栏。

### 5.2 我们注入的 JS 不含任何布局篡改

**`webapp/src/main/assets/www/oc-host.js`**（12,205 字节）中对
`display:none` / `visibility:hidden` / `aside` / `review-panel` / `session-mobile` / `matchMedia` / `innerWidth` / `width`
的检索 —— **零命中**。

唯一的样式注入是 IME 相关的（`oc-host.js:46-49, 65-66`）：

```js
 46:  var IME_STYLE_ID = "oc-ime-lift";
 48:  function installImeLiftStyle() {
 49:    if (document.getElementById(IME_STYLE_ID)) return;
 65:    var st = document.createElement("style");
 66:    st.id = IME_STYLE_ID;
```

**结论：我们的宿主没有任何一行代码会导致侧栏消失。**

### 5.3 ⚠️ 附带发现：仓库里的 `assets/www/index.html` 仍是占位页

**`webapp/src/main/assets/www/index.html`** 是一张 W1 诊断占位页，原文：

> 占位页，非应用前端。真正前端由 `@opencode-ai/app` dist 产出拷贝进 `assets/www/`，把这个占位页删掉即可

页面里是 origin / AssetLoader / insets / IME 等自检项，**没有 opencode 应用本体**。

⇒ **E6 那次"跑官方 UI"必然是通过别的方式加载上游前端**（例如 LoopbackProxy 指向上游服务，或临时拷入 dist），**不是**用仓库里这个 `index.html`。这需要跟 E6 对齐（见 §七）。

---

## 六、窄屏下打开侧栏的入口是什么？是否仍可见？

### 6.1 侧栏本体在窄屏**没有**入口（符合设计）

| 入口 | 位置（`origin/v2`） | 窄屏是否可见 | 原因 |
|---|---|---|---|
| 侧栏内折叠钮 `SessionReviewV2SidebarToggle` | `side-region.tsx:164-168` | ❌ 不可见 | 在 `side-region.tsx:83` 的 `<Show when={isDesktop() && ...}>` 内 |
| 顶栏侧栏开关 `data-slot="session-review-toggle"` | `screen.tsx:353-357` | ❌ 不可见 | `<Show when={isDesktop() && messagesReady() && ...}>` |
| 顶栏占位 `SessionHeaderSpacer` | `session-header.tsx:8` | ❌ 不可见 | `<Show when={isDesktop() && props.visible}>` |
| 键盘/命令 `review.toggle`（`mod+shift+r`） | `use-session-commands.tsx:367-371` | ⚠️ 命令存在但**窄屏无视觉效果** | 见 §6.2 |

**没有任何"窄屏抽屉式侧栏"来替代侧栏位置。** 上游的替代方案不是抽屉侧栏，而是**底部视图栏**（§4.2）。

### 6.2 `review.toggle` 在窄屏是"无效果"（不是被我们挡，是上游自己在窄屏让它无效）

**`origin/v2:packages/app/src/session/commands/use-session-commands.tsx:367-371`**

```tsx
367:      id: "review.toggle",
368:      title: language.t("command.review.toggle"),
369:      keybind: "mod+shift+r",
370:      onSelect: () => actions.session.layout.view().side.toggle(),
```

命令会执行 `side.toggle()`，但 `screen-layout.ts:19` 的 `tabsOpen` 乘了 `session.isDesktop()`，因此窄屏下**状态变了、界面不变**。

### 6.3 窄屏下侧栏内容的官方可达路径

1. **底部视图栏**（`mobile.tsx:91-94` 的 `data-slot="session-mobile-view-navigation"`）
   → `会话`（会话）/ `Review`（评审）/ `Files`（文件）/ `Terminal`（终端）
2. **溢出"更多"按钮**（`mobile.tsx:132` `icon={<Icon name="menu" />}`，`aria-label` = `common.moreOptions`）
   → `Context`（切视图）/ `Details`（开抽屉 `MobilePanelDrawer`）

---

## 七、命令面板在窄屏下是否仍可打开？（相关问题）

### 7.1 命令面板本身：**不按断点门控，窄屏可打开**

对 `palette.ts` / `command.tsx` / `dialog.tsx` 检索 `mobile` / `desktop` / `768` —— **零命中**。命令面板走 `dialog.show()`，没有断点限制。

**`origin/v2:packages/app/src/shell/commands/command.tsx`**

```ts
const PALETTE_ID = "command.palette"
export const DEFAULT_PALETTE_KEYBIND = "mod+k,mod+shift+p"
```

**`origin/v2:packages/app/src/shell/commands/dialog.tsx:1`**

```ts
1: export function DialogCommandPalette(props: { onOpenFile?: (path: string) => void }) {
```

### 7.2 但它**不是**官方在窄屏下访问侧栏的途径

- 面板 `review.toggle` 进了命令面板，但窄屏下无视觉结果（§6.2）。
- 真正在窄屏有效的文件入口是另一个对话框 —— **`file.open`**：

**`origin/v2:packages/app/src/session/commands/use-session-commands.tsx:186-191`**

```tsx
186:  const openFile = () => {
187:    void openDialog(
188:      () => import("@/shell/commands/dialog"),
189:      (x) => dialog.show(() => <x.DialogCommandPalette />),
190:    )
191:  }
```

**`origin/v2:packages/app/src/session/commands/use-session-commands.tsx:326-333`**

```tsx
326:      fileCommand({
327:        id: "file.open",
328:        title: language.t("command.file.open"),
329:        description: language.t("palette.search.placeholder"),
330:        keybind: "mod+p",
331:        slash: "open",
332:        onSelect: openFile,
333:      }),
```

⇒ `mod+p` / 斜杠 `/open` 可在窄屏唤起**文件搜索对话框**（`dialog.tsx:16-18` 走 `palette.file.searchFiles`）。这是窄屏下"能拿到文件内容"的一条真实途径，但它是**独立的搜索对话框**，不是侧栏。

### 7.3 小结

| 问题 | 答案 | 依据 |
|---|---|---|
| 命令面板窄屏能打开吗？ | ✅ **能**（无断点门控） | `command.tsx` / `dialog.tsx` 检索零命中 |
| 它是官方窄屏访问侧栏的途径吗？ | ❌ **不是** | `use-session-commands.tsx:370` + `screen-layout.ts:19` |
| 官方窄屏访问侧栏内容的途径是什么？ | **底部视图栏 + 溢出菜单 → 抽屉** | `mobile.tsx:91-94, 127-138, 162-188` |

---

## 八、交叉验证

### 8.1 证据类型说明

| 类别 | 状态 | 说明 |
|---|---|---|
| **源码证据** | ✅ **权威（结论以此为准）** | 全部行号引自 `origin/v2` = `ecfa884` = v2.0.22，经 `git -C _upstream show/grep` 读取 git 对象，**未切换工作区、未做全树递归 grep** |
| **运行证据** | ⚠️ **仅佐证（存在版本落差）** | 见 §8.2 |

### 8.2 运行证据的完整过程与**已知局限**

**我原本被要求起一个 opencode v2 服务，但主动放弃了这个方案 —— 理由如下：**

```
PS> opencode --version
1.18.33
```

**本机 PATH 上的 `opencode` 是 1.18.33（v1 线），不是 v2.0.22。** 用它起服务会得到**错误版本**的前端产物，作为 v2 证据**无效**。故未采用。

**实际采用的方案：直接静态托管上游已编译产物并 `curl` 取证。**

```
# 托管 _upstream/packages/app/dist（1642 个 _assets 文件）
PS> python -m http.server 4110 --bind 127.0.0.1
```

`curl` 结果：

```
PS> curl -s -o NUL -w "HTTP %{http_code}  bytes=%{size_download}" http://127.0.0.1:4110/
HTTP 200  bytes=6126

PS> curl -s -o $out -w "HTTP %{http_code} bytes=%{size_download}" http://127.0.0.1:4110/_assets/route-IBGsPZAl.js
HTTP 200  bytes=667962
```

对**经 HTTP 取回**的 bundle 检索：

```
min-width: 768px                       FOUND x6
session-mobile-view-navigation         FOUND x1
mobile-panel                           FOUND x3
review-panel                           FOUND x3
matchMedia                             FOUND x3
```

⇒ **编译产物里同时存在「768px 桌面门控」与「窄屏导航槽 + 抽屉槽」**，与源码结论一致。

### 8.3 ⚠️ 运行证据的版本落差（必须声明）

```
PS> git -C _upstream rev-parse HEAD
702a73d91ad5356e491145b9d9280a1572eb44f5      ← dist 的来源
PS> git -C _upstream log -1 --format='%H %ad %s' --date=short HEAD
702a73d…  2026-09-20  chore(core): refresh bundled models.dev snapshot
```

**该 dist 构建自 `702a73d`，比 `origin/v2` 落后 526 个提交。** 内部命名已有差异（该产物中 `screen-layout` 输出的是 `terminal:` 段，而 `origin/v2` 已改名为 `dock:`）。

⇒ **运行证据只作佐证，不作结论依据。** 结论（判定）**完全建立在 `origin/v2` 源码证据之上**。

### 8.4 收尾确认（端口已释放）

```
=== before: listener on 4110 ===
LocalPort  State OwningProcess
4110       Listen 44472
killing 44472
=== after: listener on 4110 (expect none) ===
PORT 4110 FREE - released
4095/4096: 4095 still owned by PID 51160 (not ours)
4095/4096: 4096 still owned by PID 12668 (not ours)
```

✅ **4110 已停并确认释放**；**4095 / 4096 未被本次调研占用**（PID 51160 / 12668 为既有进程，非本次产生）。

---

## 九、修法

# **不需要修。**

判定为「上游设计」，我们**与官方一致**，`webapp/**` **无任何需要修改的行**。

具体地，以下**都不是**缺陷，不应修改：

- `WebHostActivity.kt:392-393` 的 `useWideViewPort = false` / `loadWithOverviewMode = false` —— 这是**正确**的：它不篡改布局视口宽度，让上游的 768px 断点如实生效。
- 侧栏在窄屏消失 —— 上游行为，照抄即可。
- `review.toggle` 在窄屏无效 —— 上游行为（`screen-layout.ts:19`），照抄即可。

### 9.1 唯一的后续动作（非缺陷修复，是交付/对齐）

`webapp/src/main/assets/www/index.html` 仍是 W1 占位页（§5.3）。若要让 APK 内嵌真实前端，需要执行上游 dist 拷贝流程（该占位页自己写明"把这个占位页删掉即可"）。**这属于既有交付流程，不属于本次一致性缺陷，且本次未动手。**

---

## 十、未核对清单

| # | 事项 | 状态 |
|---|---|---|
| 1 | **E6 实测是否也"没有底部视图栏"** —— 上游设计说窄屏**应当**出现底部 tab 栏；E6 只报告了"侧栏消失 + 没有抽屉"，**未提及底部栏**。若底部栏也没出现，需单独排查（可能性：会话未挂载任何带 `mobile` 视图的面板 / E6 环境未加载真实应用，参见 §5.3） | **未核对 —— 建议优先跟进** |
| 2 | 窄屏 tab 标题的实际中文文案（`session.tab.session` / `ctx.plural("mobile.title", 0)` 等） | **未核对**（未读 `packages/app/src/runtime/i18n/zh.ts` 对应键） |
| 3 | `layout.narrow()` 的确切阈值 | **未核对**（只见调用点 `file/renderer.tsx:151,419`、`review/index.ts:49`、`btw/renderer.tsx:40`、`browser/model.ts:285`；未找到其定义处。推断同源 768px 但**未取证**） |
| 4 | `useWideViewPort = false` 在**其它 Android WebView 版本**上的视口行为（是否影响 `matchMedia`） | **未核对**（E6 设备实测 412px 已足够支撑本结论；未做跨版本验证） |
| 5 | 运行证据与 `origin/v2` 完全对齐的产物（`ecfa884` 的 dist） | **未核对**（本机 opencode 为 1.18.33，无 v2.0.22 可执行产物；未构建，见 §8.2/§8.3） |
| 6 | `packages/gui-extensions/src/*/renderer.tsx` 中各面板窄屏渲染分支的逐个实现细节 | **部分核对**（已核对 `mobile` 声明与 `placement() === "mobile"` 分流；未逐个通读 `mobile.tsx` 实现） |

---

## 十一、命令记录（可复现）

```powershell
# 基线
git -C _upstream rev-parse origin/v2          # ecfa884ff82e9116e475b71a0c99383b8438d710
git -C _upstream log -1 --format='%H %ad %s' --date=short origin/v2

# 断点
git -C _upstream show origin/v2:packages/app/src/session/model.ts
git -C _upstream grep -n "min-width: 768px" origin/v2 -- packages/app/src packages/gui-extensions/src

# 侧栏门控
git -C _upstream show origin/v2:packages/app/src/runtime/extension/side-region.tsx
git -C _upstream show origin/v2:packages/app/src/session/screen-layout.ts
git -C _upstream show origin/v2:packages/app/src/session/screen.tsx

# 窄屏替代 UI
git -C _upstream show origin/v2:packages/app/src/runtime/extension/mobile.tsx
git -C _upstream show origin/v2:packages/app/src/shell/mobile-panel-drawer.tsx
git -C _upstream show origin/v2:packages/gui-extensions/src/sdk/points.ts

# 命令面板
git -C _upstream show origin/v2:packages/app/src/session/commands/use-session-commands.tsx
git -C _upstream show origin/v2:packages/app/src/shell/commands/command.tsx
git -C _upstream show origin/v2:packages/app/src/shell/commands/dialog.tsx

# 我们的宿主
Select-String -Path webapp\src\main\java\com\xiufeigo\opencodeshell\web\WebHostActivity.kt -Pattern "useWideViewPort|loadWithOverviewMode"
Select-String -Path webapp\src\main\assets\www\oc-host.js -Pattern "review-panel|aside|display\s*:\s*none|matchMedia"

# 运行证据
python -m http.server 4110 --bind 127.0.0.1   # 目录 _upstream/packages/app/dist
curl -s -o NUL -w "HTTP %{http_code} bytes=%{size_download}" http://127.0.0.1:4110/
curl -s -o $out http://127.0.0.1:4110/_assets/route-IBGsPZAl.js
Select-String -Path $out -Pattern "min-width: 768px|session-mobile-view-navigation"
Stop-Process -Id <pid> -Force               # 已执行，4110 已确认释放
```

> 注：最后清理临时文件 `$env:TEMP\oc-route.js` 时被本地安全策略拦截（禁止 CLI 永久删除）。该文件位于系统 TEMP 目录、不在仓库内、未删除。**不影响任何结论**。
