# 配对 / 扫码路径定性归档（V8）

> **问题**：V7 / F5 都实测到"connect screen 恒不渲染"，F5 定位到"上游 `registry` 自动播种 ⇒ connect 走 `fallback` 死支"。
> **要回答**：这是**我们的缺陷**，还是**上游设计**？
> **结论（二选一，必须选一个）**：✅ **上游行为（我们一致）**

- **轮次**：V8（收尾复验轮）
- **依据来源**：`_upstream/` 工作区（上游 opencode 仓库只读检出）**行号 + 原文**，另加本轮设备侧复验佐证
- **一行话**：**"已配置的客户端不会再看到配对页"是上游既定行为；我们在 WebView 壳里没有引入偏差，因此不是我们的缺陷。**

---

## 1. 证据链（四段，全部带行号与原文）

### 1.1 播种点：web 入口**总是**自带一个服务器

`_upstream/packages/app/src/runtime/platform/web.ts:55-64`：

```ts
55: function getCurrentServerUrl() {
56:   if (import.meta.env.VITE_OPENCODE_SERVER_MODE === "none") return undefined
57:   if (import.meta.env.DEV) {
58:     const loopback =
59:       location.hostname === "localhost" || location.hostname === "[::1]" || location.hostname.startsWith("127.")
60:     const host = import.meta.env.VITE_OPENCODE_SERVER_HOST ?? (loopback ? location.hostname : "localhost")
61:     return `http://${host}:${import.meta.env.VITE_OPENCODE_SERVER_PORT ?? "4096"}`
62:   }
63:   return location.origin
64: }
```

**生产构建（非 `DEV`、未设 `VITE_OPENCODE_SERVER_MODE=none`）走 `:63`，返回 `location.origin`，恒非空。**
唯一能让它变 `undefined` 的开关是 `:56` 那个 `none`。

`_upstream/packages/app/src/entry.tsx:80-89, 94-98`：

```tsx
80:     const server: ServerConnection.Http | undefined = web.currentServerUrl
81:       ? {
82:           type: "http",
83:           authToken: !!auth,
84:           http: {
85:             url: web.currentServerUrl,
86:             ...auth,
87:           },
88:         }
89:       : undefined
...
94:             <AppInterface
95:               defaultServer={web.defaultServerUrl ? ServerConnection.Key.make(web.defaultServerUrl) : undefined}
96:               canonicalLocalServer={server ? ServerConnection.key(server) : undefined}
97:               servers={server ? [server] : []}
98:             >
```

⇒ `servers` 这个 prop 在生产构建下**至少含 1 项**。

### 1.2 registry 把 prop 合并进 `list`

`_upstream/packages/app/src/runtime/server/registry.tsx:213-215`：

```tsx
213:     const allServers = createMemo((): Array<ServerConnection.Any> => {
214:       return resolveServerList({ stored: store.list, props: props.servers })
215:     })
```

`resolveServerList`（同文件 `:101-123`）把 `props` 与本地存储 `store.list` **并集**去重，
返回结果作为对外的 `list`（`:255-257` 的 getter `get list() { return allServers() }`）。

⇒ 只要 `props.servers` 非空，**`servers.list.length` 就 ≥ 1**。

### 1.3 判定点：connect screen 是"列表为空"时的 `fallback`

`_upstream/packages/app/src/shell/routes/routes.tsx:76-89`：

```tsx
76: function AppLayout(props: ParentProps) {
77:   const servers = useServers()
78:   return (
79:     <Show when={servers.list.length > 0} fallback={<ConnectServerScreen />}>
80:       <LayoutProvider>
...
87:     </Show>
88:   )
89: }
```

**这就是 F5 说的"死支"的准确形态**：`ConnectServerScreen` 是 `<Show>` 的 **`fallback`**，
只有 `servers.list.length === 0` 时才渲染。而 1.1+1.2 保证了生产构建下这个长度 **≥ 1**
⇒ **fallback 永远不渲染**。

### 1.4 connect screen 本身是健康的、不是坏的

`_upstream/packages/app/src/servers/connect/screen.tsx:18-19, 44, 134-149` 显示它有完整的
手填表单（URL/口令）、`<Button type="submit">`（`:134-136`）与扫码入口
（`:138-149`，`platform.platform === "web"` 时才渲染 `server.connect.scan` 按钮，
`:16` 的 `PairingScanner` 是 `lazy` 组件）。
扫码走 `onScan` → 填 url/urls/password → `request.mutate()`（`:68-77`）→ `servers.add({type:"http",http})`（`:44`）。

⇒ **它没坏，只是"够不着"**。够不着的唯一原因是 §1.3 那个 `fallback` 位置。

---

## 2. 关键判据：桌面浏览器直连同一个后端，会不会也这样？

**答：会，完全一样。** 依据不是感觉，是上面四段代码本身：

1. 触发条件（`servers.list.length > 0`）由 `entry.tsx:97` 的 `servers` prop 决定，
   而该 prop 在**任何** web 构建下都由 `web.ts:63` 的 `location.origin` 填满。
2. 这段判定**不在平台抽象之下**——`routes.tsx` 是共享路由层，桌面浏览器打开同一个后端
   走的是同一份 bundle、同一个 `AppLayout`、同一个 `<Show>`。
3. 换句话说，**"配对页不可见"是 opencode web 客户端的固有属性，不是 Android 壳引入的**。

**推论（正向表述）**：
> **一个已经配置好服务器的客户端，不会再看到配对页** —— 这与官方桌面客户端行为一致，
> 因此**我们与官方一致，不是缺陷**。

**本轮设备侧佐证**：`emulator-6150` 上从冷启动到进入 Home 全程，
logcat 与 `uiautomator` 中 **`ConnectServerScreen` / `connect-server` / `server-connect-title`
等标记 0 命中**，直接进了官方 Home（`research/web/v8_shots/v8_official_ui.png`）。
这与 §1.3 的推导一致。

---

## 3. 结论

### ✅ 上游行为（我们一致）

- **不为此改动上游任何一行。** 派单明确要求"**不要**为'看起来更友好'而改动上游行为（一致性优先）"——
  强行让已配置的客户端再看到配对页，恰恰是**制造**与官方的不一致。
- 也不需要"给方案"：本条不是缺陷。

---

## 4. 那么"需要时如何进入配对"（按上游机制，不臆造）

配对页的**唯一**合法入口是 `servers.list.length === 0`。而 `list` 是
`resolveServerList({ stored, props })` 的并集（`registry.tsx:214`），
`props` 在生产构建下**恒非空**（§1.1）⇒ **在同一个已构建的客户端里，"清空本地存储"是不够的**：
即使把 `store.list` 清空，`props.servers` 那 1 项还在，`length` 仍 ≥ 1，**配对页照样不出来**。

按上游机制，正确的进入方式有两条：

### 方式 A（推荐，唯一干净的办法）：用 `VITE_OPENCODE_SERVER_MODE=none` 构建

`web.ts:56` 是上游留的、**唯一**能让 `currentServerUrl` 变 `undefined` 的开关：

```ts
56:   if (import.meta.env.VITE_OPENCODE_SERVER_MODE === "none") return undefined
```

置成 `none` 后：`entry.tsx:80` 的 `server` 为 `undefined` ⇒ `:97` 的 `servers={server ? [server] : []}`
变成 `servers={[]}` ⇒ `registry.tsx:214` 合并后 `list` 只剩本地存储项 ⇒
**本地存储为空时 `length === 0` ⇒ `routes.tsx:79` 的 `fallback` 命中，`ConnectServerScreen` 渲染**。

⇒ 即"**一个不带默认服务器的纯客户端**"，这正是配对页被设计出来的场景（§1.4：手填地址 / 扫 `opencode pair` 码）。

### 方式 B：换一个新的客户端身份（等价于方式 A 的运行态）

- 用**另一台设备 / 另一个 WebView 数据目录**（清 `app data`、或另一台模拟器）⇒ 新的空 localStorage，
  且**用 `none` 模式构建的包**；
- 或上游的 `servers.add()`（`screen.tsx:44`）路径本身要求"先有一个空的 list"才能通过 UI 添加，
  所以在已配置的客户端里**没有**"再加一台服务器"这件事的 UI 入口 ——
  已配置客户端的增删走的是 **Settings → Servers**（`settings/servers/servers.tsx`），
  **不是**配对页。

> ⚠️ **常见误解纠正**：「清空浏览器/localStorage 就能看到配对页」**在默认 web 构建下不成立**，
> 因为 `props.servers` 那一项来自 `location.origin`（`web.ts:63`），与本地存储无关。
> 要进配对页，**必须**让构建侧的 `currentServerUrl` 为空，即方式 A。

---

## 5. 对后续轮次的处置建议（不改代码，仅记账）

- 台账上把"connect screen 不可达 / 扫码入口进不去"从**缺陷**改记为
  **「上游行为 · 需 `VITE_OPENCODE_SERVER_MODE=none` 才可达」**，
  避免后续每一轮都重复把它当 bug 撞一遍（V7、F5、V8 都撞了）。
- 若产品侧确实需要"已配置客户端里也能扫码换服务器"，那是**上游特性缺口**，
  应向上游提 issue / 或在 Settings → Servers 里加入口；**不建议我们在壳里私自改上游路由判定**。

---

## 6. 附：本轮如何取到这些行号（可复现）

```
$ git rev-parse --verify origin/v2
fatal: Needed a single revision      ← 本仓并无 origin/v2 引用

$ git for-each-ref --format='%(refname)'
refs/heads/main
refs/heads/native-kotlin-snapshot
refs/remotes/origin/HEAD
refs/remotes/origin/cursor/fix-ime-selection-session-batch
refs/remotes/origin/cursor/opencode-webview-shell-c445
refs/remotes/origin/main
refs/remotes/origin/native-kotlin-snapshot
refs/tags/v1.3.19 / v1.3.20 / v1.4.0
```

**如实说明**：派单要求"用 `origin/v2` git 对象"取证，但**该引用在本仓不存在**（命令原文见上）。
本报告的行号与原文全部取自 **`_upstream/` 工作区检出**（上游 opencode 仓库，只读），
文件为 `_upstream/packages/app/src/{entry.tsx, runtime/platform/web.ts, shell/routes/routes.tsx, runtime/server/registry.tsx, servers/connect/screen.tsx}`。
若 Lead 所说的 `origin/v2` 指的是**另一个仓库/另一个远端**，请指认，我按那个对象重新取证并复核结论
（**但从代码逻辑看，§1.1–§1.3 的因果链与"哪个 git ref"无关**，换 ref 不应改变定性）。
