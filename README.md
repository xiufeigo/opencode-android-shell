# OpenCode Android

Android 客户端连接电脑或服务器上的 OpenCode，直接使用 OpenCode Desktop 共享的官方 `packages/app` 界面。当前产品模块是 `:webapp`，入口是 `WebHostActivity`；`app/` 中的 Compose 实现保留为历史代码，不参与发布。

官方 UI 固定为 **2.2.0**，提交 `1097e2668b1e08ad63bdf0b1c4860b9bc01e420c`。唯一构建基准见 [frontend/android/upstream.json](frontend/android/upstream.json)。Android APK 版本为 **2.0.26 / 54**；包名 `com.xiufeigo.opencodeshell`，Android 8.0 起支持，目标 SDK 35。

## 与 Desktop 对齐

会话、流式消息、权限与表单、模型及代理选择、附件、工作区文件、差异审查、设置、主题、语言和扩展的前端直接来自同一官方源码。Android 适配提供系统文件选择与 Markdown 保存、外链、剪贴板、通知、二维码相机、触屏面板和终端输入。

服务端浏览器通过 [OpenCode v2 插件](plugins/server-browser/README.md)提供：安装一次后，Android 和本仓库的[适配 Web](frontend/web/README.md)可操作同一会话的 Chromium。保持原服务地址、认证和端口，不需要 gateway 或配对。安装插件不会自动修改官方客户端的可视界面；未改造的客户端可通过模型使用浏览器工具。完整对比和剩余差异见 [research/desktop-parity/REPORT.md](research/desktop-parity/REPORT.md)。

电脑本地引擎启动、WSL、桌面更新器和电脑文件系统不在 Android 宿主内运行；工作区和终端由连接的 OpenCode 服务提供。当前 UI 使用 v2 服务协议。

终端按服务器、工作目录和会话分别记忆标签、顺序及当前选中的 PTY。重新进入会话并打开终端，会连接原进程；App 被强杀期间命令继续由服务器执行，回来后重放服务端保留的输出，不自动重跑命令。手机端仅缩窄滚动条滑块的视觉宽度，保留 xterm 的触摸命中区和列宽计算。OpenCode 服务必须保持运行；关闭终端标签会结束对应进程。

Android 系统返回键先关闭弹窗、菜单或手机抽屉，设置按当前层级返回，会话页返回首页，首页交回系统离开 App。返回键不回放 WebView 历史，因此不会重新打开已关闭的会话标签；重新打开需要显式使用“重新打开已关闭的标签”。本地 UI 来源由进程统一持有，退出后立即重开不会争抢端口。

## 构建

需要 Node.js 22+、Bun **1.4.4**、Android SDK 35，以及 Gradle 支持的 JDK 17 或 21。Windows 建议使用 Android Studio 的 JBR。

```sh
cd frontend/terminal
npm ci
npm test
npm run build
cd ../..
node frontend/android/build.mjs
./gradlew :webapp:testDebugUnitTest :webapp:assembleRelease
```

Windows 使用 `gradlew.bat`。如果 PATH 中的 Bun 不是 1.4.4，通过 `BUN_BINARY` 指定对应可执行文件。源码构建在 `_scratch_root/desktop-parity/checkout` 的独立临时检出进行，不修改开发者的 `_upstream/`。补丁或类型检查失败会中止，只有构建成功才替换 APK 内嵌 UI。

输出：`webapp/build/outputs/apk/release/webapp-release.apk`。调试设备使用 `:webapp:assembleDebug`。本机按 [AGENTS.md](AGENTS.md) 使用用户提供、与 CI 相同的签名密钥；仅通过本次构建进程的 `DEBUG_KEYSTORE_PATH` 等环境变量传入凭据。

## 连接服务

在电脑或服务器启动 OpenCode v2 服务，在 Android 官方连接页使用配对链接，或选择“使用密码”填写服务器 URL 和认证信息。服务器添加、编辑、切换及凭据由官方 UI 管理。添加后进入该服务器设置，导航中的“＋”可继续添加；手机在设置顶部、平板在侧栏。服务器数量没有两台上限，UI 层级随单台/多台改变。

模拟器可用 `adb reverse tcp:4096 tcp:4096` 访问电脑本机的 4096 服务，再填写 `http://127.0.0.1:4096`。实体手机应使用手机能访问的电脑或服务器地址。

APK 自带 UI 由 `http://127.0.0.1:4173` 提供；该端点只服务静态文件，拒绝 `/api/*`，API 和 PTY 由页面直连所选服务。端口绑定失败显示明确诊断，禁止加载其他进程提供的页面。

## 验证

左上角复用官方应用菜单：文件、编辑、视图、转到、窗口、帮助。编辑接系统剪贴板，视图提供缩放、重载与 Android 全屏，窗口可最小化或关闭 Android 任务；新桌面窗口、最大化与 Electron 开发者工具明确禁用。右侧审查栏“＋”提供“打开文件／浏览器”；手机使用“更多 → 浏览器”，外接键盘支持 Ctrl+Shift+B。未安装插件时显示明确错误及安装提示。升级继续清除旧 gateway 配对凭据，保留服务器设置。

```sh
node scripts/verify-android-navigation.mjs
node scripts/verify-android-back.mjs
node scripts/verify-android-file-picker.mjs
node scripts/verify-android-resize.mjs
node scripts/verify-android-terminal-release.mjs
```

导航、拖动与终端脚本运行完整打包 SPA，使用测试后端和 Chromium 的可信触摸输入，属于浏览器模拟测试。文件选择脚本覆盖顺序读取、超过 1 MiB 的文件、取消和回调失败。`verify-android-terminal-device.mjs` 和 `verify-android-resize-device.mjs` 使用实际 APK、Android WebView、系统 IME 和 ADB 输入；需配置 `ANDROID_SERIAL`，只在自己的测试设备上运行。

`verify-android-back.mjs` 默认验证手机和平板的打包 SPA；设置 `ANDROID_SERIAL` 后安装并核对本次 debug APK，在指定测试设备上按真实系统返回键，检查关闭的标签不复活、首页退出及立即重开。该测试使用自己的隔离后端，不清除应用数据。

`verify-android-navigation-device.mjs` 在明确选定的独立测试设备、全新 debug APK 数据上验证连续添加五台服务器与强制停止/重启恢复，直接操作打包 WebView 的真实 UI，使用五个测试后端。设置 `ANDROID_SERIAL` 和可选 `ADB`；脚本不清除应用数据。逐项 UI 对照见 [UI-AUDIT.md](research/desktop-parity/UI-AUDIT.md)。

构建说明：[frontend/android/README.md](frontend/android/README.md)；终端行为：[frontend/terminal/README.md](frontend/terminal/README.md)。CI 构建固定 UI，检查生成资产与提交内容一致，运行 Android 及真实 Chromium 单测，发布 `:webapp` 的 release APK、服务端插件 ZIP 和适配 Web ZIP。

`verify-android-menus-device.mjs` 验证真实 APK 的应用菜单、原生剪贴板、全屏、宽屏加号菜单，以及浏览器入口不使用旧配对。仅在独立模拟器上运行；设置 `MENU_TEST_PROFILE=phone` 或 `tablet`、对应屏幕的 `ANDROID_SERIAL`、可选 `ADB` 与 `MENU_TEST_SERVER_CONFIG`（自己的 OpenCode `{url,password}` 文件）。不输出凭据，创建并清理自己的测试会话。

服务端浏览器验收见 [SERVER-BROWSER.md](research/desktop-parity/SERVER-BROWSER.md)。`scripts/start-server-browser-test.mjs` 启动隔离的真实 OpenCode 测试服务；`verify-server-browser.mjs` 验证认证与 RPC；`verify-server-browser-ui.mjs` 验证适配 Web，配置 `ANDROID_SERIAL` 则验证实际 debug APK 的点击、中文输入和强杀重连。

## 安全边界

原生平台消息仅允许成功启动的本地 UI 主文档；远程后端和页面子框架不能调用平台桥或申请客户端相机。相机和通知使用 Android 运行时权限。HTTPS 使用系统 CA，HTTP 连接沿用项目已确认的全局明文策略。服务端凭据由官方 UI 保存和发送，不经过本地静态服务器。

剪贴板图片内联限制为 20 MiB，与官方输入框的内联附件预算一致；超限明确失败，可改用文件选择器上传。普通附件选择不受旧桥的 1 MiB 内联读取限制。

## 目录

- `webapp/`：发布的 Android 宿主与打包 UI。
- `frontend/android/`：官方源码版本、源码补丁、平台接口和构建工具。
- `frontend/terminal/`：固定 xterm 终端适配及测试。
- `frontend/embedded-ui/`：服务器迁移和触屏拖动的共享逻辑。
- `plugins/server-browser/`：OpenCode 服务端 Chromium 插件与共享 RPC 契约。
- `frontend/remote-browser/`、`frontend/web/`：Android/Web 共用浏览器面板与 Web 分发。
- `app/`、`frontend/patches/`、`frontend/OPENCODE_APP_VERSION`：旧原生/旧 WebView 方案，不驱动当前发布。
- `research/desktop-parity/`：本轮对比、实现范围和验证记录。

MIT。复用的 OpenCode 源码遵循其仓库许可证。
