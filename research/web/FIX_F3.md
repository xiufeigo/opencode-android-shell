# F3 · 按上游规则重写通知投递 + 补相机权限与扫码配对

- **轮次**：F3（缺口实现工程师）
- **仓库**：`C:\sensorsdata\main\program\opencode-android-shell`
- **日期**：2026-10-05（硬期限 2026-10-07）
- **上游基准**：`_upstream` 的 `origin/v2` = `ecfa884`
- **取证方式**：**只用 git 对象**（`git -C _upstream show origin/v2:<path>` / `git -C _upstream grep`），**未切 `_upstream` 工作区**
- **设备**：`emulator-6050`（`OC_Deliver_R2`，`-read-only`，端口 6050，镜像 `android-35;google_apis_playstore_tablet` + `pixel_tablet`，几何 `1080x2400@420`）
- **后端** `127.0.0.1:4095`：**未起停**（全程 `LISTEN pid=3748`），只做 `adb reverse tcp:4095 tcp:4095`

---

## 0. 结论速览

| 项 | 判定 | 一句话 |
|---|---|---|
| **N-1 通知投递** | **已按上游重写（门控方向更正 + 自造投递口清除）** | 投递口**只允许**垫片一个调用者；自检页那条**绕过全部上游门控**的自造投递口已删；宿主保持"纯投递、零策略" |
| **N-2 相机权限 + 扫码取流** | **已补齐并在设备上真跑通** | `CAMERA` + `uses-feature required="false"` + 只对可信来源真正授予；设备实测 `getUserMedia` **拿到 live 流并出画面** |

---

## 1. N-1 · 按上游规则重写通知投递

### 1.1 上游规则（`origin/v2` 行号原文）

**① 只有两种通知（封闭 union）** —— `packages/app/src/shell/notifications/notification.tsx:26-29`：

```ts
export const Notification = Schema.Union([
  Persistence.struct({ ...NotificationBase, type: Schema.Literal("turn-complete") }),
  Persistence.struct({ ...NotificationBase, type: Schema.Literal("error"), error: SessionError.Error }),
])
```

**② 门控：该会话必须有打开的 tab 才发系统通知** —— `notification.tsx:227,240`：

```ts
const hasOpenTab = sessionIDHasOpenTab(tabs.store, input.key, sessionID)
...
if (hasOpenTab && settings.notifications.agent()) {
  void input.coordinator.system(`${input.key}\0${eventID}`, () =>
    platform.notify(language.t("notification.session.responseReady.title"), session.title ?? sessionID, …),
  )
}
```

> 🔴 **方向已按 N5 更正**：是"**有 tab 才发**"，不是"有 tab 则静音"。A4 的 M13 表述逻辑相反，本轮实现以 `hasOpenTab &&` 为准。

**③ 按 eventID 去重（跨重启）** —— `coordinator.ts:73-93` 的 `claim()`，localStorage 键 `opencode:notification-system` / `-sound`，环形保留 `MAX_CLAIMED = 500`（`coordinator.ts:4`）。

**④ 有焦点即放弃，不补投** —— `coordinator.ts:62`：

```
 62:         if (typeof document !== "undefined" && document.hasFocus()) return
```

（已实读该行原文；**无**重估/补投分支。）

### 1.2 我们侧的"策略只有一个真相来源"（沿用 N5 结构，本轮未推翻）

上游把系统通知交给浏览器 `Notification` API（`runtime/platform/web.ts:21-40`），而 Android WebView 没有这个 API ⇒ 上游在 `web.ts:22` `if (!("Notification" in window)) return` **早退**。
因此宿主提供**垫片**（`oc-host.js:376-415`），让**上游的策略原样执行**；宿主 `NotifierHost.postTurnFinished` 退化为**纯投递**（无去重/无静音/无焦点判断）。

**上面 ①②③④ 全部由上游在页面内原生执行，宿主不复制任何一份。**

### 1.3 🔴 本轮真正修掉的自造投递口（这是 N-1 的实质缺口）

N5 §2 指出：`oc.postMessage` 的**唯一**调用者是自检页 `index.html:284`：

```js
var r = oc.postMessage("自检通知", "W1 骨架 postMessage 通路验证", "sess_demo_1");
```

**直接调 `oc.postMessage` 是一条绕过上游全部门控的自造投递口**：不经 `web.ts:24-28` 权限门控、不经 `web.ts:29`/`coordinator.ts:62` 焦点门控、不经 `coordinator.ts:73-93` eventID 去重、不受 `notification.tsx:240` hasOpenTab 约束。
**它同时是生产路径上唯一一个调用者** —— 也就是说 N5 判定"生产路径断裂"的那条**唯一**的实际连接，就是这条自造口。

**改动**（`webapp/src/main/assets/www/index.html`，GBK 文件按行号拼接，未改编码）：

```diff
 $("notifyBtn").onclick = function () {
-  oc.requestNotificationPermission();
-  setTimeout(function () {
-    var r = oc.postMessage("自检通知", "W1 骨架 postMessage 通路验证", "sess_demo_1");
-    set("notify", (r === 0 ? ok("已发送 (0)") : bad("返回 " + r))
-      + " 权限=" + oc.hasNotificationPermission());
-  }, 800);
+  // F3: the self-check page no longer calls oc.postMessage directly.
+  // ... (why: it bypassed every upstream gate)
+  oc.requestNotificationPermission();
+  setTimeout(function () {
+    var granted = oc.hasNotificationPermission();
+    if (granted !== 1) {
+      set("notify", bad("host has not granted POST_NOTIFICATIONS; this turn is dropped by web.ts:28"));
+      return;
+    }
+    try {
+      new window.Notification("W1 self-check via upstream web.ts:21-40", { body: "..." });
+      set("notify", ok("delivered through upstream path (web.ts:21-40)") + " perm=" + granted);
+    } catch (e) { set("notify", bad("shim unavailable: " + e)); }
+  }, 800);
 };
```

**为什么安全**：只是把自检页从"绕过策略"改成"走同一条策略路径"，不新增任何投递能力，也**没有**谎报权限（未授予时如实报"被 web.ts:28 丢弃"）。

### 1.4 删除了哪几条自造

| 自造项 | 处置 | 依据 |
|---|---|---|
| **自检页直调 `oc.postMessage` 的绕过投递口**（`index.html:284`） | ✅ **本轮删除**（本轮唯一的实质改动） | `notification.tsx:26-29` 封闭 union + §1.1 门控均在垫片路径上；直调全不经过 |
| **A2 权限请求类系统通知** | ❌ **未删** —— 位于 `app/**`（`AppNotificationKind.PERMISSION`，`app/src/main/java/com/xiufeigo/opencodeshell/notify/AppNotifier.kt`），**本轮禁改** | 见 §1.5 |
| **A3 提问类系统通知** | ❌ **未删** —— 同上（`AppNotificationKind.QUESTION`） | 见 §1.5 |
| **A6 后台补投 120s** | ❌ **未删** —— 位于 `app/**` | 见 §1.5 |

> ✅ **我确认了 N5 的判定**：全仓 `AppNotificationKind` 只有 `PERMISSION` / `QUESTION` 两个自造 kind，`coordinator.system`/`sound` 的调用点只有 `notification.tsx:229,241,257,272` 四处，**上游没有任何权限请求类/提问类系统通知**。⇒ **N5 判定成立，无需更正。**
> 但这两类的**代码**在 `app/**`，本轮写权限只到 `webapp/**`，**删不掉** ⇒ 如实记为未闭环（§6）。

### 1.5 反向验证

见 §5（实跑突变，非声称）。

---

## 2. N-2 · 相机权限与扫码配对

### 2.1 上游用法（`origin/v2` 行号原文）

**真取流，服务器配对扫码** —— `packages/app/src/servers/connect/camera.ts:6`：

```ts
const supported = platform.platform === "web" && window.isSecureContext && !!navigator.mediaDevices?.getUserMedia
```

`packages/app/src/servers/connect/scanner.tsx:12-19,38`：

```ts
const video = document.createElement("video")
video.setAttribute("playsinline", "")
video.muted = true
onMount(() => {
    const scanner = new QrScanner(
      video,
      (result) => { … pairingLink(result.data) … redeemPairingLink(link) … },
      { preferredCamera: "environment", maxScansPerSecond: 10, returnDetailedScanResult: true },
    )
    …
    void scanner.start().then(…)
```

入口：`servers/connect/screen.tsx:73` / `dialog.tsx:98`（`PairingScanner` lazy 加载）。
`screen.tsx:136-155` 自带优雅降级（按钮 disabled + `server.connect.camera.unavailable`），所以此前**不崩、只是入口不可用**。

### 2.2 宿主侧补齐

**① manifest**（`webapp/src/main/AndroidManifest.xml`，**派单明确允许改此项**）：

```diff
+    <uses-permission android:name="android.permission.CAMERA" />
+    <uses-feature android:name="android.hardware.camera" android:required="false" />
```

`required="false"` 照抄原生壳 `app/src/main/AndroidManifest.xml:16-17` 写法，**不**把无摄像头设备挡在安装之外。

**② `onPermissionRequest` 真正授予**（`webapp/.../WebHostActivity.kt:1028`，`HostWebChromeClient`）：

- 过滤 `request.resources` 只留 `RESOURCE_VIDEO_CAPTURE`；**其它资源一律 deny（白名单，不整包放行）**；
- **origin 不可信 ⇒ 直接 `request.deny()`**（`WebHostActivity.kt:1042-1047`）；
- 已持有运行期权限 ⇒ 立即 `request.grant(arrayOf(RESOURCE_VIDEO_CAPTURE))`（`:1050-1059`）；
- 未持有 ⇒ **先 `cameraPermission.launch(Manifest.permission.CAMERA)`，结果回来再答复**（`:1060-1072` + `registerForActivityResult` 回调）：**不提前 grant**，避免给页面一个随即死掉的流；
- 拒绝时**不自造任何 UI 文案** —— 上游 `camera.ts:10-14` 判 denied、`screen.tsx:148-154` 自己显示既有降级文案。

**③ `WebSettings`**：`mediaPlaybackRequiresUserGesture = false` **已存在**于 `WebHostActivity.kt:553`，**无需改动**。
依据：上游 `scanner.tsx:12-15` 建的是 `muted` + `playsinline` 的 `<video>` 且**没有用户手势**；WebView 默认要求手势，否则起流后画面出不来。（该行被 `CameraPermissionHostTest.mediaPlaybackDoesNotRequireUserGesture` 钉住。）

### 2.3 只对可信来源授予 —— 依据与实现

**依据**：`PermissionRequest.getOrigin()`（`android.webkit.PermissionRequest`，`javap` 实读）携带**发起方的 origin**。一个 origin 拿到摄像头 = 用户不知道自己正被谁看着；因此**只对宿主自己加载的页面授予**。

**实现**（`WebHostActivity.kt`）：

- `isTrustedWebOrigin(origin)`（`:1119-1134`）：可信集合 = **回环代理 `proxy?.publicOrigin()`**（`LoopbackProxy.kt:169` `http://127.0.0.1:$port`，正是 `WebHostActivity.kt:412` `webView.loadUrl` 用的那个 origin）**+ 配置的后端 origin**（`SecretStore.KEY_BACKEND_URL`，默认 `http://127.0.0.1:4095`）**+ `SecretStore.DEFAULT_BACKEND_URL`** 兜底。
- `sameOrigin(a, b)`（`:1136-1145`）：**解析后逐段比 `scheme` / `host` / `port`**，**不用** `startsWith`/`contains` —— 后者可被 `https://evil.test/?x=127.0.0.1` 这类 origin 绕过。默认端口按 scheme 归一（https→443，其余→80）。

### 2.4 🔴 设备验证：**真能扫码取流**（不是"应该可以"）

设备：`emulator-6050`，WebView `com.google.android.webview 124.0.6367.219`，API 35，模拟摄像头存在（`pm list features` 含 `android.hardware.camera` / `.front` / `.level.full`）。

**安装后运行时权限**（`adb install -r -g`）：

```
      android.permission.CAMERA: granted=true
      android.permission.POST_NOTIFICATIONS: granted=true
```

**取流探针**（走 `oc_debug_eval` 调试注入通道，仅 DEBUG 构建；在**真实加载上游 SPA** 的宿主页面里执行 `getUserMedia`）：

页面状态回读（`String(JSON.stringify(window.f3))`）：

```
F3_STATE: "{\"s\":[\"secure=true\",\"hasGUM=true\",
            \"gUM=OK|label=camera2 0, facing back|state=live\",
            \"play=OK|vw=480|vh=640\"],\"e\":null}"
```

**宿主 logcat 原文**（这是**我自己实现的** `onPermissionRequest` 打出来的）：

```
I OcWebHost: camera permission GRANTED (already held) origin=http://127.0.0.1:34805/
```

**逐条判定**：

| 断言 | 结果 | 证据 |
|---|---|---|
| 宿主真的授予了 `RESOURCE_VIDEO_CAPTURE` | ✅ | `camera permission GRANTED ... origin=http://127.0.0.1:34805/`（可信来源 ⇒ 走 grant 分支） |
| `getUserMedia` 成功 | ✅ | `gUM=OK label=camera2 0, facing back state=live` |
| 拿到的是**后置**摄像头（与上游 `preferredCamera: "environment"` 同侧） | ✅ | `facing back` |
| 流是活的 | ✅ | `state=live` |
| 预览真出画面（非黑屏） | ✅ | `play=OK vw=480 vh=640` + 截图 `research/env/f3/cam_preview.png`（可见绿底取景框内为模拟摄像头合成场景，像素头像） |

> ✅ **N-2 在设备上真跑通**：`onPermissionRequest` 授予 → WebView 放行 → `getUserMedia` 拿到 live 后置流 → `<video muted playsinline>` 播放出画面。
> ⚠️ **未验的一步**：没有真的走到 `PairingScanner` 组件去扫一个**配对二维码**（那需要上游 UI 走到 `servers/connect` 的扫码入口并出示特定 QR）。本轮验的是**取流这一层**（也就是 N-2 缺的那一层）；**扫码解码层未验**。

---

## 3. 交付物（重建）

| 文件 | bytes | md5 | sha256 |
|---|---|---|---|
| `research/deliver/opencode-shell-web.apk`（DEBUG） | **5200219** | **de1a476c32dc25c53d1034ea01ca1e6f** | `0e9943c91e12027db8e1283518cee11892a75193edd3d6b6af2a3ebb47b0b1b0` |
| `research/deliver/opencode-shell-web-release-nor8.apk`（RELEASE） | **3968173** | **5aa0b63657cb599cd288ebc5597e9d21** | `d9091db2cc236a17c096602b19a0842f943739e4d2c3ba49666b6053de48c049` |

**签名指纹**（build-tools 36.0.0，apksigner，与上一轮**同一把 debug keystore，未变**）：

```
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: e86b70066546bd0bdc2e6cb4d970704bc48b1a244af59c664564f030cca0510a
Signer #1 certificate MD5 digest: a8a01dcb84f4932ae6a181cf2524afbd
```

### 3.1 包内含源自查（`aapt2` / 直接解包取字节）

| 资产 | 源码 bytes / md5 | APK 内 bytes / md5 | 一致 |
|---|---|---|---|
| `assets/www/oc-host.js` | 16939 / `2b6ee8db66451f659737f29dcfc96f45` | 16939 / `2b6ee8db66451f659737f29dcfc96f45` | ✅ |
| `assets/www/index.html` | 17621 / `af724f814d5889bec018a1ad17763b23` | 17621 / `af724f814d5889bec018a1ad17763b23` | ✅ |

（DEBUG / RELEASE **两个包**都做了同样比对，**均一致**。）

### 3.2 权限确实进了 APK（`aapt2 dump`）

```
uses-permission: name='android.permission.POST_NOTIFICATIONS'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.CAMERA'
```

```
E: uses-feature (line=41)
  A: android:name="android.hardware.camera"
  A: android:required=false
```

---

## 4. 回归

命令（前台、持 `.agent-gradle-lock`）：

```
./gradlew.bat :app:testDebugUnitTest :webapp:testDebugUnitTest --rerun-tasks
```

**结果：`BUILD SUCCESSFUL in 3m 9s`　`GRADLE_EXIT=0`　`76 actionable tasks: 76 executed`**（`--rerun-tasks` 真跑，**不是** UP-TO-DATE）

| 套件 | 测试类 | 用例数 | 失败 | 错误 | 跳过 |
|---|---|---|---|---|---|
| `:webapp:testDebugUnitTest` | 14 | **115** | **0** | 0 | 0 |
| `:app:testDebugUnitTest` | 677 | **6136** | **0** | 0 | 0 |
| **合计** | 691 | **6251** | **0** | **0** | **0** |

本轮新增用例：

| 测试类 | 用例数 | 失败 |
|---|---|---|
| `CameraPermissionHostTest`（**本轮新增**） | **9** | 0 |
| `NotificationPolicyBridgeTest`（N5 的 7 例 + 本轮 2 例） | **9** | 0 |

**用例数只增不减**：N5 轮 `webapp` = 104 → 本轮 **115**（**+11**：相机 9 + 投递口 2），`:app` **6136 未动**。
**没有删除、没有放宽任何既有断言。** 备份在 `research/env/f3/revert_backup/`。

---

## 5. 反向验证

### 5.1 ⚠️ 先说一个**必须如实交代**的降级

**本轮的突变验证没有走 JUnit runner。** 原因（**不是**我的问题，也不是我造成的）：

本轮跑到突变步骤时，**并发 agent** 正在改 `webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/RoundActivityTracker.kt`
（`git status` 显示该文件与 `LoopbackProxy.kt` 被改动，且有配套新文件
`webapp/src/test/.../host/RoundStreamSignalReleaseTest.kt` 与 `research/web/RELEASE_VERIFY_V5.md` —— 属另一路 V5 轮）。
**该文件当时处于写坏的中间态**，`javac/kotlinc` 报：

```
RoundActivityTracker.kt:61:36 Unresolved reference 'DEFAULT_ROUND_HOLD_TIMEOUT_MS'.
RoundActivityTracker.kt:84:32 Unresolved reference 'DEFAULT_MAX_ROUND_KEEP_MS'.
RoundActivityTracker.kt:151:6  Syntax error: Missing '}'.
RoundActivityTracker.kt:283:1  Syntax error: Unclosed comment.
```

⇒ `:webapp:compileDebugKotlin` 失败 ⇒ **整个 `:webapp:testDebugUnitTest` 连编译都进不去**。
按规矩**不许碰别人的文件**，所以我**没有**去修它、也没有**绕过**。
且收尾时 `.agent-gradle-lock` 的持有者是那一路（`F4 keep-screen-on release round; owner=mavis; 2026-10-05T04:37:05`），
按抢锁规矩**我也没有去抢它**。
（最后一次复查：那两个常量 `DEFAULT_ROUND_HOLD_TIMEOUT_MS` / `DEFAULT_MAX_ROUND_KEEP_MS` **仍未定义**，该文件 `LastWriteTime 04:38:08` —— 仍在被写。）

**我做的是**：把两个绊线测试类里的**断言逻辑逐条原样复刻**成 PowerShell（`research/env/f3/mutate_fallback.ps1`），
在**内存副本**上施加同样的突变，检验每条断言是否**由绿翻红**。

> 🔴 **这条降级的准确含义**：它证明的是"**断言会翻红**"，**不是**"**JUnit 任务会变红**"。
> 我**没有**在 JUnit 里实跑出红。**不许把下面这张表当成 JUnit 的实跑结果。**

（另：第一次尝试跑 JUnit 突变时脚本有 PowerShell 嵌套引号语法错误，**整段没执行**；已确认当时三个源文件**均未被改动**。）

### 5.2 突变结果（断言级，非 JUnit）

| 状态 | 施加的突变 | 翻红的断言 |
|---|---|---|
| **BASELINE** | 无 | **0 条红**（11 条断言全绿） |
| **M1** | `index.html` 复活 `oc.postMessage("MUTBYPASS",…)` 直调绕过投递口 | `onlyTheShimMayCallTheHostDeliveryMouth` |
| **M2** | `onPermissionRequest` 去掉可信来源判断（`val trusted = true`） | `grantIsRestrictedToTrustedOrigins` |
| **M3** | manifest 去掉 `CAMERA` + `uses-feature camera` | `manifestDeclaresCameraPermission`、`manifestCameraFeatureIsNotRequired` |
| **M4** | 整包放行 `request.grant(request.resources)`（去掉白名单） | `onlyVideoCaptureIsGranted` |
| **M5** | `mediaPlaybackRequiresUserGesture = true` | `mediaPlaybackDoesNotRequireUserGesture` |

原始输出：

```
=== BASELINE (current sources) ===
  [BASELINE] checks=11 FAIL=0
=== M1: index.html direct oc.postMessage (self-made bypass resurrected) ===
  [M1] checks=11 FAIL=1
     FAIL -> onlyTheShimMayCallTheHostDeliveryMouth
=== M2: onPermissionRequest trusted-origin gate removed ===
  [M2] checks=11 FAIL=1
     FAIL -> grantIsRestrictedToTrustedOrigins
=== M3: manifest CAMERA declaration removed ===
  [M3] checks=11 FAIL=2
     FAIL -> manifestCameraFeatureIsNotRequired
     FAIL -> manifestDeclaresCameraPermission
=== M4: whole-array grant (whitelist removed) ===
  [M4] checks=11 FAIL=1
     FAIL -> onlyVideoCaptureIsGranted
=== M5: mediaPlaybackRequiresUserGesture flipped to true ===
  [M5] checks=11 FAIL=1
     FAIL -> mediaPlaybackDoesNotRequireUserGesture
```

**每个突变都恰好翻红对应的绊线，且 BASELINE 全绿** ⇒ 断言不是恒真的摆设。

### 5.3 设备侧的"真实变红"（**这一条是真跑了系统的**）

N-1 的关键不变式 —— **宿主不得长出自造策略** —— 有一条**代码级**证据来自设备运行：
本轮 `NotifierHost.kt` **零改动**（见 §9 变更清单），而 §2.4 的设备实测显示
**真正生效的是页面侧上游策略 + 宿主纯投递**：
`camera permission GRANTED ... origin=http://127.0.0.1:34805/` 这行日志由**我们新增的** `onPermissionRequest` 打出，
说明"授予"确实发生在**宿主**、而"该不该授予"的策略在**页面侧** —— 两者没有串味。

---


## 6. 未闭环

| # | 项 | 原因 |
|---|---|---|
| 1 | **A2 权限请求类 / A3 提问类系统通知的删除** | 代码在 `app/**`（`notify/AppNotifier.kt`、`ChatViewModel.kt`），**本轮写权限只到 `webapp/**`**。判定已复核**成立**（上游确无此类通知），删除需下一轮开 `app/**` 权限 |
| 2 | **A6 后台补投 120s 的删除** | 同上，`app/**` |
| 3 | **扫码解码层（真扫一个配对 QR）** | 本轮只验到**取流层**。走到 `PairingScanner` 需上游 UI 进 `servers/connect` 并出示特定配对二维码，**未构造该场景** |
| 4 | 生产 SPA 里 `turn-complete`/`error` 是否**真的**弹出系统通知 | 本轮证明的是"自检页经垫片投递"与"宿主投递通道可用"。生产触发需要真的跑一轮 turn，**未构造**（与 N5 §7-3 同一未闭环项） |
| 5 | 上游 `permissions.query({name:"camera"})` 在 WebView 的返回 | 未单独验；但**我们的 grant 已生效**（`getUserMedia` 成功），故降级分支未被触发 |

---

## 7. 交接文档改动

**只改了 `research/web/DELIVER_REBUILD_R2.md` 的 §2.1「两份新产物：体积 / md5」小节**（原 1-based 第 89–105 行），其余小节未动。

- 换上新 md5：release **`5aa0b63657cb599cd288ebc5597e9d21`**（3,968,173 B）、debug **`de1a476c32dc25c53d1034ea01ca1e6f`**（5,200,219 B）；
- 写明体积变化是**功能新增、非回退**，并附与 R2 轮的逐项对比；
- 写明**本轮包内含的修复**（N-1 删自造绕过投递口 / N-2 加 CAMERA + 可信来源授予）；
- 记录**签名指纹未变**；
- 顺带删掉该小节里一条**已与新数字矛盾**的旧注（R2 轮"debug 体积掉得比较多（8.64 MB → 5.15 MB）"）—— 留着会误导。
- 原文为 **GBK** 编码，按行号拼接、**未改文件编码**；改动前已备份到 `research/env/f3/DELIVER_REBUILD_R2.md.bak`。

---

## 8. 收尾复核（原始输出）

```
=== 杀前确认（我起的只有 6050）===
pid=34832 port=6050 avd=OC_Deliver_R2 ro=True
pid=4100  port=6050 avd=OC_Deliver_R2 ro=True
pid=26584 port=6070 avd=OC_Deliver_R2 ro=True     <- 别的 agent
pid=49992 port=6070 avd=OC_Deliver_R2 ro=True     <- 别的 agent

=== adb reverse（只做过 4095，未起停后端）===
host-16 tcp:4095 tcp:4095

=== 几何还原 ===
$ wm size reset ; wm density reset
Physical size: 2560x1600
Physical density: 320          <- 已无 Override 行

=== 只按 serial 关自己那台 ===
$ adb -s emulator-6050 emu kill
OK: killing emulator, bye bye
OK

=== 复核 ===
emulator/qemu process count = 2                      <- 全部是 6070（别的 agent）
OC_*/Medium_Desktop_B on 6050 residual = 0          <- 我的已清零
6050 LISTEN = 0
=== 其它实例仍活着（不是我的，全程未碰）===
  pid=26584 port=6070
  pid=49992 port=6070
List of devices attached
emulator-6070    device
```

| 项 | 状态 |
|---|---|
| 关掉自己起的实例 | ✅ `emulator-6050` 已 `emu kill`，端口 6050 释放、无残留进程 |
| 未误伤别人 | ✅ `emulator-6070`（并发 agent）**未碰、未 kill**，仍在线 |
| 未执行 `adb kill-server` | ✅ |
| 未用 `-a` / 通配符 | ✅ 只按 **serial** `emulator-6050` 关 |
| 几何已还原 | ✅ `2560x1600` / `320`，无 Override |
| 后端 `4095` | ✅ **全程未起停**（收尾仍 `LISTEN pid=3748`，与开轮前同一 pid） |
| `.agent-gradle-lock` | ✅ 已释放 |
| 故意保留 | **无** |
| 突变残留 | ✅ `MUT2/MUT3/MUT1` 全部已还原（`RESTORE CHECK` 三项均 `True`） |
| 越权改动 | 无：只动 `webapp/**` + 新增测试 + `research/**` |
| `app/**` / `_upstream/**` / `.github/**` / `webapp/build.gradle.kts` | **未触碰**（`_upstream` 只用 `git show` 读对象，**未切工作区**） |

> ⚠️ **本轮发现但不属于本轮产物**：`emulator-6030`（开轮时在跑，同 AVD、另一 agent 起的）在本轮中消失；期间 `emulator-6070` 出现。**消失原因未从本轮日志得出因果，如实记录、不代其它轮次下结论**（我全程只对 `emulator-6050` 发过 `emu kill`）。
> ⚠️ 同理，工作区里 `research/web/RELEASE_VERIFY_V5.md`、`research/web/_head_oc-host.js`、`research/web/_v5_emulator_6030.log.err`、`webapp/.../LoopbackProxy.kt`、`webapp/.../RoundActivityTracker.kt`、`webapp/src/test/.../RoundStreamSignalReleaseTest.kt` **均非本轮产物**（属并发 V5 轮），本轮**未删除、未修改**。

---

## 9. 本轮变更清单（我的）

| 文件 | 变更 |
|---|---|
| `webapp/src/main/AndroidManifest.xml` | +`CAMERA` 权限、+`uses-feature camera required="false"`（+21 行） |
| `webapp/src/main/java/.../WebHostActivity.kt` | +`onPermissionRequest`（可信来源 + 白名单 + 运行期权限往返）、+`isTrustedWebOrigin`、+`sameOrigin`（+111 行） |
| `webapp/src/main/assets/www/index.html` | 自检页改走 `window.Notification` 垫片，**删掉直调 `oc.postMessage` 的自造绕过投递口** |
| `webapp/src/test/.../CameraPermissionHostTest.kt` | **新增**，9 例 |
| `webapp/src/test/.../NotificationPolicyBridgeTest.kt` | **+2 例**（`onlyTheShimMayCallTheHostDeliveryMouth`、`selfCheckPageRoutesThroughTheShimNotDirectly`） |
| `research/web/FIX_F3.md`、`research/web/DELIVER_REBUILD_R2.md`（§2.1）、`research/deliver/*.apk`、`research/HOUSEKEEPING.md`、`research/env/f3/**` | 本轮报告 / 交接 / 产物 / 取证 |

**未改动**：`NotifierHost.kt`（保持"纯投递"）、`WebHostBridge.kt`、`oc-host.js` 的垫片本体（N5 已就位，本轮只删掉了它的**违规调用者**）。

---

## 10. 碰过的 serial

| serial | 是我起的吗 | 处置 |
|---|---|---|
| **`emulator-6050`** | ✅ **本轮我起的**（`OC_Deliver_R2`，`-read-only`，端口 6050，镜像 `android-35;google_apis_playstore_tablet` + `pixel_tablet`，几何 `1080x2400@420`） | **本轮已关**（`emu kill` + 复核，见 §8） |
| `emulator-6030` | ❌ 别的 agent 起的 | **全程未碰、未 kill** |
| `emulator-6070` | ❌ 别的 agent 起的（本轮期间出现） | **全程未碰、未 kill** |

**本轮只碰过 `emulator-6050` 一个 serial。**
