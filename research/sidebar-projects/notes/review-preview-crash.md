# review 扩展在预览里启动崩溃（Changes frame 缺失）定位报告

- 日期：2026-10-22
- 范围：只读调查；未修改 `packages/` 任何源码，未重新构建 UI，未修改共享的 `research/sidebar-projects/tools/preview.mjs`（修复用临时副本验证）。
- 复现/验证产物：`research/sidebar-projects/notes/_repro/`（临时目录，可删）。

## 摘要

- **崩溃点**：`_scratch_root/desktop-parity/checkout/packages/gui-extensions/src/review/model.ts:107`
  （`options` memo 内的 `vcs()?.branch.current`）。
- **直接原因**：预览 harness 的 mock v2 后端把两个不同契约的路由合并成同一个响应：
  `GET /api/vcs`（成功体是 `Vcs.Info = { provider?, branch: { current?, default? } }` 对象）
  与 `GET /api/vcs/status`（成功体是 `Vcs.FileStatus[]` 数组）都返回 `{ location, data: [] }`。
  于是客户端的 `location.vcs.info()` 返回 `[]`；`vcs()?.branch` 是 `undefined`，继续读 `.current`
  就抛出 `TypeError: Cannot read properties of undefined (reading 'current')`。
- **为什么会“扩展启动失败”**：该 memo 的求值发生在 `review` 扩展 setup 期间创建的响应式计算里，
  被 extension host 的 `catchError` 捕获 → `deactivate + fail` → 扩展被注销，它注册的 `Panel` 也随之消失；
  panel-grid 找不到 `review` 的 provider，于是“更改/Changes”frame 不渲染（`packages/app/src/session/panel-grid.tsx:120-122,154`）。
- **修复归属**：**mock-only**。真实服务端按 schema 永远返回带 `branch` 的对象，生产环境不会触发；
  不建议改 app/扩展源码。
- **最小修复**：`preview.mjs` 中把 `/api/vcs` 从 `/api/vcs/status` 的合并分支拆出，返回正确的 `Vcs.Info`。
  已用该 patch 的副本验证：console 不再出现 `[extension] review ...`，panel frame 从 3 个变 4 个，
  “更改”frame 出现（`Git 变更` / `暂无文件变更`），并且修复后才开始请求 `GET /api/vcs/diff`。
- **置信度**：高（≈0.95，见末节“未确认项”）。

## 复现步骤

工作目录：`C:\sensorsdata\main\program\opencode-android-shell`

```powershell
New-Item -ItemType Directory -Force -Path research\sidebar-projects\notes\_repro | Out-Null
node research\sidebar-projects\tools\preview.mjs `
  --ui dist\sidebar-preview\ui-after `
  --out research\sidebar-projects\notes\_repro `
  --name review-crash `
  > research\sidebar-projects\notes\_repro\report.json 2>&1
```

（`--out` 只是截图/JSON 输出目录；可换成任意临时目录。）

观察到的关键输出（`report.json`，也见 `_repro/review-crash.png`）：

1. `steps[]` 里有一条 `console: "error"`，原文：

```text
[extension] review TypeError: Cannot read properties of undefined (reading 'current')
    at Object.fn (http://127.0.0.1:56354/_assets/index-DzJivo7r.js:105:3464)
    at ge (http://127.0.0.1:56354/_assets/store-CWFQZoRe.js:1:5853)
    at M (http://127.0.0.1:56354/_assets/store-CWFQZoRe.js:1:5633)
    at w (http://127.0.0.1:56354/_assets/store-CWFQZoRe.js:1:1265)
    at KP (http://127.0.0.1:56354/_assets/index-DzJivo7r.js:105:3386)
    ...
```

注意 `report.errors`（`pageerror`）为空是正常的：它是 `console.error`，
由 `packages/app/src/runtime/extension/host.tsx:782`（`fail()`）打印。

2. 屏幕布局（1280×770@2x）只有 3 个 panel frame：**终端 / 浏览器 / 文件**，“更改”frame 缺失。
   见 `_repro/review-crash.png`。

3. 崩溃时的 DOM 探针（`--actions-file` 见下）：
   - `[data-slot="session-panel-frame"]` 数量 = **3**
   - `document.body.innerText.includes("变更")` = **false**
   - 页面里能查到 `session-review-v2-*` 槽位，但它们属于“文件”frame 复用的
     session-review-v2 组件（`packages/session-ui/src/v2/components/session-file-panel-v2.tsx`；
     文件扩展 `packages/gui-extensions/src/file/browser.tsx` 也引用 `SessionReviewV2Sidebar`），
     不是 review 扩展的 Panel 本身。

## 根因

### 1) 直接抛出位置（file:line + 代码）

`_scratch_root/desktop-parity/checkout/packages/gui-extensions/src/review/model.ts:99-118`：

```ts
  const vcs = createMemo(() => view().server.data.location.vcs.info({ directory: directory() }))

  const options = createMemo<ChangeMode[]>(() => {
    const list: ChangeMode[] = []
    const project = view().project

    if (project?.vcs) list.push("git")

    if (
      project?.vcs &&
      vcs()?.branch.current &&          // ← line 107：vcs() === [] 时 .branch 为 undefined，读 .current 抛错
      vcs()?.branch.default &&          // ← line 109：同样会抛
      vcs()?.branch.current !== vcs()?.branch.default
    ) {
      list.push("branch")
    }

    // Turn snapshots are captured only for Git sessions.
    if (project?.vcs === "git" && view().id) list.push("turn")

    return list
  })
```

同一模式还出现在 `model.ts:140`（`vcsKey` memo 的 `vcs()?.branch.current ?? ""`），
所以即便只给 107 行加保护，140 行仍会炸；**正确修法是让 `vcs()` 返回契约对象，而不是逐行加保护**。

### 2) 为什么 `vcs()` 是 `[]`（mock 响应形状错误）

mock 路由（`research/sidebar-projects/tools/preview.mjs:292-295`）：

```js
    if (p === "/api/vcs/status" || p === "/api/vcs" || p === "/vcs/status") {
      return send({ location: { directory }, data: [] })
    }
```

客户端把它当 `Vcs.Info` 发布：

- `packages/client/src/solid/data.ts:1371`：`const vcs = locationResource("vcs", (location) => api().vcs.get({ location }))`
- `packages/client/src/solid/data.ts:1972`：`vcs: { info: vcs.list, sync: vcs.sync, invalidate: vcs.invalidate }`
  （`vcs.list` 直接返回 `store.location[key].vcs`，也就是响应里的 `data`）
- `packages/gui-extensions/src/review/model.ts:97` 用 `location.vcs.info({ directory })` 读它

真实契约：

- `packages/protocol/src/groups/vcs.ts:52-64`：`vcs.get`（`GET /api/vcs`）成功体是 `Location.response(Vcs.Info)`
- `packages/protocol/src/groups/vcs.ts:82-94`：`vcs.status`（`GET /api/vcs/status`）成功体是 `Location.response(Schema.Array(Vcs.FileStatus))`
- `packages/schema/src/vcs.ts:12-16`：`Vcs.Info = { provider?: string, branch: Vcs.Branch }`，**`branch` 是必填**
- `packages/schema/src/vcs.ts:6-10`：`Vcs.Branch = { current?: string, default?: string }`

即真实服务端 `GET /api/vcs` 返回 `{ location, data: { branch: { current, default } } }`；
mock 返回 `data: []`（status 形状），`info()` 得到数组 `[]`。
`[]` 是 truthy，`vcs()?.branch` 不会因可选链短路 → `.branch` 为 `undefined` → 读 `.current` 抛错，
报错文本正好是 `reading 'current'`。

### 3) 求值时机与“扩展失败”链路

1. `review` 扩展的 model 不是 setup 里同步创建的：`renderer.tsx:72-106` 用
   `createKeyed(ctx.screen.current, …)` 在会话屏挂载、且 `sessions.current()` 有值时创建
   （`createReviewModel({ ctx, screen, view, demand })`）。
2. `createReviewModel` 创建 `diffQuery`（`model.ts:153-174`），其 options 回调首先读 `mode()` →
   `options()`。
3. 首次求值时 `/api/vcs` 可能还没回来，`vcs()` 为 `undefined`，`vcs()?.branch…` 短路，不报错；
   等 location resource 把 `[]` 发布进来，memo 重算 → 抛错。
   （证据：崩溃信息出现在 `event stream OPEN` 之后；修复后才会出现 `GET /api/vcs/diff` 请求，
   说明修复前 `diffQuery` 的 queryKey 还没算完就抛了。）
4. 该计算在扩展 setup 期间创建的 owner 下；host 用 `catchError` 包住 setup
   （`packages/app/src/runtime/extension/host.tsx:455-480`），错误被路由到 `crash(cause)`：
   `deactivate(id)` + `fail(id, cause)`。
5. `fail()`（`host.tsx:781-792`）以默认 `phase: "setup"` 记失败，打印
   ``console.error(`[extension] ${id}`, cause)`` —— 这就是 `[extension] review TypeError …` 的来源；
   同时 `deactivate()` 注销该实例的全部 contribution，`Panel` 条目被移除。
6. panel-grid 的 `PanelFrame` 通过
   `host.items(Panel).find((item) => GRID_PANEL_EXTENSIONS[id].includes(item.extension))` 找 provider，
   找不到 `review` 时 `<Show when={provider()}>` 不渲染（`packages/app/src/session/panel-grid.tsx:120-122,154`），
   于是“更改”frame 消失。

> 说明：任务描述中的“扩展在 startup 失败”是 host 的分类（`phase: "setup"`）造成的观感；
> 实际抛错点在会话屏挂载后的响应式重算，而不是 `setup()` 同步执行时。`ctx.screen` / `ctx.sessions`
> 这些 host API 都存在且实现是安全的（`packages/app/src/runtime/extension/host-apis.tsx:286-296`），
> 本次**不是** host API 缺失。

### 4) 构建产物中的对应位置（用于核对源码）

崩溃栈首帧 `index-DzJivo7r.js:105:3464` 处的代码为（同一行内截取）：

```js
,t=r().project;return t?.vcs&&e.push(`git`),t?.vcs&&b()?.branch.current&&b()?.branch.default&&b()?.branch.current!==b()?.branch.default&&e.push(`branch`),t?.vcs===`git`&&r().id&&e.push(`turn`),e}
```

逐字对应 `model.ts` 的 `options` memo（`project?.vcs && vcs()?.branch.current && vcs()?.branch.default && …`），
其中 `b` = `vcs`，`r` = `view`。因此可以确定栈顶就是 `model.ts:107` 这一读。
（构建文件名带 hash，重建后会变。）

## 为什么只在预览里出现

1. **真实后端满足契约**：`GET /api/vcs` 的成功体由 `Location.response(Vcs.Info)` 定义，
   `branch` 是必填字段；真实服务端不会返回数组。`vcs()?.branch.current` 的写法对契约是类型安全的
   （`branch` 非可选，`current` 可选），所以生产环境不会抛错。
2. **只有 mock 把两个路由混为一谈**：`preview.mjs:292-295` 用同一个分支处理
   `/api/vcs`、`/api/vcs/status`、`/vcs/status`，用 status 的数组形状回答了 info 请求。
   这是 harness 的 mock 形状错误，不是 app 或扩展的业务 bug。
3. **容易被“删掉这条分支”掩盖**：`preview.mjs` 末尾还有 fallback
   `return send({ data: [] })`（`preview.mjs:368`）。如果只是把 `/api/vcs` 从合并条件里删掉，
   `/api/vcs` 会落到 fallback，仍然是 `{ data: [] }`，崩溃照旧。
   **必须显式新增正确的 `/api/vcs` 路由。**
4. 同类脚本 `scripts/verify-android-terminal-release.mjs:518-519`、`scripts/verify-android-terminal.mjs:416-417`
   没有显式 mock `/api/vcs`（会落到它们的 `send({ data: [] })` fallback）。它们目前未表现出该崩溃，
   可能是因为使用的 UI 不是这套 extension 版或没有走 `review` model；未验证（见末节）。

## 建议修复（patch）

### 主修复（必需，只改 mock harness，不改源码、不需重建）

`research/sidebar-projects/tools/preview.mjs`，替换 292-295 行：

```diff
-    if (p === "/api/vcs/status" || p === "/api/vcs" || p === "/vcs/status") {
-      return send({ location: { directory }, data: [] })
-    }
+    // GET /api/vcs is Vcs.Info ({ branch: { current, default } }); /api/vcs/status is the file-status array.
+    // Merging them made `location.vcs.info()` return [], and the review extension read [].branch.current.
+    if (p === "/api/vcs" || p === "/vcs") {
+      return send({ location: { directory }, data: { branch: { current: "dev", default: "dev" } } })
+    }
+    if (p === "/api/vcs/status" || p === "/vcs/status") {
+      return send({ location: { directory }, data: [] })
+    }
     if (p === "/api/vcs/diff" || p === "/vcs/diff") return send({ files: [] })
```

替换后的完整片段（可直接粘贴）：

```js
    // GET /api/vcs is Vcs.Info ({ branch: { current, default } }); /api/vcs/status is the file-status array.
    // Merging them made `location.vcs.info()` return [], and the review extension read [].branch.current.
    if (p === "/api/vcs" || p === "/vcs") {
      return send({ location: { directory }, data: { branch: { current: "dev", default: "dev" } } })
    }
    if (p === "/api/vcs/status" || p === "/vcs/status") {
      return send({ location: { directory }, data: [] })
    }
    if (p === "/api/vcs/diff" || p === "/vcs/diff") return send({ files: [] })
```

要点：

- 必须**新增**显式 `/api/vcs` 路由（否则落到 `preview.mjs:368` 的 `{ data: [] }` fallback，问题不变）。
- `branch.current` 取当前分支名；本机 harness 仓库当前分支是 `claude-UI`，但 mock 只是展示用，
  建议用 `{ current: "dev", default: "dev" }`（位于默认分支 → 不显示 Branch 模式），
  或 `{ current: "feature", default: "dev" }` 想预览 Branch 模式时。
- 该改动只影响 preview harness，不进入构建产物，**无需 rebuild**。

### 可选（本崩溃不需要，但建议一并对齐 mock 保真度）

```diff
-    if (p === "/api/vcs/diff" || p === "/vcs/diff") return send({ files: [] })
+    if (p === "/api/vcs/diff" || p === "/vcs/diff") return send({ location: { directory }, data: [] })
```

`vcs.diff` 的真实成功体是 `Location.response(Schema.Array(FileDiff.Info))`
（`packages/protocol/src/groups/vcs.ts:109-124`），而 `{ files: [] }` 会让 `result.data` 为
`undefined`；当前 `diffs()`（`model.ts:234`）用 `?? []` 兜住了，所以不影响本次崩溃，
但一旦以后有文件进入选中/加载路径就会出错。

### 不建议：改 app / 扩展源码

- 真实 API 契约保证 `branch` 存在，按 `AGENTS.md` 的风格不应在内部重复防御性校验；
  这样会掩盖契约违例，而且 107/109/140 三处都要改。
- 改源码还需要 `bun run check` + 重新构建 UI，成本和回归面都远大于改 mock。

## 验证方法

应用上面的 patch 后，在 `C:\sensorsdata\main\program\opencode-android-shell` 下执行：

```powershell
node research\sidebar-projects\tools\preview.mjs `
  --ui dist\sidebar-preview\ui-after `
  --out research\sidebar-projects\notes\_repro `
  --name vcsfix `
  --actions-file research\sidebar-projects\notes\_repro\actions-probe.json `
  > research\sidebar-projects\notes\_repro\report-vcsfix.json 2>&1
```

`actions-probe.json`（`page.evaluate` 会把 `eval` 字符串包成 `(<eval>)()` 执行，
所以每项要写成箭头函数）：

```json
[
  { "wait": 800 },
  { "eval": "() => Array.from(document.querySelectorAll('[data-slot=\"session-panel-frame\"]')).map((e) => ({ text: e.innerText.replace(/\\s+/g, ' ').slice(0, 40), rect: e.getBoundingClientRect().toJSON() }))" },
  { "eval": "() => Array.from(document.querySelectorAll('[data-slot=\"session-review-v2-body\"]')).map((e) => { const r = e.getBoundingClientRect(); return { w: Math.round(r.width), h: Math.round(r.height), frame: e.closest('[data-slot=\"session-panel-frame\"]') ? 'in-frame' : 'no-frame' } })" },
  { "eval": "() => document.body.innerText.includes('变更')" }
]
```

修复后应看到（本次实测值）：

- `steps[]` 中**没有** `console: "error"` 且 `text` 以 `[extension] review` 开头的条目；
  `errors` 为空；`toast` 为 `null`。
- 第二个 eval 返回 **4** 个 frame，顺序/标题为
  `终端`、`更改 Git 变更 尚无文件变更 项目变更将显示在此处`、`浏览器`、`文件`；
  其中“更改”frame 的 rect 为 `{ x: 591, y: 332, width: 340, height: 429 }`
  （修复前只有 3 个 frame，且没有“更改”）。
- 第三个 eval 返回 **2** 个 `session-review-v2-body`，都 `in-frame`
  （修复前是 1 个，位于“文件”frame 复用的 `session-file-panel-v2` 里，不是 review 面板）。
- 第四个 eval 返回 **true**（修复前 false）。
- `report.backendHits` 中出现 `GET /api/vcs/diff`（修复前不会请求它，因为 `diffQuery` 的
  options 在算 queryKey 时先抛错了）。
- 截图（`_repro/vcsfix.png`）中出现“更改”frame，内容为 `Git 变更` / `筛选文件` /
  `暂无文件变更 项目变更将显示在此处`。

若不想用 actions，也可以只看：`report.steps` 无 `[extension] review` 错误 + 截图有“更改”frame。

本次调查留存的对照实验（临时副本，未改共享 harness）：

- 修复前：`_repro/report.json`、`_repro/review-crash.png`
- 只改 `/api/vcs`（最小 patch）：`_repro/report-vcsfix-min.json`、`_repro/vcsfix-min.png`
- 同时改 `/api/vcs` + `/api/vcs/diff`：`_repro/report-vcsfix.json`、`_repro/vcsfix.png`
- DOM 探针对照：`_repro/report-probe-before.json` / `_repro/report-probe-after.json`

## 置信度 / 未确认项

**置信度：高（≈0.95）**

支持证据：

1. 崩溃栈的构建位置（`index-DzJivo7r.js:105:3464`）反查出的表达式与 `model.ts:107` 的
   `vcs()?.branch.current` 逐字一致；
2. 只修改 mock 的 `/api/vcs` 响应形状，`[extension] review …` 错误立即消失，
   且 panel frame 从 3 个变 4 个、“更改”frame 出现、`session-review-v2-empty-changes` 槽位出现；
3. 修复后 `GET /api/vcs/diff` 才开始被请求，符合“崩溃发生在 diffQuery options 求值”的推断；
4. 客户端/协议/Schema 三处交叉确认了 `/api/vcs` 与 `/api/vcs/status` 两个契约的形状差异。

**未确认项 / 需注意：**

1. 构建产物压缩后的列号映射只精确到“该函数体是 `options()`”，未产出 sourcemap 级逐列核对；
   但该函数体内唯一的 `.current` 读取就是 `vcs()?.branch.current`。
2. `dist/sidebar-preview/ui-after` 的构建 commit 未验证（没有 sourcemap 或 build 记录可查）；
   只能按代码内容（`review` model 的 `options` 表达式、`session-review-v2-*` 槽位、
   `packages/app/src/session/panel-grid.tsx` 的 `GRID_PANEL_EXTENSIONS`）确认与当前 checkout 同源。
3. `scripts/verify-android-terminal-release.mjs` / `scripts/verify-android-terminal.mjs` 是否有同样问题
   未测试：它们没有显式 mock `/api/vcs`，fallback 仍是 `{ data: [] }`；如果它们也加载 extension 版 UI，
   理论上会同样崩。建议顺手把主 patch 的 `/api/vcs` 路由也补到这两个脚本（或确认它们的 UI 不走 review model）。
4. 预览里还有其它 mock 形状偏差，与本次崩溃无关但会让预览行为偏离真实：
   - `GET /api/vcs/diff` 返回 `{ files: [] }`（应为 `{ location, data: [] }`）；
   - 浏览器面板显示 `Expected object`（`/api/rpc/.../state` 的 RPC 响应形状问题）；
   - `projectInfo` 恒为 `vcs: true`，预览里无法呈现非 Git 项目分支。
5. 本文所有路径中 `_scratch_root/desktop-parity/checkout/` 为被调查的只读 checkout 前缀；
   `research/`、`dist/`、`scripts/` 均相对 `C:\sensorsdata\main\program\opencode-android-shell`。
