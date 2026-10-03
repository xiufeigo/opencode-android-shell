# 上游 v2 项目编辑弹层对照审计（PROJECT_EDIT_UPSTREAM）

> 任务：判定「Home 项目行 ⋮ → 编辑项目 → `HomeProjectEditDialog` 面板全禁用（未注册本地目录）」是**真缺口**还是**与上游一致的有意设计**。
> 只读上游副本：`_upstream/`（anomalyco/opencode v2 分支）。本文档边做边写，证据以 `文件:行号` + 原文片段为准。
> 审计日期：v97 复核轮次。

---

## 结论

> **真缺口 —— P1。** 我方 `HomeProjectEditDialog.kt:222` 的 `editable = state?.projectID != null && !saving` 是**我方自创的闸门，上游没有**。
> 上游对无 `project.id` 的项目**照常允许改名 / 改图标**，只是把写入目标从「服务器 PATCH」降级为「客户端本地 meta 持久化」（`project-model.ts:41-46`、`project-row.tsx:60-65`）。
> 「未在服务器注册」在上游**不是错误态、不禁用、不提示**，而是一个被正常处理的常态分支。我方把上游的一个**传输层选择**误读成了**可用性开关**。

**严重度定级 P1（非 P0）**：数据未损坏、无崩溃、UI 有解释性文案（`HomeProjectEditDialog.kt:242-250`），但该功能对**所有未注册本地目录项目 100% 不可用**，属功能性完全缺失。

---

## 证据

### 证据 A（决定性）：上游对「无 projectID 的项目」有显式降级分支，落点是**本地 meta**，不是禁用

`_upstream/packages/app/src/settings/workspaces/project-model.ts:37-57`

```ts
37:  const persist = (patch: ProjectPatch, complete: () => void) => {
38:    setStore("saving", (value) => value + 1)
39:    queue = queue
40:      .then(async () => {
41:        if (props.project.id && props.project.id !== "global") {
42:          const project = await serverCtx().sdk.api.project.update({ projectID: props.project.id, ...patch })
43:          serverCtx().sync.project.update(project)
44:          return
45:        }
46:        serverCtx().sync.project.meta(props.project.worktree, patch)
47:      })
48:      .then(complete)
49:      .catch((error: unknown) => {
50:        showToast({
51:          variant: "error",
52:          title: language.t("common.requestFailed"),
53:          description: error instanceof Error ? error.message : language.t("common.requestFailed"),
54:        })
55:      })
56:      .finally(() => setStore("saving", (value) => value - 1))
57:  }
```

**关键点**：
- 判定条件只问「**`id` 是否存在**」，`id == null`（未在服务器注册的本地目录）**不是禁用条件，而是「走本地 meta 存储」的分支**。
- `persist` 被 `saveName` / `saveStartup` / `saveIcon` / `setColor` **无条件**调用，链路上没有任何 `disabled` / `readOnly` 闸门。
- `patch` 的类型是 `ProjectPatch = Pick<ProjectUpdateInput, "name" | "icon" | "commands">`（`project-model.ts:11`），**改名 / 图标 / 启动命令三类改动全部走同一个 `persist`**，因此无 ID 项目**三类全都能改**。

### 证据 B：同样的二分在「重命名」内联编辑器里独立复现一次

`_upstream/packages/app/src/settings/workspaces/project-row.tsx:58-65`

```ts
58:    const context = global.ensureServerCtx(props.server)
59:    const value = name === getFilename(props.project.worktree) ? "" : name
60:    const saved = await (props.project.id && props.project.id !== "global"
61:      ? context.sdk.api.project
62:          .update({ projectID: props.project.id, name: value })
63:          .then((project) => context.sync.project.update(project))
64:      : Promise.resolve(context.sync.project.meta(props.project.worktree, { name: value }))
65:    )
```

同一个 `id && id !== "global"` 三元判定，在**另一个文件、独立函数**里第二次出现。说明这是上游刻意的**双通道设计**，不是单点笔误。

### 证据 C：上游的「本地 meta」结构与我方所需字段完全对得上

`_upstream/packages/app/src/runtime/server/persistence.ts:119-132`

```ts
119: const ProjectMeta = Persistence.struct({
120:   name: Schema.optional(Schema.String),
121:   icon: Schema.optional(
122:     Persistence.struct({
123:       override: Schema.optional(Schema.String),
124:       color: Schema.optional(Schema.String),
125:     }),
126:   ),
127:   commands: Schema.optional(Persistence.struct({ start: Schema.optional(Schema.String) })),
128: })
129:
130: export const ProjectState = Persistence.struct({
131:   value: Schema.optional(ProjectMeta),
132: })
```

`_upstream/packages/app/src/runtime/server/global-sync/types.ts:8`
```ts
8: export type ProjectMeta = NonNullable<typeof ProjectState.Type.value>
```

即上游本地落盘结构 = `{ name?, icon?: { override?, color? }, commands?: { start? } }`。
**改名、图标覆盖、图标颜色、启动命令 四项全在本地可写** —— 覆盖我方弹层的全部字段需求。

### 证据 D：本地 meta 的写入实现（浅合并，非全量覆盖）

`_upstream/packages/app/src/runtime/server/global-sync/child-store.ts:335-351`

```ts
335:  function projectMeta(directory: string, patch: ProjectMeta) {
336:    const key = directoryKey(directory)
337:    const [store, setStore] = ensureChild(directory)
338:    const cached = metaCache.get(key)
339:    if (!cached) return
340:    const previous = store.projectMeta ?? {}
341:    const icon = patch.icon ? { ...previous.icon, ...patch.icon } : previous.icon
342:    const commands = patch.commands ? { ...previous.commands, ...patch.commands } : previous.commands
343:    const next = {
344:      ...previous,
345:      ...patch,
346:      icon,
347:      commands,
348:    }
349:    cached.setStore("value", next)
350:    setStore("projectMeta", next)
351:  }
```

按 `directory`（工作树路径）索引，`icon` / `commands` 做**字段级浅合并**后写回缓存 + store。
即：改名字不会清掉图标，改颜色不会清掉图片。

### 证据 E：本地 meta 的读回路径 —— 无 ID 项目在列表里就是靠它渲染的

`_upstream/packages/app/src/runtime/server/runtime.tsx:139-158`

```ts
139:  function enrich(project: { worktree: string; expanded: boolean }) {
140:    const [childStore] = sync.child(project.worktree, { bootstrap: false })
141:    const projectID = childStore.project
142:    const metadata = projectID
143:      ? sync.data.project.find((x) => x.id === projectID)
144:      : sync.data.project.find((x) => x.worktree === project.worktree)
145:
146:    // Preserve local icon override from per-workspace localStorage cache (childStore.icon).
147:    // Without this, different subdirectories of the same git repo would share the same
148:    // icon from the database instead of using their individual overrides.
149:    const base = {
150:      ...metadata,
151:      ...(!metadata || metadata.id === "global" ? childStore.projectMeta : undefined),
152:      ...project,
153:    }
```

`runtime.tsx:151` 是**读**侧的对称实现：`!metadata || metadata.id === "global"` 时用 `childStore.projectMeta` 覆盖服务器元数据。
注释 `:146` 明确写 `per-workspace localStorage cache` —— **本地落点是客户端 localStorage，不是服务器**。

### 证据 F：上游编辑 UI 上**根本不存在** disabled / readOnly 闸门

对 `_upstream/packages/app/src/settings/workspaces/` 全目录 grep `disabled|readOnly|aria-disabled` 共 12 处，**`project.tsx`（编辑面板本体）命中 0 处**。唯一与编辑相关的禁用是：

`_upstream/packages/app/src/settings/workspaces/project-row.tsx:103`
```tsx
103:                disabled={store.editor?.saving}
```

—— 只看 `saving`（写入进行中），**完全不看 `project.id`**。其余 `disabled` 命中全部在 `workspaces.tsx`（worktree 事务）与 `project-extensions.tsx`（LSP/MCP 开关），与本议题无关。

### 证据 G：我方闸门原文（被判定为偏离点）

`app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectEditDialog.kt:220-226`

```kotlin
220:                else -> {
221:                    val state = original
222:                    val editable = state?.projectID != null && !saving
223:                    EditFields(
224:                        draft = currentDraft,
225:                        folderName = state?.folderName.orEmpty(),
226:                        editable = editable,
```

`app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectEditDialog.kt:262-272`

```kotlin
262:                val state = original
263:                val currentPatch = draft?.let { d -> state?.let { HomeProjectEdit.patchFor(it, d) } }
264:                EditDialogButton(
...
266:                    enabled = state?.projectID != null && currentPatch?.isEmpty == false && !saving,
...
269:                        val projectID = state?.projectID ?: return@EditDialogButton
270:                        ...
272:                            val result = withContext(Dispatchers.IO) { api.save(projectID, patch) }
```

`return@EditDialogButton` 早退 —— **无 ID 时保存路径在代码层根本不可达**，不是「失败」而是「从未尝试」。

### 证据 H：上游「编辑项目」入口 → 落到同一个无闸门面板

`_upstream/packages/app/src/home/projects/view.tsx:742-744`
```tsx
742:              <Menu.Item onSelect={() => props.onEditProject(props.server, props.project)}>
743:                {props.language.t("common.edit")}
744:              </Menu.Item>
```

`_upstream/packages/app/src/home/projects/controller.tsx:135-140`
```ts
135:      edit: (conn: ServerConnection.Any, project: LocalProject) => {
136:        settings.openProject({
137:          server: ServerConnection.key(conn),
138:          project: project.worktree,
139:        })
140:      },
```

`_upstream/packages/app/src/settings/surface.tsx:174-184`
```ts
174:      openProject(input: { server: string; project: string; tab?: SettingsProjectTab }) {
175:        show(
176:          {
177:            type: "project",
178:            ...input,
179:            parent: servers().length > 1 ? "server" : "root",
180:            tab: input.tab ?? "general",
181:          },
182:          false,
183:        )
184:      },
```

`_upstream/packages/app/src/settings/surface.tsx:24`
```ts
24: export type SettingsProjectTab = "general" | "workspaces" | "extensions"
```

→ 上游 Home 的「编辑项目」**不是独立弹窗**，而是跳到 **Settings → 项目 → general 标签页**，即 `project.tsx` 的 `SettingsProjectGeneral`（同一份无闸门面板）。菜单项标题用的是通用 `common.edit`，不存在「编辑项目」这一专用文案。

---

## Q1–Q5 逐条作答

### Q1 上游是否**允许**编辑一个「未在服务器注册 / 无 projectID / 本地目录」的项目（改名、改图标）？

**答：允许。改名、改图标颜色、改图标图片全部允许，无任何限制。**

- 判定只在 `project-model.ts:41` 与 `project-row.tsx:60` 两处，且**只用于选择写入通道**，不否决写入。
- `project.id` 在类型上就是可选的 —— `_upstream/packages/app/src/shell/state/layout.tsx:50`：
  ```ts
  50: export type LocalProject = Partial<Project> & { worktree: string; expanded: boolean }
  ```
  `Partial<Project>` 使 `id` 可为 `undefined`，这是**常态建模**，不是异常。
- 无 ID 分支写入的 `ProjectMeta` 覆盖 `name` + `icon{override,color}`（证据 C），正好是 Q1 问的「改名、改图标」。

### Q2 上游用**什么条件**决定编辑表单是否禁用（enabled/disabled）？条件表达式原文是什么？

**答：没有这个条件。上游编辑表单不存在 enabled/disabled 判定。**

- 原文检索结果：`_upstream/packages/app/src/settings/workspaces/project.tsx` 全文 169 行，对 `disabled|readOnly|aria-disabled` **命中 0 次**。
- 面板内唯一的「状态」表达是 busy 指示，`project.tsx:43`：
  ```tsx
  43:      <div class="settings-tab-body project-settings-general" aria-busy={model.store.saving > 0}>
  ```
  —— 仅 `aria-busy`，且条件是 `saving > 0`（写入进行中），**与 `project.id` 无关**。
- 唯一在编辑路径上的 `disabled` 是 `project-row.tsx:103` `disabled={store.editor?.saving}`，同样只看 `saving`。
- 唯一存在的 `id` 判定表达式是**传输层三元**（不是可用性开关）：
  ```ts
  project.id && project.id !== "global" ? <走服务器 PATCH> : <走本地 meta>
  ```
  **这就是我方误读的关键**：我方把「选哪个写入通道」的条件，提升成了「能不能编辑」的开关。

> 上游把 `id === "global"` 与 `id == null` 并列为「不能 PATCH」的一类 —— 说明**无 ID 是上游的既定常态分支**，不是上游会主动禁用的异常态。

### Q3 上游对「未注册目录」这种状态显示什么？是否会显示类似「未在服务器注册」的提示？

**答：什么都不显示。零提示、零降级文案、零徽标。**

- 对 `_upstream/packages/app/src/runtime/i18n/en.ts` grep 关键词 `notRegistered|unregistered|not registered|notRegister|serverRegistered|registered`，**全文件仅 1 处命中**：
  ```
  en.ts:1054:  "settings.about.trademark": "OpenCode is a registered trademark of Anomaly Innovations, Inc.",
  ```
  —— 商标声明，与项目注册状态无关。
- `project.tsx` 全文无任何条件渲染的警示块；面板对有 ID / 无 ID 项目**渲染完全相同**。
- 唯一与服务器身份相关的 UI 是 `project.tsx:34-38` 的**多服务器切换链接**（`servers().length > 1` 时显示当前服务器名），属于导航而非状态告警。

> 我方 `HomeProjectEditDialog.kt:242-250` 的 `oc_home_project_edit_missing`（「该项目未在服务器注册」）在**上游无对应物**，属我方自创文案。

### Q4 上游的保存动作调用的是哪个 API（HTTP 方法与路径）？对无 ID 的项目它怎么办？

**答：服务器侧是 `PATCH /api/project/:projectID`；对无 ID 的项目上游【根本不调用该 API】，改写客户端本地持久化。**

**服务器路由** —— `_upstream/packages/protocol/src/groups/project.ts:6,7,22-34`：
```ts
 6: const root = "/api/project"
 7: const UpdatePayload = Schema.Struct(Struct.omit(Project.UpdateInput.fields, ["projectID"]))
...
22:     HttpApiEndpoint.patch("project.update", `${root}/:projectID`, {
23:       params: { projectID: Project.ID },
24:       payload: UpdatePayload,
25:       success: Project.Info,
26:       error: ProjectNotFoundError,
27:     }).annotateMerge(
28:       OpenApi.annotations({
29:         identifier: "project.update",
30:         summary: "Update project",
31:         description: "Update the project canonical directory, display metadata, and workspace commands.",
32:       }),
33:     ),
34:   )
```

- **方法**：`PATCH`；**路径**：`/api/project/{projectID}`
- **请求体**：`Project.UpdateInput` 去掉 `projectID`（因为已在路径里）= `{ canonical?, name?, icon?, commands? }`
  （`_upstream/packages/schema/src/project.ts:50-56`）
- **响应**：`Project.Info`；**错误**：`ProjectNotFoundError`

**服务器处理** —— `_upstream/packages/server/src/handlers/project.ts:10-22`：
```ts
10:    .handle("project.update", (ctx) =>
11:      Project.Service.use((project) =>
12:        project.update({ ...ctx.payload, projectID: ctx.params.projectID }).pipe(
13:          Effect.mapError(
14:            () =>
15:              new ProjectNotFoundError({
16:                projectID: ctx.params.projectID,
17:                message: `Project not found: ${ctx.params.projectID}`,
18:              }),
19:          ),
20:        ),
21:      ),
22:    ),
```

**触发时机**：上游**逐字段 blur 自动保存，无保存按钮**。
`project.tsx:58` `onBlur={model.saveName}`、`project.tsx:152` `onBlur={model.saveStartup}`、`project.tsx:119` 色板点击即刻 `model.setColor(...)`。
e2e 回归用例证实了这条链路 —— `_upstream/packages/app/e2e/regression/project-autosave.spec.ts:88`：
```ts
88:      (request) => request.method() === "PATCH" && new URL(request.url()).pathname === `/api/project/${project.id}`,
```

**对无 ID 项目的处置**：走 `sync.project.meta(worktree, patch)`（证据 A/B/D），**本地浅合并写入**，不报错、不引导注册、不阻断。

### Q5 如果上游允许本地编辑：改动应该落在哪里（本地 HomeProjectStore 还是服务器）？给出上游对应逻辑。

**答：落在客户端本地持久化（等价于我方 `HomeProjectStore` 侧），不落服务器。**

上游对应逻辑三段：

1. **入口判定**（`project-model.ts:41`）：`id && id !== "global"` → 服务器；否则 → 本地。
2. **写入**（`global-sync/child-store.ts:335-351`，证据 D）：按工作树路径索引，`icon` / `commands` 字段级浅合并，写回 `metaCache` + `store.projectMeta`。
3. **读回**（`runtime.tsx:151`，证据 E）：列表渲染时 `!metadata || metadata.id === "global"` 用 `childStore.projectMeta` 覆盖服务器元数据。

落盘载体是 `ProjectState`（`persistence.ts:130-132`）这一客户端 `Persistence` 结构；`runtime.tsx:146` 注释确认其宿主为 **per-workspace localStorage**。

> **映射到我方**：`project.update` 分支继续走 `V2Api`（我方 `V2ApiCatalogExtras.kt:50` 已正确记录 `PATCH /api/project/{projectID}`）；
> **缺失的是无 ID 分支** —— 我方需要在 `projectID == null` 时把 patch 写入本地项目存储（`HomeProjectStore` / `data` 层已有的 `oc_home_projects` 记录），
> 并在 Home 列表渲染时用本地 meta 覆盖，使改动可见。
> 注意 `HomeProjectEdit.kt:28-30` 的现有注释已承认该缺口（"have nowhere to PATCH … keeps the row honest"），但结论方向与上游相反 —— 上游是**给无 ID 项目一条本地写通道**，而非让对话框闭嘴。

---

## 字段/动作差异清单

> 仅列差异，**未改动任何代码**。上游编辑面板 = `SettingsProjectGeneral`（`project.tsx`）+ `createEditProjectModel`（`project-model.ts`），共 3 个标签页（`surface.tsx:24`：`general | workspaces | extensions`）。

### A. `general` 标签页（我方弹层对标的正是这一页）

| # | 上游字段 / 动作 | 上游出处 | 我方 `HomeProjectEditDialog` 现状 | 差异 |
|---|---|---|---|---|
| A1 | 项目名 `TextInput` | `project.tsx:50-59` | 有（`Draft.name`） | 一致 |
| A2 | 名称等于文件夹名时发送 `""` 清除 | `project-model.ts:60` | 有（`HomeProjectEdit.kt` 注释确认） | 一致 |
| A3 | 图标上传按钮（`accept="image/*"`） | `project.tsx:67-100` | 有（SAF 选图） | 一致 |
| A4 | **拖拽上传图标**（`onDrop` / `onDragOver` / `onDragLeave` + `dragOver` 高亮态） | `project.tsx:72-77`、`project-model.ts:108-120` | **缺** | 缺 1 项动作 |
| A5 | **点击已上传图标即移除**（hover + click → 清空 `iconOverride`） | `project-model.ts:125-132` | **缺** | 缺 1 项动作 |
| A6 | 图标颜色色板（`PROJECT_AVATAR_VARIANTS` 全量变体） | `project.tsx:108-134` | 有（`Draft.color`） | 一致 |
| A7 | **色板仅在「无自定义图片」时显示**（`<Show when={!model.store.iconOverride}>`） | `project.tsx:103` | **缺该条件** | 缺 1 条互斥规则 |
| A8 | worktree 启动命令 `Textarea` | `project.tsx:144-153` | 有（`Draft.startup`） | 一致 |
| A9 | 启动命令变量提示（`$UPPER_SNAKE` 高亮为 `<code>`） | `project.tsx:154-157, 165-169` | **缺** | 缺 1 项提示 |
| A10 | **逐字段 blur 自动保存（无保存按钮）** | `project.tsx:58, 152`；`e2e/regression/project-autosave.spec.ts` | 我方为**显式「保存」按钮** | 交互模型不同（我方为有意简化，非缺陷） |
| A11 | **多服务器时显示当前服务器切换链接** | `project.tsx:34-38` | **缺** | 缺 1 项动作 |
| A12 | 并发写队列（`queue = queue.then(...)` 串行化 + `saving` 计数） | `project-model.ts:35, 38, 56` | 我方单飞保存 | 我方更简单，非缺陷 |
| A13 | 写失败 toast（`common.requestFailed`） | `project-model.ts:49-55` | 有 | 一致 |
| A14 | 「保存中回退」不丢最终值（e2e 覆盖） | `project-model.ts:61-62` + `project-autosave.spec.ts` | 未验到 | 未验到 |

### B. 我方多出、上游 `general` 页没有的

| # | 我方项 | 结论 |
|---|---|---|
| B1 | 显式「保存」按钮（`HomeProjectEditDialog.kt:257-266`） | 上游改为 blur 自动保存（见 A10） |
| B2 | 「该项目未在服务器注册」提示块（`:242-250`） | **上游无对应物**（Q3），属我方自创 |
| B3 | 禁用态点击 Toast（`:234-240`） | **上游无对应物** —— 上游从不产生禁用态 |
| B4 | 「重试」按钮（`:206-210`） | 上游通用 `common.requestFailed` toast，不做面板内重试 |

### C. 上游另两个标签页（我方完全没有对应面）

| # | 标签页 / 能力 | 上游出处 | 说明 |
|---|---|---|---|
| C1 | `workspaces` —— worktree 列表 | `surface.tsx:24`、`workspaces.tsx` | 含增删 worktree、`transaction` 态禁用 |
| C2 | `extensions` —— LSP / MCP 开关 | `surface.tsx:24`、`project-extensions.tsx` | 见 `project-lsp.ts` |
| C3 | 菜单动作：在文件管理器中打开 | `project-row.tsx:180-196` | 上游限定 `platform === "desktop"`，Android 不适用 |
| C4 | 菜单动作：关闭项目（`projects.close(worktree)`） | `project-row.tsx:198-206` | 需确认我方是否有等价项 |

---

## 搜索过程

### 工具与范围
- 检索工具：`glob`（按路径）、`grep`（ripgrep，按内容）。全程**只读** `_upstream/`，**未修改** `app/` 下任何源码。
- 显式排除 `_upstream/node_modules/**`（初次 glob 命中 133 条，其中约 60 条为 node_modules 噪音，已过滤）。

### 步骤 0：上游目录结构
`_upstream/packages/` 共 33 个包：
`ai, app, cli, client, codemode, console, containers, core, desktop, effect-drizzle-sqlite, enterprise, function, http-recorder, httpapi-codegen, identity, latex, merman, plugin, plugin-browser, posts, protocol, schema, script, sdk, server, session-ui, simulation, stats, storybook, theme, tui, ui, util, web`

### 步骤 1：glob 定位候选文件
- 模式：`_upstream/**/*project*`（排除 node_modules 后 70+ 条）
- 关键命中：
  - `_upstream/packages/app/src/settings/workspaces/project.tsx` ← **编辑面板本体**
  - `_upstream/packages/app/src/settings/workspaces/project-model.ts` ← **模型 / 保存逻辑（决定性证据）**
  - `_upstream/packages/app/src/settings/workspaces/project-row.tsx` ← **行内重命名（第二处证据）**
  - `_upstream/packages/app/src/settings/workspaces/projects.tsx`、`project.css`、`project-lsp.ts`、`project-extensions.tsx`
  - `_upstream/packages/app/src/shell/layout/project-icon.tsx`、`project-avatar-state.ts`
  - `_upstream/packages/core/src/project.ts`、`_upstream/packages/schema/src/project.ts`
  - `_upstream/packages/server/src/handlers/project.ts`、`_upstream/packages/protocol/src/groups/project.ts`
  - `_upstream/packages/app/src/runtime/server/{sync.tsx,persistence.ts,runtime.tsx}`、`global-sync/{child-store.ts,types.ts}`
  - `_upstream/packages/app/e2e/regression/project-autosave.spec.ts`

### 步骤 2：grep 搜索词（全部实际执行）
| 关键词 | 范围 | 结果 |
|---|---|---|
| `edit\|Edit\|rename\|Rename` | `app/src/settings/workspaces` (`*.tsx`) | 33 处，定位 `project.tsx` + `project-row.tsx` |
| `project\.meta\|meta:\|ProjectMeta\|projectMeta` | `app/src` (`*.ts*`) | 95 处，定位 `sync.tsx:260`、`child-store.ts:335`、`persistence.ts:119` |
| `project\.update\|projectID` | `protocol/src/groups` (`*.ts`) | 25 处，定位 `project.ts:22` |
| `notRegistered\|unregistered\|not registered\|notRegister\|serverRegistered\|registered` | `app/src/runtime/i18n/en.ts` | **1 处**（商标声明）→ Q3 定案 |
| `LocalProject` | `app/src/shell/state` (`*.ts*`) | **1 处** `layout.tsx:50` |
| `id === "global"\|"global"` | `app/src/runtime/server` (`*.ts*`) | 1 处 `runtime.tsx:151` |
| `openProject` | `app/src/settings` (`*.ts` / `*.tsx`) | 3 处，定位 `surface.tsx:174`、`shell.tsx:298/380` |
| `SettingsProjectTab\|SettingsProjectLsp\|SettingsProjectExtensions` | `app/src/settings/workspaces` (`*.tsx`) | 0 处（Tab 类型在 `settings/surface.tsx`，非 workspaces 目录） |
| `SettingsProjectTab =` | `app/src/settings` (`*.ts*`) | 1 处 `surface.tsx:24` |
| **`disabled\|readOnly\|aria-disabled`** | `app/src/settings/workspaces` (`*.tsx`) | **12 处，`project.tsx` 命中 0** → Q2 定案 |
| `Menu\|onSelect\|rename\|Rename\|openSettings\|settings` | `app/src/home/projects` (`*.tsx`) | 73 处，定位 `view.tsx:742` |
| `openProject` | `app/src/settings/surface.tsx` | 确认 `view → settings` 路由 |
| `project\.meta\|meta` 复验 | `child-store.ts` / `persistence.ts` | 确认本地合并与落盘结构 |

### 步骤 3：全文通读
- `app/src/settings/workspaces/project.tsx`（169 行，全文）
- `app/src/settings/workspaces/project-model.ts`（137 行，全文）
- `app/src/settings/workspaces/project-row.tsx`（230 行，全文）
- `app/src/protocol/src/groups/project.ts`（40 行，全文）
- `app/src/server/src/handlers/project.ts`（23 行，全文）
- `app/src/schema/src/project.ts`（60 行，全文）
- `app/src/runtime/server/persistence.ts:1-40, 100-136`
- `app/src/runtime/server/global-sync/child-store.ts:320-384`
- `app/src/runtime/server/global-sync/types.ts`（81 行，全文）
- `app/src/runtime/server/runtime.tsx:130-169`
- `app/src/runtime/server/sync.tsx:252-271`
- `app/src/shell/state/layout.tsx:50`
- `app/src/home/projects/controller.tsx:125-154`
- `app/src/home/projects/view.tsx`（grep 定位 `:742`）
- `app/src/settings/surface.tsx:24, 168-189`
- `app/e2e/regression/project-autosave.spec.ts`（109 行，全文）

### 步骤 4：我方对照（只读）
- `app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectEditDialog.kt:205-272`
- `app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectEdit.kt:1-60`
- `app/src/main/java/com/xiufeigo/opencodeshell/v2/V2ApiCatalogExtras.kt:10, 50`（我方已正确记录 `PATCH /api/project/{projectID}`）
- `app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectApi.kt:76, 117-121`

### 未找到 / 未验到（不猜）
- **未找到**任何「未注册项目不可编辑」的上游依据：`project.id == null` 在上游**只**出现在传输层三元判定中。
- **未找到**任何「未在服务器注册」的上游 i18n 文案（en.ts 全文件仅商标声明 1 处命中）。
- **未验到**上游对本地 meta 的**容量/大小限制**（`icon.override` 是 data URL，`persistence.ts` 无长度约束 schema）—— localStorage 配额风险未在上游代码中体现。
- **未验到**上游 `ProjectState` 的实际 storage key 与序列化实现细节（`@/runtime/persistence/schema` 未展开读）。
- **未验到**A14（保存中回退）在 `general` 页以外标签页的行为。

---

## 附：给修复方的落点建议（只读结论，未改码）

1. **删除** `HomeProjectEditDialog.kt:222` 的 `projectID != null` 闸门与 `:266` 的同名条件（保留 `!saving`）。
2. **新增** `HomeProjectApi.save` 的无 ID 分支，落到本地项目存储（对齐 `child-store.ts:335-351` 的浅合并语义：改 name 不清 icon，改 color 不清 override）。
3. **让 Home 列表渲染时用本地 meta 覆盖**（对齐 `runtime.tsx:151`），否则改动写了但看不见。
4. **删除或改写** `oc_home_project_edit_missing` 提示（`:242-250`）与禁用态 Toast（`:234-240`）—— 上游无此二者。
5. 可选补齐 A4/A5/A7/A9（拖拽上传、点击移除、色板互斥、变量提示）以进一步收敛与上游的差异。
