# M3「Home 首页 + 导航/返回栈」实施状态

> 状态：**已交付，本地编译 + 单测 + 打包全绿**（结果见 §1）。
> 规格依据：`PLAN` §4.1 / §3（导航）、`research/02` §1（Home 全节）+ §9b（tab bar）+ §12（移动端）、`research/03` §3.2/§3.5。
> ⚠️ 本会话 M4（Settings/命令面板）、M5（Terminal）**并发写同一工作区**；共享文件（`ui/App.kt`、`OcActivity.kt`、`strings.xml`、`app/build.gradle.kts`）按“最小增量”合并，详见 §6。

---

## 1. 验证结果（本地实跑）

```
JAVA_HOME=C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1

.\gradlew.bat :app:compileDebugKotlin   → BUILD SUCCESSFUL（0 error）
.\gradlew.bat :app:testDebugUnitTest    → BUILD SUCCESSFUL
                                          46 suites / 343 tests / 0 failed / 0 error / 0 skipped
                                          其中 M3 新增 24 条（见 §1.2）
.\gradlew.bat :app:assembleDebug        → BUILD SUCCESSFUL（app-debug.apk ≈ 30.0 MB，CI 等价门禁）
```

> `343` 是本轮工作区总量（含 M4/M5 新增套件）；M2 基线为 `218`。

### 1.1 交付时点说明

首次跑门禁时工作区正处于 M4/M5 的写入中途（`:app:compileDebugKotlin` 报 `ui/App.kt` 的
`@Composable invocations can only happen from the context of a @Composable function`、`:app:compileDebugUnitTestKotlin` 报
`SettingsPaletteModelTest`/`SettingsStructureTest` 未解析引用、`:app:testDebugUnitTest` 被另一 Gradle 进程锁住
`app/build/test-results/.../output.bin`）。M3 的处理：
① 只用最小增量编辑共享文件、每次编辑前重读（工具在文件被他人改动后会拒绝编辑，因此不会静默覆盖）；
② 发现并修复了一次并发写入造成的**语法级损坏**（`ui/App.kt:54` 两条 import 被拼到同一行：`…NewSessionScreenimport com.…DialogController`）——
该损坏是当时全部残余编译错误的唯一根因；
③ 其余问题由 M4 自行收口后再跑门禁，最终三项目标任务全绿。

### 1.2 M3 新增测试套件（纯 JVM，无 Robolectric / 无设备）

| 套件 | 条数 | 覆盖 |
|---|---|---|
| `HomeSessionsTest` | 10 | 本地日历日分桶（含 23:59/00:01 边界）、时间未知→Older、分组固定序 + 丢空组、64 上限保新、排序与入参无关、`projectName`（POSIX/Windows/尾分隔符/`/`、`C:\`、`C:/`、null）、搜索（标题+项目名，大小写不敏感，空查询=全量，无结果=空）、骨架行数 |
| `HomeProjectsTest` | 6 | 由会话 `directory` 派生项目（去重 / 最近优先 / 顺序无关 / 无目录忽略 / root 回退 / 空输入 / 最近关闭恒空） |
| `HomeNavTest` | 8 | 路由存取往返、无 id 的会话不恢复、瞬态路由（new-session/settings/terminal）不恢复、目的地→存储值映射、会话路由模式与 nav 图一致、serverKey 稳定且路径安全、`appearance.tabLayout` 默认关 |
| **合计** | **24** | |

> 首轮跑测时 `HomeSessionsTest` 抓到一个**真实缺陷**：Windows 盘根 `C:\` 会被当成项目名 `C:`（应为无名字）——已修
> `HomeSessions.projectName()` 并补 `C:/` 用例。

### 1.3 跨 agent 依赖修复

`app/build.gradle.kts` 原先缺 `androidx.lifecycle:lifecycle-runtime-compose`，而 M4 的 `ui/App.kt` 已在用
`collectAsStateWithLifecycle`（M2 曾把该依赖挂到 M6）→ **整仓编译不通过**。M3 补上这一行
（本机 Gradle 缓存已有该制品，可离线解析），最终只存在一行、无重复。

---

## 2. 文件清单

### 新增（纯 Kotlin，JVM 可测）
| 文件 | 内容 |
|---|---|
| `ui/home/HomeSessions.kt` | Home 右栏模型：`MAX_SESSIONS=64`、`SKELETON_ROWS=4`、`Group{TODAY,YESTERDAY,OLDER}`、`rows()`（按 updatedAt 降序 + 截断）、`sections()`（固定序、丢空组）、`groupOf()`（**本地日历日**分桶；`updatedAt<=0` → Older）、`filter()`、`projectName()`（POSIX/Windows basename） |
| `ui/home/HomeProjects.kt` | 左栏模型：`fromSessions()` = 会话目录去重（最近使用优先）；`RECENTLY_CLOSED = emptyList()`（**无数据源，不臆造**） |
| `ui/nav/LastRouteStore.kt` | `NavMemory`（接口，便于测试/preview）+ `LastRouteStore`（**SharedPreferences**，非加密）+ `NavRestore`（纯函数：key、`stored()`、`target()`、`toStore()`、`serverKeyOf()`） |
| `ui/nav/AppearancePrefs.kt` | `appearance.tabLayout`（upstream 原 key）+ `TabLayout.bottomStrip()`（仅 `"vertical"` 为真，**默认关**） |
| `ui/theme/OcShadows.kt` | `Modifier.ocShadow/ocRaisedShadow/ocFloatingShadow`：把 `--v2-elevation-*`（blur 当 elevation + token 颜色当 ambient/spot）集中一处，Composable 不再写 dp/颜色 |

### 新增（Compose）
| 文件 | 内容 |
|---|---|
| `ui/home/HomeScreen.kt` | 整页一张卡（`m-8` + radius 10 + `elevation-raised`）；`BoxWithConstraints` 判定 `≥1024dp` → 两栏 `280dp + 32dp gap + minmax(0,720dp)`，移动单列（会话区 `flex-1` + 底部 `HomeUtilityNav`）；内容 `max-w-1080dp` 居中、移动 `px-12` / 桌面 `px-24` |
| `ui/home/HomeSessionsList.kt` | 右栏：搜索框（h36/radius6/`layer02@60%`）+ 清除按钮、`sticky` 分组头（`h-7`，**滚动渐隐**：读 `firstVisibleItemIndex/ScrollOffset` 在一个头高内淡出到 0.25）、会话行（h40/radius6/`pl-3 pr-40`：头像 + 打开标记 + 标题 + 项目名）、骨架屏、空状态（居中 + 中性主按钮）、悬浮 ✎（钉在首分组头那一行 = upstream `top-[84px]/lg:top-[108px]`）、搜索浮层（radius12 + `elevation-floating` + `max-h-320dp` + "Sessions" 标题 + 无结果文案）、`BackHandler` 优先关浮层 |
| `ui/home/HomeProjectsList.kt` | 左栏：`Projects` 头（h28/13px/530）+ 添加项目图标按钮、空态=「添加项目」入口行、项目行（h28/radius6/选中 `layer03` + 尾部 ✎）、`最近关闭` 分区（有空才显示）、底部 `HomeUtilityNav`；桌面偏移 `mt-14 + pt-52 = 108dp` |
| `ui/home/HomeUtilityNav.kt` | Settings(gear) + Help 两行（h28/radius6/`gap-4px`/label `text-faint`/icon `icon-muted`），容器 `pr-3` |
| `ui/home/NewSessionScreen.kt` | `new-session` 路由：`ensureClient()` → `createSession()` → 进会话路由（pop 掉 draft）；无配置显示 setup 提示，3s 未回则给 Retry/Back |
| `ui/nav/BottomTabStrip.kt` | 可选底部标题栏（§9b）：`Home 按钮(grid-plus) + 会话 tab + “+”`，条高 36dp / tab 28dp / radius 6 / 相邻非活动 tab 间 1.5×12 分隔条 / 槽宽上限 224dp |

### 修改（增量，全部保留 M4/M5 现有语义）
| 文件 | 改动 |
|---|---|
| `ui/App.kt` | 仅 5 处：①`OcApp` 新增 `serverKey/lastRoute/bottomTabStrip` 参数；②M3 nav 状态（`openSessions`/`currentSession`/`currentSessionTitle`）+ **冷启动恢复一次**（Home 仍为 startDestination，恢复页压栈 → Back 一定有 Home）+ **目的地变更即持久化**；③`HOME` 路由改调 `ui.home.HomeScreen`（传 `chatViewModel`/打开集合/三个回调）；④`NEW_SESSION` 路由改调 `NewSessionScreen`；⑤NavHost 外包 `Column{Box(weight)+可选 BottomTabStrip}`。**M4 的 DialogHost/GlobalKeybindFocus/palette/settings、M5 的 terminal 路由与 `TerminalEntryButton` 全部原样保留** |
| `OcActivity.kt` | 注入 `LastRouteStore` / `AppearancePrefs` / `serverKey`（`NavRestore.serverKeyOf(server.url)`），并把三者传入 `OcApp` |
| `ChatViewModel.kt` | **仅新增** `ensureClient(): Boolean`（建 REST 客户端 + model label，**不开 SSE**）；`start()` 内部改走同一 helper，行为逐分支等价（本文件 `stop()`/状态机/reduce 0 行改动） |
| `ui/theme/OcTheme.kt` | **仅新增** token `buttonNeutral`（`--v2-background-bg-button-neutral`：dark `#ffffff0f` / light `#ffffff`，`research/03` §3.2） |
| `res/values/strings.xml` | 尾部追加 M3 段（`oc_home_*` 22 条，英文默认；值取自 `research/_i18n/en.json`：`home.*` / `sidebar.settings|help` / `command.session.new`） |
| `app/build.gradle.kts` | +1 行 `lifecycle-runtime-compose`（见 §1.3） |
| 删除 | `ui/screen/HomeScreen.kt`（M0 空态占位，仅 `ui/App.kt` 引用；已被 `ui/home/HomeScreen.kt` 取代，`ui/screen/` 目录一并删除） |

### 新增资源
`drawable/ic_oc_gear.xml`、`ic_oc_help.xml`、`ic_oc_edit.xml`、`ic_oc_close.xml`、`ic_oc_grid.xml`、`ic_oc_folder_add.xml`
（stroke 向量沿用既有 `ic_oc_*.xml` 风格，颜色 `@color/oc_icon_muted`，用点用 `OcTheme.colors` tint）。

---

## 3. 关键决策（已落代码 + 注释）

1. **Home 不给自己开 SSE**：M3 需要 REST 客户端才能 `listSessions`，但 M1 明确「停在 Home 不产生连接」。
   因此给 `ChatViewModel` 加了**纯增量**的 `ensureClient()`（只建 client + model label，不起 `EventStream`），
   `start()` 改为经它构建，逐分支等价。Home 进入时 `ensureClient()` + `listSessions()`，离开即不再持有任何流。
2. **恢复上次页面 = 「Home 压底 + 恢复页压栈」**：`NavHost(startDestination = HOME)`，冷启动读一次存储并 `navigate(restored)`
   （`launchSingleTop`）。这样「恢复到会话」同时满足「返回栈 Home → 会话 逐层回退」。
   只持久化 `home` / 具体会话；`new-session`（draft）与 `settings`（弹窗）是瞬态 → 不覆盖存储值，冷启动回到它们**之前**的页面。
3. **store 形态**：`NavMemory` 接口 + `LastRouteStore`（普通 `SharedPreferences("oc_nav_state")`）。
   不用 `EncryptedSharedPreferences`：路由名 + 会话 id 不是密钥，且它是冷启动路径，加密存储的 key 派生只会增加延迟。
4. **BackHandler 分层**：Home 的搜索浮层自己注册 `BackHandler(enabled = query.isNotEmpty())` → 先关浮层；
   会话内抽屉由 M2 的 `ModalNavigationDrawer` 负责；其余交给 NavHost。**未改 `ui/session/*` 一行**。
5. **`appearance.tabLayout` 默认关**：M3 只**读**该值（M4 的 Settings General 面板才是写入方），
   `"vertical"` 才渲染底部标题栏；条内 Home 按钮/`+`/关闭按钮都按 §9b 语义（`+` 直接新建、无菜单）。
6. **分组用「本地日历日」而非 24h 窗口**：23:00（昨天）仍属 Yesterday，隔天 23:59 属 Older；
   `updatedAt <= 0`（时间未知）归 Older，不能让无时间戳的会话冒充“今天”。
7. **搜索浮层保留 upstream 结构、简化匹配**：面板位置/圆角 12/`elevation-floating`/`max-h-320`/行 h40 `pl-18 pr-24`/"Sessions" 标题/无结果文案逐条对齐；
   匹配算法从 fuzzysort 降级为**子串（标题 + 项目名，大小写不敏感）**——fuzzysort 的打分不可移植，且 M3 无依赖可加。
8. **骨架屏静态**：`HomeSessionSkeleton` = 标题条 + 4×h40 `bg-deep@70%`，**不做 shimmer**（动效归 M6）。

---

## 4. 与 `research/02` 的差异（逐条）

| § | 规格 | M3 实现 | 原因 |
|---|---|---|---|
| §1.1 | 移动端单列 = `[项目区(auto), 会话区(1fr), HomeUtilityNav(auto)]`，项目区在手机上**也显示** | 移动单列 = 会话区 + 底部 `HomeUtilityNav`；**项目区仅 ≥1024dp 显示** | M3 简报明确「左栏 Projects（≥1024 显示）」；两者冲突时按简报执行（代价：移动端无「添加项目」入口） |
| §1.2 | 项目行 hover 显示 ⋮ 菜单 + ✎；右键=同菜单；拖拽排序（触摸禁用） | 仅保留常显 ✎ + 选中态；**不做** ⋮ 菜单/右键/拖拽 | 触摸端无 hover；编辑/显示/清除/关闭需要项目 CRUD（M4/M5），拖拽注释本身就写明 `pointerType !== "touch"` |
| §1.2 | `HomeRecentlyClosedRow` 可点击「重新加入项目列表」 | 分区代码已就位，但 `RECENTLY_CLOSED` 恒空 → 不显示 | 客户端无 `/project` 端点，凭空造「最近关闭」= 臆造（§13.2） |
| §1.2 | 项目列表来自 `layout.projects`（`GET /project`） | 由会话 `directory` 派生（最近使用优先），标注待 server 端 | 同上；简报允许先用会话/占位 |
| §1.3 | `max-w-[min(70%,480px)]` 标题宽度 | Row 权重 0.7 / 0.3（`fill=false`）近似 | Compose 无 `flex-basis` 组合；0.7 与 `min(70%,480px)` 的意图一致 |
| §1.3 | 头像 = `SessionTabAvatarView`/`ProjectAvatar`（服务端图片 + emoji 回退） | 18dp 圆角方块 + 首字母 | 客户端无图片 URL/项目端点（同 §1.2 差异） |
| §1.3 | 打开标记 = 「已打开为 tab」的会话 | 标记 = **本次运行已访问过**的会话（`openSessions`） | M3 无应用级 tab store（§4.7 归后续里程碑）；这比“永远不显示”更接近语义，且已写进注释 |
| §1.3 | 搜索框有 `ArrowDown/Up`/`Enter`/`Escape`/`aria-activedescendant` | 只做输入 + 清除 + Back 关浮层 | 移动端无硬件键盘；键盘导航归 M6（`aria-*` 无等价物） |
| §1.2 | 左栏 `lg:sticky`、自绘 12px 滚动条轨道 | 不做吸顶/自绘滚动条 | 会话列表本身不滚动整页（搜索区固定）；自绘滚动条 M6 |
| §9b.2 | 标题溢出 mask 渐隐、`@container` 窄容器规则、hover 预览卡（2s） | 未做 | 触摸端无 hover；M3 只交付「可选底部标题栏」的最小可用形态 |
| §9b.3 | `Tab[]` store（session/draft）、`tabs.recent/closed` 持久化、拖拽排序/中键关闭 | 只保留本次运行打开的会话（可切 Home/会话、可关） | §4.7 应用级 tab store 属后续里程碑；简报只要求「开启时底部有 Home/会话 tab 条，可切」 |

---

## 5. 未做项（+ 原因）

| 项 | 原因 |
|---|---|
| 项目 CRUD / 目录选择器（`dialog.directory.*`、编辑项目） | M4/M5 范围；M3 用 inline 提示 `oc_home_add_project_soon` 明示，不静默失败 |
| Help 弹窗 | 无宿主；inline 提示 `oc_home_help_soon`（同 `oc_model_picker_soon` 先例） |
| 项目选中 → 新会话限定在该目录 | 需要 `ChatViewModel.createSession(directory)`（现无此重载；`OpenCodeClient.createSession(directory)` 已有）。M3 只保留选中态视觉（§1.2 `data-[selected]`） |
| 会话行 ⋮ / 归档（`SHOW_HOME_SESSION_ARCHIVE === false` 上游本来也关） | 上游常量即关；行尾 40dp 空槽已按规格保留 |
| 骨架屏 shimmer、进/退场动效、reduced-motion | M6 动效里程碑 |
| 真机 UI 验证 | 本会话无设备；已提供 §7 手测清单 |
| 分组头渐隐的**精确** upstream 曲线 | `home-scroll-controller` 的 opacity 驱动公式未取证，M3 用「一个头高内线性淡出到 0.25」的等价实现（已注释声明） |

---

## 6. 共享文件合并说明（给 Lead / reviewer）

本会话 M3/M4/M5 **并发写同一工作区**，`ui/App.kt` 在 M3 实施期间被 M4、M5 各重写多次。M3 的合并策略：

1. **不重写共享文件，只做最小增量编辑**，每次编辑前重读（工具会因文件变化拒绝编辑，因此不会静默覆盖他人改动）。
2. `ui/App.kt` 的 5 处改动见 §2；M4 的 `DialogHost`/`GlobalKeybindFocus`/palette/settings（含单例 `onOpenSettings`）、M5 的 terminal 路由 + `TerminalEntryButton` **均原样保留**。
   M4 随后把我的 nav 状态与恢复/持久化逻辑收进了新的 `AppScaffold`（`OcApp` 只负责主题 + `DialogHost`）——**M3 各段逻辑逐行保留**，
   `OcApp(… serverKey, lastRoute, bottomTabStrip)` 参数与 `OcActivity` 的注入保持不变，最终形态是双方共同收敛的结果（已复核 §5 引用的每一行）。
3. 为降低冲突，M3 的 nav 辅助函数**不引用** `ui/App.kt#Route`（该 `object Route` 属 M4/M5 的编辑区），
   `NavRestore.session()` 内部构造同一模式串，并在注释里标注「must match `ui/App.kt#Route`」；`HomeNavTest` 有一条断言把两者钉在一起。
4. `strings.xml` 只**尾部追加** `oc_home_*`（与 M4 的 `oc_setting_*` / M5 的键无重名 —— 已用脚本查重，0 重复）；新增 drawable 全部为新名字 `ic_oc_*`。
5. **遗留待 Lead 决策**：`ui/App.kt` 里的 `object Route` 目前是唯一一份路由表（M3 未引入第二份）；
   若后续要把 nav 图整体搬到 `ui/nav/`，需要一次串行重构（届时 `NavRestore.session()` 可改为调用 `Route.session()`）。

---

## 7. 真机 checklist（本会话无设备，未手测）

1. **冷启动恢复**：进入某会话 → 杀进程 → 重开：应直接回到该会话，Back 回 Home。
   若存的是已删除会话：应落回 Home（不崩）。首次安装 → Home。
2. **Home 空态**：无会话时显示「这里还没有内容 / 创建一个会话以开始。」+ 中性按钮；点击 → 创建会话 → 进会话页 → Back 回 Home 且列表已刷新。
3. **悬浮 ✎**：位置应与首个分组头同一行、右对齐；点击 → 直接进新会话（draft 不留在返回栈）。
4. **分组**：造 3 条会话（今天 / 昨天 / 更早）→ 三个分组头顺序正确，滚动时标题应渐隐；
   `adb shell cmd uimode night yes|no` 切换深浅色，卡片/行/骨架色一致。
5. **搜索**：输入 → 浮层出现在搜索框下方（外扩 6dp）；命中标题与项目名（大小写不敏感）；无命中显示 `No sessions found for <q>`；Back 只关浮层不退出 Home。
6. **两栏**：把设备/模拟器宽度设到 ≥1024dp（或横屏平板）→ 左栏 280dp 出现「Projects」+ 项目行 + 底部 Settings/Help；<1024dp 时左栏消失、底部出现 Settings/Help。
7. **Settings 入口**：Home 的 gear 应打开 M4 的 x-large 设置弹窗（M3 只传回调，不建路由）。
8. **可选底部标题栏**：写入 `appearance.tabLayout=vertical`（M4 设置项或
   `adb shell run-as com.xiufeigo.opencodeshell` 手工写 pref）→ 重启后底部出现 `[Home][会话][+]`；Home 可切回会话 tab，`+` 直接新建，关闭按钮关掉 tab 后回 Home。
