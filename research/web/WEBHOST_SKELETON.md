# W1 — WebView 宿主模块骨架（`webapp`）

> **状态：完成。** `:webapp:assembleDebug` = **BUILD SUCCESSFUL**，产出可安装 APK。
> 角色：W1 Web 宿主工程师。用上游官方前端做界面 → **"一致"是默认属性**。
> 硬期限 2026-10-07。构建日志原文：`research/web/_build-webapp.log`。
> 上游契约来源（只读，未改）：`README-webview-legacy.md`、`frontend/patches/*`。
> 坑位来源（只读，未改）：`research/v77_ime_inset_belief/REPORT.md`、`research/v75_ime_space/REPORT.md`、`research/HANDOFF.md`。

---

## 1. 模块结构

```
webapp/
├── build.gradle.kts                       # applicationId com.xiufeigo.opencodeshell.web + ".debug"
├── proguard-rules.pro                     # 保住 @JavascriptInterface（release 现在不 minify）
└── src/main/
    ├── AndroidManifest.xml
    ├── assets/www/
    │   ├── index.html                     # ★ 本轮占位自检页（后续轮次换成上游 dist）
    │   └── oc-host.js                     # ★ 注入 facade，页面侧 window.__ocAndroid 的真身
    ├── res/
    │   ├── values/{strings,themes,colors}.xml
    │   ├── drawable/ic_launcher_web_foreground.xml
    │   ├── mipmap-anydpi{,-v26}/ic_launcher_web.xml
    │   └── xml/network_security_config.xml # ★ 明文放行范围（只回环/自建地址）
    └── java/com/xiufeigo/opencodeshell/web/
        ├── WebHostApp.kt                  # 建通知渠道 + 预热 SecretStore
        ├── WebHostActivity.kt             # WebView 宿主：assetLoader / 返回 / insets / 文件
        └── host/
            ├── WebHostBridge.kt           # @JavascriptInterface 原生对象（注入名 __ocAndroidNative）
            ├── InsetsHost.kt              # 边到边 + insets → CSS 变量 / JS 事件
            ├── NotifierHost.kt            # 通知：渠道 + postMessage + 点击回会话
            ├── FileChooserHost.kt         # SAF：选文件 / 存文件
            └── SecretStore.kt             # EncryptedSharedPreferences（明文不落盘）
```

**改动的既有文件：只有 `settings.gradle.kts`（加了一个 `include(":webapp")`）。**
`git diff --stat` = **5 insertions(+), 0 deletions(-)** — 干净的一个 include。

> ⚠ **行尾事故与修复（记录下来，因为它是可复现的坑）**
> 首次用编辑工具改 `settings.gradle.kts` 时，工具把整个文件重写成了 **CRLF**，
> 而本仓 `core.autocrlf=false`、无 `.gitattributes`、index 与工作树都以 **LF** 为准
> （对照未被我碰的 `app/build.gradle.kts`：`i/lf w/lf`）。
> 结果是 `git diff` 显示 **32 insertions / 27 deletions**（整文件被替换），而非 5 行。
> 判据是 `git ls-files --eol settings.gradle.kts` → `i/lf w/crlf`。
> 修复：**不用** `git checkout`/`git restore`（会连我的 include 一起回退，且等于用 git 改行尾），
> 而是用写文件工具按 **LF** 原样重写该文件。修复后 `i/lf w/lf`，diff 回到 5/0。
> **教训**：本仓任何既有文件的编辑后，都要跑一次 `git ls-files --eol <file>`，
> 出现 `w/crlf` 或 `w/mixed` 而 index 是 `i/lf` 就说明工具改了行尾，必须修回来。
> （本轮新建的 `webapp/**` 与 `research/web/**` 是新文件，行尾风格不影响 diff，无需处理。）

没有 `gradle/libs.versions.toml`（本仓不存在该文件，版本直接写在各模块 `build.gradle.kts`），所以未新增、也无需新增。
依赖版本**全部与 `:app` 相同**（core-ktx 1.15.0 / appcompat 1.7.0 / activity-ktx 1.9.3 / webkit 1.12.1 / security-crypto 1.1.0-alpha06 / coroutines-android 1.8.1），复用本地 Gradle 缓存，不引入新下载。

### 为什么不复用 `:app` 而是新模块

`applicationId` 不同 ⇒ 同一台设备上**两个独立 app**，可同时安装、同时运行、做 A/B 截图对比；互不覆盖安装、互不共享数据目录（SecretStore 也分开：`oc_webhost_secrets` vs 原生壳的凭据文件）。

---

## 2. 加载 URL 与 origin 证据

### 用的 URL

```
https://appassets.androidplatform.net/assets/www/index.html
```

常量：`WebHostActivity.kt:48`（`ASSET_DOMAIN`）、`WebHostActivity.kt:49`（`ASSET_PREFIX = "/assets/"`）、`WebHostActivity.kt:50`（`HOME_URL`）。

### 为什么用它（不是 `file://`）

| | `file:///android_asset/www/index.html` | `https://appassets.androidplatform.net/assets/www/index.html` |
|---|---|---|
| origin | `null`（opaque） | 真实 https origin `https://appassets.androidplatform.net` |
| 同源 `fetch` 自己资源 | 不可靠（`file://` 相对 fetch 语义各版本不一，且受 `allowFileAccess` 限制） | **可**（`shouldInterceptRequest` 由 AssetLoader 应答，页面侧就是普通同源请求） |
| `localStorage` / `sessionStorage` | 部分 WebView 版本在 opaque origin 下直接抛 `SecurityError` | **可**（`domStorageEnabled=true` + 真实 origin） |
| `EventSource` / SSE | `file://` 下无法建立（无 origin 概念） | **可**（同源相对 URL） |
| `crypto.subtle` / secure-context 特性 | 不可用（`file://` 非 secure context） | **可**（https 视为 secure context） |

结论：**真实 origin 是硬需求**，因为本项目的核心是"页面向 opencode 后端发 fetch / SSE"。`WebViewAssetLoader` 正是为这件事存在的：它让 assets 以 `https://appassets.androidplatform.net` 这个保留域 + 真实响应头（`Content-Type`、`Access-Control-Allow-Origin: *` 等）被应答，页面侧看到的就是普通 https 站点。

`setDomain(ASSET_DOMAIN)` 显式写出（不依赖默认值），`AssetsPathHandler(this)` 挂 `/assets/` ⇒ `assets/www/**` 映射到 `/assets/www/**`。
拦截点：`WebHostActivity.kt` 的 `HostWebViewClient.shouldInterceptRequest` → `loader(context).shouldInterceptRequest(request.url)`。

### 证据（占位页在设备上会自证）

`assets/www/index.html` 开页即打印四条判据：

1. `location.origin` 必须**正好**是 `https://appassets.androidplatform.net`（不等则红字报警）；
2. `location.protocol === "https:" && /^\/assets\/www\//.test(location.pathname)` ⇒ 证明 **AssetLoader 生效**（`file://` 不可能同时满足）；
3. `fetch("./oc-host.js")` 同源 fetch 本地资源，读回字节数 ⇒ 证明**同源 fetch 可用**；
4. `navigator.userAgent` + `EventSource` 存在性。

UA 由 `WebHostActivity.configureSettings` 追加标识：`… OpenCodeWebHost/0.1 (W1-skeleton)`，便于 logcat 里分辨是哪个壳。

> ⚠ **本轮无上机证据。** 本机无在线模拟器/设备（见 §6），以上四条是**设备上打开占位页即可复核**的自检项，不是本轮已跑出的截图。

### 与上游的一致性（重要）

`frontend/patches/*` 里，上游前端**已经**按这个 origin 写好了：常量 `ANDROID_ASSET_HOST = "appassets.androidplatform.net"`，且 `android-webview-no-origin-server.patch` 明确「asset origin 不当作 HTTP server」。桥名也已被上游约定为 `window.OpenCodeAndroid`（`android-webview-z-native-proxy.patch` → `packages/app/src/utils/android-proxy.ts` 读 `proxyOrigin()` / `proxyPrefix()`）。
**所以本轮"用上游官方前端 ⇒ 一致"不是巧合，是沿用同一套 origin 与桥名。**

---

## 3. 桥清单（JS 名 ↔ Kotlin 位置 ↔ 数据格式）

### 注入模型（两层，务必分清）

```
页面 ──► window.__ocAndroid          = oc-host.js 里的 facade（JS 门面，控制 onBack/事件/insets）
             │  直接转发
             ▼
         window.__ocAndroidNative    = WebHostBridge（@JavascriptInterface 原生对象）
```

`WebHostActivity` 用 `webView.addJavascriptInterface(bridge, "__ocAndroidNative")`（`WebHostBridge.INJECT_NAME`）注入原生对象；
facade `oc-host.js` 本身作为 **document-start script** 注册（`WebHostActivity.kt:164`，
`WebViewCompat.addDocumentStartJavaScript(wv, script, setOf("*"))`）。

**为什么不能把原生对象直接命名成 `__ocAndroid`**：任务要求 `window.__ocAndroid.onBack()` **返回 true 表示"页面已处理"** —— 这是"宿主问页面"的回调，只能由页面注册、宿主调用。若原生对象就叫 `__ocAndroid`，宿主回调它等于自问自答。故原生对象叫 `__ocAndroidNative`，facade 才叫 `__ocAndroid`。

**facade 全部成员**（`assets/www/oc-host.js`）：`version`、`hostInfo`、`hasNotificationPermission`、`requestNotificationPermission`、`postMessage`、`pickFile`、`readPickedFile`、`clearPickedFile`、`saveContent`、`share`、`getSecret`、`setSecret`、`setKeepScreenOn`、`setImeMode`、`getImeMode`、`reportTheme`、`onBack`、`setBackHandler`、`applyInsets`、`onInsets`、`getInsets`、`onOpenSession`、`setOpenSessionHandler`、`onFilePicked`、`onFileSaved`，
另加上游兼容入口 `window.OpenCodeAndroid.{proxyOrigin,proxyPrefix}` 与 `window.__ocAndroidProxy`。

| # | 需求 | JS 侧叫什么 | Kotlin 位置 | 数据格式 / 行为 |
|---|---|---|---|---|
| 1 | 通知 | `__ocAndroid.postMessage(title, body, sessionId)` | `WebHostBridge.kt:75` → `NotifierHost.postTurnFinished` | 入参全 String（`title`≤200、`body`≤2000、`sessionId`≤200 字符）。**返回 int**：`0`=已发出 / `-1`=无权限 / `-2`=其它失败 |
| 1 | 通知权限 | `hasNotificationPermission()` → `1`(已授权)/`0`(未授权)/`-1`(<13) | `WebHostBridge.kt:60` | — |
| 1 | 申请权限 | `requestNotificationPermission()` | `WebHostBridge.kt:69` → `WebHostActivity.requestNotificationPermission` → `ActivityResultContracts.RequestPermission` | fire-and-forget；结果以 `oc:permission` 事件 `{permission:'notifications',granted:bool}` 回到页面 |
| 1 | 渠道 | — | `NotifierHost.ensureChannels`（`WebHostApp.onCreate` + post 前各调一次） | 渠道 id `oc_turns`，「回合完成」，`IMPORTANCE_DEFAULT` |
| 1 | 点击回会话 | `__ocAndroid.setOpenSessionHandler(fn)` / 监听 `oc:openSession` | `WebHostBridge.deliverOpenSession:168` ← `WebHostActivity.onNewIntent`/`onCreate` | `PendingIntent` → `WebHostActivity`，extra `oc_session_id`（+`oc_from_notification`）；`singleTask` + `SINGLE_TOP\|CLEAR_TOP` ⇒ 复用实例回调 `onNewIntent`，**不叠 Activity**。页面收到 `{sessionId}` |
| 2 | 返回 | `setBackHandler(fn)`；宿主调 `onBack()` | `WebHostActivity.installBackHandler:178` | 顺序：`webView.canGoBack()` → `goBack()`；否则 `evaluateJavascript("__ocAndroid.onBack()")`；`true`=已处理。**两条都不成立时什么都不做 —— 无 `finish()` 分支**（`WebHostActivity.kt:193` 日志原文 `no finish`）。handler 返回非 true → 回落 `false` |
| 3 | 选文件 | `pickFile(accept, multiple)` → 事件 `oc:filePicked`；`readPickedFile()` | `WebHostBridge.kt:87`/`:93` → `FileChooserHost` | `readPickedFile()` 返回 JSON 字符串 `{uri,name,size,base64,truncated}` 或 `{error}`。>1 MiB 返回 `truncated:true` + 空 base64（**不**给半截内容）。同时仍回灌标准 `ValueCallback<Uri[]>` 供老页面用 |
| 3 | 存文件 | `saveContent(name, mime, base64)` → 事件 `oc:fileSaved` | `WebHostBridge.kt:101` → `FileChooserHost.launchSave` | `ACTION_CREATE_DOCUMENT`；回 `{ok:true,uri,bytes,mime}` 或 `{ok:false,error}` / `{ok:false,cancelled:true}` |
| 3 | `<input type=file>` | 无需 JS | `WebHostActivity.HostWebChromeClient.onShowFileChooser` | 走 `params.createIntent()`，SAF 结果回灌 `callback.onReceiveValue(Uri[])` |
| 4 | 分享 | `share(text, title)` | `WebHostBridge.kt:107` | `Intent.ACTION_SEND` + `text/plain`，经 `createChooser`。空文本返回 `false` |
| 5 | 凭据 | `getSecret(key)` / `setSecret(key, value)` | `WebHostBridge.kt:126`/`:129` → `SecretStore` | `getSecret` 返回 `string \| null`（空串视作"没有"）；`setSecret(k, null/"")` = 删键，返回 `boolean`。**EncryptedSharedPreferences**（key AES256-SIV / value AES256-GCM，主密钥在 Keystore）⇒ **明文不落盘**。Keystore 不可用时退化为**仅内存**偏好而非明文 |
| 6 | insets | CSS 变量 + `window.addEventListener('oc:insets', e=>e.detail)` / `onInsets(fn)` / `getInsets()` | `InsetsHost.publish` → `WebHostActivity.pushInsets:200` | 见 §4 |
| 7 | 屏幕常亮 | `setKeepScreenOn(on: bool)` | `WebHostBridge.kt:138` | 回合进行中传 `true` → `window.addFlags(FLAG_KEEP_SCREEN_ON)`；结束传 `false` → `clearFlags` |
| 8 | 主题 | `reportTheme(dark: bool)`；事件 `oc:theme` `{dark,scheme}` | `WebHostBridge.kt:163` → `WebHostActivity.applyPageTheme` | 页面把自己的 `data-color-scheme` 报上来，宿主据此 `applyBarsTheme()` 切系统栏图标明暗（深色 UI → 浅色图标），并关 API29+ 对比度遮罩（否则手势条变白杠） |
| — | 回环代理（**未实现**，接口先对齐） | `window.OpenCodeAndroid.proxyOrigin()` / `proxyPrefix()` | `WebHostBridge.kt:52`/`:55` | **本轮返回空串**。上游 `androidProxyBaseUrl()` 里有 `if (!origin || !prefix) return trimmed`，所以前端**无需改动**、行为退回"页面直连后端"。下一轮补代理时前端零改动 |
| — | 元信息 | `hostInfo()` | `WebHostBridge.kt:33` | JSON：`{appId,assetHost,homeUrl,scheme,proxyOrigin,proxyPrefix,maxInlineBytes}` |

**线程约定**：`@JavascriptInterface` 方法**不在主线程**（WebView 的 JavaBridge 线程）。所有碰 Activity/Window/Intent 的都 `runOnUiThread` 切回主线程；纯偏好/文件读写的留本线程。

### 安全注意

`addJavascriptInterface` 会把**整个原生对象**暴露给页面。本轮只暴露本模块自己的桥对象（无宿主 Activity 引用），且 `hostInfo` 不含任何凭据。若后续把 `WebHostActivity` 本身误传给 `addJavascriptInterface`，等于把宿主整个 API 面交给页面 —— 记为一条红线。

---

## 4. insets 与 IME：实现 + 坑位记录

### 实现

- **边到边**：`WindowCompat.setDecorFitsSystemWindows(window, false)`（`InsetsHost` init）。
- **读取**：`ViewCompat.setOnApplyWindowInsetsListener(root)`，取
  - `statusBars() or navigationBars() or displayCutout()` → 四个安全边；
  - `ime()` → `--oc-ime-bottom`。
- **px → CSS px = 1:1 dp**：`px / density`。前提是 WebView 无 `initialScale`/`viewport` 缩放（`useWideViewPort=false`、`loadWithOverviewMode=false`、禁 zoom）⇒ 1 CSS px == 1 dp。格式化成两位小数，避免浮点尾巴进 CSS。
- **发给页面（两条路，同时给）**：
  1. CSS 变量写 `:root`：`--oc-safe-top` / `--oc-safe-bottom` / `--oc-safe-left` / `--oc-safe-right` / `--oc-ime-bottom` / `--oc-window-height` / `--oc-ime-visible` / `--oc-scheme`，并设 `style.colorScheme`（让原生表单控件/滚动条也跟深浅色）。
  2. JS 事件 `oc:insets`，detail 结构：
     ```json
     {"safeTop":24.00,"safeBottom":48.00,"safeLeft":0.00,"safeRight":0.00,
      "imeBottom":0.00,"imeHeight":0.00,"imeVisible":false,
      "windowHeight":800.00,"imeMode":"vars","dark":true,"scheme":"dark"}
     ```
- **去抖**：payload 字符串变了才发（`InsetsHost.publish` 里 `payload != last`），避免每帧重排。
- **IME 策略可切**（本轮埋的 A/B 开关，页面 `__ocAndroid.setImeMode('vars'|'resize')`）：
  - `vars`（默认）：**只**发变量，WebView 铺满整窗，页面自己 `padding-bottom: max(var(--oc-safe-bottom), var(--oc-ime-bottom))`。
  - `resize`：额外把 WebView 固定高度压成 `窗口高 − max(nav, ime)`，让页面里的 `100dvh` / `position:fixed` 一起缩。
  取 `max(nav, ime)`：键盘一定比导航条高，重叠时 nav 已被 ime 盖住。

### ⚠ 坑位（本壳踩过一整套，后人别再踩）

1. **`ime.bottom` 可能是"僵死读数"，不是键盘。**
   `wm size`/`wm density` 覆盖后 reset：显示长回去了，**IME 窗口没重新排版**（仍是 `720x766` 的浮窗、停在 y≈797），平台按 `inset = 窗口底 − IME 窗顶` 上报 ⇒ v77 实测 **2195 px 上报 vs 766 px 实际**。一个 1140dp 的窗口被 836dp 的"键盘"压到只剩 209dp body，工具栏/tab 条被裁、composer 缩成 96dp 浮在半空。force-stop 重启后会重新读到同一个坏值（它属于**平台/IME 的窗口状态**，不是 app 缓存）。
   → 本模块的对策：**不"不信 ime"，而是把 `nav` 与 `ime` 两个变量都给出**，让页面自己取 `max()`；并附带 `imeVisible`，使"隐藏动画期间 ime 短暂非零"不会把布局永久压矮。（`:app` 的解法是 `believedImeBottomPx` 有界夹取，那是给 Compose 面板算预算用的；Web 侧让页面自己选更合适。）
   **可复现口径**：`adb shell wm size 720x1280` + `wm density 420` → 开会话、起键盘 → `wm size reset`（density 保留）→ 再起键盘。

2. **"IME 高度会随窗口变矮而塌缩"。**
   窗口一变矮（翻转、`dvh` 收缩、`resize` 模式压 WebView），平台给的 `ime.bottom` 就会**跟着变小**——因为它本质是"窗口底 − IME 窗顶"的差值，窗口底一抬，差值就缩。后果：任何"用 `ime` 高度当键盘真实高度"的布局都会在窗口变矮时**连锁跳变**（composer 高度阶梯、滚动锚点漂移）。
   → 两条纪律：① **不要把 `imeBottom` 缓存成"键盘多高"**，它只是"此刻被挡了多少"；② 任何依赖它的尺寸计算都要在**同一帧**内用同一份 inset 读完再算（`:app` 的 v77 修复就是这个形状：面板先量"把键盘放回去的盒子"，再判断键盘声称要多少）。
   本模块只做"把读数原样交付 + 同时给 nav/ime/visible"，把判断留给页面，就是为了让这个塌缩**可见可控**而不是在宿主里被写死。

3. **边到边让 `adjustResize` 变成 no-op。**
   manifest 里仍保留 `windowSoftInputMode="adjustResize"`（旧 WebView 无 compat 时唯一能触发 insets 的路），但 targetSdk 35 + `setDecorFitsSystemWindows(false)` 下它**不再**压窗口。所以宿主**不能**指望 adjustResize 送 ime insets，必须自己 `getInsets(Type.ime())`。（legacy 壳的结论原文："edge-to-edge makes `adjustResize` a no-op, so the root also pads `max(navigationBars, ime)`"。）
   **遗留分歧**：legacy 壳选择"宿主压 root"，因为上游对话框用 `100dvh` + `scrollIntoView`，只有视口真被压小才正常；本轮默认走"只发变量"。这正是 `setImeMode` 存在的原因 —— 下一轮上机 A/B 后二选一。**别在没量过之前把两套同时开**（会双倍顶高）。

4. **`dumpsys window insets` 怎么读**（读出 `ime` 那一段）：
   ```bash
   adb shell dumpsys window insets | grep -A6 'ime'
   ```
   要点：
   - 看 **`type=ime` 的 `frame=Rect(..., bottom=Y)`**，那个 `Y` 就是平台准备用来当 insets 的值；同一次输出里对照 `type=navagationBars` 的 `bottom`，两者**不同**才说明"为键盘预留"是真的（若两者相等，多半是 ime 没真正显示或僵死）。
   - `visibleFrame` / `animation` 段能看出**键盘是不是浮窗**（浮窗 IME 的 `frame.height` 会远小于 `ime.bottom` 的差值 —— 这就是坑 1 的现场特征）。
   - 若 ime 段长时间不变而 `visibleFrame` 在变，说明卡在"未被 resize 的窗口"路径上（坑 1）；`wm size reset` 后**可能要重启 IME 进程**才恢复。
   - 判"真键盘 vs 僵死"的量级参照：v77 的 2195px/1140dp 窗口 ≈ 吃掉 84% ⇒ 明显不合理；真键盘在正常窗口上不会长期超过 ~50%。

5. **`show_ime_with_hard_keyboard` 对调试的作用。**
   `adb shell settings put secure show_ime_with_hard_keyboard 1`（恢复 `0`）= **即使接着 USB/蓝牙硬键盘也强制弹出软 IME**。
   作用：让"走软键盘路径"和"走硬键盘路径"的代码**都能被执行到**，否则外接键盘设备上 `ime` insets 根本不发生，一整套 IME 逻辑（含上述塌缩、`restartInput`、滚动锚点）永远测不到。legacy 验证轮就靠它才在 5554 上走通真 IME 路径。
   ⚠ **它是"全局调试开关"，会改变设备上所有 app 的键盘行为**：开了之后 5554 的软键盘会常驻，别随手关（`research/HANDOFF.md` v66 条明确写过"5554 的软键盘现在是开的，别关"）；验收别的 IME 行为前先确认这个开关的当前值，否则结论不可比。
   配对用法：`adb shell settings get secure show_ime_with_hard_keyboard` 先读当前值（避免覆盖别人已开的调试态）。

6. **别给 WebView 自身加 view padding。** legacy 壳的结论：HyperOS 上 **WebView 的 view-padding 会被忽略**，必须在 Activity 的 root 上加。⇒ 本模块因此**完全不给 WebView 加 padding**，一律走 CSS 变量。

---

## 5. 构建证据

### 命令

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :webapp:assembleDebug --console=plain --no-daemon
```

### 锁纪律

`.agent-gradle-lock` 开工前检查 = **不存在**（无锁）→ 写入锁 → 前台跑完 → 释放。
释放用单条顶层 `rm -- ".agent-gradle-lock"`，输出 `mavis-trash: moved to trash: '.agent-gradle-lock'` → 复核 `Test-Path` = False ⇒ **LOCK RELEASED**。**未绕过任何本地硬安全策略。**

### 原文（成功那次）

```
BUILD SUCCESSFUL in 16s
```

前两次失败的原因（保留记录，`:webapp:compileDebugKotlin`）：
1. `ImeMode.Resize` 拼写（应为 `RESIZE`）；`Float.css()` 声明返回 `Double` 但 `String.format` 返回 `String`。
2. `FileChooserHost` 的 `companion object` 方法引用了实例属性 `activity`（companion 拿不到实例），且 `android.app.Activity` 上没有 `registerForActivityResult`（要 `ComponentActivity`）→ 改持 `AppCompatActivity` + 把 `describe/queryName/querySize/readInline` 降为实例方法。
3. `enum class ImeMode` 塞在 `companion object` 里跨文件 `InsetsHost.ImeMode` 解析不到 → 提到包级顶层 `enum class ImeMode`。

仅剩 2 条 warning，均为已废弃 API 且**故意保留**：
```
w: WebHostActivity.kt:123:9 'var databaseEnabled: Boolean' is deprecated.
w: InsetsHost.kt:105:20 'var isStatusBarContrastEnforced: Boolean' is deprecated.
```

### APK

| 项 | 值 |
|---|---|
| 路径 | `webapp/build/outputs/apk/debug/webapp-debug.apk` |
| 大小 | 4,523,950 bytes |
| **md5** | **`e83fe02e02b8aa66ba959005391c38e2`** |
| sha256 | `8300d7b58511bf4932216d020b6ccd6ab0ca24643dc88414834451741a972edd` |

`aapt2 dump packagename` ⇒ **`com.xiufeigo.opencodeshell.web.debug`**（与原生壳 `com.xiufeigo.opencodeshell[.debug]` 并存，互不覆盖）。

### manifest 实测（从 APK 里 dump 出来，不是源码）

```
android.permission.POST_NOTIFICATIONS
android:label=@0x7f0d001c                 (「OpenCode Web」)
android:hardwareAccelerated=true
android:usesCleartextTraffic=false          ← 全局不开明文
android:networkSecurityConfig=@0x7f100000   ← 明文范围另由该文件逐条限定
android:launchMode=2                        (singleTask)
```

APK 内资源（证明 assets 真打进去了）：
```
assets/www/index.html                    9477
assets/www/oc-host.js                    8760
res/xml/network_security_config.xml      1100
```

### 明文 / 混合内容策略（要求项）

- `android:usesCleartextTraffic="false"` + `networkSecurityConfig`：
  - `base-config cleartextTrafficPermitted="false"`（默认 deny 全局）
  - `domain-config cleartextTrafficPermitted="true"` 仅列 `127.0.0.1` / `localhost` / `10.0.2.2`（模拟器宿主）/ `10.0.3.2`（Genymotion），自建局域网地址**按需显式追加一行**（该配置**不支持 IP 段通配**，写 `192.168.x.x` 无效且会静默回落 https 失败）。
- `mixedContentMode = MIXED_CONTENT_COMPATIBILITY_MODE`（`WebHostActivity.kt:141`）：
  **理由** —— 宿主 origin 是 https，而后端常跑在 `http://192.168.x.x:4096`，必然是明文混合内容。`COMPATIBILITY_MODE` 让请求继续走（并尽量交给 CSP 判定）；`ALWAYS_ALLOW` 会**完全关掉**浏览器的主动拦截（含 XHR/fetch），`NEVER_ALLOW` 则整个自建调试流程不可用。明文范围另外由上面那份 network-security-config 收口。
  ⚠ 因此**唯一的闸门是那份配置**：页面内出现的任意第三方 http 目标都会走到网络层再被拒。改动那份配置时按"逐条显式"的原则，勿图省事开全局。
- 其余 WebSettings：`javaScriptEnabled=true`、`domStorageEnabled=true`、`allowFileAccess=false`、`allowContentAccess=false`、禁 zoom。硬件加速由 manifest `hardwareAccelerated="true"` 保证。

---

## 6. 未做 / 未闭环

1. **无上机验证。** 本轮没有可用的在线模拟器/设备，所以 §2 的四条 origin 判据、insets 实际数值、`oc:ready`、通知回会话、SAF 往返都**只有代码与编译证据，没有设备证据**。下一轮上机第一件事：装 APK、打开占位页、截图 origin/UA/AssetLoader/fetch 四行。
2. **回环反向代理未实现。** `proxyOrigin()`/`proxyPrefix()` 返回空串 ⇒ 页面**直连后端**，因此后端**必须**允许 `https://appassets.androidplatform.net` 的 CORS（`opencode serve --cors https://appassets.androidplatform.net`）。上一代 legacy 壳靠应用内 `LocalGateway`（OkHttp）剥掉 `Origin` 转发，所以不需要 CORS；**那个能力本轮没搬**。这是与 legacy 壳最大的功能差距，也是下一轮最该先补的（接口已对齐，前端零改动）。
3. **上游前端产物未进 `assets/www/`。** 现在是占位 `index.html`。塞入时：把 dist 放 `webapp/src/main/assets/www/`（`index.html` 在其根），facade 由宿主注入、**不需要**改上游产物。注意上游 `vite build --base ./` 的相对资源路径在 asset origin 下可用。
4. **IME 策略未定案。** 默认 `vars`；legacy 壳用的是"宿主压 root"，因为上游对话框依赖 `100dvh` + `scrollIntoView`。**须上机 A/B 后二选一，勿两套同开。**
5. **文件回传上限 1 MiB。** 超过则 `truncated:true`。正式做法应是像 `:app` 的 `LocalGateway` 那样"原生代理 + 一次性 token"（`ws://`/大文件走 token URL），而不是 base64 走 `evaluateJavascript`。
6. **未加自动化测试。** 本模块没有 JVM 测试（`:webapp:test` 无用例）。占位页的自检目前靠人工看页面。后续可加 `Robolectric` 或 instrumented 测试钉住"origin 常量 / 桥方法名 / CSS 变量名"这些契约。
7. **`allowCleartextTraffic` 的局域网白名单是空模板。** 只列了回环与模拟器宿主；自建后端 IP 需手工加行（见 `network_security_config.xml` 内注释）。
8. **release 未开 minify**，`proguard-rules.pro` 只是留位（已写好 `@JavascriptInterface` keep 规则，等真开 minify 时生效）。
9. **物理 ESC 键策略未做。** legacy 壳的结论是 ESC **必须透传给 WebView**（Vim/xterm/官方 keybind 要用），且 ESC **绝不** `finish()`。本轮只处理了系统返回键，ESC 走 WebView 默认行为（等价于透传），但**没有显式测试**。
10. **`app/**` 被并行 agent 改动，非本轮所为。** `git status` 显示 `app/src/main/java/.../SessionScreen.kt`、`header/ContextUsage.kt`、`review/ReviewPanel.kt` 为 modified，另有新增 `app/src/test/.../RightPanelParityTest.kt`。**本轮（我）未写入 `app/**` 任何文件**——我写入的路径只有 `webapp/**`、`research/web/**`、`settings.gradle.kts`（加一个 include）、以及临时 `.agent-gradle-lock`（已释放）。列出这些是为了不把别人的在途改动算到本轮头上。
