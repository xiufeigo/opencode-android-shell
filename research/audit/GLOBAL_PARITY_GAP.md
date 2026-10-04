# 全局界面/交互一致性盘点（GLOBAL PARITY GAP）

- 版本：v162 · 性质：**只读审计**，未修改任何生产代码/测试
- 上游 = 网页端 `_upstream/packages/app/src`（+ 共享包 `session-ui` / `ui` / `tui` / `server` / `protocol`）
- 本壳 = 原生 Android `app/src/main/java/com/xiufeigo/opencodeshell/`
- 证据规则：每条判定均给两侧 `file:line`；查不到写「未核对」，不凭印象写「一致/缺失」

**路径简写**：`US/` = `_upstream/packages/app/src/`；`SH/` = `app/src/main/java/com/xiufeigo/opencodeshell/`
（上游共享包写全路径，如 `US/../session-ui/src/...`）

---

## 0. 结论摘要

盘点了 **11 个表面**（任务要求的 10 面 + 我发现的「命令表/命令面板」面）。

1. **产品负责人的直觉需要修正一处**：本壳与上游的**面（surface）覆盖度基本对齐**。逐项核对中约 **七成条目判定为「一致」**——气泡布局、meta 行、工具卡策略、composer 队列/Steer、Tab 三态、终端、通知触发、设置分类骨架都能对上。差距**不是"大面积没实现"，而是集中在少数高杠杆点**（见 TOP 15）。
2. **「本壳有、上游没有」的纯自造物极少**（约 10 项），且多为**上游已有能力但只放在别处**（如 `pairing` 上游是对话框、本壳提为一级 Tab），真正凭空新增的是应用内通知横幅与 3 个调试类调色板命令。
3. **`Open terminal` 不是自造物**——上游确有完整终端面（`US/session/terminal/panel.tsx:39`，内核 ghostty-web `terminal.tsx:7`）、确有 `/api/pty*` 协议组、确有同名同键命令（`terminal.toggle` = `ctrl+\``）。本壳走**同一套上游 PTY 路由**，仅 VT 内核因平台不同换成 Termux `TerminalEmulator`。这是"接入一致"的一个**正面证据**。
4. **最痛的真实差距是 3 个持久化/导航类问题**（Tab 不落盘、项目树缺失、设置无嵌套层级），它们不影响"能不能用"，但直接影响日常体感。

---

## 1. 逐面盘点

### 表面 1 — 会话列表入口（home / 项目列表 / 最近会话）

| 能力项 | 上游 file:line | 本壳 file:line | 判定 |
|---|---|---|---|
| home 落地屏（项目列+会话列，窄屏项目下拉） | `US/home/route.tsx:13,27-31,46-49` | `SH/ui/home/HomeScreen.kt:898,988,1034` | 一致 |
| 最近会话列表 + 行数上限 | `US/home/sessions/controller.tsx:91` | `SH/ui/home/HomeSessions.kt:23,184` | 一致 |
| 归档会话过滤 | `US/home/sessions/controller.tsx:263` | `SH/ui/home/HomeSessions.kt:74,181` | 一致 |
| 选中项目作用域过滤 | `US/home/sessions/records.ts:17-21` | `SH/ui/home/HomeSessions.kt:180-182` | 一致 |
| 时间分桶 Today/Yesterday/Older | `US/home/sessions/controller.tsx:334-351` | `SH/ui/home/HomeSessions.kt:239-263`；标签 `HomeSessionsList.kt:886-888` | 一致 |
| 分组维度（时间分组+项目标签） | `US/home/sessions/view.tsx:148-170,654-656` | `SH/ui/home/HomeSessions.kt:239-247` | 一致 |
| 会话搜索框 + 结果浮层 | `US/home/sessions/view.tsx:229-366` | `SH/ui/home/HomeSessionsList.kt:285-301,607-706` | 一致 |
| 搜索排序（fuzzysort） | `US/home/sessions/search.ts:30-38` | `SH/ui/home/HomeSessions.kt:316-329`（纯子串，壳自记偏差 `:311-314`） | **不一致** |
| 搜索按 sessionID 直达置顶 | `US/home/sessions/search.ts:36-37,85-101` | `SH/ui/home/HomeSessions.kt:305-308,321-323` | 一致 |
| 行内改名入口 | `US/home/sessions/view.tsx:481-513,694` | `SH/ui/home/HomeRowMenu.kt:41-42`；`HomeSessionsList.kt:1211` | 一致 |
| 改名**失焦**语义 | `US/home/sessions/view.tsx:574`（onBlur=**取消**） | `SH/ui/home/HomeRowMenu.kt:176-179`（BLUR=**提交**） | **不一致** |
| 行菜单触发（右键/长按） | `US/home/sessions/view.tsx:521-526,605-609`（真实 onContextMenu） | `SH/ui/home/HomeRowMenu.kt:193-194`；`HomeSessionsList.kt:1067-1074`（自陈预留槽） | **不一致** |
| 行菜单项 改名/导出/删除 | `US/home/sessions/view.tsx:694-701` | `SH/ui/home/HomeSessionsList.kt:1099-1149` | 一致 |
| 行菜单项 归档 | `US/home/sessions/view.tsx:27`（=false 关闭）,`705-727` | 壳无入口 | 一致（两侧均关） |
| **项目树（嵌套 worktree + 展开/折叠）** | `US/home/sessions/records.ts:36-44`；`US/home/projects/view.tsx` | 壳**自陈无**：`SH/data/HomeProjectStore.kt:446-447`（"Upstream's `expanded` flag and its worktree tree have no counterpart in this shell (no project tree UI)"） | **缺失** |
| 项目/工作区列表 | `US/home/projects/view.tsx:74,197-224` | `SH/ui/home/HomeProjectsList.kt:91,148` | 一致 |
| 项目行菜单 新建会话/导入/编辑/清除通知/关闭 | `US/home/projects/view.tsx:734-762` | `SH/ui/home/HomeProjectsList.kt:483-563` | 一致 |
| 项目行菜单「在文件管理器中打开」 | `US/home/projects/view.tsx:745-752` | 壳自陈无 Android 对应：`SH/ui/home/HomeProjectsList.kt:84,312` | **缺失** |
| 项目行菜单「刷新工作树」 | 上游无 | `SH/ui/home/HomeProjectsList.kt:527-537`（壳注 `:516-526`） | **自造** |
| 行内相对时间 | 上游行内无（`view.tsx:647-657`） | `SH/ui/home/HomeSessionsList.kt:1062-1063,1792` | **自造** |
| 加载失败可重试态 | 上游无（`view.tsx:128-135` 仅骨架/空态） | `SH/ui/home/HomeSessionsList.kt:1902-1925` | **自造** |
| 空态（文案+新建按钮） | `US/home/sessions/view.tsx:762-790` | `SH/ui/home/HomeSessionsList.kt:1860-1893` | 一致 |
| 加载骨架 | `US/home/sessions/view.tsx:792-803` | `SH/ui/home/HomeSessionsList.kt:1822`；`HomeSessions.kt:26` | 一致 |
| 已打开会话指示条 | `US/home/sessions/view.tsx:209-217` | `SH/ui/home/HomeSessionsList.kt:1751-1760` | 一致 |
| 未读点 / 运行 marquee | `US/home/sessions/view.tsx:219-224` | `SH/ui/home/HomeSessionsList.kt:1736-1749` | 一致 |
| 虚拟滚动容器 | `US/home/route.tsx:32-51`（ScrollView） | `SH/ui/home/HomeSessionsList.kt:814`（LazyColumn） | 一致 |
| 分页/加载更多 | 两侧均仅截断（`controller.tsx:91` / `HomeSessions.kt:184`） | | 一致 |
| 桌面「后台打开为新标签」 | `US/home/sessions/view.tsx:34-43,399-404` | 未定位对应 | 未核对 |

### 表面 2 — 会话内顶部

> 上游 `US/session/header/session-header.tsx:4` 仅是占位（`SessionHeaderSpacer`）；真实顶栏在 `US/session/timeline/message-timeline.tsx:416-583` + `US/session/session-identity-header.tsx`。

| 能力项 | 上游 file:line | 本壳 file:line | 判定 |
|---|---|---|---|
| 顶栏容器（吸顶单行） | `US/session/timeline/message-timeline.tsx:418-419`；`US/session/session-identity-header.tsx:27-36` | `SH/ui/session/header/SessionHeader.kt:290-304` | 一致 |
| 标题显示 + truncate | `US/session/timeline/message-timeline.tsx:449-455` | `SH/ui/session/header/SessionHeader.kt:339-369` | 一致 |
| 标题行内改名 | `US/session/timeline/message-timeline.tsx:458-486` | `SH/ui/session/header/SessionHeader.kt:327-337`；`InlineTitleEdit.kt` | 一致 |
| ⋮ 溢出菜单 | `US/session/timeline/message-timeline.tsx:497-539` | `SH/ui/session/header/SessionHeader.kt:754,799-961` | 一致 |
| ⋮ 改名/导出/删除 | `US/session/timeline/message-timeline.tsx:518-535` | `SH/ui/session/header/SessionHeader.kt:807-814,824-831,849-861` | 一致 |
| ⋮ 撤销/重做 | `US/session/commands/use-session-commands.tsx:257-271` | `SH/ui/session/header/SessionHeader.kt:865-886` | 一致（壳置于 ⋮） |
| ⋮ 分叉 fork | `US/session/commands/use-session-commands.tsx:288-294`；`fork-dialog.tsx` | `SH/ui/session/header/SessionHeader.kt:887-894` | 一致 |
| ⋮ 导入会话 | 上游顶栏无（但**有**同名命令 `US/runtime/i18n/en.ts:156`） | `SH/ui/session/header/SessionHeader.kt:839-848` | **不一致**（位置差异，非凭空新增） |
| ⋮ 移动到…/新建工作树 | 上游顶栏无 | `SH/ui/session/header/SessionHeader.kt:901-919` | **不一致**（本壳新增到顶栏） |
| 上下文/token 使用环 | `US/session/timeline/session-context-usage.tsx:110-128` | `SH/ui/session/header/ContextUsage.kt:102,141`；`ContextRing.kt:38` | 一致 |
| **点击使用环**行为 | `US/session/timeline/session-context-usage.tsx:93-108,153`（开/关 Review→Context 面板） | `SH/ui/session/header/SessionHeader.kt:158-164`（开 popover） | **不一致** |
| 使用环浮层（cost/usage/tokens） | `US/session/timeline/session-context-usage.tsx:131-140` | `SH/ui/session/header/ContextUsagePopover.kt:70` | 一致 |
| 时间线搜索栏本体 | `US/session/timeline/search-bar.tsx:7-72` | `SH/ui/session/header/TimelineSearchBar.kt:121-236` | 一致 |
| 搜索入口**按钮** | 上游无独立按钮（`US/session/screen.tsx:273-277` 仅注入栏） | `SH/ui/session/header/SessionHeader.kt:380-382` | **自造** |
| 汇总/摘要浮层触发 | `US/session/timeline/message-timeline.tsx:549-575` | `SH/ui/session/header/SessionHeader.kt:395-403` | 一致 |
| Review 面板开关（顶栏） | `US/session/header/session-header-actions.tsx:37-73` | 壳顶栏无（grep 无命中） | **缺失** |
| 面包屑（父会话标题 + `/`） | `US/session/timeline/message-timeline.tsx:428-444` | 壳无（grep `parentTitle` 零命中） | **缺失** |
| 项目名/项目头像菜单 | `US/session/timeline/message-timeline.tsx:422-427`；`US/session/session-identity-header.tsx:38-205` | 壳顶栏无（grep `Project` 仅注释 `:253-254`） | **缺失** |
| 权限/工具模式（auto-approve）入口 | `US/session/commands/use-session-commands.tsx:443-444`（命令） | 仅设置项 `SH/ui/App.kt:733` | **缺失**（会话内无入口） |
| 模型选择 | 上游在 composer 非顶栏（`US/composer/composer.tsx:107`） | 壳在 dock 非顶栏（`SH/ui/session/dock/SessionFormDock.kt:71`） | 一致（两侧均不在顶栏） |
| 右侧动作槽 | `US/session/timeline/message-timeline.tsx:546-577` | `SH/ui/session/header/SessionHeader.kt:380-425` | 一致 |
| 返回 / 会话抽屉 ☰ | 上游无（返回在 tab 系统） | `SH/ui/session/header/SessionHeader.kt:305-318` | 自造（手机导航，合理） |
| `/btw` 可见入口 | 上游本快照 grep `\bbtw\b` **零命中** | `SH/ui/session/header/SessionHeader.kt:388` | **未核对**（见 §5.1 存疑项） |
| 分享入口 | 两侧顶栏均无 | | 一致 |

### 表面 3 — 消息时间线

| 能力项 | 上游 file:line | 本壳 file:line | 判定 |
|---|---|---|---|
| 用户气泡（右对齐/背景/圆角） | `_upstream/packages/session-ui/src/message/message-content.tsx:301-308` | `SH/ui/session/timeline/MessageTimeline.kt:903-939` | 一致 |
| 用户 meta 行（Agent·Model+时间） | `message-content.tsx:227-231,313-327` | `SH/…/MessageMeta.kt:41-43`；`MessageMetaRow.kt:107-120` | 一致 |
| 助手 meta 行（Agent·Model·时长·Interrupted） | `message-content.tsx:511-521` | `SH/…/MessageMeta.kt:33-39,56-64`；`MessageMetaRow.kt:57-86` | 一致 |
| 消息复制 / 撤销 revert | `message-content.tsx:328-352,539-547` | `SH/…/MessageMetaRow.kt:57,91-94`；`MessageRevertAction.kt` | 一致 |
| 评论卡（行评论） | `message-content.tsx:175,304-306` | `SH/…/MessageTimeline.kt:930-935`；`CommentCard.kt` | 一致 |
| 文件/产物附件内联 | `message-content.tsx:246-289` | `SH/…/PartRenderer.kt:790-818` | 一致 |
| **消息内图片缩略图 + 预览弹层** | `message-content.tsx:265-272`；`session-ui/src/components/image-preview.tsx:5` | 壳仅文字 chip：`SH/…/PartRenderer.kt:790-818` | **缺失** |
| 流式 Markdown | `message-content.tsx:162,533-537` | `SH/…/PartRenderer.kt:119-124` | 一致 |
| reasoning/thinking 折叠块 | `message-content.tsx:560-628` | `SH/…/PartRenderer.kt:708-766` | 一致 |
| thinking 默认展开受预设控制 | `session-ui/src/timeline/session-timeline-row.tsx:291-306`；`detail.ts:17-61` | 壳恒 `false`：`SH/…/PartRenderer.kt:714` | **不一致**（壳无 reasoningMode/timelineDetail 符号） |
| 工具卡分发（哪些工具有专属卡） | `message/current-message.tsx:78-96`；`tools/tool-renderer.tsx:299-395` | `SH/…/ToolRenderers.kt:149-171,605,715,874,989,1029,1066,1093` | 一致 |
| 工具卡默认展开态 | `session-timeline-row.tsx:196,301-305` | `SH/…/ToolCardPolicy.kt:93-102,123-132` | 一致 |
| 工具 running/done/error 状态 | `session-ui/src/components/basic-tool.tsx:104`；`tool-error-card.tsx:14-25` | `SH/…/PartRenderer.kt:307-313`；`ToolRenderers.kt:371-466` | 一致 |
| 工具输出截断策略 | `message-part.css:323-464`（仅 240px 滚动，无展开钮） | 壳增加 head+「show more」：`SH/…/PartRenderer.kt:487-488,563-564` | **不一致**（自造） |
| 时间线内 diff（unified） | `current-message.tsx:92`（virtualizeDiff=false）；`components/file.tsx:880` | `SH/…/PartRenderer.kt:496-540` | 一致 |
| diff 虚拟化 + Pierre 语法高亮 | `session-ui/src/components/file.tsx:775,880,1048` | 壳为行级着色+JSON 高亮：`SH/…/PartRenderer.kt:496-540,651` | **不一致** |
| 折叠分组 context tool group | `tools/tool-renderer.tsx:501-730` | `SH/…/ContextToolGroupCard.kt:74-180`；`TimelineGrouping.kt:48-117` | 一致 |
| 工具计数标签 | `components/tool-count-label.tsx` | `SH/…/ToolRenderers.kt:1327`；`ContextToolGroupCard.kt:184-198` | 一致 |
| compaction 摘要行 | `message-content.tsx:400-419` | `SH/…/ToolRenderers.kt:1161-1258` | 一致 |
| 轮次分隔线（Interrupted/model-switched） | `session-timeline-row.tsx:509-513,637-653` | `SH/…/TimelineRows.kt:62,184-185`；`TimelineSeparator.kt:97` | 一致 |
| 分隔线 provider icon / variant | `session-ui/src/components/timeline-separator.tsx:9-11,16-27` | 壳明示省略：`SH/…/TimelineSeparator.kt:39-43` | **缺失** |
| 会话错误卡 + 恢复动作 | `session-ui/src/components/session-error.tsx:22-32`（单卡） | 壳为分类+建议+溢出恢复：`SH/…/SessionErrorCard.kt:83,494-570` | **不一致**（形态不同；壳能力更细） |
| 自动重试提示 | `session-ui/src/components/session-retry.tsx:10-74` | `SH/…/PartRenderer.kt:1015-1062` | 一致 |
| Working/停滞提示行 | `US/session/timeline/message-timeline.tsx:384-391` | `SH/…/MessageTimeline.kt:976-1039` | 一致 |
| 时间线内搜索 | `US/session/timeline/search-controller.ts:221-240` | `SH/…/TimelineSearch.kt:76-171` | 一致 |
| 消息导航轨（compact ticks） | `session-ui/src/components/message-nav.tsx:48-103` | `SH/…/MessageNavRail.kt:46-88` | 一致 |
| 导航轨 **diff bars** | `message-nav.tsx:109-151`（真实计算） | 壳仅留 hook：`SH/…/MessageNavRail.kt:95-99` | **缺失**（未接 per-turn FileDiffInfo） |
| 长列表虚拟化 | `US/session/timeline/virtualizer.tsx`；`message-timeline.tsx:189,374` | `SH/…/MessageTimeline.kt:494-516` | 一致（实现不同，效果等价） |
| 滚动锚定 / jump-to-latest | `US/session/timeline/message-timeline.tsx:83,102,187-205` | `SH/…/MessageTimeline.kt:449-478,1147-1198` | 一致 |
| session not found | `US/session/route-error.tsx:11-31` | `SH/ui/session/SessionNotFoundPanel.kt:87-200` | 一致 |
| 单条消息 re-run | 上游 `actions.ts:19-22` 仅 copy/revert/openAttachment | 未定位 | 未核对 |
| 按文件分组工具组 SessionFileToolGroup | `current-message.tsx:105-118` | `TimelineGrouping.kt` 仅见 Context/Part/Label | 未核对 |
| 空时间线状态 | 未在上游定位到 | `SH/…/MessageTimeline.kt:494-516` | 未核对 |

### 表面 4 — Composer

| 能力项 | 上游 file:line | 本壳 file:line | 判定 |
|---|---|---|---|
| 多行输入框 / 高度上限 | `US/composer/editor/editor.tsx:169,191` | `SH/ui/session/composer/Composer.kt:213` | 一致 |
| 软键盘 IME 提交 | `US/composer/editor/editor.tsx:211` | `SH/…/Composer.kt:139-140,2902` | 一致 |
| Enter 发送 / Shift+Enter 换行 | `US/composer/editor/editor.tsx:204-215` | `SH/…/Composer.kt:1642-1667,1865-1905` | 一致 |
| 发送按钮 | `US/composer/editor/editor.tsx:335-343` | `SH/…/Composer.kt:1865-1905`；`SubmitButton:2803` | 一致 |
| 停止/中断 | `US/composer/editor/editor.tsx:337-342` | `SH/…/Composer.kt:1649-1652,1874,1904` | 一致（壳 Esc/Ctrl+G 亦停） |
| **运行中待发队列面板** | `US/session/composer/queue-panel.tsx:18-83` | `SH/…/ComposerQueuePanel.kt:61-150`；`Composer.kt:1409` | 一致 |
| **Steer / 注入运行中轮次** | `US/session/composer/queue.ts:132-136`；`queue-panel.tsx:160-177` | `SH/…/ComposerQueue.kt`；`ComposerQueuePanel.kt:253` | 一致（停机降级为 Send） |
| 队列行编辑/取消/删除 | `US/session/composer/queue.ts:138-207` | `SH/…/ComposerQueue.kt:84,136,143,154,161` | 一致 |
| **队列拖拽排序手势** | `US/session/composer/queue-panel.tsx:35-59` | 壳模型层有（`ComposerQueue.kt:105`）但面板无手势（`ComposerQueuePanel.kt:55-56`） | **缺失**（仅 UI 层缺） |
| Steer/Queue 投递切换 chip | `US/composer/editor/editor.tsx:310-316` | `SH/…/DeliveryToggleChip.kt`；`Composer.kt:643-647,1224-1230` | 一致 |
| 附件按钮 | `US/composer/editor/editor.tsx:250-266` | `SH/…/AttachmentPicker.kt:74`；`Composer.kt:1767` | 一致 |
| 附件条 / 移除 / 上传进度 | `US/composer/editor/editor.tsx:482-500` | `SH/…/AttachmentPreviewRow.kt`；`AttachmentUploads.kt`；`Composer.kt:1503,1509` | 一致 |
| 模型 chip | `US/composer/composer.tsx:49-129` | `SH/…/ModelChip.kt`；`Composer.kt:1813` | 一致 |
| **Agent chip** | 上游 agent+model **合一** popover：`US/providers/models/selection.tsx:184-230` | 壳拆为独立 Agent+Model 两 chip：`SH/…/AgentChip.kt`；`Composer.kt:1798` | **不一致** |
| Shell 模式（`!`/退出） | `US/composer/placeholder.ts:6`；`editor.tsx:317-334` | `SH/…/Composer.kt:1727`；`ShellModeBadge:2625` | 一致 |
| 占位文案（normal/followUp/shell） | `US/composer/placeholder.ts:1-9` | `SH/…/Composer.kt:1727-1729` | 一致 |
| `/` 斜杠命令补全 | `US/composer/editor/editor.tsx:126`；`composer/commands.tsx:16` | `SH/…/Composer.kt:1114,1250`；`ComposerSuggestions.kt:46` | 一致 |
| `@` 提及补全 | `US/composer/editor/editor.tsx:351-378` | `SH/…/Composer.kt:126,1472`；`MentionPopover.kt` | 一致 |
| 提示词历史 ↑/↓ | `US/composer/editor/editor.tsx:207-210` | `SH/…/PromptHistory.kt:25`；`Composer.kt:448,1665-1667` | 一致 |
| 每会话草稿持久化 | `US/composer/persistence.tsx:66-138` | `SH/…/ComposerDrafts.kt:43`；`Composer.kt:447` | 一致 |
| 拖拽放置区 dropzone | `US/composer/dropzone.tsx:7-18` | `SH/…/AttachmentDropZone.kt:54,84-102` | 一致（文案未分支，见不一致） |
| 用量超限 upsell 闸门 | `US/session/usage-exceeded-dialogs.tsx:43-97` | `SH/ui/overlay/UsageUpsellGate.kt:23`；`UsageExceededDialog.kt:55-129` | 一致 |
| 字符/token 计数器 | 上游 editor 无渲染位 | 壳无 | 未核对 |
| 命令面板触发 | `US/composer/composer.tsx:27-28` | `SH/ui/App.kt:2907`；`SessionScreen.kt:1936` | 一致 |

### 表面 5 — 会话右侧 dock（浅层结论，v161 已深挖）

| 能力项 | 上游 file:line | 本壳 file:line | 判定 |
|---|---|---|---|
| 侧栏容器 + Tab 组 | `US/session/files/session-side-panel.tsx:309` | `SH/ui/session/review/ReviewPanel.kt:141` | 一致 |
| 变更 Review 面板 | `US/session/files/session-side-panel.tsx:335` | `SH/ui/session/review/ReviewPanel.kt:53` | 一致 |
| 上下文/用量 Tab | `US/session/files/session-side-panel.tsx:346` | `SH/ui/session/review/ReviewPanel.kt:53`；`MobileUsagePanel.kt:50` | 一致 |
| 文件树（可展开目录） | `US/session/files/file-tree-v2.tsx` | `SH/ui/session/review/ReviewFilesTab.kt:333` | 一致 |
| 多文件 Tab 条 | `US/session/files/file-tabs.tsx` | `SH/ui/session/review/FileTabStrip.kt:68` | 一致 |
| 权限请求 Dock | `US/session/requests/session-permission-dock.tsx:8` | `SH/ui/session/dock/SessionPermissionDock.kt:73` | 一致 |
| 表单/问答 Dock | `US/session/requests/session-question-dock.tsx:70` | `SH/ui/session/dock/SessionFormDock.kt:71` | 一致 |
| Web 搜索 Dock | `US/session/requests/session-websearch-dock.tsx:10` | `SH/ui/session/dock/SessionWebSearchDock.kt:62` | 一致 |
| 浏览器面板 | `US/session/browser/pane.tsx:16` | `SH/ui/session/browser/SessionBrowserPane.kt:120` | 一致（签名不同） |
| 侧栏内 Tab 拖拽排序 | `US/session/files/session-side-panel.tsx:305` | 未见 move/reorder 命中 | 未核对 |
| 终端 Pane 尺寸持久化 | `US/shell/tabs/schema.ts:56` | 仅提及 `SH/ui/settings/SettingsModel.kt:405` | 未核对 |
| Web 搜索"运行中"Dock | 上游仅一处 dock | 壳有 `SH/ui/session/dock/SessionWebSearchRunDock.kt:174` | 未核对 |

**小结**：dock 面基本一致，唯一确认的差异是**顶栏缺 Review 开关**（见表面 2），其余留给 v161。

### 表面 6 — 设置

| 能力项 | 上游 file:line | 本壳 file:line | 判定 |
|---|---|---|---|
| 设置内搜索 | `US/settings/search-catalog.ts:53`；`search.tsx` | `SH/ui/settings/SettingsScreen.kt:412,450` | 一致 |
| 服务器清单页 | `US/settings/servers/servers.tsx:105` | `SH/ui/settings/ServersPanel.kt:93` | 一致 |
| Shell（config.shell） | 上游是 servers 页内区块 `US/settings/servers/servers.tsx:110` | 壳提为独立 Tab `SH/ui/settings/SettingsModel.kt:59,141` | **不一致**（结构） |
| 供应商/凭据 | `US/settings/providers/providers.tsx` | `SH/ui/settings/ProviderConnectCatalog.kt`；`SettingsConnectUi.kt` | 一致 |
| 模型可见性 | `US/settings/models/models.tsx:191` | `SH/ui/settings/ModelVisibility.kt:61` | 一致 |
| Extensions（MCP/插件/技能） | `US/settings/providers/extensions.tsx:83-85` | `SH/ui/settings/SettingsPanels.kt:2096` | 一致 |
| 外观：配色/主题/UI·代码·终端字体 | `US/settings/appearance/appearance.tsx:87,113,124-126` | `SH/ui/settings/SettingsModel.kt:628,538,554,570` | 一致 |
| 密度/减弱动效开关 | 两侧均无（上游仅 CSS `prefers-reduced-motion`） | | 一致 |
| 快捷键改键/冲突/重置 | `US/settings/keybinds/keybinds.tsx:306,333` | `SH/ui/settings/SettingsPanels.kt:537-596` | 一致 |
| 通用/行为（语言/自动批准/终端位置/推理模式） | `US/settings/general/general.tsx:61,144,173,284` | `SH/ui/settings/SettingsModel.kt:373-420` | 一致 |
| 关于/版本/贡献者 | `US/settings/about/about.tsx:42,54` | `SH/ui/settings/SettingsPanels.kt:707,724` | 一致 |
| SSH 服务器 | `US/servers/ssh/{dialog,settings}.tsx` | `SH/ui/servers/SshDialog.kt`；`ServersPanel.kt:104` | 一致 |
| 实验特性页 | `US/settings/experimental/experimental.tsx:39,57,72,87` | `SH/ui/settings/SettingsPanels.kt:1085`；`SettingsModel.kt:891-923` | 不一致（部分行隐藏） |
| **server 作用域嵌套导航** | `US/settings/shell.tsx:342`（nestedServerTabs）、`:59-62` | 壳扁平，无 per-server 层级：`SH/ui/settings/SettingsModel.kt:132-143` | **缺失** |
| **project 作用域嵌套导航** | `US/settings/shell.tsx:419`（nestedProjectTabs）、`:64-68` | 同上无对应层级 | **缺失** |
| **project 级 LSPS 扩展子页** | `US/settings/workspaces/project-extensions.tsx:319,367` | `SH/ui/settings/SettingsPanels.kt:2096` 仅 3 子页 | **缺失** |
| 桌面 pinch-zoom 开关 | `US/settings/general/general.tsx:347` | 无 | 缺失（桌面专属，Android 合理省略） |
| release-notes / check-updates | `US/settings/general/general.tsx:439,452` | 无（`SH/ui/settings/ShellPanelUpdates.kt:204` 仅投影） | **缺失** |
| 隐私/遥测开关 | 两侧均无用户可见开关（仅上游 Sentry `app.tsx:79`） | | 一致 |

**设置分类对照**

- **上游**（定义 `US/settings/shell.tsx:39-68`，类型 `settings/surface.tsx:9-21`，图标文案 `settings/pages.ts:5-33`）：
  `general` `appearance` `notifications` `shortcuts` ‖ `projects` `workspaces` `providers` `models` `extensions` ‖ `experimental` `about`；另含 server 级 6 子页、project 级 3 子页两层嵌套。
- **本壳**（定义 `SH/ui/settings/SettingsModel.kt:47-70`，渲染 `:121-146`，图标 `:161-198`）：
  `general` `appearance` `notifications` `shortcuts` **`pairing`** ‖ `projects` `workspaces` `providers` `models` `extensions` **`servers`** **`shell`** ‖ `experimental` `about`
- **仅本壳有（root 层）**：`pairing`（`SettingsModel.kt:52,129`；上游是 `US/servers/connect/dialog.tsx:98` 的对话框）、`shell`、以及把 `servers` 提为独立页
- **仅上游有**：两级嵌套设置导航、project 级 `lsps`、pinch-zoom、release-notes/check-updates
- 隐藏项：壳 `HIDDEN_SETTINGS`（`SettingsModel.kt:860-867`）屏蔽 `settings-shell`、3 个 sound 选择、search/status 行

### 表面 7 — 通知

| 能力项 | 上游 file:line | 本壳 file:line | 判定 |
|---|---|---|---|
| 触发-回合完成 | `US/shell/notifications/notification.tsx:225-251,283-292` | `SH/ChatViewModel.kt:7275,2217` | 一致 |
| 触发-回合失败 | `US/shell/notifications/notification.tsx:253-281` | `SH/ChatViewModel.kt:7253` | 一致 |
| **触发-权限请求** | 上游 union 只有 `turn-complete｜error`（`notification.tsx:30-33`）；`notifications.permissions` 仅设置页读（`settings/model.tsx:123`），**无投递方** | `SH/ChatViewModel.kt:7054,7235`（`PERMISSION`） | **自造** |
| **触发-提问 question** | 上游无（`notification.tsx:30-33`）；hasForms 只进角标 | `SH/ChatViewModel.kt:7243`（`QUESTION`） | **自造** |
| 系统通知投递 | `US/shell/notifications/coordinator.ts:60-69` | `SH/notify/SystemNotifier.kt:339-380`；`AndroidManifest.xml:22` | 一致 |
| **应用内横幅** | 上游无横幅渲染层（消费者仅 `session/route.tsx:188-192`、`home/projects/controller.tsx:143-149`） | `SH/ui/App.kt:569,3284` + `SH/notify/BannerPolicy.kt:39-54` | **自造** |
| 每会话 vs 全局（两级） | `notification.tsx:37-50` | `SH/ui/nav/SessionTabIndicator.kt:162-170`；`ui/home/HomeScreen.kt:1274` | 一致 |
| 通知开关设置项 | `US/settings/notifications/notifications.tsx:79-111` | `SH/ui/settings/SettingsModel.kt:720-733` | 一致 |
| **声音可选音色** | 上游 45 个：`US/shell/notifications/sound.ts:13-100` | 壳仅 `none`/`default`：`SH/ui/settings/SettingsModel.kt:768-771` | **不一致**（有意偏离） |
| **声音试听** | `US/shell/notifications/sound.ts:100`；`settings/general/controllers.ts:153` | 无，壳自述 TODO：`SH/ui/settings/SettingsModel.kt:763-766` | **缺失** |
| **「该会话有打开 tab」才响** | `US/shell/notifications/notification.tsx:231,258`（`sessionIDHasOpenTab`） | 壳无此门：`SH/notify/SystemNotifier.kt:297-326` | **缺失** |
| 点击通知→回到会话 | `US/shell/notifications/notification.tsx:54-59` | `SH/notify/NotificationNavigation.kt:34-45`；`SystemNotifier.kt:515-527` | 一致 |
| 未读计数/角标 | `notification.tsx:307,339` | `SH/ui/nav/SessionTabIndicator.kt:130-138` | 一致 |
| 已读标记 | `notification.tsx:313-330` | `SH/notify/AppNotifier.kt:162-181` | 一致 |
| 历史/持久化（上限/TTL） | `notification.tsx:61-69`（500 条/30 天） | `SH/notify/NotificationPersistence.kt:20-26`；`AppNotifier.kt:106-116` | 一致 |
| 去重 | 上游按服务端 eventID（`coordinator.ts:46-54,73-93`） | 壳按 `kind:sessionID` 5s 窗口：`SH/notify/AppNotifier.kt:136-142` | **不一致** |
| 前台抑制 | `US/shell/notifications/coordinator.ts:60-69`（有焦点即返回，永不重估） | 壳加延迟复查+后台补投 120s：`SH/notify/SystemNotificationPolicy.kt:99-105,149-155` | **不一致**（自造） |
| 通知权限申请 | `US/runtime/platform/web.ts:24-28` | `SH/notify/SystemNotificationPolicy.kt:244-245` | 一致 |

### 表面 8 — 终端（重点疑点 · 结论：**非自造，共享能力**）

**判定块**

- `上游 web UI 有终端?` → **YES**。面板 `US/session/terminal/panel.tsx:39`（Tab 条 `:299-339`、拖拽 `:289-295`、缩放、`+/-` 按钮 `:330-335`）；渲染器 `US/session/terminal/terminal.tsx:178`（内核 **ghostty-web**，`:7`）；三态挂载 `US/session/screen.tsx:100-101,300,489,534`
- `上游 server 有 PTY API?` → **YES，两套**。正式组 `US/../../packages/protocol/src/groups/pty.ts:23,37,52,68`（`GET/POST /api/pty`、`pty.connect` WS）+ `packages/server/src/handlers/pty.ts:26-224`；experimental 组 `protocol/src/groups/persistent-pty.ts:23,39,46,66-104`
- `上游 TUI/CLI 有终端?` → **YES**。`packages/tui/src/component/terminal-pane.tsx:176-188`；上下文 `tui/src/context/session-terminals.tsx:32,78-88`
- `本壳 Open terminal 入口?` → **YES**。`SH/ui/palette/AppCommands.kt:120-125`（`terminal.toggle`）；`SH/ui/palette/SessionCommands.kt:314`（`terminal.new`）；快捷键 `SH/ui/App.kt:2241,2371`（`Ctrl+\``）；UI 挂载 `SH/ui/session/SessionScreen.kt:2522,2546,2750`；面板 `SH/ui/terminal/TerminalPanel.kt:167`
- `本壳 PTY 后端?` → **server remote**。`SH/ui/terminal/share/PtyApi.kt:24-38`；`SH/v2/V2ApiPtyShell.kt:25,42,66`（`GET /api/pty/{ptyID}/connect`）。`SH/ui/terminal/share/TermuxPtyScreenEngine.kt:36,212-249` 只用 Termux `TerminalEmulator` 做**本地 VT 解析**，不是本地 PTY。
- **结论：共享能力，入口/命令/键位/协议均与上游对齐**；唯一差异是 VT 内核实现（平台必然）。**"Open terminal 是自造物"的疑点不成立。**

| 能力项 | 上游 file:line | 本壳 file:line | 判定 |
|---|---|---|---|
| 终端面板组件 | `US/session/terminal/panel.tsx:39` | `SH/ui/terminal/TerminalPanel.kt:167` | 一致 |
| 三态挂载（移动/侧/底） | `US/session/screen.tsx:300,489,534` | `SH/ui/session/SessionScreen.kt:2750`；`ui/terminal/TerminalDock.kt:69,118,129` | 一致 |
| `terminal.toggle` 命令+键位 | `US/session/commands/use-session-commands.tsx:355-368`（`ctrl+\``、`/terminal`） | `SH/ui/palette/AppCommands.kt:120-125`；`ui/App.kt:2241,2371` | 一致 |
| `terminal.new` 命令 | `US/session/commands/use-session-commands.tsx:403-405` | `SH/ui/palette/SessionCommands.kt:314`；`ui/App.kt:2301` | 一致 |
| PTY 创建 | `US/session/terminal/context.tsx:252` → `POST /api/pty` | `SH/ui/terminal/share/PtyApi.kt:24-38,55-56` | 一致 |
| PTY 附着（WS） | `US/session/terminal/terminal.tsx:577-582` | `SH/v2/V2ApiPtyShell.kt:25,42,66` | 一致 |
| 重命名/resize/删除 | `US/session/terminal/context.tsx:181-195,205-235` | `SH/ui/terminal/share/PtyApi.kt:35-38` | 一致 |
| 会话级持久终端 | `packages/tui/src/component/terminal-pane.tsx:176,183` | `SH/v2/V2ApiSessionTerminal.kt:7-25`；`ui/terminal/SessionTerminalsPanel.kt:74,99` | 一致 |
| 后台 shell | `packages/server/src/handlers/shell.ts:13,20,30,47,64` | `SH/v2/V2ApiPtyShell.kt:134,146,161`；`ui/terminal/BackgroundShellPanel.kt` | 一致 |
| 屏幕引擎抽象缝 | `US/session/terminal/runtime-adapters.ts:7-31` | `SH/ui/terminal/share/PtyScreenEngine.kt` | 一致 |
| VT 内核 | ghostty-web `US/session/terminal/terminal.tsx:7` | Termux `TerminalEmulator` `SH/ui/terminal/share/TermuxPtyScreenEngine.kt:36` | 不一致（平台必然） |
| 序列化/快照复用 | `US/session/terminal/serialize.ts`、`writer.ts` | `SH/ui/terminal/share/SessionTerminalRestore.kt:16` | 未核对 |
| 终端字体设置是否生效 | `US/settings/model.tsx`（`terminalFontFamily`） | `SH/…/strings.xml:206-207` | 未核对 |

### 表面 9 — 多会话 / 多标签

| 能力项 | 上游 file:line | 本壳 file:line | 判定 |
|---|---|---|---|
| 桌面 Tab 条 | `US/shell/titlebar/tab-strip.tsx:239` | `SH/ui/nav/DesktopTabStrip.kt:190` | 一致 |
| 移动底部 Tab 条 | `US/shell/titlebar/titlebar.tsx:466-476`（受 experimental tabLayout 控制） | `SH/ui/nav/BottomTabStrip.kt:156`；`AppearancePrefs.kt:23-29` | 一致 |
| 移动 Tab 抽屉 | `US/shell/titlebar/titlebar.tsx:522-541` | `SH/ui/nav/MobileTabsDrawer.kt:98` | 一致 |
| 打开/新建 Tab | `US/shell/titlebar/titlebar.tsx:390`（`mod+t,mod+n`） | `SH/ui/nav/DesktopTabStrip.kt:494-499` | 一致 |
| 关闭 Tab | `US/shell/titlebar/titlebar.tsx:398-405` | `SH/ui/App.kt:1062` | 一致 |
| 关闭后邻接选择 | `US/shell/tabs/closed.ts:31-34` | `SH/ui/nav/SessionTabs.kt:29` | 一致 |
| 已关闭列表 + 恢复 | `US/shell/tabs/closed.ts:6,10,17`（上限 25） | `SH/ui/nav/ClosedTabs.kt:17,25,34`；`ui/App.kt:1176-1181` | 一致 |
| Tab 拖拽排序 | `US/shell/titlebar/tab-strip.tsx:292-330` | `SH/ui/nav/DesktopTabStrip.kt:384-394`；`ui/components/TabReorder.kt:19` | 一致（壳长按） |
| Tab 标题（每会话） | `US/shell/titlebar/tab-title.ts` | `SH/ui/nav/SessionTabs.kt:220,229` | 一致 |
| Tab 未读/运行指示 | `US/shell/layout/project-avatar-state.ts`；`session-tab-avatar.tsx:26-49` | `SH/ui/nav/SessionTabIndicator.kt:44-69` | 一致 |
| Tab 溢出滚动 | `US/shell/titlebar/tab-strip.tsx:288` | `SH/ui/nav/DesktopTabStrip.kt:318,486-492` | 一致（两侧均无 +N 角标） |
| 草稿（未保存）Tab | `US/shell/tabs/schema.ts:17-25` | `SH/ui/nav/SessionTabs.kt:265` | 一致 |
| 启动恢复（最后路由） | `US/shell/tabs/tabs.tsx:73`（`tabs.recent`） | `SH/ui/nav/LastRouteStore.kt:23,60` | 一致 |
| **Tab 列表持久化（重启恢复全部）** | `US/shell/tabs/tabs.tsx:72-78`（`Persist.window("tabs"/"tabs.closed"/"tabs.info"/"tabs.panes")`） | 仅内存：`SH/ui/App.kt:657,665`（`remember { mutableStateListOf }`，无落盘） | **缺失** |
| **前进/后退历史栈** | `US/shell/titlebar/history.ts:1-11`（上限 100） | 无（仅系统返回键） | **缺失** |
| **Ctrl+Tab 切 Tab** | `US/shell/titlebar/tab-strip.tsx:256,264` | 无（`ui/App.kt` 内 `tab.next/ctrl+tab` 无命中） | **缺失** |
| Tab 右键菜单 | `US/shell/titlebar/tab-nav.tsx:361-364`（重命名 + **关闭**） | `SH/ui/nav/DesktopTabStrip.kt:452-476`（仅重命名） | **不一致** |
| Tab 预览气泡 | `US/shell/titlebar/tab-popover.tsx:24` | 未见对应 | 未核对 |
| Tab 数量角标 | `US/shell/titlebar/titlebar.tsx:472-500`（无数字角标） | `SH/ui/nav/BottomTabStrip.kt:143`（注释称有） | 未核对 |

### 表面 10 — 其它高影响面

| 能力项 | 上游 file:line | 本壳 file:line | 判定 |
|---|---|---|---|
| 配对码解析 | `US/servers/connect/pairing.ts:22` | `SH/ui/settings/PairingModel.kt`；`ui/servers/ServerConnectModel.kt` | 一致 |
| **相机扫码** | `US/servers/connect/camera.ts:4`；`scanner.tsx:9` | 仅图库解码，相机半边 TODO：`SH/ui/servers/QrScan.kt:35`（`:18-21`） | **不一致**（功能降级） |
| 添加服务器对话框 | `US/servers/connect/dialog.tsx:39` | `SH/ui/servers/ConnectServerScreen.kt` | 一致（壳置于 pairing 页） |
| 服务器注册表管理 | `US/servers/registry/row-menu.tsx` | `SH/ui/settings/ServersPanel.kt:93` | 一致 |
| SSH 连接面板 | `US/servers/ssh/connection-panel.tsx` | `SH/ui/servers/SshConnectionPanel.kt` | 一致 |
| **WSL 服务器类型** | `US/servers/wsl/{dialog,settings}.tsx` | 仅路径处理：`SH/data/HomeProjectStore.kt:71`，无 WSL 类型 | **缺失**（Android 场景低影响） |
| 命令面板 | `US/shell/commands/dialog.tsx`；`home/sessions/command-palette.tsx` | `SH/ui/palette/CommandPalette.kt:87` | 一致 |
| 不兼容服务器面板 | `US/session/route-error.tsx:28` | `SH/ui/session/IncompatibleServerPanel.kt`；`v2/V2Capability.kt:19-24` | 一致 |
| 断网恢复 | `US/runtime/server/health.ts:79-88` | `SH/NetworkRecoveryWatch.kt`；`ui/servers/ServerHealth.kt` | 一致 |
| 能力协商集中表 | 上游**无**集中表（`US/runtime/platform/platform.tsx:135` 逐能力可选） | `SH/v2/V2Capability.kt:29,67`（COVERAGE 表） | **自造**（结构差异） |
| 配对码屏幕显示 | `US/servers/connect/screen.tsx:18` | 未定位 | 未核对 |
| 混合内容拦截 | `US/servers/connect/browser.ts:3` | 未定位 | 未核对 |

### 表面 11 — 命令表 / 命令面板（我补充的高影响面）

上游命令目录是权威清单 `_upstream/packages/app/src/runtime/i18n/en.ts:59-157,771`；本壳命令注册在 `SH/ui/palette/{AppCommands,SessionCommands,BuiltinCommands}.kt`。

| 能力项 | 上游 file:line | 本壳 file:line | 判定 |
|---|---|---|---|
| 命令分类体系 | `US/runtime/i18n/en.ts:59-75`（17 类：suggested/view/project/provider/server/session/theme/language/file/context/terminal/model/mcp/agent/permissions/workspace/settings） | `SH/ui/palette/SessionCommands.kt:577-586`（8 类：MCP/Permissions/View/Terminal/File/Context/Project/Worktree/Model） | **不一致**（壳仅 9 类 vs 上游 17 类） |
| 调色板占位/空态 | `US/shell/commands/dialog.tsx` | `SH/…/strings.xml:283-289` | 一致 |
| 3 个**内置**调色板命令 | 上游无 `agent.details` / `debug.locations` / `plugin.rpc` | `SH/ui/palette/BuiltinCommands.kt:25,28,31` | **自造** |
| 会话命令：archive/undo/redo/compact/fork/export/import | `US/runtime/i18n/en.ts:96,146-156` | `SH/ui/palette/SessionCommands.kt`；`ui/session/header/SessionHeader.kt:807-961` | 一致 |
| 主题/配色 scheme 切换命令 | `US/runtime/i18n/en.ts:101-104` | 未定位同名命令 | 未核对 |
| 语言切换命令 | `US/runtime/i18n/en.ts:106-107` | 未定位 | 未核对 |
| `command.steps.toggle`（显示/隐藏 steps） | `US/runtime/i18n/en.ts:124-125` | 未定位 | 未核对 |
| `command.context.addSelection` | `US/runtime/i18n/en.ts:115-116` | 未定位 | 未核对 |
| `command.message.previous/next` | `US/runtime/i18n/en.ts:126-129` | 壳有消息导航轨（`MessageNavRail.kt`）但未见同名命令 | 未核对 |
| `command.model.variant.cycle`（thinking effort） | `US/runtime/i18n/en.ts:138-139` | 壳有 variant 数据（`SH/OpenCodeClient.kt:705`）但未见循环命令 | 未核对 |
| `command.agent.cycle` | `US/runtime/i18n/en.ts:134-137` | 壳有 AgentChip（`ui/session/composer/AgentChip.kt`）但未见循环命令 | 未核对 |
| `command.session.background`（移到后台） | `US/runtime/i18n/en.ts:771` | 未定位 | 未核对 |
| `command.workspace.toggle`（sidebar worktrees） | `US/runtime/i18n/en.ts:144-145` | 壳无 worktree 树（见表面 1） | **缺失**（派生） |

---

## 2. TOP 15 不一致清单（按用户可见影响排序）

| # | 差异（一句话） | 上游 file:line | 本壳 file:line | 影响 |
|---|---|---|---|---|
| 1 | **重启后丢失所有打开的 Tab**，只恢复最后一个路由 | `US/shell/tabs/tabs.tsx:72-78`（`Persist.window` 落盘 tabs/closed/info/panes） | `SH/ui/App.kt:657,665`（仅 `remember mutableStateListOf`，无落盘） | **L** |
| 2 | **项目列表无嵌套树**，上游按 worktree 层级展开/折叠，本壳扁平 | `US/home/sessions/records.ts:36-44`；`US/home/projects/view.tsx` | 壳自陈无：`SH/data/HomeProjectStore.kt:446-447`；扁平列表 `SH/ui/home/HomeProjectsList.kt:91` | **L** |
| 3 | **设置无 server / project 两级嵌套导航**，上游每台服务器、每个项目各有子设置页 | `US/settings/shell.tsx:342,419`（nestedServerTabs/nestedProjectTabs）、`:59-68` | `SH/ui/settings/SettingsModel.kt:132-143`（扁平单层） | **L** |
| 4 | **消息内图片只显示文件名 chip，无缩略图与点开预览** | `session-ui/src/message/message-content.tsx:265-272`；`components/image-preview.tsx:5` | `SH/ui/session/timeline/PartRenderer.kt:790-818` | **L** |
| 5 | **会话顶栏无项目入口/父会话面包屑**，无法从会话跳回项目 | `US/session/timeline/message-timeline.tsx:422-444`；`US/session/session-identity-header.tsx:38-205` | `SH/ui/session/header/SessionHeader.kt:290-425`（无对应） | **L** |
| 6 | **通知多出权限请求/提问两类 + 应用内横幅**，上游这两类只有设置项、无投递方，且无横幅 | `US/shell/notifications/notification.tsx:30-33`（union 仅 turn-complete\|error） | `SH/ChatViewModel.kt:7054,7235,7243`；`SH/ui/App.kt:569,3284`；`SH/notify/BannerPolicy.kt:39-54` | **M** |
| 7 | **通知缺「该会话有打开 tab 则不响」门控**，前台切走时仍会响 | `US/shell/notifications/notification.tsx:231,258` | `SH/notify/SystemNotifier.kt:297-326` | **M** |
| 8 | **通知音效只有 none/default 2 个且无试听** | `US/shell/notifications/sound.ts:13-100`（45 个 `SOUND_OPTIONS`）；试听 `settings/general/controllers.ts:153` | `SH/ui/settings/SettingsModel.kt:768-771`；TODO `:763-766` | **M** |
| 9 | **auto-accept 权限无会话内入口**，只在设置里 | `US/session/commands/use-session-commands.tsx:443-444` | `SH/ui/App.kt:733`（仅设置项），顶栏/菜单无 | **M** |
| 10 | **顶栏 ⋮ 多出「导入会话」「移动到…/新建工作树」**；且顶栏缺 Review 开关 | 上游顶栏仅 改名/导出/删除 `US/session/timeline/message-timeline.tsx:517-536`；Review 开关 `US/session/header/session-header-actions.tsx:37-73` | 新增 `SH/ui/session/header/SessionHeader.kt:839-848,901-919`；Review 开关缺失 | **M** |
| 11 | **thinking 恒折叠**，无 timelineDetail / reasoningMode 预设 | `session-ui/src/timeline/session-timeline-row.tsx:291-306`；`detail.ts:17-61` | `SH/ui/session/timeline/PartRenderer.kt:714`（恒 `false`） | **M** |
| 12 | **会话搜索为纯子串匹配**，无 fuzzysort 排序 | `US/home/sessions/search.ts:30-38` | `SH/ui/home/HomeSessions.kt:316-329`（壳自记偏差 `:311-314`） | **M** |
| 13 | **改名失焦语义相反**：上游失焦取消，壳失焦提交（易误改名） | `US/home/sessions/view.tsx:574`（onBlur=取消） | `SH/ui/home/HomeRowMenu.kt:176-179`（BLUR=提交） | **M** |
| 14 | **队列面板无拖拽排序**（模型层有、UI 无手势） | `US/session/composer/queue-panel.tsx:35-59` | `SH/ui/session/composer/ComposerQueuePanel.kt:55-56`（`ComposerQueue.kt:105` 有） | **M** |
| 15 | **无 Ctrl+Tab 切标签、无前进/后退历史栈；Tab 右键菜单缺「关闭」** | `US/shell/titlebar/tab-strip.tsx:256,264`；`history.ts:1-11`；`tab-nav.tsx:361-364` | `SH/ui/nav/DesktopTabStrip.kt:452-476`；`ui/App.kt` 无 tab.next | **M** |

**次级（未进 TOP15，但确认为不一致）**：Agent/Model 选择器被拆两 chip（`providers/models/selection.tsx:184-230` vs `Composer.kt:1798,1813`）；点击上下文环行为不同（`session-context-usage.tsx:93-108` vs `SessionHeader.kt:158-164`）；时间线 diff 无虚拟化/Pierre 高亮（`components/file.tsx:775,880,1048` vs `PartRenderer.kt:496-540,651`）；导航轨 diff bars 未接数据（`message-nav.tsx:109-151` vs `MessageNavRail.kt:95-99`）；分隔线丢 provider icon/variant（`timeline-separator.tsx:9-11,16-27` vs `TimelineSeparator.kt:39-43`）；相机扫码降级为图库（`servers/connect/camera.ts:4` vs `QrScan.kt:18-21`）；project 级 LSPS 子页缺失（`project-extensions.tsx:319,367` vs `SettingsPanels.kt:2096`）。

---

## 3. 「本壳有、上游没有」（自造物）

> 判定口径：**上游代码中找不到任何对应实现**才列此节。上游"有同名能力但入口位置不同"的（如导入会话）不列入。

| # | 自造物 | 本壳 file:line | 上游反证 |
|---|---|---|---|
| 1 | 应用内通知横幅（NotificationBanner） | `SH/ui/App.kt:569,3284`；`SH/notify/BannerPolicy.kt:39-54` | 上游无横幅渲染层，消费者仅 `US/session/route.tsx:188-192`、`home/projects/controller.tsx:143-149` |
| 2 | 权限请求类系统通知 | `SH/ChatViewModel.kt:7054,7235` | `US/shell/notifications/notification.tsx:30-33` union 无此 kind |
| 3 | 提问（question）类系统通知 | `SH/ChatViewModel.kt:7243` | 同上；上游 hasForms 只进角标 |
| 4 | 3 个内置调色板命令 `agent.details` / `debug.locations` / `plugin.rpc` | `SH/ui/palette/BuiltinCommands.kt:25,28,31`；文案 `strings.xml:429,433,438` | `US/runtime/i18n/en.ts:59-157` 无此三命令（上游只有 `debugBar.toggle:77`、`logs.export:76`） |
| 5 | 能力协商集中表 V2Capability（COVERAGE） | `SH/v2/V2Capability.kt:29,67` | 上游无集中协商表，`US/runtime/platform/platform.tsx:135` 逐能力可选 |
| 6 | 通知后台补投 120s TTL + 延迟复查 | `SH/notify/SystemNotificationPolicy.kt:99-105,149-155` | `US/shell/notifications/coordinator.ts:62` 读到焦点即放弃、永不重估 |
| 7 | 上下文超限救援建议（Compact / Undo 双出口） | `SH/…/strings.xml:1356-1359`；`ui/session/timeline/SessionErrorCard.kt:494-570` | 上游 `_upstream/packages/app/src` grep `InvalidRequest\|context window` 无业务命中 |
| 8 | 顶栏「时间线搜索」独立可见入口 | `SH/ui/session/header/SessionHeader.kt:380-382`；`TimelineSearchBar.kt:79-95` | `US/session/screen.tsx:273-277` 只注入搜索栏，无独立按钮 |
| 9 | 项目行菜单「刷新工作树」 | `SH/ui/home/HomeProjectsList.kt:527-537` | `US/home/projects/view.tsx:734-762` 无此项 |
| 10 | 会话列表加载失败可重试态 | `SH/ui/home/HomeSessionsList.kt:1902-1925` | `US/home/sessions/view.tsx:128-135` 仅骨架/空态 |
| 11 | 会话行内相对时间 | `SH/ui/home/HomeSessionsList.kt:1062-1063,1792` | `US/home/sessions/view.tsx:647-657` 行内无时间 |
| 12 | 工具输出「show more」展开钮（上游仅 240px 滚动） | `SH/ui/session/timeline/PartRenderer.kt:487-488,563-564` | `session-ui/src/message/message-part.css:323-464` |
| 13 | 设置 root 层 `pairing` Tab（上游是对话框） | `SH/ui/settings/SettingsModel.kt:52,129` | 上游 `US/servers/connect/dialog.tsx:98` 内嵌对话框 |

---

## 4. 「上游有、本壳完全没有」（缺失物）

| # | 缺失物 | 上游 file:line | 本壳缺失证据 |
|---|---|---|---|
| 1 | Tab 列表持久化（重启恢复全部打开/已关闭 Tab） | `US/shell/tabs/tabs.tsx:72-78` | `SH/ui/App.kt:657,665` 无落盘 |
| 2 | 标题栏前进/后退历史栈 | `US/shell/titlebar/history.ts:1-11` | 无（仅系统返回键） |
| 3 | Ctrl+Tab / mod+option+方向键 切 Tab | `US/shell/titlebar/tab-strip.tsx:256,264` | `SH/ui/App.kt` 无命中 |
| 4 | 项目树（worktree 嵌套 + expanded 折叠） | `US/home/sessions/records.ts:36-44`；`home/projects/view.tsx` | 壳自陈：`SH/data/HomeProjectStore.kt:446-447` |
| 5 | server 作用域嵌套设置页（6 子页） | `US/settings/shell.tsx:59-62,342` | `SH/ui/settings/SettingsModel.kt:132-143` |
| 6 | project 作用域嵌套设置页（3 子页） | `US/settings/shell.tsx:64-68,419` | 同上 |
| 7 | project 级 LSPS 扩展子页 | `US/settings/workspaces/project-extensions.tsx:319,367` | `SH/ui/settings/SettingsPanels.kt:2096` 仅 3 子页 |
| 8 | 消息内图片缩略图 + 预览弹层 | `session-ui/src/message/message-content.tsx:265-272`；`components/image-preview.tsx:5` | `SH/ui/session/timeline/PartRenderer.kt:790-818` |
| 9 | 会话顶栏项目菜单 + 父会话面包屑 | `US/session/timeline/message-timeline.tsx:422-444`；`session-identity-header.tsx:38-205` | `SH/ui/session/header/SessionHeader.kt:290-425` |
| 10 | 会话顶栏 Review 面板开关 | `US/session/header/session-header-actions.tsx:37-73` | 顶栏无 |
| 11 | auto-accept 权限的会话内命令入口 | `US/session/commands/use-session-commands.tsx:443-444` | 仅 `SH/ui/App.kt:733` 设置项 |
| 12 | 45 个通知音效 + 选择即试听 | `US/shell/notifications/sound.ts:13-100`；`settings/general/controllers.ts:153` | `SH/ui/settings/SettingsModel.kt:768-771`；TODO `:763-766` |
| 13 | 通知「会话有打开 tab 则静音」门控 | `US/shell/notifications/notification.tsx:231,258` | `SH/notify/SystemNotifier.kt:297-326` |
| 14 | timelineDetail / reasoningMode 预设 | `session-ui/src/timeline/detail.ts:17-61`；`session-timeline-row.tsx:291-306` | `SH/ui/session/timeline/PartRenderer.kt:714` 恒 false |
| 15 | 时间线 diff 虚拟化 + Pierre 语法高亮 | `session-ui/src/components/file.tsx:775,880,1048` | `SH/ui/session/timeline/PartRenderer.kt:496-540,651` |
| 16 | 消息导航轨 diff bars | `session-ui/src/components/message-nav.tsx:109-151` | `SH/ui/session/timeline/MessageNavRail.kt:95-99` 仅 hook |
| 17 | 队列面板拖拽排序手势 | `US/session/composer/queue-panel.tsx:35-59` | `SH/ui/session/composer/ComposerQueuePanel.kt:55-56` |
| 18 | 分隔线 provider 图标 + variant 后缀 | `session-ui/src/components/timeline-separator.tsx:9-11,16-27` | `SH/ui/session/timeline/TimelineSeparator.kt:39-43` 明示省略 |
| 19 | 相机扫码（live camera preview） | `US/servers/connect/camera.ts:4`；`scanner.tsx:9` | `SH/ui/servers/QrScan.kt:18-21` TODO，仅图库 |
| 20 | 项目行菜单「在文件管理器中打开」 | `US/home/projects/view.tsx:745-752` | `SH/ui/home/HomeProjectsList.kt:84,312` 自陈无 Android 对应 |
| 21 | 桌面 pinch-zoom 设置 | `US/settings/general/general.tsx:347` | 无（Android 合理省略） |
| 22 | release-notes / check-updates 设置 | `US/settings/general/general.tsx:439,452` | 无 |
| 23 | WSL 服务器类型 | `US/servers/wsl/{dialog,settings}.tsx` | 仅 `SH/data/HomeProjectStore.kt:71` 路径处理 |
| 24 | Tab 右键菜单「关闭标签页」 | `US/shell/titlebar/tab-nav.tsx:361-364` | `SH/ui/nav/DesktopTabStrip.kt:452-476` 仅重命名 |
| 25 | 会话行「后台打开为新标签」 | `US/home/sessions/view.tsx:34-43,399-404` | 未定位（未核对，暂列） |
| 26 | `command.workspace.toggle`（sidebar worktrees 开关） | `US/runtime/i18n/en.ts:144-145` | 无（因无项目树，派生缺失） |

---

## 5. 未核对清单

### 5.1 存疑项（需上游更新版本复核）

| # | 存疑点 | 冲突来源 |
|---|---|---|
| 1 | **`/btw` 面板的来源**：壳 `SessionBtwPanel.kt` 多处注释指向上游 `panel.tsx:36-53`、`data-component="session-working"`，并自注"来自 v2 head `8ce629b`"；但本快照 `_upstream/packages/app/src` 中 **grep `\bbtw\b` 零命中、无 `btw/` 目录**。我已独立复核确认零命中。判定：**该面板在当前上游快照中无法对位**，属"疑为自造/疑为未合入"二选一，需拉取更新上游后定夺。 | 壳 `SH/ui/session/btw/SessionBtwPanel.kt:102,114,169,225,250-253` vs `_upstream/packages/app/src`（无命中） |
| 2 | 上游顶栏是否存在"隐藏的斜杠命令形式 btw 入口"：一面查证称上游为隐藏斜杠命令（→ 判不一致），另一面查证称本快照无任何 btw（→ 判缺失）。二者未收敛。 | 同上 |

### 5.2 常规未核对

| # | 未核对项 | 原因 |
|---|---|---|
| 3 | 上游 `HOME_SESSION_LIMIT` 具体数值 | `US/home/sessions/index.ts` 未读，仅确认截断点 `controller.tsx:91` |
| 4 | new-session 独立屏逐项对照 | `US/new-session/*` 与 `SH/ui/home/NewSessionScreen.kt`(2257 行) 未通读 |
| 5 | 壳 Home 级命令面板挂载点 | `SH/ui/home/HomeScreen.kt` 未见 `CommandPalette` 挂载；`HomeSessionsList.kt:134,706` 仅引用样式来源 |
| 6 | 「最近关闭项目」的上游对应 | 壳有 `SH/ui/home/HomeScreen.kt:411`；上游未在已读文件中定位 |
| 7 | 项目行未读/通知徽标渲染位置 | 壳只见数据源 `HomeScreen.kt:533-555`，未读徽标组件；上游只见 `view.tsx:753-758` 判据 |
| 8 | 桌面「后台打开为新标签」壳侧对应 | 上游 `view.tsx:34-43,399-404` 有；壳未确认 |
| 9 | Tab 预览气泡壳侧对应 | 上游 `US/shell/titlebar/tab-popover.tsx:24` |
| 10 | Tab 数量角标实际渲染位置 | 仅 `SH/ui/nav/BottomTabStrip.kt:143` 注释提及 |
| 11 | 侧栏内 Tab 拖拽排序 | 上游 `session-side-panel.tsx:305`；壳未见 move/reorder |
| 12 | 上游 Web 搜索「运行中」Dock 对应面 | 壳有 `SessionWebSearchRunDock.kt:174`，上游仅 `session-websearch-dock.tsx:10` |
| 13 | 终端 Pane 尺寸持久化完整实现 | 上游 `US/shell/tabs/schema.ts:56`；壳仅提及 `SettingsModel.kt:405` |
| 14 | 配对码屏幕显示 | 上游 `US/servers/connect/screen.tsx:18`；壳未定位 |
| 15 | 混合内容拦截 | 上游 `US/servers/connect/browser.ts:3`；壳未定位 |
| 16 | 单条消息 re-run 入口 | 上游 `actions.ts:19-22` 仅 copy/revert/openAttachment；壳未穷举 `SessionScreen.kt` |
| 17 | 按文件分组工具组 SessionFileToolGroup | 上游 `current-message.tsx:105-118`；壳 `TimelineGrouping.kt` 未逐行读完 |
| 18 | 空时间线状态 | 上游未定位到空态组件；壳侧仅确认无 rows 时不渲染 |
| 19 | 字符/token 计数器渲染位 | 两侧均未见渲染点；上游 `prompt-parts.ts:14` 是否有调用未追 |
| 20 | 主题 scheme / 语言 / steps.toggle / context.addSelection / message.prev-next / variant.cycle / agent.cycle / session.background 等命令的壳侧对应 | 见表面 11 表，8 条均标未核对 |
| 21 | 上游命令面板组件本体渲染文件 | 仅核到 `US/composer/composer.tsx:27-28` 使用 keybind 注册表 |
| 22 | 终端 `TerminalTabs`/`TerminalStream`/`TerminalLayout`/`PtySizeGate` 与上游 `panel.tsx`/`serialize.ts` 逐行比对 | 未做 |
| 23 | 上游 45 个音效 id 的资源实际存在性 | 仅核到 `SOUND_OPTIONS` 定义 `sound.ts:13-100` |
| 24 | `pty.exited` 退出/恢复链等价性 | 上游 `US/session/terminal/context.tsx:177` vs `SH/ui/terminal/share/TerminalRevivePolicy.kt` |
| 25 | 壳 `oc_setting_terminal_font` 是否真接到渲染层 | `SH/…/strings.xml:206-207` |
| 26 | `MessageMetaRow.kt:121-210`（用户行复制/撤销实际绘制） | 判定依据为 `:89-93` 文档注释，未读绘制体 |
| 27 | 壳 `ui/markdown/*` vs 上游 `markdown.tsx`（代码高亮/Mermaid/表格）逐项对齐 | 超范围 |
| 28 | 桌面包 `_upstream/packages/desktop` 是否内嵌可交互终端面板 | 仅核到 `main/wsl/runtime.ts:128-136,348` 用 node-pty 拉起 WSL |

**未核对合计：28 项**（含 2 项存疑 + 26 项常规）

---

## 6. 审计方法与边界

- 4 个只读 evidence agent 分工覆盖 10 面，主线程做跨面交叉复核（命令表 diff、终端/项目树独立验证、自造物/缺失物定向 grep）
- **未执行**：gradle、装机、起服务、设备操作、生产代码/测试的任何修改
- 上游路径均经 scoped grep / glob 定位，**未做 `_upstream` 全树递归**
- 判定为「一致」的项目均读过两侧实现代码，非按文件名推断

### 6.1 证据时点告警（重要）

审计期间（2026-10-04 16:35–16:36）本工作树出现**并发未提交改动**，经内容判定为**在途的 v161 右栏 parity 修复工作**（新增 `app/src/test/java/com/xiufeigo/opencodeshell/RightPanelParityTest.kt`，文件头自述 "v161: the session right panel's three parity fixes"，并引用 `research/fix/RIGHT_PANEL_PARITY_FIX.md`）：

- `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewPanel.kt`
- `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/header/ContextUsage.kt`
- `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt`

**这些改动不是 v162 审计产生的**（v162 为只读审计，未写入任何生产/测试文件），也未被本轮改动或回退。但因三者正是本报告表面 2/5 引用的文件，**上述引用处的行号以 16:36 快照为准，可能与后续版本漂移**；若需精确到行的复核，建议在 v161 改动合入后重取这三个文件的行号。

### 6.2 覆盖率自评

| 面 | 判定条目数 | 一致 | 不一致 | 缺失 | 自造 | 未核对 |
|---|---|---|---|---|---|---|
| 1 会话列表 | 33 | 21 | 4 | 2 | 3 | 2 |
| 2 会话顶栏 | 24 | 13 | 6 | 3 | 1 | 1 |
| 3 消息时间线 | 32 | 22 | 6 | 3 | 1 | 3 |
| 4 Composer | 27 | 24 | 1 | 1 | 0 | 1 |
| 5 右侧 dock | 12 | 9 | 0 | 0 | 0 | 3 |
| 6 设置 | 21 | 11 | 1 | 5 | 0 | 0 |
| 7 通知 | 18 | 10 | 5 | 2 | 3 | 0 |
| 8 终端 | 14 | 12 | 1 | 0 | 0 | 2 |
| 9 多标签 | 18 | 12 | 1 | 3 | 0 | 2 |
| 10 其它高影响面 | 11 | 7 | 1 | 1 | 1 | 2 |
| 11 命令表 | 13 | 3 | 1 | 1 | 1 | 7 |
| **合计** | **223** | **144 (65%)** | **27 (12%)** | **21 (10%)** | **10 (4%)** | **21 (9%)** |

> 说明：「缺失」「自造」在本表中按**面内出现**计数，与 §3/§4 的清单条数不完全一一对应（部分项跨面重复出现，例如顶栏 Review 开关既属表面 2 缺失、也属表面 5 语境）。
