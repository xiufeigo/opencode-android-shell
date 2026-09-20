# M1「Compose 会话屏」审校报告（read-only 审查）

> 审校员：资深审校（只读 + 修复建议）。**未修改任何实现文件**，本文件为唯一产出。
> 审校对象：`ui/session/SessionScreen.kt`、`timeline/{MessageTimeline,PartRenderer,ToolPresentation}.kt`、
> `composer/Composer.kt`、`ui/theme/OcTheme.kt`、`ui/App.kt`、`OcActivity.kt`；数据层 `ChatStore.kt`/`ChatViewModel.kt` 只读参照。
> 规格参照：`PLAN-android-native-refactor.md` §1.3/§1.6/§3.2/§4.2、`research/02_ui_screens.md` §2/§3/§4、`research/07_motion_copy_p2.md`。
>
> **审校方法（含外部取证，非纸面推断）**
> 1. 逐行读上述文件 + `ChatView.kt`（View 版同功能参照）与 `res/layout|drawable|values`。
> 2. 反编译取证：`javap` 读 `markwon-core-4.6.2` / `foundation-layout-1.7.6` 字节码（证据见 §A）。
> 3. 读 androidx 源码：`LazyListState.scrollToItem/animateScrollToItem` + `LazyLayoutScrollScope.animateScrollToItem`（证据见 §A）。
> 4. 跑单测：`.\gradlew.bat :app:testDebugUnitTest`（结果见 §D）。
>
> **结论（先行）**：**暂不建议进入 M2**，先修【必须修】3 条（都在 2 个函数里，约 1 个提交 + 1 组单测），
> 【建议修】中 Markwon 相关 3 条与 Composer 交互 3 条建议同批处理。修完 M1 即可签字。

---

## 一、【必须修】（阻断 1:1 验收 / 核心功能可见失效）

### MF-1 锚底跟随的滚动目标是「末条顶部」而不是「列表底部」——长消息会跳到该条开头并永久关闭跟随
- **文件:函数**：`ui/session/timeline/MessageTimeline.kt` → `MessageTimeline`（`LaunchedEffect(appendKey)` 第 73–83 行）。
- **根因**：`listState.animateScrollToItem(target)` / `scrollToItem(target)`（`target = messages.lastIndex`）的目标语义是
  **「把第 target 项的顶部对齐视口顶部」**，不是「滚到底」：
  - 官方 KDoc：*“Instantly brings the item at [index] to the top of the viewport, offset by [scrollOffset] pixels”*（§A-3）。
  - `LazyLayoutScrollScope.animateScrollToItem`：目标项已可见时取 `target = calculateDistanceTo(index) + scrollOffset`（即该条 `offset`），
    再 `scrollBy(delta)`；**只有正向滚动被列表末端吃掉（`delta != consumed`）时才 cancelAnimation**（§A-3）。
- **影响（可复现推演，建议真机复核）**：设视口 1000px、末条 3000px、底部 contentPadding 100px，停在最底时末条 `offset = -2100`。
  下一次流式 delta → `followNextAppend=true` → `animateScrollToItem(lastIndex)` 目标 `-2100` → **列表向上跳 2100px 到该条开头**；
  此后 `atBottom=false` → `SideEffect` 把 `followNextAppend` 置 false → **跟随永久失效**（直到用户手动再滚到底）。
  即：只要「末条高于一屏」（长回答/长工具卡极常见），流式跟随的观感是「反复弹回该条开头」，与 web `anchorTo:"end"` 完全相反。
  冷启动 `scrollToItem(target)`（JUMP_SCROLL_ITEMS 分支）同病：只有「末条 + afterContentPadding ≥ 视口高」时 measure 才把位置钳到末端，
  否则打开会话看到的是最后一条的**开头**。末条短于一屏时行为恰好正确（被末端钳住），这也是它没有在手测里立刻暴露的原因。
- **最小改法**（二选一，推荐 A）：
  - **A. 改成按距离滚动（不改结构）**：用追加**之后**的新 `layoutInfo` 求剩余距离再滚，语义即「到底」：
    ```kotlin
    LaunchedEffect(appendKey) {
        if (!followNextAppend) return@LaunchedEffect
        withFrameNanos { }                      // 让新内容先 measure（否则读到旧 totalSize）
        val i = listState.layoutInfo
        val last = i.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
        val remaining = ((last.offset + last.size) - i.viewportEndOffset).toFloat()
        if (remaining > 0f) animateScrollBy(remaining) else scrollBy(remaining)   // listState.scroll { }
    }
    ```
    （冷启动分支同理：距离大于一屏用 `scrollBy` 立即，否则 `animateScrollBy`。）
  - **B. 结构法**：`LazyColumn(reverseLayout = true)` + 反转列表（index 0 = 最新），追加即在「底部」，跟随时只判 `firstVisibleItemScrollOffset == 0`。
  - 无论 A/B，请把「是否跟随」「剩余距离」抽成纯函数以便单测（见 §C）。
- **备注**：`SideEffect` 读「追加前」`layoutInfo` 的设计**本身是对的**（不进 composition → 不会每像素重组；apply 阶段读到的确是上一帧布局），
  不需要改成 `derivedStateOf`；问题只在「拿 index 当底部」这一步。

### MF-2 `appendKey` 覆盖不足：无 text 的 part 追加/属性变化不会触发跟随
- **文件:函数**：`MessageTimeline.kt` → `appendKey = messages.size to messages.last().parts.sumOf { it.text.length }`。
- **根因**：key 只统计 `parts[].text.length`；`tool`/`file`/`compaction` 等 part 的 `text` 恒为 `""`，
  且 `ChatStore` 把工具载荷放在 `toolInput/toolOutput/toolError/toolStatus`（`ChatStore.UiPart`）。
- **漏触发场景（逐条对照审校要求）**：
  | 场景 | key 是否变 | 是否该跟随 | 结论 |
  |---|---|---|---|
  | 末条 assistant 追加一个 tool part（工具调用开始） | 否（size 不变、text 不变） | 是（新卡在折叠线以下，用户看不到） | **漏** |
  | 工具 `running→completed`（副标题由无到有）/ toolOutput 流式增长 | 否 | 卡片高度实际增长 | **漏** |
  | `message.part.updated` 整条替换（同 text、新 status） | 否 | 同上 | **漏** |
  | 中间插入新消息（按 timeCreated 排序） | 是 | 视 follow 值 | 可接受（`LazyList` 按首位 key 保位，见 §A-3） |
  | 删除消息 / 历史前插 | 是（size 变） | 取决于 follow 值 | 可接受（前插不会跳，框架已保位） |
- **影响**：一轮里「最后一个动作是工具调用」时（很常见），工具卡永远停在屏外，用户以为没反应。
- **最小改法**：把 key 换成末条消息的**结构化指纹**，例如
  `messages.lastOrNull()?.let { Triple(it.parts.size, it.parts.sumOf { p -> p.text.length + (p.toolOutput?.length ?: 0) }, it.parts.map { p -> p.toolStatus ?: "" }) }`
  （或直接对末条 `UiMessage` 做 `hashCode()`——data class 已含全部字段；注意 `UiMessage` 等值变化即视为「有更新」）。
  仍建议抽成纯函数 `followKey(messages)` 并单测这四个场景。

### MF-3 顶栏标题恒为「New session」（标题来源整条链路是断的）
- **文件:函数**：`SessionScreen.kt` → `SessionTopBar(title = currentTitle ?: sessionTitle ?: R.string.oc_new_session)`；
  `ui/App.kt` → `SessionScreen(...)` **从不传 `sessionTitle`**；`ChatViewModel.currentTitle` 只在 `listSessions()` 里被填，
  而 `listSessions()` 全仓只有 `ChatView.kt:135`（View 壳）调用。
- **根因**：`observeSession(id, title = null)` 把 `_currentTitle` 置 null；Compose 路径没有任何「取会话标题」的调用 →
  `currentTitle == null && sessionTitle == null` → 永远显示 `oc_new_session`。
- **影响**：任何已有会话的顶栏标题都是错的（“New session”），`sessionTitle` 成为死参数。
- **最小改法**：`observeSession` 之后补一次标题来源：在 `start()` 成功路径里用 `listSessions()`（或 `GET /session`）匹配 `sessionID` 回填 `_currentTitle`
  （VM 已有能力，仅需在 Compose 侧调用）；同时把路由/Nav 里已有的标题（若有）传进 `SessionScreen(sessionTitle = …)`。

---

## 二、【建议修】（不阻断，但影响确定性/保真度/体验）

### Markwon / 富文本
1. **每帧全量重解析（性能）** — `PartRenderer.kt:MarkdownText.update`：`applied.value != markdown` 即 `markwon.setMarkdown(view, text)`。
   Compose 侧每来一个 delta 就重跑 Markwon `parse()+render()+Prism4j`（O(n)，长回答/大代码块下是二次方级开销 + 大量临时 Span）。
   View 版有 `RENDER_COALESCE_MS = 80` 合并（`ChatView.kt:1227`）。**改法**：用 `snapshotFlow { markdown }.conflate()` + ~80–120ms 采样后统一 `setMarkdown`，
   或对未闭合 code fence 走「纯追加」快路径；同时保留现有 `applied` 去重。
2. **`CorePlugin` 会偷偷装 `LinkMovementMethod`（与代码注释相反）** — 字节码取证：`markwon-core 4.6.2` `CorePlugin.afterSetText` 里
   `if (!hasExplicitMovementMethod && textView.getMovementMethod() == null) textView.setMovementMethod(LinkMovementMethod.getInstance())`（§A-1）。
   即：factory 里 `movementMethod = null` 的意图被 Markwon 覆盖，每个 markdown TextView 最终都带 LinkMovementMethod。
   **风险**：与「可选中的 TextView 会吞纵向拖动，因此关掉选择」的初衷相冲突；`AndroidView` interop 下可能影响以文字为起点的纵向拖动/长按。
   **最小改法**：`CorePlugin.create().hasExplicitMovementMethod(true)`（或显式 `setMovementMethod`），并按产品决定是否要链接可点（要可点就显式装 + 真机验证拖动手感）。
3. **Markwon 单例的配色在首次构建时冻结** — `PartRenderer.kt:MarkwonHolder.build` 用 `context.getColor(R.color.oc_*)` + `OcPrismTheme(context)` 一次性烘焙。
   叠加 `OcActivity` 的 `android:configChanges="…|uiMode"`：运行时切深/浅色 **Activity 不重建**，`OcApp(isDark = 计算于 onCreate)` 与 Markwon 主题都不会更新
   （正文颜色因 `update` 里 `setTextColor(argb)` 会跟随，但 link/inline-code/代码块底色不会）。
   **改法**：`isDark` 改为 `isSystemInDarkTheme()`/`LocalConfiguration` 响应式读取；给 `MarkwonHolder` 加 `reset()` 并在配色变化时重建（或把颜色改成从当前 resources 每次 `configureTheme` 读）。
4. **单例线程安全**：`get()` 的双检锁 + `@Volatile` 正确；当前所有调用点都在主线程（composition / `AndroidView.update`），
   **不构成并发风险**。但 Markwon 未声明线程安全 → 若 M1.5 引入后台预解析/预测量，必须加锁或改「每线程实例」。建议在 `MarkwonHolder` 注释里写死「仅主线程」约束。

### Composer
5. **缺 Enter 提交（交互规格缺失）** — `Composer.kt:BasicTextField` 无 `keyboardOptions/KeyboardActions/onKeyEvent`：
   软键盘回车只换行、硬键盘 Enter 也只换行。规格 `research/02` §4.7 明确「Enter（非 Shift、非 isComposing）→ submit；Shift+Enter → 换行」，
   View 版 XML 也有 `imeOptions="actionSend|flagNoEnterAction"`。**改法**：`KeyboardOptions(imeAction = ImeAction.Send)` + `KeyboardActions(onSend = …)`
   + `Modifier.onPreviewKeyEvent { it.key == Key.Enter && !it.isShiftPressed → 发送 }`。
6. **`stopping = working()` 与 §4.6 不一致** — 运行中且输入框非空时按钮仍显示「停止」，一点即 `abort()`：
   规格是 `stopping = working() && blank()`，此时应为「发送（排队 follow-up）」。M1 任务书**特意**选了简化版（`M1-progress.md`），
   但这是会误触中止的交互差异，建议按 §4.6 改回 `running && text.isBlank()`。
7. **发送失败会吞掉草稿** — `Composer.kt` 先 `onSend(outgoing); text = ""`；而 `ChatViewModel.send()` 在 `sessionID==null` 或
   `requireClient()==null`（未连/无配置）时**静默 return** → 用户消息丢失且输入框已空。
   **改法**：`send()` 返回 `Boolean`（或 `suspend fun trySend(): Boolean`），成功后再清空。
8. **乐观 RUNNING 与立即 abort 的竞态（低）** — `send()` 先置 `RunStatus.RUNNING` 再后台 `promptAsync`；若用户马上点停止，
   `abort()` 与 prompt POST 并发提交，服务端可能先收到 abort（或先建完再跑，无法中止）。
   **改法**：VM 内用一个 `Job`/`Mutex` 串行化（abort 前 `promptJob?.join()`），或等 POST 返回再点亮「停止」。
9. **可点无反馈 / 可点空实现（可访问性）** — 「+」与发送键是裸 `Modifier.clickable`：无 `MaterialTheme` → 无 ripple（`LocalIndication` 为默认空指示），
   且未声明 `role = Role.Button`；「+」点了完全无反应（占位）。**改法**：给这两个 Box 加 `role`/`indication`（或 M1.5 落 SAF 时换成 `IconButtonV2` 等价物）。
10. **发送键配色与 §4.6 不一致** — 规格：`bg-contrast`(#242424，深浅色一致) + 双层高光渐变 + 图标 `icon-muted`；
    实现：`background(c.accent)` 蓝 + `tint(c.inverse)` 白。尺寸/圆角/disabled 0.5 透明度**正确**。请裁决是否记入偏差清单或改回 contrast。

### 保真度（与权威文档逐项核对结果）
11. **工具卡副标题 13sp vs 规格 14px**（`research/02` §3.1「标题/副标题同为 14px」、`PLAN` §4.2）：实现 14sp 标题 + 13sp 副标题（沿用 View 版 XML）。
    对应地 `ToolPresentationTest` 把 View 版映射也锁死了：`glob→"Find"`、`grep→"Search"`、`webfetch→"Fetch"`，
    而 `research/02` §3.8 / `_i18n` 是 **Glob / Grep / Webfetch**。→ 需要一次裁决，然后同步 `ToolPresentation` + `strings.xml` + 测试 + `ChatView`。
12. **reasoning 用「Thought」折叠卡** — `research/02` §3.5 的明确结论是「无折叠容器、无 Thought 文案、13px muted markdown 直出」，
    且 `_i18n/en|zh` 里**根本没有** `ui.tool.thought` 键（zh 侧同名键为空）。实现沿用了 View 版折叠卡。
    → 属需要产品裁决的规格冲突（`_M1_session_status.md` 已声明）；若要 1:1 应改为直出 + `margin-top:16px`，标题条改由 `session-turn-thinking`（M6）承担。
13. **`oc_compaction` 默认（英文）资源里放了中文** — `values/strings.xml: oc_compaction = 会话已压缩`；en 应为 “Session compacted”，
    中文归入 `values-zh`（本仓当前无 `values-zh`，属 M1 已知的 i18n 缺口，但「默认语言里出现中文」是明确不一致）。
14. **缺 v2 排版细节** — 全屏未设 v2 的 `letter-spacing: -0.04px`（如顶栏 13sp/530、工具标题 14sp/500）与 `line-height`（工具标题/副标题、chip 未设 `lineHeight`；
    user 气泡 20sp ✔、composer 20sp ✔）；字体未打包 Inter（440/530 会被 Roboto 就近取整，已声明 M6）。
15. **user 消息细节** — 气泡内边距 12/10（View 版 XML 12/8）；宽度取「时间线内容区 × 82%」（View 版是「屏幕宽 × 82%」，故本实现略窄）；
    附件 chip 排在气泡**下方**（View 版在上方，`item_message_user.xml` 顺序为 attachments→bubble）。均为低危 1:1 细节。
16. **part 间距** — 统一 12dp；`research/02` §3.5b 另有 `text-part { margin-top:24px }` / reasoning `16px`（与容器 `gap:12px` 叠加）。
    需要裁决是「12 一律」还是「12 + 各自 margin」；当前与容器 gap 一致，可接受但应记档。
17. **工具卡 running/pending 可展开 + chevron 恒显** — 规格 §3.2：pending/running **不可展开**（`handleOpenChange` 直接 return），
    箭头仅在其中 `allowOpenWhilePending`（shell 设了）时才出现；subtitle 无内容时隐藏（本实现 ✔）。建议按规格加 `canExpand` 门控。
18. **空态/错误态兜底薄** — 无消息时是纯白屏（无占位）；`configMissing`/`error` 仅一行 12sp 文本、无重试入口；
    **`start()` 失败后不会重试**（WebView 配置同步通常晚到几秒 → 用户必须先退出再重进才能连上）。
    建议：空态加一行 faint 文案（如 `session.new.title` 语义占位），错误行加「重试」按钮或 `configMissing` 时定时重试 `start()`。
    （`ChatViewModel` 里那条英文长句 "Not connected — open the Terminal tab once to load the server config." 也属硬编码未本地化文案。）

### 其他
19. `collectAsState()`（非 lifecycle-aware）：屏幕不可见时仍收集；本轮无实际危害，M2 换 `collectAsStateWithLifecycle()`。
20. `SessionScreen` 无 `BackHandler`：系统返回由 NavHost 处理 ✔（`onBack = popBackStack`），但顶栏返回键未做「IME 先收起」——低危。

---

## 三、【可接受】（已声明偏差 / 平台合理改编 / 复刻正确项）

1. **不用 `derivedStateOf` 读 `layoutInfo`**：`SideEffect` 只读不订阅，`firstVisibleItemIndex` 在协程体内读 → **确实避免了每像素重组**，设计正确（问题仅见 MF-1）。
2. **不画流式光标**：符合 `research/07` P2 定案（`blinking-cursor` 为死代码）。View 版的 `makeCursor()` 没有移植反而是**保真改进**。
3. **shimmer 用 1s alpha 脉冲近似 `TextShimmer`**（web：1.2s / 每字符 -45ms 相位 / 360% 扫光）：已声明 M6 精修，可接受。
4. **`todowrite` 不渲染**（= web `HIDDEN_TOOLS`，todo dock 后置）；`file` part 仍渲染 chip（与 View 版一致，`research` 内部对 `PART_MAPPING` 有张力，低危）。
5. **工具卡视觉**：`layer01` + 圆角 6（`shapes.md`）+ 0.5dp `borderBase` + 16dp 图标 + 14sp/500 标题 + muted 单行省略副标题 + 右侧 16dp chevron —— **与任务书/`research/02` §3.1 一致**；无左色条（已按 §3.1 纠正 View 版错误）。
6. **composer 几何**：圆角 10（`shapes.xl`）+ `layer01` + 0.5dp border + 编辑区 min60/max180/`13sp`/`pt16 pb8`/`leading-5` + 工具行 44dp + 发送 28dp 圆角 6 —— 与 `§4.2`/任务书一致（`layer01+0.5dp` 与 `§4.1` 的 `bg-base+elevation-raised` 是规格内部分歧，按任务书实现，建议记档）。
7. **时间线尺寸**：turn 24dp / part 12dp / 底部让位 = 实测 composer 高度 + 12dp（优于写死 64px）/ 80dp 阈值（= web `scrollEndThreshold: 80`，dp 折算正确，语义「末条底距视口底 ≤80dp」正确）/ `items(key = message.id)` 正确（part 不是 lazy item，不存在 index-key 漏用问题）。
8. **生命周期**：`LaunchedEffect(sessionId)`+`DisposableEffect(sessionId)` 由 sessionId 键控，**不会**因重组重复 start/stop；跨 session 切换时 dispose(apply 阶段) 先于新 LaunchedEffect 协程体 → `stop()` 后 `observeSession()+start()`，顺序安全；
   仅「进入 + ESTABLISHED」各触发一次 snapshot（幂等 upsert，无害冗余）。会话离开即断 SSE ✔。
9. **IME/inset**：`WindowInsets.safeDrawing` **包含 ime**（字节码取证 §A-2：`systemBars.union(ime).union(displayCutout)`），
   配 `enableEdgeToEdge()` + manifest `windowSoftInputMode="adjustResize"` → composer 跟随键盘、顶栏不动 ✔（正确）。
   唯一副作用：键盘弹起时视口变矮 → MF-1 的跟随判定会翻 false，属 MF-1 修好后需一并加固的点（建议「用户自己发送时强制跟随一次」）。
10. **未做项**：context ring、⋮ 菜单、粘性头部完全体、thinking row/paced reveal、默认展开设置、grouped context、diff 汇总行、
    todo/permission/question dock、@-mention 与 SAF 附件、Inter 字体、androidTest —— 均已在 `_M1_session_status.md`/`M1-progress.md` 声明后置。
11. **展开体渲染成一条 `AnnotatedString` + `heightIn(max=220dp)` + 内滚**：与 View 版一致，展开卡内滚动正常（嵌套滚动在 Compose 下可接受）。

---

## 四、测试评估（审校要求 5）

**值得补（低成本/高回归价值）**
1. **锚底判定纯函数化 + JVM 单测**：把 MF-1 的决策抽出来（如 `TimelineAnchor.shouldFollow(lastEndPx, viewportEndPx, thresholdPx)`、`remainingPx(...)`），
   断言：空列表 / 未到底 / 阈值内 79dp / 阈值外 81dp / 末条高于视口（剩余距离为正、且算出的滚动量不会把用户往上带）。
   这是本屏风险最高的一处，且不需要设备。**建议随 MF-1 一起提**。
2. **`followKey(messages)` 覆盖性单测**：新增 tool part、toolStatus 变化、toolOutput 增长、末条 text 增长、删除/前插 —— 断言 key 的「变/不变」符合预期（MF-2 回归网）。
3. **Markwon 相关**：JVM 测不了（`MarkwonHolder` 依赖 Android）。两条可测的不变量价值最高：
   (a) `CorePlugin` 不再装 MovementMethod（若采纳建议 2，可用 instrumented 测断言 `movementMethod == null`）；
   (b) 同一 composition slot 换 part 后 `setMarkdown` 不会串味（`applied` 去重逻辑正确）—— instrumented 才能验。
   → 结论：**不必为 Markwon 单例专门搭 androidTest**（当前只有 1 条 instrumented 且无 CI 设备）；改为「真机 checklist」两条
   （长文本纵向拖动手感 / 深色浅色切换后 link、code 色）更划算。
4. Compose UI 测试（`createComposeRule` + `ui-test-junit4`）：现阶段不划算（需新依赖 + 设备/CI）；M1.5 若有设备，建议只加 1 条
   「追加末条后 `onNodeWithText(newText).assertIsDisplayed()`」盯住锚底即可。

**当前测试状态（本次实跑）**：`150 tests / 1 failed`。
失败为 `MentionTextTest.directoryPathsClassifyAsReference`（`MentionText.classify("src")` 期望 REFERENCE，实现要求含 `/`，裸目录名落到 AGENT）——
该文件属 **M1.5 @-mention 并行工作**（`ui/session/composer/MentionText.kt|MentionColors.kt|MentionPopover.kt` 均为新出现的文件），
**与本次审校的 M1 一阶段文件无关**；`ToolPresentationTest` 19 条全绿。请负责 mention 的成员自行修正断言或分类规则。
（注：M1 文档记录的 `116 tests / 0 failed` 已被这批并行改动覆盖，`M1-progress.md` 的数字需更新。）

---

## 五、需要 Lead 裁决的规格冲突（不是代码 bug，但会卡 1:1 验收）

| # | 冲突 | 选项 |
|---|---|---|
| A | 工具副标题 13sp（任务书/View 版） vs 14px（`research/02` §3.1、`PLAN` §4.2） | 建议统一到 14px（§3.1 是 DOM/CSS 一手证据） |
| B | 工具标题映射 Find/Search/Fetch（View 版 + 现有测试） vs Glob/Grep/Webfetch（`§3.8` + `_i18n`） | 建议统一到 §3.8，并同步 `ChatView` 与测试 |
| C | reasoning「Thought 折叠卡」（View 版） vs 「无折叠、无 Thought、直出 13px muted」（`§3.5` + 无 i18n 键） | 建议按 §3.5 直出，标题条留 M6 thinking row |
| D | composer 面 `layer01+0.5dp`（任务书） vs `bg-base+elevation-raised`（`§4.1/§4.2`） | 记入公开偏差清单或按 §4 改 |
| E | composer 发送键 `accent`（实现） vs `bg-contrast`+高光（`§4.6`） | 同上 |
| F | assistant part 间距「一律 12dp」 vs 「12dp gap + text 24px / reasoning 16px margin」 | 记档，M1.5 再定 |

---

## 六、M2 门禁结论

- **不建议现在进入 M2。** 先修 MF-1 / MF-2（同一函数重写，建议改为 `scrollBy(remaining)` 方案 + 纯函数单测）与 MF-3（标题来源，约 20 行）。
- MF-1/MF-2 修好后，「流式跟随 + 不把用户拽回底部 + 冷启动显示最后一条尾部」三件事才有真机验收意义；
  建议同时补 3 条真机 checklist：长回答（>1 屏）流式、末条为工具卡且运行结束时停在底部、上滚后再来 delta 不被拽回。
- 建议修 1/2/3（Markwon 节流、MovementMethod、主题冻结）与 5/6/7（Enter 提交、stopping 规则、草稿丢失）建议与 MF 同批或紧随其后（均为小改动）。
- 保真度类（11–17）可在 §五 裁决后一次清算；不影响 M2 功能推进，但会影响「1:1」验收签字。

---

## 附录 A：取证记录（可复现）

1. **Markwon 装 MovementMethod**（`markwon-core-4.6.2`，`javap -c io.noties.markwon.core.CorePlugin`）：
   ```
   public void afterSetText(android.widget.TextView);
     0: aload_0 / 1: getfield hasExplicitMovementMethod
     4: ifne 21
     7: aload_1 / 8: invokevirtual TextView.getMovementMethod()
    11: ifnonnull 21
    14: aload_1 / 15: invokestatic LinkMovementMethod.getInstance()
    18: invokevirtual TextView.setMovementMethod(...)
   ```
   `MarkwonImpl.setParsedMarkdown` 本身不设 movement method（只 `TextSetter.setText`），故来源就是 CorePlugin。
2. **`safeDrawing` 含 ime**（`foundation-layout-1.7.6`，`javap -c WindowInsetsHolder` 构造器偏移 180–208）：
   `systemBars.union(ime).union(displayCutout)` → `putfield safeDrawing`。
3. **`scrollToItem`/`animateScrollToItem` 语义**：androidx 源码
   （[LazyListState.kt](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListState.kt) /
   [LazyLayoutScrollScope.kt](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/layout/LazyLayoutScrollScope.kt)）：
   KDoc "brings the item at [index] to the top of the viewport"；`animateScrollToItem` 目标 = `calculateDistanceTo(index) + scrollOffset`，
   仅在 `delta != consumed`（正向被末端吃掉）时 `cancelAnimation()`；另有 `NumberOfItemsToTeleport = 100` 的远距离瞬跳。
   另：`updateScrollPositionIfTheFirstItemWasMoved` 说明**历史前插时框架按首位 item key 保位**（前插不跳，MF-2 表中据此判定）。
4. **单测实跑**：`JAVA_HOME=<jdk21> .\gradlew.bat :app:testDebugUnitTest --console=plain` → `150 tests completed, 1 failed`（见 §四）。
5. 对照过的其它文件：`ChatView.kt`（L734/L768-888/L950-1020/L1056-1166/L1217-1247）、`item_message_user.xml`、`view_tool_card.xml`、
   `view_chat.xml`（L135-266）、`bg_user_bubble.xml`/`bg_send_button.xml`/`bg_composer_panel.xml`/`bg_tool_card.xml`、`strings.xml`、`AndroidManifest.xml`、`_i18n/{en,zh}.json`。
