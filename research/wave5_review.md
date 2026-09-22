# Wave 5-R：Review 评审面板补齐（C1–C5）交付报告

> 范围：`research/verify_wave4.md` §20「C. Review / 文件」的 C1–C5。
> 规格来源：`_upstream/packages/**`（v2 分支），下文每条都给上游 `file:line`。
> 交付者：session review 面板实现（worktree `C:\sensorsdata\main\program\opencode-android-shell`）。

## 0. 结论速览

| 项 | 功能 | 状态 | 一句话 |
|---|---|---|---|
| C1 | 侧栏可拖宽 + 双击复位 + 持久化 | ✅ 完成（可即刻生效） | 宽度进面板内部 `rememberSaveable`，clamp 200–480 dp，双击回 240 dp；调用点不改也已能拖 |
| C2 | filter files + unified/split + expand/collapse | ✅ 完成（split/过滤为简化版） | 过滤框 + 四档开关；split = 并排两列同索引配对；collapse = 隐藏全部上下文行 |
| C3 | 行号 + 词级高亮 + 大 diff 虚拟化 | ✅ 完成（词级为近似） | 24 dp 行高 + 3ch 行号槽（`layer01` 底、muted）+ 2 dp 变更条；逐行 LazyColumn item 真虚拟化；**行评论明确未做**（§3 未决 / §6） |
| C4 | Git / Branch / Last turn 三口径 | ⚠️ 控件与规则完成，Git/Branch **数据源未接线 → 灰置** | 三口径 chip 已就位、可用性按上游 `options()` 规则；Android 无 `GET /vcs/diff`，需 Lead 接线（§4.2） |
| C5 | Files 树 A/M/D 徽标 + no-git 空态 | ⚠️ 桌面完成；移动端徽标待接线 | 徽标只做 file 行（与上游一致）；no-git 空态含禁用的「创建 Git 仓库」按钮 |

构建门（`$env:JAVA_HOME=…jdk-21.0.12.1+1`）：

```
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug   → BUILD SUCCESSFUL
```

测试：**基线 70 suites / 628 tests → 85 suites / 818 tests / 0 failures**（同一 worktree 的并行 wave5 agent 也新增了套件，故不是 628+29；本面板净增 1 suite / 29 tests，全过）。

新增/修改文件（全部落在授权边界内）：

| 文件 | 变化 |
|---|---|
| `ui/session/review/ReviewDiffView.kt` | **新增** 295 行：行号/词级/折叠/并排/大 patch 策略（纯逻辑） |
| `ui/session/review/ReviewModels.kt` | +239 行：`ReviewPanelWidth` / `ReviewFileFilter` / `ReviewScopes` / `ReviewChangeBadges`（纯逻辑） |
| `ui/session/review/ReviewPanel.kt` | 重写（229 → 437 行）：面板内宽度状态 + 拖拽手柄 + 三口径 chip + no-git 判定 |
| `ui/session/review/ReviewChangesTab.kt` | 重写（255 → 643 行）：工具条（style/expand 开关）+ 行号槽 + 变更条 + 词级高亮 + 单 LazyColumn 虚拟化 + no-git 空态 |
| `ui/session/review/ReviewFilesTab.kt` | 437 → 585 行：过滤框 + A/M/D 徽标 + 过滤空态 |
| `res/values/strings.xml`、`values-zh-rCN/…`、`values-zh-rTW/…` | 只追加 18 个 `oc_review_*` 键（三份文件键集一致，无重复） |
| `app/src/test/java/com/xiufeigo/opencodeshell/ReviewWave5Test.kt` | **新增** 29 个 JVM 单测 |
| `research/wave5_review.md` | 本文件 |

未改（严格遵守边界）：`ui/App.kt`、`ui/session/SessionScreen.kt`、`ui/session/timeline/**`、`ui/session/composer/**`、`ui/settings/**`、`ChatStore.kt`、`ChatViewModel.kt`、`OpenCodeClient.kt`、`ui/theme/**`、`gradle` 文件、`_upstream/**`；无新增第三方依赖；未 commit。

---

## 1. C1 侧栏可拖宽 + 双击复位 + 持久化

**文件**
- `ReviewModels.kt:167-191` — `ReviewPanelWidth`：`DEFAULT 240 / MIN 200 / MAX 480`、`clamp`、`resize`、`restore`、`reset`（纯逻辑）。
- `ReviewPanel.kt:100-140` — `initialWidth: Dp = LEGACY_PANEL_WIDTH`(340) 参数 + `rememberSaveable` 宽度状态 + `Row` 最外层 `width(widthDp.dp)`。
- `ReviewPanel.kt:307-333` — `PanelResizeHandle`：14 dp 触摸带、1 dp 可见竖线、`detectDragGestures` + `detectTapGestures(onDoubleTap)`。
- `ReviewPanel.kt:437` — `LEGACY_PANEL_WIDTH = 340.dp`（保持既有观感的默认值）。

**上游锚点**
- `_upstream/packages/session-ui/src/v2/components/session-review-v2.tsx:17-19`：`SESSION_REVIEW_V2_SIDEBAR_WIDTH_DEFAULT = 240`、`_MIN = 200`、`_MAX = 480`。
- 同文件 `:64-66`（`width ?? DEFAULT`、`minWidth ?? MIN`、`maxWidth ?? MAX`）、`:82`（`style={{ width: `${width()}px` }}`，宽度是面板自己持有的）、`:135-146`（`session-review-v2-sidebar-resize` + `ResizeHandle` 的 `onResize`/`onDblClick`）。
- `_upstream/packages/app/src/session/review/panel-state.ts:13-25`（持久化默认 `sidebarWidth: 240`）、`:40-44`（`resizeSidebar` 每次 clamp）。
- `session-review-v2.css:41-46`（拖动中禁用宽度过渡）、`:48-59`（手柄绝对定位、1 px `::after`）。

**实现要点**
1. 宽度从 `SessionScreen.kt:539` 的 `Modifier.width(REVIEW_PANEL_WIDTH)` **搬进面板**：`var widthDp by rememberSaveable { mutableStateOf(ReviewPanelWidth.restore(initialWidth.value)) }`，`initialWidth` 默认 340 dp ⇒ 现有调用点一行不改也编译、且外观不变。
2. 宽度修饰符放在**最外层**（`Modifier.width(widthDp.dp)…then(modifier)`）。Compose 的 `SizeElement(enforceIncoming = true)` 语义会把链上更内层的 `width(340.dp)` 收束到外层固定宽度，因此**拖拽今天就能生效**，不需要 Lead 先改调用点。这一点等价于上游把 `width` 写在 `<aside style>` 上（调用方只提供容器）。
3. 手柄放在面板**起始边**（朝时间线一侧，上游 CSS 是 `inset-inline-end` 的 0 宽命中区）；拖拽方向：`resize(current, deltaX) = clamp(current - deltaX)`，向左拖变宽。
4. 双击 → `ReviewPanelWidth.reset()` = 240 dp（上游 `:143`）。
5. 拖动与双击用两个独立 `pointerInput`：位移超过 touch-slop 被 drag detector 消费后，tap detector 的 `waitForUpOrCancellation` 自然取消，两个手势不打架。

**自测**：`ReviewWave5Test`
- `panelWidthDefaultsMatchUpstreamConstants`（240/200/480）
- `panelWidthClampsToUpstreamRange`（120→200、900→480、300→300）
- `panelWidthDragGrowsTowardsTheTimeline`（-20→260、+20→220、边界）
- `panelWidthRestoresPersistedValueAndFallsBackToDefault`（312 保留、null→240、60→200、1000→480、NaN→240、reset 240）

**未决 / 偏差**
- 没有做「拖动时关掉 200 ms 宽度过渡」这类 CSS 细节：面板显隐在本项目是 `if (reviewOpen)` 直接增删节点，本就没有宽度过渡动画。
- 手柄命中区 14 dp（上游是绝对定位 0 宽命中区 + 指针事件）；移动端单击命中更友好。
- 宽度是**面板级**而非全局持久化（上游 `panel-state.ts` 存全局 `review-panel-v2`）。本实现在 `SessionScreen` 的 `key(activeSession.id)` 内，随会话重置；要跨会话记住需 Lead 把初值从 `SettingsStore`/`ChatStore` 传入。

**需要 Lead 接线（可选，非阻塞）**
- `SessionScreen.kt:539`：删掉 `Modifier.width(REVIEW_PANEL_WIDTH)`（并删 `:741` 的常量），同时可显式传 `initialWidth = ReviewPanelWidth.DEFAULT.dp` 让初始值等于上游 240。
- 若要跨会话持久化：`initialWidth` 由设置层提供。

---

## 2. C2 filter files + unified/split + expand/collapse

**文件**
- `ReviewModels.kt:209-268` — `ReviewFileFilter`：`normalize` / `compact` / `matches` / `filter` / `filterRows` / `isActive`。
- `ReviewFilesTab.kt:84-118,267-340` — `filter` 状态（`rememberSaveable`）+ `FileFilterField`（搜索图标 / 占位符 / 清除按钮）。
- `ReviewChangesTab.kt:88-135,210-256` — `diffStyle` / `expandMode` 状态 + `DiffToolbar`。
- `ReviewDiffView.kt:28-42`（`ReviewDiffStyle` / `ReviewExpandMode`）、`:140-167`（collapse 过滤 + 视图组装）、`:169-204`（split 组行）。

**上游锚点**
- `session-review-v2.tsx:88-123`：sidebar 头部的 `TextInput type="search"`，`placeholder/aria-label = ui.sessionReviewV2.filterFiles`、`leadingIcon` 放大镜、`showClearButton = filter.length > 0`。
- 同文件 `:266-309`：`SegmentedControl` 两枚 —— `expand`(`ui.sessionReviewV2.showAllLines`) / `collapse`(`…hideNonDiffLines`) 与 `unified`(`…unifiedDiff`) / `split`(`…splitDiff`)，`aria-label = ui.sessionReviewV2.expandMode|diffView`。
- 过滤语义：`app/src/session/review/review-diff-kinds.ts:57-61`（`query.trim().toLowerCase()` + `includes`）；归一化风格：`app/src/providers/models/search.ts:1-18`（`matchesModelSearch`）。
- 过滤后的呈现：`app/src/session/review/panel.tsx:80-92,239-278`（搜索中切扁平结果列表、`treeKinds` 去掉 `mix`）。

**实现要点**
1. 过滤框只在**树模式**显示（预览态隐藏，Android 是 master-detail 交换，上游是左右分栏）。
2. 匹配 = 上游「小写子串」∪ `matchesModelSearch` 归一化（标点→空格）∪ 紧凑匹配；空白查询 = 不过滤。
3. `filterRows` 保留命中项的**祖先行**，树形不塌（行按 DFS 序，用「更浅层且仍打开」的祖先栈实现）。
4. collapse = 隐藏全部 `CONTEXT` 行（`ui.sessionReviewV2.hideNonDiffLines` 的字面语义）；hunk 头与变更行保留。
5. split = 删除段/新增段**同索引配对**，左右各半宽，未配对侧留空槽；hunk/文件头/`\ No newline` 跨两列整行绘制；两侧行号各自取 old/new。
6. 两个开关默认 `unified` + `expand`（上游 `expandMode` 持久化默认是 `collapse`，见 `panel-state.ts:25`——本项目按既有观感保留 expand，见 §6 偏差）。

**自测**：`filterMatchesCaseInsensitiveSubstring`、`blankFilterIsInactiveAndKeepsEveryFile`、`filterFollowsTheModelSearchNormalisation`、`filterRowsKeepsAncestorsOfAMatch`、`filterRowsAlsoMatchesDirectoryNames`、`collapseModeHidesUnchangedContextLines`、`splitViewPairsDeletionRunsWithAdditionRuns`、`splitPairsPerColumnNumbersForTheTwoSides`、`viewShapesRowsAccordingToTheDiffStyle`。

**未决 / 偏差**
- 过滤只作用于**已懒加载**的树行；上游搜索态会切换成覆盖全仓库的扁平结果列表（`panel.tsx:239-278`），Android 没有全量文件列表接口（`GET /file?path=` 是逐目录），故以「保留祖先的树过滤」近似。已展开目录中的命中一定可见；未展开目录内的命中需要用户展开。
- 过滤不会自动展开目录（若后续要做，可在 `filterRows` 命中祖先时顺手 `expandedDirs[ancestor] = true` 并 `load()`）。
- split 是「同索引并排两列」的简化版：不做 Pierre 的 hunk 内配对优化，长行以省略号截断（unified 仍保留横向滚动）。
- 没有「文件间导航」(`previousFile`/`nextFile`，`session-review-v2.tsx:206-262`)——本轮任务书 C3 只要求行号/词级/虚拟化，见 §6。

---

## 3. C3 行号 + 词级高亮 + 大 diff 虚拟化

**文件**
- `ReviewDiffView.kt:94-295` — `DiffRenderRow`（`oldLine`/`newLine`/`changed`）、`PatchRow`（`Line` / `Columns`）、`number()`（hunk 头解析）、`emphasize()`、`changedRanges()`、`split()`、`view()`、`autoCollapse()`、`MAX_ROWS` / `MAX_HIGHLIGHT_CHARS` / `AUTO_COLLAPSE_ROWS`。
- `ReviewChangesTab.kt:260-317`（`DiffLineRow`：2 dp 变更条 + 行号槽 + 内容）、`:319-430`（`SplitLineRow` / `SplitCell`）、`:556-600`（`rememberHighlight` / `buildHighlight`）、`:625-643`（几何常量）。
- `ReviewChangesTab.kt:127-200` — 单 `LazyColumn`：每行一个 item（真虚拟化）、`Modifier.animateItem()` 保留展开时淡入。

**上游锚点**
- `_upstream/packages/session-ui/src/pierre/index.ts:205-216`：`disableLineNumbers: false`（`:205`，行号开启）、`diffStyle`（`:207`）、`diffIndicators: "bars"`（`:208`，左侧变更条）、`lineDiffType: "word-alt"`（`:213`）、`maxLineDiffLength: 1000`（`:214`）、`maxLineLengthForHighlighting: 1000`（`:215`）。
- 同文件 `:221-231`：`--diffs-font-size: var(--font-size-small)`（=13 px）、`--diffs-line-height: 24px`、`--diffs-min-number-column-width: 3ch`、`--diffs-font-family: var(--font-family-mono)`。
- 虚拟化参考：上游文件树用 `@tanstack/solid-virtual`（`file-tree-v2.tsx:18,158-178`），Pierre 自身也是虚拟渲染。

**实现要点**
1. **行号槽**：宽 24 dp（`3ch` 在 13 sp 等宽下 ≈ 23.4 dp，Compose 无 `ch` 单位，取整并注释说明），底色 `layer01`、数字 11 sp `textFaint`、右对齐；`@@ -a,b +c,d @@` 解析出两套游标，add 只推新行号、del 只推旧行号、context 双推——两列数字与 unified 行严格同源。
2. **变更条**：行首 2 dp（`diffIndicators: "bars"`），新增 = `stateSuccessFg`、删除 = `stateDangerFg`，行底色沿用 W4-B 的 `stateSuccessBg` / `stateDangerBg`（只有背景承载 +/- 状态，前景保持正文色）。
3. **词级高亮（近似）**：删除段与紧随的新增段**同索引配对**，`changedRanges` 裁掉公共前缀/后缀，剩下的中间区间在两侧都加粗 + 加底色（`stateSuccessBorder`/`stateDangerBorder` @45%）；行长 > 1000 字符跳过（上游 `maxLineDiffLength: 1000`）。
4. **大 diff 虚拟化**：补丁行不再塞进一个 `Column`，而是**逐行成为 `LazyColumn` item**（`itemsIndexed`，key = `line:<file>:<index>`），只有可见行参与组合；同时 `MAX_ROWS = 4000` 截断（尾部提示 `oc_review_truncated_rows`），`AUTO_COLLAPSE_ROWS = 400` 以上的 patch 在 `expand` 模式下自动以 `collapse` 打开（避免打开即上万行）。
5. 横向滚动：所有 patch 行共享同一个 `ScrollState`（每行各自 `horizontalScroll`，offset 同步），保留 W4-B 的「不换行 + 横向滚动」观感。

**自测**：`rowsNumberBothSidesFromTheHunkHeader`、`rowsWithoutAHunkCarryNoNumbers`、`contentStripsTheSignColumnOnly`、`wordRangesMarkTheDifferingMiddle`、`changedRangesHandleInsertionsAndEqualLines`、`longLinesSkipWordHighlighting`、`viewCapsLargePatchesAndReportsTruncation`、`hugePatchesOpenCollapsed`、`lineCountCountsRawPatchLines`。

**未决 / 偏差（重要）**
- **词级高亮是近似**：不是 Pierre `word-alt` 的 token 级（Myers）diff，而是「配对行公共前后缀裁剪 + 中间段加粗/底色」。纯插入/纯删除只在有内容的一侧生效。
- **行评论（line comment）未做，原因**：上游 `session-review-file-preview-v2.tsx:120-162` 的行评论依赖①文件预览的行选区模型（`SelectedLineRange` + `selectionSide` + `selectionPreview`）、②评论文本编辑器与草稿、③把评论写进 `prompt.context`（`model.ts:211-246`）。Android 侧这三样都不存在：`ChatViewModel`/`prompt.context` 等同通路在本次授权边界之外，且移动端没有做文件预览的跨行长按选区。只做「点行号弹输入框」会是半截功能（无法回填到上下文），故本轮不做并在报告中明示。
- 行号槽对 **hunk 头/文件头**行也占用（留空），保持列对齐；上游把这些画成 `hunkSeparators: "line-info-basic"` 的整行分隔条（`pierre/index.ts:212`），本项目沿用 W4-B 的 `layer01` 整行底色近似。
- **原始补丁头 4 行仍会渲染**（`diff --git` / `index` / `---` / `+++`，归类 `FILE_HEADER`，无行号无变更条）：上游 `disableFileHeader: true`（`pierre/index.ts:216`）其实是把 Pierre 自己的文件头关掉，因为预览面板已自带文件名；Android 沿用 W4-B 的「原样展示补丁」行为，多出的这 4 行也承载 `new file mode`/`rename from` 等元信息。若要严格 1:1 隐藏，只需在 `ReviewDiffView.rows` 里过滤 `DiffLineKind.FILE_HEADER`（一处改动，已有单测锚点可同步调整）。
- split 视图下 long line 用省略号截断（不横向滚动），已在上文注明。
- 展开动画从 `AnimatedVisibility`（整组）改为 `Modifier.animateItem()`（逐行淡入）：`LazyColumn` 无法对一组 item 做整体动画，虚拟化优先。

---

## 4. C4 变更口径（Git / Branch / Last turn）

### 4.1 已实现部分

**文件**
- `ReviewModels.kt:272-344` — `ReviewChangeScope{GIT,BRANCH,LAST_TURN}`、`ReviewScopes.available/enabled/diffsFor/labelRes/emptyRes`。
- `ReviewPanel.kt:100-101,240-300`（新参数、`scopeName` 状态、三口径 chip 行、`LaunchedEffect(scope)` 请求 VCS diff）。
- `ReviewChangesTab.kt:88-111` — 空态文案按口径取 `ReviewScopes.emptyRes(scope)`。

**上游锚点**
- `_upstream/packages/app/src/session/review/view.tsx:228-247`：标题处的 `Select`，label = `ui.sessionReview.title.git|branch|lastTurn`；`:253-257`：空态文案随口径（`session.review.noUncommittedChanges` / `noBranchChanges` / `noChanges`）。
- `app/src/session/review/model.ts:23`（`ChangeMode = "git"|"branch"|"turn"`）、`:55-68`（`options()`：有 vcs → `git`；`vcs.branch.current && vcs.branch.default && current !== default` → `branch`）、`:93-110`（`vcs.diff({mode: "working" | "branch"})`）、`:145-148`（`turn` 口径返回 `[]`）、`:417`（`noGit`）。

**实现要点**
- 面板头部标题下方一行三枚 chip（`ReviewChip`，选中 = `layer03`，禁用 = 40% alpha 且 `clickable(enabled=false)`），标签/空态文案与上游键一一对应（`oc_review_scope_git|branch|turn`、`oc_review_changes_empty[_git|_branch]`）。
- 可用性完全照抄上游规则：`available(hasVcs, currentBranch, defaultBranch)`；`enabled` 再叠一层「数据源是否接线」（`onRequestVcsDiff != null`）。
- 数据选择：`LAST_TURN → diffs`（`session.diff`，`ChatViewModel.refreshDiff()` 那条 `GET /session/:id/diff`），`GIT/BRANCH → vcsDiffs`（接线后才有）。
- 口径切换时 `LaunchedEffect(scope)` 调 `onRequestVcsDiff(scope)`（`rememberUpdatedState` 取最新 lambda），宿主把结果回填到 `vcsDiffs`。

**自测**：`scopesOfferGitAndBranchOnlyWhenUpstreamWould`、`vcsScopesStayDisabledUntilTheirDataSourceIsWired`、`scopeSelectsItsOwnDiffSource`、`scopeLabelsAndEmptyCopyAreDistinct`。

### 4.2 未完成部分与接线点（⚠️）

- **Android 没有 VCS diff 接口**：`OpenCodeClient` 只有 `sessionDiff`（`GET /session/:id/diff`，`OpenCodeClient.kt:903`）与 `vcsInfo`（`GET /vcs`，`:933`），没有上游的 `vcs.diff({mode})`。因此在 Lead 接线前，**Git / Branch 两枚 chip 是灰置的**（可见但不可选），选中态只能是「上一轮」。
- **`VcsInfo` 少了分支元数据**：`OpenCodeClient.VcsInfo`（`:679-684`）目前只解析 `branch`，而 Branch 口径的开关需要 `branch.current != branch.default`（上游 `model.ts:59-66`）。`ChatViewModel.reviewVcsInfo` 也只回传 branch。
- 需要 Lead 接线（按依赖顺序）：
  1. `OpenCodeClient`：加 `vcsDiff(mode: String /* working|branch */, context: Int? = null, directory: String? = null)`（对齐上游 `vcs.diff` 入参），并把 `VcsInfo` 扩成 `branch.current/default`。
  2. `ChatViewModel`：加 `reviewVcsDiff(scope: ReviewChangeScope, onResult: (List<FileDiff>?) -> Unit)`（照 `reviewVcsInfo` 的 `Dispatchers.IO + Main` 模式，`ChatViewModel.kt:782-797`）。
  3. `SessionScreen.kt:527-543`：给 `SessionReviewPanel` 传 `hasVcs`（`GET /vcs` 成功 / `project.vcs`）、`defaultBranch`、`vcsDiffs`、`onRequestVcsDiff`。
  4. 移动端（`SessionScreen.kt:575-578` 的 `ReviewChangesTab`）如需口径切换，另加一行 `scope`；本轮移动端只保留「上一轮」。
- 备注（上游事实，供 Lead 决策）：上游 v2 **自己也没有 `turn` 数据源** —— `model.ts:55-68` 的 `options()` 从不下发 `turn`，`:145-148` 对 `turn` 直接返回空数组；`ChangeMode` 里的 `"turn"` 只在 v1 文案里留有 label。Android 把 `session.diff` 当作这一口径、把 Git/Branch 留给 VCS 接口，方向与上游 `options()` 一致。

---

## 5. C5 Files 树 A/M/D 徽标 + no-git 空态

**文件**
- `ReviewModels.kt:358-385` — `ReviewChangeBadges.badges/forPath/matches`。
- `ReviewFilesTab.kt:84-118,140-160`（新增 `diffs` 参数、`badges` 映射、行内 `badge`）、`:348-420`（`FileTreeRow` 徽标渲染）。
- `ReviewChangesTab.kt:507-556` — `NoGitEmpty`（标题 / 描述 / 禁用按钮）。
- `ReviewPanel.kt:150-175` — `noGit` 判定（`GET /vcs` 未回 + 无 diff，700 ms 稳定后成立；`hasVcs == false` 立即成立）。

**上游锚点**
- `app/src/session/files/file-tree-v2.tsx:114-122`：徽标只在 `local.node.type === "file"` 时渲染（目录不显示），内容 = `kindLabel(value)`；`:47-57`：`kindLabel` A/D/M、`kindChange` added/deleted/modified。
- `app/src/session/review/review-diff-kinds.ts:33-55`：`reviewDiffKinds` 把状态映射成 kind（`added`→add、`deleted`→del、其它→mix/modified）。
- `_upstream/packages/session-ui/src/v2/components/session-review-empty-no-git-v2.tsx:11-27`：空态 = FileIcon(`.gitignore`) + `ui.sessionReviewV2.empty.noGit.title` + `.description` + 「创建 Git 仓库」按钮（`pending` 时变 `…actionLoading` 并禁用）；`app/src/session/review/view.tsx:263-272` 与 `model.ts:417`（`noGit = project && !project.vcs`）。
- 文案另见 `session.review.noVcs.createGit.title|description|action`（`app/src/runtime/i18n/en.ts:793-796`）。

**实现要点**
- 徽标字母复用 `ReviewDiff.statusLabel`（A/M/D），颜色 `stateSuccessFg/stateInfoFg/stateDangerFg`，10 sp、`layer02` 小胶囊、右对齐；**目录行永不显示徽标**（与上游一致）。
- 路径匹配：树行是绝对路径、`FileDiff.file` 是工作区相对路径 → 先按 `root` 前缀裁成相对路径**精确查表**，退化时按**分隔符边界后缀**匹配（`…/src/App.kt` 命中 `src/App.kt`，`BarApp.kt` 不命中 `App.kt`）。
- no-git 空态：不真的建仓；`onInitGit == null`（默认）时按钮禁用（`layer02 @40%` + `textFaint`），`gitPending` 时显示「正在创建 Git 仓库…」。
- 判定为何要延迟：`GET /vcs` 是异步的，面板刚打开时 `branch == null` 只代表「未知」。所以默认策略是「等 700 ms，若仍无 branch 且无 diff → 判定无 git」，避免在正常 git 仓库里闪一下空态；`hasVcs == false` 由宿主传入时立即成立。

**自测**：`badgesMapFileStatusToTheTreeLetter`（A/D/M + 无路径条目被丢弃）、`badgesResolveAnAbsoluteTreeRowThroughTheRoot`（Windows 绝对路径 / 后缀回退 / 边界不误伤 / `matches`）、`badgesAreEmptyWithoutDiffs`。

**未决 / 偏差**
- 移动端 Files tab（`SessionScreen.kt:580-587`）没有传 `diffs`，所以**移动端暂时没有徽标**（接口已就绪，一行接线即可）。
- 「创建 Git 仓库」没有任何网络实现（上游是 `POST /vcs/init`，本仓库 `OpenCodeClient` 没有该接口）→ 按钮禁用 + 本说明。
- 目录聚合徽标（`reviewDiffKinds` 会把 kind 汇总到祖先目录）**刻意未做**：上游渲染层也会因 `node.type !== "file"` 跳过目录徽标（`file-tree-v2.tsx:116`），且 `treeKinds` 还会过滤掉 `mix`。
- 媒体预览（图片/音视频，`mediaKindFromPath`）未做：不在本轮任务书 C5 要求内，且 Android 需要额外的解码/预览组件。

---

## 6. 明确未做清单（含原因）

| 项 | 状态 | 原因 |
|---|---|---|
| 行评论（line comment）| ❌ 未做 | 依赖文件预览行选区 + 评论文本输入 + `prompt.context` 写回（`session-review-file-preview-v2.tsx:120-162`、`review/model.ts:211-246`）；`ChatViewModel`/`prompt.context` 在授权边界外，移动端也没有跨行选区模型。硬做只能得到无法回填上下文的半截功能。 |
| 文件间导航（上一个/下一个文件）| ❌ 未做 | §20 列为 C3 的一部分，但本轮任务书 C3 明确只要求「行号 + 词级高亮 + 大 diff 虚拟化」。实现需要在 LazyColumn 上维护「文件头 → item index」表并驱动 `animateScrollToItem`，属于可加但非本轮范围。 |
| Git / Branch 口径的真实数据 | ⚠️ 灰置 | Android 缺 `GET /vcs/diff`（§4.2 给出完整接线步骤）。 |
| 移动端 Files 徽标 | ⚠️ 待接线 | `SessionScreen.kt:580-587` 加 `diffs = state.diff` 即可。 |
| 跨会话记住面板宽度 | ❌ 未做 | 面板级 `rememberSaveable`；全局持久化需 SettingsStore 参与（上游 `panel-state.ts` 是全局 key）。 |
| 面板内 kebab 菜单 / wrap lines 设置项 | ❌ 未做 | 不在 C1–C5 列表内（`session.review.wrapLines` 属 D6 设置面）。 |
| split 视图横向滚动 / hunk 内配对优化 | ⚠️ 简化 | 见 §2 偏差（长行省略号截断）。 |
| `expand` 与上游默认 `collapse` 不一致 | ⚠️ 保留现状 | 上游 `panel-state.ts:25` 持久化默认 `collapse`；本项目沿用 W4-B 的「展开」默认，避免改变既有观感。若 Lead 要与上游对齐，改 `ReviewChangesTab.kt:99` 的初值即可。 |

---

## 7. 需要 Lead 接线的点（汇总）

| # | 位置 | 要做什么 | 影响 |
|---|---|---|---|
| 1 | `SessionScreen.kt:539` + `:741` | 删掉 `Modifier.width(REVIEW_PANEL_WIDTH)`（可同时传 `initialWidth = ReviewPanelWidth.DEFAULT.dp`）| 让初始值回到上游 240；**不接线也能拖**，只是初值仍是 340 |
| 2 | `OpenCodeClient`（新接口）+ `VcsInfo` 扩展 | 加 `vcsDiff(mode = working\|branch, context?)`；`VcsInfo` 补 `branch.current/default` | C4 的 Git / Branch 口径 |
| 3 | `ChatViewModel` | 加 `reviewVcsDiff(scope, onResult)`（照 `reviewVcsInfo` 模式）| 同上 |
| 4 | `SessionScreen.kt:527-543` | 传 `hasVcs` / `defaultBranch` / `vcsDiffs` / `onRequestVcsDiff`（以及可选的 `onInitGit`）| C4 生效 + C5 的 no-git 判定变精确（`hasVcs=false` 立即显示空态） |
| 5 | `SessionScreen.kt:575-578`（移动端 Changes） | 传 `scope`（以及 `noGit`）| 移动端空态文案/口径切换 |
| 6 | `SessionScreen.kt:580-587`（移动端 Files） | 传 `diffs = state.diff` | 移动端 A/M/D 徽标 |
| 7 | （可选）设置层 | 面板宽度跨会话持久化 | 与上游 `review-panel-v2` 持久化等价 |

> 所有新参数都有默认值：**不接线 = 可编译、可运行、桌面端 C1/C2/C3/C5(桌面) 全部可用**；接线只影响 C4 两口径与移动端两处。

---

## 8. 自测与构建

**新增测试**：`app/src/test/java/com/xiufeigo/opencodeshell/ReviewWave5Test.kt`（29 例，纯 JVM，无 Android 依赖）覆盖：
宽度 clamp/拖动/持久化（4）、过滤匹配与树过滤（5）、行号与词级区间（5）、折叠/并排/截断/自动折叠/行数（8）、口径可用性与数据选择与文案（4）、徽标映射与路径解析（3）。

**构建门**：

```powershell
$env:JAVA_HOME='C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

结果见下节（§9）。最终实测：`BUILD SUCCESSFUL`，**85 suites / 818 tests / 0 failures / 0 errors**（含并行 wave5 其他 agent 的套件；本面板新增 `ReviewWave5Test` 29 例全过）。`assembleDebug` 产出 `app/build/outputs/apk/debug/app-debug.apk`（32 581 906 B）。

> 环境备注 1：任务书给的 `JAVA_HOME=…\jdk-21.0.12.1` 目录实际不存在（真实目录名带 `+1`：`jdk-21.0.12.1+1`，同一 Temurin 21.0.12.1 LTS），本报告用真实目录执行。
> 环境备注 2：本轮有多个 agent 同时在同一个 worktree 里写代码并跑同一个 Gradle 项目，`app/build` 共享，出现过两类互相干扰：Kotlin/测试产物被并发进程占用（`Unable to delete directory … test-results/…/binary`、`Could not read file … .class; size in bytes: -1`）。按任务书「并发锁失败 60s 重试」处理，未改动任何他人文件。

---

## 9. 构建/测试实测记录

### 9.1 最终构建门（绿）

| 命令 | 结果 |
|---|---|
| `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug` | ✅ **BUILD SUCCESSFUL**（exit 0），`85 suites / 818 tests / 0 failures / 0 errors` |
| ↳ 其中本面板 `ReviewWave5Test` | ✅ `29 tests / 0 failures`（`app/build/test-results/testDebugUnitTest/TEST-com.xiufeigo.opencodeshell.ReviewWave5Test.xml`） |
| ↳ `:app:assembleDebug` 产物 | `app/build/outputs/apk/debug/app-debug.apk`（32 581 906 B） |
| `.\gradlew.bat :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` | ✅ BUILD SUCCESSFUL（重试第 8 次全模块转绿） |

**新增测试首轮 10 个失败已定位并修正**：把 4 行补丁头（`diff --git` / `index` / `---` / `+++`）漏算进行号索引导致期望值错误，另有 3 处断言写法问题（`Int` 与 `Float` 直接比较 1 处、目录命中的过滤期望写错 1 处、`normalize` 期望漏了扩展名 1 处）。**修正全部落在测试文件里，逻辑实现未改**；修完后 29/29 全绿。

### 9.2 测试套件规模

- 基线：70 suites / 628 tests。
- 本轮：**85 suites / 818 tests / 0 failures**（同一 worktree 里并行 wave5 agent 也新增了套件，故不是 628+29）。
- 本面板净增：1 suite / 29 tests。

### 9.3 过程中的并发干扰（已消除，记录备查）

本轮有多个 agent 同时在同一个 worktree 写代码并跑同一个 Gradle 项目（共享 `app/build`），期间出现过：

1. **模块编译被他人半成品打断**（6 次重试）：失败点全在 `ui/session/timeline/**`（`Syntax error: Unclosed comment`）、`ui/session/composer/**`（字符串键缺失/参数改名）、`ui/servers/**` + `ui/settings/**`（`ServerHealth` 重构中）。`ui/session/review/**` 在这些失败里**一次都没有出现**。
2. **构建产物争用**：`Unable to delete directory …test-results/testDebugUnitTest/binary`、`Could not read file …/AttachmentPolicy.class; size in bytes: -1`——按任务书「并发锁失败 60s 重试」处理。
3. **他人测试一度红**：`Wave5ConnectTest` 曾有 3 个失败（相对路径少了模块前缀 `src/main/res/...`、`R.string` id 期望不符、健康探测期望 404 实得 405），随其作者修完而消失，**与本次 Review 面板改动无关**，也未被本轮触碰。

重试约 3 轮（每轮 90s）后 `:app:testDebugUnitTest :app:assembleDebug` 转绿并稳定复现。

### 9.4 未安装到设备

按任务书要求，未 `adb install`；设备验证留给统一验证环节。


