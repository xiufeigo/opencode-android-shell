# v97 P1 修复：项目编辑弹层的本地 meta 写通道（PROJECT_EDIT_LOCAL_META）

> 缺陷：Home 项目行 ⋮ → 「编辑项目」→ 面板里两个 EditText `enabled=false focusable=false`、保存禁用、4 种输入手法零字符落地，面板显示「该项目未在服务器注册」（5560 Pixel_Tablet 真机 FAIL）。
> 定性：上游对照审计 `research/v97_audit/PROJECT_EDIT_UPSTREAM.md` 判为**真缺口 P1** —— 我方自创了上游不存在的可用性闸门。
> 修复者：fix-project-edit。本轮状态：**DONE（源码 + 纯 JVM 闸门全绿），真机复验未做**。

---

## 设计决策

### 1. 保存路由怎么接：`HomeProjectApi.saveLocal`（选它，不选「宿主注入回调」）

两条路可选：(a) 在 `HomeProjectApi` 上加一个 `saveLocal(worktree, patch)`；(b) 给弹层加一个宿主回调。

**选 (a)**，理由三条：

1. **对称**。`save`（`PATCH /api/project/{id}`）与 `saveLocal`（`sync.project.meta`）本来就是上游同一个 `persist()` 的两条分支（`project-model.ts:41-46`）。放在同一个 seam 上，弹层那一行就只剩一个纯函数分派，不需要知道本地 store 长什么样；而 (b) 会把 store 的形状泄进 `HomeProjectEditDialog` 的签名里。
2. **阻塞模型一致**。`HomeProjectApi` 的方法全部在 `Dispatchers.IO` 上跑，两个写通道共用一个 `withContext(Dispatchers.IO)`，弹层的 `saving` 生命周期对两条路一样。回调方案要多一套并发/取消约定。
3. **弹层保持 dumb**。弹层现在对「写去哪」的知识只有一行 `HomeProjectEdit.writeTargetFor(state?.projectID)`，这个判定是**纯函数**、可被 JVM 测试直接钉住（见 `HomeProjectLocalMetaV97Test.theSaveRouteIsAProjectIdQuestionAndNothingElse`）。

`saveLocal` 唯一的失败是「宿主根本没接 store」（preview / 隔离宿主，那里也没有行可存），它复用既有的 `ProjectFault.NoServer` 而不是新造一个 `ProjectFault` 分支 —— 因为新增 `ProjectFault` 就得新增一条用户可见文案，违反「不许新增用户可见字符串」。真实宿主（`OcApp` → `HomeScreen`）永远接了 store，这条分支不可达。

### 2. store 实例怎么保证是同一份：`HomeProjectApi` 构造参数注入，**不自服务**

**先确认了 store 的持有链**（不是猜）：

| 位置 | 事实 |
|---|---|
| `ui/App.kt:486` | `val homeProjects = remember(context, serverKey) { HomeProjectStore(context, serverKey) }` —— **根组合里唯一一个**实例 |
| `ui/App.kt:492-494` | `LaunchedEffect(homeProjects) { withContext(Dispatchers.IO) { homeProjects.restore() } }` —— 延迟读盘 |
| `ui/App.kt:627` → `HomeScreen(homeProjectStore: HomeProjectStore? = null, …)` | 同一实例传下去 |
| `ui/home/HomeScreen.kt:180` | `val openedProjects = homeProjectStore?.state?.collectAsStateWithLifecycle()?.value ?: EMPTY_PROJECTS` —— **列渲染观察的就是这个 StateFlow** |
| `ui/home/HomeScreen.kt:379-386` | `val projects = remember(openedProjects.list, openedProjects.recentlyClosed, serverProjects, visibleSessions, serverNames) { HomeProjects.openRows(…) }` —— 键里含 `openedProjects.list` |

于是修法是：`rememberHomeProjectApi()` 加一个 `homeProjectStore: HomeProjectStore? = null` 形参，`HomeScreen` 传自己手上那个（`ui/home/HomeScreen.kt:209`）。`HomeProjectApi` 只是**持有**它，不新建。

**如果照旧自服务会怎样（论证）**：`rememberHomeProjectApi()` 原本是纯自服务的（`ServerConfigStore(context)` + `HomeProjectApi`）。如果让 `HomeProjectApi` 内部自己 `HomeProjectStore(context, serverKey)`，就会得到**第二个**实例：它的 `StateFlow` 没有 `HomeScreen` 在收集，`openRows` 的 `remember` 键不会变，保存后行**不重绘**，直到进程重启 —— 也就是把「面板全禁用」换成「保存了但看不见」，仍然是坏的。所以实例注入不是洁癖，是这个修复能生效的**必要条件**，测试里专门钉了一条（`theHostHandsThePanelTheStoreTheColumnObserves`）。

另外 `saveLocal` 走 `HomeProjectStore.setMeta` → `update {}` → `_state.value = next` + `prefs.edit().apply()`，所以**同一帧** `StateFlow` 发射 → `openedProjects.list` 变 → `remember` 键变 → `openRows` 重算 → 行立刻改名/换色/换图。

### 3. 展示合并：为什么需要一个 `serverID`

上游 `runtime.tsx:151` 的读侧规则是 `...(!metadata || metadata.id === "global" ? childStore.projectMeta : undefined)` —— 本地 meta **只在没有服务器元数据时**覆盖。要判断「有没有」，`HomeProjects.openRows` 手上只有 `known`（`merge` 的产物），拿不到那一行的 `Project.id`。所以给 `HomeProjects.Project` 追加了一个 `serverID: String? = null`（末尾 + 默认值，所有既有具名/位置构造照常编译），在 `merge` 里由 `HomeProjectEdit.serverProjectID(project.id)` 填（它把伪项目 `global` 和空 id 都归一成 null，和上游 `id !== "global"` 的判断同源）。

**已注册项目行为不变**：服务器仍然拥有该行的图标；本地那份（如果因为「先本地保存、后来才被注册」而残留）被显式忽略，不会有本地遮蔽服务器的可能。

`name` 不需要改：`displayName(worktree, names, record.name)` 本来就是「服务器名 → 本地名 → 文件夹名」的优先级，已经等于上游 `runtime.tsx:151` 对 name 的处理。

### 4. 为什么最小

- 弹层本体只改了三件事：`editable` 的条件、Save 的 `enabled`、保存那 3 行分派。**没有**改任何布局、文案、控件顺序。
- 新增数据只加在 `HomeProjectRecords.Project` 的**末尾**且都有默认值 → 既有构造点一行不用改，`openRows` / `merge` 的既有行为在「无本地 meta」时逐字节不变。
- `encode` 只在字段**有值**时写 key → 一条从没保存过 meta 的旧记录，编码结果与旧版本一致（测试里断言了）。
- `mergeRestored` 的改法是换 `open(...)` 为新的 `adopt(...)`（`open` + 记录自身字段回填），因为记录现在能带字段了，而 `open(worktree: String)` 会把它重建成裸的 `Project(worktree)`。

---

## 改动清单

> 说明：下面 6 个文件里 `HomeProjectEdit.kt` / `HomeProjectEditDialog.kt` / `HomeProjectApi.kt` / `HomeProjectLocalMetaV97Test.kt` 在 git 里是 **untracked**，`HomeProjectStore.kt` / `HomeProjects.kt` / `HomeScreen.kt` 相对 HEAD 带着前几轮未提交的巨量改动，所以 `git diff` 无法只显示本轮。以下 unified diff 由本 agent 逐条 `edit` 的原文（old_string → new_string）重建，逐字可对照现盘文件。

### 1. `app/src/main/java/com/xiufeigo/opencodeshell/data/HomeProjectStore.kt`

```diff
@@ HomeProjectRecords.Project @@
     data class Project(
         val worktree: String,
         val sandboxes: List<String> = emptyList(),
@@ upstream `displayName`'s `project.name`. …
         val name: String = "",
+        /**
+         * `ProjectMeta.icon.color` (`runtime/server/persistence.ts:119-132`):
+         * the client-local icon tint, written by `sync.project.meta(worktree,
+         * patch)` (`global-sync/child-store.ts:335-351`) for a worktree the
+         * server has no `project.id` for. Null = the swatches are untouched and
+         * the avatar keeps the neutral surface.
+         *
+         * Appended with a default so every existing named or positional
+         * construction of this record still compiles.
+         */
+        val iconColor: String? = null,
+        /**
+         * `ProjectMeta.icon.override` — the client-local custom icon image, the
+         * same `data:` URL the SAF pick produces and the PATCH body would
+         * carry. Potentially large (a downsampled PNG), which is why upstream
+         * keeps it client-side and why [encode] omits it while it is null.
+         */
+        val iconOverride: String? = null,
+        /**
+         * `ProjectMeta.commands.start` — the worktree's startup command as the
+         * client-local record has it. Null = unset (the field shows the folder
+         * name and an empty command line).
+         */
+        val startup: String? = null,
+    )
+
+    /**
+     * The four fields upstream persists per worktree in its client-local
+     * `ProjectMeta` (`persistence.ts:119-132`): `{name?, icon:{override?,color?},
+     * commands:{start?}}` — the read side of [Project]. Defaults are the
+     * "nothing saved locally" values, so [NONE] is what an unregistered
+     * worktree the user never edited reads as.
+     */
+    data class LocalMeta(
+        val name: String = "",
+        val iconColor: String? = null,
+        val iconOverride: String? = null,
+        val startup: String? = null,
+    ) {
+        companion object {
+            /** No local meta at all (a worktree this client has never saved). */
+            val NONE = LocalMeta()
+        }
+    }
+
+    /**
+     * One `sync.project.meta(worktree, patch)` call
+     * (`global-sync/child-store.ts:335-351`): **null = "not in this patch"**,
+     * `""` = clear the field, any other value = the new one. Identical
+     * tri-state to the `Patch` the server route sends as an omitted key, so
+     * one [Project]-shaped diff drives both write channels.
+     */
+    data class MetaPatch(
+        val name: String? = null,
+        val iconColor: String? = null,
+        val iconOverride: String? = null,
+        val startup: String? = null,
+    ) {
+        val isEmpty: Boolean
+            get() = name == null && iconColor == null && iconOverride == null && startup == null
+    }
```

```diff
@@ after setName(state, directory, name) @@
+    /**
+     * `sync.project.meta(worktree, patch)` (`child-store.ts:335-351`) — the
+     * client-local write upstream performs for a project with no server
+     * `project.id` (`project-model.ts:41-46`, `project-row.tsx:60-65`). …
+     * The merge is **field-level and shallow**, exactly as upstream's
+     * `icon`/`commands` spread is: a field the [patch] does not mention keeps
+     * the value it had, so a rename never clears the icon image and a colour
+     * change never clears the image (upstream `icon = {...previous.icon,
+     * ...patch.icon}`). `""` clears, null leaves it alone, any other value is
+     * the new one ([MetaPatch]).
+     */
+    fun setMeta(state: State, directory: String, patch: MetaPatch): State {
+        val worktree = directory.trim()
+        if (worktree.isEmpty() || patch.isEmpty) return state
+        var changed = false
+        val list = state.list.map { project ->
+            if (!ProjectPaths.sameDirectory(project.worktree, worktree)) return@map project
+            val next = project.copy(
+                name = patch.name?.trim() ?: project.name,
+                iconColor = mergeField(patch.iconColor, project.iconColor),
+                iconOverride = mergeField(patch.iconOverride, project.iconOverride),
+                startup = mergeField(patch.startup, project.startup),
+            )
+            if (next == project) project else { changed = true; next }
+        }
+        if (!changed) return state
+        return state.copy(list = list)
+    }
+
+    /** The client-local meta `childStore.projectMeta` holds for [directory] … */
+    fun localMeta(state: State, directory: String): LocalMeta { … }
+
+    /**
+     * `open(directory)` for a record that already carries fields … This is
+     * what [mergeRestored] folds in-memory rows through. It used to fold them
+     * through [open] alone, which rebuilt each row as a bare
+     * `Project(worktree)` — correct while a record carried nothing but its
+     * worktree, and silently lossy the moment …
+     */
+    fun adopt(state: State, record: Project): State { … }
+
+    /** The `ProjectMeta` merge rule: null = untouched, `""` = cleared, else the new value. */
+    private fun mergeField(patch: String?, previous: String?): String? = when {
+        patch == null -> previous
+        patch.isEmpty() -> null
+        else -> patch
+    }
```

```diff
@@ encode() KDoc + projectJson() + JSONArray.projects() + dedupe() + mergeRestored() @@
-     * The persisted form: `{"projects":[{worktree,sandboxes}],…}`.
+     * The persisted form: `{"projects":[{worktree,sandboxes,name?,iconColor?,iconOverride?,startup?}],…}`.
+     * The three meta keys are the flattened form of upstream's `ProjectMeta` …

     private fun projectJson(project: Project): JSONObject = JSONObject().apply {
         …
         if (project.name.isNotBlank()) { put("name", project.name) }
+        project.iconColor?.takeIf { it.isNotBlank() }?.let { put("iconColor", it) }
+        project.iconOverride?.takeIf { it.isNotEmpty() }?.let { put("iconOverride", it) }
+        project.startup?.takeIf { it.isNotEmpty() }?.let { put("startup", it) }
     }

@@ decode(): String -> Project @@
-                    val name = if (entry.isNull("name")) "" else entry.optString("name", "").trim().takeIf { it != "null" } ?: ""
-                    Project(worktree, entry.optJSONArray("sandboxes").strings(), name)
+                    Project(
+                        worktree = worktree,
+                        sandboxes = entry.optJSONArray("sandboxes").strings(),
+                        name = entry.metaString("name").orEmpty(),
+                        iconColor = entry.metaString("iconColor"),
+                        iconOverride = entry.metaString("iconOverride"),
+                        startup = entry.metaString("startup"),
+                    )
                 }
                 else -> null
             }
         }
     }
+
+    /**
+     * One optional persisted string: absent, blank or JSON null all read as
+     * null (nothing saved), never as the literal `"null"` org.json hands back
+     * for a null.
+     */
+    private fun JSONObject.metaString(key: String): String? {
+        if (isNull(key)) return null
+        return optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }
+    }

@@ dedupe() @@
                 kept[index] = prior.copy(
                     sandboxes = (prior.sandboxes + project.sandboxes).dedupePaths(),
                     name = prior.name.ifEmpty { project.name },
+                    iconColor = prior.iconColor ?: project.iconColor,
+                    iconOverride = prior.iconOverride ?: project.iconOverride,
+                    startup = prior.startup ?: project.startup,
                 )

@@ mergeRestored() @@
-    val list = current.list.fold(closed) { acc, project ->
-        HomeProjectRecords.open(acc, project.worktree)
-    }
+    // `adopt`, not `open`: an in-memory row can carry a saved name / icon tint
+    // / icon image / startup command the on-disk record does not have (a write
+    // that beat this read), and `open` would rebuild it as a bare
+    // `Project(worktree)`. …
+    val list = current.list.fold(closed) { acc, project ->
+        HomeProjectRecords.adopt(acc, project)
+    }

@@ class HomeProjectStore @@
+    /**
+     * `sync.project.meta(worktree, patch)` — the client-local write behind a
+     * project the server has no `project.id` for …
+     */
+    fun setMeta(directory: String, patch: HomeProjectRecords.MetaPatch) =
+        update { HomeProjectRecords.setMeta(it, directory, patch) }
+
+    /**
+     * The client-local meta for [directory] … Reads the live [state], never the
+     * disk, so it is the same snapshot the column is drawing …
+     */
+    fun localMeta(directory: String): HomeProjectRecords.LocalMeta =
+        HomeProjectRecords.localMeta(_state.value, directory)
```

### 2. `app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectEdit.kt`

```diff
@@ file header KDoc @@
- * Projects with no server id (client-local worktrees, and upstream's pseudo
- * project `"global"`) have nowhere to PATCH: [projectIdFor] reads as null and
- * the dialog keeps the row honest instead of inventing a write.
+ * Projects with no server id (client-local worktrees, and upstream's pseudo
+ * project `"global"`) are **not** a degraded state upstream: the same three
+ * fields stay editable and the write simply lands on the client's own
+ * `ProjectMeta` instead of `PATCH /api/project/{id}`
+ * (`project-model.ts:41-46`, `project-row.tsx:60-65`,
+ * `global-sync/child-store.ts:335-351`). [writeTargetFor] is that
+ * transport fork and nothing more, and [localMetaApplies] is its read-side
+ * mirror (`runtime.tsx:151`).

@@ imports @@
 package com.xiufeigo.opencodeshell.ui.home
 
+import com.xiufeigo.opencodeshell.data.HomeProjectRecords
 import org.json.JSONObject

@@ after serverProjectID(id) @@
     fun serverProjectID(id: String?): String? = id?.trim()?.takeIf { it.isNotEmpty() && it != "global" }
+
+    /**
+     * Where one save goes — upstream's `project.id && project.id !== "global" ?
+     * serverPATCH : sync.project.meta(worktree, patch)` ternary
+     * (`project-model.ts:41-46`, repeated for the inline rename at
+     * `project-row.tsx:60-65`), with the branch it picked named. …
+     */
+    fun writeTargetFor(projectID: String?): WriteTarget =
+        serverProjectID(projectID)?.let { WriteTarget.Server(it) } ?: WriteTarget.Local
+
+    /**
+     * `runtime.tsx:151`'s read side: … the client-local meta **covers** the
+     * server's only when the worktree has no server project. …
+     */
+    fun localMetaApplies(serverProjectID: String?): Boolean = serverProjectID == null
+
+    fun originalWithLocalMeta(
+        original: Original,
+        meta: HomeProjectRecords.LocalMeta,
+    ): Original = original.copy(
+        name = meta.name.ifEmpty { original.name },
+        color = meta.iconColor ?: original.color,
+        iconOverride = meta.iconOverride ?: original.iconOverride,
+        startup = meta.startup ?: original.startup,
+    )
+
+    fun metaPatchFor(patch: Patch): HomeProjectRecords.MetaPatch = HomeProjectRecords.MetaPatch(
+        name = patch.name,
+        iconColor = patch.iconColor,
+        iconOverride = patch.iconOverride,
+        startup = patch.startup,
+    )

@@ before ProjectFault @@
+/**
+ * Which write channel a project edit takes ([HomeProjectEdit.writeTargetFor]).
+ * Both are normal, successful outcomes with the same user-visible result …
+ */
+sealed interface WriteTarget {
+    /** `project.id` present: `PATCH /api/project/{id}`. */
+    data class Server(val projectID: String) : WriteTarget
+
+    /** No server project: `sync.project.meta(worktree, patch)` — client-local. */
+    data object Local : WriteTarget
+}
+
 /** Why a project catalog call failed — shown instead of a bare 「request failed」. */
 sealed interface ProjectFault {
```

### 3. `app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectApi.kt`

```diff
@@ imports @@
 import com.xiufeigo.opencodeshell.ServerConfigStore
+import com.xiufeigo.opencodeshell.data.HomeProjectRecords
+import com.xiufeigo.opencodeshell.data.HomeProjectStore
 import com.xiufeigo.opencodeshell.data.ProjectPaths

@@ class HomeProjectApi @@
-class HomeProjectApi(private val serverStore: ServerConfigStore) {
+class HomeProjectApi(
+    private val serverStore: ServerConfigStore,
+    /**
+     * The client-local opened-project store — upstream's
+     * `childStore.projectMeta` (`global-sync/child-store.ts:335-351`). Null in
+     * previews / isolated hosts, which have no row to save onto either.
+     */
+    private val localStore: HomeProjectStore? = null,
+) {

@@ loadOriginal(): unregistered branch @@
                 if (source == null) {
-                    HomeProjectEdit.Original(
-                        projectID = null,
-                        name = "",
-                        color = null,
-                        startup = "",
-                        folderName = HomeProjectEdit.folderName(worktree),
+                    HomeProjectEdit.originalWithLocalMeta(
+                        HomeProjectEdit.Original(
+                            projectID = null,
+                            name = "",
+                            color = null,
+                            startup = "",
+                            folderName = HomeProjectEdit.folderName(worktree),
+                        ),
+                        localMetaOf(worktree),
                     )
                 } else { … }
@@
+    /** The client-local meta for [worktree] ([HomeProjectRecords.LocalMeta.NONE] without a store). */
+    private fun localMetaOf(worktree: String): HomeProjectRecords.LocalMeta =
+        localStore?.localMeta(worktree) ?: HomeProjectRecords.LocalMeta.NONE
+
+    /**
+     * `sync.project.meta(worktree, patch)` — the write for a worktree the
+     * server has no `project.id` for (`project-model.ts:46`). …
+     */
+    fun saveLocal(worktree: String, patch: HomeProjectEdit.Patch): ProjectWrite {
+        val store = localStore ?: return ProjectWrite.Failed(ProjectFault.NoServer)
+        return try {
+            store.setMeta(worktree, HomeProjectEdit.metaPatchFor(patch))
+            ProjectWrite.Ok
+        } catch (e: CancellationException) {
+            throw e
+        } catch (e: Exception) {
+            ProjectWrite.Failed(HomeProjectEdit.faultOf(e))
+        }
+    }

@@ rememberHomeProjectApi @@
-fun rememberHomeProjectApi(): HomeProjectApi {
+fun rememberHomeProjectApi(homeProjectStore: HomeProjectStore? = null): HomeProjectApi {
     val context = LocalContext.current
     val serverStore = remember(context) { ServerConfigStore(context).also { it.restore() } }
-    return remember(serverStore) { HomeProjectApi(serverStore) }
+    return remember(serverStore, homeProjectStore) { HomeProjectApi(serverStore, homeProjectStore) }
 }
```

**未改的既有契约**：`val api = api() ?: return ProjectLoad.Failed(ProjectFault.NoServer)` 一字未动 —— 没有服务器时 `loadOriginal` 仍然是 `Failed(NoServer)`。

### 4. `app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectEditDialog.kt`

```diff
@@ file header KDoc @@
  * script, saved as one [HomeProjectEdit.Patch] through
- * `PATCH /api/project/{id}` ([HomeProjectApi.save]).
+ * [HomeProjectApi.save] (`PATCH /api/project/{id}`) or, when the worktree has
+ * no server project, [HomeProjectApi.saveLocal] (the client-local
+ * `sync.project.meta` of `project-model.ts:41-46`) — the fork
+ * [HomeProjectEdit.writeTargetFor] makes, and the only thing that differs
+ * between the two. The panel itself is never disabled because of it: …

@@ the else -> branch (audit :222 / :234-250) @@
                 else -> {
                     val state = original
-                    val editable = state?.projectID != null && !saving
+                    // Upstream has **no** `disabled` on this panel at all
+                    // (`settings/workspaces/project.tsx` greps clean); the only
+                    // gate on the edit path is the write in flight
+                    // (`project-row.tsx:103` `disabled={store.editor?.saving}`).
+                    // A missing `project.id` is a transport fork upstream, not
+                    // an availability one (`project-model.ts:41-46`) …
+                    val editable = state != null && !saving
                     EditFields(
                         draft = currentDraft,
                         folderName = state?.folderName.orEmpty(),
                         editable = editable,
                         onPickImage = { pickImage.launch("image/*") },
                         onChange = { draft = it },
-                        // A greyed control that swallows the tap reads as a dead
-                        // button: …
-                        onDisabled = {
-                            Toast.makeText(
-                                context,
-                                context.getString(R.string.oc_home_project_edit_missing),
-                                Toast.LENGTH_SHORT,
-                            ).show()
-                        },
                     )
-                    if (state?.projectID == null) {
-                        Text(
-                            text = stringResource(R.string.oc_home_project_edit_missing),
-                            color = c.textMuted,
-                            fontSize = 12.sp,
-                            lineHeight = 17.sp,
-                            modifier = Modifier.padding(top = 8.dp),
-                        )
-                    }
                 }
             }

@@ the Save button (audit :266 / :269) @@
                 EditDialogButton(
                     label = stringResource(R.string.oc_common_save),
-                    enabled = state?.projectID != null && currentPatch?.isEmpty == false && !saving,
+                    // Upstream has no disabled-on-no-id rule: the only thing
+                    // that can turn Save off is "there is nothing to write" or
+                    // "a write is already in flight".
+                    enabled = currentPatch?.isEmpty == false && !saving,
                     onClick = {
                         val patch = currentPatch ?: return@EditDialogButton
-                        val projectID = state?.projectID ?: return@EditDialogButton
                         saving = true
                         scope.launch {
-                            val result = withContext(Dispatchers.IO) { api.save(projectID, patch) }
+                            // The transport fork, once, as a pure function
+                            // (`project-model.ts:41-46`): a server project
+                            // PATCHes, anything else writes the client-local
+                            // meta. Both are a successful save, so both toast
+                            // 「已保存」, fire [onSaved] and close.
+                            val result = withContext(Dispatchers.IO) {
+                                when (val target = HomeProjectEdit.writeTargetFor(state?.projectID)) {
+                                    is WriteTarget.Server -> api.save(target.projectID, patch)
+                                    WriteTarget.Local -> api.saveLocal(project.path, patch)
+                                }
+                            }
                             saving = false
```

```diff
@@ EditFields / ColorSwatch / EditDialogButton: the disabled-click plumbing is gone @@
 private fun EditFields(
     draft: HomeProjectEdit.Draft,
     folderName: String,
     editable: Boolean,
     onPickImage: () -> Unit,
     onChange: (HomeProjectEdit.Draft) -> Unit,
-    /** Fired instead of the control's own action while [editable] is false. */
-    onDisabled: () -> Unit = {},
 ) {

@@ four call sites @@
-                    onDisabledClick = onDisabled,
@@ ColorSwatch @@
 private fun ColorSwatch(
     color: String?,
     selected: Boolean,
     enabled: Boolean,
     onClick: () -> Unit,
-    onDisabledClick: (() -> Unit)? = null,
 ) {
@@
-                // Still a button while disabled when there is something to say
-                // about it; a handler-less disabled control stays inert.
-                enabled = enabled || onDisabledClick != null,
-                onClick = { if (enabled) onClick() else onDisabledClick?.invoke() },
+                enabled = enabled,
+                onClick = onClick,
@@ EditDialogButton @@
 private fun EditDialogButton(
     label: String,
     onClick: () -> Unit,
     enabled: Boolean = true,
     modifier: Modifier = Modifier,
-    /**
-     * Fired instead of [onClick] while [enabled] is false. …
-     */
-    onDisabledClick: (() -> Unit)? = null,
 ) {
@@
-                enabled = enabled || onDisabledClick != null,
-                onClick = { if (enabled) onClick() else onDisabledClick?.invoke() },
+                enabled = enabled,
+                onClick = onClick,
```

> 为什么把 `onDisabledClick` 整条拆掉而不是留一个空 handler：留 handler 会让
> `enabled = enabled || onDisabledClick != null` 恒真，于是「保存中」的按钮**可点但
> 无事发生** —— 比灰掉更坏。拆掉之后唯一能灰掉控件的窗口就是「写入在途」，
> 与上游 `project-row.tsx:103` 的 `disabled={store.editor?.saving}` 一致。
> 顺带也消灭了「禁用时弹「该项目未在服务器注册」」这条现在语义已错的文案路径
> （在途的 PATCH 期间点控件，说「未注册」是假的）。

### 5. `app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjects.kt`

```diff
@@ data class Project @@
         val iconOverride: String? = null,
+        /**
+         * The server `Project.id` this row's metadata came from — null when the
+         * row has none (a session-derived directory the catalogue does not
+         * list). Not rendered: it is the fact
+         * [HomeProjectEdit.localMetaApplies] reads to decide whether the
+         * client-local meta (`childStore.projectMeta`) may cover the server's
+         * (`runtime.tsx:151`). Appended last, with a default, so every existing
+         * named or positional construction compiles unchanged.
+         */
+        val serverID: String? = null,
     )

@@ merge(): the server-derived rows @@
                 sandboxes = project.sandboxes,
                 iconOverride = project.iconOverride,
+                // `serverProjectID` maps the server's pseudo project `global`
+                // (and a blank id) to null …
+                serverID = HomeProjectEdit.serverProjectID(project.id),
             )

@@ openRows() KDoc tail @@
+    // The client-local meta (`HomeProjectRecords.Project`'s `iconColor` /
+    // `iconOverride`) is the read side of upstream's `childStore.projectMeta`:
+    // it is applied **only** when the row has no server project
+    // ([HomeProjectEdit.localMetaApplies] — `runtime.tsx:151`), which is
+    // exactly the case the edit dialog saves it in. …

@@ openRows(): the row body @@
         return deduped.map { record ->
             val match = known.firstOrNull { ProjectPaths.sameDirectory(it.path, record.worktree) }
+            // `runtime.tsx:151`: `...(!metadata || metadata.id === "global" ?
+            // childStore.projectMeta : undefined)` — the client-local meta the
+            // editor wrote covers the server's **only** for a worktree with no
+            // server project. …
+            val local = record.takeIf { HomeProjectEdit.localMetaApplies(match?.serverID) }
             Project(
                 name = displayName(record.worktree, names, record.name),
                 path = record.worktree,
                 sessionCount = match?.sessionCount ?: 0,
                 vcs = match?.vcs,
-                iconColor = match?.iconColor,
+                iconColor = match?.iconColor ?: local?.iconColor,
                 sandboxes = sandboxesOf(record.sandboxes, match?.sandboxes.orEmpty()),
-                iconOverride = match?.iconOverride,
+                iconOverride = match?.iconOverride ?: local?.iconOverride,
+                serverID = match?.serverID,
             )
         }
```

### 6. `app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt`

```diff
@@ HomeScreen(): the self-serve api seam @@
     // Declared before the reads below: the effect fetches the display names
     // through this seam (see `serverNames`).
-    val projectApi = rememberHomeProjectApi()
+    // The **same** [HomeProjectStore] the column observes is handed in, not a
+    // second one: the editor's local-meta write (`sync.project.meta`,
+    // `project-model.ts:41-46`) has to publish through the instance the column
+    // collects, or a save would land in a store nobody is watching and the row
+    // would not move until the next cold start. `App.kt` owns the instance
+    // (`remember(context, serverKey)`) and this parameter is that handle.
+    val projectApi = rememberHomeProjectApi(homeProjectStore)
```

### 7. 新增测试 `app/src/test/java/com/xiufeigo/opencodeshell/HomeProjectLocalMetaV97Test.kt`（21 个用例）

见「测试证据」一节的清单。

### 8. 改了一条**过时**的既有 pin：`app/src/test/java/com/xiufeigo/opencodeshell/HomeProjectIconRefreshTest.kt`

**改的是哪一条**：`the avatar's override comes from the catalogue row, not the local record` 里的

```diff
-        // The client-local record cannot carry it — `HomeProjectRecords.Project`
-        // holds worktree/sandboxes/name only — …
-        val store = source("src/main/java/com/xiufeigo/opencodeshell/data/HomeProjectStore.kt")
-        assertFalse(store.contains("iconOverride"))
```

**为什么它过时**：这条断言的前提是「客户端记录**不可能**带 `icon.override`」。本次修复**正是**让记录能带它（上游 `sync.project.meta` 的 `icon.override`），所以这个前提被本次变更作废，断言必须改。

**为什么不算降强度**：新断言更强，不是更松 ——
- 旧断言只说「store 文件里不出现 `iconOverride` 这个词」（一条纯文本的**缺席**检查，改个名字就能骗过，实际上它也拦不住任何真实回归）；
- 新断言**同时**钉住两端：(1) `HomeProjectStore.kt` 里**确实有** `fun setMeta(directory: String, patch:`（本地写通道存在）；(2) **一个真实注册的**项目，它的头像**仍然**来自目录行 —— 给同一行塞一个本地 `iconOverride` 副本后，`openRows` 输出的仍是 `data:…SERVER` 而不是 `…LOCAL`；(3) 决定权是 `serverID`（`localMetaApplies(known[0].serverID) == false`），不是「有没有匹配上」。

方法名也相应从 `…not the local record` 改成 `…for a registered project`，类头 KDoc 里「头像没有客户端镜像」那段加了一句 v97 限定。

---

## 测试证据

环境：`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`，`.\gradlew.bat --console=plain`，每次跑前 `Set-Content .agent-gradle-lock "fix-project-edit"`，跑完 `Remove-Item`（两次跑都确认过锁文件已删除）。

### 命令 1（只跑新类）

```powershell
.\gradlew.bat --console=plain :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.HomeProjectLocalMetaV97Test"
```

```
> Task :app:compileDebugKotlin
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 11s
52 actionable tasks: 5 executed, 47 up-to-date
EXIT=0
```

### 命令 2（任务点名的回归闸门 + 新类 + 受影响的那条过时 pin）

```powershell
.\gradlew.bat --console=plain :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.HomeProjectStoreTest" `
  --tests "com.xiufeigo.opencodeshell.HomeProjectsTest" `
  --tests "com.xiufeigo.opencodeshell.HomeProjectFilterTest" `
  --tests "com.xiufeigo.opencodeshell.HomeProjectRowSeamV97Test" `
  --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest" `
  --tests "com.xiufeigo.opencodeshell.OpenCodeClientProjectsTest" `
  --tests "com.xiufeigo.opencodeshell.HomeProjectLocalMetaV97Test" `
  --tests "com.xiufeigo.opencodeshell.HomeProjectIconRefreshTest"
```

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 4s
52 actionable tasks: 1 executed, 51 up-to-date
EXIT=0
```

`app/build/test-results/testDebugUnitTest/*.xml` 逐类统计：

| 测试类 | 用例数 | 失败 |
|---|---:|---:|
| `HomeProjectFilterTest` | 25 | 0 |
| `HomeProjectIconRefreshTest` | 4 | 0 |
| `HomeProjectLocalMetaV97Test`（新） | 21 | 0 |
| `HomeProjectRowSeamV97Test` | 2 | 0 |
| `HomeProjectsTest` | 10 | 0 |
| `HomeProjectStoreTest` | 21 | 0 |
| `OpenCodeClientProjectsTest` | 9 | 0 |
| `StringsLocaleParityTest` | 3 | 0 |
| **合计** | **95** | **0** |

> 注：任务书里写的 `HomeRowSeamV97Test` 实际类名是 `HomeProjectRowSeamV97Test`（`app/src/test/java/com/xiufeigo/opencodeshell/HomeProjectRowSeamV97Test.kt`），已按实际类名跑。

### 命令 3（自加的扩大回归：所有引用了我改动到的文件/模型的既有 pin）

```powershell
.\gradlew.bat --console=plain :app:testDebugUnitTest `
  --tests "…ProjectIconOverrideTest" --tests "…HomeProjectEditModelTest" `
  --tests "…HomeProjectEditMatchTest" --tests "…HomeProjectRenameTest" `
  --tests "…HomeProjectCloseConfirmTest" --tests "…HomeAddProjectTest" `
  --tests "…HomeProjectsMergeTest" --tests "…HomeProjectClosedRowsTest" `
  --tests "…HomeProjectSandboxesTest" --tests "…ColdStartV64Test" `
  --tests "…MixedWindowsPathV62Test" --tests "…DialogExitResidueTest" `
  --tests "…DialogScrimResidueTest" --tests "…OcKeyboardOptionsTest" `
  --tests "…HomeSubpathSessionsV54Test" --tests "…HomeShellWave23bTest" `
  --tests "…HomeColdStartTest" --tests "…HomeProjectMenuTest" `
  --tests "…HomeProjectPencilSemanticsTest" --tests "…HomeSessionIconTest" `
  --tests "…NewSessionWorktreeListTest"
```

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 34s
52 actionable tasks: 1 executed, 51 up-to-date
EXIT=0
```

21 个类 **203 个用例、0 失败**（`ColdStartV64Test 6 / DialogExitResidueTest 7 / DialogScrimResidueTest 6 / HomeAddProjectTest 9 / HomeColdStartTest 4 / HomeProjectCloseConfirmTest 6 / HomeProjectClosedRowsTest 7 / HomeProjectEditMatchTest 7 / HomeProjectEditModelTest 13 / HomeProjectMenuTest 4 / HomeProjectPencilSemanticsTest 5 / HomeProjectRenameTest 15 / HomeProjectSandboxesTest 8 / HomeProjectsMergeTest 13 / HomeSessionIconTest 9 / HomeShellWave23bTest 19 / HomeSubpathSessionsV54Test 18 / MixedWindowsPathV62Test 12 / NewSessionWorktreeListTest 15 / OcKeyboardOptionsTest 10 / ProjectIconOverrideTest 10`）。

> 全量 `testDebugUnitTest` 与 `assembleDebug` 是 Lead 的闸门，本 agent 未跑（纪律）。

### 新类的 21 条断言（逐条语义）

**持久化（4）**
1. `everyMetaFieldSurvivesEncodeAndDecode` —— name/color/iconOverride/startup 四字段 encode→decode 全等。
2. `aLongIconOverrideSurvivesTheRoundTripByteForByte` —— 8 KB+ 的 `data:` URL 逐字节往返（长度也断言），挡住截断/转义。
3. `aRecordWrittenBeforeTheMetaExistedDecodesToTheDefaults` —— 旧格式 JSON（只有 worktree/sandboxes/name）不崩、得默认 null；且**再 encode 不写出这三个 key**（默认不落盘）。
4. `aJsonNullMetaReadsAsNothingRatherThanTheLiteralNull` —— JSON null 不变成字符串 `"null"`（`org.json` 陷阱）。

**字段级合并（5）**
5. `aNameOnlyPatchLeavesTheIconAndTheCommandAlone` —— 只改 name，color/iconOverride/startup 全部保留。
6. `aColorOnlyPatchLeavesTheImageAlone` —— 只改 color，name/iconOverride/startup 全部保留（上游 `icon={...previous.icon,...patch.icon}`）。
7. `anEmptyStringClearsAndANullLeavesTheFieldUntouched` —— 空 `MetaPatch` 是恒等；四个 `""` 全部清空。
8. `setMetaUsesTheStoresOwnDirectoryIdentityAndNeverInventsARow` —— `C:\src\app` ↔ `c:/SRC/app/` 命中；未打开的目录 / 空目录 / 空 patch 都是恒等。
9. `theRecordReadSeamAnswersWhatWasWritten` —— `localMeta` 读回写入值（且换拼写命中），未打开的目录读 `LocalMeta.NONE`。
10. `twinsAndARestoreDoNotWipeTheMeta` —— `decode` 愈合同目录双记录时，孪生记录带的 meta 不被抹掉。

**两个传输分叉（4）**
11. `theSaveRouteIsAProjectIdQuestionAndNothingElse` —— `Server("prj_1")` / `Local`（null、`""`、`"  "`、`"global"` 四条）。
12. `theLocalMetaCoversTheServerOnlyWhenThereIsNoServerProject` —— `localMetaApplies(null)=true`；`localMetaApplies("prj_1")=false`。
13. `aReloadedUnregisteredProjectOffersBackWhatWasSaved` —— `originalWithLocalMeta` 把四个字段填回；只设一个字段时其余保持原值。
14. `theServerPatchAndTheLocalPatchCarryTheSameTriState` —— 同一 `Patch` 转成 `MetaPatch` 后：值相同、未改字段仍为 null、`""` 仍是 `""`、全空 patch 仍 `isEmpty`。

**展示合并（1）**
15. `theColumnDrawsTheLocalIconForAnUnregisteredRowAndTheCatalogueForARegisteredOne` —— 未注册行走本地 color/override；同一目录一旦在目录里出现，**本地副本被忽略**，走 `mint`/`SERVER`；`global` 伪项目不夺取所有权。

**源码级 pin（6）**
16. `theDialogHasNoProjectIdGateLeft` —— 正则断言 `projectID != null &&` / `enabled = state?.projectID` / `projectID = state?.projectID ?:` **全文 0 命中**，并正向断言 `val editable = state != null && !saving` 与 `enabled = currentPatch?.isEmpty == false && !saving,`。
17. `theDialogRoutesTheSaveThroughThePureFork` —— 分派三行逐字钉住。
18. `theNotRegisteredCopyIsGoneFromThePanel` —— 弹层不再引用该 key；**同时**正向断言 `HomeScreen` 仍用它（所以 key 不能删）。
19. `theHostHandsThePanelTheStoreTheColumnObserves` —— `rememberHomeProjectApi(homeProjectStore)`、`App.kt` 的单实例 `remember`、列读的是同一个 `state`、`openRows` 的 `remember` 键含 `openedProjects.list`。
20. `theApiSeamIsInjectedNotSelfServed` —— `localStore: HomeProjectStore? = null` 构造注入、`setMeta` 调用、`localMetaOf` 读取；且 `ProjectLoad.Failed(NoServer)` 契约仍在。
21. `theDirectoryIdentityIsStillTheStoresOwn` —— 没有自创 key：`sameDirectory` 的盘符-less 折叠对 meta 写同样生效。

---

## 三语文案处理

**决定：key 保留，只去掉面板里的显示。** 依据是 grep —— 该 key 还有第二个使用者（`HomeScreen` 的「刷新 worktrees」失败 toast）。

grep 证据（`app/src` 全量，`.kt` + `.xml`）：

```
app\src\main\java\com\xiufeigo\opencodeshell\ui\home\HomeScreen.kt:713: ProjectWrite.NotRegistered -> context.getString(R.string.oc_home_project_edit_missing)
app\src\main\res\values\strings.xml:418: <string name="oc_home_project_edit_missing">This project isn\'t registered on the server</string>
app\src\main\res\values-zh-rCN\strings.xml:345: <string name="oc_home_project_edit_missing">该项目未在服务器注册</string>
app\src\main\res\values-zh-rTW\strings.xml:345: <string name="oc_home_project_edit_missing">該專案未在伺服器註冊</string>
app\src\test\java\com\xiufeigo\opencodeshell\HomeProjectLocalMetaV97Test.kt:402: assertFalse(dialog.contains("oc_home_project_edit_missing"))
app\src\test\java\com\xiufeigo\opencodeshell\HomeProjectLocalMetaV97Test.kt:406: home.contains("ProjectWrite.NotRegistered -> context.getString(R.string.oc_home_project_edit_missing)"),
```

修复前的另外两处引用（`HomeProjectEditDialog.kt:237` 的禁用 toast、`:244` 的提示块）已随闸门一起删除；**三份 `strings.xml` 一字未动**，`StringsLocaleParityTest` 3/3 通过。

「刷新 worktrees」那条保留是有意的、且不是本议题：上游同样没有「未注册 → 拒绝刷新 worktrees」这个行为（`worktree.ts` 的 refresh 路由按 `projectID` 走），但它是**另一个功能**的缺口，不在本轮授权范围内动，只在「未闭环」里记一条。

---

## 真机复验步骤（给 Lead / 测试员，Pixel_Tablet）

前置：装上本轮构建（`assembleDebug` + 装机是 Lead 的闸门）。目标项目用**一个服务器 `GET /api/project` 不返回的本地目录**（复现缺陷时用的那个，或任选一个非 git 目录 / 探测失败的目录）。

**A. 编辑可用（对应缺陷现场）**
1. Home → 项目行 ⋮ → 「编辑项目」。
2. 断言：**面板里不再有「该项目未在服务器注册」**这一行。
3. 断言：**名称输入框可聚焦、键盘可弹出、点进去有光标**（缺陷现场是 `enabled=false focusable=false`、4 种手法零字符）。
4. 断言：启动脚本输入框同样可输入。
5. 断言：此时「保存」是**灰的**（还没改任何东西）。
6. 在名称框输入 `E2ECHK_v97_改名`，断言字符真的落地（不是吞掉）。
7. 点一个颜色色板 → 断言面板里的预览头像变色；断言「保存」变**亮**。
8. 点「保存」→ 断言：toast「已保存」，面板关闭。
9. 断言：Home 上该行**名字**已改、**头像**已变色（同一帧生效，不需要重启）。
10. 断言：重开 ⋮ → 「编辑项目」→ 名称框里是刚才保存的名字、启动脚本也在（**重开面板读得回**）、色板选中态正确。

**B. 图标图片路径**
11. 同一个未注册项目 → 「选择图片」→ 选一张图 → 断言面板预览显示该图、色板整行消失（上游 `<Show when={!iconOverride}>`）→ 「保存」。
12. 断言：Home 行的头像是那张图；**强杀重启**后再看，还是那张图（验证落盘）。
13. 重开面板 → 点「清除图片」→ 断言色板重新出现 → 保存 → 断言行头像回到色板色（验证 `""` 清空而不是写入字面量）。

**C. 字段级合并不互踩（上游 `child-store.ts` 语义）**
14. 一次保存里：**改名 + 改色 + 换图** → 保存 → 断言三项全在。
15. 再开面板，**只改启动脚本**（别的都不碰）→ 保存 → 断言**名字、颜色、图标都还在**。
16. 反向：只改名字 → 保存 → 断言颜色和图标都还在。

**D. 已注册项目零回归（关键）**
17. 找一个 `GET /api/project` **能**返回的项目（vcs 那列有值的）。
18. ⋮ → 「编辑项目」→ 断言面板可编辑、保存走 `PATCH`（可看 `adb logcat | grep project` 的 200）。
19. 改名字 → 保存 → 断言 toast「已保存」、行改名、`GET /api/project` 侧也有（换台客户端看）。
20. **关键断言**：改完立刻强杀重启、且这次**不重新读目录**前不碰别的 —— 重启后行仍是新名字、新图标（服务器拥有，不该被本地遮蔽）。
21. 反证：给一个**已注册**项目，把本地记录里的 icon 弄脏（A6：先在它还没注册时改过图标，再让它被注册）→ 断言行头像走**服务器**那份。
22. 断言「刷新 worktrees」在未注册项目上仍弹「该项目未在服务器注册」（本轮**故意**保留的文案）。

**E. 旧数据兼容**
23. 装**旧版本** → 加两个项目、给其中一个改名 → 不卸载直接覆盖安装本轮构建。
24. 断言：旧项目仍列着、改过的名字仍在、其余 meta 为默认值（不崩、不显示多余字段）。
25. 断言：升级后给旧项目改图标 → 成功（说明旧记录被正常扩字段，不是被新字段挤掉）。

**F. 建议的取证**
每步一张 `adb exec-out screencap -p > research/v97_verify/<n>.png`，放 `research/v97_verify/` 下；面板截图务必能同时看到**输入框有光标**和**保存按钮亮/灰**两种态。

---

## 未闭环 / 未验到

1. **真机未验**。本轮只跑了纯 JVM 闸门；上面 A–F 六组断言**一条都没在设备上点过**。这是最大的未闭环项 —— 尤其 A 组的「4 种输入手法」缺陷现场本身只有真机能证伪（`BasicTextField` 的 `enabled` 在无 compose 测试依赖的环境里只能靠源码 pin + 肉眼）。
2. **`iconOverride` 的大小**。上游 localStorage 有 5 MB 配额；我方是 SharedPreferences，同样有量级限制，但**没有**加任何长度上限/降级。挑一张大图（> 512×512）实测是否会写盘失败或卡顿 —— 未验。`saveLocal` 里 `store.setMeta` 抛异常会走 `ProjectWrite.Failed`，但 `SharedPreferences.apply()` 是异步落盘，**写盘失败不会在调用点抛**，所以极端情况下可能出现「toast 已保存但重启丢失」。未处理。
3. **「刷新 worktrees」在未注册项目上的行为**仍是「拒绝 + 文案」，上游没有这个拒绝。属相邻缺口，本轮未授权改，只记。
4. **A4/A5/A7/A9 差异**（拖拽上传、点击已上传图即移除、色板与自定义图互斥、启动命令变量提示）里，**A7 色板互斥本轮已在上游对齐路径上**（沿用既有的 `if (draft.iconOverride == null)`，本轮未改动），A4/A5/A9 仍未做 —— 那是上一轮审计列的「可选补齐」，不在本轮授权内。
5. **`mergeRestored` 的方向性**：`adopt` 对「内存记录是默认值」的情况会**继承磁盘值**而不是清空它。也就是说「保存时清掉了名字，紧接着（启动读盘竞态）发生 restore」这一理论路径上，清除会被还原。实际不可达（保存需要弹层，弹层需要首帧，而 `restore` 在首帧前就跑完了），但这是有意选的保守方向，代码里已写明，未做竞态压测。
6. **全量闸门 / 装机 / 真机截图**未做（纪律上属 Lead）。
7. `serverID` 只在 `merge` 的**服务器行**上填；`merge` 的会话派生行（`extra`）保持 null —— 这是对的（目录里没有它），但也意味着「一个已被服务器删除、历史记录里仍有它的项目」会重新落回本地 meta 通道。语义与上游 `!metadata` 一致，未单独验。
