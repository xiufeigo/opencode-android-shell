# M1.5「建议修」清理报告（`_M1_review.md` §二 → 进入 M2 前的清账）

> 执行者：Compose 实现工程师。**只改 `ui/` 会话屏（+ 2 行主题根因）**，数据层与 View 壳 0 行改动。
> 依据：`research/_M1_review.md`（分级清单）、`research/02_ui_screens.md` §3.1/§3.8/§4.6/§4.7（权威规格）、
> `research/_i18n/en.json`（`ui.tool.*` 字面量）。基线：M1 一阶段 + M1.5（`research/_M1_5_status.md`）。
> 门禁：本地 `:app:compileDebugKotlin` / `:app:testDebugUnitTest` / `:app:assembleDebug` **全绿**（§2）。

---

## 1. 修了什么（逐条：问题 → 改法 → 落点）

### 1.1 Markwon 每帧全量重解析 → 80ms 合帧（`_M1_review` §二.1）

- **问题**：`MarkdownText` 的 `AndroidView.update` 每来一个 delta（每帧）就 `markwon.setMarkdown`，重跑
  `parse()+render()+Prism4j`；长回答/大代码块下是 O(n) per frame（整体二次方）+ 大量临时 Span。
- **改法**：新增纯 Kotlin 管道 **`ui/session/timeline/MarkdownStream.kt`**
  - `COL_CHUNK_MS = 80L`（对齐 View 版 `ChatView.RENDER_COALESCE_MS`）；
  - `Flow<T>.coalesceLatest(windowMs)` = `conflate()` + `transform { emit(it); delay(windowMs) }`：
    **首值立即发**（历史/已完成回答首帧即出，不延迟一个窗口）+ **每窗口最多一次** + **窗口内保留最新值**
    （`conflate` 的新值覆盖语义 ⇒ 流末尾一帧永不丢，否则答案会停在倒数第二个 delta 上）。
- **接线**：`MarkdownText` 内 `var rendered by remember(markwon)` + `rememberUpdatedState(markdown)` +
  `LaunchedEffect(markwon) { snapshotFlow { latest }.coalesceLatest().collect { rendered = it } }`；
  `AndroidView.update` 只读**合帧后的 `renderText`**（在 composition 作用域读取，保证重组一定会走到 update，
  不依赖 interop 内部的状态追踪）。原始 `setMarkdown(view, markdown)` 路径已从代码中消失（单测字符级守卫）。
- **保留**：`AppliedText` 去重（同一文本不重渲染）；`factory` 里 `applied.value = null`（视图被重建时强制重放文本）。

### 1.2 Markwon 配色随主题刷新（`_M1_review` §二.3）

- **问题**：`MarkwonHolder` 单例在首次构建时把 link / inline-code / code-block 底色一次性烘焙；叠加
  `OcActivity` 的 `configChanges=uiMode`（Activity 不重建）⇒ 切深/浅色后这些颜色永久错。
- **改法（两层，缺一层就会「半截换肤」）**：
  1. **Markwon 实例按主题缓存**：`MarkwonStream.kt:isNightUiMode(uiMode)`（掩码常量本地化，便于 JVM 断言）+
     `MarkdownText` 里 `remember(context, isDark) { MarkwonHolder.get(context, isDark) }`；
     `MarkwonHolder` 由「单例 + 双检锁」改为 **`HashMap<Boolean, Markwon>`（每主题一实例）**，配色仍取
     `values` / `values-night` 的资源色（`oc_accent`/`oc_code_inline_fg`/`oc_code_inline_bg`/`oc_layer_02`/
     `oc_text_base`），构建时按当前 Configuration 解析；
     `OcPrismTheme(context)` 同样按实例重建 ⇒ 代码高亮色同步刷新。
     `val applied = remember(markwon)`：换实例即作废旧 span，下一次 update 重新渲染（否则文本没变会被去重跳过）。
  2. **根因：`isDark` 响应式**（`ui/App.kt` + `OcActivity.kt`）：`OcApp(isDark = isSystemInDarkTheme())`，
     `OcActivity` 不再传 onCreate 时算出的布尔（并删除该计算）。否则 Markwon 跟随系统、而 `OcTheme.colors`
     冻结在启动时的主题 ⇒ 出现「浅色代码块配深色正文」的错配（更差）。已确认 `LocalConfiguration` 在
     `configChanges` 路径下仍会更新（`AndroidComposeView.onConfigurationChanged` +
     `ProvideAndroidComposeLocals` 的 `MutableState<Configuration>`），因此 `isSystemInDarkTheme()` 与
     `LocalConfiguration.current.uiMode` 都是活值。
- **附带**：`MarkwonHolder` KDoc 写死「仅主线程调用」约束（原 §二.4 建议；Markwon 未声明线程安全，
  当前全部调用点在 composition / `AndroidView.update`）。

### 1.3 Composer Enter 提交（§4.7，`_M1_review` §二.5）

- **现状核对**：M1.5 的 `Composer` 只有 mention 的 ↑/↓/Enter/Esc，**没有**提交路径（软硬键盘回车都只换行）⇒ 本条未解决，本轮补。
- **改法**（三条路互补，符合规格「Enter（非 Shift、非 composing）→ submit；Shift+Enter → 换行」）：
  - `BasicTextField(keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send))` —— 软键盘回车是编辑器的 send 动作
    （IME 只在提交 composing 文本后才触发动作 ⇒ 天然满足「非 isComposing」）；
  - `keyboardActions = KeyboardActions(onSend = { submit() })`；
  - `onPreviewKeyEvent`：硬键盘 `Key.Enter`/`Key.NumPadEnter` 且 `!isShiftPressed` 且 `repeatCount == 0` → `submit()` 并消费；
    `Shift+Enter` / 非 Enter / 长按重复 → 返回 `false`，交给文本框插入换行。
- **发送后清空**：`submit()` 内 `onSend(outgoing, attachments)` 之后 `value = TextFieldValue("")` + 关闭 mention 弹层
  （附件由 `SessionScreen` 清空）；空文本（`isBlank`）不发。
  ⚠️ 残留：`ChatViewModel.send()` 在 `sessionID==null`/无 client 时**静默 return**，草稿仍会被清空
  （`_M1_review` §二.7）——该条不在本轮清单，且修它必须改 VM 的返回语义（本轮禁止），见 §4。

### 1.4 停止语义（§4.6，`_M1_review` §二.6）

- **改法**：纯逻辑 `ComposerSubmit.stopping(running, text, attachmentCount) = running && !hasContent(...)`，
  `hasContent = text.isNotBlank() || attachments > 0`（「无文本/附件」口径，含只有附件的排队消息）。
- 按钮：`stopping` → 停止图标 + `onStop()`；否则发送图标 + `submit()`；`enabled = stopping || hasContent`，
  disabled 仍是 0.5 透明度。即：**运行中只要输入框有内容，按钮就是「发送（排队 follow-up）」，一点不再误触 abort**。

### 1.5 规格裁决落实（§3.1 / §3.8）

- **工具卡字号**：副标题 `13.sp → 14.sp`，与标题同为 14sp（标题 W500、副标题 regular + `textMuted` +
  `maxLines=1` + 省略号，原样保留）——裁决 A 按 §3.1 `[data-slot=basic-tool-tool-info] font-size:14px`。
- **工具标题映射**（裁决 B，§3.8 + `_i18n ui.tool.*` 字面量）：
  `glob→Glob`（不是 Find）、`grep→Grep`（不是 Search）、`webfetch→Webfetch`（不是 Fetch）；
  `bash/shell→Shell`、`edit→Edit`、`write→Write`、`read→Read`、`task→Task`、`todowrite→To-dos` 复核一致。
  改点：`ToolPresentation.title()/titleRes()` 字面量 + `strings.xml` 的 `oc_tool_find/search/fetch` 值。
  **资源 id 未改名**（`ChatView.titleForTool` 仍引用同 3 个 id ⇒ View 壳 0 行改动）；因为改的是**共享资源值**，
  View 壳的显示文案也一并变为 Glob/Grep/Webfetch（这正是裁决 B 想要的同步效果），`strings.xml` 内有注释说明。
- `oc_compaction` 已是英文 `Session compacted` ⇒ 按要求不作为本轮事项。

### 1.6 空态/错误态（轻量，`_M1_review` §二.18）

- `SessionScreen` 在 `state.messages.isEmpty()` 时叠加一行居中 faint 占位（新串 `oc_timeline_empty`，
  取值 = `_i18n session.new.title` 的 **"Build anything"**，不臆造文案），不再纯白屏。
- `configMissing` / `error` banner **保留**；`runStatus==ERROR` 且无消息时 = banner（错误文案）+ 占位行，
  不会「假死无反馈」；composer 仍可用。定时重试 `start()`、banner 重试按钮属「其余不强求」，未做。

### 1.7 纯逻辑 + 单测

| 新增/更新 | 内容 |
|---|---|
| `ui/session/composer/ComposerSubmit.kt` **新** | `hasContent` / `stopping`（§4.6 blank 判定）/ `enterSubmits(isShiftPressed, isRepeat)`（§4.7） |
| `ui/session/timeline/MarkdownStream.kt` **新** | `COL_CHUNK_MS`、`coalesceLatest`、`isNightUiMode` |
| `ComposerSubmitTest.kt` **新** | 8 条：blank 判定（空/纯空白/仅附件）、idle 不出停止、运行中 blank 出停止、运行中有文本/附件出「发送」、Enter 规则、Composer 三路接线与 `if (stopping) onStop() else submit()`、发送清空、空态守卫 |
| `MarkdownStreamTest.kt` **新** | 7 条：合帧「首值 + 最新值」、burst 末值不丢、窗口外透传、`COL_CHUNK_MS==80`、`isNightUiMode` 位判定、PartRenderer 无逐帧 `setMarkdown` + 主题键/`applied` 重置守卫 |
| `ToolPresentationTest.kt` **改** | 标题断言改 Glob/Grep/Webfetch（+1 条：`strings.xml` 资源字面量守卫，防止只改 Java 侧字面量而漏掉真正渲染的资源） |

---

## 2. 验证结果（本地实跑）

```
JAVA_HOME=C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1

.\gradlew.bat :app:compileDebugKotlin      → BUILD SUCCESSFUL（0 error）
.\gradlew.bat :app:testDebugUnitTest      → BUILD SUCCESSFUL
                                             184 tests / 30 suites / 0 failed / 0 error / 0 skipped
                                             （本轮 +16：ComposerSubmitTest 8、MarkdownStreamTest 7、
                                               ToolPresentationTest +1；M1 审校时的
                                               MentionTextTest 失败已不在，0 failed）
.\gradlew.bat :app:assembleDebug          → BUILD SUCCESSFUL（CI 等价门禁，.github/workflows/build.yml）
```

未改语义：`ChatStore.kt` / `OpenCodeClient.kt` / `EventStream.kt` / `ChatViewModel.kt` / `ChatView.kt` /
`MainActivity.kt` **0 行改动**；`Research/*` 除本文件外未动；未新增依赖。

---

## 3. 已由 M1.5/前序提交解决（本轮跳过，读代码确认）

| 原条目 | 证据 |
|---|---|
| **MF-1** 锚底跟随（末条顶部 vs 列表底部） | `MessageTimeline` 已改 `reverseLayout = true` + `items(messages.asReversed())` + `scrollToItem(0)/animateScrollToItem(0)`，SideEffect 判定 `first.index <= 1 && offset <= threshold` ⇒ 语义正确，「末条高于一屏」也贴底 |
| **MF-2** `appendKey` 覆盖不足 | 已换 `appendFingerprint(messages)`（末条 id/parts.size/text/toolName/toolStatus/toolOutput 长度）+ `TimelineFingerprintTest` 7 条覆盖 tool part/status/output 三场景 |
| **MF-3** 顶栏恒为 New session | `SessionScreen` 的 `LaunchedEffect(sessionId)` 内已调 `chatViewModel.listSessions { }` 回填 `currentTitle`（Review 建议的最小改法原样落地） |
| §二.13 `oc_compaction` 中文 | 已是 `Session compacted`（本轮按要求不处理） |

---

## 4. 未做 + 原因（本轮清单之外或受限）

| 条目 | 原因 |
|---|---|
| §二.2 `CorePlugin` 装 `LinkMovementMethod`（与注释相反） | 不在本轮 6 项清单；且 `hasExplicitMovementMethod(true)` 会改变「链接可点 / 纵向拖动手感」，属需真机验证的产品选择，留给 M2 真机回归 |
| §二.7 发送失败吞草稿 | 需改 `ChatViewModel.send()` 返回 `Boolean`/`suspend trySend()` ⇒ 违反「不改 VM 语义」约束；本轮只在 `submit()` 内保持「调用后清空」，报告留档 |
| §二.8 乐观 RUNNING 与立即 abort 竞态 | 同上（VM 内加 Job/Mutex 属数据层改动） |
| §二.9 裸 `clickable` 无 ripple / 无 `role` | 不在本轮清单；牵连 M1.5 新控件与 `IconButtonV2` 等价物选型，M2 统一做可访问性 |
| §二.10 发送键配色（accent vs `bg-contrast`+高光） | 属待裁决项（`_M1_review` §五.E），本轮只改语义不改皮肤 |
| §二.12 reasoning「Thought 折叠卡」vs §3.5 直出 | 属待裁决项（§五.C），且与 M6 thinking row 相关，不在本轮清单 |
| §二.14–17 v2 排版细节 / part 间距 / user 气泡细节 / running 可展开门控 | 均不在本轮 6 项清单；其中 §二.17（pending/running 不可展开）建议随 M6 的 toolOpen store 一起做（要一并处理 `allowOpenWhilePending`） |
| §二.19 `collectAsState()` / §二.20 顶栏返回收起 IME | 已声明 M2 / M6，不在本轮 |
| 空态定时重试 `start()`、banner「重试」按钮 | §6 明确「其余不强求」，本轮只做轻量占位 |

---

## 5. 真机 checklist（本会话无设备，未手测）

1. **流式节流**：长回答（>1 屏）流式时滑动应始终顺滑；文本最多落后 80ms；回答结束后末尾一段必须完整（不能停在倒数第二个 delta）。
2. **主题切换**：系统深/浅色来回切 2 次（`adb shell cmd uimode night yes|no`）——正文、**行内 code 前景/底色、代码块底色、链接色**、
   工具卡/时间线底色应整体一致切换（不应出现「浅色代码块 + 深色正文」）。
3. **Enter**：软键盘回车 = 发送（不是换行）；`Shift+Enter`（硬键盘/外接） = 换行；长按回车只发一次；`@` 弹层打开时回车仍是「选中候选」。
4. **停止语义**：运行中空输入 → 停止图标，点击 = abort；输入任意文字 → 按钮变发送，点击 = 发 follow-up（**不应** abort）。
5. **工具卡**：标题/副标题同字号（14sp），副标题单行省略；`glob/grep/webfetch` 显示 Glob/Grep/Webfetch。
6. **空态**：新会话空时间线显示居中 faint "Build anything"；`configMissing`/错误 banner 仍在 composer 上方。
