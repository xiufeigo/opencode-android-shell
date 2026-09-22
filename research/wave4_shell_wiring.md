# W4-D 外壳接线报告（设置面板真实数据 / 死设置 / 命令面板 / 快捷键）

- 角色：`shell-wiring`（task-4）
- 写作用域：`ui/App.kt`、`ui/palette/**`、`ui/settings/**`、`SettingsStore.kt`、三份 `strings.xml`（只追加）、`app/src/test/**`（settings/palette 相关）、本文件
- 上游对照：`_upstream/packages/app/src/**`（v2，只读）
- 主规格：`research/audit_settings_palette.md`（表 A/B/C + 附 1/2/3）、`research/audit_routes.md` 表 A/B-2、`research/wave4_contracts.md`
- 构建：`$env:JAVA_HOME=…jdk-21.0.12.1+1; .\gradlew.bat :app:testDebugUnitTest` → **BUILD SUCCESSFUL，628 tests / 0 failed**（基线 537）
- 设备：`emulator-5554`（平板 2560x1600，density 320，硬件键盘），APK `app/build/outputs/apk/debug/app-debug.apk`

---

## 1. 交付项 1：Providers / Models 两个 tab 接真实数据

**问题（Lead 实测 + 审计 C-1）**：`SettingsPanelExtras` 的唯一构造点 `ui/App.kt` 从未传 `providers`/`models`，两个面板恒显示 "No connected providers" / "No available models"。

**改法**

| 文件 | 改动 |
|---|---|
| `ui/settings/SettingsScreen.kt` | `SettingsPanelExtras` 由「快照 list」改为**实时流**：`providers: StateFlow<OpenCodeClient.Providers?>`、`selectedModel: StateFlow<Pair<String,String>?>`、`serverBridge`。理由：设置层是按键处理器里 `dialog.show{}` 推上去的，`GET /provider` 往往还没回来，快照会永久停在空态 |
| `ui/settings/SettingsModel.kt` | 新增纯函数投影：`providerRows()` / `connectedProviderCount()` / `modelRows()` / `connectedModelCount()` / `filterModelRows()` / `formatContextLimit()` |
| `ui/settings/SettingsPanels.kt` | `ProvidersPanel`：表头 `已连接的提供商: n` + `全部提供商: n`，列表**已连接置顶**，每行 = 名称 + `已连接` 徽章 + provider id + 模型数；`ModelsPanel`：`可用模型: n` + 当前生效模型（无则「服务器默认」）、模型搜索框、按 provider 分组、行右侧 `上下文 128k/1M`，当前模型打 `当前` 徽章 |
| `ui/App.kt` | `showSettingsDialog(dialog, store, serverStore, chatViewModel)` → `SettingsPanelExtras(providers = chatViewModel?.availableProviders, selectedModel = chatViewModel?.selectedModel, …)` |

**设备证据**（截图在仓库根目录）

- `w4d_providers.png`：Providers tab 显示 **已连接的提供商：10 / 全部提供商：225**，行：Antigravity（已连接，2 个模型）、Command Code（已连接，5 个模型）、MiniMax Token Plan (minimax.cn)（已连接，2 个模型）、OpenAI（已连接，7 个模型）…（与 Lead 实测 `/provider` 的 `connected.size == 10`、`all == 225` 一致）
- `w4d_models.png`：Models tab 显示 **可用模型：36**、`服务器默认`（本机 `/config` 无 `model`，故当前无标记）、搜索框「搜索模型」、分组 `Z.AI Coding Plan`（GLM-5.3-Flash / GLM-5.3，上下文 1000000）、`MiniMax Token Plan (minimax.cn)`（MiniMax-M3 上下文 1M、MiniMax-M2.7-highspeed 上下文 200k）

**未做**（审计 C-1 的缺失项，无对应后端能力，故**不画按钮**）：Connect/Disconnect、Popular 列表、API key / OAuth / 自定义 provider 表单、模型可见性开关、分组折叠持久化。`OpenCodeClient` 没有 credential/integration 端点调用，画一个点了没反应的 Connect 按钮违背本轮纪律。

---

## 2. 交付项 2：死设置项 —— 接线或隐藏

### 2.1 本轮接线（现在有真实消费方）

| 设置 | `data-action` | 消费方（file:line） |
|---|---|---|
| 终端字体 | `settings-terminal-font` | `ui/App.kt` → `TerminalPanelHost(terminalFont = settings.terminalFont)`；`TerminalPanel.kt:519` → `resolveTerminalTypeface(fontFamily)` |
| 时间线：显示推理摘要 | `settings-feed-reasoning-summaries` | `ui/settings/TimelineDisplayPrefs.kt`（CompositionLocal，`ui/App.kt` provide）→ `MessageTimeline` / `PartRenderer`（session-fidelity 落地：`false` 不渲染 reasoning part） |
| 时间线：展开 Shell 工具 | `settings-feed-shell-tool-parts-expanded` | 同上 → `MessageTimeline(shellExpanded = LocalTimelineDisplayPrefs.current.shellToolPartsExpanded)` → `ToolCardPolicy.resolveOpen` |
| 时间线：展开编辑工具 | `settings-feed-edit-tool-parts-expanded` | 同上；**矛盾已统一**：`ToolCardPolicy.DEFAULT_EDIT_EXPANDED` 已由 session-fidelity 改为 `false`（= 存储默认 = 上游 `compact` preset，`session-ui/src/timeline/detail.ts:41-49`） |
| 文件树 | `settings-show-file-tree` | `appCommands(showFileTree = …)`：为 `true` 才注册 `fileTree.toggle`（上游同款门控 `use-session-commands.tsx:375` + `settings/model.tsx:386-390`）；命令 → `SessionCommandIntents.openFileTree()` → 会话页打开 review 面板的「文件」tab |
| 快捷键表 | — | `SHORTCUT_ROWS` 改为与 `globalChord` + `appCommands` **逐字一致**（见 §4） |

跨文件接线通过两个**单向意图通道**完成（我的 scope 内定义，避免改他人文件）：

- `ui/settings/TimelineDisplayPrefs.kt`：`TimelineDisplayPrefs` + `LocalTimelineDisplayPrefs` + `SettingsStore.Settings.timelineDisplayPrefs()`
- `ui/palette/SessionCommandIntents.kt`：`SessionCommandIntents`（`reviewRevision` / `fileTreeRevision` / `fileRequest` + `consume`）+ `LocalSessionCommandIntents`；`ui/App.kt` provide 一次，palette 命令与 `SessionScreen` 共用

`session-fidelity`（MessageTimeline/PartRenderer/ToolCardPolicy）与 `mobile-form`（SessionScreen 读 intent、ReviewPanel tab 提升 + `openPath`）均已确认落地。

### 2.2 已隐藏清单（有 UI 但无消费方 → 从设置页移除，键保留在 `SettingsStore`）

`HIDDEN_SETTINGS`（`ui/settings/SettingsModel.kt`，单测断言）与理由：

| 已隐藏行 | 为什么隐藏 |
|---|---|
| `settings-theme` | `OcTheme` 只提供一个主题（OC-2），无主题注册表 |
| `settings-ui-font` | `OcTheme` 只提供 `OcFonts.default`（`ui/theme/OcTheme.kt:303-347`） |
| `settings-code-font` | 代码块固定 `FontFamily.Monospace`（`ui/markdown/MarkdownRenderer.kt:281,410`） |
| `settings-shell` | 上游写**服务端** `config.shell`（`general/controllers.ts:26-69`）；Android 只写本地 prefs，终端/工具链都不读它 |
| `settings-notifications-agent/permissions/errors` | 全仓库无通知发送方（上游 `shell/notifications/*` 未移植） |
| `settings-sounds-agent/permissions/errors` | 无播放器；且值域与上游不同（上游 = enable 布尔 + 音效 id） |
| `settings-show-search` | 上游该键控制**标题栏**搜索按钮可见性（`model.tsx:388`）；原生外壳没有标题栏，且上游 v2 设置页本身也没有这一行 |
| `settings-show-status` | 上游连存储键都没有（Android 独有），无消费方 |
| `settings-show-custom-agents` | composer 没有 agent 选择器 |

说明：不删 `SettingsStore` 的键与 `BOOLEAN_KEYS` 映射（用户已存的值不丢，后续里程碑补消费方时可直接恢复该行）；单测 `booleanPrefKeysCoverEveryRenderedSwitchPlusTheHiddenOnes` 保证「渲染的开关 ∪ 隐藏的开关 = 写入白名单」，不会出现「写了没人读」的新死项。

**默认值不一致**（审计附 1）里剩下的三条（`showSearch` / `showCustomAgents` / `notifyErrors`）随行一起隐藏，不再影响 UI；`showFileTree` 保持 Android 既有默认 `true`（改为上游 `false` 会让文件树入口默认消失，属行为回退，登记为偏差）。

---

## 3. 交付项 3：命令面板

### 3.1 5 条空实现 + `file.open`

`ui/palette/AppCommands.kt` 重写为「**回调缺席即不注册**」：不再出现 `onSelect = {}` 的死行。

| 命令 | keybind（上游） | 现在的副作用 |
|---|---|---|
| `settings.open` | `Ctrl+,` | 打开设置（原有） |
| `session.new` | `Ctrl+Shift+S`（`mod+shift+s`） | `navController.navigate(new-session)`；keybind 从旧占位 `Ctrl+N` 纠正为上游值 |
| `tab.new`（hidden） | `Ctrl+N` | 同上；上游 `Ctrl+N` 属于 `tab.new`（`titlebar.tsx:388-396`） |
| `workspace.new` | — | 目录选择器（`DirectoryPickerDialog`）→ 选中目录后进草稿页（Android 无 worktree store，取最近似的真实动作，见 §8 偏差） |
| `session.previous` / `session.next` | 菜单 `Option+Up/Down` | `listSessions` → `steppedSessionId(...)` 在 `updatedAt` 降序表里前后跳（首尾环绕），`popUpTo(HOME)` 不堆栈 |
| `terminal.toggle` | `Ctrl+`` | **真 toggle**：已在终端路由则 `popBackStack()`，否则 push（修掉「重复 push 路由」） |
| `review.toggle` | `Ctrl+Shift+R`（`mod+shift+r`） | `SessionCommandIntents.toggleReview()` → 会话页翻 review（桌面）或切 review 视图（移动形态）；keybind 从「无」补上游值 |
| `file.open` | `Ctrl+P`（`mod+p`） | 打开**文件专用面板**（`PaletteHostKind.FILES_ONLY`，等价上游 `filesOnly()` 的 `DialogCommandPalette`） |
| `fileTree.toggle` | `Ctrl+\`（`mod+\`） | 仅当 `showFileTree` 为真时注册；打开 review 的「文件」tab |

纯逻辑测试：`ShellWiringTest.everyCommandRowIsWiredToARealAction`（每行 onSelect 真的被调用）、`emptyQueryCommandsAreAllExecutable`（空查询前 5 条可执行）、`fileTreeRowOnlyExistsWhileTheSettingIsOn`、`commandKeybindsMatchUpstreamNotTheOldPlaceholders`。`appCommands` 去掉了 `@Composable`（只解析资源 id），因此可在 JVM 里逐行断言。

### 3.2 面板宿主策略（上游区分）

- `PaletteHostKind` 新增 `FILES_ONLY`；`sources()` 对 FILES_ONLY 清空 commands/sessions（`palette.ts:96-99`）
- `ui/App.kt` 按**当前路由**选宿主：Home → `HOME`（无 Files 分组、无 `/find/file`），会话/终端/草稿 → `SESSION`
- 设备证据 `w4d_palette.png`：Home 上 Ctrl+K 打开的面板占位符是「搜索命令和会话」（home 文案）、只有「命令」+「Session」两组（无 Files）——修复了审计 B-3「Home 也显示 Files」与「`PaletteHostKind.HOME` 死分支」

### 3.3 会话行 meta 与「已打开」标记

- `sessionPaletteEntries(..., openSessionIds, meta)`：`meta` = 相对时间（复用 `SessionTimeLabel` 分桶 + `oc_time_*` 复数资源，`getRelativeTime` 上游对应 `dialog.tsx:267-271`），`open` = `openSessionIds` 命中（渲染成行首竖条，`CommandPalette.kt:298-305`）
- `ui/App.kt` 把 `openSessions`（本 run 打开过的会话）传进面板，两处此前「参数存在但调用点没传」的半失效接线已补齐

### 3.4 选中文件行的副作用

上游 `createCommandPaletteFileOpener`（`palette.ts:66-80`）：打开文件 + 打开 review 面板 + 文件树切 `all`。Android：

1. `PaletteEntryType.FILE -> entry.path?.let(onOpenFile)`
2. `onOpenFile`：`intents.openFile(path)`；若当前不在会话路由，先跳到最近打开的会话（文件只能在会话的 review 面板里显示）
3. `SessionScreen`（mobile-form）消费 `fileRequest`：**确保打开** review 面板 + 切「文件」tab + 定位到该路径预览

---

## 4. 交付项 4：快捷键真正接入键盘

`ui/App.kt` 的 `globalChord(...)`（纯函数）扩展为 5 个 chord，全部接到真实动作：

| chord | 动作 | 上游 |
|---|---|---|
| `mod+k`（**不按 Shift**）/ `mod+shift+p` | 面板（按宿主） | `command.tsx:16,188-204` |
| `mod+p` | `file.open` 文件面板 | `use-session-commands.tsx:314-321` |
| `mod+,` | 设置 | `settings/command.tsx:21` |
| `mod+n` / `mod+shift+s` | 新会话草稿 | `tab.new` / `session.new` |
| `ctrl+`` (`Key.Grave`) | 终端 toggle | `use-session-commands.tsx:354-368` |

- 修正审计附 3.1 的偏差：`mod+shift+k` 不再打开面板（上游要求修饰键**严格相等**）；`isPaletteChord` 同步收严，`SettingsPaletteModelTest` 断言更新
- 弹窗打开时 chord 依然被抑制（`LocalDialogActive`），与上游 `dialog.active` 一致

---

## 5. 交付项 5：设置页结构（搜索 + 缺失根 tab 禁用入口）

- **设置搜索**：`ui/settings/SettingsScreen.kt` 在导航列顶部加 `搜索设置` 输入框（上游 `settings/search.tsx` 的位置）；输入后导航列换成命中列表（页面 + 行 + 快捷键行），点命中跳到所属 tab。纯逻辑在 `searchSettings(query, titleOf)` / `searchCatalogTitleIds()`，JVM 可测（`searchFindsPagesAndRowsAndFiltersOutNonMatches`）
- **缺失根 tab = 明确禁用入口**：`SETTINGS_NAV` 重构为「4 组 × `SettingsNavItem`（真实 tab 或禁用项）」，按上游顺序 `.general, appearance*, notifications*, shortcuts | projects*, workspaces*, providers, models, extensions*, servers | experimental* | about*`（`settings/shell.tsx:39-68,218-246`，`settings/pages.ts:5-18`）；禁用项灰字 + 「未提供」标签、**不可点**，不再有空壳
  - 同时去掉 Android 独有的两个分组标题（上游 root/trailing 组无标题，`audit_settings_palette.md` A-1）
  - 不画图标：sprite 名（appearance/notifications/folder/…）不在 `SettingsIcon` 里，画错图标比不画更糟（登记为偏差）
- 设备证据 `w4d_settings.png`：搜索框 + `通用/外观 未提供/通知 未提供/快捷键/项目 未提供/工作区 未提供/提供商/模型/扩展 未提供/服务器/实验性功能 未提供/关于 未提供`，右侧 General 只剩活设置项

---

## 6. 交付项 6：误导注释与 `show` 语义

- `ui/App.kt` 文件头注释：删除「上游设置是 `Dialog size="x-large" variant="settings"` / 设置不再是路由」的错误表述，改为**上游 `/settings` 是真实路由 + 全屏 surface**（`routes.tsx:38` → `settings/shell.tsx:70`，`settings.css:8-27`），并说明 native 仍以 dialog 层承载（结构性偏差，登记在案），`/settings` 路由保留为深链入口
- `ui/overlay/DialogHost.kt`：`DialogController.show` 语义改为**先清栈再挂载**（上游 `dialog.tsx:140-149`；`push` 才是叠层），旧实现等同 `push`；注释同步纠正，并注明设置不是上游 dialog

---

## 7. 构建与测试

```
$env:JAVA_HOME='C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1'
.\gradlew.bat :app:testDebugUnitTest      # BUILD SUCCESSFUL, 628 tests, 0 failed
.\gradlew.bat :app:assembleDebug          # BUILD SUCCESSFUL
```

新增/更新测试：

- 新增 `ShellWiringTest.kt`（21 个用例）：命令注册表逐行可执行、空查询 5 条可用、`fileTree` 门控、keybind 与上游一致、`globalChord` 全量映射（含「修饰键必须严格相等」「KeyUp 不触发」）、会话行 meta/open、`steppedSessionId` 环绕、`FILES_ONLY`/`HOME` 宿主策略、provider/model 投影与搜索、上下文格式化、设置搜索、`TimelineDisplayPrefs` 默认值
- 更新 `SettingsStructureTest.kt`：导航形状（4 组 + 7 个禁用项）、General 面板顺序、隐藏清单、快捷键分组/键位、`BOOLEAN_KEYS` 覆盖规则
- 更新 `SettingsPaletteModelTest.kt`：`mod+shift+k` 不再开面板

设备命令（平板）：

```
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 reverse tcp:4096 tcp:4096
adb -s emulator-5554 shell am force-stop com.xiufeigo.opencodeshell
adb -s emulator-5554 shell monkey -p com.xiufeigo.opencodeshell 1
adb -s emulator-5554 shell screencap -p /sdcard/x.png && adb -s emulator-5554 pull /sdcard/x.png <名字>.png
# 设置：tap 400 1372（Home 左下「设置」）→ 导航「提供商」tap 400 720 /「模型」tap 400 801
# 面板：input keycombination 113 39（Ctrl+K）
```

截图（仓库根目录）：`w4d_home.png`、`w4d_settings.png`、`w4d_providers.png`、`w4d_models.png`、`w4d_palette.png`（Home 宿主）、`w4d_k2.png`（会话宿主）、`w4d_ctrl_n.png`、`w4d_terminal.png`、`w4d_more_menu.png`、`w4d_narrow.png`；无障碍树证据 `w4d_dump_before.xml` / `w4d_dump_after.xml` / `w4d_dump_narrow.xml`。

---

## 8. Lead 裁决：会话页浮动终端 chip 的宽度门控（已落地）

**问题（Lead 转述 session-fidelity 实测）**：`TerminalEntryButton` 压在 `SessionHeader` 的 ⋮ 上，`uiautomator dump` 里 `oc_more_options` 完全查不到；窄屏又有 mobile-form 的「终端」tab，入口重复。

**修法（`ui/App.kt`）**

```kotlin
if (LocalConfiguration.current.screenWidthDp >= SESSION_CHIP_MIN_WIDTH_DP) {   // 768
    TerminalEntryButton(
        onClick = onTerminalToggle,
        modifier = Modifier.align(Alignment.TopEnd).padding(top = SESSION_CHIP_TOP_DP, end = 8.dp),  // 88dp
    )
}
```

- **窄屏不渲染**：768dp 与上游 `isDesktop()`（`session/files/session-side-panel.tsx:87`）同阈值；窄屏终端入口归 mobile-form 的「终端」tab
- **宽屏下移**：`top` 由 52dp → 88dp，避开整条 0–80dp 页头带，⋮ 不再被覆盖（否则宽屏仍然挡住）

**证据（同一台 5554）**

| 证据 | 结论 |
|---|---|
| `w4d_dump_before.xml` | 修复前：`打开终端` 在 `[2500,116][2532,148]`，⋮ 按钮 `[2472,80][2552,160]` 的 content-desc 被盖掉 → dump 里**没有** `更多选项` |
| `w4d_dump_after.xml` | 修复后：`更多选项` 出现在 `[2496,104][2528,136]`，`打开终端` 下移到 `[2500,188][2532,220]`，两者矩形不相交 |
| `w4d_more_menu.png` | 点 ⋮（tap 2512 120）弹出菜单：`重命名` 可用、`删除` 禁用 → **⋮ 可点** |
| `w4d_narrow.png` + `w4d_dump_narrow.xml` | `wm size 1080x2152`（540dp < 768）：dump 里**没有** `打开终端` chip，页面出现 mobile-form 的四 tab（会话/变更/文件/**终端**），⋮ 仍在 `[1016,104][1048,136]` |

复现命令：

```powershell
adb -s emulator-5554 shell input tap 1150 437          # 打开会话
adb -s emulator-5554 shell uiautomator dump /sdcard/dump.xml
adb -s emulator-5554 shell input tap 2512 120          # ⋮
adb -s emulator-5554 shell wm size 1080x2152           # 窄屏复核（之后 wm size reset 复原）
```

---

## 9. 偏差与未决项（如实登记）

1. **设置仍是 dialog 层而非路由 surface**：`/settings` 是真实路由（`ui/App.kt` `composable(Route.SETTINGS)`）但渲染成 `DialogSize.X_LARGE` 上的 `SettingsDialogContent`。改为全屏 surface 需要重排 `AppScaffold` 与 `DialogHost` 的关系（超出本轮外壳接线范围），本轮只把注释与事实对齐。同时 :`DialogSize` 仍无 `NORMAL`（480×368）档，`variant="settings"` 无对应概念（`audit_routes.md` B-1）。
2. **`workspace.new` 语义近似**：Android 无 worktree store，「新建工作区」= 选目录 → 草稿页。上游该 id 在 v2 checkout 里**根本没有注册点**（只在 `COMMON_COMMAND_IDS`），所以任何实现都是补注册。
3. **禁用根 tab 无图标**：见 §5。
4. **`showFileTree` 默认值仍为 `true`**（上游 `false`）：改成 false 会让文件树入口默认消失，属可见回退，故保留 Android 默认并在此登记。
5. **弹窗打开时 Esc 不生效**（无焦点时）：`DialogLayer` 的 `onPreviewKeyEvent` 只在「焦点在弹窗子树内」时收到按键；设置层（无文本焦点）按 Esc 不会关闭，需点关闭按钮/返回。上游是 document 级捕获。修法（给 layer 加 focusRequester）会改动弹窗焦点竞争（palette 搜索框自动聚焦），本轮未做，留给 M6。
6. **未实现的上游面板行为**（不在本轮 6 项内）：`showSearch` 的标题栏按钮（无标题栏）、最近文件数据源（`recentFiles` 恒空）、编辑态重绑定快捷键、设置搜索的「跳到行并高亮」（当前只跳 tab）。
7. **软键盘弹出时的 chord 未复现确认（可疑点，留给 Lead 复核）**：实测中「先在输入框里打过字、软键盘在屏幕上」之后，一次 `Ctrl+K` / `Ctrl+P` 没有打开面板；而在冷启动（无文本焦点）时 `Ctrl+K` / `Ctrl+N` / `Ctrl+`` 全部生效。该次采集中途 adb daemon 崩溃、且 mobile-form 同时在用这台 5554，**无法归因**是 IME 吞键、焦点归属还是环境噪声。理论路径：`GlobalKeybindFocus` 是 NavHost 的祖先，`onPreviewKeyEvent` 在子节点获焦时仍应先收到事件；若复核确认吞键，需要在 `DialogLayer`/根节点上做焦点接管（属 M6 焦点模型）。
8. **设备采集环境说明**：`adb reverse tcp:4096 tcp:4096` 在 adb daemon 重启后会失效（重启 daemon 后必须重加，否则 `/provider`、`/session`、`/find/file` 全部失败，面板会显示空态）；采集期间 5554 由多人共用（曾出现前后台被他人切换、app 被 Back 退出到桌面），最终验收请在安静环境复跑。
