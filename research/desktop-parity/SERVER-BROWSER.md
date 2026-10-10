# 服务端浏览器实现与验收

目标：浏览器由 OpenCode v2 服务端持有，Android 与适配后的 Web 共用可视面板；继续使用原 OpenCode API、认证和端口，不恢复 gateway 或配对服务。

这是结构性实现。服务端浏览器进程、会话归属和状态是一份事实来源，客户端只显示和发送操作。客户端断开不能关闭浏览器或重放输入；显式关闭标签、删除会话或停止服务才释放资源。浏览器上下文按工作区及会话隔离。

实现文件：`plugins/server-browser` 提供插件、共享 RPC 契约与浏览器运行时；`frontend/remote-browser` 提供共享 GUI 扩展，复用官方 Panel/Menu/Command/移动入口；`frontend/android` 负责两种客户端构建与源码接入。

与最小补丁相比，复用旧 companion 传输会继续依赖第二套认证、服务和配对状态，因此不采用。新插件有独立 RPC ID 和工具命名空间，避免覆盖官方 Desktop 浏览器。

验证顺序：真实 Chromium 的针对性测试；插件及前端类型检查；UI 与签名 APK 构建；实际 OpenCode 插件加载、认证/RPC、Web/Android 操作和断线恢复。测试不调用用户正在使用的服务、不清除用户数据。

## 2.0.16 本地验收结果

2026-10-10 在 Windows、隔离的真实 OpenCode 2.0.26 服务及独立 Android 模拟器上完成：

| 检查 | 结果与范围 |
| --- | --- |
| 插件单测 | 6/6；真实 Chromium 的会话隔离、元素引用、输入顺序/去重、弹窗、资源清理及显式模型授权开关 |
| 原服务集成 | 6/6；原认证拒绝未授权请求、v2 插件加载、29 个模型工具注册，真实注册执行器打开/列出/关闭 Chromium，服务器 localhost、画面 RPC 与会话归属 |
| 适配 Web | 7/7；正常入口、真实画面/输入、同 URL 标签草稿隔离、冷客户端恢复、帧请求失败后的重新连接，无页面异常 |
| 实际 Android APK WebView | 7/7；“更多 → 浏览器”、地址导航、真实触摸及中文输入、同 URL 标签草稿隔离、强杀后新进程恢复原标签和持续执行的页面；网页接续同一标签且 Android 画面更新，无页面异常 |
| 原有导航回归 | 43/43；手机/宽屏连续添加五台服务器、增删切换与恢复、六类应用菜单、右侧加号文件/浏览器菜单及升级设置保留；此组使用测试后端及模拟平台桥 |
| 静态与构建检查 | 插件、app、GUI 类型检查通过；固定上游设置/平台 9 项、会话终端模型 4 项通过；Android 单测 249/249，失败/错误/跳过均为 0；Android/Web 与 release/debug 构建通过 |
| 安装分发实跑 | 按 Release 文件清单 staging，在该目录执行 `node install.mjs`，从 lockfile 安装生产依赖及 Chromium；真实服务加载此目录并使用默认下载的浏览器完成上述 6 项服务集成，无需指定现有 Chrome |
| Release APK | `com.xiufeigo.opencodeshell`，2.0.16/44；包内 1,622 个 UI 文件与已测试源码产物逐字节一致；用户指定证书 SHA-256 `760dc05fb4d1a125ed82cf9bff8eee622c7244d44b6ddc82a4c34e0b37b820e6` 已核验 |

修复了首次打开宽屏标签时的地址输入竞态、同 URL 标签间草稿串用和帧连接失败后无法恢复轮询。输入队列不自动重放操作；冷客户端恢复不创建或刷新已有服务器页面。测试报告及截图由脚本保存在 gitignored 的 `research/tmp/verify-server-browser*`；测试配置中的密码不进入报告、提交或分发包。

安装步骤见 [插件说明](../../plugins/server-browser/README.md)。Release 同时提供 `opencode-2.0.16.apk`、`opencode-server-browser-0.1.0.zip`、`opencode-web-2.0.16.zip` 与校验文件。插件只安装在服务器，Android 继续连接原 OpenCode 地址（例如 4096）；Web 包只托管静态界面，不是 API gateway。

## 验证边界

工具注册/执行器测试不等同调用所有模型的完整 agent 循环；本轮不消耗用户模型额度。Android 实测使用独立模拟器，不覆盖所有实体手机或厂商后台策略。服务停止或插件重载会结束浏览器，不能恢复进程或登录状态。

未改造的官方 Web/Desktop 不会自动获得此可视面板。Desktop 的网页标注、检查浮层、Performance/Heap/Lighthouse、密码管理、原生网页权限和客户端文件选择上传仍有差异。模型工具默认关闭，明确配置 `automation: true` 才注册；公开 v2 SDK 不提供逐动作执行审批入口，不能将工具目录权限过滤当成逐动作弹窗授权。
