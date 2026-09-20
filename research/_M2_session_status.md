# M2「会话增强」实施状态（吸顶标题 / context ring / 工具卡展开 / 分组工具 / 会话抽屉 / 错误卡）

> 状态：**已交付，本地编译 + 单测 + 打包全绿**。
> 基线：M1 / M1.5 + `_M1_review.md`（MF-1/2/3 已修）+ `_M1_5_review_cleanup.md`（§二 6 项已清）。
> 规格依据：`PLAN` §4.2、`research/02` §2.2 / §3.1 / §3.2 / §3.2b / §3.3 / §3.7 / §7、`research/03` §4 / §3.4。

---

## 1. 验证结果（本地实跑）

```
JAVA_HOME=C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1

.\gradlew.bat :app:compileDebugKotlin   → BUILD SUCCESSFUL（0 error；仅 ChatView.kt 既有 warning）
.\gradlew.bat :app:testDebugUnitTest    → BUILD SUCCESSFUL
                                          218 tests / 35 suites / 0 failed / 0 error / 0 skipped
                                          （M1.5 基线 184/30 → 本轮 +34 tests / +5 suites）
.\gradlew.bat :app:assembleDebug        → BUILD SUCCESSFUL（CI 等价门禁）
```

新增测试套件：`ToolCardPolicyTest`(12)、`TimelineGroupingTest`(8)、`ContextUsageTest`(6)、
`SessionTimeLabelTest`(4)、`OpenCodeClientRenameTest`(4) —— 共 34 条，占 `218 = 184 + 34`。
未新增依赖；既有套件（含 `ToolPresentationTest` 20、`TimelineFingerprintTest` 7、`ChatStoreTest` 11）全部保持原状通过。

---

## 2. 文件清单

### 新增（纯 Kotlin，JVM 可测）
| 文件 | 内容 |
|---|---|
| `ui/session/timeline/ToolCardPolicy.kt` | `partDefaultOpen` 全量转写 + chevron 门控（`hasChildren && !pending \|\| allowOpenWhilePending(shell)`）+ `deletionOnly` + `resolveOpen`（用户显式开合 > 默认） |
| `ui/session/timeline/TimelineGrouping.kt` | `groupParts()`：仅**连续** `read\|glob\|grep\|list` 合并；`todowrite` 隐藏；`busy` 只给末组；`contextToolSummary()` → read/search(glob+grep)/list |
| `ui/session/header/ContextUsage.kt` | `usage = limit ? clamp(round(total/limit*100),0,100) : null`；`lastTokenTotal` = 最后一条 `tokens>0` 的 assistant；**无 >80% 变红分支** |
| `ui/session/drawer/SessionTimeLabel.kt` | 相对时间分桶（justNow/minutes/hours/days），含时钟偏移保护 |
| `ui/theme/OcMotion.kt` | `expand = spring(dampingRatio=1f, stiffness=StiffnessMediumLow)`（`visualDuration:.35,bounce:0` 映射）、`ring=350ms+cubic-bezier(.65,0,.35,1)`、`softSpring=.22,1,.36,1`、`SWAP_MS=150` |

### 新增（Compose）
| 文件 | 内容 |
|---|---|
| `ui/session/header/SessionHeader.kt` | 吸顶标题行（h-48）：返回 / ☰ / 标题（点击 → InlineInput 重命名）/ context ring / ⋮ 菜单（120dp、Popup 定位、Rename 可用 + Delete 占位） |
| `ui/session/header/ContextRing.kt` | Canvas 圆环：`size14 / strokeWidth1.5 / r6.25`、`startAngle=-90°`（= `rotate(-90)`）、`layer04` 底 + `iconBase` 进度、`.35s` 过渡、**butt cap**（旧 `ContextRingView` 用 ROUND，已纠正） |
| `ui/session/drawer/SessionDrawer.kt` | 抽屉内容：`+ 新建会话`(h-44) + 分隔线 + 会话行(h-48，标题 + 相对时间，当前项 `layer01` + 2×12dp 前导条) + 空态 |
| `ui/session/timeline/ContextToolGroupCard.kt` | 分组卡：`Exploring/正在探索` ↔ `Explored/已探索`（进行中 shimmer）+ 计数 + chevron，默认折叠；展开体为**不可展开**的纯文本行（title/subtitle） |
| `ui/session/timeline/SessionErrorCard.kt` | §3.3 错误卡：`layer01` + 0.5dp 边框 + 图标 `stateDangerFg`（**无左竖条**，遵 §3.1 裁决）+ 标题 `oc_error_title` + 单行摘要 + 可展开 body（左缩进 24dp、`maxHeight 240dp`、mono、可选中） |
| `res/drawable/ic_oc_dots.xml` / `ic_oc_alert.xml` | ⋮（`outline-dots`）/ 错误三角 |

### 修改
| 文件 | 改动 |
|---|---|
| `ui/session/SessionScreen.kt` | 重写外壳：`ModalNavigationDrawer` + 屏幕级会话状态 + `toolOpen` map + 把 `SessionHeader` 作为时间线吸顶槽；**删除 `SessionTopBar`**；错误 banner → 时间线错误卡 |
| `ui/session/timeline/MessageTimeline.kt` | 新增 `stickyHeader` / `toolOpen` / `onToggleTool` / `errorMessage` / 两个默认展开开关；assistant 轮次走 `TimelineGrouping`；错误卡作为 reverseLayout 的首项（= 视觉最底部）；`appendFingerprint` **未改**（`TimelineFingerprintTest` 仍绿） |
| `ui/session/timeline/PartRenderer.kt` | 工具卡改为受控展开（`ToolCardPolicy`）+ `AnimatedVisibility(expandVertically/shrinkVertically)`；chevron 仅在可展开时渲染；`toolIconRes`/`shimmerAlpha` 提升为 internal 供分组卡复用；本地 `ToolState` 枚举删除（统一走 policy） |
| `ui/session/timeline/ToolPresentation.kt` | 新增 `LineKind.ERROR`：error 状态工具把 `state.error` 作为 body 首行（§3.3 正文），Diff/JSON 逻辑不变 |
| `res/values/strings.xml` | +8 string（`oc_more_options`/`oc_rename_session`/`oc_delete_session`/`oc_sessions_empty`/`oc_context_exploring`/`oc_context_explored`/`oc_error_details`/`oc_time_just_now`）+ 6 `<plurals>`（时间 3 + 计数 3） |
| `OpenCodeClient.kt` | **仅新增**：`patch()` 私有助手、`renameSession()`（`PATCH /session/:id {title}`）、`Message.tokens`（可空，默认 null）、`tokenTotal()` 静态纯函数 |
| `ChatStore.kt` | **仅新增**：`UiMessage.tokens: Long? = null`（同 `UiPart.toolInput` 的“展示附加字段”先例）+ `parseMessage` 读取。`reduce/upsert/mergeParts` **0 行改动** |
| `ChatViewModel.kt` | **仅新增**：`renameSession(id, title, onResult)`（成功后镜像 `currentTitle`）；`toUi()` 透传 `tokens`。其余方法/状态机 **0 行改动** |

未改动：`EventStream.kt`（M2 未触碰，工作区里的改动来自 M1）、`ChatView.kt`、`MainActivity.kt`、`ChatStore.reduce` 语义。

---

## 3. 关键决策（本机裁决，均已落代码 + 注释）

1. **吸顶标题 = 唯一标题面（M2 简报 §6 的裁决）**：`research/02` 明确 context ring + ⋮ 属于**时间线吸顶行**而非 app titlebar，因此**删除 M1 的 `SessionTopBar`**，把「返回 + ☰ + 标题 + ring + ⋮」合成**一行 h-48**，由 `MessageTimeline(stickyHeader = …)` 承载。收益：滚动时不再出现两个标题；`back` 仍由屏幕（导航）拥有，`MessageTimeline` 只提供槽位与 48dp 视口内缩。
2. **☰ 会话列表入口位置**：放在返回箭头右侧（同一行）。理由是手机壳上没有 Home 右栏可点，返回栏是其唯一全局入口；简报中「返回下方」若按字面做成第二行会挤掉 48dp 行高规格。
3. **「吸顶」实现为「列表视口内缩 + 顶栏覆盖」**：Compose 的 `LazyColumn` 无法让内容滚到兄弟节点之下，且 `contentPadding` 在 `reverseLayout` 下的方向语义易踩坑（M1 已因 reverseLayout 改过一次底/顶 padding）。故用 `padding(top = 48dp)` 把列表视口压到标题行之下 —— 视觉与「常驻标题」一致，且**不需要** §2.2 的 48px→transparent 渐变（渐变只在内容从其下方穿过时有意义）。已在此记录为与 `02` 的实现差异。
4. **context ring 百分比**：纯函数按 `session-context-metrics.ts` 全量转写；token total 走新增的 `UiMessage.tokens`（additive）。**分母 `limit.context` 需要 provider/model 目录（`GET /config/providers`，M4 范围），M2 传 `null` ⇒ 环渲染空轨道（= 简报允许的“数据缺可暂 0”）**。一旦 M4 提供 limit，`progressOf(messages, limit)` 即产出真实比例，`ContextUsageTest` 已覆盖该跳变。**不做** >80% 变红：`02` §2.2 明确上游无该分支。
5. **工具卡默认展开状态保存位置**：屏幕级 `mutableStateMapOf<String, Boolean>`（上游 `timelineCache.toolOpen` 等价物），键 = `partId`（分组卡用 `"ctx:"+首个 partId`）。回调签名是 `(key, newOpen)`：卡片上报**已解析的**目标值，否则“默认展开的 edit 卡首次点击”会变成 no-op。
6. **pending/running 不可展开，shell 例外**：`ToolCardPolicy.canExpand` 直译 `hasChildren && !locked && (!pending || allowOpenWhilePending)`；`canExpand=false` 时连 chevron 都不渲染（`data-clickable=false` 一致）。清理了 `_M1_5` §4 中「§二.17 建议随 M6 做」的挂账。
7. **错误卡取代 composer 错误 banner**：`runStatus==ERROR || state.error != null` → 时间线末尾错误卡（§3.3），`configMissing` 的配置提示 banner 保留（那是配置问题不是运行失败）。避免同一条错误出现两处。
8. **颜色/动效全部走 token**：`OcTheme.colors`（`layer04`/`iconBase`/`stateDangerFg`/`layer01`/`borderBase`…）+ 新增 `OcMotion`（把 `research/03` §4 的 Compose 映射集中一处），无硬编码色值；新 drawable 用 `@color/oc_*`。

---

## 4. 与 `research/02` 的差异（逐条）

| § | 规格 | M2 实现 | 原因 |
|---|---|---|---|
| §2.2 | 菜单顺序 重命名/分享/导出/归档/──/删除 | 重命名（可用）+ 分隔 + 删除（置灰占位） | 简报允许占位；分享/导出/归档需要服务端 share API 与文件导出（M5） |
| §2.2 | 菜单 120px 固定宽、`menu-v2-in .12s` | 120dp 固定宽 + 0.5dp 描边；**未**做 scale .96→1 入场 | 移动端 120ms 入场收益极低，且 `OcMotion` 已有 SWAP_MS 备用 |
| §2.2 | ring 点击 → `openContext()` 打开右侧面板 | 环为**非交互指示器**（带 a11y `contentDescription`） | Review/Context 面板是 M4/M5；做成死按钮更差，已声明 |
| §2.2 | Tooltip 三行 Cost/Usage/Tokens | 未做 | `TooltipV2` 是 hover 语义；移动端无 hover，长按菜单归 M6 |
| §3.2 | `deferredMounts` + rAF 的 LIFO 挂载调度 | 用 `AnimatedVisibility`（进入才组合 body） | Compose 无 rAF 队列；弹簧时长已对齐。差异仅在“同帧展开多个重卡”的极端场景 |
| §3.2b | `deletionOnly` 依赖 `metadata.files` / `filediff` | 从**保留的 tool input/diff** 推断（无 ADD 且有 DELETE ⇒ 纯删除） | `UiPart` 只保留 `state.input/output/error`，metadata 未落地；可观测行为一致（已写进 `ToolCardPolicy` 文档与单测） |
| §3.7 | 计数动画（grid 0fr→1fr + blur + 480ms） | 计数为静态文本 | 简报未要求；`AnimatedCountList` 的入场编排属 M6 微交互清单 |
| §3.1 | `[basic-tool-tool-indicator]` 16×16 图标槽 | 沿用 M1「图标着色 + layer01 surface」 | M1 审校裁决（无左竖条）已固化，本轮未回退 |
| §7 | 会话行 = HomeSessionRow（h-10/头像/项目名，无时间） | 沿用本仓库 View 壳抽屉：h-48、标题 + 相对时间、当前项 layer01 + 前导条 | 简报明确要求「行=标题+相对时间」，且复用 `ChatView.SessionsAdapter` 既有行为；Home 右栏样式属 M3 |
| §2.1 | 顶部让位 64px / 底部 spacer 64px | 标题行 48dp + composer 实测高度 | 屏幕标题行不在虚拟列表内（M1 结构），用实测高度而非魔数 |
| §3.3 | 错误卡 hover 才出现的复制按钮 | 未做 | 触摸端无 hover；正文已 `SelectionContainer` 可手动复制 |

---

## 5. 未做项（+ 原因）

| 项 | 原因 |
|---|---|
| ⋮ 菜单的 Delete / 分享 / 导出 / 归档 | 简报允许占位。Delete 需要「删除后切到哪个会话」的列表交接（M3 导航层）+ 抽屉刷新；分享/导出依赖服务端 share API 与 SAF 写文件（M5） |
| context ring 的真实百分比 | 分母 `limit.context` 需要 provider/model 目录（`GET /config/providers`，M4）。token 分子已落地并有单测 |
| ring 点击 → Review/Context 右面板 | M4/M5 面板；M2 无宿主 |
| `_M1_review` §二.19 `collectAsStateWithLifecycle()` | 需要新依赖 `androidx.lifecycle:lifecycle-runtime-compose`；本轮坚持零新增依赖，仍挂 M6 |
| §二.9 裸 `clickable` 的 ripple / 自定义 `semantics` 全覆盖 | 本轮只补了新增控件的 `role`/`contentDescription`；全局 ripple 体系（OC2 无 M3 主题）属 M6 一次性收敛 |
| Todo dock / thinking row / diff 汇总行 | §4.2 其余条目，M6 范围 |
| `LazyColumn` 内 sticky 分组（跨消息分组、跨轮 diff 汇总） | §3.7 的分组只在单条 assistant 轮次内（上游 `groupParts` 也是 turn 级）；跨轮聚合无规格依据 |

---

## 6. 真机 checklist（本会话无设备，未手测）

1. **标题行**：点标题 → 键盘弹起并可编辑；Enter 保存（服务端 `PATCH` 成功后环/标题不变形）、Esc 取消、点别处 blur 保存；服务端置空标题不应进入编辑态。
2. **重命名失败**：关掉服务端或断网 → 乐观标题应回滚为原名（`pendingTitle` 清理）。
3. **抽屉**：☰ 打开；新建会话后应立刻切到空时间线且能发送；点会话切换后 SSE 重连（可观察网络面板），标题/消息/展开态刷新且**不**出现旧会话残留；系统返回键应先关抽屉。
4. **工具卡**：`edit`（有新增）默认展开、纯删除默认折叠、`bash` 默认折叠（设置项 M4 接）；`pending/running` 的 read 无 chevron 且点不动，运行中的 shell 可展开；展开体动画应为 ~0.35s 无回弹。
5. **分组**：连续 read/glob/grep 合并为一行，运行中显示 Exploring + shimmer，结束后变 Explored；中间夹 text 应断开；`todowrite` 不出现；默认折叠，点开显示不可展开的纯文本行。
6. **错误卡**：断开服务端发送 → 时间线底部出现错误卡，图标红色、摘要单行、展开显示完整错误（mono、可选中、超长内滚）；恢复后应消失。
7. **主题**：浅/深切换来回 2 次，标题行、抽屉、工具卡、分组卡、错误卡底色与图标色整体一致（`adb shell cmd uimode night yes|no`）。

---

## 7. 给下游（M3/M4/M6）的接口说明

- **`ToolCardPolicy.resolveOpen(part, userOpen, shellExpanded, editExpanded)`**：M4 接入
  `settings.general.row.shellToolPartsExpanded` / `editToolPartsExpanded` 时只需把设置值传进去；
  `SessionScreen` 已把两个开关透传到 `MessageTimeline`（当前常量默认 `false` / `true`）。
- **`ContextUsage.progressOf(messages, limit)`**：M4 拿到 `limit.context` 后传实参即可，环的动画/颜色无需改动。
- **`TimelineGrouping.rowsFor(parts, working)`**：跨轮聚合（diff 汇总行等）若要做，应新建函数而不是改它。
- **`ChatViewModel.renameSession`**：M3 会话列表页可直接复用（同一 `PATCH` 语义）；`OpenCodeClient.renameSession` 是客户端唯一新增写方法。
