# 会话界面组件级保真对比审计（Android ↔ 上游 opencode v2）

只读审计。本文件是本次审计**唯一**新增的产物，未修改任何现有代码。

## 0. 审计范围、方法与证据约定

**被审对象**

| 侧 | 根目录 |
|---|---|
| Android 原生（Kotlin/Compose） | `app/src/main/java/com/xiufeigo/opencodeshell/` |
| 上游 opencode v2（TypeScript/SolidJS，分支 v2） | `_upstream/packages/` |

**证据约定**（全文统一）

- 表格里的路径前缀：`A/` = `app/src/main/java/com/xiufeigo/opencodeshell/`；`U/` = `_upstream/packages/`。行号一律用 `read`/`grep` 工具的实际行号（与 ripgrep 一致）。
- 上游 CSS 是**视觉规格**（`_upstream/packages/session-ui/src/**/*.css`、`_upstream/packages/ui/src/**/*.css`）。CSS 里的 px 是 CSS px；Android 用 dp。**1 px ↔ 1 dp 视为 1:1 换算**（两者在移动端密度下等价），字号 px ↔ sp 同理。凡未按 1:1 换算的，差异要点里写明。
- 状态只取四值：`1:1` / `近似` / `缺失` / `未确认`。`近似` 必须写清差在哪（尺寸、token 名、行高、圆角、文案）。`缺失` 表示 Android 侧不存在该实现，且已说明搜索位置。`未确认` 表示证据不足，绝不臆测。
- 找不到证据就写 `未确认`；不把"文档/注释里声称的上游"当作证据。

**关键 token 参照值**（用于读表）

| token | light | dark | 出处 |
|---|---|---|---|
| `--v2-background-bg-base` | `#ffffff` | `#161616` | `U/ui/src/styles/tokens/theme.css:7,149` |
| `--v2-background-bg-layer-01` | `--v2-grey-100` `#fafafa` | `--v2-grey-1000` `#242424` | 同上 `:8,391` |
| `--v2-background-bg-layer-02` | `#f2f2f2` | `#2e2e2e` | 同上 |
| `--v2-border-border-base` | `--v2-alpha-dark-10` `#0000001a` | `--v2-alpha-light-10` `#ffffff1a` | 同上 `:41,178` |
| `--v2-text-text-base` | `#161616` | `#fafafa` | 同上 |
| `--v2-text-text-muted` | `--v2-grey-700` `#5c5c5c` | `--v2-grey-500` `#aeaeae` | 同上 `:21,160` |
| `--v2-state-fg-danger` | `--v2-red-800` `#b82d35` | `--v2-red-500` `#f17471` | 同上 `:66,201` |
| `--icon-diff-add-base` | `#3a8437` | `#9bcd97` | `U/ui/src/styles/theme.css:307,578` |
| `--icon-diff-delete-base` | `#ed4831` | `#fc533a` | 同上 `:310,581` |
| `--line-height-compact` / `--line-height-base` | `16px` / `20px` | 同 | `U/ui/src/styles/tokens/theme.css:139-140` |
| `--line-height-large` | `150%` | 同 | `U/ui/src/styles/theme.css`（`--font-size-small:13px` `--font-size-base:14px` 同处） |
| `--radius-sm/md/lg/xl` | `4/6/8/10px` | 同 | `U/ui/src/styles/theme.css` |

Android 的对应值在 `A/ui/theme/OcTheme.kt:95-206`（Dark/Light）与 `A/res/values/colors_oc.xml`、`A/res/values-night/colors_oc.xml`；抽样核对：`layer01` dark `#242424` ✓、`textMuted` dark `#aeaeae` ✓、`stateDangerFg` light `#b82d35` ✓、`oc_diff_add_fg` `#3A8437`/`#9BCD97` ✓ 与上游**逐位一致**。

---

## 1. 消息 / part 渲染类型覆盖

上游 part 分派入口：`U/session-ui/src/tools/tool-renderer.tsx:1189-1261`（`ToolDisplay`）、`U/session-ui/src/message/message-content.tsx`（text/reasoning/user/compaction）、`U/session-ui/src/timeline/projection.ts:596-688`（part→group 分组）、`U/session-ui/src/timeline/timeline-row.ts:25-105`（行的种类）。
Android 分派入口：`A/ui/session/timeline/PartRenderer.kt:94-124`（`when (part.type)`），共 5 个分支：`text` / `tool` / `reasoning` / `compaction` / `file`；无 `else`，未知类型**静默不渲染**。

| 上游组件/特性 | 上游证据(file:line) | Android 实现(file:line) | 状态 | 差异要点 |
|---|---|---|---|---|
| assistant text part（正文 markdown） | `U/session-ui/src/message/message-content.tsx:529-538`；`U/session-ui/src/components/message-part.css:228-234` | `A/ui/session/timeline/PartRenderer.kt:95-103`；`A/ui/markdown/MarkdownRenderer.kt:83-113` | 近似 | 正文都走 markdown；但上游 `[data-component="text-part"] { margin-top: 24px }`（`content` 间距行时归零，css:274-276）而 Android 在 turn 内用统一 `spacedBy(12.dp)`（`A/ui/session/timeline/MessageTimeline.kt:295,212`），分段间距 24px→12dp |
| text part 复制按钮（hover 显现，2 s「Copied」） | `U/session-ui/src/message/message-content.tsx:539-554`；`U/session-ui/src/components/message-part.css:236-255` | — | 缺失 | Android 正文没有复制入口。搜 `A/ui/session/timeline/`、`A/ui/markdown/`（`copy`/`Clipboard`）只命中代码块长按复制（`MarkdownRenderer.kt:254-276`）与 `MentionColors`，无正文复制 |
| text part meta（`Agent · Model · 时长 · Interrupted`，12px `text-weak`，右对齐） | `U/session-ui/src/message/message-content.tsx:511-521,548-552`；css:257-265 | — | 缺失 | Android 助手消息下方完全没有元信息行；`copy-wrapper` 的 `min-height:24px; margin-top:4px; gap:10px` 也无对应 |
| 中断标记（`data-interrupted`，整行右移、gap 12） | `U/session-ui/src/components/message-part.css:261-265`；`message-content.tsx:485-488` | — | 缺失 | 上游中断时把复制行改成整宽右对齐并追加「Interrupted」；Android 无 |
| user message 气泡（右对齐、`max-width: min(82%,64ch)`、`layer-02`、radius 10、`8px 12px`、14px/`line-height-large`） | `U/session-ui/src/message/message-content.tsx:301-308`；css:129-162 | `A/ui/session/timeline/MessageTimeline.kt:250-286` | 近似 | 82 %、radius 10、12/8 dp、14 sp/20 sp 全部 1:1（`A/ui/theme/OcTheme.kt:293` `xl=10dp`）。**底色 token 不同**：上游 `:root body` 作用域下 dark = `layer-01`、light = `layer-02`（css:1422-1427、1467-1469 两段覆盖），Android 恒用 `layer02`（`MessageTimeline.kt:272`）→ dark 下上游 `#242424`、Android `#2e2e2e` |
| user message 元信息 + 复制 + revert | `U/session-ui/src/message/message-content.tsx:311-354`；css:164-217 | — | 缺失 | Android 用户气泡下没有 `Agent · Model · 时间`、没有复制按钮、没有「Revert message」。`ui.message.revertMessage` 在 Android 无对应实现（搜 `A/` 全仓 `revert` 只命中终端/评审无关处） |
| user message 附件区（`flex-wrap`、gap 8、`max-width: min(82%,64ch)`） | `U/session-ui/src/message/message-content.tsx:246-289`；css:28-127 | `A/ui/session/timeline/MessageTimeline.kt:255-259` | 近似 | Android 用 `Column` 纵向排列（`padding(top=12dp)`），上游是 `flex-wrap` 横向换行、gap 8px |
| 图片附件（`58×46`、`object-fit:cover`、radius 6、inset 0.5px 描边） | css:61-89 | `A/ui/session/timeline/PartRenderer.kt:446-474`（`AttachmentChip`） | 近似 | Android 的 `file` part 只渲染一个 16 dp 图标 + 文件名的药丸（`layer02`、radius 6、`8/6` 内边距、12 sp），**完全不是**上游的 58×46 缩略图；图片预览（点击放大 `ImagePreview`）也没有 |
| 非图片附件卡（160px 宽、padding 8、radius 6、`overlay-hover` 底、文件名 11px/530 + 类型副标题 11px/440） | `U/session-ui/src/message/attachment-card.tsx:1-32`；`U/session-ui/src/message/attachment-card.css:1-65` | `A/ui/session/timeline/PartRenderer.kt:446-474` | 近似 | Android 是单行 chip（16 dp 图标 + 12 sp 文件名、`layer02`、radius 6），无 160 px 卡、无 11 px/530 文件名、无类型副标题、无 `inset 0 0 0 0.5px` 内描边 |
| reasoning part（`BasicTool` compact、`icon="mcp"`、streaming 标题 `Thinking` 闪烁 / 完成 `Thought`） | `U/session-ui/src/message/message-content.tsx:584-627`（标题 `:603-607`） | `A/ui/session/timeline/PartRenderer.kt:364-427` | 近似 | Android 是三处偏差：① 卡片是 `layer01` + 0.5dp `borderBase` + radius 6 的**有面卡片**（`:375-380`），上游 collapsible 是 `background: transparent; border: none`（`U/ui/src/components/collapsible.css:1-14`）且内容带 12 px 左缩进（`U/session-ui/src/components/basic-tool.css:314-318`）；② 标题恒为 `oc_thought`="Thought"（`PartRenderer.kt:392`），**没有 streaming 时的 `ui.sessionTurn.status.thinking`="Thinking" 切换**（`U/ui/src/i18n/en.ts:96`）；③ 无 `icon="mcp"` 图标槽、标题 14 sp/500 vs 上游 compact 13 px/530（`basic-tool.css:185-192`） |
| reasoning 副标题＝思考标题摘要（`reasoningHeading`） | `U/session-ui/src/message/message-content.tsx:572,608-619`；`U/session-ui/src/timeline/projection.ts:690-716` | — | 缺失 | 上游 streaming 且未展开时，副标题显示正文第一个 h1–h6 / setext / `**strong**` 的清洗后文本；Android 无 `reasoningHeading` 等价实现（搜 `A/ui/session/timeline/`：无 heading 提取） |
| reasoning 时长副标题（`12s` / `1m 5s`） | `U/session-ui/src/message/message-content.tsx:573-583,610-614`；`U/ui/src/i18n/en.ts:236-237` | — | 缺失 | 上游 reasoning 完成后副标题显示耗时；Android 无 |
| reasoning 默认折叠 | `U/session-ui/src/message/message-content.tsx:571`（`?? false`） | `A/ui/session/timeline/PartRenderer.kt:372`（`mutableStateOf(false)`） | 1:1 | 默认均折叠 |
| compaction（起止两条 `TimelineSeparator` + 摘要 markdown + running 工具行 + usage + 失败 Card） | `U/session-ui/src/message/message-content.tsx:400-473`；`U/ui/src/i18n/en.ts:104-112` | `A/ui/session/timeline/PartRenderer.kt:431-440` | 近似 | Android 只有一行静态 12 sp `textFaint` 文案 `oc_compaction`="Session compacted"，**没有** started/running/failed/cancelled/interrupted/providerCompaction 六种状态、没有 `{{input}} in · {{output}} out` usage、没有错误 Card。上游文案本身与 Android 的 `oc_compaction` 一致（`en.ts:107`） |
| tool part 分派与 `todowrite` 隐藏 | `U/session-ui/src/tools/tool-renderer.tsx:1197`（`todowrite` → `null`） | `A/ui/session/timeline/TimelineGrouping.kt:26`、`PartRenderer.kt:105` | 1:1 | 双方都不渲染 `todowrite` 工具卡 |
| tool part 未注册工具的兜底卡（`Called \`{{tool}}\``） | `U/session-ui/src/components/basic-tool.tsx:373-393`；`U/ui/src/i18n/en.ts:212` | `A/ui/session/timeline/PartRenderer.kt:94-124` | 近似 | Android 的 `when` 没有 `else`：**未知 part 类型整体不渲染**；未知工具名会被 `ToolPresentation.title` 首字母大写后当标题（`A/ui/session/timeline/ToolPresentation.kt:104-106`），不会拼成上游的 `` Called `x` `` |
| `question` 工具的已答回填卡（`[data-component="question-answers"]`） | `U/session-ui/src/tools/tool-renderer.tsx:2129-2165`；`U/session-ui/src/components/message-part.css:1242-1262` | — | 缺失 | Android 的 `HIDDEN_TOOLS` 只有 `todowrite`，`question` 会走通用工具卡；上游会渲染「问题→答案」清单并默认展开（`defaultOpen={completed()}`） |
| `patch` 工具（多文件 accordion + 每文件 diff + A/M/D 变更数） | `U/session-ui/src/tools/tool-renderer.tsx:2004-2110`；`U/session-ui/src/components/message-part.css:1297-1386` | — | 缺失 | Android 把 `patch`/`apply_patch` 当普通编辑类工具（`A/ui/session/timeline/ToolPresentation.kt:197-198`），不解析 `metadata.files`、没有多文件 accordion、没有 `apply-patch-change` 的 `+N/-N` 着色（css:1352-1368） |
| `write` 工具（正文 `mode="text"` 全文视图，不是 diff） | `U/session-ui/src/tools/tool-renderer.tsx:1965-2002`；`U/session-ui/src/components/message-part.css:491-510` | `A/ui/session/timeline/ToolPresentation.kt:175-183` | 近似 | Android 把 `write` 的 `content` 当成 `+` 全加 diff 渲染（`diffLines(old="", new=content)` → 每行前缀 `+ `），上游是纯文本代码视图，无 `+` 前缀 |
| `edit` 工具的 diff 体（Pierre diff、行号、hunk 折叠、语法高亮） | `U/session-ui/src/tools/tool-renderer.tsx:1931-1961`；`U/session-ui/src/components/message-part.css:472-489` | `A/ui/session/timeline/PartRenderer.kt:276-323` | 近似 | Android 只是把 `oldString`/`newString` 按行加 `- `/`+ ` 前缀后**单色文本**渲染（`ToolPresentation.kt:201-206`），无行号、无 hunk 分隔、无语法高亮、无 diff 背景色 |
| shell/bash 输出体（`bash-output`：0.5 px `border-base`、radius 6、`max-height:240px`、`bash-command`/`bash-result` 两段） | `U/session-ui/src/components/message-part.css:380-464`；`U/session-ui/src/tools/tool-renderer.tsx:1680-1760` | `A/ui/session/timeline/PartRenderer.kt:274-300` | 近似 | 上游 shell 用 `--v2-background-bg-base` 底、命令段与结果段之间 0.5 px 分隔线、结果段 `layer-01` + muted 文字、结果 `max-height:240px`；Android 是 `layer02` 底 + `$ cmd` + 空行 + 原文、`max-height: 220dp`、字号 12 sp（上游 13 px/`line-height-base`） |
| todo 列表 dock（`[data-component="todos"]`） | `U/session-ui/src/components/message-part.css:578-597`；`U/ui/src/i18n/en.ts`（`session.todo.progress`） | — | 缺失 | Android 完全没有 todo 渲染（`todowrite` 被隐藏后无替代视图）；上游还有一个 `padding: 10px 0 24px` 的 checkbox 列表 |
| 诊断信息（LSP diagnostics，`[data-component="diagnostics"]`） | `U/session-ui/src/components/message-part.css:726-765`；`U/session-ui/src/tools/tool-renderer.tsx:131-150` | — | 缺失 | Android 无 diagnostics 渲染（搜 `A/`：无 `diagnostic` 渲染） |
| 行分隔条 `TimelineSeparator`（左右 1 px `border-strong` + 13 px/440 中间标签，`h-8`） | `U/session-ui/src/components/timeline-separator.tsx:1-33` | — | 缺失 | 这是上游时间线**最常用的视觉元素**：模型切换、压缩起止、中断都靠它。Android 没有任何分隔条（搜 `A/ui/session/timeline/`：无 separator/divider） |
| 模型切换分隔条（`model.label` + provider 图标 + variant 圆点） | `U/session-ui/src/timeline/session-timeline-row.tsx:511`；`U/session-ui/src/components/timeline-separator.tsx:9-27` | — | 缺失 | 上游每次换模型都会插一条带 ProviderIcon 的分隔线；Android 无 |
| agent 切换提示（notice「Agent changed」+ `prev → next`） | `U/session-ui/src/timeline/session-timeline-row.tsx:308-315`；`U/ui/src/i18n/en.ts:231` | — | 缺失 | Android 无 `agent-switched` 概念（搜 `A/`：无 `agent-switched`） |
| 合成 notice（subagent/shell `finished`/`failed`/`cancelled`） | `U/session-ui/src/timeline/session-timeline-row.tsx:333-352`；`U/ui/src/i18n/en.ts:234-236` | — | 缺失 | Android 无 synthetic notice 行 |
| 「Instructions updated」notice | `U/session-ui/src/timeline/session-timeline-row.tsx:318-332`；`U/ui/src/i18n/en.ts:237` | — | 缺失 | Android 无 |
| 多文件变更提示组（`session-turn-diffs`：sticky 44 px 头 + `N Changed files` + Show all/Show less） | `U/session-ui/src/components/session-turn.css:137-206`；`U/ui/src/i18n/en.ts:60-66` | — | 缺失 | Android 无「本轮改了哪些文件」的汇总视图 |
| 中断分隔条（TurnDivider → `ui.message.interrupted`） | `U/session-ui/src/timeline/session-timeline-row.tsx:637-654`；`U/session-ui/src/timeline/projection.ts:351` | — | 缺失 | Android 无 turn 中断标记 |
| 未答 `question` 在时间线的隐藏规则（streaming/running 时不渲染） | `U/session-ui/src/tools/tool-renderer.tsx:1198,1217` | — | 缺失 | Android 无该门控（`question` 工具会照常渲染为通用卡） |

---

## 2. 工具卡细节

上游工具卡骨架：`U/session-ui/src/components/basic-tool.tsx:102-352`（`BasicTool`）+ `U/ui/src/components/collapsible.css:1-154`（`Collapsible`）+ `U/session-ui/src/components/basic-tool.css:1-201`（触发器排版）。
Android：`A/ui/session/timeline/PartRenderer.kt:173-300`（`ToolCard`/`ToolBody`）+ `A/ui/session/timeline/ToolCardPolicy.kt` + `A/ui/session/timeline/ToolPresentation.kt`。

| 上游组件/特性 | 上游证据(file:line) | Android 实现(file:line) | 状态 | 差异要点 |
|---|---|---|---|---|
| 折叠行高（`collapsible-trigger` 32 px；compact 28 px；`question`/错误卡 24 px） | `U/ui/src/components/collapsible.css:16-25`；`U/session-ui/src/components/basic-tool.css:173-174`；`U/session-ui/src/components/tool-error-card.css:73-75` | `A/ui/session/timeline/PartRenderer.kt:205-209` | 近似 | Android 用 `padding(horizontal=12.dp, vertical=8.dp)` 自适应高度：13/14 sp 标题 + 16 dp 图标 → 实际约 32–36 dp，**不是固定 32/28 dp**，且没有 compact/非 compact 两档 |
| 卡片外壳（无面：`background:transparent; border:none; border-radius: var(--radius-md)`，内容左缩进 12 px 作"导轨"） | `U/ui/src/components/collapsible.css:1-14`；`U/session-ui/src/components/basic-tool.css:314-318` | `A/ui/session/timeline/PartRenderer.kt:198-203` | 近似 | Android 给每张工具卡画了 `layer01` 填充 + 0.5 dp `borderBase` + radius 6 的**实心卡面**；上游只有 `task-tool-card` 和错误卡有面（`basic-tool.css:203-312`），普通工具不可见外壳。这是整个工具区观感差异最大的一条 |
| 标题字形（`basic-tool-tool-title` 13 px / `--font-weight-medium` 500 / `line-height-compact` 16 px） | `U/session-ui/src/components/basic-tool.css:84-102` | `A/ui/session/timeline/PartRenderer.kt:223-230` | 近似 | Android 用 **14 sp**/W500，比上游大 1 sp；行高未显式设置（跟随默认）。上游 compact 变体是 13 px/530（`basic-tool.css:185-192`） |
| 副标题字形（13 px / 400 / muted / 单行省略 / `tabular-nums`） | `U/session-ui/src/components/basic-tool.css:104-148` | `A/ui/session/timeline/PartRenderer.kt:234-243` | 近似 | Android 14 sp 单行省略、`textMuted`；缺 `font-variant-numeric: tabular-nums` |
| 参数槽 `basic-tool-tool-arg`（`k=v`，最多 3 个，13 px muted） | `U/session-ui/src/components/basic-tool.tsx:359-371`；`basic-tool.css:150-164` | — | 缺失 | Android 只取一个副标题（`ToolPresentation.kt:26-28` 的 `INPUT_KEYS` 首个非空值），没有 `args` 数组概念（如 `offset=10 limit=50`、`pattern=…`） |
| 标题/副标题间距（`info-main gap: 8px`，`align-items: baseline`） | `U/session-ui/src/components/basic-tool.css:76-82` | `A/ui/session/timeline/PartRenderer.kt:232-233` | 近似 | Android 用 `Spacer(8.dp)` + `CenterVertically`，上游是 `baseline` 对齐（中英文混排时基线位置会有 1–2 px 差） |
| 折叠箭头（`collapsible-arrow` 24×24，`opacity:0` → hover/expanded 时 1，`rotate(-90deg)` → `0deg`，`.15s ease`） | `U/ui/src/components/collapsible.css:27-44,70-88` | `A/ui/session/timeline/PartRenderer.kt:253-261` | 近似 | 旋转语义一致（`-90f`/`0f`）；但 Android 的 16 dp 箭头**常显**，上游默认 `opacity: 0`、hover 或展开才显示（触屏上等价，但静止态截图不同） |
| 箭头可见性门控（`hasChildren && !hideDetails && !locked && (!pending &#124;&#124; allowOpenWhilePending)`） | `U/session-ui/src/components/basic-tool.tsx:273-275` | `A/ui/session/timeline/ToolCardPolicy.kt:94-98` | 1:1 | 规则一致：无 body → 无箭头；pending/running 锁定，`shell` 例外（`basic-tool.tsx:591` reasoning 的 `allowOpenWhilePending` 除外） |
| pending/running 锁定展开（`handleOpenChange` 提前返回） | `U/session-ui/src/components/basic-tool.tsx:202-206` | `A/ui/session/timeline/ToolCardPolicy.kt:107-115` | 1:1 | 一致 |
| 默认展开策略（`shell` → settings；`edit/write/patch` → settings 且非纯删除；其余 → 折叠） | `U/app/src/session/timeline/controller.tsx:124-131,263-264`；`U/app/src/session/timeline/message-timeline.tsx:306-307` | `A/ui/session/timeline/ToolCardPolicy.kt:78-86` | 近似 | 公式一致，但**默认值不同**：上游默认 preset 是 `compact`（`U/app/src/settings/model.tsx:248` → `timelinePresets[2]`，`U/session-ui/src/timeline/detail.ts:41-49`：shell/edit **grouped + collapsed**），Android 的 `DEFAULT_SHELL_EXPANDED=false` 但 `DEFAULT_EDIT_EXPANDED=true`（`ToolCardPolicy.kt:33-36`）→ 默认状态下 Android 会把每个 edit 卡展开，上游把所有 edit 收进分组且折叠 |
| 展开/收起动画（`animated` 走 motion spring `visualDuration:0.35, bounce:0`；CSS collapsible 无过渡，靠 `data-expanded` 切 `overflow`） | `U/session-ui/src/components/basic-tool.tsx:63,175-196`；`U/ui/src/components/collapsible.css:90-101` | `A/ui/session/timeline/PartRenderer.kt:264-270`；`A/ui/theme/OcMotion.kt` | 近似 | Android 用 `expandVertically/shrinkVertically` + `OcMotion.expand`；上游是 spring 高度动画，缓动曲线与时长不同（Android 数值未在本次审计中逐项核对，标 `近似`） |
| 运行中标题 shimmer（`TextShimmer active={pending()}`） | `U/session-ui/src/components/basic-tool.tsx:227` | `A/ui/session/timeline/PartRenderer.kt:223-230` | 1:1 | 双方都在 running 时给标题加 shimmer（Android 还要求 `isTrailing`） |
| 运行中 16×16 spinner（`basic-tool-tool-spinner`，`color: text-faint`） | `U/session-ui/src/components/basic-tool.css:39-52` | `A/ui/session/timeline/PartRenderer.kt:247-252` | 1:1 | 尺寸 16 dp/16 px、颜色 `textFaint` 一致；形态（上游 `spinner` 组件 vs Android `SpinnerDots` 4×4 点阵）需真机比对，本次未像素级核对 |
| 工具内联图标（每工具自己的 icon，16 px，`flex-shrink:0`） | `U/session-ui/src/components/basic-tool.css:54-56`；`tool-renderer.tsx:1204-1214` 各工具 `icon=` | `A/ui/session/timeline/PartRenderer.kt:212-217`；`ToolPresentation.kt:110-119` | 近似 | Android 用自绘 8 个 drawable（`ic_oc_console/code/glasses/search/globe/task/check/file`）替代上游图标集；`task` 工具图标是 TASK 而非上游 `task-tool-card` 的 `subagent` |
| 状态色（running/error/completed 的 tint） | `U/session-ui/src/components/tool-error-card.css:89-91`（错误卡图标 `--card-accent`=`state-fg-danger`）；`U/session-ui/src/styles/theme.css` `--icon-weaker` | `A/ui/session/timeline/PartRenderer.kt:186-191` | 近似 | Android：running=`accent`、error=`stateDangerFg`、completed/pending=`iconMuted`。上游没有"运行中图标变蓝"的规则（图标恒为语义色，运行态由 shimmer+spinner 表达）→ Android 多了一条上游不存在的着色 |
| 错误卡外壳（`Card variant="error"`：透明底 + 左侧 2 px `--card-accent` 竖条 + `--card-pad-*: 0`） | `U/ui/src/components/card.tsx:47-56`；`U/ui/src/components/card.css:39-48`；`U/session-ui/src/components/tool-error-card.css:1-9` | `A/ui/session/timeline/PartRenderer.kt:198-203`；`A/ui/session/timeline/SessionErrorCard.kt:81-86` | 近似 | Android 用 `layer01` 卡面 + 0.5 dp 全描边表达错误卡，**没有左侧 2 px 竖条**（代码注释明确说"§3.1 判定无左竖条"，但 `card.css:39-48` 里 `::before` 确实画了 2 px 竖条 —— 该判断与上游 CSS 冲突） |
| 错误卡摘要 / 详情拆分（按 `: ` 切分，summary 首字母大写，detail 进 `card-description`） | `U/session-ui/src/components/tool-error-card.tsx:65-83,124,157` | `A/ui/session/timeline/ToolPresentation.kt:128-133` | 近似 | Android 只把错误文本的**第一行**当副标题，没有 summary/detail 两段结构、没有首字母大写规则 |
| 错误卡复制按钮（hover 显现，2 s「Copied」） | `U/session-ui/src/components/tool-error-card.tsx:136-155`；`tool-error-card.css:113-130` | — | 缺失 | Android 无复制错误入口 |
| 错误卡箭头（14×14、`margin-inline-start:6px`、**常显**） | `U/session-ui/src/components/tool-error-card.css:52-68` | `A/ui/session/timeline/PartRenderer.kt:253-259` | 近似 | Android 箭头 16 dp 且与其他卡共用「可展开才显示」规则；上游错误卡箭头 14 px 且恒显 |
| `subagent`/`task` 专用卡（`task-tool-card`：`layer02` 底、radius 8、`padding 8px 12px`、agent 色标题 13 px/530/20 px、运行中 `SessionProgressIndicatorV2`、可点击跳子会话 + `chevron-right`） | `U/session-ui/src/tools/tool-renderer.tsx:1611-1676`；`U/session-ui/src/components/basic-tool.css:320-389` | `A/ui/session/timeline/PartRenderer.kt:174-272` | 缺失 | Android 把 `task` 渲染成普通工具卡：没有 agent 主题色、没有独立卡面、没有跳转子会话、没有 `Delegating agent…` shimmer 态（`tool-renderer.tsx:1668-1674`） |
| `webfetch` 链接副标题（accent 色、无下划线、hover 才下划线 + 16 px 图标） | `U/session-ui/src/components/basic-tool.css:391-461` | `A/ui/session/timeline/PartRenderer.kt:234-243` | 近似 | Android 把 URL 当普通 muted 文本，不可点击、无 accent 色、无 hover 下划线 |
| 工具输出体排版（`tool-output` `white-space: pre`；`bash-output` 13 px mono / `line-height-large`，shell 变体 `font-weight:440` / `letter-spacing:-0.04px` / `line-height-base`） | `U/session-ui/src/components/message-part.css:323-366,420-463` | `A/ui/session/timeline/PartRenderer.kt:292-298` | 近似 | Android 12 sp 等宽 / 16 sp 行高 / `layer02` 底 / radius 6 / `max-height 220dp`；上游 13 px / 20 px（shell 变体）/ `bg-base` 底 + `layer-01` 结果段 / `max-height 240px`。字号 −1 sp、高度 −20 dp |
| diff 的 `+`/`-` 着色 | 上游用 Pierre diff（背景着色，`--icon-diff-add-base`/`--icon-diff-delete-base`）`U/session-ui/src/components/session-diff.ts:1-60` | `A/ui/session/timeline/PartRenderer.kt:303-323`；`A/res/values/colors_oc.xml:28-29` | 近似 | Android 把**整行文字**染成 `oc_diff_add_fg`/`oc_diff_del_fg`，且没有 `+`/`-` 符号的独立颜色。色值：add `#3A8437`/`#9BCD97` 与上游 `#3a8437`/`#9bcd97` **1:1**；delete `#CA2D17` 命中的是上游 **hover** 值（base 是 `#ed4831`，`theme.css:310`），dark `#FAA494` 在 `_upstream/**` 中**无任何对应 token**（`theme.css:581` 是 `#fc533a`） |
| 展开体高度上限 | `U/session-ui/src/components/message-part.css:351-365`（`max-height: 240px`，隐藏滚动条） | `A/ui/session/timeline/PartRenderer.kt:286,493`（`TOOL_BODY_MAX_HEIGHT = 220.dp`） | 近似 | 240 px → 220 dp，未按 1:1 换算 |
| 展开体内边距（`bash-pre padding:12px`；`tool-output padding:0`） | `U/session-ui/src/components/message-part.css:325,420-423` | `A/ui/session/timeline/PartRenderer.kt:285-290` | 近似 | Android 外层 `start/end 12dp bottom 8dp` + 内层 `padding 8dp` + 自带 `layer02` 背景；上游外层无内边距、内层 12 px |
| 工具 JSON 输入美化 + 语法着色 | `U/ui/src/...`（上游无此实现；输入通过各工具 renderer 表达） | `A/ui/session/timeline/PartRenderer.kt:315-348`；`ToolPresentation.kt:225-229` | 近似 | Android 自创了 JSON pretty + key/value 双色高亮（`appendJsonHighlighted`）；上游没有"把 tool input 打印成 JSON"的通用体，这是 Android 的额外实现（不算缺失，但也不是上游行为） |

---

## 3. 时间线容器

上游容器：`U/app/src/session/timeline/virtualizer.tsx`（TanStack 虚拟列表）+ `U/app/src/session/timeline/message-timeline.tsx` + `U/session-ui/src/components/session-turn.css`。
Android 容器：`A/ui/session/timeline/MessageTimeline.kt` + `A/ui/session/SessionScreen.kt`。

| 上游组件/特性 | 上游证据(file:line) | Android 实现(file:line) | 状态 | 差异要点 |
|---|---|---|---|---|
| 会话（turn）间距 24 px | `U/session-ui/src/components/session-turn.css:280-282` | `A/ui/session/timeline/MessageTimeline.kt:292,118` | 1:1 | 24 px → 24 dp |
| assistant 内部件间距 12 px | `U/session-ui/src/components/session-turn.css:128-135` | `A/ui/session/timeline/MessageTimeline.kt:295,212` | 1:1 | 12 px → 12 dp |
| 行水平内边距 `px-4`（移动）/ `md:px-5`（≥768 px） | `U/app/src/session/timeline/message-timeline.tsx:185` | `A/ui/session/timeline/MessageTimeline.kt:69`（16 dp） | 1:1 | 移动端 16 px → 16 dp 一致；桌面 20 px 分支不适用 |
| 内容居中 `md:max-w-[1000px] md:mx-auto` | `U/app/src/session/timeline/message-timeline.tsx:381`；`virtualizer.tsx:363` | — | 近似 | Android 恒全宽。属桌面（≥768 px）专属，移动端无差异 |
| 列表底部内边距（虚拟列表 `paddingEnd: 64`） | `U/app/src/session/timeline/virtualizer.tsx:250` | `A/ui/session/SessionScreen.kt:250-255`（`composerHeight + 12dp`） | 近似 | 上游固定 64 px；Android 用实测 composer 高度 + 12 dp（更精确，但数值不同） |
| 贴底阈值 `scrollEndThreshold: 80` | `U/app/src/session/timeline/virtualizer.tsx:248` | `A/ui/session/timeline/MessageTimeline.kt:79,289` | 1:1 | 80 px → 80 dp |
| `anchorTo: "end"` + `followOnAppend`（pinned 时） | `U/app/src/session/timeline/virtualizer.tsx:242-246` | `A/ui/session/timeline/MessageTimeline.kt:80-111`（`reverseLayout=true` + `spendFingerprint` 追底） | 近似 | 行为等价（都在贴底时自动跟随新内容），实现不同：上游用虚拟器 pin/unpin 状态机 + `anchorTo`，Android 用 `SideEffect` 读 pre-append 布局 + `LaunchedEffect(appendKey)` |
| 「跳到最新」浮动按钮（32×28 px、radius 8、`bottom-8`、水平居中、`color-mix(bg-base 92%)` + `backdrop-blur(2px)`、`200ms ease-out` scale/translate 进出、仅 `overflow && jump` 时出现） | `U/app/src/session/timeline/virtualizer.tsx:646-673`；`U/app/src/runtime/i18n/en.ts:811`（"Jump to latest"） | — | 缺失 | Android **没有**滚动到底部按钮（`A/ui/components/OcScrollbar.kt` 是拖拽滚动条，不是回到底部）。用户向上翻历史后新消息到达，无任何"回到最新"的提示与入口 |
| 「Working」底部状态行（`flex h-9 pt-3 gap-2`、13 px/530 shimmer） | `U/app/src/session/timeline/message-timeline.tsx:376-391`；`U/app/src/runtime/i18n/en.ts:781` | — | 缺失 | Android 时间线底部没有「Working」占位行。上游在「已发出但还没有可见工具/思考行」时用这一行表达"正在工作"（判定逻辑 `message-timeline.tsx:331-372`） |
| 空态（无消息） | 上游：会话无消息时时间线无行，`session.new.title`="Build anything" 只属于 **新建会话页**（`U/app/src/runtime/i18n/en.ts:834`） | `A/ui/session/SessionScreen.kt:299-306`；`A/res/values/strings.xml`（`oc_timeline_empty`="Build anything"） | 近似 | Android 在有会话 ID 的空会话里居中显示 13 sp `textFaint` 的 "Build anything"；上游该文案是空白状态页的**大标题**，不挂在会话时间线上。文案相同、挂载位置与排版不同 |
| 会话错误卡（`Card variant="error"`：单行 warning 图标 + 文本，`:` 前加粗；`max-height:240px`、`white-space:pre-wrap`、自动换行、溢出滚动） | `U/session-ui/src/components/session-error.tsx:14-33`；`U/session-ui/src/components/session-turn.css:77-84` | `A/ui/session/timeline/SessionErrorCard.kt:66-158` | 近似 | Android **自创**了可折叠结构：16 dp 图标 + 14 sp/500 标题 `oc_error_title`="Something went wrong" + 一行摘要 + 箭头，展开后是 12 sp 等宽红字正文装在 `layer02` 盒里。上游没有标题、没有折叠箭头、没有等宽正文、没有内层盒子；`:` 前缀加粗规则 Android 也没有。相同点：`max-height` 240 dp（上游 240 px）与危险色图标 |
| 重试卡（`session-retry-card`：warning 图标 + 截断到 80 字符的报错 + 每秒刷新的 `retrying in Ns - attempt #k` shimmer） | `U/session-ui/src/components/session-retry.tsx:10-74`；`U/session-ui/src/components/session-turn.css:92-116` | — | 缺失 | Android 无重试卡片（`ChatStore.RunStatus` 有 `retry` 语义，`A/ChatStore.kt:367-370`，但没有对应的 UI 呈现） |
| 粘性会话标题头（`sticky top-0 z-30`，`bg-[linear-gradient(to_bottom,bg-base_48px,transparent)]`，`pb-4 pe-3 ps-2.5`） | `U/app/src/session/session-identity-header.tsx:27-36`；`U/app/src/session/timeline/virtualizer.tsx:686`（`--sticky-accordion-top: 48px`） | `A/ui/session/timeline/MessageTimeline.kt:138-150`；`A/ui/session/header/SessionHeader.kt:110,368` | 近似 | 双方都在顶部钉一行 48 px 的头。**差异**：上游头是 48 px 实心 + 向下渐隐到透明的渐变（内容会滚到渐变下），且 `padding-inline: 12/10 px`、`padding-bottom: 16px`；Android 用**不透明** `bgBase` 盒把列表**内缩**避让（`MessageTimeline.kt:116` `padding(top = headerInset)`），所以内容不会从头部下面穿过，也没有 48 px 渐隐 |
| 逐条消息的粘性时间戳 | 上游**不存在**：时间线里唯一的 sticky 元素是会话标题头（`session-identity-header.tsx:31`）与 diff/accordion 头（`session-turn.css:148`、`message-part.css:1286-1294`） | 同样不存在 | 1:1 | 双方都没有「滚动时吸附的时间戳」。核对了 `U/session-ui/src/**` 与 `U/app/src/session/**` 的全部 `sticky` 命中（共 11 处），无逐条时间戳实现 |
| 消息导航轨（`message-nav` compact：`message-nav-tick-line` 刻度条 + hover 卡片列出每条用户消息 + diff bars） | `U/session-ui/src/components/message-nav.tsx:6-151`；`U/session-ui/src/components/message-nav.css` | — | 缺失 | Android 无消息导航轨（搜 `A/`：无 `messageNav`） |
| 时间线搜索（`TimelineSearchBar` + `search-controller`） | `U/app/src/session/screen.tsx:34,275`；`U/app/src/session/timeline/search-bar.tsx`；`U/app/src/session/timeline/search-bar.css` | — | 缺失 | Android 无会话内搜索（`A/ui/palette/CommandPalette.kt` 是全局命令面板，不是时间线内搜索） |
| 加载更早消息（`session.messages.loadEarlier` / `renderEarlier` / `loadingEarlier`） | `U/app/src/runtime/i18n/en.ts:807-810`（key 存在） | — | 1:1 | 该组 i18n key 在 `U/app/src/**/*.tsx` 中**无消费者**（已 grep 确认），上游实际未渲染"加载更早"入口；Android 也没有 → 实际无差异 |
| 历史加载 / 虚拟化 | `U/app/src/session/timeline/virtualizer.tsx`（TanStack `useVirtualizer` + `measureElement` + 分页 `rangeExtractor`） | `A/ui/session/timeline/MessageTimeline.kt:114-137`（`LazyColumn` + `reverseLayout`） | 近似 | 都做了行级虚拟化；上游额外有冷启动尺寸批处理、滚动补偿、`canRenderImmediately` 快速路径（`message-timeline.tsx:206-238`），Android 无对应机制 |
| 滚动条 | `U/session-ui/src/components/session-turn.css:16-21`（`scrollbar-width: none`，全隐藏） | `A/ui/session/timeline/MessageTimeline.kt:154-162`；`A/ui/components/OcScrollbar.kt` | 近似 | 上游**隐藏**滚动条；Android 额外自绘了一个 12 dp 命中条 + 3 dp 药丸的 overlay 滚动条（Android 的额外实现，非上游行为） |
| 自动滚动跟随的实现细节（贴底判定 + 冷启动跳转阈值） | `U/app/src/session/timeline/virtualizer.tsx:300-400` | `A/ui/session/timeline/MessageTimeline.kt:100-111,301`（`JUMP_SCROLL_ITEMS = 20`） | 近似 | Android 用 `firstVisibleItemIndex > 20` 决定"跳"还是"动画"；上游用像素级 `endEpsilon` 判定 + `scrollToEnd()`。行为相近，边界条件不同 |
| 时间线内边距（turn 容器 `session-turn-message-container` gap 0） | `U/session-ui/src/components/session-turn.css:23-31` | `A/ui/session/timeline/MessageTimeline.kt:210-213` | 近似 | 上游 turn 容器 gap 0（由 `data-timeline-spacing` 的 `pt-2`/`pt-4` 表达件间距，`session-timeline-row.tsx:361-366`）；Android 在 turn 内统一 `spacedBy(12.dp)`，没有 `pt-2`(8 px)/`pt-4`(16 px) 两档 |

---

## 4. Composer

上游：`U/app/src/composer/composer.tsx`（模型控件）+ `U/app/src/composer/editor/editor.tsx`（编辑器主体）+ `U/app/src/composer/suggestions/machine.ts`（`@`/`/` 触发机）+ `U/app/src/composer/attachments/*` + `U/app/src/session/composer/*`。
Android：`A/ui/session/composer/`。

| 上游组件/特性 | 上游证据(file:line) | Android 实现(file:line) | 状态 | 差异要点 |
|---|---|---|---|---|
| 输入卡片容器（`min-h-[96px] rounded-xl bg-base`；`!borderUnderlay` 时 `elevation-raised`；dark 覆盖为 `layer-01`） | `U/app/src/composer/editor/editor.tsx:136-142`；`U/ui/src/components/dock-surface.css:10-15`；`U/app/src/composer/editor/editor.css:33-37` | `A/ui/session/composer/Composer.kt:184-189` | 近似 | `shapes.xl`=10 dp 对 `--radius-xl`=10 px，1:1；dark 底色 `layer01` 一致。但 Android 描边用 `borderBase`（`#ffffff1a`），上游 `borderUnderlay` 是 `0 0 0 0.5px bg-base` 的实心环；且 Android 无 `elevation-raised`。`A/ui/home/NewSessionScreen.kt:166` 复用同一 Composer，也带描边（上游 new-session 不走 borderUnderlay，走阴影） |
| 编辑器内边距/字号（`px-4 pt-4 pb-2 text-[13px] font-[440] leading-5`） | `U/app/src/composer/editor/editor.tsx:191` | `A/ui/session/composer/Composer.kt:203-204,249` | 近似 | 16/16/16/8 dp 与 13 sp/20 sp 1:1；**缺 `font-weight:440`**（Android 未设 fontWeight → W400） |
| 编辑器高度 min/max（`min-h-[60px] max-h-[180px]`，超出内部滚动） | `U/app/src/composer/editor/editor.tsx:169` | `A/ui/session/composer/Composer.kt:203,337-341` | 1:1 | 60/180 dp 1:1 |
| 工具栏行（`h-11 px-2`） | `U/app/src/composer/editor/editor.tsx:250` | `A/ui/session/composer/Composer.kt:271-275,344` | 1:1 | 44 dp / 8 dp 1:1 |
| dock 容器内边距（`pb-3`、`px-3`、`md:max-w-[1000px]`） | `U/app/src/session/composer/session-composer-region.tsx:28-34` | `A/ui/session/SessionScreen.kt:307-313` | 近似 | 左右 12 dp 一致；底部 Android 8 dp vs 上游 12 px；Android 额外 top 4 dp；无 1000 px 居中（手机端不适用） |
| 占位文案（普通） | `U/app/src/runtime/i18n/en.ts:154`；`U/app/src/composer/editor/editor.tsx:245` | `A/ui/session/composer/Composer.kt:258-264`；`A/res/values/strings.xml:56` | 近似 | 文案逐字一致，唯结尾 Android 用 `...`（3 个 ASCII 句点）vs 上游 `…`（U+2026）。Android 走硬编码 `oc_composer_placeholder`，已存在的 `ui_promptInput_placeholder_normal`（`A/res/values/strings_oc_i18n.xml:133`）未使用 |
| 运行中 follow-up 占位（`Add follow-up, / for commands, @ for context…`） | `U/app/src/composer/placeholder.ts:7`；`U/app/src/composer/model.ts:91-96` | — | 缺失 | Android 只有单一占位串，无 followUp 分支 |
| shell 模式与占位（`!` 触发、`Enter shell command…`、font-mono） | `U/app/src/composer/placeholder.ts:6`；`U/app/src/composer/editor/editor.tsx:192-196,243-244` | — | 缺失 | Android 无 `mode` 状态、无 shell 模式（搜 `A/ui/session/composer/` 与 `SessionScreen.kt`：无 `mode`） |
| 输入区自动聚焦（任意单字符键聚焦并恢复光标） | `U/app/src/session/composer/region.tsx:145-149` | — | 缺失 | Android composer 内无 `FocusRequester`（搜 `A/ui/`），必须手动点输入框 |
| 附件入口「+」 | `U/app/src/composer/editor/editor.tsx:257-270` | `A/ui/session/composer/Composer.kt:280-293`；`AttachmentPicker.kt:48-75` | 近似 | 几何 1:1（28 dp、radius 6 dp、16 dp 图标）；但 Android 直接开 SAF `OpenMultipleDocuments("*/*")`，无菜单；`contentDescription` 用 `chat_attach`="Add file or image" vs 上游 `ui.promptInput.add`="Add images and files"（`U/ui/src/i18n/en.ts:156`） |
| 「+」展开的四项菜单（Attach / Commands `/` / Context `@` / Shell `!`，`min-width:180px`） | `U/app/src/composer/editor/editor.tsx:653-665` | — | 缺失 | Android 「+」无菜单 |
| 拖放 dropzone（模糊 + 条纹 + 40 px 圆形上传图标 + 15 px/530 文案） | `U/app/src/composer/dropzone.tsx:26-86`；`U/app/src/composer/attachments/attachments.ts:162-193` | — | 缺失 | 移动端无拖放，属**桌面专属**（移动端不适用） |
| 粘贴上传 / 大段文本粘贴阈值（≥8000 字符或 ≥120 换行转为文本部件） | `U/app/src/composer/attachments/attachments.ts:127-161,274-277` | — | 缺失 | Android 走 `BasicTextField` 默认粘贴，无阈值逻辑。移动端粘贴图片是常见操作，此条**移动端也适用** |
| 附件类型/大小策略（图片白名单 png/jpeg/gif/webp+pdf；`MAX_INLINE_BYTES`=20 MB，超出改走路径/上传） | `U/app/src/composer/attachments/attachments.ts:214-222` | `A/ui/session/composer/AttachmentPicker.kt:74`；`AttachmentPolicy.kt:21-24` | 近似 | Android `*/*` 全类型 + 8 MB/个 + 最多 6 个（6 个上限来自旧 View 壳，上游无数量上限）；无 20 MB 内联阈值、无"超限改走路径/上传"分支 |
| 上传进度与取消（ProgressCircle + 百分比 + 16×16 取消钮；4 px 轨道、`layer-02` 底、`icon-base` 条） | `U/app/src/composer/editor/editor.tsx:577-598`；`U/app/src/composer/attachments/attachments.css:19-97` | — | 缺失 | Android 先读完字节再入列，无进行中态、无取消 |
| 图片缩略图（`w-[58px] h-[46px] rounded-[6px]` + `inset 0 0 0 0.5px border-base`） | `U/app/src/composer/editor/editor.tsx:554-560` | `A/ui/session/composer/AttachmentPreviewRow.kt:131-151,249-253` | 1:1 | 58×46、radius 6 dp、0.5 dp 内描边 1:1 |
| 非图片附件卡（160 px 宽、padding 8、radius 6、`overlay-hover` 底、11 px/530 文件名 + 11 px/440 类型副标题） | `U/app/src/composer/editor/editor.tsx:541-547`；`U/session-ui/src/message/attachment-card.css:6-12,44-52` | `A/ui/session/composer/AttachmentPreviewRow.kt:156-182,262` | 近似 | Android 是单行 `layer02` chip：46 dp 高、14 dp 图标 + 12 sp 文件名、max 120 dp；上游是 160 px 两行卡、`overlay-hover` 底、11 px 字级 |
| 移除徽标（`size-4`、`-top-1 -end-1`、hover 才显、`icon-muted` 底 + `outline-xmark`） | `U/app/src/composer/editor/editor.tsx:566-573` | `A/ui/session/composer/AttachmentPreviewRow.kt:186-206,256-259` | 近似 | 尺寸/位置 1:1（16 dp，外扩 4 dp）；Android 常显（触屏合理），字形是文本 `×` 10 sp |
| 附件行几何（`gap-2 px-2 pt-2 pb-1`） | `U/app/src/composer/editor/editor.tsx:500-503` | `A/ui/session/composer/AttachmentPreviewRow.kt:75-81` | 1:1 | 8/8/8/4 + gap 8 dp 1:1 |
| 附件行两端渐隐遮罩（24 px 渐变） | `U/app/src/composer/editor/editor.tsx:600-607`；`attachments.css:99-123` | — | 缺失 | Android 未实现（`AttachmentPreviewRow.kt:64-67` 自述） |
| 评论 / 上下文附件（CommentCard + 移除） | `U/app/src/composer/editor/editor.tsx:505-532`；`U/app/src/composer/comments.tsx` | — | 缺失 | Android 无评论附件概念 |
| 附件点击预览（`ImagePreview` 弹窗） | `U/app/src/composer/model.ts:325-330` | — | 缺失 | Android 缩略图不可点击 |
| `@` 触发规则（`(?:^&#124;\s)@([^\s@]*)$`） | `U/app/src/composer/suggestions/machine.ts:98-105` | `A/ui/session/composer/MentionText.kt:56-63`；`Composer.kt:199` | 近似 | Android 取 caret 前最后一个 `@` 且只拒空白 → `foo@bar`、`a@@b` 在 Android 会弹层，上游不会 |
| `@` 补全插入（替换触发串为 `label + 空格`，光标移到末尾） | `machine.ts:171-191,244-247` | `MentionText.kt:73-80` | 1:1 | 行为一致 |
| 候选来源（file/agent/skill/command/reference 多类） | `U/app/src/composer/editor/editor.tsx:905-914`；`U/app/src/composer/model.ts:314-321` | `A/ui/session/composer/MentionText.kt:115-122`；`Composer.kt:125-141` | 近似 | Android 全部映射为 `Kind.FILE`（只调 `/find/file`），无 agent/skill/command/reference 类候选；候选上限 20（`MentionText.kt:20`），上游无截断 |
| 候选行图标与快捷键右列 | `editor.tsx:906-914,813-815` | `MentionPopover.kt:101-106,130-133` | 近似 | Android agent 用 `ic_oc_task` 而非 brain；候选恒为 FILE 故 agent 分支不可达；无 keybind 右列 |
| 弹层几何（`max-h-80`、`p-2`、`-top-2`、`rounded-xl`、`bg-base`、`elevation-raised`、`z-40`） | `editor.tsx:768-771` | `A/ui/session/composer/MentionPopover.kt:54,56-70,136-142` | 近似 | 320 dp / 8 dp / 8 dp 1:1；radius Android 硬编码 12 dp（上游 10 px）；底色 `layer01`（dark `#242424`）vs 上游 `bg-base`（dark `#161616`） |
| 弹层行几何（`rounded-md px-2 py-1` → 28 px） | `editor.tsx:799` | `MentionPopover.kt:91-98,144-145` | 近似 | radius 6 dp、横向 8 dp 一致；Android 固定行高 32 dp（触摸目标），上游 28 px |
| 弹层空态（`No matching items`）与 `/` 命令搜索框 | `editor.tsx:791`；`U/ui/src/i18n/en.ts:143`；`editor.tsx:773-788` | — | 缺失 | 空候选时 Android 直接关闭弹层；无空态文案、无顶部搜索框 |
| 弹层键盘（Esc / Tab / Enter / ↑↓ / Ctrl+N·P / Ctrl+G） | `machine.ts:208-229` | `Composer.kt:209-234` | 近似 | Android 仅有 ↑↓/Enter/Esc；无 Tab 选择、无 Ctrl 组合 |
| 激活行底色（dark 用 `--v2-alpha-light-10`） | `U/app/src/composer/editor/editor.css:39-41` | `MentionPopover.kt:96` | 近似 | Android 恒用 `overlayHover`（`#ffffff0f`），未按 dark/light 切换 |
| mention 着色（file→`--syntax-property`、agent→`--syntax-type`、reference→`--syntax-keyword`） | `editor.tsx:191`；`U/ui/src/styles/theme.css:320-321,591-592` | `A/ui/session/composer/MentionColors.kt:21-25,45-49`；`A/ui/theme/OcTheme.kt:141-143,198-200` | 近似 | 映射关系正确；light 取值 1:1（`#ed6dc8`/`#596600`）；**dark 取值错误**：Android `#fab283`/`#fcd53a`/`#edb2f1` 对应的是 `U/ui/src/theme/themes/oc-2.json:261-265` 的 **palette primary/warning/info（品牌/状态角色）**，而不是任何 `syntax-*` token（上游 dark syntax 是 `theme.css:586-592`，oc-2 主题的 syntax override 在 `oc-2.json:287-292`） |
| mention 原子性（渲染为 `contenteditable=false` span，带 `data-mention/path/mime`） | `editor.tsx:353-387` | `MentionColors.kt:45-49` | 近似 | Android 只是 `VisualTransformation` 上色，token 仍是普通可编辑文本，无不可分割/结构化 part |
| 斜杠命令弹层（`^/(\S*)$` 触发 `command-inline`） | `machine.ts:107-114`；`editor.tsx:116-135` | — | 缺失 | Android composer **没有 `/` 触发**（`MentionText.kt:14` 只定义 `TRIGGER='@'`）。占位文案却写着 "/ for commands"，属于承诺了但未实现 |
| 已注册的斜杠命令（`/model`、`/agent`） | `U/app/src/composer/commands.tsx:49-82` | — | 缺失 | Android 命令面板 `A/ui/palette/AppCommands.kt:22-67` 只有设置/会话/工作区/终端/Review，且不在 composer 内触发 |
| 命令执行路径（`/name` → `session.api.command`） | `U/app/src/composer/submit.ts:300-327` | — | 缺失 | Android 无 `/command` 发送分支 |
| 发送按钮几何（`size-7 rounded-md p-[6px]`） | `editor.tsx:889` | `A/ui/session/composer/Composer.kt:310-331,347` | 1:1 | 28 dp、radius 6 dp、16 dp 图标 1:1 |
| 发送/停止图标（`arrow-up` 填充；`stop` = `<rect x=5 y=5 w=10 h=10>` viewBox 20；shell 时 `arrow-undo-down`） | `editor.tsx:887`；`U/ui/src/assets/icons/additional-icons.ts:3,55` | `Composer.kt:321-330`；`A/res/drawable/ic_oc_send.xml:6`、`ic_oc_stop.xml:6` | 近似 | Android 无 shell 的第三态；send 是 2 px 描边路径（上游 evenodd 填充）；stop 同为 10×10 方块但 viewBox 24 → 占比 42 % vs 上游 50 %，视觉偏小 |
| 发送按钮配色（`variant="submit"`：底 `--v2-background-bg-icon-button-contrast` = light `#2e2e2e` / dark `#dbdbdb`，叠垂直高光渐变 + `elevation-button-contrast` 阴影；dark 图标 `icon-inverse`） | `U/ui/src/actions/submit.css:6-34`；`U/ui/src/styles/tokens/theme.css:14,397` | `A/ui/session/composer/Composer.kt:315,328` | 近似 | Android 是**扁平 accent 蓝**（`#3b5cf6`）+ 白色图标，无渐变、无阴影。上游是"对比色反转按钮"：light 深灰底白图标、**dark 浅灰底深图标** —— 色相与明暗关系完全相反，是 composer 区最显眼的差异 |
| 禁用态透明度（`opacity: 0.4`） | `U/ui/src/actions/submit.css:71-73` | `Composer.kt:313,349`（0.5f） | 近似 | 0.5 vs 0.4 |
| stopping 判定（`working() && blank()`） | `U/app/src/composer/model.ts:83-90` | `A/ui/session/composer/ComposerSubmit.kt:13-24`；`Composer.kt:114-117` | 1:1 | 规则一致（无文本无附件 = 显示停止） |
| 空白提交即中断 | `U/app/src/composer/submit.ts:64-67` | `ComposerSubmit.kt:23-24`；`Composer.kt:316-318` | 近似 | 结论一致，机制不同（上游"提交空内容→interrupt"，Android 按钮直接切 stop） |
| 中断请求体（`interrupt({sessionID, resume:true})`） | `U/app/src/session/composer/adapter.ts:38-40` | `A/ChatViewModel.kt:427-437`；`A/OpenCodeClient.kt:672-673` | 近似 | 端点一致（`POST /session/:id/abort`）；Android 发空 body，**缺 `resume: true`** |
| 发送失败回填草稿（`restoreSubmission` 回填文本/上下文/光标） | `U/app/src/composer/submit.ts:239-294` | `Composer.kt:159-167`；`A/ChatViewModel.kt:411-424` | 缺失 | Android 提交后**立即清空**（`Composer.kt:163`），失败不回填 → 用户的输入会丢 |
| 模型 chip 几何/图标（ProviderIcon 16 px opacity-40、`max-w-[220px]`、height 28 px、`chevron-down`、ghost-muted、字重 440） | `U/app/src/composer/composer.tsx:63-72,98,114-115` | `A/ui/session/composer/ModelChip.kt:101-140,199-203` | 近似 | 28 dp、max 220 dp、13 sp、`textMuted` 一致；Android 用通用 `ic_oc_model` 而非按 provider 的 `ProviderIcon`；chevron 14 dp `textFaint` |
| chip 回退文案 | `U/app/src/composer/composer.tsx:39`（`dialog.model.select.title`="Select model"） | `ModelChip.kt:98`；`strings.xml:13`（`oc_model_label`="Model"） | 近似 | Android 回退到 "Model"，上游回退到 "Select model" |
| picker 面板外壳（`w-[284px] rounded-md bg-layer-01 !p-0 elevation-floating border-0`） | `U/app/src/providers/models/select-dialog.tsx:387` | `ModelChip.kt:166-178,205-207` | 近似 | 圆角 6 dp、`layer01`、floating 一致；宽度 Android 随内容 `160..368 dp`，非固定 284 px；Android 多一条 0.5 dp `borderBase` 描边 |
| picker 搜索行（`h-7`、`pl-3 pr-2.5`、放大镜、清空、↑↓/Enter/Esc、自动 focus） | `select-dialog.tsx:393-431` | `ModelPicker.kt:159-210,331` | 近似 | Android 行高 36 dp（非 28 px）、放大镜 14 dp、清空 20 dp；**无键盘导航、无自动聚焦** |
| picker 列表高度（`max-h-[220px]`） | `select-dialog.tsx:446` | `ModelPicker.kt:222-225,333-334`（280 dp） | 近似 | 220 px → 280 dp，未 1:1 |
| picker 行与分组标签（行 h 28 px / radius 4 / padding `0 12px`；组标签 h 28 px、11 px、weight 530） | `select-dialog.tsx:459-461,479-499`；`U/ui/src/navigation/menu/menu.css:57-61,170-183` | `ModelPicker.kt:228-237,288-317,336` | 近似 | 组标签 11 sp/`textFaint`/左右 12 dp 一致，但无 530 字重、行高非固定 28 dp；模型行 Android 32 dp、横向 padding 8 dp（上游 28 px / 12 px / radius 4 px） |
| free/latest 徽标与模型 tooltip（`model.tag.free`/`model.tag.latest`、上下文上限、reasoning 支持） | `select-dialog.tsx:185-206,492-497`；`U/app/src/runtime/i18n/en.ts`（`model.tooltip.*`） | — | 缺失 | Android picker 无 Badge、无模型参数 tooltip |
| picker 底部动作（「Manage models」+ sliders 图标） | `select-dialog.tsx:510-522`；`U/app/src/runtime/i18n/en.ts`（`dialog.model.manage`） | `ModelPicker.kt:255-282`；`strings.xml:23` | 近似 | Android 该项**是自造的**「使用服务器默认 / Use server default」——上游 popover 里没有这个按钮（上游默认模型由 `selection.tsx:169-178` 的 fallback 链隐式决定）；同时 Android 完全没有 Manage models 入口 |
| 模型/变体数据结构（`provider.id:model.id` + `variant`） | `select-dialog.tsx:33`；`U/app/src/composer/submit.ts:230-234`；`editor.tsx:295-306` | `ModelPicker.kt:122-125`；`A/ChatViewModel.kt:390-397` | 近似 | Android 只有 `(providerID, modelID)` + override/serverDefault 二分，**无 variant（思考强度）概念与选择器** |
| 模型默认语义（`firstModel(configuredModel, recentModel, defaultModel)`） | `U/app/src/providers/models/selection.tsx:169-178` | `A/ChatViewModel.kt:390-397`；`ModelPicker.kt:117-125` | 近似 | Android 简化为「显式 override ?: 服务器 config 默认」，无 recent 兜底链 |
| Enter / Shift+Enter（Enter 提交、Shift+Enter 换行、丢弃 `event.repeat`） | `U/app/src/composer/editor/editor.tsx:211-215` | `Composer.kt:236-248,254-255`；`ComposerSubmit.kt:34-35` | 1:1 | 规则完全一致；Android 额外把软键盘 `ImeAction.Send` 映射为提交（移动端合理增强） |
| mod+Enter 备用投递（Steer/Queue 按钮 + `steerHint`） | `editor.tsx:214,828-865` | — | 缺失 | Android 无 mod 分支、无 Steer/Queue 语义 |
| 其它编辑器快捷键（Cmd/Ctrl+↑ 编辑队首、mod+U 附件、Ctrl+G 停止） | `editor.tsx:207-210`；`U/app/src/composer/editor/interaction.ts:198-202,227` | — | 缺失 | 桌面专属（移动端不适用） |
| Escape 停止 | `U/app/src/composer/editor/interaction.ts:225-233` | `Composer.kt:227-231` | 近似 | Android 的 Esc 只在 mention 弹层打开时关层，运行中按 Esc 不停止 |
| 历史导航与 Tab 补全（↑↓ 翻历史、持久化 prompt 历史） | `interaction.ts:234-243,267-298`；`U/app/src/composer/history/store.ts:47-58`；`machine.ts:211` | — | 缺失 | Android composer 无历史栈、无 Tab 处理 |
| 队列面板（行数徽标、4 行 148 px 上限、拖拽排序） | `U/app/src/session/composer/queue-panel.tsx:18-83`；`region.tsx:222` | — | 缺失 | Android 无队列面板、无 inbox 数据源 |
| 队列行操作（拖拽手柄、行内编辑、Steer/Send、删除） | `queue-panel.tsx:85-196`；`U/app/src/session/composer/queue.ts:132-160` | — | 缺失 | `A/ChatViewModel.kt` 只有 `send`/`abort`，无 inbox/delivery API |
| 运行中发送语义（`delivery` 默认 `steer`，可 `queue` + `resume:false`） | `U/app/src/composer/submit.ts:235`；`queue.ts:65-74` | `Composer.kt:309-318`；`A/ChatViewModel.kt:399-425` | 近似 | Android 运行中非空输入仍走 `promptAsync`，不带 `delivery`，无法显式"排队/插话"二选一 |
| 草稿持久化（按 `dir:sessionID` 分槽持久化 prompt + cursor） | `U/app/src/composer/persistence.tsx:47-59` | `Composer.kt:106-108`；`A/ui/session/SessionScreen.kt:128-131` | 近似 | Android 仅 `rememberSaveable` 单实例；切换会话**不清文本**（只清附件），无 per-session 草稿分区 |
| token/上下文占用指示（`SessionContextUsage`，紧贴 composer 上沿的 header 右端） | `U/app/src/session/timeline/message-timeline.tsx:548` | `A/ui/session/header/SessionHeader.kt:163`；`A/ui/session/SessionScreen.kt:272`；`A/ui/session/header/ContextUsage.kt:37-46` | 近似 | 计算一致（末条 assistant 的 token 合计），但**位置不同**：上游在会话头右端、Android 也在头右端 —— 实际位置接近；差异在可点击性（见第 6 节） |
| agent / variant 选择器（`mod+.` 切 agent、`shift+mod+D` 切 variant） | `U/app/src/composer/editor/editor.tsx:284-306`；`commands.tsx:65-81` | — | 缺失 | Android composer 工具栏只有「+」、模型 chip、发送键三件 |

---

## 5. Dock（permission / question / 其它请求面板）

上游：`U/app/src/session/composer/session-composer-region.tsx`（挂载顺序）+ `U/app/src/session/requests/*` + `U/session-ui/src/components/dock-prompt.tsx` + `U/ui/src/components/dock-surface.css`。
Android：`A/ui/session/dock/` + `A/ui/session/SessionScreen.kt:307-363`。

| 上游组件/特性 | 上游证据(file:line) | Android 实现(file:line) | 状态 | 差异要点 |
|---|---|---|---|---|
| composer 区域挂载顺序 websearch → question → permission → composer | `U/app/src/session/composer/session-composer-region.tsx:36,39,47,62` | `A/ui/session/SessionScreen.kt:322,335,343` | 近似 | Android 的 question→permission→composer 顺序一致；**websearch dock 整块缺失** |
| question dock 位于 permission dock 之上 | `session-composer-region.tsx:39-45` | `A/ui/session/SessionScreen.kt:322-330` | 1:1 | 一致 |
| 请求树：跨 session（父/子）取第一个待办 | `U/app/src/session/requests/session-request-tree.ts:17-39,50-60` | `A/ChatViewModel.kt:112-114,125-127` | 近似 | 上游广度遍历 session 及其全部子孙后取首个匹配；Android 只对当前跟踪 session 取 `firstOrNull()`，子 session 的请求不会上浮 |
| 同一时刻只展示一个请求（非堆叠） | `U/app/src/session/requests/model.ts:38-67` | `A/ChatViewModel.kt:113,126` | 1:1 | 双方都只暴露 1 个 permission + 1 个 question |
| `blocked` 时隐藏 composer | `U/app/src/session/requests/model.ts:69-73`；`session-composer-region.tsx:68` | `A/ui/session/SessionScreen.kt:343` | 缺失 | 上游 `blocked()` 为真时不渲染 composer；Android 无条件渲染 `Composer(...)`（`SessionScreen.kt` 全文搜 `blocked` 无命中）→ 有权限请求时输入框仍可点 |
| 区域容器内边距 `px-3` + `pb-3` | `session-composer-region.tsx:28,32` | `A/ui/session/SessionScreen.kt:313` | 近似 | Android `start/end=12dp, top=4dp, bottom=8dp`；上游无 top、bottom 12 px |
| 相邻 dock 间距 | `session-composer-region.tsx:39-60` | `A/ui/session/SessionScreen.kt:328,340` | 近似 | 上游两个 dock 紧贴（无 margin）；Android 每个 dock 追加 `padding(bottom=8.dp)` |
| `DockPrompt` 骨架 = DockShell + DockTray（`margin-top:-24px` 叠压） | `U/session-ui/src/components/dock-prompt.tsx:14-22` | `A/ui/session/dock/SessionPermissionDock.kt:86-205`；`SessionQuestionDock.kt:144-243` | 1:1 | 两段式 shell/tray + 负 overlap 结构一致 |
| permission shell 背景（`--surface-raised-stronger-non-alpha`） | `U/ui/src/components/dock-surface.css:2`；`U/ui/src/styles/theme.css:122,393` | `A/ui/session/dock/SessionPermissionDock.kt:94` | 近似 | 上游 `#ffffff`/`#1c1c1c`；Android 用 `layer01` = `#fafafa`/`#242424`，token 选错一层 |
| permission shell 描边（`--shadow-xs-border` = `0 0 0 1px var(--border-base)`） | `dock-surface.css:3`；`U/ui/src/styles/theme.css:64-66,200` | `SessionPermissionDock.kt:95` | 近似 | 上游 1 px `--border-base`（`rgba(0,0,0,.162)`/`rgba(255,255,255,.195)`）；Android 0.5 dp `borderMuted`（8 % alpha）→ 宽度与强度都弱一半 |
| dock 圆角 12 px | `dock-surface.css:6,22` | `SessionPermissionDock.kt:80`；`SessionQuestionDock.kt:86` | 1:1 | 12 px → 12 dp |
| permission body 内边距 `12px 12px 0`、gap 16 | `U/session-ui/src/components/message-part.css:793-800` | `SessionPermissionDock.kt:96,127` | 1:1 | 一致 |
| permission header 行（grid `20px 1fr` + column-gap 8、居中） | `message-part.css:806-815` | `SessionPermissionDock.kt:100-115` | 1:1 | 一致 |
| permission 图标（`warning`，`--icon-warning-base`） | `U/app/src/session/requests/session-permission-dock.tsx:27-29`；`message-part.css:823-825` | `SessionPermissionDock.kt:108-113` | 1:1 | 16 px → 16 dp，色 token `stateWarningFg` |
| permission 标题（"Permission required"，14/500/21 px） | `session-permission-dock.tsx:30`；`U/app/src/runtime/i18n/en.ts:708`；`message-part.css:827-834` | `SessionPermissionDock.kt:117-122`；`A/res/values/strings.xml:336` | 1:1 | 文案逐字一致，排版一致 |
| permission 工具说明 hint（14/400/21 muted，未知工具隐藏） | `session-permission-dock.tsx:15-20,55-60`；`message-part.css:836-851` | `SessionPermissionDock.kt:83,130-139,276-291` | 1:1 | 14 个工具硬编码映射，与上游"key 不解析则隐藏"一致 |
| permission patterns（code 行 gap 6、margin-top 8、margin-bottom 16 + `padding: 1px 0 8px`） | `session-permission-dock.tsx:62-71`；`message-part.css:853-874` | `SessionPermissionDock.kt:140-155` | 近似 | 字号/色/gap 一致；上游另有 `padding: 1px 0 8px`（底部合计 24 px），Android 只给 16 dp |
| permission patterns 可滚动 + `max-height: 100dvh` | `message-part.css:785-791,861-868` | `SessionPermissionDock.kt:140-155` | 缺失 | Android 无 maxHeight、无 `verticalScroll` → 超长 patterns 会把 dock 顶出屏幕 |
| tray 背景/边框（`--background-base` + `1px --border-weak-base` + radius 12） | `dock-surface.css:17-24`；`U/ui/src/styles/theme.css:94,206,365,477` | `SessionPermissionDock.kt:161-166` | 近似 | 上游 `#f8f8f8`/`#101010` + `#e5e5e5`/`#282828`；Android `bgBase` `#ffffff`/`#161616` + `borderMuted`，两处 token 均不同 |
| tray `padding: 32px 8px 8px` + `margin-top: -24px` | `message-part.css:876-883` | `SessionPermissionDock.kt:84,166,194-205` | 1:1 | 32/8/8 dp + 24 dp 负重叠 1:1 |
| permission 底部按钮列右对齐、gap 8 | `session-permission-dock.tsx:33-53`；`message-part.css:876-894` | `SessionPermissionDock.kt:169,178,185` | 1:1 | 一致 |
| Deny / Allow always / Allow once 三键、顺序、语义（`reject`/`always`/`once`）、variant（ghost/neutral/submit） | `session-permission-dock.tsx:37-50`；`U/ui/src/i18n/en.ts:230-232` | `SessionPermissionDock.kt:172-191`；`A/res/values/strings.xml:337-339` | 1:1 | 文案、顺序、回复字符串、variant 全部一致 |
| 无 "always for this session" / pattern 变体（v2 三键制） | `session-permission-dock.tsx:37-50` | `SessionPermissionDock.kt:172-191` | 1:1 | 双方都没有额外变体按钮；`request.always` 均未渲染 |
| permission dock 无关闭/忽略按钮、无快捷键 | `session-permission-dock.tsx:22-73` | `SessionPermissionDock.kt:86-205` | 1:1 | 均只能三选一 |
| 按钮 `button-v2 size=normal`（高 28、padding-x 12、radius 6、13/530/20） | `U/ui/src/actions/button/button.css:9-27,38-41`；`message-part.css:890-893` | `SessionPermissionDock.kt:238-270` | 1:1 | 一致 |
| 按钮禁用态（ghost/neutral `0.5`，submit `0.4`） | `button.css:89-92,227-230`；`U/ui/src/actions/submit.css:71-74` | `SessionPermissionDock.kt:241` | 近似 | Android 对全部 variant 统一 `0.5f`，submit 上游是 `0.4` |
| question shell body 内边距 `8px 8px 0` | `message-part.css:905-912` | `SessionQuestionDock.kt:154` | 近似 | 上游 8/8/0；Android 复用 permission 的 12/12/0 → 横向与顶部各多 4 dp |
| question header 标题＝进度摘要 `{{current}} of {{total}} questions` | `U/app/src/session/requests/session-question-dock.tsx:112-115,484`；`U/app/src/runtime/i18n/en.ts:818` | `SessionQuestionDock.kt:175`；`A/res/values/strings.xml:360` | 缺失 | 上游标题是 "1 of 3 questions"；Android 是静态 "Question"，全仓无 "of %d questions" 任何形式 |
| question header 无图标 | `session-question-dock.tsx:482-513` | `SessionQuestionDock.kt:162-172` | 近似 | Android 多出一个 `ic_oc_help` 图标（20 dp 槽 / 16 dp 图形 / `iconAccent`），上游 question dock 无图标槽 |
| question 标题排版（14/500/`--line-height-large` 21 px） | `message-part.css:922-929` | `SessionQuestionDock.kt:174-180` | 1:1 | 一致 |
| 多问题 = 分页（一次一题） | `session-question-dock.tsx:87-96,105-110,433-462` | `SessionQuestionDock.kt:186-201` | 缺失 | 上游 `store.tab` 单题呈现；Android `questions.forEachIndexed` 全部竖排堆叠 |
| 进度段 `question-progress-segment`（16×16 命中区 + 16×2 圆点条，三态 `icon-muted`/`icon-strong`/`icon-interactive`） | `session-question-dock.tsx:486-502`；`message-part.css:938-979` | — | 缺失 | Android 无任何进度段（`SessionQuestionDock.kt` 搜 `progress`/`answered` 无命中） |
| 最小化 chevron + 折叠（spring）+ 问题文本 3 行截断 | `session-question-dock.tsx:120-122,503-510,538-548`；`message-part.css:1206-1240` | — | 缺失 | Android 无折叠能力 |
| Back 按钮（`tab>0` 时出现，`neutral`） | `session-question-dock.tsx:520-524`；`U/ui/src/i18n/en.ts:219` | — | 缺失 | Android 无分页，故无 Back |
| Next/Submit 二态（非末题 `neutral`"Next"，末题 `submit`"Submit"） | `session-question-dock.tsx:525-533`；`U/ui/src/i18n/en.ts:224-225` | `SessionQuestionDock.kt:224-229` | 近似 | Android 恒为 Submit（submit variant），等价于"末题"；文案 "Submit" 一致，Next 态不存在 |
| question footer 布局（Dismiss 左对齐 `space-between`、动作组右对齐） | `session-question-dock.tsx:514-536`；`message-part.css:1190-1203` | `SessionQuestionDock.kt:214-229` | 近似 | 上游 Dismiss 贴左；Android 先 `Spacer(weight 1f)` 把 Dismiss 推到右侧紧邻 Submit → 左右位置颠倒 |
| question footer 按钮 `size="large"`（高 32、padding-x 15） | `session-question-dock.tsx:516,521,527`；`button.css:49-52` | `SessionQuestionDock.kt:463-497` | 近似 | 上游 32 px/15 px；Android 28 dp/12 dp → 高度与内边距都缩了 |
| question 正文排版（14/500/21 px） | `message-part.css:989-999` | `SessionQuestionDock.kt:269-276` | 近似 | 字号/行高一致，但 Android 未设 `fontWeight`（默认 400），上游是 500 |
| question hint（13 px/400/150 % muted；"Select one answer"/"Select all answers that apply"） | `session-question-dock.tsx:549-553`；`U/ui/src/i18n/en.ts:266-267`；`message-part.css:1001-1009` | `SessionQuestionDock.kt:277-285`；`A/res/values/strings.xml:365-366` | 近似 | 文案不同：Android "Select one option"/"Select all that apply"；行高 16 sp（上游 19.5 px）；颜色 `textFaint`（上游 `--v2-text-text-muted`） |
| options 容器（gap 6、margin-top 12、`padding: 1px 1px 16px`、`overflow-y:auto`） | `message-part.css:1011-1026` | `SessionQuestionDock.kt:286-289` | 近似 | Android `padding(top=4.dp)` + `spacedBy(4.dp)`（上游 12/6），且无 `overflow-y:auto` |
| 选项行 `question-option`（bg `layer-01`、0.5 px `border-base`、radius 6、padding `8px 8px 8px 10px`、gap 12、`align-items:flex-start`） | `message-part.css:1028-1059` | `SessionQuestionDock.kt:325-333` | 近似 | Android 仅选中时填 `stateInfoBg`，未选中**完全透明且无边框**（丢掉 `layer01` 卡面 + 0.5 dp border）；padding `8/6`；gap 8；居中（上游顶对齐） |
| 选项行 hover（`bg-base`）与 disabled `0.6` | `message-part.css:1045-1058` | — | 缺失 | Android 无 hover（触屏可接受）也无 disabled 0.6；`responding` 只禁用按钮，选项行仍可点 |
| `question-option-check` `transform: translateY(2px)` | `message-part.css:1061-1064` | `SessionQuestionDock.kt:334-335` | 近似 | Android 无 2 dp 下沉补偿 |
| `question-option-box`（16×16、`padding:2px`、`--radius-sm` 4 px、0.5 px `border-base`；选中 `icon-interactive-base` 描边 + checkbox 填充） | `message-part.css:1066-1116`；`U/ui/src/styles/theme.css:46` | `SessionQuestionDock.kt:421-453` | 1:1 | 16 dp、radius 4 dp、0.5 dp、选中 `iconAccent` + `iconInverse` 勾，全部一致 |
| radio 圆点（6 px，`--v2-background-bg-layer-01` 色点叠在 accent 实心上 → "甜甜圈"） | `message-part.css:1090-1096,1110-1115` | `SessionQuestionDock.kt:444-451` | 近似 | 尺寸 6 dp 一致；Android 用 `iconAccent` 实心点 + 透明底，上游是 accent 实心底 + layer-01 色点，视觉形态不同 |
| `option-label`（14 px/500/21 px） | `message-part.css:1127-1133` | `SessionQuestionDock.kt:337-343` | 近似 | Android 13 sp/530/16 sp：字号 −1 sp、字重 530、行高 16（上游 21） |
| `option-description`（14 px/400/21 px muted） | `message-part.css:1135-1144` | `SessionQuestionDock.kt:344-351` | 近似 | Android 13 sp/16 sp + `textMuted`，字号与行高均小于上游 |
| `question-option-main` 竖向 gap 2 px | `message-part.css:1119-1125` | `SessionQuestionDock.kt:336,378` | 近似 | Android `Column` 默认 0 间距，label 与 description 之间缺 2 dp |
| 自定义答案行（"Type your own answer"） | `session-question-dock.tsx:580-603`；`U/ui/src/i18n/en.ts:103` | `SessionQuestionDock.kt:300-309`；`strings.xml:363` | 近似 | 标签文案 1:1；但 Android 用 v1 `question.custom` 字段做门控（`custom=false` 时不出现），上游无此开关 |
| 自定义行占位文案（"Type your answer…"；未编辑时以 description 常显） | `session-question-dock.tsx:598-600`；`U/ui/src/i18n/en.ts:268` | `SessionQuestionDock.kt:388-395`；`strings.xml:364` | 近似 | Android 文案 "Your answer"（少 "Type "/"…"），且仅在展开时显示 |
| 自定义输入框（`<textarea rows=1>` 自动增高、14/400/21 muted、可多行） | `session-question-dock.tsx:628-653`；`message-part.css:1156-1188` | `SessionQuestionDock.kt:396-408` | 近似 | Android `BasicTextField(singleLine = true)`、13 sp/16 sp、`textBase`（上游 muted、可多行、自动增高） |
| 自定义 mark 单击（toggle）与行体单击（打开编辑）语义分离 | `session-question-dock.tsx:427-431,593-595,611-619` | `SessionQuestionDock.kt:372` | 近似 | Android 整行（含 mark）都走 `onToggle`，两种点击语义合并 |
| 每题独立 header 标签 | 上游计算 `header: field.title` 但 JSX 未渲染（`session-question-dock.tsx:78,547`） | `SessionQuestionDock.kt:260-268` | 近似 | Android **额外渲染**了 13 sp/530/16 sp `textMuted` 的 `question.header` 标签 —— 上游该字段是死代码 |
| 键盘交互（Esc=reject、⌘/Ctrl+Enter=next/submit、↑↓←→/Home/End 移动焦点） | `session-question-dock.tsx:345-389,516,530` | — | 缺失 | Android 无任何按键处理。移动端部分适用（外接键盘 / Esc） |
| question dock `max-height: var(--question-prompt-max-height, 100dvh)`（JS 实测最小 240 px） | `message-part.css:903`；`session-question-dock.tsx:144-164` | — | 缺失 | Android 无高度上限，无 `measure()` 等价逻辑 |
| 答案回填：question 工具在时间线里展示已答问题 | `U/session-ui/src/tools/tool-renderer.tsx:2129-2165`；`message-part.css:1242-1262` | — | 缺失 | Android 时间线无 question 渲染器（`ToolPresentation.kt:87-118` 工具名分支中无 `question`） |
| 未答题的 `data-answered` 已答态 | `session-question-dock.tsx:286-289,491-494`；`message-part.css:971-973` | — | 缺失 | Android 无已答态显示 |
| 必填/可选（required/optional）语义 | 上游 v2 无此概念（`session-question-dock.tsx:275-289`） | `SessionQuestionDock.kt:133-142` | 1:1 | 双方都无 required |
| 自动批准开关（auto-approve）抑制 permission dock | `U/app/src/session/requests/model.ts:64-67`；`auto-approve.ts:14-136` | `A/AutoApprover.kt:22-53`；`A/ChatViewModel.kt:138-140,854,882-898`；`A/ui/settings/SettingsPanels.kt:88` | 近似 | 逻辑已复刻；但上游 `autoApprove` 为真时 `permissionRequest()` 直接返回 `undefined`（dock 隐藏），Android `pendingPermission` 未做该门控 → dock 可能闪现 |
| websearch provider dock | `U/app/src/session/requests/session-websearch-dock.tsx:10-86`；`session-websearch-dock.css:1-58`；`session-composer-region.tsx:36-38` | — | 缺失 | Android 完全无对应实现（只在 `ToolPresentation.kt:115` 有 websearch 图标） |
| 后台任务指示器 + "移到后台" 提示与任务数 | `U/app/src/session/requests/background.ts:8-116`；`U/app/src/session/timeline/message-timeline.tsx:55-75`；`U/app/src/session/summary/background.tsx:38-60` | — | 缺失 | Android 无 background tasks 模型、无内联提示、无任务数 |
| 待处理请求计数文案（`session.question.pending`） | 上游仅 i18n 定义（`U/app/src/runtime/i18n/en.ts:831-832`），`*.tsx` 无消费者 | — | 1:1 | 上游本身也没渲染计数角标 → 实际不构成 UI 差异 |
| 文案本地化（走 i18n key 体系） | `U/ui/src/i18n/en.ts:103,219-232,263-268` | `A/res/values/strings.xml:336-366` | 近似 | Android 用 `strings.xml` 英文单语资源 + `values-zh-rCN`/`values-zh-rTW`，未接上游 key 体系；除上文标注的 4 处外英文逐字一致 |

---

## 6. 会话头 / 抽屉

上游：`U/app/src/session/session-identity-header.tsx`（+ `.css`）、`U/app/src/session/timeline/message-timeline.tsx:416-581`（头部右侧控件与 ⋮ 菜单）、`U/app/src/session/timeline/session-context-usage.tsx`（用量环）、`U/app/src/shell/mobile-drawer.css`（移动抽屉）、`U/app/src/home/sessions/controller.tsx:334-351`（会话分组）。
Android：`A/ui/session/header/`、`A/ui/session/drawer/`、`A/ui/components/SessionProgressIndicator.kt`。

| 上游组件/特性 | 上游证据(file:line) | Android 实现(file:line) | 状态 | 差异要点 |
|---|---|---|---|---|
| 顶部容器 / sticky 渐隐 | `U/app/src/session/session-identity-header.tsx:29-35` | `A/ui/session/header/SessionHeader.kt:107-113` | 近似 | 上游 `pb-4`(16 px) + `ps-2.5`(10 px) + `pe-3`(12 px) + `bg-[linear-gradient(bg-base 48px,transparent)]`；Android 只有 `height 48dp` + `start/end 4dp`，无底部渐隐 |
| 顶栏行高 / 对齐（`h-12`） | `U/app/src/session/timeline/message-timeline.tsx:419` | `A/ui/session/header/SessionHeader.kt:110` | 1:1 | 48 px → 48 dp 1:1；`gap-2` 未显式设置（改用固定 40 dp 触控位） |
| 左侧项目菜单（头像 / `monitor` 触发器 + 项目项 + 目录项 + 分隔 + 项目设置） | `session-identity-header.tsx:86-108,111-201` | — 搜过 `SessionHeader.kt:114-151`，全文件无 ProjectAvatar / 项目菜单 | 缺失 | 手机端无法从会话内查看/打开项目目录，也无「项目设置」入口 |
| 父子面包屑 + `/` 分隔符（父标题 `max-w-[40%] truncate pl-2 13px/530 text-faint`；分隔符 11 px） | `U/app/src/session/timeline/message-timeline.tsx:428-436,437-444` | — 搜过 `SessionHeader.kt:114-151` 与 `SessionScreen.kt:258-278`，无 parentID 分支 | 缺失 | 子会话（subagent）在 Android 上看不到父会话标题，也无法跳回父会话 |
| 标题 h1 度量 + 内联编辑入口（`px-1 py-1`、`leading-4`、`w-fit`、hover `overlay-hover`） | `message-timeline.tsx:449-455` | `A/ui/session/header/SessionHeader.kt:138-150` | 近似 | 13 px/530 与 radius 6 px 一致；但内边距 Android `h6/v4` vs 上游 `4/4 px`，未设 `leading-4`(16 px)，宽度 `weight(1f)` 占满 vs 上游 `w-fit` |
| 返回 affordance | 上游**无**（`message-timeline.tsx:416-543` 整行只有项目菜单/标题/⋮/ring/summary/spacer；`arrow-left`/`chevron-left` 只出现在 `providers/connect/dialog.tsx:92`、`settings/navigation.tsx:53`、`session/browser/pane.tsx:201`） | `SessionHeader.kt:114-119`（`ic_oc_chevron_down` 旋转 90°）+ `:121-125`（☰ 抽屉） | 近似 | Android 独有，且是**移动端必需**（上游移动端靠 titlebar/浏览器历史返回）→ 属合理适配 |
| 头部右侧动作顺序 | `message-timeline.tsx:546-576`（search → ring → summary → 28 px spacer） | `SessionHeader.kt:156-168`（ring → ⋮）+ `SessionScreen.kt:281-287` | 近似 | 上游 review 按钮绝对定位在面板 `end-3`（`U/app/src/session/screen.tsx:339-347`）为最右控件，header 预留 `size-7`(28 px，`U/app/src/session/header/session-header.tsx:9`)；Android 用 `align(CenterEnd)+padding(end=80dp)` 把 review 放在 ring(40 dp)+⋮(40 dp) 左侧 |
| review 切换按钮样式与显示条件 | `U/app/src/session/header/session-header-actions.tsx:53-69`；`U/ui/src/actions/icon-button/icon-button.css:41-44,100-119`（large=28×28，ghost-muted，pressed→`layer-01`/`icon-base`）；仅桌面渲染 `U/app/src/session/screen.tsx:338-348` | `A/ui/session/SessionScreen.kt:419-440` | 近似 | Android 40 dp 圆形容器、active 用 `iconAccent` + `layer02` 底、手机上常显；上游 28 px / 圆角 6 px、pressed 底 `layer-01`、图标 `icon-muted→icon-base`，且仅桌面出现（手机走上游 mobile view tabs，`U/app/src/session/review/view.tsx:23-65`） |
| 重命名入口 1：⋮ → Rename → 内联编辑器 | `message-timeline.tsx:517-525,510-515` | `A/ui/session/header/SessionHeader.kt:318-325` | 1:1 | 机制一致（含焦点交接） |
| 内联编辑器键盘契约（Enter 存 / Esc 取消 / blur 存 / pending 时 disabled） | `message-timeline.tsx:465,467-470,472-485` | `A/ui/session/header/SessionHeader.kt:236-259` | 近似 | 契约一致；Android 额外在挂载时 `requestFocus` + 弹软键盘（`:256-259`）并给编辑态加 `layer02` 底（`:241`），上游无底色 |
| 重命名入口 2：列表行长按 500 ms / 右键 → 菜单 → Rename | `U/app/src/home/sessions/view.tsx:30`（`HOME_SESSION_LONG_PRESS_MS=500`）、`:597-610`、`:694`、`:481-488` | — 搜过 `SessionDrawer.kt:131-142`（仅 `clickable`）、`HomeSessionsList.kt:390-419`（仅 `clickable`） | 缺失 | Android 两个列表都没有长按/右键菜单 → 无法从列表改名 |
| 重命名 API（`PATCH /session/:id {title}`） | `U/app/src/session/timeline/controller.tsx:151-152` | `A/OpenCodeClient.kt:659-661` + `A/ChatViewModel.kt:547-563` | 1:1 | 一致；Android 成功后本地回填 `_currentTitle` |
| 子会话禁用重命名/导出（`Show when={!parentID()}`） | `message-timeline.tsx:517,530` | — 搜过 `SessionHeader.kt:318-337`（无条件给 Rename/Delete） | 缺失 | Android 无 parentID 概念，子会话也能进 Rename |
| 用量环几何（compact：viewBox 14 / strokeWidth 1.5） | `U/ui/src/feedback/progress-circle/progress-circle.tsx:15-19`；`session-context-usage.tsx:125-129` | `A/ui/session/header/ContextRing.kt:65-68`（14 dp / 1.5 dp） | 1:1 | 14 px → 14 dp 1:1（但 Android 注释 `:16-24` 把 compact 数值写成了 "v2 appearance"） |
| 用量环几何（indicator：size 16 / strokeWidth 2，用于侧栏） | `session-context-usage.tsx:110-124`；`progress-circle.tsx:15-17`；使用点 `U/app/src/session/files/session-side-panel.tsx:370` | — 搜过 `ContextRing.kt:38-71`、`SessionHeader.kt:153-164`（只有 14/1.5 一种几何） | 缺失 | review/context 侧栏的 16/2 indicator 环未移植 |
| 用量环颜色 token（轨道 `layer-04`、进度 `icon-base`） | `U/ui/src/feedback/progress-circle/progress-circle.css:6-12`；`session-context-usage.tsx:117-121` | `A/ui/session/header/ContextRing.kt:51,54` | 1:1 | 完全对齐 |
| 起始角 / 无缺口 / 过渡曲线（`rotate(-90deg)`；`stroke-dashoffset .35s cubic-bezier(.65,0,.35,1)`） | `progress-circle.css:3,27`；`progress-circle.tsx:37-61` | `A/ui/session/header/ContextRing.kt:40-44,55,71` | 1:1 | 起点、无缺口、350 ms 时长与缓动全部对齐 |
| 颜色阈值（>80 % 变红） | 上游**无**：全仓 grep `usage\s*[><]=?\s*\d` / `percentage\s*[><]=?\s*\d` 零命中；`session-context-usage.tsx:68-85` 只 clamp 0…100 | `ContextRing.kt:34-36`、`ContextUsage.kt:19-21`（明确写"不臆造阈值"） | 1:1 | Android 正确未造规则 |
| 环内标签 | `progress-circle.tsx:23-62`（只有 track/overlay/progress 三个 `<circle>`，无 `<text>`） | `ContextRing.kt:45-61`（只 drawCircle/drawArc） | 1:1 | 两侧环内都没有数字 |
| 百分比算法 + 数据可用性 | `session-context-usage.tsx:68-82`（`input+output+reasoning+cache.read+cache.write` ÷ `model.limit.context`，round） | `A/ui/session/header/ContextUsage.kt:32-39,45-46`；调用点 `SessionScreen.kt:272`（`limit = null`） | 近似 | 公式一致，但 Android 传 `limit=null` ⇒ `percentOf` 恒返回 null ⇒ **环永远是空轨道**；token 口径来自 `ChatStore.UiMessage.tokens`（未逐字段核对） |
| 环 tooltip（Cost / Context % / Tokens，`w-[120px] gap-2`）+ 会话成本 | `session-context-usage.tsx:21-28,60-66,83-85,131-140,144` | — 搜过 `SessionHeader.kt:156-161`（仅 `contentDescription`）、`ContextRing.kt:38-71`；全仓 grep `cost` 只命中注释 | 缺失 | 成本、使用率、token 数三个数字在 Android 完全不可见 |
| 环点击 → 打开 Review + Context 面板 | `session-context-usage.tsx:93-108,148-155` | — 搜过 `SessionHeader.kt:153-164`（Box 无 `clickable`） | 缺失 | Android 的环不可点，等同死控件 |
| compaction 指示器（started 分隔线 + running `BasicTool` "Compacting" + 结果 label 带 `{{input}} in · {{output}} out`，运行态参与 timeline "working" 判定） | `U/session-ui/src/message/message-content.tsx:434-465`；`message-timeline.tsx:358-361` | `A/ui/session/timeline/PartRenderer.kt:433-439`（单行 12 sp `text-faint` "Session compacted"） | 缺失 | 只有完成态一句话；无 started/running/failed/cancelled/interrupted 变体、无 running 转轮、无 usage 数字 |
| timeline 底部 "Working" shimmer | `message-timeline.tsx:387-391`；`U/app/src/runtime/i18n/en.ts:781` | — 搜过 `MessageTimeline.kt:113-137`（只渲染 error card + messages）、`PartRenderer.kt:219-252`（shimmer 只用于工具标题） | 缺失 | Android 无持续的 "Working" 行；流式仅靠 `oc_cursor` "▍" |
| ⋮ 菜单项与顺序 | `message-timeline.tsx:517-536`（Rename / Export… / Separator / Delete…） | `A/ui/session/header/SessionHeader.kt:318-337`（Rename / 0.5 dp 分隔线 / Delete 禁用） | 近似 | 顺序一致，**Export… 缺失**；Delete 是永久 disabled 占位（`:335`），无删除确认对话框（上游 `U/app/src/home/sessions/controller.tsx:222-243` danger Dialog） |
| 菜单容器宽度与表面 | `message-timeline.tsx:508-509`；`U/ui/src/navigation/menu/menu.css:6-32`（min-width 160 px、radius 6 px、padding 2 px、`elevation-floating`、无边框） | `A/ui/session/header/SessionHeader.kt:312-316,374` | 近似 | Android 固定 120 dp（上游 fit-content）；radius 8 dp vs 6 px；竖向 padding 4 dp vs 2 px；Android 多画一条 0.5 dp `borderBase` |
| 菜单项度量（h 28 px、`padding 0 12px`、radius 4 px、13 px/440、`padding-inline-end: 30px`） | `U/ui/src/navigation/menu/menu.css:38-83`；`U/app/src/session/session-identity-header.css:7-9` | `SessionHeader.kt:346-364`（13 sp、`h10/v6`、无固定高、无圆角、默认字重） | 近似 | 高度非固定（随字体度量）、起始内边距 10 dp vs 12 px、缺 30 px 尾部留白与 4 px 圆角 |
| 分隔线与禁用项（1 px `border-muted`、`opacity:.5` + `pointer-events:none`） | `menu.css:156-159,162-168` | `SessionHeader.kt:326-332,351-352,359,378` | 近似 | 分隔线用色与粗细不同（`border-muted` 1 px vs `border-base` 0.5 dp）；Android 额外把禁用文字改成 `textMuted`，上游只降不透明度 |
| 菜单入场动画（`menu-v2-in 120ms ease-out`，scale .96→1） | `menu.css:27,302-311` | `SessionHeader.kt:306-309`；`A/ui/theme/OcMotion.kt:199-200` | 1:1 | 时长与起始缩放一致 |
| **触发图标方向与尺寸**（`outline-dots` 16×16 内三点在 `x=2.5/7.5/12.5, y=7.5–8.5` → **横向 ⋯**） | `message-timeline.tsx:499`；`U/ui/src/icons/icon/icon.tsx:139-142` | `A/res/drawable/ic_oc_dots.xml:7-11`（24×24 内 `M12,5.5v0.01M12,12v0.01M12,18.5v0.01` → **纵向 ⋮**） | 近似 | **图标方向正好差 90°**（上游是横排三点"⋯"，Android 是竖排三点"⋮"），是头部最容易被一眼看出的差异。另：Android 触发器 40 dp 圆 + 16 dp 图标 + `iconBase` 着色（`SessionHeader.kt:172-196,371`），上游 `IconButton size="large"` 28×28 + 圆角 6 px + `ghost-muted`(`icon-muted`)（`icon-button.css:41-44,100-103`） |
| 菜单定位与无障碍（`bottom-start`、gutter 6、`aria-label`/`aria-expanded`） | `message-timeline.tsx:491-493,503-504` | `SessionHeader.kt:283,288-300,376`（右下对齐、gap 4 dp） | 近似 | 对齐方向（start vs end）与间距（6 px vs 4 dp）不同；Android 无 `aria-expanded` 等价语义 |
| 列表行菜单（Rename / Export… / Separator / Delete…） | `U/app/src/home/sessions/view.tsx:694-701` | — 搜过 `HomeSessionsList.kt:390-419`、`SessionDrawer.kt:131-142` | 缺失 | Home 列表与抽屉都无行菜单 |
| Archive 入口（默认隐藏）与 Delete 确认流 | `view.tsx:27`（`SHOW_HOME_SESSION_ARCHIVE=false`）、`:705-727`；`U/app/src/home/sessions/controller.tsx:290-311` | — 全仓 grep `oc_share`/`oc_export`/`oc_archive`/`archiveSession` 零命中 | 缺失 | Android 既无 archive 也无 export/delete 的真实动作 |
| 会话列表分组规则（Today/Yesterday/Older，本地日历日；两组都空时 Older 回落 `sidebar.project.recentSessions`） | `U/app/src/home/sessions/controller.tsx:334-351` | `A/ui/home/HomeSessions.kt:66-90` + `HomeSessionsList.kt:369-373` | 近似 | 分档与"本地日历日"一致；Android **没有** "Recent sessions" 回落标题 |
| 抽屉列表分组头（每组 `HomeSessionGroupHeader`，sticky `h-7`） | `U/app/src/home/sessions/view.tsx:148-156,421-441` | — 搜过 `SessionDrawer.kt:105-118`（扁平 `LazyColumn`） | 缺失 | 抽屉里所有会话连成一列，没有日期分组 |
| 分组头度量（`h-7`、13 px/440 `text-muted`、`ps-1.5 md:ps-3`） | `view.tsx:431-437` | `HomeSessionsList.kt:358-379,609`（28 dp、start 12 dp、13 sp、无字重） | 近似 | 高度 28 dp 1:1；起始内边距取了上游 md 档 12 px（手机档应为 6 px）；字重缺 440 |
| 相对时间分档 helper（<60 s / <60 m / <24 h / days） | `U/app/src/shell/time.ts:9-22` | `A/ui/session/drawer/SessionTimeLabel.kt:25-35` | 1:1 | 分档、取整、边界一致；Android 另加时钟回拨保护（`:29`） |
| 相对时间文案 | `U/app/src/runtime/i18n/en.ts:953-956`（"Just now" / "{{count}}m ago" / "{{count}}h ago" / "{{count}}d ago"） | `A/res/values/strings.xml:71-83`（"just now" / "%d minutes ago" / …） | 近似 | 短横式 `5m ago` → 长式 `5 minutes ago`；首字母大小写不同；Android 用 plurals |
| 相对时间出现位置 | 上游会话列表行**不显示**时间（`view.tsx:583-658` 只有 avatar + title + project）；时间只在 `shell/commands/dialog.tsx:267-271` 与 `settings/workspaces/workspaces.tsx:19` | `A/ui/session/drawer/SessionDrawer.kt:167,173-190`（每行右侧常显 12 sp） | 近似 | Android 把命令面板里的 meta 时间搬到了会话列表行；上游 Home 行没有时间 |
| 行度量（`h-10 rounded-[6px]`、`py-3 ps-1.5 pe-3 md:ps-3 md:pe-10`） | `view.tsx:520,589-590` | `SessionDrawer.kt:197`（48 dp）、`:136`（radius 6 dp）、`:134+:141`（16 dp）；Home：`HomeSessionsList.kt:393,399`（40 dp/6 dp/12–40 dp） | 近似 | 圆角 6 dp 1:1，Home 行 40 dp + 12/40 内边距 1:1；但**抽屉行 48 dp**（源自旧 View `item_session.xml`）≠ 上游 40 px，横向内边距也非 12/40 |
| 行内文字（标题 13 px/530、项目名 meta 13 px/440 `text-muted`） | `view.tsx:732-747,749-760` | `HomeSessionsList.kt:405-415,462-472`；`SessionDrawer.kt:154-162`（14 sp，选中 W500 / 未选 Normal） | 近似 | 抽屉标题 14 sp 且字重随选中态变化（上游恒 530）；**抽屉行完全没有项目名 meta** |
| 选中 / 打开态（12×2 px `bg-layer-04`，位于头像左侧 `right: calc(100%+4px)`） | `view.tsx:209-218`；`U/app/src/shell/titlebar/tab-nav.css:14-16,68-70` | `SessionDrawer.kt:139`（整行 `layer01` 填充）、`:144-153`（2×12 dp `iconBase` 条，行首内侧）；Home 侧 `HomeSessionsList.kt:449-458`（2×12 dp `layer04`） | 近似 | 色 token（`iconBase` vs `layer-04`）、位置（行内 vs 头像外侧）、形态（整行填充 vs 仅叠加层）三处不同；Home 侧的 `layer04` 是对的 |
| unread 圆点 + 头像遮罩（6 px `bg-accent`，`right/top -3px`，径向遮罩） | `U/ui/src/data-display/project-avatar/project-avatar.css:107-122`；`U/app/src/shell/layout/project-avatar-state.ts:38-40` | — 全仓 grep `unread` 零命中 | 缺失 | Android 无"需要处理/未读"提示 → 列表里看不出哪个会话在等权限或提问 |
| 头像度量（16×16 px、radius 4 px、渐变底 + inset 0.5 px 边、11 px/530 大写） | `project-avatar.css:2-6,12-37` | `HomeSessionsList.kt:433-448,624`（18 dp、radius 4 dp、`layer02` 底、10 sp 首字母） | 近似 | 尺寸 18 dp vs 16 px；底色 `layer02` vs `avatar-bg-gray` 渐变 + 内描边；省略渐变与 `text-shadow` |
| 运行指示器在列表行的位置 / 出现范围（running 时**替换** 16×16 头像） | `U/app/src/shell/titlebar/session-tab-avatar.tsx:36-49`；`tab-nav.tsx:252`；`titlebar.tsx:498` | `SessionDrawer.kt:164-166`（附在标题后、相对时间前） | 近似 | Android 与头像并排而非替换；且只在会话抽屉出现一次（全仓 grep `SessionProgressIndicator(` 仅 `SessionDrawer.kt:165`），底部 Tab 条不带指示器 |
| 运行指示器触发语义（`status==="running"`；root 时任一 family 成员 running；**有待处理权限/表单时不显示**） | `U/app/src/shell/layout/project-avatar-state.ts:41-50` | `SessionScreen.kt:219-220`（仅当前 `activeSession.id` 且 `runStatus==RUNNING`） | 近似 | 抽屉里其它正在运行的会话不会显示指示器；上游"有 pending 权限时不显示 running"的抑制规则也未实现 |
| 抽屉容器形态（**底部 sheet**：`border-radius:16px 16px 0 0`、`max-height:min(75dvh,…)`、`padding:0 12px`、`bg-deep`、`elevation-overlay`；scrim + 240/200 ms 动效；28 px 把手 + 32×4 radius-999 圆条；reduced-motion 关闭动效） | `U/app/src/shell/mobile-drawer.css:1-11,13-31,46-60,80-89` | `A/ui/session/SessionScreen.kt:194-212`（左侧 `ModalNavigationDrawer`、宽 `min(320dp, 78%)`、`RectangleShape`、`bgBase`） | 近似 | 形态（底部 sheet vs 左侧抽屉）、圆角（16/16/0/0 vs 0）、底色（`bg-deep` vs `bg-base`）、把手、自定义进出场动效全部不同；Android 的宽度常数依据来自旧 View shell（`SessionScreen.kt:448-452`），未在上游 v2 找到对应常量 |
| 抽屉「新建会话」入口（`h-7`=28 px、`px-2`、13 px、plus 图标） | `titlebar.tsx:539-550` | `SessionDrawer.kt:72-93,194`（h 44 dp、16 dp 图标、14 sp W500） | 近似 | 高度 44 dp vs 28 px，字号 14 sp vs 13 px；Home 的浮动 ✎ 在上游带文字标签（`view.tsx:107-117`），Android 只剩图标（`HomeSessionsList.kt:581-596`） |
| 空态与骨架屏（"Nothing here yet" / "Create a session to get started" + `pt-[52px]`、`gap-4`、neutral 按钮；骨架 1 标题条 + 4×`h-10 bg-deep/70`） | `U/app/src/runtime/i18n/en.ts:732-733`；`view.tsx:762-789,792-802` | `SessionDrawer.kt:96-103`（单行文案）；Home 侧 `HomeSessionsList.kt:479-497,505-533` | 近似 | 抽屉空态只剩一句居中 `textFaint` 文案（上游是标题 + 说明 + 按钮三件套）；Home 侧文案 1:1 但行距用 8/20 dp spacer vs 上游 `gap-4`=16 px |
| 会话条数上限（`HOME_SESSION_LIMIT = 64`） | `U/app/src/home/sessions/index.ts:6` | `A/ui/home/HomeSessions.kt:18`（`MAX_SESSIONS = 64`） | 1:1 | 一致；抽屉无上限 |
| 自动标题回落（去掉 `New session - <ISO>` / `Child session - <ISO>` 时间戳） | `U/app/src/session/title.ts:4-13`；`U/app/src/shell/titlebar/tab-title.ts:4-6` | — 搜过 `SessionDrawer.kt:155`、`HomeSessions.kt:50`（直接用 raw `session.title`） | 缺失 | 列表行会显示带 ISO 时间戳的原始标题 |
| Home 会话搜索（`h-9 rounded-[6px] py-1 ps-3 pe-2 bg-layer-02/60`，面板 `max-h-[min(20rem,40dvh)]`；**大小写不敏感子串**匹配 + `ses_` ID 精确兜底） | `view.tsx:229-366`；`U/app/src/home/sessions/search.ts:11,30-38` | `HomeSessionsList.kt:161-221,231-267,270-294`；`HomeSessions.kt:98-105` | 近似 | 尺寸与文案基本 1:1；**上游不是 fuzzysort**（Android 注释 `HomeSessions.kt:93-96`、`HomeSessionsList.kt:226-228` 把上游描述成 fuzzysort，实际是子串 `search.ts:35`）；Android 缺 `ses_` ID 精确查找与键盘上下移动/高亮态（`view.tsx:332-345`） |
| 运行指示器几何/尺寸/颜色（25 点，`x=1.5+i%5*3`，2×2 点 + 1 px 间隙；32 帧 16×16；`currentColor` = `icon-muted` `#808080`） | `U/session-ui/src/v2/components/session-progress-indicator-v2.tsx:5-9,21-22`；`session-ui/script/generate-session-progress-indicator.ts:62-81`；`session-progress-indicator-v2.css:4` | `A/ui/theme/OcMotion.kt:430-449`；`A/ui/components/SessionProgressIndicator.kt:41-64` | 1:1 | 网格、点尺寸、间隙、内缩、viewBox、默认 16 dp、颜色全部逐值对齐 |
| 运行指示器动画模型（8 个 key pose、透明度档 `[.2,.5,.75,1]`、对角带；30 fps × 1.2 s = 36 帧；`easeOut` = `cubic-bezier(0,0,0.58,1)`） | `generate-session-progress-indicator.ts:4-18,62-81,104-117`；`session-progress-indicator-v2.stories.tsx:13` | `OcMotion.kt:473-482`（按 `index/25` 相位错开 + 8 段 alpha `[1,.75,.5,.2]`）；`SessionProgressIndicator.kt:70-84`（`LinearEasing`、1200 ms） | 近似 | 两处不同：**波形**（上游对角带 8 姿态轮转 vs Android 按索引相位推移）与**缓动**（上游 ease-out 分段插值 vs Android linear）；`OcMotion.kt:422-426` 已自述这是偏离 |
| 运行指示器 reduced-motion（隐藏 APNG，只显示 viewBox 中央 `x=7.5 y=7.5 w=2 h=2` 单个方块） | `session-progress-indicator-v2.css:17-24`；`session-progress-indicator-v2.tsx:48` | `SessionProgressIndicator.kt:44-55`（相位冻结为 0：中心 1.0、其余 25 点全部 `.2`） | 近似 | 上游降级后**只留中心一个点**；Android 仍画出整片 5×5 点阵（中心最亮、其余 20 % 底噪） |

---

## 7. Review 侧栏 / 文件浏览器 / diff 预览

上游：`U/app/src/session/review/*`、`U/app/src/session/files/*`、`U/session-ui/src/components/session-review*.tsx`、`U/session-ui/src/v2/components/session-review-v2.*`、`U/session-ui/src/pierre/*`。
Android：`A/ui/session/review/`。

| 上游组件/特性 | 上游证据(file:line) | Android 实现(file:line) | 状态 | 差异要点 |
|---|---|---|---|---|
| 面板呈现形态（桌面） | `U/app/src/session/files/session-side-panel.tsx:257-272` | `A/ui/session/SessionScreen.kt:367-389` | 近似 | 都是侧栏 dock；上游严格 `isDesktop()` 门控后才挂载 |
| 面板呈现形态（移动端） | `U/app/src/session/screen.tsx:290,303-317`；`U/app/src/session/review/view.tsx:44-64` | `A/ui/session/SessionScreen.kt:371-387` | 近似 | 上游手机端是 session/changes/files/terminal **四个整宽 tab**；Android 沿用桌面 dock |
| 面板宽度常量 | `U/session-ui/src/v2/components/session-review-v2.tsx:17-19`（DEFAULT 240 / MIN 200 / MAX 480）；`U/app/src/session/session-panel-width.ts:5-7`（450/480） | `A/ui/session/SessionScreen.kt:443`（`REVIEW_PANEL_WIDTH = 340.dp`） | 近似 | 340 dp 既非 default 240 也非 pane 480；无「聊天面板保底 450 px」约束 |
| 宽度可拖拽 + 双击复位 + 持久化 | `session-review-v2.tsx:135-146`；`U/app/src/session/review/panel-state.ts:40-44` | — | 缺失 | Android 无拖拽、无复位、无持久化（搜 `ResizeHandle`/`pointerInput` 无果） |
| 拖拽手柄视觉 | `U/session-ui/src/v2/components/session-review-v2.css:48-59` | — | 缺失 | 同上 |
| 面板圆角与阴影（`rounded-[10px]` + `--v2-elevation-raised`，无边框） | `session-side-panel.tsx:263` | `A/ui/session/review/ReviewPanel.kt:84`（0.5 dp 全描边 + 2 dp） | 近似 | 圆角 10 px vs 2 dp；阴影 vs 描边。Android 注释称对齐上游 `border-inline-start`，实际上游是 `border-inline-**end**` |
| 面板分隔线（1 px `border-inline-end`） | `session-review-v2.css:33` | `ReviewPanel.kt:84` | 近似 | 方向/位置不同 |
| 面板背景 | `session-review-v2.css:7` | `ReviewPanel.kt:81` | 1:1 | token 同名同值（`#ffffff`/`#161616`） |
| Header 高度与内边距（12 px；`8px 16px`） | `session-review-v2.css:61-69` | `ReviewPanel.kt:87-92`（40 dp；`12,4`） | 近似 | 数值不同 |
| Header 标题排版（13 px/500） | `session-review-v2.css:76-78` | `ReviewPanel.kt:94-101`（13 sp/530） | 近似 | 字重 530 vs 500；色 token 不同 |
| 变更模式选择器（Git / Branch / Last turn） | `U/app/src/session/review/view.tsx:228-247` | `ReviewPanel.kt:94-101`（静态 "Review"） | 近似 | 上游可切三种口径；Android 固定单一口径 |
| Header 分支信息 | `session-review-v2.css:61-69`（上游 header 无 branch chip） | `ReviewPanel.kt:102-119` | 近似 | Android 自行新增 branch chip（额外实现） |
| Header 关闭控件 | `session-review-v2.tsx:329-346`（收起 sidebar） | `ReviewPanel.kt:121-125`（关闭整面板） | 近似 | 语义与图标不同 |
| 关闭按钮尺寸（36×28 radius 6） | `session-review-v2.css:323-330` | `ReviewPanel.kt:207-219`（32 dp 正圆） | 近似 | 形状不同 |
| Tab 集合与顺序（session / changes / files / terminal + all-files） | `view.tsx:46`；`session-side-panel.tsx:655-661` | `ReviewPanel.kt:40-41,129-142` | 近似 | Android 缺 session/terminal/all-files 三个 tab |
| Tab 标签文案（`Files Changed {count}`） | `U/app/src/runtime/i18n/en.ts:740-741,745,803,786` | `A/res/values/strings.xml:377-378`（"Changes"/"Files"） | 近似 | Android 无计数 |
| Tab 图标 | `session-side-panel.tsx:399,450` | `ReviewPanel.kt:130-141` | 近似 | Android 无图标 |
| Tab 激活指示线 | `session-side-panel.tsx:44-45` | `ReviewPanel.kt:188-193`（24×2 dp） | 近似 | 上游指示线随 trigger 宽度 |
| Tab 文件计数角标 | `session-side-panel.tsx:339-341,657` | — | 缺失 | 无变更文件计数 |
| 空态「无变更」文案 | `U/app/src/runtime/i18n/en.ts:32-33`（desktop）/`:798`（mobile） | `A/res/values/strings.xml:379`；`ReviewChangesTab.kt:63-73` | 近似 | 三方文案互不相同（上游 "No file changes yet" + "Project changes will appear here" / 移动 "No changes"；Android "No changes yet"），Android 单行无副标题 |
| 空态插图 | `U/session-ui/src/v2/components/session-review-empty-changes-v2.tsx:10`；`session-review-v2.css:527-531` | `ReviewChangesTab.kt:63-73` | 缺失 | 无 review 图标 |
| 空态排版基线（gap 8 / `padding-bottom:160px` / `max-w 282px`） | `session-review-v2.css:515-525,533-552` | `ReviewChangesTab.kt:64-71` | 近似 | 无 160 px 底部留白、单行、色 token 不同 |
| 空态「非 Git 仓库」（"No Git Version Control System detected…" + Create Git repository 按钮） | `U/session-ui/src/v2/components/session-review-empty-no-git-v2.tsx:15-26`；`U/app/src/runtime/i18n/en.ts:28-31`；`session-review-v2.css:476-513` | — | 缺失 | 无 no-git 分支、无建仓按钮 |
| 空态加载文案（`Loading changes…`） | `U/app/src/session/review/panel.tsx:233-237`；`en.ts:297-298` | `ReviewFilesTab.kt:116-119` | 近似 | 缺省略号；Changes tab 无 loading 态 |
| 空态搜索无结果 | `panel.tsx:265`；`en.ts:161` | — | 缺失 | 依赖上游 filter |
| 变更列表结构 | `panel.tsx:245-251`（`FileTreeV2`） | `ReviewChangesTab.kt:76-114`（扁平 `LazyColumn`） | 近似 | 上游是变更文件**树**；Android 是平铺列表（basename + 暗色 dirname） |
| 目录默认展开 | `U/app/src/session/files/file-tree-v2.tsx:151`（review 树默认 `true`） | `ReviewFilesTab.kt:73-76,154-162`（默认折叠） | 近似 | 默认相反 |
| 嵌套缩进步长 | `file-tree-v2.tsx:35,37-41` | `ReviewFilesTab.kt:214` | 近似 | 步长 16 一致，但 Android 文件多缩进一级 16 dp |
| 树行高与行间距 | `file-tree-v2.tsx:164-165`；`U/ui/src/styles/file-tree.css:21` | `ReviewFilesTab.kt:211` | 近似 | 28 一致；缺 2 px gap |
| 树行圆角/悬停/选中 | `file-tree.css:29,49-64`（6 px） | `ReviewFilesTab.kt:212`（4 dp） | 近似 | 圆角差 2；无 hover/selected 底色 |
| 树行图标（16 px，选中变彩色） | `file-tree-v2.tsx:293-296` | `ReviewFilesTab.kt:230-237`（14 dp 单色） | 近似 | 差 2 dp；无选中态切换 |
| 树行文件名排版 | `file-tree-v2.tsx:107`；`file-tree.css:31` | `ReviewFilesTab.kt:239-246` | 近似 | 12/500 一致；色 token 不同 |
| 忽略文件表现 | `file-tree-v2.tsx:90`；`file-tree.css:45-47` | `ReviewFilesTab.kt:215`（alpha 0.5） | 近似 | 上游仅降文字色，Android 整行淡化 |
| 折叠箭头过渡（120 ms） | `file-tree-v2.tsx:316-322`；`file-tree.css:109-119` | `ReviewFilesTab.kt:203-226`（150 ms） | 近似 | 过渡时长 120 vs 150 |
| 同级排序（`localeCompare`） | `U/app/src/session/files/file-tree-v2-model.ts:52-57` | `A/ui/session/review/ReviewModels.kt:120-121`（`lowercase()`） | 近似 | 大小写混排顺序可能不同 |
| 实时树扁平化算法 | `file-tree-v2-model.ts:85-105` | `ReviewModels.kt:130-146` | 1:1 | 栈式 DFS 同构 |
| 每行 `+N/-N`（`total() > 0` 才渲染） | `U/ui/src/data-display/diff-changes/diff-changes.css:11-25`；`diff-changes.tsx:22` | `ReviewChangesTab.kt:77-94,166-183` | 近似 | gap 8 vs 6；Android 在总数为 0 时仍渲染汇总行 |
| 汇总 `+N/-N` 排版（compact 变体 sans 11 px/440/`line-height:1`/`letter-spacing:.05px`） | `diff-changes.css:11-25` | `ReviewChangesTab.kt:80-92`（mono 11 sp） | 近似 | compact 变体上游用 sans；Android 缺字距 |
| 状态字母 A/M/D（11/530、宽 16） | `file-tree-v2.tsx:47-51`；`U/session-ui/src/v2/components/session-review-file-preview-v2.tsx:48-58` | `ReviewModels.kt:101-105`；`ReviewChangesTab.kt:136-146` | 1:1 | 映射与排版一致 |
| 文件树变更徽标 | `file-tree-v2.tsx:114-122`；`file-tree.css:137-161` | — | 缺失 | `ReviewPanel.kt:148-154` 未把 diffs 下传给 Files 树 → Files tab 无 A/D/M 徽标 |
| 状态字母颜色（light `#198b43`/`#b82d35`/`#2c47c8`） | `file-tree.css:192-201` | `ReviewChangesTab.kt:138-142` | 1:1 | 一致 |
| 单文件行排版 | `session-review-v2.css:421-441` | `ReviewChangesTab.kt:147-165` | 近似 | path 13 px vs 11 sp；色 token 不同；行内 vs 两行 |
| 搜索/过滤输入框（`Filter files`，含键盘高亮导航） | `session-review-v2.tsx:88-123`；`session-review-v2.css:94-110`；`en.ts:19,132` | — | 缺失 | Android 无过滤（`A/ui/session/review/` 搜 `search|filter|query` 零命中） |
| 预览：diff vs 文本 | `session-review-file-preview-v2.tsx:216-252`；`U/session-ui/src/components/session-diff.ts:31-48` | `ReviewFilesTab.kt:341-377`（纯文本） | 近似 | Files tab 不显示 diff |
| unified / split 切换 | `session-review-v2.tsx:288-309`；`en.ts:23-24` | — | 缺失 | 无 split |
| expand / collapse 模式 + `expansionLineCount: 20` | `session-review-v2.tsx:266-287`；`session-review-file-preview-v2.tsx:214,223` | — | 缺失 | 无该状态 |
| 上下文折叠阈值 / 展开条 | `U/session-ui/src/pierre/index.ts:211,177-179` | — | 缺失 | 无分隔条 |
| 行号 | `pierre/index.ts:205,230`；移动端隐藏 `U/session-ui/src/components/file.tsx:970,1088` | — | 近似 | 手机端等价（都不显示）；未实现桌面分支 |
| Gutter / 变更指示条（2 px） | `pierre/index.ts:208,49-57,72-75` | `ReviewChangesTab.kt:238-240` | 缺失 | 无 gutter、无 2 px 条 |
| 词级高亮（`lineDiffType: "word-alt"`） | `pierre/index.ts:213-215,167-172` | — | 缺失 | 无 |
| 语法高亮与 Pierre 主题 | `pierre/index.ts:203-204`；`U/ui/src/theme/*/marked-theme.tsx:3-9` | — | 缺失 | 全行单色 |
| 换行 / 横向滚动（默认 wrap，可切；`mobileDiffWrap` 默认 true） | `pierre/index.ts:206`；`U/session-ui/src/components/session-review.tsx:655`；`view.tsx:173`；`U/app/src/settings/model.tsx:251` | `ReviewChangesTab.kt:218,236`（硬编码不换行） | 近似 | 上游默认换行且可切；Android 硬编码横向滚动，无设置项 |
| diff 字号/行高（`--diffs-font-size: 13px`、`--diffs-line-height: 24px`） | `pierre/index.ts:223-224`；`U/ui/src/styles/theme.css:8` | `ReviewChangesTab.kt:234-235`（**11 sp / 16 sp**） | 近似 | 13→11、24→16，字与行距都明显变小 |
| diff 字体族（IBM Plex Mono） | `pierre/index.ts:222`；`U/ui/src/styles/theme.css:4-5` | `ReviewChangesTab.kt:233`；`A/ui/theme/OcTheme.kt:359,307`（系统 mono） | 近似 | Android 用系统等宽，无 IBM Plex Mono |
| tab 宽度（`--diffs-tab-size: 2`） | `pierre/index.ts:225` | — | 未确认 | Android 无对应设置；Compose `Text` 对 `\t` 的实际渲染宽度未验证 |
| 新增/删除行配色（仅染**背景**：`color-mix(in lab, var(--diffs-bg) 60%, var(--diffs-addition-base))`，前景保持语法色） | `pierre/index.ts:25-34`；`marked-theme.tsx:9` | `ReviewChangesTab.kt:221-229`（整行**文字**染绿/红 + 背景 `stateSuccessBg`/`stateDangerBg`） | 近似 | 着色机制不同：上游只调背景、保留语法高亮前景；Android 文字直接变红绿、无语法色 |
| hunk 头与文件头（`disableFileHeader: true` 隐藏 `diff --git`/`index` 行） | `pierre/index.ts:212,216`；`session-review-file-preview-v2.tsx:228` | `ReviewChangesTab.kt:224-226` | 近似 | 上游隐藏文件头，Android 全部渲染 |
| 图片/媒体预览 | `session-review-file-preview-v2.tsx:245-250`；`U/session-ui/src/pierre/media.ts` | — | 缺失 | 无媒体分支 |
| 二进制预览文案（"Binary file (content cannot be displayed)"） | `session-review-file-preview-v2.tsx:277-286`；`U/ui/src/i18n/en.ts:45,44` | `A/res/values/strings.xml:384`（"Binary file — preview unavailable" + MIME） | 近似 | 文案自拟并追加 MIME |
| 截断与虚拟化（>500 行走虚拟化、不截断） | `U/session-ui/src/v2/components/session-review-file-preview-v2-virtualize.ts:1-4` | `ReviewFilesTab.kt:412-413,350-367`（硬截断 400 行 / 500 字符） | 近似 | Android 是硬截断而非虚拟化 |
| 树/列表虚拟化 | `file-tree-v2.tsx:158-178`；`U/app/src/session/files/list.tsx:65-85` | `ReviewFilesTab.kt:146-177`；`ReviewChangesTab.kt:76-114` | 1:1 | 均虚拟化，行高 28 对齐 |
| 复制路径 / 外部打开 | `file-tree-v2.tsx:269-274`；`session-side-panel.tsx:545`；`en.ts:35` | `ReviewFilesTab.kt:283-290`（复制正文） | 近似 | 无复制路径、无"用其它 App 打开" |
| select-for-context（从文件树把文件加入 composer 上下文） | `file-tree-v2.tsx:97-103`；`panel.tsx:248` | `A/ui/session/SessionScreen.kt:356`；`A/ui/session/composer/Composer.kt:66-67` | 近似 | Android 只能靠 composer 的 `@` 提及，无拖拽/勾选 |
| 撤销/丢弃单文件 | 上游面板内不存在（`session-review-v2.tsx:266-311` 全文已读） | — | 未确认 | 上游 v2 面板不提供；未检索 `U/app/src/shell/commands/`、`U/app/src/vcs/` |
| stage / unstage | — | — | 未确认 | 未在更大范围确认 |
| 行级评论 | `session-review-file-preview-v2.tsx:120-162,229-242`；`U/app/src/session/review/model.ts:211-246` | — | 缺失 | 核心能力缺失 |
| 文件间导航（prev/next + `{i}/{n}` 位置） | `session-review-v2.tsx:194-204,214-218,296-302`；`session-review-v2.css:277-294` | — | 缺失 | 无 |
| 工具条层 | `session-review-v2.css:233-248` | — | 缺失 | 无 |
| 单文件展开/折叠 | `U/session-ui/src/components/session-review.tsx:583,592`；`session-review.css:61-75` | `ReviewChangesTab.kt:100-111` | 1:1 | 语义一致 |
| 展开箭头旋转 | `session-review.css:165-174` | `ReviewChangesTab.kt:184-191` | 近似 | 方向一致；无过渡；色 token 不同 |
| 面板开关入口（`Toggle review`） | `U/app/src/runtime/i18n/en.ts:120`；`view.tsx:142-148` | `A/ui/session/SessionScreen.kt:281-287,418-440`；`strings.xml:254`（"Toggle review panel"） | 近似 | 上游切文件树，Android 切整面板；文案多 " panel" |
| 数据拉取时机（`filesystem.changed` 自动刷新） | `U/app/src/session/review/model.ts:93-110,123-134` | `A/ui/session/SessionScreen.kt:164-170` | 近似 | Android 仅开面板时拉一次（之后靠 SSE） |
| 变更来源口径（git / branch / turn 三种） | `model.ts:23,55-68` | `A/ChatViewModel.kt:683-695`；`A/OpenCodeClient.kt:784-788` | 近似 | 上游三口径，Android 单口径 |

---

## 8. Markdown 正文（`ui/markdown/`）

上游规格：`U/session-ui/src/components/markdown.css`。Android：`A/ui/markdown/MarkdownRenderer.kt`（`OcMarkdown`）。

| 上游组件/特性 | 上游证据(file:line) | Android 实现(file:line) | 状态 | 差异要点 |
|---|---|---|---|---|
| 正文基准（14 px / `line-height:160%`） | `markdown.css:16-17` | `MarkdownRenderer.kt:87-88`（14 sp、`fontSize*1.6`=22.4 sp） | 1:1 | 一致 |
| 标题 h1 17/600 `mt28 mb12`、h2 15/600 `mt24 mb10`、h3 13/500 `mt20 mb8`、h4–h6 13/500 muted | `markdown.css:42-78` | `MarkdownRenderer.kt:198-235` | 1:1 | 字号/字重/上下间距全部按 px→dp 1:1 |
| 段落 `p { margin-bottom: 12px }`（首末归零） | `markdown.css:28-39,88-90` | `MarkdownRenderer.kt:154-162` | 1:1 | 一致 |
| 列表（`padding-inline-start: 32px`(ul) / `2.25rem`(ol)、li `mb 8`、marker muted） | `markdown.css:104-153` | `MarkdownRenderer.kt:322-351` | 近似 | 缩进实现不同：Android 用 `Spacer(12.dp)` + 20/24 dp 标记列（合计 32/36 dp），上游是 `list-style-position: outside` 的 32 px（ul）/36 px（ol）；嵌套列表缩进（`li > ul { padding-inline-start: 1rem }`，css:156-165）Android 未实现 |
| 引用块（0.5 px 起始边线、`padding-inline-start: 0.5rem`、`margin: 1rem 0`、muted） | `markdown.css:168-174` | `MarkdownRenderer.kt:360-394` | 1:1 | 0.5 dp 边线 + 8 dp 内距 + 16 dp 上下距 |
| 代码块（`.shiki` 13 px / padding 12 / radius 6 / 0.5 px border） | `markdown.css:183-190` | `MarkdownRenderer.kt:262-288` | 近似 | 几何 1:1；Android **无语法高亮**（单色 `palette.base`），上游是 Shiki 主题着色 |
| 代码块复制按钮（hover 显现、11 px/530/12 px 药丸 + `elevation-floating`、`data-copied` 2 s） | `markdown.css:239-266,360-381` | `MarkdownRenderer.kt:246-305` | 近似 | 触屏替代方案（长按复制）合理；但 Android 的"已复制"药丸是 11 sp/`layer01`/无 `elevation-floating`/字号与字重未按 11 px/530/12 px 规格 |
| 行内代码（8 % `text-base` 混色底、radius 4、`px 4`、mono 500；`path`/`url` kind 分支） | `markdown.css:279-303` | `MarkdownRenderer.kt:403-421` | 近似 | 底色 8 % alpha + mono 一致；**无 `data-inline-code-kind` 的 path/url 分支**（path 应无色无底、url 应 accent 下划线） |
| 表格（`border-collapse`、`th/td` `0.5px border-muted` + `padding 12px`、th `border-base`、横向滚动） | `markdown.css:305-328` | — | 缺失 | Android 的 `MdBlock` 无 table 分支（`MarkdownRenderer.kt:153-177`）→ 表格会被当段落渲染 |
| 图片（`max-width:100%`、radius 4、`margin:1.5rem 0`、`cursor: zoom-in`） | `markdown.css:330-338` | — | 缺失 | Android 无图片块渲染，也无点击放大 |
| 任务列表 checkbox（绝对定位在 `inset-inline-start:-20px`、`top:4px`） | `markdown.css:132-144` | — | 缺失 | Android 无 checkbox 列表 |
| mermaid 图（`min-height:96px`、`padding:16px`、radius 6、`layer-02` 底、`svg min-width:480px`） | `markdown.css:198-223` | — | 缺失 | Android 无 mermaid |
| 逐词进入动画（`markdown-word-enter 180ms ease-out`，reduced-motion 关闭） | `markdown.css:19-25,341-354` | — | 缺失 | Android 无逐词淡入 |
| 流式渲染节奏（`TEXT_RENDER_PACE_MS=24`、`TEXT_RENDER_IMMEDIATE=512`、按 `[\s.,!?;:)\]]` 断点吸附） | `U/session-ui/src/message/message-content.tsx:78-160` | `A/ui/markdown/MarkdownRenderer.kt:91-97`（`coalesceLatest` 80 ms 窗口） | 近似 | 上游是 24 ms 一步、按标点吸附的"打字机"节奏；Android 是 80 ms 合并窗口（只降频，不做 pacing）→ 流式观感不同 |
| 主题（light/dark 双套色） | `markdown.css:317-321`（dark 下行内 code `opacity:.6`） | `MarkdownRenderer.kt:126-140` | 近似 | Android 无 dark 下 `opacity: .6` 规则 |

---

## 9. 统计

按状态计数（只统计第 1–8 节的差异表，不含本节的汇总行与第 10–13 节的表格）：

| 分区 | 行数 | 1:1 | 近似 | 缺失 | 未确认 |
|---|---|---|---|---|---|
| 1. 消息 / part 渲染类型覆盖 | 31 | 2 | 11 | 18 | 0 |
| 2. 工具卡细节 | 26 | 4 | 19 | 3 | 0 |
| 3. 时间线容器 | 21 | 6 | 10 | 5 | 0 |
| 4. Composer | 65 | 8 | 35 | 22 | 0 |
| 5. Dock | 63 | 19 | 30 | 14 | 0 |
| 6. 会话头 / 抽屉 | 55 | 12 | 29 | 14 | 0 |
| 7. Review 侧栏 | 73 | 6 | 46 | 18 | 3 |
| 8. Markdown 正文 | 15 | 4 | 6 | 5 | 0 |
| **合计** | **349** | **61** | **186** | **99** | **3** |

- **1:1 占比 17.5 %**（61/349）；**近似 53.3 %**（186/349）；**缺失 28.4 %**（99/349）；**未确认 0.9 %**（3/349）。
- 按必查清单 1–7 的口径（不含第 8 节 Markdown 附加节）：**334 行 / 1:1 57 / 近似 180 / 缺失 94 / 未确认 3**。
- 逐区看：**Dock 保真度最高**（1:1 占 30.2 %，19/63），**消息/part 渲染覆盖最差**（缺失占 58.1 %，18/31），**Composer 缺得最多**（22 项缺失，占 33.8 %），**Review 侧栏体量最大也最"近似"**（73 行中 46 行为 `近似`）。

**最集中的问题类型**（按行数）

1. **尺寸 / 字号未按 1:1 换算或取错档位**（14 px→16 dp、13 px→11 sp、240 px→220 dp、220 px→280 dp、28 px 行高→32 dp、16 px 环→14 dp、40 px 行→48 dp、抽屉行 40 px→48 dp 等）：约 35 行。
2. **整块能力缺失**（websearch dock、队列面板、斜杠命令、拖放/粘贴、上传进度、review 的 filter/split/行号/词级 diff/行评论、mermaid/表格/图片、时间线分隔条、消息元信息）：约 45 行。
3. **文案差异**（"Select one option" vs "Select one answer"、"Changes" vs "Files Changed {count}"、"No changes yet"、"just now" vs "Just now"、"5 minutes ago" vs "5m ago"、占位符 `...` vs `…`）：约 18 行。
4. **token 取错**（composer dock shell 用 `layer01` 而非 `surface-raised-stronger`、mention dark 取到品牌色、diff delete 取到 hover 值 / dark 值无对应 token、菜单分隔线 `border-base` vs `border-muted`、选中态 `iconBase` vs `layer-04`）：约 12 行。
5. **Android 自造项**（会话错误卡的可折叠标题、context 分组的 "Exploring/Explored"、模型选择器的"使用服务器默认"、question 的 per-question header 标签、工具 input 的 JSON 美化、会话抽屉本身）：约 8 行 —— 这些不是"缺失"，而是与上游行为不同向的额外实现，需要产品决策保留或移除。

---

## 10. 按「用户一眼能看出」排序的前 12 条差异

排序依据：出现在会话主界面的一级视觉区域、出现频次高、单帧截图即可辨识。

| # | 差异 | 上游 | Android | 为什么一眼能看出 |
|---|---|---|---|---|
| 1 | **工具卡全都变成了"有面卡片"** | `U/ui/src/components/collapsible.css:1-14` + `U/session-ui/src/components/basic-tool.css:314-318`：折叠体透明无边框，只有内容左缩进 12 px 当导轨 | `A/ui/session/timeline/PartRenderer.kt:198-203`：每张卡 `layer01` 填充 + 0.5 dp 描边 + radius 6 | 一屏十几张卡，整条时间线从"轻量文字流"变成"堆叠卡片"，视觉密度和节奏完全不同 |
| 2 | **`⋮` 菜单图标其实是横的 `⋯`** | `U/ui/src/icons/icon/icon.tsx:139-142`：`outline-dots` 三点在 `x=2.5/7.5/12.5, y=7.5–8.5` → **横向 ⋯** | `A/res/drawable/ic_oc_dots.xml:7-11`：`M12,5.5 / M12,12 / M12,18.5` → **纵向 ⋮** | 方向正好差 90°，是头部右上角唯一的图标按钮，单帧即可确认 |
| 3 | **上下文用量环是"死"的，而且永远空** | `U/app/src/session/timeline/session-context-usage.tsx:68-82` 用 `model.limit.context` 算百分比；`:93-108` 点击打开 context 面板；`:131-140` tooltip 显示 Cost / Usage % / Tokens | `A/ui/session/SessionScreen.kt:272` 传 `limit = null` → `ContextUsage` 恒为 null；`SessionHeader.kt:156-164` 的 Box 无 `clickable`、无 tooltip | 环永远是一条空轨道，且点了没反应 —— 用户会以为功能坏了（成本 / 百分比 / token 数三个数字在 Android 完全不可见） |
| 4 | **默认展开/分组策略相反** | 默认 preset 是 `compact`（`U/app/src/settings/model.tsx:248` → `U/session-ui/src/timeline/detail.ts:41-49`）：**所有**工具 + **所有** reasoning 都收进一个折叠分组 | `A/ui/session/timeline/ToolCardPolicy.kt:33-36`（`editExpanded=true`）+ `TimelineGrouping.kt:23`（只有 4 个工具进分组）+ `PartRenderer.kt:117-119`（reasoning 独立卡） | 同一段对话，上游显示 3 行摘要，Android 铺开十几张卡 |
| 5 | **分组行文案完全不同** | `Used {count} {tool names}`（`U/session-ui/src/tools/tool-renderer.tsx:535-558`），形如 "Used 3 Read, Glob, Grep" | "Exploring"/"Explored" + "3 reads, 2 searches, 1 list"（`A/ui/session/timeline/ContextToolGroupCard.kt:101-105,182-221`） | 这是折叠态下**唯一**可见的一行文字；且上游的 `Exploring`/`Explored` key（`U/ui/src/i18n/en.ts:93-94`）在全仓库 `*.tsx` 里**没有任何消费者**，Android 把一个死 key 当成了主文案 |
| 6 | **发送按钮是蓝色而不是对比色** | `variant="submit"`：light 深灰底/白图标，**dark 浅灰底/深图标**，带垂直高光渐变 + `elevation-button-contrast`（`U/ui/src/actions/submit.css:6-34`） | 扁平 `accent` `#3b5cf6` + 白色图标（`A/ui/session/composer/Composer.kt:315,328`） | composer 上唯一的实心按钮，且 dark 模式下明暗关系与上游**正好相反** |
| 7 | **移动端会话抽屉是侧滑面板，不是底部面板** | 底部面板：`inset-inline:0; bottom:0`、16 px 上圆角、28 px 拖拽柄 + 32×4 圆条、`max-height: min(75dvh,…)`、scrim、240/200 ms 动效（`U/app/src/shell/mobile-drawer.css:1-11,13-31,46-60`） | 侧滑 Material 抽屉：78 % 宽 / 上限 320 dp / 方形端边 / `bgBase`（`A/ui/session/SessionScreen.kt:194-213`） | 一次手势就能看出形态与手势习惯完全不同（且上游是"打开的标签"，Android 是"全部会话"） |
| 8 | **Review 侧栏在手机上占 340 dp 固定宽度** | 手机端根本不用侧栏，用四个整宽 tab（`U/app/src/session/screen.tsx:303-317`）；桌面侧栏是 240/200/480 可拖拽（`U/session-ui/src/v2/components/session-review-v2.tsx:17-19,135-146`） | `A/ui/session/SessionScreen.kt:443` 硬编码 `340.dp`，无拖拽、无复位、无持久化 | 打开评审后主对话区被压到不足半屏，且无法调整 |
| 9 | **时间线里没有任何"正在工作"的反馈** | `Working` shimmer 行（`U/app/src/session/timeline/message-timeline.tsx:376-391`）+ 「Jump to latest」浮动按钮（`virtualizer.tsx:646-673`） | 两者都没有（`A/ui/session/timeline/MessageTimeline.kt` 无对应代码） | 长任务时上游始终有一行动态文字；Android 在"已发送但还没有工具/思考行"的窗口里是完全静止的空白 |
| 10 | **助手/用户消息下方没有元信息** | 助手：复制按钮 + `Agent · Model · 时长 · Interrupted`（`message-content.tsx:511-554`）；用户：复制 + revert + `Agent · Model · 时间`（`:311-354`） | 完全没有（`A/ui/session/timeline/MessageTimeline.kt:250-286`、`PartRenderer.kt:95-103`） | 每条消息尾部都少一行 12 px 元信息 + 两个图标按钮 |
| 11 | **权限/问题 dock 的背景与描边都弱一层** | shell 用 `--surface-raised-stronger-non-alpha`（`#fff`/`#1c1c1c`）+ 1 px `--border-base`（`U/ui/src/components/dock-surface.css:2-3`）；tray 用 `--background-base`（`#f8f8f8`/`#101010`） | `layer01`（`#fafafa`/`#242424`）+ 0.5 dp `borderMuted`（8 % alpha）+ `bgBase` tray（`A/ui/session/dock/SessionPermissionDock.kt:94-95,161-166`） | dock 是打断式 UI，浮起感全靠这两层 token；Android 的 dock 会"陷"进背景 |
| 12 | **diff 用 11 sp 且整行染红绿** | 13 px/24 px 行高、IBM Plex Mono、只染**背景**并保留语法高亮前景（`U/session-ui/src/pierre/index.ts:25-34,222-224`） | 11 sp/16 sp、系统 mono、整行**文字**变 `stateSuccessFg`/`stateDangerFg`（`A/ui/session/review/ReviewChangesTab.kt:221-235`） | 评审面板主体就是 diff，字号与配色机制两点同时不同 |

**候选但未入前 12（避免超过 12 条）**：

- **question dock 一次把所有问题堆叠，没有分页**（`U/app/src/session/requests/session-question-dock.tsx:87-115,486-533` 的 `1 of 3 questions` + 进度段 + Back/Next vs `A/ui/session/dock/SessionQuestionDock.kt:175,186-201,224-229` 的静态 "Question" + 全竖排）——与第 11 条同属 dock 区，故让位。
- **composer 没有斜杠命令，却把 "/ for commands" 写在占位文案里**（`U/app/src/composer/suggestions/machine.ts:107-114`、`commands.tsx:49-82` vs `A/ui/session/composer/MentionText.kt:14` 只定义 `'@'`，而 `A/res/values/strings.xml:56` 仍写 `/ for commands`）——对用户是"承诺了不兑现"。
- `ReasoningCard` 恒显示 "Thought" 而缺少 streaming 的 "Thinking"（`PartRenderer.kt:392` vs `message-content.tsx:603-607`）。
- `⋮` 菜单缺 Export…（`SessionHeader.kt:317-336` vs `message-timeline.tsx:517-536`）。
- 会话错误卡自创可折叠标题（`SessionErrorCard.kt:66-158` vs `session-error.tsx:14-33`）。
- 会话抽屉没有日期分组、没有会话行长按菜单、没有 unread 提示（`SessionDrawer.kt:105-118` vs `U/app/src/home/sessions/view.tsx:148-156,694-701`、`project-avatar.css:107-122`）。

---

## 11. 「桌面专属、不适用移动端」的差异

判定标准：该上游特性依赖指针 hover、物理键盘修饰键、拖放、多窗口/大宽度、或宿主操作系统能力，在触屏手机上没有等价交互。

| 差异 | 上游证据 | 为什么桌面专属 |
|---|---|---|
| 拖放（dropzone）上传附件 | `U/app/src/composer/dropzone.tsx:26-86`；`attachments.ts:162-193` | 触屏没有"拖入窗口"的等价手势；Android 用 SAF 选择器（`AttachmentPicker.kt:49,74`）是正确的移动端替代 |
| 鼠标 hover 才显现的控件：正文复制按钮、错误卡复制按钮、代码块复制按钮、附件移除徽标、`collapsible-arrow`、diff 工具条、`session-turn-diffs-toggle` | `message-part.css:175-178,246-249`；`tool-error-card.css:120-123`；`markdown.css:246-248`；`U/app/src/composer/editor/editor.tsx:566-573`；`collapsible.css:27-32`；`session-turn.css:174-177` | `@media (hover: hover)` 在触屏恒不生效。Android 常显或改用长按是**合理适配**，这些**不应算保真缺陷**（本次审计仍按 `近似` 记录，但已注明触屏等价） |
| 物理键盘修饰键：`mod+Enter`（Steer/Queue）、`Cmd/Ctrl+↑`（编辑队首）、`mod+U`（附件）、`mod+.`/`shift+mod+D`（切 agent/variant）、`Ctrl+G`（停止）、`Shift+Enter` 之外的组合、`Ctrl+N/P`（弹层导航）、`Tab`（补全选中） | `U/app/src/composer/editor/editor.tsx:207-215,828-865`；`editor/interaction.ts:198-202,225-243`；`suggestions/machine.ts:208-229`；`commands.tsx:49-82` | 手机没有 Ctrl/⌘ 组合键。**注意例外**：`Shift+Enter` 换行（`Composer.kt:236-248` 已实现 ✓）与外接键盘下的 `Esc`、`⌘+Enter` 属于"移动端也可能有"，只是优先级低 |
| 附件行的左右渐隐遮罩（依赖 `animation-timeline` / `scroll-timeline`） | `U/app/src/composer/attachments/attachments.css:99-123`；`editor.tsx:600-607` | CSS scroll-driven animation 是浏览器能力；Compose 需要另写实现，**不属于移动端不适用**，但优先级低（视觉细节 24 px 渐变） |
| 消息导航轨 hover 卡片（`message-nav` compact → hover 展开完整列表） | `U/session-ui/src/components/message-nav.tsx:81-106`（`HoverCard` + `placement="right-start"`） | hover 触发；但**刻度条本体在移动端仍适用**（Android 完全缺失，算缺陷而非"不适用"） |
| 面板拖拽调宽 / 双击复位（review 侧栏、terminal） | `session-review-v2.tsx:135-146`；`U/ui/src/components/resize-handle/resize-handle.css` | 鼠标拖拽；但**移动端本就不该有侧栏**（见下一行），所以这条在移动端语境下不成立 |
| review 侧栏本身（桌面 dock 形态） | `U/app/src/session/files/session-side-panel.tsx:257`（`isDesktop()` 门控） | 上游自己把它门控在桌面；手机端上游用四个整宽 tab（`U/app/src/session/screen.tsx:303-317`）。**Android 的问题不是"缺了桌面侧栏"，而是"把桌面形态搬到了手机上"** |
| 内容居中 `md:max-w-[1000px] md:mx-auto` | `U/app/src/session/timeline/message-timeline.tsx:381`；`virtualizer.tsx:363` | ≥768 px 断点专属 |
| `md:px-5`（≥768 px 的 20 px 行内边距） | `U/app/src/session/timeline/message-timeline.tsx:185` | 同断点专属；移动端 `px-4`=16 px，Android 16 dp ✓ 1:1 |
| 行号（桌面显示，移动端隐藏） | `U/session-ui/src/pierre/index.ts:205,230`；移动端隐藏 `U/session-ui/src/components/file.tsx:970,1088` | 上游自己按平台分支；Android 缺桌面分支不算移动端缺陷 |
| unified/split diff 切换、`expand/collapse` 模式、工具条层 | `session-review-v2.tsx:266-309` | 这些控制在窄屏无法容纳（Android 只做 unified+折叠是合理的移动端取舍）；但**行号、词级高亮、背景着色机制**不属于此类，仍算缺陷 |
| 时间线内搜索、`session.header.openIn` 一整套"用外部编辑器打开"菜单（VS Code/Cursor/Zed/iTerm2/Ghostty/PowerShell…） | `U/app/src/session/timeline/search-bar.tsx`；`U/app/src/runtime/i18n/en.ts`（`session.header.open.app.*`） | 依赖宿主 OS 的已安装应用清单；Android 上没有意义 |
| 多窗口 / 标签页模型（`tabs`、`session.tab.add`、`shell/tabs/*`、`mobileTitlebarPosition`） | `U/app/src/shell/tabs/tabs.tsx`；`U/app/src/settings/model.tsx:250` | 上游的标签/多窗格模型是桌面窗口管理的抽象；Android 是单栈 Activity，用导航栈替代是正确的 |
| 项目菜单里的「项目设置 / 在文件管理器中显示 / Open in …」（`session-project-link`、`session.header.open.*`） | `session-identity-header.tsx:38-205`；`U/app/src/session/session-identity-header.css:32-55` | 依赖 `platform.openPath`（Electron/Tauri 能力）与 `platform.platform === "desktop"` 判定（`session-identity-header.tsx:57-58`）。**但头部左侧的"项目/目录"入口本身在移动端有意义**（Android 用 ☰ + 返回替代，属可接受取舍） |
| 后台任务"移到后台"提示 | `U/app/src/session/requests/background.ts`；`U/app/src/session/summary/background.tsx:38-60` | 上游的 background tasks 是桌面多会话并行概念 |
| 会话内列表的**右键**菜单（与长按共用同一实现） | `U/app/src/home/sessions/view.tsx:597-610,694-701` | 右键部分桌面专属；但**长按 500 ms** 那部分（`HOME_SESSION_LONG_PRESS_MS=500`，`view.tsx:30`）是明确为触摸设计的 —— 见下一节 |

**明确不属于"桌面专属"（Android 缺失即缺陷）**：

| 差异 | 上游证据 | 说明 |
|---|---|---|
| 会话列表行长按（500 ms）菜单 → Rename / Export… / Delete… | `U/app/src/home/sessions/view.tsx:30,597-610,694-701` | 上游**就是为触摸写的**（常量名与 500 ms 阈值）；Android 两个列表都只有 `clickable`（`SessionDrawer.kt:131-142`、`HomeSessionsList.kt:390-419`） |
| 粘贴上传 / 大段文本粘贴阈值（≥8000 字符或 ≥120 换行） | `U/app/src/composer/attachments/attachments.ts:127-161,274-277` | 手机粘贴截图/长文本是高频操作 |
| 附件上传进度与取消 | `U/app/src/composer/editor/editor.tsx:577-598`；`attachments.css:19-97` | 移动网络下更需要 |
| 上下文用量环的 tooltip 与点击入口（Cost / % / Tokens；打开 context 面板） | `U/app/src/session/timeline/session-context-usage.tsx:21-28,93-108,131-155` | 触屏可用长按替代 hover；上游本身就是 `IconButton` 点击，不依赖 hover |
| `⋮` 菜单缺 Export… | `U/app/src/session/timeline/message-timeline.tsx:526-528` | 导出可在手机上分享 |
| Working 行 / Jump to latest | `message-timeline.tsx:376-391`；`virtualizer.tsx:646-673` | 纯触摸手势增强，上游按钮本身就是 `onClick` |
| `question` dock 的进度段 / 分页 / Back / Next / 最小化 | `U/app/src/session/requests/session-question-dock.tsx:87-122,486-533` | 上游已经是移动优先的单选题交互 |
| dock 的 `blocked` 门控（有请求时隐藏 composer） | `U/app/src/session/requests/model.ts:69-73` | 触屏上误触输入框更糟 |
| 会话抽屉的日期分组 / unread 圆点 / 运行指示器抑制规则 | `U/app/src/home/sessions/view.tsx:148-156,421-441`；`U/app/src/shell/layout/project-avatar-state.ts:38-50` | 列表在手机上比桌面更长，分组与"哪个会话在等我"更重要 |
| Review 的 filter / 行号 / 词级 diff / 行评论 / Files 树 A-D-M 徽标 / no-git 空态 | `U/session-ui/src/v2/components/session-review-v2.tsx:88-123`；`pierre/index.ts:208-215`；`file-tree-v2.tsx:114-122`；`session-review-empty-no-git-v2.tsx:15-26` | 这些在窄屏同样可用，上游也没有按平台隐藏 |
| 消息元信息（Agent · Model · 时长）、工具卡的参数槽 `k=v` | `U/session-ui/src/message/message-content.tsx:511-521,548-552`；`U/session-ui/src/components/basic-tool.tsx:359-371` | 纯信息展示，与输入方式无关 |
| `@` 候选的 agent / skill / reference 类别 | `U/app/src/composer/editor/editor.tsx:905-914` | 触屏点选比键盘更自然 |
| 时间线分隔条（模型切换 / 压缩 / 中断） | `U/session-ui/src/components/timeline-separator.tsx:1-33` | 纯视觉，与平台无关 |

---

## 12. 未确认项汇总

| # | 未确认内容 | 为什么无法确认 / 已搜过哪里 |
|---|---|---|
| 1 | `--diffs-tab-size`（`U/session-ui/src/pierre/index.ts:225`，值 2）在 Android 的等价行为 | Android 无对应设置（`ReviewChangesTab.kt`、`ReviewFilesTab.kt`、`A/ui/theme/OcTheme.kt` 均无 tab 宽度）→ Compose `Text` 对 `\t` 的实际渲染宽度需真机验证 |
| 2 | 上游 review 面板是否在别处（`shell/commands/`、`vcs/`）提供撤销/丢弃单文件与 stage/unstage | 上游 v2 面板内不存在（`U/app/src/session/review/` 6 个文件全文已读，零命中）；未扩大到 `U/app/src/shell/commands/`、`U/app/src/vcs/`、`U/packages/core` |
| 3 | `--diffs-addition-base` / `--diffs-deletion-base` 的确切色值 | 由 `@pierre/diffs` 运行时提供，`_upstream/**/*.css` **零命中**；只能确认混合公式（`pierre/index.ts:25-34`）与 `--diffs-bg` 来源（`U/ui/src/styles/theme.css:97` `#fcfcfc`、`:368` `#151515`） |
| 4 | Android dark 语法色 `#fab283`/`#fcd53a`/`#edb2f1` 是"移植取错 key"还是"刻意跟随某个版本" | `A/ui/theme/OcTheme.kt:11-18` 声称取自 vendored 构建 `index-D_rl_FNw.css`，**该文件不在本仓库**；三个值确实存在于 `U/ui/src/theme/themes/oc-2.json:261-265` 的 palette `primary`/`warning`/`info`（品牌/状态角色），但没有任何 `syntax-*` token 等于它们 |
| 5 | 上游 permission dock 是否在别处提供 Esc 关闭 | `U/app/src/session/requests/session-permission-dock.tsx` 全文无 `onKeyDown`；未检索全局快捷键注册表 |
| 6 | `--v2-background-bg-button-neutral` 的具体取值（影响 `ButtonV2 variant="neutral"`） | `U/ui/src/styles/tokens/theme.css:15` → `--v2-grey-50`；Android 用 `#ffffff`/`#ffffff0f`（`A/ui/theme/OcTheme.kt:105,162`）——需确认 `--v2-grey-50` 的 dark 值才能判定是否一致 |
| 7 | 移动端空态应归属上游哪一套文案（桌面 `SessionReviewEmptyChangesV2` vs 移动 tab 的 `ReviewEmpty`） | 两者在上游都存在（`U/app/src/runtime/i18n/en.ts:32-33` 与 `:798`），需要产品定论 |
| 8 | `session-review-v2-sidebar-tree` 的自定义滚动条与上下渐隐遮罩是否属于必须复刻的规格 | `U/session-ui/src/v2/components/session-review-v2.css:124-222`；Android 未实现，是否算差异未确认 |
| 9 | 会话列表行标题的**回落规则**（去 `New session - <ISO>` / `Child session - <ISO>` 时间戳） | `U/app/src/session/title.ts:2` 与 `U/app/src/shell/titlebar/tab-title.ts:1` 引用 `@opencode/util/session-title-fallback`，但 `_upstream/packages/util/` **不在本 checkout**；只能确认 header 走 `sessionTitle()`，列表行的 `displayLabel` / `isFallbackTitle` 实现未读到 |
| 10 | Android `ChatStore.UiMessage.tokens` 的聚合口径是否逐字段等价上游 `input+output+reasoning+cache.read+cache.write` | 只读了消费端 `A/ui/session/header/ContextUsage.kt:31-39`，未回溯 `ChatStore` 的赋值路径 |
| 11 | Android 抽屉宽度 78 % / 上限 320 dp 的"上游依据" | `A/ui/session/SessionScreen.kt:448-452` 引用的是旧 View shell 的 `ChatView.DRAWER_WIDTH_*`；在 `_upstream/packages/app/src` 未找到对应常量 |
| 12 | 运行指示器 reduced-motion 下"只留中心一点"的最终像素落点 | 只按 SVG 属性（`x=7.5 y=7.5 w=2 h=2`，viewBox 16）推断，未截图验证 |
| 13 | `session.update` 的服务端 HTTP 形状（请求/响应体） | 上游只读到 SDK 调用 `U/app/src/session/timeline/controller.tsx:151-152`，未读 server 端实现；Android 用 `PATCH /session/:id {title}`（`A/OpenCodeClient.kt:659-661`） |
| 14 | Android dark 语法色是否影响 timeline/工具卡（不只是 composer mention） | `A/ui/theme/OcTheme.kt:141-148` 的 dark `syntaxKeyword/syntaxProperty/syntaxType` 同时被 `MarkdownRenderer` 之外的组件读取；本次只核对了 mention 使用点 |
| 15 | `--v2-grey-50` 的 dark 取值（决定 `ButtonV2 variant="neutral"` 是否 1:1） | `U/ui/src/styles/tokens/theme.css:15` → `--v2-grey-50`；Android 用 `#ffffff`/`#ffffff0f`（`A/ui/theme/OcTheme.kt:105,162`），未定位 `--v2-grey-50` 的 dark 定义 |
| 16 | 上游独立 sidebar / 会话列表组件是否存在 | `Get-ChildItem U/app/src -Recurse | ? Name -match "sidebar|session-list|session-row|recent"` 无结果；`sidebar.*` 只是遗留 i18n key（`U/app/src/runtime/i18n/en.ts:960,967`）→ 会话列表只有 Home 一处实现 |
| 17 | 上游 `Intl.RelativeTimeFormat` / `formatDistance` / `timeAgo` 是否存在 | `U/app/src` 全量 grep 无命中；相对时间只由 `U/app/src/shell/time.ts:9-22` 手写分档 → Android 的 `SessionTimeLabel.kt` 是等价重写，非移植 |

---

## 13. 附录：Android 代码注释/文档里引用但**上游不存在**的"规格"

这些不是 UI 差异，但会误导后续开发，单列出来：

| Android 处引用 | 声称的上游来源 | 实际情况 |
|---|---|---|
| `A/ui/session/timeline/ToolCardPolicy.kt:7,70` | `packages/session-ui/src/components/part-default-open.ts` | 该文件在 `_upstream/` 中**不存在**（grep `partDefaultOpen`/`part-default-open` 于 `_upstream/packages/session-ui/src` 与 `_upstream/packages/app/src`：零命中）。实际逻辑在 `U/app/src/session/timeline/controller.tsx:124-131,263-264` 与 `U/session-ui/src/timeline/projection.ts:656-688` |
| `A/ui/session/timeline/TimelineGrouping.kt:6,88` | 上游 `groupParts()` / `CONTEXT_GROUP_TOOLS` / `contextToolSummary()` | 三个名字在 `_upstream/` 中**全部零命中**。上游实际入口是 `U/session-ui/src/timeline/projection.ts:590-654`（`groupParts` 语义的匿名函数）与 `:656-688`（`toolGroupType`）；且分组规则**由 `timelineDetail` 决定**，不是硬编码 4 个工具 |
| `A/ui/session/timeline/ContextToolGroupCard.kt:60-62,98-100,169-172` | `context-tool-group-label`="Exploring"/"Explored"、`context-tool-group-summary`="3 reads, 2 searches, 1 list" | `ui.sessionTurn.status.gatheringContext`("Exploring")/`gatheredContext`("Explored") 在 `U/session-ui/src` 与 `U/app/src` 的 **所有 `*.tsx` 中零消费者**（死 key）；`AnimatedCountList`/`tool-count-summary` **只在 stories 里使用**（`U/session-ui/src/components/tool-count-summary.stories.tsx:14-16,73-77`），组件本身从 `package.json:29` 导出但没有生产消费者。上游真实实现是 `Used {count} {tool names}` |
| `A/ui/session/header/SessionHeader.kt:71-82` | `research/02 §2.2` 的头部结构（`session-title-parent` / `session-title-child` / context ring / ⋮） | 结构顺序与上游一致，但 Android 注释把"back chevron"映射到 `session-title-parent` —— 上游 `session-title-parent` 是**父会话标题按钮**（`session-identity-header.tsx:291-300`），不是返回键 |
| `A/ui/session/review/ReviewPanel.kt` 注释（据 D 组审计记录） | "对齐上游 sidebar 的 `border-inline-start`" | 上游是 `border-inline-**end**`（`U/session-ui/src/v2/components/session-review-v2.css:33`），且外框是 `rounded-[10px]` + `elevation-raised` 无边框（`session-side-panel.tsx:263`） |
| `A/ui/session/timeline/SessionErrorCard.kt:62-64` | "§3.1 判定无左竖条" | `U/ui/src/components/card.css:39-48` 里 `[data-component="card"]::before` **确实**画了一条 2 px `--card-accent` 竖条，错误卡的 `--card-accent` = `--v2-state-fg-danger`（`U/ui/src/components/card.tsx:47-56`）。上游错误卡是有左竖条的 |
| `A/ui/session/header/ContextRing.kt:18-20` | `ProgressCircleV2 size=14, strokeWidth=1.5` | 那是 `appearance="compact"` 的规格；`SessionContextUsage` 用的是 `appearance="indicator" size={16} strokeWidth={2}`（`U/app/src/session/timeline/session-context-usage.tsx:110-124`） |
| `A/ui/session/composer/ModelPicker.kt`（"使用服务器默认"） | 上游 `ModelSelectorPopoverView` 底部项 | 上游 picker 底部是「Manage models」（`U/app/src/providers/models/select-dialog.tsx:510-522`）；"使用服务器默认"是 Android 自造项，上游没有对应 UI |
| `A/ui/session/composer/ModelChip.kt:69-71` | `prompt-input-v2.tsx` `PromptInputV2ModelControl` | 该文件不存在于 `_upstream/`；上游 composer 在 `U/app/src/composer/composer.tsx` |
| `A/ui/home/HomeSessions.kt:93-96`、`A/ui/home/HomeSessionsList.kt:226-228` | 上游会话搜索用 **fuzzysort** | 上游是大小写不敏感**子串**匹配（`U/app/src/home/sessions/search.ts:30-38`）+ `ses_` ID 精确兜底（`:11`），没有 fuzzysort |
| `A/ui/session/drawer/SessionScreen.kt:448-452`（`DRAWER_WIDTH_RATIO` / `DRAWER_WIDTH_MAX`） | 抽屉宽度来自上游 | 引用的是旧 View shell 的 `ChatView.DRAWER_WIDTH_*`；上游 v2 的移动抽屉是底部 sheet，无宽度常量（`U/app/src/shell/mobile-drawer.css:13-31`） |
| `A/ui/session/header/ContextRing.kt:16-24` | `ProgressCircleV2` "v2 appearance" = size 14 / strokeWidth 1.5 | 14/1.5 是 `appearance="compact"` 的规格（`U/ui/src/feedback/progress-circle/progress-circle.tsx:15-19`）；`SessionContextUsage` 实际用 `appearance="indicator" size={16} strokeWidth={2}`（`U/app/src/session/timeline/session-context-usage.tsx:110-124`） |
| `A/ui/session/timeline/PartRenderer.kt:60-63`（"View-era adapter `ChatView.MessagesAdapter.AssistantHolder.bind`"） | 移植基准 | 该 View 实现是**本仓库的旧代码**，不是上游；把它当基准会把旧 View 壳的偏差（如工具卡的有面外观、单色 diff、`$ cmd` 文本体）当成规格带进来 |

---

## 14. 方法学备注（供复核）

- 全部结论来自 `read`（带行号）与 `grep`（ripgrep 行号）双向核对；本文件的行号与 `read`/`grep` 工具输出一致。
- 上游 px 与 Android dp 的换算：本次审计把 `1 px ↔ 1 dp` 视为 1:1（移动端密度下等价），字号 `px ↔ sp` 同理；凡未按 1:1 换算的都在差异要点里显式标注（如 `240 px → 220 dp`、`220 px → 280 dp`、`13 px → 11 sp`、`40 px → 48 dp`）。
- 上游"存在的组件"不等于"生产中被使用"：本审计对 `AnimatedCountList`/`tool-count-summary`、`ui.sessionTurn.status.gatheringContext`/`gatheredContext`、`ui.sessionTimeline.notice.*`、`session.messages.loadEarlier`、`session.question.pending` 等 key 都做了**消费者检索**，结果已写入对应行与第 13 节。
- 第 11 节的"桌面专属"判定只依据上游是否用 `@media (hover: hover)` / `isDesktop()` / 物理修饰键 / 宿主 OS 能力分支；未依赖主观判断。
- 本次审计**未**运行任何构建、测试或截图比对，因此所有"视觉一致/不一致"的判断都是**规格级**（读 CSS/组件树）而非像素级。标记为 `1:1` 的条目表示"规格值逐项相同"，不表示渲染结果已实测一致。
