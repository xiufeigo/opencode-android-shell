# OpenCode Desktop 与 Android 差异及实现记录

基准：官方 v2 的 OpenCode **2.0.26**，commit `ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4`。Android **2.0.12 / versionCode 40**。用户选择 Android 连接电脑或服务器的 OpenCode，并要求内置浏览器自动化。

Desktop 是 Electron 宿主，不是独立的一套会话界面：会话、工作区和设置来自 `packages/app`；浏览器与终端来自 `packages/gui-extensions`。Android 应复用这些模块，并补足平台接口。直接仿写桌面页面或持续修改压缩后的 JS 会产生第二套状态和升级负担，因此本轮选择官方源码构建。

## 差异与处理

| 能力 | 改造前的问题 | 当前结果 |
| --- | --- | --- |
| 官方前端 | 资产 2.0.22、参考源码及旧版本标记互不一致；依赖生成 chunk 名打补丁 | 唯一 commit pin、冻结依赖、官方 app/GUI 生产源码类型检查、源码补丁与打包元数据 |
| 会话与消息 | 壳层适配没有完整接入官方平台接口 | 直接保留官方会话、流式消息、权限表单、模型、代理与命令实现；未重新实现业务 API |
| 服务器与恢复 | 占位服务器迁移、默认选择和路由恢复容易相互干扰 | 迁移只删除资产占位，保留真实服务器及认证；官方持久化恢复路由 |
| 文件、差异、设置 | 自定义 UI/产物补丁容易偏离 Desktop | 直接使用官方工作区、文件、Review、设置、主题与语言组件 |
| Android 平台 | 官方 PlatformProvider 未调用原生文件保存和剪贴板接口 | 安全主文档消息通道、SAF 保存、系统外链、剪贴板文本/图片；取消与失败明确返回 |
| 终端与触屏 | 编译产物适配、旧实例恢复竞态、桌面拖动范围不适合触屏 | 保留验证过的 xterm 移动输入；源码对接官方终端；按 PTY 等待旧实例快照；触屏面板拖动 |
| 内置浏览器 | 没有 Electron 执行引擎，手机上的官方入口被平台和窄屏条件关闭 | 官方 browser pane/toolbar/model + Android 移动入口 + 认证 companion；官方 CDP 引擎、网络隧道和工具协议 |
| 浏览器截图 | 官方截图检查电脑窗口可见，后台服务窗口不可见 | 用 Android 面板的实际可见性注入宿主条件，仍要求目标 tab 可见 |
| 浏览器重载 | 隐藏的普通 Electron 窗口重建后没有可截图的显示表面 | 固定官方 Electron 44.4.5；远程宿主及 WebContentsView 显式使用离屏渲染，重载恢复设备实测通过 |
| 生命周期与安全 | 旧 Java 接口可达子框架、加密失败会退回明文、崩溃实例复用 | 主文档 capability + 独立加密平台通道；诊断页仅有重试能力；销毁旧实例并完整重建；显式失败 |

## 浏览器架构

`Android 官方面板 → Bridge WebSocket → Electron createBrowserPane → OpenCode browser RPC/网络隧道 → 网站`。

手机显示客户端画面、提供触屏与 IME；浏览器服务执行官方 action schema。使用真实 OpenCode 2.0.25 后端与固定 2.0.26 客户端完成验证。浏览器服务必须可达，不能声称 APK 在 Android 本地运行了 Electron 或 OpenCode 引擎。

桌面窗口逻辑适配远程宿主可见性、离屏渲染和命令 AbortSignal。不会把远程服务器错误标记为本地 sidecar，也不会授予电脑本地 `file://` 访问。服务的令牌只从文件或环境变量加载；服务端凭据来自官方服务器配置。

## 2.0.12 应用菜单与侧栏加号菜单

左上角以前只在 Windows/Linux 渲染官方应用菜单；Android 现在复用相同六组分类、翻译、命令和帮助链接。平台适配提供原生剪贴板、重载、UI 缩放、系统全屏、任务最小化及关闭；电脑新窗口、最大化、Electron 开发者工具明确禁用。编辑会恢复原字段焦点及选区，异步剪贴板读取期间内容改变则拒绝修改。

右侧“＋”过去在浏览器不可用时只剩文件项，官方组件因此退化为直接按钮。Android 浏览器扩展现在保持菜单项，菜单与快捷命令共用打开动作；未连接服务时调起真实原生配对框，成功后沿用官方注册握手打开面板。取消不产生面板，并发配置共用同一 Promise，失败显示真实状态；“断开”仍清除原生配对和传输。原生检查发现不能等待已有标签库存才打开首个标签，已移除这个多余门槛，由官方 connection 等待注册完成再发送命令。

- 官方设置/兼容入口单测 **9/9**；app/GUI 类型检查、固定 UI 构建通过。
- 完整打包 SPA 的手机、触屏平板导航/导入/菜单回归 **41/41**，包括六组菜单、设置命令、缩放复原、编辑选区与帮助链接，及实际展开侧栏“＋”、打开文件、请求浏览器配对和取消。
- Android 单测 **249/249**，debug/release 构建通过；使用用户指定密钥，签名证书 `760dc05fb4d1a125ed82cf9bff8eee622c7244d44b6ddc82a4c34e0b37b820e6`。

导航模拟报告：`research/tmp/verify-android-navigation/2026-10-09T14-13-45-901Z/report.json`。实际手机 **4/4**（540×1176）：菜单打开设置、原生复制粘贴保留选区、系统全屏隐藏/恢复系统栏、无 WebView 异常；报告为 `research/tmp/verify-android-menus/2026-10-09T15-22-22-554Z-device/report.json`。实际平板菜单 **3/3**：展开“＋”打开远程文件选择器、从未配对状态打开原生配对框并打开官方浏览器面板、无 WebView 异常；报告为 `research/tmp/verify-android-menus/2026-10-09T15-31-52-353Z-device/report.json`。

原生菜单验证入口为 `scripts/verify-android-menus-device.mjs`，使用独立 API 35 模拟器、真实 WebView、系统剪贴板/系统栏/配对框、真实 OpenCode v2 和生产 Electron companion；不将模拟桥调用记录表述为原生能力实测。两个原生配对字段具备无障碍标签；设备脚本按标签定位，不使用 AndroidSelector 不支持的字段索引，也不把不支持的等待选项当作弹窗关闭证据。

本轮 release 包 **57,620,384 字节**、SHA-256 `CFCA72ECFB5943629294E6611F2F1116234591B9BA372E83008616C120D5C0B6`，包名、2.0.12/40 版本及用户指定证书核验通过，包内 **1,688 个 UI 文件**与源码构建资产逐字节一致。

## 2.0.11 UI 修正与验证

完整逐项核对见 [UI-AUDIT.md](UI-AUDIT.md)。多服务器没有两台数据上限；问题在于新增后进入的服务器详情页缺少新增入口，同时根设置的数量变化 effect 抢写目标路由。现在在路由解析层处理旧根标签，根页与详情页共用新增动作；手机顶部与平板侧栏均可继续添加。侧栏原样式只在悬停时显示“＋”，触屏设备改为常显。

Android 平台身份从 `web` 改为明确的 `android`，接入会话列表的目录/分支显示和文件导入接口，同时保留移动标题栏安全区和扫码条件。真实 WebView 124 还暴露出官方连接代码直接调用缺失的 `URL.parse`，密码连接在发请求前失败；使用已有 core-js 依赖在入口兼容层补齐，没有替换配对校验或健康检查。

- 官方设置路由与缺失 URL.parse 的启动回归 **9/9**；app/GUI 类型检查及 UI 构建通过。
- 完整打包页面的手机与触屏平板导航、服务器管理、导入及 Review 回归 **32/32**；覆盖连续添加五台、逐台切换、修改、删除、刷新恢复、删除到一台再增至三台，及非法文件不发请求、超过 2 MiB 的文件按官方 schema 导入。
- 文件选择器的顺序读取、取消、异常拒绝及清理 **4/4**；终端完整发布回归 **51/51**。这些使用 Chromium，原生路径另列。
- 实际 API 35 / WebView **124.0.6367.219**，viewport **412×891**：五台连续添加、强停重启后恢复五台和所选第四台、无页面异常 **3/3**。服务为五个独立测试端点，页面通过 CDP 驱动，不冒充实体手机网络验证。
- 实际 Android SAF 选择中文 JSON 文件后，`File.text()` 完整读回 **2,097,175 字节**；保持 `allowContentAccess=false`。该最小原生检查通过注入当前文件选择器函数验证系统选择与读取路径，官方菜单、schema 与导入 API 的完整组合由上述 Chromium 导入回归覆盖，不表述为整条原生导入 UI 已端到端实测。

本轮页面报告：`research/tmp/verify-android-navigation/2026-10-09T11-54-49-908Z/report.json`；设备报告：`research/tmp/verify-android-navigation/2026-10-09T11-57-48-544Z-device/report.json`；终端报告：`research/tmp/verify-android-terminal-release-ready/2026-10-09T11-00-51-071Z-63d896/report.json`。重启测试先确认 WebView localStorage 已写入磁盘，再强停；此前只看 JavaScript 中的值就立即杀进程，会把异步写盘误判为恢复错误，没有为此增加第二套配置或初始化补丁。

本轮本地 debug/release 使用用户指定密钥，证书与 CI 保持一致。release 包为 **57,618,328 字节**、SHA-256 `BA009381DA0A80D5869EF13D4AC304AC10EAFC4376C5BE2F9571662787A5D5C7`；包名、2.0.11/39 版本、签名核验通过，包内 **1,688 个 UI 文件**与源码构建资产逐字节相同。CI 构建还会独立检查冻结依赖及提交资源一致性。

## 2.0.10 已有能力的验证与证据

自动化检查按单元测试、类型检查、构建、冒烟顺序执行：

- Android 宿主单测 **249 项通过**；官方 app/GUI 生产源码类型检查通过；debug/release APK 构建通过。
- Android RPC、服务器迁移、触屏与终端生命周期 JS 单测 **16 项通过**；companion 认证、远程端点和帧协议单测 **3 项通过**。
- 官方浏览器连接、文件状态和导航权限单测 **19 项通过**。
- 完整打包 SPA 导航 **16/16**、面板拖动 **10/10**、终端发布回归 **51/51**。这些使用 Chromium 与模拟桥，不冒充设备测试。
- 实际 API 35 Android WebView：终端 **22/22**；平板面板拖动 **7/7**；原生文件保存、剪贴板与子框架隔离已验证。
- 真实 OpenCode + 生产启动器的浏览器回归 **15/15**：注册、网络隧道、JPEG、evaluate、snapshot、frames、console、network、点击、中文输入、截图传回服务器、权限边界、取消、CPU、Lighthouse 与堆快照。
- 实际 API 35 手机浏览器 **6/6**：网页画面、ADB 点击、中文输入事件、ADB 滑动、页面重载恢复、无页面异常。菜单准备使用 DOM 点击，中文插入使用 CDP InputEvent；不冒充实际软键盘的端到端输入，也不声称覆盖所有网站或全部工具分支。

最终浏览器引擎报告：`research/tmp/verify-android-browser/2026-10-09T08-18-07-715Z/report.json`；实际手机报告与截图：`research/tmp/verify-android-browser/2026-10-09T08-16-16-344Z-device/`。普通隐藏窗口的最小复现返回 `Current display surface not available for capture`，相同流程启用离屏渲染后连续两次重建与截图成功。

初次验收的本机构建 APK：`webapp/build/outputs/apk/release/webapp-release.apk`，57,607,944 字节；SHA-256 `3FFD58E336A2037BD9D0B51EA70879DBF15AE7FF3601ACB5E464B3BE20750930`。包名/版本/签名验证通过。该次构建使用本机默认 debug keystore 签名，与 GitHub CI 的签名不同。设备浏览器验证运行在同源代码的 debug APK。

用户随后提供 `C:/sensorsdata/main/packages/debug.keystore` 及其 ZIP 备份；二者内容一致，实际证书为 CI 使用的 `760dc05fb4d1a125ed82cf9bff8eee622c7244d44b6ddc82a4c34e0b37b820e6`。使用该密钥重新构建 release 并核验 APK 签名通过，后续本地包可覆盖升级 GitHub Release 版本。具体打包约定见根目录 `AGENTS.md`；密钥留在仓库外，凭据仅传入本次 Gradle 进程。

发布前补充检查固定构建检出的 LF 行尾，并恢复旧 Windows 检出中被 CRLF 转换的源文件，避免同一 commit 在 Linux CI 生成不同资源。规范行尾后官方类型检查、APK 构建和导航 16 项回归再次通过；终端 51 项及拖动 10 项回归通过。

CI 首次完整构建还发现 Workbox 按文件系统遍历顺序生成缓存清单。固定 URL 排序后，确认 Windows/Linux 的 1,616 个缓存条目内容与完整性校验相同、Service Worker 运行代码未变；两次源码构建逐字节一致，APK 中的缓存清单与源码构建一致。行尾检查仅扫描稀疏检出的源码，避免读取被排除的 blob 导致逐个网络下载。

本轮具体报告保存在 `research/tmp/verify-android-*` 和 `_scratch_root/desktop-parity` 的本地测试目录。可复现入口：根 README、`browser-companion/smoke.mjs`、`scripts/verify-android-navigation.mjs`、`scripts/verify-android-resize.mjs`、`scripts/verify-android-terminal-release.mjs` 及设备验证脚本。CI 增加固定官方 UI 和 companion 的构建检查；PR/main 构建只读，tag 发布获得仓库写权限，两者复用同一构建工作流。签名包直接上传 GitHub Release，避免仓库已遇到的 Actions 产物存储配额限制；发布失败会明确失败。

## 仍存在的差异

当前不是所有 Desktop 平台能力的 100% 移植。第三方 GUI 扩展的本地安装/启停管理、SSH/WSL sidecar 生命周期、电脑本地文件应用、多窗口与 Electron 更新器未移植；应用菜单已复用官方组件并接入 Android 动作，Android 平台替代系统文件选择、通知与外链。内置浏览器手动 pinch/长按拖拽尚无适配；自动化 drag 等动作仍由官方 CDP 工具执行。浏览器服务目前需运行在有图形环境的电脑。

模型生成与付费提供商调用没有作为自动测试执行，因此“复用官方业务代码”不能等同于“逐一实测所有模型/代理/插件”。硬件相机扫码、系统通知投递、实体手机网络和不同厂商 WebView 仍需目标设备验收。这些边界不能用假成功或普通外部浏览器掩盖。

## 来源

- [官方 Desktop 入口](https://github.com/anomalyco/opencode/tree/v2/packages/desktop)
- [固定版本共享 app](https://github.com/anomalyco/opencode/tree/ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4/packages/app)
- [固定版本 GUI 扩展](https://github.com/anomalyco/opencode/tree/ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4/packages/gui-extensions)
- [官方浏览器工具合同](https://github.com/anomalyco/opencode/blob/ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4/packages/plugin-browser/src/rpc.ts)
- [Electron 离屏渲染](https://www.electronjs.org/docs/latest/tutorial/offscreen-rendering)
