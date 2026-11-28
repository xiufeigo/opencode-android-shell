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
| 宿主运行时源码 | `frontend/host-runtime/` | `oc-host.js`、`oc-perf.js`、`oc-preflight.js`、诊断页的唯一权威源码。 |
| 宿主运行时生成产物 | `webapp/src/main/assets/www/{index.html,oc-host.js,oc-perf.js,oc-preflight.js}` | 由 `node frontend/host-runtime/build.mjs` 逐字节复制生成。 |
| Android 上游补丁与覆盖源码 | `frontend/android/` | `upstream.patch` / `ui.patch` 修改上游，`sources.mjs` 声明覆盖文件。 |
| 内嵌 UI 存储迁移 | `frontend/embedded-ui/` | 由 `sources.mjs` 注入上游应用。 |
| 服务端浏览器 RPC 契约 | `plugins/server-browser/src/rpc.ts` | 构建时复制到浏览器面板客户端。 |
| 服务端浏览器插件 | `plugins/server-browser/` | OpenCode v2 插件。 |
| 浏览器面板客户端 | `frontend/remote-browser/` | 由 `sources.mjs` 复制进上游 `gui-plugins`。 |
| Android 宿主入口 | `webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt` | Activity 生命周期和 WebView 编排。 |
| Android 宿主能力 | `webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/` | 通知、文件、字体、本地 UI 服务、insets、电源与桥接。 |
| 签名规则 | `AGENTS.md` 与 CI workflow | 本机与 CI 使用同一证书指纹；凭据不进入仓库。 |

## 依赖方向

```text
frontend/android 构建配方 ──生成──> webapp/assets/www/ui
frontend/terminal ──生成──> webapp/assets/www/oc-terminal.js
frontend/host-runtime ──复制生成──> webapp/assets/www/{index.html,oc-host.js,oc-perf.js,oc-preflight.js}
plugins/server-browser/src/rpc.ts ──复制──> frontend/remote-browser 构建输入
webapp/host 能力模块 ──被编排──> WebHostActivity
webapp/assets/www/oc-host.js ──页面侧 facade──> 内嵌 UI 与 Android 覆盖源码
scripts ──验证──> 以上产物与运行时契约
```

目标方向是入口层编排能力模块，能力模块不反向依赖入口实现。生成产物可以存在，但必须有唯一源码、可重复构建和防漂移检查。

## 当前主要偏离

1. `oc-host.js` 是 1413 行单体，混合缓存清理、主题桥、insets/IME、返回 facade、通知垫片、后台生命周期、终端设置和终端兼容代码。
2. `WebHostActivity` 约 1500 行，同时编排 WebView 构造、生命周期、电源、通知权限、相机权限、返回、渲染恢复、外链和诊断。可变状态集中在 Activity 内。
3. 大量单元测试是历史事故绊线，直接断言资产源码字符串。它们能防回归，也把实现细节固化，阻碍删除死代码和移动模块。
4. `frontend/remote-browser` 在仓库内缺少它导入的 `./rpc` 文件；该文件由构建复制生成。这个契约需要显式化，不能让本地读者靠猜。
5. 验证脚本包含过期行号和历史叙述。行号不是稳定契约。

## 治理规则

- 每轮只做一个小而完整的闭环：建立基线、迁移调用方、删除旧实现、验证、提交、记录。
- 不只新增抽象。新抽象必须通过删除测试：删掉它会让调用方更复杂，才允许存在。
- 生成产物不手改。手改源码与生成产物必须能通过字节级或契约级检查证明一致。
- 行为兼容优先。上游 UI 契约、APK 包名、签名证书、服务器协议、存储键和本地数据迁移都不能擅自破坏。
- 历史事故编号可以留在 research 证据里，不应成为当前模块命名和结构的主轴。
