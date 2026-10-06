# UX13-P9 · 通知权限状态闭环修复（shim 静态 permission 永不更新）+ 设备复验

- 轮次：UX13-P9
- 仓库：`D:\XiaoZhuTouTou\opencode-android-shell`
- 基线：`bc87e72 feat(web): bundle official frontend with local http origin (Plan A, UX12)`
- 结论：**shim 缺陷已修复且设备实测生效**；但**上游闸门在更早的位置短路**，回合一��通知仍未投出（见 §5，带计数数据，不猜）。

---

## 1. 根因（承 P8，直接采信）

P8 实测：POST_NOTIFICATIONS 弹窗✅、后台 blur 保活✅、后台 `round end succeeded`✅ —— 通知仍 0 条。卡点收敛到自己的垫片：

- `oc-host.js` 的 `ShimNotification.permission` 硬编码 `"default"`，Android 授权后**没有任何路径**更新它；
- `requestPermission()` 只在被**调用**时才查宿主真值。上游闸门若**先读属性**（读到 `"default"` 才调 `requestPermission`），就永远卡死。

P8 探针的 `oc is not defined` 是探针作用域看不到 IIFE 闭包的**观测假象**，本轮未再沿此线走。

---

## 2. 修复点

### 2.1 `webapp/src/main/assets/www/oc-host.js`

**(a) `requestPermission()` 同步回写静态属性**（先查真值、再回写、最后才 resolve）：

```js
var has = oc.hasNotificationPermission();
// P9 root-cause fix: write the host truth back into the static property.
ShimNotification.permission = has === 1 ? "granted" : (has < 0 ? "denied" : "default");
if (has === 1) return Promise.resolve("granted");
if (has < 0) return Promise.resolve("denied");
```

原有返回逻辑一字未改（has===1 ⇒ granted；has<0 ⇒ denied；否则触发宿主申请并 resolve `"default"`）。

**(b) 垫片安装处新增宿主推送入口**（`window.Notification = ShimNotification` 之后）：

```js
window.addEventListener("oc:notifyPerm", function (ev) {
  var detail = ev && ev.detail;
  if (detail === "granted" || detail === "denied") {
    ShimNotification.permission = detail;
  }
});
```

注释写明：宿主在权限 launcher 回调里经 `evaluateJavascript` 派发该事件。

### 2.2 `WebHostActivity.kt`（POST_NOTIFICATIONS launcher 回调，269-285 行）

只加了两行派发，原有 `oc:permission` 回调逻辑未动：

```kotlin
Log.i(TAG, "POST_NOTIFICATIONS granted=$granted")
// P9: push the grant result into the page-side Notification shim so its
// static `permission` property stops reporting a stale "default".
evaluateJs(
    "window.dispatchEvent(new CustomEvent('oc:notifyPerm'," +
        "{detail:'${if (granted) "granted" else "denied"}'}));"
)
```

### 2.3 编码与红线

- 编辑过程中 `oc-host.js` 曾出现 3 个 U+FFFD 替换字符，已用 ASCII 注释重写该块并**逐行校验 U+FFFD 计数为 0**；文件保持 **UTF-8 无 BOM + CRLF**。Kotlin 文件 U+FFFD = 0。
- 未改 `ui/**` 官方资源、未改 `app/**`、未 git commit、未碰凭据/代理。

---

## 3. 绊线测试 `NotificationShimTest`（新建，7 例）

断言① `requestPermission` 回写静态属性：`requestPermissionWritesBackStaticPermission`、`writeBackHappensBeforeResolving`（校验 `has` 查询 < 回写 < resolve 的**顺序**）。

断言② `oc:notifyPerm` 事件闭环（**两边源码级**）：页面侧 `pageSideListensForNotifyPermEvent`、`listenerIsInstalledAtTheShimInstallSite`（校验监听器位于垫片安装块内、`window.OpenCodeAndroid` 兼容入口之前）；宿主侧 `hostDispatchesNotifyPermFromPermissionCallback`、`hostDispatchLivesInTheNotificationPermissionCallback`（校验派发落在 POST_NOTIFICATIONS launcher 回调体内）。

防回归 `permissionIsNotOnlyAHardcodedDefault`：静态赋值必须 ≥3 处。

### 3.1 反向验证（抄原文）

> **按预案先试 `git show bc87e72:...` 整体还原，两文件均无法编译** —— `bc87e72` 早于 P7/P8，其 `WebHostActivity.kt` 不含 `backgroundRoundRelease` / `backgroundKeepAlive` 等未提交接线，`compileDebugKotlin FAILED`。
> **改为外科式撤除 P9 三处新增**（保留 P7/P8 不动），实测等价：撤除后 `oc:permission/backgroundRoundRelease/backgroundKeepAlive` 标记均在。

```text
NotificationShimTest > pageSideListensForNotifyPermEvent FAILED
NotificationShimTest > writeBackHappensBeforeResolving FAILED
NotificationShimTest > hostDispatchLivesInTheNotificationPermissionCallback FAILED
NotificationShimTest > permissionIsNotOnlyAHardcodedDefault FAILED
NotificationShimTest > requestPermissionWritesBackStaticPermission FAILED
NotificationShimTest > listenerIsInstalledAtTheShimInstallSite FAILED
NotificationShimTest > hostDispatchesNotifyPermFromPermissionCallback FAILED
EXITCODE=1
tests=7 failures=7
```

失败消息（部分，XML 原文）：`未找到回写赋值语句` / `未找到 oc:notifyPerm 监听器` / `未找到 oc:notifyPerm 派发` / `ShimNotification.permission 至少要有 3 处赋值…实际只有 1 处`。

恢复后全绿。

---

## 4. 构建与产物

锁协议：`JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`，内联 `ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk`；长编译用 `Start-Process` 脱离会话 + 轮询日志，未被回收。

```text
EXITCODE=0
suites=27 totalTests=219 totalFailures=0
```

**219 = 212 + 7**（新增 7 例）。`:webapp:assembleDebug / assembleRelease / assembleReleaseR8` 全过。

### 4.1 三份产物

| 文件 | 体积 | MD5 |
|---|---|---|
| `opencode-shell-web.apk` | 16840057 | `1759452c0170a0c3f1b494a4bec382ff` |
| `opencode-shell-web-release-nor8.apk` | 14305662 | `90b9205b381d8895663904224bdf8c74` |
| `opencode-shell-web-release-r8.apk` | 12025760 | `25b2160c0f3a5d8a307db5e0c86dfdf0` |

### 4.2 包内自查

- `assets/www/ui` = **1613** ✅
- **`assets/www/oc-host.js` md5 = `afe15de09e858eb5d59173ad2bf1b3a6`**（已变；APK 内实测同值，与源码一致）
- ⚠️ **`oa` 不存在**：本包 `assets/` 下只有 `www` 一个顶层目录（`www/ui` 1613 + 5 个注入件）。Plan A（UX12）已把官方前端合并进 `assets/www/ui`，**没有独立的 `oa` 目录**，故 `oa=1590` 这一项**无法核对**，不是缺失文件。
- 凭据扫描（`password="` / `sk-*` / `api_key="` / `Bearer …` / `token="`）：**0 命中**。

---

## 5. 设备复验（emulator-6790）

`Medium_Phone_API_37.0` / 1080x2400 / d420 / SDK 37；`adb reverse tcp:4095` → **`install -r` 成功** → `pm clear` → `am start`。服务器 `http://127.0.0.1:4095`（opencode/opencode）经 UI 添加并切换上下文；会话 `Greeting`。

### 5.1 权限状态闭环 —— **修复生效**

真实弹出 POST_NOTIFICATIONS 对话框并点击 Allow：

```text
I OcWebHost: P7 proactive POST_NOTIFICATIONS request on first round start (foreground)
I OcWebHost: POST_NOTIFICATIONS granted=true
android.permission.POST_NOTIFICATIONS: granted=true, flags=[ USER_SET|… ]
```

授权后注入读回：

```text
{"perm":"granted","hasPm":"function","pmType":"function","notifPermFn":"function"}
```

**`Notification.permission` 由 `"default"` 变为 `"granted"`** —— 这正是 P8 钉死的缺陷，现已闭环；`__ocAndroid.postMessage` 为 `function`（桥在，类型正确）。

### 5.2 后台回合一例 —— **仍然没有通知**

刻意让回合先跑起来再 HOME：

```text
I OcWebHost: P7 onPause: round active -> keep SSE alive (blur only, no pagehide)
I OcWebHost: P6 dispatch synthetic blur (upstream pageLifecycle)
I OcWebHost: [oc-pow] round end session.execution.succeeded      <-- 后台成功终态帧
I OcWebHost: P7 background round ended -> dispatch pagehide
```

`oc_turns` 通道 `mLastNotificationUpdateTimeMs=0`，无本包 `NotificationRecord`。

**上游闸门计数（不许再猜，带数据）**：注入包装 `Notification` 构造 + `requestPermission` + `permission` 读/写计数，跨越一个完整后台成功回落后读数：

```json
{"ctor":0,"req":0,"read":2,"events":0,"curPerm":"granted"}
```

`read:2` 全部来自探针自身读取，**上游一次都没读过 `permission`、没调过 `requestPermission`、没构造过 Notification**。

⇒ 结论：卡点**不在** P9 修的静态属性，也**不在**权限本身 —— 上游 notify 闸门在**更早的位置**短路（早于 permission 判断）。对照 `oc-host.js` 注释里上游自身的闸门链，最可能是 `hasFocus()`（`coordinator.ts:62`）或 `hasOpenTab`（`notification.tsx:227,240`）先返回 false。

⚠️ **诚实边界**：计数器是页面加载后由探针替换 `window.Notification` 得到的。若上游在模块初始化时就把 `Notification` 捕获进模块作用域，计数会漏。**要把这条彻底钉死，应在垫片安装处原生埋点**，而不是运行时再包装（下一轮建议）。本轮不据此下最终结论。

### 5.3 宿主投递链路 —— **端到端可用**

直接构造验证投递链路（绕过上游闸门）：

```text
constructed:object perm:granted
mLastNotificationUpdateTimeMs=1791285021071        <-- >0，通道真的收到通知
NotificationRecord(0x0a218407: pkg=com.xiufeigo.opencodeshell.web.debug … importance=3)
android.title=String (P9 direct probe)
android.text=String (delivery test)
```

点击该通知 → `mCurrentFocus` 回到 `WebHostActivity`，UI 为 `Session view` —— 深链回会话正常。

⇒ **垫片 → `oc.postMessage` → `NotifierHost` → 系统通知 → 点击回会话，整条宿主链路无损**。加上 5.1 的 `perm:"granted"`，**P9 的修复是功能正确的**；剩下的是上游闸门策略问题，不是本宿主投递问题。

### 5.4 环境副产物

首轮默认模型 `GPT-5.6` 回 `Country, region, or territory not supported`（回合 failed，不产生成功终态帧）—— 这是模型区域限制，与通知无关。已切 `Ling 3.1 Flash Free` 后回合正常成功。

---

## 6. 收尾

- `adb reverse --remove tcp:4095` → OK
- `emu kill` → OK；`adb devices` **serial 列表为空**，emulator 进程 0，端口 6790 监听数 **0**
- 后端 pid **15096** 未变、仍在运行，`/api/info` 返回 **401**（鉴权挑战 = 服务健康，符合只读探测预期）
- 未 commit，未改 `ui/**` / `app/**`

## 7. 遗留（下一轮）

1. 上游闸门短路的**确切位置**：建议把计数埋点直接写进 `oc-host.js` 垫片安装处（原生地包 `hasFocus` / `visibilityState` / 构造计数），绕开"模块作用域捕获"这一观测盲区。
2. `oa=1590` 期望值需与 Plan A（UX12）现状对齐 —— 包内已无该目录。