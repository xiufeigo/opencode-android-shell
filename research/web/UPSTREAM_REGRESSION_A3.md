# UPSTREAM_REGRESSION_A3 — 路线 A 地基在最新上游上的复核

**审计员**：上游回归审计师 A3
**日期**：2026-10-05（本地 CST）
**目的**：确认「路线 A（服务端自托管官方 Web UI）」的四条地基，在**最新上游 v2** 上是否依然成立
**约束遵守**：纯只读；未碰设备；未跑 gradle；未改任何生产代码；唯一写入为本文件（`research/web/**`）；未切换 `_upstream` 工作区（仍停在 `702a73d`，只读其 git 对象/ref）

---

## 0. 方法说明（**必读**：与任务书给定命令有偏差，原因已核实）

任务书要求 `git -C _upstream fetch --quiet origin v2`。**该命令失败**，实测原文：

```
$ git -C _upstream fetch --quiet origin v2
fatal: unable to access 'https://github.com/anomalyco/opencode.git/':
Failed to connect to github.com:443 after 21107 ms: Could not connect to server
git-fetch-exit=128
```
（连续重试 2 次，均失败。）

网络分host探测结果：

| host | DNS | HTTP | 结论 |
|---|---|---|---|
| `github.com` | 20.205.243.166 | **000（不可达）** | 出网被墙 |
| `raw.githubusercontent.com` | 185.199.108-111.133 | **000（不可达）** | 出网被墙 |
| `codeload.github.com` | 20.205.243.165 | 301（根路径通） | 尝试下载 tarball **卡在 0 字节**，已放弃 |
| `api.github.com` | 20.205.243.168 | **200** | ✅ 唯一可用通道 |

因此**改用 GitHub REST API（`api.github.com`）读取同一份权威数据**：commit 列表用 `compare/{base}...v2`，文件树用 `git/trees/{sha}?recursive=1`，文件内容用 `git/blobs/{40位sha}`。这是 git fetch 本来要拉的**同一批对象**（同一 commit/tree/blob SHA），只是走通了另一条链路。

**完整性校验（重要）**：两棵树均 `truncated=False`
- `tree-tip` sha=`0a46301…` entries=**9630**
- `tree-base` sha=`ecfa884…` entries=**9644**

即全仓文件清单完整取回，"无变化"结论不是被截断造成的假象。

---

## 1. 最新基线

| 项 | 值 |
|---|---|
| **最新 `origin/v2`** | **`0a46301e36d7edd517dd89736acc5daf9c74898e`**（短 `0a46301`） |
| 提交标题 | `chore(core): refresh bundled models.dev snapshot` |
| 提交时间 | **2026-10-04T13:59:27Z**（= 2026-10-04 21:59 CST） |
| 提交者 | `opencode-agent[bot]` |
| 审计基线 | `ecfa884ff82e9116e475b71a0c99383b8438d710`（`feat(session-ui): show model variant in message meta (#53112)`，2026-10-04T08:21:24Z） |
| **区间提交数** | **`ahead_by = 5`，`behind_by = 0`**，merge-base = `ecfa884`（线性前进，无分叉） |
| `packages/app/package.json` 的 `"version"` | 仍为 **`"2.0.22"`**（第 3 行）⇒ 这 5 个提交**尚未**随版本号发布 |

### 1.1 `ecfa884..v2` 的 5 个提交（全部列出）

| # | SHA | 时间 (UTC) | 标题 |
|---|---|---|---|
| 1 | `247eb5c` | 2026-10-04T10:16:34 | `fix(tui): allow plugin keymaps during setup (#53010)` |
| 2 | `8380c86` | 2026-10-04T10:40:27 | `feat(plugin): add select dialog search and actions (#53009)` |
| 3 | `a515e3b` | 2026-10-04T11:36:42 | `refactor(ai): delete unregistered recording cassettes (#53127)` |
| 4 | `c789aa1` | 2026-10-04T11:37:07 | `refactor(ai): remove unused test code (#53126)` |
| 5 | `0a46301` | 2026-10-04T13:59:27 | `chore(core): refresh bundled models.dev snapshot` |

### 1.2 触及 `packages/cli/src` / `packages/app/src` / `packages/gui-extensions/src` 的提交

### **答：一个都没有。**

这是本次审计最强的单条证据。用 blob SHA 逐个比对（不是看 diff，是比对 git 对象哈希本身）：

```
=== BYTE-IDENTITY PROOF: blob SHA at ecfa884 (base) vs 0a46301 (tip) ===
packages/cli/src/app-assets.ts             6586773a == 6586773a  => IDENTICAL
packages/cli/src/services/web-ui.ts        732e505f == 732e505f  => IDENTICAL
packages/app/package.json                  a704345c == a704345c  => IDENTICAL
packages/gui-extensions/src/main.ts        d70d5769 == d70d5769  => IDENTICAL
packages/gui-extensions/src/renderer.ts    0358ee75 == 0358ee75  => IDENTICAL
packages/cli/src/version.ts                7124c72b == 7124c72b  => IDENTICAL

=== 三个受审 src 树下的每一个 blob ===
base blobs in scope = 1474 ; tip blobs in scope = 1474
added   : 0
removed : 0
MODIFIED(content differs): 0
```

**1474 个 blob，0 增 / 0 删 / 0 内容变更。** 路线 A 的全部依赖源码**逐字节未动**。

全仓路径增删（递归树对比）也只落在无关目录：

```
=> packages/tui/test/fixture/plugin/setup-keymap/tui.tsx      (新增, 测试)
=> packages/tui/test/plugin-keymap.test.tsx                  (新增, 测试)
=> packages/tui/test/plugin-select.test.tsx                  (新增, 测试)
<= packages/ai/test/fixtures/recordings/**                   (删除 16 个测试录像)
```

### 1.3 按主题归类（**只列影响界面/托管/扩展加载的**）

| 主题 | 涉及提交 | 触及的源码 | 对路线 A 的影响 |
|---|---|---|---|
| **TUI 键位映射** | #1 `247eb5c` | `packages/tui/src/context/keymap.tsx` (+26/-21)、`packages/plugin/src/tui/context.ts` (+30/-2) | **无**。TUI（终端 UI）是独立前端，不参与 web 托管链路 |
| **TUI 选择对话框**（搜索+动作） | #2 `8380c86` | `packages/tui/src/ui/dialog-select.tsx` (+38/-2)、`packages/tui/src/plugin/api.tsx` (+56/-7) | **无**。同上，纯 TUI |
| **AI 测试清理** | #3 `a515e3b`、#4 `c789aa1` | 仅 `packages/ai/test/**` | **无**。测试录像与测试桩 |
| **models.dev 数据快照** | #5 `0a46301` | `packages/core/src/models-dev/snapshot.txt` (+1/-1) | **无**。模型元数据一行，不影响资源托管/路由 |
| **文档** | 随 #2 | `services/www/src/docs/content/build/plugins/cli.mdx` (+55) | **无**。文档站，非运行时 |

> 归类小结：**5 个提交中，触及"界面/托管/扩展加载"这条线的只有 TUI 系（#1、#2），而 TUI 与 Web 托管是两条完全独立的渲染栈。** 没有任何提交触及 `cli/src`（服务端/托管）、`app/src`（Web 应用）或 `gui-extensions/src`（扩展）。

---

## 2. 四条地基逐条复核（`origin/v2` = `0a46301` 原文 + 行号）

### 地基 1 — 服务端自托管官方 UI

**证据**：`packages/cli/src/app-assets.ts`（blob `6586773a`，55 行）第 22 行 —— **与原审计行号完全一致**：

```ts
 20: const sourceAssets = Effect.fnUntraced(function* () {
 21:   const fs = yield* FileSystem.FileSystem
 22:   const root = path.resolve(import.meta.dirname, "../../app/dist")
 23:   const files = yield* fs.readDirectory(root, { recursive: true })
```

**✅ 仍然成立。** 但本次审计**必须补充一条原审计未记录的细节**（该细节同样存在于 `ecfa884`，**不是新变化**）—— 第 22 行**不是发布的构建所走的路径**：

```ts
 10: export const load = Effect.fn("cli.app-assets.load")(function* () {
 11:   const embedded = yield* Effect.tryPromise(() => import("virtual:opencode-app-assets")).pipe(Effect.option)
 12:   if (Option.isSome(embedded) && (Object.keys(embedded.value.default).length > 0 || !OPENCODE_LOCAL))
 13:     return lazy(embedded.value.default, (key) =>
 14:       brotliDecompressSync(Buffer.from(embedded.value.default[key]!, "base64")),
 15:     )
 16:   if (!OPENCODE_LOCAL) return yield* Effect.fail(new Error("Web UI assets are missing from the CLI build"))
 17:   return yield* sourceAssets()
 18: })
```

真实优先级是：**① 优先读 `virtual:opencode-app-assets` 内嵌资源（第 11–15 行，brotli 解压，资源被编译进 CLI 二进制）→ ② 仅当 `OPENCODE_LOCAL` 为真才回落到第 22 行的 `../../app/dist` 磁盘目录 → ③ 非 LOCAL 且内嵌为空则直接 `Effect.fail` 抛 `Web UI assets are missing from the CLI build`（第 16 行）**。

**对我们的意义**：第 16 行是一个**真实的失败模式**——若某个发行构建漏掉内嵌步骤，服务端会以异常而非空白页启动。这不改变"路线 A 的地基成立"，但意味着**我们不能假设"发行包里一定有 UI 资源"**；正确的不变量是"资源来自 CLI 内嵌产物"（发布路径），第 22 行只是开发期路径。

---

### 地基 2 — SPA 回落

**证据**：`packages/cli/src/services/web-ui.ts`（blob `732e505f`，73 行）第 30–37 行：

```ts
 30: function serveUI(request: HttpServerRequest.HttpServerRequest, url: URL, assets: AssetMap) {
 31:   const key = url.pathname.replace(/^\//, "")
 32:   const requested = assets[key]
 33:   if ((key.startsWith("_assets/") || key.startsWith("icons/")) && requested === undefined)
 34:     return Effect.succeed(HttpServerResponse.empty({ status: 404, headers: { "cache-control": "no-store" } }))
 35:   const name = requested !== undefined ? key : "index.html"
 36:   const file = requested ?? assets["index.html"]
 37:   if (file === undefined) return Effect.succeed(HttpServerResponse.empty({ status: 404 }))
```

第 35–36 行即 SPA 回落：命中不到资源时统一用 `index.html` 顶上。

**✅ 仍然成立。**

> ⚠️ **与原审计记录的行号不一致，需澄清**：原审计记为 `web-ui.ts:12-24,27-33`。实测 tip 上 `serveUI` 在 **30–55 行**。由于本文件 blob `732e505f` 在 `ecfa884` 与 `0a46301` **完全相同**（见 §1.2），所以不是"上游移动了行号"，而是**原审计引用的行号本身有偏差/来自更早版本**。语义结论不变，但请以本报告行号为准。

---

### 地基 3 — 路由 `/` + 资源前缀 `/_assets/`

**证据 A（路由 `/`）**：同文件第 31 行 `url.pathname.replace(/^\//, "")` ⇒ `/` 得到 `key = ""`，`assets[""]` 为 `undefined` ⇒ 落到第 35–36 行的 `index.html`。

**证据 B（`/_assets/` 前缀是代码里的显式契约）**：同文件第 33–34 行 —— `_assets/` 与 `icons/` 前缀**被显式排除在 SPA 回落之外**，缺失时**直接 404**。也就是说这个前缀是硬编码的分支，不是偶然的目录名。

**✅ 仍然成立**（且比原审计记录的更明确：前缀是显式契约）。

运行期交叉验证见 §3，结论一致。

---

### 地基 4 — 扩展编译进 app

**证据 A（workspace 依赖）**：`packages/app/package.json`（blob `a704345c`）第 66 行：
```json
 64:     "@opencode/client": "workspace:*",
 66:     "@opencode/gui-extensions": "workspace:*",
```

**证据 B（main 侧静态 compose）**：`packages/gui-extensions/src/main.ts`（blob `d70d5769`）第 19–32 行：
```ts
 19: export const builtins = Extension.compose(
 20:   context,
 21:   btw,
 22:   debug,
 23:   terminal,
 24:   file,
 25:   review,
 26:   details,
 27:   { ...browser, main: () => import("./browser/main") },
 28:   { ...pairing, main: () => import("./pairing/main") },
 29:   { ...updater, main: () => import("./updater/main") },
 30:   { ...ssh, main: () => import("./ssh/main") },
 31:   { ...wsl, main: () => import("./wsl/main") },
 32: )
```

**证据 C（renderer 侧静态 compose）**：`packages/gui-extensions/src/renderer.ts`（blob `0358ee75`）第 36–49 行：
```ts
 27: // The window renders once every built-in is active, so the small renderer entries load with the app, like the
 28: // features they replaced. Heavy UI stays behind `lazy()` inside them.
 29: const eager = (setup: Awaited<ReturnType<NonNullable<Definition["renderer"]>>>["default"]) => () =>
 30:   Promise.resolve({ default: setup })
 36: export const builtins = Extension.compose(
 37:   { ...context, renderer: eager(contextRenderer) },
 38:   { ...btw, renderer: eager(btwRenderer) },
 39:   { ...debug, renderer: eager(debugRenderer) },
 40:   { ...terminal, renderer: eager(terminalRenderer) },
 41:   { ...file, renderer: eager(fileRenderer) },
 42:   { ...review, renderer: eager(reviewRenderer) },
 43:   { ...details, renderer: eager(detailsRenderer) },
 44:   { ...browser, renderer: eager(browserRenderer) },
 45:   { ...pairing, renderer: eager(pairingRenderer) },
 46:   { ...updater, renderer: eager(updaterRenderer) },
 47:   { ...ssh, renderer: eager(sshRenderer) },
 48:   { ...wsl, renderer: eager(wslRenderer) },
 49: )
```

**✅ 仍然成立。** 12 个扩展在 main 侧与 renderer 侧**双侧静态列举**，全部走 `Extension.compose` 的**静态字面量**（非动态发现/运行时注册）⇒ 打包器能静态看到，扩展确实被编译进 app 产物。文件头注释还说明两侧一致性由 `builtins.typecheck.ts` 静态校验。

---

## 3. 运行期交叉验证（已执行，端口 4110，**已收尾**）

> ⚠️ **诚实声明（不掩盖）**：本机可用的 v2 发行版是 **v2.0.14**（`C:\Users\xiufe\.bun\bin\opencode.exe`），**不是** tip `0a46301` 的构建产物——在 tip 上从源码构建需 `bun install` + `vite build`，超出本次只读范围。因此：
> - **运行期**结论覆盖的是 **v2.0.14** 这个真实 v2 服务；
> - **对 tip 的外推**依据是 §1.2 的 blob 逐字节同一性（`web-ui.ts` / `app-assets.ts` 自 `ecfa884` 起未变），**不是**"我猜它会一样"。

启动参数与身份：
```
$ opencode serve --port 4110 --hostname 127.0.0.1      # env OPENCODE_SERVER_PASSWORD=opencode
server listening on http://127.0.0.1:4110
$ opencode --version  =>  opencode v2.0.14
```

### 3.1 `GET /`（未带认证）
```
HTTP/1.1 200 OK
content-type: text/html
cache-control: no-cache
content-security-policy: default-src 'self'; script-src 'self' 'wasm-unsafe-eval' 'sha256-DvjbVLSCqgtwL7bnRLffODAPpW63X4inV+gVnQBZwBw='; style-src 'self' 'unsafe-inline'; img-src 'self' data: https: blob:; font-src 'self' data:; media-src 'self' data:; connect-src * data: blob:
x-content-type-options: nosniff
vary: Origin, Accept-Encoding
```
→ **地基 3（路由 `/`）运行期确认**。body 内实际引用前缀（节选）：
```
/_assets/button-BFiwHX2o.js      /_assets/button-CP8fzIZH.css
/_assets/desktop-native-vJFY6KWL.js   /_assets/dist-B3cU5Pub.js
```

### 3.2 `GET /_assets/button-BFiwHX2o.js`
```
HTTP/1.1 200 OK
content-type: text/javascript
cache-control: public, max-age=31536000, immutable
content-security-policy: default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; ...
x-content-type-options: nosniff
```
→ **地基 3（`/_assets/` 前缀 + 不可变缓存）运行期确认**。`cache-control` 与 `web-ui.ts:44` 的 `revalidate ? "no-cache" : "public, max-age=31536000, immutable"` 完全对应。

### 3.3 前缀是硬契约（对应 `web-ui.ts:33-34`）
```
GET /_assets/does-not-exist.js  =>  status=404   content_type=(空)
```
→ 缺失的 `_assets/` 资源**回 404，而不是回落成 HTML**。这正是第 33–34 行的行为，**运行期与源码一致**。

### 3.4 SPA 回落（对应 `web-ui.ts:35-36`）
```
GET /some/deep/route
HTTP/1.1 200 OK
content-type: text/html
cache-control: no-cache
   -> body identical to / ?  True        （与 GET / 的 body 逐字节哈希相同）
```
→ **地基 2 运行期确认**。

### 3.5 附带
```
GET /icons/prod/favicon.ico      =>  status=200  content_type=image/vnd.microsoft.icon
GET /api/health                  =>  status=401
```
→ `icons/` 与 `/api` 各自走第 33 行与第 17–25 行的分支；**UI 先于认证下发、API 受认证保护**（`OPENCODE_SERVER_PASSWORD=opencode` 生效）。

### 3.6 收尾：服务已停，端口已释放
```
pid to stop = 48908 (实际监听者 46100)
--- before stop ---  LISTENING pid=46100
--- after stop (3s) ---
port 4110 => RELEASED (no listener)
pid 48908 => gone
pid 46100 => gone
curl exit=7 http_code=000  (000 = refused, server down)
--- 4095/4096 未被触碰 ---
port 4095 => still LISTENING pid=3748 (pre-existing, untouched)
port 4096 => still LISTENING pid=12668 (pre-existing, untouched)
```
✅ 临时服务已停；`curl` 退出码 7 = 连接被拒 = 服务确已下线；**4110 端口无监听**；4095/4096 生产服务全程未动。

---

## 4. 扩展新增 / 删除清单（web UI 暴露面）

`packages/gui-extensions/src/**` 下的扩展目录（tip 侧）：

| 扩展目录 | `index.ts` | `renderer.tsx` | `main.ts` | `panel.tsx` | `tab.tsx` | 文件数 |
|---|:---:|:---:|:---:|:---:|:---:|---:|
| `browser` | ✅ | ✅ | ✅ | ✅ | — | 96 |
| `btw` | ✅ | ✅ | — | ✅ | — | 5 |
| `context` | ✅ | ✅ | — | — | ✅ | 70 |
| `debug` | ✅ | ✅ | — | — | — | 68 |
| `details` | ✅ | ✅ | — | ✅ | — | 76 |
| `file` | ✅ | ✅ | — | — | — | 89 |
| `pairing` | ✅ | ✅ | ✅ | — | — | 6 |
| `review` | ✅ | ✅ | — | ✅ | — | 73 |
| `sdk` | ✅ | — | ✅ | — | — | 13 |
| `ssh` | ✅ | ✅ | ✅ | — | — | 82 |
| `terminal` | ✅ | ✅ | — | ✅ | ✅ | 79 |
| `updater` | ✅ | ✅ | ✅ | — | — | 77 |
| `wsl` | ✅ | ✅ | ✅ | — | — | 79 |

**实际被 `Extension.compose` 编入 app 的内置扩展 = 12 个**（`sdk` 是 SDK 自身，非功能扩展；`main.ts` / `renderer.ts` / `builtins.typecheck.ts` 是装配文件，非扩展目录）：
`context, btw, debug, terminal, file, review, details, browser, pairing, updater, ssh, wsl`

### 与 `ecfa884` 的差异

```
--- top-level dir set diff base vs tip ---
(identical)
--- index.ts / renderer.tsx path diff base vs tip ---
(no added/removed)
```

### **新增扩展：0 个。删除扩展：0 个。**

**对"托管官方 UI 是否自动获得新功能"的含义**：
在 `ecfa884 → 0a46301` 这个窗口内，**托管官方 UI 不会自动获得任何新功能**——因为这 5 个提交里没有一个落在 Web 渲染栈上（唯一的"新东西"是 TUI 的选择对话框搜索与键位映射，属终端 UI）。同时，**我们也没有失去任何东西**：12 个内置扩展一字未改。

---

## 5. 结论：**路线 A 仍然安全 —— YES**

### 依据（按证据强度排序）

1. **【决定性】源码逐字节未动。** `packages/cli/src` + `packages/app/src` + `packages/gui-extensions/src` 下的 **1474 个 blob，在 `ecfa884` 与最新 `0a46301` 之间 0 增 / 0 删 / 0 变更**（§1.2）。四条地基所依赖的全部代码就是这些 blob 的一部分，因此"地基变了"在数学上不成立。
2. **【逐条复核】四条地基全部在最新 `origin/v2` 上原地成立**，且都拿到了行号 + 原文（§2）：`app-assets.ts:22`；`web-ui.ts:30-37`（回落）、`:31`（`/`）、`:33-34`（`/_assets/` 硬契约）；`packages/app/package.json:66` + `gui-extensions/src/main.ts:19-32` + `renderer.ts:36-49`。
3. **【运行期】四条地基在真实 v2 服务上行为一致**（§3）：`/` → 200 `text/html`；`/_assets/*.js` → 200 `text/javascript` + `immutable`；缺失 `_assets` → 404 不回落；深链 → 200 且 body 与 `/` 逐字节相同。
4. **【影响面】5 个新提交全部落在 TUI / AI 测试 / 模型数据 / 文档**，无一触及 `cli/src`、`app/src`、`gui-extensions/src`（§1.3）。
5. **【功能面】内置扩展 12 个，与 `ecfa884` 完全一致，0 新增 0 删除**（§4）。

### 这次复核相对上一轮的三点**增量**（都不是回归，但会影响我们的心智模型）

1. **`app-assets.ts:11-16` 的内嵌资源优先级应被纳入地基 1。** 发布路径是 `virtual:opencode-app-assets`（brotli 内嵌），`../../app/dist`（第 22 行）只是 `OPENCODE_LOCAL` 开发路径。**推论**：我们的自托管流程若哪天改成从源码/本地目录喂资源，必须确保 `OPENCODE_LOCAL` 语义被尊重，否则会命中第 16 行的 `Effect.fail` 或误走内嵌分支。
2. **`/_assets/` 与 `icons/` 是显式代码分支（`web-ui.ts:33`），不是约定俗成的目录名。** 这意味着如果我们将来对上游做 patch/裁剪，**删改这条前缀判断会直接让资源 404**，而不是优雅降级。
3. **原审计记录的 `web-ui.ts:12-24,27-33` 行号与实测（`30-55`）不符**，且该文件 blob 在区间内未变 ⇒ 是原记录行号有偏差。**后续引用请以本报告为准。**

### 若将来 NO，修复方案（预案，本次不触发）

- **坏在"自托管"**：改为在构建期确保 `virtual:opencode-app-assets` 内嵌产物非空（而不是依赖 `../../app/dist` 落盘目录）。
- **坏在"路由/前缀"**：在自托管层把 `/` 映射到 `index.html`、把 `/_assets/*` 原样透传，并在上游侧保留 `web-ui.ts:33` 的分支语义；不要试图用 SPA 回落掩盖资源 404。
- **坏在"扩展编译"**：保持 `packages/app/package.json` 的 `workspace:*` 依赖 + 两侧 `Extension.compose` 静态字面量形态；**任何把扩展改为动态注册的重构都会打破打包期可见性**（静态列举是我们能"自动获得新功能"的前提）。

---

## 6. 未核对清单（**明确未验证，不得当作已验证**）

| # | 未核对项 | 原因 |
|---|---|---|
| 1 | `git fetch` / 签名校验（`git verify-commit`）、本地 commit 对象 | `github.com:443` 出网被墙，无法 fetch。改用 `api.github.com` REST 取同一批 commit/tree/blob SHA —— **数据同源**，但未走 git 原生传输与签名校验 |
| 2 | **在 tip `0a46301` 上从源码构建并运行服务** | 需 `bun install` + `vite build`，超出只读范围。运行期实测用的是**本机 v2.0.14**（§3 开头已声明）。对 tip 的外推**仅**依赖 blob 同一性 |
| 3 | 发布版 npm tarball 中 UI 资源是否真被内嵌（`OPENCODE_LOCAL` 在发行构建中的取值） | 未下载发行 tarball。`app-assets.ts:11-16` 只证明了**代码分支顺序**，未证明**我们线上那个构建**走了哪条 |
| 4 | `packages/app/src` 下各文件逐一阅读 | 该树 0 变更（blob 同一），且地基 4 的证据落在 `package.json` + `gui-extensions`，故未逐文件读。**若后续要改 app 侧逻辑，需补读** |
| 5 | 5 个新提交在 `packages/ai/test/**` 的 16 个录像删除是否影响任何运行期行为 | 判定为测试专用（`refactor(ai): delete unregistered recording cassettes`），未深入验证录制测试框架 |
| 6 | `services/www/src/docs/.../plugins/cli.mdx` (+55) 文档新增内容 | 文档站，非运行时，不影响四条地基。**未读内容** |
| 7 | `packages/core/src/models-dev/snapshot.txt` (+1/-1) 的具体差异 | 属模型元数据快照，未取内容。理论上不影响托管，但**未验证** |

---

## 7. 本次操作的写入与影响面（自证合规）

- **唯一新增文件**：`research/web/UPSTREAM_REGRESSION_A3.md`（本文件）
- **未改任何生产代码**；**未改 `_upstream` 工作区**（其 `HEAD` 仍为 `702a73d`，`refs/remotes/origin/v2` 仍为 `ecfa884`，未 fetch、未 checkout）
- **未碰设备**；**未跑 gradle**
- **临时服务**：端口 **4110**，起→测→**停**，已用 `Get-NetTCPConnection` + `curl`（exit 7）双重复核端口释放；4095/4096 生产服务未触碰
- 临时工作区（tree/blob JSON 缓存、启动日志）位于系统 `%TEMP%\a3_upstream`（22.8 MB），**不在仓库内**，**已用 `rm --` 移入回收站删除并确认消失**（`mavis-trash: moved to trash`，随后 `Test-Path` = false）
- 复核实测：
  ```
  4110 => RELEASED (no listener)
  curl / => exit=7 code=000 (connection refused)
  port 4095 => still LISTENING pid=3748
  port 4096 => still LISTENING pid=12668
  _upstream HEAD = 702a73d   refs/remotes/origin/v2 = ecfa884   （均未变动）
  ```
- 说明：父仓库 `git status` 中另有 `M webapp/.../NotifierHost.kt`、`.agent-gradle-lock`、`research/env/*`、`research/web/n2-emu-5880.log.err` 等改动，**均为本次审计之前就存在的他人/并行工作产物，非本次审计所写**；`_upstream` 的 `M bun.lock` 同为进入本任务前既有。
