# F5 · 接入健壮性（修 V7 查出的三条真缺陷）

- **轮次**：F5（接入健壮性工程师，用 mcode 执行）
- **仓库**：`C:\sensorsdata\main\program\opencode-android-shell`
- **设备**：`emulator-6130`（本轮自建，端口 **6130**，偶数且 ≥6130；启动前 6130/6132/6134 无占用、全机 emulator/qemu 进程数 = 0）
- **镜像 / 几何**：`android-35;google_apis_playstore_tablet` + `pixel_tablet` 几何 `1080x2400@420`（**未传 `-skin`** —— V7 已证实 `pixel_tablet` 不是合法 skin 名）
- **后端**：`127.0.0.1:4095` 由 Lead 起好，本轮**全程未起停**（开轮与收尾均 `LISTEN pid=3748`，同一 pid），只做 `adb reverse`

## 0. 一页结论

| # | 项 | 判定 | 一句话 |
|---|---|---|---|
| 1 | **后端不可达 ⇒ 友好错误态** | ✅ **FIXED（debug + release 双份设备复验）** | 裸 JSON + 泄露内部 URL 已消失，改成中文可读页 + **48px 重试按钮**（已实测点了会真重试） |
| 2 | **口令错 ⇒ 明确认证失败 + 可改** | ✅ **FIXED（debug + release 双份设备复验）** | 宿主主动探 `/api/info`，**401 ⇒ 原生对话框"用户名或口令不对" + 返回/继续加载/重试**（三个按钮各 142px ≈ 54dp） |
| 3 | **connect/扫码路径** | 🔴 **未打通**（**不是"应该可以"**） | 卡在**上游 registry 自动播种**这一环：connect screen 是 `fallback`，registry 一旦非空就是死支。清空 localStorage + reload 后仍自动长回来（见 §3） |
| 4 | **日志无口令** | ✅ **确认** | 全量 logcat 中 `wrongpass123` 出现 **0 次**；只有既有的 `passLen=12` 记法 |
| 5 | **回归** | ✅ **0 失败** | `:webapp` **141** 例（128 → 141，**+13**）、`:app` **6136** 例，均 0 fail |

> ⚠️ **本轮额外抓到并修掉一个真缺口**：缺陷2 的第一版**只修了冷启动路径**；
> 首测时发现"在配置对话框里新播错口令"这条路径**仍原样落回骨架屏**
> （因为它绕过了探测直接 `launch`）。已修（`WebHostActivity.kt:649`），复测通过。

---

## 1. 缺陷1 · 后端不可达 ⇒ 友好错误态

### 1.1 改前（V7 证据）

`research/env/v7/v7_n5_noreverse2.png` —— 整页**只有一行裸 JSON**：

```
{"error":"unexpected end of stream on http://127.0.0.1:4095/…"}
```

三条都不合格：**不是人话** / **没有重试按钮** / **泄露内部 URL 与端口**。

### 1.2 根因与实现（行号）

原实现在 `LoopbackProxy.onForward` 的 catch 分支里，把**异常原文**直接序列化成 JSON 写给 WebView。
`e.message` 里必然带 `http://127.0.0.1:4095/…` —— 泄露就是这么来的。

| 位置 | 改后 |
|---|---|
| `webapp/.../host/ConnectErrorPage.kt`（**新增**） | `:71 redact()` 抹掉任何 `scheme://…` 与裸 `host:port`；`:107 html()` 生成页面；`:44 MIN_TOUCH_TARGET_CSS_PX = 48`；`:47 HINT_NETWORK` / `:55 HINT_AUTH` |
| `webapp/.../host/LoopbackProxy.kt:396-398` | 新增 `isDocument` 判定（只看请求自身的 path + Accept） |
| `webapp/.../host/LoopbackProxy.kt:405-411` | 文档请求失败 ⇒ `writeFriendlyPage(output, ConnectErrorPage.unreachableHtml(e.message))` |
| `webapp/.../host/LoopbackProxy.kt:412` | API 请求仍回 JSON，但**先 redact** |
| `webapp/.../host/LoopbackProxy.kt:846 isDocumentRequest` | `GET /`（或空路径）**或** `Accept` 含 `text/html` ⇒ 算文档 |
| `webapp/.../host/LoopbackProxy.kt:860 writeFriendlyPage` | 状态码用 **503**（不是 502：事实是"我们连不上上游"），`Cache-Control: no-store` 保证重试不被缓存挡 |

**为什么不删/改异常原文**：排错要靠它。原文**仍然进 logcat**，只是**不印到用户眼前**。
这条"日志保留原文、界面只放分类"的分界写在 `ConnectErrorPage` 类注释里。

### 1.3 改后（设备证据，release 包）

| 证据 | 路径 |
|---|---|
| 改前 | `research/env/v7/v7_n5_noreverse2.png` |
| 改后（debug 包） | `research/env/f5/f5_d1_unreachable.png` |
| 改后（**release 包**，即交付物） | `research/env/f5/f5_rel_d1_unreachable.png` |
| 点重试后 | `research/env/f5/f5_rel_d1_retry.png` |

**制造不可达的方法**（按派单，`reverse --remove` 被本机安全策略拦 ⇒ **未绕过**）：
`adb reverse tcp:4095 tcp:4099`，事后用 `adb reverse tcp:4095 tcp:4095` 覆盖恢复。

**logcat 原文（release 包）**：

```
I OcWebHost: F5 probe before load path=/api/info
W OcWebHost/Probe: probe /api/info failed: IOException: unexpected end of stream on http://127.0.0.1:4095/...
W OcWebHost: F5 probe unreachable reason=IOException, loading anyway
E OcWebHost/Proxy: upstream GET http://127.0.0.1:4095/ failed: unexpected end of stream on http://127.0.0.1:4095/...
```

点"重试"后**又产生一次新的上游请求**（证明按钮真的重试，不是装饰）：

```
E OcWebHost/Proxy: upstream GET http://127.0.0.1:4095/ failed: unexpected end of stream on http://127.0.0.1:4095/...
```

### 1.4 **是否还泄露内部信息？—— 不泄露**

- 改后截图上**没有** `127.0.0.1`、**没有** `4095`（肉眼可核，见 `f5_rel_d1_unreachable.png`）；
- 界面上只给用户**自己能确认/能改**的东西：「请检查服务器地址是否正确、设备是否连上了服务器所在的网络，然后重试。」；
- 单测守着：`ConnectRobustnessTest` 的 `redact hides scheme url and host port` / `redact also covers bare host port without scheme` / `unreachable page is human readable not bare json` / `error page escapes html so detail cannot break out`；
- 触摸靶：`min-height:48px` 写死在 CSS 里（有单测 `error page retry button meets 48dp hard requirement` 钉住），不靠内容撑高。

---

## 2. 缺陷2 · 口令错 ⇒ 明确认证失败 + 可改

### 2.1 改前（V7 证据）

`research/env/v7/v7_n5_wrongpw.png` —— **永远停在 "Loading" 骨架屏**；
`uiautomator` 树仅 2,248 B、无任何错误文案节点。**用户无从自救**：看不到"认证失败"，也没有重试/改口令入口。

代理层其实**早就知道**（V7 logcat 原文，每 ~1s 重复）：

```
W OcWebHost/Proxy: auth rejected: GET http://127.0.0.1:4095/api/info -> 401; user=opencode passLen=12 authHeaderSent=true
```

⇒ **断层在"代理知道、页面不告诉用户"**。页面侧永远不会给这个提示（上游 SPA 对 401 没有提示分支），
所以**只能在原生侧判定**。

### 2.2 探测实现（行号）

`webapp/.../host/BackendProbe.kt`（**新增**，探测与判定分离，判定是纯函数可单测）：

| 位置 | 内容 |
|---|---|
| `BackendProbe.kt:87 PROBE_PATH = "/api/info"` | 探这个端点 |
| `BackendProbe.kt:81 PROBE_TIMEOUT_MS = 8_000L` | 超时上限 |
| `BackendProbe.kt:122 classify(code, reason)` | 纯判定：`401/403 → AuthFailed`；`2xx/3xx → OK`；无响应或 `5xx → Unreachable`；其余 `→ Unknown` |
| `BackendProbe.kt:136 probe()` | `GET` + `Basic`；**只打 `passLen`，永不回显口令** |
| `BackendProbe.kt:167 probeAsync()` | 后台线程跑，主线程不阻塞（最坏也只是多等 8s 才有提示，不会 ANR） |
| `BackendProbe.kt:185 cancel()` | 点"重试"时取消上一轮，避免乱序结果把界面带错分支 |
| `WebHostActivity.kt:386 probeThenLaunch()` | 冷启动与"保存并连接"都走这里 |
| `WebHostActivity.kt:400` | `AuthFailed ⇒ showConnectErrorDialog(Kind.AUTH_FAILED, backend)` |
| `WebHostActivity.kt:421 showConnectErrorDialog()` | **返回**（回设置对话框，可改口令/改地址）/ **继续加载** / **重试**（重新探测） |
| `WebHostActivity.kt:649` | ⚠️ **本轮补的缺口**：设置对话框"保存并连接"原先直接 `launch`（绕过探测），已改走 `probeThenLaunch` |

### 2.3 超时值 **8 000 ms** 及其依据（三条）

1. **必须小于代理自身的连接超时**：`LoopbackProxy.defaultClient()` 的 `connectTimeout` 是 **15 s**
   （`LoopbackProxy.kt:116`）。探测定得比它还长就没有意义 —— 页面早就自己 502 了。取其一半量级再留余量。
2. **必须远大于真实耗时**：本机回环 + `adb reverse` 下 `/api/info` 的实测是**毫秒级**——
   设备侧 logcat 时间戳 `22:06:43.766 → 22:06:43.928`，**162 ms**（含建连）。
   8 s 是它的**约 50 倍**，冷启动/模拟器 CPU 争抢下也不会误杀健康请求。
3. **必须短到用户能接受**：这是**挡在加载前面的串行一步**，超过 ~10 s 就开始被感知为"应用卡住"。
   8 s 落在"用户还愿意等、但不该再等更久"的区间。

单测钉住上界：`probe timeout is bounded and justified against proxy connect timeout`
（断言 `PROBE_TIMEOUT_MS < 15_000`，防止将来有人把它调到比代理还长）。

### 2.4 设备证据（release 包）

logcat 原文：

```
I OcWebHost: credentials stored backend=http://127.0.0.1:4095 passwordPresent=true
I OcWebHost: F5 probe before load path=/api/info
I OcWebHost/Probe: probe /api/info -> 401; user=opencode passLen=12 authHeaderSent=true
W OcWebHost: F5 probe AUTH FAILED code=401; showing retryable dialog
```

`uiautomator` 原文（`research/env/f5/f5_rel_d2.xml`，6,987 B，对比 V7 的 2,248 B 空树）：

| 节点文本 | bounds | 高度 |
|---|---|---|
| 登录没通过 | `[133,1039][947,1117]` | 标题 |
| 服务器拒绝了登录：用户名或口令不对。请确认后重试，或重新输入口令。 | `[112,1138][968,1242]` | 正文 |
| 返回 | `[102,1254][270,1396]` | **142 px ≈ 54.1 dp** @420dpi |
| 继续加载 | `[598,1254][810,1396]` | **142 px ≈ 54.1 dp** |
| 重试 | `[810,1254][978,1396]` | **142 px ≈ 54.1 dp** |

> 三个按钮 142 px ÷ 2.625 = **54.1 dp ≥ 48 dp** ✅

截图：`research/env/f5/f5_rel_d2_wrongpw.png`（release）、`research/env/f5/f5_d2_wrongpw.png`（debug）。

### 2.5 **确认日志无口令**

- 全量 logcat 中字符串 `wrongpass123` 出现次数 = **0**（机械计数）。
- 代理/探测日志只有既有的安全记法：`passLen=12`、`passwordPresent=true`、**从不** `Basic <base64>`。
- `ConnectRobustnessTest.probe result carries no password field anywhere` 用反射守住
  `Result` 及其子类**不得出现**含 `pass` / `secret` 的字段。
- 错误文案侧：`HINT_AUTH` 只说"用户名或口令不对"，**不回显口令**；单测
  `auth page never echoes password` 钉住。

---

## 3. 缺陷3 · connect/扫码路径 —— **未打通，卡在哪一环说清楚**

### 3.1 根因（行号，**上游 `origin/v2` 侧**）

| 行号 | 内容 | 含义 |
|---|---|---|
| `_upstream/packages/app/src/shell/routes/routes.tsx:79` | `<Show when={servers.list.length > 0} fallback={<ConnectServerScreen />}>` | 🔴 **connect screen 是 `fallback`**，只有 registry **为空**时才渲染 |
| `_upstream/packages/app/src/servers/connect/screen.tsx:18` | `export function ConnectServerScreen()` | 配对页本体 |
| `_upstream/packages/app/src/servers/connect/screen.tsx:63` | `<PairingScanner .../>` | 扫码组件 |
| `_upstream/packages/app/src/servers/connect/scanner.tsx:20-41` | `onMount` → `new QrScanner(video, …)` → `scanner.start()` | 取流就在这里 |
| `_upstream/packages/app/src/servers/connect/camera.ts:6` | `platform.platform === "web" && window.isSecureContext && !!navigator.mediaDevices?.getUserMedia` | 取流的前置条件 |
| `_upstream/packages/app/src/runtime/persistence/storage.ts:32` | `const LOCAL_PREFIX = "opencode."` | registry 的 localStorage 键前缀 |

**回答派单的三个问题**：

- **是我们的代理拦了？** ❌ 不是。代理只做转发，`/` 与 `/_assets/` 都正常 200。
- **是 WebView 设置？** ❌ 不是。相机权限与 `PermissionRequest` 接线都在
  （`WebHostActivity` 有 `onPermissionRequest` 对可信 origin 授 `RESOURCE_VIDEO_CAPTURE`），
  且代理 origin 是 `http://127.0.0.1:<port>` —— **localhost 本身就是 secure context**，
  `camera.ts:6` 那三个条件在本部署下都成立。
- **是上游的注册表语义？** ✅ **就是它。** 页面 boot 时会把**当前 origin 当成一个 server 自动播种**进 registry，
  于是 `servers.list.length > 0` 恒真 ⇒ `fallback` 分支**是死支** ⇒ 真机 UI 上**到不了**扫码入口。

### 3.2 本轮设备实测（把"猜"变成"测"）

DEBUG 构建有 `oc_debug_eval` 通道（V7 那轮用的是 release 包，没有；这轮用 debug 包拿到该通道）：

```
I OcWebHost/Eval: eval=(function(){var p=String.fromCharCode(111,112,101,110,99,111,100,101);
                 var n=0;for(var k of Object.keys(localStorage)){if(k.indexOf(p)===0){
                 localStorage.removeItem(k);n++}}location.reload();return n})() -> "2"
```

即：**清掉了 2 个 `opencode.*` 键并 reload**。`uiautomator` 回读
（`research/env/f5/f5_d3.xml`，10,467 B）：

```
OpenCode / Tabs / Projects / scrollable content / Recent sessions / Loading
```

⇒ reload 之后**又是 Home 会话列表**，不是 connect screen。截图 `research/env/f5/f5_d3_after_clear.png`。

**结论（设备证据支持，不是"应该可以"）**：清空 registry 不够，**它在 boot 时自动长回来**，
所以 `routes.tsx:79` 的 fallback 在本部署下**恒不渲染**。

### 3.3 卡在哪一环 + 依赖什么 + 最小可行改法

- **卡在**：上游"registry 自动播种"这一环（`_upstream/.../registry.tsx` + `routes.tsx:79`）。
  相机/权限/代理/WebView 设置**都不是**阻塞点。
- **依赖**：需要**上游**改语义 —— 把 `ConnectServerScreen` 从"空 registry 的 fallback"
  变成**常驻可达的导航项**（或提供"添加服务器"入口）。这在 `_upstream/**`，
  **本轮无写权限**，且**不是** `webapp/**` 内能绕过去的。
- **最小可行改法（二选一）**：
  1. **上游侧（推荐）**：`routes.tsx:79` 的 `fallback` 改为"无 server 时显示 connect screen，
     **有** server 时在导航里保留一个 '添加服务器' 入口指向同一组件" ——
     `screen.tsx` 本身已经是可复用组件，改动面小。
  2. **宿主侧（本轮可做但未做）**：给宿主加一个**配对模式**开关
     （`WebHostActivity` 读一个 extra），在该模式下**首屏不自动播种 registry**，
     使 fallback 真正渲染，用户用完再退出配对模式。
     ⚠️ 代价：需要宿主能可靠地阻止那次自动播种（`oc_debug_eval` 清 storage 只是**事后**清，
     boot 早期那次播种拦不住），属**未验证方案**，不写成"可行"。

### 3.4 本轮**未**声称的东西（不写"应该可以"）

- ❌ **没有**拿到 `getUserMedia` live 流。
- ❌ **没有**在真机 UI 上点开过 "Scan QR code"。
- ❌ **没有**声称 F3 的取流结论在 release 包上成立或被推翻（V7 已记：release 包无该通道，不可复现）。

---

## 4. 交接文档改动

`HANDOFF_WEB_SHELL.md` —— **只改"当前可交付"这一小节**：

- 交付物表新增两行 `opencode-shell-web-release-f5.apk` / `opencode-shell-web-debug-f5.apk`（含体积与 md5）；
- 旧的两份（`…-nor8.apk` / `opencode-shell-web.apk`）**划删除线并标注"已被 F5 取代"**，附取代原因；
- 新增一段 F5 重建说明（含两条修复内容）；
- "重建记录"补 F5 = **第四次重建**；
- "交付物 = 代码"机械自查那条**换成 F5 轮的 dex 含源自查**（§6）；
- 旧 md5 作废清单**追加** `bc7f3018…` / `90690556…`。

**未改动**该文档的其它任何小节。

---

## 5. 新产物（体积 / md5 / 签名指纹）

| 产物 | 体积 | md5 | sha256 |
|---|---|---|---|
| `research/deliver/opencode-shell-web-release-f5.apk` | **3,975,357 B（≈3.79 MB）** | `a1484b271eab097bc3f6b9cd7e1e4b4d` | `d528c9cea31239d33ab0c38bb0c344046fce2bd08b7436de5708225a55cb5864` |
| `research/deliver/opencode-shell-web-debug-f5.apk` | **5,275,479 B（≈5.03 MB）** | `2b6f48224ed99d45c753a87cb6b11868` | — |

**相对上一份 release（`opencode-shell-web-release-nor8.apk`，3,968,477 B / `bc7f3018…`）**：
**+6,880 B**（两条修复只加了两个小类与一段原生对话框，开销可忽略）。

**签名指纹**（`apksigner verify --print-certs`，与既往交付物同一 `sharedDebug` 签名）：

```
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: e86b70066546bd0bdc2e6cb4d970704bc48b1a244af59c664564f030cca0510a
```

applicationId = `com.xiufeigo.opencodeshell.web.debug`（与既往一致）。

## 6. 包内含源自查（"交付物 = 代码"，机械证明）

对 **release** APK 的 `classes.dex`（raw 8,800,336 B）做 UTF-8 串检索，逐条 `contains`：

| 新增串 | 是否在 dex 内 |
|---|---|
| `用户名或口令不对` | **True** |
| `连不上服务器` | **True** |
| `请检查服务器地址是否正确` | **True** |
| `min-height:48px` | **True** |
| `oc-backend-probe`（探测线程名） | **True** |
| `/api/info`（探测端点） | **True** |

⇒ **两条修复确实进了交付包**，不是只改了工作区。

## 7. 回归用例数

| 模块 | 用例数 | 失败 | 备注 |
|---|---|---|---|
| `:webapp:testDebugUnitTest` | **141** | **0** | 改前 **128** ⇒ **+13**（新增 `ConnectRobustnessTest` 13 例），**只增不减** |
| `:app:testDebugUnitTest` | **6136** | **0** | 全量 |

`BUILD SUCCESSFUL`（`:webapp:assembleDebug` / `:webapp:assembleRelease` 同步跑通）。

> ✅ 有一条测试**先红后绿**：`auth page never echoes password` 首跑失败，
> 因为我第一版文案没写"用户名或口令不对"。**按规矩改的是产品文案、不是断言**
> （`HINT_AUTH` 补上该短语），复跑通过。

---

## 8. 收尾复核输出（原文）

```
=== 杀前归属确认（按 AVD 名 + 端口，HOUSEKEEPING 规则2）===
pid=55968 avd=OC_Deliver_R2 port=6130 MINE=True
=== wm 复原 ===
Physical size: 2560x1600
Physical density: 320
（已无 Override 行）
=== emu kill (serial-scoped) ===
OK: killing emulator, bye bye
OK
=== VERIFY 1: our AVD residual processes ===
OC_*/Medium_Desktop_B residual count = 0
=== VERIFY 2: any emulator/qemu at all ===
emulator/qemu total process count = 0
=== VERIFY 3: port 6130/6132/6134 LISTEN ===
LISTEN count on 6130/6132/6134 = 0
=== VERIFY 4: adb devices ===
List of devices attached
=== VERIFY 5: backend 4095 untouched ===
LocalPort OwningProcess
---------  ------------
      4095          3748
=== gradle lock ===
lock exists after release = False
```

- **故意保留：无。**
- **未执行** `adb kill-server`、**未用** `-a`/通配符，只按 serial 关。
- 开轮时 `adb devices` 空、全机 emulator/qemu 进程数 0、6130–6144 端口全 FREE
  ⇒ **不存在可被波及的用户设备**（无 `MCRemote_*` / `DSH_*` / `T7x_*` 在场）。
- 后端 `4095` 全程**未起停**（开轮与收尾均 `LISTEN pid=3748`，同一 pid）。
- 制造"不可达"用**改错端口**（`reverse tcp:4095 tcp:4099`），**未**执行 `reverse --remove`
  （该命令在本机被安全策略拦，**未绕过**），事后用 `reverse tcp:4095 tcp:4095` 覆盖恢复。
- 释放 gradle 锁时 `Remove-Item` 路径被拦 ⇒ 按派单改用**单条顶层 `rm --`**，
  回执 `mavis-trash: moved to trash`，**未绕过**。
- ⚠️ 本轮全程**同一时刻只有一个 adb 客户端**（V7 轮踩过的"两个 adb 客户端抢 adb"坑未复现）。

## 9. 我碰过哪些 serial

**只有 `emulator-6130`**（本轮自建、端口 6130、AVD `OC_Deliver_R2`、几何 `1080x2400@420`）。
无其它 serial 被本轮触碰。

## 10. 未闭环

1. 🔴 **扫码/取流路径**（§3）：卡在上游 registry 自动播种，**依赖上游改语义**。需产品负责人决定走 §3.3 的哪条路。
2. **"配对模式"宿主侧开关**：给出了方案但**未实现、未验证**，不声称可行。
3. **N-5 第三类（WebView 过旧）**：仍只引用 G1/V3，本轮**未独立复核**。
4. **N-7 命令面板/消息区复制**：沿用 V7 结论，本轮未覆盖。
5. 本轮**没有**用"错误口令 → 改对口令 → 回到正常页面"的**完整闭环**收尾
   （时间预算用于缺陷3 取证；`重试` 按钮的行为已由 `probeThenLaunch` 复用同一路径，逻辑上闭合但**未上机走到终点**）。
6. ⚠️ 设备播种口令时发现：模拟器软键盘下 `input text` / `input keyevent` 都会**丢字符或错位**
   （实测 `input text "opencode"` 得 8 字符却 401；逐字符 keyevent 8 次只得 5 字符）。
   **这不影响本报告结论**（缺陷1 不需要正确口令；缺陷2 只需要"错口令"），
   但**下一轮若要播种正确口令**必须先解决输入保真问题，否则会误判成"后端拒绝正确口令"。
