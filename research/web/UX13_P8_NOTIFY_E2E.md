# UX13-P8 · 通知修复端到端复验（权限弹窗 → 后台通知 → 点击回会话 → 空闲断流回归）

- 执行时间：2026-10-06 18:28 ~ 19:00（CST）
- 仓库：`D:\XiaoZhuTouTou\opencode-android-shell`
- 后端：`127.0.0.1:4095` **全程未停、未重启、未 kill-server**（`pid=15096`，start 16:23:01，早于本轮 18:28）
- 设备：**本轮自建 AVD `Medium_Phone_API_37.0` @ 6790**（唯一碰过的 serial）
- 包：`research/deliver/opencode-shell-web.apk`（P7 批）
- **只验证，不改代码**（收尾 `git status` 与开轮时逐字一致，仍是 P7 那 7 个文件）

---

## 结论速览

| # | 验收点 | 判定 |
| --- | --- | --- |
| **A1** | 首次回合开始弹 `POST_NOTIFICATIONS` 授权框 | ✅ **PASS**（弹窗出现 + 真实点「允许」+ `granted=false → true, flags=[USER_SET]`） |
| **A2** | 回合进行中切后台：只派 `blur`、**不派** `pagehide`、SSE 存活 | ✅ **PASS**（两次独立后台窗口复现） |
| **A3** | `[oc-pow] round end session.execution.succeeded` **在后台窗口内到达**（判定性证据） | ✅ **PASS**（HOME 后 +3.0s / +0.5s 两次） |
| **A4** | 后台回合结束后补派 `pagehide` 收口 SSE（省电语义不丢） | ✅ **PASS** |
| **A5** | `dumpsys notification` 出现本包通知（渠道 `oc_turns`） | ❌ **FAIL**（渠道建了但 `mLastNotificationUpdateTimeMs=0`，**一条都没投出**） |
| **A6** | 点击通知回**对应会话页** | ⚠️ **无法验证**（依赖 A5；无通知可点） |
| **A7** | 空闲后台仍派 `pagehide` 断流、回前台 `pageshow` 重连 | ✅ **PASS**（UX8 省电语义未被 P7 改坏） |
| **A8** | 常亮回归（`flagSet` 回合内 true、终态秒级释放） | ✅ **PASS**（Δ=1.649s） |

**一句话**：P7 的 **SSE 保活**（A2/A3/A4）与**权限申请**（A1）两件事在设备上**都成立**；
但**通知仍然一条都发不出来**，卡点不在 SSE、也不在 Android 权限，而在**页内 notify 策略的 shim**（详见 §4）。

---

## 0. 环境与前置记录

### 0.1 APK 校验

```
Algorithm : MD5
Hash      : DEBB8C57C5A8FDC1E1E7FD3C29FB5797
Path      : D:\XiaoZhuTouTou\opencode-android-shell\research\deliver\opencode-shell-web.apk
```

预期 `debb8c57c5a8fdc1e1e7fd3c29fb5797` → **一致 ✅**

### 0.2 设备 / 工具

| 项 | 值 |
| --- | --- |
| adb | `C:\Users\xiufeigo\AppData\Local\Android\Sdk\platform-tools\adb.exe`（不在 PATH，需绝对路径） |
| emulator | `C:\Users\xiufeigo\AppData\Local\Android\Sdk\emulator\emulator.exe` |
| AVD | `Medium_Phone_API_37.0`，`-port 6790 -no-snapshot-load -no-boot-anim` |
| 启动前 `adb devices -l` | 空 |
| 启动前 6790 占用 | 无监听 |
| 设备 | `emulator-6790  product:sdk_gphone64_x86_64  model:sdk_gphone64_x86_64` |
| 系统 | `ro.build.version.sdk=37`（Android 17）、`ro.build.version.release=17` |
| `adb reverse` | `host-15 tcp:4095 tcp:4095`（收尾已 remove） |

### 0.3 后端只读探测（全程不开停）

| 请求 | 凭据 | 结果 |
| --- | --- | --- |
| `GET http://127.0.0.1:4095/` | 无 | **200** |
| `GET /api/config` | 无 | **401 Unauthorized** |
| `GET /api/config` | Basic `opencode:opencode` | **200** |
| `GET /api/event` | Basic `opencode:opencode` | 5s 超时不返回 = **SSE 正常保持** |

⇒ 后端 v2.0.24 **开着 basic auth**（`opencode`/`opencode`）。

### 0.4 步骤 0（起机装包）

- `sys.boot_completed=1`
- `pm clear com.xiufeigo.opencodeshell.web.debug` → `Success`
- `am start -n …/WebHostActivity` → `topResumedActivity=WebHostActivity t15` ✅
- **权限基线（`pm clear` 之后、任何回合之前）**：

```
android.permission.POST_NOTIFICATIONS: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]
```

⇒ denied 基线成立。

---

## 0bis. 🔴 阻塞 1：默认服务器条目指向 4173，而 4173 按设计拒绝 `/api/`

`am start` 后 Home 永远停在 `Loading`、计数恒 `0`，logcat 里 `/api/event` **每 ~1.015s 一次 `OPEN` 紧跟 `disconnected`**：

```
10-06 10:31:25.804 [oc-pow] event stream OPEN #113 /api/event @715
10-06 10:31:25.804 [global-sdk] event stream disconnected [object Object] @2
10-06 10:31:25.804 OcPerfJS res #262 api ... xfer=300 http://127.0.0.1:4173/api/event @19
10-06 10:31:26.818 [oc-pow] event stream OPEN #114 /api/event @715
```

`xfer≈300` = 只回了 401 的响应头，没有流。

**定位（只读源码）** —— `webapp/.../host/LocalUiOriginServer.kt:217`：

```kotlin
/**
 * 🔴 红线 3 的代码级落点：`/api/` 前缀（以及官方 connect 用到的任何后端路由）
 * 永远不由本机端点应答。
 */
internal fun refuseApiPath(path: String): Boolean {
    val p = normalize(path)
    return p == "/api" || p.startsWith("/api/") || p == "/event" || p.startsWith("/event")
}
```

⇒ **4173 不是回环代理**，它按设计拒绝一切 `/api/`（`LocalUiOriginServer.kt:64` 自陈：固定 4173 只是为了落在后端 CORS 白名单）。
⇒ 而 `pm clear` 后 App 内那条**默认服务器条目指向 `http://127.0.0.1:4173`**，于是所有 API 全部撞墙。

**复现与解决（只改 App 内服务器配置，没碰代码）**：设置 → Server → … → Edit，把地址改成 `http://127.0.0.1:4095`、密码填 `opencode`。
只填密码地址留 4173 时保存报 **`Could not connect to server`**；改成 4095 后立刻出现 `http://127.0.0.1:4095/api/info`、`/api/location`、`/api/project`、`/api/session/active`，上下文切换器里 4173 🔴 / 4095 🟢，切到 4095 后**会话列表正常加载**。

> **判定**：**环境/配置口径**问题（UX12 把页面 origin 改成 4173 后，App 内默认服务器条目没跟着改），**不是** P7 回归，也不是宿主缺陷。
> 复现口令：装包后不手动改服务器条目，App 必然卡 Loading + 1s 级抖动。

---

## 0ter. 🔴 阻塞 2（本轮最贵的一个坑）：步骤 0 漏了 `adb install`，前 12 分钟测的是**旧包**

首次发消息后权限弹窗**没出**。顺着查下去发现：

```
$ dumpsys package com.xiufeigo.opencodeshell.web.debug
    versionName=0.1.0-webhost
    lastUpdateTime=2026-10-06 10:04:10          ← 早于本轮 10:28 起机
```

把设备上的 `base.apk` pull 回来对比 md5：

| 对象 | MD5 |
| --- | --- |
| 设备上实际安装的 `base.apk` | `418F1A735EE02FDACEBD801E12F038A8` |
| 本轮要验的 `research/deliver/opencode-shell-web.apk` | `DEBB8C57C5A8FDC1E1E7FD3C29FB5797` |

⇒ **完全不是同一个包**。`Medium_Phone_API_37.0` 这个 AVD 是**既有**的（不是本轮新建），里面留着上一轮遗留的安装；本轮步骤 0 只写了 `pm clear → am start`，**没有 `adb install`**，于是 `pm clear` 之后跑的一直是旧二进制。

**为什么难发现**：旧包**大部分行为都正常**（会话列表、发消息、回合、常亮都能跑），只有 P7 新增的那两件事缺席：

- 旧包发消息时 logcat 有 `D1 applyKeepScreenOn: roundActive=true foreground=true …`，**但没有** `P7 proactive POST_NOTIFICATIONS request…`；
- 也**没有** `P7 onPause:` / `P7 background round ended` 两行。

（顺手做了个交叉验证：直接从 `research/deliver/…apk` 的 `classes*.dex` 里搜 P7 的日志串，四条全部 **FOUND**——确认 P7 代码确实在交付包里，不是打包漏了。）

```
P7 proactive POST_NOTIFICATIONS request       -> FOUND
P7 onPause: round active                      -> FOUND
P7 background round ended                     -> FOUND
round active -> keep SSE alive                -> FOUND
```

**补装**：`adb install -r research/deliver/opencode-shell-web.apk` → `Success`，`lastUpdateTime=10:39:06`。用 `-r` 是为了**保住刚配好的服务器条目**（`pm clear` 会把它一起抹掉）。装完 P7 的日志行立刻开始出现。

> **判定**：**流程缺陷**，不是产品缺陷。但它足以让整轮结论反号，必须写进报告。
> **建议**：后续每轮的「步骤 0」应把 `adb install -r` 写进命令序列，并在开跑前用 `dumpsys package | grep lastUpdateTime` 或 pull 回来比 md5 做一次**在位断言**。

---

## 1. 权限弹窗（A1）—— ✅ PASS

路径：连服务器(4095) → 切上下文 → 进会话 `v2 smoke test` → 真实发 `reply with the single word OK`。

宿主日志（**真包**）：

```
10-06 10:39:57.934  4832  4832 I OcWebHost: P7 proactive POST_NOTIFICATIONS request on first round start (foreground)
10-06 10:39:57.983  4832  4832 I OcWebHost: D1 applyKeepScreenOn: roundActive=true foreground=true shouldKeep=true flagSet=true
10-06 10:39:57.994  4832  4832 I OcWebHost: P7 onPause: round active -> keep SSE alive (blur only, no pagehide)
10-06 10:39:59.701  4832  4832 I OcWebHost: web[LOG] [oc-pow] round end session.execution.succeeded @715
```

> 顺带一个**有意思的副产物**：10:39:57.994 那条 `P7 onPause: round active` **不是**用户按 HOME 触发的，是**系统权限弹窗自己把 Activity 打到 onPause** 触发的。也就是说 P7 的保活判定天然覆盖了「弹权限框」这种系统级打断——这是好事。

**系统弹窗**（截图 `ux13p8_perm_dialog.png`）：`Allow OpenCode Web to send you notifications?` / `Allow` / `Don’t allow`。

**真实点击「允许」**（`adb shell input tap`，非 `pm grant`）后授权态跃迁：

```
# 之前（基线，pm clear 之后）
android.permission.POST_NOTIFICATIONS: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]
# 之后（点 Allow）
android.permission.POST_NOTIFICATIONS: granted=true,  flags=[ USER_SET|USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]
```

⇒ **denied → granted，USER_SET 是真·用户点击留下的痕迹** ✅

> 附带发现：弹窗锚定在**会话页**且该回合确实在进行中（`oc-pow round start` 与弹窗同窗），符合 P7 选「用户刚发消息」这个时机的设计。

---

## 2. 后台通知端到端（A2–A6）—— SSE 全对，通知没出来

### 2.1 后台窗口 #1（发送后 0.6s 按 HOME，静置 28s）

```
10:40:50.565  D1 applyKeepScreenOn: roundActive=true foreground=true shouldKeep=true flagSet=true
10:40:50.566  [oc-pow] keepScreenOn -> true busy=[__post__]
10:40:50.566  [oc-pow] round POST /api/session/ses_ef43fab08ffeoHiNxtinjW9V0c/prompt
10:40:50.645  [oc-pow] round start session.execution.started
────────────── HOME 键（主机 18:40:51.949）──────────────
10:40:51.432  P7 onPause: round active -> keep SSE alive (blur only, no pagehide)   ← 只派 blur
              （整个后台窗口内 **没有** 任何 pagehide / CLOSE）
10:40:55.194  [oc-pow] event stream OPEN #86 http://127.0.0.1:4173/api/event status=404   ← 4173 那条废流的噪声
10:40:55.360  [oc-pow] round end session.execution.succeeded                        ← ★ 判定性证据：后台窗口内到达
10:40:55.362  [oc-pow] keepScreenOn -> false busy=[]
10:40:55.435  P7 background round ended -> dispatch pagehide (release SSE)
10:40:55.450  [oc-pow] event stream CLOSE #50 http://127.0.0.1:4095 cancel
```

对照 P7 的设计意图逐条核：

| 期望 | 实测 |
| --- | --- |
| 回合进行中切后台 → **只派 `blur`** | ✅ `P7 onPause: round active -> keep SSE alive (blur only, no pagehide)` |
| 回合进行中**不派** `pagehide` | ✅ 后台窗口内 0 条 pagehide |
| 4095 流存活（OPEN 无中途 CLOSE） | ✅ 从 10:40:50 到 10:40:55.450 之间无 CLOSE；CLOSE 原因 `cancel` 且发生在**回合结束之后** |
| `round end succeeded` 在后台窗口出现 | ✅ HOME 后 **+3.4s** 到达 |
| 回合结束后补派 `pagehide` 收口省电 | ✅ `P7 background round ended` → `CLOSE … cancel` |

### 2.2 后台窗口 #2（复现，10:43:09.7 HOME）

```
10:43:08.632  D1 applyKeepScreenOn: roundActive=true foreground=true shouldKeep=true flagSet=true
10:43:08.710  [oc-pow] round start session.execution.started
────────────── HOME ──────────────
10:43:09.245  P7 onPause: round active -> keep SSE alive (blur only, no pagehide)
10:43:10.281  [oc-pow] round end session.execution.succeeded        ← ★ HOME 后 +0.5s 到达
10:43:11.247  P7 background round ended -> dispatch pagehide (release SSE)
10:43:11.257  [oc-pow] event stream CLOSE #92 http://127.0.0.1:4095 cancel
```

⇒ 两次独立窗口结论一致，**A2 / A3 / A4 稳定 PASS**。

### 2.3 通知本体（A5）—— ❌ 没发出来

`dumpsys notification --noredact`：

```
AppSettings: com.xiufeigo.opencodeshell.web.debug (10235) importance=DEFAULT userSet=true
  NotificationChannel{mId='oc_turns', mName=回合完成, mDescription=hasDescription, mImportance=3, ...
                      mLastNotificationUpdateTimeMs=0, ...}     ← 建了渠道，但从没往里投过东西
```

- 渠道 **`oc_turns` 存在**（`mName=回合完成`，importance=3）；
- 但 **`mLastNotificationUpdateTimeMs=0`** ⇒ **一次都没 post 过**；
- `dumpsys notification | grep NotificationRecord(` 全表只有一条 `com.android.systemui` 的 DND 快捷记录，**没有任何本包记录**；
- 下拉通知栏截图 `ux13p8_notif_shade.png`：只有系统自带的 `Settings · Virtual SD card`，**没有本包通知**。

### 2.4 点击回会话（A6）—— 无法验证

因为一条通知都没投出，**没有可点的对象**，`topActivity` / 页面判定无从谈起。**如实记为「未验证」**，不臆断。

---

## 3. 空闲断流回归（A7）—— ✅ PASS（UX8 省电语义未被改坏）

流程：回 Home（不在任何回合中）→ 静置 10s 确认空闲 → `KEYCODE_HOME` → 15s → 回前台。

```
10:46:02.574  [oc-pow] event stream CLOSE #179 http://127.0.0.1:4095 cancel
10:46:02.581  OcPerf  : K2.onReceivedError t=413992ms url=http://127.0.0.1:4095/api/event code=-1 desc=net::ERR_FAILED main=false
────────────── 后台 15s ──────────────
10:46:17.619  D1 applyKeepScreenOn: roundActive=false foreground=true shouldKeep=false flagSet=false
10:46:17.738  [oc-pow] event stream OPEN #235 http://127.0.0.1:4095/api/event status=200
```

| 期望 | 实测 |
| --- | --- |
| 空闲后台仍派 `pagehide` 断流 | ✅ `CLOSE #179 … cancel`（`cancel` = `AbortController.abort()`，正是上游 `pagehide → w()` 的指纹）+ `net::ERR_FAILED` |
| 该窗口内**没有** `P7 onPause: round active` | ✅ 空闲路径不打这条（只有回合进行中才打） |
| 回前台 `pageshow` 重连 | ✅ `OPEN #235 … status=200` |
| 列表/会话可正常加载 | ✅ 截图 `ux13p8_s3d_afterresume.png`：会话列表（`v2 smoke test` / `Greeting` / …）正常渲染 |

⇒ **A7 PASS**：P7 只在「回合进行中」改道 `blur`，空闲路径原样保留。

### 3.1 常亮回归（A8，顺手）—— ✅ PASS

复用 UX12 口径，看 `flagSet` 写入与释放：

```
10:43:08.632  roundActive=true  foreground=true  shouldKeep=true  flagSet=true    ← 回合开始置位
10:43:10.281  roundActive=false foreground=false shouldKeep=false flagSet=false   ← 终态帧释放
                                                                        Δ = 1.649s
```

秒级释放，无粘滞 ✅

---

## 4. 通知发不出来的卡点定位（对应步骤 4）

### 4.1 上游 notify 闸门原文

`ui/oa/index-D2r3WEwY.js`（`platform.notify`，只读摘录）：

```js
async notify(e, t, n) {
  if (!(`Notification` in window)
      || (Notification.permission === "default"
            ? await Notification.requestPermission().catch(() => `denied`)
            : Notification.permission) !== `granted`
      || document.visibilityState === `visible` && document.hasFocus())
    return;
  let r = new Notification(e, {body: t ?? ``, icon: `https://opencode.ai/favicon-96x96-v3.png`});
  r.onclick = () => { window.focus(), n?.(), r.close() }
}
```

### 4.2 页内 shim 原文

`assets/www/oc-host.js:521-551`（只读摘录）：

```js
if (!("Notification" in window)) {
  var ShimNotification = function (title, options) {
    var opts = options || {};
    var sid = currentSessionId();
    try { oc.postMessage(String(title ?? ""), String(opts.body ?? ""), sid); } catch (e) {}
    ...
  };
  ShimNotification.permission = "default";                 // ← 硬编码，永不更新
  ShimNotification.requestPermission = function () {
    var has = oc.hasNotificationPermission();              // ← 依赖全局 oc
    if (has === 1) return Promise.resolve("granted");
    if (has < 0) return Promise.resolve("denied");
    oc.requestNotificationPermission();
    return Promise.resolve("default");
  };
  try { window.Notification = ShimNotification; } catch (e) {}
}
```

### 4.3 只读注入打点（标注：只观测，不改任何行为；已 clearInterval 收尾）

`am start --es oc_debug_eval "b64:…"`（同步，非 async），结果打在 `OcWebHost/Eval`：

```
# 周期性探针（前台）
[oc-p8-probe] INSTALLED
[oc-p8-probe] vis=visible focus=true perm=default notifInWin=true      ← 10:42:50.425

# 同一个探针在后台窗口内（HOME 之后）
[oc-p8-probe] vis=hidden focus=true perm=default notifInWin=true      ← 10:43:10.181

# 一次性取值探针
OcWebHost/Eval -> "HASPERM=ERRReferenceError: oc is not defined \
                  SHIMPERM=default \
                  NOTIF=function (title, options) {\r\n      var opts = options || {};"
```

### 4.4 判定

| 闸门项 | 观测值 | 是否卡点 |
| --- | --- | --- |
| `'Notification' in window` | `true`（且 `String(window.Notification)` 就是 shim 本体，shim **确实装上了**） | 否 |
| `Notification.permission` | **`default`** | ⚠️ 触发 `requestPermission()` 分支 |
| `oc.hasNotificationPermission()` | **`ReferenceError: oc is not defined`** | ✅ **卡点** |
| `visibilityState` | 后台 `hidden` | 否（不构成 `visible && hasFocus`） |
| `hasFocus()` | 后台仍 `true`（WebView 怪癖），但因 `visibilityState=hidden` 故不触发早退 | 否 |

**卡点**：`ShimNotification.requestPermission()` 里第一行就是 `oc.hasNotificationPermission()`，而 `oc` 在页面里取不到 ⇒ **同步抛 `ReferenceError`** ⇒ 上游那句 `await Notification.requestPermission().catch(…)` 连 promise 都拿不到，异常直接穿出 `notify()` ⇒ 闸门早退 ⇒ `new Notification(...)` 从不执行 ⇒ 宿主 `NotifierHost` 永远收不到 postMessage ⇒ 渠道建了但没东西可投（`mLastNotificationUpdateTimeMs=0`）。

**定性：这是缺陷，不是预期语义。** 理由：

1. P6/P7 的全部推理都建立在「闸门只在终态帧评估、且帧能到」上；A3 已经证明**帧确实到了后台**（`round end succeeded` 后台可见），所以**上游策略的其余条件都不是这次失败的原因**；
2. 提示里猜的「会话 tab 未开」不成立——失败发生在 tab 是开着的、页面已 `hidden`、Android 权限已 granted 的情况下；
3. P7 代码注释自称「上游闸门里那条 `requestPermission()` 路径保持不变（**shim 会短路成 granted**）」，但 shim 的短路恰恰依赖 `oc`，而 `oc` 不可达 ⇒ **这层短路从未生效过**。

**未完全隔离的一点（据实说明）**：`oc` 的声明是 `oc-host.js:246` 的 `var oc = {…}`，而该文件经 `WebViewCompat.addDocumentStartJavaScript` 注入（`WebHostActivity.kt:441-453`）。`var` 在文档起始脚本里本应挂到 `window`，但探针取不到；究竟是**注入作用域问题**还是**注入时序/上下文问题**，本轮**没有再往下钉死**（时间预算 + 只验证不改代码的纪律）。不过**结论不受影响**：无论机制如何，**可观测结果（一条通知都没有）已被 dumpsys + 通知栏截图双重坐实**。

**建议下一轮（只提方案，本轮不动代码）**：
- 让 shim 的 `permission`/`requestPermission` 不再依赖可达性存疑的裸 `oc` 标识符（改走 `window.__ocAndroid` facade，注释里说它就是为此保留的），并让 `permission` 反映宿主实时授权态而不是硬编码 `"default"`；
- 给「Android 已 granted 但页内 `permission` 仍 default」补一条 tripwire 测试。

---

## 5. 顺带观察（非本轮验收项，如实记录）

1. **4173 废流持续 1s 级抖动**：App 内那条指向 4173 的服务器条目一直以 `status=404` + `NON2XX` 每秒重连一次，贯穿全程（`event stream OPEN #N http://127.0.0.1:4173/api/event status=404`）。它不干扰 4095 的业务流，但纯属无谓的网络与日志噪声。**建议**：把这条失效条目从默认里摘掉，或让 404 不再进入重连退避。
2. **`document.hasFocus()` 在后台仍报 `true`**：只靠 `visibilityState=hidden` 才没让上游闸门误早退。若将来上游收紧成「focus 为准」，后台通知会再次静默失效。值得盯。
3. **权限弹窗会触发 `onPause`**：系统弹窗自己把 Activity 打到后台，P7 的 `blur` 保活路径顺带覆盖了这种情况（见 §1）。这是好事，但意味着**「后台」不等于「用户按了 HOME」**，分析 onPause 时序时要记得这一条。

---

## 6. 收尾（Step 5）

| 项 | 结果 |
| --- | --- |
| `adb reverse --remove tcp:4095` | ✅ 执行，`adb reverse --list` 已空 |
| `adb -s emulator-6790 emu kill` | ✅ `OK: killing emulator, bye bye` |
| 只关自建 emulator-6790 | ✅ 未执行 `kill-server`，`adb devices -l` 收尾为**空** |
| 后端 `127.0.0.1:4095` | ✅ **pid=15096 未变**，`start=2026-10-06 16:23:01`（早于本轮 18:28），`GET /` → **200** |
| 代码零改动 | ✅ `git status --porcelain`（非未跟踪项）与开轮时**逐字一致**，仍是 P7 那 7 个文件：`AndroidManifest.xml` / `oc-host.js` / `WebHostActivity.kt` / `WebHostBridge.kt` / `network_security_config.xml` / `CameraPermissionHostTest.kt` / `Ux8KeepAwakeSseTripwireTest.kt` |
| 注入打点收尾 | ✅ `clearInterval(window.__ocP8)` 已执行 |
| gradle | 未触碰 |

**碰过的 serial**：`emulator-6790`（唯一，本轮自建；无其它设备在线）

---

## 7. 证据文件清单

| 文件 | 内容 |
| --- | --- |
| `research/web/p8_logcat_run1.txt` | 全程 logcat（`logcat -v threadtime`，起机即开） |
| `research/web/p8_STALE_installed.apk` | 从设备 pull 的**旧包**，md5 `418F1A73…`（阻塞 2 的物证） |
| `research/web/ux13p8_perm_dialog.png` | 系统权限弹窗（`Allow OpenCode Web to send you notifications?`） |
| `research/web/ux13p8_notif_shade.png` | 下拉通知栏 —— **无本包通知**（A5 FAIL 的物证） |
| `research/web/ux13p8_s2b_bgwindow.png` | 后台窗口 28s 结束时的设备截图 |
| `research/web/ux13p8_s3d_afterresume.png` | 空闲断流回归回前台后列表正常加载 |
| `ux13p8_nav*.png` / `ux13p8_s1*.png` | 导航与各步过程截图 |

---

## 8. 一句话复盘

P7 修的两件事——**回合中后台保活 SSE**、**首次回合主动申请通知权限**——在真机上**都按设计工作**，且后台 `round end succeeded` 确实到了；
但**通知一条都发不出来**，卡点已收敛到一处具体位置：`oc-host.js` 的 `ShimNotification.requestPermission()` 依赖的 `oc` 全局在页面里不可达，导致上游 notify 闸门在权限分支上同步抛 `ReferenceError` 而早退。
同时本轮暴露了两个**流程级**问题（默认服务器条目指向 4173、步骤 0 漏 `adb install`），它们各自都足以让整轮结论反号，建议写进下一轮的检查清单。
