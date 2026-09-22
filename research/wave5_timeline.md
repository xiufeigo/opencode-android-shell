# Wave 5-T — 会话时间线缺失功能（A2 / A3 / A5 / A6）

> 实现工程师交付报告。所有上游引用格式为 `_upstream/...:行号`（v2 sparse checkout）。
> 本波只改：`ui/session/timeline/**`、`ui/session/header/**`、三份 `strings.xml`（仅追加 `<name="oc_search_*|oc_nav_*|oc_turn_*|oc_sep_*">`）、`app/src/test/**`、本文件。
> **未改**：`ui/App.kt`、`ui/session/SessionScreen.kt`、`ui/session/composer/**`、`ui/session/review/**`、`ui/settings/**`、`ChatStore.kt`、`ChatViewModel.kt`、`OpenCodeClient.kt`、`SettingsStore.kt`、`ui/theme/**`、gradle 文件、`_upstream/**`。

---

## 0. 交付总览

| 项 | 状态 | 纯逻辑（JVM 测试） | UI |
|---|---|---|---|
| **A3 时间线分隔条** | ✅ 完成 | `TimelineRows.build/currentTick/…`（`TimelineRowsTest`，22 例） | `TimelineSeparatorRow` 接进 `MessageTimeline` 的 `LazyColumn` |
| **A5 消息导航轨** | ✅ 完成 | `TimelineRows.userTicks/currentTick` + `MessageNavRailPolicy`（`MessageNavRailPolicyTest`，5 例） | `MessageNavRail` 右侧刻度条，点击 `animateScrollToItem` |
| **A6 本轮变更汇总** | ✅ 完成（仅路径，见 §4 数据缺口） | `TurnChanges.of/pathsOf/visibleCount`（`TurnChangesTest`，15 例） | `TurnChangesBlock` 收在 assistant turn 末尾 |
| **A2 会话内搜索栏** | ✅ 完成（正文高亮粒度见 §4） | `TimelineSearch.hits/ranges/move/clamp/highlight`（`TimelineSearchTest`，18 例） | 头部搜索入口 + 搜索栏（`TimelineSearchBar.kt`），命中计数/上一下一/Esc |
| `mod+f` 全局快捷键 | ⚠️ 未接（**需 Lead**，§5.1） | — | 头部图标按钮可用；输入框内 Enter/↑/↓/Esc 可用 |

新增文件（行数）：

| 文件 | 行数 | 内容 |
|---|---|---|
| `ui/session/timeline/TimelineRows.kt` | 219 | A3 行模型（消息 + 分隔条）+ A5 刻度/当前刻度 + 纯函数 |
| `ui/session/timeline/TimelineSeparator.kt` | 111 | 分隔条 widget + `Row.Separator` → 本地化标签 |
| `ui/session/timeline/TimelineSearch.kt` | 264 | A2 纯匹配 + 进程级会话搜索控制器（上游控制器是每屏一个 + 全局注册） |
| `ui/session/timeline/TimelineSearchBar.kt`（header 包） | 269 | 搜索入口按钮 + 搜索栏 UI |
| `ui/session/timeline/MessageNavRail.kt` | 121 | A5 刻度条 + 纯几何策略 |
| `ui/session/timeline/TurnChanges.kt` | 281 | A6 文件路径抽取 + 汇总块 |

修改文件：`MessageTimeline.kt`（770 行）、`SessionHeader.kt`（422 行）、三份 `strings.xml`（各 +28~30 行，只追加）。

---

## 1. A3 时间线分隔条

### 改了什么

| 位置 | 说明 |
|---|---|
| `ui/session/timeline/TimelineRows.kt:56-215` | 纯插入规则：`build(messages)` 返回**上→下**的行序列（`Row.Message` / `Row.Separator`），`MessageTimeline` 再 `asReversed()` 交给 `reverseLayout` 列表（与原来 `messages.asReversed()` 的语序完全一致） |
| `TimelineRows.kt:118-148` | 插入判定（见下） |
| `TimelineRows.kt:193-198` | `modelLabel()`：`providerID/modelID` → `modelID` |
| `ui/session/timeline/TimelineSeparator.kt:46-67` | `h-8` + 左右 1 px `borderStrong` + 13 sp/440 `textFaint` 标签 |
| `TimelineSeparator.kt:89-106` | kind → 文案映射 |
| `MessageTimeline.kt:276-292` | `items(rows.asReversed(), key = { it.key })`，`when(row)` 分派 |

判定规则（每条都有上游锚点）：

| 分隔条 | 判定（Android） | 上游锚点 |
|---|---|---|
| 模型切换 | `messages[i].model != null && != messages[i-1].model`；会话首个模型不算切换 | `timeline/projection.ts:274-278`（`model-switched` notice 进 `groupMessages` 的 separate 集）、`session-timeline-row.tsx:392-514`（`data-type="model-switched"` + `py-2`）、标签 `model().label` |
| 压缩开始 | 该消息含 `type == "compaction"` 的 part，在消息行**前** | `message/message-content.tsx:434-438`（`ui.messagePart.compaction.started`） |
| 压缩结束 | 同一消息行**后** | `message-content.tsx:461-465`（`ui.messagePart.compaction` → 复用现有 `oc_compaction`） |
| 中断 | `UiMessage.interrupted == true`，放在该 turn **之后**（下一条消息之前；若它是最后一条则收尾） | `projection.ts:327-353`（`TurnDivider` 在 interrupted 输出之后）、`session-timeline-row.tsx:637-653`、`ui.message.interrupted`（`ui/i18n/en.ts:245`） |

额外保真：`projection.ts:350` 的 `!compaction && …` —— **压缩的 turn 不再画中断分隔条**（`TimelineRows.kt:126`，测试 `aCompactedTurnKeepsItsInterruptionSilent`）。

文案的复用与新增：
- 中断 = 现有 `oc_meta_interrupted`（"Interrupted" / "已中断" / "已中斷"，与 `ui.message.interrupted` 逐字一致）。
- 压缩结束 = 现有 `oc_compaction`（"Session compacted" = `ui.messagePart.compaction`，`en.ts:106`）。
- 压缩开始 = 新增 `oc_sep_compaction_started`（"Session compaction started" = `ui.messagePart.compaction.started`，`en.ts:107`；上游 zh/zht 未翻译，本仓按 `oc_compaction` 风格补译）。
- 模型切换标签 = 模型 id（无新文案）。

### 怎么自测（JVM）

`app/src/test/java/com/xiufeigo/opencodeshell/TimelineRowsTest.kt`（22 例）：
`modelSwitchInsertsTheSeparatorBeforeTheNewModel`、`theInitialModelIsNotASwitch`、`anUnchangedModelInsertsNothing`、
`compactionInsertsStartAndEndAroundItsMessage`、`interruptedTurnGetsADividerAfterIt`、`aTrailingInterruptionClosesTheTimeline`、
`aCompactedTurnKeepsItsInterruptionSilent`、`rowKeysAreUniqueAndStable` 等。

设备自测建议（Lead 复验）：`SessionScreen` 桌面形态滚动一条「换模型 → 被中断 → 压缩」的会话，应看到 4 条分隔条；移动形态同样。

### 未决项 / 偏差
- 上游分隔条上的 `ProviderIcon`（`timeline-separator.tsx:9-11`）与 `variant` 圆点+后缀（`:16-27`）未做：`UiMessage` 没有 variant，会话 UI 包内也没有 provider 图标解析器（`OcTheme` 无 provider 图标资源）。见 `TimelineSeparator.kt:39-45`。
- 上游每个分隔条外层还有 `py-2`（`session-timeline-row.tsx:510,647`）。本实现改用 `padding(vertical = 2.dp)` + 列表本身的 24 dp `TURN_GAP`，避免和 `Arrangement.spacedBy` 叠加成 40 dp。
- 压缩「结束」永远存在（`UiPart` 没有 compaction 状态，见 §4）。上游在压缩仍在跑时只画 start；本实现会同时给出 end 标签。
- 分隔条的 `LazyColumn` key 是 `sep:<kind>:<锚点消息 id>`（`TimelineRows.kt:94`），消息行 key 仍是消息 id，稳定。

---

## 2. A5 消息导航轨

### 改了什么

| 位置 | 说明 |
|---|---|
| `TimelineRows.kt:152-168` | `userTicks(rows, leading)`：每个 **user** 消息一个刻度，按时间正序返回，并算出它在 `reverseLayout` 列表里的真实 index（含 `Working` / error 两张前置行的偏移） |
| `TimelineRows.kt:171-188` | `currentTick()`：视口中点最近的刻度（平局取更新的一条） |
| `ui/session/timeline/MessageNavRail.kt:46-91` | 24 dp 宽刻度条：12 dp 刻度按钮 + 16×1 dp `iconBase` 线，当前项 24×2 dp `textBase` 线；`heightIn(max)+verticalScroll`；点击回调 |
| `MessageNavRail.kt:95-114` | `MessageNavRailPolicy.tickHeightDp/needsScroll`（纯逻辑） |
| `MessageTimeline.kt:311-346` | 覆盖层：`BoxWithConstraints` 贴 `CenterEnd`，`padding(end = 12.dp(滚动条) + 2.dp)`，点击 → `scrollToItem/animateScrollToItem` |
| `MessageTimeline.kt:202-214` | `derivedStateOf` 采样 `visibleItemsInfo` 的首/末 index → `currentTick` |

### 上游锚点

`components/message-nav.tsx:33-79`（compact 分支：`ul[data-component=message-nav][data-size=compact]`，每项 `li[data-slot=message-nav-item]` → `div[data-slot=message-nav-tick-button]` → `div[data-slot=message-nav-tick-line]`；`data-active` 标记当前）、`:28-31`（`onMessageSelect`）、`:67-69`（无文字 turn 的 `ui.messageNav.newMessage` 回落）。
`components/message-nav.css:14-16`（24 px 宽）、`:30-44`（12 px 高 / 当前 2 px×100% / `--icon-strong-base`）、`:47-59`（16×1 px / `--icon-base` / hover 撑满）。
`MessageTimeline.kt` 的 index 语义依据：`OcScrollbarModel.thumb` 的既有注释（`ui/components/OcScrollbar.kt:36-47`）——index 0 是视觉底部、`firstVisibleItemIndex` 从底部数起。

### 怎么自测（JVM）

`MessageNavRailPolicyTest.kt`（5 例）：`aShortRailKeepsTheNativeTickHeight`、`aLongRailShrinksItsTicks`、`theTickNeverShrinksBelowTheFloor`、`scrollingIsOnlyNeededWhenTheNativeTicksDoNotFit`。
`TimelineRowsTest.kt`：`ticksAreOldestFirstAndOffsetByTheLeadingRows`（含 `leading=2` 的偏移）、`assistantTurnsHaveNoTick`、`tickPreviewCollapsesWhitespaceAndTruncates`、`currentTickPicksTheOneNearestTheViewportMiddle`、`currentTickPrefersTheNewerTickOnATie`。

设备自测建议：长会话右缘应出现刻度条；点第 1 个刻度跳到最早的用户消息；滚动时高亮刻度跟随；消息很多时刻度变矮而不是溢出到 composer。

### 未决项 / 偏差
- 上游 compact 轨有 `HoverCard`（`message-nav.tsx:83-103`）+ `size="normal"` 的 200 px 卡片列表（标题预览 + diff 条）。移动端无 hover，未做；刻度只带语义 `contentDescription`（预览文字 / `oc_nav_new_message`）。
- diff 条（`message-nav.tsx:109-150` 的 5 根 add/del 条）需要 `FileDiffInfo`，本波拿不到（§4）。
- 刻度条是**覆盖层**，不改变列表宽度；上游在桌面是布局内的 24 px 列。移动端优先（覆盖层不会挤压正文）。
- 当前刻度的采样锚点是「可见区间中点」，上游由 `current?: SessionMessageUser` 显式传入（调用点在 app 包，本波不可见）。

---

## 3. A6 本轮变更汇总

### 改了什么

| 位置 | 说明 |
|---|---|
| `ui/session/timeline/TurnChanges.kt:76-180` | 纯抽取：`of(message)` / `pathsOf(part)` / `visibleCount` / `hiddenCount` |
| `TurnChanges.kt:107-112` | 数据源 A：`tool` part 且 `toolName ∈ {edit, write, patch, apply_patch}` |
| `TurnChanges.kt:113-143` | 从 `state.input` 取 `files[].path`、`filePath`/`file_path`/`path`/`file` |
| `TurnChanges.kt:144-170` | 回退：`patchText` / `files[].patch` 的 `*** Update File:` / `+++ b/…` 头（跳过 `/dev/null`） |
| `TurnChanges.kt:107` | 数据源 B：`patch` part 的 `rawJson.files`（复用 `RawParts.patch`，`RawParts.kt:53-59`） |
| `TurnChanges.kt:193-243` | `TurnChangesBlock`：`{n} Changed files` + `Show all/Show less` + 文件行 + `+{n} more files` |
| `MessageTimeline.kt:472-503` | `AssistantMessageRow` 末尾渲染（assistant turn 的最后一块） |

### 上游锚点

- 文案：`ui/i18n/en.ts:62-66`（`ui.sessionTurn.diffs.changed.one/other` = "{{count}} Changed file(s)"、`showAll` "Show all"、`showLess` "Show less"、`more` "+{{count}} more files"）；复数用法见 `app/src/session/summary/panel.tsx:105`。
- 版式/状态：`session-ui/src/components/session-turn.css:137-206`（`[data-slot=session-turn-diffs]`、`-header` 44 px+`gap:8px`+sticky、`-label` medium/`text-strong`、`-toggle` `text-interactive-base`、`[data-component=session-turn-diffs-group][data-show-all]`、`-more` weak/small、`-content`）与 `:217-245`（一行 = `-diff-filename` 加粗 + `-diff-directory` 弱化）。
- 截断语义：`tools/tool-renderer.tsx:439-480`（`showAll || all.length <= 10`，其余折成 `+{n} more`）。
- 「变更文件」这一术语的上游来源也见 `session-diff.ts` / `apply-patch-file.ts:17-26`（`FileDiffInfo`）。

### 怎么自测（JVM）

`TurnChangesTest.kt`（15 例）：`editReturnsItsFilePath`、`writeReturnsItsFilePath`、`applyPatchReturnsEveryPatchedPath`、`applyPatchFallsBackToItsUnifiedPatchHeaders`、`unifiedPatchHeadersAndDevNullAreHandled`、`nonEditToolsContributeNothing`、`patchPartContributionsComeFromItsFileList`、`aTurnKeepsFirstAppearanceOrderAndDeduplicates`、`collapsedListStopsAtTenAndReportsTheRest`、`shortListsAreNeverTruncated` 等。

设备自测建议：让 agent 改 2~3 个文件，assistant turn 末尾应出现「3 个文件已更改 / 全部显示」；超过 10 个文件时折叠出「+N 个文件」，点开变「收起」。

### 未决项 / 偏差（数据缺口，**不是伪造**）
- 每文件 `additions/deletions/status`（上游 `FileDiffInfo`，`apply-patch-file.ts:17-26`）与 `session.diff` 不在 `ChatStore.UiMessage/UiPart` 里（`ChatStore.kt:67-92`），`ChatStore` 属禁改文件 → **只显示计数与路径**，没有 +/− 统计、没有 per-file diff 展开（`session-turn-diff-view`、`:254-277` 未做）。
- header 未做 `position:sticky`（44 px 粘性行会和 `MessageTimeline` 自己的 sticky 会话头打架），改为普通行；`[data-slot=session-turn-diffs-header]` 的 hover 才显示 toggle（`:174-185`）在触屏上恒显。
- 「Show all」只在 `files.size > 10` 时出现（1~10 个文件时折叠视图本就完整）；上游 `data-show-all` 属性的可见性由 hover 决定。
- 同一 turn 同时有 `edit` 与 `patch` part 时，路径按「工具在 part 顺序 → patch part」合并去重（`TurnChanges.kt:100-105`）。

---

## 4. A2 会话内搜索栏

### 改了什么

| 位置 | 说明 |
|---|---|
| `ui/session/timeline/TimelineSearch.kt:94-190` | 纯逻辑：`normalize`（`:94`）、`ranges`（`:101`）、`hits`（`:120`）、`clamp`（`:152`）、`move`（`:159`）、`hitAt`（`:166`）、`highlight`（`:169`） |
| `TimelineSearch.kt:194-262` | 会话级控制器（Compose state）：`open/query/active/count/available` + `toggle/open/close/setQuery/moveBy/publish/setAvailable/reset` |
| `ui/session/header/TimelineSearchBar.kt:79-106` | 头部搜索入口按钮（40 dp 圆形、16 dp 放大镜） |
| `TimelineSearchBar.kt:111-236` | 搜索栏：`h-7`、前置放大镜、`n/N`（11 sp muted）、上一个/下一个、×；Esc/Enter/Shift+Enter/↓/↑ 键位 |
| `ui/session/header/SessionHeader.kt:127-181` | 入口位置：尾部簇的**最前**（上游 `props.search` 的位置），打开时整行让给搜索栏 |
| `MessageTimeline.kt:163-200` | 计算 hits、`SideEffect` 发布 count、`DisposableEffect` 挂载/离开、命中导航滚动 |
| `MessageTimeline.kt:506-521` | `hitSurface(hit, active)`：命中部件淡色带、当前命中强色带 |
| `MessageTimeline.kt:551-566` | 用户气泡**逐字**高亮（`highlightedBody`，`MessageTimeline.kt:688-704`） |

### 上游锚点

- `app/src/session/timeline/search-controller.ts:94`（`trim().toLowerCase()`）、`:96-135`（matches：只搜 text part，逐 occurrence 计数）、`:137-143`（activeIndex）、`:174-182`（打开即聚焦并全选）、`:193-195`（文档级 open 事件）、`:197-219`（open/close/setValue）、`:221-240`（滚动到命中）、`:242-252`（`move` 环绕）。
- `search-bar.tsx:11-12`（仅 visible 时渲染、`h-7 w-[200px] max-w-[50vw]`）、`:18-49`（输入 + Esc/Enter/↓/↑）、`:51-58`（`{active+1}/{count}`，11 px muted tabular）、`:59-67`（× 关闭）。
- 文案：`app/runtime/i18n/en.ts:852-853`（`session.search.placeholder` = "Find..."、`noResults` = "No matches"）、`ui/i18n/en.ts:136-139`（`ui.fileSearch.previousMatch` / `nextMatch` / `close`）。
- 入口位置：`app/src/session/timeline/message-timeline.tsx:544-548`（`{props.search}` 在尾部簇首位，其后是 `SessionContextUsage`）。上游**没有搜索图标**，只有 `mod+f` 命令（`search-controller.ts:184-192`）；本波补一个图标按钮作为触屏入口（Android 追加）。
- 高亮：上游用 `CSS.highlights`（`search-controller.ts:17-19,69-79`），选择器是 `[data-slot="text-part-body"]` + `[data-slot="user-message-text"]`。

### 怎么自测（JVM）

`TimelineSearchTest.kt`（18 例）：`queryIsTrimmedAndCaseFolded`、`emptyQueryMatchesNothing`、`everyOccurrenceIsAHit`、`occurrencesAreCountedPerPart`、`occurrencesDoNotOverlap`、`messageAndPartOrderIsPreserved`、`onlyTextPartsAreSearched`、`toolNamesAreSearchable`、`hiddenToolsAreNotSearchable`、`toolSearchCanBeTurnedOff`、`clampResetsAStaleCursor`、`moveWrapsAroundBothWays`、`highlightCollectsHitPartsAndTheActiveOne`、`rangesAreHalfOpenAndCaseInsensitive` 等。

设备自测建议：打开会话 → 头部放大镜 → 输入一个词；应显示 `1/N`，↑/↓（或 IME 搜索键）切换并在列表内跳转到对应消息；用户气泡内该词有底色；Esc / × 关闭后头部恢复标题与环。

### 关键结构决策（必须知道）

**为什么搜索状态是进程级对象**：`TimelineSearch` 是一个 `object`（`TimelineSearch.kt:56`），由 `MessageTimeline` 发布 `count`/`available`，由 `SessionHeader` 渲染入口与输入栏。
原因是本波禁改 `ui/session/SessionScreen.kt`，而两种形态下头部与时间线的**组合位置不同**：
- 桌面：`SessionScreen.kt:453-462` 把 `headerSlot` 作为 `MessageTimeline(stickyHeader = …)` 传入 → 头部在时间线**内部**；
- 移动：`SessionScreen.kt:552` 直接渲染 `headerSlot()`，而 `:597` 的 `MessageTimeline(header = null)` 在**另一个子树** → 头部在时间线**外部**。

因此 `CompositionLocal` 只能在桌面生效；要让「移动端与桌面都要可用」，跨子树的可观察状态是唯一可行形态。这也与上游一致：上游同样是**一个会话屏一个 controller + 全局注册**（`command.register("session.search", …)` `:184-192`、`document.addEventListener("opencode:timeline-search-open")` `:193-195`）。
`MessageTimeline` 在 `onDispose` 调 `TimelineSearch.reset()`（`MessageTimeline.kt:183-186`），所以切会话/切 tab 不会留下搜索态。

### 未决项 / 偏差
1. **正文高亮粒度**：assistant 文本走 `OcMarkdown`（`ui/markdown/**`，禁改），只能按**部件**染色（`hitSurface`）；用户气泡是**逐字**高亮（`MessageTimeline.kt:551-566,688-704`）。要逐字命中 markdown 正文，需要给 `OcMarkdown` 加 `highlightRanges: List<IntRange>` 参数（见 §5.2）。
2. 命中导航是**行粒度**（一个消息一行）；上游还能滚到 part 内部（`search-controller.ts:221-240` 用 `getBoundingClientRect` 定位 range）。`UiMessage`/`LazyColumn` 没有 part 级滚动目标。
3. 工具名匹配的是**原始工具名 + 英文卡面标题**（`TimelineSearch.kt:184-190`）。本地化标题（zh 的 `oc_tool_edit`="编辑"）**搜不到**——纯匹配器拿不到 `Resources`（见 §5.3）。
4. `mod+f` 全局键位未接（§5.1）；输入框内的 Enter/Shift+Enter/↑/↓/Esc 已按上游接线。
5. 移动端搜索栏占满整行（标题、上下文环、⋮ 在搜索时隐藏）：上游是 `w-[200px] max-w-[50vw]`，360 dp 手机上放不下；这是为触屏做的显式取舍（`SessionHeader.kt:150-155`）。
6. 上游「零命中」时 bar 会渲染 `0/0`（`search-bar.tsx:56`）；本实现改为控制器自带但上游未用的 `session.search.noResults`（"No matches"/"无匹配结果"）。
7. 上游 `setValue` 每次都跳到第一条命中并 `pauseAutoScroll()`；本实现的跳转 key 是「当前命中的消息 id + 打开态」，因此**同一条消息内**继续输入不会重复滚动（`MessageTimeline.kt:188-200`，注释已写）。

---

## 5. 需要 Lead 接线

### 5.1 `mod+f` → 打开会话搜索（建议必接）

上游 `session.search` 命令绑定 `mod+f`（`search-controller.ts:184-192`），Android 的全局键位表在 `ui/App.kt`（禁改）。建议补丁（3 行）：

```kotlin
// ui/App.kt:640-655 GlobalChord 增加一个成员
    /** `session.search`（`search-controller.ts:188`）—— `mod+f`。 */
    SESSION_SEARCH,

// ui/App.kt:665-685 globalChord(...) 增加一条分支（放在 PALETTE 之前更贴近"更精确者优先"）
        mod && key == Key.F && !shift -> GlobalChord.SESSION_SEARCH

// ui/App.kt:605-632 GlobalKeybindFocus 的 when 增加
                    GlobalChord.SESSION_SEARCH -> {
                        TimelineSearch.open()
                        true
                    }
```

注意：`GlobalKeybindFocus` 只在「根 focusable 持有焦点」时生效（`ui/App.kt:581-590`），而搜索栏聚焦后自身处理 Esc（本波已实现），行为与上游一致。
（`SessionScreen` 已在导航树下，`TimelineSearch` 无需额外接线即可用。）

### 5.2 markdown 正文的逐字高亮（可选，A2 保真提升）

`ui/markdown/MarkdownRenderer.kt` 的 `OcMarkdown(...)` 增加一个可选参数，例如 `highlightRanges: List<IntRange> = emptyList(), highlightColor: Color`，在段落/行内 span 拼接时对落在范围内的字加 `SpanStyle(background = …)`。有了它，`MessageTimeline.hitSurface` 可以只保留"当前命中"的整块淡色，逐字高亮交给 markdown 层（用户气泡已经这样做了）。
上游对应物：`search-controller.ts:69-79` 的 `CSS.highlights` + `:17-18` 的两个 highlight 名。

### 5.3 工具标题按语言搜索（可选）

`TimelineSearch.hits(...)` 的 `includeToolNames` 之外再加一个 `toolTitle: ((ChatStore.UiPart) -> String?)?` 解析器；调用点传 `{ part -> ToolPresentation.titleRes(part.toolName)?.let { stringResource(it) } }`（需要在组合里预解析成 `Map<partId, String>`）。当前实现匹配的是原始名与英文标题（见 §4 未决项 3）。

### 5.4 若 Lead 愿意改 `SessionScreen`（更干净，但非必须）

把搜索状态从 `object` 提升为屏级实例，显式传给两侧（当前形态已在两端可用）：

```kotlin
val search = remember { TimelineSearchController() }          // 普通 class，非 object
SessionHeader(..., timelineSearch = search)
MessageTimeline(..., search = search)
```

`MessageTimeline` 与 `SessionHeader` 的新参数都已有默认值（`messageNav`、`turnChanges`），**现有 `SessionScreen` 调用点无需改动即可编译**；只有显式接上 A5/A6 开关或屏级 controller 时才需要动它。

### 5.5 设备验证建议（由 Lead 统一执行）

不需要安装到模拟器（本波未做）；建议的观察点：① 换模型/中断/压缩的分隔条；② 右缘刻度条点击跳转 + 高亮跟随；③ 多文件 turn 的「N 个文件已更改 / 全部显示」；④ 头部放大镜 → 输入 → `n/N` → ↑↓ → Esc。

---

## 6. 数据缺口清单（本波拿不到，未伪造）

| 想要的数据 | 上游来源 | 现状 |
|---|---|---|
| `session.diff` / `FileDiffInfo{file,patch,additions,deletions,status}` | `session-ui/src/components/apply-patch-file.ts:17-26` | `ChatStore.UiMessage/UiPart` 无此字段（`ChatStore.kt:67-92`）；A6 只显示路径计数 |
| 每文件 +/− 与 diff 体 | `session-turn.css:254-277` | 同上；`session-turn-diff-view` 未渲染 |
| 导航轨的 add/del 5 根条 | `message-nav.tsx:109-150` | 同上 |
| compaction 状态（running/completed/failed）与 outcome 后缀 | `message-content.tsx:400-465` | `UiPart(type="compaction")` 无状态；压缩结束标签固定为 `oc_compaction` |
| model variant（思考强度） | `timeline-separator.tsx:16-27` | `UiMessage` 无 variant |
| provider 图标 | `timeline-separator.tsx:9-11` | 会话 UI 内无 provider 图标解析器 |

---

## 7. 构建与测试门

命令（与 `research/verify_wave4.md:455-457` 一致）：

```powershell
$env:JAVA_HOME='C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

| 阶段 | suites | tests | failures | 说明 |
|---|---|---|---|---|
| 本波开工前基线（同目录实测，`research/verify_wave4.md:457` 写的是 70/628） | 72 | 637 | 0 | 仓库在本波开工前已含其他波次的用例 |
| 本波结束（`gate_retry_1.txt`） | 85 | 818 | **0** | `BUILD SUCCESSFUL in 56s`，`:app:testDebugUnitTest` + `:app:assembleDebug` 全绿，产出 `app/build/outputs/apk/debug/app-debug.apk`（31 054 912 B，23:15:25） |

> 85/818 里包含**同工作区其他 Wave 并发新增的 suite**（connections/servers、composer、review、prompt-history 等）；本波自己的增量是 **4 个 suite / 60 个用例**，全部通过：

| 本波新增 suite | tests | failures |
|---|---|---|
| `TimelineSearchTest`（A2） | 18 | 0 |
| `TimelineRowsTest`（A3 + A5） | 22 | 0 |
| `TurnChangesTest`（A6） | 15 | 0 |
| `MessageNavRailPolicyTest`（A5 几何） | 5 | 0 |

设备安装/截图**未做**（按要求设备由 Lead 统一验证）。

---

## 8. 并发构建记录（同工作区其他 Wave 正在改同一模块）

本波做门时，`app/src/main/java/**` 里有**其他 Wave（connections/servers + composer）**正在编辑的文件。整个收敛过程如下（每次都记录了原文）：

1. **其他波次的编译错误**（不在本波 scope，全部由对方随后修掉）：
   ```
   e: ui/servers/ConnectServerScreen.kt:86 Unresolved reference 'ServerHealthProbe'
   e: ui/servers/ServerHealth.kt:268 Syntax error: Unclosed comment
   e: ui/settings/ServersPanel.kt:41 Unresolved reference 'HealthMessage'
   e: ui/session/composer/Composer.kt:708 Unresolved reference 'onSizeChanged'
   e: app/src/test/.../Wave5PromptHistoryTest.kt:32 No value passed for parameter 'shell'
   ```
   过滤后确认：**`ui/session/timeline/**`、`ui/session/header/**`、本波 4 个测试文件零错误**。
   （题外提示：`ServerHealth.kt:268 Unclosed comment` 与本波自己踩到的是同一个坑 —— Kotlin 块注释**可嵌套**，KDoc 里写 `` `pkg/**` `` 会开一个嵌套注释，行尾的 `*/` 只关掉它、外层注释再也闭合不了。本波已把 `MessageTimeline.kt` / `TimelineSeparator.kt` 的 `ui/xxx/**` 写法改成 `package ui.xxx`。）
2. **Gradle 文件锁**（按任务书「等待 60 秒重试一次」执行）：
   ```
   R.jar: 另一个程序正在使用此文件，进程无法访问。
   classes.jar: 另一个程序正在使用此文件，进程无法访问。
   java.io.IOException: Unable to delete directory 'app\build\test-results\testDebugUnitTest\binary'
   ```
   并发方是同一工作区的其他 agent 的 Gradle 构建（`Get-Process java` 可见多个 daemon/test worker）。
3. **最终结果**：第 1 次重试即 `BUILD SUCCESSFUL in 56s`（`gate_retry_1.txt`），85 suites / 818 tests / **0 failures**。

### 顺带修掉的自审问题（本波自己的）

- `derivedStateOf` 闭包陈旧：A5 的 `currentTick` 原本 `remember(listState) { derivedStateOf { … ticks … } }`，`ticks` 变化后闭包仍指向旧列表 → 改为 `remember(listState, ticks)`。
- 高亮入口原来按「第一个命中部件 id」给分组行染色，当前命中落在组内其它部件时会显示成弱高亮 → 改为 `hitSurface(hit, active)` 两个布尔。

