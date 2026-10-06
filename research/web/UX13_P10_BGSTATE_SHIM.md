# UX13-P10 —— 通知闸门最后一段：后台"未聚焦"页面状态 shim + 端到端收口

- 轮次：UX13-P10
- 仓库：`D:\XiaoZhuTouTou\opencode-android-shell`
- 基线：`bc87e72 feat(web): bundle official frontend with local http origin (Plan A, UX12)`
- 结论：**修复有效。设备端首次出现真实后台通知**（`oc_turns` 通道、`mLastNotificationUpdateTimeMs>0`、标题 `Response ready` / 正文 `Greeting`），点击通知**成功回到该会话**。P9 死结的最后一堵墙被撬开。

---

## 0. 根因（承 P9，已从 bundle 原文钉死，本轮直接采信）

上游 notify 闸门（`ui/oa/index-D2r3WEwY.js`）：

```js
if ((…Notification.requestPermission().catch(()=>'denied') : Notification.permission) !== 'granted'
    || document.visibilityState === 'visible' && document.hasFocus()) return;
let r = new Notification(...)
```

授权 granted（P9 已修）之外，**还要求"页面不是 可见且聚焦"**。而保活路径下 WebView 切后台只 pause 渲染/JS 时钟，**不改这两个属性/方法**：
`document.visibilityState` 恒 `"visible"`、`document.hasFocus()` 恒 `true`
⇒ `(visible && focused)` 恒真 ⇒ 闸门永远 `return` ⇒ 上游从未构造过 `Notification`。
这与 P9 设备实测计数器 `ctor=0` **完全吻合**。

---

## 1. 修改（严格两处 + 绊线；未碰 `ui/**`、未碰 `app/**`、未 commit）

### 1.1 `webapp/src/main/assets/www/oc-host.js`（注入件）

在 Notification shim 块之后新增"后台状态 shim"（`ocBackgrounded` 闭包状态 + setter）：

```js
var ocBackgrounded = false;
try {
  Object.defineProperty(document, "visibilityState", {
    configurable: true,
    get: function () { return ocBackgrounded ? "hidden" : "visible"; },
  });
  document.hasFocus = function () { return !ocBackgrounded; };
} catch (e) {}
window.__ocSetBackground = function (v) { ocBackgrounded = !!v; };
```

🔴 **只覆盖属性/方法，绝不派发 `visibilitychange` / `blur` / `focus` 事件**，这条约束连同理由写死在源码注释里：
P7 已证上游 pageLifecycle 监听 `visibilitychange` 且在 visible 时触发 resync → abort，会把保活路径下的 4095 SSE 掐死，"终帧必丢"的 P6 死锁原样复活。事件面由 `WebHostActivity.dispatchPageLifecycleEvent` 独占。

**文件自检**：本文件**没有 BOM**（首字节 `47,42,13` = `/*` + CR，块注释开头，属正常）；**CRLF 保持**（1372 行，裸 LF = 0）；**U+FFFD 替换字符 = 0**。全文 `visibilitychange` 只出现在注释里，**没有任何 dispatch 形态**。

### 1.2 `WebHostActivity.kt`

- `onPause()`（既有 `webView.onPause()` 之后）追加：
  `evaluateJs("window.__ocSetBackground && window.__ocSetBackground(true)")`
- `onResume()`（既有保活/空闲分支之后）追加：
  `evaluateJs("window.__ocSetBackground && window.__ocSetBackground(false)")`

两处**无差别设置**（保活路径与空闲路径都设）。走既有 `evaluateJs` 辅助函数，其内部即
`if (!::webView.isInitialized) return` + `runCatching { webView.evaluateJavascript(js, null) }` ⇒ 满足"未初始化时兜住"的要求。

---

## 2. 绊线：`NotificationShimTest` 新增 3 例（7 → 10）

| 用例 | 钉住什么 |
|---|---|
| `hostJsDefinesBackgroundStateShim` | `__ocSetBackground` 定义、`Object.defineProperty(document, "visibilityState"`、getter 返回 hidden/visible、`document.hasFocus` 被覆盖、`ocBackgrounded` 状态源存在 |
| `shimMustNotDispatchVisibilityOrFocusEvents` | **防 SSE 掐死**：不得出现 `dispatchEvent(new Event("visibilitychange")`（双/单引号两形）、不得派发 focus/blur；并**反向**断言源码里留有"绝不派发 visibilitychange"的注释，把约束钉在代码里 |
| `hostTogglesBackgroundStateInPauseAndResume` | `true`/`false` 各恰好 1 处、分别落在 `onPause`/`onResume` 区间内、两处都整句经 `evaluateJs(...)` 通道 |

> 过程中修正了自己的两个测试缺陷（不是产品缺陷）：① 一条正则断言过脆（转义），改为整句字面量断言；② 计数把**注释里**提到的名字也算进去，改为只数**完整调用语句**。

### 2.1 反向验证（外科撤除 ⇒ 真红 ⇒ 恢复全绿）

按 P9 的教训**没有整文件回退**（`bc87e72` 早于 P7/P8，`compileDebugKotlin` 编译不过），只精确摘除本轮新增的两处 shim 与两行调用：

```text
NotificationShimTest > hostTogglesBackgroundStateInPauseAndResume FAILED
NotificationShimTest > hostJsDefinesBackgroundStateShim FAILED
NotificationShimTest > shimMustNotDispatchVisibilityOrFocusEvents FAILED
10 tests completed, 3 failed
BUILD FAILED
EXITCODE=1
```

失败文案（原文）：

```text
[hostTogglesBackgroundStateInPauseAndResume] AssertionError: WebHostActivity.kt 必须在 onPause 里把页面置为后台：`__ocSetBackground(true)`
[hostJsDefinesBackgroundStateShim]          AssertionError: oc-host.js 必须暴露 window.__ocSetBackground —— 宿主靠它切后台/回前台读数
[shimMustNotDispatchVisibilityOrFocusEvents] AssertionError: shim 处应留有"只覆盖属性/方法、不派发事件"的注释，把 P7 约束钉在源码里
```

7 条既有 P9 用例在撤除状态下**仍全绿**（证明红只来自本轮新增）。从备份恢复后 **222 全绿**。

---

## 3. 测试 + 重建 + 落盘

锁协议：`JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`、内联 `ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk`、`Start-Process` 脱离会话 + 轮询日志（`research/tmp/run-gradle.cmd`，自建以绕开 `%*` 不受 `shift` 影响的坑）。

```text
BUILD SUCCESSFUL in 1m 12s
134 actionable tasks: 31 executed, 103 up-to-date
EXITCODE=0

suites=27 totalTests=222 totalFailures=0
```

**222 = 219 + 3**。`:webapp:assembleDebug / assembleRelease / assembleReleaseR8` 全部成功。

### 3.1 产物

| 文件 | 体积 | MD5 |
|---|---|---|
| `opencode-shell-web.apk` | 16840057 | `3191d82f8f715ab0443fe11cb02e6657` |
| `opencode-shell-web-release-nor8.apk` | 14306850 | `d2a2e859be46826a69bc61e4714b0dde` |
| `opencode-shell-web-release-r8.apk` | 12026808 | `c1d62415338a3ef690def7a3f35e6a07` |

**`oc-host.js` 新 MD5 = `232e916b5b16e99a6d94c3c4561a9b8a`**（P9 为 `afe15de09e858eb5d59173ad2bf1b3a`）。
APK 内 `assets/www/oc-host.js` 实测 MD5 与工作区源文件**逐字节一致**。

### 3.2 包内自查

- `assets/www/ui` = **1613** ✓
- 凭据扫描（`aapt2 dump strings`，逐包）：debug 2404 / release 2404 / releaseR8 1322 条字符串，**总命中 0**
  （注：首两次扫描因 build-tools 路径写错而"空跑"，已用真实存在的 `36.0.0` 重跑，上述为有效结果）

---

## 4. 设备端到端（emulator-6790 / Medium_Phone_API_37.0 / SDK 37 / 1080x2400）

`adb reverse tcp:4095` → **`install -r` Success** → `pm clear` → `am start`。
（`adb reverse tcp:4173` 报 "Address already in use"：4173 是应用**自身**的资源服务端口，前端 API 基址实际走 4173 页面 + 4095 后端，故在 UI 里另建了 `127.0.0.1:4095` 服务器条目并切换上下文。）

### 4.1 授权（真点 Allow）

```text
I OcWebHost: P7 proactive POST_NOTIFICATIONS request on first round start (foreground)
I OcWebHost: POST_NOTIFICATIONS granted=true
android.permission.POST_NOTIFICATIONS: granted=true, flags=[ USER_SET|USER_SENSITIVE_WHEN_GRANTED|... ]
```

### 4.2 决定性一轮：发消息 → 立刻 HOME → 等 25s —— ✅ **通知出来了**

保活路径与终帧都正常（P7 设计生效）：

```text
I OcWebHost: P7 onPause: round active -> keep SSE alive (blur only, no pagehide)
I OcWebHost: P6 dispatch synthetic blur (upstream pageLifecycle)
web[LOG] [oc-pow] round end session.execution.succeeded          <-- 后台收到终态帧
I OcWebHost: P7 background round ended -> dispatch pagehide
```

`dumpsys notification --noredact`（**抄原文**）：

```text
NotificationRecord(0x0408017e: pkg=com.xiufeigo.opencodeshell.web.debug user=UserHandle{0}
  id=1080752700 tag=null importance=3
  key=0|com.xiufeigo.opencodeshell.web.debug|1080752700|null|10235:
  Notification(channel=oc_turns ... flags=AUTO_CANCEL ...))

android.title=String (Response ready)
android.text=String (Greeting)

NotificationChannel{mId='oc_turns', mName=回合完成, mImportance=3, ...,
  mLastNotificationUpdateTimeMs=1791286212958, ...}     <-- >0
```

下拉通知栏截图（`research/tmp/p10-shade.png`）可见 **"OpenCode Web • now / Response ready / Greeting"**。

**真实点击通知** → 回到该会话：

```text
I OcWebHost: P6 dispatch synthetic pageshow (upstream pageLifecycle)
mCurrentFocus=Window{... com.xiufeigo.opencodeshell.web.debug/...WebHostActivity}
```

会话页显示 `Ping1` / `Ping2` 两条完整往返（截图 `p10-click.png`）。

> **ctor 计数说明（诚实交代）**：本轮没能拿到一个"包装计数"的 ctor 数字。原因有二：
> ① 上游在页面加载时**捕获了 shim 引用**，事后再替换 `window.Notification` 的探针**看不见上游的调用**（实测 `read:0`）；
> ② 更重要的是 `oc-host.js` 的 shim 构造函数是**唯一**通往系统通知的路径（ctor 内即 `oc.postMessage(...)`），因此 §4.2 那条真实落地的 `oc_turns` 通知**等价于 ctor ≥ 1**，且它是 P8/P9 以来第一次（那两轮为 `ctor=0` / 0 条通知）。

### 4.3 回归：回前台页面正常

`onResume` 触发 `pageshow`，composer **可输入**（截图 `p10-composer.png`，输入 `fg-ok`，发送按钮点亮）⇒
`visibilityState` 已恢复 `visible`、`hasFocus()` 恢复 `true`，**没有把页面永久留在 hidden**。

### 4.4 回归：空闲后台断流仍 PASS

不带回合直接 HOME，走空闲路径：

```text
I OcWebHost: P6 dispatch synthetic pagehide (upstream pageLifecycle)
web[LOG] [oc-pow] event stream CLOSE #2 http://127.0.0.1:4095 cancel
```

SSE 如期断开，省电语义未因 P10 丢失（UX8 验收项不变）。

### 4.5 ⚠ 遗留观察（本轮新发现，不影响上面的结论，但需下一轮处理）

用 DevTools 协议（`adb forward` → `webview_devtools_remote_<pid>` → `Runtime.evaluate`）读页面真值：

```json
{"vis":"hidden","focus":false,"perm":"default","hasSetter":"function"}   // 后台
{"vis":"visible","focus":true,"perm":"default"}                            // 前台
```

`vis/focus` 完全符合设计。但发现 **P9 的静态 `permission` 在页面重载后回落成 `"default"`**：
宿主只在 POST_NOTIFICATIONS launcher 回调那一刻派发一次 `oc:notifyPerm`；一旦页面重载（本例是**点击通知把 Activity 拉起 → 页面重载**），
垫片重新装成 `"default"`，此后要靠上游再次调用 `requestPermission()` 才会被 P9 的回写逻辑刷回 `granted`。

在此状态下追加的两轮插桩运行（ping3/ping4）**没有再出通知**（`read:2` 说明闸门确实读了权限、`ctor=0`）。
在剩余预算内未能把原因在"重载后静态权限回落"与"重载后上游 `hasOpenTab`/tab 注册表状态（notification.tsx:240）"之间彻底分离。
⇒ **建议下一轮专门做一轮：把 `oc:notifyPerm` 的推送改为宿主在每次页面 `oc:ready` 时按宿主真值重推**，消除重载窗口。

---

## 5. 收尾

- `adb reverse --remove-all` → 列表**已空**
- `emu kill` → OK；`adb devices` **serial 列表为空**；`emulator`/`qemu-system-x86_64` **进程数 = 0**
  （`Get-NetTCPConnection -LocalPort 6790` 仍计 1，是 adb 断开后的 TIME_WAIT 套接字残留，**无进程持有**）
- 后端 **pid 15096 未变**，4095 仍在 LISTEN；全程只读未动，未碰凭据/代理
- **未 commit**；未改 `ui/**`、未改 `app/**`

## 6. 产物落盘

- `research/deliver/opencode-shell-web.apk`（16840057 / `3191d82f8f715ab0443fe11cb02e6657`）
- `research/deliver/opencode-shell-web-release-nor8.apk`（14306850 / `d2a2e859be46826a69bc61e4714b0dde`）
- `research/deliver/opencode-shell-web-release-r8.apk`（12026808 / `c1d62415338a3ef690def7a3f35e6a07`）
- 证据截图：`research/tmp/p10-shade.png`（通知栏）、`p10-click.png`（点击回会话）、`p10-composer.png`（前台可输入）

## 7. 遗留（下一轮）

1. **P9 遗留**：静态 `permission` 页面重载后回落 `"default"` ⇒ 建议在 `oc:ready` 时按宿主真值重推 `oc:notifyPerm`。
2. 前端 API 基址走 4173 页面 + 4095 后端，而 `adb reverse tcp:4173` 冲突（被应用自身资源服务占用）—— 环境搭法值得固化。
3. 插桩探针（`window.Notification` 后置替换）对上游不可见；下一轮若要精确 ctor 计数，需在**页面加载前**注入。
