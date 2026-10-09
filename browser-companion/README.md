# Android 内置浏览器服务

这个服务直接打包固定 OpenCode 2.0.26 的官方 `createBrowserPane`，不是另写一套网页自动化。Electron 在电脑执行网页；Android 通过认证 WebSocket 显示 JPEG 帧、发送触屏/键盘输入。网页网络经官方 OpenCode 隧道转到所连服务器，截图和分析文件沿用官方传输协议。

先在仓库根目录运行 `node frontend/android/build.mjs`。需要固定 Bun 1.4.2、Node.js 22+、可运行 Electron 的图形环境，然后：

```sh
cd browser-companion
npm ci
npm test
npm run build
npm start
```

Windows 启动器会把固定 Electron 运行时复制到 `%LOCALAPPDATA%/OpenCode Android Browser/runtime-<version>-<arch>`，以避开受保护开发目录的 ACL 对 Chromium 沙箱启动的影响。服务保留沙箱、上下文隔离和禁用 Node 集成。运行时不是静默降级到无沙箱模式。

Electron 固定为官方 Desktop 使用的 44.4.5。远程宿主显式启用离屏渲染：隐藏的普通窗口重建后可能没有可截图的显示表面，不能用普通窗口隐藏来代替离屏渲染。Android 可见性仍决定帧传输和官方截图权限。

| 环境变量 | 用途 |
| --- | --- |
| `BUN_BINARY` | 固定 Bun 可执行文件路径 |
| `OPENCODE_BROWSER_HOST` | 默认 `127.0.0.1`；实体手机连接时指定可达网卡或 `0.0.0.0` |
| `OPENCODE_BROWSER_PORT` | 默认 `4100` |
| `OPENCODE_BROWSER_DATA` | 服务配对令牌和浏览器标签元数据目录；默认 Electron userData |
| `OPENCODE_BROWSER_TOKEN` | 可选注入的配对令牌；默认首次生成并保存在 `pairing-token` 文件 |

在 Android「设置 → 常规 → 内置浏览器」填写 HTTP/HTTPS 服务地址和令牌；取消不修改配置，“断开”清除配置。服务只在日志里打印令牌文件路径，不打印令牌。Android 配对令牌使用 Keystore 加密存储。OpenCode 服务器凭据随当前官方服务器配置发送到已配对服务，因此应连接自己信任的浏览器服务。

HTTP/WS 与项目已有明文连接策略一致；HTTPS/WSS 可由反向代理提供，需支持 WebSocket 并将 `/bridge` 转发到服务。配对凭据放在 WebSocket 子协议头中，不放在 URL 查询串。

HTTP/WS 会明文传输配对令牌和 OpenCode 认证信息，只适用于可信网络；公网或不可信网络使用 HTTPS/WSS。

手机竖屏通过“更多 → 浏览器”打开，宽屏通过官方侧面板“＋ → 浏览器”打开；服务未连接时该菜单调起原生配对框。支持触屏点击、单指滚动和中文 IME。脚本自动化沿用官方全部 action schema；浏览器后台服务、网页和下载文件仍受官方远程文件权限与大小限制约束。手动 pinch/长按拖拽、第三方 GUI 扩展安装当前不提供。

服务断开后 Android 显示错误并按退避重新连接，恢复标签元数据；未完成的操作明确失败，不自动重放。浏览器 Cookie 使用官方会话分区，标签恢复不等于网页 DOM 或登录状态跨进程保存。截图的可见条件是 Android 正在显示目标面板；隐藏页面不能以电脑后台窗口替代这一条件。

真实引擎冒烟测试需要同一电脑上的测试 OpenCode v2 服务，避免修改日常会话：

```sh
OPENCODE_TEST_SERVER_URL=http://127.0.0.1:4096 \
OPENCODE_TEST_SERVER_PASSWORD=your-test-password \
OPENCODE_BROWSER_TOKEN_FILE=/path/to/pairing-token \
npm run smoke
```

可选 `OPENCODE_BROWSER_URL` 指定服务地址，`OPENCODE_TEST_SERVER_USERNAME` 指定用户名，`OPENCODE_BROWSER_REPORT_DIR` 指定报告目录。脚本创建并删除自己的测试会话，验证网络隧道、画面、输入、自动化、截图、取消和分析文件传输。它不代替 Android 设备测试，也不调用模型或消耗模型请求。
