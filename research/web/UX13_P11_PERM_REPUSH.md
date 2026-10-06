# UX13-P11 —— 通知权限「页面重载即重推」收口（小修复 + 重建）

- 轮次：UX13-P11
- 仓库：`D:\XiaoZhuTouTou\opencode-android-shell`
- 基线：`bc87e72 feat(web): bundle official frontend with local http origin (Plan A, UX12)`
- 结论：**缺口已收口**。宿主改为在**每次页面加载完成**时按当前真值重推 `oc:notifyPerm`，
  P10 §4.5 记录的"重载后静态 permission 回落 `default` ⇒ 通知静默失效"窗口被消除。
  **本轮不做设备复验**（按任务书并入 P12）。

---

## 0. 背景（P10 遗留，直接采信）

P10 已端到端打通后台通知（`oc_turns` 通道真实落地、点击回会话成功），但留下一个缺口：

页面**重载**后（本例是**点击通知把 Activity 拉起 → 页面重载**），
`addDocumentStartJavaScript` 重跑 ⇒ `oc-host.js` 垫片重装 ⇒ 静态
`Notification.permission` 回落 `"default"`（P10 §4.5 实测原文）：

```json
{"vis":"hidden","focus":false,"perm":"default","hasSetter":"function"}   // 后台
{"vis":"visible","focus":true,"perm":"default"}                            // 前台
```

而上游 notify 闸门是**先读属性**、读到 `!== "granted"` 就直接 `return`；
闸门自身**不会**去调 `requestPermission` —— bundle 原文里那个 `await requestPermission`
分支只在"曾经申请过"的内部状态下才走。
⇒ 重载后通知**静默失效**，直到下一次宿主主动推送。

---

## 1. 修改（严格两处 + 绊线）

### 1.1 `webapp/src/main/java/.../WebHostActivity.kt`（唯一改动文件）

**(a) 新增真值读取 `notificationPermissionState()`**（紧邻既有 launcher 注册，L287-301）：

```kotlin
private fun notificationPermissionState(): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "granted"
    val granted = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
    return if (granted) "granted" else "denied"
}
```

真值来源与 P9 用过的通道**同源**：既有 `WebHostBridge.hasNotificationPermission()`
（`host/WebHostBridge.kt:65`）用的就是 `ContextCompat.checkSelfPermission` + `POST_NOTIFICATIONS`；
pre-33 分支与 `requestNotificationPermission()` 里 `"pre-13: no runtime permission needed"`
那个既有判断保持一致。**未新增任何权限查询 API**。

**(b) 新增重推 `pushNotificationPermission()`**（L303-321）—— 事件名/detail 与 P9 launcher
回调里那次派发**完全一致**：

```kotlin
private fun pushNotificationPermission() {
    val state = notificationPermissionState()
    Log.i(TAG, "P11 push oc:notifyPerm=$state")
    evaluateJs(
        "window.dispatchEvent(new CustomEvent('oc:notifyPerm'," +
            "{detail:'$state'}));"
    )
}
```

走既有 `evaluateJs` 辅助（`WebHostActivity.kt:673`），其内部即
`if (!::webView.isInitialized) return` + `runCatching { webView.evaluateJavascript(js, null) }`
⇒ 天然满足"未初始化时兜住"。

**(c) 在页面就绪路径调用**（`onPageFinished` 内，L1101-1107）：

```kotlin
// P11 · 权限真值重推：页面每次加载完成（首载 / 点通知把 Activity 拉起后
// 的重载 / 手动刷新）都会重装 oc-host.js 垫片，静态
// `Notification.permission` 随之回落成 "default"；而上游闸门是**先读
// 属性**（读到 != "granted" 就 return，闸门自身不会去调 requestPermission）
// ⇒ 不重推就会静默失效到下一次宿主主动推送。此刻垫片已装、监听器已在，
// 按宿主真值补推一次即可关掉这个窗口。
pushNotificationPermission()
```

### 1.2 选点理由：为什么落在 `onPageFinished`（先读代码后决定，非拍脑袋）

任务书给的两条路是「insets 投递 / `oc:ready` 消费处」与「`onPageFinished`」。读代码结论：

- `oc:ready` 在 `WebHostActivity.kt` 里**不存在**（全文 grep 无命中）——那条路不存在。
- 真正既有的页面就绪通道就是 `onPageFinished` 里的 **insets 补发**，而且它**正是同一类缺陷的
  既有修法**，可直接引为同类先例 —— 原文（`WebHostActivity.kt:1057-1060`）：

  ```kotlin
  // F18（缺陷 A）：首屏 insets 补发。
  // 第一次 publish 发生在 onCreate 的 requestApplyInsets()，那时页面还不存在，
  // evaluateJs 打在空文档上 ⇒ payload 丢失；顶部 inset 是静态的，之后再不变，
  // 去重逻辑又不会重发。此刻 documentElement 一定在了，显式补一次（不走去重）。
  ```

  权限重推与 insets 补发是**同一个形状的问题**（"首次投递打在还不存在的文档上 / 垫片还没装好，
  之后不再变故不会重发"），且**同一时刻**满足两个前提：垫片已装、`window` 上的事件通道已存在。
  ⇒ 选 `onPageFinished`，紧贴 `insets.replay()` 之后。

### 1.3 `oc-host.js`：**无需改**（本轮一个字都没动）

监听器在 P9 已在位（`oc-host.js:566-572`，原文）：

```js
try {
  window.addEventListener("oc:notifyPerm", function (ev) {
    var detail = ev && ev.detail;
    if (detail === "granted" || detail === "denied") {
      ShimNotification.permission = detail;
    }
```

**为什么重载后监听器必然自动重装**（引用注入机制原文，`WebHostActivity.kt:440-466`）：

```kotlin
* 把 facade（`assets/www/oc-host.js`）注册成**文档开始**脚本。
*
* 用 `WebViewCompat.addDocumentStartJavaScript` 而不是 `onPageFinished` 里
* `evaluateJavascript`：后者太晚，页面首屏脚本已经跑完、没拿到
* `window.__ocAndroid`。老 WebView 不支持该特性时退回 `onPageStarted` 注入
...
if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
    WebViewCompat.addDocumentStartJavaScript(wv, script, setOf("*"))
    Log.i(TAG, "facade registered as document-start script")
} else {
    wv.webViewClient = object : HostWebViewClient() {
        override fun onPageStarted(...) { view?.evaluateJavascript(script, null) }
```

`addDocumentStartJavaScript` 的语义就是**每个文档加载前必跑一次**，且 `setOf("*")` 覆盖全部 origin
⇒ 每次重载垫片连同监听器一起重装；`onPageFinished` 在其之后触发 ⇒ 事件派发时监听器**必定已在**。
两条分支（document-start / 老 WebView 的 `onPageStarted`）都保证监听器早于 `onPageFinished`。

---

## 2. 绊线：`NotificationShimTest` 追加 1 例（10 → 11）

| 用例 | 钉住什么 |
|---|---|
| `hostRepushesNotifyPermOnEveryPageLoad` | ①重推函数存在、走 `evaluateJs`、复用 P9 的 `oc:notifyPerm` 事件名；②真值来自 `ContextCompat.checkSelfPermission` + `POST_NOTIFICATIONS` + 保留 pre-33 分支（不能写死字符串）；③**核心** —— 调用点必须落在 `onPageFinished` 区间内；④调用点恰好 1 处 |

> 过程中修正了自己的**一个测试缺陷**（不是产品缺陷）：①段取子串用的锚点写成
> `"private fun onCreate"`，而真实签名是 `override fun onCreate` ⇒ `indexOf` 返回 -1、
> `substring` 会抛 `StringIndexOutOfBoundsException`；已改为 `"override fun onCreate"`。

### 2.1 反向验证（外科撤除 ⇒ 真红 ⇒ 恢复全绿）

**未整文件回退**（沿用 P9/P10 教训：`bc87e72` 早于 P7/P8，`compileDebugKotlin` 编译不过）。
先备份到 `research/tmp/p11_bak/WebHostActivity.kt`（md5 `535692470f8e2af7c752e19662c0974a`），
再**只摘掉 `onPageFinished` 里那一行调用及其注释**（两个 helper 保留）：

```text
NotificationShimTest > hostRepushesNotifyPermOnEveryPageLoad FAILED
223 tests completed, 1 failed
BUILD FAILED
EXITCODE=1
```

失败文案（取自测试源码原文；Gradle 的 XML 结果文件按平台 GBK 写盘、用 UTF-8 读会显示乱码，
故不在此处贴乱码版本）：

```text
java.lang.AssertionError: WebHostActivity.kt 必须在 onPageFinished（页面加载完成）里调用重推 ——
P10 实测：重载后静态 permission 回落 "default"，闸门读到非 granted 直接 return，不重推则通知静默失效
```

**恰好 1 条红、且就是本轮新增那条** ⇒ 证明红只来自新增，且断言真的在咬合这行代码。
其余 **222 条（含 P9/P10 的 10 条）全部仍绿**。恢复后 **223 全绿**。

---

## 3. 测试 + 重建 + 落盘

锁协议：`JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`、内联
`ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk`、`Start-Process` 脱离会话 + 轮询日志
（复用 P10 自建的 `research/tmp/run-gradle.cmd`，绕开 `%*` 不受 `shift` 影响的坑）。

```text
BUILD SUCCESSFUL in 54s
134 actionable tasks: 25 executed, 109 up-to-date
EXITCODE=0

suites=27 totalTests=223 totalFailures=0 totalErrors=0
```

**223 = 222 + 1**（新增 1 例）。`:webapp:assembleDebug / assembleRelease / assembleReleaseR8` 全过。

### 3.1 产物

| 文件 | 体积 | MD5 |
|---|---|---|
| `opencode-shell-web.apk` | 16867074 | `7df1fc50f39a1b9ed529f3d915541ca8` |
| `opencode-shell-web-release-nor8.apk` | 14306878 | `6650549efbbcdf39c89ef8b0cf218cf1` |
| `opencode-shell-web-release-r8.apk` | 12026852 | `dd26b0afc8a4a083da15e2d15bfb14ba` |

### 3.2 包内自查

- `assets/www/ui` = **1613** ✓（三个包逐个实测）
- **包内 `assets/www/oc-host.js` MD5 = `232e916b5b16e99a6d94c3c4561a9b8a`**（三个包）
  —— 与工作区源文件**逐字节一致**，也与 **P10 的 `232e916b…` 完全相同**
  ⇒ **本轮确实没有动过 `oc-host.js`**（开工前先量过一次 `232e916b…` 作文档基线，收工复量同值）。
  > 注：`git diff --stat` 对该文件显示 `92 insertions` 是 **P9+P10 的累计未提交改动**，不是本轮。
- 凭据扫描（`aapt2 dump strings`，build-tools `36.0.0`；`password="` / `sk-*` / `api_key="` /
  `Bearer …` / `token="` / `BEGIN PRIVATE KEY`）：debug 2404 / release 2404 / releaseR8 1322 条字符串，
  **总命中 0**（字符串条数与 P10 完全一致，说明注入面没有意外膨胀）

### 3.3 文件自检

- `WebHostActivity.kt`：U+FFFD 替换字符 **0**；**无 BOM**（首字节 `112,97,99` = `pac`）；行尾 **裸 LF 1257 行 / CRLF 0**（该文件本就是 LF，未被编辑工具混入 CRLF）。
- `NotificationShimTest.kt` 同样未引入 BOM/CRLF 混排。

---

## 4. 红线核对

- 未改 `ui/`：`git status --short -- ui` **空** ✓
- 未改 `app/**`：`git status --short -- app` **空** ✓
- 未 commit：HEAD 仍为 `bc87e72` ✓
- 未碰凭据/代理 ✓（只读凭据扫描）
- 未起停 4095 后端：收工复测 **pid 15096 仍在 LISTEN**，与 P10 记录的 pid 一字不差，全程只读未动 ✓
- 本轮**未起模拟器、未装包**（设备复验按任务书并入 P12）
- 锁已释放：本轮三次 gradle 均 `--no-daemon`，单次 daemon 在构建结束即停；
  收工时残留的 3 个 java 进程**启动时间为 17:19 / 17:19 / 18:21，早于本轮第一条命令（19:37）**，
  属前几轮遗留，本轮未新建、未遗留 ✓

---

## 5. 遗留（下一轮 P12）

1. **P12 做设备复验**：这是本轮唯一**未验证**的一环 —— 需实测"点通知回拉 → 页面重载 →
   `Notification.permission` 立刻回到 `granted`"（预期日志 `P11 push oc:notifyPerm=granted`），
   以及重载后再发一回合仍能出通知。
2. **P10 §4.5 另一半未分离**：重载后不通知还可能与"重载后上游 `hasOpenTab`/tab 注册表状态"
   （`notification.tsx:240`）有关。本轮修的是权限回落这一半；若 P12 复验后仍不出通知，
   按此线索继续分离。
3. `denied` vs `default` 的语义取舍（已在源码注释写明）：非 granted 时一律推 `denied`，
   对上游闸门等价（`!== "granted"` ⇒ return），且真拿到授权后 launcher 回调会再推 `granted`，不会卡死。
   若日后要更精确，可扩成三态。