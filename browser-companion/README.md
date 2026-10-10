# OpenCode 统一浏览器服务

## 单次连接（推荐）

统一网关将现有 OpenCode API、SSE、PTY WebSocket 和内置浏览器放在同一地址，使用原 OpenCode 密码。Android 只添加这个地址，点击“浏览器”直接打开官方 New tab、地址栏和面板，无需再次配对。每台服务器部署一个网关，Android 可以添加多台。

需要 Node.js 22+、Electron 图形环境以及已经运行的 OpenCode v2 服务。Release 的 `opencode-browser-gateway-2.0.13.tar.gz` 已带引擎构建结果，解压后无需检出上游或构建 UI，只需 `npm ci`。PowerShell 启动：

```powershell
$env:OPENCODE_BROWSER_UPSTREAM = 'http://127.0.0.1:4096'
$env:OPENCODE_BROWSER_HOST = '0.0.0.0'
npm start
```

Linux/macOS：

```sh
OPENCODE_BROWSER_UPSTREAM=http://127.0.0.1:4096 OPENCODE_BROWSER_HOST=0.0.0.0 npm start
```

Android 添加 `http://电脑地址:4100`，使用原 OpenCode 密码。`OPENCODE_BROWSER_UPSTREAM` 必须为原服务的 HTTP(S) origin，不带路径、查询或嵌入凭据。网关用原服务验证认证，再签发 30 秒、单次使用的浏览器票据。票据放在 WebSocket 子协议头中；浏览器连接只能操作该服务器，不接收其他服务器凭据。普通 API 和 PTY 保持原服务认证。

同一电脑同时运行多台网关时，为每台设置不同的 `OPENCODE_BROWSER_PORT` 和 `OPENCODE_BROWSER_DATA`。数据目录同时隔离标签、Chromium Cookie 和缓存，避免多个进程争用默认配置目录。

手机“更多 → 浏览器”、宽屏侧栏“＋ → 浏览器”、Ctrl+Shift+B 使用同一打开动作。每个手机标签可独立关闭。页面传输真实触摸事件，支持点击、滚动、多指缩放及网页自己的长按/拖动行为；系统 Chromium 上下文菜单不是 Android 弹层。

未安装网关的普通 OpenCode 服务会显示明确错误。第三方 GUI 扩展安装、电脑多窗口及 SSH/WSL 本地引擎仍不由此服务提供。

## 引擎、源码构建与旧版配对

下述独立配对仅在未设置 `OPENCODE_BROWSER_UPSTREAM` 时启用，供旧版连接兼容；新版打开浏览器不会弹此配对框。

这个服务直接打包固定 OpenCode 2.0.26 的官方 `createBrowserPane`，不是另写一套网页自动化。Electron 在电脑执行网页；Android 通过认证 WebSocket 显示 JPEG 帧、发送触屏/键盘输入。网页网络经官方 OpenCode 隧道转到所连服务器，截图和分析文件沿用官方传输协议。

先在仓库根目录运行 `node frontend/android/build.mjs`。需要固定 Bun 1.4.2、Node.js 22+、可运行 Electron 的图形环境，然后：

```sh
cd browser-companion
npm ci
npm test
npm run build
npm run package
npm start
```

Windows 启动器会把固定 Electron 运行时复制到 `%LOCALAPPDATA%/OpenCode Android Browser/runtime-<version>-<arch>`，以避开受保护开发目录的 ACL 对 Chromium 沙箱启动的影响。服务保留沙箱、上下文隔离和禁用 Node 集成。运行时不是静默降级到无沙箱模式。

Electron 固定为官方 Desktop 使用的 44.4.5。远程宿主显式启用离屏渲染：隐藏的普通窗口重建后可能没有可截图的显示表面，不能用普通窗口隐藏来代替离屏渲染。Android 可见性仍决定帧传输和官方截图权限。

| 环境变量 | 用途 |
| --- | --- |
| `BUN_BINARY` | 固定 Bun 可执行文件路径 |
| `OPENCODE_BROWSER_HOST` | 默认 `127.0.0.1`；实体手机连接时指定可达网卡或 `0.0.0.0` |
| `OPENCODE_BROWSER_PORT` | 默认 `4100` |
| `OPENCODE_BROWSER_UPSTREAM` | 原 OpenCode origin；设置后启用上述统一网关 |
| `OPENCODE_BROWSER_DATA` | 浏览器标签元数据及旧版令牌目录；默认 Electron userData |
| `OPENCODE_BROWSER_TOKEN` | 可选注入的配对令牌；默认首次生成并保存在 `pairing-token` 文件 |

旧版在 Android「设置 → 常规 → 内置浏览器 → 兼容旧版浏览器服务」填写地址和令牌；取消不修改配置，“断开”清除配置。服务只打印令牌文件路径，不打印令牌；Android 使用 Keystore 加密存储。旧配对只发送当前连接所属服务器的凭据。

HTTP/WS 与项目已有明文连接策略一致；HTTPS/WSS 可由反向代理提供，需支持 WebSocket 并转发整个网关 origin。统一连接的路径是 `/api/android-browser/bridge`，旧配对是 `/bridge`。票据与配对凭据放在 WebSocket 子协议头中，不放在 URL 查询串。

HTTP/WS 会明文传输配对令牌和 OpenCode 认证信息，只适用于可信网络；公网或不可信网络使用 HTTPS/WSS。

脚本自动化沿用官方 action schema；浏览器服务、网页和下载文件仍受官方远程文件权限与大小限制约束。

服务断开后 Android 显示错误并按退避重新连接，恢复标签元数据；未完成的操作明确失败，不自动重放。浏览器 Cookie 使用官方会话分区，标签恢复不等于网页 DOM 或登录状态跨进程保存。截图的可见条件是 Android 正在显示目标面板；隐藏页面不能以电脑后台窗口替代这一条件。

真实引擎冒烟测试使用自己的测试 OpenCode v2 服务，避免修改日常会话。统一网关测试省略 `OPENCODE_BROWSER_TOKEN_FILE`，将 `OPENCODE_TEST_SERVER_URL` 设为网关地址；旧配对测试：

```sh
OPENCODE_TEST_SERVER_URL=http://127.0.0.1:4096 \
OPENCODE_TEST_SERVER_PASSWORD=your-test-password \
OPENCODE_BROWSER_TOKEN_FILE=/path/to/pairing-token \
npm run smoke
```

可选 `OPENCODE_BROWSER_URL` 指定服务地址，`OPENCODE_TEST_SERVER_USERNAME` 指定用户名，`OPENCODE_BROWSER_REPORT_DIR` 指定报告目录。脚本创建并删除自己的测试会话，验证网络隧道、画面、输入、自动化、截图、取消和分析文件传输。它不代替 Android 设备测试，也不调用模型或消耗模型请求。
