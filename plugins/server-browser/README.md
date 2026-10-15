# OpenCode 服务端浏览器插件

Chromium 在 OpenCode **服务端**运行。Android 2.0.16 和本仓库的适配 Web 界面显示同一会话的标签页，并发送真实网页操作。模型通过 `server_browser` 工具访问同一浏览器。`localhost` 指服务器本机。

使用 OpenCode **v2 / 2.0.26** 插件接口，已在 Windows 的真实 2.0.26 服务上验证。不是 v1 插件。原来的 OpenCode URL、认证、4096 端口都继续使用；不启动 gateway，不增加浏览器配对或浏览器服务端口。

## 安装 Release 插件包

服务器需要 Node.js 22+。把 `opencode-server-browser-0.1.0.zip` 解压到一个固定目录，在该目录运行：

```sh
node install.mjs
```

此命令按 lockfile 安装运行依赖并下载 Chromium，失败会直接报错；不修改 OpenCode 配置。按它打印的绝对路径，将插件条目合并到已有 `opencode.json` 的 `plugins` 数组中，例如：

```json
{
  "plugins": [
    { "package": "C:/tools/opencode-server-browser" }
  ]
}
```

已有其他插件时保留它们。可以配置在项目的 `opencode.json`，也可放进 OpenCode 的全局配置，让各项目都加载。然后重启原来的 OpenCode 服务，例如原有的 `opencode serve --hostname 0.0.0.0 --port 4096`；继续使用原有认证设置。服务无认证时，本插件不会另设认证。

Linux 主机缺少 Chromium 系统依赖时，需要管理员按 Playwright 提示安装；在插件目录执行 `npx playwright install-deps chromium`。本插件使用 headless Chromium，无需桌面显示器。服务运行账户必须能够访问安装的浏览器缓存。

也可使用已安装的 Chromium，跳过 Chromium 下载，仅执行 `npm ci --omit=dev --ignore-scripts` 并明确指定：

```json
{
  "plugins": [{
    "package": "/opt/opencode-server-browser",
    "options": { "executablePath": "/usr/bin/chromium" }
  }]
}
```

该路径是服务端路径。路径错误或依赖缺失会在首次打开标签页时明确失败，不会切换到客户端浏览器。

## 使用

- Android：会话底部“更多 → 浏览器”；宽屏使用右侧“＋ → 浏览器”，或 `Ctrl+Shift+B`。
- 适配 Web：使用相同入口。下载 `opencode-web-2.0.16.zip`，解压后运行 `node serve.mjs`，打开打印的地址并连接原有 OpenCode 服务。需要其他设备访问时，可用 `node serve.mjs --host 0.0.0.0 --port 4175`，也可将 `dist/` 放在已有静态站点服务上。
- AI：需要在插件条目中明确配置 `"options": { "automation": true }`，然后使用 `server_browser.tabs.open`、`snapshot`、`click`、`fill`、`screenshot` 等工具。默认只提供手动面板，不注册模型工具。

`automation: true` 是对此插件模型自动化的一次性授权。OpenCode 2.0.26 的公开插件 SDK 没有暴露 `Permission.assert`，所以本插件**不承诺逐次动作弹窗审批**；`server_browser` 权限名称只参与官方工具目录过滤。需要逐次审批的环境请保持 automation 关闭，手动面板仍可用。上游 2.0.29 已新增 `permission.assert`（需要审批时弹窗并等待回复），但本插件仍按 2.0.26 SDK 编译；采用它需要先升 SDK 并在 2.0.29 服务上重新验收。

**安装服务端插件不会自动修改官方 Web/Desktop 的前端文件。** 未改造的官方客户端可通过模型使用已注册的工具，但不会自动出现此可视面板。适配 Web 的静态站点仅提供界面，API 仍直连原有 OpenCode 服务，不是 gateway。

## 生命周期与范围

同一个服务、工作区和会话共享标签与 cookies；不同会话的浏览器上下文隔离。退出客户端、切换会话或强杀 Android 不会关闭或重新加载服务端页面。重开面板会恢复已有标签；网页任务继续运行。关闭标签也会清理该标签的截图和下载文件；删除/移动会话或停止 OpenCode 会释放对应所有资源。**服务重启、插件重载后不恢复浏览器进程、登录态和页面执行状态**。

已提供标签管理、地址导航、历史前后退、刷新/停止、页面画面、鼠标/触屏/键盘输入、网页弹窗，以及模型的快照、元素操作、执行页面脚本、截图、工作区文件上传、下载记录、控制台和网络记录。截图和下载保存在服务端临时目录，可由文件面板访问；不承诺文件永久保存。

此版本没有移植 Desktop 的网页标注、元素检查浮层、Performance/Heap/Lighthouse 面板、密码管理、摄像头/麦克风和客户端文件上传选择器。网页没有直接运行在 Android WebView 中，所以不能调用 Android 原生平台桥。

## 接口与资源约束

插件 RPC 位于 OpenCode 原有 `/api/rpc/xiufeigo.server-browser/{state,command,frame}`，沿用服务认证及工作区参数；每次调用验证真实会话归属。普通命令按会话顺序执行，`dialog`/`stop` 可解除当前阻塞。客户端不会自动重放输入；服务端只对最近 256 个命令 ID 去重，不提供跨服务重启的 exactly-once 保证。已开始的操作不会因客户端 HTTP 断开被撤销。

网页、脚本结果、控制台与网络内容均是未可信数据。页面脚本在 Chromium 内执行，不在 Node 服务进程内执行。导航入口允许 HTTP/HTTPS 和 `about:blank`，拒绝文件 URL、脚本 URL 和内嵌认证。浏览器能访问服务端网络，按运行账户权限使用。

上传工具限当前工作区文件，验证 realpath，禁止路径和符号链接逃逸；此限制无隐藏开关。截图最多 1600 万像素、5 MiB；单标签最多保留 64 个文件，日志和网络记录各 500 条。下载没有单文件字节配额，按服务器可用磁盘使用；关闭标签即清理文件。网络响应体只在明确为未压缩文本且长度不超过 20000 字节时读取；否则明确返回不可用原因。画面按需轮询 JPEG，页面隐藏时暂停；它不是视频串流，音频和视频流畅度不等同本地浏览器。

## 从源码构建

```sh
npm ci
npm run install-browser
npm test
npm run typecheck
npm run build
```

真实 Chromium 测试可通过 `SERVER_BROWSER_CHROMIUM` 明确指定已有 Chromium 的路径。
