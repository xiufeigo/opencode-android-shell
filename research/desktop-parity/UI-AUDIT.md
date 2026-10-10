# Android 与 Desktop UI 核对（2.0.14）

基准：官方 **2.0.26 / ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4**。核对的是发布模块 `webapp`；Desktop renderer 本身使用共享 `packages/app`，并注入 Electron 平台与扩展宿主。

| 区域／触发 | Desktop 行为及官方出处 | Android 结果与不一致 |
| --- | --- | --- |
| 顶部与导航 | `app/src/shell/titlebar/titlebar.tsx`：宽屏标签/主页，窄屏 MobileDrawer | 官方响应式布局；手机不会照搬宽屏多栏截图。Android 额外启用同一应用菜单，并使用系统安全区和软键盘空间 |
| 左上应用菜单 | `app/src/shell/commands/desktop-menu.ts`、`titlebar/windows-menu.tsx`：文件、编辑、视图、转到、窗口、帮助 | 同一分类、翻译和命令；复制/剪切/粘贴恢复原焦点/选区。新桌面窗口、最大化和 DevTools 可见但禁用；全屏隐藏系统栏，最小化进入后台，关闭结束 Android 任务 |
| 快捷键 | `app/src/shell/commands/command.tsx` 与菜单 capture keydown | 外接键盘使用官方快捷键；软键盘不能提供全部 Ctrl/Alt 组合。已移除 Ctrl+Shift+B 浏览器命令，不吞该输入 |
| 首页会话目录/分支 | `app/src/home/sessions/controller.tsx`、`view.tsx`：Desktop 悬停/Alt 展示位置 | 启用官方 Desktop 列表样式和远程目录/分支信息；没有完整的触屏悬停/Alt 等价操作 |
| 服务器设置与持续添加 | `app/src/settings/shell.tsx`、`runtime/server/registry.tsx`：单台合并，多台分组 | 修复新增导航竞态；根页和详情页均能继续添加，触屏“＋”常显；五台增删切换恢复已测。SSH/WSL 管理未接入 |
| 会话与输入框 | `app/src/session/`、`composer/`：流式消息、权限表单、模型/代理/附件 | 共享业务状态与组件；软键盘和手机布局不同。复用代码不代表全部模型和代理逐项验收 |
| 右侧审查栏“＋” | `app/src/runtime/extension/side-region.tsx` 根据扩展贡献构造单动作或菜单 | 浏览器已移除，只剩文件时按官方规则直接打开工作区文件选择器；这是本次有意变化 |
| 手机“更多” | GUI panel registry 与官方 MobileDrawer | 保留终端、文件等支持的共享面板，删除浏览器标签及入口 |
| 文件和 Review | GUI `file/renderer.tsx`、`file/list.tsx`、`file/tree-v2.tsx`、Review renderer | 共享远程文件与差异 UI；Desktop 的 Open in app、文件管理器定位依赖 `ctx.desktop`，Android 隐藏这些入口，不会显示伪造成功 |
| 导入／附件／保存 | Desktop `renderer/platform/files.ts`、共享项目菜单/附件 controller | Android 使用 SAF 文件选择及 Markdown 保存；不具备 Desktop 原生目录选择、附件本地路径映射和拖放取消通知。文件字节导入已测，系统拖放待设备验收 |
| Word/Excel/PowerPoint | GUI `microsoft-office/renderer.tsx`：三种文件只读预览 | renderer 和预览资产已包含，不列为缺失；本轮没有逐格式做实际文件验收 |
| 终端 | GUI terminal 面板与官方 ghostty-web | UI/PTY 同源；Android 使用 xterm 适配，触摸滚动、IME、字体及历史行为有专门实现和测试，不能说引擎完全相同 |
| MCP／插件／Skills 设置 | `app/src/settings/providers/extensions.tsx` 查询所选服务器 API | 保留；第三方 GUI 扩展本机托管未移植，二者不要混淆。GUI Extensions 诊断页本来是开发 Desktop 专用 |
| 设置／主题／语言 | 官方共享设置和 UI 组件 | 同源；缩放每步 20%。桌面窗口缩放与 Android CSS 缩放实现不同，多指缩放手势和手机字号不是桌面窗口能力的完整替代 |
| 系统通知 | `app/src/shell/notifications/notification.tsx`、Desktop `renderer/platform/notifications.ts` | 成功/失败通知经 `oc-host.js` 的 Notification shim → `WebHostBridge` → `NotifierHost`，Android 系统权限决定投递；本轮源码核验，跨会话点击和厂商后台限制尚未完整设备验收 |
| 草稿与恢复 | Desktop `renderer/platform/storage.ts`、`main/storage/index.ts` | Android 采用共享 Web IndexedDB/localStorage，Desktop 用 SQLite；存储机制不同，升级保留服务器和草稿数据 |
| 本机服务／系统能力 | Desktop sidecar、GUI SSH/WSL/updater、窗口 registry、deep-link 和 CLI | Android 未实现本机引擎生命周期、SSH/WSL 管理、多窗口、通用 opencode:// 链接、Electron 更新器、桌面调试日志导出及 CLI 安装；普通远程 HTTP 服务器仍可连接 |
| 内置浏览器 | Desktop browser pane、Electron/CDP 与网络隧道 | 用户明确取消。Android 删除面板、快捷键、配对、传输和 gateway；网页链接交给系统浏览器 |

“没有实现”“交互有意适配”“已接线但未完整实测”是三种不同结论。当前无法宣称与 Desktop 的全部功能和交互 100% 一致。具体验证与版本范围见 [REPORT.md](REPORT.md)。

官方代码：[Desktop 平台](https://github.com/anomalyco/opencode/blob/ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4/packages/desktop/src/renderer/platform/index.ts)、[共享设置](https://github.com/anomalyco/opencode/blob/ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4/packages/app/src/settings/shell.tsx)、[GUI renderer composition](https://github.com/anomalyco/opencode/blob/ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4/packages/gui-extensions/src/renderer.ts)。
