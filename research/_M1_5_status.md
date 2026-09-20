# M1.5「会话屏增强」落地状态（Compose 原生）

> 范围：① 附件（SAF 选图/文件 + data URL + chip）② @-mention 补全 ③ 模型选择器 ④ 正文可选中。
> 规格依据：`PLAN-android-native-refactor.md` §4.2、`research/02_ui_screens.md` §3.4 / §4.2 / §4.4 / §4.5 / §4.6 / §4.7。
> 参照实现（只读）：`ChatView.kt` L101-109（launcher）/ L332-343（send）/ L345-458（mention）/
> L460-730（附件读取、chip、缩略图）/ L1031-1037（可选正文）。
> 基线：M1 第一阶段（`research/_M1_session_status.md`）。

## 1. 交付文件

| 文件 | 内容 |
|---|---|
| `ui/session/composer/AttachmentPolicy.kt` **新** | 纯 Kotlin（可 JVM 测）：`MAX_ATTACHMENTS=6` / `MAX_ATTACHMENT_BYTES=8MB`、`isTooLarge`、`dataUrl(mime,bytes)`（`java.util.Base64`）、`attachment()`、`merge()`（按 `filename+dataUrl` 去重 + 截断 + `dropped` 计数）、`imagePayload()`；`AttachmentMime`：`extensionOf` / `declaredIsUsable` / `resolve(declared,name,lookup)`（resolver → 扩展名 → `application/octet-stream`）/ `isImage` |
| `ui/session/composer/AttachmentReader.kt` **新** | Android 侧：`OpenableColumns.DISPLAY_NAME` 取名、`contentResolver.getType` + `MimeTypeMap` 兜底、图片 `ImageDecoder`（≤2048 长边、JPEG 85）降采样、`Dispatchers.IO` 上 `readAll()` → `Result(attachments, tooLarge, failed)` |
| `ui/session/composer/AttachmentPicker.kt` **新** | `rememberLauncherForActivityResult(OpenMultipleDocuments())` 注册 + `AttachmentPicker.pick()` 控制器；合并/超限/超尺寸 toast（`chat_attach_limit` / `chat_attach_too_large`） |
| `ui/session/composer/AttachmentPreviewRow.kt` **新** | 附件预览行：图片 58×46 圆角 6 + 0.5dp 内描边；非图片 `layer02` 圆角 6 chip（14dp 图标 + 12sp 文件名，120dp 上限）；右上 16dp 圆形删除按钮（`iconMuted` + `bgBase` 的「×」）；横向滚动；`LruCache` 缩略图（键=payload hash，不用整串 data URL） |
| `ui/session/composer/MentionText.kt` **新** | 纯 Kotlin：`anchorAt(text,caret)`（`@` 触发 + 空白关闭）、`insert(...)`（`@path ` 替换 + 失效判定）、`classify()`（file/agent/reference）、`spans()`（`@token` 扫描）、`candidates(paths)`（trim/去重/限 20）、`DEBOUNCE_MS=200` |
| `ui/session/composer/MentionColors.kt` **新** | `mentionColor(kind)`：file→`syntaxProperty`、agent→`syntaxType`、reference→`syntaxKeyword`；`mentionAnnotated()`（已发送正文着色）；`MentionColorTransformation`（`BasicTextField` 的 `VisualTransformation`，`OffsetMapping.Identity`） |
| `ui/session/composer/MentionPopover.kt` **新** | 弹层：贴编辑区上沿、`maxH 320`、圆角 12、`layer01` + `elevation-raised`、内边距 8、内部 `LazyColumn`、行 32dp 圆角 6 + 选中 `overlayHover`、file/agent 图标 |
| `ui/session/composer/ModelChip.kt` **新** | 模型 chip：h28 / `maxW 220` / 图标 16 + 名称 + `▾`；`DropdownMenu`（`containerColor=layer02`、圆角 8、0.5dp 边框、`shadowElevation`）+ 当前项 `ic_oc_check`；无数据 → toast `oc_model_picker_soon`；`ModelOptions.fromLabel()` |
| `ui/session/composer/Composer.kt` **改** | 参数扩为 `attachments / modelOptions / onAttachClick / onRemoveAttachment / onSelectModel / onSearchFiles / onSend(text, attachments)`；`TextFieldValue` + 防抖 `/find/file`（200ms，`LaunchedEffect(trigger)` 键=锚点+query）+ `onPreviewKeyEvent`（↓/↑/Enter/Esc）；附件行 + 弹层接入；发送后清空文本/附件 |
| `ui/session/SessionScreen.kt` **改** | 持有附件 state（`rememberAttachmentPicker`）、`onSend = { text, files -> chatViewModel.send(text, files); attachments = emptyList() }`、`onSearchFiles = chatViewModel.searchFiles`、切会话清空附件 |
| `ui/session/timeline/PartRenderer.kt` **改** | 新增 `SelectableBody`（`SelectionContainer`）；`text` 部分与 reasoning 正文包上；tool 卡展开体**不包** |
| `ui/session/timeline/MessageTimeline.kt` **改** | user 气泡：附件 chip 移到气泡**上方**、正文用 `mentionAnnotated` 着色并包 `SelectionContainer` |
| `app/src/test/.../AttachmentPolicyTest.kt` **新** | 15 条：data URL 格式/可解码、上限边界、合并去重/截断/计数、mime 兜底顺序、缩略图 payload 条件 |
| `app/src/test/.../MentionTextTest.kt` **新** | 19 条：触发锚点（caret=0/空白/最后一个 `@`/中间 caret/越界）、插入与失效判定、三类归类、token 扫描偏移、候选裁剪 |
| `app/src/test/.../ComposerM1_5Test.kt` **新** | 11 条：`ModelOptions` 语义 + 源码守卫（SAF 契约、`AttachmentPolicy.merge`、附件/mention/模型接线、防抖与陈旧结果守卫、`SelectionContainer` 位置且 tool 体未被包） |

**未改语义**：`ChatStore.kt` / `OpenCodeClient.kt` / `EventStream.kt` / `ChatViewModel.kt` / `ChatView.kt` / `MainActivity.kt`（本阶段 0 行改动）；
`PartRenderer` 既有 text/tool/reasoning/compaction 分派与 `ui/App.kt`、`OcActivity.kt` 未动。

## 2. 关键决策

1. **附件 state 提升到 `SessionScreen`，launcher 封装成 `rememberAttachmentPicker`**：
   `+` 按钮（Composer）→ 控制器 → SAF → `AttachmentReader`（IO）→ toast → `AttachmentPolicy.merge` → state。
   这样「预览行 / 发送 / 切会话清空」只有一个 owner，且 `Composer` 不碰 `contentResolver`（可测性：合并与上限规则在纯 Kotlin 里）。
   发送时把附件以 `ChatViewModel.Attachment(mime, filename, dataUrl)` 交给既有 `send(text, attachments)`，由 VM 打包成
   `ChatStore.UiPart(type="file", mime, filename, url=dataUrl)`（数据层 0 改动）。文本为空、只有附件时也允许发送（与 View 版一致）。
2. **图片重新编码后 mime 跟随字节**：`ChatView.downscaleOrRaw` 保留原始 mime，却写 JPEG 字节（`data:image/png;base64,<jpeg>`），服务端按 mime 解码会失败。
   这里降采样后 mime 改 `image/jpeg`；非图片/API<28 走原始字节与原 mime。这是**有意修正 View 版缺陷**，已在 `AttachmentReader` 注释标明。
3. **`data:` URL 用 `java.util.Base64`**（minSdk 26 可用）而非 `android.util.Base64`：后者在 JVM 单测里返回默认值，无法断言 `data:<mime>;base64,...` 这一**协议字符串**。校验规则（格式/上限/去重/mime 兜底）因此全部可 JVM 测。
4. **mention 用 `VisualTransformation` 着色，不维护 span 状态**：`BasicTextField(value=TextFieldValue)` + `MentionColorTransformation`，`OffsetMapping.Identity` 保证光标/选区映射不变；
   同一套 `MentionText.spans/classify` 也用于已发送 user 气泡的高亮，编辑器与时间线不会出现两套规则。
5. **弹层放在 composer 布局内（不是 `PopupWindow`）**：View 版把 `PopupWindow` 按 `rootView.height - composerTop` 贴到窗口底部，IME 一变就要重算；
   Compose 里 composer 本来就底部对齐，弹层作为其上方的兄弟节点天然"贴编辑区上沿"，随 IME 一起抬升，并且**不遮挡时间线**（只把 composer 撑高，`onSizeChanged` 会同步给时间线底部 padding）。
6. **触发/防抖用 `LaunchedEffect(trigger)` 而不是 Job 字段**：key = `Anchor(start, query)`，同一 query 内移动光标不重复请求，新按键自动取消上一个；
   回调经 `CompletableDeferred` 桥接（`searchFiles` 在 viewModel scope 回调），4s 超时兜底（无 client 时它**永不回调**），并用 `trigger != active` 丢弃陈旧结果，`MentionText.insert` 再做一次锚点校验。
7. **`SelectionContainer` 只包可读正文**：`text` 部分、reasoning 正文、user 气泡正文；tool 展开体（自带 `verticalScroll`）不包。
   ⚠️ **已知能力边界**：正文 markdown 走 `AndroidView`(Markwon)，Compose 的选择管线看不到 interop 子视图，所以**当前只有 Compose 文本（user 气泡）真正可选**；
   把 `TextView` 改成 `setTextIsSelectable(true)` 会吞掉纵向拖动、破坏 `LazyColumn` 滚动（M1 已实测），故保持 `false`。结构已就位，等 Compose 原生 markdown 渲染器落地后选择性自动生效。
8. **模型菜单只暴露真实可得的数据**：`ChatViewModel` 目前只有 `GET /config` 的默认模型（`modelLabel`），没有 provider/model 目录（M2 做），所以 `ModelOptions.fromLabel(label)` 返回**单条**（就是当前模型，选中即无变化），`modelLabel` 为空时整表为空 → 点击 toast「模型选择器稍后版本」。不臆造模型列表。
9. **发送/停止仍是 M1 口径**（运行中 = 停止按钮），未改成 `stopping = working() && blank()`：本阶段不改语义，排队 follow-up 属 M6。
10. **附件 chip 移到 user 气泡上方**（任务要求"内/上方"）：视觉上与 composer 的附件行同构；`message.parts` 里 file part 顺序不变（发送时仍是 text → file）。

## 3. 与规格的偏差 / 取舍（已在代码注释标明）

| 项 | 规格 | 实现 | 理由 |
|---|---|---|---|
| 删除按钮显隐 | hover 才出现（`opacity-0 group-hover:opacity-100`） | 常显 | 触摸设备无 hover；View 版在时间线里也是常显 |
| 预览行左右渐隐遮罩 | 24dp 渐变 | 未做 | 纯视觉装饰，留 M6 与横向滚动细节一起做 |
| 候选行右侧 keybind 列 / 空态「没有匹配项」 | §4.7 | 未做 | 候选为空即收起（与 `ChatView.showMention` 一致）；keybind 属命令面板 M6 |
| `/` 命令、`!` shell 模式、`+` 四项菜单 | §4.4 | 只做「图片和文件」一项（直接进 SAF） | 命令/shell 模式依赖命令目录与 M6 的 shell 轨 |
| ProviderIcon | §4.5 | 用 `ic_oc_model`（无 provider 图标资源） | 资源集里没有 provider 图标 |
| agent / variant 控件 | §4.1 工具行 | 未做 | M2/M6 |
| 正文真正可选 | 全部正文 | 仅 Compose 文本（user 气泡） | 见决策 7 的能力边界 |
| 弹层最大高 | 320dp | 320dp（`heightIn(max)`） | 规格一致；未做"可用高度自适应" |
| 附件数量/尺寸 | 6 × 8MB | 6 × 8MB | 与 View 版一致 |

## 4. 验证结果

```
JAVA_HOME=C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1

.\gradlew.bat :app:compileDebugKotlin
BUILD SUCCESSFUL            ← 0 error / 0 warning（本次改动的文件无警告）

.\gradlew.bat :app:testDebugUnitTest
BUILD SUCCESSFUL            ← 161 tests / 27 suites，0 failed，0 error
                              新增：AttachmentPolicyTest 15 + MentionTextTest 19 + ComposerM1_5Test 11 = 45
                              （M1 基线 116/24）

.\gradlew.bat :app:assembleDebug      ← CI 等价门禁（.github/workflows/build.yml）
BUILD SUCCESSFUL
```

真机手测**未做**（本会话无设备），建议按下列顺序验证：
1. `+` 选 2 张图 + 1 个文件 → 预览行出现缩略图/文件名与删除按钮；再选 7 个 → 出现"至多 6 个"toast；
2. 发一条只有附件没文字的（发送按钮应可用）；服务端收到的 `parts` 为 `[{file,mime,url,filename}]`，且图片 data URL 的 mime 与字节一致；
3. 输入 `@` → 弹层出现在编辑区上沿（键盘弹起时也应贴住），继续输入路径片段 → 候选收敛；点选/回车 → `@路径 ` 变成 property 色；↑/↓/Esc 生效；
4. 运行中点模型 chip → 单条菜单（或 toast）；会话切换 → 附件清空；
5. 长按 user 气泡正文 → 系统选择菜单；确认时间线纵向滚动仍正常（含 tool 卡展开体的内部滚动）。

## 5. 未做项（明确留给后续）

- `/` 命令菜单、`!` shell 模式、`+` 菜单其余三项（Images and files 之外的 Commands / Context / Shell）。
- agent / variant 控件、ProviderIcon、真实 provider/model 目录与切换（M2）；`onSelectModel` 目前只在"已选中当前模型"时无事发生。
- 附件预览行的 hover 显隐、左右 24dp 渐隐遮罩、拖拽添加（`Drop files to attach`）。
- 正文（Markwon/`AndroidView`）真正的文本选择：需 Compose 原生 markdown 渲染器；跨 part/跨消息选择也未做。
- 弹层候选的 agent/reference/resource/command 来源（`sync().data.reference` / `agents` / `mcp_resource` / `command`）与 `mention` 图标/描述列。
- `stopping = working() && blank()` 的排队语义、thinking row、diff 汇总行、todo/permission/question dock（M6）。
