# M1「会话聊天屏」第一阶段 落地状态（Compose 原生）

> ⚠️ 后续阶段：附件 / @-mention / 模型选择器 / 正文可选中 已由 **M1.5** 落地，
> 见 `research/_M1_5_status.md`（本文第 3、5 节的"占位/未做"项以该文为准）。

> 范围：消息时间线 + 4-part 渲染 + composer（文本输入/发送/停止）。
> 规格依据：`PLAN-android-native-refactor.md` §4.2 / §3.2 / §1.3 / §1.6、
> `research/02_ui_screens.md` §2/§3/§4、`research/07_motion_copy_p2.md`（无流式光标定案）。
> 参照实现（只读）：`ChatView.kt` L734/L768-888/L1030-1166。

## 1. 交付文件

| 文件 | 内容 |
|---|---|
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt` | 顶层会话屏：顶部栏（返回 + 标题，h-48dp）+ 时间线 + 悬浮 composer；`observeSession(id)` + `start()` / `stop()` 生命周期；`state/currentTitle/modelLabel/configMissing` 收集（`collectAsState`） |
| `…/ui/session/timeline/MessageTimeline.kt` | `LazyColumn(state = rememberLazyListState())`，anchor-to-bottom + followOnAppend(80dp 阈值)、`items(key = message.id)`、turn 间距 24dp、assistant part 间距 12dp、user 右对齐气泡(82%) |
| `…/ui/session/timeline/PartRenderer.kt` | 按 `part.type` 分派：text(Markwon/AndroidView) · tool(纯 Compose 卡) · reasoning(折叠) · compaction(一行) · file(chip)；另含 `MarkwonHolder` 单例 |
| `…/ui/session/timeline/ToolPresentation.kt` | 纯 Kotlin（可 JVM 测）：title/titleRes/icon/subtitle/bodyLines/diffLines/pretty/splitLines，1:1 移植 `ChatView` |
| `…/ui/session/composer/Composer.kt` | 圆角 10 `layer01` + 0.5dp `borderBase` 容器；BasicTextField min60/max180、13sp、padding 16/16/16/8；工具行 h44（+ 占位、模型 chip 占位、发送/停止 28dp 圆角 6） |
| `app/src/test/java/com/xiufeigo/opencodeshell/ToolPresentationTest.kt` | 19 条 JVM 用例（标题/图标/副标题/错误摘要/body 三类/diff/JSON/行切分） |

接入改动（未触碰任何既有语义）：

| 文件 | 改动 |
|---|---|
| `ui/App.kt` | `OcApp(isDark, chatViewModel: ChatViewModel? = null)`；`Route.SESSION` 挂 `SessionScreen`（`id` 取自 `savedStateHandle`/`arguments`）；`chatViewModel == null` 或 `id` 为空 → 原 M0 占位 |
| `OcActivity.kt` | 用 `remember` 创建 `ChatViewModel(ServerConfigStore(appCtx).also{restore()})`，`DisposableEffect` 销毁；传给 `OcApp` |
| `res/values/strings.xml` | 新增 3 条：`oc_compaction`(会话已压缩) / `oc_composer_placeholder`(Ask anything, / for commands, @ for context...) / `oc_back` |

**未改**：`ChatStore.kt` / `OpenCodeClient.kt` / `EventStream.kt` / `ChatViewModel.kt` / `ChatView.kt` / `MainActivity.kt` 的全部逻辑与签名。

## 2. 关键决策

1. **锚定底部用 SideEffect 取「追加前的」layoutInfo**，不用 composition 内 `derivedStateOf`。
   原因：追加后 LazyColumn 下一次 layout 才会把视口推离底部，此时再问「用户在底部吗」必然得到 false；
   而 `SideEffect` 在 apply 之后、measure 之前执行，读到的 `layoutInfo` 仍是上一帧内容 —— 决策值天然正确；
   同时避免把时间线订阅到每一次滚动的 layoutInfo（否则每个拖动像素都重组整个列表）。
   `LaunchedEffect(appendKey)` 的 key 含 `messages.size` 与末条 `text` 总长（流式增长也跟随）。
2. **composer 悬浮覆盖时间线**（复刻 web `paddingEnd 64` 让位），其 `onSizeChanged` 实测高度作为
   时间线 `contentPadding.bottom`，不写死常数。
3. **Markwon 用进程级单例 + `applicationContext`**：`AndroidView` 包 `TextView`，`update` 时
   `markwon.setMarkdown`，文本未变则跳过；配置与 `ChatView.buildMarkwon()` 逐项一致（同插件、同 res 色、同 theme 旋钮）。
4. **工具卡不画左竖条**：`research/02` §3.1 已推翻 View 版设计（左色条不存在），
   改为「图标着色 + `layer01` surface」；状态色 running=`accent`、error=`stateDangerFg`、done=`iconMuted`。
   卡片 = `layer01` + 圆角 6 + 0.5dp `borderBase`，只 trigger 行可点，chevron `animateFloatAsState` 180°。
5. **展开体渲染成一条 `AnnotatedString`**（而非每行一个 `Text`）：diff 增删行用 res `oc_diff_add_fg`/`oc_diff_del_fg`，
   JSON 行复用 `ChatView.jsonSpannable` 的 key/value 着色规则；容器 `heightIn(max=220dp)` + 内部 `verticalScroll`。
6. **无流式光标**（`07` 定案），运行中的标题用 1s alpha 脉冲（0.55↔1.0）近似 TextShimmer，M6 再精修。

## 3. 与规格的偏差（已在代码注释中标明，待 M1.5/M6 裁决）

| 项 | 任务描述 | 实现 | 理由 |
|---|---|---|---|
| 工具副标题字号 | 13sp | **13sp**（按任务） | 但 `research/02` §3.1 与 `PLAN` §4.2 写 14px；View 版 XML 是 13sp。三者不一致，取任务口径 + View 版，待统一 |
| reasoning 标题 | 「思考中 / Thinking」 | `R.string.oc_thought` = "Thought" | 「Thinking/思考中」是**会话级 thinking row**（`session-turn-thinking`）文案；reasoning **part** 的折叠标题在 i18n 里是 `ui.tool.thought` = Thought，且既有 res 已是它。thinking row 属 M6 |
| 正文 text 字号 | — | 14sp + 1.5 行距 | 对齐 `ChatView.textViewFor`（web v2 为 13px/440）；字体（Inter 可变字重）属 M6 |
| `+` / 模型 chip | 占位 | 只占位可点（空实现） | 附件/模型选择器属 M1 下一阶段/M2 |
| 文本可选中 | — | `setTextIsSelectable(false)` | 可选中的 `TextView` 会吞掉纵向拖动、破坏 LazyColumn 滚动；M1.5 用 `SelectionContainer` 补 |
| shrink 隐藏 | — | `todowrite` 不渲染 | 与 View 版一致，To-dos dock 后置 |
| 时间线底部 padding | 让位 composer | 实测 composer 高度 + 12dp | 见决策 2 |
| session directory | — | 传 null | 路由只带 `serverKey`/`id`；与 M0 记录的「directory 参数恒空」一致 |
| 会话标题栏右槽 | context ring + ⋮ | 未做 | 属 M1 后续阶段（`research/02` §2.2） |

## 4. 验证结果

```
JAVA_HOME=<temp>\jdk21\...   .\gradlew.bat :app:compileDebugKotlin
> Task :app:compileDebugKotlin
BUILD SUCCESSFUL            ← 0 error（唯一 warning 来自既有 ChatView.kt:107，非本次改动）

.\gradlew.bat :app:testDebugUnitTest
BUILD SUCCESSFUL            ← 116 tests / 24 suites，0 failed，0 error（含新增 ToolPresentationTest 19 条）
```

真机手测未做（本阶段无设备）：建议 deep link `adb shell am start -d "opencode-native://ui"`
后导航到 `server/{serverKey}/session/{id}`，检查流式追加跟随、工具卡展开、滚动到历史不被拽回、IME 抬升。

## 5. 未做项（明确留给后续阶段）

- 附件（SAF 选择、data URL、chip 删除、缩略图）与 `viewModel.send(text, attachments)` 的附件实参；`+` 仅占位。
- @-mention 弹层（`/find/file` 联动）与 `/` 命令、`!` shell 模式。
- 模型 chip 的 ModelSelectorPopoverV2、agent/variant 控件。
- context ring + ⋮ 菜单（重命名/分享/导出/归档/删除）、吸顶粘性头部完全体。
- thinking row（`Thinking` shimmer + `TextReveal`）、paced-reveal 打字节奏、默认展开设置
  （`shellToolPartsExpanded` / `editToolPartsExpanded`）、grouped context 工具组、diff 汇总行、todo dock、
  permission/question dock、followup 排队。
- 正文文本选择（`SelectionContainer`）、Markwon 增量/节流渲染优化、Inter 可变字体。
- Compose 仪器测试（androidTest）——本阶段只有 JVM 单测。
