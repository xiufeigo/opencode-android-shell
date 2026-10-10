# Android 与 Desktop UI 核对（2.0.13）

基准为官方 **2.0.26 / ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4**，不是随时变化的 `v2` 分支。核对产品模块 `webapp/`，不使用已经退出发布的 `app/` Compose UI 代表当前产品。

Desktop 的 renderer 从 `packages/app` 加载界面，并注入 Electron 平台、sidecar 和扩展服务器。因此复用 `packages/app` 是官方 Desktop 的实现路径；界面差异需要区分响应式布局、平台能力条件和未移植能力。

| 区域 | 官方 Desktop 的来源与行为 | Android 本轮核对结果 |
| --- | --- | --- |
| 首页与会话列表 | `home/projects/view.tsx`、`home/sessions/view.tsx`；宽屏侧栏、窄屏项目菜单；Desktop 可在悬停/Alt 时显示工作目录和分支 | 保留官方响应式布局；原 Android 被当作 Web，目录信息及 Desktop 列表样式被关闭，本轮改为明确 Android 平台并启用这套列表显示。目录来自远程服务，不调用电脑文件管理器 |
| 服务器设置 | `settings/shell.tsx`；单台显示合并设置，多台显示服务器分组，新增后进入单独服务器设置 | 原本嵌套页没有新增入口，且数量增长 effect 会覆盖新增目标路由。移除导航竞态，在嵌套页补官方 AddServerMenu：手机顶部“＋”、宽屏侧栏“＋”；与根级共用新增动作；无悬停能力的触屏设备常显侧栏按钮 |
| 多服务器数据 | `runtime/server/registry.tsx`；按规范 URL 去重并持久化，无两台上限 | 没有新增第二份服务器配置；验证五台连续添加、编辑、切换、删除、刷新和只剩一台后再增至三台 |
| 会话导入 | `home/projects/controller.tsx` 依据 `openAttachmentPickerDialog` 显示菜单；按官方 SessionTransfer schema 校验，再发往选中项目所属服务 | 原接口缺失导致菜单隐藏。接入 Android SAF/WebView File，验证非法文件不发请求、超过 2 MiB 的有效文件导入后进入对应远程会话 |
| 连接页与二维码 | `servers/connect/screen.tsx`、`dialog.tsx`；平台与相机能力决定扫码入口 | 保留配对链接、密码与原生相机接入。改为 Android 身份时同时调整扫码入口条件，避免相机可用但按钮被隐藏；在入口兼容层补 WebView 124 缺失的 URL.parse，实际密码连接和新增通过；实体相机扫码仍未自动实测 |
| 消息、输入框、权限、模型、代理 | `session/`、`providers/` 的官方共享组件与业务状态 | 无另一套仿写页面；本轮没有改其业务逻辑。沿用官方实现不代表所有模型和代理都逐一调用验证 |
| 文件、Review 和终端 | 官方工作区文件及差异面板；GUI 扩展提供终端 | 保留官方文件选择入口和布局；移动终端/触屏使用已有 Android 适配，本轮执行终端回归确认平台身份变化没有破坏输入和恢复 |
| 内置浏览器 | 官方 GUI browser 扩展及 Electron/CDP；打开 New tab、地址栏和空白首页，网页网络通过所选服务器 | Android 使用相同 pane/model/toolbar 和执行引擎；2.0.13 的统一网关复用所选服务器地址与认证，点击直接打开 New tab，首次打开不再弹配对框。手机标签独立关闭、首标签去重、按钮复用官方翻译；真实 touch 支持网页点击、滚动、拖动与 pinch |
| 左上应用菜单 | `titlebar/windows-menu.tsx` 与 `shell/commands/desktop-menu.ts`；六组分类，调用统一 command、平台 action 或帮助链接 | 2.0.12 直接复用官方菜单和翻译，手机与平板均显示；命令沿用同一注册表，编辑接原生剪贴板并恢复选区，支持重载、缩放、系统全屏、最小化/关闭；桌面新窗口、最大化和开发者工具禁用 |
| 右侧“＋”菜单 | `runtime/extension/side-region.tsx` 根据扩展 `session.panel` 项构建；文件和浏览器各贡献动作 | 保留官方打开文件/浏览器两项与快捷键；2.0.13 菜单和 Ctrl+Shift+B 共用服务器自动连接动作，错误明确提示，不调用原生配对。手机从“更多 → 浏览器”显示同一个官方面板 |
| 设置、主题、语言 | `settings/` 和官方 UI 组件；部分条目由扩展贡献 | 共享组件、主题与语言；UI 缩放每步 20%，与 Desktop native/menu-actions 一致；不支持的菜单快捷键不吞输入。GUI 扩展管理尚未移植 |
| SSH/WSL、多窗口与更新 | Desktop 原生宿主与扩展服务器 | 尚未移植。HTTP 多服务器可以完整管理；应用菜单已接入 Android 宿主动作，不能把它表述为全部 Desktop 平台能力已对齐 |

手机小于 768 CSS px 时出现官方移动布局，属于共享组件的响应式行为。强制缩放整张桌面页面会牺牲点击尺寸和键盘空间，不能解决平台入口缺失；本轮沿用布局并修复功能路径。

之前“导航 16 项通过”的验收重新打开总设置，漏掉连续添加时的目标页面和过渡状态。新脚本先等待新增目标服务器页稳定，再使用该页的新增入口；同时覆盖单台和多台间切换。浏览器测试、实际 WebView 测试及发布 APK 签名分别记录，详细结果见 [REPORT.md](REPORT.md)。

官方出处：[Desktop renderer](https://github.com/anomalyco/opencode/blob/ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4/packages/desktop/src/renderer/desktop-app.tsx)、[设置层级](https://github.com/anomalyco/opencode/blob/ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4/packages/app/src/settings/shell.tsx)、[平台接口](https://github.com/anomalyco/opencode/blob/ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4/packages/app/src/runtime/platform/platform.tsx)、[服务器注册表](https://github.com/anomalyco/opencode/blob/ea666727a14c961f3a4e6ce9d0f5862b3a2bcbf4/packages/app/src/runtime/server/registry.tsx)。
