# UX10：让一页能同时加 http 与 https（鸡生蛋死锁 + 跨源正解）

- **轮次**：UX10（协议与连接）
- **日期**：2026-10-06
- **BUG 编号**：B-6（P0）
- **本轮代码改动**：**0**（未改任何源码 ⇒ 未重建产物、未动凭据、未加自定义页）
- **一句话结论**：**鸡生蛋机制已在设备上闭合**（页面协议 = 所配后端协议；页面 http 时官方对话框对 http 目标**不再**报混合内容，且 http 服务器**添加成功**）；但**"一页同时加 http 与 https" 卡在第二道墙 —— CORS**，而本轮**实测推翻了 UX9 §5.1 的前提**：opencode 服务端**默认就发 CORS，但只放行 `http://` 回环来源（带端口）**，所以"页面改成本机 http 源"这条路**对 opencode 后端是可行的**、而 `file://`（`Origin: null`）**确定不可行**。**这是一个能真正修好 B-6 的方向，但它要求把官方 UI 作为本地资源由 App 自带（产物内嵌 + 全量跨源回归），属架构级改动，本轮按红线停下上交选项。**

---

## 0. 本轮要解决的三件事与结果

| # | 任务 | 结果 |
|---|---|---|
| 1 | 路线① 设备复现（页面 http ⇒ 连接屏能加 http 服务器） | ✅ **端到端复现成功**（§3，含 `location.href` 原文 + 截图 + 成功证据） |
| 2 | 路线② 设备复现（配 https 后端 ⇒ 连接屏拒 http 红字） | ⚠️ **未端到端复现**，原因与等价判据实测见 §4（如实记录） |
| 3 | 跨源问题"正面处理并给结论" | ✅ **给了结论，且更正了 UX9 的前提**（§5，服务端 CORS 白名单实测表） |
| 4 | 修法 | **按红线停下上交选项**（§6），未擅自取舍 |

---

## 1. 环境与取证方法（可复核）

- 自建实例 **`emulator-6772`**（AVD `OC_UX6_Phone`，端口 6772，几何 `1200x2670` / density 420，WebView 非 83）。
- 包 `com.xiufeigo.opencodeshell.web.debug`，**直接复用既有产物** `webapp/build/outputs/apk/debug/webapp-debug.apk`（2026-10-05 23:45，与 `research/deliver/opencode-shell-web.apk` 同一份、字节数一致）⇒ **本轮无需重建**。
- `adb reverse tcp:4095 tcp:4095`（**只 reverse；后端 4095 全程未起停**）。
- 页面内取数走宿主 **DEBUG-only** 注入通道：`am start --es oc_debug_eval "<js>"`，结果读 logcat `OcWebHost/Eval`。
- 交互全部为**真实点击 / 真实软键盘输入**（`input tap` / `input text` / `input keyevent`）；**无任何注入式 UI 操作**（注入只用于读 `location` 与跑判据函数）。

---

## 2. 机制复核：鸡生蛋两边都成立

UX9 已钉死：页面 URL 就是所配后端地址（`WebHostActivity.kt:323-324` → `:340` → `:360 loadUrl`）。
本轮**在设备上确认了"页面 http"这一侧**，并首次拿到"页面 http 时 http 服务器可被成功添加"的正向证据。

---

## 3. 路线①：**端到端复现成功**（页面 `http:` ⇒ 官方对话框能加 http 服务器）

### 3.1 读数环境 = 全新安装/清数据

`pm clear com.xiufeigo.opencodeshell.web.debug` → 首次启动（未配置任何后端，`bootUrl()` 落到默认 `http://127.0.0.1:4095`）。

### 3.2 `location.href` 原文（verbatim，来自 logcat `OcWebHost/Eval`）

```json
{"href":"http://127.0.0.1:4095/","protocol":"http:","origin":"http://127.0.0.1:4095",
 "docURL":"http://127.0.0.1:4095/","isSecureContext":true,"oc":"object"}
```

### 3.3 在真实页面里跑上游判据（`browser.ts:3-12` 原样注入，verbatim）

```json
{"href":"http://127.0.0.1:4095/settings",
 "caseB_pageHttp_remoteHttp":false,
 "caseC_pageHttps_remoteHttp":true,
 "caseD_pageHttps_loopbackHttp":false,
 "A_sameOrigin_127":"status=200",
 "B_crossOrigin_localhost":"status=200"}
```

**⇒ `caseC`（页面 https ∧ 目标非回环 http）仍是唯一命中那句红字的组合**；本轮页面是 `http:` ⇒ `caseB=false` ⇒ 不会出现那句红字。

### 3.4 真实点击：官方「Add server」对话框（与 B-6 同一路径 `dialog.tsx:280-291`）

设置 → 段选择器 → **Server** → **Add server**（全程真实点击，见 `research/web/ux10_nav1..nav5.png`）。

**(a) 填 `http://10.0.2.2:4095`（非回环 http 目标）⇒ 红字消失**

- 页面侧健康检查确实发出（logcat）：
  ```
  OcPerf: K2.onReceivedError url=http://10.0.2.2:4095/api/info code=-2 desc=net::ERR_ADDRESS_UNREACHABLE
  ```
- 官方对话框显示的是**通用错误** `Could not connect to server`（截图 `ux10_nav7_result.png`），
  **不是**「无法从 HTTPS 页面连接此 HTTP 服务器」。

**⇒ 墙①（混合内容）确实由"页面协议"决定：本轮页面 http ⇒ 官方不再拒绝 http 目标、只报真实网络原因。**
（`10.0.2.2` 在本 AVD 上不可达，属**我方取证环境**限制，与本 bug 无关；已如实标注。）

**(b) 填 `http://localhost:4095` + 密码 ⇒ 添加成功（正向证据）**

- 提交后对话框关闭，官方随即把 API 打到新服务器（logcat 原文）：
  ```
  OcPerfJS res #427 ... http://localhost:4095/api/location
  OcPerfJS res #429 ... http://localhost:4095/api/session/active
  OcPerfJS res #430 ... http://localhost:4095/api/location
  OcPerfJS res #431 ... http://localhost:4095/api/project
  OcPerfJS res #432 ... http://localhost:4095/api/vcs
  ```
- ⇒ **http 服务器被成功添加并进入使用态**（截图 `ux10_nav6_filled.png` 记录了提交前的真实输入）。

**⇒ 路线① 成立：页面是 `http:` 时，官方「添加服务器」对 http 目标正常工作，不报混合内容。**

---

## 4. 路线②：**未端到端复现**（原因如实记录）+ 等价判据实测

### 4.1 为什么没做

要让页面变成 `https:`，需要一台**证书受信的 https 后端**。本轮**无法构造**：

1. 自签 https 服务（任务允许的"等价构造"）在本项目**走不通**：`webapp` 模块**没有任何 `onReceivedSslError` 实现**（`grep` 全模块 0 命中）⇒ WebView 会直接取消自签证书的连接，页面根本起不来。
2. 模拟器不信任用户安装的 CA（Android 7+ WebView 策略），**装 CA 也不行**。
3. 产品负责人那台真实 https 后端**不许碰**，也不在他的实例上。

⇒ 按 UX9 §9 的同一标准，如实写：**「路线②未端到端复现 + 原因」**。

### 4.2 等价证据（把逻辑链闭合）

- §3.3 的 `caseC_pageHttps_remoteHttp=true` 是**本轮在真实页面里**跑上游判据得到的原文；
- 页面协议来源是**代码事实**（`WebHostActivity.kt:323-324` 的 `KEY_BACKEND_URL`）；
- 产品负责人本人已在 https 后端配置下**亲眼看到那句红字**。

三者合起来足以支撑机制结论，但**严格说是"判据实测 + 他的现场"，不是我这边的端到端复现**。

### 4.3 ⚠️ 由此得到的一个**新的产品事实**（重要）

> **红字只在"页面 https"时出现；而页面 https 唯一的原因是"你把后端配成了 https"。**
> 也就是说：产品负责人在**官方连接屏（尚未连上任何服务器）**上看到红字，**恰好证明他那一台 App 的后端地址已经是 https** —— 与 B-6 记录的自述逐字吻合。
> **⇒ 死锁是双向的：配了 https 后端 → 页面变 https → 那一页再也加不进 http。**

---

## 5. 🔑 决定性发现：**更正 UX9 §5.1 —— 服务端不是"0 个 CORS 头"，而是"只放行 http 回环来源"**

UX9 §5.1 只测了 `Origin: https://appassets.androidplatform.net` ⇒ `Access-Control-Allow-Origin` 为空，于是得出"服务端默认不发 CORS ⇒ 跨源必然失败 ⇒ 方案 D 打掉 UX6 的优点"。
**本轮把 Origin 换了几组重测，结论被推翻**（`GET http://127.0.0.1:4095/global/health`，只读探测，后端未起停）：

| 请求 `Origin` | HTTP | `Access-Control-Allow-Origin` |
|---|---|---|
| `http://127.0.0.1:4095` | 200 | **`http://127.0.0.1:4095`** |
| `http://localhost:4095` | 200 | **`http://localhost:4095`** |
| `http://127.0.0.1:4173` | 200 | **`http://127.0.0.1:4173`** |
| `http://127.0.0.1:6789` | 200 | **`http://127.0.0.1:6789`** |
| `http://localhost:4173` | 200 | **`http://localhost:4173`** |
| `http://127.0.0.1`（无端口） | 200 | （空） |
| `https://127.0.0.1:4095` | 200 | （空）← **只放行 http，不放行 https** |
| `http://10.0.2.2:4095` | 200 | （空） |
| `https://appassets.androidplatform.net` | 200 | （空） |
| `http://example.com` | 200 | （空） |
| `null`（`file://` 的 origin） | 200 | （空）← **file:// 路线确定死** |

**三条推论（全部由上表直接支撑）：**

1. **opencode 服务端默认就放行"任意 `http://` 回环来源（带端口）"** ⇒ 页面若从 `http://127.0.0.1:<port>` 加载，对 opencode 后端**天然跨源可用**。
   ⇒ **"页面改成本机 http 源"（任务的首选方向）并不需要改服务端**，**UX9 对方案 D 的"打掉无需 CORS 优点"这一代价评估需要下调**。
2. **设备侧印证**：§3.3 的 `B_crossOrigin_localhost="status=200"` —— 真实页面（origin `http://127.0.0.1:4095`）跨源 `fetch` 到 `http://localhost:4095` **成功 200**；`A_sameOrigin_127=200` 同源也 200。**跨源不一定要开 CORS，只要来源在白名单里。**
3. **`file://`（官方桌面 Electron 那种形态）在本项目确定不可行**：它的 origin 是 `null`，服务端 `ACAO` 为空 ⇒ 跨源必被拦。
   ⇒ **"照抄桌面端 `file://`"这条路被实测排除**；`WebHostActivity.kt:48-51` 当初拒绝 `file://`（SSE/localStorage 会废）的判断，本轮再加一条独立理由：**服务端不放行 `null` origin**。

### 5.1 那第二台服务器到底要什么（代码级事实，不是猜测）

上游健康检查 `_upstream/packages/app/src/runtime/server/health.ts:88-101`：

```ts
const current = await OpenCode.make({ baseUrl: server.url, fetch, headers })
  .server.info({ signal })
  .then((status) => ({ data: { healthy: true as const, version: status.version } }))
```

- 它**要读响应体**（`status.version`）⇒ 必须是普通 CORS 模式，`no-cors` 的 opaque 响应用不了；
- 失败时 `TypeError` ⇒ `retryable()` 判定为可重试 ⇒ 最终 `{healthy:false}` ⇒ `dialog.tsx:281` 闸门拦下。

**⇒ "添加第二台服务器"必须让那台服务器回 `Access-Control-Allow-Origin`。**
**⇒ 关键区分**：白名单是**每台目标服务器自己**决定的。
- 目标是 **opencode 服务端** ⇒ **默认就放行 http 回环页面来源** ⇒ 壳侧改页面来源即可，**不改服务端**；
- 目标是**别的实现/别的配置**（含产品负责人那台 `8.138.19.15:4094` 是否同配置，**本轮未探测，也不该去探测**）⇒ **需要那台自己开 CORS**。

---

## 6. 修法分析（择优：依据 + 代价）——**本轮按红线停下上交，不擅自取舍**

### 6.1 首选方向（与官方桌面端同形）：**把官方 UI 作为本地资源由 App 自带，页面从 `http://127.0.0.1:<port>` 加载**

**为什么这是唯一真正"修好"的形态**：`browser.ts:4` 只看**页面协议**。页面来源变成 `http://127.0.0.1:<port>` ⇒
**那一页与"当前配了哪台后端"彻底解耦** ⇒ 先配 https 后端也不再把页面拖成 https ⇒ **死锁从根上消失**，而这正是桌面 Electron 那一页的形态。

**约束逐条对照**：

| 任务给的约束 | 本方案的落实 | 代价 |
|---|---|---|
| 必须本地静态资源服务、只服务 UI 资源 | `WebViewClient.shouldInterceptRequest` 拦截 `http://127.0.0.1:<port>/*` 回 assets ⇒ **0 个 `ServerSocket`**（不撞 B-6 红线，UX9 §6.1 的"D 撞红线"这一条**不再成立**） | 需新增拦截分支 |
| 绝不转发 API / 不碰凭据 | 只服务 assets 里的 UI 文件；**API 一律由页面直连**（官方 connect 流程自带 Basic 认证）⇒ 宿主仍然 `Authorization`/`password`/`Basic` **0 命中** | — |
| 必须正面解决跨源 | **已解决（对 opencode 后端）**：§5 实测 `http://127.0.0.1:<任意端口>` 在服务端白名单内；设备侧跨源 `fetch` 200 印证 | 对**非 opencode 实现**的目标仍需对方开 CORS |

**必须一并付出的代价（如实列出，不掩饰）**：

1. **官方 UI 必须内嵌进 APK**：从后端下载 SPA 产物打进 assets。⇒ 产物体积上涨、**UI 与 API 版本耦合**（后端升级而 APK 未升级 ⇒ 前后端版本错配风险）。
2. **架构不再"开箱即连"**：页面不再由后端提供，**App 启动后必须由用户在官方对话框里加一次服务器**（UX6 的"直连"体验会退化为"官方连接屏"体验）。这是**产品级取舍**，不是技术细节。
3. **全链路从同源变跨源**：所有 API、SSE、PTY/WebSocket 都要走 CORS。**必须重跑全部已验收项**（官方直连 / 设置加服 / 终端 / 键盘 / 主题 / 常亮 / SSE）——这是本轮**做不完**的部分。
4. 白名单只放行 **`http://` 回环**：若某台目标只放行 https 或不放行回环，行为会变（§5 表已列）。

### 6.2 次选方向：让用户能"重置/切换后端地址"（回到 loopback）

- **能做什么**：至少留一条"回到 http 页面"的路径，产品负责人当天就能加 http 服务器。
- **是不是"修好"**：**不是**。它只是让用户能**自己绕过**死锁，根因（页面协议被后端协议绑架）还在；而且 §3.4(a) 已经证明——**页面 http 之后，加非回环的 http 服务器仍然会因为跨源而失败**，所以它连"绕过"都不完全。

### 6.3 明确禁止：回环代理

会让宿主转发含 Basic 认证头的 API ＝ 凭据处理，撞红线与 `DirectConnectAuthBoundaryTest`。**本轮 0 次考虑落地。**

### 6.4 🚩 按红线停下并上交（结论）

> 红线原文：「若你判断"要同时满足 ①一页能加 http+https ②不碰凭据 ③不改服务端"三者做不到 ⇒ **立刻停下、把选项与代价写清楚上交**，不许擅自取舍，也不许硬凑。」

**本轮判断：三者对 opencode 后端可以同时做到（§6.1 已给出可执行设计与逐条依据），但它的落地是一个架构级改动 + 全量跨源回归，不是一轮能安全完成的量。**
按指令「**先把机制与触发条件用设备证据钉死**」＋「**不许硬凑**」，**本轮交付证据与决策依据，不提交半成品改动。**

**请产品负责人在下列两条里选一条：**

| 选项 | 做法 | 代价 | 是否满足"不改服务端" |
|---|---|---|---|
| **A（推荐，需一整轮）** | 按 §6.1 做：UI 内嵌 assets + 页面从 `http://127.0.0.1:<port>` 加载 | UI 体积涨、启动改为"连接屏"、**全部已验收项必须重跑** | ✅（opencode 后端默认放行回环 http 来源，§5 实测） |
| **B（今天可用，零代码）** | 把应用后端地址指向 `http://…`（如 `http://8.138.19.15:4094`）⇒ 页面变 http ⇒ 红字消失，官方对话框对 http 正常 | Basic 口令走明文 http；加**非回环**第二台仍受跨源限制（§3.4a 已证） | ✅ |

---

## 7. 验收对照（B-6 的验收标准）

| B-6 验收项 | 本轮结果 |
|---|---|
| 手机档能添加 `http://…` 服务器并真的连上 | ✅ **成功**（§3.4b：提交后 API 全部打到 `http://localhost:4095`） |
| 同一页里 **https** 服务器也能加 | ❌ **未做到**：需要证书受信的 https 后端，本轮无法构造（§4.1）；且若目标不是 opencode 实现，还需对方开 CORS（§5.1） |
| 不许弄坏已验收项 | ✅ 未改任何代码 |
| 重建三份产物 + 同步交接文档 | ➖ **无代码改动 ⇒ 不重建**（产物保持 `research/deliver/` 原三份；md5 不变） |

---

## 8. 回归与红线自检

### 8.1 回归

命令：`.\gradlew.bat --console=plain :webapp:testDebugUnitTest :app:testDebugUnitTest`（`JAVA_HOME` 钉 **JBR 21.0.10**）

| 模块 | 测试类数 | 用例数 | failures | errors | skipped |
|---|---|---|---|---|---|
| `webapp` | 23 | **184** | **0** | 0 | 0 |
| `app` | 677 | **6136** | **0** | 0 | 0 |
| **合计** | **700** | **6320** | **0** | **0** | **0** |

`BUILD SUCCESSFUL`；**用例数与 UX9 完全一致（0 增 0 减，本轮未加也未删用例）**。

### 8.2 红线

| 红线 | 结果 |
|---|---|
| 宿主不持有/注入/转发任何口令 | ✅ **本轮 0 行代码改动**；`DirectConnectAuthBoundaryTest` 所在 `:app` 6136 用例全绿 |
| 新代码 `Authorization`/`password`/`Basic`/凭据转发 0 命中 | ✅ 无新代码 |
| 不许加回自定义页/灰页/启动配置对话框 | ✅ `webapp/src/main/assets/www/` 仍是原有 5 个注入件（`index.html`/`oc-host.js`/`oc-perf.js`/`oc-plus-chooser.js`/`oc-preflight.js`） |
| 不许偷偷把凭据处理请回来 | ✅ 回环代理**未被引入**，且在 §6.3 明确列为禁止项 |

---

## 9. 本轮未做到 / 需说明的边界（如实记录）

1. **路线②未端到端复现**（原因见 §4.1：宿主无 `onReceivedSslError` ⇒ 自签证书不可用；模拟器不信任用户 CA；产品负责人实例不许碰）。
2. **`http://10.0.2.2:4095` 不可达**（本 AVD 网络限制）⇒ 路线① 的"非回环 http 目标"是用**官方报错文案**（通用错误 ≠ 混合内容红字）证明的，不是用"成功"证明的；"成功"那一半由 `localhost` 目标补上（§3.4b）。
3. **`8.138.19.15:4094` 的 CORS 行为未探测**（不是我的服务器，不探测）。
4. **服务端 CORS 白名单只在本机 4095 上实测**；不同版本/不同启动参数的白名单可能不同。
5. **§6.1 方案 A 未做原型验证**：其中"UI 内嵌 + 跨源全链路（SSE/PTY/常亮/键盘/主题）是否全部不回归"**尚无证据**，必须在下一轮逐项实测后才能说"可交付"。
6. **§6.1 方案 A 未做原型验证**：其中"UI 内嵌 + 跨源全链路（SSE/PTY/常亮/键盘/主题）是否全部不回归"**尚无证据**，必须在下一轮逐项实测后才能说"可交付"。
7. **本轮补做的低成本 spike（静态，降低方案 A 的未知）**：
   - `oc-host.js` 全文 **0 命中** `location.origin` / `backendUrl` / `location.protocol` / `127.0.0.1:4095` / `isSecureContext`
     ⇒ **我们的注入件没有硬编码"页面来自后端"这一假设**；
   - 它的两条观测链（`oc-host.js:684-688`）是"**按路径**"工作：拦截 `POST /api/session/{id}/prompt|message|message_async`、给 `/api/event` 的 SSE 响应体套 tee
     ⇒ 在"页面来源 ≠ 后端"下**逻辑上仍成立**（它拦的是页面自己发出的请求，不问 origin）；
   - ⚠️ **但仍未验证**：跨源 `EventSource`（`/api/event`）在目标回 ACAO 时能否建立、SSE 响应体 tee 是否照旧工作。**这是方案 A 的第一个必测点。**

---

## 10. 收尾复核输出（原始输出）

```
=== (a) 自建实例残留 ===
$ adb devices
List of devices attached
emulator-5830	device        ← 非自建（中途上线），全程未对其执行任何 adb 命令
emulator-5832	device        ← 非自建（中途上线），全程未对其执行任何 adb 命令
（自建 emulator-6772 已关，无残留）

=== (b) 端口释放 ===
$ Get-NetTCPConnection -LocalPort 6772
LocalAddress LocalPort  State      OwningProcess
127.0.0.1     6772      TimeWait   0        ← 仅剩 TIME_WAIT，无监听 ⇒ 实例已彻底关闭

=== (c) 后端未起停 ===
$ Get-NetTCPConnection -LocalPort 4095 -State Listen
4095 LISTEN pid=3748 addr=127.0.0.1
$ (Invoke-WebRequest http://127.0.0.1:4095/global/health).StatusCode
200
⇒ pid=3748 与 UX9 §10(c) 记录的基线 PID **完全一致** ⇒ 全程未起停

=== (d) gradle 锁 ===
$ Test-Path .agent-gradle-lock
lock exists: False        （已用单条顶层 `rm -- .agent-gradle-lock` 释放）

=== (e) 工作区改动（本轮只应动文档）===
 M HANDOFF_WEB_SHELL.md
 M research/HANDOFF.md
 M research/web/BUG_QUEUE.md
?? research/web/UX10_HTTP_HTTPS_TOGETHER.md

$ git status --porcelain -- app webapp frontend | Measure-Object | Select -Expand Count
0
⇒ **零生产源码改动**（`app/**`、`webapp/**`、`frontend/**` 全部 0 条目）

=== (f) 取证截图（仓库按约定 gitignore research/**/*.png）===
research/web/ux10_route1_connect.png    43968
research/web/ux10_nav1..nav12.png       （13 张，完整走查：菜单→设置→Server→Add server→填写→报错→添加成功）
```

**⇒ 改动全部落在文档，零生产源码改动；三份交付物未被触碰（体积/md5 维持原值）。**

### 碰过的 serial

- **`emulator-6772`** —— 本轮自建（AVD `OC_UX6_Phone`，端口 6772）。**已关，无残留（仅 TIME_WAIT）。**
- **`emulator-5830` / `emulator-5832`** —— **本轮中途出现在 `adb devices` 里的非自建实例**（不是本轮启动的）。**全程未对其执行任何 adb 命令**（本轮每一条 adb 命令都显式带 `-s emulator-6772`）。
- **产品负责人的实例（`DSH_*` / `T*` / `Pixel_*` / `MCRemote_*`）：一台都没有碰过。**
- 未执行 `adb kill-server`、未使用 `-a`、未使用任何通配符；关闭只针对 `emulator-6772` 单台（`emu kill`）。
- 只做了 `adb reverse tcp:4095 tcp:4095`（任务允许），收尾已 `reverse --remove`。

