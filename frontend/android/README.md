# 官方源码构建

`upstream.json` 是当前 Android UI 唯一版本来源：官方仓库、提交 SHA、UI 版本、Bun 版本。`OPENCODE_APP_VERSION` 仅描述旧 `app/` 资产，不用于 `webapp/`。

`build.mjs` 创建独立 sparse checkout，每次构建固定工作区及 Desktop 图标目录范围，校验版本，应用可审核的源码补丁，冻结安装官方 lockfile，再执行 app 和 GUI 扩展的生产源码类型检查与 Vite 构建。核对上游时临时加入其他包也不能污染下一次构建的 Tailwind 扫描。类型检查排除上游测试和 Storybook 示例，它们依赖未纳入 Android 构建的测试工作区。Windows 会把 Git 的文本 symlink 还原为目标字节。

只需要运行：

```sh
node frontend/android/build.mjs
```

Bun 必须为 1.4.4；`BUN_BINARY` 可指定二进制路径。Terminal 独立执行 `frontend/terminal` 的 `npm ci && npm test && npm run build`。最终 APK 通过 `:webapp:assembleRelease` 构建。

源码补丁涉及：官方入口的平台提供者和路由恢复；服务器设置的导航状态及持续添加入口；终端稳定模块入口及旧实例快照交接；共享 ResizeHandle 的触屏生命周期和点击范围；应用菜单的 Android 平台动作；官方中文词典的缺口填充。业务 API、会话状态、PTY、文件、设置和主题继续由官方实现负责。

`sidebar.patch` 叠加在 `upstream.patch` 之后，负责垂直布局的左侧栏：项目树、顶部四按钮（应用菜单 / 收起侧栏 / 后退 / 前进）、“项目”标题右侧的添加项目 +、文件夹的 ⋯ 与新建会话按钮、桌面右键与触屏长按菜单、会话内联重命名，以及收起后嵌入会话标题（显示器图标前）的两个按钮。文件夹行使用 `currentColor` 的单色文件夹图标，随深浅色主题切换；会话行不带头像但保留标题缩进；每个文件夹默认只列最近 5 个会话，超出时用“展开其余的 x 个会话 / 收起”切换；子 agent 会话不直接铺平，而是通过主会话左侧的箭头按需载入并缩进展示。多服务器时，“新建会话”上方提供服务器下拉框，项目树只显示选中服务器，项目来源与水平首页一致，只列已添加/打开的项目，不再从会话目录推导未打开项目。左侧栏收起/展开使用与右侧栏一致的 160ms 宽度过渡，内容保持固定宽度、只裁剪容器，避免项目树在动画期间逐帧重排；共享 `ResizeHandle` 的拖拽合并到每个动画帧一次。右侧审阅栏保持官方布局与拖拽调宽，动画缩短到 160ms、去掉对 `width` 无效的 `will-change`，并把审阅内容挂载推迟到动画第一帧之后；窄屏下给聊天列保留至少 120px 的可拖行程，避免分隔条被锁在 450px。这样保留官方推挤式动效，同时减少逐帧重排和首次点击的挂载阻塞。上下文页（`gui-plugins/src/context/tab.tsx`）打开时会把该会话的完整历史一次拉全再发布（`data.session.message.loadMore(id, { all: true })`），所以“消息数 / 原始消息列表 / 系统提示词”与桌面端一致；加载中显示提示，失败时保留最近消息、显示原因并提供重试。旧实现只读时间线首屏的最近一页，会话开头的 `system` 通知（系统提示词）不在窗口内时整块隐藏，看起来像 Android 少了这个区块。
`shell.tsx` / `titlebar.tsx` 的竖排与 Android 平台改动由 `sidebar.patch` 一并承载，`upstream.patch` 不再单独修改这两个文件，避免同一文件跨补丁叠加。会话页与右侧栏保持官方实现，不再注册面板网格或标题栏右侧插槽。中文词条补 `sidebar.collapse` / `sidebar.expand` 及会话预览、子会话展开相关文案。

终端触摸焦点规则：拖动只滚动，不抢焦点（不会因为滑一下历史就弹出键盘）；但拖动也不关闭已打开的键盘，手势结束时恢复它开始时已有的焦点，点击仍按原逻辑在释放时聚焦。`verify-android-terminal-release.mjs` 的 `drag-focus-separation` 与 `drag-keeps-focus` 两条检查分别钉住这两半；真实 APK 由 `verify-android-terminal-device.mjs` 的 `*-drag-does-not-open-ime` 在系统 IME 上复核（debug 包 debuggable ⇒ WebView 自带 `webview_devtools_remote_*` CDP 通道，无需改代码）。曾经在 pointerdown 无条件 `textarea.focus()` 的写法会同时破坏后半条，已改回按手势状态恢复。

中文文案：`i18n-zh.ts` / `i18n-zht.ts` 包装官方词典（`packages/app/src/runtime/i18n/zh-android.ts`、`zht-android.ts`），只补官方尚未翻译的 key，官方词条后展开、优先胜出，所以上游补齐后无需删除。按未打补丁的 2.1.4 源码，`extract-i18n.mjs` 得到 en 1276 / zh 1106 / zht 1106，官方 app+ui 词典缺口 **170** 条（含本轮 `session.elicitation.selectPlaceholder`），覆盖词典全部补上。另外 8 条侧栏英文是本仓库写进 `en.ts` 的，不在上游词典里，同样有中文。本基准下中文界面不再有因官方 app 词典缺口而回退英文的条目。`scripts/extract-i18n.mjs` 继续只读官方源码，用来跟踪后续缺口；`research/_i18n_v2/_pending_*.json` 是早前轮次的人工快照，与当前版本已不匹配，不再作为来源。

2.1.4 把官方 UI 从 2.1.2（`a517687f`）升到正式 tag v2.1.4（`68bc26b`）。2.1.3 把 Bun 升到 **1.4.4**（Brotli、TLS、HTTP/3、WebSocket 安全修复），代码块与差异改用 Pierre Highlights；2.1.4 在桌面/Web 会话里显示 MCP 征询表单。现有 `upstream.patch` / `sidebar.patch` 的目标里，上游只改了 `desktop-menu.ts`（文档链接与扩展菜单插入）和 `runtime/i18n/en.ts`（新增一条占位文案），改动区与补丁上下文不重叠。堆快照命令只在 Electron `ctx.desktop` 下注册，Android 不暴露。类型检查改为 `bun check -p tsconfig.android.json`，因为上游 `typecheck` 脚本不再接受位置参数形式的 tsconfig。

2.1.4 的新界面文案上游仍没有中文：`session.elicitation.selectPlaceholder` 已由本仓库补齐。免费额度 UI 仍依赖服务端 ≥2.1.2 的 `provider.free-limit`；MCP 表单依赖服务端发出 `session.form` 请求。未读徽标仍依赖服务端 `time.viewed`。

2.2.0 把官方 UI 从 2.1.4（`68bc26b`）升到正式 tag v2.2.0（`1097e266`），Bun 仍是 **1.4.4**。上游结构变化有三处，本仓库逐一跟进：

- `packages/gui-extensions` 更名为 `packages/gui-plugins`，`build.mjs` / `sources.mjs` / 两份补丁里的路径同步改名。
- GUI SDK 从 `packages/gui-extensions/src/sdk/` 迁到 `packages/plugin/src/gui/`，包名从 `@opencode/gui-extensions/sdk` 变为 `@opencode/plugin/gui`；旧 `sdk/core.ts` 里 `"web" | "desktop"` 的平台联合类型改到 `packages/plugin/src/gui/core.ts`，在这里补上 `"android"`。旧的 `builtins.typecheck.ts` 上游已删除，2.2.0 的 `compose()` 不再对“渲染条目必须有主进程提供者”做类型断言，所以浏览器扩展的 typecheck 移除逻辑不再需要复刻。
- 上游 2.2.0 的 `packages/app/src/runtime/polyfills.ts` 不再依赖 core-js，改成内联 polyfill。旧补丁靠 `core-js/stable/url/parse` 补 `URL.parse`（旧 Android WebView 缺失），2.2.0 的 lockfile 已无 core-js，因此改为在本仓库内联实现 `URL.parse`，`polyfills.test.ts` 保留官方的 Map.groupBy / Promise 用例并合并本仓库的 URL.parse 与配对链接用例。

官方 2.2.0 重写了 Shell：自带竖排标签、侧栏收起（`settings.appearance.sidebarOpen()`）、peek 动画和折叠后的悬浮开关。Android 侧的竖排项目树不再自带一套收起状态，而是复用官方的 `sidebarOpen` 设置；`shell/layout/vertical.ts` 保留为路由与外壳共用的 `useVerticalTabs()` 取值入口，判定条件与 2.2.0 的 `shell.tsx` 对齐（`tabLayout() !== "horizontal" && !mobile()`）。`shell.tsx` / `titlebar.tsx` 的竖排与 Android 平台改动仍由 `sidebar.patch` 承载。

`build.mjs` 的工作区列表新增 `patchmark`：2.2.0 把 `packages/session-ui` 依赖的补丁渲染包拆成独立工作区，稀疏检出缺它时 bun 无法解析工作区图。


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
