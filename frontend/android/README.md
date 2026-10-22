# 官方源码构建

`upstream.json` 是当前 Android UI 唯一版本来源：官方仓库、提交 SHA、UI 版本、Bun 版本。`OPENCODE_APP_VERSION` 仅描述旧 `app/` 资产，不用于 `webapp/`。

`build.mjs` 创建独立 sparse checkout，每次构建固定工作区及 Desktop 图标目录范围，校验版本，应用可审核的源码补丁，冻结安装官方 lockfile，再执行 app 和 GUI 扩展的生产源码类型检查与 Vite 构建。核对上游时临时加入其他包也不能污染下一次构建的 Tailwind 扫描。类型检查排除上游测试和 Storybook 示例，它们依赖未纳入 Android 构建的测试工作区。Windows 会把 Git 的文本 symlink 还原为目标字节。

只需要运行：

```sh
node frontend/android/build.mjs
```

Bun 必须为 1.4.2；`BUN_BINARY` 可指定二进制路径。Terminal 独立执行 `frontend/terminal` 的 `npm ci && npm test && npm run build`。最终 APK 通过 `:webapp:assembleRelease` 构建。

源码补丁涉及：官方入口的平台提供者和路由恢复；服务器设置的导航状态及持续添加入口；终端稳定模块入口及旧实例快照交接；共享 ResizeHandle 的触屏生命周期和点击范围；应用菜单的 Android 平台动作；官方中文词典的缺口填充。业务 API、会话状态、PTY、文件、设置和主题继续由官方实现负责。

终端触摸焦点规则：拖动只滚动，不抢焦点（不会因为滑一下历史就弹出键盘）；但拖动也不关闭已打开的键盘，手势结束时恢复它开始时已有的焦点，点击仍按原逻辑在释放时聚焦。`verify-android-terminal-release.mjs` 的 `drag-focus-separation` 与 `drag-keeps-focus` 两条检查分别钉住这两半；真实 APK 由 `verify-android-terminal-device.mjs` 的 `*-drag-does-not-open-ime` 在系统 IME 上复核（debug 包 debuggable ⇒ WebView 自带 `webview_devtools_remote_*` CDP 通道，无需改代码）。曾经在 pointerdown 无条件 `textarea.focus()` 的写法会同时破坏后半条，已改回按手势状态恢复。

中文文案：`i18n-zh.ts` / `i18n-zht.ts` 包装官方词典（`packages/app/src/runtime/i18n/zh-android.ts`、`zht-android.ts`），只补官方尚未翻译的 key，官方词条后展开、优先胜出，所以上游补齐后无需删除。当前共 **169** 条：2.1.2 新增的 13 条（会话切换命令、项目文件夹缺失、时间线“变更文件”设置、免费额度与语音），以及 2.0.29 基准下官方仍缺的 156 条（提供方多账号、Console 连接、连接与配对流程、第三方声明、运行计数、扩展状态、会话位置等）。也就是说本基准下中文界面不再有因官方缺口而回退英文的条目。`scripts/extract-i18n.mjs` 继续只读官方源码，用来跟踪后续缺口；`research/_i18n_v2/_pending_*.json` 是早前轮次的人工快照，与当前版本已不匹配，不再作为来源。

2.1.2 把官方 UI 从 2.0.29 升到正式 tag v2.1.2（`a517687f`）。上游这轮集中在共享 app、session-ui 与 ui：每回合“变更文件”、Voice 消息、标签未读/键盘导航、免费模型分组、工作区子菜单、项目文件夹缺失提示、免费额度、provider 图标与设计细节；v2.1.0 还有破坏性协议改名（`native` / `keepID`）。29 个补丁文件里按 tag diff 只有 `gui-extensions/src/terminal/panel.tsx` 上游有改动（标签栏高度 52→49px），改动区与补丁上下文不重叠，`git apply --check` 通过；`runtime/platform/**` 与 `gui-extensions/src/sdk/**` 零 diff。Bun 仍为 1.4.2，构建流程未变。

2.1.2 的新界面文案上游仍没有中文（`zh.ts`/`zht.ts` 未更新）：新增的 13 条 key 已由本仓库补齐，中文界面不因本轮新增回退英文。`scripts/extract-i18n.mjs` 已按 2.1.2 源码重跑（en 1275 / zh 1106）。另外，2.1.2 的免费额度 UI 依赖服务端 ≥2.1.2 产出 `provider.free-limit`（旧服务端下静默不生效），“每回合变更文件”不依赖新服务端；未读徽标仍依赖服务端 `time.viewed`，服务端低于 2.0.26 时没有未读来源。

2.0.15 将终端标签按会话分组持久化，复用官方工作目录存储，避免运行中的 PTY 归属被会话布局缓存裁剪。旧目录共享的标签由首次打开终端的会话一次性接收。`session-model.test.ts` 直接测试真实终端模型及持久化器，并加入 UI 构建的必跑检查。

2.0.16 接入 `frontend/remote-browser` 共享扩展和服务端插件的唯一 RPC 契约。浏览器运行在服务端，客户端使用现有 server.client 认证与事件，不恢复旧 IPC、gateway 或配对。`build.mjs --web` 使用同一固定源码和扩展生成 Web 分发；Android 明确要求原生平台桥，Web 明确使用标准浏览器平台，二者不做运行时静默降级。

2.0.17 用 `back.ts` 在官方 Shell 中注册 Android 返回处理，直接读取当前布局、设置层级与打开的标签。原生宿主不再使用 WebView 历史回退，页面未消费时交回 Android 默认返回；在首页离开 App，不复活已关闭的标签。`verify-android-back.mjs` 覆盖手机、平板，设置 `ANDROID_SERIAL` 后使用实际 APK 和系统返回键验证退出、重新进入。

2.0.18 将手机端 xterm 滚动条滑块视觉宽度缩为 8 CSS px，同时保留 14 CSS px 的布局与触摸命中区，避免高 DPR 设备上的滚动条过粗。

2.0.14 按用户要求移除内置 browser 扩展的 renderer 注册、平台桥、移动入口、配对和网关项目；官方类型检查同步移除该 composition 的浏览器断言，仍检查其余扩展的 IPC 依赖。旧构建缓存中的五个 Android 浏览器文件会清除，原生启动清除旧配对凭据。外部 URL 继续交给系统浏览器。

`platform.ts` 通过官方 Platform 接口调用 `rpc.mjs`，AndroidX WebMessageListener 仅接受本地 UI 主框架。原生失败拒绝 Promise，用户取消保存返回 false。系统平台方法不伪装成功。剪贴板图片读取预算为 `AndroidPlatformHost.MAX_CLIPBOARD_IMAGE_BYTES`，默认 20 MiB，超出返回错误。

平台身份明确为 `android`；原生宿主提供的能力按接口接入，不声明 Electron 的 windowID 或电脑目录选择。`menu.ts` 为官方应用菜单提供编辑、重载、缩放及原生全屏/窗口动作；不支持的桌面动作明确禁用。菜单保存输入框焦点和选区，异步剪贴板期间编辑内容变化则明确拒绝操作。`files.mjs` 使用 WebView 标准文件 input，由 Android SAF 回传 URI，再顺序读取 `File`；官方会话导入因此可用，取消不导入、读取和校验失败继续抛错。保持原存储 namespace，升级不迁移或重置真实服务器。

多服务器时，旧根级服务器标签在路由解析层归入偏好设置，移除会抢写新增目标页的库存变化导航 effect。根级和嵌套页共用一个新增动作，移动顶部和宽屏侧栏均可持续添加；构建同时运行官方设置路由测试与新增回归用例。

触屏设备不具备悬停时，侧栏新增按钮常显。连接页使用的 `URL.parse` 在旧 Android WebView 中缺失，沿用上游 `runtime/polyfills.ts` 的 core-js 兼容入口，并在构建中验证原生 API 缺失的启动场景。

APK 内的 `ui/opencode-build.json` 记录来源；`/_assets/` 在磁盘映射为 `oa/`，避免 Android 资产合并过滤下划线目录。构建完全成功后才替换资产。CI 检查修改、删除和未跟踪的生成文件，避免漏提交新 chunk。

升级上游时先更新 pin、审核源补丁及 SDK 合同，完成类型检查、Android 单测、构建、手机/平板回归后再发布。
