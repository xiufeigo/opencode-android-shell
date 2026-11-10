# 官方源码构建

`upstream.json` 是当前 Android UI 唯一版本来源：官方仓库、提交 SHA、UI 版本、Bun 版本。`OPENCODE_APP_VERSION` 仅描述旧 `app/` 资产，不用于 `webapp/`。

升级基线只取官方**正式 release tag**，不使用 `dev` 分支。原因是该仓库 `dev` 频繁 rebase，同一改动会以多个提交重复出现，提交标题无法当作变更清单（`--first-parent` 三天内可达上千个提交），既不能作为 diff 基线也不能作为发版依据。`upstream.json` 的 `commit` 必须是某个正式 tag 的提交；历史上 2.0.26 → 2.2.6 每一轮都是如此，且不追求每个 tag 都跟（允许跳过中间版本）。核对上游增量时若临时参考 `dev`，只用于观察趋势，不写入 `upstream.json`、不据此改补丁。

`build.mjs` 创建独立 sparse checkout，每次构建固定工作区及 Desktop 图标目录范围，校验版本，应用可审核的源码补丁，冻结安装官方 lockfile，再执行 app 和 GUI 扩展的生产源码类型检查与 Vite 构建。核对上游时临时加入其他包也不能污染下一次构建的 Tailwind 扫描。类型检查排除上游测试和 Storybook 示例，它们依赖未纳入 Android 构建的测试工作区。Windows 会把 Git 的文本 symlink 还原为目标字节。

只需要运行：

```sh
node frontend/android/build.mjs
```

Bun 必须为 1.4.4；`BUN_BINARY` 可指定二进制路径。Terminal 独立执行 `frontend/terminal` 的 `npm ci && npm test && npm run build`。最终 APK 通过 `:webapp:assembleRelease` 构建。

源码补丁涉及：官方入口的平台提供者和路由恢复；服务器设置的导航状态及持续添加入口；终端模块入口改用本仓库自带引擎；共享 ResizeHandle 的触屏生命周期和点击范围；应用菜单的 Android 平台动作；官方中文词典的缺口填充。业务 API、会话状态、PTY、文件、设置和主题继续由官方实现负责。

`ui.patch` 保留 Android 返回和菜单接线、触屏面板拖动、上下文页全历史以及图片预览缩放。竖排与 inbox 侧栏使用官方的标签列表、导航和收起行为，不再替换为项目树。Android 菜单保留在官方侧栏头部；收起后可展开侧栏访问。

上下文页（`gui-plugins/src/context/tab.tsx`）打开时加载完整会话历史，加载中显示提示，失败时保留最近消息并提供重试。右侧面板仍保留窄屏拖拽范围和延后一帧挂载的适配。

提供商图标由 `provider-icons.ts` 统一解析：已知厂商的套餐、地区和 Console ID 复用对应品牌图标，未知 ID 沿用官方占位。`provider-icons-extra.svg` 固定补入腾讯、火山引擎和 LLM Gateway 图标；腾讯图形取自固定上游源码，其余图形取自 OpenCode 模型目录，不在构建时联网获取。构建会运行映射单测；`node scripts/verify-provider-icons.mjs` 验证打包界面中的实际图标。

终端触摸焦点规则：拖动只滚动，不抢焦点（不会因为滑一下历史就弹出键盘）；但拖动也不关闭已打开的键盘，手势结束时恢复它开始时已有的焦点，点击仍按原逻辑在释放时聚焦。`verify-android-terminal-release.mjs` 的 `drag-focus-separation` 与 `drag-keeps-focus` 两条检查分别钉住这两半；真实 APK 由 `verify-android-terminal-device.mjs` 的 `*-drag-does-not-open-ime` 在系统 IME 上复核（debug 包 debuggable ⇒ WebView 自带 `webview_devtools_remote_*` CDP 通道，无需改代码）。曾经在 pointerdown 无条件 `textarea.focus()` 的写法会同时破坏后半条，已改回按手势状态恢复。

中文文案：`i18n-zh.ts` / `i18n-zht.ts` 包装官方词典（`packages/app/src/runtime/i18n/zh-android.ts`、`zht-android.ts`），只补官方尚未翻译的 key，官方词条后展开、优先胜出。`scripts/extract-i18n.mjs` 只读官方源码，用来跟踪后续缺口。

2.1.4 把官方 UI 从 2.1.2（`a517687f`）升到正式 tag v2.1.4（`68bc26b`）。2.1.3 把 Bun 升到 **1.4.4**（Brotli、TLS、HTTP/3、WebSocket 安全修复），代码块与差异改用 Pierre Highlights；2.1.4 在桌面/Web 会话里显示 MCP 征询表单。堆快照命令只在 Electron `ctx.desktop` 下注册，Android 不暴露。

2.1.4 的新界面文案上游仍没有中文：`session.elicitation.selectPlaceholder` 已由本仓库补齐。免费额度 UI 仍依赖服务端 ≥2.1.2 的 `provider.free-limit`；MCP 表单依赖服务端发出 `session.form` 请求。未读徽标仍依赖服务端 `time.viewed`。

当前 UI 基准为官方 v2.2.6（`dc2ffcf`），Bun 仍是 **1.4.4**。按官方 app 和 ui 词典比对，本仓库补齐了 2.2.6 新增的 48 条简繁中文缺口。

2.2.0 把官方 UI 从 2.1.4（`68bc26b`）升到正式 tag v2.2.0（`1097e266`）。上游结构变化有三处，本仓库逐一跟进：

- `packages/gui-extensions` 更名为 `packages/gui-plugins`，`build.mjs` / `sources.mjs` / 两份补丁里的路径同步改名。
- GUI SDK 从 `packages/gui-extensions/src/sdk/` 迁到 `packages/plugin/src/gui/`，包名从 `@opencode/gui-extensions/sdk` 变为 `@opencode/plugin/gui`；旧 `sdk/core.ts` 里 `"web" | "desktop"` 的平台联合类型改到 `packages/plugin/src/gui/core.ts`，在这里补上 `"android"`。旧的 `builtins.typecheck.ts` 上游已删除，2.2.0 的 `compose()` 不再对“渲染条目必须有主进程提供者”做类型断言，所以浏览器扩展的 typecheck 移除逻辑不再需要复刻。
- 上游 2.2.0 的 `packages/app/src/runtime/polyfills.ts` 不再依赖 core-js，改成内联 polyfill。旧补丁靠 `core-js/stable/url/parse` 补 `URL.parse`（旧 Android WebView 缺失），2.2.0 的 lockfile 已无 core-js，因此改为在本仓库内联实现 `URL.parse`，`polyfills.test.ts` 保留官方的 Map.groupBy / Promise 用例并合并本仓库的 URL.parse 与配对链接用例。

官方 2.2.0 的 Shell 自带竖排标签、侧栏收起、peek 动画和折叠后的悬浮开关。项目沿用这些官方实现，不再维护独立项目树。

`build.mjs` 的工作区列表新增 `patchmark`：2.2.0 把 `packages/session-ui` 依赖的补丁渲染包拆成独立工作区，稀疏检出缺它时 bun 无法解析工作区图。


2.2.6 把官方 UI 从 2.2.5（`9bbb8ce`）升到正式 tag v2.2.6（`dc2ffcf`）。旧补丁无法直接 `git apply`，本仓库在纯净 v2.2.6 上重做等价的 Android 改动后重新导出两份补丁：

- `packages/app/src/servers/connect/camera.ts` 上游已删除，相机可用性判断改到 `packages/app/src/servers/connect/controller.ts` 的 `cameraSupport()`：Android 放行（只屏蔽 `desktop`）。
- `packages/app/src/session/helpers.ts` 的 `createSizing` 改由上游的 sizing 状态机实现，重复按下已被上游忽略，本仓库不再重复打补丁。
- `packages/app/src/session/screen.tsx` 的 `sideMounted` 延迟挂载改由上游的 `content-visibility` 方案承担。
- `packages/ui/src/components/resize-handle.tsx` 上游已原生使用 Pointer Events，`touch-action: none` 与 24px 触屏命中区保留在本仓库补丁的 CSS 里；旧的 `resize-pointer.mjs` 适配层随之删除。
- `packages/ui/src/components/image-preview.tsx` 上游改为 zoom 状态机，本仓库把双指捏合与双击缩放重写到新接口上（`zoomAt` / `follow` / `ease`）。
- `packages/app/src/entry.tsx` 跟随上游改用 `import.meta.env.VITE_OPENCODE_VERSION`，`PlatformProvider` 改用 Android 平台实现。
- `frontend/android/back.ts` 的设置搜索返回判断跟随上游改为 `canReturn()` + `back()`。

2.2.5 把官方 UI 从 2.2.1（`3ebcf9a`）升到正式 tag v2.2.5（`9bbb8ce`）。上游把终端整体搬到了服务端常驻 PTY，本仓库的终端接线随之改写：

- `packages/gui-plugins/src/terminal/model.ts` 不再 `import("ghostty-web")`，改从 `/oc-terminal.js` 载入本仓库自带的 xterm 引擎；`terminal.tsx` 保持 `convertEol: true`，让裸 `\n` 也回到行首。
- 旧的“旧实例快照交接”（`frontend/terminal/panel-lifecycle.mjs`）随本地快照一起消失：PTY 状态改由服务端持有，新实例用 `takeover` 直接接管，旧实例断开不会结束进程。该交接条与配套单测一并删除。
- `frontend/terminal/terminal.js` 的适配改为跟随官方的量测入口：官方 2.2.5 只调用 `FitAddon.proposeDimensions()`、不再调用 `fit()`（网格尺寸只认服务端下发的规范尺寸），因此本地布局（帧高、跟随贴底、横向滚动归零）改挂在 `proposeDimensions()` 上，`fit()` 只刷新布局、不再本地改网格。
- `session-model.test.ts` 按 2.2.5 的终端模型重写：同一目录下两个会话各自持有自己的终端、冷启动后仍能从服务端列回运行中的终端、关掉一个会话的终端不影响另一个会话、旧本地 `terminals` 存储被清除。该用例仍是 UI 构建的必跑检查。

2.0.15 将终端标签按会话分组持久化，复用官方工作目录存储，避免运行中的 PTY 归属被会话布局缓存裁剪。旧目录共享的标签由首次打开终端的会话一次性接收。`session-model.test.ts` 直接测试真实终端模型及持久化器，并加入 UI 构建的必跑检查。

2.0.16 接入 `frontend/remote-browser` 共享扩展和服务端插件的唯一 RPC 契约。浏览器运行在服务端，客户端使用现有 server.client 认证与事件，不恢复旧 IPC、gateway 或配对。`build.mjs --web` 使用同一固定源码和扩展生成 Web 分发；Android 明确要求原生平台桥，Web 明确使用标准浏览器平台，二者不做运行时静默降级。

2.0.17 用 `back.ts` 在官方 Shell 中注册 Android 返回处理，直接读取当前布局、设置层级与打开的标签。原生宿主不再使用 WebView 历史回退，页面未消费时交回 Android 默认返回；在首页离开 App，不复活已关闭的标签。`verify-android-back.mjs` 覆盖手机、平板，设置 `ANDROID_SERIAL` 后使用实际 APK 和系统返回键验证退出、重新进入。

2.0.18 将手机端 xterm 滚动条滑块视觉宽度缩为 8 CSS px，同时保留 14 CSS px 的布局与触摸命中区，避免高 DPR 设备上的滚动条过粗。

2.0.14 按用户要求移除内置 browser 扩展的 renderer 注册、平台桥、移动入口、配对和网关项目；官方类型检查同步移除该 composition 的浏览器断言，仍检查其余扩展的 IPC 依赖。旧构建缓存中的五个 Android 浏览器文件会清除，原生启动清除旧配对凭据。外部 URL 继续交给系统浏览器。

`platform.ts` 通过官方 Platform 接口调用 `rpc.mjs`，AndroidX WebMessageListener 仅接受本地 UI 主框架。原生失败拒绝 Promise，用户取消保存返回 false。系统平台方法不伪装成功。剪贴板图片读取预算为 `AndroidPlatformHost.MAX_CLIPBOARD_IMAGE_BYTES`，默认 20 MiB，超出返回错误。

平台身份明确为 `android`；原生宿主提供的能力按接口接入，不声明 Electron 的 windowID 或电脑目录选择。`menu.ts` 为官方应用菜单提供编辑、重载、缩放及原生全屏/窗口动作；不支持的桌面动作明确禁用。菜单保存输入框焦点和选区，异步剪贴板期间编辑内容变化则明确拒绝操作。`files.mjs` 使用 WebView 标准文件 input，由 Android SAF 回传 URI，再顺序读取 `File`；官方会话导入因此可用，取消不导入、读取和校验失败继续抛错。保持原存储 namespace，升级不迁移或重置真实服务器。

Android 字体选择器通过系统 `SystemFonts` 枚举通用字体，并额外读取 MIUI 当前 WebView 字体覆盖；过滤语言 fallback 和锁屏/主题资源，只显示可用于界面的字体。当前 MIUI 字体覆盖为 `MiSansVF.ttf` 时显示为 `MiSans`；同一 TTC 集合只显示一项并使用其第一面。字体按需加载，原生桥只返回不透明标识，WebView 拦截器只提供已枚举的系统字体，不开放任意文件路径。该系统 API 需要 Android 10 或更高版本，旧系统在设置页明确显示错误。`node scripts/verify-android-fonts.mjs` 验证三种字体角色、恢复默认和持久化；需安装 `plugins/server-browser` 的 Playwright 依赖及对应 Chromium。

多服务器时，旧根级服务器标签在路由解析层归入偏好设置，移除会抢写新增目标页的库存变化导航 effect。根级和嵌套页共用一个新增动作，移动顶部和宽屏侧栏均可持续添加；构建同时运行官方设置路由测试与新增回归用例。

触屏设备不具备悬停时，侧栏新增按钮常显。连接页使用的 `URL.parse` 在旧 Android WebView 中缺失，沿用上游 `runtime/polyfills.ts` 的 core-js 兼容入口，并在构建中验证原生 API 缺失的启动场景。

APK 内的 `ui/opencode-build.json` 记录来源；`/_assets/` 在磁盘映射为 `oa/`，避免 Android 资产合并过滤下划线目录。构建完全成功后才替换资产。CI 检查修改、删除和未跟踪的生成文件，避免漏提交新 chunk。

内置 UI 不注册官方 service worker：`upstream.patch` 在 `entry.tsx` 把注册条件改成 `!android`，Web 分发（`platform: web`）保持官方 PWA 行为。原因是 Android 的页面由本机 127.0.0.1 提供，workbox 预缓存没有收益；而 `skipWaiting: false` 会让**上一版 APK** 的 worker 在覆盖安装后继续接管导航，页面可能拿到旧 `index.html` 与已随旧包删除的 chunk（出现过整页 `ReferenceError` 白屏）。`oc-host.js` 在文档开始（早于页面脚本）反注册所有 worker、删除 Cache Storage，并在当前文档仍被旧 worker 接管时重载一次（`sessionStorage` 标记防循环）。`EmbeddedUiServiceWorkerTest` 钉住三件事与防止撤回，`platform: web` 的注册行必须保留。

升级上游时先更新 pin、审核源补丁及 SDK 合同，完成类型检查、Android 单测、构建、手机/平板回归后再发布。
