# W4-B 会话界面保真 · 交付说明（task-2）

> 负责人 `session-fidelity`。写作用域：`ui/session/timeline/**`、`ui/session/header/**`、
> `ui/session/composer/**`、`res/drawable/ic_oc_dots.xml`、三份 `strings.xml`（只追加，前缀
> `oc_meta_` / `oc_working_` / `oc_jump_` / `oc_step_`）、`app/src/test/**`（我的相关测试）。
> 上游 = `_upstream/packages/**`（只读）。**未 git commit。**
> 依据：`research/wave4_contracts.md` §1/§3、`research/audit_session_ui.md` Top
> 1/2/4/5/6/9/10/12。

## 0. 交付总览

| # | 交付项 | 状态 | 主要文件 |
|---|---|---|---|
| 1 | 工具卡改上游形态（透明无边框 + 12dp 内容导轨） | ✅ 设备已验证 | `ui/session/timeline/PartRenderer.kt` |
| 2 | 默认折叠/分组对齐 `compact` preset + `Used {count} {names}` | ✅ 设备已验证 | `TimelineGrouping.kt`、`ContextToolGroupCard.kt`、`ToolCardPolicy.kt` |
| 3 | `⋮` 改横向三点 `⋯` | ⚠️ 图标已改；头部该按钮被 W4-C 的"打开终端"覆盖层遮住（见 §10-6） | `res/drawable/ic_oc_dots.xml` |
| 4 | 发送按钮 `variant="submit"` 规格 | ✅ 设备已验证（含禁用/启用两态取色） | `ui/session/composer/Composer.kt` |
| 5 | 渲染被丢弃的 part 类型（patch/subtask/agent/retry/step/snapshot） | ✅ 真实 payload 核对 + 单测 | `RawParts.kt`、`PartRenderer.kt` |
| 6 | 消息元信息行 + 复制 | ✅ 设备已验证（文案/UIA 均可查） | `MessageMeta.kt`、`MessageMetaRow.kt`、`MessageTimeline.kt` |
| 7 | Working shimmer 行 + Jump to latest | ✅ 设备已验证（含实测抓 bug 并修复） | `MessageTimeline.kt` |
| 8 | 上下文用量环接真实分母 + 可点 + Cost/%/Tokens | ✅ 环已由 `mobile-form` 接线，设备可见进度弧 | `header/ContextUsagePopover.kt`、`header/SessionHeader.kt`、`header/ContextUsage.kt` |
| 9 | diff 渲染规格转交 | ✅（规格已发 `mobile-form`，其已按规格改完 `ReviewChangesTab.kt`） | — |

**设备实测（`emulator-5554` 2560×1600，装 `app-debug.apk`，会话 `ses_f41f7138cffeB43nHxQAyNE1uf`）**

| 证据 | 结果 |
|---|---|
| 时间线截图 | 每组一行 `使用了 1 Shell` / `使用了 1 思考`（= `Used 1 Shell` / `Used 1 Thought` 的中文文案，来自我的 `oc_meta_used` 模板），折叠态，无卡面/描边 |
| 时间线截图 | 每条消息下方 `Build · gpt-5.6-sol-fast · 52 秒`（助手，本地化时长）与 `Build · gpt-5.6-sol-fast · 12:35`（用户，本地时间）+ 复制图标 |
| `uiautomator dump` | `复制回复` / `复制消息` / `切换工具详情` / `上下文用量` / `跳到最新` 节点均存在且有正确 bounds |
| 头部环放大 | 14dp 轨道 + 自发进度弧（起点 -90°），不再是空环 → 真实分母已接上 |
| 发送键放大取色 | 禁用态中心 ≈ `#ABABAB`（= `#2E2E2E` @ `alpha 0.4` 叠白底）、启用态 `#424242` 填充 + 白色箭头（浅色主题 `variant="submit"`），不再是扁平 accent 蓝 |
| 跳转按钮 | 上滚后出现于 `[1264,1220][1296,1252]`（水平居中、composer 之上），图标朝下；点击后消失并回底 |

新增/修改文件一览：

- 新增：`ui/session/timeline/RawParts.kt`、`ui/session/timeline/MessageMeta.kt`、
  `ui/session/timeline/MessageMetaRow.kt`、`ui/session/header/ContextUsagePopover.kt`
- 修改：`ui/session/timeline/{PartRenderer,ContextToolGroupCard,TimelineGrouping,MessageTimeline,ToolCardPolicy}.kt`、
  `ui/session/header/SessionHeader.kt`、`ui/session/composer/Composer.kt`、
  `res/drawable/ic_oc_dots.xml`、三份 `strings.xml`
- 测试：重写 `TimelineGroupingTest`、修 `ToolCardPolicyTest`；新增 `RawPartsTest`、
  `MessageMetaTest`、`WorkingRowTest`
- 反例（不属于我）：`ui/session/SessionScreen.kt`、`ui/session/review/**`、`ui/session/drawer/**`、
  `ui/App.kt`、`ui/settings/**`、`ChatStore.kt`、`ChatViewModel.kt`、`OpenCodeClient.kt` 未改。

---

## 1. item 1 — 工具卡：透明折叠体 + 12dp 导轨

**上游证据**
- `_upstream/packages/ui/src/components/collapsible.css:1-14`：折叠体 `background-color: transparent;
  border: none; border-radius: var(--radius-md)`。
- `_upstream/packages/session-ui/src/components/basic-tool.css:314-318`：仅当不是
  `data-rail="false"` 时，内容 `padding-inline-start: 12px`（"导轨"）。
- compact 档位（`basic-tool.css:173-200`）：折叠行 `height: 28px`、标题 `13px/530`、副标题
  `13px/440`、`gap: 6px`。
- 输出体（`message-part.css:323-365,420-464`）：`bg-base` 底、0.5px `border-base`、radius 6、
  内边距 12px、等宽 13px、`max-height: 240px`。

**改动**
- `PartRenderer.ToolCard`：删掉 `.clip(shapes.md).background(c.layer01).border(0.5.dp, c.borderBase, shapes.md)`
  （正是审计 `PartRenderer.kt:198-203` 指的那三行）；折叠行改 `height(28.dp)`、`padding` 归零、字号
  13sp/530（标题）与 13sp/440（副标题）、`gap = 6.dp`。图标、状态着色（running→accent / error→
  `stateDangerFg` / 其余 muted）、running spinner、箭头旋转语义**保持不变**。
- `PartRenderer.ToolBody`：内容导轨 `padding(start = 12.dp)` 保留并加注释；输出盒改为
  `bgBase` + 0.5dp `borderBase` + radius 6 + 内边距 12dp；字号 12sp→**13sp**、行高 16sp→**20sp**、
  高度上限 220dp→**240dp**（审计 §2 的三条"未 1:1"）。
- `PartRenderer.ReasoningCard`：同样去掉卡面/描边；标题 13sp/530；标题按上游
  `message-content.tsx:603-607` 在未完成时显示 `Thinking`、完成后 `Thought`（新增
  `oc_step_thinking`）。

**自测方式**
- 单测：分组/策略逻辑见 item 2 的测试（`TimelineGroupingTest`、`ToolCardPolicyTest`）。
- 设备：展开任一 `bash`/`edit` 卡，卡片本身不应再有灰底与描边；展开体内缩进 12dp 对齐；
  一屏多张卡时时间线应恢复"轻量文字流"密度（`adb -s emulator-5554 shell screencap -p /sdcard/t1.png`，
  `adb -s emulator-5554 pull /sdcard/t1.png`）。
- 反证：`grep -n "layer01" PartRenderer.kt` 现在只在 `AttachmentChip`（file part）出现，工具卡分支无命中。

## 2. item 2 — compact preset：全量分组 + `Used {count} {names}`

**上游证据**
- preset：`_upstream/packages/app/src/settings/model.tsx:248`（默认 `compact`）→
  `_upstream/packages/session-ui/src/timeline/detail.ts:41-49`：`shell/edit/thinking` 都是
  `placement:"grouped", details:"collapsed"`，`subagents/notices/tools` 全部 `grouped`。
- 分组规则：`_upstream/packages/session-ui/src/timeline/projection.ts:593-688`（`groupContent()` /
  `toolGroupType()`）：连续成员合成一组；**失败工具**（`currentToolFailed` → `undefined`）与
  `question` 不分组；`detail.thinking.placement === "grouped"` 时 reasoning 也进组。
- 组行文案：`_upstream/packages/session-ui/src/tools/tool-renderer.tsx:516-558`：
  `names = 去重工具名 ", " 连接`；`thoughts = reasoning 数`；`title = names || plural(Thought, thoughts)`；
  `count = 工具数 || thoughts`；`text = plural("ui.messagePart.tools.used", count, {tools: title})`
  → `Used 3 Read, Glob, Grep`；两者都空时用 `ui.messagePart.context.updates`（"Updates"）。
- 运行态：`tool-renderer.tsx:513-515`：`pending = busy || 任一工具 streaming/running`。

**改动**
- `TimelineGrouping` 重写：
  - `isGroupMember`：所有工具（`todowrite` 除外）+ 非空 reasoning；
  - `isStandaloneTool`：失败工具、`question`；
  - `INVISIBLE_TYPES = {step-start, step-finish, snapshot}`，`GROUP_FILLER_TYPES = {step-start, step-finish}`
    （有开着的组就搭车，没有就丢弃；`snapshot` 直接丢）；
  - 新增 `label()` → `Label(toolNames, toolCount, thoughts)`，附带 `count` / `isUpdatesOnly` /
    `englishNames` / `englishTitle` / `englishText`（纯逻辑，JVM 可测）；
  - `groupBusy(parts, working)` = `working || 任一 toolStatus == "running"`；
  - 新增 `toolLabel()`：`apply_patch|patch → Patch`、`skill → Skill`、`subagent → Agent`、
    `websearch → Websearch`（对齐 `tool-renderer.tsx:375-404`）。
- `ContextToolGroupCard` 重写：组行 = spinner(忙时)/glasses 图标 + `Used …` 文案（忙时 shimmer）+
  箭头，`height 28dp`、`gap 6dp`；**删除** M6 的 `Exploring/Explored` 交叉淡入与
  `"3 reads, 2 searches, 1 list"` 计数（上游 `ui.sessionTimeline.exploring/explored` 在
  `_upstream/**/*.tsx` 中无任何消费者，是死 key——审计 Top 5）。展开列表：reasoning 走
  `ReasoningCard`，工具走平面行（可再展开其 body，对齐 `tool-renderer.tsx:697-728` 在组内渲染完整
  `ToolDisplay`）。
- `ToolCardPolicy.DEFAULT_EDIT_EXPANDED: true → false`（compact 默认折叠；同时满足
  `shell-wiring` 的设置默认值一致性要求）。
- **用户手动展开优先**：不变——组行与组内每一项都仍走 `toolOpen` 这张屏幕级 map
  （`resolveOpen(userOpen ?: default)`）。

**自测方式**
- `TimelineGroupingTest`（新增/重写 11 个用例）：全量分组、失败/question 独立、`step-*` 不单列、
  `snapshot` 丢弃、label 文案（`Used 4 Read, Glob, Shell` / `Used 2 Thoughts` / `Updates`）、
  busy 语义、`toolLabel` 映射。
- 设备：进入 `ses_f41f7138cffeB43nHxQAyNE1uf`，默认应只见 1 行 `Used …`（折叠），而不是十几张卡；
  点开后逐项仍可展开；点组行收起。
- 反证：`grep -n oc_context_exploring ContextToolGroupCard.kt` 无命中。

## 3. item 3 — `⋮` → 横向三点 `⋯`

**上游证据**：`_upstream/packages/ui/src/icons/icon/icon.tsx:139-142`
（`outline-dots`，viewBox 16，三个 1×1 方块，中心 x=3/8/13 → 横排；`Icon` 组件在
`:292-315` 只设 `width/height/viewBox`，`stroke-width` 取 SVG 默认 1，即 16px 下 2px 圆点）。

**改动**：`res/drawable/ic_oc_dots.xml` 由竖向 `M12,5.5 / M12,12 / M12,18.5` 改为横向
`M4.5,12 / M12,12 / M19.5,12`（24 viewBox 下等于 16 单位的 3/8/13），圆点直径 3.2（16dp 渲染 ≈2.13dp，
与上游 2px 基本一致）。**注意**：同目录还有若干竖排 dots 图标风险——`grep -rn ic_oc_dots app/src/main`
只有 `SessionHeader.kt:282`（⋮ 菜单触发器）一处消费，无副作用。

**自测方式**：截图看头部右上角应为横排三点；点开菜单仍正常（`oc_more_options` 未变）。

## 4. item 4 — 发送按钮 `variant="submit"`

**上游证据**：`_upstream/packages/ui/src/actions/submit.css:6-34`
（`background-image: linear-gradient(180deg, --submit-highlight, --v2-alpha-light-0) +
linear-gradient(90deg, --v2-background-bg-icon-button-contrast, …)`；`:16` `color: --v2-text-text-contrast`；
`:29,33` dark 下 `text/icon-inverse`；`:72` disabled `opacity: 0.4`）+
`_upstream/packages/ui/src/styles/tokens/theme.css:14,24,397`（contrast = light `#2e2e2e` /
dark `#dbdbdb`；`--submit-highlight` = `--v2-alpha-light-20` / `--v2-alpha-light-60`）。

**改动**：`Composer.kt` 的发送键底色由扁平 `c.accent`（`#3b5cf6`）改为
`SubmitButton`（新增内部对象）：light `#2E2E2E` 底 + 白色图标，dark `#DBDBDB` 底 + `#161616` 图标；
叠一层 `verticalGradient(white 20%/60% → transparent)`；`shadow(1.5dp)` + 0.5dp ring；禁用透明度
0.5→**0.4**。明暗判定用 `c.bgBase.luminance() < 0.5f`（避免新开 token 文件，越界）。
未做：`text-shadow: 0 .5px .5px rgba(0,0,0,.3)`——它作用在上游按钮的**文字**上，本按钮是矢量图标。

**自测方式**：light/dark 各截一张图，按钮应为"对比色反转"（light 深底白箭头 / dark 浅底深箭头），
不再是蓝色；空输入时透明度更淡（0.4）；切到运行中且输入为空应变成 stop 方块，配色同规格。

## 5. item 5 — 渲染此前被丢弃的 part 类型

**数据来源**：契约 `ChatStore.UiPart.rawJson`（`ChatStore.kt:48,55-63`，仅
`subtask/step-start/step-finish/snapshot/patch/agent/retry` 有值）。
**上游 shape**：`_upstream/packages/schema/src/v1/session.ts:87-100`（snapshot/patch）、
`:181-193`（agent）、`:204-218`（subtask）、`:220-231`（retry，`error = {name, data:{message}}`）、
`:233-257`（step-start/step-finish）。
**真实数据核对**（本机 server `http://127.0.0.1:4096`，实测）：

- `ses_f41f7138cffeB43nHxQAyNE1uf`：16 条消息，part 计数 `text 13 / reasoning 5 / tool 3 /
  step-start 9 / step-finish 9`；
- `step-finish` 样例：`{"type":"step-finish","reason":"stop","cost":0.264192,
  "tokens":{"total":33000,"input":32994,"output":6,…}}`；`step-start` 样例：`{"type":"step-start"}`（可带 `snapshot`）。

**改动**（`ui/session/timeline/RawParts.kt` 纯解析 + `PartRenderer.kt` 渲染）
- `patch` → `PatchRow`：`oc_step_patch`="Patch" 标题 + `oc_step_files` 复数字幕（`{n} files`，
  对齐 `tool-renderer.tsx:375-383`），展开列文件路径（等宽 13sp，12dp 导轨）。payload 只有
  `hash`+`files`，没有 `+N/-N`（那是 `apply_patch` 工具的 `metadata.filediff`），所以不伪造计数。
- `subtask` → `SubtaskRow`：task 图标 + `capitalize(agent)` + `description`（退回 `prompt`），
  对齐上游 `task-tool-card` 的信息位。
- `agent` → `AgentNoticeRow`：notice 行 `Agent changed`（13sp/530 `textFaint`）+ 名称（`ms-2` 间距、
  `textMuted`），对齐 `session-timeline-row.tsx:308-315,450-481`。
- `retry` → `RetryNoticeRow`：`ic_oc_alert`（`stateDangerFg`）+ 错误文案截断 80 字符（
  `session-retry.tsx:30-38`）+ shimmer 的 `Retrying - attempt #k`。
- `step-start`/`step-finish` → 不单列；仅作为 group filler 搭车（见 item 2）。
- `snapshot` → 明确忽略（`when` 里显式列出，绝不落进"未知类型"）。
- `PartRenderer` 的 `when` 现在覆盖全部 12 种 v1 part 类型。

**自测方式**
- `RawPartsTest`（8 个用例）：patch/subtask/agent/retry/step-finish 解析、80 字符截断、
  `capitalize`/`basename`、坏 JSON 与缺字段容错。
- 设备：上述会话里 `step-start/step-finish` 必须**不出现**任何"未知类型"卡片，也不出现空行；
  若某会话有 `subtask`/`agent`/`retry`，应分别看到委派行/切换行/重试行。

## 6. item 6 — 消息元信息行 + 复制

**上游证据**
- 助手：`_upstream/packages/session-ui/src/message/message-content.tsx:511-521`（`Agent · Model ·
  时长 · Interrupted`，空段过滤后 `" · "` 连接）、`:548-552`（12px `text-weak`）、`:539-554`（复制按钮
  `outline-copy`→`check`，2s 复位）、`:500-509`（时长 `{n}s` / `{m}m {s}s`）。
- 用户：`:311-354`（`Agent · Model` + 时间 `stamp()`，`metaHead` 用 `\u00A0·\u00A0`）+ `:222-231`。
- 布局：`components/message-part.css:164-211`（用户：右对齐、`min-height:24px`、`margin-top:4px`、
  `gap:10px`）、`:236-265`（助手：左对齐、`gap:10px`；`[data-interrupted]` 时整宽**右对齐** `gap:12px`）。
- 按钮：`:56-76` `MessageActionButton`（`IconButton size="normal" variant="ghost-muted"`，
  `Icon size="small"`=14px）。

**改动**：`MessageMeta.kt`（纯逻辑：`of(message)`、`durationOf`、`capitalizeAgent`、`text`/`head`）+
`MessageMetaRow.kt`（`AssistantMetaRow`/`UserMetaRow`/`CopyActionButton`）+ `MessageTimeline` 接线：
- 助手：在该消息**最后一个非空 text part 之后**渲染（与上游把 copy-wrapper 挂在 text-part 内一致）；
- 用户：气泡下方渲染，右侧 `Agent · Model · 14:32` + 复制；
- 复制：`LocalClipboardManager.setText`，图标 2s 内切 `ic_oc_check`，`contentDescription` 为
  `Copy message`/`Copy response`/`Copied`；
- 触屏适配：上游这两行是 `@media (hover:hover)` 才显现，手机无 hover → 常显（与审计 §11 的判定一致）。
- 数据来自 W4-A 落地的新字段：`UiMessage.agent/model/timeCompleted/interrupted`（`ChatStore.kt:85-91`）；
  `model` 取 `providerID/modelID` 的 model 半边（上游用 provider catalog 的显示名，Android 目录里
  没有该显示名，故用 id，属已登记的偏差）。**缺数据的段不显示**（例：未完成 → 无时长）。

**自测方式**
- `MessageMetaTest`（6 个用例）：段拼接、`Interrupted` 位置、缺数据退化、时长分档（0/12/59/60/65/150s）、
  用户 head 的 `\u00A0·\u00A0`。
- 设备：助手消息底部一行 `Agent · 模型 · 12s`（有数据时）+ 复制图标；点复制→图标变对勾，2s 后复原；
  用户消息右下角 `HH:mm` + 复制。

## 7. item 7 — Working 行 + Jump to latest

**上游证据**
- Working：`_upstream/packages/app/src/session/timeline/message-timeline.tsx:376-391`
  （`flex h-9 items-center gap-2 pt-3 text-[13px] font-[530]`，`TextShimmer active`，
  `data-component="session-working" role="status"`）；判定在 `:331-372`
  （busy、无 pending permission/form、无 retry、尾内容不是非空 text、无可见 running 工具行；
  `context` 组不抑制该行）。
- Jump to latest：`_upstream/packages/app/src/session/timeline/virtualizer.tsx:646-673`
  （`w-8 h-7 rounded-lg`、`bottom-8` 水平居中、`color-mix(bg-base 92%)` + `backdrop-blur(2px)`、
  `200ms ease-out` scale/translate、`session.messages.jumpToLatest`）。

**改动**：`MessageTimeline.kt`
- `showWorkingRow(messages, runStatus, hasPendingRequest)`（纯逻辑，JVM 可测）→
  列表**尾部**（`reverseLayout` 的 item 0）插入 `WorkingRow`：`h 36dp + pt 12dp`、13sp/530 shimmer 的
  `Working`；`runStatus=RUNNING` 且无可见活动时出现。
- `JumpToLatestButton`：`32×28 dp`、radius 8、`bgBase@92%` + `ocRaisedShadow`、16dp 下箭头
  （与上游 `virtualizer.tsx:665-671` 的 `arrow-down` 同向）、`contentDescription = oc_jump_to_latest`；
  `AnimatedVisibility` 200ms `ease-out` fade+scale(0.8)；仅当"已滚离底部且有消息"时出现，点击回到
  `item 0`。
- **设备实测抓到的真 bug（已修）**：可见性最初写成 `!followNextAppend`（由 `SideEffect` 采样
  `layoutInfo`）。滚动本身不会触发重组 → 这个布尔永远不会更新 → 按钮**从不出现**。改成
  `remember { derivedStateOf { … } }` 订阅 `layoutInfo`（只在布尔翻转时通知），并用 `thresholdPx` 作 key；
  重装后 `uiautomator` 已能抓到 `跳到最新 -> [1264,1220][1296,1252]`（水平居中、composer 上方），
  点击后按钮消失且视图回到底部。箭头方向也在同一轮实测中发现画反（旋转了 180°），已去掉旋转。
- 新参数 `hasPendingRequest`（默认 false，`SessionScreen` 不传也能编译；**`mobile-form` 已传**
  `state.pendingPermissions.isNotEmpty() || state.pendingQuestions.isNotEmpty()`，与上游 `blocked` 抑制一致）。

**自测方式**
- `WorkingRowTest`（8 个用例）：只在 RUNNING、空助手消息显示、有 pending 请求隐藏、文本可见后隐藏、
  retry 优先、standalone running 工具抑制、**分组工具不抑制**、step-* 不算可见活动。
- 设备：发一条 prompt 后立刻截图应看到 `Working` shimmer；向上滚动后右下/底部中间出现跳转按钮，
  点击回到底部并继续跟随新内容。

## 8. item 8 — 上下文用量环：真实分母 + 可点 + Cost/%/Tokens

**上游证据**：`_upstream/packages/app/src/session/timeline/session-context-usage.tsx:68-82`
（`total = input+output+reasoning+cache.read+cache.write`；`usage = round(total / model.limit.context * 100)`，
无 >80% 变红规则）、`:93-108`（点击开关 Review→Context 面板）、`:131-155`（tooltip
`w-[120px] gap-2`：Cost / Context / Tokens；Cost 取 `info().cost`，USD 货币格式）。

**改动**
- `ContextUsage`（已有纯逻辑）不动；`SessionHeader` 新增可选参数
  `contextPercent: Int?` / `contextTokens: Long?` / `contextCost: Double?` / `onOpenContext: (() -> Unit)?`，
  并保留旧 `contextProgress: Float = 0f`（**保证 `SessionScreen.kt` 现状不改也能编译**；
  `contextPercent` 优先）。
- 新增 `ContextUsagePopover.kt`：`ContextUsageButton`（40dp 触控位、14dp 环）+ 弹层
  （`w-120dp`、`layer01`、0.5dp 描边、每行"名称 muted 左 / 值 base 右"、gap 8dp）：
  Cost（`NumberFormat.getCurrencyInstance()`）、Context `{percent}%`、Tokens（千分位）。
  **tap** = `onOpenContext()`（有回调时）否则开关弹层；**长按** = 开关弹层。
- 待办（属别人文件）：`mobile-form` 把 `SessionScreen.kt:272` 的
  `contextProgress = ContextUsage.progressOf(state.messages, limit = null)` 换成
  `contextPercent = ContextUsage.percentOf(ContextUsage.lastTokenTotal(state.messages), contextLimit?.toLong())`、
  `contextTokens = …`、`contextCost = sessionCost`、`onOpenContext = {…}`。已发消息，其确认收到并待我通知
  后立即切换（我已于本条实现完成后再发一次确认）。

**自测方式**
- 现有 `ContextUsageTest` / `SessionContextRingTest` 全绿；设备上发一条消息后环应有进度弧（不再永远空）；
  长按环弹出 Cost/Context/Tokens；`mobile-form` 接线后 tap 打开 review。

## 9. item 9 — diff 渲染规格（转交，未改他人文件）

已把精确数值发给 `mobile-form`（其拥有 `ReviewChangesTab.kt`）：
`_upstream/packages/session-ui/src/pierre/index.ts:221-231`（`--diffs-font-size` = 13px、
`--diffs-line-height` = 24px、`--diffs-min-number-column-width` = 3ch）+
`:25-34`（`--diffs-bg-addition-override` = light `color-mix(in lab, bg 33.333%, addition-base)` /
dark 60%；deletion 同理；**只染背景，前景保持语法色**）。其回复：已改为 13sp/24sp、add/delete
只染背景（前景 `textBase`），hunk/context 行色保留。

---

## 10. 已登记的偏差与未决项

1. **model 显示名**：`MessageMeta` 显示 `providerID/modelID` 的 modelID 半边，上游用 provider catalog
   的模型显示名（Android 侧该字段在 picker 的 provider 树里，未在消息上暴露）。修法：`MessageMeta.of`
   加一个可选的 `modelName` 解析器（单点改动）。
2. **Working 判定的数据缺口**：上游还有 `time.streamed`、pending steer、background tasks 三个抑制条件，
   Android 数据模型没有对应字段；已用"尾 part 非空 text / retry / standalone running tool / pending dock"
   覆盖可表达的部分，代码注释写明。
3. **工具输出体的 diff 配色**：`PartRenderer.toolBodyAnnotated` 仍是"整行文字染色"
   （`oc_diff_add_fg`/`oc_diff_del_fg`）。这与审计算作 item 9 的问题同源，但只出现在工具卡展开体的
   `edit/write` 行，且改色会动 `res/values/colors_oc.xml`（不在我的写作用域）——登记给 Lead 决定是否
   另开任务。同时 `colors_oc.xml` 的 delete 色取到上游 **hover** 值（`#CA2D17`）且 dark `#FAA494`
   在上游无对应 token（审计 §2 已记）。
4. **环的 tooltip 定位**：弹层用 Popup 锚定在环按钮右下/右对齐，未做上游的 `shift: -8` 微调。
5. **`hasPendingRequest` 已由 `mobile-form` 接线**（`pendingPermissions`/`pendingQuestions` 非空）。
6. **⋮ 按钮当前不可见（与他人文件的集成冲突，已报 Lead）**：`SessionHeader` 顺序是
   `… ring(40dp) → SessionOverflowMenu(40dp)`，而行尾 40dp 正是 W4-C 加的"打开终端"覆盖层的落点
   （`uiautomator`：`打开终端 -> [2500,116][2532,148]`，头部右侧 2464–2560 被该覆盖层占据）。
   实测 dump 里找不到 `oc_more_options`（"More options"）节点，截图里该位置只有终端按钮。
   我的部分（drawable 横向三点 + `HeaderIconButton` 触发）本身没问题，需要 Lead/`mobile-form`
   决定布局：给覆盖层让位、或把 ⋮ 移到覆盖层左侧。**注意**：这不是我这轮引入的（`git diff` 显示
   `header/SessionHeader.kt` 只有我的 33/-13 行改动，终端/审查按钮来自 `SessionScreen.kt`）。
7. **抽屉/评审面板/App.kt**：明确不属于本任务（Top 7/8/11 由他人负责）。
8. **设备实测中的一次性崩溃（已消失）**：13:33 在 `emulator-5554` 点"打开终端"时崩在
   `ui/terminal/TerminalPanel.kt:575`（`TerminalView.setTypeface` NPE，`ui/terminal/**` 属 Lead）。
   约 20 分钟后同一操作不再复现（终端面板正常打开），推测已被 Lead 修复 —— 仅记录，不需要动作。

## 10.1 测试与构建记录

- `.\gradlew.bat :app:testDebugUnitTest` → **626 tests / 0 failures**（70 个测试类；与 `shell-wiring`
  独立跑出的一致）。过程中出现过的失败均为在途的他人文件（`App.kt`、`ShellWiringTest.kt` 编译错误）
  与并发 Gradle 的文件锁（`Couldn't delete … R.jar`），不是本任务代码的失败。
- 我的 8 个相关测试类单独复跑：`TimelineGroupingTest 13`、`ToolCardPolicyTest 12`、`RawPartsTest 8`、
  `WorkingRowTest 8`、`TimelineFingerprintTest 7`、`ContextUsageTest 6`、`MessageMetaTest 6`、
  `SessionContextRingTest 3` = **63 tests / 0 failures**。
- `.\gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL；APK 已装到 `emulator-5554` 做上表实测。

## 11. 验证命令

```powershell
# 编译（只看我的 scope）
$env:JAVA_HOME='C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1'
.\gradlew.bat :app:compileDebugKotlin --console=plain 2>&1 | Select-String '^e: .*session/(timeline|header|composer)/'
# 单测
.\gradlew.bat :app:testDebugUnitTest --console=plain
# 设备自测
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 reverse tcp:4096 tcp:4096
adb -s emulator-5554 shell am force-stop com.xiufeigo.opencodeshell
adb -s emulator-5554 shell monkey -p com.xiufeigo.opencodeshell 1
adb -s emulator-5554 shell screencap -p /sdcard/w4b.png ; adb -s emulator-5554 pull /sdcard/w4b.png .
# 真实 part 形状核对
Invoke-RestMethod http://127.0.0.1:4096/session/ses_f41f7138cffeB43nHxQAyNE1uf/message
```
