# REVIEW_FILTER_CLEAR_FIX — v97 U11（Review 过滤器 Clear/Refresh 不恢复全量清单）

> **状态：DONE（代码 + JVM pin 闭环；真机复验未做，步骤见 §6）**
> 缺陷来源：`research/v97_verify/5562_v98/REPORT_SWEEPD2.md` §3.11 / §4.3（U11，跨轮复现 FAIL）
> 修复日期：2026-10-03 · 会话 `mvs_297d7c48ee074ec482e3704a8d179af4`

---

## 1. 结论先行：报告给的候选根因**不成立**

`REPORT_SWEEPD2.md` §3.11.2 的候选是「`allRows` 被 `remember` **在过滤之后**缓存了」。

**这个判断是错的，而且方向恰好相反：`allRows` 是在过滤之前算的，并且算得完全正确。**

`ReviewFilesTab.kt:242-263`（修复前）：

```kotlin
val allRows = remember(children.toMap(), expandedDirs.toMap(), root) {   // 242
    ReviewFileTree.flatten(
        children = { key -> children[key].orEmpty() },
        root = root,
        expanded = { expandedDirs.containsKey(it) },
    )
}
val filtering = ReviewFileFilter.isActive(filter)                        // 249
val rows = if (filtering) {                                               // 250
    ReviewFileFilter.filterRows(ReviewFileFilter.searchRows(…), filter)
} else {
    allRows                                                               // 262
}
```

- `remember` 的三个 key 是 `children` / `expandedDirs` / `root`，**没有一个是 `filter`** ⇒ 缓存与过滤词无关；
- `allRows` 的 lambda 只读 `children` / `expandedDirs` / `root`，**只写 `filter` 的分支永远污染不到它**；
- 所以 `filter` 归零后 `rows = allRows` 是 15 行整表，**数据层从未出错**。

**真机 dump 直接证明 `filter` 确实归零了**（`h_07_afterclear.xml`）：

| 节点 | 清空前（`h_02_filt.xml`） | 清空后（`h_07_afterclear.xml`） |
|---|---|---|
| `EditText` | `[1174,247][1316,280]` t=**`gradle`** FOCUS | `[1174,248][1350,280]` t=**``** FOCUS |
| `Button cd='Clear filter'` | `[1316,247][1364,280]` **在** | **整节点消失** |
| 列表首行 TextView | `.gradle` @297 | `.gradle` @297 |

`FileFilterField` 的 ✕ 只在 `if (value.isNotEmpty())` 里挂载（`ReviewFilesTab.kt:376`）——**✕ 消失 + 字段回占位符 ⇒ `filter == ""` 真的进了状态**。截�� `h_07_afterclear.png` 里也能直接看到 `Filter files` 占位符回来了。

---

## 2. 真根因：`LazyListState` 的 **first-item-moved 滚动保持** + IME 把视口压到 60px

「只剩 2 行」是**视口读数**，不是列表长度。三条互相独立的证据：

### 2.1 列表从来没短到 2 行 —— 折叠线下面还挂着第 3 行

`h_07_afterclear.xml` / `h_08_afterrefresh.xml`：

```
[1140,290][1366,350] View SCROLLABLE        ← 容器只有 60px 高（基线是 [1140,290][1366,720] = 430px）
[1140,280][1366,308] View CLICK   → .gradle
[1140,308][1366,336] View CLICK   → .idea
[1140,336][1366,374] View CLICK   → (第 3 行，被 IME 截断，只露出 14px)
[1140,346][1366,350] Button                  ← 该行自己的 Button 子节点，346>350 被切
```

- 容器 `scrollable="true"`，60px 视口 ≈ 2.1 行；
- **第 3 行仍然 mount 着**（`[1140,336][1366,374]`）。若列表真的只剩 2 行，这里不会有第 3 行节点。

⇒ 列表长度 > 2，**可见 2 行纯粹是 IME 把面板压扁**。IME 起来时 `Compose` 用 `adjustResize`，面板可视高度只剩 60px。

### 2.2 没回来的是**列表顶部**，不是行

清空后第一行是 `.gradle` —— 它在 15 行整表里是**下标 3**（`.dsh-acl-recovery` `.git` `.github` `.gradle` …）。前 3 行被滚到了视口上方。

成因是 `ReviewFilesTab.kt:288-290` 的 **key 化 items**：

```kotlin
items(
    items = rows,
    key = { row -> row.node.absolute.ifEmpty { "root:${rows.indexOf(row)}" } },
)
```

`LazyListState` 在 item 集合变化时会执行 first-item-moved 位置保持：**用旧首项的 key 去新列表里找，找到就滚到它的新下标**。过滤态首项 = `.gradle`（key `/repo/.gradle`）⇒ 清空后在整表里找到下标 3 ⇒ 视口钉在 3。

**这解释了为什么残留「逐字稳定」**：它是确定性的滚动偏移，不是概率性算错。报告 §4.3 说「不是随机算错，而是一个稳定的错误中间态」——对，但机制是滚动，不是数据。

### 2.3 为什么「切 tab 往返」是唯一恢复路径

`d_020_tabcycle.xml` → `d_021_filesback.xml`：点 tab 后 15 行全回来了。原因是**点 tab 让 `BasicTextField` 失焦 ⇒ IME 收起 ⇒ 面板恢复全高**（430px 装得下 15 行），同时 `LazyColumn` 随 tab 切换被 dispose/recreate ⇒ 滚动归 0。

「唯一恢复路径」实际是「**唯一能让视口变高**的路径」，不是数据恢复路径。

### 2.4 Refresh 为什么也救不了

`ReviewPanel.kt:292-301` 的 Refresh 走 `onRefreshVcs()` → 宿主 evict location → 递增 `reprobeKey`。它**只重读 review 侧载荷**（`/api/project`、`/api/vcs/base`、scope diff，见 `ReviewPanel.kt:199/210/224`）。

而 **Files 树根本没有 refresh 入口**：`ReviewFilesTab` 的 `load()` 第一行就是

```kotlin
if (children.containsKey(dir) || loadingDirs.containsKey(dir)) return   // ReviewFilesTab.kt:122
```

即已列出的目录**永远视为「已回答」**。所以 Refresh 之后 `children` 里还是那一份清单，滚动偏移也还在 ⇒ dump 逐字不变。**Refresh 不是「复用了旧 scope/ref 的缓存」，而是「压根没重取文件树」。**

---

## 3. 逐条排除（按派单要求）

| 假设 | 判定 | 依据 |
|---|---|---|
| `remember` 无 key / key 错 | ❌ 排除 | key = `children.toMap()` / `expandedDirs.toMap()` / `root`（`ReviewFilesTab.kt:242`），**不含 `filter`**；`Map`/`data class FileNode` 是内容相等，`allRows` 会随数据变化重算。**过滤前缓存，正确**。 |
| `derivedStateOf` 依赖漏 | ❌ 排除 | 整个 review 包 **零** `derivedStateOf`（已全包 grep）。本路径是 `remember` + 直接计算。 |
| 过滤结果被写回源状态 | ❌ 排除 | `filter` 只被两个地方写：文本框 `onValueChange` 与 ✕ 的 `onValueChange("")`（`ReviewFilesTab.kt:195` / `384`）。`searchRows` 用 `hasLoadedChildren` 下钻但**只读不写** `expandedDirs`（`ReviewModels.kt:360-371`），已由新 pin `searching never widens the browse tree` 钉死。 |
| `Refresh` 复用旧 scope/ref 缓存 | ⚠️ 部分成立但非根因 | diff 侧确实每次都是新请求身份（`reprobeKey` 驱动，`ReviewPanel.kt:199/210/224`）。**但文件树压根不在 Refresh 的重取范围内** —— `load()` 对已列目录直接 return（`:122`）。这本身是一个真实缺陷，已修（§4.2）。 |
| `Loaded` 被 partial 覆盖 | ❌ 排除 | `children[dir] = result` 只在 `load()` 的回调里整目录覆写（`:127`），失败走 `failedDirs[dir] = true`（同一分支），**没有 partial 覆盖路径**。 |
| 滚动位置未随过滤态复位 | ✅ **真根因** | §2.2，`items(key=…)` + `LazyListState` first-item-moved 保持 + IME 60px 视口。 |

---

## 4. 修复（最小、可解释）

### 4.1 `Clear filter` 立刻恢复全量（回到顶部）

`ReviewFilesTab.kt:284-287` + `:325-327`：

```kotlin
val listState = rememberLazyListState()
LaunchedEffect(filtering) {
    if (!filtering) listState.scrollToItem(0)
}
…
else -> LazyColumn(
    state = listState,
    modifier = Modifier.fillMaxSize(),
) {
```

在 **active → inactive 这条边**回顶。首次组合时 `filtering=false` 会跑一次 `scrollToItem(0)`，而此时本来就是 0，无副作用；之后只会在过滤态真正翻转时触发。这也正是上游 `filterFiles`（`session-review-v2.tsx:88-123`）的行为：搜索结果是一份独立列表，清空即回到树的顶部。

### 4.2 `Refresh` 真的重取 + 失败诚实态

`ReviewFilesTab.kt:104/107/166-175`：新增 `refreshKey: Int = 0`（默认 0 ⇒ 挂载读行为完全不变，所有旧调用点零改动）：

```kotlin
LaunchedEffect(resolvedRoot, refreshKey) {
    val dir = resolvedRoot?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
    if (ReviewTreeRead.mustEvictBeforeReload(refreshKey, mountRefreshKey)) {
        children.remove(dir)          // 先驱逐：load 对已列目录直接 return
    }
    load(dir)
}
```

`ReviewPanel.kt:373-377` 把同一个 settle 信号 `reprobeKey` 透传下去（Refresh 本来就在递增它，不新增信号、不碰 `ChatViewModel`）。

**失败诚实态**：重取失败走 `load()` 原有的 `failedDirs[dir] = true`（`:127`），body 命中 `failedDirs.containsKey(root)` 分支显示 `oc_review_files_error` + 重试（`:265-269`）。**没有任何路径会把「上一次过滤的残留」当作答案渲染** —— 重取前那一行 `children.remove(dir)` 已经把残留删掉了。

### 4.3 新增纯函数 `ReviewTreeRead`（`ReviewModels.kt:373-397`）

把「Refresh 是一次新的读取请求」写成可 JVM 断言的两句话：`readId(root, refreshKey)` 与 `mustEvictBeforeReload(refreshKey, mountRefreshKey)`。

---

## 5. diff 摘要

| 文件 | 改动 |
|---|---|
| `ReviewFilesTab.kt` | +`refreshKey` 参数 / `mountRefreshKey`；`LaunchedEffect(resolvedRoot, refreshKey)` 挂载读+刷新驱逐重取；`rememberLazyListState` + 过滤态归零回顶；`LazyColumn(state = listState)`；`@param` 文档 |
| `ReviewModels.kt` | +`object ReviewTreeRead`（`readId` / `mustEvictBeforeReload`）+ 文档 |
| `ReviewPanel.kt` | Files 分支透传 `refreshKey = reprobeKey`（+4 行注释） |
| `ReviewFilterClearV97Test.kt`（新） | 6 个 pin |

**未新增任何用户可见字符串**（复用 `oc_review_files_error` / `oc_review_filter_clear` / `oc_review_filter_empty`），三语无需同步；`StringsLocaleParityTest` 3/3 通过即证。

**为什么没有降低任何既有断言强度**：
- 生产侧**没有改动** `filterRows` / `searchRows` / `flatten` / `isActive` 任何一行判定逻辑，也没有改动任何既有 `when` 分支的顺序或条件；`allRows` 的 `remember` 键**原样保留**。
- 改动只增加一个滚动状态、一个可选参数和一个「刷新前驱逐」；旧的 `if (children.containsKey(dir) || …) return` 守卫**未被削弱**（驱逐发生在它之前，因此语义从「永不重取」变成「显式重取时重取」）。
- 既有 94 个用例的类全部原样重跑，0 失败。

---

## 6. 测试证据

新增 `app/src/test/java/com/xiufeigo/opencodeshell/ReviewFilterClearV97Test.kt`（纯 JVM，6 例）：

1. `clear restores the recorded full list row for row` —— **四态真值表**：无过滤记全量(15) → 过滤(2: `.gradle`/`gradle`) → 清空 ⇒ 渲染输入与记录的全量**逐行相等且同序**，首行回到 `.dsh-acl-recovery`。
2. `clear does not leave the previous filter's rows on top of the list` —— 钉死真机残留的那两行位置：清空后 top2 必须是 `.dsh-acl-recovery`/`.git`（**不是** `.gradle`/`.idea`），且 15 行全回。
3. `searching never widens the browse tree…` —— 搜索期间 `expandedDirs` 不变，**清空后恢复的是用户离开时那棵树**（全部 `level == 0`）。
4. `a blank filter keeps the full list and is reported inactive` —— `""` / `"   "` 均非激活态，仍是 15 行。
5. `a refresh is a new read request and evicts the cached listing first` —— **`Refresh` 产出新的读取请求身份**：两次刷新 `readId` 不等、不复用挂载读、挂载读不驱逐、任何后续 key 都驱逐、身份按 root 隔离。
6. `a re-read after the refresh returns the full listing, not the filtered subset` —— 驱逐后重取回到**全量**，不是过滤子集。

### 命令（JDK = Android Studio jbr，抢 `.agent-gradle-lock`，前台跑完，已删锁）

```
.\gradlew.bat :app:testDebugUnitTest \
  --tests "com.xiufeigo.opencodeshell.ReviewFilterClearV97Test" \
  --tests "com.xiufeigo.opencodeshell.ReviewVcsProbePlanTest" \
  --tests "com.xiufeigo.opencodeshell.ReviewDiffHonestyV97Test" \
  --tests "com.xiufeigo.opencodeshell.ReviewDiffReadStateV97Test" \
  --tests "com.xiufeigo.opencodeshell.ReviewSearchV97FixTest" \
  --tests "com.xiufeigo.opencodeshell.ReviewModelsTest" \
  --tests "com.xiufeigo.opencodeshell.ReviewWave5Test" \
  --tests "com.xiufeigo.opencodeshell.ReviewVcsReprobeTest" \
  --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest" \
  --console=plain
```

### 原文输出（尾部）

```
> Task :app:compileDebugKotlin
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 58s
52 actionable tasks: 5 executed, 47 up-to-date
```

### 用例数（`app/build/test-results/testDebugUnitTest/TEST-*.xml`）

| 测试类 | 用例 | failures | errors | skipped |
|---|---|---|---|---|
| **ReviewFilterClearV97Test（新）** | **6** | 0 | 0 | 0 |
| ReviewVcsProbePlanTest | 8 | 0 | 0 | 0 |
| ReviewDiffHonestyV97Test | 9 | 0 | 0 | 0 |
| ReviewDiffReadStateV97Test | 8 | 0 | 0 | 0 |
| ReviewSearchV97FixTest | 12 | 0 | 0 | 0 |
| ReviewModelsTest | 12 | 0 | 0 | 0 |
| ReviewWave5Test | 30 | 0 | 0 | 0 |
| ReviewVcsReprobeTest | 12 | 0 | 0 | 0 |
| StringsLocaleParityTest | 3 | 0 | 0 | 0 |
| **合计** | **100** | **0** | **0** | **0** |

全量结果文件扫描：`no <failure>/<error> nodes in any result file`。

**U9 未被破坏**：`ReviewDiffHonestyV97Test`（9）+ `ReviewDiffReadStateV97Test`（8）全绿，四态表（`Attempt.Pending` / `Verdict.IN_FLIGHT` / `Answered(0)` / `Failed`，以及 `mayRenderClean` 只在 `SETTLED_EMPTY` 放行「干净」文案）一字未动；`ReviewChangesTab.kt` 本次**零改动**。**U12** 依赖的 `searchRows` 递归下钻亦零改动，且被新 pin #3 反向加固。

---

## 7. 真机复验步骤（本轮未做 —— 未装机）

前置：同 `REPORT_SWEEPD2.md` §3.11 的会话；`dump --compressed`；**建议先关 IME 再读数**，否则视口只有 60px（这正是上一轮误判的来源）。

1. Review → **Files**，无过滤 ⇒ dump 记全量（应为 15 行，首行 `.dsh-acl-recovery`）。**记下行数与首行**。
2. 点过滤框，`input text gradle` ⇒ dump 断言收敛（`.gradle` / `gradle`），`Clear filter` 按钮在。
3. 点 **Clear filter（✕）** ⇒ **断言：✕ 节点消失、字段回 `Filter files` 占位符、列表回到第 1 步的全量（行数相等、首行 = `.dsh-acl-recovery`）**。修复前此步首行停在 `.gradle`（下标 3）。
4. **打开 IME**（复现上一轮的 60px 视口）再点一次 Clear ⇒ **断言仍回到全量顶部**。这一步是本次修复的核心断言，专门证「视口再小也回顶」。
5. 再输 `gradle` → 点 **Refresh** ⇒ **断言：列表仍是全量**（不是 2 行），且服务端 `GET /file?path=<root>` 出现一次**新的**请求（新的读取身份）。
6. **U9 回归**：窄窗 `wm size 560x768` + `wm density 160` → Changes tab 造超时 ⇒ 断言仍是诚实失败文案 `The diff request timed out or failed. Tap Refresh to try again.`，**不得**出现 `No uncommitted changes yet`；点 Refresh 后仍不假空。**验完 `wm size reset` + `wm density reset` 并复核。**
7. 断开服务端后点 **Refresh** ⇒ **断言：Files 树显示 `oc_review_files_error` + 重试**，不得显示任何上一次的过滤残留。

## 8. 未闭环

1. **真机复验未执行**（本轮纪律：只允许 JVM 单元测试，未装机）。§7 全部为待验项。
2. **`refreshKey` 用的是 `reprobeKey`（settle 后）而非 tap 瞬间**：真正的 tap 时刻信号在 `ChatViewModel`（本轮禁改）。副作用是重取比 evict 晚一拍（用户看到 busy 态结束后清单才换），可接受但非最紧。若要 tap 立即重取，需要宿主加一个 `refreshRequestedKey`，属跨边界改动，未做。
3. **§2.2 的 `LazyListState` first-item-moved 行为**在 JVM 层无法直接 pin（需要 Compose runtime / Robolectric）。已 pin 的是它**上游**的纯函数契约（行输入真值表 + 读取身份）；滚动复位本身只有真机断言（§7.3/§7.4）覆盖。
4. **上一轮测试方法论的缺陷**（比 U11 本身更值得记）：uiautomator dump 只报**可见**节点。IME 打开时面板视口塌到 60px，「行数」读数天然被截断成 2，**不足以支撑「列表只有 2 行」的结论**。建议后续所有「列表行数」类断言先确认 `scrollable` 容器 bounds，再决定要不要收 IME。
5. `Refresh` 在 **mobile** 路径（`MobileSessionTabs.kt`）未接 `refreshKey`（保持默认 0 = 仅挂载读）。该路径的 Refresh 语义与桌面面板不同，未在本次范围内改动，未验。
