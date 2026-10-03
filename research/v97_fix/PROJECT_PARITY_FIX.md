# v97 P2/P3 修复：项目编辑面板与上游的剩余差距（PROJECT_PARITY_FIX）

> 任务：收敛 ①「刷新工作区」对未注册项目的拒绝、② A5 点击图标移除、③ A9 启动命令变量提示。
> 上游只读副本：`_upstream/`（anomalyco/opencode v2）。证据以 `文件:行号` + 原文为准。
> 修复者：fix-parity。本轮状态：**源码 + 纯 JVM 闸门全绿**，真机复验未做。

---

## 结论速览

| # | 项 | 决定 | 一句话 |
|---|---|---|---|
| ① | 「刷新工作区」对未注册项目弹「未在服务器注册」 | **改** | 上游根本没有这个状态：刷新是**服务器项目**才有的动作，且失败一律 `.catch(() => undefined)` 静默。改为**未注册行不显示该菜单项** + 残留分支**静默 no-op**。 |
| ② | A5 点击已上传图标即移除 | **改** | 上游 `iconClick()` 一个按钮既选图又清图。触屏无 hover，把分叉落到图片本身：**已上传 → 点击清除**，空位不点（避免与「选择图片」重复）。保留「清除图片」按钮。 |
| ③ | A9 `$VAR` 变量提示 | **改** | 文案是**服务器事实**（`core/src/worktree.ts:244-245` 确实导出这两个变量），收益不低。三语同步新增 2 个 key。 |

---

## ① 「刷新工作区」对未注册项目仍被拒绝 → **改**

### 上游证据

**证据 1（决定性）：上游的「刷新」根本不是项目菜单项，它挂在「服务器项目」上。**

`_upstream/packages/app/src/session/timeline/session-workspace-menu.tsx:15-19`
```tsx
export function SessionWorkspaceMenu(props: {
  eligible?: boolean
  sessionID: string
  project: Project
  directory: string
```

`_upstream/packages/app/src/runtime/server/types.ts:3-6`
```ts
export type Project = Omit<ProjectListOutput[number], "canonical"> & {
  worktree: string
  worktrees: WorktreeDirectory[]
}
```

→ 形参类型是 `Project`（**不是** `LocalProject`）。`LocalProject` 才是上游对「本地目录」的建模（`shell/state/layout.tsx:50` `Partial<Project> & { worktree, expanded }`，`id` 可缺）；而这里用的是**服务器目录列表导出的 `Project`，`id` 必然存在**。所以上游在类型层面就走不到「没有 projectID 的目录」这个状态。

**证据 2：上游对这个调用是静默的，任何失败都不提示。**

`session-workspace-menu.tsx:49-58`
```tsx
  const onOpenChange = (open: boolean) => {
    props.onOpenChange?.(open)
    if (!open) return
    const sdk = serverSDK
    void sdk.api.worktree
      .list({ projectID: props.project.id })
      .then(update)
      .then(() => sdk.api.worktree.refresh({ projectID: props.project.id }))
      .catch(() => undefined)
  }
```

→ `.catch(() => undefined)`：**无 toast、无错误块、无降级文案**。`showToast` 虽在 `:11` 被 import，但这条链路上**一次都没用**。

**证据 3：上游 Home 行菜单里没有「刷新工作区」这个项。**

`_upstream/packages/app/src/home/projects/view.tsx:732-763` 逐项列出：`session.new` → `Show(canImportSession)` → `dialog.project.edit.title` → `Show(canRevealProject)` → `clearNotifications` → `common.close`。**没有 refresh**。

> 这意味着我方「刷新工作区」是**自创**的上游没有的动作（我方自述见 `HomeScreen.kt:661`）。既然自创，就更没有理由给它配一个上游不存在的拒绝态。

**证据 4：被审计标为「我方自创文案」的那句提示，上游 i18n 根本没有对应 key。**

审计 Q3（`research/v97_audit/PROJECT_EDIT_UPSTREAM.md:273-277`）：对 `_upstream/packages/app/src/runtime/i18n/en.ts` grep `notRegistered|unregistered|not registered|…`，**全文件仅 1 处命中**，是商标声明 `en.ts:1054`。

**证据 5：`oc_home_project_edit_missing` 在本仓库的最后两个用户，本轮全部消除。**

修复前 grep（`app/src` 全量）：
```
app\src\main\java\com\xiufeigo\opencodeshell\ui\home\HomeScreen.kt:713: ProjectWrite.NotRegistered -> context.getString(R.string.oc_home_project_edit_missing)
app\src\main\res\values\strings.xml:418: <string name="oc_home_project_edit_missing">This project isn\'t registered on the server</string>
app\src\main\res\values-zh-rCN\strings.xml:345: <string name="oc_home_project_edit_missing">该项目未在服务器注册</string>
app\src\main\res\values-zh-rTW\strings.xml:345: <string name="oc_home_project_edit_missing">該專案未在伺服器註冊</string>
```
（弹层那一处在上一轮 `HomeProjectEditDialog.kt` 迁移时已删。）

### 决定：改，按上游语义分两半落地

上游的两条规则各对应一个改法：

1. **「只在有服务器项目时提供这个动作」** → 行菜单按 `serverID` 隐藏菜单项（不是禁用、不是点了再拒绝）。
2. **「失败静默」** → 残留分支静默返回，不弹任何 toast。

选「隐藏菜单项」而不是「静默 no-op」单独用，是因为静默 no-op 会让用户点了个**什么都没有**的菜单项 —— 那是把「弹错文案」换成「点了没反应」，两个坏。隐藏让状态在**动作发生前**就不可见，与上游「该处不存在此动作」一致。

**为什么不选「用本地路径重试」**：`POST /api/worktree/refresh` 的入参是 `projectID`（`worktree.refresh({ projectID })`），没有路径形态的调用；「先注册再刷新」要引入一次写副作用（`api.location`），超出本次范围，且上游对未注册目录**根本不存在**这个动作，不存在需要重试的语义。

### 改动 diff

**1. `app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectEdit.kt`** — 新增纯谓词：

```diff
+    /**
+     * Whether 「刷新工作区」 belongs on this row's menu at all.
+     *
+     * Upstream's refresh is not a project-menu item to be gated — it is a side
+     * effect of **opening** the session workspace menu, on a menu typed on a
+     * server project (`session-workspace-menu.tsx:18` `project: Project`, i.e.
+     * `ProjectListOutput[number]` with its `id` — `runtime/server/types.ts:3-6`).
+     * Upstream therefore never renders the action anywhere the server has no
+     * project for this directory, and treats the call as a best-effort refresh
+     * whose failure is swallowed (`.catch(() => undefined)`, `:53-57`).
+     *
+     * Two rules fall out, and both are honoured here rather than refused with a
+     * message: the row menu **hides** the item when the row has no server
+     * project, and a call that still finds none **does nothing quietly** instead
+     * of toasting 「该项目未在服务器注册」. …
+     * Same predicate as [serverProjectID] being non-null — written out because
+     * the two live on opposite sides of a tap …
+     */
+    fun refreshApplies(serverID: String?): Boolean = serverProjectID(serverID) != null
```

**2. `app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectsList.kt:516`** — 菜单项进 gate：

```diff
-            DropdownMenuItem(
-                text = {
-                    Text(
-                        text = stringResource(R.string.oc_home_project_refresh_worktrees),
-                        color = c.textBase,
-                    )
-                },
-                onClick = onRefresh,
-            )
+            // Upstream's 「Refresh worktrees」 is not a project-menu item at
+            // all: it is a side effect of **opening** the session workspace
+            // menu, typed on a server project (`session-workspace-menu.tsx:18`
+            // `project: Project`, whose `id` comes from `ProjectListOutput[number]`
+            // — `types.ts:3-6`), and its failure is swallowed
+            // (`.catch(() => undefined)`, `:53-57`). …
+            if (HomeProjectEdit.refreshApplies(project.serverID)) {
+                DropdownMenuItem(
+                    text = {
+                        Text(
+                            text = stringResource(R.string.oc_home_project_refresh_worktrees),
+                            color = c.textBase,
+                        )
+                    },
+                    onClick = onRefresh,
+                )
+            }
```

`project.serverID` 由 `HomeProjects.merge` 填（`HomeProjects.kt:114` `serverID = HomeProjectEdit.serverProjectID(project.id)`），即 `GET /api/project` 自己对该行的回答 —— 菜单里**不发网络请求**。

**3. `app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt:708`** — 残留分支静默：

```diff
     val onRefreshWorktrees: (HomeProjects.Project) -> Unit = { project ->
         projectActionScope.launch {
             val result = withContext(Dispatchers.IO) { projectApi.refreshWorktrees(project.path) }
+            // … 上游证据注释 …
+            if (result is ProjectWrite.NotRegistered) return@launch
             val message = when (result) {
                 ProjectWrite.Ok -> context.getString(R.string.oc_home_project_worktrees_refreshed)
-                ProjectWrite.NotRegistered -> context.getString(R.string.oc_home_project_edit_missing)
                 is ProjectWrite.Failed -> context.getString(
                     R.string.oc_home_project_worktrees_refresh_failed,
                     projectFaultText(context, result.fault),
                 )
+                ProjectWrite.NotRegistered -> return@launch
             }
             Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
         }
     }
```

`when` 仍**穷举**（`NotRegistered` 分支保留为 `return@launch`），不是靠提前 return 破坏穷举性。

**4. `app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectEdit.kt`** — `ProjectWrite.NotRegistered` 的 KDoc 从「拒绝」改为「不是错误态」，并写明上游证据：

```diff
 sealed interface ProjectWrite {
     data object Ok : ProjectWrite
-    /** No server project owns this worktree — nothing to PATCH or refresh. */
+    /**
+     * No server project owns this worktree, so there is nothing to
+     * `POST /api/worktree/refresh` against.
+     *
+     * **Not an error state**, and deliberately not reported as one. …
+     * `.catch(() => undefined)` (`:57`) — **silent**, no toast …
+     */
     data object NotRegistered : ProjectWrite
     data class Failed(val fault: ProjectFault) : ProjectWrite
 }
```

**5. `HomeProjectApi.refreshWorktrees` 未改** —— API 层语义正确（上游的 `worktree.refresh` 仍需 `projectID`），本轮只改**呈现**。新 pin 专门断言这一点（`theOutcomeItselfSurvivesTheQuietPresentation`）。

**6. `app/src/test/java/com/xiufeigo/opencodeshell/HomeProjectLocalMetaV97Test.kt`** — 上一轮 `theNotRegisteredCopyIsGoneFromThePanel` 有一条**正向断言要求** `HomeScreen` 继续用它（当时的理由是「弹层删了，刷新 toast 还在，key 不能删」）。本轮消除了第二个用户，该断言**必须**跟着改，否则钉住的是本轮要修的行为。已改为**反向断言 + 静默断言**（不是删掉，是换方向并加强）：

```diff
-    fun theNotRegisteredCopyIsGoneFromThePanel() {
-        assertFalse(dialog.contains("oc_home_project_edit_missing"))
-        assertTrue(
-            "the key keeps its other user — dropping it entirely would break the refresh toast",
-            home.contains("ProjectWrite.NotRegistered -> context.getString(R.string.oc_home_project_edit_missing)"),
-        )
+    fun theNotRegisteredCopyIsGoneFromThePanelAndFromTheRefreshToast() {
+        // …（保留原 Q3 证据，新增 ① 的上游证据与 superseded 说明）…
+        assertFalse(dialog.contains("oc_home_project_edit_missing"))
+        assertFalse(
+            "the refusal toast is gone — upstream never shows this state at all",
+            home.contains("oc_home_project_edit_missing"),
+        )
+        assertTrue(home.contains("if (result is ProjectWrite.NotRegistered) return@launch"))
     }
```

**未做（有意）**：没有从三份 `strings.xml` 删除 `oc_home_project_edit_missing` 这个 key。它现在**零引用**，但删除资源 key 属于独立的、更大的一步（`StringsLocaleParityTest` 把三份 key 集合当契约），本轮不夹带。新 pin `theSelfInventedCopyIsReferencedNowhereInThePanelHome` 断言「三处源码均不引用它」且「三语文案仍都在」，把这个状态钉住。

---

## ② A5：点击已上传的图标即移除 → **改**

### 上游证据

`_upstream/packages/app/src/settings/workspaces/project-model.ts:125-135`
```ts
    iconClick() {
      if (store.iconOverride && store.iconHover) {
        setStore("iconOverride", "")
        saveIcon(store.color, "")
        return
      }
      iconInput?.click()
    },
    setIconInput(input: HTMLInputElement) {
      iconInput = input
    },
```

`_upstream/packages/app/src/settings/workspaces/project.tsx:67-93`（同一个 `<button>`，hover 决定 tap 的语义与叠加图标）
```tsx
            <button
              data-action="settings-project-icon"
              type="button"
              aria-label={language.t("dialog.project.edit.icon.alt")}
              class="project-settings-icon"
              classList={{ "project-settings-icon--active": model.store.dragOver }}
              onMouseEnter={() => model.setStore("iconHover", true)}
              onMouseLeave={() => model.setStore("iconHover", false)}
              onDrop={(event) => model.drop(event)}
              onDragOver={(event) => model.dragOver(event)}
              onDragLeave={() => model.dragLeave()}
              onClick={() => model.iconClick()}
            >
              …
              <span classList={{ "project-settings-icon-overlay": true, visible: model.store.iconHover }}>
                <Icon name={model.store.iconOverride ? "close" : "share"} />
              </span>
            </button>
```

关键三点：
1. **一个按钮，两个动作**（选图 / 清除），不是两个按钮。
2. 分叉条件是 `store.iconOverride && store.iconHover`；`iconHover` 由 `onMouseEnter/onMouseLeave` 驱动（`:73-74`），**纯粹是桌面 hover 的产物**。
3. 清除时 `saveIcon(store.color, "")` —— **只清 override，保留 color**（`:78` `{ icon: { color: color ?? "", override: override ?? "" } }`，此处 `color` 原样回传）。

### 决定：改，且把分叉从 hover 移到「图片本身」

`iconHover` 在触屏上不存在，无法照搬。落到图片本身是同一棵判定树的触屏形态：

| 图标状态 | 上游（hover 时 tap） | 本轮（tap） |
|---|---|---|
| 有图 | `Clear`（`iconClick` 第一个分支） | **Clear** |
| 无图 | `Pick`（`iconInput.click()`） | **不响应**（见下） |

**为什么无图时不响应**：上游无图时点图标 = 打开文件选择器。我方已经有一个**带文字的「选择图片」按钮**做同一件事；让 32 dp 的头像也变成一个无标签的选择器，等于凭空多一个无障碍标签要编（新增用户可见字符串），而收益是零。所以无图时该区域保持惰性 —— **不是**丢掉上游的 `Pick` 分支，而是它在本 shell 的载体（「选择图片」按钮）已经存在且更好用。

**为什么保留「清除图片」按钮**：任务允许合并或保留。保留的理由是可发现性 —— 手机上 32 dp 头像上一个无标签 ✕，没有用户会想到它是清除。点击图片是**增补**的、符合上游手势语义的入口；按钮是**原有的、可发现的**入口。两者调用**同一个纯函数** `HomeProjectEdit.draftWithoutIcon(draft)`，不存在两套清除逻辑。

**contentDescription 复用**：`oc_home_project_edit_icon_clear`（既有 key）的语义就是「清除图片」，直接用作 contentDescription，**不新增任何 key**。pin 里有一条专门断言没有引入 `oc_home_project_edit_icon_remove` 这类自造 key。

### 改动 diff

**1. `app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectEdit.kt`** — 新增纯函数：

```diff
+    fun iconTap(override: String?, enabled: Boolean): IconAction = when {
+        !enabled -> IconAction.None
+        override != null -> IconAction.Clear
+        else -> IconAction.Pick
+    }
+
+    fun draftWithoutIcon(draft: Draft): Draft = draft.copy(iconOverride = null)
+
+sealed interface IconAction {
+    data object Clear : IconAction   // setStore("iconOverride", "")
+    data object Pick : IconAction    // iconInput?.click()
+    data object None : IconAction    // 保存中，吞掉 tap
+}
```
（`IconAction` 的 KDoc 里抄了上游 `iconClick()` 原文与 hover 缺失的论证。）

**2. `app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectEditDialog.kt:348`** — 图标区：

```diff
-        Row(... ) {
-            ProjectIconView(
-                name = draft.name.trim().ifEmpty { folderName },
-                iconColor = draft.color,
-                override = draft.iconOverride,
-                size = 32.dp,
-            )
+        val clearIconLabel = stringResource(R.string.oc_home_project_edit_icon_clear)
+        when (HomeProjectEdit.iconTap(draft.iconOverride, editable)) {
+            IconAction.Clear -> Box(
+                modifier = Modifier
+                    .size(32.dp)
+                    .clip(OcTheme.shapes.sm)
+                    .clickable(role = Role.Button, onClick = { onChange(HomeProjectEdit.draftWithoutIcon(draft)) })
+                    .semantics { contentDescription = clearIconLabel },
+            ) {
+                ProjectIconView(…)
+            }
+            // Without an image the tap would do the same thing the adjacent
+            // 「选择图片」 button does, so the slot stays inert rather than
+            // becoming a second, unlabelled picker.
+            else -> ProjectIconView(…)
+        }
+        Row(... ) {
             EditDialogButton(label = … icon_pick, …)
             if (draft.iconOverride != null) {
                 EditDialogButton(
                     label = stringResource(R.string.oc_home_project_edit_icon_clear),
                     enabled = editable,
-                    onClick = { onChange(draft.copy(iconOverride = null)) },
+                    onClick = { onChange(HomeProjectEdit.draftWithoutIcon(draft)) },
                 )
             }
         }
```

**清除语义**：`draftWithoutIcon` 置 **null**（不是 `""`），由既有的 `HomeProjectEdit.patchFor`（`HomeProjectEdit.kt:131-135`）把它翻译成 `iconOverride = ""` 发出去 —— 即上游 `saveIcon(color, "")` 的 `""` 清除。**不改 `patchFor`**，所以「清图不清名字/颜色/脚本」是既有纯函数保证的，pin 直接断言它。

---

## ③ A9：启动命令的 `$VAR` 变量提示 → **改**

### 上游证据

**触发条件：无条件渲染**（不是「用户输入了 `$` 才显示」）。

`_upstream/packages/app/src/settings/workspaces/project.tsx:137-158`
```tsx
          <div class="project-settings-startup">
            <div class="project-settings-startup-copy">
              <span class="project-settings-startup-title">{language.t("dialog.project.edit.worktree.startup")}</span>
              <span class="project-settings-startup-description">
                {language.t("project.settings.worktree.startup.description")}
              </span>
            </div>
            <Textarea … />
            <div class="project-settings-startup-hint flex flex-col">
              <span>{inlineVariables(language.t("project.settings.worktree.startup.hint.base"))}</span>
              <span>{inlineVariables(language.t("project.settings.worktree.startup.hint.new"))}</span>
            </div>
          </div>
```

**确切文案**（`_upstream/packages/app/src/runtime/i18n/en.ts:1097-1098`）：
```
"project.settings.worktree.startup.hint.base": "Use $OPENCODE_WORKTREE_BASE for the base worktree.",
"project.settings.worktree.startup.hint.new": "Use $OPENCODE_WORKTREE_PATH for the new worktree."
```

**高亮规则**（`project.tsx:165-169`）：
```tsx
function inlineVariables(text: string) {
  return text
    .split(/(\$[A-Z][A-Z0-9_]*)/g)
    .map((part, index) => (index % 2 === 0 ? part : <code dir="ltr">{part}</code>))
}
```

**提示说的是事实**（这是「做不做」的决定性依据 —— 不是猜的）：
`_upstream/packages/core/src/worktree.ts:241-247`
```ts
            ChildProcess.make(windows ? command : "bash", windows ? [] : ["-lc", command], {
              cwd: result.directory,
              env: {
                OPENCODE_WORKTREE_BASE: sourceDirectory,
                OPENCODE_WORKTREE_PATH: result.directory,
              },
              extendEnv: true,
```

### 决定：改（不选「不改 + 理由」）

评估过「重文案、收益低」这条路，结论是**不成立**：

- 这不是装饰性提示，而是**用户唯一能知道这两个变量存在的地方**。没有它，命令里写 `$OPENCODE_WORKTREE_PATH` 的用户只能靠猜；而服务器确实会导出它。
- 文案可**逐字**取自上游 en.ts，不是自创。
- 我方 shell **确实有 worktree 创建**（`ui/App.kt:995-1008` `worktreeCreateOpen`、`prompt_toast_worktreeCreateFailed_title`），所以「创建工作区后运行一次」这个上下文在我方也成立，提示不是悬空文案。
- 成本可控：2 个 key × 3 语，`StringsLocaleParityTest` 把关。

### 三语文案（本轮新增的唯一用户可见字符串）

| key | `values` (en) | `values-zh-rCN` | `values-zh-rTW` |
|---|---|---|---|
| `oc_home_project_edit_startup_hint_base` | `Use $OPENCODE_WORKTREE_BASE for the base worktree.` | `用 $OPENCODE_WORKTREE_BASE 表示基础工作区。` | `用 $OPENCODE_WORKTREE_BASE 代表基底工作區。` |
| `oc_home_project_edit_startup_hint_new` | `Use $OPENCODE_WORKTREE_PATH for the new worktree.` | `用 $OPENCODE_WORKTREE_PATH 表示新建的工作区。` | `用 $OPENCODE_WORKTREE_PATH 代表新建的工作區。` |

英文逐字等于 `en.ts:1097-1098`；中文为对译，**三语的变量名本身保持英文原样**（变量名是代码 token，不该翻译）。

### 改动 diff

**1. 三份 `strings.xml`** —— 见上表（`oc_home_project_edit_startup_hint` 旁边各加 2 行）。

**2. `app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectEdit.kt`** — 上游 split 的纯函数：

```diff
+    fun variableSegments(text: String): List<HintSegment> {
+        val segments = mutableListOf<HintSegment>()
+        var index = 0
+        for (match in VARIABLE.findAll(text)) {
+            if (match.range.first > index) {
+                segments += HintSegment(text.substring(index, match.range.first), false)
+            }
+            segments += HintSegment(match.value, true)
+            index = match.range.last + 1
+        }
+        if (index < text.length) segments += HintSegment(text.substring(index), false)
+        return segments
+    }
+
+    /** `$[A-Z][A-Z0-9_]*` — the exact token `inlineVariables` wraps in `<code>`. */
+    private val VARIABLE = Regex("""\$[A-Z][A-Z0-9_]*""")
+
+data class HintSegment(val text: String, val variable: Boolean)
```

用 `findAll` 而非 `split`：与上游 split 的**结果等价**（捕获组在 `split` 里保留匹配项，且每个匹配落在奇数下标），但不依赖下标奇偶，逻辑可在纯 JVM 里逐字断言。

**3. `app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectEditDialog.kt:446`** — 渲染两行：

```diff
         Text(text = stringResource(R.string.oc_home_project_edit_startup_hint), …)
+        // `project.tsx:154-157`'s two hint lines, with `inlineVariables`
+        // (`project.tsx:165-169`) marking each `$UPPER_SNAKE` run as <code>.
+        StartupVariableHint(stringResource(R.string.oc_home_project_edit_startup_hint_base))
+        StartupVariableHint(stringResource(R.string.oc_home_project_edit_startup_hint_new))
     }
+
+@Composable
+private fun StartupVariableHint(text: String) {
+    val c = OcTheme.colors
+    Text(
+        text = buildAnnotatedString {
+            HomeProjectEdit.variableSegments(text).forEach { segment ->
+                if (segment.variable) {
+                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = c.textMuted)) {
+                        append(segment.text)
+                    }
+                } else {
+                    append(segment.text)
+                }
+            }
+        },
+        color = c.textFaint,
+        fontSize = 12.sp,
+        lineHeight = 17.sp,
+        modifier = Modifier.padding(top = 2.dp),
+    )
+}
```

`<code dir="ltr">` 的 `ltr` 方向在 `Text` 上由 `TextStyle` 天然处理（`BasicTextField` 的 startup 字段本身已是 `FontFamily.Monospace`），故只加等宽字重，不另设方向。

---

## 测试证据

### 新增类：`app/src/test/java/com/xiufeigo/opencodeshell/ProjectParityV97Test.kt`（18 例）

本仓库 test 依赖只有 junit / org.json / mockwebserver（无 compose / robolectric），故：**行为语义走纯函数，渲染走源码 pin**。

**① 刷新（5 例）**
1. `refreshIsNotOfferedWithoutAServerProject` —— 纯函数真值表：`null` / `""` / `"   "` / `"global"` 全 false，`"prj_01"` true。
2. `theRowMenuGatesTheRefreshItemOnThatPredicate` —— 正则断言 `if (HomeProjectEdit.refreshApplies(project.serverID)) {` 后**紧跟** `DropdownMenuItem`（是「隐藏」不是「禁用」），且全文恰好 1 处。
3. `refreshingAnUnregisteredProjectIsSilentNotRefused` —— 断言旧 toast 行**不存在**，且有 `if (result is ProjectWrite.NotRegistered) return@launch`；同时正向断言成功/失败两条 toast 未被动。
4. `theOutcomeItselfSurvivesTheQuietPresentation` —— 断言 `HomeProjectApi` 仍 `?: return ProjectWrite.NotRegistered`、`ProjectWrite.NotRegistered` 仍存在、`when` 仍穷举。**锁死「只改呈现不改语义」。**
5. `theSelfInventedCopyIsReferencedNowhereInThePanelHome` —— 三处源码均不引用 `oc_home_project_edit_missing`，且三语文案仍都在（key 未被偷偷删掉）。

> 另：`HomeProjectLocalMetaV97Test.theNotRegisteredCopyIsGoneFromThePanelAndFromTheRefreshToast` —— 上一轮那条正向断言改为反向 + 静默断言（见上文 diff #6）。合计本轮覆盖 **19 个用例**（新类 18 + 改写 1）。

**② 点击图标清除（6 例）**
6. `tappingTheIconPicksWithoutAnImageAndClearsWithOne` —— `null`/`""` → `Pick`；有图 → `Clear`。
7. `aDisabledIconSwallowsTheTap` —— `enabled=false` 两态都 → `None`。
8. `clearingTheIconSendsAnEmptyOverrideAndNothingElse` —— **核心语义 pin**：`patchFor(original, draftWithoutIcon(draftOf(original)))` 得到 `iconOverride == ""` 而 `iconColor / name / startup` **全为 null**，且 `isEmpty == false`（是真实写入，不是空 patch）。
9. `clearingTheIconKeepsTheColorAndTheOtherFields` —— `draftWithoutIcon` 只动一个字段。
10. `bothClearEntryPointsShareTheOnePureFunction` —— 点击与「清除图片」都调 `draftWithoutIcon`；「选择图片」与 `image/*` 过滤器未被动。
11. `theIconTapBorrowsTheExistingClearCopyForItsLabel` —— contentDescription 复用既有 key；断言**没有**引入 `oc_home_project_edit_icon_remove`。

**③ `$VAR` 提示（7 例）**
12. `theVariableSplitMatchesUpstreamsRegex` —— 逐段断言 `("Use ", false) / ("$OPENCODE_WORKTREE_BASE", true) / (" for the base worktree.", false)`。
13. `theVariableSplitRejectsWhatUpstreamDoesNotMatch` —— `$5` / `$lower_case` / `$1ST` / 普通文本 / 空串**均无**变量段；`$A1_B2` 是变量（数字可出现在首位之后）。
14. `theVariableSplitKeepsEveryRunAndAllSurroundingText` —— 一行两个变量 + 拼接无损。
15. `theStartupHintIsUpstreamsTwoLinesInAllThreeLocales` —— **三语 pin**：每个 locale 的两个 key 各含且仅含 1 个变量段、变量名正确；英文**逐字**等于 `en.ts:1097-1098`。
16. `theExistingStartupHintIsStillThere` —— 旧的一行提示未丢（A9 是增补，不是替换）。
17. `theHintRendersBothLinesThroughTheSegmentHelper` —— 两行无条件渲染且走 `variableSegments` + 等宽 `SpanStyle`。
18. `theHintNamesTheVariablesTheServerExports` —— 两个变量名在 `core/src/worktree.ts:244-245` 真实存在（钉住「文案是服务器事实」）。

### 回归命令

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --console=plain :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.ProjectParityV97Test" `
  --tests "com.xiufeigo.opencodeshell.HomeProjectLocalMetaV97Test" `
  --tests "com.xiufeigo.opencodeshell.HomeProjectIconRefreshTest" `
  --tests "com.xiufeigo.opencodeshell.HomeProjectsTest" `
  --tests "com.xiufeigo.opencodeshell.HomeProjectStoreTest" `
  --tests "com.xiufeigo.opencodeshell.HomeProjectFilterTest" `
  --tests "com.xiufeigo.opencodeshell.ProjectIconLocalMetaV97Test" `
  --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest"
```

### 结果

```
BUILD SUCCESSFUL in 5s
GRADLE_EXIT=0
```

`app/build/test-results/testDebugUnitTest/` 逐类统计：

| 测试类 | 用例 | 失败 | 跳过 |
|---|---:|---:|---:|
| `ProjectParityV97Test`（本轮新增） | 18 | 0 | 0 |
| `HomeProjectLocalMetaV97Test`（本轮改写 1 例） | 21 | 0 | 0 |
| `HomeProjectIconRefreshTest` | 4 | 0 | 0 |
| `HomeProjectsTest` | 10 | 0 | 0 |
| `HomeProjectStoreTest` | 21 | 0 | 0 |
| `HomeProjectFilterTest` | 25 | 0 | 0 |
| `ProjectIconLocalMetaV97Test` | 12 | 0 | 0 |
| `StringsLocaleParityTest` | 3 | 0 | 0 |
| **合计** | **114** | **0** | **0** |

### 本轮被测试抓出并修掉的 3 个真问题（未掩盖）

第一次跑出 `114 tests completed, 3 failed`，三条都是**实现/断言的真实缺陷**，逐条修掉后重跑：

1. **`iconTap("", true)` 返回 `Clear`，应为 `Pick`。**
   我把 `override != null` 当成「有图」，但上游的守卫是 `if (store.iconOverride && store.iconHover)` —— **JS 真值判断**，空串即「无图」，落到 `iconInput.click()`。
   改成 `!override.isNullOrEmpty()`，与上游真值语义逐字一致。这条是纯函数 pin 抓出来的，UI 层不跑测试根本不会发现。

2. **`oc_home_project_edit_missing` 的「零引用」断言被自己的注释触发。**
   我在 `HomeScreen.kt` 的 KDoc 里**说明**了这个 key 被弃用，而断言用的是整文件 `contains`。
   改成断言 `R.string.oc_home_project_edit_missing`（活引用的确切形态）—— 既精确，又允许注释继续讨论这个 key。比「剥掉注释再匹配」更稳：剥注释是启发式，可能把字符串字面量里的真实引用也吃掉。

3. **`HomeProjectLocalMetaV97Test` 同一处断言**，同因同改。

> 另修一个**编译期**问题：Kotlin 的块注释**可嵌套**，我在 KDoc 里写的 `ui/home/**` 里的 `/*` 开了一个嵌套注释，导致 `Unclosed comment` 编译失败。已改为 `ui/home`。

---

## 改动文件清单（全部落在授权范围内）

| 文件 | 改动 |
|---|---|
| `ui/home/HomeProjectEdit.kt` | ① `refreshApplies` 纯谓词；② `iconTap` / `draftWithoutIcon` / `IconAction`；③ `variableSegments` / `HintSegment`；`ProjectWrite.NotRegistered` 的 KDoc 改为「非错误态」 |
| `ui/home/HomeProjectsList.kt` | ① 菜单项按 `refreshApplies(project.serverID)` 隐藏 |
| `ui/home/HomeScreen.kt` | ① `NotRegistered` 静默返回，去掉「未在服务器注册」toast |
| `ui/home/HomeProjectEditDialog.kt` | ② 点击图标清除（复用既有 clear 串作 contentDescription）；③ 两行 `$VAR` 提示 + `StartupVariableHint` |
| `res/values/strings.xml`、`values-zh-rCN/`、`values-zh-rTW/` | ③ 各新增 2 个 key（三语同步） |
| `app/src/test/.../ProjectParityV97Test.kt` | 新增，18 例 |
| `app/src/test/.../HomeProjectLocalMetaV97Test.kt` | ① 改写 1 例（反向断言 + 静默断言） |
| `research/v97_fix/PROJECT_PARITY_FIX.md` | 本文件 |

**未触碰的冻结区**：`ui/App.kt`(mtime 19:20)、`data/HomeProjectStore.kt`(18:04)、`app/build.gradle.kts`(17:57) 均**早于**本轮首次写入(19:48)，且本轮未对 `ui/session/**`、`ui/nav/**`、`_upstream/**` 发起任何写操作。
> `git status` 在这些路径上显示大量 `M` / `??`，那是**前几轮遗留的未提交成果**（`PROJECT_EDIT_LOCAL_META.md` 开头已声明此现象），不是本轮改动。

**运行纪律**：全程只跑 `:app:testDebugUnitTest --tests <精确类名>`；无 `assembleDebug`、无 `clean`、无全量测试、无装机。锁按协议取用（一次等待 60 s 让出），结束即释放（末次释放后该锁由其他 agent 重新持有，非本轮遗留）。

---

## 真机复验步骤（未做）

> 本轮只跑了纯 JVM 闸门。**没有** `assembleDebug`、**没有**装机（按环境纪律）。以下为待执行的人工步骤。

**① 未注册项目不再弹「未在服务器注册」**
1. 启动 app，连接一台 opencode server。
2. Home → 找一个**服务器没有对应 project** 的本地目录（用 Home 的「添加项目」加一个 server 不认识的目录）。
3. 点该行 **⋯** → 菜单里**不应出现**「刷新工作区」。
4. 回归：找一个**已注册**项目 → ⋯ → 「刷新工作区」**仍在**，点了弹「工作区已刷新」。

**② 点击图标清除**
1. Home → ⋯ →「编辑项目」。
2. 「选择图片」→ 选一张图 → 保存 → 重新打开编辑面板。
3. **直接点那个 32 dp 图标** → 图片消失；色板行重新出现（上游 `<Show when={!model.store.iconOverride}>` 语义）；「清除图片」按钮同时消失。
4. 改名 + 改色 + 填启动命令 → 再次点图标清图 → 保存 → 重新打开：名字/颜色/启动命令**都还在**，只有图没了（pin 9/10 的真机对应）。
5. TalkBack 焦点落到图标上应读「清除图片」。

**③ 变量提示**
1. 编辑项目 → 启动命令输入框下方应出现**两行**提示，`$OPENCODE_WORKTREE_BASE` / `$OPENCODE_WORKTREE_PATH` 为等宽字重。
2. 切 zh-CN / zh-TW，两行都应显示，变量名保持英文。
3. 起一个带 worktree 的会话，确认 `startup` 命令在新建 worktree 里能读到这两个环境变量。

---

## 未闭环

1. **`oc_home_project_edit_missing` 这个 key 仍在三份 `strings.xml` 里，零引用。** 本轮只断了引用没删 key（删除资源 key 属独立一步，`StringsLocaleParityTest` 把三份 key 集合当契约）。已用 pin 钉住「零引用」这一状态。
2. **真机复验未做**（环境纪律禁止 assembleDebug / 装机）。①②③ 的所有断言都是纯 JVM / 源码级。
3. **A4（拖拽上传图标）与 A11（多服务器切换链接）本轮未做。** A4 在触屏上等价于 A3 的 SAF 选择（已覆盖），上游的 `onDrop` 是桌面 HTML5 拖放，触屏无对应交互，收益低；A11 需要 `servers().length > 1` 的多服务器设置，超出本次三项范围。
4. **A7（色板仅在无自定义图片时显示）已由上一轮实现**（`HomeProjectEditDialog.kt` 的 `if (draft.iconOverride == null)`，对齐 `project.tsx:103`），本轮未动，只在 ② 的 pin 里顺带断言其联动。
5. **「刷新工作区」本身仍是自创动作**（上游 Home 行菜单无此项，见证据 3）。本轮只把它对齐到「服务器项目才提供 + 失败静默」。是否**整体移除**该菜单项以彻底贴合上游，属产品决策，未在本轮做。
6. **hover 语义在触屏上的替代是本轮的判断，不是上游原文。** `iconHover` 分支在触屏无法照搬，落到「图片本身」是可辩护的映射，但与上游并非逐字等价。
