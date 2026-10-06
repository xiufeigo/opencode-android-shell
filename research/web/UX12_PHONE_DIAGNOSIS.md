# UX12-PhoneDiag —— 手机档「列表恒 Loading」阻塞根因诊断（CDP 取证）

- 执行时间：2026-10-06
- 仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`
- 被测产物：`research/deliver/opencode-shell-web.apk`（debug 构建 A）
- 包名：`com.xiufeigo.opencodeshell.web.debug`，Activity `.WebHostActivity`
- **本轮不修代码、不改 `webapp/**` / `app/**`、不碰 gradle、不起停后端。**

## 0. 开轮基线（只读探测）

```
$ & "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -list-avds
Medium_Phone_API_37.0
Medium_Tablet

$ adb devices -l
List of devices attached
                      （空 —— 无任何实体/在线设备）

$ Get-NetTCPConnection -LocalPort 6790
count=0                      （6790 未占用）

$ @(Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'").Count
0                            （全机无 emulator/qemu 进程）
```

基线干净：本轮将创建的 `emulator-6790` 归属明确，不会波及他人设备。

### 0.1 后端只读探测（`http://127.0.0.1:4095`，Basic opencode:opencode）

```
STATUS=200
{"version":"2.0.23","pid":31812,"urls":["http://127.0.0.1:4095"],
 "paths":{"tmp":"C:\\Users\\xiufeigo\\AppData\\Local\\Temp\\opencode"},
 "capabilities":{"persistentPty":false}}
```

**后端在跑，pid = 31812，版本 2.0.23。** 本轮全程只做 `Invoke-WebRequest` 只读探测，
**未 start / stop / kill 任何后端进程**（收尾 §5 复验 pid 未变）。

### 0.2 起机 / 装包（`emulator-6790`）

```
$ Start-Process emulator.exe -ArgumentList @('-avd','Medium_Phone_API_37.0','-port','6790',
      '-no-snapshot-load','-no-snapshot-save','-no-boot-anim','-no-audio','-gpu','swiftshader_indirect')
[{0}] BOOT start avd=Medium_Phone_API_37.0 port=6790
--- procs matching port 6790 --- 2
emulator-6790  offline transport_id:5        （启动瞬间）
--- 轮询 sys.boot_completed --- boot_completed=True
emulator-6790  device  product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa
```

**几何实测（`wm size` / `wm density`）—— 任务书给的 1080x2400 属实，未覆写**：

```
$ adb -s emulator-6790 shell wm size
Physical size: 1080x2400
$ adb -s emulator-6790 shell wm density
Physical density: 420
$ adb -s emulator-6790 shell getprop ro.build.version.release   ->  17
$ adb -s emulator-6790 shell getprop ro.build.version.sdk        ->  37
```

APK md5 复验 = `BF688F8A033EDE65C3D4F8C5E8D1E930`（与任务书一致）。

```
$ adb -s emulator-6790 reverse tcp:4095 tcp:4095        -> 4095
$ adb -s emulator-6790 reverse --list                   ->  host-15 tcp:4095 tcp:4095
$ adb -s emulator-6790 install -r research\deliver\opencode-shell-web.apk   ->  Success
$ adb -s emulator-6790 shell pm clear com.xiufeigo.opencodeshell.web.debug  ->  Success
$ adb -s emulator-6790 reverse --list                   ->  host-15 tcp:4095 tcp:4095   （clear 后仍在）
$ adb -s emulator-6790 shell am start -n .../.WebHostActivity
  mCurrentFocus=Window{d905d06 u0 .../WebHostActivity}
  mFocusedApp=ActivityRecord{205634340 u0 .../WebHostActivity t9}
```

---

## 1. WebView 版本（关键对照项）

### 1.1 手机档（`Medium_Phone_API_37.0`，本轮实测）

```
$ adb -s emulator-6790 shell dumpsys webviewupdate
Current WebView Update Service state
  Current WebView package (name, version): (com.google.android.webview, 145.0.7632.218)
  Preferred WebView package (name, version): (com.google.android.webview, 145.0.7632.218)
  WebView packages:
    Valid package com.google.android.webview (versionName 145.0.7632.218, versionCode 763221809,
    targetSdkVersion 36) is installed/enabled for all users
    com.google.android.webview.beta / .dev / .canary / .debug  is NOT installed.
    com.android.webview  is NOT installed.
```

**手机档 WebView = `com.google.android.webview` 145.0.7632.218**（versionCode 763221809）。

与上一轮 `UX12_PHONE_REGRESSION.md:138` 的应用侧自报日志 `G1 webview version=145.0.7632.218 major=145 min=105 tooOld=false`
**完全一致** ⇒ 宿主读到的版本与系统实际 provider 一致，无版本回退/降级。

### 1.2 手机档 UA（**注入读数**，`am start --es oc_debug_eval "b64:..."`，只读页面状态）

```json
{"ua":"Mozilla/5.0 (Linux; Android 17; sdk_gphone64_x86_64 Build/CE2A.260420.019; wv)
        AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/145.0.7632.218
        Mobile Safari/537.36 OpenCodeWebHost/0.1 (W1-skeleton)",
 "origin":"http://127.0.0.1:4173",
 "href":"http://127.0.0.1:4173/",
 "readyState":"complete"}
```

要点：
- `Chrome/145.0.7632.218` 与 §1.1 provider 版本**逐位一致**；
- **`Mobile Safari/537.36`** —— 手机档 UA 带 `Mobile` token；
- `origin = http://127.0.0.1:4173`，`readyState=complete`（首屏已加载完）。

### 1.3 ⚠️ 平板/手机档的**镜像与 OS 版本并不相同**（AVD `config.ini` 静态读取）

| 项 | 手机档 `Medium_Phone.avd` | 平板档 `Medium_Tablet.avd` |
|---|---|---|
| `image.sysdir.1` | `system-images\android-37.0\google_apis_playstore\x86_64\` | `system-images\android-35\google_apis_playstore_tablet\x86_64\` |
| OS 版本 | **API 37 / Android 17**（`getprop` 实测） | **API 35 / Android 15** |
| `PlayStore.enabled` | `true` | `true` |
| 原生几何 | 1080x2400 / density 420 | 2560x1600 / density 320 |
| `hw.ramSize` | 2048 | 1907 |

⚠️ **这意味着「平板 12 项全 PASS」与「手机恒 Loading」并非同 OS 版本下的对照**：
平板档是 **API 35**，手机档是 **API 37**，两者的 WebView 起点版本、Preload/网络栈实现、bionic 行为都不同。
本轮不擅自判定哪一侧是「正常基线」，只把这条**环境差异**作为候选解释之一记入 §4。

---

## 2. 复现：加服务器并连上（全部真实点击）

路径（每一步都是 `input tap` / `input text` / `input keyevent` 真实交互，**无注入**）：
Home → 抽屉 → Settings → 分节菜单 `Server` → `Add server` → 填地址/口令 → 提交 → 等 **16 秒**（> 任务书要求的 14 秒）。

截图证据链（`research/web/`）：

| 截图 | 内容 |
|---|---|
| `ux12d_01_home.png` | 首屏：Home / `All projects` / `Search sessions` / **`Loading`** |
| `ux12d_02_drawer.png` | 抽屉展开 |
| `ux12d_03_settings.png` | Settings 页 |
| `ux12d_04_sectmenu.png` | 分节菜单，`Server` 在列 |
| `ux12d_05_server.png` | Server 页：红点 `127.0.0.1:4173` + `Terminal shell` 行 |
| `ux12d_06_addserver.png` | Add server 对话框（地址框已聚焦、键盘已弹） |
| `ux12d_07_filled.png` | 已填 `http://127.0.0.1:4095` + 8 位口令 |
| `ux12d_08_ready.png` | 收起键盘后的提交态 |
| `ux12d_10a_menu.png` | 菜单里**新增了 `127.0.0.1:4095` 分节** ⇒ 服务器已添加 |
| `ux12d_11_4095sec.png` | **`127.0.0.1:4095` 绿点** + `Terminal shell` ⇒ **连接成功，复现到「绿点已出现」** |
| `ux12d_12_home2.png` | Back to settings 后 |
| `ux12d_13_home3.png` | **回 Home：仍然 `Loading`；且当前服务器上下文是 `127.0.0.1:4173`** |
| `ux12d_14_projexpand.png` | 展开项目树：同时列出 4173（红点）与 4095（绿点），**两个下面都没有任何项目行** |
| `ux12d_15_4095list.png` | **把服务器上下文切到 4095 之后：列表加载出来了**（`Today` / `v2 smoke test` / `New session` / `Greeting` / `Yesterday` / `Single word reply request`） |

### 2.1 ⚠️ 两个当场就与「恒 Loading」矛盾的事实

1. **`Loading` 在「加任何服务器之前」就已经存在**（`ux12d_01_home.png`，此时只有 4173）。
   ⇒ `Loading` **不是**「连上 4095 之后」才出现的症状。
2. **回 Home 时列表的服务器上下文是 `127.0.0.1:4173`**（`ux12d_13_home3.png` 里
   `All projects / 127.0.0.1:4173`、`Search sessions in 127.0.0.1:4173`），
   **不是**刚连上的 4095。
3. **`ux12d_15_4095list.png`：把服务器上下文切到 4095 后，列表真的渲染出来了。**
   ⇒ 「恒 Loading 无法进入任何会话」**在本轮不成立**。

---

## 3. CDP 抓包（决定性证据）

### 3.1 建立 CDP 通道（标准调试通道，标注）

```
$ adb -s emulator-6790 shell pidof com.xiufeigo.opencodeshell.web.debug        -> 2502
$ adb -s emulator-6790 shell "cat /proc/net/unix | grep -o webview_devtools_remote.*"
webview_devtools_remote_2502
$ adb -s emulator-6790 forward tcp:9333 localabstract:webview_devtools_remote_2502   -> 9333
$ adb -s emulator-6790 forward --list
emulator-6790 tcp:9333 localabstract:webview_devtools_remote_2502
```

`GET http://127.0.0.1:9333/json` —— 确认页面 target 的 url 是 **4173**：

```json
[{ "id": "BCAA7D78A2E87597F141FA72F3A7206B",
   "type": "page",
   "title": "OpenCode",
   "url": "http://127.0.0.1:4173/settings?server=http%3A%2F%2F127.0.0.1%3A4095",
   "webSocketDebuggerUrl": "ws://127.0.0.1:9333/devtools/page/BCAA7D78A2E87597F141FA72F3A7206B",
   "description": "{\"attached\":true,\"width\":1080,\"height\":2400,\"visible\":true}" }]
```

**页面 origin = `http://127.0.0.1:4173`，服务器上下文 = `http://127.0.0.1:4095`** ⇒ 确认跨源成立。

抓包脚本：仓库根临时文件 `_scratch_diag.mjs`（**已按要求删除**），Node **v24.21.0**（原生 `WebSocket`）。
开域 `Network.enable`（`maxTotalBufferSize=100MB`，以便事后取 body）+ `Runtime.enable` + `Log.enable`，
记录 `requestWillBeSent` / `responseReceived` / `loadingFinished` / `loadingFailed` / `consoleAPICalled` /
`exceptionThrown` / `Log.entryAdded`，并在 `loadingFinished` 当场 `Network.getResponseBody` 取回正文。
抓包窗口 **90 秒**；窗口内真实点击：`Back to settings` → `Back to app`（等 20 秒）→ 展开项目行 → 选 4095 → 展开 4095。

```
[diag] WS open -> ws://127.0.0.1:9333/devtools/page/BCAA7D78A2E87597F141FA72F3A7206B
[diag] Network.enable + Runtime.enable + Log.enable done; capturing 90 s
[diag] WROTE research\env\ux12d_capture.json all=116 backend=17 console=1833
```

原始数据落盘：`research/env/ux12d_capture.json`（116 条请求 / 17 条 4095 / 1833 条 console）。

### 3.2 全部 `url 含 127.0.0.1:4095` 的请求（共 17 条，逐条）

`method  path  status  mimeType  encodedDataLength  bodyLen  ACAO  失败原因`

```
GET     /api/info                                        200  application/json      413   174  http://127.0.0.1:4173  -
GET     /api/info                                        200  application/json      413   174  http://127.0.0.1:4173  -
GET     /api/info                                        200  application/json      413   174  http://127.0.0.1:4173  -
GET     /api/info                                        200  application/json      413   174  http://127.0.0.1:4173  -
GET     /api/info                                        200  application/json      413   174  http://127.0.0.1:4173  -
GET     /api/info                                        200  application/json      413   174  http://127.0.0.1:4173  -
GET     /api/info                                        200  application/json      413   174  http://127.0.0.1:4173  -
OPTIONS /api/session?limit=5000&order=desc&parentID=null 204  (空)                    0     -  http://127.0.0.1:4173  -
GET     /api/session?limit=5000&order=desc&parentID=null 200  application/json     1162  2603  http://127.0.0.1:4173  -
OPTIONS /api/session/ses_ef43fab08ffeoHiNxtinjW9V0c      204  (空)                    0     -  http://127.0.0.1:4173  -
OPTIONS /api/session/ses_ef0b994f8ffe9IggreUrPLGTzM      204  (空)                    0     -  http://127.0.0.1:4173  -
GET     /api/session/ses_ef43fab08ffeoHiNxtinjW9V0c      200  application/json      706   467  http://127.0.0.1:4173  -
GET     /api/session/ses_ef0b994f8ffe9IggreUrPLGTzM      200  application/json      540   301  http://127.0.0.1:4173  -
GET     /api/info                                        200  application/json      413   174  http://127.0.0.1:4173  -
GET     /api/provider?location%5Bdirectory%5D=D%3A%5CXiaoZhuTouTou%5Coc-fixture-4095
                                                                200  application/json      680   441  http://127.0.0.1:4173  -
GET     /api/model?location%5Bdirectory%5D=D%3A%5CXiaoZhuTouTou%5Coc-fixture-4095
                                                                200  application/json     3772  82640  http://127.0.0.1:4173  -
GET     /api/info                                        200  application/json      413   174  http://127.0.0.1:4173  -
```

**汇总：17 条全部 `status=200`（或预检 204），`encodedDataLength` 全非 0，`loadingFailed` = 0 条，
无 401/403，无 `(blocked)`，无 `corsErrorStatus`。** 每条 4095 响应都带
`Access-Control-Allow-Origin: http://127.0.0.1:4173`，`remoteIPAddress:port = 127.0.0.1:4095`，
`protocol = http/1.1`，`fromDiskCache = false` ⇒ **跨源 CORS 预检与实际取回都成功**。

**正文实取（`Network.getResponseBody`，证明确实拿到了负载）**：

```
GET /api/info      enc=413 bodyLen=174
  [{"version":"2.0.23","pid":31812,"urls":["http://127.0.0.1:4095"],
    "paths":{"tmp":"C:\\Users\\xiufeigo\\AppData\\Local\\Temp\\opencode"},
    "capabilities":{"persistentPty":false}}]

GET /api/session?limit=5000&order=desc&parentID=null   enc=1162 bodyLen=2603
  {"data":[{"id":"ses_ef43fab08ffeoHiNxtinjW9V0c","projectID":"f7f79b1c0e560bab3fc4e5e5cdc92652c878e406",
  "agent":"build","model":{"id":"fledge-alpha-free","providerID":"opencode","variant":"default"},
  "cost":0,"tokens":{"input":5846,"output":25,...},"outcome":"succeeded",
  "time":{"created":1791198516586,"updated":1791258239268,...},
  "title":"v2 smoke test","location":{"directory":"D:\\XiaoZhuTouTou\\OpenCodeUI"}},
  {"id":"ses_ef0b994f8ffe9IggreUrPLGTzM",...}]}
```

`pid: 31812` 与 §0.1 主机侧只读探测到的后端 pid **完全一致** ⇒ 设备侧 4095 确实打到了本机那个后端。

### 3.3 `/api/project`、`/api/session`、`/api/event` 逐项判定

| 端点 | 抓到没有 | status / bytes | 判定 |
|---|---|---|---|
| **`/api/session`** | ✅ 抓到 | 预检 `OPTIONS` **204** + `GET` **200 / encodedDataLength=1162 / body=2603 字节**，且渲染出 6 个会话 | **取回成功** |
| **`/api/event`** | ❌ **对 4095 从未发出**（count=0） | — | 见下 |
| **`/api/project`** | ❌ **全窗口从未发出**（count=0） | — | 见下 |
| `/api/provider`、`/api/model`、`/api/info` | ✅ 抓到 | **200**（680 / 3772+body 82640 / 413） | **取回成功** |

**关于 `/api/project` 一次都没发出**：不是「发出去被拒」，而是**这个前端版本在 Home 列表根本不用
`/api/project`**，它用 `GET /api/session?limit=5000&order=desc&parentID=null` + 每会话再
`GET /api/session/{id}`。所以「`/api/project` 没有任何网络记录」**不能**推出「客户端状态机坏了」。

**关于 `/api/event`**：事件流**只发往 4173，从未发往 4095**（`url 含 4095 且 path 为 /api/event` 的记录数 = **0**），
原因是当时服务器上下文还是 4173（§2.1 第 2 点）。

### 3.4 `Loading` 期间真正失败的是 **4173** 那条链路

同一抓包窗口内，所有 `4173/api/*` 请求按端点归组：

```
/api/event     n=88   status=[404 x88]   sum_enc=0   fails=[net::ERR_ABORTED x88]
/api/info      n=9    status=[404 x9]    sum_enc=0   fails=[net::ERR_ABORTED x9]
```

全窗口内**所有非 2xx 状态码就是这两组，没有第三种**：

```
404 :4173/api/event   x88
404 :4173/api/info    x9
```

`/api/event` 在 90 秒里被请求了 **88 次**、每次都 **404 + `net::ERR_ABORTED`** ⇒ 这正是上一轮
`UX12_PHONE_REGRESSION.md:205-212` 记的「`/api/event` 每 ~1.5–2s 开一次即断、
`[global-sdk] event stream disconnected`」的**真实成因**。

原因很直白：宿主 `4173` 是**只发静态资源**的本机来源（上一轮 `UX12_PHONE_REGRESSION.md:136`
的原文 `OcLocalUi: local UI origin listening on http://127.0.0.1:4173 (static assets only)`），
**它没有实现 `/api/*`**，所以必然 404。事件流永远建不起来 ⇒ 官方前端的数据加载状态机永远不落地
⇒ 骨架屏停在 `Loading`。

### 3.5 ⭐ 决定性反证：`xfer=0` 是 **`OcPerfJS` 的记录假象**

把 `OcPerfJS` 全部 370 条 `res #` 行按服务器归组统计 `xfer`：

| 服务器 | 条数 | xfer 最小 | xfer 最大 | xfer>0 的条数 |
|---|---|---|---|---|
| **4095（跨源）** | **47** | **0** | **0** | **0** |
| **4173/api（同源）** | **323** | 300 | 309 | **323（全部）** |
| 其他（静态资源等） | 56 | 0 | 135619 | 23 |

即 **`OcPerfJS` 对同源请求稳定报到 300–309 字节，对跨源请求恒报 `xfer=0`**，
而 CDP 网络层证明**跨源请求的字节实打实到了页面**（`/api/model` 正文 **82,640 字节**、
`/api/session` 正文 **2,603 字节**）。

`xfer=0` 的原文（节选，**逐位抄录**）：

```
OcPerfJS res #441 api start=231261 dur=58  enc=0 dec=0 xfer=0    http://127.0.0.1:4095/api/provider
OcPerfJS res #442 api start=231263 dur=80  enc=0 dec=0 xfer=0    http://127.0.0.1:4095/api/model
OcPerfJS res #443 api start=231264 dur=81  enc=0 dec=0 xfer=0    http://127.0.0.1:4095/api/integration
OcPerfJS res #585 api start=348146 dur=22 enc=0 dec=0 xfer=0    http://127.0.0.1:4095/api/session
OcPerfJS res #586 api start=348227 dur=40 enc=0 dec=0 xfer=0    /127.0.0.1:4095/api/session/ses_ef43fab08ffeoHiNxtinjW9V0c
OcPerfJS res #587 api start=348228 dur=47 enc=0 dec=0 xfer=0    /127.0.0.1:4095/api/session/ses_ef0b994f8ffe9IggreUrPLGTzM
OcPerfJS res #606 api start=361390 dur=28 enc=0 dec=0 xfer=0    http://127.0.0.1:4095/api/info
```

对照：同源那条 `/api/event` 同窗口内 `OcPerfJS` 报的是 **`xfer=309`**（有值），
而它其实是一个 **404**：

```
OcPerfJS res #195 api start=79401 dur=10 enc=9 dec=9 xfer=309 http://127.0.0.1:4173/api/event
OcPerfJS res-total n=342 xfer=4401638 dec=4311638 ratio=1.02
```

⇒ **`xfer=0` 与「请求失败」无因果关系**；它是 `OcPerfJS`（基于 Resource Timing 的性能记录）
在**跨源**请求上取不到 `transferSize`（跨源资源 Timing 条目不暴露大小）造成的**测量假象**。
上一轮 `UX12_PHONE_REGRESSION.md:463-465` 据此立下的「CORS 未放行」根因假设，**被本轮网络级证据推翻**。

### 3.6 console 里全部 error / warning 抄录

console 消息共 1,833 条，按类型：`log` 1,111、`log:error` **430**、`info` 290、`log:info` 1、`log:warning` 1。
**error / warning 去重后全部 5 类**（括号内为出现次数）：

```
[389x] network: Failed to load resource: the server responded with a status of 404 (Not Found)
[ 18x] network: Failed to load resource: net::ERR_CLEARTEXT_NOT_PERMITTED
[ 15x] network: Failed to load resource: net::ERR_CONNECTION_REFUSED
[  8x] network: Failed to load resource: the server responded with a status of 401 (Unauthorized)
[  1x] rendering: Blocked aria-hidden on an element because its descendant retained focus.
        The focus must not be hidden from assistive technology users. Avoid using aria-hidden
        on a focused element or its ancestor. Consider using the inert attribute instead.
        ('Blocked aria-hidden' — 无障碍提示，与本议题无关)
```

**逐条归因**（按时间戳定位，`t` 为脚本本机秒）：

| error | 归因 |
|---|---|
| **404 ×389** | 全部是 `4173` 的 `/api/event`(88) + `/api/info`(9) —— 但 389 远大于 97，因为每条 404 会被资源加载层与 `Log` 各记一次。**与 §3.4 同源**。 |
| **ERR_CLEARTEXT_NOT_PERMITTED ×18** | 时间戳集中在 `t=1791259049.145–147`，即**抓包开始后 0.15 秒**的「加服务器握手」瞬间。⚠️ **本轮无任何一条 `Network` 请求记录与之对应**，且它**不影响** 4095 的成功（4095 全部 200）。是添加 http 服务器时的探测噪声。 |
| **ERR_CONNECTION_REFUSED ×15** | 同上时间窗（`t≈049.146–148`），添加服务器时对默认候选地址（如对话框占位符 `http://localhost:4096`）的探测失败。**预期内**。 |
| **401 ×8** | 同上时间窗（`t≈049.147–149`）。⚠️ **本轮 `Network` 层 401 记录数 = 0**，`4095` 全部 `status=200`。是添加握手里对某端点的一次未授权探测，**未影响后续 `/api/info` 200**。 |
| aria-hidden ×1 | 无障碍提示，**与本议题无关**。 |

⇒ **console 里没有任何 CORS 报错原文**（`net::ERR_FAILED` / `Access-Control-Allow-Origin` 缺失 /
`corsErrorStatus` 均为 0 条）。

---

## 4. 结论

### 4.1 判定：**C —— 不是产品缺陷，也不是环境缺陷；上一轮的「恒 Loading」结论源于两处误读**

先逐条否掉两个候选：

**A（环境问题）—— 否掉。**
手机档 WebView = `com.google.android.webview` **145.0.7632.218**（UA `Chrome/145.0.7632.218`，与 provider 逐位一致），
在**同一个 WebView、同一个页面 origin、同一台设备**上，对 `http://127.0.0.1:4095` 的跨源请求
**全部 200、全部拿到正文**（`/api/model` 82,640 字节）。⇒ WebView 没有缺陷，AVD 也没有怪癖。
若真是 WebView 缺陷，同一台设备不可能一边 404（同源 4173）一边 200（跨源 4095）——**网络栈是好的**。

**B（真实产品缺陷 / 跨源回归）—— 否掉。**
跨源路径被证明是通的：CORS 预检 `OPTIONS` **204**、`GET` **200**、
`Access-Control-Allow-Origin: http://127.0.0.1:4173` 精确回显页面 origin、
`loadingFailed` **0 条**、console **零 CORS 报错**、`corsErrorStatus` **0 条**。
不存在「跨源在特定 WebView 上被拦」的回归。

**C —— 成立。** `Loading` 的真实成因与「跨源」无关，是**服务器上下文指向了一个没有 API 的 origin**：

> Home 的服务器上下文默认是 **`127.0.0.1:4173`**（宿主本机静态资源来源，`static assets only`），
> 该 origin **不实现 `/api/*`**，于是 `/api/event`（90 秒 88 次）与 `/api/info` 全部 **404 + `net::ERR_ABORTED`**，
> 事件流永远建不起来 ⇒ 官方前端加载状态机永不落地 ⇒ 骨架屏停在 `Loading`。
> 一旦把服务器上下文**切到 4095**（`ux12d_15_4095list.png`），`/api/session` 返回 2,603 字节真实数据，
> **列表立刻渲染出 6 个会话**。

### 4.2 上一轮的两处误读（这是阻塞被记成「回归」的真正来源）

1. **把 `OcPerfJS` 的 `xfer=0` 当成了「响应字节数为 0」。**
   §3.5 已证明：`xfer=0` 与「请求失败」无因果关系，它只是**跨源请求上 Resource Timing 不暴露
   `transferSize`** 的测量假象（同源 323 条全部有值 300–309，跨源 47 条全部为 0）。
   `UX12_PHONE_REGRESSION.md:460-461` 写的「响应字节数恒为 0 ⇒ SDK 拿不到任何负载」**与网络层事实矛盾**。
2. **把「服务器上下文在 4173」误当成「已连上 4095」。**
   `ux12d_13_home3.png` 显示回 Home 后上下文是 `127.0.0.1:4173`，
   `ux12d_14_projexpand.png` 显示 4173 红点 / 4095 绿点**并存**、两个下面都没有项目。
   **绿点 ≠ 列表用的那个服务器**。上一轮 §5.0 把 4095 侧流量与 4173 侧 `Loading` 混在了一条因果链上。

⚠️ 同时修正上一轮 §5.0 的第二条假设：它写「宿主同源化只对 4173 生效，对 4095 无效」——
**实测反了**：宿主同源化对 **4173** 生效恰恰**是问题所在**（4173 只是个静态资源 origin），
而**跨源的 4095 反而是唯一能正常提供 API 的那条**。

### 4.3 关于「平板 12 项全 PASS」

平板档与手机档**不是同 OS 版本**（平板 API 35 / 手机 API 37，见 §1.3），
且平板档走通的是同源链路，**没有踩到「4173 无 API」这个坑**。
本轮**不判定**「平板是否也受影响」——那需要单独起平板档复现，不在本轮授权范围。
**只如实记录：手机档的 `Loading` 已定位到 4173 链路 404，不是跨源问题。**

### 4.4 最小复现（本轮实际走通，可复验）

```
1. adb reverse tcp:4095 tcp:4095
2. 装 APK、pm clear、起 WebHostActivity           → Home 立刻显示 Loading（未加任何服务器）
3. 抽屉 → Settings → Server → Add server
   填 http://127.0.0.1:4095 + 口令 opencode，提交，等 ≥14 秒   → 4095 绿点
4. Back to app 回 Home                              → 仍 Loading，上下文是 4173，事件流 404 刷屏
5. 展开项目树 → 选 127.0.0.1:4095                   → 列表立刻出 6 个会话   ← 分水岭
```

**判定结论一句话**：`Loading` ＝ 服务器上下文停在**无 API 的 4173 静态来源**；
跨源 4095 全程 200 且有正文。**不改代码、不放宽任何东西**即可自愈。

---

## 5. 收尾（全部已执行并复验）

### 5.1 设备与端口

```
$ adb -s emulator-6790 forward --remove tcp:9333
$ adb -s emulator-6790 forward --list          ->  (空)
$ adb -s emulator-6790 reverse --remove tcp:4095
$ adb -s emulator-6790 reverse --list         ->  (空)

$ adb -s emulator-6790 emu kill
OK: killing emulator, bye bye
OK

$ adb devices -l
List of devices attached
                                （空 —— 无任何在线设备）

$ 全机 emulator.exe / qemu-system-x86_64.exe 进程数      -> 0
$ 其中命令行含 6790 的                                   -> 0
$ Get-NetTCPConnection -LocalPort 6790                 -> 1 条，State=TimeWait，OwningProcess=0
```

⚠️ 6790 只剩 `TimeWait`（`127.0.0.1:6790 -> 127.0.0.1:49498`），**不是 `Listen`、无属主进程**，
属正常 TCP 析构，会自行消失。**本轮自建的 6790 已完全关闭。**

### 5.2 后端未起停的证据

```
$ Invoke-WebRequest http://127.0.0.1:4095/api/info   (Basic opencode:opencode)
STATUS=200
{"version":"2.0.23","pid":31812,"urls":["http://127.0.0.1:4095"],
 "paths":{"tmp":"C:\\Users\\xiufeigo\\AppData\\Local\\Temp\\opencode"},
 "capabilities":{"persistentPty":false}}

$ Get-Process -Id 31812
pid=31812  name=opencode  start=10/06/2026 10:48:58
```

**pid 仍是 31812，与 §0.1 开轮探测时逐位一致；进程启动时间 10:48:58 早于本轮开工（11:51）**
⇒ 全程**未 start / stop / kill / 重启**过任何后端进程，只做过 `Invoke-WebRequest` 只读探测。
设备侧 `/api/info` 读回的 `pid: 31812` 与主机侧一致（§3.2），**旁证后端全程未换进程**。

### 5.3 临时脚本已删

```
$ ls _scratch_diag.mjs
_scratch_diag.mjs exists=False      （经 mavis-trash 移入回收站，可恢复）
```

- 抓包原始数据保留在 **`research/env/ux12d_capture.json`**（116 请求 / 17×4095 / 1833 console）与
  **`research/env/ux12d_capture.log`**；
- 截图 `research/web/ux12d_01..15*.png`（15 张）与启动日志
  `research/env/logs/ux12pd_phone_6790_boot.log` 全部保留，供复核。

### 5.4 本轮碰过的 serial

| serial | 来源 | 处置 |
|---|---|---|
| **`emulator-6790`** | 本轮自建（`Medium_Phone_API_37.0` 镜像，端口 6790） | ✅ `emu kill` 已关，进程 0 残留 |

**全机只碰过这一个 serial。** 未执行 `adb kill-server`、未 `adb -a`、未使用通配符、
未接触任何实体设备或其他实例；`adb devices` 在开轮与收尾时均为「空 / 仅本轮实例」。

### 5.5 纪律自检

| 纪律项 | 状态 |
|---|---|
| 只关自建 `emulator-6790` | ✅ |
| 不 `adb kill-server` / `-a` / 通配符 | ✅ |
| 不改 `webapp/**`、`app/**` 任何字节 | ✅（本轮只写 `research/**`） |
| 不碰 gradle | ✅ |
| 后端只读、不起停 | ✅（pid 31812 未变） |
| 不修代码、不放宽任何东西 | ✅（本轮只产出诊断） |
| 删除临时脚本 | ✅（`_scratch_diag.mjs` 已移除） |

---

## 6. 附：本轮证据文件清单

| 文件 | 内容 |
|---|---|
| `research/env/ux12d_capture.json` | **CDP 抓包原始数据**（116 请求 / 17×4095 / 1833 console，含响应正文） |
| `research/env/ux12d_capture.log` | 抓包脚本 stdout |
| `research/web/ux12d_01_home.png` … `ux12d_15_4095list.png` | 15 张过程截图 |
| `research/env/logs/ux12pd_phone_6790_boot.log` | emulator 启动日志 |

---

**报告完。**
