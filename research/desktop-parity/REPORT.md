# OpenCode Desktop 与 Android 差异及实现记录

基准：官方 v2 的 OpenCode **2.0.26**，commit `ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4`。Android **2.0.10 / versionCode 38**。用户选择 Android 连接电脑或服务器的 OpenCode，并要求内置浏览器自动化。

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

## 验证与证据

自动化检查按单元测试、类型检查、构建、冒烟顺序执行：

- Android 宿主单测 **249 项通过**；官方 app/GUI 生产源码类型检查通过；debug/release APK 构建通过。
- Android RPC、服务器迁移、触屏与终端生命周期 JS 单测 **16 项通过**；companion 认证、远程端点和帧协议单测 **3 项通过**。
- 官方浏览器连接、文件状态和导航权限单测 **19 项通过**。
- 完整打包 SPA 导航 **16/16**、面板拖动 **10/10**、终端发布回归 **51/51**。这些使用 Chromium 与模拟桥，不冒充设备测试。
- 实际 API 35 Android WebView：终端 **22/22**；平板面板拖动 **7/7**；原生文件保存、剪贴板与子框架隔离已验证。
- 真实 OpenCode + 生产启动器的浏览器回归 **15/15**：注册、网络隧道、JPEG、evaluate、snapshot、frames、console、network、点击、中文输入、截图传回服务器、权限边界、取消、CPU、Lighthouse 与堆快照。
- 实际 API 35 手机浏览器 **6/6**：网页画面、ADB 点击、中文输入事件、ADB 滑动、页面重载恢复、无页面异常。菜单准备使用 DOM 点击，中文插入使用 CDP InputEvent；不冒充实际软键盘的端到端输入，也不声称覆盖所有网站或全部工具分支。

最终浏览器引擎报告：`research/tmp/verify-android-browser/2026-10-09T08-18-07-715Z/report.json`；实际手机报告与截图：`research/tmp/verify-android-browser/2026-10-09T08-16-16-344Z-device/`。普通隐藏窗口的最小复现返回 `Current display surface not available for capture`，相同流程启用离屏渲染后连续两次重建与截图成功。

本机构建 APK：`webapp/build/outputs/apk/release/webapp-release.apk`，57,607,944 字节；SHA-256 `3FFD58E336A2037BD9D0B51EA70879DBF15AE7FF3601ACB5E464B3BE20750930`。包名/版本/签名验证通过。此包使用本机 debug keystore 签名，与现有 GitHub CI 的签名不同，不能直接覆盖其已安装版本；CI 使用原有共享签名配置。设备浏览器验证运行在同源代码的 debug APK。

发布前补充检查固定构建检出的 LF 行尾，并恢复旧 Windows 检出中被 CRLF 转换的源文件，避免同一 commit 在 Linux CI 生成不同资源。规范行尾后官方类型检查、APK 构建和导航 16 项回归再次通过；终端 51 项及拖动 10 项回归通过。

CI 首次完整构建还发现 Workbox 按文件系统遍历顺序生成缓存清单。固定 URL 排序后，确认 Windows/Linux 的 1,616 个缓存条目内容与完整性校验相同、Service Worker 运行代码未变；两次源码构建逐字节一致，APK 中的缓存清单与源码构建一致。行尾检查仅扫描稀疏检出的源码，避免读取被排除的 blob 导致逐个网络下载。

本轮具体报告保存在 `research/tmp/verify-android-*` 和 `_scratch_root/desktop-parity` 的本地测试目录。可复现入口：根 README、`browser-companion/smoke.mjs`、`scripts/verify-android-navigation.mjs`、`scripts/verify-android-resize.mjs`、`scripts/verify-android-terminal-release.mjs` 及设备验证脚本。CI 增加固定官方 UI 和 companion 的构建检查；PR/main 构建只读，tag 发布获得仓库写权限，两者复用同一构建工作流。签名包直接上传 GitHub Release，避免仓库已遇到的 Actions 产物存储配额限制；发布失败会明确失败。

## 仍存在的差异

当前不是所有 Desktop 平台能力的 100% 移植。第三方 GUI 扩展的本地安装/启停管理、SSH/WSL sidecar 生命周期、电脑本地文件应用、原生菜单、多窗口与 Electron 更新器未移植；Android 平台替代系统文件选择、通知与外链。内置浏览器手动 pinch/长按拖拽尚无适配；自动化 drag 等动作仍由官方 CDP 工具执行。浏览器服务目前需运行在有图形环境的电脑。

模型生成与付费提供商调用没有作为自动测试执行，因此“复用官方业务代码”不能等同于“逐一实测所有模型/代理/插件”。硬件相机扫码、系统通知投递、实体手机网络和不同厂商 WebView 仍需目标设备验收。这些边界不能用假成功或普通外部浏览器掩盖。

## 来源

- [官方 Desktop 入口](https://github.com/anomalyco/opencode/tree/v2/packages/desktop)
- [固定版本共享 app](https://github.com/anomalyco/opencode/tree/ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4/packages/app)
- [固定版本 GUI 扩展](https://github.com/anomalyco/opencode/tree/ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4/packages/gui-extensions)
- [官方浏览器工具合同](https://github.com/anomalyco/opencode/blob/ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4/packages/plugin-browser/src/rpc.ts)
- [Electron 离屏渲染](https://www.electronjs.org/docs/latest/tutorial/offscreen-rendering)
