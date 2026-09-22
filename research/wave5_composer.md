# Wave 5-C — 数据管线 + 输入区(Composer) 交付报告

工作目录 `C:\sensorsdata\main\program\opencode-android-shell`；改动范围严格限制在
`OpenCodeClient.kt`（纯加法）、`ChatViewModel.kt`（纯加法/新公开流）、`ui/session/composer/**`、
三份 `strings.xml`（只追加，前缀 `oc_composer_` / `oc_slash_` / `oc_attach_`）、
`app/src/test/java/com/xiufeigo/opencodeshell/**`（新增 7 个测试类）、本文件。
`ui/App.kt`、`ui/session/SessionScreen.kt`、`timeline/**`、`header/**`、`review/**`、
`ui/settings/**`、`ui/theme/**`、`ChatStore.kt`、gradle 文件、`_upstream/**` **未改动**。

---

## 0. 结论速览

| 项 | 状态 | 备注 |
|---|---|---|
| B1 斜杠命令 | ✅ 完成（含端到端） | 内置 `/model` `/agent` + `GET /command` 全量；发送走 `POST /session/:id/command`（实测 200） |
| B2 「+」四项菜单 | ✅ 完成 | 附件/命令/上下文/Shell，另加「粘贴」(B6)；`min-width:180px` |
| B3 Shell 模式 | ✅ 完成（含端到端） | **不是** `prompt_async` 的 `mode` 字段，而是 `POST /session/:id/shell`（实测 200，见 §1.3） |
| B4 agent 选择器 + variant | ✅ 完成（含端到端） | `GET /agent` chip + `variants` 子选择；`prompt_async` 发 `agent`/`variant`（实测 204 且回显） |
| B5 附件渐隐/图片预览 | ✅ 完成 | 24dp 两端渐隐 + 应用内 `Dialog` 预览 |
| B6 粘贴/进度/取消 | ✅ 完成（粘贴入口有偏差，见 §3.6） | Compose 无 `onPaste`，改为「+」菜单「粘贴」行 + 硬件 `Ctrl+V` |
| B7 输入体验 | ✅ 完成 | 自动聚焦、↑↓ 历史（按会话）、Esc/Ctrl+G 停止回合 |
| B8 草稿 | ✅ 完成 | 按 `sessionID` 分槽 + 发送失败回填 |
| B9 `@` 候选类别 | ✅ 完成 | agent/command/skill/file 四类，行内类别图标+标签，插入仍是纯文本 mention |

**接线状态**：SessionScreen 的新参数接线**已由 Lead 完成**（`ui/session/SessionScreen.kt:457-479`，23:15:24），
9 项功能均为端到端连通；接线后构建门复跑仍 BUILD SUCCESSFUL（85 suites / 818 tests / 0 failures）。
`Composer(...)` 的新参数全部有默认值，未接线时也能编译并保持 M1.5 行为，参数契约表见 §4。

---

## 1. 真实端点实测（opencode 1.18.31，`http://127.0.0.1:4096`）

所有结论都来自本机真实请求（`/doc` 的 openapi + 实际调用），不是猜测。

### 1.1 三个只读目录

```
GET /agent   -> 200, 数组 275 条: [{name,description,mode,native,hidden,topP,temperature,color,
                 permission,variant,prompt,options,steps}]
                 mode: primary=5 (build, compaction, plan, summary, title) / subagent=270
                 hidden=true 3 条
GET /command -> 200, 数组 75 条: [{name,description,source,template,hints}]
                 source: command=2 / skill=73
GET /skill   -> 200, 数组 73 条: [{name,description,location,content}]
GET /global/health -> 200 {"healthy":true,"version":"1.18.31"}
```

### 1.2 模型 variant（B4 的字段来源）

`GET /provider` 的 `all[].models.<id>` 带 `variants`（openapi `Model.variants` =
`additionalProperties: object`），例：

```
openai/gpt-5.6-sol-fast : variants = none|low|medium|high|xhigh|max
anthropic/claude-opus-4.8: variants = low|medium|high|xhigh|max
```

`prompt_async` 的 openapi body 是
`{messageID?, model?{providerID,modelID}, agent?, noReply?, tools?, format?, system?, variant?, parts[必填]}`
→ `agent` 与 `variant` 都是官方字段。实测：

```http
POST /session/<ses>/prompt_async
{"parts":[{"type":"text","text":"Reply with the single word VARIANT_OK"}],
 "model":{"providerID":"openai","modelID":"gpt-5.6-sol-fast"},"agent":"build","variant":"low"}
-> 204
读回 /session/<ses>/message: assistant info: modelID=gpt-5.6-sol-fast agent=build variant=low
```

### 1.3 ⚠️ B3 的关键发现：`mode:"shell"` 无效，真路由是 `/shell`

按任务书的实验先做了 `mode`：

```http
POST /session/<ses>/prompt_async  {"parts":[{"type":"text","text":"echo hi"}],"mode":"shell"}
-> 204（被接受，但字段不在 prompt_async schema 里）
读回消息: user info.agent=build, info.mode="" ; assistant agent=build mode=build
        → 服务端**忽略** mode，没有进 shell 模式
```

随后从 openapi 里查到真路由 `POST /session/{sessionID}/shell`
（operationId `session.shell`，requestBody `required:[agent, command]`，`model` 是对象）：

```http
POST /session/<新建会话>/shell  {"agent":"build","command":"echo wave5shell"}
-> 200
{"info":{... "mode":"build","agent":"build" ...},
 "parts":[{"type":"tool","tool":"bash","state":{"status":"completed","output":"wave5shell\r\n"}}]}
```

对照上游：`_upstream/packages/app/src/composer/submit.ts:296-298`
`await session.api.shell({sessionID, id, command})` —— 上游 shell 模式本来就是独立路由，
不是 `prompt_async` 的字段。**所以 B3 可以端到端**，本实现走 `/shell`。
（探测用的 3 个一次性会话已全部 `DELETE /session/:id` 清理，返回 200。）

### 1.4 斜杠命令发送（B1）

`POST /session/{sessionID}/command` 的 body（openapi）：
`{command[必填], arguments[必填], agent?, model?(**字符串** "providerID/modelID"), variant?, messageID?, parts?}`。
注意与 `prompt_async` 的 `model` 对象形状不同，本实现分别构造（§2.2）。

---

## 2. 逐项交付（文件 file:line / 上游锚点 / 自测方式）

### 2.1 B1 斜杠命令

- 纯逻辑：`ui/session/composer/ComposerSuggestions.kt:64`（`commandQuery`，`^/(\S*)$`）、
  `:77`（`commandItems`：内置 `/model`,`/agent` + 服务端列表）、`:191`（`invocation`
  `/name args` → `{command,arguments}`）、`:210`（`expandTemplate`，`$ARGUMENTS` 兜底展开）。
- UI：`Composer.kt:305`（onValueChange 里派生 `slashQuery`）、`:231`（`accept`：
  内置命令清空草稿并打开对应选择器 + `modelOpenRequest`/`agentOpenRequest`）、`:345`（`submit`）、
  弹出层复用 `MentionPopover.kt:50`（同一张卡承载 `/` 与 `@` 两种列表，↑↓/Enter/Tab/Esc）。
- 数据：`OpenCodeClient.kt:1057` `commands()`；`ChatViewModel.kt:444` `refreshCatalogs()`。
- 发送：`Composer.kt:345` → `ComposerSubmit.Payload(command,arguments)` →
  `ChatViewModel.kt:613 submit()` → `client.runCommand`（`OpenCodeClient.kt:1029`）。
- 上游锚点：`composer/suggestions/machine.ts:107-114`（`/^\/(\S*)$/`）、
  `:122-134`（`commands.open`）、`:164-192`（选中语义，内置清空草稿）、
  `composer/commands.tsx:49-82`（内置 `/model`、`/agent`）、
  `composer/editor/interaction.ts:132-136,169-177`（过滤与选中）、
  `composer/submit.ts:300-322`（`findCommand` + `sendCommand`）。
- 自测：`Wave5ComposerSuggestionsTest`（11 例）、`Wave5ComposerWiringTest`。
- 未决：无（服务器不在 `GET /command` 里的 `/xxx` 按上游语义当普通 prompt 发送，未拦截）。

### 2.2 B2 「+」四项菜单

- `Composer.kt:701` `AddMenuButton` + `AddMenuRow`：附件（走现有 SAF `onAttachClick`）/
  命令（插入 `/` 并开列表）/ 上下文（插入 `@` 并开列表）/ Shell（进 shell 模式并清空）+
  粘贴（B6）。`ADD_MENU_MIN_WIDTH = 180.dp`，向上弹出（按钮在屏幕底部），间距 6dp。
- 上游锚点：`composer/editor/editor.tsx:613-671`（`ComposerEditorAddMenu`、
  `min-width:180px`、`gutter={6}`、`placement="top-start"`、`attachShortcut`）。
- 自测：`Wave5ComposerWiringTest.composerWiresTheSlashMenuAndTheAddMenu`。

### 2.3 B3 Shell 模式

- 进入：输入恰好 `!`（`Composer.kt:414` 附近，`machine.ts:93-97`）或菜单 Shell 行；
  退出：Esc、空输入 Backspace（`machine.ts:203-206`，`Composer.kt` onPreviewKeyEvent）、
  以及一个「Shell」徽标（**本机偏差**：手机上无 Esc 键，见 §3.1）。
- 视觉：`FontFamily.Monospace` + 占位 `oc_composer_placeholder_shell`（"输入 shell 命令，例如 git status"），
  「+」/agent chip/model chip 隐藏（对应上游 `inert` 容器 `editor.tsx:253-281`）。
- 提交：`ComposerSubmit.canSubmit(..., shell=true)` 要求非空文本、不要附件
  （上游 `interaction.ts:343-354`）→ `ChatViewModel.sendShell`（`:682`）→
  `OpenCodeClient.shellCommand`（`:1007`）`POST /session/:id/shell`，`agent` 取
  `effectiveAgent()`（默认 `build`，openapi 必填）。
- 自测：`Wave5OpenCodeClientTest.shellCommandUsesTheDedicatedShellRoute` /
  `shellPayloadAlwaysCarriesTheRequiredCommandAndAgent`；`Wave5ComposerWiringTest.composerWiresShellModeAndItsRoute`。
- 未决：无（已有真实 200 证据，§1.3）。

### 2.4 B4 agent 选择器 + 模型 variant

- agent chip：`AgentChip.kt:66`（`GET /agent` 只列 `mode=="primary" && !hidden`，含「服务器默认」行、
  `openRequest` 供 `/agent` 打开）。
- variant：`ModelPicker.kt:128` `variantOptions`（`default` + `variants` 键）、`:144` `resolveVariant`
  （镜像 `variant.ts:31-35`）、`:154` `cycleVariant`、`:275` 选择器内 variant 分组；
  `ModelChip.kt:94` 把当前 variant 以 `模型名 · 变体` 显示在 chip 上（手机工具栏放不下第二个 chip）。
- 发送：`ChatViewModel.kt:513 effectiveVariant()`（用户 pin → 校验在 catalog 里；否则用 agent 自己配的
  variant，镜像 `getConfiguredAgentVariant`）→ `promptAsync(variant=…)`（`OpenCodeClient.kt:977`）。
  `selectModel`（`ChatViewModel.kt`）切模型时清掉不再适用的 variant。
- 上游锚点：`providers/models/variant.ts:11-40`、`composer/editor/editor.tsx:284-307`、
  `composer/commands.tsx:58-64`。
- 自测：`Wave5ModelVariantTest`（4 例）、`Wave5OpenCodeClientTest` 的
  `modelVariantsComeFromTheCatalogKeys` / `promptAsyncCarriesAgentVariantAndModel` /
  `promptAsyncOmitsUnpinnedSelections` / `blankSelectionsAreNeverPutOnTheWire`。

### 2.5 B7 输入体验

- 自动聚焦：`Composer.kt:249`（`FocusRequester` + `withFrameNanos {}` + `requestFocus()`，
  以 `draftKey` 为 key，进会话/切会话各一次）。
- 历史：`PromptHistory.kt:25`（每会话一份，normal/shell 分栈，newest-first，`MAX_ENTRIES=100`，
  空行/重复不入栈，失败发送 `remove`）；`ComposerHistory.kt`（同一文件 `:138`）按 key 持有；
  `Composer.kt:517` 处理 ↑/↓。上游：`interaction.ts:261-298,468-473`、`history/entry.ts:8,28-61`。
- Esc 停止回合：`Composer.kt` onPreviewKeyEvent（Esc 或 Ctrl+G，且 `running`）→ `onStop()`；
  优先级镜像上游：先 shell 退出，再停止（`editor/interaction.ts:225-233`）。
- 自测：`Wave5PromptHistoryTest`（10 例）、`Wave5ComposerWiringTest`。

### 2.6 B8 草稿

- `ComposerDrafts.kt:25`：`Map<key, Draft(text, pendingRestore)>` 的 `StateFlow`；
  `restore`（`:65`）由 ViewModel 在发送失败时调用，`takeRestore`（`:74`）由 Composer 消费一次。
- Composer：`Composer.kt:242` 以 `draftKey = ComposerDrafts.key(sessionID)` 做 `rememberSaveable` key，
  每次输入 `ComposerDrafts.set`，`LaunchedEffect` 里 adopt 失败回填。
- 失败回填 + 历史撤销：`ChatViewModel.kt:696 restoreDraft()`（`ComposerDrafts.restore` +
  `ComposerHistory.of(key).remove`，镜像 `submit.ts:407-418` + `entry.ts:51-61`）。
- 自测：`Wave5ComposerDraftsTest`（8 例）、`Wave5ComposerWiringTest.aFailedSendRestoresTheComposerDraftAndDropsItsHistoryEntry`。
- 未决：草稿按 key 存活于进程内（Brief 允许"内存栈"）；未接 `SettingsStore` 持久化，杀进程丢失。

### 2.7 B9 `@` 候选类别

- `ComposerSuggestions.mentionItems`（`:129`）：agent → command/skill → file，
  过滤规则同上游 `filterKeys:["label"]`；文件行仍由 `MentionText.candidates` 去重/截断。
- 行渲染：`MentionPopover.kt`（类别图标：agent=`ic_oc_task`、command=`ic_oc_console`、
  skill=`ic_oc_code`、file=`ic_oc_file`；尾列显示服务端 description，否则类别名）。
- 插入仍是纯文本：`Composer.kt:231 accept()` 用 `MentionText.insert` 插 `@label `。
- 上游锚点：`editor/interaction.ts:107-131`（分组与排序）、`:185-189`（skill 语义）。
- 自测：`Wave5ComposerSuggestionsTest` 的 4 个 mention 用例。

### 2.8 B5/B6 附件

- 24dp 渐隐：`AttachmentPreviewRow.kt:135 AttachmentEdgeFade`，仅在 `scroll.value>0` /
  `scroll.value<maxValue` 时绘制（`FADE_WIDTH = 24.dp`，`:147`）。上游 `editor.tsx:600-607`
  （渐隐色用 `layer-01` 而不是 `bg-base`，因为我们的条带画在 `layer-01` 卡片上，见 §3.2）。
- 图片点击预览：`AttachmentPreviewRow.kt:224 AttachmentPreviewOverlay`（`Dialog` 全屏 scrim +
  关闭按钮；解码按 1080px 目标采样）。
- 粘贴：`AttachmentPolicy.pasteKind`（`:115`）/ `isLargePaste`（`:123`）：
  ≥8000 字符或 ≥120 换行 → `LARGE_TEXT`（作为一次性文本块插入，仍是 prompt 的 text part），
  剪贴板含图片/文件项 → `ATTACHMENT`（走同一 SAF 读取通道）。入口：菜单「粘贴」行 +
  硬件 `Ctrl+V`（Composer 的 onPreviewKeyEvent）。
- 上传进度 + 取消：`AttachmentUploads.kt:26`（单例队列，`:54 start` / `:84 cancel`；
  取消后回调不触发，不产生半份选择），`AttachmentReader.kt:51` 每篇文档回调
  `(index,total,label)` 并在每次迭代 `ensureActive()`；`AttachmentPicker.kt:53` 改为入队；
  UI 在 `Composer.kt:867 UploadProgressRow`（track+accent 填充 + `done/total` + 取消）。
- 自测：`Wave5AttachmentPasteTest`（5 例）、`Wave5ComposerWiringTest.thePickerReadsThroughTheUploadQueueSoProgressAndCancelExist`。

---

## 3. 与上游/规格的偏差（均已记录，非静默）

1. **Shell 退出徽标**（新增非上游元素）：手机无 Esc 键，`+`/chip 在 shell 模式又必须隐藏，
   故在同一个 28dp 槽位放一个「Shell」徽标（点击退出）。上游只有 Esc/空输入 Backspace。
2. **渐隐颜色**：上游 `linear-gradient(to right, --v2-background-bg-base, transparent)`；
   我们的条带画在 `layer-01` 卡片上，改用 `layer-01`，否则接缝可见。
3. **`@` 候选不去重跨类别**：与 agent 同名的文件仍会出现（上游按 id/kind 分组，行内图标区分），
   已在测试里显式固定该语义。
4. **文件候选不再二次过滤**：`/find/file?query=` 已经过滤，客户端只做 trim/去重/截断（同上游 `searchContextFiles`）。
5. **模型 variant 展示**：上游是并列的第二个下拉控件；手机工具栏改为「模型名 · 变体」后缀 +
   选择器内 variant 分组（同一份 `variants` 数据，仍是子选择）。
6. **粘贴入口**：Compose `BasicTextField` 没有 DOM 式 `onPaste`，因此经「+」菜单「粘贴」行与硬件
   `Ctrl+V` 触发（阈值判定与 SAF 通道完全复用）。
7. **`ComposerSubmit` 可见性**：由 `internal object` 放宽为 `public object`（其 `Payload` 出现在 public
   的 `Composer` 参数上，Kotlin 不允许 public API 暴露 internal 类型）。行为未变。

---

## 4. SessionScreen 接线（✅ 已由 Lead 完成，2026/9/20 23:15）

接线已落地在 `ui/session/SessionScreen.kt:457-479`（Lead 于 23:15:24 写入，我未改该文件）：

```kotlin
sessionID = activeSession.id,
agents = availableAgents,
selectedAgent = selectedAgent,
agentOverrideActive = selectedAgent != null,
onSelectAgent = { chatViewModel.selectAgent(it) },
onUseDefaultAgent = { chatViewModel.selectAgent(null) },
commands = availableCommands,
skills = availableSkills,
selectedVariant = selectedVariant,
onSelectVariant = { chatViewModel.selectVariant(it) },
onSubmit = { submission ->
    chatViewModel.submit(
        submission.text, submission.attachments, submission.shell,
        submission.command, submission.arguments,
    )
},
onPasteAttachments = { pasted -> attachments = pasted },
```

接线后重跑构建门：**BUILD SUCCESSFUL**（85 suites / 818 tests / 0 failures，见 §5.2）。
以下参数说明保留作为契约文档；`Composer(...)` 的新参数全部有默认值，因此**不接线时也能编译并保持 M1.5 行为**
（老 `onSend` 路径），便于分阶段验收：

| 参数 | 作用 | 不接线的后果 |
|---|---|---|
| `sessionID` | B8 草稿 / B7 历史按会话分槽 | 单槽草稿、历史不随会话区分 |
| `agents` + `selectedAgent` + `agentOverrideActive` + `onSelectAgent` + `onUseDefaultAgent` | B4 agent chip | chip 不渲染（`agents` 为空即隐藏） |
| `commands` | B1 斜杠菜单的服务端半区 | 只有内置 `/model`、`/agent` |
| `skills` | B9 `@` skill 类别 | `@` 只有 agent/command/file |
| `selectedVariant` + `onSelectVariant` | B4 variant 子选择 | 选择器无 variant 分组 |
| `onSubmit` | B1/B3 三种写路由 | 服务端斜杠命令与 shell 模式不发送（回落 `onSend`） |
| `onPasteAttachments` | B6 粘贴图片回写屏幕附件列表 | 粘贴图片无效（大文本粘贴不受影响） |

`ChatViewModel` 侧已就绪：`availableAgents/Commands/Skills`、`selectedAgent/selectedVariant`、
`selectAgent/selectVariant`、`submit(…)`、`sendShell(…)`、`runCommand(…)`（`ensureClient()` 时自动拉目录）。

---

## 5. 测试与构建门

### 5.1 新增测试（JVM，`app/src/test/java/com/xiufeigo/opencodeshell/`）

| 文件 | 覆盖 |
|---|---|
| `Wave5ComposerSuggestionsTest.kt` | `/` 触发、菜单候选/过滤/截断、`/name args` 解析、模板展开、`@` 四类别 |
| `Wave5ComposerDraftsTest.kt` | 分槽、一次性回填、失败后继续输入、跨会话不串 |
| `Wave5PromptHistoryTest.kt` | 新→旧、↑↓ 与会话草稿回退、caret 门控、分模式栈、100 上限、失败删除 |
| `Wave5AttachmentPasteTest.kt` | 8000 字符 / 120 换行阈值、图片优先于文本 |
| `Wave5ModelVariantTest.kt` | variant 选项、resolve、循环、`default` 语义 |
| `Wave5OpenCodeClientTest.kt` | `/agent`、`/command`、`/skill`、`/global/health` 解析、`variants` 解析、三个写 body（MockWebServer 实发路径 + 纯 payload 构造） |
| `Wave5ComposerWiringTest.kt` | 源码级接线守卫 + 三语 strings 完整性/未漏译 |

实测（`:app:testDebugUnitTest --tests '*Wave5*' --tests '*ComposerSubmitTest*'`）：
**129 tests completed, 0 failures（本 Wave + M1.5 相关用例全绿）**。

### 5.2 构建门（最终结果 ✅）

```
$env:JAVA_HOME='C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL
```

- 结果汇总（`app/build/test-results/testDebugUnitTest/*.xml`，时间戳 2026-09-20T15:14 = 本机 23:14）：
  **suites = 85，tests = 818，failures = 0，errors = 0**。
- 产物：`app/build/outputs/apk/debug/app-debug.apk`（2026/9/20 23:14:04，31,053,292 bytes）。
  最后一次改动（把发送按钮的可用态与 shell 模式的 `canSubmit` 对齐）后重跑仍为
  **BUILD SUCCESSFUL**（`81 actionable tasks: 8 executed, 73 up-to-date`）。
- **接线后复跑**（SessionScreen 于 23:15:24 接上全部新参数，§4）：再次
  **BUILD SUCCESSFUL**，**suites = 85 / tests = 818 / failures = 0**——即「全功能接线」的树也是绿的。
- 我动手前的基线（本机实测，`:app:testDebugUnitTest` 全绿时）：`suites=72 tests=637 failures=0`
  （任务书写的 70/628 略低，可能是更早的快照；以实测为准）。
  本 Wave 新增 7 个测试类（44 个用例），其余增量来自并行 Wave。
- 关键既有守卫未被破坏：`ComposerM1_5Test`、`ComposerSubmitTest`（含
  `aSendClearsTheEditor` 对 `submit()` 内 `value = TextFieldValue("")` / `trigger = null` 的源码断言）
  以及 `MentionTextTest` 全部通过。

### 5.3 单元测试工作目录（给并行 Wave 的提醒）

`:app:testDebugUnitTest` 的 CWD 是 **module 目录 `app/`**，因此源码扫描型测试应写
`File("src/main/res/values/strings.xml")`；本 Wave 的 `Wave5ComposerWiringTest.source()` 采用
`File(relative)` → `File("app/$relative")` 双候选（与 `ComposerM1_5Test` 一致），两种 CWD 都能跑。

---

## 6. 并发构建与并发改动记录（原文，均已恢复）

本工作区同时有其他 Wave 的 agent 在编辑 `ui/servers/**`、`ui/settings/ServersPanel.kt`、
`ui/session/review/**` 与三份 `strings.xml`，期间出现过以下**非本 Wave 代码**的干扰，
最终在同一轮里等到对方保存后自行消失，构建门已转绿（§5.2）：

1. 2026/9/20 22:5x，`:app:compileDebugKotlin` 被他人在建文件打断（原文节选）：

```
e: .../ui/servers/ServerHealth.kt:268:1 Syntax error: Unclosed comment.
e: .../ui/servers/ConnectServerScreen.kt:86:28 Unresolved reference 'ServerHealthProbe'.
e: .../ui/settings/ServersPanel.kt:41:46 Unresolved reference 'HealthMessage'.
```

   对方保存后 `:app:compileDebugKotlin` 恢复通过（我的 4 个 composer 编译错误已单独修掉，见 §6.4）。

2. 2026/9/20 23:0x，并发的 Gradle 进程占用测试输出目录（原文）：

```
e: Daemon compilation failed: null
Failed to compile with Kotlin daemon: java.lang.Exception
> java.io.IOException: Unable to delete directory
  '...\app\build\test-results\testDebugUnitTest\binary'
  Failed to delete some children. This might happen because a process has files open
  - ...\app\build\test-results\testDebugUnitTest\binary\output.bin
```

   按任务书等 60 秒重试；此外还捕获到 Kotlin 增量编译的并发报错：

```
[KOTLIN] [IC] Incremental compilation was attempted but failed:
    Failed to compute files to recompile: java.lang.IllegalStateException: Storage for
    [...\app\build\kotlin\compileDebugUnitTestKotlin\cacheable\caches-jvm\lookups\lookups.tab]
    is already registered
```

   等待 ~2 分钟后重跑即 BUILD SUCCESSFUL（§5.2）。

3. 期间全量 `:app:testDebugUnitTest` 曾出现 17 → 3 个失败，失败用例**全部**属于其他 Wave 的
   在建文件（`ReviewWave5Test` 6、`Wave5ConnectTest` 3~11，含一个把测试 CWD 当仓库根的
   `File("app/src/...")` 断言）；我方未改动这些文件，并发 Wave 自行修复后同一命令转绿。

4. 本 Wave 自己被编译器抓到的 4 个问题（已修，记录备查）：
   `ComposerSubmit.Payload` 可见性（public composable 不能暴露 internal 类型）、
   `Composer.kt` 漏 import `onSizeChanged`、`PromptHistory.entries` 缺默认参数、
   `aSendClearsTheEditor` 的 M1.5 源码断言要求 `submit()` 内联出现
   `value = TextFieldValue("")` 与 `trigger = null`（已把清空动作前移到 submit 开头）。

---

## 7. 未决项清单

1. ~~`SessionScreen` 接线~~ → **已由 Lead 完成**（§4），全部功能已端到端连通。
2. 草稿未做进程外持久化（Brief 允许内存栈）。
3. 附件发送失败时**附件不回到屏幕状态**（`attachments` 属于 SessionScreen；只回填文本，同 Brief 要求）。
4. `GET /config/providers` 未接入（现有代码用 `GET /provider`，含 `variants`，够用）。
5. `POST /session/:id/command` 失败时的模板展开兜底（`ComposerSuggestions.expandTemplate`）
   已实现并测试，但当前路由选择不依赖它——服务器不可用时按普通 prompt 发送由上游语义决定，未额外切换。
6. 设备/模拟器上的**真机验收未做**（按任务约定由 Lead 统一验证）；建议至少点一遍：
   `/` 菜单选中 `/init` → 发送 → 看 `POST /session/:id/command`；`!` → `echo hi` → 看 `/shell`；
   agent chip 切换；模型选择器里的 variant 分组；`@` 四类别；附件两端渐隐/点击预览；发送失败回填草稿。

---

## 9. W5-G 修复：长描述把斜杠候选行的 `/名字` 挤出行（追加，2026/9/21）

> **本节为追加内容，§0–§8 原文一字未改。**
> 改动范围：`ui/session/composer/MentionPopover.kt`（行布局）、
> `ui/session/composer/SuggestionRowText.kt`（新增，纯逻辑）、
> `app/src/test/java/com/xiufeigo/opencodeshell/Wave5ComposerRowLayoutTest.kt`（新增）、本文件。
> 三份 `strings.xml` 未改（无需新串，回退用的是既有 `oc_composer_mention_*`）；
> `ui/App.kt`、`SessionScreen.kt`、`timeline/**`、`review/**`、`ui/settings/**`、`ChatStore.kt`、
> `ChatViewModel.kt`、`OpenCodeClient.kt`、`theme/**`、gradle、`_upstream/**` 均未触碰。
> **B1 的触发侧（`HalfWidthText`）一行未动**，只改候选行的排版。

### 9.1 现象

Lead 真机（平板 2560×1600）复现，证据 `research/wave5_shots/v6-slash-tablet.png`：
`/` 菜单（`GET /command` 本机 75 条）里 `customize-opencode` 与 `jira-SCB-Collector` 两行
**只剩类别图标 + 描述，`/名字` 完全不见**；其余 8 行（描述都短）label 正常。

### 9.2 根因：Compose `Row` 先量「无 weight 的子项」，label 被量成 0 dp

代码位置：`ui/session/composer/MentionPopover.kt:115 SuggestionRow`（修前是同一函数的旧实现）。
修前该行是：`Text(label, modifier = Modifier.weight(1f))` + `Text(description, maxLines = 1, Ellipsis)`
（描述**没有** weight）。

Compose 的测量顺序（**权威依据**：本机 Gradle 缓存里
`androidx.compose.foundation:foundation-layout-android:1.7.6` 的 sources jar，
`~/.gradle/caches/modules-2/files-2.1/androidx.compose.foundation/foundation-layout-android/1.7.6/`)
——注意这是**库源码**，不是本仓文件：

- `commonMain/.../Row.kt:49-51`（KDoc）："If a child is not provided a weight, it will be
  asked for its preferred width **before** the sizes of the children with weights are
  calculated proportionally to their weight based on the remaining available space."
- `commonMain/.../RowColumnMeasurePolicy.kt:101`（源码注释）：`// First measure children with zero weight.`
- 同文件 `:118,:127`：无 weight 的子项拿到 `remaining = mainAxisMax - fixedSpace`；
  `:139` `fixedSpace += placeableMainAxisSize`；
- 同文件 `:150-166`：有 weight 的子项之后才按 `weightUnitSpace = remainingToTarget / totalWeight`
  分配，且 `:159` 先 `coerceAtLeast(0)`。

即：**无 weight 的描述被"先按偏好宽度"测量**，而 `Text(maxLines = 1, overflow = Ellipsis)`
的偏好宽度就是整行可用宽度（长描述取满 `remaining`）→ `fixedSpace` 随即等于整行 →
之后 label 作为唯一 weighted 子项拿到 `remainingToTarget = (R − R) = 0` → **宽 0 dp**，
于是整行只剩图标与描述。这也解释为什么只有两条超长描述的命令复现。

数据侧无辜：`ComposerSuggestions.commandItems` 一直产出了 `label = "/customize-opencode"`；
本节的测试用真机上那两条命令的**真实超长描述**跑 `commandItems` 固定了这一点。

### 9.3 修法：label 定宽优先、描述让位（严格对齐上游）

上游行结构 `_upstream/packages/app/src/composer/editor/editor.tsx:799-816`：
`<div class="flex min-w-0 flex-1 items-center gap-2">` + `<bdi class="shrink-0">label</bdi>`
+ `<span class="min-w-0 truncate">description</span>` —— **label 不收缩（`shrink-0`）、
描述承担截断（`truncate`）**。逐字映射到 Compose：

| 位置 | 改动 |
|---|---|
| `MentionPopover.kt:145-151` | label 的 `Text` **不带任何 modifier**（= 无 weight，被优先按固有宽度测量）。任何长描述都吃不到它的宽度；只有比整行还长的 label 才截断 |
| `MentionPopover.kt:152-166` | 描述放进行内**唯一**的 `weight(1f)` 槽 `Box(contentAlignment = CenterEnd)`：只能拿 label 剩下的部分，且仍然贴行尾（短描述贴右、长描述 `…` 截断在行尾）——与修复前 73 条正常行的观感完全一致 |
| `SuggestionRowText.kt`（新增，纯逻辑） | `label(label, fallback)` **保证非空**（trim → 类别名 → `UNKNOWN_LABEL`）；`description(?)` 把空白收敛成 null；`clamp(text, max)` 给两半各自字符预算（96 / 512），切断处落在代理对中间会回退 1 个 UTF-16 单元（emoji 不会被劈成 `�`） |

**可见截断仍由布局负责**（`maxLines = 1` + `TextOverflow.Ellipsis`）；`clamp` 的预算只挡住
"服务器回超长描述"导致的整行文本测量，预算远大于任何屏幕一行能显示的字符数，用户看不出差别。

**被否决的替代写法**（工作区里一度出现、已被本次修复取代）：给描述也加 `weight(1f, fill = false)`。
它确实能让 label 不再变 0（两个子项都带 weight 时 `weightUnitSpace = R/2`，各拿一半），但：

1. `fill = false` 的未用分配**不会**归还给兄弟节点——`Row.kt:306-311` KDoc 原文：
   "Otherwise, the element is allowed to be smaller … as the unused allocated width will
   **not be redistributed to other siblings**"。所以 label 会被恒撑成半行、短描述停在行中间、
   行尾留一大段空白（平板 1240 dp 宽的行上非常明显）；
2. 描述被硬截在 R/2，本来放得下的中等长度描述在手机上会平白多出省略号。

### 9.4 自测方式

**纯逻辑（JVM）**：`app/src/test/java/com/xiufeigo/opencodeshell/Wave5ComposerRowLayoutTest.kt`（新增，8 例）
- `aRowLabelIsNeverBlank`：`""` / `"   "` / `"\n"` / `"\t "` / 全角空格 `"\u3000"` / `" \u00a0 "`
  全部回退到类别名或 `?`，断言 `isNotBlank()`——**"候选行的 label 一定非空"这条不变式**；
- `theDescriptionIsOptionalAndTrimmed`：null / 空串 / 空白 → null，正常描述 trim；
- `bothHalvesAreBounded` + `aCutNeverSplitsASurrogatePair`：截断策略边界
  （`clamp("abcd",3) == "abc…"`、`clamp(…,1) == "a…"`、`max ≤ 0 → ""`、
  `"a\uD83D\uDE00b"` 在 cut = 2/3/4 的结果分别是 `a…` / `a😀…` / 原串）；
- `theTwoTabletOffendersKeepTheirLabelAndABoundedDescription`：真机两条命令的真实超长描述 →
  label 仍是 `/customize-opencode`、`/jira-SCB-Collector`，描述被收进预算。

**布局守卫（源码级，JVM 无 Compose 运行时，沿用本仓既有习惯 `Wave5ComposerWiringTest`/`ComposerM1_5Test`）**：
切出 `SuggestionRow` 函数体（`private fun SuggestionRow(` → 之后第一个行首 `}`）后断言：
1. `Modifier.weight(` 在行内**恰好 1 处**，且该处是描述槽的 `Box`（**"行内只有一个子项参与余量分配"**
   是这条 bug 的可执行判据——修前正好相反）； 
2. label 在描述槽之前，且 label 的 `Text` 调用体内**没有任何 `Modifier.`**；
3. 两个 `Text` 都是 `maxLines = 1` + `TextOverflow.Ellipsis`（描述截断、不换行）。

### 9.5 设备复验命令序列（Lead 按此截图复验）

> 环境坑见 `research/wave5_status.md` §4：**模拟器串号重启后会互换**（5554 折叠屏 2076×2152 /
> 5556 平板 2560×1600，先核对 `wm size`）；**每次 `install -r` 后必须重做 `adb reverse`**；
> 中文 IME 下 `input` 可能产出全角字符（`HalfWidthText` 已兜底，但切英文布局更稳）。
> 本次我**没有**在设备上安装/运行（按约定设备归集成方），下面序列由以下证据推导：
> launcher 是 `.OcActivity`（`AndroidManifest.xml:18-30`）、点击坐标取自
> `v6-slash-tablet.png` 的物理像素（编辑器输入区 y≈1250）、斜杠键 `KEYCODE_SLASH = 76`。

```powershell
# 0) 先确认哪台是平板（串号会互换）
adb devices
foreach ($d in (adb devices | Select-String "emulator-\d+" | ForEach-Object { ($_ -split "\s+")[0] })) {
  "$d -> " + (adb -s $d shell wm size)      # 选输出 2560x1600 的那台
}
$S = "emulator-5556"                        # ← 用上面查到的平板串号

# 1) 装本次构建的 APK（产物来自本节的构建门），并重做端口反向
adb -s $S install -r app\build\outputs\apk\debug\app-debug.apk
adb -s $S reverse tcp:4096 tcp:4096

# 2) 起应用到会话页（OcActivity 即 launcher；composer 进入会话会自聚焦 = B7）
adb -s $S shell am start -n com.xiufeigo.opencodeshell/.OcActivity
Start-Sleep -Seconds 3

# 3) 点一下编辑器输入区拿焦点，然后敲 `/` 打开候选
adb -s $S shell input tap 640 1250
adb -s $S shell input keyevent 76           # KEYCODE_SLASH；不行就用: input text "/"

# 4) 截图：这一屏必须同时含 customize-opencode 与 jira-SCB-Collector 两行
adb -s $S exec-out screencap -p > research\wave5_shots\v6g-slash-tablet.png
```

**验收判读**：第 4 步截图里，第 5、6 行（紧跟 `/model`、`/agent`、`/init`、`/review` 之后）
左端必须出现 `/customize-opencode`、`/jira-SCB-Collector`，描述仍在行尾并被 `…` 截断；
修复前这两行的左端是空的（只有图标 + 描述），其余行不变。
若要更稳地只看这两条：`input text "/customize"` 与 `input text "/jira"` 各截一张，
每张里该行都必须带 `/名字`。

### 9.6 构建门（本节改动）

```
$env:JAVA_HOME='C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL          # 2026/09/21 10:35:15
```

- 结果汇总（聚合 `app/build/test-results/testDebugUnitTest/*.xml`，时间戳 10:35:14）：
  **suites = 93，tests = 906，failures = 0，errors = 0，skipped = 0**。
  基线 **86 suites / 827 tests → 93 / 906（只升）**；其中本节贡献
  **1 个套件 / 8 个用例**（`Wave5ComposerRowLayoutTest`：8 tests / 0 failures / 0 errors，源码守卫在内），
  其余增量来自并行 Wave 的新测试类。
- 产物：`app/build/outputs/apk/debug/app-debug.apk`（2026/09/21 10:34:17，32,885,166 bytes）。
- 期间遇到的并发干扰（同 §6 前例，**均为并行 Wave 的中间态，重试后自行消失**，非本节改动）：
  1. 10:16–10:20 `ui/settings/**` 重构中间态 → `:app:compileDebugKotlin` 报
     `Unresolved reference 'SettingsTab' / 'ServerListItem' / 'ProjectPageRow'` 等；
  2. 10:20 `AppNotifierTest.kt` 引用尚未落地的 `markNotificationsViewed` →
     `:app:compileDebugUnitTestKotlin` 失败；
  3. 10:23 一次 `Failed to delete ... testDebugUnitTest/binary`（Gradle 输出目录被另一进程占用）；
  4. 10:26 / 10:28 全量运行 900 tests / 2 failures：`Wave5SettingsTest`（快捷键冲突检测）与
     `SessionExportTest.normalizationCollapsesRunsAndTrimsEdges`
     （`expected:<keep_this-and-this.json> but was:<keep_this--and-this.json>`，导出文件名归一化）
     —— 两者都是并行 Wave 的在建用例，**不引用 composer/popover 任何符号**
     （`MentionPopover|SuggestionRow` 在 `app/src/test` 只命中 `ComposerM1_5Test` 的
     `composer.contains("MentionPopover(")` 与本节新测试），10:32 起自行修好，10:35 全绿。
- 最后确认：本次落盘的 `MentionPopover.kt`（10:14:17）与 `SuggestionRowText.kt`（10:18:03）
  就是 10:35 那次全绿构建所编译的版本（之后再无写入）；行内守卫复核
  `Modifier.weight(` 计数 = 1、`maxLines = 1` 计数 = 2、label 在描述槽之前 —— 全部成立。


