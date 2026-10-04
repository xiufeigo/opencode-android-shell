# V8 · 收尾复验报告（规范名 release 包 `opencode-shell-web-release-nor8.apk`）

- **轮次**：V8（收尾复验 + 配对路径定性）
- **日期**：2026-10-05 22:21–22:35（设备时间 2026-10-04 22:2x–22:3x，模拟器时区 GMT+0）
- **被测物**：`research/deliver/opencode-shell-web-release-nor8.apk`（**规范名**，`554feb4` 归拢重建后的那一份）
- **我碰过的 serial**：**`emulator-6150`**（唯一一个）

> ⚠️ **本报告不引用 F5 的 `-f5` 副本结论**。下面每一条要么是本轮在 `emulator-6150` 上对**这份规范名包**实测的原始输出，
> 要么明确写"测不到 + 原因"。没有任何一条是从旧包结论外推的。

---

## 0. 本轮发现的一个**真实缺陷**（重要，影响错误态②的解读）

在验"口令错 ⇒ 认证失败"之前，我先撞上一个更严重的问题：**即使口令正确，探测也必然 401。**

### 0.1 设备证据

设备端直接验证"口令本身是对的"（不经 App）：

```
$ Invoke-WebRequest -Uri "http://127.0.0.1:4095/api/info" -Headers @{Authorization="Basic "+[Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("opencode:opencode"))}
STATUS=200
{"version":"2.0.14","pid":3748,"urls":["http://127.0.0.1:4095"],"paths":{...}}
$ ... 无 Authorization
NOAUTH_ERR=401
```

后端进程（**非本轮起的，全程未起停**）：

```
ProcessId : 3748
Name      : opencode.exe
CommandLine: "...\opencode.exe" serve --port 4095 --host name 127.0.0.1
```

而 App 在**播种了正确口令 `opencode`** 之后：

```
I OcWebHost: credentials stored backend=http://127.0.0.1:4095 passwordPresent=true
I OcWebHost: F5 probe before load path=/api/info
I OcWebHost/Probe: probe /api/info -> 401; user=opencode passLen=8 authHeaderSent=true
W OcWebHost: F5 probe AUTH FAILED code=401; showing retryable dialog
```

`passLen=8` 与口令 `opencode` 长度一致（掩码字段也确认为 **8 个点** `••••••••`，见 §2.2），
而同一时刻**直连同一后端、同一组凭据返回 200** ⇒ **不是口令错，是探测请求没带上 `Authorization`。**

### 0.2 根因（源码定位，`webapp/.../host/BackendProbe.kt`）

```kotlin
// BackendProbe.kt:139-148  probe()
val builder = Request.Builder().url(url).get()
if (!password.isNullOrEmpty()) {
    val token = Base64.encodeToString("$BASIC_USER:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    builder.header("Authorization", "Basic $token")     // ← 头只加在 builder 上
}
return try {
    val call0 = call ?: httpClient.newCall(builder.build())   // ← :148
```

```kotlin
// BackendProbe.kt:167-173  probeAsync()
val call = httpClient.newCall(
    Request.Builder().url(backend.trim().trimEnd('/') + PROBE_PATH).get().build(),  // ← :169 没有 Authorization
)
inflight.add(call)
Thread({ val result = probe(backend, password, call) ... })   // ← :173 把这个"无头"的 call 传进去
```

`probeAsync` 为了让 `cancel()` 能取消在途请求，**提前建了一个不带 `Authorization` 的 `Call`**；
`probe()` 在 `:148` 用 `call ?: ...` 优先取了这个无头的 `call`，**把刚在 `:145` 构造好的带头 `builder` 整个丢弃**。

⇒ **每一次探测都是匿名请求** ⇒ 后端一律回 401 ⇒ `classify()`（`:122-128`）判成 `AuthFailed`
⇒ 每次启动都弹"用户名或口令不对"对话框，**即使用户输的是正确口令**。

日志里的 `authHeaderSent=true` 是**误导性的**：它由 `!password.isNullOrEmpty()` 推出（`:154`），
只代表"口令非空"，**不代表请求真的带了头**。这条日志也应当一并修，否则下轮还会被它带偏。

### 0.3 对本轮结论的影响（据实说明）

- 错误态②"口令错 ⇒ 明确认证失败 + 可重试"：**界面行为达标**（文案与三个可操作按钮都对，见 §2.2）。
- 但**触发条件是错的**：它不是"口令错时出现"，而是"**无论口令对错都出现**"。
  ⇒ **该条只能判为「界面 PASS / 判定逻辑 FAIL」**，不能整体记"通过"。
- 修法（**属改代码，超出本轮权限，未动手**）：`probeAsync` 不应自建无头的 `Call`；
  要么把 `password` 一起交给 `cancel()` 之外的路子，要么让 `probe()` 始终用自己 `builder` 建的请求。
  最小改法是删掉 `probeAsync` 里那个 `httpClient.newCall(...)`，让 `probe()` 走 `call == null` 分支自建带头请求，
  `cancel()` 改为在 `probeAsync` 里持有对 `probe()` 内部请求的引用。

---

## 1. 包身份（原文）

```
$ Get-Item research\deliver\opencode-shell-web-release-nor8.apk | Select Length
Size=3975357

$ Get-FileHash … -Algorithm MD5
MD5=627f8ed8a38aaa8caa24a39c6fe1678d0
SHA256=63168db962d42341b5063c9d5bffadf3cf94c1a814eb246d58c7abc094c2b24e
```

> 📌 **派单里给的 md5 少了一位**（`627f8ed8a38aaa8caa24a39c6fe1678d`，35 字符）。
> **本轮实测 = `627f8ed8a38aaa8caa24a39c6fe1678d0`**（32 字符），以本报告为准。

### 1.1 `aapt2 dump badging`（build-tools 36.0.0）

```
package: name='com.xiufeigo.opencodeshell.web.debug' versionCode='1' versionName='0.1.0-webhost' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
minSdkVersion:'26'
targetSdkVersion:'35'
uses-permission: name='android.permission.POST_NOTIFICATIONS'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.CAMERA'
uses-permission: name='com.xiufeigo.opencodeshell.web.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
application-label:'OpenCode Web'
```

### 1.2 `apksigner verify --print-certs`（原文）

```
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: e86b70066546bd0bdc2e6cb4d970704bc48b1a244af59c664564f030cca0510a
Signer #1 certificate SHA-1 digest: 426b682d492e08b8b5cff4a57bac2bef4070f18d
Signer #1 certificate MD5 digest: a8a01dcb84f4932ae6a181cf2524afbd
```

### 1.3 设备端一致性（装的是不是同一份字节）

```
$ adb -s emulator-6150 install -r -d research\deliver\opencode-shell-web-release-nor8.apk
Performing Streamed Install
Success

$ adb -s emulator-6150 shell md5sum /data/app/~~xZr1867OHNANAWHNIo_aZg==/…/base.apk
627f8ed8a38aaa8caa24a39c6fe1678d0  /data/app/.../base.apk
```

**设备端 base.apk 与本机 APK 逐位一致** ⇒ 下面所有设备证据都出自这份规范名包。

### 1.4 环境（口径）

| 项 | 实测 |
|---|---|
| 镜像 | `android-35;google_apis_playstore_tablet` + `pixel_tablet`（**不传 `-skin`**，`pixel_tablet` 非合法 skin 名，V7 已踩） |
| 几何 | `Override size: 1080x2400` / `Override density: 420`（`Physical size: 2560x1600` / `Physical density: 320`） |
| WebView | `versionName=124.0.6367.219` |
| 后端 | `127.0.0.1:4095` → `LISTEN pid=3748`（**Lead 起的，全程未起停**），只做 `adb reverse tcp:4095 tcp:4095` |

---

## 2. 前置身份检查 + 首屏

### 2.1 前置链路原文

```
I OcPerf  : C.webviewCreated+setContentView t=377ms
I OcPerf  : D.settingsConfigured t=378ms
I OcWebHost: facade registered as document-start script
I OcPerf  : E2.perfProbeRegistered t=384ms
I OcPerf  : E3.g1ProbeRegistered t=384ms
I OcWebHost: G1 webview version=124.0.6367.219 major=124 min=105 tooOld=false
I OcWebHost: home=http://127.0.0.1:33819/ backend=http://127.0.0.1:4095 passwordPresent=true
```

### 2.2 口令播种（含"输入保真"这一步，回应 F5 的坑）

F5 轮记录过：本镜像软键盘下 `input text` 会丢字符。本轮**先禁用 IME 再输入**，
并且**做了一次可见字段对照实验**，证明这次输入是保真的：

```
$ adb -s emulator-6150 shell ime disable com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
Input method …LatinIME: now disabled for user #0
$ adb -s emulator-6150 shell settings put secure show_ime_with_hard_keyboard 0
```

对照实验（往**不掩码**的 URL 字段敲同样的字符串，再读回来）：

```
$ uiautomator dump …
<node index="1" text="opencode" … password="false" …/>      ← 逐字符一致，8/8
```

口令字段（掩码，只能看长度）：

```
<node index="0" text="••••••••" … password="true" focused="true" …/>   ← 8 位
```

**结论：8 位输入与 `opencode` 逐字符一致**（由上面对照实验背书）。
—— 也正因如此，§0 的 401 **不能**用"输入丢字符"解释。

### 2.3 首屏 = 官方 UI

`research/web/v8_shots/v8_official_ui.png`：官方 opencode Home 页，
标题栏 `Home`，列表含**本机真实会话**（`Counting from 1 to 4000` / `F4KEEPRELEASE` /
`V6_KEEPAWAKE_ROUND` / `DRAFT_OK` / `DSH插件致后端监听地址报错修复` …），
左上 opencode 标识、右侧会话数角标 `1`。

UI 可用标记口径（**不用"最后一个 `_assets` GET"当首屏**，后台还有几十个懒加载资源）：

```
I OcWebHost: web[LOG] OcPerfJS ocFirstContent kind=raf at=349 @24
I OcWebHost: web[LOG] OcPerfJS paint first-paint at=… 
I OcWebHost: web[LOG] OcPerfJS paint first-contentful-paint at=…
```

代理侧同源资源也在流式取回（`stateDiagram-*.js` / `statapl-*.js` / `src-*.js` / `ssh-config-*.js` …）
⇒ **确认是官方 UI，不是我们的兜底页**（兜底页是 `ConnectErrorPage` 生成的，见 §3）。

### 2.4 ⚠️ CDP：**测不到 + 原因**（不拿别的东西冒充 CDP）

派单要求"用 CDP 取 `title`/节点数/可见文本"。**这份 release 包做不到**：

```
$ adb -s emulator-6150 shell cat /proc/net/unix | grep -E "devtools|webview"
（只有 WebViewZygoteInit 的两条，无 webview_devtools_remote_*）
$ adb -s emulator-6150 shell dumpsys package com.xiufeigo.opencodeshell.web.debug | grep flags
flags=0x0
flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]        ← 无 DEBUGGABLE
```

源码侧同因（`webapp/src` 全量检索 `setWebContentsDebuggingEnabled` **零命中**），
`oc_debug_eval` 注入钩子也被 `if (!BuildConfig.DEBUG) return` 编译关闭（V6/V7 已复核，本轮一致）。

⇒ **所以下面所有"可见文本"证据的采集口径是：`uiautomator` 无障碍树 + `screencap` 截图 + 应用自身 `web[LOG]`**，
**已明确标注为非 CDP**。原生对话框（`android.app.AlertDialog`）的文本能被 `uiautomator` 完整读到（见 §3.2）；
**WebView 内的页面文本 `uiautomator` 读不到**（dump 只有 2,2xx B、非空文本节点 0），
所以 WebView 页面的可见文本**以截图为准**。

---

## 3. 错误态两条

### 3.1 错误态①：后端不可达 ⇒ 友好错误页 ✅ **PASS**

制造不可达用**派单指定的"改错端口法"**（`reverse --remove` 在本机会被安全策略拦，**未绕过**）：

```
$ adb -s emulator-6150 reverse tcp:4095 tcp:4099
$ adb -s emulator-6150 reverse --list
host-16 tcp:4095 tcp:4099          ← 设备侧 4095 打到主机 4099（无人监听）
$ adb -s emulator-6150 shell am force-stop <pkg> ; am start -W -n <pkg>/…WebHostActivity
Status: ok   LaunchState: COLD   TotalTime: 316
```

宿主侧判定与加载：

```
W OcWebHost: F5 probe unreachable reason=IOException, loading anyway
I OcWebHost: home=http://127.0.0.1:45129/ backend=http://127.0.0.1:4095 passwordPresent=true
I OcWebHost: web[LOG] OcPerfJS tick+1 t=2596 nodes=4 @19
```

页面渲染（截图 `research/web/v8_shots/v8_unreachable.png`），可见文本逐字：

> **连不上服务器**
> 请检查服务器地址是否正确、设备是否连上了服务器所在的网络，然后重试。
> **[ 重试 ]**

**断言逐条**：

| 断言 | 判定 | 依据 |
|---|---|---|
| 无裸 JSON | ✅ | 页面是中文标题+说明+按钮，**不是** V7 那种 `{"error":"…"}`（`ConnectErrorPage.kt:13-15` 记录的正是被修掉的旧形态） |
| 可见文本不含 `127.0.0.1:4095` | ✅ | 截图可见文本只有上述三行，**无任何 host:port**。根因是 `ConnectErrorPage.redact` 把 `scheme://host:port/…` 整段替成 `<已隐藏>`（`ConnectErrorPage.kt:23-25,60`） |
| 可见文本不含端口号 | ✅ | 同上；页面只让用户去查"服务器地址/网络" |
| 有 ≥48dp 重试按钮 | ✅ | 包内生成器源码 `ConnectErrorPage.kt:126`：`button{width:100%;min-height:48px;height:48px;…}`；`ConnectErrorPage.kt:40-44` 论证 **48 CSS px ≡ 48dp**（WebView `textZoom=1`、宿主不注入缩放），常量 `MIN_TOUCH_TARGET_CSS_PX = 48` |

> 采集口径提醒：这一页的可见文本来自**截图**（CDP 不可用，见 §2.4）。

### 3.2 错误态②：口令错 ⇒ 明确认证失败 + 可重试 ⚠️ **界面 PASS / 判定逻辑 FAIL**

播种错口令（本轮首次启动时 SecretStore 里残留的是上一轮的 12 字符口令，`passLen=12`）：

```
I OcWebHost/Probe: probe /api/info -> 401; user=opencode passLen=12 authHeaderSent=true
W OcWebHost: F5 probe AUTH FAILED code=401; showing retryable dialog
```

原生对话框的**无障碍树原文**（`uiautomator` 对原生 View 读得到，**这份是真实文本、不是我看图转写**）：

```
TEXT: 登录没通过
TEXT: 服务器拒绝了登录：用户名或口令不对。请确认后重试，或重新输入口令。
TEXT: 返回
TEXT: 继续加载
TEXT: 重试

<node text="返回"   resource-id="android:id/button3" … bounds="[102,1254][270,1396]" />
<node text="继续加载" resource-id="android:id/button2" … bounds="[598,1254][810,1396]" />
<node text="重试"   resource-id="android:id/button1" … bounds="[810,1254][978,1396]" />
```

截图：`research/web/v8_shots/v8_wrongpass.png`。

**断言逐条**：

| 断言 | 判定 | 依据 |
|---|---|---|
| 出现"用户名或口令不对"类提示 | ✅ | 原文 `服务器拒绝了登录：用户名或口令不对。请确认后重试，或重新输入口令。`（与 `ConnectErrorPage.HINT_AUTH` 同源） |
| 有可操作按钮 | ✅ | **三个**：`返回`（回口令框改口令）/ `继续加载`（仍然加载）/ `重试`（重探一次） |
| 按钮 ≥48dp | ✅（旁证） | `WebHostActivity.kt:460-465` 对三个按钮统一设 `minHeight = minTargetPx`（48dp 口径） |
| **从这条路径返回正常态（换回对口令能进官方 UI）** | ⚠️ **部分达成** | 见下 |

**关于"返回正常态"——必须据实说明**：
本轮确实做到了「改回对口令 → 走完流程 → 进入官方 UI」（§2.3 的 Home 截图就是从这条路径出来的），
但**进入官方 UI 的方式是点"继续加载"，不是"口令正确 ⇒ 正常放行"**——
因为 §0 的缺陷，**正确口令拿不到 `Result.OK`**，探测分支永远走不到 `launch()`。
所以这一格判 **"流程可自救 ✅ / 正确口令放行 ❌（缺陷）"**。

**可见文本不含内部 URL/端口**（派单点名要这条证据）：本条路径的原生对话框文本
（上面 `uiautomator` 原文）**不含 `127.0.0.1:4095`、不含任何端口号**。
（口令字段 URL 只在"配置"对话框里作为**可编辑的服务器地址**出现，那是用户自己要填的东西，不是错误页泄露。）

---

## 4. 预检放行 ✅ **PASS**

```
I OcWebHost: G1 webview version=124.0.6367.219 major=124 min=105 tooOld=false
I OcPerf  : J.g1.preflightOk t=320853ms major=124 unknown=false
```

- WebView **124 ≥ min 105** ⇒ 放行；
- `J.g1.preflightOk` 出现、`unknown=false`；
- **`blockedOldWebview` 全程 0 命中**（`logcat` 按该关键字检索无输出）。

---

## 5. 回合常亮三段 ✅ **PASS（三段齐全）**

在官方 UI 里真发了一个回合（提示词 `V8_KEEPAWAKE_PROBE`，截图 `v8_round_active.png`）：

**① 置起**（回合进行中）
```
I OcWebHost/Proxy: D1 roundActive=true ageMs=0
I OcWebHost: D1 applyKeepScreenOn: roundActive=true foreground=true shouldKeep=true flagSet=true
```

**② 回合结束释放**
```
I OcWebHost/Proxy: D1 roundActive=false ageMs=0
I OcWebHost: D1 applyKeepScreenOn: roundActive=false foreground=true shouldKeep=false flagSet=false
```

**③ +90s 兜底点仍 `flagSet=false`**
回合结束于 `22:32:04`，+90s 落点 = `22:33:34`；此后**没有任何一次重新置起**：

```
I OcWebHost: D1 applyKeepScreenOn: roundActive=false foreground=true shouldKeep=false flagSet=false   ← 22:32:51
（此后无新行）
$ adb -s emulator-6150 shell date
Sun Oct  4 22:34:05 GMT 2026            ← 回合结束后 +121s
$ adb -s emulator-6150 shell dumpsys window windows | grep KEEP_SCREEN_ON
（无命中）                                ← 窗口上确实没有留 KEEP_SCREEN_ON
```

⇒ **兜底定时器没有把灯重新按亮**，三段闭环。

---

## 6. IME 重叠量 ⚠️ **可见性口径 PASS，数值口径测不到**

```
ImeTracker: com.xiufeigo.opencodeshell.web.debug:ad426fc2: onShown
D InsetsController: show(ime(), fromIme=true)
$ adb -s emulator-6150 shell dumpsys input_method | grep -E "mInputShown|mIsInputViewShown"
  mInputShown=true
  mIsInputViewShown=true mStatusIcon=0
```

截图 `research/web/v8_shots/v8_ime_open.png`：**composer 完整可见**（占位文案 `Ask anything, / for commands, @ for context...`、
`+`、`MiMo-V2.6-Pro` 模型选择、发送键都在位），**没有任何一部分被键盘吞掉**。

**必须写明的限制**：
- 派单要的口径是 `ImeOcclusion.overlapPx(composer, imeTopCss)`，那是个 **CSS px 数值**，
  需要从页面里读 composer 的 `Rect` ⇒ **需要 CDP ⇒ 本 release 包没有 CDP**（§2.4）。
- 另外这次 `screencap` 里**软键盘本身没被画进截屏**（只拍到了 IME 导航条），
  所以"键盘矩形 vs composer 矩形"的两条边也没法从这张图上量。
- ⇒ **本轮给出的是"未被遮挡"的定性结论；`overlapPx == 0` 这个数值我没有测到，不冒充已验证。**

---

## 7. 收尾复核输出（原文）

### 7.1 杀前归属确认（按 AVD 名 + 端口，只认自己起的）

```
=== PRE-KILL ownership check (AVD name + port) ===
pid=24648 avd=OC_Deliver_R2 port=6150 MINE=True
pid=45984 avd=OC_Deliver_R2 port=6150 MINE=True
```

### 7.2 几何复原 + 只摘自己那条 reverse

```
$ adb -s emulator-6150 shell wm size reset ; wm density reset
Physical size: 2560x1600
Physical density: 320          ← 已无 Override 行
$ adb -s emulator-6150 reverse tcp:4095 tcp:4095 ; reverse --list
host-16 tcp:4095 tcp:4095
```

### 7.3 关停

```
$ adb -s emulator-6150 emu kill
OK: killing emulator, bye bye
OK
```

### 7.4 复核（25 s 后）

```
=== [1] OC_*/Medium_Desktop_B residual processes ===
emulator/qemu process count = 0
OC_*/Medium_Desktop_B residual = 0

=== [2] ports 6150/6152/6154 LISTEN ===
port 6150 LISTEN=0
port 6152 LISTEN=0
port 6154 LISTEN=0

=== [3] adb devices ===
List of devices attached
（空）

=== [4] gradle lock ===
.agent-gradle-lock exists = False

=== [5] backend 4095 untouched (must still be pid 3748) ===
4095 LISTEN=1 pid=3748
```

- **故意保留：无。**
- **本轮只碰过 `emulator-6150` 一个 serial。** 开轮时 `adb devices` 为空、全机 emulator/qemu 进程数 **0**、
  6150–6164 端口全 FREE ⇒ **不存在可被波及的用户设备**。
- **未执行 `adb kill-server`、未用 `-a`/通配符**；只按 serial 关。
- 后端 `4095`（`pid=3748`）**全程未起停**（开轮前与收尾同一 pid）。
- ⚠️ 本轮**没有遇到模拟器静默退出**（F4/R2/V6/V7 轮记录过的那类），`emulator-6150` 全程存活到本轮主动 `emu kill`。

## 8. 未闭环 / 测不到（如实列出）

1. **🔴 `BackendProbe` 每次探测都不带 `Authorization`**（§0）——**这是本轮发现的最严重问题**，
   正确口令也被判成"用户名或口令不对"。属改代码，超出本轮权限，**未动手**。
   连带那条误导性日志 `authHeaderSent=true`（`:154`）也需一起修。
2. **CDP 在这份 release 包上不可用**（无 devtools socket、无 `DEBUGGABLE`）⇒
   派单里所有"CDP 取 title / 节点数 / 可见文本"的要求**均未按 CDP 口径完成**，
   已降级为 `uiautomator` + 截图 + 应用自身 `web[LOG]`，并逐处标注。
   若必须要 CDP 口径，需另出一个 debug 构建。
3. **`IME overlapPx == 0` 的数值未测到**（原因同 2）——只给了"未被遮挡"的定性结论。
4. **错误态②的"正确口令 ⇒ 正常放行"未达成**（被 §0 缺陷挡住），只能靠"继续加载"进官方 UI。
5. 配对/扫码页在本轮**没有也不可能被直接看到**——原因与定性见
   `research/audit/PAIRING_ROUTE_VERDICT.md`（结论：**上游行为，我们与官方一致**）。
6. 本轮**没有**给 `Connection:close` 之类代理细节做回归（不在派单范围）。

---

## 9. 收尾（谁起谁关）

本轮**只**启动了 `OC_Deliver_R2` 的一个实例（`emulator-6150`，端口 6150），
收尾按 `research/HOUSEKEEPING.md` 执行，**完整复核输出见 §7**。
全程**未执行 `adb kill-server`、未用 `-a`/通配符**、
**未碰**任何 `MCRemote_*` / `DSH_*` / `T7x_*` 或来源不明的实例。
后端 `4095`（`pid=3748`）**全程未起停**。**故意保留：无。**
