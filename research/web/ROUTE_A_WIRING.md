# R1 · 路线 A 接线：WebView 直连「服务端自托管 UI」+ 应用内回环代理免 CORS

> **结论：跑通了。** 官方 opencode Web UI 在 `emulator-5636` 上真机渲染出**带真实会话列表**的界面。
> 设备 `emulator-5636`（唯一触碰的设备）。后端由 Lead 起于本机 `127.0.0.1:4095`，本轮**未起停后端**。
> 上游依据：`research/web/SERVER_HOSTS_UI.md`（G1）、`research/web/WEBHOST_SKELETON.md`（W1）。

| 交付项 | 值 |
|---|---|
| APK | `webapp/build/outputs/apk/debug/webapp-debug.apk`（8,636,834 B） |
| **APK md5** | **`907c5eba15ed6a765036c7b5240e9dc9`** |
| 真机截图（会话列表） | `research/web/ROUTE_A_device-5636_02-sessions.png` |
| 真机截图（首屏/Loading） | `research/web/ROUTE_A_device-5636_01-home.png` |
| 关键 logcat | `research/web/_logcat-final.txt`（683 行） |
| 认证 401 次数 | **0** |
| CORS 相关报错 | **0** |

---

## 1. 起点：W1 留下的死结

`webapp/.../host/WebHostBridge.kt:43-49`（改动前）：

```kotlin
// 本轮**没有**实现那个回环代理（属 :app 的 LocalGateway 能力），所以这里
// 返回空串 ⇒ 上游 `androidProxyBaseUrl()` 的 `if (!origin || !prefix) return trimmed`
// 会原样返回真实 URL，行为退回"页面直连后端"（= 需要后端允许 CORS）。
private val PROXY_ORIGIN = ""
private val PROXY_PREFIX = ""
```

W1 首页是 `https://appassets.androidplatform.net/assets/www/index.html`（占位页）。换成官方 SPA 后，
它从 `http://127.0.0.1:4095` 拉 API ⇒ 跨源 ⇒ CORS ⇒ 白屏。

**关键认识**：本轮不是"给直连补个代理"，而是必须把**文档本身**也放到代理 origin 上，
否则同源无从谈起。

---

## 2. legacy `LocalGateway` 在哪、怎么做的

文件：`app/src/main/java/com/xiufeigo/opencodeshell/LocalGateway.kt`（636 行，只读，未改动）

| 行号 | 做法 |
|---|---|
| `45-49` | `class LocalGateway`：`ServerSocket` + OkHttp + 线程池的组合构造 |
| `82` | `publicOrigin() = "http://127.0.0.1:$port"` —— 页面可见的 origin |
| `93-120` | **`originFor(backendUrl)`**：为每个后端**另开一个专用回环端口**。因为官方 SDK 用 `new URL('/api/...', baseUrl)`，路径前缀会被丢弃，所以 `baseUrl` 必须是**纯 origin** |
| `122-144` | `start()`：`ServerSocket(0, 50, 127.0.0.1)` + `Executors.newCachedThreadPool`（daemon 线程 `oc-gateway`） |
| `152-158` | `pause()`/`resume()`：Activity 不可见时直接 accept 后丢弃，省电 |
| `197-252` | `handleClient`：解析请求 → OPTIONS 就地 204 → WebSocket 升级判定 → 否则 `proxyHttp` |
| `208-216` | OPTIONS 预检就地回 CORS 头（**legacy 路线必须回，因为页面在另一个 origin**） |
| `254-322` | `proxyHttp`：OkHttp 真转发；成功后逐头回写 |
| `324-376` | `tunnelWebSocket`：WebSocket 逐字握手 + 双向 `copyStreaming`（PTY 终端依赖它） |
| `386-410` | `readHttpRequest`：手写 HTTP/1.1 解析（请求行/头/body） |
| `412-419` | `readBody`：GET/HEAD 空体；`Transfer-Encoding: chunked` 走 `readChunked`；否则按 `Content-Length` 全量读 |
| `432` | `SSE_IDLE_READ_TIMEOUT_MS = 35_000` —— opencode `/event` 每 10s 心跳，35s 静默即认定半开 |
| `441-448` | `isEventStreamRequestPath`：`/event` 或以 `/event` 结尾（SDK 不发 `Accept: text/event-stream` 请求头，只能靠路径认） |
| `473-495` | `corsHeaders`：`allowedWebOrigin` 白名单只放 `appassets.androidplatform.net` / `127.0.0.1` / `localhost` |
| `497-505` | **`shouldDropRequestHeader`：剥 `origin` / `referer` / `host` / `connection` / `accept-encoding` / `transfer-encoding`** |
| `507-512` | `shouldDropResponseHeader` |
| `528-552` | `copyStreaming`：`SocketTimeoutException` ⇒ 记日志并 `break`，让页面重连 |
| `579-592` | `readChunked` |

**legacy 不注入 Basic 认证**（那一层页面在 `https://appassets` 上、只做换源，认证由页面自己出）。
本轮页面就住在代理 origin 上，必须由原生注入 —— 这是与 legacy 的**实质**差异之一。

---

## 3. 代理方案选型：两条路都评估，选了哪条、为什么

### 3.1 方案 B（被否）：`shouldInterceptRequest` 拦截 + 自己转发

`:app` 的 `MainActivity` 里有 `assetLoader.shouldInterceptRequest(request.url)`（`ConnectCorsTest.kt:41` 断言过）。
看上去可以"在 WebView 侧拦下所有请求自己转发"，但对本轮**结构性地做不到**：

1. **拿不到"文档本身"的同源。** `shouldInterceptRequest` 只能拦**子资源**；顶层文档导航不被它接管。
   要让文档也走代理，就必须让 WebView 去访问 `http://127.0.0.1:<port>/` —— 那一刻已经需要一个真的 HTTP 服务了，
   方案 B 退化成了方案 A。
2. **根本不接管 WebSocket。** PTY 终端走 `ws://`，`shouldInterceptRequest` 看不见 WebSocket 帧，无法隧道。
3. **SSE 不可靠。** intercept 必须返回 `WebResourceResponse`（一个 `InputStream`），Chromium 侧对长流的处理
   不可靠；而 opencode `/event` 是**永久长连接**（10s 心跳），不是短请求。
4. legacy 已在 `README-webview-legacy.md:38-40` 记录了它选"应用内 LocalGateway"的原因，本轮结论与之一致。

### 3.2 方案 A（**采用**）：应用内回环 HTTP 服务（`ServerSocket`）

**做法**：`webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/LoopbackProxy.kt`（新增，431 行），
逐段搬 `LocalGateway` 的成熟做法，并针对路线 A 改三处：

| `LoopbackProxy.kt` 行号 | 内容 |
|---|---|
| `60` | `class LoopbackProxy(upstreamBase, passwordProvider, httpClient, sseIdleReadTimeoutMs)` |
| `77` | `const val BASIC_USER = "opencode"` —— **只有密码是秘密** |
| `102` | `publicOrigin() = "http://127.0.0.1:$port"` |
| `110-132` | `start()`：`ServerSocket(0, 50, 127.0.0.1)` + daemon 线程池 `oc-web-proxy` |
| `147-179` | `handleClient`：解析 → WS 升级判定 → 否则转发 |
| `181-266` | **`onForward`：注入 Basic 头 + 逐字转发** |
| `268-311` | `tunnelWebSocket`：同样注入 Basic，逐字握手 + 双向 copy |
| `363-365` | `isEventStreamRequest`（沿用 legacy 的 `/event` 路径判定） |
| `376-384` | `shouldDropRequestHeader` —— **剥 `origin`/`referer`/`authorization` 等** |
| `390-419` | `copyStreaming` + 退出原因/字节数日志（排查用） |

**与 legacy 的三处实质差异**（代码注释同款）：

1. **注入 Basic 头**（`:196`）—— legacy 那层页面在别的 origin、认证由页面出；本轮文档就在代理 origin 上。
2. **不回灌 CORS 头**，并丢弃上游自己的 `Access-Control-Allow-Origin`（`:243-245`）—— 同源了，不需要；
   留着反而会让人误以为代理是 CORS 边界。
3. **目标地址逐字拼接**（`:170-172`），不再有 token/prefix 改写 —— 因为页面与 API 同源。

**同源结论**：页面 origin == API origin == `http://127.0.0.1:<随机端口>`，
**完全不依赖后端 CORS**（实测 0 条 CORS 报错）。

---

## 4. 凭据注入的实现

### 4.1 密码从哪来 —— EncryptedSharedPreferences

`webapp/.../host/SecretStore.kt`（W1 已有，本轮只加常量 `:23-36`）：

- `KEY_SERVER_PASSWORD = "serverPassword"`，文件 `oc_webhost_secrets`，
  key 用 AES256-SIV、value 用 AES256-GCM，主密钥在 Android Keystore。
- `getSecret` 空串视为"没有"（`:51-57`），所以"存了空密码"等价于"没存"，不会把空口令当有效凭据。
- **不硬编码、不明文落盘**；`passwordProvider` **每次转发现读**，代理内存里不驻留明文。

### 4.2 注入点（行号）

`WebHostActivity.kt:160`：

```kotlin
passwordProvider = { SecretStore.getSecret(this, SecretStore.KEY_SERVER_PASSWORD) },
```

`LoopbackProxy.kt:190-198`（HTTP）与 `:294-299`（WebSocket）：构造
`Authorization: Basic base64("opencode:" + password)`。**页面永远拿不到密码** —— 浏览器只发同源请求，
这个头是代理在**建上游请求时**补的。

`:232-235` 只在 401/403 时打一行 `user=… passLen=… authHeaderSent=…`，**只打长度、绝不回显口令**。

### 4.3 首次启动为什么用**原生**对话框

密码必须在进 JS 之前落盘。若让页面自己弹窗要密码，密码会经
`window.__ocAndroid.setSecret` 进入 JS 可见范围。原生对话框：`WebHostActivity.kt:183-243`。
`proxyOrigin()`（`WebHostBridge.kt:43-56`）改成延迟查询，宿主因此可以先建桥、后起代理。

---

## 5. 其他改动

- `webapp/build.gradle.kts:80-81` —— 加 `com.squareup.okhttp3:okhttp:4.12.0`（与 `:app` 同版本，本地 Gradle 缓存已有，未引入新下载）。
- `webapp/src/main/AndroidManifest.xml:8-13` —— **补 `INTERNET` 权限**（原来没有；`ServerSocket`/OkHttp 会被内核直接 EACCES，回环也不例外）。
- `WebHostActivity.kt:141-153` 启动链、`155-181` 起代理并把首页指到代理 origin、`465` `onDestroy` 关代理释放端口。
- 保留 W1 全部桥：通知 / 返回 / 文件 / 分享 / 凭据 / IME+insets / 常亮 / 主题。
- **`app/**`、`_upstream/**`、`.github/**` 一律未碰**（`git status` 只有 `M settings.gradle.kts` 与 `?? webapp/`）。

---

## 6. 真机验证（`emulator-5636`）

1. `:webapp:assembleDebug` → 安装 → 启动 `WebHostActivity`；
2. 首次启动弹原生配置框，填后端 `http://127.0.0.1:4095` + 密码，存进 Keystore；
3. 代理起在 `http://127.0.0.1:41915/`，首页从**代理 origin** 加载官方 UI。

**logcat 关键行**（`_logcat-final.txt` 原文）：

```
I OcWebHost/Proxy: listening on http://127.0.0.1:41915 -> http://127.0.0.1:4095
I OcWebHost: home=http://127.0.0.1:41915/ backend=http://127.0.0.1:4095 passwordPresent=true
I OcWebHost/Proxy: GET http://127.0.0.1:4095/api/info
I OcWebHost/Proxy: GET http://127.0.0.1:4095/api/project
I OcWebHost/Proxy: GET http://127.0.0.1:4095/api/session?limit=5000&order=desc&parentID=null
I OcWebHost/Proxy: GET http://127.0.0.1:4095/api/session/active
I OcWebHost/Proxy: GET http://127.0.0.1:4095/api/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y
```

截图 `ROUTE_A_device-5636_02-sessions.png` 里是**真实会话数据**（Today / Yesterday / Older 三组、
`Counting from 1 to 4000` / `Friendly greeting` / `DRAFT_OK` 等标题与项目名），不是占位页、不是白屏。

**踩到的坑（如实记录）**：

1. **Kotlin 嵌套块注释**：`LoopbackProxy.kt` 与 `WebHostActivity.kt` 的 KDoc 里写了 `` `/api/*` ``，
   其中 `/*` 在 Kotlin 里是**嵌套注释的开始**，一路吞到文件尾 ⇒ `Unclosed comment`，
   并连带把 `WebHostActivity` 打成语法错误（`TAG` 未解析、`toTypedArray` 二义…共 15 个报错）。
   改写成 `` `/api/…` `` 后一次过。**教训：Kotlin 注释里不能出现 `/*`。**
2. **配置对话框布局塌陷**：自定义 `LinearLayout` 没给 `MATCH_PARENT` 宽度，AlertDialog 按内容收缩成
   极窄的框，输入框与按钮被挤出可视区（截图 `_state-now.png` 可见只剩标题）。
   修法：容器与两个 `EditText` 都设 `MATCH_PARENT`，并用 `SOFT_INPUT_ADJUST_RESIZE`（**不要**再加
   `STATE_ALWAYS_VISIBLE`，那会把横屏下所剩无几的对话框高度压扁）。
3. **默认焦点落在 URL 框**：AlertDialog 默认聚焦第一个可编辑控件，于是 `input text` 把 `opencode`
   插进了 URL（`backend=http://127.0.0.opencode1:4095`）。修法：**把密码框放第一个**（`:220-221`），
   打开即可直接输密码（`password="true" focused="true"` 实测成立）。
4. **字段里多出一个字符**：`passLen=9`（应为 8）⇒ 上游 401。清空重输后通过。
   期间修了一处**会误导人的日志**：原来 `password=present` 是硬编码字面量、恒为真，已改成真实判断
   `passwordPresent=…`（`WebHostActivity.kt:171-173`）。
5. logcat 全部用 `logcat -d -t N | Select-String` 抓，**未用 `--pid` 过滤**（已知 `--pid` 会假阴性）。

---

## 7. 未闭环 / 下一步

- **横屏下窗口未占满屏**：截图里 WebView 内容是居中窄列（约 300px 宽）而非全屏。
  官方 UI 已正常渲染，但 Activity 的窗口/insets 呈现需要单独一轮处理（疑似横屏下的
  aspect-ratio 兼容模式或 `configChanges` 与窗口尺寸策略）。
- **SSE `/api/event` 长连未做长时间 soak**：本轮只确认它能建立、且 401 消失；
  35s 空转看门狗（`LoopbackProxy.kt`）沿用 legacy，未在真机上跑够 10 分钟验证重连。
- **PTY 终端（WebSocket 隧道）未真机验证**：`tunnelWebSocket` 已按 legacy 搬过来，但本轮没开终端会话。
- **大包附件**未压测（legacy `readBody` 按 `Content-Length` 全量进内存，超大图仍需分块）。
- **换后端**需在对话框改 URL；`secret` 键名固定，多后端并存未设计。
- `Home URL` 恢复态：进程重启后回环端口会变，代码已按 origin 前缀判定并重算（`:171-174`），
  但未专门测过"配置变更 → 恢复"路径。
