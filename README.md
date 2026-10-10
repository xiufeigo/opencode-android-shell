# OpenCode Android

Android 客户端连接电脑或服务器上的 OpenCode，直接使用 OpenCode Desktop 共享的官方 `packages/app` 界面。当前产品模块是 `:webapp`，入口是 `WebHostActivity`；`app/` 中的 Compose 实现保留为历史代码，不参与发布。

官方 UI 固定为 **2.0.26**，提交 `ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4`。唯一构建基准见 [frontend/android/upstream.json](frontend/android/upstream.json)。Android APK 版本为 **2.0.13 / 41**；包名 `com.xiufeigo.opencodeshell`，Android 8.0 起支持，目标 SDK 35。

## 与 Desktop 对齐

会话、流式消息、权限与表单、模型及代理选择、附件、工作区文件、差异审查、设置、主题、语言和扩展的前端直接来自同一官方源码。Android 适配提供系统文件选择与 Markdown 保存、外链、剪贴板、通知、二维码相机、触屏面板和终端输入。

内置浏览器复用官方 Electron/CDP 执行引擎，通过电脑上的 `browser-companion` 向 Android 传输画面和输入。手机提供官方地址栏、标签、检查器与评论；自动化继续使用官方 browser 工具和服务端网络隧道。统一网关让浏览器与 API 共用服务器地址和认证，点击后直接打开官方面板；需要在电脑部署配套服务，APK 本身不执行 Electron。完整对比、验证范围和剩余差异见 [research/desktop-parity/REPORT.md](research/desktop-parity/REPORT.md)。

电脑本地引擎启动、WSL、桌面更新器和电脑文件系统不在 Android 宿主内运行；工作区和终端由连接的 OpenCode 服务提供。当前 UI 使用 v2 服务协议。

## 构建

需要 Node.js 22+、Bun **1.4.2**、Android SDK 35，以及 Gradle 支持的 JDK 17 或 21。Windows 建议使用 Android Studio 的 JBR。

```sh
cd frontend/terminal
npm ci
npm test
npm run build
cd ../..
node frontend/android/build.mjs
./gradlew :webapp:testDebugUnitTest :webapp:assembleRelease
```

Windows 使用 `gradlew.bat`。如果 PATH 中的 Bun 不是 1.4.2，通过 `BUN_BINARY` 指定对应可执行文件。源码构建在 `_scratch_root/desktop-parity/checkout` 的独立临时检出进行，不修改开发者的 `_upstream/`。补丁或类型检查失败会中止，只有构建成功才替换 APK 内嵌 UI。

输出：`webapp/build/outputs/apk/release/webapp-release.apk`。调试设备使用 `:webapp:assembleDebug`。本机按 [AGENTS.md](AGENTS.md) 使用用户提供、与 CI 相同的签名密钥；仅通过本次构建进程的 `DEBUG_KEYSTORE_PATH` 等环境变量传入凭据。

## 连接服务

在电脑或服务器启动 OpenCode v2 服务，在 Android 官方连接页使用配对链接，或选择“使用密码”填写服务器 URL 和认证信息。服务器添加、编辑、切换及凭据由官方 UI 管理。添加后进入该服务器设置，导航中的“＋”可继续添加；手机在设置顶部、平板在侧栏。服务器数量没有两台上限，UI 层级随单台/多台改变。

模拟器可用 `adb reverse tcp:4096 tcp:4096` 访问电脑本机的 4096 服务，再填写 `http://127.0.0.1:4096`。实体手机应使用手机能访问的电脑或服务器地址。

APK 自带 UI 由 `http://127.0.0.1:4173` 提供；该端点只服务静态文件，拒绝 `/api/*`，API 和 PTY 由页面直连所选服务。端口绑定失败显示明确诊断，禁止加载其他进程提供的页面。

## 连接内置浏览器

从本项目 GitHub Release 下载配套 `opencode-browser-gateway-2.0.13.tar.gz`，在运行 OpenCode 的电脑解压，执行：

```sh
npm ci
```

PowerShell 启动统一服务：

```powershell
$env:OPENCODE_BROWSER_UPSTREAM = 'http://127.0.0.1:4096'
$env:OPENCODE_BROWSER_HOST = '0.0.0.0'
npm start
```

Android 只添加 `http://电脑地址:4100`，使用原 OpenCode 密码。浏览器、会话和终端共用这个地址；侧栏“＋ → 浏览器”、手机“更多 → 浏览器”、Ctrl+Shift+B 直接打开官方 New tab 和地址栏。多台服务器分别部署网关、分别添加即可。普通 OpenCode 服务没有远程浏览器执行端，未启用时会显示明确错误。

HTTP/WS 会明文传输服务器认证信息和短时票据；HTTPS/WSS 可由反向代理提供。

网页执行在浏览器服务的 Chromium 中，网络经官方隧道到所选 OpenCode 服务，网页的 localhost 指向后者。服务需要 Electron 图形环境；Linux/HTTPS 部署和旧配对兼容见 [browser-companion/README.md](browser-companion/README.md)。

## 验证

左上角复用官方应用菜单：文件、编辑、视图、转到、窗口、帮助。编辑接系统剪贴板，视图提供缩放、重载与 Android 全屏，窗口可最小化或关闭 Android 任务；新桌面窗口、最大化与 Electron 开发者工具明确禁用。右侧审查栏“＋”保留打开文件和浏览器菜单；使用所选服务器自动连接后打开官方浏览器面板，旧配对仅保留在兼容设置。

```sh
node scripts/verify-android-navigation.mjs
node scripts/verify-android-file-picker.mjs
node scripts/verify-android-resize.mjs
node scripts/verify-android-terminal-release.mjs
```

导航、拖动与终端脚本运行完整打包 SPA，使用测试后端和 Chromium 的可信触摸输入，属于浏览器模拟测试。文件选择脚本覆盖顺序读取、超过 1 MiB 的文件、取消和回调失败。`verify-android-terminal-device.mjs` 和 `verify-android-resize-device.mjs` 使用实际 APK、Android WebView、系统 IME 和 ADB 输入；需配置 `ANDROID_SERIAL`，只在自己的测试设备上运行。

`verify-android-navigation-device.mjs` 在明确选定的独立测试设备、全新 debug APK 数据上验证连续添加五台服务器与强制停止/重启恢复，直接操作打包 WebView 的真实 UI，使用五个测试后端。设置 `ANDROID_SERIAL` 和可选 `ADB`；脚本不清除应用数据。逐项 UI 对照见 [UI-AUDIT.md](research/desktop-parity/UI-AUDIT.md)。

`scripts/verify-android-browser-device.mjs` 验证已配对的实际 APK：网页画面、ADB 点击/滑动、中文输入、页面重载恢复。需先在独立测试会话连接真实 OpenCode 和浏览器服务，将 WebView CDP 转发到 `DEVICE_CDP`，并设置 `ANDROID_SERIAL` 和可选 `ADB`。菜单准备使用 DOM 点击，中文输入使用 CDP InputEvent；这两项不代表实际软键盘端到端输入。

构建说明：[frontend/android/README.md](frontend/android/README.md)；终端行为：[frontend/terminal/README.md](frontend/terminal/README.md)。CI 构建固定 UI，检查生成资产与提交内容一致，运行 Android 单测，只发布 `:webapp` 的 release APK。

`verify-android-menus-device.mjs` 验证真实 APK 的应用菜单、原生剪贴板、全屏、宽屏加号菜单、免二次配对打开浏览器、导航与 ADB 网页点击/滑动。仅在独立模拟器上运行；设置 `MENU_TEST_PROFILE=phone` 或 `tablet`、对应屏幕的 `ANDROID_SERIAL`、可选 `ADB` 与 `MENU_TEST_SERVER_CONFIG`（自己的统一网关 `{url,password}` 文件）。脚本通过兼容设置清除该测试设备的旧配对，不重置服务器数据；不输出凭据，创建并清理自己的测试会话。

## 安全边界

原生平台消息仅允许成功启动的本地 UI 主文档；远程后端和页面子框架不能调用平台桥或申请客户端相机。相机和通知使用 Android 运行时权限。HTTPS 使用系统 CA，HTTP 连接沿用项目已确认的全局明文策略。服务端凭据由官方 UI 保存和发送，不经过本地静态服务器。

剪贴板图片内联限制为 20 MiB，与官方输入框的内联附件预算一致；超限明确失败，可改用文件选择器上传。普通附件选择不受旧桥的 1 MiB 内联读取限制。

## 目录

- `webapp/`：发布的 Android 宿主与打包 UI。
- `frontend/android/`：官方源码版本、源码补丁、平台接口和构建工具。
- `frontend/terminal/`：固定 xterm 终端适配及测试。
- `browser-companion/`：官方浏览器执行引擎、统一网关和远程画面传输。
- `frontend/embedded-ui/`：服务器迁移和触屏拖动的共享逻辑。
- `app/`、`frontend/patches/`、`frontend/OPENCODE_APP_VERSION`：旧原生/旧 WebView 方案，不驱动当前发布。
- `research/desktop-parity/`：本轮对比、实现范围和验证记录。

MIT。复用的 OpenCode 源码遵循其仓库许可证。
