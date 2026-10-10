# OpenCode Desktop 与 Android 差异记录

核对日期：2026-10-10。Android **2.0.14 / versionCode 42**；官方 **2.0.26 / ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4**。本次读取了同一提交的完整 Desktop renderer/main/native 源码、共享 app、GUI 扩展及 Android 宿主，不以不同版本的参考 checkout 代替固定基准。

当前产品是 `:webapp`，用户选择连接电脑或服务器上的 OpenCode。历史 `:app` Compose 模块不参与产品发布。共享官方前端不等于完整移植 Desktop 平台能力，也不等于所有业务已经逐项实测。

## 本次浏览器移除

按用户要求删除远端 **v2.0.13 tag 和 Release**；远端查询均返回 404。移除内置 browser 扩展的 renderer 注册、浏览器菜单/快捷键/移动面板、Android 远程传输、原生配对以及 browser-companion/gateway 项目和发布步骤。旧配对凭据在加密偏好初始化时同步删除；旧构建缓存中的浏览器适配文件也会清理。

Android 直接连接普通 OpenCode 服务，例如 `http://电脑地址:4096`，使用原密码。已有服务器记录保留；此前填了 4100 的用户需自行编辑为原服务地址，不能假设任意服务器的 4100 都对应同一主机的 4096。外部网页继续由系统浏览器打开。

右侧“＋”现在只贡献官方文件动作，按官方组件的单项逻辑直接打开文件选择器。手机“更多”、设置和 Ctrl+Shift+B 不再提供内置浏览器动作。文件、Review、终端和应用菜单继续使用共享模块。

## 当前能力与差异

| 能力 | Android 当前结果 | 与 Desktop 的差异／验证边界 |
| --- | --- | --- |
| HTTP 多服务器管理 | 官方注册表及连接页；持续新增、编辑、切换、删除和恢复 | 已验证五台连续添加及一台/多台过渡；不支持客户端建立 SSH 隧道 |
| 会话、流式消息、权限请求、模型/代理选择 | 官方共享 app 和连接服务器的 API | 不是另一套仿写业务；没有逐个调用所有模型、付费提供商或代理验收 |
| MCP、OpenCode 插件、Skills | 官方服务端设置，通过所选服务器 API 读取 | 与客户端 GUI 扩展安装是不同功能；不能把 GUI 宿主缺失说成服务器插件不可用 |
| 文件、Review、Office 文档 | 官方文件/差异组件；包含 Word/Excel/PowerPoint 只读预览 renderer | 系统编辑器打开/文件管理器定位电脑工作区文件未移植；Office 本轮仅核实接线和资产，未逐格式设备验收 |
| 附件、导入、保存、剪贴板 | Android SAF/WebView 文件选择、Markdown 保存、原生文本/图片剪贴板 | 上传文件字节，不提供 Electron 本地路径映射；Android 系统拖放未完整实测 |
| 终端 | 官方 PTY/面板 + 已验证的 xterm Android 适配 | Desktop 使用 ghostty-web；字体度量、触摸滚动、IME 和手势是 Android 实现，不是同一终端引擎 |
| 系统通知 | 官方成功/失败触发器经 Notification shim 和 WebHostBridge 转为 Android 通知；遵守官方偏好和前后台策略 | 不是“未接入”；Android 13+ 使用系统通知权限。跨会话通知点击、厂商后台限制仍需设备验收 |
| 相机扫码 | 连接页共享扫码 UI + Android 摄像头权限 | Desktop 不使用同一手机相机流程；实体相机扫码尚未自动实测 |
| 草稿、状态存储 | 官方 Web IndexedDB/localStorage 持久化 + 宿主加密偏好 | Desktop 使用本地 SQLite/draft API；存储实现不同，不能声称 Android 采用 Desktop 数据库 |
| 应用菜单 | 官方六组菜单；编辑、缩放、重载、Android 全屏、最小化/关闭接原生动作 | 新窗口、最大化、Electron DevTools 明确禁用；外接键盘支持快捷键，软键盘不提供全部键组合 |
| 本机 OpenCode 引擎 | 不提供；连接现有服务 | Desktop sidecar 的安装、启动、重启和本地服务生命周期未移植；符合当前远程客户端范围 |
| SSH/WSL | 不提供 Android 客户端管理和启动 | Desktop SSH 认证/隧道、Windows WSL 发行版与服务生命周期属于原生 main 能力；已暴露的 HTTP 服务仍可连接 |
| 第三方 GUI 扩展宿主 | 不提供 Desktop main-process 扩展安装/启停/重载 | 官方 GUI Extensions 诊断设置本来只在开发 Desktop 显示，不应当作普通生产 UI 缺失 |
| 桌面系统能力 | 未移植多窗口恢复、通用 opencode:// 外部深链接、Electron 更新、桌面调试日志导出、CLI 安装 | Android 单任务、系统外链和通知内部跳转不能替代这些能力；CLI 安装入口仅适用于官方 macOS |
| 内置浏览器 | 本次按用户要求移除 | 无 Android browser 自动化宿主；不影响服务器或其他 Desktop 客户端自行提供的工具 |

逐区域、触发方式和源码出处见 [UI-AUDIT.md](UI-AUDIT.md)。手机窄屏使用官方响应式布局，是有意适配；不是桌面截图的等比例缩小。悬停/Alt 展示目录、鼠标菜单及拖放不能等同触屏长按，尚无完整触控等价操作。

## 本次验证

- Native RPC 回归 **3/3**；固定上游设置路由/URL.parse 兼容 **9/9**；app/GUI 生产类型检查与源码构建通过。
- Android 单测 **249/249**，失败/错误/跳过均为 0；debug 和 release 构建通过。Release 包名 `com.xiufeigo.opencodeshell`、版本 **2.0.14/42**，使用用户指定密钥；证书 SHA-256 `760dc05fb4d1a125ed82cf9bff8eee622c7244d44b6ddc82a4c34e0b37b820e6` 已核验。
- 完整打包 SPA **43/43**：五服务器连续管理、导入超过 2 MiB 的会话文件、旧真实服务器恢复、应用菜单、文件入口、浏览器菜单/设置/RPC/快捷键移除。报告 `research/tmp/verify-android-navigation/2026-10-10T02-29-26-077Z/report.json`。
- 完整终端发布回归 **51/51**：触摸滚动、输出期间锚定、IME、旋转、历史恢复和 PTY 输入。报告 `research/tmp/verify-android-terminal-release-ready/2026-10-10T02-22-34-462Z-cd032d/report.json`。
- 实际 Android API 35 WebView：手机 **6/6**（412×891），覆盖原生剪贴板、系统栏全屏、应用菜单和浏览器入口/RPC/快捷键移除；平板 **4/4**（1200×768），覆盖设置、文件“＋”和浏览器移除。两者无页面异常。报告分别为 `research/tmp/verify-android-menus/2026-10-10T02-37-22-226Z-device/report.json`、`research/tmp/verify-android-menus/2026-10-10T02-34-00-209Z-device/report.json`；使用签名 debug APK 和普通 OpenCode 测试服务。
- Release APK **57,551,805 字节**，SHA-256 `b39f6c908988696347e516793597d96b7d7234f576b25c7a024272927426e5e2`；包内 **1,621** 个 UI 文件与已验证的源码构建产物逐字节一致，DEX 不再包含 BrowserPairing 类。

上述 SPA 和终端脚本使用 Chromium、测试后端及模拟平台桥，不能当作 Android 设备或所有功能的端到端验收。实际 WebView 检查使用模拟器，也不能替代实体相机或各厂商后台行为验收；历史 v2.0.10–2.0.13 浏览器验证见 Git 历史，不再代表当前产品能力。

## 官方来源

- [Desktop 平台与原生能力](https://github.com/anomalyco/opencode/blob/ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4/packages/desktop/src/renderer/platform/index.ts)
- [Desktop 启动与 sidecar](https://github.com/anomalyco/opencode/blob/ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4/packages/desktop/src/renderer/desktop-app.tsx)
- [官方共享 app](https://github.com/anomalyco/opencode/tree/ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4/packages/app)
- [官方 GUI 扩展](https://github.com/anomalyco/opencode/tree/ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4/packages/gui-extensions)
