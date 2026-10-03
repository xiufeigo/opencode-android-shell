# Review 面板 + Home 会话搜索 —— 缺陷修复报告（v97 fix）

- 轮次：v97 修复（**已改源码 + 已补 JVM pin**）
- 诊断来源：`research/v97_fix/REVIEW_HOME_SEARCH_DIAG.md`
- 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
- 纪律：只跑 `:app:testDebugUnitTest --tests "<精确类名>"`；未 assembleDebug / 未 clean / 未装机

---

## 总览

| 编号 | 结论 | 本轮动作 |
|---|---|---|
| **N4** | 原始描述为**误判**（空态与结果行同组件互斥；过滤每次重算） | **未改** `remember` key。派生真缺陷（浮层遮挡不足）**已修** |
| **N5** | 原始描述为**误判**（过滤器已清空、2 行是软键盘挤压；Refresh evict 是设计） | **未改** `ReviewFileFilter` 匹配语义。派生真缺陷（搜不到未展开目录）**已修** |
| **N6-a** | 真缺陷 | **已修**：瞬时失败不再烧掉候选 ref（重试不再弱于首次） |
| **N6-b** | 真缺陷（客户端截断已被证伪，缺的是不完整性标记） | **已修**：失败态不再把 fallback/可能不完整的统计当本 scope 的总量渲染 |

> ⚠️ **对诊断报告的一处修正（重要）**：报告 §N6-② 认定「移动端**没有任何**组件在 evict 之后重发 diff」。
> 实际代码里该通路**已经存在**——`SessionScreen.kt:2626`
> `LaunchedEffect(mobileReviewScope, reviewReprobeKey) { … requestVcsDiff(mobileReviewScope) }`，
> 而 `reloadAfterEvict()`（`:989-991`）在 evict 回调里**无条件** `reviewReprobeKey++`（成功与失败都加），
> 且 `reviewReprobeLocation` 成功时还 `forgetReviewVcsProbe(dir)`（`:5471`）清掉探测记账。
> ⇒ **N6-a 的真实断链不在"没有重发"，而在"重发时已经被削弱的候选"**：
> 超时把目录永久标记为已探测（`:5363`），`reviewVcsCandidates()` 随即只返回 canonical ref，
> 于是「Refresh → evict → 重发」虽然发生了，重发用的却是**比首次更弱的候选集**。
> 用户看到的效果同样是「点了没反应」，但根因与最小修法不同。本轮按后者修。

---

## N6-a — 瞬时超时永久烧掉候选 ref（最高优先，已修）

### 根因

`vcs/diff?mode=working` 在巨型工作区上会挂起（本仓库自述：2.0.11 实测 25 s 无响应，
`ChatViewModel.kt:5323-5328`），客户端 20 s 墙钟超时
（`REVIEW_VCS_TIMEOUT_MS`）。旧代码把这个**瞬时**失败当成确定结论记账：

```kotlin
// 旧：ChatViewModel.kt:5363
if (!answered) markReviewVcsProbed()
```

```kotlin
// 旧：ChatViewModel.kt:5407-5412
private fun reviewVcsCandidates(): List<String?> {
    val dir = directory ?: return listOf(null)
    reviewVcsRefs[dir]?.let { return listOf(it) }
    if (dir in reviewVcsProbed) return listOf(dir)   // ← fresh-graph 候选被永久丢弃
    return ReviewVcsReprobe.refs(dir)
}
```

⇒ 第一次超时后，每次重试只试 canonical ref，**fresh-graph 变体 `<dir>/.` 永久退场**。
`reviewVcsProbed` 的原设计意图（`:5222-5223`「a refresh cannot re-issue the probe」）
是"家目录 working diff 太贵，只探一次"，但它对**超时**和**拒绝**一视同仁。

### 改动 1（新增纯函数，可 pin）：`ReviewVcsProbePlan.kt`

新增 `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewVcsProbePlan.kt`（零 Android 依赖）：

```kotlin
enum class Failure { TRANSIENT, PERMANENT }

fun candidates(directory: String?, remembered: String?, probed: Boolean): List<String?>
fun shouldMarkProbed(failure: Failure): Boolean = failure == Failure.PERMANENT
fun failureOf(code: Int?): Failure = if (code != null && code in 400..499) PERMANENT else TRANSIENT
```

`failureOf` 的判据：**只有 4xx 算确定性拒绝**（服务器明确拒绝这个 ref，再问一次结果不会变）；
无状态（超时/IO/取消）= 瞬时；5xx = 服务器自己失败 = 瞬时。

### 改动 2：`ChatViewModel.kt`

```diff
-            if (!answered) markReviewVcsProbed()
+            val settled = diffs != null || answered
+            if (!settled && ReviewVcsProbePlan.shouldMarkProbed(failure ?: ReviewVcsProbePlan.Failure.TRANSIENT)) {
+                markReviewVcsProbed()
+            }
```

并新增 `reviewVcsFailureCode(e)`（同时读 `V2Api.ApiException.code` 与
`OpenCodeClient.ApiException.code`），把循环里最坏的失败类别累积起来。
`reviewVcsCandidates()` 改为委派 `ReviewVcsProbePlan.candidates(...)`。

**注意保留的语义**：某个 ref **答了**（哪怕空列表）仍是关于该 location 的确定事实，
此时照旧按"已探测"记账——一个干净仓库每次都答空，不该每次重试都重走 fresh-graph。
只有**全部候选都抛异常**（什么答案都没拿到）才进入新判据。

### 为什么不算降强度

- 原断言「超时后不再重发 fresh-graph」是**缺陷本身**，不是被保护的正确行为；它与
  `ReviewVcsReprobe` 存在的理由（`:613-647`，为 predates-`git init` 的 location 兜底）直接冲突。
- 保留的强度：4xx 仍然记账、答过仍记账、`forgetReviewVcsProbe` 仍在 evict 成功后清账。
  放宽的**只有**"无状态的超时/IO 不再永久记账"这一条。

---

## N6-b — diff 统计缺不完整性标记（已修）

### 根因

客户端**没有**截断（`V2Api.callBody` 全量读 body、`vcsDiff` 无 limit、
`ReviewDiff.totals` 全量累加、`FileDiff.fromJson` 直读字段）——报告已证伪。
真缺陷是：面板把**可能不完整**的统计当确定值渲染，且**失败态下渲染的是
`ReviewScopes.diffsFor` 的 session-snapshot fallback 数字**，却顶着本 scope 的 chip。

本仓库在消息列表早已立了"截断必须标记"的契约（`V2Api.kt:578-580`、
`V2Transport.kt:566-568`「so a short transcript is never presented as a complete one」），
diff 列表没守住。

### 改动：`ReviewModels.kt` + `ReviewChangesTab.kt`

```kotlin
// ReviewModels.kt — 纯函数
fun changesSummaryPartial(scope: ReviewChangeScope, loadFailed: Boolean): Boolean =
    loadFailed && scope != ReviewChangeScope.LAST_TURN
```

`ReviewChangesTab.kt` 的 summary item 改为 `if (!summaryPartial) { …+N -N… }`。

### **未新增任何用户可见字符串（三语均未新增）** —— 理由

按派单允许的两条路（"新增用户可见文案三语同步" vs "只做数据层标记并写明理由"），本轮选后者：

1. **机制未闭环**：报告 §未闭环-1 明确写出，`+153 -76` 究竟来自服务端规模上限、
   竞态放弃还是 `context` 为 null 的更窄语义，**本轮未取证到**。在不知道成因时
   写一句"统计可能不完整"给用户，是把一个未验证的假设播报给用户。
2. **文案收益为负**：`mode=working` 在巨型工作区本来就是"尽力而为"的读，
   挂一个"可能不完整"到每一次 diff 头上，会让用户对所有 diff 统计失去信任，
   而这恰恰不是绝大多数（小工作区）场景的事实。
3. **失败态已有诚实文案**：`oc_review_changes_failed_hint` + `LoadFailedBanner`
   已经说明了"本 scope 的读没回来、下面这些是 fallback"。缺的只是**数字不该冒充
   本 scope 的总量**——这半边由 `changesSummaryPartial` 补齐，无需新串。

⇒ 三语（zh/en/ja）**零新增**，`StringsLocaleParityTest` 因此不受影响。

---

## N5 派生 — 过滤器搜不到未展开目录里的文件（已修）

### 根因

`ReviewFilesTab.kt:233-239` 的 browse 列表用 `expanded = { expandedDirs.containsKey(it) }`
展平，未展开目录里的文件**根本没进 `rows`**，任何查询都够不着。搜 `gradle` 找不到
`build.gradle.kts`，除非手工点开那个目录，且界面不作任何解释。

### 改动：`ReviewModels.kt` + `ReviewFilesTab.kt`

```kotlin
// ReviewModels.kt — 纯函数
fun searchRows(children, root, isExpanded, hasLoadedChildren, query): List<Row> =
    if (!isActive(query)) ReviewFileTree.flatten(children, root, isExpanded)
    else ReviewFileTree.flatten(children, root, hasLoadedChildren)
```

调用点：过滤激活时对 **所有已加载**目录展平再 `filterRows`；否则仍用原来的
`allRows`。

### 为什么不算降强度

- **匹配语义一字未改**（`matches`/`normalize`/`compact`/`isActive` 原样），`ReviewWave5Test`
  的既有断言全部继续成立。
- **树展开态语义未破**：`hasLoadedChildren` 只**读** `children`，从不写 `expandedDirs`；
  不做自动 `load()`，因此**零新增网络请求**、无自动展开副作用。
- **清空即恢复是构造性保证**而非 key 选择：query 非激活时 `searchRows` 直接走
  `isExpanded` 分支返回与原来逐行相同的列表。
- 仍然诚实：从未 fetch 过的目录**仍然搜不到**——懒加载树的固有限制，pin 成显式契约
  （`matches only the supplied row - an unloaded child stays unreachable by design`）。

---

## N4 派生 — 搜索浮层遮挡不足（低危，已修）

### 根因

`HomeSessionsList.kt:502-519` 的搜索面板是**浮层**（`align(TopStart)` + `offset(-6,-6)`），
主列表**从不因 query 清空**（`sections` 只由 `rows` 派生，`:273`）。面板高度随内容收缩，
`heightIn(max = PANEL_MAX_HEIGHT)` 只作用在**内层结果 `LazyColumn`**（`:641`），
空态时该 `LazyColumn` 根本不渲染 ⇒ 面板只剩 header + 一行文案，下方主列表原样露出，
与"No sessions found"视觉撞车（`v97b_052_homesearch.png`）。

### 改动：`HomeSessionsList.kt`

```diff
             .background(c.bgBase)
+            .heightIn(min = if (results.isEmpty()) PANEL_MIN_COVER_HEIGHT else 0.dp)
 ) {
```

新增 `private val PANEL_MIN_COVER_HEIGHT = 160.dp`（与 `PANEL_MAX_HEIGHT` 并列）。

### 为什么不算降强度

- 只改**呈现**，`HomeSessions.filter` / `results` / `remember(fullSearchRows, query)`
  一律未动——报告 §N4 明确警告"不要按『过滤器只算一次』去改 remember key"，本轮**没有**改。
- min height **只在空态**生效；有结果时高度与改动前逐像素相同。
- 未采用报告建议的"完全对齐上游 `onSetSearchRoot` 测量根"（需 `onGloballyPositioned`，
  风险高于收益，报告本身也建议下一轮）。

---

## 测试证据

命令（抢锁协议已遵守：等锁 → `Set-Content .agent-gradle-lock "fix-review"` → 跑完立刻释放）：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --console=plain :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.ReviewVcsProbePlanTest" `
  --tests "com.xiufeigo.opencodeshell.ReviewSearchV97FixTest" `
  --tests "com.xiufeigo.opencodeshell.ReviewModelsTest" `
  --tests "com.xiufeigo.opencodeshell.ReviewWave5Test" `
  --tests "com.xiufeigo.opencodeshell.OpenCodeClientReviewTest" `
  --tests "com.xiufeigo.opencodeshell.HomeProjectFilterTest" `
  --tests "com.xiufeigo.opencodeshell.HomeProjectsTest" `
  --tests "com.xiufeigo.opencodeshell.ChatStoreReviewEventTest" `
  --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest"
```

原文（`research/v97_fix/gradle_run.log` 末尾）：

```
BUILD SUCCESSFUL in 3s
52 actionable tasks: 2 executed, 50 up-to-date
```

逐类用例数（`app/build/test-results/testDebugUnitTest/TEST-*.xml`）：

| 测试类 | 用例数 | 失败 |
| --- | --- | --- |
| `ReviewVcsProbePlanTest`（**新增**） | 8 | 0 |
| `ReviewSearchV97FixTest`（**新增**） | 12 | 0 |
| `ReviewModelsTest` | 12 | 0 |
| `ReviewWave5Test` | 30 | 0 |
| `OpenCodeClientReviewTest` | 4 | 0 |
| `HomeProjectFilterTest` | 25 | 0 |
| `HomeProjectsTest` | 10 | 0 |
| `ChatStoreReviewEventTest` | 5 | 0 |
| `StringsLocaleParityTest` | 3 | 0 |
| **合计** | **109** | **0** |

新增 20 个 pin（8 + 12），其中 4 个是**修复前必然失败**的回归 pin：

- `a transient failure does not burn the fresh-graph candidates`
- `a transient failure is never recorded as probed`
- `only a deterministic refusal retires the fresh-graph candidate`
- `a no-status failure is transient - a timeout has no HTTP verdict`
- `a search reaches a file inside a collapsed but loaded directory`
- `a failed scope read never presents fallback totals as this scope's total`
- `clearing the filter returns the full flattened list - the N5 pin`
- `the home search filter is a pure function of the current query`（钉死 N4「过滤器只算一次」的误判）

### 过程中的两次真实红灯（已修，均为**测试自身的错误**而非产品缺陷）

1. 首轮 `109 tests completed, 4 failed`：
   - `totals` 期望值抄了报告里的 `207 to 9108`，但 `FileDiff("f", null, 274, 44, …)` 的
     `274` 是 additions，`207 × 274 = 56718`。**报告给的这条 pin 本身算错了**，
     已改为 `207 * 274 to 207 * 44`。
   - `a transient failure does not burn the fresh-graph candidates` 最初写成
     `candidates(probed=false) == candidates(probed=true)`。**这条期望本身是错的**：
     `probed=true` 时收窄候选是**设计**，真正的修法是「瞬时失败不把 `probed` 置真」。
     已改为 `probed = shouldMarkProbed(TRANSIENT)`（即 false），断言列表不变——这才是修复的真实契约。
   - Home 搜索 pin 误用 `it.title` 去比对 id（`row("a","pathlib")` 的 title 是 `pathlib` 不是 `a`）。
2. 次轮 `109 tests completed, 1 failed`：`ReviewFileTree.flatten` 是**深度优先前序**，
   我按层序写了期望值。已改为实际 DFS 序（产品代码行为正确，是期望值错）。


---

## 真机复验步骤

1. **N6-a 窄窗超时后 Refresh 能恢复**
   窄窗 → Session → Changes → Git changes，在本仓库这种巨型工作区上等到
   `The diff request timed out or failed` 出现 → 点右上 `Refresh`。
   期望：evict 成功后重发**完整候选集**（canonical + `<dir>/.`），而非只试 canonical。

2. **N5 Files 过滤能搜到未展开目录**
   Review → Files，**不要**展开任何目录，输入 `build.gradle`。
   期望：命中根目录及已加载子目录里的 `build.gradle.kts`（含之前搜不到的未展开目录）。
   再清空过滤框 → 期望：树恢复成你离开时的展开态（不自动展开、不丢状态）。

3. **N4 Home 搜索浮层无露出行**
   Home → 搜索框输入一个必然无结果的词（如 `E2ECHK9999`）。
   期望：`No sessions found for …` 下方**不再**露出主列表会话行。

4. **N6-b 失败态不显示总量**
   制造一次 scope 读失败（窄窗超时），让页面落到 fallback 文件列表。
   期望：文件列表**可见**，但顶部**不出现** `+N -N` 汇总行（`LoadFailedBanner` 仍在）。

---

## 未闭环

1. **N6-① 截断机制仍未定位**（本轮未取证）。客户端无截断已证伪，但服务端 `mode=working`
   为何只回部分结果，仍需对同一目录直打
   `GET /api/vcs/diff?mode=working&location[directory]=<repo>`，
   与 `git diff --shortstat HEAD` 三方对齐后，才能决定是否值得引入显式 truncated 标记与新文案。
2. **`REVIEW_VCS_TIMEOUT_MS = 20_000L` 是否过短**——需要产品取舍，未动。
3. **N4/N5 的真机复现未独立重跑**——本轮结论建立在已有截图像素判读 + 纯函数 pin 上。
4. **N4 浮层的横向覆盖**未完全对齐上游 `width: calc(100% + 12px)`，
   需要显式测量根，按报告建议留给下一轮。
