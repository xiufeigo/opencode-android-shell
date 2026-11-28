# 架构地图

本目录是仓库架构治理的当前工作区。`research/` 保留历史调查证据；本目录只记录当前仍然成立的模块所有权、架构决策、任务池和治理进度。

## 产品形态

仓库交付一个 Android WebView 宿主。APK 内嵌官方 OpenCode Web UI，页面直接连接用户选择的 OpenCode v2 服务。Android 宿主只提供 WebView 容器、本地静态 UI 来源、系统能力与平台桥，不再保存服务器凭据，也不代理 API。

## 当前权威来源

| 关注点 | 权威来源 | 说明 |
| --- | --- | --- |
| 上游 UI 版本与提交 | `frontend/android/upstream.json` | 构建脚本校验上游包版本。 |
| Android 版本与包名 | `webapp/build.gradle.kts` | CI 用 APK badging 反向校验。 |
| 官方 UI 生成产物 | `webapp/src/main/assets/www/ui/` | 由 `node frontend/android/build.mjs` 生成，禁止手改。 |
| 终端引擎源码 | `frontend/terminal/terminal.js` | 生成 `webapp/src/main/assets/www/oc-terminal.js`。 |
| 终端生成产物 | `webapp/src/main/assets/www/oc-terminal.js` | CI 要求与源码构建字节一致。 |
| 宿主运行时源码 | `frontend/host-runtime/` | `index.html`、`oc-perf.js`、`oc-preflight.js` 直接复制；`oc-host.js` 由职责拆分的 facade/shim 拼件按固定顺序生成。 |
| 宿主运行时生成产物 | `webapp/src/main/assets/www/{index.html,oc-host.js,oc-perf.js,oc-preflight.js}` | 由 `node frontend/host-runtime/build.mjs` 生成；架构检查要求与权威源码字节一致。 |
| Android 上游补丁与覆盖源码 | `frontend/android/` | `upstream.patch` / `ui.patch` 修改上游，`sources.mjs` 声明覆盖文件。 |
| 内嵌 UI 存储迁移 | `frontend/embedded-ui/` | 由 `sources.mjs` 注入上游应用。 |
| 服务端浏览器 RPC 契约 | `plugins/server-browser/src/rpc.ts` | 由 `frontend/remote-browser/sync-rpc.mjs` 复制生成客户端输入。 |
| 服务端浏览器插件 | `plugins/server-browser/` | OpenCode v2 插件。 |
| 浏览器面板客户端 | `frontend/remote-browser/` | 由 `sources.mjs` 复制进上游 `gui-plugins`。 |
| Kotlin/JS 宿主桥契约 | `docs/architecture/host-bridge-contract.json` | 机器可读契约清单；`check-architecture` 校验生产者、消费者与字面量不漂移。 |
| Android 宿主组合根 | `webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt` | Activity 生命周期、框架入口注册和能力模块组装；当前约 580 行。 |
| Android 宿主能力 | `webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/` | 启动导航/恢复、WebView bootstrap、WebViewClient/WebChromeClient 适配、后台生命周期、电源、通知、相机、insets、主题、字体、本地 UI 服务、桥接等深模块。 |
| 签名规则 | `AGENTS.md` 与 CI workflow | 本机与 CI 使用同一证书指纹；凭据不进入仓库。 |

## 依赖方向

```text
frontend/android 构建配方 ──生成──> webapp/assets/www/ui
frontend/terminal ──生成──> webapp/assets/www/oc-terminal.js
frontend/host-runtime ──复制/拼接生成──> webapp/assets/www/{index.html,oc-host.js,oc-perf.js,oc-preflight.js}
plugins/server-browser/src/rpc.ts ──sync-rpc.mjs──> frontend/remote-browser/rpc.ts ──上游构建复制──> gui-plugins
WebHostActivity ──组合/注入──> webapp/host 能力模块
webapp/host 能力模块 ──不依赖──> WebHostActivity 具体类
webapp/assets/www/oc-host.js ──页面侧 facade/shim──> 内嵌 UI 与 Android 覆盖源码
scripts ──验证──> 以上产物与运行时契约
```

目标方向是入口层编排能力模块，能力模块不反向依赖入口实现。生成产物可以存在，但必须有唯一源码、可重复构建和防漂移检查。

## host-runtime 拼件所有权

`oc-host.js` 仍是一个 document-start 运行时产物，但权威源码已按变化轴拆分，并由 `frontend/host-runtime/build.mjs` 按固定顺序拼接：

1. `facade-bootstrap.js`：native 桥探测、终端引擎标记、嵌入式 UI service worker/cache 清理、facade 共享状态。
2. `scheme-bridge.js`：宿主深浅色到 `prefers-color-scheme` 的桥。
3. `safe-area-style.js`：顶部安全区与视口高度 CSS。
4. `back-overlay.js`：Android Back 触发可见浮层关闭。
5. `ime-insets.js`：IME CSS、insets 应用到 CSS 变量与根节点高度。
6. `surface-color.js`：页面表面色解析。
7. `android-facade.js`：`window.__ocAndroid` 对页面公开的 facade API。
8. `notification-shim.js`：浏览器 Notification API 垫片。
9. `background-state-shim.js`：后台 visibility/focus 读数 shim。
10. `editable-arrow-guard.js`：输入框边界方向键守卫。
11. `round-power-sensor.js`：fetch/SSE 回合电源传感器。
12. `surface-color-tail.js`：表面色 observer 与外层 IIFE 收口。
13. `overlay-focus.js`：浮层自动聚焦键盘抑制。
14. `terminal-settings.js`：终端字号设置契约。

这些文件目前共享同一个 document-start 闭包，因此**拼接顺序是行为契约**。拆分的目标是降低修改半径与并行冲突，而不是把它们伪装成独立 ES module；后续若要真正模块化，必须先显式化共享状态并重建行为基线。

## 当前主要偏离

1. 大量单元测试仍是历史事故型字符串绊线，直接读取 Kotlin/JS 源文本。它们能防回归，也把实现位置固化；后续应分批升级为行为契约测试，确实无法行为化的资产约束集中收纳。
2. `scripts/verify-android-terminal-release.mjs` 仍是 4000 行级验证 harness，和其他设备验证脚本存在重复基础设施；需要收敛成共享驱动层。
3. Kotlin/JS 桥契约（事件名、extra 名、存储键、接口方法）仍主要靠源码绊线守护，还没有统一的契约清单或生成常量。
4. 上游 UI 生成产物边界主要靠构建脚本与审查约束，还缺少和 host-runtime 同等级的显式生成/防漂移检查。
5. `research/` 同时包含仍有效证据与已失效历史叙述；有效工程约束需要继续迁入本目录。

## 治理规则

- 每轮只做一个小而完整的闭环：建立基线、迁移调用方、删除旧实现、验证、提交、记录。
- 不只新增抽象。新抽象必须通过删除测试：删掉它会让调用方更复杂，才允许存在。
- 生成产物不手改。手改源码与生成产物必须能通过字节级或契约级检查证明一致。
- 行为兼容优先。上游 UI 契约、APK 包名、签名证书、服务器协议、存储键和本地数据迁移都不能擅自破坏。
- 历史事故编号可以留在 research 证据里，不应成为当前模块命名和结构的主轴。
