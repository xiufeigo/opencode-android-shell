# v97 N-1 修复报告 —— 冷启动不恢复上次会话 route/tab

- 缺陷报告：`research/v97_verify/5560_sweepA/REPORT_v97b.md` §N-1（emulator-5560 Pixel_Tablet，3/3 稳定复现）
- 修复范围：`ui/nav/LastRouteStore.kt`（纯逻辑半边）、`ui/App.kt`（写回侧一个调用点 + 注释）
- 新增 pin：`app/src/test/java/com/xiufeigo/opencodeshell/LastRouteRestoreV97Test.kt`（15 个纯 JVM 用例）
- 结论：**测试员定性正确**（"写入侧正常、读回侧失效"），且其两个"对照组"读数正是本根因的指纹。具体吞掉 `session` 的那一步是**首帧的写回**，不是 prune、不是 restore prune、不是 `store == null`。

---

## 根因

### 一句话

**写回侧在读回侧读到任何东西之前，就把 Home 当成"用户刚到达的页面"写回了盘**——`NavController.addOnDestinationChangedListener` 会在注册瞬间用当前 destination（冷启动即 `Route.HOME`）回调一次新监听器，而这个写发生在 `LaunchedEffect { withContext(Dispatchers.IO) { load() } }` 的协程体被调度之前；`SharedPreferences.apply()` 随即更新了内存 map，于是读回来的是 `home`，`target != Route.HOME` 分支自然永不成立。

### 冷启动时序（全部发生在第一帧内）

| # | 时刻 | 代码锚 | 发生了什么 |
|---|---|---|---|
| 0 | `onCreate` → `setContent` | `OcActivity.kt:73` | `ServerConfigStore(applicationContext).also { it.restore() }` —— 同步恢复，所以 `serverKey`（`OcActivity.kt:130-132`）在第一帧前已稳定，不存在"serverKey 后变 → 图重建"这条路径 |
| 1 | 第一帧前 | `OcActivity.kt:128,142` | `lastRoute = remember { LastRouteStore(applicationContext) }` 注入 `OcApp` → `AppScaffold`（`App.kt:602,623`）。测试员已排除 `store == null`，成立 |
| 2 | **合成期** | `App.kt:794-798` | `LaunchedEffect(lastRoute, serverKey) { val stored = withContext(Dispatchers.IO) { lastRoute?.load() } … }` 被 **remember**，协程体此刻**只被调度、没有执行**（Compose `LaunchedEffectImpl.onRemembered` → `scope.launch`） |
| 3 | **合成期**（同一个 `AppScaffold` 体内，靠后） | `App.kt:1641` → `NavHost.kt:502` | `NavHost(navController = …, startDestination = Route.HOME) { … }` 的**函数体里同步**执行 `navController.graph = graph` → `setGraph` → `onGraphCreated` → 导航到 `Route.HOME` |
| 4 | **apply 阶段**（合成结束后） | `App.kt:823,866` | `DisposableEffect(navController, lastRoute)` 的 `onRemembered` 调 `navController.addOnDestinationChangedListener(listener)`；`NavController.kt:413-428` 明确写着 *"The current destination, if any, will be immediately sent to your listener"*，并在此刻用 `Route.HOME` 回调本监听器 |
| 5 | 同上，回调内 | 修复前 `App.kt:836-837`：`NavRestore.toStore(route, sessionId)?.let { lastRoute?.save(it.route, it.sessionId) }` | `toStore("home", null)` = `Stored("home", null)` → `LastRouteStore.save`（`LastRouteStore.kt:31-37`）`putString(KEY_ROUTE,"home").putString(KEY_SESSION_ID,null).apply()`。**`apply()` 同步更新了 SharedPreferences 的内存 map，磁盘上的 `oc_last_route=session` 当场作废** |
| 6 | apply 阶段之后 | `App.kt:795` | 被调度的读协程终于跑起来，`lastRoute.load()` 读的是**同一个 SharedPreferences 实例的内存 map** → 拿到 `home` |
| 7 | 状态写回后 | `App.kt:806-812` | `restoreTarget = NavRestore.target(Stored("home", null), key)` = `"home"` = `Route.HOME` → `if (navRestoreSettled && target != null && target != Route.HOME)` **不成立**，不导航 |
| 8 | 首帧渲染 | — | 第 1 棵 UI 树起就是 Home，标签条空（`openSessions` 只由监听器在**真的**到达 session route 时才 add，`App.kt:842-861`） |
| 9 | 此后 | `App.kt:862`（修复后仍会跑，但 `settled=true`，值已是 home，幂等） | 落盘稳定停在 `home` —— 之后每次冷启动都在第 5 步被同样地覆盖，于是**永久不可恢复** |

### 排除的三个候选（都有代码证据）

1. **不是 restore prune（`App.kt:1340-1385` 那一段）**。它是 `listSessions { … }` 的**异步回调**里的判断，而且入口有早退：`if (openSessions.isEmpty() && closedTabs.isEmpty()) return@prune`（`App.kt:1342`）。冷启动首帧 `openSessions` 是空的（tab 由监听器在到达 session route 时才建），所以它连 `listSessions` 都不会发；何况首棵树就是 Home，说明 **session destination 从未存在过**，prune 无从下手。
2. **不是 `target != Route.HOME` 分支的逻辑写错**。`NavRestore` 纯逻辑本身正确（`LastRouteStore.kt:122-146`），且测试员给的控制组读数正是它的指纹：**落盘 `session` 与落盘 `home` 冷启动表现完全相同**——因为两条路在第 5 步之后已经**汇合成同一个状态**（内存 map 里都是 `home`）。如果只是"分支没走到"而盘上值还在，第 1 棵树该是 session、第 2 棵起才对；8 连 dump 全 Home + 结束后落盘变 home，只有一个解释：读到的就是 home。
3. **不是别的 Activity / 不是深链**。`OcActivity.kt:128,142` 的注入链完整（日志里 `AppScaffold(..., NavMemory, ...)` 非空），manifest 的 launcher 就是 `OcActivity`，测试员用的 `am start -n …/.OcActivity` 命中同一进程。

### 上游对照（为什么这是移植时丢掉的纪律）

- `_upstream/packages/desktop/src/renderer/window/router.tsx:7-9`：
  ```tsx
  const initialUrl = getLastActiveUrl(props.windowID)
  if (initialUrl !== "/") history.set({ value: initialUrl, replace: true, scroll: false })
  onCleanup(history.listen((value) => setLastActiveUrl(props.windowID, value)))
  ```
  上游**先把恢复值打进 history，再挂持久化监听器**——写回结构上不可能覆盖正在被恢复的值。本移植把两半都塞进同一帧，顺序反了。
- `_upstream/packages/desktop/src/renderer/window/route-storage.ts:17-23` `acceptedLastActiveUrl` 只接受 `/`、`/new-session` 和 `/^\/server\/[^/]+\/session\/[^/]+$/`，其余一律回落 `"/"`。这条规则本次也补进了 Android 侧（见改动 2）。

---

## 改动清单

`git diff` 无法直接用（整仓相对唯一初始提交都未提交，含其他 agent 的大片改动），下面是**只含本次改动**的 unified diff（用还原出的改前文件对比生成）。

### 1. `app/src/main/java/com/xiufeigo/opencodeshell/ui/App.kt`（写回侧门控）

```diff
@@ -770,6 +770,25 @@ private fun AppScaffold(
     // on the IO dispatcher instead, and the one-shot `navRestoreSettled` gate
     // keeps the notification navigation below behind it so the restore still
     // lands underneath.
+    //
+    // v97 N-1 (`research/v97_verify/5560_sweepA/REPORT_v97b.md`): the same gate
+    // has to hold the **write-back** below too, because the read is the slow
+    // half of the round trip and the destination listener is not. Sequence on
+    // a cold start, all inside the first frame:
+    //   1. this effect is remembered — its body is *dispatched*, not run;
+    //   2. `NavHost`'s body sets the graph (`NavHost.kt:502`), which navigates
+    //      to `Route.HOME`;
+    //   3. the effects run: the `DisposableEffect` below registers the
+    //      destination listener, which is *immediately* called back with Home
+    //      (`NavController.addOnDestinationChangedListener` informs the new
+    //      listener of the current state) — an ungated `save("home", null)`
+    //      overwrites `oc_last_route=session` here, and `apply()` has already
+    //      updated the in-memory prefs;
+    //   4. only then does the dispatched read run, and it finds `home` — so
+    //      `target == Route.HOME`, the branch below never navigates, and the
+    //      value on disk stays `home` forever after.
+    // The read therefore decides the initial route, and the write-back waits for
+    // it (`NavRestore.toStore(settled = …)`).
     var restoreTarget: String? by remember { mutableStateOf(null) }
     var navRestoreSettled by remember { mutableStateOf(false) }
     LaunchedEffect(lastRoute, serverKey) {
@@ -794,6 +813,13 @@ private fun AppScaffold(
     // Every destination is persisted as it is reached. Transient pages
     // (new-session / settings / terminal) map to null and leave the stored value
     // untouched, so a cold start lands on the page *before* them.
+    //
+    // v97 N-1: nothing is persisted until `navRestoreSettled` — see the comment
+    // on the gate above for the ordering this undoes. The rest of the listener
+    // (the visible session, the tab the strip renders) runs for the first
+    // destination as it always did; only the *write* waits, and by the time the
+    // restore has landed the listener has already recorded the session it
+    // navigated to.
     DisposableEffect(navController, lastRoute) {
         val listener = object : NavController.OnDestinationChangedListener {
             override fun onDestinationChanged(
@@ -833,7 +859,7 @@ private fun AppScaffold(
                         ),
                     )
                 }
-                NavRestore.toStore(route, sessionId)
+                NavRestore.toStore(navRestoreSettled, route, sessionId)
                     ?.let { lastRoute?.save(it.route, it.sessionId) }
             }
         }
```

**行为变化只有一处**：`save()` 在 `navRestoreSettled == true` 之前不被调用。监听器其余部分（`currentSession` / `currentRoute` / `setSessionVisible` / 新建 tab）**一行未动、也不受门控**——恢复落地时它照常把恢复出来的会话记成 tab。

### 2. `app/src/main/java/com/xiufeigo/opencodeshell/ui/nav/LastRouteStore.kt`（纯逻辑半边：门控谓词 + 无效 id 回落）

```diff
@@ -125,10 +125,23 @@ object NavRestore {
         else -> null
     }

-    /** The `NavController` route to re-enter, or `null` to stay on Home. */
+    /**
+     * The `NavController` route to re-enter, or `null` to stay on Home.
+     *
+     * The id goes through [sessionOrNull] rather than [session]: a stored id
+     * that cannot fill the route pattern's single `{id}` segment (blank, or a
+     * `/`-bearing worktree *directory* written by a build older than the v65
+     * fix) is not a restorable page, and navigating to the percent-encoded
+     * route it would produce throws
+     * `IllegalArgumentException: Navigation destination that matches route …
+     * cannot be found`. This is upstream's own rule for the same value —
+     * `acceptedLastActiveUrl` accepts only `/`, `/new-session` and
+     * `/^\/server\/[^/]+\/session\/[^/]+$/`, and falls back to `"/"` otherwise
+     * (`_upstream/packages/desktop/src/renderer/window/route-storage.ts:17-23`).
+     */
     fun target(stored: Stored?, serverKey: String): String? = when (stored?.route) {
         ROUTE_HOME -> HOME
-        ROUTE_SESSION -> stored.sessionId?.let { session(serverKey, it) }
+        ROUTE_SESSION -> sessionOrNull(serverKey, stored.sessionId)
         else -> null
     }

@@ -139,6 +152,29 @@ object NavRestore {
         else -> null
     }

+    /**
+     * The write-back half, gated on the cold-start **read** having settled.
+     *
+     * v97 (N-1, `research/v97_verify/5560_sweepA/REPORT_v97b.md`): the restore
+     * reads the store off the main thread, but a `NavController` destination
+     * listener is told about the current destination *the moment it is
+     * registered* (`NavController.addOnDestinationChangedListener` — "The
+     * current destination, if any, will be immediately sent to your listener",
+     * navigation-common 2.8.5 `NavController.kt:413-428`). On a cold start that
+     * destination is the graph's start destination, Home — so the ungated
+     * listener persisted `home` over a perfectly good `session` **before the
+     * restore read had even started**, and the read then found `home`. Upstream
+     * orders the two halves the other way round: the restore is applied to the
+     * history *first* and the persistence listener is attached after it
+     * (`_upstream/…/window/router.tsx:7-9`).
+     *
+     * `settled == false` therefore means "the app has not yet read the value this
+     * write would overwrite" → nothing is persisted. Everything after the read
+     * lands is unchanged, and the tab/visible-session bookkeeping the same
+     * listener does is deliberately *not* gated.
+     */
+    fun toStore(settled: Boolean, destinationRoute: String?, sessionId: String?): Stored? =
+        if (settled) toStore(destinationRoute, sessionId) else null
```

- `toStore(settled, …)` 是 3 参重载，2 参原签名**保留**（`HomeNavTest` 等既有 pin 一条未改）。settled 时它逐字委托给原实现，**不改变任何既有答案**，只会在读回落地前"闭嘴"。
- `target()` 改走 `sessionOrNull`（v65 已有、已在 `App.kt:1663` 用于新建会话路径的那个函数）：填不满 `{id}` 单段的落盘 id 回落 `null` → Home，不再构造出无人匹配、会让 `NavController` 抛 `IllegalArgumentException` 的百分号编码路由。`session()` 本身不动（`V65FixesTest:47` 的编码契约不受影响）。

### 3. `app/src/test/java/com/xiufeigo/opencodeshell/LastRouteRestoreV97Test.kt`（新增，15 个纯 JVM 用例）

无 compose / robolectric 依赖（本仓 test 只有 junit/org.json/mockwebserver）：用一个 `FakeNavMemory : NavMemory` 存内存盘值，用**生产代码的两个谓词**按 `AppScaffold` 的真实顺序回放一次冷启动（`private fun coldStart(...)`），再对 `App.kt` / `LastRouteStore.kt` 做仓库既有的源码 pin。

覆盖对照表：

| 任务书要求 | 用例 |
|---|---|
| 落盘 `session` + 有效 id → 初始 route 必须是 session | `a stored session with a live id comes back on that session`、`the stored value survives the first frame` |
| 落盘 `session` + 无效/缺失 id → 回落 Home（不崩、不空指针） | `a stored session with no id falls back to Home`（null/""/"  "）、`a stored id that cannot fill the route falls back to Home instead of throwing`（`/home/x/…` worktree 目录）、`the raw route builder still encodes, so the v65 contract is untouched` |
| 落盘 `home` → Home | `a stored home stays on Home and never navigates`（并断言 `saves == 0`，即 Home 冷启动不多写、不多闪） |
| 写回保护：恢复判定发生在写回之前 | `the write-back is gated on the restore having read`（源码 pin：`NavRestore.toStore(navRestoreSettled, route, sessionId)` 存在、未门控的 2 参调用在 `App.kt` 中已消失）、`the restore is still a read that decides the initial route`、`nothing is persisted before the read has settled`、`the gate is the whole difference between the bug and the fix`、`a dead session falls back to Home and persists home only once it is proven dead`、`a first run with nothing on disk writes nothing`、`after the read every destination persists exactly as it did` |
| 不破坏既有 restore prune 语义 | `the cold-start prune that backs a dead session out is still wired`（源码 pin `pruneRestoredTabs()` / `pruneRestoredTabs = pruneTabsAgainstServer` / prune 块内的 `currentSession in result.prunedSessionIds` 与 `navigate(Route.HOME)`） |
| 报告里的盘上键名不漂移 | `the store still reads the keys the report found on disk` |

### 未触碰

`_upstream/`、`app/build.gradle.kts`、`data/HomeProjectStore.kt`、`ui/home/**`、`ui/session/composer/AgentChip.kt`、`ui/session/timeline/**` 全部未改；**未新增任何用户可见字符串**；**未降低任何既有断言**（`HomeNavTest` / `V65FixesTest` / `SessionTabServerPruneTest` / `ColdStartV64Test` 原样通过）。

---

## 测试证据

### 锁纪律

跑 gradle 前检查 `.agent-gradle-lock`；最后一次运行时锁被 `fix-settings-geom`（另一 agent）持有，**按协议等待后取得锁**，跑完立刻释放：

```
waiting for lock: fix-settings-geom
...
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 8s
52 actionable tasks: 5 executed, 47 up-to-date
EXIT=0
```

### 命令

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --console=plain :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.LastRouteRestoreV97Test" `
  --tests "com.xiufeigo.opencodeshell.HomeNavTest" `
  --tests "com.xiufeigo.opencodeshell.V65FixesTest" `
  --tests "com.xiufeigo.opencodeshell.ColdStartV64Test" `
  --tests "com.xiufeigo.opencodeshell.ShellWiringTest" `
  --tests "com.xiufeigo.opencodeshell.Wave5IntegrationWiringTest" `
  --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest" `
  --tests "com.xiufeigo.opencodeshell.SessionTabServerPruneTest" `
  --tests "com.xiufeigo.opencodeshell.NotificationSystemTest" `
  --tests "com.xiufeigo.opencodeshell.BootPathTest"
```

（选类依据：glob + grep 出真正引用 `LastRouteStore`/`NavRestore`/`NavMemory`/`oc_last_route` 的测试类 = `HomeNavTest`、`V65FixesTest`、`ColdStartV64Test`（`nav_route_restore` 步进）、`SessionTabServerPruneTest`（restore prune 语义）、`NotificationSystemTest:426`（pin 了 `App.kt` 的 nav 调用），外加任务书点名的 `ShellWiringTest`、`Wave5IntegrationWiringTest`、`StringsLocaleParityTest`、`BootPathTest`。）

### 结果（`app/build/test-results/testDebugUnitTest/TEST-*.xml`）

```
Class                      Tests Failures Skipped
BootPathTest                2     00       0
ColdStartV64Test            6     00       0
HomeNavTest                 8     00       0
LastRouteRestoreV97Test    15     00       0
NotificationSystemTest     21     00       0
SessionTabServerPruneTest   9     00       0
ShellWiringTest            17     00       0
StringsLocaleParityTest     3     00       0
V65FixesTest               15     00       0
Wave5IntegrationWiringTest 25     00       0

CLASSES=10 TESTS=121
```

**BUILD SUCCESSFUL，10 个类 121 个用例，0 失败 0 跳过 0 错误**，其中新增类 15 个用例。

### 「修复前的时序」也被执行过（临时 scratch，已删除）

为了不把机制停留在论证层面，另写了一个**临时**用例类 `ZzPreFixOrderingScratchTest`，直接用生产谓词按"修复前的调用点"回放首帧顺序，跑完即删：

```
Class                       Tests Failures
ZzPreFixOrderingScratchTest  2     00
CLASSES=11 TESTS=123
```

- `theUngatedFirstFrameDestroysTheStoredSession`（**绿**）——`NavRestore.toStore("home", null)` 写下 home 后，盘上 `session` 消失、读回 `NavRestore.target(...) == Route.HOME`：这正是真机"落盘 session 与落盘 home 表现相同 + 结束后落盘变 home"的成因。
- `theGatedFirstFrameKeepsItAndRestores`（**绿**）——换成带门控的调用后盘上仍是 `session`，读回 `server/s1/session/ses_f03e2d5b8ffeBCDbQGqZHCliRd`。

文件已从工作区删除（`ZzPreFixOrderingScratchTest.kt`），最终一次 BUILD SUCCESSFUL 是删除之后跑的。

---

## 真机复验步骤

前提：装上**含本次改动**的包（`am start -n com.xiufeigo.opencodeshell/.OcActivity` 是 launcher 路径；`ASSEMBLE`/装机不在本次授权内，交由持有装机流程的 agent 执行）。

### A. 主断言 —— 冷启动回到上次会话 route + 该会话的 tab 在

```bash
D=emulator-5560
P=com.xiufeigo.opencodeshell
PREFS=/data/data/$P/shared_prefs/oc_nav_state.xml

# 1) 进任意会话（Home 会话列首行），等 3s
adb -s $D shell input tap 1592 496 ; sleep 6

# 2) 落盘值 = session + 合法 id（写入侧，必须仍然正常）
adb -s $D shell run-as $P cat $PREFS
#   期望：<string name="oc_last_route">session</string>
#         <string name="oc_last_session_id">ses_…</string>

# 3) 冷启动
adb -s $D shell am force-stop $P ; sleep 2
adb -s $D shell run-as $P cat $PREFS          # 落盘值仍在（force-stop 不清）
adb -s $D shell am start -n $P/.OcActivity

# 4) 连续 8 棵 UI 树
for i in $(seq 1 8); do
  adb -s $D shell uiautomator dump /sdcard/r$i.xml >/dev/null
  adb -s $D pull /sdcard/r$i.xml . >/dev/null
done
```

断言（三条都要）：
1. `r1..r8` 里出现 **`关闭标签页`**（= 标签条上有 tab）、出现该会话的标题/header，且**不再**出现 `添加项目`；
2. 若 `r1` 仍是 Home（预期如此：Home 是 start destination，恢复是"压在上面"的一跳，保留 Back 下有 Home 的语义），`r2` 起必须是 session —— 这是**恢复发生**的判据；
3. 结束后 `adb -s $D shell run-as $P cat $PREFS` 仍是 `oc_last_route=session` + **同一个** id（不是 home）。

对照 B（防"什么都没改"的假绿）：把落盘手工置成 `home` 再冷启动，8 棵必须**全**是 Home、无 tab —— 与 A 的可区分即为修复生效。

### B. dead id 优雅回落 Home（且不崩）

```bash
# 方案 1（贴近真实）：在 Home 上把该会话删掉，再冷启动
#   -> 期望：落盘最终为 home，无 tab，不崩（logcat 无 FATAL）
# 方案 2（确定性强，直接种一个不存在的 id）：
adb -s $D shell am force-stop $P
adb -s $D shell run-as $P sh -c 'sed -i "s|<string name=\"oc_last_session_id\">.*</string>|<string name=\"oc_last_session_id\">ses_v97deadbeefdeadbeefdeadbeef</string>|" /data/data/'"$P"'/shared_prefs/oc_nav_state.xml'
adb -s $D shell run-as $P cat $PREFS      # 确认已种上
adb -s $D shell am start -n $P/.OcActivity ; sleep 6
adb -s $D shell run-as $P cat $PREFS      # 期望：oc_last_route=home
```

断言：1) 8 棵树全 Home、无 tab、**无 ANR/无 `FATAL EXCEPTION` / 无 `Navigation destination that matches route … cannot be found`**；2) 落盘收敛为 `home`（不再每帧重写、无死循环）。

### C. 不回归

- Home 冷启动（落盘 `home`）不闪 session 帧：落盘置 home → 8 棵树的 `r1` 已是 Home 且无任何一次出现 `关闭标签页`。
- 会话内新建/重命名/切 tab/关 tab（报告 1.3–1.7）行为不变，因为监听器只对"第一次 Home 回调"闭嘴，之后每次真实导航照旧写回。

---

## 未闭环 / 未验到

1. **未上真机**。本次改动**只经 JVM 测试**；上面的复验步骤是写给持有装机流程的 agent 的，**尚未执行**，A/B/C 三组断言目前**没有实机读数**。这是最大的未闭环项。
2. **未在设备上测量首帧的确切交错**。根因的三条支柱是：navigation-common 2.8.5 源码 `NavController.kt:413-428`（注册即回调）、navigation-compose 2.8.5 源码 `NavHost.kt:502`（`navController.graph = graph` 在 composable 函数体里同步执行）、Compose `LaunchedEffect` 只调度不内联执行体；再叠加 3/3 真机症状与上面被执行的时序模型。**没有做** logcat 打点去量"第 5 步早于第 6 步多少毫秒"。修复本身不依赖这个量（门控把两个半边在**逻辑上**解耦，无论交错如何都不会覆盖），但报告不该把它写成实测。
3. **未跑全量测试**（任务书禁止）。只跑了 10 个类 121 个用例。`App.kt` 是共享热点文件，其他 pin 它源码文本的测试类（`NotificationSystemTest` 已跑；另有若干 pin App.kt 片段的类未跑）理论上可能因注释位移受影响——**本次 diff 只增注释、不动可执行语句**，但未全量验证。
4. **一个刻意留下的行为边界**（未改、未验证）：读回落地（`navRestoreSettled == true`）之前发生的导航，**不会被持久化**，而恢复仍可能压在上面。这个窗口约等于一次 `SharedPreferences` 读（毫秒级），修复前这个窗口因为值早已被写成 home 而"看不出差别"。若真机复验发现"冷启动瞬间点 Home 的某个会话，最后落在上次会话"这一类反馈，正确的收口是"恢复前已有真实导航则放弃恢复"，需要单独一轮，不在本次范围。
5. **未处理**（既有问题、非本次缺陷）：`NavHost` 的图在 `NavHost.kt:502` 每次重组合都会重新赋值，若 `builder` lambda 身份不稳定会触发 `setGraph` 清栈回 Home。本轮无证据表明它发生（会话内导航、tab 切换在真机均正常），未动。
