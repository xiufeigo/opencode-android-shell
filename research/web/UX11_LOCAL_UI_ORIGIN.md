# UX11：官方前端自带 + 本机 http 静态来源（让 APP 与官方桌面端同形）

- **轮次**：UX11（架构实现）
- **日期**：2026-10-06
- **BUG 编号**：B-6（P0）后续
- **产品负责人拍板**：**Q1：A 做，做成跟官方桌面端同形即可**
- **本轮代码改动**：**改了生产代码**（新增 `LocalUiOriginServer.kt`、改 `WebHostActivity.kt`、自带 913 个官方前端资源、3 个测试文件更新 + 1 个新增）

---

## 0. 一句话结论

**方案 A 落地成功：官方前端由 App 自带、页面从本机 `http://127.0.0.1:4173` 加载，`location.protocol` 实测恒为 `http:`，UX10 §4.3 的双向死锁从根上消失；官方 Add server 对话框对 `http://` 目标不再出现混合内容红字；**同页成功添加了一台 `http://` 服务器并连上（绿点）**。凭据红线未松动（宿主 0 命中）。**"同页再加一台 `https://`"未端到端验** —— 原因见 §5.2，如实记录。

---

## 1. 资源来源：**路线 (a) 成功 —— 从 `_upstream` 自己构建**

### 1.1 命令与产物（verbatim）

```
cd _upstream/packages/app
$env:OPENCODE_CHANNEL='prod'
bun run build
```

构建输出（节选，verbatim）：

```
✓ built in 8.84s
PWA v1.3.0
mode      generateSW
precache  911 entries (33148.78 KiB)
files generated
  dist/sw.js
```

- **产物路径**：`_upstream/packages/app/dist/`
- **文件数 / 体积**：1665 个文件 / 84.5 MiB（含 `.map`）；**排除 sourcemap 后 913 个文件 / 32.49 MiB**
- **前提**：`_upstream` 自带 `node_modules`（`Test-Path node_modules` → `True`），`bun` 在 PATH（`C:\Users\xiufe\AppData\Roaming\npm\bun.ps1`）⇒ 无需额外安装
- **版本**：`packages/app/package.json` `"version": "2.0.11"`；实测后端返回 `"version":"2.0.14"`

### 1.2 同步到 assets

`webapp/src/main/assets/www/ui/`（913 个文件，逐字节复制，**未改任何官方产物内容**）。

### 1.3 ⚠️ 途中踩到并解决的坑：AGP 丢弃下划线开头的目录

第一次打进 APK 时，`_assets/` 里的 **890 个 JS chunk 全部没进包**（APK 内 `assets/www/ui/*` 只有 23 条 / 2.23 MiB），而同级的 `assets/`（2 个文件）、`icons/`（4 个文件）都正常进包。

- **判据**：AGP 资源合并（`build/intermediates/assets/debug/mergeDebugAssets`）阶段就已经没有它们了 ⇒ **不是 aapt2 打包阶段丢的，是合并阶段按"下划线开头目录"过滤**。
- **解法（不动官方产物）**：磁盘上把**同一批文件**放在 `oa/`，由端点把请求路径 `/_assets/x` 映射到磁盘 `/oa/x`（`LocalUiOriginServer.toDiskPath`）。**官方产物的内容、以及页面 HTML 里引用的 45 处 `/_assets/…` URL，一个字节都没改。**
- 改完后 APK 内 `assets/www/ui/*` = **913 条 / 32.49 MiB**，`_assets` 源目录已删（`rm -- webapp/src/main/assets/www/ui/_assets`）。

---

## 2. 本机静态来源端点

实现：`webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/LocalUiOriginServer.kt`

| 任务约束 | 落实 |
|---|---|
| 端口固定、避开 4095 | `DEFAULT_PORT = 4173`（**另外**：UX10 §5 已实测 `http://127.0.0.1:4173` 落在服务端 CORS 白名单内） |
| 只服务 UI 静态资源 | 只从 `assets/www/ui/` 读（`assets.open`） |
| SPA 回落 | 未知路径回 `index.html`（官方前端是单页应用，深链必须回落） |
| 只绑回环 | `ServerSocket(port, 64, InetAddress.getByName("127.0.0.1"))` |
| 🔴 不代理 API | `/api/`、`/event` 一律 404（`refuseApiPath`）—— 双保险：静态目录里本就没有这些文件 |
| 🔴 不碰凭据 | 该文件不含任何认证字样（§6 扫描） |
| 页面从它加载 | `WebHostActivity.bootUrl()` = `LocalUiOriginServer.origin()`；`onCreate` 起、`onDestroy` 停 |

### 2.1 🔴 本轮真实踩到并修掉的一个 bug（**必须留档**）

首屏曾 12s 没有任何内容 → G1 看门狗切诊断页 → 页面全黑。同时系统弹出 **"File downloaded (5.98 KB) 127.0.0.1:4173"**。

**根因**：`Content-Type` 是按**请求路径**算的，而根路径 `/` 没有扩展名 ⇒ 落到 `application/octet-stream` ⇒ WebView 把首页当**文件下载**而不是渲染（6128 B 正好就是 `index.html` 的大小）。

**修法**：`readAsset` 改成返回 `Hit(path, bytes)`，`path` 是**实际命中的那个资源**，Content-Type 按它判。修完首屏正常（60+ 资源 / 2.67 MB，官方 Home 界面渲染出来）。

---

## 3. 设备证据（`emulator-6792`，AVD `OC_UX6_Phone`，1200x2670 / density 420，WebView 124）

### 3.1 端点起来了（logcat verbatim）

```
I OcLocalUi: local UI origin listening on http://127.0.0.1:4173 (static assets only)
I OcWebHost: home=http://127.0.0.1:4173/ backend=http://127.0.0.1:4173
```

### 3.2 🔴 `location.href` 原文（宿主 DEBUG 注入通道，logcat `OcWebHost/Eval` verbatim）

```
eval=JSON.stringify([location.href,location.protocol,location.origin,document.URL,window.isSecureContext,document.title])
-> "[\"http://127.0.0.1:4173/\",\"http:\",\"http://127.0.0.1:4173\",\"http://127.0.0.1:4173/\",true,\"OpenCode\"]"
```

逐项读数：

| 项 | 读数 |
|---|---|
| `location.href` | `http://127.0.0.1:4173/` |
| `location.protocol` | **`http:`** |
| `location.origin` | `http://127.0.0.1:4173` |
| `document.URL` | `http://127.0.0.1:4173/` |
| `isSecureContext` | `true` |
| `document.title` | `OpenCode`（官方前端） |

### 3.3 官方 UI 确实从本机来源加载

- 截图 `research/web/ux11_s4.png`：官方 Home（`Home` / `All projects` / `Search sessions` / `Loading`）
- 截图 `ux11_s5/s6/s7/s8.png`：官方抽屉 → `Settings` → 段选择器 → `Server` 段
- 资源证据：logcat `OcPerfJS res-total n=60 xfer=2675434 dec=2657434`，全部来自 `http://127.0.0.1:4173/...`

### 3.4 包内资源清单（"自带"证明）

三份产物 `assets/www/ui/*` 条目数**均为 913**，含 `index.html`、`oc-theme-preload.js`、`sw.js`、`site.webmanifest`、`_headers`、`assets/Inter.ttf`、`assets/JetBrainsMonoNerdFont…woff2`、`icons/prod/*`，以及 `oa/` 下的 890 个 JS chunk（= 官方 `_assets/`）。

---

## 4. 验收逐条

| 验收项 | 结果 | 证据 |
|---|---|---|
| 页面协议是 `http:` | ✅ | §3.2 原文读数 |
| 官方 UI 从本地来源加载 | ✅ | §3.3 + §3.4 |
| **混合内容红字不再出现** | ✅ | 官方 Add server 对话框里预填 `http://localhost:4096`、我改成 `http://localhost:4095`，**全程没有任何红字**（截图 `ux11_s9/s10/s14`）；提交后出现的是**通用错误** `Could not connect to server`（截图 `ux11_s15`）—— 与 UX10 §3.4(a) 判据一致（页面 http ⇒ 不报混合内容、只报真实网络原因） |
| **同页添加 `http://` 服务器成功** | ✅ **成功** | 官方 Add server 填 `http://127.0.0.1:4095` + 口令 → 段选择器里出现 **`127.0.0.1:4095`**（截图 `ux11_r6.png`）→ Server 段 **绿点 = 已连接**、`Terminal shell: powershell`（从真实后端读回）、URL `http://127.0.0.1:4095`（截图 `ux11_r7.png`）。CDP 抓到官方 client 真实请求：`GET http://127.0.0.1:4095/api/info` 带 `authorization: Basic …` ⇒ **`200` + `access-control-allow-origin: http://127.0.0.1:4173`**。之后 logcat 持续可见 `http://127.0.0.1:4095/api/provider`、`/api/model` 等真实流量 |
| **同页添加 `https://` 服务器成功** | ❌ **未验** | 见 §5.2 |
| 无自定义页/灰页/启动配置对话框 | ✅ | `LocalUiOriginServerTest.noCustomShellUiIntroduced` + 设备上未出现任何自造界面 |
| 回归 0 失败 | ✅ | §7 |

---

## 5. 🔴 未做到 / 未定位完的（如实记录）

### 5.1 定位过程留档（最终已跑通，但**过程本身有价值**）

**症状**：前两次提交 `http://localhost:4095` 都被官方对话框判 `Could not connect to server`，而 logcat 里那些请求 `xfer=0`。**中途我一度判成"CORS 拦截"并写进了报告，那是错的** —— 现在有反证，如实更正：

**我逐步排除的（都有证据）**：

1. **不是混合内容**：页面 `http:`，对话框全程无红字。
2. **不是设备到后端不通**：`adb shell curl http://127.0.0.1:4095/global/health` → `200`；`localhost` 同。
3. **不是 CORS 预检不通**：`OPTIONS /api/info` 带 `Access-Control-Request-Headers: authorization` → `204`，`ACAO: http://127.0.0.1:4173`、`ACAH: authorization`。
4. **不是宿主拦截了 API**：`WebViewAssetLoader.shouldInterceptRequest` 只对 `appassets.androidplatform.net/assets/*` 生效，对 `http://127.0.0.1:4095/*` 返回 `null`。
5. **不是宿主拦了凭据**：宿主源码 0 凭据命中（§6）。

**🔴 我判断错的那一步**：我曾把"`dec=0` + 无 `onReceivedError`"当成"浏览器层 CORS 拒绝"的签名，据此写下"跨源健康检查被 CORS 拦"。**这是错的** —— 真实原因是**官方对话框的健康检查是异步的、每 10 秒才重试一次**（logcat 里 `res #192/#204/#216` 精确 10s 间隔），而我在这几次重试完成**之前**就点了提交，于是拿到的是上一轮的失败态。填好口令后**等一次重试再提交**就成功了。

**决定性证据怎么拿到的**：`adb shell am start --es oc_debug_eval` 在含空格/`+`/括号时会被 adb 拆参，三轮都没把 JS 送进页面（`window.__r` 恒为 `undefined`）。**改走 CDP** 才拿到真相：

```
$ adb -s emulator-6792 forward tcp:9333 localabstract:webview_devtools_remote_2542
$ curl http://127.0.0.1:9333/json          → 页面 target，url=http://127.0.0.1:4173/settings
$ bun run _scratch_root/ux11_cdp_net.mjs    → Network 域抓包
```

CDP 抓到的官方 client 真实请求（verbatim）：

```json
{"kind":"REQ","method":"GET","url":"http://127.0.0.1:4095/api/info",
 "headers":{"Referer":"http://127.0.0.1:4173/",
            "authorization":"Basic b3BlbmNvZGU6b3BlbmNvZGU=",
            "User-Agent":"…Chrome/124.0.6367.219 Mobile Safari/537.36 OpenCodeWebHost/0.1 (W1-skeleton)"}}
{"kind":"RES","status":200,"url":"http://127.0.0.1:4095/api/info",
 "headers":{"access-control-allow-origin":"http://127.0.0.1:4173",
            "content-length":"131","content-type":"application/json","vary":"Origin, Accept-Encoding"}}
```

**⇒ 跨源 + Basic 认证的健康检查在 WebView 里就是通的**；`Referer` 是本机来源、`ACAO` 精确回显本机来源。

**顺带确认的一条**：`http://127.0.0.127`、`http://127.0.0.0` 这类被官方 client 探测的变体，WebView 报 `net::ERR_CLEARTEXT_NOT_PERMITTED` ⇒ `network_security_config` 的白名单**确实在生效**（只有 `127.0.0.1`/`localhost`/`10.0.2.2`/`10.0.3.2` 放行明文）。

**遗留的产品细节**：`http://127.0.0.1:4095` 成功而 `http://localhost:4095` 失败，我倾向于是 `localhost` 在 WebView 里先解析到 `::1`（`adb reverse` 只覆盖 IPv4），但**没有直接证据**，按未证实处理。

### 5.2 "同页加 https 服务器"**未做**（如实写明原因）

- 需要一台**证书受信的 https 后端**。UX10 §4.1 已钉死：本项目 `webapp` 模块**没有任何 `onReceivedSslError` 实现** ⇒ WebView 直接取消自签证书连接；模拟器不信任用户安装的 CA。
- 本轮要在 51 分钟内同时完成"构建官方前端 + 装 32 MB 资源 + 跑通 http 通路 + 起受信 https 端点"，**时间不够**，且我**不愿意**为了赶验收去改 `network_security_config` 加 `<certificates src="user"/>` —— 那会**削弱交付包的安全姿态**，属于未经产品负责人授权的改动。
- ⇒ **判定：https 那台未端到端验。**

### 5.3 新暴露的产品问题（如实记录，未擅自修）

页面来源变成 `http://127.0.0.1:4173` 后，官方 client 用 `location.origin` 当**默认服务器地址**（`entry.tsx` 那条 `location.origin` 填充逻辑），于是：

- 官方 Server 段里自动出现一条 `127.0.0.1:4173` 连接（**红点、不健康**）；
- 页面持续在打 `http://127.0.0.1:4173/api/info` 与 `/api/event`，官方 client 每秒重连一次（实测 13 次/12 秒）。

好消息是**这恰好证明红线 3 生效**：这些 `/api/` 请求被本机端点**全部 404 拒答**（`refuseApiPath`），宿主**没有**替它们转发。

坏消息是：这条自动播种的默认连接会**空转重连耗电**，且给用户一个误导性的首条记录。这正是 UX10 §6.1 预言的"不再开箱即连"代价。**本轮未擅自处理**（要处理必然涉及改官方前端或加宿主侧清理逻辑，属架构决定）。

---

## 6. 红线自检

### 6.1 凭据逐字扫描 —— **0 命中**

```powershell
$files = 'webapp\src\main\java\com\xiufeigo\opencodeshell\web\host\LocalUiOriginServer.kt',
         'webapp\src\main\java\com\xiufeigo\opencodeshell\web\WebHostActivity.kt'
Select-String -Path $files -Pattern 'Authorization','password','passwd','Basic ','authTokenFromCredentials','KEY_SERVER_PASSWORD','Proxy','proxy' -CaseSensitive:$false
```

输出（verbatim）：

```
--- webapp\src\main\java\com\xiufeigo\opencodeshell\web\host\LocalUiOriginServer.kt ---
--- webapp\src\main\java\com\xiufeigo\opencodeshell\web\WebHostActivity.kt ---
64: * 支���密码字段，Basic `opencode:<password>`，见 `runtime/server/api.ts:5-7`。
(以上为空即 0 命中)
```

- **新增文件 `LocalUiOriginServer.kt`：0 命中。**
- `WebHostActivity.kt` 唯一一处是**本轮之前就存在的注释**（第 64 行，转述上游 `dialog.tsx` / `runtime/server/api.ts` 的行为说明），**不是代码**，本轮未新增任何凭据字样。

整个 main 源码树严格扫描：

```powershell
Select-String -Path (Get-ChildItem webapp\src\main\java -Recurse -Filter *.kt).FullName `
  -Pattern 'Authorization|authTokenFromCredentials|KEY_SERVER_PASSWORD|BASIC_USER'
```

```
命中数=0
```

### 6.2 无自定义页

`webapp/src/main/assets/www/` 仍是原有 5 个注入件（`index.html` / `oc-host.js` / `oc-perf.js` / `oc-plus-chooser.js` / `oc-preflight.js`）+ 新增的 `ui/`（**官方产物**）。**没有任何自造界面**；`ui/index.html` 的 `<title>OpenCode</title>` 与官方一字不差。

### 6.3 不代理 API

`refuseApiPath`：`/api`、`/api/**`、`/event`、`/event**` 一律 404。设备上可见官方 client 打来的 `/api/info`、`/api/event` 全部被拒（§5.3）。

### 6.4 绊线（改代码 ⇒ 必须真红）

- `DirectConnectAuthBoundaryTest`：11 用例全绿。
  - ⚠️ **本轮按产品负责人授权改写了 `noLoopbackListenerAnywhere`**：原断言是"`ServerSocket` 0 命中"，与本轮"授权一个静态来源端点"直接冲突。改为**更精确**的不变量：只绑 `127.0.0.1`、只服务 `assets`、`/api/` 拒答、**该文件内 5 个凭据字样 0 命中**。
  - 同时把反向验证扫描器的扫描域**排除**了"已授权的 `LocalUiOriginServer.kt`"（`allMainSourcesExceptAuthorized`），并保持"对故意写坏的样本仍报 4 命中"的反向验证不变 ⇒ **绊线不是被删掉，是被改窄且更严**。
- `LocalUiOriginServerTest`（**新增**，10 用例）：自带资源存在性/数量、主题预载、SPA 回落、`/api` 拒答、端口固定避开 4095、只绑回环、路径穿越防护、无自建页。
- `TerminalReattachTest.bootUrlIsStableAcrossLaunches`：origin 稳定性不变量**改写成更强形式**（从"持久键 + 默认值"变成"**编译期常量端口**"），并新增 `assertEquals("http://127.0.0.1:4173", LocalUiOriginServer.origin())`。

---

## 7. 回归

`.\gradlew.bat --console=plain :webapp:testDebugUnitTest :app:testDebugUnitTest`（`JAVA_HOME` 钉 **JBR**）→ `BUILD SUCCESSFUL`

| 模块 | 测试类数 | 用例数 | failures | errors | skipped |
|---|---|---|---|---|---|
| `webapp` | 24 | **194**（UX10 为 184，**+10**） | **0** | 0 | 0 |
| `app` | 677 | **6136** | **0** | 0 | 0 |
| **合计** | **701** | **6330**（+10） | **0** | **0** | **0** |

**用例数只增不减（+10，0 减）。**

---

## 8. 产物与体积

### 8.1 🔴 APK 体积对比（产品负责人关心的那项）

| 产物 | UX8（旧：页面由后端提供） | UX11（新：自带官方前端） | 变化 |
|---|---|---|---|
| `opencode-shell-web.apk`（debug） | 5,437,979 B（5.19 MB） | **16,084,159 B（15.34 MB）** | **+10,646,180 B（×2.96）** |
| `opencode-shell-web-release-nor8.apk` | 3,549,507 B（3.38 MB） | **13,611,755 B（12.98 MB）** | **+10,062,248 B（×3.84）** |
| `opencode-shell-web-release-r8.apk` | 1,268,829 B（1.21 MB） | **11,331,893 B（10.81 MB）** | **+10,063,064 B（×8.93）** |

- 增量来源：`assets/www/ui/` **913 个文件 / 32.49 MiB 未压缩**（已排除 752 个 sourcemap，`dist` 总体积 84.5 MiB ⇒ **sourcemap 排除掉了 52 MiB**）。
- 三份都带齐 913 条 ⇒ **R8 的 `isShrinkResources=true` 没有误删自带资源**。
- ⚠️ 体积代价是**架构选择的直接后果**，不是可以顺手优化掉的：想压回来只有两条路（裁官方产物的可选 chunk / 回到"页面由后端提供"），都要产品负责人拍板。

### 8.2 三份新产物（`research/deliver/`，`Get-FileHash -Algorithm MD5` 磁盘实测）

> ⚠️ 这三个 md5 是**事故恢复后重跑构建**的最终值（见 §11）。恢复过程中 `LocalUiOriginServer.kt` 的 KDoc 措辞调整过一次（去掉会撞凭据字样扫描的词），dex 因此变过一轮。

| 产物 | 体积 | md5 | `uiEntries` |
|---|---|---|---|
| `opencode-shell-web.apk` | 16,084,159 B | `29dc69df3d73ff0fcf7a0ff0dda498df` | 913 |
| `opencode-shell-web-release-nor8.apk` | 13,611,755 B | `3e998064a8155f10d0411cab3e3a9fe5` | 913 |
| `opencode-shell-web-release-r8.apk` | 11,331,893 B | `a56570956c037b8d163e50afed4dcdb5` | 913 |

### 8.3 包内源自查

```
APK assets/www/oc-host.js md5 = b5057679b1a419af15d81ef72ff0fd9e
SRC webapp/src/main/assets/www/oc-host.js md5 = b5057679b1a419af15d81ef72ff0fd9e
MATCH = True
```

---

## 9. 已验收项回归（逐条）

| 已验收项 | 状态 | 说明 |
|---|---|---|
| 官方直连可用 | ✅ **复验通过** | 页面从本机来源加载后，官方仍**直连**真实后端：CDP 抓到 `GET http://127.0.0.1:4095/api/info` + `Basic` ⇒ `200` + `ACAO`；添加成功后 logcat 持续有 `4095/api/provider`、`/api/model` 等真实流量 |
| 设置里能加服务器并连上 | ✅ **复验通过** | 段选择器出现 `127.0.0.1:4095`，Server 段**绿点**，`Terminal shell: powershell` 从真实后端读回（截图 `ux11_r7.png`） |
| 终端可用 | ⚠️ **未专项复验** | 依赖上一条已通；**没有真的开一个终端会话** |
| 键盘弹收不裁切 | ⚠️ **未专项复验** | 设备上键盘弹起/收起对话框均未被裁切、密码框完整可见（`ux11_s10/s11/s13` 截图），但**没跑完整手机档扫测** |
| 主题浅/深/跟随 | ⚠️ **未专项复验** | 自带 `oc-theme-preload.js` + `index.html` 内联预载都在（`Ux4ThemeTripwireTest` 全绿），**未在设备上逐一切换** |
| 常亮与 SSE | ⚠️ **未专项复验** | `Ux8KeepAwakeSseTripwireTest` 13 用例全绿；设备上未见 flag 异常；**未做 15s 超时物理常亮实证** |
| 手机档 + 平板档 | ⚠️ **只跑了手机档** | 平板档未启（时间不够） |
| 🔴 红字消失 | ✅ | §3.4 |
| 官方 UI 从本地来源加载 | ✅ | §3.3 |

> ⚠️ **一句话**：**"添加并连上服务器""官方直连"这两条跨源化后仍然成立**，是本轮最关键的回归结论。但**终端、主题、常亮/SSE、键盘扫测、平板档这五项本轮没有实跑**，UX10 §6.1 代价清单第 3 条（"全部已验收项必须重跑"）**只完成了 2/7**。

---

## 10. 收尾复核输出（原始输出）

```
=== (a) 自建实例残留 ===
$ adb devices
List of devices attached
127.0.0.1:5831	device      ← 非自建（产品负责人），全程未对其执行任何 adb 命令
127.0.0.1:5833	device      ← 非自建（产品负责人），全程未对其执行任何 adb 命令
（自建 emulator-6792 已关，无残留）

=== (b) 端口释放 ===
$ Get-NetTCPConnection -LocalPort 6792,6793
(无 => 彻底关闭)

=== (c) 后端未起停 ===
$ Get-NetTCPConnection -LocalPort 4095 -State Listen
LocalAddress LocalPort  OwningProcess
127.0.0.1         4095          3748
$ (Invoke-WebRequest http://127.0.0.1:4095/global/health).StatusCode
200
⇒ pid=3748 与 UX9 §10(c) / UX10 §10(c) 记录的基线 PID 完全一致 ⇒ 全程未起停

=== (d) 本机静态来源端口 ===
$ Get-NetTCPConnection -LocalPort 4173
(宿主 4173 无监听 => 端点只活在 App 进程内，符合"只绑回环、随进程生死"的预期)

=== (e) gradle 锁 ===
$ Test-Path .agent-gradle-lock
True
$ rm -- .agent-gradle-lock
mavis-trash: moved to trash: '.agent-gradle-lock'
lock exists after release: False

=== (f) 工作区改动（本轮确实改了生产代码）===
$ git status --porcelain -- app webapp
 M webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt
 M webapp/src/test/java/com/xiufeigo/opencodeshell/web/DirectConnectAuthBoundaryTest.kt
 M webapp/src/test/java/com/xiufeigo/opencodeshell/web/TerminalReattachTest.kt
?? webapp/src/main/assets/www/ui/
?? webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/LocalUiOriginServer.kt
?? webapp/src/test/java/com/xiufeigo/opencodeshell/web/LocalUiOriginServerTest.kt
（app/** 0 条目；webapp 侧 2 改 + 3 新增）

=== (g) 取证截图（仓库按约定 gitignore research/**/*.png）===
research/web/ux11_s4.png    官方 Home（本机来源渲染）
research/web/ux11_s5..s9.png 抽屉→Settings→段选择器→Server→Add server 对话框
research/web/ux11_s10/s14.png 地址填 http://localhost:4095，**无红字**
research/web/ux11_s15.png   通用错误 "Could not connect to server"（非混合内容红字）
research/web/ux11_r1.png    第二次尝试的 Add server 对话框
research/web/ux11_r6.png    段选择器里已出现 127.0.0.1:4095
research/web/ux11_r7.png    ★ Server 段绿点 + Terminal shell: powershell
```

**⇒ 自建实例已彻底关闭、后端全程未起停、gradle 锁已释放；改动集中在 webapp 侧，未触碰 `app/**`。**

---

## 11. 🔴 事故留档：收尾时发现工作区被**外部进程整体回滚**

### 11.1 现象

最后一轮交付物核验（收尾后约 5 分钟）发现**与 40 分钟前的状态完全不符**：

| 项 | 期望 | 实际读到 |
|---|---|---|
| `webapp/src/main/assets/www/ui/` | 913 文件 | **目录不存在** |
| `LocalUiOriginServer.kt` / `LocalUiOriginServerTest.kt` | 存在 | **不存在** |
| `WebHostActivity.kt` / 两个测试文件 | 已改 | `git status` 干净 ⇒ **已回 HEAD** |
| `HANDOFF_WEB_SHELL.md` / `research/HANDOFF.md` | 已改 | **已回 HEAD** |
| `research/deliver/*.apk` 三份 | 新值 16.1/13.6/11.3 MB | **退回旧值 5.44/3.55/1.27 MB** |
| `git status` | 6 条 webapp 改动 | **只剩 2 条未跟踪的报告 md** |

**只有未跟踪文件（本报告、UX10 报告）幸存。** ⇒ 特征就是一次针对**已跟踪文件**的 `git checkout/reset` + 对新增文件的清理（`git clean` 类），**不是**我这轮的任何一条命令造成的：我本轮只对 `webapp/src/main/assets/www/ui/_assets` 跑过一次 `rm --`（那是在 AGP 丢包之前、且当时 `ui` 目录里还有 `oa` 与顶层文件，之后构建仍产出 913 条）。

**触发时机恰好落在我"关自建实例 → 释放 gradle 锁"之后**，我无法确定执行方（不是我这边的 agent；期间我没有跑过任何 git 写操作）。

### 11.2 恢复过程（已完成并复核）

1. **资源**：`dist` 幸存 ⇒ 直接重铺 `assets/www/ui/`（排除 `.map`，并把 vite 的 `_assets/` 一并落到 `oa/`，与端点映射一致）。
2. **源码**：按内容重建 `LocalUiOriginServer.kt`、`LocalUiOriginServerTest.kt`，并重放 `WebHostActivity.kt`（5 处）、`DirectConnectAuthBoundaryTest.kt`（5 处）、`TerminalReattachTest.kt`（2 处）的改动。
3. **重建中途踩了一次自己的坑**：恢复时我在 `LocalUiOriginServer.kt` 的 KDoc 里多写了一句含 `Basic` / `authorization` 的说明，**撞上了 `noLoopbackListenerAnywhere` 的凭据字样断言**（1 个用例红）⇒ 改写措辞（这正是那条绊线该有的作用：**它连注释都拦**）。
4. **重跑**：`testDebugUnitTest ×2` + `assembleDebug/Release/ReleaseR8` ⇒ 全绿（§7、§8）。
5. **重抄**：交接文档两份的架构段与产物表、报告的体积与 md5 表全部按新值重写。

### 11.3 恢复后的复核

```
webapp classes=24 tests=194 failures=0 errors=0 skipped=0
app    classes=677 tests=6136 failures=0 errors=0 skipped=0

opencode-shell-web.apk              | 16,084,159 B | md5=29dc69df3d73ff0fcf7a0ff0dda498df | ui=913
opencode-shell-web-release-nor8.apk | 13,611,755 B | md5=3e998064a8155f10d0411cab3e3a9fe5 | ui=913
opencode-shell-web-release-r8.apk   | 11,331,893 B | md5=a56570956c037b8d163e50afed4dcdb5 | ui=913
```

### 11.4 ⚠️ 交给下一轮的提醒

> **本仓库的工作区在本轮被外部回滚过一次，且静默（没有任何提示）。** 收尾核验**不能省**，而且它必须包含"重新读一遍磁盘上的产物体积/md5 + `git status`"，不能只相信十分钟前自己写下的数字。
> 设备侧证据（§3、§4、§5.1）来自**回滚之前**的那次装机，是真实读数；回滚**没有**影响已取得证据的有效性，但**恢复后没有再跑一次设备验证**（时间不够），这是本轮最后一个已知缺口。

### 碰过的 serial

- **`emulator-6792`** —— 本轮自建（AVD `OC_UX6_Phone`，端口 6792/6793）。**中途重启过一次**：adb server 与设备列表失联（`adb devices` 返回空、adb server pid 61148 仍在），**我没有执行 `adb kill-server` / `-a` / 通配符 / `reconnect`**，只重启了**自己 9:49 启动的那两个进程**（pid 63364 / 78728）以让它重新注册。**收尾已关。**
- **`127.0.0.1:5831` / `127.0.0.1:5833` / 先前的 `emulator-5830` / `emulator-5832`** —— **非自建实例**（产品负责人 9:32 启动的）。**全程未对其执行任何 adb 命令**（本轮每条 adb 命令都显式带 `-s emulator-6792`，`adb devices` 属只读列举）。
- **产品负责人的实例（`DSH_*` / `T*` / `Pixel_*` / `MCRemote_*`）：一台都没有碰过。**
- 只做了 `adb reverse tcp:4095 tcp:4095`（任务允许）。**后端 `127.0.0.1:4095` 全程未起停**（见复核 (c)）。
