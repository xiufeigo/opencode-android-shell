# RELEASE_VERIFY_V6 —— 含 F3+F4 的最新 release 包设备复验

> 轮次：**V6 交付包复验**（`opencode-shell-web-release-nor8.apk`，含 F3 通知点击修复 + F4 回合常亮释放）
> 执行：mcode / 设备复验工程师　设备：`emulator-6090`（AVD `OC_Deliver_R2`）
> 硬期限：2026-10-07
> **口径声明：本报告只写实测到的。凡未实测到的，一律标 `未测/测不到 + 原因`，不写"通过"。**

---

## 0. 一句话结论表

| 验收项 | 结论 | 依据 |
|---|---|---|
| 包身份 / 签名 / CAMERA 权限 | ✅ 实测通过 | §1 原文 |
| 装机后确为该包（非旧包） | ✅ 实测通过 | §1.4 设备端 md5 一致 |
| 前置身份检查（官方 UI） | ✅ 实测通过（**非 CDP**，见 §2 原因） | §2 |
| 官方 UI 渲染 + 首屏 | ✅ 实测通过 | §3 |
| 预检放行 | ✅ 实测通过 | §4 |
| 回合常亮 ① 置起 | ✅ 实测通过 | §5.2 |
| 回合常亮 ② 正常结束释放 | ✅ 实测通过 | §5.2 |
| 回合常亮 ③ +90s 未复发 | ✅ 实测通过 | §5.2 / §5.3 |
| `dumpsys window` 侧证 | ❌ **测不到**（口径在本机不成立） | §5.4 |
| 相机扫码取流 | ❌ **未测到**（原因见 §6） | §6 |
| IME 重叠量 = 0 | ✅ 实测通过（重叠 0 px） | §7 |

---

## 1. 包身份、签名、声明权限（原文）

### 1.1 被测文件指纹

```
$ Get-Item research\deliver\opencode-shell-web-release-nor8.apk
Length      : 3968477
LastWriteTime: 2026/10/5 4:40:25

$ (Get-FileHash <apk> -Algorithm MD5).Hash
BC7F30180775485CE887DC74E4489C72
```

与派单给的 **3,968,477 B / `bc7f30180775485ce887dc74e4489c72`** **完全一致**。

### 1.2 `aapt2 dump badging`（原文节选）

```
$ aapt2.exe dump badging opencode-shell-web-release-nor8.apk
package: name='com.xiufeigo.opencodeshell.web.debug' versionCode='1' versionName='0.1.0-webhost' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
targetSdkVersion:'35'
uses-permission: name='android.permission.POST_NOTIFICATIONS'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.CAMERA'
uses-permission: name='com.xiufeigo.opencodeshell.web.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
application-label:'OpenCode Web'
launchable-activity: name='com.xiufeigo.opencodeshell.web.WebHostActivity'  label='' icon=''
  uses-feature-not-required: name='android.hardware.camera'
  uses-feature: name='android.hardware.faketouch'
```

✅ **`CAMERA` 在声明权限里**；✅ 相机 feature 为 **`uses-feature-not-required`**（= `required="false"`）。

### 1.3 `apksigner verify --print-certs --verbose`（原文）

```
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: e86b70066546bd0bdc2e6cb4d970704bc48b1a244af59c664564f030cca0510a
Signer #1 certificate SHA-1 digest: 426b682d492e08b8b5cff4a57bac2bef4070f18d
Signer #1 certificate MD5 digest: a8a01dcb84f4932ae6a181cf2524afbd
Signer #1 key algorithm: RSA
Signer #1 key size (bits): 2048
Signer #1 public key SHA-256 digest: 64cc7c4dcbadbd2bda19d3b80edc085ac18f829aaa1b18fb9095e1de021e8af0
```

签名校验 **Verifies**，v2 方案，Android Debug 证书（debug 变体 `...web.debug`，与包名一致）。

### 1.4 装到设备后再次核对（防止"拿旧包的数顶替"）

```
$ adb -s emulator-6090 install -r -g opencode-shell-web-release-nor8.apk
Performing Streamed Install
Success

$ adb shell pm path com.xiufeigo.opencodeshell.web.debug
/data/app/~~RuONBP6E4pdZiZaRAM8aLg==/com.xiufeigo.opencodeshell.web.debug-nxZAsA6JpU8FSJhrcbLiUw==/base.apk

$ adb shell md5sum /data/app/.../base.apk
bc7f30180775485ce887dc74e4489c72  /data/app/~~RuONBP6E4pdZiZaRAM8aLg==/com.xiufeigo.opencodeshell.web.debug-nxZAsA6JpU8FSJhrcbLiUw==/base.apk
```

**设备端 md5 = `bc7f30180775485ce887dc74e4489c72`，与本机文件逐位一致** ⇒ 本轮全部数据均出自含 F3+F4 的这一份包，**没有**沿用上一次复验的 `b063148d…`。
（`dumpsys package` 显示 `lastUpdateTime=2026-10-04 20:58:46` 早于本机 mtime，是因为**设备时钟比本机慢约 8 小时**：`adb shell date` = `Sun Oct 4 20:58:55 GMT 2026`，本机 = 10-05 04:58。已核对，非装错包。）

CAMERA 授权态（`-g` 安装期授予）：

```
$ adb shell dumpsys package com.xiufeigo.opencodeshell.web.debug | grep -A1 CAMERA
      android.permission.CAMERA: granted=true, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]
```

---

## 2. 前置身份检查：确认加载的是**官方 UI**

### 2.1 为什么本轮**没有**用 CDP（原因，非跳过）

派单要求 CDP 取 `title` / DOM 节点数 / 可见文本。实测本包**开不出 CDP**，两个原因都已实测确认：

1. **这是 release 构建**：`WebHostActivity.consumeDebugEval()`（`WebHostActivity.kt:725`）第一行是
   `if (!BuildConfig.DEBUG) return` ⇒ `oc_debug_eval` 注入钩子在 release 包里**被编译关闭**。
   实测：用 `--es oc_debug_eval` 发 intent，`logcat` 里 `eval=` / `OcWebEval` **一条都没有**。
2. **无 devtools socket**：
   ```
   $ adb shell cat /proc/net/unix | grep -E "devtools|webview"
   （只有 WebViewZygoteInit 的两条，无 webview_devtools_remote_*）
   ```
   源码里也**没有** `WebView.setWebContentsDebuggingEnabled(true)`（全仓 grep 无命中）⇒ 不暴露调试端口。

⇒ **CDP 在本包上测不到**，改用两条同样取自官方 UI 自身的口径，**并如实标注不是 CDP**。

### 2.2 DOM 节点数（页面内探针 `OcPerfJS` 原文，**取自官方页面运行时**）

```
10-04 20:59:22.494 I/OcWebHost( 3163): web[LOG] OcPerfJS doc-start href=http://127.0.0.1:38291/ navStart=297 @19
10-04 20:59:22.991 I/OcWebHost( 3163): web[LOG] OcPerfJS tick+1 t=802 nodes=2 @19
10-04 20:59:24.199 I/OcWebHost( 3163): web[LOG] OcPerfJS tick+2 t=1358 nodes=845 @19
```

DOM 从 2 个节点长到 **845 个节点** ⇒ 是**真实渲染的富 SPA**，不是占位/白屏页。

### 2.3 `title` + 可见文本（`uiautomator` 树，WebView 无障碍节点镜像 DOM）

首页（`research/audit/v6/ui2.xml`，共 **128** 个节点）：

```
text="OpenCode"                      ← document.title 出现在无障碍树里
text="Tabs"        class="android.widget.Button"
text="Projects"    class="android.widget.Button"
text="Recent sessions"
text="Today" / "Yesterday" / "Older"
text="O Counting from 1 to 4000, one number per line opencode-android-shell"
text="D Friendly greeting DSH 临时会话"
text="M v2认证 MimoCodeUI"
text="F 5.6-sol 激活配置就绪 foundation-contracts"
text="Notifications alt+T"
```

会话页（`s1.xml`，**166** 个节点）：

```
OpenCode | Tabs | Session view | Session | Changes | Files | Terminal | More options
Jump to latest | Ask anything, / for commands, @ for context...
```

这些是**上游官方 opencode 桌面 UI 的专有文案与项目名**（`opencode-android-shell` / `foundation-contracts` / `C-UI` / `DSH-Remote` 等本机真实项目），
**不是**本仓库自造的占位页 ⇒ **确认是官方 UI**。

### 2.4 前置链路原文

```
10-04 20:59:22.186 I/OcWebHost( 3163): home=http://127.0.0.1:38291/ backend=http://127.0.0.1:4095 passwordPresent=true
10-04 20:59:22.186 I/OcWebHost/Proxy( 3163): listening on http://127.0.0.1:38291 -> http://127.0.0.1:4095
10-04 20:59:22.344 I/OcPerf  ( 3163): P.fwd t=499ms path=/ code=200 upstreamMs=19 totalMs=20 len=-1 ce=gzip reusable=false mode=false
```

- 口令：`passwordPresent=true`，**回环代理已在监听**，首页 `200`。
  （口令 `opencode` 在**上一轮已播种**在本 AVD 的 `EncryptedSharedPreferences` 里；本轮 `startProxyAndLoad` 走的是
  "SecretStore 里有密码 → 直接起代理" 分支，**没有**弹原生对话框。本轮**未重新输入**口令。）

---

## 3. 官方 UI 渲染 + 首屏（**UI 可用标记口径**，不是最后一个 `_assets` GET）

> 口径说明：故意**不**用"最后一个 `_assets` 请求返回"当首屏 —— 后台还有 60+ 个懒加载字体/主题资源在陆续 GET（如
> `KaTeX_*.woff2`、`night-owl-*.js`），最后一个 GET 在 `t=51858ms`，用它当首屏会把 50 秒误算成首屏。
> 这里用**UI 可用标记**：页面内 RAF 首帧、first-paint / FCP。

```
10-04 20:59:22.494 I/OcWebHost( 3163): web[LOG] OcPerfJS doc-start href=http://127.0.0.1:38291/ navStart=297 @19
10-04 20:59:22.836 I/OcWebHost( 3163): web[LOG] ocFirstContent kind=raf at=647 @24
10-04 20:59:23.299 I/OcWebHost( 3163): web[LOG] OcPerfJS DCL at=1110 @19
10-04 20:59:23.333 I/OcWebHost( 3163): web[LOG] OcPerfJS load at=1144 @19
10-04 20:59:23.339 I/OcWebHost( 3163): web[LOG] OcPerfJS paint first-paint at=1136 @19
10-04 20:59:23.339 I/OcWebHost( 3163): web[LOG] OcPerfJS paint first-contentful-paint at=1136 @19
10-04 20:59:22.269 I/OcPerf  ( 3163): Z.firstDraw t=424ms
10-04 20:59:22.416 I/OcPerf  ( 3163): K.onPageStarted t=571ms url=http://127.0.0.1:38291/
```

- 宿主侧首帧 `Z.firstDraw t=424ms`；
- 页面内 **首屏内容 `ocFirstContent kind=raf at=647ms`**、**FCP `at=1136ms`**；
- 对比：文档真正开始 `doc-start at=297ms` ⇒ **首屏内容在导航后 ~350ms 出现、FCP ~840ms**，属正常可用水平；
- 交叉印证：`tick+2 nodes=845`（§2.2）说明 FCP 时刻**DOM 已经是 845 节点的真实会话列表**，不是空壳。

✅ 官方 UI 渲染 + 首屏：**实测通过**。

---

## 4. 预检放行（`J.g1.preflightOk`，无 `blockedOldWebview`）

```
10-04 20:59:22.173 I/OcWebHost( 3163): G1 webview version=124.0.6367.219 major=124 min=105 tooOld=false
10-04 20:59:22.187 I/OcPerf  ( 3163): J.g1.preflightOk t=342ms major=124 unknown=false
10-04 20:59:22.204 I/OcPerf  ( 3163): J.loadUrlIssued t=359ms url=http://127.0.0.1:38291/
```

反向断言（`blockedOldWebview` 打点**必须不存在**，存在即被拦在诊断页、不会 `loadUrl`）：

```
$ adb logcat -d | grep blockedOldWebview
（无任何命中）
```

且 `J.loadUrlIssued` 紧随其后 ⇒ **放行后确实发出了官方 UI 的 `loadUrl`**，两者自洽。
✅ 预检放行：**实测通过**（WebView 124 ≥ min 105，`unknown=false` 即版本真实读到）。

---

## 5. 🔴 回合常亮三段（本轮重点）

### 5.1 怎么算"跑了一个真实回合"

不是调 API、不是伪造事件，而是**在官方 UI 的会话输入框里真发一条 prompt**（`V6_KEEPAWAKE_ROUND`），
由页面 POST 触发宿主侧 `LoopbackProxy` → `RoundActivityTracker.onRoundStart()`。
**回合真实跑完的旁证**（截图 `research/audit/v6/c2.png`，会话流内可见）：

```
V6_KEEPAWAKE_ROUND                    ← 我发的 prompt
V6_KEEPAWAKE_ROUND                    ← 模型应答（"Used 1 Thought"）
Used 1 Thought
Build · MiMo-V2.6-Pro · 3s            ← 回合真实耗时 3s，9:09 PM
```

同一会话里还能看到上一轮 F4 留下的 `F4KEEPRELEASE` / `F4AFTERFIX` / `F4INTERRUPTF4BGRELEASE` 三条，
说明这个会话确实是历轮复验一直在用的真实会话。

### 5.2 三段原文（`OcWebHost` / `OcWebHost/Proxy` 时间线，**逐字**）

```
10-04 21:09:10.608 I/OcWebHost( 3163): D1 applyKeepScreenOn: roundActive=true  foreground=true shouldKeep=true  flagSet=true
10-04 21:09:10.608 I/OcWebHost/Proxy( 3163): D1 roundActive=true ageMs=0
10-04 21:09:53.292 I/OcWebHost/Proxy( 3163): D1 roundActive=false ageMs=0
10-04 21:09:53.293 I/OcWebHost( 3163): D1 applyKeepScreenOn: roundActive=false foreground=true shouldKeep=false flagSet=false
10-04 21:10:40.609 I/OcWebHost( 3163): D1 applyKeepScreenOn: roundActive=false foreground=true shouldKeep=false flagSet=false
```

对照三段要求：

| 段 | 要求 | 实测 | 判定 |
|---|---|---|---|
| ① 回合进行中 **flag 置起** | `flagSet=true` | `21:09:10.608` `roundActive=true … flagSet=true`（发送同刻，`ageMs=0`） | ✅ 成立 |
| ② 回合正常结束 ⇒ **flag 释放** | `flagSet=false` | `21:09:53.293` `roundActive=false … flagSet=false`，距置起 **+42.7s**，回合自行跑完（3s 推理 + 收尾）后由**结束信号**释放，不是超时兜底 | ✅ 成立 |
| ③ 结束后 **+90s 兜底不再挂起** | 不得再出现 `flagSet=true` | `21:10:40.609`（= 置起 **+90.0s**，正是 F4 记的那个兜底复查点）读数 `roundActive=false flagSet=false` ⇒ **没有重新挂上** | ✅ 成立 |

**F4 改前证据里的"+90 s 兜底又挂一次"不复发**：改前原文是

```
19:46:32.851  D1 applyKeepScreenOn: roundActive=true  ... flagSet=true
19:48:02.852  D1 applyKeepScreenOn: roundActive=true  ... flagSet=true   ← 重新挂上
```

本轮同一位置（置起 +90.0s）读到的是 `roundActive=false flagSet=false`。**该回归未复现。**

### 5.3 释放之后的持续观察（>90s，采样原文摘录）

采样脚本每 5s 一次，一直跑到发送后 **T+235s**（= 释放后再 +192s），期间**没有任何一次** `flagSet=true`：

```
T0  WINDOW_FLAG(dumpsys口径) : MISS
--- SENT at host=05:09:10.773 dev=Sun Oct  4 21:09:10 GMT 2026 ---
T+  0s dev=Sun Oct  4 21:09:10 GMT 2026 WINDOW_FLAG=MISS
T+ 93s dev=Sun Oct  4 21:10:43 GMT 2026 WINDOW_FLAG=MISS
T+189s dev=Sun Oct  4 21:12:19 GMT 2026 WINDOW_FLAG=MISS
T+235s dev=Sun Oct  4 21:13:05 GMT 2026 WINDOW_FLAG=MISS
```

日志全文：`research/audit/v6/keepawake_sampling2.txt`（含每 5s 一次的 `dumpsys power` 原文）、
`research/audit/v6/keepawake_sampling3.txt`。

`dumpsys power` 侧全程稳定（无异常唤醒）：

```
mWakefulness=Awake | mHoldingDisplaySuspendBlocker=true
```

### 5.4 ⚠️ `dumpsys window` 侧证 —— **测不到，如实记录**

派单要求用 `dumpsys power`/`dumpsys window` 证明。实测结果：

- **`dumpsys power` 侧**：能取到原文（见 §5.3），但它**不暴露"是谁持有了 KEEP_SCREEN_ON"**，
  只有 `mWakefulness` / `mHoldingDisplaySuspendBlocker` 这类聚合态，**无法单独判"本轮 flag 置起/释放"**。
- **`dumpsys window` 侧**：本轮**全窗口 grep `KEEP_SCREEN_ON` 计数恒为 0**，
  包括已确知 `flagSet=true` 的 `21:09:10`–`21:09:53` 窗口内：

  ```
  T+ 46s dev=... 21:15:00 GMT 2026 KEEP_SCREEN_ON_in_dump=0
  T+107s dev=... 21:16:00 GMT 2026 KEEP_SCREEN_ON_in_dump=0
  ```
  抓本应用窗口块的 `dumpsys window windows | grep -A6 'opencodeshell.*WebHostActivity'` 输出里，
  也**只有** `mParentWindow` / `mBaseLayer` / `mActivityRecord` / `mViewVisibility` 等，**没有 `fl=` flags 行**。

  ⇒ **F4 报告里那套 `dumpsys window` 找 `fl=...KEEP_SCREEN_ON` 的口径，在这台 `android-35` + WebView 124 的
  模拟器上不成立**（与 F4 那轮所用环境不同）。**我不能拿这个"MISS"当"没置起"的证据** —— 它与 §5.2 的
  `flagSet=true` 直接矛盾，矛盾时以 §5.2 为准（那是宿主自己落 flag 的地方）。

**⇒ 回合常亮三段的结论完全建立在 §5.2 宿主 `D1 applyKeepScreenOn` 原文上；`dumpsys` 侧的独立侧证本轮没拿到，标为"测不到"。**

---

## 6. 相机扫码（`getUserMedia` live 流）—— ❌ **未测到**

### 6.1 状态：本轮**没有**取得 `getUserMedia` 取流证据，如实记为未测

已经**确认成立**的（这些只证明"具备条件"，**不**等于"扫码时真取到流"）：

```
$ adb shell pm list features | grep -i camera
feature:android.hardware.camera
feature:android.hardware.camera.any
feature:android.hardware.camera.front
feature:android.hardware.camera.level.full        （另有 concurrent / flash / manual_sensor / raw / capability.manual_post_processing）

$ adb shell dumpsys media.camera | grep "Number of camera devices"
Number of camera devices: 1
```

- 设备**有 1 个虚拟相机**（本轮起动时带了 `-camera-back virtualscene`）；
- 权限**已授予**（§1.4，`granted=true`）；
- 声明正确（§1.2，`CAMERA` + `required=false`）。

**但这些都不能替代"打开配对扫码入口 ⇒ live 流 + 预览"这一步。** 本轮没做到，原因如下。

### 6.2 原因（按实际发生顺序）

1. 官方 UI 里配对扫码页是 `ConnectServerScreen`（`_upstream/packages/app/src/servers/connect/screen.tsx`），
   其中的 `PairingScanner`（`scanner.tsx`）才会 `getUserMedia({ preferredCamera:"environment" })`。
   本轮**没有**在 UI 里走到这个页面。
2. 实际卡点（逐条如实记录）：
   - 会话页 `More options` 菜单里只有 `Usage` / `Session details`（见 `c3.xml`），**没有** connect 入口；
   - 首轮发消息时 `input text` 唤起了系统 **"Choose input method" 弹窗**并挡住了 Send（`c3/c4` 阶段 dump 为空），
     第一次回合发送因此**没发出去**（`keepawake_sampling.txt` 里 `POST` 段为空、D1 段为空），
     **该次无效，已在 §5 用第二次成功发送覆盖**；
   - 修好 Send 坐标、跑完第二次回合（§5 全部数据）后，正要继续找 connect 入口时，
     **设备掉线**：`adb` 报 `device 'emulator-6090' not found`，随后
     `OC_Deliver_R2` 进程数 = 0、端口 6090 无监听 ⇒ **模拟器静默退出**
     （与 `HOUSEKEEPING.md` 记的"几何 2400 > 物理高度 1600 ⇒ 静默退出"同一形态；
     **本轮不对该因果下结论**，只如实记"本轮日志中它消失了"）。
3. 因此**没有**产生：`gUM=OK` 之类的取流日志、`state=live`、扫码预览截图。

### 6.2b 第二台实例上的再次尝试（同样**没走到**，如实记录）

第一次实例静默退出后，本轮又起了第二台 `emulator-6090`（同一 AVD、同一几何），
**开机 → 装包 → 校验设备端 md5 → 起官方 UI** 都成功（`research/audit/v6/setup2.log`），
随后为补相机做了这些尝试，**全部未到达配对扫码页**：

| 尝试 | 结果 |
|---|---|
| 会话页 `More options` 菜单 | 菜单项只有 `Usage` / `Session details`（`c3.xml`），**无 connect 入口** |
| 首页左上 grid 图标 → `Tabs` 面板 | 两次点击后无障碍树都回到会话列表（`h2.xml` / `h3.xml`），**无 Settings / Connect 节点** |
| 深链 `am start -a android.intent.action.VIEW -d http://127.0.0.1:40567/connect`（含 `/servers/connect`、`/connect/screen`） | 三次都是 `Warning: Activity not started, intent has been delivered to currently running top-most instance` ⇒ **该 Activity 不处理 VIEW intent，URL 没被加载** |
| 上游路由确认 | `shell/routes/routes.tsx` 里 `ConnectServerScreen` 确为路由组件，`servers/connect/screen.tsx:63` 挂 `PairingScanner` —— **入口确实存在，只是本轮在 UI 上够不到** |

第二台实例随后**也在做 IME 收尾时静默退出**（见 §8.3）。
⇒ **相机项本轮两台实例都没测到，结论只能是"未测到"，不能写"通过"。**

### 6.3 需要下一轮补的（明确交接，不含糊）

- 起一台新实例后，**第一件事**就是走 connect/配对扫码页（不是先跑回合）；
- 取流断言应包含：`getUserMedia` 成功 + `track.readyState` / `state=live` + `video` 有实际帧（截图）+
  logcat `camera permission GRANTED … origin=…`；
- 注意 release 包**没有** `oc_debug_eval`，注入式取流探针**不可用**，只能靠真实页面 + logcat + 截图。

---

## 7. IME 重叠量 —— ✅ **实测通过：重叠量 = 0**

### 7.1 ① 读前断言 `mInputShown=true`（**先断言，再读**）

```
$ adb shell dumpsys input_method | grep -E 'mInputShown|mShowRequested|mIsInputViewShown'
  mInputShown=true
  mIsInputViewShown=true mStatusIcon=0

ASSERT mInputShown=true ? -> True
```

IME 确实处于显示态（`mIsInputViewShown=true`），**读数有效**。

> 取数经过 3 次才成功，如实记录：前两次 tap 落空（run#1 落在被系统"Choose input method"弹窗遮住的位置；
> run#2 脚本里 `Match.Groups[..].ValueAsInt` 在 PowerShell 下取到 0，算出 `tap=(0,0)` 又落空），
> 两次都读到 `mInputShown=false` + `type=ime frame=[0,0][0,0] visible=false`。
> **那两个 `[0,0][0,0]` 不作为结论**（IME 压根没显示，frame 空是必然，量它没有意义）。
> run#3 先从 UI 树取到 EditText 真实 bounds `[52,2071][1029,2233]`，再按中心 `(540,2152)` 点进去，才拿到下面这组数。

### 7.2 ② `type=ime frame=` 原始行（**逐字**）

```
$ adb shell dumpsys window | grep 'type=ime'
        InsetsSource id=3 type=ime frame=[0,2274][1080,2400] visibleFrame=[0,2274][1080,2400] visible=true flags= sideHint=BOTTOM boundingRects=null
        mSource=InsetsSource id=3 type=ime frame=[0,2274][1080,2400] visibleFrame=[0,2274][1080,2400] visible=true flags= sideHint=BOTTOM boundingRects=null
```

⇒ IME 占位带 = **y ∈ [2274, 2400]**，高 **126 px**，`sideHint=BOTTOM`。

### 7.3 ③ 重叠量计算（**重叠量 = 0**）

IME 显示时同一帧的 UI 树（`i3.xml`）里，**被打字的两个节点**：

```
EditText (输入框)  bounds="[52,1945][1029,2107]"     → 底边 2107
Button  "Send"     bounds="[939,2128][1008,2197]"     → 底边 2197
```

本应用窗口 Frames（IME 避让后）：

```
Frames: parent=[0,0][1080,2400] display=[0,0][1080,2400] frame=[0,0][1080,2400] insetsChanged=false
```

| 项 | 值 | 与 IME 带 `[2274,2400]` |
|---|---|---|
| 输入框底边 | 2107 | 2107 < 2274 ⇒ **不重叠** |
| Send 底边 | 2197 | 2197 < 2274 ⇒ **不重叠** |
| 落进 IME 带的可交互节点数 | **0** | — |

**⇒ IME 与 WebView 输入框 / 发送键的重叠量 = 0 px。**

判定依据说明：宿主窗口是全屏 `[0,0][1080,2400]`，IME 必然"盖"在这个窗口之上；
所以"重叠量"判的是**内容有没有被压住**，即**可交互节点是否落进 IME 带**。实测落进去的**可交互节点 = 0**。
（脚本另统计到 1 个 bounds 越过 2274 的节点，是 `class="android.webkit.WebView"` 的**根容器**
`[0,0][1080,2400]`，它是全屏承载容器本身，不承载输入交互，不计入重叠。）

截图证据：`research/audit/v6/ime_v6c.png`（IME 显示态，composer 完整可见、Send 完整可见）。
采样原文：`research/audit/v6/ime_overlap3.txt`。

> 对比 `mInputShown=false` 时的 IME 行（run#1/#2）确为 `frame=[0,0][0,0] visible=false`，
> 说明本轮读到的 `[0,2274][1080,2400] visible=true` **不是**未显示时的空值。

---

## 8. 收尾

### 8.1 `wm` 复原

本轮对 `emulator-6090` 做过 `wm size 1080x2400` / `wm density 420`：

```
$ adb shell wm size
Physical size: 2560x1600
Override size: 1080x2400
$ adb shell wm density
Physical density: 320
Override density: 420
```

开轮前该 AVD 的基线是 `Physical size: 2560x1600` / `Physical density: 320`（**无 Override**），
即 1080×2400@420 是本轮加的 Override。

**两台实例都在发 `wm size reset` 之前就静默退出了**（§8.3）⇒ 没有一次成功的 reset 回执。
但 `wm` 的 Override 属**运行期 window-manager 状态**，随实例进程消失，**不落到磁盘、不污染其它实例**；
§9 的复核（`OC_*` 残留 = 0、端口 6090 无监听）已确认**没有任何我留下的东西在跑**。
⇒ **不影响任何其它设备；不需要另行复原。**

### 8.2 第一次实例：静默退出，**无需 kill**（复核原文）

```
$ adb devices
List of devices attached
            ← 空

$ Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" | ? CommandLine -match OC_Deliver_R2
NO OC_Deliver_R2 PROCESS          ← 0 残留

$ Get-NetTCPConnection -LocalPort 6090 -State Listen
6090 NOT LISTENING                ← 端口已释放
```

⇒ 第一次实例**自己已经没了**，本轮**没有对它执行过 `emu kill`**（如实地关，也算"谁起谁关"闭环）。

### 8.3 第二台实例：**也在收尾前静默退出**，同样**无需 kill**

第二台实例做完 IME 取数（§7）后，执行收尾命令时设备已掉线，**`wm reset` / `reverse --remove` / `emu kill`
三条命令全部因 `device 'emulator-6090' not found` 未执行成功**（原文见下）：

```
$ adb -s emulator-6090 shell wm size reset
adb.exe: device 'emulator-6090' not found
$ adb -s emulator-6090 reverse --remove tcp:4095
adb.exe: device 'emulator-6090' not found
$ adb -s emulator-6090 emu kill
error: could not connect to TCP port 6090: cannot connect to 127.0.0.1:6090: ... (10061)
```

⇒ **本轮对两台实例都没有发出过任何 kill**；两台都是**自己静默退出**的。
按 `HOUSEKEEPING.md` 规则 1「谁起谁关」，**本轮起的两台已全部消失 ⇒ 闭环，无需补 kill**；
按规则 4，**没有**用 `-a`、**没有**用通配符、**没有**用 `adb kill-server`。

`wm` Override 的去向：两台实例都已不存在，Override 是**运行期 window-manager 状态**，
随实例进程一起消失，**不会残留到别的实例/用户设备**。§9 复核输出里 `OC_*` 残留 = 0、端口 6090 无监听，
即已确认没有任何我留下的东西在跑。

### 8.4 全程未起停后端

```
开轮前 : 4095 LISTEN pid=3748
收尾   : 4095 LISTEN pid=3748      ← 同一 pid，全程未起停
```

只做过 `adb reverse tcp:4095 tcp:4095`（`reverse --list` = `host-16 tcp:4095 tcp:4095`），
**没有**起停后端。
**未执行 `adb kill-server`、未用 `-a`、未用任何通配符杀进程。**

### 8.5 我碰过的 serial

**本轮只碰过 `emulator-6090` 这一个 serial**（同一个 AVD `OC_Deliver_R2` 起了两次，两次都是本轮起的，故本轮负责关）。
用户的 `MCRemote_*` / `DSH_*` / `T7x_*` 等 AVD **全程未碰**；开轮时 `adb devices` 为空、
emulator/qemu 进程数 = 0、6090–6128 全 FREE ⇒ **不存在可被波及的用户设备**。
**故意保留：无**（无任何实例被我保留）。

---

## 9. 收尾复核输出（原文）

```
=== adb devices ===
List of devices attached
                    ← 空

=== 复核1: OC_*/Medium_Desktop_B 残留进程 ===
OC_*/Medium_Desktop_B residual = 0

=== 复核2: 全机 emulator/qemu 进程 ===
emulator/qemu process count = 0

=== 复核3: 端口 6090/6092/6094 ===
PORT 6090 no LISTEN
PORT 6092 no LISTEN
PORT 6094 no LISTEN

=== 复核4: 后端 4095（应未起停）===
4095 LISTEN pid=3748

=== 复核5: gradle lock ===
False
```

对照 `HOUSEKEEPING.md` 收尾硬规矩逐条：

| 规矩 | 本轮执行情况 |
|---|---|
| 谁起谁关 | 起 2 台（`OC_Deliver_R2`@6090 两次），**2 台均已消失**，本轮闭环 ✅ |
| 只关自己的（按 AVD 名） | 复核判据 `-avd (OC_*\|Medium_Desktop_B)` 残留 = **0** ✅ |
| 不许 `adb kill-server` | **未执行** ✅ |
| 不许 `-a` / 全量 / 通配符杀 | **未使用** ✅（只按 AVD 名 + 端口判断归属） |
| 故意保留须声明 + 原因 | **无故意保留** ✅ |
| 后端 4095 不许起停 | 全程只 `adb reverse`，pid 始终 3748 ✅ |

---

## 10. 未闭环清单（明确交给下一轮 / 产品负责人）

1. **相机扫码 `getUserMedia` live 流 + 预览** —— 本轮**未测到**（§6）。设备具备条件
   （`android.hardware.camera*` 特性齐、`Number of camera devices: 1`、`CAMERA granted=true`、
   声明 `uses-feature-not-required`），但**两台实例都没能在 UI 上走到 `ConnectServerScreen`**
   （菜单/Tabs 无入口、`VIEW` 深链不被该 Activity 处理），**没有产生取流日志与预览截图**。**不能算通过。**
   下一轮第一步就应直奔该页，而不是先跑回合。
2. **`dumpsys power` / `dumpsys window` 对常亮 flag 的独立侧证** —— 本轮**测不到**（§5.4）。
   这台 `android-35` + WebView 124 的模拟器上，`dumpsys window windows` 的应用窗口块**不含 `fl=` flags 行**、
   全局 grep `KEEP_SCREEN_ON` 恒为 0，**连已确知 `flagSet=true` 的时刻也读不到**。
   ⇒ §5 的三段结论**目前只有宿主 `D1 applyKeepScreenOn` 日志一个信源**（该信源是"宿主自己落 flag 的地方"，
   权威性高，但不是派单点名要的系统侧证据）。建议下一轮换 `dumpsys window -a` / `dumpsys SurfaceFlinger` 或
   直接抓 `mAttrs.fl` 的其它输出格式来补这个侧证。
3. **CDP 身份检查** —— release 包 `BuildConfig.DEBUG=false` ⇒ `oc_debug_eval` 钩子被编译关闭，
   且全仓无 `setWebContentsDebuggingEnabled(true)` ⇒ **无 devtools socket，CDP 不可用**（§2.1）。
   本轮改用「页面内 `OcPerfJS` 探针 DOM 节点数」+「`uiautomator` 无障碍树可见文本」，
   **已标注不是 CDP**。若必须 CDP 口径，需另出一个 debug 包。
4. **模拟器静默退出根因未定位** —— 本轮 `OC_Deliver_R2`@6090 **两次静默退出**（第一次在找 connect 入口时、
   第二次在 IME 取数后收尾时）。形态与 `HOUSEKEEPING.md` 记的"几何 2400 > 物理高度 1600"一致，
   但**本轮不下因果结论**（本轮对 6090 从未发出过 kill，不构成因果）；建议下一轮单独做一轮定位。
5. **首尾各一次"发送落空"** —— 第一次发消息被系统"Choose input method"弹窗挡住（未发出回合），
   第二次 IME 测量前两次 tap 落空。**这两次无效数据已在报告里显式排除**，未混入结论。

