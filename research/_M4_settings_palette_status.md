# M4 · Settings 设置 + 命令面板 — 交付状态

**状态：完成（编译绿 + 单测绿）**
`gradlew --offline --rerun-tasks :app:assembleDebug :app:testDebugUnitTest` → **BUILD SUCCESSFUL**（56s，全量重跑）
APK `app/build/outputs/apk/debug/app-debug.apk`（≈29.9 MB）· **46 suites / 343 tests / 0 failures**
（新增 2 suite：`SettingsPaletteModelTest` 20 项、`SettingsStructureTest` 24 项）

规格依据：`PLAN-android-native-refactor.md` §4.3/§4.4/§1.3、`research/02_ui_screens.md` §5/§6、
`research/05_palette_host_session.md`（§0/§1/§2/§5）。

---

## 1. 交付文件

| 文件 | 内容 |
|---|---|
| `ui/overlay/DialogHost.kt` | **全局 dialog 栈**（§4.3/`05` §1.2）。`DialogController.show/close/closeAll`、`isActive`；层渲染顺序 = 旧→新，每层自带 scrim 且吞掉层外点击；`LocalDialogController` / `LocalDialogActive`；`Escape` 关闭顶层。 |
| `ui/settings/SettingsModel.kt` | **纯逻辑**：2 分组 5 tab、General 5 个 section + 21 行 `data-action`、配色/主题/字体行、通知/音效/高级、快捷键 GROUPS + `groupForCommandId`（逐字照抄 `settings-keybinds.tsx`）、服务器排序/过滤/meta。 |
| `ui/settings/SettingsIcon.kt` | 图标名枚举（`sliders/keyboard/server/providers/models`）。 |
| `ui/settings/SettingsScreen.kt` | `Dialog size=x-large variant=settings` 外壳：`min(100vw−32,980) × min(100vh−92,600)`；>639dp 左导航 240dp（`layer-01`、padding 12、gap 4、右侧 1px border）+ 底部 `OpenCode Desktop v…`；**≤639dp 换成顶部导航条 + 底部 1px 分隔线，隐藏 nav-footer**（照 vendored CSS `05` §5.3）。 |
| `ui/settings/SettingsComponents.kt` | `settings-v2` 原语：list（radius 8 / `layer-01` / `padding-inline:20` / 0.5dp inset 边框）、row（`padding-block:20` + `0.5px` 底边、末行无边框、`<640dp` 纵向堆叠）、`SelectV2`/`TextInputV2`/`Switch`/`KeybindV2` 替身、`Tag`、`Button`。 |
| `ui/settings/SettingsPanels.kt` | General（section 顺序已断言）、Shortcuts（6 组只读 + keybind chip，录制留占位）、Providers、Models。 |
| `ui/settings/ServersPanel.kt` | 服务器列表（健康点 + name + `v{ver} • username` + `Default` tag + ⋮ 菜单）、>1 台才出现搜索框、空态/无结果态、`dialog-server-v2` 添加/编辑对话框（480px、字段顺序 URL→名称→用户名/密码、11px danger 错误行、**Android CORS 提示保留**）。 |
| `ui/settings/SettingsServerBridge.kt` | `ServerBridge` 实现：读 `ServerConfigStore.listServers()/activeUrl()`，写 `replaceAll()`。 |
| `ui/palette/PaletteModel.kt` | **纯逻辑**：`PaletteEntry` 三型（`command|file|session`）+ id 前缀、`COMMON_COMMAND_IDS`、`ENTRY_LIMIT=5`、分组保序、`matchesEntry`、空查询装配、`moveIndex` 首尾循环、`mod+k / mod+shift+p`、`mod+comma`。 |
| `ui/palette/CommandPalette.kt` | 容器 `min(100vw−24,640)`、高 auto `min280/max480`、`margin-top max(48,(100vh−480)/2)`、radius 12、`elevation-floating`、居中偏上；搜索 36dp（`layer-02@60%`）；组标题；行高 36dp（≤640dp 纵向堆叠 48dp）；`Arrow↑↓/Enter/Escape`、首尾循环。 |
| `ui/palette/AppCommands.kt` | 命令注册表（`settings.open` 真动作；其余为 `COMMON_COMMAND_IDS` 行位）。 |
| `SettingsStore.kt`（根包） | 持久化：`opencode-theme-id` / `opencode-color-scheme` 等 web 同名字段 + Android 扩展键；`StateFlow<Settings>`；`Settings.isDark(systemDark)` 复刻 `oc-theme-preload.js`。 |
| `ui/App.kt` | `DialogHost` 包住导航树；Home 齿轮 / `Ctrl+,` / 面板 `settings.open` 三处都 `dialog.show`；`Ctrl+K`/`Ctrl+Shift+P` 推命令面板；`/settings` 路由改为「开弹窗后 pop」。 |
| `res/values/strings.xml` | M4 英文默认文案（约 90 条，字面量取自 `research/_i18n/en.json`）。 |

## 2. 关键实现决定（与规格的对应 / 偏差）

1. **宿主 = 全局 dialog 栈，非路由**（`05` §1/§2 定案）。会话内 Ctrl+K 亦走同一实例；
   Home/会话/new-session 三处共用 `CommandPalette`，仅数据装配与 placeholder 不同（home 无 file 类）。
2. **设置不再占路由**：`Route.SETTINGS` 保留为深链入口，进入即弹窗并 `popBackStack()`。
3. **`DialogController` 在弹窗层内部获取**：`AppScaffold` 位于 `DialogHost` 之内，所以
   `/` 、齿轮、键位回调共享同一 controller；`showSettingsDialog` / `showPaletteDialog` 是**普通函数**
   （接收 controller），因为它们在 `onPreviewKeyEvent` / `LaunchedEffect` 中被调用，不能读 CompositionLocal。
4. **持久化用 SharedPreferences 而非 DataStore（偏差，需知悉）**：本仓库无 `androidx.datastore`
   依赖且构建 offline，新增依赖会拉包失败。已改为 `oc_settings` 这个独立 prefs 文件 +
   `StateFlow`，**对外接口与 DataStore 版一致**（`state` / `update(action, value)`），
   换实现只需改这一个文件。键名与 Web 对齐（`opencode-theme-id` / `opencode-color-scheme`）。
5. **服务器写入范围（重要限制）**：`ServerConfigStore` 仍是唯一真源。M4 新增**只读** `listServers()` /
   `activeUrl()` 与写入用的 `replaceAll()`，并额外持久化原始 `{"list":…,"active":…}` JSON
   （新键 `list_json`，`restore()` 会回填）。**新增/编辑/删除只写 DataStore 镜像**——原生客户端立即生效，
   但 WebView `localStorage` 要等下一次 `syncServerConfig` 才同步；把改动**推回** WebView 需要新增 JS 钩子
   （现有 bridge 只有 `syncServerConfig` 入向方法），M4 未做，留给 WebView 退役前的收尾任务。
6. **Providers / Models** 目前是无网络只读列表（空态文案）；`SettingsPanelExtras` 已留
   `providers/models/loading` 入参，接 `provider.list` / `model.list` 只是填数据，UI 不需改。
7. **短键录制**为占位（显示 keybind + chip 样式 + `Unassigned`/`Press keys` 文案），
   录制交互与冲突 toast 未做（`02` §5.6 明确属后续）。
8. **服务器健康点**恒为 `unknown`（不发起 `checkServerHealth` 网络调用），排序仍按
   `当前置顶 → health rank → 稳定输入序` 实现并用单测锁定。

## 3. 并发风险提示（给父代理）

本工作区同时有 M3/M5 代理在改 `ui/App.kt`、`ui/home/*`、`ui/terminal/*`、`ui/nav/*`（文件在本次会话中反复被改写）。
交付前 App.kt 由本代理与 M5 代理交叉合并过，期间多次出现「A 方写坏、B 方修好」的循环。
**最终状态是绿的**（上方 BUILD SUCCESSFUL 为最后一次性 `--rerun-tasks` 全量构建），但若后续
再有代理改写 App.kt，需重跑：

```
$env:JAVA_HOME='...\jdk-21.0.12.1+1'
.\gradlew.bat --offline --rerun-tasks :app:assembleDebug :app:testDebugUnitTest
```

另外：`app/build/test-results/testDebugUnitTest/binary/output.bin` 会被并发 Gradle 测试执行器占用，
表现为 `Unable to delete directory`；重跑即可，不是代码问题。

## 4. 未做 / 后续

- 快捷键录制与冲突检测、`Reset to defaults`。
- Providers/Models 联网数据与 provider 连接对话框。
- `Mod+K` 之外的文件选择器 `mod+p` 分支（`DialogSelectFileDesktopV2` 系列）——M4 按 `05` §1.3 结论，
  会话内 Ctrl+P/Ctrl+K/Ctrl+Shift+P 三者等价于同一 palette，**已正确实现**；仅 `mode="files"` 入口（右侧面板文件树 `+`）属 M5。
- 设置项对终端/文件树的实际消费（`showFileTree`/`showSearch`/`terminal font`）由 M5/M6 读取 `SettingsStore`。
