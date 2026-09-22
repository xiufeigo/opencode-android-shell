# 只读审计：设置页 / 命令面板 / Providers-Models-Servers 上游差异

审计对象

- Android 原生实现：`app/src/main/java/com/xiufeigo/opencodeshell/**`（下文路径省略该前缀，写作 `ui/settings/...`、`SettingsStore.kt` 等）
- 上游 opencode v2 稠密克隆：`_upstream/packages/app/src/**`（分支 v2），以及被上游引用的 `_upstream/packages/session-ui/src/timeline/detail.ts`

方法：逐条读源码定位消费方；行号均来自本次审计时的工作区快照。**未找到证据的一律写「未确认」，不做推断性结论**（确属「按代码可推导」的写在差异要点里并标注推导依据）。

## 证据范围限制（影响部分结论）

1. `_upstream` 只含 `packages/app`、`packages/schema`、`packages/session-ui`、`packages/theme`、`packages/ui`；**`@opencode/client` 不在 checkout 内，`node_modules` 不存在**。因此上游 `data.location.provider.list()` / `data.location.model.list()`（`providers/catalog/providers.ts:35-38`）背后的**具体 HTTP 路由无法在本仓库取证**。凡涉及「上游用 `/config/providers` 还是别的端点」的结论标 `未确认`。
2. 上游设置持久化是 `settings.v3`（`settings/model.tsx:280`，`persisted` + Effect Schema），不是 localStorage 的 `opencode-theme-id` 那一套；Android `SettingsStore.kt:19-27` 的注释引用 `packages/ui/src/theme/context.tsx:15-16`，该文件在本次 checkout 中**不存在**（`packages/ui` 下无 `src/theme/context.tsx`）→ 该引用 `未确认`。
3. 本仓库根目录的 `research/02_ui_screens.md` / `research/05_palette_host_session.md` 被 Android 代码大量引用为「已核实 DOM」。本次审计以 `_upstream` 真实源码为准，与那些文档冲突处以源码为准。

---

## 表 A「设置」

状态口径

- `1:1`＝控件类型/语义/消费方一致
- `近似`＝有对应 UI，但选项集、默认值或消费方与上游不同
- `缺失`＝无 UI
- 追加「有 UI 但无消费方」= 死设置（详见「附 2」）

### A-1 上游根级导航（`settings/shell.tsx:39-57`、`218-246`）

| 上游项 | 上游证据(file:line) | Android 实现(file:line) | 状态 | 差异要点 |
|---|---|---|---|---|
| 根 tab `general`（Preferences） | `settings/shell.tsx:40`、`273-275` | `ui/settings/SettingsModel.kt:18`、`SettingsScreen.kt:134` | 近似 | 上游 General 只放「偏好」；Android 把 Appearance/Notifications/Sounds/Advanced 全塞进 General，见 A-2 |
| 根 tab `appearance` | `settings/shell.tsx:41`、`276-278` | 无独立 tab；内容并入 General 的 Appearance 段（`SettingsModel.kt:172-207`） | 近似 | 少一个顶层 tab，功能未丢但信息架构不同 |
| 根 tab `notifications` | `settings/shell.tsx:42`、`279-281` | 无独立 tab；并入 General（`SettingsModel.kt:208-251`） | 近似 | 同上 |
| 根 tab `shortcuts` | `settings/shell.tsx:43`、`282-284` | `SettingsTab.SHORTCUTS`（`SettingsModel.kt:19`）、`SettingsPanels.kt:113-133` | 近似 | 只读，见 A-6 |
| 根 tab `experimental` | `settings/shell.tsx:55`、`285-287` | 无 | 缺失 | tab layout / browser pane / show project name / show project icon 全缺 |
| 根 tab `about` | `settings/shell.tsx:56`、`288-290` | 无；只在左侧导航底部显示 `app.name` + `v{version}`（`SettingsScreen.kt:256-273`） | 近似 | 无 About 页/贡献者/许可 |
| server 组 tab `projects` | `settings/shell.tsx:47`、`294-304` | 无 | 缺失 | — |
| server 组 tab `workspaces` | `settings/shell.tsx:48`、`305-310` | 无 | 缺失 | — |
| server 组 tab `providers` | `settings/shell.tsx:49`、`311-313` | `SettingsTab.PROVIDERS`（`SettingsModel.kt:21`）、`SettingsPanels.kt:137-168` | 缺失（空壳） | 面板存在但永远展示 empty，见 C-1 |
| server 组 tab `models` | `settings/shell.tsx:50`、`314-316` | `SettingsTab.MODELS`（`SettingsModel.kt:22`）、`SettingsPanels.kt:172-194` | 缺失（空壳） | 同上，见 C-1 |
| server 组 tab `extensions` | `settings/shell.tsx:51`、`317-319`；`settings/providers/extensions.tsx:31-60` | 无 | 缺失 | MCP 开关 / 插件列表 / skills 全缺 |
| server 组 tab `servers` | `settings/shell.tsx:241`、`325-328` | `SettingsTab.SERVERS`（`SettingsModel.kt:20`）、`ui/settings/ServersPanel.kt:48-126` | 近似 | 只支持 http；无 SSH/WSL，见 C-4 |
| 多服务器时的服务器列表导航组 | `settings/shell.tsx:220-232`、`248-253` | 无（`SETTINGS_NAV` 固定 5 项，`SettingsModel.kt:43-49`） | 缺失 | 上游多服务器时把每个 server 变成一级导航项；Android 只有 Servers tab 里的列表 |
| 嵌套 server 页（ServerSettings，`nestedServerTabs`） | `settings/shell.tsx:59-62`、`334-407` | 无 | 缺失 | 上游每个服务器有独立设置页（general/projects/workspaces/providers/models/extensions） |
| 嵌套 project 页（ProjectSettings） | `settings/shell.tsx:64-68`、`409-455` | 无 | 缺失 | 项目级设置（名称/图标/颜色/workspaces/extensions） |
| 导航分组标题「Desktop / Server」 | 上游第一组**无 label**（`shell.tsx:219`），第二组 label 仅多服务器时出现（`shell.tsx:223`） | `SettingsModel.kt:43-49` 硬编码 `oc_setting_section_desktop` / `oc_setting_section_server` | 近似 | Android 多了两个分组标题；上游 root 组没有标题 |
| 设置页搜索（含 `mod+f`、结果跳转） | `settings/navigation.tsx:104-106`、`settings/search.tsx:50-61`、`settings/search-catalog.ts` | 无 | 缺失 | Android 设置页没有搜索框、没有 `settings.search.focus` 命令 |
| `<639dp` 顶部横向 tab 条 | `settings/navigation.tsx:51-97`（back + 下拉 Menu） | `SettingsScreen.kt:205-223` 横向滚动条（无返回按钮、无下拉菜单） | 近似 | Android 无「Back to app」按钮 |
| `settings-v2-nav-footer`（app 名 + 版本） | 上游**不存在**：全 `_upstream/packages` 内 grep `nav-footer` 无命中，`settings/settings.css` 无该类名，`settings/*.tsx` 内也无 app 名/版本脚注（版本只在 About 页，`settings/about/about.tsx:42`） | `SettingsScreen.kt:256-273`（`oc_settings_app_name` + `BuildConfig.VERSION_NAME`） | 上游缺失（Android 独有） | 这是 Android 沿用旧 vendored DOM（`research/02` §5.3）的产物，当前 v2 上游设置导航没有 footer；`oc_settings_app_name` = "OpenCode Desktop"（`res/values/strings.xml:120`） |

### A-2 General（Preferences）tab 内逐条

| 上游项 | 上游证据(file:line) | Android 实现(file:line) | 状态 | 差异要点 |
|---|---|---|---|---|
| Language 下拉 | `settings/general/general.tsx:270-295`（`data-action=settings-language`） | `SettingsModel.kt:128-137`；写 `SettingsStore.kt:156`；消费 `SettingsStore.kt:59-77` | 近似 | 上游选项来自 `language.locales`（数十种，`runtime/i18n/*`）；Android 只有 en/zh/zht（`SettingsStore.kt:243`）。上游改的是 Web UI 语言，Android 只改原生 `stringResource`，**WebView 内容语言不变** |
| Workspace default destination 下拉 | `general.tsx:71-97`（`settings-workspace-destination`） | 无 | 缺失 | — |
| Auto-accept permissions 开关 | `general.tsx:53-69`（`settings-auto-accept-permissions`）；默认 `model.tsx:259` | `SettingsModel.kt:138-142`；消费 `ChatViewModel.kt:134-140` + `AutoApprover.kt:22-52` | 1:1 | 唯一真正生效的开关之一；上游还有 `mod+shift+a` 命令可切换（`use-session-commands.tsx:441-451`），Android 无 |
| Show agent（showCustomAgents）开关 | `general.tsx:327-337`（`settings-show-custom-agents`） | `SettingsModel.kt:270-274` | 有 UI 但无消费方 | composer 没有 agent 选择器（`ui/session/composer/` 下无 agent chip），见附 2 |
| Terminal placement 下拉 | `general.tsx:130-155`（`settings-terminal-placement`） | 无 | 缺失 | — |
| Follow-up behavior 下拉（queue/steer） | `general.tsx:157-184`（`settings-follow-up-behavior`） | 无 | 缺失 | — |
| Pinch zoom 开关（desktop） | `general.tsx:342-351` | 无 | 缺失（平台不适用） | — |
| Wrap lines（mobileDiffWrap）开关 | `general.tsx:353-367`（`settings-mobile-diff-wrap`） | 无 | 缺失 | 移动端高相关，Android 没有 diff 换行开关 |
| Mobile titlebar bottom 开关 | `general.tsx:369-381`（`settings-mobile-titlebar-bottom`，仅 mobile 且非 prod） | 无 UI；`ui/nav/AppearancePrefs.kt:14-31` 只**读** `appearance.tabLayout`，没人写 | 缺失 | `bottomTabStrip` 由 `OcActivity.kt:62` 传入，值永远来自一个无人写入的 prefs key |
| Timeline detail 预设控件 | `general.tsx:480-490`（`TimelineDetailControl`）；预设定义 `session-ui/src/timeline/detail.ts:17-73` | 无；只有两个布尔开关 `SettingsModel.kt:155-169` | 近似（降级） | 上游是 6 类别 × placement/details 的预设选择器（everything/detailed/compact/quiet/text-only），Android 把 `showReasoningSummaries`/`shellToolPartsExpanded`/`editToolPartsExpanded` 这三个 v1 遗留键做成开关，且**这三个键在上游只是 migration 输入**（`settings/model.tsx:207-232`） |
| Release notes 开关（Updates，desktop） | `general.tsx:435-445` | 无 | 缺失 | — |
| Check for updates 按钮 | `general.tsx:447-460` | 无 | 缺失 | — |

### A-3 Appearance（上游独立 tab / Android General 段）

| 上游项 | 上游证据(file:line) | Android 实现(file:line) | 状态 | 差异要点 |
|---|---|---|---|---|
| Color scheme（system/light/dark） | `settings/appearance/appearance.tsx:82-99`（`settings-color-scheme`）；默认经 `@opencode/ui/theme/context`（`未确认` 默认值来源） | `SettingsModel.kt:175-184`；写 `SettingsStore.kt:151`；消费 `ui/App.kt:152-156`（`Settings.isDark`，`SettingsStore.kt:109-113`） | 1:1 | 生效 |
| Theme（多主题下拉） | `appearance.tsx:101-122`（`settings-theme`，`theme.ids()` 动态列表 + `loadThemes()`，`general/controllers.ts:71-87`） | `SettingsModel.kt:185-190` 写死单选项 `oc-2` | 有 UI 但无消费方 | `themeId` 全仓库仅被读来显示，见附 2 |
| UI Font 文本框 | `appearance.tsx:124`（`settings-ui-font`） | `SettingsModel.kt:191-195` | 有 UI 但无消费方 | `ui/theme/OcTheme.kt:303-307,323-347` 的 `LocalOcFontFamily` 只提供 `OcFonts.default`，`App.kt:156` 调用 `OcTheme(isDark=...)` 未传字体 |
| Code Font 文本框 | `appearance.tsx:125`（`settings-code-font`） | `SettingsModel.kt:196-200` | 有 UI 但无消费方 | 代码块固定 `FontFamily.Monospace`（`ui/markdown/MarkdownRenderer.kt:281,410`） |
| Terminal Font 文本框 | `appearance.tsx:126`（`settings-terminal-font`） | `SettingsModel.kt:201-205` | 有 UI 但无消费方 | `ui/terminal/TerminalPanel.kt:104-108` 有 `terminalFont` 参数，但唯一调用点 `ui/App.kt:351-355` 没传；终端实际用 `resolveTerminalTypeface(null)`（`TerminalPanel.kt:686-687`） |
| 字体名 placeholder（Inter / IBM Plex Mono / JetBrainsMono Nerd Font Mono） | `settings/model.tsx:19-21`、`general/controllers.ts:89-103` | `SettingsModel.kt:77` 统一用 `oc_setting_font_default`（"Default"） | 近似 | placeholder 文案不同 |
| Appearance tab 头部副标题 | `appearance.tsx:74` | 无 | 缺失 | Android `SettingsPanelHeader` 无副标题（`SettingsScreen.kt:277-302`） |

### A-4 Notifications / Sounds（上游独立 tab / Android General 段）

| 上游项 | 上游证据(file:line) | Android 实现(file:line) | 状态 | 差异要点 |
|---|---|---|---|---|
| Notifications: agent 开关 | `notifications/notifications.tsx:78-88`；默认 true（`model.tsx:261`） | `SettingsModel.kt:211-215` | 有 UI 但无消费方 | 无任何通知发送方，见附 2 |
| Notifications: permissions 开关 | `notifications.tsx:90-100`；默认 true | `SettingsModel.kt:216-220` | 有 UI 但无消费方 | 同上 |
| Notifications: errors 开关 | `notifications.tsx:102-112`；默认 **false**（`model.tsx:261`） | `SettingsModel.kt:221-225`；默认 **true**（`SettingsStore.kt:130`） | 有 UI 但无消费方 + 默认值不一致 | 默认值相反 |
| Sounds: agent 下拉 | `notifications.tsx:119` + `general/controllers.ts:111-112`（`soundOptions = none + SOUND_OPTIONS`）、`shell/notifications/sound.ts` 的 `playSoundById` | `SettingsModel.kt:232-237`，选项仅 none/default（`SettingsModel.kt:111-114`） | 有 UI 但无消费方 | 上游每个通道是「enable 布尔 + 具体音效 id」两位（`model.tsx:262-269`，默认 `staplebops-01/02`、`nope-03`），并把「none」编码为 `setEnabled(false)`（`controllers.ts:131-141`）；Android 只有一个 `none|default` 字符串，且无播放器 |
| Sounds: permissions / errors 下拉 | `notifications.tsx:120-121` | `SettingsModel.kt:238-249` | 有 UI 但无消费方 | 同上 |
| 音效试听（highlight 预览、100ms 去抖） | `general/behavior.ts:40-70`、`controllers.ts:127-130` | 无 | 缺失 | 下拉打开时试听 |
| Notifications tab 副标题 | `notifications.tsx:67-69` | 无 | 缺失 | — |

### A-5 Advanced（Android 独有段）

| 上游项 | 上游证据(file:line) | Android 实现(file:line) | 状态 | 差异要点 |
|---|---|---|---|---|
| File tree 开关（`showFileTree`） | 上游只有存储键与默认值 **false**（`settings/model.tsx:243`），**v2 设置页没有任何 UI 行**（全 `_upstream/packages/app/src` grep `settings-show-file-tree` 无命中；`general.tsx` 无该行）；消费方是 `visibility.fileTree`（`model.tsx:386-390`）→ `useSessionCommands` 的 `shown()`（`use-session-commands.tsx:60,375`）与 `fileTree.toggle` 命令 | `SettingsModel.kt:255-259`；默认 **true**（`SettingsStore.kt:134`） | 有 UI 但无消费方 + 默认值不一致 | Android 造了一个上游没有的行，且没有 file tree 面板可显示/隐藏 |
| Command palette 开关（`showSearch`） | 同样只有键与默认 **false**（`model.tsx:244`），v2 无 UI 行（grep `settings-show-search` 无命中） | `SettingsModel.kt:260-264`；默认 **true**（`SettingsStore.kt:135`） | 有 UI 但无消费方 + 默认值不一致 | 上游该键控制标题栏搜索按钮的可见性（`visibility.search`，`model.tsx:388`），不控制 `mod+k` 面板 |
| Server status 开关（`showStatus`） | **上游连键都没有**（`settings/model.tsx:76-98` 的 `generalSchema` 无 `showStatus`；grep 全仓库无命中） | `SettingsModel.kt:265-269`；默认 true（`SettingsStore.kt:136`） | 上游缺失（Android 独有）且无消费方 | Android 自造项 |
| Show agent（`showCustomAgents`，同 A-2） | `general.tsx:327-337`；默认 **false**（`model.tsx:249`） | `SettingsModel.kt:270-274`；默认 **true**（`SettingsStore.kt:137`） | 有 UI 但无消费方 + 默认值不一致 | — |

### A-6 Shortcuts（Keybinds）

| 上游项 | 上游证据(file:line) | Android 实现(file:line) | 状态 | 差异要点 |
|---|---|---|---|---|
| 分组集合与顺序（General/Session/Navigation/Model and agent/Terminal/Prompt） | `settings/keybinds/keybinds.tsx:36` | `SettingsModel.kt:288-300` | 1:1 | 名称一致 |
| `groupFor(id)` 前缀规则 | `keybinds.tsx:55-71` | `SettingsModel.kt:317-325` | 近似 | 上游额外有 `id === "command.palette" → General` 特判（`keybinds.tsx:56`）；Android 靠 `command.palette` 前缀不匹配任何规则落到 General，结果相同 |
| 行来源＝运行时命令注册表（`command.catalog` + `command.options`） | `keybinds.tsx:120-143` | 硬编码 6 行常量 `SHORTCUT_ROWS`（`SettingsModel.kt:334-341`） | 近似（大幅缩水） | 上游列出**所有**已注册命令（几十条）；Android 只列 6 条 |
| 记录/重绑定（点击 → 录键 → 冲突检测 → 落盘 `settings.keybinds`） | `keybinds.tsx:248-308`（`recordKeybind`、`signatures`、conflicts） | 无；`KeybindChip` 只显示文本（`SettingsPanels.kt:124-127`） | 缺失 | Android 文案自称「Recording arrives in a later milestone」（`res/values/strings.xml:232`） |
| Backspace/Delete = 置 `"none"` 清除绑定 | `keybinds.tsx:272-282` | 无 | 缺失 | — |
| Reset all（`hasOverrides` 时可用） | `keybinds.tsx:406-408`、`331-338` | 无 | 缺失 | — |
| 快捷键搜索框（fuzzysort title+keybind） | `keybinds.tsx:410-434`、`164-195` | 无 | 缺失 | — |
| `formatKeybind` 平台化显示（Mac 用 ⌘/⇧） | `shell/commands/command.tsx:206-261` | 硬编码 `"Ctrl+K / Ctrl+Shift+P"` 等字符串（`SettingsModel.kt:335-340`） | 近似 | Android 恒用 Ctrl 文案 |
| 每行 keybind 来自真实生效值（`effective(id)`，含用户覆盖） | `keybinds.tsx:217-226` | 硬编码常量，与 `AppCommands.kt` 的 keybind 字段**各自独立** | 近似 | 两处可漂移：`SHORTCUT_ROWS` 里 `session.new=Ctrl+N`（`SettingsModel.kt:338`），`AppCommands.kt:40` 同样写 `Ctrl+N`，但上游 `session.new` 是 `mod+shift+s`（`use-session-commands.tsx:252`），`Ctrl+N` 实际属于隐藏命令 `tab.new`（`titlebar.tsx:393`） |

---

## 表 B「命令面板」

### B-1 上游命令注册表全量（按注册点）

上游 id 是「最后一次注册生效」且同一 id 可被不同路由覆盖（`shell/commands/command.tsx:292-311` `registered()` 去重保首个；`:430-447` `register`）。`file.open` 被显式排除出面板（`:99-104` `commandPaletteOptions`），`hidden: true` 也不显示。

**面板分组（`category`）的赋值方式**：各注册文件用 `withCategory(...)` 批量打标，面板按 `entry.category` 分组（`shell/commands/dialog.tsx:19-23`）。映射（`session/commands/use-session-commands.tsx:89-96`）：`command.category.session|project|file|context|view|terminal|mcp|permissions`；`composer/commands.tsx:24-25`：`command.category.model|agent`；`new-session/commands.tsx:35,42,50`：view/project/workspace；`settings/command.tsx:20`：Settings。**同一条命令的「分组」因此取决于它的 category 而非 id 前缀**（两者在多数情况下一致，例如 `terminal.*`→Terminal，但 `message.previous/next` 用的是 session category，`B-1` 表内以注册点标注为准）。`session.*`/`message.*` 这类还有个后果：`keybinds.tsx:55-71` 的 `groupFor` 与 `category` 是**两套独立分组**，设置页快捷键列表按前者、命令面板按后者。

| # | 上游 id | 上游标题(i18n key) | keybind | 分组/注册点 | Android 实现(file:line) | 状态 | 差异要点 |
|---|---|---|---|---|---|---|---|
| 1 | `settings.open` | `command.settings.open` | `mod+comma` | `settings/command.tsx:16-24`（category=Settings） | `ui/palette/AppCommands.kt:31-36` | 1:1（可执行） | 打开设置 dialog；唯一面板里能真正执行的非终端命令 |
| 2 | `session.new` | `command.session.new` | `mod+shift+s` | `use-session-commands.tsx:249-255`（slash `new`）→ 触发 `tab.new` | `AppCommands.kt:37-42`（keybind 写成 `Ctrl+N`） | 占位不可用 | `onSelect = {}`（`:41`）；keybind 与上游不符；`Ctrl+N` 也未接入 `globalChord`（`ui/App.kt:452-466` 只识别 palette/settings 两个和弦）→ **点了没反应，键盘也按不出来** |
| 3 | `session.undo` | `command.session.undo` | — | `use-session-commands.tsx:256-263` | 无 | 缺失 | Android 无 revert |
| 4 | `session.redo` | `command.session.redo` | — | `:264-271` | 无 | 缺失 | — |
| 5 | `session.compact` | `command.session.compact` | — | `:272-279` | 无 | 缺失 | 但 `OpenCodeClient` 无 `compact` 端点调用（`未确认` 是否存在于 v1 API） |
| 6 | `session.background` | `command.session.background` | `ctrl+b` | `:280-286` | 无 | 缺失 | — |
| 7 | `session.fork` | `command.session.fork` | — | `:287-294` | 无 | 缺失 | — |
| 8 | `session.export` | `command.session.export` | — | `:295-302` | 无 | 缺失 | — |
| 9 | `session.copyID` | `command.session.copyID` | — | `:303-308` | 无 | 缺失 | — |
| 10 | `file.open` | `command.file.open` | `mod+p` | `:314-321`（面板中隐藏） | 无（面板里有 `file:` 行，但没有 `file.open` 命令） | 缺失 | 上游 `file.open` 是 `DialogSelectFile` 入口；Android 只能靠输入关键字触发 `/find/file` |
| 11 | `file.close` | `command.tab.close` | `mod+w` | `:322-329` | 无 | 缺失 | — |
| 12 | `project.copyID` | `command.project.copyID` | — | `:333-340` | 无 | 缺失 | — |
| 13 | `context.addSelection` | `command.context.addSelection` | `mod+shift+l` | `:342-351` | 无 | 缺失 | — |
| 14 | `terminal.toggle` | `command.terminal.toggle` | ctrl+反引号（backtick） | `:354-368` | `AppCommands.kt:58-63` → `ui/App.kt:241,351-356` | 近似（可执行） | 面板里能打开终端路由；但**不是 toggle**（再次选中会再 push 一个 terminal 路由实例）。该键未接入 `globalChord`，Android 只能靠 session 页的 `TerminalEntryButton`（`ui/App.kt:332-337`）或面板本身进入 |
| 15 | `review.toggle` | `command.review.toggle` | `mod+shift+r` | `:369-374` | `AppCommands.kt:64-68` | 占位不可用 | `onSelect = {}`；Android review 面板由 `SessionScreen.kt:281-287` 的 `ReviewToggleButton` 控制，未接命令 |
| 16 | `fileTree.toggle` | `command.fileTree.toggle` | `mod+\` | `:375-384`（仅 `showFileTree` 为真时注册） | 无 | 缺失 | — |
| 17 | `input.focus` | `command.input.focus` | `ctrl+l` | `:385-390`；`new-session/commands.tsx:32-38` | 无 | 缺失 | — |
| 18 | `terminal.close` | `terminal.close` | `mod+w` | `:393-401`（hidden） | 无 | 缺失 | — |
| 19 | `terminal.new` | `command.terminal.new` | `ctrl+alt+t` | `:402-408` | 无 | 缺失 | Android `ui/terminal/` 无「新建 tab」命令入口 |
| 20 | `message.previous` | `command.message.previous` | `mod+alt+[` | `:411-419` | 无 | 缺失 | — |
| 21 | `message.next` | `command.message.next` | `mod+alt+]` | `:420-427` | 无 | 缺失 | — |
| 22 | `mcp.toggle` | `command.mcp.toggle` | `mod+;` | `:430-439` | 无 | 缺失 | — |
| 23 | `permissions.autoaccept` | `command.permissions.autoaccept.enable/disable` | `mod+shift+a` | `:441-451` | 无 | 缺失 | Android 只能去设置页改开关 |
| 24 | `model.choose` | `command.model.choose` | `mod+'` | `composer/commands.tsx:50-57`（slash `model`） | 无 | 缺失 | Android 模型选择只在 composer chip（`ui/session/composer/ModelChip.kt:86-180`），无命令/无 slash |
| 25 | `model.variant.cycle` | `command.model.variant.cycle` | `shift+mod+d` | `composer/commands.tsx:58-64` | 无 | 缺失 | Android 无 variant 概念 |
| 26 | `agent.cycle` | `command.agent.cycle` | `mod+.` | `composer/commands.tsx:65-73` | 无 | 缺失 | — |
| 27 | `agent.cycle.reverse` | `command.agent.cycle.reverse` | `shift+mod+.` | `composer/commands.tsx:74-81` | 无 | 缺失 | — |
| 28 | `file.attach` | `prompt.action.attachFile` | `mod+u` | `composer/model.ts:410-418` | 无命令（有 UI：`ui/session/composer/AttachmentPicker.kt`） | 缺失 | 只能点按钮 |
| 29 | `prompt.mode.shell` | `command.prompt.mode.shell` | `mod+shift+x` | `composer/model.ts:419-426` | 无 | 缺失 | — |
| 30 | `prompt.mode.normal` | `command.prompt.mode.normal` | `mod+shift+e` | `composer/model.ts:427-434` | 无 | 缺失 | — |
| 31 | `project.select` | `session.new.project.search` | `mod+shift+o` | `new-session/commands.tsx:39-46` | 无 | 缺失 | — |
| 32 | `session.location.cycle` | `command.session.location.cycle` | `mod+alt+l` | `new-session/commands.tsx:47-54` | 无 | 缺失 | — |
| 33 | `command.palette` | `command.palette` | —（`hidden`） | `home/sessions/controller.tsx:109-139`；`session/composer/region.tsx:167-174`；`new-session/commands.tsx:24-31` | `PaletteModel.kt:104`（仅常量 `PALETTE_ID`） | 近似 | 面板本身由 `globalChord` 直接打开（`ui/App.kt:418-430`），没有注册成可被 slash/菜单触发的命令 |
| 34 | `settings.search.focus` | `settings.search.placeholder` | `mod+f` | `settings/search.tsx:50-61`（hidden） | 无 | 缺失 | — |
| 35 | `home.sessions.search.focus` | 占位符文案 | `mod+f` | `home/sessions/search.ts:65-73`（hidden） | 无 | 缺失 | Android Home 有搜索 UI（`ui/home/HomeSessionsList.kt`）但无快捷键 |
| 36 | `home.toggle` | `home.title` | `mod+b`（Win: `alt+home`） | `titlebar.tsx:374-383`（hidden） | 无 | 缺失 | — |
| 37 | `tab.new` | `command.session.new` | `mod+t,mod+n` | `titlebar.tsx:388-396`（hidden） | 无 | 缺失 | — |
| 38 | `tab.close` | `command.tab.close` | `mod+w` | `titlebar.tsx:397-406`（hidden） | 无 | 缺失 | Android 会话 tab 关闭在 `BottomTabStrip`（`ui/App.kt:373-376`），无命令 |
| 39 | `tab.reopenClosed` | `command.tab.reopenClosed` | `mod+shift+t` | `titlebar.tsx:407-413` | 无 | 缺失 | — |
| 40 | `tab.prev` | 无标题 | `mod+option+ArrowLeft,ctrl+shift+tab` | `tab-strip.tsx:251-259`（hidden） | 无 | 缺失 | — |
| 41 | `tab.next` | 无标题 | `mod+option+ArrowRight,ctrl+tab` | `tab-strip.tsx:260-267`（hidden） | 无 | 缺失 | — |
| 42 | `common.goBack` | `common.goBack` | `mod+[` | `titlebar.tsx:131-138` | 无 | 缺失 | Android 返回靠系统返回/header 箭头 |
| 43 | `common.goForward` | `common.goForward` | `mod+]` | `titlebar.tsx:139-145` | 无 | 缺失 | — |
| 44 | `debugBar.toggle` | `command.debugBar.toggle` | — | `shell/shell.tsx:49-56` | 无 | 缺失 | — |
| 45 | `app.checkForUpdates` | `desktop.menu.checkForUpdates` | — | `shell/commands/desktop.tsx:17-23`（hidden） | 无 | 缺失（平台） | — |
| 46 | `server.ssh.add` | `ssh.add` | — | `desktop.tsx:24-30` | 无 | 缺失 | 见 C-4 |
| 47 | `logs.export` | `command.logs.export` | — | `desktop.tsx:31-37` | 无 | 缺失（平台） | — |
| 48 | `browser.open` | `command.browser.open` | `mod+shift+b` | `session/browser/model.ts:56-65` | 无 | 缺失 | Android 无内嵌浏览器 |
| 49 | `browser.reload` | `command.browser.reload` | `f5` | `session/browser/pane.tsx:45-57` | 无 | 缺失 | — |
| 50 | `session.summary.toggle` | `command.session.summary.toggle` | `mod+shift+y` | `session/summary/popover.tsx:17-27` | 无 | 缺失 | — |
| 51 | `session.search` | `session.search.placeholder` | `mod+f` | `session/timeline/search-controller.ts:184-192`（hidden） | 无 | 缺失 | Android 无时间线搜索 |
| 52 | `session.previous` | `command.session.previous` | —（仅桌面菜单 `Option+Up`） | `COMMON_COMMAND_IDS`（`shell/commands/palette.ts:41`）+ `desktop-menu.ts:245`，**本 checkout 内无任何 `command.register` 提供它** | `AppCommands.kt:48-52` | 占位不可用（上游也缺注册） | 上游它只出现在 `COMMON_COMMAND_IDS` 与菜单引用里；Android 把它做成可见空操作行 |
| 53 | `session.next` | `command.session.next` | —（仅桌面菜单 `Option+Down`） | `palette.ts:42` + `desktop-menu.ts:251`，无注册 | `AppCommands.kt:53-57` | 占位不可用 | 同上 |
| 54 | `workspace.new` | `workspace.new`（"New worktree"） | — | `palette.ts:40`，无注册 | `AppCommands.kt:43-47` | 占位不可用 | 同上 |
| 55 | `project.previous` / `project.next` | `desktop.menu.previousProject/nextProject` | `Cmd+Option+Up/Down` | `desktop-menu.ts:255-266`，无注册 | 无 | 缺失 | — |

计数：上游注册点共 **51** 条（不含仅在 `COMMON_COMMAND_IDS`/菜单里出现的 3 条未注册 id）；Android `appCommands()` 共 **7** 条。

### B-2 Android 面板行为

| 行为 | 上游证据(file:line) | Android 实现(file:line) | 状态 | 差异要点 |
|---|---|---|---|---|
| 面板打开方式 | 全局 keydown 捕获（`command.tsx:401-424`）→ `dialog.push` | `globalChord` → `dialog.show(DialogSize.LARGE)`（`ui/App.kt:415-439,559-572`） | 近似 | Android 用 Compose key event，需先拿到焦点（`App.kt:407-408` best-effort `requestFocus`） |
| 打开和弦 | `DEFAULT_PALETTE_KEYBIND = "mod+k,mod+shift+p"`（`command.tsx:16`），`matchKeybind` 要求 ctrl/meta/shift **全部严格相等**（`command.tsx:188-204`）→ `mod+k` 必须是**未按 Shift** 的 K | `isPaletteChord`（`PaletteModel.kt:282-287`）：`mod+K` → true（不看 shift），`mod+P` → 仅 shift | 近似 | Android 的 `mod+shift+K` 也会打开面板（上游不会）。`mod+shift+p`、`mod+,` 一致 |
| 设置和弦 | `settings.open = mod+comma`（`settings/command.tsx:21`） | `isSettingsChord`（`PaletteModel.kt:290`） | 1:1 | — |
| 选中命令的副作用 | `palette.ts:163-188`：先 `dialog.close()`，再 `option.onSelect("palette")` | `ui/App.kt:536-547`：`dialog.close()` 后按 `entry.type` 分发 | 近似 | Android 分发到 `commands.firstOrNull{entryId}.onSelect`（`:545`） |
| 选中 file 行的副作用 | `palette.ts:186-187` → `openFile()`：打开 session tab + `file.load` + 打开 review panel + fileTree tab=all（`palette.ts:66-80`） | `ui/App.kt:543`：**空（只关闭面板）**，代码注释标注 TODO | 缺失 | 上游选文件会真正打开文件；Android 点了没反应 |
| 选中 session 行的副作用 | `palette.ts:172-185`：`projects.open/touch` + `appTabs.addSessionTab` + `select` | `ui/App.kt:539` → `navController.navigate` | 近似 | 不更新 projects 列表、不 touch 最近项目 |
| 空查询内容（session 宿主） | `dialog.tsx:32-35`：`preferredCommandEntries()` + `recentFileEntries()` | `PaletteModel.kt:165-170`、`ui/App.kt:526-529` | 近似 | Android `recentFiles` **恒为空**（`App.kt:527` 注释承认没有打开文件数据源）→ 空查询只有命令 5 行 |
| `COMMON_COMMAND_IDS` 顺序 | `palette.ts:38-45` | `PaletteModel.kt:91-98` | 1:1 | 字面一致 |
| `ENTRY_LIMIT = 5` | `palette.ts:37` | `PaletteModel.kt:88` | 1:1 | — |
| `preferredCommandEntries` 回退 | `palette.ts:104-112`：命中的 id 排序，**未命中任何 id 时取前 5 条已注册命令** | `PaletteModel.kt:153-157`：同样逻辑 | 1:1（逻辑） | 但表现不同：上游因 `workspace.new/session.previous/session.next` 无注册，命中集合实际只有 `session.new`、`terminal.toggle`、`review.toggle`（推导依据：`palette.ts:107` 的 `order.has` 过滤 + 上文注册表）→ 上游空查询≈3 条命令 + 最近文件；Android 因自己「补注册」了那 3 条空命令，空查询恒为 5 条**其中 4 条无效** |
| 分组名（Commands/Files/Session） | `palette.group.commands`（`palette.ts:101`）、`command.category.session`（`palette.ts:153`） | `PaletteModel.kt:79-85` | 1:1 | — |
| 匹配规则 | `matchesCommandPaletteEntry`：`[title, description, category].some(contains)`（`dialog.tsx:25-28`） | `matchesEntry`（`PaletteModel.kt:127-133`） | 1:1 | 都是 lowercase contains，无模糊排序 |
| 会话搜索延迟 100ms + 去抖 + 精确 ID 查询 | `palette.ts:234-258`（`looksLikeSessionID` 时额外 `session.get`） | `ui/App.kt:506-511`（直接 `listSessions`，**无 100ms 去抖、无精确 ID 兜底**） | 近似 | Android 一次拉全部会话本地过滤；无「输入 session id 直达」 |
| 文件搜索去抖 | `command.tsx` 无去抖（异步 source + signal 取消，`palette.ts`/`dialog.tsx:43-48`） | `ui/App.kt:514-524` 200ms 去抖 + 迟到结果丢弃 | 近似 | 参数不同（上游靠 AbortSignal） |
| 键盘：Arrow/Enter/Esc | `dialog.tsx:102-122` | `CommandPalette.kt:122-152` | 1:1 | 循环移动、Esc 关闭一致 |
| 悬停不改变选中（防抖） | `dialog.tsx:203-207`（`movementX/Y===0` 忽略） | 无（`PaletteRow` 只有 click，`CommandPalette.kt:286-289`） | 缺失 | Android 没有 hover-highlight 概念 |
| 会话行的「已打开」标记 | `dialog.tsx:76-78,162-166,238-244`（`openSessions()` 集合，来自 `tabs.store`） | `PaletteModel.kt:251-263`（`openSessionIds` 参数）但 `ui/App.kt:509` 调用时**未传** → 该行永不显示 | 近似（半失效） | 参数存在、调用点遗漏 |
| 会话行相对时间 meta | `dialog.tsx:267-271`（`getRelativeTime`） | `PaletteEntry.meta` 字段存在（`PaletteModel.kt:30`），`ui/App.kt:509`/`sessionPaletteEntries` 从不填 | 缺失 | Android 会话行无时间 |
| 命令行的 slash 支持 | `CommandOption.slash`（`command.tsx:90`）+ composer slash 菜单 | `PaletteCommand` 无 slash 字段（`PaletteModel.kt:298-310`） | 缺失 | Android composer 无 `/` 命令菜单 |
| keybind 渲染为按键胶囊 | `Keybind keys={formatKeybindParts(...)}`（`dialog.tsx:231-233`） | 纯文本 `entry.keybind`（`CommandPalette.kt:356-363`） | 近似 | 视觉差异 |

### B-3 宿主（Home vs Session）展示策略

| 维度 | 上游 | Android 实现(file:line) | 状态 | 差异要点 |
|---|---|---|---|---|
| 宿主种类 | 两个组件：`DialogHomeCommandPaletteV2`（Home，`home/sessions/command-palette.tsx`）与 `DialogCommandPalette`（session/new-session，`shell/commands/dialog.tsx:30-55`） | 枚举 `PaletteHostKind.HOME/SESSION`（`PaletteModel.kt:202-208`），但 `PaletteDialogLayer` **只被以 `PaletteHostKind.SESSION` 调用**（`ui/App.kt:422`） | 缺失一半 | `PaletteHostKind.HOME` 分支全仓库无调用点 → `ui/palette/PaletteModel.kt:226` 与 `res/values/strings.xml:242`（`oc_palette_placeholder_home`）成为死代码/死资源 |
| Home 空查询 | `commandEntries().slice(0,5)`，**不含最近文件**（`home/sessions/command-palette.tsx:55-58`） | 走 SESSION 分支：`COMMON_COMMAND_IDS` 前 5 + 最近文件（空） | 近似 | Android 在 Home 上显示的是 session 版内容 |
| Home 有查询 | 命令 + 异步 sessions（`command-palette.tsx:69` `sources=[sessions]`），**无 files** | SESSION 分支会额外命中 `/find/file` 的 file 行（`ui/App.kt:514-524`） | 近似 | Android Home 会搜文件，上游 Home 不会 |
| 占位文案 | `palette.search.placeholder` / `palette.search.placeholder.home`（`command-palette.tsx:67`、`dialog.tsx:39`） | `PaletteModel.kt:210-214` | 近似 | Android 同一宿主 → 永远用 `oc_palette_placeholder`（"Search files, commands, and sessions"），home 文案从未显示 |
| 会话行点击 | Home：`onSelectSession` → `ctx.projects.open/touch` + `addSessionTab` + `select`（`home/sessions/controller.tsx:123-134`）；Session：同 `palette.ts:172-185` | 统一 `navController.navigate`（`ui/App.kt:539`） | 近似 | — |
| session 宿主把 `command.palette` 重定向到 `file.open` | `session/composer/region.tsx:167-174`（palette 打开的是选文件对话框） | 无（`file.open` 不存在） | 缺失 | Android session 宿主直接打开命令面板本体 |
| 打开入口 | 命令 `command.palette`（hidden）+ 标题栏按钮 + `mod+k` | 仅 `mod+k`/`mod+shift+p`（`ui/App.kt:418-430`）+ Home 无按钮 | 近似 | Android 无标题栏搜索按钮（`showSearch` 死设置，见附 2） |

---

## 表 C「Providers / Models / 服务器」

### C-1 Providers 与 Models 两个设置 tab

| 上游项 | 上游证据(file:line) | Android 实现(file:line) | 状态 | 差异要点 |
|---|---|---|---|---|
| Providers tab：Connected 列表（名称 + 来源 Badge + Disconnect 按钮） | `settings/providers/providers.tsx:48-56,135-174`；来源判定 `:71-96`（credential→"api"、env→"environment"、config/custom） | `ui/settings/SettingsPanels.kt:137-168`（只渲染 name + id 两行文本，无 Badge/无按钮） | 缺失 | 无连接/断开能力 |
| Providers tab：Popular 列表 + Connect 按钮 + View all | `providers.tsx:176-212`、`providers/catalog/order.ts:1-10`（popularProviders） | 无（`oc_providers_section_popular` 字符串存在但无引用，`res/values/strings.xml:237`） | 缺失 | — |
| Providers tab 数据源 | `useProviders`（`providers/catalog/providers.ts:14-86`，来自 `data.location.provider/model`） | `SettingsPanelExtras.providers`（`ui/settings/SettingsScreen.kt:99`），**唯一构造点 `ui/App.kt:588` 未传** → 恒为 `emptyList()` | 缺失 | 面板永远显示 `oc_providers_empty`（"No connected providers"），见附 2 |
| Connect 流程：Provider picker（搜索 + Popular/Other 分组 + Recommended/Custom 徽章） | `providers/connect/dialog.tsx:105-250` | 无 | 缺失 | — |
| Connect 流程：API key 表单 | `providers/connect/dialog.tsx:466-545`（`provider-connect-submit`） | 无 | 缺失 | 无任何写 credential 的 HTTP 调用（`OpenCodeClient.kt` 无 `credential`/`integration` 方法） |
| Connect 流程：OAuth（code / auto 两种模式 + 轮询） | `providers/connect/dialog.tsx:547-646`；`runtime/server/sync.tsx:305-320`（`integration.oauth.connect`） | 无 | 缺失 | — |
| Connect 流程：Custom provider 表单（providerID/name/baseURL/apiKey/models 行/headers 行 + 校验） | `providers/credentials/dialog.tsx`、`providers/credentials/form.ts:30-151` | 无 | 缺失 | — |
| Models tab：按 provider 分组的可折叠列表 + 每模型可见性开关 + 搜索 | `settings/models/models.tsx:51-71,109-210`；可见性规则 `providers/models/models.tsx:95-107` | `ui/settings/SettingsPanels.kt:172-194`（只列表 name+id，无开关/无分组标题/无搜索） | 缺失 | — |
| Models tab 数据源 | `useModels()`（`providers/models/models.tsx:24-31`，connected providers 的全模型） | `SettingsPanelExtras.models`（`SettingsScreen.kt:100`），唯一构造点 `App.kt:588` 未传 → 恒空 | 缺失 | 面板永远显示 `oc_models_empty` |
| `settings-v2.models.providers` 折叠状态持久化 | `models/models.tsx:45-49` | 无 | 缺失 | — |

### C-2 模型选择（composer）

| 上游项 | 上游证据(file:line) | Android 实现(file:line) | 状态 | 差异要点 |
|---|---|---|---|---|
| 运行/管理模型可见性（hide/show，最近 6 个月 latest 自动可见） | `providers/models/models.tsx:70-107` | 无 | 缺失 | Android 列出 catalog 全部模型，无筛选 |
| 选择器排序（popularProviders 优先，其余按 provider 名） | `providers/models/select-dialog.tsx:36-46`；`providers/catalog/order.ts:1-10` | `ui/session/composer/ModelPicker.kt:56-91`（`POPULAR` 列表与上游**逐字一致**） | 1:1 | 排序算法等价 |
| 选择器搜索（normalize + compact contains） | `providers/models/search.ts`（被 `select-dialog.tsx:23,278` 引用） | `ModelPicker.kt:104-134`（同算法注释对齐 `matchesModelSearch`） | 1:1 | — |
| 当前选中项打勾 | `select-dialog.tsx:208-212` | `ModelPicker.kt:242-248,308-316` | 1:1 | — |
| **「使用服务器默认」底部行** | **上游无此行**；上游底部是 `dialog.model.manage`（"Manage models"，打开 `DialogManageModels`） | `ModelPicker.kt:255-282`（`oc_model_picker_use_default`）→ `ChatViewModel.clearModelOverride()`（`ChatViewModel.kt:390-397`） | Android 独有 | 这是 Android 自造项，上游没有对应行；上游恢复默认靠 `selection.tsx:234-249` 的 `firstModel(scope, session, agent, fallback)` 链 |
| 默认模型解析链 | `configuredModel`（`providers/models/configured.ts:19-27`，取 config 文档里最后一个有 `model` 的条目，支持 `{providerID,modelID,variant}` 与 `"provider/model"` 两种形态）→ 再 `recentModel` → 再 `defaultModel`（`selection.tsx:169-178,234-249`） | `serverDefaultModel = parseModelRef(GET /config .model)`（`ChatViewModel.kt:332-338`、`OpenCodeClient.kt:608-609`），priority `override ?: serverDefault`（`ModelPicker.kt:122-125`） | 近似 | Android 少了 session/agent 级模型、recent 记忆、以及 variant；`parseModelRef` 只处理 `"provider/model"` 字符串（`OpenCodeClient.kt:870-885`），**不支持 config 里的对象形态** |
| recent 模型记忆（RECENT_LIMIT=5） | `providers/models/models.tsx:10,109-113`；`selection.tsx:163-167` | 无（只有单个 `selectedModelOverride`，`SettingsStore.kt:188-198`） | 缺失 | — |
| variant（模型变体）选择与循环 | `providers/models/variant.ts`、`select-dialog.tsx`/`selection.tsx:381-417` | 无 | 缺失 | — |
| free / latest 徽章 + 模型 tooltip | `select-dialog.tsx:27-28,200-205,469-499`、`providers/models/tooltip.tsx` | 无 | 缺失 | — |
| 模型 `(latest)` 名称清洗 | `providers/models/models.tsx:76-82` | 无 | 缺失 | — |
| 选择器底部「Manage models」入口 | `select-dialog.tsx:510-523`（popover）与 `:558-567`（dialog） | 无 | 缺失 | — |
| catalog 名称解析 | 上游把 v2 `provider.list` + `model.list` 归一化为 `ProviderListResponse`，**过滤 `model.status === "deprecated"`**、按 cost tier 取值、展开 `capabilities`（`runtime/server/global-sync/utils.ts:44-127`） | `OpenCodeClient.providers()` 打 `GET /provider`（`OpenCodeClient.kt:611-616`）并解析 v1 `{all,connected,default}` 形状（`Providers.fromJson`） | 近似（端点不同） | **端点差异确认**：Android 用 `/provider`；上游具体路由 `未确认`（`@opencode/client` 不在 checkout）。模型名称因此来自不同归一化管线（Android 直接用 catalog 的 `name`，`ChatViewModel.kt:369-374`） |
| `/config` 的用法 | 上游 `config.get()` 用于 shell 选项（`settings/general/controllers.ts:35`）与 config 文档（`providers/models/configured.ts:13-18`，`data.location.config`） | `GET /config` 只取 `.model`（`OpenCodeClient.kt:608-609`、`ChatViewModel.kt:337`） | 近似 | 上游用 config 文档序列（`type==="document"`）取最后一个含 model 的条目（可能来自项目级 config）；Android 只读全局单值 |

### C-3 服务器（Servers tab 与连接配置）

| 上游项 | 上游证据(file:line) | Android 实现(file:line) | 状态 | 差异要点 |
|---|---|---|---|---|
| 列表行：健康指示灯 + 名称 + URL meta + Default 徽章 + ⋮ 菜单 | `settings/servers/servers.tsx:61-82`、`servers/registry/row.tsx` | `ui/settings/ServersPanel.kt:134-218`（灯 + name + `v{ver} • {username}` + Default 标签 + ⋮） | 近似 | Android meta 是「version • username」，上游是 **URL**（`servers.tsx:69`） |
| 健康检查 | `servers/registry/controller.ts` 的 `collection.health()`（`servers.tsx:27`） | `SettingsServerBridge.kt:32` 恒为 `UNKNOWN`/`UNHEALTHY`（不做网络探测，注释承认） | 近似 | 灯永远是灰点或红点，不会变绿 |
| 多服务器搜索框（>1 才显示） | 上游 `servers.tsx` 无搜索框；搜索框在 select-server 对话框（Android 注释引用的 `dialog-select-server.tsx` 不在本 checkout → `未确认`） | `ServersPanel.kt:64-74` + `SettingsModel.kt:391`（`showsServerSearch`） | 未确认 | Android 自行加了搜索 |
| 排序（default 优先 → 健康值 → 输入序） | 上游 root 导航排序见 `settings/shell.tsx:196-203`（default 优先，再 collection 顺序），**无健康值参与** | `SettingsModel.kt:371-374`（default → health rank → index） | 近似 | Android 多了一层健康值排序 |
| Add/Edit 对话框：URL + Name + **Password** | `servers/connect/dialog.tsx:110-161`；连接模型 `runtime/server/persistence.ts:8,15`（`password`、`displayName`） | `ServersPanel.kt:318-356`（URL + Name + **Username** + Password） | 近似（字段集不同） | 上游 v2 **没有用户名输入**（Basic auth 用户名固定 `opencode`，`runtime/server/api.ts:5-6`；测试 `runtime/server/api.test.ts:9-11,25-26` 明确「忽略 legacy username」）。Android 仍让用户填 username，并在 `ServerConfigStore.kt:150-157,229-235` 把无 username 的条目**反推**成 `opencode` → 会向 WebView 的 localStorage 回写一个上游已废弃的字段 |
| 保存前健康检查（不健康则报错不保存） | `servers/connect/dialog.tsx:280-291`（`checkServerHealth`，失败文案 `dialog.server.add.error` / `mixedContent`） | `ServersPanel.kt:277-296` 只做 `http://`/`https://` 前缀校验 | 缺失 | Android 能保存不可达地址；`oc_server_error_auth`/`oc_server_error_cors`/`oc_server_checking` 字符串为死资源（`res/values/strings.xml:206-209`） |
| 输入时健康预览（去抖 + localhost/IP 才探测） | `servers/registry/management.ts:10-37` | 无 | 缺失 | — |
| 地址候选 datalist / 相机扫码配对（web） | `servers/connect/dialog.tsx:120-129,162-186`、`servers/connect/pairing.ts`、`camera.ts` | 无 | 缺失（平台不适用） | — |
| URL 归一化 | `normalizeServerUrl`（`runtime/server/registry`，`未确认` 实现细节） | `ServersPanel.kt:234-246` `normalizeUrlInput`（CJK 全角 → ASCII） | 近似 | Android 是 CJK IME 专项处理，机制不同 |
| 编辑时 URL 改名要迁移行、保留 default、移除旧 key 的 tabs | `servers/connect/dialog.tsx:292-298` + `management.ts:39-52` | `SettingsServerBridge.kt:58-81`（替换行 + 让 default 跟随新 URL） | 近似 | Android 无 tabs 需要迁移 |
| 删除时移除该 server 的 tabs | `apps/web` 侧 `tabs.removeServer(key)`（`servers/connect/dialog.tsx:245`） | `SettingsServerBridge.kt:83-89`（只改列表和 active） | 近似 | 无 tabs 概念 |
| 写回通道 | localStorage/持久化 store，WebView 与原生共用同一份 | `ServerConfigStore.replaceAll`（`ServerConfigStore.kt:196-210`）→ 只写原生 DataStore；`SettingsStore.encodeServerListJson`（`SettingsStore.kt:205-206`）**无调用点** | 近似（已知缺口） | `ServerConfigStore.kt:185-195` 注释承认：WebView 的 `localStorage` 仍是权威副本，M4 没有把新值推回 WebView 的 JS hook；`encodeServerListJson` 是死方法 |
| 单服务器时的「服务器设置页」含 Shell 下拉 | `settings/servers/servers.tsx:12-13,103-113`（`ServerShell` → `ShellSetting`，写服务端 `config.update({shell})`，`general/controllers.ts:26-69`） | 把 shell 放在 General 段（`SettingsModel.kt:143-154`），写本地 prefs（`SettingsStore.kt:157`） | 有 UI 但无消费方 | 上游是**服务端**配置（`GET /config/shells` 列表 + `POST /config/update`，`controllers.ts:34-66`）；Android 是本机字符串，且没有任何消费方 → 见附 2 |
| Shell 选项来源 | `createShellOptions`（`general/behavior.ts:16-38`）：从服务端探测到的 shell 列表 + `auto` + 当前值兜底；同名的用完整路径区分 | `SettingsModel.kt:148-153` 硬编码 `auto/sh/bash/zsh` | 近似 | 上游是动态列表，Android 是固定 4 项 |

### C-4 SSH / WSL / 远程服务器

| 上游项 | 上游证据(file:line) | Android 实现 | 状态 | 差异要点 |
|---|---|---|---|---|
| SSH 服务器：设置行（名称 + `ssh` 标签 + 状态 + Authenticate 按钮 + 菜单） | `servers/ssh/settings.tsx:14-89` | 无 | 缺失 | — |
| SSH：新建/编辑对话框、认证状态机、恢复、命名规则 | `servers/ssh/dialog.tsx`、`authenticate.tsx`、`authentication.tsx`、`authentication-state.ts`、`controller.ts`、`name.ts`、`status.ts` | 无 | 缺失 | — |
| SSH 命令入口 `server.ssh.add` | `shell/commands/desktop.tsx:24-30` | 无 | 缺失 | — |
| WSL 服务器设置与安装对话框 | `servers/wsl/settings.tsx`、`servers/wsl/dialog.tsx`、`model.ts`、`probes.ts` | 无 | 缺失（平台不适用） | — |
| `SettingsServer` 家族的 `wsl`/`ssh` 字段与去重 | `settings/servers/inventory.ts:26-75` | 无（`ServerListItem` 只有 url/name/username/version/health/isDefault，`SettingsModel.kt:357-364`） | 缺失 | — |
| 连接类型（`ServerConnection.Any`：http/wsl/ssh） | `runtime/server/registry.tsx:135` | 仅 http（`ServerConfigStore.kt:32-38`、`parseEntry` 只接受 `type=="http"`，`:136-148`） | 近似 | Android 明确拒绝非 http 条目 |
| 移动端配对（扫码） | `servers/connect/*`（`pairing.ts`、`scanner.tsx`、`camera.ts`、`screen.tsx`、`browser.ts`） | 无 | 缺失 | 上游 web 端扫二维码导入 URL+password |
| 服务器状态/只读视图（`session/summary/server-panel.tsx`） | `_upstream/packages/app/src/session/summary/server-panel.tsx`（存在） | 无 | 缺失 | — |

### C-5 凭据

| 上游项 | 上游证据(file:line) | Android 实现 | 状态 | 差异要点 |
|---|---|---|---|---|
| 凭据存储 | 服务端 credential（`serverSdk.api.credential.remove`，`providers.tsx:108`）+ 客户端 `ServerConnection.Http.password`（`runtime/server/persistence.ts:8`） | 服务端密码存 `ServerConfigStore` 的 EncryptedSharedPreferences（`ServerConfigStore.kt:40-54`），明文回退到 `oc_server_config_plain` | 近似 | Android 把凭据放在客户端；上游 provider 凭据在服务端 |
| 断开/移除凭据 | `providers.tsx:100-121` | 无 | 缺失 | — |
| Basic auth 头构造 | `runtime/server/api.ts:5-6`（`opencode:{password}`）+ `health.ts:81-83` | `OpenCodeClient` 用 `ServerConfigStore` 的 username/password | 近似 | Android 允许自定义 username（上游忽略） |
| 自定义 provider 的 env 占位 `{env:VAR}` | `providers/credentials/form.ts:57-58,142` | 无 | 缺失 | — |

---

## 附 1：同名设置项默认值逐条对照

上游默认值集中在 `_upstream/packages/app/src/settings/model.tsx:239-270`（`defaultSettings`）；Android 在 `SettingsStore.kt:116-138`（`read()`）+ `SettingsStore.kt:236-245`（常量）。

| 设置项 | 上游键/字段（file:line） | 上游默认 | Android 字段（file:line） | Android 默认 | 一致？ |
|---|---|---|---|---|---|
| Color scheme | `@opencode/ui/theme/context`（`未确认`）；选项 `system/light/dark`（`appearance.tsx:11`） | 未确认 | `colorScheme`（`SettingsStore.kt:117`） | `"system"`（`:237`） | 未确认 |
| Theme | `theme.ids()`（`controllers.ts:74`） | 未确认 | `themeId`（`SettingsStore.kt:118`） | `"oc-2"`（`:236`） | 未确认 |
| UI Font | `appearance.sans`（`model.tsx:257`）= `""` | `""`（placeholder `Inter`，`model.tsx:20`） | `uiFont`（`SettingsStore.kt:119`） | `""` | ✅ |
| Code Font | `appearance.mono`（`model.tsx:257`）= `""` | `""`（placeholder `IBM Plex Mono`，`:19`） | `codeFont`（`SettingsStore.kt:120`） | `""` | ✅ |
| Terminal Font | `appearance.terminal`（`model.tsx:257`）= `""` | `""`（placeholder `JetBrainsMono Nerd Font Mono`，`:21`） | `terminalFont`（`SettingsStore.kt:121`） | `""` | ✅ |
| Language | 无默认字段（`language.locale()` 决定，`general.tsx:288`） | `未确认` | `language`（`SettingsStore.kt:122`） | `"en"`（`:238`） | 未确认 |
| autoApprove permissions | `permissions.autoApprove`（`model.tsx:259`） | `false` | `autoAcceptPermissions`（`SettingsStore.kt:123`） | `false` | ✅ |
| Shell | 无客户端默认；服务端 config 的 `shell`（`controllers.ts:42-45`） | 服务端 undefined → 显示 `auto`（`behavior.ts:22`） | `shell`（`SettingsStore.kt:124`） | `"auto"`（`:239`） | 近似 |
| showFileTree | `general.showFileTree`（`model.tsx:243`） | **`false`** | `showFileTree`（`SettingsStore.kt:134`） | **`true`** | ❌ 相反 |
| showSearch | `general.showSearch`（`model.tsx:244`） | **`false`** | `showSearch`（`SettingsStore.kt:135`） | **`true`** | ❌ 相反 |
| showCustomAgents | `general.showCustomAgents`（`model.tsx:249`） | **`false`** | `showCustomAgents`（`SettingsStore.kt:137`） | **`true`** | ❌ 相反 |
| notifications.agent | `model.tsx:261` | `true` | `notifyAgent`（`SettingsStore.kt:128`） | `true` | ✅ |
| notifications.permissions | `model.tsx:261` | `true` | `notifyPermissions`（`SettingsStore.kt:129`） | `true` | ✅ |
| notifications.errors | `model.tsx:261` | **`false`** | `notifyErrors`（`SettingsStore.kt:130`） | **`true`** | ❌ 相反 |
| sounds.agent（启用+音效） | `model.tsx:263-264`：`agentEnabled true` / `agent "staplebops-01"` | 启用 + 具体音效 | `soundAgent`（`SettingsStore.kt:131`） | `"default"`（`:240`） | ❌ 值域不同（无 enable 位） |
| sounds.permissions | `model.tsx:265-266`：`true` / `"staplebops-02"` | 同上 | `soundPermissions`（`SettingsStore.kt:132`） | `"default"` | ❌ |
| sounds.errors | `model.tsx:267-268`：`true` / `"nope-03"` | 同上 | `soundErrors`（`SettingsStore.kt:133`） | `"default"` | ❌ |
| timeline 展示（`showReasoningSummaries` 合并目标） | `general.timelineDetail`（`model.tsx:248`）= `timelinePresets[2].value` → **"compact"**：shell grouped/collapsed、edit grouped/collapsed、**thinking grouped/collapsed**（`session-ui/src/timeline/detail.ts:40-50`） | thinking 折叠、shell 折叠、edit 折叠 | `reasoningSummaries`（`SettingsStore.kt:125`）= **true**；`shellToolPartsExpanded`（`:126`）= `false`；`editToolPartsExpanded`（`:127`）= **`false`** | 与上面逐个比 | 近似（模型不同） |
| ——逐项对比 | 上游 thinking=folded vs `reasoningSummaries=true`（Android 恒显示推理，`PartRenderer.kt:117-118`）；上游 shell=folded vs Android `false` → ✅ 巧合一致；上游 edit=**folded** vs Android `editToolPartsExpanded=false` → ✅ 一致；但 **Android 代码内的注释/默认常量** `ToolCardPolicy.kt:35-36` `DEFAULT_EDIT_EXPANDED = true` 与设置默认 `false` **互相矛盾**，而真正生效的是 `ToolCardPolicy` 的常量（`MessageTimeline.kt:77`），见附 2 | | | | |
| mobileDiffWrap | `model.tsx:251` | `true` | 无 | — | — |
| terminalPlacement | `model.tsx:252` | `"side"` | 无 | — | — |
| followUpBehavior | `model.tsx:253` | `"steer"` | 无 | — | — |
| mobileTitlebarPosition | `model.tsx:250` | `"top"` | `AppearancePrefs.TabLayout.KEY`（`AppearancePrefs.kt:16`） | 读 `appearance.tabLayout`，缺省 null → 不显示底部条（`:20-27`） | 近似（键不同、无人写） |
| appearance.tabLayout | `model.tsx:257` | `"horizontal"` | 读 `"appearance.tabLayout"`（`AppearancePrefs.kt:16-20`） | 缺省 null → 等同 horizontal | ✅ 行为一致（键名沿用，但设置页无 UI 可改） |
| appearance.fontSize | `model.tsx:257` | `14` | 无 | — | — |
| appearance.showProjectName | `model.tsx:257` | `false` | 无 | — | — |
| general.autoSave | `model.tsx:241` | `true` | 无 | — | — |
| general.releaseNotes | `model.tsx:242` | `true` | 无 | — | — |
| general.showNavigation | `model.tsx:244` | `false` | 无 | — | — |
| general.showProjectIcon | `model.tsx:246` | `false` | 无 | — | — |
| general.showTerminal | `model.tsx:247` | `false` | 无 | — | — |
| general.experimentalBrowser | `model.tsx:254` | `false` | 无 | — | — |
| sessionSummary.projectExpanded / serverExpanded | `model.tsx:256` | `true` / `true` | 无 | — | — |
| workspace defaultDestination | `model.tsx:260` | `"last-used"` | 无 | — | — |
| keybinds | `model.tsx:258` | `{}`（空覆盖表） | 无（`SHORTCUT_ROWS` 是常量） | — | — |
| 服务器 URL placeholder | `dialog.server.add.placeholder` (i18n `localhost:4096`，`servers/connect/dialog.tsx:116`) | `http://localhost:4096` | `DEFAULT_SERVER_URL_PLACEHOLDER`（`SettingsModel.kt:404`） | `http://10.0.2.2:4096` | 有意的 Android 偏差（`:400-403` 注释说明） |
| 服务器默认用户名 | 上游无该字段（`runtime/server/api.ts:5-6` 固定 `opencode`） | 固定 `opencode` | `DEFAULT_SERVER_USERNAME`（`SettingsModel.kt:397`） | `"opencode"` | 数值一致，但 Android 把它做成了可编辑字段 |

**默认值不一致清单（6 条）**：`showFileTree`、`showSearch`、`showCustomAgents`、`notifications.errors`、三路 `sounds.*`（值域不同）、以及 `DEFAULT_EDIT_EXPANDED` 的设置默认（false）与运行时生效常量（true）自相矛盾。

## 附 2：死设置项（有 UI / 有存储但无消费方）

判定方法：对 `SettingsStore.Settings` 的每个字段做全仓库（`app/src`）grep，凡只在 `SettingsStore.kt`（读写）、`ui/settings/SettingsPanels.kt:86-109`（仅用于**回显**）、单测断言中出现，即为死设置。

| # | 设置项 | Android 读取点（回显） | 存储键 | 消费方 | 结论 |
|---|---|---|---|---|---|
| 1 | Theme (`settings-theme`) | `SettingsPanels.kt:94` | `opencode-theme-id`（`SettingsStore.kt:212`） | 无（`ui/theme/OcTheme.kt:303-347` 只用 `OcFonts.default`，主题恒为 OC2） | 死 |
| 2 | UI Font | `SettingsPanels.kt:95` | `opencode-ui-font`（`:214`） | 无 | 死 |
| 3 | Code Font | `SettingsPanels.kt:96` | `opencode-code-font`（`:215`） | 无 | 死 |
| 4 | Terminal Font | `SettingsPanels.kt:97` | `opencode-terminal-font`（`:216`） | `TerminalPanel.kt:107-108` 有同名参数，但唯一调用点 `ui/App.kt:351-355` 未传值 | 死 |
| 5 | Shell (`settings-shell`) | `SettingsPanels.kt:89` | `opencode-shell`（`:220`） | 无（不写服务端 `config.shell`，也不影响 `ui/terminal`） | 死 |
| 6 | Show reasoning summaries | `SettingsPanels.kt:90` | `opencode.android.reasoningSummaries`（`:221`） | 无（`PartRenderer.kt:117-118` 无条件渲染 reasoning） | 死 |
| 7 | Expand shell tool parts | `SettingsPanels.kt:91` | `opencode.android.shellToolPartsExpanded`（`:222`） | 无（`SessionScreen.kt:247-297` 调 `MessageTimeline` 未传 `shellExpanded`，用 `ToolCardPolicy.DEFAULT_SHELL_EXPANDED`） | 死 |
| 8 | Expand edit tool parts | `SettingsPanels.kt:92` | `opencode.android.editToolPartsExpanded`（`:223`） | 同上（未传 `editExpanded`；且生效常量 `ToolCardPolicy.DEFAULT_EDIT_EXPANDED = true` 与设置默认 `false` 矛盾） | 死 + 自相矛盾 |
| 9 | Notifications: Agent | `SettingsPanels.kt:98` | `opencode.android.notify.agent`（`:224`） | 无（无任何通知发送代码） | 死 |
| 10 | Notifications: Permissions | `SettingsPanels.kt:99` | `...notify.permissions`（`:225`） | 无 | 死 |
| 11 | Notifications: Errors | `SettingsPanels.kt:100` | `...notify.errors`（`:226`） | 无 | 死 |
| 12 | Sounds: Agent | `SettingsPanels.kt:101` | `...sound.agent`（`:227`） | 无（无播放器；上游是 `shell/notifications/sound.ts`） | 死 |
| 13 | Sounds: Permissions | `SettingsPanels.kt:102` | `...sound.permissions`（`:228`） | 无 | 死 |
| 14 | Sounds: Errors | `SettingsPanels.kt:103` | `...sound.errors`（`:229`） | 无 | 死 |
| 15 | File tree | `SettingsPanels.kt:104` | `...showFileTree`（`:230`） | 无（无 file tree 面板） | 死 |
| 16 | Command palette (`showSearch`) | `SettingsPanels.kt:105` | `...showSearch`（`:231`） | 无（面板只有 `mod+k` 一种入口，没有可隐藏的按钮） | 死 |
| 17 | Server status | `SettingsPanels.kt:106` | `...showStatus`（`:232`） | 无 | 死（且上游无此设置项） |
| 18 | Show agent | `SettingsPanels.kt:107` | `...showCustomAgents`（`:233`） | 无（composer 无 agent 选择器） | 死 |
| 19 | 整个 Providers tab | — | — | `SettingsPanelExtras.providers` 唯一构造点 `ui/App.kt:588` 未赋值 → 恒空 | 死面板 |
| 20 | 整个 Models tab | — | — | `SettingsPanelExtras.models` 同上 | 死面板 |
| 21 | Language 的 WebView 侧 | `SettingsStore.kt:156,170-174` | `opencode-language`（`:218`） | 仅原生资源（`applyLanguage`，`:59-77`）；WebView 内容语言不变 | 半死 |
| 22 | `PaletteHostKind.HOME` 分支 + `oc_palette_placeholder_home` | `PaletteModel.kt:226`、`res/values/strings.xml:242` | — | 无调用点（`ui/App.kt:422` 恒 SESSION） | 死分支/死资源 |
| 23 | `SettingsStore.encodeServerListJson` | `SettingsStore.kt:205-206` | — | 无调用点 | 死方法 |
| 24 | 未被引用的字符串资源 | — | — | `oc_providers_section_popular`、`oc_server_error_auth`、`oc_server_checking`、`oc_shortcut_recording_soon`、`oc_setting_value_on`、`oc_setting_value_off`、`oc_palette_title`、`oc_command_file_open`、`oc_command_category_*`、`oc_servers_more`、`oc_servers_no_username`（`res/values/strings.xml` 各处；仅 `SettingsPaletteModelTest.kt:286` 引用了 `oc_palette_title`） | — | 死资源 |

**活的设置项（有真实消费方）只有 3 个**：`colorScheme`（`ui/App.kt:152-156` → `Settings.isDark`，`SettingsStore.kt:109-113`）、`autoAcceptPermissions`（`ChatViewModel.kt:134-140` → `AutoApprover.kt`）、`language`（仅原生资源，`SettingsStore.kt:59-77`）。另外 `selectedModelOverride`（`SettingsStore.kt:188-198`，不在 `Settings` 快照里）被 `ChatViewModel.kt:104,381-397` 消费，属活。

## 附 3：命令面板快捷键 & Home/会话宿主展示策略

1. **和弦一致**：`mod+k` / `mod+shift+p` 与上游 `DEFAULT_PALETTE_KEYBIND`（`shell/commands/command.tsx:16`）字面一致，Android 侧实现于 `PaletteModel.kt:282-287` + `ui/App.kt:452-466`。
   - 唯一偏差：上游 `matchKeybind` 要求修饰键严格相等（`command.tsx:188-204`），故 `mod+shift+k` **不会**打开面板；Android `keyK -> true` 不看 shift → `mod+shift+k` 也会打开。`mod+shift+p`、`mod+,` 完全一致。
   - `settings.open = mod+comma`（`settings/command.tsx:21`）与 Android `isSettingsChord`（`PaletteModel.kt:290`）一致。
2. **除这两个和弦外，Android 没有把任何上游 keybind 接进键盘处理**：`globalChord`（`ui/App.kt:452-466`）只返回 PALETTE/SETTINGS；终端键（ctrl+反引号）、`Ctrl+N`、`Ctrl+P` 都只在面板/设置页里被**当成文本显示**（`AppCommands.kt:34,40,61`、`SettingsModel.kt:335-340`），按下去没有反应。
3. **宿主展示策略**：上游 Home 用 `DialogHomeCommandPaletteV2`（`home/sessions/command-palette.tsx`，只命令 + 会话，空查询 `commandEntries().slice(0,5)`，无文件、无最近文件），session/new-session 用 `DialogCommandPalette`（`shell/commands/dialog.tsx:30-55`，命令 + 最近文件 + 会话异步源 + 文件异步源）；Android 只有 SESSION 一种宿主被实例化（`ui/App.kt:422`），因此 **Home 上也会出现 Files 分组和 `/find/file` 搜索**，且占位文案永远是 session 版；`PaletteHostKind.HOME` + `oc_palette_placeholder_home` 为死代码/死资源。上游 Home 的服务器选择器（`home/sessions/controller.tsx:115-119` 取 `home.server.focused()`）在 Android 也不存在（只有单一 active server）。

---

## 统计（行数按报告内表格实际行数机械计数）

- **表 A「设置」= 58 行**（A-1 导航 19 + A-2 General 12 + A-3 Appearance 7 + A-4 Notifications/Sounds 7 + A-5 Advanced 4 + A-6 Shortcuts 9）。
  状态分布：`1:1` **3**、`近似` **15**、`缺失` **25**、`有 UI 但无消费方（死）` **14**、`上游缺失（Android 独有）` **1**（`settings-v2-nav-footer`，A-1）。另 `settings-show-status`（A-5）同时属于「上游连存储键都没有」。
  其中「缺失」按类别拆：导航/页级 10、General 段 8、Appearance 1、Notifications 2、Shortcuts 4。
- **表 B「命令面板」= 82 行**（B-1 上游注册表 55 + B-2 面板行为 20 + B-3 宿主策略 7）。
  - 上游命令：**51** 条有 `command.register` 注册点；另有 **5** 个 id 只出现在 `COMMON_COMMAND_IDS` / 桌面菜单里而**本 checkout 无注册**（`session.previous`、`session.next`、`workspace.new`、`project.previous`、`project.next`）。
  - Android：`appCommands()` **7** 条 → `1:1 可执行` **1**（`settings.open`）、`近似可执行` **1**（`terminal.toggle`，且非 toggle 语义）、`占位不可用（onSelect = {}）` **4**（`session.new`、`workspace.new`、`session.previous`、`session.next`）、`占位不可用（onSelect = {} 且上游可执行）` **1**（`review.toggle`）。
  - B-1 的 55 行状态汇总：`1:1 可执行` **1**、`近似可执行` **2**（`terminal.toggle`、`command.palette`）、`占位不可用` **5**、`缺失` **47**（含上游同样未注册的 `project.previous/next`）。
  - 面板行为 20 行：`1:1` **5**、`近似` **8**、`缺失` **6**、`半失效` **1**（会话「已打开」标记）。
  - 宿主策略 7 行：`近似` **5**、`缺失` **2**（`PaletteHostKind.HOME` 分支 + `file.open` 重定向）。
- **表 C「Providers / Models / 服务器」= 49 行**（C-1 Providers-Models tab 10 + C-2 模型选择 13 + C-3 服务器 14 + C-4 SSH/WSL 8 + C-5 凭据 4）。
  状态分布：`1:1` **3**、`近似` **15**、`缺失` **28**、`有 UI 但无消费方/死` **1**、`未确认` **1**、`Android 独有` **1**。
- **默认值核对（附 1，37 行）**：可明确对比的 **22** 项中，一致 **8**、**不一致 7**（`showFileTree`、`showSearch`、`showCustomAgents`、`notifications.errors`、`sounds.agent/permissions/errors` 值域）、近似 **4**、上游值 `未确认` **3**（color scheme / theme / language 的上游默认来自 `@opencode/ui/theme/context`，该文件不在本 checkout）。此外发现 1 处 Android **内部自相矛盾**：设置默认 `editToolPartsExpanded=false`（`SettingsStore.kt:127`）与运行时生效常量 `ToolCardPolicy.DEFAULT_EDIT_EXPANDED=true`（`ToolCardPolicy.kt:36`）。
- **死设置项（附 2，24 行）**：**18** 个设置字段 + **2** 个死面板（Providers / Models）+ **1** 个半死（Language 只作用于原生资源，WebView 内容语言不变）+ **1** 个死分支（`PaletteHostKind.HOME`）+ **1** 个死方法（`SettingsStore.encodeServerListJson`）+ **11** 个死字符串资源。
  全仓库对 `SettingsStore.Settings` 的 21 个字段做读取点 grep 后，**只有 3 个字段有真实消费方**：`colorScheme`、`autoAcceptPermissions`、`language`（半消费）。
