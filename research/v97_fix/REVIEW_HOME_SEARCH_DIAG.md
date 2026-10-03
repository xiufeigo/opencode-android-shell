# Review 面板与 Home 会话搜索 —— 缺陷取证/诊断报告

- 轮次：v97 诊断（**只读**，未改任何源码/测试；未跑 gradle；未碰 adb）
- 缺陷来源：`research/v97_verify/5562_sweepB/REPORT_v97b.md` §N4 / §N5 / §N6
- 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
- 方法：读实现 → 读真机截图取证 → 对照 `_upstream/packages/app/src/**`。每条结论均给 `文件:行号`。

---

## 结论摘要

| 编号 | 结论 | 一句话理由 |
|---|---|---|
| **N4** | **非缺陷（原始描述为误判）**；派生 1 个真缺陷（次级·低危） | 空态与"残留行"**不在同一个 composable**——代码里二者互斥（`HomeSessionsList.kt:633-648` 的 `if/else`）；截图里那行是**搜索浮层下方没被遮住的主列表行**（浮层高度随内容收缩，`HomeSessionsList.kt:502-519`）。真缺陷是"浮层遮挡不足 → 视觉自相矛盾"，不是"过滤只算一次"。 |
| **N5** | **非缺陷（原始描述为误判）**；附带 1 个真缺陷（口径·低危） | `v97b_036_filterclear.png` 里输入框显示的是**占位符**、且可见 `.idea` 行——`.idea` 不可能匹配 `gradle`，证明 filter 早已清空、列表**已恢复**；只有 2 行是**软键盘把可视区压到 2 行**（同报告 N8）。"Refresh 只弹 toast"是**设计**（evict 位置缓存，与文件树/过滤器无关，`ReviewPanel.kt:290-302`）。真缺陷是过滤器**只匹配已展开层的当前条目名**，不递归搜未展开目录。 |
| **N6** | **一半真缺陷 / 一半证据不足** | ①"统计被截断"**证据不足**：客户端全链路**无任何上限**（`V2Api.kt:141-146` 全量读 body、`V2Api.kt:1322` 无 limit、`ReviewModels.kt:152` 累加全量、`OpenCodeClient.kt:917-918` 直读服务端字段），`+153 -76` 是服务端 `mode=working` 在本仓库这个 **57407 insertions 的巨型工作区**上的部分结果；真缺陷是**把可能不完整的统计当完整值渲染、无 truncated 标记**。②"超时不可恢复"**真缺陷**：移动端 `Refresh` 只做 evict，**没有任何 re-request 路径**。 |

---

## N4 — Home 会话搜索"空态 + 残留行"

### 根因（真实缺陷是遮挡，不是过滤）

**先证伪"空态判定集合 ≠ 渲染集合"这一假设。** 空态与结果列表在同一 composable 内**互斥**：

`app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt:633-648`
```kotlin
if (results.isEmpty()) {
    Text(
        text = stringResource(R.string.oc_home_search_no_results, query.trim()),
        ...
    )
} else {
    LazyColumn(modifier = Modifier.heightIn(max = PANEL_MAX_HEIGHT)) {
        items(items = results, key = { "q:" + it.id }) { row -> ... }
```
`if/else` 结构决定了「No sessions found」与任何结果行**不可能同时被渲染**。所以报告里"空态与残留行同时渲染"在**这个 composable 内部**不成立。

过滤本身也是**每次输入都重算**的，不是"只算一次"：

`HomeSessionsList.kt:284`
```kotlin
val results = remember(fullSearchRows, query) { HomeSessions.filter(fullSearchRows, query) }
```
`query` 在 key 里。报告称 N4「与 N2 同一根因（过滤器只算一次）」——**该归因不成立**，N2 与 N4 是两回事。

**真正的因果链是 z-order / 遮挡**：

1. `HomeSessionsList.kt:502-519` — 搜索面板是一个**浮层**，浮在主列表之上：
```kotlin
if (query.isNotEmpty()) {
    HomeSessionSearchPanel(
        query = query,
        results = results,
        ...
        modifier = Modifier
            .align(Alignment.TopStart)      // ← 浮在主列表之上，不是替换它
            .offset(x = (-6).dp, y = (-6).dp)
            .fillMaxWidth(),
    )
}
```
2. 面板高度**随内容收缩**：只有 header（`HomeSessionsList.kt:627-632`）+ 空态文案（`:633-639`）时，面板很矮；`heightIn(max = PANEL_MAX_HEIGHT)` 只作用在**内层结果 `LazyColumn`**（`:641`），空态时该 `LazyColumn` 根本不渲染。
3. 主列表**从未被搜索清空**——`sections` 由 `rows` 派生，与 `query` 无关：
`HomeSessionsList.kt:273`
```kotlin
val sections = remember(rows, now) { HomeSessions.sections(rows, now) }
```
4. ⇒ 面板变矮后，**它下面的主列表原样露出**，与空态文案视觉上撞在一起。

**截图坐实**（`v97b_052_homesearch.png`）：面板内是 `Sessions` 组标题 + `No sessions found for E2ECHK5562`；紧下方那行 `| Queued  opencode-android-shell  just now`，以及再下面被键盘切掉的 `E2ECHK xiufeigo`，**都是主列表的会话行**（图标+标题+项目名+相对时间，形态与面板结果行不同），不是搜索结果。

### 上游对照

`_upstream/packages/app/src/home/sessions/view.tsx:229-243`
```tsx
function HomeSessionSearch(props: HomeSessionsViewProps) {
  return (
    <div class="w-full">
      <div ref={props.onSetSearchRoot} data-component="home-session-search" class="relative z-30 w-full">
        <Show when={props.searchOpen}>
          <div
            data-component="home-session-search-panel"
            class={`
              absolute flex flex-col overflow-hidden rounded-[12px]
              bg-v2-background-bg-base shadow-[var(--v2-elevation-floating)]
            `}
            style={{ top: "-6px", "inset-inline-start": "-6px", width: "calc(100% + 12px)",
```
上游同样是 `absolute` 浮层 + 实底 `bg-v2-background-bg-base`，**形态一致**；但上游有两处本仓库缺失的约束：

- **`overflow-hidden` + 明确的 `width: calc(100% + 12px)`**（`view.tsx:237-243`）：浮层宽度明确大于内容列，把左右两侧也盖住。本仓库只有 `fillMaxWidth()` + `offset(-6,-6)`（`HomeSessionsList.kt:515-517`），宽度方向没有同等保证。
- **`onSetSearchRoot` 测量宿主根节点**（`view.tsx:232`）：上游用一个显式的测量回调把浮层锚定在搜索框所在的**独立 stacking/测量根**上，而不是无约束地叠在列表之上。

**正确语义**：搜索面板是一层**自足的浮层**——它要么完整遮住它下面那片区域（不透明、有界），要么下方列表必须被明确隐藏/压暗。空态文案与"下方仍有会话行"同屏出现，正确实现下不应发生。本仓库两者都没有做。

### 最小修复方案

**推荐（低风险、纯视图层）**：空态时把浮层补到能遮住的高度，让下方主列表不可见。最贴近上游 `overflow-hidden` + 实底的语义：

```diff
--- a/app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt
+++ b/app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt
@@ HomeSessionSearchPanel 的 Column 外层
-        .background(c.bgBase)
+        .background(c.bgBase)
+        // 空态时面板只包住 header + 一行文案，下方主列表会原样露出，
+        // 读起来像「一边说没有结果、一边又列着会话」（v97b_052）。
+        // 上游 `home-session-search-panel` 用 `overflow-hidden` + 实底把
+        // 这片区域完全盖住；这里补一个最小可视高度兜底。
+        .heightIn(min = if (results.isEmpty()) EMPTY_PANEL_MIN_HEIGHT else 0.dp)
     ) {
```
并新增常量 `private val EMPTY_PANEL_MIN_HEIGHT = 120.dp`（与 `:641` 的 `PANEL_MAX_HEIGHT = 320.dp` 并列）。

**若要完全对齐上游**（改动更大，建议下一轮）：给浮层加 `.widthIn(min = ...)`/`fillMaxWidth()` 外的显式测量根，仿照 `onSetSearchRoot`。**本轮不建议**——需要引入 `onGloballyPositioned`，风险高于收益。

> ⚠️ 本方案**只改 UI 呈现**，不碰 `HomeSessions.filter` / `searchRows` 的任何语义。**不要**按报告 N4 的"过滤器只算一次"去改 `remember` key——那会把一个误判固化成真回归。

### 应补的 JVM pin（纯 JVM，可落地）

本仓库 test 依赖仅 `junit` / `org.json` / `mockwebserver`，**无 compose/robolectric** → **无法 pin 浮层高度**。可 pin 的是这条结论的**前提**："空态与结果行互斥、过滤每次重算"，把它钉死以防将来有人按报告去改 `remember` key：

`app/src/test/java/com/xiufeigo/opencodeshell/HomeSessionsSearchParityTest.kt`（新增）
```kotlin
@Test
fun `empty query returns every row unchanged so a cleared search restores the full list`() {
    val rows = listOf(row("a", "Queued"), row("b", "E2ECHK5562"))
    assertEquals(rows, HomeSessions.filter(rows, ""))          // 清空即全量
    assertEquals(rows, HomeSessions.filter(rows, "   "))        // 纯空白同上
}

@Test
fun `filter is a pure function of the current query - no frozen result set`() {
    val rows = listOf(row("a", "pathlib"), row("b", "Queued"))
    val first = HomeSessions.filter(rows, "Queued")
    assertEquals(listOf("b"), first.map { it.title })
    // 同一 rows 换词必须重算：直接证伪 N2/N4 的「过滤器只算一次」
    assertEquals(listOf("a"), HomeSessions.filter(rows, "pathlib").map { it.title })
    assertEquals(emptyList<String>(), HomeSessions.filter(rows, "E2ECHK5562").map { it.title })
}
```
> pin 点说明：`HomeSessions.filter`（`HomeSessions.kt:316`）是纯函数，`Row` 可手工构造。**不要**写 compose UI 测试。

---

## N5 — Review → Files 清空过滤器"不恢复"

### 根因：原始描述为误判（取证推翻）

**过滤链本身正确**，`ReviewFilesTab.kt:240` 把 `filter` 放在 `remember` 的 key 里：
```kotlin
val rows = remember(allRows, filter) { ReviewFileFilter.filterRows(allRows, filter) }
```
而 `ReviewFileFilter.filterRows` 在查询非激活时**原样返回全量行**（`ReviewModels.kt:317-318`）：
```kotlin
fun filterRows(rows: List<ReviewFileTree.Row>, query: String): List<ReviewFileTree.Row> {
    if (!isActive(query)) return rows
```
且 `isActive` 以 `isNotBlank()` 判空（`ReviewModels.kt:290`）。⇒ **清空过滤器必然恢复全量**，代码上不存在"不恢复"。

**截图直接证伪**（`v97b_036_filterclear.png`，我已读图）：
- `Filter files` 输入框里是**灰色占位符**（`ReviewFilesTab.kt:334-342` 的 `if (value.isEmpty()) Text(oc_review_filter_files)`），**不是**残留的查询词；
- 可见行是 `.gradle` 和 **`.idea`**。`.idea` **不可能**匹配 `gradle`（`ReviewModels.kt:326` 的 `matches(query, [name, path, absolute])`）。⇒ 查询确已清空、列表确已恢复，只是**只剩 2 行可见**。

**为什么只有 2 行可见 —— 是 N8，不是 N5**：同一张截图里，Review 侧栏的 Files 列表可视带只到 y≈340，其下就是 GBoard 键盘。28dp 的行高只够放 2 行。这与报告 N8 自己的描述完全一致：
> N8 —「Review 侧栏同病（`v97b_035`/`v97b_036` 里 Files 列表只剩 2 行可视）」

**"切 tab 再切回恢复 15 行"同样吻合**：切到 `Context` 再切回，焦点离开 `BasicTextField` → 键盘收起 → 15 行全部露出。**不是状态被重置，是可视区被解放**。

**"Refresh 只弹 toast"是设计，不是缺陷**。面板头部的 `Refresh` 走 `onRefreshVcs`：
`ReviewPanel.kt:290-302`
```kotlin
// 「刷新」 (evict this directory's cached location + re-probe):
if (onRefreshVcs != null) {
    ReviewRefreshAction(
        pending = refreshing,
        onClick = { if (!refreshing) { refreshing = true; onRefreshVcs() } },
    )
}
```
`onRefreshVcs` → `ChatViewModel.reviewReprobeLocation`（`ChatViewModel.kt:5458`），作用是**驱逐该 directory 的缓存 location 并重探**。它的设计文档明确写了它**不碰**文件树/过滤器（`ReviewPanel.kt:129-135`；`ReviewChangesTab.kt:131-134` 同样声明"the refresh evicts the directory's cached location"）。所以"Refresh 救不回列表"是**预期行为**——它压根不是"重新加载文件列表"的按钮。

### 派生真缺陷（口径缺陷 · 低危，确有其事）

`Filter files` **只匹配"已展开层"的当前条目名，不递归搜未展开目录**。`ReviewFileFilter.filterRows` 的入参 `rows` 来自 `ReviewFileTree.flatten`：

`ReviewFilesTab.kt:233-239`
```kotlin
val allRows = remember(children.toMap(), expandedDirs.toMap(), root) {
    ReviewFileTree.flatten(
        children = { key -> children[key].orEmpty() },
        root = root,
        expanded = { expandedDirs.containsKey(it) },   // ← 只下钻已展开目录
    )
}
```
`flatten` 以 `expanded` 门控下钻（`ReviewModels.kt:200-210`），因此**未展开目录里的文件根本没进 `rows`**，过滤器无从匹配。⇒ 输入 `gradle` 搜不到 `gradle.properties`、`build.gradle.kts`。这条是报告 N5「附带」项，**成立**。

**上游对照**：本文件 `ReviewFilesTab.kt:308-313` 自述该取舍——
```
 * Filters flattened tree rows, keeping the ancestors of a match so the tree
 * shape survives (upstream switches to a flat result list while searching —
 * the lazily loaded Android tree cannot list unloaded files, so the loaded
 * rows are filtered instead; see `research/wave5_review.md` §C2).
```
即上游在搜索时切成**扁平全量结果**（可搜到深层文件），本仓库因树是**懒加载**只能过滤已加载行。⇒ **这是已知取舍，不是回归**，但**用户可感知**（用户搜 `gradle` 却搜不到 `build.gradle.kts` 不会有任何解释）。

### 最小修复方案

**核心：N5 本体不改。** 过滤器与 Refresh 均按设计工作。

若要修派生的口径缺陷，推荐**分级方案**（不要动 `filterRows` 的纯函数语义）：

1. **最小（文案诚实化，零风险）**：过滤激活且结果集为空时，现有空态 `oc_review_filter_empty`（`ReviewFilesTab.kt:248-252`）已存在。**追加**一行副文案说明"只搜索已加载/已展开的目录"，并对**未展开的目录**在匹配时给一个"展开以搜索更多"的操作。这需要新增一条 string 资源 + 一处 `CenteredHint` 扩展。

2. **彻底（对齐上游，工作量中等）**：过滤激活时对 `rows` 里的**目录节点**做**递归自动展开 + 懒加载**，再对展开后的全量行过滤。落点是 `ReviewFilesTab.kt:233-240`：在 `filter` 非空时把 `ReviewFileFilter.filterRows` 的结果里的目录祖先集合并入 `expandedDirs` 并对未加载者调 `load(dir)`。⚠️ 这会引入**自动网络请求**，需评估对 `v97b_037` 那种大工作区的性能影响，**本轮不建议直接上**。

3. **显式不做**：不要改 `ReviewFilesTab.kt:240` 的 `remember(allRows, filter)` key。报告描述的现象已被截图证伪，按它改会引入真回归。

### 应补的 JVM pin（纯 JVM，可落地）

`ReviewFileFilter.filterRows` / `isActive` 是纯函数，正好用来把"清空即恢复"钉死：

`app/src/test/java/com/xiufeigo/opencodeshell/ReviewFileFilterClearTest.kt`（新增）
```kotlin
@Test
fun `clearing the filter returns the full flattened list - the N5 regression pin`() {
    val all = listOf(row("gradle", level = 0), row(".idea", level = 0), row("build.gradle.kts", level = 0))
    val filtered = ReviewFileFilter.filterRows(all, "gradle")
    assertTrue(filtered.size < all.size)                  // 过滤确实生效
    assertEquals(all, ReviewFileFilter.filterRows(all, ""))       // ← 核心 pin
    assertEquals(all, ReviewFileFilter.filterRows(all, "    "))    // 纯空白等同清空
}

@Test
fun `matches only the supplied row - an unloaded child is unreachable by design`() {
    // 把「只匹配已加载层」这一已知取舍钉成显式契约，避免将来被当回归改掉
    assertTrue(ReviewFileFilter.matches("gradle", ".idea").not())
    assertTrue(ReviewFileFilter.matches("gradle", "build.gradle.kts"))
}
```
> pin 点说明：`ReviewFileFilter`（`ReviewModels.kt:277-335`）零 Android 依赖，`Row` 手工构造即可。**不要**写 compose 测试。

---

## N6 — Changes 的 diff 统计与超时不可恢复

拆成两个独立命题，结论不同。

### N6-① 统计"被截断" —— 证据不足 + 一处真缺陷（无截断标记）

**客户端全链路不存在任何截断**，逐段验过：

1. **HTTP 读取无上限** —— `V2Api.kt:141-146`
```kotlin
internal fun callBody(request: Request): String =
    http.newCall(request).execute().use { response ->
        val body = response.body?.string() ?: ""   // ← 全量读，无 cap
        if (!response.isSuccessful) throw apiError(response.code, body)
        body
    }
```
`RAW_BODY_CAP = 4096`（`V2Api.kt:2709`）**只**用于 `ApiException.rawBody` 的排障副本（`V2Api.kt:263`），不参与成功路径。

2. **`vcsDiff` 无 limit / 无分页** —— `V2Api.kt:1322-1328`，签名 `vcsDiff(mode, base, context, locationDirectory)`，客户端调用时（`ChatViewModel.kt:5350`）**只传 mode 与 locationDirectory**，`base`/`context` 均为 null。

3. **求和不截断** —— `ReviewModels.kt:152-153`
```kotlin
fun totals(diffs: List<OpenCodeClient.FileDiff>): Pair<Int, Int> =
    diffs.sumOf { it.additions } to diffs.sumOf { it.deletions }
```

4. **per-file 计数直读服务端字段** —— `OpenCodeClient.kt:914-920`
```kotlin
fun fromJson(obj: JSONObject): FileDiff = FileDiff(
    file = obj.optStr("file").takeIf { it.isNotEmpty() },
    patch = obj.optStr("patch").takeIf { it.isNotEmpty() },
    additions = obj.optInt("additions", 0),
    deletions = obj.optInt("deletions", 0),
```

⇒ **`+153 -76` 就是服务端返回的那些 `additions`/`deletions` 之和**。客户端没有截断它。

**那差异从哪来？** 看宽窗截图 `v97b_032_changes.png`：
- 作用域 chip **「Git changes」是激活态**（高亮），`Branch changes` 灰显；
- 基线行是 `Relative to main@refs/remotes/origi…`；
- 列表里的文件全是 `research/v97_verify/…` 下的 `DBG_F14A_stuck2.png`、`log_04_f14a.txt`、`ui_E2ECHK5554_n…` 等**本轮验证产物**，per-file 计数 `-1`、`+1`、`+52 -27`、`+1 -47`、`+1 -1`、`+56`… 相加恰好 ≈ `+153 -76`。

而 host 侧 `git diff --shortstat HEAD` 是 `207 files changed, 57407 insertions(+), 9201 deletions(-)`。

本仓库代码自己就写明了这个请求在巨型工作区上的行为（`ChatViewModel.kt:5229-5235`）：
```
 * `vcs/diff?mode=working` builds a whole-tree patch server-side, and the
 * route can simply hang on a large working tree (measured 2026-09-25 on
 * 2.0.11: no response in 25 s from a location with thousands of uncommitted
 * files, while a small one answers `{data:[]}` instantly).
```
⇒ **57407 insertions 的工作区正是 `mode=working` 的病理场景**。`+153 -76` 是服务端在该规模下的**部分/竞态结果**，不是客户端截断。**机制未闭环**（见末节），但"客户端截断"这一归因**已被证伪**。

**真缺陷（本条可修）**：面板把一个**可能不完整**的统计当作确定值渲染，**没有任何 `truncated` 标记**。而本仓库**在别处恰恰建立了"截断必须标记"的契约**——`V2Api.kt:578-580`：
```
 * [MessageDrain.truncated] is true **only** when the drain stopped because
 * of the [maxPages] cap with the server still offering a `cursor.next` —
 * not when the last page simply ended the transcript.
```
且 `V2Transport.kt:566-568` 明确这个标记会一路带到导出 payload，"so a short transcript is never presented as a complete one"。**消息列表守住了这条契约，diff 列表没有** —— 这是真实的、一致性可论证的缺陷。

### N6-② 窄窗超时"Refresh 重试无效" —— 真缺陷

**先排除"重试没重发"**：`onRetryDiff` 确实重发。移动端 `SessionScreen.kt:2673`
```kotlin
onRetryDiff = { requestVcsDiff(mobileReviewScope) },
```
桌面端 `ReviewPanel.kt:392`
```kotlin
onRetryDiff = { requestVcsDiff.value?.invoke(scope) },
```

**真正的断链**：窄窗走的是**移动端形态**（`v97b_095_narrow_changes3.png`：Session/Changes/Files/Terminal 底部 tab + 顶栏 `main` + `Git changes|Branch changes|Last turn changes` + 右上 `Refresh`）。这条路径**直接挂载 `ReviewChangesTab`**，不经过 `SessionReviewPanel`（`ReviewChangesTab.kt:120-124` 自述："the mobile form, which mounts [ReviewChangesTab] directly instead of [SessionReviewPanel]"）。

而**「`reprobeKey` 变化 → 重发当前 scope 的 diff」这条 effect 只存在于桌面面板里**：
`ReviewPanel.kt:210-213`
```kotlin
LaunchedEffect(scope, reprobeKey) {
    if (scope == ReviewChangeScope.LAST_TURN) return@LaunchedEffect
    requestVcsDiff.value?.invoke(scope)
}
```
`ReviewChangesTab` 的 composable 体里**只有** `LaunchedEffect(loadPending)`（`ReviewChangesTab.kt:221-228`），**没有**任何 `LaunchedEffect` 挂在 refresh/evict 上；它的参数表（`ReviewChangesTab.kt:163-184`）也**根本没有 `reprobeKey` 这个参数**。

⇒ **因果链（窄窗不可恢复）**：
1. 巨型工作区 → `mode=working` 服务端挂起 → 20s 墙钟超时（`ChatViewModel.kt:7477` `REVIEW_VCS_TIMEOUT_MS = 20_000L`）→ `diffs` 保持 null → `ReviewVcsDiffResult.Failed`（`ChatViewModel.kt:5366-5368`）。
2. 面板渲染 `LOAD_FAILED`，文案是 `The diff request timed out or failed. Tap Refresh to try again.`（`ReviewChangesTab.kt:274` 的 `oc_review_changes_failed_hint`）——**这句话把用户指向右上角的 `Refresh`**。
3. 用户点 `Refresh` → `onRefresh` → `reviewReprobeLocation`（evict 缓存 location）。
4. **移动端没有任何组件在 evict 之后重发 diff** ⇒ 状态原样停留 ⇒ 「重试仍失败」。

**第二处加重因素（桌面端也中招）**：一次失败后目录被永久标记为"已探测"，**候选 ref 列表被永久收窄**：
`ChatViewModel.kt:5363` — `if (!answered) markReviewVcsProbed()`
`ChatViewModel.kt:5407-5412`
```kotlin
private fun reviewVcsCandidates(): List<String?> {
    val dir = directory ?: return listOf(null)
    reviewVcsRefs[dir]?.let { return listOf(it) }
    if (dir in reviewVcsProbed) return listOf(dir)   // ← 超时后只剩 canonical ref
    return ReviewVcsReprobe.refs(dir)               // ← fresh-graph 变体被永久丢弃
}
```
即：**第一次失败会把"多试几个 location ref"的恢复能力永久烧掉**，后续每次重试都比第一次更弱。这与 `reviewVcsProbed` 自己的注释「so a refresh cannot re-issue the probe」（`ChatViewModel.kt:5222-5223`）是刻意的设计，但它在**瞬时故障**（服务端正忙）场景下把**瞬时失败固化成永久降级**。

### 上游对照

上游 `session/review/model.ts:23` 的 `vcs.diff({mode})` 与本仓库 `ReviewChangesTab` 的 scope 语义一致（Git/Branch/Last-turn），且上游**没有** evict-only 的 Refresh —— 它只有一个动作：**重发同一个 diff 请求**。上游也不存在"刷新=驱逐缓存"这个第二语义。所以：

- **正确语义 A**：对「取不到 diff」这个失败，用户可达的**唯一**动作必须是**重发**；文案指向的动作 == 实际重发的动作。
- **正确语义 B**：重试不应比首次尝试更弱。
- **正确语义 C**：统计值若可能不完整，必须标记（对齐本仓库自己 `MessageDrain.truncated` 的契约）。

### 最小修复方案

**修 1（核心）：给移动端补上 evict → 重发的通路。** 二选一，推荐 (a)：

**(a) 在 `ReviewChangesTab` 内加一条挂到 `onRefresh` 的重发 effect**（改动最小、不动签名）——需要 host 把 `onRefresh` 包一层：
```diff
--- a/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt
+++ b/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt
@@ 移动端 Changes 页
-    onRefresh = reprobeReviewVcs,
+    // 移动端直接挂 ReviewChangesTab，拿不到 SessionReviewPanel 里那条
+    // `LaunchedEffect(reprobeKey)` 的重发（ReviewPanel.kt:210-213），
+    // 而失败文案恰恰让用户点这个 Refresh —— 两边必须对上。
+    onRefresh = {
+        reprobeReviewVcs()
+        requestVcsDiff(mobileReviewScope)
+    },
```

**(b) 给 `ReviewChangesTab` 加 `reprobeKey: Int = 0` 参数**，并仿照 `ReviewPanel.kt:210` 加 `LaunchedEffect(scope, reprobeKey) { onRetryDiff?.invoke() }`。更对齐桌面端，但改签名。**若走 (b)，同时把 `Refresh` 的语义收敛为纯 evict、文案改为「Tap retry」**，避免两个动作继续混。

**修 2：让瞬时失败不烧掉恢复能力。**
```diff
--- a/app/src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt
+++ b/app/src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt
@@ reviewVcsDiff
-            if (!answered) markReviewVcsProbed()
+            // 超时是**瞬时**失败：把它记成「已探测」会让
+            // reviewVcsCandidates() 永久只试 canonical ref（:5407-5412），
+            // 之后每次重试都比首次更弱。只在确定性的"拒绝"上标记，
+            // 超时/IO 失败不消耗 fresh-graph 候选。
+            if (!answered) markReviewVcsProbedTransient()
```
（`markReviewVcsProbedTransient` 的语义 = 带时间窗的标记，或改为 `reviewVcsProbed` 只在 HTTP 4xx 语义拒绝时写入。）

**修 3：统计值加不完整性标记。** 需要先拿到服务端是否截断的信号；在拿到之前，**最低限度**的诚实化是：当 `loadFailed` 为真时**不显示** `+N -N` 汇总（`ReviewChangesTab.kt:350-367`），或在汇总行后追加 `oc_review_diff_partial` 提示。⚠️ 依赖 N6-① 的机制闭环，**建议在机制确认后一并做**。

### 应补的 JVM pin（纯 JVM，可落地）

`reviewVcsDiff` 需要 `ChatViewModel`（Android），**不可直接 pin**。因此**必须先把纯逻辑抽出来**再 pin：

**(1) 候选 ref 的降级规则 —— 抽纯函数后 pin（推荐，优先级最高）**

`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewVcsProbePlan.kt`（新增）
```kotlin
object ReviewVcsProbePlan {
    /** ref 的失败性质：瞬时（超时/IO）不消耗候选，确定性拒绝才消耗。 */
    enum class Failure { TRANSIENT, PERMANENT }

    fun candidates(dir: String, remembered: String?, probed: Boolean): List<String> = when {
        remembered != null -> listOf(remembered)
        probed -> listOf(dir)
        else -> ReviewVcsReprobe.refs(dir)
    }

    fun shouldMarkProbed(failure: Failure): Boolean = failure == Failure.PERMANENT
}
```
然后 `ChatViewModel.reviewVcsCandidates()` 改为委派它，pin：
```kotlin
@Test
fun `a transient failure does not burn the fresh-graph candidates`() {
    assertEquals(
        ReviewVcsProbePlan.candidates("/repo", null, probed = false),
        ReviewVcsProbePlan.candidates("/repo", null, probed = true)   // ← 修 2 的核心 pin
    )
    assertTrue(ReviewVcsProbePlan.shouldMarkProbed(ReviewVcsProbePlan.Failure.TRANSIENT).not())
}
```

**(2) 「文案指向的动作 == 实际重发的动作」—— pin 纯文案/状态决策**

`ReviewScopes.changesBody`（`ReviewChangesTab.kt:233-240` 调用）已是纯函数，可直接 pin 失败态必须带可重发语义：
```kotlin
@Test
fun `LOAD_FAILED is distinct from EMPTY so a failure is never reported as no changes`() {
    val failed = ReviewScopes.changesBody(
        scope = ReviewChangeScope.GIT, noGit = false, loadFailed = true,
        loadPending = false, hasDiffs = false, loadStalled = false,
    )
    assertEquals(ReviewScopes.ChangesBody.LOAD_FAILED, failed)
    assertNotEquals(ReviewScopes.ChangesBody.EMPTY, failed)
}
```

**(3) 统计不截断 —— pin `ReviewDiff.totals` 累加全量**
```kotlin
@Test
fun `totals sum every file the server returned - no client-side cap`() {
    val diffs = (1..207).map { OpenCodeClient.FileDiff("f$it", null, 274, 44, "modified") }
    assertEquals(207 to 9108, ReviewDiff.totals(diffs))
}
```
> ⚠️ 该 pin 锁的是"客户端不做截断"这一**已验证事实**，防止将来有人加 `take(N)`。它**不能**证明服务端不截断。

---

## 未闭环 / 未验到

1. **N6-① 的截断机制未闭环（本轮核心遗留）**。已**证伪**"客户端截断"（`V2Api.kt:141-146` / `:1322` / `ReviewModels.kt:152` / `OpenCodeClient.kt:917-918` 四段全通），但**未能定位** `+153 -76` 究竟来自：服务端对 `mode=working` 的规模上限？竞态下服务端自己放弃？还是 `context` 为 null 时服务端用了更窄的语义？
   **建议下一轮取证手段（只读、不装包）**：在已有 host 上对同一目录直接打
   `GET /api/vcs/diff?mode=working&location[directory]=<repo>`，把返回的 `data[]` 长度与 `sum(additions)` 与 host 侧 `git diff --shortstat HEAD` 三方对齐；同时打一次带 `context=` 与不带 `context=` 的对照。**这需要 host 服务可达，本轮未执行。**

2. **N6-② 的 `REVIEW_VCS_TIMEOUT_MS = 20_000L` 是否过短，未定量**。代码注释自述该路由在大型工作区"hangs for tens of seconds"（`ChatViewModel.kt:5323-5328`），但 20s 是否应该上调、以及上调后 UX 是否可接受，**需要产品取舍**，不是取证能定的。

3. **N5/N4 的"真机复现"未被独立重跑**。本轮结论建立在**已有截图的像素级判读**上（`v97b_036` 的占位符 + `.idea` 行、`v97b_052` 的面板/主列表分层、`v97b_032` 的 scope chip 激活态）。这些判读是**确定性的**（`.idea` 不可能匹配 `gradle`），但**未在真机上重跑一遍最小复现**。建议修复轮之前，由测试同学用"收键盘后再读列表"这一条单独确认 N5。

4. ~~`SessionScreen.kt:2673` 的 `requestVcsDiff` 是否有去重/在途保护~~ —— **本轮已查，无去重**，`SessionScreen.kt:455-460`：
   ```kotlin
   val requestVcsDiff: (ReviewChangeScope) -> Unit = { reviewScope ->
       vcsDiffResult = null
       chatViewModel.reviewVcsDiff(reviewScope.name.lowercase()) { result ->
           vcsDiffResult = result
       }
   }
   ```
   每次调用都**无条件**清空旧裁决并重发，**不存在在途重入保护**。⇒ **修 1 的方案 (a) 可以直接生效**，无需额外处理并发。附带收益：`vcsDiffResult = null` 同时把 `vcsDiffPending` 置回 true（`SessionScreen.kt:450`），失败态会正确退回「等待中」再进失败态，不会卡在旧裁决上。

5. **未跑任何测试、未构建、未装机**（按派单纪律）。上列 pin 均为**待落地**状态，`assertEquals` 里的 `Row`/`FileDiff` 构造签名需在实现时对齐实际字段。
