# Wave 5-S：设置页补全（D1 / D4 / D5 / D6，D2 探明）

工作区：`C:\sensorsdata\main\program\opencode-android-shell`
上游规格：`_upstream/packages/app/src/settings/**`（分支 v2）、
`_upstream/packages/session-ui/src/timeline/detail.ts`。

本轮改动**只落在**允许清单内：`ui/settings/**`、`SettingsStore.kt`、三份 `strings.xml`、
`app/src/test/**`、本文件。**`ui/App.kt` / `ui/session/**` / `ChatStore.kt` /
`ChatViewModel.kt` / `OpenCodeClient.kt` / `ui/theme/**` / `ui/terminal/**` 一行未动**，
下面每条「需要 Lead 接线」都写了确切位置与改动量。

---

## 0. 结论速览

| 项 | 结果 |
|---|---|
| 根 tab | 7 个禁用行 → **6 个变成真页面**（外观 / 通知 / 项目 / 工作树 / 扩展 / 关于），只剩 `experimental` 一个禁用行 |
| **D1 关于** | 完成：app 名 + `BuildConfig.VERSION_NAME` + MIT / 官网 / 版权（`about.tsx:42-90` 的本机子集） |
| **D1 外观** | 完成，但**只放有消费方的两行**：配色方案（原有生效）+ 终端字体（`ui/App.kt:548` 已接）。UI/code 字体继续隐藏，见 §D1-b |
| **D1 通知** | 完成：并发波次 W5-E5 的 `AppNotifier` 已把 `notifyAgent/notifyPermissions/notifyErrors` 变成真消费方，三个开关上线；音效三行仍隐藏（无播放器） |
| **D1 项目 / 工作树 / 扩展** | 完成：只读列表页（`GET /project`、`GET /experimental/worktree`、`GET /mcp`、`GET /skill`），除 Reload 外**零按钮** |
| **D4 快捷键录制** | 「录键 → 冲突检测 → 落盘 → Reset all」全链路完成；**`globalChord` 未接**（`ui/App.kt` 禁改），见 §D4 |
| **D5 Timeline 预设** | 完成：预设下拉（Everything / Compact / Quiet + `Custom` 检测），底层仍是 3 个真实布尔；`detailed`/`text-only` 不可映射，理由见 §D5 |
| **D6 其它上游行** | **未新增任何行** —— 候选行的消费方全在本轮禁改文件里，逐条列在 §D6 |
| **D2 Providers 连接** | 只读探明：端点**存在**（`PUT`/`DELETE /auth/{id}` 均 200），但列表刷新钩子不可达，故未做写路径，见 §D2 |

---

## 1. 改动的文件

生产代码（全部在 `app/src/main/java/com/xiufeigo/opencodeshell/`）：

| 文件 | 改动 |
|---|---|
| `ui/settings/SettingsModel.kt` | 新增 6 个 tab（appearance / notifications / projects / workspaces / extensions / about）、`APPEARANCE_PANEL` / `NOTIFICATIONS_PANEL`、`panelsFor`/`ROW_PANELS`/`ALL_SETTINGS_PANELS`、`TimelineDetailPreset` + `TIMELINE_DETAIL_OPTIONS`、键位录制模型（`normalizeKeyName`/`formatKeybindChord`/`keybindSignatures`/`keybindConflicts`/`applyKeybindOverrides`/`isKeybindUnassigned`）、四个只读页的纯解析（`projectPageRows`/`worktreePageRows`/`mcpPageRows`/`skillPageRows`） |
| `ui/settings/SettingsPanels.kt` | `AppearancePanel` / `NotificationsPanel` / `AboutPanel` / `ProjectsPanel` / `WorktreesPanel` / `ExtensionsPanel`、`TimelineDetailControl`、带录制与冲突提示的 `ShortcutsPanel` |
| `ui/settings/SettingsScreen.kt` | `PanelSlot` 覆盖全部 10 个 tab（`when` 穷尽，缺页即编译错误）、导航图标可空（新页面画 14dp 占位而不是错图标） |
| `ui/settings/SettingsServerApi.kt`（新） | 只读 HTTP：`GET /project` `/experimental/worktree` `/mcp` `/skill` + `SettingsPageLoad` 三态结果 |
| `ui/settings/SettingsServerBridge.kt` | 实现新的可选能力 `SettingsServerData`（不新增 `SettingsPanelExtras` 字段，见 §D1-c） |
| `ui/settings/SettingsComponents.kt` | `KeybindChip` 支持录制态 + 点击 |
| `SettingsStore.kt` | `Settings.keybinds`（覆盖表）+ `setKeybind`/`clearKeybind`/`resetKeybinds`/`hasKeybindOverrides`、`encodeKeybinds`/`parseKeybinds`、`settings-timeline-detail` 预设写入、`notifyErrors` 默认值对齐上游 `false` |

资源：`res/values/strings.xml`、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml`
（**只追加**，全部落在 `oc_settings_` / `oc_about_` 前缀内；`oc_keybind_`/`oc_mcp_` 前缀本轮未用到，
因为既有 `oc_shortcut_*` 已覆盖录制文案）。

测试：`app/src/test/java/com/xiufeigo/opencodeshell/Wave5SettingsTest.kt`（新，18 例）、
`SettingsStructureTest.kt`、`ShellWiringTest.kt`（随结构更新）。

---

## 2. D1 逐步说明

### a) 关于页（完成）

- 内容：`oc_settings_app_name`（"OpenCode Desktop"）、`Version %1$s`（`BuildConfig.VERSION_NAME`，
  `app/build.gradle.kts` 的 `versionName = "1.4.0"`，`buildConfig = true` 已开）、
  `Released under the MIT License`、`OpenCode, the open source coding agent`、
  `www.opencode.ai`（`LocalUriHandler` 真打开浏览器）、`© Anomaly Innovations, Inc.`。
- 文案逐字取自 `_upstream/packages/app/src/runtime/i18n/en.ts:1042-1057`。
- **未做**：AnimatedWordmark / 贡献者名单 / `legal.svg` 版权图（`about.tsx:46-91`）——
  这些是桌面端资产与远端贡献者计数请求，本机没有对应资源，画个空框不如不画。

### b) 外观页（完成，但只放 2 行）

- 上线：`settings-color-scheme`（`ui/App.kt` → `Settings.isDark` → `OcTheme(isDark)`）、
  `settings-terminal-font`（`ui/App.kt:548` → `TerminalPanelHost.terminalFont`）。
- 结构与上游一致：一个无标题 `SettingsList`（`appearance.tsx:81-127`）+ 页面标题/副标题。
- **`settings-ui-font` / `settings-code-font` 继续隐藏**（仍在 `HIDDEN_SETTINGS`）。
  硬约束要求「接了真消费方才允许出现」，而三处消费点全部在本轮禁改范围内：

  | 消费点 | 现状 | 接线所需 |
  |---|---|---|
  | `ui/theme/OcTheme.kt:323,348-360` `LocalOcFontFamily` | 只提供 `OcFonts.default`，`OcTheme(isDark)` 从 `LocalOcFontFamily.current` 读 | 在 `ui/App.kt:188` 的 `OcTheme(isDark = dark)` **外层**包一层 `CompositionLocalProvider(LocalOcFontFamily provides …)`（`internal` 可见性，同模块可写），或在 `OcTheme()` 增加 `fontFamily` 参数 |
  | `ui/markdown/MarkdownRenderer.kt:281,410`（code block 固定 `FontFamily.Monospace`） | 需要读 `LocalOcMonoFontFamily`（已存在，`OcTheme.kt:326`），或由调用点传参 | 改 `ui/markdown/**` |
  | `ui/terminal/TerminalPanel.kt:119,139` | **已接线**（`App.kt:548` 传 `settings.terminalFont`） | 无需 |

  也就是说：`OcTheme.kt` 已经预留了 `LocalOcFontFamily`，缺的是「谁来 provide」；
  本轮的 `ui/settings/**` 无法 provide 到整棵树（settings 页面在 dialog 里，包一层只影响设置页自己，
  那是假的）。→ **需要 Lead 接线**：`ui/App.kt` 一行 `CompositionLocalProvider`。

### c) 通知页（完成，3 个开关上线）

- 上线：`settings-notifications-agent` / `-permissions` / `-errors`（`settings-notifications` 段标题 = `System notifications`）。
- 消费方**已经存在**（并发波次 W5-E5）：`ChatViewModel.kt:204`
  `AppNotifier(enabled = ::notificationEnabled)`，`ChatViewModel.kt:229-231` 逐 kind 读
  `settings.notifyAgent` / `notifyPermissions` / `notifyErrors`；`notify/AppNotifier.kt:70-76` 的
  `raise()` 在关掉时直接返回 `null`。
- 因此这三行**不是**「改了没用」的开关，而是本轮真正打通的一条闭环（原计划的「只做设置行 + 写明需要
  Lead 接发送方」已被 E5 提前满足，故直接上线；`Wave5EventsWiringTest.kt:129-133` 已在守护这条链路）。
- 顺带对齐一个默认值：`notifyErrors` 由 `true` 改为上游的 **`false`**（`settings/model.tsx:261`，
  审计「附 1」把它列为不一致项）；开关现在人人可见，默认值必须按上游。
- **音效三行（`settings-sounds-*`）继续隐藏**：`shell/notifications/sound.ts` 在原生侧没有对应物，
  `bundled aac`（`assets/opencode-app/assets/staplebops-*.aac`）只有 WebView 在用，原生没有播放器。
  行一出现就是死开关。接线点：新增原生播放器（`SoundPool`/`MediaPlayer` + 音效资源）后，把
  `HIDDEN_SETTINGS` 里的三条移到 `NOTIFICATIONS_PANEL`（store 键与 `SOUNDS` 取值域都已就绪）。

### d) 项目 / 工作树 / 扩展（完成，只读）

三个页面都是**只读**，除 Reload 外没有任何按钮：

| 页面 | 数据源（本机实测） | 行内容 |
|---|---|---|
| 项目 Projects | `GET /project` → 200，本机 30+ 条，形如 `{id,worktree,vcs,icon,time{created,updated},sandboxes}` | 名称（worktree 末段）+ 完整路径 + `vcs` 标签，按 `time.updated` 倒序 |
| 工作树 Worktrees | `GET /experimental/worktree` → 200（本机 `[]`） | 名称 + 目录 + branch 标签 |
| 扩展 Extensions | `GET /mcp` → 200（本机 `{}`）；`GET /skill` → 200（本机 1 条 `customize-opencode`） | MCP 段：名称 + 状态胶囊（connected 高亮 / failed 显示 error）+ 全文只读；Skills 段：名称 + 描述 |

- 上游锚点：`settings/workspaces/projects.tsx`、`settings/workspaces/workspaces.tsx`、
  `settings/providers/extensions.tsx:24-169`。
- 导航标签用上游的 **"Worktrees"**（`settings.tab.workspaces`），页头副标题用
  `settings.workspaces.description`。
- **为什么没有写操作**：上游这些页面的增删改都是写接口（`worktree.create/remove`、`project.*`、
  `mcp` 开关），而 `OpenCodeClient` 本轮禁改；在 `ui/settings` 里另起一套写接口会让「谁负责状态刷新」
  变成两套。因此本轮只做只读，读路径集中在新的 `SettingsServerApi`。
- **扩展页为什么没有 Plugins 子页**：本机 `GET /plugin` 不是端点（返回 SPA HTML），
  上游 `pluginLabels` 依赖 v2 的 `plugin.list()`；画一个永远空的 Plugins 页就是空壳，故不画。
- **MCP 开关画不出**：上游 `useMcpToggle` 会 POST 到服务端；没有刷新钩子（见 §D2 同理）所以不画。

---

## 3. D4 快捷键录制

实现位置：`ui/settings/SettingsPanels.kt`（`ShortcutsPanel`）+ `ui/settings/SettingsModel.kt`（纯模型）+
`SettingsStore.kt`（持久化）。

对齐上游 `settings/keybinds/keybinds.tsx:248-338` 的行为：

| 行为 | 上游 | 本轮 |
|---|---|---|
| 点击胶囊进入录制 | `toggle(id)`（`:248-256`），按钮文案变 `Press keys` | ✅ 同一个胶囊（`KeybindChip(recording = …)`），`Press keys` 用既有 `oc_shortcut_press_keys` |
| 录制哪一次按键 | `handle()` 捕获 keydown，忽略裸修饰键 | ✅ `onPreviewKeyEvent` + `normalizeKeyName`（`KEYCODE_*` → `A`/`Enter`/`` ` ``/`ArrowLeft`…，裸修饰键返回 null 继续等） |
| `Esc` 取消 | ✅ | ✅ |
| `Backspace`/`Delete`（无修饰键）= 清空 | 写 `"none"` | ✅ `store.clearKeybind(id)`，胶囊显示 `Unassigned` |
| 冲突检测 | `signatures()` + `used()`，命中则 toast 且**不写** | ✅ `keybindSignatures` / `keybindConflicts`，命中原样保留录制态并在标题下显示一行
`%1$s is already assigned to %2$s.`（冲突行标题列表） |
| 落盘 | `settings.keybinds` | ✅ `SettingsStore.KEY_KEYBINDS = "opencode.android.keybinds"`，JSON 对象字符串（`encodeKeybinds`/`parseKeybinds` 纯函数，可 JVM 测） |
| Reset all（`hasOverrides` 时才可用） | ✅ | ✅ `Reset to defaults` 按钮，`enabled = settings.keybinds.isNotEmpty()` |
| 搜索框（title + keybind） | fuzzysort | ✅ 大小写无关 contains（title + keybind），比 fuzzysort 弱但同一语义 |
| 每行显示**生效值** | `effective(id)` | ✅ `applyKeybindOverrides(settings.keybinds)`，覆盖值优先，`none` 显示 `Unassigned` |

**未完成（需要 Lead 接线，1 处）**：`ui/App.kt` 的键盘处理仍用硬编码和弦，
覆盖表没有进 `globalChord`：

- `ui/App.kt:452-466` 附近的 `globalChord(event)`（实现于 `ui/palette/PaletteModel.kt`）只返回
  内置的 `PALETTE / PALETTE_FILES / SETTINGS / NEW_SESSION / TERMINAL / SESSION_SEARCH`；
- 接线方式：在 `App.kt` 里把 `store.state.value.keybinds` 传进 `globalChord`（或加一层
  `effectiveChord(overrides)` 包装），命中覆盖值时映射到同一个 `GlobalChord` 枚举。
  建议只放行**已有动作**的 id（`command.palette`/`settings.open`/`file.open`/`session.new`/`tab.new`/
  `terminal.toggle`/`review.toggle`/`fileTree.toggle`），否则就要连命令分发一起改。
- 在此之前，录制结果的作用范围是：**持久化 + 面板显示**（即面板立刻反映新键位、冲突被真实拦截、
  Reset 真的清空），但按下去仍是内置和弦。这一条已在报告里显式声明，没有伪装成已生效。
- 另一个已知差距：上游键位表来自运行时命令注册表（几十条），Android 只有 9 条
  （`SHORTCUT_ROWS`，与 `ui/App.kt` 真正实现的动作一一对应）。列表编造上游存在但本机没实现的命令
  会立刻变成「按了没反应」的行，故维持 9 条。

---

## 4. D5 Timeline detail 预设

上游模型（`session-ui/src/timeline/detail.ts:17-73`）：6 类别 × `placement`(separate/grouped/hidden)
× `details`(expanded/collapsed)，5 个预设。Android 的消费方只有三个布尔
（`TimelineDisplayPrefs` → `MessageTimeline`/`PartRenderer`/`ToolCardPolicy`：

- `shellToolPartsExpanded`、`editToolPartsExpanded` → 卡片默认展开/折叠；
- `reasoningSummaries` → **false 时 reasoning 直接不渲染**（`PartRenderer.kt:138`、`TimelineGrouping.kt:169`）。

可映射子空间与 `TimelineDetailPreset` 的对应（**这是本轮的选择器依据**）：

| 上游预设 | shell | edit | thinking | Android 投影 | 是否作为选项 |
|---|---|---|---|---|---|
| `everything` | separate/expanded | separate/expanded | separate/expanded | 展开 + 展开 + 显示 | ✅ `Everything` |
| `detailed` | separate/expanded | separate/expanded | grouped/collapsed | 与 `everything` **完全相同** | ❌（重复选项=假选项） |
| `compact` | grouped/collapsed | grouped/collapsed | grouped/collapsed | 折叠 + 折叠 + 显示 | ✅ `Compact` |
| `quiet` | hidden/collapsed | grouped/collapsed | hidden/collapsed | 折叠 + 折叠 + 隐藏 | ✅ `Quiet`（Android 无法隐藏 shell 卡片，取它在本机表达得出来的最简状态） |
| `text-only` | hidden | hidden | hidden | 不可表达（工具卡片永远渲染） | ❌ |

- 选择器是**检测式**的：任何不在预设里的三布尔组合显示 `Custom`
  （对应上游 `timelinePreset(value) === undefined` → `settings.timeline.custom`），
  选中某个预设时 `SettingsStore.update("settings-timeline-detail", id)` 一次性写三个布尔
  （`TIMELINE_DETAIL_PRESETS` 表，`Wave5SettingsTest` 里有防漂移断言）。
- 三个单布尔开关保留在预设下面，作为上游「Advanced」折叠区的等价物 —— 这样
  (展开, 折叠, 显示) 这类组合仍然可达，而不会被预设系统吞掉。
- 未做（需要 Lead 决定是否扩消费方）：`placement` 的 grouped/separate、`notices`/`tools`/`subagents`
  的 hidden —— 这些要改 `ui/session/timeline/**` 的渲染策略，本轮禁改。

---

## 5. D6 其它上游设置行：**一条都没加**

逐个候选核对（含上游 file:line、Android 消费方、本轮能否接线）：

| 候选 | 上游 | 需要的消费方 | 本轮结论 |
|---|---|---|---|
| `general.showFileTree` | `model.tsx:243`（默认 false；v2 无 UI 行） | `use-session-commands.tsx:375` 门控 `fileTree.toggle` 注册 | **已生效**（W4-D 完成，`ui/App.kt:782` 传 `showFileTree`）——本轮不需要动 |
| `general.mobileDiffWrap` | `model.tsx:251`（默认 true） | 评审面板的 `softWrap`（`ui/session/review/ReviewChangesTab.kt:304,339,399`、`ReviewFilesTab.kt:536`） | ❌ 消费方在 `ui/session/**`（禁改）。接线量：给 4 处 `softWrap` 接 `LocalTimelineDisplayPrefs` 之外的新 CompositionLocal 或参数 |
| `general.terminalPlacement` | `model.tsx:252`（默认 "side"） | 终端面板布局 | ❌ 终端在原生里是**独立路由**（`ui/App.kt:153` `terminal?directory=`），没有 side/bottom 容器可切；上游是桌面双栏概念 |
| `general.followUpBehavior` | `model.tsx:253`（queue/steer） | composer 提交策略（`ui/session/composer/**`） | ❌ 禁改 |
| `workspace.defaultDestination` | `model.tsx:260` | 新会话目的地（`ui/home/**`） | ❌ 禁改（且 `ui/home` 不在允许清单） |
| `general.showCustomAgents` | `general.tsx:327-337` | composer 的 agent 选择器 | ❌ composer 无 agent chip（`ui/session/composer/**` 禁改） |
| `appearance.tabLayout`（实验性页第 1 行） | `experimental.tsx:38-51` | `ui/nav/AppearancePrefs.kt` 读 `appearance.tabLayout`，但**没人写**；值经 `OcActivity.kt:62` → `bottomTabStrip` | ❌ 写入口要落在 `OcActivity`/`ui/App.kt`（禁改）。若 Lead 想开：本轮 `SettingsStore` 已有 `update(action,value)` 的形状，加一个 `appearance-tab-layout` 动作 + 让 `AppearancePrefs` 读同一个键即可 |
| `general.releaseNotes` / `checkForUpdates` / `pinchZoom` / `showNavigation` / `showTerminal` / `showProjectName` / `showProjectIcon` / `experimentalBrowser` | `general.tsx:342-460`、`experimental.tsx:52-97` | 桌面标题栏 / 更新器 / 内嵌浏览器 | ❌ 平台不适用（Android 无标题栏、无更新器、无内嵌浏览器） |

结论：**D6 的正确产出就是「不加」**。加任何一条都会是死开关；
「实验性功能」页因此保持禁用行（`SETTINGS_NAV_DISABLED` 只剩它一个），
理由写在 `SettingsModel.SETTINGS_NAV` 的 KDoc 里。

---

## 6. D2 Providers 连接：端点在，但闭环还差一个刷新钩子

探明（本机 `http://127.0.0.1:4096`，opencode 1.18.31）：

| 探测 | 结果 | 结论 |
|---|---|---|
| `POST /auth/anthropic` | 200 **SPA HTML** | 不是端点 |
| `PUT /auth/anthropic` body `{"type":"api","key":"…"}` | **200 `true`** | 连接端点**存在** |
| `DELETE /auth/anthropic` | **200 `true`** | 断开端点**存在** |
| `GET /provider/auth` | 200 `{"openai":[{"type":"oauth",…},{"type":"oauth",…}]…}` | OAuth/api 方法元数据存在 |
| `GET /config/providers` | 200 `{"providers":[{id,name,source,env,key}…]}` | 上游来源徽章（api/environment/config）的数据源存在 |
| `GET /plugin` | 200 SPA HTML | 无 plugin 端点（扩展页不画 Plugins 的依据） |

> 披露：探活时用 `PUT /auth/anthropic` 真的写进了一条 `{"type":"api","key":"x"}`。
> 已用 `DELETE /auth/anthropic`（200）删除，随后核对
> `%USERPROFILE%\.local\share\opencode\auth.json` 只剩原有 8 条凭据，anthropic 条目已消失。

**为什么仍然只做只读**：连接/断开之后，Providers 面板的数据来自
`SettingsPanelExtras.providers`（`App.kt:962` → `ChatViewModel.availableProviders`），
而 `ChatViewModel.refreshProviders()`（`ChatViewModel.kt:505`）与 `refreshCatalogs()`（`:542`）
**都是 private**。没有可调用的刷新钩子，用户点完「连接」列表不会变 —— 那就是「改了没用」。
写路径本身很小（`SettingsServerApi` 里加 PUT/DELETE 各 ~15 行，用同一个 OkHttp 通道），
**需要 Lead 先给一个刷新入口**，二选一：

1. 把 `ChatViewModel.refreshProviders()` 改成 `public`（或加 `fun refreshProviderCatalog()`），
   然后 `Chrome`/`ui/App.kt` 把回调经 `SettingsPanelExtras` 传进来；或者
2. 允许 Providers 面板自己用 `SettingsServerApi.providers()` 拉 `GET /provider` 并自持
   StateFlow（会与 `ChatViewModel` 的目录出现两份来源，需要 Lead 拍板）。

在此之前保持现状：面板只读、不画 Connect/Disconnect 按钮（画了必然是空操作）。

---

## 7. 构建与测试

命令：`$env:JAVA_HOME='C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1'; .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`

（数字见文末「验收记录」；并发波次占锁时按 60–150s 重试。）

本轮新增测试（`Wave5SettingsTest`，18 例）覆盖：
导航/页面可达性、Appearance/Notifications 面板内容、`pageDescriptionResId` 映射、
预设检测与「枚举 ↔ 存储表」防漂移、键名归一化、和弦格式化、签名/冲突/覆盖、
键位 JSON 编解码、四个只读页解析（含本机真实 `/project` 载荷）、`SettingsServerApi.endpoint`。

---

## 8. 给 Lead 的接线清单（按优先级）

1. **D4 `globalChord` 接覆盖表**（`ui/App.kt` + `ui/palette/PaletteModel.kt`）：唯一让「录制」真正生效的一步。
2. **UI/code 字体**（`ui/App.kt:188` 包一层 `CompositionLocalProvider(LocalOcFontFamily provides …)`；
   `ui/markdown/MarkdownRenderer.kt` 的 mono）：接上后把 `settings-ui-font` / `settings-code-font`
   从 `HIDDEN_SETTINGS` 移进 `APPEARANCE_PANEL`（行定义已经在 `SettingsRow` 表里留好了位置）。
3. **D2 连接/断开**：先给 `ChatViewModel` 一个公开刷新入口（或批准面板自取目录），再放写路径。
4. **通知音效**：需要原生播放器（`assets/opencode-app/assets/*.aac` 已有素材，可直接打包复用）。
5. **清理**（本轮「只追加」约束下没做）：`oc_setting_tab_workspaces` 已被
   `oc_settings_tab_worktrees` 取代、`oc_shortcut_recording_soon`（"Recording arrives in a later
   milestone"）现在彻底过期 —— 两条字符串资源可以删。

---

## 9. 验收记录

- 命令：
  `$env:JAVA_HOME='C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1'; .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`
- 结果：**BUILD SUCCESSFUL**；`:app:testDebugUnitTest` 汇总
  **93 suites / 906 tests / 0 failures / 0 errors**（基线 86 suites / 827 tests → 只升不降）；
  `:app:assembleDebug` 产出 `app/build/outputs/apk/debug/app-debug.apk`（≈30.9 MB）。
- 本轮新增的 `Wave5SettingsTest` = **18 例，0 失败**；`SettingsStructureTest` / `ShellWiringTest`
  按要求随结构更新后全绿。
- 并发说明（给 Lead 排障用）：本轮多次撞上其它波次的 Gradle 进程锁
  （`Failed to delete some children`、`bundleDebugClassesToRuntimeJar: classes.jar 正被另一个程序使用`），
  按 60–150s 重试后通过；期间还看到 `ui/session/export/SessionExportSaver.kt` 编译失败、
  `SessionExportTest.normalizationCollapsesRunsAndTrimsEdges` 断言失败
  （`expected:<keep_this-and-this.json> but was:<keep_this---and-this.json>`）——
  **都不是本轮文件**，在该波次收尾后已随最终 BUILD SUCCESSFUL 一并转绿。
- 冒烟（手工，非自动化）：本机 `http://127.0.0.1:4096` 上 `GET /project` 返回 30+ 条、
  `GET /skill` 返回 1 条、`GET /mcp` = `{}`、`GET /experimental/worktree` = `[]`，
  与三个只读页的空态/有数据态一一对应。
