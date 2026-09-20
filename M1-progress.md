# M1 进度记录（Compose 原生会话聊天屏 · 第一阶段）

> 里程碑定义见 `PLAN-android-native-refactor.md` §6。实现成员完成 + Lead 复核。
> 前置：M0 数据三修正 + Compose 主题/应用壳（见 `M0-progress.md`）。

## 已交付（`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/`）
| 文件 | 内容 |
|---|---|
| `SessionScreen.kt` | 顶部栏(h48 返回+标题) + 时间线 + 悬浮 composer；`observeSession(id)→start`/`stop` 生命周期；收集 `state/currentTitle/modelLabel/configMissing` |
| `timeline/MessageTimeline.kt` | `LazyColumn(rememberLazyListState())`，anchor-to-bottom + followOnAppend(80dp)、`items(key=id)`、turn 24dp / assistant part 12dp / user 右对齐 82% |
| `timeline/PartRenderer.kt` | 4-part 分派：text(Markwon 单例/AndroidView) · tool(纯 Compose 卡,图标着色无左色条,展开体 220dp 内滚) · reasoning(折叠) · compaction · file chip |
| `timeline/ToolPresentation.kt` | 纯 Kotlin 移植 `titleForTool/iconForTool/subtitleForTool/toolBodyText`（可 JVM 测） |
| `composer/Composer.kt` | 圆角10 `layer01`+0.5dp 边框；BasicTextField min60/max180 13sp；工具行 44dp；发送/停止 28dp(空文本 opacity.5, RUNNING→停止) |
| `ToolPresentationTest.kt` | 19 条 JVM 用例 |

接入改动：`ui/App.kt`（`OcApp(chatViewModel)`、`Route.SESSION` 挂屏）、`OcActivity.kt`（VM 创建/销毁）、`strings.xml`（+3 条）。**未改任何数据层/ChatView/MainActivity 语义**。

## 关键决策（成员 + Lead 认可）
1. 锚底：`SideEffect` 在 apply 后/measure 前读「追加前」`layoutInfo` 决定是否跟随 —— 规避 `derivedStateOf` 每像素重组与追加后读值必 false 的竞态；`appendKey` 含末条文本长度，流式增长也跟随。
2. composer 悬浮覆盖，实测高度回灌时间线 `contentPadding.bottom`。
3. 依 `research/02 §3.1` 推翻 View 版「左色条」，改图标着色 + layer01 surface。

## 验证
- [x] `:app:compileDebugKotlin` BUILD SUCCESSFUL（0 error）
- [x] `:app:testDebugUnitTest` BUILD SUCCESSFUL（**116 tests / 24 suites, 0 failed**，含新增 `ToolPresentationTest` 19 条）
- [ ] 真机手测（无设备；`adb shell am start -d "opencode-native://ui"` 后可进会话）

## 已知偏差 / 未做（记 `research/_M1_session_status.md`，M1.5/M2 处理）
- 工具副标题 13sp（规格 02/PLAN 写 14px，待统一）；reasoning 标题沿用 res `oc_thought`
- 文本暂不可选中（M1.5 用 `SelectionContainer`）；context ring / ⋮ 未做
- 附件(SAF/data URL/chip)、@-mention 与 / 命令、模型选择器、thinking row 与 paced-reveal、默认展开设置、grouped context/diff 汇总/todo/permission dock、Inter 可变字体、androidTest —— 下一阶段

## M1 退出条件对照（PLAN §6）
- [x] 时间线（流式+diff 结构）+ 4-part 渲染（tool/text/reasoning/compaction）基本成立
- [x] composer（文本输入 + 发送/停止两态）+ `message.part.delta` 追加生效（数据层）
- [x] 附件 / @-mention / 模型选择器 / 正文可选中（M1.5，见下）
- [ ] 真机连本地 server 流式收到完整文本（待真机）

---

# M1.5（会话屏增强）落地

交付（`ui/session/composer/`）：`AttachmentPolicy`（6×8MB/data-url/mime 兜底，纯 Kotlin 可测）、`AttachmentReader`（SAF→data url + ImageDecoder 降采样）、`AttachmentPicker`（OpenMultipleDocuments）、`AttachmentPreviewRow`（58×46 圆角6/非图 chip/删除钮）、`MentionText`（anchor/insert/classify/spans）、`MentionColors`、`MentionPopover`、`ModelChip`。
接入：`Composer`（附件行+@-mention+键盘）、`SessionScreen`（附件 state+picker+send）、`PartRenderer`（`SelectionContainer`）、`MessageTimeline`(user 气泡附件 chip + mention 着色)。
验证：`compile` + `test`(161/27) + `assembleDebug` 全绿；新增 `AttachmentPolicyTest`15 + `MentionTextTest`19 + `ComposerM1_5Test`11。
边界：markdown 正文经 AndroidView 目前仅 Compose 文本可选（TextView selectable 会吞拖动）；模型菜单为单条当前模型（目录/M2）；有意修正 View 图片 JPEG mime 缺陷。
未做：`/`、`!` shell、`+` 菜单其余项、agent/variant、ProviderIcon、附件渐隐遮罩、mention agent/reference 候选源；见 `research/_M1_5_status.md`。

---

# M1 审校修复（`research/_M1_review.md`）
审校结论：暂不进 M2，先修 3 条必须修。已全部修复并跑绿（168 tests/28 suites/0 failed）：
- ① **锚底语义**：改用 `reverseLayout`（item 0=最新在底，`scrollToItem(0)` 对高项也正确），弃 `scrollToItem(lastIndex)` 顶对齐错误；SideEffect 读 pre-append layout 决策保持不变。
- ② **appendKey 结构化**：新增 `appendFingerprint()`（消息数+末条 id+part 数+各 part text/toolOutput 长+toolStatus），覆盖新增 tool/file part 与流式/工具变化；`TimelineFingerprintTest` 7 条。
- ③ **标题回填**：`SessionScreen` 进会话后调 `listSessions {}` 回填 `currentTitle`。
- 顺带：`oc_compaction` 默认文案改英文「Session compacted」（zh 留待 i18n）。
采纳建议修的部分，其余（Markwon 每帧重解析/配色冻结、Enter 提交、stopping 语义、13sp vs 14px 规格裁决、空态薄等）记入 `research/_M1_review.md`，M2 推进前列为待办。
