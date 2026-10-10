# 官方源码构建

`upstream.json` 是当前 Android UI 唯一版本来源：官方仓库、提交 SHA、UI 版本、Bun 版本。`OPENCODE_APP_VERSION` 仅描述旧 `app/` 资产，不用于 `webapp/`。

`build.mjs` 创建独立 sparse checkout，校验版本，应用可审核的源码补丁，冻结安装官方 lockfile，再执行 app 和 GUI 扩展的生产源码类型检查与 Vite 构建。类型检查排除上游测试和 Storybook 示例，它们依赖未纳入 Android 构建的测试工作区。Windows 会把 Git 的文本 symlink 还原为目标字节。

只需要运行：

```sh
node frontend/android/build.mjs
```

Bun 必须为 1.4.2；`BUN_BINARY` 可指定二进制路径。Terminal 独立执行 `frontend/terminal` 的 `npm ci && npm test && npm run build`。最终 APK 通过 `:webapp:assembleRelease` 构建。

源码补丁涉及：官方入口的平台提供者和路由恢复；服务器设置的导航状态及持续添加入口；终端稳定模块入口及旧实例快照交接；共享 ResizeHandle 的触屏生命周期和点击范围；浏览器移动入口、远程显示、可见性和请求取消。业务 API、会话状态、PTY、文件、设置、主题和浏览器自动化继续由官方实现负责。

`browser.tsx` 实现官方扩展 Bridge，把官方浏览器面板的 IPC、embed、capture 映射到所选服务器的认证网关。`mobile-browser.tsx` 仅将同一个 pane/model 接入现有移动视图切换器，并复用官方翻译。旧版配对保留在兼容设置；外部 GUI 扩展的安装管理当前明确报“不支持”，不返回伪造成功。先构建 UI，再执行 `browser-companion` 的构建；后者检查所需源码补丁已经应用。

`platform.ts` 通过官方 Platform 接口调用 `rpc.mjs`，AndroidX WebMessageListener 仅接受本地 UI 主框架。原生失败拒绝 Promise，用户取消保存返回 false。系统平台方法不伪装成功。剪贴板图片读取预算为 `AndroidPlatformHost.MAX_CLIPBOARD_IMAGE_BYTES`，默认 20 MiB，超出返回错误。

平台身份明确为 `android`；原生宿主提供的能力按接口接入，不声明 Electron 的 windowID 或电脑目录选择。`menu.ts` 为官方应用菜单提供编辑、重载、缩放及原生全屏/窗口动作；不支持的桌面动作明确禁用。菜单保存输入框焦点和选区，异步剪贴板期间编辑内容变化则明确拒绝操作。`files.mjs` 使用 WebView 标准文件 input，由 Android SAF 回传 URI，再顺序读取 `File`；官方会话导入因此可用，取消不导入、读取和校验失败继续抛错。保持原存储 namespace，升级不迁移或重置真实服务器。

浏览器扩展的菜单与快捷命令共用一个打开动作，通过 `browser-transport.mjs` 自动连接当前服务器网关、用原认证申请单次票据，沿用官方注册握手。手机首标签检查同一 model.opening 状态，避免菜单与自动打开同时创建两个。连接按服务器隔离，配置变化只重建受影响服务器。没有能力的服务器显示真实错误，旧配对仅保留在兼容设置。

多服务器时，旧根级服务器标签在路由解析层归入偏好设置，移除会抢写新增目标页的库存变化导航 effect。根级和嵌套页共用一个新增动作，移动顶部和宽屏侧栏均可持续添加；构建同时运行官方设置路由测试与新增回归用例。

触屏设备不具备悬停时，侧栏新增按钮常显。连接页使用的 `URL.parse` 在旧 Android WebView 中缺失，沿用上游 `runtime/polyfills.ts` 的 core-js 兼容入口，并在构建中验证原生 API 缺失的启动场景。

APK 内的 `ui/opencode-build.json` 记录来源；`/_assets/` 在磁盘映射为 `oa/`，避免 Android 资产合并过滤下划线目录。构建完全成功后才替换资产。CI 检查修改、删除和未跟踪的生成文件，避免漏提交新 chunk。

升级上游时先更新 pin、审核源补丁及 SDK 合同，完成类型检查、Android 单测、构建、手机/平板回归后再发布。
